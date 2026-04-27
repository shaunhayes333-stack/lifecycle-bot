package com.lifecyclebot.v3

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.core.V3BotMode
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.scanner.SourceType
import com.lifecyclebot.v3.scoring.FluidLearningAI
import com.lifecyclebot.v3.scoring.UnifiedScorer
import com.lifecyclebot.v3.scoring.ScoreCard
import kotlin.math.abs

/**
 * V5.9.346 — MemeUnifiedScorerBridge.
 *
 * Lifts the 79%-WR architecture from PerpsUnifiedScorerBridge / Crypto-Alts
 * trader into the meme trader. Four structural advantages re-deployed:
 *
 *   1) Technical-analysis pre-filter — composite TA score computed BEFORE
 *      V3 ever scores. Only TA-validated candidates reach V3.
 *
 *   2) Synthetic minimum floors on liquidity / mcap / age — the V3 layers
 *      score the candidate as an "established asset", not as fresh-launch
 *      noise. Real values are still used for sizing and execution; this
 *      only affects the V3 scoring stage.
 *
 *   3) 60/40 blend — TA primary (60%), V3 advisory (40%) with a bounded
 *      AITrustNetworkAI trust multiplier.
 *
 *   4) Bootstrap-adaptive floor — at 0% learning the floor is permissive
 *      so the bot trades from first start; at 100% it tightens.
 */
object MemeUnifiedScorerBridge {

    private const val TAG = "MemeBridge"

    private val defaultCtx by lazy {
        TradingContext(
            config = TradingConfigV3(),
            mode = V3BotMode.PAPER,
            marketRegime = "NEUTRAL",
        )
    }

    data class MemeVerdict(
        val techScore: Int,
        val v3Score: Int,
        val blendedScore: Int,
        val trustMultiplier: Double,
        val techFloor: Int,
        val blendedFloor: Int,
        val shouldEnter: Boolean,
        val topReasons: List<String>,
        val rejectReason: String? = null,
    )

    /**
     * Compute the meme trader's blended verdict for a candidate.
     */
    fun scoreForEntry(ts: TokenState): MemeVerdict {
        // 1) TA composite (0-100)
        val techScore = computeTechnicalScore(ts)

        // 2) Synthetic CandidateSnapshot with permissive memetoken floors
        //    (liq ≥ $2K, mcap ≥ $20K, age ≥ 5min per user 3a).
        val nowMs = System.currentTimeMillis()
        val realAgeMin = if (ts.addedToWatchlistAt > 0L) {
            (nowMs - ts.addedToWatchlistAt) / 60_000.0
        } else 60.0
        val syntheticAgeMin = realAgeMin.coerceAtLeast(5.0)
        val histLast = ts.history.lastOrNull()
        val holders = (histLast?.holderCount ?: 0).takeIf { it > 0 } ?: 50

        val snap = CandidateSnapshot(
            mint = ts.mint,
            symbol = ts.symbol,
            source = SourceType.DEX_TRENDING,
            discoveredAtMs = nowMs - (syntheticAgeMin * 60_000.0).toLong(),
            ageMinutes = syntheticAgeMin,
            liquidityUsd = ts.lastLiquidityUsd.coerceAtLeast(2_000.0),
            marketCapUsd = ts.lastMcap.coerceAtLeast(20_000.0),
            buyPressurePct = ts.lastBuyPressurePct.coerceIn(0.0, 100.0),
            volume1mUsd = ts.lastLiquidityUsd * 0.02,
            volume5mUsd = ts.lastLiquidityUsd * 0.08,
            holders = holders,
            topHolderPct = ts.topHolderPct,
            bundledPct = null,
            hasIdentitySignals = false,
            isSellable = true,
            rawRiskScore = null,
            extra = mapOf(
                "techScore" to techScore,
                "realAgeMin" to realAgeMin,
            ),
        )

        // 3) V3 score (advisory) via UnifiedScorer.score
        val card: ScoreCard = try {
            UnifiedScorer.score(snap, defaultCtx)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "V3 scoring failed for ${ts.symbol}: ${e.message}")
            return MemeVerdict(
                techScore = techScore,
                v3Score = 0,
                blendedScore = techScore,
                trustMultiplier = 1.0,
                techFloor = 30,
                blendedFloor = 25,
                shouldEnter = techScore >= 30,
                topReasons = listOf("v3_failed_TA_fallback"),
                rejectReason = if (techScore < 30) "tech_score<30_after_v3_fail" else null,
            )
        }

        val v3Score = card.components.sumOf { it.value }
        val trustMult = card.components
            .firstOrNull { it.name.equals("AITrustNetworkAI", ignoreCase = true) }
            ?.let { 1.0 + (it.value / 50.0).coerceIn(-0.20, 0.30) } ?: 1.0

