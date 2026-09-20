package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7102 §A_LEARNER_THAT_STOPPED_DISCRIMINATING.
 *
 * THE EVENT THIS EXISTS FOR, from the operator's 5.0.7088 device snapshot:
 *
 *     Predictive oracle (§6915): evals=894 admit=0 probe=251 refuse=643
 *
 * Eight hundred and ninety-four evaluations and not one admit. That is not a
 * verdict about 894 candidates; it is a verdict about the oracle. It had
 * trained on entry prices that V5.0.7089 later proved were fabricated by a 1e9
 * supply constant, learned that everything loses, and collapsed into a constant
 * function. A gate that returns the same answer to every question has stopped
 * being a gate.
 *
 * NOTHING IN THE STACK NOTICED. The numbers were in a status line, correct and
 * complete, waiting for a human to read a ratio and realise what it meant. The
 * oracle recovered on 5.0.7091 (admit=378 refuse=0) only because the upstream
 * data defect was fixed and fresh samples outvoted the poisoned ones. That is
 * luck plus sample volume, not self-awareness — and the same collapse in a
 * lower-traffic authority would simply persist.
 *
 * So: a learned authority is watched for the one failure that its own counters
 * can prove and its own logic cannot see — its verdict distribution collapsing
 * onto a single outcome over a large sample.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO.
 *
 * It does not change a verdict, override an authority, relax a threshold or
 * re-enable anything. Detection only. Two reasons, and both are load-bearing:
 *
 *   · A collapse can be CORRECT. A lane fed nothing but genuine losers should
 *     refuse everything, and a watchdog that "corrects" that would be inventing
 *     admissions out of a statistic. The signal says "look at this", not "this
 *     is wrong".
 *   · Auto-recovery is the operator's call. V5.0.7091 said it in as many words
 *     — "nothing can un-teach what is already persisted... that is an operator
 *     decision, not mine to take" — and that is still true. What was missing
 *     was not the authority to act. It was being told at all.
 *
 * A degenerate distribution is therefore reported as a fault ABOUT THE LEARNER,
 * next to the faults about the market, and the operator decides.
 */
object LearnedPolicyDegeneracyWatch7102 {

    /**
     * Below this many observations a lopsided distribution is ordinary small-
     * sample noise, not a collapse. The oracle reached 894 in one session; a
     * quieter authority simply stays unjudged until it has spoken enough times,
     * which is the correct answer for it too.
     */
    private const val MIN_SAMPLE_7102 = 200L

    /**
     * Fraction of one verdict at which the authority has stopped discriminating.
     * Set at 0.99 rather than 1.0 on purpose: the failure being caught is a
     * policy that has effectively collapsed, and one stray admit in a thousand
     * refusals is the same collapse with a rounding error in it.
     */
    private const val DEGENERATE_FRACTION_7102 = 0.99

    /**
     * V5.0.7158 — warning tier. A learner is not dead at this share, but it
     * is close enough to a constant that its output carries little
     * information, and the gap between this and 0.99 is where a collapsing
     * learner spends its whole decline unnoticed. Report-only.
     */
    private const val SKEWED_FRACTION_7158 = 0.85

    /**
     * Re-report interval. A collapse that persists is still news an hour later,
     * but not every evaluation — this is a health signal, not a log storm.
     */
    private const val REPORT_INTERVAL_MS_7102 = 900_000L

    private class Tally7102 {
        val counts = ConcurrentHashMap<String, AtomicLong>()
        val total = AtomicLong(0L)
        @Volatile var lastReportAtMs = 0L
        @Volatile var lastReportedVerdict = ""
    }

    private val tallies = ConcurrentHashMap<String, Tally7102>()
    private val reports = AtomicLong(0L)

