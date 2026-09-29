package com.inmc.monster.skill.builtin

import com.inmc.monster.skill.Skill
import com.inmc.monster.skill.SkillContext
import com.inmc.monster.skill.SkillParam
import com.inmc.monster.skill.TargetSelector
import com.inmc.monster.spawn.SpawnOptions
import com.inmc.monster.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Player

/** Restores health to the caster or its allies. */
object Heal : Skill {
    override val id = "heal"
    override val displayName = "회복"
    override val description = listOf("자신이나 주변 아군 몬스터의 체력을 회복시킵니다.")
    override val icon = Material.GOLDEN_APPLE
    override val defaultSelector = TargetSelector.SELF
    override val parameters = listOf(
        SkillParam.double("amount", "회복량", 20.0, 0.0, 100_000.0),
        SkillParam.bool("percent", "최대 체력 비율로 회복", false),
        SkillParam.particle("particle", "파티클", "HEART"),
        SkillParam.sound("sound", "효과음", "ENTITY_PLAYER_LEVELUP"),
    )

    override fun cast(ctx: SkillContext) {
        val amount = ctx.params.double("amount")
        val percent = ctx.params.bool("percent")
        val particle = Fx.particle(ctx.params.string("particle"), Particle.HEART)

        val targets = ctx.targets.ifEmpty { listOf(ctx.entity) }
        for (target in targets) {
            val max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: continue
            val healed = if (percent) max * amount / 100.0 else amount
            Act.heal(target, healed)
            Fx.burst(target.location.add(0.0, 1.0, 0.0), particle, 8, 0.4)
        }
        Fx.play(ctx.origin, ctx.params.string("sound"))
    }
}

/**
 * Temporary stat boost.
 *
 * The buff is written into the mob's resolved stat map and reverted on a timer. That is a
 * deliberate choice over an attribute modifier: our custom stats have no attribute to hang a
 * modifier on, and doing half of them one way and half the other would make the numbers on the
 * status screen disagree with the numbers in the damage calculation.
 */
object Enrage : Skill {
    override val id = "enrage"
    override val displayName = "광폭화"
    override val description = listOf(
        "일정 시간 동안 공격력과 이동 속도가 올라갑니다.",
        "지속 시간이 끝나면 원래 값으로 되돌아갑니다.",
    )
    override val icon = Material.BLAZE_POWDER
    override val defaultSelector = TargetSelector.SELF
    override val parameters = listOf(
        SkillParam.double("damage-multiplier", "공격력 배율", 1.5, 1.0, 10.0),
        SkillParam.double("speed-multiplier", "이동 속도 배율", 1.3, 1.0, 5.0),
        SkillParam.int("duration", "지속 시간 (틱)", 200, 20, 12_000),
        SkillParam.particle("particle", "파티클", "ANGRY_VILLAGER"),
        SkillParam.sound("sound", "효과음", "ENTITY_RAVAGER_ROAR"),
    )

    override fun cast(ctx: SkillContext) {
        val mob = ctx.caster
        val damageFactor = ctx.params.double("damage-multiplier")
        val speedFactor = ctx.params.double("speed-multiplier")

        val previousDamage = mob.stats.getOrZero("ATTACK_DAMAGE")
        val speedAttribute = ctx.entity.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)
        val previousSpeed = speedAttribute?.baseValue

        mob.stats["ATTACK_DAMAGE"] = previousDamage * damageFactor
        if (speedAttribute != null && previousSpeed != null) {
            speedAttribute.baseValue = (previousSpeed * speedFactor).coerceIn(0.0, 4.0)
        }

        val particle = Fx.particle(ctx.params.string("particle"), Particle.ANGRY_VILLAGER)
        Fx.burst(ctx.origin.clone().add(0.0, 1.2, 0.0), particle, 25, 0.6)
        Fx.play(ctx.origin, ctx.params.string("sound"))

        Act.later(ctx, ctx.params.int("duration").toLong()) {
            mob.stats["ATTACK_DAMAGE"] = previousDamage
            if (speedAttribute != null && previousSpeed != null) speedAttribute.baseValue = previousSpeed
        }
    }
}

/** Absorption hearts, so a boss can survive a burst window. */
object Shield : Skill {
    override val id = "shield"
    override val displayName = "방어막"
    override val description = listOf("흡수 하트를 부여해 일정량의 피해를 대신 받아냅니다.")
    override val icon = Material.SHIELD
    override val defaultSelector = TargetSelector.SELF
    override val parameters = listOf(
        SkillParam.double("amount", "방어막 양", 20.0, 1.0, 1000.0),
        SkillParam.int("duration", "지속 시간 (틱)", 200, 20, 12_000),
        SkillParam.particle("particle", "파티클", "END_ROD"),
    )

