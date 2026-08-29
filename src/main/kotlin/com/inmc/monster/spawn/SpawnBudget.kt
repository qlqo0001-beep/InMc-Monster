package com.inmc.monster.spawn

import com.inmc.monster.config.PluginConfig
import com.inmc.monster.config.WorldSettings
import com.inmc.monster.runtime.MobTracker
import org.bukkit.Location

/** Why a spawn was refused. Surfaced by `/몹 상태` so a quiet world has a visible reason. */
enum class BudgetVerdict(val label: String) {
    OK("허용"),
    GLOBAL_FULL("전역 한도 초과"),
    WORLD_FULL("월드 한도 초과"),
    CHUNK_FULL("청크 한도 초과"),
    CROWDED("주변 몬스터 과밀"),
    MODEL_FULL("모델 몬스터 한도 초과"),
    WORLD_DISABLED("월드 비활성");

    val allowed: Boolean get() = this == OK
}

/**
 * The ceiling on how many custom mobs may exist at once.
 *
 * This is not an optimisation, it is the thing that makes probabilistic spawning safe to turn
 * on at all. Replacement fires on every natural spawn on the server; without a hard cap, a
 * generous chance value and a busy night produce an entity count that no amount of tuning
 * elsewhere recovers from.
 *
 * Four limits, each answering a different failure: the global one protects the server, the
 * per-world one stops one world starving the others, the per-chunk one prevents a pile in a
 * single spot, and the near-player one is what a player actually experiences as "too many".
 */
class SpawnBudget(private val tracker: MobTracker) {

    @Volatile
    private var config: PluginConfig? = null

    /** Counts of each refusal since the last reset, for the status screen. */
    private val refusals = java.util.EnumMap<BudgetVerdict, Int>(BudgetVerdict::class.java)

    fun configure(config: PluginConfig) {
        this.config = config
    }

    fun check(location: Location, world: WorldSettings, modelled: Boolean): BudgetVerdict {
        val config = this.config ?: return BudgetVerdict.OK
        val verdict = evaluate(location, world, config, modelled)
        if (!verdict.allowed) {
            synchronized(refusals) { refusals.merge(verdict, 1, Int::plus) }
        }
        return verdict
    }

    private fun evaluate(
        location: Location,
        world: WorldSettings,
        config: PluginConfig,
        modelled: Boolean,
    ): BudgetVerdict {
        if (!world.enabled) return BudgetVerdict.WORLD_DISABLED

        val budget = config.budget
        if (tracker.size >= budget.global) return BudgetVerdict.GLOBAL_FULL

        val bukkitWorld = location.world ?: return BudgetVerdict.OK

        // The world file may set its own ceiling; the global per-world value is the fallback.
        val worldLimit = if (world.maxMobs > 0) world.maxMobs else budget.perWorld
        if (tracker.countIn(bukkitWorld) >= worldLimit) return BudgetVerdict.WORLD_FULL

        if (modelled && tracker.modelledCount >= budget.modelled) return BudgetVerdict.MODEL_FULL

        if (tracker.countAt(location) >= budget.perChunk) return BudgetVerdict.CHUNK_FULL

        if (budget.nearPlayer > 0) {
            val near = tracker.countNear(location, budget.nearPlayerRadius.toDouble())
            if (near >= budget.nearPlayer) return BudgetVerdict.CROWDED
        }
        return BudgetVerdict.OK
    }

    fun refusalCounts(): Map<BudgetVerdict, Int> = synchronized(refusals) { java.util.EnumMap(refusals) }

    fun resetCounters() {
        synchronized(refusals) { refusals.clear() }
    }
}
