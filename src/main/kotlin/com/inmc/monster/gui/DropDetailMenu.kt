package com.inmc.monster.gui

import com.inmc.monster.Monsters
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StorageMode
import com.inmc.monster.mob.DropDistribution
import com.inmc.monster.mob.MobDrop
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/** Per-drop settings: chance, amount, storage, tier, conditions and commands. */
class DropDetailMenu(
    monsters: Monsters,
    private val ctx: DropContext,
    private val drop: MobDrop,
    private val returnPage: Int,
) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(4, previewIcon())

        chanceButton(19, 0.01)
        chanceButton(20, 0.1)
        chanceButton(21, 1.0)
        chanceButton(22, 10.0)

        set(
            23,
            Icon.of(
                Material.WRITABLE_BOOK, "<yellow>확률 직접 입력</yellow>",
                "<gray>현재: <yellow>" + Numbers.chance(drop.chance) + "%</yellow></gray>",
                "<dark_gray>0.01 ~ 100 사이, 소수점 두 자리</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptDouble(
                monsters.prompts, player, "확률", Numbers.MIN_CHANCE, Numbers.MAX_CHANCE, { reopen(player) },
            ) { value ->
                drop.chance = value
                save()
            }
        }

        set(
            28,
            Editors.intIcon(Material.IRON_NUGGET, "<yellow>최소 수량</yellow>", drop.minAmount, "개"),
        ) { event ->
            drop.minAmount += Editors.step(event, 1)
            save(); redraw(event.whoClicked)
        }

        set(
            29,
            Editors.intIcon(Material.GOLD_NUGGET, "<yellow>최대 수량</yellow>", drop.maxAmount, "개"),
        ) { event ->
            drop.maxAmount += Editors.step(event, 1)
            save(); redraw(event.whoClicked)
        }

        // --- reference source ---------------------------------------------------
        val candidates = monsters.itemResolver.candidates(drop.item)
        set(
            30,
            Icon.of(
                if (candidates.size > 1) Material.SPYGLASS else Material.GRAY_DYE,
                "<yellow>참조 플러그인</yellow>",
                buildList {
                    add("<gray>현재: <white>" + refLabel(drop.item.ref) + "</white></gray>")
                    add("<dark_gray>" + drop.item.ref.serialize() + "</dark_gray>")
                    add("")
                    when {
                        candidates.isEmpty() -> {
                            add("<gray>이 아이템은 후보를 다시 계산할 수 없습니다.</gray>")
                            add("<dark_gray>스냅샷이 없어 원본을 되살릴 수 없습니다.</dark_gray>")
                        }

                        candidates.size == 1 -> {
                            add("<gray>이 아이템을 알아본 플러그인이 하나뿐입니다.</gray>")
                        }

                        else -> {
                            add("<gray>이 아이템을 <white>" + candidates.size + "개</white> 플러그인이 알아봅니다.</gray>")
                            add("<dark_gray>어느 쪽 정의로 지급할지 고르세요.</dark_gray>")
                            add("")
                            candidates.forEach { candidate ->
                                val marker =
                                    if (candidate == drop.item.ref) "<green>▶</green>" else "<dark_gray>·</dark_gray>"
                                add(marker + " <dark_gray>" + refLabel(candidate) + " - " + candidate.serialize() + "</dark_gray>")
                            }
                            add("")
                            add("<red>⚠ 바꾸면 그 플러그인의 정의로만 지급됩니다.</red>")
                            add("")
                            add("<yellow>▶ 좌클릭: 다음 후보  /  우클릭: 이전 후보</yellow>")
                        }
                    }
                },
            ),
        ) { event ->
            if (candidates.size <= 1) return@set
            drop.item = drop.item.copy(ref = Editors.cycle(event, candidates, drop.item.ref))
            save(); redraw(event.whoClicked)
        }

        // --- storage mode -------------------------------------------------------
        val reference = drop.item.mode == StorageMode.REFERENCE
        val canReference = drop.item.ref != ItemRef.None
        set(
            31,
            Icon.of(
                if (reference) Material.RECOVERY_COMPASS else Material.BUNDLE,
                if (reference) "<green>저장 방식: 참조 (자동 갱신)</green>" else "<yellow>저장 방식: 스냅샷 (고정)</yellow>",
                buildList {
                    add("<dark_gray>" + drop.item.ref.serialize() + "</dark_gray>")
                    add("")
                    if (reference) {
                        add("<gray>지급할 때마다 원본 정의대로 새로 만듭니다.</gray>")
                        add("<gray>MMOItems 에서 아이템을 수정하면 그대로 반영되고,</gray>")
                        add("<gray>새로 발급한 아이템과도 정상적으로 겹칩니다.</gray>")
                    } else {
                        add("<gray>등록 당시의 아이템을 그대로 고정해 지급합니다.</gray>")
                        add("<gray>원본 플러그인이 사라져도 동작합니다.</gray>")
                    }
                    add("")
                    if (canReference) add("<yellow>▶ 클릭하여 전환</yellow>")
                    else add("<red>이 아이템은 참조로 표현할 수 없습니다.</red>")
                },
            ),
        ) { event ->
            if (!canReference) return@set
            drop.item = drop.item.withMode(drop.item.mode.toggle())
            save(); redraw(event.whoClicked)
        }

        set(
            32,
            Icon.of(
                if (drop.announce) Material.BELL else Material.GRAY_DYE,
                "<yellow>획득 시 서버 공지</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(drop.announce),
                "<dark_gray>희귀 드랍이 나왔을 때 전체 공지를 보냅니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            drop.announce = !drop.announce
            save(); redraw(event.whoClicked)
        }

        set(
            33,
            Icon.of(
                Material.COMMAND_BLOCK, "<yellow>실행 명령어</yellow>",
                buildList {
                    if (drop.commands.isEmpty()) add("<gray>등록된 명령어가 없습니다.</gray>")
                    else drop.commands.forEach { add("<dark_gray>/" + it + "</dark_gray>") }
                    add("")
                    add("<gray>아이템 지급: </gray>" + Icon.toggle(drop.giveItem))
                    add("")
                    add("<yellow>▶ 좌클릭: 명령어 편집</yellow>")
                    add("<yellow>▶ 우클릭: 아이템 지급 여부 전환</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) {
                drop.giveItem = !drop.giveItem
                save(); redraw(player)
                return@set
            }
            Editors.promptText(
                monsters.prompts, player, "실행할 명령어를 입력하세요. (| 로 여러 개)",
                listOf(
                    "<gray>예: <white>give {플레이어네임} diamond 1|say {플레이어네임} 축하합니다</white></gray>",
                    "<gray>'없음' 을 입력하면 모두 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                drop.commands = if (input.trim() == "없음") {
                    mutableListOf()
                } else {
                    input.split('|').map { it.trim().removePrefix("/") }
                        .filter { it.isNotEmpty() }.toMutableList()
                }
                save()
            }
        }

        set(
            37,
            Icon.of(
                if (drop.tier != null) Material.BUNDLE else Material.CHEST,
                "<yellow>소속 티어</yellow>",
                buildList {
                    add("<gray>현재: <white>" + (drop.tier ?: "기본 풀") + "</white></gray>")
                    if (ctx.table.tiers.isEmpty()) {
                        add("")
                        add("<dark_gray>이 몬스터에는 아직 티어가 없습니다.</dark_gray>")
                        add("<dark_gray>드랍 규칙 화면에서 먼저 만드세요.</dark_gray>")
                    } else {
                        add("<gray>티어마다 별도의 배출 개수로 뽑힙니다.</gray>")
                        add("")
                        ctx.table.tiers.forEach { (name, tier) ->
                            val marker = if (name == drop.tier) "<green>▶</green>" else "<dark_gray>·</dark_gray>"
                            add(marker + " <dark_gray>" + name + " (" + tier.minRolls + "~" + tier.maxRolls + "개)</dark_gray>")
                        }
                        add("")
                        add("<yellow>▶ 좌클릭: 다음 티어</yellow>")
                        add("<red>▶ 우클릭: 기본 풀로</red>")
                    }
                },
            ),
        ) { event ->
            if (ctx.table.tiers.isEmpty()) return@set
            drop.tier = if (event.isRightClick) null else nextTier(drop.tier)
            save(); redraw(event.whoClicked)
        }

        set(
            38,
            Editors.intIcon(
                Material.EXPERIENCE_BOTTLE, "<yellow>최소 몬스터 레벨</yellow>", drop.minLevel,
                extra = listOf(
                    "<dark_gray>몬스터 레벨이 이 값 이상일 때만 나옵니다.</dark_gray>",
                    "<dark_gray>0 이면 제한 없음.</dark_gray>",
                ),
            ),
        ) { event ->
            drop.minLevel = (drop.minLevel + Editors.step(event, 1)).coerceAtLeast(0)
            save(); redraw(event.whoClicked)
        }

        set(
            39,
            Icon.of(
                Material.DIAMOND_PICKAXE, "<yellow>필요 도구</yellow>",
                buildList {
                    if (drop.requiredTools.isEmpty()) {
                        add("<gray>제한 없음 - 어떤 도구로 잡아도 나옵니다.</gray>")
                    } else {
                        drop.requiredTools.forEach { add("<dark_gray> · " + it + "</dark_gray>") }
                    }
                    add("")
                    add("<gray>처치한 플레이어가 손에 들고 있어야 하는</gray>")
                    add("<gray>아이템 종류입니다.</gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "필요한 도구 종류를 입력하세요. (쉼표로 여러 개)",
                listOf(
                    "<gray>예: <white>DIAMOND_SWORD, NETHERITE_SWORD</white></gray>",
                    "<gray>'없음' 을 입력하면 제한을 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                drop.requiredTools = if (input.trim() == "없음") {
                    mutableListOf()
                } else {
                    input.split(',').map { it.trim().uppercase() }
                        .filter { Material.matchMaterial(it) != null }.toMutableList()
                }
                save()
            }
        }

        set(45, Icon.back()) { event -> reopenList(event.whoClicked) }

        set(
            53,
            Icon.of(
                Material.TNT, "<red>이 드랍 삭제</red>",
                "<gray>목록에서 제거합니다.</gray>",
                "",
                "<red>▶ Shift+클릭</red>",
            ),
        ) { event ->
            if (!event.isShiftClick) return@set
            ctx.table.entries.remove(drop)
            save()
            reopenList(event.whoClicked)
        }
    }

    private fun refLabel(ref: ItemRef): String = when (ref) {
        is ItemRef.MMOItems -> "MMOItems"
        is ItemRef.Namespaced -> when (ref.namespace) {
            "itemsadder" -> "ItemsAdder"
            "nexo" -> "Nexo"
            "oraxen" -> "Oraxen"
            "ecoitems" -> "EcoItems"
            else -> ref.namespace
        }

        is ItemRef.Vanilla -> "바닐라"
        is ItemRef.None -> "스냅샷 전용"
    }

    private fun nextTier(current: String?): String? {
        val names = ctx.table.tiers.keys.toList()
        if (names.isEmpty()) return null
        val index = names.indexOf(current)
        if (index < 0) return names.first()
        return names.getOrNull(index + 1)
    }

    private fun chanceButton(slot: Int, delta: Double) {
        set(
            slot,
            Icon.of(
                Material.LIGHT_BLUE_DYE, "<aqua>확률 ±" + Numbers.chance(delta) + "%</aqua>",
                "<gray>현재: <yellow>" + Numbers.chance(drop.chance) + "%</yellow></gray>",
                "",
                "<yellow>▶ 좌클릭: +" + Numbers.chance(delta) + "</yellow>",
                "<yellow>▶ 우클릭: -" + Numbers.chance(delta) + "</yellow>",
            ),
        ) { event ->
            drop.chance += if (event.isLeftClick) delta else -delta
            save(); redraw(event.whoClicked)
        }
    }

    private fun previewIcon(): org.bukkit.inventory.ItemStack {
        val icon = monsters.itemResolver.icon(drop.item)
        return Icon.annotate(
            icon.stack,
            lore = listOf(
                "<gray>확률: <yellow>" + Numbers.chance(drop.chance) + "%</yellow></gray>",
                "<gray>수량: <white>" + drop.minAmount + " ~ " + drop.maxAmount + "개</white></gray>",
                "<gray>티어: <white>" + (drop.tier ?: "기본 풀") + "</white></gray>",
                "<dark_gray>" + drop.item.ref.serialize() + "</dark_gray>",
            ) + icon.notes(),
        )
    }

    private fun save() = ctx.save()

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = DropDetailMenu(monsters, ctx, drop, returnPage).open(player)

    private fun reopenList(who: org.bukkit.entity.HumanEntity) {
        (who as? Player)?.let { DropListMenu(monsters, ctx, returnPage).open(it) }
    }

    companion object {
        private val TITLE = Text.renderFlat("<dark_gray>드랍 상세 설정</dark_gray>")
    }
}

/** Table-wide drop settings: roll counts, distribution, exp and tiers. */
class DropRulesMenu(
    monsters: Monsters,
    private val ctx: DropContext,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>드랍 규칙</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val table = ctx.table

        set(
            10,
            Editors.intIcon(
                Material.IRON_NUGGET, "<yellow>최소 배출 개수</yellow>", table.minRolls, "종",
                extra = listOf(
                    "<dark_gray>당첨된 항목이 이보다 적으면 확률에 비례해</dark_gray>",
                    "<dark_gray>부족한 만큼 채웁니다. 0 이면 아무것도 안 나올 수 있습니다.</dark_gray>",
                ),
            ),
        ) { event ->
            table.minRolls = (table.minRolls + Editors.step(event, 1)).coerceIn(0, 64)
            if (table.minRolls > table.maxRolls) table.maxRolls = table.minRolls
            save(); redraw(event.whoClicked)
        }

        set(
            11,
            Editors.intIcon(
                Material.GOLD_NUGGET, "<yellow>최대 배출 개수</yellow>", table.maxRolls, "종",
                extra = listOf("<dark_gray>당첨이 이보다 많으면 무작위로 잘립니다.</dark_gray>"),
            ),
        ) { event ->
            table.maxRolls = (table.maxRolls + Editors.step(event, 1)).coerceIn(1, 64)
            if (table.maxRolls < table.minRolls) table.minRolls = table.maxRolls
            save(); redraw(event.whoClicked)
        }

        set(
            13,
            Icon.of(
                Material.PLAYER_HEAD, "<yellow>분배 방식</yellow>",
                buildList {
                    add("<gray>현재: <white>" + table.distribution.label + "</white></gray>")
                    add("<dark_gray>" + table.distribution.description + "</dark_gray>")
                    add("")
                    addAll(
                        Editors.optionList(DropDistribution.entries.toList(), table.distribution) { it.label },
                    )
                    add("")
                    add("<gray>보스는 막타보다 기여도 기준이 분쟁이 적습니다.</gray>")
                    addAll(Editors.cycleHint)
                },
            ),
        ) { event ->
            table.distribution =
                Editors.cycle(event, DropDistribution.entries.toList(), table.distribution)
            save(); redraw(event.whoClicked)
        }

        set(
            15,
            Icon.of(
                if (table.requirePlayerKill) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>플레이어 처치 필요</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(table.requirePlayerKill),
                "<dark_gray>끄면 용암·다른 몬스터에게 죽어도 드랍합니다.</dark_gray>",
                "<red>⚠ 끄면 몬스터를 함정에 몰아넣는 파밍이 가능해집니다.</red>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            table.requirePlayerKill = !table.requirePlayerKill
            save(); redraw(event.whoClicked)
        }

        set(
            16,
            Icon.of(
                if (table.keepVanillaDrops) Material.ROTTEN_FLESH else Material.BARRIER,
                "<yellow>바닐라 드랍 유지</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(table.keepVanillaDrops),
                "<dark_gray>끄면 썩은 고기 같은 기본 드랍이 사라지고</dark_gray>",
                "<dark_gray>여기 등록한 것만 나옵니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            table.keepVanillaDrops = !table.keepVanillaDrops
            save(); redraw(event.whoClicked)
        }

        set(
            19,
            Editors.intIcon(Material.EXPERIENCE_BOTTLE, "<yellow>최소 경험치</yellow>", table.expMin, stepLabel = "5"),
        ) { event ->
            table.expMin = (table.expMin + Editors.step(event, 5)).coerceAtLeast(0)
            if (table.expMin > table.expMax) table.expMax = table.expMin
            save(); redraw(event.whoClicked)
        }

        set(
            20,
            Editors.intIcon(Material.EXPERIENCE_BOTTLE, "<yellow>최대 경험치</yellow>", table.expMax, stepLabel = "5"),
        ) { event ->
            table.expMax = (table.expMax + Editors.step(event, 5)).coerceAtLeast(table.expMin)
            save(); redraw(event.whoClicked)
        }

        set(
            22,
            Editors.numberIcon(
                Material.COMPARATOR, "<yellow>확률 배율</yellow>", table.chanceMultiplier, "배",
                extra = listOf(
                    "<dark_gray>모든 항목의 확률에 곱해집니다.</dark_gray>",
                    "<dark_gray>개별 확률을 건드리지 않고 전체 후함만 조절합니다.</dark_gray>",
                ),
                stepLabel = "0.1",
            ),
        ) { event ->
            table.chanceMultiplier =
                (table.chanceMultiplier + Editors.step(event, 0.1)).coerceIn(0.0, 100.0)
            save(); redraw(event.whoClicked)
        }

        set(
            24,
            Editors.intIcon(
                Material.SHIELD, "<yellow>드랍 보호 시간</yellow>", table.protectSeconds, "초",
                extra = listOf(
                    "<dark_gray>이 시간 동안은 획득자만 주울 수 있습니다.</dark_gray>",
                    "<dark_gray>0 이면 보호하지 않습니다.</dark_gray>",
                ),
            ),
        ) { event ->
            table.protectSeconds = (table.protectSeconds + Editors.step(event, 1)).coerceIn(0, 300)
            save(); redraw(event.whoClicked)
        }

        set(
            31,
            Icon.of(
                Material.BUNDLE, "<yellow>티어 관리</yellow>",
                buildList {
                    if (table.tiers.isEmpty()) {
                        add("<gray>등록된 티어가 없습니다.</gray>")
                        add("<dark_gray>'희귀 1개 + 일반 0~3개' 처럼 배출 개수를</dark_gray>")
                        add("<dark_gray>따로 굴리고 싶을 때 만듭니다.</dark_gray>")
                    } else {
                        table.tiers.forEach { (name, tier) ->
                            add("<gray>" + name + ": <white>" + tier.minRolls + " ~ " + tier.maxRolls + "개</white></gray>")
                        }
                    }
                    add("")
                    add("<yellow>▶ 클릭하여 편집</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "티어를 입력하세요.",
                listOf(
                    "<gray>형식: <white>이름 최소 최대</white>  (여러 개는 | 로 구분)</gray>",
                    "<gray>예: <white>희귀 1 1|일반 0 3</white></gray>",
                    "<gray>'없음' 을 입력하면 모두 지웁니다.</gray>",
                ),
                reopen = { DropRulesMenu(monsters, ctx).open(player) },
            ) { input ->
                table.tiers.clear()
                if (input.trim() != "없음") {
                    for (part in input.split('|')) {
                        val bits = part.trim().split(' ').filter { it.isNotBlank() }
                        if (bits.size < 3) continue
                        val min = bits[1].toIntOrNull() ?: continue
                        val max = bits[2].toIntOrNull() ?: continue
                        table.tiers[bits[0]] = com.inmc.monster.mob.DropTier(
                            minRolls = min.coerceAtLeast(0),
                            maxRolls = max.coerceAtLeast(1),
                        )
                    }
                }
                save()
            }
        }

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { DropListMenu(monsters, ctx).open(it) }
        }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun save() = ctx.save()

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }
}
