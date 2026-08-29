package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.skill.ParamType
import com.inmc.monster.skill.Skill
import com.inmc.monster.skill.SkillInstance
import com.inmc.monster.skill.SkillParam
import com.inmc.monster.skill.SkillTrigger
import com.inmc.monster.skill.TargetSelector
import com.inmc.monster.util.Numbers
import com.inmc.monster.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/** A mob's configured skills. */
class SkillListMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
    private var page: Int = 0,
) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val skills = definition.skills
        val pages = maxOf(1, (skills.size + CONTENT + 1) / CONTENT)
        page = page.coerceIn(0, pages - 1)

        skills.drop(page * CONTENT).take(CONTENT).forEachIndexed { index, instance ->
            val skill = monsters.skills.registry[instance.skillId]
            set(index, iconFor(instance, skill)) { event ->
                val player = event.whoClicked as? Player ?: return@set
                when (event.click) {
                    ClickType.SHIFT_RIGHT -> {
                        definition.skills.remove(instance)
                        save()
                        SkillListMenu(monsters, definition, page).open(player)
                    }

                    // Q is unreliable on this server, so right-click does the same thing.
                    ClickType.DROP, ClickType.RIGHT -> {
                        instance.enabled = !instance.enabled
                        save(); redraw(player)
                    }

                    else -> if (skill != null) {
                        SkillDetailMenu(monsters, definition, instance, page).open(player)
                    }
                }
            }
        }

        for (slot in CONTENT until 54) set(slot, Icon.EDGE)

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { MobManageMenu(monsters, definition).open(it) }
        }

        if (page > 0) set(46, Icon.prevPage()) { event -> switchPage(event.whoClicked, page - 1) }
        if (page < pages - 1) set(47, Icon.nextPage()) { event -> switchPage(event.whoClicked, page + 1) }

        set(
            49,
            Icon.of(
                Material.BLAZE_POWDER, "<green>+ 스킬 추가</green>",
                "<gray>사용 가능한 스킬 목록에서 고릅니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            (event.whoClicked as? Player)?.let { SkillPickerMenu(monsters, definition).open(it) }
        }

        set(
            51,
            Icon.of(
                Material.PAPER, "<yellow>도움말</yellow>",
                "<gray>등록된 스킬: <white>" + skills.size + "개</white></gray>",
                "",
                "<yellow>좌클릭</yellow><gray> : 설정</gray>",
                "<yellow>우클릭</yellow><gray> : 켜기/끄기</gray>",
                "<red>Shift+우클릭</red><gray> : 삭제</gray>",
                "",
                "<dark_gray>같은 스킬을 여러 번 추가해 서로 다른 발동 조건과</dark_gray>",
                "<dark_gray>수치로 쓸 수 있습니다.</dark_gray>",
            ),
        )

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun iconFor(instance: SkillInstance, skill: Skill?): org.bukkit.inventory.ItemStack {
        if (skill == null) {
            return Icon.of(
                Material.BARRIER, "<red>알 수 없는 스킬: " + instance.skillId + "</red>",
                "<gray>이 이름의 스킬이 등록되어 있지 않습니다.</gray>",
                "<dark_gray>오타이거나, 연동 플러그인이 빠졌을 수 있습니다.</dark_gray>",
                "",
                "<red>▶ Shift+우클릭: 삭제</red>",
            )
        }
        val unavailable = monsters.skills.registry.unavailableReason(skill)
        val lore = mutableListOf(
            "<gray>발동: <white>" + instance.trigger.label + "</white></gray>",
            "<gray>대상: <white>" + instance.selector.label + "</white></gray>",
            "<gray>쿨다운: <white>" + Numbers.chance(instance.cooldownTicks / 20.0) + "초</white>" +
                "   확률: <white>" + Numbers.chance(instance.chance) + "%</white></gray>",
            "<gray>반경: <white>" + Numbers.chance(instance.radius) + "</white></gray>",
        )
        if (instance.trigger == SkillTrigger.ON_LOW_HEALTH) {
            lore.add("<gray>발동 체력: <white>" + Numbers.chance(instance.healthThreshold) + "% 이하</white></gray>")
        }
        if (instance.castTimeTicks > 0) {
            lore.add("<gray>선딜: <white>" + instance.castTimeTicks + "틱</white>" +
                (if (instance.interruptOnDamage) " <dark_gray>(피격 시 취소)</dark_gray>" else "") + "</gray>")
        }
        if (instance.phases.isNotEmpty()) {
            lore.add("<light_purple>페이즈: " + instance.phases.joinToString(", ") + "</light_purple>")
        }
        if (unavailable != null) {
            lore.add("")
            lore.add("<red>⚠ " + unavailable + "</red>")
        }
        if (skill.rejects(instance.selector)) {
            // Surfaced on the list itself, not just inside the detail screen: an admin looking
            // for "why is this one not firing" should not have to open every skill to find it.
            lore.add("")
            lore.add("<red>⚠ 대상이 '" + instance.selector.label + "' 라 발동하지 않습니다.</red>")
            lore.add("<dark_gray>이 스킬은 자기 자신이 아닌 대상이 필요합니다.</dark_gray>")
        }
        if (!instance.enabled) {
            lore.add("")
            lore.add("<red>비활성화됨</red>")
        }
        lore.add("")
        lore.add("<yellow>▶ 좌클릭: 설정   우클릭: 켜기/끄기</yellow>")
        lore.add("<red>▶ Shift+우클릭: 삭제</red>")

        return Icon.of(
            if (instance.enabled) skill.icon else Material.GRAY_DYE,
            (if (instance.enabled) "<yellow>" else "<dark_gray>") + skill.displayName +
                (if (instance.enabled) "</yellow>" else "</dark_gray>"),
            lore,
        )
    }

    private fun switchPage(who: org.bukkit.entity.HumanEntity, target: Int) {
        (who as? Player)?.let { SkillListMenu(monsters, definition, target).open(it) }
    }

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    companion object {
        private const val CONTENT = 45
        private val TITLE = Text.renderFlat("<dark_gray>스킬 목록</dark_gray>")
    }
}

