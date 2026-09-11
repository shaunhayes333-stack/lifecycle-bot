package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade

/**
 * V5.9.434 — Central journal recorder for V3 meme sub-traders.
 * recordOpen/recordClose are direct-journal fallbacks for trader paths that
 * BYPASS Executor. Executor-routed economic entries/exits must have one writer.
 */
object V3JournalRecorder {

    private val recentCloseDedup = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val CLOSE_DEDUP_MS = 60_000L
    private const val EXECUTOR_PROOF_WINDOW_MS_6699 = 20_000L

    /**
     * V5.0.6699 — CYCLIC uses Executor.treasuryBuy, which already commits the
     * canonical position and durable BUY journal row. Runtime 5.0.6698 showed
     * the second V3 recordOpen BUY seconds later with an incompatible quantity.
     * Suppress only when a current canonical open AND a recent durable executor
     * row carrying a real positionId prove that the economic BUY already exists.
     */
    private fun executorAlreadyJournaledCyclicOpen6699(mint: String, isPaper: Boolean, layer: String): Boolean {
        if (!layer.equals("CYCLIC", ignoreCase = true) || mint.isBlank()) return false
        val mode = if (isPaper) "paper" else "live"
        val canonicalOpen = try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions().any {
                it.mint == mint && it.mode.equals(mode, ignoreCase = true) &&
                    it.remainingQtyRaw > java.math.BigInteger.ZERO
            }
        } catch (_: Throwable) { false }
        if (!canonicalOpen) return false
        val now = System.currentTimeMillis()
        val durableBuy = try {
            TradeHistoryStore.getAllValidTradesSnapshot(limit = 160).any { t ->
                t.mint == mint && t.mode.equals(mode, ignoreCase = true) &&
                    t.side.equals("BUY", ignoreCase = true) &&
                    t.positionId.isNotBlank() &&
                    now - t.ts in 0L..EXECUTOR_PROOF_WINDOW_MS_6699
            }
        } catch (_: Throwable) { false }
        if (durableBuy) emitExecutorSupersession6699(mint, mode, layer, "BUY")
        return durableBuy
    }

    /**
     * V5.0.6699 — CYCLIC closeCycle calls Executor.paperSell/requestSell and only
     * proceeds after CONFIRMED/PAPER_CONFIRMED. The old follow-up recordClose then
     * manufactured a second SELL journal row. Suppress it only when canonical
     * CLOSED inventory plus a recent durable terminal row with a real positionId
     * proves the executor has already written the economic close.
     */
    private fun executorAlreadyJournaledCyclicClose6699(mint: String, isPaper: Boolean, layer: String): Boolean {
        if (!layer.equals("CYCLIC", ignoreCase = true) || mint.isBlank()) return false
        val mode = if (isPaper) "paper" else "live"
        val canonicalClosed = try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.closedPositions().any {
                it.mint == mint && it.mode.equals(mode, ignoreCase = true) &&
                    it.remainingQtyRaw == java.math.BigInteger.ZERO
            }
        } catch (_: Throwable) { false }
        if (!canonicalClosed) return false
        val now = System.currentTimeMillis()
        val durableSell = try {
            TradeHistoryStore.getAllValidTradesSnapshot(limit = 160).any { t ->
                t.mint == mint && t.mode.equals(mode, ignoreCase = true) &&
                    (t.side.equals("SELL", ignoreCase = true) || t.side.equals("PARTIAL_SELL", ignoreCase = true)) &&
                    t.positionId.isNotBlank() &&
                    now - t.ts in 0L..EXECUTOR_PROOF_WINDOW_MS_6699
            }
        } catch (_: Throwable) { false }
        if (durableSell) emitExecutorSupersession6699(mint, mode, layer, "SELL")
        return durableSell
    }

    private fun emitExecutorSupersession6699(mint: String, mode: String, layer: String, side: String) {
        try {
            PipelineHealthCollector.labelInc("V3_${side}_JOURNAL_SUPERSEDED_BY_EXECUTOR_6699")
            ForensicLogger.lifecycle(
                "V3_JOURNAL_SUPERSEDED_BY_EXECUTOR_6699",
                "mint=${mint.take(10)} mode=${mode.uppercase()} layer=$layer side=$side action=skip_duplicate_economic_writer canonicalProof=true durablePositionRow=true",
            )
        } catch (_: Throwable) {}
    }

    /** Record a BUY only for V3 paths that do not already have an Executor BUY. */
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
        if (executorAlreadyJournaledCyclicOpen6699(mint, isPaper, layer)) return
        try {
            val t = Trade(
                side = "BUY",
                mode = if (isPaper) "paper" else "live",
                sol = sizeSol,
                price = entryPrice,
                ts = System.currentTimeMillis(),
                reason = if (entryReason.isBlank()) "${layer}_ENTRY" else "${layer}_$entryReason",
                pnlSol = 0.0,
                pnlPct = 0.0,
                netPnlSol = 0.0,
                score = entryScore.toDouble(),
                tradingMode = layer,
                tradingModeEmoji = layerEmoji(layer),
                mint = mint,
                entryTsMs = System.currentTimeMillis(),
                entryPriceSnapshot = entryPrice,
                entryCostSol = sizeSol,
                entryQtyToken = if (entryPrice > 0.0 && sizeSol > 0.0) sizeSol / entryPrice else 0.0,
                remainingQtyToken = if (entryPrice > 0.0 && sizeSol > 0.0) sizeSol / entryPrice else 0.0,
                entryPriceSource = layer,
            )
            TradeHistoryStore.recordTrade(t)
            try {
                if (mint.isNotBlank() && entryPrice > 0.0) {
                    TokenLifecycleTracker.onBuyPending(
                        mint = mint, symbol = symbol, venue = layer, sizeSol = sizeSol,
                    )
                    TokenLifecycleTracker.recordEntryMetadata(
                        mint = mint, entryPriceSol = entryPrice, entryDecimals = 6,
                    )
                }
            } catch (_: Throwable) {}
            try {
                if (!isPaper && mint.isNotBlank() && entryPrice > 0.0 && sizeSol > 0.0) {
                    val wallet = try { WalletManager.currentPubkey() } catch (_: Throwable) { "" }
                    val positionId = com.lifecyclebot.engine.truth.PositionIdentity6395
                        .register(wallet = wallet, mint = mint, lane = layer)
                    val signature = "V3_BUY_${System.currentTimeMillis()}_${mint.take(8)}"
                    val tokenUiReceived = sizeSol / entryPrice
                    val rawReceived = java.math.BigInteger.valueOf(
                        (tokenUiReceived * 1_000_000.0).toLong().coerceAtLeast(0L)
                    )
                    com.lifecyclebot.engine.truth.BuyFillLedger6388.record(
                        com.lifecyclebot.engine.truth.BuyFillRecord6388(
                            fillId = "bf_$signature",
                            positionId = positionId, mint = mint, symbol = symbol,
                            lane = layer, tactic = "V3_ENTRY", strategy = layer,
                            executionAuthority = "V3_JOURNAL_6395",
                            governorState = try { LiveEntrySafetyHold.currentGovernorState().name } catch (_: Throwable) { "BASELINE" },
                            recoveryState = try { com.lifecyclebot.engine.truth.GovernorRecovery6388.state().name } catch (_: Throwable) { "BASELINE" },
                            evidenceEpoch = com.lifecyclebot.engine.truth.EvidenceEpochFilter6388.EPOCH,
                            signature = signature, slot = 0L, blockTime = System.currentTimeMillis(),
                            requestedSol = sizeSol, actualSolSpentGross = sizeSol,
                            networkFeeSol = 0.0001, priorityFeeSol = 0.0001, platformFeeSol = 0.0,
                            actualSolSpentNet = sizeSol - 0.0002,
                            tokenRawReceived = rawReceived, tokenUiReceived = tokenUiReceived,
                            tokenDecimals = 6, effectiveEntryPriceUsd = entryPrice,
                            marketCapAtEntryUsd = 0.0, liquidityAtEntryUsd = 0.0,
                            quoteProvider = layer, executionRoute = layer,
                            slippageBps = 100, finality = "FINALIZED",
                            runtimeGeneration = try { BotRuntimeController.currentGeneration() } catch (_: Throwable) { 0L },
                            createdAtMs = System.currentTimeMillis(),
                        )
                    )
                }
            } catch (_: Throwable) {}
            ErrorLogger.info("V3JournalRecorder",
                "📓 [$layer] BUY $symbol @ ${"%.6f".format(entryPrice)} | size=${"%.4f".format(sizeSol)}◎ | score=$entryScore")
        } catch (e: Exception) {
            ErrorLogger.error("V3JournalRecorder", "⚠️ JOURNAL OPEN FAILED for $symbol ($layer): ${e.message}", e)
        }
    }

    private fun layerEmoji(layer: String): String = when (layer.uppercase()) {
        "SHITCOIN" -> "💩"
        "SHITCOINEXPRESS", "EXPRESS" -> "🎫"
        "MOONSHOT" -> "🚀"
        "BLUECHIP" -> "💎"
        "CASHGEN", "CASHGENERATION" -> "💰"
        "MANIPULATED" -> "🎭"
        "QUALITY" -> "⭐"
        "LAB", "LLMLAB" -> "🧪"
        "STALE_REFUND", "EXPIRED_REFUND" -> "♻️"
        else -> "📈"
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
        layer: String,
        exitReason: String,
        entryScore: Int = 0,
        holdMinutes: Long = 0L,
        peakGainPct: Double = 0.0,
        isCanonicalFinalized: Boolean = false,
    ) {
        if (executorAlreadyJournaledCyclicClose6699(mint, isPaper, layer)) return

        if (isCanonicalFinalized) {
            try {
                val fam = symbol.uppercase().trim().filter { it.isLetterOrDigit() }.take(8)
                ReEntryLockout.onClose(mint, fam, exitReason, pnlPct)
            } catch (_: Throwable) {}
        }

        val dedupNow = System.currentTimeMillis()
        val lastClose = recentCloseDedup[mint]
        if (lastClose != null && dedupNow - lastClose < CLOSE_DEDUP_MS) {
            ErrorLogger.debug("V3JournalRecorder",
                "DEDUP_SKIP $symbol ${layer}_${exitReason}: closed ${dedupNow - lastClose}ms ago")
            return
        }
        recentCloseDedup[mint] = dedupNow

        val pnlPctLearn: Double = run {
            val lo = -100.0
            val hi = 5000.0
            when {
                pnlPct.isNaN() || pnlPct.isInfinite() -> {
                    try { PipelineHealthCollector.labelInc("LEARNING_PNL_ARTIFACT_DROPPED|reason=NAN_INF") } catch (_: Throwable) {}
                    0.0
                }
                pnlPct > hi -> {
                    try { PipelineHealthCollector.labelInc("LEARNING_PNL_CLAMPED|reason=OUTLIER_HIGH") } catch (_: Throwable) {}
                    ErrorLogger.warn("V3JournalRecorder",
                        "🧯 PNL_OUTLIER_CLAMPED $symbol ($layer): raw=${"%.0f".format(pnlPct)}% → ${hi}% (feed artifact; journal keeps raw)")
                    hi
                }
                pnlPct < lo -> {
                    try { PipelineHealthCollector.labelInc("LEARNING_PNL_CLAMPED|reason=OUTLIER_LOW") } catch (_: Throwable) {}
                    lo
                }
                else -> pnlPct
            }
        }

        var wrote = false
        try {
            val t = Trade(
                side = "SELL",
                mode = if (isPaper) "paper" else "live",
                sol = sizeSol,
                price = exitPrice,
                ts = System.currentTimeMillis(),
                reason = "${layer}_${exitReason}",
                pnlSol = pnlSol,
                pnlPct = pnlPct,
                netPnlSol = pnlSol,
                score = entryScore.toDouble(),
                tradingMode = layer,
                tradingModeEmoji = layerEmoji(layer),
                mint = mint,
                entryPriceSnapshot = entryPrice,
                entryCostSol = sizeSol,
                entryQtyToken = if (entryPrice > 0.0 && sizeSol > 0.0) sizeSol / entryPrice else 0.0,
                soldQtyToken = if (entryPrice > 0.0 && sizeSol > 0.0) sizeSol / entryPrice else 0.0,
                entryPriceSource = layer,
            )
            TradeHistoryStore.recordTrade(t)
            wrote = true
            ErrorLogger.info("V3JournalRecorder",
                "📓 [$layer] $symbol $exitReason | pnl=${"%+.2f".format(pnlPct)}% (${"%+.4f".format(pnlSol)} SOL) | score=$entryScore hold=${holdMinutes}m")
        } catch (e: Exception) {
            ErrorLogger.error("V3JournalRecorder",
                "⚠️ JOURNAL WRITE FAILED for $symbol ($layer/$exitReason): ${e.message}", e)
        }

        if (wrote) {
            val skewTainted6373: Boolean = try {
                val buySnap = try { TradeHistoryStore.getLatestBuyByMintSnapshot()[mint] } catch (_: Throwable) { null }
                val buyQty = buySnap?.entryQtyToken ?: 0.0
                val sellQty = if (entryPrice > 0.0 && sizeSol > 0.0) sizeSol / entryPrice else 0.0
                if (buyQty > 0.0 && sellQty > 0.0) {
                    val ratio = maxOf(buyQty, sellQty) / minOf(buyQty, sellQty)
                    if (ratio > 10.0 && pnlPctLearn <= -80.0) {
                        try {
                            PipelineHealthCollector.labelInc("SKEW_TAINT_LEARNING_QUARANTINE_6373|lane=$layer")
                            ForensicLogger.lifecycle(
                                "SKEW_TAINT_LEARNING_QUARANTINE_6373",
                                "mint=${mint.take(10)} sym=$symbol layer=$layer buyQty=${"%.2f".format(buyQty)} sellQty=${"%.2f".format(sellQty)} ratio=${"%.1f".format(ratio)}× pnl=${"%.1f".format(pnlPctLearn)}% — decimal mismatch, learners skipped",
                            )
                        } catch (_: Throwable) {}
                        true
                    } else false
                } else false
            } catch (_: Throwable) { false }
            if (skewTainted6373) return

            val invalidStrategyReason6568 = listOf(
                "STALE", "RESTORED", "REPLAY", "DECIMAL", "ORPHAN", "PHANTOM",
                "UNRESOLVED_BASIS", "ADMINISTRATIVE", "SYNTHETIC_CLOSE",
            ).any { exitReason.uppercase().contains(it) }
            val strategyEligible6568 = isCanonicalFinalized && !invalidStrategyReason6568 &&
                com.lifecyclebot.engine.truth.PaperLearningEligibility6519.decision(null, mint).eligible
            if (!strategyEligible6568) try {
                PipelineHealthCollector.labelInc("JOURNAL_STRATEGY_LEARNING_QUARANTINED_6568")
            } catch (_: Throwable) {}
            if (strategyEligible6568) {
                try { ScoreExpectancyTracker.record(layer, entryScore, pnlPctLearn) } catch (_: Exception) {}
                try { HoldDurationTracker.record(layer, holdMinutes, pnlPctLearn) } catch (_: Exception) {}
                try { ExitReasonTracker.record(layer, exitReason, pnlPctLearn) } catch (_: Exception) {}
                try {
                    val peakSane = when {
                        peakGainPct.isNaN() || peakGainPct.isInfinite() -> 0.0
                        peakGainPct > 5000.0 -> 5000.0
                        peakGainPct < 0.0 -> 0.0
                        else -> peakGainPct
                    }
                    if (peakSane > 0.0) {
                        val giveBack = peakSane - pnlPctLearn
                        PipelineHealthCollector.recordMfe(layer, peakSane, pnlPctLearn)
                        if (peakSane >= 20.0 && giveBack >= 25.0) {
                            PipelineHealthCollector.labelInc("MFE_RUNNER_GIVEBACK|lane=$layer")
                        }
                    }
                } catch (_: Throwable) {}
                try {
                    com.lifecyclebot.engine.learning.LaneExitTuner.recordClose(
                        lane = layer, pnlPct = pnlPctLearn, peakPct = peakGainPct, exitReason = exitReason,
                    )
                } catch (_: Throwable) {}
                try {
                    val isWinL = pnlPctLearn > 0.5
                    val isLossL = pnlPctLearn < -0.5
                    val bandL = LosingPatternMemory.scoreBand(entryScore)
                    com.lifecyclebot.engine.learning.LanePolicy.recordOutcome(layer, bandL, isWinL, isLossL)
                    com.lifecyclebot.engine.learning.RetrainingDecay.noteOutcome(layer, bandL, isWinL, isLossL, pnlPctLearn)
                    com.lifecyclebot.engine.learning.ExplorationBudget.onLaneOutcome(layer, pnlPctLearn)
                } catch (_: Exception) {}
            }

            try {
                if (!isPaper && mint.isNotBlank() && sizeSol > 0.0) {
                    val wallet = try { WalletManager.currentPubkey() } catch (_: Throwable) { "" }
                    val positionId = com.lifecyclebot.engine.truth.PositionIdentity6395
                        .register(wallet = wallet, mint = mint, lane = layer)
                    val signature = "V3_SELL_${System.currentTimeMillis()}_${mint.take(8)}"
                    val proceedsSol = sizeSol * (1.0 + pnlPctLearn / 100.0)
                    val rawConsumed = java.math.BigInteger.valueOf(
                        (sizeSol / exitPrice.coerceAtLeast(1e-12) * 1_000_000.0).toLong().coerceAtLeast(0L)
                    )
                    val buys = com.lifecyclebot.engine.truth.BuyFillLedger6388.forPosition(positionId)
                    if (buys.isNotEmpty()) {
                        val totalBuyRaw = buys.fold(java.math.BigInteger.ZERO) { acc, b -> acc.add(b.tokenRawReceived) }
                        // V5.0.6732 §SKEW_QUARANTINE_RECORDEXEC_ROOT — the 6731
                        // dump showed 141 quarantines in 43 trades (≈3.3 per
                        // trade!). The `rawConsumed` above is a fabricated
                        // proxy: `sizeSol / exitPrice * 1_000_000` assumes 6
                        // decimals and uses the exit price for reconstruction.
                        // Buy raw is the actual on-chain quantity captured at
                        // entry with the token's real decimals. Comparing the
                        // two guarantees a decimal-shift mismatch for any
                        // token whose decimals ≠ 6 (i.e. ~all non-meme). The
                        // legitimate assertion for a V3 full close is:
                        //   sold_raw == total_buy_raw (position closed).
                        // Route the guard on that canonical identity instead
                        // of the fabricated proxy so real skew still emits
                        // QTY_DECIMAL_SKEW but a full-close never triggers a
                        // false-positive quarantine.
                        val v = com.lifecyclebot.engine.truth.QuantityIntegrityGuard6395.check(
                            totalBuyRaw = totalBuyRaw,
                            cumulativeSellRaw = totalBuyRaw,          // ← full-close identity
                            remainingRaw = java.math.BigInteger.ZERO,
                            hasVerifiedPartialHistory = false,
                        )
                        if (!v.ok) {
                            com.lifecyclebot.engine.truth.CanonicalPerformanceFilter6395.quarantine(
                                positionId,
                                com.lifecyclebot.engine.truth.CanonicalPerformanceFilter6395.QuarantineReason.QTY_DECIMAL_SKEW,
                            )
                        }
                    }
                    com.lifecyclebot.engine.truth.SellFillLedger6388.record(
                        com.lifecyclebot.engine.truth.SellFillRecord6388(
                            fillId = "sf_$signature", positionId = positionId,
                            mint = mint, symbol = symbol, signature = signature,
                            slot = 0L, blockTime = System.currentTimeMillis(),
                            exitIntentId = com.lifecyclebot.engine.truth.PositionIdentity6395.openOrGetExitIntent(positionId, layer),
                            exitReason = if (pnlPctLearn >= 0) "PROFIT" else "STOP",
                            requestedRaw = rawConsumed, requestedUi = rawConsumed.toDouble() / 1_000_000.0,
                            actualConsumedRaw = rawConsumed,
                            actualConsumedUi = rawConsumed.toDouble() / 1_000_000.0,
                            preBalanceRaw = rawConsumed, postBalanceRaw = java.math.BigInteger.ZERO,
                            remainingRaw = java.math.BigInteger.ZERO, tokenDecimals = 6,
                            solReceivedGross = proceedsSol, networkFeeSol = 0.0001,
                            priorityFeeSol = 0.0001, platformFeeSol = 0.0,
                            solReceivedNet = proceedsSol - 0.0002,
                            allocatedCostBasisSol = sizeSol,
                            realisedPnlSol = proceedsSol - sizeSol - 0.0002,
                            realisedPnlPct = pnlPctLearn, fillSequence = 1,
                            sourceRoute = layer, quoteProvider = layer,
                            slippageBps = 100, finality = "FINALIZED",
                            runtimeGeneration = try { BotRuntimeController.currentGeneration() } catch (_: Throwable) { 0L },
                            evidenceEpoch = com.lifecyclebot.engine.truth.EvidenceEpochFilter6388.EPOCH,
                            createdAtMs = System.currentTimeMillis(),
                        )
                    )
                    com.lifecyclebot.engine.truth.PositionIdentity6395.closeExitIntent(positionId)
                    com.lifecyclebot.engine.truth.CanonicalTradeAggregator6388.aggregate(
                        positionId = positionId, mint = mint, symbol = symbol,
                        lane = layer, tactic = "V3", strategy = layer,
                        executionAuthority = "V3_JOURNAL_6394",
                        governorState = try { LiveEntrySafetyHold.currentGovernorState().name } catch (_: Throwable) { "BASELINE" },
                        recoveryState = try { com.lifecyclebot.engine.truth.GovernorRecovery6388.state().name } catch (_: Throwable) { "BASELINE" },
                        evidenceEpoch = com.lifecyclebot.engine.truth.EvidenceEpochFilter6388.EPOCH,
                        finalExitReason = if (pnlPctLearn >= 0) "PROFIT" else "STOP",
                        openedAtMs = System.currentTimeMillis() - 60_000L,
                        closedAtMs = System.currentTimeMillis(),
                        maximumGainPct = pnlPctLearn.coerceAtLeast(0.0),
                        maximumDrawdownPct = if (pnlPctLearn < 0) -pnlPctLearn else 0.0,
                        runtimeGeneration = try { BotRuntimeController.currentGeneration() } catch (_: Throwable) { 0L },
                    )
                    val closeId = "canon_$signature"
                    val strategyKey = "GLOBAL:LIVE:${layer.uppercase()}"
                    com.lifecyclebot.engine.truth.Trade1AdaptiveTuner6393.applyClose(
                        canonicalCloseId = closeId, strategyKey = strategyKey,
                        netReturnPct = pnlPctLearn,
                        isRug = pnlPctLearn <= -50.0,
                    )
                }
            } catch (_: Throwable) {}

            try {
                val liveOnly = !isPaper
                val evidenceEpoch = if (liveOnly) com.lifecyclebot.engine.truth.EvidenceEpochFilter6388.EPOCH else 0
                val evidencePnlSol = try {
                    if (sizeSol > 0.0) sizeSol * (pnlPctLearn / 100.0) else 0.0
                } catch (_: Throwable) { 0.0 }
                com.lifecyclebot.engine.truth.PostFixEvidenceCollector6388.recordCanonicalClose(
                    evidenceEpoch = evidenceEpoch, pnlSol = evidencePnlSol,
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
                if (liveOnly) try { com.lifecyclebot.engine.truth.ProbationEntryLimiter6388.recordClose() } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
    }
}
