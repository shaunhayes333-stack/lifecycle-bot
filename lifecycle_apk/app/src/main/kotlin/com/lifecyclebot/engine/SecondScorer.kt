package com.lifecyclebot.engine

import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.TokenState

/**
 * V5.9.806 — Independent second perception scorer.
 *
 * Closes one of the P4 gaps the operator called out: the entire downstream
 * intelligence stack consumes V3's view of the world. If V3 mis-reads a
 * candidate, EVERY brain reasoning above it is processing the same bad
 * features. The fix is not to replace V3 — V3 is a deep scorer — but to
 * grade its confidence by comparing it to a SECOND, independently-weighted
 * scorer. When the two scorers DISAGREE materially, the candidate is
 * marked "disputed" and the rest of the stack can apply a defensive
 * dampener without having to second-guess V3 itself.
 *
 * Weights (deliberately different from V3):
 *   • Liquidity stability (lastLiquidityUsd vs 10-min moving avg) — heavy
 *   • Holder count (more holders = more distribution = less rug)
 *   • Pool age in minutes (older = better)
 *   • Buy/sell imbalance (-30..+30)  — moderate
 *   • V3 momentum (intentionally LOW weight here — opposite of V3 emphasis)
 *
 * Output: 0..100 score, same scale as V3, so the disagreement gap is a
 * direct apples-to-apples comparison.
 */
object SecondScorer {

    data class SecondScore(val score: Int, val components: Map<String, Int>)

    fun score(ts: TokenState): SecondScore {
        val comp = HashMap<String, Int>(6)

        // Liquidity score: 0 (rugged) → 25 (deep)
        val liq = ts.lastLiquidityUsd
        val liqScore = when {
            liq <= 0       -> 0
            liq < 2_000    -> 5
            liq < 5_000    -> 12
            liq < 20_000   -> 18
            liq < 100_000  -> 22
            else           -> 25
        }
        comp["liq"] = liqScore

        // Holder count score: 0 → 20  (peakHolderCount = highest-ever observed
        // holders on this token; better signal than instantaneous because pump.fun
        // BC tokens often show zero holders mid-bonding-curve quote).
        val holders = ts.peakHolderCount
        val holderScore = when {
            holders <= 0    -> 0
            holders < 50    -> 4
            holders < 200   -> 10
            holders < 1_000 -> 16
            else            -> 20
        }
        comp["holders"] = holderScore

        // Pool age score (older = safer): 0 → 20.
        // addedToWatchlistAt is the bot's first-sighting timestamp; close
        // enough to "pool age" without us reaching into chain data.
        val ageMin = ((System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000L).coerceAtLeast(0)
        val ageScore = when {
            ageMin < 1     -> 0
            ageMin < 5     -> 5
            ageMin < 30    -> 10
            ageMin < 120   -> 15
            else           -> 20
        }
        comp["age"] = ageScore

        // Buy pressure score (anti-FOMO: prefer balanced markets, NOT > 80%)
        val buyPct = ts.lastBuyPressurePct
        val buyScore = when {
            buyPct <= 0          -> 0
            buyPct < 40          -> 8         // sellers heavy — neutral
            buyPct in 40.0..60.0 -> 15        // balanced — best
            buyPct < 80          -> 10
            buyPct <= 100        -> 3         // overheated FOMO — penalised
            else                 -> 0
        }
        comp["buy"] = buyScore

        // Recent volume signal — last observed h1 volume on the latest
        // candle. Falls back to zero if history isn't seeded yet.
        val vol = try {
            ts.history.lastOrNull()?.volumeH1 ?: 0.0
        } catch (_: Throwable) { 0.0 }
        val volScore = when {
            vol <= 0          -> 0
            vol < 500         -> 3
            vol < 5_000       -> 8
            vol < 50_000      -> 12
            else              -> 15
        }
        comp["vol"] = volScore

        // Safety / rugcheck score: 0 → 5
        val rcScore = try { ts.safety.rugcheckScore } catch (_: Throwable) { -1 }
        val safetyScore = when {
            rcScore < 0   -> 0
            rcScore < 30  -> 0
            rcScore < 70  -> 3
            else          -> 5
        }
        comp["safety"] = safetyScore

        val total = (liqScore + holderScore + ageScore + buyScore + volScore + safetyScore)
            .coerceIn(0, 100)
        comp["total"] = total
        return SecondScore(total, comp)
    }

    /**
     * Comparison helper used by FDG to flag disputed candidates.
     * Threshold of 20 points is intentionally permissive — the second
     * scorer's role is to flag CLEAR disagreement, not to override V3
     * on every small variance.
     */
    fun isDisputed(v3Score: Int, candidate: CandidateDecision, ts: TokenState): DisputeVerdict {
        val s = score(ts)
        val gap = v3Score - s.score
        val disputed = kotlin.math.abs(gap) >= 20
        return DisputeVerdict(
            v3Score = v3Score,
            secondScore = s.score,
            gap = gap,
            disputed = disputed,
            secondScoreSaysWorse = gap > 0,
            components = s.components,
        )
    }

    data class DisputeVerdict(
        val v3Score: Int,
        val secondScore: Int,
        val gap: Int,
        val disputed: Boolean,
        val secondScoreSaysWorse: Boolean,
        val components: Map<String, Int>,
    )
}
