package com.inmc.monster.affix

import com.inmc.monster.skill.SkillInstance
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.EntityType

enum class AffixType(val label: String) {
    PREFIX("접두사"),
    SUFFIX("접미사");

    companion object {
        fun parse(raw: String?): AffixType =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: PREFIX
    }
}

/** Which kind of mob an affix is allowed to land on. */
enum class AffixTarget { CUSTOM, VANILLA }

/**
 * One stat change: multiply first, then add.
 *
 * Order matters and is fixed deliberately. Applying the flat bonus first would let a `+5 damage`
 * affix be silently amplified by an unrelated `x1.2` affix rolled alongside it, so two affixes
 * that each look mild would combine into something neither describes.
 */
class StatModifier(var mult: Double = 1.0, var add: Double = 0.0) {

    fun apply(base: Double): Double = base * mult + add

    fun isNoop(): Boolean = mult == 1.0 && add == 0.0

    fun copyOf(): StatModifier = StatModifier(mult, add)

    /** Human-readable form for GUI lore: "+5", "x1.20", or "x1.20 +5". */
    fun describe(): String {
        val parts = mutableListOf<String>()
        if (mult != 1.0) parts.add("x" + String.format("%.2f", mult))
        if (add != 0.0) parts.add((if (add > 0) "+" else "") + kr.inmc.core.util.Numbers.chance(add))
        return if (parts.isEmpty()) "-" else parts.joinToString(" ")
    }
}

/**
 * A named modifier that can be rolled onto a mob - "강력한", "욕심많은", "of the Void".
 *
 * Affixes exist to make ordinary field mobs occasionally interesting without needing a separate
 * mob definition for every variation. That is why they can land on plain vanilla mobs too: the
 * moment a zombie you have killed a thousand times spawns as a 강력한 좀비, the field stops
 * being uniform, and no new mob file was written to make that happen.
 */
class Affix(val id: String) {

    var display: String = id
    var type: AffixType = AffixType.PREFIX
    var enabled: Boolean = true

    /** Relative weight among affixes of the same type that pass the filters. */
    var weight: Int = 10

    var applyTo: MutableSet<AffixTarget> = linkedSetOf(AffixTarget.CUSTOM, AffixTarget.VANILLA)

    /** Empty = any entity type. */
    var entityTypes: MutableSet<EntityType> = linkedSetOf()

    /** Empty = any world. */
    var worlds: MutableSet<String> = linkedSetOf()

    /** Stat key -> how it changes. Covers both vanilla attributes and custom stats. */
    var modifiers: LinkedHashMap<String, StatModifier> = LinkedHashMap()

    /** Skills the affix grants on top of whatever the mob already has. */
    val skills: MutableList<SkillInstance> = mutableListOf()

    /**
     * Extra loot, rolled *in addition to* whatever the mob itself drops.
     *
     * This is what makes an affixed ordinary mob worth killing. A 강력한 좀비 that drops the same
     * rotten flesh as every other zombie is only a harder zombie; giving the affix its own table
     * means the rare spawn pays for the trouble, and it works on plain vanilla mobs that have no
     * definition of their own to hang a drop table off.
     */
    var drops: com.inmc.monster.mob.DropTable = com.inmc.monster.mob.DropTable()

    var dropMultiplier: Double = 1.0
    var expMultiplier: Double = 1.0

    /** Outline colour when the mob is set to glow. Blank = no glow from this affix. */
    var glowColor: String = ""

    /** Minimum mob level before this affix can roll. */
    var minLevel: Int = 0

    fun accepts(target: AffixTarget, entityType: EntityType, world: String): Boolean {
        if (!enabled) return false
        if (!applyTo.contains(target)) return false
        if (entityTypes.isNotEmpty() && !entityTypes.contains(entityType)) return false
        if (worlds.isNotEmpty() && !worlds.any { it.equals(world, ignoreCase = true) }) return false
        return true
    }

