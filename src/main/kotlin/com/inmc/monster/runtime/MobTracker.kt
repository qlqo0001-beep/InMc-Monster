package com.inmc.monster.runtime

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The persistent-data keys stamped onto a custom mob at spawn.
 *
 * Written once and never read on a hot path. Their real job is to survive us: after a restart
 * or a crash the runtime map is gone but the entities are not, and these tags are the only way
 * to recognise leftovers and remove them.
 */
class MobKeys(plugin: Plugin) {
    val id: NamespacedKey = NamespacedKey(plugin, "mob_id")
    val level: NamespacedKey = NamespacedKey(plugin, "mob_level")
    val affixes: NamespacedKey = NamespacedKey(plugin, "mob_affixes")
    val tag: NamespacedKey = NamespacedKey(plugin, "mob_tag")
    val generation: NamespacedKey = NamespacedKey(plugin, "mob_generation")

    /** True when this entity was spawned by us, whether or not we still track it. */
    fun isTagged(entity: Entity): Boolean =
        entity.persistentDataContainer.has(id, PersistentDataType.STRING)

    fun readId(entity: Entity): String? =
        entity.persistentDataContainer.get(id, PersistentDataType.STRING)

    /** A mob carrying only affixes stores an empty id, so vanilla leftovers stay recognisable. */
    fun isAffixedVanilla(entity: Entity): Boolean = readId(entity)?.isEmpty() == true

    fun stamp(entity: Entity, mobId: String, mobLevel: Int, affixIds: List<String>, ownerTag: String?, gen: Int) {
        val container = entity.persistentDataContainer
        container.set(id, PersistentDataType.STRING, mobId)
        container.set(level, PersistentDataType.INTEGER, mobLevel)
        container.set(generation, PersistentDataType.INTEGER, gen)
        if (affixIds.isNotEmpty()) {
            container.set(affixes, PersistentDataType.STRING, affixIds.joinToString(","))
        }
        if (!ownerTag.isNullOrBlank()) container.set(tag, PersistentDataType.STRING, ownerTag)
    }

    /** Strips our tags, turning an affixed vanilla mob back into an ordinary one. */
    fun clear(entity: Entity) {
        val container = entity.persistentDataContainer
        container.remove(id)
        container.remove(level)
        container.remove(affixes)
        container.remove(tag)
        container.remove(generation)
    }
}

/**
 * Index of every custom mob currently alive.
 *
 * Three lookups have to be fast because they run on hot paths: by entity id (every damage
 * event), by world (every spawn attempt), and by chunk (every spawn attempt). They are kept as
 * separate maps and updated together on register/unregister rather than derived on demand.
 */
class MobTracker(val keys: MobKeys) {

    private val byId = ConcurrentHashMap<UUID, ActiveMob>()
    private val perWorld = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val perChunk = ConcurrentHashMap<Long, MutableSet<UUID>>()
    private val perTag = ConcurrentHashMap<String, MutableSet<UUID>>()

    /** Model mobs are counted separately: one blueprint is many display entities. */
    @Volatile
    var modelledCount: Int = 0
        private set

    val size: Int get() = byId.size

    fun all(): Collection<ActiveMob> = byId.values

    operator fun get(id: UUID): ActiveMob? = byId[id]

    fun of(entity: Entity): ActiveMob? = byId[entity.uniqueId]

    /**
     * Fast rejection for event handlers.
     *
     * Every damage, death and target event on the server passes through here first, so it must
     * stay a single hash lookup and nothing more.
     */
    fun isCustom(entity: Entity): Boolean = byId.containsKey(entity.uniqueId)

    fun countIn(world: World): Int = perWorld[world.name]?.size ?: 0

    fun countIn(chunk: Chunk): Int = perChunk[chunkKey(chunk.world.name, chunk.x, chunk.z)]?.size ?: 0

    fun countAt(location: Location): Int {
        val world = location.world ?: return 0
        return countIn(world.getChunkAt(location))
    }

