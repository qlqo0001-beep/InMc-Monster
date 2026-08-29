package com.inmc.monster

import com.inmc.monster.affix.Affix
import com.inmc.monster.affix.AffixTarget
import com.inmc.monster.affix.AffixType
import com.inmc.monster.affix.StatModifier
import com.inmc.monster.death.DeathAction
import com.inmc.monster.death.DeathActionType
import com.inmc.monster.mob.DropDistribution
import com.inmc.monster.mob.DropTier
import com.inmc.monster.mob.LevelSource
import com.inmc.monster.mob.MobDefinition
import com.inmc.monster.mob.MobDrop
import com.inmc.monster.pattern.PatternStep
import com.inmc.monster.pattern.Phase
import com.inmc.monster.skill.SkillInstance
import com.inmc.monster.skill.SkillTrigger
import com.inmc.monster.skill.TargetSelector
import com.inmc.monster.spawn.SkyRule
import com.inmc.monster.spawn.Spawner
import com.inmc.monster.spawn.SpawnerEntry
import com.inmc.monster.spawn.SpawnerShape
import com.inmc.monster.spawn.TimeRule
import com.inmc.monster.spawn.WeatherRule
import com.inmc.monster.trigger.MessageStyle
import com.inmc.monster.trigger.SpawnAnchor
import com.inmc.monster.trigger.Trigger
import com.inmc.monster.trigger.TriggerType
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.inventory.EquipmentSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Save-then-load round trips.
 *
 * These matter more than they look. A setting that saves but does not load back reads as "the
 * GUI did not save my change", and there is no error anywhere to point at - the value simply
 * reverts on the next restart. Every field an admin can edit is checked here.
 */
class MobDefinitionRoundTripTest {

