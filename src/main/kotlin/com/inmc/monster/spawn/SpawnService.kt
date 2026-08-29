package com.inmc.monster.spawn

import com.inmc.monster.Monsters
import com.inmc.monster.affix.Affix
import com.inmc.monster.affix.AffixRoller
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.runtime.ActiveMob
import com.inmc.monster.skill.SkillTrigger
import com.inmc.monster.util.Ph
import com.inmc.monster.util.Text
import org.bukkit.Location
import org.bukkit.entity.Ageable
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import java.util.Random

/**
 * Options for one spawn request.
 *
 * The dungeon API hands these in, which is why [countsTowardBudget] exists: dungeon mobs live
 * inside a controlled encounter and should not be refused because the overworld happens to be
 * busy, nor should they crowd the field budget out.
 */
class SpawnOptions(
    val tag: String? = null,
    val level: Int? = null,
    val affixIds: List<String>? = null,
    val dropsEnabled: Boolean = true,
    val countsTowardBudget: Boolean = true,
    val generation: Int = 0,
    /** Skip the mob's own spawn rules. Used by `/몹 소환` and the dungeon API. */
    val ignoreRules: Boolean = false,
    val announce: Boolean? = null,
) {
    companion object {
        val DEFAULT = SpawnOptions()

        /** A mob created by another mob - inherits the parent's generation plus one. */
        fun childOf(parent: ActiveMob, inheritLevel: Boolean): SpawnOptions = SpawnOptions(
            tag = parent.tag,
            level = if (inheritLevel) parent.level else null,
            generation = parent.generation + 1,
            ignoreRules = true,
        )
    }
}

/**
 * Every custom mob in the world came through here.
 *
 * Having one entry point matters more than it looks: the budget check, the generation ceiling,
 * the stat application, the PDC stamp and the tracker registration all have to happen for every
 * spawn, and a second path that forgot one of them would produce mobs that are subtly wrong -
 * untracked, unbudgeted, or unremovable - in a way that only shows up under load.
 */
class SpawnService(private val monsters: Monsters) {

    private val rng = Random()

    /**
     * Spawns [definition] at [location].
     *
     * Returns null when the spawn was refused, and the reason is available from
     * [lastRefusal] for the caller that wants to report it.
     */
    fun spawn(
        definition: MobDefinition,
        location: Location,
        options: SpawnOptions = SpawnOptions.DEFAULT,
    ): ActiveMob? {
        lastRefusal = null

        val world = location.world ?: return refuse("월드를 찾을 수 없습니다")
        if (!definition.enabled) return refuse("몬스터가 비활성화되어 있습니다")

        val worldSettings = monsters.worlds.of(world)
        if (!worldSettings.allows(definition.id)) return refuse("이 월드에서 허용되지 않는 몬스터입니다")

        // The generation ceiling is checked before anything is created. A death action that
        // spawns the mob which spawns it back is a two-line config, and this is the only thing
        // standing between that config and an unbounded chain.
        if (options.generation > monsters.config.maxSpawnGeneration) {
            return refuse(
                "연쇄 소환 깊이 한도(" + monsters.config.maxSpawnGeneration + ")를 넘었습니다: " + definition.id
            )
        }

        if (!options.ignoreRules) {
            val rules = definition.replacement.rules
            if (!rules.matches(location, monsters.regions)) return refuse("스폰 조건을 만족하지 않습니다")
        }

        val wantsModel = definition.model.isNotBlank() && monsters.models.isEnabled
        if (options.countsTowardBudget) {
            val verdict = monsters.budget.check(location, worldSettings, wantsModel)
            if (!verdict.allowed) return refuse(verdict.label)
        }

        val entity = createEntity(definition.entityType, location)
            ?: return refuse("엔티티를 생성할 수 없습니다" + (lastCreateFailure?.let { " - " + it } ?: ""))

        return try {
            decorate(entity, definition, location, worldSettings, options, wantsModel)
        } catch (t: Throwable) {
            monsters.logger.severe("몬스터 생성 실패 (" + definition.id + "): " + t.message)
            entity.remove()
            refuse("생성 중 오류가 발생했습니다")
        }
    }

    /**
     * Turns an entity the server already created into a custom mob.
     *
     * The replacement path uses this when the custom mob shares the vanilla mob's entity type:
     * decorating the existing entity avoids cancelling the event and spawning a second one,
     * which is both cheaper and keeps the server's own spawn accounting intact.
     */
    fun adopt(
        entity: LivingEntity,
        definition: MobDefinition,
        options: SpawnOptions = SpawnOptions.DEFAULT,
    ): ActiveMob? {
        val world = entity.world
        val worldSettings = monsters.worlds.of(world)
        val wantsModel = definition.model.isNotBlank() && monsters.models.isEnabled
        return try {
            decorate(entity, definition, entity.location, worldSettings, options, wantsModel)
        } catch (t: Throwable) {
            monsters.logger.severe("몬스터 적용 실패 (" + definition.id + "): " + t.message)
            null
        }
    }

