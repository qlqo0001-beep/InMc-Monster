package com.inmc.monster

import com.inmc.monster.affix.Affix
import com.inmc.monster.config.Messages
import com.inmc.monster.config.PluginConfig
import com.inmc.monster.config.WorldSettings
import com.inmc.monster.mob.MobDefinition
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginConfigTest {

    private fun parse(yaml: String): PluginConfig =
        PluginConfig.from(YamlConfiguration().apply { loadFromString(yaml) })

    @Test
    fun `an empty file yields safe defaults`() {
        val config = parse("")
        assertEquals(setOf(SpawnReason.NATURAL), config.replacement.allowedReasons)
        assertEquals(3, config.maxSpawnGeneration)
        assertTrue(config.budget.global > 0)
        assertTrue(config.cleanup.onStartup)
    }

    @Test
    fun `an empty spawn-reason list falls back to NATURAL alone`() {
        // An empty list must not mean "everything". Replacing every spawn reason is the single
        // most damaging misconfiguration available - REINFORCEMENTS compounds without bound and
        // BREED turns an animal farm into a monster farm - so the fallback has to be the safe
        // one, not the permissive one.
        val config = parse(
            """
            replacement:
              allowed-spawn-reasons: []
            """.trimIndent(),
        )
        assertEquals(setOf(SpawnReason.NATURAL), config.replacement.allowedReasons)
    }

    @Test
    fun `an all-typo spawn-reason list falls back to NATURAL alone`() {
        val config = parse(
            """
            replacement:
              allowed-spawn-reasons:
                - NATURALL
                - nonsense
            """.trimIndent(),
        )
        assertEquals(setOf(SpawnReason.NATURAL), config.replacement.allowedReasons)
    }

    @Test
    fun `explicitly listed reasons are honoured`() {
        val config = parse(
            """
            replacement:
              allowed-spawn-reasons:
                - NATURAL
                - PATROL
                - typo_ignored
            """.trimIndent(),
        )
        assertEquals(setOf(SpawnReason.NATURAL, SpawnReason.PATROL), config.replacement.allowedReasons)
    }

    @Test
    fun `out-of-range values are clamped rather than accepted`() {
        val config = parse(
            """
            replacement:
              chance-multiplier: 9999.0
            affix:
              vanilla-chance: 500.0
              max-prefix: 99
            max-spawn-generation: 99
            skill-tick-period: 0
            triggers:
              counter-expiry-days: 0
            """.trimIndent(),
        )
        assertEquals(100.0, config.replacement.chanceMultiplier)
        assertEquals(100.0, config.affixDefaults.vanillaChance)
        assertEquals(3, config.affixDefaults.maxPrefix)
        assertEquals(10, config.maxSpawnGeneration)
        assertEquals(1L, config.skillTickPeriod)
        assertEquals(1, config.triggers.counterExpiryDays)
    }

    @Test
    fun `every configured value is read back`() {
        val config = parse(
            """
            debug: true
            budget:
              global-max: 123
              per-world-max: 45
              per-chunk-max: 3
              near-player-max: 7
              near-player-radius: 24
              modelled-max: 11
            replacement:
              enabled: false
              chance-multiplier: 0.5
            affix:
              vanilla-enabled: false
              vanilla-chance: 1.5
              custom-chance: 20.0
              max-prefix: 2
              max-suffix: 1
              strip-vanilla-instead-of-remove: false
            drops:
              default-chance: 33.0
              require-player-kill: false
              killer-protect-seconds: 20
              announce: false
            display:
              nameplates: false
              show-health: false
              show-level: false
              boss-bars: false
              boss-bar-radius: 100
              announce-spawn: true
              announce-death: true
            cleanup:
              on-startup: false
              on-chunk-load: false
              on-shutdown: false
            triggers:
              enabled: false
              counter-expiry-days: 30
              default-cooldown-seconds: 45
              default-daily-limit: 3
            max-spawn-generation: 1
            skill-tick-period: 4
            """.trimIndent(),
        )

        assertTrue(config.debug)
        assertEquals(123, config.budget.global)
        assertEquals(45, config.budget.perWorld)
        assertEquals(3, config.budget.perChunk)
        assertEquals(7, config.budget.nearPlayer)
        assertEquals(24, config.budget.nearPlayerRadius)
        assertEquals(11, config.budget.modelled)
        assertFalse(config.replacement.enabled)
        assertEquals(0.5, config.replacement.chanceMultiplier)
        assertFalse(config.affixDefaults.vanillaEnabled)
        assertEquals(1.5, config.affixDefaults.vanillaChance)
        assertEquals(20.0, config.affixDefaults.customChance)
        assertFalse(config.affixDefaults.stripVanillaInsteadOfRemove)
        assertEquals(33.0, config.dropDefaults.chance)
        assertFalse(config.dropDefaults.requirePlayerKill)
        assertEquals(20, config.dropDefaults.protectSeconds)
        assertFalse(config.display.nameplates)
        assertEquals(100, config.display.bossBarRadius)
        assertTrue(config.display.announceSpawn)
        assertFalse(config.cleanup.onStartup)
        assertFalse(config.triggers.enabled)
        assertEquals(30, config.triggers.counterExpiryDays)
        assertEquals(1, config.maxSpawnGeneration)
        assertEquals(4L, config.skillTickPeriod)
    }
}