    fun copyOf(): Affix = Affix(id).also { copy ->
        copy.display = display
        copy.type = type
        copy.enabled = enabled
        copy.weight = weight
        copy.applyTo = LinkedHashSet(applyTo)
        copy.entityTypes = LinkedHashSet(entityTypes)
        copy.worlds = LinkedHashSet(worlds)
        copy.modifiers = LinkedHashMap(modifiers.mapValues { it.value.copyOf() })
        skills.forEach { copy.skills.add(it.copyOf()) }
        copy.dropMultiplier = dropMultiplier
        copy.expMultiplier = expMultiplier
        copy.glowColor = glowColor
        copy.minLevel = minLevel
        copy.drops = drops.copyOf()
    }

    fun save(section: ConfigurationSection) {
        section.set("display", display)
        section.set("type", type.name)
        section.set("enabled", enabled)
        section.set("weight", weight)
        section.set("apply-to", applyTo.map { it.name })
        section.set("entity-types", entityTypes.map { it.name })
        section.set("worlds", worlds.toList())
        section.set("drop-multiplier", dropMultiplier)
        section.set("exp-multiplier", expMultiplier)
        section.set("min-level", minLevel)
        section.set("glow-color", glowColor.takeIf { it.isNotBlank() })

        section.set("modifiers", null)
        if (modifiers.isNotEmpty()) {
            val target = section.createSection("modifiers")
            modifiers.forEach { (key, modifier) ->
                val one = target.createSection(key)
                if (modifier.mult != 1.0) one.set("mult", modifier.mult)
                if (modifier.add != 0.0) one.set("add", modifier.add)
            }
        }
        section.set("skills", null)
        if (skills.isNotEmpty()) {
            val target = section.createSection("skills")
            skills.forEachIndexed { index, skill -> skill.save(target.createSection(index.toString())) }
        }
        drops.save(section, "drops")
    }

    companion object {

        fun load(id: String, section: ConfigurationSection): Affix {
            val affix = Affix(id)
            affix.display = section.getString("display")?.takeIf { it.isNotBlank() } ?: id
            affix.type = AffixType.parse(section.getString("type"))
            affix.enabled = section.getBoolean("enabled", true)
            affix.weight = section.getInt("weight", 10).coerceIn(1, 10_000)

            val targets = linkedSetOf<AffixTarget>()
            for (name in section.getStringList("apply-to")) {
                runCatching { AffixTarget.valueOf(name.trim().uppercase()) }.getOrNull()?.let { targets.add(it) }
            }
            affix.applyTo = targets.ifEmpty { linkedSetOf(AffixTarget.CUSTOM, AffixTarget.VANILLA) }

            val types = linkedSetOf<EntityType>()
            for (name in section.getStringList("entity-types")) {
                runCatching { EntityType.valueOf(name.trim().uppercase()) }.getOrNull()?.let { types.add(it) }
            }
            affix.entityTypes = types
            affix.worlds = section.getStringList("worlds").toCollection(linkedSetOf())
            affix.dropMultiplier = section.getDouble("drop-multiplier", 1.0).coerceIn(0.0, 100.0)
            affix.expMultiplier = section.getDouble("exp-multiplier", 1.0).coerceIn(0.0, 100.0)
            affix.minLevel = section.getInt("min-level", 0).coerceAtLeast(0)
            affix.glowColor = section.getString("glow-color") ?: ""

            section.getConfigurationSection("modifiers")?.let { target ->
                for (key in target.getKeys(false)) {
                    val one = target.getConfigurationSection(key) ?: continue
                    val modifier = StatModifier(
                        mult = one.getDouble("mult", 1.0),
                        add = one.getDouble("add", 0.0),
                    )
                    if (!modifier.isNoop()) affix.modifiers[key.uppercase()] = modifier
                }
            }
            section.getConfigurationSection("skills")?.let { target ->
                for (key in target.getKeys(false).sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) {
                    target.getConfigurationSection(key)?.let { one ->
                        SkillInstance.load(one)?.let { affix.skills.add(it) }
                    }
                }
            }
            affix.drops = com.inmc.monster.mob.DropTable.load(section, "drops", 25.0)
            return affix
        }
    }
}
