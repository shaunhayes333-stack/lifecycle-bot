package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.TradeHistoryStore
import kotlin.math.abs
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.9.123 — DrawdownCircuitAI
 *
 * Layer-level continuous drawdown tracking + graduated aggression dial.
 * The existing LiveSafetyCircuitBreaker is binary (halts trading at -10%
 * session drawdown). This is a gradient — it dials aggression DOWN
 * smoothly as paper drawdown grows within a rolling 4-hour window.
 *
 *   0-3% rolling DD    → aggression 1.0  (normal)
 *   3-6% rolling DD    → aggression 0.75 (trim TP by 25%, score floor +3)
 *   6-10% rolling DD   → aggression 0.50 (trim TP by 50%, score floor +7)
 *   10%+ rolling DD    → aggression 0.25 (nearly defensive only)
 *
 * Consumed by UnifiedScorer as a score floor bump, and by Executor when
 * sizing new trades.
 */
object DrawdownCircuitAI {

    private const val TAG = "DDCircuit"
    private const val WINDOW_MS = 4L * 3600_000L   // 4 hours

    private data class PnlSample(val ts: Long, val balance: Double)

    private val samples = ArrayDeque<PnlSample>()
    private val currentAggression = AtomicReference(1.0)

    private data class JournalCircuitSnapshot(
        val aggression: Double,
        val decisive: Int,
        val wrPct: Double,
        val profitFactor: Double,
        val currentLossStreak: Int,
        val longestLossStreak: Int,
        val currentDrawdownPct: Double,
        val maxDrawdownPct: Double,
    ) {
        fun line(): String = "journal n=$decisive WR=${"%.1f".format(wrPct)}% PF=${"%.2f".format(profitFactor)} lossStreak=$currentLossStreak maxLossStreak=$longestLossStreak DD=${"%.1f".format(currentDrawdownPct)}% maxDD=${"%.1f".format(maxDrawdownPct)}% aggr=${"%.2f".format(aggression)}"
    }

    @Volatile private var cachedJournalCircuit: JournalCircuitSnapshot? = null
    @Volatile private var cachedJournalCircuitAtMs: Long = 0L
    private const val JOURNAL_CIRCUIT_TTL_MS = 5_000L

    @Synchronized
    fun recordBalance(balanceSol: Double) {
        val now = System.currentTimeMillis()
        samples.addLast(PnlSample(now, balanceSol))
        while (samples.isNotEmpty() && (now - samples.first().ts) > WINDOW_MS) {
            samples.removeFirst()
        }
        recompute()
    }

    @Synchronized
    private fun recompute() {
        if (samples.size < 2) return
        val peak = samples.maxOf { it.balance }
        val current = samples.last().balance
        if (peak <= 0.0) return
        val ddPct = ((peak - current) / peak * 100.0).coerceAtLeast(0.0)
        val rawAggression = when {
            ddPct < 3.0  -> 1.0
            ddPct < 6.0  -> 0.75
            ddPct < 10.0 -> 0.50
            else         -> 0.25
        }
        // V5.9.213: Bootstrap floor — in early phase (< 60 samples ≈ first hour) never
        // collapse below 0.50. A fresh bot loses a lot — that's noise, not a crisis.
        // Without this, 21 consecutive paper losses drive aggression to 0.25, which
        // feeds FEARFUL mood → SymbolicBlock → only junk gets through → more losses.
        // V5.9.220: Extended bootstrap — 120 samples (≈2h) in paper, 60 in live.
        //           Paper floor raised to 0.60 to prevent proof-run stalling.
        val isPaper = try { com.lifecyclebot.engine.GlobalTradeRegistry.isPaperMode } catch (_: Exception) { true }
        val bootstrapWindow = if (isPaper) 120 else 60
        val bootstrapFloorVal = if (isPaper) 0.60 else 0.50
        val bootstrapFloor = if (samples.size < bootstrapWindow) bootstrapFloorVal else 0.0
        val aggression = rawAggression.coerceAtLeast(bootstrapFloor)
        val prev = currentAggression.getAndSet(aggression)
        if (kotlin.math.abs(prev - aggression) > 0.01) {
            ErrorLogger.info(TAG, "📉 4h DD=${"%.1f".format(ddPct)}%% → aggression=${"%.2f".format(aggression)}" +
                if (bootstrapFloor > 0) " (bootstrap floor ${"%.2f".format(bootstrapFloor)})" else "")
        }
    }

