package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.mob.MobRegistry
import com.inmc.monster.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * Paged list of every mob definition.
 *
 * The icon is a spawn egg for the mob's entity type where one exists, so the list is scannable
 * by shape rather than by reading names - which matters once there are more than a screenful.
 */
class MobListMenu(
    monsters: Monsters,
    private var page: Int = 0,
) : Menu(monsters, SIZE, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val all = monsters.mobs.all()
        val pages = maxOf(1, (all.size + CONTENT_SIZE - 1) / CONTENT_SIZE)
        page = page.coerceIn(0, pages - 1)

        all.drop(page * CONTENT_SIZE).take(CONTENT_SIZE).forEachIndexed { index, definition ->
            set(index, iconFor(definition)) { event ->
                val player = event.whoClicked as? Player ?: return@set
                when (event.click) {
                    ClickType.SHIFT_RIGHT -> ConfirmMenu(
                        monsters,
                        question = "<red>'" + definition.id + "' 몬스터를 삭제할까요?</red>",
                        detail = listOf(
                            "<gray>설정 파일이 삭제되며 되돌릴 수 없습니다.</gray>",
                            "<dark_gray>이미 소환된 개체는 그대로 남습니다.</dark_gray>",
                        ),
                        onConfirm = {
                            monsters.mobs.delete(definition.id)
                            monsters.messages.send(
                                player, "mob-deleted", com.inmc.monster.util.Ph.of().mob(definition.id),
                            )
                            MobListMenu(monsters, page).open(player)
                        },
                        onCancel = { MobListMenu(monsters, page).open(player) },
                    ).open(player)

                    ClickType.DROP, ClickType.CONTROL_DROP, ClickType.SHIFT_LEFT -> promptCopy(player, definition)

                    else -> MobManageMenu(monsters, definition).open(player)
                }
            }
        }

        for (slot in CONTENT_SIZE until SIZE) set(slot, Icon.EDGE)

        set(45, Icon.back()) { event -> (event.whoClicked as? Player)?.let { MainMenu(monsters).open(it) } }

        if (page > 0) set(46, Icon.prevPage()) { event -> switchPage(event.whoClicked, page - 1) }
        if (page < pages - 1) set(47, Icon.nextPage()) { event -> switchPage(event.whoClicked, page + 1) }

        set(
            49,
            Icon.of(
                Material.WRITABLE_BOOK, "<green>+ 새 몬스터 만들기</green>",
                "<gray>이름을 입력하면 기본값으로 생성됩니다.</gray>",
                "<dark_gray>한글/영문/숫자/_/- 32자 이내</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event -> (event.whoClicked as? Player)?.let { promptCreate(it) } }

        set(
            51,
            Icon.of(
                Material.PAPER, "<yellow>도움말</yellow>",
                "<gray>등록된 몬스터: <white>" + all.size + "종</white></gray>",
                "<gray>활성 개체: <white>" + monsters.tracker.size + "마리</white></gray>",
                "",
                "<yellow>좌클릭</yellow><gray> : 설정 열기</gray>",
                "<yellow>Shift+좌클릭</yellow><gray> : 복제</gray>",
                "<red>Shift+우클릭</red><gray> : 삭제</gray>",
            ),
        )

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun iconFor(definition: MobDefinition): org.bukkit.inventory.ItemStack {
        val material = spawnEgg(definition.entityType)
        val lore = mutableListOf(
            "<gray>표시명: " + definition.displayName + "</gray>",
            "<gray>종류: <white>" + definition.entityType.name + "</white></gray>",
            "<gray>체력: <red>" + com.inmc.monster.util.Numbers.chance(definition.maxHealth()) + "</red>" +
                "   공격력: <red>" + com.inmc.monster.util.Numbers.chance(definition.stats.getOrZero("ATTACK_DAMAGE")) + "</red></gray>",
            "<gray>드랍: <white>" + definition.drops.entries.size + "종</white>" +
                "   스킬: <white>" + definition.skills.size + "개</white>" +
                "   페이즈: <white>" + definition.phases.size + "</white></gray>",
        )
        if (definition.parent != null) lore.add("<dark_gray>상속: " + definition.parent + "</dark_gray>")
        if (definition.model.isNotBlank()) {
            val ok = monsters.models.isEnabled
            lore.add(
                (if (ok) "<aqua>모델: " else "<red>모델(ModelEngine 없음): ") + definition.model +
                    if (ok) "</aqua>" else "</red>",
            )
        }
        if (definition.replacement.enabled) {
            lore.add(
                "<light_purple>자연 스폰 치환 " +
                    com.inmc.monster.util.Numbers.chance(definition.replacement.chance) + "%</light_purple>",
            )
        }
        if (!definition.enabled) lore.add("<red>비활성화됨</red>")
        val live = monsters.tracker.byDefinition(definition.id).size
        if (live > 0) lore.add("<green>현재 " + live + "마리 활동 중</green>")
        lore.add("")
        lore.add("<yellow>▶ 좌클릭: 설정</yellow>")
        lore.add("<yellow>▶ Shift+좌클릭: 복제</yellow>   <red>Shift+우클릭: 삭제</red>")

        return Icon.of(material, "<yellow>" + definition.id + "</yellow>", lore)
    }

    /** Spawn egg for the type when one exists; a generic egg otherwise (wither, ender dragon…). */
    private fun spawnEgg(type: org.bukkit.entity.EntityType): Material =
        Material.matchMaterial(type.name + "_SPAWN_EGG") ?: Material.EGG

    private fun switchPage(who: org.bukkit.entity.HumanEntity, target: Int) {
        (who as? Player)?.let { MobListMenu(monsters, target).open(it) }
    }

    private fun promptCreate(player: Player) {
        monsters.prompts.request(
            player,
            listOf(
                "<yellow>새 몬스터의 이름을 입력하세요.</yellow>",
                "<gray>한글/영문/숫자/_/- 만 사용할 수 있습니다. (32자 이내)</gray>",
                "<dark_gray>예: 부패한기사</dark_gray>",
            ),
            onCancel = { MobListMenu(monsters, page).open(player) },
        ) { input ->
            val name = input.trim()
            if (!MobRegistry.isValidId(name)) {
                monsters.messages.send(player, "mob-invalid-name")
                MobListMenu(monsters, page).open(player)
                return@request
            }
            val created = monsters.mobs.create(name)
            if (created == null) {
                monsters.messages.send(player, "mob-exists", com.inmc.monster.util.Ph.of().mob(name))
                MobListMenu(monsters, page).open(player)
                return@request
            }
            monsters.messages.send(player, "mob-created", com.inmc.monster.util.Ph.of().mob(name))
            MobManageMenu(monsters, created).open(player)
        }
    }

    private fun promptCopy(player: Player, source: MobDefinition) {
        monsters.prompts.request(
            player,
            listOf(
                "<yellow>복제본의 이름을 입력하세요.</yellow>",
                "<gray>원본: <white>" + source.id + "</white></gray>",
            ),
            onCancel = { MobListMenu(monsters, page).open(player) },
        ) { input ->
            val name = input.trim()
            val clone = monsters.mobs.copy(source, name, monsters.config.dropDefaults.chance)
            if (clone == null) {
                monsters.messages.send(player, "mob-copy-failed")
                MobListMenu(monsters, page).open(player)
                return@request
            }
            monsters.messages.send(player, "mob-copied", com.inmc.monster.util.Ph.of().mob(name))
            MobManageMenu(monsters, clone).open(player)
        }
    }

    companion object {
        private const val SIZE = 54
        private const val CONTENT_SIZE = 45
        private val TITLE = Text.renderFlat("<dark_gray>몬스터 목록</dark_gray>")
    }
}
