package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.SolanaWallet

object ProcessorAmountPlanner {
    data class BuyPlan(
        val processor: String,
        val solAmount: Double,
        val lamports: Long,
        val walletSol: Double,
        val reserveSol: Double,
    )

    data class SellPlan(
        val processor: String,
        val rawAmount: Long,
        val uiAmount: Double,
        val walletRaw: Long,
        val walletUi: Double,
        val decimals: Int,
    )

    fun planBuy(
        ts: TokenState,
        wallet: SolanaWallet,
        processor: String,
        requestedSol: Double,
        priorityFeeSol: Double = 0.0,
        jitoTipLamports: Long = 0L,
        tradeKey: String? = null,
        traderTag: String = "MEME",
    ): BuyPlan? {
        if (!requestedSol.isFinite() || requestedSol <= 0.0) return null
        val freshWalletSol = try { wallet.getSolBalance() } catch (e: Throwable) {
            try { ForensicLogger.lifecycle("BUY_PROCESSOR_AMOUNT_BLOCKED", "processor=$processor mint=${ts.mint.take(10)} reason=WALLET_SOL_UNKNOWN err=${e.message?.take(60)}") } catch (_: Throwable) {}
            return null
        }
        if (!freshWalletSol.isFinite() || freshWalletSol <= 0.0) return null
        val senderTipSol = (jitoTipLamports.coerceAtLeast(0L).toDouble() / 1_000_000_000.0)
        val baseFeeReserveSol = 0.0030
        val reserveSol = (priorityFeeSol.coerceAtLeast(0.0) + senderTipSol + baseFeeReserveSol).coerceAtLeast(0.0030)
        val spendableSol = (freshWalletSol - reserveSol).coerceAtLeast(0.0)
        val clampedSol = requestedSol.coerceAtMost(spendableSol)
        if (clampedSol <= 0.0 || clampedSol < 0.000001) {
            try {
                LiveTradeLogStore.log(
                    tradeKey ?: LiveTradeLogStore.keyFor(ts.mint, System.currentTimeMillis()),
                    ts.mint, ts.symbol, "BUY", LiveTradeLogStore.Phase.BUY_FAILED,
                    "BUY_PROCESSOR_AMOUNT_BLOCKED processor=$processor requested=${requestedSol.fmt6()} wallet=${freshWalletSol.fmt6()} reserve=${reserveSol.fmt6()}",
                    solAmount = requestedSol,
                    traderTag = traderTag,
                )
            } catch (_: Throwable) {}
            try { ForensicLogger.lifecycle("BUY_PROCESSOR_AMOUNT_BLOCKED", "processor=$processor mint=${ts.mint.take(10)} requested=$requestedSol wallet=$freshWalletSol reserve=$reserveSol") } catch (_: Throwable) {}
            return null
        }
        val lamports = (clampedSol * 1_000_000_000.0).toLong().coerceAtLeast(1L)
        try {
            LiveTradeLogStore.log(
                tradeKey ?: LiveTradeLogStore.keyFor(ts.mint, System.currentTimeMillis()),
                ts.mint, ts.symbol, "BUY", LiveTradeLogStore.Phase.BUY_QUOTE_TRY,
                "BUY_PROCESSOR_AMOUNT_RECALCULATED processor=$processor sol=${clampedSol.fmt6()} lamports=$lamports wallet=${freshWalletSol.fmt6()} reserve=${reserveSol.fmt6()} requested=${requestedSol.fmt6()}",
                solAmount = clampedSol,
                traderTag = traderTag,
            )
        } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle("BUY_PROCESSOR_AMOUNT_RECALCULATED", "processor=$processor mint=${ts.mint.take(10)} sol=$clampedSol lamports=$lamports wallet=$freshWalletSol reserve=$reserveSol requested=$requestedSol") } catch (_: Throwable) {}
        return BuyPlan(processor, clampedSol, lamports, freshWalletSol, reserveSol)
    }

    fun planSellFromConfirmed(
        ts: TokenState,
        processor: String,
        requestedRaw: Long,
        requestedUi: Double,
        walletRaw: Long,
        walletUi: Double,
        decimals: Int,
        sellTradeKey: String? = null,
        traderTag: String = "MEME",
    ): SellPlan {
        try {
            LiveTradeLogStore.log(
                sellTradeKey ?: LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime),
                ts.mint, ts.symbol, "SELL", LiveTradeLogStore.Phase.SELL_BALANCE_CHECK,
                "PROCESSOR_AMOUNT_RECALCULATED processor=$processor raw=$requestedRaw ui=$requestedUi walletRaw=$walletRaw decimals=$decimals",
                tokenAmount = requestedUi,
                traderTag = traderTag,
            )
        } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle("PROCESSOR_AMOUNT_RECALCULATED", "processor=$processor mint=${ts.mint.take(10)} raw=$requestedRaw ui=$requestedUi decimals=$decimals") } catch (_: Throwable) {}
        return SellPlan(processor, requestedRaw, requestedUi, walletRaw, walletUi, decimals)
    }

    private fun Double.fmt6(): String = String.format(java.util.Locale.US, "%.6f", this)
}
