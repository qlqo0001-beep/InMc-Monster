package com.inmc.monster.skill

import com.inmc.monster.Monsters
import com.inmc.monster.runtime.ActiveMob
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import java.util.Random

/**
 * Runs skills and patterns.
 *
 * Two entry points. [fire] is event-driven - something happened, so see whether any skill cares.
 * [tick] is the periodic half, driven by a task that only runs while custom mobs exist, and it
 * is the only place with a per-mob loop; everything else keys straight off the mob that the
 * event already identified.
 */
class SkillEngine(private val monsters: Monsters) {

    private val rng = Random()

    /**
     * Fires every skill on [mob] bound to [trigger].
     *
     * [explicitTarget] lets an event supply the entity it already knows about - the player who
     * was hit, the entity that was targeted - so the selector does not have to re-derive it.
     */
    fun fire(mob: ActiveMob, trigger: SkillTrigger, explicitTarget: LivingEntity? = null) {
        if (mob.skills.isEmpty()) return
        if (!mob.isAlive) return

        val now = monsters.currentTick()
        for ((index, instance) in mob.skillsFor(trigger)) {
            if (!mob.isReady(index, now)) continue
            if (trigger == SkillTrigger.ON_LOW_HEALTH) {
                if (mob.healthPercent() > instance.healthThreshold) continue
                // A health threshold is a one-way door: without this the skill would re-fire on
                // every hit for the rest of the fight once health dropped below the line.
                if (!mob.fireOnce(index)) continue
            }
            if (instance.chance < 100.0 && rng.nextDouble() * 100.0 >= instance.chance) continue
            begin(mob, instance, index, now, explicitTarget)
        }
    }

    /**
     * Periodic work: timers, windups and pattern cursors.
     *
     * Called by [com.inmc.monster.scheduler.SkillTicker], which does not run at all when no
     * custom mob is alive.
     */
    fun tick(now: Long) {
        for (mob in monsters.tracker.all()) {
            if (!mob.isAlive) continue

            // Health-driven work runs here rather than inside the damage event, where the
            // entity's health has not been reduced yet. At two ticks the delay is invisible, and
            // the value read is the real one.
            monsters.advancePhase(mob)
            monsters.nameplates.refresh(mob)

            completeCast(mob, now)

            for ((index, instance) in mob.skillsFor(SkillTrigger.ON_TIMER)) {
                if (!mob.isReady(index, now)) continue
                if (instance.chance < 100.0 && rng.nextDouble() * 100.0 >= instance.chance) continue
                begin(mob, instance, index, now, null)
            }

            // ON_LOW_HEALTH is polled rather than event-driven so a mob that burns to death or
            // bleeds out from poison still reaches its enrage threshold.
            for ((index, instance) in mob.skillsFor(SkillTrigger.ON_LOW_HEALTH)) {
                if (mob.healthPercent() > instance.healthThreshold) continue
                if (!mob.isReady(index, now)) continue
                if (!mob.fireOnce(index)) continue
                begin(mob, instance, index, now, null)
            }

            advancePattern(mob, now)
        }
    }

    /** Runs the current phase's scripted sequence. */
    private fun advancePattern(mob: ActiveMob, now: Long) {
        val phase = mob.phase ?: return
        if (!phase.hasPattern) return
        if (now < mob.patternNextTick) return

        if (mob.patternCursor >= phase.steps.size) {
            if (!phase.loop) return
            mob.patternCursor = 0
        }

        val step = phase.steps[mob.patternCursor]
        mob.patternCursor++
        mob.patternNextTick = now + step.delayTicks.coerceAtLeast(1)

        val skill = monsters.skills.registry[step.skillId] ?: run {
            monsters.logger.warning(
                "패턴에 없는 스킬이 지정되어 있습니다: " + step.skillId +
                    " (" + (mob.definition?.id ?: "?") + " / " + phase.name + ")"
            )
            return
        }
        castNow(mob, skill, SkillParams(step.values, skill.parameterMap), skill.defaultSelector, 8.0, null)
    }

    /**
     * Starts a cast, either immediately or after a windup.
     *
     * A windup is what makes a heavy attack readable: the telegraph particles give a player the
     * half-second they need to move, and [SkillInstance.interruptOnDamage] lets a fight be built
     * around punishing the cast.
     */
    private fun begin(
        mob: ActiveMob,
        instance: SkillInstance,
        index: Int,
        now: Long,
        explicitTarget: LivingEntity?,
    ) {
        val skill = monsters.skills.registry[instance.skillId] ?: return
        if (!monsters.skills.registry.isAvailable(skill)) return

        mob.putOnCooldown(index, now, effectiveCooldown(mob, instance))

        if (instance.castTimeTicks <= 0) {
            castNow(mob, skill, instance.paramsFor(skill), instance.selector, instance.radius, explicitTarget)
            return
        }

        mob.castingSkill = index
        mob.castCompleteTick = now + instance.castTimeTicks
        telegraph(mob, instance)
    }

