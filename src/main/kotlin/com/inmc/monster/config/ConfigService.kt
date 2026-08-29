package com.inmc.monster.config

import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Every disk touch in the plugin goes through here.
 *
 * A single worker thread serialises all reads and writes, which keeps the main thread free
 * (the spec forbids main-thread I/O) and removes any chance of two saves racing on the same
 * file. Callers hand in a closure and get the result back on the main thread.
 */
class ConfigService(private val plugin: Plugin) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "inmc-monsters-io").apply { isDaemon = true }
    }

    val dataFolder: File get() = plugin.dataFolder

    fun file(vararg path: String): File = File(plugin.dataFolder, path.joinToString(File.separator))

    /** Runs [work] off-thread and drops the result on the main thread. */
    fun <T> async(work: () -> T, then: (T) -> Unit) {
        executor.execute {
            val result = try {
                work()
            } catch (t: Throwable) {
                plugin.logger.log(java.util.logging.Level.SEVERE, "비동기 작업 실패", t)
                return@execute
            }
            if (!plugin.isEnabled) return@execute
            Bukkit.getScheduler().runTask(plugin, Runnable { then(result) })
        }
    }

    /** Fire-and-forget off-thread work. */
    fun asyncRun(work: () -> Unit) {
        executor.execute {
            try {
                work()
            } catch (t: Throwable) {
                plugin.logger.log(java.util.logging.Level.SEVERE, "비동기 작업 실패", t)
            }
        }
    }

    // --- blocking helpers, only ever called from the worker thread --------------

    fun load(file: File): YamlConfiguration = YamlConfiguration.loadConfiguration(file)

    fun save(file: File, config: YamlConfiguration) {
        try {
            file.parentFile?.mkdirs()
            config.save(file)
        } catch (e: IOException) {
            plugin.logger.severe("파일 저장 실패 (${file.name}): ${e.message}")
        }
    }

    /** Copies a bundled resource when the target does not exist yet. Never overwrites. */
    fun copyDefault(resource: String, target: File) {
        if (target.exists()) return
        val stream = plugin.getResource(resource)
        if (stream == null) {
            plugin.logger.warning("내장 리소스를 찾을 수 없습니다: $resource")
            return
        }
        try {
            target.parentFile?.mkdirs()
            stream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: IOException) {
            plugin.logger.severe("리소스 복사 실패 ($resource): ${e.message}")
        }
    }

    /** Reads a bundled resource as UTF-8 text; used for the shipped message defaults. */
    fun readResource(resource: String): YamlConfiguration? {
        val stream = plugin.getResource(resource) ?: return null
        return stream.use { input ->
            YamlConfiguration.loadConfiguration(input.reader(StandardCharsets.UTF_8))
        }
    }

    /**
     * Drains queued writes and stops the worker. Called from onDisable, where blocking
     * briefly is both safe and necessary - the server is going down and the state files
     * have to land on disk.
     */
    fun shutdown() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.logger.warning("I/O 작업이 10초 안에 끝나지 않아 강제 종료합니다")
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            executor.shutdownNow()
        }
    }
}
