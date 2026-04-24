package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TreasuryOpportunityEngine — Active treasury deployment for compound growth.
 *
 * Hardened version:
 * - Atomic deployment checks
 * - Prevents duplicate treasury deployment on same mint
 * - Stricter stale/cooldown/concurrency handling
 * - Safer stats/state management
 *
 * NOTE:
 * This protects Treasury's own book only.
 * If you want to stop cross-layer duplicate buys entirely, the caller must still
 * enforce a global "mint already active anywhere" gate before calling recordDeployment().
 */
object TreasuryOpportunityEngine {

    private const val TAG = "TreasuryOpp"

    data class DeploymentConfig(
        val maxDeployPct: Double = 20.0,           // Max % of treasury to deploy at once
        val maxConcurrentDeploys: Int = 3,         // Max simultaneous treasury positions
        val minTreasuryToStart: Double = 0.5,      // Min treasury SOL before deploying
        val minConfidenceScore: Double = 25.0,     // V5.9.186: was 5 — treasury needs quality (25+)
        val minEntryScore: Double = 30.0,          // V5.9.186: was 5 — treasury quality gate (30+)
        val minLiquidityUsd: Double = 1_000.0,     // V5.9.180: was 5_000 — learn broadly
        val opportunityTtlMs: Long = 60_000L,      // Opportunity becomes stale after 60s
        val targetModes: Set<String> = setOf(
            "MOONSHOT", "PUMP_SNIPER", "MICRO_CAP", "REVIVAL"
        ),
        val cooldownMs: Long = 60_000L,            // 1 min cooldown between deploys
    )

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

    private var config = DeploymentConfig()

    // Treasury book only: one active deployment per mint
    private val activeDeployments = ConcurrentHashMap<String, TreasuryDeployment>()
    private val opportunityQueue = ConcurrentHashMap<String, Opportunity>()

    @Volatile private var lastDeployTimeMs: Long = 0L
    @Volatile private var totalDeployed: Double = 0.0
    @Volatile private var totalReturned: Double = 0.0
    @Volatile private var deploymentCount: Int = 0
    @Volatile private var successCount: Int = 0

    private val enabled = AtomicBoolean(false)
    private val deployLock = Any()

