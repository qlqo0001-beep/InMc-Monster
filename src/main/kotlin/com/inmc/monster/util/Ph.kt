package com.inmc.monster.util

import kr.inmc.core.util.Numbers
import kr.inmc.core.util.TokenBag
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * Placeholder bag for a single message render.
 *
 * Tokens are spelled in Korean because that is what an admin types into the GUI, with English
 * aliases resolving to the same value so either spelling works in a config file.
 */
class Ph : TokenBag<Ph>() {

    override val aliases: Map<String, List<String>> get() = ALIASES



    fun player(name: String): Ph = put(PLAYER, name)

    fun player(player: Player): Ph = put(PLAYER, player.name)

    fun mob(displayName: String): Ph = put(MOB, displayName)

    fun mobId(id: String): Ph = put(MOB_ID, id)

    fun skill(name: String): Ph = put(SKILL, name)

    fun affix(name: String): Ph = put(AFFIX, name)

    fun item(name: String): Ph = put(ITEM, name)

    fun level(value: Int): Ph = put(LEVEL, value.toString())

    fun health(value: Double): Ph = put(HEALTH, Numbers.chance(value))

    fun maxHealth(value: Double): Ph = put(MAX_HEALTH, Numbers.chance(value))

    fun phase(name: String): Ph = put(PHASE, name)

    fun damage(value: Double): Ph = put(DAMAGE, Numbers.chance(value))

    fun amount(value: Int): Ph = put(AMOUNT, value.toString())

    fun count(value: Int): Ph = put(COUNT, value.toString())

    fun chance(value: Double): Ph = put(CHANCE, Numbers.chance(value))

    fun time(text: String): Ph = put(TIME, text)

    fun distance(blocks: Int): Ph = put(DISTANCE, blocks.toString())

    fun money(amount: String): Ph = put(MONEY, amount)

    fun location(location: Location): Ph =
        location(location.world?.name ?: "?", location.blockX, location.blockY, location.blockZ)

    fun location(world: String, x: Int, y: Int?, z: Int): Ph = apply {
        put(WORLD, world)
        put(X, x.toString())
        put(Y, y?.toString() ?: "?")
        put(Z, z.toString())
        put(LOCATION, if (y == null) "$world $x, ?, $z" else "$world $x, $y, $z")
    }

    fun world(name: String): Ph = put(WORLD, name)

    fun copy(): Ph = copyValuesInto(Ph())



    companion object {
        const val PLAYER = "player"
        const val MOB = "mob"
        const val MOB_ID = "mobId"
        const val SKILL = "skill"
        const val AFFIX = "affix"
        const val ITEM = "item"
        const val LEVEL = "level"
        const val HEALTH = "health"
        const val MAX_HEALTH = "maxHealth"
        const val PHASE = "phase"
        const val DAMAGE = "damage"
        const val AMOUNT = "amount"
        const val COUNT = "count"
        const val CHANCE = "chance"
        const val TIME = "time"
        const val DISTANCE = "distance"
        const val MONEY = "money"
        const val LOCATION = "location"
        const val WORLD = "world"
        const val X = "x"
        const val Y = "y"
        const val Z = "z"

        private val ALIASES: Map<String, List<String>> = mapOf(
            PLAYER to listOf("{플레이어네임}", "{플레이어}", "{player}"),
            MOB to listOf("{몬스터}", "{몹이름}", "{몹}", "{mob}"),
            MOB_ID to listOf("{몹아이디}", "{mob_id}"),
            SKILL to listOf("{스킬}", "{skill}"),
            AFFIX to listOf("{수식어}", "{특성}", "{affix}"),
            ITEM to listOf("{아이템}", "{item}"),
            LEVEL to listOf("{레벨}", "{level}"),
            HEALTH to listOf("{체력}", "{health}"),
            MAX_HEALTH to listOf("{최대체력}", "{max_health}"),
            PHASE to listOf("{페이즈}", "{phase}"),
            DAMAGE to listOf("{데미지}", "{damage}"),
            AMOUNT to listOf("{수량}", "{amount}"),
            COUNT to listOf("{개수}", "{count}"),
            CHANCE to listOf("{확률}", "{chance}"),
            TIME to listOf("{시간}", "{time}"),
            DISTANCE to listOf("{거리}", "{distance}"),
            MONEY to listOf("{돈}", "{money}"),
            LOCATION to listOf("{좌표}", "{location}"),
            WORLD to listOf("{월드}", "{world}"),
            X to listOf("{x}"),
            Y to listOf("{y}"),
            Z to listOf("{z}"),
        )

        fun of(): Ph = Ph()
    }
}
