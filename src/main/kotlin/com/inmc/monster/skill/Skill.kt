package com.inmc.monster.skill

import com.inmc.monster.Monsters
import com.inmc.monster.runtime.ActiveMob
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

/**
 * What a skill declares about one of its settings.
 *
 * The GUI builds a skill's whole edit screen from this list. That is the entire reason it
 * exists: with two dozen built-in skills, hand-writing a menu per skill would mean two dozen
 * screens to keep in sync with two dozen `cast` implementations, and they would drift apart
 * the first time a parameter was renamed.
 */
class SkillParam(
    val key: String,
    val label: String,
    val type: ParamType,
    val default: Any,
    val min: Double = 0.0,
    val max: Double = 10_000.0,
    /** Only for [ParamType.ENUM]. */
    val options: List<String> = emptyList(),
    val description: String = "",
) {
    companion object {
        fun double(key: String, label: String, default: Double, min: Double = 0.0, max: Double = 10_000.0, description: String = "") =
            SkillParam(key, label, ParamType.DOUBLE, default, min, max, description = description)

        fun int(key: String, label: String, default: Int, min: Int = 0, max: Int = 10_000, description: String = "") =
            SkillParam(key, label, ParamType.INT, default, min.toDouble(), max.toDouble(), description = description)

        fun bool(key: String, label: String, default: Boolean, description: String = "") =
            SkillParam(key, label, ParamType.BOOL, default, description = description)

        fun text(key: String, label: String, default: String, description: String = "") =
            SkillParam(key, label, ParamType.STRING, default, description = description)

        fun enum(key: String, label: String, default: String, options: List<String>, description: String = "") =
            SkillParam(key, label, ParamType.ENUM, default, options = options, description = description)

        fun particle(key: String = "particle", label: String = "파티클", default: String = "CRIT") =
            SkillParam(key, label, ParamType.PARTICLE, default)

        fun sound(key: String = "sound", label: String = "효과음", default: String = "ENTITY_GENERIC_EXPLODE") =
            SkillParam(key, label, ParamType.SOUND, default)

        fun potion(key: String = "effect", label: String = "포션 효과", default: String = "SLOWNESS") =
            SkillParam(key, label, ParamType.POTION, default)

        fun mob(key: String = "mob", label: String = "몬스터", default: String = "") =
            SkillParam(key, label, ParamType.MOB_ID, default)
    }
}

enum class ParamType(val label: String) {
    DOUBLE("소수"),
    INT("정수"),
    BOOL("켜기/끄기"),
    STRING("문자"),
    ENUM("선택"),
    PARTICLE("파티클"),
    SOUND("효과음"),
    POTION("포션 효과"),
    MOB_ID("몬스터"),
    MATERIAL("블록/아이템"),
}

/**
 * Resolved parameter values for one cast.
 *
 * Reads fall back to the skill's declared default, so a config written before a parameter was
 * added still casts - it just uses the default for the new setting.
 */
class SkillParams(
    private val values: Map<String, Any>,
    private val declared: Map<String, SkillParam>,
) {

    fun double(key: String): Double = number(key)?.toDouble()
        ?: (declared[key]?.default as? Number)?.toDouble() ?: 0.0

    fun int(key: String): Int = number(key)?.toInt()
        ?: (declared[key]?.default as? Number)?.toInt() ?: 0

    fun bool(key: String): Boolean = (values[key] as? Boolean)
        ?: (declared[key]?.default as? Boolean) ?: false

    fun string(key: String): String = (values[key] as? String)
        ?: (declared[key]?.default as? String) ?: ""

    fun material(key: String): Material? = Material.matchMaterial(string(key))

    fun has(key: String): Boolean = values.containsKey(key)

    fun raw(): Map<String, Any> = values

    private fun number(key: String): Number? = values[key] as? Number
}

