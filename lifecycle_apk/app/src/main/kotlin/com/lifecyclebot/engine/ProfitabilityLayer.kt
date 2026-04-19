package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Trade

/**
 * V5.9.67: Central profitability helpers — pure, side-effect-free checks
 * called from Executor & SmartSizer. Focused on squeezing more EV out of
 * the trades the scanner already decides to take, without changing entry
 * criteria, scanner behaviour, or trade count.
 *
 * All methods are defensively null/exception safe so a bug in any layer
 * never blocks the existing execution pipeline.
 */
object ProfitabilityLayer {

    // ════════════════════════════════════════════════════════════════════
    // 1. TRAILING STOP
    //    Once unrealized PnL passes an activation threshold, convert the
    //    static SL into a trailing stop pegged to peak − giveback.
    //    Returns an exit reason if the trailing stop has been hit, else
    //    null. Only fires if the position is ALREADY in profit territory.
    // ════════════════════════════════════════════════════════════════════
    fun checkTrailingStop(ts: TokenState): String? {
        return try {
            val pos = ts.position
            if (!pos.isOpen || pos.entryPrice <= 0 || ts.lastPrice <= 0) return null
            val pnlPct = (ts.lastPrice / pos.entryPrice - 1.0) * 100.0

            // Different activation thresholds per asset class. Memes move
            // fast (12% activation, 8% giveback). Bluechips slower (4%/3%).
            val isBluechip = pos.isBlueChipPosition ||
                pos.tradingMode.contains("BLUE", ignoreCase = true)
            val activate = if (isBluechip) 4.0 else 12.0
            val giveback = if (isBluechip) 3.0 else 8.0

            // We need peakGainPct to be meaningful. It's updated by Executor
            // on every poll via the existing highestPrice tracking.
            val peakPct = pos.peakGainPct
            if (peakPct < activate) return null

            // Trailing hit when pullback from peak exceeds giveback budget.
            val pullback = peakPct - pnlPct
            if (pullback >= giveback) {
                "trail_stop_peak${peakPct.toInt()}_pull${pullback.toInt()}"
            } else null
        } catch (_: Throwable) { null }
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. SCALE-OUT LADDER
    //    Returns a Pair<fractionToSell, reason> to indicate a partial
    //    exit. Targets: 33% at TP/3, 33% at TP*0.66, 34% at TP.
    //    Uses Position.partialSoldPct to remember which rung fired.
    // ════════════════════════════════════════════════════════════════════
    data class ScaleOutSignal(val fraction: Double, val reason: String)

    fun checkScaleOut(ts: TokenState, takeProfitPct: Double): ScaleOutSignal? {
        return try {
            val pos = ts.position
            if (!pos.isOpen || pos.entryPrice <= 0 || ts.lastPrice <= 0) return null
            if (takeProfitPct <= 0) return null

            val pnlPct = (ts.lastPrice / pos.entryPrice - 1.0) * 100.0
            val already = pos.partialSoldPct
            val tp1 = takeProfitPct / 3.0
            val tp2 = takeProfitPct * 0.66

            when {
                pnlPct >= tp2 && already < 0.66 ->
                    ScaleOutSignal(fraction = 0.50, reason = "scale_out_tp2_${pnlPct.toInt()}pct")
                pnlPct >= tp1 && already < 0.33 ->
                    ScaleOutSignal(fraction = 0.33, reason = "scale_out_tp1_${pnlPct.toInt()}pct")
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. DRAIN-EXIT
    //    If liquidity is draining while we hold, bail before the dump.
    //    Already-existing LiquidityDepthAI signals are used; we just act
    //    on them for OPEN positions (previously only used as entry-skip).
    // ════════════════════════════════════════════════════════════════════
    fun checkDrainExit(ts: TokenState): String? {
        return try {
            val pos = ts.position
            if (!pos.isOpen) return null
            val sig = LiquidityDepthAI.getSignal(ts.mint, ts.symbol, isOpenPosition = true)
            val collapsing = sig.signal in listOf(
                LiquidityDepthAI.SignalType.LIQUIDITY_COLLAPSE,
                LiquidityDepthAI.SignalType.LIQUIDITY_DRAINING
            )
            if (!collapsing) return null

            // Require some age so we don't exit on the first noisy tick.
            val ageSec = (System.currentTimeMillis() - pos.entryTime) / 1000L
            if (ageSec < 60) return null

            "drain_exit_${sig.signal.name.lowercase()}"
        } catch (_: Throwable) { null }
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. FEE-AWARE MIN-PROFIT BAND
    //    Prevents scratches (-0.8% … +0.8%) from being exited by weak
    //    signals. If unrealized PnL is in the band AND position is
    //    younger than the idle window, block the exit. SL still wins.
    // ════════════════════════════════════════════════════════════════════
    fun shouldBlockFeeBandExit(ts: TokenState, reason: String): Boolean {
        return try {
            val pos = ts.position
            if (!pos.isOpen || pos.entryPrice <= 0 || ts.lastPrice <= 0) return false

            // Never block a true stop-loss or trail-stop or drain exit.
            val r = reason.lowercase()
            val hardExit = r.contains("stop") || r.contains("sl") ||
                           r.contains("trail") || r.contains("drain") ||
                           r.contains("rug") || r.contains("v8") ||
                           r.contains("circuit") || r.contains("force")
            if (hardExit) return false

            val pnlPct = (ts.lastPrice / pos.entryPrice - 1.0) * 100.0
            if (pnlPct < -0.8 || pnlPct > 0.8) return false

            // In-band: only block if the position is younger than the
            // idle budget (15 min). After that we accept the scratch.
            val ageMin = (System.currentTimeMillis() - pos.entryTime) / 60_000L
            ageMin < 15L
        } catch (_: Throwable) { false }
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. CONFLUENCE SIZE BOOST
    //    Multiplier applied by SmartSizer when many independent layers
    //    agree. Doesn't change whether we enter — only the size.
    //    Returns a multiplier in [1.0 .. 1.3], centred at 1.0.
    // ════════════════════════════════════════════════════════════════════
    fun confluenceMultiplier(
        quality: String?,
        source: String?,
        entryAiRisk: String?,
        momentumBuilding: Boolean,
    ): Double {
        return try {
            var score = 0
            if (quality?.uppercase() in setOf("A", "B", "ORBITAL")) score++
            if (source?.uppercase() in setOf("PUMP_FUN_GRADUATE", "RAYDIUM_NEW_POOL",
                    "DEX_BOOSTED", "COINGECKO_TRENDING")) score++
            if (entryAiRisk?.uppercase() == "LOW") score++
            if (momentumBuilding) score++
            when (score) {
                4 -> 1.30
                3 -> 1.15
                else -> 1.00
            }
        } catch (_: Throwable) { 1.0 }
    }

    // ════════════════════════════════════════════════════════════════════
    // 6. HOT-STREAK SIZE SCALING
    //    Tracks last-N closed trades to identify hot/cold regimes.
    //    Applied as a SmartSizer multiplier in [0.8 .. 1.2].
    // ════════════════════════════════════════════════════════════════════
    fun streakMultiplier(): Double {
        return try {
            val trades = TradeHistoryStore.getAllSells().takeLast(6)
            if (trades.size < 3) return 1.0
            val recent = trades.takeLast(3)
            val wins = recent.count { it.pnlPct > 0.5 }
            val losses = recent.count { it.pnlPct < -0.5 }
            when {
                wins >= 3 -> 1.20
                losses >= 3 -> 0.80
                wins >= 2 -> 1.10
                losses >= 2 -> 0.90
                else -> 1.0
            }
        } catch (_: Throwable) { 1.0 }
    }

    // ════════════════════════════════════════════════════════════════════
    // 7. DYNAMIC TP/SL FROM COHORT (light-weight)
    //    Returns (tpPct, slPct) inferred from recent closed trades with
    //    similar tradingMode. Falls back to the static input if we don't
    //    have enough history.
    // ════════════════════════════════════════════════════════════════════
    data class DynamicTargets(val tpPct: Double, val slPct: Double, val src: String)

    fun dynamicTargets(staticTp: Double, staticSl: Double, mode: String?): DynamicTargets {
        return try {
            val history = TradeHistoryStore.getAllSells().takeLast(200)
                .filter { it.tradingMode.equals(mode ?: "", ignoreCase = true) }
            if (history.size < 20) {
                return DynamicTargets(staticTp, staticSl, "static_fallback")
            }
            val winners = history.filter { it.pnlPct > 1.0 }.map { it.pnlPct }.sorted()
            val losers  = history.filter { it.pnlPct < -1.0 }.map { it.pnlPct }.sorted()
            if (winners.size < 5 || losers.size < 5) {
                return DynamicTargets(staticTp, staticSl, "thin_cohort")
            }
            // p75 of winners = typical-upper-win; p25 of losers = typical
            // stop-loss depth.
            val p75Win = winners[(winners.size * 3 / 4).coerceAtMost(winners.size - 1)]
            val p25Los = losers[(losers.size / 4).coerceAtMost(losers.size - 1)]
            // Clamp so absurd history can't push extremes.
            val tp = p75Win.coerceIn(staticTp * 0.5, staticTp * 2.0)
            val sl = p25Los.coerceIn(staticSl * 2.0, staticSl * 0.5) // sl is negative
            DynamicTargets(tp, sl, "cohort_n${history.size}")
        } catch (_: Throwable) {
            DynamicTargets(staticTp, staticSl, "err_fallback")
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 8. DEAD-PRICE WATCHLIST EVICTION
    //    Tracks consecutive price=0 returns per symbol. Three strikes →
    //    caller should drop the symbol from the watchlist for 1h.
    // ════════════════════════════════════════════════════════════════════
    private val deadPriceCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val evictedUntil    = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Call on each poll with whether the price fetch succeeded. */
    fun notePriceFetch(symbol: String, gotPrice: Boolean) {
        try {
            if (gotPrice) {
                deadPriceCounts.remove(symbol)
            } else {
                val n = deadPriceCounts.getOrDefault(symbol, 0) + 1
                deadPriceCounts[symbol] = n
                if (n >= 3) {
                    evictedUntil[symbol] = System.currentTimeMillis() + 3_600_000L
                    deadPriceCounts.remove(symbol)
                }
            }
        } catch (_: Throwable) {}
    }

    fun isEvicted(symbol: String): Boolean {
        return try {
            val until = evictedUntil[symbol] ?: return false
            if (System.currentTimeMillis() < until) true
            else { evictedUntil.remove(symbol); false }
        } catch (_: Throwable) { false }
    }
}
