package com.inmc.monster.spawn

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import java.util.EnumSet

enum class TimeRule(val label: String) {
    ANY("아무때나"),
    DAY("낮"),
    NIGHT("밤"),
    CUSTOM("직접 지정");

    companion object {
        fun parse(raw: String?): TimeRule =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: ANY
    }
}

enum class WeatherRule(val label: String) {
    ANY("아무때나"),
    CLEAR("맑음"),
    RAIN("비"),
    THUNDER("천둥");

    companion object {
        fun parse(raw: String?): WeatherRule =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: ANY
    }
}

enum class SkyRule(val label: String) {
    ANY("상관없음"),
    SURFACE("지상 (하늘이 보이는 곳)"),
    CAVE("동굴 (하늘이 막힌 곳)");

    companion object {
        fun parse(raw: String?): SkyRule =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: ANY
    }
}

/**
 * Where and when something may spawn.
 *
 * One rule object is shared by every spawn path - vanilla replacement, region spawners, block
 * spawners and triggers - so an admin learns the conditions once and they mean the same thing
 * everywhere. It also means only one place has to be fast, and [matches] is written to bail out
 * on the cheapest checks first: sets that are empty cost nothing, and the block lookups that
 * need chunk data come last.
 */
class SpawnRules {

    /** Empty = every world. */
    var worlds: MutableSet<String> = linkedSetOf()

    /** Biome key values, e.g. `plains`, `dark_forest`. Empty = every biome. */
    var biomes: MutableSet<String> = linkedSetOf()

    var minY: Int = -64
    var maxY: Int = 320

    var time: TimeRule = TimeRule.ANY

    /** Only consulted when [time] is CUSTOM. World ticks, 0..24000, wraps around midnight. */
    var timeFrom: Long = 0
    var timeTo: Long = 24000

    var weather: WeatherRule = WeatherRule.ANY
    var sky: SkyRule = SkyRule.ANY

    var minLight: Int = 0
    var maxLight: Int = 15

    /** Moon phases 0..7 that allow the spawn. Empty = any. */
    var moonPhases: MutableSet<Int> = linkedSetOf()

    /** Blocks the mob may stand on. Empty = any solid block. */
    var standingOn: MutableSet<Material> = EnumSet.noneOf(Material::class.java)

    var radius: Int = 48
    var minNearbyPlayers: Int = 0
    var maxNearbyPlayers: Int = 0

    /** Refuses to spawn closer than this to any player, so mobs never pop into someone's face. */
    var minPlayerDistance: Double = 0.0

    /** WorldGuard region names that allow the spawn. Empty = anywhere. */
    var allowedRegions: MutableSet<String> = linkedSetOf()
    var blockedRegions: MutableSet<String> = linkedSetOf()

    /**
     * Whether spawning inside a player-owned claim is allowed.
     *
     * Off by default. Custom mobs appearing inside someone's base is the single most reliable
     * way to generate complaints, and the server here runs both Lands and WorldGuard.
     */
    var allowInClaims: Boolean = false

    fun copyOf(): SpawnRules = SpawnRules().also { copy ->
        copy.worlds = LinkedHashSet(worlds)
        copy.biomes = LinkedHashSet(biomes)
        copy.minY = minY
        copy.maxY = maxY
        copy.time = time
        copy.timeFrom = timeFrom
        copy.timeTo = timeTo
        copy.weather = weather
        copy.sky = sky
        copy.minLight = minLight
        copy.maxLight = maxLight
        copy.moonPhases = LinkedHashSet(moonPhases)
        copy.standingOn = EnumSet.noneOf(Material::class.java).also { it.addAll(standingOn) }
        copy.radius = radius
        copy.minNearbyPlayers = minNearbyPlayers
        copy.maxNearbyPlayers = maxNearbyPlayers
        copy.minPlayerDistance = minPlayerDistance
        copy.allowedRegions = LinkedHashSet(allowedRegions)
        copy.blockedRegions = LinkedHashSet(blockedRegions)
        copy.allowInClaims = allowInClaims
    }

    /**
     * True when [location] satisfies every configured condition.
     *
     * [regionCheck] is supplied by the caller rather than looked up here so this class stays
     * free of WorldGuard/Lands imports and remains unit-testable without a server.
     */
    fun matches(location: Location, regionCheck: RegionCheck = RegionCheck.PERMISSIVE): Boolean {
        val world = location.world ?: return false

        if (worlds.isNotEmpty() && !worlds.any { it.equals(world.name, ignoreCase = true) }) return false

        val y = location.blockY
        if (y < minY || y > maxY) return false

        if (time != TimeRule.ANY && !matchesTime(world.time)) return false

        if (weather != WeatherRule.ANY) {
            val thundering = world.isThundering
            val storming = world.hasStorm()
            val ok = when (weather) {
                WeatherRule.CLEAR -> !storming && !thundering
                WeatherRule.RAIN -> storming && !thundering
                WeatherRule.THUNDER -> thundering
                WeatherRule.ANY -> true
            }
            if (!ok) return false
        }

        if (moonPhases.isNotEmpty()) {
            val phase = ((world.fullTime / 24000L) % 8L).toInt()
            if (!moonPhases.contains(phase)) return false
        }

        if (minNearbyPlayers > 0 || maxNearbyPlayers > 0 || minPlayerDistance > 0.0) {
            var nearby = 0
            var closest = Double.MAX_VALUE
            val radiusSq = (radius.toDouble() * radius.toDouble())
            for (player in world.players) {
                if (!isCountable(player)) continue
                val distSq = player.location.distanceSquared(location)
                if (distSq < closest) closest = distSq
                if (distSq <= radiusSq) nearby++
            }
            if (nearby < minNearbyPlayers) return false
            if (maxNearbyPlayers > 0 && nearby > maxNearbyPlayers) return false
            if (minPlayerDistance > 0.0 && closest < minPlayerDistance * minPlayerDistance) return false
        }

        // Block-level checks last: these are the ones that touch chunk data.
        val block = location.block

        if (biomes.isNotEmpty()) {
            val key = block.biome.key.value().lowercase()
            if (!biomes.any { it.lowercase() == key }) return false
        }

        if (minLight > 0 || maxLight < 15) {
            val light = block.lightLevel.toInt()
            if (light < minLight || light > maxLight) return false
        }

        if (sky != SkyRule.ANY) {
            val open = block.lightFromSky > 0 || world.getHighestBlockYAt(location) <= y
            if (sky == SkyRule.SURFACE && !open) return false
            if (sky == SkyRule.CAVE && open) return false
        }

        if (standingOn.isNotEmpty()) {
            val below = block.getRelative(org.bukkit.block.BlockFace.DOWN).type
            if (!standingOn.contains(below)) return false
        }

        if (!regionCheck.allows(location, allowedRegions, blockedRegions, allowInClaims)) return false

        return true
    }

