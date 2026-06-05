package com.lifecyclebot.engine.runtime

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1323 — Cold-Streak Damper (P1-9 surgical).
 *
 * Operator §9: projected execs/day = 3218, win rate = 11.1%, expectancy
 * = -0.0109 SOL/trade, current cold streak = 16 losses, longest = 50.
 *
 * Do NOT block all trades. Do NOT disable learning. Apply EXECUTION
 * DAMPERS that scale size down as the streak grows. Streak resets on
 * win. Lanes with poor expectancy get damped but still trained.
 *
 * Streak counts are per-lane, per-paper-vs-live, persisted in memory
 * (re-derived on boot from journal if needed by Build E).
 */
object ColdStreakDamper {

    private data class Streak(val lossStreak: AtomicInteger, val winStreak: AtomicInteger, val lastTradeMs: AtomicLong)
    private val streaks = ConcurrentHashMap<String, Streak>()

    private fun key(lane: String, isPaper: Boolean): String =
        "${if (isPaper) "PAPER" else "LIVE"}|${lane.uppercase().take(24)}"

    private fun get(lane: String, isPaper: Boolean): Streak =
        streaks.computeIfAbsent(key(lane, isPaper)) {
            Streak(AtomicInteger(0), AtomicInteger(0), AtomicLong(0L))
        }

    /** Call from the close fanout (paperSell / liveSell) for every settled trade. */
    fun noteOutcome(lane: String, isPaper: Boolean, isWin: Boolean, isLoss: Boolean) {
        val s = get(lane, isPaper)
        s.lastTradeMs.set(System.currentTimeMillis())
        when {
            isWin -> {
                s.lossStreak.set(0)
                s.winStreak.incrementAndGet()
                try { PipelineHealthCollector.labelInc("COLD_STREAK_RESET|${lane.uppercase().take(24)}") } catch (_: Throwable) {}
            }
            isLoss -> {
                s.winStreak.set(0)
                val n = s.lossStreak.incrementAndGet()
                try { PipelineHealthCollector.labelInc("COLD_STREAK_${if (isPaper) "PAPER" else "LIVE"}_${lane.uppercase().take(24)}_DEPTH_$n") } catch (_: Throwable) {}
            }
            else -> { /* scratch — neutral */ }
        }
    }

    /**
     * Returns the size multiplier in (0, 1] given the current cold-streak
     * depth. Operator's rule: damp size, never zero. Floor at 0.25.
     *
     *   0..2 losses : 1.00  (no damp)
     *   3..5        : 0.75
     *   6..9        : 0.50
     *   10..19      : 0.35
     *   20+         : 0.25 (floor)
     */
    fun sizeMultiplier(lane: String, isPaper: Boolean): Double {
        val n = get(lane, isPaper).lossStreak.get()
        val mult = when {
            n <= 2  -> 1.00
            n <= 5  -> 0.75
            n <= 9  -> 0.50
            n <= 19 -> 0.35
            else    -> 0.25
        }
        if (mult < 1.0) {
            try { PipelineHealthCollector.labelInc("COLD_STREAK_DAMPED|${lane.uppercase().take(24)}") } catch (_: Throwable) {}
        }
        return mult
    }

    /** Per-lane current loss streak (for UI / snapshot). */
    fun currentLossStreak(lane: String, isPaper: Boolean): Int = get(lane, isPaper).lossStreak.get()
    fun currentWinStreak(lane: String, isPaper: Boolean): Int = get(lane, isPaper).winStreak.get()

    fun snapshot(): Map<String, Map<String, Int>> = streaks.mapValues {
        mapOf(
            "lossStreak" to it.value.lossStreak.get(),
            "winStreak"  to it.value.winStreak.get(),
        )
    }
}

/**
 * V5.9.1355 — Damage-Control Learning Gate (P0.6 surgical).
 *
 * Operator P0.6: when projected execs/day is >2000 while WR < 10%, the bot is
 * spraying full-size entries into broken contexts. Enter DAMAGE_CONTROL when the
 * last-100 settled meme trades show WR < 20% OR profit-factor < 0.5. In that
 * state: cap NORMAL executions (toxic buckets become dust-probe only) but NEVER
 * disable learning — allow a 1-in-25 controlled probe per context so the buckets
 * keep learning and recovery is always possible. Soft-shape only; honours the
 * performance doctrine (no hard veto outside the original whitelist).
 */
object DamageControlGate {

    private const val WINDOW = 100
    private const val WR_FLOOR_PCT = 20.0
    private const val PF_FLOOR = 0.5
    private const val PROBE_EVERY = 25
    const val DAMAGE_MAX_EXECS_PER_DAY = 250.0

    private val window = java.util.ArrayDeque<Double>(WINDOW + 1)
    private val lock = Any()
    @Volatile private var active = false
    private val probeCounter = ConcurrentHashMap<String, AtomicLong>()

    /** Feed every settled MEME close (pnlPct). Recomputes the damage state. */
    fun noteOutcome(pnlPct: Double) {
        synchronized(lock) {
            window.addLast(pnlPct)
            while (window.size > WINDOW) window.removeFirst()
            if (window.size >= 30) recompute()
        }
    }

