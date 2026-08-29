package com.inmc.monster.listener

import com.inmc.monster.Monsters
import com.inmc.monster.gui.Menu
import com.inmc.monster.util.Text
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import java.util.logging.Level

/**
 * Single routing point for every plugin menu.
 *
 * Menus are identified by their [org.bukkit.inventory.InventoryHolder], so one listener covers
 * all of them and no menu has to register anything of its own.
 *
 * Every handler is wrapped. A menu that throws part-way through drawing otherwise leaves the
 * admin looking at a half-filled window with no idea anything went wrong - the only trace is a
 * stack trace in a console they may not be watching. Worse, the click stays uncancelled, so a
 * failure in a screen that holds real items can hand those items to the player. Cancelling and
 * saying so is the only honest outcome.
 */
class MenuListener(private val monsters: Monsters) : Listener {

    private fun menuOf(holder: Any?): Menu? = holder as? Menu

    @EventHandler(priority = EventPriority.HIGH)
    fun onClick(event: InventoryClickEvent) {
        val menu = menuOf(event.view.topInventory.holder) ?: return
        try {
            menu.handleClick(event)
        } catch (t: Throwable) {
            // Cancel first: whatever the menu was doing did not finish, so the click must not
            // be allowed to move items on its own.
            event.isCancelled = true
            report(menu, event.whoClicked as? Player, "클릭", t)
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDrag(event: InventoryDragEvent) {
        val menu = menuOf(event.view.topInventory.holder) ?: return
        try {
            menu.onDrag(event)
        } catch (t: Throwable) {
            event.isCancelled = true
            report(menu, event.whoClicked as? Player, "드래그", t)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val menu = menuOf(event.view.topInventory.holder) ?: return
        try {
            menu.onClose(event)
        } catch (t: Throwable) {
            // A close handler is what returns staged items, so a failure here can genuinely eat
            // an admin's items. It is logged loudly rather than swallowed.
            report(menu, event.player as? Player, "창 닫기", t)
        }
    }

    /**
     * Tells both the console and the player.
     *
     * The player gets the exception type but not a stack trace: enough to know the screen failed
     * and to say something useful when reporting it, without pasting internals into chat.
     */
    private fun report(menu: Menu, player: Player?, action: String, error: Throwable) {
        monsters.logger.log(
            Level.SEVERE,
            "GUI 처리 실패 (" + menu.javaClass.simpleName + " / " + action + ")",
            error,
        )
        player?.closeInventory()
        player?.sendMessage(
            Text.render(
                "<red>화면을 처리하는 중 오류가 발생했습니다. <gray>(" +
                    error.javaClass.simpleName + ")</gray></red>",
            ),
        )
        player?.sendMessage(
            Text.render("<dark_gray>콘솔 로그를 확인해주세요. 창을 다시 열면 계속할 수 있습니다.</dark_gray>"),
        )
    }
}
