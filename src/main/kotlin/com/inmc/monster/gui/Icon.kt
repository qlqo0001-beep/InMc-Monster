package com.inmc.monster.gui

import com.inmc.monster.util.Text
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/** Builders for the decorative items every menu is made of. */
object Icon {

    val FILLER: ItemStack by lazy { blank(Material.GRAY_STAINED_GLASS_PANE) }
    val EDGE: ItemStack by lazy { blank(Material.BLACK_STAINED_GLASS_PANE) }
    val SLOT_HINT: ItemStack by lazy { blank(Material.LIGHT_GRAY_STAINED_GLASS_PANE) }

    fun blank(material: Material): ItemStack = ItemStack(material).apply {
        editMeta { meta -> meta.displayName(Text.renderFlat(" ")) }
    }

    fun of(material: Material, name: String, vararg lore: String): ItemStack =
        of(material, name, lore.toList())

    fun of(material: Material, name: String, lore: List<String>): ItemStack =
        ItemStack(material).apply { decorate(this, name, lore) }

    /**
     * Adds menu information to a real item **below whatever lore it already has**.
     *
     * Overwriting the lore would erase exactly the part that identifies a custom item - an
     * MMOItems weapon's stat block, an ItemsAdder item's description - leaving the admin (and,
     * in the `/monsters info` chance table, the player) looking at an anonymous vanilla-looking
     * stack. Anything the source plugin wrote stays; our lines go after a separator.
     */
    fun annotate(stack: ItemStack, name: String? = null, lore: List<String>): ItemStack =
        stack.clone().apply {
            editMeta { meta: ItemMeta ->
                if (name != null) meta.displayName(Text.renderFlat(name))
                if (lore.isEmpty()) return@editMeta

                val existing = meta.lore().orEmpty()
                val merged = if (existing.isEmpty()) {
                    Text.renderLore(lore)
                } else {
                    existing + Text.renderFlat(SEPARATOR) + Text.renderLore(lore)
                }
                meta.lore(merged)
            }
        }

    /** Replaces name and lore outright. Only for icons we authored ourselves. */
    fun relabel(stack: ItemStack, name: String?, lore: List<String>): ItemStack =
        stack.clone().apply { decorate(this, name, lore) }

    private fun decorate(stack: ItemStack, name: String?, lore: List<String>) {
        stack.editMeta { meta: ItemMeta ->
            if (name != null) meta.displayName(Text.renderFlat(name))
            if (lore.isNotEmpty()) meta.lore(Text.renderLore(lore))
        }
    }

    private const val SEPARATOR = "<dark_gray>────────────</dark_gray>"

    fun toggle(value: Boolean): String = if (value) "<green>켜짐</green>" else "<red>꺼짐</red>"

    fun toggleMaterial(value: Boolean): Material =
        if (value) Material.LIME_DYE else Material.GRAY_DYE

    fun back(): ItemStack = of(Material.ARROW, "<gray>◀ 뒤로</gray>")

    fun close(): ItemStack = of(Material.BARRIER, "<red>✖ 닫기</red>")

    fun confirm(name: String = "<green>✔ 확인</green>", lore: List<String> = emptyList()): ItemStack =
        of(Material.LIME_CONCRETE, name, lore)

    fun cancel(name: String = "<red>✖ 취소</red>", lore: List<String> = emptyList()): ItemStack =
        of(Material.RED_CONCRETE, name, lore)

    fun prevPage(): ItemStack = of(Material.SPECTRAL_ARROW, "<yellow>◀ 이전 페이지</yellow>")

    fun nextPage(): ItemStack = of(Material.SPECTRAL_ARROW, "<yellow>다음 페이지 ▶</yellow>")
}