/** Picks which skill to add to a mob. */
class SkillPickerMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
    private var page: Int = 0,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>스킬 선택</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val all = monsters.skills.registry.all()
        val pages = maxOf(1, (all.size + CONTENT - 1) / CONTENT)
        page = page.coerceIn(0, pages - 1)

        all.drop(page * CONTENT).take(CONTENT).forEachIndexed { index, skill ->
            val unavailable = monsters.skills.registry.unavailableReason(skill)
            set(
                index,
                Icon.of(
                    if (unavailable == null) skill.icon else Material.GRAY_DYE,
                    (if (unavailable == null) "<yellow>" else "<dark_gray>") + skill.displayName +
                        (if (unavailable == null) "</yellow>" else "</dark_gray>"),
                    buildList {
                        skill.description.forEach { add("<gray>" + it + "</gray>") }
                        add("<dark_gray>" + skill.id + "</dark_gray>")
                        add("")
                        add("<gray>설정 항목: <white>" + skill.parameters.size + "개</white></gray>")
                        if (unavailable != null) {
                            add("")
                            add("<red>⚠ " + unavailable + "</red>")
                            add("<dark_gray>지금 추가해 두면 설치 후 바로 동작합니다.</dark_gray>")
                        }
                        add("")
                        add("<yellow>▶ 클릭하여 추가</yellow>")
                    },
                ),
            ) { event ->
                val player = event.whoClicked as? Player ?: return@set
                val instance = SkillInstance(skillId = skill.id, selector = skill.defaultSelector)
                definition.skills.add(instance)
                monsters.mobs.markDirty(definition)
                SkillDetailMenu(monsters, definition, instance, 0).open(player)
            }
        }

        for (slot in CONTENT until 54) set(slot, Icon.EDGE)

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { SkillListMenu(monsters, definition).open(it) }
        }
        if (page > 0) set(46, Icon.prevPage()) { event -> switch(event.whoClicked, page - 1) }
        if (page < pages - 1) set(47, Icon.nextPage()) { event -> switch(event.whoClicked, page + 1) }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun switch(who: org.bukkit.entity.HumanEntity, target: Int) {
        (who as? Player)?.let { SkillPickerMenu(monsters, definition, target).open(it) }
    }

    private companion object {
        const val CONTENT = 45
    }
}

