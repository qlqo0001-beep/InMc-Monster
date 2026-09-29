package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.trigger.MessageStyle
import com.inmc.monster.trigger.SpawnAnchor
import com.inmc.monster.trigger.Trigger
import com.inmc.monster.trigger.TriggerRegistry
import com.inmc.monster.trigger.TriggerType
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * The "이럴 때 이 몬스터가 나온다" rules.
 *
 * The counting types are the interesting ones - "fell ten logs, then a 10% chance" is two gates
 * and both matter: the count makes it feel earned, the chance stops it being a metronome.
 */
class TriggerListMenu(
    monsters: Monsters,
    private var page: Int = 0,
) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val all = monsters.triggers.all()
        val pages = Paging.pageCount(all.size, CONTENT)
        page = page.coerceIn(0, pages - 1)

        Paging.slice(all, page, CONTENT).forEachIndexed { index, trigger ->
            set(index, iconFor(trigger)) { event ->
                val player = event.whoClicked as? Player ?: return@set
                when (event.click) {
                    ClickType.SHIFT_RIGHT -> ConfirmMenu(
                        monsters,
                        question = "<red>'" + trigger.id + "' 등장 조건을 삭제할까요?</red>",
                        detail = listOf("<gray>플레이어 진행도 기록도 함께 의미를 잃습니다.</gray>"),
                        onConfirm = {
                            monsters.triggers.delete(trigger.id)
                            TriggerListMenu(monsters, page).open(player)
                        },
                        onCancel = { TriggerListMenu(monsters, page).open(player) },
                    ).open(player)

                    // Q is unreliable on this server, so right-click does the same thing.
                    ClickType.DROP, ClickType.RIGHT -> {
                        trigger.enabled = !trigger.enabled
                        monsters.triggers.markDirty(trigger)
                        monsters.triggers.rebuildIndexes()
                        redraw(player)
                    }

                    else -> TriggerDetailMenu(monsters, trigger, page).open(player)
                }
            }
        }

        for (slot in CONTENT until 54) set(slot, Icon.EDGE)

        set(Paging.SLOT_BACK, Icon.back()) { event -> (event.whoClicked as? Player)?.let { MainMenu(monsters).open(it) } }
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { event -> switch(event.whoClicked, page - 1) }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { event -> switch(event.whoClicked, page + 1) }

        set(
            49,
            Icon.of(
                Material.OAK_SAPLING, "<green>+ 등장 조건 만들기</green>",
                "<gray>이름을 입력하면 기본값으로 생성됩니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "등장 조건 이름을 입력하세요.",
                listOf("<dark_gray>예: 나무꾼의저주, 비오는날의손님</dark_gray>"),
                reopen = { TriggerListMenu(monsters, page).open(player) },
            ) { input ->
                val name = input.trim()
                if (!TriggerRegistry.isValidId(name)) {
                    player.sendMessage(Text.render("<red>사용할 수 없는 이름입니다.</red>"))
                    return@promptText
                }
                val created = monsters.triggers.create(name)
                if (created == null) {
                    player.sendMessage(Text.render("<red>이미 존재하는 등장 조건입니다.</red>"))
                    return@promptText
                }
                TriggerDetailMenu(monsters, created, page).open(player)
            }
        }

        set(
            51,
            Icon.of(
                Material.PAPER, "<yellow>도움말</yellow>",
                "<gray>등록된 조건: <white>" + monsters.triggers.size + "개</white></gray>",
                "<gray>전역 활성화: </gray>" + Icon.toggle(monsters.config.triggers.enabled),
                "",
                "<yellow>좌클릭</yellow><gray> : 설정</gray>",
                "<yellow>우클릭</yellow><gray> : 켜기/끄기</gray>",
                "<red>Shift+우클릭</red><gray> : 삭제</gray>",
                "",
                "<dark_gray>쿨다운과 일일 제한이 없으면 나무를 심고 베기를</dark_gray>",
                "<dark_gray>반복해 무한히 뽑아낼 수 있습니다. 기본값을 그대로</dark_gray>",
                "<dark_gray>두는 편이 안전합니다.</dark_gray>",
            ),
        )

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun iconFor(trigger: Trigger): org.bukkit.inventory.ItemStack = Icon.of(
        if (trigger.enabled) materialFor(trigger.type) else Material.GRAY_DYE,
        "<yellow>" + trigger.id + "</yellow>",
        buildList {
            add("<gray>종류: <white>" + trigger.type.label + "</white></gray>")
            add("<dark_gray>" + trigger.type.description + "</dark_gray>")
            add("")
            if (trigger.type.countable && trigger.count > 1) {
                add("<gray>누적 <white>" + trigger.count + "회</white> → <yellow>" +
                    Numbers.chance(trigger.chance) + "%</yellow> 확률</gray>")
            } else {
                add("<gray>확률: <yellow>" + Numbers.chance(trigger.chance) + "%</yellow></gray>")
            }
            add("<gray>소환: <white>" + trigger.mobId.ifBlank { "(미지정)" } + "</white> x" + trigger.amount + "</gray>")
            add("<gray>쿨다운: <white>" + trigger.cooldownSeconds + "초</white>" +
                (if (trigger.dailyLimit > 0) "   일일 <white>" + trigger.dailyLimit + "회</white>" else "") +
                "</gray>")
            if (trigger.materials.isNotEmpty()) {
                add("<gray>대상 블록/아이템: <white>" + trigger.materials.size + "종</white></gray>")
            }
            if (trigger.entityTypes.isNotEmpty()) {
                add("<gray>대상 엔티티: <white>" + trigger.entityTypes.size + "종</white></gray>")
            }
            if (trigger.mobId.isBlank() || !monsters.mobs.exists(trigger.mobId)) {
                add("")
                add("<red>⚠ 소환할 몬스터가 지정되지 않았거나 존재하지 않습니다.</red>")
            }
            if (!trigger.enabled) {
                add("")
                add("<red>비활성화됨</red>")
            }
            add("")
            add("<yellow>▶ 좌클릭: 설정   우클릭: 켜기/끄기</yellow>")
            add("<red>▶ Shift+우클릭: 삭제</red>")
        },
    )

    private fun materialFor(type: TriggerType): Material = when (type) {
        TriggerType.BLOCK_BREAK -> Material.IRON_PICKAXE
        TriggerType.BLOCK_PLACE -> Material.BRICKS
        TriggerType.MOB_KILL -> Material.IRON_SWORD
        TriggerType.PLAYER_DEATH -> Material.SKELETON_SKULL
        TriggerType.DAMAGE_TAKEN -> Material.SHIELD
        TriggerType.FISH_CATCH -> Material.FISHING_ROD
        TriggerType.ITEM_CONSUME -> Material.COOKED_BEEF
        TriggerType.CRAFT -> Material.CRAFTING_TABLE
        TriggerType.ENCHANT -> Material.ENCHANTING_TABLE
        TriggerType.WEATHER_CHANGE -> Material.WATER_BUCKET
        TriggerType.TIME_REACH -> Material.CLOCK
        TriggerType.PLAYER_JOIN -> Material.PLAYER_HEAD
        TriggerType.INTERVAL -> Material.REPEATER
    }

    private fun switch(who: org.bukkit.entity.HumanEntity, target: Int) {
        (who as? Player)?.let { TriggerListMenu(monsters, target).open(it) }
    }

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    companion object {
        private const val CONTENT = 45
        private val TITLE = Text.renderFlat("<dark_gray>등장 조건</dark_gray>")
    }
}

