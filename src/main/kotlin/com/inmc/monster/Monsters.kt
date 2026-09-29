package com.inmc.monster

import com.inmc.monster.trigger.TriggerCounters
import com.inmc.monster.trigger.TriggerProgressImport
import java.io.File
import kr.inmc.core.CorePlugin
import kr.inmc.core.InmcHost
import com.inmc.monster.affix.AffixRegistry
import com.inmc.monster.affix.AffixRoller
import com.inmc.monster.api.MonsterAPI
import com.inmc.monster.combat.DamageBridge
import com.inmc.monster.combat.StatResolver
import kr.inmc.core.config.ConfigService
import com.inmc.monster.config.Messages
import com.inmc.monster.config.PluginConfig
import com.inmc.monster.config.WorldSettingsRegistry
import com.inmc.monster.death.DeathHandler
import com.inmc.monster.death.DropService
import kr.inmc.core.input.ChatPrompt
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.integration.EconomyHook
import kr.inmc.core.integration.MMOItemsHook
import com.inmc.monster.integration.MagicSpellsHook
import com.inmc.monster.integration.ModelHook
import com.inmc.monster.integration.MythicLibHook
import com.inmc.monster.integration.MythicMobsHook
import com.inmc.monster.integration.PapiHook
import com.inmc.monster.integration.RegionHook
import kr.inmc.core.item.ItemMatcher
import kr.inmc.core.item.ItemResolver
import com.inmc.monster.mob.MobRegistry
import com.inmc.monster.runtime.ActiveMob
import com.inmc.monster.runtime.BossBars
import com.inmc.monster.runtime.MobKeys
import com.inmc.monster.runtime.MobTracker
import com.inmc.monster.runtime.Nameplates
import com.inmc.monster.skill.SkillService
import com.inmc.monster.spawn.RespawnScheduler
import com.inmc.monster.spawn.SpawnBudget
import com.inmc.monster.spawn.SpawnService
import com.inmc.monster.spawn.SpawnerRegistry
import com.inmc.monster.trigger.TriggerRegistry
import com.inmc.monster.util.Ph
import kr.inmc.core.util.Placeholders
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin

/**
 * Service locator wiring the plugin together.
 *
 * Everything is constructed once and reached through `monsters.<service>`. A reload swaps the
 * volatile config objects and re-reads the definition files, but never rebuilds the services,
 * so listeners, menus and live mobs never end up holding a stale reference.
 */
class Monsters(override val plugin: JavaPlugin) : InmcHost {

    val logger: java.util.logging.Logger = plugin.logger
    override val io = ConfigService(plugin)

    /** core 의 ChatPrompt·MenuListener 가 메시지를 보낼 때 쓰는 통로. */
    override fun tell(target: CommandSender, key: String, ph: Placeholders?) =
        messages.send(target, key, ph as? Ph)

    // --- integrations (all optional) -------------------------------------------
    val mmoItems = MMOItemsHook(logger)
    val mythicLib = MythicLibHook(logger)
    val customItems = CustomItemHook(logger)
    val models = ModelHook(logger)
    val mythicMobs = MythicMobsHook(logger)
    val magicSpells = MagicSpellsHook(logger)
    val economy = EconomyHook(logger)
    val regions = RegionHook(logger)
    val papi = PapiHook(this)

    // --- item layer -------------------------------------------------------------
    val itemResolver = ItemResolver(mmoItems, customItems, logger)
    val itemMatcher = ItemMatcher(mmoItems, customItems)
    val spawnEggs = com.inmc.monster.item.SpawnEggs(this)

    // --- configuration ----------------------------------------------------------
    @Volatile
    var config: PluginConfig = PluginConfig.from(YamlConfiguration())

    @Volatile
    var messages: Messages = Messages.from(YamlConfiguration())

    val worlds = WorldSettingsRegistry(io, logger)

    // --- registries -------------------------------------------------------------
    val mobs = MobRegistry(io, logger)
    val affixes = AffixRegistry(io, logger)
    val affixRoller = AffixRoller(affixes)

    // --- runtime ----------------------------------------------------------------
    val keys = MobKeys(plugin)
    val tracker = MobTracker(keys)
    val nameplates = Nameplates(this)
    val bossBars = BossBars(this)

