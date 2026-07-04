package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.367 — INSIDER COPY-TRADE DISPATCHER
 *
 * User feedback (Feb 2026):
 *   "the insider tracker is great but its hidden and we aren't using it
 *    to copy trade etc in the memetrader or the markets or aate alts.
 *    is it wired to fire buy and sell signals?"
 *
 * Audit confirmed: InsiderTrackerAI + InsiderWalletTracker were both running
 * and emitting signals, but the callback paths in BotService only LOGGED
 * and NOTIFIED — no signal ever reached the watchlist or any trader's
 * execution path. The TokenMergeQueue had a `WHALE_COPY` slot reserved
 * (conf=50, fast-track) since V4 but nothing ever enqueued with it.
 *
 * This object is the single entry point both insider sources call.
 * Responsibilities:
 *
 *   1. ACCUMULATION / BUY / NEW_POSITION on a Solana mint
 *      → enqueue to TokenMergeQueue with scanner="WHALE_COPY" so the
 *        existing memetrader scanner pipeline (V3 + FDG + sizing) picks
 *        it up. Conf scales with the wallet's risk level.
 *
 *   2. DISTRIBUTION / SELL on an ALPHA wallet for a token we currently
 *      hold open
 *      → close the matching position across every trader that exposes
 *        a getOpenPositions / closePositionManual API:
 *          - CryptoAltTrader   (BTC/ETH/SOL/etc.)
 *          - TokenizedStockTrader
 *          - CommoditiesTrader
 *          - ForexTrader
 *          - MetalsTrader
 *
 *   3. Emit telemetry counters for the main UI insider tile.
 *
 * V5.0.6096 update:
 *   - Forced copy-BUY on CryptoAltTrader is now in scope via
 *     CryptoAltTrader.copyBuyFromInsiderSignal(), so INSIDER_SHARK is not
 *     just visible/advisory — it opens Crypto Universe paper trades through
 *     the normal alt sizing/exposure/learning path.
 */
object InsiderCopyEngine {

    private const val TAG = "InsiderCopy"

    // Telemetry counters for the main UI tile
    val totalCopyBuys: AtomicInteger = AtomicInteger(0)
    val totalCopyExits: AtomicInteger = AtomicInteger(0)
    val lastSignalAtMs: AtomicLong = AtomicLong(0L)

    data class SharkSignalRow(
        val mint: String,
        val symbol: String,
        val action: String,
        val confidence: Int,
        val walletLabel: String,
        val detectedAtMs: Long = System.currentTimeMillis(),
        val source: String = "INSIDER_SHARK",
    )

    private val activeSignals = ConcurrentHashMap<String, SharkSignalRow>()
    private val exitSignals = ConcurrentHashMap<String, SharkSignalRow>()

    fun activeSignalRows(limit: Int = 8): List<SharkSignalRow> = activeSignals.values
        .sortedByDescending { it.detectedAtMs }
        .take(limit)

    fun getUiSummary(): String {
        val recent = activeSignalRows(3).joinToString(" | ") { row ->
            "${row.symbol}:${row.action}:${row.confidence}%:${row.walletLabel.take(14)}"
        }
        return "signals=${activeSignals.size} buys=${totalCopyBuys.get()} exits=${totalCopyExits.get()} ${if (recent.isBlank()) "recent=--" else "recent=$recent"}"
    }

    fun exitSignalForMint(mint: String, symbol: String = ""): SharkSignalRow? {
        val now = System.currentTimeMillis()
        exitSignals.entries.removeIf { now - it.value.detectedAtMs > 45 * 60 * 1000L }
        return exitSignals[mint] ?: exitSignals.values.firstOrNull { symbol.isNotBlank() && it.symbol.equals(symbol, true) }
    }

