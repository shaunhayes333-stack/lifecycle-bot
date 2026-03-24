package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.TokenState

/**
 * FinalDecisionGate (FDG)
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * The SINGLE AUTHORITATIVE CHECKPOINT that ALL trades must pass before execution.
 * 
 * Flow:
 *   Scanner → Strategy → Edge → Safety → Learning → SmartSizer → ✅ FDG → Executor
 * 
 * HARD HIERARCHY (non-negotiable, order matters):
 *   1. HARD BLOCKS (rugcheck, extreme sell pressure, zero liq) → instant ❌
 *   2. EDGE veto (quality = SKIP) → ❌
 *   3. CONFIDENCE threshold → ❌ if below minimum
 *   4. MODE rules (paper vs live strictness)
 *   5. SIZING validation
 * 
 * ABSOLUTE RULE:
 *   if (blockReason != null) → shouldTrade = false
 *   NO EXCEPTIONS. Not even in paper mode. Not even for "learning".
 * 
 * If you want to learn from blocked trades:
 *   - Log them
 *   - Simulate them
 *   - DO NOT execute them
 */
object FinalDecisionGate {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FINAL DECISION - The canonical output all modules feed into
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class FinalDecision(
        val shouldTrade: Boolean,
        val mode: TradeMode,
        val approvalClass: ApprovalClass,  // LIVE, PAPER_BENCHMARK, PAPER_EXPLORATION, BLOCKED
        val quality: String,           // "A+", "A", "B", "C"
        val confidence: Double,        // 0-100%
        val edge: EdgeVerdict,
        val blockReason: String?,      // null = no block, string = blocked
        val blockLevel: BlockLevel?,   // How severe the block is
        val sizeSol: Double,
        val tags: List<String>,        // ["distribution", "early", "pump_fun", etc]
        val mint: String,
        val symbol: String,
        
        // Why this approval class was assigned
        val approvalReason: String,
        
        // Audit trail
        val gateChecks: List<GateCheck>,  // What checks were run
    ) {
        /**
         * ABSOLUTE RULE: Cannot trade if blocked.
         * This is redundant with shouldTrade but makes intent crystal clear.
         */
        fun canExecute(): Boolean = shouldTrade && blockReason == null
        
        /**
         * Is this a benchmark-quality trade that would pass live rules?
         */
        fun isBenchmarkQuality(): Boolean = approvalClass in listOf(ApprovalClass.LIVE, ApprovalClass.PAPER_BENCHMARK)
        
        /**
         * Is this an exploration trade for learning?
         */
        fun isExploration(): Boolean = approvalClass == ApprovalClass.PAPER_EXPLORATION
        
        fun summary(): String = buildString {
            append(if (shouldTrade) "✅" else "❌")
            append(" $symbol | $approvalClass | $quality | ${confidence.toInt()}% | ${edge.name}")
            if (blockReason != null) append(" | BLOCKED: $blockReason")
            if (shouldTrade) append(" | ${sizeSol.format(3)} SOL")
        }
    }
    
    enum class TradeMode {
        PAPER,      // Paper trading - executes FDG-approved trades
        LIVE,       // Live trading - strictest rules
    }
    
    enum class EdgeVerdict {
        STRONG,     // High quality setup
        WEAK,       // Acceptable but not great
        SKIP,       // Do not trade
    }
    
    enum class BlockLevel {
        HARD,       // Absolute block - rugcheck, zero liq, etc. NEVER bypass.
        EDGE,       // Edge optimizer says skip
        CONFIDENCE, // Below confidence threshold
        MODE,       // Mode-specific restriction
        SIZE,       // Position sizing issue
    }
    
    /**
     * APPROVAL CLASS - Explicit categorization for analytics separation
     * 
     * This enables clean reporting:
     *   - Benchmark stats: "How good is strategy under strict rules?"
     *   - Exploration stats: "What did we learn from weaker setups?"
     */
    enum class ApprovalClass {
        LIVE,              // Live mode approval - strictest rules, real money
        PAPER_BENCHMARK,   // Paper mode, would PASS live rules - benchmark quality
        PAPER_EXPLORATION, // Paper mode, relaxed rules - learning from weaker setups
        BLOCKED,           // Not approved
    }
    
