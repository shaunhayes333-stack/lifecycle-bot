package com.lifecyclebot.v3.eligibility

import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.scanner.CandidateSnapshot

/**
 * V3 Eligibility Result
 */
data class EligibilityResult(
    val passed: Boolean,
    val reason: String
) {
    companion object {
        fun pass() = EligibilityResult(true, "PASS")
        fun fail(reason: String) = EligibilityResult(false, reason)
    }
}

/**
 * V3 Cooldown Manager
 */
class CooldownManager {
    private val cooldowns = mutableMapOf<String, Long>()
    
    fun setCooldown(mint: String, untilMs: Long) {
        cooldowns[mint] = untilMs
    }
    
    fun isCoolingDown(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = cooldowns[mint] ?: return false
        return nowMs < until
    }
    
    fun clearExpired(nowMs: Long = System.currentTimeMillis()) {
        cooldowns.entries.removeIf { it.value < nowMs }
    }
}

/**
 * V3 Exposure Guard
 */
class ExposureGuard(
    private val maxOpenPositions: Int = 5,
    private val maxExposurePct: Double = 0.70
) {
    private val openMints = mutableSetOf<String>()
    var currentExposurePct: Double = 0.0
    
    fun openPosition(mint: String) { openMints += mint }
    fun closePosition(mint: String) { openMints -= mint }
    fun isTokenAlreadyOpen(mint: String): Boolean = mint in openMints
    fun isGlobalExposureMaxed(): Boolean = 
        openMints.size >= maxOpenPositions || currentExposurePct >= maxExposurePct
    fun openCount(): Int = openMints.size
}

/**
 * V3 Eligibility Gate
 * ONLY hard blocks - everything else is scoring
 */
class EligibilityGate(
    private val config: TradingConfigV3,
    private val cooldownManager: CooldownManager,
    private val exposureGuard: ExposureGuard
) {
    fun evaluate(candidate: CandidateSnapshot): EligibilityResult {
        // Zero liquidity = can't trade
        if (candidate.liquidityUsd <= 0.0) {
            return EligibilityResult.fail("ZERO_LIQUIDITY")
        }
        
        // Too old = stale opportunity
        if (candidate.ageMinutes > config.maxTokenAgeMinutes) {
            return EligibilityResult.fail("TOO_OLD")
        }
        
        // Below min liquidity = too risky
        if (candidate.liquidityUsd < config.minLiquidityUsd) {
            return EligibilityResult.fail("LOW_LIQUIDITY")
        }
        
        // On cooldown = wait
        if (cooldownManager.isCoolingDown(candidate.mint)) {
            return EligibilityResult.fail("COOLDOWN")
        }
        
        // Already open = no stacking
        if (exposureGuard.isTokenAlreadyOpen(candidate.mint)) {
            return EligibilityResult.fail("ALREADY_OPEN")
        }
        
        // Global exposure maxed
        if (exposureGuard.isGlobalExposureMaxed()) {
            return EligibilityResult.fail("GLOBAL_EXPOSURE_MAX")
        }
        
        return EligibilityResult.pass()
    }
}