    fun setEnabled(enabled: Boolean) {
        this.enabled.set(enabled)
        ErrorLogger.info(TAG, "Treasury opportunity engine ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    fun isEnabled(): Boolean = enabled.get()

    fun updateConfig(newConfig: DeploymentConfig) {
        config = newConfig
        ErrorLogger.info(
            TAG,
            "Config updated | maxDeploy=${config.maxDeployPct}% maxConcurrent=${config.maxConcurrentDeploys} minTreasury=${config.minTreasuryToStart.fmt()}◎"
        )
    }

    fun assessOpportunity(
        mint: String,
        symbol: String,
        entryScore: Double,
        confidence: Double,
        mode: String,
        liquidityUsd: Double,
        mcapUsd: Double,
    ): Opportunity? {
        if (!enabled.get()) return null

        return try {
            if (mint.isBlank() || symbol.isBlank()) return null
            if (mode !in config.targetModes) return null
            if (confidence < config.minConfidenceScore) return null
            if (entryScore < config.minEntryScore) return null
            if (liquidityUsd < config.minLiquidityUsd) return null

            val treasuryAvailable = TreasuryManager.treasurySol
            if (treasuryAvailable < config.minTreasuryToStart) return null

            val expectedReturn = estimateReturn(mode, entryScore, confidence, mcapUsd)
            val riskLevel = calculateRiskLevel(mode, liquidityUsd, mcapUsd)

            val maxDeploy = treasuryAvailable * (config.maxDeployPct / 100.0)
            val recommendedSize = when (riskLevel) {
                1, 2 -> maxDeploy
                3 -> maxDeploy * 0.7
                4 -> maxDeploy * 0.5
                else -> maxDeploy * 0.3
            }.coerceAtLeast(0.01)

            val opp = Opportunity(
                mint = mint,
                symbol = symbol,
                confidence = confidence,
                expectedReturnPct = expectedReturn,
                riskLevel = riskLevel,
                mode = mode,
                reason = "High confidence $mode setup with ${entryScore.toInt()} entry score",
                recommendedSizeSol = recommendedSize,
            )

            opportunityQueue[mint] = opp

            ErrorLogger.info(
                TAG,
                "💎 Opportunity: $symbol | mode=$mode conf=${confidence.toInt()}% entry=${entryScore.toInt()} liq=$${liquidityUsd.toInt()} exp=${expectedReturn.toInt()}% risk=$riskLevel size=${recommendedSize.fmt()}◎"
            )

            opp
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "assessOpportunity error: ${e.message}")
            null
        }
    }

    fun shouldDeploy(opportunity: Opportunity): Boolean {
        if (!enabled.get()) return false

        return try {
            val now = System.currentTimeMillis()
            val treasury = TreasuryManager.treasurySol
            val activeCount = activeDeployments.values.count { it.isActive }
            val elapsed = now - lastDeployTimeMs
            val age = now - opportunity.timestampMs

            when {
                treasury < config.minTreasuryToStart -> {
                    ErrorLogger.debug(TAG, "Treasury too low: ${treasury.fmt()}◎ < ${config.minTreasuryToStart.fmt()}◎")
                    false
                }
                activeCount >= config.maxConcurrentDeploys -> {
                    ErrorLogger.debug(TAG, "Max deployments reached: $activeCount/${config.maxConcurrentDeploys}")
                    false
                }
                elapsed < config.cooldownMs -> {
                    ErrorLogger.debug(TAG, "Cooldown active: ${elapsed / 1000}s < ${config.cooldownMs / 1000}s")
                    false
                }
                activeDeployments[opportunity.mint]?.isActive == true -> {
                    ErrorLogger.debug(TAG, "Already active in treasury: ${opportunity.symbol}")
                    false
                }
                age > config.opportunityTtlMs -> {
                    ErrorLogger.debug(TAG, "Opportunity stale: ${opportunity.symbol} age=${age}ms")
                    false
                }
                else -> true
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "shouldDeploy error: ${e.message}")
            false
        }
    }

    /**
     * Atomic version of deployment recording.
     * Returns true only if treasury deployment was successfully reserved + recorded.
     *
     * Caller should prefer:
     *  1. assessOpportunity(...)
     *  2. shouldDeploy(...)
     *  3. recordDeployment(...)
     *
     * This method hardens step 3 against races and duplicates.
     */
    fun recordDeployment(
        mint: String,
        symbol: String,
        deploySol: Double,
        entryPrice: Double,
        mode: String,
    ): Boolean {
        if (!enabled.get()) return false

        return try {
            synchronized(deployLock) {
                val now = System.currentTimeMillis()

                if (mint.isBlank() || symbol.isBlank()) {
                    ErrorLogger.warn(TAG, "recordDeployment blocked: blank mint/symbol")
                    return false
                }

                if (deploySol <= 0.0 || entryPrice <= 0.0) {
                    ErrorLogger.warn(TAG, "recordDeployment blocked: invalid deploySol=$deploySol entryPrice=$entryPrice")
                    return false
                }

                if (activeDeployments[mint]?.isActive == true) {
                    ErrorLogger.warn(TAG, "recordDeployment blocked: duplicate active treasury deployment for $symbol")
                    return false
                }

                val treasury = TreasuryManager.treasurySol
                if (treasury < config.minTreasuryToStart) {
                    ErrorLogger.warn(TAG, "recordDeployment blocked: treasury ${treasury.fmt()}◎ below minimum")
                    return false
                }

                val activeCount = activeDeployments.values.count { it.isActive }
                if (activeCount >= config.maxConcurrentDeploys) {
                    ErrorLogger.warn(TAG, "recordDeployment blocked: max concurrent treasury deployments reached")
                    return false
                }

                val elapsed = now - lastDeployTimeMs
                if (elapsed < config.cooldownMs) {
                    ErrorLogger.warn(TAG, "recordDeployment blocked: cooldown ${elapsed}ms < ${config.cooldownMs}ms")
                    return false
                }

                val maxDeployNow = treasury * (config.maxDeployPct / 100.0)
                if (deploySol > maxDeployNow + 1e-9) {
                    ErrorLogger.warn(
                        TAG,
                        "recordDeployment blocked: deploy ${deploySol.fmt()}◎ exceeds treasury cap ${maxDeployNow.fmt()}◎"
                    )
                    return false
                }

                val deployment = TreasuryDeployment(
                    mint = mint,
                    symbol = symbol,
                    deploySol = deploySol,
                    entryPrice = entryPrice,
                    mode = mode,
                    startTimeMs = now,
                )

                activeDeployments[mint] = deployment
                opportunityQueue.remove(mint)

                lastDeployTimeMs = now
                totalDeployed += deploySol
                deploymentCount++

                ErrorLogger.info(
                    TAG,
                    "💎 DEPLOYED: $symbol | ${deploySol.fmt()}◎ | entry=${entryPrice.fmtPrice()} | mode=$mode | active=${activeDeployments.values.count { it.isActive }}"
                )

                true
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordDeployment error: ${e.message}")
            false
        }
    }

    fun updateDeploymentPnl(mint: String, currentPrice: Double) {
        try {
            if (currentPrice <= 0.0) return

            val dep = activeDeployments[mint] ?: return
            if (!dep.isActive) return

            val pnlPct = if (dep.entryPrice > 0.0) {
                ((currentPrice - dep.entryPrice) / dep.entryPrice) * 100.0
            } else {
                0.0
            }

            dep.currentPnlPct = pnlPct
            if (pnlPct > dep.peakPnlPct) {
                dep.peakPnlPct = pnlPct
            }
        } catch (_: Exception) {
        }
    }

    fun closeDeployment(mint: String, returnSol: Double, pnlPct: Double) {
        try {
            synchronized(deployLock) {
                val dep = activeDeployments[mint] ?: return
                if (!dep.isActive) {
                    activeDeployments.remove(mint)
                    return
                }

                dep.isActive = false
                totalReturned += returnSol

                when {
                    pnlPct > 5.0 -> {
                        successCount++
                        ErrorLogger.info(
                            TAG,
                            "💎 SUCCESS: ${dep.symbol} | pnl=${pnlPct.toInt()}% | returned=${returnSol.fmt()}◎ | peak=${dep.peakPnlPct.toInt()}%"
                        )
                    }
                    pnlPct < -5.0 -> {
                        ErrorLogger.info(
                            TAG,
                            "💎 LOSS: ${dep.symbol} | pnl=${pnlPct.toInt()}% | returned=${returnSol.fmt()}◎"
                        )
                    }
                    else -> {
                        ErrorLogger.info(
                            TAG,
                            "💎 CLOSED: ${dep.symbol} | pnl=${pnlPct.toInt()}% | returned=${returnSol.fmt()}◎"
                        )
                    }
                }

                activeDeployments.remove(mint)
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "closeDeployment error: ${e.message}")
        }
    }

    fun getStats(): DeploymentStats {
        return try {
            val active = activeDeployments.values.filter { it.isActive }
            val activeCount = active.size
            val activePnl = active.sumOf { it.currentPnlPct }

            DeploymentStats(
                totalDeployed = totalDeployed,
                totalReturned = totalReturned,
                netPnlSol = totalReturned - totalDeployed,
                deploymentCount = deploymentCount,
                successCount = successCount,
                winRate = if (deploymentCount > 0) {
                    successCount.toDouble() / deploymentCount.toDouble() * 100.0
                } else {
                    0.0
                },
                activeDeployments = activeCount,
                activePnlPct = if (activeCount > 0) activePnl / activeCount else 0.0,
                queueSize = opportunityQueue.size,
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "getStats error: ${e.message}")
            DeploymentStats()
        }
    }

    fun getActiveDeployments(): List<TreasuryDeployment> {
        return try {
            activeDeployments.values
                .filter { it.isActive }
                .sortedByDescending { it.startTimeMs }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getPendingOpportunities(): List<Opportunity> {
        return try {
            opportunityQueue.values
                .sortedByDescending { it.confidence }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun estimateReturn(
        mode: String,
        entryScore: Double,
        confidence: Double,
        mcapUsd: Double,
    ): Double {
        return try {
            val baseReturn = when (mode) {
                "MOONSHOT" -> 100.0
                "PUMP_SNIPER" -> 50.0
                "MICRO_CAP" -> 150.0
                "REVIVAL" -> 80.0
                else -> 30.0
            }

            val scoreMultiplier = (entryScore / 100.0).coerceIn(0.1, 1.5)
            val confMultiplier = (confidence / 100.0).coerceIn(0.1, 1.5)

            val mcapMultiplier = when {
                mcapUsd < 20_000 -> 1.5
                mcapUsd < 100_000 -> 1.2
                mcapUsd < 500_000 -> 1.0
                else -> 0.8
            }

            (baseReturn * scoreMultiplier * confMultiplier * mcapMultiplier).coerceIn(10.0, 500.0)
        } catch (_: Exception) {
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

            if (liquidityUsd < 10_000.0) risk = minOf(risk + 1, 5)
            if (liquidityUsd > 100_000.0) risk = maxOf(risk - 1, 1)
            if (mcapUsd < 20_000.0) risk = minOf(risk + 1, 5)

            risk
        } catch (_: Exception) {
            3
        }
    }

    private fun Double.fmt(): String = "%.4f".format(this)
    private fun Double.fmtPrice(): String = "%.8f".format(this)

    fun clear() {
        synchronized(deployLock) {
            activeDeployments.clear()
            opportunityQueue.clear()
            lastDeployTimeMs = 0L
            totalDeployed = 0.0
            totalReturned = 0.0
            deploymentCount = 0
            successCount = 0
        }
        ErrorLogger.info(TAG, "Treasury opportunity engine state cleared")
    }
}