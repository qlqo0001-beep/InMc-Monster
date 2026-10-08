package com.inmc.monster.integration

import kr.inmc.core.integration.PluginClasses
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.logging.Logger

/**
 * MythicLib, reached entirely by reflection.
 *
 * This is the bridge that makes MMOItems equipment on a mob actually mean something.
 *
 * MythicLib ships `EntityStatProvider`, whose constructor walks a `LivingEntity`'s armour
 * contents plus both hands and registers each as an `NBTItem`; `getStat(String)` then returns
 * the summed MMOItems stat across that equipment. So the moment a mob is wearing an MMOItems
 * weapon, its ATTACK_DAMAGE, CRITICAL_STRIKE_CHANCE and the rest are already readable - no NBT
 * parsing of our own required.
 *
 * Reading the stats is only half of it. A mob that simply carries the gear still hits for plain
 * vanilla damage, because nothing routed the attack through MythicLib's damage pipeline. That
 * is what [damage] is for: an `AttackMetadata` built from the mob's own stat provider makes the
 * hit obey the same elemental, critical and mitigation rules a player's attack would, which in
 * turn means the target's MMOItems defence, dodge and elemental resistance apply normally.
 *
 * Everything degrades: with MythicLib absent, [isEnabled] is false and callers fall back to
 * their own arithmetic.
 */
class MythicLibHook(private val logger: Logger) {

    private var enabled = false

    // StatProvider.get(LivingEntity) -> StatProvider
    private var statProviderGet: Method? = null

    // StatProvider#getStat(String) -> double
    private var getStat: Method? = null

    // new DamageMetadata(double, DamageType[])
    private var damageMetaCtor: Constructor<*>? = null
    private var registerWeaponCrit: Method? = null

    // new AttackMetadata(DamageMetadata, StatProvider)
    private var attackMetaCtor: Constructor<*>? = null

    // MythicLib.plugin.getDamage().damage(AttackMetadata, LivingEntity)
    private var damageManager: Any? = null
    private var damageMethod: Method? = null
    private var findAttack: Method? = null

    private var damageTypeClass: Class<*>? = null
    private val damageTypes = HashMap<String, Any>()

    val isEnabled: Boolean get() = enabled

    fun setup() {
        reset()
        // 미설치는 Monsters.setupIntegrations 가 다른 선택 연동과 묶어 한 줄로 알린다.
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicLib")) return
        try {
            val providerClass = PluginClasses.require(
                "MythicLib", "io.lumine.mythic.lib.api.stat.provider.StatProvider",
            )
            statProviderGet = providerClass.methods.first {
                it.name == "get" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == LivingEntity::class.java
            }
            getStat = providerClass.getMethod("getStat", String::class.java)

            val damageMetaClass = PluginClasses.require("MythicLib", "io.lumine.mythic.lib.damage.DamageMetadata")
            damageTypeClass = PluginClasses.require("MythicLib", "io.lumine.mythic.lib.damage.DamageType")
            damageMetaCtor = damageMetaClass.getConstructor(
                Double::class.javaPrimitiveType,
                java.lang.reflect.Array.newInstance(damageTypeClass, 0).javaClass,
            )
            registerWeaponCrit = damageMetaClass.methods.firstOrNull {
                it.name == "registerWeaponCriticalStrike" && it.parameterCount == 0
            }

            val attackMetaClass = PluginClasses.require("MythicLib", "io.lumine.mythic.lib.damage.AttackMetadata")
            attackMetaCtor = attackMetaClass.getConstructor(damageMetaClass, providerClass)

            val mythicLibClass = PluginClasses.require("MythicLib", "io.lumine.mythic.lib.MythicLib")
            val instance = mythicLibClass.getField("plugin").get(null)
            damageManager = mythicLibClass.getMethod("getDamage").invoke(instance)
            damageMethod = damageManager!!.javaClass.methods.first {
                it.name == "damage" && it.parameterCount == 2 &&
                    it.parameterTypes[1] == LivingEntity::class.java
            }
            findAttack = damageManager!!.javaClass.methods.firstOrNull {
                it.name == "findAttack" && it.parameterCount == 1
            }

            cacheDamageTypes()

            enabled = true
            logger.info("MythicLib 연동 활성화 - MMOItems 장비 스탯이 몬스터에게 적용됩니다")
        } catch (t: Throwable) {
            reset()
            logger.warning(
                "MythicLib 연동 실패 (버전 불일치일 수 있습니다): " +
                    t.javaClass.simpleName + ": " + t.message
            )
        }
    }

