package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.util.Numbers
import com.inmc.monster.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * What the plugin is actually doing right now.
 *
 * Built around the question an admin asks when something is wrong: "why is nothing spawning?"
 * The refusal counters answer it directly - a full budget, a disabled world and a failing spawn
 * rule all look identical from in-game otherwise.
 */
class StatusMenu(monsters: Monsters) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(
            4,
            Icon.of(
                Material.CLOCK, "<gold>현황</gold>",
                buildList {
                    add("<gray>정의된 몬스터: <white>" + monsters.mobs.size + "종</white></gray>")
                    add("<gray>수식어: <white>" + monsters.affixes.size + "종</white>   스킬: <white>" + monsters.skills.registry.size + "종</white></gray>")
                    add("<gray>스포너: <white>" + monsters.spawners.size + "</white>   등장 조건: <white>" + monsters.triggers.size + "</white></gray>")
                    add("<gray>리스폰 대기: <white>" + monsters.respawns.size + "건</white></gray>")
                },
            ),
        )

        set(
            19,
            Icon.of(
                Material.ZOMBIE_HEAD, "<yellow>활성 개체</yellow>",
                buildList {
                    add(
                        "<gray>전체: <yellow>" + monsters.tracker.size + "</yellow> / " +
                            monsters.config.budget.global + "</gray>",
                    )
                    add(
                        "<gray>모델 몬스터: <yellow>" + monsters.tracker.modelledCount + "</yellow> / " +
                            monsters.config.budget.modelled + "</gray>",
                    )
                    add("")
                    for (world in Bukkit.getWorlds()) {
                        val count = monsters.tracker.countIn(world)
                        if (count == 0) continue
                        val settings = monsters.worlds.of(world)
                        val limit = if (settings.maxMobs > 0) settings.maxMobs else monsters.config.budget.perWorld
                        add("<dark_gray> · " + world.name + ": " + count + " / " + limit + "</dark_gray>")
                    }
                },
            ),
        )

        val refusals = monsters.budget.refusalCounts()
        set(
            21,
            Icon.of(
                if (refusals.isEmpty()) Material.LIME_DYE else Material.BARRIER,
                "<yellow>스폰 거부 사유</yellow>",
                buildList {
                    if (refusals.isEmpty()) {
                        add("<green>거부된 스폰이 없습니다.</green>")
                    } else {
                        add("<gray>마지막 초기화 이후 누적입니다.</gray>")
                        add("")
                        refusals.entries.sortedByDescending { it.value }.forEach { (verdict, count) ->
                            add("<gray>" + verdict.label + ": <yellow>" + count + "회</yellow></gray>")
                        }
                        add("")
                        add("<dark_gray>몬스터가 안 나온다면 여기부터 보세요.</dark_gray>")
                    }
                    add("")
                    add("<yellow>▶ Shift+클릭: 집계 초기화</yellow>")
                },
            ),
        ) { event ->
            if (!event.isShiftClick) return@set
            monsters.budget.resetCounters()
            redraw(event.whoClicked)
        }

        set(
            23,
            Icon.of(
                Material.COMPARATOR, "<yellow>치환 설정</yellow>",
                buildList {
                    add("<gray>치환 사용: </gray>" + Icon.toggle(monsters.config.replacement.enabled))
                    add("<gray>전역 확률 배율: <white>x" +
                        Numbers.chance(monsters.config.replacement.chanceMultiplier) + "</white></gray>")
                    add("")
                    add("<gray>치환 대상 스폰 사유:</gray>")
                    monsters.config.replacement.allowedReasons.forEach {
                        add("<dark_gray> · " + it.name + "</dark_gray>")
                    }
                    add("")
                    add("<dark_gray>여기 없는 사유로 생긴 몬스터는 치환되지 않습니다.</dark_gray>")
                    add("<dark_gray>스포너·스폰에그·번식이 기본 제외인 이유입니다.</dark_gray>")
                },
            ),
        )

        set(
            25,
            Icon.of(
                Material.COMMAND_BLOCK, "<yellow>연동 상태</yellow>",
                buildList {
                    add(line("MythicLib", monsters.mythicLib.isEnabled, "MMOItems 장비 스탯 적용"))
                    add(line("MMOItems", monsters.mmoItems.isEnabled, "아이템 참조"))
                    add(line("ItemsAdder 계열", monsters.customItems.isEnabled, "커스텀 아이템"))
                    add(line("모델 (" + monsters.models.providerName + ")", monsters.models.isEnabled, "몬스터 모델 표시"))
                    add(line("MythicMobs", monsters.mythicMobs.isEnabled, "외부 스킬 호출"))
                    add(line("MagicSpells", monsters.magicSpells.isEnabled, "외부 스펠 호출"))
                    add(line("Vault", monsters.economy.isEnabled, "재화 지급"))
                    add(line("WorldGuard / Lands", monsters.regions.isEnabled, "지역 조건"))
                    add(line("PlaceholderAPI", monsters.papi.isEnabled, "%monster_...%"))
                },
            ),
        )

        set(
            30,
            Icon.of(
                Material.TNT, "<red>커스텀 몬스터 전부 제거</red>",
                "<gray>지금 살아 있는 커스텀 몬스터를 모두 지웁니다.</gray>",
                "<gray>현재: <yellow>" + monsters.tracker.size + "마리</yellow></gray>",
                "<dark_gray>설정은 그대로이며 스포너가 다시 채웁니다.</dark_gray>",
                "",
                "<red>▶ Shift+클릭</red>",
            ),
        ) { event ->
            if (!event.isShiftClick) return@set
            val player = event.whoClicked as? Player ?: return@set
            val removed = monsters.removeAllTracked()
            monsters.messages.send(player, "cleanup-done", com.inmc.monster.util.Ph.of().count(removed))
            redraw(player)
        }

        set(
            32,
            Icon.of(
                Material.SPYGLASS, "<yellow>디버그 모드</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(monsters.config.debug),
                "<dark_gray>스폰 판정 결과를 콘솔에 출력합니다.</dark_gray>",
                "<dark_gray>config.yml 의 debug 값으로 바꾼 뒤 리로드하세요.</dark_gray>",
            ),
        )

        set(45, Icon.back()) { event -> (event.whoClicked as? Player)?.let { MainMenu(monsters).open(it) } }

        set(
            49,
            Icon.of(
                Material.LIME_DYE, "<green>새로고침</green>",
                "<gray>지금 값으로 다시 표시합니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event -> redraw(event.whoClicked) }

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun line(name: String, enabled: Boolean, purpose: String): String =
        if (enabled) "<green>✔ " + name + "</green> <dark_gray>- " + purpose + "</dark_gray>"
        else "<dark_gray>✖ " + name + " - " + purpose + "</dark_gray>"

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    companion object {
        private val TITLE = Text.renderFlat("<dark_gray>현황 / 진단</dark_gray>")
    }
}
