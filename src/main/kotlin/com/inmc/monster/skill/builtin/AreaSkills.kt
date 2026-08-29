package com.inmc.monster.skill.builtin

import com.inmc.monster.skill.Skill
import com.inmc.monster.skill.SkillContext
import com.inmc.monster.skill.SkillParam
import com.inmc.monster.skill.TargetSelector
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.potion.PotionEffect

/** Leaves a lingering cloud that keeps applying an effect. */
object PoisonCloud : Skill {
    override val id = "poison_cloud"
    override val displayName = "독구름"
    override val description = listOf(
        "지정 위치에 지속 효과 구름을 남깁니다.",
        "포션 효과는 자유롭게 바꿀 수 있습니다.",
    )
    override val icon = Material.LINGERING_POTION
    override val defaultSelector = TargetSelector.SELF
    override val parameters = listOf(
        SkillParam.potion("effect", "포션 효과", "POISON"),
        SkillParam.int("duration", "구름 지속 (틱)", 200, 20, 6000),
        SkillParam.int("effect-duration", "효과 지속 (틱)", 100, 20, 6000),
        SkillParam.int("amplifier", "효과 강도", 0, 0, 4),
        SkillParam.double("radius", "반경", 4.0, 1.0, 24.0),
        SkillParam.particle("particle", "파티클", "WITCH"),
    )

    override fun cast(ctx: SkillContext) {
        val centre = ctx.target?.location ?: ctx.origin
        val world = centre.world ?: return
        val type = Fx.potion(ctx.params.string("effect")) ?: return

        val chosen = Fx.particle(ctx.params.string("particle"), Particle.WITCH)

        val cloud = world.spawn(centre, AreaEffectCloud::class.java) { spawned ->
            spawned.duration = ctx.params.int("duration")
            spawned.radius = ctx.params.double("radius").toFloat()
            // The cloud renders its particle every tick for its whole lifetime, so a particle
            // that needs data has to be given it here - otherwise the failure repeats for
            // hundreds of ticks instead of once.
            val data = Fx.dataFor(chosen, centre)
            if (data == null) spawned.particle = chosen else spawned.setParticle(chosen, data)
            spawned.radiusPerTick = 0f
            spawned.addCustomEffect(
                PotionEffect(
                    type,
                    ctx.params.int("effect-duration"),
                    ctx.params.int("amplifier").coerceIn(0, 255),
                    false, true, true,
                ),
                true,
            )
            // Attribution matters: without a source the cloud's kills belong to nobody, and the
            // death handler cannot tell that the mob was responsible.
            spawned.source = ctx.entity
        }
        // Cloud lifetime is bounded by its own duration, so nothing here has to clean it up.
        if (!cloud.isValid) return
    }
}

/** Ring of frost: damage plus a heavy slow. */
object FrostNova : Skill {
    override val id = "frost_nova"
    override val displayName = "빙결"
    override val description = listOf(
        "주변에 냉기를 퍼뜨려 피해를 주고 이동 속도를 크게 낮춥니다.",
    )
    override val icon = Material.BLUE_ICE
    override val defaultSelector = TargetSelector.SELF
    override val parameters = listOf(
        SkillParam.double("damage", "피해량", 8.0, 0.0, 10_000.0),
        SkillParam.double("radius", "반경", 6.0, 1.0, 48.0),
        SkillParam.int("slow-duration", "둔화 지속 (틱)", 100, 0, 6000),
        SkillParam.int("slow-amplifier", "둔화 강도", 2, 0, 6),
        SkillParam.int("freeze-ticks", "빙결 시각 효과 (틱)", 100, 0, 600),
        SkillParam.particle("particle", "파티클", "SNOWFLAKE"),
        SkillParam.sound("sound", "효과음", "BLOCK_GLASS_BREAK"),
    )

    override fun cast(ctx: SkillContext) {
        val radius = ctx.params.double("radius")
        val particle = Fx.particle(ctx.params.string("particle"), Particle.SNOWFLAKE)

        Fx.ring(ctx.origin, radius, particle)
        Fx.ring(ctx.origin, radius * 0.6, particle, 24)
        Fx.burst(ctx.origin.clone().add(0.0, 1.0, 0.0), particle, 40, radius / 3.0)
        Fx.play(ctx.origin, ctx.params.string("sound"))

        val slowDuration = ctx.params.int("slow-duration")
        val freeze = ctx.params.int("freeze-ticks")

        for (victim in Act.enemiesNear(ctx, ctx.origin, radius)) {
            Act.damage(ctx, victim, ctx.params.double("damage"))
            if (slowDuration > 0) {
                Act.effect(victim, Fx.potion("SLOWNESS"), slowDuration, ctx.params.int("slow-amplifier"))
            }
            if (freeze > 0) victim.freezeTicks = maxOf(victim.freezeTicks, freeze)
        }
    }
}

