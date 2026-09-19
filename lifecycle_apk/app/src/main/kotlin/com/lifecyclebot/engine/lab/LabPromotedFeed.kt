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
 * Real-money safety (rewritten V5.0.7106 — the text here described a contract
 * that no longer exists, which is the failure V5.0.7105 had just finished
 * removing from LaneAutoPauseGuard):
 *   • A PROMOTED strategy nudges PAPER trades automatically.
 *   • For LIVE trades, callers ask `liveNudgeRefusal7106(strategyId, sol)`.
 *     Null means the strategy has cleared the higher live bar and is inside
 *     its rolling exposure cap, so its nudge applies. A non-null reason means
 *     IGNORE THE NUDGE — the entry still proceeds on the bot's own merits.
 *     Nothing here may stop a trade or wait on a human.
 *   • Callers that apply a nudge in live must report the spend through
 *     `recordLiveSpend7106`, or the cap bounds nothing.
 *   • `requireLiveApproval` / `isLiveAuthorised` remain for the UI's manual
 *     grant/revoke view. They are NOT the live gate any more; 7106 is.
 */
object LabPromotedFeed {
    private const val TAG = "LabPromotedFeed"

    // Strategies that have user-granted live spend authority.
    private val liveAuthorised = ConcurrentHashMap.newKeySet<String>()
    private val liveRevoked = ConcurrentHashMap.newKeySet<String>()

    fun grantLiveAuthority(strategyId: String) {
        liveRevoked.remove(strategyId)
        liveAuthorised.add(strategyId)
        ErrorLogger.info(TAG, "🧪 Granted live spend authority to $strategyId")
    }

    fun revokeLiveAuthority(strategyId: String) {
        liveAuthorised.remove(strategyId)
        liveRevoked.add(strategyId)
    }

    // V5.0.6684 — paper proof is autonomous live authority unless the operator
    // explicitly revoked this strategy. Hard trade safety remains downstream.
    fun isLiveAuthorised(strategyId: String): Boolean {
        if (liveRevoked.contains(strategyId)) return false
        if (liveAuthorised.contains(strategyId)) return true
        val s = try { LlmLabStore.getStrategy(strategyId) } catch (_: Throwable) { null } ?: return false
        return s.status == LabStrategyStatus.PROMOTED &&
            s.paperTrades >= LlmLabStore.MIN_TRADES_BEFORE_PROMOTION &&
            s.winRatePct() >= LlmLabStore.MIN_WR_FOR_PROMOTION_PCT &&
            s.paperPnlSol >= LlmLabStore.MIN_PAPER_PNL_SOL_FOR_PROMOTION
    }

    fun requireLiveApproval(strategyId: String): Boolean = !isLiveAuthorised(strategyId)

    // ────────────────────────────────────────────────────────────────────────
    // V5.0.7106 §A LIVE PROOF BAR, AND A BOUND ON WHAT ONE STRATEGY MAY SPEND
    //
    // Operator decision: an LLM-authored strategy may direct real money without
    // a tap, but it must EARN that by clearing a higher bar than paper
    // promotion, and no single strategy may direct unlimited real money.
    //
    // Before this, V5.0.6684 made live authority identical to paper authority:
    // isLiveAuthorised() returns true for any PROMOTED strategy that clears the
    // SAME three paper constants it was promoted on. So thirty paper trades at
    // 33% win rate bought both promotion and real-money spend authority, and
    // "live proof" did not exist as a distinct idea anywhere in the codebase.
    //
    // It does now. The live bar is strictly above the paper bar on all three
    // axes — twice the sample, a higher win rate, three times the proven PnL —
    // so a strategy has to keep working after promotion, on a population it did
    // not train on, before it touches the wallet.
    //
    // The exposure cap is a ROLLING 24h window, not a lifetime total, and that
    // is deliberate: a lifetime cap eventually silences every strategy that
    // ever worked, which is a slow death disguised as a safety feature. A
    // rolling window bounds the damage any one strategy can do in a day and
    // then forgives it.
    //
    // CRITICAL PROPERTY, and the reason this is safe to run unattended: failing
    // either test NEVER blocks a trade. It drops the strategy's NUDGE, and the
    // entry proceeds on the bot's own merits. The cap bounds the LLM's
    // influence; it cannot bound the bot's volume.
    // ────────────────────────────────────────────────────────────────────────

    /** Twice the paper sample — the strategy must keep working after promotion. */
    private const val LIVE_MIN_TRADES_7106 = 60
    /** Above the 33% paper bar. */
    private const val LIVE_MIN_WR_PCT_7106 = 40.0
    /** Three times the paper PnL proof. */
    private const val LIVE_MIN_PNL_SOL_7106 = 0.15
    /** Rolling ceiling on real SOL one strategy may direct per day. */
    private const val LIVE_MAX_EXPOSURE_SOL_PER_DAY_7106 = 2.0
    private const val EXPOSURE_WINDOW_MS_7106 = 24L * 60L * 60L * 1000L

