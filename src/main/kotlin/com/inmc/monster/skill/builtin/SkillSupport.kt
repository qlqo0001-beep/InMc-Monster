package com.inmc.monster.skill.builtin

import com.inmc.monster.skill.SkillContext
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector

/**
 * Helpers shared by the built-in skills.
 *
 * Everything here is deliberately forgiving about names: an admin typing a particle or sound
 * into a GUI text field will get it wrong sometimes, and a skill that throws on a typo takes
 * the whole cast - and often the mob's whole pattern - down with it. A wrong name falls back to
 * something visible instead.
 */
object Fx {

    fun particle(name: String, fallback: Particle = Particle.CRIT): Particle =
        runCatching { Particle.valueOf(name.trim().uppercase()) }.getOrDefault(fallback)

    /**
     * Sounds and potion effects are registry-backed, so they are resolved by key.
     *
     * Admins type the enum-style name they see everywhere else (`ENTITY_GENERIC_EXPLODE`), and
     * the registry wants the lower-case namespaced form, so the name is converted rather than
     * demanding a different spelling in this one place.
     */
    fun sound(name: String): Sound? = runCatching {
        org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.minecraft(name.trim().lowercase()))
    }.getOrNull()

    fun potion(name: String): PotionEffectType? = runCatching {
        org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(name.trim().lowercase()))
    }.getOrNull()

    /**
     * The one place particles are actually spawned.
     *
     * Roughly a dozen particles are not optional about their extra data - BLOCK, ITEM, DUST and
     * their relatives - and spawning one bare throws
     * `IllegalArgumentException: missing required data interface ...`, which aborts the entire
     * skill cast. Since particle names come from a free-text GUI field, *any* of them can arrive
     * here, so the data is derived rather than assumed absent.
     *
     * [context] is used to pick something that looks right: a ground effect uses the block it is
     * standing on, so a slam kicks up the actual terrain rather than a fixed placeholder.
     */
    fun spawn(
        location: Location,
        particle: Particle,
        count: Int,
        spreadX: Double,
        spreadY: Double,
        spreadZ: Double,
        speed: Double,
        context: Location = location,
    ) {
        val world = location.world ?: return
        val amount = count.coerceIn(1, 2000)

        if (particle.dataType == Void::class.java) {
            world.spawnParticle(particle, location, amount, spreadX, spreadY, spreadZ, speed)
            return
        }

        val data = dataFor(particle, context)
        if (data == null) {
            // A particle whose data we cannot build would throw; showing something is better
            // than showing nothing and losing the rest of the skill with it.
            world.spawnParticle(FALLBACK, location, amount, spreadX, spreadY, spreadZ, speed)
            return
        }
        world.spawnParticle(particle, location, amount, spreadX, spreadY, spreadZ, speed, data)
    }

    /**
     * Extra data for a particle that demands some, or null when we cannot supply it.
     *
     * Dispatches on the declared data type rather than on the particle name, so a version that
     * adds another BlockData-backed particle is handled without a code change.
     */
    fun dataFor(particle: Particle, context: Location): Any? {
        val type = particle.dataType
        return when {
            type == Void::class.java -> null

            org.bukkit.block.data.BlockData::class.java.isAssignableFrom(type) -> groundData(context)

            org.bukkit.inventory.ItemStack::class.java.isAssignableFrom(type) ->
                org.bukkit.inventory.ItemStack(org.bukkit.Material.FLINT)

            Particle.DustOptions::class.java.isAssignableFrom(type) ->
                Particle.DustOptions(org.bukkit.Color.RED, 1.0f)

            Particle.DustTransition::class.java.isAssignableFrom(type) ->
                Particle.DustTransition(org.bukkit.Color.RED, org.bukkit.Color.WHITE, 1.0f)

            Particle.Spell::class.java.isAssignableFrom(type) ->
                Particle.Spell(org.bukkit.Color.WHITE, 1.0f)

            // Geyser and GeyserBase are siblings, so GeyserBase is matched first - checking the
            // other order would still work today, but only by accident of them being unrelated.
            Particle.GeyserBase::class.java.isAssignableFrom(type) -> Particle.GeyserBase(1, 1.0f)

            Particle.Geyser::class.java.isAssignableFrom(type) -> Particle.Geyser(1)

            Particle.Trail::class.java.isAssignableFrom(type) ->
                Particle.Trail(context, org.bukkit.Color.WHITE, 20)

            org.bukkit.Vibration::class.java.isAssignableFrom(type) ->
                org.bukkit.Vibration(
                    org.bukkit.Vibration.Destination.BlockDestination(context),
                    20,
                )

            org.bukkit.Color::class.java.isAssignableFrom(type) -> org.bukkit.Color.RED

            type == Float::class.javaObjectType || type == Float::class.javaPrimitiveType -> 1.0f

            type == Int::class.javaObjectType || type == Int::class.javaPrimitiveType -> 0

            // A type a later version introduces: the caller substitutes a plain particle rather
            // than guessing at a constructor it has never seen.
            else -> null
        }
    }

    /** Block data for terrain effects: the ground underfoot, falling back to the block itself. */
    private fun groundData(context: Location): org.bukkit.block.data.BlockData {
        val below = context.block.getRelative(org.bukkit.block.BlockFace.DOWN)
        if (!below.isEmpty) return below.blockData
        val here = context.block
        if (!here.isEmpty) return here.blockData
        return org.bukkit.Material.STONE.createBlockData()
    }

    fun burst(location: Location, particle: Particle, count: Int, spread: Double, speed: Double = 0.02) {
        spawn(location, particle, count, spread, spread, spread, speed)
    }

    fun play(location: Location, name: String, volume: Float = 1.0f, pitch: Float = 1.0f) {
        if (name.isBlank()) return
        val world = location.world ?: return
        sound(name)?.let { world.playSound(location, it, volume, pitch) }
    }

    /** Flat ring of particles on the ground, used to show a skill's reach. */
    fun ring(centre: Location, radius: Double, particle: Particle, points: Int = 32) {
        val world = centre.world ?: return
        val steps = points.coerceIn(4, 200)
        for (i in 0 until steps) {
            val angle = 2.0 * Math.PI * i / steps
            val point = Location(
                world,
                centre.x + radius * kotlin.math.cos(angle),
                centre.y + 0.2,
                centre.z + radius * kotlin.math.sin(angle),
            )
            spawn(point, particle, 1, 0.0, 0.0, 0.0, 0.0, context = centre)
        }
    }

    /** Straight line of particles from [from] to [to]. */
    fun line(from: Location, to: Location, particle: Particle, density: Double = 0.5) {
        val world = from.world ?: return
        if (to.world != world) return
        val direction = to.toVector().subtract(from.toVector())
        val length = direction.length()
        if (length <= 0.0) return
        val step = direction.normalize().multiply(density)
        val point = from.clone()
        var travelled = 0.0
        while (travelled < length) {
            spawn(point, particle, 1, 0.0, 0.0, 0.0, 0.0, context = from)
            point.add(step)
            travelled += density
        }
    }

    /** Shown when a configured particle needs data we cannot construct. */
    private val FALLBACK = Particle.CRIT
}

