package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * GLOBAL TRADE REGISTRY - V4.0 THREAD-SAFE WATCHLIST & STATE MANAGEMENT
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * PROBLEM SOLVED:
 * The watchlist was randomly resetting from 31 tokens to 1 due to:
 * 1. Multiple threads reading/writing to cfg.watchlist
 * 2. ConfigStore.save() being called with stale watchlist data
 * 3. No synchronization between BotService, scanners, and AI layers
 * 4. Race conditions when tokens are added/removed simultaneously
 *
 * SOLUTION:
 * Single source of truth for ALL token tracking state:
 * - Watchlist (tokens being monitored)
 * - Active positions (across ALL layers)
 * - Duplicate suppression (prevent re-adding same token)
 * - Exposure tracking (total SOL committed)
 *
 * USAGE:
 * - ALL watchlist mutations go through GlobalTradeRegistry
 * - ConfigStore.watchlist is READ-ONLY after init
 * - Scanners call addToWatchlist() instead of modifying cfg directly
 * - BotService calls getWatchlist() to get current list
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object GlobalTradeRegistry {

    private const val TAG = "GlobalTradeReg"

    // ═══════════════════════════════════════════════════════════════════════════
    // THREAD-SAFE WATCHLIST
    // ═══════════════════════════════════════════════════════════════════════════

    // Master watchlist - ConcurrentHashMap for thread safety
    // Key: mint address, Value: WatchlistEntry with metadata
    private val watchlist = ConcurrentHashMap<String, WatchlistEntry>()

    // Recently processed tokens - prevents duplicate processing in same cycle
    // Key: mint, Value: last processed timestamp
    private val recentlyProcessed = ConcurrentHashMap<String, Long>()

    // Tokens that have been rejected - prevents re-adding rejected tokens
    // Key: mint, Value: RejectionEntry
    private val rejectedTokens = ConcurrentHashMap<String, RejectionEntry>()

    // Active positions across ALL layers
    // Key: mint, Value: PositionEntry
    private val activePositions = ConcurrentHashMap<String, PositionEntry>()

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.0 PROBATION TIER
    // ═══════════════════════════════════════════════════════════════════════════
    // Single-source or low-confidence tokens must prove themselves before
    // being promoted to the main watchlist. This prevents registry flooding.
    // Key: mint, Value: ProbationEntry
    private val probation = ConcurrentHashMap<String, ProbationEntry>()

    // Counters
    private val totalTokensAdded = AtomicLong(0)
    private val totalTokensRemoved = AtomicLong(0)
    private val duplicatesBlocked = AtomicLong(0)
    private val probationPromotions = AtomicLong(0)
    private val probationRejections = AtomicLong(0)

    // Timing constants
    // V5.9.162 — aggressive bootstrap volume boost. User: "it was smashing
    // out hundreds of trades an hour". 20s duplicate/rejection cooldowns on
    // the same mint were capping re-discovery throughput on a hot watchlist;
    // 5s per-token processing cooldown was serialising evaluation of the
    // same mint across rapid scan ticks. All dropped hard for memetrader
    // volume. Bootstrap-only: tightens back above 40% learning via
    // effective*() helpers below.
    // V5.9.1518 — PATCH ITEM 4: duplicate-lane cooldown reduced 25% (20s→15s)
    // to let a hot watchlist re-discover the same mint sooner without bypassing
    // any safety gate. Bootstrap (3s) unchanged.
    private const val DUPLICATE_COOLDOWN_MS = 15_000L   // mature: 15s (was 20s, -25%)
    private const val DUPLICATE_COOLDOWN_MS_BOOTSTRAP = 3_000L
    private const val REJECTION_COOLDOWN_MS = 20_000L   // mature: 20s
    private const val REJECTION_COOLDOWN_MS_BOOTSTRAP = 3_000L
    private const val PROCESS_COOLDOWN_MS = 5_000L      // mature: 5s
    private const val PROCESS_COOLDOWN_MS_BOOTSTRAP = 1_000L

    private fun isBootstrapPhase(): Boolean = try {
        com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() &&
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() < 0.40
    } catch (_: Exception) { false }

    private fun effectiveDuplicateCooldownMs(): Long =
        if (isBootstrapPhase()) DUPLICATE_COOLDOWN_MS_BOOTSTRAP else DUPLICATE_COOLDOWN_MS

    private fun effectiveRejectionCooldownMs(): Long =
        if (isBootstrapPhase()) REJECTION_COOLDOWN_MS_BOOTSTRAP else REJECTION_COOLDOWN_MS

    private fun effectiveProcessCooldownMs(): Long =
        if (isBootstrapPhase()) PROCESS_COOLDOWN_MS_BOOTSTRAP else PROCESS_COOLDOWN_MS

    // V5.0 Probation constants
    // V5.0.4170 — PROBATION TIGHTENING (mobile data conservation).
    // Operator dump showed PROBATION=3753 intakes per session. Dust mints
    // that never graduate sit in probation for up to 5 minutes burning
    // refresh/enrichment cycles for the whole window. Tightening MAX
    // 5min → 2min and MIN 60s → 30s evicts statistically-dead candidates
    // sooner without affecting tokens that actually mature (the bot's
    // typical promotion happens in 15–90 s based on PROBATION_RESULT
    // forensic data). Net: ~60% less probation-window refresh waste.
    private const val PROBATION_MIN_TIME_MS = 30_000L       // 30s minimum probation (was 60s)
    private const val PROBATION_MAX_TIME_MS = 120_000L      // 2 minutes max (was 5 minutes)
    private const val STALE_POSITION_MS = 30 * 60_000L     // V5.9.1506 — ghost-prune cutoff (far beyond any meme hold)
    private const val PROBATION_CONF_THRESHOLD = 50         // Confidence below this = probation
    private const val PROBATION_CONF_THRESHOLD_PAPER = 22   // V5.9.266: moderate (was 18 at V5.9.263)
    private const val PROBATION_MULTI_SOURCE_EXEMPT = true  // Multi-source tokens skip probation

    // V5.2: Paper mode flag for more aggressive token acceptance
    @Volatile
    var isPaperMode: Boolean = false

    // Maximum watchlist size to prevent memory issues
    // V5.2: Increased for paper mode learning - need more exposure
    // V5.9.369: 300→500 to give 100+ idle bench depth (user feedback)
    private const val MAX_WATCHLIST_SIZE = 500  // V5.9.369: was 300; bumped for memetrader idle pool target ≥100
    private const val MAX_PROBATION_SIZE = 500

    // V5.0.3708 — STRICT SOURCE-BALANCED HOT WATCHLIST CAP.
    // Operator: "it isn't just a pumpfun bot — where are all the other exchanges,
    // apps and Solana tokens?" 1561 relaxed Pump to 65% and only reserved 20
    // non-pump slots, which let PumpPortal/PumpFun crowd out DexScreener,
    // Raydium, Meteora/Gecko, Birdeye, CoinGecko, wallet/social/app feeds.
    // Pump remains observable/promotable via probation, but it cannot be the
    // majority of the hot supervisor/UI bench.
    private const val MAX_PUMP_PORTAL_CONCURRENT = 175
    private const val MAX_PUMP_HOT_FRACTION = 0.35
    private const val MIN_NON_PUMP_RESERVED_HOT_SLOTS = 80

    private val pumpPortalRejections = AtomicLong(0)
    private val pumpPortalProbationDiversions = AtomicLong(0)

    /** Identifies whether an addedBy / source tag points at a PumpPortal-style intake. */
    private fun isPumpPortalSource(addedBy: String, source: String): Boolean {
        val tags = (addedBy + "|" + source).uppercase()
        return tags.contains("PUMP_PORTAL") ||
            tags.contains("PUMPPORTAL") ||
            tags.contains("PUMP_FUN") ||
            tags.contains("PUMPFUN") ||
            tags.contains("PUMP_GRADUATE") ||
            tags.contains("PUMPGRADUATE")
    }

    /** Count of current pump-source entries (cheap, scans the small watchlist map). */
    fun pumpPortalConcurrentCount(): Int =
        watchlist.values.count { isPumpPortalSource(it.addedBy, it.source) }

    fun sourceMixSnapshot(): Map<String, Int> = mapOf(
        "pump" to pumpPortalConcurrentCount(),
        "nonPump" to watchlist.values.count { !isPumpPortalSource(it.addedBy, it.source) },
        "total" to watchlist.size,
        "probation" to probation.size,
    )

    private fun pumpHotCapFor(totalHot: Int): Int {
        val total = totalHot.coerceAtLeast(1)
        val dynamic = kotlin.math.ceil(total * MAX_PUMP_HOT_FRACTION).toInt().coerceAtLeast(1)
        val reserveBound = if (total > MIN_NON_PUMP_RESERVED_HOT_SLOTS) {
            (total - MIN_NON_PUMP_RESERVED_HOT_SLOTS).coerceAtLeast(1)
        } else {
            dynamic
        }
        return minOf(MAX_PUMP_PORTAL_CONCURRENT, maxOf(1, minOf(dynamic, reserveBound)))
    }

    private fun hasNonPumpConfirmation(addedBy: String, source: String): Boolean {
        val tags = (addedBy + "|" + source).uppercase()
        return tags.contains("DEX_") || tags.contains("DEXSCREENER") ||
            tags.contains("RAYDIUM") || tags.contains("COINGECKO") ||
            tags.contains("GECKO") || tags.contains("METEORA") ||
            tags.contains("BIRDEYE") || tags.contains("ORCA") ||
            tags.contains("JUPITER") || tags.contains("HELIUS") ||
            tags.contains("SOLANA") || tags.contains("WALLET") ||
            tags.contains("APP") || tags.contains("CMC") || tags.contains("WHALE") ||
            tags.contains("V3_PREMIUM") || tags.contains("BOOSTED") ||
            tags.contains("TRENDING")
    }

    private fun strongPumpHotException(
        addedBy: String,
        source: String,
        initialMcap: Double,
        laneAffinity: Set<String>,
        toolAffinity: Set<String>,
    ): Boolean {
        if (!isPumpPortalSource(addedBy, source)) return false
        val tags = (addedBy + "|" + source + "|" + laneAffinity.joinToString("|") + "|" + toolAffinity.joinToString("|")).uppercase()
        val specialistAffinity = tags.contains("SHITCOIN") || tags.contains("MOONSHOT") || tags.contains("EXPRESS") || tags.contains("SNIPER") || tags.contains("MEME")
        val strongMcap = initialMcap >= 50_000.0
        val strongTooling = toolAffinity.size >= 2 || laneAffinity.size >= 2
        // V5.0.3723 — source-balance butterfly: keep the strict 35% Pump cap for
        // weak single-source Pump spam, but do not throw high-conviction fresh meme
        // candidates into slow probation purely because the quota is full. This is
        // still source-balanced: weak Pump remains capped, non-Pump reservation stays,
        // and multi-source/non-pump confirmation continues to hot-admit above.
        return specialistAffinity && (strongMcap || strongTooling)
    }

    private fun shouldDivertPumpToProbation(
        addedBy: String,
        source: String,
        initialMcap: Double = 0.0,
        laneAffinity: Set<String> = emptySet(),
        toolAffinity: Set<String> = emptySet(),
    ): Boolean {
        if (!isPumpPortalSource(addedBy, source)) return false
        if (hasNonPumpConfirmation(addedBy, source)) return false // multi-source confirmation earns hot admission
        if (strongPumpHotException(addedBy, source, initialMcap, laneAffinity, toolAffinity)) return false
        val total = watchlist.size
        if (total < 12) return false // cold start: let a few through so UI is alive
        // V5.0.3708 — strict means strict for weak single-source Pump rows. Do not
        // admit Pump merely because non-pump is sparse; strong Pump exceptions are
        // handled above and are narrow, auditable, and telemetry-visible.
        val currentPump = pumpPortalConcurrentCount()
        return currentPump >= pumpHotCapFor(total + 1)
    }

    fun pumpPortalRejectionCount(): Long = pumpPortalRejections.get()
    fun pumpPortalProbationDiversionCount(): Long = pumpPortalProbationDiversions.get()

    fun pumpPortalCapMax(): Int = MAX_PUMP_PORTAL_CONCURRENT

    data class WatchlistEntry(
        val mint: String,
        val symbol: String,
        var addedAt: Long,  // V5.9.1328 — mutable: refreshed on duplicate intake so WATCHLIST_RR fresh-window stays honest
        val addedBy: String,  // "SCANNER", "USER", "DEX_BOOSTED", "PUMP_FUN", etc.
        val source: String,   // More specific: "pump.fun", "raydium", "moonshot"
        val initialMcap: Double,
        var initialLiquidityUsd: Double = 0.0,
        var initialConfidence: Int = 0,
        val laneAffinity: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>(),
        val toolAffinity: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>(),
        var lastProcessedAt: Long = 0,
        var processCount: Int = 0,
    )

    data class RejectionEntry(
        val mint: String,
        val symbol: String,
        val rejectedAt: Long,
        val reason: String,
        val rejectedBy: String,  // "V3", "FDG", "FILTERS", etc.
    )

    data class PositionEntry(
        val mint: String,
        val symbol: String,
        val layer: String,  // "V3", "TREASURY", "BLUE_CHIP", "SHITCOIN"
        val openedAt: Long,
        val sizeSol: Double,
        var currentPnlPct: Double = 0.0,
    )

    /**
     * V5.0 PROBATION ENTRY
     * Tokens in probation need additional confirmation before being promoted.
     */
    data class ProbationEntry(
        val mint: String,
        val symbol: String,
        val addedAt: Long,
        val addedBy: String,
        val source: String,
        val initialMcap: Double,
        val initialLiquidity: Double,
        val initialConfidence: Int,
        val isEstimatedLiquidity: Boolean,  // True if liquidity was estimated, not confirmed
        val isSingleSource: Boolean,        // True if only 1 scanner found it
        var additionalScanners: MutableSet<String> = mutableSetOf(),  // Other scanners that found it later
        val laneAffinity: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>(),
        val toolAffinity: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>(),
        var rcScore: Int = -1,              // Rugcheck score (-1 = not checked)
        var priceAtAdd: Double = 0.0,       // Track price to check if it holds
        var currentPrice: Double = 0.0,
        var promotionReason: String? = null,
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    // V4.0 CRITICAL: Flag to prevent re-initialization during runtime
    @Volatile
    private var initialized = false

    /**
     * Initialize from ConfigStore watchlist.
     * Called once at bot startup. BLOCKED if already initialized.
     */
    fun init(initialWatchlist: List<String>, defaultSource: String = "CONFIG") {
        // V4.0 CRITICAL: Guard against re-initialization
        if (initialized && watchlist.isNotEmpty()) {
            ErrorLogger.warn(TAG, "⚠️ init() called again with ${initialWatchlist.size} tokens - BLOCKED (already has ${watchlist.size} tokens)")
            return
        }

        watchlist.clear()
        val now = System.currentTimeMillis()

        for (mint in initialWatchlist) {
            if (mint.isNotBlank() && mint.length > 30) {
                watchlist[mint] = WatchlistEntry(
                    mint = mint,
                    symbol = mint.take(8),  // Will be updated when token data is fetched
                    addedAt = now,
                    addedBy = defaultSource,
                    source = defaultSource,
                    initialMcap = 0.0,
                )
            }
        }

        initialized = true
        ErrorLogger.info(TAG, "✅ Initialized with ${watchlist.size} tokens from $defaultSource (ONE-TIME)")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WATCHLIST OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Add a token to the watchlist.
     * Returns true if added, false if duplicate/rejected/full.
     */
    fun addToWatchlist(
        mint: String,
        symbol: String,
        addedBy: String,
        source: String = addedBy,
        initialMcap: Double = 0.0,
        initialLiquidityUsd: Double = 0.0,
        confidence: Int = 0,
        laneAffinity: Set<String> = emptySet(),
        toolAffinity: Set<String> = emptySet(),
    ): AddResult {
        // Validate mint
        if (mint.isBlank() || mint.length < 30) {
            return AddResult(false, "INVALID_MINT")
        }

        val now = System.currentTimeMillis()

        if (ScannerHardRejectStore.isRejected(mint)) {
            PipelineTracer.registryRejected(symbol, mint, "SCANNER_HARD_REJECT:${ScannerHardRejectStore.reason(mint).take(80)}")
            return AddResult(false, "SCANNER_HARD_REJECT", probation = false)
        }

        // Check if already in watchlist
        val existing = watchlist[mint]
        if (existing != null) {
            existing.laneAffinity.addAll(laneAffinity.map { it.uppercase() })
            existing.toolAffinity.addAll(toolAffinity.map { it.uppercase() })
            // V5.0.3732 — preserve freshest known intake liquidity/confidence on duplicate hits.
            // Source-balance rebalancing can run before BotService hydrates status.tokens;
            // without registry-level liquidity, demotion fabricated liq=$0 for real $5k+ mints.
            if (initialLiquidityUsd > existing.initialLiquidityUsd) existing.initialLiquidityUsd = initialLiquidityUsd
            if (confidence > existing.initialConfidence) existing.initialConfidence = confidence
            // V5.9.1328 — ROOT FIX B: Refresh addedAt on duplicate intake.
            // Without this, brand-new pump-portal tokens (which keep re-arriving
            // from the WS feed every few seconds) carry an addedAt timestamp
            // from their FIRST sighting, instantly aging out of the
            // FRESH_WINDOW_MS=120s classification used by WATCHLIST_RR. Result:
            // every cycle reports fresh=0 even when 30+ truly-fresh memes
            // arrived in the last minute, so the round-robin starves the
            // execution path of fresh candidates. Refreshing addedAt restores
            // the "fresh" classification semantics the round-robin assumes.
            existing.addedAt = now
            duplicatesBlocked.incrementAndGet()
            return AddResult(false, "DUPLICATE: already watching since ${(now - existing.addedAt)/1000}s ago")
        }

        // V5.9.626 — PROTECTED INTAKE: rejection/process memory must not
        // block admission into the scanner/watchlist universe. Rejection memory
        // is still useful for EXECUTION gates, but the intake bench must remain
        // observable so candidates can hydrate, learn, rehabilitate, and be
        // fairly re-qualified. Old behavior returned here and could make Meme
        // Trader show 0 tokens even while raw discovery streams were alive.
        val rejection = rejectedTokens[mint]
        if (rejection != null) {
            val elapsed = now - rejection.rejectedAt
            if (elapsed >= effectiveRejectionCooldownMs()) {
                rejectedTokens.remove(mint)
            } else {
                ErrorLogger.debug(TAG, "🛡️ Protected intake admits $symbol despite rejection memory: ${rejection.reason} (${elapsed/1000}s ago)")
            }
        }

        val lastProcessed = recentlyProcessed[mint]
        if (lastProcessed != null) {
            val elapsed = now - lastProcessed
            if (elapsed < effectiveDuplicateCooldownMs()) {
                ErrorLogger.debug(TAG, "🛡️ Protected intake admits $symbol despite recent processed marker: ${elapsed/1000}s ago")
            }
        }

        // V5.9.1560 — source-balanced hot watchlist. Excess pump-origin rows
        // go to probation instead of occupying the visible/supervisor hot bench.
        // They are NOT deleted: later multi-source confirmation / RC / price action
        // can still promote them.
        if (shouldDivertPumpToProbation(addedBy, source, initialMcap, laneAffinity, toolAffinity)) {
            pumpPortalProbationDiversions.incrementAndGet()
            val current = pumpPortalConcurrentCount()
            val cap = pumpHotCapFor(watchlist.size + 1)
            try {
                addToProbation(
                    mint = mint,
                    symbol = symbol,
                    addedBy = addedBy,
                    source = "SOURCE_BALANCE_DIVERT:$source",
                    initialMcap = initialMcap,
                    liquidityUsd = initialLiquidityUsd.takeIf { it > 0.0 } ?: (initialMcap / 10.0),
                    confidence = confidence,
                    isEstimatedLiquidity = false,
                    isSingleSource = true,
                    price = 0.0,
                    laneAffinity = laneAffinity,
                    toolAffinity = toolAffinity,
                )
            } catch (_: Throwable) {}
            return AddResult(false, "SOURCE_BALANCE_PUMP_PROBATION: pump=$current cap=$cap total=${watchlist.size}", probation = true)
        }

        // V5.2: No max size check - let watchlist grow as needed
        // Learning requires seeing many tokens
        // Stale tokens are pruned automatically by age/loss tracking

        if (strongPumpHotException(addedBy, source, initialMcap, laneAffinity, toolAffinity)) {
            try { ForensicLogger.lifecycle("SOURCE_BALANCE_PUMP_STRONG_HOT_ADMIT", "symbol=$symbol mint=${mint.take(10)} mcap=${initialMcap.toInt()} lane=${laneAffinity.joinToString("+")} tools=${toolAffinity.joinToString("+")} source=$source") } catch (_: Throwable) {}
        }

        // Add to watchlist
        watchlist[mint] = WatchlistEntry(
            mint = mint,
            symbol = symbol,
            addedAt = now,
            addedBy = addedBy,
            source = source,
            initialMcap = initialMcap,
            initialLiquidityUsd = initialLiquidityUsd,
            initialConfidence = confidence,
        ).also { entry ->
            entry.laneAffinity.addAll(laneAffinity.map { it.uppercase() })
            entry.toolAffinity.addAll(toolAffinity.map { it.uppercase() })
        }

        totalTokensAdded.incrementAndGet()
        ErrorLogger.debug(TAG, "➕ Added $symbol | by=$addedBy | source=$source | mcap=\$${initialMcap.toLong()}")

        return AddResult(true, "ADDED")
    }

    data class AddResult(
        val added: Boolean,
        val reason: String,
        val probation: Boolean = false,  // V5.0: True if token went to probation instead
    )

    /**
     * V5.0: Add a token with probation awareness.
     * Low-confidence or single-source tokens go to probation first.
     * Multi-source tokens or USER_ADDED bypass probation.
     */
    fun addWithProbation(
        mint: String,
        symbol: String,
        addedBy: String,
        source: String = addedBy,
        initialMcap: Double = 0.0,
        liquidityUsd: Double = 0.0,
        confidence: Int = 50,
        isMultiSource: Boolean = false,
        isEstimatedLiquidity: Boolean = false,
        price: Double = 0.0,
        laneAffinity: Set<String> = emptySet(),
        toolAffinity: Set<String> = emptySet(),
    ): AddResult {
        // Validate mint
        if (mint.isBlank() || mint.length < 30) {
            PipelineTracer.registryRejected(symbol, mint, "INVALID_MINT")
            return AddResult(false, "INVALID_MINT")
        }

        val now = System.currentTimeMillis()

        if (ScannerHardRejectStore.isRejected(mint)) {
            PipelineTracer.registryRejected(symbol, mint, "SCANNER_HARD_REJECT:${ScannerHardRejectStore.reason(mint).take(80)}")
            return AddResult(false, "SCANNER_HARD_REJECT", probation = false)
        }

        // Check if already in watchlist
        if (watchlist.containsKey(mint)) {
            duplicatesBlocked.incrementAndGet()
            PipelineTracer.registryDuplicate(symbol, mint, "WATCHLIST")
            return AddResult(false, "DUPLICATE: already in watchlist")
        }

        // Check if already in probation - update with additional scanner
        val existingProbation = probation[mint]
        if (existingProbation != null) {
            existingProbation.additionalScanners.add(addedBy)
            existingProbation.laneAffinity.addAll(laneAffinity.map { it.uppercase() })
            existingProbation.toolAffinity.addAll(toolAffinity.map { it.uppercase() })
            PipelineTracer.registryDuplicate(symbol, mint, "PROBATION")
            // Check if this promotes it
            if (existingProbation.additionalScanners.size >= 1) {
                return promoteFromProbation(mint, "MULTI_SCANNER_CONFIRM")
            }
            return AddResult(false, "ALREADY_IN_PROBATION", probation = true)
        }

        // V5.9.626 — rejection memory cannot block protected intake.
        val rejection = rejectedTokens[mint]
        if (rejection != null) {
            val elapsed = now - rejection.rejectedAt
            if (elapsed >= effectiveRejectionCooldownMs()) {
                rejectedTokens.remove(mint)
            } else {
                ErrorLogger.debug(TAG, "🛡️ Protected probation/intake admits $symbol despite rejection memory: ${rejection.reason}")
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // PROBATION ROUTING DECISION
        // V5.2: Paper mode is MUCH more lenient for maximum learning exposure
        // V5.0.4021: Probation leniency is paper-only. Live proven edge may
        // shape size/routing, but it must not bypass probation/safety intake.
        // ═══════════════════════════════════════════════════════════════════
        val lenientMode = isPaperMode
        val confThreshold = if (lenientMode) PROBATION_CONF_THRESHOLD_PAPER else PROBATION_CONF_THRESHOLD

        val needsProbation = when {
            // USER_ADDED always bypasses probation
            addedBy == "USER_ADDED" || addedBy == "USER" || addedBy == "CONFIG" -> false
            // Paper mode - bypass probation for fast learning
            lenientMode && confidence >= confThreshold -> false
            // Multi-source tokens with sufficient confidence bypass
            isMultiSource && confidence >= confThreshold -> false
            // Low confidence = probation
            confidence < confThreshold -> true
            // Estimated liquidity = probation (strict-live only)
            isEstimatedLiquidity && !lenientMode -> true
            // Single source with borderline confidence = probation (strict-live only)
            !isMultiSource && confidence < 60 && !lenientMode -> true
            // Otherwise, allow
            else -> false
        }

        if (needsProbation) {
            // V5.9.642 — HYBRID 2440+PROTECTED INTAKE: probation is no
            // longer a holding pen. It is observational metadata only. The
            // scanner/watchlist bench must receive every valid Solana
            // candidate for upstream qualification; FDG/V3/sub-traders own
            // execution quality. Keep the probation record for UI/forensics,
            // but also admit the mint to the watchlist immediately.
            try {
                addToProbation(
                    mint = mint,
                    symbol = symbol,
                    addedBy = addedBy,
                    source = source,
                    initialMcap = initialMcap,
                    liquidityUsd = liquidityUsd,
                    confidence = confidence,
                    isEstimatedLiquidity = isEstimatedLiquidity,
                    isSingleSource = !isMultiSource,
                    price = price,
                    laneAffinity = laneAffinity,
                    toolAffinity = toolAffinity,
                )
            } catch (e: Throwable) {
                ErrorLogger.debug(TAG, "probation-observe failed for $symbol: ${e.message}")
            }
            val admitted = addToWatchlist(
                mint = mint,
                symbol = symbol,
                addedBy = addedBy,
                source = source,
                initialMcap = initialMcap,
                initialLiquidityUsd = liquidityUsd,
                confidence = confidence,
                laneAffinity = laneAffinity,
                toolAffinity = toolAffinity,
            )
            return if (admitted.added) {
                admitted.copy(reason = "ADDED_PROBATION_OBSERVED")
            } else {
                admitted
            }
        }

        // Direct add to watchlist
        return addToWatchlist(
            mint = mint,
            symbol = symbol,
            addedBy = addedBy,
            source = source,
            initialMcap = initialMcap,
            initialLiquidityUsd = liquidityUsd,
            confidence = confidence,
            laneAffinity = laneAffinity,
            toolAffinity = toolAffinity,
        )
    }

    /**
     * V5.0: Add token to probation tier.
     */
    private fun addToProbation(
        mint: String,
        symbol: String,
        addedBy: String,
        source: String,
        initialMcap: Double,
        liquidityUsd: Double,
        confidence: Int,
        isEstimatedLiquidity: Boolean,
        isSingleSource: Boolean,
        price: Double,
        laneAffinity: Set<String> = emptySet(),
        toolAffinity: Set<String> = emptySet(),
    ): AddResult {
        // Check probation size
        if (probation.size >= MAX_PROBATION_SIZE) {
            // Prune oldest probation entry
            val oldest = probation.values.minByOrNull { it.addedAt }
            if (oldest != null) {
                probation.remove(oldest.mint)
                probationRejections.incrementAndGet()
                ErrorLogger.debug(TAG, "🗑️ Probation pruned: ${oldest.symbol} (full)")
            }
        }

        val now = System.currentTimeMillis()
        probation[mint] = ProbationEntry(
            mint = mint,
            symbol = symbol,
            addedAt = now,
            addedBy = addedBy,
            source = source,
            initialMcap = initialMcap,
            initialLiquidity = liquidityUsd,
            initialConfidence = confidence,
            isEstimatedLiquidity = isEstimatedLiquidity,
            isSingleSource = isSingleSource,
            priceAtAdd = price,
            currentPrice = price,
        ).also { entry ->
            entry.laneAffinity.addAll(laneAffinity.map { it.uppercase() })
            entry.toolAffinity.addAll(toolAffinity.map { it.uppercase() })
        }

        val reason = when {
            isSingleSource -> "SINGLE_SOURCE"
            isEstimatedLiquidity -> "ESTIMATED_LIQ"
            confidence < PROBATION_CONF_THRESHOLD -> "LOW_CONF($confidence)"
            else -> "REVIEW_NEEDED"
        }

        ErrorLogger.info(TAG, "⏳ PROBATION: $symbol | $reason | conf=$confidence | liq=$${liquidityUsd.toInt()}")
        return AddResult(false, "PROBATION: $reason", probation = true)
    }

    /**
     * V5.9.1226 — demote existing hot-watchlist dead weight into probation.
     *
     * This is NOT scanner pruning. The token remains observable and can be
     * promoted by future scanner/source confirmation or price action, but it no
     * longer burns the main supervisor processTokenCycle budget every loop.
     */
    fun demoteWatchlistToProbation(
        mint: String,
        reason: String,
        liquidityUsd: Double = 0.0,
        confidence: Int = 0,
        price: Double = 0.0,
        isEstimatedLiquidity: Boolean = false,
    ): Boolean {
        if (mint.isBlank()) return false
        if (activePositions.containsKey(mint)) {
            ErrorLogger.debug(TAG, "🛡️ probation demote BLOCKED for $mint — active position open (reason=$reason)")
            return false
        }
        val entry = watchlist.remove(mint) ?: return false
        totalTokensRemoved.incrementAndGet()
        try {
            addToProbation(
                mint = entry.mint,
                symbol = entry.symbol,
                addedBy = "DEMOTED_${entry.addedBy}",
                source = "PROBATION_DEMOTE:$reason|${entry.source}",
                initialMcap = entry.initialMcap,
                liquidityUsd = liquidityUsd,
                confidence = confidence,
                isEstimatedLiquidity = isEstimatedLiquidity,
                isSingleSource = true,
                price = price,
                laneAffinity = entry.laneAffinity,
                toolAffinity = entry.toolAffinity,
            )
        } catch (t: Throwable) {
            ErrorLogger.debug(TAG, "probation demote add failed for ${entry.symbol}: ${t.message}")
        }
        ErrorLogger.info(TAG, "⏳ WATCHLIST→PROBATION: ${entry.symbol} | reason=$reason | processed=${entry.processCount} liq=$${liquidityUsd.toInt()}")
        return true
    }

    /**
     * V5.9.1228 — probation-only admission for cold/no-volume firehose intake.
     * Keeps the token observable/promotable without putting it in the hot
     * watchlist/supervisor lane immediately.
     */
    fun addToProbationOnly(
        mint: String,
        symbol: String,
        addedBy: String,
        source: String = addedBy,
        initialMcap: Double = 0.0,
        liquidityUsd: Double = 0.0,
        confidence: Int = 0,
        isEstimatedLiquidity: Boolean = false,
        price: Double = 0.0,
        laneAffinity: Set<String> = emptySet(),
        toolAffinity: Set<String> = emptySet(),
    ): AddResult {
        if (mint.isBlank() || mint.length < 30) return AddResult(false, "INVALID_MINT")
        if (ScannerHardRejectStore.isRejected(mint)) return AddResult(false, "SCANNER_HARD_REJECT", probation = false)
        if (watchlist.containsKey(mint)) return AddResult(false, "DUPLICATE: already in watchlist")
        probation[mint]?.let { existing ->
            existing.additionalScanners.add(addedBy)
            existing.laneAffinity.addAll(laneAffinity.map { it.uppercase() })
            existing.toolAffinity.addAll(toolAffinity.map { it.uppercase() })
            return AddResult(false, "ALREADY_IN_PROBATION", probation = true)
        }
        return addToProbation(
            mint = mint,
            symbol = symbol,
            addedBy = addedBy,
            source = source,
            initialMcap = initialMcap,
            liquidityUsd = liquidityUsd,
            confidence = confidence,
            isEstimatedLiquidity = isEstimatedLiquidity,
            isSingleSource = true,
            price = price,
            laneAffinity = laneAffinity,
            toolAffinity = toolAffinity,
        )
    }

    /**
     * V5.0: Promote a token from probation to watchlist.
     */
    fun promoteFromProbation(mint: String, reason: String): AddResult {
        val entry = probation.remove(mint) ?: return AddResult(false, "NOT_IN_PROBATION")

        // Add to watchlist
        val now = System.currentTimeMillis()
        watchlist[mint] = WatchlistEntry(
            mint = mint,
            symbol = entry.symbol,
            addedAt = now,
            addedBy = "${entry.addedBy}+PROBATION",
            source = entry.source,
            initialMcap = entry.initialMcap,
        ).also { wl ->
            wl.laneAffinity.addAll(entry.laneAffinity)
            wl.toolAffinity.addAll(entry.toolAffinity)
        }

        totalTokensAdded.incrementAndGet()
        probationPromotions.incrementAndGet()

        ErrorLogger.info(TAG, "✅ PROMOTED: ${entry.symbol} | reason=$reason | was in probation ${(now - entry.addedAt)/1000}s")
        return AddResult(true, "PROMOTED: $reason")
    }

    /**
     * V5.0: Reject a token from probation.
     */
    fun rejectFromProbation(mint: String, reason: String) {
        val entry = probation.remove(mint) ?: return

        registerRejection(mint, entry.symbol, "PROBATION_FAILED: $reason", "PROBATION")
        probationRejections.incrementAndGet()

        ErrorLogger.info(TAG, "❌ PROBATION REJECTED: ${entry.symbol} | $reason")
    }

    /**
     * V5.0: Update probation entry with new scanner confirmation.
     */
    fun updateProbationScanner(mint: String, scanner: String): Boolean {
        val entry = probation[mint] ?: return false
        entry.additionalScanners.add(scanner)

        // Auto-promote if multi-scanner confirmed
        if (entry.additionalScanners.size >= 1 && entry.isSingleSource) {
            promoteFromProbation(mint, "SCANNER_CONFIRM: ${entry.additionalScanners.joinToString(",")}")
            return true
        }
        return false
    }

    /**
     * V5.0: Update probation entry with RC score.
     */
    fun updateProbationRC(mint: String, rcScore: Int): Boolean {
        val entry = probation[mint] ?: return false
        entry.rcScore = rcScore

        // V5.2: Good RC score can promote (RC >= 2 is great)
        if (rcScore >= 2) {
            promoteFromProbation(mint, "GOOD_RC:$rcScore")
            return true
        }
        // V5.2: Bad RC score = instant reject (RC <= 1 is dangerous)
        if (rcScore <= 1) {
            rejectFromProbation(mint, "BAD_RC:$rcScore")
            return true
        }
        return false
    }

    /**
     * V5.0: Update probation entry with price.
     */
    fun updateProbationPrice(mint: String, price: Double) {
        val entry = probation[mint] ?: return
        entry.currentPrice = price
    }

    /**
     * V5.0: Process probation tier - check for promotions/rejections.
     * Call this periodically from bot loop.
     */
    fun processProbation(): List<ProbationResult> {
        val results = mutableListOf<ProbationResult>()
        val now = System.currentTimeMillis()

        for ((mint, entry) in probation) {
            val elapsed = now - entry.addedAt

            // V5.9.1328 — ROOT FIX E: TIMEOUT must not auto-reject.
            // Under Train-First doctrine, cold mints with no oracle price
            // action (vol1h=$0 for new pump-portal pools) hit
            // PROBATION_MAX_TIME_MS=5min without firing any promotion
            // signal (price-up needs >5%, multi-scanner needs a 2nd hit,
            // RC needs a rugcheck >= 2 callback). Auto-rejecting them
            // here removed their addedAt timestamp from the lifecycle
            // and they re-arrive seconds later from PumpPortal as
            // "duplicates", recursing forever in the "PROBATION REJECTED
            // ... TIMEOUT" log spam. Operator: V3/FDG is final authority;
            // tokens that timed out should be promoted to the watchlist
            // so V3/FDG can re-evaluate with full pipeline data instead
            // of being silently culled by an opaque 5-min cutoff.
            if (elapsed >= PROBATION_MAX_TIME_MS) {
                val noPairCold = entry.source.contains("NO_PAIR_NO_FALLBACK", ignoreCase = true) &&
                    entry.priceAtAdd <= 0.0 && entry.currentPrice <= 0.0 && entry.additionalScanners.isEmpty() && entry.rcScore < 2
                if (noPairCold) {
                    if (entry.promotionReason != "NO_PAIR_TIMEOUT_HELD") {
                        entry.promotionReason = "NO_PAIR_TIMEOUT_HELD"
                        try { ForensicLogger.lifecycle("PROBATION_TIMEOUT_HELD_NO_PAIR", "mint=${mint.take(10)} symbol=${entry.symbol} src=${entry.source} ageMs=$elapsed no_price_no_pair=true") } catch (_: Throwable) {}
                    }
                    continue
                }
                promoteFromProbation(mint, "TIMEOUT_AUTO_PROMOTE")
                results.add(ProbationResult(mint, entry.symbol, "PROMOTED", "TIMEOUT_AUTO_PROMOTE"))
                continue
            }

            // Check minimum time
            if (elapsed < PROBATION_MIN_TIME_MS) continue

            // Check price action - promote if price held or increased
            if (entry.priceAtAdd > 0 && entry.currentPrice > 0) {
                val priceChange = (entry.currentPrice - entry.priceAtAdd) / entry.priceAtAdd * 100
                if (priceChange >= 5.0) {
                    // Price up 5%+ = promote
                    promoteFromProbation(mint, "PRICE_UP:${priceChange.toInt()}%")
                    results.add(ProbationResult(mint, entry.symbol, "PROMOTED", "PRICE_UP"))
                    continue
                }
                if (priceChange <= -20.0) {
                    // Price down 20%+ = reject
                    rejectFromProbation(mint, "PRICE_DUMP:${priceChange.toInt()}%")
                    results.add(ProbationResult(mint, entry.symbol, "REJECTED", "PRICE_DUMP"))
                    continue
                }
            }

            // Check if multi-scanner confirmed
            if (entry.additionalScanners.isNotEmpty()) {
                promoteFromProbation(mint, "MULTI_CONFIRM")
                results.add(ProbationResult(mint, entry.symbol, "PROMOTED", "MULTI_CONFIRM"))
                continue
            }

            // V5.2: Check if RC confirmed (RC >= 2 is great for promotion)
            if (entry.rcScore >= 2) {
                promoteFromProbation(mint, "RC_OK:${entry.rcScore}")
                results.add(ProbationResult(mint, entry.symbol, "PROMOTED", "RC_OK"))
                continue
            }
        }

        return results
    }

    data class ProbationResult(
        val mint: String,
        val symbol: String,
        val action: String,  // "PROMOTED" or "REJECTED"
        val reason: String,
    )

    /**
     * V5.0: Get probation entries.
     */
    fun getProbationEntries(): List<ProbationEntry> = probation.values.toList()

    /**
     * V5.0: Get probation entry for a specific token.
     */
    fun getProbationEntry(mint: String): ProbationEntry? = probation[mint]

    /**
     * V5.0: Check if token is in probation.
     */
    fun isInProbation(mint: String): Boolean = probation.containsKey(mint)

    /**
     * V5.0: Get probation size.
     */
    fun probationSize(): Int = probation.size

    /**
     * V5.0: Get probation stats.
     */
    fun getProbationStats(): String {
        return "PROBATION: ${probation.size}/$MAX_PROBATION_SIZE | promoted=${probationPromotions.get()} | rejected=${probationRejections.get()}"
    }

    /**
     * V5.0.4112 — CANONICAL HELD-TOKEN CHECK.
     * Operator mandate: "the watchlist isnt meant to drop held tokens ever.
     * maybe consider a separate held tokens lane that only flushes from the
     * watchlist if its sold not pruned via the time ticker".
     *
     * Single source of truth answering "do we currently hold this mint?".
     * Three orthogonal signals — any of them = HELD:
     *   1. activePositions registered with this registry (canonical fast path).
     *   2. BotService.status.tokens[mint].position.isOpen (in-memory live state).
     *   3. HostWalletTokenTracker.hasOpenPosition(mint) (wallet truth — catches
     *      orphaned recoveries before they round-trip back to the journal).
     *
     * Every watchlist eviction path must consult this. Failing to do so was the
     * upstream cause of the WALLET_RECOVERED phantom-PnL cluster: a held mint
     * got pruned by SAFETY/RUG flag → price polling died → WalletReconciler
     * later "recovered" it as orphan with costSol=0 → phantom +99% wins.
     */
    fun isMintHeldAnywhere(mint: String): Boolean {
        if (mint.isBlank()) return false
        if (activePositions.containsKey(mint)) return true
        try {
            val st = com.lifecyclebot.engine.BotService.status
            synchronized(st.tokens) {
                val ts = st.tokens[mint]
                if (ts != null && ts.position.isOpen) return true
            }
        } catch (_: Throwable) {}
        try {
            if (com.lifecyclebot.engine.HostWalletTokenTracker.hasOpenPosition(mint)) return true
        } catch (_: Throwable) {}
        return false
    }

    /**
     * Remove a token from the watchlist.
     * V5.9.369 — never evict a mint that has an active position. Eviction
     * while a position is open orphans the token from price polling and
     * exit management, leaving a "ghost position" the bot can't close.
     * If the caller is force-closing (e.g. after the position closes and
     * the trader explicitly drops the watchlist entry), it should call
     * removeFromWatchlistForced(mint, reason).
     */
    fun removeFromWatchlist(mint: String, reason: String = "MANUAL"): Boolean {
        // V5.0.4112 — escalated guard: also block if held anywhere (in-memory
        // status.tokens / wallet tracker), not just activePositions. Held
        // positions getting silently evicted was orphaning price polling and
        // causing the WALLET_RECOVERED phantom-PnL cluster.
        if (isMintHeldAnywhere(mint)) {
            ErrorLogger.debug(TAG, "🛡️ removeFromWatchlist BLOCKED for $mint — token still held (reason was: $reason)")
            return false
        }
        val removed = watchlist.remove(mint)
        if (removed != null) {
            totalTokensRemoved.incrementAndGet()
            ErrorLogger.debug(TAG, "➖ Removed ${removed.symbol} | reason=$reason")
            return true
        }
        return false
    }

    /**
     * V5.9.369 — explicit forced-removal escape hatch. ONLY for trader
     * close paths that have already closed the position. Bypasses the
     * active-position guard. Do not use from rejection / cleanup paths.
     *
     * V5.0.4112 — even "forced" callers must NOT silently nuke a token we
     * still hold. The trader-close path closes the position FIRST, so this
     * guard is benign there. From cleanup paths (AntiChoke, sweepers, etc.)
     * it now correctly refuses to orphan a held mint.
     */
    fun removeFromWatchlistForced(mint: String, reason: String = "FORCED"): Boolean {
        if (isMintHeldAnywhere(mint)) {
            ErrorLogger.warn(TAG, "🛡️ removeFromWatchlistForced REFUSED for $mint — token still held (reason was: $reason)")
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FORCED_REMOVE_REFUSED_TOKEN_HELD") } catch (_: Throwable) {}
            return false
        }
        val removed = watchlist.remove(mint)
        if (removed != null) {
            totalTokensRemoved.incrementAndGet()
            ErrorLogger.debug(TAG, "➖ FORCED removed ${removed.symbol} | reason=$reason")
            return true
        }
        return false
    }

    /**
     * Register that a token was rejected (so it won't be re-added).
     */
    fun registerRejection(mint: String, symbol: String, reason: String, rejectedBy: String) {
        // V5.0.4112 — escalated guard: don't reject a mint we currently hold
        // through ANY lane (canonical isMintHeldAnywhere check).
        if (isMintHeldAnywhere(mint)) {
            ErrorLogger.debug(TAG, "🛡️ registerRejection BLOCKED for $symbol — token still held (would-be reason: $reason)")
            return
        }
        rejectedTokens[mint] = RejectionEntry(
            mint = mint,
            symbol = symbol,
            rejectedAt = System.currentTimeMillis(),
            reason = reason,
            rejectedBy = rejectedBy,
        )
        val r = reason.uppercase()
        val by = rejectedBy.uppercase()
        if (by.contains("SAFETY") || r.contains("CONFIRMED RUG") || r.contains("HONEYPOT") || r.contains("SCAM") || r.contains("BASE_OR_QUOTE") || r.contains("BLOCKED_SYMBOL")) {
            ScannerHardRejectStore.mark(mint, symbol, reason, rejectedBy)
            probation.remove(mint)
            // V5.0.4112 — DO NOT silently evict from watchlist if the mint
            // is currently held. The bot must keep polling price & exits;
            // dropping the watchlist entry orphans it and lets the
            // WalletReconciler later "recover" the position with costSol=0,
            // producing phantom +99% wins. Tag the entry instead — the
            // exit/SL logic will still see the safety verdict via
            // rejectedTokens, but the price-poll lane keeps the entry alive.
            if (!isMintHeldAnywhere(mint)) {
                watchlist.remove(mint)
            } else {
                ErrorLogger.warn(TAG,
                    "🛡️ Safety/rug rejection recorded for HELD mint $symbol — watchlist entry PRESERVED for exit polling | reason=$reason")
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SAFETY_REJECTION_DEFERRED_TOKEN_HELD") } catch (_: Throwable) {}
            }
        }

        // V5.9.624 — PROTECTED MEME INTAKE.
        // Rejection memory may block EXECUTION, but it must not amputate the
        // scanner/watchlist intake pool. Earlier behavior removed the token
        // here, so any caller of registerRejection() became an indirect
        // watchlist choke. Keep the candidate observable for rehydration,
        // telemetry, shadow learning, and fair scheduling.
        ErrorLogger.debug(TAG, "🛡️ Rejection recorded for $symbol but intake retained | reason=$reason by=$rejectedBy")
    }

    /**
     * Mark a token as processed in this cycle.
     */
    fun markProcessed(mint: String) {
        recentlyProcessed[mint] = System.currentTimeMillis()
        watchlist[mint]?.let {
            it.lastProcessedAt = System.currentTimeMillis()
            it.processCount++
        }
    }

    /**
     * V5.0: Get watchlist entry for a token (for UI display).
     * Returns null if token is not in watchlist.
     */
    fun getEntry(mint: String): WatchlistEntry? = watchlist[mint]

    /**
     * Check if we can process a token (not processed too recently).
     */
    fun canProcess(mint: String): Boolean {
        val lastProcessed = recentlyProcessed[mint] ?: return true
        return System.currentTimeMillis() - lastProcessed >= effectiveProcessCooldownMs()
    }

    /**
     * Get current watchlist as a list of mint addresses.
     * This is the ONLY way to read the watchlist.
     */
    fun getLaneAffinity(mint: String): Set<String> = watchlist[mint]?.laneAffinity?.toSet() ?: emptySet()

    fun getToolAffinity(mint: String): Set<String> = watchlist[mint]?.toolAffinity?.toSet() ?: emptySet()

    fun mergeAffinity(mint: String, lanes: Set<String> = emptySet(), tools: Set<String> = emptySet()) {
        val e = watchlist[mint] ?: return
        e.laneAffinity.addAll(lanes.map { it.uppercase() })
        e.toolAffinity.addAll(tools.map { it.uppercase() })
    }

    fun getWatchlist(): List<String> {
        return watchlist.keys.toList()
    }

    /**
     * Get watchlist entries with metadata.
     */
    fun getWatchlistEntries(): List<WatchlistEntry> {
        return watchlist.values.toList()
    }

    /**
     * Get watchlist size.
     */
    fun size(): Int = watchlist.size

    /**
     * Check if a token is in the watchlist.
     */
    fun isWatching(mint: String): Boolean = watchlist.containsKey(mint)

    /**
     * Update symbol for a token (when we fetch actual data).
     */
    fun updateSymbol(mint: String, symbol: String) {
        watchlist[mint]?.let { entry ->
            watchlist[mint] = entry.copy(symbol = symbol)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION TRACKING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Register an open position.
     */
    fun registerPosition(mint: String, symbol: String, layer: String, sizeSol: Double) {
        activePositions[mint] = PositionEntry(
            mint = mint,
            symbol = symbol,
            layer = layer,
            openedAt = System.currentTimeMillis(),
            sizeSol = sizeSol,
        )
        ErrorLogger.debug(TAG, "📊 Position opened: $symbol | layer=$layer | size=$sizeSol SOL")
    }

    /**
     * Close a position.
     */
    fun closePosition(mint: String): PositionEntry? {
        val pos = activePositions.remove(mint)
        if (pos != null) {
            ErrorLogger.debug(TAG, "📊 Position closed: ${pos.symbol} | layer=${pos.layer}")
        }
        return pos
    }

    /**
     * Check if a position is open for a token.
     */
    fun hasOpenPosition(mint: String): Boolean = activePositions.containsKey(mint)

    /**
     * Get all open positions.
     */
    fun getOpenPositions(): List<PositionEntry> {
        try { pruneClosedPositions() } catch (_: Throwable) {}
        return activePositions.values.toList()
    }

    /**
     * Get total exposure in SOL.
     */
    fun getTotalExposure(): Double = activePositions.values.sumOf { it.sizeSol }

    /**
     * V5.9.1506 — SELF-HEALING GHOST PRUNE (root fix for "open count out by 30
     * and increasing"). PAPER-mode open count reads from activePositions.size,
     * but only ONE exit path (paperSell) + AntiChoke ever call closePosition().
     * Any position that exited via a different path (catastrophe stop, forced
     * close, lane reassignment, scanner eviction, EXIT_MANAGED) leaked its
     * registry row forever → the count climbed +1 per trade and never fell.
     *
     * This prune drops any active row that is ALREADY stamped CLOSED in the
     * authoritative PositionCloseLedger, plus any row impossibly stale for a
     * meme scalp (open > STALE_POSITION_MS with no close) — those are leaked
     * ghosts by definition. Called from getPositionCount/getOpenPositions so
     * the count is ALWAYS wallet/ledger-truth, no external scheduler needed.
     */
    fun pruneClosedPositions(): Int {
        if (activePositions.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var pruned = 0
        for ((mint, p) in activePositions.entries.toList()) {
            val ledgerClosed = try { PositionCloseLedger.isClosed(mint) } catch (_: Throwable) { false }
            val stale = p.openedAt > 0L && (now - p.openedAt) > STALE_POSITION_MS
            if (ledgerClosed || stale) {
                if (activePositions.remove(mint, p)) {
                    pruned++
                    val why = if (ledgerClosed) "LEDGER_CLOSED" else "STALE_${(now - p.openedAt)/60000}min"
                    try {
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "REGISTRY_GHOST_PRUNED",
                            "mint=${mint.take(10)} symbol=${p.symbol} layer=${p.layer} reason=$why"
                        )
                    } catch (_: Throwable) {}
                    // free re-entry the same way a real close does, so a pruned
                    // ghost can't immediately re-open and re-leak.
                    try {
                        val fam = p.symbol.uppercase().trim().filter { it.isLetterOrDigit() }.take(8)
                        com.lifecyclebot.engine.ReEntryLockout.onClose(mint, fam, "GHOST_REAP_ZERO_BALANCE", 0.0)
                    } catch (_: Throwable) {}
                }
            }
        }
        if (pruned > 0) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("REGISTRY_GHOST_PRUNED") } catch (_: Throwable) {}
        }
        return pruned
    }

    /**
     * Get position count. Self-heals leaked ghosts first so the PAPER-mode
     * "Open" tile equals ledger-truth instead of drifting upward.
     */
    fun getPositionCount(): Int {
        try { pruneClosedPositions() } catch (_: Throwable) {}
        return activePositions.size
    }

    // ═══════════════════════════════════════════════════════════════════════════
    /** V5.9.612 AntiChoke: clear a stale registry-only position when wallet / sub-trader truth proves unheld. */
    fun clearPositionIfUnheld(mint: String, reason: String = "ANTI_CHOKE_UNHELD"): Boolean {
        val pos = activePositions.remove(mint) ?: return false
        ErrorLogger.warn(TAG, "👻 Cleared registry ghost position: ${pos.symbol} | layer=${pos.layer} | reason=$reason")
        return true
    }

    /**
     * V5.9.623 protected intake pool.
     * Scanner/watchlist is a 500-token discovery bench, not an AntiChoke garbage
     * collection target. Keep this no-op so legacy callers cannot silently drain
     * the pool again. Explicit user/manual removals still use removeFromWatchlist().
     */
    fun pruneDormant(maxAgeMs: Long, maxRemove: Int, reason: String = "ANTI_CHOKE_DORMANT"): Int {
        ErrorLogger.debug(TAG, "🛡️ pruneDormant ignored — protected scanner/watchlist intake pool | reason=$reason")
        return 0
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAINTENANCE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Prune oldest non-position entry from watchlist.
     * Returns true if an entry was removed.
     */
    private fun pruneOldestEntry(): Boolean {
        val oldest = watchlist.values
            .filter { !activePositions.containsKey(it.mint) }
            .minByOrNull { it.addedAt }

        if (oldest != null) {
            watchlist.remove(oldest.mint)
            totalTokensRemoved.incrementAndGet()
            ErrorLogger.debug(TAG, "🧹 Pruned ${oldest.symbol} (oldest, no position)")
            return true
        }
        return false
    }

    /**
     * Clean up expired rejections and cooldowns.
     * Call this periodically (e.g., every loop).
     */
    fun cleanup() {
        val now = System.currentTimeMillis()

        // V5.9.162: cleanup uses effective (bootstrap-aware) cooldowns so
        // rejection/processed entries flush fast during bootstrap and keep
        // their 20s persistence after maturity.
        val rejCd = effectiveRejectionCooldownMs()
        val dupCd = effectiveDuplicateCooldownMs()

        // Clean old rejections
        rejectedTokens.entries.removeIf { now - it.value.rejectedAt > rejCd }

        // Clean old processed entries
        recentlyProcessed.entries.removeIf { now - it.value > dupCd }
    }

    /**
     * Sync watchlist back to ConfigStore.
     * Call this periodically to persist state.
     */
    fun syncToConfig(context: android.content.Context) {
        try {
            val cfg = com.lifecyclebot.data.ConfigStore.load(context)
            val currentList = getWatchlist()

            // Only save if different
            if (cfg.watchlist.toSet() != currentList.toSet()) {
                com.lifecyclebot.data.ConfigStore.saveWatchlistOnly(context, currentList)
                ErrorLogger.debug(TAG, "💾 Synced ${currentList.size} tokens to ConfigStore")
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to sync to ConfigStore: ${e.message}", e)
        }
    }

    /**
     * Get stats for logging/debugging.
     */
    fun getStats(): String {
        return "GlobalTradeReg: watching=${watchlist.size} positions=${activePositions.size} " +
            "added=${totalTokensAdded.get()} removed=${totalTokensRemoved.get()} " +
            "dupes_blocked=${duplicatesBlocked.get()}"
    }

    /**
     * Reset all state (for testing or full restart).
     * V4.0: Also resets initialized flag to allow reinit on next session.
     * V5.0: Also clears probation tier.
     */
    fun reset() {
        watchlist.clear()
        recentlyProcessed.clear()
        rejectedTokens.clear()
        activePositions.clear()
        probation.clear()
        totalTokensAdded.set(0)
        totalTokensRemoved.set(0)
        duplicatesBlocked.set(0)
        probationPromotions.set(0)
        probationRejections.set(0)
        initialized = false  // Allow reinit
        ErrorLogger.info(TAG, "🔄 Registry reset - can reinit on next session")
    }
}