    private fun fullyPopulated(): MobDefinition = MobDefinition("부패한기사").apply {
        displayName = "<dark_red>부패한 기사</dark_red>"
        enabled = false
        parent = "언데드기본형"
        entityType = EntityType.HUSK
        model = "corrupted_knight"
        tags = linkedSetOf("boss", "dungeon")

        stats["MAX_HEALTH"] = 250.0
        stats["ATTACK_DAMAGE"] = 17.5
        customStats["CRITICAL_STRIKE_CHANCE"] = 22.0

        level.base = 12
        level.min = 8
        level.max = 40
        level.source = LevelSource.Y_DEPTH
        level.step = 32.0
        level.perLevel["MAX_HEALTH"] = 7.5

        flags.baby = true
        flags.burnInSunlight = false
        flags.bossBar = true
        flags.lifespanSeconds = 600
        flags.targetPlayersOnly = true

        immunities.knockbackResistance = 0.75
        immunities.fall = true
        immunities.wither = true
        immunities.potions = linkedSetOf("POISON")
        immunities.damageCauses.add(org.bukkit.event.entity.EntityDamageEvent.DamageCause.CRAMMING)

        equipment[EquipmentSlot.HAND] = com.inmc.monster.mob.EquipmentEntry(
            item = com.inmc.monster.item.StoredItem(
                ref = com.inmc.monster.item.ItemRef.MMOItems("SWORD", "CURSED_BLADE"),
                material = Material.IRON_SWORD,
            ),
            dropChance = 2.5,
        )

        drops.minRolls = 1
        drops.maxRolls = 4
        drops.distribution = DropDistribution.INSTANCED
        drops.requirePlayerKill = false
        drops.protectSeconds = 15
        drops.chanceMultiplier = 1.5
        drops.keepVanillaDrops = true
        drops.expMin = 40
        drops.expMax = 90
        drops.tiers["희귀"] = DropTier(minRolls = 1, maxRolls = 1)
        drops.entries.add(
            MobDrop(
                id = "drop1",
                item = com.inmc.monster.item.StoredItem(
                    ref = com.inmc.monster.item.ItemRef.Vanilla(Material.DIAMOND),
                    material = Material.DIAMOND,
                ),
                chance = 12.5,
                minAmount = 2,
                maxAmount = 5,
                announce = true,
                commands = mutableListOf("eco give {플레이어네임} 500"),
                giveItem = false,
                tier = "희귀",
                requiredTools = mutableListOf("DIAMOND_SWORD"),
                minLevel = 20,
            ),
        )

        affixes.enabled = false
        affixes.chance = 42.0
        affixes.maxPrefix = 2
        affixes.maxSuffix = 0
        affixes.allowed = linkedSetOf("강력한")
        affixes.blocked = linkedSetOf("날쌘")

        replacement.enabled = true
        replacement.chance = 7.25
        replacement.weight = 33
        replacement.replaces = linkedSetOf(EntityType.ZOMBIE, EntityType.HUSK)
        replacement.rules.minY = 10
        replacement.rules.maxY = 60
        replacement.rules.time = TimeRule.NIGHT
        replacement.rules.weather = WeatherRule.THUNDER
        replacement.rules.sky = SkyRule.CAVE
        replacement.rules.biomes = linkedSetOf("dark_forest")
        replacement.rules.minPlayerDistance = 12.0
        replacement.rules.allowInClaims = true
        replacement.rules.moonPhases = linkedSetOf(0, 4)
        replacement.rules.standingOn.add(Material.STONE)

        skills.add(
            SkillInstance(
                skillId = "ground_slam",
                trigger = SkillTrigger.ON_LOW_HEALTH,
                selector = TargetSelector.ALL_PLAYERS_IN_RADIUS,
                cooldownTicks = 240,
                chance = 55.5,
                radius = 12.0,
                healthThreshold = 33.0,
                castTimeTicks = 30,
                interruptOnDamage = true,
                phases = linkedSetOf("광폭"),
                enabled = false,
            ).apply {
                values["damage"] = 22.0
                values["launch"] = 1.2
            },
        )

        phases.add(
            Phase("광폭", healthAbove = 35.0).apply {
                skillPower = 1.4
                loop = false
                model = "knight_rage"
                statMultipliers["ATTACK_DAMAGE"] = 1.3
                steps.add(PatternStep(delayTicks = 45, skillId = "explode").apply { values["radius"] = 8.0 })
                onEnter.add(
                    DeathAction(DeathActionType.BROADCAST, chance = 80.0, requirePlayerKill = false).apply {
                        values["message"] = "광폭화!"
                        values["scope"] = "RADIUS"
                    },
                )
            },
        )

        deathActions.add(
            DeathAction(DeathActionType.SPAWN_MOB, chance = 65.0, requirePlayerKill = true).apply {
                worlds = linkedSetOf("world")
                killerPermission = "monster.boss"
                values["mob"] = "썩은병사"
                values["amount"] = 3
            },
        )
    }

    private fun roundTrip(definition: MobDefinition): MobDefinition {
        val yaml = YamlConfiguration()
        definition.save(yaml)
        // Through text as well as through the object, because YAML type coercion on reload is
        // where values quietly change shape (an Int written, a Double expected).
        val reparsed = YamlConfiguration().apply { loadFromString(yaml.saveToString()) }
        return MobDefinition.load(definition.id, reparsed, 25.0)
    }

    @Test
    fun `identity and appearance survive`() {
        val original = fullyPopulated()
        val loaded = roundTrip(original)

        assertEquals(original.displayName, loaded.displayName)
        assertEquals(original.enabled, loaded.enabled)
        assertEquals(original.parent, loaded.parent)
        assertEquals(original.entityType, loaded.entityType)
        assertEquals(original.model, loaded.model)
        assertEquals(original.tags, loaded.tags)
    }

    @Test
    fun `stats and level curve survive`() {
        val original = fullyPopulated()
        val loaded = roundTrip(original)

        assertEquals(250.0, loaded.stats["MAX_HEALTH"])
        assertEquals(17.5, loaded.stats["ATTACK_DAMAGE"])
        assertEquals(22.0, loaded.customStats["CRITICAL_STRIKE_CHANCE"])
        assertEquals(original.level.base, loaded.level.base)
        assertEquals(original.level.min, loaded.level.min)
        assertEquals(original.level.max, loaded.level.max)
        assertEquals(original.level.source, loaded.level.source)
        assertEquals(original.level.step, loaded.level.step)
        assertEquals(7.5, loaded.level.perLevel["MAX_HEALTH"])
    }

