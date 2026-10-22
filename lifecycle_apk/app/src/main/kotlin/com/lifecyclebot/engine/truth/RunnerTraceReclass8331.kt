package com.lifecyclebot.engine.truth

/**
 * V5.0.8331 — "WRONGLY_REFUSED" needs decision-time evidence, not a qualified lane (pure).
 *
 * Device, 5.0.8326: the runner decision trace read 169 opportunities, 71 "wrongly refused", of which 67 were
 * QUALIFIED_LANE_STOPPED — AdmissionRunnerTrace8144.classify8144 called a refusal wrong merely because another lane was
 * natively qualified at the first read. A refusal is proven wrong only when the bot's own record at the decision shows a
 * safe, executable, positive-net entry: hard safety passed, a route observed (curve / pool), exit depth at or above the
 * live exit-safe floor, and an expected net move after the window's round trip above zero.
 *
 * [reclassify8331] keeps the 8144 verdict as the raw count and buckets every WRONGLY_REFUSED trace:
 *  - WRONG_REFUSAL  every piece of evidence present and in favour;
 *  - UNPROVEN       qualified elsewhere only — the evidence is absent (an older trace without the 8331 fields) or one of
 *                   the four conditions fails;
 *  - DATA_GAP       the window itself was never observed (no route / no window time): the data path failed first.
 * Traces the 8144 rule did not call wrong are left as they are (null).
 */
object RunnerTraceReclass8331 {
    enum class Reclass8331 { WRONG_REFUSAL, UNPROVEN, DATA_GAP }

    /** The live exit-safe depth floor (PreTradeHardGate MIN_LIVE_LIQ_HARD_FLOOR_USD). */
    const val LIVE_EXIT_FLOOR_USD_8331 = 500.0

    /** Pure: the four pieces of decision-time evidence of a safe, executable, positive-net entry. */
    fun evidenceFavours8331(hardSafetyPassed: Boolean, route: String, exitDepthUsd: Double, expectedNetPct: Double,
                            floorUsd: Double = LIVE_EXIT_FLOOR_USD_8331): Boolean =
        hardSafetyPassed && route.isNotBlank() && exitDepthUsd.isFinite() && exitDepthUsd >= floorUsd &&
            expectedNetPct.isFinite() && expectedNetPct > 0.0

    /** Pure: the trace carries the 8331 evidence fields (recorded at its window) at all. */
    fun carriesEvidence8331(t: AdmissionRunnerTrace8144.Trace8144): Boolean =
        t.exitDepthUsd.isFinite() || t.expectedNetPct.isFinite() || t.hardSafetyPassed

    /** Pure: the bucket of a trace the 8144 rule called WRONGLY_REFUSED; null for every other verdict. */
    fun reclassify8331(t: AdmissionRunnerTrace8144.Trace8144, floorUsd: Double = LIVE_EXIT_FLOOR_USD_8331): Reclass8331? {
        if (AdmissionRunnerTrace8144.classify8144(t).miss != AdmissionRunnerTrace8144.Miss8144.WRONGLY_REFUSED) return null
        if (t.windowAtMs <= 0L || t.route.isBlank()) return Reclass8331.DATA_GAP
        if (!carriesEvidence8331(t)) return Reclass8331.UNPROVEN
        return if (evidenceFavours8331(t.hardSafetyPassed, t.route, t.exitDepthUsd, t.expectedNetPct, floorUsd)) Reclass8331.WRONG_REFUSAL
        else Reclass8331.UNPROVEN
    }

    /** Pure: the raw 8144 wrong count beside its 8331 buckets. */
    fun summary8331(traces: List<AdmissionRunnerTrace8144.Trace8144>): String {
        val buckets = traces.mapNotNull { reclassify8331(it) }
        return "reclass8331[rawWrong=${buckets.size} wrongRefusal=${buckets.count { it == Reclass8331.WRONG_REFUSAL }} " +
            "unproven=${buckets.count { it == Reclass8331.UNPROVEN }} dataGap=${buckets.count { it == Reclass8331.DATA_GAP }} " +
            "withEvidence=${traces.count { carriesEvidence8331(it) }}/${traces.size}]"
    }
}
