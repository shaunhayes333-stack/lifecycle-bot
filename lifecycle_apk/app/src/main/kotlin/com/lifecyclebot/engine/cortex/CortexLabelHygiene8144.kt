package com.lifecyclebot.engine.cortex

/**
 * V5.0.8144 §LABEL_HYGIENE — which forward labels may grade the Cortex's authority books.
 *
 * 5.0.8142: MOONSHOT REFUSE forward mean +213.2% against STRONG +30.5%. The construction, read end to end
 * (ForwardReturnLabeler7731 -> Cortex7885.onLabel / reviseLabel8006), had four asymmetric biases, none of them signal:
 *   1. REPEATS. A refused coin is re-observed every 15 minutes while it stays a candidate (REOBSERVE_MS_7731), and a
 *      coin that keeps running stays a candidate for hours: one runner contributed a REFUSE label every quarter hour.
 *      A STRONG read that was bought holds a position and is not re-observed. One coin, many REFUSE samples.
 *   2. HORIZON. Every graded label was revised along the coin's later path (1 h, 4 h, 24 h, 48 h, 72 h, 7 d) while the
 *      coin "lived" — the 5-minute read the books are documented on became a multi-day hold no lane runs, followed
 *      only for survivors, with no ceiling. The repeated REFUSE labels of one runner were each revised to its run.
 *   3. UNEXECUTABLE. Candidates refused for safety / rug / route / honeypot / freeze / mint-authority / no-liquidity
 *      reasons were graded like any other: the price of a coin nobody could buy (or sell) is not a return.
 *   4. UNRESOLVED. A label whose coin lost every mark before its horizon (GAP) is never booked; refused coins are
 *      priced off-watch and lose their marks far more often than held ones, so the cohort that survives is upward biased.
 * The fix: a label grades the authority books only when the decision was executable and is the first of its coin's
 * episode in that lane ([EPISODE_MS_8144]; an admitted decision always counts — it is a trade); a revision counts only
 * inside the lane's executable hold horizon; decisions that never got a label are counted per cohort (resolved share)
 * and a cohort under CortexWalkForward8144.MIN_RESOLVED_SHARE_8144 neither earns nor blocks authority.
 * Every excluded label is still recorded as an OBSERVED grade record (CortexGradeBook8144) — nothing is discarded.
 */
object CortexLabelHygiene8144 {
    const val EPISODE_MS_8144 = 6L * 3_600_000L
    /** A runner-capable lane holds at most ~6 h (RunnerExitContract8136); every other lane exits inside the hour. */
    private const val EXEC_HORIZON_RUNNER_MS_8144 = 6L * 3_600_000L
    private const val EXEC_HORIZON_DEFAULT_MS_8144 = 60L * 60_000L
    /** Until a lane's fill latency is measured the label re-bases 5 s after the decision: a floor on the missed move. */
    const val UNMEASURED_FILL_HAIRCUT_PCT_8144 = 3.0
    /** A decision with no 5-minute label after this long is unresolved (the labeler books by 5 m + grace). */
    const val LOST_AFTER_MS_8144 = 30L * 60_000L

    private val UNEXECUTABLE_8144 = listOf(
        "SAFETY", "RUG", "HONEYPOT", "FREEZE", "MINT_AUTH", "ROUTE", "TOKEN_MAP", "HARD", "UNSELLABLE",
        "NO_LIQ", "ZERO_LIQ", "LIQ_ZERO", "BLACKLIST", "TOXIC", "QUARANTIN", "SCAM", "HOSTILE",
    )

    // 8196: a candidate outside its own native mandate cannot be labelled as
    // executable merely because its refusal reason lacks a generic safety word.
    private val UNEXECUTABLE_NATIVE_8196 = listOf(
        "OUTSIDE_NATIVE_", "NATIVE_MCAP_TOO_", "NATIVE_TOKEN_TOO_OLD", "NATIVE_TOO_OLD",
        "NATIVE_LANE_LIQ_", "NATIVE_LIQ_UNHYDRATED", "NATIVE_MCAP_UNHYDRATED",
        "NATIVE_SELLABILITY_REJECT", "NATIVE_PRICE_NOT_FRESH", "NATIVE_BIRTH_METADATA_HYDRATING",
        "NATIVE_NO_PAIR", "PAIR_HARD_UNAVAILABLE", "PAIR_PENDING_HYDRATION",
    )

    /** V5.0.8291 — markers of a learned (statistical) veto: the price of the coin it refused is still a return. */
    private val LEARNED_JUDGEMENT_8291 = listOf("LEARNED_", "TOXIC_PATTERN", "TOXIC_BUCKET", "EV_BUCKET", "LOSING_PATTERN")
    /** Physical / safety words that keep a reason unexecutable even beside a learned marker. */
    private val PHYSICAL_8291 = listOf("SAFETY", "RUG", "HONEYPOT", "FREEZE", "MINT_AUTH", "ROUTE", "UNSELLABLE", "NO_LIQ", "ZERO_LIQ", "LIQ_ZERO", "SCAM", "HOSTILE", "BLACKLIST")