    override fun cast(ctx: SkillContext) {
        val amount = ctx.params.double("amount")
        val particle = Fx.particle(ctx.params.string("particle"), Particle.END_ROD)

        val targets = ctx.targets.ifEmpty { listOf(ctx.entity) }
        for (target in targets) {
            val maxAbsorption = target.getAttribute(org.bukkit.attribute.Attribute.MAX_ABSORPTION)
            // MAX_ABSORPTION caps the value the server will accept, so it is raised first -
            // otherwise a 40-point shield silently becomes whatever the mob's default allows.
            if (maxAbsorption != null && maxAbsorption.baseValue < amount) maxAbsorption.baseValue = amount
            target.absorptionAmount = amount
            Fx.burst(target.location.add(0.0, 1.0, 0.0), particle, 20, 0.5)
        }

        Act.later(ctx, ctx.params.int("duration").toLong()) {
            targets.forEach { if (it.isValid) it.absorptionAmount = 0.0 }
        }
    }
}

/** Applies an arbitrary potion effect to whoever the selector picked. */
object PotionSkill : Skill {
    override val id = "potion"
    override val displayName = "포션 효과"
    override val description = listOf("대상에게 원하는 포션 효과를 겁니다.")
    override val icon = Material.POTION
    override val parameters = listOf(
        SkillParam.potion("effect", "포션 효과", "SLOWNESS"),
        SkillParam.int("duration", "지속 시간 (틱)", 100, 1, 72_000),
        SkillParam.int("amplifier", "강도", 0, 0, 10),
        SkillParam.bool("ambient", "은은한 파티클", false),
    )

    override fun cast(ctx: SkillContext) {
        val type = Fx.potion(ctx.params.string("effect")) ?: return
        val duration = ctx.params.int("duration")
        val amplifier = ctx.params.int("amplifier")
        for (target in ctx.targets) {
            Act.effect(target, type, duration, amplifier)
        }
    }
}

/** Screen-warping fear effect. Disorients without stunning. */
object Terrify : Skill {
    override val id = "terrify"
    override val displayName = "공포"
    override val description = listOf(
        "대상의 시야를 뒤흔들고 구토·실명 효과를 겁니다.",
        "완전한 행동 불능이 아니라 조준을 흔드는 견제기입니다.",
    )
    override val icon = Material.WITHER_SKELETON_SKULL
    override val parameters = listOf(
        SkillParam.int("duration", "지속 시간 (틱)", 80, 10, 1200),
        SkillParam.bool("spin", "시야 회전", true),
        SkillParam.bool("nausea", "구토 효과", true),
        SkillParam.bool("blind", "실명 효과", false),
        SkillParam.sound("sound", "효과음", "ENTITY_GHAST_SCREAM"),
    )

    override fun cast(ctx: SkillContext) {
        val duration = ctx.params.int("duration")
        for (target in ctx.targets) {
            if (ctx.params.bool("nausea")) Act.effect(target, Fx.potion("NAUSEA"), duration, 0)
            if (ctx.params.bool("blind")) Act.effect(target, Fx.potion("BLINDNESS"), duration / 2, 0)
            if (ctx.params.bool("spin") && target is Player) {
                // One nudge, not a per-tick rotation: continuously fighting the player's mouse
                // is unplayable rather than frightening.
                val location = target.location
                location.yaw += 90f + (Math.random() * 90.0).toFloat()
                target.teleport(location)
            }
        }
        Fx.play(ctx.origin, ctx.params.string("sound"))
    }
}

/**
 * Spawns other custom mobs.
 *
 * Every summon is a child of this mob, so it inherits the generation counter and the spawn
 * service refuses it once the chain gets too deep. That ceiling is what stops a mob summoning
 * a mob that summons it back.
 */
object Summon : Skill {
    override val id = "summon"
    override val displayName = "몬스터 소환"
    override val description = listOf(
        "다른 커스텀 몬스터를 불러냅니다.",
        "소환된 몬스터는 소환 깊이 제한을 물려받습니다.",
    )
    override val icon = Material.ZOMBIE_SPAWN_EGG
    override val defaultSelector = TargetSelector.SELF
    override val parameters = listOf(
        SkillParam.mob("mob", "소환할 몬스터"),
        SkillParam.int("amount", "마리 수", 2, 1, 20),
        SkillParam.double("spread", "퍼지는 반경", 3.0, 0.0, 24.0),
        SkillParam.bool("inherit-level", "레벨 물려주기", true),
        SkillParam.int("lifespan", "소환수 수명 (초, 0=무제한)", 60, 0, 3600),
        SkillParam.particle("particle", "파티클", "SOUL"),
    )

