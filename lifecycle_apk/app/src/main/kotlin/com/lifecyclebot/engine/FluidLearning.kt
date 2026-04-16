package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.perps.PriceAggregator

/**
 * FluidLearning - Makes Paper & Shadow Mode fully functional learning environments
 *
 * Win/loss classification:
 * - WIN  = pnlPct >= 0.5
 * - LOSS = pnlPct <= -2.0
 * - Anything between is SCRATCH / NEUTRAL
 */
object FluidLearning {

    // ═══════════════════════════════════════════════════════════════════════════
    // THRESHOLDS
    // ═══════════════════════════════════════════════════════════════════════════

    private const val WIN_THRESHOLD_PCT = 0.5
    private const val LOSS_THRESHOLD_PCT = -2.0

    // ═══════════════════════════════════════════════════════════════════════════
    // SIMULATED BALANCE TRACKING
    // ═══════════════════════════════════════════════════════════════════════════

    @Volatile private var simulatedBalanceSol: Double = 11.7647  // V5.9.7: $1000 USD wallet
    @Volatile private var simulatedPeakSol: Double = 11.7647
    @Volatile private var totalPaperPnlSol: Double = 0.0
    @Volatile private var paperTradeCount: Int = 0
    @Volatile private var paperWinCount: Int = 0
    @Volatile private var paperLossCount: Int = 0

    // Simulated open positions for exposure tracking
    private val simulatedExposure = mutableMapOf<String, Double>()  // mint → SOL exposed

    // ═══════════════════════════════════════════════════════════════════════════
    // PRICE IMPACT SIMULATION
    // ═══════════════════════════════════════════════════════════════════════════

    private val priceImpactOffsets = mutableMapOf<String, Double>()  // mint → cumulative impact %
    private var prefs: SharedPreferences? = null

    /**
     * Get the simulated price impact for a token.
     * Returns a multiplier (e.g. 1.02 means price is 2% higher due to your buys)
     */
    fun getPriceImpact(mint: String): Double {
        return 1.0 + (priceImpactOffsets[mint] ?: 0.0) / 100.0
    }

    /**
     * Record price impact from a paper trade.
     * BUY: Pushes price UP
     * SELL: Pushes price DOWN
     */
    fun recordPriceImpact(mint: String, solAmount: Double, liquidityUsd: Double, isBuy: Boolean) {
        val safeLiquidity = liquidityUsd.coerceAtLeast(1.0)
        val solPrice = try { kotlinx.coroutines.runBlocking { PriceAggregator.getPrice("SOL")?.price } ?: 140.0 } catch (_: Exception) { 140.0 } // V5.9: live price
        val tradeUsd = solAmount * solPrice

        val impactPct = when {
            safeLiquidity < 5_000.0 -> (tradeUsd / safeLiquidity) * 15.0
            safeLiquidity < 20_000.0 -> (tradeUsd / safeLiquidity) * 8.0
            safeLiquidity < 50_000.0 -> (tradeUsd / safeLiquidity) * 4.0
            safeLiquidity < 100_000.0 -> (tradeUsd / safeLiquidity) * 2.0
            else -> (tradeUsd / safeLiquidity) * 1.0
        }.coerceAtMost(10.0)

        val currentImpact = priceImpactOffsets[mint] ?: 0.0
        val newImpact = if (isBuy) currentImpact + impactPct else currentImpact - impactPct

        priceImpactOffsets[mint] = newImpact.coerceIn(-20.0, 20.0)

        ErrorLogger.debug(
            "FluidLearning",
            "📊 Price impact: ${if (isBuy) "BUY" else "SELL"} ${solAmount.fmt(4)} SOL " +
                "| liq=$${safeLiquidity.toLong()} | impact=${String.format("%+.2f", impactPct)}% " +
                "| cumulative=${String.format("%+.2f", newImpact)}%"
        )
    }

    /**
     * Clear price impact for a token
     */
    fun clearPriceImpact(mint: String) {
        priceImpactOffsets.remove(mint)
    }

    /**
     * Decay all price impacts over time
     */
    fun decayPriceImpacts(decayPct: Double = 10.0) {
        priceImpactOffsets.keys.toList().forEach { mint ->
            val current = priceImpactOffsets[mint] ?: return@forEach
            val decayed = current * (1.0 - decayPct / 100.0)

            if (kotlin.math.abs(decayed) < 0.1) {
                priceImpactOffsets.remove(mint)
            } else {
                priceImpactOffsets[mint] = decayed
            }
        }
    }

