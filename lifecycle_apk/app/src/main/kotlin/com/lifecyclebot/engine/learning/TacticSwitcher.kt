package com.lifecyclebot.engine.learning

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.LosingPatternMemory
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.ScoreExpectancyTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1333 — TACTIC SWITCHER (Fluid Constantly-Learnt Tactic Rotation).
 *
 * Operator mandate: "if they aren't successful they need to change tactics
 * in a fluid constantly learnt state". This module fulfills that exactly.
 *
 *   NEVER DISABLE A BUCKET. ALWAYS ROTATE ITS TACTIC.
 *
 * Each (lane, scoreBand) tuple gets a current Tactic. When the bucket's
 * recent N closes are net-negative beyond a threshold, the tactic rotates
 * to the next in the cycle. Each new tactic gets a fresh sample window
 * (TRIAL_WINDOW) to prove itself; if it underperforms, rotate again.
 *
 * Tactics (canonical 4-cycle):
 *   MOMENTUM      — enter on positive price velocity + buy pressure  (default)
 *   PULLBACK      — enter on -3% to -8% retracement from local high
 *   REACCUMULATION — enter on sideways consolidation + sustained buys
 *   BREAKOUT      — enter only on confirmed structure break (new HH)
 *
 * Train-First doctrine:
 *   - Bucket NEVER stops trading.
 *   - Score floors / soft-size already protect capital.
 *   - The tactic switch changes WHAT signal qualifies a buy, not WHETHER.
 *
 * Persistence: each tactic written to LearningPersistence as JSON blob
 * "tactic_${LANE}|${BAND}" so the rotation state survives restarts.
 */
object TacticSwitcher {

    private const val TAG = "TacticSwitcher"

    enum class Tactic { MOMENTUM, PULLBACK, REACCUMULATION, BREAKOUT }

    /** Fresh trades required before a rotated tactic is judged. */
    private const val TRIAL_WINDOW = 25

    /** Bucket sample size threshold before rotation considered. */
    private const val MIN_SAMPLES = 25

    /** Loss-rate threshold (75%+) to trigger rotation. */
    private const val LOSS_RATE_TRIGGER = 0.75

    /** Mean PnL threshold (-5%+ net-negative) to trigger rotation. */
    private const val MEAN_PNL_TRIGGER = -5.0

    private data class Cell(
        val tactic: AtomicInteger,
        val trialStartedAt: AtomicLong,          // ms
        val tradesSinceRotation: AtomicInteger,
        val pnlSumSinceRotation: AtomicLong,     // in basis-points (×100)
        val winsSinceRotation: AtomicInteger,
        val lossesSinceRotation: AtomicInteger,
        val lastRotationReason: java.util.concurrent.atomic.AtomicReference<String>,
    )

    private val cells = ConcurrentHashMap<String, Cell>()
    private val mutex = Any()

    private fun key(lane: String, scoreBand: String): String =
        "${lane.uppercase().take(24)}|${scoreBand.uppercase().take(8)}"

    private fun getOrCreate(lane: String, scoreBand: String): Cell {
        val k = key(lane, scoreBand)
        return cells.computeIfAbsent(k) {
            val cell = Cell(
                tactic = AtomicInteger(Tactic.MOMENTUM.ordinal),
                trialStartedAt = AtomicLong(System.currentTimeMillis()),
                tradesSinceRotation = AtomicInteger(0),
                pnlSumSinceRotation = AtomicLong(0L),
                winsSinceRotation = AtomicInteger(0),
                lossesSinceRotation = AtomicInteger(0),
                lastRotationReason = java.util.concurrent.atomic.AtomicReference("initial"),
            )
            loadFromPersistenceIfAny(k, cell)
            cell
        }
    }

    /** Public read API — sub-traders call this at entry-decision time. */
    fun currentTactic(lane: String, scoreBand: String): Tactic =
        Tactic.values()[getOrCreate(lane, scoreBand).tactic.get()]

    /** Convenience overload accepting raw score. */
    fun currentTactic(lane: String, score: Int): Tactic {
        val band = try { LosingPatternMemory.scoreBand(score) } catch (_: Throwable) { "" }
        return currentTactic(lane, band)
    }

    /**
     * Called from journal-write site (sell path) so the switcher observes
     * outcome per (lane, scoreBand) and decides whether to rotate.
     *
     * pnlPct: realized PnL% on the trade.
     */
    fun onTradeClosed(lane: String, scoreBand: String, pnlPct: Double) {
        val cell = getOrCreate(lane, scoreBand)
        cell.tradesSinceRotation.incrementAndGet()
        cell.pnlSumSinceRotation.addAndGet((pnlPct * 100).toLong())
        if (pnlPct > 0.0) cell.winsSinceRotation.incrementAndGet() else cell.lossesSinceRotation.incrementAndGet()

        // Evaluate rotation only after a full trial window has accumulated.
        val tradesIn = cell.tradesSinceRotation.get()
        if (tradesIn < TRIAL_WINDOW) {
            persist(key(lane, scoreBand), cell)
            return
        }

        val losses = cell.lossesSinceRotation.get()
        val lossRate = losses.toDouble() / tradesIn
        val meanPnl = (cell.pnlSumSinceRotation.get().toDouble() / 100.0) / tradesIn

        if (lossRate >= LOSS_RATE_TRIGGER && meanPnl <= MEAN_PNL_TRIGGER) {
            rotate(lane, scoreBand, cell, "lossRate=${"%.0f".format(lossRate * 100)}% mean=${"%+.1f".format(meanPnl)}% n=$tradesIn")
        } else {
            // Decent or improving — reset window so we keep watching forward.
            // (Don't rotate; do reset counters so we evaluate next window cleanly.)
            cell.tradesSinceRotation.set(0)
            cell.pnlSumSinceRotation.set(0L)
            cell.winsSinceRotation.set(0)
            cell.lossesSinceRotation.set(0)
            cell.trialStartedAt.set(System.currentTimeMillis())
            persist(key(lane, scoreBand), cell)
        }
    }

