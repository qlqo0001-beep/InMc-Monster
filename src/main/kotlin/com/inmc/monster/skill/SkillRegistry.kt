package com.inmc.monster.skill

import com.inmc.monster.Monsters
import com.inmc.monster.skill.builtin.BuiltinSkills
import com.inmc.monster.skill.external.CommandSkill
import com.inmc.monster.skill.external.MagicSpellsSkill
import com.inmc.monster.skill.external.MythicMobsSkill
import java.util.concurrent.ConcurrentHashMap

/**
 * Every skill the plugin can cast, keyed by id.
 *
 * Built-ins are registered unconditionally. The three external bridges are registered too, even
 * when their plugin is missing - a skill that reports "MythicMobs 미설치" in the GUI is far more
 * useful than one that silently vanishes from the list, because an admin who configured it on a
 * server that had the plugin would otherwise see their skill disappear without explanation.
 */
class SkillRegistry(private val monsters: Monsters) {

    private val skills = ConcurrentHashMap<String, Skill>()

    val size: Int get() = skills.size

    fun register(skill: Skill) {
        skills[skill.id.lowercase()] = skill
    }

    operator fun get(id: String?): Skill? = id?.let { skills[it.lowercase()] }

    fun exists(id: String): Boolean = skills.containsKey(id.lowercase())

    fun all(): List<Skill> = skills.values.sortedBy { it.displayName }

    /** Ids only, for tab-completion. */
    fun ids(): List<String> = skills.keys.sorted()

    fun setup() {
        skills.clear()
        BuiltinSkills.all().forEach { register(it) }
        register(MythicMobsSkill(monsters))
        register(MagicSpellsSkill(monsters))
        register(CommandSkill(monsters))
        monsters.logger.info("스킬 " + skills.size + "종을 등록했습니다")
    }

    /**
     * Whether this skill can actually run right now.
     *
     * External bridges answer false when their plugin is absent; built-ins are always available.
     * The GUI uses this to grey a skill out rather than hiding it.
     */
    fun isAvailable(skill: Skill): Boolean = when (skill) {
        is MythicMobsSkill -> monsters.mythicMobs.isEnabled
        is MagicSpellsSkill -> monsters.magicSpells.isEnabled
        else -> true
    }

    fun unavailableReason(skill: Skill): String? = when {
        skill is MythicMobsSkill && !monsters.mythicMobs.isEnabled -> "MythicMobs 가 설치되어 있지 않습니다"
        skill is MagicSpellsSkill && !monsters.magicSpells.isEnabled -> "MagicSpells 가 설치되어 있지 않습니다"
        else -> null
    }
}