/** Trigger, targeting and timing for one configured skill. */
class SkillDetailMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
    private val instance: SkillInstance,
    private val returnPage: Int,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>스킬 설정</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val skill = monsters.skills.registry[instance.skillId]
        if (skill == null) {
            set(22, Icon.of(Material.BARRIER, "<red>스킬을 찾을 수 없습니다</red>", "<gray>" + instance.skillId + "</gray>"))
            set(45, Icon.back()) { event -> back(event.whoClicked) }
            return
        }

        set(
            4,
            Icon.of(
                skill.icon, "<gold>" + skill.displayName + "</gold>",
                buildList {
                    skill.description.forEach { add("<gray>" + it + "</gray>") }
                    add("<dark_gray>" + skill.id + "</dark_gray>")
                    monsters.skills.registry.unavailableReason(skill)?.let {
                        add("")
                        add("<red>⚠ " + it + "</red>")
                    }
                },
            ),
        )

        set(
            19,
            Icon.of(
                Material.REDSTONE_TORCH, "<yellow>발동 조건</yellow>",
                buildList {
                    add("<gray>현재: <white>" + instance.trigger.label + "</white></gray>")
                    add("<dark_gray>" + instance.trigger.description + "</dark_gray>")
                    add("")
                    addAll(Editors.optionList(SkillTrigger.entries.toList(), instance.trigger) { it.label })
                    addAll(Editors.cycleHint)
                },
            ),
        ) { event ->
            instance.trigger = Editors.cycle(event, SkillTrigger.entries.toList(), instance.trigger)
            save(); redraw(event.whoClicked)
        }

        // Only the selectors this skill can act on are offered. A leap aimed at its own caster
        // is a configuration that looks fine and never fires, so it is not made available.
        val selectors = skill.usableSelectors
        val badSelector = skill.rejects(instance.selector)

        set(
            20,
            Icon.of(
                if (badSelector) Material.BARRIER else Material.SPYGLASS,
                "<yellow>대상 선택</yellow>",
                buildList {
                    add("<gray>현재: <white>" + instance.selector.label + "</white></gray>")
                    if (badSelector) {
                        add("")
                        add("<red>⚠ 이 스킬은 자기 자신이 아닌 대상이 필요합니다.</red>")
                        add("<red>지금 설정으로는 절대 발동하지 않습니다.</red>")
                        add("<dark_gray>아래 목록에서 다른 대상을 골라주세요.</dark_gray>")
                    }
                    add("")
                    addAll(Editors.optionList(selectors, instance.selector) { it.label })
                    addAll(Editors.cycleHint)
                },
            ),
        ) { event ->
            instance.selector = if (instance.selector in selectors) {
                Editors.cycle(event, selectors, instance.selector)
            } else {
                // The stored value is not on the list, so cycling has nothing to move from -
                // land on the skill's own default instead of leaving it stuck.
                skill.defaultSelector
            }
            save(); redraw(event.whoClicked)
        }

        set(
            21,
            Editors.intIcon(
                Material.CLOCK, "<yellow>쿨다운</yellow>", instance.cooldownTicks, "틱",
                extra = listOf(
                    "<gray>= <white>" + Numbers.chance(instance.cooldownTicks / 20.0) + "초</white></gray>",
                    "<dark_gray>주기적 발동이면 이 간격마다 시전합니다.</dark_gray>",
                ),
                stepLabel = "20",
            ),
        ) { event ->
            if (Editors.isPrompt(event)) {
                val player = event.whoClicked as? Player ?: return@set
                Editors.promptInt(monsters, player, "쿨다운 (틱)", 1, 72_000, { reopen(player) }) {
                    instance.cooldownTicks = it
                    save()
                }
                return@set
            }
            instance.cooldownTicks = (instance.cooldownTicks + Editors.step(event, 20)).coerceIn(1, 72_000)
            save(); redraw(event.whoClicked)
        }

        set(
            22,
            Editors.numberIcon(
                Material.LIGHT_BLUE_DYE, "<yellow>발동 확률</yellow>", instance.chance, "%",
                stepLabel = "5",
            ),
        ) { event ->
            if (Editors.isPrompt(event)) {
                val player = event.whoClicked as? Player ?: return@set
                Editors.promptDouble(monsters, player, "발동 확률", 0.01, 100.0, { reopen(player) }) {
                    instance.chance = it
                    save()
                }
                return@set
            }
            instance.chance = (instance.chance + Editors.step(event, 5.0)).coerceIn(0.01, 100.0)
            save(); redraw(event.whoClicked)
        }

        set(
            23,
            Editors.numberIcon(
                Material.ENDER_EYE, "<yellow>탐지 반경</yellow>", instance.radius, "블록",
                extra = listOf("<dark_gray>대상을 찾는 범위입니다. 스킬 자체의 범위와는 별개입니다.</dark_gray>"),
            ),
        ) { event ->
            instance.radius = (instance.radius + Editors.step(event, 1.0)).coerceIn(0.5, 128.0)
            save(); redraw(event.whoClicked)
        }

        if (instance.trigger == SkillTrigger.ON_LOW_HEALTH) {
            set(
                24,
                Editors.numberIcon(
                    Material.RED_DYE, "<yellow>발동 체력</yellow>", instance.healthThreshold, "% 이하",
                    extra = listOf("<dark_gray>이 체력 아래로 떨어지면 한 번만 발동합니다.</dark_gray>"),
                    stepLabel = "5",
                ),
            ) { event ->
                instance.healthThreshold =
                    (instance.healthThreshold + Editors.step(event, 5.0)).coerceIn(1.0, 100.0)
                save(); redraw(event.whoClicked)
            }
        }

        set(
            28,
            Editors.intIcon(
                Material.FEATHER, "<yellow>선딜 (시전 시간)</yellow>", instance.castTimeTicks, "틱",
                extra = listOf(
                    "<dark_gray>0 이면 즉시 발동합니다.</dark_gray>",
                    "<dark_gray>선딜 중에는 예고 파티클이 표시되어</dark_gray>",
                    "<dark_gray>플레이어가 피할 시간을 얻습니다.</dark_gray>",
                ),
                stepLabel = "5",
            ),
        ) { event ->
            instance.castTimeTicks = (instance.castTimeTicks + Editors.step(event, 5)).coerceIn(0, 600)
            save(); redraw(event.whoClicked)
        }

        set(
            29,
            Icon.of(
                if (instance.interruptOnDamage) Material.SHIELD else Material.GRAY_DYE,
                "<yellow>피격 시 시전 취소</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(instance.interruptOnDamage),
                "<dark_gray>선딜이 있어야 의미가 있습니다.</dark_gray>",
                "<dark_gray>공략에 '캐스팅을 끊는다' 는 요소를 만듭니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            instance.interruptOnDamage = !instance.interruptOnDamage
            save(); redraw(event.whoClicked)
        }

        set(
            30,
            Icon.of(
                Material.HEART_OF_THE_SEA, "<yellow>사용 페이즈 제한</yellow>",
                buildList {
                    if (instance.phases.isEmpty()) {
                        add("<gray>모든 페이즈에서 사용합니다.</gray>")
                    } else {
                        add("<gray>다음 페이즈에서만 사용:</gray>")
                        instance.phases.forEach { add("<dark_gray> · " + it + "</dark_gray>") }
                    }
                    if (definition.phases.isEmpty()) {
                        add("")
                        add("<dark_gray>이 몬스터에는 페이즈가 없습니다.</dark_gray>")
                    }
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters, player, "사용할 페이즈 이름을 입력하세요. (쉼표로 여러 개)",
                listOf(
                    "<gray>등록된 페이즈: " +
                        (if (definition.phases.isEmpty()) "없음" else definition.phases.joinToString(", ") { it.name }) +
                        "</gray>",
                    "<gray>'없음' 을 입력하면 제한을 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                instance.phases = if (input.trim() == "없음") {
                    linkedSetOf()
                } else {
                    input.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toCollection(linkedSetOf())
                }
                save()
            }
        }

        set(
            32,
            Icon.of(
                Material.WRITABLE_BOOK, "<green>세부 수치 설정</green>",
                "<gray>이 스킬만의 값입니다. <white>" + skill.parameters.size + "개</white></gray>",
                "<dark_gray>피해량·반경·파티클 등 스킬마다 다릅니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            (event.whoClicked as? Player)?.let {
                SkillParamMenu(monsters, definition, instance, skill, returnPage).open(it)
            }
        }

        set(
            34,
            Icon.of(
                if (instance.enabled) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>스킬 활성화</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(instance.enabled),
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            instance.enabled = !instance.enabled
            save(); redraw(event.whoClicked)
        }

        set(45, Icon.back()) { event -> back(event.whoClicked) }

        set(
            53,
            Icon.of(
                Material.TNT, "<red>이 스킬 삭제</red>",
                "<gray>목록에서 제거합니다.</gray>",
                "",
                "<red>▶ Shift+클릭</red>",
            ),
        ) { event ->
            if (!event.isShiftClick) return@set
            definition.skills.remove(instance)
            save()
            back(event.whoClicked)
        }
    }

    private fun back(who: org.bukkit.entity.HumanEntity) {
        (who as? Player)?.let { SkillListMenu(monsters, definition, returnPage).open(it) }
    }

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) =
        SkillDetailMenu(monsters, definition, instance, returnPage).open(player)
}

