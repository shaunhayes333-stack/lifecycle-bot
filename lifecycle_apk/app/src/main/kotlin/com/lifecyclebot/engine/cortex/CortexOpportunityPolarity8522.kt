package com.lifecyclebot.engine.cortex

import java.util.concurrent.atomic.AtomicLong

/** Explicit operator opportunity policy. Forecasts, outcomes and earned authority retain their original meaning. */
object CortexOpportunityPolarity8522 {
    const val POLICY_LABEL_8522 = "OPERATOR_INVERSE"
    const val MAX_AGE_MS_8522 = 5_000L
    /** Enough graded same-lane decisions to reject noisy, misleading direction flips. */
    const val MIN_DIRECTION_ROWS_8527 = 60
    const val MIN_BUCKET_ROWS_8527 = 20

    /** A directional override proves BOTH its inversion and the economics of the exact bucket it changes. */
    fun inverseRankVerified8527(rank: CortexRank8325.LaneRank8325?, lane: String, action: Decision): Boolean {
        if (rank == null || rank.lane != lane || rank.rows < MIN_DIRECTION_ROWS_8527 ||
            rank.orderedBy != "edge" || rank.verdict != "INVERTED") return false
        return when (action) {
            Decision.TAKE_CANDIDATE -> rank.refuseCount8527 >= MIN_BUCKET_ROWS_8527 &&
                rank.refuseNetLowerPct8527.isFinite() && rank.refuseNetLowerPct8527 > 0.0
            Decision.REFUSE -> rank.strongCount8527 >= MIN_BUCKET_ROWS_8527 &&
                rank.strongNetUpperPct8527.isFinite() && rank.strongNetUpperPct8527 < 0.0
            Decision.ABSTAIN -> false
        }
    }

    private val lanes = setOf("QUALITY", "BLUECHIP", "SHITCOIN", "EXPRESS", "MOONSHOT", "PROJECT_SNIPER",
        "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN", "CYCLIC", "CORE")

    enum class Decision { TAKE_CANDIDATE, REFUSE, ABSTAIN }

    /** Immutable source identity, captured before reading voters. Zero revisions remain unknown, never invented. */
    data class Source(
        val mint: String, val lane: String, val mode: String,
        val candidateVersion: Long, val featureRevision: Long,
        val priceAtMs: Long, val price: Double,
        val pressureAtMs: Long, val pressurePct: Double,
        val changeAtMs: Long, val change5mPct: Double,
        val hourAtMs: Long, val change1hPct: Double,
        val historySize: Int, val historyAtMs: Long, val historyPrice: Double,
        val v3Score: Int?, val v3Confidence: Int?,
        val originalOpinions8522: Map<String, com.lifecyclebot.engine.SpecialistBrainBridge7542.Opinion>? = null,
    )

    data class Projection(
        val decision: Decision, val rawBucket: CortexScoreboard7885.Bucket?, val rawEdgePct: Double,
        val reason: String, val mint: String, val lane: String, val mode: String,
        val assessmentAtMs: Long, val inputAtMs: Long, val candidateVersion: Long,
        val policy: String = POLICY_LABEL_8522,
        val featureRevision: Long = -1L,
        val source8522: Source? = null,
        // V5.0.8535 §P0-11 — what the bound decision rests on, surfaced on every projection (abstentions included).
        val pUp8535: Double = Double.NaN,
        val expectedNetPct8535: Double = Double.NaN,
        val tailProb8535: Double = Double.NaN,
        val calibrationN8535: Double = Double.NaN,
        val nullFraction8535: Double = Double.NaN,
        val effectiveVoters8535: Double = Double.NaN,
    ) {
        /** One line: decision, raw polarity, P(up), expected net, tail, calibration sample, null fraction, voters, reason. */
        val receipt8535: String get() = receiptLine8535(decision.name, rawBucket?.name ?: "-", pUp8535, expectedNetPct8535, tailProb8535,
            calibrationN8535, nullFraction8535, effectiveVoters8535, reason)
    }

