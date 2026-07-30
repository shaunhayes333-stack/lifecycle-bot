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
                entryTsMs = System.currentTimeMillis(),
                entryPriceSnapshot = entryPrice,
                entryCostSol = sizeSol,
                entryQtyToken = if (entryPrice > 0.0 && sizeSol > 0.0) sizeSol / entryPrice else 0.0,
                remainingQtyToken = if (entryPrice > 0.0 && sizeSol > 0.0) sizeSol / entryPrice else 0.0,
                entryPriceSource = layer,
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
        // V5.9.1378 (P0 #9) — peak gain % reached during the hold (MFE / max
        // favorable excursion). Default 0.0 keeps older callers compiling; pass the
        // real Position.peakGainPct so give-back (MFE - realized) is measurable and
        // the exit ladder can be tuned against "runners getting cut" telemetry.
        peakGainPct: Double = 0.0,
    ) {
        // V5.9.1375 (P0 #6) — arm RE-ENTRY LOCKOUT for stop-loss-type exits BEFORE
        // the dedup early-return, so a BUY->STOP_LOSS->BUY loop keeps the lock fresh
        // even when later close waves are deduped. Fail-open; only arms on real losses.
        try {
            val _fam = symbol.uppercase().trim().filter { it.isLetterOrDigit() }.take(8)
            com.lifecyclebot.engine.ReEntryLockout.onClose(mint, _fam, exitReason, pnlPct)
        } catch (_: Throwable) {}

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
                entryPriceSnapshot = entryPrice,
                entryCostSol = sizeSol,
                entryQtyToken = if (entryPrice > 0.0 && sizeSol > 0.0) sizeSol / entryPrice else 0.0,
                soldQtyToken = if (entryPrice > 0.0 && sizeSol > 0.0) sizeSol / entryPrice else 0.0,
                entryPriceSource = layer,
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
            // V5.0.6373 — SKEW-TAINT LEARNING QUARANTINE (source-of-creation).
            // Operator snapshot showed 4 mints with QTY_DECIMAL_SKEW_6309 audits
            // (buyQty=3185/sellQty=184.9 ratio=17.2×, buyQty=33650/sellQty=603.8
            // ratio=55.7× etc.) but `Skew learning quarantine: 0` — the V5.0.6310
            // check inside Executor compares this SELL's derived qty to
            // TradeHistoryStore's latest-buy-by-mint entryQtyToken, both of which
            // are computed as sizeSol/entryPrice in V3JournalRecorder, so they
            // never diverge at THAT layer. The real skew lives between the
            // Executor.paperBuy exec record (wallet-verified/heuristic-inferred
            // qty via inferUiScaleFromTrade) and this SELL row. When that ratio
            // exceeds 10× AND the resulting pnl%<=-80%, the loss is a scaling
            // artifact — the wallet actually took a near-scratch. Skip every
            // downstream learner so μ (TacticSwitcher / RetrainingDecay /
            // ExplorationBudget / ScoreExpectancyTracker / LaneExitTuner) isn't
            // taught catastrophic edge from a decimal mismatch.
            val skewTainted6373: Boolean = try {
                val buySnap = try {
                    com.lifecyclebot.engine.TradeHistoryStore.getLatestBuyByMintSnapshot()[mint]
                } catch (_: Throwable) { null }
                val buyQty  = buySnap?.entryQtyToken ?: 0.0
                val sellQty = if (entryPrice > 0.0 && sizeSol > 0.0) sizeSol / entryPrice else 0.0
                if (buyQty > 0.0 && sellQty > 0.0) {
                    val ratio = maxOf(buyQty, sellQty) / minOf(buyQty, sellQty)
                    if (ratio > 10.0 && pnlPctLearn <= -80.0) {
                        try {
                            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SKEW_TAINT_LEARNING_QUARANTINE_6373|lane=$layer")
                            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                                "SKEW_TAINT_LEARNING_QUARANTINE_6373",
                                "mint=${mint.take(10)} sym=$symbol layer=$layer buyQty=${"%.2f".format(buyQty)} sellQty=${"%.2f".format(sellQty)} ratio=${"%.1f".format(ratio)}× pnl=${"%.1f".format(pnlPctLearn)}% — decimal mismatch, learners skipped"
                            )
                        } catch (_: Throwable) {}
                        true
                    } else false
                } else false
            } catch (_: Throwable) { false }
            if (skewTainted6373) return
            // V5.0.6365 — REVERTED V5.0.6361 CANONICAL LEARNING CONTRACT SHIM.
            //   Operator (verbatim): "was over 80% winrate 3 updates ago /
            //   wallet was growing / now 100 tokens open, wallet shrinking."
            //   V5.0.6360 was the last known-good state; V5.0.6361 wrapped
            //   every learning aggregator behind a shim call to
            //   CanonicalLearningContract6346.assess. The shim built a
            //   synthetic Trade with `entryQtyToken=0.0, soldQtyToken=0.0,
            //   tokenDecimals=6` (hardcoded pump.fun default) because
            //   recordClose has NO qty parameter — literally no way to
            //   supply real values.
            //
            //   The shim mostly returns CANONICAL because qty=0 skips the
            //   parity checks, BUT any close reaching this method with
            //   sizeSol<=0 or entryPrice<=0 (stale positions, partial
            //   refunds, price-glitched exits) hits the SELL missing-basis
            //   branch (lines 115-122 of the contract) and gets QUARANTINED.
            //   Every quarantined close silently skipped
            //   ScoreExpectancyTracker / HoldDurationTracker /
            //   ExitReasonTracker / LaneExitTuner / TacticSwitcher /
            //   ColdStreakDamper / DamageControlGate / LanePolicy /
            //   RetrainingDecay — the exact levers that decide sizing +
            //   entry tactic + exit rule. Over hours the tuners drifted
            //   and the bot could no longer reject its own losers.
            //
            //   Correct source-of-creation posture: canonical eligibility
            //   MUST be enforced at the layer that HAS qty (Executor sell
            //   path, or FillLotLedger6344 — both live upstream of this
            //   recorder). Doing it here with a shim that hardcodes 0.0
            //   is worse than not doing it at all. The Executor V5.0.6361
            //   full-exit qty preservation is unaffected and remains in
            //   place — that write happens at the correct layer.
            try { ScoreExpectancyTracker.record(layer, entryScore, pnlPctLearn) } catch (_: Exception) {}
            try { HoldDurationTracker.record(layer, holdMinutes, pnlPctLearn) } catch (_: Exception) {}
            try { ExitReasonTracker.record(layer, exitReason, pnlPctLearn) } catch (_: Exception) {}
            // V5.9.1378 (P0 #9) — MFE give-back: how much of the peak did we keep?
            // giveBack = peak - realized. A large give-back on a winner-turned-loser
            // means the trail/exit cut a runner or held a fader too long. Emit as
            // labelled telemetry (sanitized peak) so the snapshot exposes the leak.
            try {
                val peakSane = when {
                    peakGainPct.isNaN() || peakGainPct.isInfinite() -> 0.0
                    peakGainPct > 5000.0 -> 5000.0
                    peakGainPct < 0.0 -> 0.0
                    else -> peakGainPct
                }
                if (peakSane > 0.0) {
                    val giveBack = peakSane - pnlPctLearn
                    com.lifecyclebot.engine.PipelineHealthCollector.recordMfe(layer, peakSane, pnlPctLearn)
                    // A runner that ran >=20% but closed >=25pp off its peak = cut runner.
                    if (peakSane >= 20.0 && giveBack >= 25.0) {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MFE_RUNNER_GIVEBACK|lane=$layer")
                    }
                }
            } catch (_: Throwable) {}
            // V5.9.1379 — feed the closed-loop lane exit tuner. peakGainPct may be
            // 0.0 from callers that don't pass it yet (tuner just treats peak as 0).
            try {
                com.lifecyclebot.engine.learning.LaneExitTuner.recordClose(
                    lane = layer, pnlPct = pnlPctLearn, peakPct = peakGainPct, exitReason = exitReason
                )
            } catch (_: Throwable) {}
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
            // V5.9.1460 — CLOSE THE LEARNING LOOP. Before this, the two levers that
            // actually decide how much capital a lane gets (LanePolicy policy-State,
            // read by FdgRouteVerdict at entry; and RetrainingDecay executionWeight)
            // were NEVER driven by outcomes — RetrainingDecay.noteOutcome had ZERO
            // callers and no code ever auto-demoted a LanePolicy bucket. A bleeding
            // lane kept full size forever, so WR could not climb 25%→50% over any
            // number of trades (operator: "statistically impossible"). Wire BOTH here,
            // in the proven MEME close fanout, with lane/band/pnl already computed:
            try {
                val isWinL = pnlPctLearn > 0.5; val isLossL = pnlPctLearn < -0.5
                val bandL = com.lifecyclebot.engine.LosingPatternMemory.scoreBand(entryScore)
                // (a) rolling-WR auto demote/promote of the entry-gate policy State
                com.lifecyclebot.engine.learning.LanePolicy.recordOutcome(layer, bandL, isWinL, isLossL)
                // (b) V5.0.6368 — magnitude-aware per-loss execution-weight decay / per-win recovery.
                // Catastrophic (-95%) close now compounds 4× decay steps; scratch (-1%) stays at 1×.
                com.lifecyclebot.engine.learning.RetrainingDecay.noteOutcome(layer, bandL, isWinL, isLossL, pnlPctLearn)
                // (c) V5.0.6368 — feed magnitude downstream to ExplorationBudget so
                //     bleeding lanes see their paperMicroTrade ceiling collapse
                //     (0.25×) without touching LanePolicy state at all.
                com.lifecyclebot.engine.learning.ExplorationBudget.onLaneOutcome(layer, pnlPctLearn)
            } catch (_: Exception) {}

            // V5.0.6388 (S6/S8/S11) — GOVERNOR RECOVERY EVIDENCE + PROMOTION/DEMOTION.
            // Every canonical close (paper or live) is recorded into the
            // post-fix evidence collector (paper closes are treated as
            // audit-only via evidenceEpoch<6388). Then the state machine
            // re-evaluates its promotion/demotion criteria in place.
            try {
                val liveOnly = !isPaper
                val evidenceEpoch = if (liveOnly) com.lifecyclebot.engine.truth.EvidenceEpochFilter6388.EPOCH else 0
                val pnlSol = try {
                    if (sizeSol > 0.0) sizeSol * (pnlPctLearn / 100.0) else 0.0
                } catch (_: Throwable) { 0.0 }
                com.lifecyclebot.engine.truth.PostFixEvidenceCollector6388.recordCanonicalClose(
                    evidenceEpoch = evidenceEpoch, pnlSol = pnlSol,
                    signaturesComplete = true, quantityIntegrity = true,
                    decimalIntegrity = true, quarantined = false,
                )
                val reconcilerHealthy = try {
                    com.lifecyclebot.engine.sell.SellReconciler.isStarted &&
                    com.lifecyclebot.engine.sell.SellReconciler.totalTicks > 0L
                } catch (_: Throwable) { false }
                val evidence = com.lifecyclebot.engine.truth.PostFixEvidenceCollector6388.snapshot(
                    tradesCompletedInState = 1, reconcilerHealthyThroughout = reconcilerHealthy,
                )
                com.lifecyclebot.engine.truth.GovernorRecovery6388.evaluatePromotion(evidence)
                com.lifecyclebot.engine.truth.GovernorRecovery6388.evaluateDemotion(evidence)
                // If this was a probation close, release the open counter.
                if (liveOnly) {
                    try { com.lifecyclebot.engine.truth.ProbationEntryLimiter6388.recordClose() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }
}
