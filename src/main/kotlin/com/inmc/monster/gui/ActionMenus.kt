package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.death.DeathAction
import com.inmc.monster.death.DeathActionType
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.skill.ParamType
import com.inmc.monster.skill.SkillParam
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * A list of actions - used for both a mob's death events and a phase's entrance events.
 *
 * The two are the same shape, so they share one editor. [onBack] and [title] are what differ.
 */
class ActionListMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
    private val actions: MutableList<DeathAction>,
    private val label: String,
    private val onBack: (Player) -> Unit,
    private var page: Int = 0,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>" + label + "</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val pages = Paging.pageCount(actions.size, CONTENT)
        page = page.coerceIn(0, pages - 1)

        Paging.slice(actions, page, CONTENT).forEachIndexed { index, action ->
            set(index, iconFor(action)) { event ->
                val player = event.whoClicked as? Player ?: return@set
                when (event.click) {
                    ClickType.SHIFT_RIGHT -> {
                        actions.remove(action)
                        save()
                        reopen(player)
                    }

                    // Q is unreliable on this server, so right-click does the same thing.
                    ClickType.DROP, ClickType.RIGHT -> {
                        action.enabled = !action.enabled
                        save(); redraw(player)
                    }

                    else -> ActionDetailMenu(
                        monsters, definition, action, label,
                        onBack = { reopen(it) },
                    ).open(player)
                }
            }
        }

        for (slot in CONTENT until 54) set(slot, Icon.EDGE)

        set(Paging.SLOT_BACK, Icon.back()) { event -> (event.whoClicked as? Player)?.let(onBack) }
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { event -> switch(event.whoClicked, page - 1) }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { event -> switch(event.whoClicked, page + 1) }

        set(
            49,
            Icon.of(
                Material.WRITABLE_BOOK, "<green>+ 동작 추가</green>",
                "<gray>추가할 동작 종류를 고릅니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            (event.whoClicked as? Player)?.let {
                ActionPickerMenu(monsters, definition, actions, label, onBack = { p -> reopen(p) }).open(it)
            }
        }

        set(
            51,
            Icon.of(
                Material.PAPER, "<yellow>도움말</yellow>",
                "<gray>등록된 동작: <white>" + actions.size + "개</white></gray>",
                "",
                "<yellow>좌클릭</yellow><gray> : 설정</gray>",
                "<yellow>우클릭</yellow><gray> : 켜기/끄기</gray>",
                "<red>Shift+우클릭</red><gray> : 삭제</gray>",
                "",
                "<dark_gray>연쇄 소환은 깊이 " + monsters.config.maxSpawnGeneration + " 까지만 허용됩니다.</dark_gray>",
                "<dark_gray>A가 B를, B가 A를 소환하는 설정은 여기서 끊깁니다.</dark_gray>",
            ),
        )

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun iconFor(action: DeathAction): org.bukkit.inventory.ItemStack {
        val params = action.params()
        val lore = mutableListOf<String>()
        lore.add("<gray>" + action.type.description + "</gray>")
        lore.add("")
        lore.add("<gray>확률: <white>" + Numbers.chance(action.chance) + "%</white></gray>")
        lore.add("<gray>플레이어 처치 필요: </gray>" + Icon.toggle(action.requirePlayerKill))

        // A one-line preview of what this action will actually do, so the list is readable
        // without opening every entry.
        when (action.type) {
            DeathActionType.SPAWN_MOB ->
                lore.add("<aqua>→ " + params.string("mob").ifBlank { "(미지정)" } + " " + params.int("amount") + "마리</aqua>")

            DeathActionType.RUN_COMMAND ->
                lore.add("<aqua>→ /" + params.string("command").take(28) + "</aqua>")

            DeathActionType.BROADCAST ->
                lore.add("<aqua>→ " + Text.plain(Text.render(params.string("message"))).take(28) + "</aqua>")

            DeathActionType.GIVE_MONEY ->
                lore.add("<aqua>→ " + Numbers.money(params.double("amount")) + "</aqua>")

            DeathActionType.GIVE_EXP ->
                lore.add("<aqua>→ 경험치 " + params.int("amount") + "</aqua>")

            DeathActionType.RESPAWN_TIMER ->
                lore.add("<aqua>→ " + params.int("seconds") + "초 뒤 리스폰</aqua>")

            else -> Unit
        }

        if (action.worlds.isNotEmpty()) {
            lore.add("<dark_gray>월드: " + action.worlds.joinToString(", ") + "</dark_gray>")
        }
        if (action.killerPermission.isNotBlank()) {
            lore.add("<dark_gray>권한: " + action.killerPermission + "</dark_gray>")
        }
        if (!action.enabled) {
            lore.add("")
            lore.add("<red>비활성화됨</red>")
        }
        lore.add("")
        lore.add("<yellow>▶ 좌클릭: 설정   우클릭: 켜기/끄기</yellow>")
        lore.add("<red>▶ Shift+우클릭: 삭제</red>")

        return Icon.of(
            if (action.enabled) action.type.icon else Material.GRAY_DYE,
            (if (action.enabled) "<yellow>" else "<dark_gray>") + action.type.label +
                (if (action.enabled) "</yellow>" else "</dark_gray>"),
            lore,
        )
    }

    private fun switch(who: org.bukkit.entity.HumanEntity, target: Int) {
        (who as? Player)?.let {
            ActionListMenu(monsters, definition, actions, label, onBack, target).open(it)
        }
    }

    private fun reopen(player: Player) =
        ActionListMenu(monsters, definition, actions, label, onBack, page).open(player)

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private companion object {
        const val CONTENT = 45
    }
}

