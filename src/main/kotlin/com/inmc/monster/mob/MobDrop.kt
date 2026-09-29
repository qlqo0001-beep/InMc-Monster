package com.inmc.monster.mob

import kr.inmc.core.item.StoredItem
import kr.inmc.core.util.Numbers
import org.bukkit.configuration.ConfigurationSection
import java.util.UUID

/**
 * One entry in a mob's drop table.
 *
 * Mirrors the reward entry from the random-box plugin on purpose: the admin gesture is the
 * same one - drop an item into an empty GUI slot, confirm - so the stored shape is the same
 * too, and [kr.inmc.core.item.ItemResolver] rebuilds it from its live definition every
 * time it drops rather than handing out a frozen copy.
 */
class MobDrop(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    var item: StoredItem,
    chance: Double = 25.0,
    minAmount: Int = 1,
    maxAmount: Int = 1,
    /** Broadcast when this entry is rolled. */
    var announce: Boolean = false,
    var commands: MutableList<String> = mutableListOf(),
    var giveItem: Boolean = true,
    /** Name of the tier this drop rolls in; null uses the mob-level range. */
    var tier: String? = null,
    /** Only drops when the killer holds a matching tool. Empty = any. */
    var requiredTools: MutableList<String> = mutableListOf(),
    /** Only drops at or above this mob level. 0 = no requirement. */
    var minLevel: Int = 0,
) : Weighted {

    override var chance: Double = Numbers.clampChance(chance)
        set(value) {
            field = Numbers.clampChance(value)
        }

    var minAmount: Int = minAmount.coerceAtLeast(1)
        set(value) {
            field = value.coerceIn(1, 64)
            if (field > maxAmount) maxAmount = field
        }

    var maxAmount: Int = maxAmount.coerceAtLeast(minAmount)
        set(value) {
            field = value.coerceIn(1, 64)
            if (field < minAmount) minAmount = field
        }

    fun label(): String = item.label()

    fun copyOf(): MobDrop = MobDrop(
        id = id,
        item = item,
        chance = chance,
        minAmount = minAmount,
        maxAmount = maxAmount,
        announce = announce,
        commands = commands.toMutableList(),
        giveItem = giveItem,
        tier = tier,
        requiredTools = requiredTools.toMutableList(),
        minLevel = minLevel,
    )

    fun save(section: ConfigurationSection) {
        section.set("id", id)
        item.save(section)
        section.set("chance", chance)
        section.set("min-amount", minAmount)
        section.set("max-amount", maxAmount)
        if (announce) section.set("announce", true)
        if (!giveItem) section.set("give-item", false)
        if (commands.isNotEmpty()) section.set("commands", commands)
        if (requiredTools.isNotEmpty()) section.set("required-tools", requiredTools)
        if (minLevel > 0) section.set("min-level", minLevel)
        section.set("tier", tier)
    }

    companion object {
        fun load(section: ConfigurationSection, defaultChance: Double): MobDrop? {
            val item = StoredItem.load(section) ?: return null
            return MobDrop(
                id = section.getString("id") ?: UUID.randomUUID().toString().substring(0, 8),
                item = item,
                chance = section.getDouble("chance", defaultChance),
                minAmount = section.getInt("min-amount", 1),
                maxAmount = section.getInt("max-amount", section.getInt("min-amount", 1)),
                announce = section.getBoolean("announce", false),
                commands = section.getStringList("commands").toMutableList(),
                giveItem = section.getBoolean("give-item", true),
                tier = section.getString("tier")?.takeIf { it.isNotBlank() },
                requiredTools = section.getStringList("required-tools").toMutableList(),
                minLevel = section.getInt("min-level", 0).coerceAtLeast(0),
            )
        }
    }
}

/** How loot is handed out when several players fought the same mob. */
enum class DropDistribution(val label: String, val description: String) {
    /** Everything goes to whoever landed the killing blow. */
    KILLER("막타", "마지막 공격을 한 플레이어가 전부 가져갑니다."),

    /** Everything goes to whoever dealt the most damage. */
    TOP_DAMAGE("최대 기여자", "가장 많은 피해를 준 플레이어가 전부 가져갑니다."),

    /** Each participant rolls the table independently - instanced loot, no competition. */
    INSTANCED("개인 지급", "피해를 준 모든 플레이어가 각자 따로 굴립니다."),

    /** One roll, items split round-robin among participants by contribution order. */
    SHARED("기여도 분배", "한 번 굴린 결과를 기여도 순서대로 나눠 가집니다."),

    /** Dropped on the ground for anyone to pick up, vanilla style. */
    GROUND("바닥 드랍", "바닥에 떨어뜨립니다. 누구나 주울 수 있습니다.");

    companion object {
        fun parse(raw: String?): DropDistribution =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: KILLER
    }
}

/** A named sub-pool with its own roll count, so "1 rare + 0~3 commons" is expressible. */
class DropTier(
    var minRolls: Int = 0,
    var maxRolls: Int = 1,
) {
    fun copyOf(): DropTier = DropTier(minRolls, maxRolls)
}

