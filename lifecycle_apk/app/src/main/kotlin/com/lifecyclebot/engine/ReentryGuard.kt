package com.lifecyclebot.engine

/**
 * ReentryGuard — Hard Re-Entry Lock for Bad Tokens
 * 
 * PROBLEM: Score penalties affect position SIZE, not ELIGIBILITY.
 * A token with collapse, distribution, or stop-loss can still be re-entered,
 * which pollutes learning even in paper mode.
 * 
 * SOLUTION: Hard time-based lockout that blocks ALL re-entry attempts.
 * 
 * Lockout triggers:
 *   - LIQUIDITY_COLLAPSE: 60 min lockout
 *   - DISTRIBUTION detected: 45 min lockout
 *   - STOP_LOSS hit: 30 min lockout
 *   - Multiple losses (2+ in 30min): 30 min lockout
 *   - Memory score below floor: 20 min lockout
 * 
 * The token can be SHADOW-TRACKED but NOT re-entered until:
 *   - Lockout expires, OR
 *   - Full reset condition is met (manual clear or 24h total reset)
 */
object ReentryGuard {
    
    // ═══════════════════════════════════════════════════════════════════
    // CONFIGURATION - Aggressive timings for fast iteration
    // ═══════════════════════════════════════════════════════════════════
    
    private const val COLLAPSE_LOCKOUT_MS = 5 * 60 * 1000L        // 5 min (was 60 min)
    private const val DISTRIBUTION_LOCKOUT_MS = 3 * 60 * 1000L    // 3 min (was 45 min)
    private const val STOP_LOSS_LOCKOUT_MS = 2 * 60 * 1000L       // 2 min (was 30 min)
    private const val MULTI_LOSS_LOCKOUT_MS = 2 * 60 * 1000L      // 2 min (was 30 min)
    private const val BAD_MEMORY_LOCKOUT_MS = 1 * 60 * 1000L      // 1 min (was 20 min)
    private const val GLOBAL_RESET_HOURS = 24                      // Full reset after 24h
    
    // Memory score floor - below this = hard block
    // V5.9.266: moderate (was -2.0 at V5.9.263)
    private const val MEMORY_SCORE_FLOOR = -1.0
    
    // ═══════════════════════════════════════════════════════════════════
    // LOCKOUT TRACKING
    // ═══════════════════════════════════════════════════════════════════
    
    enum class LockoutReason {
        LIQUIDITY_COLLAPSE,
        DISTRIBUTION_DETECTED,
        STOP_LOSS_HIT,
        MULTIPLE_LOSSES,
        BAD_MEMORY_SCORE,
        MANUAL_BLOCK,
    }
    
    data class LockoutEntry(
        val reason: LockoutReason,
        val lockedAt: Long,
        val expiresAt: Long,
        val lossCount: Int = 0,
        val lastPnlPct: Double = 0.0,
    )
    
    // mint -> lockout entry
    private val lockouts = mutableMapOf<String, LockoutEntry>()
    
    // Track recent losses per token for multi-loss detection
    private val recentLosses = mutableMapOf<String, MutableList<Long>>()

    // V5.9.175 — second-moon re-entry bypass. When a trade exits as a WIN,
    // stamp a "hot" mark. For the next SECOND_MOON_WINDOW_MS the discovery
    // pipeline and ReentryGuard treat this token as eligible for an
    // immediate re-scan — letting the bot ride continuation runs instead
    // of being locked out by cooldowns that were written for losers.
    private const val SECOND_MOON_WINDOW_MS = 10 * 60 * 1000L   // 10 minutes
    private val secondMoonHot = mutableMapOf<String, Long>()    // mint -> expiresAt

    // ═══════════════════════════════════════════════════════════════════
    // MAIN API: Check if token is blocked
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Returns true if token is BLOCKED from re-entry.
     * This is a HARD block - no exceptions, no score overrides.
     *
     * V5.9.175: if this token is marked "second-moon hot" (just exited a
     * winning trade inside the 10-min window), the block is bypassed once
     * so the bot can chase continuation.
     */
    fun isBlocked(mint: String): Boolean {
        // V5.9.704 — graduated air control: reentry cooldowns activate at
        // guardLevel >= 2 (1000+ lifetime sells). Below 1000 trades the bot
        // is in early learning and must not be gated by reentry lockouts.
        if (FreeRangeMode.guardLevel() < 2) return false
        cleanupExpired()
        if (isSecondMoonHot(mint)) return false
        return lockouts[mint]?.let { entry ->
            System.currentTimeMillis() < entry.expiresAt
        } ?: false
    }

    /**
     * V5.9.175 — call this from the close path whenever a trade exits with
     * positive PnL. Opens a short re-entry window that bypasses cooldowns.
     */
    fun markSecondMoonCandidate(mint: String, pnlPct: Double) {
        if (pnlPct <= 0.0 || mint.isBlank()) return
        secondMoonHot[mint] = System.currentTimeMillis() + SECOND_MOON_WINDOW_MS
    }

    /**
     * V5.9.175 — consumed by discovery/scan pipelines to bypass cooldowns.
     * Side-effect free: checking does NOT burn the window; only isBlocked()
     * uses it to allow a re-entry attempt.
     */
    fun isSecondMoonHot(mint: String): Boolean {
        val exp = secondMoonHot[mint] ?: return false
        if (System.currentTimeMillis() > exp) {
            secondMoonHot.remove(mint)
            return false
        }
        return true
    }
    
