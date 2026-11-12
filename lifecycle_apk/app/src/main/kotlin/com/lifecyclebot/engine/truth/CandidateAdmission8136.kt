package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8136 §ONE_CANDIDATE_ADMISSION_RECORD.
 *
 * 5.0.8127 top refusals: 729 generic score floor (CANONICAL_V3_SCORE_FLOOR_7243), 230 losing tactic, 192 proven-only,
 * 161 negative timing. Each came from its own gate reading its own slice of the evidence, so a candidate whose OWN
 * record proved it pays (a fired setup on 30+ graded labels with mean - SE above cost) could still be refused by the
 * generic V3 meme score (a prior that scores fresh launches 9-32), and the executor's pre-lease floor refused the
 * same candidate a second time on the same generic number. The reverse also happened: a lane-wide bypass (ladder,
 * lane labels, retired veto population, playbook score) cleared the generic floor for a coin whose selected setup
 * was losing on its own labels.
 *
 * One record per candidate now compares, in one place: lane + setup, regime + lifecycle stage, input freshness,
 * expected executable gross move, round-trip cost (fees, tips, slippage, impact), expected net and downside, the
 * setup / timing / cell / stage evidence with its confidence interval (n, mean, stderr), runner-tail potential and
 * the safety refusals, and returns ONE terminal admission with its evidence trace:
 *
 *   ADMIT       supported candidate-specific edge (specific = true), or the generic prior clears (specific = false)
 *   REFUSE      hard safety, structural selling risk, a candidate-specific loss pattern, adverse timing, bad
 *               execution economics (specific = true) — or no supported edge and the generic prior fails
 *   DEFER_DATA  core inputs unobserved or the price stale: recoverable, re-evaluated within a bounded window
 *               ([deferBudget8136]); never NO_TRIGGER, never a fabricated setup, never a permanent verdict.
 *
 * The generic V3 score is a PRIOR: once this record admits on supported specific edge, a generic score floor does
 * not independently refuse it (FinalDecisionGate.playbookClearsFloor7937 → [floorVerdict8136]). Thin positive
 * evidence (n under [SUPPORT_MIN_N_8136]) never bypasses the generic prior. A runner label never overrides a proven
 * loss pattern, a hostile mint, structural risk or bad economics: those are checked first.
 */
object CandidateAdmission8136 {

    enum class Terminal8136 { ADMIT, REFUSE, DEFER_DATA }

    /** One measured record: [n] graded labels, mean and standard error (%). [netOfCost]: the labels already carry the round trip. */
    data class Evidence8136(
        val kind: String,
        val id: String,
        val n: Int,
        val meanPct: Double,
        val sePct: Double,
        val runnerRate: Double = Double.NaN,
        val netOfCost: Boolean = true,
        /** V5.0.8198 — the late (60-minute, label slot "240") read of the same record: n and mean net (%). */
        val lateN: Int = 0,
        val lateMeanPct: Double = Double.NaN,
        /** V5.0.8290 — the record is OUTLIER_SUSPECT (RobustLabelRead8185): it proves nothing for live money. */
        val suspect8290: Boolean = false,
    ) {
        val lowerPct: Double get() = if (meanPct.isFinite() && sePct.isFinite()) meanPct - sePct else Double.NaN
        val upperPct: Double get() = if (meanPct.isFinite() && sePct.isFinite()) meanPct + sePct else Double.NaN
        val compact: String get() = "$kind:$id n$n ${fmt8136(meanPct)}±${fmt8136(sePct)}"
    }

    /** Everything the admission compares for one candidate (mint + lane + selected setup). Pure data. */
    data class Candidate8136(
        val mint: String,
        val lane: String,
        /** The strategy the coin trades as (LanePlaybook7907.selectedSetup8094); null when it could not be classified. */
        val selectedSetup: String?,
        val regime: String = "",
        val stage: String = "",
        /** Age of the last observed price; Long.MAX_VALUE when the coin was never priced. */
        val priceAgeMs: Long = 0L,
        /** Core classifier inputs the coin is missing (LanePlaybook7907 DATA_INCOMPLETE), e.g. "shape:UNKNOWN". */
        val missingInputs: List<String> = emptyList(),
        val expectedGrossPct: Double = Double.NaN,
        val costPct: Double = Double.NaN,
        /** V5.0.8209 — the executable size (SOL) [costPct] was quoted for, and that quote's breakdown; immutable with the decision. */
        val costSizeSol: Double = Double.NaN,
        val costBreakdown: String = "",
        val costMargin8391: Double = NetEdge8030.EDGE_MULT,
        val downsidePct: Double = Double.NaN,
        /** Executable move per fired setup; an alternative cannot inherit the selected setup's economics. */
        val expectedGrossBySetup: Map<String, Double> = emptyMap(),
        /** Every fired setup's own record in this lane (the selected one included). */
        val setups: List<Evidence8136> = emptyList(),
        val cell: Evidence8136? = null,
        /** The selected setup's record at the coin's current timing band. */
        val timing: Evidence8136? = null,
        val stageRecord: Evidence8136? = null,
        /** The tactic the coin would be traded as, when its realised record in this lane is losing. */
        val losingTactic: String? = null,
        /** A fresh launch: its price must be fresher ([HOT_STALE_PRICE_MS_8136]). */
        val hotLaunch: Boolean = false,
        val safetyRefusal: String? = null,
        val structuralRisk: String? = null,
        val genericScore: Double = Double.NaN,
        val genericFloor: Double = Double.NaN,
        /** V5.0.8153 — each fired setup's record at the coin's CURRENT timing band (StrategyRank8153 ranks with it). */
        val setupTimings: Map<String, Evidence8136> = emptyMap(),
        /** Candidate generation pinned by the live admission adapter; 0 for pure fixtures. */
        val candidateVersion: Long = 0L,
        /**
         * V5.0.8169 — true (live adapter): a CELL / STAGE loss pattern refuses only on [cellLoss8169] / [stageLoss8169],
         * the records booked since the grading epoch (ForwardReturnLabeler7731.freshCellStatFor8169); older evidence
         * decays to neutral. False (pure fixtures): [cell] / [stageRecord] are the loss records, as before.
         */
        val lossEpochAware8169: Boolean = false,
        /** V5.0.8238 — true (live adapter, LIVE account): the generic prior alone cannot admit real money. */
        val live: Boolean = false,
        val cellLoss8169: Evidence8136? = null,
        val stageLoss8169: Evidence8136? = null,
        /**
         * V5.0.8200 — the Cortex's STRONG read on THIS candidate, as the executable-net record of that lane's STRONG reads
         * (Cortex7885.strongCohortEvidence8200). Null when the read is not STRONG, the lane is uncalibrated / inverted /
         * barred, or the coin is hard-blocked. It carries a lane-wide stage loss at the VALID margin and is a candidate-
         * specific edge at the SUPPORTED margin, exactly like a fired setup; it never carries the coin's own losing cell.
         */
        val cortexStrong8200: Evidence8136? = null,
        /** 8213: fresh qualified, authoritative native verdict of THIS lane, not an inferred score or another lane's claim. */
        val nativeQualified8213: Boolean = false,
        /** Native's own named setup is retained when optional playbook features prevent classification. */
        val nativeSetup8213: String? = null,
        val entryWait8506: String? = null,
        /** Executed-print velocity witness, current for at most 20 seconds (RunTape8293). */
        val highVelocityTape8520: Boolean = false,
        /** Conservative 30m GROSS executed-tape cohort lower bound; NaN when evidence is thin. */
        val tapeLowerGross8520: Double = Double.NaN,
        /**
         * V5.0.8507 — the coin is a watchlist re-check (TokenState.source WATCHLIST: re-hydrated by the pair poll, no live
         * discovery event). 8506: that source n1270 -10.9% 5-min net and 349 of the buy-qualified rows; its live buys all
         * bought the top. Read only by the native-qualified live opinion door below.
         */
        val watchlistRecheck8507: Boolean = false,
        /**
         * V5.0.8245 — RunnerGrab7967: this coin is a grab right now (proven runner cell n >= 20, mean - SE >= +20% net
         * at 60 m; chart firming / not breaking; hard safety and Mayhem clear; the grab record standing). Candidate-
         * specific positive evidence at the SUPPORTED margin beside the setup / cell / Cortex records; it never carries
         * the coin's own losing cell and still answers to economics and the data step's critical inputs.
         */
        val runnerGrab8245: Evidence8136? = null,
        /**
         * V5.0.8283 — core history inputs this token is too young to have (LifecycleData8283: a 5 m move at 15 s, shape
         * before 30 s). Recorded in the trace and NOT waited for; they are never in [missingInputs] and never filled in.
         */
        val historyUnavailable8283: List<String> = emptyList(),
        /**
         * V5.0.8290 — the lane's realised label-to-bank shortfall (points, LIVE only).
         * V5.0.8316 §THE_SHORTFALL_IS_ONE_WAY — the records above are RAW: the loss tests ([losing8136], [clearlyLosing8136],
         * [provenLosing8136]) read them as graded. 8290 took the shortfall off every record at build, so a +20 % SHITCOIN
         * setup read -23 % and refused as SETUP_RECORD_LOSING (8314: 48 ADMIT / 3,626 REFUSE). The shortfall now lives
         * only in the shifted twins below, which the SUPPORT tests read ([supported8136], [bestSetup8153], edge assembly):
         * it can still deny a positive proof, never manufacture a loss.
         */
        val liveShortfall8290: Double = 0.0,
        /**
         * V5.0.8323 — the lane's cheat sheet (LaneCheatSheet8323) per fired setup: [SHEET_FAVOUR_8323] supports (its record
         * cleared its prior with evidence and the bank agrees), [SHEET_AVOID_8323] refuses (a clear loss on its labels or live
         * closes, not on its re-probe slot). Setups absent are NEUTRAL: the existing rules decide.
         */
        val sheet8323: Map<String, String> = emptyMap(),
        /**
         * V5.0.8324 §TAIL_AWARE_LOSS — runner lanes only (live adapter): the lane's measured runner share and runner premium
         * (E[run] - E[non-run], points) from the Cortex tail pond (CortexTailEv8324). The LOSS tests read each record credited
         * the runners its sample could still be missing ([tailCredited8324]); NaN (pure fixtures, scalp lanes) = as before.
         */
        val tailLaneRate8324: Double = Double.NaN,
        val tailPremium8324: Double = Double.NaN,
        /**
         * V5.0.8325 §LIVE_NEEDS_LOCAL_LANE_AND_SETUP — live adapter, LIVE account only: the lane's own live closes
         * [closes, mean %] (Cortex7885.laneRealised8295) and the lane's own graded label pond (executable-net, Cortex7885
         * tailPond8324) as a record. Null (pure fixtures, paper) = not judged here. See [liveLocalGuard8325].
         */
        val laneRealised8325: DoubleArray? = null,
        val laneLabel8325: Evidence8136? = null,
        /**
         * V5.0.8424 — this candidate is the shut lane's admitted PROBE (LaneRealisedProbe8424 via
         * EntryEconomics8329.laneRealisedRefusal8329 == null): the realised-loss governor sizes it down, it does not refuse it.
         */
        val laneProbe8424: Boolean = false,
        /**
         * V5.0.8429 — the lane's one PROBE slot is open to this candidate (LaneRealisedProbe8424.slotOpen8429: no live position
         * open in the lane, no other coin's fresh claim). A learned-negative record door (setup / cell / stage / tactic /
         * timing / falling-hour / cheat sheet) then counts and sizes down instead of refusing: the record can only change
         * with a new close, and a lane that never trades never gets one. Hard safety, structure, economics and data still bind.
         */
        val recordProbe8429: Boolean = false,
        /**
         * V5.0.8418 — why the SELECTED setup's expected move is NaN (NetEdge8030.moveWait8418): [NetEdge8030.MOVE_WAIT_HISTORY_8418]
         * (a sparse history: the data step waits for "move:HISTORY_SPARSE", which a candle / hour arrival answers) or
         * [NetEdge8030.MOVE_WAIT_FALLING_8418] (a momentum lane's falling hour with no supported strategy: an observation, not
         * missing data — refused as [FALLING_HOUR_UNPROVEN_8418], never deferred forever). Null = a number, or no reason known.
         */
        val moveWait8418: String? = null,
        /**
         * V5.0.8418 — the lane's falling-hour record (FallingHourCohort8418: forward-labelled outcomes, net of cost, of
         * momentum-lane decisions taken on a falling hour with no supported strategy). Read only when [moveWait8418] is
         * the falling hour: proven losing refuses, anything less defers until the hour turns. Null = nothing graded.
         */
        val fallingHourProof8418: Evidence8136? = null,
        /** Current predicate matches, independent of whether a historical performance book exists yet. */
        val firedSetups8528: Set<String> = emptySet(),
        /** V5.0.8537 — the learned edge model's prediction for this coin and whether it has earned authority (EdgeModel8537). */
        val edge8537: EdgeModel8537.Verdict? = null,
        /** V5.0.8538 — LIVE: the 5.0.8069–8114 positive edge this coin carries (LiveEdgeGate7877.edge8114Live8538), else null. */
        val liveEdge8114: String? = null,
        /** V5.0.8723 — the coin's origin x age x pool cell when that cell is proven to pay (PayingCell8723); else null. */
        val marketCell8723: Evidence8136? = null,
        /** V5.0.8546 — LIVE, Solana meme lane, and MemeRegime8546 reads COLD (every source losing). */
        val regimeCold8546: Boolean = false,
    ) {
        /** V5.0.8316 — every fired setup's record past the realised shortfall (support tests only). */
        val setupsProof8290: List<Evidence8136> get() = setups.map { LiveAdmissionProof8290.shift8290(it, liveShortfall8290) ?: it }
        /** V5.0.8316 — each setup's timing-band record past the shortfall (StrategyRank8153's support ranking only). */
        val setupTimingsProof8290: Map<String, Evidence8136> get() = setupTimings.mapValues { LiveAdmissionProof8290.shift8290(it.value, liveShortfall8290) ?: it.value }
        /** V5.0.8316 — the selected setup's timing record past the shortfall. */
        val timingProof8290: Evidence8136? get() = LiveAdmissionProof8290.shift8290(timing, liveShortfall8290)
        /** V5.0.8316 — the coin's cell past the shortfall (the live positive cell record). */
        val cellProof8290: Evidence8136? get() = LiveAdmissionProof8290.shift8290(cell, liveShortfall8290)
        /** V5.0.8316 — the post-epoch cell past the shortfall (liveCell8210's support read). */
        val cellLossProof8290: Evidence8136? get() = LiveAdmissionProof8290.shift8290(cellLoss8169, liveShortfall8290)
    }

    /** The one authoritative terminal admission and the evidence trace behind it. */
    data class Decision8136(
        val terminal: Terminal8136,
        val reason: String,
        /** The terminal rests on candidate-specific evidence (true) or on the generic prior / missing data (false). */
        val specific: Boolean,
        val chosenSetup: String?,
        val expectedNetPct: Double,
        val trace: List<String>,
        /** V5.0.8153 — the evidence the terminal and [chosenSetup] rest on (the one identity sizing, seal and exit read). */
        val basis: String = "",
        /** V5.0.8209 — the cost record this terminal was decided on (cost %, size quoted, breakdown); travels to FDG/sizing unchanged. */
        val costPct: Double = Double.NaN,
        val costSizeSol: Double = Double.NaN,
        val costBreakdown: String = "",
        val costMargin8391: Double = NetEdge8030.EDGE_MULT,
        /** Lifecycle prior already weighed against this decision's selected strategy. */
        val evaluatedStage: String = "",
        val evaluatedEntryWait: String? = null,
        val nativePhaseWitness8528: Boolean = false,
        /** V5.0.8535 §P0-03 — the fired setup's own rebound clock settled the generic NO_RECLAIM wait. */
        val strategyClockWitness8535: Boolean = false,
        /** V5.0.8577 — a LIVE candidate its own lane qualified: the gate's learned doors are advisory for it (LaneAuthority8577). */
        val laneQualified8577: Boolean = false,
    ) {
        val admitted: Boolean get() = terminal == Terminal8136.ADMIT
        val compact: String get() = "${terminal.name}:$reason${chosenSetup?.let { " setup=$it" } ?: ""} [${trace.joinToString("; ")}]"
    }

    /**
     * V5.0.8418 — a momentum lane's falling hour with no supported strategy (see [Candidate8136.moveWait8418]).
     * Superseded as a terminal (8418 phone run: 403 MOONSHOT refusals on no evidence): kept as the name of the condition.
     */
    const val FALLING_HOUR_UNPROVEN_8418 = "FALLING_HOUR_UNPROVEN_8418"
    /** V5.0.8418 — refused: the lane's falling-hour record is proven losing after cost (FallingHourCohort8418.provenLosing8418). */
    const val FALLING_HOUR_PROVEN_LOSING_8418 = "FALLING_HOUR_PROVEN_LOSING_8418"
    /** V5.0.8418 — deferred: the falling-hour record is not proven; the candidate is re-judged when its hour turns. */
    const val FALLING_HOUR_DEFER_8418 = "DATA_DEFER_FALLING_HOUR_8418"  // "DATA_DEFER_" keeps FdgReceipt8397 reading it as a data wait

    /**
     * V5.0.8435 — Pure: a defer that waits on a KNOWN market condition of the coin (its falling hour) rather than on a
     * missing input. It stays a DEFER (re-judged when the condition changes) but asks no fetch: none can answer it.
     */
    fun conditionWait8435(reason: String?): Boolean = reason == FALLING_HOUR_DEFER_8418
    /** V5.0.8418 — the missing input a falling-hour defer waits for: the hour reading (re-observed via the chg fetch). */
    const val FALLING_HOUR_WAIT_8418 = "chg:FALLING_HOUR_8418"

    internal const val SUPPORT_MIN_N_8136 = 30
    internal const val LOSING_MIN_N_8136 = 12
    internal const val PROVEN_LOSING_MIN_N_8136 = 30
    internal const val PROVEN_LOSING_PCT_8136 = -2.0
    internal const val TAIL_MIN_N_8136 = 100
    internal const val TAIL_MIN_RATE_8136 = 0.10
    internal const val STALE_PRICE_MS_8136 = 5L * 60_000L
    internal const val HOT_STALE_PRICE_MS_8136 = 60_000L

    // V5.0.8170: incomplete optional chart context is not equal to missing executable proof.
    // Even a genuinely profitable, graded setup must NOT compensate for an unobserved
    // sellable venue, liquidity, supply/decimals, price or cost.
    private val CRITICAL_MISSING_8170 = setOf(
        "LIQ", "LIQUIDITY", "PRICE", "ECONOMICS", "ROUTE", "SELLABILITY",
        "TOKEN_MAP", "MINT", "IDENTITY", "DECIMALS", "SUPPLY", "DEPTH", "QUOTE",
        // V5.0.8283 — wallet balance, sellability and economic cost by their other names: binding at every lifecycle stage.
        "WALLET", "BALANCE", "WALLET_BALANCE", "SELLABLE", "COST", "EXECUTABLE_PRICE",
        // V5.0.8418 — an unknown expected move ("move:HISTORY_SPARSE", the named form of "economics") is economics: binding.
        "MOVE",
    )
    fun criticalMissing8170(missing: String): Boolean =
        missing.substringBefore(':').trim().uppercase() in CRITICAL_MISSING_8170


