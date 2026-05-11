package com.lifecyclebot.v3.bridge

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.v3.core.BotOrchestrator
import com.lifecyclebot.v3.core.ProcessResult
import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.core.V3BotMode
import com.lifecyclebot.v3.decision.OpsMetrics
import com.lifecyclebot.v3.eligibility.CooldownManager
import com.lifecyclebot.v3.eligibility.ExposureGuard
import com.lifecyclebot.v3.learning.LearningMetrics
import com.lifecyclebot.v3.learning.LearningStore
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.scanner.SourceType
import com.lifecyclebot.v3.shadow.ShadowTracker
import com.lifecyclebot.v3.sizing.PortfolioRiskState
import com.lifecyclebot.v3.sizing.WalletSnapshot

/**
 * V3Adapter
 *
 * Bridges legacy TokenState objects into the V3 pipeline safely.
 *
 * Key fixes:
 * - Prevents stale config by rebuilding orchestrator when config/mode/regime changes
 * - Avoids pre-created pipeline fields that would hold old config forever
 * - Sanitizes token fields to reduce null/negative/garbage propagation
 * - Keeps helper methods deterministic and safe for live and paper modes
 */
object V3Adapter {

    @Volatile
    private var config = TradingConfigV3()

    private val cooldownManager = CooldownManager()
    private val exposureGuard = ExposureGuard()
    private val learningStore = LearningStore()
    private val shadowTracker = ShadowTracker()

    @Volatile
    private var orchestrator: BotOrchestrator? = null

    @Volatile
    private var lastMode: V3BotMode? = null

    @Volatile
    private var lastMarketRegime: String? = null

    @Volatile
    private var configVersion: Long = 0L

    @Volatile
    private var orchestratorConfigVersion: Long = -1L

    /**
     * Update V3 configuration.
     * Forces orchestrator rebuild on next request.
     */
    fun configure(newConfig: TradingConfigV3) {
        synchronized(this) {
            config = newConfig
            configVersion++
            orchestrator = null
            orchestratorConfigVersion = -1L
        }
    }

    /**
     * Returns a BotOrchestrator built against the latest config/context.
     */
    fun getOrchestrator(
        isPaperMode: Boolean,
        marketRegime: String = "NEUTRAL",
    ): BotOrchestrator {
        val mode = if (isPaperMode) V3BotMode.PAPER else V3BotMode.LIVE
        val normalizedRegime = marketRegime.trim().ifBlank { "NEUTRAL" }

        synchronized(this) {
            val needsRebuild =
                orchestrator == null ||
                    lastMode != mode ||
                    lastMarketRegime != normalizedRegime ||
                    orchestratorConfigVersion != configVersion

            if (needsRebuild) {
                val ctx = TradingContext(
                    config = config,
                    mode = mode,
                    marketRegime = normalizedRegime,
                )

                orchestrator = BotOrchestrator(
                    ctx = ctx,
                    eligibilityGate = com.lifecyclebot.v3.eligibility.EligibilityGate(
                        config,
                        cooldownManager,
                        exposureGuard,
                    ),
                    fatalRiskChecker = com.lifecyclebot.v3.risk.FatalRiskChecker(config),
                    finalDecisionEngine = com.lifecyclebot.v3.decision.FinalDecisionEngine(config),
                    smartSizer = com.lifecyclebot.v3.sizing.SmartSizerV3(config),
                )

                lastMode = mode
                lastMarketRegime = normalizedRegime
                orchestratorConfigVersion = configVersion
            }

            return requireNotNull(orchestrator)
        }
    }

