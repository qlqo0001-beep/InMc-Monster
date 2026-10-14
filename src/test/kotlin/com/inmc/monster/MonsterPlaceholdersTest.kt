package com.inmc.monster

import com.inmc.monster.integration.MonsterPlaceholders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonsterPlaceholdersTest {

    private fun list() = MonsterPlaceholders.placeholders(
        "monster",
        mobs = listOf("goblin" to "Boss", "total" to null),
        spawners = listOf("arena" to "", "cave" to "dungeon"),
        triggerIds = listOf("curse"),
    )

    @Test
    fun `tags are the ones a live mob can carry`() {
        val keys = list()
        assertTrue("%monster_tag_boss%" in keys)
        assertTrue("%monster_tag_spawner:arena%" in keys)
        assertTrue("%monster_tag_dungeon%" in keys)
        assertTrue("%monster_tag_trigger:curse%" in keys)
        assertTrue("%monster_trigger_progress_curse%" in keys)
        assertTrue("%monster_trigger_cooldown_curse%" in keys)
        assertTrue("%monster_active_goblin%" in keys)
    }

    @Test
    fun `second definition tags are not listed`() {
        val keys = MonsterPlaceholders.placeholders("monster", listOf("goblin" to "boss"), emptyList(), emptyList())
        assertFalse(keys.any { it.startsWith("%monster_tag_") && it != "%monster_tag_boss%" })
    }

    @Test
    fun `no duplicates against fixed keys`() {
        val keys = list()
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(MonsterPlaceholders.FIXED.all { "%monster_" + it + "%" in keys })
    }
}
