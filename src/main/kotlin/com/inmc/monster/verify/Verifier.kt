package com.inmc.monster.verify

import com.inmc.monster.Monsters
import com.inmc.monster.spawn.SpawnOptions
import com.inmc.monster.util.Ph
import org.bukkit.entity.Player
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `/몹 검증` — 정의·스킬 레지스트리·소환/태그/정리·수식어·순위를 서버 안에서 실제로 돌려 확인한다(드랍·상점 검증기와 같은 틀, 2026-10-08).
 *
 * - 소환은 검증하는 사람 머리 위 4칸에 **드랍 없음·예산 제외·규칙 무시**로 한 마리, 바로 지운다(태그 `zz_verify`).
 * - 스킬은 쏘지 않는다(입자·피해가 실제로 나간다) — 정의가 쓰는 스킬 id 가 전부 등록돼 있는지만 본다. 쏘아 보려면 `/몹 스킬테스트`.
 */
class Verifier(private val monsters: Monsters) {

    data class Result(val name: String, val failure: String?) {
        val skipped: Boolean get() = failure?.startsWith(SKIP) == true
    }

    private class Check(val name: String, val run: (Monsters, Player) -> String?)

    fun run(player: Player) {
        val results = try {
            CHECKS.map { check ->
                val failure = try {
                    check.run(monsters, player)
                } catch (t: Throwable) {
                    "검증기 오류: " + t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
                }
                Result(check.name, failure)
            }
        } finally {
            monsters.api.killAllByTag(TAG)
        }
        player.closeInventory()

        val failures = results.filter { it.failure != null && !it.skipped }
        val skips = results.filter { it.skipped }
        monsters.messages.send(
            player, "verify-done",
            Ph.of().count(results.size - failures.size - skips.size).mob(failures.size.toString())
                .item(if (skips.isEmpty()) "" else " · 건너뜀 ${skips.size}"),
        )
        for (f in failures) monsters.messages.send(player, "verify-failure", Ph.of().item("${f.name} — ${f.failure}"))
        for (s in skips) monsters.messages.send(player, "verify-skipped", Ph.of().item("${s.name} — ${s.failure!!.removePrefix(SKIP).trim()}"))

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = monsters.io.file("verify", "monster-$stamp.txt")
        val text = buildString {
            appendLine("# inmc-monster 검증 - ${LocalDateTime.now()} - ${player.name}")
            for (r in results) {
                appendLine((if (r.failure == null) "PASS " else if (r.skipped) "SKIP " else "FAIL ") + r.name + (r.failure?.let { " — $it" } ?: ""))
            }
        }
        monsters.io.asyncRun {
            file.parentFile.mkdirs()
            kr.inmc.core.util.AtomicFiles.write(file, text)
        }
        monsters.messages.send(player, "verify-report", Ph.of().item("plugins/${monsters.plugin.name}/verify/${file.name}"))
    }

    companion object {
        const val SKIP = "건너뜀:"
        const val TAG = "zz_verify"

        private fun ok(condition: Boolean, failure: String): String? = if (condition) null else failure

        private val CHECKS: List<Check> = listOf(
            Check("몬스터 정의 — 하나 이상 읽혔다") { m, _ ->
                ok(m.mobs.all().isNotEmpty(), "몬스터 정의가 하나도 없습니다")
            },
            Check("스킬 레지스트리 — 내장 스킬이 등록돼 있고 정의가 쓰는 스킬이 전부 있다") { m, _ ->
                ok(m.skills.registry.all().isNotEmpty(), "등록된 스킬이 없습니다") ?: run {
                    val missing = LinkedHashSet<String>()
                    for (def in m.mobs.all()) for (instance in def.skills) {
                        val id = instance.skillId
                        if (m.skills.registry[id] == null) missing += "${def.id}→$id"
                    }
                    ok(missing.isEmpty(), "없는 스킬을 쓰는 정의: " + missing.joinToString(", "))
                }
            },
            Check("소환(드랍 없음·예산 제외) → 태그로 세고 → 정리") { m, p ->
                val def = m.mobs.all().firstOrNull { !it.hasPhases } ?: m.mobs.all().firstOrNull() ?: return@Check "$SKIP 정의가 없습니다"
                // 평화로움 난이도에서는 서버가 적대 몹 생성을 거부한다(테섭 spawn 월드) — 다른 월드에서 돌려야 본다.
                if (p.world.difficulty == org.bukkit.Difficulty.PEACEFUL) return@Check "$SKIP '${p.world.name}' 은 평화로움 난이도라 몹을 못 만듭니다 — 다른 월드에서"
                val at = p.location.block.getRelative(0, 4, 0).location.add(0.5, 0.0, 0.5)
                val mob = m.api.spawn(def.id, at, SpawnOptions(tag = TAG, dropsEnabled = false, countsTowardBudget = false, ignoreRules = true, announce = false))
                    ?: return@Check "소환 실패: " + (m.api.lastRefusal() ?: "이유 없음")
                val counted = m.api.countWithTag(TAG)
                val custom = m.api.isCustom(mob.entity)
                val removed = m.api.killAllByTag(TAG)
                ok(counted == 1, "태그로 센 수 $counted (1 이어야)")
                    ?: ok(custom, "소환한 개체가 커스텀 몹으로 안 읽힙니다")
                    ?: ok(removed == 1, "정리한 수 $removed (1 이어야)")
                    ?: ok(m.api.countWithTag(TAG) == 0, "정리했는데 태그 수가 ${m.api.countWithTag(TAG)}")
                    ?: ok(mob.entity.isDead || !mob.entity.isValid, "정리했는데 개체가 살아 있습니다")
            },
            Check("수식어 — 정의가 읽혔고 id 가 비어 있지 않다") { m, _ ->
                val affixes = m.affixes.all()
                if (affixes.isEmpty()) return@Check "$SKIP 수식어 정의가 없습니다"
                ok(affixes.all { it.id.isNotBlank() }, "id 가 빈 수식어가 있습니다")
                    ?: ok(affixes.map { it.id.lowercase() }.toSet().size == affixes.size, "id 가 겹치는 수식어가 있습니다")
            },
            Check("월드 설정 — 지금 월드의 설정을 읽는다") { m, p ->
                ok(m.worlds.of(p.world).world.equals(p.world.name, ignoreCase = true) || m.worlds.all().isEmpty(), "'${p.world.name}' 설정이 ${m.worlds.of(p.world).world} 로 읽힙니다")
            },
            Check("관리 화면이 열린다") { m, p ->
                com.inmc.monster.gui.MobListMenu(m).open(p)
                val opened = p.openInventory.topInventory.holder is com.inmc.monster.gui.MobListMenu
                p.closeInventory()
                ok(opened, "관리 화면이 안 열렸습니다")
            },
        )
    }
}
