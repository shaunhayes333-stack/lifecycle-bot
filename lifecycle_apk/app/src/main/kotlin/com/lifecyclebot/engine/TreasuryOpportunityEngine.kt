package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TreasuryOpportunityEngine — Active treasury deployment for compound growth.
 * 
 * Manages intelligent use of accumulated treasury funds to:
 * - Deploy into high-confidence setups
 * - Compound profits without risking initial capital
 * - Target moonshot opportunities with house money
 * 
 * DEFENSIVE DESIGN:
 * - Static object, no initialization
 * - All methods wrapped in try/catch
 * - Conservative deployment rules
 * - Never risks more than allocated treasury portion
 */
object TreasuryOpportunityEngine {
    
    private const val TAG = "TreasuryOpp"
    
    // ═══════════════════════════════════════════════════════════════════
    // DEPLOYMENT CONFIG
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Deployment rules for treasury capital.
     */
    data class DeploymentConfig(
        val maxDeployPct: Double = 20.0,           // Max % of treasury to deploy at once
        val maxConcurrentDeploys: Int = 3,         // Max simultaneous treasury positions
        val minTreasuryToStart: Double = 0.5,      // Min treasury SOL before deploying
        val minConfidenceScore: Double = 75.0,     // Min confidence to deploy
        val targetModes: Set<String> = setOf(      // Preferred modes for treasury
            "MOONSHOT", "PUMP_SNIPER", "MICRO_CAP", "REVIVAL"
        ),
        val cooldownMs: Long = 300_000,            // 5 min cooldown between deploys
    )
    
    /**
     * Opportunity assessment result.
     */
    data class Opportunity(
        val mint: String,
        val symbol: String,
        val confidence: Double,           // 0-100
        val expectedReturnPct: Double,    // Estimated return %
        val riskLevel: Int,               // 1-5 (higher = riskier)
        val mode: String,                 // Trading mode
        val reason: String,               // Why this is an opportunity
        val recommendedSizeSol: Double,   // How much to deploy
        val timestampMs: Long = System.currentTimeMillis(),
    )
    
    /**
     * Active treasury deployment.
     */
    data class TreasuryDeployment(
        val mint: String,
        val symbol: String,
        val deploySol: Double,
        val entryPrice: Double,
        val mode: String,
        val startTimeMs: Long = System.currentTimeMillis(),
        var currentPnlPct: Double = 0.0,
        var peakPnlPct: Double = 0.0,
        var isActive: Boolean = true,
    )
    
    // ═══════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════
    
    private var config = DeploymentConfig()
    private val activeDeployments = ConcurrentHashMap<String, TreasuryDeployment>()
    private val opportunityQueue = ConcurrentHashMap<String, Opportunity>()
    
    @Volatile private var lastDeployTimeMs: Long = 0
    @Volatile private var totalDeployed: Double = 0.0
    @Volatile private var totalReturned: Double = 0.0
    @Volatile private var deploymentCount: Int = 0
    @Volatile private var successCount: Int = 0
    
    private val isEnabled = AtomicBoolean(false)
    
