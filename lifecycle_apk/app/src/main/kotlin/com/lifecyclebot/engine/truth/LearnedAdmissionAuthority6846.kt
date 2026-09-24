package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6846 §LEARNED_ADMISSION — operator directive Feb 2026 (5.0.6845
 * DIRECT REPAIR BLOCK).
 *
 * PROBLEM the operator surfaced:
 *   ExecutableEntryAuthority6450 gates=3678 allows=3678 denies=0 while
 *   BrainConsensusGate was reporting 75.2% SOFT_BLOCK and lane WR=3.8%.
 *   The admission boundary was rubber-stamping every candidate; the
 *   learned intelligence had nowhere to bite.
 *
 * SCOPE (from the directive):
 *   §1 admission authority becomes the coordinator of learned evidence.
 *   §2 DUMP regime turns mature-negative cohorts into denies (not just
 *      size damping).
 *   §4 lane-owner weighting conditions on lane+scoreBand+regime+source
 *      rather than lane lifetime identity alone.
 *   §5 source-family adaptation feeds canonical terminal outcomes into
 *      admission weighting; no hardcodes, must recover.
 *   §6 lane capital target: projected exposure past adaptive target
 *      suppresses low-edge entries.
 *   §7 early-reject cohorts whose learned/regime/capital shaping would
 *      mathematically produce sub-executable size.
 *
 * DOES NOT touch: canonical accounting, mark sanity, same-mint guards,
 *   inventory caps, reconciler, FDG sealing, terminal idempotency,
 *   capital conservation, stale-ticket protection (§8 preserved).
 *
 * CONTRACT — evaluate() returns one of:
 *   ALLOW       — proceed at requested notional
 *   PROBE_ONLY  — proceed at the caller's probe/minimum size (bounded
 *                 exploration)
 *   DENY        — do not admit; must not fall back to a duplicate ALLOW
 *                 code path elsewhere.
 *
 * TELEMETRY (§1 acceptance):
 *   ENTRY_AUTHORITY_ALLOW
 *   ENTRY_AUTHORITY_PROBE
 *   ENTRY_AUTHORITY_DENY
 *   ENTRY_AUTHORITY_DENY_REASON_<COHORT|REGIME|SOURCE|POLICY|CAPITAL>
 *
 * BOOTSTRAP GRACE — when the (lane, sourceFamily) cohort has fewer than
 * `MATURITY_MIN_N` terminals we treat the learned signal as unresolved
 * and pass through as ALLOW. This preserves exploration (and every
 * existing test that runs against an empty adaptive state).
 */
object LearnedAdmissionAuthority6846 {

    enum class Verdict { ALLOW, PROBE_ONLY, DENY }

    data class Decision(
        val verdict: Verdict,
        val recommendedSizeSol: Double,
        val reason6846: String,
        val denyCategory: String,
    )

    /** Terminal count under which the cohort is still "in exploration" —
     *  we do not deny in that range. Directive §2 uses n>=8 and n>=10
     *  respectively; MATURITY_MIN_N is the strict lower bound. */
    private const val MATURITY_MIN_N = 8

    // ── §2 DUMP thresholds (operator numbers, verbatim) ─────────────────
    private const val DUMP_MATURE_MIN_N = 8
    private const val DUMP_MATURE_PWIN_MAX = 0.20
    private const val DUMP_MATURE_LOSS_RATE = 0.75
    private const val DUMP_STRONG_MIN_N = 10
    private const val DUMP_STRONG_PWIN_MAX = 0.15

    // ── §2b V5.0.6909 regime-independent cohort expectancy ──────────────
    //
    // Anchored to the operator's own 5.0.6908 cohort numbers rather than
    // invented, and every one requires NEGATIVE EXPECTANCY so a low win rate
    // with a fat tail is never punished:
    //
    //   CORE                n=14  WR 0%     EV -6.94%  -> proven dead
    //   CORE|S26-40         n=5   W/L 0/5   mu -5.9%   -> below maturity, untouched
    //   PROJECT_SNIPER|S10  pWin 0%         E  -14.3%  -> proven dead
    //   PROJECT_SNIPER|S20                  E  +31.5%  -> positive, untouched
    //   PROJECT_SNIPER agg  n=23  WR 13%    EV +28.97% -> positive, untouched
    //
    /** Sample at which a zero-win cohort is called dead rather than unlucky. */
    private const val COHORT_DEAD_MIN_N_6909 = 12
    /** Win rate at or below which a cohort has no demonstrated upside at all. */
    private const val COHORT_DEAD_PWIN_MAX_6909 = 0.05
    /** Per-trade expectancy (FRACTION, not percent) marking a real bleed. */
    private const val COHORT_DEAD_EV_MAX_6909 = -0.05
    /** Win rate below which a negative-EV mature cohort is metered. Set above
     *  PROJECT_SNIPER's 13% aggregate on purpose: that lane is only reached
     *  here per-cohort and only when its EV is also negative, so the runner
     *  cohorts stay fully admitted. */
    private const val COHORT_NEGATIVE_PWIN_MAX_6909 = 0.20

