package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.mob.StatMap
import com.inmc.monster.pattern.Phase
import com.inmc.monster.pattern.PatternStep
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * A mob's health-band phases.
 *
 * Shown sorted by threshold, highest first, which is the order they are entered in - so the
 * list reads like the fight does.
 */
class PhaseListMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val sorted = definition.phases.sortedByDescending { it.healthAbove }
        sorted.take(CONTENT).forEachIndexed { index, phase ->
            set(index, iconFor(phase)) { event ->
                val player = event.whoClicked as? Player ?: return@set
                when (event.click) {
                    ClickType.SHIFT_RIGHT -> {
                        definition.phases.remove(phase)
                        save()
                        reopen(player)
                    }

                    else -> PhaseDetailMenu(monsters, definition, phase).open(player)
                }
            }
        }

        for (slot in CONTENT until 54) set(slot, Icon.EDGE)

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { MobManageMenu(monsters, definition).open(it) }
        }

        set(
            49,
            Icon.of(
                Material.HEART_OF_THE_SEA, "<green>+ 페이즈 추가</green>",
                "<gray>체력 구간마다 다른 행동을 줍니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "페이즈 이름을 입력하세요.",
                listOf("<dark_gray>예: 1페이즈, 광폭, 최후의발악</dark_gray>"),
                reopen = { reopen(player) },
            ) { input ->
                val name = input.trim()
                if (name.isBlank() || definition.phases.any { it.name == name }) return@promptText
                definition.phases.add(Phase(name))
                save()
            }
        }

        set(
            51,
            Icon.of(
                Material.PAPER, "<yellow>페이즈가 하는 일</yellow>",
                "<gray>등록된 페이즈: <white>" + definition.phases.size + "개</white></gray>",
                "",
                "<gray>체력이 각 페이즈의 기준선 아래로 내려가면</gray>",
                "<gray>해당 페이즈로 넘어갑니다.</gray>",
                "<dark_gray>한 번 넘어간 페이즈는 되돌아가지 않습니다 -</dark_gray>",
                "<dark_gray>회복할 때마다 등장 연출이 반복되면 전투가</dark_gray>",
                "<dark_gray>망가지기 때문입니다.</dark_gray>",
                "",
                "<yellow>좌클릭</yellow><gray> : 설정</gray>",
                "<red>Shift+우클릭</red><gray> : 삭제</gray>",
            ),
        )

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun iconFor(phase: Phase): org.bukkit.inventory.ItemStack = Icon.of(
        if (phase.hasPattern) Material.REPEATING_COMMAND_BLOCK else Material.HEART_OF_THE_SEA,
        "<yellow>" + phase.name + "</yellow>",
        buildList {
            add("<gray>진입 체력: <white>" + Numbers.chance(phase.healthAbove) + "% 이상</white></gray>")
            add("<gray>패턴 스텝: <white>" + phase.steps.size + "개</white>" +
                (if (phase.loop) " <dark_gray>(반복)</dark_gray>" else "") + "</gray>")
            add("<gray>진입 동작: <white>" + phase.onEnter.size + "개</white></gray>")
            add("<gray>스킬 위력: <white>x" + Numbers.chance(phase.skillPower) + "</white></gray>")
            if (!phase.statMultipliers.isEmpty()) {
                add("<gray>스탯 배율:</gray>")
                phase.statMultipliers.asMap().forEach { (key, value) ->
                    add("<dark_gray> · " + key + " x" + Numbers.chance(value) + "</dark_gray>")
                }
            }
            if (phase.model.isNotBlank()) add("<aqua>모델 교체: " + phase.model + "</aqua>")
            add("")
            add("<yellow>▶ 좌클릭: 설정</yellow>")
            add("<red>▶ Shift+우클릭: 삭제</red>")
        },
    )

    private fun reopen(player: Player) = PhaseListMenu(monsters, definition).open(player)

    private fun save() = monsters.mobs.markDirty(definition)

    companion object {
        private const val CONTENT = 45
        private val TITLE = Text.renderFlat("<dark_gray>패턴 / 페이즈</dark_gray>")
    }
}