/** Plain explosion, with block damage behind a switch. */
object ExplodeSkill : Skill {
    override val id = "explode"
    override val displayName = "폭발"
    override val description = listOf(
        "폭발을 일으킵니다.",
        "블록 파괴는 기본으로 꺼져 있습니다 - 켜면 지형이 실제로 파괴됩니다.",
    )
    override val icon = Material.TNT
    override val defaultSelector = TargetSelector.SELF
    override val parameters = listOf(
        SkillParam.double("damage", "피해량", 16.0, 0.0, 10_000.0),
        SkillParam.double("radius", "반경", 4.0, 0.5, 32.0),
        SkillParam.double("power", "폭발 연출 세기", 2.0, 0.0, 10.0),
        SkillParam.bool("break-blocks", "블록 파괴", false),
        SkillParam.bool("fire", "불 붙이기", false),
    )

    override fun cast(ctx: SkillContext) {
        val centre = ctx.origin
        val world = centre.world ?: return
        val radius = ctx.params.double("radius")

        if (ctx.params.bool("break-blocks")) {
            world.createExplosion(
                centre, ctx.params.double("power").toFloat(), ctx.params.bool("fire"), true, ctx.entity,
            )
        } else {
            // Effect-only: the visual and the sound of an explosion without touching the world.
            world.spawnParticle(Particle.EXPLOSION_EMITTER, centre, 1)
            Fx.play(centre, "ENTITY_GENERIC_EXPLODE")
            if (ctx.params.bool("fire")) {
                for (victim in Act.enemiesNear(ctx, centre, radius)) victim.fireTicks = maxOf(victim.fireTicks, 60)
            }
        }
        Act.damageAll(ctx, Act.enemiesNear(ctx, centre, radius), ctx.params.double("damage"))
    }
}

/**
 * Traps the target inside a temporary shell of blocks.
 *
 * The original blocks are snapshotted and restored, and the restore is also run on plugin
 * shutdown - a cage left behind by a server stop would be indistinguishable from griefing.
 */
object BlockCage : Skill {
    override val id = "block_cage"
    override val displayName = "블록 감옥"
    override val description = listOf(
        "대상 주위를 임시 블록으로 가둡니다.",
        "원래 블록은 저장해 두었다가 그대로 복구합니다.",
    )
    override val icon = Material.IRON_BARS
    override val parameters = listOf(
        SkillParam(
            "material", "가둘 블록", com.inmc.monster.skill.ParamType.MATERIAL, "IRON_BARS",
            description = "블록으로 배치할 수 있는 종류만 사용됩니다.",
        ),
        SkillParam.int("duration", "지속 시간 (틱)", 100, 20, 1200),
        SkillParam.int("radius", "반경 (블록)", 2, 1, 6),
        SkillParam.sound("sound", "효과음", "BLOCK_ANVIL_PLACE"),
    )

    override fun cast(ctx: SkillContext) {
        val target = ctx.target ?: return
        val material = ctx.params.material("material")?.takeIf { it.isBlock } ?: Material.IRON_BARS
        val radius = ctx.params.int("radius")
        val centre = target.location.block.location

        val restored = ArrayList<Pair<Location, org.bukkit.block.data.BlockData>>()

        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                for (dy in 0..radius) {
                    // Only the shell, so the target is enclosed rather than buried in a solid cube.
                    val edge = kotlin.math.abs(dx) == radius || kotlin.math.abs(dz) == radius || dy == radius
                    if (!edge) continue
                    val block = centre.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble()).block
                    if (!block.isPassable && block.type != Material.AIR) continue
                    restored.add(block.location to block.blockData)
                    block.type = material
                }
            }
        }
        if (restored.isEmpty()) return

        Fx.play(centre, ctx.params.string("sound"))
        CageRegistry.schedule(ctx, restored, ctx.params.int("duration").toLong())
    }
}

/**
 * Remembers cages so they are always undone.
 *
 * The scheduled restore covers the normal case; the shutdown sweep covers the one that actually
 * causes damage - a restart in the middle of a boss fight leaving iron bars around a player's
 * base with no record of where they came from.
 */
object CageRegistry {

    private val pending = java.util.concurrent.ConcurrentHashMap<
        Int, List<Pair<Location, org.bukkit.block.data.BlockData>>,
        >()

    private val counter = java.util.concurrent.atomic.AtomicInteger()

    fun schedule(
        ctx: SkillContext,
        blocks: List<Pair<Location, org.bukkit.block.data.BlockData>>,
        durationTicks: Long,
    ) {
        val id = counter.incrementAndGet()
        pending[id] = blocks
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            ctx.monsters.plugin,
            Runnable { restore(id) },
            durationTicks.coerceAtLeast(1L),
        )
    }

    private fun restore(id: Int) {
        val blocks = pending.remove(id) ?: return
        for ((location, data) in blocks) {
            // The chunk may have unloaded; touching it would force a load just to place air.
            if (!location.isChunkLoaded) continue
            location.block.blockData = data
        }
    }

    /** Called from onDisable so no cage outlives the plugin. */
    fun restoreAll() {
        for (id in pending.keys.toList()) restore(id)
        pending.clear()
    }
}
