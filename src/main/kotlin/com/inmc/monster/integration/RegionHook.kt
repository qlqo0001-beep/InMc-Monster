package com.inmc.monster.integration

import com.inmc.monster.spawn.RegionCheck
import org.bukkit.Bukkit
import org.bukkit.Location
import java.lang.reflect.Method
import java.util.logging.Logger

/**
 * WorldGuard and Lands lookups, both optional.
 *
 * WorldGuard is compile-only (its API has been stable for years). Lands is reached purely by
 * reflection because its method names have moved between major versions, and a soft integration
 * must never be able to break the build.
 *
 * This exists mostly to keep custom mobs out of player claims. Both plugins are installed on
 * this server, and mobs appearing inside somebody's base is the single most reliable way to
 * turn a new feature into a support queue.
 */
class RegionHook(private val logger: Logger) : RegionCheck {

    private var worldGuardEnabled = false
    private var landsEnabled = false

    // Lands, via reflection: LandsIntegration.of(plugin) -> getArea(Location)
    private var landsApi: Any? = null
    private var landsGetArea: Method? = null
    private var areaGetName: Method? = null

    val isEnabled: Boolean get() = worldGuardEnabled || landsEnabled

    fun setup(plugin: org.bukkit.plugin.Plugin) {
        setupWorldGuard()
        setupLands(plugin)
    }

    private fun setupWorldGuard() {
        worldGuardEnabled = false
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            logger.info("WorldGuard 미설치 - 지역 조건은 무시됩니다")
            return
        }
        try {
            PluginClasses.require("WorldGuard", "com.sk89q.worldguard.WorldGuard")
            PluginClasses.require("WorldGuard", "com.sk89q.worldedit.bukkit.BukkitAdapter")
            worldGuardEnabled = true
            logger.info("WorldGuard 연동 활성화")
        } catch (t: Throwable) {
            logger.warning("WorldGuard 연동 실패: " + t.message)
        }
    }

    private fun setupLands(plugin: org.bukkit.plugin.Plugin) {
        landsEnabled = false
        landsApi = null
        if (!Bukkit.getPluginManager().isPluginEnabled("Lands")) {
            logger.info("Lands 미설치 - 클레임 조건은 무시됩니다")
            return
        }
        try {
            val integrationClass = PluginClasses.require("Lands", "me.angeschossen.lands.api.LandsIntegration")
            val of = integrationClass.methods.first { it.name == "of" && it.parameterCount == 1 }
            landsApi = of.invoke(null, plugin)

            landsGetArea = integrationClass.methods.firstOrNull {
                (it.name == "getArea" || it.name == "getAreaByLoc") &&
                    it.parameterCount == 1 && it.parameterTypes[0] == Location::class.java
            } ?: error("getArea(Location) 를 찾을 수 없습니다")

            areaGetName = landsGetArea!!.returnType.methods.firstOrNull {
                it.name == "getName" && it.parameterCount == 0
            }

            landsEnabled = true
            logger.info("Lands 연동 활성화")
        } catch (t: Throwable) {
            landsApi = null
            logger.warning("Lands 연동 실패 (버전 불일치일 수 있습니다): " + t.message)
        }
    }

    /**
     * The rule [com.inmc.monster.spawn.SpawnRules] delegates to.
     *
     * An allow-list only permits locations *inside* one of the named regions; a block-list
     * rejects them. With no region plugin installed both lists are ignored rather than treated
     * as unsatisfiable - a spawn silently never firing because WorldGuard is missing looks like
     * a broken spawn rule, not a missing plugin.
     */
    override fun allows(
        location: Location,
        allowedRegions: Set<String>,
        blockedRegions: Set<String>,
        allowInClaims: Boolean,
    ): Boolean {
        if (allowedRegions.isEmpty() && blockedRegions.isEmpty() && allowInClaims) return true

        if (worldGuardEnabled && (allowedRegions.isNotEmpty() || blockedRegions.isNotEmpty())) {
            val present = worldGuardRegions(location)
            if (blockedRegions.isNotEmpty() &&
                blockedRegions.any { blocked -> present.any { it.equals(blocked, ignoreCase = true) } }
            ) {
                return false
            }
            if (allowedRegions.isNotEmpty()) {
                val inside = allowedRegions.any { wanted -> present.any { it.equals(wanted, ignoreCase = true) } }
                if (!inside) return false
            }
        }

        if (!allowInClaims && isClaimed(location)) return false

        return true
    }

    /** True when the location sits inside a player claim (Lands) or a non-global WG region. */
    fun isClaimed(location: Location): Boolean {
        if (landsEnabled && landsArea(location) != null) return true
        return false
    }

    fun regionNames(location: Location): Set<String> = worldGuardRegions(location)

    private fun worldGuardRegions(location: Location): Set<String> {
        if (!worldGuardEnabled) return emptySet()
        return try {
            val container = com.sk89q.worldguard.WorldGuard.getInstance().platform.regionContainer
            val query = container.createQuery()
            val adapted = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(location)
            query.getApplicableRegions(adapted).regions.mapTo(HashSet()) { it.id }
        } catch (t: Throwable) {
            logger.warning("WorldGuard 지역 조회 실패: " + t.message)
            emptySet()
        }
    }

    private fun landsArea(location: Location): String? {
        if (!landsEnabled) return null
        return try {
            val area = landsGetArea?.invoke(landsApi, location) ?: return null
            areaGetName?.invoke(area) as? String
        } catch (_: Throwable) {
            null
        }
    }
}