    private val liveSpend7106 = ConcurrentHashMap<String, ArrayDeque<Pair<Long, Double>>>()

    /** Rolling live SOL this strategy has directed inside the window. */
    private fun liveExposureSol7106(strategyId: String): Double {
        val q = liveSpend7106[strategyId] ?: return 0.0
        val cutoff = System.currentTimeMillis() - EXPOSURE_WINDOW_MS_7106
        return synchronized(q) {
            while (q.isNotEmpty() && q.first().first < cutoff) q.removeFirst()
            q.sumOf { it.second }
        }
    }

    /** Record real SOL directed by this strategy. Call only on a LIVE buy. */
    fun recordLiveSpend7106(strategyId: String, sol: Double) {
        if (strategyId.isBlank() || !sol.isFinite() || sol <= 0.0) return
        val q = liveSpend7106.getOrPut(strategyId) { ArrayDeque() }
        val now = System.currentTimeMillis()
        val cutoff = now - EXPOSURE_WINDOW_MS_7106
        synchronized(q) {
            while (q.isNotEmpty() && q.first().first < cutoff) q.removeFirst()
            q.addLast(now to sol)
        }
    }

    /**
     * May this strategy direct real money right now?
     *
     * Returns null when it may, or a reason string when it may not. A reason is
     * an instruction to IGNORE THE NUDGE — never to skip the trade.
     */
    fun liveNudgeRefusal7106(strategyId: String, proposedSol: Double): String? {
        if (liveRevoked.contains(strategyId)) return "OPERATOR_REVOKED"
        val s = try { LlmLabStore.getStrategy(strategyId) } catch (_: Throwable) { null }
            ?: return "STRATEGY_UNKNOWN"
        // An explicit operator grant substitutes for the PROOF bar — that is
        // what the tap in LabActivity means, and honouring revoke while
        // ignoring grant would make the operator's hand work in one direction
        // only. It does NOT substitute for the exposure cap: the bar is about
        // earning trust, the cap is about bounding a single strategy's damage,
        // and a human saying "I trust this one" is not the same as saying "let
        // it spend without limit".
        val operatorGranted = liveAuthorised.contains(strategyId)
        if (!operatorGranted) {
            if (s.status != LabStrategyStatus.PROMOTED) return "NOT_PROMOTED"
            if (s.paperTrades < LIVE_MIN_TRADES_7106) return "LIVE_BAR_TRADES_${s.paperTrades}_OF_$LIVE_MIN_TRADES_7106"
            if (s.winRatePct() < LIVE_MIN_WR_PCT_7106) return "LIVE_BAR_WR_${"%.0f".format(s.winRatePct())}_OF_${LIVE_MIN_WR_PCT_7106.toInt()}"
            if (s.paperPnlSol < LIVE_MIN_PNL_SOL_7106) return "LIVE_BAR_PNL_${"%.3f".format(s.paperPnlSol)}_OF_$LIVE_MIN_PNL_SOL_7106"
        }
        val used = liveExposureSol7106(strategyId)
        val proposed = if (proposedSol.isFinite() && proposedSol > 0.0) proposedSol else 0.0
        if (used + proposed > LIVE_MAX_EXPOSURE_SOL_PER_DAY_7106) {
            return "EXPOSURE_CAP_${"%.2f".format(used)}_PLUS_${"%.3f".format(proposed)}_OVER_$LIVE_MAX_EXPOSURE_SOL_PER_DAY_7106"
        }
        return null
    }

    /** V5.0.7106 — operator report line: what the LLM is authorised to spend. */
    fun liveAuthorityStatusLine7106(): String = try {
        val promoted = LlmLabStore.allStrategies().filter { it.status == LabStrategyStatus.PROMOTED }
        val cleared = promoted.count { liveNudgeRefusal7106(it.id, 0.0) == null }
        val exposure = promoted.sumOf { liveExposureSol7106(it.id) }
        "promoted=${promoted.size} liveBarCleared=$cleared revoked=${liveRevoked.size} " +
            "rolling24hSol=${"%.3f".format(exposure)} capPerStrategy=$LIVE_MAX_EXPOSURE_SOL_PER_DAY_7106 " +
            "bar=n$LIVE_MIN_TRADES_7106/wr${LIVE_MIN_WR_PCT_7106.toInt()}/pnl$LIVE_MIN_PNL_SOL_7106"
    } catch (t: Throwable) { "unavailable(${t.javaClass.simpleName})" }

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
            .filter { !com.lifecyclebot.engine.AdaptiveLaneReproof6684.isTargetedStrategy(it.id) }
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
            .filter { !com.lifecyclebot.engine.AdaptiveLaneReproof6684.isTargetedStrategy(it.id) }
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
