package com.inmc.monster.affix

import com.inmc.monster.config.PluginConfig
import com.inmc.monster.config.WorldSettings
import com.inmc.monster.mob.MobDefinition
import org.bukkit.entity.EntityType
import java.util.Random

/**
 * Decides which affixes, if any, a spawning mob gets.
 *
 * Kept free of Bukkit state so the weighting can be unit-tested: everything it needs arrives as
 * arguments. The roll happens once, at spawn, and the result is fixed for that mob's life.
 */
class AffixRoller(private val registry: AffixRegistry) {

    /**
     * Rolls affixes for a **custom** mob.
     *
     * The chance comes from the mob when it sets one, otherwise from the global default, and is
     * then scaled by the world's affix multiplier - so an admin can make one world uniformly
     * more dangerous without editing a single mob.
     */
    fun rollForCustom(
        definition: MobDefinition,
        world: WorldSettings,
        config: PluginConfig,
        level: Int,
        rng: Random,
    ): List<Affix> {
        if (registry.isEmpty) return emptyList()
        val settings = definition.affixes
        if (!settings.enabled) return emptyList()

        val baseChance = if (settings.chance >= 0.0) settings.chance else config.affixDefaults.customChance
        val chance = baseChance * world.affixMultiplier
        if (chance <= 0.0 || rng.nextDouble() * 100.0 >= chance) return emptyList()

        val maxPrefix = if (settings.maxPrefix >= 0) settings.maxPrefix else config.affixDefaults.maxPrefix
        val maxSuffix = if (settings.maxSuffix >= 0) settings.maxSuffix else config.affixDefaults.maxSuffix

        return roll(
            target = AffixTarget.CUSTOM,
            entityType = definition.entityType,
            world = world.world,
            level = level,
            maxPrefix = maxPrefix,
            maxSuffix = maxSuffix,
            allowed = settings.allowed,
            blocked = settings.blocked,
            rng = rng,
        )
    }

    /**
     * Rolls affixes for a plain vanilla mob that was *not* replaced by a custom one.
     *
     * This is the "가끔씩 주석이 붙은 몬스터" case: most zombies stay ordinary zombies, and once
     * in a while one spawns as a 강력한 좀비. Deliberately a much lower default chance than the
     * custom-mob path, because it applies to every natural spawn on the server.
     */
    fun rollForVanilla(
        entityType: EntityType,
        world: WorldSettings,
        config: PluginConfig,
        rng: Random,
    ): List<Affix> {
        if (registry.isEmpty) return emptyList()
        val defaults = config.affixDefaults
        if (!defaults.vanillaEnabled) return emptyList()

        val chance = defaults.vanillaChance * world.affixMultiplier
        if (chance <= 0.0 || rng.nextDouble() * 100.0 >= chance) return emptyList()

        return roll(
            target = AffixTarget.VANILLA,
            entityType = entityType,
            world = world.world,
            level = 0,
            maxPrefix = defaults.maxPrefix,
            maxSuffix = defaults.maxSuffix,
            allowed = emptySet(),
            blocked = emptySet(),
            rng = rng,
        )
    }

    private fun roll(
        target: AffixTarget,
        entityType: EntityType,
        world: String,
        level: Int,
        maxPrefix: Int,
        maxSuffix: Int,
        allowed: Set<String>,
        blocked: Set<String>,
        rng: Random,
    ): List<Affix> {
        val picked = ArrayList<Affix>(maxPrefix + maxSuffix)

        for (type in listOf(AffixType.PREFIX, AffixType.SUFFIX)) {
            val limit = if (type == AffixType.PREFIX) maxPrefix else maxSuffix
            if (limit <= 0) continue
            val pool = registry
                .candidates(type, target, entityType, world, level, allowed, blocked)
                .toMutableList()
            repeat(limit) {
                if (pool.isEmpty()) return@repeat
                val chosen = registry.pick(pool, rng) ?: return@repeat
                picked.add(chosen)
                // Removed so the same affix cannot be rolled twice onto one mob.
                pool.remove(chosen)
            }
        }
        return picked
    }

    companion object {

        /**
         * Builds the display name: prefixes, then suffixes, then the base name.
         *
         * "강력한" + "욕심많은" + "좀비" reads as 강력한 욕심많은 좀비, which is the order the
         * request asked for and also the order Korean adjectives naturally stack in.
         */
        fun decorate(baseName: String, affixes: List<Affix>): String {
            if (affixes.isEmpty()) return baseName
            val prefixes = affixes.filter { it.type == AffixType.PREFIX }.joinToString(" ") { it.display }
            val suffixes = affixes.filter { it.type == AffixType.SUFFIX }.joinToString(" ") { it.display }
            return buildString {
                if (prefixes.isNotEmpty()) append(prefixes).append(' ')
                if (suffixes.isNotEmpty()) append(suffixes).append(' ')
                append(baseName)
            }
        }
    }
}