    /**
     * Entrypoint for InsiderWalletTracker.InsiderSignal events.
     * action ∈ {"BUY", "SELL", "NEW_POSITION"}.
     */
    fun onWalletTrackerSignal(signal: com.lifecyclebot.perps.InsiderWalletTracker.InsiderSignal) {
        lastSignalAtMs.set(System.currentTimeMillis())
        val isAlpha = signal.category ==
            com.lifecyclebot.perps.InsiderWalletTracker.InsiderCategory.SMART_MONEY
        when (signal.action.uppercase()) {
            "BUY", "NEW_POSITION", "ACCUMULATION" -> {
                copyBuyMemeMint(
                    mint        = signal.tokenMint,
                    symbol      = signal.tokenSymbol,
                    confidence  = signal.confidence,
                    walletLabel = signal.walletLabel,
                )
                copyBuyCryptoAlt6096(signal.tokenSymbol, signal.confidence, signal.walletLabel)
            }
            "SELL", "DISTRIBUTION" -> {
                if (isAlpha || signal.confidence >= 70) {
                    copyExitAcrossTraders(
                        mintOpt     = signal.tokenMint,
                        symbol      = signal.tokenSymbol,
                        confidence  = signal.confidence,
                        walletLabel = signal.walletLabel,
                    )
                }
            }
        }
    }

    /**
     * Entrypoint for InsiderTrackerAI.InsiderSignal events.
     */
    fun onTrackerSignal(signal: com.lifecyclebot.v3.scoring.InsiderTrackerAI.InsiderSignal) {
        lastSignalAtMs.set(System.currentTimeMillis())
        val mint = signal.tokenMint ?: return    // can't act without a mint
        val symbol = signal.tokenSymbol ?: mint.take(6)
        val isAlpha = signal.wallet.riskLevel ==
            com.lifecyclebot.v3.scoring.InsiderTrackerAI.RiskLevel.ALPHA

        when (signal.signalType) {
            com.lifecyclebot.v3.scoring.InsiderTrackerAI.InsiderSignalType.ACCUMULATION,
            com.lifecyclebot.v3.scoring.InsiderTrackerAI.InsiderSignalType.PRE_TWEET,
            com.lifecyclebot.v3.scoring.InsiderTrackerAI.InsiderSignalType.TOKEN_CREATION -> {
                copyBuyMemeMint(
                    mint        = mint,
                    symbol      = symbol,
                    confidence  = signal.confidence,
                    walletLabel = signal.wallet.label,
                )
                copyBuyCryptoAlt6096(symbol, signal.confidence, signal.wallet.label)
            }
            com.lifecyclebot.v3.scoring.InsiderTrackerAI.InsiderSignalType.DISTRIBUTION -> {
                if (isAlpha || signal.confidence >= 70) {
                    copyExitAcrossTraders(
                        mintOpt     = mint,
                        symbol      = symbol,
                        confidence  = signal.confidence,
                        walletLabel = signal.wallet.label,
                    )
                }
            }
            else -> {} // TRANSFER_OUT, TRANSFER_IN, FRONT_RUN, UNUSUAL — informational
        }
    }

