package com.inmc.monster.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason

/**
 * Immutable snapshot of `config.yml`.
 *
 * Read once per reload and published through a `@Volatile` field, so every lookup at runtime is
 * a field read - no disk access, no YAML walk.
 */
class PluginConfig(
    val debug: Boolean,
    val budget: SpawnBudgetConfig,
    val replacement: ReplacementConfig,
    val affixDefaults: AffixDefaults,
    val dropDefaults: DropDefaults,
    val display: DisplayConfig,
    val cleanup: CleanupConfig,
    val triggers: TriggerConfig,
    /**
     * Hard ceiling on how deep a spawn chain may go. A mob spawned by a death action or a
     * summon skill carries its parent's generation + 1; past this it is refused.
     *
     * Without it, "A spawns B on death, B spawns A on death" is a two-line config that takes
     * the server down.
     */
    val maxSpawnGeneration: Int,
    /** Tick period of the skill/pattern runner. 2 is smooth; higher trades precision for CPU. */
    val skillTickPeriod: Long,
) {

    companion object {
        fun from(config: YamlConfiguration): PluginConfig = PluginConfig(
            debug = config.getBoolean("debug", false),
            budget = SpawnBudgetConfig(
                global = config.getInt("budget.global-max", 400),
                perWorld = config.getInt("budget.per-world-max", 200),
                perChunk = config.getInt("budget.per-chunk-max", 6),
                nearPlayer = config.getInt("budget.near-player-max", 24),
                nearPlayerRadius = config.getInt("budget.near-player-radius", 32).coerceIn(8, 128),
                // Model mobs cost far more than their entity count suggests: each blueprint is
                // rendered as a pile of display entities, so they get their own ceiling.
                modelled = config.getInt("budget.modelled-max", 60),
            ),
            replacement = ReplacementConfig(
                enabled = config.getBoolean("replacement.enabled", true),
                chanceMultiplier = config.getDouble("replacement.chance-multiplier", 1.0).coerceIn(0.0, 100.0),
                allowedReasons = parseReasons(config.getStringList("replacement.allowed-spawn-reasons")),
                unknownReasons = unknownReasons(config.getStringList("replacement.allowed-spawn-reasons")),
            ),
            affixDefaults = AffixDefaults(
                vanillaEnabled = config.getBoolean("affix.vanilla-enabled", true),
                vanillaChance = config.getDouble("affix.vanilla-chance", 3.0).coerceIn(0.0, 100.0),
                customChance = config.getDouble("affix.custom-chance", 10.0).coerceIn(0.0, 100.0),
                maxPrefix = config.getInt("affix.max-prefix", 1).coerceIn(0, 3),
                maxSuffix = config.getInt("affix.max-suffix", 1).coerceIn(0, 3),
                // An affixed vanilla zombie is still a vanilla zombie. Stripping the affix on
                // reload keeps the mob alive instead of making ordinary mobs vanish under the
                // "remove everything" policy.
                stripVanillaInsteadOfRemove = config.getBoolean("affix.strip-vanilla-instead-of-remove", true),
            ),
            dropDefaults = DropDefaults(
                chance = config.getDouble("drops.default-chance", 25.0).coerceIn(0.01, 100.0),
                requirePlayerKill = config.getBoolean("drops.require-player-kill", true),
                protectSeconds = config.getInt("drops.killer-protect-seconds", 8).coerceIn(0, 120),
                announce = config.getBoolean("drops.announce", true),
            ),
            display = DisplayConfig(
                nameplates = config.getBoolean("display.nameplates", true),
                showHealth = config.getBoolean("display.show-health", false),
                showLevel = config.getBoolean("display.show-level", true),
                bossBars = config.getBoolean("display.boss-bars", true),
                bossBarRadius = config.getInt("display.boss-bar-radius", 48).coerceIn(8, 256),
                announceSpawn = config.getBoolean("display.announce-spawn", false),
                announceDeath = config.getBoolean("display.announce-death", false),
                affixNameplates = config.getBoolean("display.affix-nameplates", true),
                affixAlwaysShowName = config.getBoolean("display.affix-always-show-name", false),
            ),
            cleanup = CleanupConfig(
                onStartup = config.getBoolean("cleanup.on-startup", true),
                onChunkLoad = config.getBoolean("cleanup.on-chunk-load", true),
                onShutdown = config.getBoolean("cleanup.on-shutdown", true),
            ),
            triggers = TriggerConfig(
                enabled = config.getBoolean("triggers.enabled", true),
                counterExpiryDays = config.getInt("triggers.counter-expiry-days", 14).coerceIn(1, 365),
                defaultCooldownSeconds = config.getInt("triggers.default-cooldown-seconds", 120).coerceAtLeast(0),
                defaultDailyLimit = config.getInt("triggers.default-daily-limit", 0).coerceAtLeast(0),
            ),
            maxSpawnGeneration = config.getInt("max-spawn-generation", 3).coerceIn(0, 10),
            skillTickPeriod = config.getLong("skill-tick-period", 2L).coerceIn(1L, 20L),
        )

        /**
         * Only reasons an admin explicitly listed are honoured, and the default list is
         * NATURAL alone.
         *
         * The dangerous ones are dangerous in specific ways: REINFORCEMENTS lets a zombie call
         * a zombie, so replacing it compounds; BREEDING turns an animal farm into a monster farm;
         * SPAWNER_EGG takes the admin's own tools away. They can still be enabled here, one at
         * a time, deliberately.
         */
        private fun parseReasons(raw: List<String>): Set<SpawnReason> {
            if (raw.isEmpty()) return setOf(SpawnReason.NATURAL)
            val out = java.util.EnumSet.noneOf(SpawnReason::class.java)
            for (name in raw) {
                val reason = SpawnReason.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                if (reason != null) out.add(reason)
            }
            return if (out.isEmpty()) setOf(SpawnReason.NATURAL) else out
        }

        /** The entries of [raw] that name no spawn reason, for the warning at load. */
        private fun unknownReasons(raw: List<String>): List<String> = raw
            .map { it.trim() }
            .filter { name ->
                name.isNotEmpty() && SpawnReason.entries.none { it.name.equals(name, ignoreCase = true) }
            }
    }
}

