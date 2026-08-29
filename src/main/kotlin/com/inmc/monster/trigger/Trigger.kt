package com.inmc.monster.trigger

import com.inmc.monster.spawn.SpawnRules
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.EntityType

/** What a player did that a trigger might care about. */
enum class TriggerType(val label: String, val description: String) {
    BLOCK_BREAK("블록 파괴", "지정한 블록을 캘 때. 벌목·채굴 조건에 씁니다."),
    BLOCK_PLACE("블록 설치", "지정한 블록을 설치할 때."),
    MOB_KILL("몬스터 처치", "지정한 엔티티를 처치할 때."),
    PLAYER_DEATH("플레이어 사망", "플레이어가 죽을 때."),
    DAMAGE_TAKEN("피해 입음", "플레이어가 피해를 입을 때."),
    FISH_CATCH("낚시 성공", "낚시로 무언가를 낚을 때."),
    ITEM_CONSUME("아이템 섭취", "지정한 아이템을 먹거나 마실 때."),
    CRAFT("아이템 제작", "지정한 아이템을 제작할 때."),
    ENCHANT("아이템 인챈트", "인챈트 테이블을 사용할 때."),
    WEATHER_CHANGE("날씨 변화", "비나 천둥이 시작될 때. 접속 중인 플레이어 각각에게 판정합니다."),
    TIME_REACH("시간 도달", "월드 시간이 지정 값에 도달할 때."),
    PLAYER_JOIN("접속", "플레이어가 서버에 접속할 때."),
    INTERVAL("주기적", "접속 중인 플레이어에게 일정 시간마다 판정합니다.");

    /** True when the type counts individual actions rather than firing on a single event. */
    val countable: Boolean
        get() = this in setOf(BLOCK_BREAK, BLOCK_PLACE, MOB_KILL, FISH_CATCH, ITEM_CONSUME, CRAFT, ENCHANT)