    /**
     * Attaches affixes to a plain vanilla mob without giving it a custom definition.
     *
     * This is the path that makes an ordinary field zombie occasionally spawn as a 강력한 좀비.
     * It stays intentionally light: no drop table, no skills unless an affix grants them, and
     * no phases. The mob is still a vanilla zombie, only meaner.
     */
    fun applyVanillaAffixes(entity: LivingEntity, affixes: List<Affix>): ActiveMob? {
        if (affixes.isEmpty()) return null
        val stats = monsters.statResolver.resolveVanilla(entity, affixes)
        monsters.statResolver.applyTo(entity, stats)

        val baseName = vanillaName(entity.type)
        val display = AffixRoller.decorate(baseName, affixes)

        val mob = ActiveMob(
            uuid = entity.uniqueId,
            entity = entity,
            definition = null,
            affixes = affixes,
            level = 0,
            stats = stats,
            generation = 0,
            tag = null,
            displayName = display,
        )

        monsters.tracker.keys.stamp(entity, "", 0, affixes.map { it.id }, null, 0)
        applyGlow(entity, affixes)
        monsters.nameplates.apply(mob)
        monsters.tracker.register(mob)
        return mob
    }

    // --- internals -------------------------------------------------------------

    private fun decorate(
        entity: LivingEntity,
        definition: MobDefinition,
        location: Location,
        worldSettings: com.inmc.monster.config.WorldSettings,
        options: SpawnOptions,
        wantsModel: Boolean,
    ): ActiveMob? {
        val level = options.level ?: monsters.statResolver.resolveLevel(definition, location, rng)

        val affixes = when {
            options.affixIds != null -> options.affixIds.mapNotNull { monsters.affixes.get(it) }
            else -> monsters.affixRoller.rollForCustom(definition, worldSettings, monsters.config, level, rng)
        }

        val stats = monsters.statResolver.resolve(definition, affixes, level)

        applyFlags(entity, definition)
        monsters.statResolver.applyTo(entity, stats)
        definition.equipment.applyTo(entity, monsters.itemResolver)

        val display = AffixRoller.decorate(
            Text.plain(Text.render(definition.displayName)).ifBlank { definition.id },
            affixes,
        )

        var modelled = false
        if (wantsModel) {
            modelled = monsters.models.applyModel(entity, definition.model)
        }

        val mob = ActiveMob(
            uuid = entity.uniqueId,
            entity = entity,
            definition = definition,
            affixes = affixes,
            level = level,
            stats = stats,
            generation = options.generation,
            tag = options.tag ?: definition.tags.firstOrNull(),
            displayName = display,
        ).also { it.modelled = modelled }

        if (definition.flags.lifespanSeconds > 0) {
            mob.expiresAtTick = monsters.currentTick() + definition.flags.lifespanSeconds * 20L
        }

        monsters.tracker.keys.stamp(
            entity, definition.id, level, affixes.map { it.id }, mob.tag, options.generation,
        )
        applyGlow(entity, affixes)
        mob.initialisePhase()
        monsters.nameplates.apply(mob)
        monsters.bossBars.create(mob)
        monsters.tracker.register(mob)

        // Fired before the mob does anything. A dungeon plugin that refuses this spawn must not
        // have already seen its entrance broadcast go out or its ON_SPAWN skills resolve, and the
        // caller must not be handed a live-looking handle to a mob that was just removed.
        if (!monsters.api.fireSpawnEvent(mob)) {
            lastRefusal = "다른 플러그인이 스폰을 취소했습니다"
            return null
        }

        monsters.skills.fire(mob, SkillTrigger.ON_SPAWN)

        val announce = options.announce ?: monsters.config.display.announceSpawn
        if (announce) {
            val ph = Ph.of().mob(display).mobId(definition.id).location(location).level(level)
            monsters.broadcastNear(location, monsters.messages.raw("mob-spawn-announce"), ph)
        }

        return mob
    }

    /**
     * Why the last [createEntity] call failed, so the refusal can name the real cause.
     *
     * The server's own message is worth surfacing verbatim. "Cannot spawn monster in Peaceful
     * difficulty" tells an admin exactly what to change; "엔티티를 생성할 수 없습니다" on its own
     * sends them looking through this plugin's settings for a problem that is not there.
     */
    private var lastCreateFailure: String? = null

