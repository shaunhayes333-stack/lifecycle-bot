package com.lifecyclebot.engine

/**
 * V5.0.6324 — IMMEDIATE COLLAPSE PROTECTION (operator hotfix §8).
 *
 * Score a live-entry candidate for immediate post-entry collapse risk
 * BEFORE dispatching the actual buy. Existing advisor signals like
 * PROVIDER_PROOF_HOLDER_CASCADE_BLIND / BRAIN_RUGCHECK_FLOOR /
 * MOMENTUM_AVOID now soft-shape size, floor, and probe requirement —
 * they are no longer telemetry-only.
 *
 * Only genuine security failures hard-block. Ordinary uncertainty
 * reduces exposure or redirects to probe / shadow.
 */
object ImmediateCollapseGuard {

    data class SignalSet(
        val priceAgeMs: Long,
        val sourceTimestampInconsistent: Boolean,
        val recentLiquidityChangePct: Double,        // negative = withdrawal
        val recentVolumeQuality: Double,             // 0..1
        val buySellImbalance: Double,                // -1..+1 (+1 = all buys)
        val rapidSellAcceleration: Boolean,
        val topHolderConcentrationPct: Double,       // percentage
        val topHolderMovingOut: Boolean,
        val deployerWalletMoving: Boolean,
        val mintOrFreezeAuthorityLive: Boolean,      // still upgradable = bad
        val lpBurned: Boolean,
        val lpLocked: Boolean,
        val quoteRoutePriceImpactPct: Double,
        val advertisedVsActualLiquidityRatio: Double,   // 1.0 = healthy
        val crossProviderPriceDeviationPct: Double,
        val exhaustionSignature: Boolean,
        val staleScannerEvent: Boolean,
        val tokenMapComplete: Boolean,
        val advisorLabels: Collection<String>,
    )

    data class Verdict(
        val hardBlock: Boolean,
        val probeRequired: Boolean,
        val sizeMultiplier: Double,   // 0.0..1.0 additional shaping
        val floorAdjustment: Double,  // additive score-floor uplift
        val reasons: List<String>,
    )

    fun evaluate(s: SignalSet): Verdict {
        val reasons = mutableListOf<String>()
        var hardBlock = false
        var probe = false
        var sizeMult = 1.0
        var floorAdj = 0.0

        // ── HARD BLOCKS (genuine security failures only) ──────────────
        if (s.mintOrFreezeAuthorityLive) {
            reasons += "MINT_OR_FREEZE_AUTHORITY_LIVE"
            hardBlock = true
        }
        // ── SOFT SHAPING ──────────────────────────────────────────────
        if (s.priceAgeMs > 15_000L) { reasons += "STALE_PRICE_${s.priceAgeMs}ms"; sizeMult *= 0.60; floorAdj += 3.0; probe = true }
        if (s.sourceTimestampInconsistent) { reasons += "SOURCE_TS_INCONSISTENT"; sizeMult *= 0.75; floorAdj += 2.0; probe = true }
        if (s.recentLiquidityChangePct < -15.0) { reasons += "LIQ_WITHDRAWAL_${s.recentLiquidityChangePct.toInt()}pct"; sizeMult *= 0.50; floorAdj += 4.0; probe = true }
        if (s.recentVolumeQuality < 0.35) { reasons += "VOL_QUALITY_${(s.recentVolumeQuality*100).toInt()}"; sizeMult *= 0.75; floorAdj += 2.0 }
        if (s.buySellImbalance < -0.30) { reasons += "SELL_HEAVY_IMBALANCE_${(s.buySellImbalance*100).toInt()}"; sizeMult *= 0.60; floorAdj += 3.0; probe = true }
        if (s.rapidSellAcceleration) { reasons += "RAPID_SELL_ACCEL"; sizeMult *= 0.50; floorAdj += 4.0; probe = true }
        if (s.topHolderConcentrationPct > 45.0) { reasons += "TOP_HOLDER_${s.topHolderConcentrationPct.toInt()}pct"; sizeMult *= 0.70; floorAdj += 3.0 }
        if (s.topHolderMovingOut) { reasons += "TOP_HOLDER_EXITING"; sizeMult *= 0.55; floorAdj += 4.0; probe = true }
        if (s.deployerWalletMoving) { reasons += "DEPLOYER_MOVING"; sizeMult *= 0.55; floorAdj += 4.0; probe = true }
        if (!s.lpBurned && !s.lpLocked) { reasons += "LP_NOT_BURNED_OR_LOCKED"; sizeMult *= 0.75; floorAdj += 3.0 }
        if (s.quoteRoutePriceImpactPct > 12.0) { reasons += "ROUTE_IMPACT_${s.quoteRoutePriceImpactPct.toInt()}pct"; sizeMult *= 0.70; floorAdj += 2.0 }
        if (s.advertisedVsActualLiquidityRatio < 0.70) { reasons += "LIQ_MISMATCH_${(s.advertisedVsActualLiquidityRatio*100).toInt()}pct"; sizeMult *= 0.65; floorAdj += 3.0 }
        if (s.crossProviderPriceDeviationPct > 8.0) { reasons += "CROSS_PROVIDER_DEV_${s.crossProviderPriceDeviationPct.toInt()}pct"; sizeMult *= 0.75; floorAdj += 2.0; probe = true }
        if (s.exhaustionSignature) { reasons += "EXHAUSTION_SIG"; sizeMult *= 0.55; floorAdj += 4.0; probe = true }
        if (s.staleScannerEvent) { reasons += "STALE_SCANNER_EVENT"; sizeMult *= 0.75; floorAdj += 2.0 }
        if (!s.tokenMapComplete) { reasons += "TOKEN_MAP_INCOMPLETE"; sizeMult *= 0.65; floorAdj += 3.0; probe = true }

        // Advisor label soft-shape (spec: PROVIDER_PROOF_HOLDER_CASCADE_BLIND / BRAIN_RUGCHECK_FLOOR / MOMENTUM_AVOID)
        val labels = s.advisorLabels.map { it.uppercase() }
        if (labels.any { it.contains("PROVIDER_PROOF_HOLDER_CASCADE_BLIND") }) { reasons += "ADVISOR_CASCADE_BLIND"; sizeMult *= 0.55; floorAdj += 4.0; probe = true }
        if (labels.any { it.contains("BRAIN_RUGCHECK_FLOOR") }) { reasons += "ADVISOR_RUGCHECK_FLOOR"; sizeMult *= 0.65; floorAdj += 3.0; probe = true }
        if (labels.any { it.contains("MOMENTUM_AVOID") }) { reasons += "ADVISOR_MOMENTUM_AVOID"; sizeMult *= 0.60; floorAdj += 3.0; probe = true }

        sizeMult = sizeMult.coerceIn(0.05, 1.0)
        floorAdj = floorAdj.coerceIn(0.0, 20.0)
        return Verdict(hardBlock, probe, sizeMult, floorAdj, reasons)
    }
}
