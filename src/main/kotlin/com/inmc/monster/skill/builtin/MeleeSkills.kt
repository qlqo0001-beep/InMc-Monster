package com.inmc.monster.skill.builtin

import com.inmc.monster.skill.Skill
import com.inmc.monster.skill.SkillContext
import com.inmc.monster.skill.SkillParam
import com.inmc.monster.skill.TargetSelector
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.util.Vector

/** Cone-shaped swing in front of the mob. The bread-and-butter attack. */
object MeleeStrike : Skill {
    override val id = "melee_strike"
    override val displayName = "근접 강타"
    override val description = listOf("전방 부채꼴 범위의 대상을 한 번에 베어냅니다.")
    override val icon = Material.IRON_SWORD
    override val defaultSelector = TargetSelector.TARGET
    override val parameters = listOf(
        SkillParam.double("damage", "피해량", 8.0, 0.0, 10_000.0),
        SkillParam.double("range", "사거리", 4.0, 1.0, 32.0),
        SkillParam.double("angle", "부채꼴 각도", 90.0, 10.0, 360.0),
        SkillParam.particle("particle", "파티클", "SWEEP_ATTACK"),
        SkillParam.sound("sound", "효과음", "ENTITY_PLAYER_ATTACK_SWEEP"),
    )

    override fun cast(ctx: SkillContext) {
        val range = ctx.params.double("range")
        val halfAngle = ctx.params.double("angle") / 2.0
        val facing = ctx.entity.location.direction.setY(0.0).normalize()

        val hit = Act.enemiesNear(ctx, ctx.origin, range).filter { candidate ->
            val toTarget = candidate.location.toVector().subtract(ctx.origin.toVector()).setY(0.0)
            if (toTarget.lengthSquared() < 1.0E-4) return@filter true
            Math.toDegrees(facing.angle(toTarget.normalize()).toDouble()) <= halfAngle
        }

        Fx.burst(ctx.origin.clone().add(facing.clone().multiply(1.5)).add(0.0, 1.0, 0.0),
            Fx.particle(ctx.params.string("particle"), Particle.SWEEP_ATTACK), 8, 0.5)
        Fx.play(ctx.origin, ctx.params.string("sound"))

        Act.damageAll(ctx, hit, ctx.params.double("damage"))
    }
}

/** Jumps at the target and hits everything where it lands. */
object LeapAttack : Skill {
    override val id = "leap_attack"
    override val requiresTarget = true
    override val displayName = "도약 공격"
    override val description = listOf(
        "대상을 향해 뛰어오른 뒤 착지 지점에 피해를 줍니다.",
        "착지 판정은 도약 후 일정 시간 뒤에 발생합니다.",
    )
    override val icon = Material.RABBIT_FOOT
    override val parameters = listOf(
        SkillParam.double("damage", "착지 피해량", 12.0, 0.0, 10_000.0),
        SkillParam.double("radius", "착지 반경", 3.5, 0.5, 32.0),
        SkillParam.double("power", "도약 세기", 1.2, 0.1, 5.0),
        SkillParam.int("land-delay", "착지 판정 지연 (틱)", 20, 1, 200),
        SkillParam.particle("particle", "착지 파티클", "EXPLOSION"),
        SkillParam.sound("sound", "효과음", "ENTITY_GENERIC_EXPLODE"),
    )

    override fun cast(ctx: SkillContext) {
        val target = ctx.target ?: return
        val power = ctx.params.double("power")

        val direction = target.location.toVector().subtract(ctx.origin.toVector())
        if (direction.lengthSquared() < 1.0E-4) return
        direction.normalize().multiply(power)
        // Upward component is fixed rather than proportional: scaling it with distance made
        // long leaps launch the mob into the ceiling of any indoor arena.
        direction.y = 0.55
        ctx.entity.velocity = direction

        val radius = ctx.params.double("radius")
        val damage = ctx.params.double("damage")
        val particle = Fx.particle(ctx.params.string("particle"), Particle.EXPLOSION)

        Act.later(ctx, ctx.params.int("land-delay").toLong()) {
            val landing = ctx.entity.location
            Fx.burst(landing, particle, 20, radius / 2.0)
            Fx.ring(landing, radius, particle)
            Fx.play(landing, ctx.params.string("sound"))
            Act.damageAll(ctx, Act.enemiesNear(ctx, landing, radius), damage)
        }
    }
}

