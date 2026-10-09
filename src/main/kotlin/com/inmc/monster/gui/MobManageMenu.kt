package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.spawn.SpawnOptions
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Numbers
import com.inmc.monster.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Everything about one mob, one click away.
 *
 * Sections are grouped by what an admin is trying to do rather than by how the data is stored:
 * "how strong is it" (stats, equipment), "what does it do" (skills, phases, death), "where does
 * it come from" (spawn rules), "what does it give" (drops).
 */
class MobManageMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
) : Menu(monsters, 54, title(definition)) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(4, summaryIcon())

        section(
            19, Material.NAME_TAG, "기본 정보",
            listOf(
                "<gray>표시명: " + definition.displayName + "</gray>",
                "<gray>종류: <white>" + definition.entityType.name + "</white></gray>",
                "<gray>레벨: <white>" + definition.level.base + "</white> <dark_gray>(" + definition.level.source.label + ")</dark_gray></gray>",
                "<gray>모델: <white>" + definition.model.ifBlank { "없음" } + "</white></gray>",
            ),
        ) { player -> MobBasicMenu(monsters, definition).open(player) }

        section(
            20, Material.DIAMOND_SWORD, "스탯",
            listOf(
                "<gray>체력 <red>" + Numbers.chance(definition.maxHealth()) + "</red>" +
                    "   공격력 <red>" + Numbers.chance(definition.stats.getOrZero("ATTACK_DAMAGE")) + "</red></gray>",
                "<gray>커스텀 스탯: <white>" + definition.customStats.keys.size + "개</white></gray>",
                "<dark_gray>치명타·관통·회피 등은 커스텀 스탯입니다.</dark_gray>",
            ),
        ) { player -> StatMenu(monsters, definition).open(player) }

        section(
            21, Material.IRON_CHESTPLATE, "장비",
            buildList {
                if (definition.equipment.isEmpty) {
                    add("<gray>착용 장비가 없습니다.</gray>")
                } else {
                    definition.equipment.entries().forEach { (slot, entry) ->
                        add(
                            "<gray>" + com.inmc.monster.mob.MobEquipment.label(slot) + ": <white>" +
                                entry.item.label() + "</white> <dark_gray>(드랍 " +
                                Numbers.chance(entry.dropChance) + "%)</dark_gray></gray>",
                        )
                    }
                }
                add("")
                add(
                    when {
                        monsters.mythicLib.isEnabled && com.inmc.monster.integration.CustomItemStats.isEnabled ->
                            "<green>MythicLib·커스텀아이템 연동 중 - 장비 스탯이 적용됩니다.</green>"
                        monsters.mythicLib.isEnabled -> "<green>MythicLib 연동 중 - MMOItems 스탯이 적용됩니다.</green>"
                        com.inmc.monster.integration.CustomItemStats.isEnabled -> "<green>커스텀아이템 연동 중 - 장비의 능력치가 적용됩니다.</green>"
                        else -> "<yellow>MythicLib·커스텀아이템이 없어 장비 스탯은 반영되지 않습니다.</yellow>"
                    },
                )
            },
        ) { player -> EquipmentMenu(monsters, definition).open(player) }

        section(
            22, Material.CHEST, "드랍 아이템",
            listOf(
                "<gray>등록된 드랍: <white>" + definition.drops.entries.size + "종</white></gray>",
                "<gray>배출 개수: <white>" + definition.drops.minRolls + " ~ " + definition.drops.maxRolls + "</white></gray>",
                "<gray>분배 방식: <white>" + definition.drops.distribution.label + "</white></gray>",
                "<gray>경험치: <white>" + definition.drops.expMin + " ~ " + definition.drops.expMax + "</white></gray>",
            ),
        ) { player -> DropListMenu(monsters, DropContext.of(monsters, definition)).open(player) }

        section(
            23, Material.BLAZE_POWDER, "스킬",
            buildList {
                add("<gray>등록된 스킬: <white>" + definition.skills.size + "개</white></gray>")
                definition.skills.take(4).forEach { instance ->
                    val skill = monsters.skills.registry[instance.skillId]
                    add(
                        "<dark_gray> · " + (skill?.displayName ?: instance.skillId) +
                            " (" + instance.trigger.label + ")</dark_gray>",
                    )
                }
                if (definition.skills.size > 4) add("<dark_gray> · ...</dark_gray>")
            },
        ) { player -> SkillListMenu(monsters, definition).open(player) }

        section(
            24, Material.HEART_OF_THE_SEA, "패턴 / 페이즈",
            buildList {
                if (definition.phases.isEmpty()) {
                    add("<gray>페이즈가 없습니다.</gray>")
                    add("<dark_gray>체력 구간마다 다른 행동을 주려면 추가하세요.</dark_gray>")
                } else {
                    definition.phases.sortedByDescending { it.healthAbove }.forEach { phase ->
                        add(
                            "<dark_gray> · " + phase.name + " (체력 " +
                                Numbers.chance(phase.healthAbove) + "% 이상, 스텝 " + phase.steps.size + ")</dark_gray>",
                        )
                    }
                }
            },
        ) { player -> PhaseListMenu(monsters, definition).open(player) }

        section(
            25, Material.SKELETON_SKULL, "사망 이벤트",
            listOf(
                "<gray>등록된 동작: <white>" + definition.deathActions.size + "개</white></gray>",
                "<gray>죽을 때 다른 몬스터를 소환하거나</gray>",
                "<gray>명령어를 실행할 수 있습니다.</gray>",
                "<dark_gray>연쇄 소환은 깊이 " + monsters.config.maxSpawnGeneration + " 까지만 허용됩니다.</dark_gray>",
            ),
        ) { player ->
            ActionListMenu(
                monsters, definition, definition.deathActions, "사망 이벤트 | " + definition.id,
                onBack = { MobManageMenu(monsters, definition).open(it) },
            ).open(player)
        }

        section(
            29, Material.COMPASS, "자연 스폰 설정",
            listOf(
                "<gray>치환: </gray>" + Icon.toggle(definition.replacement.enabled),
                "<gray>확률: <white>" + Numbers.chance(definition.replacement.chance) + "%</white>" +
                    "   가중치: <white>" + definition.replacement.weight + "</white></gray>",
                "<gray>대상: <white>" +
                    (if (definition.replacement.replaces.isEmpty()) "자기 종류만" else definition.replacement.replaces.joinToString(", ") { it.name }) +
                    "</white></gray>",
                "<dark_gray>바닐라 자연 스폰을 확률적으로 가로챕니다.</dark_gray>",
            ),
        ) { player -> ReplacementMenu(monsters, definition).open(player) }

        section(
            30, Material.REDSTONE_TORCH, "특성 / AI",
            listOf(
                "<gray>AI: </gray>" + Icon.toggle(definition.flags.aiEnabled) +
                    "  <gray>아기: </gray>" + Icon.toggle(definition.flags.baby),
                "<gray>햇빛 연소: </gray>" + Icon.toggle(definition.flags.burnInSunlight) +
                    "  <gray>보스바: </gray>" + Icon.toggle(definition.flags.bossBar),
                "<gray>수명: <white>" +
                    (if (definition.flags.lifespanSeconds > 0) definition.flags.lifespanSeconds.toString() + "초" else "무제한") +
                    "</white></gray>",
            ),
        ) { player -> FlagsMenu(monsters, definition).open(player) }

        section(
            31, Material.SHIELD, "면역 / 저항",
            buildList {
                val immunities = definition.immunities
                if (immunities.isEmpty && immunities.knockbackResistance <= 0.0) {
                    add("<gray>설정된 면역이 없습니다.</gray>")
                    add("<dark_gray>보스가 절벽으로 밀려나거나 낙사하는 사고를</dark_gray>")
                    add("<dark_gray>막으려면 넉백·낙하 면역을 켜세요.</dark_gray>")
                } else {
                    add("<gray>넉백 저항: <white>" + Numbers.chance(immunities.knockbackResistance * 100.0) + "%</white></gray>")
                    com.inmc.monster.mob.Immunities.SIMPLE_LABELS.forEach { (key, label) ->
                        if (isImmune(key)) add("<dark_gray> · " + label + "</dark_gray>")
                    }
                    if (immunities.potions.isNotEmpty()) {
                        add("<dark_gray> · 포션 " + immunities.potions.size + "종</dark_gray>")
                    }
                }
            },
        ) { player -> ImmunityMenu(monsters, definition).open(player) }

        section(
            32, Material.EXPERIENCE_BOTTLE, "수식어 허용",
            listOf(
                "<gray>수식어: </gray>" + Icon.toggle(definition.affixes.enabled),
                "<gray>확률: <white>" +
                    (if (definition.affixes.chance >= 0.0) Numbers.chance(definition.affixes.chance) + "%" else "전역 기본값") +
                    "</white></gray>",
                "<gray>허용 목록: <white>" +
                    (if (definition.affixes.allowed.isEmpty()) "전체" else definition.affixes.allowed.size.toString() + "종") +
                    "</white></gray>",
            ),
        ) { player -> MobAffixMenu(monsters, definition).open(player) }

        // --- actions ------------------------------------------------------------

        set(
            45,
            Icon.of(
                Material.ARROW, "<gray>◀ 목록으로</gray>",
            ),
        ) { event -> (event.whoClicked as? Player)?.let { MobListMenu(monsters).open(it) } }

        set(
            47,
            Icon.of(
                if (definition.enabled) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>몬스터 활성화</yellow>",
                "<gray>현재: </gray>" + Icon.toggle(definition.enabled),
                "<dark_gray>끄면 스폰되지 않습니다. 이미 소환된 개체는 남습니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭하여 전환</yellow>",
            ),
        ) { event ->
            definition.enabled = !definition.enabled
            monsters.mobs.rebuildReplacementIndex()
            save(); redraw(event.whoClicked)
        }

        set(
            49,
            Icon.of(
                Material.ZOMBIE_SPAWN_EGG, "<green>지금 소환</green>",
                "<gray>내 위치에 한 마리 소환합니다.</gray>",
                "<dark_gray>스폰 조건을 무시하고 강제로 생성합니다.</dark_gray>",
                "",
                "<yellow>▶ 좌클릭: 1마리   우클릭: 5마리</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            val amount = if (event.isRightClick) 5 else 1
            var spawned = 0
            repeat(amount) {
                val where = if (amount == 1) player.location else monsters.spawns.scatter(player.location, 3.0)
                if (monsters.spawns.spawn(definition, where, SpawnOptions(ignoreRules = true)) != null) spawned++
            }
            if (spawned == 0) {
                monsters.messages.send(player, "spawn-failed")
                monsters.spawns.lastRefusal?.let {
                    player.sendMessage(Text.render("<dark_gray>사유: " + it + "</dark_gray>"))
                }
            } else {
                monsters.messages.send(
                    player, "spawn-success",
                    Ph.of().mob(definition.displayName).location(player.location).count(spawned),
                )
            }
            // The menu deliberately stays open: an admin tuning a mob spawns it repeatedly, and
            // closing the screen every time turned into the top complaint about the box plugin.
            redraw(player)
        }

        val live = monsters.tracker.byDefinition(definition.id).size
        set(
            48,
            Icon.of(
                if (live > 0) Material.LAVA_BUCKET else Material.GRAY_DYE,
                "<red>이 몬스터 정리</red>",
                "<gray>지금 살아 있는 <yellow>" + live + "마리</yellow>를 모두 제거합니다.</gray>",
                "<dark_gray>설정은 그대로이며, 스포너가 있다면 다시 채웁니다.</dark_gray>",
                "<dark_gray>바로 옆 '지금 소환' 으로 만든 시험용 개체를</dark_gray>",
                "<dark_gray>치우는 용도입니다.</dark_gray>",
                "",
                if (live > 0) "<red>▶ Shift+클릭</red>" else "<dark_gray>제거할 개체가 없습니다.</dark_gray>",
            ),
        ) { event ->
            if (!event.isShiftClick) return@set
            val player = event.whoClicked as? Player ?: return@set
            val mobs = monsters.tracker.byDefinition(definition.id)
            mobs.forEach { monsters.removeMob(it) }
            monsters.messages.send(player, "cleanup-done", Ph.of().count(mobs.size))
            redraw(player)
        }

        set(
            50,
            Icon.of(
                Material.matchMaterial(definition.entityType.name + "_SPAWN_EGG") ?: Material.EGG,
                "<green>소환 알 받기</green>",
                "<gray>이 몬스터를 소환하는 알을 인벤토리에 넣어줍니다.</gray>",
                "<gray>블록을 우클릭하면 그 자리에 나타납니다.</gray>",
                "<dark_gray>알에는 몬스터 ID 가 새겨져 있어서, 이름을 바꾸거나</dark_gray>",
                "<dark_gray>다른 사람에게 넘겨도 소환 대상은 그대로입니다.</dark_gray>",
                "",
                "<yellow>▶ 좌클릭: 1개   우클릭: 16개</yellow>",
            ),
        ) { event ->
            val player = event.whoClicked as? Player ?: return@set
            val amount = if (event.isRightClick) 16 else 1
            val egg = monsters.spawnEggs.create(definition, amount)
            val leftovers = player.inventory.addItem(egg)
            if (leftovers.isNotEmpty()) {
                leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
                monsters.messages.send(player, "drop-inventory-full")
            }
            player.sendMessage(
                Text.render("<green>" + definition.id + " 소환 알 " + amount + "개를 받았습니다.</green>"),
            )
            redraw(player)
        }

        set(
            51,
            Icon.of(
                Material.BOOK, "<yellow>드랍 시뮬레이션</yellow>",
                "<gray>이 몬스터를 여러 번 잡았다고 가정하고</gray>",
                "<gray>드랍 결과를 미리 계산합니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event -> (event.whoClicked as? Player)?.let { SimulationMenu(monsters, definition).open(it) } }

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun isImmune(key: String): Boolean {
        val immunities = definition.immunities
        return when (key) {
            "fire" -> immunities.fire
            "fall" -> immunities.fall
            "drowning" -> immunities.drowning
            "suffocation" -> immunities.suffocation
            "explosion" -> immunities.explosion
            "projectile" -> immunities.projectile
            "magic" -> immunities.magic
            "wither" -> immunities.wither
            "lightning" -> immunities.lightning
            "void" -> immunities.voidDamage
            else -> false
        }
    }

    private fun summaryIcon(): org.bukkit.inventory.ItemStack {
        val material = Material.matchMaterial(definition.entityType.name + "_SPAWN_EGG") ?: Material.EGG
        val live = monsters.tracker.byDefinition(definition.id).size
        return Icon.of(
            material,
            "<gold>" + definition.id + "</gold>",
            buildList {
                add("<gray>" + definition.displayName + "</gray>")
                add("")
                add("<gray>현재 활동 중: <yellow>" + live + "마리</yellow></gray>")
                if (live > 0) {
                    add("<dark_gray>여기서 바꾼 값은 그 " + live + "마리에도 곧바로 반영됩니다.</dark_gray>")
                    add("<dark_gray>(엔티티 종류와 모델은 새로 소환해야 바뀝니다)</dark_gray>")
                }
                if (definition.parent != null) add("<dark_gray>상속: " + definition.parent + "</dark_gray>")
                if (definition.tags.isNotEmpty()) {
                    add("<dark_gray>태그: " + definition.tags.joinToString(", ") + "</dark_gray>")
                }
                if (!definition.enabled) {
                    add("")
                    add("<red>⚠ 비활성화 상태입니다.</red>")
                }
            },
        )
    }

    private fun section(
        slot: Int,
        material: Material,
        name: String,
        lore: List<String>,
        onClick: (Player) -> Unit,
    ) {
        set(slot, Icon.of(material, "<yellow>" + name + "</yellow>", lore + listOf("", "<yellow>▶ 클릭</yellow>"))) { event ->
            (event.whoClicked as? Player)?.let(onClick)
        }
    }

    private fun save() = monsters.mobs.markDirty(definition)

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    companion object {
        private fun title(definition: MobDefinition) =
            Text.renderFlat("<dark_gray>몬스터 설정 <gray>|</gray> " + definition.id + "</dark_gray>")
    }
}
