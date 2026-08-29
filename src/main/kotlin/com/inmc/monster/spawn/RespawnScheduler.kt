package com.inmc.monster.spawn

import com.inmc.monster.Monsters
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.util.Ph
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Timed respawns for world bosses.
 *
 * This exists because of a deliberate policy choice elsewhere: custom mobs do not survive a
 * restart. That is the right call for field mobs - it stops entity counts creeping up across
 * reboots - but it would quietly delete a world boss that nobody had killed yet. A respawn
 * timer, persisted to disk, gives that boss a way back without keeping the entity alive.
 */
class RespawnScheduler(private val monsters: Monsters) {

    private class Pending(
        val mobId: String,
        val world: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val dueAt: Long,
        val announce: Boolean,
        var warned: Boolean = false,
    )

    private val pending = ConcurrentLinkedQueue<Pending>()

    @Volatile
    private var dirty = false

    private val file: File get() = monsters.io.file("respawns.yml")

    val size: Int get() = pending.size

    fun schedule(definition: MobDefinition, location: Location, seconds: Int, announce: Boolean) {
        val world = location.world?.name ?: return
        pending.add(
            Pending(
                mobId = definition.id,
                world = world,
                x = location.x,
                y = location.y,
                z = location.z,
                dueAt = System.currentTimeMillis() + seconds * 1000L,
                announce = announce,
            ),
        )
        dirty = true
        if (monsters.config.debug) {
            monsters.logger.info("리스폰 예약: " + definition.id + " (" + seconds + "초 후)")
        }
    }

    /** Ticker hook, once a second. */
    fun tick(now: Long) {
        if (pending.isEmpty()) return
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()

            if (entry.announce && !entry.warned && entry.dueAt - now <= WARN_MILLIS) {
                entry.warned = true
                announceSoon(entry)
            }

            if (now < entry.dueAt) continue
            iterator.remove()
            dirty = true
            respawn(entry)
        }
    }

    private fun respawn(entry: Pending) {
        val definition = monsters.mobs.get(entry.mobId) ?: run {
            monsters.logger.warning("리스폰할 몬스터를 찾을 수 없습니다: " + entry.mobId)
            return
        }
        val world = org.bukkit.Bukkit.getWorld(entry.world) ?: run {
            // The world may not be loaded yet on a fresh boot; re-queue rather than drop it.
            monsters.logger.warning("리스폰 대상 월드가 없습니다 (" + entry.world + ") - 1분 뒤 다시 시도합니다")
            pending.add(
                Pending(
                    entry.mobId, entry.world, entry.x, entry.y, entry.z,
                    System.currentTimeMillis() + 60_000L, entry.announce, warned = true,
                ),
            )
            return
        }
        val location = Location(world, entry.x, entry.y, entry.z)
        val spawned = monsters.spawns.spawn(
            definition, location, SpawnOptions(ignoreRules = true, announce = entry.announce),
        )
        if (spawned == null) {
            monsters.logger.warning(
                "리스폰 실패 (" + entry.mobId + "): " + (monsters.spawns.lastRefusal ?: "알 수 없는 이유")
            )
        }
    }

    private fun announceSoon(entry: Pending) {
        val definition = monsters.mobs.get(entry.mobId) ?: return
        val remaining = ((entry.dueAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        val ph = Ph.of()
            .mob(com.inmc.monster.util.Text.plain(com.inmc.monster.util.Text.render(definition.displayName)))
            .time(remaining.toString() + "초")
            .world(entry.world)
        monsters.broadcast(monsters.messages.raw("respawn-warning"), ph)
    }

    /** Cancels every pending respawn for one mob id. Returns how many were removed. */
    fun cancel(mobId: String): Int {
        val before = pending.size
        pending.removeIf { it.mobId.equals(mobId, ignoreCase = true) }
        if (pending.size != before) dirty = true
        return before - pending.size
    }

    fun upcoming(): List<Triple<String, String, Long>> = pending.map {
        Triple(it.mobId, it.world, (it.dueAt - System.currentTimeMillis()) / 1000L)
    }.sortedBy { it.third }

    // --- persistence -----------------------------------------------------------

    fun load(then: () -> Unit) {
        monsters.io.async({
            val target = file
            if (!target.exists()) return@async emptyList<Pending>()
            val config = monsters.io.load(target)
            config.getKeys(false).mapNotNull { key ->
                val section = config.getConfigurationSection(key) ?: return@mapNotNull null
                Pending(
                    mobId = section.getString("mob") ?: return@mapNotNull null,
                    world = section.getString("world") ?: return@mapNotNull null,
                    x = section.getDouble("x"),
                    y = section.getDouble("y"),
                    z = section.getDouble("z"),
                    dueAt = section.getLong("due-at"),
                    announce = section.getBoolean("announce", true),
                )
            }
        }) { loaded ->
            pending.clear()
            pending.addAll(loaded)
            dirty = false
            if (loaded.isNotEmpty()) monsters.logger.info("리스폰 예약 " + loaded.size + "건을 불러왔습니다")
            then()
        }
    }

    fun flush() {
        if (!dirty) return
        dirty = false
        val text = serialise()
        monsters.io.asyncRun { write(text) }
    }

    fun flushBlocking() {
        if (!dirty) return
        dirty = false
        write(serialise())
    }

    private fun serialise(): String {
        val config = YamlConfiguration()
        pending.forEachIndexed { index, entry ->
            val section = config.createSection(index.toString())
            section.set("mob", entry.mobId)
            section.set("world", entry.world)
            section.set("x", entry.x)
            section.set("y", entry.y)
            section.set("z", entry.z)
            section.set("due-at", entry.dueAt)
            section.set("announce", entry.announce)
        }
        return config.saveToString()
    }

    private fun write(text: String) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(HEADER + text, Charsets.UTF_8)
        } catch (t: Throwable) {
            monsters.logger.severe("리스폰 예약 저장 실패: " + t.message)
        }
    }

    private companion object {
        /** How far ahead of a respawn the warning broadcast goes out. */
        const val WARN_MILLIS = 60_000L

        const val HEADER = "# 예약된 리스폰 - 플러그인이 자동으로 관리합니다. 직접 수정하지 마세요.\n\n"
    }
}