/** Straight-line rush that damages and knocks back whoever it runs into. */
object Charge : Skill {
    override val id = "charge"
    override val requiresTarget = true
    override val displayName = "돌진"
    override val description = listOf(
        "대상 방향으로 직선 돌진하며 부딪힌 대상에게 피해와 넉백을 줍니다.",
    )
    override val icon = Material.PISTON
    override val parameters = listOf(
        SkillParam.double("damage", "충돌 피해량", 10.0, 0.0, 10_000.0),
        SkillParam.double("speed", "돌진 속도", 1.4, 0.2, 5.0),
        SkillParam.double("width", "충돌 판정 폭", 1.5, 0.5, 8.0),
        SkillParam.double("knockback", "넉백 세기", 1.0, 0.0, 5.0),
        SkillParam.int("duration", "돌진 지속 (틱)", 20, 5, 200),
        SkillParam.particle("particle", "파티클", "CLOUD"),
    )

    override fun cast(ctx: SkillContext) {
        val target = ctx.target ?: return
        val speed = ctx.params.double("speed")
        val direction = target.location.toVector().subtract(ctx.origin.toVector()).setY(0.0)
        if (direction.lengthSquared() < 1.0E-4) return
        direction.normalize().multiply(speed)

        val width = ctx.params.double("width")
        val damage = ctx.params.double("damage")
        val knockback = ctx.params.double("knockback")
        val particle = Fx.particle(ctx.params.string("particle"), Particle.CLOUD)
        val duration = ctx.params.int("duration")

        // Each entity is damaged at most once per charge - without the guard the mob would hit
        // the same player on every tick it stayed in contact, which is a stunlock, not a rush.
        val alreadyHit = HashSet<java.util.UUID>()

        var ticks = 0
        val task = object : Runnable {
            override fun run() {
                if (!ctx.caster.isAlive || ticks++ >= duration) return
                ctx.entity.velocity = direction.clone()
                val here = ctx.entity.location
                Fx.burst(here, particle, 4, 0.3)
                for (victim in Act.enemiesNear(ctx, here, width)) {
                    if (!alreadyHit.add(victim.uniqueId)) continue
                    Act.damage(ctx, victim, damage)
                    Act.knockback(victim, here, knockback, 0.35)
                }
                Act.later(ctx, 1L) { run() }
            }
        }
        task.run()
    }
}

/** Slams the ground, damaging and launching everything around the mob. */
object GroundSlam : Skill {
    override val id = "ground_slam"
    override val displayName = "지면 강타"
    override val description = listOf(
        "주변 대상에게 피해를 주고 공중으로 띄웁니다.",
        "보스의 광역 견제기로 쓰기 좋습니다.",
    )
    override val icon = Material.ANVIL
    override val defaultSelector = TargetSelector.SELF
    override val parameters = listOf(
        SkillParam.double("damage", "피해량", 14.0, 0.0, 10_000.0),
        SkillParam.double("radius", "반경", 6.0, 1.0, 48.0),
        SkillParam.double("launch", "띄우는 높이", 0.8, 0.0, 3.0),
        SkillParam.particle("particle", "파티클", "BLOCK"),
        SkillParam.sound("sound", "효과음", "ENTITY_GENERIC_EXPLODE"),
    )

    override fun cast(ctx: SkillContext) {
        val radius = ctx.params.double("radius")
        val launch = ctx.params.double("launch")
        val particle = Fx.particle(ctx.params.string("particle"), Particle.CLOUD)

        Fx.ring(ctx.origin, radius, particle)
        Fx.ring(ctx.origin, radius / 2.0, particle, 20)
        Fx.burst(ctx.origin, particle, 30, 1.0)
        Fx.play(ctx.origin, ctx.params.string("sound"))

        for (victim in Act.enemiesNear(ctx, ctx.origin, radius)) {
            Act.damage(ctx, victim, ctx.params.double("damage"))
            if (launch > 0.0) {
                victim.velocity = victim.velocity.clone().add(Vector(0.0, launch, 0.0))
            }
        }
    }
}

/** Pushes everything nearby away. Pure control, no damage by default. */
object KnockbackSkill : Skill {
    override val id = "knockback"
    override val displayName = "밀치기"
    override val description = listOf("반경 내 대상을 바깥으로 밀어냅니다.")
    override val icon = Material.SLIME_BLOCK
    override val defaultSelector = TargetSelector.SELF
    override val parameters = listOf(
        SkillParam.double("radius", "반경", 5.0, 1.0, 48.0),
        SkillParam.double("horizontal", "수평 세기", 1.4, 0.0, 6.0),
        SkillParam.double("vertical", "수직 세기", 0.45, 0.0, 3.0),
        SkillParam.double("damage", "피해량", 0.0, 0.0, 10_000.0),
        SkillParam.particle("particle", "파티클", "EXPLOSION"),
    )