    private fun completeCast(mob: ActiveMob, now: Long) {
        val index = mob.castingSkill
        if (index < 0) return
        if (now < mob.castCompleteTick) {
            // Keep the telegraph alive so the windup stays visible for its whole duration.
            mob.skills.getOrNull(index)?.let { telegraph(mob, it) }
            return
        }
        mob.castingSkill = -1
        val instance = mob.skills.getOrNull(index) ?: return
        val skill = monsters.skills.registry[instance.skillId] ?: return
        castNow(mob, skill, instance.paramsFor(skill), instance.selector, instance.radius, null)
    }

    /** Called by the damage listener; cancels a windup that was set to be interruptible. */
    fun interrupt(mob: ActiveMob) {
        val index = mob.castingSkill
        if (index < 0) return
        val instance = mob.skills.getOrNull(index) ?: return
        if (!instance.interruptOnDamage) return
        mob.castingSkill = -1
        mob.entity.world.spawnParticle(
            org.bukkit.Particle.SMOKE, mob.entity.location.add(0.0, 1.0, 0.0), 12, 0.3, 0.3, 0.3, 0.01,
        )
    }

    /** Casts by id with explicit values - used by death actions and the test command. */
    fun castById(
        mob: ActiveMob,
        skillId: String,
        values: Map<String, Any>,
        selector: TargetSelector? = null,
        radius: Double = 8.0,
        explicitTarget: LivingEntity? = null,
    ): Boolean {
        val skill = monsters.skills.registry[skillId] ?: return false
        if (!monsters.skills.registry.isAvailable(skill)) return false
        castNow(
            mob, skill, SkillParams(values, skill.parameterMap),
            selector ?: skill.defaultSelector, radius, explicitTarget,
        )
        return true
    }

    private fun castNow(
        mob: ActiveMob,
        skill: Skill,
        params: SkillParams,
        selector: TargetSelector,
        radius: Double,
        explicitTarget: LivingEntity?,
    ) {
        var targets = resolveTargets(mob, selector, radius, explicitTarget)

        if (skill.requiresTarget) {
            // A leap or a charge is a direction from the mob to somebody else. Aimed at the
            // caster the direction is zero and the skill does nothing, so the caster is removed
            // from the list and an empty result ends the cast rather than producing a no-op.
            targets = targets.filter { it != mob.entity }
            if (targets.isEmpty()) return
        }

        // Only skills that genuinely need nobody are allowed to run with no target; the rest
        // would otherwise fire into empty air and waste their cooldown.
        if (targets.isEmpty() && selector != TargetSelector.SELF && selector != TargetSelector.LOCATION) return

        val ctx = SkillContext(
            monsters = monsters,
            caster = mob,
            entity = mob.entity,
            targets = targets,
            origin = mob.entity.location.clone(),
            params = params,
            power = mob.skillPower(),
        )
        try {
            skill.cast(ctx)
            monsters.api.fireSkillEvent(mob, skill.id, targets)
        } catch (t: Throwable) {
            monsters.logger.severe(
                "스킬 시전 실패 (" + skill.id + " / " + (mob.definition?.id ?: "vanilla") + "): " + t.message
            )
        }
    }

    /** Cooldown after the mob's COOLDOWN_REDUCTION stat, floored at one tick. */
    private fun effectiveCooldown(mob: ActiveMob, instance: SkillInstance): Int {
        val reduction = mob.stats.getOrZero(com.inmc.monster.mob.StatKeys.COOLDOWN_REDUCTION)
        if (reduction <= 0.0) return instance.cooldownTicks
        val factor = (1.0 - reduction / 100.0).coerceIn(0.1, 1.0)
        return (instance.cooldownTicks * factor).toInt().coerceAtLeast(1)
    }

    private fun telegraph(mob: ActiveMob, instance: SkillInstance) {
        val location = mob.entity.location.add(0.0, 1.2, 0.0)
        mob.entity.world.spawnParticle(
            org.bukkit.Particle.ANGRY_VILLAGER, location, 3, 0.4, 0.4, 0.4, 0.0,
        )
        if (instance.radius > 1.0) {
            drawRing(mob.entity.location, instance.radius, org.bukkit.Particle.FLAME)
        }
    }

    /**
     * Ground ring used by telegraphs and by area skills that want to show their reach.
     *
     * Delegates to [com.inmc.monster.skill.builtin.Fx] rather than calling `spawnParticle`
     * directly: this is public API, so the particle can be anything a caller hands over,
     * including one that would throw without its extra data.
     */
    fun drawRing(centre: Location, radius: Double, particle: org.bukkit.Particle, points: Int = 24) {
        com.inmc.monster.skill.builtin.Fx.ring(centre, radius, particle, points)
    }