/** Damage, movement and effect helpers that need the cast context. */
object Act {

    /** Skill damage through the shared bridge, so MythicLib rules apply when available. */
    fun damage(ctx: SkillContext, target: LivingEntity, amount: Double) {
        if (amount <= 0.0) return
        ctx.monsters.damage.applySkill(ctx.caster, target, amount)
    }

    fun damageAll(ctx: SkillContext, targets: Collection<LivingEntity>, amount: Double) {
        targets.forEach { damage(ctx, it, amount) }
    }

    /**
     * Living entities inside [radius] of [centre] that this mob should treat as enemies.
     *
     * Excludes the caster and every other custom mob, so an area attack never turns a pack of
     * summons into a friendly-fire accident.
     */
    fun enemiesNear(ctx: SkillContext, centre: Location, radius: Double): List<LivingEntity> =
        centre.getNearbyLivingEntities(radius)
            .filter { it != ctx.entity && !ctx.monsters.tracker.isCustom(it) && !it.isDead }
            .toList()

    fun alliesNear(ctx: SkillContext, centre: Location, radius: Double): List<LivingEntity> =
        centre.getNearbyLivingEntities(radius)
            .filter { it != ctx.entity && ctx.monsters.tracker.isCustom(it) && !it.isDead }
            .toList()

    fun effect(target: LivingEntity, type: PotionEffectType?, duration: Int, amplifier: Int) {
        if (type == null) return
        target.addPotionEffect(
            PotionEffect(type, duration.coerceAtLeast(1), amplifier.coerceIn(0, 255), false, true, true),
        )
    }

    /**
     * Applies knockback away from [from].
     *
     * Velocity is set rather than added: adding onto whatever the entity was already doing makes
     * the same skill fling a running player twice as far as a standing one, which reads as the
     * skill being inconsistent.
     */
    fun knockback(target: LivingEntity, from: Location, horizontal: Double, vertical: Double) {
        val direction = target.location.toVector().subtract(from.toVector())
        if (direction.lengthSquared() < 1.0E-4) direction.add(Vector(0.0, 0.0, 1.0))
        direction.setY(0.0).normalize().multiply(horizontal)
        direction.y = vertical
        target.velocity = direction
    }

    fun pull(target: LivingEntity, towards: Location, strength: Double) {
        val direction = towards.toVector().subtract(target.location.toVector())
        if (direction.lengthSquared() < 1.0E-4) return
        target.velocity = direction.normalize().multiply(strength).setY(0.25)
    }

    fun heal(target: LivingEntity, amount: Double) {
        if (amount <= 0.0) return
        val max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: return
        target.health = (target.health + amount).coerceIn(0.0, max)
    }

    /** Runs [block] after [delayTicks], on the main thread. Used by multi-stage skills. */
    fun later(ctx: SkillContext, delayTicks: Long, block: () -> Unit) {
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            ctx.monsters.plugin,
            Runnable {
                // The mob may have died or been removed during the delay, and touching a dead
                // entity throws; skills that fire after a wait must always re-check.
                if (ctx.caster.isAlive) block()
            },
            delayTicks.coerceAtLeast(1L),
        )
    }
}
