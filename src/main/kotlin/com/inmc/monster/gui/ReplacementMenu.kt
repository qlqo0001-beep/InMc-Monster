package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.mob.MobDefinition
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player

/**
 * How this mob takes over vanilla spawns.
 *
 * The screen leads with the two numbers that decide whether it appears at all, and says plainly
 * what the global reason filter is doing - an admin who sets 100% chance and sees nothing needs
 * to be told that only NATURAL spawns are eligible, not left guessing.
 */
class ReplacementMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val settings = definition.replacement

        set(
            4,
            Icon.of(
                Material.PAPER, "<yellow>지금 이 설정이 하는 일</yellow>",
                buildList {
                    if (!settings.enabled) {
                        add("<red>치환이 꺼져 있어 자연스폰으로는 등장하지 않습니다.</red>")
                    } else {
                        val effective = settings.chance * monsters.config.replacement.chanceMultiplier
                        add(
                            "<gray>" + typeList() + " 의 자연스폰 중 <yellow>" +
                                Numbers.chance(effective) + "%</yellow> 가</gray>",
                        )
                        add("<gray>이 몬스터로 바뀝니다.</gray>")
                        if (monsters.config.replacement.chanceMultiplier != 1.0) {
                            add(
                                "<dark_gray>(설정값 " + Numbers.chance(settings.chance) +
                                    "% × 전역 배율 " + Numbers.chance(monsters.config.replacement.chanceMultiplier) + ")</dark_gray>",
                            )
                        }
                    }
                    add("")
                    add(
                        "<gray>치환 대상 스폰 사유: <white>" +
                            monsters.config.replacement.allowedReasons.joinToString(", ") { it.name } +
                            "</white></gray>",
                    )
                    add("<dark_gray>여기 없는 사유(스포너·스폰에그·번식 등)로 생긴</dark_gray>")
                    add("<dark_gray>몬스터는 절대 치환되지 않습니다. config.yml 에서</dark_gray>")
                    add("<dark_gray>바꿀 수 있지만, 기본값에는 이유가 있습니다.</dark_gray>")
                },
            ),
        )

        set(
            19,
            Icon.of(
                if (settings.enabled) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>자연스폰 치환</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(settings.enabled),
                "<dark_gray>끄면 스포너·명령어·던전으로만 등장합니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            settings.enabled = !settings.enabled
            monsters.mobs.rebuildReplacementIndex()
            save(); redraw(event.whoClicked)
        }

        set(
            20,
            Editors.numberIcon(
                Material.LIGHT_BLUE_DYE, "<yellow>치환 확률</yellow>", settings.chance, "%",
                extra = listOf(
                    "<dark_gray>대상 종류의 자연스폰 한 건마다 굴립니다.</dark_gray>",
                    "<red>⚠ 값이 크면 필드가 커스텀 몬스터로 뒤덮입니다.</red>",
                ),
                stepLabel = "1",
            ),
        ) { event ->
            if (Editors.isPrompt(event)) {
                val player = event.whoClicked as? Player ?: return@set
                Editors.promptDouble(monsters.prompts, player, "치환 확률", 0.0, 100.0, { reopen(player) }) {
                    settings.chance = it
                    monsters.mobs.rebuildReplacementIndex()
                    save()
                }
                return@set
            }
            settings.chance = (settings.chance + Editors.step(event, 1.0)).coerceIn(0.0, 100.0)
            monsters.mobs.rebuildReplacementIndex()
            save(); redraw(event.whoClicked)
        }

        set(
            21,
            Editors.intIcon(
                Material.COMPARATOR, "<yellow>경쟁 가중치</yellow>", settings.weight,
                extra = listOf(
                    "<dark_gray>같은 스폰을 여러 몬스터가 노릴 때</dark_gray>",
                    "<dark_gray>누가 가져갈지를 정하는 비율입니다.</dark_gray>",
                ),
            ),
        ) { event ->
            settings.weight = (settings.weight + Editors.step(event, 1)).coerceIn(1, 10_000)
            save(); redraw(event.whoClicked)
        }

        set(
            23,
            Icon.of(
                Material.ZOMBIE_SPAWN_EGG, "<yellow>치환할 엔티티 종류</yellow>",
                buildList {
                    add("<gray>현재: <white>" + typeList() + "</white></gray>")
                    add("")
                    add("<gray>비워두면 이 몬스터와 같은 종류만 가로챕니다.</gray>")
                    add("<dark_gray>같은 종류일 때는 서버가 만든 엔티티를 그대로</dark_gray>")
                    add("<dark_gray>꾸미기 때문에 더 가볍고 안전합니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "치환할 엔티티 종류를 입력하세요. (쉼표로 여러 개)",
                listOf(
                    "<gray>예: <white>ZOMBIE, HUSK, DROWNED</white></gray>",
                    "<gray>'없음' 을 입력하면 자기 종류만 치환합니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                settings.replaces = if (input.trim() == "없음") {
                    linkedSetOf()
                } else {
                    input.split(',').mapNotNull { raw ->
                        runCatching { EntityType.valueOf(raw.trim().uppercase()) }.getOrNull()
                    }.toCollection(linkedSetOf())
                }
                monsters.mobs.rebuildReplacementIndex()
                save()
            }
        }

        set(
            31,
            Icon.of(
                Material.COMPASS, "<green>등장 조건 설정</green>",
                "<gray>시간·날씨·바이옴·Y좌표·지역 등</gray>",
                "<gray>어디서 나타날지 정합니다.</gray>",
                "<dark_gray>확률 판정을 통과한 뒤에만 검사하므로</dark_gray>",
                "<dark_gray>조건이 많아도 비용은 거의 없습니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            SpawnRulesMenu(
                monsters, settings.rules, definition.id,
                onSave = { save() },
                onBack = { ReplacementMenu(monsters, definition).open(it) },
            ).open(player)
        }

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { MobManageMenu(monsters, definition).open(it) }
        }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun typeList(): String {
        val settings = definition.replacement
        return if (settings.replaces.isEmpty()) definition.entityType.name
        else settings.replaces.joinToString(", ") { it.name }
    }

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = ReplacementMenu(monsters, definition).open(player)

    companion object {
        private val TITLE = Text.renderFlat("<dark_gray>자연 스폰 설정</dark_gray>")
    }
}
