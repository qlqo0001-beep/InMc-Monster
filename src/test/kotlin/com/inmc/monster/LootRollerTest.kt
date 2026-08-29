package com.inmc.monster

import com.inmc.monster.mob.LootRoller
import com.inmc.monster.mob.Weighted
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LootRollerTest {

    private class Entry(val name: String, override val chance: Double) : Weighted

    private fun entries(count: Int, chance: Double): List<Entry> =
        (1..count).map { Entry("item$it", chance) }

    @Test
    fun `every entry rolls independently when the ceiling allows it`() {
        // 20 entries at 100% with a ceiling of 20 must produce all 20 - the percentages alone
        // decide, and nothing is truncated.
        val selected = LootRoller.select(entries(20, 100.0), 0, 20, Random(1))
        assertEquals(20, selected.size)
    }

    @Test
    fun `the ceiling truncates rather than the roller re-rolling up to it`() {
        // 20 entries at 100% with a ceiling of 2 must produce exactly 2 - not 20, and not a
        // repeated draw of the same entry.
        val selected = LootRoller.select(entries(20, 100.0), 0, 2, Random(2))
        assertEquals(2, selected.size)
        assertEquals(2, selected.map { it.name }.toSet().size)
    }

    @Test
    fun `low chances usually produce nothing when the floor is zero`() {
        // This is the property that makes a rare drop rare. Rolling until the maximum is reached
        // would turn every 1% entry into "at least one per kill", which is the mistake this
        // roller exists to avoid.
        val rng = Random(3)
        val empties = (1..500).count { LootRoller.select(entries(5, 1.0), 0, 3, rng).isEmpty() }
        assertTrue(empties > 400, "expected most rolls to be empty, got $empties/500")
    }

    @Test
    fun `the floor tops up when nothing won`() {
        val selected = LootRoller.select(entries(5, 0.01), 1, 3, Random(4))
        assertEquals(1, selected.size)
    }

    @Test
    fun `the floor never exceeds the ceiling`() {
        val selected = LootRoller.select(entries(10, 0.01), 5, 2, Random(5))
        assertTrue(selected.size <= 2, "floor must not push past the ceiling, got ${selected.size}")
    }

    @Test
    fun `an empty table yields nothing`() {
        assertTrue(LootRoller.select(emptyList<Entry>(), 3, 5, Random(6)).isEmpty())
    }

    @Test
    fun `raw winner count reports truncation`() {
        val detailed = LootRoller.selectDetailed(entries(10, 100.0), 0, 3, Random(7))
        assertEquals(10, detailed.rawWinners)
        assertEquals(3, detailed.selected.size)
    }

    @Test
    fun `amount rolls stay inside the configured range`() {
        val rng = Random(8)
        repeat(200) {
            val amount = LootRoller.rollAmount(2, 5, rng)
            assertTrue(amount in 2..5, "amount $amount outside 2..5")
        }
    }

    @Test
    fun `amount roll handles an inverted range`() {
        assertEquals(4, LootRoller.rollAmount(4, 1, Random(9)))
    }
}
