package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.mob.StatKeys
import com.inmc.monster.mob.StatUnit
import com.inmc.monster.util.Numbers
import com.inmc.monster.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Stat editor, paged across the vanilla attributes and the plugin's own stats.
 *
 * The two kinds are kept on separate pages rather than mixed, because they behave differently in
 * a way that matters: a vanilla stat is written into the entity and enforced by the server, and
 * a custom stat only means something because our combat code reads it. Mixing them invites the
 * assumption that adding CRITICAL_STRIKE_CHANCE to a mob with no skills and no melee does
 * something.
 */
class StatMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
    private var customPage: Boolean = false,
) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        if (customPage) drawCustom() else drawVanilla()

        set(
            45,
            Icon.of(Material.ARROW, "<gray>◀ 몬스터 설정으로</gray>"),
        ) { event -> (event.whoClicked as? Player)?.let { MobManageMenu(monsters, definition).open(it) } }

        set(
            49,
            Icon.of(
                if (customPage) Material.NETHER_STAR else Material.IRON_SWORD,
                if (customPage) "<light_purple>커스텀 스탯</light_purple>" else "<yellow>기본 스탯</yellow>",
                if (customPage) {
                    listOf(
                        "<gray>치명타·관통·회피처럼 바닐라에 없는 값입니다.</gray>",
                        "<gray>이 플러그인의 전투 계산에서만 쓰입니다.</gray>",
                        "<dark_gray>MMOItems 장비의 같은 이름 스탯과 합산됩니다.</dark_gray>",
                        "",
                        "<yellow>▶ 클릭하여 기본 스탯 보기</yellow>",
                    )
                } else {
                    listOf(
                        "<gray>바닐라 속성입니다. 소환할 때 엔티티에</gray>",
                        "<gray>직접 기록되며 서버가 그대로 적용합니다.</gray>",
                        "",
                        "<yellow>▶ 클릭하여 커스텀 스탯 보기</yellow>",
                    )
                },
            ),
        ) { event ->
            (event.whoClicked as? Player)?.let { StatMenu(monsters, definition, !customPage).open(it) }
        }

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun drawVanilla() {
        val keys = StatKeys.VANILLA.keys.toList()
        keys.take(CONTENT).forEachIndexed { index, key ->
            val current = definition.stats[key]
            val step = stepFor(key)

            set(
                index,
                Icon.of(
                    materialFor(key),
                    "<yellow>" + StatKeys.vanillaLabel(key) + "</yellow>",
                    buildList {
                        if (current == null) {
                            add("<gray>설정되지 않음 <dark_gray>(바닐라 기본값 사용)</dark_gray></gray>")
                        } else {
                            add("<gray>현재: <yellow>" + Numbers.chance(current) + "</yellow></gray>")
                        }
                        add("<dark_gray>" + key + "</dark_gray>")
                        add("")
                        add("<yellow>▶ 좌클릭 +" + Numbers.chance(step) + "  /  우클릭 -" + Numbers.chance(step) + "</yellow>")
                        add("<yellow>▶ Shift 로 10배   숫자키: 직접 입력</yellow>")
                        add("<red>▶ Shift+우클릭: 설정 해제</red>")
                    },
                ),
            ) { event ->
                val player = event.whoClicked as? Player
                if (Editors.isPrompt(event)) {
                    player?.let {
                        Editors.promptDouble(
                            monsters, it, StatKeys.vanillaLabel(key), 0.0, 100_000.0, { reopen(it) },
                        ) { value ->
                            definition.stats[key] = value
                            save()
                        }
                    }
                    return@set
                }
                if (event.isShiftClick && event.isRightClick) {
                    definition.stats.remove(key)
                    save(); redraw(event.whoClicked)
                    return@set
                }
                val base = current ?: 0.0
                definition.stats[key] = (base + Editors.step(event, step)).coerceAtLeast(0.0)
                save(); redraw(event.whoClicked)
            }
        }
    }

    private fun drawCustom() {
        StatKeys.CUSTOM.take(CONTENT).forEachIndexed { index, stat ->
            val current = definition.customStats[stat.key]
            val unit = if (stat.unit == StatUnit.PERCENT) "%" else ""

            set(
                index,
                Icon.of(
                    customMaterial(stat.key),
                    "<light_purple>" + stat.label + "</light_purple>",
                    buildList {
                        if (current == null) {
                            add("<gray>설정되지 않음 <dark_gray>(0 으로 취급)</dark_gray></gray>")
                        } else {
                            add("<gray>현재: <yellow>" + Numbers.chance(current) + unit + "</yellow></gray>")
                        }
                        add("<dark_gray>" + stat.key + "</dark_gray>")
                        add("<dark_gray>범위: " + Numbers.chance(stat.min) + " ~ " + Numbers.chance(stat.max) + "</dark_gray>")
                        if (monsters.mythicLib.isEnabled) {
                            add("<dark_gray>장비의 동일 스탯과 합산됩니다.</dark_gray>")
                        }
                        add("")
                        add("<yellow>▶ 좌클릭 +1  /  우클릭 -1   Shift 로 10배</yellow>")
                        add("<yellow>▶ 숫자키: 직접 입력</yellow>")
                        add("<red>▶ Shift+우클릭: 설정 해제</red>")
                    },
                ),
            ) { event ->
                val player = event.whoClicked as? Player
                if (Editors.isPrompt(event)) {
                    player?.let {
                        Editors.promptDouble(monsters, it, stat.label, stat.min, stat.max, { reopen(it) }) { value ->
                            definition.customStats[stat.key] = value
                            save()
                        }
                    }
                    return@set
                }
                if (event.isShiftClick && event.isRightClick) {
                    definition.customStats.remove(stat.key)
                    save(); redraw(event.whoClicked)
                    return@set
                }
                val base = current ?: stat.default
                definition.customStats[stat.key] =
                    (base + Editors.step(event, 1.0)).coerceIn(stat.min, stat.max)
                save(); redraw(event.whoClicked)
            }
        }
    }

    /** Nudge size per stat, so movement speed does not jump in whole blocks per tick. */
    private fun stepFor(key: String): Double = when (key) {
        "MOVEMENT_SPEED", "FLYING_SPEED" -> 0.01
        "KNOCKBACK_RESISTANCE", "GRAVITY" -> 0.05
        "SCALE", "ATTACK_SPEED", "JUMP_STRENGTH", "STEP_HEIGHT" -> 0.1
        "MAX_HEALTH", "FOLLOW_RANGE" -> 5.0
        else -> 1.0
    }

    private fun materialFor(key: String): Material = when (key) {
        "MAX_HEALTH" -> Material.RED_DYE
        "ATTACK_DAMAGE" -> Material.IRON_SWORD
        "MOVEMENT_SPEED", "FLYING_SPEED" -> Material.SUGAR
        "ARMOR" -> Material.IRON_CHESTPLATE
        "ARMOR_TOUGHNESS" -> Material.NETHERITE_SCRAP
        "KNOCKBACK_RESISTANCE" -> Material.ANVIL
        "ATTACK_KNOCKBACK" -> Material.PISTON
        "ATTACK_SPEED" -> Material.CLOCK
        "FOLLOW_RANGE" -> Material.SPYGLASS
        "SCALE" -> Material.SLIME_BALL
        "MAX_ABSORPTION" -> Material.GOLDEN_APPLE
        "JUMP_STRENGTH" -> Material.RABBIT_FOOT
        "GRAVITY" -> Material.FEATHER
        else -> Material.PAPER
    }

    private fun customMaterial(key: String): Material = when (key) {
        StatKeys.CRITICAL_STRIKE_CHANCE, StatKeys.CRITICAL_STRIKE_POWER -> Material.DIAMOND_SWORD
        StatKeys.ARMOR_PENETRATION -> Material.NETHERITE_PICKAXE
        StatKeys.LIFESTEAL -> Material.GHAST_TEAR
        StatKeys.DODGE_RATING -> Material.FEATHER
        StatKeys.DAMAGE_REDUCTION -> Material.SHIELD
        StatKeys.THORNS -> Material.CACTUS
        StatKeys.HEALTH_REGENERATION -> Material.GLISTERING_MELON_SLICE
        StatKeys.SKILL_POWER -> Material.BLAZE_POWDER
        StatKeys.COOLDOWN_REDUCTION -> Material.CLOCK
        StatKeys.EXP_MULTIPLIER -> Material.EXPERIENCE_BOTTLE
        StatKeys.DROP_MULTIPLIER -> Material.CHEST
        else -> Material.NETHER_STAR
    }

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = StatMenu(monsters, definition, customPage).open(player)

    companion object {
        private const val CONTENT = 45
        private val TITLE = Text.renderFlat("<dark_gray>스탯 설정</dark_gray>")
    }
}
