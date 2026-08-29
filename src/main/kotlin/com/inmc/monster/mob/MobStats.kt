package com.inmc.monster.mob

import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.ConfigurationSection

/**
 * The stat vocabulary.
 *
 * Two kinds of stat live side by side:
 *
 *  - **Vanilla-backed** ones map onto an [Attribute] and are written straight into the entity's
 *    attribute instance at spawn. The server then enforces them for free - no per-tick work.
 *  - **Custom** ones have no vanilla equivalent (critical chance, armour penetration, ...) and
 *    are consumed by our own combat code. They are deliberately named exactly like MythicLib's
 *    `SharedStat` constants, so a value read off an MMOItems weapon and a value typed into our
 *    GUI mean the same thing and can simply be added together.
 *
 * Attributes are looked up by registry key rather than referenced as constants: the set has
 * changed name and membership several times across versions, and a missing constant is a
 * compile error while a missing key is just a stat we skip.
 */
object StatKeys {

    /** Vanilla attributes worth exposing for a mob, in GUI display order. */
    private val VANILLA_NAMES: List<String> = listOf(
        "MAX_HEALTH",
        "ATTACK_DAMAGE",
        "MOVEMENT_SPEED",
        "FLYING_SPEED",
        "ARMOR",
        "ARMOR_TOUGHNESS",
        "KNOCKBACK_RESISTANCE",
        "ATTACK_KNOCKBACK",
        "ATTACK_SPEED",
        "FOLLOW_RANGE",
        "SCALE",
        "STEP_HEIGHT",
        "JUMP_STRENGTH",
        "GRAVITY",
        "MAX_ABSORPTION",
        "FALL_DAMAGE_MULTIPLIER",
        "SAFE_FALL_DISTANCE",
        "BURNING_TIME",
        "EXPLOSION_KNOCKBACK_RESISTANCE",
        "ENTITY_INTERACTION_RANGE",
        "NAME_TAG_DISTANCE",
        "MOVEMENT_EFFICIENCY",
        "WATER_MOVEMENT_EFFICIENCY",
        "OXYGEN_BONUS",
        "TEMPT_RANGE",
        "LUCK",
    )

    /**
     * Name -> attribute, containing only the ones this server build actually has.
     *
     * Built once at class load, and resolved through the registry rather than `Attribute`
     * constants: the set has changed name and membership several times across versions, and a
     * missing constant is a compile error while a missing registry key is just a stat we skip.
     */
    val VANILLA: Map<String, Attribute> = VANILLA_NAMES
        .mapNotNull { name ->
            val attribute = runCatching {
                org.bukkit.Registry.ATTRIBUTE.get(NamespacedKey.minecraft(name.lowercase()))
            }.getOrNull()
            if (attribute == null) null else name to attribute
        }
        .toMap()

    /**
     * Plugin-only stats, consumed by our combat and drop code.
     *
     * Names match MythicLib `SharedStat` where an equivalent exists so equipment values and
     * configured values are directly comparable.
     */
    const val CRITICAL_STRIKE_CHANCE = "CRITICAL_STRIKE_CHANCE"
    const val CRITICAL_STRIKE_POWER = "CRITICAL_STRIKE_POWER"
    const val ARMOR_PENETRATION = "ARMOR_PENETRATION"
    const val LIFESTEAL = "LIFESTEAL"
    const val PVE_DAMAGE = "PVE_DAMAGE"
    const val MAGICAL_DAMAGE = "MAGICAL_DAMAGE"
    const val PHYSICAL_DAMAGE = "PHYSICAL_DAMAGE"
    const val PROJECTILE_DAMAGE = "PROJECTILE_DAMAGE"
    const val SKILL_POWER = "SKILL_POWER"
    const val DODGE_RATING = "DODGE_RATING"
    const val DAMAGE_REDUCTION = "DAMAGE_REDUCTION"
    const val HEALTH_REGENERATION = "HEALTH_REGENERATION"
    const val THORNS = "THORNS"
    const val COOLDOWN_REDUCTION = "COOLDOWN_REDUCTION"
    const val EXP_MULTIPLIER = "EXP_MULTIPLIER"
    const val DROP_MULTIPLIER = "DROP_MULTIPLIER"

    /** Custom stats in GUI display order, with the unit each one is measured in. */
    val CUSTOM: List<CustomStat> = listOf(
        CustomStat(CRITICAL_STRIKE_CHANCE, "치명타 확률", StatUnit.PERCENT, 0.0, 0.0, 100.0),
        CustomStat(CRITICAL_STRIKE_POWER, "치명타 배율", StatUnit.PERCENT, 50.0, 0.0, 1000.0),
        CustomStat(ARMOR_PENETRATION, "방어 관통", StatUnit.PERCENT, 0.0, 0.0, 100.0),
        CustomStat(LIFESTEAL, "생명 흡수", StatUnit.PERCENT, 0.0, 0.0, 100.0),
        CustomStat(PVE_DAMAGE, "대인 데미지", StatUnit.PERCENT, 0.0, -100.0, 1000.0),
        CustomStat(PHYSICAL_DAMAGE, "물리 데미지", StatUnit.PERCENT, 0.0, -100.0, 1000.0),
        CustomStat(MAGICAL_DAMAGE, "마법 데미지", StatUnit.PERCENT, 0.0, -100.0, 1000.0),
        CustomStat(PROJECTILE_DAMAGE, "투사체 데미지", StatUnit.PERCENT, 0.0, -100.0, 1000.0),
        CustomStat(SKILL_POWER, "스킬 위력", StatUnit.PERCENT, 0.0, -100.0, 1000.0),
        CustomStat(DODGE_RATING, "회피 확률", StatUnit.PERCENT, 0.0, 0.0, 90.0),
        CustomStat(DAMAGE_REDUCTION, "피해 감소", StatUnit.PERCENT, 0.0, 0.0, 90.0),
        CustomStat(HEALTH_REGENERATION, "체력 재생", StatUnit.FLAT, 0.0, 0.0, 1000.0),
        CustomStat(THORNS, "가시 반사", StatUnit.PERCENT, 0.0, 0.0, 100.0),
        CustomStat(COOLDOWN_REDUCTION, "쿨다운 감소", StatUnit.PERCENT, 0.0, 0.0, 90.0),
        CustomStat(EXP_MULTIPLIER, "경험치 배율", StatUnit.PERCENT, 0.0, -100.0, 1000.0),
        CustomStat(DROP_MULTIPLIER, "드랍 배율", StatUnit.PERCENT, 0.0, -100.0, 1000.0),
    )

