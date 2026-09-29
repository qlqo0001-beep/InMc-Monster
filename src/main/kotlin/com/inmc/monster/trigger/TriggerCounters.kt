package com.inmc.monster.trigger

import kr.inmc.core.store.PlayerStore
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player progress towards each trigger.
 *
 * Three numbers per player per trigger: how many qualifying actions they have taken, when the
 * cooldown expires, and how many times it has fired today. Small individually, but a server with
 * a dozen triggers and a few thousand players over a year is not small, so entries expire.
 *
 * The expiry is the whole reason this class exists rather than a bare map. Without it the file
 * only ever grows, and the growth is invisible until a restart takes a minute to load it.
 */
class TriggerCounters(expiryDays: Int) {

    /**
     * 리로드로 갱신된다.
     *
     * 예전에는 리로드마다 이 객체를 통째로 다시 만들어 설정 변경을 반영했는데, 그 과정에서
     * 마지막 플러시 이후 쌓인 진행도가 조용히 사라졌다. 이제 객체는 그대로 두고 이 값만 바꾼다.
     */
    @Volatile
    var expiryDays: Int = expiryDays

    private class Progress {
        var count: Int = 0
        var cooldownUntil: Long = 0L
        var firedToday: Int = 0
        var dayStamp: Long = 0L
        var lastTouched: Long = System.currentTimeMillis()
    }

    private val players = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Progress>>()

    /**
     * 마지막 동기화 이후 값이 바뀐 플레이어.
     *
     * 공유 저장소는 플레이어 단위로 파일을 쓴다. 누가 바뀌었는지 모르면 플러시마다 전원의
     * 파일을 다시 쓰게 되고, 그건 종료 시 10초 예산 안에서 정작 바뀐 것을 잘리게 만든다.
     */
    private val touchedPlayers = ConcurrentHashMap.newKeySet<UUID>()

    val size: Int get() = players.size

    private fun progress(player: UUID, triggerId: String): Progress =
        players.computeIfAbsent(player) { ConcurrentHashMap() }
            .computeIfAbsent(triggerId) { Progress() }

    private fun touch(player: UUID) {
        touchedPlayers.add(player)
    }

    /** 공유 저장소에 아직 안 넘긴 변경이 있는지. [syncTo] 를 부를지 판단하는 데만 쓴다. */
    fun hasPending(): Boolean = touchedPlayers.isNotEmpty()

    /** True when the player is still on cooldown for this trigger. */
    fun onCooldown(player: UUID, triggerId: String, now: Long): Boolean =
        players[player]?.get(triggerId)?.let { it.cooldownUntil > now } ?: false

    fun cooldownRemaining(player: UUID, triggerId: String, now: Long): Long {
        val until = players[player]?.get(triggerId)?.cooldownUntil ?: return 0L
        return ((until - now) / 1000L).coerceAtLeast(0L)
    }

    /** True when the player has already hit today's cap. */
    fun atDailyLimit(player: UUID, trigger: Trigger, now: Long): Boolean {
        if (trigger.dailyLimit <= 0) return false
        val entry = players[player]?.get(trigger.id) ?: return false
        rollDay(entry, now)
        return entry.firedToday >= trigger.dailyLimit
    }

    /**
     * Records one qualifying action and reports whether the count threshold is now met.
     *
     * Resets the counter when the trigger asks for it, so "every ten logs" means every ten
     * rather than "ten, then every single one after that".
     */
    fun increment(player: UUID, trigger: Trigger, now: Long): Boolean {
        val entry = progress(player, trigger.id)
        entry.lastTouched = now
        entry.count++
        touch(player)
        if (entry.count < trigger.count) return false
        if (trigger.resetCounter) entry.count = 0
        return true
    }

    fun currentCount(player: UUID, triggerId: String): Int =
        players[player]?.get(triggerId)?.count ?: 0

    /** Called after the trigger actually fired: starts the cooldown and bumps the daily tally. */
    fun markFired(player: UUID, trigger: Trigger, now: Long) {
        val entry = progress(player, trigger.id)
        entry.lastTouched = now
        entry.cooldownUntil = now + trigger.cooldownSeconds * 1000L
        rollDay(entry, now)
        entry.firedToday++
        touch(player)
    }