class WorldSettingsTest {

    private val defaults = PluginConfig.from(YamlConfiguration())

    @Test
    fun `defaults allow everything in an enabled world`() {
        val settings = WorldSettings.defaults("world", defaults)
        assertTrue(settings.enabled)
        assertTrue(settings.allows("anything"))
        assertFalse(settings.touchesVanilla)
    }

    @Test
    fun `a disabled world allows nothing`() {
        val settings = WorldSettings.defaults("world", defaults).copy(enabled = false)
        assertFalse(settings.allows("anything"))
    }

    @Test
    fun `an allow-list excludes everything not on it`() {
        val settings = WorldSettings.defaults("world", defaults)
            .copy(allowedMobs = setOf("부패한기사"))
        assertTrue(settings.allows("부패한기사"))
        assertTrue(settings.allows("부패한기사".uppercase()))
        assertFalse(settings.allows("썩은병사"))
    }

    @Test
    fun `a block-list beats an allow-list`() {
        val settings = WorldSettings.defaults("world", defaults)
            .copy(allowedMobs = setOf("boss"), blockedMobs = setOf("boss"))
        assertFalse(settings.allows("boss"))
    }

    @Test
    fun `vanilla multipliers are only flagged when they differ from one`() {
        val untouched = WorldSettings.defaults("world", defaults)
        assertFalse(untouched.touchesVanilla)
        assertTrue(untouched.copy(vanillaHealthMultiplier = 1.5).touchesVanilla)
        assertTrue(untouched.copy(vanillaDamageMultiplier = 0.5).touchesVanilla)
    }

    @Test
    fun `settings round trip through yaml`() {
        val original = WorldSettings.defaults("world", defaults).copy(
            enabled = false,
            replacementMultiplier = 2.5,
            affixMultiplier = 0.5,
            dropMultiplier = 3.0,
            expMultiplier = 1.5,
            maxMobs = 77,
            allowedMobs = setOf("a", "b"),
            blockedMobs = setOf("c"),
            triggersEnabled = false,
            spawnersEnabled = false,
            vanillaHealthMultiplier = 1.25,
            vanillaDamageMultiplier = 0.75,
        )
        val yaml = YamlConfiguration()
        original.save(yaml)
        val loaded = WorldSettings.load(
            "world",
            YamlConfiguration().apply { loadFromString(yaml.saveToString()) },
            defaults,
        )

        assertFalse(loaded.enabled)
        assertEquals(2.5, loaded.replacementMultiplier)
        assertEquals(0.5, loaded.affixMultiplier)
        assertEquals(3.0, loaded.dropMultiplier)
        assertEquals(1.5, loaded.expMultiplier)
        assertEquals(77, loaded.maxMobs)
        assertEquals(setOf("a", "b"), loaded.allowedMobs)
        assertEquals(setOf("c"), loaded.blockedMobs)
        assertFalse(loaded.triggersEnabled)
        assertFalse(loaded.spawnersEnabled)
        assertEquals(1.25, loaded.vanillaHealthMultiplier)
        assertEquals(0.75, loaded.vanillaDamageMultiplier)
    }
}

class MessagesTest {

    @Test
    fun `missing keys fall back to the built-in default`() {
        val messages = Messages.from(YamlConfiguration())
        assertTrue(messages.raw("mob-unknown").isNotEmpty())
        assertTrue(messages.raw(Messages.PREFIX).isNotEmpty())
    }

    @Test
    fun `a partially edited file keeps the untouched defaults`() {
        // The realistic failure: an admin deletes a line they did not want, and every message
        // that shared the file goes blank. Only the key they actually overrode should change.
        val yaml = YamlConfiguration().apply { loadFromString("mob-unknown: \"custom\"") }
        val messages = Messages.from(yaml)
        assertEquals("custom", messages.raw("mob-unknown"))
        assertEquals(Messages.DEFAULTS["spawn-failed"], messages.raw("spawn-failed"))
    }

    @Test
    fun `an unknown key is empty rather than throwing`() {
        assertEquals("", Messages.from(YamlConfiguration()).raw("no-such-key"))
    }
}

/**
 * The files the plugin actually ships.
 *
 * A bundled config that does not parse is a first-boot crash for every server that installs
 * this, and it is invisible until someone runs it - the build is perfectly happy either way.
 */
class BundledResourceTest {

    private fun resource(path: String): File {
        val file = File("src/main/resources/$path")
        assertTrue(file.exists(), "missing bundled resource: $path")
        return file
    }