    // ── §2c V5.0.6915 oracle expectancy refusal ─────────────────────────
    /**
     * Effective confidence (as a sample count) the oracle must carry before
     * its negative expectancy may throttle admission. 8 matches MATURITY_MIN_N
     * on purpose: the oracle earns the same standing as a genuinely mature
     * cohort, no more. Its confidence maps 1.0 -> 16, so this is ~0.50
     * confidence — above its own internal refuse floor of 0.45.
     */
    private const val ORACLE_MIN_CONFIDENT_N_6915 = 8

    /**
     * Expectancy (FRACTION per trade) at or below which a confident oracle
     * estimate throttles. -0.08 = losing 8% per trade on blended evidence.
     * PROJECT_SNIPER's observed -37.92% clears this by a factor of nearly five;
     * CRYPTO_LEV's +7.06% is nowhere near it.
     */
    private const val ORACLE_REFUSE_EV_6915 = -0.08

    // V5.0.7287 — the cohort probe budget (one quarter-size probe per cohort
    // per 5/15 minutes) and PROBE_SIZE_FRACTION_7139 are retired with the
    // probe itself: a candidate trades at its size or does not trade.

    /** Amount by which projected exposure must exceed adaptive target
     *  before §6 damping kicks in (fractional, not absolute). */
    private const val CAPITAL_TARGET_TOLERANCE = 1.15

    /** Terminal expectancy threshold used by §5 source-family adaptation
     *  — a mature source cohort with pWin below this AND EV below zero
     *  gets suppressed to PROBE_ONLY (in DUMP), or ALLOWed only for
     *  high-edge composite candidates (out of DUMP). */
    private const val SOURCE_FAMILY_PWIN_SUSPECT = 0.25

    /** Cohort input snapshot the caller assembles from
     *  UnifiedPolicyHead + BrainConsensusGate + LosingPatternMemory +
     *  ForwardOutcomeModel + RegimeDetector + LaneExpectancyDamper +
     *  source-family expectancy. The authority does not query these
     *  itself so the coupling stays testable and the caller controls
     *  when the signals are read. */
    data class Inputs(
        val lane: String,
        val mint: String,
        val requestedSizeSol: Double,
        val scoreBand: Int,             // e.g. 41..60 -> "lab band"
        val regime: String,             // "DUMP" / "PUMP" / "CHOP" / …
        val livePWin: Double,           // 0..1, from LiveProbabilityEngine / ForwardOutcomeModel
        val expectedPnl: Double,        // -1..+1
        val cohortSample: Int,          // n terminals for the (lane, scoreBand, regime, source) cohort
        // V5.0.7154 — the TRUE terminal count for this cohort, before
        // LearnedAdmissionInputs6909 maxes cohortSample with the oracle's
        // effective weight. See the ORACLE branch in gate() for why the two
        // must not be the same number.
        val oracleRawCohortN7154: Int = 0,
        /**
         * V5.0.7207 — the LANE's true terminal count, the coarser sibling of
         * [oracleRawCohortN7154]. The cell count answers "has this exact
         * score band closed 8 trades"; this answers "has this lane closed 8
         * trades". Both are real counts; only the resolution differs. See the
         * ORACLE branch in gate() for why asking only the first made the
         * refusal unreachable.
         */
        val oracleRawLaneN7207: Int = 0,
        // V5.0.7259 — carry the oracle's actual categorical verdict.  Passing
        // only pWin/EV/confidence let PROBE be reconstructed as executable
        // exploration downstream, even when the oracle had admitted nothing.
        val oracleVerdict6915: PredictiveEntryOracle6915.Verdict? = null,
        /** V5.0.7287 — the oracle's REFUSE rests on a recorded safety fact. */
        val oracleHardSafety7287: Boolean = false,
        val laneWrPct: Double,          // 0..100
        val laneLossRatePct: Double,    // 0..100
        val sourceFamily: String,       // "PUMP_FUN_NEW" / "BIRDEYE_TRENDING" / …
        val sourcePWin: Double,         // 0..1 rolling from canonical terminals
        val sourceExpectedPnl: Double,  // -1..+1
        val sourceSample: Int,          // n canonical terminals for this source
        val policyHardBlock: Boolean,   // UnifiedPolicyHead HARD_BLOCK
        val brainSoftBlock: Boolean,    // BrainConsensusGate SOFT_BLOCK
        val losingPatternMatch: Boolean, // LosingPatternMemory cohort in negative bucket
        val laneCapitalUsedSol: Double,
        val laneCapitalTargetSol: Double,
        val laneCapitalReservedSol: Double,
        val minExecutableSol: Double,
        val probeSizeSol: Double,       // caller-supplied probe size
    )

    private val allows = AtomicLong(0L)
    private val probes = AtomicLong(0L)
    private val denies = AtomicLong(0L)

