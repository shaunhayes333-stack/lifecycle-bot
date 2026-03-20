package com.lifecyclebot.engine

import kotlin.math.max
import kotlin.math.min

/**
 * AutoCompoundEngine — Automatic profit reinvestment
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Intelligently compounds profits to accelerate growth while protecting capital.
 *
 * Strategy:
 *   1. Profits above treasury threshold get split:
 *      - X% → Treasury (locked, protected)
 *      - Y% → Compound pool (reinvested)
 *      - Z% → Trading wallet (active use)
 *
 *   2. Compound triggers:
 *      - After each winning trade
 *      - When compound pool reaches threshold
 *      - On daily rebalance
 *
 *   3. Size scaling:
 *      - Compound pool increases max position size
 *      - Drawdown reduces compound contribution
 *      - Streak bonuses for consistent profits
 *
 * Example flow:
 *   Win +0.5 SOL:
 *     → 0.1 SOL to treasury (20%)
 *     → 0.2 SOL to compound pool (40%)
 *     → 0.2 SOL stays in wallet (40%)
 *
 *   Compound pool at 1.0 SOL:
 *     → Max position increases by 0.5x
 *     → Next trade can use larger size
 */
object AutoCompoundEngine {

    data class CompoundConfig(
        val enabled: Boolean = true,
        val treasuryPct: Double = 20.0,      // % of profit to treasury
        val compoundPct: Double = 40.0,       // % of profit to compound
        val walletPct: Double = 40.0,         // % of profit to keep liquid
        val minProfitToCompound: Double = 0.01,  // Min profit before splitting
        val compoundThreshold: Double = 0.5,  // SOL needed to trigger size increase
        val maxSizeMultiplier: Double = 2.0,  // Max position size boost
        val drawdownReduction: Boolean = true, // Reduce compound during drawdown
        val streakBonus: Boolean = true,       // Bonus % on win streaks
    )

    data class CompoundState(
        val compoundPoolSol: Double = 0.0,
        val totalCompounded: Double = 0.0,
        val totalToTreasury: Double = 0.0,
        val currentSizeMultiplier: Double = 1.0,
        val lastCompoundAt: Long = 0,
        val consecutiveWins: Int = 0,
    )

    @Volatile
    var config = CompoundConfig()
        private set

    @Volatile
    var state = CompoundState()
        private set

    data class AllocationResult(
        val toTreasury: Double,
        val toCompound: Double,
        val toWallet: Double,
        val newSizeMultiplier: Double,
        val message: String,
    )

    /**
     * Update compound configuration.
     */
    fun configure(newConfig: CompoundConfig) {
        config = newConfig
    }

    /**
     * Process a winning trade and allocate profits.
     *
     * @param profitSol The profit from the winning trade
     * @param currentDrawdownPct Current drawdown percentage
     * @return Allocation breakdown
     */
    fun processWin(profitSol: Double, currentDrawdownPct: Double = 0.0): AllocationResult {
        if (!config.enabled || profitSol < config.minProfitToCompound) {
            return AllocationResult(
                toTreasury = 0.0,
                toCompound = 0.0,
                toWallet = profitSol,
                newSizeMultiplier = state.currentSizeMultiplier,
                message = "Below minimum, keeping in wallet"
            )
        }

        // Update streak
        val newStreak = state.consecutiveWins + 1
        
        // Calculate split with adjustments
        var treasuryPct = config.treasuryPct
        var compoundPct = config.compoundPct
        var walletPct = config.walletPct

        // Drawdown adjustment: more to wallet, less to compound
        if (config.drawdownReduction && currentDrawdownPct > 10) {
            val reduction = min(20.0, currentDrawdownPct / 2)
            compoundPct = max(10.0, compoundPct - reduction)
            walletPct = min(60.0, walletPct + reduction)
        }

        // Streak bonus: more to compound on hot streaks
        if (config.streakBonus && newStreak >= 3) {
            val bonus = min(10.0, (newStreak - 2) * 2.5)
            compoundPct = min(60.0, compoundPct + bonus)
            walletPct = max(20.0, walletPct - bonus)
        }

        // Normalize to 100%
        val total = treasuryPct + compoundPct + walletPct
        treasuryPct = treasuryPct / total * 100
        compoundPct = compoundPct / total * 100
        walletPct = walletPct / total * 100

        // Calculate amounts
        val toTreasury = profitSol * treasuryPct / 100
        val toCompound = profitSol * compoundPct / 100
        val toWallet = profitSol * walletPct / 100

        // Update compound pool
        val newPool = state.compoundPoolSol + toCompound
        
        // Calculate size multiplier
        val multiplierBoost = (newPool / config.compoundThreshold) * 0.25
        val newMultiplier = min(config.maxSizeMultiplier, 1.0 + multiplierBoost)

        // Update state
        state = state.copy(
            compoundPoolSol = newPool,
            totalCompounded = state.totalCompounded + toCompound,
            totalToTreasury = state.totalToTreasury + toTreasury,
            currentSizeMultiplier = newMultiplier,
            lastCompoundAt = System.currentTimeMillis(),
            consecutiveWins = newStreak,
        )

        val message = buildString {
            append("Split: ${treasuryPct.toInt()}%T/${compoundPct.toInt()}%C/${walletPct.toInt()}%W")
            if (newStreak >= 3) append(" 🔥$newStreak")
            if (newMultiplier > state.currentSizeMultiplier) append(" ⬆️${newMultiplier}x")
        }

        return AllocationResult(
            toTreasury = toTreasury,
            toCompound = toCompound,
            toWallet = toWallet,
            newSizeMultiplier = newMultiplier,
            message = message,
        )
    }

