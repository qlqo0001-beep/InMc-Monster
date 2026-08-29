package com.inmc.monster.gui

import com.inmc.monster.Monsters
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * Minimal inventory-menu base. The plugin ships its own instead of pulling in a GUI library,
 * which keeps the dependency list at zero.
 *
 * Clicks are cancelled by default and dispatched through a per-slot action map; menus that
 * genuinely need the player to move items (the reward editor, the loot window) opt out by
 * overriding [isSlotEditable].
 */
abstract class Menu(
    protected val monsters: Monsters,
    val size: Int,
    title: Component,
) : InventoryHolder {

    private val inv: Inventory = Bukkit.createInventory(this, size, title)
    private val actions = HashMap<Int, (InventoryClickEvent) -> Unit>()

    final override fun getInventory(): Inventory = inv

    /** Fills the inventory. Called on open and whenever state changes. */
    abstract fun draw()

    open fun onClose(event: InventoryCloseEvent) {}

    /** Slots the player may freely put items into or take items out of. */
    open fun isSlotEditable(slot: Int): Boolean = false

    /** True when the player may shift-click items in from their own inventory. */
    open fun acceptsShiftInsert(): Boolean = false

    open fun onDrag(event: InventoryDragEvent) {
        val topSize = size
        val touchesLocked = event.rawSlots.any { it < topSize && !isSlotEditable(it) }
        if (touchesLocked) event.isCancelled = true
    }

    open fun handleClick(event: InventoryClickEvent) {
        val raw = event.rawSlot
        val inTop = raw in 0 until size

        if (!inTop) {
            // Clicking one's own inventory: only allow shift-inserting into menus that want it.
            if (event.isShiftClick && !acceptsShiftInsert()) event.isCancelled = true
            return
        }

        if (!isSlotEditable(raw)) event.isCancelled = true
        actions[raw]?.invoke(event)
    }

    fun open(player: Player) {
        draw()
        player.openInventory(inv)
    }

    fun refresh() {
        draw()
    }

    // --- drawing helpers -------------------------------------------------------

    protected fun clear() {
        inv.clear()
        actions.clear()
    }

    protected fun set(slot: Int, stack: ItemStack?, onClick: ((InventoryClickEvent) -> Unit)? = null) {
        if (slot !in 0 until size) return
        inv.setItem(slot, stack)
        if (onClick != null) actions[slot] = onClick else actions.remove(slot)
    }

    protected fun action(slot: Int, onClick: (InventoryClickEvent) -> Unit) {
        if (slot in 0 until size) actions[slot] = onClick
    }

    protected fun fillBorder(stack: ItemStack) {
        val rows = size / 9
        for (i in 0 until size) {
            val row = i / 9
            val col = i % 9
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                if (inv.getItem(i) == null) inv.setItem(i, stack)
            }
        }
    }

    protected fun fillEmpty(stack: ItemStack) {
        for (i in 0 until size) {
            if (inv.getItem(i) == null) inv.setItem(i, stack)
        }
    }

    protected fun viewers(): List<Player> = inv.viewers.filterIsInstance<Player>()
}
