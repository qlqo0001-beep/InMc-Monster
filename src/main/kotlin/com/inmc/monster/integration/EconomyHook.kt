package com.inmc.monster.integration

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import java.util.logging.Logger

/**
 * Vault economy, optional.
 *
 * The Vault classes are compile-only, so this class must never touch [Economy] unless
 * [isEnabled] is true - every public method guards on it. With Vault absent, a mob's money
 * reward is skipped and everything else about the kill still works.
 */
class EconomyHook(private val logger: Logger) {

    private var economy: Economy? = null

    val isEnabled: Boolean get() = economy != null

    fun setup() {
        economy = null
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            logger.info("Vault 미설치 - 재화 지급이 비활성화됩니다")
            return
        }
        try {
            val provider = Bukkit.getServicesManager().getRegistration(Economy::class.java)
            if (provider == null) {
                logger.warning("Vault는 있지만 경제 플러그인이 등록되지 않았습니다 - 재화 지급 비활성화")
                return
            }
            economy = provider.provider
            logger.info("Vault 연동 활성화 (${provider.provider.name})")
        } catch (t: Throwable) {
            logger.warning("Vault 연동 실패: ${t.message}")
        }
    }

    fun balance(player: OfflinePlayer): Double =
        economy?.let { runCatching { it.getBalance(player) }.getOrDefault(0.0) } ?: 0.0

    fun has(player: OfflinePlayer, amount: Double): Boolean {
        if (amount <= 0.0) return true
        val eco = economy ?: return false
        return runCatching { eco.has(player, amount) }.getOrDefault(false)
    }

    fun withdraw(player: OfflinePlayer, amount: Double): Boolean {
        if (amount <= 0.0) return true
        val eco = economy ?: return false
        return runCatching { eco.withdrawPlayer(player, amount).transactionSuccess() }
            .getOrDefault(false)
    }

    /** Kill rewards pay out through here. Silently a no-op when Vault is absent. */
    fun deposit(player: OfflinePlayer, amount: Double): Boolean {
        if (amount <= 0.0) return true
        val eco = economy ?: return false
        return runCatching { eco.depositPlayer(player, amount).transactionSuccess() }
            .getOrDefault(false)
    }

    /** Uses the economy plugin's own formatting when available so amounts read consistently. */
    fun format(amount: Double): String =
        economy?.let { runCatching { it.format(amount) }.getOrNull() }
            ?: com.inmc.monster.util.Numbers.money(amount)
}
