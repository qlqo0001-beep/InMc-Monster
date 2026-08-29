package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.mob.Immunities
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.util.Numbers
import com.inmc.monster.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent.DamageCause

/**
 * What the mob shrugs off.
 *
 * Worth more to a boss fight than extra health. A boss that takes full knockback gets shoved
 * off the arena and dies to fall damage, ending the encounter in a way nobody designed; one
 * that resists knockback and ignores falling behaves the way the arena was built for.
 */
class ImmunityMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val immunities = definition.immunities

        set(
            4,
            Editors.numberIcon(
                Material.ANVIL, "<yellow>넉백 저항</yellow>", immunities.knockbackResistance * 100.0, "%",
                extra = listOf(
                    "<gray>100% 면 어떤 공격에도 밀리지 않습니다.</gray>",
                    "<dark_gray>보스가 절벽 밖으로 밀려나는 사고를 막습니다.</dark_gray>",
                    "<dark_gray>바닐라 KNOCKBACK_RESISTANCE 스탯과 별개로 적용됩니다.</dark_gray>",
                ),
                stepLabel = "10",
            ),
        ) { event ->
            val delta = Editors.step(event, 10.0) / 100.0
            immunities.knockbackResistance = (immunities.knockbackResistance + delta).coerceIn(0.0, 1.0)
            save(); redraw(event.whoClicked)
        }

        Immunities.SIMPLE_LABELS.forEachIndexed { index, (key, label) ->
            val slot = 19 + index + (index / 7) * 2
            set(slot, immunityIcon(key, label)) { event ->
                setImmune(key, !isImmune(key))
                save(); redraw(event.whoClicked)
            }
        }

        set(
            40,
            Icon.of(
                Material.MILK_BUCKET, "<yellow>포션 효과 면역</yellow>",
                buildList {
                    if (immunities.potions.isEmpty()) {
                        add("<gray>면역 포션이 없습니다.</gray>")
                    } else {
                        immunities.potions.forEach { add("<dark_gray> · " + it + "</dark_gray>") }
                    }
                    add("")
                    add("<gray>여기 적힌 효과는 이 몬스터에게 걸리지 않습니다.</gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters, player, "면역시킬 포션 효과를 입력하세요. (쉼표로 여러 개)",
                listOf(
                    "<gray>예: <white>POISON, WITHER, SLOWNESS</white></gray>",
                    "<gray>'없음' 을 입력하면 모두 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                immunities.potions = if (input.trim() == "없음") {
                    linkedSetOf()
                } else {
                    input.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                        .toCollection(linkedSetOf())
                }
                save()
            }
        }

        set(
            42,
            Icon.of(
                Material.STRUCTURE_VOID, "<yellow>피해 원인 면역 (고급)</yellow>",
                buildList {
                    if (immunities.damageCauses.isEmpty()) {
                        add("<gray>설정된 원인이 없습니다.</gray>")
                    } else {
                        immunities.damageCauses.forEach { add("<dark_gray> · " + it.name + "</dark_gray>") }
                    }
                    add("")
                    add("<gray>위 토글로 표현되지 않는 원인을 직접 지정합니다.</gray>")
                    add("<dark_gray>예: CRAMMING, STARVATION, FLY_INTO_WALL</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters, player, "면역시킬 피해 원인을 입력하세요. (쉼표로 여러 개)",
                listOf(
                    "<gray>예: <white>CRAMMING, STARVATION, FLY_INTO_WALL</white></gray>",
                    "<gray>'없음' 을 입력하면 모두 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                val causes = java.util.EnumSet.noneOf(DamageCause::class.java)
                if (input.trim() != "없음") {
                    input.split(',').forEach { raw ->
                        runCatching { DamageCause.valueOf(raw.trim().uppercase()) }
                            .getOrNull()?.let { causes.add(it) }
                    }
                }
                immunities.damageCauses = causes
                save()
            }
        }

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { MobManageMenu(monsters, definition).open(it) }
        }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun immunityIcon(key: String, label: String) = Icon.of(
        if (isImmune(key)) materialFor(key) else Material.GRAY_DYE,
        "<yellow>" + label + " 면역</yellow>",
        listOf(
            "<gray>현재: </gray>" + Icon.toggle(isImmune(key)),
            "",
            "<yellow>▶ 클릭하여 전환</yellow>",
        ),
    )

    private fun materialFor(key: String): Material = when (key) {
        "fire" -> Material.FIRE_CHARGE
        "fall" -> Material.FEATHER
        "drowning" -> Material.WATER_BUCKET
        "suffocation" -> Material.SAND
        "explosion" -> Material.TNT
        "projectile" -> Material.ARROW
        "magic" -> Material.POTION
        "wither" -> Material.WITHER_SKELETON_SKULL
        "lightning" -> Material.LIGHTNING_ROD
        "void" -> Material.END_PORTAL_FRAME
        else -> Material.PAPER
    }

    private fun isImmune(key: String): Boolean {
        val immunities = definition.immunities
        return when (key) {
            "fire" -> immunities.fire
            "fall" -> immunities.fall
            "drowning" -> immunities.drowning
            "suffocation" -> immunities.suffocation
            "explosion" -> immunities.explosion
            "projectile" -> immunities.projectile
            "magic" -> immunities.magic
            "wither" -> immunities.wither
            "lightning" -> immunities.lightning
            "void" -> immunities.voidDamage
            else -> false
        }
    }

    private fun setImmune(key: String, value: Boolean) {
        val immunities = definition.immunities
        when (key) {
            "fire" -> immunities.fire = value
            "fall" -> immunities.fall = value
            "drowning" -> immunities.drowning = value
            "suffocation" -> immunities.suffocation = value
            "explosion" -> immunities.explosion = value
            "projectile" -> immunities.projectile = value
            "magic" -> immunities.magic = value
            "wither" -> immunities.wither = value
            "lightning" -> immunities.lightning = value
            "void" -> immunities.voidDamage = value
        }
    }

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = ImmunityMenu(monsters, definition).open(player)

    companion object {
        private val TITLE = Text.renderFlat("<dark_gray>면역 / 저항</dark_gray>")
    }
}
