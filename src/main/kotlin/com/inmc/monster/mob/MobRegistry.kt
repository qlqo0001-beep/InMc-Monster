package com.inmc.monster.mob

import com.inmc.monster.config.ConfigService
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import java.io.File
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * In-memory cache of every mob definition, backed by one `mobs/<id>.yml` per mob.
 *
 * Lookups never touch disk - the map is the source of truth at runtime - and writes are queued
 * through [ConfigService]'s single worker thread behind a dirty set, so a GUI session that
 * flips ten toggles still produces one file write.
 */
class MobRegistry(
    private val io: ConfigService,
    private val logger: Logger,
) {

    private val mobs = ConcurrentHashMap<String, MobDefinition>()
    private val dirty = ConcurrentHashMap.newKeySet<String>()

    /**
     * EntityType -> mobs that may replace a vanilla spawn of that type.
     *
     * Rebuilt on load and on every replacement edit. [com.inmc.monster.spawn.SpawnReplacer] runs
     * on every single CreatureSpawnEvent on the server, so its first act has to be one map hit
     * that usually returns null.
     */
    private val replacementIndex = EnumMap<EntityType, MutableList<MobDefinition>>(EntityType::class.java)

    private val folder: File get() = io.file("mobs")

    val size: Int get() = mobs.size

    fun all(): List<MobDefinition> = mobs.values.sortedBy { it.id.lowercase() }

    fun ids(): List<String> = mobs.keys.sorted()

    fun get(id: String?): MobDefinition? {
        if (id == null) return null
        return mobs[id] ?: mobs.values.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    fun exists(id: String): Boolean = get(id) != null

    /** Candidates for replacing a vanilla spawn of [type]; empty list when there are none. */
    fun replacementsFor(type: EntityType): List<MobDefinition> = replacementIndex[type] ?: emptyList()

    /** True when no mob anywhere wants to replace a vanilla spawn - lets the listener short-circuit. */
    val hasReplacements: Boolean get() = replacementIndex.isNotEmpty()

    fun withTag(tag: String): List<MobDefinition> =
        mobs.values.filter { it.tags.contains(tag.lowercase()) }

    // --- loading ---------------------------------------------------------------

    fun loadAll(defaultDropChance: Double, then: (Int) -> Unit) {
        io.async({
            val dir = folder
            dir.mkdirs()
            val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".yml") } ?: emptyArray()
            files.mapNotNull { file ->
                val id = file.nameWithoutExtension
                try {
                    id to MobDefinition.load(id, io.load(file), defaultDropChance)
                } catch (t: Throwable) {
                    logger.severe("몬스터 파일을 읽지 못했습니다 (" + file.name + "): " + t.message)
                    null
                }
            }
        }) { loaded ->
            mobs.clear()
            dirty.clear()
            loaded.forEach { (id, def) -> mobs[id] = def }
            resolveInheritance()
            rebuildReplacementIndex()
            then(loaded.size)
        }
    }

    /**
     * Folds every `parent` chain in, once, after all files are loaded.
     *
     * Done here rather than at load time because a child can be read before its parent exists in
     * the map. A cycle is reported and the whole chain is left unresolved rather than being
     * silently half-applied - a mob quietly inheriting three quarters of a template is far
     * harder to diagnose than one that says so in the log.
     */
    private fun resolveInheritance() {
        val resolved = HashSet<String>()

        fun resolve(def: MobDefinition, seen: MutableList<String>) {
            if (resolved.contains(def.id)) return
            val parentId = def.parent
            if (parentId.isNullOrBlank()) {
                resolved.add(def.id)
                return
            }
            if (seen.contains(def.id.lowercase())) {
                logger.severe(
                    "몬스터 상속이 순환합니다: " + seen.joinToString(" -> ") + " -> " + def.id +
                        " (상속을 적용하지 않습니다)"
                )
                resolved.add(def.id)
                return
            }
            val parent = get(parentId)
            if (parent == null) {
                logger.warning("'" + def.id + "' 의 부모 '" + parentId + "' 를 찾을 수 없습니다 - 상속을 건너뜁니다")
                resolved.add(def.id)
                return
            }
            seen.add(def.id.lowercase())
            resolve(parent, seen)
            seen.remove(def.id.lowercase())
            def.inheritFrom(parent)
            resolved.add(def.id)
        }

        mobs.values.forEach { resolve(it, mutableListOf()) }
    }

    // --- mutation --------------------------------------------------------------

    fun create(id: String): MobDefinition? {
        if (!isValidId(id) || exists(id)) return null
        val def = MobDefinition.create(id)
        mobs[id] = def
        rebuildReplacementIndex()
        markDirty(def)
        return def
    }

    /**
     * Duplicates a mob under a new id.
     *
     * Goes through save/load rather than copying fields by hand: the YAML round trip is the
     * definition of what a mob *is*, so a clone made this way cannot drift when a new setting is
     * added - if it persists, it copies.
     */
    fun copy(source: MobDefinition, newId: String, defaultDropChance: Double): MobDefinition? {
        if (!isValidId(newId) || exists(newId)) return null
        val config = YamlConfiguration()
        val clone = try {
            source.save(config)
            MobDefinition.load(newId, config, defaultDropChance)
        } catch (t: Throwable) {
            logger.severe("몬스터 복제 실패 (" + source.id + " -> " + newId + "): " + t.message)
            return null
        }
        // A mob that never got a custom display name should show its own id, not the source's.
        if (source.displayName == source.id) clone.displayName = newId
        mobs[newId] = clone
        rebuildReplacementIndex()
        markDirty(clone)
        return clone
    }

    fun delete(id: String): Boolean {
        val def = mobs.remove(id) ?: return false
        dirty.remove(id)
        rebuildReplacementIndex()
        io.asyncRun { File(folder, def.id + ".yml").delete() }
        return true
    }

    /**
     * Queues a mob for saving.
     *
     * Refuses a definition object that is no longer the registered one. A reload swaps every
     * instance, so a menu opened beforehand keeps editing an orphan: the screen shows the new
     * value, the save writes the *reloaded* definition, and the admin's change vanishes without
     * a word. Menus are closed on reload, but this stays as the backstop that makes the case
     * audible instead of silent.
     */
    fun markDirty(def: MobDefinition) {
        val current = mobs[def.id]
        if (current !== def) {
            if (current != null) {
                logger.warning(
                    "'" + def.id + "' 몬스터의 오래된 화면에서 수정이 들어왔습니다 - 무시합니다. " +
                        "설정을 다시 읽은 뒤에는 GUI 를 다시 열어주세요."
                )
            }
            return
        }
        dirty.add(def.id)
        // Mobs already in the world are refreshed from the ticker rather than here: a GUI
        // session produces dozens of edits in a few seconds, and re-dressing every live copy on
        // each click would do the same work over and over for one visible result.
        needsLiveRefresh.add(def.id)
    }

    /** Definitions edited since the last live refresh, drained by the ticker. */
    private val needsLiveRefresh = ConcurrentHashMap.newKeySet<String>()

    /**
     * Takes the definitions whose live mobs should be re-synced, clearing the queue.
     *
     * Returning the objects rather than the ids keeps the caller from having to re-look them up
     * and, more importantly, from acting on one that was deleted in between.
     */
    fun drainLiveRefresh(): List<MobDefinition> {
        if (needsLiveRefresh.isEmpty()) return emptyList()
        val pending = needsLiveRefresh.toList()
        needsLiveRefresh.removeAll(pending.toSet())
        return pending.mapNotNull { mobs[it] }
    }

    /** Call after any edit that touches a mob's replacement settings or entity type. */
    fun rebuildReplacementIndex() {
        replacementIndex.clear()
        for (def in mobs.values) {
            if (!def.enabled) continue
            val settings = def.replacement
            if (!settings.enabled || settings.chance <= 0.0) continue
            // An empty `replaces` set means "only my own type": the cheap, same-type path.
            val types = if (settings.replaces.isEmpty()) setOf(def.entityType) else settings.replaces
            for (type in types) {
                replacementIndex.computeIfAbsent(type) { mutableListOf() }.add(def)
            }
        }
    }

    // --- persistence -----------------------------------------------------------

    /**
     * Serialises pending mobs on the calling (main) thread - cheap, in-memory - and hands the
     * finished YAML to the I/O worker. Called once a second by the ticker.
     */
    fun flushDirty() {
        if (dirty.isEmpty()) return
        val pending = dirty.toList()
        dirty.removeAll(pending.toSet())

        val snapshots = pending.mapNotNull { id ->
            val def = mobs[id] ?: return@mapNotNull null
            val config = YamlConfiguration()
            try {
                def.save(config)
            } catch (t: Throwable) {
                logger.severe("몬스터 직렬화 실패 (" + id + "): " + t.message)
                return@mapNotNull null
            }
            id to config.saveToString()
        }
        if (snapshots.isEmpty()) return

        io.asyncRun {
            folder.mkdirs()
            for ((id, text) in snapshots) {
                try {
                    File(folder, "$id.yml").writeText(HEADER + text, Charsets.UTF_8)
                } catch (t: Throwable) {
                    logger.severe("몬스터 저장 실패 (" + id + "): " + t.message)
                }
            }
        }
    }

    /** Blocking flush used on shutdown, where the worker is about to stop. */
    fun flushDirtyBlocking() {
        if (dirty.isEmpty()) return
        val pending = dirty.toList()
        dirty.clear()
        folder.mkdirs()
        for (id in pending) {
            val def = mobs[id] ?: continue
            val config = YamlConfiguration()
            try {
                def.save(config)
                File(folder, "$id.yml").writeText(HEADER + config.saveToString(), Charsets.UTF_8)
            } catch (t: Throwable) {
                logger.severe("몬스터 저장 실패 (" + id + "): " + t.message)
            }
        }
    }

    companion object {
        /**
         * Korean is allowed on purpose - admins name mobs in Korean - which is exactly why the
         * command layer takes mob names as greedy strings rather than single words.
         */
        private val ID_PATTERN = Regex("[A-Za-z0-9가-힣_-]{1,32}")

        private const val HEADER =
            "# inmc-monster 몬스터 설정 - 대부분의 값은 /몹 GUI 에서 편집할 수 있습니다.\n" +
                "# MiniMessage 형식: https://webui.advntr.dev/\n\n"

        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)
    }
}