    /** Pure (V5.0.8535 §P0-11): the override / abstention receipt text. */
    fun receiptLine8535(decision: String, rawPolarity: String, pUp: Double, expectedNetPct: Double, tailProb: Double,
                        calibrationN: Double, nullFraction: Double, voters: Double, reason: String): String {
        fun f(v: Double, p: String = "%.2f") = if (v.isFinite()) p.format(v) else "?"
        return "$decision raw=$rawPolarity pUp=${f(pUp)} net=${f(expectedNetPct, "%+.1f")}% tail=${f(tailProb)} n=${f(calibrationN, "%.0f")} " +
            "null=${f(nullFraction)} voters=${f(voters, "%.1f")} why=$reason"
    }

    /** Pure: the share of voter ids with no finite reading (unknown inputs are named, never scored as neutral). */
    fun nullFraction8535(raws: DoubleArray, ids: Int): Double = if (ids <= 0) Double.NaN else raws.count { !it.isFinite() }.toDouble() / ids

    private val recentReceipts8535 = java.util.ArrayDeque<String>()

    fun appliesToLane8522(lane: String): Boolean = lane in lanes

    internal fun currentInput8522(source: Source, assessment: Cortex7885.Assessment, nowMs: Long): Boolean =
        assessment.opportunitySource8522 == source && assessment.lane == source.lane &&
            assessment.inputAtMs8522 > 0L && assessment.atMs >= assessment.inputAtMs8522 &&
            nowMs - assessment.inputAtMs8522 in 0L..MAX_AGE_MS_8522 &&
            nowMs - assessment.atMs in 0L..MAX_AGE_MS_8522

    /** A global regime/portfolio read alone is not evidence about this candidate. */
    internal fun candidateEvidence8522(a: Cortex7885.Assessment): Boolean = a.ids.indices.any { i ->
        val id = a.ids[i]
        a.raws.getOrNull(i)?.isFinite() == true &&
            (id in candidateIds || id.startsWith("V3M_") || id.startsWith("LB_"))
    }

    private val candidateIds = setOf("SPEC_OWN_SCORE", "SPEC_OWN_CONF", "SPEC_BREADTH", "SPEC_MEAN_SCORE",
        "V3_SCORE", "V3_CONFIDENCE", "ENTRY_SCORE", "SETUP_QUALITY", "MOMENTUM_SCORE", "PRESSURE_SCORE",
        "VOLUME_SCORE", "RSI", "FORWARD_MODEL_EV", "FORWARD_MODEL_PWIN", "PATTERN_CLASSIFIER")

