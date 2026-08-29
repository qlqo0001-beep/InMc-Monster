package com.inmc.monster.combat

import com.inmc.monster.Monsters
import com.inmc.monster.mob.StatKeys
import com.inmc.monster.runtime.ActiveMob
import org.bukkit.entity.LivingEntity
import java.util.Random

/**
 * Turns a mob's configured stats into damage the server actually applies.
 *
 * There are two roads and they must agree. When MythicLib is present the hit is handed to its
 * damage pipeline, which is the only way MMOItems rules apply: elemental damage from the mob's
 * weapon, critical registration, and - on the receiving end - the *player's* defence, dodge,
 * block and elemental resistance. Take that road away and none of that happens, no matter what
 * the mob is holding.
 *
 * Without MythicLib the arithmetic here reproduces the same shape (crit, armour penetration,
 * damage multipliers) so a server that never installs it still gets consistent, tunable damage.
 * The numbers will not match MythicLib exactly - nothing short of reimplementing it would - but
 * the stats an admin configures all still mean what the GUI says they mean.
 */
class DamageBridge(private val monsters: Monsters) {

    private val rng = Random()

    /** Result of a damage calculation, kept so callers can report crits in effects. */
    class Outcome(val amount: Double, val critical: Boolean)

    /**
     * Final melee damage for [mob], equipment included.
     *
     * The base is the mob's ATTACK_DAMAGE stat plus whatever MythicLib reads off its gear, so a
     * mob given an MMOItems sword genuinely hits harder without the sword's damage being typed
     * into the mob's own stats.
     */
    fun meleeDamage(mob: ActiveMob): Outcome {
        val base = mob.stats.getOrZero("ATTACK_DAMAGE") +
            monsters.statResolver.equipmentBonus(mob.entity, "ATTACK_DAMAGE")
        return roll(mob, base.coerceAtLeast(0.0), StatKeys.PHYSICAL_DAMAGE)
    }

    /** Final damage for a skill hit, scaled by the mob's skill power. */
    fun skillDamage(mob: ActiveMob, amount: Double): Outcome =
        roll(mob, (amount * mob.skillPower()).coerceAtLeast(0.0), StatKeys.MAGICAL_DAMAGE)

    private fun roll(mob: ActiveMob, base: Double, typeStat: String): Outcome {
        if (base <= 0.0) return Outcome(0.0, false)

        var amount = base

        // Type multiplier (physical / magical / projectile), then the blanket PvE bonus.
        amount *= 1.0 + effective(mob, typeStat) / 100.0
        amount *= 1.0 + effective(mob, StatKeys.PVE_DAMAGE) / 100.0

        val critChance = effective(mob, StatKeys.CRITICAL_STRIKE_CHANCE)
        val critical = critChance > 0.0 && rng.nextDouble() * 100.0 < critChance
        if (critical) {
            amount *= 1.0 + effective(mob, StatKeys.CRITICAL_STRIKE_POWER) / 100.0
        }
        return Outcome(amount.coerceAtLeast(0.0), critical)
    }

    private fun effective(mob: ActiveMob, key: String): Double =
        mob.stats.getOrZero(key) + monsters.statResolver.equipmentBonus(mob.entity, key)

    /**
     * Applies [outcome] from [mob] to [target].
     *
     * Prefers MythicLib; falls back to a plain Bukkit damage call with armour penetration
     * approximated by ignoring part of the target's armour value.
     */
    fun apply(mob: ActiveMob, target: LivingEntity, outcome: Outcome, types: List<String>) {
        if (outcome.amount <= 0.0) return
        if (!target.isValid || target.isDead) return

        if (monsters.mythicLib.damage(mob.entity, target, outcome.amount, types, outcome.critical)) {
            afterHit(mob, outcome.amount)
            return
        }

        val penetration = effective(mob, StatKeys.ARMOR_PENETRATION).coerceIn(0.0, 100.0)
        val amount = if (penetration <= 0.0) outcome.amount else applyPenetration(target, outcome.amount, penetration)
        target.damage(amount, mob.entity)
        afterHit(mob, amount)
    }

    fun applySkill(mob: ActiveMob, target: LivingEntity, amount: Double) {
        apply(mob, target, skillDamage(mob, amount), com.inmc.monster.integration.MythicLibHook.SKILL_TYPES)
    }

    /**
     * Approximates armour penetration without MythicLib.
     *
     * Vanilla reduces damage by roughly 4% per armour point. Rather than trying to bypass that
     * after the fact, the incoming damage is scaled up by the share of reduction being ignored,
     * which lands in the right place for the armour values players actually wear.
     */
    private fun applyPenetration(target: LivingEntity, amount: Double, penetrationPercent: Double): Double {
        val armour = target.getAttribute(org.bukkit.attribute.Attribute.ARMOR)?.value ?: return amount
        if (armour <= 0.0) return amount
        val reduction = (armour * 0.04).coerceIn(0.0, 0.8)
        val ignored = reduction * (penetrationPercent / 100.0)
        if (ignored <= 0.0) return amount
        return amount / (1.0 - ignored).coerceAtLeast(0.2)
    }

    /** Lifesteal, applied after the hit lands so it reflects damage actually dealt. */
    private fun afterHit(mob: ActiveMob, amount: Double) {
        val lifesteal = effective(mob, StatKeys.LIFESTEAL)
        if (lifesteal <= 0.0) return
        val healed = amount * lifesteal / 100.0
        if (healed <= 0.0 || !mob.isAlive) return
        val max = mob.entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: return
        mob.entity.health = (mob.entity.health + healed).coerceAtMost(max)
    }

    /**
     * Incoming damage adjustments: dodge, flat reduction and thorns.
     *
     * Returns the damage that should remain, or null when the hit is dodged entirely.
     */
    fun mitigate(mob: ActiveMob, incoming: Double, attacker: LivingEntity?): Double? {
        val dodge = mob.stats.getOrZero(StatKeys.DODGE_RATING)
        if (dodge > 0.0 && rng.nextDouble() * 100.0 < dodge) {
            mob.entity.world.spawnParticle(
                org.bukkit.Particle.CLOUD, mob.entity.location.add(0.0, 1.0, 0.0), 6, 0.3, 0.3, 0.3, 0.01,
            )
            return null
        }

        val thorns = mob.stats.getOrZero(StatKeys.THORNS)
        if (thorns > 0.0 && attacker != null && attacker.isValid) {
            val reflected = incoming * thorns / 100.0
            if (reflected > 0.0) attacker.damage(reflected, mob.entity)
        }

        val reduction = mob.stats.getOrZero(StatKeys.DAMAGE_REDUCTION).coerceIn(0.0, 90.0)
        return incoming * (1.0 - reduction / 100.0)
    }
}
