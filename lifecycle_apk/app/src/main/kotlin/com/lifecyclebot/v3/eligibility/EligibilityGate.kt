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
 * V3 D-Grade Looper Tracker
 *
 * Prevents repeated D-grade proposals from clogging the pipeline.
 * Tracks tokens that have been proposed with:
 *   - quality = D
 *   - confidence < 15
 *
 * If a token has been proposed repeatedly in the last 2 minutes with these
 * characteristics, it gets blocked from re-proposal until either:
 *   - Quality improves above D
 *   - Confidence rises to 15+
 *   - 2 minutes pass since last proposal
 */
object DGradeLooperTracker {

    private data class ProposalRecord(
        val timestamp: Long,
        val quality: String,
        val confidence: Int
    )

    private val recentProposals = mutableMapOf<String, MutableList<ProposalRecord>>()

    private const val WINDOW_MS = 2 * 60 * 1000L          // 2 minute window
    private const val MAX_D_GRADE_PROPOSALS = 10          // Max D-grade low-conf proposals per window
    private const val D_GRADE_CONF_THRESHOLD = 15         // D-grade looper threshold

    /**
     * Record a proposal for a token.
     */
    fun recordProposal(mint: String, quality: String, confidence: Int) {
        val now = System.currentTimeMillis()
        cleanOldRecords(now)

        val records = recentProposals.getOrPut(mint) { mutableListOf() }
        records.add(
            ProposalRecord(
                timestamp = now,
                quality = quality,
                confidence = confidence
            )
        )
    }

    /**
     * Check if a D-grade token should be blocked from re-proposal.
     */
    fun shouldBlockDGradeLooper(mint: String, quality: String, confidence: Int): Boolean {
        // Only applies to D-grade with low confidence
        if (quality != "D" || confidence >= D_GRADE_CONF_THRESHOLD) {
            return false
        }

        val now = System.currentTimeMillis()
        cleanOldRecords(now)

        val records = recentProposals[mint] ?: return false

        val recentDGradeCount = records.count {
            it.quality == "D" && it.confidence < D_GRADE_CONF_THRESHOLD
        }

        return recentDGradeCount >= MAX_D_GRADE_PROPOSALS
    }

    /**
     * Backward-compatible alias in case other files still call the old method name.
     */
    fun shouldBlockCGradeLooper(mint: String, quality: String, confidence: Int): Boolean {
        return shouldBlockDGradeLooper(mint, quality, confidence)
    }

    private fun cleanOldRecords(now: Long) {
        val cutoff = now - WINDOW_MS

        recentProposals.entries.forEach { (_, records) ->
            records.removeIf { it.timestamp < cutoff }
        }

        recentProposals.entries.removeIf { (_, records) ->
            records.isEmpty()
        }
    }

    /**
     * Clear all tracking (for testing)
     */
    fun clear() {
        recentProposals.clear()
    }
}

/**
 * Backward-compatible alias in case the rest of the codebase still references
 * the old object name.
 */
typealias CGradeLooperTracker = DGradeLooperTracker

/**
 * V3 Exposure Guard
 */
class ExposureGuard(
    private val maxOpenPositions: Int = 5,
    private val maxExposurePct: Double = 0.70
) {
    private val openMints = mutableSetOf<String>()

    var currentExposurePct: Double = 0.0

    fun openPosition(mint: String) {
        openMints += mint
    }

    fun closePosition(mint: String) {
        openMints -= mint
    }

    fun isTokenAlreadyOpen(mint: String): Boolean = mint in openMints

    fun isGlobalExposureMaxed(): Boolean {
        return openMints.size >= maxOpenPositions || currentExposurePct >= maxExposurePct
    }

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