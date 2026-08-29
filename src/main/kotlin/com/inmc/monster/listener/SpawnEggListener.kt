package com.inmc.monster.listener

import com.inmc.monster.Monsters
import com.inmc.monster.spawn.SpawnOptions
import com.inmc.monster.util.Ph
import com.inmc.monster.util.Text
import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

/**
 * Right-clicking a custom spawn egg on a block spawns that mob there.
 *
 * Placement rules and the spawn budget are bypassed: an admin holding an egg is asking for the
 * mob *here*, and a silent refusal because it happens to be daytime would be baffling. The
 * permission check is what keeps that from being a problem - only staff ever hold one.
 */
class SpawnEggListener(private val monsters: Monsters) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (!monsters.ready) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return

        val stack = event.item ?: return
        val mobId = monsters.spawnEggs.idOf(stack) ?: return

        // Consumed the event whatever happens next: the egg is one of ours, so the vanilla
        // "spawn a zombie" behaviour of the underlying item must never also fire.
        event.isCancelled = true

        val player = event.player
        if (!player.hasPermission(com.inmc.monster.command.MonsterCommand.PERMISSION)) {
            monsters.messages.send(player, "no-permission")
            return
        }

        val definition = monsters.mobs.get(mobId)
        if (definition == null) {
            monsters.messages.send(player, "mob-unknown", Ph.of().mob(mobId))
            return
        }

        val block = event.clickedBlock ?: return
        val where = block.location.add(0.5, 1.0, 0.5)

        val spawned = monsters.spawns.spawn(definition, where, SpawnOptions(ignoreRules = true))
        if (spawned == null) {
            monsters.messages.send(player, "spawn-failed")
            monsters.spawns.lastRefusal?.let {
                player.sendMessage(Text.render("<dark_gray>사유: " + it + "</dark_gray>"))
            }
            return
        }

        monsters.messages.send(
            player, "spawn-success",
            Ph.of().mob(definition.displayName).location(where).count(1),
        )

        // Creative keeps its stack, matching how vanilla spawn eggs behave there.
        if (player.gameMode == GameMode.CREATIVE) return
        stack.amount -= 1
    }
}

