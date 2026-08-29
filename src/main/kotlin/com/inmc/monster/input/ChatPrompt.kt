package com.inmc.monster.input

import com.inmc.monster.Monsters
import com.inmc.monster.util.Numbers
import com.inmc.monster.util.Text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat-based text entry for the admin GUI.
 *
 * Menus cannot capture free text, so anything that needs a name, a decimal chance, a message
 * or a command closes the menu and waits for one chat line. Typing 취소 / cancel aborts, and
 * an unanswered prompt times out so a forgotten one cannot leak.
 */
class ChatPrompt(private val monsters: Monsters) {

    private class Pending(
        val expiresAt: Long,
        val onInput: (String) -> Unit,
        val onCancel: () -> Unit,
    )

    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun isWaiting(playerId: UUID): Boolean = pending.containsKey(playerId)

    /**
     * Asks [player] for one line of chat. [onInput] and [onCancel] always run on the main
     * thread, so callers can reopen menus directly from them.
     */
    fun request(
        player: Player,
        promptLines: List<String>,
        timeoutSeconds: Long = 60L,
        onCancel: () -> Unit = {},
        onInput: (String) -> Unit,
    ) {
        player.closeInventory()
        pending[player.uniqueId] = Pending(
            expiresAt = System.currentTimeMillis() + timeoutSeconds * 1000L,
            onInput = onInput,
            onCancel = onCancel,
        )
        promptLines.forEach { player.sendMessage(Text.render(it, null, player)) }
        monsters.messages.send(player, "prompt-enter")
    }

    /** Convenience wrapper that re-asks until the input parses as a number. */
    fun requestDouble(
        player: Player,
        promptLines: List<String>,
        min: Double,
        max: Double,
        onCancel: () -> Unit = {},
        onValue: (Double) -> Unit,
    ) {
        request(player, promptLines, onCancel = onCancel) { input ->
            val value = input.replace(",", "").trim().toDoubleOrNull()
            if (value == null) {
                monsters.messages.send(player, "prompt-invalid-number")
                requestDouble(player, promptLines, min, max, onCancel, onValue)
            } else {
                onValue(Numbers.round2(value.coerceIn(min, max)))
            }
        }
    }

    fun requestInt(
        player: Player,
        promptLines: List<String>,
        min: Int,
        max: Int,
        onCancel: () -> Unit = {},
        onValue: (Int) -> Unit,
    ) {
        request(player, promptLines, onCancel = onCancel) { input ->
            val value = input.replace(",", "").trim().toIntOrNull()
            if (value == null) {
                monsters.messages.send(player, "prompt-invalid-number")
                requestInt(player, promptLines, min, max, onCancel, onValue)
            } else {
                onValue(value.coerceIn(min, max))
            }
        }
    }

    /**
     * Called from the async chat listener. Returns true when the line was consumed as prompt
     * input and the chat event should be cancelled.
     */
    fun submit(player: Player, message: String): Boolean {
        val entry = pending.remove(player.uniqueId) ?: return false
        val text = message.trim()

        // Chat arrives off the main thread, so the plugin may already be going down. Still
        // report the line as consumed: the prompt is dead either way, and answering "not mine"
        // would let the typed text through to public chat - which for a webhook prompt means
        // broadcasting the URL.
        if (!monsters.plugin.isEnabled) return true

        Bukkit.getScheduler().runTask(monsters.plugin, Runnable {
            if (text.equals("취소", ignoreCase = true) || text.equals("cancel", ignoreCase = true)) {
                monsters.messages.send(player, "prompt-cancelled")
                entry.onCancel()
            } else {
                entry.onInput(text)
            }
        })
        return true
    }

    fun cancel(playerId: UUID) {
        pending.remove(playerId)
    }

    /** Ticker hook: expires abandoned prompts. */
    fun tick(now: Long) {
        if (pending.isEmpty()) return
        val expired = pending.entries.filter { it.value.expiresAt <= now }
        for ((playerId, entry) in expired) {
            pending.remove(playerId)
            Bukkit.getPlayer(playerId)?.let { monsters.messages.send(it, "prompt-timeout") }
            entry.onCancel()
        }
    }
}