    private fun journalCircuitSnapshot(): JournalCircuitSnapshot? {
        val now = System.currentTimeMillis()
        cachedJournalCircuit?.let { if (now - cachedJournalCircuitAtMs < JOURNAL_CIRCUIT_TTL_MS) return it }
        return try {
            val sells = TradeHistoryStore.getAllSells().takeLast(300)
            if (sells.size < 20) return null
            val decisive = sells.filter { it.pnlPct >= 0.5 || it.pnlPct <= -2.0 }.takeLast(120)
            if (decisive.size < 20) return null
            val wins = decisive.count { it.pnlPct >= 0.5 }
            val losses = decisive.count { it.pnlPct <= -2.0 }
            val wr = if (wins + losses > 0) wins.toDouble() * 100.0 / (wins + losses).toDouble() else 50.0
            val grossWin = decisive.filter { it.pnlSol > 0.0 }.sumOf { it.pnlSol }
            val grossLoss = abs(decisive.filter { it.pnlSol < 0.0 }.sumOf { it.pnlSol })
            val pf = if (grossLoss > 1e-9) grossWin / grossLoss else if (grossWin > 0.0) 2.0 else 0.0

            var curLoss = 0
            var longestLoss = 0
            var runningLoss = 0
            decisive.forEach { t ->
                if (t.pnlPct <= -2.0) {
                    runningLoss++
                    longestLoss = maxOf(longestLoss, runningLoss)
                } else if (t.pnlPct >= 0.5) {
                    runningLoss = 0
                }
            }
            for (t in decisive.asReversed()) {
                if (t.pnlPct <= -2.0) curLoss++ else if (t.pnlPct >= 0.5) break
            }

            var equity = 0.0
            var peak = 0.0
            var grossDeployed = 0.0
            var maxDdPct = 0.0
            var currentDdPct = 0.0
            decisive.forEach { t ->
                val pnl = if (t.pnlSol.isFinite()) t.pnlSol else 0.0
                val notional = abs(t.sol).takeIf { it > 0.0 } ?: abs(pnl).coerceAtLeast(0.0001)
                grossDeployed += notional
                equity += pnl
                if (equity > peak) peak = equity
                val dd = peak - equity
                val denom = if (peak >= 0.05) peak else grossDeployed
                val ddPct = if (dd > 0.0 && denom > 0.0) (dd / denom * 100.0).coerceIn(0.0, 100.0) else 0.0
                if (ddPct > maxDdPct) maxDdPct = ddPct
                currentDdPct = ddPct
            }

            val journalAgg = when {
                curLoss >= 30 || longestLoss >= 35 || currentDdPct >= 50.0 || (wr < 15.0 && pf < 0.50) -> 0.25
                curLoss >= 15 || longestLoss >= 20 || currentDdPct >= 30.0 || (wr < 20.0 && pf < 0.70) -> 0.35
                curLoss >= 8  || longestLoss >= 12 || currentDdPct >= 15.0 || wr < 30.0 -> 0.55
                wr < 40.0 || pf < 0.90 -> 0.75
                else -> 1.0
            }
            val snap = JournalCircuitSnapshot(
                aggression = journalAgg,
                decisive = decisive.size,
                wrPct = wr,
                profitFactor = pf,
                currentLossStreak = curLoss,
                longestLossStreak = longestLoss,
                currentDrawdownPct = currentDdPct,
                maxDrawdownPct = maxDdPct,
            )
            cachedJournalCircuit = snap
            cachedJournalCircuitAtMs = now
            snap
        } catch (_: Throwable) { cachedJournalCircuit }
    }

    /** Current defensive aggression, using the stricter of balance-feed DD and canonical journal truth. */
    fun getAggression(): Double {
        val balanceAgg = currentAggression.get()
        val journalAgg = journalCircuitSnapshot()?.aggression ?: 1.0
        return minOf(balanceAgg, journalAgg).coerceIn(0.0, 1.0)
    }

    fun diagnosticLine(): String = journalCircuitSnapshot()?.line()
        ?: "journal circuit warming up; balance aggression=${"%.2f".format(currentAggression.get())}"

    fun score(@Suppress("UNUSED_PARAMETER") candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        val agg = getAggression()
        // V5.9.208: Upgraded penalties — heavier DD should block more aggressively
        val value = when {
            agg >= 0.95 -> 0    // 0-3% DD: normal
            agg >= 0.70 -> -4   // 3-6% DD: -4 (was -2)
            agg >= 0.40 -> -10  // 6-10% DD: -10 (was -5) — should mostly block new entries
            else        -> -20  // 10%+ DD: -20 (was -10) — near-total halt
        }
        return ScoreComponent("DrawdownCircuitAI", value,
            "📉 aggression=${"%.2f".format(agg)} (gate=$value; ${diagnosticLine()})")
    }
}
