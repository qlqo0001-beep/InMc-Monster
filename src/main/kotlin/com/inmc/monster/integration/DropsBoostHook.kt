package com.inmc.monster.integration

import org.bukkit.Bukkit
import java.lang.reflect.Method
import java.util.logging.Logger

/**
 * inmc-drops 드랍 이벤트 배율을 읽는다 — `/드랍 배율`이 몬스터 드랍에도 같이 걸리게(테섭 2026-10-04).
 *
 * drops 쪽 안정 진입점(`DropsPlugin.boostFactor()`)을 리플렉션으로 부른다. 컴파일 의존은 두지
 * 않는다 — drops paper-plugin.yml 이 monster 를 join-classpath 로 물고 있어 역방향 간선을
 * 걸면 Paper 가 순환으로 끊는다. 없거나 꺼져 있거나 시그니처가 바뀌면 1.0(평시 그대로).
 *
 * 플러그인 인스턴스 기준으로 Method 를 캐시한다 — 리로드로 인스턴스가 바뀌면 다시 찾는다.
 */
class DropsBoostHook(private val logger: Logger) {

    @Volatile
    private var cachedPlugin: Any? = null

    @Volatile
    private var cachedMethod: Method? = null

    fun factor(): Double {
        val plugin = Bukkit.getPluginManager().getPlugin("inmc-drops") ?: return 1.0
        return try {
            var method = cachedMethod
            if (method == null || cachedPlugin !== plugin) {
                method = plugin.javaClass.getMethod("boostFactor")
                cachedPlugin = plugin
                cachedMethod = method
            }
            (method.invoke(plugin) as? Number)?.toDouble()?.takeIf { it > 0.0 } ?: 1.0
        } catch (t: Throwable) {
            cachedPlugin = null
            cachedMethod = null
            logger.fine("드랍 이벤트 배율을 읽지 못했습니다: ${t.message}")
            1.0
        }
    }
}
