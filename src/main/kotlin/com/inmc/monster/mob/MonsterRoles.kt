package com.inmc.monster.mob

import com.inmc.monster.Monsters
import com.inmc.monster.runtime.ActiveMob
import kr.inmc.core.integration.ItemRoles
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.ItemResolver
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.EquipmentSlot

/**
 * 몬스터가 커스텀아이템에 내놓는 역할(core [ItemRoles]) — **몬스터 드랍**(어느 몹이 몇 % 로 몇 개)과 **몬스터 장비**(어느 몹이 어느 칸에).
 *
 * 몹 정의의 드랍 표·장비는 그대로 두고, 커스텀아이템 쪽에서 "이 아이템을 좀비 왕이 5% 로 떨군다"를 **더한다**([extraDrops]·[equip]).
 * 몹 정의에 적힌 바닐라가 아닌 아이템(수제·MMOItems …)은 처음 한 번 커스텀아이템으로 옮겨 그 목록에 보이게 하고, 정의에는 커스텀아이템
 * 참조를 적는다 — 바닐라 그대로인 드랍(썩은 살점 등)은 옮기지 않는다.
 */
object MonsterRoles {

    const val OWNER = "몬스터"

    val DROP = "monster.drop"
    val EQUIP = "monster.equip"

    private val SLOTS = listOf(
        EquipmentSlot.HEAD to "머리", EquipmentSlot.CHEST to "몸통", EquipmentSlot.LEGS to "다리", EquipmentSlot.FEET to "발",
        EquipmentSlot.HAND to "주 손", EquipmentSlot.OFF_HAND to "보조 손",
    )

    fun roles(monsters: Monsters): List<ItemRoles.Role> {
        val mob = ItemRoles.Choice("mob", "몹", { monsters.mobs.all().map { it.id to it.id } })
        return listOf(
            ItemRoles.Role(
                DROP, OWNER, "몬스터 드랍", Material.ROTTEN_FLESH,
                listOf("이 아이템을 그 몹이 떨굽니다(몹의 드랍 표에 더해).", "몹 배율·월드 배율이 확률에 곱해집니다."),
                listOf(
                    mob,
                    ItemRoles.Number("chance", "확률(%)", 0.0, 100.0, 1.0, "10", integer = false),
                    ItemRoles.Number("min", "최소 개수", 1.0, 64.0, 1.0, "1"),
                    ItemRoles.Number("max", "최대 개수", 1.0, 64.0, 1.0, "1"),
                ),
            ),
            ItemRoles.Role(
                EQUIP, OWNER, "몬스터 장비", Material.IRON_HELMET,
                listOf("그 몹이 나타날 때 이 아이템을 입거나 듭니다(몹 정의의 장비보다 나중에)."),
                listOf(
                    mob,
                    ItemRoles.Choice("slot", "칸", { SLOTS.map { it.first.name to it.second } }, EquipmentSlot.HAND.name),
                    ItemRoles.Number("drop-chance", "죽을 때 떨굴 확률(%)", 0.0, 100.0, 5.0, "0", integer = false),
                ),
            ),
        )
    }

    @Volatile
    private var drops: Map<String, List<MobDrop>> = emptyMap()

    @Volatile
    private var equipment: Map<String, Map<EquipmentSlot, EquipmentEntry>> = emptyMap()

    private fun ours(ref: ItemRef?) = (ref as? ItemRef.Namespaced)?.namespace.equals("inmc", ignoreCase = true)

    private var syncing = false

    /** 역할을 다시 읽는다. 처음이면 몹 정의의 바닐라가 아닌 드랍·장비 아이템을 커스텀아이템으로 옮긴다. */
    fun sync(monsters: Monsters) {
        if (!ItemRoles.active) {
            drops = emptyMap()
            equipment = emptyMap()
            return
        }
        if (syncing) return
        syncing = true
        try {
            for (def in monsters.mobs.all()) {
                var changed = false
                for (drop in def.drops.entries) {
                    val ref = adopt(monsters, drop.item, def.id + "_드랍") ?: continue
                    drop.item = ItemRoles.holders(DROP).firstOrNull { it.ref == ref }?.item() ?: kr.inmc.core.item.StoredItem(ref, drop.item.material, displayName = drop.item.displayName)
                    changed = true
                }
                for ((slot, entry) in def.equipment.entries()) {
                    val ref = adopt(monsters, entry.item, def.id + "_장비") ?: continue
                    def.equipment[slot] = EquipmentEntry(kr.inmc.core.item.StoredItem(ref, entry.item.material, displayName = entry.item.displayName), entry.dropChance)
                    changed = true
                }
                if (changed) monsters.mobs.markDirty(def)
            }
            drops = ItemRoles.holders(DROP).groupBy({ it.values["mob"].orEmpty() }) { holder ->
                MobDrop(
                    id = "role-" + holder.ref.id,
                    item = holder.item(),
                    chance = holder.values["chance"]?.toDoubleOrNull() ?: 10.0,
                    minAmount = holder.values["min"]?.toDoubleOrNull()?.toInt() ?: 1,
                    maxAmount = holder.values["max"]?.toDoubleOrNull()?.toInt() ?: 1,
                )
            }
            equipment = ItemRoles.holders(EQUIP).groupBy { it.values["mob"].orEmpty() }.mapValues { (_, holders) ->
                holders.mapNotNull { holder ->
                    val slot = runCatching { EquipmentSlot.valueOf(holder.values["slot"].orEmpty()) }.getOrNull() ?: return@mapNotNull null
                    slot to EquipmentEntry(holder.item(), holder.values["drop-chance"]?.toDoubleOrNull() ?: 0.0)
                }.toMap()
            }
        } finally {
            syncing = false
        }
    }

    /** 바닐라가 아니고 아직 커스텀아이템이 아닌 것을 옮긴다. 옮겼으면 그 참조. */
    private fun adopt(monsters: Monsters, item: kr.inmc.core.item.StoredItem, hint: String): ItemRef.Namespaced? {
        if (ours(item.ref) || item.ref is ItemRef.Vanilla) return null
        val stack = monsters.itemResolver.create(item, 1)?.let { ItemRoles.sample(it, item) } ?: return null
        return ItemRoles.adopt(stack, hint)
    }

    /** 이 몹의 드랍 표에 더할 것 — 몹 정의의 표를 굴릴 때만(속성 표에는 안 더한다). */
    fun extraDrops(mob: ActiveMob, table: DropTable): List<MobDrop> {
        val def = mob.definition ?: return emptyList()
        if (table !== def.drops) return emptyList()
        return drops[def.id].orEmpty()
    }

    /** 몹 정의의 장비를 입힌 뒤 부른다 — 역할로 더한 장비를 그 칸에. */
    fun equip(entity: LivingEntity, definition: MobDefinition, resolver: ItemResolver) {
        val extra = equipment[definition.id] ?: return
        val gear = entity.equipment ?: return
        for ((slot, entry) in extra) {
            val stack = resolver.create(entry.item, 1) ?: continue
            gear.setItem(slot, stack, true)
            gear.setDropChance(slot, (entry.dropChance / 100.0).toFloat())
        }
    }
}
