package com.inmc.monster.integration

import com.inmc.monster.spawn.SpawnerRegistry
import com.inmc.monster.trigger.TriggerRegistry

/**
 * `/papi info monster` 의 목록(2026-10-10) — PlaceholderAPI 없이 시험하려고 [MonsterExpansion] 에서 뗐다.
 *
 * `tag_<태그>` 는 **살아 있는 몹이 실제로 달 수 있는 태그**만 — 몹의 태그는 하나(`SpawnService`: 넣은 태그, 없으면 정의의 첫 태그)라
 * 정의의 둘째 태그부터는 늘 0 이다. 스포너 몹은 스포너의 태그(비면 `spawner:<id>`), 등장 조건 몹은 `trigger:<id>`. 다른 플러그인이 API 로
 * 붙인 태그는 미리 알 수 없어 빠진다. 고정 키와 겹치는 것(몹 이름이 `total` 이면 `active_total`)은 한 번만.
 */
internal object MonsterPlaceholders {

    val FIXED: List<String> = listOf(
        "active_total", "definition_count", "spawner_count", "trigger_count", "respawn_pending", "active_world",
        "nearest", "nearest_distance", "nearest_health", "nearest_phase", "nearest_level",
    )

    /**
     * @param mobs 몹 id → 정의의 첫 태그(없으면 null)
     * @param spawners 스포너 id → 설정한 태그(비면 기본)
     * @param triggerIds 등장 조건 id
     */
    fun placeholders(
        identifier: String,
        mobs: List<Pair<String, String?>>,
        spawners: List<Pair<String, String>>,
        triggerIds: List<String>,
    ): List<String> {
        val keys = ArrayList<String>(FIXED)
        mobs.mapTo(keys) { "active_" + it.first }
        val tags = mobs.mapNotNull { it.second } +
            spawners.map { (id, tag) -> tag.ifBlank { SpawnerRegistry.SPAWNER_TAG_PREFIX + id } } +
            triggerIds.map { TriggerRegistry.TRIGGER_TAG_PREFIX + it }
        tags.map { it.lowercase() }.filter { it.isNotBlank() }.distinct().sorted().mapTo(keys) { "tag_$it" }
        for (id in triggerIds) {
            keys += "trigger_progress_$id"
            keys += "trigger_cooldown_$id"
        }
        return keys.distinct().map { "%" + identifier + "_" + it + "%" }
    }
}
