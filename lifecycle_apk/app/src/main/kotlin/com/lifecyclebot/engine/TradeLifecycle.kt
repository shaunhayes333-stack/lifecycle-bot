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