    // --- domain services --------------------------------------------------------
    val statResolver = StatResolver(mythicLib)
    val damage = DamageBridge(this)
    val budget = SpawnBudget(tracker)
    val spawns = SpawnService(this)
    val respawns = RespawnScheduler(this)
    val spawners = SpawnerRegistry(this)
    val skills = SkillService(this)
    val drops = DropService(this)
    val deaths = DeathHandler(this)
    val triggers = TriggerRegistry(this)
    val prompts = ChatPrompt(this)
    val api = MonsterAPI(this)

    /** False until persisted state has finished loading; interactions are held off until then. */
    @Volatile
    var ready: Boolean = false
        private set

    /** Server tick counter, used for every cooldown and timer in the plugin. */
    @Volatile
    var tick: Long = 0L
        private set

    fun currentTick(): Long = tick

    fun advanceTick(by: Long) {
        tick += by
    }

    // --- lifecycle --------------------------------------------------------------

    /**
     * `triggers/trigger-progress.yml` 을 공유 저장소로 한 번 옮기고 [then] 을 부른다.
     *
     * `enable` 에서만 부른다. `TriggerRegistry.load` 안에 넣으면 리로드 경로 네 곳에서
     * 다시 돌게 된다 — 이름 변경 표시가 그걸 막아주긴 하지만, 애초에 여기가 맞는 자리다.
     *
     * 성공한 뒤에야 원본 이름을 바꾼다. 순서를 뒤집으면 저장이 실패했을 때 사본이 없다.
     */
    private fun importTriggerProgress(then: () -> Unit) {
        val store = CorePlugin.get().players
        val source = TriggerCounters.fileOf(io.file("triggers"))
        if (!source.exists()) {
            then()
            return
        }
        io.async({ TriggerProgressImport.parse(io.load(source)) { logger.warning(it) } }) { entries ->
            val written = TriggerProgressImport.apply(store, entries)
            logger.info("등장 조건 진행도 " + written + "건을 inmc-core 로 옮겼습니다")
            val archived = File(source.parentFile, source.name + TriggerProgressImport.IMPORTED_SUFFIX)
            io.asyncRun {
                if (!source.renameTo(archived)) {
                    logger.warning(
                        "옮긴 진행도 파일의 이름을 바꾸지 못했습니다 (" + source.name +
                            "). 다음 부팅에 다시 읽지만 이미 있는 값은 덮지 않습니다.",
                    )
                }
            }
            then()
        }
    }