    /** Pure projection: the caller still applies all mandate, data, safety, account, route and size checks. */
    fun project8522(source: Source, assessment: Cortex7885.Assessment?, nowMs: Long, currentMode: String,
                    verifiedLaneRank8527: CortexRank8325.LaneRank8325? = null): Projection {
        fun result(decision: Decision, reason: String) = Projection(decision, assessment?.bucket,
            assessment?.calibratedEdge ?: Double.NaN, reason, source.mint, source.lane, source.mode,
            assessment?.atMs ?: 0L, assessment?.inputAtMs8522 ?: 0L, source.candidateVersion,
            featureRevision = source.featureRevision, source8522 = source,
            // V5.0.8535 §P0-11 — the receipt's fields from the assessment itself (NaN when there is none).
            pUp8535 = assessment?.fused?.pWin ?: Double.NaN,
            expectedNetPct8535 = assessment?.calibratedEdge ?: Double.NaN,
            tailProb8535 = assessment?.fused?.let { f -> f.pRun8325.takeIf { it.isFinite() } ?: f.runnerRate } ?: Double.NaN,
            calibrationN8535 = assessment?.fused?.laneN ?: Double.NaN,
            nullFraction8535 = assessment?.let { nullFraction8535(it.raws, it.ids.size) } ?: Double.NaN,
            effectiveVoters8535 = assessment?.fused?.effectiveVoters ?: Double.NaN)
        fun abstain(reason: String) = result(Decision.ABSTAIN, reason)
        if (source.mint.isBlank() || !appliesToLane8522(source.lane)) return abstain("OUTSIDE_SOLANA_NATIVE_SCOPE")
        if (source.mode !in setOf("PAPER", "LIVE") || source.mode != currentMode) return abstain("MODE_MISMATCH")
        val a = assessment ?: return abstain("NO_CURRENT_ASSESSMENT")
        val captured = a.opportunitySource8522 ?: return abstain("UNBOUND_ASSESSMENT")
        if (captured.mint != source.mint || captured.lane != source.lane || a.lane != source.lane)
            return abstain("CANDIDATE_MISMATCH")
        if (captured.mode != source.mode) return abstain("MODE_MISMATCH")
        if (captured != source) return abstain("INPUT_REVISION_CHANGED")
        if (!currentInput8522(source, a, nowMs)) return abstain("STALE_OR_FUTURE_ASSESSMENT")
        if (a.scoreVersion8372 != CortexForwardEpoch8521.SCORE_VERSION) return abstain("OLD_FORECAST_VERSION")
        if (a.staleMark || !source.price.isFinite() || source.price <= 0.0 ||
            source.priceAtMs <= 0L || source.priceAtMs > nowMs) return abstain("UNOBSERVED_OR_STALE_MARK")
        if (!a.calibratedEdge.isFinite()) return abstain("UNKNOWN_FORECAST")
        if (!a.fused.totalWeight.isFinite() || a.fused.totalWeight <= 0.0 ||
            !a.fused.effectiveVoters.isFinite() || a.fused.effectiveVoters <= 0.0) return abstain("PRIOR_ONLY")
        if (!candidateEvidence8522(a)) return abstain("NO_CANDIDATE_EVIDENCE")
        if (CortexScoreboard7885.bucketOf(a.calibratedEdge, 0.0, false) != a.bucket)
            return abstain("FORECAST_BUCKET_MISMATCH")
        // Invert ONLY a bucket whose same-lane, finite, current-epoch forward outcomes support
        // the particular action after modelled execution costs. The opposite bucket cannot borrow its proof.
        return when (a.bucket) {
            CortexScoreboard7885.Bucket.REFUSE ->
                if (inverseRankVerified8527(verifiedLaneRank8527, source.lane, Decision.TAKE_CANDIDATE))
                    result(Decision.TAKE_CANDIDATE, "VERIFIED_REFUSE_BUCKET_NET_POSITIVE_8527")
                else abstain("REFUSE_BUCKET_INVERSION_UNPROVEN_8527")
            CortexScoreboard7885.Bucket.STRONG ->
                if (inverseRankVerified8527(verifiedLaneRank8527, source.lane, Decision.REFUSE))
                    result(Decision.REFUSE, "VERIFIED_STRONG_BUCKET_NET_NEGATIVE_8527")
                else abstain("STRONG_BUCKET_INVERSION_UNPROVEN_8527")
            CortexScoreboard7885.Bucket.NEUTRAL -> abstain("RAW_NEUTRAL")
        }
    }

    private val evaluations = Array(Decision.values().size) { AtomicLong(0L) }
    internal fun note8522(projection: Projection): Projection = projection.also {
        evaluations[it.decision.ordinal].incrementAndGet()
        // V5.0.8535 §P0-11 — every bound decision leaves its receipt (the last few are on the status line).
        synchronized(recentReceipts8535) {
            recentReceipts8535.addLast("${it.lane}:${it.mint.take(6)} ${it.receipt8535}")
            while (recentReceipts8535.size > 6) recentReceipts8535.removeFirst()
        }
    }

    /** V5.0.8535 — the most recent projection receipts (newest last). */
    fun recentReceipts8535(): List<String> = synchronized(recentReceipts8535) { recentReceipts8535.toList() }

    fun statusLine8522(): String = "$POLICY_LABEL_8522 scope=Solana/native opportunity only " +
        "projection evaluations: raw REFUSE→candidate=${evaluations[Decision.TAKE_CANDIDATE.ordinal].get()} " +
        "raw STRONG→REFUSE=${evaluations[Decision.REFUSE.ordinal].get()} abstain=${evaluations[Decision.ABSTAIN.ordinal].get()} " +
        "(not trades; raw forecasts/outcomes and earned authority unchanged)"
}
