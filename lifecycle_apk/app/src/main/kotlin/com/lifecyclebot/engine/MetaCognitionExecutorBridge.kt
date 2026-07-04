package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.MetaCognitionAI

/** V5.0.4267 — executor-side MetaCognition consumer for high-throughput lanes.
 *  V5.0.6077 — no 30-trade actuator cliff: meta-cog shapes from trade 1 with
 *  a bounded confidence ramp, then reaches full bridge weight at 30 analyzed trades.
 *  V5.0.6090 — reins off: meta-cog can materially control non-safety sizing while
 *  still preserving hard safety and never zero-sizing learned strategy. */
object MetaCognitionExecutorBridge {
    fun sizeMultiplierForLane(lane: String): Double {
        return try {
            val analyzed6077 = MetaCognitionAI.getTotalTradesAnalyzed()
            if (analyzed6077 <= 0) return 1.0
            val trade1Ramp6077 = (analyzed6077.toDouble() / 20.0).coerceIn(0.35, 1.0)
            val trader = traderLayerFor(lane)
            val core = listOf(
                MetaCognitionAI.AILayer.ENTRY_INTELLIGENCE,
                MetaCognitionAI.AILayer.LIQUIDITY_DEPTH,
                MetaCognitionAI.AILayer.MOMENTUM_PREDICTOR,
                MetaCognitionAI.AILayer.VOLATILITY_REGIME,
                MetaCognitionAI.AILayer.EXECUTION_COST_PREDICTOR,
            )
            val mults = (listOfNotNull(trader) + core).map { MetaCognitionAI.getTrustMultiplier(it).coerceIn(0.55, 1.65) }
            if (mults.isEmpty()) 1.0 else (1.0 + (mults.average().coerceIn(0.65, 1.45) - 1.0) * trade1Ramp6077).coerceIn(0.65, 1.45)
        } catch (_: Throwable) { 1.0 }
    }

    private fun traderLayerFor(lane: String): MetaCognitionAI.AILayer? {
        val l = lane.uppercase()
        return when {
            l.contains("SHIT") -> MetaCognitionAI.AILayer.SHITCOIN_TRADER
            l.contains("EXPRESS") -> MetaCognitionAI.AILayer.SHITCOIN_EXPRESS
            l.contains("QUALITY") -> MetaCognitionAI.AILayer.QUALITY_TRADER
            l.contains("BLUE") -> MetaCognitionAI.AILayer.BLUECHIP_TRADER
            l.contains("MOON") -> MetaCognitionAI.AILayer.MOONSHOT_TRADER
            l.contains("DIP") -> MetaCognitionAI.AILayer.DIP_HUNTER
            else -> null
        }
    }
}
