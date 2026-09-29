package com.inmc.monster.gui

import com.inmc.monster.Monsters
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import java.io.File

/**
 * Server-wide settings.
 *
 * Unlike everything else in the plugin, these live in `config.yml` rather than in a registry, so
 * edits here rewrite that file and reload. That is slower than a dirty flag, but these values
 * change a handful of times in a server's life and the file stays the readable source of truth
 * with its comments intact.
 */
class GlobalSettingsMenu(monsters: Monsters) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val config = monsters.config

        set(
            4,
            Icon.of(
                Material.COMPARATOR, "<gold>전역 설정</gold>",
                "<gray>여기서 바꾼 값은 config.yml 에 기록되고</gray>",
                "<gray>즉시 다시 읽힙니다.</gray>",
                "<dark_gray>월드별 설정이 있으면 그쪽이 우선합니다.</dark_gray>",
            ),
        )

        // --- budget --------------------------------------------------------------
        intSetting(
            19, Material.BARRIER, "전역 최대 몬스터", "budget.global-max", config.budget.global, 10,
            listOf(
                "<gray>서버 전체에서 동시에 존재할 수 있는 수입니다.</gray>",
                "<dark_gray>확률 스폰을 켤 때 반드시 필요한 안전장치입니다.</dark_gray>",
            ),
        )

        intSetting(
            20, Material.GRASS_BLOCK, "월드별 최대", "budget.per-world-max", config.budget.perWorld, 10,
            listOf("<dark_gray>월드 설정에서 개별 값을 지정하면 그쪽이 우선합니다.</dark_gray>"),
        )

        intSetting(
            21, Material.CHEST, "청크당 최대", "budget.per-chunk-max", config.budget.perChunk, 1,
            listOf("<dark_gray>한 자리에 몬스터가 쌓이는 것을 막습니다.</dark_gray>"),
        )

        intSetting(
            22, Material.PLAYER_HEAD, "플레이어 주변 최대", "budget.near-player-max", config.budget.nearPlayer, 2,
            listOf(
                "<gray>반경 <white>" + config.budget.nearPlayerRadius + "</white> 블록 기준입니다.</gray>",
                "<dark_gray>플레이어가 '너무 많다' 고 느끼는 지점이 이 값입니다.</dark_gray>",
            ),
        )

        intSetting(
            23, Material.ARMOR_STAND, "모델 몬스터 최대", "budget.modelled-max", config.budget.modelled, 5,
            listOf(
                "<gray>ModelEngine 모델을 쓰는 몬스터 전용 상한입니다.</gray>",
                "<dark_gray>모델 하나가 여러 디스플레이 엔티티로 그려지므로</dark_gray>",
                "<dark_gray>일반 몬스터와 같은 한도를 쓰면 안 됩니다.</dark_gray>",
            ),
        )

        // --- replacement ---------------------------------------------------------
        set(
            28,
            Icon.of(
                if (config.replacement.enabled) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>자연스폰 치환 사용</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(config.replacement.enabled),
                "<dark_gray>끄면 어떤 몬스터도 자연스폰을 가로채지 않습니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            writeAndReload(event.whoClicked, "replacement.enabled", !config.replacement.enabled)
        }

        set(
            29,
            Editors.numberIcon(
                Material.LIGHT_BLUE_DYE, "<yellow>치환 확률 전역 배율</yellow>",
                config.replacement.chanceMultiplier, "배",
                extra = listOf("<dark_gray>모든 몬스터의 치환 확률에 곱해집니다.</dark_gray>"),
                stepLabel = "0.1",
            ),
        ) { event ->
            val value = (config.replacement.chanceMultiplier + Editors.step(event, 0.1)).coerceIn(0.0, 100.0)
            writeAndReload(event.whoClicked, "replacement.chance-multiplier", value)
        }

        set(
            30,
            Icon.of(
                Material.SPAWNER, "<yellow>치환 대상 스폰 사유</yellow>",
                buildList {
                    add("<gray>현재:</gray>")
                    config.replacement.allowedReasons.forEach { add("<white> · " + it.name + "</white>") }
                    add("")
                    add("<red>⚠ 기본값 NATURAL 하나에는 이유가 있습니다.</red>")
                    add("<dark_gray>REINFORCEMENTS - 좀비가 좀비를 부르는 경로라 증식합니다.</dark_gray>")
                    add("<dark_gray>BREEDING - 플레이어 동물 농장이 몬스터 농장이 됩니다.</dark_gray>")
                    add("<dark_gray>SPAWNER_EGG - 관리자가 스폰에그를 못 씁니다.</dark_gray>")
                    add("<dark_gray>SPAWNER - 몬스터 스포너 파밍장이 오염됩니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭하여 직접 입력</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "치환할 스폰 사유를 입력하세요. (쉼표로 여러 개)",
                listOf(
                    "<gray>예: <white>NATURAL</white>  또는  <white>NATURAL, PATROL</white></gray>",
                    "<red>하나씩, 의도를 가지고 추가하세요.</red>",
                ),
                reopen = { GlobalSettingsMenu(monsters).open(player) },
            ) { input ->
                val typed = input.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val reasons = typed.mapNotNull { raw ->
                    runCatching { SpawnReason.valueOf(raw.uppercase()) }.getOrNull()?.name
                }
                if (reasons.isEmpty()) {
                    player.sendMessage(Text.render("<red>인식된 스폰 사유가 없습니다.</red>"))
                    return@promptText
                }
                // Naming the rejects matters more than the count: a typo here silently produces
                // a narrower list than the admin thinks they saved.
                val rejected = typed.filter { it.uppercase() !in reasons }
                for (name in rejected) {
                    player.sendMessage(
                        Text.render("<red>'" + name + "' 은 스폰 사유가 아닙니다. 제외했습니다.</red>"),
                    )
                }
                writeAndReload(player, "replacement.allowed-spawn-reasons", reasons)
            }
        }

        // --- affixes -------------------------------------------------------------
        set(
            32,
            Icon.of(
                if (config.affixDefaults.vanillaEnabled) Material.NAME_TAG else Material.GRAY_DYE,
                "<yellow>바닐라 몬스터에 수식어</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(config.affixDefaults.vanillaEnabled),
                "<gray>확률: <white>" + Numbers.chance(config.affixDefaults.vanillaChance) + "%</white></gray>",
                "<dark_gray>평범한 좀비가 가끔 '강력한 좀비' 로 나오는 연출입니다.</dark_gray>",
                "<dark_gray>서버의 모든 자연스폰에 걸리므로 낮게 두세요.</dark_gray>",
                "",
                "<yellow>▶ 좌클릭: 켜기/끄기</yellow>",
                "<yellow>▶ 우클릭: 확률 +1 (Shift 로 -1)</yellow>",
            ),
        ) { event ->
            if (event.isRightClick) {
                val delta = if (event.isShiftClick) -1.0 else 1.0
                val value = (config.affixDefaults.vanillaChance + delta).coerceIn(0.0, 100.0)
                writeAndReload(event.whoClicked, "affix.vanilla-chance", value)
                return@set
            }
            writeAndReload(event.whoClicked, "affix.vanilla-enabled", !config.affixDefaults.vanillaEnabled)
        }

        set(
            33,
            Editors.numberIcon(
                Material.EXPERIENCE_BOTTLE, "<yellow>커스텀 몬스터 수식어 확률</yellow>",
                config.affixDefaults.customChance, "%",
                extra = listOf("<dark_gray>몬스터별로 따로 지정하면 그쪽이 우선합니다.</dark_gray>"),
                stepLabel = "1",
            ),
        ) { event ->
            val value = (config.affixDefaults.customChance + Editors.step(event, 1.0)).coerceIn(0.0, 100.0)
            writeAndReload(event.whoClicked, "affix.custom-chance", value)
        }

        // --- drops and display ---------------------------------------------------
        set(
            37,
            Editors.numberIcon(
                Material.CHEST, "<yellow>드랍 기본 확률</yellow>", config.dropDefaults.chance, "%",
                extra = listOf("<dark_gray>새 드랍을 등록할 때 들어가는 값입니다.</dark_gray>"),
                stepLabel = "5",
            ),
        ) { event ->
            val value = (config.dropDefaults.chance + Editors.step(event, 5.0)).coerceIn(0.01, 100.0)
            writeAndReload(event.whoClicked, "drops.default-chance", value)
        }

        set(
            38,
            Icon.of(
                if (config.display.nameplates) Material.NAME_TAG else Material.GRAY_DYE,
                "<yellow>이름표 표시</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(config.display.nameplates),
                "<dark_gray>체력이 실제로 변할 때만 갱신됩니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            writeAndReload(event.whoClicked, "display.nameplates", !config.display.nameplates)
        }

        set(
            39,
            Icon.of(
                if (config.display.bossBars) Material.DRAGON_EGG else Material.GRAY_DYE,
                "<yellow>보스바 표시</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(config.display.bossBars),
                "<dark_gray>몬스터별로도 켜야 실제로 표시됩니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            writeAndReload(event.whoClicked, "display.boss-bars", !config.display.bossBars)
        }

        set(
            40,
            Icon.of(
                if (config.triggers.enabled) Material.OAK_SAPLING else Material.GRAY_DYE,
                "<yellow>등장 조건 사용</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(config.triggers.enabled),
                "<gray>진행도 보관 기간: <white>" + config.triggers.counterExpiryDays + "일</white></gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            writeAndReload(event.whoClicked, "triggers.enabled", !config.triggers.enabled)
        }

        set(
            41,
            Editors.intIcon(
                Material.CHAIN_COMMAND_BLOCK, "<yellow>연쇄 소환 깊이 제한</yellow>",
                config.maxSpawnGeneration, "단계",
                extra = listOf(
                    "<gray>사망 소환과 소환 스킬이 이어질 수 있는 깊이입니다.</gray>",
                    "<red>⚠ A가 B를, B가 A를 소환하는 설정을 막는 유일한 장치입니다.</red>",
                    "<dark_gray>0 으로 두면 연쇄 소환이 아예 불가능해집니다.</dark_gray>",
                ),
            ),
        ) { event ->
            val value = (config.maxSpawnGeneration + Editors.step(event, 1)).coerceIn(0, 10)
            writeAndReload(event.whoClicked, "max-spawn-generation", value)
        }

        set(45, Icon.back()) { event -> (event.whoClicked as? Player)?.let { MainMenu(monsters).open(it) } }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun intSetting(
        slot: Int,
        material: Material,
        name: String,
        path: String,
        value: Int,
        step: Int,
        detail: List<String>,
    ) {
        set(
            slot,
            Editors.intIcon(material, "<yellow>" + name + "</yellow>", value, extra = detail, stepLabel = step.toString()),
        ) { event ->
            if (Editors.isPrompt(event)) {
                val player = event.whoClicked as? Player ?: return@set
                Editors.promptInt(
                    monsters.prompts, player, name, 0, 100_000, { GlobalSettingsMenu(monsters).open(player) },
                ) { writeAndReload(player, path, it) }
                return@set
            }
            writeAndReload(event.whoClicked, path, (value + Editors.step(event, step)).coerceAtLeast(0))
        }
    }

    /**
     * Writes one value into `config.yml` and reloads.
     *
     * Reading and rewriting the file rather than holding an in-memory copy means the admin's own
     * comments survive - the shipped config.yml explains why the defaults are what they are, and
     * silently replacing it with a generated file would throw that away.
     */
    private fun writeAndReload(who: org.bukkit.entity.HumanEntity, path: String, value: Any) {
        val player = who as? Player
        player?.closeInventory()

        monsters.io.async({
            val file: File = monsters.io.file("config.yml")
            val yaml = monsters.io.load(file)
            yaml.set(path, value)
            monsters.io.save(file, yaml)
            true
        }) {
            monsters.reload { count ->
                monsters.spawners.load {
                    monsters.triggers.load {
                        player?.let {
                            monsters.messages.send(it, "reloaded", com.inmc.monster.util.Ph.of().count(count))
                            GlobalSettingsMenu(monsters).open(it)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val TITLE = Text.renderFlat("<dark_gray>전역 설정</dark_gray>")
    }
}
