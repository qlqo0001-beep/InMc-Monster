package com.inmc.monster.death

import com.inmc.monster.Monsters
import com.inmc.monster.runtime.ActiveMob
import com.inmc.monster.skill.SkillTrigger
import com.inmc.monster.spawn.SpawnOptions
import com.inmc.monster.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDeathEvent
import java.util.Random
import kotlin.math.roundToInt

/**
 * Everything that happens when a custom mob dies.
 *
 * Order is deliberate: skills first (so an ON_DEATH explosion still has the mob's position),
 * then drops, then actions, then teardown. Actions run last because [DeathActionType.SPAWN_MOB]
 * can create a new mob at the same spot, and it must not be visible to this mob's own cleanup.
 */
class DeathHandler(private val monsters: Monsters) {

    private val rng = Random()

    fun onDeath(mob: ActiveMob, event: EntityDeathEvent) {
        // A second DeathEvent for the same entity is rare but not impossible, and double-paying
        // a boss table would be the most damaging kind of bug to find after the fact.
        if (mob.deathHandled) return
        mob.deathHandled = true

        val definition = mob.definition
        val killer = event.entity.killer
        val location = event.entity.location.clone()

        monsters.skills.fire(mob, SkillTrigger.ON_DEATH, killer)

        if (definition != null) {
            handleDrops(mob, definition, event, killer)
            handleExp(mob, definition, event)
            runActions(mob, definition.deathActions, killer, location)
        }
        handleAffixDrops(mob, event, killer)

        if (monsters.config.display.announceDeath && killer != null && definition != null) {
            val ph = Ph.of().player(killer).mob(mob.displayName).location(location).level(mob.level)
            monsters.broadcast(monsters.messages.raw("mob-death-announce"), ph)
        }

        monsters.api.fireDeathEvent(mob, killer)
        monsters.cleanupMob(mob)
    }

    private fun handleDrops(
        mob: ActiveMob,
        definition: com.inmc.monster.mob.MobDefinition,
        event: EntityDeathEvent,
        killer: Player?,
    ) {
        val table = definition.drops

        // Vanilla loot is cleared unless explicitly kept: a custom mob that also drops rotten
        // flesh is almost never what an admin configured, and the equipment drop chances are
        // handled separately by the equipment settings.
        if (!table.keepVanillaDrops) event.drops.clear()

        if (table.requirePlayerKill && killer == null) return

        val drops = monsters.drops.roll(mob, table, killer)
        if (drops.isEmpty()) return
        monsters.drops.distribute(mob, table, drops, killer)
    }

    /**
     * Extra loot from whatever affixes this mob rolled.
     *
     * Runs for plain vanilla mobs too - a 강력한 좀비 has no definition and therefore no drop
     * table of its own, and without this the only thing an affix changes about an ordinary mob
     * is how hard it hits back. The tables are rolled separately from the mob's own so an affix
     * cannot be crowded out of the mob's roll ceiling.
     */
    fun handleAffixDrops(mob: ActiveMob, event: EntityDeathEvent, killer: Player?) {
        if (mob.affixes.isEmpty()) return
        for (affix in mob.affixes) {
            val table = affix.drops
            if (table.entries.isEmpty()) continue
            if (table.requirePlayerKill && killer == null) continue

            val drops = monsters.drops.roll(mob, table, killer)
            if (drops.isEmpty()) continue
            monsters.drops.distribute(mob, table, drops, killer)
        }

        // Affix experience is a multiplier on whatever the mob already gave, so a vanilla mob
        // keeps the amount the server decided and an affixed one simply pays more.
        val multiplier = mob.expMultiplier()
        if (multiplier != 1.0 && event.droppedExp > 0) {
            event.droppedExp = (event.droppedExp * multiplier).roundToInt().coerceAtLeast(0)
        }
    }

    private fun handleExp(
        mob: ActiveMob,
        definition: com.inmc.monster.mob.MobDefinition,
        event: EntityDeathEvent,
    ) {
        val table = definition.drops
        if (table.expMax <= 0) return
        val worldSettings = monsters.worlds.of(event.entity.world)
        val base = if (table.expMax <= table.expMin) {
            table.expMin
        } else {
            table.expMin + rng.nextInt(table.expMax - table.expMin + 1)
        }
        event.droppedExp = (base * mob.expMultiplier() * worldSettings.expMultiplier)
            .roundToInt().coerceAtLeast(0)
    }

    /** Runs death actions, or a phase's on-enter actions - the shapes are identical. */
    fun runActions(
        mob: ActiveMob,
        actions: List<DeathAction>,
        killer: Player?,
        location: Location,
    ) {
        if (actions.isEmpty()) return
        for (action in actions) {
            if (!action.enabled) continue
            if (action.requirePlayerKill && killer == null) continue
            if (action.worlds.isNotEmpty() &&
                !action.worlds.any { it.equals(location.world?.name, ignoreCase = true) }
            ) {
                continue
            }
            if (action.killerPermission.isNotBlank() && killer?.hasPermission(action.killerPermission) != true) {
                continue
            }
            if (action.chance < 100.0 && rng.nextDouble() * 100.0 >= action.chance) continue

            try {
                execute(mob, action, killer, location)
            } catch (t: Throwable) {
                monsters.logger.severe(
                    "사망 이벤트 실행 실패 (" + action.type.name + " / " +
                        (mob.definition?.id ?: "?") + "): " + t.message
                )
            }
        }
    }

