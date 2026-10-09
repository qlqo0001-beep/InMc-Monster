package com.inmc.monster.combat

import com.inmc.monster.affix.Affix
import com.inmc.monster.integration.MythicLibHook
import com.inmc.monster.mob.LevelSource
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.mob.StatKeys
import com.inmc.monster.mob.StatMap
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import java.util.Random
import kotlin.math.abs

/**
 * Turns a definition plus its rolled affixes into the numbers a mob actually fights with.
 *
 * Resolution happens exactly once, at spawn, and the result is stored on the
 * [com.inmc.monster.runtime.ActiveMob]. Recomputing per hit would mean walking affixes and
 * level curves inside the damage path for no benefit - none of those inputs change while the
 * mob is alive.
 *
 * Equipment is the deliberate exception. MMOItems stats on a mob's gear are read *live* from
 * MythicLib at damage time rather than baked in here, so an admin who edits a weapon in MMOItems
 * sees the change on mobs already standing in the world.
 */
class StatResolver(private val mythicLib: MythicLibHook) {

    /**
     * Picks the level for a mob about to spawn at [location].
     *
     * Distance-based scaling is the useful default: it makes the world get harder the further
     * out a player travels without needing a separate mob file per region.
     */
    fun resolveLevel(definition: MobDefinition, location: Location, rng: Random): Int {
        val level = definition.level
        val raw = when (level.source) {
            LevelSource.FIXED -> level.base

            LevelSource.RANDOM ->
                if (level.max <= level.min) level.min
                else level.min + rng.nextInt(level.max - level.min + 1)

            LevelSource.DISTANCE_FROM_SPAWN -> {
                val world = location.world
                if (world == null) level.base
                else {
                    val spawn = world.spawnLocation
                    val distance = if (spawn.world == location.world) spawn.distance(location) else 0.0
                    level.base + (distance / level.step).toInt()
                }
            }

            LevelSource.Y_DEPTH -> {
                // Deeper is harder: every `step` blocks below y=64 adds a level.
                val depth = (64 - location.blockY).coerceAtLeast(0)
                level.base + (depth / level.step).toInt()
            }

            LevelSource.NEARBY_PLAYERS -> {
                val world = location.world
                val nearby = world?.players?.count {
                    it.gameMode != org.bukkit.GameMode.SPECTATOR &&
                        it.location.distanceSquared(location) <= 32.0 * 32.0
                } ?: 0
                level.base + ((nearby - 1).coerceAtLeast(0) * level.step).toInt()
            }
        }
        return raw.coerceIn(level.min, level.max.coerceAtLeast(level.min))
    }

    /**
     * Final stats for a custom mob.
     *
     * Order is fixed: base, then level scaling, then affix multipliers, then affix additions.
     * Applying additions before multipliers would let an unrelated `x1.2` affix silently
     * amplify a `+5` one, so two affixes that each read as mild would combine into something
     * neither of them describes.
     */
    fun resolve(definition: MobDefinition, affixes: List<Affix>, level: Int): StatMap {
        val out = StatMap()

        for ((key, value) in definition.stats.asMap()) {
            out[key] = value * definition.level.multiplierFor(key, level)
        }
        for ((key, value) in definition.customStats.asMap()) {
            out[key] = value * definition.level.multiplierFor(key, level)
        }

        applyAffixes(out, affixes)
        return out
    }

    /**
     * Stats for a plain vanilla mob that only carries affixes.
     *
     * The base values are read off the entity the server already created, so a skeleton and a
     * ravager both get "20% more health" relative to their own vanilla numbers rather than
     * being flattened onto a shared baseline.
     */
    fun resolveVanilla(entity: LivingEntity, affixes: List<Affix>): StatMap {
        val out = StatMap()
        for ((key, attribute) in StatKeys.VANILLA) {
            val instance = entity.getAttribute(attribute) ?: continue
            // Only stats an affix actually touches are captured; writing back every attribute
            // would pin values the server is entitled to change later.
            if (affixes.any { it.modifiers.containsKey(key) }) out[key] = instance.baseValue
        }
        applyAffixes(out, affixes)
        return out
    }

    private fun applyAffixes(stats: StatMap, affixes: List<Affix>) {
        if (affixes.isEmpty()) return

        // Multiplicative pass first, across all affixes, then the additive pass.
        for (affix in affixes) {
            for ((key, modifier) in affix.modifiers) {
                if (modifier.mult == 1.0) continue
                val current = stats[key] ?: continue
                stats[key] = current * modifier.mult
            }
        }
        for (affix in affixes) {
            for ((key, modifier) in affix.modifiers) {
                if (modifier.add == 0.0) continue
                stats.add(key, modifier.add)
            }
        }
    }

    /**
     * Writes the vanilla-backed stats onto the entity.
     *
     * Uses `setBaseValue` rather than an [org.bukkit.attribute.AttributeModifier]: a mob's
     * configured health *is* its health, not a bonus on top of the zombie default, and base
     * values survive equipment changes without us having to track modifier identity.
     */
    fun applyTo(entity: LivingEntity, stats: StatMap) {
        for ((key, value) in stats.asMap()) {
            val attribute = StatKeys.VANILLA[key] ?: continue
            val instance = entity.getAttribute(attribute) ?: run {
                // Not every mob owns every attribute - a cow has no ATTACK_DAMAGE - so it is
                // registered on demand rather than skipped, otherwise a peaceful animal could
                // never be given a damage stat.
                runCatching { entity.registerAttribute(attribute) }
                entity.getAttribute(attribute)
            } ?: continue
            instance.baseValue = clampFor(attribute, value)
        }
        // Health has to be re-applied after the maximum moves, or the mob spawns at its old
        // (vanilla) health and looks like the stat did not take.
        stats["MAX_HEALTH"]?.let { max ->
            entity.health = max.coerceIn(0.1, entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: max)
        }
    }

    /**
     * Live equipment contribution for one stat, or 0 without MythicLib.
     *
     * MMOItems ships MythicLib as a hard dependency, so "MythicLib missing" also means "no
     * MMOItems", and returning zero is exactly right rather than merely safe.
     */
    fun equipmentBonus(entity: LivingEntity, key: String): Double =
        mythicLib.equipmentStat(entity, key) + com.inmc.monster.integration.CustomItemStats.equipmentStat(entity, key)

    /** Configured stat plus whatever the mob's gear adds. */
    fun effective(entity: LivingEntity, stats: StatMap, key: String): Double =
        stats.getOrZero(key) + equipmentBonus(entity, key)

    /**
     * Keeps a value inside what the server will accept.
     *
     * Vanilla rejects out-of-range attribute values with an exception, which at spawn time would
     * abort the whole mob over a single mistyped number.
     */
    private fun clampFor(attribute: Attribute, value: Double): Double {
        val safe = if (value.isNaN() || abs(value) == Double.POSITIVE_INFINITY) 0.0 else value
        return when (attribute.key.value()) {
            "max_health" -> safe.coerceIn(1.0, 1_024.0)
            "movement_speed", "flying_speed" -> safe.coerceIn(0.0, 4.0)
            "knockback_resistance" -> safe.coerceIn(0.0, 1.0)
            "scale" -> safe.coerceIn(0.0625, 16.0)
            "attack_damage" -> safe.coerceIn(0.0, 2_048.0)
            "armor" -> safe.coerceIn(0.0, 30.0)
            "armor_toughness" -> safe.coerceIn(0.0, 20.0)
            "follow_range" -> safe.coerceIn(0.0, 128.0)
            else -> safe
        }
    }
}
