package com.inmc.monster.mob

import com.inmc.monster.death.DeathAction
import com.inmc.monster.pattern.Phase
import com.inmc.monster.skill.SkillInstance
import com.inmc.monster.spawn.SpawnRules
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType

/** How a mob's level is decided when it spawns. */
enum class LevelSource(val label: String, val description: String) {
    FIXED("고정", "항상 기본 레벨입니다."),
    RANDOM("무작위", "최소~최대 사이에서 무작위로 정해집니다."),
    DISTANCE_FROM_SPAWN("스폰 거리", "월드 스폰에서 멀어질수록 강해집니다."),
    Y_DEPTH("깊이", "지하로 내려갈수록 강해집니다."),
    NEARBY_PLAYERS("주변 인원", "근처 플레이어가 많을수록 강해집니다.");

    companion object {
        fun parse(raw: String?): LevelSource =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: FIXED
    }
}

/**
 * Level configuration.
 *
 * Levels are the cheapest way to widen content: one mob definition covers a whole difficulty
 * curve instead of needing a near-duplicate file per tier. [perLevel] holds *percent* increases
 * so the same numbers read sensibly whether the base health is 20 or 2000.
 */
class MobLevel(
    var base: Int = 1,
    var min: Int = 1,
    var max: Int = 1,
    var source: LevelSource = LevelSource.FIXED,
    /** Blocks (or players, or Y) per +1 level, depending on [source]. */
    var step: Double = 250.0,
    /** Stat key -> percent gained per level above [base]. */
    var perLevel: StatMap = StatMap(),
) {

    fun copyOf(): MobLevel = MobLevel(base, min, max, source, step, perLevel.copyOf())

    /** Percent multiplier for [key] at [level], as a plain factor (1.0 = unchanged). */
    fun multiplierFor(key: String, level: Int): Double {
        val percent = perLevel[key] ?: return 1.0
        val steps = (level - base).coerceAtLeast(0)
        return 1.0 + (percent / 100.0) * steps
    }

    fun save(section: ConfigurationSection, path: String) {
        val target = section.createSection(path)
        target.set("base", base)
        target.set("min", min)
        target.set("max", max)
        target.set("source", source.name)
        target.set("step", step)
        perLevel.save(target, "per-level")
    }

    companion object {
        fun load(section: ConfigurationSection?, path: String): MobLevel {
            val target = section?.getConfigurationSection(path) ?: return MobLevel()
            return MobLevel(
                base = target.getInt("base", 1).coerceIn(1, 10_000),
                min = target.getInt("min", 1).coerceIn(1, 10_000),
                max = target.getInt("max", 1).coerceIn(1, 10_000),
                source = LevelSource.parse(target.getString("source")),
                step = target.getDouble("step", 250.0).coerceAtLeast(0.1),
                perLevel = StatMap.load(target, "per-level"),
            )
        }
    }
}

/** How a mob may enter the world through vanilla spawn replacement. */
class ReplacementSettings(
    var enabled: Boolean = false,
    /** Percent chance to take over a matching vanilla spawn. */
    var chance: Double = 5.0,
    /**
     * Vanilla entity types whose spawns this mob may replace.
     *
     * Empty means "only my own type", which is both the safest default and the cheapest: a
     * same-type replacement decorates the entity the server already created instead of
     * cancelling it and spawning a second one.
     */
    var replaces: MutableSet<EntityType> = linkedSetOf(),
    var rules: SpawnRules = SpawnRules(),
    /** Relative weight when several mobs compete for the same spawn. */
    var weight: Int = 10,
) {

    fun copyOf(): ReplacementSettings = ReplacementSettings(
        enabled, chance, LinkedHashSet(replaces), rules.copyOf(), weight,
    )

    fun save(section: ConfigurationSection, path: String) {
        val target = section.createSection(path)
        target.set("enabled", enabled)
        target.set("chance", chance)
        target.set("weight", weight)
        target.set("replaces", replaces.map { it.name })
        rules.save(target, "rules")
    }

    companion object {
        fun load(section: ConfigurationSection?, path: String): ReplacementSettings {
            val target = section?.getConfigurationSection(path) ?: return ReplacementSettings()
            val types = linkedSetOf<EntityType>()
            for (name in target.getStringList("replaces")) {
                runCatching { EntityType.valueOf(name.trim().uppercase()) }.getOrNull()?.let { types.add(it) }
            }
            return ReplacementSettings(
                enabled = target.getBoolean("enabled", false),
                chance = target.getDouble("chance", 5.0).coerceIn(0.0, 100.0),
                replaces = types,
                rules = SpawnRules.load(target, "rules"),
                weight = target.getInt("weight", 10).coerceIn(1, 10_000),
            )
        }
    }
}