    // --- targeting -------------------------------------------------------------

    fun resolveTargets(
        mob: ActiveMob,
        selector: TargetSelector,
        radius: Double,
        explicitTarget: LivingEntity?,
    ): List<LivingEntity> {
        val entity = mob.entity
        val location = entity.location

        return when (selector) {
            TargetSelector.SELF -> listOf(entity)

            TargetSelector.LOCATION -> emptyList()

            TargetSelector.TARGET ->
                listOfNotNull(explicitTarget ?: (entity as? Mob)?.target ?: nearestPlayer(mob, radius))

            TargetSelector.NEAREST_PLAYER -> listOfNotNull(nearestPlayer(mob, radius))

            TargetSelector.RANDOM_PLAYER -> {
                val players = playersInRange(mob, radius)
                if (players.isEmpty()) emptyList() else listOf(players[rng.nextInt(players.size)])
            }

            TargetSelector.HIGHEST_THREAT ->
                listOfNotNull(
                    mob.threat.onlineRanking().firstOrNull { it.world == entity.world }
                        ?: nearestPlayer(mob, radius),
                )

            TargetSelector.ALL_PLAYERS_IN_RADIUS -> playersInRange(mob, radius)

            TargetSelector.ALL_ENEMIES_IN_RADIUS ->
                location.getNearbyLivingEntities(radius)
                    .filter { it != entity && !monsters.tracker.isCustom(it) }
                    .toList()

            TargetSelector.ALLIES_IN_RADIUS ->
                location.getNearbyLivingEntities(radius)
                    .filter { it != entity && monsters.tracker.isCustom(it) }
                    .toList()
        }
    }

    private fun playersInRange(mob: ActiveMob, radius: Double): List<Player> {
        val location = mob.entity.location
        val radiusSq = radius * radius
        return mob.entity.world.players.filter {
            it.gameMode != org.bukkit.GameMode.SPECTATOR &&
                !it.isDead &&
                it.location.distanceSquared(location) <= radiusSq
        }
    }

    private fun nearestPlayer(mob: ActiveMob, radius: Double): Player? =
        playersInRange(mob, radius).minByOrNull { it.location.distanceSquared(mob.entity.location) }
}

/** Groups the registry and the engine so [Monsters] exposes one skill entry point. */
class SkillService(private val monsters: Monsters) {
    val registry = SkillRegistry(monsters)
    private val engine = SkillEngine(monsters)

    fun setup() = registry.setup()

    /**
     * Reports skill configurations that can never fire.
     *
     * Run once after definitions load rather than on every cast: a mob whose leap attack is
     * aimed at itself would otherwise just quietly do nothing forever, and the admin's only
     * clue is that the skill they configured never happens. One line at startup naming the mob
     * and the skill turns that into something fixable.
     */
    fun reportUnusable() {
        for (definition in monsters.mobs.all()) {
            for (instance in definition.skills) {
                val skill = registry[instance.skillId]
                if (skill == null) {
                    monsters.logger.warning(
                        "'" + definition.id + "' 의 스킬 '" + instance.skillId + "' 은(는) 등록되지 않은 스킬입니다",
                    )
                    continue
                }
                if (skill.rejects(instance.selector)) {
                    monsters.logger.warning(
                        "'" + definition.id + "' 의 " + skill.displayName + " 스킬은 대상이 '" +
                            instance.selector.label + "' 로 설정되어 있어 절대 발동하지 않습니다. " +
                            "이 스킬은 자기 자신이 아닌 대상이 필요합니다 - GUI 에서 대상을 바꿔주세요.",
                    )
                }
            }
        }
    }

    fun fire(mob: ActiveMob, trigger: SkillTrigger, target: LivingEntity? = null) =
        engine.fire(mob, trigger, target)

    fun tick(now: Long) = engine.tick(now)

    fun interrupt(mob: ActiveMob) = engine.interrupt(mob)

    fun castById(
        mob: ActiveMob,
        skillId: String,
        values: Map<String, Any> = emptyMap(),
        selector: TargetSelector? = null,
        radius: Double = 8.0,
        target: LivingEntity? = null,
    ): Boolean = engine.castById(mob, skillId, values, selector, radius, target)

    fun resolveTargets(mob: ActiveMob, selector: TargetSelector, radius: Double): List<LivingEntity> =
        engine.resolveTargets(mob, selector, radius, null)

    fun drawRing(centre: Location, radius: Double, particle: org.bukkit.Particle, points: Int = 24) =
        engine.drawRing(centre, radius, particle, points)
}
