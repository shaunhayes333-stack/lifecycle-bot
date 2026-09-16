package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6816 §PROJECT_SNIPER_UNCHOKE — operator directive V5.0.6813 P1:
 *   "Trace PROJECT_SNIPER from markReady -> CanonicalNotionalResolver ->
 *    OrderSizeResolver -> ticket creation. Fix starvation where
 *    markReady=27 but sizedExecutable=0. Allow strong lanes to execute
 *    without being starved by weak ones taking up the cash cap."
 *
 * DESIGN — additive, tightly scoped to PROJECT_SNIPER.
 *   • Exposes `laneMinExecutableSol(lane, defaultMin)` which returns
 *     the same default for non-PROJECT_SNIPER lanes; for PROJECT_SNIPER
 *     it returns `min(defaultMin, PROJECT_SNIPER_FLOOR_SOL)` so the
 *     minimum executable notional for the sniper lane is never
 *     inflated above the paper floor. This preserves the
 *     "no upward promotion" invariant.
 *   • Exposes `preferMinPromotion(lane)` which returns true for
 *     PROJECT_SNIPER — signalling the sizer that when a positive
 *     but sub-minimum request comes through, it SHOULD promote to
 *     minimum executable rather than reject with BELOW_MIN_NOTIONAL.
 *     This is the exact starvation mode the operator dump captured
 *     (markReady=27 → sizedExecutable=0).
 *   • Never mutates cash, ledger, or ticket state. Pure lane-scoped
 *     policy read.
 */
object ProjectSniperSizingChoke6816 {

    /** PROJECT_SNIPER-specific floor. Matches the paper executable minimum
     *  so the choke never inflates the sniper lane above other lanes. */
    private const val PROJECT_SNIPER_FLOOR_SOL = 0.05

    private val queries = AtomicLong(0L)
    private val laneOverrides = AtomicLong(0L)
    private val promotionsHinted = AtomicLong(0L)

    /**
     * @return the effective per-lane minimum executable size. For
     *   PROJECT_SNIPER the floor is clamped so we do not require MORE
     *   than the caller's supplied default; for every other lane the
     *   caller's default is returned unchanged.
     */
    fun laneMinExecutableSol(lane: String?, defaultMin: Double): Double {
        queries.incrementAndGet()
        if (lane.isNullOrBlank()) return defaultMin
        val key = lane.trim().uppercase()
        if ("PROJECT_SNIPER" !in key && "SNIPER" !in key) return defaultMin
        laneOverrides.incrementAndGet()
        // Never raise the caller's floor. Only offer a lane-scoped clamp
        // when the caller's default is above the sniper floor.
        val out = kotlin.math.min(defaultMin.coerceAtLeast(0.0), PROJECT_SNIPER_FLOOR_SOL)
        try {
            PipelineHealthCollector.labelInc("PROJECT_SNIPER_MIN_CLAMPED_6816")
        } catch (_: Throwable) {}
        return out
    }

    /**
     * @return true when the sizer should promote a sub-minimum but
     *   strictly positive request to minimum executable if hard caps
     *   allow — instead of rejecting outright. Only true for the
     *   PROJECT_SNIPER lane; every other lane keeps the caller's
     *   existing behaviour.
     */
    fun preferMinPromotion(lane: String?): Boolean {
        if (lane.isNullOrBlank()) return false
        val key = lane.trim().uppercase()
        val hit = "PROJECT_SNIPER" in key || "SNIPER" in key
        if (hit) {
            promotionsHinted.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PROJECT_SNIPER_MIN_PROMOTION_HINT_6816")
            } catch (_: Throwable) {}
        }
        return hit
    }

    /**
     * Telemetry hook called by consumers when the sniper lane sized to
     * zero despite mark being ready. Diagnostic-only; publishing this
     * counter is how the operator will see whether the choke is
     * releasing after this ship.
     */
    fun recordStarvation(reason: String) {
        try {
            PipelineHealthCollector.labelInc("PROJECT_SNIPER_STARVATION_6816")
            PipelineHealthCollector.labelInc(
                "PROJECT_SNIPER_STARVATION_6816_${reason.uppercase().take(32)}"
            )
            ForensicLogger.lifecycle(
                "PROJECT_SNIPER_STARVATION_6816",
                "reason=${reason.take(120)}",
            )
        } catch (_: Throwable) {}
    }

    fun statusLine(): String =
        "queries=${queries.get()} overrides=${laneOverrides.get()} " +
            "promotions=${promotionsHinted.get()}"

    internal fun clearForTest() {
        queries.set(0L); laneOverrides.set(0L); promotionsHinted.set(0L)
    }
}