/** Per-mob affix policy. */
class AffixSettings(
    var enabled: Boolean = true,
    /** Percent chance to roll any affix at all. Negative = use the global default. */
    var chance: Double = -1.0,
    var maxPrefix: Int = -1,
    var maxSuffix: Int = -1,
    /** Affix ids this mob may roll. Empty = every affix that accepts this mob. */
    var allowed: MutableSet<String> = linkedSetOf(),
    var blocked: MutableSet<String> = linkedSetOf(),
) {
    fun copyOf(): AffixSettings =
        AffixSettings(enabled, chance, maxPrefix, maxSuffix, LinkedHashSet(allowed), LinkedHashSet(blocked))

    fun save(section: ConfigurationSection, path: String) {
        val target = section.createSection(path)
        target.set("enabled", enabled)
        target.set("chance", chance)
        target.set("max-prefix", maxPrefix)
        target.set("max-suffix", maxSuffix)
        target.set("allowed", allowed.toList())
        target.set("blocked", blocked.toList())
    }

    companion object {
        fun load(section: ConfigurationSection?, path: String): AffixSettings {
            val target = section?.getConfigurationSection(path) ?: return AffixSettings()
            return AffixSettings(
                enabled = target.getBoolean("enabled", true),
                chance = target.getDouble("chance", -1.0),
                maxPrefix = target.getInt("max-prefix", -1),
                maxSuffix = target.getInt("max-suffix", -1),
                allowed = target.getStringList("allowed").map { it.lowercase() }.toCollection(linkedSetOf()),
                blocked = target.getStringList("blocked").map { it.lowercase() }.toCollection(linkedSetOf()),
            )
        }
    }
}

/**
 * One custom monster, as configured.
 *
 * Persisted as `mobs/<id>.yml`, one file per mob, and held in memory by
 * [MobRegistry] for the lifetime of the server. Nothing reads this from disk at runtime.
 *
 * [parent] gives single-inheritance templates. With a hundred mobs on a server, the alternative
 * is a hundred copies of the same undead stat block, and the first balance change has to be
 * made a hundred times.
 */
class MobDefinition(val id: String) {

    var displayName: String = id
    var enabled: Boolean = true
    var parent: String? = null

    var entityType: EntityType = EntityType.ZOMBIE

    /** ModelEngine R4 blueprint id. Blank = plain vanilla appearance. */
    var model: String = ""

    /** Vanilla-attribute-backed stats, written into the entity at spawn. */
    var stats: StatMap = StatMap()

    /** Plugin-only stats consumed by our combat code. */
    var customStats: StatMap = StatMap()

    var level: MobLevel = MobLevel()
    var flags: MobFlags = MobFlags()
    var immunities: Immunities = Immunities()
    var equipment: MobEquipment = MobEquipment()
    var drops: DropTable = DropTable()
    var affixes: AffixSettings = AffixSettings()
    var replacement: ReplacementSettings = ReplacementSettings()

    val skills: MutableList<SkillInstance> = mutableListOf()
    val phases: MutableList<Phase> = mutableListOf()
    val deathActions: MutableList<DeathAction> = mutableListOf()

    /** Free-form tags for the dungeon API and for `/몹 정리 <tag>`. */
    var tags: MutableSet<String> = linkedSetOf()

    val hasPhases: Boolean get() = phases.isNotEmpty()

    fun maxHealth(): Double = stats.getOr("MAX_HEALTH", 20.0)

    /**
     * Folds a parent definition in underneath this one.
     *
     * Scalars the child never set keep the parent's value; collections are merged with the
     * child appended, so a template can supply a baseline skill set that a child extends rather
     * than replaces.
     */
    fun inheritFrom(parentDef: MobDefinition) {
        if (displayName == id) displayName = parentDef.displayName
        if (model.isBlank()) model = parentDef.model
        if (entityType == EntityType.ZOMBIE && parentDef.entityType != EntityType.ZOMBIE) {
            entityType = parentDef.entityType
        }

        // Parent stats first, then the child's own values overwrite them key by key.
        val mergedStats = parentDef.stats.copyOf()
        stats.asMap().forEach { (key, value) -> mergedStats[key] = value }
        stats = mergedStats

        val mergedCustom = parentDef.customStats.copyOf()
        customStats.asMap().forEach { (key, value) -> mergedCustom[key] = value }
        customStats = mergedCustom

        if (skills.isEmpty()) parentDef.skills.forEach { skills.add(it.copyOf()) }
        if (phases.isEmpty()) parentDef.phases.forEach { phases.add(it.copyOf()) }
        if (deathActions.isEmpty()) parentDef.deathActions.forEach { deathActions.add(it.copyOf()) }
        if (equipment.isEmpty) equipment = parentDef.equipment.copyOf()
        if (drops.entries.isEmpty()) drops.inheritFrom(parentDef.drops)
        if (tags.isEmpty()) tags = LinkedHashSet(parentDef.tags)
    }

