package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.affix.Affix
import com.inmc.monster.mob.DropTable
import com.inmc.monster.mob.MobDefinition
import org.bukkit.entity.Player

/**
 * The drop table a drop screen is editing, and what owns it.
 *
 * The editor is identical whether the table belongs to a mob or to an affix - the gesture is
 * "name an item that will be handed out later" either way - so the screens take this instead of
 * a [MobDefinition]. Only saving and the back button differ, and both arrive as callbacks.
 */
class DropContext(
    val table: DropTable,
    /** Shown in the window title so an admin knows whose table they are editing. */
    val label: String,
    val save: () -> Unit,
    val back: (Player) -> Unit,
    /**
     * False for tables that are handed out alongside another table rather than on their own.
     *
     * An affix's drops are extra loot on top of whatever the mob already gives, so the settings
     * that only make sense for a mob's own table - experience, vanilla-drop handling, the
     * distribution mode - are hidden rather than shown doing nothing.
     */
    val standalone: Boolean = true,
) {

    companion object {

        fun of(monsters: Monsters, definition: MobDefinition): DropContext = DropContext(
            table = definition.drops,
            label = definition.id,
            save = { monsters.mobs.markDirty(definition) },
            back = { MobManageMenu(monsters, definition).open(it) },
        )

        fun of(monsters: Monsters, affix: Affix, returnPage: Int): DropContext = DropContext(
            table = affix.drops,
            label = "수식어 " + affix.id,
            save = { monsters.affixes.markDirty() },
            back = { AffixDetailMenu(monsters, affix, returnPage).open(it) },
            standalone = false,
        )
    }
}
