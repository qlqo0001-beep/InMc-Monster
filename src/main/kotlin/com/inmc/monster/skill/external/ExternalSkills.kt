package com.inmc.monster.skill.external

import com.inmc.monster.Monsters
import com.inmc.monster.skill.Skill
import com.inmc.monster.skill.SkillContext
import com.inmc.monster.skill.SkillParam
import com.inmc.monster.skill.TargetSelector
import com.inmc.monster.util.Ph
import com.inmc.monster.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material

/**
 * Casts a skill that lives in MythicMobs.
 *
 * The point is reuse: a server that already has a MythicMobs skill library should be able to
 * point a mob at those skills rather than rebuilding them here. Registered even when MythicMobs
 * is absent so a configuration written on a server that had it does not silently lose the skill
 * - the GUI shows it greyed out with the reason instead.
 */
class MythicMobsSkill(private val monsters: Monsters) : Skill {
    override val id = "mythicmobs"
    override val displayName = "MythicMobs 스킬"
    override val description = listOf(
        "MythicMobs 에 등록된 스킬을 이름으로 호출합니다.",
        "MythicMobs 가 설치되어 있어야 동작합니다.",
    )
    override val icon = Material.WITHER_SKELETON_SKULL
    override val defaultSelector = TargetSelector.SELF
    override val parameters = listOf(
        SkillParam.text("skill", "MythicMobs 스킬 이름", ""),
    )

    override fun cast(ctx: SkillContext) {
        if (!monsters.mythicMobs.isEnabled) return
        val name = ctx.params.string("skill")
        if (name.isBlank()) return
        monsters.mythicMobs.cast(ctx.entity, name)
    }
}

/**
 * Casts a spell that lives in MagicSpells.
 *
 * Same rationale and the same degradation as [MythicMobsSkill].
 */
class MagicSpellsSkill(private val monsters: Monsters) : Skill {
    override val id = "magicspells"
    override val displayName = "MagicSpells 스펠"
    override val description = listOf(
        "MagicSpells 에 정의된 스펠을 이름으로 시전합니다.",
        "MagicSpells 가 설치되어 있어야 동작합니다.",
    )
    override val icon = Material.ENCHANTED_BOOK
    override val defaultSelector = TargetSelector.SELF
    override val parameters = listOf(
        SkillParam.text("spell", "스펠 내부 이름", ""),
    )

    override fun cast(ctx: SkillContext) {
        if (!monsters.magicSpells.isEnabled) return
        val name = ctx.params.string("spell")
        if (name.isBlank()) return
        monsters.magicSpells.cast(ctx.entity, name)
    }
}

/**
 * Runs a console command.
 *
 * The escape hatch that connects this plugin to everything else on the server - Skript, a
 * quest plugin, a custom command - without needing a bridge per plugin. Runs from the console
 * on purpose: a mob is not a player and has no permissions of its own, and giving a skill the
 * killer's permissions would let a mob act with an operator's authority.
 */
class CommandSkill(private val monsters: Monsters) : Skill {
    override val id = "command"
    override val displayName = "명령어 실행"
    override val description = listOf(
        "콘솔에서 명령어를 실행합니다.",
        "{플레이어네임}, {몬스터}, {좌표} 등 치환자를 쓸 수 있습니다.",
        "여러 개는 | 로 구분합니다.",
    )
    override val icon = Material.COMMAND_BLOCK
    override val defaultSelector = TargetSelector.TARGET
    override val parameters = listOf(
        SkillParam.text("command", "명령어", "say {몬스터} 등장!"),
        SkillParam.bool("per-target", "대상마다 한 번씩 실행", false),
    )

    override fun cast(ctx: SkillContext) {
        val raw = ctx.params.string("command")
        if (raw.isBlank()) return
        val commands = raw.split('|').map { it.trim().removePrefix("/") }.filter { it.isNotEmpty() }
        if (commands.isEmpty()) return

        val base = Ph.of()
            .mob(ctx.caster.displayName)
            .mobId(ctx.caster.definition?.id ?: "")
            .level(ctx.caster.level)
            .location(ctx.origin)

        if (ctx.params.bool("per-target") && ctx.targets.isNotEmpty()) {
            for (target in ctx.targets) {
                val ph = base.copy().player((target as? org.bukkit.entity.Player)?.name ?: target.name)
                commands.forEach { dispatch(it, ph) }
            }
            return
        }

        val ph = base.copy().player(
            (ctx.target as? org.bukkit.entity.Player)?.name ?: ctx.target?.name ?: "",
        )
        commands.forEach { dispatch(it, ph) }
    }

    private fun dispatch(command: String, ph: Ph) {
        val resolved = Text.plain(ph.apply(command))
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)
        } catch (t: Throwable) {
            monsters.logger.warning("스킬 명령어 실행 실패 (" + resolved + "): " + t.message)
        }
    }
}