    /**
     * External-driven rotation check — TacticSwitcher.maybeRotateFromMemory()
     * can be called periodically (e.g., from the bot loop) to pull stats from
     * LosingPatternMemory + ScoreExpectancyTracker so even quiet buckets get
     * re-evaluated when accumulated history shows poison.
     */
    fun maybeRotateFromMemory(lane: String, scoreBand: String) {
        val cell = getOrCreate(lane, scoreBand)
        val tradesIn = cell.tradesSinceRotation.get()
        if (tradesIn < MIN_SAMPLES) return

        val st = try { LosingPatternMemory.stats(lane, scoreBandToMidScore(scoreBand)) } catch (_: Throwable) { return }
        val totalSamples = st.wins + st.losses
        if (totalSamples < MIN_SAMPLES) return
        val lossRate = if (totalSamples > 0) st.losses.toDouble() / totalSamples else 0.0
        if (lossRate >= LOSS_RATE_TRIGGER && st.meanPnl <= MEAN_PNL_TRIGGER) {
            rotate(lane, scoreBand, cell, "memory:lossRate=${"%.0f".format(lossRate * 100)}% μ=${"%+.1f".format(st.meanPnl)}% n=$totalSamples")
        }
    }

    private fun rotate(lane: String, scoreBand: String, cell: Cell, reason: String) {
        val k = key(lane, scoreBand)
        synchronized(mutex) {
            val current = cell.tactic.get()
            val next = (current + 1) % Tactic.values().size
            cell.tactic.set(next)
            cell.tradesSinceRotation.set(0)
            cell.pnlSumSinceRotation.set(0L)
            cell.winsSinceRotation.set(0)
            cell.lossesSinceRotation.set(0)
            cell.trialStartedAt.set(System.currentTimeMillis())
            cell.lastRotationReason.set(reason)
            persist(k, cell)
            val prevName = Tactic.values()[current].name
            val nextName = Tactic.values()[next].name
            ErrorLogger.info(TAG, "🔄 TACTIC_ROTATE $k  $prevName → $nextName  ($reason)")
            try {
                PipelineHealthCollector.labelInc("TACTIC_ROTATE|$k|$nextName")
            } catch (_: Throwable) {}
        }
    }

    /** Snapshot used by the dump renderer. */
    data class Snapshot(
        val key: String,
        val tactic: Tactic,
        val tradesSinceRotation: Int,
        val winsSinceRotation: Int,
        val lossesSinceRotation: Int,
        val meanPnlPct: Double,
        val ageMs: Long,
        val lastReason: String,
    )

    fun snapshotAll(): List<Snapshot> {
        val now = System.currentTimeMillis()
        return cells.entries.map { (k, c) ->
            val trades = c.tradesSinceRotation.get()
            val pnlMean = if (trades > 0) (c.pnlSumSinceRotation.get().toDouble() / 100.0) / trades else 0.0
            Snapshot(
                key = k,
                tactic = Tactic.values()[c.tactic.get()],
                tradesSinceRotation = trades,
                winsSinceRotation = c.winsSinceRotation.get(),
                lossesSinceRotation = c.lossesSinceRotation.get(),
                meanPnlPct = pnlMean,
                ageMs = now - c.trialStartedAt.get(),
                lastReason = c.lastRotationReason.get() ?: "",
            )
        }.sortedByDescending { it.tradesSinceRotation }
    }

    private fun persist(key: String, cell: Cell) {
        try {
            val json = """{"t":${cell.tactic.get()},"start":${cell.trialStartedAt.get()},"n":${cell.tradesSinceRotation.get()},"pnl":${cell.pnlSumSinceRotation.get()},"w":${cell.winsSinceRotation.get()},"l":${cell.lossesSinceRotation.get()},"r":"${cell.lastRotationReason.get()?.replace("\"", "")?.take(80) ?: ""}"}"""
            LearningPersistence.save("tactic_$key", json)
        } catch (_: Throwable) {}
    }

    private fun loadFromPersistenceIfAny(key: String, cell: Cell) {
        try {
            val raw = LearningPersistence.load("tactic_$key") ?: return
            Regex(""""t":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                if (it in Tactic.values().indices) cell.tactic.set(it)
            }
            Regex(""""start":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { cell.trialStartedAt.set(it) }
            Regex(""""n":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { cell.tradesSinceRotation.set(it) }
            Regex(""""pnl":(-?\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { cell.pnlSumSinceRotation.set(it) }
            Regex(""""w":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { cell.winsSinceRotation.set(it) }
            Regex(""""l":(\d+)""").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { cell.lossesSinceRotation.set(it) }
            Regex(""""r":"([^"]*)"""").find(raw)?.groupValues?.getOrNull(1)?.let { cell.lastRotationReason.set(it) }
        } catch (_: Throwable) {}
    }

    private fun scoreBandToMidScore(band: String): Int = when (band.uppercase()) {
        "S0-10" -> 5
        "S11-25" -> 18
        "S26-40" -> 33
        "S41-60" -> 50
        "S61+" -> 70
        else -> 30
    }
}
