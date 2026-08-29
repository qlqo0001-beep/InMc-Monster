package com.inmc.monster.mob

import java.util.Random

/** Anything with a 0.01 .. 100 percentage chance. Kept separate so the roller stays testable. */
interface Weighted {
    val chance: Double
}

/**
 * How a drop table decides what actually drops.
 *
 * Two behaviours have to hold at the same time, and they pull in opposite directions:
 *
 *  - 20 drops registered, max 20: the result may be anything from nothing up to all 20, purely
 *    as the individual chances fall. The percentages alone decide.
 *  - 20 drops registered, max 2: the result is *up to* 2 - not always exactly 2. A mob whose
 *    entries are all 1% should usually drop nothing at all.
 *
 * So every entry rolls independently, the winners are truncated to `maxRolls`, and the
 * guaranteed floor only tops up when fewer than `minRolls` won. Rolling repeatedly until the
 * maximum is reached would silently turn every chance into "at least this often", which is the
 * mistake that makes a rare drop stop being rare.
 */
object LootRoller {

    /**
     * A roll, plus how it got there.
     *
     * [rawWinners] is the count *before* the ceiling and floor were applied. The simulator
     * uses it to tell an admin whether their chance values are actually deciding anything:
     * if nearly every roll is truncated, the max-rolls cap is what shapes the result and the
     * individual percentages barely matter.
     */
    data class Selection<T : Weighted>(val selected: List<T>, val rawWinners: Int)

    fun <T : Weighted> select(
        entries: List<T>,
        minRolls: Int,
        maxRolls: Int,
        rng: Random,
    ): List<T> = selectDetailed(entries, minRolls, maxRolls, rng).selected

    fun <T : Weighted> selectDetailed(
        entries: List<T>,
        minRolls: Int,
        maxRolls: Int,
        rng: Random,
    ): Selection<T> {
        if (entries.isEmpty()) return Selection(emptyList(), 0)

        val ceiling = maxRolls.coerceIn(1, entries.size)

        // The floor may be 0: a tier can be optional ("0~1 rare"), and the box-level pool
        // never passes 0 because RandomBox clamps its own minRolls to 1.
        val floor = minRolls.coerceIn(0, ceiling)

        // 1. independent trial per reward
        val winners = entries.filter { rng.nextDouble() * 100.0 < it.chance }.toMutableList()
        val rawWinners = winners.size
        winners.shuffle(rng)

        // 2. never hand out more than the configured ceiling
        if (winners.size > ceiling) {
            return Selection(winners.subList(0, ceiling).toList(), rawWinners)
        }

        // 3. top up to the guaranteed floor, weighted by chance, without repeats
        if (winners.size < floor) {
            val remaining = entries.filterNot { candidate -> winners.any { it === candidate } }.toMutableList()
            while (winners.size < floor && remaining.isNotEmpty()) {
                winners.add(takeWeighted(remaining, rng))
            }
        }
        return Selection(winners.toList(), rawWinners)
    }

    fun rollAmount(min: Int, max: Int, rng: Random): Int {
        val low = min.coerceAtLeast(1)
        val high = max.coerceAtLeast(low)
        return if (low == high) low else low + rng.nextInt(high - low + 1)
    }

    /** Removes and returns one entry, picked proportionally to its chance. */
    private fun <T : Weighted> takeWeighted(pool: MutableList<T>, rng: Random): T {
        val total = pool.sumOf { it.chance }
        if (total <= 0.0) return pool.removeAt(rng.nextInt(pool.size))
        var cursor = rng.nextDouble() * total
        for (index in pool.indices) {
            cursor -= pool[index].chance
            if (cursor <= 0.0) return pool.removeAt(index)
        }
        return pool.removeAt(pool.size - 1)
    }

}