    @Test
    fun `flags and immunities survive`() {
        val loaded = roundTrip(fullyPopulated())

        assertTrue(loaded.flags.baby)
        assertTrue(!loaded.flags.burnInSunlight)
        assertTrue(loaded.flags.bossBar)
        assertTrue(loaded.flags.targetPlayersOnly)
        assertEquals(600, loaded.flags.lifespanSeconds)

        assertEquals(0.75, loaded.immunities.knockbackResistance)
        assertTrue(loaded.immunities.fall)
        assertTrue(loaded.immunities.wither)
        assertTrue(loaded.immunities.potions.contains("POISON"))
        assertTrue(
            loaded.immunities.damageCauses
                .contains(org.bukkit.event.entity.EntityDamageEvent.DamageCause.CRAMMING),
        )
    }

    @Test
    fun `equipment survives with its drop chance`() {
        val loaded = roundTrip(fullyPopulated())
        val hand = loaded.equipment[EquipmentSlot.HAND]
        assertNotNull(hand)
        assertEquals(2.5, hand.dropChance)
        assertEquals("mmoitems:SWORD:CURSED_BLADE", hand.item.ref.serialize())
    }

    @Test
    fun `the drop table survives entry for entry`() {
        val loaded = roundTrip(fullyPopulated())
        val table = loaded.drops

        assertEquals(1, table.minRolls)
        assertEquals(4, table.maxRolls)
        assertEquals(DropDistribution.INSTANCED, table.distribution)
        assertTrue(!table.requirePlayerKill)
        assertEquals(15, table.protectSeconds)
        assertEquals(1.5, table.chanceMultiplier)
        assertTrue(table.keepVanillaDrops)
        assertEquals(40, table.expMin)
        assertEquals(90, table.expMax)
        assertEquals(1, table.tiers["희귀"]?.maxRolls)

        assertEquals(1, table.entries.size)
        val drop = table.entries.first()
        assertEquals("drop1", drop.id)
        assertEquals(12.5, drop.chance)
        assertEquals(2, drop.minAmount)
        assertEquals(5, drop.maxAmount)
        assertTrue(drop.announce)
        assertTrue(!drop.giveItem)
        assertEquals("희귀", drop.tier)
        assertEquals(listOf("eco give {플레이어네임} 500"), drop.commands)
        assertEquals(listOf("DIAMOND_SWORD"), drop.requiredTools)
        assertEquals(20, drop.minLevel)
    }

    @Test
    fun `affix policy survives, including the negative sentinel`() {
        val loaded = roundTrip(fullyPopulated())
        assertTrue(!loaded.affixes.enabled)
        assertEquals(42.0, loaded.affixes.chance)
        assertEquals(2, loaded.affixes.maxPrefix)
        assertEquals(0, loaded.affixes.maxSuffix)
        assertEquals(setOf("강력한"), loaded.affixes.allowed)
        assertEquals(setOf("날쌘"), loaded.affixes.blocked)
    }

    @Test
    fun `a default affix chance stays as -1 rather than becoming zero`() {
        // -1 means "use the global default" and 0 means "never". Collapsing one into the other
        // would silently switch affixes off for every mob that never set a chance.
        val definition = MobDefinition.create("plain")
        val loaded = roundTrip(definition)
        assertEquals(-1.0, loaded.affixes.chance)
        assertEquals(-1, loaded.affixes.maxPrefix)
    }

