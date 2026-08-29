package com.inmc.monster.affix

import com.inmc.monster.config.ConfigService
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import java.io.File
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Every affix, in one `affixes.yml`.
 *
 * A single file rather than one per affix: affixes are short, there are rarely more than a few
 * dozen, and an admin balancing them wants to see them next to each other.
 */
class AffixRegistry(
    private val io: ConfigService,
    private val logger: Logger,
) {

    private val affixes = ConcurrentHashMap<String, Affix>()

    @Volatile
    private var dirty = false

    private val file: File get() = io.file("affixes.yml")

    val size: Int get() = affixes.size

    /** True when nothing is configured, so the spawn path can skip affix rolling entirely. */
    val isEmpty: Boolean get() = affixes.isEmpty()

    fun all(): List<Affix> = affixes.values.sortedWith(compareBy({ it.type.ordinal }, { it.id.lowercase() }))

    fun get(id: String?): Affix? {
        if (id == null) return null
        return affixes[id.lowercase()] ?: affixes.values.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    fun exists(id: String): Boolean = get(id) != null

    fun create(id: String): Affix? {
        if (!isValidId(id) || exists(id)) return null
        val affix = Affix(id)
        affixes[id.lowercase()] = affix
        markDirty()
        return affix
    }

    fun delete(id: String): Boolean {
        val removed = affixes.remove(id.lowercase()) != null
        if (removed) markDirty()
        return removed
    }

    fun markDirty() {
        dirty = true
    }

    /**
     * Candidates of one [type] that accept this mob, with their weights.
     *
     * [allowed] and [blocked] come from the mob's own affix settings, so a boss can be given a
     * hand-picked affix pool without touching the affixes themselves.
     */
    fun candidates(
        type: AffixType,
        target: AffixTarget,
        entityType: EntityType,
        world: String,
        level: Int,
        allowed: Set<String>,
        blocked: Set<String>,
    ): List<Affix> = affixes.values.filter { affix ->
        affix.type == type &&
            affix.accepts(target, entityType, world) &&
            level >= affix.minLevel &&
            !blocked.contains(affix.id.lowercase()) &&
            (allowed.isEmpty() || allowed.contains(affix.id.lowercase()))
    }

    /** Weighted pick from [candidates]; null when the list is empty. */
    fun pick(candidates: List<Affix>, rng: Random): Affix? {
        if (candidates.isEmpty()) return null
        val total = candidates.sumOf { it.weight }
        if (total <= 0) return candidates[rng.nextInt(candidates.size)]
        var cursor = rng.nextInt(total)
        for (affix in candidates) {
            cursor -= affix.weight
            if (cursor < 0) return affix
        }
        return candidates.last()
    }

    // --- persistence -----------------------------------------------------------

    fun loadAll(then: (Int) -> Unit) {
        io.async({
            val target = file
            if (!target.exists()) io.copyDefault("affixes.yml", target)
            val config = io.load(target)
            config.getKeys(false).mapNotNull { id ->
                val section = config.getConfigurationSection(id) ?: return@mapNotNull null
                try {
                    id to Affix.load(id, section)
                } catch (t: Throwable) {
                    logger.severe("수식어를 읽지 못했습니다 (" + id + "): " + t.message)
                    null
                }
            }
        }) { loaded ->
            affixes.clear()
            dirty = false
            loaded.forEach { (id, affix) -> affixes[id.lowercase()] = affix }
            then(loaded.size)
        }
    }

    fun flushDirty() {
        if (!dirty) return
        dirty = false
        val text = serialise() ?: return
        io.asyncRun {
            try {
                file.parentFile?.mkdirs()
                file.writeText(text, Charsets.UTF_8)
            } catch (t: Throwable) {
                logger.severe("수식어 저장 실패: " + t.message)
            }
        }
    }

    fun flushBlocking() {
        if (!dirty) return
        dirty = false
        val text = serialise() ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeText(text, Charsets.UTF_8)
        } catch (t: Throwable) {
            logger.severe("수식어 저장 실패: " + t.message)
        }
    }

    private fun serialise(): String? {
        val config = YamlConfiguration()
        return try {
            affixes.values.forEach { affix -> affix.save(config.createSection(affix.id)) }
            HEADER + config.saveToString()
        } catch (t: Throwable) {
            logger.severe("수식어 직렬화 실패: " + t.message)
            null
        }
    }

    companion object {
        private val ID_PATTERN = Regex("[A-Za-z0-9가-힣_-]{1,24}")

        private const val HEADER =
            "# 몬스터 수식어(접두/접미) - /몹 GUI 에서 편집할 수 있습니다.\n" +
                "# mult 는 곱연산, add 는 합연산이며 항상 mult 를 먼저 적용합니다.\n\n"

        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)
    }
}
