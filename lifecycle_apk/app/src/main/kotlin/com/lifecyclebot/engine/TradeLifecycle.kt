package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * TradeLifecycle - Formal state machine for trade progression
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Every token progresses through these states:
 * 
 *   DISCOVERED ──► ELIGIBLE ──► CANDIDATE ──► PROPOSED
 *        │              │            │            │
 *        ▼              ▼            ▼            ▼
 *   [rejected]    [filtered]   [no signal]   FDG_BLOCKED
 *                                                 │
 *                                            FDG_APPROVED
 *                                                 │
 *                                              SIZED
 *                                                 │
 *                                             EXECUTED
 *                                                 │
 *                                            MONITORING
 *                                                 │
 *                                              CLOSED
 *                                                 │
 *                                            CLASSIFIED
 * 
 * Each state transition is logged for debugging and learning.
 */
object TradeLifecycle {
    
    /**
     * Lifecycle states a token can be in
     * 
     * DISCOVERY FUNNEL (3-tier filter to reduce noise):
     *   DISCOVERED = seen by scanner (raw count)
     *   ELIGIBLE = passes minimum liquidity + safety prerequisites
     *   WATCHLISTED = actually admitted for strategy evaluation
     */
    enum class State {
        // ── Discovery Phase (3-tier funnel) ──
        DISCOVERED,      // Scanner found the token (raw, unfiltered)
        ELIGIBLE,        // Passed minimum prereqs (liq >= $3k, not banned, basic safety)
        WATCHLISTED,     // Admitted to watchlist for active strategy evaluation
        
        // ── Evaluation Phase ──
        CANDIDATE,       // Strategy generated a BUY signal
        PROPOSED,        // Ready for FDG evaluation
        
        // ── Decision Phase ──
        FDG_BLOCKED,     // FDG rejected the trade
        FDG_APPROVED,    // FDG approved the trade
        
        // ── Execution Phase ──
        SIZED,           // Position size calculated
        EXECUTED,        // Trade executed (bought)
        
        // ── Monitoring Phase ──
        MONITORING,      // Position open, watching for exit
        
        // ── Completion Phase ──
        CLOSED,          // Position closed (sold)
        CLASSIFIED,      // Learning classification complete
        
        // ── Terminal States ──
        REJECTED,        // Rejected during discovery (banned, etc.)
        FILTERED,        // Filtered out (didn't meet criteria)
        INELIGIBLE,      // Failed eligibility prereqs (liq too low, unsafe)
        NO_SIGNAL,       // Strategy didn't generate BUY signal
        EXPIRED,         // Watchlist timeout
    }
    
    /**
     * State transition record for audit trail
     */
    data class Transition(
        val timestamp: Long = System.currentTimeMillis(),
        val fromState: State?,
        val toState: State,
        val reason: String,
        val metadata: Map<String, Any> = emptyMap(),
    )
    
