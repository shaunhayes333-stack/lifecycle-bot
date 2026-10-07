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
                // V5.0.7807 — admission streaks learn from clean terminal truth only (Field Manual L357).
                if (!CanonicalTradeFinalizedBus6450.isCleanForLearning7807(e)) return@subscribe
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
        // Account-specific loss evidence cannot cross the simulation/live boundary.
        val streak = cohortLosses[key]?.get() ?: 0L
        val coolUntil7035 = cohortCooldownMs[key] ?: 0L
        val cooling = coolUntil7035 > System.currentTimeMillis()
        // V5.0.7181 §A_COUNTER_WAS_OUTVOTING_THE_BRAIN.
        //
        // Operator: "the whole thing is meant to be fluid adaptive predictive
        // and brain driven" — and the dominant term in entry sizing was
        // `if (streak >= 2) 0.35`.
        //
        // This ladder and LaneExpectancyDamper measure THE SAME THING: is this
        // lane currently bleeding. They are applied in different places —
        // the damper at Executor:12826 and SmartSizer:813, this at
        // ExecutableOpenGate — so neither knows the other ran, and the same
        // evidence is counted twice. From the operator's 5.0.7176 run:
        //
        //   PROJECT_SNIPER  damper 0.91 x streak 0.35 = 0.32x
        //   QUALITY         damper 0.59 x streak 0.35 = 0.21x
        //   MOONSHOT        damper 0.54 x streak 0.35 = 0.19x
        //   EXPRESS         damper 1.11 x streak 0.35 = 0.39x
        //
        // The EXPRESS line is the one that matters. That lane is the only
        // profitable meme lane in the book — 1W/2L at +258.6% per trade, and
        // its single winner (+871%) is most of the meme P&L. The learned organ
        // agrees: it returns 1.11, the highest weight of any lane, which is
        // why LaneCapitalFairness6732 hands EXPRESS the largest budget in the
        // book at targetSol=1.8901. And then this counter sizes its entries at
        // 0.35 because it had two losses.
        //
        // Two losses is not evidence in a lane that wins one trade in three
        // and pays 800% when it does. A positive-skew lane spends most of its
        // life on a losing streak; that is the shape, not a fault. Shrinking on
        // a raw loss count is structurally guaranteed to make the position
        // small exactly when the payoff arrives, which is the whole game.
        //
        // LaneExpectancyDamper already knows all of this. It is evidence
        // weighted (trades/(trades+3)), sample gated (MIN_TRADES=8), floored
        // (MIN_MULT=0.18) and explicitly runner aware — isRunnerLane plus
        // RUNNER_MEAN_PCT and WR_RUNNER_MIN_PCT let a low-win-rate lane with a
        // positive mean be BOOSTED rather than damped, which is exactly how
        // EXPRESS earned its 1.11. This ladder has none of that. It counts to
        // two and multiplies by a constant.
        //
        // So the counter hands lane-expectancy authority to the learner and
        // keeps only the job the learner cannot do: covering the cold start.
        // sizeMultiplier returns exactly 1.0 when it has no opinion, so that
        // is the handover test — no new accessor, no second source of truth.
        //
        //   * learner has an opinion -> this organ returns 1.0 and the damper's
        //     verdict stands alone, applied once, downstream.
        //   * learner is neutral (too few closes) -> the streak prior applies
        //     as before, so a brand-new bleeding lane is still protected.
        //
        // `cooling` is deliberately left as an override. That is a separate,
        // time-bounded protective authority (LosingStreakReflex 6439), not a
        // second reading of lane expectancy, and it expires on its own.
        //
        // Nothing is disabled, no lane is throttled, and no threshold moves.
        // One duplicated measurement is removed, and the organ that measures
        // it properly is the one left holding the vote.
        val learnedMult7181 = try {
            com.lifecyclebot.engine.LaneExpectancyDamper.sizeMultiplier(lane)
        } catch (_: Throwable) { 1.0 }
        val learnedHasOpinion7181 = learnedMult7181.isFinite() &&
            kotlin.math.abs(learnedMult7181 - 1.0) > 1e-6
        // V5.0.7719 — the cold-start prior is the same evidence-gated ladder
        // as sizeMultiplierFor6488: silent until the lane has a sample.
        val streakPrior7181 = sizeMultiplierFor6488(lane, mode)
        // V5.0.7807 — B9: one live loss no longer cuts a lane to 0.35x for 60 s
        // until it has LiveRiskPolicy7807.MIN_LIVE_SAMPLE_DEEP_SHRINK_7807 live
        // closes; below that the post-loss cool-down shrinks to 0.7x (Field Manual L357).
        val coolingMult7807 = if (mode == "LIVE" &&
            (try { com.lifecyclebot.engine.LaneExpectancyDamper.sameModeCloses7265(lane) } catch (_: Throwable) { 0 }) <
            LiveRiskPolicy7807.MIN_LIVE_SAMPLE_DEEP_SHRINK_7807
        ) LiveRiskPolicy7807.SHALLOW_LOSS_FLOOR_7807 else 0.35
        val mult = when {
            cooling -> coolingMult7807
            learnedHasOpinion7181 -> 1.0
            else -> streakPrior7181
        }
        if (learnedHasOpinion7181 && !cooling && streakPrior7181 < 1.0) {
            try {
                PipelineHealthCollector.labelInc("ENTRY_STREAK_DEFERRED_TO_LEARNER_7181")
                PipelineHealthCollector.labelInc(
                    "ENTRY_STREAK_DEFERRED_TO_LEARNER_7181_${normalizedLane(lane)}",
                )
                ForensicLogger.lifecycle(
                    "ENTRY_STREAK_DEFERRED_TO_LEARNER_7181",
                    "lane=${normalizedLane(lane)} streak=$streak " +
                        "streakPrior=${"%.2f".format(streakPrior7181)} " +
                        "learnedMult=${"%.2f".format(learnedMult7181)} " +
                        "action=lane_expectancy_is_the_learners_vote_counter_is_cold_start_only",
                )
            } catch (_: Throwable) {}
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
     *   * PROBE_ONLY -> returns DENY_LEARNED_NEGATIVE_6846. The observation
     *                   remains available to shadow/replay learning, but no
     *                   canonical PAPER/LIVE capital is spent.
     *   * DENY       -> returns Verdict.DENY_LEARNED_NEGATIVE_6846 with
     *                   size 0.0.  Callers MUST NOT fall back to a
     *                   duplicate ALLOW path elsewhere (operator §8: "no
     *                   pid/source/lane alias bypasses").
     *
     * Non-learned callers keep the existing 3-arg gate() and this
     * overload is opt-in; the existing test surface is unaffected.
     */
    fun gate(inputs: LearnedAdmissionAuthority6846.Inputs): Decision {
        // V5.0.7262 §PAPER_LEARNS,_LIVE_REQUIRES_CONVICTION.
        //
        // Operator 5.0.7261, paper mode, fresh book: "its not buying at
        // alllllll. zero nothing." The snapshot:
        //
        //   Predictive oracle: evals=2069 admit=0 probe=2069 noEvidence=2069
        //                      coldAdmit7261=0 coldProbe7261=2069
        //   Entry authority:   gates=2471 allows=460 probes=0 denies=2011
        //   ENTRY_AUTHORITY_DENY_REASON_ORACLE_PROBE_NON_EXECUTABLE_7259=2011
        //   EXEC=0  TRADEJRNL_REC=0  lifetime completed trades=0
        //
        // 7259 made an oracle PROBE non-executable in BOTH modes and 7260
        // made this authority fail closed on a PROBE_ONLY verdict. On a clean
        // book the oracle has no terminal evidence, so every candidate is a
        // PROBE, so nothing executes, so no terminal evidence is ever written,
        // so every candidate stays a PROBE. 7261's cold-start admit needs
        // score>=60 and confidence>=0.40; a cold V3 scorer on this device
        // produces 9-32 and 8-16%. The deadlock survived its own repair.
        //
        // This file's own doctrine (LearnedAdmissionAuthority6846 §2b):
        // "a small position is how the brain learns a bucket... NOTHING IS
        // DISABLED." And the standing operator rule: no global choke. PAPER
        // exists precisely so evidence can be bought without money. So:
        //
        //   PAPER — a learned PROBE_ONLY executes at probe size (the 7139
        //           fraction), metered by the existing cohort probe budget
        //           inside LearnedAdmissionAuthority6846. A learned-authority
        //           exception falls open to the historical streak gate, as it
        //           did before 7260. Paper learns.
        //   LIVE  — unchanged from 7259/7260: PROBE_ONLY and exceptions are
        //           shadow-only. Real money follows ADMIT only.
        // V5.0.7263 — operator: "the oracle is way way too strict to allow
        // any trading in paper or live... it also has to allow trading. not
        // probing." The 7262 paper/live split is withdrawn: in BOTH modes a
        // learned-authority exception falls open to the historical streak
        // gate (the pre-7260 contract — "never fail-closed here because it
        // would open a global choke"), and PROBE_ONLY executes at probe size.
        // PROBE_ONLY now only arises from EVIDENCE of a negative cohort
        // (LearnedAdmissionAuthority6846 §2/§2b/§4/§5); the oracle's own
        // verdict no longer produces it.
        val learned = try {
            LearnedAdmissionAuthority6846.evaluate(inputs)
        } catch (_: Throwable) {
            gates.incrementAndGet()
            denies.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("CANONICAL_HIGH_EV_AUTHORITY_UNAVAILABLE_7426")
                ForensicLogger.lifecycle(
                    "CANONICAL_HIGH_EV_AUTHORITY_UNAVAILABLE_7426",
                    "mode=" + currentMode() + " lane=" + normalizedLane(inputs.lane) + " mint=" + inputs.mint.take(10) + " action=shadow_replay_lab_only",
                )
            } catch (_: Throwable) {}
            return Decision(
                Verdict.DENY_LEARNED_NEGATIVE_6846,
                0.0,
                "learned_authority_unavailable_7426:shadow_replay_lab_only",
            )
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
                // V5.0.7426 — canonical PAPER and LIVE are deployment-quality
                // books. Exploration does not spend canonical capital.
                gates.incrementAndGet()
                probes.incrementAndGet()
                denies.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_PROBE_LEARNED_6846")
                    PipelineHealthCollector.labelInc("CANONICAL_HIGH_EV_PROBE_SHADOW_ONLY_7426")
                    ForensicLogger.lifecycle(
                        "CANONICAL_HIGH_EV_PROBE_SHADOW_ONLY_7426",
                        "mode=" + currentMode() + " lane=" + normalizedLane(inputs.lane) + " mint=" + inputs.mint.take(10) + " category=" + learned.denyCategory,
                    )
                } catch (_: Throwable) {}
                Decision(
                    Verdict.DENY_LEARNED_NEGATIVE_6846,
                    0.0,
                    "learned6846_probe_shadow_only_7426:" + learned.denyCategory,
                )
            }
            else -> {
                // Explicit learned ALLOW falls through to the independent
                // historical cohort-streak authority.
                gate(inputs.lane, inputs.mint, inputs.requestedSizeSol)
            }
        }
    }

    /**
     * V5.0.7846 — MODE-PURE ENTRY HISTORY.
     *
     * LIVE admission may only be shaped by canonical LIVE outcomes. PAPER loss
     * streaks remain useful to PAPER/replay learning but cannot suppress or size
     * real-money entries. This removes the old 6991 cross-mode protective seed.
     */
    fun consecutiveLossesFor6488(lane: String, mode: String = currentMode()): Long {
        val normalizedMode7846 = mode.trim().uppercase().ifBlank { currentMode() }
        return cohortLosses[cohortKey(normalizedMode7846, lane)]?.get() ?: 0L
    }

    fun defensiveActiveFor6488(lane: String, mode: String = currentMode()): Boolean =
        consecutiveLossesFor6488(lane, mode) > 0L

    /**
     * V5.0.7377 — when a streak may suppress a lane's WAIT candidates outright.
     * defensiveActiveFor6488 is "any loss": one loss (or a paper streak seeded
     * into live) sent every WAIT candidate in the lane to shadow, and a lane can
     * only clear its streak by winning, so it locked itself out (5.0.7376: 200
     * DEFENSIVE_WAIT_SHADOW_ONLY_6487 in 10 min, MOONSHOT streak=1). Suppression now
     * needs the hard limit and a lane that does not pay; below that the streak's
     * score-floor delta shapes entries instead.
     */
    fun defensiveSuppressWait7377(lane: String, mode: String = currentMode()): Boolean =
        consecutiveLossesFor6488(lane, mode) >= STREAK_HARD_LIMIT && !lanePaysEv7334(lane) &&
            laneHasEvidence7719(lane) // V5.0.7719 — no suppression without a sample either

    /**
     * V5.0.7334 — a streak on a lane whose measured expectancy is positive
     * (n>=10) is the lane's fat-tailed shape, not a cold spell. 5.0.7333:
     * SHITCOIN streak=3 raised its floor 72 -> 87 (939 times) while its
     * cohort read 5W/0L at +130% and its meta arm +23% over 18 closes.
     */
    // V5.0.7384 — with under 10 live closes the live snapshot says nothing, while the
    // streak it is weighed against is paper's (seedProtectiveLiveAware). Fall back to
    // the lane's combined history, as the oracle's break-even bar does (7380): the
    // journal (n>=20, mean>0), then the score-band tracker, which survives a clear.
    private fun lanePaysEv7334(lane: String): Boolean = try {
        val u = lane.uppercase()
        val live = try { RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        val liveSnap = com.lifecyclebot.engine.LiveProbabilityEngine.laneSnapshots()
            .firstOrNull { it.lane.equals(lane, true) && it.sample >= 10 }
        if (live) {
            // V5.0.7846 — LIVE cannot fall back to combined/paper-derived
            // OracleTradeHistory or ScoreExpectancyTracker evidence.
            liveSnap?.evPct?.let { it > 0.0 } ?: false
        } else {
            liveSnap?.evPct?.let { it > 0.0 }
                ?: OracleTradeHistory7287.lane(u)?.takeIf { it.n >= 20 }?.let { it.meanNetPct > 0.0 }
                ?: (com.lifecyclebot.engine.ScoreExpectancyTracker.laneStats7380(u)
                    ?.let { it.first >= 20 && it.third > 0.0 } == true)
        }
    } catch (_: Throwable) { false }

    // ─────────────────────────────────────────────────────────────────────
    // V5.0.7719 §A_LOSS_IS_NOT_A_LESSON_UNTIL_THERE_IS_A_SAMPLE.
    //
    // Operator, on 5.0.7716 (STREAK_SCORE_FLOOR_RAISED=638 after four live
    // closes): "waaaaay too soon. it shouldn't just tighten but explore or
    // pivot. but only after proving true entry, strategy, hold etc poor. not
    // every trade is ever going to be the same." The ladder above raised the
    // floor +8 after ONE loss and +15 after two, and cut size to 0.65x/0.35x
    // on the same counts, in a lane with no sample behind it.
    //
    // The streak now shapes only once the lane has a real sample
    // (STREAK_EVIDENCE_MIN_CLOSES_7719 decisive closes from the learners'
    // own tables) AND the streak is three or more; the shaping is milder
    // (+5/+10 on the floor, 0.75x/0.5x on size). Below the sample the
    // counter is silent and the pivot authority (TacticSwitcher: rotate
    // tactic on 8+ decisive closes) is what answers a bleed. The
    // LosingStreakReflex cool-down override is unchanged.
    // ─────────────────────────────────────────────────────────────────────
    const val STREAK_EVIDENCE_MIN_CLOSES_7719 = 10
    const val STREAK_SHAPE_ONE_7719 = 3
    const val STREAK_SHAPE_TWO_7719 = 5
    private val streakDeferredNoEvidence7719 = AtomicLong(0L)

    /** True once the lane has enough decisive closes for a streak to mean anything. */
    fun laneHasEvidence7719(lane: String): Boolean = try {
        val u = lane.uppercase()
        val live = try { RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        val liveN = com.lifecyclebot.engine.LiveProbabilityEngine.laneSnapshots()
            .firstOrNull { it.lane.equals(lane, true) }?.sample ?: 0
        if (live) {
            // V5.0.7846 — no PAPER/combined sample can mature a LIVE streak.
            liveN >= STREAK_EVIDENCE_MIN_CLOSES_7719
        } else {
            val oracle = OracleTradeHistory7287.lane(u)?.n ?: 0
            val score = com.lifecyclebot.engine.ScoreExpectancyTracker.laneStats7380(u)?.first ?: 0
            maxOf(liveN, oracle, score) >= STREAK_EVIDENCE_MIN_CLOSES_7719
        }
    } catch (_: Throwable) { false }

    private fun streakShapingActive7719(lane: String, mode: String): Boolean {
        val losses = consecutiveLossesFor6488(lane, mode)
        if (losses < STREAK_SHAPE_ONE_7719) return false
        if (lanePaysEv7334(lane)) return false
        if (!laneHasEvidence7719(lane)) {
            streakDeferredNoEvidence7719.incrementAndGet()
            try { PipelineHealthCollector.labelInc("STREAK_TIGHTEN_DEFERRED_NO_EVIDENCE_7719") } catch (_: Throwable) {}
            return false
        }
        return true
    }

    fun streakDeferredNoEvidenceCount7719(): Long = streakDeferredNoEvidence7719.get()

    fun scoreFloorDeltaFor6488(lane: String, mode: String = currentMode()): Int = when {
        !streakShapingActive7719(lane, mode) -> 0
        consecutiveLossesFor6488(lane, mode) >= STREAK_SHAPE_TWO_7719 -> 10
        else -> 5
    }

    fun sizeMultiplierFor6488(lane: String, mode: String = currentMode()): Double = when {
        !streakShapingActive7719(lane, mode) -> 1.0
        consecutiveLossesFor6488(lane, mode) >= STREAK_SHAPE_TWO_7719 -> 0.5
        else -> 0.75
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
