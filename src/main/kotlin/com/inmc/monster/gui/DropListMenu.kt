package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.item.StorageMode
import com.inmc.monster.mob.DropDistribution
import com.inmc.monster.mob.MobDrop
import com.inmc.monster.util.Numbers
import com.inmc.monster.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack

/**
 * Drop registration: drop items into the empty slots and press 확인.
 *
 * This is the random-box reward editor, reused wholesale. The gesture is identical because the
 * job is identical - name an item that will be handed out later - and so is the storage: an
 * [com.inmc.monster.item.ItemRef] with a snapshot fallback, rebuilt from its live definition
 * every time it drops. That is what stops an MMOItems reward from freezing at the values it had
 * on the day it was registered.
 *
 * Remove mode lifts icons out rather than handing them over: the icons are display copies, and
 * giving one to the admin would put a decorated fake into circulation.
 */
class DropListMenu(
    monsters: Monsters,
    private val ctx: DropContext,
    private var page: Int = 0,
    private var removeMode: Boolean = false,
) : Menu(monsters, SIZE, title(ctx)) {

    private val slotToDrop = HashMap<Int, MobDrop>()
    private val stagedRemovals = HashSet<Int>()

    override fun draw() {
        clear()
        slotToDrop.clear()

        val drops = ctx.table.entries
        val pages = maxOf(1, (drops.size + CONTENT_SIZE - 1) / CONTENT_SIZE)
        page = page.coerceIn(0, pages - 1)

        drops.drop(page * CONTENT_SIZE).take(CONTENT_SIZE).forEachIndexed { index, drop ->
            slotToDrop[index] = drop
            val icon = dropIcon(drop)
            set(index, if (removeMode && index in stagedRemovals) null else icon) { event ->
                val player = event.whoClicked as? Player ?: return@set
                if (removeMode) {
                    if (!stagedRemovals.add(index)) stagedRemovals.remove(index)
                    inventory.setItem(index, if (index in stagedRemovals) null else icon)
                    player.updateInventory()
                    return@set
                }
                // Any click on a registered drop opens its detail screen.
                //
                // This used to be bound to Q / F / shift-right only, which turned out not to be
                // reachable in practice - the keyboard click types did not arrive, and the one
                // combination that did was undiscoverable. A plain left-click cannot be missed
                // and the slot has nothing else to do: the icons are display copies, so clicking
                // one can never pick it up.
                DropDetailMenu(monsters, ctx, drop, page).open(player)
            }
        }

        for (slot in CONTENT_SIZE until SIZE) set(slot, Icon.EDGE)

        set(45, Icon.back()) { event ->
            val player = event.whoClicked as? Player ?: return@set
            returnStaged(player)
            ctx.back(player)
        }

        if (page > 0) set(46, Icon.prevPage()) { event -> switchPage(event.whoClicked, page - 1) }
        if (page < pages - 1) set(47, Icon.nextPage()) { event -> switchPage(event.whoClicked, page + 1) }

        set(
            48,
            Icon.of(
                Material.COMPARATOR, "<yellow>드랍 규칙</yellow>",
                listOf(
                    "<gray>배출 개수: <white>" + ctx.table.minRolls + " ~ " + ctx.table.maxRolls + "</white></gray>",
                    "<gray>분배 방식: <white>" + ctx.table.distribution.label + "</white></gray>",
                    "<gray>플레이어 처치 필요: </gray>" + Icon.toggle(ctx.table.requirePlayerKill),
                    "<gray>경험치: <white>" + ctx.table.expMin + " ~ " + ctx.table.expMax + "</white></gray>",
                    "",
                    "<yellow>▶ 클릭</yellow>",
                ),
            ),
        ) { event -> (event.whoClicked as? Player)?.let { DropRulesMenu(monsters, ctx).open(it) } }

        set(
            49,
            Icon.of(
                if (removeMode) Material.LAVA_BUCKET else Material.HOPPER,
                if (removeMode) "<red>제거 모드 (켜짐)</red>" else "<gray>제거 모드 (꺼짐)</gray>",
                if (removeMode) {
                    listOf(
                        "<gray>지울 항목을 클릭해 빼낸 뒤</gray>",
                        "<gray>확인을 누르면 삭제됩니다.</gray>",
                        "<dark_gray>다시 클릭하면 되돌립니다.</dark_gray>",
                        "",
                        "<yellow>▶ 클릭하여 등록 모드로</yellow>",
                    )
                } else {
                    listOf(
                        "<gray>빈 칸에 아이템을 올린 뒤</gray>",
                        "<gray>확인을 누르면 등록됩니다.</gray>",
                        "",
                        "<yellow>▶ 클릭하여 제거 모드로</yellow>",
                    )
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            returnStaged(player)
            DropListMenu(monsters, ctx, page, !removeMode).open(player)
        }

        set(
            51,
            Icon.of(
                Material.PAPER, "<yellow>도움말</yellow>",
                "<gray>등록된 드랍: <white>" + ctx.table.entries.size + "종</white></gray>",
                "<gray>확률 합: <white>" + Numbers.chance(ctx.table.entries.sumOf { it.chance }) + "%</white></gray>",
                "<gray>기본 확률: <white>" + Numbers.chance(monsters.config.dropDefaults.chance) + "%</white></gray>",
                "",
                "<yellow>항목 클릭</yellow><gray> : 확률·개수·조건 상세 설정</gray>",
                "<dark_gray>각 항목은 독립적으로 굴려지고, 당첨된 것들이</dark_gray>",
                "<dark_gray>배출 개수 상한까지만 잘려서 지급됩니다.</dark_gray>",
            ),
        )

        set(
            53,
            Icon.confirm(
                if (removeMode) "<red>✔ 제거 확정</red>" else "<green>✔ 등록 확정</green>",
                listOf("<gray>변경 사항을 저장합니다.</gray>"),
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (removeMode) applyRemovals(player) else applyAdditions(player)
            save()
            DropListMenu(monsters, ctx, page, removeMode).open(player)
        }
    }

    override fun isSlotEditable(slot: Int): Boolean {
        if (slot >= CONTENT_SIZE) return false
        return !removeMode && !slotToDrop.containsKey(slot)
    }

    override fun acceptsShiftInsert(): Boolean = !removeMode

    override fun onClose(event: InventoryCloseEvent) {
        (event.player as? Player)?.let { returnStaged(it) }
    }

    private fun applyAdditions(player: Player) {
        var added = 0
        for (slot in 0 until CONTENT_SIZE) {
            if (slotToDrop.containsKey(slot)) continue
            val stack = inventory.getItem(slot) ?: continue
            if (stack.type.isAir) continue

            ctx.table.entries.add(
                MobDrop(
                    item = monsters.itemResolver.capture(stack),
                    chance = monsters.config.dropDefaults.chance,
                    minAmount = stack.amount,
                    maxAmount = stack.amount,
                ),
            )
            inventory.setItem(slot, null)
            added++
        }
        if (added > 0) {
            player.sendMessage(Text.render("<green>드랍 " + added + "종을 등록했습니다.</green>"))
        }
    }

    private fun applyRemovals(player: Player) {
        val removed = stagedRemovals.mapNotNull { slotToDrop[it] }
        stagedRemovals.clear()
        if (removed.isEmpty()) return
        ctx.table.entries.removeAll(removed.toSet())
        player.sendMessage(Text.render("<yellow>드랍 " + removed.size + "종을 제거했습니다.</yellow>"))
    }

    private fun returnStaged(player: Player) {
        for (slot in 0 until CONTENT_SIZE) {
            if (slotToDrop.containsKey(slot)) continue
            val stack = inventory.getItem(slot) ?: continue
            if (stack.type.isAir) continue
            inventory.setItem(slot, null)
            player.inventory.addItem(stack).values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }
        }
    }

    private fun switchPage(who: org.bukkit.entity.HumanEntity, target: Int) {
        val player = who as? Player ?: return
        returnStaged(player)
        DropListMenu(monsters, ctx, target, removeMode).open(player)
    }

    private fun dropIcon(drop: MobDrop): ItemStack {
        val icon = monsters.itemResolver.icon(drop.item)
        val amountText =
            if (drop.minAmount == drop.maxAmount) drop.minAmount.toString() + "개"
            else drop.minAmount.toString() + "~" + drop.maxAmount + "개"

        val lore = mutableListOf(
            "<gray>확률: <yellow>" + Numbers.chance(drop.chance) + "%</yellow></gray>",
            "<gray>수량: <white>" + amountText + "</white></gray>",
            "<gray>저장 방식: <white>" +
                (if (drop.item.mode == StorageMode.REFERENCE) "참조 (자동 갱신)" else "스냅샷 (고정)") +
                "</white></gray>",
            "<dark_gray>" + drop.item.ref.serialize() + "</dark_gray>",
        )
        drop.tier?.let { lore.add("<light_purple>티어: " + it + "</light_purple>") }
        if (drop.minLevel > 0) lore.add("<aqua>최소 레벨: " + drop.minLevel + "</aqua>")
        if (drop.requiredTools.isNotEmpty()) {
            lore.add("<aqua>필요 도구: " + drop.requiredTools.joinToString(", ") + "</aqua>")
        }
        if (drop.announce) lore.add("<gold>★ 획득 시 서버 공지</gold>")
        if (drop.commands.isNotEmpty()) lore.add("<aqua>실행 명령어 " + drop.commands.size + "개</aqua>")
        if (!drop.giveItem) lore.add("<dark_gray>아이템 지급 없음 (명령어 전용)</dark_gray>")
        lore.addAll(icon.notes())
        lore.add("")
        lore.add(
            if (removeMode) "<red>▶ 클릭하여 빼낸 뒤 확인</red>"
            else "<yellow>▶ 클릭하여 상세 설정</yellow>",
        )

        return Icon.annotate(icon.stack, lore = lore)
    }

    private fun save() = ctx.save()

    companion object {
        private const val SIZE = 54
        private const val CONTENT_SIZE = 45

        private fun title(ctx: DropContext) =
            Text.renderFlat("<dark_gray>드랍 아이템 <gray>|</gray> " + ctx.label + "</dark_gray>")
    }
}
