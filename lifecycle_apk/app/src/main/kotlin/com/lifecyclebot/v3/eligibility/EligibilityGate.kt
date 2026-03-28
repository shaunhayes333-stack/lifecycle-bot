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
 * V3 C-Grade Looper Tracker
 * 
 * Prevents repeated C-grade proposals from clogging the pipeline.
 * Tracks tokens that have been proposed with:
 *   - quality = C
 *   - confidence < 35
 * 
 * If a token has been proposed 2+ times in the last 5 minutes with these
 * characteristics, it gets blocked from re-proposal until it either:
 *   - Improves to B+ quality
 *   - Confidence rises to 35+
 *   - 5 minutes pass since last proposal
 */
object CGradeLooperTracker {
    private data class ProposalRecord(
        val timestamp: Long,
        val quality: String,
        val confidence: Int
    )
    
    private val recentProposals = mutableMapOf<String, MutableList<ProposalRecord>>()
    private const val WINDOW_MS = 5 * 60 * 1000L  // 5 minute window
    private const val MAX_C_GRADE_PROPOSALS = 2   // Max 2 C-grade proposals per window
    private const val C_GRADE_CONF_THRESHOLD = 35 // Minimum confidence for C-grade
    
    /**
     * Record a proposal for a token
     */
    fun recordProposal(mint: String, quality: String, confidence: Int) {
        val now = System.currentTimeMillis()
        cleanOldRecords(now)
        
        val records = recentProposals.getOrPut(mint) { mutableListOf() }
        records.add(ProposalRecord(now, quality, confidence))
    }
    
    /**
     * Check if a C-grade token should be blocked from re-proposal
     */
    fun shouldBlockCGradeLooper(mint: String, quality: String, confidence: Int): Boolean {
        // Only applies to C-grade with low confidence
        if (quality != "C" || confidence >= C_GRADE_CONF_THRESHOLD) {
            return false
        }
        
        val now = System.currentTimeMillis()
        cleanOldRecords(now)
        
        val records = recentProposals[mint] ?: return false
        
        // Count recent C-grade low-conf proposals
        val recentCGradeCount = records.count { 
            it.quality == "C" && it.confidence < C_GRADE_CONF_THRESHOLD 
        }
        
        return recentCGradeCount >= MAX_C_GRADE_PROPOSALS
    }
    
    private fun cleanOldRecords(now: Long) {
        val cutoff = now - WINDOW_MS
        recentProposals.entries.forEach { (_, records) ->
            records.removeIf { it.timestamp < cutoff }
        }
        recentProposals.entries.removeIf { (_, records) -> records.isEmpty() }
    }
    
    /**
     * Clear all tracking (for testing)
     */
    fun clear() {
        recentProposals.clear()
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