    /**
     * Convert legacy TokenState into V3 CandidateSnapshot.
     */
    fun toCandidate(ts: TokenState): CandidateSnapshot {
        val now = System.currentTimeMillis()
        val discoveredAt = ts.addedToWatchlistAt.takeIf { it > 0L } ?: now
        val ageMinutes = ((now - discoveredAt).coerceAtLeast(0L)) / 60_000.0

        val safety = ts.safety
        val meta = ts.meta

        val liquidityUsd = ts.lastLiquidityUsd.coerceAtLeast(0.0)
        val marketCapUsd = ts.lastMcap.coerceAtLeast(0.0)
        val buyPressurePct = meta.pressScore.coerceIn(0.0, 100.0)
        val holders = ts.peakHolderCount.takeIf { it > 0 }
        val topHolderPct = safety.topHolderPct.takeIf { it > 0.0 }?.coerceIn(0.0, 100.0)
        val bundledPct = safety.firstBlockSupplyPct.takeIf { it > 0.0 }?.coerceIn(0.0, 100.0)

        return CandidateSnapshot(
            mint = ts.mint.orEmpty(),
            symbol = ts.symbol.orEmpty().ifBlank { "UNKNOWN" },
            source = parseSourceType(ts.source.orEmpty()),
            discoveredAtMs = discoveredAt,
            ageMinutes = ageMinutes,
            liquidityUsd = liquidityUsd,
            marketCapUsd = marketCapUsd,
            buyPressurePct = buyPressurePct,
            volume1mUsd = meta.volScore * (liquidityUsd / 100.0).coerceAtLeast(50.0),  // V5.9.187d: real vol proxy (was 0.0 — killed hasMomentumOrVolume)
            volume5mUsd = meta.volScore * (liquidityUsd / 100.0).coerceAtLeast(50.0) * 3.0,  // V5.9.187d: real vol proxy (was 0.0)
            holders = holders,
            topHolderPct = topHolderPct,
            bundledPct = bundledPct,
            hasIdentitySignals = ts.name.isNotBlank() && ts.name != ts.symbol,
            isSellable = !safety.isBlocked,
            // V5.9.685 — was safety.entryScorePenalty (0 = clean token, not rugcheck score).
            // FatalRiskChecker.check() uses rawRiskScore as the RUGCHECK score (0..100)
            // and blocks unconditionally on score 0..2. Passing entryScorePenalty=0
            // (meaning "no penalty, safe token") caused every clean token to hit
            // EXTREME_RUG_CRITICAL_score=0 — which is why bot had 50+ positions
            // before the update and only 3 after. Fix: pass the actual rugcheck score.
            // rugcheckScore=-1 means timeout/unavailable; FatalRiskChecker defaults
            // rawRiskScore ?: 100, so -1 → 100 which is safe (doesn't trigger 0..5 block).
            rawRiskScore = safety.rugcheckScore.takeIf { it >= 0 },
            extra = buildExtraMap(ts),
        )
    }

    /**
     * Build wallet snapshot from total SOL.
     */
    fun toWallet(
        totalSol: Double,
        reserveSol: Double = 0.05,
    ): WalletSnapshot {
        val safeTotal = totalSol.coerceAtLeast(0.0)
        val safeReserve = reserveSol.coerceAtLeast(0.0)

        return WalletSnapshot(
            totalSol = safeTotal,
            tradeableSol = (safeTotal - safeReserve).coerceAtLeast(0.0),
        )
    }

    /**
     * Build learning metrics for V3.
     */
    fun toLearningMetrics(
        classifiedTrades: Int = 0,
        winRate: Double = 50.0,
        payoffRatio: Double = 1.0,
    ): LearningMetrics {
        return LearningMetrics(
            classifiedTrades = classifiedTrades.coerceAtLeast(0),
            last20WinRatePct = winRate.coerceIn(0.0, 100.0),
            payoffRatio = payoffRatio.coerceAtLeast(0.0),
        )
    }

    /**
     * Build ops metrics for V3.
     */
    fun toOpsMetrics(
        apiHealthy: Boolean = true,
        feedsHealthy: Boolean = true,
        walletHealthy: Boolean = true,
        latencyMs: Long = 100L,
    ): OpsMetrics {
        return OpsMetrics(
            apiHealthy = apiHealthy,
            feedsHealthy = feedsHealthy,
            walletHealthy = walletHealthy,
            latencyMs = latencyMs.coerceAtLeast(0L),
        )
    }