    internal fun fmt8136(v: Double): String = if (v.isFinite()) "%+.1f".format(v) else "-"

    /** Remove only a classifier's absent liquidity claim when a fresh source supplied that fact.
     * Low-liquidity/sellability refusals remain binding downstream. */
    internal fun resolvedMissingInputs(missing: List<String>, liquidityObserved: Boolean): List<String> =
        missing.filterNot { liquidityObserved && it.substringBefore(':').trim().uppercase() in setOf("LIQ", "LIQUIDITY") }

    // ── pure evidence tests ──

    /**
     * Pure: a record supports admission — [SUPPORT_MIN_N_8136]+ labels and mean - SE above the bar (0 for labels already
     * net of cost, else [costPct]); or, on [TAIL_MIN_N_8136]+ labels, a positive point estimate over the bar with
     * [TAIL_MIN_RATE_8136]+ of its labels running (the runner-tail test, LiveEdgeGate7877.runnerVerdict).
     */
    fun supported8136(e: Evidence8136?, costPct: Double): Boolean {
        // V5.0.8290 — an outlier-suspect record never supports an admission (LiveAdmissionProof8290 rule 1).
        if (e == null || e.suspect8290 || e.n < SUPPORT_MIN_N_8136 || !e.lowerPct.isFinite()) return false
        val bar = if (e.netOfCost) 0.0 else if (costPct.isFinite() && costPct > 0.0) costPct else return false
        if (e.lowerPct > bar) return true
        return e.n >= TAIL_MIN_N_8136 && e.meanPct > bar && e.runnerRate.isFinite() && e.runnerRate >= TAIL_MIN_RATE_8136
    }

    /**
     * Pure (V5.0.8324): [e] read for a LOSS test on a runner lane — its mean credited the runners its sample could plausibly
     * still be missing (CortexTailEv8324.missedRunnerPts8324). A record whose runner share is unknown, or no measured lane
     * premium, is returned unchanged. Never used by a support test.
     */
    internal fun tailCredited8324(e: Evidence8136?, laneRate: Double, premium: Double): Evidence8136? {
        if (e == null || !e.runnerRate.isFinite() || !e.meanPct.isFinite()) return e
        val pts = com.lifecyclebot.engine.cortex.CortexTailEv8324.missedRunnerPts8324(e.n.toDouble(), e.runnerRate, laneRate, premium)
        return if (pts > 0.0) e.copy(meanPct = e.meanPct + pts) else e
    }

    const val LIVE_LANE_LOSING_8325 = "LIVE_LANE_REALISED_LOSING_8325"
    const val LIVE_LANE_LABELS_8325 = "LIVE_LANE_LABELS_NOT_POSITIVE_8325"
    const val LIVE_TAIL_ALONE_8325 = "LIVE_TAIL_EVIDENCE_ALONE_8325"
    /** V5.0.8325 — a lane label pond judges the lane on this many graded records. */
    internal const val LANE_LABEL_MIN_N_8325 = 20

    /**
     * Pure (V5.0.8325 §LIVE_NEEDS_LOCAL_LANE_AND_SETUP): why a LIVE candidate about to be admitted may not be, else null.
     *  - the lane's own live closes are losing (3+, mean < 0: LaneRealisedGovernor8295) — the governors already deny a setup
     *    record full proof there, so a cohort / cell / runner-grab / favour read was the door that let losers in;
     *  - the lane's own graded labels (20+, executable-net) are not positive;
     *  - (withdrawn V5.0.8328, see the body) the admitting [edge] is a lane-wide read and [own] is not positive on 12+ labels.
     * Paper and pure fixtures (null lane fields) are unchanged. Hard safety, economics and data refusals came first.
     */
    internal fun liveLocalGuard8325(c: Candidate8136, edge: Evidence8136?, own: Evidence8136?): String? {
        if (!c.live) return null
        // V5.0.8424 — the lane's admitted probe passes this door (LaneRealisedProbe8424: one small-stake position at a time).
        if (com.lifecyclebot.engine.cortex.LaneRealisedGovernor8295.tailRefuted8295(c.laneRealised8325) && !c.laneProbe8424) return LIVE_LANE_LOSING_8325
        val lane = c.laneLabel8325
        // V5.0.8395 (R5) — explicit precedence: safety > candidate-specific valid negatives > COMPLETE supported candidate
        // proof > lane prior. The realised-loss governor above stays separate and binding. A heterogeneous lane-label average
        // yields only to the chosen setup's own live record, supported after cost on at least as many labels as the prior.
        if (lane != null && lane.n >= LANE_LABEL_MIN_N_8325 && !(lane.meanPct > 0.0)) {
            if (setupProofSupersedesLanePrior8395(own, c.costPct)) {
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LANE_PRIOR_SUPERSEDED_BY_SETUP_PROOF_8395_${c.lane.uppercase()}") } catch (_: Throwable) {}
                return null
            }
            // V5.0.8418 §LANE_PRIOR_SIZES_DOWN_NOT_HALTS — the lane-wide label average (every coin the lane looked at, junk
            // included, still carrying the pre-8418 9 % mark cost) refused EVERY live candidate in the lane: 735 of 1,751
            // refusals on the 8418 phone run with the evidence hold gone. Owner policy is size down, not halt, and live size
            // already starts at the small stake until the lane's own live closes prove; so the prior is counted, not binding.
            // The realised-loss governor above (LIVE_LANE_REALISED_LOSING_8325) still refuses.
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LANE_LABEL_PRIOR_ADVISORY_8418_${c.lane.uppercase()}") } catch (_: Throwable) {}
            return null
        }
        // V5.0.8328 — the third read (LIVE_TAIL_ALONE_8325: a lane-wide edge with the setup's own record absent, thin or
        // not positive) is withdrawn: it bound the thin one-SE live loss the 8316 directive made advisory (8257 band) and
        // refused a setup with no record at all. The setup's own clear loss still refuses upstream (clearlyLosing8136);
        // [own] stays in the signature for the trace line.
        return null
    }

