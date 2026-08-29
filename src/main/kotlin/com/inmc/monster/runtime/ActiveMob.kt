package com.inmc.monster.runtime

import com.inmc.monster.affix.Affix
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.mob.StatMap
import com.inmc.monster.pattern.Phase
import com.inmc.monster.skill.SkillInstance
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.entity.LivingEntity
import java.util.UUID

/**
 * A custom mob that is alive right now.
 *
 * Everything mutable about a spawned mob lives here rather than in the entity's persistent data.
 * The PDC is written exactly once, at spawn, and is only ever read back by the cleanup sweep -
 * so the hot paths (damage, skills, nameplates) cost a map lookup instead of an NBT read.
 *
 * There is deliberately no way to reconstruct one of these from an entity alone. The server's
 * policy is that custom mobs do not survive a restart, and a half-restored boss - stats intact,
 * phase and skill timers gone - is worse than no boss at all.
 */
class ActiveMob(
    val uuid: UUID,
    val entity: LivingEntity,
    /** Null for a plain vanilla mob that only carries affixes. */
    val definition: MobDefinition?,
    val affixes: List<Affix>,
    val level: Int,
    /**
     * Final stats after level scaling, affixes and equipment.
     *
     * Recomputed when the definition is edited, so a mob already standing in the world picks up
     * the change instead of keeping the numbers it happened to spawn with.
     */
    var stats: StatMap,
    /**
     * How many spawn hops from a player-triggered spawn this mob is.
     *
     * A mob summoned by a skill or created by another mob's death action carries its parent's
     * value plus one. The spawn service refuses anything past the configured ceiling, which is
     * what stops "A spawns B on death, B spawns A on death" from taking the server down.
     */
    val generation: Int,
    /** Free-form owner tag, used by the dungeon API to find and clean up its own mobs. */
    val tag: String?,
    /** Display name with affixes already folded in, e.g. "강력한 욕심많은 좀비". */
    val displayName: String,
    val spawnedAt: Long = System.currentTimeMillis(),
) {

    /** Skills from the definition plus any the affixes granted, flattened from the definition. */
    var skills: List<SkillInstance> = flattenSkills()
        private set

    val threat = ThreatTable()

    /** Cooldown expiry, in server ticks, indexed to match [skills]. */
    private var cooldowns = LongArray(skills.size)

    /** ON_LOW_HEALTH fires once per skill, not once per tick below the threshold. */
    private var oneShotFired = BooleanArray(skills.size)

    private fun flattenSkills(): List<SkillInstance> =
        (definition?.skills.orEmpty() + affixes.flatMap { it.skills }).filter { it.enabled }

    /**
     * Re-reads the skill list after the definition was edited.
     *
     * Cooldowns are keyed by position, and an edit can insert, remove or reorder entries, so the
     * timers are rebuilt rather than carried over - reusing them would apply one skill's
     * remaining cooldown to whatever ended up in its slot. Losing a partial cooldown is a far
     * smaller surprise than a skill that refuses to fire for no visible reason.
     */
    fun refreshSkills() {
        skills = flattenSkills()
        cooldowns = LongArray(skills.size)
        oneShotFired = BooleanArray(skills.size)
        castingSkill = -1
        patternCursor = 0
        patternNextTick = 0L
    }

    var phase: Phase? = null
        private set

    /** Index into the current phase's step list. */
    var patternCursor: Int = 0

    /** Server tick at which the next pattern step is due. */
    var patternNextTick: Long = 0L

    var bossBar: BossBar? = null

    /** Tick at which the mob despawns on its own; 0 = no lifespan. */
    var expiresAtTick: Long = 0L

    /** True once the death handler has run, so a double DeathEvent cannot double-drop. */
    var deathHandled: Boolean = false

    /** True when a ModelEngine model was attached, so the budget can count these separately. */
    var modelled: Boolean = false

    /** Set while a cast time is winding up; cleared when it lands or is interrupted. */
    var castingSkill: Int = -1
    var castCompleteTick: Long = 0L

    /**
     * Where [MobTracker] filed this mob, recorded at registration.
     *
     * Kept so removal can go straight to the right bucket. Searching every bucket instead would
     * be O(chunks ever used), and on a long session that grows without bound while despawns
     * happen constantly.
     */
    internal var indexWorld: String? = null
    internal var indexChunkKey: Long = 0L
    internal var indexTag: String? = null

    val isVanillaWithAffix: Boolean get() = definition == null

    val isAlive: Boolean get() = entity.isValid && !entity.isDead

    fun healthPercent(): Double {
        val max = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: return 100.0
        if (max <= 0.0) return 100.0
        return (entity.health / max * 100.0).coerceIn(0.0, 100.0)
    }

    // --- cooldowns -------------------------------------------------------------

    fun isReady(index: Int, nowTick: Long): Boolean =
        index in cooldowns.indices && cooldowns[index] <= nowTick

    fun putOnCooldown(index: Int, nowTick: Long, ticks: Int) {
        if (index in cooldowns.indices) cooldowns[index] = nowTick + ticks
    }

    /** True the first time this is called for [index]; false forever after. */
    fun fireOnce(index: Int): Boolean {
        if (index !in oneShotFired.indices) return false
        if (oneShotFired[index]) return false
        oneShotFired[index] = true
        return true
    }

    // --- phases ----------------------------------------------------------------

    /**
     * Moves to the phase matching current health, if it changed.
     *
     * Only ever advances: a boss healed back above a threshold stays where it is. Re-entering a
     * phase would replay its entrance broadcast and re-apply its buffs on every heal tick, which
     * makes the fight read as broken even though each individual step is doing what it says.
     */
    fun advancePhase(): Phase? {
        val definition = definition ?: return null
        if (definition.phases.isEmpty()) return null
        val next = Phase.select(definition.phases, healthPercent()) ?: return null
        val current = phase
        if (current != null && next.healthAbove >= current.healthAbove) return null
        phase = next
        patternCursor = 0
        patternNextTick = 0L
        return next
    }

    /** Sets the starting phase without treating it as a transition. */
    fun initialisePhase() {
        val definition = definition ?: return
        phase = Phase.select(definition.phases, healthPercent())
    }

    /** Skill power from the mob's own stat and the active phase, as a plain multiplier. */
    fun skillPower(): Double {
        val fromStat = 1.0 + stats.getOrZero(com.inmc.monster.mob.StatKeys.SKILL_POWER) / 100.0
        return (fromStat * (phase?.skillPower ?: 1.0)).coerceAtLeast(0.0)
    }

    fun dropMultiplier(): Double {
        var factor = 1.0 + stats.getOrZero(com.inmc.monster.mob.StatKeys.DROP_MULTIPLIER) / 100.0
        affixes.forEach { factor *= it.dropMultiplier }
        return factor.coerceAtLeast(0.0)
    }

    fun expMultiplier(): Double {
        var factor = 1.0 + stats.getOrZero(com.inmc.monster.mob.StatKeys.EXP_MULTIPLIER) / 100.0
        affixes.forEach { factor *= it.expMultiplier }
        return factor.coerceAtLeast(0.0)
    }

    /** Skills matching a trigger, paired with their index so cooldowns can be addressed. */
    fun skillsFor(trigger: com.inmc.monster.skill.SkillTrigger): List<IndexedValue<SkillInstance>> {
        val phaseName = phase?.name
        return skills.withIndex().filter { (_, skill) ->
            skill.trigger == trigger && skill.activeIn(phaseName)
        }
    }
}
