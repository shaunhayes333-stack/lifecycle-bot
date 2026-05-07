package com.lifecyclebot.engine.sell

import java.math.BigInteger

/**
 * V5.9.495z39 — Canonical sell intent.
 *
 * Operator real-money safety spec item 1: "The executor must never
 * pass ambiguous percent/amount/slippage values into PumpPortal/
 * Jupiter/Raydium." This data class is the single source of truth
 * for every live sell. Adapters MUST read from these fields and may
 * NOT mix them.
 */
data class SellIntent(
    val mint: String,
    val symbol: String,
    val reason: ExitReason,

    /** Requested fraction in basis points. 2500 = 25%. Always in [1, 10000]. */
    val requestedFractionBps: Int,

    /** Actual on-chain wallet raw token balance at sell-time (post-buy). */
    val confirmedWalletRaw: BigInteger,

    /** floor(confirmedWalletRaw * requestedFractionBps / 10000).
     *  Always >0 and always <=confirmedWalletRaw. Verified at construction. */
    val requestedSellRaw: BigInteger,

    /** UI-decimal token quantity for log readability — derived, NOT
     *  authoritative. Adapters that take UI quantity must read this;
     *  adapters that take raw token amount must read requestedSellRaw. */
    val requestedSellUi: Double,

    /** Slippage in bps. Must respect SellSlippageProfile cap for reason. */
    val slippageBps: Int,

    /** True only for RUG_DRAIN / HARD_STOP / MANUAL_FULL_EXIT. */
    val emergencyDrain: Boolean,

    /** Cost basis from the buy tx. Used for proportional PnL. */
    val entrySolSpent: Double,
    val entryTokenRaw: BigInteger,
) {
    init {
        require(mint.isNotBlank())                  { "mint blank" }
        require(symbol.isNotBlank())                { "symbol blank" }
        require(requestedFractionBps in 1..10_000)  { "requestedFractionBps out of range: $requestedFractionBps" }
        require(confirmedWalletRaw.signum() >= 0)   { "confirmedWalletRaw negative" }
        require(requestedSellRaw.signum() > 0)      { "requestedSellRaw must be > 0" }
        require(requestedSellRaw <= confirmedWalletRaw) {
            "requestedSellRaw ($requestedSellRaw) > confirmedWalletRaw ($confirmedWalletRaw)"
        }
        require(slippageBps in 0..9999)             { "slippageBps out of range: $slippageBps" }
        // Drain-only reasons demand emergencyDrain=true.
        if (reason == ExitReason.RUG_DRAIN ||
            reason == ExitReason.HARD_STOP ||
            reason == ExitReason.MANUAL_FULL_EXIT) {
            require(emergencyDrain) { "$reason requires emergencyDrain=true" }
        }
        // Profit-lock and capital-recovery are NEVER full-balance drains.
        if (reason == ExitReason.PROFIT_LOCK ||
            reason == ExitReason.CAPITAL_RECOVERY ||
            reason == ExitReason.PARTIAL_TAKE_PROFIT) {
            require(!emergencyDrain) { "$reason cannot be emergencyDrain=true" }
            require(requestedFractionBps < 10_000) {
                "$reason cannot use 100% fraction; use HARD_STOP/MANUAL_FULL_EXIT"
            }
        }
    }

    companion object {
        /** Build a SellIntent with strict integer math.
         *  requestedSellRaw = floor(confirmedWalletRaw * fractionBps / 10000). */
        fun build(
            mint: String,
            symbol: String,
            reason: ExitReason,
            requestedFractionBps: Int,
            confirmedWalletRaw: BigInteger,
            decimals: Int,
            slippageBps: Int,
            emergencyDrain: Boolean,
            entrySolSpent: Double,
            entryTokenRaw: BigInteger,
        ): SellIntent {
            val rawSell = confirmedWalletRaw
                .multiply(BigInteger.valueOf(requestedFractionBps.toLong()))
                .divide(BigInteger.valueOf(10_000L))
            val ui = rawSell.toBigDecimal()
                .movePointLeft(decimals)
                .toDouble()
            return SellIntent(
                mint = mint,
                symbol = symbol,
                reason = reason,
                requestedFractionBps = requestedFractionBps,
                confirmedWalletRaw = confirmedWalletRaw,
                requestedSellRaw = rawSell,
                requestedSellUi = ui,
                slippageBps = slippageBps,
                emergencyDrain = emergencyDrain,
                entrySolSpent = entrySolSpent,
                entryTokenRaw = entryTokenRaw,
            )
        }
    }
}

/**
 * Operator spec item 3: separate exit semantics. Only RUG_DRAIN /
 * HARD_STOP / MANUAL_FULL_EXIT may use a full-balance drain. Profit
 * lock / capital recovery / partial TP must respect requestedFractionBps.
 */
enum class ExitReason {
    PARTIAL_TAKE_PROFIT,
    CAPITAL_RECOVERY,
    PROFIT_LOCK,
    STOP_LOSS,
    HARD_STOP,
    RUG_DRAIN,
    MANUAL_FULL_EXIT,
    UNKNOWN,
}