    /**
     * Build current portfolio risk state.
     */
    fun toRiskState(recentDrawdownPct: Double = 0.0): PortfolioRiskState {
        return PortfolioRiskState(
            recentDrawdownPct = recentDrawdownPct.coerceAtLeast(0.0),
        )
    }

    /**
     * Process a token through the V3 pipeline.
     */
    fun processV3(
        ts: TokenState,
        walletSol: Double,
        isPaperMode: Boolean,
        marketRegime: String = "NEUTRAL",
        classifiedTrades: Int = 0,
        winRate: Double = 50.0,
        drawdownPct: Double = 0.0,
        apiHealthy: Boolean = true,
    ): ProcessResult {
        val orchestrator = getOrchestrator(
            isPaperMode = isPaperMode,
            marketRegime = marketRegime,
        )

        return orchestrator.processCandidate(
            candidate = toCandidate(ts),
            wallet = toWallet(walletSol),
            risk = toRiskState(drawdownPct),
            learningMetrics = toLearningMetrics(
                classifiedTrades = classifiedTrades,
                winRate = winRate,
            ),
            opsMetrics = toOpsMetrics(
                apiHealthy = apiHealthy,
            ),
        )
    }

    fun onPositionOpened(mint: String) {
        if (mint.isNotBlank()) {
            exposureGuard.openPosition(mint)
        }
    }

    fun onPositionClosed(mint: String) {
        if (mint.isNotBlank()) {
            exposureGuard.closePosition(mint)
        }
    }

    fun setCooldown(
        mint: String,
        durationMs: Long,
    ) {
        if (mint.isBlank()) return
        val safeDurationMs = durationMs.coerceAtLeast(0L)
        cooldownManager.setCooldown(mint, System.currentTimeMillis() + safeDurationMs)
    }

    fun updateExposure(exposurePct: Double) {
        exposureGuard.currentExposurePct = exposurePct.coerceIn(0.0, 100.0)
    }

    fun getShadowTracker(): ShadowTracker = shadowTracker

    fun getLearningStore(): LearningStore = learningStore

    private fun parseSourceType(source: String): SourceType {
        val s = source.trim()

        return when {
            s.contains("BOOSTED", ignoreCase = true) -> SourceType.DEX_BOOSTED
            s.contains("RAYDIUM", ignoreCase = true) -> SourceType.RAYDIUM_NEW_POOL
            s.contains("PUMP", ignoreCase = true) -> SourceType.PUMP_FUN_GRADUATE
            s.contains("TRENDING", ignoreCase = true) -> SourceType.DEX_TRENDING
            s.contains("GRAD", ignoreCase = true) -> SourceType.PUMP_FUN_GRADUATE
            else -> SourceType.DEX_TRENDING
        }
    }