    private fun matchesTime(worldTime: Long): Boolean = when (time) {
        TimeRule.ANY -> true
        TimeRule.DAY -> worldTime in 0..12299
        TimeRule.NIGHT -> worldTime >= 12300 || worldTime < 0
        TimeRule.CUSTOM ->
            // A window that wraps past midnight (from 22000 to 2000) is the normal case for
            // night-time spawns, so the wrapped comparison is not a special case here.
            if (timeFrom <= timeTo) worldTime in timeFrom..timeTo
            else worldTime >= timeFrom || worldTime <= timeTo
    }

    /** Spectators and creative-mode staff should not keep a spawner awake. */
    private fun isCountable(player: Player): Boolean =
        player.gameMode != org.bukkit.GameMode.SPECTATOR

    fun save(section: ConfigurationSection, path: String) {
        val target = section.createSection(path)
        target.set("worlds", worlds.toList())
        target.set("biomes", biomes.toList())
        target.set("min-y", minY)
        target.set("max-y", maxY)
        target.set("time", time.name)
        target.set("time-from", timeFrom)
        target.set("time-to", timeTo)
        target.set("weather", weather.name)
        target.set("sky", sky.name)
        target.set("min-light", minLight)
        target.set("max-light", maxLight)
        target.set("moon-phases", moonPhases.toList())
        target.set("standing-on", standingOn.map { it.name })
        target.set("radius", radius)
        target.set("min-nearby-players", minNearbyPlayers)
        target.set("max-nearby-players", maxNearbyPlayers)
        target.set("min-player-distance", minPlayerDistance)
        target.set("allowed-regions", allowedRegions.toList())
        target.set("blocked-regions", blockedRegions.toList())
        target.set("allow-in-claims", allowInClaims)
    }

    companion object {

        fun load(section: ConfigurationSection?, path: String): SpawnRules {
            val rules = SpawnRules()
            val target = section?.getConfigurationSection(path) ?: return rules

            rules.worlds = target.getStringList("worlds").toCollection(linkedSetOf())
            rules.biomes = target.getStringList("biomes").map { it.lowercase() }.toCollection(linkedSetOf())
            rules.minY = target.getInt("min-y", -64)
            rules.maxY = target.getInt("max-y", 320)
            rules.time = TimeRule.parse(target.getString("time"))
            rules.timeFrom = target.getLong("time-from", 0L).coerceIn(0L, 24000L)
            rules.timeTo = target.getLong("time-to", 24000L).coerceIn(0L, 24000L)
            rules.weather = WeatherRule.parse(target.getString("weather"))
            rules.sky = SkyRule.parse(target.getString("sky"))
            rules.minLight = target.getInt("min-light", 0).coerceIn(0, 15)
            rules.maxLight = target.getInt("max-light", 15).coerceIn(0, 15)
            rules.moonPhases = target.getIntegerList("moon-phases").filter { it in 0..7 }.toCollection(linkedSetOf())
            rules.standingOn = EnumSet.noneOf(Material::class.java).also { set ->
                target.getStringList("standing-on").forEach { name ->
                    Material.matchMaterial(name)?.let { set.add(it) }
                }
            }
            rules.radius = target.getInt("radius", 48).coerceIn(1, 256)
            rules.minNearbyPlayers = target.getInt("min-nearby-players", 0).coerceAtLeast(0)
            rules.maxNearbyPlayers = target.getInt("max-nearby-players", 0).coerceAtLeast(0)
            rules.minPlayerDistance = target.getDouble("min-player-distance", 0.0).coerceIn(0.0, 256.0)
            rules.allowedRegions = target.getStringList("allowed-regions").toCollection(linkedSetOf())
            rules.blockedRegions = target.getStringList("blocked-regions").toCollection(linkedSetOf())
            rules.allowInClaims = target.getBoolean("allow-in-claims", false)
            return rules
        }
    }
}

/**
 * Region/claim lookups, injected so [SpawnRules] never imports WorldGuard or Lands.
 *
 * The permissive instance is what tests and servers without those plugins use.
 */
fun interface RegionCheck {

    fun allows(
        location: Location,
        allowedRegions: Set<String>,
        blockedRegions: Set<String>,
        allowInClaims: Boolean,
    ): Boolean

    companion object {
        /**
         * Used when no region plugin is installed, and by tests.
         *
         * Allows everything on purpose: an allow-list naming regions that cannot be resolved
         * would otherwise evaluate to "never matches" and silently switch the spawn off, which
         * looks like a bug in the spawn rule rather than a missing plugin.
         */
        val PERMISSIVE = RegionCheck { _, _, _, _ -> true }
    }
}
