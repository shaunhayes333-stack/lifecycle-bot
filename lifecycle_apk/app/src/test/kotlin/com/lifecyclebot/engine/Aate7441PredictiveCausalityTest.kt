package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7441PredictiveCausalityTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun ev_estimator_alias_does_not_double_train_forward_model() {
        val s = src("engine/truth/FinalizedBusConsumerBridge6465.kt")
        val alias = s.substringAfter("private fun deliverToEvEstimator").substringBefore("private fun", "")
        assertFalse(alias.contains("ForwardOutcomeModel.recordOutcome"))
        assertTrue(s.contains("EV_ESTIMATOR_ALIAS_ACK_ONLY_7441"))
        assertTrue(s.contains("SignalQualityTracker.recordOutcome(env.mint, env.realizedReturnPct)"))
    }

    @Test fun momentum_grades_only_frozen_entry_prediction() {
        val s = src("engine/MomentumPredictorAI.kt")
        assertTrue(s.contains("EntryPrediction7441"))
        assertTrue(s.contains("stampEntryPrediction7441"))
        assertTrue(s.contains("entryPredictions7441.remove(mint)"))
        assertTrue(s.contains("MOMENTUM_OUTCOME_MISSING_ENTRY_PREDICTION_7441"))
        assertFalse(s.substringAfter("fun recordOutcome(mint: String, pnlPct: Double, peakPnlPct: Double)")
            .substringBefore("fun getPredictionAccuracy").contains("when (momentum.prediction)"))
    }

    @Test fun fdg_freezes_momentum_when_it_freezes_forward_signal() {
        val s = src("engine/FinalDecisionGate.kt")
        assertTrue(s.contains("SignalQualityTracker.stamp"))
        assertTrue(s.contains("MomentumPredictorAI.stampEntryPrediction7441"))
    }

    @Test fun lead_lag_consumes_interval_prices_not_repeated_24h_returns() {
        val lead = src("v4/meta/CrossAssetLeadLagAI.kt")
        val crypto = src("perps/CryptoAltTrader.kt")
        val stock = src("perps/TokenizedStockTrader.kt")
        assertTrue(lead.contains("fun recordPrice7441"))
        assertTrue(lead.contains("dt < 45_000L"))
        assertTrue(lead.contains("dt > 180_000L"))
        assertTrue(crypto.contains("recordPrice7441(market.symbol, data.price)"))
        assertTrue(stock.contains("recordPrice7441(market.symbol, data.price)"))
        assertFalse(crypto.contains("CrossAssetLeadLagAI.recordReturn(market.symbol, data.priceChange24hPct)"))
        assertFalse(stock.contains("CrossAssetLeadLagAI.recordReturn(market.symbol, data.priceChange24hPct)"))
        // V5.0.7674 — dynamic crypto must obey the same causality rule. 7431
        // fixed the key but still repeated rolling 24h change into the model.
        assertTrue(crypto.contains("recordPrice7441(leadLagSymbol7431, price)"))
        assertFalse(crypto.contains("recordReturn(leadLagSymbol7431, change)"))
    }
}