    companion object {
        fun parse(raw: String?): TriggerType? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** Where the mob appears relative to what happened. */
enum class SpawnAnchor(val label: String) {
    PLAYER("플레이어 위치"),
    EVENT("사건이 일어난 위치"),
    RANDOM_NEARBY("플레이어 주변 무작위");

    companion object {
        fun parse(raw: String?): SpawnAnchor =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: PLAYER
    }
}

/** How the trigger's message is shown. */
enum class MessageStyle(val label: String) {
    CHAT("채팅"),
    ACTIONBAR("액션바"),
    TITLE("타이틀"),
    BROADCAST("서버 전체");

    companion object {
        fun parse(raw: String?): MessageStyle =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: CHAT
    }
}

/**
 * One "이럴 때 이 몬스터가 나온다" rule.
 *
 * The counting types are the interesting ones: "fell ten logs, then a 10% chance" is two
 * separate gates, and both matter. The count makes the event rare enough to feel earned, and
 * the chance stops it being a metronome that players can time exactly.
 *
 * [cooldownSeconds] and [dailyLimit] are not optional extras. Without them, a trigger on
 * BLOCK_BREAK is farmable: plant a sapling, fell it, repeat, and the mob becomes an infinite
 * resource rather than an event.
 */
class Trigger(val id: String) {

    var enabled: Boolean = true
    var type: TriggerType = TriggerType.BLOCK_BREAK

    /** Materials this trigger reacts to, for the block and item types. Empty = any. */
    var materials: MutableSet<Material> = linkedSetOf()

    /** Entity types this trigger reacts to, for MOB_KILL. Empty = any. */
    var entityTypes: MutableSet<EntityType> = linkedSetOf()

    /** Actions needed before a roll happens. 1 = roll on every action. */
    var count: Int = 1

    /** Percent chance once the count is reached. */
    var chance: Double = 10.0

    var mobId: String = ""
    var amount: Int = 1

    var anchor: SpawnAnchor = SpawnAnchor.PLAYER
    var spawnRadius: Double = 6.0

    var message: String = ""
    var messageStyle: MessageStyle = MessageStyle.CHAT

    /** Per-player cooldown in seconds. */
    var cooldownSeconds: Int = 120

    /** Per-player cap per real-world day. 0 = uncapped. */
    var dailyLimit: Int = 0

    /** Whether reaching the count resets it, or lets it keep rolling on every further action. */
    var resetCounter: Boolean = true

    var worlds: MutableSet<String> = linkedSetOf()
    var permission: String = ""

    /** Extra placement conditions, shared with the spawn system. */
    var rules: SpawnRules = SpawnRules()

    /** Interval in seconds, only for [TriggerType.INTERVAL]. */
    var intervalSeconds: Int = 300

    /** World time to fire at, only for [TriggerType.TIME_REACH]. */
    var atTime: Long = 13000

    fun matchesWorld(world: String): Boolean =
        worlds.isEmpty() || worlds.any { it.equals(world, ignoreCase = true) }

    fun matchesMaterial(material: Material): Boolean =
        materials.isEmpty() || materials.contains(material)

    fun matchesEntity(type: EntityType): Boolean =
        entityTypes.isEmpty() || entityTypes.contains(type)

    fun copyOf(newId: String): Trigger = Trigger(newId).also { copy ->
        copy.enabled = enabled
        copy.type = type
        copy.materials = linkedSetOf<Material>().also { it.addAll(materials) }
        copy.entityTypes = linkedSetOf<EntityType>().also { it.addAll(entityTypes) }
        copy.count = count
        copy.chance = chance
        copy.mobId = mobId
        copy.amount = amount
        copy.anchor = anchor
        copy.spawnRadius = spawnRadius
        copy.message = message
        copy.messageStyle = messageStyle
        copy.cooldownSeconds = cooldownSeconds
        copy.dailyLimit = dailyLimit
        copy.resetCounter = resetCounter
        copy.worlds = LinkedHashSet(worlds)
        copy.permission = permission
        copy.rules = rules.copyOf()
        copy.intervalSeconds = intervalSeconds
        copy.atTime = atTime
    }

    fun save(config: ConfigurationSection) {
        config.set("enabled", enabled)
        config.set("type", type.name)
        config.set("materials", materials.map { it.name })
        config.set("entity-types", entityTypes.map { it.name })
        config.set("count", count)
        config.set("chance", chance)
        config.set("mob", mobId)
        config.set("amount", amount)
        config.set("anchor", anchor.name)
        config.set("spawn-radius", spawnRadius)
        config.set("message", message.takeIf { it.isNotBlank() })
        config.set("message-style", messageStyle.name)
        config.set("cooldown-seconds", cooldownSeconds)
        config.set("daily-limit", dailyLimit)
        config.set("reset-counter", resetCounter)
        config.set("worlds", worlds.toList())
        config.set("permission", permission.takeIf { it.isNotBlank() })
        config.set("interval-seconds", intervalSeconds)
        config.set("at-time", atTime)
        rules.save(config, "rules")
    }

    companion object {

        fun load(id: String, config: ConfigurationSection): Trigger {
            val trigger = Trigger(id)
            trigger.enabled = config.getBoolean("enabled", true)
            trigger.type = TriggerType.parse(config.getString("type")) ?: TriggerType.BLOCK_BREAK

            trigger.materials = linkedSetOf<Material>().also { set ->
                config.getStringList("materials").forEach { name ->
                    Material.matchMaterial(name)?.let { set.add(it) }
                }
            }
            trigger.entityTypes = linkedSetOf<EntityType>().also { set ->
                config.getStringList("entity-types").forEach { name ->
                    runCatching { EntityType.valueOf(name.trim().uppercase()) }.getOrNull()?.let { set.add(it) }
                }
            }

            trigger.count = config.getInt("count", 1).coerceIn(1, 100_000)
            trigger.chance = config.getDouble("chance", 10.0).coerceIn(0.01, 100.0)
            trigger.mobId = config.getString("mob") ?: ""
            trigger.amount = config.getInt("amount", 1).coerceIn(1, 50)
            trigger.anchor = SpawnAnchor.parse(config.getString("anchor"))
            trigger.spawnRadius = config.getDouble("spawn-radius", 6.0).coerceIn(0.0, 64.0)
            trigger.message = config.getString("message") ?: ""
            trigger.messageStyle = MessageStyle.parse(config.getString("message-style"))
            trigger.cooldownSeconds = config.getInt("cooldown-seconds", 120).coerceAtLeast(0)
            trigger.dailyLimit = config.getInt("daily-limit", 0).coerceAtLeast(0)
            trigger.resetCounter = config.getBoolean("reset-counter", true)
            trigger.worlds = config.getStringList("worlds").toCollection(linkedSetOf())
            trigger.permission = config.getString("permission") ?: ""
            trigger.intervalSeconds = config.getInt("interval-seconds", 300).coerceIn(5, 86_400)
            trigger.atTime = config.getLong("at-time", 13000L).coerceIn(0L, 24000L)
            trigger.rules = SpawnRules.load(config, "rules")
            return trigger
        }
    }
}
