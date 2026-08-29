package com.inmc.monster.trigger

import com.inmc.monster.Monsters
import com.inmc.monster.spawn.SpawnOptions
import com.inmc.monster.util.Ph
import com.inmc.monster.util.Text
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import java.io.File
import java.util.EnumSet
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * Every configured trigger, plus the indexes that make them free when nothing matches.
 *
 * The listener for BLOCK_BREAK sees every block any player breaks anywhere on the server. It
 * therefore cannot afford to walk a list of triggers per event, so the materials that any
 * trigger cares about are collected into one [EnumSet] and the handler's first line is a single
 * set lookup that almost always says no.
 */
class TriggerRegistry(private val monsters: Monsters) {

    private val triggers = ConcurrentHashMap<String, Trigger>()
    private val dirty = ConcurrentHashMap.newKeySet<String>()

    private val rng = Random()

    /** Union of every material any BLOCK_BREAK / BLOCK_PLACE / item trigger watches. */
    private var watchedMaterials: EnumSet<Material> = EnumSet.noneOf(Material::class.java)

    /** Union of every entity type any MOB_KILL trigger watches. */
    private var watchedEntities: EnumSet<EntityType> = EnumSet.noneOf(EntityType::class.java)

    /** True when at least one trigger of each type exists, so listeners can bail immediately. */
    private var activeTypes: MutableSet<TriggerType> = EnumSet.noneOf(TriggerType::class.java)

    var counters: TriggerCounters = TriggerCounters(14)
        private set

    private val folder: File get() = monsters.io.file("triggers")

    val size: Int get() = triggers.size

    fun all(): List<Trigger> = triggers.values.sortedBy { it.id.lowercase() }

