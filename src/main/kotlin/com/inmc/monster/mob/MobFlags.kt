package com.inmc.monster.mob

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.potion.PotionEffectType
import java.util.EnumSet

/**
 * Behaviour switches applied once, at spawn.
 *
 * Everything here maps onto a plain Bukkit setter. Nothing in this class needs a repeating
 * task, which is the point: a flag that had to be re-asserted every tick would cost more than
 * the behaviour is worth.
 */
class MobFlags(
    var baby: Boolean = false,
    var silent: Boolean = false,
    var glowing: Boolean = false,
    var invisible: Boolean = false,
    var aiEnabled: Boolean = true,
    var aware: Boolean = true,
    var canPickupItems: Boolean = false,
    var collidable: Boolean = true,
    var gravity: Boolean = true,
    /**
     * Vanilla's "wander off and vanish" rule. Left on by default on purpose - turning it off
     * for ordinary field mobs is how entity counts creep up until the server chokes.
     */
    var removeWhenFarAway: Boolean = true,
    var burnInSunlight: Boolean = true,
    /** Nameplate visible without looking directly at the mob. */
    var alwaysShowName: Boolean = true,
    var showNameplate: Boolean = true,
    var bossBar: Boolean = false,
    /** Only ever targets players, never villagers/golems/other mobs. */
    var targetPlayersOnly: Boolean = false,
    /** Seconds before the mob despawns on its own. 0 = never. */
    var lifespanSeconds: Int = 0,
) {

    fun copyOf(): MobFlags = MobFlags(
        baby, silent, glowing, invisible, aiEnabled, aware, canPickupItems, collidable, gravity,
        removeWhenFarAway, burnInSunlight, alwaysShowName, showNameplate, bossBar,
        targetPlayersOnly, lifespanSeconds,
    )

    fun save(section: ConfigurationSection, path: String) {
        val target = section.createSection(path)
        target.set("baby", baby)
        target.set("silent", silent)
        target.set("glowing", glowing)
        target.set("invisible", invisible)
        target.set("ai-enabled", aiEnabled)
        target.set("aware", aware)
        target.set("can-pickup-items", canPickupItems)
        target.set("collidable", collidable)
        target.set("gravity", gravity)
        target.set("remove-when-far-away", removeWhenFarAway)
        target.set("burn-in-sunlight", burnInSunlight)
        target.set("always-show-name", alwaysShowName)
        target.set("show-nameplate", showNameplate)
        target.set("boss-bar", bossBar)
        target.set("target-players-only", targetPlayersOnly)
        target.set("lifespan-seconds", lifespanSeconds)
    }

    companion object {
        fun load(section: ConfigurationSection?, path: String): MobFlags {
            val target = section?.getConfigurationSection(path) ?: return MobFlags()
            val base = MobFlags()
            return MobFlags(
                baby = target.getBoolean("baby", base.baby),
                silent = target.getBoolean("silent", base.silent),
                glowing = target.getBoolean("glowing", base.glowing),
                invisible = target.getBoolean("invisible", base.invisible),
                aiEnabled = target.getBoolean("ai-enabled", base.aiEnabled),
                aware = target.getBoolean("aware", base.aware),
                canPickupItems = target.getBoolean("can-pickup-items", base.canPickupItems),
                collidable = target.getBoolean("collidable", base.collidable),
                gravity = target.getBoolean("gravity", base.gravity),
                removeWhenFarAway = target.getBoolean("remove-when-far-away", base.removeWhenFarAway),
                burnInSunlight = target.getBoolean("burn-in-sunlight", base.burnInSunlight),
                alwaysShowName = target.getBoolean("always-show-name", base.alwaysShowName),
                showNameplate = target.getBoolean("show-nameplate", base.showNameplate),
                bossBar = target.getBoolean("boss-bar", base.bossBar),
                targetPlayersOnly = target.getBoolean("target-players-only", base.targetPlayersOnly),
                lifespanSeconds = target.getInt("lifespan-seconds", base.lifespanSeconds).coerceAtLeast(0),
            )
        }
    }
}

/**
 * What the mob shrugs off.
 *
 * Boss fights need this more than they need extra health. A boss with heavy knockback applied
 * to it walks off a cliff and dies to fall damage, ending the fight in a way nobody designed;
 * a boss immune to knockback and falling behaves the way the arena was built for.
 */