/**
 * Editor generated from a skill's own parameter declarations.
 *
 * This screen has no knowledge of any particular skill. It reads [Skill.parameters] and builds
 * a control per entry, which is the only reason two dozen built-in skills do not need two dozen
 * hand-written menus that drift out of sync with their `cast` implementations.
 */
class SkillParamMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
    private val instance: SkillInstance,
    private val skill: Skill,
    private val returnPage: Int,
    private var page: Int = 0,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>스킬 수치 설정</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val params = skill.parameters
        val pages = maxOf(1, (params.size + CONTENT - 1) / CONTENT)
        page = page.coerceIn(0, pages - 1)

        params.drop(page * CONTENT).take(CONTENT).forEachIndexed { index, param ->
            set(index, iconFor(param)) { event -> handle(event, param) }
        }

        for (slot in CONTENT until 54) set(slot, Icon.EDGE)

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let {
                SkillDetailMenu(monsters, definition, instance, returnPage).open(it)
            }
        }
        if (page > 0) set(46, Icon.prevPage()) { event -> switch(event.whoClicked, page - 1) }
        if (page < pages - 1) set(47, Icon.nextPage()) { event -> switch(event.whoClicked, page + 1) }

        set(
            49,
            Icon.of(
                Material.BARRIER, "<yellow>기본값으로 되돌리기</yellow>",
                "<gray>이 스킬의 모든 수치를 기본값으로 돌립니다.</gray>",
                "",
                "<red>▶ Shift+클릭</red>",
            ),
        ) { event ->
            if (!event.isShiftClick) return@set
            instance.values.clear()
            save(); redraw(event.whoClicked)
        }

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun current(param: SkillParam): Any = instance.values[param.key] ?: param.default

    private fun iconFor(param: SkillParam): org.bukkit.inventory.ItemStack {
        val value = current(param)
        val isDefault = !instance.values.containsKey(param.key)

        val lore = mutableListOf<String>()
        lore.add("<gray>현재: <yellow>" + display(value) + "</yellow>" + (if (isDefault) " <dark_gray>(기본값)</dark_gray>" else "") + "</gray>")
        if (param.description.isNotBlank()) lore.add("<dark_gray>" + param.description + "</dark_gray>")
        lore.add("<dark_gray>" + param.key + " · " + param.type.label + "</dark_gray>")

        when (param.type) {
            ParamType.DOUBLE, ParamType.INT -> {
                lore.add("<dark_gray>범위: " + Numbers.chance(param.min) + " ~ " + Numbers.chance(param.max) + "</dark_gray>")
                lore.add("")
                lore.add("<yellow>▶ 좌클릭 +1  /  우클릭 -1   Shift 로 10배</yellow>")
                lore.add("<yellow>▶ 숫자키: 직접 입력</yellow>")
            }

            ParamType.BOOL -> {
                lore.add("")
                lore.add("<yellow>▶ 클릭하여 전환</yellow>")
            }

            ParamType.ENUM -> {
                lore.add("")
                param.options.forEach { option ->
                    val marker = if (option == value.toString()) "<green>▶</green>" else "<dark_gray>·</dark_gray>"
                    lore.add(marker + " <dark_gray>" + option + "</dark_gray>")
                }
                lore.addAll(Editors.cycleHint)
            }

            else -> {
                lore.add("")
                lore.add("<yellow>▶ 클릭하여 입력</yellow>")
            }
        }
        if (!isDefault) lore.add("<red>▶ Shift+우클릭: 기본값으로</red>")

        return Icon.of(materialFor(param), "<yellow>" + param.label + "</yellow>", lore)
    }

    private fun handle(event: org.bukkit.event.inventory.InventoryClickEvent, param: SkillParam) {
        val player = event.whoClicked as? Player ?: return

        if (event.isShiftClick && event.isRightClick) {
            instance.values.remove(param.key)
            save(); redraw(player)
            return
        }

        when (param.type) {
            ParamType.BOOL -> {
                val value = current(param) as? Boolean ?: false
                instance.values[param.key] = !value
                save(); redraw(player)
            }

            ParamType.INT -> {
                if (Editors.isPrompt(event)) {
                    Editors.promptInt(
                        monsters, player, param.label, param.min.toInt(), param.max.toInt(), { reopen(player) },
                    ) { instance.values[param.key] = it; save() }
                    return
                }
                val value = (current(param) as? Number)?.toInt() ?: 0
                instance.values[param.key] =
                    (value + Editors.step(event, 1)).coerceIn(param.min.toInt(), param.max.toInt())
                save(); redraw(player)
            }

            ParamType.DOUBLE -> {
                if (Editors.isPrompt(event)) {
                    Editors.promptDouble(
                        monsters, player, param.label, param.min, param.max, { reopen(player) },
                    ) { instance.values[param.key] = it; save() }
                    return
                }
                val value = (current(param) as? Number)?.toDouble() ?: 0.0
                instance.values[param.key] =
                    (value + Editors.step(event, 1.0)).coerceIn(param.min, param.max)
                save(); redraw(player)
            }

            ParamType.ENUM -> {
                val value = current(param).toString()
                instance.values[param.key] = Editors.cycle(event, param.options, value)
                save(); redraw(player)
            }

            else -> {
                Editors.promptText(
                    monsters, player, param.label + " 값을 입력하세요.",
                    hintsFor(param),
                    reopen = { reopen(player) },
                ) { input ->
                    instance.values[param.key] = input.trim()
                    save()
                }
            }
        }
    }

    private fun hintsFor(param: SkillParam): List<String> = when (param.type) {
        ParamType.PARTICLE -> listOf("<gray>예: <white>FLAME, SNOWFLAKE, SOUL, CRIT, EXPLOSION</white></gray>")
        ParamType.SOUND -> listOf("<gray>예: <white>ENTITY_GENERIC_EXPLODE, ENTITY_ENDERMAN_TELEPORT</white></gray>")
        ParamType.POTION -> listOf("<gray>예: <white>SLOWNESS, POISON, WITHER, BLINDNESS</white></gray>")
        ParamType.MOB_ID -> listOf(
            "<gray>등록된 몬스터: " + monsters.mobs.ids().take(8).joinToString(", ") + "</gray>",
        )

        ParamType.MATERIAL -> listOf("<gray>예: <white>IRON_BARS, COBWEB, STONE</white></gray>")
        else -> listOf("<dark_gray>치환자를 쓸 수 있습니다: {몬스터}, {플레이어네임}</dark_gray>")
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

    private fun switch(who: org.bukkit.entity.HumanEntity, target: Int) {
        (who as? Player)?.let {
            SkillParamMenu(monsters, definition, instance, skill, returnPage, target).open(it)
        }
    }

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) =
        SkillParamMenu(monsters, definition, instance, skill, returnPage, page).open(player)

    private companion object {
        const val CONTENT = 45
    }
}

