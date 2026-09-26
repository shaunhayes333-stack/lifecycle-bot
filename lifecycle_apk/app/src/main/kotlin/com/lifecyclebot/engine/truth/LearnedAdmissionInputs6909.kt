package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6909 §THE_LEARNED_ADMISSION_AUTHORITY_HAD_NO_CALLER.
 *
 * LearnedAdmissionAuthority6846 is 317 lines written to fix one specific
 * operator complaint, quoted in its own header:
 *
 *   "ExecutableEntryAuthority6450 gates=3678 allows=3678 denies=0 while
 *    BrainConsensusGate was reporting 75.2% SOFT_BLOCK and lane WR=3.8%.
 *    The admission boundary was rubber-stamping every candidate; the
 *    learned intelligence had nowhere to bite."
 *
 * It was never wired. The only `Inputs(` in the entire codebase was its own
 * `data class Inputs(` declaration. ExecutableEntryAuthority6450 grew the
 * matching `gate(inputs)` overload — complete and correct, ALLOW /
 * PROBE_ONLY / DENY all handled — and its own comment closes with "this
 * overload is opt-in". Nobody opted in. Both real admission call sites
 * (ExecutableOpenGate and BotService) still call the 3-arg
 * `gate(lane, mint, 1.0)`, which reaches only the losing-streak damper and
 * never touches the learned authority at all.
 *
 * So the 5.0.6908 diagnosis — "learners know conditions are poor but do not
 * have enough admission authority", BUY 819 / NO_BUY 101 against a 2-7% win
 * rate — is not a calibration problem. The calibrated authority exists and
 * was simply unreachable.
 *
 * WHY THIS IS A SEPARATE OBJECT. 6846's header states its design rule
 * explicitly: "The authority does not query these itself so the coupling
 * stays testable and the caller controls when the signals are read." That is
 * the right call and it is preserved here — the signal reads live in this
 * assembler, 6846 stays a pure function of its Inputs, and its existing test
 * surface is untouched.
 *
 * HONEST PARTIAL COVERAGE. Several Inputs fields have no authority this
 * assembler can read without inventing one (source-family expectancy,
 * UnifiedPolicyHead hard-block, LosingPatternMemory cohort membership, lane
 * capital targets). Those are filled with NEUTRAL values chosen so their
 * corresponding section of 6846 stays inert rather than misfires:
 * `sourceSample = 0` fails §5's maturity test, `policyHardBlock = false` and
 * `losingPatternMatch = false` fail §1/§4, and `laneCapitalTargetSol = 0.0`
 * fails §6's `> 0.0` guard. Sections §2/§2b, which the diagnosis identifies
 * as the ones that matter, are fully populated from real evidence. Filling
 * the rest is follow-up work, not a silent gap.
 */
object LearnedAdmissionInputs6909 {

    private val assembled = AtomicLong(0L)
    private val forecastMissing = AtomicLong(0L)
    private val forecastResolved = AtomicLong(0L)
    // V5.0.6911 — how often the regime-agnostic aggregate rescued a read the
    // per-regime cell could not answer. If this stays at 0 while
    // matureCohorts is also 0, the model genuinely has no evidence yet; if
    // this is high, the regime-keyed blindness was the whole problem.
    private val aggregateUsed6911 = AtomicLong(0L)
    private val matureCohorts6911 = AtomicLong(0L)
    // V5.0.6915 — oracle reach. reads>0 proves the stack is consulted before
    // capital commits at all; the verdict split shows what it concluded.
    private val oracleReads6915 = AtomicLong(0L)
    private val oracleAdmit6915 = AtomicLong(0L)
    private val oracleRefuse6915 = AtomicLong(0L)

