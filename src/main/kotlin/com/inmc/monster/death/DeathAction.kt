package com.inmc.monster.death

import com.inmc.monster.skill.SkillParam
import com.inmc.monster.skill.SkillParams
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

/**
 * What can happen when a mob dies - or when it enters a phase.
 *
 * Parameters are declared the same way skills declare theirs, so the GUI that edits a skill's
 * settings edits these too instead of needing a second, near-identical editor.
 */
enum class DeathActionType(
    val label: String,
    val icon: Material,
    val description: String,
    val parameters: List<SkillParam>,
) {

    SPAWN_MOB(
        "몬스터 소환", Material.ZOMBIE_SPAWN_EGG,
        "죽은 자리에 다른 몬스터를 생성합니다.",
        listOf(
            SkillParam.mob("mob", "소환할 몬스터"),
            SkillParam.int("amount", "마리 수", 1, 1, 20),
            SkillParam.double("spread", "퍼지는 반경", 2.0, 0.0, 16.0),
            SkillParam.bool("inherit-level", "레벨 물려주기", true),
        ),
    ),

    RUN_COMMAND(
        "명령어 실행", Material.COMMAND_BLOCK,
        "콘솔에서 명령어를 실행합니다. {플레이어네임} 등 치환자를 쓸 수 있습니다.",
        listOf(
            SkillParam.text("command", "명령어", "say {플레이어네임} 님이 처치했습니다"),
            SkillParam.bool("as-player", "플레이어 권한으로", false),
        ),
    ),

    BROADCAST(
        "메시지", Material.PAPER,
        "메시지를 보냅니다.",
        listOf(
            SkillParam.text("message", "내용", "<red>{몬스터}</red><gray>이(가) 쓰러졌다!</gray>"),
            SkillParam.enum("scope", "범위", "SERVER", listOf("SERVER", "RADIUS", "KILLER")),
            SkillParam.enum("style", "표시 방식", "CHAT", listOf("CHAT", "ACTIONBAR", "TITLE")),
            SkillParam.double("radius", "반경", 32.0, 1.0, 256.0),
        ),
    ),

    CAST_SKILL(
        "스킬 시전", Material.BLAZE_POWDER,
        "등록된 내장 스킬을 한 번 시전합니다.",
        listOf(
            SkillParam.text("skill", "스킬 ID", "explode"),
            SkillParam.double("radius", "반경", 6.0, 0.5, 64.0),
            SkillParam.double("damage", "피해량", 6.0, 0.0, 1000.0),
        ),
    ),

    EXPLODE(
        "폭발", Material.TNT,
        "폭발을 일으킵니다. 블록 파괴 여부를 고를 수 있습니다.",
        listOf(
            SkillParam.double("power", "폭발 위력", 3.0, 0.5, 20.0),
            SkillParam.bool("break-blocks", "블록 파괴", false),
            SkillParam.bool("fire", "불 붙이기", false),
        ),
    ),

    LIGHTNING(
        "번개", Material.LIGHTNING_ROD,
        "번개를 내립니다. 연출용과 실피해용을 구분할 수 있습니다.",
        listOf(
            SkillParam.int("count", "횟수", 1, 1, 20),
            SkillParam.double("spread", "퍼지는 반경", 3.0, 0.0, 32.0),
            SkillParam.bool("real-damage", "실제 피해 주기", false),
        ),
    ),

    GIVE_EXP(
        "경험치 지급", Material.EXPERIENCE_BOTTLE,
        "처치한 플레이어에게 경험치를 줍니다.",
        listOf(SkillParam.int("amount", "경험치", 50, 0, 100_000)),
    ),

    GIVE_MONEY(
        "재화 지급", Material.GOLD_INGOT,
        "처치한 플레이어에게 Vault 재화를 지급합니다.",
        listOf(SkillParam.double("amount", "금액", 1000.0, 0.0, 10_000_000.0)),
    ),

    HEAL_NEARBY(
        "주변 회복", Material.GOLDEN_APPLE,
        "죽으면서 주변 아군 몬스터의 체력을 회복시킵니다.",
        listOf(
            SkillParam.double("amount", "회복량", 20.0, 0.0, 10_000.0),
            SkillParam.double("radius", "반경", 10.0, 1.0, 64.0),
        ),
    ),

    POTION_KILLER(
        "처치자에게 포션", Material.SPLASH_POTION,
        "처치한 플레이어에게 포션 효과를 겁니다. 죽으면서 저주를 남기는 연출에 씁니다.",
        listOf(
            SkillParam.potion("effect", "포션 효과", "SLOWNESS"),
            SkillParam.int("duration", "지속 시간 (틱)", 100, 1, 72_000),
            SkillParam.int("amplifier", "강도", 0, 0, 4),
        ),
    ),

    RESPAWN_TIMER(
        "리스폰 예약", Material.CLOCK,
        "일정 시간 뒤 같은 자리에 다시 등장하도록 예약합니다. 월드보스에 씁니다.",
        listOf(
            SkillParam.int("seconds", "대기 시간 (초)", 1800, 10, 604_800),
            SkillParam.bool("announce", "등장 예고 방송", true),
        ),
    ),

    PLAY_EFFECT(
        "연출", Material.FIREWORK_ROCKET,
        "파티클과 효과음만 재생합니다.",
        listOf(
            SkillParam.particle(),
            SkillParam.sound(),
            SkillParam.int("count", "파티클 개수", 40, 1, 1000),
            SkillParam.double("spread", "퍼지는 반경", 1.5, 0.0, 16.0),
        ),
    );

    val parameterMap: Map<String, SkillParam> get() = parameters.associateBy { it.key }

    companion object {
        fun parse(raw: String?): DeathActionType? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** Where a death action's message or effect is aimed. */
enum class ActionScope { SERVER, RADIUS, KILLER }

/** How a message is presented. */
enum class ActionStyle { CHAT, ACTIONBAR, TITLE }

/**
 * One configured death (or phase-enter) action, with the conditions that gate it.
 *
 * [requirePlayerKill] defaults to true because the alternative is exploitable: a mob shoved
 * into lava would otherwise still run its reward commands.
 */
class DeathAction(
    var type: DeathActionType,
    var chance: Double = 100.0,
    var requirePlayerKill: Boolean = true,
    var worlds: MutableSet<String> = linkedSetOf(),
    var killerPermission: String = "",
    var enabled: Boolean = true,
    val values: MutableMap<String, Any> = LinkedHashMap(),
) {

    fun params(): SkillParams = SkillParams(values, type.parameterMap)

    fun copyOf(): DeathAction = DeathAction(
        type, chance, requirePlayerKill, LinkedHashSet(worlds), killerPermission, enabled,
        LinkedHashMap(values),
    )

    fun save(section: ConfigurationSection) {
        section.set("type", type.name)
        section.set("chance", chance)
        section.set("require-player-kill", requirePlayerKill)
        section.set("enabled", enabled)
        if (worlds.isNotEmpty()) section.set("worlds", worlds.toList())
        if (killerPermission.isNotBlank()) section.set("killer-permission", killerPermission)
        if (values.isNotEmpty()) {
            val target = section.createSection("values")
            values.forEach { (key, value) -> target.set(key, value) }
        }
    }

    companion object {
        fun load(section: ConfigurationSection): DeathAction? {
            val type = DeathActionType.parse(section.getString("type")) ?: return null
            val values = LinkedHashMap<String, Any>()
            section.getConfigurationSection("values")?.let { target ->
                for (key in target.getKeys(false)) target.get(key)?.let { values[key] = it }
            }
            return DeathAction(
                type = type,
                chance = section.getDouble("chance", 100.0).coerceIn(0.01, 100.0),
                requirePlayerKill = section.getBoolean("require-player-kill", true),
                worlds = section.getStringList("worlds").toCollection(linkedSetOf()),
                killerPermission = section.getString("killer-permission") ?: "",
                enabled = section.getBoolean("enabled", true),
                values = values,
            )
        }
    }
}

/** Convenience: read the enum-typed parameters back out of a params bag. */
fun SkillParams.scope(key: String = "scope"): ActionScope =
    runCatching { ActionScope.valueOf(string(key).uppercase()) }.getOrDefault(ActionScope.SERVER)

fun SkillParams.style(key: String = "style"): ActionStyle =
    runCatching { ActionStyle.valueOf(string(key).uppercase()) }.getOrDefault(ActionStyle.CHAT)

