package com.inmc.monster.api

import com.inmc.monster.runtime.ActiveMob
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Base for every event this plugin fires.
 *
 * All of them carry the [ActiveMob] rather than a bare entity, because the interesting
 * information - definition, level, affixes, phase, threat table - only exists there.
 */
abstract class CustomMobEvent(val mob: ActiveMob) : Event() {

    val entity: LivingEntity get() = mob.entity

    /** The mob's definition id, or an empty string for a vanilla mob carrying only affixes. */
    val mobId: String get() = mob.definition?.id ?: ""
}

/**
 * Fired after a custom mob has been fully built and registered.
 *
 * Cancelling removes the mob again. Deliberately fired *after* construction rather than before:
 * a dungeon plugin's decision usually depends on what the mob turned out to be - its level, its
 * rolled affixes - and none of that exists yet at the point a pre-spawn event could fire.
 */
class CustomMobSpawnEvent(mob: ActiveMob) : CustomMobEvent(mob), Cancellable {

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * Fired when a custom mob dies, after drops have been handed out.
 *
 * [contributors] exposes the threat table so a dungeon can credit everyone who fought, not just
 * whoever landed the last hit.
 */
class CustomMobDeathEvent(mob: ActiveMob, val killer: Player?) : CustomMobEvent(mob) {

    val contributors: List<Pair<java.util.UUID, Double>> = mob.threat.ranking()

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

/**
 * Fired before a custom mob's outgoing or incoming damage is applied.
 *
 * Cancelling stops the hit. [damage] is writable so an encounter can scale a boss up or down
 * without editing its definition.
 */
class CustomMobDamageEvent(
    mob: ActiveMob,
    val other: LivingEntity?,
    /** True when the custom mob is dealing the damage, false when it is receiving it. */
    val outgoing: Boolean,
    var damage: Double,
) : CustomMobEvent(mob), Cancellable {

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** Fired when a mob crosses into a new phase. */
class CustomMobPhaseChangeEvent(
    mob: ActiveMob,
    val phaseName: String,
    val previousPhase: String?,
) : CustomMobEvent(mob) {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** Fired after a skill resolves, with whatever the selector picked. */
class CustomMobSkillEvent(
    mob: ActiveMob,
    val skillId: String,
    val targets: List<LivingEntity>,
) : CustomMobEvent(mob) {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
