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

    enum class Verdict { ALLOW, ALLOW_PROBE, DENY_LOSING_STREAK, DENY_COOLDOWN, DENY_DAILY_LOSS_CAP, DENY_LEARNED_NEGATIVE_6846 }

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
                        cohortLosses.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
                        cohortLastLossMs[key] = e.settledAtMs
                        cohortCooldownMs[key] = e.settledAtMs + 60_000L
                    }
                    CanonicalTradeFinalizedBus6450.Outcome.WIN ->
                        cohortLosses.computeIfAbsent(key) { AtomicLong(0L) }.set(0L)
                    CanonicalTradeFinalizedBus6450.Outcome.BREAKEVEN -> Unit
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.6488: learned streaks soft-shape only. True hard safety remains in
     * rug/raw-floor/route/finality authorities; strategy history cannot emit a
     * zero-size or cross-lane shutdown.
     */
    fun gate(lane: String, mint: String, requestedSizeSol: Double): Decision {
        gates.incrementAndGet()
        val mode = currentMode()
        val key = cohortKey(mode, lane)
        // V5.0.7035 §THE_ONE_LEARNER_THAT_REALLY_DID_RESET_ON_THE_FLIP.
        //
        // This read was `cohortLosses[key]?.get() ?: 0L` with no cross-mode
        // fallback, so the flip to live started every lane at zero losses, no
        // cooldown and a neutral 1.00 multiplier — however hard that lane had
        // been bleeding in paper, and at the exact moment the money became
        // real.
        //
        // ColdStreakDamper (6991) and ForwardOutcomeModel (6869/6991) were both
        // repaired for this and this one was missed, because the parity census
        // that was supposed to find it reports every learner as resetting in a
        // paper-only session — see PaperLiveParityCreed6439.resetsOnFlip, fixed
        // in the same build.
        //
        // A loss streak and a cooldown are PROTECTIVE evidence and simulation
        // understates live costs, so they transfer at full strength through the
        // same PaperSeededPrior6991 the other two use: live opens guarded and
        // the damper is already trimming on the first real trade in a lane
        // paper knows is bad. Live losses accumulate into the live cohort
        // directly, so maxOf hands authority back to live the moment it has
        // its own view.
        val ownStreak7035 = cohortLosses[key]?.get() ?: 0L
        val ownCooldown7035 = cohortCooldownMs[key] ?: 0L
        val streak: Long
        val coolUntil7035: Long
        if (mode == "LIVE") {
            val paperKey7035 = cohortKey("PAPER", lane)
            val paperStreak7035 = cohortLosses[paperKey7035]?.get() ?: 0L
            val seeded7035 = PaperSeededPrior6991.seedProtective(paperStreak7035, ownStreak7035)
            if (seeded7035 > ownStreak7035) {
                try {
                    PaperSeededPrior6991.noteProtectiveSeed(
                        "ExecutableEntryAuthority6450.lossStreak[${normalizedLane(lane)}]",
                        paperStreak7035, ownStreak7035,
                    )
                } catch (_: Throwable) {}
            }
            streak = seeded7035
            // The cooldown is a timestamp, not a magnitude, so it is inherited
            // as-is rather than weighted: a lane paper stopped out sixty
            // seconds ago is still cooling when live picks it up.
            coolUntil7035 = maxOf(ownCooldown7035, cohortCooldownMs[paperKey7035] ?: 0L)
        } else {
            streak = ownStreak7035
            coolUntil7035 = ownCooldown7035
        }
        val cooling = coolUntil7035 > System.currentTimeMillis()
        val mult = when {
            streak >= STREAK_HARD_LIMIT || cooling -> 0.35
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

    /**
     * V5.0.6846 §MAKE_LEARNED_ENTRY_AUTHORITY_ACTUALLY_AUTHORITATIVE —
     * overload of gate() that consults LearnedAdmissionAuthority6846
     * with the caller-assembled Inputs (UnifiedPolicyHead / Brain /
     * LosingPatternMemory / ForwardOutcomeModel / RegimeDetector /
     * source-family + capital projections) BEFORE the historical streak
     * damping is applied.
     *
     * Contract:
     *   * ALLOW      -> returns the streak-shaped size (existing behaviour).
     *   * PROBE_ONLY -> returns Verdict.ALLOW_PROBE with the
     *                   LearnedAdmissionAuthority-recommended probe size.
     *   * DENY       -> returns Verdict.DENY_LEARNED_NEGATIVE_6846 with
     *                   size 0.0.  Callers MUST NOT fall back to a
     *                   duplicate ALLOW path elsewhere (operator §8: "no
     *                   pid/source/lane alias bypasses").
     *
     * Non-learned callers keep the existing 3-arg gate() and this
     * overload is opt-in; the existing test surface is unaffected.
     */
    fun gate(inputs: LearnedAdmissionAuthority6846.Inputs): Decision {
        val learned = try {
            LearnedAdmissionAuthority6846.evaluate(inputs)
        } catch (_: Throwable) {
            // Fail-open on learned-authority error — the historical
            // streak damping still runs below.  Never fail-closed here
            // because it would open a global choke, which the operator
            // §8 explicitly forbids.
            null
        }
        return when (learned?.verdict) {
            LearnedAdmissionAuthority6846.Verdict.DENY -> {
                gates.incrementAndGet()
                denies.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_DENY_LEARNED_6846")
                } catch (_: Throwable) {}
                Decision(
                    Verdict.DENY_LEARNED_NEGATIVE_6846,
                    0.0,
                    "learned6846:${learned.denyCategory}",
                )
            }
            LearnedAdmissionAuthority6846.Verdict.PROBE_ONLY -> {
                gates.incrementAndGet()
                probes.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_PROBE_LEARNED_6846")
                } catch (_: Throwable) {}
                Decision(
                    Verdict.ALLOW_PROBE,
                    learned.recommendedSizeSol,
                    "learned6846_probe:${learned.denyCategory}",
                )
            }
            else -> {
                // ALLOW (or learned-authority errored): fall through to
                // historical cohort-streak shaping.
                gate(inputs.lane, inputs.mint, inputs.requestedSizeSol)
            }
        }
    }

    /**
     * V5.0.6991 — live inherits paper's LOSS STREAK, at full strength.
     *
     * This used to read only its own mode's cohort, so a flip to live reported
     * zero consecutive losses for every lane no matter how badly paper had
     * bled. The defensive reflex, its score-floor delta and its size
     * multiplier all started neutral at the exact moment the money was real.
     *
     * A loss streak is PROTECTIVE evidence, and paper understates rather than
     * overstates how bad live will be — live adds slippage, partial fills and
     * failed routes that a simulation never charges. So it transfers whole,
     * per PaperSeededPrior6991: live takes the worse of the two views and
     * starts guarded. The opposite direction, a paper WIN streak, is not
     * seeded anywhere — that would size up real money on simulated wins.
     *
     * Once live has its own cohort the live value wins on its own merits,
     * because maxOf picks it as soon as it is the larger one, and live losses
     * accumulate into it directly.
     */
    fun consecutiveLossesFor6488(lane: String, mode: String = currentMode()): Long {
        val own = cohortLosses[cohortKey(mode, lane)]?.get() ?: 0L
        val isLive = try { RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        if (!isLive) return own
        val paper = cohortLosses[cohortKey("PAPER", lane)]?.get() ?: 0L
        if (paper <= own) return own
        val seeded = PaperSeededPrior6991.seedProtective(paper, own)
        if (seeded > own) {
            try {
                PaperSeededPrior6991.noteProtectiveSeed(
                    "ExecutableEntryAuthority6450.lossStreak[$lane]", paper, own,
                )
            } catch (_: Throwable) {}
        }
        return seeded
    }

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
    /**
     * V5.0.6988 — losing-streak cohorts held per MODE.
     *
     * cohortKey(mode, lane) prefixes every cohort with the runtime mode, so a
     * flip from paper to live resets every streak to zero: the defensive
     * reflex, its score-floor delta and its size multiplier all start again
     * from no history at exactly the moment real money is at risk.
     *
     * Returned as (paperCohorts, liveCohorts) for the parity verifier.
     * Read-only.
     */
    fun modeCensus6988(): Pair<Int, Int> {
        var paper = 0
        var live = 0
        try {
            for (k in cohortLosses.keys) {
                val head = k.substringBefore('|').uppercase()
                when {
                    head.startsWith("PAPER") -> paper++
                    head.startsWith("LIVE") -> live++
                }
            }
        } catch (_: Throwable) {}
        return paper to live
    }

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
        cohortLosses.clear(); cohortLastLossMs.clear(); cohortCooldownMs.clear()
        gates.set(0L); allows.set(0L); probes.set(0L); denies.set(0L); bypassAttempts.set(0L)
    }

    internal fun recordLossForTest6487(count: Int, lane: String = "TEST", mode: String = "PAPER") {
        cohortLosses[cohortKey(mode, lane)] = AtomicLong(count.coerceAtLeast(0).toLong())
        cohortLastLossMs.remove(cohortKey(mode, lane)); cohortCooldownMs.remove(cohortKey(mode, lane))
    }
}
