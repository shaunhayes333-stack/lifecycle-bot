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
        val streak = cohortLosses[key]?.get() ?: 0L
        val cooling = (cohortCooldownMs[key] ?: 0L) > System.currentTimeMillis()
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
        cohortLosses.clear(); cohortLastLossMs.clear(); cohortCooldownMs.clear()
        gates.set(0L); allows.set(0L); probes.set(0L); denies.set(0L); bypassAttempts.set(0L)
    }

    internal fun recordLossForTest6487(count: Int, lane: String = "TEST", mode: String = "PAPER") {
        cohortLosses[cohortKey(mode, lane)] = AtomicLong(count.coerceAtLeast(0).toLong())
        cohortLastLossMs.remove(cohortKey(mode, lane)); cohortCooldownMs.remove(cohortKey(mode, lane))
    }
}
