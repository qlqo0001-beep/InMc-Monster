package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.util.Text

/**
 * Two-button confirmation for anything destructive.
 *
 * Used rather than a shift-click convention wherever the action deletes configuration an admin
 * cannot get back - a mistyped click on a mob list should not be able to erase a definition
 * someone spent an afternoon on.
 */
class ConfirmMenu(
    monsters: Monsters,
    private val question: String,
    private val detail: List<String> = emptyList(),
    private val confirmLabel: String = "<green>✔ 진행합니다</green>",
    private val cancelLabel: String = "<red>✖ 취소</red>",
    private val onConfirm: () -> Unit,
    private val onCancel: () -> Unit = {},
) : Menu(monsters, 27, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(4, Icon.of(org.bukkit.Material.PAPER, question, detail))

        // Both buttons reopen or close from the click handler rather than from onClose. Opening
        // an inventory while one is closing is unreliable in Bukkit, so pressing Escape simply
        // closes the window and changes nothing - which is what Escape should do anyway.
        set(11, Icon.confirm(confirmLabel, listOf("<gray>되돌릴 수 없습니다.</gray>"))) { onConfirm() }

        set(15, Icon.cancel(cancelLabel)) { onCancel() }
    }

    companion object {
        private val TITLE = Text.renderFlat("<dark_red>확인</dark_red>")
    }
}
