package com.inmc.monster.runtime

import com.inmc.monster.Monsters
import com.inmc.monster.util.Ph
import kr.inmc.core.util.Text
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The name floating above a mob.
 *
 * Refreshed only when the displayed value actually changes, never on a timer. Two hundred mobs
 * re-sending a name component twenty times a second is four thousand packets per second for
 * text that mostly did not move; tracking the last rendered health and skipping the no-ops
 * turns that into approximately nothing.
 */
class Nameplates(private val monsters: Monsters) {

    /** Last health value rendered into each mob's name, so redundant updates can be skipped. */
    private val lastRendered = ConcurrentHashMap<UUID, Int>()

    fun apply(mob: ActiveMob) {
        val display = monsters.config.display
        val definition = mob.definition

        val wants = if (definition != null) {
            definition.flags.showNameplate
        } else {
            // A plain vanilla mob that only picked up an affix. It is still an ordinary zombie,
            // so it gets a nameplate only when the server asked for one.
            display.affixNameplates
        }

        if (!display.nameplates || !wants) {
            mob.entity.customName(null)
            mob.entity.isCustomNameVisible = false
            return
        }

        mob.entity.customName(render(mob))
        mob.entity.isCustomNameVisible = if (definition != null) {
            definition.flags.alwaysShowName
        } else {
            // Affixed vanilla mobs default to look-to-see rather than always-on. A field full of
            // ordinary zombies with floating labels visible across the map is not the "가끔 강력한
            // 좀비가 섞여 있다" effect the affix system is for - it just looks broken.
            display.affixAlwaysShowName
        }
        lastRendered[mob.uuid] = healthKey(mob)
    }

    /**
     * Re-renders only if the health readout would differ.
     *
     * Rounded to a whole number first: a mob taking 0.3 damage a tick from fire would otherwise
     * produce a new name every tick for a number the player cannot see change.
     */
    fun refresh(mob: ActiveMob) {
        val display = monsters.config.display
        if (!display.nameplates || !display.showHealth) return
        val wants = mob.definition?.flags?.showNameplate ?: true
        if (!wants) return

        val key = healthKey(mob)
        if (lastRendered[mob.uuid] == key) return
        lastRendered[mob.uuid] = key
        mob.entity.customName(render(mob))
    }

    fun forget(id: UUID) {
        lastRendered.remove(id)
    }

    fun clear() {
        lastRendered.clear()
    }

    private fun healthKey(mob: ActiveMob): Int = mob.entity.health.roundToInt()

    private fun render(mob: ActiveMob): net.kyori.adventure.text.Component {
        val display = monsters.config.display
        val maxHealth = mob.entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0

        val prefix = if (display.showLevel && mob.level > 0) {
            monsters.messages.raw("nameplate-level").replace("{레벨}", mob.level.toString())
        } else {
            ""
        }

        val body = if (display.showHealth) {
            monsters.messages.raw("nameplate")
        } else {
            "<white>{몬스터}</white>"
        }

        val ph = Ph.of()
            .mob(mob.displayName)
            .level(mob.level)
            .health(mob.entity.health.roundToInt().toDouble())
            .maxHealth(maxHealth.roundToInt().toDouble())

        return Text.renderFlat(prefix + body, ph)
    }
}

/**
 * Boss bars for mobs that ask for one.
 *
 * Visibility is distance-based and re-evaluated once a second by the ticker rather than per
 * tick: a player either sees the bar or does not, and a second of lag on that transition is
 * imperceptible next to the cost of checking every player against every boss twenty times over.
 */
class BossBars(private val monsters: Monsters) {

    private val viewers = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    fun create(mob: ActiveMob) {
        val definition = mob.definition ?: return
        if (!monsters.config.display.bossBars || !definition.flags.bossBar) return

        val bar = BossBar.bossBar(
            title(mob),
            1.0f,
            BossBar.Color.RED,
            BossBar.Overlay.NOTCHED_10,
        )
        mob.bossBar = bar
        viewers[mob.uuid] = ConcurrentHashMap.newKeySet()
    }

    /** Updates progress and title, and adds/removes viewers by distance. */
    fun tick(mob: ActiveMob) {
        val bar = mob.bossBar ?: return
        val radius = monsters.config.display.bossBarRadius.toDouble()
        val radiusSq = radius * radius
        val location = mob.entity.location
        val world = location.world ?: return

        val progress = (mob.healthPercent() / 100.0).coerceIn(0.0, 1.0)
        bar.progress(progress.toFloat())
        bar.name(title(mob))

        val shown = viewers.computeIfAbsent(mob.uuid) { ConcurrentHashMap.newKeySet() }

        for (player in world.players) {
            val inRange = player.location.distanceSquared(location) <= radiusSq
            val showing = shown.contains(player.uniqueId)
            if (inRange && !showing) {
                player.showBossBar(bar)
                shown.add(player.uniqueId)
            } else if (!inRange && showing) {
                player.hideBossBar(bar)
                shown.remove(player.uniqueId)
            }
        }

        // A player who changed world or logged out never gets a "left the radius" event, so
        // stale viewers are pruned here rather than leaking a bar they can no longer dismiss.
        val stale = shown.filter { id ->
            val player = org.bukkit.Bukkit.getPlayer(id)
            player == null || player.world != world
        }
        for (id in stale) {
            org.bukkit.Bukkit.getPlayer(id)?.hideBossBar(bar)
            shown.remove(id)
        }
    }

    fun remove(mob: ActiveMob) {
        val bar = mob.bossBar ?: return
        viewers.remove(mob.uuid)?.forEach { id ->
            org.bukkit.Bukkit.getPlayer(id)?.hideBossBar(bar)
        }
        mob.bossBar = null
    }

    fun hideFrom(player: Player) {
        for ((mobId, shown) in viewers) {
            if (!shown.remove(player.uniqueId)) continue
            monsters.tracker[mobId]?.bossBar?.let { player.hideBossBar(it) }
        }
    }

    fun clear() {
        for ((mobId, shown) in viewers) {
            val bar = monsters.tracker[mobId]?.bossBar ?: continue
            shown.forEach { id -> org.bukkit.Bukkit.getPlayer(id)?.hideBossBar(bar) }
        }
        viewers.clear()
    }

    private fun title(mob: ActiveMob): net.kyori.adventure.text.Component {
        val ph = Ph.of()
            .mob(mob.displayName)
            .level(mob.level)
            .phase(mob.phase?.name ?: "")
        return Text.renderFlat(monsters.messages.raw("boss-bar"), ph)
    }
}
