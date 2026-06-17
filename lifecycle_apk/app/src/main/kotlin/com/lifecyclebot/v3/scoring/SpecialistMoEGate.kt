package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import kotlin.math.roundToInt

/**
 * V5.0.3823 — SpecialistMoEGate.
 *
 * First MoE upgrade over the 44-layer scorer. The old stack already computed many
 * specialist votes, but the final score still behaved like a flattened sum with
 * several hand-authored weights. This gate is an additive, bounded read-weighting
 * layer: each specialist component keeps voting, but its vote is softly scaled by
 * existing learned evidence from EducationSubLayerAI + MetaCognitionAI.
 *
 * Safety doctrine:
 *  - no hard veto
 *  - no zeroing a non-zero vote
 *  - no hot-path LLM/API call
 *  - no new learned state; consumes already-persisted Education/MetaCog state
 *  - bounded multiplier [0.75, 1.25]
 */
object SpecialistMoEGate {
    private const val MIN_TRADES_FOR_WEIGHT = 8
    private const val FLOOR = 0.75
    private const val CAP = 1.25

    @Volatile private var lastApplied = 0L
    @Volatile private var lastBoosted = 0L
    @Volatile private var lastDamped = 0L
    @Volatile private var lastNeutral = 0L

    private fun metaLayerFor(name: String): MetaCognitionAI.AILayer? = when (name.lowercase()) {
        "entry" -> MetaCognitionAI.AILayer.ENTRY_INTELLIGENCE
        "momentum" -> MetaCognitionAI.AILayer.MOMENTUM_PREDICTOR
        "liquidity" -> MetaCognitionAI.AILayer.LIQUIDITY_DEPTH
        "volume", "orderflow" -> MetaCognitionAI.AILayer.ORDER_FLOW_IMBALANCE
        "narrative" -> MetaCognitionAI.AILayer.NARRATIVE_DETECTOR
        "memory" -> MetaCognitionAI.AILayer.TOKEN_WIN_MEMORY
        "regime" -> MetaCognitionAI.AILayer.MARKET_REGIME
        "time" -> MetaCognitionAI.AILayer.TIME_OPTIMIZATION
        "feargreed" -> MetaCognitionAI.AILayer.FEAR_GREED
        "social" -> MetaCognitionAI.AILayer.SOCIAL_VELOCITY
        "volatility" -> MetaCognitionAI.AILayer.VOLATILITY_REGIME
        "smartmoney" -> MetaCognitionAI.AILayer.SMART_MONEY_DIVERGENCE
        "holdtime" -> MetaCognitionAI.AILayer.HOLD_TIME_OPTIMIZER
        "liquiditycycle" -> MetaCognitionAI.AILayer.LIQUIDITY_CYCLE
        "collective_ai" -> MetaCognitionAI.AILayer.COLLECTIVE_INTELLIGENCE
        "correlationhedgeai" -> MetaCognitionAI.AILayer.CORRELATION_HEDGE
        "liquidityexitpathai" -> MetaCognitionAI.AILayer.LIQUIDITY_EXIT_PATH
        "mevdetectionai" -> MetaCognitionAI.AILayer.MEV_DETECTION
        "stablecoinflowai" -> MetaCognitionAI.AILayer.STABLECOIN_FLOW
        "operatorfingerprintai" -> MetaCognitionAI.AILayer.OPERATOR_FINGERPRINT
        "sessionedgeai" -> MetaCognitionAI.AILayer.SESSION_EDGE
        "executioncostpredictorai" -> MetaCognitionAI.AILayer.EXECUTION_COST_PREDICTOR
        "drawdowncircuitai" -> MetaCognitionAI.AILayer.DRAWDOWN_CIRCUIT
        "capitalefficiencyai" -> MetaCognitionAI.AILayer.CAPITAL_EFFICIENCY
        "tokendnaclusteringai" -> MetaCognitionAI.AILayer.TOKEN_DNA_CLUSTERING
        "peeralphaverficationai", "peeralphaverificationai" -> MetaCognitionAI.AILayer.PEER_ALPHA_VERIFICATION
        "newsshockai" -> MetaCognitionAI.AILayer.NEWS_SHOCK
        "fundingrateawarenessai" -> MetaCognitionAI.AILayer.FUNDING_RATE_AWARENESS
        "orderbookimbalancepulseai" -> MetaCognitionAI.AILayer.ORDERBOOK_IMBALANCE_PULSE
        "insider_tracker" -> MetaCognitionAI.AILayer.INSIDER_TRACKER
        "behavior" -> MetaCognitionAI.AILayer.BEHAVIOR_AI
        else -> null
    }

