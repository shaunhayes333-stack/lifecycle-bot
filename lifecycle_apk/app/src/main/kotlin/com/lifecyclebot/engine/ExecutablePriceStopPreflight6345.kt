package com.lifecyclebot.engine

import kotlin.math.abs

/**
 * V5.0.6345 — EXECUTABLE-PRICE STOP PREFLIGHT (operator P0-8 spec).
 *
 * OPERATOR DIRECTIVE (verbatim excerpts):
 *   "Stops must be validated against the last executable quote, not the
 *    oracle mid. A stop pinned to a stale WebSocket mark will fire on a
 *    quote that no market maker can actually honour — that is the exact
 *    catastrophic-slippage failure mode the WADDLE bug produced."
 *
 * PURPOSE
 *   Given the raw stop price the strategy layer wants to place and the
 *   last executable-quote pair (best bid / best ask, or Jupiter route
 *   quote for pump.fun tokens), returns the preflighted stop. If the
 *   requested stop is inside the executable spread or so far from mid
 *   that no bid can plausibly fill it, we clamp the stop to the widest
 *   defensible executable price and emit an executive log entry.
 *
 *   The strategy layer keeps its strategic view of where risk should
 *   sit; the preflight guarantees the placed stop is a level someone
 *   can actually trade against.
 */
object ExecutablePriceStopPreflight6345 {

    /** How far outside the executable bid the stop is allowed to sit
     *  before we treat it as unreachable (fraction of last-mid). */
    private const val UNREACHABLE_FRACTION: Double = 0.20   // 20% below bid → unreachable

    data class Preflight(
        val requestedStopSol: Double,
        val executableStopSol: Double,
        val clamped: Boolean,
        val reason: String,
        val bidSol: Double,
        val askSol: Double,
        val midSol: Double,
    ) {
        val ok: Boolean get() = executableStopSol > 0.0
    }

    /**
     * Validate/clamp a stop price against the current executable quote.
     *
     * @param requestedStopSol   the strategy-layer stop price (SOL per token)
     * @param executableBidSol   best executable bid (SOL per token, > 0)
     * @param executableAskSol   best executable ask (SOL per token, > 0)
     * @param minStopSlackFrac   minimum fraction below bid we require so
     *                            the stop actually clears the spread and
     *                            fires on a real quote, not on the spread
     *                            floor. 0.005 = 50 bps default.
     */
    fun preflight(
        requestedStopSol: Double,
        executableBidSol: Double,
        executableAskSol: Double,
        minStopSlackFrac: Double = 0.005,
    ): Preflight {
        // Missing quote — cannot preflight. Return the strategy stop
        // untouched but flag it so the caller can defer entry.
        if (executableBidSol <= 0.0 || executableAskSol <= 0.0 ||
            !executableBidSol.isFinite() || !executableAskSol.isFinite()) {
            try {
                PipelineHealthCollector.labelInc("STOP_PREFLIGHT_QUOTE_MISSING_6345")
            } catch (_: Throwable) {}
            return Preflight(
                requestedStopSol = requestedStopSol,
                executableStopSol = requestedStopSol,
                clamped = false,
                reason = "QUOTE_MISSING_6345",
                bidSol = executableBidSol,
                askSol = executableAskSol,
                midSol = 0.0,
            )
        }

        val mid = (executableBidSol + executableAskSol) / 2.0
        val slack = mid * minStopSlackFrac
        val maxDefensibleFloor = executableBidSol - slack
        // Stop is above the executable bid — that's not a stop, that's a
        // limit sell. Clamp to bid − slack.
        if (requestedStopSol >= executableBidSol) {
            try {
                PipelineHealthCollector.labelInc("STOP_PREFLIGHT_ABOVE_BID_CLAMPED_6345")
                ForensicLogger.lifecycle(
                    "STOP_PREFLIGHT_ABOVE_BID_CLAMPED_6345",
                    "req=${"%.6e".format(requestedStopSol)} bid=${"%.6e".format(executableBidSol)} " +
                        "clamped=${"%.6e".format(maxDefensibleFloor)}",
                )
            } catch (_: Throwable) {}
            return Preflight(
                requestedStopSol = requestedStopSol,
                executableStopSol = maxDefensibleFloor.coerceAtLeast(0.0),
                clamped = true,
                reason = "STOP_ABOVE_BID_CLAMPED_TO_BID_MINUS_SLACK",
                bidSol = executableBidSol,
                askSol = executableAskSol,
                midSol = mid,
            )
        }

        // Stop so far below mid it will never fire on a real quote.
        // Clamp upward to bid × (1 - UNREACHABLE_FRACTION).
        val unreachableFloor = executableBidSol * (1.0 - UNREACHABLE_FRACTION)
        if (requestedStopSol < unreachableFloor && requestedStopSol > 0.0) {
            try {
                PipelineHealthCollector.labelInc("STOP_PREFLIGHT_UNREACHABLE_CLAMPED_6345")
                ForensicLogger.lifecycle(
                    "STOP_PREFLIGHT_UNREACHABLE_CLAMPED_6345",
                    "req=${"%.6e".format(requestedStopSol)} bid=${"%.6e".format(executableBidSol)} " +
                        "distance=${"%.2f%%".format(100.0 * abs(mid - requestedStopSol) / mid)} " +
                        "clamped=${"%.6e".format(unreachableFloor)}",
                )
            } catch (_: Throwable) {}
            return Preflight(
                requestedStopSol = requestedStopSol,
                executableStopSol = unreachableFloor,
                clamped = true,
                reason = "STOP_UNREACHABLE_CLAMPED_TO_BID_MINUS_20PCT",
                bidSol = executableBidSol,
                askSol = executableAskSol,
                midSol = mid,
            )
        }

        // Stop is inside the healthy executable window.
        try { PipelineHealthCollector.labelInc("STOP_PREFLIGHT_OK_6345") } catch (_: Throwable) {}
        return Preflight(
            requestedStopSol = requestedStopSol,
            executableStopSol = requestedStopSol,
            clamped = false,
            reason = "OK",
            bidSol = executableBidSol,
            askSol = executableAskSol,
            midSol = mid,
        )
    }
}