    private val CUSTOM_BY_KEY: Map<String, CustomStat> = CUSTOM.associateBy { it.key }

    fun customStat(key: String): CustomStat? = CUSTOM_BY_KEY[key.uppercase()]

    fun isVanilla(key: String): Boolean = VANILLA.containsKey(key.uppercase())

    fun isKnown(key: String): Boolean = isVanilla(key) || CUSTOM_BY_KEY.containsKey(key.uppercase())

    /** Korean label for a vanilla attribute, for GUI lore. */
    fun vanillaLabel(key: String): String = VANILLA_LABELS[key.uppercase()] ?: key

    private val VANILLA_LABELS: Map<String, String> = mapOf(
        "MAX_HEALTH" to "최대 체력",
        "ATTACK_DAMAGE" to "공격력",
        "MOVEMENT_SPEED" to "이동 속도",
        "FLYING_SPEED" to "비행 속도",
        "ARMOR" to "방어력",
        "ARMOR_TOUGHNESS" to "방어 강도",
        "KNOCKBACK_RESISTANCE" to "넉백 저항",
        "ATTACK_KNOCKBACK" to "공격 넉백",
        "ATTACK_SPEED" to "공격 속도",
        "FOLLOW_RANGE" to "추적 거리",
        "SCALE" to "크기",
        "STEP_HEIGHT" to "계단 높이",
        "JUMP_STRENGTH" to "점프력",
        "GRAVITY" to "중력",
        "MAX_ABSORPTION" to "최대 흡수 하트",
        "FALL_DAMAGE_MULTIPLIER" to "낙하 피해 배율",
        "SAFE_FALL_DISTANCE" to "안전 낙하 거리",
        "BURNING_TIME" to "연소 시간",
        "EXPLOSION_KNOCKBACK_RESISTANCE" to "폭발 넉백 저항",
        "ENTITY_INTERACTION_RANGE" to "상호작용 거리",
        "NAME_TAG_DISTANCE" to "이름표 표시 거리",
        "MOVEMENT_EFFICIENCY" to "이동 효율",
        "WATER_MOVEMENT_EFFICIENCY" to "수중 이동 효율",
        "OXYGEN_BONUS" to "산소 보너스",
        "TEMPT_RANGE" to "유인 거리",
        "LUCK" to "행운",
    )
}

enum class StatUnit { FLAT, PERCENT }

class CustomStat(
    val key: String,
    val label: String,
    val unit: StatUnit,
    val default: Double,
    val min: Double,
    val max: Double,
)

/**
 * A mutable bag of stat values, keyed by stat name.
 *
 * Deliberately a plain map rather than a field per stat: the GUI, the affix system, the level
 * scaler and the equipment reader all need to walk stats generically, and every one of them
 * would otherwise need updating whenever a stat is added.
 */
class StatMap(initial: Map<String, Double> = emptyMap()) {

    private val values = LinkedHashMap<String, Double>()

    init {
        initial.forEach { (key, value) -> values[key.uppercase()] = value }
    }

    val keys: Set<String> get() = values.keys

    fun isEmpty(): Boolean = values.isEmpty()

    operator fun get(key: String): Double? = values[key.uppercase()]

    fun getOrZero(key: String): Double = values[key.uppercase()] ?: 0.0

    fun getOr(key: String, fallback: Double): Double = values[key.uppercase()] ?: fallback

    operator fun set(key: String, value: Double) {
        values[key.uppercase()] = value
    }

    fun remove(key: String) {
        values.remove(key.uppercase())
    }

    fun add(key: String, delta: Double) {
        val k = key.uppercase()
        values[k] = (values[k] ?: 0.0) + delta
    }

    fun multiply(key: String, factor: Double) {
        val k = key.uppercase()
        values[k] = (values[k] ?: 0.0) * factor
    }

    fun asMap(): Map<String, Double> = LinkedHashMap(values)

    fun copyOf(): StatMap = StatMap(values)

    /** Adds every entry of [other] on top of this one. Used for affixes and equipment. */
    fun mergeAdd(other: StatMap) {
        other.values.forEach { (key, value) -> add(key, value) }
    }

    fun save(section: ConfigurationSection, path: String) {
        section.set(path, null)
        if (values.isEmpty()) return
        val target = section.createSection(path)
        values.forEach { (key, value) -> target.set(key, value) }
    }

    companion object {

        fun load(section: ConfigurationSection?, path: String): StatMap {
            val target = section?.getConfigurationSection(path) ?: return StatMap()
            val out = LinkedHashMap<String, Double>()
            for (key in target.getKeys(false)) {
                if (!target.isDouble(key) && !target.isInt(key) && !target.isLong(key)) continue
                out[key.uppercase()] = target.getDouble(key)
            }
            return StatMap(out)
        }
    }
}