class Immunities(
    /** 0.0 = knockback applies in full, 1.0 = completely unmoved. */
    var knockbackResistance: Double = 0.0,
    var fire: Boolean = false,
    var fall: Boolean = false,
    var drowning: Boolean = false,
    var suffocation: Boolean = false,
    var explosion: Boolean = false,
    var projectile: Boolean = false,
    var magic: Boolean = false,
    var wither: Boolean = false,
    var lightning: Boolean = false,
    var voidDamage: Boolean = false,
    var potions: MutableSet<String> = linkedSetOf(),
    var damageCauses: MutableSet<DamageCause> = EnumSet.noneOf(DamageCause::class.java),
) {

    /** True when nothing is configured, so the damage listener can bail out immediately. */
    val isEmpty: Boolean
        get() = !fire && !fall && !drowning && !suffocation && !explosion && !projectile &&
            !magic && !wither && !lightning && !voidDamage &&
            potions.isEmpty() && damageCauses.isEmpty()

    // HOT_FLOOR and CAMPFIRE are deprecated in favour of a unified fire cause, but are still
    // what the server reports on this version, so both spellings have to be matched.
    @Suppress("DEPRECATION")
    fun blocks(cause: DamageCause): Boolean {
        if (damageCauses.contains(cause)) return true
        return when (cause) {
            DamageCause.FIRE, DamageCause.FIRE_TICK, DamageCause.LAVA, DamageCause.HOT_FLOOR,
            DamageCause.CAMPFIRE,
            -> fire

            DamageCause.FALL -> fall
            DamageCause.DROWNING -> drowning
            DamageCause.SUFFOCATION, DamageCause.CRAMMING -> suffocation
            DamageCause.BLOCK_EXPLOSION, DamageCause.ENTITY_EXPLOSION -> explosion
            DamageCause.PROJECTILE -> projectile
            DamageCause.MAGIC -> magic
            DamageCause.WITHER -> wither
            DamageCause.LIGHTNING -> lightning
            DamageCause.VOID -> voidDamage
            else -> false
        }
    }

    fun blocksPotion(type: PotionEffectType): Boolean {
        if (potions.isEmpty()) return false
        val name = type.key.value().uppercase()
        return potions.any { it.uppercase() == name }
    }

    fun copyOf(): Immunities = Immunities(
        knockbackResistance, fire, fall, drowning, suffocation, explosion, projectile,
        magic, wither, lightning, voidDamage,
        LinkedHashSet(potions),
        // EnumSet.copyOf throws on an empty source collection, so build and fill instead.
        EnumSet.noneOf(DamageCause::class.java).also { it.addAll(damageCauses) },
    )

    fun save(section: ConfigurationSection, path: String) {
        val target = section.createSection(path)
        target.set("knockback-resistance", knockbackResistance)
        target.set("fire", fire)
        target.set("fall", fall)
        target.set("drowning", drowning)
        target.set("suffocation", suffocation)
        target.set("explosion", explosion)
        target.set("projectile", projectile)
        target.set("magic", magic)
        target.set("wither", wither)
        target.set("lightning", lightning)
        target.set("void", voidDamage)
        target.set("potions", potions.toList())
        target.set("damage-causes", damageCauses.map { it.name })
    }

    companion object {

        /** Toggles offered in the GUI, paired with their Korean label. */
        val SIMPLE_LABELS: List<Pair<String, String>> = listOf(
            "fire" to "화염",
            "fall" to "낙하",
            "drowning" to "익사",
            "suffocation" to "질식",
            "explosion" to "폭발",
            "projectile" to "투사체",
            "magic" to "마법",
            "wither" to "위더",
            "lightning" to "번개",
            "void" to "무의 공간",
        )

        fun load(section: ConfigurationSection?, path: String): Immunities {
            val target = section?.getConfigurationSection(path) ?: return Immunities()
            val causes = EnumSet.noneOf(DamageCause::class.java)
            for (name in target.getStringList("damage-causes")) {
                runCatching { DamageCause.valueOf(name.trim().uppercase()) }.getOrNull()?.let { causes.add(it) }
            }
            return Immunities(
                knockbackResistance = target.getDouble("knockback-resistance", 0.0).coerceIn(0.0, 1.0),
                fire = target.getBoolean("fire", false),
                fall = target.getBoolean("fall", false),
                drowning = target.getBoolean("drowning", false),
                suffocation = target.getBoolean("suffocation", false),
                explosion = target.getBoolean("explosion", false),
                projectile = target.getBoolean("projectile", false),
                magic = target.getBoolean("magic", false),
                wither = target.getBoolean("wither", false),
                lightning = target.getBoolean("lightning", false),
                voidDamage = target.getBoolean("void", false),
                potions = target.getStringList("potions").map { it.uppercase() }.toCollection(linkedSetOf()),
                damageCauses = causes,
            )
        }
    }
}
