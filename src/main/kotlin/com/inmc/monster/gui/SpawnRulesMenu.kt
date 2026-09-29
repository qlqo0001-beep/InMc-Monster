package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.spawn.SkyRule
import com.inmc.monster.spawn.SpawnRules
import com.inmc.monster.spawn.TimeRule
import com.inmc.monster.spawn.WeatherRule
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Numbers
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Placement conditions, shared by every spawn path.
 *
 * One screen rather than three near-identical ones, because a condition means the same thing
 * whether it gates a vanilla replacement, a spawner or a trigger - and an admin who learns it
 * once should not have to relearn it per feature.
 *
 * Takes callbacks rather than a definition so the caller decides what "save" and "back" mean.
 */
class SpawnRulesMenu(
    monsters: Monsters,
    private val rules: SpawnRules,
    private val label: String,
    private val onSave: () -> Unit,
    private val onBack: (Player) -> Unit,
) : Menu(monsters, 54, Text.renderFlat("<dark_gray>스폰 조건 <gray>|</gray> " + label + "</dark_gray>")) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(
            10,
            Icon.of(
                Material.GRASS_BLOCK, "<yellow>허용 월드</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (rules.worlds.isEmpty()) "모든 월드" else rules.worlds.joinToString(", ")) +
                            "</white></gray>",
                    )
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event -> promptSet(event, "허용할 월드 이름", rules.worlds) { rules.worlds = it } }

        set(
            11,
            Icon.of(
                Material.OAK_SAPLING, "<yellow>허용 바이옴</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (rules.biomes.isEmpty()) "모든 바이옴" else rules.biomes.joinToString(", ")) +
                            "</white></gray>",
                    )
                    add("<dark_gray>예: plains, dark_forest, desert</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event -> promptSet(event, "허용할 바이옴 (소문자)", rules.biomes) { rules.biomes = it } }

        set(
            12,
            Editors.intIcon(
                Material.LADDER, "<yellow>최소 Y 좌표</yellow>", rules.minY, "",
                stepLabel = "8",
            ),
        ) { event ->
            rules.minY = (rules.minY + Editors.step(event, 8)).coerceIn(-64, 320)
            if (rules.minY > rules.maxY) rules.maxY = rules.minY
            saveAndRedraw(event.whoClicked)
        }

        set(
            13,
            Editors.intIcon(
                Material.SCAFFOLDING, "<yellow>최대 Y 좌표</yellow>", rules.maxY, "",
                stepLabel = "8",
            ),
        ) { event ->
            rules.maxY = (rules.maxY + Editors.step(event, 8)).coerceIn(-64, 320)
            if (rules.maxY < rules.minY) rules.minY = rules.maxY
            saveAndRedraw(event.whoClicked)
        }

        set(
            14,
            Icon.of(
                Material.CLOCK, "<yellow>시간대</yellow>",
                buildList {
                    add("<gray>현재: <white>" + rules.time.label + "</white></gray>")
                    if (rules.time == TimeRule.CUSTOM) {
                        add("<gray>범위: <white>" + rules.timeFrom + " ~ " + rules.timeTo + "</white> 틱</gray>")
                        add("<dark_gray>자정을 넘는 구간(22000~2000)도 그대로 동작합니다.</dark_gray>")
                    }
                    add("")
                    addAll(Editors.optionList(TimeRule.entries.toList(), rules.time) { it.label })
                    addAll(Editors.cycleHint)
                    if (rules.time == TimeRule.CUSTOM) add("<dark_gray>숫자키: 시간 범위 입력</dark_gray>")
                },
            ),
        ) { event ->
            if (Editors.isPrompt(event) && rules.time == TimeRule.CUSTOM) {
                val player = event.whoClicked as? Player ?: return@set
                Editors.promptText(
                    monsters.prompts, player, "시간 범위를 입력하세요.",
                    listOf(
                        "<gray>형식: <white>시작 끝</white>  (0 ~ 24000 틱)</gray>",
                        "<gray>예: <white>13000 23000</white> (밤)</gray>",
                    ),
                    reopen = { reopen(player) },
                ) { input ->
                    val bits = input.trim().split(' ').filter { it.isNotBlank() }
                    if (bits.size == 2) {
                        rules.timeFrom = bits[0].toLongOrNull()?.coerceIn(0L, 24000L) ?: rules.timeFrom
                        rules.timeTo = bits[1].toLongOrNull()?.coerceIn(0L, 24000L) ?: rules.timeTo
                        onSave()
                    }
                }
                return@set
            }
            rules.time = Editors.cycle(event, TimeRule.entries.toList(), rules.time)
            saveAndRedraw(event.whoClicked)
        }

        set(
            15,
            Icon.of(
                Material.WATER_BUCKET, "<yellow>날씨</yellow>",
                buildList {
                    add("<gray>현재: <white>" + rules.weather.label + "</white></gray>")
                    add("")
                    addAll(Editors.optionList(WeatherRule.entries.toList(), rules.weather) { it.label })
                    addAll(Editors.cycleHint)
                },
            ),
        ) { event ->
            rules.weather = Editors.cycle(event, WeatherRule.entries.toList(), rules.weather)
            saveAndRedraw(event.whoClicked)
        }

        set(
            16,
            Icon.of(
                Material.GLASS, "<yellow>지상 / 동굴</yellow>",
                buildList {
                    add("<gray>현재: <white>" + rules.sky.label + "</white></gray>")
                    add("")
                    addAll(Editors.optionList(SkyRule.entries.toList(), rules.sky) { it.label })
                    addAll(Editors.cycleHint)
                },
            ),
        ) { event ->
            rules.sky = Editors.cycle(event, SkyRule.entries.toList(), rules.sky)
            saveAndRedraw(event.whoClicked)
        }

        set(
            19,
            Editors.intIcon(Material.TORCH, "<yellow>최소 밝기</yellow>", rules.minLight),
        ) { event ->
            rules.minLight = (rules.minLight + Editors.step(event, 1)).coerceIn(0, 15)
            if (rules.minLight > rules.maxLight) rules.maxLight = rules.minLight
            saveAndRedraw(event.whoClicked)
        }

        set(
            20,
            Editors.intIcon(Material.GLOWSTONE, "<yellow>최대 밝기</yellow>", rules.maxLight),
        ) { event ->
            rules.maxLight = (rules.maxLight + Editors.step(event, 1)).coerceIn(0, 15)
            if (rules.maxLight < rules.minLight) rules.minLight = rules.maxLight
            saveAndRedraw(event.whoClicked)
        }

        set(
            21,
            Icon.of(
                Material.DIRT, "<yellow>밟고 설 블록</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (rules.standingOn.isEmpty()) "제한 없음" else rules.standingOn.joinToString(", ") { it.name }) +
                            "</white></gray>",
                    )
                    add("<dark_gray>이 블록 위에서만 생성됩니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "밟고 설 블록 종류를 입력하세요. (쉼표로 여러 개)",
                listOf(
                    "<gray>예: <white>GRASS_BLOCK, SAND, STONE</white></gray>",
                    "<gray>'없음' 을 입력하면 제한을 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                val set = java.util.EnumSet.noneOf(Material::class.java)
                if (input.trim() != "없음") {
                    input.split(',').forEach { raw ->
                        Material.matchMaterial(raw.trim())?.let { set.add(it) }
                    }
                }
                rules.standingOn = set
                onSave()
            }
        }

        set(
            23,
            Editors.intIcon(
                Material.SPYGLASS, "<yellow>플레이어 탐지 반경</yellow>", rules.radius, "블록",
                extra = listOf("<dark_gray>아래 인원 조건을 판정하는 범위입니다.</dark_gray>"),
                stepLabel = "8",
            ),
        ) { event ->
            rules.radius = (rules.radius + Editors.step(event, 8)).coerceIn(1, 256)
            saveAndRedraw(event.whoClicked)
        }

        set(
            24,
            Editors.intIcon(
                Material.PLAYER_HEAD, "<yellow>최소 주변 인원</yellow>", rules.minNearbyPlayers, "명",
                extra = listOf("<dark_gray>0 이면 조건 없음.</dark_gray>"),
            ),
        ) { event ->
            rules.minNearbyPlayers = (rules.minNearbyPlayers + Editors.step(event, 1)).coerceAtLeast(0)
            saveAndRedraw(event.whoClicked)
        }

        set(
            25,
            Editors.intIcon(
                Material.SKELETON_SKULL, "<yellow>최대 주변 인원</yellow>", rules.maxNearbyPlayers, "명",
                extra = listOf("<dark_gray>0 이면 상한 없음. 사람이 몰리면 안 나오게 할 때 씁니다.</dark_gray>"),
            ),
        ) { event ->
            rules.maxNearbyPlayers = (rules.maxNearbyPlayers + Editors.step(event, 1)).coerceAtLeast(0)
            saveAndRedraw(event.whoClicked)
        }

        set(
            28,
            Editors.numberIcon(
                Material.BARRIER, "<yellow>최소 플레이어 거리</yellow>", rules.minPlayerDistance, "블록",
                extra = listOf(
                    "<dark_gray>이보다 가까운 곳에는 생성되지 않습니다.</dark_gray>",
                    "<dark_gray>플레이어 코앞에 몬스터가 튀어나오는 것을 막습니다.</dark_gray>",
                ),
                stepLabel = "1",
            ),
        ) { event ->
            rules.minPlayerDistance =
                (rules.minPlayerDistance + Editors.step(event, 1.0)).coerceIn(0.0, 256.0)
            saveAndRedraw(event.whoClicked)
        }

        set(
            30,
            Icon.of(
                Material.MAP, "<yellow>허용 지역 (WorldGuard)</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (rules.allowedRegions.isEmpty()) "제한 없음" else rules.allowedRegions.joinToString(", ")) +
                            "</white></gray>",
                    )
                    if (!monsters.regions.isEnabled) {
                        add("<dark_gray>WorldGuard 가 없어 이 조건은 무시됩니다.</dark_gray>")
                    }
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            promptSet(event, "허용할 지역 이름", rules.allowedRegions) { rules.allowedRegions = it }
        }

        set(
            31,
            Icon.of(
                Material.RED_BANNER, "<yellow>차단 지역 (WorldGuard)</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (rules.blockedRegions.isEmpty()) "없음" else rules.blockedRegions.joinToString(", ")) +
                            "</white></gray>",
                    )
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            promptSet(event, "차단할 지역 이름", rules.blockedRegions) { rules.blockedRegions = it }
        }

        set(
            32,
            Icon.of(
                if (rules.allowInClaims) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>클레임 안에서도 생성</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(rules.allowInClaims),
                "<dark_gray>Lands 등으로 보호된 땅 안쪽입니다.</dark_gray>",
                "<red>⚠ 켜면 플레이어 기지 안에 몬스터가 나타날 수 있습니다.</red>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            rules.allowInClaims = !rules.allowInClaims
            saveAndRedraw(event.whoClicked)
        }

        set(
            34,
            Icon.of(
                Material.ENDER_PEARL, "<yellow>달 위상</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (rules.moonPhases.isEmpty()) "상관없음" else rules.moonPhases.joinToString(", ")) +
                            "</white></gray>",
                    )
                    add("<dark_gray>0 = 보름달, 4 = 그믐달</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters.prompts, player, "허용할 달 위상을 입력하세요. (0~7, 쉼표로 여러 개)",
                listOf("<gray>'없음' 을 입력하면 제한을 지웁니다.</gray>"),
                reopen = { reopen(player) },
            ) { input ->
                rules.moonPhases = if (input.trim() == "없음") {
                    linkedSetOf()
                } else {
                    input.split(',').mapNotNull { it.trim().toIntOrNull() }
                        .filter { it in 0..7 }.toCollection(linkedSetOf())
                }
                onSave()
            }
        }

        set(
            49,
            Icon.of(
                Material.PAPER, "<yellow>요약</yellow>",
                listOf(
                    "<gray>Y: <white>" + rules.minY + " ~ " + rules.maxY + "</white></gray>",
                    "<gray>밝기: <white>" + rules.minLight + " ~ " + rules.maxLight + "</white></gray>",
                    "<gray>시간: <white>" + rules.time.label + "</white>   날씨: <white>" + rules.weather.label + "</white></gray>",
                    "<gray>최소 거리: <white>" + Numbers.chance(rules.minPlayerDistance) + "</white></gray>",
                ),
            ),
        )

        set(45, Icon.back()) { event -> (event.whoClicked as? Player)?.let(onBack) }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun promptSet(
        event: org.bukkit.event.inventory.InventoryClickEvent,
        label: String,
        current: Set<String>,
        setter: (MutableSet<String>) -> Unit,
    ) {
        val player = event.whoClicked as? Player ?: return
        Editors.promptText(
            monsters.prompts, player, label + " 을(를) 입력하세요. (쉼표로 여러 개)",
            listOf(
                "<gray>현재: " + (if (current.isEmpty()) "없음" else current.joinToString(", ")) + "</gray>",
                "<gray>'없음' 을 입력하면 제한을 지웁니다.</gray>",
            ),
            reopen = { reopen(player) },
        ) { input ->
            setter(
                if (input.trim() == "없음") linkedSetOf()
                else input.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toCollection(linkedSetOf()),
            )
            onSave()
        }
    }

    private fun saveAndRedraw(who: org.bukkit.entity.HumanEntity) {
        onSave()
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) =
        SpawnRulesMenu(monsters, rules, label, onSave, onBack).open(player)
}
