import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

group = "com.inmc.monster"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    maven("https://repo.helpch.at/releases") { name = "helpchat" }
    maven("https://jitpack.io") { name = "jitpack" }
    maven("https://maven.enginehub.org/repo/") { name = "enginehub" }
}

dependencies {
    compileOnly(libs.paper.api)

    // Soft integrations - never required at runtime.
    // Pulled in non-transitively: these plugins pin strict Guava/Gson versions that clash with
    // the ones Paper ships, and only their API signatures are needed here.
    compileOnly(libs.placeholderapi) { isTransitive = false }
    compileOnly(libs.vault.api) { isTransitive = false }
    compileOnly(libs.worldguard.bukkit) { isTransitive = false }
    compileOnly(libs.worldguard.core) { isTransitive = false }
    compileOnly(libs.worldedit.core) { isTransitive = false }
    compileOnly(libs.worldedit.bukkit) { isTransitive = false }
    // MMOItems / MythicLib / ModelEngine / MythicMobs / MagicSpells / ItemsAdder are reached
    // purely by reflection - no compile dependency, so a server without them still loads us.

    implementation(kotlin("stdlib"))

    testImplementation(libs.paper.api)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.jar {
    archiveClassifier.set("dev")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("inmc-monster")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    relocate("kotlin", "com.inmc.monster.libs.kotlin")
    relocate("org.jetbrains.annotations", "com.inmc.monster.libs.annotations")
    relocate("org.intellij.lang.annotations", "com.inmc.monster.libs.intellij")
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")
    exclude("module-info.class")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    minecraftVersion("26.2")
    jvmArgs("-Xmx4G")
}