/** One phase: threshold, multipliers, entrance actions and its pattern. */
class PhaseDetailMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
    private val phase: Phase,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>페이즈 설정</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(4, Icon.of(Material.HEART_OF_THE_SEA, "<gold>" + phase.name + "</gold>"))

        set(
            19,
            Editors.numberIcon(
                Material.RED_DYE, "<yellow>진입 체력</yellow>", phase.healthAbove, "%",
                extra = listOf(
                    "<gray>체력이 이 값 이상일 때 이 페이즈가 활성화됩니다.</gray>",
                    "<dark_gray>가장 낮은 페이즈는 0 으로 두어야 마지막까지</dark_gray>",
                    "<dark_gray>빈 구간이 생기지 않습니다.</dark_gray>",
                ),
                stepLabel = "5",
            ),
        ) { event ->
            if (Editors.isPrompt(event)) {
                val player = event.whoClicked as? Player ?: return@set
                Editors.promptDouble(monsters.prompts, player, "진입 체력", 0.0, 100.0, { reopen(player) }) {
                    phase.healthAbove = it
                    save()
                }
                return@set
            }
            phase.healthAbove = (phase.healthAbove + Editors.step(event, 5.0)).coerceIn(0.0, 100.0)
            save(); redraw(event.whoClicked)
        }

        set(
            20,
            Editors.numberIcon(
                Material.BLAZE_POWDER, "<yellow>스킬 위력 배율</yellow>", phase.skillPower, "배",
                extra = listOf("<dark_gray>이 페이즈에서 시전하는 모든 스킬 피해에 곱해집니다.</dark_gray>"),
                stepLabel = "0.1",
            ),
        ) { event ->
            phase.skillPower = (phase.skillPower + Editors.step(event, 0.1)).coerceIn(0.0, 100.0)
            save(); redraw(event.whoClicked)
        }

        set(
            21,
            Icon.of(
                Material.ANVIL, "<yellow>스탯 배율</yellow>",
                buildList {
                    if (phase.statMultipliers.isEmpty()) {
                        add("<gray>설정된 배율이 없습니다.</gray>")
                    } else {
                        phase.statMultipliers.asMap().forEach { (key, value) ->
                            add("<gray>" + key + ": <white>x" + Numbers.chance(value) + "</white></gray>")
                        }
                    }
                    add("")
                    add("<gray>이 페이즈에 진입할 때 한 번 적용됩니다.</gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "스탯과 배율을 입력하세요.",
                listOf(
                    "<gray>형식: <white>스탯이름 배율</white>  (여러 개는 | 로 구분)</gray>",
                    "<gray>예: <white>ATTACK_DAMAGE 1.25|MOVEMENT_SPEED 1.2</white></gray>",
                    "<gray>'없음' 을 입력하면 모두 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                if (input.trim() == "없음") {
                    phase.statMultipliers = StatMap()
                    save()
                    return@promptText
                }
                val map = StatMap()
                for (part in input.split('|')) {
                    val bits = part.trim().split(' ', limit = 2)
                    if (bits.size != 2) continue
                    val value = bits[1].trim().toDoubleOrNull() ?: continue
                    map[bits[0].trim()] = value
                }
                phase.statMultipliers = map
                save()
            }
        }

        set(
            22,
            Icon.of(
                if (monsters.models.isEnabled) Material.ARMOR_STAND else Material.BARRIER,
                "<yellow>모델 교체</yellow>",
                buildList {
                    add("<gray>현재: <white>" + phase.model.ifBlank { "교체 안 함" } + "</white></gray>")
                    add("<dark_gray>페이즈에 들어설 때 ModelEngine 모델을 바꿉니다.</dark_gray>")
                    if (!monsters.models.isEnabled) {
                        add("<red>ModelEngine 이 설치되어 있지 않습니다.</red>")
                    }
                    add("")
                    add("<yellow>▶ 좌클릭: 입력   우클릭: 제거</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) {
                phase.model = ""
                save(); redraw(player)
                return@set
            }
            Editors.promptText(
                monsters.prompts, player, "교체할 모델 ID 를 입력하세요.",
                listOf("<dark_gray>예: corrupted_knight_rage</dark_gray>"),
                reopen = { reopen(player) },
            ) { input ->
                phase.model = input.trim()
                save()
            }
        }

        set(
            24,
            Icon.of(
                if (phase.loop) Material.REPEATING_COMMAND_BLOCK else Material.COMMAND_BLOCK,
                "<yellow>패턴 반복</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(phase.loop),
                "<dark_gray>끄면 스텝을 한 바퀴만 돌고 멈춥니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            phase.loop = !phase.loop
            save(); redraw(event.whoClicked)
        }

        set(
            30,
            Icon.of(
                Material.REPEATING_COMMAND_BLOCK, "<green>패턴 스텝 편집</green>",
                "<gray>등록된 스텝: <white>" + phase.steps.size + "개</white></gray>",
                "<gray>'대기 후 시전' 을 순서대로 늘어놓아</gray>",
                "<gray>플레이어가 외울 수 있는 리듬을 만듭니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            (event.whoClicked as? Player)?.let { PatternMenu(monsters, definition, phase).open(it) }
        }

        set(
            32,
            Icon.of(
                Material.FIREWORK_ROCKET, "<green>진입 동작 편집</green>",
                "<gray>등록된 동작: <white>" + phase.onEnter.size + "개</white></gray>",
                "<gray>페이즈에 들어서는 순간 한 번 실행됩니다.</gray>",
                "<dark_gray>방송·연출·소환 등 사망 이벤트와 같은 종류입니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            (event.whoClicked as? Player)?.let { player ->
                ActionListMenu(
                    monsters, definition, phase.onEnter, "진입 동작 | " + phase.name,
                    onBack = { PhaseDetailMenu(monsters, definition, phase).open(it) },
                ).open(player)
            }
        }

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { PhaseListMenu(monsters, definition).open(it) }
        }

        set(
            53,
            Icon.of(
                Material.TNT, "<red>이 페이즈 삭제</red>",
                "<gray>패턴과 진입 동작도 함께 사라집니다.</gray>",
                "",
                "<red>▶ Shift+클릭</red>",
            ),
        ) { event ->
            if (!event.isShiftClick) return@set
            val player = event.whoClicked as? Player ?: return@set
            definition.phases.remove(phase)
            save()
            PhaseListMenu(monsters, definition).open(player)
        }
    }

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = PhaseDetailMenu(monsters, definition, phase).open(player)
}

/** The scripted sequence for one phase. */
class PatternMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
    private val phase: Phase,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>패턴 스텝</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        phase.steps.take(CONTENT).forEachIndexed { index, step ->
            set(index, iconFor(index, step)) { event ->
                val player = event.whoClicked as? Player ?: return@set
                when (event.click) {
                    ClickType.SHIFT_RIGHT -> {
                        phase.steps.remove(step)
                        save(); reopen(player)
                    }

                    ClickType.SHIFT_LEFT -> {
                        // Reordering matters more than any single value here: a pattern is a
                        // sequence, and editing one usually means moving a step, not retyping it.
                        if (index > 0) {
                            phase.steps.removeAt(index)
                            phase.steps.add(index - 1, step)
                            save(); reopen(player)
                        }
                    }

                    ClickType.RIGHT -> {
                        step.delayTicks = (step.delayTicks - 5).coerceIn(0, 12_000)
                        save(); redraw(player)
                    }

                    ClickType.DROP, ClickType.MIDDLE, ClickType.NUMBER_KEY -> promptSkill(player, step)

                    else -> {
                        step.delayTicks = (step.delayTicks + 5).coerceIn(0, 12_000)
                        save(); redraw(player)
                    }
                }
            }
        }

        for (slot in CONTENT until 54) set(slot, Icon.EDGE)

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { PhaseDetailMenu(monsters, definition, phase).open(it) }
        }

        set(
            49,
            Icon.of(
                Material.WRITABLE_BOOK, "<green>+ 스텝 추가</green>",
                "<gray>대기 시간과 시전할 스킬을 지정합니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "시전할 스킬 ID 를 입력하세요.",
                listOf(
                    "<gray>사용 가능: " + monsters.skills.registry.ids().take(10).joinToString(", ") + "</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                val id = input.trim().lowercase()
                if (!monsters.skills.registry.exists(id)) {
                    player.sendMessage(Text.render("<red>'" + input + "' 스킬을 찾을 수 없습니다.</red>"))
                    return@promptText
                }
                phase.steps.add(PatternStep(delayTicks = 20, skillId = id))
                save()
            }
        }

        set(
            51,
            Icon.of(
                Material.PAPER, "<yellow>도움말</yellow>",
                "<gray>등록된 스텝: <white>" + phase.steps.size + "개</white></gray>",
                "<gray>반복: </gray>" + Icon.toggle(phase.loop),
                "",
                "<yellow>좌클릭</yellow><gray> : 대기 +5틱</gray>",
                "<yellow>우클릭</yellow><gray> : 대기 -5틱</gray>",
                "<yellow>숫자키</yellow><gray> : 스킬 변경</gray>",
                "<yellow>Shift+좌클릭</yellow><gray> : 위로 이동</gray>",
                "<red>Shift+우클릭</red><gray> : 삭제</gray>",
                "",
                "<dark_gray>대기 시간은 '이전 스텝으로부터' 의 간격입니다.</dark_gray>",
            ),
        )

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun iconFor(index: Int, step: PatternStep): org.bukkit.inventory.ItemStack {
        val skill = monsters.skills.registry[step.skillId]
        return Icon.of(
            skill?.icon ?: Material.BARRIER,
            "<yellow>" + (index + 1) + ". " + (skill?.displayName ?: step.skillId) + "</yellow>",
            buildList {
                add("<gray>대기: <white>" + step.delayTicks + "틱</white> <dark_gray>(" +
                    Numbers.chance(step.delayTicks / 20.0) + "초)</dark_gray></gray>")
                add("<dark_gray>" + step.skillId + "</dark_gray>")
                if (skill == null) {
                    add("")
                    add("<red>⚠ 등록되지 않은 스킬입니다.</red>")
                }
                add("")
                add("<yellow>▶ 좌/우클릭: 대기 ±5틱</yellow>")
                add("<yellow>▶ 숫자키: 스킬 변경   Shift+좌클릭: 위로</yellow>")
                add("<red>▶ Shift+우클릭: 삭제</red>")
            },
        )
    }

    private fun promptSkill(player: Player, step: PatternStep) {
        Editors.promptText(
            monsters.prompts, player, "시전할 스킬 ID 를 입력하세요.",
            listOf("<gray>사용 가능: " + monsters.skills.registry.ids().take(10).joinToString(", ") + "</gray>"),
            reopen = { reopen(player) },
        ) { input ->
            val id = input.trim().lowercase()
            if (!monsters.skills.registry.exists(id)) {
                player.sendMessage(Text.render("<red>'" + input + "' 스킬을 찾을 수 없습니다.</red>"))
                return@promptText
            }
            step.skillId = id
            save()
        }
    }

    private fun reopen(player: Player) = PatternMenu(monsters, definition, phase).open(player)

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private companion object {
        const val CONTENT = 45
    }
}