    /**
     * Process a losing trade (resets streak).
     */
    fun processLoss() {
        state = state.copy(consecutiveWins = 0)
    }

    /**
     * Get current position size multiplier.
     */
    fun getSizeMultiplier(): Double = state.currentSizeMultiplier

    /**
     * Withdraw from compound pool (resets multiplier proportionally).
     */
    fun withdrawFromPool(amount: Double): Double {
        val available = min(amount, state.compoundPoolSol)
        val newPool = state.compoundPoolSol - available
        val newMultiplier = if (newPool < config.compoundThreshold) {
            1.0
        } else {
            min(config.maxSizeMultiplier, 1.0 + (newPool / config.compoundThreshold) * 0.25)
        }
        
        state = state.copy(
            compoundPoolSol = newPool,
            currentSizeMultiplier = newMultiplier,
        )
        
        return available
    }

    /**
     * Daily rebalance — call this once per day.
     */
    fun dailyRebalance(currentWalletSol: Double, targetWalletSol: Double): Double {
        // If wallet is below target and compound pool has excess, top up
        if (currentWalletSol < targetWalletSol && state.compoundPoolSol > config.compoundThreshold) {
            val deficit = targetWalletSol - currentWalletSol
            val available = state.compoundPoolSol - config.compoundThreshold
            val transfer = min(deficit, available)
            
            if (transfer > 0) {
                withdrawFromPool(transfer)
                return transfer
            }
        }
        return 0.0
    }

    /**
     * Reset state (new session).
     */
    fun reset() {
        state = CompoundState()
    }

    /**
     * Get status summary.
     */
    fun getStatus(): String = buildString {
        appendLine("🔄 *Auto-Compound Engine*")
        appendLine("━━━━━━━━━━━━━━━━━━━━━")
        appendLine("Status: ${if (config.enabled) "✅ Enabled" else "❌ Disabled"}")
        appendLine("Compound Pool: ${state.compoundPoolSol.fmt(4)} SOL")
        appendLine("Size Multiplier: ${state.currentSizeMultiplier.fmt(2)}x")
        appendLine("Win Streak: ${state.consecutiveWins}")
        appendLine("Total Compounded: ${state.totalCompounded.fmt(4)} SOL")
        appendLine("Total to Treasury: ${state.totalToTreasury.fmt(4)} SOL")
        appendLine()
        appendLine("*Split Config:*")
        appendLine("  Treasury: ${config.treasuryPct.toInt()}%")
        appendLine("  Compound: ${config.compoundPct.toInt()}%")
        appendLine("  Wallet: ${config.walletPct.toInt()}%")
    }

    private fun Double.fmt(d: Int) = "%.${d}f".format(this)
}
