package com.inmc.monster.item

import com.inmc.monster.util.Text
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import java.util.Base64

/** Whether an item is rebuilt from its live definition or handed out exactly as captured. */
enum class StorageMode {
    /** Rebuild from [ItemRef] every time - picks up later edits, stacks with fresh copies. */
    REFERENCE,

    /** Always hand out the captured snapshot - frozen, but survives the source plugin going away. */
    SNAPSHOT;

    fun toggle(): StorageMode = if (this == REFERENCE) SNAPSHOT else REFERENCE

    companion object {
        fun parse(raw: String?): StorageMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: REFERENCE
    }
}

/**
 * One item as persisted in YAML - used for reward entries, the key item and the capsule item.
 *
 * [ref] is the human readable identity written to disk (`minecraft:diamond`,
 * `mmoitems:SWORD:EXCALIBUR`, ...). [snapshot] is only kept when the reference alone cannot
 * reproduce the item, or when the admin pinned the entry to [StorageMode.SNAPSHOT].
 */
data class StoredItem(
    val ref: ItemRef,
    val material: Material,
    val mode: StorageMode = StorageMode.REFERENCE,
    val snapshot: ByteArray? = null,
    /** Plain-text display name, used for key matching (spec §75) and `{열쇠이름}`. */
    val displayName: String? = null,
) {

    /** Label shown in GUI lore, chat messages and Discord payloads. */
    fun label(): String = displayName?.takeIf { it.isNotBlank() } ?: ref.serialize()

    fun withMode(mode: StorageMode): StoredItem = copy(mode = mode)

    fun save(section: ConfigurationSection) {
        section.set("item", ref.serialize())
        section.set("mode", mode.name)
        section.set("material", material.key().toString())
        if (!displayName.isNullOrBlank()) section.set("name", displayName)
        if (snapshot != null) section.set("snapshot", Base64.getEncoder().encodeToString(snapshot))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredItem) return false
        return ref == other.ref &&
            material == other.material &&
            mode == other.mode &&
            displayName == other.displayName &&
            (snapshot?.contentEquals(other.snapshot ?: ByteArray(0)) ?: (other.snapshot == null))
    }

    override fun hashCode(): Int {
        var result = ref.hashCode()
        result = 31 * result + material.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + (snapshot?.contentHashCode() ?: 0)
        return result
    }

    companion object {

        fun load(section: ConfigurationSection): StoredItem? {
            val ref = ItemRef.parse(section.getString("item"))
            val snapshot = section.getString("snapshot")?.let {
                runCatching { Base64.getDecoder().decode(it) }.getOrNull()
            }
            val material = section.getString("material")
                ?.let { Material.matchMaterial(it) }
                ?: (ref as? ItemRef.Vanilla)?.material
                ?: Material.CHEST
            if (ref == ItemRef.None && snapshot == null) return null
            return StoredItem(
                ref = ref,
                material = material,
                mode = StorageMode.parse(section.getString("mode")),
                snapshot = snapshot,
                displayName = section.getString("name"),
            )
        }

        /** Reads the plain-text display name off a stack, or null when it has none. */
        fun plainName(stack: ItemStack): String? {
            val meta = stack.itemMeta ?: return null
            if (!meta.hasDisplayName()) return null
            val component = meta.displayName() ?: return null
            return Text.plain(component).takeIf { it.isNotBlank() }
        }
    }
}
