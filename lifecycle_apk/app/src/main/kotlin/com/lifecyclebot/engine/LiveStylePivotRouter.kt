package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.3965 — profitability-aware live style pivot router.
 *
 * Never disables lanes. It changes the execution style, size multiplier,
 * confirmation/proof requirements, or defers for recheck when all-in edge is
 * below costs even after pivoting.
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
        val decision: String, // BUY / DEFER / PROBE
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
        var confirm = "ROUTE_PROOF"
        var decision = "BUY"

        val routeTrusted = ts.lastPrice > 0.0 && ts.lastLiquidityUsd > 0.0 && (ts.lastPricePoolAddr.isNotBlank() || ts.pairAddress.isNotBlank())
        val holderProof = try { ts.safety.topHolderPct > 0.0 || ts.holderGrowthRate != 0.0 || ts.peakHolderCount > 0 } catch (_: Throwable) { false }
        val rugProof = try { ts.safety.hardBlockReasons.isEmpty() && !ts.safety.isBlocked } catch (_: Throwable) { false }
        val providerProof = ts.lastPriceSource.isNotBlank() || ts.source.isNotBlank()
        val liq = ts.lastLiquidityUsd
        val scoreBand = scoreBand(score)
        val bleeder = BleederMemoryRouter.statsFor(lane)

        fun defensive(max: Double, reason: String) {
            finalLane = "DEFENSIVE_PROBE"
            finalStyle = "DEFENSIVE_PROBE"
            mult = minOf(mult, max)
            confirm = "SECOND_CYCLE_ROUTE_LIQ_HOLDER_PROOF"
            decision = "PROBE"
            reasons += reason
        }
        fun liquidityDepth(max: Double, reason: String) {
            finalLane = "LIQUIDITY_DEPTH"
            finalStyle = "LIQUIDITY_DEPTH"
            mult = minOf(mult, max)
            confirm = "ROUTE_LIQ_HOLDER_PROOF"
            decision = "PROBE"
            reasons += reason
        }

        if (!basisTrusted) defensive(0.10, "BASIS_UNTRUSTED")
        if (!routeTrusted) defensive(0.10, "ROUTE_PROOF_MISSING")
        if (!rugProof) defensive(0.10, "RUG_PROOF_NOT_CLEAN")

        when (lane) {
            "EXPRESS" -> {
                if (bleeder.provenBleeder || bleeder.n50 == 0 || bleeder.wr50 < 25.0 || bleeder.ev50Pct < 0.0) liquidityDepth(0.15, "EXPRESS_BLEEDER_NATIVE_PIVOT")
                if (!holderProof || !routeTrusted || liq <= 0.0) defensive(0.15, "EXPRESS_REQUIRES_LIQ_ROUTE_HOLDER_PROOF")
            }
            "CYCLIC" -> {
                if (bleeder.provenBleeder || bleeder.wr50 < 25.0 || bleeder.ev50Pct < 0.0) defensive(0.15, "CYCLIC_BLEEDER_NATIVE_PIVOT")
                val trendVolumeConfirms = ts.meta.momScore >= 55.0 && (ts.history.lastOrNull()?.vol ?: ts.meta.volScore) > 0.0
                if (trendVolumeConfirms && routeTrusted) { finalLane = "PULLBACK_RECLAIM"; finalStyle = "PULLBACK_RECLAIM"; reasons += "CYCLIC_TREND_VOLUME_CONFIRMED" }
            }
            "COPYTRADE", "WHALE_FOLLOW" -> {
                val repeatWin = try { StrategyTelemetry.computeLeaderboard().any { it.strategy == "WALLET_RECOVERED" && it.trades >= 5 && it.totalSolPnl > 0.0 && it.winRatePct >= 50.0 } } catch (_: Throwable) { false }
                if (repeatWin && routeTrusted && holderProof && rugProof && basisTrusted) { finalLane = "WALLET_RECOVERED"; finalStyle = "WALLET_RECOVERED"; mult = minOf(mult, 1.0); reasons += "WALLET_RECOVERED_PROVEN_PROMOTION" }
                else defensive(0.15, "WHALE_COPY_NO_DIRECT_FULL_LIVE")
            }
            "SHITCOIN" -> {
                if (liq < 5_000.0 || !routeTrusted) defensive(0.20, "SHITCOIN_THIN_ROUTE_DEPTH")
                val s = BleederMemoryRouter.statsFor("SHITCOIN")
                if (s.n50 < 10 || s.netPnl50Sol <= 0.0) { mult = minOf(mult, 0.35); reasons += "SHITCOIN_FEE_GIVEBACK_AWARE_SIZE" }
            }
            "MOONSHOT" -> {
                if (scoreBand == "S41-60") defensive(0.20, "MOONSHOT_S41_60_DANGER_PIVOT")
                else if (score >= 61.0 && routeTrusted && basisTrusted) reasons += "MOONSHOT_NATIVE_CONFIRMED"
            }
            "BLUECHIP" -> { if (routeTrusted) { mult = maxOf(mult, 1.0); reasons += "BLUECHIP_ROUTE_PROOF_PROMOTED" } }
            "PRESALE_SNIPE", "PROJECT_SNIPER" -> { if (routeTrusted && liq > 0.0) { mult = maxOf(mult, 1.0); reasons += "PRESALE_ROUTE_LIQ_PROMOTED" } }
            "WALLET_RECOVERED" -> { if (!basisTrusted) defensive(0.10, "WALLET_RECOVERED_REQUIRES_TRUSTED_BASIS") else reasons += "WALLET_RECOVERED_TRUSTED_BASIS" }
        }

        if (bleeder.noWinsOverEight) defensive(0.10, "ZERO_WINS_OVER_8")
        if (bleeder.repeatedDeepLoss) defensive(0.10, "THREE_DEEP_LOSSES_LAST50")
        if (bleeder.failedBasisCount > 0 || bleeder.orphanCount > 0) defensive(0.10, "BASIS_OR_ORPHAN_RECENT")

        val be = LiveBreakEvenGuard.check(ts, finalLane, finalStyle, score, buySlippageBps, plannedSizeSol * mult)
        try {
            ForensicLogger.lifecycle(
                "LIVE_BREAK_EVEN_CHECK",
                "mint=${ts.mint.take(10)} lane=$finalLane style=$finalStyle expectedEdge=${"%.1f".format(be.expectedEdgePct)} requiredEdge=${"%.1f".format(be.requiredEdgePct)} decision=${if (be.pass) "PASS" else "PIVOT_OR_DEFER"} pivotReason=${reasons.joinToString("|")}"
            )
            PipelineHealthCollector.labelInc("LIVE_BREAK_EVEN_CHECK")
        } catch (_: Throwable) {}
        if (!be.pass) {
            if (decision == "BUY") {
                finalLane = "DEFENSIVE_PROBE"
                finalStyle = "DEFENSIVE_PROBE"
                mult = minOf(mult, 0.15)
                decision = "PROBE"
                reasons += "BREAK_EVEN_NATIVE_PIVOT"
            }
            val recheck = LiveBreakEvenGuard.check(ts, finalLane, finalStyle, score, buySlippageBps, plannedSizeSol * mult)
            // V5.0.3967 — DO NOT convert every pivot into a hard defer. 3966
            // correctly identified bleeders, but then `BREAK_EVEN_DEFER_RECHECK`
            // vetoed 100/100 live attempts because defensive probes inherit the
            // original lane's low expected edge while also carrying intentionally
            // conservative cost buffers. That turns "pivot, don't disable" into
            // a global live shutdown. If entry basis, route, liquidity, and rug
            // proof are usable, let the probe execute at reduced size; quote,
            // slippage, and executor mechanics remain downstream authorities.
            val hardProofMissing = !basisTrusted || !routeTrusted || !rugProof || liq <= 0.0 || plannedSizeSol <= 0.0
            if (!recheck.pass && hardProofMissing) {
                decision = "DEFER"
                reasons += "BREAK_EVEN_DEFER_RECHECK_PROOF_MISSING"
            } else if (!recheck.pass) {
                decision = "PROBE"
                mult = minOf(mult, 0.15)
                reasons += "BREAK_EVEN_PROBE_ALLOWED_BELOW_COST_MODEL"
                try { PipelineHealthCollector.labelInc("LIVE_STYLE_PIVOT_PROBE_ALLOWED_BELOW_BE") } catch (_: Throwable) {}
            }
            return finish(lane, style, finalLane, finalStyle, mult, confirm, recheck, basisTrusted, routeTrusted, holderProof, rugProof, liq, providerProof, reasons, decision)
        }
        return finish(lane, style, finalLane, finalStyle, mult, confirm, be, basisTrusted, routeTrusted, holderProof, rugProof, liq, providerProof, reasons.ifEmpty { listOf("NATIVE_ALLOWED") }, decision)
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
    ) = Decision(originalLane, originalStyle, finalLane, finalStyle, mult.coerceIn(0.05, 1.25), confirm, be.expectedEdgePct, be.requiredEdgePct, basisTrusted, routeTrusted, holderProof, rugProof, liq, providerProof, finalLane != originalLane || finalStyle != originalStyle || mult < 0.999 || reasons.any { it.contains("PIVOT", true) }, reasons.distinct(), decision)
}
