package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.RuntimeModeAuthority
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P1 — EXECUTABLE ENTRY AUTHORITY.
 *
 * OPERATOR MANDATE:
 *   consecutiveLosses=9, cooldownRemaining≈1753s, LANE_EVAL blocks=1817,
 *   yet hundreds of BUYs continue through other paths.
 *
 *   "Create ONE executable-entry authority immediately before capital
 *    reservation. Every executable route must pass it:
 *      QUALITY, BLUECHIP, MOONSHOT, SHITCOIN, PRESALE, COPYTRADE,
 *      WHALE_FOLLOW, EXPRESS, PROJECT_SNIPER, etc.
 *    No pid/source/lane alias bypasses.
 *
 *    Prefer de-risking bad regimes (minimum/probe sizing, stronger
 *    confirmation, cohort-specific floor shaping) rather than globally
 *    choking unrelated positive-EV cohorts."
 *
 * DESIGN
 * ──────
 * `gate(lane, mint, requestedSizeSol)` returns a Verdict:
 *   ALLOW                — proceed at requested size
 *   ALLOW_PROBE          — proceed at PROBE size (minimum)
 *   DENY_LOSING_STREAK   — global streak, no override
 *   DENY_COOLDOWN        — cohort cooldown active
 *   DENY_DAILY_LOSS_CAP  — daily loss budget exhausted
 * Loss streak is derived from CanonicalTradeFinalizedBus6450 outcomes
 * (unique positionIds).
 */
object ExecutableEntryAuthority6450 {

    enum class Verdict { ALLOW, ALLOW_PROBE, DENY_LOSING_STREAK, DENY_COOLDOWN, DENY_DAILY_LOSS_CAP }

    data class Decision(val verdict: Verdict, val recommendedSizeSol: Double, val reason: String)

    private const val PROBE_SIZE_SOL = 0.01
    private const val STREAK_HARD_LIMIT = 3
    private const val STREAK_TIGHTEN_ONE = 1
    private const val STREAK_TIGHTEN_TWO = 2
    private const val DAILY_LOSS_CAP_SOL = 1.5

    // V5.0.6488 — streak state is event-local by mode + lane. A paper
    // SHITCOIN loss must never suppress a live BLUECHIP entry. These maps are
    // bounded by the finite lane/mode universe and rebuilt from canonical replay.
    private val cohortLosses = ConcurrentHashMap<String, AtomicLong>()
    private val cohortLastLossMs = ConcurrentHashMap<String, Long>()
    private val cohortCooldownMs = ConcurrentHashMap<String, Long>()
    private val gates = AtomicLong(0L)
    private val allows = AtomicLong(0L)
    private val probes = AtomicLong(0L)
    private val denies = AtomicLong(0L)
    private val bypassAttempts = AtomicLong(0L)
    // V5.0.6805 §HARD_VETO_IS_A_COHORT_TRANSITION — hard-veto telemetry is
    // a cohort transition (first-veto-after-entering-hard-limit), not a
    // per-candidate event. Keeps counters causal and bounded. Cleared on
    // WIN so a recovered lane can re-arm cleanly on the next breach.
    private val lossStreakVetoLatched6805 = ConcurrentHashMap<String, Boolean>()

    private fun normalizedMode(raw: String?): String = when {
        raw.equals("live", true) -> "LIVE"
        raw.equals("paper", true) -> "PAPER"
        RuntimeModeAuthority.isLive() -> "LIVE"
        else -> "PAPER"
    }

    private fun normalizedLane(raw: String?): String = raw?.trim()?.uppercase()
        ?.takeIf { it.isNotBlank() } ?: "UNKNOWN"

    private fun cohortKey(mode: String?, lane: String?): String =
        "${normalizedMode(mode)}|${normalizedLane(lane)}"

    private fun currentMode(): String = if (RuntimeModeAuthority.isLive()) "LIVE" else "PAPER"

