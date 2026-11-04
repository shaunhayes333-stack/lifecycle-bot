package com.lifecyclebot.engine.cortex

import java.util.concurrent.atomic.AtomicLong

/** Explicit operator opportunity policy. Forecasts, outcomes and earned authority retain their original meaning. */
object CortexOpportunityPolarity8522 {
    const val POLICY_LABEL_8522 = "OPERATOR_INVERSE"
    const val MAX_AGE_MS_8522 = 5_000L
    /** Enough graded same-lane decisions to reject noisy, misleading direction flips. */
    const val MIN_DIRECTION_ROWS_8527 = 60

    /** Only the calibrated edge's own OUT-OF-SAMPLE order can authorize an edge-bucket inversion. */
    fun inverseRankVerified8527(rank: CortexRank8325.LaneRank8325?, lane: String): Boolean =
        rank != null && rank.lane == lane && rank.rows >= MIN_DIRECTION_ROWS_8527 &&
            rank.orderedBy == "edge" && rank.verdict == "INVERTED" &&
            rank.inverseBottomNetLowerBoundPct.isFinite() && rank.inverseBottomNetLowerBoundPct > 0.0
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
    )

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
            featureRevision = source.featureRevision, source8522 = source)
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
        // V5.0.8527: 8522 flipped every native lane even where recent rank IC was positive or FLAT.
        // The scored cohort, decision clock and target must match this raw EDGE bucket, not a tail EV pond.
        // No fresh, sufficiently graded INVERTED rank means abstain: native/V3 authority remains unchanged.
        if (!inverseRankVerified8527(verifiedLaneRank8527, source.lane)) return abstain("LANE_INVERSION_UNPROVEN_8527")
        return when (a.bucket) {
            CortexScoreboard7885.Bucket.REFUSE -> result(Decision.TAKE_CANDIDATE, "RAW_REFUSE_OPERATOR_TAKE_CANDIDATE")
            CortexScoreboard7885.Bucket.STRONG -> result(Decision.REFUSE, "RAW_STRONG_OPERATOR_REFUSE")
            CortexScoreboard7885.Bucket.NEUTRAL -> abstain("RAW_NEUTRAL")
        }
    }

    private val evaluations = Array(Decision.values().size) { AtomicLong(0L) }
    internal fun note8522(projection: Projection): Projection = projection.also {
        evaluations[it.decision.ordinal].incrementAndGet()
    }

    fun statusLine8522(): String = "$POLICY_LABEL_8522 scope=Solana/native opportunity only " +
        "projection evaluations: raw REFUSE→candidate=${evaluations[Decision.TAKE_CANDIDATE.ordinal].get()} " +
        "raw STRONG→REFUSE=${evaluations[Decision.REFUSE.ordinal].get()} abstain=${evaluations[Decision.ABSTAIN.ordinal].get()} " +
        "(not trades; raw forecasts/outcomes and earned authority unchanged)"
}
