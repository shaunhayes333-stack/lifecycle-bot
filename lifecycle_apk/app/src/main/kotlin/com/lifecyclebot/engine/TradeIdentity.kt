package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * TradeIdentity — Single Source of Truth for Trade Entity
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * PROBLEM SOLVED:
 * Before: mint, symbol, lifecycle state, approved size, execution record were
 * tracked separately in TradeLifecycle, TradeStateMachine, Executor, BotService.
 * This caused entity/state mismatches leading to:
 *   - Wrong token appearing in logs
 *   - Confusing closeouts  
 *   - Bad analytics
 * 
 * SOLUTION:
 * TradeIdentity is the CANONICAL trade object. All systems reference it.
 * When you need mint/symbol/state, get it from TradeIdentity, not from 
 * separate tracking systems.
 * 
 * FLOW:
 *   1. Scanner discovers token → TradeIdentity.create(mint, symbol)
 *   2. FDG approves → identity.approve(sizeSol, quality, confidence)
 *   3. Executor buys → identity.executed(entryPrice, actualSize)
 *   4. Monitoring → identity.updatePrice(currentPrice)
 *   5. Exit → identity.closed(exitPrice, pnlPct, reason)
 *   6. Learning → identity.classified(classification, isWin)
 * 
 * All logging/tracking/analytics use identity.mint, identity.symbol to 
 * guarantee consistency.
 */
object TradeIdentityManager {
    
    private val identities = ConcurrentHashMap<String, TradeIdentity>()
    private val tradeIdCounter = AtomicLong(System.currentTimeMillis())
    
    /**
     * Create or get a trade identity for a mint.
     * This is the ONLY way to start tracking a trade.
     */
    fun getOrCreate(mint: String, symbol: String, source: String = ""): TradeIdentity {
        return identities.getOrPut(mint) {
            TradeIdentity(
                tradeId = tradeIdCounter.incrementAndGet(),
                mint = mint,
                symbol = symbol,
                source = source,
                createdAt = System.currentTimeMillis(),
            )
        }.also { existing ->
            // Always update symbol if it was unknown
            if (existing.symbol.isBlank() && symbol.isNotBlank()) {
                existing.symbol = symbol
            }
        }
    }
    
    /**
     * Get existing identity (returns null if not tracked)
     */
    fun get(mint: String): TradeIdentity? = identities[mint]
    
    /**
     * Get identity or throw (use when identity MUST exist)
     */
    fun require(mint: String): TradeIdentity = 
        identities[mint] ?: throw IllegalStateException("No TradeIdentity for mint: ${mint.take(12)}...")
    
    /**
     * Remove identity (after classification complete and data persisted)
     */
    fun remove(mint: String) {
        identities.remove(mint)
    }
    
    /**
     * Get all active identities
     */
    fun getAll(): List<TradeIdentity> = identities.values.toList()
    
    /**
     * Get identities by state
     */
    fun getByState(state: IdentityState): List<TradeIdentity> = 
        identities.values.filter { it.state == state }
    
    /**
     * Cleanup old identities (call periodically)
     */
    fun cleanup(maxAgeMs: Long = 4 * 60 * 60 * 1000) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        identities.entries.removeIf { (_, identity) ->
            identity.state == IdentityState.CLASSIFIED && identity.closedAt < cutoff
        }
    }
    
    /**
     * Get summary stats
     */
    fun stats(): IdentityStats {
        val all = identities.values.toList()
        return IdentityStats(
            total = all.size,
            discovered = all.count { it.state == IdentityState.DISCOVERED },
            eligible = all.count { it.state == IdentityState.ELIGIBLE },
            watchlisted = all.count { it.state == IdentityState.WATCHLISTED },
            ineligible = all.count { it.state == IdentityState.INELIGIBLE },
            approved = all.count { it.state == IdentityState.APPROVED },
            executed = all.count { it.state == IdentityState.EXECUTED },
            monitoring = all.count { it.state == IdentityState.MONITORING },
            closed = all.count { it.state == IdentityState.CLOSED },
            classified = all.count { it.state == IdentityState.CLASSIFIED },
            blocked = all.count { it.state == IdentityState.BLOCKED },
        )
    }
}

/**
 * Identity states - simplified, clear lifecycle
 * 
 * DISCOVERY FUNNEL (3-tier):
 *   DISCOVERED → ELIGIBLE → WATCHLISTED
 */
