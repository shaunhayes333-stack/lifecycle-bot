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

        val routeTrusted = ts.lastPrice > 0.0 && ts.lastLiquidityUsd > 0.0 && (ts.lastPricePoolAddr.isNotBlank() || ts.pairAddress.isNotBlank())
        val holderProof = try { ts.safety.topHolderPct > 0.0 || ts.holderGrowthRate != 0.0 || ts.peakHolderCount > 0 } catch (_: Throwable) { false }
        val rugProof = try { ts.safety.hardBlockReasons.isEmpty() && !ts.safety.isBlocked } catch (_: Throwable) { false }
        val providerProof = ts.lastPriceSource.isNotBlank() || ts.source.isNotBlank()
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
            else -> ""
        }

        if (!basisTrusted) defer("BASIS_UNTRUSTED")
        if (!routeTrusted) defer("ROUTE_PROOF_MISSING")
        if (!rugProof) defer("RUG_PROOF_NOT_CLEAN")

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
                val repeatWin = try { StrategyTelemetry.computeLeaderboard().any { it.strategy == "WALLET_RECOVERED" && it.trades >= 5 && it.totalSolPnl > 0.0 && it.winRatePct >= 50.0 } } catch (_: Throwable) { false }
                if (repeatWin && routeTrusted && holderProof && rugProof && basisTrusted) promoteQuality("WALLET_RECOVERED", "WALLET_RECOVERED", 1.0, "WALLET_RECOVERED_PROVEN_PROMOTION")
                else if (highQualityProof) promoteQuality("LIQUIDITY_DEPTH_QUALITY", "LIQUIDITY_DEPTH_QUALITY", 0.75, "WHALE_COPY_QUALITY_PROMOTION_NO_DIRECT_TRIGGER")
                else defer("WHALE_COPY_AWAIT_REPEAT_WIN_AND_PROOF")
            }
            "SHITCOIN" -> {
                val s = BleederMemoryRouter.statsFor("SHITCOIN")
                if (liq < 5_000.0 || !routeTrusted) defer("SHITCOIN_THIN_ROUTE_DEPTH")
                if (s.n50 < 10 || s.netPnl50Sol <= 0.0) { mult = minOf(mult, 0.35); reasons += "SHITCOIN_FEE_GIVEBACK_AWARE_SIZE" }
            }
            "MOONSHOT" -> {
                if (scoreBand == "S41-60") {
                    if (bestQualityLane() == "LIQUIDITY_DEPTH_QUALITY" && highQualityProof) promoteQuality("LIQUIDITY_DEPTH_QUALITY", "LIQUIDITY_DEPTH_QUALITY", 0.75, "MOONSHOT_S41_60_QUALITY_PROMOTION")
                    else defer("MOONSHOT_S41_60_DANGER_DEFER")
                } else if (score >= 61.0 && routeTrusted && basisTrusted) { mult = maxOf(mult, 1.0); reasons += "MOONSHOT_NATIVE_CONFIRMED" }
            }
            "BLUECHIP" -> { if (routeTrusted && basisTrusted && rugProof) { mult = maxOf(mult, 1.0); reasons += "BLUECHIP_ROUTE_PROOF_PROMOTED" } }
            "PRESALE_SNIPE", "PROJECT_SNIPER" -> { if (routeTrusted && liq > 0.0 && basisTrusted && rugProof) { finalLane = "PRESALE_SNIPE"; finalStyle = "PRESALE_SNIPE"; mult = maxOf(mult, 1.0); reasons += "PRESALE_ROUTE_LIQ_PROMOTED" } }
            "TREASURY", "CASHGEN" -> { if (routeTrusted && liq > 0.0 && basisTrusted && rugProof) { finalLane = "TREASURY"; finalStyle = "TREASURY_CASHGEN"; mult = maxOf(mult, 1.0); reasons += "TREASURY_CASHGEN_QUALITY_PROMOTED" } }
            "WALLET_RECOVERED" -> { if (!basisTrusted) defer("WALLET_RECOVERED_REQUIRES_TRUSTED_BASIS") else reasons += "WALLET_RECOVERED_TRUSTED_BASIS" }
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
            decision = "DEFER"
            mult = 0.0
            reasons += "BREAK_EVEN_DEFER_QUALITY_EDGE_NOT_CONFIRMED"
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
        if (decision == "DEFER") 0.0 else mult.coerceIn(0.35, 1.25),
        confirm, be.expectedEdgePct, be.requiredEdgePct, basisTrusted, routeTrusted,
        holderProof, rugProof, liq, providerProof,
        finalLane != originalLane || finalStyle != originalStyle || mult < 0.999 || reasons.any { it.contains("PROMOTION", true) || it.contains("PIVOT", true) },
        reasons.distinct(), decision,
    )
}