    init {
        try {
            CanonicalTradeFinalizedBus6450.subscribe { e ->
                val key = cohortKey(e.mode, e.entryLane)
                when (e.outcome) {
                    CanonicalTradeFinalizedBus6450.Outcome.LOSS -> {
                        val streakAfterLoss6805 = cohortLosses.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
                        cohortLastLossMs[key] = e.settledAtMs
                        // V5.0.6805 §COOLDOWN_ONLY_AT_CREED_BREACH — an ordinary
                        //   loss #1/#2 may shape risk but MUST NOT arm the
                        //   hard-veto cooldown. Cooldown arms only when the
                        //   canonical mode×lane streak actually breaches the
                        //   3-loss creed. Previously every loss re-armed a
                        //   60s cooldown, so a single loss could hold the
                        //   veto surface alive indefinitely via cooling=true.
                        if (streakAfterLoss6805 >= STREAK_HARD_LIMIT) {
                            cohortCooldownMs[key] = maxOf(cohortCooldownMs[key] ?: 0L, e.settledAtMs + 60_000L)
                        }
                    }
                    CanonicalTradeFinalizedBus6450.Outcome.WIN -> {
                        cohortLosses.computeIfAbsent(key) { AtomicLong(0L) }.set(0L)
                        // V5.0.6805 §WIN_CLEARS_ALL_STREAK_STATE — a canonical
                        //   WIN must clear cohortCooldownMs and the veto latch
                        //   too, otherwise a recovered cohort remains under
                        //   cooldown until natural expiry and cannot re-arm
                        //   the latch on a future genuine breach.
                        cohortCooldownMs.remove(key)
                        lossStreakVetoLatched6805.remove(key)
                    }
                    CanonicalTradeFinalizedBus6450.Outcome.BREAKEVEN -> Unit
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.6488: learned streaks soft-shape only. True hard safety remains in
     * rug/raw-floor/route/finality authorities; strategy history cannot emit a
     * zero-size or cross-lane shutdown.
     *
     * V5.0.6801 §LEARNING_MUST_CONTROL_ADMISSION — operator diagnosis Feb 2026:
     *   "Bad learned signals are still allowed to become BUYs. Entry authority
     *    gates=3162 allows=3162 denies=0. The system correctly identifies
     *    EXPRESS 5.3% WR, SHITCOIN 0% WR, PROJECT_SNIPER 13.1% WR and then
     *    simply reduces their sizing while continuing to feed them trades.
     *    Route toxic cohorts to SHADOW_ONLY except reproof probes."
     *
     *   The self-learning stack was intentionally soft-shape-only, but the
     *   operator's rebuttal is that the system now describes how badly it
     *   trades better than it stops taking those trades. Add a hard-block
     *   surface driven by CausalFeedbackAuthority6715.cohortLoserAdvisoryForLane
     *   (LANE-level, band-agnostic; the band-level 6727 TERMINAL suppressor
     *   already exists but only fires with band context which the caller
     *   here does not always have). Reproof probes bypass so the system
     *   continues to learn / reprove without being locked out.
     *
     *   isReproofProbe6801 = true → PROBE_SIZE_SOL admission, no denial.
     *   otherwise a lane with cohortLoserAdvisoryForLane returning
     *   ADVISORY_MULT_FLOOR (chronic terminal loser) hard-denies.
     */
    @JvmOverloads
    fun gate(lane: String, mint: String, requestedSizeSol: Double, isReproofProbe6801: Boolean = false, discoverySource6801: String = ""): Decision {
        gates.incrementAndGet()
        val mode = currentMode()
        val key = cohortKey(mode, lane)
        val streak = cohortLosses[key]?.get() ?: 0L
        val cooling = (cohortCooldownMs[key] ?: 0L) > System.currentTimeMillis()

        // V5.0.6801 §LEARNING_MUST_CONTROL_ADMISSION — hard-block toxic lane
        // cohorts unless the caller is an explicit reproof probe. This does
        // NOT permanently disable a lane: reproof probes keep flowing so
        // recovery can be observed and the block auto-clears once WR
        // recovers past the advisory floor.
        val loserAdvisory6801 = try {
            com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715
                .cohortLoserAdvisoryForLane(mode, lane)
        } catch (_: Throwable) { null }
        val laneIsTerminalLoser6801 = loserAdvisory6801 != null &&
            loserAdvisory6801.sizeMultiplier <= 0.55 // ADVISORY_MULT_FLOOR=0.40 + shaping headroom; catches WR under ~7.5% with adequate sample

        // V5.0.6801 §SOURCE_AWARE_ADMISSION — parallel source-cohort veto.
        // A source whose settled outcomes are catastrophic (PUMP_PORTAL
        // flood in the operator diagnosis) is admission-blocked here even
        // if the LANE cohort is currently clean, and vice versa. Reproof
        // probes still get PROBE-size admission so recovery is observable.
        val sourceAdvisory6801 = try {
            if (discoverySource6801.isBlank()) null
            else com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715
                .sourceLoserAdvisory6801(mode, discoverySource6801)
        } catch (_: Throwable) { null }
        val sourceIsTerminalLoser6801 = sourceAdvisory6801 != null &&
            sourceAdvisory6801.sizeMultiplier <= 0.55

        if ((laneIsTerminalLoser6801 || sourceIsTerminalLoser6801) && !isReproofProbe6801) {
            denies.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_TOXIC_LANE_SHADOW_ONLY_6801")
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_TOXIC_LANE_SHADOW_ONLY_6801_${normalizedLane(lane)}")
                if (sourceIsTerminalLoser6801) {
                    PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_TOXIC_SOURCE_SHADOW_ONLY_6801")
                    PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_TOXIC_SOURCE_SHADOW_ONLY_6801_${sourceAdvisory6801!!.source}")
                }
                ForensicLogger.lifecycle(
                    "EXECUTABLE_ENTRY_TOXIC_LANE_SHADOW_ONLY_6801",
                    "mode=$mode lane=${normalizedLane(lane)} mint=${mint.take(10)} " +
                        "laneWr=${loserAdvisory6801?.worstWinRatePct?.let { "%.2f".format(it) } ?: "n/a"}% laneN=${loserAdvisory6801?.worstDecidedCount ?: 0} " +
                        "srcAdvisory=${sourceAdvisory6801?.source ?: "n/a"} srcWr=${sourceAdvisory6801?.winRatePct?.let { "%.2f".format(it) } ?: "n/a"}% srcN=${sourceAdvisory6801?.decidedCount ?: 0} " +
                        "action=hard_deny_admission_reproof_only",
                )
            } catch (_: Throwable) {}
            return Decision(
                Verdict.DENY_LOSING_STREAK,
                0.0,
                "mode=$mode lane=${normalizedLane(lane)} " +
                    "laneWr=${loserAdvisory6801?.worstWinRatePct?.let { "%.1f".format(it) } ?: "n/a"}% " +
                    "srcWr=${sourceAdvisory6801?.winRatePct?.let { "%.1f".format(it) } ?: "n/a"}% " +
                    "action=SHADOW_ONLY_REPROOF_REQUIRED_6801",
            )
        }
        if ((laneIsTerminalLoser6801 || sourceIsTerminalLoser6801) && isReproofProbe6801) {
            probes.incrementAndGet()
            val probeSize6801 = PROBE_SIZE_SOL.coerceAtMost(requestedSizeSol.coerceAtLeast(PROBE_SIZE_SOL))
            try {
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_TOXIC_LANE_REPROOF_PROBE_6801")
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_TOXIC_LANE_REPROOF_PROBE_6801_${normalizedLane(lane)}")
                ForensicLogger.lifecycle(
                    "EXECUTABLE_ENTRY_TOXIC_LANE_REPROOF_PROBE_6801",
                    "mode=$mode lane=${normalizedLane(lane)} mint=${mint.take(10)} " +
                        "laneWr=${loserAdvisory6801?.worstWinRatePct?.let { "%.2f".format(it) } ?: "n/a"}% " +
                        "srcAdvisory=${sourceAdvisory6801?.source ?: "n/a"} " +
                        "requestedSol=$requestedSizeSol probeSol=$probeSize6801 action=probe_only_no_normal_admission",
                )
            } catch (_: Throwable) {}
            return Decision(
                Verdict.ALLOW_PROBE,
                probeSize6801,
                "mode=$mode lane=${normalizedLane(lane)} " +
                    "laneWr=${loserAdvisory6801?.worstWinRatePct?.let { "%.1f".format(it) } ?: "n/a"}% " +
                    "srcWr=${sourceAdvisory6801?.winRatePct?.let { "%.1f".format(it) } ?: "n/a"}% action=REPROOF_PROBE_6801",
            )
        }

        // V5.0.6803 §LOSS_STREAK_HARD_CREED_ENFORCEMENT — operator diagnosis
        //   Feb 2026: "maxLossStreak=3 is currently more of a policy
        //   declaration than an effective risk invariant. Actual streaks
        //   reached 10." STREAK_HARD_LIMIT was firing but only shaped size
        //   to 0.35. Turn it into a real hard-deny (reproof probes still
        //   admitted): 3 consecutive confirmed losses on a lane×mode
        //   cohort now yields a cool-down deny window instead of merely
        //   shrinking size while continuing to feed the trader more losses.
        //   The existing cooling logic already tracks the STREAK_COOLDOWN_
        //   MS window; this simply upgrades hard-limit from a size shaper
        //   to a hard vetoer.
        // V5.0.6805 §COOLING_IS_RECOVERY_TIMING_ONLY — cooling is recovery-
        //   observation timing, not a hard-veto surface. A single loss cannot
        //   convert into a hard veto merely because the 60s cooldown window
        //   is still ticking. Only canonical consecutive losses trip veto.
        val streakBreached6803 = streak >= STREAK_HARD_LIMIT
        if (streakBreached6803 && !isReproofProbe6801) {
            denies.incrementAndGet()
            // V5.0.6805 §HARD_VETO_LATCH — first veto after entering hard-
            //   limit emits full telemetry; subsequent candidates within the
            //   same cohort transition emit a single cohort-veto counter so
            //   dashboards do not see thousands of per-candidate labels for
            //   a single 3-loss creed breach.
            val firstVetoForCohort6805 = lossStreakVetoLatched6805.putIfAbsent(key, true) == null
            if (firstVetoForCohort6805) try {
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_LOSS_STREAK_HARD_VETO_6803")
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_LOSS_STREAK_HARD_VETO_6803_${normalizedLane(lane)}")
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_LOSS_STREAK_COHORT_VETO_6805")
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_LOSS_STREAK_COHORT_VETO_6805_${normalizedLane(lane)}")
                ForensicLogger.lifecycle(
                    "EXECUTABLE_ENTRY_LOSS_STREAK_HARD_VETO_6803",
                    "mode=$mode lane=${normalizedLane(lane)} mint=${mint.take(10)} " +
                        "streak=$streak limit=$STREAK_HARD_LIMIT cooling=$cooling " +
                        "action=hard_deny_admission_reproof_only_cooldown_enforced",
                )
            } catch (_: Throwable) {} else try {
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_LOSS_STREAK_COHORT_VETO_LATCHED_6805")
            } catch (_: Throwable) {}
            return Decision(
                Verdict.DENY_LOSING_STREAK,
                0.0,
                "mode=$mode lane=${normalizedLane(lane)} streak=$streak limit=$STREAK_HARD_LIMIT cooling=$cooling action=LOSS_STREAK_HARD_VETO_6803",
            )
        }
        if (streakBreached6803 && isReproofProbe6801) {
            probes.incrementAndGet()
            val probeSize6803 = PROBE_SIZE_SOL.coerceAtMost(requestedSizeSol.coerceAtLeast(PROBE_SIZE_SOL))
            try {
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_LOSS_STREAK_REPROOF_PROBE_6803")
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_LOSS_STREAK_REPROOF_PROBE_6803_${normalizedLane(lane)}")
            } catch (_: Throwable) {}
            return Decision(
                Verdict.ALLOW_PROBE,
                probeSize6803,
                "mode=$mode lane=${normalizedLane(lane)} streak=$streak limit=$STREAK_HARD_LIMIT action=LOSS_STREAK_REPROOF_PROBE_6803",
            )
        }

        val mult = when {
            streak >= STREAK_TIGHTEN_TWO -> 0.35
            streak >= STREAK_TIGHTEN_ONE -> 0.65
            else -> 1.0
        }
        val shaped = (requestedSizeSol * mult).coerceAtLeast(0.0)
        allows.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(
                if (mult < 1.0) "EXECUTABLE_ENTRY_COHORT_SHAPED_6488" else "EXECUTABLE_ENTRY_COHORT_CLEAR_6488"
            )
            if (mult < 1.0) ForensicLogger.lifecycle(
                "EXECUTABLE_ENTRY_COHORT_SHAPED_6488",
                "mode=$mode lane=${normalizedLane(lane)} mint=${mint.take(10)} streak=$streak cooling=$cooling sizeMult=$mult",
            )
        } catch (_: Throwable) {}
        return Decision(
            Verdict.ALLOW,
            shaped,
            "mode=$mode lane=${normalizedLane(lane)} streak=$streak cooling=$cooling sizeMult=${"%.2f".format(mult)}",
        )
    }

    fun consecutiveLossesFor6488(lane: String, mode: String = currentMode()): Long =
        cohortLosses[cohortKey(mode, lane)]?.get() ?: 0L

    fun defensiveActiveFor6488(lane: String, mode: String = currentMode()): Boolean =
        consecutiveLossesFor6488(lane, mode) > 0L

    fun scoreFloorDeltaFor6488(lane: String, mode: String = currentMode()): Int = when {
        consecutiveLossesFor6488(lane, mode) >= STREAK_HARD_LIMIT -> 15
        consecutiveLossesFor6488(lane, mode) >= STREAK_TIGHTEN_TWO -> 15
        consecutiveLossesFor6488(lane, mode) >= STREAK_TIGHTEN_ONE -> 8
        else -> 0
    }

    fun sizeMultiplierFor6488(lane: String, mode: String = currentMode()): Double = when {
        consecutiveLossesFor6488(lane, mode) >= STREAK_HARD_LIMIT -> 0.35
        consecutiveLossesFor6488(lane, mode) >= STREAK_TIGHTEN_TWO -> 0.35
        consecutiveLossesFor6488(lane, mode) >= STREAK_TIGHTEN_ONE -> 0.65
        else -> 1.0
    }

    // Compatibility telemetry only. Global values must not be used for entry authority.
    fun consecutiveLossesNow6487(): Long = cohortLosses.values.maxOfOrNull { it.get() } ?: 0L
    fun defensiveActive6487(): Boolean = cohortLosses.values.any { it.get() > 0L }
    fun scoreFloorDelta6487(): Int = 0
    fun sizeMultiplier6487(): Double = 1.0

    /** Called if any caller bypasses the gate (should be zero). */
    fun recordBypass(lane: String, source: String) {
        bypassAttempts.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "EXECUTABLE_ENTRY_BYPASS_6450",
                "lane=$lane source=${source.take(40)}",
            )
            PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_BYPASS_6450")
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val hottest = cohortLosses.entries.maxByOrNull { it.value.get() }
        return "cohorts=${cohortLosses.size} hottest=${hottest?.key ?: "none"}:${hottest?.value?.get() ?: 0L} " +
            "gates=${gates.get()} allows=${allows.get()} probes=${probes.get()} denies=${denies.get()} bypass=${bypassAttempts.get()}"
    }

    internal fun resetForTest6487() {
        cohortLosses.clear(); cohortLastLossMs.clear(); cohortCooldownMs.clear(); lossStreakVetoLatched6805.clear()
        gates.set(0L); allows.set(0L); probes.set(0L); denies.set(0L); bypassAttempts.set(0L)
    }

    internal fun recordLossForTest6487(count: Int, lane: String = "TEST", mode: String = "PAPER") {
        cohortLosses[cohortKey(mode, lane)] = AtomicLong(count.coerceAtLeast(0).toLong())
        cohortLastLossMs.remove(cohortKey(mode, lane)); cohortCooldownMs.remove(cohortKey(mode, lane))
    }
}