    fun enable(then: () -> Unit) {
        skills.setup()
        reload { count ->
            logger.info("몬스터 " + count + "종을 불러왔습니다")
            respawns.load {
                spawners.load {
                    triggers.load {
                        // 진행도는 공유 저장소에서 온다. 그 저장소의 로드 콜백은 첫 틱에야
                        // 돌기 때문에(load: BEFORE 로는 보장되지 않는다) 여기서 기다린다.
                        // ready 를 먼저 올리면 리스너가 임포트 전 저장소에 쓰기 시작한다.
                        CorePlugin.get().players.whenReady {
                            importTriggerProgress {
                                triggers.loadProgress()
                                ready = true
                                MonsterAPI.install(api)
                                if (config.cleanup.onStartup) {
                                    val removed = sweepLeftovers()
                                    if (removed > 0) logger.info("이전 세션에 남아 있던 커스텀 몬스터 " + removed + "마리를 정리했습니다")
                                }
                                then()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Re-reads every configuration file. Integration hooks are refreshed too, so installing
     * MythicLib and running `/몹 리로드` is enough to pick it up.
     */
    fun reload(then: (Int) -> Unit) {
        io.async({
            val configFile = io.file("config.yml")
            val messagesFile = io.file("messages.yml")
            io.copyDefault("config.yml", configFile)
            io.copyDefault("messages.yml", messagesFile)
            io.copyDefault("affixes.yml", io.file("affixes.yml"))
            io.copyDefault("mobs/example.yml", io.file("mobs", "example.yml"))
            io.load(configFile) to io.load(messagesFile)
        }) { (rawConfig, rawMessages) ->
            config = PluginConfig.from(rawConfig)
            messages = Messages.from(rawMessages)
            // A misspelled spawn reason is harmless but invisible: it matches nothing, so the
            // setting looks applied while doing nothing at all.
            for (name in config.replacement.unknownReasons) {
                logger.warning(
                    "[설정] allowed-spawn-reasons 의 '" + name + "' 은 스폰 사유가 아닙니다. " +
                        "무시됩니다. (번식은 BREED 가 아니라 BREEDING 입니다)",
                )
            }
            budget.configure(config)
            setupIntegrations()
            // Integrations may have come or gone, so cached menu icons are no longer trustworthy.
            itemResolver.clearIconCache()

            worlds.loadAll(config) {
                affixes.loadAll {
                    mobs.loadAll(config.dropDefaults.chance) { count ->
                        // Every definition object is replaced here, so any menu still open is
                        // editing an orphan: it would keep showing edits that never reach disk.
                        // Closing them is the only way to make that impossible rather than
                        // merely detectable.
                        closeOpenMenus()
                        skills.reportUnusable()
                        // 커스텀아이템이 있으면 "몬스터 드랍·장비" 역할을 읽는다(처음이면 바닐라가 아닌 아이템을 옮긴다).
                        com.inmc.monster.mob.MonsterRoles.sync(this)
                        then(count)
                    }
                }
            }
        }
    }

    /** Re-runs integration discovery without touching config. Called when a plugin enables late. */
    fun refreshIntegrations() {
        setupIntegrations()
        itemResolver.clearIconCache()
    }

    private fun setupIntegrations() {
        mmoItems.setup()
        mythicLib.setup()
        customItems.setup()
        models.setup()
        mythicMobs.setup()
        magicSpells.setup()
        economy.setup()
        regions.setup(plugin)
        papi.setup()
    }

    private fun closeOpenMenus() {
        var closed = 0
        for (player in Bukkit.getOnlinePlayers()) {
            // core 가 소유한 화면(공용 확인창·설정 화면)도 잡아야 하므로 owner 로 가려낸다.
            val holder = player.openInventory.topInventory.holder
            if (holder !is kr.inmc.core.gui.Menu || holder.owner !== this) continue
            player.closeInventory()
            messages.send(player, "reload-menu-closed")
            closed++
        }
        if (closed > 0) logger.info("설정을 다시 읽어 열려 있던 GUI " + closed + "개를 닫았습니다")
    }

    fun shutdown() {
        MonsterAPI.install(null)
        papi.teardown()
        com.inmc.monster.skill.builtin.CageRegistry.restoreAll()
        bossBars.clear()

        if (config.cleanup.onShutdown) {
            val removed = removeAllTracked()
            if (removed > 0) logger.info("종료하면서 커스텀 몬스터 " + removed + "마리를 제거했습니다")
        }

        mobs.flushDirtyBlocking()
        affixes.flushBlocking()
        worlds.flushBlocking()
        spawners.flushBlocking()
        triggers.flushBlocking()
        respawns.flushBlocking()
        io.shutdown()
    }

    // --- phase transitions ------------------------------------------------------

    /**
     * Moves [mob] into the phase its current health calls for, if it changed.
     *
     * Driven from the tick loop rather than from the damage event on purpose. Bukkit applies
     * damage *after* the event finishes, so a check inside the handler reads the health from
     * before the hit - a boss taken from 51% to 45% still looked like 51%, and the transition
     * was always one hit late or, on a short fight, never happened at all.
     */
    fun advancePhase(mob: ActiveMob) {
        val previous = mob.phase?.name
        val entered = mob.advancePhase() ?: return

        api.firePhaseEvent(mob, entered.name, previous)
        skills.fire(mob, com.inmc.monster.skill.SkillTrigger.ON_PHASE_ENTER)

        if (entered.model.isNotBlank() && mob.modelled) {
            models.swapModel(mob.entity, entered.model)
        }
        if (entered.onEnter.isNotEmpty()) {
            deaths.runActions(mob, entered.onEnter, mob.entity.killer, mob.entity.location)
        }
        if (entered.statMultipliers.isEmpty()) return
        for ((key, factor) in entered.statMultipliers.asMap()) {
            mob.stats.multiply(key, factor)
        }
        statResolver.applyTo(mob.entity, mob.stats)
    }

    // --- live re-application ----------------------------------------------------

    /**
     * Re-applies a definition to the mobs already standing in the world.
     *
     * Without this an admin tuning a boss has to kill and re-spawn it after every change, which
     * makes the GUI feel like it is not doing anything. Health is carried across as a
     * *percentage* rather than an absolute: raising max health mid-fight must not top the boss
     * back up, and lowering it must not kill it outright.
     */
    fun reapply(definition: com.inmc.monster.mob.MobDefinition): Int {
        val live = tracker.byDefinition(definition.id).filter { it.isAlive }
        if (live.isEmpty()) return 0

        for (mob in live) {
            try {
                val percent = mob.healthPercent().coerceIn(0.0, 100.0)

                mob.stats = statResolver.resolve(definition, mob.affixes, mob.level)
                // A phase already entered folded its multipliers into the stats, so they are
                // re-applied here - otherwise re-resolving would quietly undo the phase buff.
                mob.phase?.statMultipliers?.asMap()?.forEach { (key, factor) ->
                    mob.stats.multiply(key, factor)
                }

                spawns.applyFlags(mob.entity, definition)
                statResolver.applyTo(mob.entity, mob.stats)
                definition.equipment.applyTo(mob.entity, itemResolver)

                val max = mob.entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value
                if (max != null && max > 0.0) {
                    mob.entity.health = (max * percent / 100.0).coerceIn(0.1, max)
                }

                mob.refreshSkills()
                nameplates.apply(mob)
            } catch (t: Throwable) {
                logger.warning("'" + definition.id + "' 실시간 적용 실패: " + t.message)
            }
        }
        return live.size
    }

    // --- mob lifecycle helpers --------------------------------------------------

    /**
     * Drops all runtime state for a mob whose entity is already dead or gone.
     *
     * Called from the death handler and the purge sweep. Does not touch the entity.
     */
    fun cleanupMob(mob: ActiveMob) {
        bossBars.remove(mob)
        nameplates.forget(mob.uuid)
        mob.threat.clear()
        tracker.unregister(mob.uuid)
    }

    /** Removes a live mob from the world along with its runtime state. */
    fun removeMob(mob: ActiveMob) {
        if (mob.modelled) models.removeModel(mob.entity)
        cleanupMob(mob)
        if (mob.entity.isValid) mob.entity.remove()
    }

    fun removeAllTracked(): Int {
        val mobs = tracker.all().toList()
        mobs.forEach { removeMob(it) }
        tracker.clear()
        nameplates.clear()
        return mobs.size
    }

    /**
     * Removes leftovers from a previous session.
     *
     * The tracker is empty after a restart but the entities are not, so the PDC tag is the only
     * thing that identifies them. An entity carrying only affixes is stripped rather than
     * deleted when configured that way - it is still an ordinary vanilla mob underneath, and
     * deleting it would look to a player like their world quietly losing its wildlife.
     */
    fun sweepLeftovers(): Int {
        var removed = 0
        for (world in Bukkit.getWorlds()) {
            for (entity in world.livingEntities) {
                if (tracker.isCustom(entity)) continue
                if (!keys.isTagged(entity)) continue
                if (keys.isAffixedVanilla(entity) && config.affixDefaults.stripVanillaInsteadOfRemove) {
                    keys.clear(entity)
                    entity.customName(null)
                    entity.isCustomNameVisible = false
                    continue
                }
                entity.remove()
                removed++
            }
        }
        return removed
    }

    /** Same sweep, for one chunk that just loaded. */
    fun sweepChunk(chunk: org.bukkit.Chunk): Int {
        var removed = 0
        for (entity in chunk.entities) {
            val living = entity as? org.bukkit.entity.LivingEntity ?: continue
            if (tracker.isCustom(living)) continue
            if (!keys.isTagged(living)) continue
            if (keys.isAffixedVanilla(living) && config.affixDefaults.stripVanillaInsteadOfRemove) {
                keys.clear(living)
                living.customName(null)
                living.isCustomNameVisible = false
                continue
            }
            living.remove()
            removed++
        }
        return removed
    }

    // --- messaging helpers ------------------------------------------------------

    fun broadcast(raw: String, ph: Ph?) {
        if (raw.isBlank()) return
        for (player in Bukkit.getOnlinePlayers()) {
            player.sendMessage(Text.render(raw, ph, player))
        }
    }

    /** Broadcast limited to players who could plausibly see the event. */
    fun broadcastNear(location: Location, raw: String, ph: Ph?, radius: Double = 64.0) {
        if (raw.isBlank()) return
        val world = location.world ?: return
        val radiusSq = radius * radius
        for (player in world.players) {
            if (player.location.distanceSquared(location) > radiusSq) continue
            player.sendMessage(Text.render(raw, ph, player))
        }
    }
}
