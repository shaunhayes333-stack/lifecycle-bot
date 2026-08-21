package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
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

    private val consecutiveLosses = AtomicLong(0L)
    private val cohortLastLossMs = ConcurrentHashMap<String, Long>() // lane -> ts
    private val cohortCooldownMs = ConcurrentHashMap<String, Long>() // lane -> cooldown until
    private val gates = AtomicLong(0L)
    private val allows = AtomicLong(0L)
    private val probes = AtomicLong(0L)
    private val denies = AtomicLong(0L)
    private val bypassAttempts = AtomicLong(0L)

    init {
        // Subscribe to canonical finalized events so streak state is the SAME
        // source that reward learners see. Idempotent — bus dedupes.
        try {
            CanonicalTradeFinalizedBus6450.subscribe { e ->
                when (e.outcome) {
                    CanonicalTradeFinalizedBus6450.Outcome.LOSS -> {
                        consecutiveLosses.incrementAndGet()
                        cohortLastLossMs[e.entryLane] = e.settledAtMs
                        cohortCooldownMs[e.entryLane] = e.settledAtMs + 60_000L // 60s cohort cooldown
                    }
                    CanonicalTradeFinalizedBus6450.Outcome.WIN -> consecutiveLosses.set(0L)
                    CanonicalTradeFinalizedBus6450.Outcome.BREAKEVEN -> Unit
                }
            }
        } catch (_: Throwable) {}
    }

    fun gate(lane: String, mint: String, requestedSizeSol: Double): Decision {
        gates.incrementAndGet()
        val now = System.currentTimeMillis()
        val cl = consecutiveLosses.get()
        val cooldownUntil = cohortCooldownMs[lane] ?: 0L
        val cooldownRemainingMs = cooldownUntil - now
        return when {
            cl >= STREAK_HARD_LIMIT -> {
                denies.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "EXECUTABLE_ENTRY_DENIED_STREAK_6450",
                        "lane=$lane mint=${mint.take(10)} streak=$cl",
                    )
                    PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_DENIED_STREAK_6450")
                } catch (_: Throwable) {}
                Decision(Verdict.DENY_LOSING_STREAK, 0.0, "streak=$cl>=$STREAK_HARD_LIMIT")
            }
            cooldownRemainingMs > 0 -> {
                denies.incrementAndGet()
                try { PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_DENIED_COOLDOWN_6450") } catch (_: Throwable) {}
                Decision(Verdict.DENY_COOLDOWN, 0.0, "cooldownMs=$cooldownRemainingMs")
            }
            cl >= STREAK_TIGHTEN_TWO -> {
                allows.incrementAndGet()
                val tightened = requestedSizeSol * 0.35
                try { PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_DEFENSIVE_TIGHTEN_6487_L2") } catch (_: Throwable) {}
                Decision(Verdict.ALLOW, tightened, "defensive streak=$cl scoreFloorDelta=15 sizeMult=0.35")
            }
            cl >= STREAK_TIGHTEN_ONE -> {
                allows.incrementAndGet()
                val tightened = requestedSizeSol * 0.65
                try { PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_DEFENSIVE_TIGHTEN_6487_L1") } catch (_: Throwable) {}
                Decision(Verdict.ALLOW, tightened, "defensive streak=$cl scoreFloorDelta=8 sizeMult=0.65")
            }
            else -> {
                allows.incrementAndGet()
                Decision(Verdict.ALLOW, requestedSizeSol, "ok scoreFloorDelta=0 sizeMult=1.00")
            }
        }
    }

    fun consecutiveLossesNow6487(): Long = consecutiveLosses.get()

    fun defensiveActive6487(): Boolean = consecutiveLosses.get() > 0L

    fun scoreFloorDelta6487(): Int = when {
        consecutiveLosses.get() >= STREAK_HARD_LIMIT -> 100
        consecutiveLosses.get() >= STREAK_TIGHTEN_TWO -> 15
        consecutiveLosses.get() >= STREAK_TIGHTEN_ONE -> 8
        else -> 0
    }

    fun sizeMultiplier6487(): Double = when {
        consecutiveLosses.get() >= STREAK_HARD_LIMIT -> 0.0
        consecutiveLosses.get() >= STREAK_TIGHTEN_TWO -> 0.35
        consecutiveLosses.get() >= STREAK_TIGHTEN_ONE -> 0.65
        else -> 1.0
    }

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

    fun statusLine(): String = "streak=${consecutiveLosses.get()} gates=${gates.get()} " +
        "allows=${allows.get()} probes=${probes.get()} denies=${denies.get()} bypass=${bypassAttempts.get()}"

    internal fun resetForTest6487() {
        consecutiveLosses.set(0L); cohortLastLossMs.clear(); cohortCooldownMs.clear()
        gates.set(0L); allows.set(0L); probes.set(0L); denies.set(0L); bypassAttempts.set(0L)
    }

    internal fun recordLossForTest6487(count: Int, lane: String = "TEST") {
        repeat(count.coerceAtLeast(0)) { consecutiveLosses.incrementAndGet() }
        cohortLastLossMs.remove(lane); cohortCooldownMs.remove(lane)
    }
}
