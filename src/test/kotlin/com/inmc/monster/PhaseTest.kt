package com.inmc.monster

import com.inmc.monster.pattern.Phase
import com.inmc.monster.trigger.TriggerCounters
import com.inmc.monster.trigger.Trigger
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhaseTest {

    private fun phases() = listOf(
        Phase("3페이즈", healthAbove = 0.0),
        Phase("1페이즈", healthAbove = 66.0),
        Phase("2페이즈", healthAbove = 33.0),
    )

    @Test
    fun `selection is by threshold regardless of list order`() {
        // The list is deliberately out of order: an admin editing thresholds in the GUI will not
        // keep it sorted and should not have to.
        val list = phases()
        assertEquals("1페이즈", Phase.select(list, 100.0)?.name)
        assertEquals("1페이즈", Phase.select(list, 66.0)?.name)
        assertEquals("2페이즈", Phase.select(list, 65.0)?.name)
        assertEquals("3페이즈", Phase.select(list, 10.0)?.name)
    }

    @Test
    fun `a zero threshold catches everything down to death`() {
        assertEquals("3페이즈", Phase.select(phases(), 0.0)?.name)
    }

    @Test
    fun `a gap at the bottom still resolves to the lowest phase`() {
        // Nobody sets 20 as the lowest threshold on purpose, but a mob below it must still have
        // a phase rather than falling through into null.
        val list = listOf(Phase("고", healthAbove = 80.0), Phase("저", healthAbove = 20.0))
        assertEquals("저", Phase.select(list, 5.0)?.name)
    }

    @Test
    fun `an empty list has no phase`() {
        assertNull(Phase.select(emptyList(), 50.0))
    }

    @Test
    fun `copies are deep`() {
        val phase = Phase("원본", healthAbove = 50.0)
        phase.steps.add(com.inmc.monster.pattern.PatternStep(20, "explode"))
        val copy = phase.copyOf()
        copy.steps.clear()
        assertEquals(1, phase.steps.size)
        assertEquals(0, copy.steps.size)
    }
}

class TriggerCountersTest {

    private val player: UUID = UUID.randomUUID()

    private fun trigger(count: Int, cooldown: Int = 0, daily: Int = 0) = Trigger("test").apply {
        this.count = count
        this.cooldownSeconds = cooldown
        this.dailyLimit = daily
    }

    @Test
    fun `the threshold is only met on the nth action`() {
        val counters = TriggerCounters(14)
        val rule = trigger(count = 3)
        val now = System.currentTimeMillis()

        assertFalse(counters.increment(player, rule, now))
        assertFalse(counters.increment(player, rule, now))
        assertTrue(counters.increment(player, rule, now))
    }

    @Test
    fun `resetting makes it every nth rather than from the nth onwards`() {
        val counters = TriggerCounters(14)
        val rule = trigger(count = 2)
        val now = System.currentTimeMillis()

        counters.increment(player, rule, now)
        assertTrue(counters.increment(player, rule, now))
        // Counter reset, so the very next action must not fire again.
        assertFalse(counters.increment(player, rule, now))
        assertTrue(counters.increment(player, rule, now))
    }

    @Test
    fun `not resetting fires on every action past the threshold`() {
        val counters = TriggerCounters(14)
        val rule = trigger(count = 2).apply { resetCounter = false }
        val now = System.currentTimeMillis()

        counters.increment(player, rule, now)
        assertTrue(counters.increment(player, rule, now))
        assertTrue(counters.increment(player, rule, now))
    }

    @Test
    fun `cooldown blocks until it expires`() {
        val counters = TriggerCounters(14)
        val rule = trigger(count = 1, cooldown = 60)
        val now = System.currentTimeMillis()

        counters.markFired(player, rule, now)
        assertTrue(counters.onCooldown(player, rule.id, now))
        assertTrue(counters.onCooldown(player, rule.id, now + 59_000))
        assertFalse(counters.onCooldown(player, rule.id, now + 61_000))
    }

    @Test
    fun `the daily limit stops further firings`() {
        val counters = TriggerCounters(14)
        val rule = trigger(count = 1, daily = 2)
        val now = System.currentTimeMillis()

        assertFalse(counters.atDailyLimit(player, rule, now))
        counters.markFired(player, rule, now)
        counters.markFired(player, rule, now)
        assertTrue(counters.atDailyLimit(player, rule, now))
    }

    @Test
    fun `the daily tally resets the next day`() {
        val counters = TriggerCounters(14)
        val rule = trigger(count = 1, daily = 1)
        val now = System.currentTimeMillis()

        counters.markFired(player, rule, now)
        assertTrue(counters.atDailyLimit(player, rule, now))
        assertFalse(counters.atDailyLimit(player, rule, now + TriggerCounters.DAY_MILLIS))
    }

    @Test
    fun `stale progress is purged`() {
        // Without expiry the progress file only ever grows, and the growth is invisible until a
        // restart takes a minute to load it.
        val counters = TriggerCounters(1)
        val rule = trigger(count = 5)
        val now = System.currentTimeMillis()

        counters.increment(player, rule, now)
        assertEquals(1, counters.size)

        val removed = counters.purge(now + 2 * TriggerCounters.DAY_MILLIS)
        assertEquals(1, removed)
        assertEquals(0, counters.size)
    }

    @Test
    fun `recent progress survives a purge`() {
        val counters = TriggerCounters(14)
        val rule = trigger(count = 5)
        val now = System.currentTimeMillis()

        counters.increment(player, rule, now)
        assertEquals(0, counters.purge(now))
        assertEquals(1, counters.currentCount(player, rule.id))
    }

    @Test
    fun `an untouched trigger reports no progress`() {
        val counters = TriggerCounters(14)
        assertEquals(0, counters.currentCount(player, "unknown"))
        assertNotNull(counters)
    }
}
