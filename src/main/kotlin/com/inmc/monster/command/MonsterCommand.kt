package com.inmc.monster.command

import com.inmc.monster.Monsters
import com.inmc.monster.gui.MainMenu
import com.inmc.monster.gui.MobListMenu
import com.inmc.monster.mob.MobRegistry
import com.inmc.monster.spawn.SpawnOptions
import com.inmc.monster.util.Ph
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * `/몹`, registered through Paper's Brigadier API.
 *
 * **Korean names and Brigadier.** Brigadier's unquoted-string reader only accepts
 * `[0-9A-Za-z_.+-]`, so a Korean argument read with `word()` or an unquoted `string()` stops at
 * the first Hangul character and the command fails with "인수를 끝내는 공백이 필요합니다". The
 * random-box plugin shipped with exactly that bug. Every name argument here is therefore a
 * `greedyString()`, and the two commands that genuinely need two names split the greedy tail on
 * its last space rather than declaring a second argument.
 *
 * One admin permission node: `monster.admin`.
 */
class MonsterCommand(private val monsters: Monsters) {

    fun register(plugin: JavaPlugin) {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                tree().build(),
                "INMC 커스텀 몬스터",
                listOf("monster", "mob", "몬스터"),
            )
        }
    }

    // --- suggestions -----------------------------------------------------------

    private val mobIds = SuggestionProvider<CommandSourceStack> { _, builder ->
        monsters.mobs.ids()
            .filter { it.startsWith(builder.remainingLowerCase, ignoreCase = true) }
            .forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    private val skillIds = SuggestionProvider<CommandSourceStack> { _, builder ->
        monsters.skills.registry.ids()
            .filter { it.startsWith(builder.remainingLowerCase, ignoreCase = true) }
            .forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    // --- tree ------------------------------------------------------------------

    private fun tree(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("몹")
            .executes { ctx -> openGui(ctx.source.sender) }

            .then(Commands.literal("gui").requires(::isAdmin).executes { ctx -> openGui(ctx.source.sender) })

            .then(
                Commands.literal("목록").requires(::isAdmin)
                    .executes { ctx -> list(ctx.source.sender) },
            )

            .then(
                Commands.literal("생성").requires(::isAdmin)
                    .then(
                        Commands.argument("이름", StringArgumentType.greedyString())
                            .executes { ctx -> create(ctx.source.sender, ctx.arg("이름")) },
                    ),
            )

            .then(
                Commands.literal("삭제").requires(::isAdmin)
                    .then(
                        Commands.argument("이름", StringArgumentType.greedyString()).suggests(mobIds)
                            .executes { ctx -> delete(ctx.source.sender, ctx.arg("이름")) },
                    ),
            )

            .then(
                Commands.literal("복제").requires(::isAdmin)
                    .then(
                        // "<원본> <새이름>" as one greedy argument - see the class comment.
                        Commands.argument("원본_새이름", StringArgumentType.greedyString()).suggests(mobIds)
                            .executes { ctx -> copy(ctx.source.sender, ctx.arg("원본_새이름")) },
                    ),
            )

            .then(
                Commands.literal("소환").requires(::isAdmin)
                    .then(
                        Commands.argument("이름", StringArgumentType.greedyString()).suggests(mobIds)
                            .executes { ctx -> spawn(ctx.source.sender, ctx.arg("이름"), 1) },
                    )
                    // Count comes first so the name can still be the greedy tail.
                    .then(
                        Commands.argument("수량", IntegerArgumentType.integer(1, 100))
                            .then(
                                Commands.argument("이름", StringArgumentType.greedyString()).suggests(mobIds)
                                    .executes { ctx ->
                                        spawn(
                                            ctx.source.sender,
                                            ctx.arg("이름"),
                                            IntegerArgumentType.getInteger(ctx, "수량"),
                                        )
                                    },
                            ),
                    ),
            )

            .then(
                Commands.literal("정리").requires(::isAdmin)
                    .executes { ctx -> cleanup(ctx.source.sender, null) }
                    .then(Commands.literal("전체").executes { ctx -> cleanup(ctx.source.sender, "*") })
                    .then(
                        Commands.argument("대상", StringArgumentType.greedyString()).suggests(mobIds)
                            .executes { ctx -> cleanup(ctx.source.sender, ctx.arg("대상")) },
                    ),
            )

            .then(Commands.literal("상태").requires(::isAdmin).executes { ctx -> status(ctx.source.sender) })

            .then(
                Commands.literal("스킬테스트").requires(::isAdmin)
                    .then(
                        Commands.argument("스킬", StringArgumentType.greedyString()).suggests(skillIds)
                            .executes { ctx -> testSkill(ctx.source.sender, ctx.arg("스킬")) },
                    ),
            )

            .then(Commands.literal("리로드").requires(::isAdmin).executes { ctx -> reload(ctx.source.sender) })
            // 서버 안 자동 검증(2026-10-08) — 정의·스킬 레지스트리·소환/태그/정리·수식어·월드 설정·화면.
            .then(Commands.literal("검증").requires(::isAdmin).executes { ctx ->
                val player = ctx.source.sender as? org.bukkit.entity.Player
                if (player == null) monsters.messages.send(ctx.source.sender, "player-only") else com.inmc.monster.verify.Verifier(monsters).run(player)
                Command.SINGLE_SUCCESS
            })

    // --- handlers --------------------------------------------------------------

    private fun openGui(sender: CommandSender): Int {
        val player = sender as? Player ?: return notPlayer(sender)
        if (!isAdmin(sender)) return denied(sender)
        if (!monsters.ready) return notReady(sender)
        MainMenu(monsters).open(player)
        return Command.SINGLE_SUCCESS
    }

    private fun list(sender: CommandSender): Int {
        val player = sender as? Player ?: return notPlayer(sender)
        if (!monsters.ready) return notReady(sender)
        MobListMenu(monsters).open(player)
        return Command.SINGLE_SUCCESS
    }

    private fun create(sender: CommandSender, rawName: String): Int {
        if (!monsters.ready) return notReady(sender)
        val name = rawName.trim()
        if (!MobRegistry.isValidId(name)) {
            monsters.messages.send(sender, "mob-invalid-name")
            return 0
        }
        if (monsters.mobs.exists(name)) {
            monsters.messages.send(sender, "mob-exists", Ph.of().mob(name))
            return 0
        }
        val definition = monsters.mobs.create(name)
        if (definition == null) {
            monsters.messages.send(sender, "mob-invalid-name")
            return 0
        }
        monsters.messages.send(sender, "mob-created", Ph.of().mob(name))
        (sender as? Player)?.let { com.inmc.monster.gui.MobManageMenu(monsters, definition).open(it) }
        return Command.SINGLE_SUCCESS
    }

    private fun delete(sender: CommandSender, rawName: String): Int {
        if (!monsters.ready) return notReady(sender)
        val definition = monsters.mobs.get(rawName.trim()) ?: return unknown(sender, rawName)
        monsters.mobs.delete(definition.id)
        monsters.messages.send(sender, "mob-deleted", Ph.of().mob(definition.id))
        return Command.SINGLE_SUCCESS
    }

    /** Takes "<원본> <새이름>" and splits on the last space. */
    private fun copy(sender: CommandSender, raw: String): Int {
        if (!monsters.ready) return notReady(sender)
        val trimmed = raw.trim()
        val split = trimmed.lastIndexOf(' ')
        if (split <= 0) {
            sender.sendMessage(
                kr.inmc.core.util.Text.render("<gray>사용법: <white>/몹 복제 [원본이름] [새이름]</white></gray>"),
            )
            return 0
        }
        val sourceName = trimmed.substring(0, split).trim()
        val newName = trimmed.substring(split + 1).trim()

        val source = monsters.mobs.get(sourceName) ?: return unknown(sender, sourceName)
        if (monsters.mobs.exists(newName)) {
            monsters.messages.send(sender, "mob-exists", Ph.of().mob(newName))
            return 0
        }
        val clone = monsters.mobs.copy(source, newName, monsters.config.dropDefaults.chance)
        if (clone == null) {
            monsters.messages.send(sender, "mob-copy-failed")
            return 0
        }
        monsters.messages.send(sender, "mob-copied", Ph.of().mob(newName))
        return Command.SINGLE_SUCCESS
    }

    private fun spawn(sender: CommandSender, rawName: String, amount: Int): Int {
        val player = sender as? Player ?: return notPlayer(sender)
        if (!monsters.ready) return notReady(sender)
        val definition = monsters.mobs.get(rawName.trim()) ?: return unknown(sender, rawName)

        var spawned = 0
        repeat(amount) {
            val where = if (amount == 1) player.location else monsters.spawns.scatter(player.location, 3.0)
            // Manual spawns bypass the mob's own placement rules on purpose: an admin asking for
            // a mob here wants it here, not a silent refusal because it is currently daytime.
            if (monsters.spawns.spawn(definition, where, SpawnOptions(ignoreRules = true)) != null) spawned++
        }

        if (spawned == 0) {
            val reason = monsters.spawns.lastRefusal
            monsters.messages.send(sender, "spawn-failed")
            if (reason != null) {
                sender.sendMessage(kr.inmc.core.util.Text.render("<dark_gray>사유: " + reason + "</dark_gray>"))
            }
            return 0
        }
        monsters.messages.send(
            sender, "spawn-success",
            Ph.of().mob(definition.displayName).location(player.location).count(spawned),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun cleanup(sender: CommandSender, target: String?): Int {
        if (!monsters.ready) return notReady(sender)
        if (target == null) {
            monsters.messages.send(sender, "cleanup-usage")
            return 0
        }
        val removed = when {
            target == "*" -> monsters.removeAllTracked()

            monsters.mobs.exists(target) -> {
                val mobs = monsters.tracker.byDefinition(target)
                mobs.forEach { monsters.removeMob(it) }
                mobs.size
            }

            org.bukkit.Bukkit.getWorld(target) != null -> {
                val world = org.bukkit.Bukkit.getWorld(target)!!
                val mobs = monsters.tracker.inWorld(world)
                mobs.forEach { monsters.removeMob(it) }
                mobs.size
            }

            else -> {
                // Falls through to a tag, which is how dungeon runs are cleaned up.
                monsters.api.killAllByTag(target)
            }
        }
        monsters.messages.send(sender, "cleanup-done", Ph.of().count(removed))
        return Command.SINGLE_SUCCESS
    }

    private fun status(sender: CommandSender): Int {
        if (!monsters.ready) return notReady(sender)
        val text = buildString {
            appendLine("<gold>─── 커스텀 몬스터 현황 ───</gold>")
            appendLine("<gray>정의: <white>" + monsters.mobs.size + "종</white>   수식어: <white>" + monsters.affixes.size + "종</white>   스킬: <white>" + monsters.skills.registry.size + "종</white></gray>")
            appendLine("<gray>활성 개체: <yellow>" + monsters.tracker.size + "</yellow> / " + monsters.config.budget.global + "   (모델: " + monsters.tracker.modelledCount + " / " + monsters.config.budget.modelled + ")</gray>")
            appendLine("<gray>스포너: <white>" + monsters.spawners.size + "</white>   등장 조건: <white>" + monsters.triggers.size + "</white>   리스폰 대기: <white>" + monsters.respawns.size + "</white></gray>")
            for (world in org.bukkit.Bukkit.getWorlds()) {
                val count = monsters.tracker.countIn(world)
                if (count == 0) continue
                appendLine("<dark_gray> · " + world.name + ": <white>" + count + "</white></dark_gray>")
            }
            val refusals = monsters.budget.refusalCounts()
            if (refusals.isNotEmpty()) {
                appendLine("<gray>스폰 거부 누적:</gray>")
                refusals.forEach { (verdict, count) ->
                    appendLine("<dark_gray> · " + verdict.label + ": " + count + "</dark_gray>")
                }
            }
            // The model provider is named rather than labelled, because BetterModel and
            // ModelEngine are both supported and an admin needs to know which one actually
            // answered - "모델 O" next to the wrong plugin name is worse than no line at all.
            appendLine("<gray>연동: MythicLib <white>" + on(monsters.mythicLib.isEnabled) + "</white>  MMOItems <white>" + on(monsters.mmoItems.isEnabled) + "</white>  커스텀아이템 <white>" + on(com.inmc.monster.integration.CustomItemStats.isEnabled) + "</white>  모델 <white>" + on(monsters.models.isEnabled) + " (" + monsters.models.providerName + ")</white></gray>")
            append("<gray>      MythicMobs <white>" + on(monsters.mythicMobs.isEnabled) + "</white>  MagicSpells <white>" + on(monsters.magicSpells.isEnabled) + "</white>  Vault <white>" + on(monsters.economy.isEnabled) + "</white></gray>")
        }
        sender.sendMessage(kr.inmc.core.util.Text.render(text))
        return Command.SINGLE_SUCCESS
    }

    private fun on(value: Boolean): String = if (value) "O" else "X"

    /**
     * Casts a skill from the nearest custom mob, for checking a skill without building a fight
     * around it first.
     */
    private fun testSkill(sender: CommandSender, skillId: String): Int {
        val player = sender as? Player ?: return notPlayer(sender)
        if (!monsters.ready) return notReady(sender)

        val skill = monsters.skills.registry[skillId.trim()]
        if (skill == null) {
            sender.sendMessage(
                kr.inmc.core.util.Text.render("<red>'" + skillId + "' 스킬을 찾을 수 없습니다.</red>"),
            )
            return 0
        }
        val mob = monsters.tracker.inWorld(player.world)
            .filter { it.isAlive }
            .minByOrNull { it.entity.location.distanceSquared(player.location) }
        if (mob == null) {
            sender.sendMessage(
                kr.inmc.core.util.Text.render("<red>주변에 커스텀 몬스터가 없습니다. 먼저 소환해주세요.</red>"),
            )
            return 0
        }
        monsters.skills.castById(mob, skill.id, emptyMap(), target = player)
        sender.sendMessage(
            kr.inmc.core.util.Text.render(
                "<green>" + mob.displayName + " 이(가) <yellow>" + skill.displayName + "</yellow> 을(를) 시전했습니다.</green>",
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun reload(sender: CommandSender): Int {
        monsters.messages.send(sender, "reloading")
        monsters.reload { count ->
            monsters.spawners.load {
                monsters.triggers.load {
                    monsters.messages.send(sender, "reloaded", Ph.of().count(count))
                }
            }
        }
        return Command.SINGLE_SUCCESS
    }

    // --- helpers ---------------------------------------------------------------

    private fun isAdmin(source: CommandSourceStack): Boolean = isAdmin(source.sender)

    private fun isAdmin(sender: CommandSender): Boolean = sender.hasPermission(PERMISSION)

    private fun CommandContext<CommandSourceStack>.arg(name: String): String =
        StringArgumentType.getString(this, name)

    private fun notPlayer(sender: CommandSender): Int {
        monsters.messages.send(sender, "player-only")
        return 0
    }

    private fun notReady(sender: CommandSender): Int {
        monsters.messages.send(sender, "not-ready")
        return 0
    }

    private fun denied(sender: CommandSender): Int {
        monsters.messages.send(sender, "no-permission")
        return 0
    }

    private fun unknown(sender: CommandSender, name: String): Int {
        monsters.messages.send(sender, "mob-unknown", Ph.of().mob(name))
        return 0
    }

    companion object {
        const val PERMISSION = "monster.admin"
    }
}