    fun save(config: YamlConfiguration) {
        config.set("id", id)
        config.set("display", displayName)
        config.set("enabled", enabled)
        config.set("parent", parent)
        config.set("type", entityType.name)
        config.set("model", model.takeIf { it.isNotBlank() })
        config.set("tags", tags.toList())

        stats.save(config, "stats")
        customStats.save(config, "custom-stats")
        level.save(config, "level")
        flags.save(config, "flags")
        immunities.save(config, "immunities")
        equipment.save(config, "equipment")
        drops.save(config, "drops")
        affixes.save(config, "affixes")
        replacement.save(config, "replacement")

        config.set("skills", null)
        if (skills.isNotEmpty()) {
            val target = config.createSection("skills")
            skills.forEachIndexed { index, skill -> skill.save(target.createSection(index.toString())) }
        }
        config.set("phases", null)
        if (phases.isNotEmpty()) {
            val target = config.createSection("phases")
            phases.forEachIndexed { index, phase -> phase.save(target.createSection(index.toString())) }
        }
        config.set("death-actions", null)
        if (deathActions.isNotEmpty()) {
            val target = config.createSection("death-actions")
            deathActions.forEachIndexed { index, action -> action.save(target.createSection(index.toString())) }
        }
    }

    companion object {

        /** A brand-new mob: a zombie with vanilla-ish numbers, ready to be edited in the GUI. */
        fun create(id: String): MobDefinition = MobDefinition(id).apply {
            displayName = id
            stats["MAX_HEALTH"] = 20.0
            stats["ATTACK_DAMAGE"] = 3.0
            stats["MOVEMENT_SPEED"] = 0.23
            stats["FOLLOW_RANGE"] = 24.0
            customStats[StatKeys.CRITICAL_STRIKE_POWER] = 50.0
            drops.minRolls = 0
            drops.maxRolls = 2
        }

        fun load(id: String, config: ConfigurationSection, defaultDropChance: Double): MobDefinition {
            val def = MobDefinition(id)
            def.displayName = config.getString("display")?.takeIf { it.isNotBlank() } ?: id
            def.enabled = config.getBoolean("enabled", true)
            def.parent = config.getString("parent")?.takeIf { it.isNotBlank() }
            def.entityType = config.getString("type")
                ?.let { name -> runCatching { EntityType.valueOf(name.trim().uppercase()) }.getOrNull() }
                ?: EntityType.ZOMBIE
            def.model = config.getString("model") ?: ""
            def.tags = config.getStringList("tags").map { it.lowercase() }.toCollection(linkedSetOf())

            def.stats = StatMap.load(config, "stats")
            def.customStats = StatMap.load(config, "custom-stats")
            def.level = MobLevel.load(config, "level")
            def.flags = MobFlags.load(config, "flags")
            def.immunities = Immunities.load(config, "immunities")
            def.equipment = MobEquipment.load(config, "equipment")
            def.drops = DropTable.load(config, "drops", defaultDropChance)
            def.affixes = AffixSettings.load(config, "affixes")
            def.replacement = ReplacementSettings.load(config, "replacement")

            config.getConfigurationSection("skills")?.let { target ->
                for (key in target.getKeys(false).sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) {
                    target.getConfigurationSection(key)?.let { one ->
                        SkillInstance.load(one)?.let { def.skills.add(it) }
                    }
                }
            }
            config.getConfigurationSection("phases")?.let { target ->
                for (key in target.getKeys(false).sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) {
                    target.getConfigurationSection(key)?.let { one ->
                        Phase.load(one)?.let { def.phases.add(it) }
                    }
                }
            }
            config.getConfigurationSection("death-actions")?.let { target ->
                for (key in target.getKeys(false).sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) {
                    target.getConfigurationSection(key)?.let { one ->
                        DeathAction.load(one)?.let { def.deathActions.add(it) }
                    }
                }
            }
            return def
        }
    }
}