        // V3 normalised onto 0-100 axis (classicScore total typically ±40 → map [-40,+40] → [0,100])
        val v3Normalised = ((v3Score + 40) * 1.25).coerceIn(0.0, 100.0).toInt()

        // 4) 60/40 blend with bounded trust multiplier
        val blended = ((techScore * 0.60) + (v3Normalised * 0.40 * trustMult))
            .coerceIn(0.0, 120.0)
            .toInt()

        // 5) Bootstrap-adaptive floors (memetokens — base 30/25, scale +20 with learning)
        val lp = try { FluidLearningAI.getLearningProgress() } catch (_: Exception) { 1.0 }
        val techFloor    = (30 + (lp * 20)).toInt().coerceIn(30, 50)
        val blendedFloor = (25 + (lp * 20)).toInt().coerceIn(25, 45)

        // 6) Final entry decision
        val passesTech    = techScore >= techFloor
        val passesBlended = blended  >= blendedFloor
        val passesV3Veto  = v3Score  > -15

        val shouldEnter = passesTech && passesBlended && passesV3Veto
        val rejectReason = when {
            !passesTech    -> "tech<${techFloor}(${techScore})"
            !passesBlended -> "blend<${blendedFloor}(${blended})"
            !passesV3Veto  -> "v3_veto(${v3Score})"
            else           -> null
        }

        val topReasons = card.components
            .sortedByDescending { abs(it.value) }
            .take(4)
            .map { "${it.name}=${it.value}" }

        return MemeVerdict(
            techScore = techScore,
            v3Score = v3Score,
            blendedScore = blended,
            trustMultiplier = trustMult,
            techFloor = techFloor,
            blendedFloor = blendedFloor,
            shouldEnter = shouldEnter,
            topReasons = topReasons,
            rejectReason = rejectReason,
        )
    }

    /**
     * Technical-analysis composite (0-100). Per user 2abc: combines
     * Momentum + Volume + BuyPressure + EMA fan + RSI proxy. The
     * meme-specialist trader scores (Moonshot/ShitCoin) don't expose a
     * public per-candidate method so we compose from raw signals only;
     * specialist traders still receive their own scoring when this
     * verdict says BUY (via the existing scoring loop).
     */
    private fun computeTechnicalScore(ts: TokenState): Int {
        val hist = ts.history
        val pxNow = ts.lastPrice

        // 1) Buy pressure (already 0-100)
        val buyPress = ts.lastBuyPressurePct.coerceIn(0.0, 100.0)

        // 2) Short-term momentum from price slope. 0% → 50, +20% → 100, -20% → 0
        val pxThen = if (hist.size >= 5) hist[hist.size - 5].price else pxNow
        val pctChg = if (pxThen > 0) ((pxNow - pxThen) / pxThen) * 100.0 else 0.0
        val momentum = (50.0 + (pctChg.coerceIn(-20.0, 20.0) * 2.5))
            .coerceIn(0.0, 100.0)

        // 3) Volume / depth proxy (alts-style)
        val volScore = when {
            ts.lastLiquidityUsd >= 50_000.0 -> 80.0
            ts.lastLiquidityUsd >= 10_000.0 -> 60.0
            ts.lastLiquidityUsd >= 3_000.0  -> 40.0
            else                             -> 25.0
        }

        // 4) EMA fan from short MAs
        val emaScore = if (hist.size >= 10) {
            val ema5  = hist.takeLast(5).map { it.price }.average()
            val ema10 = hist.takeLast(10).map { it.price }.average()
            when {
                pxNow > ema5 && ema5 > ema10 -> 80.0
                pxNow > ema5 || ema5 > ema10 -> 55.0
                else                          -> 30.0
            }
        } else 50.0

        // 5) RSI proxy
        val rsiScore = if (hist.size >= 8) {
            val recent = hist.takeLast(8)
            var gains = 0.0; var losses = 0.0
            for (i in 1 until recent.size) {
                val d = recent[i].price - recent[i - 1].price
                if (d > 0) gains += d else losses -= d
            }
            val rs = if (losses > 0) gains / losses else 99.0
            val rsi = 100.0 - (100.0 / (1.0 + rs))
            when {
                rsi in 50.0..70.0 -> 80.0
                rsi in 40.0..80.0 -> 65.0
                rsi in 30.0..85.0 -> 50.0
                else              -> 25.0
            }
        } else 50.0

        // Weighted blend
        val composite = (
            buyPress * 0.25 +
            momentum * 0.25 +
            volScore * 0.15 +
            emaScore * 0.20 +
            rsiScore * 0.15
        )

        return composite.coerceIn(0.0, 100.0).toInt()
    }
}
