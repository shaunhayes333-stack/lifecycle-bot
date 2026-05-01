package com.lifecyclebot.engine.lab

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.402 — Lab Promoted Feed.
 *
 * The read-only bridge between the LLM Lab and the live trading engines.
 * Once a Lab strategy is auto-promoted (paper-proven), its parameters
 * surface here for `Executor`, sub-traders, and any other consumer that
 * wants to nudge their behaviour by the LLM's proven discoveries.
 *
 * Two channels:
 *   • EntryNudge  — score-floor + size-multiplier per (asset, symbol)
 *   • ExitNudge   — does any promoted strategy say "exit this position now"
 *
 * Real-money safety:
 *   • A PROMOTED strategy nudges PAPER trades automatically.
 *   • For LIVE (real-money) trades, callers must check
 *     `requireLiveApproval(strategyId)` first; if the strategy hasn't been
 *     granted live spend authority yet, callers should fall back to legacy
 *     behaviour and queue an approval via LlmLabEngine.requestSingleLiveTrade.
 */
object LabPromotedFeed {
    private const val TAG = "LabPromotedFeed"

    // Strategies that have user-granted live spend authority.
    private val liveAuthorised = ConcurrentHashMap.newKeySet<String>()

    fun grantLiveAuthority(strategyId: String) {
        liveAuthorised.add(strategyId)
        ErrorLogger.info(TAG, "🧪 Granted live spend authority to $strategyId")
    }

    fun revokeLiveAuthority(strategyId: String) {
        liveAuthorised.remove(strategyId)
    }

    fun requireLiveApproval(strategyId: String): Boolean = !liveAuthorised.contains(strategyId)

    fun isLiveAuthorised(strategyId: String): Boolean = liveAuthorised.contains(strategyId)

    // ────────────────────────────────────────────────────────────────────────
    // ENTRY NUDGE — applied by Executor.doBuy on top of legacy logic.
    // ────────────────────────────────────────────────────────────────────────
    data class EntryNudge(
        val strategyId: String,
        val strategyName: String,
        val sizeMultiplier: Double,    // 0.75..1.5 (clamped at consumer)
        val scoreFloor: Int,           // require score >= this
        val takeProfitPct: Double,     // hint for exit logic
        val stopLossPct: Double,
        val maxHoldMins: Int,
    )

    /**
     * Returns the strongest promoted strategy's nudge for an asset+score.
     * Null = no nudge — caller uses default behaviour.
     */
    fun entryNudge(asset: LabAssetClass, score: Int): EntryNudge? {
        val candidates = LlmLabStore.allStrategies()
            .filter { it.status == LabStrategyStatus.PROMOTED &&
                      (it.asset == LabAssetClass.ANY || it.asset == asset) &&
                      score >= it.entryScoreMin }
        if (candidates.isEmpty()) return null
        // pick best paper expectancy
        val best = candidates.maxByOrNull { it.paperPnlSol / (it.paperTrades.coerceAtLeast(1)) }
            ?: return null

        // Paper WR translates to a sizing multiplier 0.75..1.5
        val mult = (0.5 + (best.winRatePct() / 100.0)).coerceIn(0.75, 1.5)
        return EntryNudge(
            strategyId = best.id,
            strategyName = best.name,
            sizeMultiplier = mult,
            scoreFloor = best.entryScoreMin,
            takeProfitPct = best.takeProfitPct,
            stopLossPct = best.stopLossPct,
            maxHoldMins = best.maxHoldMins,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // EXIT NUDGE — fast hint for sub-trader exit checks.
    // ────────────────────────────────────────────────────────────────────────
    /**
     * Returns true if any promoted strategy for this asset class would exit
     * a position with the given pnl + hold. Used as a soft cap by Moonshot/
     * ShitCoin/etc. exit checks alongside their own legacy logic.
     */
    fun shouldExitByPromotedRule(asset: LabAssetClass, pnlPct: Double, holdMinutes: Long): Boolean {
        val promoted = LlmLabStore.allStrategies()
            .filter { it.status == LabStrategyStatus.PROMOTED &&
                      (it.asset == LabAssetClass.ANY || it.asset == asset) }
        if (promoted.isEmpty()) return false
        return promoted.any { s ->
            pnlPct >= s.takeProfitPct ||
            pnlPct <= s.stopLossPct ||
            holdMinutes >= s.maxHoldMins
        }
    }

    /** UI helper — count of promoted strategies and their total paper PnL. */
    fun summary(): Pair<Int, Double> {
        val promoted = LlmLabStore.allStrategies().filter { it.status == LabStrategyStatus.PROMOTED }
        return promoted.size to promoted.sumOf { it.paperPnlSol }
    }
}