    /**
     * Initialize with context for persistence
     */
    fun init(ctx: Context, startingBalance: Double = 11.7647) {
        prefs = ctx.getSharedPreferences("fluid_learning", Context.MODE_PRIVATE)
        loadState()

        if (simulatedBalanceSol <= 0.0) {
            simulatedBalanceSol = startingBalance
            simulatedPeakSol = startingBalance
            saveState()
        }

        ErrorLogger.info(
            "FluidLearning",
            "📊 INIT: balance=${simulatedBalanceSol.fmt(4)} SOL | peak=${simulatedPeakSol.fmt(4)} | " +
                "trades=$paperTradeCount | winRate=${getWinRate().toInt()}%"
        )
    }

    /**
     * Current simulated balance
     */
    fun getSimulatedBalance(): Double = simulatedBalanceSol


    /** Directly set the simulated balance — used when syncing from external traders */
    fun forceSetBalance(sol: Double) {
        if (sol > 0.0) {
            simulatedBalanceSol = sol
            if (sol > simulatedPeakSol) simulatedPeakSol = sol
            saveState()
        }
    }
    /**
     * Backward compatibility alias
     */
    fun getPaperBalance(): Double = simulatedBalanceSol

    /**
     * Current simulated peak
     */
    fun getSimulatedPeak(): Double = simulatedPeakSol

    /**
     * Total open exposure in SOL
     */
    fun getSimulatedExposure(): Double = simulatedExposure.values.sum()

    /**
     * Available capital after open exposure
     */
    fun getAvailableBalance(): Double = (simulatedBalanceSol - getSimulatedExposure()).coerceAtLeast(0.0)

    /**
     * Decisive-trade win rate only
     */
    fun getWinRate(): Double {
        val decisiveTrades = paperWinCount + paperLossCount
        return if (decisiveTrades > 0) {
            (paperWinCount.toDouble() / decisiveTrades.toDouble()) * 100.0
        } else {
            50.0
        }
    }

    fun getTradeCount(): Int = paperTradeCount

    fun getTotalPnl(): Double = totalPaperPnlSol

    fun getRecoveryRatio(): Double {
        return if (simulatedPeakSol > 0.0) simulatedBalanceSol / simulatedPeakSol else 1.0
    }

