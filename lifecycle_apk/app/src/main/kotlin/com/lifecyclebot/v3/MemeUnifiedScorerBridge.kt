package com.lifecyclebot.v3

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.v3.scoring.FluidLearningAI

/** Technical confluence over the current canonical V3 decision; never rescores invented market data. */
object MemeUnifiedScorerBridge {

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
    fun scoreForEntry(ts: TokenState, decision: V3Decision? = null): MemeVerdict {
        val techScore = computeTechnicalScore(ts)
        val v3Score = when (decision) {
            is V3Decision.Execute -> decision.score
            is V3Decision.Watch -> decision.score
            is V3Decision.ShadowOnly -> decision.score
            else -> return MemeVerdict(techScore, 0, techScore, 1.0, 30, 25,
                false, listOf("canonical_v3_score_unavailable"), "canonical_v3_score_unavailable")
        }
        val trustMult = 1.0 // Already included in the canonical score.
        val v3Normalised = v3Score.coerceIn(0, 100).toDouble()

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

        val topReasons = listOf("canonical_v3=$v3Score", "technical=$techScore")

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
        val pxThen = if (hist.size >= 5) hist[hist.size - 5].priceUsd else pxNow
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
            val ema5  = hist.takeLast(5).map { it.priceUsd }.average()
            val ema10 = hist.takeLast(10).map { it.priceUsd }.average()
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
                val d = recent[i].priceUsd - recent[i - 1].priceUsd
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
