package com.inmc.monster.spawn

import com.inmc.monster.Monsters
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * Every spawner, region-based and block-based alike.
 *
 * Region spawners are `spawners/<id>.yml`; block spawners live in the same folder and simply
 * carry a block coordinate. They are indexed by chunk so the per-tick sweep only ever looks at
 * spawners whose chunk is loaded, and each spawner refuses to run at all unless a player is
 * inside its activation range - the two things that keep fifty configured spawners from costing
 * anything on an empty server.
 */
class SpawnerRegistry(private val monsters: Monsters) {

    private val spawners = ConcurrentHashMap<String, Spawner>()
    private val dirty = ConcurrentHashMap.newKeySet<String>()

    /** Block position key -> spawner id, for the block-bound kind. */
    private val byBlock = ConcurrentHashMap<String, String>()

    private val rng = Random()

    private val folder: File get() = monsters.io.file("spawners")

    val size: Int get() = spawners.size

    fun all(): List<Spawner> = spawners.values.sortedBy { it.id.lowercase() }

    fun get(id: String?): Spawner? {
        if (id == null) return null
        return spawners[id] ?: spawners.values.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    fun exists(id: String): Boolean = get(id) != null

    fun atBlock(block: Block): Spawner? = byBlock[blockKey(block)]?.let { spawners[it] }

    fun create(id: String, location: Location, blockBound: Boolean): Spawner? {
        if (!isValidId(id) || exists(id)) return null
        val spawner = Spawner(id).apply {
            this.blockBound = blockBound
            world = location.world?.name ?: return null
            x = if (blockBound) location.blockX.toDouble() else location.x
            y = if (blockBound) location.blockY.toDouble() else location.y
            z = if (blockBound) location.blockZ.toDouble() else location.z
            if (blockBound) {
                shape = SpawnerShape.RADIUS
                radius = 4.0
            }
        }
        spawners[id] = spawner
        rebuildBlockIndex()
        markDirty(spawner)
        return spawner
    }

    fun delete(id: String): Boolean {
        val spawner = spawners.remove(id) ?: return false
        dirty.remove(id)
        rebuildBlockIndex()
        monsters.io.asyncRun { File(folder, spawner.id + ".yml").delete() }
        return true
    }

    fun markDirty(spawner: Spawner) {
        if (spawners[spawner.id] !== spawner) return
        dirty.add(spawner.id)
    }

    fun rebuildBlockIndex() {
        byBlock.clear()
        for (spawner in spawners.values) {
            if (!spawner.blockBound) continue
            byBlock[blockKey(spawner.world, spawner.x.toInt(), spawner.y.toInt(), spawner.z.toInt())] = spawner.id
        }
    }

    // --- evaluation ------------------------------------------------------------

    /** Ticker hook: runs every spawner that is due. */
    fun tick(now: Long) {
        if (spawners.isEmpty()) return
        for (spawner in spawners.values) {
            if (!spawner.enabled || !spawner.isValid()) continue
            if (now < spawner.nextAttemptTick) continue
            spawner.nextAttemptTick = now + spawner.intervalSeconds * 20L
            try {
                attempt(spawner)
            } catch (t: Throwable) {
                monsters.logger.severe("스포너 처리 실패 (" + spawner.id + "): " + t.message)
            }
        }
    }

    private fun attempt(spawner: Spawner) {
        val centre = spawner.centre() ?: return
        val world = centre.world ?: return

        val worldSettings = monsters.worlds.of(world)
        if (!worldSettings.enabled || !worldSettings.spawnersEnabled) return

        // A block spawner in an unloaded chunk is simply not there yet; forcing the chunk to
        // load so a spawner can run would defeat the whole point of chunk unloading.
        if (!centre.isChunkLoaded) return

        if (spawner.activationRange > 0.0) {
            val nearby = monsters.spawns.nearestPlayer(centre, spawner.activationRange) ?: return
            if (spawner.minPlayerDistance > 0.0 &&
                nearby.location.distanceSquared(centre) < spawner.minPlayerDistance * spawner.minPlayerDistance
            ) {
                return
            }
        }

        if (spawner.chance < 100.0 && rng.nextDouble() * 100.0 >= spawner.chance) return

        val tag = spawner.tag.ifBlank { SPAWNER_TAG_PREFIX + spawner.id }
        val alive = monsters.tracker.countWithTag(tag)
        if (alive >= spawner.maxAlive) return

        val budgetLeft = spawner.maxAlive - alive
        val wanted = if (spawner.perAttemptMax <= spawner.perAttemptMin) {
            spawner.perAttemptMin
        } else {
            spawner.perAttemptMin + rng.nextInt(spawner.perAttemptMax - spawner.perAttemptMin + 1)
        }

        repeat(minOf(wanted, budgetLeft)) {
            val entry = pickEntry(spawner) ?: return@repeat
            val definition = monsters.mobs.get(entry.mobId) ?: run {
                monsters.logger.warning(
                    "스포너 '" + spawner.id + "' 에 없는 몬스터가 지정되어 있습니다: " + entry.mobId
                )
                return@repeat
            }
            val where = pickLocation(spawner, centre) ?: return@repeat
            if (!spawner.rules.matches(where, monsters.regions)) return@repeat

            repeat(entry.amount) {
                monsters.spawns.spawn(
                    definition, where,
                    SpawnOptions(tag = tag, ignoreRules = true),
                )
            }
        }
    }

    private fun pickEntry(spawner: Spawner): SpawnerEntry? {
        if (spawner.entries.isEmpty()) return null
        if (spawner.entries.size == 1) return spawner.entries[0]
        val total = spawner.entries.sumOf { it.weight }
        if (total <= 0) return spawner.entries[rng.nextInt(spawner.entries.size)]
        var cursor = rng.nextInt(total)
        for (entry in spawner.entries) {
            cursor -= entry.weight
            if (cursor < 0) return entry
        }
        return spawner.entries.last()
    }

    /** Finds a spawn point inside the spawner's shape, or null after a few failed tries. */
    private fun pickLocation(spawner: Spawner, centre: Location): Location? {
        val world = centre.world ?: return null

        return when (spawner.shape) {
            SpawnerShape.POINT -> centre.clone()

            SpawnerShape.RADIUS -> {
                repeat(8) {
                    val angle = rng.nextDouble() * 2.0 * Math.PI
                    val distance = rng.nextDouble() * spawner.radius
                    val candidate = centre.clone().add(
                        distance * kotlin.math.cos(angle), 0.0, distance * kotlin.math.sin(angle),
                    )
                    if (!candidate.isChunkLoaded) return@repeat
                    candidate.y = (world.getHighestBlockYAt(candidate) + 1).toDouble()
                    if (isSpawnable(candidate)) return candidate
                }
                null
            }

            SpawnerShape.BOX -> {
                val minX = minOf(spawner.x, spawner.x2)
                val maxX = maxOf(spawner.x, spawner.x2)
                val minY = minOf(spawner.y, spawner.y2)
                val maxY = maxOf(spawner.y, spawner.y2)
                val minZ = minOf(spawner.z, spawner.z2)
                val maxZ = maxOf(spawner.z, spawner.z2)
                repeat(12) {
                    val candidate = Location(
                        world,
                        minX + rng.nextDouble() * (maxX - minX),
                        minY + rng.nextDouble() * (maxY - minY),
                        minZ + rng.nextDouble() * (maxZ - minZ),
                    )
                    if (!candidate.isChunkLoaded) return@repeat
                    if (isSpawnable(candidate)) return candidate
                }
                null
            }
        }
    }

    /** Two blocks of air with something solid underneath - the vanilla rule for standing room. */
    private fun isSpawnable(location: Location): Boolean {
        val feet = location.block
        if (!feet.isPassable) return false
        if (!feet.getRelative(0, 1, 0).isPassable) return false
        val ground = feet.getRelative(0, -1, 0)
        if (ground.isPassable) return false
        // Lava and fire would kill the mob the moment it appeared.
        return !ground.isLiquid && feet.type != org.bukkit.Material.FIRE
    }

    // --- persistence -----------------------------------------------------------

    fun load(then: () -> Unit) {
        monsters.io.async({
            val dir = folder
            dir.mkdirs()
            val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".yml") } ?: emptyArray()
            files.mapNotNull { file ->
                val id = file.nameWithoutExtension
                try {
                    id to Spawner.load(id, monsters.io.load(file))
                } catch (t: Throwable) {
                    monsters.logger.severe("스포너를 읽지 못했습니다 (" + file.name + "): " + t.message)
                    null
                }
            }
        }) { loaded ->
            spawners.clear()
            dirty.clear()
            loaded.forEach { (id, spawner) -> spawners[id] = spawner }
            rebuildBlockIndex()
            if (loaded.isNotEmpty()) monsters.logger.info("스포너 " + loaded.size + "개를 불러왔습니다")
            then()
        }
    }

    fun flushDirty() {
        if (dirty.isEmpty()) return
        val pending = dirty.toList()
        dirty.removeAll(pending.toSet())
        val snapshots = pending.mapNotNull { id ->
            val spawner = spawners[id] ?: return@mapNotNull null
            val config = YamlConfiguration()
            spawner.save(config)
            id to config.saveToString()
        }
        if (snapshots.isEmpty()) return
        monsters.io.asyncRun {
            folder.mkdirs()
            for ((id, text) in snapshots) {
                try {
                    File(folder, "$id.yml").writeText(HEADER + text, Charsets.UTF_8)
                } catch (t: Throwable) {
                    monsters.logger.severe("스포너 저장 실패 (" + id + "): " + t.message)
                }
            }
        }
    }

    fun flushBlocking() {
        if (dirty.isEmpty()) return
        val pending = dirty.toList()
        dirty.clear()
        folder.mkdirs()
        for (id in pending) {
            val spawner = spawners[id] ?: continue
            val config = YamlConfiguration()
            try {
                spawner.save(config)
                File(folder, "$id.yml").writeText(HEADER + config.saveToString(), Charsets.UTF_8)
            } catch (t: Throwable) {
                monsters.logger.severe("스포너 저장 실패 (" + id + "): " + t.message)
            }
        }
    }

    private fun blockKey(block: Block): String =
        blockKey(block.world.name, block.x, block.y, block.z)

    private fun blockKey(world: String, x: Int, y: Int, z: Int): String = "$world;$x;$y;$z"

    companion object {
        private val ID_PATTERN = Regex("[A-Za-z0-9가-힣_-]{1,32}")

        /** Tag automatically applied to a spawner's mobs when it does not set its own. */
        const val SPAWNER_TAG_PREFIX = "spawner:"

        private const val HEADER =
            "# 스포너 설정 - /몹 GUI 에서 편집할 수 있습니다.\n" +
                "# block-bound 가 true 면 월드에 설치된 블록에 묶인 스포너입니다.\n\n"

        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)
    }
}
