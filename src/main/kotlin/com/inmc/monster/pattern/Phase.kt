package com.inmc.monster.pattern

import com.inmc.monster.death.DeathAction
import com.inmc.monster.mob.StatMap
import org.bukkit.configuration.ConfigurationSection

/**
 * One step of a scripted pattern.
 *
 * A step is "wait this long, then cast this". Sequencing them is what turns a mob with a few
 * skills into a fight with a rhythm players can learn.
 */
class PatternStep(
    /** Ticks to wait *before* this step runs, counted from the previous step. */
    var delayTicks: Int = 20,
    var skillId: String = "",
    val values: MutableMap<String, Any> = LinkedHashMap(),
) {

    fun copyOf(): PatternStep = PatternStep(delayTicks, skillId, LinkedHashMap(values))

    fun save(section: ConfigurationSection) {
        section.set("delay", delayTicks)
        section.set("skill", skillId)
        if (values.isNotEmpty()) {
            val target = section.createSection("values")
            values.forEach { (key, value) -> target.set(key, value) }
        }
    }

    companion object {
        fun load(section: ConfigurationSection): PatternStep? {
            val skillId = section.getString("skill")?.takeIf { it.isNotBlank() } ?: return null
            val values = LinkedHashMap<String, Any>()
            section.getConfigurationSection("values")?.let { target ->
                for (key in target.getKeys(false)) target.get(key)?.let { values[key] = it }
            }
            return PatternStep(
                delayTicks = section.getInt("delay", 20).coerceIn(0, 12_000),
                skillId = skillId,
                values = values,
            )
        }
    }
}

/**
 * A health band with its own behaviour.
 *
 * Phases are chosen by [healthAbove], highest first: the active phase is the first one whose
 * threshold the mob's current health percentage is still at or above. A boss with thresholds
 * 66 / 33 / 0 therefore has three phases and never falls between them, because the last one is
 * always reachable.
 *
 * Phases only ever advance. A boss healed back above a threshold does not rewind to an earlier
 * phase - re-running an entrance broadcast and re-applying its buffs every time a heal lands
 * would make the fight incoherent.
 */
class Phase(
    var name: String,
    var healthAbove: Double = 0.0,
    /** Multipliers folded onto the mob's stats while this phase is active. 1.0 = unchanged. */
    var statMultipliers: StatMap = StatMap(),
    /** ModelEngine blueprint to swap to on entry. Blank = keep the current model. */
    var model: String = "",
    var onEnter: MutableList<DeathAction> = mutableListOf(),
    var steps: MutableList<PatternStep> = mutableListOf(),
    var loop: Boolean = true,
    /** Extra multiplier applied to every skill cast in this phase. */
    var skillPower: Double = 1.0,
) {

    val hasPattern: Boolean get() = steps.isNotEmpty()

    fun copyOf(): Phase = Phase(
        name = name,
        healthAbove = healthAbove,
        statMultipliers = statMultipliers.copyOf(),
        model = model,
        onEnter = onEnter.map { it.copyOf() }.toMutableList(),
        steps = steps.map { it.copyOf() }.toMutableList(),
        loop = loop,
        skillPower = skillPower,
    )

    fun save(section: ConfigurationSection) {
        section.set("name", name)
        section.set("health-above", healthAbove)
        section.set("loop", loop)
        section.set("skill-power", skillPower)
        if (model.isNotBlank()) section.set("model", model)
        statMultipliers.save(section, "stat-multipliers")
        if (onEnter.isNotEmpty()) {
            val target = section.createSection("on-enter")
            onEnter.forEachIndexed { index, action -> action.save(target.createSection(index.toString())) }
        }
        if (steps.isNotEmpty()) {
            val target = section.createSection("steps")
            steps.forEachIndexed { index, step -> step.save(target.createSection(index.toString())) }
        }
    }

    companion object {

        fun load(section: ConfigurationSection): Phase? {
            val name = section.getString("name")?.takeIf { it.isNotBlank() } ?: return null
            val phase = Phase(
                name = name,
                healthAbove = section.getDouble("health-above", 0.0).coerceIn(0.0, 100.0),
                statMultipliers = StatMap.load(section, "stat-multipliers"),
                model = section.getString("model") ?: "",
                loop = section.getBoolean("loop", true),
                skillPower = section.getDouble("skill-power", 1.0).coerceIn(0.0, 100.0),
            )
            section.getConfigurationSection("on-enter")?.let { target ->
                for (key in target.getKeys(false)) {
                    target.getConfigurationSection(key)?.let { one ->
                        DeathAction.load(one)?.let { phase.onEnter.add(it) }
                    }
                }
            }
            section.getConfigurationSection("steps")?.let { target ->
                for (key in target.getKeys(false).sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) {
                    target.getConfigurationSection(key)?.let { one ->
                        PatternStep.load(one)?.let { phase.steps.add(it) }
                    }
                }
            }
            return phase
        }

        /**
         * Picks the phase for a health percentage.
         *
         * [phases] does not have to be sorted; this sorts a copy, because an admin editing
         * thresholds in the GUI will not keep the list in order and should not have to.
         */
        fun select(phases: List<Phase>, healthPercent: Double): Phase? {
            if (phases.isEmpty()) return null
            return phases.sortedByDescending { it.healthAbove }
                .firstOrNull { healthPercent >= it.healthAbove }
                ?: phases.minByOrNull { it.healthAbove }
        }
    }
}

