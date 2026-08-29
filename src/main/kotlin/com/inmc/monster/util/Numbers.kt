package com.inmc.monster.util

import java.math.BigDecimal
import java.math.RoundingMode

object Numbers {

    /** Chance values are stored as 0.01 .. 100.0 with two decimals (spec: 확률은 소수점). */
    const val MIN_CHANCE = 0.01
    const val MAX_CHANCE = 100.0

    fun clampChance(value: Double): Double =
        round2(value.coerceIn(MIN_CHANCE, MAX_CHANCE))

    fun round2(value: Double): Double =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()

    /** 12.34 -> "12.34", 50.0 -> "50" - keeps GUI lore tidy. */
    fun chance(value: Double): String {
        val rounded = round2(value)
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            BigDecimal.valueOf(rounded).stripTrailingZeros().toPlainString()
        }
    }

    fun money(value: Double): String =
        if (value == value.toLong().toDouble()) String.format("%,d", value.toLong())
        else String.format("%,.2f", value)
}