    @Test
    fun `replacement settings and spawn rules survive`() {
        val loaded = roundTrip(fullyPopulated())
        val settings = loaded.replacement

        assertTrue(settings.enabled)
        assertEquals(7.25, settings.chance)
        assertEquals(33, settings.weight)
        assertEquals(setOf(EntityType.ZOMBIE, EntityType.HUSK), settings.replaces)

        val rules = settings.rules
        assertEquals(10, rules.minY)
        assertEquals(60, rules.maxY)
        assertEquals(TimeRule.NIGHT, rules.time)
        assertEquals(WeatherRule.THUNDER, rules.weather)
        assertEquals(SkyRule.CAVE, rules.sky)
        assertEquals(setOf("dark_forest"), rules.biomes)
        assertEquals(12.0, rules.minPlayerDistance)
        assertTrue(rules.allowInClaims)
        assertEquals(setOf(0, 4), rules.moonPhases)
        assertTrue(rules.standingOn.contains(Material.STONE))
    }

    @Test
    fun `skills survive with their parameter values`() {
        val loaded = roundTrip(fullyPopulated())
        assertEquals(1, loaded.skills.size)
        val skill = loaded.skills.first()

        assertEquals("ground_slam", skill.skillId)
        assertEquals(SkillTrigger.ON_LOW_HEALTH, skill.trigger)
        assertEquals(TargetSelector.ALL_PLAYERS_IN_RADIUS, skill.selector)
        assertEquals(240, skill.cooldownTicks)
        assertEquals(55.5, skill.chance)
        assertEquals(12.0, skill.radius)
        assertEquals(33.0, skill.healthThreshold)
        assertEquals(30, skill.castTimeTicks)
        assertTrue(skill.interruptOnDamage)
        assertEquals(setOf("광폭"), skill.phases)
        assertTrue(!skill.enabled)
        assertEquals(22.0, (skill.values["damage"] as Number).toDouble())
    }

    @Test
    fun `phases survive with their patterns and entrance actions`() {
        val loaded = roundTrip(fullyPopulated())
        assertEquals(1, loaded.phases.size)
        val phase = loaded.phases.first()

        assertEquals("광폭", phase.name)
        assertEquals(35.0, phase.healthAbove)
        assertEquals(1.4, phase.skillPower)
        assertTrue(!phase.loop)
        assertEquals("knight_rage", phase.model)
        assertEquals(1.3, phase.statMultipliers["ATTACK_DAMAGE"])

        assertEquals(1, phase.steps.size)
        assertEquals(45, phase.steps.first().delayTicks)
        assertEquals("explode", phase.steps.first().skillId)

        assertEquals(1, phase.onEnter.size)
        assertEquals(DeathActionType.BROADCAST, phase.onEnter.first().type)
        assertEquals(80.0, phase.onEnter.first().chance)
    }

    @Test
    fun `death actions survive with their conditions`() {
        val loaded = roundTrip(fullyPopulated())
        assertEquals(1, loaded.deathActions.size)
        val action = loaded.deathActions.first()

        assertEquals(DeathActionType.SPAWN_MOB, action.type)
        assertEquals(65.0, action.chance)
        assertTrue(action.requirePlayerKill)
        assertEquals(setOf("world"), action.worlds)
        assertEquals("monster.boss", action.killerPermission)
        assertEquals("썩은병사", action.params().string("mob"))
        assertEquals(3, action.params().int("amount"))
    }

    @Test
    fun `a second round trip changes nothing`() {
        // Stability matters as much as fidelity: the registry rewrites a mob every time it is
        // edited, so a value that drifts by one trip would drift a little more every save.
        val once = roundTrip(fullyPopulated())
        val twice = roundTrip(once)

        val first = YamlConfiguration().also { once.save(it) }.saveToString()
        val second = YamlConfiguration().also { twice.save(it) }.saveToString()
        assertEquals(first, second)
    }

    @Test
    fun `a brand new mob round trips`() {
        val created = MobDefinition.create("새몹")
        val loaded = roundTrip(created)
        assertEquals(20.0, loaded.stats["MAX_HEALTH"])
        assertEquals(3.0, loaded.stats["ATTACK_DAMAGE"])
        assertEquals(EntityType.ZOMBIE, loaded.entityType)
        assertTrue(loaded.enabled)
    }
}

class SpawnerRoundTripTest {

