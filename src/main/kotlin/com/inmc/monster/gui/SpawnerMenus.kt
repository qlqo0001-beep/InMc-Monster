package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.spawn.Spawner
import com.inmc.monster.spawn.SpawnerEntry
import com.inmc.monster.spawn.SpawnerRegistry
import com.inmc.monster.spawn.SpawnerShape
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * Every spawner on the server, region-based and block-bound alike.
 *
 * Both kinds share one screen because they differ only in where the centre comes from - a saved
 * coordinate or a block someone placed - and an admin thinks of them as the same feature.
 */
class SpawnerListMenu(
    monsters: Monsters,
    private var page: Int = 0,
) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val all = monsters.spawners.all()
        val pages = Paging.pageCount(all.size, CONTENT)
        page = page.coerceIn(0, pages - 1)

        Paging.slice(all, page, CONTENT).forEachIndexed { index, spawner ->
            set(index, iconFor(spawner)) { event ->
                val player = event.whoClicked as? Player ?: return@set
                when (event.click) {
                    ClickType.SHIFT_RIGHT -> ConfirmMenu(
                        monsters,
                        question = "<red>'" + spawner.id + "' 스포너를 삭제할까요?</red>",
                        detail = listOf("<gray>이미 소환된 몬스터는 그대로 남습니다.</gray>"),
                        onConfirm = {
                            monsters.spawners.delete(spawner.id)
                            SpawnerListMenu(monsters, page).open(player)
                        },
                        onCancel = { SpawnerListMenu(monsters, page).open(player) },
                    ).open(player)

                    // Q is unreliable on this server, so right-click does the same thing.
                    ClickType.DROP, ClickType.RIGHT -> {
                        spawner.enabled = !spawner.enabled
                        monsters.spawners.markDirty(spawner)
                        redraw(player)
                    }

                    ClickType.SWAP_OFFHAND, ClickType.MIDDLE, ClickType.SHIFT_LEFT -> {
                        val centre = spawner.centre()
                        if (centre == null) {
                            player.sendMessage(Text.render("<red>이 스포너의 월드를 찾을 수 없습니다.</red>"))
                        } else {
                            player.closeInventory()
                            player.teleport(centre)
                            player.sendMessage(Text.render("<green>'" + spawner.id + "' 스포너 위치로 이동했습니다.</green>"))
                        }
                    }

                    else -> SpawnerDetailMenu(monsters, spawner, page).open(player)
                }
            }
        }

        for (slot in CONTENT until 54) set(slot, Icon.EDGE)

        set(Paging.SLOT_BACK, Icon.back()) { event -> (event.whoClicked as? Player)?.let { MainMenu(monsters).open(it) } }
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { event -> switch(event.whoClicked, page - 1) }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { event -> switch(event.whoClicked, page + 1) }

        set(
            48,
            Icon.of(
                Material.COMPASS, "<green>+ 지역 스포너 만들기</green>",
                "<gray>지금 서 있는 자리를 중심으로 만듭니다.</gray>",
                "<dark_gray>좌표·반경 기반이며 던전에도 그대로 씁니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event -> promptCreate(event, blockBound = false) }

        set(
            50,
            Icon.of(
                Material.SPAWNER, "<green>+ 블록 스포너 만들기</green>",
                "<gray>지금 보고 있는 블록에 묶습니다.</gray>",
                "<dark_gray>그 블록이 파괴되면 스포너도 사라집니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event -> promptCreate(event, blockBound = true) }

        set(
            51,
            Icon.of(
                Material.PAPER, "<yellow>도움말</yellow>",
                "<gray>등록된 스포너: <white>" + monsters.spawners.size + "개</white></gray>",
                "",
                "<yellow>좌클릭</yellow><gray> : 설정</gray>",
                "<yellow>우클릭</yellow><gray> : 켜기/끄기</gray>",
                "<yellow>Shift+좌클릭</yellow><gray> : 그 위치로 이동</gray>",
                "<red>Shift+우클릭</red><gray> : 삭제</gray>",
                "",
                "<dark_gray>근처에 플레이어가 없으면 스포너는 아예 돌지 않습니다.</dark_gray>",
            ),
        )

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun iconFor(spawner: Spawner): org.bukkit.inventory.ItemStack {
        val tag = spawner.tag.ifBlank { SpawnerRegistry.SPAWNER_TAG_PREFIX + spawner.id }
        val alive = monsters.tracker.countWithTag(tag)
        return Icon.of(
            when {
                !spawner.enabled -> Material.GRAY_DYE
                spawner.blockBound -> Material.SPAWNER
                else -> Material.COMPASS
            },
            "<yellow>" + spawner.id + "</yellow>" +
                (if (spawner.blockBound) " <dark_gray>(블록)</dark_gray>" else ""),
            buildList {
                add(
                    "<gray>위치: <white>" + spawner.world + " " +
                        spawner.x.toInt() + ", " + spawner.y.toInt() + ", " + spawner.z.toInt() + "</white></gray>",
                )
                add("<gray>형태: <white>" + spawner.shape.label + "</white>" +
                    (if (spawner.shape == SpawnerShape.RADIUS) " <dark_gray>(반경 " + Numbers.chance(spawner.radius) + ")</dark_gray>" else "") +
                    "</gray>")
                add("<gray>주기: <white>" + spawner.intervalSeconds + "초</white>" +
                    "   확률: <white>" + Numbers.chance(spawner.chance) + "%</white></gray>")
                add("<gray>동시 최대: <white>" + spawner.maxAlive + "마리</white>" +
                    "   현재: <yellow>" + alive + "</yellow></gray>")
                add("<gray>활성 거리: <white>" + Numbers.chance(spawner.activationRange) + "</white></gray>")
                if (spawner.entries.isEmpty()) {
                    add("<red>⚠ 소환할 몬스터가 지정되지 않았습니다.</red>")
                } else {
                    add("<gray>소환 대상:</gray>")
                    spawner.entries.take(4).forEach {
                        add("<dark_gray> · " + it.mobId + " (가중치 " + it.weight + ", " + it.amount + "마리)</dark_gray>")
                    }
                    if (spawner.entries.size > 4) add("<dark_gray> · ...</dark_gray>")
                }
                if (!spawner.enabled) {
                    add("")
                    add("<red>비활성화됨</red>")
                }
                add("")
                add("<yellow>▶ 좌클릭: 설정   우클릭: 켜기/끄기</yellow>")
                add("<yellow>▶ Shift+좌클릭: 그 위치로 이동</yellow>")
                add("<red>▶ Shift+우클릭: 삭제</red>")
            },
        )
    }

    private fun promptCreate(event: org.bukkit.event.inventory.InventoryClickEvent, blockBound: Boolean) {
        val player = event.whoClicked as? Player ?: return

        val location = if (blockBound) {
            val target = player.getTargetBlockExact(8)
            if (target == null) {
                player.sendMessage(Text.render("<red>8블록 안에서 바라보는 블록이 없습니다.</red>"))
                return
            }
            target.location
        } else {
            player.location
        }

        Editors.promptText(
            monsters.prompts, player, "스포너 이름을 입력하세요.",
            listOf(
                "<gray>한글/영문/숫자/_/- 32자 이내</gray>",
                "<gray>위치: <white>" + location.world?.name + " " +
                    location.blockX + ", " + location.blockY + ", " + location.blockZ + "</white></gray>",
            ),
            reopen = { SpawnerListMenu(monsters, page).open(player) },
        ) { input ->
            val name = input.trim()
            if (!SpawnerRegistry.isValidId(name)) {
                player.sendMessage(Text.render("<red>사용할 수 없는 이름입니다.</red>"))
                return@promptText
            }
            val created = monsters.spawners.create(name, location, blockBound)
            if (created == null) {
                player.sendMessage(Text.render("<red>이미 존재하는 스포너입니다.</red>"))
                return@promptText
            }
            if (blockBound) created.blockMaterial = location.block.type
            monsters.spawners.markDirty(created)
            SpawnerDetailMenu(monsters, created, page).open(player)
        }
    }

    private fun switch(who: org.bukkit.entity.HumanEntity, target: Int) {
        (who as? Player)?.let { SpawnerListMenu(monsters, target).open(it) }
    }

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    companion object {
        private const val CONTENT = 45
        private val TITLE = Text.renderFlat("<dark_gray>스포너 관리</dark_gray>")
    }
}

/** One spawner's timing, capacity, shape and mob list. */
class SpawnerDetailMenu(
    monsters: Monsters,
    private val spawner: Spawner,
    private val returnPage: Int,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>스포너 설정</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(
            4,
            Icon.of(
                if (spawner.blockBound) Material.SPAWNER else Material.COMPASS,
                "<gold>" + spawner.id + "</gold>",
                buildList {
                    add(
                        "<gray>" + spawner.world + " " + spawner.x.toInt() + ", " +
                            spawner.y.toInt() + ", " + spawner.z.toInt() + "</gray>",
                    )
                    if (spawner.blockBound) add("<dark_gray>블록에 묶인 스포너입니다.</dark_gray>")
                    if (!spawner.isValid()) {
                        add("")
                        add("<red>⚠ 소환할 몬스터가 없어 동작하지 않습니다.</red>")
                    }
                },
            ),
        )

        set(
            19,
            Editors.intIcon(
                Material.CLOCK, "<yellow>소환 주기</yellow>", spawner.intervalSeconds, "초",
                stepLabel = "5",
            ),
        ) { event ->
            if (Editors.isPrompt(event)) {
                val player = event.whoClicked as? Player ?: return@set
                Editors.promptInt(monsters.prompts, player, "소환 주기 (초)", 1, 86_400, { reopen(player) }) {
                    spawner.intervalSeconds = it
                    save()
                }
                return@set
            }
            spawner.intervalSeconds = (spawner.intervalSeconds + Editors.step(event, 5)).coerceIn(1, 86_400)
            save(); redraw(event.whoClicked)
        }

        set(
            20,
            Editors.numberIcon(
                Material.LIGHT_BLUE_DYE, "<yellow>소환 확률</yellow>", spawner.chance, "%",
                extra = listOf("<dark_gray>주기가 돌아왔을 때 실제로 소환할 확률입니다.</dark_gray>"),
                stepLabel = "5",
            ),
        ) { event ->
            spawner.chance = (spawner.chance + Editors.step(event, 5.0)).coerceIn(0.01, 100.0)
            save(); redraw(event.whoClicked)
        }

        set(
            21,
            Editors.intIcon(
                Material.BARRIER, "<yellow>동시 최대 마리</yellow>", spawner.maxAlive, "마리",
                extra = listOf(
                    "<dark_gray>이 스포너가 만든 몬스터가 이만큼 살아 있으면</dark_gray>",
                    "<dark_gray>더 소환하지 않습니다.</dark_gray>",
                ),
            ),
        ) { event ->
            spawner.maxAlive = (spawner.maxAlive + Editors.step(event, 1)).coerceIn(1, 200)
            save(); redraw(event.whoClicked)
        }

        set(
            22,
            Editors.intIcon(Material.IRON_NUGGET, "<yellow>1회 최소 소환</yellow>", spawner.perAttemptMin, "마리"),
        ) { event ->
            spawner.perAttemptMin = (spawner.perAttemptMin + Editors.step(event, 1)).coerceIn(1, 50)
            if (spawner.perAttemptMin > spawner.perAttemptMax) spawner.perAttemptMax = spawner.perAttemptMin
            save(); redraw(event.whoClicked)
        }

        set(
            23,
            Editors.intIcon(Material.GOLD_NUGGET, "<yellow>1회 최대 소환</yellow>", spawner.perAttemptMax, "마리"),
        ) { event ->
            spawner.perAttemptMax = (spawner.perAttemptMax + Editors.step(event, 1)).coerceIn(1, 50)
            if (spawner.perAttemptMax < spawner.perAttemptMin) spawner.perAttemptMin = spawner.perAttemptMax
            save(); redraw(event.whoClicked)
        }

        set(
            24,
            Editors.numberIcon(
                Material.SPYGLASS, "<yellow>활성 거리</yellow>", spawner.activationRange, "블록",
                extra = listOf(
                    "<gray>이 거리 안에 플레이어가 없으면 아예 돌지 않습니다.</gray>",
                    "<dark_gray>0 으로 두면 항상 동작합니다 - 스포너가 많은 서버에서는</dark_gray>",
                    "<dark_gray>아무도 없는 청크를 채우느라 틱을 낭비하게 됩니다.</dark_gray>",
                ),
                stepLabel = "8",
            ),
        ) { event ->
            spawner.activationRange =
                (spawner.activationRange + Editors.step(event, 8.0)).coerceIn(0.0, 256.0)
            save(); redraw(event.whoClicked)
        }

        set(
            25,
            Editors.numberIcon(
                Material.SHIELD, "<yellow>최소 플레이어 거리</yellow>", spawner.minPlayerDistance, "블록",
                extra = listOf("<dark_gray>이보다 가까우면 소환하지 않습니다.</dark_gray>"),
            ),
        ) { event ->
            spawner.minPlayerDistance =
                (spawner.minPlayerDistance + Editors.step(event, 1.0)).coerceIn(0.0, 128.0)
            save(); redraw(event.whoClicked)
        }

        set(
            28,
            Icon.of(
                Material.STRUCTURE_BLOCK, "<yellow>생성 형태</yellow>",
                buildList {
                    add("<gray>현재: <white>" + spawner.shape.label + "</white></gray>")
                    when (spawner.shape) {
                        SpawnerShape.RADIUS -> add("<gray>반경: <white>" + Numbers.chance(spawner.radius) + "</white></gray>")
                        SpawnerShape.BOX -> add(
                            "<gray>두 번째 지점: <white>" + spawner.x2.toInt() + ", " +
                                spawner.y2.toInt() + ", " + spawner.z2.toInt() + "</white></gray>",
                        )

                        SpawnerShape.POINT -> add("<dark_gray>정확히 이 좌표에만 생성됩니다.</dark_gray>")
                    }
                    add("")
                    addAll(Editors.optionList(SpawnerShape.entries.toList(), spawner.shape) { it.label })
                    addAll(Editors.cycleHint)
                    if (spawner.shape == SpawnerShape.BOX) {
                        add("<dark_gray>숫자키: 지금 서 있는 자리를 두 번째 지점으로</dark_gray>")
                    }
                },
            ),
        ) { event ->
            if (Editors.isPrompt(event) && spawner.shape == SpawnerShape.BOX) {
                val player = event.whoClicked as? Player ?: return@set
                spawner.x2 = player.location.x
                spawner.y2 = player.location.y
                spawner.z2 = player.location.z
                save(); redraw(player)
                return@set
            }
            spawner.shape = Editors.cycle(event, SpawnerShape.entries.toList(), spawner.shape)
            save(); redraw(event.whoClicked)
        }

        set(
            29,
            Editors.numberIcon(
                Material.ENDER_EYE, "<yellow>생성 반경</yellow>", spawner.radius, "블록",
                stepLabel = "2",
            ),
        ) { event ->
            spawner.radius = (spawner.radius + Editors.step(event, 2.0)).coerceIn(0.0, 128.0)
            save(); redraw(event.whoClicked)
        }

        set(
            31,
            Icon.of(
                Material.ZOMBIE_SPAWN_EGG, "<green>소환할 몬스터</green>",
                buildList {
                    if (spawner.entries.isEmpty()) {
                        add("<red>지정된 몬스터가 없습니다.</red>")
                        add("<dark_gray>하나 이상 지정해야 스포너가 동작합니다.</dark_gray>")
                    } else {
                        spawner.entries.forEach {
                            add("<gray>" + it.mobId + " <dark_gray>(가중치 " + it.weight + ", " + it.amount + "마리)</dark_gray></gray>")
                        }
                    }
                    add("")
                    add("<yellow>▶ 클릭하여 편집</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "소환할 몬스터를 입력하세요.",
                listOf(
                    "<gray>형식: <white>몹이름 가중치 마리수</white>  (여러 개는 | 로 구분)</gray>",
                    "<gray>예: <white>부패한기사 10 1|썩은병사 30 2</white></gray>",
                    "<gray>가중치와 마리수는 생략하면 10 과 1 입니다.</gray>",
                    "<gray>등록된 몬스터: " + monsters.mobs.ids().take(8).joinToString(", ") + "</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                spawner.entries.clear()
                for (part in input.split('|')) {
                    val bits = part.trim().split(' ').filter { it.isNotBlank() }
                    if (bits.isEmpty()) continue
                    val mobId = bits[0]
                    if (!monsters.mobs.exists(mobId)) {
                        player.sendMessage(Text.render("<yellow>'" + mobId + "' 몬스터를 찾을 수 없어 건너뜁니다.</yellow>"))
                        continue
                    }
                    spawner.entries.add(
                        SpawnerEntry(
                            mobId = mobId,
                            weight = bits.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 10_000) ?: 10,
                            amount = bits.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 20) ?: 1,
                        ),
                    )
                }
                save()
            }
        }

        set(
            33,
            Icon.of(
                Material.COMPASS, "<green>생성 조건</green>",
                "<gray>시간·날씨·바이옴·밝기 등을 지정합니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            SpawnRulesMenu(
                monsters, spawner.rules, spawner.id,
                onSave = { save() },
                onBack = { SpawnerDetailMenu(monsters, spawner, returnPage).open(it) },
            ).open(player)
        }

        set(
            37,
            Icon.of(
                Material.FLOWER_BANNER_PATTERN, "<yellow>태그</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            spawner.tag.ifBlank { SpawnerRegistry.SPAWNER_TAG_PREFIX + spawner.id } +
                            "</white></gray>",
                    )
                    add("<dark_gray>이 스포너가 만든 몬스터에 붙는 표식입니다.</dark_gray>")
                    add("<dark_gray>/몹 정리 [태그] 로 한 번에 치울 수 있습니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 좌클릭: 입력   우클릭: 기본값으로</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) {
                spawner.tag = ""
                save(); redraw(player)
                return@set
            }
            Editors.promptText(
                monsters.prompts, player, "태그를 입력하세요.",
                listOf("<dark_gray>예: dungeon:room3</dark_gray>"),
                reopen = { reopen(player) },
            ) { input ->
                spawner.tag = input.trim()
                save()
            }
        }

        set(
            39,
            Icon.of(
                if (spawner.enabled) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>스포너 활성화</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(spawner.enabled),
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            spawner.enabled = !spawner.enabled
            save(); redraw(event.whoClicked)
        }

        set(
            41,
            Icon.of(
                Material.ENDER_PEARL, "<yellow>이 위치로 이동</yellow>",
                "<gray>스포너 중심으로 텔레포트합니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            val centre = spawner.centre()
            if (centre == null) {
                player.sendMessage(Text.render("<red>이 스포너의 월드를 찾을 수 없습니다.</red>"))
                return@set
            }
            player.closeInventory()
            player.teleport(centre)
        }

        set(
            43,
            Icon.of(
                Material.PLAYER_HEAD, "<yellow>현재 위치로 이동시키기</yellow>",
                "<gray>스포너 중심을 지금 내 자리로 옮깁니다.</gray>",
                "",
                "<red>▶ Shift+클릭</red>",
            ),
        ) { event ->
            if (!event.isShiftClick) return@set
            val player = event.whoClicked as? Player ?: return@set
            spawner.world = player.world.name
            spawner.x = player.location.x
            spawner.y = player.location.y
            spawner.z = player.location.z
            monsters.spawners.rebuildBlockIndex()
            save(); redraw(player)
        }

        set(Paging.SLOT_BACK, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { SpawnerListMenu(monsters, returnPage).open(it) }
        }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun save() = monsters.spawners.markDirty(spawner)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = SpawnerDetailMenu(monsters, spawner, returnPage).open(player)
}