    private fun copyBuyCryptoAlt6096(symbol: String, confidence: Int, walletLabel: String) {
        try {
            val opened = com.lifecyclebot.perps.CryptoAltTrader.copyBuyFromInsiderSignal(symbol, confidence, walletLabel)
            if (opened) {
                ErrorLogger.info(TAG, "🦈 INSIDER-SHARK CRYPTO BUY OPENED: $symbol ($walletLabel, conf=$confidence) — routed to CryptoAltTrader")
            }
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "copyBuyCryptoAlt6096($symbol) error: ${t.message}")
        }
    }

    /**
     * Push a Solana mint into the memetrader scanner pipeline so it appears
     * in the watchlist with a WHALE_COPY source label.
     */
    private fun copyBuyMemeMint(mint: String, symbol: String, confidence: Int, walletLabel: String) {
        if (mint.isBlank() || mint.length < 30) return
        try {
            activeSignals[mint] = SharkSignalRow(mint, symbol, "BUY", confidence, walletLabel)
            TokenMergeQueue.enqueue(
                mint     = mint,
                symbol   = symbol,
                scanner  = "INSIDER_SHARK",
                marketCapUsd = 0.0,
                liquidityUsd = 0.0,
                volumeH1     = 0.0,
                laneAffinity = setOf("INSIDER_SHARK", "WHALE_FOLLOW", "COPY_TRADE", "MOONSHOT", "QUALITY"),
                toolAffinity = setOf("INSIDER_WALLET", "SOCIAL_ALPHA", "SMART_MONEY", "COPY_TRADE"),
            )
            totalCopyBuys.incrementAndGet()
            ErrorLogger.info(
                TAG,
                "🦈 INSIDER-SHARK BUY ENQUEUED: $symbol ($walletLabel, conf=$confidence) — routed to memetrader watchlist via INSIDER_SHARK"
            )
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "copyBuyMemeMint($symbol) error: ${e.message}")
        }
    }

    /**
     * Close any open positions matching the mint or symbol across every
     * Markets-layer trader. Logs each individual exit for transparency.
     */
    private fun copyExitAcrossTraders(
        mintOpt: String?,
        symbol: String,
        confidence: Int,
        walletLabel: String,
    ) {
        val reason = "INSIDER_SHARK_COPY_EXIT($walletLabel, conf=$confidence)"
        var exits = 0
        if (!mintOpt.isNullOrBlank()) {
            exitSignals[mintOpt] = SharkSignalRow(mintOpt, symbol, "SELL", confidence, walletLabel)
        }

        // CryptoAlts (BTC/ETH/SOL/SUI/etc.)
        try {
            val matches = com.lifecyclebot.perps.CryptoAltTrader.getOpenPositions()
                .filter { it.marketSymbol.equals(symbol, ignoreCase = true) }
            for (p in matches) {
                if (com.lifecyclebot.perps.CryptoAltTrader.closePositionManual(p.id, reason)) {
                    exits++
                    ErrorLogger.info(TAG, "🐋 COPY-EXIT [Alts] ${p.marketSymbol} | reason=$reason")
                }
            }
        } catch (_: Exception) {}

        // TokenizedStockTrader
        try {
            val matches = com.lifecyclebot.perps.TokenizedStockTrader.getActivePositions()
                .filter { it.market.symbol.equals(symbol, ignoreCase = true) }
            for (p in matches) {
                if (com.lifecyclebot.perps.TokenizedStockTrader.closePositionManual(p.id, reason)) {
                    exits++
                    ErrorLogger.info(TAG, "🐋 COPY-EXIT [Stocks] ${p.market.symbol} | reason=$reason")
                }
            }
        } catch (_: Exception) {}

        // CommoditiesTrader
        try {
            val matches = com.lifecyclebot.perps.CommoditiesTrader.getAllPositions()
                .filter { it.market.symbol.equals(symbol, ignoreCase = true) }
            for (p in matches) {
                if (com.lifecyclebot.perps.CommoditiesTrader.closePositionManual(p.id, reason)) {
                    exits++
                    ErrorLogger.info(TAG, "🐋 COPY-EXIT [Commod] ${p.market.symbol} | reason=$reason")
                }
            }
        } catch (_: Exception) {}

        // ForexTrader
        try {
            val matches = com.lifecyclebot.perps.ForexTrader.getAllPositions()
                .filter { it.market.symbol.equals(symbol, ignoreCase = true) }
            for (p in matches) {
                if (com.lifecyclebot.perps.ForexTrader.closePositionManual(p.id, reason)) {
                    exits++
                    ErrorLogger.info(TAG, "🐋 COPY-EXIT [Forex] ${p.market.symbol} | reason=$reason")
                }
            }
        } catch (_: Exception) {}

        // MetalsTrader
        try {
            val matches = com.lifecyclebot.perps.MetalsTrader.getAllPositions()
                .filter { it.market.symbol.equals(symbol, ignoreCase = true) }
            for (p in matches) {
                if (com.lifecyclebot.perps.MetalsTrader.closePositionManual(p.id, reason)) {
                    exits++
                    ErrorLogger.info(TAG, "🐋 COPY-EXIT [Metals] ${p.market.symbol} | reason=$reason")
                }
            }
        } catch (_: Exception) {}

        totalCopyExits.addAndGet(exits.coerceAtLeast(if (!mintOpt.isNullOrBlank()) 1 else 0))
        if (exits > 0) {
            ErrorLogger.info(TAG, "🦈 INSIDER-SHARK COPY-EXIT TOTAL: $exits market position(s) closed for $symbol; memetrader exit signal active=${!mintOpt.isNullOrBlank()}")
        } else if (!mintOpt.isNullOrBlank()) {
            ErrorLogger.info(TAG, "🦈 INSIDER-SHARK MEME EXIT SIGNAL: $symbol | reason=$reason")
        }
    }

    fun getStats(): String =
        "buys=${totalCopyBuys.get()} exits=${totalCopyExits.get()} lastMin=${
            if (lastSignalAtMs.get() == 0L) "--"
            else "${(System.currentTimeMillis() - lastSignalAtMs.get()) / 60_000L}m"
        }"
}
