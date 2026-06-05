package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade

/**
 * V5.9.434 — Central journal recorder for the V3 meme sub-traders
 * (ShitCoinTraderAI, MoonshotTraderAI, QualityTraderAI, BlueChipTraderAI,
 * CashGenerationAI, ManipulatedTraderAI).
 *
 * V5.9.436 — Now ALSO the central outcome-attribution hub. Every V3
 * close is automatically fed into:
 *   - ScoreExpectancyTracker  (per-layer score-bucket P&L)
 *   - HoldDurationTracker     (per-layer hold-time-bucket P&L)
 *   - ExitReasonTracker       (per-layer exit-reason P&L)
 *
 * V5.9.447 — UNIVERSAL JOURNAL COVERAGE.
 * User (build 2316): "all trades processed by the bot in the full universe
 * must be logged in the journal. no exceptions"
 *
 * Audit found 3 silent execution paths that bypassed the Journal:
 *   1. ShitCoinExpress.boardRide / exitRide — never journaled
 *   2. LlmLabTrader.openPaper / closePosition — never journaled
 *   3. PositionPersistence 60-day stale refund — silent SOL credit
 *
 * Added recordOpen(...) below so any sub-trader executing its own buys
 * (i.e. NOT routing through Executor) can journal a BUY row directly.
 * Together with recordClose(...) every universe path now lands in
 * TradeHistoryStore.
 *
 * Sub-traders soft-reject incoming entries by querying these trackers,
 * closing the open feedback loop that left WR stuck at 30% over 5000
 * trades (no actual outcome attribution to entry score / hold time /
 * exit reason previously existed).
 *
 * Root cause this fixes: the V3 sub-traders each have their own
 * closePosition() that books into FluidLearning / SmartSizer /
 * RunTracker30D / dailyWins but was NEVER calling
 * TradeHistoryStore.recordTrade. Only Executor-routed lanes (stocks /
 * crypto alts / perps / metals / forex / commodities) showed up in the
 * Journal, so with 4791 bot trades the Journal had only ~300 rows.
 */
object V3JournalRecorder {

    // V5.9.706 — dedup guard: prevents double-journal when BotService rapid-monitor
    // AND a sub-trader both fire closePosition on the same mint.
    // V5.9.1203 — extend 5s → 60s. Runtime 5.0.3170 showed repeated SELL rows
    // on the same mint prefix over ~45s (stale close waves after the first exit).
    // One physical close should produce one sub-trader journal close; later close
    // attempts inside the same minute are accounting pollution, not new trades.
    private val recentCloseDedup = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val CLOSE_DEDUP_MS = 60_000L


    /**
     * V5.9.447 — record a BUY (entry) row in the Journal for sub-traders
     * whose entry path bypasses the main Executor. Use recordClose() for
     * the matching SELL row when the position closes.
     */
    fun recordOpen(
        symbol: String,
        mint: String,
        entryPrice: Double,
        sizeSol: Double,
        isPaper: Boolean,
        layer: String,
        entryScore: Int = 0,
        entryReason: String = "",
    ) {
        try {
            val t = Trade(
                side       = "BUY",
                mode       = if (isPaper) "paper" else "live",
                sol        = sizeSol,
                price      = entryPrice,
                ts         = System.currentTimeMillis(),
                reason     = if (entryReason.isBlank()) "${layer}_ENTRY" else "${layer}_$entryReason",
                pnlSol     = 0.0,
                pnlPct     = 0.0,
                netPnlSol  = 0.0,
                score      = entryScore.toDouble(),
                tradingMode = layer,
                tradingModeEmoji = layerEmoji(layer),
                mint       = mint,
            )
            TradeHistoryStore.recordTrade(t)
            // V5.9.495z39 P1 backfill — ensure every sub-trader buy populates
            // entry-price + decimals into TokenLifecycleTracker so downstream
            // PnL math (RealizedPnLCalculator / SellForensicsWriter) gets the
            // correct cost basis even when the V3 trader bypasses Executor.
            // Pump.fun/SPL memes default to 6 decimals; sub-traders that
            // know the real decimals can call recordEntryMetadata themselves.
            try {
                if (mint.isNotBlank() && entryPrice > 0.0) {
                    // entryPrice here is SOL/token (V3 sub-traders pass the
                    // SOL-denominated entry price). Refresh the lifecycle
                    // record idempotently — TokenLifecycleTracker.onBuyPending
                    // creates the row if missing.
                    com.lifecyclebot.engine.TokenLifecycleTracker.onBuyPending(
                        mint = mint, symbol = symbol, venue = layer, sizeSol = sizeSol,
                    )
                    com.lifecyclebot.engine.TokenLifecycleTracker.recordEntryMetadata(
                        mint = mint,
                        entryPriceSol = entryPrice,
                        entryDecimals = 6,
                    )
                }
            } catch (_: Throwable) { /* never break the journal write */ }
            ErrorLogger.info("V3JournalRecorder",
                "📓 [$layer] BUY $symbol @ ${"%.6f".format(entryPrice)} | size=${"%.4f".format(sizeSol)}◎ | score=$entryScore")
        } catch (e: Exception) {
            ErrorLogger.error("V3JournalRecorder",
                "⚠️ JOURNAL OPEN FAILED for $symbol ($layer): ${e.message}", e)
        }
    }