    @Test
    fun `every spawner field survives`() {
        val spawner = Spawner("던전1층").apply {
            enabled = false
            blockBound = true
            world = "world"
            x = 100.5; y = 64.0; z = -200.5
            x2 = 120.0; y2 = 70.0; z2 = -180.0
            shape = SpawnerShape.BOX
            radius = 12.0
            intervalSeconds = 45
            maxAlive = 8
            perAttemptMin = 2
            perAttemptMax = 4
            chance = 66.6
            activationRange = 96.0
            minPlayerDistance = 6.0
            blockMaterial = Material.SPAWNER
            tag = "dungeon:floor1"
            rules.time = TimeRule.DAY
            entries.add(SpawnerEntry("부패한기사", weight = 25, amount = 2))
            entries.add(SpawnerEntry("썩은병사", weight = 75, amount = 1))
        }

        val yaml = YamlConfiguration()
        spawner.save(yaml)
        val loaded = Spawner.load("던전1층", YamlConfiguration().apply { loadFromString(yaml.saveToString()) })

        assertEquals(false, loaded.enabled)
        assertTrue(loaded.blockBound)
        assertEquals("world", loaded.world)
        assertEquals(100.5, loaded.x)
        assertEquals(-180.0, loaded.z2)
        assertEquals(SpawnerShape.BOX, loaded.shape)
        assertEquals(12.0, loaded.radius)
        assertEquals(45, loaded.intervalSeconds)
        assertEquals(8, loaded.maxAlive)
        assertEquals(2, loaded.perAttemptMin)
        assertEquals(4, loaded.perAttemptMax)
        assertEquals(66.6, loaded.chance)
        assertEquals(96.0, loaded.activationRange)
        assertEquals(6.0, loaded.minPlayerDistance)
        assertEquals("dungeon:floor1", loaded.tag)
        assertEquals(TimeRule.DAY, loaded.rules.time)
        assertEquals(2, loaded.entries.size)
        assertEquals("부패한기사", loaded.entries[0].mobId)
        assertEquals(75, loaded.entries[1].weight)
    }

    @Test
    fun `a spawner with no mobs is not valid`() {
        val spawner = Spawner("empty").apply { world = "world" }
        assertTrue(!spawner.isValid())
        spawner.entries.add(SpawnerEntry("mob"))
        assertTrue(spawner.isValid())
    }

    @Test
    fun `copies are independent`() {
        val spawner = Spawner("a").apply {
            world = "world"
            entries.add(SpawnerEntry("mob", 10, 1))
        }
        val copy = spawner.copyOf("b")
        copy.entries.clear()
        assertEquals(1, spawner.entries.size)
        assertEquals("b", copy.id)
    }
}

class TriggerRoundTripTest {

    @Test
    fun `every trigger field survives`() {
        val trigger = Trigger("나무꾼의저주").apply {
            enabled = false
            type = TriggerType.BLOCK_BREAK
            materials = linkedSetOf(Material.OAK_LOG, Material.BIRCH_LOG)
            entityTypes = linkedSetOf(EntityType.ZOMBIE)
            count = 10
            chance = 12.5
            mobId = "분노한나무정령"
            amount = 2
            anchor = SpawnAnchor.RANDOM_NEARBY
            spawnRadius = 9.0
            message = "<red>숲이 분노했다!</red>"
            messageStyle = MessageStyle.TITLE
            cooldownSeconds = 300
            dailyLimit = 5
            resetCounter = false
            worlds = linkedSetOf("world")
            permission = "monster.trigger.forest"
            intervalSeconds = 600
            atTime = 18000
            rules.weather = WeatherRule.RAIN
        }

        val yaml = YamlConfiguration()
        trigger.save(yaml)
        val loaded = Trigger.load("나무꾼의저주", YamlConfiguration().apply { loadFromString(yaml.saveToString()) })

        assertEquals(false, loaded.enabled)
        assertEquals(TriggerType.BLOCK_BREAK, loaded.type)
        assertEquals(setOf(Material.OAK_LOG, Material.BIRCH_LOG), loaded.materials)
        assertEquals(setOf(EntityType.ZOMBIE), loaded.entityTypes)
        assertEquals(10, loaded.count)
        assertEquals(12.5, loaded.chance)
        assertEquals("분노한나무정령", loaded.mobId)
        assertEquals(2, loaded.amount)
        assertEquals(SpawnAnchor.RANDOM_NEARBY, loaded.anchor)
        assertEquals(9.0, loaded.spawnRadius)
        assertEquals("<red>숲이 분노했다!</red>", loaded.message)
        assertEquals(MessageStyle.TITLE, loaded.messageStyle)
        assertEquals(300, loaded.cooldownSeconds)
        assertEquals(5, loaded.dailyLimit)
        assertEquals(false, loaded.resetCounter)
        assertEquals(setOf("world"), loaded.worlds)
        assertEquals("monster.trigger.forest", loaded.permission)
        assertEquals(600, loaded.intervalSeconds)
        assertEquals(18000L, loaded.atTime)
        assertEquals(WeatherRule.RAIN, loaded.rules.weather)
    }

