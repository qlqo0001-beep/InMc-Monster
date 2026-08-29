package com.inmc.monster.runtime

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Who hurt this mob, and how much.
 *
 * Needed for two things that look unrelated but are the same question. Loot distribution has to
 * answer "who earned this" - on a boss, the player who lands the killing blow is very often not
 * the one who did the work - and re-targeting has to answer "who is the biggest threat".
 *
 * Bounded by construction: one entry per player who actually landed a hit on this one mob, and
 * the whole table dies with the mob.
 */
class ThreatTable {

    private val damage = LinkedHashMap<UUID, Double>()

    /** Set when a player lands the final blow, which may not be the top contributor. */
    var lastAttacker: UUID? = null
        private set

    val isEmpty: Boolean get() = damage.isEmpty()

    fun add(player: Player, amount: Double) {
        if (amount <= 0.0) return
        damage.merge(player.uniqueId, amount, Double::plus)
        lastAttacker = player.uniqueId
    }

    fun total(): Double = damage.values.sum()

    fun of(id: UUID): Double = damage[id] ?: 0.0

    /** Contributors sorted by damage, highest first. */
    fun ranking(): List<Pair<UUID, Double>> = damage.entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }

    fun topContributor(): UUID? = damage.maxByOrNull { it.value }?.key

    /** Online contributors sorted by damage, highest first. Offline players are dropped. */
    fun onlineRanking(): List<Player> = ranking().mapNotNull { (id, _) -> Bukkit.getPlayer(id) }

    fun participants(): Set<UUID> = damage.keys.toSet()

    fun clear() {
        damage.clear()
        lastAttacker = null
    }
}
