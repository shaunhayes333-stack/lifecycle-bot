package com.lifecyclebot.engine

/**
 * V5.2: Pipeline Trace Logger
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Provides EXPLICIT logging for every handoff in the entry pipeline:
 *   Scanner → Filter → Queue → Emit → Strategy → FDG → Executor
 * 
 * This reveals the EXACT kill point when entries stall.
 * 
 * LOG FORMAT:
 *   🔗 PIPELINE | [STAGE] | symbol | reason/details
 */
object PipelineTracer {
    
    private const val TAG = "PIPELINE"
    
    // ═══════════════════════════════════════════════════════════════════
    // STAGE 1: SCANNER OUTPUT
    // ═══════════════════════════════════════════════════════════════════
    
    fun scannerFound(symbol: String, mint: String, scanner: String, confidence: Int) {
        ErrorLogger.info(TAG, "🔗 [1_SCANNER] $symbol | scanner=$scanner | conf=$confidence | mint=${mint.take(8)}...")
    }
    
    fun scannerRejected(symbol: String, reason: String, details: String = "") {
        ErrorLogger.debug(TAG, "🔗 [1_SCANNER_REJECT] $symbol | reason=$reason | $details")
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STAGE 2: FILTER/PRECHECK
    // ═══════════════════════════════════════════════════════════════════
    
    fun filterPassed(symbol: String, mint: String, checkType: String) {
        ErrorLogger.debug(TAG, "🔗 [2_FILTER_PASS] $symbol | check=$checkType")
    }
    
    fun filterRejected(symbol: String, mint: String, checkType: String, reason: String) {
        ErrorLogger.info(TAG, "🔗 [2_FILTER_REJECT] $symbol | check=$checkType | reason=$reason")
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STAGE 3: QUEUE/MERGE
    // ═══════════════════════════════════════════════════════════════════
    
    fun queued(symbol: String, mint: String, scanner: String) {
        ErrorLogger.info(TAG, "🔗 [3_QUEUED] $symbol | scanner=$scanner | mint=${mint.take(8)}...")
    }
    
    fun emitted(symbol: String, mint: String, confidence: Int, scanners: List<String>) {
        ErrorLogger.info(TAG, "🔗 [4_EMITTED] $symbol | conf=$confidence | scanners=${scanners.joinToString("+")} | mint=${mint.take(8)}...")
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STAGE 4: WATCHLIST/REGISTRY
    // ═══════════════════════════════════════════════════════════════════
    
    fun registryAdded(symbol: String, mint: String, newSize: Int) {
        ErrorLogger.info(TAG, "🔗 [5_REGISTRY_ADD] $symbol | watchlist_size=$newSize")
    }
    
    fun registryRejected(symbol: String, mint: String, reason: String) {
        ErrorLogger.info(TAG, "🔗 [5_REGISTRY_REJECT] $symbol | reason=$reason")
    }
    
    fun registryDuplicate(symbol: String, mint: String, existsIn: String) {
        ErrorLogger.debug(TAG, "🔗 [5_REGISTRY_DUP] $symbol | exists_in=$existsIn")
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STAGE 5: STRATEGY EVALUATION
    // ═══════════════════════════════════════════════════════════════════
    
    fun strategyStart(symbol: String, mint: String, layer: String) {
        ErrorLogger.info(TAG, "🔗 [6_STRATEGY_START] $symbol | layer=$layer")
    }
    
    fun strategyResult(symbol: String, mint: String, layer: String, shouldEnter: Boolean, score: Int, confidence: Int, reason: String) {
        val status = if (shouldEnter) "ENTRY_SIGNAL" else "NO_ENTRY"
        ErrorLogger.info(TAG, "🔗 [6_STRATEGY_RESULT] $symbol | layer=$layer | $status | score=$score | conf=$confidence | reason=$reason")
    }
    
    fun strategySkipped(symbol: String, mint: String, layer: String, reason: String) {
        ErrorLogger.debug(TAG, "🔗 [6_STRATEGY_SKIP] $symbol | layer=$layer | reason=$reason")
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STAGE 6: FINAL DECISION GATE
    // ═══════════════════════════════════════════════════════════════════
    
    fun fdgStart(symbol: String, mint: String, score: Int, confidence: Int) {
        ErrorLogger.info(TAG, "🔗 [7_FDG_START] $symbol | score=$score | conf=$confidence")
    }
    
    fun fdgApproved(symbol: String, mint: String, quality: String, sizeSol: Double, approvalClass: String) {
        ErrorLogger.info(TAG, "🔗 [7_FDG_APPROVED] $symbol | quality=$quality | size=${sizeSol}SOL | class=$approvalClass")
    }
    
    fun fdgBlocked(symbol: String, mint: String, reason: String, level: String, quality: String, confidence: Int) {
        ErrorLogger.info(TAG, "🔗 [7_FDG_BLOCKED] $symbol | reason=$reason | level=$level | quality=$quality | conf=$confidence")
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STAGE 7: EXECUTOR
    // ═══════════════════════════════════════════════════════════════════
    
    fun executorStart(symbol: String, mint: String, mode: String, sizeSol: Double) {
        ErrorLogger.info(TAG, "🔗 [8_EXECUTOR_START] $symbol | mode=$mode | size=${sizeSol}SOL")
    }
    
    fun executorSuccess(symbol: String, mint: String, mode: String, sizeSol: Double, price: Double) {
        ErrorLogger.info(TAG, "🔗 [8_EXECUTOR_SUCCESS] $symbol | mode=$mode | size=${sizeSol}SOL | price=$price")
    }
    
    fun executorFailed(symbol: String, mint: String, mode: String, reason: String) {
        ErrorLogger.info(TAG, "🔗 [8_EXECUTOR_FAILED] $symbol | mode=$mode | reason=$reason")
    }
    
    fun executorSkipped(symbol: String, mint: String, reason: String) {
        ErrorLogger.info(TAG, "🔗 [8_EXECUTOR_SKIP] $symbol | reason=$reason")
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // NO-BUY EXPLICIT REASONS
    // ═══════════════════════════════════════════════════════════════════
    
    enum class NoBuyReason {
        CONFIDENCE_TOO_LOW,
        SCORE_TOO_LOW,
        ALREADY_WATCHING,
        ALREADY_IN_POSITION,
        WALLET_BALANCE_ZERO,
        PAPER_MODE_DISABLED,
        LIVE_MODE_DISABLED,
        DUPLICATE_MINT,
        COOLDOWN_ACTIVE,
        RUGCHECK_FAIL,
        LIQUIDITY_TOO_LOW,
        ZERO_HOLDERS,
        LAYER_NOT_ENABLED,
        MAX_POSITIONS_REACHED,
        DAILY_LOSS_LIMIT,
        AGGRESSION_BLOCKED,
        UNKNOWN
    }
    
    fun noBuy(symbol: String, mint: String, reason: NoBuyReason, details: String = "") {
        val emoji = when (reason) {
            NoBuyReason.CONFIDENCE_TOO_LOW -> "📉"
            NoBuyReason.SCORE_TOO_LOW -> "📊"
            NoBuyReason.ALREADY_WATCHING -> "👀"
            NoBuyReason.ALREADY_IN_POSITION -> "📍"
            NoBuyReason.WALLET_BALANCE_ZERO -> "💸"
            NoBuyReason.COOLDOWN_ACTIVE -> "⏳"
            NoBuyReason.RUGCHECK_FAIL -> "🚨"
            NoBuyReason.LIQUIDITY_TOO_LOW -> "💧"
            NoBuyReason.ZERO_HOLDERS -> "👥"
            else -> "❌"
        }
        ErrorLogger.info(TAG, "$emoji NO_BUY: $symbol | reason=${reason.name} | $details")
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // LOOP SNAPSHOT (Freeze aggression per loop)
    // ═══════════════════════════════════════════════════════════════════
    
    data class LoopSnapshot(
        val loopId: Int,
        val aggression: Int,
        val paperMode: Boolean,
        val liveMode: Boolean,
        val walletBalance: Double,
        val openPositions: Int,
        val watchlistSize: Int,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private var currentSnapshot: LoopSnapshot? = null
    
    fun startLoop(
        loopId: Int,
        aggression: Int,
        paperMode: Boolean,
        liveMode: Boolean,
        walletBalance: Double,
        openPositions: Int,
        watchlistSize: Int
    ): LoopSnapshot {
        val snapshot = LoopSnapshot(
            loopId = loopId,
            aggression = aggression,
            paperMode = paperMode,
            liveMode = liveMode,
            walletBalance = walletBalance,
            openPositions = openPositions,
            watchlistSize = watchlistSize
        )
        currentSnapshot = snapshot

        // V5.9.495z31 — publish the pipeline's mode to the authority,
        // then assert consistency. If a desync is detected, surface it
        // in the loop log so the operator can see immediately.
        RuntimeModeAuthority.publishPipelineMode(paperMode)
        val consistent = RuntimeModeAuthority.assertConsistentForLoop()

        ErrorLogger.info(TAG, "🔄 LOOP_START #$loopId | aggr=$aggression | " +
            "paper=$paperMode live=$liveMode | bal=${walletBalance}SOL | " +
            "positions=$openPositions | watching=$watchlistSize | " +
            "RUNTIME_MODE_AUTHORITY=${RuntimeModeAuthority.authority()}" +
            if (!consistent) " | ⚠ MODE_DESYNC" else "")

        return snapshot
    }
    
    fun getLoopAggression(): Int = currentSnapshot?.aggression ?: 5
    
    fun getLoopSnapshot(): LoopSnapshot? = currentSnapshot
    
    fun endLoop(loopId: Int, tokensProcessed: Int, entriesAttempted: Int, entriesExecuted: Int) {
        ErrorLogger.info(TAG, "🔄 LOOP_END #$loopId | processed=$tokensProcessed | " +
            "attempted=$entriesAttempted | executed=$entriesExecuted")
        currentSnapshot = null
    }
}
