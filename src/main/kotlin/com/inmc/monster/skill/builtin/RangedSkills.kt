package com.inmc.monster.skill.builtin

import com.inmc.monster.skill.Skill
import com.inmc.monster.skill.SkillContext
import com.inmc.monster.skill.SkillParam
import com.inmc.monster.skill.TargetSelector
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Arrow
import org.bukkit.entity.Fireball
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Projectile
import org.bukkit.entity.SmallFireball
import org.bukkit.entity.Snowball

/** Fires a fan of projectiles at the target. */
object ProjectileVolley : Skill {
    override val id = "projectile_volley"
    override val requiresTarget = true
    override val displayName = "투사체 연발"
    override val description = listOf(
        "대상을 향해 여러 발의 투사체를 부채꼴로 발사합니다.",
        "화살·눈덩이·화염구 중에서 고를 수 있습니다.",
    )
    override val icon = Material.BOW
    override val parameters = listOf(
        SkillParam.enum("type", "투사체", "ARROW", listOf("ARROW", "SNOWBALL", "SMALL_FIREBALL", "FIREBALL")),
        SkillParam.int("count", "발사 수", 5, 1, 40),
        SkillParam.double("spread", "퍼짐 각도", 30.0, 0.0, 180.0),
        SkillParam.double("speed", "속도", 1.6, 0.1, 6.0),
        SkillParam.double("damage", "발당 피해량", 5.0, 0.0, 10_000.0),
        SkillParam.sound("sound", "효과음", "ENTITY_ARROW_SHOOT"),
    )

    override fun cast(ctx: SkillContext) {
        val target = ctx.target ?: return
        val count = ctx.params.int("count")
        val spread = ctx.params.double("spread")
        val speed = ctx.params.double("speed")
        val damage = ctx.params.double("damage")
        val kind = ctx.params.string("type").uppercase()

        val eye = ctx.entity.eyeLocation
        val base = target.eyeLocation.toVector().subtract(eye.toVector()).normalize()

        for (i in 0 until count) {
            // Spread is distributed evenly rather than randomly, so a volley reads as a fan the
            // player can step out of instead of an unpredictable shotgun.
            val offset = if (count == 1) 0.0 else (i.toDouble() / (count - 1) - 0.5) * spread
            val direction = base.clone().rotateAroundY(Math.toRadians(offset)).multiply(speed)

            val projectile = launch(ctx, kind, direction) ?: continue
            ProjectileTracker.register(ctx, projectile, damage)
        }
        Fx.play(eye, ctx.params.string("sound"))
    }

    private fun launch(ctx: SkillContext, kind: String, direction: org.bukkit.util.Vector): Projectile? {
        val shooter = ctx.entity
        return runCatching {
            when (kind) {
                "SNOWBALL" -> shooter.launchProjectile(Snowball::class.java, direction)
                "SMALL_FIREBALL" -> shooter.launchProjectile(SmallFireball::class.java, direction)
                "FIREBALL" -> shooter.launchProjectile(Fireball::class.java, direction)
                else -> shooter.launchProjectile(Arrow::class.java, direction)
            }
        }.getOrNull()
    }
}

/** A slow orb that steers towards its target. */
object HomingOrb : Skill {
    override val id = "homing_orb"
    override val requiresTarget = true
    override val displayName = "유도탄"
    override val description = listOf(
        "대상을 천천히 추적하는 구체를 날립니다.",
        "명중하거나 지속 시간이 끝나면 사라집니다.",
    )
    override val icon = Material.ENDER_EYE
    override val parameters = listOf(
        SkillParam.double("damage", "명중 피해량", 9.0, 0.0, 10_000.0),
        SkillParam.double("speed", "이동 속도", 0.35, 0.05, 2.0),
        SkillParam.double("hit-radius", "명중 판정 반경", 1.2, 0.3, 6.0),
        SkillParam.int("duration", "지속 시간 (틱)", 120, 10, 600),
        SkillParam.particle("particle", "파티클", "SOUL_FIRE_FLAME"),
    )

    override fun cast(ctx: SkillContext) {
        val target = ctx.target ?: return
        val speed = ctx.params.double("speed")
        val hitRadius = ctx.params.double("hit-radius")
        val damage = ctx.params.double("damage")
        val particle = Fx.particle(ctx.params.string("particle"), Particle.SOUL_FIRE_FLAME)
        val duration = ctx.params.int("duration")

        // No real entity is spawned: a moving particle point costs nothing, cannot be blocked
        // by another plugin's projectile handling, and cannot leak if the chunk unloads.
        val position = ctx.entity.eyeLocation.clone()
        var ticks = 0

        val step = object : Runnable {
            override fun run() {
                if (ticks++ >= duration || !target.isValid || target.isDead) return
                if (position.world == null) return

                val toTarget = target.eyeLocation.toVector().subtract(position.toVector())
                if (toTarget.lengthSquared() > 1.0E-4) {
                    position.add(toTarget.normalize().multiply(speed))
                }
                Fx.spawn(position, particle, 3, 0.05, 0.05, 0.05, 0.0)

                if (position.distanceSquared(target.eyeLocation) <= hitRadius * hitRadius) {
                    Fx.burst(position, particle, 20, 0.4)
                    Act.damage(ctx, target, damage)
                    return
                }
                if (!position.block.isPassable) {
                    Fx.burst(position, particle, 10, 0.3)
                    return
                }
                Act.later(ctx, 1L) { run() }
            }
        }
        step.run()
    }
}