    /**
     * Token lifecycle tracker
     */
    data class Lifecycle(
        val mint: String,
        val symbol: String,
        var currentState: State = State.DISCOVERED,
        val transitions: MutableList<Transition> = mutableListOf(),
        val startTime: Long = System.currentTimeMillis(),
        var endTime: Long? = null,
        
        // Key metrics at each stage
        var discoveryScore: Double = 0.0,
        var eligibilityScore: Double = 0.0,
        var candidateScore: Double = 0.0,
        var fdgDecision: String? = null,
        var sizeSol: Double = 0.0,
        var entryPrice: Double = 0.0,
        var exitPrice: Double = 0.0,
        var pnlPct: Double = 0.0,
        var classification: String? = null,
    ) {
        fun transition(newState: State, reason: String, metadata: Map<String, Any> = emptyMap()) {
            transitions.add(Transition(
                fromState = currentState,
                toState = newState,
                reason = reason,
                metadata = metadata,
            ))
            currentState = newState
            
            if (newState in listOf(State.CLASSIFIED, State.REJECTED, State.FILTERED, State.NO_SIGNAL, State.EXPIRED)) {
                endTime = System.currentTimeMillis()
            }
        }
        
        fun durationMs(): Long = (endTime ?: System.currentTimeMillis()) - startTime
        
        fun summary(): String = buildString {
            append("[$symbol] $currentState")
            append(" | ${transitions.size} transitions")
            append(" | ${durationMs() / 1000}s")
            if (pnlPct != 0.0) append(" | PnL: ${pnlPct.toInt()}%")
            if (classification != null) append(" | $classification")
        }
        
        fun fullHistory(): String = buildString {
            appendLine("═══ LIFECYCLE: $symbol ($mint) ═══")
            appendLine("Duration: ${durationMs() / 1000}s")
            appendLine("Final State: $currentState")
            appendLine("")
            appendLine("TRANSITIONS:")
            transitions.forEachIndexed { i, t ->
                val time = (t.timestamp - startTime) / 1000
                appendLine("  ${i+1}. [+${time}s] ${t.fromState ?: "START"} → ${t.toState}")
                appendLine("      Reason: ${t.reason}")
                if (t.metadata.isNotEmpty()) {
                    appendLine("      Data: ${t.metadata}")
                }
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val lifecycles = mutableMapOf<String, Lifecycle>()
    private const val MAX_TRACKED = 500  // Limit memory usage
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROPOSAL DEDUPE — Prevent duplicate proposal/approval spam
    // ═══════════════════════════════════════════════════════════════════════════
    
    // mint -> (lastProposalTime, lastApprovalTime, proposalCount)
    private data class ProposalTracker(
        var lastProposalMs: Long = 0,
        var lastApprovalMs: Long = 0,
        var proposalCount: Int = 0,
        var approvalCount: Int = 0,
    )
    
    private val proposalTrackers = mutableMapOf<String, ProposalTracker>()
    private const val MIN_PROPOSAL_INTERVAL_MS = 60_000L  // 60 seconds between proposals (prevents spam)
    private const val MIN_APPROVAL_INTERVAL_MS = 60_000L  // 60 seconds between approvals
    private const val MAX_PROPOSALS_PER_WINDOW = 3        // Max 3 proposals in 5 min window
    private const val PROPOSAL_WINDOW_MS = 5 * 60_000L    // 5 min window
    
    /**
     * Check if a proposal should be allowed (dedupe check)
     * Returns: Pair(allowed: Boolean, reason: String?)
     */
    fun canPropose(mint: String): Pair<Boolean, String?> {
        val now = System.currentTimeMillis()
        val tracker = proposalTrackers.getOrPut(mint) { ProposalTracker() }
        
        // Reset counts if window expired
        if (now - tracker.lastProposalMs > PROPOSAL_WINDOW_MS) {
            tracker.proposalCount = 0
        }
        
        // Check 1: Too soon since last proposal?
        if (now - tracker.lastProposalMs < MIN_PROPOSAL_INTERVAL_MS) {
            val waitSec = (MIN_PROPOSAL_INTERVAL_MS - (now - tracker.lastProposalMs)) / 1000
            return false to "DEDUPE: Proposal too soon, wait ${waitSec}s"
        }
        
        // Check 2: Too many proposals in window?
        if (tracker.proposalCount >= MAX_PROPOSALS_PER_WINDOW) {
            return false to "DEDUPE: Max $MAX_PROPOSALS_PER_WINDOW proposals in ${PROPOSAL_WINDOW_MS/60000}min"
        }
        
        // Check 3: Already in active state (PROPOSED, FDG_APPROVED, EXECUTED, MONITORING)?
        val lc = lifecycles[mint]
        if (lc != null && lc.currentState in listOf(
            State.PROPOSED, State.FDG_APPROVED, State.SIZED, State.EXECUTED, State.MONITORING
        )) {
            return false to "DEDUPE: Already in state ${lc.currentState}"
        }
        
        return true to null
    }
    
    /**
     * Check if an approval should be allowed (dedupe check)
     */
    fun canApprove(mint: String): Pair<Boolean, String?> {
        val now = System.currentTimeMillis()
        val tracker = proposalTrackers.getOrPut(mint) { ProposalTracker() }
        
        // Check: Too soon since last approval?
        if (now - tracker.lastApprovalMs < MIN_APPROVAL_INTERVAL_MS) {
            val waitSec = (MIN_APPROVAL_INTERVAL_MS - (now - tracker.lastApprovalMs)) / 1000
            return false to "DEDUPE: Approval too soon, wait ${waitSec}s"
        }
        
        return true to null
    }
    
    /**
     * Record that a proposal was made
     */
    fun recordProposal(mint: String) {
        val now = System.currentTimeMillis()
        val tracker = proposalTrackers.getOrPut(mint) { ProposalTracker() }
        tracker.lastProposalMs = now
        tracker.proposalCount++
    }
    
    /**
     * Record that an approval was made
     */
    fun recordApproval(mint: String) {
        val now = System.currentTimeMillis()
        val tracker = proposalTrackers.getOrPut(mint) { ProposalTracker() }
        tracker.lastApprovalMs = now
        tracker.approvalCount++
    }
    
    /**
     * Clear proposal tracking for a token (after trade closes or times out)
     */
    fun clearProposalTracking(mint: String) {
        proposalTrackers.remove(mint)
    }
    
    /**
     * Start tracking a new token lifecycle
     */
    fun discovered(mint: String, symbol: String, score: Double, source: String): Lifecycle {
        pruneOldLifecycles()
        
        val lifecycle = Lifecycle(mint = mint, symbol = symbol, discoveryScore = score)
        lifecycle.transition(State.DISCOVERED, "Found by scanner", mapOf(
            "source" to source,
            "score" to score,
        ))
        lifecycles[mint] = lifecycle
        
        ErrorLogger.debug("Lifecycle", "🔍 DISCOVERED: $symbol | score=$score | src=$source")
        return lifecycle
    }
    
    /**
     * Token passed initial filters
     */
    /**
     * Token passed minimum eligibility prerequisites:
     * - Liquidity >= minimum threshold
     * - Not banned/blacklisted
     * - Basic safety checks passed
     */
    fun eligible(mint: String, score: Double, reason: String) {
        lifecycles[mint]?.let { lc ->
            lc.eligibilityScore = score
            lc.transition(State.ELIGIBLE, reason, mapOf("score" to score))
            ErrorLogger.debug("Lifecycle", "✓ ELIGIBLE: ${lc.symbol} | $reason")
        }
    }
    
    /**
     * Token failed eligibility prerequisites (liq too low, unsafe, etc.)
     */
    fun ineligible(mint: String, reason: String) {
        lifecycles[mint]?.let { lc ->
            lc.transition(State.INELIGIBLE, reason)
            ErrorLogger.debug("Lifecycle", "✗ INELIGIBLE: ${lc.symbol} | $reason")
        }
    }
    
    /**
     * Token admitted to watchlist for active strategy evaluation.
     * This is the final gate before strategy runs on the token.
     */
    fun watchlisted(mint: String, watchlistSize: Int, reason: String = "admitted for evaluation") {
        lifecycles[mint]?.let { lc ->
            lc.transition(State.WATCHLISTED, reason, mapOf("watchlistSize" to watchlistSize))
            ErrorLogger.info("Lifecycle", "📋 WATCHLISTED: ${lc.symbol} | watchlist now=$watchlistSize")
        }
    }
    
    /**
     * Token filtered out (watchlist full, already tracked, etc.)
     */
    fun filtered(mint: String, reason: String) {
        lifecycles[mint]?.let { lc ->
            lc.transition(State.FILTERED, reason)
            ErrorLogger.debug("Lifecycle", "✗ FILTERED: ${lc.symbol} | $reason")
        }
    }
    
    /**
     * Token rejected (banned, rugged, etc.)
     */
    fun rejected(mint: String, reason: String) {
        lifecycles[mint]?.let { lc ->
            lc.transition(State.REJECTED, reason)
            ErrorLogger.debug("Lifecycle", "🚫 REJECTED: ${lc.symbol} | $reason")
        }
    }
    
    /**
     * Strategy generated BUY signal
     */
    fun candidate(mint: String, score: Double, phase: String, quality: String) {
        lifecycles[mint]?.let { lc ->
            lc.candidateScore = score
            lc.transition(State.CANDIDATE, "BUY signal generated", mapOf(
                "score" to score,
                "phase" to phase,
                "quality" to quality,
            ))
            ErrorLogger.debug("Lifecycle", "📈 CANDIDATE: ${lc.symbol} | quality=$quality | phase=$phase")
        }
    }
    
    /**
     * No BUY signal from strategy
     */
    fun noSignal(mint: String, reason: String) {
        lifecycles[mint]?.let { lc ->
            lc.transition(State.NO_SIGNAL, reason)
            ErrorLogger.debug("Lifecycle", "⏸ NO_SIGNAL: ${lc.symbol} | $reason")
        }
    }
    
    /**
     * Ready for FDG evaluation
     */
    fun proposed(mint: String) {
        lifecycles[mint]?.let { lc ->
            lc.transition(State.PROPOSED, "Submitted to FDG")
            ErrorLogger.debug("Lifecycle", "📋 PROPOSED: ${lc.symbol}")
        }
    }
    
    /**
     * FDG blocked the trade
     */
    fun fdgBlocked(mint: String, reason: String, level: String) {
        lifecycles[mint]?.let { lc ->
            lc.fdgDecision = "BLOCKED: $reason"
            lc.transition(State.FDG_BLOCKED, reason, mapOf("level" to level))
            ErrorLogger.info("Lifecycle", "🚫 FDG_BLOCKED: ${lc.symbol} | $reason | level=$level")
        }
    }
    
    /**
     * FDG approved the trade
     */
    fun fdgApproved(mint: String, quality: String, confidence: Double, approvalClass: String = "UNKNOWN") {
        lifecycles[mint]?.let { lc ->
            lc.fdgDecision = "APPROVED"
            lc.transition(State.FDG_APPROVED, "Trade approved ($approvalClass)", mapOf(
                "quality" to quality,
                "confidence" to confidence,
                "approvalClass" to approvalClass,
            ))
            val classIcon = when (approvalClass) {
                "LIVE" -> "🔴"
                "PAPER_BENCHMARK" -> "🟢"
                "PAPER_EXPLORATION" -> "🟡"
                else -> "✅"
            }
            ErrorLogger.info("Lifecycle", "$classIcon FDG_APPROVED_$approvalClass: ${lc.symbol} | quality=$quality | conf=${confidence.toInt()}%")
        }
    }
    
    /**
     * Position sized
     */
    fun sized(mint: String, sizeSol: Double, tier: String) {
        lifecycles[mint]?.let { lc ->
            lc.sizeSol = sizeSol
            lc.transition(State.SIZED, "Size calculated", mapOf(
                "sizeSol" to sizeSol,
                "tier" to tier,
            ))
            ErrorLogger.debug("Lifecycle", "📏 SIZED: ${lc.symbol} | ${sizeSol} SOL | tier=$tier")
        }
    }
    
    /**
     * Trade executed (bought)
     */
    fun executed(mint: String, price: Double, sizeSol: Double) {
        lifecycles[mint]?.let { lc ->
            lc.entryPrice = price
            lc.sizeSol = sizeSol
            lc.transition(State.EXECUTED, "Buy executed", mapOf(
                "price" to price,
                "sizeSol" to sizeSol,
            ))
            ErrorLogger.info("Lifecycle", "🛒 EXECUTED: ${lc.symbol} | ${sizeSol} SOL @ $price")
        }
    }
    
    /**
     * Position being monitored
     */
    fun monitoring(mint: String, currentPnl: Double) {
        lifecycles[mint]?.let { lc ->
            if (lc.currentState != State.MONITORING) {
                lc.transition(State.MONITORING, "Position open", mapOf("pnl" to currentPnl))
                ErrorLogger.debug("Lifecycle", "👁 MONITORING: ${lc.symbol} | pnl=${currentPnl.toInt()}%")
            }
        }
    }
    
    /**
     * Position closed (sold)
     */
    fun closed(mint: String, exitPrice: Double, pnlPct: Double, reason: String) {
        lifecycles[mint]?.let { lc ->
            lc.exitPrice = exitPrice
            lc.pnlPct = pnlPct
            lc.transition(State.CLOSED, reason, mapOf(
                "exitPrice" to exitPrice,
                "pnlPct" to pnlPct,
            ))
            ErrorLogger.info("Lifecycle", "💰 CLOSED: ${lc.symbol} | pnl=${pnlPct.toInt()}% | $reason")
        }
    }
    
    /**
     * Learning classification complete
     */
    fun classified(mint: String, classification: String, isWin: Boolean?) {
        lifecycles[mint]?.let { lc ->
            lc.classification = classification
            lc.transition(State.CLASSIFIED, "Learning complete", mapOf(
                "classification" to classification,
                "isWin" to (isWin?.toString() ?: "scratch"),
            ))
            ErrorLogger.info("Lifecycle", "🎓 CLASSIFIED: ${lc.symbol} | $classification | " +
                "win=${isWin?.toString() ?: "scratch"} | pnl=${lc.pnlPct.toInt()}%")
        }
    }
    
    /**
     * Token expired from watchlist
     */
    fun expired(mint: String, reason: String) {
        lifecycles[mint]?.let { lc ->
            lc.transition(State.EXPIRED, reason)
            ErrorLogger.debug("Lifecycle", "⏰ EXPIRED: ${lc.symbol} | $reason")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // QUERIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun get(mint: String): Lifecycle? = lifecycles[mint]
    
    fun getState(mint: String): State? = lifecycles[mint]?.currentState
    
    fun getByState(state: State): List<Lifecycle> = 
        lifecycles.values.filter { it.currentState == state }
    
    fun getActive(): List<Lifecycle> = 
        lifecycles.values.filter { it.endTime == null }
    
    fun getCompleted(): List<Lifecycle> = 
        lifecycles.values.filter { it.endTime != null }
    
    fun getStats(): LifecycleStats {
        val all = lifecycles.values.toList()
        return LifecycleStats(
            total = all.size,
            discovered = all.count { it.currentState == State.DISCOVERED },
            eligible = all.count { it.currentState == State.ELIGIBLE },
            candidates = all.count { it.currentState == State.CANDIDATE },
            fdgBlocked = all.count { it.currentState == State.FDG_BLOCKED },
            fdgApproved = all.count { it.currentState == State.FDG_APPROVED },
            executed = all.count { it.currentState in listOf(State.EXECUTED, State.MONITORING) },
            closed = all.count { it.currentState == State.CLOSED },
            classified = all.count { it.currentState == State.CLASSIFIED },
            filtered = all.count { it.currentState == State.FILTERED },
            rejected = all.count { it.currentState == State.REJECTED },
        )
    }
    
    data class LifecycleStats(
        val total: Int,
        val discovered: Int,
        val eligible: Int,
        val candidates: Int,
        val fdgBlocked: Int,
        val fdgApproved: Int,
        val executed: Int,
        val closed: Int,
        val classified: Int,
        val filtered: Int,
        val rejected: Int,
    ) {
        fun summary(): String = 
            "Lifecycle: $total tracked | " +
            "funnel: discovered→$eligible eligible→$candidates candidates→$fdgApproved approved→$executed executed | " +
            "blocked=$fdgBlocked filtered=$filtered rejected=$rejected"
    }
    
    private fun pruneOldLifecycles() {
        if (lifecycles.size > MAX_TRACKED) {
            // Remove oldest completed lifecycles
            val completed = lifecycles.entries
                .filter { it.value.endTime != null }
                .sortedBy { it.value.startTime }
                .take(lifecycles.size - MAX_TRACKED + 100)
            
            completed.forEach { lifecycles.remove(it.key) }
        }
    }
    
    /**
     * Clear all tracking (for testing)
     */
    fun clear() {
        lifecycles.clear()
    }
}
