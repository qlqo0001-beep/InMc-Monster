package com.inmc.monster.integration

import com.inmc.monster.mob.StatKeys
import kr.inmc.core.integration.CustomItemHook
import org.bukkit.entity.LivingEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 몹이 입고 든 **커스텀아이템 장비**의 능력치(2026-10-09, 사용자 결정 "MI 를 쓰는 기능은 CI 로도") — MythicLib 이 MMOItems 장비를 읽는 자리의
 * 커스텀아이템 판. core `CustomItemHook.stats` 로 아이템의 최종 능력치를 받아 우리 스탯 키로 옮긴다. MythicLib 과 둘 다 있으면 둘 다 더한다
 * (한 아이템이 둘 다인 일은 없다).
 *
 * 맞을 때마다 여섯 칸의 PDC 를 읽지 않게 몹마다 1초 캐시한다 — 몹 장비는 거의 안 바뀐다.
 */
object CustomItemStats {

    /** 우리 스탯 키 → 더할 커스텀아이템 능력치 id. 바닐라 속성(방어력·속도 …)은 아이템 속성으로 이미 몹에 붙으니 여기 없다. */
    private val KEYS: Map<String, List<String>> = mapOf(
        "ATTACK_DAMAGE" to listOf("attack-damage", "damage-bonus"),
        StatKeys.CRITICAL_STRIKE_CHANCE to listOf("crit-chance"),
        StatKeys.CRITICAL_STRIKE_POWER to listOf("crit-power"),
        StatKeys.LIFESTEAL to listOf("lifesteal"),
        StatKeys.PROJECTILE_DAMAGE to listOf("projectile-damage"),
        StatKeys.DAMAGE_REDUCTION to listOf("damage-reduction"),
        StatKeys.DODGE_RATING to listOf("dodge-chance"),
        StatKeys.THORNS to listOf("thorns"),
        StatKeys.HEALTH_REGENERATION to listOf("health-regen"),
        StatKeys.COOLDOWN_REDUCTION to listOf("cooldown-reduction"),
    )

    private class Cached(val at: Long, val totals: Map<String, Double>)

    private val cache = ConcurrentHashMap<UUID, Cached>()

    /** 커스텀아이템 공급처가 꽂혀 있나(켤 때가 아니라 물을 때 본다 — 커스텀아이템은 대개 나중에 켜진다). */
    val isEnabled: Boolean get() = CustomItemHook.hasFirstParty()

    /** [entity] 가 입고 든 커스텀아이템 장비의 [key] 합. 공급처가 없거나 대응이 없으면 0. */
    fun equipmentStat(entity: LivingEntity, key: String): Double {
        if (!isEnabled) return 0.0
        val ids = KEYS[key] ?: return 0.0
        val totals = totals(entity)
        return ids.sumOf { totals[it] ?: 0.0 }
    }

    private fun totals(entity: LivingEntity): Map<String, Double> {
        val now = System.currentTimeMillis()
        cache[entity.uniqueId]?.let { if (now - it.at < TTL_MILLIS) return it.totals }
        val out = HashMap<String, Double>()
        val equipment = entity.equipment
        if (equipment != null) {
            for (stack in listOf(equipment.helmet, equipment.chestplate, equipment.leggings, equipment.boots, equipment.itemInMainHand, equipment.itemInOffHand)) {
                if (stack == null || stack.type.isAir) continue
                for ((id, value) in CustomItemHook.stats(stack)) out[id] = (out[id] ?: 0.0) + value
            }
        }
        cache[entity.uniqueId] = Cached(now, out)
        if (cache.size > 2000) cache.entries.removeIf { now - it.value.at > TTL_MILLIS }
        return out
    }

    /** 몹이 사라질 때. 안 불러도 1초 뒤엔 낡은 값이라 다시 센다. */
    fun forget(id: UUID) {
        cache.remove(id)
    }

    private const val TTL_MILLIS = 1000L
}
