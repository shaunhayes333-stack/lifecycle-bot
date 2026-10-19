package com.lifecyclebot.engine.market

import com.lifecyclebot.engine.truth.CandidateAdmission8136

/** Discovery priority from present observations and an already evaluated, lane-specific economic record. */
object DiscoveryQuality8239 {
    data class Input(val observedAtMs: Long, val priceUsd: Double, val liquidityUsd: Double,
                     val change5mPct: Double, val buyPressurePct: Double, val liquidityDeltaPct: Double,
                     val nativeJudgement: Boolean = false,
                     val admission: CandidateAdmission8136.Ordering8239? = null)
    data class Priority(val tier: Int, val netLowerPct: Double, val reason: String)

    /** Unknown inputs reserve hydration attention; activity cannot outrank fresh supported net edge. */
    fun priority(input: Input, nowMs: Long): Priority {
        val fresh = input.observedAtMs > 0L && nowMs - input.observedAtMs in 0L..90_000L
        if (!fresh || !input.priceUsd.isFinite() || input.priceUsd <= 0.0 ||
            !input.liquidityUsd.isFinite() || input.liquidityUsd <= 0.0) return Priority(0, 0.0, "HYDRATE_STALE_OR_UNPRICED")
        val a = input.admission?.takeIf { nowMs - it.atMs in 0L..30_000L }
        val selling = input.change5mPct.isFinite() && input.change5mPct < 0.0 &&
            input.buyPressurePct.isFinite() && input.buyPressurePct < 42.0
        val shrinking = input.liquidityDeltaPct.isFinite() && input.liquidityDeltaPct <= -12.0
        if (input.nativeJudgement || a?.refusal != null || selling || shrinking)
            return Priority(0, 0.0, a?.refusal ?: if (input.nativeJudgement) "NATIVE_REFUSED" else "CURRENT_SELLING_OR_DEPTH_LOSS")
        if (a?.nativeQualified == true && a.supported && a.netLowerPct.isFinite() && a.netLowerPct > 0.0 &&
            a.costPct.isFinite() && a.costPct > 0.0) return Priority(3, a.netLowerPct, "SUPPORTED_NET_EDGE")
        if (input.change5mPct.isFinite() && input.change5mPct > 0.0 &&
            input.buyPressurePct.isFinite() && input.buyPressurePct >= 55.0)
            return Priority(2, 0.0, "OBSERVED_CONTINUATION")
        return Priority(1, 0.0, "HYDRATE_UNPROVEN")
    }
}