    private fun cacheDamageTypes() {
        val typeClass = damageTypeClass ?: return
        for (constant in typeClass.enumConstants ?: emptyArray()) {
            val name = (constant as? Enum<*>)?.name ?: continue
            damageTypes[name] = constant
        }
    }

    private fun reset() {
        enabled = false
        statProviderGet = null
        getStat = null
        damageMetaCtor = null
        registerWeaponCrit = null
        attackMetaCtor = null
        damageManager = null
        damageMethod = null
        findAttack = null
        damageTypeClass = null
        damageTypes.clear()
    }

    /**
     * Summed MMOItems stat value across everything [entity] is wearing or holding.
     *
     * Returns 0 when MythicLib is absent, which is the right neutral: the caller adds this to
     * its own configured stats, so "no equipment bonus" and "no MythicLib" behave identically.
     */
    fun equipmentStat(entity: LivingEntity, statKey: String): Double {
        if (!enabled) return 0.0
        return try {
            val provider = statProviderGet!!.invoke(null, entity) ?: return 0.0
            (getStat!!.invoke(provider, statKey) as? Double) ?: 0.0
        } catch (_: Throwable) {
            0.0
        }
    }

    /**
     * Deals [amount] to [target] as an attack *from* [attacker], through MythicLib.
     *
     * Returns false when MythicLib is unavailable or the call failed, and the caller then does
     * its own damage instead. Never throws.
     */
    fun damage(
        attacker: LivingEntity,
        target: LivingEntity,
        amount: Double,
        types: List<String>,
        critical: Boolean,
    ): Boolean {
        if (!enabled) return false
        return try {
            val typeArray = buildTypeArray(types)
            val damageMeta = damageMetaCtor!!.newInstance(amount, typeArray)
            if (critical) registerWeaponCrit?.invoke(damageMeta)
            val provider = statProviderGet!!.invoke(null, attacker) ?: return false
            val attackMeta = attackMetaCtor!!.newInstance(damageMeta, provider)
            damageMethod!!.invoke(damageManager, attackMeta, target)
            true
        } catch (t: Throwable) {
            logger.warning("MythicLib 데미지 처리 실패 - 자체 계산으로 대체합니다: " + t.message)
            false
        }
    }

    /**
     * True when MythicLib has already registered an attack for this damage event.
     *
     * The re-entry guard: our own [damage] call fires a fresh EntityDamageByEntityEvent, and
     * without this check the listener would intercept its own attack and recurse until the
     * stack gave out.
     */
    fun hasRegisteredAttack(event: org.bukkit.event.entity.EntityDamageEvent): Boolean {
        if (!enabled) return false
        return try {
            findAttack?.invoke(damageManager, event) != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildTypeArray(types: List<String>): Any {
        val typeClass = damageTypeClass!!
        val resolved = types.mapNotNull { damageTypes[it.uppercase()] }
        val array = java.lang.reflect.Array.newInstance(typeClass, resolved.size)
        resolved.forEachIndexed { index, value -> java.lang.reflect.Array.set(array, index, value) }
        return array
    }

    companion object {
        /** Damage type names used for a mob's ordinary melee swing. */
        val MELEE_TYPES = listOf("WEAPON", "PHYSICAL")

        /** Damage type names used for a skill hit, so skill-damage stats apply correctly. */
        val SKILL_TYPES = listOf("SKILL", "MAGICAL")

        val PROJECTILE_TYPES = listOf("WEAPON", "PHYSICAL", "PROJECTILE")
    }
}
