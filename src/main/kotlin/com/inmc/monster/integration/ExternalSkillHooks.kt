package com.inmc.monster.integration

import kr.inmc.core.integration.PluginClasses
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import java.lang.reflect.Method
import java.util.logging.Logger

/**
 * Casts a skill defined in MythicMobs.
 *
 * Exists so a server that already has a MythicMobs skill library can point a mob at those
 * skills instead of rebuilding them here. Neither plugin was installed on the test server, so
 * this is written to no-op cleanly and say so in the log rather than to fail at cast time.
 */
class MythicMobsHook(private val logger: Logger) {

    private var enabled = false
    private var apiHelper: Any? = null
    private var castSkill: Method? = null
    private var skillExists: Method? = null

    val isEnabled: Boolean get() = enabled

    fun setup() {
        enabled = false
        apiHelper = null
        castSkill = null
        skillExists = null

        // 미설치는 Monsters.setupIntegrations 가 다른 선택 연동과 묶어 한 줄로 알린다.
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) return
        try {
            val bukkitClass = PluginClasses.require("MythicMobs", "io.lumine.mythic.bukkit.MythicBukkit")
            val instance = bukkitClass.getMethod("inst").invoke(null)
            apiHelper = bukkitClass.getMethod("getAPIHelper").invoke(instance)

            val helperClass = apiHelper!!.javaClass
            castSkill = helperClass.methods.firstOrNull {
                it.name == "castSkill" && it.parameterCount == 2 &&
                    it.parameterTypes[1] == String::class.java
            } ?: error("castSkill(Entity, String) 를 찾을 수 없습니다")

            skillExists = helperClass.methods.firstOrNull {
                it.name == "getSkill" && it.parameterCount == 1
            }

            enabled = true
            logger.info("MythicMobs 연동 활성화 - 몬스터 스킬에서 MythicMobs 스킬을 호출할 수 있습니다")
        } catch (t: Throwable) {
            enabled = false
            logger.warning("MythicMobs 연동 실패: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    fun cast(caster: Entity, skillName: String): Boolean {
        if (!enabled || skillName.isBlank()) return false
        return try {
            castSkill!!.invoke(apiHelper, caster, skillName)
            true
        } catch (t: Throwable) {
            logger.warning("MythicMobs 스킬 시전 실패 (" + skillName + "): " + t.message)
            false
        }
    }

    fun exists(skillName: String): Boolean {
        if (!enabled) return false
        return runCatching { skillExists?.invoke(apiHelper, skillName) != null }.getOrDefault(false)
    }
}

/**
 * Casts a spell defined in MagicSpells.
 *
 * Same rationale and the same degradation as [MythicMobsHook]: the spell library a server has
 * already built is worth reusing, and its absence costs nothing.
 */
class MagicSpellsHook(private val logger: Logger) {

    private var enabled = false
    private var getSpellByName: Method? = null
    private var castMethod: Method? = null

    val isEnabled: Boolean get() = enabled

    fun setup() {
        enabled = false
        getSpellByName = null
        castMethod = null

        if (!Bukkit.getPluginManager().isPluginEnabled("MagicSpells")) return
        try {
            val magicSpells = PluginClasses.require("MagicSpells", "com.nisovin.magicspells.MagicSpells")
            getSpellByName = magicSpells.methods.firstOrNull {
                (it.name == "getSpellByInternalName" || it.name == "getSpellByName") &&
                    it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            } ?: error("getSpellByInternalName(String) 을 찾을 수 없습니다")

            val spellClass = PluginClasses.require("MagicSpells", "com.nisovin.magicspells.Spell")
            castMethod = spellClass.methods.firstOrNull {
                it.name == "cast" && it.parameterCount == 1 &&
                    it.parameterTypes[0].isAssignableFrom(LivingEntity::class.java)
            } ?: spellClass.methods.firstOrNull { it.name == "cast" && it.parameterCount == 1 }

            if (castMethod == null) error("Spell.cast(LivingEntity) 를 찾을 수 없습니다")

            enabled = true
            logger.info("MagicSpells 연동 활성화 - 몬스터 스킬에서 스펠을 호출할 수 있습니다")
        } catch (t: Throwable) {
            enabled = false
            logger.warning("MagicSpells 연동 실패: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    fun cast(caster: LivingEntity, spellName: String): Boolean {
        if (!enabled || spellName.isBlank()) return false
        return try {
            val spell = getSpellByName!!.invoke(null, spellName) ?: run {
                logger.warning("MagicSpells 스펠을 찾을 수 없습니다: " + spellName)
                return false
            }
            castMethod!!.invoke(spell, caster)
            true
        } catch (t: Throwable) {
            logger.warning("MagicSpells 스펠 시전 실패 (" + spellName + "): " + t.message)
            false
        }
    }
}
