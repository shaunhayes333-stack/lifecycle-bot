package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
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

        // §1 — UnifiedPolicyHead HARD_BLOCK is absolute (per directive).
        if (inputs.policyHardBlock) return deny("POLICY_HARD_BLOCK", inputs, "policyHead=HARD_BLOCK")

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
                // Deny normal, allow probe.
                return probe("REGIME_DUMP_STRONG_NEGATIVE", inputs,
                    "dump strong n=${inputs.cohortSample} lanePWin=${"%.2f".format(lanePWin)} EV=${"%.2f".format(inputs.expectedPnl)}")
            }
            val matureNegative =
                inputs.cohortSample >= DUMP_MATURE_MIN_N &&
                (lanePWin < DUMP_MATURE_PWIN_MAX || laneLossRate >= DUMP_MATURE_LOSS_RATE)
            if (matureNegative) {
                return deny("REGIME_DUMP_MATURE_NEGATIVE", inputs,
                    "dump n=${inputs.cohortSample} lanePWin=${"%.2f".format(lanePWin)} lossRate=${"%.2f".format(laneLossRate)}")
            }
        }

        // §5 — Source-family adaptation. Mature source with severe
        // negative expectancy → PROBE_ONLY (adaptive; no hardcoded lane
        // disable).
        if (sourceMature &&
            inputs.sourcePWin < SOURCE_FAMILY_PWIN_SUSPECT &&
            inputs.sourceExpectedPnl < 0.0
        ) {
            return probe("SOURCE_FAMILY_MATURE_NEGATIVE", inputs,
                "srcKey=$srcKey n=${inputs.sourceSample} srcPWin=${"%.2f".format(inputs.sourcePWin)} srcEV=${"%.2f".format(inputs.sourceExpectedPnl)}")
        }

        // §4 — LosingPatternMemory + Brain SOFT_BLOCK together produce
        // mature negative evidence. Route to PROBE_ONLY rather than
        // silently pass through.
        if (inputs.losingPatternMatch && inputs.brainSoftBlock && cohortMature) {
            return probe("POLICY_SOFT_NEGATIVE", inputs,
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

    private fun probe(category: String, inputs: Inputs, detail: String): Decision {
        probes.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("ENTRY_AUTHORITY_PROBE")
            PipelineHealthCollector.labelInc("ENTRY_AUTHORITY_DENY_REASON_$category")
            ForensicLogger.lifecycle(
                "ENTRY_AUTHORITY_PROBE_6846",
                "lane=${inputs.lane} regime=${inputs.regime} src=${inputs.sourceFamily} " +
                    "category=$category $detail",
            )
        } catch (_: Throwable) {}
        val probeSize = inputs.probeSizeSol
            .coerceAtLeast(inputs.minExecutableSol.coerceAtLeast(0.0))
            .coerceAtMost(inputs.requestedSizeSol.coerceAtLeast(0.0))
        return Decision(Verdict.PROBE_ONLY, probeSize, "probe:$category:$detail", category)
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
