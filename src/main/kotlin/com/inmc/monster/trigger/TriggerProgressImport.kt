package com.inmc.monster.trigger

import kr.inmc.core.store.PlayerStore
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

/**
 * `triggers/trigger-progress.yml` 을 core 의 공유 저장소로 한 번 옮긴다.
 *
 * 두 파일의 모양이 같다 — 양쪽 다 `uuid → 트리거id → 필드` 다. 그래서 이 임포트는 사실상
 * 항등 변환이고, 그게 저장소를 3단으로 설계한 이유이기도 하다.
 *
 * **원본은 지우지 않고 이름만 바꾼다** (`trigger-progress.yml.imported`). 원본이 있으면
 * "아직 안 옮겼다" 는 뜻이고, 잘못된 상태에서 임포트가 돌았다면 그게 유일한 사본이다.
 */
object TriggerProgressImport {

    const val IMPORTED_SUFFIX = ".imported"

    /** 한 항목. 저장소의 `대상 → 필드` 하나에 그대로 대응한다. */
    data class Entry(
        val playerId: UUID,
        val triggerId: String,
        val count: Int,
        val cooldownUntil: Long,
        val firedToday: Int,
        val day: Long,
        val touched: Long,
    )

    /**
     * Bukkit 없이 도는 순수 파싱.
     *
     * 알 수 없는 모양은 예외 대신 건너뛴다. 손으로 고친 파일 한 줄 때문에 서버가 안 뜨는
     * 일은 없어야 한다 — 임포트는 부팅 경로에 있다.
     */
    fun parse(config: YamlConfiguration, onSkip: (String) -> Unit = {}): List<Entry> {
        val out = ArrayList<Entry>()
        for (playerKey in config.getKeys(false)) {
            val id = runCatching { UUID.fromString(playerKey) }.getOrNull()
            if (id == null) {
                onSkip("UUID 가 아닌 항목을 건너뜁니다: $playerKey")
                continue
            }
            val section = config.getConfigurationSection(playerKey) ?: continue
            for (triggerId in section.getKeys(false)) {
                val one = section.getConfigurationSection(triggerId) ?: continue
                // 트리거 id 는 저장소의 대상 이름이 된다. 점이 들어 있으면 저장 시점에
                // 예외가 나는데, 그 경로가 블록 파괴 리스너라 서버가 죽는다. 여기서 막는다.
                if ('.' in triggerId) {
                    onSkip("이름에 점이 있어 옮길 수 없는 진행도를 건너뜁니다: $playerKey / $triggerId")
                    continue
                }
                out.add(
                    Entry(
                        playerId = id,
                        triggerId = triggerId,
                        count = one.getInt("count", 0),
                        cooldownUntil = one.getLong("cooldown-until", 0L),
                        firedToday = one.getInt("fired-today", 0),
                        day = one.getLong("day", 0L),
                        touched = one.getLong("touched", 0L),
                    ),
                )
            }
        }
        return out
    }

    /**
     * 저장소에 반영한다. **이미 있는 항목은 건드리지 않는다.**
     *
     * 그래서 두 번 돌려도 결과가 같다. 임포트 뒤에 쌓인 진행도를 옛 파일이 되돌리는 일도 없다.
     */
    fun apply(store: PlayerStore, entries: List<Entry>, namespace: String = TriggerCounters.NAMESPACE): Int {
        var written = 0
        for (entry in entries) {
            if (store.subject(entry.playerId, namespace, entry.triggerId).isNotEmpty()) continue
            store.set(entry.playerId, namespace, entry.triggerId, "count", entry.count)
            store.set(entry.playerId, namespace, entry.triggerId, "cooldown-until", entry.cooldownUntil)
            store.set(entry.playerId, namespace, entry.triggerId, "fired-today", entry.firedToday)
            store.set(entry.playerId, namespace, entry.triggerId, "day", entry.day)
            store.set(entry.playerId, namespace, entry.triggerId, "touched", entry.touched)
            written++
        }
        return written
    }
}
