package com.inmc.monster.gui

import com.inmc.monster.Monsters
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * The hub every other screen hangs off.
 *
 * Laid out so the things an admin touches daily - mobs, spawners, triggers - sit on the top row
 * and the things they set once - worlds, global settings - sit below.
 */
class MainMenu(monsters: Monsters) : Menu(monsters, 45, TITLE) {

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(
            11,
            Icon.of(
                Material.ZOMBIE_HEAD, "<yellow>몬스터 관리</yellow>",
                "<gray>등록된 몬스터: <white>" + monsters.mobs.size + "종</white></gray>",
                "<gray>스탯·장비·드랍·스킬·패턴을 편집합니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event -> (event.whoClicked as? Player)?.let { MobListMenu(monsters).open(it) } }

        set(
            13,
            Icon.of(
                Material.NAME_TAG, "<yellow>수식어 관리</yellow>",
                "<gray>등록된 수식어: <white>" + monsters.affixes.size + "종</white></gray>",
                "<gray>'강력한', '욕심많은' 처럼 몬스터 이름 앞뒤에</gray>",
                "<gray>붙어 능력치를 바꾸는 특성입니다.</gray>",
                "<dark_gray>바닐라 몬스터에도 붙일 수 있습니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event -> (event.whoClicked as? Player)?.let { AffixListMenu(monsters).open(it) } }

        set(
            15,
            Icon.of(
                Material.SPAWNER, "<yellow>스포너 관리</yellow>",
                "<gray>등록된 스포너: <white>" + monsters.spawners.size + "개</white></gray>",
                "<gray>좌표·반경 기반 스포너와</gray>",
                "<gray>월드에 설치하는 블록형 스포너입니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event -> (event.whoClicked as? Player)?.let { SpawnerListMenu(monsters).open(it) } }

        set(
            20,
            Icon.of(
                Material.OAK_SAPLING, "<yellow>등장 조건</yellow>",
                "<gray>등록된 조건: <white>" + monsters.triggers.size + "개</white></gray>",
                "<gray>'나무 10개를 베면 10% 확률로 등장' 처럼</gray>",
                "<gray>플레이어 행동에 반응하는 조건입니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event -> (event.whoClicked as? Player)?.let { TriggerListMenu(monsters).open(it) } }

        set(
            22,
            Icon.of(
                Material.GRASS_BLOCK, "<yellow>월드별 설정</yellow>",
                "<gray>월드마다 스폰 확률·드랍 배율·상한을</gray>",
                "<gray>따로 조절합니다.</gray>",
                "<dark_gray>지정하지 않은 값은 전역 설정을 따릅니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event -> (event.whoClicked as? Player)?.let { WorldListMenu(monsters).open(it) } }

        set(
            24,
            Icon.of(
                Material.COMPARATOR, "<yellow>전역 설정</yellow>",
                "<gray>스폰 한도, 치환 대상, 표시 옵션 등</gray>",
                "<gray>서버 전체에 적용되는 값입니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event -> (event.whoClicked as? Player)?.let { GlobalSettingsMenu(monsters).open(it) } }

        set(
            30,
            Icon.of(
                Material.CLOCK, "<yellow>현황 / 진단</yellow>",
                "<gray>활성 개체 수, 스폰 거부 사유,</gray>",
                "<gray>연동 상태를 확인합니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event -> (event.whoClicked as? Player)?.let { StatusMenu(monsters).open(it) } }

        set(
            32,
            Icon.of(
                Material.BOOK, "<yellow>스킬 목록</yellow>",
                "<gray>사용 가능한 스킬: <white>" + monsters.skills.registry.size + "종</white></gray>",
                "<gray>내장 스킬과 외부 플러그인 연동 스킬을</gray>",
                "<gray>확인합니다.</gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event -> (event.whoClicked as? Player)?.let { SkillCatalogMenu(monsters).open(it) } }

        set(
            40,
            Icon.of(
                Material.LIME_DYE, "<green>설정 다시 읽기</green>",
                "<gray>모든 설정 파일을 다시 불러옵니다.</gray>",
                "<dark_gray>열려 있는 GUI 는 모두 닫힙니다.</dark_gray>",
                "",
                "<yellow>▶ Shift+클릭</yellow>",
            ),
        ) { event ->
            if (!event.isShiftClick) return@set
            val player = event.whoClicked as? Player ?: return@set
            player.closeInventory()
            monsters.messages.send(player, "reloading")
            monsters.reload { count ->
                monsters.spawners.load {
                    monsters.triggers.load {
                        monsters.messages.send(
                            player, "reloaded", com.inmc.monster.util.Ph.of().count(count),
                        )
                    }
                }
            }
        }

        set(44, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    companion object {
        private val TITLE = Text.renderFlat("<dark_gray>커스텀 몬스터</dark_gray>")
    }
}