/** Read-only catalogue of every registered skill, for reference. */
class SkillCatalogMenu(
    monsters: Monsters,
    private var page: Int = 0,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>스킬 도감</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val all = monsters.skills.registry.all()
        val pages = maxOf(1, (all.size + CONTENT - 1) / CONTENT)
        page = page.coerceIn(0, pages - 1)

        all.drop(page * CONTENT).take(CONTENT).forEachIndexed { index, skill ->
            val unavailable = monsters.skills.registry.unavailableReason(skill)
            set(
                index,
                Icon.of(
                    if (unavailable == null) skill.icon else Material.GRAY_DYE,
                    "<yellow>" + skill.displayName + "</yellow>",
                    buildList {
                        skill.description.forEach { add("<gray>" + it + "</gray>") }
                        add("<dark_gray>" + skill.id + "</dark_gray>")
                        add("")
                        add("<gray>기본 대상: <white>" + skill.defaultSelector.label + "</white></gray>")
                        add("<gray>설정 항목:</gray>")
                        skill.parameters.take(8).forEach {
                            add("<dark_gray> · " + it.label + " (" + it.type.label + ")</dark_gray>")
                        }
                        if (skill.parameters.size > 8) add("<dark_gray> · ...</dark_gray>")
                        if (unavailable != null) {
                            add("")
                            add("<red>⚠ " + unavailable + "</red>")
                        }
                    },
                ),
            )
        }

        for (slot in CONTENT until 54) set(slot, Icon.EDGE)

        set(45, Icon.back()) { event -> (event.whoClicked as? Player)?.let { MainMenu(monsters).open(it) } }
        if (page > 0) set(46, Icon.prevPage()) { event -> switch(event.whoClicked, page - 1) }
        if (page < pages - 1) set(47, Icon.nextPage()) { event -> switch(event.whoClicked, page + 1) }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun switch(who: org.bukkit.entity.HumanEntity, target: Int) {
        (who as? Player)?.let { SkillCatalogMenu(monsters, target).open(it) }
    }

    private companion object {
        const val CONTENT = 45
    }
}