    /**
     * Evaluate the (lane, cohort, regime, source, capital) inputs and
     * return the admission decision. Callers must respect DENY without
     * falling through to any other ALLOW path (see §8's directive:
     * "no pid/source/lane alias bypasses").
     */
    fun evaluate(inputs: Inputs): Decision {
        val laneKey = inputs.lane.trim().uppercase().ifBlank { "UNKNOWN" }
        val regimeKey = inputs.regime.trim().uppercase().ifBlank { "UNKNOWN" }
        val srcKey = inputs.sourceFamily.trim().uppercase().ifBlank { "UNKNOWN" }

        // V5.0.7259 §PREDICTION_IS_PERMISSION,_NOT_A_SIZE_HINT.
        // Canonical capital may only follow an explicit positive oracle
        // verdict. PROBE remains useful for shadow/replay/lab learning, but it
        // is not permission to open a live economic position.
        //
        // V5.0.7262 §PAPER_LEARNS,_LIVE_REQUIRES_CONVICTION.
        //
        // 7259 applied the rule above to PAPER as well, and on the operator's
        // 5.0.7261 clean book that produced admit=0 probe=2069 -> denies=2011
        // -> EXEC=0 -> lifetime trades=0. The oracle judges from terminal
        // evidence; terminal evidence comes from closed trades; 7259 made
        // trades require the evidence first. 7261's cold-start admit did not
        // reach it either (score>=60 against a scorer producing 9-32). §2b of
        // this very file says why that is wrong: "a small position is how the
        // brain learns a bucket... NOTHING IS DISABLED."
        //
        // So in PAPER a non-ADMIT verdict is not a denial; it is the reason
        // paper exists. The candidate still walks every branch below (policy
        // hard block, DUMP-regime deny, dead-cohort budget, capital target,
        // size floor) and, if it survives, leaves as a metered PROBE_ONLY at
        // the 7139 quarter size instead of an ALLOW. One per lane x band per
        // five minutes (the existing negative-cohort window), so paper learns
        // at a bounded rate rather than firehosing. REFUSE denies in both
        // modes. LIVE is byte-for-byte 7259.
        // V5.0.7263 §THE_ORACLE_ADVISES;_IT_DOES_NOT_GATE.
        //
        // Operator, on 7262: "the oracle is way way too strict to allow any
        // trading in paper or live. I get its purpose but it also has to allow
        // trading. not probing."
        //
        // So the categorical verdict is telemetry, in both modes. The oracle's
        // NUMBERS still reach every branch below through inputs.livePWin,
        // inputs.expectedPnl and inputs.cohortSample — that is how it was
        // wired from 6915 until 7259 — and those branches already refuse or
        // meter an entry when there is EVIDENCE of negative expectancy (§2
        // DUMP-regime deny, §2b proven-dead cohort budget, §5 source family).
        // What is gone is the rule that "no evidence yet" is itself a refusal.
        // A cold book trades at the size the rest of the stack chooses, the
        // closes it produces are the evidence, and the evidence-based branches
        // take over as soon as a cohort matures. Nothing here is probe-sized
        // for want of history; only a cohort that has PROVEN itself negative
        // is metered, exactly as §2b has said since 6909.
        //
        // "until the Oracle can prove its edge yes." OracleEdgeProof7263 grades
        // every forecast against the close that follows it. While it reads
        // ADVISORY the verdict word is telemetry; once it reads PROVEN — the
        // ADMIT pile demonstrably settling better than the PROBE/REFUSE pile
        // on >=20/>=10 closes — the verdict is allowed to gate: LIVE is ADMIT
        // or nothing (7259), PAPER meters a PROBE and denies a REFUSE. If the
        // edge stops holding it demotes itself and this becomes advisory again.
        val oracleTier7263 = try { OracleEdgeProof7263.tier() } catch (_: Throwable) { OracleEdgeProof7263.Tier.ADVISORY }
        try {
            PipelineHealthCollector.labelInc(
                "ORACLE_VERDICT_${oracleTier7263.name}_7263_${inputs.oracleVerdict6915?.name ?: "MISSING"}",
            )
        } catch (_: Throwable) {}
        // V5.0.7287 §A PROVEN ORACLE GUIDES; IT DOES NOT ADVISE.
        //
        // Operator: "once it proves itself absolutely should be guiding the
        // trading not just advising." And: "just trade or dont trade."
        //
        // A recorded safety fact (serial-rugger creator, tier-B risk) refuses
        // in every tier. Otherwise, once OracleEdgeProof7263 reads PROVEN and
        // the estimator is not degenerate, the oracle's verdict IS the
        // admission decision, in paper and live alike: ADMIT trades at the
        // requested size past every cruder cohort rule below (those rules
        // estimate the same expectancy the oracle has now been shown to
        // estimate better), REFUSE does not trade. The policy head's
        // HARD_BLOCK stays absolute ahead of it. While ADVISORY, the verdict
        // is recorded and graded, and the evidence rules below decide.
        if (inputs.oracleHardSafety7287) {
            return deny("ORACLE_HARD_SAFETY_REFUSE_7287", inputs, "oracle=REFUSE hardSafety=true")
        }

        // §1 — UnifiedPolicyHead HARD_BLOCK is absolute (per directive).
        if (inputs.policyHardBlock) return deny("POLICY_HARD_BLOCK", inputs, "policyHead=HARD_BLOCK")

        val oracleBinding7287 = oracleTier7263 == OracleEdgeProof7263.Tier.PROVEN && !try {
            PredictiveEntryOracle6915.isDegenerateNow7120()
        } catch (_: Throwable) { false }
        if (oracleBinding7287) {
            when (inputs.oracleVerdict6915) {
                PredictiveEntryOracle6915.Verdict.ADMIT ->
                    return allow(inputs, "ORACLE_PROVEN_ADMIT_7287")
                PredictiveEntryOracle6915.Verdict.REFUSE ->
                    return deny("ORACLE_PROVEN_REFUSE_7287", inputs, "oracle=REFUSE tier=PROVEN")
                null -> Unit
            }
        }

        // Bootstrap grace — we don't deny cohorts we have not seen enough of.
        val cohortMature = inputs.cohortSample >= MATURITY_MIN_N
        val sourceMature = inputs.sourceSample >= MATURITY_MIN_N

        // §2 — DUMP regime, mature negative cohort → DENY normal
        val laneLossRate = inputs.laneLossRatePct / 100.0
        val lanePWin = inputs.laneWrPct / 100.0
        if (regimeKey == "DUMP" && cohortMature) {
            val strongNegative =
                inputs.cohortSample >= DUMP_STRONG_MIN_N &&
                inputs.expectedPnl < 0.0 &&
                lanePWin < DUMP_STRONG_PWIN_MAX
            if (strongNegative) {
                // V5.0.7139 — the WORST cohort was the only one with no budget.
                //
                // "Deny normal, allow probe" had no meter on it, while the
                // strictly WEAKER case below (COHORT_MATURE_NEGATIVE_6909) runs
                // every probe through cohortProbeBudgetAllows6909. So the
                // evidence ran backwards: the more certain the system was that a
                // cohort loses money, the more freely it was allowed to keep
                // buying it. The 7136 device shows the result —
                // ENTRY_AUTHORITY_DENY_REASON_REGIME_DUMP_STRONG_NEGATIVE fired
                // 716 times in 210 seconds, unmetered.
                //
                // Same meter, same fallback, same wording as the weaker branch:
                // this is not a disable. The cohort is admitted again on the
                // next window, so exploration continues at a rate the evidence
                // justifies instead of continuously.
                val dumpCohortKey7139 = "$laneKey|S${inputs.scoreBand}|$regimeKey"
                val dumpDetail7139 = "dump strong n=${inputs.cohortSample} " +
                    "lanePWin=${"%.2f".format(lanePWin)} EV=${"%.2f".format(inputs.expectedPnl)} " +
                    "cohort=$dumpCohortKey7139"
                // V5.0.7287 — trade or don't: strong negative evidence in a
                // DUMP regime does not trade. No quarter-size probe.
                return deny("REGIME_DUMP_STRONG_NEGATIVE", inputs, dumpDetail7139)
            }
            val matureNegative =
                inputs.cohortSample >= DUMP_MATURE_MIN_N &&
                (lanePWin < DUMP_MATURE_PWIN_MAX || laneLossRate >= DUMP_MATURE_LOSS_RATE)
            if (matureNegative) {
                return deny("REGIME_DUMP_MATURE_NEGATIVE", inputs,
                    "dump n=${inputs.cohortSample} lanePWin=${"%.2f".format(lanePWin)} lossRate=${"%.2f".format(laneLossRate)}")
            }
        }

        // ── §2c V5.0.6915 §THE_ORACLE_MUST_BE_ABLE_TO_SAY_NO ───────────────
        //
        // Operator: "the brains are meant to contribute way more than trade
        // size!!!"
        //
        // PredictiveEntryOracle6915 blends cell/lane/global expectancy by
        // shrinkage and folds in AutonomousMetaPolicy, SemanticPatternGraph,
        // SsiPilotCouncil and realised source expectancy. Unlike every earlier
        // brain read, it ALWAYS returns an estimate — a thin cell falls back to
        // its lane, a thin lane to the book — so there is no longer any state
        // in which the intelligence stack has nothing to say at admission time.
        //
        // Its expectancy arrives here as `expectedPnl` and its confidence as
        // `cohortSample` (see LearnedAdmissionInputs6909 for that mapping). The
        // rule below is the only thing in this authority that can refuse on
        // expectancy ALONE, without also requiring a particular regime or a
        // low win rate — because a fat-tailed cohort is allowed to have a
        // terrible win rate and still be the best trade on the board.
        //
        // WHY THIS DOES NOT CAP RUNNERS. Expectancy is mean PnL. A single 10x
        // lifts a cohort's mean enormously, so the exact cohorts that produce
        // runners score HIGHEST here. What scores low is a cohort that loses
        // steadily with no tail — PROJECT_SNIPER at 0/11 and EV -37.92%, which
        // is what the operator has been watching bleed.
        //
        // PROBE_ONLY, not DENY, and metered by the same cohort budget as §2b:
        // a grave still gets a trickle so it can prove it has healed.
        // V5.0.7120 — a DEGENERATE oracle may not meter throughput.
        //
        // This branch is where the oracle's expectancy becomes a refusal or a
        // metered probe, and on the operator's 5.0.7117 device it spent 152
        // denials as ORACLE_PROBE_BUDGET_6915 while the oracle itself read
        // n=1783 top=REFUSE@1.00:DEGENERATE. Demoting the oracle's own verdict
        // (see PredictiveEntryOracle6915 §7120) is not sufficient on its own,
        // because this branch does not read that verdict — it reads
        // `inputs.expectedPnl`, which LearnedAdmissionInputs6909 sources from
        // the SAME collapsed estimator. Without this guard the demotion would
        // look complete in the oracle's status line and change nothing here.
        //
        // Operator's rule, verbatim: "authority = ADVISORY, decision multiplier
        // = 1.0 / neutral, cannot consume probe budget." So the branch is
        // skipped entirely — no refusal, no probe, and crucially no call to
        // cohortProbeBudgetAllows6909, which would otherwise burn a cohort's
        // budget on a verdict this build has already decided not to trust.
        //
        // Everything below this branch still applies: §2b cohort evidence,
        // regime tests and win-rate tests are unaffected, so this is a demotion
        // of one collapsed input and not a loosening of admission. The operator
        // was explicit that zero-signal FDG protection stays as it is.
        val oracleDegenerate7120 = try {
            PredictiveEntryOracle6915.isDegenerateNow7120()
        } catch (_: Throwable) { false }
        if (oracleDegenerate7120) {
            try {
                PipelineHealthCollector.labelInc("ORACLE_AUTHORITY_DEMOTED_ADVISORY_7120")
            } catch (_: Throwable) {}
        }
        // V5.0.7154 §A CONFIDENCE WEIGHT IS NOT A SAMPLE COUNT.
        //
        // ORACLE_MIN_CONFIDENT_N_6915 = 8 is an OBSERVATION bar: "do not
        // refuse a lane until eight terminals say so". What actually reaches
        // it is LearnedAdmissionInputs6909's
        //
        //     cohortSample = maxOf(cohortSample, oracleEffectiveN6915)
        //     oracleEffectiveN6915 = (oracle.confidence * 16.0).toInt()
        //
        // and PredictiveEntryOracle6915.Forecast documents `confidence` in
        // its own KDoc as "Effective sample weight behind the estimate. NOT a
        // raw count." So a confidence of 0.50 alone clears an eight-close bar
        // with zero closes behind it, and maxOf guarantees the thinner, truer
        // number can never win.
        //
        // Operator's 5.0.7145 device, the denial that fired 1263 times:
        //
        //   ENTRY_AUTHORITY_DENY_6846 cohort=QUALITY|S19|ORACLE effN=13
        //     oracleEV=-0.2955 pWin=0.08 budgeted=false
        //   PREDICTIVE_ORACLE_REFUSED_6915 E=-29.55% conf=0.82
        //     [cellScoreExp(n=5,E=-45.9) cellFwd(pooled,n=1,E=-9.8,pW=0.00)
        //      lane(n=14,E=-28.x)]
        //
        // effN=13 is exactly 0.82 x 16. The evidence it is standing in for is
        // a cell of FIVE and a POOLED cell of ONE — pooled meaning
        // ForwardOutcomeModel.cohortEvidence6911 blended paper and live with
        // no shrink, so paper's losses are refusing live entries. Thirteen
        // observations were reported; one and five existed.
        //
        // The threshold is NOT changed — 8 still means 8. The branch now
        // reads the true terminal count for the bar, and where the weight
        // says confident but the count does not, the candidate goes to the
        // metered PROBE path that already exists rather than to a hard deny.
        // Refusing on evidence we do not have is the same defect as every
        // other absence-as-fact in this run; probing on thin evidence is how
        // the bot earns the count that would justify refusing later.
        // V5.0.7207 §THE_EXCEPTION_BECAME_THE_ONLY_PATH.
        //
        // Operator 5.0.7206: volume finally arrived — V3 1783->4309, FDG
        // 112->1129, buys 28->112 — and the book went to -1.1151 SOL, PF 0.56,
        // WR 13.2%, with a 16-loss streak. The counters name why:
        //
        //   PREDICTIVE_ORACLE_REFUSE_6915            4042
        //   ORACLE_REFUSAL_ON_WEIGHT_NOT_COUNT_7154  4088
        //
        // Those two being equal means EVERY oracle refusal became a probe.
        // 7154 was written as a narrow exception and is in fact the only path
        // through this branch, which leaves ORACLE_REFUSE_EV_6915 unreachable.
        // Same defect class as 7148's VETO_BRIER_MAX and 7201's offence n>=20:
        // a gate parked behind a threshold it cannot reach.
        //
        // The arithmetic makes it inevitable. oracleRawCohortN7154 is set from
        // LearnedAdmissionInputs6909:276, which sources it from the SCORE-BAND
        // CELL alone (agg6911.samples / fwd.samples). Cells are lane x band —
        // 9 active lanes x 5 bands = 45 of them — so 69 lifetime closes give a
        // mean cell count near 1.5 and a fresh band starts at 0. A cell
        // essentially can never reach 8. Device, verbatim:
        //
        //   ENTRY_AUTHORITY_PROBE_6846 cohort=PROJECT_SNIPER|S41|ORACLE
        //     effN=11 rawN=0 oracleEV=-0.1943 pWin=0.18 budgeted=false
        //   PREDICTIVE_ORACLE_REFUSED_6915 E=-19.43% conf=0.74
        //     reason=NEGATIVE_EXPECTANCY_WITH_EVIDENCE_6915
        //     [cellScoreExp(n=2,E=-4.1) lane(n=10,E=-14.5,WR=20%) global(n=50,E=-2.6)]
        //
        // rawN=0 while the oracle says WITH_EVIDENCE at conf=0.74, because the
        // lane has TEN real terminal closes reading -14.5% EV at 20% WR. That
        // is not "evidence we do not have". It is evidence at a coarser
        // resolution — and it is precisely the evidence the oracle blended to
        // reach -19.43%.
        //
        // 7154's principle was right and stays: never refuse on a confidence
        // WEIGHT that no real count supports. It simply read one level of the
        // hierarchy and treated the absence of a cell as the absence of
        // everything. Thin now means thin at BOTH levels.
        //
        // No threshold moves — 8 still means 8, ORACLE_REFUSE_EV_6915 is still
        // -0.08. Nothing is disabled or capped: falling through to the lines
        // below still yields a METERED PROBE whenever the cohort budget allows,
        // so a lane that has proven itself negative keeps a trickle to prove it
        // has healed, exactly as §2b intends. Only once that budget is spent
        // does it deny.
        //
        // Effect on the 7206 book, enumerated over every active lane from its
        // lane sample count and oracle EV. The branch only fires at EV <= -8%,
        // so a lane above that never reaches here at all:
        //   PROJECT_SNIPER n=10 EV=-11.6% WR=20% -> metered, 1 per band per 15m
        //   QUALITY        n=10 EV=-19.2% WR=0%  -> metered, 1 per band per 15m
        //   CYCLIC         n=3  EV=-18.2%        -> still thin, still probes
        //   BLUECHIP  n=7 EV=+4.2%   \
        //   MOONSHOT  n=9 EV=+30.4%   |  EV above the refuse floor:
        //   SHITCOIN  n=2 EV=+118%    |  branch never reached, untouched
        //   CORE n=4 / TREASURY n=1 / EXPRESS n=0  /
        // Exactly two lanes change behaviour, and they are the two the operator
        // has been watching bleed. Every immature lane keeps earning its count
        // and every profitable lane is untouched.
        val oracleThinEvidence7154 =
            inputs.oracleRawCohortN7154 < ORACLE_MIN_CONFIDENT_N_6915 &&
                inputs.oracleRawLaneN7207 < ORACLE_MIN_CONFIDENT_N_6915
        if (!oracleDegenerate7120 &&
            inputs.cohortSample >= ORACLE_MIN_CONFIDENT_N_6915 &&
            inputs.expectedPnl <= ORACLE_REFUSE_EV_6915
        ) {
            val cohortKey6915 = "$laneKey|S${inputs.scoreBand}|ORACLE"
            val detail = "cohort=$cohortKey6915 effN=${inputs.cohortSample} " +
                "rawN=${inputs.oracleRawCohortN7154} " +
                // V5.0.7207 — print the lane count beside the cell count. The
                // 7206 snapshot showed rawN=0 next to effN=11 and gave the
                // operator no way to see that ten real lane closes existed.
                "laneN7207=${inputs.oracleRawLaneN7207} " +
                "oracleEV=${"%.4f".format(inputs.expectedPnl)} " +
                "pWin=${"%.2f".format(inputs.livePWin)}"
            if (!oracleThinEvidence7154) {
                try {
                    PipelineHealthCollector.labelInc("ORACLE_REFUSAL_HONOURED_ON_LANE_EVIDENCE_7207")
                    PipelineHealthCollector.labelInc(
                        "ORACLE_REFUSAL_HONOURED_ON_LANE_EVIDENCE_7207|$laneKey".take(60),
                    )
                } catch (_: Throwable) {}
            }
            if (oracleThinEvidence7154) {
                try {
                    PipelineHealthCollector.labelInc("ORACLE_REFUSAL_ON_WEIGHT_NOT_COUNT_7154")
                    PipelineHealthCollector.labelInc(
                        "ORACLE_REFUSAL_ON_WEIGHT_NOT_COUNT_7154|$laneKey".take(60),
                    )
                } catch (_: Throwable) {}
                // V5.0.7287 — thin at both cell and lane: there is no
                // evidence to refuse on, so the candidate trades at full size
                // through the rest of the checks instead of as a probe.
            } else {
                // V5.0.7287 — evidenced negative expectancy does not trade.
                return deny("ORACLE_NEGATIVE_EXPECTANCY_6915", inputs, detail)
            }
        }

        // ── §2b V5.0.6909 §COHORT_EVIDENCE_IS_NOT_A_REGIME_PRIVILEGE ───────
        //
        // OPERATOR DIAGNOSIS (5.0.6908):
        //
        //   Regime:        CHOP wr=2.5% scoreFloorDelta=5 sizeMult=0.35
        //   UnifiedPolicy: global bias=-0.56
        //   CORE:          18 finalized, 0W / 18L, EV -6.94%
        //   Verdicts:      BUY 819 / NO_BUY 101 / PROBE_ONLY 11
        //
        //   > "The learner is saying conditions are poor, but the upstream
        //   >  admission machinery continues manufacturing BUY intents."
        //
        // 88% BUY against a 2-7% realised win rate. The reason is directly
        // above: §2 — the ONLY cohort-expectancy deny in this authority — is
        // gated on `regimeKey == "DUMP"`, and the bot is in CHOP. So a cohort
        // that is 0-for-18 with EV -6.94% was admitted at full size purely
        // because the weather was classified CHOP rather than DUMP.
        //
        // Those are independent facts. DUMP is a statement about the market;
        // 0/18 is a statement about this cohort. A cohort that has never won
        // in eighteen attempts is dead in every regime, and making an
        // environmental condition a PRECONDITION for acting on cohort
        // evidence is what left the learned intelligence with nothing to bite
        // on. Regime belongs here as a severity modifier, not a gatekeeper —
        // which is what §2's stricter DUMP thresholds above already are.
        //
        // THE FAT TAIL IS PROTECTED, DELIBERATELY. Profitability currently
        // comes from three runners producing ~95.7% of all winning SOL, and
        // PROJECT_SNIPER carries it at EV +28.97%/trade despite a 13% win
        // rate. Judging by win rate would destroy exactly that. So every test
        // below is keyed on the COHORT (lane x scoreBand x regime, via the
        // forward model) and requires NEGATIVE EXPECTANCY, never a low win
        // rate alone. The model already separates these cleanly:
        //
        //   PROJECT_SNIPER|S20  E[pnl]=+31.5%  -> positive EV, untouched
        //   PROJECT_SNIPER|S10  pWin=0% E=-14.3% -> negative EV, throttled
        //
        // NOTHING IS DISABLED (V5.9.1358). The verdict for a mature-negative
        // cohort is PROBE_ONLY, not DENY, because a small live position is how
        // the brain learns a bucket and heals it. What changes is FREQUENCY:
        // a proven-dead cohort gets a metered trickle of probes instead of
        // eighteen consecutive full-size entries. Volume is the actual defect
        // — 819 BUYs — not the existence of the trade. A cohort throttled here
        // keeps producing real outcomes and re-admits itself automatically the
        // moment its expectancy turns.
        if (cohortMature && inputs.expectedPnl < 0.0) {
            val provenDead = inputs.cohortSample >= COHORT_DEAD_MIN_N_6909 &&
                inputs.livePWin <= COHORT_DEAD_PWIN_MAX_6909 &&
                inputs.expectedPnl <= COHORT_DEAD_EV_MAX_6909
            val matureNegative6909 = inputs.cohortSample >= MATURITY_MIN_N &&
                inputs.livePWin < COHORT_NEGATIVE_PWIN_MAX_6909
            if (provenDead || matureNegative6909) {
                val cohortKey6909 = "$laneKey|S${inputs.scoreBand}|$regimeKey"
                val detail = "cohort=$cohortKey6909 n=${inputs.cohortSample} " +
                    "pWin=${"%.2f".format(inputs.livePWin)} EV=${"%.4f".format(inputs.expectedPnl)} " +
                    "provenDead=$provenDead"
                // V5.0.7287 — a mature cohort with negative expectancy does
                // not trade. It re-admits itself as soon as its expectancy
                // (which the rest of its lane and the book keep moving) turns.
                return deny("COHORT_MATURE_NEGATIVE_6909", inputs, detail)
            }
        }

        // §5 — Source-family adaptation. Mature source with severe
        // negative expectancy → PROBE_ONLY (adaptive; no hardcoded lane
        // disable).
        if (sourceMature &&
            inputs.sourcePWin < SOURCE_FAMILY_PWIN_SUSPECT &&
            inputs.sourceExpectedPnl < 0.0
        ) {
            return deny("SOURCE_FAMILY_MATURE_NEGATIVE", inputs,
                "srcKey=$srcKey n=${inputs.sourceSample} srcPWin=${"%.2f".format(inputs.sourcePWin)} srcEV=${"%.2f".format(inputs.sourceExpectedPnl)}")
        }

        // §4 — LosingPatternMemory + Brain SOFT_BLOCK together produce
        // mature negative evidence. Route to PROBE_ONLY rather than
        // silently pass through.
        if (inputs.losingPatternMatch && inputs.brainSoftBlock && cohortMature) {
            return deny("POLICY_SOFT_NEGATIVE", inputs,
                "brainSoft=true losingPattern=true cohortN=${inputs.cohortSample}")
        }

        // §6 — Lane capital target: if projected exposure materially
        // exceeds the adaptive target, suppress low-edge entries; permit
        // exceptional high-edge candidates through.
        val projectedExposure = inputs.laneCapitalUsedSol +
            inputs.laneCapitalReservedSol +
            inputs.requestedSizeSol
        if (inputs.laneCapitalTargetSol > 0.0 &&
            projectedExposure > inputs.laneCapitalTargetSol * CAPITAL_TARGET_TOLERANCE
        ) {
            val edgeComposite = RuntimeTune6833.edgeComposite(
                livePWin = inputs.livePWin,
                expectedPnl = inputs.expectedPnl,
                sourceQuality = inputs.sourcePWin,
            )
            if (!RuntimeTune6833.isExceptionalEdge(edgeComposite)) {
                return deny("CAPITAL_LANE_TARGET_EXCEEDED", inputs,
                    "projected=${"%.4f".format(projectedExposure)} target=${"%.4f".format(inputs.laneCapitalTargetSol)} " +
                        "tol=${"%.2f".format(CAPITAL_TARGET_TOLERANCE)} edge=${"%.2f".format(edgeComposite)}")
            }
        }

        // §7 — Early reject cohorts whose learned/regime shaping would
        // mathematically resolve below the executable minimum. This
        // stops the mark → size → phantom-ticket churn documented in
        // ORDER_SIZE_BLOCKED_EXIT_THROUGHPUT=750 / PAPER_BUY_REJECTED_
        // BEFORE_TICKET_SIZE=658.
        if (inputs.minExecutableSol > 0.0 && cohortMature) {
            // Simple lower-bound: if lane's own damper × EXPRESS mult
            // (already integrated in RuntimeTune6833) × cohortShaping
            // is < minExecutableSol, deny early.
            val cohortSizeShaping = when {
                lanePWin < 0.10 -> 0.35
                lanePWin < 0.20 -> 0.50
                lanePWin < 0.30 -> 0.75
                else -> 1.0
            }
            val projectedSize = inputs.requestedSizeSol * cohortSizeShaping
            if (projectedSize < inputs.minExecutableSol * 0.75) {
                return deny("SIZE_SHAPING_BELOW_MIN_EXECUTABLE", inputs,
                    "requested=${"%.4f".format(inputs.requestedSizeSol)} " +
                        "shaping=${"%.2f".format(cohortSizeShaping)} " +
                        "projected=${"%.4f".format(projectedSize)} " +
                        "minExec=${"%.4f".format(inputs.minExecutableSol)}")
            }
        }

        // V5.0.7263 — a candidate that survived every evidence-based branch is
        // admitted at full requested size, with or without oracle history.
        return allow(inputs, "clear")
    }