    fun getPerformanceContext(): SmartSizer.PerformanceContext {
        return SmartSizer.PerformanceContext(
            recentWinRate = getWinRate(),
            winStreak = 0,
            lossStreak = 0,
            sessionPeakSol = simulatedPeakSol,
            totalTrades = paperTradeCount,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PAPER TRADE RECORDING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Record a paper BUY - tracks exposure
     */
    fun recordPaperBuy(mint: String, solAmount: Double) {
        simulatedExposure[mint] = (simulatedExposure[mint] ?: 0.0) + solAmount

        ErrorLogger.debug(
            "FluidLearning",
            "📥 PAPER BUY: $mint | ${solAmount.fmt(4)} SOL | " +
                "exposure=${getSimulatedExposure().fmt(4)} | available=${getAvailableBalance().fmt(4)}"
        )
    }

    /**
     * Record a paper SELL - updates simulated balance and learning stats
     */
    fun recordPaperSell(mint: String, originalSol: Double, pnlSol: Double) {
        val pnlPct = if (originalSol > 0.0) (pnlSol / originalSol) * 100.0 else 0.0

        val isWin = isWinPct(pnlPct)
        val isLoss = isLossPct(pnlPct)
        val isScratch = !isWin && !isLoss

        simulatedBalanceSol += pnlSol
        // V5.9.8: Keep BotService.status in sync — single source of truth for UI
        try { com.lifecyclebot.engine.BotService.status.paperWalletSol = simulatedBalanceSol } catch (_: Exception) {}
        totalPaperPnlSol += pnlSol
        paperTradeCount++

        when {
            isWin -> {
                paperWinCount++
                if (simulatedBalanceSol > simulatedPeakSol) {
                    simulatedPeakSol = simulatedBalanceSol
                }
            }

            isLoss -> {
                paperLossCount++
            }

            else -> {
                if (simulatedBalanceSol > simulatedPeakSol) {
                    simulatedPeakSol = simulatedBalanceSol
                }
            }
        }

        simulatedExposure.remove(mint)

        val emoji = when {
            isWin -> "✅"
            isLoss -> "❌"
            else -> "➖"
        }

        val tag = when {
            isWin -> " [WIN]"
            isLoss -> " [LOSS]"
            else -> " [SCRATCH]"
        }

        ErrorLogger.info(
            "FluidLearning",
            "$emoji PAPER SELL: $mint | pnl=${pnlSol.fmt(4)} SOL | pnlPct=${pnlPct.fmt(2)}% | " +
                "balance=${simulatedBalanceSol.fmt(4)} | winRate=${getWinRate().toInt()}%$tag"
        )

        saveState()
    }

    /**
     * Record top-up in paper mode
     */
    fun recordPaperTopUp(mint: String, additionalSol: Double) {
        simulatedExposure[mint] = (simulatedExposure[mint] ?: 0.0) + additionalSol

        ErrorLogger.debug(
            "FluidLearning",
            "📈 PAPER TOP-UP: $mint | +${additionalSol.fmt(4)} SOL | " +
                "total exposure=${getSimulatedExposure().fmt(4)} | available=${getAvailableBalance().fmt(4)}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING PHASE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    enum class LearningPhase {
        BOOTSTRAP,
        DEVELOPING,
        REFINING,
        MATURE,
    }

    fun getLearningPhase(): LearningPhase {
        return when {
            paperTradeCount < 100 -> LearningPhase.BOOTSTRAP
            paperTradeCount < 500 -> LearningPhase.DEVELOPING
            paperTradeCount < 1000 -> LearningPhase.REFINING
            else -> LearningPhase.MATURE
        }
    }

    fun getLearningScaleMultiplier(): Double {
        return when (getLearningPhase()) {
            LearningPhase.BOOTSTRAP -> 1.5
            LearningPhase.DEVELOPING -> 1.25
            LearningPhase.REFINING -> 1.1
            LearningPhase.MATURE -> 1.0
        }
    }

    fun shouldEnableAllFeatures(cfg: BotConfig): Boolean {
        return cfg.fluidLearningEnabled && cfg.paperMode
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════

    private fun saveState() {
        prefs?.edit()?.apply {
            putFloat("simulated_balance", simulatedBalanceSol.toFloat())
            putFloat("simulated_peak", simulatedPeakSol.toFloat())
            putFloat("total_pnl", totalPaperPnlSol.toFloat())
            putInt("trade_count", paperTradeCount)
            putInt("win_count", paperWinCount)
            putInt("loss_count", paperLossCount)
            apply()
        }
    }

    private fun loadState() {
        prefs?.let { p ->
            simulatedBalanceSol = p.getFloat("simulated_balance", 11.7647f).toDouble()
            simulatedPeakSol = p.getFloat("simulated_peak", 11.7647f).toDouble()
            totalPaperPnlSol = p.getFloat("total_pnl", 0.0f).toDouble()
            paperTradeCount = p.getInt("trade_count", 0)
            paperWinCount = p.getInt("win_count", 0)
            paperLossCount = p.getInt("loss_count", 0)
        }
    }

    /**
     * Reset simulated balance to starting amount
     */
    fun reset(startingBalance: Double = 11.7647) {
        simulatedBalanceSol = startingBalance
        simulatedPeakSol = startingBalance
        totalPaperPnlSol = 0.0
        paperTradeCount = 0
        paperWinCount = 0
        paperLossCount = 0
        simulatedExposure.clear()
        priceImpactOffsets.clear()
        saveState()

        ErrorLogger.info("FluidLearning", "🔄 RESET: Starting fresh with ${startingBalance.fmt(4)} SOL")
    }

    /**
     * Get summary for logging
     */
    fun getSummary(): String {
        val phase = getLearningPhase()
        val drawdown = if (simulatedPeakSol > 0.0) {
            ((simulatedPeakSol - simulatedBalanceSol) / simulatedPeakSol) * 100.0
        } else {
            0.0
        }

        return "FluidLearning[$phase]: ${simulatedBalanceSol.fmt(2)} SOL | " +
            "peak=${simulatedPeakSol.fmt(2)} | dd=${drawdown.toInt()}% | " +
            "trades=$paperTradeCount | win=${getWinRate().toInt()}%"
    }

    private fun isWinPct(pnlPct: Double): Boolean = pnlPct >= WIN_THRESHOLD_PCT

    private fun isLossPct(pnlPct: Double): Boolean = pnlPct <= LOSS_THRESHOLD_PCT

    private fun Double.fmt(decimals: Int = 4): String = "%.${decimals}f".format(this)
}