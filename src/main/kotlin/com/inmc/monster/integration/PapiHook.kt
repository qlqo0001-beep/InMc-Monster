package com.inmc.monster.integration

import com.inmc.monster.Monsters
import com.inmc.monster.util.Text
import me.clip.placeholderapi.PlaceholderAPI
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

/**
 * PlaceholderAPI, both directions.
 *
 * Inbound: `%papi_...%` inside any configured message resolves before MiniMessage parsing.
 * Outbound: an expansion exposing live mob counts and boss state so scoreboards and HUDs can
 * show them without this plugin knowing those systems exist.
 *
 * PlaceholderAPI classes are compile-only, so nothing here may be touched unless the plugin is
 * actually present - [setup] is the only guard.
 */
class PapiHook(private val monsters: Monsters) {

    private var expansion: MonsterExpansion? = null

    val isEnabled: Boolean get() = expansion != null

    fun setup() {
        teardown()
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            monsters.logger.info("PlaceholderAPI 미설치 - %papi_...% 는 그대로 출력됩니다")
            return
        }
        try {
            Text.papiResolver = { player, text -> PlaceholderAPI.setPlaceholders(player, text) }
            expansion = MonsterExpansion(monsters).also { it.register() }
            monsters.logger.info("PlaceholderAPI 연동 활성화 (%monster_...%)")
        } catch (t: Throwable) {
            monsters.logger.warning("PlaceholderAPI 연동 실패: " + t.message)
            Text.papiResolver = null
            expansion = null
        }
    }

    fun teardown() {
        expansion?.let { runCatching { it.unregister() } }
        expansion = null
        Text.papiResolver = null
    }
}

/** `%monster_...%` placeholders. Separate class so it loads only when PlaceholderAPI exists. */
private class MonsterExpansion(private val monsters: Monsters) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "monster"

    override fun getAuthor(): String = "INMC"

    override fun getVersion(): String = monsters.plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val online = player as? Player ?: player?.uniqueId?.let { Bukkit.getPlayer(it) }

        return when {
            params.equals("active_total", true) -> monsters.tracker.size.toString()

            params.equals("definition_count", true) -> monsters.mobs.size.toString()

            params.equals("spawner_count", true) -> monsters.spawners.size.toString()

            params.equals("trigger_count", true) -> monsters.triggers.size.toString()

            params.equals("respawn_pending", true) -> monsters.respawns.size.toString()

            params.equals("active_world", true) ->
                online?.let { monsters.tracker.countIn(it.world).toString() } ?: "0"

            // %monster_active_<몹이름>%
            params.startsWith("active_", true) ->
                monsters.tracker.byDefinition(params.substring("active_".length)).size.toString()

            // %monster_tag_<태그>%
            params.startsWith("tag_", true) ->
                monsters.tracker.countWithTag(params.substring("tag_".length)).toString()

            // The nearest custom mob to the viewer, for a "what is hunting me" HUD line.
            params.equals("nearest", true) -> nearest(online)?.displayName ?: "-"

            params.equals("nearest_distance", true) -> {
                val location = online?.location ?: return "-"
                val mob = nearest(online) ?: return "-"
                mob.entity.location.distance(location).toInt().toString()
            }

            params.equals("nearest_health", true) -> {
                val mob = nearest(online) ?: return "-"
                mob.entity.health.toInt().toString()
            }

            params.equals("nearest_phase", true) -> nearest(online)?.phase?.name ?: "-"

            params.equals("nearest_level", true) -> nearest(online)?.level?.toString() ?: "-"

            // %monster_trigger_progress_<조건이름>%
            params.startsWith("trigger_progress_", true) -> {
                val id = params.substring("trigger_progress_".length)
                val uuid = online?.uniqueId ?: return "0"
                monsters.triggers.counters.currentCount(uuid, id).toString()
            }

            // %monster_trigger_cooldown_<조건이름>%  - seconds remaining
            params.startsWith("trigger_cooldown_", true) -> {
                val id = params.substring("trigger_cooldown_".length)
                val uuid = online?.uniqueId ?: return "0"
                monsters.triggers.counters
                    .cooldownRemaining(uuid, id, System.currentTimeMillis()).toString()
            }

            else -> null
        }
    }

    private fun nearest(player: Player?): com.inmc.monster.runtime.ActiveMob? {
        val location = player?.location ?: return null
        return monsters.tracker.inWorld(player.world)
            .filter { it.isAlive }
            .minByOrNull { it.entity.location.distanceSquared(location) }
    }
}