    private fun nonZeroRounded(v: Double, original: Int): Int {
        val rounded = v.roundToInt()
        if (rounded != 0 || original == 0) return rounded
        return if (original > 0) 1 else -1
    }

    fun apply(components: List<ScoreComponent>, candidate: CandidateSnapshot, ctx: TradingContext): List<ScoreComponent> {
        var applied = 0L
        var boosted = 0L
        var damped = 0L
        var neutral = 0L
        val assetClass = try { EducationSubLayerAI.assetClassOf(ctx.mode.name, candidate.mint) } catch (_: Throwable) { "MEME" }

        val out = components.map { c ->
            if (c.value == 0) {
                neutral++
                return@map c
            }
            try {
                val layerName = EducationSubLayerAI.componentNameToLayer(c.name)
                val maturity = EducationSubLayerAI.getLayerMaturity(layerName)
                if (maturity.trades < MIN_TRADES_FOR_WEIGHT) {
                    neutral++
                    return@map c
                }
                val scopedAcc = EducationSubLayerAI.getLayerAccuracy(layerName, assetClass)
                val expectancy = maturity.expectancyPct.coerceIn(-25.0, 25.0)
                val sharpe = maturity.sharpe.coerceIn(-3.0, 3.0)
                val metaTrust = metaLayerFor(c.name)?.let { MetaCognitionAI.getTrustMultiplier(it).coerceIn(0.75, 1.25) } ?: 1.0

                val accTerm = (scopedAcc - 0.50) * 0.55
                val expTerm = expectancy * 0.006
                val sharpeTerm = sharpe * 0.035
                val metaTerm = (metaTrust - 1.0) * 0.45
                val mult = (1.0 + accTerm + expTerm + sharpeTerm + metaTerm).coerceIn(FLOOR, CAP)
                val newValue = nonZeroRounded(c.value * mult, c.value)
                if (newValue == c.value) {
                    neutral++
                    c
                } else {
                    applied++
                    if (mult > 1.0) boosted++ else damped++
                    c.copy(
                        value = newValue,
                        reason = "${c.reason} | MOE×${"%.2f".format(mult)} acc=${"%.0f".format(scopedAcc * 100)} exp=${"%+.1f".format(expectancy)} sh=${"%+.2f".format(sharpe)}"
                    )
                }
            } catch (_: Throwable) {
                neutral++
                c
            }
        }
        lastApplied = applied
        lastBoosted = boosted
        lastDamped = damped
        lastNeutral = neutral
        if (applied > 0L) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SPECIALIST_MOE_APPLIED") } catch (_: Throwable) {}
        }
        return out
    }

    fun formatForPipelineDump(): String {
        val total = lastApplied + lastNeutral
        if (total <= 0L) return ""
        return "\n===== Specialist MoE Gate (V5.0.3823) — bounded specialist weighting =====\n" +
            "  applied=$lastApplied boosted=$lastBoosted damped=$lastDamped neutral=$lastNeutral bounds=${"%.2f".format(FLOOR)}..${"%.2f".format(CAP)}\n" +
            "  Read: soft read-weighting only; no veto, no zeroing, no hot-path LLM/API.\n"
    }
}
