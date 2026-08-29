package com.inmc.monster.listener

import com.inmc.monster.Monsters
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.spawn.SpawnOptions
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import java.util.Random

/**
 * Turns some vanilla spawns into custom ones.
 *
 * This handler runs on **every creature the server spawns**, so its structure is dictated by
 * that: each check is ordered cheapest-first and the common answer is "no" within a couple of
 * map lookups.
 *
 * The most important line in the class is the spawn-reason filter. Replacing every reason looks
 * harmless in a config file and is not: REINFORCEMENTS means a zombie calling a zombie, so
 * replacing it compounds without bound; BREEDING turns a player's animal farm into a monster farm;
 * SPAWNER_EGG takes an admin's own tools away. The default allow-list is NATURAL alone, and
 * anything else has to be added deliberately.
 *
 * Vanilla spawning itself is never suppressed. A spawn that is not replaced proceeds exactly as
 * the server intended, which is what keeps ordinary mobs and animals behaving normally.
 */
class SpawnListener(private val monsters: Monsters) : Listener {

    private val rng = Random()

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onSpawn(event: CreatureSpawnEvent) {
        if (!monsters.ready) return

        val replacement = monsters.config.replacement
        val entity = event.entity

        // Our own spawns come through as CUSTOM. Letting them be seen here would allow one
        // custom mob to replace another, forever.
        if (event.spawnReason == CreatureSpawnEvent.SpawnReason.CUSTOM) return
        if (monsters.tracker.isCustom(entity)) return

        val worldSettings = monsters.worlds.of(entity.world)
        if (!worldSettings.enabled) return

        val allowed = replacement.enabled && replacement.allowedReasons.contains(event.spawnReason)

        if (allowed && monsters.mobs.hasReplacements) {
            val definition = pick(event, entity, worldSettings)
            if (definition != null) {
                replace(event, entity, definition)
                return
            }
        }

        // Not replaced - it stays a vanilla mob, but it may still earn an affix. This is the
        // "가끔씩 주석이 붙은 몬스터" path: most zombies stay ordinary, and once in a while one
        // spawns as 강력한 좀비 without any custom definition existing for it.
        if (monsters.affixes.isEmpty) return
        if (!monsters.config.affixDefaults.vanillaEnabled) return
        val affixes = monsters.affixRoller.rollForVanilla(
            entity.type, worldSettings, monsters.config, rng,
        )
        if (affixes.isNotEmpty()) monsters.spawns.applyVanillaAffixes(entity, affixes)
    }

    /** Weighted pick among the mobs that want this spawn and pass their own rules. */
    private fun pick(
        event: CreatureSpawnEvent,
        entity: LivingEntity,
        worldSettings: com.inmc.monster.config.WorldSettings,
    ): MobDefinition? {
        val candidates = monsters.mobs.replacementsFor(entity.type)
        if (candidates.isEmpty()) return null

        val location = event.location
        val eligible = ArrayList<MobDefinition>(candidates.size)

        for (definition in candidates) {
            if (!definition.enabled) continue
            if (!worldSettings.allows(definition.id)) continue

            val settings = definition.replacement
            val chance = settings.chance * replacementMultiplier(worldSettings)
            if (chance <= 0.0) continue
            if (rng.nextDouble() * 100.0 >= chance) continue

            // Rules are checked only after the chance roll passes. They are the expensive part -
            // biome, light and region lookups all touch chunk data - and there is no point
            // paying for them on a spawn that already lost its dice roll.
            if (!settings.rules.matches(location, monsters.regions)) continue

            eligible.add(definition)
        }
        if (eligible.isEmpty()) return null
        if (eligible.size == 1) return eligible[0]

        val total = eligible.sumOf { it.replacement.weight }
        var cursor = rng.nextInt(total.coerceAtLeast(1))
        for (definition in eligible) {
            cursor -= definition.replacement.weight
            if (cursor < 0) return definition
        }
        return eligible.last()
    }

    private fun replacementMultiplier(worldSettings: com.inmc.monster.config.WorldSettings): Double =
        monsters.config.replacement.chanceMultiplier * worldSettings.replacementMultiplier

    /**
     * Applies [definition] to this spawn.
     *
     * When the types match, the entity the server just created is decorated in place: no cancel,
     * no second spawn, and the server's own spawn accounting stays intact. A different type has
     * to cancel and spawn afresh, which is why an empty `replaces` list - meaning "only my own
     * type" - is both the default and the cheap path.
     */
    private fun replace(event: CreatureSpawnEvent, entity: LivingEntity, definition: MobDefinition) {
        if (definition.entityType == entity.type) {
            if (monsters.spawns.adopt(entity, definition, SpawnOptions(ignoreRules = true)) == null) {
                if (monsters.config.debug) {
                    monsters.logger.info("[치환 실패] " + definition.id + " - 엔티티를 꾸미지 못했습니다")
                }
            }
            return
        }

        event.isCancelled = true
        monsters.spawns.spawn(definition, event.location, SpawnOptions(ignoreRules = true))
    }
}
