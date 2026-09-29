package com.inmc.monster.scheduler

import com.inmc.monster.Monsters
import kr.inmc.core.scheduler.TickerBase

/**
 * The plugin's once-a-second task.
 *
 * Everything periodic that does not need tick precision lives here rather than in its own
 * scheduled job: spawner evaluation, respawn timers, boss-bar visibility, prompt expiry,
 * counter pruning and the debounced disk flushes. One task with a clear list is far easier to
 * account for on a timings report than a dozen anonymous ones.
 *
 * Each stage is isolated so one bad definition cannot stop the rest of the ticker.
 */
class Ticker(private val monsters: Monsters) : TickerBase(monsters.plugin) {

    override val periodTicks = PERIOD_TICKS

    override fun ready(): Boolean = monsters.ready

    override fun tick(now: Long) {
        val tick = monsters.currentTick()

        step("purge") {
            val removed = monsters.tracker.purgeDead()
            if (removed > 0 && monsters.config.debug) {
                monsters.logger.info("[정리] 죽은 몬스터 " + removed + "건을 인덱스에서 제거했습니다")
            }
        }
        step("lifespan") { expireMobs(tick) }
        step("live-refresh") {
            for (definition in monsters.mobs.drainLiveRefresh()) {
                val count = monsters.reapply(definition)
                if (count > 0 && monsters.config.debug) {
                    monsters.logger.info(
                        "[실시간 적용] " + definition.id + " - " + count + "마리에 반영했습니다",
                    )
                }
            }
        }
        step("spawners") { monsters.spawners.tick(tick) }
        step("respawns") { monsters.respawns.tick(now) }
        step("triggers") { monsters.triggers.tick(now) }
        step("bossbars") {
            for (mob in monsters.tracker.all()) {
                if (mob.bossBar != null && mob.isAlive) monsters.bossBars.tick(mob)
            }
        }
        step("prompts") { monsters.prompts.tick(now) }
        step("projectiles") { com.inmc.monster.skill.builtin.ProjectileTracker.purge(now) }
        step("counters") {
            // Once an hour is plenty for an expiry measured in days, and it keeps the common
            // tick cheap.
            if (tick % (3600L * 20L) < PERIOD_TICKS) monsters.triggers.counters.purge(now)
        }
        step("flush") {
            monsters.mobs.flushDirty()
            monsters.affixes.flushDirty()
            monsters.worlds.flushDirty()
            monsters.spawners.flushDirty()
            monsters.triggers.flushDirty()
            monsters.respawns.flush()
        }
    }

    /** Removes mobs that have outlived their configured lifespan. */
    private fun expireMobs(tick: Long) {
        val expired = monsters.tracker.all().filter { it.expiresAtTick in 1..tick }
        expired.forEach { monsters.removeMob(it) }
    }

    companion object {
        const val PERIOD_TICKS = 20L
    }
}

/**
 * The fast task: skill cooldowns, windups and pattern cursors.
 *
 * Separate from [Ticker] because it needs a much shorter period, and gated on there being any
 * custom mob at all - on a server where none are spawned it does nothing but a size check, and
 * it also owns the tick counter every cooldown in the plugin is measured against.
 */
class SkillTicker(private val monsters: Monsters) : TickerBase(monsters.plugin) {

    /** 관리자가 설정에서 바꾸는 값이라 [start] 때마다 다시 읽어야 한다. */
    override val periodTicks: Long get() = monsters.config.skillTickPeriod

    /**
     * 준비 검사를 여기서 하지 않는다 - 틱 카운터는 플러그인이 준비되기 전에도 흘러야 하고,
     * 그 카운터가 플러그인 안 모든 스킬 쿨다운의 기준이다. 진짜 게이트는 [tick] 안
     * `advanceTick` 바로 뒤에 있다.
     */
    override fun ready(): Boolean = true

    override fun tick(now: Long) {
        monsters.advanceTick(monsters.config.skillTickPeriod)
        if (!monsters.ready) return
        if (monsters.tracker.size == 0) return
        step("skills") { monsters.skills.tick(monsters.currentTick()) }
    }
}
