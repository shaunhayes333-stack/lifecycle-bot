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

    /**
     * Assemble admission inputs for (lane, mint) from the live learned
     * signals. Never throws; on any signal failure the corresponding field
     * falls back to a neutral value so 6846 fails OPEN, per its §8 directive
     * that no global choke may be introduced here.
     */
    fun build(
        lane: String,
        mint: String,
        requestedSizeSol: Double,
        entryScore: Int,
        minExecutableSol: Double,
        probeSizeSol: Double,
    ): LearnedAdmissionAuthority6846.Inputs {
        assembled.incrementAndGet()
        val laneKey = lane.trim().uppercase().ifBlank { "UNKNOWN" }
        val regime = try {
            com.lifecyclebot.engine.RegimeDetector.currentRegime().name
        } catch (_: Throwable) { "UNKNOWN" }

        // Forward outcome model: the cohort discriminator. quality/edgePhase
        // are not known at the admission boundary, so the fine key misses and
        // the model falls back to its coarse key — lane x scoreBand x regime —
        // which is exactly the granularity §2b needs and the granularity that
        // keeps PROJECT_SNIPER|S20 separate from PROJECT_SNIPER|S10.
        val fwd = try {
            com.lifecyclebot.engine.ForwardOutcomeModel.forecast(
                laneKey, entryScore.coerceAtLeast(0), "", regime, "",
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
        val cohortSample = if (useAgg6911) {
            agg6911!!.samples.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        } else {
            (fwd?.samples ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }

        return LearnedAdmissionAuthority6846.Inputs(
            lane = laneKey,
            mint = mint,
            requestedSizeSol = requestedSizeSol.coerceAtLeast(0.0),
            scoreBand = entryScore.coerceAtLeast(0),
            regime = regime,
            livePWin = if (useAgg6911) agg6911!!.pWin.coerceIn(0.0, 1.0)
                else (fwd?.pWin ?: 0.0).coerceIn(0.0, 1.0),
            expectedPnl = if (useAgg6911) (agg6911!!.expectedPnlPct / 100.0)
                else expectedPnlFraction,
            cohortSample = cohortSample,
            laneWrPct = laneWrPct,
            laneLossRatePct = laneLossRatePct,
            // Neutral — see "HONEST PARTIAL COVERAGE" above. sourceSample=0
            // keeps §5 inert rather than guessing a source expectancy.
            sourceFamily = "",
            sourcePWin = 0.0,
            sourceExpectedPnl = 0.0,
            sourceSample = 0,
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
     * degrades to the historical 3-arg gate rather than blocking.
     */
    fun gate(
        lane: String,
        mint: String,
        requestedSizeSol: Double,
        entryScore: Int,
        minExecutableSol: Double,
        probeSizeSol: Double,
    ): ExecutableEntryAuthority6450.Decision {
        return try {
            val inputs = build(lane, mint, requestedSizeSol, entryScore, minExecutableSol, probeSizeSol)
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
            ExecutableEntryAuthority6450.gate(lane, mint, requestedSizeSol)
        }
    }

    fun statusLine(): String =
        "assembled=${assembled.get()} forecastBootstrapOrMissing=${forecastMissing.get()} " +
            "forecastResolved=${forecastResolved.get()} " +
            "aggUsed6911=${aggregateUsed6911.get()} matureCohorts6911=${matureCohorts6911.get()}"
}