    /**
     * Record one verdict from a learned authority.
     *
     * @param authority the learner's name as the operator report spells it, so a
     *   fault and the status line it came from can be matched by eye.
     * @param verdict the decision it reached — ADMIT / REFUSE / PROBE, or
     *   whatever vocabulary that authority actually uses. This object does not
     *   interpret the labels; collapse onto ANY single label is the signal.
     */
    fun observe7102(authority: String, verdict: String) {
        if (authority.isBlank() || verdict.isBlank()) return
        try {
            val t = tallies.getOrPut(authority) { Tally7102() }
            t.counts.getOrPut(verdict.uppercase()) { AtomicLong(0L) }.incrementAndGet()
            val total = t.total.incrementAndGet()
            if (total < MIN_SAMPLE_7102) return

            val dominant = t.counts.entries.maxByOrNull { it.value.get() } ?: return
            val share = dominant.value.get().toDouble() / total.toDouble()
            if (share < DEGENERATE_FRACTION_7102) {
                // Recovered, or never collapsed. Clear the latch so a later
                // collapse reports immediately rather than waiting out an
                // interval it did not earn.
                t.lastReportedVerdict = ""
                return
            }

            val now = System.currentTimeMillis()
            val sameAsLast = dominant.key == t.lastReportedVerdict
            if (sameAsLast && now - t.lastReportAtMs < REPORT_INTERVAL_MS_7102) return
            t.lastReportAtMs = now
            t.lastReportedVerdict = dominant.key
            reports.incrementAndGet()

            PipelineHealthCollector.labelInc("LEARNED_POLICY_DEGENERATE_7102")
            PipelineHealthCollector.labelInc(
                "LEARNED_POLICY_DEGENERATE_7102_${authority.uppercase()}_${dominant.key}".take(60)
            )
            ForensicLogger.lifecycle(
                "LEARNED_POLICY_DEGENERATE_7102",
                "authority=$authority verdict=${dominant.key} " +
                    "share=${"%.4f".format(share)} n=$total " +
                    "distribution=${distributionOf7102(t)} " +
                    "action=learner_has_stopped_discriminating_this_is_a_fault_about_the_learner_not_the_market",
            )
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.7120 — is [authority] currently collapsed onto one verdict?
     *
     * V5.0.7102 built this watch as detection only and said so: "changes no
     * verdict". That was the right first step and the wrong resting place. The
     * operator's 5.0.7117 device:
     *
     *     evals=1784 admit=0 probe=0 refuse=1783
     *     PredictiveEntryOracle6915:n=1783 top=REFUSE@1.00:DEGENERATE
     *     ORACLE_PROBE_BUDGET_6915 = 152 blocks
     *
     * — a learner the app has already diagnosed as collapsed, still spending
     * executable throughput. Operator: "When 7102 declares an inference
     * component DEGENERATE, it should automatically fall back to
     * advisory/neutral weight, not continue contributing authoritative REFUSE
     * decisions... Don't remove the oracle. Fail it open to neutral while it
     * rehydrates/retrains."
     *
     * Same threshold as the report, read from the same tally, so the status
     * line and the behaviour can never disagree — an authority that PRINTS
     * DEGENERATE is an authority that IS demoted, with no second definition to
     * drift. Below MIN_SAMPLE_7102 this is false: too little evidence to judge
     * is not the same as healthy, but it is also not grounds to demote.
     */
    fun isDegenerate7102(authority: String): Boolean {
        if (authority.isBlank()) return false
        return try {
            val t = tallies[authority] ?: return false
            val total = t.total.get()
            if (total < MIN_SAMPLE_7102) return false
            val dominant = t.counts.entries.maxByOrNull { it.value.get() } ?: return false
            dominant.value.get().toDouble() / total.toDouble() >= DEGENERATE_FRACTION_7102
        } catch (_: Throwable) {
            false
        }
    }

    private fun distributionOf7102(t: Tally7102): String =
        t.counts.entries
            .sortedByDescending { it.value.get() }
            .joinToString(",") { "${it.key}=${it.value.get()}" }

    /**
     * One line per watched authority for the operator report. An authority with
     * too few observations to judge says so rather than reporting a reassuring
     * zero — "not enough evidence" and "healthy" are different facts and this
     * codebase has been bitten before by a counter that confused them.
     */
    fun statusLine7102(): String {
        if (tallies.isEmpty()) return "watched=0 reports=0"
        val parts = tallies.entries.sortedBy { it.key }.map { (name, t) ->
            val total = t.total.get()
            if (total < MIN_SAMPLE_7102) return@map "$name:n=$total(below_min_${MIN_SAMPLE_7102})"
            val dominant = t.counts.entries.maxByOrNull { it.value.get() }
            val share = if (dominant == null || total == 0L) 0.0
            else dominant.value.get().toDouble() / total.toDouble()
            // V5.0.7158 §0.99 ONLY CATCHES A LEARNER THAT HAS ALREADY DIED.
            //
            // Operator's 5.0.7155:
            //   PredictiveEntryOracle6915: n=930 top=REFUSE@0.86:ok
            //
            // Reported as fine. The oracle emits three verdicts — ADMIT,
            // PROBE, REFUSE — so uniform is 0.33, and it said REFUSE 86% of
            // the time across 930 evaluations. In the 5.0.7145 session the
            // same line read REFUSE@0.62, so it is not stable either: it is
            // drifting toward a constant, which is precisely what this
            // watcher exists to notice, and 0.99 will not notice it until
            // there is nothing left to save.
            //
            // A discriminator that agrees with itself 86% of the time is
            // barely discriminating. It is not yet provably dead, so calling
            // it DEGENERATE would overstate; SKEWED says what is true and
            // puts the number where the operator reads it. The hard 0.99
            // threshold is unchanged, and this watcher gates nothing — it is
            // report-only, so this widens what is seen, not what is done.
            val flag = when {
                share >= DEGENERATE_FRACTION_7102 -> "DEGENERATE"
                share >= SKEWED_FRACTION_7158 -> "SKEWED"
                else -> "ok"
            }
            "$name:n=$total top=${dominant?.key ?: "NONE"}@${"%.2f".format(share)}:$flag"
        }
        return "watched=${tallies.size} reports=${reports.get()} ${parts.joinToString(" ")}"
    }

    internal fun resetForTest7102() { tallies.clear(); reports.set(0L) }
}
