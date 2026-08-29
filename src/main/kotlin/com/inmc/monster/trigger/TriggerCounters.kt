package com.inmc.monster.trigger

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player progress towards each trigger.
 *
 * Three numbers per player per trigger: how many qualifying actions they have taken, when the
 * cooldown expires, and how many times it has fired today. Small individually, but a server with
 * a dozen triggers and a few thousand players over a year is not small, so entries expire.
 *
 * The expiry is the whole reason this class exists rather than a bare map. Without it the file
 * only ever grows, and the growth is invisible until a restart takes a minute to load it.
 */
class TriggerCounters(private val expiryDays: Int) {

    private class Progress {
        var count: Int = 0
        var cooldownUntil: Long = 0L
        var firedToday: Int = 0
        var dayStamp: Long = 0L
        var lastTouched: Long = System.currentTimeMillis()
    }

    private val players = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Progress>>()

    @Volatile
    private var dirty = false

    val size: Int get() = players.size

    private fun progress(player: UUID, triggerId: String): Progress =
        players.computeIfAbsent(player) { ConcurrentHashMap() }
            .computeIfAbsent(triggerId) { Progress() }

    /** True when the player is still on cooldown for this trigger. */
    fun onCooldown(player: UUID, triggerId: String, now: Long): Boolean =
        players[player]?.get(triggerId)?.let { it.cooldownUntil > now } ?: false

    fun cooldownRemaining(player: UUID, triggerId: String, now: Long): Long {
        val until = players[player]?.get(triggerId)?.cooldownUntil ?: return 0L
        return ((until - now) / 1000L).coerceAtLeast(0L)
    }

    /** True when the player has already hit today's cap. */
    fun atDailyLimit(player: UUID, trigger: Trigger, now: Long): Boolean {
        if (trigger.dailyLimit <= 0) return false
        val entry = players[player]?.get(trigger.id) ?: return false
        rollDay(entry, now)
        return entry.firedToday >= trigger.dailyLimit
    }

    /**
     * Records one qualifying action and reports whether the count threshold is now met.
     *
     * Resets the counter when the trigger asks for it, so "every ten logs" means every ten
     * rather than "ten, then every single one after that".
     */
    fun increment(player: UUID, trigger: Trigger, now: Long): Boolean {
        val entry = progress(player, trigger.id)
        entry.lastTouched = now
        entry.count++
        dirty = true
        if (entry.count < trigger.count) return false
        if (trigger.resetCounter) entry.count = 0
        return true
    }

    fun currentCount(player: UUID, triggerId: String): Int =
        players[player]?.get(triggerId)?.count ?: 0

    /** Called after the trigger actually fired: starts the cooldown and bumps the daily tally. */
    fun markFired(player: UUID, trigger: Trigger, now: Long) {
        val entry = progress(player, trigger.id)
        entry.lastTouched = now
        entry.cooldownUntil = now + trigger.cooldownSeconds * 1000L
        rollDay(entry, now)
        entry.firedToday++
        dirty = true
    }

    private fun rollDay(entry: Progress, now: Long) {
        val today = now / DAY_MILLIS
        if (entry.dayStamp != today) {
            entry.dayStamp = today
            entry.firedToday = 0
        }
    }

    fun forget(player: UUID) {
        players.remove(player)
        dirty = true
    }

    fun clear() {
        players.clear()
        dirty = true
    }

    /** Ticker hook: drops entries nobody has touched for [expiryDays]. */
    fun purge(now: Long): Int {
        val cutoff = now - expiryDays * DAY_MILLIS
        var removed = 0
        val emptyPlayers = ArrayList<UUID>()
        for ((id, entries) in players) {
            val stale = entries.entries.filter { it.value.lastTouched < cutoff }
            stale.forEach { entries.remove(it.key); removed++ }
            if (entries.isEmpty()) emptyPlayers.add(id)
        }
        emptyPlayers.forEach { players.remove(it) }
        if (removed > 0) dirty = true
        return removed
    }

    // --- persistence -----------------------------------------------------------

    fun isDirty(): Boolean = dirty

    fun serialise(): String {
        dirty = false
        val config = YamlConfiguration()
        for ((id, entries) in players) {
            if (entries.isEmpty()) continue
            val playerSection = config.createSection(id.toString())
            for ((triggerId, entry) in entries) {
                val one = playerSection.createSection(triggerId)
                one.set("count", entry.count)
                one.set("cooldown-until", entry.cooldownUntil)
                one.set("fired-today", entry.firedToday)
                one.set("day", entry.dayStamp)
                one.set("touched", entry.lastTouched)
            }
        }
        return config.saveToString()
    }

    fun loadFrom(config: YamlConfiguration) {
        players.clear()
        for (playerKey in config.getKeys(false)) {
            val uuid = runCatching { UUID.fromString(playerKey) }.getOrNull() ?: continue
            val section = config.getConfigurationSection(playerKey) ?: continue
            val entries = ConcurrentHashMap<String, Progress>()
            for (triggerId in section.getKeys(false)) {
                val one = section.getConfigurationSection(triggerId) ?: continue
                entries[triggerId] = Progress().apply {
                    count = one.getInt("count", 0)
                    cooldownUntil = one.getLong("cooldown-until", 0L)
                    firedToday = one.getInt("fired-today", 0)
                    dayStamp = one.getLong("day", 0L)
                    lastTouched = one.getLong("touched", System.currentTimeMillis())
                }
            }
            if (entries.isNotEmpty()) players[uuid] = entries
        }
        dirty = false
    }

    companion object {
        const val DAY_MILLIS = 86_400_000L

        fun fileOf(folder: File): File = File(folder, "trigger-progress.yml")
    }
}
