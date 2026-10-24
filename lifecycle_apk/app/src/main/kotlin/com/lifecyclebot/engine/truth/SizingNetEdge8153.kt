package com.lifecyclebot.engine.truth

/**
 * V5.0.8153 §COST_AWARE_SIZING — the economics a live size must survive, net of what executing it costs.
 *
 * Three gaps in LiveRiskPolicy7807.decide, closed here as pure maths (no Android, no network):
 *
 *  1. Net EV at the size. The only cost test was the plan's first TARGET against twice the 7766 model round trip
 *     (1% "platform" + priority + 1% slippage + impact). The selected setup's EXPECTED return was never set against
 *     the cost at the size being sealed, so a setup expecting +5% gross on a 7.5% measured round trip could still
 *     open whenever its plan named a +30% target. [netNonPositive8153] refuses it.
 *
 *  2. Kelly on a net edge. The proven quarter-Kelly share is read from forward labels whose returns are net of the
 *     cost the LABELLER charged — the lane's measured median or the $5 route-minimum model (ForwardReturnLabeler7731
 *     COST_SIZE_USD_7731) — not of the cost at the Kelly size, where impact grows with the order. A record +6% net
 *     at $5 is +0% net at a size whose round trip is 6 points dearer, yet it was sized as if it still paid.
 *     [netKellyFrac8153] re-nets the record at the cost of the size and can only lower the share.
 *
 *  3. The binding envelope. A lane's open principal may not grow past its slot share of the live book just because
 *     other lanes leave cash idle ([laneEnvelopeRoomSol8153]); no order may exceed the SOL it can actually spend
 *     (wallet minus the reserve minus other sealed reservations), and a route-minimum promotion must sit inside the
 *     same cost and capital envelope ([promotionRefusal8153]).
 */
object SizingNetEdge8153 {

    const val NET_EV_REFUSAL_8153 = "NET_EV_NONPOSITIVE_AT_SIZE_8153"

    /**
     * The record a proven Kelly share came from: per-label NET return mean (%), its variance (%²), the label count,
     * and the round trip (%) the labeller charged those labels (the mean is net of THIS, not of the cost at size).
     */
    data class EdgeRecord8153(
        val meanNetPct: Double,
        val variancePct2: Double,
        val n: Double,
        val labelCostPct: Double,
    )

    /** Pure: expected gross minus the round trip at the size; NaN when either is unknown. */
    fun netEdgePct8153(expectedGrossPct: Double, costAtSizePct: Double): Double =
        if (expectedGrossPct.isFinite() && costAtSizePct.isFinite()) expectedGrossPct - costAtSizePct else Double.NaN

    /** Pure: true only when the expected gross is MEASURED and does not clear the round trip at the size. */
    fun netNonPositive8153(expectedGrossPct: Double, costAtSizePct: Double): Boolean {
        val net = netEdgePct8153(expectedGrossPct, costAtSizePct)
        return net.isFinite() && net <= 0.0
    }

    /**
     * Pure: the proven quarter-Kelly share re-netted at the round trip of the size being sealed. The record's mean is
     * lowered by the cost above what its labels were charged (never raised when the size is cheaper); the result is
     * never above [provenFrac]. No record: [provenFrac] unchanged (only the share is known).
     */
    fun netKellyFrac8153(provenFrac: Double, rec: EdgeRecord8153?, costAtSizePct: Double): Double {
        if (!provenFrac.isFinite() || provenFrac <= 0.0) return 0.0
        if (rec == null) return provenFrac
        val extra = if (costAtSizePct.isFinite() && rec.labelCostPct.isFinite()) (costAtSizePct - rec.labelCostPct).coerceAtLeast(0.0) else 0.0
        // superseded by V5.0.8365 — min(proven, kelly(mean - extra)) re-read a runner-aware (live / runner-rate) share on the
        // symmetric moments read and flattened it; the share is now scaled by the record's own net-of-extra-cost ratio
        // (Kelly8365.netRatio8365, in [0, 1]): never above [provenFrac], 0 once the extra cost consumes mean - SE.
        val net = provenFrac * Kelly8365.netRatio8365(rec.meanNetPct, rec.variancePct2, rec.n, extra)
        return minOf(provenFrac, net).coerceAtLeast(0.0)
    }

    /**
     * Pure: the principal a lane may still add. Its envelope is its slot share of the live book's equity
     * ([laneSlots] / [bookSlots], never above the whole book) — idle cash elsewhere does not enlarge it.
     * Unknown deployment (NaN) = unbounded here (the per-order caps still bind).
     */
    fun laneEnvelopeRoomSol8153(equitySol: Double, laneSlots: Int, bookSlots: Int, laneDeployedSol: Double): Double {
        if (!laneDeployedSol.isFinite()) return Double.POSITIVE_INFINITY
        if (!equitySol.isFinite() || equitySol <= 0.0 || laneSlots <= 0 || bookSlots <= 0) return 0.0
        val share = (laneSlots.toDouble() / bookSlots.toDouble()).coerceIn(0.0, 1.0)
        return (equitySol * share - laneDeployedSol.coerceAtLeast(0.0)).coerceAtLeast(0.0)
    }

    /** Pure: the smallest finite non-negative cap (the binding envelope); +inf when none is known. */
    fun envelopeCapSol8153(vararg caps: Double): Double =
        caps.filter { !it.isNaN() && it >= 0.0 }.minOrNull() ?: Double.POSITIVE_INFINITY

    /**
     * Pure: why a risk-safe route minimum may NOT be promoted (null = it may). The 7840 promotion proves the minimum
     * against the lane / small-account share, depth and the hard loss at the stop; it never asked whether the minimum
     * could be paid from tradeable SOL, fits the lane's book envelope, or still clears the cost at THAT size.
     */
    fun promotionRefusal8153(
        execMinSol: Double, tradeableSol: Double, laneRoomSol: Double,
        firstTargetPct: Double, minCostPct: Double, expectedGrossPct: Double,
    ): String? = when {
        tradeableSol.isFinite() && execMinSol > tradeableSol.coerceAtLeast(0.0) + 1e-12 -> "ROUTE_MIN_EXCEEDS_TRADEABLE_8153"
        laneRoomSol.isFinite() && execMinSol > laneRoomSol + 1e-12 -> "ROUTE_MIN_EXCEEDS_LANE_ENVELOPE_8153"
        LiveRiskPolicy7807.costVerdict(firstTargetPct, minCostPct) == LiveRiskPolicy7807.CostVerdict.PASS -> "ROUTE_MIN_COST_CONSUMES_MOVE_8153"
        netNonPositive8153(expectedGrossPct, minCostPct) -> "ROUTE_MIN_NET_EV_NONPOSITIVE_8153"
        else -> null
    }
}
