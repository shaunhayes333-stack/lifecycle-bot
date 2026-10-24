package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8370 — SMALL STAKES TRADE (owner choice, as 8114).
 *
 * At 379ad2fa0 (8114) LiveRiskPolicy7807.decide promoted a risk / Kelly stake below the executable route minimum to the
 * minimum whenever the minimum was fundable (lane position cap or the small-account share, the depth cap) and its loss at
 * the stop fit the hard per-trade risk cap (executableMinRiskOk) — "OPEN_RISK_SAFE_MIN_PROMOTED_7840". No candidate-level
 * reason was asked. 8329 / 8365 added two binding refusals on top (ROUTE_MIN_PROMOTION_UNSUPPORTED_8329 on the upstream
 * sizers, KELLY_TOO_SMALL_FOR_ROUTE_8365 at the last mile). Both are labels now: computed, counted, never binding.
 *
 * Every hard cap still binds at the promoted size: tradeable cash after the reserve and the lane envelope
 * (SizingNetEdge8153.promotionRefusal8153), the lane position cap / small-account share and the depth cap
 * (minFundable7840), the hard loss at the stop (executableMinRiskOk), the fixed-cost wall (CostLanding8169), the 8188
 * negative-edge test, the exposure preflight downstream. Above the minimum the log-optimal Kelly size is used unchanged.
 */
object SmallStake8370 {
    const val LABEL_PREFIX_8370 = "SMALL_STAKE_PROMOTED_8370_"

    private val noted8370 = ConcurrentHashMap<String, AtomicLong>()

    /** Pure: the gate key of a promotion refusal (lane suffix dropped) for the telemetry label. */
    fun key8370(refusal: String): String = when {
        refusal.startsWith(Kelly8365.KELLY_TOO_SMALL_8365) -> Kelly8365.KELLY_TOO_SMALL_8365
        refusal.startsWith(EntryEconomics8329.PROMOTION_UNSUPPORTED_8329) -> EntryEconomics8329.PROMOTION_UNSUPPORTED_8329
        else -> refusal.take(60)
    }

    /**
     * LiveRiskPolicy7807.decide / MinPromotion8188.runtimeRefusal8188: records [refusal] (KELLY_TOO_SMALL_FOR_ROUTE_8365 /
     * ROUTE_MIN_PROMOTION_UNSUPPORTED_8329_<lane>) as a label only: the promotion goes on (every hard cap still binds).
     */
    fun telemetry8370(refusal: String?) {
        if (refusal == null) return
        val k = key8370(refusal)
        noted8370.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc(LABEL_PREFIX_8370 + k) } catch (_: Throwable) {}
    }

    /** Labels counted so far for [gate] (KELLY_TOO_SMALL_FOR_ROUTE_8365 / ROUTE_MIN_PROMOTION_UNSUPPORTED_8329). */
    fun count8370(gate: String): Long = noted8370[gate]?.get() ?: 0L

    /**
     * Pure: the 8114 last-mile promotion (LiveRiskPolicy7807 at 379ad2fa0, lines 530-548): the route minimum [routeMinSol]
     * replaces a smaller [stakeSol] when it fits the larger of the lane cap [laneCapSol] and the small-account share
     * [smallAccountCapSol], the depth cap [liqCapSol], and its loss at the stop [lossAtStopSol] fits [hardLossBudgetSol];
     * else 0.0 (refused). A stake at or above the minimum is returned unchanged.
     */
    fun promoted8370(stakeSol: Double, routeMinSol: Double, laneCapSol: Double, smallAccountCapSol: Double,
                     liqCapSol: Double, lossAtStopSol: Double, hardLossBudgetSol: Double): Double {
        if (!(routeMinSol > 0.0) || !routeMinSol.isFinite()) return stakeSol
        if (stakeSol.isFinite() && stakeSol >= routeMinSol) return stakeSol
        val fundable = routeMinSol <= maxOf(laneCapSol, smallAccountCapSol) + 1e-12 && routeMinSol <= liqCapSol + 1e-12
        val riskSafe = lossAtStopSol.isFinite() && hardLossBudgetSol.isFinite() && lossAtStopSol <= hardLossBudgetSol + 1e-12
        return if (fundable && riskSafe) routeMinSol else 0.0
    }

    fun statusLine8370(): String =
        "smallStake8370[labelOnly " +
            noted8370.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } + "]"
}
