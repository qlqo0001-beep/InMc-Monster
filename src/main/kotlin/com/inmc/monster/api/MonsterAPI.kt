package com.inmc.monster.api

import com.inmc.monster.Monsters
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.runtime.ActiveMob
import com.inmc.monster.spawn.SpawnOptions
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import java.util.UUID

/**
 * The surface other plugins - the dungeon plugin above all - are meant to build against.
 *
 * Everything here is deliberately about *tags*. A dungeon spawns its mobs with a tag naming the
 * room or the run, then finds and clears exactly those mobs when the run ends. Without that,
 * cleaning up after a dungeon means either tracking every UUID by hand or killing mobs by
 * definition id and taking out the copies standing in the open world.
 *
 * Obtain it with `MonsterAPI.get()`, guarded by a null check - it returns null until the plugin
 * has finished enabling.
 */
class MonsterAPI(private val monsters: Monsters) {

    // --- spawning --------------------------------------------------------------

    /** Spawns [mobId] at [location]. Returns null when the mob is unknown or the spawn refused. */
    fun spawn(mobId: String, location: Location, options: SpawnOptions = SpawnOptions.DEFAULT): ActiveMob? {
        val definition = monsters.mobs.get(mobId) ?: return null
        return monsters.spawns.spawn(definition, location, options)
    }

    /**
     * Spawns several of the same mob, scattered around [location].
     *
     * Returns the ones that were actually created - a partial result is normal when the budget
     * fills up part-way, and pretending otherwise would hide the reason a wave came out thin.
     */
    fun spawnGroup(
        mobId: String,
        location: Location,
        amount: Int,
        spread: Double = 3.0,
        options: SpawnOptions = SpawnOptions.DEFAULT,
    ): List<ActiveMob> {
        val definition = monsters.mobs.get(mobId) ?: return emptyList()
        val out = ArrayList<ActiveMob>(amount)
        repeat(amount.coerceIn(1, 200)) {
            val where = monsters.spawns.scatter(location, spread)
            monsters.spawns.spawn(definition, where, options)?.let { out.add(it) }
        }
        return out
    }

    /** Why the last [spawn] returned null, for reporting to whoever asked. */
    fun lastRefusal(): String? = monsters.spawns.lastRefusal

    // --- lookup ----------------------------------------------------------------

    fun get(entity: Entity): ActiveMob? = monsters.tracker.of(entity)

    fun get(id: UUID): ActiveMob? = monsters.tracker[id]

    fun isCustom(entity: Entity): Boolean = monsters.tracker.isCustom(entity)

    /** Definition id of [entity], or null when it is not one of ours. */
    fun idOf(entity: Entity): String? = monsters.tracker.of(entity)?.definition?.id

    fun definitions(): List<MobDefinition> = monsters.mobs.all()

    fun definition(mobId: String): MobDefinition? = monsters.mobs.get(mobId)

    fun exists(mobId: String): Boolean = monsters.mobs.exists(mobId)

    // --- tags ------------------------------------------------------------------

    fun withTag(tag: String): List<ActiveMob> = monsters.tracker.withTag(tag)

    fun countWithTag(tag: String): Int = monsters.tracker.countWithTag(tag)

    /** Removes every live mob carrying [tag]. Returns how many were removed. */
    fun killAllByTag(tag: String): Int {
        val mobs = monsters.tracker.withTag(tag)
        mobs.forEach { monsters.removeMob(it) }
        return mobs.size
    }

    fun countAll(): Int = monsters.tracker.size

    fun inWorld(world: org.bukkit.World): List<ActiveMob> = monsters.tracker.inWorld(world)

    // --- combat helpers --------------------------------------------------------

    /** Deals skill damage from [mob] to [target] through the same path our own skills use. */
    fun dealSkillDamage(mob: ActiveMob, target: LivingEntity, amount: Double) {
        monsters.damage.applySkill(mob, target, amount)
    }

    /** Casts one of the registered skills on demand - useful for scripted encounters. */
    fun castSkill(mob: ActiveMob, skillId: String, values: Map<String, Any> = emptyMap()): Boolean =
        monsters.skills.castById(mob, skillId, values)

    fun skillIds(): List<String> = monsters.skills.registry.ids()

    // --- event dispatch (internal) ---------------------------------------------

    /** Returns false when a listener cancelled the spawn, in which case the mob is already gone. */
    internal fun fireSpawnEvent(mob: ActiveMob): Boolean {
        val event = CustomMobSpawnEvent(mob)
        Bukkit.getPluginManager().callEvent(event)
        if (!event.isCancelled) return true
        monsters.removeMob(mob)
        return false
    }

    internal fun fireDeathEvent(mob: ActiveMob, killer: org.bukkit.entity.Player?) {
        Bukkit.getPluginManager().callEvent(CustomMobDeathEvent(mob, killer))
    }

    internal fun firePhaseEvent(mob: ActiveMob, phaseName: String, previous: String?) {
        Bukkit.getPluginManager().callEvent(CustomMobPhaseChangeEvent(mob, phaseName, previous))
    }

    internal fun fireSkillEvent(mob: ActiveMob, skillId: String, targets: List<LivingEntity>) {
        Bukkit.getPluginManager().callEvent(CustomMobSkillEvent(mob, skillId, targets))
    }

    /** Returns the possibly-modified damage, or null when a listener cancelled the hit. */
    internal fun fireDamageEvent(
        mob: ActiveMob,
        other: LivingEntity?,
        outgoing: Boolean,
        damage: Double,
    ): Double? {
        val event = CustomMobDamageEvent(mob, other, outgoing, damage)
        Bukkit.getPluginManager().callEvent(event)
        return if (event.isCancelled) null else event.damage
    }

    companion object {

        @Volatile
        private var instance: MonsterAPI? = null

        /** Null until the plugin has enabled; callers must handle that. */
        @JvmStatic
        fun get(): MonsterAPI? = instance

        internal fun install(api: MonsterAPI?) {
            instance = api
        }
    }
}
