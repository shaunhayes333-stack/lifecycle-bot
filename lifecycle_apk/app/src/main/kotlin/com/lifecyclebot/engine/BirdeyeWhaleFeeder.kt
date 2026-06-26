package com.lifecyclebot.engine

import com.lifecyclebot.network.BirdeyeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.938 — BirdeyeWhaleFeeder.
 *
 * Bridges Birdeye /defi/v2/tokens/top_traders into WhaleTrackerAI which
 * had been receiving INFERRED whale activity (from AICrossTalk price-
 * action heuristics) instead of real on-chain wallets.
 *
 * Per admitted mint, fetch top 10 actual wallet addresses with their
 * 24h volumeBuy/volumeSell/tradeCount, classify each into
 * BUY/SELL/ACCUMULATE/DISTRIBUTE based on the buy-sell volume delta,
 * and feed them to WhaleTrackerAI.recordWhaleActivity().
 *
 * Doctrine #87.5 — dormant safety subsystems must FIRE. WhaleTracker
 * existed but was starving for ground-truth data.
 *
 * CU budget: ~30 CU/call × 50 admitted mints × 1 fetch/60min = 1500 CU/hr
 * = 1.08M CU/month. Fits in Starter's 5M budget.
 */
object BirdeyeWhaleFeeder {
    private const val TAG = "BirdeyeWhaleFeeder"
    private const val CACHE_TTL_MS = 60L * 60_000L
    private const val REQUEST_TIMEOUT_MS = 3500L
    private const val MIN_VOLUME_USD_TO_CLASSIFY = 1000.0

    private data class Stamp(val timestampMs: Long)
    private val lastFetched = ConcurrentHashMap<String, Stamp>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    private val knownWhales = ConcurrentHashMap<String, Long>()

    @Volatile private var consecutiveFails: Int = 0
    @Volatile private var circuitOpenUntilMs: Long = 0L

    suspend fun maybeFeed(mint: String, symbol: String, apiKey: String, currentPriceUsd: Double) {
        if (mint.isBlank() || apiKey.isBlank()) return
        val now = System.currentTimeMillis()
        if (now < circuitOpenUntilMs) return
        val last = lastFetched[mint]
        if (last != null && (now - last.timestampMs) < CACHE_TTL_MS) return
        // V5.0.4186 — BIRDEYE = BACKUP. Whale top-traders data is
        // enrichment only — when scanner-lane budget is throttled,
        // skip and let HeliusWS / on-chain monitors provide whale signal.
        if (!com.lifecyclebot.engine.BirdeyeBudgetGate.canAffordScannerLane()) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BIRDEYE_WHALE_FEED_SKIPPED_BUDGET") } catch (_: Throwable) {}
            return
        }
        if (inFlight.putIfAbsent(mint, true) != null) return

        try {
            val traders = withContext(Dispatchers.IO) {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    BirdeyeApi(apiKey).getTopTraders(mint, limit = 10)
                }
            }
            if (traders == null) {
                val fails = (consecutiveFails + 1).also { consecutiveFails = it }
                if (fails >= 5 && circuitOpenUntilMs < now) {
                    circuitOpenUntilMs = now + 30L * 60_000L
                    ErrorLogger.warn(TAG, "⚡ Circuit OPEN — $fails fails")
                }
                return
            }
            consecutiveFails = 0
            lastFetched[mint] = Stamp(now)

            var fedCount = 0
            for (t in traders) {
                if (t.wallet.isBlank()) continue
                val volNet = t.volumeBuy - t.volumeSell
                val volTotal = t.volumeBuy + t.volumeSell
                if (volTotal < MIN_VOLUME_USD_TO_CLASSIFY) continue

                knownWhales[t.wallet] = now

                val action = when {
                    volNet > volTotal * 0.50  -> WhaleTrackerAI.WhaleAction.ACCUMULATE
                    volNet > 0                -> WhaleTrackerAI.WhaleAction.BUY
                    volNet < -volTotal * 0.50 -> WhaleTrackerAI.WhaleAction.DISTRIBUTE
                    else                      -> WhaleTrackerAI.WhaleAction.SELL
                }
                val amountSolEquiv = volTotal / 85.0

                try {
                    WhaleTrackerAI.recordWhaleActivity(
                        whaleAddress  = t.wallet,
                        mint          = mint,
                        symbol        = symbol,
                        action        = action,
                        amountSol     = amountSolEquiv,
                        priceAtAction = currentPriceUsd,
                    )
                    if (!WhaleTrackerAI.isTrackedWhale(t.wallet)) {
                        WhaleTrackerAI.addWhaleToTrack(t.wallet, nickname = "be_top_${mint.take(6)}", initialTrust = 55.0)
                    }
                    fedCount++
                } catch (_: Throwable) { }
            }

            if (fedCount > 0) {
                ErrorLogger.debug(TAG, "fed $fedCount whales for ${symbol.ifBlank { mint.take(8) }}")
            }
            if (knownWhales.size > 10_000) {
                val cutoff = now - 24L * 3600_000L
                knownWhales.entries.removeIf { it.value < cutoff }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "feed fail-open ${mint.take(10)}: ${e.message?.take(40)}")
        } finally {
            inFlight.remove(mint)
        }
    }

    fun isKnownWhale(wallet: String): Boolean = knownWhales.containsKey(wallet)
    fun knownWhaleCount(): Int = knownWhales.size
    fun isCircuitOpen(): Boolean = System.currentTimeMillis() < circuitOpenUntilMs
}