    private fun load(path: String): YamlConfiguration =
        YamlConfiguration().apply { loadFromString(resource(path).readText(Charsets.UTF_8)) }

    @Test
    fun `config yml parses and produces the documented defaults`() {
        val config = PluginConfig.from(load("config.yml"))
        // The shipped file must agree with the safe defaults, not merely parse.
        assertEquals(setOf(SpawnReason.NATURAL), config.replacement.allowedReasons)
        assertTrue(config.maxSpawnGeneration in 1..5)
        assertTrue(config.budget.global > 0)
        assertTrue(config.budget.modelled > 0)
        assertTrue(config.cleanup.onStartup)
        assertFalse(config.debug)
    }

    @Test
    fun `messages yml parses and covers the keys the code sends`() {
        val messages = Messages.from(load("messages.yml"))
        // Every key the plugin looks up must resolve to something; a blank would be a message
        // the player simply never sees, with nothing in the log to say so.
        for (key in Messages.DEFAULTS.keys) {
            assertTrue(messages.raw(key).isNotEmpty(), "blank message for key: $key")
        }
    }

    @Test
    fun `affixes yml parses into usable affixes`() {
        val yaml = load("affixes.yml")
        val ids = yaml.getKeys(false)
        assertTrue(ids.isNotEmpty(), "the shipped affix file is empty")

        for (id in ids) {
            val section = yaml.getConfigurationSection(id)
            assertNotNull(section, "affix '$id' is not a section")
            val affix = Affix.load(id, section)
            assertTrue(affix.weight > 0, "affix '$id' has no weight")
            assertTrue(
                affix.modifiers.isNotEmpty() || affix.skills.isNotEmpty(),
                "affix '$id' changes nothing - it would only rename the mob",
            )
        }
    }

    @Test
    fun `the example mob parses and is shipped disabled`() {
        val definition = MobDefinition.load("example", load("mobs/example.yml"), 25.0)
        // Disabled on purpose: a server should not find something new prowling the field the
        // first time it starts with this plugin installed.
        assertFalse(definition.enabled, "the example mob must ship disabled")
        assertFalse(definition.replacement.enabled, "the example mob must not replace vanilla spawns")
        assertTrue(definition.stats["MAX_HEALTH"]!! > 0.0)
        assertTrue(definition.skills.isNotEmpty())
        assertTrue(definition.phases.isNotEmpty())
    }

    @Test
    fun `the example mob's skills and actions all exist`() {
        // A shipped example that references a skill id we do not register would greet a new
        // admin with a warning in the console on the first spawn.
        val definition = MobDefinition.load("example", load("mobs/example.yml"), 25.0)
        val known = com.inmc.monster.skill.builtin.BuiltinSkills.all().map { it.id }.toSet()

        for (skill in definition.skills) {
            assertTrue(known.contains(skill.skillId), "example references unknown skill: ${skill.skillId}")
        }
        for (phase in definition.phases) {
            for (step in phase.steps) {
                assertTrue(known.contains(step.skillId), "example pattern references unknown skill: ${step.skillId}")
            }
        }
    }

    @Test
    fun `the example mob's equipment does not drop`() {
        // Equipment on a custom mob is routinely stronger than the economy expects players to
        // own. The shipped example must not teach otherwise.
        val definition = MobDefinition.load("example", load("mobs/example.yml"), 25.0)
        for ((slot, entry) in definition.equipment.entries()) {
            assertEquals(0.0, entry.dropChance, "example equipment in $slot has a non-zero drop chance")
        }
    }

    @Test
    fun `paper-plugin yml declares every soft dependency the hooks look for`() {
        val yaml = load("paper-plugin.yml")
        val declared = yaml.getConfigurationSection("dependencies.server")?.getKeys(false).orEmpty()
        // A hook that is never declared cannot join our classpath, which is exactly the failure
        // that cost the random-box plugin its ItemsAdder integration.
        for (plugin in listOf(
            "MMOItems", "MythicLib", "BetterModel", "ModelEngine",
            "MythicMobs", "MagicSpells", "ItemsAdder", "WorldGuard", "Lands", "Vault", "PlaceholderAPI",
        )) {
            assertTrue(declared.contains(plugin), "paper-plugin.yml does not declare $plugin")
        }
    }

    @Test
    fun `every declared dependency is optional and load-order free`() {
        val yaml = load("paper-plugin.yml")
        val section = yaml.getConfigurationSection("dependencies.server")
        assertNotNull(section)
        for (plugin in section.getKeys(false)) {
            val entry = section.getConfigurationSection(plugin)
            assertNotNull(entry, "$plugin has no settings")
            assertFalse(entry.getBoolean("required", true), "$plugin must not be required")
            // load: OMIT keeps us out of the dependency cycle that previously made Paper drop one
            // of our edges and silently break a hook.
            assertEquals("OMIT", entry.getString("load"), "$plugin must use load: OMIT")
            assertTrue(entry.getBoolean("join-classpath"), "$plugin must join our classpath")
        }
    }
}