    private fun buildExtraMap(ts: TokenState): Map<String, Any?> {
        val meta = ts.meta
        val safety = ts.safety
        val extras = mutableMapOf<String, Any?>()

        extras["rsiOversold"] = meta.rsi < 30.0
        extras["momentumUp"] = meta.momScore > 55.0
        extras["momentumWeak"] = meta.momScore < 35.0
        extras["higherLows"] = !meta.lowerHighs
        extras["pumpBuilding"] = meta.pressScore > 65.0 && meta.momScore > 60.0

        extras["liquidityDraining"] = meta.breakdown
        extras["volumeExpanding"] = meta.volScore > 60.0

        extras["accumulationAtVal"] = meta.curveStage.contains("ACCUM", ignoreCase = true)
        extras["sellCluster"] = meta.pressScore < 30.0

        extras["phase"] = ts.phase
        extras["price"] = ts.lastPrice.coerceAtLeast(0.0)

        // V5.9.202: Wire memory layer to TokenWinMemory instead of hardcoded 0
        extras["memoryScore"] = try {
            com.lifecyclebot.engine.TokenWinMemory.getMemoryScoreForMint(ts.mint.orEmpty())
        } catch (_: Exception) { 0 }

        try {
            val suppressionPenalty =
                com.lifecyclebot.engine.DistributionFadeAvoider.getSuppressionPenalty(ts.mint)
            val isFatalSuppression =
                com.lifecyclebot.engine.DistributionFadeAvoider.isFatalSuppression(ts.mint)
            val suppressionReason =
                com.lifecyclebot.engine.DistributionFadeAvoider.checkRawStrategySuppression(ts.mint)

            extras["suppressionPenalty"] = suppressionPenalty
            extras["isFatalSuppression"] = isFatalSuppression
            extras["suppressionReason"] = suppressionReason
        } catch (_: Exception) {
            extras["suppressionPenalty"] = 0
            extras["isFatalSuppression"] = false
            extras["suppressionReason"] = null
        }

        // V5.9.202: Wire copytrade layer to DistributionFadeAvoider suppression data
        // copyTradeStale = token has been seen & invalidated before (stale pattern)
        // copyTradeCrowded = token is currently under copy-trade suppression
        extras["copyTradeStale"] = try {
            com.lifecyclebot.engine.DistributionFadeAvoider.checkRawStrategySuppression(ts.mint)
                ?.contains("COPY_TRADE", ignoreCase = true) == true
        } catch (_: Exception) { false }
        extras["copyTradeCrowded"] = try {
            com.lifecyclebot.engine.DistributionFadeAvoider.getSuppressionPenalty(ts.mint) >= 15
        } catch (_: Exception) { false }

        // V5.9.202: Wire HoldTimeOptimizerAI — pass setupQuality and volatility regime
        extras["setupQuality"] = meta.setupQuality.ifBlank { "C" }
        extras["volatilityRegime"] = when {
            meta.volScore >= 80 -> "EXTREME"
            meta.volScore >= 65 -> "HIGH"
            meta.volScore >= 45 -> "NORMAL"
            meta.volScore >= 25 -> "LOW"
            else -> "CALM"
        }
        extras["totalEntryScore"] = (meta.pressScore + meta.momScore + meta.volScore).toInt().coerceIn(0, 100)

        // V5.9.202: Wire OrderFlowImbalanceAI — build buy/sell volume lists from candle history
        // Use last 10 candles to compute per-bar buy/sell volumes
        val candles = ts.history.toList().takeLast(10)
        if (candles.size >= 3) {
            val buyVols = candles.map { c ->
                val total = c.vol.coerceAtLeast(0.0)
                total * (c.buyRatio.coerceIn(0.0, 1.0))
            }
            val sellVols = candles.map { c ->
                val total = c.vol.coerceAtLeast(0.0)
                total * (1.0 - c.buyRatio.coerceIn(0.0, 1.0))
            }
            val closes = candles.map { it.priceUsd }
            extras["buyVolumes"]  = buyVols
            extras["sellVolumes"] = sellVols
            extras["recentCloses"] = closes
        }

        // V5.9.202: Wire LiquidityCycleAI — feed current token pool data so it can
        // update market-wide state when it hasn't been seeded yet
        // Signature: updateMarketState(totalLiquidityUsd, poolCount, topPoolLiquidities)
        try {
            val cycleState = com.lifecyclebot.v3.scoring.LiquidityCycleAI.getCurrentState()
            if (cycleState.avgPoolLiquidity <= 0 && ts.lastLiquidityUsd > 0) {
                // Seed with this token's data so first-ever score isn't 0
                com.lifecyclebot.v3.scoring.LiquidityCycleAI.updateMarketState(
                    totalLiquidityUsd = ts.lastLiquidityUsd * 50.0,
                    poolCount = 50,
                    topPoolLiquidities = mapOf(ts.mint.orEmpty() to ts.lastLiquidityUsd)
                )
            }
        } catch (_: Exception) { }

        extras["zeroHolders"] = ts.peakHolderCount <= 0
        extras["pureSellPressure"] = meta.pressScore < 20.0
        extras["unsellableSignal"] = safety.isBlocked

        extras["suspiciousName"] =
            ts.symbol.length <= 1 || ts.symbol.all { it.isDigit() }

        return extras
    }
}