package com.inmc.monster.config

import kr.inmc.core.config.ConfigService
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-world overrides, one `worlds/<world>.yml` per world.
 *
 * Every field is nullable-by-absence: a value the file does not set falls through to
 * [PluginConfig]. That is the rule the whole plugin follows - the global file is the default,
 * the world file is the exception - so an admin never has to restate settings they did not
 * want to change.
 */
class WorldSettings(
    val world: String,
    val enabled: Boolean,
    val replacementMultiplier: Double,
    val affixMultiplier: Double,
    val dropMultiplier: Double,
    val expMultiplier: Double,
    val maxMobs: Int,
    /** Empty = every mob allowed. Non-empty = only these ids may spawn here. */
    val allowedMobs: Set<String>,
    val blockedMobs: Set<String>,
    val triggersEnabled: Boolean,
    val spawnersEnabled: Boolean,
    /** Blanket multipliers applied to plain vanilla mobs in this world. 1.0 = untouched. */
    val vanillaHealthMultiplier: Double,
    val vanillaDamageMultiplier: Double,
) {

    fun allows(mobId: String): Boolean {
        if (!enabled) return false
        val key = mobId.lowercase()
        if (blockedMobs.contains(key)) return false
        if (allowedMobs.isEmpty()) return true
        return allowedMobs.contains(key)
    }

    /** True when vanilla mobs in this world need any post-spawn adjustment at all. */
    val touchesVanilla: Boolean
        get() = vanillaHealthMultiplier != 1.0 || vanillaDamageMultiplier != 1.0

    fun save(config: YamlConfiguration) {
        config.set("enabled", enabled)
        config.set("replacement-multiplier", replacementMultiplier)
        config.set("affix-multiplier", affixMultiplier)
        config.set("drop-multiplier", dropMultiplier)
        config.set("exp-multiplier", expMultiplier)
        config.set("max-mobs", maxMobs)
        config.set("allowed-mobs", allowedMobs.toList())
        config.set("blocked-mobs", blockedMobs.toList())
        config.set("triggers-enabled", triggersEnabled)
        config.set("spawners-enabled", spawnersEnabled)
        config.set("vanilla.health-multiplier", vanillaHealthMultiplier)
        config.set("vanilla.damage-multiplier", vanillaDamageMultiplier)
    }

    fun copy(
        enabled: Boolean = this.enabled,
        replacementMultiplier: Double = this.replacementMultiplier,
        affixMultiplier: Double = this.affixMultiplier,
        dropMultiplier: Double = this.dropMultiplier,
        expMultiplier: Double = this.expMultiplier,
        maxMobs: Int = this.maxMobs,
        allowedMobs: Set<String> = this.allowedMobs,
        blockedMobs: Set<String> = this.blockedMobs,
        triggersEnabled: Boolean = this.triggersEnabled,
        spawnersEnabled: Boolean = this.spawnersEnabled,
        vanillaHealthMultiplier: Double = this.vanillaHealthMultiplier,
        vanillaDamageMultiplier: Double = this.vanillaDamageMultiplier,
    ) = WorldSettings(
        world, enabled, replacementMultiplier, affixMultiplier, dropMultiplier, expMultiplier,
        maxMobs, allowedMobs, blockedMobs, triggersEnabled, spawnersEnabled,
        vanillaHealthMultiplier, vanillaDamageMultiplier,
    )

    companion object {

        fun defaults(world: String, config: PluginConfig) = WorldSettings(
            world = world,
            enabled = true,
            replacementMultiplier = 1.0,
            affixMultiplier = 1.0,
            dropMultiplier = 1.0,
            expMultiplier = 1.0,
            maxMobs = config.budget.perWorld,
            allowedMobs = emptySet(),
            blockedMobs = emptySet(),
            triggersEnabled = true,
            spawnersEnabled = true,
            vanillaHealthMultiplier = 1.0,
            vanillaDamageMultiplier = 1.0,
        )

        fun load(world: String, section: ConfigurationSection, config: PluginConfig): WorldSettings {
            val base = defaults(world, config)
            return WorldSettings(
                world = world,
                enabled = section.getBoolean("enabled", base.enabled),
                replacementMultiplier = section.getDouble("replacement-multiplier", 1.0).coerceIn(0.0, 100.0),
                affixMultiplier = section.getDouble("affix-multiplier", 1.0).coerceIn(0.0, 100.0),
                dropMultiplier = section.getDouble("drop-multiplier", 1.0).coerceIn(0.0, 100.0),
                expMultiplier = section.getDouble("exp-multiplier", 1.0).coerceIn(0.0, 100.0),
                maxMobs = section.getInt("max-mobs", base.maxMobs).coerceAtLeast(0),
                allowedMobs = section.getStringList("allowed-mobs").map { it.lowercase() }.toSet(),
                blockedMobs = section.getStringList("blocked-mobs").map { it.lowercase() }.toSet(),
                triggersEnabled = section.getBoolean("triggers-enabled", true),
                spawnersEnabled = section.getBoolean("spawners-enabled", true),
                vanillaHealthMultiplier = section.getDouble("vanilla.health-multiplier", 1.0).coerceIn(0.01, 100.0),
                vanillaDamageMultiplier = section.getDouble("vanilla.damage-multiplier", 1.0).coerceIn(0.01, 100.0),
            )
        }
    }
}

