package com.lifecyclebot.engine.cortex

/**
 * V5.0.8153 §ENTRY_TRUTH — the pure rules that keep the Cortex's entry learning on what could actually be banked.
 *
 *  LATE FIRST PRICE  ForwardReturnLabeler7731 re-bases a label's entry to the first mark at the lane's fill latency
 *                    (8076/8094), but only inside its 5-60 s window. An observation first priced AFTER the window — a
 *                    refused coin off the watchlist is first priced by the off-watch batch at its 5-minute horizon —
 *                    kept the decision price (a PumpPortal frame nobody could buy at) as its entry. Refused coins are
 *                    priced off-watch far more often than held ones, so the REFUSE cohort was graded from unbuyable
 *                    prices. Such a label is flagged; it stays an OBSERVED record but never an executable value and
 *                    counts as unresolved in its cohort's resolved share.
 *  SELECTED STRATEGY A decision record carries the strategy the coin trades as at the decision
 *                    (LanePlaybook7907.selectedSetup8094, taken synchronously with the decision snapshot); a trade
 *                    record carries the sealed entry identity's setup first, then that decision's, and only then the
 *                    classifier's answer at bind time — a later relabel of the token never re-keys a trade.
 *  UNBOUND CLOSE     a close whose entry read was never bound (ENTRY_BIND_MISSING / stale / owner lane unassessed) used
 *                    to teach nothing; its sealed owner now gets the whole-position outcome on the realised book, with no
 *                    verdict bucket ([NO_READ_BUCKET_8153]) so it never grades a STRONG / REFUSE cohort.
 *  DISPLAY           the verdict stats shown on device come from the tradeable (hygienic) grade records, not the decayed
 *                    bucket books that still carry pre-8146 repeat-inflated labels.
 *  REALISED EVIDENCE the owner-routed realised closes of a cohort cap its authority (CortexRealisedAuthority8136) and a
 *                    cohort whose closes shrink a staked wallet (LearningObjective8153 growth <= 0) holds none.
 * Pure: no Android, no I/O.
 */
object LearningEntryTruth8153 {
    const val NO_READ_BUCKET_8153 = -1

    /** Pure: the label's first priced mark arrived [firstMarkAgeMs] after the decision, past the re-base window. */
    fun lateFirstPrice8153(firstMarkAgeMs: Long, rebaseMaxMs: Long): Boolean = rebaseMaxMs > 0L && firstMarkAgeMs > rebaseMaxMs

    enum class SetupSource8153 { SEALED_IDENTITY, DECISION_SELECTED, CLASSIFIER_AT_BIND, NONE }

    /** Pure: a trade record's strategy — the sealed setup, else the decision's selected setup, else the classifier now. */
    fun entrySetup8153(sealedSetupTag: String?, decisionSelected: String?, classifierNow: () -> String?): Pair<String, SetupSource8153> {
        val sealed = CortexOutcomeRoute8144.setupOf8144(sealedSetupTag)
        if (sealed.isNotBlank()) return sealed to SetupSource8153.SEALED_IDENTITY
        val decided = CortexOutcomeRoute8144.setupOf8144(decisionSelected)
        if (decided.isNotBlank()) return decided to SetupSource8153.DECISION_SELECTED
        val now = CortexOutcomeRoute8144.setupOf8144(try { classifierNow() } catch (_: Throwable) { null })
        if (now.isNotBlank()) return now to SetupSource8153.CLASSIFIER_AT_BIND
        return "" to SetupSource8153.NONE
    }

    /**
     * Pure: the trade record and owner of an eligible close no Cortex read was bound to. The owner comes from the sealed
     * identity (this mode and mint only), else the entry snapshot tag, on [closeLane]; null when no lane is known.
     */
    fun unboundTrade8153(
        positionId: String, mint: String, mode: String, atMs: Long, closeLane: String,
        sealedMode: String?, sealedMint: String?, sealedLane: String?, sealedSetupTag: String?, snapshotSetupTag: String?,
    ): Pair<CortexGradeRecord8144, CortexOutcomeRoute8144.Owner8144>? {
        if (positionId.isBlank()) return null
        val owner = CortexOutcomeRoute8144.owner8144(mode, mint, sealedMode, sealedMint, sealedLane, sealedSetupTag, snapshotSetupTag, closeLane, "")
        if (owner.lane.isBlank()) return null
        val r = CortexGradeRecord8144('T', positionId, "$mint|${owner.lane}", atMs, owner.lane, owner.setup, "-",
            LanePlaybook7907.TIMING_UNKNOWN_8094, "CUNK", "UNK", NO_READ_BUCKET_8153, Double.NaN, true, true, false)
        r.mode = mode
        return r to owner
    }

    data class VerdictRow8153(
        val strongN: Int, val strongMean: Double,
        val neutralN: Int, val neutralMean: Double,
        val refuseN: Int, val refuseMean: Double,
        val strongResolved: Double, val refuseResolved: Double,
    )

    /** Pure: the verdict stats of [lane] read from the tradeable grade records (executable, first of episode, on time). */
    fun verdictRow8153(book: CortexGradeBook8144, lane: String): VerdictRow8153 {
        val s = CortexScoreboard7885.Bucket.STRONG.ordinal
        val nu = CortexScoreboard7885.Bucket.NEUTRAL.ordinal
        val r = CortexScoreboard7885.Bucket.REFUSE.ordinal
        val st = CortexWalkForward8144.stat8144(book.executableSeries(lane, null, s))
        val nt = CortexWalkForward8144.stat8144(book.executableSeries(lane, null, nu))
        val rt = CortexWalkForward8144.stat8144(book.executableSeries(lane, null, r))
        return VerdictRow8153(st.n, st.mean, nt.n, nt.mean, rt.n, rt.mean, book.resolvedShare(lane, s), book.resolvedShare(lane, r))
    }

    /**
     * Pure: the ceiling the owner-routed realised closes [xs] (one cohort, one account mode) put on authority: the
     * CortexRealisedAuthority8136 cap, and 0 once [CortexRealisedAuthority8136.MIN_REALISED_N_8136]+ closes shrink a
     * staked wallet (objective growth <= 0) even when their mean is positive.
     */
    fun realisedEvidenceCap8153(xs: List<Double>): Double {
        val st = CortexWalkForward8144.stat8144(xs)
        val base = CortexRealisedAuthority8136.cappedFraction8136(1.0, st.n.toDouble(), if (st.n > 0) st.mean else 0.0, st.se)
        if (st.n >= CortexRealisedAuthority8136.MIN_REALISED_N_8136 && !(LearningObjective8153.score8153(xs).growthPerTradePct > 0.0)) return 0.0
        return base
    }
}