/** Chooses which kind of action to add. */
class ActionPickerMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
    private val actions: MutableList<DeathAction>,
    private val label: String,
    private val onBack: (Player) -> Unit,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>동작 선택</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        DeathActionType.entries.forEachIndexed { index, type ->
            set(
                index,
                Icon.of(
                    type.icon, "<yellow>" + type.label + "</yellow>",
                    buildList {
                        add("<gray>" + type.description + "</gray>")
                        add("")
                        add("<gray>설정 항목: <white>" + type.parameters.size + "개</white></gray>")
                        if (type == DeathActionType.GIVE_MONEY && !monsters.economy.isEnabled) {
                            add("")
                            add("<red>⚠ Vault 가 없어 지급되지 않습니다.</red>")
                        }
                        add("")
                        add("<yellow>▶ 클릭하여 추가</yellow>")
                    },
                ),
            ) { event ->
                val player = event.whoClicked as? Player ?: return@set
                val action = DeathAction(type)
                actions.add(action)
                monsters.mobs.markDirty(definition)
                ActionDetailMenu(
                    monsters, definition, action, label,
                    onBack = { onBack(it) },
                ).open(player)
            }
        }

        set(Paging.SLOT_BACK, Icon.back()) { event -> (event.whoClicked as? Player)?.let(onBack) }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }
}

/**
 * One action's conditions and values.
 *
 * The value controls are generated from [DeathActionType.parameters], the same declaration the
 * executor reads - so a new action type needs no menu code at all.
 */
class ActionDetailMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
    private val action: DeathAction,
    private val label: String,
    private val onBack: (Player) -> Unit,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>동작 설정</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(
            4,
            Icon.of(
                action.type.icon, "<gold>" + action.type.label + "</gold>",
                "<gray>" + action.type.description + "</gray>",
                "<dark_gray>" + action.type.name + "</dark_gray>",
            ),
        )

        set(
            19,
            Editors.numberIcon(
                Material.LIGHT_BLUE_DYE, "<yellow>발동 확률</yellow>", action.chance, "%",
                stepLabel = "5",
            ),
        ) { event ->
            if (Editors.isPrompt(event)) {
                val player = event.whoClicked as? Player ?: return@set
                Editors.promptDouble(monsters.prompts, player, "발동 확률", 0.01, 100.0, { reopen(player) }) {
                    action.chance = it
                    save()
                }
                return@set
            }
            action.chance = (action.chance + Editors.step(event, 5.0)).coerceIn(0.01, 100.0)
            save(); redraw(event.whoClicked)
        }

        set(
            20,
            Icon.of(
                if (action.requirePlayerKill) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>플레이어 처치 필요</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(action.requirePlayerKill),
                "<dark_gray>끄면 용암·다른 몬스터에게 죽어도 실행됩니다.</dark_gray>",
                "<red>⚠ 보상성 동작이라면 켜 두세요. 함정 파밍이 가능해집니다.</red>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            action.requirePlayerKill = !action.requirePlayerKill
            save(); redraw(event.whoClicked)
        }

        set(
            21,
            Icon.of(
                Material.GRASS_BLOCK, "<yellow>허용 월드</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (action.worlds.isEmpty()) "모든 월드" else action.worlds.joinToString(", ")) +
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
                action.worlds = if (input.trim() == "없음") {
                    linkedSetOf()
                } else {
                    input.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toCollection(linkedSetOf())
                }
                save()
            }
        }

        set(
            22,
            Icon.of(
                Material.NAME_TAG, "<yellow>처치자 권한 조건</yellow>",
                buildList {
                    add("<gray>현재: <white>" + action.killerPermission.ifBlank { "없음" } + "</white></gray>")
                    add("<dark_gray>이 권한을 가진 플레이어가 처치했을 때만 실행됩니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "필요한 권한 노드를 입력하세요.",
                listOf("<gray>'없음' 을 입력하면 조건을 지웁니다.</gray>"),
                reopen = { reopen(player) },
            ) { input ->
                action.killerPermission = if (input.trim() == "없음") "" else input.trim()
                save()
            }
        }

        set(
            24,
            Icon.of(
                if (action.enabled) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>동작 활성화</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(action.enabled),
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            action.enabled = !action.enabled
            save(); redraw(event.whoClicked)
        }

        // --- generated value controls -------------------------------------------
        action.type.parameters.forEachIndexed { index, param ->
            if (index >= 9) return@forEachIndexed
            set(28 + index + (index / 7) * 2, paramIcon(param)) { event -> handle(event, param) }
        }

        set(Paging.SLOT_BACK, Icon.back()) { event -> (event.whoClicked as? Player)?.let(onBack) }

        set(
            53,
            Icon.of(
                Material.TNT, "<red>이 동작 삭제</red>",
                "<gray>목록에서 제거합니다.</gray>",
                "",
                "<red>▶ Shift+클릭</red>",
            ),
        ) { event ->
            if (!event.isShiftClick) return@set
            val player = event.whoClicked as? Player ?: return@set
            definition.deathActions.remove(action)
            definition.phases.forEach { it.onEnter.remove(action) }
            save()
            onBack(player)
        }
    }

    private fun current(param: SkillParam): Any = action.values[param.key] ?: param.default

    private fun paramIcon(param: SkillParam): org.bukkit.inventory.ItemStack {
        val value = current(param)
        val isDefault = !action.values.containsKey(param.key)
        val lore = mutableListOf<String>()
        lore.add(
            "<gray>현재: <yellow>" + display(value) + "</yellow>" +
                (if (isDefault) " <dark_gray>(기본값)</dark_gray>" else "") + "</gray>",
        )
        if (param.description.isNotBlank()) lore.add("<dark_gray>" + param.description + "</dark_gray>")
        lore.add("<dark_gray>" + param.key + " · " + param.type.label + "</dark_gray>")

        when (param.type) {
            ParamType.DOUBLE, ParamType.INT -> {
                lore.add("<dark_gray>범위: " + Numbers.chance(param.min) + " ~ " + Numbers.chance(param.max) + "</dark_gray>")
                lore.add("")
                lore.add("<yellow>▶ 좌클릭 +1 / 우클릭 -1   Shift 로 10배</yellow>")
                lore.add("<yellow>▶ 숫자키: 직접 입력</yellow>")
            }

            ParamType.BOOL -> {
                lore.add("")
                lore.add("<yellow>▶ 클릭하여 전환</yellow>")
            }

            ParamType.ENUM -> {
                lore.add("")
                if (param.options.size >= Editors.PICK_FROM) {
                    lore.addAll(Editors.pickHint.drop(1))
                } else {
                    param.options.forEach { option ->
                        val marker = if (option == value.toString()) "<green>▶</green>" else "<dark_gray>·</dark_gray>"
                        lore.add(marker + " <dark_gray>" + option + "</dark_gray>")
                    }
                    lore.addAll(Editors.cycleHint)
                }
            }

            else -> {
                lore.add("")
                lore.add("<yellow>▶ 클릭하여 입력</yellow>")
            }
        }
        return Icon.of(materialFor(param), "<yellow>" + param.label + "</yellow>", lore)
    }

    private fun handle(event: org.bukkit.event.inventory.InventoryClickEvent, param: SkillParam) {
        val player = event.whoClicked as? Player ?: return
        when (param.type) {
            ParamType.BOOL -> {
                action.values[param.key] = !(current(param) as? Boolean ?: false)
                save(); redraw(player)
            }

            ParamType.INT -> {
                if (Editors.isPrompt(event)) {
                    Editors.promptInt(
                        monsters.prompts, player, param.label, param.min.toInt(), param.max.toInt(), { reopen(player) },
                    ) { action.values[param.key] = it; save() }
                    return
                }
                val value = (current(param) as? Number)?.toInt() ?: 0
                action.values[param.key] =
                    (value + Editors.step(event, 1)).coerceIn(param.min.toInt(), param.max.toInt())
                save(); redraw(player)
            }

            ParamType.DOUBLE -> {
                if (Editors.isPrompt(event)) {
                    Editors.promptDouble(
                        monsters.prompts, player, param.label, param.min, param.max, { reopen(player) },
                    ) { action.values[param.key] = it; save() }
                    return
                }
                val value = (current(param) as? Number)?.toDouble() ?: 0.0
                action.values[param.key] = (value + Editors.step(event, 1.0)).coerceIn(param.min, param.max)
                save(); redraw(player)
            }

            ParamType.ENUM -> {
                val value = current(param).toString()
                if (param.options.size >= Editors.PICK_FROM) {
                    // 보기가 많은 열거 인자는 고르는 화면으로(2026-10-08).
                    kr.inmc.core.gui.PickMenu(
                        monsters, player, param.label + " 고르기", param.options,
                        icon = { Icon.of(if (it == value) Material.LIME_DYE else Material.GRAY_DYE, "<yellow>$it</yellow>") },
                        back = { reopen(player) },
                    ) { picked ->
                        action.values[param.key] = picked
                        save(); reopen(player)
                    }.show()
                    return
                }
                action.values[param.key] = Editors.cycle(event, param.options, value)
                save(); redraw(player)
            }

            else -> Editors.promptText(
                monsters.prompts, player, param.label + " 값을 입력하세요.",
                hintsFor(param),
                reopen = { reopen(player) },
            ) { input ->
                action.values[param.key] = input.trim()
                save()
            }
        }
    }

    private fun hintsFor(param: SkillParam): List<String> = when (param.type) {
        ParamType.PARTICLE -> listOf("<gray>예: <white>FLAME, SOUL, EXPLOSION</white></gray>")
        ParamType.SOUND -> listOf("<gray>예: <white>ENTITY_GENERIC_EXPLODE</white></gray>")
        ParamType.POTION -> listOf("<gray>예: <white>SLOWNESS, POISON, WITHER</white></gray>")
        ParamType.MOB_ID -> listOf(
            "<gray>등록된 몬스터: " + monsters.mobs.ids().take(8).joinToString(", ") + "</gray>",
        )

        else -> listOf("<dark_gray>치환자: {몬스터} {플레이어네임} {좌표} {레벨}</dark_gray>")
    }

    private fun materialFor(param: SkillParam): Material = when (param.type) {
        ParamType.DOUBLE, ParamType.INT -> Material.LIGHT_BLUE_DYE
        ParamType.BOOL -> if (current(param) == true) Material.LIME_DYE else Material.GRAY_DYE
        ParamType.ENUM -> Material.COMPARATOR
        ParamType.PARTICLE -> Material.FIREWORK_STAR
        ParamType.SOUND -> Material.NOTE_BLOCK
        ParamType.POTION -> Material.POTION
        ParamType.MOB_ID -> Material.ZOMBIE_SPAWN_EGG
        ParamType.MATERIAL -> Material.STONE
        ParamType.STRING -> Material.WRITABLE_BOOK
    }

    private fun display(value: Any): String = when (value) {
        is Boolean -> if (value) "켜짐" else "꺼짐"
        is Double -> Numbers.chance(value)
        is Number -> value.toString()
        else -> value.toString().ifBlank { "(비어 있음)" }
    }

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) =
        ActionDetailMenu(monsters, definition, action, label, onBack).open(player)
}