    override fun cast(ctx: SkillContext) {
        val radius = ctx.params.double("radius")
        val damage = ctx.params.double("damage")
        Fx.ring(ctx.origin, radius, Fx.particle(ctx.params.string("particle"), Particle.EXPLOSION))
        for (victim in Act.enemiesNear(ctx, ctx.origin, radius)) {
            if (damage > 0.0) Act.damage(ctx, victim, damage)
            Act.knockback(victim, ctx.origin, ctx.params.double("horizontal"), ctx.params.double("vertical"))
        }
    }
}

/** Yanks the target in. The counterpart to knockback, for melee bosses players try to kite. */
object Grapple : Skill {
    override val id = "grapple"
    override val requiresTarget = true
    override val displayName = "당기기"
    override val description = listOf(
        "대상을 자신 쪽으로 끌어당깁니다.",
        "원거리로 도망치는 플레이어를 붙잡을 때 씁니다.",
    )
    override val icon = Material.FISHING_ROD
    override val parameters = listOf(
        SkillParam.double("strength", "당기는 세기", 1.2, 0.1, 5.0),
        SkillParam.double("damage", "피해량", 0.0, 0.0, 10_000.0),
        SkillParam.particle("particle", "파티클", "CRIT"),
        SkillParam.sound("sound", "효과음", "ENTITY_FISHING_BOBBER_RETRIEVE"),
    )

    override fun cast(ctx: SkillContext) {
        val strength = ctx.params.double("strength")
        val damage = ctx.params.double("damage")
        val particle = Fx.particle(ctx.params.string("particle"), Particle.CRIT)
        for (victim in ctx.targets) {
            Fx.line(ctx.origin.clone().add(0.0, 1.0, 0.0), victim.location.add(0.0, 1.0, 0.0), particle)
            Act.pull(victim, ctx.origin, strength)
            if (damage > 0.0) Act.damage(ctx, victim, damage)
        }
        Fx.play(ctx.origin, ctx.params.string("sound"))
    }
}

/** Short-range teleport, used to close distance or reposition behind a player. */
object Blink : Skill {
    override val id = "blink"
    override val requiresTarget = true
    override val displayName = "순간이동"
    override val description = listOf(
        "대상 뒤 또는 주변 무작위 위치로 순간이동합니다.",
    )
    override val icon = Material.ENDER_PEARL
    override val parameters = listOf(
        SkillParam.enum("mode", "이동 방식", "BEHIND_TARGET", listOf("BEHIND_TARGET", "RANDOM_NEARBY", "TO_TARGET")),
        SkillParam.double("distance", "거리", 2.0, 0.5, 32.0),
        SkillParam.particle("particle", "파티클", "PORTAL"),
        SkillParam.sound("sound", "효과음", "ENTITY_ENDERMAN_TELEPORT"),
    )

    override fun cast(ctx: SkillContext) {
        val distance = ctx.params.double("distance")
        val target = ctx.target

        val destination = when (ctx.params.string("mode").uppercase()) {
            "RANDOM_NEARBY" -> {
                val angle = Math.random() * 2.0 * Math.PI
                ctx.origin.clone().add(
                    distance * kotlin.math.cos(angle), 0.0, distance * kotlin.math.sin(angle),
                )
            }

            "TO_TARGET" -> target?.location?.clone() ?: return

            else -> {
                val behind = target?.location?.direction?.setY(0.0)?.normalize()?.multiply(-distance) ?: return
                target.location.clone().add(behind)
            }
        }

        // Never teleport into a wall: a mob stuck inside a block suffocates or falls out of the
        // arena, and both read to a player as the boss having glitched.
        val world = destination.world ?: return
        destination.y = world.getHighestBlockYAt(destination).toDouble() + 1.0
        if (!destination.block.isPassable) return

        val particle = Fx.particle(ctx.params.string("particle"), Particle.PORTAL)
        Fx.burst(ctx.origin.clone().add(0.0, 1.0, 0.0), particle, 30, 0.5)
        ctx.entity.teleport(destination)
        Fx.burst(destination.clone().add(0.0, 1.0, 0.0), particle, 30, 0.5)
        Fx.play(destination, ctx.params.string("sound"))
    }
}