    private fun createEntity(type: EntityType, location: Location): LivingEntity? {
        val world = location.world ?: return null
        lastCreateFailure = null
        return try {
            // CUSTOM as the reason so our own spawns cannot be picked up by the replacement
            // listener, which would otherwise let one custom mob replace another endlessly.
            val entity = world.spawnEntity(
                location, type, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,
            )
            entity as? LivingEntity ?: run {
                lastCreateFailure = type.name + " 은(는) 살아있는 엔티티가 아닙니다"
                entity.remove()
                null
            }
        } catch (t: Throwable) {
            lastCreateFailure = t.message ?: t.javaClass.simpleName
            monsters.logger.warning("엔티티 생성 실패 (" + type.name + "): " + t.message)
            null
        }
    }

    /** Also used by [com.inmc.monster.Monsters.reapply] when a definition is edited live. */
    fun applyFlags(entity: LivingEntity, definition: MobDefinition) {
        val flags = definition.flags
        entity.isSilent = flags.silent
        entity.isGlowing = flags.glowing
        entity.isInvisible = flags.invisible
        entity.setCanPickupItems(flags.canPickupItems)
        entity.isCollidable = flags.collidable
        entity.setGravity(flags.gravity)
        entity.removeWhenFarAway = flags.removeWhenFarAway
        entity.setAI(flags.aiEnabled)

        if (entity is Ageable) {
            if (flags.baby) entity.setBaby() else entity.setAdult()
        }
        if (entity is Mob) {
            entity.isAware = flags.aware
        }
        if (!flags.burnInSunlight) {
            // Only the three undead families burn in daylight, and each exposes its own setter.
            // Anything else simply has nothing to turn off.
            when (entity) {
                is org.bukkit.entity.Zombie -> entity.setShouldBurnInDay(false)
                is org.bukkit.entity.AbstractSkeleton -> entity.setShouldBurnInDay(false)
                is org.bukkit.entity.Phantom -> entity.setShouldBurnInDay(false)
                else -> Unit
            }
        }
    }

    /**
     * Turns the outline on when an affix asks for one.
     *
     * The colour is not applied: vanilla derives glow colour from the entity's scoreboard team,
     * so honouring it means creating and managing a team per colour and reassigning entities as
     * they spawn and die. That is a real feature, not a one-liner, and doing half of it would
     * leave every affix glowing the same white while the config claims otherwise.
     */
    private fun applyGlow(entity: LivingEntity, affixes: List<Affix>) {
        if (affixes.none { it.glowColor.isNotBlank() }) return
        entity.isGlowing = true
    }

    private fun vanillaName(type: EntityType): String =
        type.name.lowercase().split('_').joinToString(" ") { part ->
            part.replaceFirstChar { it.titlecase() }
        }

    // --- refusal reporting -----------------------------------------------------

    /** Human-readable reason the last [spawn] call returned null. */
    var lastRefusal: String? = null
        private set

    private fun refuse(reason: String): ActiveMob? {
        lastRefusal = reason
        if (monsters.config.debug) monsters.logger.info("[스폰 거부] " + reason)
        return null
    }

    /** Finds a safe spot near [origin] for spread-out group spawns. */
    fun scatter(origin: Location, spread: Double): Location {
        if (spread <= 0.0) return origin
        val world = origin.world ?: return origin
        repeat(8) {
            val dx = (rng.nextDouble() * 2.0 - 1.0) * spread
            val dz = (rng.nextDouble() * 2.0 - 1.0) * spread
            val candidate = origin.clone().add(dx, 0.0, dz)
            val ground = world.getHighestBlockYAt(candidate)
            // Stay near the origin's own height: teleporting a cave spawn to the surface would
            // put the mob somewhere the encounter was never designed for.
            if (kotlin.math.abs(ground - origin.blockY) <= 4) {
                candidate.y = (ground + 1).toDouble()
                if (candidate.block.isPassable) return candidate
            }
            if (candidate.block.isPassable && candidate.block.getRelative(0, 1, 0).isPassable) return candidate
        }
        return origin
    }

    fun nearestPlayer(location: Location, radius: Double): Player? {
        val world = location.world ?: return null
        val radiusSq = radius * radius
        return world.players
            .filter { it.gameMode != org.bukkit.GameMode.SPECTATOR }
            .filter { it.location.distanceSquared(location) <= radiusSq }
            .minByOrNull { it.location.distanceSquared(location) }
    }
}
