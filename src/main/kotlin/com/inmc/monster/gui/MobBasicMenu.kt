package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.mob.LevelSource
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.util.Numbers
import com.inmc.monster.util.Text
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player

/** Identity, entity type, model and level scaling. */
class MobBasicMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
) : Menu(monsters, 54, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(
            10,
            Icon.of(
                Material.NAME_TAG, "<yellow>표시명</yellow>",
                "<gray>현재: " + definition.displayName + "</gray>",
                "<dark_gray>MiniMessage 와 & 색상 코드를 모두 지원합니다.</dark_gray>",
                "<dark_gray>예: <red>부패한 기사</red></dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters, player, "표시할 이름을 입력하세요.",
                listOf(
                    "<gray>색상 코드와 MiniMessage 태그를 쓸 수 있습니다.</gray>",
                    "<dark_gray>예: &c부패한 기사   또는   <red>부패한 기사</red></dark_gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                definition.displayName = input
                save()
            }
        }

        set(
            12,
            Icon.of(
                spawnEgg(definition.entityType), "<yellow>엔티티 종류</yellow>",
                "<gray>현재: <white>" + definition.entityType.name + "</white></gray>",
                "<dark_gray>적대·중립·평화 몬스터와 동물 모두 지정할 수 있습니다.</dark_gray>",
                "<red>⚠ 이미 소환된 개체의 종류는 바뀌지 않습니다.</red>",
                "",
                "<yellow>▶ 클릭하여 스폰 알로 고르기</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            // Picked from a list of spawn eggs rather than typed. Asking an admin to spell
            // ZOMBIFIED_PIGLIN from memory, in chat, was the worst interaction in the plugin.
            EntityTypeMenu(monsters, definition).open(player)
        }

        set(
            14,
            Icon.of(
                if (monsters.models.isEnabled) Material.ARMOR_STAND else Material.BARRIER,
                "<yellow>ModelEngine 모델</yellow>",
                buildList {
                    add("<gray>현재: <white>" + definition.model.ifBlank { "없음 (바닐라 외형)" } + "</white></gray>")
                    add("")
                    if (monsters.models.isEnabled) {
                        add("<green>ModelEngine 연동 중입니다.</green>")
                        add("<gray>블루프린트 ID 를 입력하세요.</gray>")
                    } else {
                        add("<red>ModelEngine 이 설치되어 있지 않습니다.</red>")
                        add("<gray>지금 입력해 두면 설치 후 바로 적용됩니다.</gray>")
                    }
                    add("<dark_gray>모델 몬스터는 별도의 스폰 한도를 씁니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 좌클릭: 입력   우클릭: 제거</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) {
                definition.model = ""
                save(); redraw(player)
                return@set
            }
            Editors.promptText(
                monsters, player, "ModelEngine 블루프린트 ID 를 입력하세요.",
                listOf("<dark_gray>예: corrupted_knight</dark_gray>"),
                reopen = { reopen(player) },
            ) { input ->
                definition.model = input.trim()
                save()
            }
        }

        set(
            16,
            Icon.of(
                // LEAD rather than the chain block: the chain was renamed to IRON_CHAIN in 26.x
                // and a hard reference to either name breaks on the other version.
                Material.LEAD, "<yellow>상속 (부모 몬스터)</yellow>",
                buildList {
                    add("<gray>현재: <white>" + (definition.parent ?: "없음") + "</white></gray>")
                    add("")
                    add("<gray>공통 스탯을 한 곳에서 관리할 때 씁니다.</gray>")
                    add("<gray>자식이 지정하지 않은 값만 물려받습니다.</gray>")
                    add("<dark_gray>변경은 다음 리로드부터 적용됩니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 좌클릭: 지정   우클릭: 해제</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            if (event.isRightClick) {
                definition.parent = null
                save(); redraw(player)
                return@set
            }
            Editors.promptText(
                monsters, player, "부모로 삼을 몬스터 이름을 입력하세요.",
                listOf("<gray>등록된 몬스터: " + monsters.mobs.ids().take(8).joinToString(", ") + "</gray>"),
                reopen = { reopen(player) },
            ) { input ->
                val parent = monsters.mobs.get(input.trim())
                if (parent == null) {
                    monsters.messages.send(player, "mob-unknown", com.inmc.monster.util.Ph.of().mob(input))
                    return@promptText
                }
                if (parent.id == definition.id) {
                    player.sendMessage(Text.render("<red>자기 자신을 부모로 지정할 수 없습니다.</red>"))
                    return@promptText
                }
                definition.parent = parent.id
                save()
            }
        }

        // --- level ---------------------------------------------------------------

        set(
            28,
            Editors.intIcon(
                Material.EXPERIENCE_BOTTLE, "<yellow>기본 레벨</yellow>", definition.level.base,
                extra = listOf("<dark_gray>레벨 스케일링의 기준점입니다.</dark_gray>"),
            ),
        ) { event ->
            if (Editors.isPrompt(event)) {
                val player = event.whoClicked as? Player ?: return@set
                Editors.promptInt(monsters, player, "기본 레벨", 1, 10_000, { reopen(player) }) { value ->
                    definition.level.base = value
                    save()
                }
                return@set
            }
            definition.level.base = (definition.level.base + Editors.step(event, 1)).coerceIn(1, 10_000)
            save(); redraw(event.whoClicked)
        }

        set(
            29,
            Editors.intIcon(Material.IRON_NUGGET, "<yellow>최소 레벨</yellow>", definition.level.min),
        ) { event ->
            definition.level.min = (definition.level.min + Editors.step(event, 1)).coerceIn(1, 10_000)
            if (definition.level.min > definition.level.max) definition.level.max = definition.level.min
            save(); redraw(event.whoClicked)
        }

        set(
            30,
            Editors.intIcon(Material.GOLD_NUGGET, "<yellow>최대 레벨</yellow>", definition.level.max),
        ) { event ->
            definition.level.max = (definition.level.max + Editors.step(event, 1)).coerceIn(1, 10_000)
            if (definition.level.max < definition.level.min) definition.level.min = definition.level.max
            save(); redraw(event.whoClicked)
        }

        set(
            31,
            Icon.of(
                Material.COMPASS, "<yellow>레벨 결정 방식</yellow>",
                buildList {
                    add("<gray>현재: <white>" + definition.level.source.label + "</white></gray>")
                    add("<dark_gray>" + definition.level.source.description + "</dark_gray>")
                    add("")
                    addAll(Editors.optionList(LevelSource.entries.toList(), definition.level.source) { it.label })
                    addAll(Editors.cycleHint)
                },
            ),
        ) { event ->
            definition.level.source = Editors.cycle(event, LevelSource.entries.toList(), definition.level.source)
            save(); redraw(event.whoClicked)
        }

        set(
            32,
            Editors.numberIcon(
                Material.REPEATER, "<yellow>레벨 간격</yellow>", definition.level.step,
                extra = listOf(
                    "<dark_gray>'스폰 거리' 방식이면 몇 블록마다 +1 레벨인지,</dark_gray>",
                    "<dark_gray>'깊이' 방식이면 몇 칸마다 +1 레벨인지입니다.</dark_gray>",
                ),
                stepLabel = "10",
            ),
        ) { event ->
            definition.level.step = (definition.level.step + Editors.step(event, 10.0)).coerceAtLeast(0.1)
            save(); redraw(event.whoClicked)
        }

        set(
            34,
            Icon.of(
                Material.ANVIL, "<yellow>레벨당 증가율</yellow>",
                buildList {
                    if (definition.level.perLevel.isEmpty()) {
                        add("<gray>설정된 증가율이 없습니다.</gray>")
                        add("<dark_gray>레벨이 올라도 강해지지 않습니다.</dark_gray>")
                    } else {
                        definition.level.perLevel.asMap().forEach { (key, value) ->
                            add("<gray>" + key + ": <white>+" + Numbers.chance(value) + "%</white> / 레벨</gray>")
                        }
                    }
                    add("")
                    add("<gray>기본 레벨을 넘어선 레벨 1당 몇 % 씩</gray>")
                    add("<gray>올라가는지 지정합니다.</gray>")
                    add("")
                    add("<yellow>▶ 클릭하여 편집</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters, player, "스탯과 증가율을 입력하세요.",
                listOf(
                    "<gray>형식: <white>스탯이름 증가율</white>  (여러 개는 | 로 구분)</gray>",
                    "<gray>예: <white>MAX_HEALTH 8|ATTACK_DAMAGE 5</white></gray>",
                    "<gray>'없음' 을 입력하면 모두 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                if (input.trim() == "없음") {
                    definition.level.perLevel = com.inmc.monster.mob.StatMap()
                    save()
                    return@promptText
                }
                val map = com.inmc.monster.mob.StatMap()
                for (part in input.split('|')) {
                    val bits = part.trim().split(' ', limit = 2)
                    if (bits.size != 2) continue
                    val value = bits[1].trim().removeSuffix("%").toDoubleOrNull() ?: continue
                    map[bits[0].trim()] = value
                }
                definition.level.perLevel = map
                save()
            }
        }

        set(
            43,
            Icon.of(
                Material.FLOWER_BANNER_PATTERN, "<yellow>태그</yellow>",
                buildList {
                    add(
                        "<gray>현재: <white>" +
                            (if (definition.tags.isEmpty()) "없음" else definition.tags.joinToString(", ")) +
                            "</white></gray>",
                    )
                    add("")
                    add("<gray>던전 플러그인이 자기 몬스터만 찾아내고</gray>")
                    add("<gray>정리하는 데 쓰는 표식입니다.</gray>")
                    add("<dark_gray>/몹 정리 [태그] 로도 쓸 수 있습니다.</dark_gray>")
                    add("")
                    add("<yellow>▶ 클릭</yellow>")
                },
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            Editors.promptText(
                monsters, player, "태그를 입력하세요.",
                listOf(
                    "<gray>여러 개는 쉼표로 구분합니다.</gray>",
                    "<gray>'없음' 을 입력하면 모두 지웁니다.</gray>",
                ),
                reopen = { reopen(player) },
            ) { input ->
                definition.tags = if (input.trim() == "없음") {
                    linkedSetOf()
                } else {
                    input.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                        .toCollection(linkedSetOf())
                }
                save()
            }
        }

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { MobManageMenu(monsters, definition).open(it) }
        }
        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun spawnEgg(type: EntityType): Material =
        Material.matchMaterial(type.name + "_SPAWN_EGG") ?: Material.EGG

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    private fun reopen(player: Player) = MobBasicMenu(monsters, definition).open(player)

    companion object {
        private val TITLE = Text.renderFlat("<dark_gray>기본 정보</dark_gray>")
    }
}
