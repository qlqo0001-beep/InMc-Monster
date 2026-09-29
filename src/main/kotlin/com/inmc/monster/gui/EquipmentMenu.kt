package com.inmc.monster.gui

import com.inmc.monster.Monsters
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.item.StorageMode
import com.inmc.monster.mob.EquipmentEntry
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.mob.MobEquipment
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * Dresses a mob: drop an item into a slot and confirm.
 *
 * Uses the same gesture as the random-box reward editor and the same storage - an [ItemRef]
 * with a snapshot fallback - so an MMOItems weapon stays a live reference and picks up later
 * edits instead of being frozen at the moment it was equipped.
 *
 * Drop chances default to zero and the screen says why. A mob wearing gear the economy does not
 * expect players to own will hand that gear over the first time somebody kills it, and by the
 * time anyone notices it is already on the market.
 */
class EquipmentMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
) : Menu(monsters, 54, TITLE) {

    /** Slot index in this window -> the equipment slot it edits. */
    private val slotMap: Map<Int, EquipmentSlot> = mapOf(
        20 to EquipmentSlot.HAND,
        21 to EquipmentSlot.OFF_HAND,
        23 to EquipmentSlot.HEAD,
        24 to EquipmentSlot.CHEST,
        25 to EquipmentSlot.LEGS,
        26 to EquipmentSlot.FEET,
    )

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(
            4,
            Icon.of(
                Material.ARMOR_STAND, "<yellow>장비 설정</yellow>",
                buildList {
                    add("<gray>아래 칸에 아이템을 올린 뒤 확인을 누르면</gray>")
                    add("<gray>해당 부위에 착용됩니다.</gray>")
                    add("")
                    if (monsters.mythicLib.isEnabled) {
                        add("<green>MythicLib 연동 중 - MMOItems 장비 스탯이</green>")
                        add("<green>몬스터의 공격력·치명타에 그대로 반영됩니다.</green>")
                    } else {
                        add("<yellow>MythicLib 이 없어 장비 스탯은 반영되지 않습니다.</yellow>")
                        add("<dark_gray>장비는 외형과 바닐라 효과로만 동작합니다.</dark_gray>")
                    }
                },
            ),
        )

        for ((windowSlot, equipmentSlot) in slotMap) {
            drawSlot(windowSlot, equipmentSlot)
            // The label sits directly above each slot so an empty slot is still identifiable.
            set(windowSlot - 9, labelIcon(equipmentSlot))
        }

        set(45, Icon.back()) { event ->
            val player = event.whoClicked as? Player ?: return@set
            returnStaged(player)
            MobManageMenu(monsters, definition).open(player)
        }

        set(
            48,
            Icon.of(
                Material.HOPPER, "<yellow>드랍 확률 일괄 0%</yellow>",
                "<gray>모든 부위의 드랍 확률을 0으로 되돌립니다.</gray>",
                "<dark_gray>고급 장비가 필드에 풀리는 사고를 되돌릴 때 씁니다.</dark_gray>",
                "",
                "<yellow>▶ Shift+클릭</yellow>",
            ),
        ) { event ->
            if (!event.isShiftClick) return@set
            definition.equipment.entries().forEach { (_, entry) -> entry.dropChance = 0.0 }
            save(); redraw(event.whoClicked)
        }

        set(
            53,
            Icon.confirm(
                "<green>✔ 착용 확정</green>",
                listOf("<gray>올려둔 아이템을 장비로 등록합니다.</gray>"),
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            applyStaged(player)
            save()
            EquipmentMenu(monsters, definition).open(player)
        }
    }

    private fun labelIcon(slot: EquipmentSlot) = Icon.of(
        Material.LIGHT_GRAY_STAINED_GLASS_PANE,
        "<gray>" + MobEquipment.label(slot) + "</gray>",
    )

    private fun drawSlot(windowSlot: Int, slot: EquipmentSlot) {
        val entry = definition.equipment[slot]
        if (entry == null) {
            inventory.setItem(windowSlot, null)
            action(windowSlot) { }
            return
        }

        val icon = monsters.itemResolver.icon(entry.item)
        val lore = mutableListOf(
            "<gray>부위: <white>" + MobEquipment.label(slot) + "</white></gray>",
            "<gray>드랍 확률: " +
                (if (entry.dropChance <= 0.0) "<green>0%</green>" else "<red>" + Numbers.chance(entry.dropChance) + "%</red>") +
                "</gray>",
            "<gray>저장 방식: <white>" +
                (if (entry.item.mode == StorageMode.REFERENCE) "참조 (자동 갱신)" else "스냅샷 (고정)") +
                "</white></gray>",
            "<dark_gray>" + entry.item.ref.serialize() + "</dark_gray>",
        )
        if (entry.dropChance > 0.0) {
            lore.add("")
            lore.add("<red>⚠ 이 장비는 처치 시 플레이어에게 떨어집니다.</red>")
        }
        lore.addAll(icon.notes())
        lore.add("")
        lore.add("<yellow>▶ 좌클릭: 드랍 확률 +1%  /  우클릭: -1%</yellow>")
        lore.add("<yellow>▶ 숫자키: 확률 직접 입력</yellow>")
        lore.add("<yellow>▶ Shift+좌클릭: 저장 방식 전환</yellow>")
        lore.add("<red>▶ Shift+우클릭: 장비 해제</red>")

        set(windowSlot, Icon.annotate(icon.stack, lore = lore)) { event ->
            val player = event.whoClicked as? Player ?: return@set
            when {
                Editors.isPrompt(event) -> Editors.promptDouble(
                    monsters.prompts, player, MobEquipment.label(slot) + " 드랍 확률", 0.0, 100.0, { reopen(player) },
                ) { value ->
                    entry.dropChance = value
                    if (value > 0.0) warnAboutDrops(player)
                    save()
                }

                event.click == org.bukkit.event.inventory.ClickType.DROP ||
                    event.click == org.bukkit.event.inventory.ClickType.SHIFT_LEFT -> {
                    entry.item = entry.item.withMode(entry.item.mode.toggle())
                    save(); redraw(player)
                }

                event.isShiftClick && event.isRightClick -> {
                    definition.equipment[slot] = null
                    save(); redraw(player)
                }

                else -> {
                    val before = entry.dropChance
                    entry.dropChance = (entry.dropChance + Editors.step(event, 1.0)).coerceIn(0.0, 100.0)
                    if (before <= 0.0 && entry.dropChance > 0.0) warnAboutDrops(player)
                    save(); redraw(player)
                }
            }
        }
    }

    private fun warnAboutDrops(player: Player) {
        player.sendMessage(
            Text.render(
                "<yellow>⚠ 드랍 확률을 0보다 크게 설정했습니다. 이 장비가 플레이어에게 넘어갑니다.</yellow>",
            ),
        )
    }

    /** Slots the admin may drop items into: the six equipment positions that are still empty. */
    override fun isSlotEditable(slot: Int): Boolean {
        val equipmentSlot = slotMap[slot] ?: return false
        return definition.equipment[equipmentSlot] == null
    }

    override fun acceptsShiftInsert(): Boolean = false

    override fun onClose(event: InventoryCloseEvent) {
        (event.player as? Player)?.let { returnStaged(it) }
    }

    /** Captures whatever the admin left sitting in an empty slot. */
    private fun applyStaged(player: Player) {
        var added = 0
        for ((windowSlot, equipmentSlot) in slotMap) {
            if (definition.equipment[equipmentSlot] != null) continue
            val stack = inventory.getItem(windowSlot) ?: continue
            if (stack.type.isAir) continue
            definition.equipment[equipmentSlot] = EquipmentEntry(
                item = monsters.itemResolver.capture(stack),
                dropChance = 0.0,
            )
            inventory.setItem(windowSlot, null)
            added++
        }
        if (added > 0) {
            player.sendMessage(Text.render("<green>장비 " + added + "개를 착용시켰습니다. <gray>(드랍 확률 0%)</gray></green>"))
        }
    }

    /** Gives back anything staged but not confirmed, so nothing is silently eaten. */
    private fun returnStaged(player: Player) {
        for ((windowSlot, equipmentSlot) in slotMap) {
            if (definition.equipment[equipmentSlot] != null) continue
            val stack = inventory.getItem(windowSlot) ?: continue
            if (stack.type.isAir) continue
            inventory.setItem(windowSlot, null)
            player.inventory.addItem(stack).values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }
        }
    }

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = EquipmentMenu(monsters, definition).open(player)

    companion object {
        private val TITLE = Text.renderFlat("<dark_gray>장비 설정</dark_gray>")
    }
}
