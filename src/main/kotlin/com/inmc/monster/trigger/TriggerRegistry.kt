package com.inmc.monster.trigger

import com.inmc.monster.Monsters
import com.inmc.monster.spawn.SpawnOptions
import com.inmc.monster.util.Ph
import kr.inmc.core.CorePlugin
import kr.inmc.core.store.YamlFolder
import kr.inmc.core.util.Text
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
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

    /** 폴더 저장 경로. dirty 집합과 파일 쓰기를 여기에 맡긴다. */
    private val files = YamlFolder(monsters.io, monsters.logger, "triggers", HEADER, "등장 조건")

    private val rng = Random()

    /** Union of every material any BLOCK_BREAK / BLOCK_PLACE / item trigger watches. */
    private var watchedMaterials: EnumSet<Material> = EnumSet.noneOf(Material::class.java)

    /** Union of every entity type any MOB_KILL trigger watches. */
    private var watchedEntities: EnumSet<EntityType> = EnumSet.noneOf(EntityType::class.java)

    /** True when at least one trigger of each type exists, so listeners can bail immediately. */
    private var activeTypes: MutableSet<TriggerType> = EnumSet.noneOf(TriggerType::class.java)

    /** 리로드로 재생성하지 않는다 — 만료 일수만 갱신한다. */
    val counters: TriggerCounters = TriggerCounters(14)

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
        rebuildIndexes()
        files.deleteFile(trigger.id)
        return true
    }

    fun markDirty(trigger: Trigger) {
        if (triggers[trigger.id] !== trigger) return
        files.markDirty(trigger.id)
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

    /**
     * 등장 조건 정의를 다시 읽는다. **진행도는 건드리지 않는다.**
     *
     * 예전에는 여기서 `counters` 를 새로 만들었다. 이 메서드는 리로드 경로 네 곳에서
     * 불리므로(`/몹 리로드`, 메인 화면, 전역 설정 화면, 부팅), 그때마다 마지막 플러시 이후
     * 쌓인 진행도가 조용히 사라졌다. 이제 객체는 유지하고 만료 일수만 갱신한다.
     */
    fun load(then: () -> Unit) {
        counters.expiryDays = monsters.config.triggers.counterExpiryDays
        monsters.io.async({
            files.readAll(skip = { it == PROGRESS_NAME }) { id, config ->
                // 파일명이 곧 트리거 id 이고, 그 id 는 공유 저장소의 대상 이름이 된다.
                // 점이 들어 있으면 저장 시점에 예외가 나는데 그 경로가 블록 파괴 리스너라
                // 서버가 죽는다. 쓰기가 아니라 여기서 막는다.
                if (!isValidId(id)) {
                    monsters.logger.severe(
                        "등장 조건 파일 이름에 쓸 수 없는 문자가 있어 건너뜁니다 (" + id +
                            "). 영문·숫자·한글·밑줄·하이픈 32자까지만 됩니다.",
                    )
                    null
                } else {
                    Trigger.load(id, config)
                }
            }
        }) { loaded ->
            triggers.clear()
            files.clearDirty()
            loaded.forEach { (id, trigger) -> triggers[id] = trigger }
            rebuildIndexes()
            if (loaded.isNotEmpty()) monsters.logger.info("등장 조건 " + loaded.size + "개를 불러왔습니다")
            then()
        }
    }

    /**
     * 진행도를 공유 저장소에서 읽는다. 부팅 때 한 번만 — [load] 와 달리 리로드에서 부르지 않는다.
     *
     * core 의 저장소가 준비된 뒤에 불려야 한다 (`PlayerStore.whenReady`).
     */
    fun loadProgress() {
        counters.loadFrom(CorePlugin.get().players)
    }

    fun flushDirty() {
        files.flushDirty(::render)
        // 진행도의 유일한 저장처는 공유 저장소다. 예전에는 여기서 triggers/trigger-progress.yml
        // 에도 같이 썼는데, 그건 core 로 옮기는 동안의 되돌림 대비였고 이제 필요 없다.
        if (counters.hasPending()) counters.syncTo(CorePlugin.get().players)
    }

    /** 메인 스레드에서 돈다. */
    private fun render(id: String): YamlConfiguration? {
        val trigger = triggers[id] ?: return null
        val config = YamlConfiguration()
        return try {
            trigger.save(config)
            config
        } catch (t: Throwable) {
            monsters.logger.severe("등장 조건 직렬화 실패 (" + id + "): " + t.message)
            null
        }
    }

    fun flushBlocking() {
        files.flushDirtyBlocking(::render)
        if (counters.hasPending()) counters.syncTo(CorePlugin.get().players)
    }

    companion object {
        private val ID_PATTERN = Regex("[A-Za-z0-9가-힣_-]{1,32}")

        const val TRIGGER_TAG_PREFIX = "trigger:"

        /**
         * 더 이상 쓰지 않는 파일이지만 이름은 남긴다.
         *
         * 진행도는 공유 저장소가 갖는다. 다만 임포트 뒤 이름 변경이 실패했거나 아직 한 번도
         * 부팅하지 않은 서버에는 `trigger-progress.yml` 이 남아 있고, 그걸 등장 조건 정의로
         * 읽어버리면 안 되므로 [load] 가 이 이름으로 걸러낸다.
         */
        private const val PROGRESS_NAME = "trigger-progress"

        private const val HEADER =
            "# 등장 조건 - /몹 GUI 에서 편집할 수 있습니다.\n" +
                "# count 는 누적 횟수, chance 는 그때 굴리는 확률입니다.\n\n"

        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)
    }
}