    @Test
    fun `countable types are classified correctly`() {
        assertTrue(TriggerType.BLOCK_BREAK.countable)
        assertTrue(TriggerType.MOB_KILL.countable)
        assertTrue(!TriggerType.WEATHER_CHANGE.countable)
        assertTrue(!TriggerType.PLAYER_JOIN.countable)
        assertTrue(!TriggerType.INTERVAL.countable)
    }

    @Test
    fun `empty filters match everything`() {
        val trigger = Trigger("any")
        assertTrue(trigger.matchesMaterial(Material.STONE))
        assertTrue(trigger.matchesEntity(EntityType.CREEPER))
        assertTrue(trigger.matchesWorld("anything"))
    }

    @Test
    fun `populated filters exclude`() {
        val trigger = Trigger("logs").apply {
            materials = linkedSetOf(Material.OAK_LOG)
            worlds = linkedSetOf("world")
        }
        assertTrue(trigger.matchesMaterial(Material.OAK_LOG))
        assertTrue(!trigger.matchesMaterial(Material.STONE))
        assertTrue(trigger.matchesWorld("WORLD"))
        assertTrue(!trigger.matchesWorld("world_nether"))
    }
}

class AffixRoundTripTest {

    @Test
    fun `every affix field survives`() {
        val affix = Affix("강력한").apply {
            display = "<red>강력한</red>"
            type = AffixType.SUFFIX
            enabled = false
            weight = 42
            applyTo = linkedSetOf(AffixTarget.VANILLA)
            entityTypes = linkedSetOf(EntityType.ZOMBIE)
            worlds = linkedSetOf("world")
            dropMultiplier = 1.75
            expMultiplier = 2.25
            minLevel = 15
            glowColor = "RED"
            modifiers["ATTACK_DAMAGE"] = StatModifier(mult = 1.35, add = 2.0)
            modifiers["MAX_HEALTH"] = StatModifier(mult = 1.25)
            skills.add(SkillInstance(skillId = "enrage"))
        }

        val yaml = YamlConfiguration()
        affix.save(yaml.createSection("강력한"))
        val reparsed = YamlConfiguration().apply { loadFromString(yaml.saveToString()) }
        val loaded = Affix.load("강력한", reparsed.getConfigurationSection("강력한")!!)

        assertEquals("<red>강력한</red>", loaded.display)
        assertEquals(AffixType.SUFFIX, loaded.type)
        assertEquals(false, loaded.enabled)
        assertEquals(42, loaded.weight)
        assertEquals(setOf(AffixTarget.VANILLA), loaded.applyTo)
        assertEquals(setOf(EntityType.ZOMBIE), loaded.entityTypes)
        assertEquals(setOf("world"), loaded.worlds)
        assertEquals(1.75, loaded.dropMultiplier)
        assertEquals(2.25, loaded.expMultiplier)
        assertEquals(15, loaded.minLevel)
        assertEquals("RED", loaded.glowColor)
        assertEquals(1.35, loaded.modifiers["ATTACK_DAMAGE"]?.mult)
        assertEquals(2.0, loaded.modifiers["ATTACK_DAMAGE"]?.add)
        assertEquals(1.25, loaded.modifiers["MAX_HEALTH"]?.mult)
        assertEquals(1, loaded.skills.size)
    }

