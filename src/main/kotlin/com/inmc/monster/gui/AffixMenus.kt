package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.affix.Affix
import com.inmc.monster.affix.AffixRegistry
import com.inmc.monster.affix.AffixTarget
import com.inmc.monster.affix.AffixType
import com.inmc.monster.affix.StatModifier
import com.inmc.monster.mob.MobDefinition
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
 * Every affix on the server.
 *
 * Affixes are what make a field of ordinary mobs stop being uniform without writing a new mob
 * definition for each variation - a zombie you have killed a thousand times occasionally spawns
 * as a 강력한 좀비, and nothing but this list had to change for that to happen.
 */
class AffixListMenu(
    monsters: Monsters,
    private var page: Int = 0,
) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val all = monsters.affixes.all()
        val pages = Paging.pageCount(all.size, CONTENT)
        page = page.coerceIn(0, pages - 1)

        Paging.slice(all, page, CONTENT).forEachIndexed { index, affix ->
            set(index, iconFor(affix)) { event ->
                val player = event.whoClicked as? Player ?: return@set
                when (event.click) {
                    ClickType.SHIFT_RIGHT -> ConfirmMenu(
                        monsters,
                        question = "<red>'" + affix.id + "' 수식어를 삭제할까요?</red>",
                        detail = listOf("<gray>이미 붙어 있는 몬스터에는 영향이 없습니다.</gray>"),
                        onConfirm = {
                            monsters.affixes.delete(affix.id)
                            AffixListMenu(monsters, page).open(player)
                        },
                        onCancel = { AffixListMenu(monsters, page).open(player) },
                    ).open(player)

                    // Q is unreliable on this server, so right-click does the same thing.
                    ClickType.DROP, ClickType.RIGHT -> {
                        affix.enabled = !affix.enabled
                        monsters.affixes.markDirty()
                        redraw(player)
                    }

                    else -> AffixDetailMenu(monsters, affix, page).open(player)
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
                Material.NAME_TAG, "<green>+ 수식어 만들기</green>",
                "<gray>이름을 입력하면 기본값으로 생성됩니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "수식어 이름을 입력하세요.",
                listOf(
                    "<gray>한글/영문/숫자/_/- 24자 이내</gray>",
                    "<dark_gray>예: 강력한, 날쌘, 욕심많은</dark_gray>",
                ),
                reopen = { AffixListMenu(monsters, page).open(player) },
            ) { input ->
                val name = input.trim()
                if (!AffixRegistry.isValidId(name)) {
                    player.sendMessage(Text.render("<red>사용할 수 없는 이름입니다.</red>"))
                    return@promptText
                }
                val created = monsters.affixes.create(name)
                if (created == null) {
                    player.sendMessage(Text.render("<red>이미 존재하는 수식어입니다.</red>"))
                    return@promptText
                }
                created.display = name
                monsters.affixes.markDirty()
            }
        }

        set(
            51,
            Icon.of(
                Material.PAPER, "<yellow>수식어가 하는 일</yellow>",
                "<gray>등록된 수식어: <white>" + monsters.affixes.size + "종</white></gray>",
                "<gray>바닐라 몬스터 부착 확률: <white>" +
                    Numbers.chance(monsters.config.affixDefaults.vanillaChance) + "%</white></gray>",
                "<gray>커스텀 몬스터 부착 확률: <white>" +
                    Numbers.chance(monsters.config.affixDefaults.customChance) + "%</white></gray>",
                "",
                "<gray>스폰할 때 한 번 굴려서 붙고, 그 몬스터가</gray>",
                "<gray>죽을 때까지 유지됩니다.</gray>",
                "<dark_gray>곱연산(mult)을 먼저, 합연산(add)을 나중에 적용합니다.</dark_gray>",
                "",
                "<yellow>좌클릭</yellow><gray> : 설정</gray>",
                "<yellow>우클릭</yellow><gray> : 켜기/끄기</gray>",
                "<red>Shift+우클릭</red><gray> : 삭제</gray>",
            ),
        )

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun iconFor(affix: Affix): org.bukkit.inventory.ItemStack = Icon.of(
        if (affix.enabled) {
            if (affix.type == AffixType.PREFIX) Material.NAME_TAG else Material.PAPER
        } else {
            Material.GRAY_DYE
        },
        "<yellow>" + affix.id + "</yellow> <dark_gray>(" + affix.type.label + ")</dark_gray>",
        buildList {
            add("<gray>표시: " + affix.display + "</gray>")
            add("<gray>가중치: <white>" + affix.weight + "</white></gray>")
            add(
                "<gray>적용 대상: <white>" +
                    affix.applyTo.joinToString(", ") { if (it == AffixTarget.CUSTOM) "커스텀" else "바닐라" } +
                    "</white></gray>",
            )
            if (affix.entityTypes.isNotEmpty()) {
                add("<gray>엔티티 제한: <white>" + affix.entityTypes.size + "종</white></gray>")
            }
            if (affix.modifiers.isEmpty()) {
                add("<red>능력치 변화가 없습니다.</red>")
            } else {
                add("<gray>능력치:</gray>")
                affix.modifiers.entries.take(5).forEach { (key, modifier) ->
                    add("<dark_gray> · " + key + " " + modifier.describe() + "</dark_gray>")
                }
                if (affix.modifiers.size > 5) add("<dark_gray> · ...</dark_gray>")
            }
            if (affix.dropMultiplier != 1.0) {
                add("<aqua>드랍 배율 x" + Numbers.chance(affix.dropMultiplier) + "</aqua>")
            }
            if (affix.skills.isNotEmpty()) add("<light_purple>추가 스킬 " + affix.skills.size + "개</light_purple>")
            if (!affix.enabled) {
                add("")
                add("<red>비활성화됨</red>")
            }
            add("")
            add("<yellow>▶ 좌클릭: 설정   우클릭: 켜기/끄기</yellow>")
            add("<red>▶ Shift+우클릭: 삭제</red>")
        },
    )

    private fun switch(who: org.bukkit.entity.HumanEntity, target: Int) {
        (who as? Player)?.let { AffixListMenu(monsters, target).open(it) }
    }

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    companion object {
        private const val CONTENT = 45
        private val TITLE = Text.renderFlat("<dark_gray>수식어 관리</dark_gray>")
    }
}

