package com.inmc.monster.item

import org.bukkit.Material

/**
 * How a stored item is *described*, as opposed to how it currently looks.
 *
 * Storing only a serialized snapshot of an ItemStack freezes it: editing a MMOItems
 * definition later would still hand out the old copy, and per-item NBT (unique ids and
 * the like) means freshly generated copies of the "same" item refuse to stack. Storing a
 * reference instead means the item is rebuilt from its live definition every time it
 * drops. [None] covers the cases no reference can express - a hand-made vanilla item with
 * a custom name, lore or enchantments - and falls back to the snapshot.
 */
sealed interface ItemRef {

    /** Canonical `namespace:key` form written to YAML. */
    fun serialize(): String

    data class Vanilla(val material: Material) : ItemRef {
        override fun serialize(): String = "minecraft:" + material.key().value()
    }

    data class MMOItems(val type: String, val id: String) : ItemRef {
        override fun serialize(): String = "mmoitems:$type:$id"
    }

    /** Any other plugin that tags its items with a namespaced id (ItemsAdder, Nexo, ...). */
    data class Namespaced(val namespace: String, val id: String) : ItemRef {
        override fun serialize(): String = "$namespace:$id"
    }

    /** No reference could be derived - the snapshot is the only source of truth. */
    data object None : ItemRef {
        override fun serialize(): String = "snapshot"
    }

    companion object {

        fun parse(raw: String?): ItemRef {
            if (raw.isNullOrBlank()) return None
            val text = raw.trim()
            if (text.equals("snapshot", ignoreCase = true)) return None

            val parts = text.split(':')
            if (parts.size < 2) {
                // bare material name, e.g. "STONE" - the legacy plugin allowed this
                val material = matchMaterial(text)
                return if (material != null) Vanilla(material) else None
            }

            val namespace = parts[0].lowercase()
            return when {
                namespace == "minecraft" -> matchMaterial(parts[1])?.let { Vanilla(it) } ?: None
                namespace == "mmoitems" && parts.size >= 3 ->
                    MMOItems(parts[1].uppercase(), parts.drop(2).joinToString(":").uppercase())
                else -> Namespaced(namespace, parts.drop(1).joinToString(":"))
            }
        }

        private fun matchMaterial(name: String): Material? =
            Material.matchMaterial(name) ?: Material.matchMaterial("minecraft:" + name.lowercase())
    }
}