    override fun cast(ctx: SkillContext) {
        val mobId = ctx.params.string("mob")
        if (mobId.isBlank()) return
        val definition = ctx.monsters.mobs.get(mobId) ?: run {
            ctx.monsters.logger.warning("소환 스킬에 없는 몬스터가 지정되어 있습니다: " + mobId)
            return
        }

        val amount = ctx.params.int("amount")
        val spread = ctx.params.double("spread")
        val particle = Fx.particle(ctx.params.string("particle"), Particle.SOUL)
        val lifespan = ctx.params.int("lifespan")
        val options = SpawnOptions.childOf(ctx.caster, ctx.params.bool("inherit-level"))

        repeat(amount) {
            val where = ctx.monsters.spawns.scatter(ctx.origin, spread)
            val summoned = ctx.monsters.spawns.spawn(definition, where, options) ?: return@repeat
            if (lifespan > 0) {
                summoned.expiresAtTick = ctx.monsters.currentTick() + lifespan * 20L
            }
            Fx.burst(where.clone().add(0.0, 1.0, 0.0), particle, 20, 0.4)
        }
    }
}

/** Particles and sound only. The building block for telegraphs and flourishes. */
object VisualSkill : Skill {
    override val id = "visual"
    override val displayName = "연출"
    override val description = listOf("피해 없이 파티클과 효과음만 재생합니다.")
    override val icon = Material.FIREWORK_ROCKET
    override val defaultSelector = TargetSelector.SELF
    override val parameters = listOf(
        SkillParam.particle(),
        SkillParam.sound(),
        SkillParam.int("count", "파티클 개수", 40, 1, 2000),
        SkillParam.double("spread", "퍼지는 반경", 1.5, 0.0, 24.0),
        SkillParam.double("height", "높이 오프셋", 1.0, -4.0, 8.0),
        SkillParam.bool("ring", "바닥 원 그리기", false),
        SkillParam.double("ring-radius", "원 반경", 5.0, 0.5, 48.0),
    )

    override fun cast(ctx: SkillContext) {
        val particle = Fx.particle(ctx.params.string("particle"))
        val at = ctx.origin.clone().add(0.0, ctx.params.double("height"), 0.0)
        Fx.burst(at, particle, ctx.params.int("count"), ctx.params.double("spread"))
        if (ctx.params.bool("ring")) Fx.ring(ctx.origin, ctx.params.double("ring-radius"), particle)
        Fx.play(ctx.origin, ctx.params.string("sound"))
    }
}

/** Sends a message. Used for boss taunts and phase callouts. */
object BroadcastSkill : Skill {
    override val id = "broadcast"
    override val displayName = "메시지"
    override val description = listOf(
        "채팅·액션바·타이틀로 메시지를 보냅니다.",
        "{몬스터}, {플레이어네임} 등 치환자를 쓸 수 있습니다.",
    )
    override val icon = Material.PAPER
    override val defaultSelector = TargetSelector.ALL_PLAYERS_IN_RADIUS
    override val parameters = listOf(
        SkillParam.text("message", "내용", "<red>{몬스터}</red><gray>이(가) 포효한다!</gray>"),
        SkillParam.enum("style", "표시 방식", "CHAT", listOf("CHAT", "ACTIONBAR", "TITLE")),
        SkillParam.bool("server-wide", "서버 전체에 방송", false),
    )

    override fun cast(ctx: SkillContext) {
        val raw = ctx.params.string("message")
        if (raw.isBlank()) return

        val ph = Ph.of()
            .mob(ctx.caster.displayName)
            .level(ctx.caster.level)
            .phase(ctx.caster.phase?.name ?: "")
            .location(ctx.origin)

        val recipients: List<Player> = if (ctx.params.bool("server-wide")) {
            org.bukkit.Bukkit.getOnlinePlayers().toList()
        } else {
            ctx.players()
        }
        if (recipients.isEmpty()) return

        when (ctx.params.string("style").uppercase()) {
            "ACTIONBAR" -> recipients.forEach {
                it.sendActionBar(Text.render(raw, ph.copy().player(it), it))
            }

            "TITLE" -> recipients.forEach {
                it.showTitle(
                    net.kyori.adventure.title.Title.title(
                        Text.render(raw, ph.copy().player(it), it),
                        net.kyori.adventure.text.Component.empty(),
                    ),
                )
            }

            else -> recipients.forEach {
                it.sendMessage(Text.render(raw, ph.copy().player(it), it))
            }
        }
    }
}