    @Test
    fun `neutral modifiers are dropped rather than stored`() {
        val affix = Affix("noop").apply {
            modifiers["ARMOR"] = StatModifier()
        }
        val yaml = YamlConfiguration()
        affix.save(yaml.createSection("noop"))
        val loaded = Affix.load("noop", yaml.getConfigurationSection("noop")!!)
        assertTrue(loaded.modifiers.isEmpty())
    }

    @Test
    fun `an empty apply-to list falls back to both targets`() {
        // An affix that applies to nothing is never what an admin meant; it is what an empty
        // list in a hand-edited file looks like.
        val yaml = YamlConfiguration()
        val section = yaml.createSection("x")
        section.set("apply-to", emptyList<String>())
        val loaded = Affix.load("x", section)
        assertEquals(setOf(AffixTarget.CUSTOM, AffixTarget.VANILLA), loaded.applyTo)
    }

    @Test
    fun `acceptance honours every filter`() {
        val affix = Affix("test").apply {
            applyTo = linkedSetOf(AffixTarget.CUSTOM)
            entityTypes = linkedSetOf(EntityType.ZOMBIE)
            worlds = linkedSetOf("world")
        }
        assertTrue(affix.accepts(AffixTarget.CUSTOM, EntityType.ZOMBIE, "world"))
        assertTrue(!affix.accepts(AffixTarget.VANILLA, EntityType.ZOMBIE, "world"))
        assertTrue(!affix.accepts(AffixTarget.CUSTOM, EntityType.SKELETON, "world"))
        assertTrue(!affix.accepts(AffixTarget.CUSTOM, EntityType.ZOMBIE, "nether"))

        affix.enabled = false
        assertTrue(!affix.accepts(AffixTarget.CUSTOM, EntityType.ZOMBIE, "world"))
    }
}

class EnumParseTest {

    @Test
    fun `unknown enum names fall back rather than throwing`() {
        // Hand-edited files contain typos. Every parse here has to survive one.
        assertEquals(DropDistribution.KILLER, DropDistribution.parse("nonsense"))
        assertEquals(DropDistribution.KILLER, DropDistribution.parse(null))
        assertEquals(TimeRule.ANY, TimeRule.parse("아무거나"))
        assertEquals(WeatherRule.ANY, WeatherRule.parse(null))
        assertEquals(SkyRule.ANY, SkyRule.parse("???"))
        assertEquals(SpawnerShape.RADIUS, SpawnerShape.parse("bad"))
        assertEquals(SpawnAnchor.PLAYER, SpawnAnchor.parse("bad"))
        assertEquals(MessageStyle.CHAT, MessageStyle.parse("bad"))
        assertEquals(AffixType.PREFIX, AffixType.parse("bad"))
        assertEquals(LevelSource.FIXED, LevelSource.parse("bad"))
        assertEquals(SkillTrigger.ON_TIMER, SkillTrigger.parse("bad"))
        assertEquals(TargetSelector.TARGET, TargetSelector.parse("bad"))
    }

    @Test
    fun `an unknown trigger or action type is reported rather than guessed`() {
        // These two return null instead of a default: guessing which action an admin meant
        // would silently run the wrong thing on every mob death.
        assertNull(TriggerType.parse("nonsense"))
        assertNull(DeathActionType.parse("nonsense"))
    }

    @Test
    fun `enum names parse case-insensitively`() {
        assertEquals(DropDistribution.TOP_DAMAGE, DropDistribution.parse("top_damage"))
        assertEquals(TimeRule.NIGHT, TimeRule.parse("Night"))
        assertEquals(TriggerType.BLOCK_BREAK, TriggerType.parse("block_break"))
    }
}