    private fun layerEmoji(layer: String): String = when (layer.uppercase()) {
        "SHITCOIN"          -> "💩"
        "SHITCOINEXPRESS",
        "EXPRESS"           -> "🎫"
        "MOONSHOT"          -> "🚀"
        "BLUECHIP"          -> "💎"
        "CASHGEN",
        "CASHGENERATION"    -> "💰"
        "MANIPULATED"       -> "🎭"
        "QUALITY"           -> "⭐"
        "LAB",
        "LLMLAB"            -> "🧪"
        "STALE_REFUND",
        "EXPIRED_REFUND"    -> "♻️"
        else                -> "📈"
    }

    fun recordClose(
        symbol: String,
        mint: String,
        entryPrice: Double,
        exitPrice: Double,
        sizeSol: Double,
        pnlPct: Double,
        pnlSol: Double,
        isPaper: Boolean,
        layer: String,         // "SHITCOIN" | "MOONSHOT" | "BLUECHIP" | "CASHGEN" | "MANIPULATED" | "QUALITY"
        exitReason: String,
        // V5.9.436 — outcome-attribution metadata. Defaults so older callers
        // still compile; pass real values to feed the learning trackers.
        entryScore: Int = 0,
        holdMinutes: Long = 0L,
    ) {
        // V5.9.1203 — dedup: drop duplicate journal entry for same mint within 60s
        val _dedupNow = System.currentTimeMillis()
        val _lastClose = recentCloseDedup[mint]
        if (_lastClose != null && _dedupNow - _lastClose < CLOSE_DEDUP_MS) {
            com.lifecyclebot.engine.ErrorLogger.debug("V3JournalRecorder",
                "DEDUP_SKIP $symbol ${layer}_${exitReason}: closed ${_dedupNow - _lastClose}ms ago")
            return
        }
        recentCloseDedup[mint] = _dedupNow

        // V5.9.1357 — LEARNING-PNL SANITIZER (expectancy poison firewall).
        // A glitched price feed (near-zero entryPrice, bad mcap-derived tick)
        // can yield physically-impossible closes like +1,340,125% that poison
        // an entire lane's expectancy bin (the "lie of averages" disease — one
        // absurd outlier makes a bleeding lane look like a megawinner, e.g.
        // TREASURY EV=+670059%/trade). The on-disk journal keeps the RAW pnl so
        // the user UI and accounting stay truthful, but EVERY learning tracker
        // (expectancy, hold-duration, exit-reason, tactic switcher, damage
        // control) is fed a clamped value. A genuine meme moonshot can do +900%
        // even +2000% on a real fill, so the cap is generous; anything past it
        // is a feed artifact, not a realized exit.
        val pnlPctLearn: Double = run {
            val LO = -100.0          // can't lose more than the stake
            val HI = 5000.0          // +50x — generous real-fill ceiling
            when {
                pnlPct.isNaN() || pnlPct.isInfinite() -> {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LEARNING_PNL_ARTIFACT_DROPPED|reason=NAN_INF") } catch (_: Throwable) {}
                    0.0
                }
                pnlPct > HI -> {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LEARNING_PNL_CLAMPED|reason=OUTLIER_HIGH") } catch (_: Throwable) {}
                    com.lifecyclebot.engine.ErrorLogger.warn("V3JournalRecorder",
                        "🧯 PNL_OUTLIER_CLAMPED $symbol ($layer): raw=${"%.0f".format(pnlPct)}% → ${HI}% (feed artifact; journal keeps raw)")
                    HI
                }
                pnlPct < LO -> {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LEARNING_PNL_CLAMPED|reason=OUTLIER_LOW") } catch (_: Throwable) {}
                    LO
                }
                else -> pnlPct
            }
        }

        // 1. Persist to the on-device SQLite Journal so the user UI sees it.
        var wrote = false
        try {
            val t = Trade(
                side       = "SELL",
                mode       = if (isPaper) "paper" else "live",
                sol        = sizeSol,
                price      = exitPrice,
                ts         = System.currentTimeMillis(),
                reason     = "${layer}_${exitReason}",
                pnlSol     = pnlSol,
                pnlPct     = pnlPct,
                netPnlSol  = pnlSol,
                // V5.9.1205 — persist entry score into SELL rows too.
                // LosingPatternMemory buckets by Trade.score; without this,
                // V3 sub-trader closes defaulted to score=0 and polluted S0-10.
                score      = entryScore.toDouble(),
                tradingMode = layer,
                tradingModeEmoji = layerEmoji(layer),
                mint       = mint,
            )
            TradeHistoryStore.recordTrade(t)
            wrote = true
            // V5.9.441 — always-on log so user can verify every V3 exit
            // lands in the Journal in real time.
            ErrorLogger.info("V3JournalRecorder",
                "📓 [$layer] $symbol ${exitReason} | " +
                "pnl=${"%+.2f".format(pnlPct)}% (${"%+.4f".format(pnlSol)} SOL) | " +
                "score=${entryScore} hold=${holdMinutes}m")
        } catch (e: Exception) {
            // V5.9.441 — promoted from debug→error. If journal writes are
            // failing we MUST see it in the log instead of silently losing
            // trades.
            ErrorLogger.error("V3JournalRecorder",
                "⚠️ JOURNAL WRITE FAILED for $symbol ($layer/${exitReason}): ${e.message}", e)
        }

        // 2. V5.9.436 — feed all three outcome-attribution trackers.
        //    Each tracker is fail-open and thread-safe. Only feed when the
        //    journal write actually landed so trackers don't diverge from
        //    the on-disk truth.
        if (wrote) {
            try { ScoreExpectancyTracker.record(layer, entryScore, pnlPctLearn) } catch (_: Exception) {}
            try { HoldDurationTracker.record(layer, holdMinutes, pnlPctLearn) } catch (_: Exception) {}
            try { ExitReasonTracker.record(layer, exitReason, pnlPctLearn) } catch (_: Exception) {}
            // V5.9.1333 — Tactic switcher observes per-(lane, scoreBand) outcome.
            // When a bucket bleeds past threshold, rotates its entry tactic
            // (MOMENTUM → PULLBACK → REACCUMULATION → BREAKOUT). Never disables.
            try {
                val band = com.lifecyclebot.engine.LosingPatternMemory.scoreBand(entryScore)
                com.lifecyclebot.engine.learning.TacticSwitcher.onTradeClosed(layer, band, pnlPctLearn)
            } catch (_: Exception) {}
            // V5.9.1355 P0.6 — feed the global damage-control window + per-lane
            // cold-streak damper. recordClose is the MEME close fanout so these
            // windows stay meme-domain clean.
            try {
                val isWinC = pnlPctLearn > 0.5; val isLossC = pnlPctLearn < -0.5
                com.lifecyclebot.engine.runtime.ColdStreakDamper.noteOutcome(layer, isPaper, isWinC, isLossC)
                com.lifecyclebot.engine.runtime.DamageControlGate.noteOutcome(pnlPctLearn)
            } catch (_: Exception) {}
        }
    }
}