    // ═══════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Enable/disable treasury opportunity engine.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        if (enabled) {
            ErrorLogger.info(TAG, "Treasury opportunity engine ENABLED")
        } else {
            ErrorLogger.info(TAG, "Treasury opportunity engine DISABLED")
        }
    }
    
    /**
     * Check if engine is enabled.
     */
    fun isEnabled(): Boolean = isEnabled.get()
    
    /**
     * Update deployment configuration.
     */
    fun updateConfig(newConfig: DeploymentConfig) {
        config = newConfig
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // OPPORTUNITY DETECTION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Assess if a token is a good treasury deployment opportunity.
     * 
     * @param mint Token mint address
     * @param symbol Token symbol
     * @param entryScore Strategy entry score
     * @param confidence Confidence level
     * @param mode Current trading mode
     * @param liquidityUsd Available liquidity
     * @param mcapUsd Market cap
     * @return Opportunity if worth deploying, null otherwise
     */
    fun assessOpportunity(
        mint: String,
        symbol: String,
        entryScore: Double,
        confidence: Double,
        mode: String,
        liquidityUsd: Double,
        mcapUsd: Double,
    ): Opportunity? {
        if (!isEnabled.get()) return null
        
        return try {
            // Check if this mode is targeted for treasury deployment
            if (mode !in config.targetModes) return null
            
            // Check confidence threshold
            if (confidence < config.minConfidenceScore) return null
            
            // Check entry score
            if (entryScore < 70) return null
            
            // Check liquidity (need at least $5k)
            if (liquidityUsd < 5000) return null
            
            // Estimate expected return based on mode and metrics
            val expectedReturn = estimateReturn(mode, entryScore, confidence, mcapUsd)
            
            // Calculate risk level
            val riskLevel = calculateRiskLevel(mode, liquidityUsd, mcapUsd)
            
            // Calculate recommended size
            val treasuryAvailable = TreasuryManager.treasurySol
            val maxDeploy = treasuryAvailable * (config.maxDeployPct / 100.0)
            val recommendedSize = when (riskLevel) {
                1, 2 -> maxDeploy * 1.0      // Low risk: full allocation
                3 -> maxDeploy * 0.7         // Medium risk: 70%
                4 -> maxDeploy * 0.5         // High risk: 50%
                else -> maxDeploy * 0.3      // Very high risk: 30%
            }
            
            val opp = Opportunity(
                mint = mint,
                symbol = symbol,
                confidence = confidence,
                expectedReturnPct = expectedReturn,
                riskLevel = riskLevel,
                mode = mode,
                reason = "High confidence $mode setup with ${entryScore.toInt()} entry score",
                recommendedSizeSol = recommendedSize.coerceAtLeast(0.01),
            )
            
            // Add to queue
            opportunityQueue[mint] = opp
            
            ErrorLogger.info(TAG, "💎 Opportunity: $symbol ($mode) conf=${confidence.toInt()}% " +
                "expected=${expectedReturn.toInt()}% risk=$riskLevel size=${recommendedSize.fmt()}")
            
            opp
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "assessOpportunity error: ${e.message}")
            null
        }
    }
    
    /**
     * Check if we should deploy treasury into an opportunity.
     * 
     * @param opportunity The opportunity to evaluate
     * @return true if should deploy
     */
    fun shouldDeploy(opportunity: Opportunity): Boolean {
        if (!isEnabled.get()) return false
        
        return try {
            // Check treasury balance
            val treasury = TreasuryManager.treasurySol
            if (treasury < config.minTreasuryToStart) {
                ErrorLogger.debug(TAG, "Treasury too low: ${treasury.fmt()} < ${config.minTreasuryToStart}")
                return false
            }
            
            // Check concurrent deployments
            val activeCount = activeDeployments.count { it.value.isActive }
            if (activeCount >= config.maxConcurrentDeploys) {
                ErrorLogger.debug(TAG, "Max deployments reached: $activeCount")
                return false
            }
            
            // Check cooldown
            val elapsed = System.currentTimeMillis() - lastDeployTimeMs
            if (elapsed < config.cooldownMs) {
                ErrorLogger.debug(TAG, "Cooldown active: ${elapsed / 1000}s < ${config.cooldownMs / 1000}s")
                return false
            }
            
            // Check if already deployed to this token
            if (activeDeployments.containsKey(opportunity.mint)) {
                return false
            }
            
            // Check opportunity is still valid (not stale)
            val age = System.currentTimeMillis() - opportunity.timestampMs
            if (age > 60_000) {  // 1 minute staleness
                return false
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // DEPLOYMENT TRACKING
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Record a treasury deployment.
     */
    fun recordDeployment(
        mint: String,
        symbol: String,
        deploySol: Double,
        entryPrice: Double,
        mode: String,
    ) {
        try {
            val deployment = TreasuryDeployment(
                mint = mint,
                symbol = symbol,
                deploySol = deploySol,
                entryPrice = entryPrice,
                mode = mode,
            )
            
            activeDeployments[mint] = deployment
            lastDeployTimeMs = System.currentTimeMillis()
            totalDeployed += deploySol
            deploymentCount++
            
            ErrorLogger.info(TAG, "💎 DEPLOYED: $symbol ${deploySol.fmt()}◎ ($mode)")
            
            // Remove from opportunity queue
            opportunityQueue.remove(mint)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordDeployment error: ${e.message}")
        }
    }
    
    /**
     * Update deployment P&L.
     */
    fun updateDeploymentPnl(mint: String, currentPrice: Double) {
        try {
            activeDeployments[mint]?.let { dep ->
                if (!dep.isActive) return
                
                val pnlPct = if (dep.entryPrice > 0) {
                    ((currentPrice - dep.entryPrice) / dep.entryPrice * 100)
                } else 0.0
                
                dep.currentPnlPct = pnlPct
                if (pnlPct > dep.peakPnlPct) {
                    dep.peakPnlPct = pnlPct
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    /**
     * Close a treasury deployment.
     */
    fun closeDeployment(mint: String, returnSol: Double, pnlPct: Double) {
        try {
            activeDeployments[mint]?.let { dep ->
                dep.isActive = false
                totalReturned += returnSol
                
                if (pnlPct > 5.0) {
                    successCount++
                    ErrorLogger.info(TAG, "💎 SUCCESS: ${dep.symbol} +${pnlPct.toInt()}% " +
                        "returned ${returnSol.fmt()}◎")
                } else if (pnlPct < -5.0) {
                    ErrorLogger.info(TAG, "💎 LOSS: ${dep.symbol} ${pnlPct.toInt()}%")
                }
            }
            
            // Remove after a delay to allow UI to update
            activeDeployments.remove(mint)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "closeDeployment error: ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Get deployment statistics.
     */
    fun getStats(): DeploymentStats {
        return try {
            val activeCount = activeDeployments.count { it.value.isActive }
            val activePnl = activeDeployments.values
                .filter { it.isActive }
                .sumOf { it.currentPnlPct }
            
            DeploymentStats(
                totalDeployed = totalDeployed,
                totalReturned = totalReturned,
                netPnlSol = totalReturned - totalDeployed,
                deploymentCount = deploymentCount,
                successCount = successCount,
                winRate = if (deploymentCount > 0) successCount.toDouble() / deploymentCount * 100 else 0.0,
                activeDeployments = activeCount,
                activePnlPct = if (activeCount > 0) activePnl / activeCount else 0.0,
                queueSize = opportunityQueue.size,
            )
        } catch (e: Exception) {
            DeploymentStats()
        }
    }
    
    data class DeploymentStats(
        val totalDeployed: Double = 0.0,
        val totalReturned: Double = 0.0,
        val netPnlSol: Double = 0.0,
        val deploymentCount: Int = 0,
        val successCount: Int = 0,
        val winRate: Double = 0.0,
        val activeDeployments: Int = 0,
        val activePnlPct: Double = 0.0,
        val queueSize: Int = 0,
    )
    
    /**
     * Get active deployments for display.
     */
    fun getActiveDeployments(): List<TreasuryDeployment> {
        return try {
            activeDeployments.values.filter { it.isActive }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get pending opportunities.
     */
    fun getPendingOpportunities(): List<Opportunity> {
        return try {
            opportunityQueue.values.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════
    
    private fun estimateReturn(mode: String, entryScore: Double, confidence: Double, mcapUsd: Double): Double {
        return try {
            val baseReturn = when (mode) {
                "MOONSHOT" -> 100.0      // Target 100%+ returns
                "PUMP_SNIPER" -> 50.0    // Quick 50% pops
                "MICRO_CAP" -> 150.0     // Higher potential
                "REVIVAL" -> 80.0        // Recovery plays
                else -> 30.0             // Conservative
            }
            
            // Adjust by score and confidence
            val scoreMultiplier = entryScore / 100.0
            val confMultiplier = confidence / 100.0
            
            // Lower mcap = higher potential
            val mcapMultiplier = when {
                mcapUsd < 50_000 -> 1.5
                mcapUsd < 100_000 -> 1.2
                mcapUsd < 500_000 -> 1.0
                else -> 0.8
            }
            
            (baseReturn * scoreMultiplier * confMultiplier * mcapMultiplier).coerceIn(10.0, 500.0)
        } catch (e: Exception) {
            30.0
        }
    }
    
    private fun calculateRiskLevel(mode: String, liquidityUsd: Double, mcapUsd: Double): Int {
        return try {
            var risk = when (mode) {
                "BLUE_CHIP" -> 1
                "STANDARD", "LONG_HOLD" -> 2
                "MOMENTUM_SWING", "COPY_TRADE" -> 3
                "MOONSHOT", "REVIVAL" -> 4
                "PUMP_SNIPER", "MICRO_CAP" -> 5
                else -> 3
            }
            
            // Adjust by liquidity
            if (liquidityUsd < 10_000) risk = minOf(risk + 1, 5)
            if (liquidityUsd > 100_000) risk = maxOf(risk - 1, 1)
            
            risk
        } catch (e: Exception) {
            3
        }
    }
    
    private fun Double.fmt(): String = "%.4f".format(this)
    
    /**
     * Clear all state (for testing).
     */
    fun clear() {
        activeDeployments.clear()
        opportunityQueue.clear()
        totalDeployed = 0.0
        totalReturned = 0.0
        deploymentCount = 0
        successCount = 0
    }
}
