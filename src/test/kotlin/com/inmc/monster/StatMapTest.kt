package com.inmc.monster

import com.inmc.monster.affix.StatModifier
import com.inmc.monster.mob.MobLevel
import com.inmc.monster.mob.StatMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatMapTest {

    @Test
    fun `keys are case insensitive`() {
        val stats = StatMap()
        stats["max_health"] = 100.0
        assertEquals(100.0, stats["MAX_HEALTH"])
        assertEquals(100.0, stats.getOrZero("Max_Health"))
    }

    @Test
    fun `missing keys read as zero but stay absent`() {
        val stats = StatMap()
        assertEquals(0.0, stats.getOrZero("ATTACK_DAMAGE"))
        assertNull(stats["ATTACK_DAMAGE"])
        assertTrue(stats.isEmpty())
    }

    @Test
    fun `copies do not share storage`() {
        val original = StatMap(mapOf("ARMOR" to 5.0))
        val copy = original.copyOf()
        copy["ARMOR"] = 10.0
        assertEquals(5.0, original["ARMOR"])
        assertEquals(10.0, copy["ARMOR"])
    }

    @Test
    fun `merge adds rather than replaces`() {
        val base = StatMap(mapOf("ARMOR" to 5.0))
        base.mergeAdd(StatMap(mapOf("ARMOR" to 3.0, "LUCK" to 1.0)))
        assertEquals(8.0, base["ARMOR"])
        assertEquals(1.0, base["LUCK"])
    }
}

class StatModifierTest {

    @Test
    fun `multiply is applied before add`() {
        // The order is fixed on purpose: applying the flat bonus first would let an unrelated
        // x1.2 affix silently amplify a +5 one, so two affixes that each read as mild would
        // combine into something neither of them describes.
        val modifier = StatModifier(mult = 2.0, add = 5.0)
        assertEquals(25.0, modifier.apply(10.0))
    }

    @Test
    fun `a neutral modifier changes nothing`() {
        val modifier = StatModifier()
        assertEquals(10.0, modifier.apply(10.0))
        assertTrue(modifier.isNoop())
    }

    @Test
    fun `describe reports both parts`() {
        assertEquals("x1.20 +5", StatModifier(1.2, 5.0).describe())
        assertEquals("+5", StatModifier(1.0, 5.0).describe())
        assertEquals("x1.20", StatModifier(1.2, 0.0).describe())
        assertEquals("-", StatModifier().describe())
    }
}

class MobLevelTest {

    @Test
    fun `per-level percentages compound from the base level`() {
        val level = MobLevel(base = 10, perLevel = StatMap(mapOf("MAX_HEALTH" to 10.0)))
        assertEquals(1.0, level.multiplierFor("MAX_HEALTH", 10))
        assertEquals(1.5, level.multiplierFor("MAX_HEALTH", 15))
    }

    @Test
    fun `levels below the base do not shrink the mob`() {
        val level = MobLevel(base = 10, perLevel = StatMap(mapOf("MAX_HEALTH" to 10.0)))
        assertEquals(1.0, level.multiplierFor("MAX_HEALTH", 5))
    }

    @Test
    fun `stats without a curve are untouched`() {
        val level = MobLevel(base = 1, perLevel = StatMap(mapOf("MAX_HEALTH" to 10.0)))
        assertEquals(1.0, level.multiplierFor("ATTACK_DAMAGE", 50))
    }
}
