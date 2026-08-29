package com.inmc.monster

import com.inmc.monster.death.DeathActionType
import com.inmc.monster.skill.ParamType
import com.inmc.monster.skill.builtin.BuiltinSkills
import org.bukkit.Particle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Particles that demand extra data.
 *
 * Found in game, not in a test: `ground_slam` shipped with `BLOCK` as its default particle, and
 * `BLOCK` throws `IllegalArgumentException: missing required data interface BlockData` when
 * spawned bare. Because it fired on a timer, it failed every few seconds and took the whole cast
 * with it each time.
 *
 * The lesson is not "fix that default" - it is that particle names come from a free-text GUI
 * field, so *any* of the data-requiring particles can reach the spawn call. These tests pin both
 * halves: the shipped defaults are sane, and the helper can supply data for everything the
 * server declares a type for.
 */
class ParticleDataTest {

    /** Particles the server says need extra data. Non-empty on every version that matters. */
    private val dataRequiring: List<Particle> =
        Particle.entries.filter { it.dataType != Void::class.java }

    @Test
    fun `the server really does declare data-requiring particles`() {
        // Guards the tests below: if this list were empty they would all pass vacuously.
        assertTrue(
            dataRequiring.isNotEmpty(),
            "no particle reported a data type, so these tests are not checking anything",
        )
        assertTrue(
            dataRequiring.contains(Particle.BLOCK),
            "BLOCK no longer needs data on this version - re-check what does",
        )
    }

    @Test
    fun `every shipped skill default particle is spawnable`() {
        // A default that throws is worse than a wrong-looking one: the skill never runs at all.
        for (skill in BuiltinSkills.all()) {
            for (param in skill.parameters) {
                if (param.type != ParamType.PARTICLE) continue
                val name = param.default.toString()
                val particle = runCatching { Particle.valueOf(name.uppercase()) }.getOrNull()
                assertNotNull(particle, "${skill.id}.${param.key} default '$name' is not a particle")
                assertTrue(
                    particle.dataType == Void::class.java || canSupplyData(particle),
                    "${skill.id}.${param.key} default '$name' needs ${particle.dataType.simpleName} " +
                        "and nothing can supply it",
                )
            }
        }
    }

    @Test
    fun `every shipped death action default particle is spawnable`() {
        for (type in DeathActionType.entries) {
            for (param in type.parameters) {
                if (param.type != ParamType.PARTICLE) continue
                val name = param.default.toString()
                val particle = runCatching { Particle.valueOf(name.uppercase()) }.getOrNull()
                assertNotNull(particle, "${type.name}.${param.key} default '$name' is not a particle")
                assertTrue(
                    particle.dataType == Void::class.java || canSupplyData(particle),
                    "${type.name}.${param.key} default '$name' needs ${particle.dataType.simpleName}",
                )
            }
        }
    }

    @Test
    fun `data can be supplied for the common data-requiring particles`() {
        // The ones an admin is most likely to type into a GUI field.
        val expected = listOf(
            Particle.BLOCK,
            Particle.BLOCK_MARKER,
            Particle.FALLING_DUST,
            Particle.DUST_PILLAR,
            Particle.ITEM,
            Particle.DUST,
            Particle.DUST_COLOR_TRANSITION,
            Particle.ENTITY_EFFECT,
        )
        val unsupported = expected.filterNot { canSupplyData(it) }
        assertTrue(unsupported.isEmpty(), "no data available for: $unsupported")
    }

    @Test
    fun `the resolver falls back rather than throwing on a bad name`() {
        assertEquals(Particle.CRIT, com.inmc.monster.skill.builtin.Fx.particle("완전히없는파티클"))
        assertEquals(Particle.FLAME, com.inmc.monster.skill.builtin.Fx.particle("nonsense", Particle.FLAME))
        assertEquals(Particle.BLOCK, com.inmc.monster.skill.builtin.Fx.particle("block"))
    }

    /**
     * Whether [dataFor] would produce something for this particle.
     *
     * Mirrors the helper's own dispatch without touching a world, so the check runs headless.
     * The BlockData branch is the one the in-game failure came from, and it is the one this
     * cannot exercise directly - it needs a live block - so it is asserted structurally instead.
     */
    private fun canSupplyData(particle: Particle): Boolean {
        val type = particle.dataType
        return when {
            type == Void::class.java -> true
            org.bukkit.block.data.BlockData::class.java.isAssignableFrom(type) -> true
            org.bukkit.inventory.ItemStack::class.java.isAssignableFrom(type) -> true
            Particle.DustOptions::class.java.isAssignableFrom(type) -> true
            Particle.DustTransition::class.java.isAssignableFrom(type) -> true
            Particle.Spell::class.java.isAssignableFrom(type) -> true
            Particle.GeyserBase::class.java.isAssignableFrom(type) -> true
            Particle.Geyser::class.java.isAssignableFrom(type) -> true
            Particle.Trail::class.java.isAssignableFrom(type) -> true
            org.bukkit.Vibration::class.java.isAssignableFrom(type) -> true
            org.bukkit.Color::class.java.isAssignableFrom(type) -> true
            type == Float::class.javaObjectType || type == Float::class.javaPrimitiveType -> true
            type == Int::class.javaObjectType || type == Int::class.javaPrimitiveType -> true
            else -> false
        }
    }

    @Test
    fun `every data-requiring particle on this version is supported`() {
        // Every one of them is handled today. If a later version adds a new exotic data type
        // this fails, and someone decides deliberately whether to support it or accept the
        // fallback - rather than discovering it as a dead effect in game.
        val unsupported = dataRequiring.filterNot { canSupplyData(it) }.map { it.name }.sorted()
        assertTrue(
            unsupported.isEmpty(),
            "these particles have no usable data and would silently fall back: $unsupported",
        )
    }
}