    private fun rollDay(entry: Progress, now: Long) {
        val today = now / DAY_MILLIS
        if (entry.dayStamp != today) {
            entry.dayStamp = today
            entry.firedToday = 0
        }
    }

    fun forget(player: UUID) {
        players.remove(player)
        touch(player)
    }

    fun clear() {
        touchedPlayers.addAll(players.keys)
        players.clear()
    }

    /** Ticker hook: drops entries nobody has touched for [expiryDays]. */
    fun purge(now: Long): Int {
        val cutoff = now - expiryDays * DAY_MILLIS
        var removed = 0
        val emptyPlayers = ArrayList<UUID>()
        for ((id, entries) in players) {
            val stale = entries.entries.filter { it.value.lastTouched < cutoff }
            if (stale.isEmpty()) continue
            stale.forEach { entries.remove(it.key); removed++ }
            // 만료도 변경이다. 표시하지 않으면 저장소에는 지워진 항목이 그대로 남는다 —
            // 그러면 "파일이 자라기만 하는 것을 막는다"는 만료의 목적이 사라진다.
            touch(id)
            if (entries.isEmpty()) emptyPlayers.add(id)
        }
        emptyPlayers.forEach { players.remove(it) }
        return removed
    }

    // --- 공유 저장소 (inmc-core) ------------------------------------------------

    /**
     * 마지막 호출 이후 바뀐 플레이어만 공유 저장소에 반영한다.
     *
     * 저장소의 모양(`네임스페이스 → 대상 → 필드`)이 `trigger-progress.yml` 과 같아서,
     * 운영자가 `plugins/inmc-core/players/<uuid>.yml` 을 열어도 지금까지 보던 것과 같다.
     *
     * 사라진 트리거는 지운다. 값만 덮어쓰면 만료·삭제가 저장소에 반영되지 않는다.
     */
    fun syncTo(store: PlayerStore, namespace: String = NAMESPACE) {
        val batch = touchedPlayers.toList()
        if (batch.isEmpty()) return
        touchedPlayers.removeAll(batch.toSet())

        for (id in batch) {
            val entries = players[id]
            if (entries.isNullOrEmpty()) {
                // 우리 네임스페이스만 비운다. 통째로 지우면 core 의 profile 까지 날아간다.
                store.clear(id, namespace)
                continue
            }
            for (subject in store.subjects(id, namespace)) {
                // containsKey 를 명시한다 — ConcurrentHashMap 의 `in` 은 containsValue 다.
                if (!entries.containsKey(subject)) store.clearSubject(id, namespace, subject)
            }
            for ((triggerId, entry) in entries) {
                store.set(id, namespace, triggerId, "count", entry.count)
                store.set(id, namespace, triggerId, "cooldown-until", entry.cooldownUntil)
                store.set(id, namespace, triggerId, "fired-today", entry.firedToday)
                store.set(id, namespace, triggerId, "day", entry.dayStamp)
                store.set(id, namespace, triggerId, "touched", entry.lastTouched)
            }
        }
    }

    /** 부팅 시 공유 저장소에서 되읽는다. */
    fun loadFrom(store: PlayerStore, namespace: String = NAMESPACE) {
        players.clear()
        touchedPlayers.clear()
        for (id in store.knownPlayers()) {
            val subjects = store.subjects(id, namespace)
            if (subjects.isEmpty()) continue
            val entries = ConcurrentHashMap<String, Progress>()
            for (triggerId in subjects) {
                entries[triggerId] = Progress().apply {
                    count = store.getInt(id, namespace, triggerId, "count")
                    cooldownUntil = store.getLong(id, namespace, triggerId, "cooldown-until")
                    firedToday = store.getInt(id, namespace, triggerId, "fired-today")
                    dayStamp = store.getLong(id, namespace, triggerId, "day")
                    lastTouched = store.getLong(
                        id, namespace, triggerId, "touched", System.currentTimeMillis(),
                    )
                }
            }
            if (entries.isNotEmpty()) players[id] = entries
        }
    }

    companion object {
        const val DAY_MILLIS = 86_400_000L

        /** 공유 저장소에서 이 플러그인이 소유하는 이름. */
        const val NAMESPACE = "monster-trigger"

        fun fileOf(folder: File): File = File(folder, "trigger-progress.yml")
    }
}
