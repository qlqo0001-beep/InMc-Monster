package com.inmc.monster.item

import com.inmc.monster.Monsters
import com.inmc.monster.mob.MobDefinition
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * Spawn eggs for custom mobs.
 *
 * The egg carries the mob id in its persistent data rather than in its name, so renaming it in
 * an anvil, stacking it, or moving it through a shop cannot change what it spawns. The vanilla
 * egg it is built from is only cosmetic - it is picked to match the mob's entity type so a
 * pocketful of them is still identifiable at a glance.
 */
class SpawnEggs(private val monsters: Monsters) {

    private val key: NamespacedKey by lazy { NamespacedKey(monsters.plugin, "spawn_egg") }

    /** Builds an egg for [definition]. */
    fun create(definition: MobDefinition, amount: Int = 1): ItemStack {
        val material = Material.matchMaterial(definition.entityType.name + "_SPAWN_EGG")
            ?: Material.EGG

        val stack = ItemStack(material, amount.coerceIn(1, 64))
        stack.editMeta { meta ->
            meta.displayName(
                Text.renderFlat("<gold>" + definition.displayName + "</gold> <gray>소환 알</gray>"),
            )
            meta.lore(
                Text.renderLore(
                    buildList {
                        add("<dark_gray>" + definition.id + "</dark_gray>")
                        add("")
                        add("<gray>종류: <white>" + definition.entityType.name + "</white></gray>")
                        add(
                            "<gray>체력 <red>" + Numbers.chance(definition.maxHealth()) + "</red>" +
                                "   공격력 <red>" +
                                Numbers.chance(definition.stats.getOrZero("ATTACK_DAMAGE")) + "</red></gray>",
                        )
                        if (definition.level.base > 1) {
                            add("<gray>레벨: <white>" + definition.level.base + "</white></gray>")
                        }
                        add("")
                        add("<yellow>▶ 블록을 우클릭하면 그 자리에 소환됩니다.</yellow>")
                        add("<dark_gray>스폰 조건과 한도를 무시하고 강제로 생성합니다.</dark_gray>")
                    },
                ),
            )
            meta.persistentDataContainer.set(key, PersistentDataType.STRING, definition.id)
        }
        return stack
    }

    /** The mob id an egg spawns, or null when this is not one of ours. */
    fun idOf(stack: ItemStack?): String? {
        if (stack == null || stack.type.isAir) return null
        val meta = stack.itemMeta ?: return null
        return meta.persistentDataContainer.get(key, PersistentDataType.STRING)
            ?.takeIf { it.isNotBlank() }
    }

    fun isSpawnEgg(stack: ItemStack?): Boolean = idOf(stack) != null
}