    /** Pure (V5.0.8291): is the (upper-case) refusal [r] a learned veto with no physical block named in it? */
    private fun learnedJudgement8291(r: String): Boolean =
        LEARNED_JUDGEMENT_8291.any { r.contains(it) } && PHYSICAL_8291.none { r.contains(it) }

    /** V5.0.8418 — refusal words that keep a decision unexecutable even as a learned veto (superseded 8291's TOXIC_PATTERN rule). */
    private val TOXIC_8418 = listOf("TOXIC", "MANIPULAT", "WASH_TRAD")

    data class Verdict8144(val executable: Boolean, val repeat: Boolean) {
        /** May this label grade the voters, the bucket / setup / veto books and calibration? */
        val authority: Boolean get() = executable && !repeat
        val tag: String get() = when { !executable -> "UNEXECUTABLE"; repeat -> "REPEAT"; else -> "GRADED" }
    }

    /** Pure: could the decision have been executed? An admit was; a refusal is unless its reason names a hard block. */
    fun executableDecision8144(admitted: Boolean, reason: String?, nativeEligible: Boolean? = null): Boolean {
        if (admitted) return true
        // V5.0.8418 §GRADE_ONLY_EXECUTABLE — restored: where the contemporaneous native specialist evaluated this coin, its
        // NO (on score or anything else) is evidence that this was NOT a tradeable lane entry, so it never grades authority.
        // superseded by V5.0.8418 — 8372 "a scored NO must still teach" (`nativeEligible == false && reason.isNullOrBlank()`).
        if (nativeEligible == false) return false
        val r = reason?.uppercase() ?: return true
        // V5.0.8418 — a toxic / manipulation refusal sits beside the safety classes: never an executable grade.
        if (TOXIC_8418.any { r.contains(it) }) return false
        // V5.0.8291 — a LEARNED veto is a judgement of a buyable coin, not a physical block, even when its name carries a
        // hard word (TOXIC_PATTERN, LEARNED_TOXIC_LANE, TOXIC_BUCKET_HARD_VETO_6249, PAPER_EV_BUCKET_HARD_BLOCK_6405):
        // filed unexecutable, its refusals never reached the veto book, so it could never be shown to refuse winners.
        if (learnedJudgement8291(r)) return UNEXECUTABLE_NATIVE_8196.none { r.startsWith(it) || r.contains("_$it") }
        return UNEXECUTABLE_8144.none { r.contains(it) } &&
            UNEXECUTABLE_NATIVE_8196.none { r.startsWith(it) || r.contains("_$it") }
    }

    /** The native snapshot distinguishes a score/setup judgement from absent data or an impossible trade. */
    fun counterfactualExecutable8372(admitted: Boolean, reason: String?, nativeEligible: Boolean?,
                                     nativeGradeable: Boolean?, nativeReason: String?): Boolean {
        if (admitted) return true
        if (nativeGradeable == false) return false
        if (!executableDecision8144(false, reason, nativeEligible)) return false
        if (!nativeReason.isNullOrBlank()) {
            val native = if (nativeReason.startsWith("NATIVE_", true)) nativeReason else "NATIVE_$nativeReason"
            if (!executableDecision8144(false, native)) return false
        }
        return nativeEligible != false || nativeGradeable == true || !reason.isNullOrBlank()
    }

    /**
     * Pure (V5.0.8291): WHY a decision is unexecutable, as one of a bounded set of classes (null when executable):
     * NATIVE_INELIGIBLE (the lane's own native specialist refused it), NATIVE_MANDATE (outside the lane's mandate band),
     * or the hard word its refusal named. 5.0.8286 reported unexec=803 with no breakdown.
     */
    fun unexecClass8291(admitted: Boolean, reason: String?, nativeEligible: Boolean? = null): String? {
        if (executableDecision8144(admitted, reason, nativeEligible)) return null
        val r = reason?.uppercase().orEmpty()
        if (nativeEligible == false) return if (r.isBlank()) "NATIVE_UNVERIFIED" else "NATIVE_INELIGIBLE"   // V5.0.8418
        if (TOXIC_8418.any { r.contains(it) }) return "TOXIC"
        if (UNEXECUTABLE_NATIVE_8196.any { r.startsWith(it) || r.contains("_$it") }) return "NATIVE_MANDATE"
        return UNEXECUTABLE_8144.firstOrNull { r.contains(it) } ?: "OTHER"
    }

    /** Pure: a refused decision of a coin whose last graded decision in this lane is under [EPISODE_MS_8144] old. */
    fun repeatInEpisode8144(admitted: Boolean, lastGradedAtMs: Long?, atMs: Long): Boolean =
        !admitted && lastGradedAtMs != null && atMs - lastGradedAtMs in 0L until EPISODE_MS_8144

