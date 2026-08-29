package com.inmc.monster.listener

import com.inmc.monster.Monsters
import com.inmc.monster.integration.MythicLibHook
import com.inmc.monster.runtime.ActiveMob
import com.inmc.monster.skill.SkillTrigger
import com.inmc.monster.skill.builtin.ProjectileTracker
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerAttemptPickupItemEvent

/**
 * Everything combat-related that happens to or because of a custom mob.
 *
 * Every handler starts with the same one-line rejection - is this entity one of ours - because
 * these are the busiest events on a server and the honest answer is almost always no.
 */
class CombatListener(private val monsters: Monsters) : Listener {

    /**
     * Promotes a custom mob's melee swing into a MythicLib attack, and applies its stats.
     *
     * This is the step that makes MMOItems equipment on a mob mean anything. A mob holding an
     * MMOItems sword and left alone hits for plain vanilla damage; routed through MythicLib it
     * hits with the weapon's elemental damage and critical rules, and the *player's* defence,
     * dodge and resistances apply on the receiving end.
     *
     * Priority LOWEST so the damage is replaced before other plugins read it.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDamageByEntity(event: EntityDamageByEntityEvent) {
        if (!monsters.ready) return

        val victim = event.entity as? LivingEntity ?: return

        // Re-entry guard. Our own MythicLib call fires a fresh EntityDamageByEntityEvent, and
        // without this the handler would intercept its own attack and recurse until the stack
        // ran out.
        if (monsters.mythicLib.hasRegisteredAttack(event)) return

        val damager = event.damager
        val attacker = when (damager) {
            is LivingEntity -> damager
            is Projectile -> damager.shooter as? LivingEntity
            else -> null
        }

        // --- our mob is attacking -------------------------------------------------
        val attackerMob = attacker?.let { monsters.tracker.of(it) }
        if (attackerMob != null && attackerMob.definition != null) {
            // A projectile our skill launched carries its own damage and was already accounted
            // for when it was fired; re-rolling here would double it.
            if (damager is Projectile && ProjectileTracker.isTracked(damager)) return

            handleOutgoing(event, attackerMob, victim)
            return
        }

        // --- our mob is being hit -------------------------------------------------
        val victimMob = monsters.tracker.of(victim) ?: return
        handleIncoming(event, victimMob, attacker)
    }

    private fun handleOutgoing(
        event: EntityDamageByEntityEvent,
        mob: ActiveMob,
        victim: LivingEntity,
    ) {
        val outcome = monsters.damage.meleeDamage(mob)
        if (outcome.amount <= 0.0) return

        val adjusted = monsters.api.fireDamageEvent(mob, victim, outgoing = true, damage = outcome.amount)
        if (adjusted == null) {
            event.isCancelled = true
            return
        }

        event.isCancelled = true
        monsters.damage.apply(
            mob, victim,
            com.inmc.monster.combat.DamageBridge.Outcome(adjusted, outcome.critical),
            MythicLibHook.MELEE_TYPES,
        )
        monsters.skills.fire(mob, SkillTrigger.ON_ATTACK, victim)
        if (victim is Player && victim.isDead) {
            monsters.skills.fire(mob, SkillTrigger.ON_KILL_PLAYER, victim)
        }
    }

    private fun handleIncoming(
        event: EntityDamageByEntityEvent,
        mob: ActiveMob,
        attacker: LivingEntity?,
    ) {
        val mitigated = monsters.damage.mitigate(mob, event.finalDamage, attacker)
        if (mitigated == null) {
            event.isCancelled = true
            return
        }

        val adjusted = monsters.api.fireDamageEvent(mob, attacker, outgoing = false, damage = mitigated)
        if (adjusted == null) {
            event.isCancelled = true
            return
        }
        if (adjusted != event.finalDamage) event.damage = adjusted

        if (attacker is Player) mob.threat.add(attacker, adjusted)

        monsters.skills.interrupt(mob)
        monsters.skills.fire(mob, SkillTrigger.ON_DAMAGED, attacker)

        // The phase check and the nameplate deliberately do *not* happen here. Bukkit applies
        // damage after the event returns, so reading health now gives the value from before this
        // hit - which made a boss crossing 50% stay in its first phase until the next hit landed,
        // and left the nameplate permanently one hit behind. Both are driven from the tick loop
        // instead, where the health is the real one.
    }

    /** Non-entity damage: fire, fall, drowning. Only immunities care at this point. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        if (!monsters.ready) return
        if (event is EntityDamageByEntityEvent) return

        val mob = monsters.tracker.of(event.entity) ?: return
        val immunities = mob.definition?.immunities ?: return
        if (!immunities.isEmpty && immunities.blocks(event.cause)) {
            event.isCancelled = true
            return
        }
        monsters.skills.fire(mob, SkillTrigger.ON_DAMAGED, null)
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onDeath(event: EntityDeathEvent) {
        if (!monsters.ready) return
        val mob = monsters.tracker.of(event.entity) ?: return
        if (mob.definition == null) {
            // Affix-only vanilla mob: it keeps every one of its ordinary vanilla drops, and the
            // affixes add their own loot on top rather than replacing it.
            monsters.deaths.handleAffixDrops(mob, event, event.entity.killer)
            monsters.cleanupMob(mob)
            return
        }
        monsters.deaths.onDeath(mob, event)
    }

    /** Skill projectiles carry their own damage; this is where it is delivered. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        if (!monsters.ready) return
        val victim = event.hitEntity as? LivingEntity ?: return
        ProjectileTracker.onHit(monsters, event.entity, victim)
    }

    /** Enforces the "players only" targeting flag. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onTarget(event: EntityTargetLivingEntityEvent) {
        if (!monsters.ready) return
        val mob = monsters.tracker.of(event.entity) ?: return
        val definition = mob.definition ?: return

        val target = event.target
        if (definition.flags.targetPlayersOnly && target != null && target !is Player) {
            event.isCancelled = true
            return
        }
        if (target != null) monsters.skills.fire(mob, SkillTrigger.ON_TARGET, target)
    }

    /** Potion immunities. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onPotionEffect(event: EntityPotionEffectEvent) {
        if (!monsters.ready) return
        if (event.action != EntityPotionEffectEvent.Action.ADDED) return
        val mob = monsters.tracker.of(event.entity) ?: return
        val immunities = mob.definition?.immunities ?: return
        if (immunities.potions.isEmpty()) return
        val type = event.newEffect?.type ?: return
        if (immunities.blocksPotion(type)) event.isCancelled = true
    }

    /** Honours the short pickup reservation on a boss's loot. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPickup(event: PlayerAttemptPickupItemEvent) {
        if (!monsters.ready) return
        if (monsters.drops.canPickUp(event.item, event.player)) return
        event.isCancelled = true
    }
}
