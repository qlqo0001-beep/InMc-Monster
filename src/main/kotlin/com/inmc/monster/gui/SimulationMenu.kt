package com.inmc.monster.gui

import com.inmc.monster.Monsters
import com.inmc.monster.mob.LootRoller
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.mob.MobDrop
import com.inmc.monster.mob.Weighted
import com.inmc.monster.util.Numbers
import com.inmc.monster.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.Random

/**
 * "If I killed this thing a thousand times, what would I get?"
 *
 * Worth having because a drop table is not readable by inspection: the roll-count ceiling
 * interacts with the individual chances in a way that surprises people. A table of twenty
 * entries at 30% each with `max-rolls: 2` is not a generous table - it is a table where the
 * percentages barely matter, and only a simulation makes that obvious before it goes live.
 *
 * Rolls the entries only; no ItemStacks are built, so ten thousand iterations cost nothing.
 */
class SimulationMenu(
    monsters: Monsters,
    private val definition: MobDefinition,
    private var runs: Int = 1000,
) : Menu(monsters, 54, TITLE) {

    private val rng = Random()

    /** Drop id -> how many kills produced it, and how many units in total. */
    private var hits: Map<String, Pair<Int, Long>> = emptyMap()
    private var emptyKills: Int = 0
    private var truncatedRolls: Int = 0
    private var lastRun: Int = 0

    override fun draw() {
        clear()
        fillEmpty(Icon.EDGE)

        set(
            4,
            Icon.of(
                Material.BOOK, "<gold>드랍 시뮬레이션</gold>",
                buildList {
                    add("<gray>대상: <white>" + definition.id + "</white></gray>")
                    add("<gray>등록된 드랍: <white>" + definition.drops.entries.size + "종</white></gray>")
                    add(
                        "<gray>배출 개수: <white>" + definition.drops.minRolls + " ~ " +
                            definition.drops.maxRolls + "</white></gray>",
                    )
                    add("<gray>확률 배율: <white>x" + Numbers.chance(definition.drops.chanceMultiplier) + "</white></gray>")
                    if (lastRun > 0) {
                        add("")
                        add("<gray>마지막 실행: <yellow>" + lastRun + "회</yellow></gray>")
                        add(
                            "<gray>아무것도 안 나온 비율: <white>" +
                                Numbers.chance(emptyKills * 100.0 / lastRun) + "%</white></gray>",
                        )
                        if (truncatedRolls > 0) {
                            add("")
                            add(
                                "<yellow>⚠ " + Numbers.chance(truncatedRolls * 100.0 / lastRun) +
                                    "% 의 처치에서 당첨이 상한을 넘어 잘렸습니다.</yellow>",
                            )
                            add("<dark_gray>이 비율이 높으면 개별 확률보다 배출 개수 상한이</dark_gray>")
                            add("<dark_gray>결과를 결정하고 있다는 뜻입니다.</dark_gray>")
                        }
                    }
                },
            ),
        )

        if (lastRun == 0) {
            set(
                22,
                Icon.of(
                    Material.BARRIER, "<gray>아직 실행하지 않았습니다</gray>",
                    "<gray>아래 버튼으로 시뮬레이션을 돌려보세요.</gray>",
                ),
            )
        } else {
            drawResults()
        }

        set(45, Icon.back()) { event ->
            (event.whoClicked as? Player)?.let { MobManageMenu(monsters, definition).open(it) }
        }

        set(
            48,
            Editors.intIcon(
                Material.COMPARATOR, "<yellow>실행 횟수</yellow>", runs, "회",
                stepLabel = "100",
            ),
        ) { event ->
            runs = (runs + Editors.step(event, 100)).coerceIn(100, 100_000)
            redraw(event.whoClicked)
        }

        set(
            50,
            Icon.of(
                Material.LIME_CONCRETE, "<green>▶ 시뮬레이션 실행</green>",
                "<gray><white>" + runs + "</white>회 처치했다고 가정하고 굴립니다.</gray>",
                "<dark_gray>실제 아이템은 만들지 않으므로 서버에 부담이 없습니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭</yellow>",
            ),
        ) { event ->
            if (definition.drops.entries.isEmpty()) {
                (event.whoClicked as? Player)?.sendMessage(
                    Text.render("<red>등록된 드랍이 없어 시뮬레이션할 것이 없습니다.</red>"),
                )
                return@set
            }
            simulate()
            redraw(event.whoClicked)
        }

        set(53, Icon.close()) { event -> event.whoClicked.closeInventory() }
    }

    private fun drawResults() {
        val ordered = definition.drops.entries
            .filter { hits.containsKey(it.id) }
            .sortedByDescending { hits[it.id]?.first ?: 0 }

        ordered.take(CONTENT).forEachIndexed { index, drop ->
            val (count, units) = hits[drop.id] ?: return@forEachIndexed
            val icon = monsters.itemResolver.icon(drop.item)
            val observed = count * 100.0 / lastRun
            val configured = (drop.chance * definition.drops.chanceMultiplier).coerceIn(0.0, 100.0)

            set(
                9 + index,
                Icon.annotate(
                    icon.stack,
                    lore = buildList {
                        add("<gray>설정 확률: <white>" + Numbers.chance(configured) + "%</white></gray>")
                        add("<gray>실제 배출: <yellow>" + Numbers.chance(observed) + "%</yellow> <dark_gray>(" + count + "회)</dark_gray></gray>")
                        add("<gray>총 수량: <white>" + units + "개</white>" +
                            "   평균 <white>" + Numbers.chance(units.toDouble() / lastRun) + "개</white>/처치</gray>")
                        if (observed < configured * 0.75 && configured > 1.0) {
                            add("")
                            add("<yellow>⚠ 설정보다 눈에 띄게 적게 나옵니다.</yellow>")
                            add("<dark_gray>배출 개수 상한에 걸려 잘리고 있습니다.</dark_gray>")
                        }
                    },
                ),
            )
        }
    }

    /**
     * Rolls the table [runs] times.
     *
     * Uses the same [LootRoller] the live drop path uses, with the same scaled chances, so the
     * numbers here are the numbers players will see - not an approximation of them.
     */
    private fun simulate() {
        val table = definition.drops
        val multiplier = table.chanceMultiplier
        val counts = HashMap<String, Int>()
        val units = HashMap<String, Long>()
        var empty = 0
        var truncated = 0

        val weighted = table.entries.map { ScaledDrop(it, (it.chance * multiplier).coerceIn(0.0, 100.0)) }
        val defaultPool = weighted.filter { it.entry.tier == null }

        repeat(runs) {
            var produced = 0

            if (defaultPool.isNotEmpty()) {
                val selection = LootRoller.selectDetailed(defaultPool, table.minRolls, table.maxRolls, rng)
                if (selection.rawWinners > selection.selected.size) truncated++
                selection.selected.forEach { scaled ->
                    counts.merge(scaled.entry.id, 1, Int::plus)
                    units.merge(
                        scaled.entry.id,
                        LootRoller.rollAmount(scaled.entry.minAmount, scaled.entry.maxAmount, rng).toLong(),
                        Long::plus,
                    )
                    produced++
                }
            }

            for ((name, tier) in table.tiers) {
                val pool = weighted.filter { it.entry.tier == name }
                if (pool.isEmpty()) continue
                LootRoller.select(pool, tier.minRolls, tier.maxRolls, rng).forEach { scaled ->
                    counts.merge(scaled.entry.id, 1, Int::plus)
                    units.merge(
                        scaled.entry.id,
                        LootRoller.rollAmount(scaled.entry.minAmount, scaled.entry.maxAmount, rng).toLong(),
                        Long::plus,
                    )
                    produced++
                }
            }

            if (produced == 0) empty++
        }

        hits = counts.mapValues { (id, count) -> count to (units[id] ?: 0L) }
        emptyKills = empty
        truncatedRolls = truncated
        lastRun = runs
    }

    private fun redraw(who: org.bukkit.entity.HumanEntity) {
        refresh()
        (who as? Player)?.updateInventory()
    }

    /** Wraps a drop with its scaled chance so the roller can weigh it without mutating anything. */
    private class ScaledDrop(val entry: MobDrop, override val chance: Double) : Weighted

    companion object {
        private const val CONTENT = 27
        private val TITLE = Text.renderFlat("<dark_gray>드랍 시뮬레이션</dark_gray>")
    }
}
