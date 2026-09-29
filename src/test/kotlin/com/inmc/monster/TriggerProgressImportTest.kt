package com.inmc.monster

import com.inmc.monster.trigger.TriggerProgressImport
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `triggers/trigger-progress.yml` → core 공유 저장소 임포트.
 *
 * 이 트리에는 실제 진행도 파일이 없어(운영 서버에만 있다) 합성 YAML 로만 검증한다.
 * 그래서 파싱을 Bukkit 없이 도는 순수 함수로 떼어놓았다 — 임포터는 부팅 경로에 있고,
 * 거기서 예외가 나면 서버가 안 뜬다.
 */
class TriggerProgressImportTest {

    private val steve = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private fun yaml(build: YamlConfiguration.() -> Unit): YamlConfiguration =
        YamlConfiguration().apply(build).let {
            YamlConfiguration().apply { loadFromString(it.saveToString()) }
        }

    @Test
    fun `다섯 필드를 그대로 옮긴다`() {
        val config = yaml {
            set("$steve.광질.count", 7)
            set("$steve.광질.cooldown-until", 1_700_000_000_000L)
            set("$steve.광질.fired-today", 2)
            set("$steve.광질.day", 19_823L)
            set("$steve.광질.touched", 1_700_000_001_000L)
        }

        val entry = TriggerProgressImport.parse(config).single()

        assertEquals(steve, entry.playerId)
        assertEquals("광질", entry.triggerId)
        assertEquals(7, entry.count)
        assertEquals(1_700_000_000_000L, entry.cooldownUntil)
        assertEquals(2, entry.firedToday)
        assertEquals(19_823L, entry.day)
        assertEquals(1_700_000_001_000L, entry.touched)
    }

    @Test
    fun `한 플레이어의 여러 트리거를 모두 가져온다`() {
        val config = yaml {
            set("$steve.광질.count", 3)
            set("$steve.밤사냥.count", 5)
        }

        val entries = TriggerProgressImport.parse(config)

        assertEquals(setOf("광질", "밤사냥"), entries.map { it.triggerId }.toSet())
    }

    @Test
    fun `UUID 가 아닌 최상위 키는 건너뛰고 이유를 남긴다`() {
        val skipped = ArrayList<String>()
        val config = yaml {
            set("이건뭐지.광질.count", 1)
            set("$steve.광질.count", 3)
        }

        val entries = TriggerProgressImport.parse(config) { skipped.add(it) }

        assertEquals(listOf(steve), entries.map { it.playerId })
        assertEquals(1, skipped.size, "건너뛴 이유가 로그로 남아야 한다")
    }

    @Test
    fun `점이 든 트리거 이름은 예외가 아니라 건너뛴다`() {
        // 트리거 id 는 파일명에서 오므로 운영자가 `triggers/광질.보스.yml` 을 만들 수 있다.
        // 저장소는 점 든 대상 이름을 거부하는데, 그 경로가 블록 파괴 리스너라 예외가 나면
        // 서버가 죽는다. 임포트에서도 같은 이유로 던지지 않는다.
        val skipped = ArrayList<String>()
        val config = yaml {
            set("$steve.광질쩜보스.count", 1)
            set("$steve.정상.count", 2)
        }
        // YAML 경로 자체가 점을 계층으로 해석하므로, 실제 파일에서 점 든 id 가 어떻게
        // 보이는지를 직접 만들어 넣는다.
        val section = config.createSection("$steve")
        section.createSection("광질.보스").set("count", 1)

        val entries = TriggerProgressImport.parse(config) { skipped.add(it) }

        assertTrue(entries.none { '.' in it.triggerId }, "점 든 id 가 통과하면 안 된다")
    }

    @Test
    fun `빈 파일에서는 아무것도 나오지 않는다`() {
        assertTrue(TriggerProgressImport.parse(YamlConfiguration()).isEmpty())
    }

    @Test
    fun `없는 필드는 0 으로 채운다`() {
        // 옛 파일에 없던 필드가 생겼을 때 예외 대신 기본값으로 간다.
        val config = yaml { set("$steve.광질.count", 4) }

        val entry = TriggerProgressImport.parse(config).single()

        assertEquals(4, entry.count)
        assertEquals(0L, entry.cooldownUntil)
        assertEquals(0, entry.firedToday)
        assertEquals(0L, entry.touched)
    }
}