    fun countNear(location: Location, radius: Double): Int {
        val world = location.world ?: return 0
        val ids = perWorld[world.name] ?: return 0
        if (ids.isEmpty()) return 0
        val radiusSq = radius * radius
        var count = 0
        for (id in ids) {
            val mob = byId[id] ?: continue
            if (!mob.entity.isValid) continue
            if (mob.entity.location.distanceSquared(location) <= radiusSq) count++
        }
        return count
    }

    fun withTag(tag: String): List<ActiveMob> =
        perTag[tag.lowercase()]?.mapNotNull { byId[it] } ?: emptyList()

    fun countWithTag(tag: String): Int = perTag[tag.lowercase()]?.size ?: 0

    fun byDefinition(mobId: String): List<ActiveMob> =
        byId.values.filter { it.definition?.id.equals(mobId, ignoreCase = true) }

    fun inWorld(world: World): List<ActiveMob> =
        perWorld[world.name]?.mapNotNull { byId[it] } ?: emptyList()

    /**
     * Files a mob under its world, its spawn chunk and its tag.
     *
     * The chunk bucket is the chunk it *spawned* in and stays that way even if the mob walks
     * away. That is the useful meaning for the budget it feeds - the per-chunk cap exists to stop
     * spawns piling up in one spot, not to track where mobs currently stand.
     */
    fun register(mob: ActiveMob) {
        byId[mob.uuid] = mob
        val location = mob.entity.location
        val world = location.world?.name
        if (world != null) {
            val key = chunkKey(world, location.blockX shr 4, location.blockZ shr 4)
            mob.indexWorld = world
            mob.indexChunkKey = key
            perWorld.computeIfAbsent(world) { ConcurrentHashMap.newKeySet() }.add(mob.uuid)
            perChunk.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(mob.uuid)
        }
        mob.tag?.lowercase()?.let { tag ->
            mob.indexTag = tag
            perTag.computeIfAbsent(tag) { ConcurrentHashMap.newKeySet() }.add(mob.uuid)
        }
        if (mob.modelled) modelledCount++
    }

    /**
     * Removes a mob from every index it was filed under.
     *
     * Goes straight to the recorded buckets rather than scanning. Scanning would be O(number of
     * chunks that have ever held a mob), which only grows, while despawns happen constantly -
     * so the cost of removing one mob would climb for the whole life of the server.
     */
    fun unregister(id: UUID): ActiveMob? {
        val mob = byId.remove(id) ?: return null

        mob.indexWorld?.let { world ->
            perWorld[world]?.let { bucket ->
                bucket.remove(id)
                if (bucket.isEmpty()) perWorld.remove(world, bucket)
            }
        }
        perChunk[mob.indexChunkKey]?.let { bucket ->
            bucket.remove(id)
            // Empty buckets are dropped, otherwise the map keeps one entry per chunk that ever
            // held a mob and never shrinks.
            if (bucket.isEmpty()) perChunk.remove(mob.indexChunkKey, bucket)
        }
        mob.indexTag?.let { tag ->
            perTag[tag]?.let { bucket ->
                bucket.remove(id)
                if (bucket.isEmpty()) perTag.remove(tag, bucket)
            }
        }

        if (mob.modelled && modelledCount > 0) modelledCount--
        return mob
    }

    /** Drops index entries whose entity is gone. Cheap enough to run once a second. */
    fun purgeDead(): Int {
        val gone = byId.values.filter { !it.entity.isValid || it.entity.isDead }
        gone.forEach { unregister(it.uuid) }
        if (byId.isEmpty()) {
            perWorld.clear()
            perChunk.clear()
            perTag.clear()
            modelledCount = 0
        }
        return gone.size
    }

    fun clear() {
        byId.clear()
        perWorld.clear()
        perChunk.clear()
        perTag.clear()
        modelledCount = 0
    }

    /** Snapshot of live entities, for bulk removal. */
    fun entities(): List<LivingEntity> = byId.values.map { it.entity }

    private fun chunkKey(world: String, chunkX: Int, chunkZ: Int): Long {
        // World name is folded into the high bits so two worlds cannot collide on the same
        // chunk coordinates - which they otherwise always would at spawn.
        val coords = (chunkX.toLong() shl 32) xor (chunkZ.toLong() and 0xFFFFFFFFL)
        return coords xor (world.hashCode().toLong() shl 16)
    }
}