/** When a skill fires. */
enum class SkillTrigger(val label: String, val description: String) {
    ON_SPAWN("소환 시", "몬스터가 생성될 때 한 번"),
    ON_TIMER("주기적", "지정한 간격마다 반복"),
    ON_ATTACK("공격 시", "몬스터가 대상을 때릴 때"),
    ON_DAMAGED("피격 시", "몬스터가 피해를 입을 때"),
    ON_LOW_HEALTH("체력 감소 시", "체력이 지정 % 아래로 떨어질 때 한 번"),
    ON_TARGET("타겟 지정 시", "새로운 대상을 노리기 시작할 때"),
    ON_KILL_PLAYER("플레이어 처치 시", "플레이어를 죽였을 때"),
    ON_PHASE_ENTER("페이즈 진입 시", "새 페이즈에 들어설 때"),
    ON_DEATH("사망 시", "몬스터가 죽을 때");

    companion object {
        fun parse(raw: String?): SkillTrigger =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: ON_TIMER
    }
}

/** Who the skill applies to. */
enum class TargetSelector(val label: String) {
    SELF("자기 자신"),
    TARGET("현재 대상"),
    NEAREST_PLAYER("가장 가까운 플레이어"),
    RANDOM_PLAYER("무작위 플레이어"),
    HIGHEST_THREAT("최대 기여 플레이어"),
    ALL_PLAYERS_IN_RADIUS("반경 내 모든 플레이어"),
    ALL_ENEMIES_IN_RADIUS("반경 내 모든 적"),
    ALLIES_IN_RADIUS("반경 내 아군 몬스터"),
    LOCATION("현재 위치");

    companion object {
        fun parse(raw: String?): TargetSelector =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: TARGET
    }
}

/**
 * Everything a skill needs to do its job.
 *
 * Always constructed on the main thread; skills may touch the world freely.
 */
class SkillContext(
    val monsters: Monsters,
    val caster: ActiveMob,
    val entity: LivingEntity,
    /** Resolved by the selector. Empty for location-only skills. */
    val targets: List<LivingEntity>,
    val origin: Location,
    val params: SkillParams,
    /**
     * Multiplier folded in from the mob's SKILL_POWER stat and the active phase.
     *
     * Skills multiply their damage by this rather than reading stats themselves, so a phase can
     * make every skill hit harder without each skill knowing phases exist.
     */
    val power: Double,
) {

    val world get() = origin.world!!

    val target: LivingEntity? get() = targets.firstOrNull()

    fun players(): List<Player> = targets.filterIsInstance<Player>()

    /** Damage after the power multiplier, floored at zero. */
    fun scaled(amount: Double): Double = (amount * power).coerceAtLeast(0.0)
}

/**
 * One castable ability.
 *
 * Implementations are stateless singletons: all per-mob state lives in [ActiveMob], so the same
 * Skill object serves every mob on the server.
 */
interface Skill {

    val id: String

    val displayName: String

    val description: List<String>

    /** Icon material for the GUI. */
    val icon: Material

    val parameters: List<SkillParam>

    /** Selector used when the admin has not chosen one. */
    val defaultSelector: TargetSelector get() = TargetSelector.TARGET

    /**
     * True when the skill aims at somebody other than the caster.
     *
     * A leap, a charge or a grapple is defined by the direction from the mob to someone else;
     * pointed at the caster the direction is zero and the skill does nothing at all. Before this
     * existed the GUI happily offered `자기 자신` for those skills and the result was a skill
     * that looked configured, reported no error, and simply never fired.
     */
    val requiresTarget: Boolean get() = false

    fun cast(ctx: SkillContext)

    /** Declared parameters keyed for fast lookup, built once per skill object. */
    val parameterMap: Map<String, SkillParam>
        get() = parameters.associateBy { it.key }

    /**
     * Selectors this skill can actually do something with.
     *
     * The GUI cycles through this list rather than the whole enum, so a configuration that can
     * never fire cannot be built in the first place.
     */
    val usableSelectors: List<TargetSelector>
        get() = if (!requiresTarget) {
            TargetSelector.entries
        } else {
            TargetSelector.entries.filter { it != TargetSelector.SELF && it != TargetSelector.LOCATION }
        }

