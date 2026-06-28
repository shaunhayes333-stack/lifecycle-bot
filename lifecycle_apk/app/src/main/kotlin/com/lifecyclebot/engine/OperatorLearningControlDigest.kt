package com.lifecyclebot.engine

/**
 * V5.0.4393 — batched 450+ audit visibility closure for learning/control
 * status surfaces that already existed but were not operator-visible.
 *
 * Report-only. This reads compact status strings only; it does not mutate
 * scanner intake, FDG, sizing, executor, ledger, learning weights, or gates.
 */
object OperatorLearningControlDigest {
    fun status(): String {
        val adaptive = try { AdaptiveLearningEngine.getStatus().take(180) } catch (_: Throwable) { "AdaptiveLearningEngine unavailable" }
        val compound = try { AutoCompoundEngine.getStatus().take(180) } catch (_: Throwable) { "AutoCompoundEngine unavailable" }
        val cloud = try { CloudLearningSync.getStatus().take(180) } catch (_: Throwable) { "CloudLearningSync unavailable" }
        val enabled = try { EnabledTraderAuthority.snapshotStr().take(160) } catch (_: Throwable) { "EnabledTraderAuthority unavailable" }
        val tuner = try { PatternAutoTuner.getStatus().take(180) } catch (_: Throwable) { "PatternAutoTuner unavailable" }
        val scanner = try { ScannerLearning.getStatus().take(220) } catch (_: Throwable) { "ScannerLearning unavailable" }
        val toxic = try { ToxicModeCircuitBreaker.getStatus().take(200) } catch (_: Throwable) { "ToxicModeCircuitBreaker unavailable" }
        val ml = try { com.lifecyclebot.ml.OnDeviceMLEngine.getStatus().take(160) } catch (_: Throwable) { "OnDeviceMLEngine unavailable" }
        val market = try { com.lifecyclebot.v3.modes.MarketStructureRouter.getStatus().take(160) } catch (_: Throwable) { "MarketStructureRouter unavailable" }
        val regime = try { com.lifecyclebot.v3.scoring.RegimeTransitionAI.getStatus().take(160) } catch (_: Throwable) { "RegimeTransitionAI unavailable" }
        return "OPERATOR_LEARNING_CONTROL_DIGEST_4393 adaptive=[$adaptive] compound=[$compound] cloud=[$cloud] enabled=[$enabled] tuner=[$tuner] solana_market_scanner=SolanaMarketScanner scanner=[$scanner] toxic=[$toxic] ml=[$ml] market=[$market] regime=[$regime] report_only=true no_execution_authority=true no_gate_change=true"
    }
}
