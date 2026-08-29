package com.inmc.monster.death

import com.inmc.monster.Monsters
import com.inmc.monster.mob.DropDistribution
import com.inmc.monster.mob.DropTable
import com.inmc.monster.mob.LootRoller
import com.inmc.monster.mob.MobDrop
import com.inmc.monster.runtime.ActiveMob
import com.inmc.monster.util.Ph
import com.inmc.monster.util.Text
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.Random
import java.util.UUID

/** One rolled drop, ready to be handed out. */
class RolledDrop(val entry: MobDrop, val stack: ItemStack?)

/**
 * Rolls a mob's drop table and hands the result to the right players.
 *
 * The rolling itself reuses [LootRoller], the same independent-trial-then-truncate rule the
 * random box plugin uses, so "10% chance" means the same thing in both places.
 *
 * Distribution is the part that matters on a boss. Whoever lands the last hit is very often not
 * the player who did the work, so the default modes read the threat table rather than the
 * killer field.
 */
class DropService(private val monsters: Monsters) {

    private val rng = Random()

    private val ownerKey: NamespacedKey by lazy { NamespacedKey(monsters.plugin, "drop_owner") }
    private val expiryKey: NamespacedKey by lazy { NamespacedKey(monsters.plugin, "drop_expiry") }

    /**
     * Rolls [table] once.
     *
     * Chance is scaled by the mob's own multipliers and by the world's, then clamped - a world
     * multiplier of 3 on an already-common drop must not silently exceed 100%.
     */
    fun roll(mob: ActiveMob, table: DropTable, killer: Player?): List<RolledDrop> {
        if (table.entries.isEmpty()) return emptyList()

        val worldSettings = monsters.worlds.of(mob.entity.world)
        val multiplier = table.chanceMultiplier * mob.dropMultiplier() * worldSettings.dropMultiplier

        val eligible = table.entries.filter { entry ->
            entry.minLevel <= mob.level && matchesTool(entry, killer)
        }
        if (eligible.isEmpty()) return emptyList()

        // The multiplier is folded into a throwaway view so the stored chances stay untouched.
        val weighted = eligible.map { ScaledDrop(it, (it.chance * multiplier).coerceIn(0.0, 100.0)) }

        val out = ArrayList<RolledDrop>()

        val defaultPool = weighted.filter { it.entry.tier == null }
        out += selectFrom(defaultPool, table.minRolls, table.maxRolls)

        for ((name, tier) in table.tiers) {
            val pool = weighted.filter { it.entry.tier == name }
            if (pool.isEmpty()) continue
            out += selectFrom(pool, tier.minRolls, tier.maxRolls)
        }
        return out
    }

    private fun selectFrom(pool: List<ScaledDrop>, minRolls: Int, maxRolls: Int): List<RolledDrop> {
        if (pool.isEmpty()) return emptyList()
        return LootRoller.select(pool, minRolls, maxRolls, rng).map { scaled ->
            val entry = scaled.entry
            val amount = LootRoller.rollAmount(entry.minAmount, entry.maxAmount, rng)
            val stack = if (entry.giveItem) monsters.itemResolver.create(entry.item, amount) else null
            RolledDrop(entry, stack)
        }
    }

    private fun matchesTool(entry: MobDrop, killer: Player?): Boolean {
        if (entry.requiredTools.isEmpty()) return true
        val held = killer?.inventory?.itemInMainHand ?: return false
        return entry.requiredTools.any { it.equals(held.type.name, ignoreCase = true) }
    }

    /**
     * Hands [drops] out according to [table]'s distribution mode.
     *
     * Commands attached to a drop run once per receiving player, which is what makes a
     * command-only entry ("give the killer a title") behave correctly under instanced loot.
     */
    fun distribute(mob: ActiveMob, table: DropTable, drops: List<RolledDrop>, killer: Player?) {
        if (drops.isEmpty()) return
        val location = mob.entity.location

        when (table.distribution) {
            DropDistribution.GROUND -> {
                drops.forEach { drop ->
                    drop.stack?.let { dropOnGround(location, it, null, 0) }
                    runCommands(mob, drop, killer)
                }
            }

            DropDistribution.KILLER -> {
                val receiver = killer ?: return groundFallback(mob, drops, location)
                give(mob, table, drops, receiver, location)
            }

            DropDistribution.TOP_DAMAGE -> {
                val top = mob.threat.topContributor()?.let { org.bukkit.Bukkit.getPlayer(it) } ?: killer
                    ?: return groundFallback(mob, drops, location)
                give(mob, table, drops, top, location)
            }

            DropDistribution.INSTANCED -> {
                // Each participant rolls the table again for themselves, so nobody competes and
                // nobody sees a rare drop go to someone else.
                val participants = mob.threat.onlineRanking().ifEmpty { listOfNotNull(killer) }
                if (participants.isEmpty()) return groundFallback(mob, drops, location)
                for (player in participants) {
                    give(mob, table, roll(mob, table, player), player, location)
                }
            }

            DropDistribution.SHARED -> {
                val participants = mob.threat.onlineRanking().ifEmpty { listOfNotNull(killer) }
                if (participants.isEmpty()) return groundFallback(mob, drops, location)
                drops.forEachIndexed { index, drop ->
                    val receiver = participants[index % participants.size]
                    give(mob, table, listOf(drop), receiver, location)
                }
            }
        }
    }

