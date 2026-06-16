package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.SolanaWallet
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.pow

object ProcessorAmountPlanner {

    private data class ConfirmedSellAmount(
        val requestedRaw: Long,
        val requestedUi: Double,
        val walletRaw: Long,
        val walletUi: Double,
        val decimals: Int,
    )

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
        ExecutionRootCauseTrace.authority("BUY", "BUY_PLAN_START", ts, "processor=$processor requestedSol=$requestedSol priorityFee=$priorityFeeSol jitoTip=$jitoTipLamports")
        if (!requestedSol.isFinite() || requestedSol <= 0.0) {
            ExecutionRootCauseTrace.authority("BUY", "BUY_PLAN_BLOCK_INVALID_REQUEST", ts, "processor=$processor requestedSol=$requestedSol")
            return null
        }
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
            ExecutionRootCauseTrace.authority("BUY", "BUY_PLAN_BLOCK_INSUFFICIENT_SOL", ts, "processor=$processor requested=$requestedSol wallet=$freshWalletSol reserve=$reserveSol spendable=$spendableSol")
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
        ExecutionRootCauseTrace.authority("BUY", "BUY_PLAN_OK", ts, "processor=$processor sol=$clampedSol lamports=$lamports wallet=$freshWalletSol reserve=$reserveSol requested=$requestedSol")
        try { ForensicLogger.lifecycle("BUY_PROCESSOR_AMOUNT_RECALCULATED", "processor=$processor mint=${ts.mint.take(10)} sol=$clampedSol lamports=$lamports wallet=$freshWalletSol reserve=$reserveSol requested=$requestedSol") } catch (_: Throwable) {}
        return BuyPlan(processor, clampedSol, lamports, freshWalletSol, reserveSol)
    }

    /**
     * V5.0.3801 — processor-bound sell authority extracted from Executor.
     *
     * This is still synchronous by design: a live sell route must refresh owner-filtered
     * wallet/token proof at the processor boundary before quote/build. The Executor
     * remains the orchestrator; this helper owns amount truth and formatting only.
     */
    fun planSell(
        ts: TokenState,
        wallet: SolanaWallet,
        processor: String,
        requestedUiQty: Double,
        exitReason: String = processor,
        fraction: Double? = null,
        sellTradeKey: String? = null,
        traderTag: String = "MEME",
    ): SellPlan? {
        val confirmed = resolveConfirmedSellAmountOrNull(
            ts = ts,
            wallet = wallet,
            requestedUiQty = requestedUiQty,
            fraction = fraction,
            sellTradeKey = sellTradeKey,
            traderTag = traderTag,
            reason = exitReason,
            processor = processor,
        ) ?: run {
            ExecutionRootCauseTrace.authority("SELL", "PROCESSOR_AMOUNT_RECALC_BLOCKED", ts, "processor=$processor exitReason=$exitReason requestedUi=$requestedUiQty reason=BALANCE_UNKNOWN")
            try { ForensicLogger.lifecycle("PROCESSOR_AMOUNT_RECALC_BLOCKED", "processor=$processor mint=${ts.mint.take(10)} reason=BALANCE_UNKNOWN") } catch (_: Throwable) {}
            return null
        }
        return planSellFromConfirmed(
            ts = ts,
            processor = processor,
            requestedRaw = confirmed.requestedRaw,
            requestedUi = confirmed.requestedUi,
            walletRaw = confirmed.walletRaw,
            walletUi = confirmed.walletUi,
            decimals = confirmed.decimals,
            sellTradeKey = sellTradeKey,
            traderTag = traderTag,
        )
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
        ExecutionRootCauseTrace.authority("SELL", "SELL_PLAN_OK", ts, "processor=$processor raw=$requestedRaw ui=$requestedUi walletRaw=$walletRaw walletUi=$walletUi decimals=$decimals")
        try { ForensicLogger.lifecycle("PROCESSOR_AMOUNT_RECALCULATED", "processor=$processor mint=${ts.mint.take(10)} raw=$requestedRaw ui=$requestedUi decimals=$decimals") } catch (_: Throwable) {}
        return SellPlan(processor, requestedRaw, requestedUi, walletRaw, walletUi, decimals)
    }

    private fun resolveConfirmedSellAmountOrNull(
        ts: TokenState,
        wallet: SolanaWallet,
        requestedUiQty: Double,
        fraction: Double? = null,
        sellTradeKey: String? = null,
        traderTag: String = "MEME",
        reason: String = "UNKNOWN",
        processor: String = "DIRECT",
    ): ConfirmedSellAmount? {
        if (!requestedUiQty.isFinite() || requestedUiQty <= 0.0) return null
        val accounts = try { wallet.getTokenAccountsWithDecimalsBounded() } catch (e: Exception) {
            ErrorLogger.warn("ProcessorAmountPlanner", "BALANCE_UNKNOWN ${ts.symbol}: token-account read failed: ${e.message}")
            null
        }

        // HostWalletTokenTracker / generic TX_PARSE are not sell amount authority.
        // RPC-empty/missing means BALANCE_UNKNOWN unless SellAmountAuthority has a
        // buy-tied owner-delta proof that is explicitly eligible for the exit reason.
        if (accounts == null || accounts.isEmpty() || accounts[ts.mint] == null) {
            val tracked = try { HostWalletTokenTracker.getEntry(ts.mint) } catch (_: Throwable) { null }
            if (tracked != null && tracked.source == HostWalletTokenTracker.PositionSource.TX_PARSE) {
                try { ForensicLogger.lifecycle("BALANCE_PROOF_REJECTED", "reason=GENERIC_TX_PARSE_NOT_OWNER_FILTERED mint=${ts.mint.take(10)} site=processor_amount_planner trackerStatus=${tracked.status.name}") } catch (_: Throwable) {}
            }
        }

        if (accounts == null || accounts.isEmpty()) {
            return recoverFromSellAmountAuthorityOrNull(ts, wallet, requestedUiQty, reason, processor)
                ?: run {
                    LiveTradeLogStore.log(
                        sellTradeKey ?: LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime),
                        ts.mint, ts.symbol, "SELL", LiveTradeLogStore.Phase.SELL_BALANCE_CHECK,
                        "RPC-EMPTY-MAP → BALANCE_UNKNOWN — sell broadcast blocked; caller tokenUnits/cache not authoritative AND HostWalletTokenTracker has no eligible entry",
                        traderTag = traderTag,
                    )
                    null
                }
        }

        val entry = accounts[ts.mint]
        if (entry == null) {
            return recoverFromSellAmountAuthorityOrNull(ts, wallet, requestedUiQty, reason, processor)
                ?: run {
                    LiveTradeLogStore.log(
                        sellTradeKey ?: LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime),
                        ts.mint, ts.symbol, "SELL", LiveTradeLogStore.Phase.SELL_BALANCE_CHECK,
                        "BALANCE_UNKNOWN — exact owner+mint token account missing; sell broadcast blocked (no tracker fallback eligible)",
                        traderTag = traderTag,
                    )
                    null
                }
        }

        val (walletUi, dec) = entry
        if (!walletUi.isFinite() || walletUi <= 0.0) return null
        val decimals = dec.coerceAtLeast(0)
        val scale = 10.0.pow(decimals.toDouble())
        val walletRaw = (walletUi * scale).toLong().coerceAtLeast(0L)
        if (walletRaw <= 0L) return null
        val requestedUi = when {
            fraction != null -> walletUi * fraction.coerceIn(0.0, 1.0)
            requestedUiQty > walletUi -> walletUi
            else -> requestedUiQty
        }
        val requestedRaw = (requestedUi * scale).toLong().coerceIn(1L, walletRaw)
        return ConfirmedSellAmount(requestedRaw, requestedRaw.toDouble() / scale, walletRaw, walletUi, decimals)
    }

    private fun recoverFromSellAmountAuthorityOrNull(
        ts: TokenState,
        wallet: SolanaWallet,
        requestedUiQty: Double,
        reason: String,
        processor: String,
    ): ConfirmedSellAmount? {
        val recovered = try { com.lifecyclebot.engine.sell.SellAmountAuthority.resolveForExit(ts.mint, wallet, reason) } catch (_: Throwable) { null }
        val c = recovered as? com.lifecyclebot.engine.sell.SellAmountAuthority.Resolution.Confirmed ?: return null
        val walletRawBig = if (c.rawAmount.signum() < 0) BigInteger.ZERO else c.rawAmount
        val walletRaw = if (walletRawBig > BigInteger.valueOf(Long.MAX_VALUE)) Long.MAX_VALUE else walletRawBig.toLong()
        val decimals = c.decimals.coerceAtLeast(0)
        val scale = 10.0.pow(decimals.toDouble())
        val requestedRawBig0 = BigDecimal(requestedUiQty.coerceAtLeast(0.0)).movePointRight(decimals).toBigInteger()
        val requestedRawBig = if (requestedRawBig0 < BigInteger.ONE) BigInteger.ONE else requestedRawBig0
        val requestedRaw = (if (requestedRawBig > walletRawBig) walletRawBig else requestedRawBig)
            .let { if (it > BigInteger.valueOf(Long.MAX_VALUE)) Long.MAX_VALUE else it.toLong() }
            .coerceIn(1L, walletRaw.coerceAtLeast(1L))
        val requestedUi = requestedRaw.toDouble() / scale
        val walletUi = walletRaw.toDouble() / scale
        ExecutionRootCauseTrace.authority("SELL", "PROCESSOR_AMOUNT_OWNER_DELTA_RECOVERED", ts, "processor=$processor reason=$reason requestedRaw=$requestedRaw walletRaw=$walletRaw decimals=$decimals")
        try { ForensicLogger.lifecycle("PROCESSOR_AMOUNT_OWNER_DELTA_RECOVERED", "processor=$processor mint=${ts.mint.take(10)} reason=$reason requestedRaw=$requestedRaw walletRaw=$walletRaw decimals=$decimals") } catch (_: Throwable) {}
        return ConfirmedSellAmount(requestedRaw, requestedUi, walletRaw, walletUi, decimals)
    }

    private fun Double.fmt6(): String = String.format(java.util.Locale.US, "%.6f", this)
}