/** Explosion at the target's feet, with optional lingering fire. */
object FireballSkill : Skill {
    override val id = "fireball"
    override val displayName = "화염구"
    override val description = listOf(
        "대상 위치에 폭발을 일으킵니다.",
        "블록 파괴는 기본으로 꺼져 있습니다.",
    )
    override val icon = Material.FIRE_CHARGE
    override val parameters = listOf(
        SkillParam.double("damage", "피해량", 12.0, 0.0, 10_000.0),
        SkillParam.double("radius", "폭발 반경", 3.0, 0.5, 24.0),
        SkillParam.int("ignite-ticks", "화염 지속 (틱)", 60, 0, 600),
        SkillParam.bool("break-blocks", "블록 파괴", false),
        SkillParam.particle("particle", "파티클", "FLAME"),
        SkillParam.sound("sound", "효과음", "ENTITY_GENERIC_EXPLODE"),
    )

    override fun cast(ctx: SkillContext) {
        val centre = ctx.target?.location ?: ctx.origin
        val radius = ctx.params.double("radius")
        val particle = Fx.particle(ctx.params.string("particle"), Particle.FLAME)

        Fx.burst(centre, particle, 40, radius / 2.0, 0.05)
        Fx.ring(centre, radius, particle)
        Fx.play(centre, ctx.params.string("sound"))

        if (ctx.params.bool("break-blocks")) {
            centre.world?.createExplosion(centre, radius.toFloat(), false, true, ctx.entity)
        }

        val ignite = ctx.params.int("ignite-ticks")
        for (victim in Act.enemiesNear(ctx, centre, radius)) {
            Act.damage(ctx, victim, ctx.params.double("damage"))
            if (ignite > 0) victim.fireTicks = maxOf(victim.fireTicks, ignite)
        }
    }
}

/** Calls lightning down on the target. Visual and real damage are separated on purpose. */
object LightningSkill : Skill {
    override val id = "lightning"
    override val displayName = "번개"
    override val description = listOf(
        "대상 위치에 번개를 내립니다.",
        "'실제 피해' 를 끄면 연출용 번개가 되어 바닐라 피해가 들어가지 않습니다.",
    )
    override val icon = Material.LIGHTNING_ROD
    override val parameters = listOf(
        SkillParam.double("damage", "피해량", 10.0, 0.0, 10_000.0),
        SkillParam.int("count", "번개 수", 1, 1, 20),
        SkillParam.double("spread", "퍼지는 반경", 2.0, 0.0, 32.0),
        SkillParam.bool("vanilla-strike", "바닐라 번개 피해 사용", false),
        SkillParam.double("radius", "피해 반경", 2.5, 0.5, 24.0),
    )

    override fun cast(ctx: SkillContext) {
        val centre = ctx.target?.location ?: ctx.origin
        val world = centre.world ?: return
        val count = ctx.params.int("count")
        val spread = ctx.params.double("spread")
        val vanilla = ctx.params.bool("vanilla-strike")
        val radius = ctx.params.double("radius")
        val damage = ctx.params.double("damage")

        repeat(count) {
            val point = if (spread <= 0.0) centre.clone() else centre.clone().add(
                (Math.random() * 2 - 1) * spread, 0.0, (Math.random() * 2 - 1) * spread,
            )
            point.y = world.getHighestBlockYAt(point).toDouble()

            // The effect-only strike is the default: a real lightning bolt deals fixed vanilla
            // damage that ignores every stat on the mob, so a boss would hit for the same 5
            // hearts whatever its configured power.
            if (vanilla) world.strikeLightning(point) else world.strikeLightningEffect(point)

            if (!vanilla) {
                Act.damageAll(ctx, Act.enemiesNear(ctx, point, radius), damage)
            }
        }
    }
}

/**
 * Bookkeeping for projectiles a skill launched.
 *
 * A projectile arrives at its victim long after the cast returned, so the damage it should do
 * has to be remembered. The map is keyed by projectile id and swept on hit, and entries expire
 * on their own so a projectile that flies into the void cannot leak.
 */
object ProjectileTracker {

    private class Shot(val casterId: java.util.UUID, val damage: Double, val expiresAt: Long)

    private val shots = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Shot>()

    fun register(ctx: SkillContext, projectile: Projectile, damage: Double) {
        shots[projectile.uniqueId] = Shot(
            casterId = ctx.caster.uuid,
            damage = damage,
            expiresAt = System.currentTimeMillis() + 30_000L,
        )
    }

    /** Called by the combat listener when a tracked projectile hits something. */
    fun onHit(monsters: com.inmc.monster.Monsters, projectile: Projectile, victim: LivingEntity): Boolean {
        val shot = shots.remove(projectile.uniqueId) ?: return false
        val caster = monsters.tracker[shot.casterId] ?: return false
        if (shot.damage <= 0.0) return true
        monsters.damage.apply(
            caster, victim,
            monsters.damage.skillDamage(caster, shot.damage),
            com.inmc.monster.integration.MythicLibHook.PROJECTILE_TYPES,
        )
        return true
    }

    fun isTracked(projectile: Projectile): Boolean = shots.containsKey(projectile.uniqueId)

    /** Ticker hook: drops entries for projectiles that never hit anything. */
    fun purge(now: Long) {
        if (shots.isEmpty()) return
        shots.entries.removeIf { it.value.expiresAt <= now }
    }

    fun clear() = shots.clear()
}
