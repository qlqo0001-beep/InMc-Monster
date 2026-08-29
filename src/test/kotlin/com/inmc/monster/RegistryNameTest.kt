package com.inmc.monster

import com.inmc.monster.affix.AffixRegistry
import com.inmc.monster.mob.MobRegistry
import com.inmc.monster.skill.SkillInstance
import com.inmc.monster.skill.SkillTrigger
import com.inmc.monster.skill.TargetSelector
import com.inmc.monster.skill.builtin.BuiltinSkills
import com.inmc.monster.spawn.SpawnerRegistry
import com.inmc.monster.trigger.TriggerRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistryNameTest {

    @Test
    fun `korean names are valid identifiers`() {
        // Admins name mobs in Korean, which is exactly why the command layer takes names as
        // greedy strings - Brigadier's unquoted reader stops at the first Hangul character.
        assertTrue(MobRegistry.isValidId("부패한기사"))
        assertTrue(MobRegistry.isValidId("boss_1"))
        assertTrue(MobRegistry.isValidId("night-walker"))
        assertTrue(AffixRegistry.isValidId("강력한"))
        assertTrue(SpawnerRegistry.isValidId("던전_1층"))
        assertTrue(TriggerRegistry.isValidId("나무꾼의저주"))
    }

    @Test
    fun `path separators and spaces are rejected`() {
        // A name is used directly as a file name, so anything that could escape the folder or
        // break the file has to be refused here rather than at write time.
        assertFalse(MobRegistry.isValidId("../escape"))
        assertFalse(MobRegistry.isValidId("with space"))
        assertFalse(MobRegistry.isValidId("slash/name"))
        assertFalse(MobRegistry.isValidId(""))
        assertFalse(MobRegistry.isValidId("a".repeat(33)))
    }
}

class BuiltinSkillTest {

    @Test
    fun `the built-in set comfortably exceeds the required fifteen`() {
        assertTrue(BuiltinSkills.all().size >= 15, "expected at least 15 built-in skills")
    }

    @Test
    fun `skill ids are unique`() {
        val ids = BuiltinSkills.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate skill id: $ids")
    }

    @Test
    fun `every skill declares its parameters with distinct keys`() {
        for (skill in BuiltinSkills.all()) {
            val keys = skill.parameters.map { it.key }
            assertEquals(keys.size, keys.toSet().size, "duplicate parameter key in ${skill.id}: $keys")
        }
    }

    @Test
    fun `numeric parameter defaults sit inside their own range`() {
        // The GUI clamps edits to min..max, so a default outside that range would be silently
        // corrected the first time anyone touched the control - and the skill would behave
        // differently before and after being opened in a menu.
        for (skill in BuiltinSkills.all()) {
            for (param in skill.parameters) {
                val default = param.default as? Number ?: continue
                val value = default.toDouble()
                assertTrue(
                    value >= param.min && value <= param.max,
                    "${skill.id}.${param.key} default $value outside ${param.min}..${param.max}",
                )
            }
        }
    }

    @Test
    fun `enum parameter defaults are one of the offered options`() {
        for (skill in BuiltinSkills.all()) {
            for (param in skill.parameters) {
                if (param.type != com.inmc.monster.skill.ParamType.ENUM) continue
                assertTrue(
                    param.options.contains(param.default.toString()),
                    "${skill.id}.${param.key} default '${param.default}' not in ${param.options}",
                )
            }
        }
    }

    @Test
    fun `parameter lookup resolves declared defaults`() {
        val skill = BuiltinSkills.all().first { it.id == "ground_slam" }
        val instance = SkillInstance(
            skillId = skill.id,
            trigger = SkillTrigger.ON_TIMER,
            selector = TargetSelector.SELF,
        )
        val params = instance.paramsFor(skill)
        // Nothing was configured, so every read must fall back to the skill's own default.
        assertEquals(14.0, params.double("damage"))
        assertEquals(6.0, params.double("radius"))
    }

    @Test
    fun `configured values win over defaults`() {
        val skill = BuiltinSkills.all().first { it.id == "ground_slam" }
        val instance = SkillInstance(skillId = skill.id)
        instance.values["damage"] = 99.0
        assertEquals(99.0, instance.paramsFor(skill).double("damage"))
    }

    @Test
    fun `an unknown parameter reads as zero rather than throwing`() {
        val skill = BuiltinSkills.all().first()
        val params = SkillInstance(skillId = skill.id).paramsFor(skill)
        assertEquals(0.0, params.double("no-such-key"))
        assertEquals("", params.string("no-such-key"))
        assertFalse(params.bool("no-such-key"))
    }

    @Test
    fun `phase membership defaults to every phase`() {
        val instance = SkillInstance(skillId = "explode")
        assertTrue(instance.activeIn(null))
        assertTrue(instance.activeIn("광폭"))

        instance.phases.add("광폭")
        assertFalse(instance.activeIn(null))
        assertFalse(instance.activeIn("1페이즈"))
        assertTrue(instance.activeIn("광폭"))
    }
}