    data class GateCheck(
        val name: String,
        val passed: Boolean,
        val reason: String?,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // THRESHOLDS - Can be learned/adjusted by BotBrain
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Hard block thresholds (non-negotiable - these are DANGEROUS)
    var hardBlockRugcheckMin = 10          // Block if rugcheck score <= this
    var hardBlockBuyPressureMin = 15.0     // Block if buy pressure < this %
    var hardBlockTopHolderMax = 70.0       // Block if top holder > this %
    
    // Confidence thresholds by mode
    var paperConfidenceMin = 0.0           // Paper mode: NO confidence minimum (learn from all)
    var liveConfidenceMin = 40.0           // Live needs higher confidence
    
    // PAPER MODE LEARNING: Allow edge overrides so bot can learn from trades
    var allowEdgeOverrideInPaper = false   // NO MORE EDGE OVERRIDE - causes garbage data
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DISTRIBUTION COOLDOWN TRACKER
    // 
    // When a token exits due to distribution, block it from being bought again
    // for a cooldown period. This prevents the buy→dump→buy→dump loop.
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val distributionCooldowns = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val DISTRIBUTION_COOLDOWN_MS = 7 * 60 * 1000L  // 7 minutes
    
    /**
     * Record that a token was closed due to distribution.
     * Call this from Executor when a trade exits with distribution reason.
     */
    fun recordDistributionExit(mint: String) {
        distributionCooldowns[mint] = System.currentTimeMillis()
        // Clean up old entries (older than 2 hours)
        val twoHoursAgo = System.currentTimeMillis() - (2 * 60 * 60 * 1000L)
        distributionCooldowns.entries.removeIf { it.value < twoHoursAgo }
    }
    
    /**
     * Check if a token is in distribution cooldown.
     */
    fun isInDistributionCooldown(mint: String): Boolean {
        val exitTime = distributionCooldowns[mint] ?: return false
        val elapsed = System.currentTimeMillis() - exitTime
        return elapsed < DISTRIBUTION_COOLDOWN_MS
    }
    
    /**
     * Get remaining cooldown time in minutes.
     */
    fun getRemainingCooldownMinutes(mint: String): Int {
        val exitTime = distributionCooldowns[mint] ?: return 0
        val elapsed = System.currentTimeMillis() - exitTime
        val remaining = DISTRIBUTION_COOLDOWN_MS - elapsed
        return if (remaining > 0) (remaining / 60000).toInt() else 0
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE VETO TRACKER (STICKY VETOES)
    // 
    // When Edge says "VETOED", that decision is AUTHORITATIVE for a cooldown period.
    // This prevents the bug where:
    //   1. Edge vetoes at T+0
    //   2. Strategy says BUY at T+10 seconds
    //   3. FDG approves because it only sees the latest signal
    // 
    // Rule: Once vetoed, stay vetoed for EDGE_VETO_COOLDOWN_MS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class EdgeVeto(
        val timestamp: Long,
        val reason: String,
        val quality: String,  // The edge quality when vetoed (SKIP, etc.)
    )
    
    private val edgeVetoes = java.util.concurrent.ConcurrentHashMap<String, EdgeVeto>()
    private const val EDGE_VETO_COOLDOWN_MS = 3 * 60 * 1000L  // 3 minutes - reduced for faster paper learning
    
    /**
     * Record an Edge veto for a token.
     * Called from BotService when Edge returns a veto.
     */
    fun recordEdgeVeto(mint: String, reason: String, quality: String) {
        edgeVetoes[mint] = EdgeVeto(
            timestamp = System.currentTimeMillis(),
            reason = reason,
            quality = quality,
        )
        // Clean up old entries (older than 30 minutes)
        val thirtyMinutesAgo = System.currentTimeMillis() - (30 * 60 * 1000L)
        edgeVetoes.entries.removeIf { it.value.timestamp < thirtyMinutesAgo }
    }
    
    /**
     * Check if a token has an active Edge veto.
     */
    fun hasActiveEdgeVeto(mint: String): EdgeVeto? {
        val veto = edgeVetoes[mint] ?: return null
        val elapsed = System.currentTimeMillis() - veto.timestamp
        return if (elapsed < EDGE_VETO_COOLDOWN_MS) veto else null
    }
    
    /**
     * Get remaining veto time in seconds.
     */
    fun getVetoRemainingSeconds(mint: String): Int {
        val veto = edgeVetoes[mint] ?: return 0
        val elapsed = System.currentTimeMillis() - veto.timestamp
        val remaining = EDGE_VETO_COOLDOWN_MS - elapsed
        return if (remaining > 0) (remaining / 1000).toInt() else 0
    }
    
    /**
     * Clear a veto (e.g., when conditions significantly improve)
     */
    fun clearEdgeVeto(mint: String) {
        edgeVetoes.remove(mint)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN GATE FUNCTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Evaluate a trade candidate through the Final Decision Gate.
     * 
     * @param ts TokenState with all market data
     * @param candidate CandidateDecision from strategy evaluation
     * @param config Current bot configuration
     * @param proposedSizeSol Size from SmartSizer
     * @param brain Optional BotBrain for learned thresholds
     * 
     * @return FinalDecision - the ONLY object Executor should look at
     */
    fun evaluate(
        ts: TokenState,
        candidate: CandidateDecision,
        config: BotConfig,
        proposedSizeSol: Double,
        brain: BotBrain? = null,
    ): FinalDecision {
        val checks = mutableListOf<GateCheck>()
        var blockReason: String? = null
        var blockLevel: BlockLevel? = null
        val tags = mutableListOf<String>()
        
        val mode = if (config.paperMode) TradeMode.PAPER else TradeMode.LIVE
        
        // Use learned thresholds if available
        val rugcheckThreshold = brain?.learnedRugcheckThreshold ?: hardBlockRugcheckMin
        val buyPressureThreshold = brain?.learnedMinBuyPressure ?: hardBlockBuyPressureMin
        val topHolderThreshold = brain?.learnedMaxTopHolder ?: hardBlockTopHolderMax
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 1: HARD BLOCKS (non-negotiable, checked first)
        // ─────────────────────────────────────────────────────────────────────
        
        // 1a. Zero liquidity - impossible to trade
        if (ts.lastLiquidityUsd <= 0) {
            blockReason = "HARD_BLOCK_ZERO_LIQUIDITY"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("liquidity", false, "liq=${ts.lastLiquidityUsd}"))
        } else {
            checks.add(GateCheck("liquidity", true, null))
        }
        
        // 1b. Rugcheck score critically low
        // PAPER MODE: Allow -1 (API error/no data) to enable learning
        // LIVE MODE: Block both -1 and low scores for safety
        val rugcheckBlock = when {
            ts.safety.rugcheckScore == -1 && config.paperMode -> false  // Paper: allow API errors
            ts.safety.rugcheckScore <= rugcheckThreshold -> true         // Block low scores
            else -> false
        }
        if (blockReason == null && rugcheckBlock) {
            blockReason = "HARD_BLOCK_RUGCHECK_${ts.safety.rugcheckScore}"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("rugcheck", false, "score=${ts.safety.rugcheckScore} <= $rugcheckThreshold"))
            tags.add("low_rugcheck")
        } else if (blockReason == null) {
            // Log if paper mode allowed -1
            if (ts.safety.rugcheckScore == -1 && config.paperMode) {
                checks.add(GateCheck("rugcheck", true, "score=-1 (paper: allowed for learning)"))
            } else {
                checks.add(GateCheck("rugcheck", true, null))
            }
        }
        
        // 1c. Extreme sell pressure (mass dumping)
        if (blockReason == null && ts.meta.pressScore < buyPressureThreshold) {
            blockReason = "HARD_BLOCK_SELL_PRESSURE_${ts.meta.pressScore.toInt()}%"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("buy_pressure", false, "buy%=${ts.meta.pressScore.toInt()} < $buyPressureThreshold"))
            tags.add("sell_pressure")
        } else if (blockReason == null) {
            checks.add(GateCheck("buy_pressure", true, null))
        }
        