    private fun execute(mob: ActiveMob, action: DeathAction, killer: Player?, location: Location) {
        val params = action.params()
        val world = location.world ?: return

        when (action.type) {
            DeathActionType.SPAWN_MOB -> {
                val definition = monsters.mobs.get(params.string("mob")) ?: run {
                    monsters.logger.warning("사망 소환에 없는 몬스터가 지정되어 있습니다: " + params.string("mob"))
                    return
                }
                val options = SpawnOptions.childOf(mob, params.bool("inherit-level"))
                repeat(params.int("amount")) {
                    val where = monsters.spawns.scatter(location, params.double("spread"))
                    monsters.spawns.spawn(definition, where, options)
                }
            }

            DeathActionType.RUN_COMMAND -> {
                val ph = placeholders(mob, killer, location)
                val command = Text.plain(ph.apply(params.string("command"))).removePrefix("/")
                if (command.isBlank()) return
                if (params.bool("as-player") && killer != null) {
                    killer.performCommand(command)
                } else {
                    org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), command)
                }
            }

            DeathActionType.BROADCAST -> {
                val ph = placeholders(mob, killer, location)
                val raw = params.string("message")
                if (raw.isBlank()) return
                val recipients = when (params.scope()) {
                    ActionScope.SERVER -> org.bukkit.Bukkit.getOnlinePlayers().toList()
                    ActionScope.KILLER -> listOfNotNull(killer)
                    ActionScope.RADIUS -> world.players.filter {
                        it.location.distanceSquared(location) <= params.double("radius").let { r -> r * r }
                    }
                }
                for (viewer in recipients) {
                    val rendered = Text.render(raw, ph.copy().player(viewer), viewer)
                    when (params.style()) {
                        ActionStyle.ACTIONBAR -> viewer.sendActionBar(rendered)
                        ActionStyle.TITLE -> viewer.showTitle(
                            net.kyori.adventure.title.Title.title(
                                rendered, net.kyori.adventure.text.Component.empty(),
                            ),
                        )

                        ActionStyle.CHAT -> viewer.sendMessage(rendered)
                    }
                }
            }

            DeathActionType.CAST_SKILL -> {
                monsters.skills.castById(
                    mob,
                    params.string("skill"),
                    mapOf(
                        "radius" to params.double("radius"),
                        "damage" to params.double("damage"),
                    ),
                    radius = params.double("radius"),
                )
            }

            DeathActionType.EXPLODE -> {
                world.createExplosion(
                    location,
                    params.double("power").toFloat(),
                    params.bool("fire"),
                    params.bool("break-blocks"),
                    mob.entity,
                )
            }

            DeathActionType.LIGHTNING -> {
                val real = params.bool("real-damage")
                val spread = params.double("spread")
                repeat(params.int("count")) {
                    val point = if (spread <= 0.0) location.clone() else location.clone().add(
                        (rng.nextDouble() * 2 - 1) * spread, 0.0, (rng.nextDouble() * 2 - 1) * spread,
                    )
                    point.y = world.getHighestBlockYAt(point).toDouble()
                    if (real) world.strikeLightning(point) else world.strikeLightningEffect(point)
                }
            }

            DeathActionType.GIVE_EXP -> {
                killer?.giveExp(params.int("amount"))
            }

            DeathActionType.GIVE_MONEY -> {
                val amount = params.double("amount")
                if (killer == null || amount <= 0.0) return
                if (!monsters.economy.isEnabled) {
                    monsters.logger.warning("Vault 가 없어 재화 지급을 건너뜁니다 (" + (mob.definition?.id ?: "?") + ")")
                    return
                }
                monsters.economy.deposit(killer, amount)
            }

            DeathActionType.HEAL_NEARBY -> {
                val radius = params.double("radius")
                val amount = params.double("amount")
                for (ally in monsters.tracker.all()) {
                    if (ally.uuid == mob.uuid || !ally.isAlive) continue
                    if (ally.entity.world != world) continue
                    if (ally.entity.location.distanceSquared(location) > radius * radius) continue
                    val max = ally.entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: continue
                    ally.entity.health = (ally.entity.health + amount).coerceAtMost(max)
                    world.spawnParticle(
                        org.bukkit.Particle.HEART, ally.entity.location.add(0.0, 1.0, 0.0), 5, 0.3, 0.3, 0.3, 0.0,
                    )
                }
            }

            DeathActionType.POTION_KILLER -> {
                if (killer == null) return
                val type = com.inmc.monster.skill.builtin.Fx.potion(params.string("effect")) ?: return
                killer.addPotionEffect(
                    org.bukkit.potion.PotionEffect(
                        type, params.int("duration"), params.int("amplifier").coerceIn(0, 255),
                        false, true, true,
                    ),
                )
            }

            DeathActionType.RESPAWN_TIMER -> {
                val definition = mob.definition ?: return
                monsters.respawns.schedule(
                    definition, location, params.int("seconds"), params.bool("announce"),
                )
            }

            DeathActionType.PLAY_EFFECT -> {
                val particle = com.inmc.monster.skill.builtin.Fx.particle(params.string("particle"))
                com.inmc.monster.skill.builtin.Fx.burst(
                    location, particle, params.int("count"), params.double("spread"),
                )
                com.inmc.monster.skill.builtin.Fx.play(location, params.string("sound"))
            }
        }
    }

    private fun placeholders(mob: ActiveMob, killer: Player?, location: Location): Ph {
        val ph = Ph.of()
            .mob(mob.displayName)
            .mobId(mob.definition?.id ?: "")
            .level(mob.level)
            .location(location)
        killer?.let { ph.player(it) }
        return ph
    }
}