    private fun recompute() {
        val list = window.toList()
        val decisive = list.filter { kotlin.math.abs(it) > 0.5 }
        if (decisive.size < 20) { active = false; return }
        val wins = decisive.count { it > 0.5 }
        val wrPct = wins.toDouble() * 100.0 / decisive.size.toDouble()
        val grossWin = decisive.filter { it > 0.0 }.sum()
        val grossLoss = kotlin.math.abs(decisive.filter { it < 0.0 }.sum())
        val pf = if (grossLoss > 1e-6) grossWin / grossLoss else 2.0
        val wasActive = active
        active = wrPct < WR_FLOOR_PCT || pf < PF_FLOOR
        if (active && !wasActive) {
            try { PipelineHealthCollector.labelInc("DAMAGE_CONTROL_LEARNING_ON") } catch (_: Throwable) {}
        }
    }

    fun isActive(): Boolean = active

    enum class Decision { ALLOW, PROBE_ONLY, BLOCK_NORMAL }

    /** Gate a normal entry while damaged: 1-in-25 cadence probe, else block-normal. */
    fun gateNormalEntry(contextKey: String): Decision {
        if (!active) return Decision.ALLOW
        val n = probeCounter.computeIfAbsent(contextKey) { AtomicLong(0) }.incrementAndGet()
        return if (n % PROBE_EVERY == 0L) {
            try { PipelineHealthCollector.labelInc("DAMAGE_CONTROL_PROBE_ALLOWED") } catch (_: Throwable) {}
            Decision.PROBE_ONLY
        } else {
            try { PipelineHealthCollector.labelInc("DAMAGE_CONTROL_NORMAL_ENTRY_BLOCKED") } catch (_: Throwable) {}
            Decision.BLOCK_NORMAL
        }
    }

    fun statusLine(): String = if (active)
        "DAMAGE_CONTROL ON — normal execs capped, dead buckets probe-only (1/$PROBE_EVERY)"
    else ""
}

/**
 * V5.9.1323 — Provider Health Gate (P1-10 surgical).
 *
 * Operator §10: Groq rate-limited (sr=22%, 4xx=7), Helius (sr=0%, 4xx=170),
 * X (sr=0%), GeckoTerminal (sr=80%, 4xx=12). API failures must degrade
 * signals to "unavailable", not freeze UI or repeatedly call failing
 * providers.
 *
 * Circuit-breaker contract:
 *   - record(provider, success) every call.
 *   - shouldCall(provider) returns false during cooldown after burst failures.
 *   - cooldown grows with consecutive failures (15s / 60s / 300s / 900s).
 *   - one success resets the breaker.
 */
object ProviderHealthGate {

    private data class Health(
        val consecFailures: AtomicInteger,
        val cooldownUntilMs: AtomicLong,
        val totalCalls: AtomicLong,
        val totalFailures: AtomicLong,
    )

    private val health = ConcurrentHashMap<String, Health>()

    private fun get(provider: String): Health = health.computeIfAbsent(provider.uppercase().take(24)) {
        Health(AtomicInteger(0), AtomicLong(0L), AtomicLong(0L), AtomicLong(0L))
    }

    private fun cooldownForFailures(n: Int): Long = when {
        n <= 2  -> 0L
        n <= 5  -> 15_000L
        n <= 10 -> 60_000L
        n <= 20 -> 300_000L
        else    -> 900_000L
    }

    /** Returns true if the provider is healthy enough to call now. */
    fun shouldCall(provider: String): Boolean {
        val h = get(provider)
        val now = System.currentTimeMillis()
        val until = h.cooldownUntilMs.get()
        if (until > now) {
            try { PipelineHealthCollector.labelInc("PROVIDER_SUPPRESSED|${provider.uppercase().take(24)}") } catch (_: Throwable) {}
            return false
        }
        return true
    }

    fun recordCall(provider: String, success: Boolean) {
        val h = get(provider)
        h.totalCalls.incrementAndGet()
        if (success) {
            if (h.consecFailures.get() > 0) {
                try { PipelineHealthCollector.labelInc("PROVIDER_RECOVERED|${provider.uppercase().take(24)}") } catch (_: Throwable) {}
            }
            h.consecFailures.set(0)
            h.cooldownUntilMs.set(0L)
        } else {
            h.totalFailures.incrementAndGet()
            val n = h.consecFailures.incrementAndGet()
            val cd = cooldownForFailures(n)
            if (cd > 0L) {
                h.cooldownUntilMs.set(System.currentTimeMillis() + cd)
                try { PipelineHealthCollector.labelInc("PROVIDER_CIRCUIT_OPEN|${provider.uppercase().take(24)}|${cd/1000}s") } catch (_: Throwable) {}
            }
        }
    }

    data class Snapshot(val provider: String, val totalCalls: Long, val totalFailures: Long, val consecFailures: Int, val cooldownMsRemaining: Long)

    fun snapshot(): List<Snapshot> {
        val now = System.currentTimeMillis()
        return health.entries.map { (k, h) ->
            Snapshot(
                provider = k,
                totalCalls = h.totalCalls.get(),
                totalFailures = h.totalFailures.get(),
                consecFailures = h.consecFailures.get(),
                cooldownMsRemaining = maxOf(0L, h.cooldownUntilMs.get() - now),
            )
        }
    }
}
