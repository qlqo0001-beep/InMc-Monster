package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.mob.MobDefinition
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player

/**
 * Picks a mob's entity type by clicking a spawn egg.
 *
 * Replaces a chat prompt that asked the admin to type `ZOMBIE` correctly from memory. The
 * types are grouped the way a server owner thinks about them - hostile, passive, the rest -
 * because "which of these is an animal" is the question being answered, not "which of these
 * sorts alphabetically next".
 */
class EntityTypeMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
    private var group: Group = Group.HOSTILE,
    private var page: Int = 0,
) : Menu(monsters, 54, TITLE) {

    /** How the list is split up. Membership is derived from the entity class, not a hand list. */
    enum class Group(val label: String, val icon: Material) {
        HOSTILE("적대", Material.ZOMBIE_HEAD),
        PASSIVE("평화 / 동물", Material.WHEAT),
        OTHER("기타", Material.ARMOR_STAND),
    }

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        val types = typesIn(group)
        val pages = Paging.pageCount(types.size, CONTENT)
        page = page.coerceIn(0, pages - 1)

        Paging.slice(types, page, CONTENT).forEachIndexed { index, type ->
            val selected = type == definition.entityType
            set(
                index,
                Icon.of(
                    eggFor(type),
                    (if (selected) "<green>▶ " else "<yellow>") + type.name +
                        (if (selected) "</green>" else "</yellow>"),
                    buildList {
                        if (selected) add("<green>현재 선택된 종류입니다.</green>")
                        add("<dark_gray>" + type.key.asString() + "</dark_gray>")
                        add("")
                        add("<red>⚠ 이미 소환된 개체의 종류는 바뀌지 않습니다.</red>")
                        add("")
                        add("<yellow>▶ 클릭하여 선택</yellow>")
                    },
                ),
            ) { event ->
                val player = event.whoClicked as? Player ?: return@set
                definition.entityType = type
                monsters.mobs.rebuildReplacementIndex()
                monsters.mobs.markDirty(definition)
                MobBasicMenu(monsters, definition).open(player)
            }
        }

        for (slot in CONTENT until 54) set(slot, Icon.EDGE)

        set(Paging.SLOT_BACK, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { MobBasicMenu(monsters, definition).open(it) }
        }
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { event -> open(event.whoClicked, group, page - 1) }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { event -> open(event.whoClicked, group, page + 1) }

        // Group tabs
        Group.entries.forEachIndexed { index, tab ->
            set(
                49 + index,
                Icon.of(
                    if (tab == group) tab.icon else Material.GRAY_DYE,
                    (if (tab == group) "<green>" else "<gray>") + tab.label +
                        (if (tab == group) "</green>" else "</gray>"),
                    listOf(
                        "<gray>" + typesIn(tab).size + "종</gray>",
                        "",
                        if (tab == group) "<dark_gray>보고 있는 분류입니다.</dark_gray>" else "<yellow>▶ 클릭</yellow>",
                    ),
                ),
            ) { event -> open(event.whoClicked, tab, 0) }
        }

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    /**
     * Spawnable living entity types in [group].
     *
     * Filtered on `isSpawnable` and `isAlive` because the enum also contains projectiles, boats
     * and display entities, none of which can be a mob.
     */
    private fun typesIn(group: Group): List<EntityType> = EntityType.entries
        .filter { it.isSpawnable && it.isAlive && it != EntityType.PLAYER }
        .filter { groupOf(it) == group }
        .sortedBy { it.name }

    private fun groupOf(type: EntityType): Group {
        val clazz = type.entityClass ?: return Group.OTHER
        return when {
            org.bukkit.entity.Monster::class.java.isAssignableFrom(clazz) -> Group.HOSTILE
            org.bukkit.entity.Boss::class.java.isAssignableFrom(clazz) -> Group.HOSTILE
            org.bukkit.entity.Animals::class.java.isAssignableFrom(clazz) -> Group.PASSIVE
            org.bukkit.entity.WaterMob::class.java.isAssignableFrom(clazz) -> Group.PASSIVE
            org.bukkit.entity.AbstractVillager::class.java.isAssignableFrom(clazz) -> Group.PASSIVE
            else -> Group.OTHER
        }
    }

    private fun eggFor(type: EntityType): Material =
        Material.matchMaterial(type.name + "_SPAWN_EGG") ?: Material.EGG

    private fun open(who: org.bukkit.entity.HumanEntity, tab: Group, target: Int) {
        (who as? Player)?.let { EntityTypeMenu(monsters, definition, tab, target).open(it) }
    }

    companion object {
        private const val CONTENT = 45
        private val TITLE = Text.renderFlat("<dark_gray>엔티티 종류 선택</dark_gray>")
    }
}
