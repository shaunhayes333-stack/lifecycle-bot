package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.3968 — profitability-aware live style pivot router.
 *
 * No defensive probes. Live mode is for real quality winning setups. Native
 * bleeder contexts are not disabled, but they must be promoted into proven
 * winner styles (BLUECHIP / PRESALE_SNIPE / TREASURY-CASHGEN / WALLET_RECOVERED / high-confidence
 * MOONSHOT / LIQUIDITY_DEPTH_QUALITY / PULLBACK_RECLAIM) with full basis, route,
 * rug, and liquidity proof, or deferred for a better setup.
 */
object LiveStylePivotRouter {
    data class Decision(
        val originalLane: String,
        val originalStyle: String,
        val finalLane: String,
        val finalStyle: String,
        val sizeMultiplier: Double,
        val confirmationRequirement: String,
        val expectedEdgePct: Double,
        val requiredEdgePct: Double,
        val basisTrusted: Boolean,
        val routeTrusted: Boolean,
        val holderProof: Boolean,
        val rugProof: Boolean,
        val liquidityUsd: Double,
        val providerProof: Boolean,
        val pivotApplied: Boolean,
        val reasons: List<String>,
        val decision: String, // BUY / DEFER only. No defensive live probes.
    )

    fun route(
        ts: TokenState,
        originalLaneRaw: String,
        originalStyleRaw: String,
        score: Double,
        plannedSizeSol: Double,
        buySlippageBps: Int,
        basisTrusted: Boolean,
    ): Decision {
        val lane = BleederMemoryRouter.canon(originalLaneRaw.ifBlank { ts.position.tradingMode.ifBlank { ts.source.ifBlank { "STANDARD" } } })
        val style = originalStyleRaw.ifBlank { lane }
        val reasons = mutableListOf<String>()
        var finalLane = lane
        var finalStyle = style
        var mult = 1.0
        var confirm = "QUALITY_ROUTE_LIQ_HOLDER_RUG_BASIS_PROOF"
        var decision = "BUY"

        val providerProof = ts.lastPriceSource.isNotBlank() || ts.source.isNotBlank()
        // V5.0.3984 — align style-router proof with MintEntryMarketSnapshot.
        // 3983 correctly made concrete pool metadata optional for live mint-route
        // execution; keeping pool as mandatory here converted the same condition
        // into LIVE_ENTRY_DEFERRED_BY_STYLE_PIVOT/ROUTE_PROOF_MISSING. Route proof
        // for pre-quote strategy selection is real price + liquidity + provider;
        // Jupiter/executor remains the hard route authority later.
        val routeTrusted = ts.lastPrice > 0.0 && ts.lastLiquidityUsd > 0.0 && providerProof
        val holderProof = try { ts.safety.topHolderPct > 0.0 || ts.holderGrowthRate != 0.0 || ts.peakHolderCount > 0 } catch (_: Throwable) { false }
        val rugProof = try { ts.safety.hardBlockReasons.isEmpty() && !ts.safety.isBlocked } catch (_: Throwable) { false }
        val liq = ts.lastLiquidityUsd
        val scoreBand = scoreBand(score)
        val bleeder = BleederMemoryRouter.statsFor(lane)
        val qualityProof = basisTrusted && routeTrusted && rugProof && providerProof && liq >= 1_000.0
        val highQualityProof = qualityProof && (holderProof || liq >= 15_000.0 || score >= 61.0)

        fun defer(reason: String) {
            decision = "DEFER"
            mult = 0.0
            confirm = "DEFER_RECHECK_WITH_FULL_PROOF"
            reasons += reason
        }
        fun promoteQuality(targetLane: String, targetStyle: String, maxMult: Double, reason: String) {
            finalLane = targetLane
            finalStyle = targetStyle
            mult = minOf(mult, maxMult).coerceAtLeast(0.35)
            decision = "BUY"
            confirm = "QUALITY_ROUTE_LIQ_HOLDER_RUG_BASIS_PROOF"
            reasons += reason
        }
        fun bestQualityLane(): String = when {
            lane == "BLUECHIP" && qualityProof -> "BLUECHIP"
            lane == "PRESALE_SNIPE" && qualityProof -> "PRESALE_SNIPE"
            lane == "PROJECT_SNIPER" && qualityProof -> "PRESALE_SNIPE"
            (lane == "TREASURY" || lane == "CASHGEN") && qualityProof -> "TREASURY"
            lane == "WALLET_RECOVERED" && basisTrusted && routeTrusted && rugProof -> "WALLET_RECOVERED"
            score >= 61.0 && qualityProof -> "MOONSHOT"
            highQualityProof -> "LIQUIDITY_DEPTH_QUALITY"
            qualityProof && score >= 55.0 -> "QUALITY"
            else -> ""
        }
        val liveMaturity = try { LiveMaturityAuthority.snapshot() } catch (_: Throwable) { null }
        val liveAdaptive = liveMaturity?.adaptive == true
        fun liveBootstrapGreen(): Boolean = try {
            val rows = TradeHistoryStore.getRecentValidClosedTrades(limit = 250, includePartials = false)
                .filter { it.side.equals("SELL", true) }
                .filter { it.mode.equals("live", true) || it.tradingMode.equals("live", true) || !it.mode.equals("paper", true) }
                .takeLast(80)
            val decisive = rows.filter { it.pnlPct >= 0.5 || it.pnlPct <= -2.0 }
            val wr = if (decisive.isNotEmpty()) decisive.count { it.pnlPct >= 0.5 } * 100.0 / decisive.size else 0.0
            val net = rows.sumOf { if (it.netPnlSol != 0.0) it.netPnlSol else it.pnlSol }
            rows.size >= 25 && wr >= 35.0 && net > 0.0
        } catch (_: Throwable) { false }
        val bootstrapGreen = liveBootstrapGreen()
        fun cleanProofForRelease(targetLane: String): Boolean {
            val t = BleederMemoryRouter.canon(targetLane)
            val targetAllowed = t in setOf("MOONSHOT", "LIQUIDITY_DEPTH_QUALITY", "PULLBACK_RECLAIM", "PRESALE_SNIPE", "TREASURY", "BLUECHIP", "WALLET_RECOVERED", "QUALITY")
            val hostileBleederNoProof = lane in setOf("EXPRESS", "CYCLIC", "COPYTRADE", "WHALE_FOLLOW") && !qualityProof
            return targetAllowed && !hostileBleederNoProof && basisTrusted && routeTrusted && rugProof && providerProof && liq >= 1_000.0 && score >= 55.0
        }
        fun qualityReleaseMultiplier(targetLane: String): Double {
            val t = BleederMemoryRouter.canon(targetLane)
            val base = when (t) {
                "BLUECHIP", "PRESALE_SNIPE", "TREASURY", "WALLET_RECOVERED" -> 1.00
                "MOONSHOT" -> if (score >= 70.0 && liq >= 5_000.0) 1.00 else 0.85
                "LIQUIDITY_DEPTH_QUALITY", "PULLBACK_RECLAIM", "QUALITY" -> 0.85
                else -> 0.75
            }
            // Low-liq is still size-reduction by doctrine, never a hard block.
            return when {
                liq < 2_500.0 -> minOf(base, 0.65)
                liq < 5_000.0 -> minOf(base, 0.75)
                else -> base
            }
        }
        fun canLiveAdaptiveRelease(targetLane: String): Boolean {
            val cleanHighConfidenceBootstrap = score >= 70.0 && cleanProofForRelease(targetLane)
            return (liveAdaptive || cleanHighConfidenceBootstrap) && cleanProofForRelease(targetLane)
        }
        fun canGreenBootstrapFullQualityRelease(targetLane: String, be: LiveBreakEvenGuard.Result): Boolean {
            if (!bootstrapGreen || !cleanProofForRelease(targetLane)) return false
            if (decision == "DEFER" && reasons.any { it.contains("BASIS", true) || it.contains("ROUTE_PROOF_MISSING", true) || it.contains("RUG", true) }) return false
            val qualityTarget = BleederMemoryRouter.canon(targetLane) in setOf("MOONSHOT", "LIQUIDITY_DEPTH_QUALITY", "PULLBACK_RECLAIM", "PRESALE_SNIPE", "TREASURY", "BLUECHIP", "WALLET_RECOVERED", "QUALITY")
            val edgeCloseEnough = be.expectedEdgePct >= maxOf(8.0, be.requiredEdgePct * 0.25)
            val strongContext = score >= 62.0 || liq >= 5_000.0 || holderProof
            return qualityTarget && edgeCloseEnough && strongContext
        }
        fun pivotThinDepthToQuality(reason: String, maxMult: Double = 0.45): Boolean {
            val target = bestQualityLane()
            return if (target.isNotBlank() && canLiveAdaptiveRelease(target)) {
                promoteQuality(target, target, maxMult, reason)
                true
            } else false
        }

        if (!basisTrusted) defer("BASIS_UNTRUSTED")
        if (!routeTrusted) defer("ROUTE_PROOF_MISSING")
        if (!rugProof) defer("RUG_PROOF_NOT_CLEAN")

        // V5.0.4151 — DUMP REGIME FRESH-LAUNCH PROOF GATE.
        // score>=0/confidence alone cannot authorize a <=15m fresh launch in DUMP.
        // This is WATCH_PROBATION, not a permanent block: scanner keeps tracking and
        // this router re-evaluates when route, volume, reclaim, or smart-wallet proof arrives.
        try {
            val regimeNow = try { RegimeDetector.currentRegime().name } catch (_: Throwable) { "NORMAL" }
            val ageMinutes = ((System.currentTimeMillis() - ts.addedToWatchlistAt).coerceAtLeast(0L) / 60_000.0)
            val sourceBrainMult = try { ScannerSourceBrain.intakeMultiplier(ts.source) } catch (_: Throwable) { 1.0 }
            val sourceCleanWrOk = try {
                val src = ts.source.uppercase()
                StrategyTelemetry.computeLiveTerminalLeaderboard(limit = 1_500)
                    .filter { it.trades >= 10 }
                    .any { it.strategy.uppercase().contains(src) && it.winRatePct >= 35.0 }
            } catch (_: Throwable) { false }
            val exitCapacityOk = liq > 0.0 || ts.tokenMap.pumpFunExecutable
            val microLiqOk = liq >= 5_000.0
            val normalLiqOk = liq >= 25_000.0
            val momentumOk = (ts.meta.momScore >= 6.0 || (ts.momentum ?: 0.0) >= 6.0)
            val volumeOk = ts.meta.volScore >= 6.0
            val pressureOk = ts.lastBuyPressurePct >= 55.0 || ((ts.history.lastOrNull()?.buyRatio ?: 0.0) * 100.0) >= 55.0
            val sourceOk = sourceBrainMult >= 0.75 || sourceCleanWrOk
            val noHardSafety = rugProof && try { ts.safety.hardBlockReasons.isEmpty() && !ts.safety.isBlocked } catch (_: Throwable) { true }
            val dumpFresh = ageMinutes <= 15.0 && regimeNow.equals("DUMP", true)
            val hasDumpProof = routeTrusted && microLiqOk && exitCapacityOk && momentumOk && volumeOk && pressureOk && noHardSafety && sourceOk
            if (dumpFresh && !hasDumpProof) {
                decision = "DEFER"
                mult = 0.0
                finalLane = "WATCH_PROBATION"
                finalStyle = "WATCH_PROBATION"
                confirm = "WATCH_PROBATION_DUMP_FRESH_RECHECK"
                reasons += "DUMP_FRESH_LAUNCH_NO_PROOF"
                reasons += "age=${"%.1f".format(ageMinutes)}m route=$routeTrusted liq=${liq.toInt()} exit=$exitCapacityOk mom=${ts.meta.momScore.toInt()} vol=${ts.meta.volScore.toInt()} buy=${ts.lastBuyPressurePct.toInt()} srcMult=${"%.2f".format(sourceBrainMult)}"
                try { PipelineHealthCollector.labelInc("DUMP_FRESH_LAUNCH_NO_PROOF") } catch (_: Throwable) {}
            } else if (dumpFresh && normalLiqOk) {
                reasons += "DUMP_FRESH_LAUNCH_PROOF_NORMAL_READY"
            } else if (dumpFresh) {
                mult = minOf(mult, 0.35)
                reasons += "DUMP_FRESH_LAUNCH_MICRO_PROOF_ONLY"
            }
        } catch (_: Throwable) { /* fail-open: route/safety gates above still apply */ }

        when (lane) {
            "EXPRESS" -> {
                if (bleeder.provenBleeder || bleeder.n50 == 0 || bleeder.wr50 < 25.0 || bleeder.ev50Pct < 0.0) {
                    val target = bestQualityLane()
                    if (target.isNotBlank()) promoteQuality(target, target, 0.85, "EXPRESS_BLEEDER_QUALITY_PROMOTION")
                    else defer("EXPRESS_BLEEDER_AWAIT_QUALITY_PROOF")
                }
            }
            "CYCLIC" -> {
                val trendVolumeConfirms = ts.meta.momScore >= 55.0 && (ts.history.lastOrNull()?.vol ?: ts.meta.volScore) > 0.0
                if (bleeder.provenBleeder || bleeder.wr50 < 25.0 || bleeder.ev50Pct < 0.0) {
                    val target = bestQualityLane()
                    if (trendVolumeConfirms && highQualityProof) promoteQuality("PULLBACK_RECLAIM", "PULLBACK_RECLAIM", 0.85, "CYCLIC_PULLBACK_RECLAIM_QUALITY_PROMOTION")
                    else if (target.isNotBlank()) promoteQuality(target, target, 0.85, "CYCLIC_BLEEDER_QUALITY_PROMOTION")
                    else defer("CYCLIC_BLEEDER_AWAIT_QUALITY_PROOF")
                }
            }
            "COPYTRADE", "WHALE_FOLLOW" -> {
                val repeatWin = try { StrategyTelemetry.computeLiveTerminalLeaderboard().any { it.strategy == "WALLET_RECOVERED" && it.trades >= 5 && it.totalSolPnl > 0.0 && it.winRatePct >= 50.0 } } catch (_: Throwable) { false }
                if (repeatWin && routeTrusted && holderProof && rugProof && basisTrusted) promoteQuality("WALLET_RECOVERED", "WALLET_RECOVERED", 1.0, "WALLET_RECOVERED_PROVEN_PROMOTION")
                else if (highQualityProof) promoteQuality("LIQUIDITY_DEPTH_QUALITY", "LIQUIDITY_DEPTH_QUALITY", 0.75, "WHALE_COPY_QUALITY_PROMOTION_NO_DIRECT_TRIGGER")
                else defer("WHALE_COPY_AWAIT_REPEAT_WIN_AND_PROOF")
            }
            "SHITCOIN" -> {
                val s = BleederMemoryRouter.statsFor("SHITCOIN")
                if (liq < 1_000.0 || !routeTrusted) defer("SHITCOIN_THIN_ROUTE_DEPTH")
                else if (liq < 5_000.0) {
                    // V5.0.3984 — LIVE_ADAPTIVE tuning. After >=500 live terminal
                    // closes, 1k-5k fresh launches should no longer be treated as
                    // bootstrap data starvation or hard-deferred by default. If proof
                    // is clean, pivot to quality with liquidity-aware reduced size; true sub-1k/route-missing
                    // still defers.
                    if (!pivotThinDepthToQuality("SHITCOIN_THIN_ROUTE_DEPTH_LIVE_ADAPTIVE_REDUCED_QUALITY", 0.45)) defer("SHITCOIN_THIN_ROUTE_DEPTH")
                }
                // V5.0.4016 — IF IT BLEEDS, IT QUARANTINES/PAPER-ROUTES.
                // Bleed handling is not promotion authority. A bad SHITCOIN
                // candidate must never be renamed into MOONSHOT/QUALITY merely
                // because SHITCOIN is bleeding; quality lanes require their own
                // independent admission before bleed handling runs.
                if (s.n50 >= 10 && s.netPnl50Sol <= 0.0) {
                    finalLane = "SHITCOIN"
                    finalStyle = "SHITCOIN"
                    defer("SHITCOIN_LIVE_BLEED_QUARANTINE")
                } else if (s.n50 < 10 && decision != "DEFER") {
                    mult = minOf(mult, if (liveAdaptive) 0.50 else 0.35)
                    reasons += "SHITCOIN_LIVE_ADAPTIVE_FEE_GIVEBACK_AWARE_SIZE"
                }
            }
            "MOONSHOT" -> {
                if (scoreBand == "S41-60") {
                    // V5.0.4153 — SOURCE FIX: MOONSHOT S41-60 is live-toxic.
                    // Operator 22:16 snapshot: losses=49 wins=1 meanPnL=-94.95%,
                    // lane WR=10%, net=-2.114 SOL. Earlier builds kept S55-60 native
                    // release paths alive, so the read-only danger report screamed but
                    // execution still bled. No native MOONSHOT exposure in S41-60 now.
                    // Only independent liquidity-depth quality proof may rescue it into
                    // a DIFFERENT quality lane; otherwise defer/train off executed closes.
                    if (bestQualityLane() == "LIQUIDITY_DEPTH_QUALITY" && highQualityProof) {
                        promoteQuality("LIQUIDITY_DEPTH_QUALITY", "LIQUIDITY_DEPTH_QUALITY", 0.50, "MOONSHOT_S41_60_ONLY_LDQ_QUALITY_RESCUE_V4153")
                    } else {
                        defer("MOONSHOT_S41_60_LIVE_TOXIC_DEFER_V4153")
                    }
                } else if (score >= 61.0 && routeTrusted && basisTrusted) { mult = maxOf(mult, 1.0); reasons += "MOONSHOT_NATIVE_CONFIRMED" }
            }
            "QUALITY" -> {
                if (score < 50.0) defer("QUALITY_LOW_SCORE_LIVE_DEFER")
                else if (routeTrusted && basisTrusted && rugProof) { mult = maxOf(mult, 0.85); reasons += "QUALITY_SCORE50_PLUS_PROMOTED" }
            }
            // V5.0.4118 — MISSING LANE PIVOTS. Operator: "all lanes return to
            // trader and pivot correctly into the right strategies." STANDARD,
            // MANIPULATED, and DIP_HUNTER had NO when-branch — they fell through
            // with no promotion path, starving volume. Now each pivots into its
            // correct AgenticStyleRouter strategy when proof is clean.
            "STANDARD" -> {
                // STANDARD is the default lane for most volume. If proof is clean,
                // let it trade native. If bleeder stats are poor, pivot to quality.
                if (bleeder.provenBleeder || bleeder.weakPerformer) {
                    val target = bestQualityLane()
                    if (target.isNotBlank()) promoteQuality(target, target, 0.85, "STANDARD_BLEEDER_QUALITY_PROMOTION")
                    else { mult = maxOf(mult, 0.65); reasons += "STANDARD_BLEEDER_SIZE_SHAPED_NATIVE" }
                } else if (routeTrusted && basisTrusted && rugProof) {
                    mult = maxOf(mult, 1.0); reasons += "STANDARD_NATIVE_CONFIRMED"
                }
            }
            "MANIPULATED" -> {
                // MANIPULATED pivots into VOLUME_IGNITION_SCALP or NARRATIVE_SOCIAL
                // when proof confirms; size-shapes when bleeder, but never killed.
                if (bleeder.provenBleeder || bleeder.wr50 < 25.0) {
                    val target = bestQualityLane()
                    if (target.isNotBlank() && highQualityProof) promoteQuality(target, target, 0.75, "MANIPULATED_BLEEDER_QUALITY_PROMOTION")
                    else { mult = minOf(mult, 0.55); reasons += "MANIPULATED_BLEEDER_SIZE_SHAPED_NATIVE" }
                } else if (routeTrusted && basisTrusted && rugProof && liq >= 2_000.0) {
                    mult = maxOf(mult, 0.85); reasons += "MANIPULATED_NATIVE_VOLUME_IGNITION_CONFIRMED"
                } else if (!routeTrusted || liq < 2_000.0) {
                    mult = minOf(mult, 0.50); reasons += "MANIPULATED_THIN_DEPTH_SIZE_SHAPED"
                }
            }
            "DIP_HUNTER" -> {
                // DIP_HUNTER pivots into PULLBACK_RECLAIM or PANIC_REVERSION
                // when trend/volume confirms; size-shapes when bleeder.
                val trendVolumeConfirms = ts.meta.momScore >= 45.0
                if (bleeder.provenBleeder || bleeder.wr50 < 25.0) {
                    if (trendVolumeConfirms && highQualityProof) promoteQuality("PULLBACK_RECLAIM", "PULLBACK_RECLAIM", 0.80, "DIP_HUNTER_BLEEDER_PULLBACK_RECLAIM_PROMOTION")
                    else { mult = minOf(mult, 0.55); reasons += "DIP_HUNTER_BLEEDER_SIZE_SHAPED_NATIVE" }
                } else if (routeTrusted && basisTrusted && rugProof) {
                    if (trendVolumeConfirms && liq >= 3_000.0) { mult = maxOf(mult, 0.90); reasons += "DIP_HUNTER_PULLBACK_RECLAIM_CONFIRMED" }
                    else { mult = maxOf(mult, 0.75); reasons += "DIP_HUNTER_NATIVE_CONFIRMED" }
                }
            }
            "BLUECHIP" -> { if (routeTrusted && basisTrusted && rugProof) { mult = maxOf(mult, 1.0); reasons += "BLUECHIP_ROUTE_PROOF_PROMOTED" } }
            "PRESALE_SNIPE", "PROJECT_SNIPER" -> {
                val ps = BleederMemoryRouter.statsFor("PRESALE_SNIPE")
                val presaleBleeding = ps.n20 >= 3 && (ps.wr20 <= 0.0 || ps.ev20Pct < 0.0 || ps.netPnl50Sol <= 0.0)
                if (presaleBleeding) { finalLane = "PRESALE_SNIPE"; finalStyle = "PRESALE_SNIPE"; defer("PRESALE_SNIPE_LIVE_BLEED_QUARANTINE") }
                else if (routeTrusted && liq >= 5_000.0 && basisTrusted && rugProof) { finalLane = "PRESALE_SNIPE"; finalStyle = "PRESALE_SNIPE"; mult = maxOf(mult, 1.0); reasons += "PRESALE_ROUTE_LIQ_PROMOTED" }
                else defer("PRESALE_AWAIT_MIN_DEPTH_AND_PROOF")
            }
            "TREASURY", "CASHGEN" -> { if (routeTrusted && liq >= 5_000.0 && basisTrusted && rugProof && score >= 40.0) { finalLane = "TREASURY"; finalStyle = "TREASURY_CASHGEN"; mult = maxOf(mult, 1.0); reasons += "TREASURY_CASHGEN_QUALITY_PROMOTED" } else defer("TREASURY_CASHGEN_AWAIT_DEPTH_SCORE_PROOF") }
            "WALLET_RECOVERED" -> { if (!basisTrusted) defer("WALLET_RECOVERED_REQUIRES_TRUSTED_BASIS") else reasons += "WALLET_RECOVERED_TRUSTED_BASIS" }
        }

        // V5.0.4151 — MOONSHOT PIVOT-NOT-DISABLE arbiter. This runs before
        // break-even so toxic MOONSHOT contexts are converted to micro/retrain
        // or watch-probation with explicit pivot telemetry instead of being
        // normal-size/live-paused/disabled.
        if (lane == "MOONSHOT") {
            val regime = try { RegimeDetector.currentRegime().name } catch (_: Throwable) { "NORMAL" }
            val arb = MoonshotPivotArbiter.decide(
                ts = ts,
                lane = lane,
                regime = regime,
                score = score,
                routeProof = routeTrusted,
                basisTrusted = basisTrusted,
                rugProof = rugProof,
                holderProof = holderProof,
                liquidityUsd = liq,
                plannedSizeSol = plannedSizeSol,
            )
            reasons += arb.reasons
            reasons += "MOONSHOT_CLEAN_WR=${"%.1f".format(arb.cleanWrPct)}"
            reasons += "MOONSHOT_CLEAN_PNL=${"%+.4f".format(arb.cleanPnlSol)}"
            finalLane = arb.finalLane
            finalStyle = arb.finalStyle
            if (!arb.allowBuy) {
                decision = "DEFER"
                mult = 0.0
                confirm = "WATCH_PROBATION_RECHECK_ROUTE_RECLAIM"
            } else if (arb.sizeCapSol != null) {
                val capMult = (arb.sizeCapSol / plannedSizeSol.coerceAtLeast(0.000001)).coerceIn(0.001, 1.0)
                mult = minOf(mult, capMult)
                confirm = "MICRO_RETRAIN_ROUTE_PROOF"
            }
        }

        val targetAfterLane = bestQualityLane()
        if (bleeder.noWinsOverEight && targetAfterLane.isBlank()) defer("ZERO_WINS_OVER_8_AWAIT_QUALITY_PROOF")
        if (bleeder.repeatedDeepLoss && targetAfterLane.isBlank()) defer("THREE_DEEP_LOSSES_LAST50_AWAIT_QUALITY_PROOF")
        if ((bleeder.failedBasisCount > 0 || bleeder.orphanCount > 0) && !basisTrusted) defer("BASIS_OR_ORPHAN_RECENT_AWAIT_BASIS")

        val be = LiveBreakEvenGuard.check(ts, finalLane, finalStyle, score, buySlippageBps, plannedSizeSol * mult)
        try {
            ForensicLogger.lifecycle(
                "LIVE_BREAK_EVEN_CHECK",
                "mint=${ts.mint.take(10)} lane=$finalLane style=$finalStyle expectedEdge=${"%.1f".format(be.expectedEdgePct)} requiredEdge=${"%.1f".format(be.requiredEdgePct)} decision=${if (be.pass) "PASS" else "DEFER_QUALITY_EDGE"} pivotReason=${reasons.joinToString("|")}"
            )
            PipelineHealthCollector.labelInc("LIVE_BREAK_EVEN_CHECK")
        } catch (_: Throwable) {}
        if (!be.pass) {
            val adaptiveRelease = canLiveAdaptiveRelease(finalLane) &&
                be.expectedEdgePct >= (be.requiredEdgePct * 0.55) &&
                (score >= 61.0 || liq >= 5_000.0 || finalLane in setOf("WALLET_RECOVERED", "BLUECHIP", "PRESALE_SNIPE", "TREASURY", "QUALITY"))
            val bootstrapRelease = canGreenBootstrapFullQualityRelease(finalLane, be)
            if (adaptiveRelease || bootstrapRelease) {
                // LIVE quality release: do not let a green live-bootstrap system
                // self-starve because lane-local edge samples are still sparse. Clean
                // proof + quality target + bounded edge gap returns to real quality
                // sizing, so the live policy can compound from real closes. This is NOT
                // a safety bypass: basis/route/rug/provider proof remain mandatory above.
                decision = "BUY"
                val qualityMult = qualityReleaseMultiplier(finalLane)
                mult = maxOf(if (mult <= 0.0) qualityMult else mult, qualityMult).coerceIn(0.65, 1.00)
                reasons += if (bootstrapRelease) "BREAK_EVEN_GREEN_BOOTSTRAP_FULL_QUALITY_RELEASE" else "BREAK_EVEN_LIVE_ADAPTIVE_FULL_QUALITY_RELEASE"
            } else {
                decision = "DEFER"
                mult = 0.0
                reasons += "BREAK_EVEN_DEFER_QUALITY_EDGE_NOT_CONFIRMED"
            }
        }
        return finish(lane, style, finalLane, finalStyle, mult, confirm, be, basisTrusted, routeTrusted, holderProof, rugProof, liq, providerProof, reasons.ifEmpty { listOf("NATIVE_QUALITY_ALLOWED") }, decision)
    }

    fun scoreBand(score: Double): String = when {
        score < 21.0 -> "S0-20"
        score < 41.0 -> "S21-40"
        score < 61.0 -> "S41-60"
        score < 81.0 -> "S61-80"
        else -> "S81-100"
    }

    private fun finish(
        originalLane: String, originalStyle: String, finalLane: String, finalStyle: String,
        mult: Double, confirm: String, be: LiveBreakEvenGuard.Result,
        basisTrusted: Boolean, routeTrusted: Boolean, holderProof: Boolean, rugProof: Boolean,
        liq: Double, providerProof: Boolean, reasons: List<String>, decision: String,
    ) = Decision(
        originalLane, originalStyle, finalLane, finalStyle,
        if (decision == "DEFER") 0.0 else mult.coerceIn(0.001, 1.25),
        confirm, be.expectedEdgePct, be.requiredEdgePct, basisTrusted, routeTrusted,
        holderProof, rugProof, liq, providerProof,
        finalLane != originalLane || finalStyle != originalStyle || mult < 0.999 || reasons.any { it.contains("PROMOTION", true) || it.contains("PIVOT", true) },
        reasons.distinct(), decision,
    )
}