    /**
     * Assemble admission inputs for (lane, mint) from the live learned
     * signals. Never throws; optional contributors fall back to neutral, while
     * a missing oracle verdict is explicitly non-executable. This blocks only
     * the affected candidate and cannot stop the scanner/runtime.
     */
    fun build(
        lane: String,
        mint: String,
        requestedSizeSol: Double,
        entryScore: Int,
        minExecutableSol: Double,
        probeSizeSol: Double,
        // V5.0.6915 — discovery source, so the oracle can read the source
        // scorecard's realised expectancy. Blank is tolerated and simply
        // drops that one input.
        sourceFamilyHint: String = "",
        // V5.0.7260 — candidate-specific ForwardOutcomeModel key and
        // confidence. Blank quality/phase made every exact forecast read the
        // bootstrap cell, even when FDG had already produced these fields.
        qualityHint: String = "",
        edgePhaseHint: String = "",
        candidateConfidenceHint: Double = 0.50,
    ): LearnedAdmissionAuthority6846.Inputs {
        assembled.incrementAndGet()
        val laneKey = lane.trim().uppercase().ifBlank { "UNKNOWN" }
        val regime = try {
            com.lifecyclebot.engine.RegimeDetector.currentRegime().name
        } catch (_: Throwable) { "UNKNOWN" }

        // Forward outcome model: ask the exact candidate cell first. Both real
        // admission callers now carry the quality and phase already resident
        // on TokenState; the model itself retains its coarse fallback.
        val fwd = try {
            com.lifecyclebot.engine.ForwardOutcomeModel.forecast(
                laneKey,
                entryScore.coerceAtLeast(0),
                qualityHint.ifBlank { "U" },
                regime,
                edgePhaseHint.ifBlank { "UNKNOWN" },
            )
        } catch (_: Throwable) { null }
        if (fwd == null || fwd.source == "bootstrap") forecastMissing.incrementAndGet()
        else forecastResolved.incrementAndGet()

        // UNIT CONVERSION — NOT COSMETIC. ForwardOutcomeModel.expectedPnl is a
        // PERCENT (its own dump prints "E[pnl]=+31.5%"), while
        // LearnedAdmissionAuthority6846.Inputs.expectedPnl is documented
        // "-1..+1", a fraction. Handing the percent straight through would be
        // a 100x error: §6 feeds this value to RuntimeTune6833.edgeComposite,
        // where -14.3 instead of -0.143 would swamp the composite. The sign
        // tests in §2/§2b would have survived it, which is precisely why this
        // class of mismatch survives review.
        val expectedPnlFraction = try {
            val raw = fwd?.expectedPnl ?: 0.0
            if (raw.isFinite()) raw / 100.0 else 0.0
        } catch (_: Throwable) { 0.0 }

        val laneSnap = try {
            com.lifecyclebot.engine.LiveProbabilityEngine.laneSnapshots()
                .firstOrNull { it.lane.equals(laneKey, ignoreCase = true) }
        } catch (_: Throwable) { null }
        val laneWrPct = laneSnap?.wrPct?.takeIf { it.isFinite() } ?: 0.0
        // Loss rate is the complement of the win rate over the same sample.
        // Breakevens make this an upper bound rather than an identity, which is
        // the conservative direction for a gate that only ever throttles.
        val laneLossRatePct = (100.0 - laneWrPct).coerceIn(0.0, 100.0)

        // V5.0.6911 — the per-regime forecast cell is the wrong granularity
        // for admission. Operator 5.0.6909: assembled=5589 with
        // forecastBootstrapOrMissing=5589 — every single admission read came
        // back bootstrap, so §2b never fired, while the model plainly held
        // n=13 and n=11 cells with well separated expectancy. Those cells were
        // written under regime=NORMAL and the detector had rotated to CHOP.
        // Ask the regime-agnostic (lane, band) aggregate instead; fall back to
        // the per-regime cell only when the aggregate is genuinely empty.
        val agg6911 = try {
            com.lifecyclebot.engine.ForwardOutcomeModel.cohortEvidence6911(laneKey, entryScore.coerceAtLeast(0))
        } catch (_: Throwable) { null }
        val useAgg6911 = agg6911 != null && agg6911.samples > 0L
        if (useAgg6911) {
            aggregateUsed6911.incrementAndGet()
            if (agg6911!!.samples >= 8L) matureCohorts6911.incrementAndGet()
        }

        // V5.0.6915 §THE_ORACLE_IS_THE_EVIDENCE_SOURCE.
        //
        // Operator: "the brains are meant to contribute way more than trade
        // size!!!" — and: "a human can guess. Aate should never."
        //
        // Up to 6914 this assembler fed the admission authority a raw
        // per-cohort cell, which returned bootstrap on 3,191 of 3,191 reads
        // (matureCohorts6911=1), so §2b's expectancy test could never fire and
        // admission always said yes. PredictiveEntryOracle6915 replaces that
        // single sparse cell with a hierarchical shrinkage estimate over
        // cell/lane/global PLUS bounded reads from AutonomousMetaPolicy,
        // SemanticPatternGraph, SsiPilotCouncil and the source scorecard. It
        // always produces an estimate, because a lane or the book as a whole
        // always has evidence even when a cell does not.
        //
        // The oracle's CONFIDENCE is mapped onto cohortSample, which is what
        // 6846's maturity tests read. That is the whole unlock: maturity stops
        // meaning "this exact cell has 8 closes" and starts meaning "the
        // hierarchy behind this candidate carries enough weight to act on",
        // without changing a single existing threshold.
        // V5.0.6917 — candidate identity for the mint-specific brains. Read
        // from the live TokenState rather than threaded through every caller,
        // because these are enrichment inputs: a miss simply drops that brain
        // from the estimate and the cohort hierarchy still answers.
        val tsForBrains6917 = try {
            com.lifecyclebot.engine.BotService.status.tokens[mint]
        } catch (_: Throwable) { null }
        val symbolHint6917 = try { tsForBrains6917?.symbol.orEmpty() } catch (_: Throwable) { "" }
        val liquidityUsdHint6917 = try { tsForBrains6917?.lastLiquidityUsd ?: 0.0 } catch (_: Throwable) { 0.0 }
        // V5.0.7070 — THE CREATOR DEFENCE WAS STARVED AT BOTH ENDS.
        //
        // PredictiveEntryOracle6915 scales a penalty by creator rug count and
        // hardSafetyRefusal6927 refuses serial ruggers outright. That defence
        // is complete and correctly wired, and it has never fired once,
        // because neither end of it was ever fed:
        //
        //   WRITE: BotService passed `creatorWallet = null` into learnFromRug
        //          with the comment "Would need API to get this", so the
        //          blacklist was permanently empty. (Fixed this build.)
        //
        //   READ:  this line asks Birdeye, which is dead — sr=0%, http 401,
        //          BIRDEYE_KEY_DEAD_401_STICKY_6503 — and the free DexScreener
        //          seeder explicitly leaves creatorAddress blank. So `creator`
        //          arrived empty at the oracle and the whole branch was skipped
        //          on every candidate.
        //
        // No API was ever needed at either end. PumpFunWebSocket delivers the
        // creator as `traderPublicKey` on every launch and
        // DataOrchestrator.handleNewPumpToken already stores it in
        // OperatorRegistry — free, keyless, and by far the dominant intake
        // source on this bot. Birdeye stays FIRST because when it is alive it
        // covers non-pump mints too; the registry is the fallback that makes
        // the defence work at all today.
        val creatorHint6917 = try {
            com.lifecyclebot.engine.BirdeyeCreationInfoProvider.peekCached(mint)?.creatorAddress
                ?.takeIf { it.isNotBlank() }
                ?: com.lifecyclebot.engine.OperatorRegistry.getDevWallet(mint).orEmpty()
        } catch (_: Throwable) { "" }
        try {
            if (creatorHint6917.isNotBlank()) {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CREATOR_RESOLVED_FOR_ADMISSION_7070")
            } else {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CREATOR_UNRESOLVED_FOR_ADMISSION_7070")
            }
        } catch (_: Throwable) {}

        // V5.0.6915 — realised per-source expectancy. Read once here and
        // shared with 6846's §5, which has been inert since 6909 only because
        // the scorecard had no accessor.
        val srcExp6915 = try {
            com.lifecyclebot.engine.SourceFamilyOpportunityScorecard
                .expectancyFor6915(sourceFamilyHint)
        } catch (_: Throwable) { null }
        val oracle6915 = try {
            PredictiveEntryOracle6915.evaluate(
                lane = laneKey,
                score = entryScore.coerceAtLeast(0),
                sourceFamily = sourceFamilyHint,
                regime = regime,
                // V5.0.6917 — candidate identity so the mint-specific brains
                // can be read: CollectiveIntelligenceAI.predictTokenSuccess,
                // MomentumPredictorAI, InsiderTrackerAI and the creator rug
                // memory. All were zero-caller before this.
                mint = mint,
                symbol = symbolHint6917,
                liquidityUsd = liquidityUsdHint6917,
                creator = creatorHint6917,
                quality = qualityHint,
                edgePhase = edgePhaseHint,
                candidateConfidence = candidateConfidenceHint,
            )
        } catch (_: Throwable) { null }
        if (oracle6915 != null) {
            oracleReads6915.incrementAndGet()
            when (oracle6915.verdict) {
                PredictiveEntryOracle6915.Verdict.REFUSE -> oracleRefuse6915.incrementAndGet()
                PredictiveEntryOracle6915.Verdict.ADMIT -> oracleAdmit6915.incrementAndGet()
            }
        }
        // Confidence 0..1 -> an effective sample count on the same scale the
        // maturity constants use (MATURITY_MIN_N=8, DUMP_STRONG_MIN_N=10).
        // 1.0 confidence maps to 16 so a fully-evidenced candidate clears every
        // existing gate; 0.45 (the oracle's own refuse floor) maps to ~7, just
        // under MATURITY_MIN_N, so a thin estimate still cannot deny.
        val oracleEffectiveN6915 = ((oracle6915?.confidence ?: 0.0) * 16.0).toInt().coerceIn(0, 16)
        val cohortSample = if (useAgg6911) {
            agg6911!!.samples.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        } else {
            (fwd?.samples ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
        // V5.0.7207 — the lane's real terminal count, taken from the SAME
        // laneSnap already resolved at the top of this function for laneWrPct.
        // Deliberately not a second laneSnapshots() call: the count and the win
        // rate reaching 6846 must describe one snapshot, or a gate can refuse
        // on a WR from one sample and a size from another. It is also the same
        // authority the oracle blends as its "lane" level
        // (PredictiveEntryOracle6915:689-693), so the oracle's evidence and the
        // admission gate's view of that evidence cannot drift apart.
        //
        // Absent snapshot -> 0 -> thin -> probe, which is exactly the pre-7207
        // behaviour, so a failure here can only ever be the permissive
        // direction and never a new refusal.
        // V5.0.7287 — the lane's true count is the whole journal's, when that
        // is larger than the session snapshot (OracleTradeHistory7287).
        val laneRawTerminalN7207 = maxOf(
            laneSnap?.sample?.coerceAtLeast(0) ?: 0,
            try { OracleTradeHistory7287.lane(laneKey)?.n ?: 0 } catch (_: Throwable) { 0 },
        )

        return LearnedAdmissionAuthority6846.Inputs(
            lane = laneKey,
            mint = mint,
            requestedSizeSol = requestedSizeSol.coerceAtLeast(0.0),
            scoreBand = entryScore.coerceAtLeast(0),
            regime = regime,
            // V5.0.6915 — the oracle's blended estimate is preferred over any
            // single cell. It is always present once anything has closed, which
            // is what ends the "pWin=0.65 EV=0.0 hardcoded prior" state.
            livePWin = oracle6915?.pWin?.takeIf { it in 0.0..1.0 }
                ?: if (useAgg6911) agg6911!!.pWin.coerceIn(0.0, 1.0)
                else (fwd?.pWin ?: 0.0).coerceIn(0.0, 1.0),
            expectedPnl = oracle6915?.expectancyPct?.takeIf { it.isFinite() }?.div(100.0)
                ?: if (useAgg6911) (agg6911!!.expectedPnlPct / 100.0)
                else expectedPnlFraction,
            // Maturity now means "the hierarchy carries enough weight", not
            // "this exact cell has 8 closes". No threshold in 6846 changed.
            cohortSample = maxOf(cohortSample, oracleEffectiveN6915),
            // V5.0.7154 — carry the TRUE terminal count alongside the
            // weight-inflated one. The line above is deliberate and stays,
            // but it made the two indistinguishable downstream, and the
            // ORACLE refusal in 6846 needs the real count. See that branch.
            oracleRawCohortN7154 = cohortSample,
            // V5.0.7207 — the LANE's true terminal count, read from the same
            // LiveProbabilityEngine.laneSnapshots() the oracle itself blends
            // as its "lane" level (PredictiveEntryOracle6915:689-693). Read
            // here rather than threaded through Forecast so the oracle's
            // public shape is untouched and the two cannot drift: both sides
            // take sn.sample from one call on one authority.
            //
            // cohortSample above is the SCORE-BAND CELL count, and on the
            // 5.0.7206 device it read 0 against a lane that had closed ten
            // trades. 6846's ORACLE branch treated that 0 as "no evidence"
            // and converted 4,088 of 4,042 refusals into probes. See that
            // branch for the full reasoning; this line is the input it needed.
            oracleRawLaneN7207 = laneRawTerminalN7207,
            oracleVerdict6915 = oracle6915?.verdict,
            oracleHardSafety7287 = oracle6915?.hardSafety7287 == true,
            oracleEvidencedRefuse7340 = oracle6915?.reason == "NEGATIVE_EXPECTANCY_WITH_EVIDENCE_6915",
            laneWrPct = laneWrPct,
            laneLossRatePct = laneLossRatePct,
            // V5.0.6915 — §5 source-family adaptation is no longer inert. The
            // scorecard has tracked this since V5.0.4287 and had no read
            // accessor; expectancyFor6915 supplies one.
            sourceFamily = sourceFamilyHint,
            sourcePWin = (srcExp6915?.winRatePct ?: 0.0) / 100.0,
            sourceExpectedPnl = (srcExp6915?.meanPnlPct ?: 0.0) / 100.0,
            sourceSample = srcExp6915?.closed ?: 0,
            policyHardBlock = false,
            brainSoftBlock = false,
            losingPatternMatch = false,
            laneCapitalUsedSol = 0.0,
            laneCapitalTargetSol = 0.0,
            laneCapitalReservedSol = 0.0,
            minExecutableSol = minExecutableSol.coerceAtLeast(0.0),
            probeSizeSol = probeSizeSol.coerceAtLeast(0.0),
        )
    }

    /**
     * Convenience for the admission call sites: build the inputs and put them
     * through the learned overload of the entry authority. A signal failure
     * is non-executable: missing prediction is not positive prediction.
     */
    fun gate(
        lane: String,
        mint: String,
        requestedSizeSol: Double,
        entryScore: Int,
        minExecutableSol: Double,
        probeSizeSol: Double,
        // V5.0.6915 — forwarded to the oracle for the source-expectancy read.
        sourceFamilyHint: String = "",
        qualityHint: String = "",
        edgePhaseHint: String = "",
        candidateConfidenceHint: Double = 0.50,
    ): ExecutableEntryAuthority6450.Decision {
        return try {
            val inputs = build(
                lane, mint, requestedSizeSol, entryScore, minExecutableSol, probeSizeSol,
                sourceFamilyHint, qualityHint, edgePhaseHint, candidateConfidenceHint,
            )
            val decision = ExecutableEntryAuthority6450.gate(inputs)
            if (decision.verdict != ExecutableEntryAuthority6450.Verdict.ALLOW) {
                try {
                    PipelineHealthCollector.labelInc("LEARNED_ADMISSION_NON_ALLOW_6909")
                    PipelineHealthCollector.labelInc("LEARNED_ADMISSION_NON_ALLOW_6909_${decision.verdict.name}")
                    ForensicLogger.lifecycle(
                        "LEARNED_ADMISSION_6909",
                        "lane=${lane.uppercase()} mint=${mint.take(10)} score=$entryScore " +
                            "verdict=${decision.verdict.name} size=${decision.recommendedSizeSol} reason=${decision.reason}",
                    )
                } catch (_: Throwable) {}
            }
            decision
        } catch (_: Throwable) {
            try { PipelineHealthCollector.labelInc("LEARNED_ADMISSION_ASSEMBLY_FAILED_6909") } catch (_: Throwable) {}
            ExecutableEntryAuthority6450.Decision(
                ExecutableEntryAuthority6450.Verdict.DENY_LEARNED_NEGATIVE_6846,
                0.0,
                "ORACLE_ASSEMBLY_UNAVAILABLE_7260",
            )
        }
    }

    fun statusLine(): String =
        "assembled=${assembled.get()} forecastBootstrapOrMissing=${forecastMissing.get()} " +
            "forecastResolved=${forecastResolved.get()} " +
            "aggUsed6911=${aggregateUsed6911.get()} matureCohorts6911=${matureCohorts6911.get()} " +
            "oracle6915[reads=${oracleReads6915.get()} admit=${oracleAdmit6915.get()} " +
            "refuse=${oracleRefuse6915.get()}]"
}
