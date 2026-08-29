package com.inmc.monster

import com.inmc.monster.command.MonsterCommand
import com.inmc.monster.listener.CombatListener
import com.inmc.monster.listener.MenuListener
import com.inmc.monster.listener.SessionListener
import com.inmc.monster.listener.SpawnEggListener
import com.inmc.monster.listener.SpawnListener
import com.inmc.monster.listener.TriggerListener
import com.inmc.monster.scheduler.SkillTicker
import com.inmc.monster.scheduler.Ticker
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/**
 * INMC Custom Monsters.
 *
 * A custom monster system for Paper with no required plugin dependencies. MMOItems/MythicLib,
 * ModelEngine, MythicMobs, MagicSpells, ItemsAdder, WorldGuard, Lands, Vault and PlaceholderAPI
 * are all optional; the plugin boots and works with none of them installed, and every one of
 * them is reached by reflection so a missing jar can never stop a class from loading.
 *
 * Vanilla spawning is never suppressed. Custom mobs enter the world either by taking over a
 * natural spawn - probabilistically, and only for spawn reasons an admin explicitly allowed -
 * or through the plugin's own spawners and triggers.
 */
class MonsterPlugin : JavaPlugin() {

    lateinit var monsters: Monsters
        private set

    private lateinit var ticker: Ticker
    private lateinit var skillTicker: SkillTicker

    override fun onEnable() {
        monsters = Monsters(this)
        ticker = Ticker(monsters)
        skillTicker = SkillTicker(monsters)

        // Commands register through the lifecycle manager, which must be called from onEnable.
        MonsterCommand(monsters).register(this)

        Bukkit.getPluginManager().let { pm ->
            pm.registerEvents(MenuListener(monsters), this)
            pm.registerEvents(SpawnListener(monsters), this)
            pm.registerEvents(CombatListener(monsters), this)
            pm.registerEvents(TriggerListener(monsters), this)
            pm.registerEvents(SessionListener(monsters), this)
            pm.registerEvents(SpawnEggListener(monsters), this)
        }

        // Config and definitions load off the main thread; every listener and both tickers
        // no-op until `monsters.ready` flips.
        monsters.enable {
            logger.info("inmc-monster 활성화 완료")
        }
        ticker.start()
        skillTicker.start()
    }

    override fun onDisable() {
        if (!::monsters.isInitialized) return
        ticker.stop()
        skillTicker.stop()
        monsters.shutdown()
        logger.info("inmc-monster 비활성화")
    }
}
