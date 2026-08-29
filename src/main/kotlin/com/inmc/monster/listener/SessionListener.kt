package com.inmc.monster.listener

import com.inmc.monster.Monsters
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.world.ChunkLoadEvent

/**
 * Per-player upkeep, chunk cleanup and late-loading integrations.
 *
 * The chunk handler is the one that matters for the "custom mobs do not survive a restart"
 * policy. The tracker is empty after a reboot but the entities are not, and a mob only becomes
 * visible again when its chunk loads - so that is where the leftovers are caught.
 */
class SessionListener(private val monsters: Monsters) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        monsters.prompts.cancel(id)
        // A boss bar the player still holds would still be there when they log back in.
        monsters.bossBars.hideFrom(event.player)
    }

    /** Chat input for the admin GUI. Runs off-thread; ChatPrompt hops back before its callback. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!monsters.prompts.isWaiting(player.uniqueId)) return
        val text = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (monsters.prompts.submit(player, text)) {
            event.isCancelled = true
        }
    }

    /**
     * Removes custom mobs left behind by a previous session.
     *
     * Entities carrying only an affix are stripped instead of deleted when configured that way:
     * underneath, one of those is still an ordinary vanilla zombie, and removing it would look
     * to a player like the world quietly losing its wildlife after every restart.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (!monsters.ready) return
        if (!monsters.config.cleanup.onChunkLoad) return
        val removed = monsters.sweepChunk(event.chunk)
        if (removed > 0 && monsters.config.debug) {
            monsters.logger.info(
                "[정리] 청크 " + event.chunk.x + "," + event.chunk.z + " 에서 " + removed + "마리 제거"
            )
        }
    }

    /**
     * Re-probes soft integrations when one of them enables after us.
     *
     * Load order is deliberately unconstrained (`load: OMIT` in paper-plugin.yml) to avoid the
     * dependency cycle that cost the random-box plugin its ItemsAdder access. The price is that
     * an integration may not be enabled yet when we first look, so we look again the moment it
     * announces itself and the hook comes up without needing a reload.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginEnable(event: PluginEnableEvent) {
        val name = event.plugin.name
        if (name !in WATCHED) return
        monsters.logger.info(name + " 가 활성화되어 연동을 다시 확인합니다")
        monsters.refreshIntegrations()
    }

    companion object {
        private val WATCHED = setOf(
            "Vault",
            "PlaceholderAPI",
            "MMOItems",
            "MythicLib",
            "ModelEngine",
            "MythicMobs",
            "MagicSpells",
            "WorldGuard",
            "Lands",
            "ItemsAdder",
            "Nexo",
            "Oraxen",
            "EcoItems",
        )
    }
}
