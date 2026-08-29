package com.inmc.monster.integration

import org.bukkit.Bukkit

/**
 * Class lookup for soft integrations.
 *
 * `Class.forName(name)` uses *our* classloader, which only sees another plugin's classes when
 * Paper actually joined it to our classpath. That link is not guaranteed: on a real server the
 * declared dependencies formed a cycle
 * (`inmc-monsters -> MMOItems -> nightcore -> ItemsAdder -> nightcore`), Paper broke it by dropping
 * one of our edges, and the ItemsAdder hook died with a ClassNotFoundException even though the
 * class was sitting right there in the jar.
 *
 * Going through the owning plugin's own classloader removes the dependency on load order and
 * on classpath joining entirely.
 */
object PluginClasses {

    /** True when the plugin is installed, whether or not it has finished enabling. */
    fun isPresent(pluginName: String): Boolean =
        Bukkit.getPluginManager().getPlugin(pluginName) != null

    fun isEnabled(pluginName: String): Boolean =
        Bukkit.getPluginManager().isPluginEnabled(pluginName)

    /**
     * Loads [className] using [pluginName]'s classloader, falling back to ours.
     * Returns null when the plugin is absent or the class does not exist in this version.
     */
    fun find(pluginName: String, className: String): Class<*>? {
        val owner = Bukkit.getPluginManager().getPlugin(pluginName) ?: return null
        return try {
            Class.forName(className, true, owner.javaClass.classLoader)
        } catch (_: Throwable) {
            runCatching { Class.forName(className) }.getOrNull()
        }
    }

    /** Same as [find] but raises so callers can report a precise reason. */
    fun require(pluginName: String, className: String): Class<*> =
        find(pluginName, className)
            ?: throw ClassNotFoundException("$className (플러그인 $pluginName 의 클래스로더에서 찾을 수 없음)")
}
