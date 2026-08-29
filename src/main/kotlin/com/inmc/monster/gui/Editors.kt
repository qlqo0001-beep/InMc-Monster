package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.util.Numbers
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack

/**
 * Reusable editor widgets.
 *
 * Twenty-odd screens all need the same three controls - nudge a number, type a number, cycle an
 * enum - and writing them per screen is how menus drift apart until the same setting behaves
 * differently in two places. These exist so every editable value in the plugin responds to the
 * same clicks.
 */
object Editors {

    /**
     * True when the click means "let me type the value instead of nudging it".
     *
     * A number key is the binding that actually works everywhere. Middle-click was the original
     * choice and is the obvious one, but in survival mode it is the creative-only pick-block
     * control and never reaches the server - so on a live server the only way to enter an exact
     * value silently did nothing. Q and F are accepted too; they do not arrive on every setup
     * either, and accepting all four costs nothing.
     */
    fun isPrompt(event: InventoryClickEvent): Boolean = when (event.click) {
        org.bukkit.event.inventory.ClickType.NUMBER_KEY,
        org.bukkit.event.inventory.ClickType.MIDDLE,
        org.bukkit.event.inventory.ClickType.DROP,
        org.bukkit.event.inventory.ClickType.SWAP_OFFHAND,
        -> true

        else -> false
    }

    /** Left-click adds, right-click subtracts, shift multiplies the step by ten. */
    fun step(event: InventoryClickEvent, amount: Double): Double {
        val magnitude = if (event.isShiftClick) amount * 10.0 else amount
        return if (event.isLeftClick) magnitude else -magnitude
    }

    fun step(event: InventoryClickEvent, amount: Int): Int {
        val magnitude = if (event.isShiftClick) amount * 10 else amount
        return if (event.isLeftClick) magnitude else -magnitude
    }

    /** Standard hint block appended to every nudgeable value. */
    fun nudgeHint(amount: String): List<String> = listOf(
        "",
        "<yellow>▶ 좌클릭 +" + amount + "  /  우클릭 -" + amount + "</yellow>",
        "<yellow>▶ Shift 로 10배</yellow>",
        "<dark_gray>숫자키(1~9): 직접 입력</dark_gray>",
    )

    /**
     * A number tile: nudge with clicks, type an exact value with a number key.
     *
     * The typed escape hatch matters - nudging from 20 to 4000 is forty shift-clicks, and an
     * admin who has to do that once will edit the YAML by hand from then on.
     */
    fun numberIcon(
        material: Material,
        name: String,
        value: Double,
        unit: String = "",
        extra: List<String> = emptyList(),
        stepLabel: String = "1",
    ): ItemStack = Icon.of(
        material, name,
        listOf("<gray>현재: <yellow>" + Numbers.chance(value) + unit + "</yellow></gray>") + extra + nudgeHint(stepLabel),
    )

    fun intIcon(
        material: Material,
        name: String,
        value: Int,
        unit: String = "",
        extra: List<String> = emptyList(),
        stepLabel: String = "1",
    ): ItemStack = Icon.of(
        material, name,
        listOf("<gray>현재: <yellow>" + value + unit + "</yellow></gray>") + extra + nudgeHint(stepLabel),
    )

    /** Asks for an exact decimal value. */
    fun promptDouble(
        monsters: Monsters,
        player: Player,
        label: String,
        min: Double,
        max: Double,
        reopen: () -> Unit,
        onValue: (Double) -> Unit,
    ) {
        monsters.prompts.requestDouble(
            player,
            listOf(
                "<yellow>" + label + " 값을 입력하세요.</yellow>",
                "<gray>범위: <white>" + Numbers.chance(min) + " ~ " + Numbers.chance(max) + "</white></gray>",
            ),
            min = min,
            max = max,
            onCancel = reopen,
        ) { value ->
            onValue(value)
            reopen()
        }
    }

    fun promptInt(
        monsters: Monsters,
        player: Player,
        label: String,
        min: Int,
        max: Int,
        reopen: () -> Unit,
        onValue: (Int) -> Unit,
    ) {
        monsters.prompts.requestInt(
            player,
            listOf(
                "<yellow>" + label + " 값을 입력하세요.</yellow>",
                "<gray>범위: <white>" + min + " ~ " + max + "</white></gray>",
            ),
            min = min,
            max = max,
            onCancel = reopen,
        ) { value ->
            onValue(value)
            reopen()
        }
    }

    fun promptText(
        monsters: Monsters,
        player: Player,
        label: String,
        hints: List<String>,
        reopen: () -> Unit,
        onValue: (String) -> Unit,
    ) {
        monsters.prompts.request(
            player,
            listOf("<yellow>" + label + "</yellow>") + hints,
            onCancel = reopen,
        ) { input ->
            onValue(input)
            reopen()
        }
    }

    /** Cycles a list forward on left-click and backward on right-click. */
    fun <T> cycle(event: InventoryClickEvent, options: List<T>, current: T): T {
        if (options.isEmpty()) return current
        val index = options.indexOf(current)
        return if (event.isRightClick) {
            options[(index - 1 + options.size) % options.size]
        } else {
            options[(index + 1) % options.size]
        }
    }

    /** Lore block listing an enum's options with the active one marked. */
    fun <T> optionList(options: List<T>, current: T, label: (T) -> String): List<String> =
        options.map { option ->
            val marker = if (option == current) "<green>▶</green>" else "<dark_gray>·</dark_gray>"
            marker + " <dark_gray>" + label(option) + "</dark_gray>"
        }

    /** Standard cycle hint. */
    val cycleHint: List<String> = listOf("", "<yellow>▶ 좌클릭: 다음  /  우클릭: 이전</yellow>")
}