    fun assess8144(admitted: Boolean, reason: String?, lastGradedAtMs: Long?, atMs: Long): Verdict8144 =
        Verdict8144(executableDecision8144(admitted, reason), repeatInEpisode8144(admitted, lastGradedAtMs, atMs))

    private fun executableHorizonMs8144(runnerLane: Boolean): Long = if (runnerLane) EXEC_HORIZON_RUNNER_MS_8144 else EXEC_HORIZON_DEFAULT_MS_8144

    /** V5.0.8392 — the hold the books grade, for the shadow books and the hold layer to run the same window. */
    fun executableHorizonMs8392(runnerLane: Boolean): Long = executableHorizonMs8144(runnerLane)

    /** Pure: does a revision [ageMs] after the decision still describe a hold the lane actually runs? */
    fun revisionCounts8144(runnerLane: Boolean, ageMs: Long): Boolean = ageMs in 0L..executableHorizonMs8144(runnerLane)

    /** Pure: the executable value of a net label — the unmeasured-latency haircut applies until the lane's fills are measured. */
    fun executablePct8144(netPct: Double, latencyMeasured: Boolean): Double = when {
        !netPct.isFinite() -> Double.NaN
        latencyMeasured -> netPct
        else -> netPct - UNMEASURED_FILL_HAIRCUT_PCT_8144
    }

    /** Pure: the execution-cost band of a round trip in percent. */
    fun costBand8144(costPct: Double): String = when {
        !costPct.isFinite() || costPct < 0.0 -> "CUNK"
        costPct < 3.0 -> "C0_3"
        costPct < 6.0 -> "C3_6"
        costPct < 12.0 -> "C6_12"
        else -> "C12P"
    }
}

/**
 * V5.0.8144 §OUTCOME_TO_OWNER — a whole-position close returns to the exact strategy that owned the trade.
 *
 * The owner is read in this order: the sealed entry identity (AateEntryIdentity8136: mode, mint, owner lane, exact setup
 * sealed at the canonical open), then the position's entry snapshot setup tag (EntryStrategySnapshot6450.cortexSetupTag8085),
 * then the setup the Cortex recorded when it bound the position. A sealed identity for another mode or mint is not this
 * trade's. With none of them the close is UNVERIFIABLE: it stays on the lane's realised book and never becomes a
 * strategy-specific sample (missing attribution is not a negative strategy sample — 8136 rule).
 */
object CortexOutcomeRoute8144 {
    enum class Attribution8144 { SEALED_IDENTITY, ENTRY_SNAPSHOT, CORTEX_ENTRY, UNVERIFIABLE }

    data class Owner8144(val lane: String, val setup: String, val attribution: Attribution8144, val mismatch: Boolean) {
        val trainsStrategy: Boolean get() = attribution != Attribution8144.UNVERIFIABLE && setup.isNotBlank()
    }

    /** Pure: the setup id of a LanePlaybook decision tag ("SETUP;ALT,ALT#O=..#V=.."). */
    fun setupOf8144(tag: String?): String = tag.orEmpty().substringBefore(';').substringBefore('#').trim()

    fun owner8144(
        closeMode: String, closeMint: String,
        sealedMode: String?, sealedMint: String?, sealedLane: String?, sealedSetupTag: String?,
        snapshotSetupTag: String?, entryLane: String, entrySetup: String,
    ): Owner8144 {
        val entry = setupOf8144(entrySetup).takeUnless { LaneSetupAttribution8295.deskSetup8295(it) }.orEmpty()
        val sealed = sealedLane?.trim()?.uppercase().orEmpty()
        val sealedOk = sealed.isNotBlank() && sealedMode != null && sealedMode.equals(closeMode, true) && sealedMint == closeMint
        if (sealedOk) {
            val s = setupOf8144(sealedSetupTag)
            // V5.0.8295 — a desk toolkit name filled into the seal at close is not a playbook owner: fall through.
            if (s.isNotBlank() && !LaneSetupAttribution8295.deskSetup8295(s)) {
                val mismatch = (entry.isNotBlank() && entry != s) || !sealed.equals(entryLane.trim(), true)
                return Owner8144(sealed, s, Attribution8144.SEALED_IDENTITY, mismatch)
            }
        }
        val lane = if (sealedOk) sealed else entryLane.trim().uppercase()
        val snap = setupOf8144(snapshotSetupTag).takeUnless { LaneSetupAttribution8295.deskSetup8295(it) }.orEmpty()
        if (snap.isNotBlank()) return Owner8144(lane, snap, Attribution8144.ENTRY_SNAPSHOT, entry.isNotBlank() && entry != snap)
        if (entry.isNotBlank()) return Owner8144(lane, entry, Attribution8144.CORTEX_ENTRY, false)
        return Owner8144(lane, "", Attribution8144.UNVERIFIABLE, false)
    }
}
