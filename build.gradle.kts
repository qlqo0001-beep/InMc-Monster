plugins {
    id("inmc.paper-plugin")
}

group = "com.inmc.monster"
version = "1.0.0"

inmc {
    paper = "26.2"
    pluginName = "inmc-monster"
    runMemory = "4G"
}

dependencies {
    // 선택 연동 — 런타임 필수 아님.
    // isTransitive = false 필수: 이 플러그인들이 Guava/Gson 버전을 strictly 로 못박아
    // paper-api 가 요구하는 버전과 충돌해 의존성 해석 자체가 실패한다.
    compileOnly(libs.placeholderapi) { isTransitive = false }
    compileOnly(libs.vault.api) { isTransitive = false }
    compileOnly(libs.worldguard.bukkit) { isTransitive = false }
    compileOnly(libs.worldguard.core) { isTransitive = false }
    compileOnly(libs.worldedit.core) { isTransitive = false }
    compileOnly(libs.worldedit.bukkit) { isTransitive = false }
    // MMOItems / MythicLib / ModelEngine / MythicMobs / MagicSpells / ItemsAdder 는
    // 100% 리플렉션 — compileOnly 조차 걸지 않는다. ReflectionPurityTest 가 이를 강제한다.
}