    // ── §4 LANE-OWNER weighting (canonical helper for the owner
    //      election path). Exposes the four components + FINAL so the
    //      operator's acceptance requirements (LANE_OWNER_WEIGHT_*)
    //      can be published. ──────────────────────────────────────────
    data class OwnerWeights(
        val base: Double,
        val expectancy: Double,
        val regime: Double,
        val source: Double,
        val final: Double,
    )

    fun laneOwnerWeights(
        lane: String,
        laneExpectancy: Double,   // -1..+1
        regime: String,
        sourceExpectancy: Double, // -1..+1
    ): OwnerWeights {
        val base = 1.0
        val expectancy = (1.0 + laneExpectancy).coerceIn(0.0, 2.0)
        val regimeMult = when (regime.trim().uppercase()) {
            "DUMP" -> 0.6
            "CHOP" -> 0.8
            "PUMP" -> 1.1
            else -> 1.0
        }
        val source = (1.0 + sourceExpectancy).coerceIn(0.0, 2.0)
        val final = (base * expectancy * regimeMult * source).coerceIn(0.0, 4.0)
        try {
            val laneKey = lane.trim().uppercase()
            PipelineHealthCollector.labelInc("LANE_OWNER_WEIGHT_BASE_$laneKey")
            PipelineHealthCollector.labelInc("LANE_OWNER_WEIGHT_EXPECTANCY_$laneKey")
            PipelineHealthCollector.labelInc("LANE_OWNER_WEIGHT_REGIME_$laneKey")
            PipelineHealthCollector.labelInc("LANE_OWNER_WEIGHT_SOURCE_$laneKey")
            PipelineHealthCollector.labelInc("LANE_OWNER_WEIGHT_FINAL_$laneKey")
        } catch (_: Throwable) {}
        return OwnerWeights(base, expectancy, regimeMult, source, final)
    }