/**
 * A mob's complete drop configuration.
 *
 * The table itself is a flat list; tiers partition it. Rolling walks the default pool and each
 * tier separately so a tier's roll count is independent of how many commons happened to win.
 */
class DropTable {

    val entries: MutableList<MobDrop> = mutableListOf()
    val tiers: LinkedHashMap<String, DropTier> = LinkedHashMap()

    var minRolls: Int = 0
    var maxRolls: Int = 3
    var distribution: DropDistribution = DropDistribution.KILLER

    /**
     * When true, only a player kill produces drops.
     *
     * Left on by default because the alternative is a farm: mobs pushed into lava or killed by
     * other mobs would print loot with nobody playing.
     */
    var requirePlayerKill: Boolean = true

    /** Seconds during which only the owner may pick the drops up. 0 disables the reservation. */
    var protectSeconds: Int = 8

    /** Multiplier applied to every chance in this table. Affixes and worlds stack onto it. */
    var chanceMultiplier: Double = 1.0

    var expMin: Int = 0
    var expMax: Int = 0

    /** Vanilla loot in addition to ours. Off by default so a custom mob drops only what it says. */
    var keepVanillaDrops: Boolean = false

    fun tierOf(name: String?): DropTier? = name?.let { tiers[it] }

    fun copyOf(): DropTable = DropTable().also { copy ->
        entries.forEach { copy.entries.add(it.copyOf()) }
        tiers.forEach { (name, tier) -> copy.tiers[name] = tier.copyOf() }
        copy.minRolls = minRolls
        copy.maxRolls = maxRolls
        copy.distribution = distribution
        copy.requirePlayerKill = requirePlayerKill
        copy.protectSeconds = protectSeconds
        copy.chanceMultiplier = chanceMultiplier
        copy.expMin = expMin
        copy.expMax = expMax
        copy.keepVanillaDrops = keepVanillaDrops
    }

    /** Child values replace parent values; child entries are appended to the parent's. */
    fun inheritFrom(parent: DropTable) {
        val merged = parent.entries.map { it.copyOf() } + entries
        entries.clear()
        entries.addAll(merged)
        parent.tiers.forEach { (name, tier) -> tiers.putIfAbsent(name, tier.copyOf()) }
    }

    fun save(section: ConfigurationSection, path: String) {
        val target = section.createSection(path)
        target.set("min-rolls", minRolls)
        target.set("max-rolls", maxRolls)
        target.set("distribution", distribution.name)
        target.set("require-player-kill", requirePlayerKill)
        target.set("protect-seconds", protectSeconds)
        target.set("chance-multiplier", chanceMultiplier)
        target.set("keep-vanilla-drops", keepVanillaDrops)
        target.set("exp.min", expMin)
        target.set("exp.max", expMax)

        if (tiers.isNotEmpty()) {
            val tierSection = target.createSection("tiers")
            tiers.forEach { (name, tier) ->
                val one = tierSection.createSection(name)
                one.set("min-rolls", tier.minRolls)
                one.set("max-rolls", tier.maxRolls)
            }
        }
        if (entries.isNotEmpty()) {
            val list = target.createSection("entries")
            entries.forEachIndexed { index, drop -> drop.save(list.createSection(index.toString())) }
        }
    }

    companion object {

        fun load(section: ConfigurationSection?, path: String, defaultChance: Double): DropTable {
            val table = DropTable()
            val target = section?.getConfigurationSection(path) ?: return table

            table.minRolls = target.getInt("min-rolls", 0).coerceAtLeast(0)
            table.maxRolls = target.getInt("max-rolls", 3).coerceAtLeast(1)
            table.distribution = DropDistribution.parse(target.getString("distribution"))
            table.requirePlayerKill = target.getBoolean("require-player-kill", true)
            table.protectSeconds = target.getInt("protect-seconds", 8).coerceIn(0, 300)
            table.chanceMultiplier = target.getDouble("chance-multiplier", 1.0).coerceIn(0.0, 100.0)
            table.keepVanillaDrops = target.getBoolean("keep-vanilla-drops", false)
            table.expMin = target.getInt("exp.min", 0).coerceAtLeast(0)
            table.expMax = target.getInt("exp.max", table.expMin).coerceAtLeast(table.expMin)

            target.getConfigurationSection("tiers")?.let { tierSection ->
                for (name in tierSection.getKeys(false)) {
                    val one = tierSection.getConfigurationSection(name) ?: continue
                    table.tiers[name] = DropTier(
                        minRolls = one.getInt("min-rolls", 0).coerceAtLeast(0),
                        maxRolls = one.getInt("max-rolls", 1).coerceAtLeast(1),
                    )
                }
            }
            target.getConfigurationSection("entries")?.let { list ->
                for (key in list.getKeys(false)) {
                    val one = list.getConfigurationSection(key) ?: continue
                    MobDrop.load(one, defaultChance)?.let { table.entries.add(it) }
                }
            }
            return table
        }
    }
}
