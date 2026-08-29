package com.inmc.monster

import com.inmc.monster.pattern.Phase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The example mob's own phase thresholds, exercised the way a fight walks through them.
 *
 * Reported in game: a boss taken below 50% stayed in its first phase. The selection itself was
 * correct - the bug was *when* it was consulted. The check ran inside the damage event, and
 * Bukkit only applies damage after the event returns, so it always read the health from before
 * the hit. Crossing 50% therefore did nothing until the next hit landed, and on a short fight
 * the mob died still reporting its opening phase.
 *
 * The fix moved the check into the tick loop. These tests pin the selection rules it depends on.
 */
class PhaseTransitionTest {

    /** Exactly what `mobs/example.yml` ships: 1페이즈 above 50%, 광폭 below it. */
    private fun examplePhases() = listOf(
        Phase("1페이즈", healthAbove = 50.0),
        Phase("광폭", healthAbove = 0.0),
    )

    @Test
    fun `the example mob crosses into 광폭 the moment it drops below half`() {
        val phases = examplePhases()
        assertEquals("1페이즈", Phase.select(phases, 100.0)?.name)
        assertEquals("1페이즈", Phase.select(phases, 50.0)?.name, "exactly 50% is still the first phase")
        assertEquals("광폭", Phase.select(phases, 49.9)?.name)
        assertEquals("광폭", Phase.select(phases, 1.0)?.name)
    }

    @Test
    fun `reading health before the hit is what produced the reported bug`() {
        // Health 51 before the hit, 45 after. The old code passed the first value and stayed in
        // the opening phase; the new code runs after the damage lands and passes the second.
        val phases = examplePhases()
        assertEquals("1페이즈", Phase.select(phases, 51.0)?.name, "pre-damage health - the old behaviour")
        assertEquals("광폭", Phase.select(phases, 45.0)?.name, "post-damage health - the fixed behaviour")
    }

    @Test
    fun `a phase list with no zero threshold still resolves near death`() {
        // Nobody configures this on purpose, but a mob below every threshold must land somewhere
        // rather than falling through to no phase at all.
        val phases = listOf(Phase("상", healthAbove = 80.0), Phase("중", healthAbove = 40.0))
        assertEquals("중", Phase.select(phases, 5.0)?.name)
        assertEquals("중", Phase.select(phases, 0.0)?.name)
    }

    @Test
    fun `three phases advance in order across their boundaries`() {
        val phases = listOf(
            Phase("1", healthAbove = 66.0),
            Phase("2", healthAbove = 33.0),
            Phase("3", healthAbove = 0.0),
        )
        val walk = listOf(100.0, 70.0, 66.0, 65.0, 40.0, 33.0, 32.0, 1.0)
        val seen = walk.map { Phase.select(phases, it)?.name }
        assertEquals(listOf("1", "1", "1", "2", "2", "2", "3", "3"), seen)
    }

    @Test
    fun `no phases means no phase`() {
        assertNull(Phase.select(emptyList(), 50.0))
    }
}
