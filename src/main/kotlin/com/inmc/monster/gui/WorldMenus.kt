package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.config.WorldSettings
import com.inmc.monster.util.Numbers
import com.inmc.monster.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Per-world overrides.
 *
 * Lists the worlds the server actually has rather than only the ones with a file, because the
 * useful action is almost always "make this world different" for a world that has no file yet.
 */
class WorldListMenu(
    monsters: Monsters,
    private var page: Int = 0,
) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val worlds = Bukkit.getWorlds()
        val pages = maxOf(1, (worlds.size + CONTENT - 1) / CONTENT)
        page = page.coerceIn(0, pages - 1)

        worlds.drop(page * CONTENT).take(CONTENT).forEachIndexed { index, world ->
            val settings = monsters.worlds.of(world)
            set(index, iconFor(world, settings)) { event ->
                (event.whoClicked as? Player)?.let { WorldDetailMenu(monsters, settings.world, page).open(it) }
            }
        }

        for (slot in CONTENT until 54) set(slot, Icon.EDGE)

        set(45, Icon.back()) { event -> (event.whoClicked as? Player)?.let { MainMenu(monsters).open(it) } }
        if (page > 0) set(46, Icon.prevPage()) { event -> switch(event.whoClicked, page - 1) }
        if (page < pages - 1) set(47, Icon.nextPage()) { event -> switch(event.whoClicked, page + 1) }

        set(
            51,
            Icon.of(
                Material.PAPER, "<yellow>도움말</yellow>",
                "<gray>여기서 지정하지 않은 값은 항상</gray>",
                "<gray>전역 설정(config.yml)을 따릅니다.</gray>",
                "",
                "<dark_gray>월드별 파일은 값을 바꾼 뒤에 생성됩니다.</dark_gray>",
            ),
        )

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun iconFor(world: org.bukkit.World, settings: WorldSettings): org.bukkit.inventory.ItemStack {
        val live = monsters.tracker.countIn(world)
        return Icon.of(
            if (settings.enabled) Material.GRASS_BLOCK else Material.GRAY_DYE,
            "<yellow>" + world.name + "</yellow>",
            buildList {
                add("<gray>커스텀 몬스터: </gray>" + Icon.toggle(settings.enabled))
                add("<gray>현재 활동 중: <yellow>" + live + "마리</yellow> / " +
                    (if (settings.maxMobs > 0) settings.maxMobs else monsters.config.budget.perWorld) + "</gray>")
                add("<gray>치환 배율: <white>x" + Numbers.chance(settings.replacementMultiplier) + "</white>" +
                    "   수식어 배율: <white>x" + Numbers.chance(settings.affixMultiplier) + "</white></gray>")
                add("<gray>드랍 배율: <white>x" + Numbers.chance(settings.dropMultiplier) + "</white>" +
                    "   경험치 배율: <white>x" + Numbers.chance(settings.expMultiplier) + "</white></gray>")
                if (settings.touchesVanilla) {
                    add(
                        "<light_purple>바닐라 배율: 체력 x" + Numbers.chance(settings.vanillaHealthMultiplier) +
                            ", 피해 x" + Numbers.chance(settings.vanillaDamageMultiplier) + "</light_purple>",
                    )
                }
                if (settings.allowedMobs.isNotEmpty()) {
                    add("<aqua>허용 목록 " + settings.allowedMobs.size + "종만</aqua>")
                }
                if (settings.blockedMobs.isNotEmpty()) {
                    add("<red>차단 목록 " + settings.blockedMobs.size + "종</red>")
                }
                add("")
                add("<yellow>▶ 클릭</yellow>")
            },
        )
    }

    private fun switch(who: org.bukkit.entity.HumanEntity, target: Int) {
        (who as? Player)?.let { WorldListMenu(monsters, target).open(it) }
    }

    companion object {
        private const val CONTENT = 45
        private val TITLE = Text.renderFlat("<dark_gray>월드별 설정</dark_gray>")
    }
}