        // 1d. Single whale controls supply
        if (blockReason == null && ts.safety.topHolderPct > topHolderThreshold) {
            blockReason = "HARD_BLOCK_TOP_HOLDER_${ts.safety.topHolderPct.toInt()}%"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("top_holder", false, "holder=${ts.safety.topHolderPct.toInt()}% > $topHolderThreshold"))
            tags.add("whale_control")
        } else if (blockReason == null) {
            checks.add(GateCheck("top_holder", true, null))
        }
        
        // 1e. Check hard block reasons from safety checker
        if (blockReason == null && ts.safety.hardBlockReasons.isNotEmpty()) {
            blockReason = "HARD_BLOCK_SAFETY_${ts.safety.hardBlockReasons.first().take(30)}"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("safety_block", false, ts.safety.hardBlockReasons.joinToString(", ")))
            tags.add("safety_blocked")
        } else if (blockReason == null) {
            checks.add(GateCheck("safety_block", true, null))
        }
        
        // 1f. Freeze authority in LIVE mode (honeypot risk)
        // freezeAuthorityDisabled == false means freeze IS enabled (bad)
        if (blockReason == null && !config.paperMode && ts.safety.freezeAuthorityDisabled == false) {
            blockReason = "HARD_BLOCK_FREEZE_AUTHORITY"
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("freeze_auth", false, "freezeAuth=enabled (live mode)"))
            tags.add("freeze_auth")
        } else if (blockReason == null) {
            checks.add(GateCheck("freeze_auth", true, null))
        }
        
        // 1g. Check candidate's own block reason
        if (blockReason == null && candidate.blockReason.isNotEmpty()) {
            blockReason = candidate.blockReason
            blockLevel = BlockLevel.HARD
            checks.add(GateCheck("candidate_block", false, candidate.blockReason))
        } else if (blockReason == null) {
            checks.add(GateCheck("candidate_block", true, null))
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 1h: DISTRIBUTION HARD BLOCK (CRITICAL - applies to ALL modes)
        // 
        // NEVER buy during distribution phase. This is when whales/bots are
        // actively DUMPING. The outcome is predictable: you will lose.
        // 
        // We check MULTIPLE signals:
        //   1. Distribution COOLDOWN (token recently exited due to distribution)
        //   2. EdgeOptimizer phase detection (edgePhase == "DISTRIBUTION")
        //   3. TokenState phase tag (ts.phase contains "distribution")
        //   4. DistributionDetector direct check (confidence >= 50%)
        // 
        // Even paper mode should NOT take these trades.
        // ═══════════════════════════════════════════════════════════════════════
        
        if (blockReason == null) {
            // First check: Is this token in distribution cooldown?
            val inCooldown = isInDistributionCooldown(ts.mint)
            val cooldownMinutes = if (inCooldown) getRemainingCooldownMinutes(ts.mint) else 0
            
            if (inCooldown) {
                blockReason = "DISTRIBUTION_COOLDOWN_${cooldownMinutes}min"
                blockLevel = BlockLevel.HARD
                checks.add(GateCheck("distribution", false, "Recently exited distribution, cooldown=${cooldownMinutes}min remaining"))
                tags.add("distribution_cooldown")
            } else {
                // Check other distribution signals
                val isDistributionPhase = candidate.edgePhase.uppercase() == "DISTRIBUTION"
                val hasDistributionTag = ts.phase.lowercase().contains("distribution")
                
                // Also run DistributionDetector directly for more accurate detection
                val distSignal = if (ts.history.size >= 5) {
                    DistributionDetector.detect(
                        mint = ts.mint,
                        ts = ts,
                        currentExitScore = candidate.exitScore,
                        history = ts.history
                    )
                } else null
                
                val isDistributorConfident = distSignal?.isDistributing == true && distSignal.confidence >= 50
                
                if (isDistributionPhase || hasDistributionTag || isDistributorConfident) {
                    val reason = when {
                        isDistributionPhase -> "edgePhase=DISTRIBUTION"
                        hasDistributionTag -> "tsPhase=${ts.phase}"
                        isDistributorConfident -> "detector=${distSignal?.confidence}% (${distSignal?.details})"
                        else -> "unknown"
                    }
                    blockReason = "HARD_BLOCK_DISTRIBUTION"
                    blockLevel = BlockLevel.HARD
                    checks.add(GateCheck("distribution", false, reason))
                    tags.add("distribution_block")
                } else {
                    val detectorInfo = if (distSignal != null) " detector=${distSignal.confidence}%" else ""
                    checks.add(GateCheck("distribution", true, "edgePhase=${candidate.edgePhase}$detectorInfo"))
                }
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 1i: STICKY EDGE VETO (AUTHORITATIVE - applies to ALL modes)
        // 
        // When Edge says "VETOED", that decision is AUTHORITATIVE.
        // The veto is STICKY for 5 minutes to prevent:
        //   1. Edge vetoes at T+0
        //   2. Strategy says BUY at T+10 seconds 
        //   3. FDG approves because it only sees the latest signal
        // 
        // Rule: Once vetoed by Edge, stay blocked for EDGE_VETO_COOLDOWN_MS
        // This ensures Edge's "do not enter" is respected, not overridden.
        // ═══════════════════════════════════════════════════════════════════════
        
        if (blockReason == null) {
            val activeVeto = hasActiveEdgeVeto(ts.mint)
            if (activeVeto != null) {
                val remainingSec = getVetoRemainingSeconds(ts.mint)
                blockReason = "EDGE_VETO_ACTIVE"
                blockLevel = BlockLevel.EDGE
                checks.add(GateCheck("edge_veto_sticky", false, 
                    "Vetoed ${remainingSec}s ago: ${activeVeto.reason} (quality=${activeVeto.quality})"))
                tags.add("edge_veto_sticky")
            } else {
                checks.add(GateCheck("edge_veto_sticky", true, "No active veto"))
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 1j: PAPER MODE QUALITY FILTER
        // 
        // For QUALITY training data, paper mode still needs SOME filters.
        // We want trades with real market signals, not pure garbage.
        // 
        // MINIMUM QUALITY THRESHOLDS (Paper Mode):
        //   - Buy pressure >= 50% (just above distribution zone)
        //   - Liquidity >= $3000 (actually tradeable)
        // 
        // Distribution triggers at ~48%. 50% gives 2% buffer - tight but workable.
        // ═══════════════════════════════════════════════════════════════════════
        
        if (blockReason == null && config.paperMode) {
            val minBuyPressurePaper = 42.0  // LOWERED: 42% to allow more learning trades
            val minLiquidityPaper = 2000.0  // $2000 minimum
            
            val hasBuyerInterest = ts.meta.pressScore >= minBuyPressurePaper
            val hasLiquidity = ts.lastLiquidityUsd >= minLiquidityPaper
            
            if (!hasBuyerInterest) {
                blockReason = "PAPER_NEAR_DISTRIBUTION_${ts.meta.pressScore.toInt()}%"
                blockLevel = BlockLevel.CONFIDENCE
                checks.add(GateCheck("paper_quality", false, "buy%=${ts.meta.pressScore.toInt()} < $minBuyPressurePaper (too close to distribution zone)"))
                tags.add("near_distribution")
            } else if (!hasLiquidity) {
                blockReason = "PAPER_LOW_LIQUIDITY_$${ts.lastLiquidityUsd.toInt()}"
                blockLevel = BlockLevel.CONFIDENCE
                checks.add(GateCheck("paper_quality", false, "liq=$${ts.lastLiquidityUsd.toInt()} < $minLiquidityPaper"))
                tags.add("paper_illiquid")
            } else {
                checks.add(GateCheck("paper_quality", true, "buy%=${ts.meta.pressScore.toInt()} liq=$${ts.lastLiquidityUsd.toInt()}"))
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // GATE 1j: PHASE FILTER
        // 
        // LIVE MODE: Strict - unknown phases need high conviction
        // PAPER MODE: Relaxed - unknown phases allowed if buy% >= 52
        // ═══════════════════════════════════════════════════════════════════════
        
        if (blockReason == null && candidate.phase.lowercase().contains("unknown")) {
            if (config.paperMode) {
                // PAPER MODE: Allow unknown phases with decent buy pressure
                val minBuyPressureUnknown = 48.0  // LOWERED from 52%
                if (ts.meta.pressScore >= minBuyPressureUnknown) {
                    checks.add(GateCheck("phase_filter", true, "PAPER: unknown phase OK (buy%=${ts.meta.pressScore.toInt()} >= $minBuyPressureUnknown)"))
                    tags.add("phase_unknown_allowed")
                } else {
                    blockReason = "UNKNOWN_PHASE_WEAK_BUYERS"
                    blockLevel = BlockLevel.CONFIDENCE
                    checks.add(GateCheck("phase_filter", false, "unknown phase + weak buyers (buy%=${ts.meta.pressScore.toInt()} < $minBuyPressureUnknown)"))
                    tags.add("phase_unknown_garbage")
                }
            } else {
                // LIVE MODE: Strict filtering for real money
                val minScore = 80.0
                val minBuyPressure = 65.0
                val isHighScore = candidate.entryScore >= minScore
                val isHighBuyPressure = ts.meta.pressScore >= minBuyPressure
                
                if (!isHighScore || !isHighBuyPressure) {
                    blockReason = "UNKNOWN_PHASE_LOW_CONVICTION"
                    blockLevel = BlockLevel.CONFIDENCE
                    checks.add(GateCheck("phase_filter", false, 
                        "phase=${candidate.phase} score=${candidate.entryScore.toInt()}<${minScore.toInt()} OR buy%=${ts.meta.pressScore.toInt()}<${minBuyPressure.toInt()}"))
                    tags.add("phase_unknown_weak")
                } else {
                    checks.add(GateCheck("phase_filter", true, "unknown phase OK (score=${candidate.entryScore.toInt()} buy%=${ts.meta.pressScore.toInt()})"))
                }
            }
        } else if (blockReason == null) {
            checks.add(GateCheck("phase_filter", true, "phase=${candidate.phase}"))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 2: EDGE VETO
        // 
        // LIVE MODE: Edge veto ENFORCED strictly
        // PAPER MODE: Edge veto OVERRIDABLE with market confirmation
        //             
        // Edge says "SKIP" based on pattern analysis, but it could be wrong.
        // In paper mode, we override IF the market shows decent interest:
        //   - Buy pressure >= 54% (lowered to allow more learning)
        //   - Liquidity >= $5000 (real money in the pool)
        // 
        // 54% gives 6% buffer above distribution zone (48%).
        // ─────────────────────────────────────────────────────────────────────
        
        val edgeVerdict = when (candidate.edgeQuality.uppercase()) {
            "STRONG", "A", "A+" -> EdgeVerdict.STRONG
            "WEAK", "B", "OK" -> EdgeVerdict.WEAK
            else -> EdgeVerdict.SKIP
        }
        
        if (blockReason == null && edgeVerdict == EdgeVerdict.SKIP) {
            if (config.paperMode) {
                // PAPER MODE: Override edge veto with decent market confirmation
                // Changed to OR logic - either strong buyers OR good liquidity is enough
                val minBuyPressureOverride = 48.0  // LOWERED from 50%
                val minLiquidityOverride = 2500.0  // LOWERED from 3000
                
                val hasStrongBuyers = ts.meta.pressScore >= minBuyPressureOverride
                val hasGoodLiquidity = ts.lastLiquidityUsd >= minLiquidityOverride
                
                if (hasStrongBuyers || hasGoodLiquidity) {
                    // Market confirmation - override edge veto for learning
                    checks.add(GateCheck("edge", true, 
                        "PAPER: edge override (buy%=${ts.meta.pressScore.toInt()}>=$minBuyPressureOverride OR liq=$${ts.lastLiquidityUsd.toInt()}>=$minLiquidityOverride)"))
                    tags.add("edge_override_confirmed")
                } else {
                    // No confirmation - respect edge veto
                    blockReason = "EDGE_VETO_NO_CONFIRMATION"
                    blockLevel = BlockLevel.EDGE
                    checks.add(GateCheck("edge", false, "edge=${candidate.edgeQuality} no confirm: buy%=${ts.meta.pressScore.toInt()}<$minBuyPressureOverride AND liq<$minLiquidityOverride"))
                    tags.add("edge_skip_unconfirmed")
                }
            } else {
                // LIVE MODE: Enforce edge veto strictly
                blockReason = "EDGE_VETO_${candidate.edgeQuality}"
                blockLevel = BlockLevel.EDGE
                checks.add(GateCheck("edge", false, "edge=${candidate.edgeQuality} quality=${candidate.setupQuality}"))
                tags.add("edge_skip")
            }
        } else if (blockReason == null) {
            checks.add(GateCheck("edge", true, null))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 3: CONFIDENCE THRESHOLD
        // ─────────────────────────────────────────────────────────────────────
        
        val confidenceThreshold = if (config.paperMode) paperConfidenceMin else liveConfidenceMin
        
        if (blockReason == null && candidate.aiConfidence < confidenceThreshold) {
            blockReason = "LOW_CONFIDENCE_${candidate.aiConfidence.toInt()}%"
            blockLevel = BlockLevel.CONFIDENCE
            checks.add(GateCheck("confidence", false, "conf=${candidate.aiConfidence.toInt()}% < $confidenceThreshold"))
            tags.add("low_confidence")
        } else if (blockReason == null) {
            checks.add(GateCheck("confidence", true, null))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 4: MODE-SPECIFIC RULES
        // ─────────────────────────────────────────────────────────────────────
        
        if (blockReason == null && !config.paperMode) {
            // LIVE MODE additional restrictions
            
            // Must have autoTrade enabled
            if (!config.autoTrade) {
                blockReason = "LIVE_AUTO_TRADE_DISABLED"
                blockLevel = BlockLevel.MODE
                checks.add(GateCheck("auto_trade", false, "autoTrade=false"))
            }
            
            // Stricter quality requirement in live
            if (blockReason == null && candidate.setupQuality == "C") {
                blockReason = "LIVE_QUALITY_TOO_LOW"
                blockLevel = BlockLevel.MODE
                checks.add(GateCheck("live_quality", false, "quality=C (live requires B+)"))
            }
        }
        
        if (blockReason == null) {
            checks.add(GateCheck("mode_rules", true, null))
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // GATE 5: SIZING VALIDATION
        // ─────────────────────────────────────────────────────────────────────
        
        var finalSize = proposedSizeSol
        
        if (blockReason == null) {
            // Minimum size check
            val minSize = if (config.paperMode) 0.001 else 0.01
            if (finalSize < minSize) {
                blockReason = "SIZE_TOO_SMALL"
                blockLevel = BlockLevel.SIZE
                checks.add(GateCheck("min_size", false, "size=$finalSize < $minSize"))
            } else {
                checks.add(GateCheck("min_size", true, null))
            }
            
            // Maximum size cap (safety)
            val maxSize = if (config.paperMode) 1.0 else 0.5
            if (finalSize > maxSize) {
                finalSize = maxSize
                checks.add(GateCheck("max_size", true, "capped from $proposedSizeSol to $maxSize"))
                tags.add("size_capped")
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // BUILD FINAL DECISION
        // ─────────────────────────────────────────────────────────────────────
        
        // Add source/phase tags
        if (ts.source.isNotBlank()) tags.add("src:${ts.source}")
        if (ts.phase.isNotBlank()) tags.add("phase:${ts.phase}")
        
        val shouldTrade = blockReason == null && candidate.shouldTrade
        
        // ─────────────────────────────────────────────────────────────────────
        // DETERMINE APPROVAL CLASS
        // This enables clean analytics separation between:
        //   - Benchmark: Strategy quality under strict rules
        //   - Exploration: Learning from weaker setups
        // ─────────────────────────────────────────────────────────────────────
        
        val (approvalClass, approvalReason) = when {
            // Blocked trades
            !shouldTrade -> ApprovalClass.BLOCKED to "blocked: ${blockReason ?: "unknown"}"
            
            // Live mode - all approvals are strict
            !config.paperMode -> ApprovalClass.LIVE to "live mode approval"
            
            // Paper mode - determine if benchmark or exploration
            else -> {
                // Would this trade pass LIVE mode rules?
                val wouldPassLiveEdge = edgeVerdict != EdgeVerdict.SKIP
                val wouldPassLiveQuality = candidate.setupQuality in listOf("A+", "A", "B")
                val wouldPassLiveConfidence = candidate.aiConfidence >= liveConfidenceMin
                
                if (wouldPassLiveEdge && wouldPassLiveQuality && wouldPassLiveConfidence) {
                    // This is benchmark quality - would pass in live mode
                    ApprovalClass.PAPER_BENCHMARK to "benchmark: passes live rules (edge=$wouldPassLiveEdge quality=${candidate.setupQuality} conf=${candidate.aiConfidence.toInt()}%)"
                } else {
                    // This is exploration - relaxed for learning
                    val relaxedReasons = mutableListOf<String>()
                    if (!wouldPassLiveEdge) relaxedReasons.add("edge=${edgeVerdict.name}")
                    if (!wouldPassLiveQuality) relaxedReasons.add("quality=${candidate.setupQuality}")
                    if (!wouldPassLiveConfidence) relaxedReasons.add("conf=${candidate.aiConfidence.toInt()}%<${liveConfidenceMin.toInt()}%")
                    ApprovalClass.PAPER_EXPLORATION to "exploration: relaxed ${relaxedReasons.joinToString(", ")}"
                }
            }
        }
        
        // Add approval class to tags for easy filtering
        tags.add("class:${approvalClass.name}")
        
        return FinalDecision(
            shouldTrade = shouldTrade,
            mode = mode,
            approvalClass = approvalClass,
            quality = candidate.finalQuality,
            confidence = candidate.aiConfidence,
            edge = edgeVerdict,
            blockReason = blockReason,
            blockLevel = blockLevel,
            sizeSol = finalSize,
            tags = tags,
            mint = ts.mint,
            symbol = ts.symbol,
            approvalReason = approvalReason,
            gateChecks = checks,
        )
    }
    
    /**
     * Log a blocked trade for learning purposes.
     * This is how we "learn" from blocked trades without executing them.
     */
    fun logBlockedTrade(decision: FinalDecision, onLog: (String) -> Unit) {
        onLog("🚫 FDG BLOCKED: ${decision.symbol} | ${decision.blockReason} | " +
              "quality=${decision.quality} conf=${decision.confidence.toInt()}% " +
              "edge=${decision.edge.name}")
        
        // Log the gate checks for debugging
        val failedChecks = decision.gateChecks.filter { !it.passed }
        if (failedChecks.isNotEmpty()) {
            onLog("   Failed checks: ${failedChecks.joinToString { "${it.name}(${it.reason})" }}")
        }
    }
    
    /**
     * Log an approved trade with explicit approval class.
     */
    fun logApprovedTrade(decision: FinalDecision, onLog: (String) -> Unit) {
        val classIcon = when (decision.approvalClass) {
            ApprovalClass.LIVE -> "🔴"
            ApprovalClass.PAPER_BENCHMARK -> "🟢"
            ApprovalClass.PAPER_EXPLORATION -> "🟡"
            ApprovalClass.BLOCKED -> "⬛"
        }
        onLog("$classIcon FDG ${decision.approvalClass}: ${decision.symbol} | ${decision.quality} | " +
              "${decision.confidence.toInt()}% | ${decision.sizeSol.format(3)} SOL")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // THRESHOLD LEARNING (called by BotBrain after trades)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Update thresholds based on learned values from BotBrain.
     */
    fun updateThresholds(
        rugcheckMin: Int? = null,
        buyPressureMin: Double? = null,
        topHolderMax: Double? = null,
    ) {
        rugcheckMin?.let { hardBlockRugcheckMin = it }
        buyPressureMin?.let { hardBlockBuyPressureMin = it }
        topHolderMax?.let { hardBlockTopHolderMax = it }
    }
    
    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}