    /** True when [selector] would leave this skill with nobody to aim at. */
    fun rejects(selector: TargetSelector): Boolean = requiresTarget && selector !in usableSelectors
}

/**
 * A skill as configured on a mob: which skill, with what values, fired by what.
 *
 * Kept separate from [Skill] so one skill can appear many times on the same mob with different
 * settings - a boss that fires a small volley often and a large one rarely is two instances of
 * the same skill, not two skills.
 */
class SkillInstance(
    var skillId: String,
    var trigger: SkillTrigger = SkillTrigger.ON_TIMER,
    var selector: TargetSelector = TargetSelector.TARGET,
    /** Ticks between casts for ON_TIMER, and the minimum gap for every other trigger. */
    var cooldownTicks: Int = 100,
    var chance: Double = 100.0,
    var radius: Double = 8.0,
    /** Health percentage threshold for ON_LOW_HEALTH. */
    var healthThreshold: Double = 50.0,
    /** Windup in ticks before the effect lands, with a telegraph particle. 0 = instant. */
    var castTimeTicks: Int = 0,
    /** Cancels a windup if the mob is hit. Only meaningful with a cast time. */
    var interruptOnDamage: Boolean = false,
    /** Phase names this instance belongs to. Empty = active in every phase. */
    var phases: MutableSet<String> = linkedSetOf(),
    var enabled: Boolean = true,
    val values: MutableMap<String, Any> = LinkedHashMap(),
) {

    fun copyOf(): SkillInstance = SkillInstance(
        skillId, trigger, selector, cooldownTicks, chance, radius, healthThreshold,
        castTimeTicks, interruptOnDamage, LinkedHashSet(phases), enabled,
        LinkedHashMap(values),
    )

    fun activeIn(phase: String?): Boolean = phases.isEmpty() || (phase != null && phases.contains(phase))

    fun paramsFor(skill: Skill): SkillParams = SkillParams(values, skill.parameterMap)

    fun save(section: ConfigurationSection) {
        section.set("skill", skillId)
        section.set("trigger", trigger.name)
        section.set("selector", selector.name)
        section.set("cooldown-ticks", cooldownTicks)
        section.set("chance", chance)
        section.set("radius", radius)
        section.set("health-threshold", healthThreshold)
        section.set("cast-time-ticks", castTimeTicks)
        section.set("interrupt-on-damage", interruptOnDamage)
        section.set("enabled", enabled)
        if (phases.isNotEmpty()) section.set("phases", phases.toList())
        if (values.isNotEmpty()) {
            val target = section.createSection("values")
            values.forEach { (key, value) -> target.set(key, value) }
        }
    }

    companion object {
        fun load(section: ConfigurationSection): SkillInstance? {
            val skillId = section.getString("skill")?.takeIf { it.isNotBlank() } ?: return null
            val values = LinkedHashMap<String, Any>()
            section.getConfigurationSection("values")?.let { target ->
                for (key in target.getKeys(false)) {
                    target.get(key)?.let { values[key] = it }
                }
            }
            return SkillInstance(
                skillId = skillId,
                trigger = SkillTrigger.parse(section.getString("trigger")),
                selector = TargetSelector.parse(section.getString("selector")),
                cooldownTicks = section.getInt("cooldown-ticks", 100).coerceIn(1, 72_000),
                chance = section.getDouble("chance", 100.0).coerceIn(0.01, 100.0),
                radius = section.getDouble("radius", 8.0).coerceIn(0.5, 128.0),
                healthThreshold = section.getDouble("health-threshold", 50.0).coerceIn(1.0, 100.0),
                castTimeTicks = section.getInt("cast-time-ticks", 0).coerceIn(0, 600),
                interruptOnDamage = section.getBoolean("interrupt-on-damage", false),
                phases = section.getStringList("phases").toCollection(linkedSetOf()),
                enabled = section.getBoolean("enabled", true),
                values = values,
            )
        }
    }
}
