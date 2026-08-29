package com.inmc.monster.listener

import com.inmc.monster.Monsters
import com.inmc.monster.trigger.TriggerType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.weather.WeatherChangeEvent

/**
 * Feeds player actions into the trigger system.
 *
 * These are among the highest-frequency events on a server - BlockBreakEvent fires for every
 * block any player mines - so every handler rejects in two cheap checks: does any trigger of
 * this type exist, and is this specific material or entity type one that any of them watches.
 * Both are backed by pre-built sets in [com.inmc.monster.trigger.TriggerRegistry], so a server
 * with no triggers configured pays a boolean per event.
 */
class TriggerListener(private val monsters: Monsters) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!ready(TriggerType.BLOCK_BREAK)) return
        if (!monsters.triggers.watches(event.block.type)) return
        monsters.triggers.handle(
            TriggerType.BLOCK_BREAK, event.player, event.block.location, material = event.block.type,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!ready(TriggerType.BLOCK_PLACE)) return
        if (!monsters.triggers.watches(event.block.type)) return
        monsters.triggers.handle(
            TriggerType.BLOCK_PLACE, event.player, event.block.location, material = event.block.type,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        if (!ready(TriggerType.MOB_KILL)) return
        val killer = event.entity.killer ?: return
        if (!monsters.triggers.watches(event.entityType)) return
        monsters.triggers.handle(
            TriggerType.MOB_KILL, killer, event.entity.location, entityType = event.entityType,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        if (!ready(TriggerType.PLAYER_DEATH)) return
        monsters.triggers.handle(TriggerType.PLAYER_DEATH, event.entity, event.entity.location)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (!ready(TriggerType.FISH_CATCH)) return
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        monsters.triggers.handle(TriggerType.FISH_CATCH, event.player, event.player.location)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (!ready(TriggerType.ITEM_CONSUME)) return
        val material = event.item.type
        if (!monsters.triggers.watches(material)) return
        monsters.triggers.handle(
            TriggerType.ITEM_CONSUME, event.player, event.player.location, material = material,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        if (!ready(TriggerType.CRAFT)) return
        val player = event.whoClicked as? org.bukkit.entity.Player ?: return
        val material = event.recipe.result.type
        if (!monsters.triggers.watches(material)) return
        monsters.triggers.handle(TriggerType.CRAFT, player, player.location, material = material)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        if (!ready(TriggerType.ENCHANT)) return
        monsters.triggers.handle(
            TriggerType.ENCHANT, event.enchanter, event.enchanter.location,
            material = event.item.type,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        if (!ready(TriggerType.PLAYER_JOIN)) return
        monsters.triggers.handle(TriggerType.PLAYER_JOIN, event.player, event.player.location)
    }

    /**
     * Weather triggers fire once per online player in the affected world.
     *
     * The event itself is world-wide, but every gate that matters - cooldown, daily limit,
     * permission - is per player, so the trigger has to be evaluated per player too.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWeather(event: WeatherChangeEvent) {
        if (!ready(TriggerType.WEATHER_CHANGE)) return
        if (!event.toWeatherState()) return
        for (player in event.world.players) {
            monsters.triggers.handle(TriggerType.WEATHER_CHANGE, player, player.location)
        }
    }

    private fun ready(type: TriggerType): Boolean =
        monsters.ready && monsters.config.triggers.enabled && monsters.triggers.hasType(type)
}