/** One trigger: what to watch for, how often, and what appears. */
class TriggerDetailMenu(
    monsters: Monsters,
    private val trigger: Trigger,
    private val returnPage: Int,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>등장 조건 설정</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(
            4,
            Icon.of(
                Material.PAPER, "<gold>" + trigger.id + "</gold>",
                buildList {
                    add("<gray>" + summary() + "</gray>")
                    add("")
                    add("<dark_gray>" + trigger.type.description + "</dark_gray>")
                },
            ),
        )

        set(
            19,
            Icon.of(
                Material.REDSTONE_TORCH, "<yellow>발동 종류</yellow>",
                buildList {
                    add("<gray>현재: <white>" + trigger.type.label + "</white></gray>")
                    add("<dark_gray>" + trigger.type.description + "</dark_gray>")
                    add("")
                    addAll(Editors.optionList(TriggerType.entries.toList(), trigger.type) { it.label })
                    addAll(Editors.cycleHint)
                },
            ),
        ) { event ->
            trigger.type = Editors.cycle(event, TriggerType.entries.toList(), trigger.type)
            save(); redraw(event.whoClicked)
        }

        set(
            20,
            Editors.intIcon(
                Material.COMPARATOR, "<yellow>누적 횟수</yellow>", trigger.count, "회",
                extra = listOf(
                    "<gray>이만큼 반복해야 확률 판정이 일어납니다.</gray>",
                    "<dark_gray>1 이면 매번 굴립니다.</dark_gray>",
                    if (trigger.type.countable) "" else "<red>이 종류는 누적을 세지 않습니다.</red>",
                ).filter { it.isNotEmpty() },
            ),
        ) { event ->
            if (Editors.isPrompt(event)) {
                val player = event.whoClicked as? Player ?: return@set
                Editors.promptInt(monsters.prompts, player, "누적 횟수", 1, 100_000, { reopen(player) }) {
                    trigger.count = it
                    save()
                }
                return@set
            }
            trigger.count = (trigger.count + Editors.step(event, 1)).coerceIn(1, 100_000)
            save(); redraw(event.whoClicked)
        }

        set(
            21,
            Editors.numberIcon(
                Material.LIGHT_BLUE_DYE, "<yellow>등장 확률</yellow>", trigger.chance, "%",
                stepLabel = "5",
            ),
        ) { event ->
            if (Editors.isPrompt(event)) {
                val player = event.whoClicked as? Player ?: return@set
                Editors.promptDouble(monsters.prompts, player, "등장 확률", 0.01, 100.0, { reopen(player) }) {
                    trigger.chance = it
                    save()
                }
                return@set
            }
            trigger.chance = (trigger.chance + Editors.step(event, 5.0)).coerceIn(0.01, 100.0)
            save(); redraw(event.whoClicked)
        }

        set(
            22,
            Icon.of(
                if (trigger.resetCounter) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>도달 시 카운터 초기화</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(trigger.resetCounter),
                "<dark_gray>켜면 '10개마다', 끄면 '10개부터 매번' 이 됩니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            trigger.resetCounter = !trigger.resetCounter
            save(); redraw(event.whoClicked)
        }

        set(
            23,
            Icon.of(
                Material.IRON_PICKAXE, "<yellow>대상 블록 / 아이템</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (trigger.materials.isEmpty()) "전체" else trigger.materials.size.toString() + "종") +
                            "</white></gray>",
                    )
                    trigger.materials.take(6).forEach { add("<dark_gray> · " + it.name + "</dark_gray>") }
                    if (trigger.materials.size > 6) add("<dark_gray> · ...</dark_gray>")
                    add("")
                    add("<red>⚠ 비워두면 모든 블록/아이템에 반응합니다.</red>")
                    add("<dark_gray>서버의 모든 채굴 이벤트를 타므로 꼭 지정하세요.</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "대상 블록/아이템을 입력하세요. (쉼표로 여러 개)",
                listOf(
                    "<gray>예: <white>OAK_LOG, BIRCH_LOG, SPRUCE_LOG</white></gray>",
                    "<gray>'없음' 을 입력하면 제한을 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                val set = linkedSetOf<Material>()
                if (input.trim() != "없음") {
                    input.split(',').forEach { raw -> Material.matchMaterial(raw.trim())?.let { set.add(it) } }
                }
                trigger.materials = set
                monsters.triggers.rebuildIndexes()
                save()
            }
        }

        set(
            24,
            Icon.of(
                Material.IRON_SWORD, "<yellow>대상 엔티티</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (trigger.entityTypes.isEmpty()) "전체" else trigger.entityTypes.joinToString(", ") { it.name }) +
                            "</white></gray>",
                    )
                    add("<dark_gray>'몬스터 처치' 종류에서 사용합니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "대상 엔티티를 입력하세요. (쉼표로 여러 개)",
                listOf(
                    "<gray>예: <white>ZOMBIE, SKELETON</white></gray>",
                    "<gray>'없음' 을 입력하면 제한을 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                trigger.entityTypes = if (input.trim() == "없음") {
                    linkedSetOf()
                } else {
                    input.split(',').mapNotNull { raw ->
                        runCatching { EntityType.valueOf(raw.trim().uppercase()) }.getOrNull()
                    }.toCollection(linkedSetOf())
                }
                monsters.triggers.rebuildIndexes()
                save()
            }
        }

        set(
            28,
            Icon.of(
                Material.ZOMBIE_SPAWN_EGG, "<yellow>등장할 몬스터</yellow>",
                buildList {
                    add("<gray>현재: <white>" + trigger.mobId.ifBlank { "(미지정)" } + "</white></gray>")
                    add("<gray>마리 수: <white>" + trigger.amount + "</white></gray>")
                    if (trigger.mobId.isNotBlank() && !monsters.mobs.exists(trigger.mobId)) {
                        add("<red>⚠ 존재하지 않는 몬스터입니다.</red>")
                    }
                    add("")
                    add("<yellow>▶ 좌클릭: 몬스터 지정</yellow>")
                    add("<yellow>▶ 우클릭: 마리 수 +1 (Shift 로 -1)</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) {
                trigger.amount = (trigger.amount + if (event.isShiftClick) -1 else 1).coerceIn(1, 50)
                save(); redraw(player)
                return@set
            }
            Editors.promptText(
                monsters.prompts, player, "등장시킬 몬스터 이름을 입력하세요.",
                listOf("<gray>등록된 몬스터: " + monsters.mobs.ids().take(10).joinToString(", ") + "</gray>"),
                reopen = { reopen(player) },
            ) { input ->
                val name = input.trim()
                if (!monsters.mobs.exists(name)) {
                    player.sendMessage(Text.render("<red>'" + name + "' 몬스터를 찾을 수 없습니다.</red>"))
                    return@promptText
                }
                trigger.mobId = monsters.mobs.get(name)?.id ?: name
                save()
            }
        }

        set(
            29,
            Icon.of(
                Material.COMPASS, "<yellow>등장 위치</yellow>",
                buildList {
                    add("<gray>현재: <white>" + trigger.anchor.label + "</white></gray>")
                    add("<gray>흩어지는 반경: <white>" + Numbers.chance(trigger.spawnRadius) + "</white></gray>")
                    add("")
                    addAll(Editors.optionList(SpawnAnchor.entries.toList(), trigger.anchor) { it.label })
                    addAll(Editors.cycleHint)
                    add("<dark_gray>숫자키: 반경 입력</dark_gray>")
                },
            ),
        ) { event ->
            if (Editors.isPrompt(event)) {
                val player = event.whoClicked as? Player ?: return@set
                Editors.promptDouble(monsters.prompts, player, "흩어지는 반경", 0.0, 64.0, { reopen(player) }) {
                    trigger.spawnRadius = it
                    save()
                }
                return@set
            }
            trigger.anchor = Editors.cycle(event, SpawnAnchor.entries.toList(), trigger.anchor)
            save(); redraw(event.whoClicked)
        }

        set(
            30,
            Icon.of(
                Material.WRITABLE_BOOK, "<yellow>등장 메시지</yellow>",
                buildList {
                    add("<gray>현재: " + trigger.message.ifBlank { "<dark_gray>(없음)</dark_gray>" } + "</gray>")
                    add("<gray>표시 방식: <white>" + trigger.messageStyle.label + "</white></gray>")
                    add("<dark_gray>치환자: {몬스터} {플레이어네임} {좌표}</dark_gray>")
                    add("")
                    add("<yellow>▶ 좌클릭: 메시지 입력</yellow>")
                    add("<yellow>▶ 우클릭: 표시 방식 변경</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) {
                trigger.messageStyle =
                    Editors.cycle(event, MessageStyle.entries.toList(), trigger.messageStyle)
                save(); redraw(player)
                return@set
            }
            Editors.promptText(
                monsters.prompts, player, "등장할 때 보낼 메시지를 입력하세요.",
                listOf(
                    "<dark_gray>예: &c숲이 분노했다!</dark_gray>",
                    "<gray>'없음' 을 입력하면 메시지를 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                trigger.message = if (input.trim() == "없음") "" else input
                save()
            }
        }

        set(
            32,
            Editors.intIcon(
                Material.CLOCK, "<yellow>쿨다운</yellow>", trigger.cooldownSeconds, "초",
                extra = listOf(
                    "<gray>플레이어별로 적용됩니다.</gray>",
                    "<red>⚠ 0 으로 두면 반복 파밍이 가능해집니다.</red>",
                ),
                stepLabel = "30",
            ),
        ) { event ->
            trigger.cooldownSeconds = (trigger.cooldownSeconds + Editors.step(event, 30)).coerceAtLeast(0)
            save(); redraw(event.whoClicked)
        }

        set(
            33,
            Editors.intIcon(
                Material.BARRIER, "<yellow>일일 제한</yellow>", trigger.dailyLimit, "회",
                extra = listOf(
                    "<gray>플레이어당 하루 최대 발동 횟수입니다.</gray>",
                    "<dark_gray>0 이면 제한 없음.</dark_gray>",
                ),
            ),
        ) { event ->
            trigger.dailyLimit = (trigger.dailyLimit + Editors.step(event, 1)).coerceAtLeast(0)
            save(); redraw(event.whoClicked)
        }

        set(
            37,
            Icon.of(
                Material.GRASS_BLOCK, "<yellow>허용 월드</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (trigger.worlds.isEmpty()) "모든 월드" else trigger.worlds.joinToString(", ")) +
                            "</white></gray>",
                    )
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "허용할 월드를 입력하세요. (쉼표로 여러 개)",
                listOf("<gray>'없음' 을 입력하면 제한을 지웁니다.</gray>"),
                reopen = { reopen(player) },
            ) { input ->
                trigger.worlds = if (input.trim() == "없음") {
                    linkedSetOf()
                } else {
                    input.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toCollection(linkedSetOf())
                }
                save()
            }
        }

        set(
            38,
            Icon.of(
                Material.NAME_TAG, "<yellow>필요 권한</yellow>",
                "<gray>현재: <white>" + trigger.permission.ifBlank { "없음" } + "</white></gray>",
                "<dark_gray>이 권한이 있는 플레이어에게만 발동합니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "필요한 권한 노드를 입력하세요.",
                listOf("<gray>'없음' 을 입력하면 조건을 지웁니다.</gray>"),
                reopen = { reopen(player) },
            ) { input ->
                trigger.permission = if (input.trim() == "없음") "" else input.trim()
                save()
            }
        }

        set(
            39,
            Icon.of(
                Material.COMPASS, "<green>추가 등장 조건</green>",
                "<gray>시간·날씨·바이옴·Y좌표 등을 지정합니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            SpawnRulesMenu(
                monsters, trigger.rules, trigger.id,
                onSave = { save() },
                onBack = { TriggerDetailMenu(monsters, trigger, returnPage).open(it) },
            ).open(player)
        }

        if (trigger.type == TriggerType.INTERVAL) {
            set(
                41,
                Editors.intIcon(
                    Material.REPEATER, "<yellow>판정 주기</yellow>", trigger.intervalSeconds, "초",
                    stepLabel = "30",
                ),
            ) { event ->
                trigger.intervalSeconds =
                    (trigger.intervalSeconds + Editors.step(event, 30)).coerceIn(5, 86_400)
                save(); redraw(event.whoClicked)
            }
        }

        if (trigger.type == TriggerType.TIME_REACH) {
            set(
                41,
                Editors.intIcon(
                    Material.CLOCK, "<yellow>발동 시각</yellow>", trigger.atTime.toInt(), "틱",
                    extra = listOf("<dark_gray>0 = 아침, 13000 = 밤</dark_gray>"),
                    stepLabel = "1000",
                ),
            ) { event ->
                trigger.atTime = (trigger.atTime + Editors.step(event, 1000)).coerceIn(0L, 24000L)
                save(); redraw(event.whoClicked)
            }
        }

        set(
            43,
            Icon.of(
                if (trigger.enabled) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>조건 활성화</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(trigger.enabled),
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            trigger.enabled = !trigger.enabled
            monsters.triggers.rebuildIndexes()
            save(); redraw(event.whoClicked)
        }

        set(Paging.SLOT_BACK, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { TriggerListMenu(monsters, returnPage).open(it) }
        }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    /** One-line description of what this rule currently does, in plain Korean. */
    private fun summary(): String {
        val mob = trigger.mobId.ifBlank { "(미지정)" }
        val what = when (trigger.type) {
            TriggerType.BLOCK_BREAK ->
                (if (trigger.materials.isEmpty()) "아무 블록이나" else trigger.materials.size.toString() + "종의 블록을") +
                    " " + trigger.count + "개 캐면"

            TriggerType.MOB_KILL ->
                (if (trigger.entityTypes.isEmpty()) "아무 몬스터나" else trigger.entityTypes.size.toString() + "종을") +
                    " " + trigger.count + "마리 잡으면"

            TriggerType.WEATHER_CHANGE -> "비나 천둥이 시작되면"
            TriggerType.TIME_REACH -> "월드 시간이 " + trigger.atTime + " 이 되면"
            TriggerType.PLAYER_JOIN -> "접속하면"
            TriggerType.INTERVAL -> trigger.intervalSeconds.toString() + "초마다"
            else -> trigger.type.label + " 시"
        }
        return what + " " + Numbers.chance(trigger.chance) + "% 확률로 " + mob + " " + trigger.amount + "마리 등장"
    }

    private fun save() = monsters.triggers.markDirty(trigger)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = TriggerDetailMenu(monsters, trigger, returnPage).open(player)
}