/**
 * In-memory registry of world settings.
 *
 * Worlds without a file are served a defaults object built on demand and cached, so the common
 * case - an admin who never touched world settings - costs one map hit and no disk access.
 */
class WorldSettingsRegistry(
    private val io: ConfigService,
    private val logger: java.util.logging.Logger,
) {

    private val settings = ConcurrentHashMap<String, WorldSettings>()
    private val dirty = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var config: PluginConfig = PluginConfig.from(YamlConfiguration())

    private val folder: File get() = io.file("worlds")

    fun all(): List<WorldSettings> = settings.values.sortedBy { it.world.lowercase() }

    /** Never null: an unconfigured world gets defaults, cached for next time. */
    fun of(world: String): WorldSettings =
        settings.computeIfAbsent(world.lowercase()) { WorldSettings.defaults(world, config) }

    fun of(world: org.bukkit.World): WorldSettings = of(world.name)

    fun update(updated: WorldSettings) {
        settings[updated.world.lowercase()] = updated
        dirty.add(updated.world.lowercase())
    }

    fun loadAll(config: PluginConfig, then: (Int) -> Unit) {
        this.config = config
        io.async({
            val dir = folder
            dir.mkdirs()
            val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".yml") } ?: emptyArray()
            files.mapNotNull { file ->
                val name = file.nameWithoutExtension
                try {
                    name to WorldSettings.load(name, io.load(file), config)
                } catch (t: Throwable) {
                    logger.severe("월드 설정을 읽지 못했습니다 (" + file.name + "): " + t.message)
                    null
                }
            }
        }) { loaded ->
            settings.clear()
            dirty.clear()
            loaded.forEach { (name, value) -> settings[name.lowercase()] = value }
            then(loaded.size)
        }
    }

    fun flushDirty() {
        if (dirty.isEmpty()) return
        val pending = dirty.toList()
        dirty.removeAll(pending.toSet())
        val snapshots = pending.mapNotNull { key ->
            val value = settings[key] ?: return@mapNotNull null
            val yaml = YamlConfiguration()
            value.save(yaml)
            value.world to yaml.saveToString()
        }
        if (snapshots.isEmpty()) return
        io.asyncRun {
            folder.mkdirs()
            for ((name, text) in snapshots) {
                try {
                    File(folder, "$name.yml").writeText(HEADER + text, Charsets.UTF_8)
                } catch (t: Throwable) {
                    logger.severe("월드 설정 저장 실패 ($name): " + t.message)
                }
            }
        }
    }

    fun flushBlocking() {
        if (dirty.isEmpty()) return
        val pending = dirty.toList()
        dirty.clear()
        folder.mkdirs()
        for (key in pending) {
            val value = settings[key] ?: continue
            val yaml = YamlConfiguration()
            value.save(yaml)
            try {
                File(folder, value.world + ".yml").writeText(HEADER + yaml.saveToString(), Charsets.UTF_8)
            } catch (t: Throwable) {
                logger.severe("월드 설정 저장 실패 (" + value.world + "): " + t.message)
            }
        }
    }

    private companion object {
        const val HEADER =
            "# 월드별 설정 - 여기서 지정하지 않은 값은 항상 config.yml 을 따릅니다.\n" +
                "# 대부분의 값은 /몹 GUI 에서 편집할 수 있습니다.\n\n"
    }
}