/**
 * One world's overrides.
 *
 * Reads settings fresh on every draw and writes a whole replacement through the registry,
 * because [WorldSettings] is immutable - the registry is the only thing that owns them, and
 * that is what keeps a stale menu from writing over a reload.
 */
class WorldDetailMenu(
    monsters: Monsters,
    private val worldName: String,
    private val returnPage: Int,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>월드 설정</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val settings = monsters.worlds.of(worldName)

        set(
            4,
            Icon.of(
                Material.GRASS_BLOCK, "<gold>" + worldName + "</gold>",
                "<gray>활동 중: <yellow>" +
                    (Bukkit.getWorld(worldName)?.let { monsters.tracker.countIn(it) } ?: 0) +
                    "마리</yellow></gray>",
            ),
        )

        set(
            19,
            Icon.of(
                if (settings.enabled) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>커스텀 몬스터 사용</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(settings.enabled),
                "<dark_gray>끄면 이 월드에서는 커스텀 몬스터가 전혀 생성되지 않습니다.</dark_gray>",
                "<dark_gray>바닐라 자연스폰은 그대로 동작합니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            update(settings.copy(enabled = !settings.enabled))
            redraw(event.whoClicked)
        }

        set(
            20,
            Editors.numberIcon(
                Material.COMPASS, "<yellow>치환 확률 배율</yellow>", settings.replacementMultiplier, "배",
                extra = listOf("<dark_gray>이 월드의 모든 치환 확률에 곱해집니다. 0 이면 치환 없음.</dark_gray>"),
                stepLabel = "0.1",
            ),
        ) { event ->
            update(
                settings.copy(
                    replacementMultiplier = (settings.replacementMultiplier + Editors.step(event, 0.1))
                        .coerceIn(0.0, 100.0),
                ),
            )
            redraw(event.whoClicked)
        }

        set(
            21,
            Editors.numberIcon(
                Material.NAME_TAG, "<yellow>수식어 확률 배율</yellow>", settings.affixMultiplier, "배",
                stepLabel = "0.1",
            ),
        ) { event ->
            update(
                settings.copy(
                    affixMultiplier = (settings.affixMultiplier + Editors.step(event, 0.1)).coerceIn(0.0, 100.0),
                ),
            )
            redraw(event.whoClicked)
        }

        set(
            22,
            Editors.numberIcon(
                Material.CHEST, "<yellow>드랍 확률 배율</yellow>", settings.dropMultiplier, "배",
                stepLabel = "0.1",
            ),
        ) { event ->
            update(
                settings.copy(
                    dropMultiplier = (settings.dropMultiplier + Editors.step(event, 0.1)).coerceIn(0.0, 100.0),
                ),
            )
            redraw(event.whoClicked)
        }

        set(
            23,
            Editors.numberIcon(
                Material.EXPERIENCE_BOTTLE, "<yellow>경험치 배율</yellow>", settings.expMultiplier, "배",
                stepLabel = "0.1",
            ),
        ) { event ->
            update(
                settings.copy(
                    expMultiplier = (settings.expMultiplier + Editors.step(event, 0.1)).coerceIn(0.0, 100.0),
                ),
            )
            redraw(event.whoClicked)
        }

        set(
            24,
            Editors.intIcon(
                Material.BARRIER, "<yellow>최대 커스텀 몬스터</yellow>", settings.maxMobs, "마리",
                extra = listOf(
                    "<dark_gray>0 이면 전역 설정(" + monsters.config.budget.perWorld + ")을 따릅니다.</dark_gray>",
                ),
                stepLabel = "10",
            ),
        ) { event ->
            update(
                settings.copy(
                    maxMobs = (settings.maxMobs + Editors.step(event, 10)).coerceAtLeast(0),
                ),
            )
            redraw(event.whoClicked)
        }

        set(
            28,
            Icon.of(
                if (settings.triggersEnabled) Material.OAK_SAPLING else Material.GRAY_DYE,
                "<yellow>등장 조건 사용</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(settings.triggersEnabled),
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            update(settings.copy(triggersEnabled = !settings.triggersEnabled))
            redraw(event.whoClicked)
        }

        set(
            29,
            Icon.of(
                if (settings.spawnersEnabled) Material.SPAWNER else Material.GRAY_DYE,
                "<yellow>스포너 사용</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(settings.spawnersEnabled),
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            update(settings.copy(spawnersEnabled = !settings.spawnersEnabled))
            redraw(event.whoClicked)
        }

        set(
            31,
            Icon.of(
                Material.BOOK, "<yellow>허용 몬스터 목록</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (settings.allowedMobs.isEmpty()) "전체 허용" else settings.allowedMobs.joinToString(", ")) +
                            "</white></gray>",
                    )
                    add("<dark_gray>지정하면 이 목록의 몬스터만 생성됩니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            promptList(event, "허용할 몬스터", settings.allowedMobs) { update(settings.copy(allowedMobs = it)) }
        }

        set(
            32,
            Icon.of(
                Material.RED_BANNER, "<yellow>차단 몬스터 목록</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (settings.blockedMobs.isEmpty()) "없음" else settings.blockedMobs.joinToString(", ")) +
                            "</white></gray>",
                    )
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            promptList(event, "차단할 몬스터", settings.blockedMobs) { update(settings.copy(blockedMobs = it)) }
        }

        set(
            37,
            Editors.numberIcon(
                Material.RED_DYE, "<yellow>바닐라 체력 배율</yellow>", settings.vanillaHealthMultiplier, "배",
                extra = listOf(
                    "<gray>이 월드의 평범한 바닐라 몬스터에 적용됩니다.</gray>",
                    "<red>⚠ 주민·골렘 등 모든 생명체에 걸립니다.</red>",
                    "<dark_gray>골렘 농장이나 주민 거래가 있는 월드에서는 주의하세요.</dark_gray>",
                ),
                stepLabel = "0.1",
            ),
        ) { event ->
            update(
                settings.copy(
                    vanillaHealthMultiplier = (settings.vanillaHealthMultiplier + Editors.step(event, 0.1))
                        .coerceIn(0.01, 100.0),
                ),
            )
            redraw(event.whoClicked)
        }

        set(
            38,
            Editors.numberIcon(
                Material.IRON_SWORD, "<yellow>바닐라 피해 배율</yellow>", settings.vanillaDamageMultiplier, "배",
                stepLabel = "0.1",
            ),
        ) { event ->
            update(
                settings.copy(
                    vanillaDamageMultiplier = (settings.vanillaDamageMultiplier + Editors.step(event, 0.1))
                        .coerceIn(0.01, 100.0),
                ),
            )
            redraw(event.whoClicked)
        }

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { WorldListMenu(monsters, returnPage).open(it) }
        }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun promptList(
        event: org.bukkit.event.inventory.InventoryClickEvent,
        label: String,
        current: Set<String>,
        setter: (Set<String>) -> Unit,
    ) {
        val player = event.whoClicked as? Player ?: return
        Editors.promptText(
            monsters, player, label + " 이름을 입력하세요. (쉼표로 여러 개)",
            listOf(
                "<gray>현재: " + (if (current.isEmpty()) "없음" else current.joinToString(", ")) + "</gray>",
                "<gray>'없음' 을 입력하면 목록을 지웁니다.</gray>",
            ),
            reopen = { WorldDetailMenu(monsters, worldName, returnPage).open(player) },
        ) { input ->
            setter(
                if (input.trim() == "없음") emptySet()
                else input.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
            )
        }
    }

    private fun update(updated: WorldSettings) = monsters.worlds.update(updated)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }
}
