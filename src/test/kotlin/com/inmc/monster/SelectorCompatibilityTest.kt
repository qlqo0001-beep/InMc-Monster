package com.inmc.monster

import com.inmc.monster.skill.TargetSelector
import com.inmc.monster.skill.builtin.BuiltinSkills
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Skills that need somebody other than the caster to aim at.
 *
 * Found in game: a leap attack was configured with `자기 자신` as its target. The direction from
 * the caster to itself is the zero vector, so the skill returned immediately every time - no
 * error, no log, nothing happening. The configuration looked complete and was impossible.
 *
 * These tests pin the two halves of the fix: the skills that need a target say so, and the
 * selectors they offer can never include one that makes them a no-op.
 */
class SelectorCompatibilityTest {

    /** Skills whose whole behaviour is a direction or a flight path towards someone else. */
    private val mustHaveTarget = setOf(
        "leap_attack",
        "charge",
        "grapple",
        "blink",
        "projectile_volley",
        "homing_orb",
    )

    @Test
    fun `direction-based skills declare that they need a target`() {
        for (id in mustHaveTarget) {
            val skill = BuiltinSkills.all().firstOrNull { it.id == id }
            assertTrue(skill != null, "missing skill: $id")
            assertTrue(skill.requiresTarget, "$id aims at something and must declare requiresTarget")
        }
    }

    @Test
    fun `a target-requiring skill never offers self or location`() {
        for (skill in BuiltinSkills.all()) {
            if (!skill.requiresTarget) continue
            assertFalse(
                skill.usableSelectors.contains(TargetSelector.SELF),
                "${skill.id} offers 자기 자신, which would make it a silent no-op",
            )
            assertFalse(
                skill.usableSelectors.contains(TargetSelector.LOCATION),
                "${skill.id} offers 현재 위치, which gives it nothing to aim at",
            )
        }
    }

    @Test
    fun `the exact configuration that failed in game is now rejected`() {
        val leap = BuiltinSkills.all().first { it.id == "leap_attack" }
        assertTrue(leap.rejects(TargetSelector.SELF), "leap_attack must reject 자기 자신")
        assertTrue(leap.rejects(TargetSelector.LOCATION))
        assertFalse(leap.rejects(TargetSelector.TARGET))
        assertFalse(leap.rejects(TargetSelector.NEAREST_PLAYER))
    }

    @Test
    fun `every skill default selector is one it can actually use`() {
        // A default the skill rejects would hand every newly added copy a broken configuration.
        for (skill in BuiltinSkills.all()) {
            assertFalse(
                skill.rejects(skill.defaultSelector),
                "${skill.id} defaults to ${skill.defaultSelector.name}, which it rejects",
            )
            assertTrue(
                skill.usableSelectors.contains(skill.defaultSelector),
                "${skill.id} defaults to a selector missing from its own list",
            )
        }
    }

    @Test
    fun `area skills keep the full selector list`() {
        // The restriction has to be narrow. Ground slam and the rest are centred on the caster,
        // so removing 자기 자신 from them would break the common case to fix the rare one.
        for (id in listOf("ground_slam", "heal", "enrage", "shield", "visual", "explode")) {
            val skill = BuiltinSkills.all().first { it.id == id }
            assertFalse(skill.requiresTarget, "$id is caster-centred and must not require a target")
            assertTrue(skill.usableSelectors.contains(TargetSelector.SELF))
        }
    }
}