class SpawnBudgetConfig(
    val global: Int,
    val perWorld: Int,
    val perChunk: Int,
    val nearPlayer: Int,
    val nearPlayerRadius: Int,
    val modelled: Int,
)

class ReplacementConfig(
    val enabled: Boolean,
    val chanceMultiplier: Double,
    val allowedReasons: Set<SpawnReason>,
    /**
     * Entries of `allowed-spawn-reasons` that name no spawn reason at all.
     *
     * Dropping them silently is safe - a name that matches nothing can never allow a
     * replacement - but it is also indistinguishable from the setting working, and these names
     * are easy to get wrong (the breeding reason is BREEDING, not BREED). They are reported at
     * load instead of vanishing.
     */
    val unknownReasons: List<String> = emptyList(),
)

class AffixDefaults(
    val vanillaEnabled: Boolean,
    val vanillaChance: Double,
    val customChance: Double,
    val maxPrefix: Int,
    val maxSuffix: Int,
    val stripVanillaInsteadOfRemove: Boolean,
)

class DropDefaults(
    val chance: Double,
    val requirePlayerKill: Boolean,
    val protectSeconds: Int,
    val announce: Boolean,
)

class DisplayConfig(
    val nameplates: Boolean,
    val showHealth: Boolean,
    val showLevel: Boolean,
    val bossBars: Boolean,
    val bossBarRadius: Int,
    val announceSpawn: Boolean,
    val announceDeath: Boolean,
    /** Whether an affix-only vanilla mob gets a nameplate at all. */
    val affixNameplates: Boolean,
    /**
     * Whether that nameplate floats permanently.
     *
     * Off by default. Always-on labels on ordinary field mobs are visible right across the map,
     * which turns an occasional 강력한 좀비 into visual noise.
     */
    val affixAlwaysShowName: Boolean,
)

class CleanupConfig(
    val onStartup: Boolean,
    val onChunkLoad: Boolean,
    val onShutdown: Boolean,
)

class TriggerConfig(
    val enabled: Boolean,
    val counterExpiryDays: Int,
    val defaultCooldownSeconds: Int,
    val defaultDailyLimit: Int,
)