    // ── decision helpers ─────────────────────────────────────────────
    private fun allow(inputs: Inputs, reason: String): Decision {
        allows.incrementAndGet()
        try { PipelineHealthCollector.labelInc("ENTRY_AUTHORITY_ALLOW") } catch (_: Throwable) {}
        return Decision(Verdict.ALLOW, inputs.requestedSizeSol, "allow:$reason", "")
    }

    private fun deny(category: String, inputs: Inputs, detail: String): Decision {
        denies.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("ENTRY_AUTHORITY_DENY")
            PipelineHealthCollector.labelInc("ENTRY_AUTHORITY_DENY_REASON_$category")
            ForensicLogger.lifecycle(
                "ENTRY_AUTHORITY_DENY_6846",
                "lane=${inputs.lane} regime=${inputs.regime} src=${inputs.sourceFamily} " +
                    "category=$category $detail",
            )
        } catch (_: Throwable) {}
        return Decision(Verdict.DENY, 0.0, "deny:$category:$detail", category)
    }

    data class Summary(val allows: Long, val probes: Long, val denies: Long)
    fun summary(): Summary = Summary(allows.get(), probes.get(), denies.get())

    fun statusLine(): String {
        val s = summary()
        return "LearnedAdmissionAuthority6846 allows=${s.allows} probes=${s.probes} denies=${s.denies}"
    }

    internal fun clearForTest() {
        allows.set(0L); probes.set(0L); denies.set(0L)
    }
}