    /**
     * Get lockout info for logging/UI
     */
    fun getLockoutInfo(mint: String): LockoutEntry? {
        cleanupExpired()
        return lockouts[mint]
    }
    
    /**
     * Get remaining lockout time in minutes
     */
    fun getRemainingMinutes(mint: String): Int {
        val entry = lockouts[mint] ?: return 0
        val remaining = entry.expiresAt - System.currentTimeMillis()
        return if (remaining > 0) (remaining / 60000).toInt() else 0
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // LOCKOUT TRIGGERS
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Call when liquidity collapse is detected
     */
    fun onLiquidityCollapse(mint: String) {
        addLockout(mint, LockoutReason.LIQUIDITY_COLLAPSE, COLLAPSE_LOCKOUT_MS)
    }
    
    /**
     * Call when distribution pattern is detected (whale selling)
     */
    fun onDistributionDetected(mint: String) {
        addLockout(mint, LockoutReason.DISTRIBUTION_DETECTED, DISTRIBUTION_LOCKOUT_MS)
    }
    
    /**
     * Call when stop-loss is hit on a position
     */
    fun onStopLossHit(mint: String, pnlPct: Double) {
        addLockout(mint, LockoutReason.STOP_LOSS_HIT, STOP_LOSS_LOCKOUT_MS, pnlPct = pnlPct)
    }
    
    /**
     * Call when a trade completes with a loss
     * Tracks multiple losses for escalating lockout
     */
    fun onTradeLoss(mint: String, pnlPct: Double) {
        val now = System.currentTimeMillis()
        val thirtyMinAgo = now - 30 * 60 * 1000L
        
        // Track this loss
        val losses = recentLosses.getOrPut(mint) { mutableListOf() }
        losses.add(now)
        
        // Clean old losses
        losses.removeAll { it < thirtyMinAgo }
        
        // If 2+ losses in 30 min, apply multi-loss lockout
        if (losses.size >= 2) {
            addLockout(mint, LockoutReason.MULTIPLE_LOSSES, MULTI_LOSS_LOCKOUT_MS, 
                       lossCount = losses.size, pnlPct = pnlPct)
        }
    }
    
    /**
     * Call when memory score drops below floor
     */
    fun onBadMemoryScore(mint: String, score: Double) {
        if (score < MEMORY_SCORE_FLOOR) {
            addLockout(mint, LockoutReason.BAD_MEMORY_SCORE, BAD_MEMORY_LOCKOUT_MS)
        }
    }
    
    /**
     * Manual block (e.g., user blacklist, scam detection)
     */
    fun manualBlock(mint: String, durationMs: Long = 60 * 60 * 1000L) {
        addLockout(mint, LockoutReason.MANUAL_BLOCK, durationMs)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // LOCKOUT MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════
    
    private fun addLockout(
        mint: String, 
        reason: LockoutReason, 
        durationMs: Long,
        lossCount: Int = 0,
        pnlPct: Double = 0.0,
    ) {
        val now = System.currentTimeMillis()
        val existing = lockouts[mint]
        
        // If already locked, extend if new lockout is longer
        val newExpiry = now + durationMs
        if (existing != null && existing.expiresAt > newExpiry) {
            // Existing lockout is longer, keep it but update reason if more severe
            val severity = mapOf(
                LockoutReason.LIQUIDITY_COLLAPSE to 5,
                LockoutReason.DISTRIBUTION_DETECTED to 4,
                LockoutReason.STOP_LOSS_HIT to 3,
                LockoutReason.MULTIPLE_LOSSES to 2,
                LockoutReason.BAD_MEMORY_SCORE to 1,
                LockoutReason.MANUAL_BLOCK to 6,
            )
            if ((severity[reason] ?: 0) > (severity[existing.reason] ?: 0)) {
                lockouts[mint] = existing.copy(reason = reason)
            }
            return
        }
        
        lockouts[mint] = LockoutEntry(
            reason = reason,
            lockedAt = now,
            expiresAt = newExpiry,
            lossCount = lossCount,
            lastPnlPct = pnlPct,
        )
    }
    
    /**
     * Manually clear a lockout (reset condition met)
     */
    fun clearLockout(mint: String) {
        lockouts.remove(mint)
        recentLosses.remove(mint)
    }
    
    /**
     * Clear all lockouts (daily reset)
     */
    fun clearAll() {
        lockouts.clear()
        recentLosses.clear()
    }
    
    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        lockouts.entries.removeAll { it.value.expiresAt < now }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STATS & LOGGING
    // ═══════════════════════════════════════════════════════════════════
    
    fun getBlockedCount(): Int {
        cleanupExpired()
        return lockouts.size
    }
    
    fun getBlockedMints(): List<String> {
        cleanupExpired()
        return lockouts.keys.toList()
    }
    
    fun getStats(): Map<String, Any> {
        cleanupExpired()
        val byReason = lockouts.values.groupBy { it.reason }
        return mapOf(
            "totalBlocked" to lockouts.size,
            "byReason" to byReason.mapValues { it.value.size },
            "blockedMints" to lockouts.keys.take(10).toList(),
        )
    }
    
    /**
     * Format lockout for logging
     */
    fun formatLockout(mint: String): String {
        val entry = lockouts[mint] ?: return "not locked"
        val remaining = getRemainingMinutes(mint)
        return "${entry.reason.name} (${remaining}m remaining)"
    }
}
