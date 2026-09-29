package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.mob.MobDefinition
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Behaviour switches.
 *
 * Every one of these maps onto a single Bukkit setter applied once at spawn - nothing here
 * needs a repeating task, which is why they are all cheap enough to expose freely.
 */
class FlagsMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val flags = definition.flags

        toggle(
            10, Material.EGG, "아기 형태", flags.baby,
            listOf("<dark_gray>좀비·주민 등 성장 단계가 있는 몬스터에만 적용됩니다.</dark_gray>"),
        ) { flags.baby = it }

        toggle(
            11, Material.NOTE_BLOCK, "소리 끄기", flags.silent,
            listOf("<dark_gray>몬스터가 내는 기본 소리를 없앱니다.</dark_gray>"),
        ) { flags.silent = it }

        toggle(
            12, Material.GLOWSTONE_DUST, "발광 효과", flags.glowing,
            listOf("<dark_gray>벽 너머로도 윤곽선이 보입니다.</dark_gray>"),
        ) { flags.glowing = it }

        toggle(
            13, Material.POTION, "투명화", flags.invisible,
            listOf(
                "<dark_gray>장비는 그대로 보입니다.</dark_gray>",
                "<dark_gray>ModelEngine 모델을 쓸 때 기본 엔티티를 숨기는 것과는 별개입니다.</dark_gray>",
            ),
        ) { flags.invisible = it }

        toggle(
            14, Material.REDSTONE, "AI 활성화", flags.aiEnabled,
            listOf(
                "<dark_gray>끄면 움직이지 않는 장식용 몬스터가 됩니다.</dark_gray>",
                "<dark_gray>스킬은 AI 와 무관하게 계속 발동합니다.</dark_gray>",
            ),
        ) { flags.aiEnabled = it }

        toggle(
            15, Material.ENDER_EYE, "주변 인식", flags.aware,
            listOf("<dark_gray>끄면 플레이어를 먼저 공격하지 않습니다.</dark_gray>"),
        ) { flags.aware = it }

        toggle(
            16, Material.HOPPER, "아이템 줍기", flags.canPickupItems,
            listOf("<red>⚠ 켜면 바닥의 장비를 주워 착용합니다.</red>"),
        ) { flags.canPickupItems = it }

        toggle(
            19, Material.SHIELD, "충돌 판정", flags.collidable,
            listOf("<dark_gray>끄면 플레이어와 겹쳐 지나갈 수 있습니다.</dark_gray>"),
        ) { flags.collidable = it }

        toggle(
            20, Material.FEATHER, "중력", flags.gravity,
            listOf("<dark_gray>끄면 공중에 떠 있습니다.</dark_gray>"),
        ) { flags.gravity = it }

        toggle(
            21, Material.CAMPFIRE, "햇빛에 연소", flags.burnInSunlight,
            listOf(
                "<dark_gray>좀비·스켈레톤·팬텀 계열에만 적용됩니다.</dark_gray>",
                "<dark_gray>끄면 낮에도 타지 않습니다.</dark_gray>",
            ),
        ) { flags.burnInSunlight = it }

        toggle(
            22, Material.NAME_TAG, "이름표 표시", flags.showNameplate,
            listOf("<dark_gray>전역 설정에서 이름표를 끄면 이 값과 무관하게 표시되지 않습니다.</dark_gray>"),
        ) { flags.showNameplate = it }

        toggle(
            23, Material.ITEM_FRAME, "이름표 항상 보이기", flags.alwaysShowName,
            listOf("<dark_gray>끄면 조준했을 때만 이름이 보입니다.</dark_gray>"),
        ) { flags.alwaysShowName = it }

        toggle(
            24, Material.DRAGON_EGG, "보스바 표시", flags.bossBar,
            listOf(
                "<dark_gray>주변 플레이어에게 체력 바를 띄웁니다.</dark_gray>",
                "<dark_gray>페이즈 이름이 함께 표시됩니다.</dark_gray>",
            ),
        ) { flags.bossBar = it }

        toggle(
            25, Material.PLAYER_HEAD, "플레이어만 공격", flags.targetPlayersOnly,
            listOf("<dark_gray>주민·골렘·다른 몬스터를 노리지 않습니다.</dark_gray>"),
        ) { flags.targetPlayersOnly = it }

        toggle(
            30, Material.BARRIER, "멀어지면 소멸", flags.removeWhenFarAway,
            listOf(
                "<gray>바닐라의 기본 동작입니다.</gray>",
                "<red>⚠ 끄면 엔티티가 계속 쌓입니다. 보스에만 쓰세요.</red>",
                "<dark_gray>재시작 시에는 어느 쪽이든 제거됩니다.</dark_gray>",
            ),
        ) { flags.removeWhenFarAway = it }

        set(
            32,
            Editors.intIcon(
                Material.CLOCK, "<yellow>수명 (초)</yellow>", flags.lifespanSeconds, "초",
                extra = listOf(
                    "<dark_gray>0 이면 제한 없음.</dark_gray>",
                    "<dark_gray>소환수처럼 일정 시간 뒤 사라져야 하는</dark_gray>",
                    "<dark_gray>몬스터에 씁니다.</dark_gray>",
                ),
                stepLabel = "10",
            ),
        ) { event ->
            if (Editors.isPrompt(event)) {
                val player = event.whoClicked as? Player ?: return@set
                Editors.promptInt(monsters.prompts, player, "수명 (초)", 0, 86_400, { reopen(player) }) {
                    flags.lifespanSeconds = it
                    save()
                }
                return@set
            }
            flags.lifespanSeconds = (flags.lifespanSeconds + Editors.step(event, 10)).coerceIn(0, 86_400)
            save(); redraw(event.whoClicked)
        }

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { MobManageMenu(monsters, definition).open(it) }
        }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun toggle(
        slot: Int,
        material: Material,
        name: String,
        value: Boolean,
        detail: List<String>,
        setter: (Boolean) -> Unit,
    ) {
        set(
            slot,
            Icon.of(
                if (value) material else Material.GRAY_DYE,
                "<yellow>" + name + "</yellow>",
                listOf("<gray>현재: </gray>" + Icon.toggle(value)) + detail +
                    listOf("", "<yellow>▶ 클릭하여 전환</yellow>"),
            ),
        ) { event ->
            setter(!value)
            save(); redraw(event.whoClicked)
        }
    }

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = FlagsMenu(monsters, definition).open(player)

    companion object {
        private val TITLE = Text.renderFlat("<dark_gray>특성 / AI</dark_gray>")
    }
}
