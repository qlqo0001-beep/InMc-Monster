package com.inmc.monster.mob

import kr.inmc.core.item.ItemResolver
import kr.inmc.core.item.StoredItem
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.EquipmentSlot

/**
 * One equipped item plus how likely the mob is to drop it.
 *
 * [dropChance] defaults to zero and the GUI warns before it is raised. Equipment on a custom
 * mob is routinely an MMOItems weapon far stronger than anything the economy expects players
 * to own; a non-zero drop chance hands that item to whoever kills the mob, which is almost
 * never what an admin actually meant when they dressed the mob up.
 */
class EquipmentEntry(
    var item: StoredItem,
    dropChance: Double = 0.0,
) {

    var dropChance: Double = dropChance.coerceIn(0.0, 100.0)
        set(value) {
            field = value.coerceIn(0.0, 100.0)
        }

    fun copyOf(): EquipmentEntry = EquipmentEntry(item, dropChance)

    fun save(section: ConfigurationSection) {
        item.save(section)
        section.set("drop-chance", dropChance)
    }

    companion object {
        fun load(section: ConfigurationSection): EquipmentEntry? {
            val item = StoredItem.load(section) ?: return null
            return EquipmentEntry(item, section.getDouble("drop-chance", 0.0))
        }
    }
}

/**
 * The six equipment slots a mob can wear, as configured.
 *
 * Slots are addressed by [EquipmentSlot] so the same map drives both the GUI layout and the
 * actual dressing at spawn time.
 */
class MobEquipment {

    private val slots = LinkedHashMap<EquipmentSlot, EquipmentEntry>()

    val isEmpty: Boolean get() = slots.isEmpty()

    operator fun get(slot: EquipmentSlot): EquipmentEntry? = slots[slot]

    operator fun set(slot: EquipmentSlot, entry: EquipmentEntry?) {
        if (entry == null) slots.remove(slot) else slots[slot] = entry
    }

    fun entries(): Map<EquipmentSlot, EquipmentEntry> = LinkedHashMap(slots)

    fun copyOf(): MobEquipment = MobEquipment().also { copy ->
        slots.forEach { (slot, entry) -> copy.slots[slot] = entry.copyOf() }
    }

    /**
     * Dresses [entity]. Items that cannot be rebuilt are skipped rather than replaced with a
     * lookalike, and a warning is logged once by the resolver.
     */
    fun applyTo(entity: LivingEntity, resolver: ItemResolver) {
        val equipment = entity.equipment ?: return
        for (slot in SLOTS) {
            val entry = slots[slot]
            if (entry == null) {
                equipment.setItem(slot, null, true)
                equipment.setDropChance(slot, 0f)
                continue
            }
            val stack = resolver.create(entry.item, 1)
            if (stack == null) {
                equipment.setDropChance(slot, 0f)
                continue
            }
            // silent = true: dressing a mob at spawn must not play the equip sound for every
            // player in range, which at a busy spawner is a wall of clicks.
            equipment.setItem(slot, stack, true)
            equipment.setDropChance(slot, (entry.dropChance / 100.0).toFloat())
        }
    }

    fun save(section: ConfigurationSection, path: String) {
        section.set(path, null)
        if (slots.isEmpty()) return
        val target = section.createSection(path)
        slots.forEach { (slot, entry) -> entry.save(target.createSection(slot.name.lowercase())) }
    }

    companion object {

        /** Vanilla-dressable slots, in GUI order. BODY and SADDLE are mob-specific and skipped. */
        val SLOTS: List<EquipmentSlot> = listOf(
            EquipmentSlot.HAND,
            EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET,
        )

        fun label(slot: EquipmentSlot): String = when (slot) {
            EquipmentSlot.HAND -> "주손"
            EquipmentSlot.OFF_HAND -> "보조손"
            EquipmentSlot.HEAD -> "머리"
            EquipmentSlot.CHEST -> "가슴"
            EquipmentSlot.LEGS -> "다리"
            EquipmentSlot.FEET -> "발"
            else -> slot.name
        }

        fun load(section: ConfigurationSection?, path: String): MobEquipment {
            val out = MobEquipment()
            val target = section?.getConfigurationSection(path) ?: return out
            for (key in target.getKeys(false)) {
                val slot = runCatching { EquipmentSlot.valueOf(key.uppercase()) }.getOrNull() ?: continue
                if (slot !in SLOTS) continue
                val entrySection = target.getConfigurationSection(key) ?: continue
                EquipmentEntry.load(entrySection)?.let { out.slots[slot] = it }
            }
            return out
        }
    }
}