    private fun groundFallback(mob: ActiveMob, drops: List<RolledDrop>, location: Location) {
        // No eligible player - the mob died to fire, another mob, or someone who logged out.
        // Dropping on the ground beats silently deleting loot that was already rolled, and with
        // nobody to reserve it for there is nothing to protect.
        drops.forEach { drop ->
            drop.stack?.let { dropOnGround(location, it, null, 0) }
            runCommands(mob, drop, null)
        }
    }

    private fun give(
        mob: ActiveMob,
        table: DropTable,
        drops: List<RolledDrop>,
        receiver: Player,
        location: Location,
    ) {
        for (drop in drops) {
            val stack = drop.stack
            if (stack != null) {
                val leftovers = receiver.inventory.addItem(stack)
                if (leftovers.isNotEmpty()) {
                    leftovers.values.forEach {
                        dropOnGround(location, it, receiver.uniqueId, table.protectSeconds)
                    }
                    monsters.messages.send(receiver, "drop-inventory-full")
                }
            }
            runCommands(mob, drop, receiver)
            announce(mob, drop, receiver)
        }
    }

    private fun dropOnGround(location: Location, stack: ItemStack, owner: UUID?, protectSeconds: Int) {
        val world = location.world ?: return
        val item: Item = world.dropItemNaturally(location, stack)
        if (owner == null || protectSeconds <= 0) return
        // Reservation lives on the item itself, so it survives a chunk unload and needs no
        // repeating task to expire it - the pickup listener simply compares timestamps.
        item.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, owner.toString())
        item.persistentDataContainer.set(
            expiryKey, PersistentDataType.LONG, System.currentTimeMillis() + protectSeconds * 1000L,
        )
    }

    /**
     * Whether [player] may pick this item up right now.
     *
     * Returns true for anything we never reserved, which is every ordinary item on the server.
     */
    fun canPickUp(item: Item, player: Player): Boolean {
        val container = item.persistentDataContainer
        val owner = container.get(ownerKey, PersistentDataType.STRING) ?: return true
        val expiry = container.get(expiryKey, PersistentDataType.LONG) ?: return true
        if (System.currentTimeMillis() >= expiry) {
            // Reservation lapsed - strip the tags so the check short-circuits from now on.
            container.remove(ownerKey)
            container.remove(expiryKey)
            return true
        }
        return owner == player.uniqueId.toString()
    }

    private fun runCommands(mob: ActiveMob, drop: RolledDrop, receiver: Player?) {
        if (drop.entry.commands.isEmpty()) return
        val ph = Ph.of()
            .mob(mob.displayName)
            .mobId(mob.definition?.id ?: "")
            .level(mob.level)
            .item(drop.entry.label())
            .location(mob.entity.location)
        receiver?.let { ph.player(it) }

        for (raw in drop.entry.commands) {
            val command = Text.plain(ph.apply(raw)).removePrefix("/")
            if (command.isBlank()) continue
            try {
                org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), command)
            } catch (t: Throwable) {
                monsters.logger.warning("드랍 명령어 실행 실패 (" + command + "): " + t.message)
            }
        }
    }

    private fun announce(mob: ActiveMob, drop: RolledDrop, receiver: Player) {
        if (!drop.entry.announce || !monsters.config.dropDefaults.announce) return
        val ph = Ph.of()
            .player(receiver)
            .mob(mob.displayName)
            .item(drop.entry.label())
            .location(mob.entity.location)
        val message = monsters.messages.raw("drop-announce")
        org.bukkit.Bukkit.getOnlinePlayers().forEach { viewer ->
            viewer.sendMessage(Text.render(message, ph, viewer))
        }
    }

    /** Wraps a drop with its scaled chance so [LootRoller] can weigh it without mutation. */
    private class ScaledDrop(val entry: MobDrop, override val chance: Double) : com.inmc.monster.mob.Weighted
}