enum class IdentityState {
    DISCOVERED,   // Scanner found token (raw)
    ELIGIBLE,     // Passed minimum prereqs (liq, safety, not banned)
    WATCHLISTED,  // Admitted for strategy evaluation
    CANDIDATE,    // Strategy generated BUY signal
    PROPOSED,     // Submitted to FDG
    BLOCKED,      // FDG blocked
    APPROVED,     // FDG approved
    EXECUTED,     // Buy executed
    MONITORING,   // Position open
    CLOSED,       // Position closed
    CLASSIFIED,   // Learning complete
    INELIGIBLE,   // Failed eligibility prereqs
}

/**
 * TradeIdentity — The canonical trade object
 * 
 * IMMUTABLE FIELDS (set at creation):
 *   - tradeId: Unique identifier for this trade attempt
 *   - mint: Token mint address (NEVER changes)
 *   - createdAt: When tracking started
 * 
 * MUTABLE FIELDS (updated through lifecycle):
 *   - symbol: May be updated if initially unknown
 *   - state: Current lifecycle state
 *   - All tracking fields below
 */
data class TradeIdentity(
    // ═══════════════════════════════════════════════════════════════════════════
    // IMMUTABLE IDENTITY (set once at creation)
    // ═══════════════════════════════════════════════════════════════════════════
    val tradeId: Long,
    val mint: String,
    var symbol: String,  // Mutable only if initially blank
    val source: String,
    val createdAt: Long,
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    var state: IdentityState = IdentityState.DISCOVERED,
    var stateChangedAt: Long = System.currentTimeMillis(),
    var stateHistory: MutableList<StateTransition> = mutableListOf(),
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DISCOVERY PHASE
    // ═══════════════════════════════════════════════════════════════════════════
    var discoveryScore: Double = 0.0,
    var phase: String = "",
    var rugcheckScore: Int? = null,  // V5.0: RC score for hard gating (-1 = not fetched)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FDG DECISION
    // ═══════════════════════════════════════════════════════════════════════════
    var fdgDecision: String = "",       // "APPROVED" or "BLOCKED: reason"
    var fdgQuality: String = "",        // "A+", "A", "B", "C"
    var fdgConfidence: Double = 0.0,
    var fdgApprovalClass: String = "",  // "LIVE", "PAPER_BENCHMARK", "PAPER_EXPLORATION", "BLOCKED"
    var approvedSizeSol: Double = 0.0,
    var blockReason: String = "",
    var blockLevel: String = "",
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════
    var entryPrice: Double = 0.0,
    var entrySizeSol: Double = 0.0,
    var entryTime: Long = 0,
    var entryScore: Double = 0.0,
    var txSignature: String = "",       // Live trade tx signature
    var isPaperTrade: Boolean = true,
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MONITORING
    // ═══════════════════════════════════════════════════════════════════════════
    var currentPrice: Double = 0.0,
    var highestPrice: Double = 0.0,
    var lowestPrice: Double = 0.0,
    var currentPnlPct: Double = 0.0,
    var peakPnlPct: Double = 0.0,
    var lastUpdateAt: Long = 0,
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CLOSE
    // ═══════════════════════════════════════════════════════════════════════════
    var exitPrice: Double = 0.0,
    var exitReason: String = "",
    var closedAt: Long = 0,
    var pnlSol: Double = 0.0,
    var pnlPct: Double = 0.0,
    var holdTimeMs: Long = 0,
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CLASSIFICATION (Learning)
    // ═══════════════════════════════════════════════════════════════════════════
    var classification: String = "",    // "WIN", "LOSS", "SCRATCH"
    var isWin: Boolean? = null,         // null = scratch
    var classifiedAt: Long = 0,
) {
    
    data class StateTransition(
        val from: IdentityState,
        val to: IdentityState,
        val at: Long,
        val reason: String,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE TRANSITIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun transition(newState: IdentityState, reason: String = "") {
        if (state != newState) {
            stateHistory.add(StateTransition(state, newState, System.currentTimeMillis(), reason))
            state = newState
            stateChangedAt = System.currentTimeMillis()
            ErrorLogger.debug("TradeIdentity", "[$symbol] $state → $newState ${if(reason.isNotBlank()) "($reason)" else ""}")
        }
    }
    
    /**
     * Mark as eligible (passed minimum prereqs)
     */
    fun eligible(score: Double, reason: String = "") {
        discoveryScore = score
        transition(IdentityState.ELIGIBLE, reason)
    }
    
    /**
     * Mark as watchlisted (admitted for strategy evaluation)
     */
    fun watchlisted(reason: String = "admitted for evaluation") {
        transition(IdentityState.WATCHLISTED, reason)
    }
    
    /**
     * Mark as ineligible (failed prereqs)
     */
    fun ineligible(reason: String) {
        transition(IdentityState.INELIGIBLE, reason)
    }
    
    /**
     * Mark as candidate (strategy generated BUY signal)
     */
    fun candidate(score: Double, phase: String, quality: String) {
        this.entryScore = score
        this.phase = phase
        this.fdgQuality = quality
        transition(IdentityState.CANDIDATE, "BUY signal, quality=$quality")
    }
    
    /**
     * V3 CLEAN RUNTIME: Mark as V3 execute decision
     * Bypasses old CANDIDATE/PROPOSED states
     */
    fun v3Execute(score: Int, band: String, sizeSol: Double) {
        this.entryScore = score.toDouble()
        this.fdgDecision = "V3_EXECUTE"
        this.fdgQuality = band
        this.fdgConfidence = score.toDouble()
        this.approvedSizeSol = sizeSol
        transition(IdentityState.APPROVED, "V3 score=$score band=$band size=${"%.4f".format(sizeSol)}")
    }
    
    /**
     * Mark as proposed to FDG
     */
    fun proposed() {
        transition(IdentityState.PROPOSED, "submitted to FDG")
    }
    
    /**
     * Mark as blocked by FDG
     */
    fun blocked(reason: String, level: String, quality: String, confidence: Double) {
        fdgDecision = "BLOCKED: $reason"
        fdgQuality = quality
        fdgConfidence = confidence
        blockReason = reason
        blockLevel = level
        transition(IdentityState.BLOCKED, reason)
    }
    
    /**
     * Mark as approved by FDG
     */
    fun approved(sizeSol: Double, quality: String, confidence: Double) {
        fdgDecision = "APPROVED"
        fdgQuality = quality
        fdgConfidence = confidence
        approvedSizeSol = sizeSol
        transition(IdentityState.APPROVED, "size=${"%.4f".format(sizeSol)} quality=$quality")
    }
    
    /**
     * Mark as executed (bought)
     */
    fun executed(price: Double, sizeSol: Double, isPaper: Boolean, signature: String = "") {
        entryPrice = price
        entrySizeSol = sizeSol
        entryTime = System.currentTimeMillis()
        highestPrice = price
        lowestPrice = price
        currentPrice = price
        isPaperTrade = isPaper
        txSignature = signature
        transition(IdentityState.EXECUTED, "${if(isPaper) "PAPER" else "LIVE"} @ ${"%.8f".format(price)}")
    }
    
    /**
     * Start monitoring (position open)
     */
    fun monitoring() {
        transition(IdentityState.MONITORING, "position open")
    }
    
    /**
     * Update price during monitoring
     */
    fun updatePrice(price: Double) {
        currentPrice = price
        lastUpdateAt = System.currentTimeMillis()
        
        if (price > highestPrice) highestPrice = price
        if (price < lowestPrice || lowestPrice == 0.0) lowestPrice = price
        
        if (entryPrice > 0) {
            currentPnlPct = ((price - entryPrice) / entryPrice) * 100
            if (currentPnlPct > peakPnlPct) peakPnlPct = currentPnlPct
        }
    }
    
    /**
     * Mark as closed (sold)
     */
    fun closed(price: Double, pnlPct: Double, pnlSol: Double, reason: String) {
        exitPrice = price
        exitReason = reason
        closedAt = System.currentTimeMillis()
        this.pnlPct = pnlPct
        this.pnlSol = pnlSol
        holdTimeMs = if (entryTime > 0) closedAt - entryTime else 0
        transition(IdentityState.CLOSED, "$reason | pnl=${"%.1f".format(pnlPct)}%")
    }
    
    /**
     * Mark as classified (learning complete)
     */
    fun classified(classification: String, isWin: Boolean?) {
        this.classification = classification
        this.isWin = isWin
        classifiedAt = System.currentTimeMillis()
        transition(IdentityState.CLASSIFIED, classification)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if position is currently open
     */
    fun isOpen(): Boolean = state in listOf(IdentityState.EXECUTED, IdentityState.MONITORING)
    
    /**
     * Short identifier for logs
     */
    fun shortId(): String = "$symbol(${mint.take(6)})"
    
    /**
     * Full summary for debugging
     */
    fun summary(): String = buildString {
        append("[$symbol] ")
        append("state=$state ")
        append("mint=${mint.take(8)}... ")
        if (isOpen()) {
            append("entry=${"%.8f".format(entryPrice)} ")
            append("current=${"%.8f".format(currentPrice)} ")
            append("pnl=${"%.1f".format(currentPnlPct)}% ")
        }
        if (state == IdentityState.CLOSED || state == IdentityState.CLASSIFIED) {
            append("exit=${"%.8f".format(exitPrice)} ")
            append("pnl=${"%.1f".format(pnlPct)}% ")
            append("$classification ")
        }
    }
    
    /**
     * Full audit trail
     */
    fun auditTrail(): String = buildString {
        appendLine("═══ TRADE IDENTITY: $symbol ═══")
        appendLine("Trade ID: $tradeId")
        appendLine("Mint: $mint")
        appendLine("Source: $source")
        appendLine("Created: ${java.text.SimpleDateFormat("HH:mm:ss").format(createdAt)}")
        appendLine("Current State: $state")
        appendLine()
        appendLine("STATE HISTORY:")
        stateHistory.forEachIndexed { i, t ->
            val time = java.text.SimpleDateFormat("HH:mm:ss").format(t.at)
            appendLine("  ${i+1}. [$time] ${t.from} → ${t.to}")
            if (t.reason.isNotBlank()) appendLine("      Reason: ${t.reason}")
        }
        if (fdgDecision.isNotBlank()) {
            appendLine()
            appendLine("FDG: $fdgDecision | quality=$fdgQuality | conf=${"%.0f".format(fdgConfidence)}%")
            if (blockReason.isNotBlank()) appendLine("     Block: $blockReason ($blockLevel)")
        }
        if (entryPrice > 0) {
            appendLine()
            appendLine("EXECUTION:")
            appendLine("  Entry: ${"%.8f".format(entryPrice)} | Size: ${"%.4f".format(entrySizeSol)} SOL")
            appendLine("  Mode: ${if(isPaperTrade) "PAPER" else "LIVE"}")
            if (txSignature.isNotBlank()) appendLine("  Tx: ${txSignature.take(20)}...")
        }
        if (closedAt > 0) {
            appendLine()
            appendLine("CLOSE:")
            appendLine("  Exit: ${"%.8f".format(exitPrice)} | Reason: $exitReason")
            appendLine("  PnL: ${"%.1f".format(pnlPct)}% (${"%.4f".format(pnlSol)} SOL)")
            appendLine("  Hold: ${holdTimeMs / 60000}min")
        }
        if (classification.isNotBlank()) {
            appendLine()
            appendLine("CLASSIFIED: $classification (isWin=$isWin)")
        }
    }
}

data class IdentityStats(
    val total: Int,
    val discovered: Int,
    val eligible: Int,
    val watchlisted: Int,
    val ineligible: Int,
    val approved: Int,
    val executed: Int,
    val monitoring: Int,
    val closed: Int,
    val classified: Int,
    val blocked: Int,
) {
    fun summary(): String = "Identities: $total | " +
        "discovered=$discovered eligible=$eligible watchlisted=$watchlisted ineligible=$ineligible | " +
        "approved=$approved executed=$executed monitoring=$monitoring | " +
        "closed=$closed classified=$classified blocked=$blocked"
    
    /**
     * Funnel conversion rates
     */
    fun funnelReport(): String = buildString {
        appendLine("═══ DISCOVERY FUNNEL ═══")
        appendLine("DISCOVERED: $discovered")
        val eligRate = if (discovered > 0) (eligible * 100.0 / discovered) else 0.0
        appendLine("  → ELIGIBLE: $eligible (${eligRate.toInt()}%)")
        val watchRate = if (eligible > 0) (watchlisted * 100.0 / eligible) else 0.0
        appendLine("    → WATCHLISTED: $watchlisted (${watchRate.toInt()}%)")
        appendLine("    → INELIGIBLE: $ineligible")
        val approveRate = if (watchlisted > 0) (approved * 100.0 / watchlisted) else 0.0
        appendLine("      → APPROVED: $approved (${approveRate.toInt()}%)")
        appendLine("        → EXECUTED: $executed")
    }
}