    /**
     * V5.0.8429 §A_LOSING_RECORD_SIZES_DOWN_IT_DOES_NOT_HALT — may [door] (a learned-negative record refusal) stand aside for
     * this candidate as the lane's one probe? True only when the lane's probe slot is open to it ([Candidate8136.recordProbe8429]);
     * the door is then counted and traced, never silently dropped. 8422: TIMING_ADVERSE 647, FALLING_HOUR 344, SETUP_RECORD
     * 290 on MOONSHOT alone, every one a book filled in the 9 %-cost / wrong-mark era with no trade able to change it.
     */
    private fun probe8429(c: Candidate8136, trace: MutableList<String>, door: String): Boolean {
        if (!c.recordProbe8429) return false
        trace += "probe8429=$door"
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LEARNED_RECORD_PROBE_8429_$door") } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LEARNED_RECORD_PROBE_8429_${c.lane.uppercase()}") } catch (_: Throwable) {}
        return true
    }

    /** Pure (V5.0.8395): the chosen setup's own record is complete (>= the lane-prior sample) and supported after cost. */
    fun setupProofSupersedesLanePrior8395(own: Evidence8136?, costPct: Double): Boolean =
        own != null && !own.suspect8290 && own.n >= LANE_LABEL_MIN_N_8325 && supported8136(own, costPct) && !losing8136(own)

    /** Pure: losing on its own evidence — [LOSING_MIN_N_8136]+ labels and mean + SE below zero (LanePlaybook7907.setupRecordNegative8094). */
    fun losing8136(e: Evidence8136?): Boolean =
        e != null && e.n >= LOSING_MIN_N_8136 && e.upperPct.isFinite() && e.upperPct < 0.0

    /**
     * V5.0.8257 §A_THIN_BOOK_MUST_NOT_LOCK_ITSELF — 5.0.8256 (327 s): FDG 0 allow / 1,424 block, of which SETUP_RECORD_LOSING
     * 296 + TIMING_ADVERSE 177 + CELL/STAGE 235 + PROVEN_SETUP_ONLY 148 were learned-negative records on an 18-trade,
     * PF 0.10 book; the operator reset learning and the bot bought at once. A record of 12+ labels whose mean sits
     * one SE below zero refused live as if proven, so a lane that had lost a dozen forward labels could never trade
     * to earn the next ones. A thin record binds live only when the loss is CLEAR on its own sample — mean two SE
     * below zero (the n>=30 proven form is unchanged). Inside the one-SE band it is advisory: traced, counted, and the
     * candidate falls through to the edge test, where without a supported setup it is a generic-prior candidate
     * (generic floor / exploration slot), never a bypass of economics or safety.
     */
    fun clearlyLosing8136(e: Evidence8136?): Boolean =
        losing8136(e) && e != null && (provenLosing8136(e) || (e.meanPct + 2.0 * e.sePct) < 0.0)

    /** Pure: proven losing — [PROVEN_LOSING_MIN_N_8136]+ labels and mean + SE below [PROVEN_LOSING_PCT_8136]. */
    internal fun provenLosing8136(e: Evidence8136?): Boolean =
        e != null && e.n >= PROVEN_LOSING_MIN_N_8136 && e.upperPct.isFinite() && e.upperPct < PROVEN_LOSING_PCT_8136

    /**
     * Pure (V5.0.8198) — the 8114 stage rule's runner exemption (TokenMetricStageRouter.judgeStage7928: "the late read that
     * spares runner lanes"): a stage whose 5-minute labels lose but whose 60-minute labels pay (STAGE_LATE_MIN_N_8198+, mean
     * net > 0) is a runner stage, not a losing one. 8136 re-implemented the stage veto here without it, so every stage that
     * pays late (the 600-700% runs) was refused on its 5-minute read while LiveEdgeGate's own 7928 read still spared it.
     */
    fun stageLatePays8198(e: Evidence8136?): Boolean =
        e != null && e.lateN >= STAGE_LATE_MIN_N_8198 && e.lateMeanPct.isFinite() && e.lateMeanPct > 0.0

    /** Same bar as TokenMetricStageRouter.STAGE_MIN_N_7928 (one rule for both readers). */
    const val STAGE_LATE_MIN_N_8198 = 30

    internal const val STALE_CELL_TRACE_8169 = "staleCellLossDecayed8169="
    internal const val STALE_STAGE_TRACE_8169 = "staleStageLossDecayed8169="

    /** Pure (V5.0.8169): the all-time record [full] proves a loss that the post-epoch record [fresh] does not. */
    fun staleLoss8169(epochAware: Boolean, full: Evidence8136?, fresh: Evidence8136?): Boolean =
        epochAware && provenLosing8136(full) && !provenLosing8136(fresh)

    /** Pure: positive but thin — under [SUPPORT_MIN_N_8136] labels; it never bypasses the generic prior. */
    fun thinPositive8136(e: Evidence8136?): Boolean =
        e != null && e.n in 1 until SUPPORT_MIN_N_8136 && e.meanPct.isFinite() && e.meanPct > 0.0

    /** Pure: is the price too old to trade on? A hot launch moves faster, so its bar is tighter. */
    fun priceStale8136(priceAgeMs: Long, hotLaunch: Boolean, live: Boolean = false): Boolean =
        priceAgeMs < 0L || priceAgeMs > (if (live) { if (hotLaunch) PriceAge8549.LIVE_HOT_STALE_MS_8549 else PriceAge8549.LIVE_STALE_MS_8549 }
            else if (hotLaunch) HOT_STALE_PRICE_MS_8136 else STALE_PRICE_MS_8136)   // V5.0.8549 — LIVE meme bars

    /**
     * Shared across all twelve native specialists. Missing optional classifier inputs are NOT an execution-safety
     * failure when a fresh authoritative native lane verdict, a named native strategy, a passing score and a sized,
     * positive-after-cost quote are already available. Never synthesise a price, a route, a quote or an edge:
     * absent/stale executable facts still defer, and known-negative evidence is handled before this helper.
     */
    internal fun optionalNativeContextClears8213(c: Candidate8136, gross: Double): Boolean =
        nativeQualifiedClears8259(c, gross) &&
            c.genericScore.isFinite() && c.genericFloor.isFinite() && c.genericScore >= c.genericFloor

    /**
     * V5.0.8259 — the specialist's own qualification: its native mandate fired a NAMED setup on THIS token with a fresh
     * price, known economics that clear the round trip, every critical executable fact observed, no safety or structural
     * refusal.
     *
     * V5.0.8279 §SELECT_LIKE_8114 — this is the lane's OPINION, not a proven candidate-specific edge. 8259 admitted it as
     * `specific = true`, which stood down every learned quality filter downstream (LiveEdgeGate: proven-only door, chase
     * rule, timing / picked-setup / tactic negatives, watch-first; FDG: V3 base signal, legacy edge veto, cell proof
     * ladder, fresh-launch learned prior) — the filters that selected the 8085–8114 runners. 8261 live book: 18.2 % WR,
     * pf 0.67, MOONSHOT buys at $16k on a firing IGNITION while the playbook read the setup negative. The native admit
     * is now a non-specific admit behind the generic V3 floor (7843): every 8114 door decides it again.
     */
    internal fun nativeQualifiedClears8259(c: Candidate8136, gross: Double): Boolean =
        c.nativeQualified8213 &&
            !c.nativeSetup8213.isNullOrBlank() &&
            !c.nativeSetup8213.equals("NONE", true) &&
            c.costPct.isFinite() && c.costPct > 0.0 &&
            !priceStale8136(c.priceAgeMs, c.hotLaunch, c.live) &&
            c.missingInputs.none(::criticalMissing8170) &&
            c.safetyRefusal == null && c.structuralRisk == null &&
            NetEdge8030.entryClears8030(gross, c.costPct, c.costMargin8391)

    /**
     * V5.0.8520 — use observed extreme tape velocity as a candidate-specific phase witness.
     * This clears only the auxiliary chart/phase/reclaim wait. It never overrides missing
     * price, quote, token-map, sellability, safety, structural risk, losing evidence or
     * the lane's own score and sized executable cost margin.
     */
    internal fun highVelocityFastTrack8520(c: Candidate8136): Boolean =
        c.live && c.highVelocityTape8520 && !c.watchlistRecheck8507 &&
            c.lane.uppercase() in setOf("MOONSHOT", "SHITCOIN", "EXPRESS") &&
            optionalNativeContextClears8213(c, c.expectedGrossPct) &&
            com.lifecyclebot.engine.RunTape8293.conservativeNetFastTrack8520(c.tapeLowerGross8520, c.costPct)

    /** A generic chart classifier's no-match cannot erase a named strategy that actually fired.
     * This settles only NO_PHASE_TRIGGER; it grants no statistical edge and skips no later check.
     */
    internal fun nativePhaseWitness8528(c: Candidate8136): Boolean =
        c.live && c.stage == "NO_SETUP" && c.entryWait8506 == "NO_PHASE_TRIGGER" &&
            !c.selectedSetup.isNullOrBlank() && c.selectedSetup in c.firedSetups8528 &&
            c.sheet8323[c.selectedSetup] != SHEET_AVOID_8323 &&
            optionalNativeContextClears8213(c, c.expectedGrossPct)

    /**
     * V5.0.8535 §P0-03 — a strategy-owned entry clock. The generic NO_RECLAIM wait (stage DUMPING / PEAK_EXHAUSTION) is
     * the momentum-breakout clock; a fired setup whose own clock IS the pullback / dip / reclaim (EntryClock8535:
     * MOMENTUM_PULLBACK_5M, FIRST_DIP_BOUGHT, TREND_DIP_8383, PEAK_RECLAIM_8383 …) is judged on its own witness. Like
     * [nativePhaseWitness8528] it grants no statistical edge and skips no later check: loss records, economics, the
     * critical inputs, safety and structure still bind.
     */
    internal fun strategyClockWitness8535(c: Candidate8136): Boolean =
        c.live && c.entryWait8506 == "NO_RECLAIM" &&
            !c.selectedSetup.isNullOrBlank() && c.selectedSetup in c.firedSetups8528 &&
            com.lifecyclebot.engine.cortex.EntryClock8535.ownsReclaim(c.selectedSetup) &&
            c.sheet8323[c.selectedSetup] != SHEET_AVOID_8323 &&
            optionalNativeContextClears8213(c, c.expectedGrossPct)

    // ── the decision ──

    /** Pure: the one terminal admission for [c]. Order: safety, structure, loss patterns, timing, economics, data, edge, prior. */
    fun decide8136(c: Candidate8136, doorBinds8393: (String) -> Boolean = { true }): Decision8136 {
        val trace = ArrayList<String>(10)
        trace += "lane=${c.lane} version=${c.candidateVersion} setup=${c.selectedSetup ?: "-"} regime=${c.regime.ifBlank { "-" }} stage=${c.stage.ifBlank { "-" }}${if (c.hotLaunch) " hot" else ""}"
        var decisionGross = c.expectedGrossPct
        var net = if (decisionGross.isFinite() && c.costPct.isFinite()) decisionGross - c.costPct else Double.NaN
        trace += "gross=${fmt8136(decisionGross)} cost=${fmt8136(c.costPct)} net=${fmt8136(net)} downside=${fmt8136(c.downsidePct)}" +
            (if (c.costSizeSol.isFinite()) " costSize=${"%.4f".format(c.costSizeSol)}SOL" else "")
        if (c.liveShortfall8290 > 0.0) trace += "liveShortfall8290=${fmt8136(-c.liveShortfall8290)} off every label record at the support tests (loss tests read raw, 8316)"
        fun done(t: Terminal8136, reason: String, specific: Boolean, setup: String?, basis: String = ""): Decision8136 =
            Decision8136(t, reason, specific, setup, net, trace.toList(), basis.ifBlank { reason },
                costPct = c.costPct, costSizeSol = c.costSizeSol, costBreakdown = c.costBreakdown, costMargin8391 = c.costMargin8391,
                evaluatedStage = c.stage, evaluatedEntryWait = c.entryWait8506,
                nativePhaseWitness8528 = t == Terminal8136.ADMIT && setup == c.selectedSetup && nativePhaseWitness8528(c),
                strategyClockWitness8535 = t == Terminal8136.ADMIT && setup == c.selectedSetup && strategyClockWitness8535(c))

        // 1. hard safety and structural selling risk: nothing overrides them.
        c.safetyRefusal?.let { trace += "safety=$it"; return done(Terminal8136.REFUSE, "SAFETY_REFUSED_8136", true, c.selectedSetup) }
        // V5.0.8546 — the regime master switch: in a COLD meme market (MemeRegime8546) LIVE opens nothing new. A door: its
        // refused record can unbind it. (V5.0.8687 — a paper-book family proof no longer carries a coin past it.)
        if (c.live && c.regimeCold8546 && doorBinds8393(LIVE_REGIME_COLD_8546)) {
            trace += "regimeCold8546"
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("${LIVE_REGIME_COLD_8546}_${c.lane.uppercase()}") } catch (_: Throwable) {}
            return done(Terminal8136.REFUSE, LIVE_REGIME_COLD_8546, false, c.selectedSetup)
        }
        // V5.0.8221 §P0d — a FRESH_LAUNCH statistical refusal (learned negative
        // setup record from FreshLaunchSelector7737) is NOT a hard structural
        // hazard. Pre-8221 the structuralRisk field conflated both and short-
        // circuited admission before alternative supported setups were
        // evaluated, so cell/kind-proven alternatives from the same lane never
        // got their chance. Operator 8220 forensic: MONEROCHAN +27139% refused
        // on FRESH_LAUNCH_SETUP_STOPS_FIRST_7737 while the lane had other
        // qualified evidence. The learned refusal falls through here into the
        // alternative-setup evaluation; a true RUG_PRONE still refuses hard.
        c.structuralRisk?.let {
            val isLaunchLearned = it.startsWith("FRESH_LAUNCH_")
            // V5.0.8696 — the structural stage is a selection judgement: it stands aside once its own graded refusals prove
            // it refuses winners (GateOutcome8418.selectionGateRetired8696). Was: "nothing overrides".
            val retired8696 = !isLaunchLearned && c.live && GateOutcome8418.selectionGateRetired8696("CANDIDATE_ADMISSION_8136_STRUCTURAL_RISK_8136", c.lane)
            if (retired8696) trace += "structureRetiredByRecord8696=$it"
            if (!isLaunchLearned && !retired8696) {
                trace += "structure=$it"
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("STRUCTURAL_RISK_8136_WHY_${it.take(40)}") } catch (_: Throwable) {}  // V5.0.8500
                return done(Terminal8136.REFUSE, "STRUCTURAL_RISK_8136", true, c.selectedSetup)
            }
            if (isLaunchLearned) {
                trace += "launchLearned=$it"
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ADMISSION_LAUNCH_LEARNED_FALLTHROUGH_8221") } catch (_: Throwable) {}
            }
        }

        // 2. candidate-specific loss patterns. The coin's own cell proven to pay, or another fired setup supported on its
        //    own record, is the strategy instead (LiveEdgeGate7877: cellOrKindProven8094 / provenSetup8056 multi-strategy).
        // Pre-8210 all-mode cells are audit history, not candidate-specific live approval evidence.
        val liveCell8210 = if (c.lossEpochAware8169) c.cellLoss8169 else c.cell
        // V5.0.8316 — support is read on the cell past the realised shortfall; the loss tests below read the raw cell.
        val laneCellProof8290 = if (c.lossEpochAware8169) c.cellLossProof8290 else c.cellProof8290
        // V5.0.8723 — the coin's market cell (origin x age x pool, PayingCell8723), proven to pay past the lane's realised
        // shortfall, is the candidate's cell evidence when the lane's own cell supports nothing: it carries the lane-wide
        // priors below (flow / stage waits, a cold or losing setup tag, the lane cell, stage and timing records) and its
        // measured net is the economics. Safety, structure, price, critical facts and the size checks still decide.
        val market8723 = LiveAdmissionProof8290.shift8290(c.marketCell8723, c.liveShortfall8290)?.takeIf { supported8136(it, c.costPct) }
        val liveCellProof8290 = if (market8723 != null && !supported8136(laneCellProof8290, c.costPct)) market8723 else laneCellProof8290
        val carried8723 = market8723 != null
        if (carried8723) {
            trace += "marketCell8723=${market8723?.compact}"
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MARKET_CELL_PAYS_8723_${c.lane.uppercase()}") } catch (_: Throwable) {}
        }
        val fastTrack8520 = highVelocityFastTrack8520(c)
        // The runtime adapter supplies only setups whose current predicates fired.
        // Compare them before a generic stage/flow-data wait: that optional context
        // is not an extra mandatory input of an independently observed setup.
        val bestSetup = bestSetup8153(c)
        // V5.0.8539 — owner: "there are still 0 paper meme trades". PAPER is where the stack's strategies are tried: a coin its
        // own lane qualified is explored through the generic entry waits, the falling-hour wait, an unread expected move and
        // the economics margin (each recorded). Rug / safety / structure / stale price / critical facts still refuse paper.
        val paperExplore8539 = paperExplores8539(c)
        if (paperExplore8539) trace += "paperExplore8539"
        val nativePhase8528 = nativePhaseWitness8528(c)
        val strategyClock8535 = strategyClockWitness8535(c)
        if (strategyClock8535) {
            // V5.0.8535 §P0-03 — the strategy-specific signal and the generic clock it answered are both on the record.
            trace += "strategyClock8535=${c.selectedSetup}@${com.lifecyclebot.engine.cortex.EntryClock8535.of(c.selectedSetup)} answers=${c.entryWait8506}"
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ENTRY_WAIT_STRATEGY_CLOCK_8535_${c.lane.uppercase()}_${c.selectedSetup}") } catch (_: Throwable) {}
        }
        if (c.live && c.entryWait8506 != null) {
            val wait = c.entryWait8506
            val missing = when (wait) {
                "FLOW_UNOBSERVED" -> "swaps,bp,chg"
                "STAGE_UNRESOLVED" -> "shape,chg,bp"
                else -> null
            }
            // V5.0.8539 — owner: the meme trader's specialists trade on their own. A flow / stage read the bot cannot observe
            // (8538 run: DATA_INCOMPLETE_DEFER_8136 x3,614, launchTape missing on 36,617 classifications while the PumpPortal
            // per-coin tape is denied) no longer parks a coin its own lane brain has qualified: the read is optional for it.
            val specialist8539 = specialistQualified8539(c)
            if (missing != null && !fastTrack8520 && bestSetup == null && !carried8723) {
                if (!specialist8539) {
                trace += "entryWait8506=$wait"
                trace += "missing=$missing"
                return done(Terminal8136.DEFER_DATA, "DATA_INCOMPLETE_DEFER_8136", false, c.selectedSetup)
                }
                trace += "specialistQualified8539 optionalFlow=$missing"
            }
            if (missing != null && fastTrack8520) trace += "fastTrack8520=$wait executedTapeObserved; optionalStage=$missing"
            else if (missing != null) trace += "supportedSetup8521=${bestSetup?.id} optionalStage=$missing"
            // V5.0.8511 §4A — the generic stage/flow wait no longer pre-empts candidate-specific evidence. It refuses only when
            // (a) no setup of this candidate is SUPPORTED after cost on its own record (bestSetup8153: lower bound past the
            // cost bar or a proven tail; suspect / sheet-AVOID records never count), and (b) its door still binds
            // (Rehearsal8339 / FdgGate8365 demotion, the same test every other non-safety door takes). A supported setup or a
            // demoted door falls through to every later door: losing cell/stage, timing, economics at the quoted size.
            // Safety, structure and the critical input/economic gates below still bind.
            val supported8511 = bestSetup
            // V5.0.8539 — the owning lane's own qualification answers an unobservable flow read and the shared menu's
            // no-match (NO_PHASE_TRIGGER): 8538 run CASHGEN native 53/100 Y refused ENTRY_NO_PHASE_TRIGGER_8506 x308.
            val specialistAnswers8539 = specialist8539 && (missing != null || wait == "NO_PHASE_TRIGGER")
            if (specialistAnswers8539 && missing == null) trace += "specialistQualified8539 ownTrigger=${c.nativeSetup8213 ?: c.lane}"
            if (!fastTrack8520 && !specialistAnswers8539 && !(paperExplore8539 && wait != "RUG_PRONE")) {
                // The original 8511 binding door remains unchanged for ordinary candidates.
                if (supported8511 == null && !carried8723 && !nativePhase8528 && !strategyClock8535 && doorBinds8393("ENTRY_${wait}_8506"))
                    return done(Terminal8136.REFUSE, "ENTRY_${wait}_8506", true, c.selectedSetup)
            }
            trace += if (fastTrack8520) "entryWait8506=$wait overriddenBy=FRESH_EXECUTED_TAPE_8520"
                else if (supported8511 != null) "entryWait8506=$wait overriddenBy=${supported8511.id}"
                else if (nativePhase8528) "entryWait8506=$wait observedSetup8528=${c.selectedSetup} nativeOpinionNotProvenEdge"
                else if (strategyClock8535) "entryWait8506=$wait ownClock8535=${c.selectedSetup} nativeOpinionNotProvenEdge"
                else "entryWait8506=$wait doorAdvisory"
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                (if (supported8511 != null) "ENTRY_WAIT_SUPPORTED_SETUP_FALLTHROUGH_8511_" else "ENTRY_WAIT_DOOR_ADVISORY_8511_") + c.lane.uppercase()) } catch (_: Throwable) {}
        }
        val cellSupported = supported8136(liveCellProof8290, c.costPct)
        // V5.0.8153 — every fired setup ranked on its own record AND its timing band (StrategyRank8153): a supported setup
        //    losing at this point of the move is not the strategy; ties of the conservative net go to the runner rate.
        // V5.0.8324 — the loss tests read the tail-credited record on a runner lane (support tests keep the raw / shifted ones).
        // V5.0.8325 — the loss tests read every record on its own labels again (the 8324 missed-runner credit let thin losing
        //    records trade live: 8323 SHITCOIN 1/7). [tailCredited8324] is no longer read by a live admission test.
        val selectedRec = c.setups.firstOrNull { it.id == c.selectedSetup }
        val timingLoss8324 = c.timing
        var chosen = c.selectedSetup ?: c.nativeSetup8213?.takeIf {
            c.nativeQualified8213 && it.isNotBlank() && !it.equals("NONE", true)
        }
        fun chooseSetup(id: String) {
            chosen = id
            decisionGross = if (id == c.selectedSetup) c.expectedGrossPct
                else c.expectedGrossBySetup[id] ?: Double.NaN
            net = if (decisionGross.isFinite() && c.costPct.isFinite()) decisionGross - c.costPct else Double.NaN
            trace += "economicsSetup=$id gross=${fmt8136(decisionGross)} cost=${fmt8136(c.costPct)} net=${fmt8136(net)}"
        }
        if (losing8136(selectedRec)) {
            // V5.0.8198 — precedence 2 before 3: the selected setup's own losing record is candidate-specific negative
            //    evidence about THIS executable strategy. Only an independently supported alternative setup (a different
            //    strategy, with its own economics) replaces it; a positive cell record names no strategy and no longer
            //    lets the proven-losing setup trade ("carried by cell" admitted it under SPECIFIC_EDGE_PROVEN).
            if (bestSetup == null && carried8723) {
                trace += "setupLosing=${selectedRec?.compact} carried by market cell"
            } else if (bestSetup == null) {
                trace += "setupLosing=${selectedRec?.compact}${if (cellSupported) " cellSupported=${c.cell?.compact} names_no_strategy" else ""}"
                // V5.0.8257 — a thin record inside one SE of zero is advisory, not a live refusal (see clearlyLosing8136).
                if (clearlyLosing8136(selectedRec) && doorBinds8393("SETUP_RECORD_LOSING_8136") && !probe8429(c, trace, "SETUP_RECORD_LOSING_8136")) return done(Terminal8136.REFUSE, "SETUP_RECORD_LOSING_8136", true, c.selectedSetup)
                // V5.0.8316 — the 8290 "liveThinLossBinds" live refusal is withdrawn (8314: SETUP_RECORD_LOSING 407 on
                //    records the shortfall had shifted below zero): 8257's rule holds live too — only a CLEAR loss refuses;
                //    a thin one-SE loser falls to the generic prior, where economics, safety and the V3 floor decide.
                trace += "setupLosingThin=advisory"
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SETUP_RECORD_LOSING_THIN_ADVISORY_8257") } catch (_: Throwable) {}
            } else {
                trace += "altSetup=${bestSetup.compact} over ${selectedRec?.compact}"
                chooseSetup(bestSetup.id)
            }
        }
        // V5.0.8323 — the lane's cheat sheet: an AVOIDED strategy (a clear loss on its labels or live closes, and not on its
        //    re-probe slot) trades only as a supported / favoured alternative; with none it refuses. NEUTRAL falls through.
        if (chosen?.let { c.sheet8323[it] } == SHEET_AVOID_8323) {
            if (bestSetup != null && bestSetup.id != chosen) {
                trace += "sheetAvoid8323=$chosen altSetup=${bestSetup.compact}"
                chooseSetup(bestSetup.id)
            } else {
                trace += "sheetAvoid8323=$chosen"
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SHEET_AVOID_REFUSED_8323_${c.lane.uppercase()}") } catch (_: Throwable) {}
                if (!carried8723 && doorBinds8393("SHEET_AVOID_8323") && !probe8429(c, trace, "SHEET_AVOID_8323")) return done(Terminal8136.REFUSE, "SHEET_AVOID_8323", true, chosen)
            }
        }
        // V5.0.8169 — a loss pattern refuses on the record graded since the epoch; an older one is noted and decays to
        //    neutral (the candidate goes on to the generic prior — never to an ADMIT the stale record cannot support).
        val cellLoss = if (c.lossEpochAware8169) c.cellLoss8169 else c.cell  // V5.0.8325 — own labels (no tail credit)
        // V5.0.8212: UNKNOWN is not a lifecycle stage and carries no loss authority.
        // TokenMetricStageRouter.judgeStage7928 explicitly ignores UNKNOWN; 8136 had
        // independently been blocking the same unclassified tokens on STAGE|LANE|UNKNOWN.
        val stageLoss = if (c.stage.equals("UNKNOWN", ignoreCase = true)) null
            else if (c.lossEpochAware8169) c.stageLoss8169 else c.stageRecord  // V5.0.8325 — own labels (no tail credit)
        if (provenLosing8136(cellLoss) && doorBinds8393("CELL_PROVEN_LOSING_8136")) {
            // V5.0.8240 — the cell (source|lane|mcap|age) is a lane-wide prior over every coin of that shape, exactly like
            //    the stage record below: it names no strategy. 8241 runner ledger: MONPAD +30584% and KimWhale +2962% were
            //    MISSED(CELL_PROVEN_LOSING) while a SUPPORTED setup (n, mean - SE and timing band clearing cost — the
            //    token-specific strategy evidence) had fired on them. As for the stage loss (8173), the supported setup is
            //    the strategy; without one the cell prior refuses. Economics, data and the edge test below still decide.
            if (bestSetup == null) {
                trace += "cellLosing=${cellLoss?.compact}"
                if (!carried8723 && !probe8429(c, trace, "CELL_PROVEN_LOSING_8136")) return done(Terminal8136.REFUSE, "CELL_PROVEN_LOSING_8136", true, chosen)
            } else {
                trace += "cellLosing=${cellLoss?.compact} carried by supported setup ${bestSetup.compact}"
                if (chosen != bestSetup.id) chooseSetup(bestSetup.id)
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CELL_LOSS_CARRIED_BY_SUPPORTED_SETUP_8240") } catch (_: Throwable) {}
            }
        }
        if (staleLoss8169(c.lossEpochAware8169, c.cell, cellLoss)) trace += "$STALE_CELL_TRACE_8169${c.cell?.compact}"
        if (provenLosing8136(stageLoss) && !cellSupported && stageLatePays8198(stageLoss)) {
            trace += "stageLosing5m=${stageLoss?.compact} spared late60m=n${stageLoss?.lateN} ${fmt8136(stageLoss?.lateMeanPct ?: Double.NaN)}"
        }
        if (provenLosing8136(stageLoss) && !cellSupported && !stageLatePays8198(stageLoss) && doorBinds8393("STAGE_PROVEN_LOSING_8136")) {
            // V5.0.8173 — the stage record is a lane-wide prior (every coin of the lane at this lifecycle stage). A fired
            //    setup supported on its own record (StrategyRank8153: n, mean - SE, its own timing band) is the token-specific
            //    edge and is the strategy instead, exactly as for a losing selected setup or tactic above — unless the
            //    coin's own cell is losing. 5.0.8172: STAGE_PROVEN_LOSING refused 432 while LAUNCH_LADDER_PROVEN (n210, auth
            //    1.00) fired on the same launches. Economics, data and the edge test below still decide.
            // V5.0.8180 — bestSetup (SUPPORTED) was null on nearly every live candidate (thin books): the coin's own
            //    evidence at the VALID margin (a fired setup, else its own cell) carries the lane-wide stage loss too.
            val carry8180 = bestSetup ?: StageCarry8180.carry8180(c)
            if (!carried8723 && (carry8180 == null || (losing8136(cellLoss) && bestSetup == null))) {
                trace += "stageLosing=${stageLoss?.compact}"
                if (!probe8429(c, trace, "STAGE_PROVEN_LOSING_8136")) return done(Terminal8136.REFUSE, "STAGE_PROVEN_LOSING_8136", true, chosen)
            } else {
                trace += "stageLosing=${stageLoss?.compact} carried by ${carry8180.kind.lowercase()} ${carry8180.compact}"
                // A VALID carry is not an edge: the strategy (and its measured economics) changes only for a SUPPORTED setup.
                if (bestSetup != null && bestSetup.id != chosen) chooseSetup(bestSetup.id)
            }
        }
        if (!cellSupported && staleLoss8169(c.lossEpochAware8169, c.stageRecord, stageLoss)) trace += "$STALE_STAGE_TRACE_8169${c.stageRecord?.compact}"
        if (c.losingTactic != null && bestSetup == null && !cellSupported && doorBinds8393("TACTIC_RECORD_LOSING_8136") && !probe8429(c, trace, "TACTIC_RECORD_LOSING_8136")) {
            trace += "tacticLosing=${c.losingTactic}"
            return done(Terminal8136.REFUSE, "TACTIC_RECORD_LOSING_8136", true, chosen)
        }
        // 3. timing: the selected setup's record at this point of the move. A token whose own cell pays has already paid
        //    for its timing (its labels start at the fill latency).
        // V5.0.8144 — the default (selected) setup's adverse timing no longer vetoes before the token's own proven
        //    alternatives are weighed: another fired setup supported on its own record is the strategy instead (the
        //    timing book belongs to the selected setup, not to the alternative).
        if (chosen == c.selectedSetup && losing8136(timingLoss8324) && !cellSupported && bestSetup != null && bestSetup.id != c.selectedSetup) {
            trace += "altSetup=${bestSetup.compact} over timing ${c.timing?.compact}"
            chooseSetup(bestSetup.id)
        }
        if (chosen == c.selectedSetup && losing8136(timingLoss8324) && !cellSupported) {
            trace += "timing=${c.timing?.compact}"
            // V5.0.8257 — the same thin-record rule as the setup record: a clear loss refuses, a one-SE band is advisory.
            if (clearlyLosing8136(timingLoss8324) && doorBinds8393("TIMING_ADVERSE_8136") && !probe8429(c, trace, "TIMING_ADVERSE_8136")) return done(Terminal8136.REFUSE, "TIMING_ADVERSE_8136", true, chosen)
            // V5.0.8316 — the 8290 live thin-loss refusal is withdrawn here too (8314: TIMING_ADVERSE 118); see the setup record above.
            trace += "timingThin=advisory"
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TIMING_ADVERSE_THIN_ADVISORY_8257") } catch (_: Throwable) {}
        }

        val chosenRec = c.setups.firstOrNull { it.id == chosen }
        // V5.0.8316 — edge assembly (the SUPPORT tests) reads every record past the realised shortfall: the shifted twins.
        val chosenProof8316 = c.setupsProof8290.firstOrNull { it.id == chosen }
        val edge = listOfNotNull(bestSetup ?: chosenProof8316?.takeIf { supported8136(it, c.costPct) }, liveCellProof8290?.takeIf { cellSupported },
            // V5.0.8290 — the Cortex cohort is read past the same realised shortfall as every other label record.
            LiveAdmissionProof8290.shift8290(c.cortexStrong8200, c.liveShortfall8290)?.takeIf { supported8136(it, c.costPct) && !losing8136(cellLoss) },  // V5.0.8200
            LiveAdmissionProof8290.shift8290(c.runnerGrab8245, c.liveShortfall8290)?.takeIf { grabSupported8245(it) && !losing8136(cellLoss) })   // V5.0.8245 / 8316
            .maxByOrNull { it.lowerPct }
        if (edge?.kind == KIND_RUNNER_GRAB_8245) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ADMISSION_EDGE_RUNNER_GRAB_8245") } catch (_: Throwable) {}
        }
        // Resolve the winning strategy before validating its executable economics.
        if (edge?.kind == KIND_SETUP && edge.id != chosen) chooseSetup(edge.id)

        // V5.0.8171: an unformed optional chart has no gross-move forecast;
        // that does NOT erase a statistically supported NET executable cohort.
        // Translate only the conservative measured net lower bound back to gross
        // using this candidate's KNOWN round-trip cost. Never use a prior, a
        // thin positive, a hypothetical runner tail or an unknown cost.
        // V5.0.8172 — only when the candidate's own move is unformed (no chart). A measured chart with an unmeasured
        // ALTERNATIVE setup defers on economics: the alternative never borrows a move it was not measured on (8153).
        if (!decisionGross.isFinite() && !c.expectedGrossPct.isFinite() && edge != null && edge.netOfCost &&
            edge.lowerPct.isFinite() && edge.lowerPct > 0.0 &&
            c.costPct.isFinite() && c.costPct >= 0.0) {
            decisionGross = edge.lowerPct + c.costPct
            net = edge.lowerPct
            trace += "grossFromProvenNetLower8171=${fmt8136(edge.lowerPct)} cost=${fmt8136(c.costPct)}"
        }
        // V5.0.8723 — the market cell's measured net (its proven lower bound, after cost) is the move this coin is bought
        // for: a first-minutes coin has no chart for a setup to forecast from, and the cell's labels are what such coins did.
        if (edge != null && edge.kind == PayingCell8723.KIND_8723 && edge.lowerPct.isFinite() && edge.lowerPct > 0.0 &&
            c.costPct.isFinite() && c.costPct >= 0.0 && !(decisionGross.isFinite() && decisionGross >= edge.lowerPct + c.costPct)) {
            decisionGross = edge.lowerPct + c.costPct
            net = edge.lowerPct
            trace += "grossFromMarketCell8723=${fmt8136(edge.lowerPct)} cost=${fmt8136(c.costPct)}"
        }

        // V5.0.8418 — the selected setup's move is NaN because its momentum lane's hour is FALLING and nothing supports a
        //    bounce: that is the coin's own observation, refused here by name. 8416: MOONSHOT DATA_INCOMPLETE_DEFER 6,682 — a
        //    falling hour sat in "missing=economics", which no fetch answers, until the window reset and it deferred again.
        val moveWaitChosen8418 = if (chosen == c.selectedSetup) c.moveWait8418 else null
        // V5.0.8418 (phone run: 403 MOONSHOT refusals) — the refusal now needs its own evidence: the lane's falling-hour
        //    record proven losing after cost (FallingHourCohort8418). Unproven, the candidate DEFERS on the hour reading and
        //    is re-judged when the hour turns (the hour is part of its identity, so a held defer is not reused across it).
        if (!decisionGross.isFinite() && moveWaitChosen8418 == NetEdge8030.MOVE_WAIT_FALLING_8418 && !paperExplore8539) {
            val proof8418 = c.fallingHourProof8418
            if (FallingHourCohort8418.provenLosing8418(proof8418) && !probe8429(c, trace, FALLING_HOUR_PROVEN_LOSING_8418)) {
                trace += "fallingHour8418 setup=${chosen ?: "-"} noSupportedStrategy provenLosing=${proof8418?.compact} cost=${fmt8136(c.costPct)}"
                FallingHourCohort8418.noteDecision8418(c.lane, refused = true)
                return done(Terminal8136.REFUSE, FALLING_HOUR_PROVEN_LOSING_8418, true, chosen)
            }
            trace += "fallingHour8418 setup=${chosen ?: "-"} noSupportedStrategy unproven=${proof8418?.compact ?: "n0"} deferUntilHourTurns"
            trace += "missing=$FALLING_HOUR_WAIT_8418,economics"
            FallingHourCohort8418.noteDecision8418(c.lane, refused = false)
            return done(Terminal8136.DEFER_DATA, FALLING_HOUR_DEFER_8418, false, chosen)
        }

        // 4. execution economics: the expected executable move must clear NetEdge8030.EDGE_MULT x the round trip.
        val econKnown = decisionGross.isFinite() && c.costPct.isFinite()
        if (econKnown && !NetEdge8030.entryClears8030(decisionGross, c.costPct, c.costMargin8391) && paperExplore8539) {
            trace += "paperExplore8539 economicsAdvisory gross=${fmt8136(decisionGross)} cost=${fmt8136(c.costPct)}"
        } else if (econKnown && !NetEdge8030.entryClears8030(decisionGross, c.costPct, c.costMargin8391)) {
            // V5.0.8204 — the refusal names its evidence: expected gross, round-trip cost at the ticket it was costed at
            // (CostMeter8151 last ticket / default), and the cost breakdown. 5.0.8202: 319 economic refusals, no record of
            // which notional or liquidity they were judged at.
            trace += "econRefused8204 gross=${fmt8136(decisionGross)} cost=${fmt8136(c.costPct)} needMult=${c.costMargin8391} " +
                "breakdown=${c.costBreakdown.ifBlank { try { NetEdge8030.lastEntryBreakdown8204() } catch (_: Throwable) { "-" } }}"
            return done(Terminal8136.REFUSE, "EXECUTION_ECONOMICS_8136", true, chosen)
        }

        // 5. data: a stale price or unknown economics defers every candidate; missing classifier inputs defer a candidate
        //    with no supported evidence of its own (its setup cannot be judged yet).
        // V5.0.8283 — history the token is too young to have is recorded here, never waited for (LifecycleData8283).
        if (c.historyUnavailable8283.isNotEmpty()) trace += "historyUnavailable8283=${c.historyUnavailable8283.joinToString(",")} judgedOnLaunchEvidence"
        val stale = priceStale8136(c.priceAgeMs, c.hotLaunch, c.live)
        val criticalMissing8170 = c.missingInputs.filter(::criticalMissing8170)
        val nativeOptional8213 = c.missingInputs.isNotEmpty() && edge == null &&
            optionalNativeContextClears8213(c, decisionGross)
        // V5.0.8279 — the native admit sits behind the generic V3 floor again (8114 doctrine, 7843 second floor).
        val nativeLive8259 = edge == null && optionalNativeContextClears8213(c, decisionGross)
        // V5.0.8531 §MINIMUM_GATE_DECIDES — owner: "if we can clear a minimum gate for tokenomics, metadata etc then the buy
        // should proceed". Only the minimum (fresh price, known economics, every CRITICAL_MISSING_8170 fact: liquidity, route,
        // sellability, mint / decimals / supply, depth, quote, wallet, cost, move) defers; optional chart / tape / context gaps
        // go on to the evidence checks below, which still need positive after-cost evidence for a LIVE admit. superseded:
        // `|| (c.missingInputs.isNotEmpty() && edge == null && !nativeOptional8213 && !nativeLive8259)` (8527: DATA_INCOMPLETE
        // _DEFER_8136 x2,062, DATA_NEVER_ARRIVED x1,131 — tokens parked on launchTape / opp / age / chg / bp).
        if (!econKnown && paperExplore8539 && !stale && criticalMissing8170.isEmpty() && c.costPct.isFinite()) trace += "paperExplore8539 moveUnread"
        if (stale || (!econKnown && !(paperExplore8539 && c.costPct.isFinite())) || criticalMissing8170.isNotEmpty()) {
            // V5.0.8418 — an unknown move names what it waits for (candles / the hour), so its fetch and its arrival match.
            val econMissing8418: List<String> = if (econKnown) emptyList()
                else if (c.costPct.isFinite() && moveWaitChosen8418 == NetEdge8030.MOVE_WAIT_HISTORY_8418) listOf(StrategyMove8153.DEFER_FEATURE_8153)
                else listOf("economics")
            val missing = c.missingInputs + (if (stale) listOf("price") else emptyList()) + econMissing8418
            trace += "missing=${missing.joinToString(",")}"
            if (criticalMissing8170.isNotEmpty()) trace += "criticalUnobserved8170=${criticalMissing8170.joinToString(",")}"
            return done(Terminal8136.DEFER_DATA, "DATA_INCOMPLETE_DEFER_8136", false, chosen)
        }
        // A price/route/cost-proven candidate with POSITIVE candidate-specific graded
        // evidence may trade through an OPTIONAL shape/tape/context gap. Do not
        // reclassify optional absence as a fake signal; retain the gap in its seal.
        if (c.missingInputs.isNotEmpty()) trace += "optionalUnobserved8170=${c.missingInputs.joinToString(",")}"
        if (nativeOptional8213) trace += "nativeOptionalCleared8213=${c.nativeSetup8213} score=${fmt8136(c.genericScore)}"
        edgeModelRefusal8537(c, edge, trace)?.let { return done(Terminal8136.REFUSE, it, true, chosen) }

        // V5.0.8325 — a LIVE admit needs the lane's and the setup's own evidence positive after cost (tail / cohort reads support only).
        val own8325 = c.setupsProof8290.firstOrNull { it.id == chosen }
        liveLocalGuard8325(c, edge, own8325)?.let { why ->
            trace += "liveLocal8325=$why edge=${edge?.compact ?: "-"} setup=${own8325?.compact ?: "-"}"
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("${why}_${c.lane.uppercase()}") } catch (_: Throwable) {}
            // V5.0.8723 — the lane's losses were on other kinds of coin; a coin admitted on its market cell answers to that cell.
            if (edge?.kind != PayingCell8723.KIND_8723 && doorBinds8393(why)) return done(Terminal8136.REFUSE, why, true, chosen)
        }
        // 6. supported candidate-specific edge: the generic score is only a prior.
        if (edge != null) {
            if (edge.kind == KIND_RUNNER_GRAB_8245) {
                runnerEntry8252[c.mint] = System.currentTimeMillis()
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("RUNNER_FUNNEL_8254_ADMISSION_EDGE") } catch (_: Throwable) {}
            }
            trace += "edge=${edge.compact}${if (edge.runnerRate.isFinite()) " run=${(edge.runnerRate * 100).toInt()}%" else ""}"
            return done(Terminal8136.ADMIT, "SPECIFIC_EDGE_PROVEN_8136", true, chosen, edge.compact)
        }
        // V5.0.8537 — the learned edge model's authoritative pick is candidate-specific proven evidence (EdgeModel8537).
        if (EdgeModel8537.callOf(c.edge8537) == EdgeModel8537.Call.POSITIVE)
            return done(Terminal8136.ADMIT, EDGE_MODEL_PROVEN_8537, true, chosen ?: c.nativeSetup8213, "EDGE_MODEL_8537:${c.lane}")

        // 7. no supported edge: thin positives are noted, never admitted; the generic prior decides.
        listOfNotNull(chosenRec, c.cell).filter { thinPositive8136(it) }
            .forEach { trace += "thinPositive=${it.compact} needs n>=$SUPPORT_MIN_N_8136" }
        if (c.genericScore.isFinite() && c.genericFloor.isFinite()) {
            trace += "generic=${fmt8136(c.genericScore)} floor=${fmt8136(c.genericFloor)}"
            if (c.genericScore < c.genericFloor) return done(Terminal8136.REFUSE, "GENERIC_SCORE_FLOOR_8136", false, chosen)
        }
        // V5.0.8259 / V5.0.8279 §SELECT_LIKE_8114 — the specialist's own qualification admits as a lane OPINION
        // (specific = false): it clears the 8238 "supported record only" lock (which 8114 did not have) but NOT the 8114
        // live doors — LIVE_PROVEN_SETUP_ONLY_8059 with its runner / cold-start / measured-edge exemptions, the chase
        // rule, the learned setup / timing / tactic negatives, watch-first, the V3 base signal and the cell proof ladder
        // all judge it again. Only a SUPPORTED record (step 6) is candidate-specific evidence.
        // V5.0.8507 §WATCHLIST_RECHECK_NEEDS_ITS_OWN_RECORD — a live watchlist re-check admits on the native lane opinion only
        // when its own cell record is supported (the same evidence the 8238 generic-prior door asks for). Paper / shadow keep
        // learning from it; a candidate-specific supported edge (step 6) already admitted above; the closed-loop veto audit
        // (doorBinds8393) unbinds this door if the re-checks it refuses are proven to win.
        if (nativeLive8259 && c.live && c.watchlistRecheck8507 && !cellSupported && doorBinds8393("LIVE_WATCHLIST_RECHECK_UNPROVEN_8507")) {
            trace += "watchlistRecheckUnproven8507 cell=${c.cell?.compact ?: "-"} native=${c.nativeSetup8213}"
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_WATCHLIST_RECHECK_UNPROVEN_8507_${c.lane.uppercase()}") } catch (_: Throwable) {}
            return done(Terminal8136.REFUSE, "LIVE_WATCHLIST_RECHECK_UNPROVEN_8507", false, chosen)
        }
        // V5.0.8536 — owner: no LIVE buy on the lane's opinion alone. 8535 live: 468 of 625 admissions were this door, every
        // live buy came through it, 0 of 5 won, and its picks fell 9.3 % in 5 minutes against 6.9 % for the coins it refused.
        // LIVE money needs a SUPPORTED record (step 6 above); paper keeps admitting the opinion and grades it, so each setup
        // unlocks for LIVE by itself once its own labels prove it. Not a demotable door: specific, no veto-audit unbind.
        // V5.0.8538 — the opinion is not alone when the coin carries one of the 8069–8114 live edges (a proven cell / kind /
        // setup / lane trigger, a runner cell beating its lane, a promoted specialist, an expert copy, a confirmed run, a
        // runner ticket, the chart reader's BUY): it admits, and LiveEdgeGate7877's live backstop reads the same proof.
        // V5.0.8545 §LIVE_DOES_NOT_BUY_THE_HIGH_ON_AN_UNPROVEN_READ — 8542 live entry quality: at the high n3 w0, near the high
        // n5 w0, CONTROLLED_MARKUP n5 w0; lifetime runner-style live buys LOSS_BOUGHT_TOP 28 of 48. Every runner lane's
        // BREAKOUT / PEAK / DUMP stage records are -12 % .. -22 % (5-min executable net), and a lane-wide losing stage record
        // was "carried" by a merely VALID setup / cell (StageCarry8180) into the lane-opinion admit below. LIVE money now
        // needs this stage's own record SUPPORTED (or a supported setup / cell, which admitted above) to buy a coin in its
        // markup or at its peak. A door: learnt and unlearnt through the refused record (doorBinds8393); paper and
        // the paper book keep entering these coins and grading them.
        if (c.live && liveIntoHigh8545(c.stage) && !supported8136(c.stageLoss8169 ?: c.stageRecord, c.costPct) &&
            doorBinds8393(LIVE_INTO_HIGH_UNPROVEN_8545)) {
            trace += "liveIntoHigh8545 stage=${c.stage} record=${(c.stageLoss8169 ?: c.stageRecord)?.compact ?: "-"}"
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("${LIVE_INTO_HIGH_UNPROVEN_8545}_${c.lane.uppercase()}_${c.stage}") } catch (_: Throwable) {}
            return done(Terminal8136.REFUSE, LIVE_INTO_HIGH_UNPROVEN_8545, true, chosen)
        }
        // V5.0.8559 — the launch stage, same rule: 8557 stage records (5-min executable net) LAUNCH SHITCOIN -28.3 % / MOONSHOT
        // -30.6 % / EXPRESS -27.5 % / PROJECT_SNIPER -29.6 % / MANIPULATED -25.4 % / DIP_HUNTER -34.6 %, run <= 5 %; BASE is the
        // only positive stage. LIVE money buys a fresh launch only on that stage's own SUPPORTED record;
        // a learnt door (doorBinds8393); paper and the paper book keep entering launches and grading them.
        if (c.live && liveIntoLaunch8559(c.stage) && !supported8136(c.stageLoss8169 ?: c.stageRecord, c.costPct) &&
            doorBinds8393(LIVE_LAUNCH_UNPROVEN_8559)) {
            trace += "liveLaunch8559 stage=${c.stage} record=${(c.stageLoss8169 ?: c.stageRecord)?.compact ?: "-"}"
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("${LIVE_LAUNCH_UNPROVEN_8559}_${c.lane.uppercase()}") } catch (_: Throwable) {}
            return done(Terminal8136.REFUSE, LIVE_LAUNCH_UNPROVEN_8559, true, chosen)
        }
        if (nativeLive8259 && c.live) {
            if (c.liveEdge8114 != null) trace += "liveNativeCarriedBy8538=${c.liveEdge8114}"
            // V5.0.8539 — learnt and unlearnt: the door binds until its own refused record proves it turns away winners
            // (FdgGate8365 demotion via doorBinds8393; 8538 run: refused n16 run 12 % vs admitted run 1 %).
            else if (!doorBinds8393(LIVE_NATIVE_OPINION_UNPROVEN_8536)) trace += "liveNativeOpinionDoorDemoted8539"
            else {
                trace += "liveNativeOpinionUnproven8536=${c.nativeSetup8213 ?: "-"} generic=${fmt8136(c.genericScore)}"
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_NATIVE_OPINION_UNPROVEN_8536_${c.lane.uppercase()}") } catch (_: Throwable) {}
                return done(Terminal8136.REFUSE, LIVE_NATIVE_OPINION_UNPROVEN_8536, true, chosen)
            }
        }
        if (nativeLive8259) {
            trace += "nativeQualifiedAdmit8259=${c.nativeSetup8213} generic=${fmt8136(c.genericScore)} floor=${fmt8136(c.genericFloor)} opinion8279"
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("NATIVE_QUALIFIED_ADMIT_8259_${c.lane.uppercase()}") } catch (_: Throwable) {}
            if (c.nativeSetup8213 == RUNNER_FORMING_8260) {
                runnerEntry8252[c.mint] = System.currentTimeMillis()
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("RUNNER_FUNNEL_8254_ADMISSION_FORMING_8260") } catch (_: Throwable) {}
            }
            return done(Terminal8136.ADMIT, "NATIVE_QUALIFIED_ADMIT_8259", false, chosen ?: c.nativeSetup8213, "NATIVE_QUALIFIED_8259:${c.nativeSetup8213}")
        }
        if (c.genericScore.isFinite() && c.genericFloor.isFinite()) {
            // V5.0.8238 §LIVE_IS_PROVEN_EVIDENCE_ONLY — 8237 live book: every recorded live loss (CASHGEN|NO_TRIGGER x3,
            // CASHGEN|NONE, MOONSHOT|NONE, …) was a generic-prior admit: a V3 score over a fluid floor of 15 with no setup
            // record, no supported cell, no validated Cortex read. The 700% wins were proven setups. Live money needs a
            // supported record (step 6); the prior alone admits paper and the shadow book, where it is learnt from.
            if (c.live && !cellSupported && doorBinds8393("LIVE_GENERIC_PRIOR_UNSUPPORTED_8238") && c.liveEdge8114 == null) {  // V5.0.8538
                trace += "liveGenericPriorUnsupported8238 cell=${c.cell?.compact ?: "-"}"
                return done(Terminal8136.REFUSE, "LIVE_GENERIC_PRIOR_UNSUPPORTED_8238", false, chosen)
            }
            return done(Terminal8136.ADMIT, "GENERIC_PRIOR_CLEARS_8136", false, chosen,
                if (nativeOptional8213) "NATIVE_QUALIFIED_8213:${c.nativeSetup8213}" else "GENERIC_PRIOR:${chosen ?: "-"}")
        }
        return done(Terminal8136.REFUSE, "NO_SUPPORTED_EDGE_8136", false, chosen)
    }

    /**
     * Pure (V5.0.8153): the best SUPPORTED fired setup of [c] — the same evidence test as [supported8136] / [losing8136],
     * ranked by StrategyRank8153 (tier, conservative net capped by the setup's own timing band, runner rate, sample).
     */
    internal fun bestSetup8153(c: Candidate8136): Evidence8136? {
        // V5.0.8316 — a SUPPORT test: ranked on the records past the realised shortfall (Candidate8136.setupsProof8290).
        val setups8316 = c.setupsProof8290
        val timings8316 = c.setupTimingsProof8290
        // V5.0.8323 — an AVOIDED setup (LaneCheatSheet8323) is never the supported strategy.
        val options = setups8316.filter { !it.suspect8290 && c.sheet8323[it.id] != SHEET_AVOID_8323 }.map { e ->  // V5.0.8290 — a suspect record is never the strategy
            val t = timings8316[e.id]
            StrategyRank8153.Option8153(e.id, e.n, e.meanPct, e.sePct, e.runnerRate, e.netOfCost,
                t?.n ?: 0, t?.meanPct ?: Double.NaN, t?.sePct ?: Double.NaN)
        }
        val best = StrategyRank8153.bestSupported8153(options, c.costPct) ?: return sheetFavour8323(c, setups8316, timings8316)
        return setups8316.firstOrNull { it.id == best.id }
    }

    const val LIVE_NATIVE_OPINION_UNPROVEN_8536 = "LIVE_NATIVE_OPINION_UNPROVEN_8536"
    const val LIVE_INTO_HIGH_UNPROVEN_8545 = "LIVE_INTO_HIGH_UNPROVEN_8545"
    const val LIVE_REGIME_COLD_8546 = "LIVE_REGIME_COLD_8546"
    const val LIVE_LAUNCH_UNPROVEN_8559 = "LIVE_LAUNCH_UNPROVEN_8559"

    /** Pure (V5.0.8559): the coin is in its launch stage (the brief's LAUNCH column). */
    fun liveIntoLaunch8559(stage: String?): Boolean = stage?.trim()?.uppercase() == "FRESH_LAUNCH"

    /**
     * Pure (V5.0.8545): a lifecycle stage where a buy goes into the coin's high (markup, peak). A dump is not here: a fired
     * rebound setup owns its own reclaim clock there, and a dump without a reclaim is already ENTRY_NO_RECLAIM_8506.
     */
    fun liveIntoHigh8545(stage: String?): Boolean =
        stage?.trim()?.uppercase() in setOf("CONTROLLED_MARKUP", "PEAK_EXHAUSTION")
    /**
     * V5.0.8539 — pure: the coin's own lane brain qualified it (an authoritative, eligible native opinion of this lane) and
     * its score clears the lane's fluid floor. Such a coin is the specialist's call: an unobservable flow / stage read is
     * optional for it. Hard safety, structure, stale price, unknown economics and every critical fact still bind.
     */
    /** V5.0.8539 — pure: a PAPER candidate its own lane qualified is explored (paper is where strategies are tried). */
    fun paperExplores8539(c: Candidate8136): Boolean = !c.live && specialistQualified8539(c)

    /**
     * V5.0.8650: the existing CLEARLY losing, candidate-local evidence is part of the ONE admission verdict.
     * The 8604 caller passed { false } for EVERY learned record (including a setup's own repeated losses),
     * after which LaneAuthority8577 promoted the resulting native opinion to LIVE. Keep general lane priors,
     * regime guesses and optional market-data waits adaptive; only already-verified setup/cell/timing loss and an
     * out-of-sample-authoritative negative model verdict bind. CandidateAdmission's supported-alternative and
     * one-per-lane re-probe paths remain the sole exceptions, without a second downstream gate.
     */
    private val VERIFIED_RECORD_DOORS_8650 = setOf(
        "SETUP_RECORD_LOSING_8136", "TIMING_ADVERSE_8136", "CELL_PROVEN_LOSING_8136",
        "SHEET_AVOID_8323", "EDGE_MODEL_NEGATIVE_8537",
    )
    internal fun verifiedRecordDoor8650(reason: String): Boolean = reason in VERIFIED_RECORD_DOORS_8650

    fun specialistQualified8539(c: Candidate8136): Boolean =
        c.nativeQualified8213 && !c.nativeSetup8213.isNullOrBlank() &&
            !c.nativeSetup8213.equals("NONE", ignoreCase = true) &&
            c.genericScore.isFinite() && c.genericFloor.isFinite() && c.genericScore >= c.genericFloor

    const val EDGE_MODEL_NEGATIVE_8537 = "EDGE_MODEL_NEGATIVE_8537"
    const val EDGE_MODEL_PROVEN_8537 = "EDGE_MODEL_PROVEN_8537"

    /**
     * V5.0.8537 — the learned edge model (EdgeModel8537) refuses, in paper and live, a coin it expects to lose after cost once
     * its own out-of-sample picks in this lane have paid (authority). A candidate-specific supported record ([edge]) keeps the
     * coin unless the model expects it to lose more than a full round trip. Without authority the model decides nothing.
     */
    internal fun edgeModelRefusal8537(c: Candidate8136, edge: Evidence8136?, trace: MutableList<String>): String? {
        val v = c.edge8537 ?: return null
        val call = EdgeModel8537.callOf(v)
        trace += "edge8537=${fmt8136(v.predPct)} model=${v.modelKey} oos=n${v.oosSelN}/${fmt8136(v.oosSelMeanPct)} call=$call"
        if (call != EdgeModel8537.Call.NEGATIVE) return null
        // V5.0.8715 — a refusal earned on the refusal record alone binds LIVE money; paper keeps trading those coins, which
        // is what keeps the record honest.
        if (!v.authority && !c.live) { trace += "edge8537RefusalLiveOnly8715"; return null }
        if (edge != null && !(c.costPct.isFinite() && v.predPct <= -c.costPct)) { trace += "edge8537NegativeKeptBy=${edge.compact}"; return null }
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("${EDGE_MODEL_NEGATIVE_8537}_${c.lane.uppercase()}") } catch (_: Throwable) {}
        return EDGE_MODEL_NEGATIVE_8537
    }
    const val LIVE_UNPROVEN_EDGE_8536 = "LIVE_UNPROVEN_EDGE_8536"
    const val SHEET_FAVOUR_8323 = "FAVOUR"
    const val SHEET_AVOID_8323 = "AVOID"

    /**
     * Pure (V5.0.8323): the lane's FAVOURED fired setup (LaneCheatSheet8323: n >= its favour bar, mean - SE above its prior,
     * live closes not losing) supports when no setup clears the 30-label SUPPORTED bar — on its record past the realised
     * shortfall (still above zero), not outlier-suspect, not losing at the coin's timing band. Best lower bound first.
     * Economics, data and every hard gate still decide after it.
     */
    internal fun sheetFavour8323(c: Candidate8136, setups: List<Evidence8136>, timings: Map<String, Evidence8136>): Evidence8136? {
        if (c.sheet8323.isEmpty()) return null
        val fav = setups.filter { e ->
            c.sheet8323[e.id] == SHEET_FAVOUR_8323 && !e.suspect8290 &&
                // V5.0.8325 — FAVOUR is earned on the local record only; admission reads it on 20+ labels again (8323 bar).
                e.n >= com.lifecyclebot.engine.cortex.LaneCheatSheet8323.FAVOUR_MIN_N_8323 &&
                e.lowerPct.isFinite() && e.lowerPct > 0.0 && !losing8136(timings[e.id])
        }.maxByOrNull { it.lowerPct } ?: return null
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SHEET_FAVOUR_SUPPORTS_8323_${c.lane.uppercase()}") } catch (_: Throwable) {}
        return fav
    }

    const val KIND_SETUP = "SETUP"
    const val KIND_CELL = "CELL"
    /** V5.0.8245 — RunnerGrab7967's proven runner cell with the chart firming now. */
    const val KIND_RUNNER_GRAB_8245 = "RUNNER_GRAB"

    /**
     * V5.0.8252 §RUNNER_ENTRY_INTENT — the explicit runner-entry intent: this record ADMITTED the candidate on the
     * runner-grab edge (RunnerGrab7967 forming run in a proven runner cell). Written once per admission, read by
     * RunnerPlan8153 for the position that entry opened; a sealed scalp style can no longer demote it to a scalp plan.
     */
    private val runnerEntry8252 = ConcurrentHashMap<String, Long>()
    private const val RUNNER_ENTRY_WINDOW_MS_8252 = 5L * 60_000L

    /** Was the position that opened at [entryTimeMs] admitted on the runner-grab edge (inside the entry window)? */
    fun runnerEntryAdmitted8252(mint: String, entryTimeMs: Long): Boolean {
        val at = runnerEntry8252[mint] ?: return false
        return entryTimeMs > 0L && entryTimeMs >= at && entryTimeMs - at <= RUNNER_ENTRY_WINDOW_MS_8252
    }

    /** Test seam: the runner-entry admit stamp for [mint] at [atMs]. */
    internal fun stampRunnerEntryForTest8252(mint: String, atMs: Long) { runnerEntry8252[mint] = atMs }
    internal const val GRAB_MIN_N_8245 = 20
    internal const val GRAB_MARGIN_PCT_8245 = 20.0

    /** Pure (V5.0.8245): the grab's own proven bar — fewer labels than [SUPPORT_MIN_N_8136], a far higher net lower bound. */
    fun grabSupported8245(e: Evidence8136?): Boolean =
        e != null && !e.suspect8290 && e.n >= GRAB_MIN_N_8245 && e.lowerPct.isFinite() && e.lowerPct >= GRAB_MARGIN_PCT_8245
    /** V5.0.8260 — the native setup name of a forming runner admitted on an unlearned book ([RunnerGrab7967.formingNative8260]). */
    const val RUNNER_FORMING_8260 = "RUNNER_FORMING_8260"
    private val RUNNER_LANES_8260 = setOf("MOONSHOT", "SHITCOIN", "EXPRESS")
    /** V5.0.8270 — a lane's READY verdict is native readiness for this long; after that only a fresh verdict counts. */
    internal const val NATIVE_READY_FRESH_MS_8270 = 10L * 60_000L
    /** V5.0.8200 — the Cortex's STRONG cohort on this candidate's lane (executable-net labels). */
    const val KIND_CORTEX_8200 = "CORTEX"
    const val KIND_TIMING = "TIMING"
    internal const val KIND_STAGE = "STAGE"

    /**
     * Pure: what the record means for a generic score floor — true clears it (supported specific edge), false holds it
     * with no lane-wide bypass (a specific refusal), null leaves the floor's own bypasses to decide.
     */
    fun floorVerdict8136(d: Decision8136?): Boolean? = when {
        d == null || !d.specific -> null
        d.terminal == Terminal8136.ADMIT -> true
        d.terminal == Terminal8136.REFUSE -> false
        else -> null
    }

    // ── bounded DATA_INCOMPLETE re-evaluation ──

    /** One mint's deferral window: first deferral and how many re-evaluations it has had. */
    data class Defer8136(val firstMs: Long, val count: Int)

    const val DEFER_WINDOW_MS_8136 = 10L * 60_000L
    const val DEFER_MAX_8136 = 40
    const val DEFER_RESET_MS_8136 = 30L * 60_000L

    /** Pure: the next deferral state. A window older than [DEFER_RESET_MS_8136] starts over, so no deferral is permanent. */
    fun nextDefer8136(prev: Defer8136?, nowMs: Long): Defer8136 =
        if (prev == null || nowMs < prev.firstMs || nowMs - prev.firstMs >= DEFER_RESET_MS_8136) Defer8136(nowMs, 1)
        else prev.copy(count = prev.count + 1)

    /** Pure: may this candidate still wait for its data ([DEFER_MAX_8136] re-evaluations inside [DEFER_WINDOW_MS_8136])? */
    fun deferOpen8136(s: Defer8136, nowMs: Long): Boolean =
        s.count <= DEFER_MAX_8136 && nowMs - s.firstMs in 0L..DEFER_WINDOW_MS_8136

    private val defers8136 = ConcurrentHashMap<String, Defer8136>()

    /** Counts one DATA_INCOMPLETE re-evaluation of [mint]; false once its bounded window is spent (the ordinary refusal then stands). */
    fun deferBudget8136(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank()) return false
        val s = defers8136.compute(mint) { _, p -> nextDefer8136(p, nowMs) } ?: return false
        if (defers8136.size > 4_000) defers8136.entries.removeIf { nowMs - it.value.firstMs >= DEFER_RESET_MS_8136 }
        val open = deferOpen8136(s, nowMs)
        if (!open) try { PipelineHealthCollector.labelInc("ADMISSION_DEFER_WINDOW_SPENT_8136") } catch (_: Throwable) {}
        return open
    }

    // ── V5.0.8144 §COALESCED_MISSING_DATA_REEVALUATION ──
    //
    // 5.0.8142: 458 DATA_INCOMPLETE FDG deferrals. Every cycle re-ran the whole admission record (playbook classification,
    // forward-label cells, timing / stage books, cost model) for a candidate whose missing input had not had time to
    // arrive, and every re-run spent one of its 40 bounded re-evaluations. A deferral is now scheduled per mint + missing
    // feature with a backoff ([REEVAL_BASE_MS_8144] doubling to [REEVAL_MAX_MS_8144]): until one of its features is due,
    // the last DEFER_DATA decision is returned as it was and no budget is spent; when one is due, the record is decided
    // again in full. The deferral window, its budget and its reset are unchanged, and nothing but DEFER_DATA is cached.

    /** One mint + missing feature: when it is next due and how many times it has been re-scheduled. */
    data class Reeval8144(val dueMs: Long, val attempts: Int)

    const val REEVAL_BASE_MS_8144 = 5_000L
    const val REEVAL_MAX_MS_8144 = 60_000L

    /** Pure: the next schedule after a re-evaluation at [nowMs] — the backoff doubles per attempt up to the cap. */
    fun nextReeval8144(prev: Reeval8144?, nowMs: Long): Reeval8144 {
        val attempts = (prev?.attempts ?: 0).coerceAtMost(16)
        val wait = (REEVAL_BASE_MS_8144 shl attempts.coerceAtMost(10)).coerceAtMost(REEVAL_MAX_MS_8144)
        return Reeval8144(nowMs + wait, attempts + 1)
    }

    /** Pure: is this feature due (never scheduled, its time reached, or a schedule from a clock that moved back)? */
    fun reevalDue8144(s: Reeval8144?, nowMs: Long): Boolean =
        s == null || nowMs >= s.dueMs || s.dueMs - nowMs > 2 * REEVAL_MAX_MS_8144

    /** Pure: the features a DEFER_DATA decision is waiting for (its "missing=" trace entry). */
    fun missingOf8144(d: Decision8136): List<String> =
        d.trace.firstOrNull { it.startsWith("missing=") }?.removePrefix("missing=")?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

    private class Held8144(
        val candidate: Candidate8136, val decision: Decision8136, val features: List<String>, val atMs: Long,
        val candidateVersion: Long, val mode: String,
    )

    private val reevals8144 = ConcurrentHashMap<String, Reeval8144>()
    /** A cached verdict is valid only for the same candidate evidence and generation. */
    private data class Memo8153(val atMs: Long, val candidate: Candidate8136, val decision: Decision8136)
    private val memos8153 = ConcurrentHashMap<String, Memo8153>()
    private const val MEMO_MS_8153 = 1_500L
    /** V5.0.8223 — the FDG snapshot prior (score, fluid floor, admission size) carried per candidate generation. */
    private data class SnapshotPrior8223(val score: Double, val floor: Double, val sizeSol: Double, val atMs: Long)
    private val snapshotPriors8223 = ConcurrentHashMap<String, SnapshotPrior8223>()
    private const val PRIOR_CARRY_MS_8223 = 120_000L
    private val held8144 = ConcurrentHashMap<String, Held8144>()

    /**
     * V5.0.8399 — is [mint] waiting on a data fetch its admission asked for (a DATA_INCOMPLETE_DEFER held at most
     * [WatchlistEviction8398.AWAIT_DATA_MS_8399] ago)? GlobalTradeRegistry keeps such a coin on the bench until the ask lands.
     */
    fun awaitingData8399(mint: String, nowMs: Long): Boolean {
        if (mint.isBlank() || held8144.isEmpty()) return false
        val prefix = "$mint|"
        for ((key, h) in held8144) {
            if (key.startsWith(prefix) && nowMs - h.atMs in 0L..com.lifecyclebot.engine.WatchlistEviction8398.AWAIT_DATA_MS_8399) return true
        }
        return false
    }

    /** Missing facts from the same lane/generation; a receipt never borrows another candidate's evidence. */
    fun missingForReceipt8397(mint: String, lane: String, version: Long, nowMs: Long): Set<String> {
        val l = CanonicalLaneIdentity6506.desk8392(lane)
        val h = held8144["$mint|$l"] ?: return emptySet()
        if (nowMs - h.atMs !in 0L..REEVAL_MAX_MS_8144 || (version > 0L && h.candidateVersion > 0L && version != h.candidateVersion)) return emptySet()
        return h.features.toSet()
    }
    private val coalesced8144 = AtomicLong(0)

    // ── V5.0.8252 §EVIDENCE_ARRIVAL ──
    // A held DEFER_DATA waited for its backoff even after the missing input had arrived (5.0.8251: DATA_INCOMPLETE_DEFER
    // 577 FDG blocks, SETUP_INPUT_REFRESH_REQUESTED 1,676). When a source answers for [mint] with one of the features a
    // held defer is waiting for, that feature is due NOW: the next decide8136 re-decides the record in full on the new
    // evidence instead of returning the coalesced defer, and the pipeline marks the mint near a decision. Nothing is
    // decided here and no feature is assumed present — the record still reads its own inputs.
    private val arrivalReevals8252 = AtomicLong(0)
    val TAPE_FEATURES_8252: Set<String> = setOf("tape", "swaps", "bp")
    val PAIR_FEATURES_8252: Set<String> = setOf("bp", "chg", "liq", "liquidity", "mcap")

    fun evidenceArrived8252(mint: String, features: Set<String>, nowMs: Long = System.currentTimeMillis(),
                            kinds8283: Set<FeatureKind8283>? = null) {
        if (mint.isBlank()) return
        // V5.0.8283 — the shared feature revision advances first: it is part of every lane's setup-cache identity
        // (LanePlaybook7907.inputKey8084), so the next evaluation rebuilds its setup classification on this evidence.
        FeatureRevision8283.advance8283(mint, kinds8283 ?: FeatureRevision8283.kindsOf8283(features), nowMs)
        // Both readers cache decisions; waking only the defer scheduler leaves stale elections in place.
        // V5.0.8289 — allocation-free match: substringAfter copied every memo key on every evidence bump (a few hundred a
        // second since 8286's per-print bumps), a GC storm of its own.
        memos8153.keys.removeIf { memoForMint8289(it, mint) }
        StrategyElection8243.invalidateEvidence(mint)
        com.lifecyclebot.engine.LaneExecutionCoordinator.invalidateUnsealedEvidence8253(mint)
        // The FDG receipt may be waiting on this exact named transfer as well as on the general admission queue.
        val namedResumed8397 = com.lifecyclebot.engine.HandoffReceiverTasks8153.onEvidenceArrived8397(mint, features, nowMs)
        if (held8144.isEmpty() && !namedResumed8397) return
        var woke = namedResumed8397
        val prefix8289 = "$mint|"  // V5.0.8289 — one prefix per call, not one string per held entry
        for ((key, h) in held8144) {
            if (!key.startsWith(prefix8289)) continue
            for (f in h.features) {
                if (!com.lifecyclebot.engine.FdgReceipt8397.answersFeature(f, features)) continue
                val rk = "$mint|$f"
                reevals8144[rk] = Reeval8144(nowMs, reevals8144[rk]?.attempts ?: 0)
                woke = true
            }
        }
        if (!woke) return
        arrivalReevals8252.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EVIDENCE_ARRIVED_REEVAL_DUE_8252") } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.DataPipeline8191.noteNearDecision8191(mint, nowMs) } catch (_: Throwable) {}
    }

    /**
     * V5.0.8289 — pure, allocation-free: is memo key "mode|mint|lane|version" one of [mint]'s? Same rule as
     * `key.substringAfter('|').startsWith("$mint|")` (a key without '|' is matched from its start).
     */
    internal fun memoForMint8289(key: String, mint: String): Boolean {
        val i = key.indexOf('|')
        val from = if (i < 0) 0 else i + 1
        val end = from + mint.length
        return key.length > end && key[end] == '|' && key.startsWith(mint, from)
    }

    // ── V5.0.8289 — memory census and pressure trim (MemoryGuard7977 registry) ──
    /** A memo / held defer carries a whole [Candidate8136] (setups, timings, evidence) and its [Decision8136] trace. */
    const val DECISION_BYTES_8289 = 3_072L

    fun decisionCacheCount8289(): Int = memos8153.size + held8144.size
    fun estBytes8289(): Long = decisionCacheCount8289() * DECISION_BYTES_8289 + snapshotPriors8223.size * 160L +
        (reevals8144.size + defers8136.size + firstMissing8204.size) * 140L

    /**
     * Pressure trim, unservable entries only: a memo past [MEMO_MS_8153], a held defer past [REEVAL_MAX_MS_8144]
     * (coalescedDefer8144 ignores it), a snapshot prior past [PRIOR_CARRY_MS_8223], a reevaluation schedule past the
     * deferral reset. Nothing a reader can still be served is removed.
     */
    fun trim8289(nowMs: Long = System.currentTimeMillis()): Int {
        val before = decisionCacheCount8289()
        memos8153.entries.removeIf { nowMs - it.value.atMs > MEMO_MS_8153 }
        held8144.entries.removeIf { nowMs - it.value.atMs > REEVAL_MAX_MS_8144 }
        snapshotPriors8223.entries.removeIf { nowMs - it.value.atMs > PRIOR_CARRY_MS_8223 }
        reevals8144.entries.removeIf { nowMs - it.value.dueMs > DEFER_RESET_MS_8136 }
        return (before - decisionCacheCount8289()).coerceAtLeast(0)
    }

    /** Test seam: is the held defer's [feature] for [mint] due right now? */
    internal fun reevalDueForTest8252(mint: String, feature: String, nowMs: Long): Boolean = reevalDue8144(reevals8144["$mint|$feature"], nowMs)

    /** Test seam: hold a DEFER_DATA for [mint] in [lane] waiting on [features], scheduled with the normal backoff. */
    internal fun holdDeferForTest8252(mint: String, lane: String, features: List<String>, nowMs: Long) {
        scheduleDue8144(mint, features, nowMs)
        val c = Candidate8136(mint, lane, null, missingInputs = features)
        held8144["$mint|$lane"] = Held8144(c, Decision8136(Terminal8136.DEFER_DATA, "DATA_INCOMPLETE_DEFER_8136", false, null, Double.NaN, listOf("missing=${features.joinToString(",")}")), features, nowMs, 1L, "LIVE")
    }

    private fun features8144(missing: List<String>): List<String> = missing.ifEmpty { listOf("core:UNKNOWN") }

    /** Schedules every due feature of [mint]; true when at least one was due (the caller re-evaluates now). */
    private fun scheduleDue8144(mint: String, features: List<String>, nowMs: Long): Boolean {
        var due = false
        for (f in features) {
            val key = "$mint|$f"
            val prev = reevals8144[key]
            if (reevalDue8144(prev, nowMs)) { reevals8144[key] = nextReeval8144(prev, nowMs); due = true }
        }
        if (reevals8144.size > 8_000) reevals8144.entries.removeIf { nowMs - it.value.dueMs > DEFER_RESET_MS_8136 }
        return due
    }

    /** The held DEFER_DATA decision for [mint] in [laneRaw] while none of its missing features is due yet, else null. */
    private fun coalescedDefer8144(mint: String, laneRaw: String, candidate: Candidate8136, candidateVersion: Long,
                                   mode: String, nowMs: Long): Decision8136? {
        val h = held8144["$mint|$laneRaw"] ?: return null
        if (nowMs - h.atMs !in 0L..REEVAL_MAX_MS_8144) return null
        if (h.candidateVersion != candidateVersion || h.mode != mode) return null
        // A data response, changed price/economics, changed strategy, or new label evidence invalidates the held defer.
        // Only reuse it when the exact evidence that produced it is still present.
        if (h.candidate != cacheIdentity8144(candidate)) return null
        if (h.features.any { reevalDue8144(reevals8144["$mint|$it"], nowMs) }) return null
        coalesced8144.incrementAndGet()
        return h.decision
    }

    /** Holds a DEFER_DATA decision (scheduling its features) and files the decision in the runner trace. */
    private fun noteDecision8144(mint: String, laneRaw: String, candidate: Candidate8136, d: Decision8136,
                                 candidateVersion: Long, mode: String, nowMs: Long) {
        if (mint.isBlank()) return
        val key = "$mint|$laneRaw"
        if (d.terminal == Terminal8136.DEFER_DATA) {
            val f = features8144(missingOf8144(d))
            if (mode == "LIVE") deferCauses8700(d.trace).forEach { deferCauseCounts8700.computeIfAbsent("${candidate.lane}|$it") { AtomicLong(0) }.incrementAndGet() }
            scheduleDue8144(mint, f, nowMs)
            if (held8144.size > 4_000) held8144.entries.removeIf { nowMs - it.value.atMs > REEVAL_MAX_MS_8144 }
            held8144[key] = Held8144(cacheIdentity8144(candidate), d, f, nowMs, candidateVersion, mode)
        } else {
            // V5.0.8283 — a held DATA defer decided again to a real terminal; "after evidence" when the revision advanced since.
            held8144.remove(key)?.let { h -> try { FeatureRevision8283.noteDeferResolved8283(FeatureRevision8283.advancedAt8283(mint) > h.atMs) } catch (_: Throwable) {} }
        }
        if (d.admitted && d.trace.any { it.startsWith("nativeOptionalCleared8213=") }) try {
            PipelineHealthCollector.labelInc("ADMISSION_NATIVE_OPTIONAL_DATA_ADMITTED_8213_${candidate.lane}")
        } catch (_: Throwable) {}
        try { AdmissionRunnerTrace8144.noteAdmission8144(mint, laneRaw, d.terminal.name, d.reason) } catch (_: Throwable) {}
        try { noteFunnel8283(candidate, d) } catch (_: Throwable) {}  // V5.0.8283 — unique-candidate feature funnel
        try { AdmissionEdge8180.note8180(mode, mint, laneRaw, candidateVersion, d, nowMs) } catch (_: Throwable) {}  // V5.0.8180 / 8198 — this lane's own proof, sizers read it
        // V5.0.8169 — a loss refusal that decayed to neutral on its epoch, counted once per fresh decision.
        try {
            if (d.trace.any { it.startsWith(STALE_CELL_TRACE_8169) }) com.lifecyclebot.engine.ExitDoctrine8169.noteStaleDecay8169(cell = true)
            if (d.trace.any { it.startsWith(STALE_STAGE_TRACE_8169) }) com.lifecyclebot.engine.ExitDoctrine8169.noteStaleDecay8169(cell = false)
        } catch (_: Throwable) {}
        // V5.0.8153 — the one strategy identity: every later reader of LanePlaybook7907.selectedSetup8094 gets this choice.
        try {
            val lane = CanonicalLaneIdentity6506.canonical(laneRaw).uppercase().ifBlank { laneRaw.trim().uppercase() }
            StrategyIdentity8153.record8153(mint, lane, d.chosenSetup, d.basis, nowMs,
                mode, candidateVersion, com.lifecyclebot.engine.cortex.LanePlaybook7907.selectionRevision8198(), d.admitted)
            AdmissionRunnerTrace8144.noteIdentity8153(mint, d.chosenSetup, d.basis)
        } catch (_: Throwable) {}
        if (memos8153.size > 4_000) memos8153.entries.removeIf { nowMs - it.value.atMs > MEMO_MS_8153 }
        memos8153["$mode|$mint|${candidate.lane}|$candidateVersion"] = Memo8153(nowMs, cacheIdentity8144(candidate), d)
    }

    /** V5.0.8283 — FeatureFunnel8283: this lane's natively qualified and admitted unique candidates. */
    private fun noteFunnel8283(c: Candidate8136, d: Decision8136) {
        if (c.nativeQualified8213) FeatureFunnel8283.note8283(c.lane, c.mint, FeatureFunnel8283.Stage8283.NATIVE_QUALIFIED)
        if (d.admitted) FeatureFunnel8283.note8283(c.lane, c.mint, FeatureFunnel8283.Stage8283.ADMISSION)
    }

    /** Candidate price age affects admission only at the stale/fresh boundary. */
    private fun cacheIdentity8144(c: Candidate8136): Candidate8136 = c.copy(
        priceAgeMs = if (priceStale8136(c.priceAgeMs, c.hotLaunch, c.live)) Long.MAX_VALUE else 0L,
        costBreakdown = "",   // V5.0.8209 — the numbers (costPct, costSizeSol) are identity; the prose is not
    )

    /**
     * LiveEdgeGate7877.dataDefer8136: [deferBudget8136] coalesced per mint + missing feature. A call while none of
     * [missing] is due keeps the open window without spending a re-evaluation; a due feature re-evaluates (counted).
     */
    private val firstMissing8204 = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /**
     * V5.0.8204 — the inputs this mint was FIRST deferred on (per deferral window). Exhausting the budget while every one
     * of them is still missing means the requested fetch never completed. Pure given the recorded first set.
     */
    fun deferUnanswered8204(mint: String, missingNow: List<String>, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank()) return false
        val first = firstMissing8204[mint] ?: return false
        return first.isNotEmpty() && missingNow.map { it.trim() }.containsAll(first)
    }

    // ── V5.0.8418 §A_DEFER_ENDS — 8416: DATA_INCOMPLETE_DEFER 6,682 (MOONSHOT) + FETCH_NEVER_COMPLETED 2,127. 8229 kept every
    //    spent window a DEFER, so the receiver task, its rescue slot and the FDG re-ran the same pending coin until the 30-minute
    //    reset opened a new window — the wait never ended. A spent window now ends the wait under a named terminal (a REFUSE
    //    receipt, FdgReceipt8397): [DATA_NEVER_ARRIVED_8418] when the requested fetch never answered any first-missing input,
    //    [DATA_WAIT_SPENT_8418] when some arrived but the record is still incomplete. The assessment stays DEFER (no verdict on
    //    the coin, nothing filled in); arrival inside the window still resumes it (evidenceArrived8252); the reset re-opens it.
    const val DATA_NEVER_ARRIVED_8418 = "DATA_NEVER_ARRIVED_8418"
    const val DATA_WAIT_SPENT_8418 = "DATA_WAIT_SPENT_8418"
    private val deferTerminals8418 = ConcurrentHashMap<String, AtomicLong>()

    /** Pure-ish: the named terminal of a spent data wait, or null while the window is open (counted per reason + first input). */
    fun deferTerminal8418(budgetOpen: Boolean, unanswered: Boolean, missing: List<String>): String? {
        if (budgetOpen) return null
        val r = if (unanswered) DATA_NEVER_ARRIVED_8418 else DATA_WAIT_SPENT_8418
        val f = missing.firstOrNull()?.substringBefore(':')?.trim()?.uppercase()?.take(16).orEmpty().ifBlank { "CORE" }
        if (deferTerminals8418.size < 64 || deferTerminals8418.containsKey("$r|$f")) deferTerminals8418.computeIfAbsent("$r|$f") { AtomicLong() }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("${r}_$f") } catch (_: Throwable) {}
        return r
    }

    // ── V5.0.8703 — the buy door's proof is awaited as a candidate, not as a ticket ──
    // 8689: 30 sealed LIVE tickets, 5 buys. A coin admitted here whose safety proof the buy door would defer on was FDG-
    // allowed, sized and ticketed, deferred at the door three times, ended DEFERRED_DATA_SAFETY_PROOF_8270, and the lease
    // backoff that failure set ended the next tickets on the same coin (BUY_LEASE_BACKOFF_ENDS_TICKET_8545 x9). The same
    // V5.0.8705 — "the same proof" is now the door itself, run as a probe (PreTradeHardGate.proofWait8703), not a copy of it.
    // proof is read here: a LIVE admit whose proof is not ready waits as a data defer (its refresh requested now, at the
    // front of the safety queue) and is decided again, at a fresh price, when the proof is in.
    internal fun proofDeferred8703(d: Decision8136, wait: String): Decision8136 = d.copy(
        terminal = Terminal8136.DEFER_DATA, reason = "DATA_INCOMPLETE_DEFER_8136", specific = false,
        trace = d.trace.filterNot { it.startsWith("missing=") } + "buyDoorProof8703=$wait wouldAdmit=${d.reason}" + "missing=proof:$wait",
        nativePhaseWitness8528 = false, strategyClockWitness8535 = false)

    /** Pure (V5.0.8717): an admit the buy door would hard-block is a refusal carrying the door's own reason. */
    internal fun doorBlocked8717(d: Decision8136, block: String): Decision8136 = d.copy(
        terminal = Terminal8136.REFUSE, reason = "PRETRADE_HARD_BLOCK_$block", specific = true,
        trace = d.trace + "buyDoorBlock8717=$block wouldAdmit=${d.reason}",
        nativePhaseWitness8528 = false, strategyClockWitness8535 = false)

    private fun awaitBuyDoorProof8703(ts: TokenState, live: Boolean, d: Decision8136, nowMs: Long, lane: String): Decision8136 {
        if (!live || !d.admitted) return d
        // V5.0.8717 — 8712: of 16 CASHGEN tickets 2 filled. The door's HARD blocks were not read here (only its proof waits),
        // so a coin it would refuse outright was admitted, sized and ticketed, then died at the door
        // (LP_LOCK_BELOW_REQUIRED_8522 x5 first failures); and a coin still inside the backoff an earlier failed buy set kept
        // being ticketed, each ticket ended by that backoff (ENTRY_CHASE_CONSUMED x11, SEALED_SIZE x14, SAFETY_PROOF x26).
        // One probe answers all three: a block refuses here, a backoff or a proof wait defers here; no ticket is spent.
        val door8717 = try { com.lifecyclebot.engine.PreTradeHardGate.probeDoor8717(ts) } catch (_: Throwable) { null }
        com.lifecyclebot.engine.PreTradeHardGate.doorBlockOf8717(door8717)?.let { block ->
            try { PipelineHealthCollector.labelInc("ADMISSION_REFUSED_BY_BUY_DOOR_8717_${block.take(40)}") } catch (_: Throwable) {}
            return doorBlocked8717(d, block)
        }
        val backoffMs8717 = try { com.lifecyclebot.engine.ExecutionAttemptLease.buyBackoffLeftMs8717(ts.mint, lane.trim().uppercase(), nowMs) } catch (_: Throwable) { 0L }
        if (backoffMs8717 > 0L) {
            try { PipelineHealthCollector.labelInc("ADMISSION_AWAITS_BUY_BACKOFF_8717") } catch (_: Throwable) {}
            return proofDeferred8703(d, "BUY_BACKOFF")
        }
        val wait = com.lifecyclebot.engine.PreTradeHardGate.proofWaitOf8705(door8717)
        if (wait == null) {
            // admitted with a proof past half its life: refreshed now so it is still current at the buy door
            if (com.lifecyclebot.engine.PreTradeHardGate.proofAgeing8703(ts.safety.checkedAt, nowMs))
                try { com.lifecyclebot.engine.SafetyRefreshQueue.request(ts.mint) } catch (_: Throwable) {}
            return d
        }
        try {
            com.lifecyclebot.engine.SafetyRefreshQueue.request(ts.mint)
            com.lifecyclebot.engine.SafetyProofPriority8310.noteDeferred8310(ts.mint, "ADMISSION_$wait", nowMs)
            PipelineHealthCollector.labelInc("ADMISSION_AWAITS_BUY_DOOR_PROOF_8703_$wait")
        } catch (_: Throwable) {}
        return proofDeferred8703(d, wait)
    }

    // ── V5.0.8700 — what each DATA defer is waiting on ──
    // 8689: DATA_INCOMPLETE_DEFER_8136 x1,298 and DATA_DEFER_FALLING_HOUR_8418 x598 on LIVE with one shared reason name and
    // no count of which wait produced them (a stage / flow read, a stale price, an unread move, an unknown cost, a critical
    // fact). Each fresh defer is counted once under every cause its own trace names.
    private val deferCauseCounts8700 = ConcurrentHashMap<String, AtomicLong>()

    /** Pure: the causes a DEFER_DATA decision's [trace] names. Optional gaps listed beside a cause are not causes. */
    internal fun deferCauses8700(trace: List<String>): List<String> {
        val out = ArrayList<String>(3)
        trace.firstOrNull { it.startsWith("entryWait8506=") }?.let { out += "ENTRY_WAIT_" + it.removePrefix("entryWait8506=").trim().uppercase() }
        val critical = trace.firstOrNull { it.startsWith("criticalUnobserved8170=") }?.removePrefix("criticalUnobserved8170=")
            ?.split(',')?.map { it.substringBefore(':').trim().uppercase() }?.filter { it.isNotBlank() }.orEmpty()
        critical.distinct().forEach { out += "CRITICAL_$it" }
        val costKnown = trace.firstOrNull { it.startsWith("gross=") }?.substringAfter(" cost=", "")?.substringBefore(' ')
            ?.toDoubleOrNull()?.isFinite() == true
        val missing = trace.firstOrNull { it.startsWith("missing=") }?.removePrefix("missing=")?.split(',')?.map { it.trim() }.orEmpty()
        missing.firstOrNull { it.startsWith("proof:") }?.let { out += "BUY_DOOR_PROOF_" + it.removePrefix("proof:").uppercase() }   // V5.0.8703
        if ("price" in missing) out += "PRICE_STALE"
        if (FALLING_HOUR_WAIT_8418 in missing) out += "FALLING_HOUR"
        else if (StrategyMove8153.DEFER_FEATURE_8153 in missing) out += "MOVE_HISTORY_SPARSE"
        else if ("economics" in missing) out += if (costKnown) "MOVE_UNREAD" else "COST_UNKNOWN"
        return out.ifEmpty { listOf("UNNAMED") }
    }

    private fun deferCauseLine8700(): String =
        "deferCause8700{" + deferCauseCounts8700.entries.sortedByDescending { it.value.get() }.take(12).joinToString(",") { "${it.key}=${it.value.get()}" } + "}"

    private fun deferTerminalLine8418(): String =
        "deferTerminal8418{" + deferTerminals8418.entries.sortedByDescending { it.value.get() }.take(10).joinToString(",") { "${it.key}=${it.value.get()}" } + "}"

    fun deferBudgetFor8144(mint: String, missing: List<String>, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isNotBlank()) {
            val prev = defers8136[mint]
            val remaining = missing.map { it.trim() }.filter { it.isNotBlank() }.toSet()
            val previousMissing = firstMissing8204[mint]
            // Reopen only after an actual missing fact is now present in the
            // assessed candidate. Repeated ticks/notifications cannot refill a budget.
            val remainingKinds = remaining.map { it.substringBefore(':').lowercase() }.toSet()
            val previousKinds = previousMissing?.map { it.substringBefore(':').lowercase() }?.toSet()
            val progressed = previousKinds != null && remainingKinds.size < previousKinds.size &&
                previousKinds.containsAll(remainingKinds)
            if (prev == null || nowMs < prev.firstMs || nowMs - prev.firstMs >= DEFER_RESET_MS_8136 || progressed) {
                firstMissing8204[mint] = remaining
                if (progressed) {
                    defers8136.remove(mint)
                    reevals8144.keys.removeIf { it.startsWith("$mint|") }
                }
            }
            if (firstMissing8204.size > 4096) firstMissing8204.keys.take(1024).forEach { firstMissing8204.remove(it) }
        }
        if (mint.isBlank()) return false
        if (scheduleDue8144(mint, features8144(missing), nowMs)) return deferBudget8136(mint, nowMs)
        val s = defers8136[mint] ?: return deferBudget8136(mint, nowMs)
        coalesced8144.incrementAndGet()
        try { PipelineHealthCollector.labelInc("ADMISSION_REEVAL_COALESCED_8144") } catch (_: Throwable) {}
        return deferOpen8136(s, nowMs)
    }

    // ── the ledger: counts per terminal admission reason ──

    private val counts8136 = ConcurrentHashMap<String, AtomicLong>()
    private const val MAX_KEYS_8136 = 64

    /** Pure: a data-gap reason (recoverable) rather than a verdict on the token. */
    internal fun dataReason8136(reason: String): Boolean =
        com.lifecyclebot.engine.FdgReceipt8397.outcome(false, reason) == com.lifecyclebot.engine.FdgReceipt8397.Outcome.DEFER_DATA

    /** Pure: an existing gate's verdict (LiveEdgeGate7877.Verdict8083 name / plan wait) as a terminal admission. */
    fun terminalOf8136(verdict: String, reason: String): Terminal8136 = when {
        verdict == Terminal8136.ADMIT.name -> Terminal8136.ADMIT
        verdict == Terminal8136.DEFER_DATA.name -> Terminal8136.DEFER_DATA
        dataReason8136(reason) -> Terminal8136.DEFER_DATA
        else -> Terminal8136.REFUSE
    }

    /** Pure: the bounded ledger key — source, terminal and the reason without its lane / numeric tail. */
    fun ledgerKey8136(source: String, terminal: Terminal8136, reason: String): String {
        val r = reason.substringBefore(':').substringBefore(' ').replace(Regex("_-?\\d+(\\.\\d+)?PCT.*\$"), "")
        // Cut after the first version tag that ends a named reason (drops the lane / setup tail), e.g.
        // PLAYBOOK_SETUP_PROVEN_LOSING_7907_MOONSHOT_X -> PLAYBOOK_SETUP_PROVEN_LOSING_7907.
        val m = Regex("_\\d{4}").findAll(r).firstOrNull { it.range.last >= 11 }
        val stem = if (m != null) r.substring(0, m.range.last + 1) else r
        return "$source|${terminal.name}:${stem.take(44)}"
    }

    fun note8136(source: String, terminal: Terminal8136, reason: String) {
        try {
            val key = ledgerKey8136(source, terminal, reason)
            val k = if (counts8136.size < MAX_KEYS_8136 || counts8136.containsKey(key)) key else "$source|${terminal.name}:OTHER"
            counts8136.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet()
            PipelineHealthCollector.labelInc("CANDIDATE_ADMISSION_8136_${source}_${terminal.name}")
        } catch (_: Throwable) {}
    }

    /** LiveEdgeGate7877.recordAssessment8083 / TradePlan7739: an existing gate's terminal, filed in this ledger. */
    fun noteVerdict8136(source: String, verdict: String, reason: String) = note8136(source, terminalOf8136(verdict, reason), reason)

    fun statusLine8136(): String {
        val byTerminal = Terminal8136.values().joinToString(",") { t ->
            "${t.name}=${counts8136.entries.filter { it.key.contains("|${t.name}:") }.sumOf { it.value.get() }}"
        }
        val top = counts8136.entries.sortedByDescending { it.value.get() }.take(12)
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }
        return "admission8136[$byTerminal reasons{$top}] coalescedReevals8144=${coalesced8144.get()} arrivalReevals8252=${arrivalReevals8252.get()} ${deferTerminalLine8418()} ${deferCauseLine8700()}"
    }

    // ── live adapter: the record from the bot's own stores (read-only peeks; no fetch, no synthetic data) ──

    private fun stdErr8136(variance: Double, n: Double): Double =
        if (n > 1.0 && variance.isFinite() && variance >= 0.0) kotlin.math.sqrt(variance / n) else Double.POSITIVE_INFINITY

    /** The admission record for [ts] in [laneRaw] now, decided. Null only on an internal failure. */
    fun assess8136(
        ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis(), candidateVersion: Long = 0L,
        /**
         * V5.0.8198 P0-B — the generic prior from the SAME decision snapshot (FinalDecisionGate: the lane's authoritative
         * entry score and its fluid CanonicalEntryFloor7266). Without them the record's generic branch was unreachable at
         * runtime (both defaulted NaN and candidateFor8136 never set them): a VALID carry ended as NO_SUPPORTED_EDGE with no
         * score in its trace. Callers without a snapshot score pass nothing; nothing is guessed.
         */
        genericScore: Double = Double.NaN, genericFloor: Double = Double.NaN,
        proposedSizeSol: Double = Double.NaN,   // V5.0.8209 — cost at THIS candidate's size; a material size change re-quotes (identity miss)
    ): Decision8136? = try {
        val mode = FdgGate8365.paperContext8393()?.let { if (it) "PAPER" else "LIVE" }
            ?: (try { if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE" } catch (_: Throwable) { "UNKNOWN" })
        // V5.0.8212: an unrouteable 0.0100 SOL proposal has a hypothetical 11.6% round trip
        // in 8211. The live sizing gate can promote to the wallet's routable minimum when
        // permitted; price admission on that achievable ticket (only if this wallet can
        // actually carry it). The later risk/size/route gates still authorize or refuse
        // the exact sealed SOL amount and re-quote if it materially changes.
        val admissionSize8212 = if (mode == "LIVE") try {
            com.lifecyclebot.engine.truth.NetEdge8030.admissionQuoteSize8212(
                proposedSizeSol,
                (com.lifecyclebot.engine.BotService.status.walletSol -
                    com.lifecyclebot.engine.truth.LiveSpendReserveAuthority7255.RESERVE_SOL).coerceAtLeast(0.0),
                com.lifecyclebot.engine.WalletManager.lastKnownSolPrice,
            )
        } catch (_: Throwable) { proposedSizeSol }
        // V5.0.8399 — paper prices admission at the paper ledger's wallet-percent ticket, not the live wallet's dust minimum.
        else if (mode == "PAPER" && !(proposedSizeSol.isFinite() && proposedSizeSol > 0.0)) try { com.lifecyclebot.engine.truth.NetEdge8030.paperTicket8399() } catch (_: Throwable) { proposedSizeSol }
        else proposedSizeSol
        val version = candidateVersion.takeIf { it > 0L }
            ?: try { com.lifecyclebot.engine.LaneExecutionCoordinator.candidateVersionFor(ts.mint) } catch (_: Throwable) { 0L }
        // V5.0.8223 — one canonical prior per candidate generation. FDG is the only caller that knows the authoritative
        // entry score and fluid floor; LiveEdgeGate7877 / TradePlan7739 / the FDG floor fallback re-assess the same
        // candidate WITHOUT them, so the memo identity missed (NaN score) and the record re-decided on a prior-less
        // candidate: GENERIC_PRIOR_CLEARS at the FDG became NO_SUPPORTED_EDGE in the plan (8220 forensic). The snapshot
        // FDG supplied is carried by (mode, mint, lane, version) and read by every later caller of the same generation.
        // Nothing is guessed: a candidate FDG never scored still carries no prior.
        val laneKey8223 = CanonicalLaneIdentity6506.canonical(laneRaw).uppercase().ifBlank { laneRaw.trim().uppercase() }
        val priorKey8223 = "$mode|${ts.mint}|$laneKey8223|$version"
        val prior8223 = if (genericScore.isFinite() && genericFloor.isFinite()) {
            SnapshotPrior8223(genericScore, genericFloor, admissionSize8212, nowMs).also {
                if (snapshotPriors8223.size > 4_000) snapshotPriors8223.entries.removeIf { e -> nowMs - e.value.atMs > PRIOR_CARRY_MS_8223 }
                snapshotPriors8223[priorKey8223] = it
            }
        } else snapshotPriors8223[priorKey8223]?.takeIf { nowMs - it.atMs in 0L..PRIOR_CARRY_MS_8223 }?.also {
            try { PipelineHealthCollector.labelInc("ADMISSION_PRIOR_CARRIED_8223_$laneKey8223") } catch (_: Throwable) {}
        }
        val sizeForQuote8223 = if (admissionSize8212.isFinite() && admissionSize8212 > 0.0) admissionSize8212 else prior8223?.sizeSol ?: admissionSize8212
        val built = candidateFor8136(ts, laneRaw, nowMs, sizeForQuote8223)
        val rawCandidate = if (prior8223 != null) built.copy(genericScore = prior8223.score, genericFloor = prior8223.floor) else built
        val candidate = rawCandidate.copy(candidateVersion = version, live = mode == "LIVE")
        try { PriceAge8549.noteDecision8549(candidate.lane, candidate.priceAgeMs) } catch (_: Throwable) {}   // V5.0.8549
        // Policy-scoped results never overwrite the ordinary LIVE admission cache or its defer budget.
        val policyMode8393 = "$mode|${FdgGate8365.policyKey8393()}"
        val key = "$policyMode8393|${ts.mint}|${candidate.lane}|$version"
        val identity = cacheIdentity8144(candidate)
        val cached = memos8153[key]?.takeIf { nowMs - it.atMs in 0L..MEMO_MS_8153 && it.candidate == identity }?.decision
        // V5.0.8577 — LIVE: the lane-qualified candidate's learned-door refusal is advisory (LaneAuthority8577.apply8577).
        // Share the strategy rules while preserving the actual execution mode for freshness, costs and paper exploration.
        cached ?: (if (FdgGate8365.policyKey8393() == "OUTSIDE") coalescedDefer8144(ts.mint, candidate.lane, identity, version, mode, nowMs) else null) ?: LaneAuthority8577.apply8577(candidate,
            decide8136(candidate, ::verifiedRecordDoor8650),
            if (candidate.live) LaneAuthority8577.memeEntryRefusal8626(ts, candidate.lane, candidate.stage, nowMs) else null,  // V5.0.8626
            launchPhase8609 = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.features(ts, nowMs).launchPhase } catch (_: Throwable) { null },
            pocket8610 = if (candidate.live) PocketLedger8610.verdict8610(ts, candidate.lane, nowMs) else null)
            .let { awaitBuyDoorProof8703(ts, candidate.live, it, nowMs, candidate.lane) }.also {
            // A held shadow rehearsal must not publish an ADMIT into live sizing, strategy identity or the runner trace.
            if (!FdgGate8365.shadowOnly8393()) {
                noteDecision8144(ts.mint, candidate.lane, candidate, it, version, mode, nowMs)
                // V5.0.8535 §P2-01 — the admission's terminal joins the candidate's decision episode with its trace hash.
                try { com.lifecyclebot.engine.DecisionEpisode8535.noteAdmission8535(candidate.lane, ts.mint, version, it.terminal.name, it.reason, it.chosenSetup, it.trace, nowMs) } catch (_: Throwable) {}
            }
            // V5.0.8544 — a LIVE meme candidate is also decided the PAPER way for the separate paper book beside live
            // V5.0.8618 — the tuning report files the paper close under what LIVE decided on this same candidate.
            if (mode == "LIVE") try { TuningReport8618.noteLiveVerdict8618(ts.mint, candidate.lane, it.admitted, it.reason, nowMs) } catch (_: Throwable) {}
            if (mode == "LIVE" && !FdgGate8365.shadowOnly8393()) try { MemePaperTrader8544.consider8544(ts, candidate, nowMs) } catch (_: Throwable) {}
            if (memos8153.size > 4_000) memos8153.entries.removeIf { e -> nowMs - e.value.atMs > MEMO_MS_8153 }
            memos8153[key] = Memo8153(nowMs, identity, it)
        }
    } catch (_: Throwable) { null }

    /**
     * Pure (V5.0.8418): the number NetEdge8030.expectedGrossFor8153 gives for [m] — NaN while it waits (a sparse or falling
     * move that would not clear [margin] x [costPct] at the candidate's own quoted size), else its expected gross.
     */
    internal fun grossOf8418(m: StrategyMove8153.Move8153?, costPct: Double, margin: Double): Double =
        if (m == null || StrategyMove8153.needsDefer8153(m, costPct, margin)) Double.NaN else m.expectedGrossPct

    /** V5.0.8325 — the lane's own graded label pond (executable-net, Cortex7885.lanePond8325) as an admission record; null without one. */
    private fun laneLabel8325(lane: String): Evidence8136? = try {
        com.lifecyclebot.engine.cortex.Cortex7885.lanePond8325(lane)?.takeIf { it.n > 0 && it.mean.isFinite() }?.let { p ->
            Evidence8136("LANE", lane, p.n, p.mean, if (p.se.isFinite()) p.se else Double.POSITIVE_INFINITY)
        }
    } catch (_: Throwable) { null }

    /** Builds the candidate record from the playbook, forward labels, net-edge cost model and safety report. */
    internal fun candidateFor8136(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis(), proposedSizeSol: Double = Double.NaN): Candidate8136 {
        val lane = CanonicalLaneIdentity6506.canonical(laneRaw).uppercase().ifBlank { laneRaw.trim().uppercase() }
        // V5.0.8290 — LIVE: the lane's realised shortfall (LiveAdmissionProof8290) travels on the record as liveShortfall8290.
        // V5.0.8316 — the records below are built RAW; Candidate8136's shifted twins (setupsProof8290 ...) carry the shortfall
        //    to the support tests only. 8290 shifted them here, so the loss tests read manufactured losses (SETUP_RECORD_LOSING 407).
        val live8290 = try { !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
        val extra8290 = if (live8290) (try { LiveAdmissionProof8290.laneExtra8290(lane, nowMs) } catch (_: Throwable) { 0.0 }) else 0.0
        val nativeOpinion8213 = try {
            com.lifecyclebot.engine.SpecialistBrainBridge7542.cachedSnapshot7650(ts.mint)
                ?.takeIf { it.mint == ts.mint }?.opinions?.get(lane)
                ?.takeIf { it.eligible && it.authoritative &&
                    com.lifecyclebot.engine.LaneExecutionCoordinator.laneCanOwnExecution6910(lane) }
        } catch (_: Throwable) { null }
        val pb = com.lifecyclebot.engine.cortex.LanePlaybook7907
        // V5.0.8153 — the strategy is ranked fresh over every fired setup (StrategyRank8153), not read back from the
        //    identity this record itself holds; its choice is then that identity for every later reader.
        // V5.0.8535 §P1-01 / §P1-03 / §P1-13 — a fire's AUTHORITY (SetupAuthority8535): a token-quality condition, a fire on
        //    partial evidence or a near-high read with no fresh breakout witness is graded, never the executable strategy or a
        //    fired option; the candidate then takes the generic path like a coin with no fired setup.
        val features8535 = try { pb.features(ts, nowMs) } catch (_: Throwable) { null }
        val pick8153 = try { pb.strategyPick8153(ts, lane, nowMs, features8535) } catch (_: Throwable) { null }
        fun triggered8535(id: String): Boolean =
            com.lifecyclebot.engine.cortex.SetupAuthority8535.executable8535(id, features8535)
        val options8153 = try { pb.strategyOptions8153(ts, lane, nowMs).filter { triggered8535(it.id) } } catch (_: Throwable) { emptyList() }
        val selectedRaw8535 = pick8153?.id
        val selected = selectedRaw8535?.takeIf { triggered8535(it) }
        if (selectedRaw8535 != null && selected == null) try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SETUP_AUTHORITY_NOT_EXECUTABLE_8535_" +
                com.lifecyclebot.engine.cortex.SetupAuthority8535.authorityOf8535(selectedRaw8535, features8535).name + "_$lane")
        } catch (_: Throwable) {}
        // V5.0.8217 — the lane's native qualification must not depend on one per-mint snapshot surviving in the cache.
        // A live QUALIFIED/READY entry in that lane's own candidate book (its hunter+brain qualified THIS mint) is the
        // same native verdict; its setup is the lane's own fired pick. Nothing is qualified that the lane did not qualify.
        val nativeOpinionId8650 = nativeOpinion8213
            ?.let { com.lifecyclebot.engine.SpecialistBrainBridge7542.nativeSetupIdentity8535(it) }
            ?.takeIf { it.isNotBlank() && !it.equals("NONE", true) && triggered8535(it) }
        // An authoritative opinion whose identity was actually rejected by SetupAuthority8535
        // cannot mask a separate fresh READY verdict from this same lane's book.
        val nativeBook8217 = if (nativeOpinionId8650 != null) null else try {
            // V5.0.8270 — only the lane's authoritative READY verdict is native readiness; a QUALIFIED observation is
            // wider watching, not executable readiness. Stale readiness (older than NATIVE_READY_FRESH_MS_8270) does
            // not outlive a fresher refusal: the book rewrites the entry on refusal, so a fresh READY is the proof.
            com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.entry(lane, ts.mint)?.takeIf {
                it.state == com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.State.READY &&
                    nowMs - it.updatedAtMs in 0L..NATIVE_READY_FRESH_MS_8270 &&
                    com.lifecyclebot.engine.LaneExecutionCoordinator.laneCanOwnExecution6910(lane)
            }
        } catch (_: Throwable) { null }
        // V5.0.8221 §P0a — preserve native qualification when the playbook has no selected setup. Pre-8221 this field
        // required BOTH nativeBook8217 != null AND pick8153.id != null. A QUALIFIED/READY candidate-book entry with
        // no playbook pick (optional data gap) therefore lost its native qualification and could be deferred before
        // intent. Operator 8220 forensic: MOONSHOT 4832 native approvals, 0 live buys — exactly this failure mode.
        // Fix: when the book is QUALIFIED/READY, treat the lane's own hunter+brain verdict as native qualification
        // using a NATIVE_BOOK sentinel when no playbook id is available. The lane still owns the strategy identity;
        // nothing bypasses safety, rug, FDG or sizing downstream.
        // V5.0.8270 — no invented NATIVE_BOOK sentinel: the setup identity is the playbook pick, else the lane's own
        // READY verdict reason (its actual native setup text); a READY entry with neither names nothing and is not
        // native qualification.
        val nativeSetupBook8217 = when {
            nativeBook8217 == null -> null
            pick8153?.id != null && pick8153.id.isNotBlank() && !pick8153.id.equals("NONE", true) -> pick8153.id
            nativeBook8217.reason.isNotBlank() && !nativeBook8217.reason.equals("NONE", true) -> "NATIVE_READY_8270:${nativeBook8217.reason.take(40)}"
            else -> null
        }
        // V5.0.8260 — the forming runner on an unlearned book is the runner lanes' own qualification.
        val runnerForming8260 = if (lane.uppercase() in RUNNER_LANES_8260) try {
            com.lifecyclebot.engine.RunnerGrab7967.formingNative8260(ts, nowMs)
        } catch (_: Throwable) { null } else null
        val nativeIdentity8650 = (nativeOpinionId8650 ?: nativeSetupBook8217 ?: runnerForming8260)
            ?.takeIf { it.isNotBlank() && !it.equals("NONE", true) && triggered8535(it) }
        if (nativeSetupBook8217 != null) try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ADMISSION_NATIVE_FROM_BOOK_8217_$lane")
            if (nativeSetupBook8217.startsWith("NATIVE_READY_8270:")) com.lifecyclebot.engine.PipelineHealthCollector
                .labelInc("ADMISSION_NATIVE_READY_VERDICT_SETUP_8270_$lane")
        } catch (_: Throwable) {}
        val setups = try {
            pb.firedSetupRecords8136(ts, lane, nowMs).filter { triggered8535(it.first) }.map { (id, r) ->
                Evidence8136(KIND_SETUP, id, r[0].toInt(), r[1], r[2], r[3], suspect8290 = r.getOrNull(4) == 1.0)   // raw (8316)
            }
        } catch (_: Throwable) { emptyList() }
        val band8153 = try { pb.timingBandOf8094(ts) } catch (_: Throwable) { pb.TIMING_UNKNOWN_8094 }
        val setupTimings = options8153.filter { it.timingN > 0 }.associate { o ->
            o.id to Evidence8136(KIND_TIMING, "${o.id}@$band8153", o.timingN, o.timingMeanPct, o.timingSePct)   // raw (8316)
        }
        val cellStat = try { ForwardReturnLabeler7731.cellStatFor(ts, lane, nowMs) } catch (_: Throwable) { null }
        // A cell whose observations mostly lost their mark is not a measurement of its coins (LiveEdgeGate7877.mostlyUnpriced8022).
        val cell = cellStat?.takeIf { it.resolvedShare >= LiveEdgeGate7877.MIN_RESOLVED_SHARE_7944 }
            ?.let { Evidence8136(KIND_CELL, it.key.take(40), it.n60, it.meanNet60Pct, it.stderr60Pct, it.runnerRate60, suspect8290 = it.outlierSuspect8185) }   // raw (8316)
        // V5.0.8153 — the timing record of the strategy selected here (not of whatever selectedSetup8094 held).
        val timing = selected?.let(setupTimings::get)
        val memeLane = com.lifecyclebot.engine.TokenMetricStageRouter.memeEntry8506(lane, ts.mint)
        val entry = if (memeLane) com.lifecyclebot.engine.TokenMetricStageRouter.entryRead8506(ts, lane, nowMs) else null
        val fit = entry?.first ?: try { com.lifecyclebot.engine.TokenMetricStageRouter.laneFit(ts, lane) } catch (_: Throwable) { null }
        val stageRecord = try {
            if (fit == null) null else ForwardReturnLabeler7731.stageStatFor7928(fit.lane, fit.stage.name)
                ?.let { st -> Evidence8136(KIND_STAGE, fit.stage.name, st.n60, st.meanNet60Pct, st.stderr60Pct, st.runnerRate60, lateN = st.n240, lateMeanPct = if (st.n240 > 0) st.meanNet240Pct else Double.NaN) }
        } catch (_: Throwable) { null }
        // V5.0.8169 — the same cell and stage, booked since the grading epoch: the only records a loss pattern refuses on.
        val cellLoss8169 = try {
            // V5.0.8290 — LIVE reads the post-epoch cell executable (the 8187 lane offset, as cellStatFor does): it is the live
            //    POSITIVE cell record too (liveCell8210), not only the loss record. V5.0.8316 — the shortfall is read on its
            //    shifted twin (cellLossProof8290) at the support test only; the loss test reads this raw record.
            ForwardReturnLabeler7731.freshCellStatFor8169(ts, lane, nowMs)
                ?.let { if (live8290) ForwardReturnLabeler7731.executable8187(it, lane) ?: it else it }
                ?.let { Evidence8136(KIND_CELL, it.key.take(40), it.n60, it.meanNet60Pct, it.stderr60Pct, it.runnerRate60, suspect8290 = it.outlierSuspect8185) }
        } catch (_: Throwable) { null }
        val stageLoss8169 = try {
            if (fit == null) null else ForwardReturnLabeler7731.freshStageStatFor8169(fit.lane, fit.stage.name)
                ?.let { st -> Evidence8136(KIND_STAGE, fit.stage.name, st.n60, st.meanNet60Pct, st.stderr60Pct, st.runnerRate60, lateN = st.n240, lateMeanPct = if (st.n240 > 0) st.meanNet240Pct else Double.NaN) }
        } catch (_: Throwable) { null }
        // V5.0.8153 — unread depth is UNKNOWN, never LOW: the launch probe is costed without impact nobody measured.
        val launch = try {
            FreshLaunchSelector7737.launchRead7742(ts, FieldManual7715.allInCostPct8350(StrategyMove8153.probeSizeUsd8153(ts.lastLiquidityUsd), ts.lastLiquidityUsd, ts.mint), nowMs)
        } catch (_: Throwable) { null }
        val costRec8209 = try { NetEdge8030.entryCost8209(ts, lane, proposedSizeSol) } catch (_: Throwable) { null }
        val cost8153 = costRec8209?.costPct ?: Double.NaN
        // V5.0.8418 — the selected setup's move read once: its number, and why it has none (moveWait8418).
        val move8418 = try { NetEdge8030.expectedMove8418(ts, lane, selected, cost8153) } catch (_: Throwable) { null }
        val margin8418 = NetEdge8030.margin8391(lane, costRec8209?.sizeSol ?: Double.NaN, cost8153)
        val curveActive8153 = try { CurveReserves7951.realSolIfActive8046(ts.mint, nowMs) != null } catch (_: Throwable) { false }
        // V5.0.8170: a live DexScreener pair or sweep already priced the actual
        // liquidity, but the token-local scalar hadn't received the ordinary poll.
        // Use the same dated source the setup resolver reads to avoid needless
        // DATA_INCOMPLETE; the on-chain pool/route hard gates remain later.
        val observedLiq8170 = try {
            SetupFeatureResolver8169.observedLiquidity8170(ts.mint,
                try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts)?.ageMin ?: Double.NaN } catch (_: Throwable) { Double.NaN },
                nowMs)
        } catch (_: Throwable) { null }
        val liquidityMissing8153 = !curveActive8153 &&
            StrategyMove8153.liquidityRead8153(ts.lastLiquidityUsd) == "UNKNOWN" &&
            observedLiq8170 == null
        val safety = if (ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK)
            "HARD_BLOCK:${ts.safety.hardBlockReasons.firstOrNull().orEmpty().take(40)}"
            // V5.0.8684 — LIVE: the buy door's confirmed top-holder block refuses here, before FDG / ticket / lease are spent.
            else if (try { !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false })
                (try { com.lifecyclebot.engine.PreTradeHardGate.holderBlock8684(ts, nowMs) } catch (_: Throwable) { null })
            else null
        val canonicalPrice = CanonicalPriceMarkRegistry6522.getFresh6734(
            ts.mint, CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE, nowMs)
        val localAt = ts.lastPriceUpdate.takeIf { ts.lastPrice.isFinite() && ts.lastPrice > 0.0 && it in 1L..nowMs } ?: 0L
        val observedAt = maxOf(localAt, canonicalPrice?.timestampMs?.takeIf { it <= nowMs } ?: 0L)
        val priceAge = if (observedAt > 0L) nowMs - observedAt else Long.MAX_VALUE
        val lifecycle8283 = try { pb.lifecycleSplit8283(ts, lane, nowMs) } catch (_: Throwable) { null }
        // V5.0.8324 — a runner lane's loss tests read its tail pond (the Cortex grade book: hold-window objective, raw while plausible).
        val pond8324 = if (com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane))
            (try { com.lifecyclebot.engine.cortex.Cortex7885.tailPond8324(lane) } catch (_: Throwable) { null }) else null
        return Candidate8136(
            mint = ts.mint,
            lane = lane,
            selectedSetup = selected,
            firedSetups8528 = try { pb.matchedSetups8083(ts, lane, nowMs).filter(::triggered8535).toSet() } catch (_: Throwable) { emptySet() },
            edge8537 = EdgeModel8537.verdict(ts, lane, cost8153, nowMs),  // V5.0.8537
            liveEdge8114 = if (live8290) LiveEdgeGate7877.edge8114Live8538(ts, lane, nowMs) else null,  // V5.0.8538
            marketCell8723 = PayingCell8723.evidence8723(ts, lane, nowMs),
            regimeCold8546 = live8290 && MemePaperTrader8544.memeLane8544(lane) &&
                (try { MemeRegime8546.regime8546(nowMs) == MemeRegime8546.Regime8546.COLD } catch (_: Throwable) { false }),
            regime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "" },
            stage = fit?.stage?.name.orEmpty(),
            entryWait8506 = entry?.second,
            highVelocityTape8520 = try { com.lifecyclebot.engine.RunTape8293.freshHighVelocity8520(ts.mint, nowMs) } catch (_: Throwable) { false },
            tapeLowerGross8520 = try { com.lifecyclebot.engine.RunTape8293.conservativeForwardGross8520() } catch (_: Throwable) { Double.NaN },
            priceAgeMs = priceAge,
            // V5.0.8283 — the lifecycle split (dataIncomplete8136's binding inputs; the too-young history recorded apart).
            missingInputs = resolvedMissingInputs(
                lifecycle8283?.binding.orEmpty(),
                liquidityObserved = curveActive8153 || observedLiq8170 != null) +
                (if (liquidityMissing8153) listOf("liquidity:UNKNOWN") else emptyList()),
            historyUnavailable8283 = lifecycle8283?.unavailable.orEmpty(),
            // V5.0.8153 — the selected strategy's expected executable move (NetEdge8030.executableMove8153), as the gate judges it.
            expectedGrossPct = grossOf8418(move8418, cost8153, margin8418),
            moveWait8418 = NetEdge8030.moveWait8418(move8418, cost8153, margin8418),
            fallingHourProof8418 = if (NetEdge8030.moveWait8418(move8418, cost8153, margin8418) == NetEdge8030.MOVE_WAIT_FALLING_8418)
                (try { FallingHourCohort8418.evidence8418(lane) } catch (_: Throwable) { null }) else null,
            costPct = cost8153,
            costMargin8391 = margin8418,
            costSizeSol = costRec8209?.sizeSol ?: Double.NaN,
            costBreakdown = costRec8209?.breakdown.orEmpty(),
            downsidePct = try { com.lifecyclebot.engine.cortex.StopAuthority7887.laneStopMag8029(lane) } catch (_: Throwable) { Double.NaN },
            expectedGrossBySetup = (options8153.map { it.id } + listOfNotNull(selected)).distinct().associateWith { setup ->
                try { NetEdge8030.expectedGrossFor8153(ts, lane, setup, cost8153) } catch (_: Throwable) { Double.NaN }
            },
            setups = setups,
            cell = cell,
            timing = timing,
            stageRecord = stageRecord,
            losingTactic = try { com.lifecyclebot.engine.learning.TacticSwitcher.losingTacticFor8094(ts, lane, selected ?: nativeIdentity8650) } catch (_: Throwable) { null },
            hotLaunch = launch != null && launch.verdict != FreshLaunchSelector7737.LaunchVerdict.NOT_FRESH,
            safetyRefusal = safety,
            structuralRisk = if (fit?.stage == com.lifecyclebot.engine.TokenMetricStageRouter.Stage.RUG_PRONE)
                "STAGE_RUG_PRONE" + ((try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts).reason } catch (_: Throwable) { "" }).substringAfter("why8500=", "").substringBefore(' ').takeIf { it.isNotBlank() }?.let { "_$it" } ?: "")   // V5.0.8500 — names its trigger
                else launch?.takeIf { it.verdict == FreshLaunchSelector7737.LaunchVerdict.REFUSED }?.why,
            setupTimings = setupTimings,
            lossEpochAware8169 = true,
            live = try { !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false },
            liveShortfall8290 = extra8290,
            cellLoss8169 = cellLoss8169,
            stageLoss8169 = stageLoss8169,
            cortexStrong8200 = try { com.lifecyclebot.engine.cortex.Cortex7885.strongCohortEvidence8200(ts, lane) } catch (_: Throwable) { null },  // V5.0.8290 — shifted in decide8136
            runnerGrab8245 = try {
                com.lifecyclebot.engine.RunnerGrab7967.grabEvidence8245(ts, nowMs)?.let { Evidence8136(KIND_RUNNER_GRAB_8245, it.key, it.n, it.meanNetPct, it.sePct) }  // V5.0.8316 — shifted in decide8136
            } catch (_: Throwable) { null },
            nativeQualified8213 = nativeIdentity8650 != null,
            watchlistRecheck8507 = ts.source.trim().equals("WATCHLIST", ignoreCase = true),
            // V5.0.8535 §P0-06 — the native identity is the predicate that fired or the rule that decided, never the style label.
            // V5.0.8536 — the native identity passes the same SetupAuthority8535 test as the selected setup: a near-high read
            // with no breakout witness, a quality condition or a partial fire no longer re-enters as the native strategy
            // (8535 live: BREAKOUT_RUNNER -15.6 %, TURNOVER_SPIKE -23.4 % came in this way).
            nativeSetup8213 = nativeIdentity8650,
            sheet8323 = try { com.lifecyclebot.engine.cortex.LaneCheatSheet8323.admissionVerdicts8323(lane, setups.map { it.id }, selected, ts.mint, nowMs) } catch (_: Throwable) { emptyMap() },
            tailLaneRate8324 = pond8324?.pBook ?: Double.NaN,
            tailPremium8324 = pond8324?.premium ?: Double.NaN,
            laneRealised8325 = if (live8290) (try { com.lifecyclebot.engine.cortex.Cortex7885.laneRealised8295(lane) } catch (_: Throwable) { null }) else null,
            laneLabel8325 = if (live8290) laneLabel8325(lane) else null,
            // V5.0.8424 — the same ask LiveEdgeGate7877 makes (by mint, so the probe claim is this coin's): a shut lane's probe
            laneProbe8424 = live8290 && (try { EntryEconomics8329.laneRealisedRefusal8329(lane, nowMs, ts.mint) == null } catch (_: Throwable) { false }),
            recordProbe8429 = live8290 && (try { LaneRealisedProbe8424.slotOpen8429(lane, ts.mint, nowMs) } catch (_: Throwable) { false }),
        )
    }
}