    fun get(id: String?): Trigger? {
        if (id == null) return null
        return triggers[id] ?: triggers.values.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    fun exists(id: String): Boolean = get(id) != null

    fun create(id: String): Trigger? {
        if (!isValidId(id) || exists(id)) return null
        val trigger = Trigger(id)
        triggers[id] = trigger
        rebuildIndexes()
        markDirty(trigger)
        return trigger
    }

    fun delete(id: String): Boolean {
        val trigger = triggers.remove(id) ?: return false
        dirty.remove(id)
        rebuildIndexes()
        monsters.io.asyncRun { File(folder, trigger.id + ".yml").delete() }
        return true
    }

    fun markDirty(trigger: Trigger) {
        if (triggers[trigger.id] !== trigger) return
        dirty.add(trigger.id)
    }

    // --- fast rejection --------------------------------------------------------

    fun hasType(type: TriggerType): Boolean = activeTypes.contains(type)

    fun watches(material: Material): Boolean =
        watchedMaterials.isEmpty() || watchedMaterials.contains(material)

    fun watches(type: EntityType): Boolean =
        watchedEntities.isEmpty() || watchedEntities.contains(type)

    fun rebuildIndexes() {
        val materials = EnumSet.noneOf(Material::class.java)
        val entities = EnumSet.noneOf(EntityType::class.java)
        val types = EnumSet.noneOf(TriggerType::class.java)

        for (trigger in triggers.values) {
            if (!trigger.enabled) continue
            types.add(trigger.type)
            // An empty material set means "any", which has to widen the index to everything -
            // otherwise the fast path would reject events the trigger actually wanted.
            if (trigger.materials.isEmpty() && trigger.type.countable) {
                materials.addAll(Material.entries)
            } else {
                materials.addAll(trigger.materials)
            }
            if (trigger.entityTypes.isEmpty() && trigger.type == TriggerType.MOB_KILL) {
                entities.addAll(EntityType.entries)
            } else {
                entities.addAll(trigger.entityTypes)
            }
        }
        watchedMaterials = materials
        watchedEntities = entities
        activeTypes = types
    }

    // --- evaluation ------------------------------------------------------------

    /**
     * Runs every trigger of [type] for [player].
     *
     * [material] and [entityType] are the specifics of what happened; either may be null when
     * the trigger type does not involve one.
     */
    fun handle(
        type: TriggerType,
        player: Player,
        eventLocation: Location,
        material: Material? = null,
        entityType: EntityType? = null,
    ) {
        if (!monsters.config.triggers.enabled) return
        if (!activeTypes.contains(type)) return

        val worldSettings = monsters.worlds.of(player.world)
        if (!worldSettings.enabled || !worldSettings.triggersEnabled) return

        val now = System.currentTimeMillis()

        for (trigger in triggers.values) {
            if (!trigger.enabled || trigger.type != type) continue
            if (!trigger.matchesWorld(player.world.name)) continue
            if (material != null && !trigger.matchesMaterial(material)) continue
            if (entityType != null && !trigger.matchesEntity(entityType)) continue
            if (trigger.permission.isNotBlank() && !player.hasPermission(trigger.permission)) continue

            if (counters.onCooldown(player.uniqueId, trigger.id, now)) continue
            if (counters.atDailyLimit(player.uniqueId, trigger, now)) continue

            // The count gate comes before the chance gate: felling nine logs should cost nothing
            // beyond a counter bump, and only the tenth should roll dice.
            if (trigger.count > 1 || trigger.type.countable) {
                if (!counters.increment(player.uniqueId, trigger, now)) continue
            }
            if (trigger.chance < 100.0 && rng.nextDouble() * 100.0 >= trigger.chance) continue

            fire(trigger, player, eventLocation, now)
        }
    }

    private fun fire(trigger: Trigger, player: Player, eventLocation: Location, now: Long) {
        val definition = monsters.mobs.get(trigger.mobId) ?: run {
            monsters.logger.warning(
                "등장 조건 '" + trigger.id + "' 에 없는 몬스터가 지정되어 있습니다: " + trigger.mobId
            )
            return
        }

        val anchor = when (trigger.anchor) {
            SpawnAnchor.PLAYER -> player.location
            SpawnAnchor.EVENT -> eventLocation
            SpawnAnchor.RANDOM_NEARBY -> monsters.spawns.scatter(player.location, trigger.spawnRadius)
        }
        val where = if (trigger.spawnRadius > 0.0 && trigger.anchor != SpawnAnchor.RANDOM_NEARBY) {
            monsters.spawns.scatter(anchor, trigger.spawnRadius)
        } else {
            anchor
        }

        if (!trigger.rules.matches(where, monsters.regions)) return

        var spawned = 0
        repeat(trigger.amount) {
            val mob = monsters.spawns.spawn(
                definition, where,
                SpawnOptions(tag = TRIGGER_TAG_PREFIX + trigger.id, ignoreRules = true),
            )
            if (mob != null) spawned++
        }
        if (spawned == 0) return

        counters.markFired(player.uniqueId, trigger, now)
        announce(trigger, player, where)
    }

    private fun announce(trigger: Trigger, player: Player, where: Location) {
        if (trigger.message.isBlank()) return
        val definition = monsters.mobs.get(trigger.mobId)
        val ph = Ph.of()
            .player(player)
            .mob(Text.plain(Text.render(definition?.displayName ?: trigger.mobId)))
            .location(where)

        val rendered = Text.render(trigger.message, ph, player)
        when (trigger.messageStyle) {
            MessageStyle.CHAT -> player.sendMessage(rendered)
            MessageStyle.ACTIONBAR -> player.sendActionBar(rendered)
            MessageStyle.TITLE -> player.showTitle(
                net.kyori.adventure.title.Title.title(
                    rendered, net.kyori.adventure.text.Component.empty(),
                ),
            )

            MessageStyle.BROADCAST -> monsters.broadcast(trigger.message, ph)
        }
    }

    /** Ticker hook for the time-based trigger types. */
    fun tick(now: Long) {
        if (!monsters.config.triggers.enabled) return
        if (triggers.isEmpty()) return

        if (activeTypes.contains(TriggerType.INTERVAL)) {
            for (trigger in triggers.values) {
                if (!trigger.enabled || trigger.type != TriggerType.INTERVAL) continue
                for (player in org.bukkit.Bukkit.getOnlinePlayers()) {
                    if (!trigger.matchesWorld(player.world.name)) continue
                    if (counters.onCooldown(player.uniqueId, trigger.id, now)) continue
                    if (counters.atDailyLimit(player.uniqueId, trigger, now)) continue
                    if (rng.nextDouble() * 100.0 >= trigger.chance) continue
                    fire(trigger, player, player.location, now)
                }
            }
        }

        if (activeTypes.contains(TriggerType.TIME_REACH)) {
            for (trigger in triggers.values) {
                if (!trigger.enabled || trigger.type != TriggerType.TIME_REACH) continue
                for (world in org.bukkit.Bukkit.getWorlds()) {
                    if (!trigger.matchesWorld(world.name)) continue
                    // A one-second tick means the exact tick value is easy to miss, so a small
                    // window is accepted instead of an equality test.
                    if (kotlin.math.abs(world.time - trigger.atTime) > 30L) continue
                    for (player in world.players) {
                        if (counters.onCooldown(player.uniqueId, trigger.id, now)) continue
                        if (counters.atDailyLimit(player.uniqueId, trigger, now)) continue
                        if (rng.nextDouble() * 100.0 >= trigger.chance) continue
                        fire(trigger, player, player.location, now)
                    }
                }
            }
        }
    }

    // --- persistence -----------------------------------------------------------

    fun load(then: () -> Unit) {
        counters = TriggerCounters(monsters.config.triggers.counterExpiryDays)
        monsters.io.async({
            val dir = folder
            dir.mkdirs()
            val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".yml") } ?: emptyArray()
            val loaded = files.mapNotNull { file ->
                val id = file.nameWithoutExtension
                if (id == PROGRESS_NAME) return@mapNotNull null
                try {
                    id to Trigger.load(id, monsters.io.load(file))
                } catch (t: Throwable) {
                    monsters.logger.severe("등장 조건을 읽지 못했습니다 (" + file.name + "): " + t.message)
                    null
                }
            }
            val progressFile = File(dir, "$PROGRESS_NAME.yml")
            val progress = if (progressFile.exists()) monsters.io.load(progressFile) else null
            loaded to progress
        }) { (loaded, progress) ->
            triggers.clear()
            dirty.clear()
            loaded.forEach { (id, trigger) -> triggers[id] = trigger }
            progress?.let { counters.loadFrom(it) }
            rebuildIndexes()
            if (loaded.isNotEmpty()) monsters.logger.info("등장 조건 " + loaded.size + "개를 불러왔습니다")
            then()
        }
    }

    fun flushDirty() {
        if (dirty.isNotEmpty()) {
            val pending = dirty.toList()
            dirty.removeAll(pending.toSet())
            val snapshots = pending.mapNotNull { id ->
                val trigger = triggers[id] ?: return@mapNotNull null
                val config = YamlConfiguration()
                trigger.save(config)
                id to config.saveToString()
            }
            if (snapshots.isNotEmpty()) {
                monsters.io.asyncRun {
                    folder.mkdirs()
                    for ((id, text) in snapshots) {
                        try {
                            File(folder, "$id.yml").writeText(HEADER + text, Charsets.UTF_8)
                        } catch (t: Throwable) {
                            monsters.logger.severe("등장 조건 저장 실패 (" + id + "): " + t.message)
                        }
                    }
                }
            }
        }
        if (counters.isDirty()) {
            val text = counters.serialise()
            monsters.io.asyncRun { writeProgress(text) }
        }
    }

    fun flushBlocking() {
        if (dirty.isNotEmpty()) {
            val pending = dirty.toList()
            dirty.clear()
            folder.mkdirs()
            for (id in pending) {
                val trigger = triggers[id] ?: continue
                val config = YamlConfiguration()
                try {
                    trigger.save(config)
                    File(folder, "$id.yml").writeText(HEADER + config.saveToString(), Charsets.UTF_8)
                } catch (t: Throwable) {
                    monsters.logger.severe("등장 조건 저장 실패 (" + id + "): " + t.message)
                }
            }
        }
        if (counters.isDirty()) writeProgress(counters.serialise())
    }

    private fun writeProgress(text: String) {
        try {
            folder.mkdirs()
            File(folder, "$PROGRESS_NAME.yml").writeText(PROGRESS_HEADER + text, Charsets.UTF_8)
        } catch (t: Throwable) {
            monsters.logger.severe("등장 조건 진행도 저장 실패: " + t.message)
        }
    }

    companion object {
        private val ID_PATTERN = Regex("[A-Za-z0-9가-힣_-]{1,32}")

        const val TRIGGER_TAG_PREFIX = "trigger:"

        private const val PROGRESS_NAME = "trigger-progress"

        private const val HEADER =
            "# 등장 조건 - /몹 GUI 에서 편집할 수 있습니다.\n" +
                "# count 는 누적 횟수, chance 는 그때 굴리는 확률입니다.\n\n"

        private const val PROGRESS_HEADER =
            "# 플레이어별 등장 조건 진행도 - 플러그인이 자동으로 관리합니다.\n" +
                "# 오래 접속하지 않은 기록은 자동으로 삭제됩니다.\n\n"

        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)
    }
}