/** One affix: display, weighting, targets and stat modifiers. */
class AffixDetailMenu(
    monsters: Monsters,
    private val affix: Affix,
    private val returnPage: Int,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>수식어 설정</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(
            4,
            Icon.of(
                Material.NAME_TAG, "<gold>" + affix.id + "</gold>",
                "<gray>미리보기: " + affix.display + " 좀비</gray>",
                "<dark_gray>" + affix.type.label + "</dark_gray>",
            ),
        )

        set(
            19,
            Icon.of(
                Material.WRITABLE_BOOK, "<yellow>표시 이름</yellow>",
                "<gray>현재: " + affix.display + "</gray>",
                "<dark_gray>몬스터 이름 앞이나 뒤에 붙는 문구입니다.</dark_gray>",
                "<dark_gray>색상 코드와 MiniMessage 를 쓸 수 있습니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "표시할 문구를 입력하세요.",
                listOf("<dark_gray>예: &c강력한   또는   <red>강력한</red></dark_gray>"),
                reopen = { reopen(player) },
            ) { input ->
                affix.display = input
                save()
            }
        }

        set(
            20,
            Icon.of(
                if (affix.type == AffixType.PREFIX) Material.NAME_TAG else Material.PAPER,
                "<yellow>접두 / 접미</yellow>",
                buildList {
                    add("<gray>현재: <white>" + affix.type.label + "</white></gray>")
                    add("<dark_gray>접두사는 이름 앞, 접미사는 그 뒤에 붙습니다.</dark_gray>")
                    add("<dark_gray>'강력한 욕심많은 좀비' 처럼 함께 붙을 수 있습니다.</dark_gray>")
                    addAll(Editors.cycleHint)
                },
            ),
        ) { event ->
            affix.type = if (affix.type == AffixType.PREFIX) AffixType.SUFFIX else AffixType.PREFIX
            save(); redraw(event.whoClicked)
        }

        set(
            21,
            Editors.intIcon(
                Material.COMPARATOR, "<yellow>가중치</yellow>", affix.weight,
                extra = listOf(
                    "<dark_gray>같은 종류의 수식어끼리 경쟁할 때의 비율입니다.</dark_gray>",
                    "<dark_gray>값이 클수록 자주 뽑힙니다.</dark_gray>",
                ),
            ),
        ) { event ->
            affix.weight = (affix.weight + Editors.step(event, 1)).coerceIn(1, 10_000)
            save(); redraw(event.whoClicked)
        }

        set(
            22,
            Icon.of(
                Material.ZOMBIE_HEAD, "<yellow>적용 대상</yellow>",
                buildList {
                    add("<gray>커스텀 몬스터: </gray>" + Icon.toggle(affix.applyTo.contains(AffixTarget.CUSTOM)))
                    add("<gray>바닐라 몬스터: </gray>" + Icon.toggle(affix.applyTo.contains(AffixTarget.VANILLA)))
                    add("")
                    add("<dark_gray>바닐라를 켜면 평범한 자연스폰 몬스터에도</dark_gray>")
                    add("<dark_gray>가끔 이 수식어가 붙습니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 좌클릭: 커스텀 전환</yellow>")
                    add("<yellow>▶ 우클릭: 바닐라 전환</yellow>")
                },
            ),
        ) { event ->
            val target = if (event.isRightClick) AffixTarget.VANILLA else AffixTarget.CUSTOM
            if (!affix.applyTo.remove(target)) affix.applyTo.add(target)
            save(); redraw(event.whoClicked)
        }

        set(
            23,
            Icon.of(
                Material.SPYGLASS, "<yellow>엔티티 종류 제한</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (affix.entityTypes.isEmpty()) "모든 종류" else affix.entityTypes.joinToString(", ") { it.name }) +
                            "</white></gray>",
                    )
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "허용할 엔티티 종류를 입력하세요. (쉼표로 여러 개)",
                listOf(
                    "<gray>예: <white>ZOMBIE, SKELETON, SPIDER</white></gray>",
                    "<gray>'없음' 을 입력하면 제한을 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                affix.entityTypes = if (input.trim() == "없음") {
                    linkedSetOf()
                } else {
                    input.split(',').mapNotNull { raw ->
                        runCatching { EntityType.valueOf(raw.trim().uppercase()) }.getOrNull()
                    }.toCollection(linkedSetOf())
                }
                save()
            }
        }

        set(
            24,
            Icon.of(
                Material.GRASS_BLOCK, "<yellow>허용 월드</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (affix.worlds.isEmpty()) "모든 월드" else affix.worlds.joinToString(", ")) +
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
                affix.worlds = if (input.trim() == "없음") {
                    linkedSetOf()
                } else {
                    input.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toCollection(linkedSetOf())
                }
                save()
            }
        }

        set(
            28,
            Icon.of(
                Material.ANVIL, "<green>능력치 변화</green>",
                buildList {
                    if (affix.modifiers.isEmpty()) {
                        add("<red>설정된 변화가 없습니다.</red>")
                        add("<dark_gray>이대로면 이름만 붙고 아무 효과도 없습니다.</dark_gray>")
                    } else {
                        affix.modifiers.forEach { (key, modifier) ->
                            add("<gray>" + key + ": <white>" + modifier.describe() + "</white></gray>")
                        }
                    }
                    add("")
                    add("<gray>곱연산을 먼저, 합연산을 나중에 적용합니다.</gray>")
                    add("<dark_gray>순서가 고정된 이유: 합을 먼저 적용하면 관계없는</dark_gray>")
                    add("<dark_gray>배율 수식어가 그 값을 조용히 증폭시킵니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭하여 편집</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "능력치 변화를 입력하세요.",
                listOf(
                    "<gray>형식: <white>스탯 mult 값</white> 또는 <white>스탯 add 값</white></gray>",
                    "<gray>여러 개는 | 로 구분합니다.</gray>",
                    "<gray>예: <white>ATTACK_DAMAGE mult 1.35|ATTACK_DAMAGE add 2|MAX_HEALTH mult 1.25</white></gray>",
                    "<gray>'없음' 을 입력하면 모두 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                affix.modifiers.clear()
                if (input.trim() != "없음") {
                    for (part in input.split('|')) {
                        val bits = part.trim().split(' ').filter { it.isNotBlank() }
                        if (bits.size < 3) continue
                        val key = bits[0].uppercase()
                        val value = bits[2].toDoubleOrNull() ?: continue
                        val modifier = affix.modifiers.getOrPut(key) { StatModifier() }
                        if (bits[1].equals("mult", ignoreCase = true)) modifier.mult = value
                        else modifier.add = value
                    }
                    affix.modifiers.entries.removeIf { it.value.isNoop() }
                }
                save()
            }
        }

        set(
            30,
            Editors.numberIcon(
                Material.CHEST, "<yellow>드랍 배율</yellow>", affix.dropMultiplier, "배",
                extra = listOf("<dark_gray>이 수식어가 붙은 몬스터의 드랍 확률에 곱해집니다.</dark_gray>"),
                stepLabel = "0.1",
            ),
        ) { event ->
            affix.dropMultiplier = (affix.dropMultiplier + Editors.step(event, 0.1)).coerceIn(0.0, 100.0)
            save(); redraw(event.whoClicked)
        }

        set(
            31,
            Editors.numberIcon(
                Material.EXPERIENCE_BOTTLE, "<yellow>경험치 배율</yellow>", affix.expMultiplier, "배",
                stepLabel = "0.1",
            ),
        ) { event ->
            affix.expMultiplier = (affix.expMultiplier + Editors.step(event, 0.1)).coerceIn(0.0, 100.0)
            save(); redraw(event.whoClicked)
        }

        set(
            32,
            Editors.intIcon(
                Material.LADDER, "<yellow>최소 몬스터 레벨</yellow>", affix.minLevel,
                extra = listOf("<dark_gray>이 레벨 이상인 몬스터에게만 붙습니다. 0 이면 제한 없음.</dark_gray>"),
            ),
        ) { event ->
            affix.minLevel = (affix.minLevel + Editors.step(event, 1)).coerceAtLeast(0)
            save(); redraw(event.whoClicked)
        }

        set(
            34,
            Icon.of(
                if (affix.glowColor.isNotBlank()) Material.GLOWSTONE_DUST else Material.GRAY_DYE,
                "<yellow>발광 표시</yellow>",
                buildList {
                    add("<gray>현재: <white>" + affix.glowColor.ifBlank { "없음" } + "</white></gray>")
                    add("")
                    add("<gray>값을 넣으면 이 수식어가 붙은 몬스터가 빛납니다.</gray>")
                    add("<dark_gray>색상은 적용되지 않습니다 - 바닐라는 스코어보드 팀에서</dark_gray>")
                    add("<dark_gray>색을 가져오므로, 팀 관리까지 하지 않는 한 전부 흰색으로</dark_gray>")
                    add("<dark_gray>보입니다. 절반만 구현하느니 켜기/끄기만 제공합니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭하여 전환</yellow>")
                },
            ),
        ) { event ->
            affix.glowColor = if (affix.glowColor.isBlank()) "WHITE" else ""
            save(); redraw(event.whoClicked)
        }

        set(
            40,
            Icon.of(
                Material.CHEST, "<green>추가 드랍 아이템</green>",
                buildList {
                    add("<gray>등록된 드랍: <white>" + affix.drops.entries.size + "종</white></gray>")
                    add("")
                    add("<gray>이 수식어가 붙은 몬스터가 죽을 때</gray>")
                    add("<gray>원래 드랍에 <white>더해서</white> 나옵니다.</gray>")
                    add("<dark_gray>바닐라 몬스터에도 적용되므로, 평범한 좀비에</dark_gray>")
                    add("<dark_gray>수식어가 붙었을 때만 나오는 보상을 만들 수 있습니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            (event.whoClicked as? Player)?.let {
                DropListMenu(monsters, DropContext.of(monsters, affix, returnPage)).open(it)
            }
        }

        set(
            42,
            Icon.of(
                if (affix.enabled) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>수식어 활성화</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(affix.enabled),
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            affix.enabled = !affix.enabled
            save(); redraw(event.whoClicked)
        }

        set(Paging.SLOT_BACK, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { AffixListMenu(monsters, returnPage).open(it) }
        }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun save() = monsters.affixes.markDirty()

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = AffixDetailMenu(monsters, affix, returnPage).open(player)
}

/** Per-mob affix policy: whether they roll at all, how often, and from which pool. */
class MobAffixMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
) : Menu(monsters, 45, Text.renderFlat("<dark_gray>수식어 허용</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val settings = definition.affixes

        set(
            11,
            Icon.of(
                if (settings.enabled) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>수식어 사용</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(settings.enabled),
                "<dark_gray>끄면 이 몬스터에는 수식어가 붙지 않습니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            settings.enabled = !settings.enabled
            save(); redraw(event.whoClicked)
        }

        set(
            13,
            Icon.of(
                Material.LIGHT_BLUE_DYE, "<yellow>부착 확률</yellow>",
                buildList {
                    if (settings.chance < 0.0) {
                        add(
                            "<gray>현재: <white>전역 기본값 (" +
                                Numbers.chance(monsters.config.affixDefaults.customChance) + "%)</white></gray>",
                        )
                    } else {
                        add("<gray>현재: <yellow>" + Numbers.chance(settings.chance) + "%</yellow></gray>")
                    }
                    add("<dark_gray>월드별 배율이 여기에 곱해집니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 좌클릭 +5 / 우클릭 -5</yellow>")
                    add("<red>▶ Shift+우클릭: 전역 기본값으로</red>")
                },
            ),
        ) { event ->
            if (event.isShiftClick && event.isRightClick) {
                settings.chance = -1.0
                save(); redraw(event.whoClicked)
                return@set
            }
            val base = if (settings.chance < 0.0) monsters.config.affixDefaults.customChance else settings.chance
            settings.chance = (base + Editors.step(event, 5.0)).coerceIn(0.0, 100.0)
            save(); redraw(event.whoClicked)
        }

        set(
            15,
            Icon.of(
                Material.NAME_TAG, "<yellow>최대 접두 / 접미 수</yellow>",
                buildList {
                    add(
                        "<gray>접두: <white>" +
                            (if (settings.maxPrefix < 0) "기본 (" + monsters.config.affixDefaults.maxPrefix + ")" else settings.maxPrefix.toString()) +
                            "</white></gray>",
                    )
                    add(
                        "<gray>접미: <white>" +
                            (if (settings.maxSuffix < 0) "기본 (" + monsters.config.affixDefaults.maxSuffix + ")" else settings.maxSuffix.toString()) +
                            "</white></gray>",
                    )
                    add("")
                    add("<yellow>▶ 좌클릭: 접두 +1  /  우클릭: 접미 +1</yellow>")
                    add("<red>▶ Shift+클릭: 기본값으로</red>")
                },
            ),
        ) { event ->
            if (event.isShiftClick) {
                settings.maxPrefix = -1
                settings.maxSuffix = -1
                save(); redraw(event.whoClicked)
                return@set
            }
            if (event.isRightClick) {
                val base = if (settings.maxSuffix < 0) monsters.config.affixDefaults.maxSuffix else settings.maxSuffix
                settings.maxSuffix = (base + 1) % 4
            } else {
                val base = if (settings.maxPrefix < 0) monsters.config.affixDefaults.maxPrefix else settings.maxPrefix
                settings.maxPrefix = (base + 1) % 4
            }
            save(); redraw(event.whoClicked)
        }

        set(
            29,
            Icon.of(
                Material.BOOK, "<yellow>허용 수식어 목록</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (settings.allowed.isEmpty()) "전체 허용" else settings.allowed.joinToString(", ")) +
                            "</white></gray>",
                    )
                    add("<dark_gray>비워두면 이 몬스터를 받아주는 모든 수식어가 후보입니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event -> promptList(event, "허용할 수식어", settings.allowed) { settings.allowed = it } }

        set(
            33,
            Icon.of(
                Material.RED_BANNER, "<yellow>차단 수식어 목록</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (settings.blocked.isEmpty()) "없음" else settings.blocked.joinToString(", ")) +
                            "</white></gray>",
                    )
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event -> promptList(event, "차단할 수식어", settings.blocked) { settings.blocked = it } }

        set(36, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { MobManageMenu(monsters, definition).open(it) }
        }
        set(44, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun promptList(
        event: org.bukkit.event.inventory.InventoryClickEvent,
        label: String,
        current: Set<String>,
        setter: (MutableSet<String>) -> Unit,
    ) {
        val player = event.whoClicked as? Player ?: return
        Editors.promptText(
            monsters.prompts, player, label + " 이름을 입력하세요. (쉼표로 여러 개)",
            listOf(
                "<gray>등록된 수식어: " + monsters.affixes.all().take(8).joinToString(", ") { it.id } + "</gray>",
                "<gray>현재: " + (if (current.isEmpty()) "없음" else current.joinToString(", ")) + "</gray>",
                "<gray>'없음' 을 입력하면 목록을 지웁니다.</gray>",
            ),
            reopen = { MobAffixMenu(monsters, definition).open(player) },
        ) { input ->
            setter(
                if (input.trim() == "없음") linkedSetOf()
                else input.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    .toCollection(linkedSetOf()),
            )
            save()
        }
    }

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }
}
