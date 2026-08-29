package com.inmc.monster.spawn

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/** Where a spawner puts its mobs. */
enum class SpawnerShape(val label: String) {
    /** A disc of [Spawner.radius] around the centre, on whatever ground is there. */
    RADIUS("반경"),

    /** A box between two corners. */
    BOX("영역"),

    /** Exactly the centre point, every time. Used for fixed boss altars. */
    POINT("고정 지점");

    companion object {
        fun parse(raw: String?): SpawnerShape =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: RADIUS
    }
}

/** One mob a spawner may produce, with its relative weight. */
class SpawnerEntry(var mobId: String, var weight: Int = 10, var amount: Int = 1) {
    fun copyOf(): SpawnerEntry = SpawnerEntry(mobId, weight, amount)
}

/**
 * A configured spawner - the region kind and the block kind share this class.
 *
 * They differ only in where the centre comes from (a saved coordinate versus a block a player
 * placed) and in how they are removed, so a single type keeps the evaluation logic, the GUI and
 * the persistence in one place instead of two near-identical copies.
 */
class Spawner(val id: String) {

    var enabled: Boolean = true

    /** True when this spawner is bound to a block in the world. */
    var blockBound: Boolean = false

    var world: String = ""
    var x: Double = 0.0
    var y: Double = 0.0
    var z: Double = 0.0

    /** Second corner, only for [SpawnerShape.BOX]. */
    var x2: Double = 0.0
    var y2: Double = 0.0
    var z2: Double = 0.0

    var shape: SpawnerShape = SpawnerShape.RADIUS
    var radius: Double = 8.0

    /** Seconds between spawn attempts. */
    var intervalSeconds: Int = 30

    /** How many mobs from this spawner may be alive at once. */
    var maxAlive: Int = 5

    /** Mobs produced per successful attempt. */
    var perAttemptMin: Int = 1
    var perAttemptMax: Int = 1

    /** Percent chance an attempt actually spawns anything. */
    var chance: Double = 100.0

    /**
     * A player must be within this distance for the spawner to run at all.
     *
     * Zero would mean "always", which is exactly what makes a server with fifty spawners spend
     * its tick budget filling chunks nobody is standing in.
     */
    var activationRange: Double = 48.0

    /** Refuses to spawn closer than this to a player, so mobs never appear in someone's face. */
    var minPlayerDistance: Double = 4.0

    var rules: SpawnRules = SpawnRules()

    val entries: MutableList<SpawnerEntry> = mutableListOf()

    /** Block type this spawner is bound to, for the block-bound kind. */
    var blockMaterial: Material = Material.SPAWNER

    /** Tag applied to everything this spawner produces, so it can be cleaned up as a unit. */
    var tag: String = ""

    /** Runtime only: server-tick of the next attempt. Never persisted. */
    @Transient
    var nextAttemptTick: Long = 0L

    fun centre(): Location? {
        val bukkitWorld = org.bukkit.Bukkit.getWorld(world) ?: return null
        return Location(bukkitWorld, x, y, z)
    }

    fun isValid(): Boolean = world.isNotBlank() && entries.isNotEmpty()

    fun copyOf(newId: String): Spawner = Spawner(newId).also { copy ->
        copy.enabled = enabled
        copy.blockBound = blockBound
        copy.world = world
        copy.x = x; copy.y = y; copy.z = z
        copy.x2 = x2; copy.y2 = y2; copy.z2 = z2
        copy.shape = shape
        copy.radius = radius
        copy.intervalSeconds = intervalSeconds
        copy.maxAlive = maxAlive
        copy.perAttemptMin = perAttemptMin
        copy.perAttemptMax = perAttemptMax
        copy.chance = chance
        copy.activationRange = activationRange
        copy.minPlayerDistance = minPlayerDistance
        copy.rules = rules.copyOf()
        entries.forEach { copy.entries.add(it.copyOf()) }
        copy.blockMaterial = blockMaterial
        copy.tag = tag
    }

    fun save(config: YamlConfiguration) {
        config.set("enabled", enabled)
        config.set("block-bound", blockBound)
        config.set("world", world)
        config.set("x", x)
        config.set("y", y)
        config.set("z", z)
        config.set("x2", x2)
        config.set("y2", y2)
        config.set("z2", z2)
        config.set("shape", shape.name)
        config.set("radius", radius)
        config.set("interval-seconds", intervalSeconds)
        config.set("max-alive", maxAlive)
        config.set("per-attempt-min", perAttemptMin)
        config.set("per-attempt-max", perAttemptMax)
        config.set("chance", chance)
        config.set("activation-range", activationRange)
        config.set("min-player-distance", minPlayerDistance)
        config.set("block-material", blockMaterial.name)
        config.set("tag", tag.takeIf { it.isNotBlank() })
        rules.save(config, "rules")

        config.set("entries", null)
        if (entries.isNotEmpty()) {
            val target = config.createSection("entries")
            entries.forEachIndexed { index, entry ->
                val one = target.createSection(index.toString())
                one.set("mob", entry.mobId)
                one.set("weight", entry.weight)
                one.set("amount", entry.amount)
            }
        }
    }

    companion object {

        fun load(id: String, config: ConfigurationSection): Spawner {
            val spawner = Spawner(id)
            spawner.enabled = config.getBoolean("enabled", true)
            spawner.blockBound = config.getBoolean("block-bound", false)
            spawner.world = config.getString("world") ?: ""
            spawner.x = config.getDouble("x")
            spawner.y = config.getDouble("y")
            spawner.z = config.getDouble("z")
            spawner.x2 = config.getDouble("x2")
            spawner.y2 = config.getDouble("y2")
            spawner.z2 = config.getDouble("z2")
            spawner.shape = SpawnerShape.parse(config.getString("shape"))
            spawner.radius = config.getDouble("radius", 8.0).coerceIn(0.0, 128.0)
            spawner.intervalSeconds = config.getInt("interval-seconds", 30).coerceIn(1, 86_400)
            spawner.maxAlive = config.getInt("max-alive", 5).coerceIn(1, 200)
            spawner.perAttemptMin = config.getInt("per-attempt-min", 1).coerceIn(1, 50)
            spawner.perAttemptMax = config.getInt("per-attempt-max", 1).coerceIn(spawner.perAttemptMin, 50)
            spawner.chance = config.getDouble("chance", 100.0).coerceIn(0.01, 100.0)
            spawner.activationRange = config.getDouble("activation-range", 48.0).coerceIn(0.0, 256.0)
            spawner.minPlayerDistance = config.getDouble("min-player-distance", 4.0).coerceIn(0.0, 128.0)
            spawner.blockMaterial = config.getString("block-material")
                ?.let { Material.matchMaterial(it) } ?: Material.SPAWNER
            spawner.tag = config.getString("tag") ?: ""
            spawner.rules = SpawnRules.load(config, "rules")

            config.getConfigurationSection("entries")?.let { target ->
                for (key in target.getKeys(false)) {
                    val one = target.getConfigurationSection(key) ?: continue
                    val mobId = one.getString("mob")?.takeIf { it.isNotBlank() } ?: continue
                    spawner.entries.add(
                        SpawnerEntry(
                            mobId = mobId,
                            weight = one.getInt("weight", 10).coerceIn(1, 10_000),
                            amount = one.getInt("amount", 1).coerceIn(1, 20),
                        ),
                    )
                }
            }
            return spawner
        }
    }
}
