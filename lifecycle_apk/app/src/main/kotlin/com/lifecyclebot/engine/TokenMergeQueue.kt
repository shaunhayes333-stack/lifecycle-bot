package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TOKEN MERGE QUEUE - V4.2 FLOW + QUALITY PATCH
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Purpose:
 * - Merge duplicate discoveries from multiple scanners
 * - Prevent duplicate watchlist adds / duplicate AI evaluation
 * - Preserve multi-source confirmation advantage
 * - Let strong single-source setups still get through
 *
 * Design:
 * - Single source starts at its base scanner confidence
 * - Good single-source setups can receive a controlled fast-track bonus
 * - Multi-source confirmation remains the strongest signal
 * - Confidence is always capped for safety
 */
object TokenMergeQueue {

    private const val TAG = "TokenMergeQ"

    // Discoveries inside this window are considered the same opportunity
    private const val MERGE_WINDOW_MS = 5_000L

    // How often queue processing is allowed to run
    private const val PROCESS_INTERVAL_MS = 2_000L

    // Scanner confidence rankings
    private val scannerConfidence = mapOf(
        "DEX_BOOSTED" to 60,
        "V3_PREMIUM" to 55,
        "WHALE_COPY" to 50,
        "DEX_TRENDING" to 45,
        "MOONSHOT" to 45,
        "RAYDIUM_NEW_POOL" to 40,
        "RAYDIUM_NEW" to 40,
        "V3_SCANNER" to 40,
        "PUMP_FUN_GRADUATE" to 35,
        "SOCIAL_TRENDING" to 35,
        "USER_ADDED" to 30,
        "UNKNOWN" to 20,
    )

    // Multi-source confirmation bonuses
    private const val MULTI_SOURCE_BOOST = 25

    // Strong single-source fast-track
    private const val HIGH_QUALITY_SINGLE_LIQUIDITY = 8_000.0
    private const val HIGH_QUALITY_SINGLE_BONUS = 15

    // Optional tiny nudge so single-source tokens are not artificially too weak
    private const val SINGLE_SOURCE_BONUS = 5

    private val pendingDiscoveries = ConcurrentHashMap<String, MergeEntry>()

    @Volatile
    private var lastProcessTime = 0L

    private val totalDiscoveries = AtomicInteger(0)
    private val totalMerges = AtomicInteger(0)
    private val totalEmitted = AtomicInteger(0)

    data class MergeEntry(
        val mint: String,
        var symbol: String,
        var marketCapUsd: Double,
        var liquidityUsd: Double,
        val firstSeenAt: Long,
        var lastSeenAt: Long,
        val scanners: MutableSet<String>,
        var bestScanner: String,
        var confidence: Int,
        var discoveryCount: Int,
    )

    data class MergedToken(
        val mint: String,
        val symbol: String,
        val marketCapUsd: Double,
        val liquidityUsd: Double,
        val primaryScanner: String,
        val allScanners: Set<String>,
        val confidence: Int,
        val multiScannerBoost: Boolean,
    )

    /**
     * Enqueue a scanner discovery.
     */
    fun enqueue(
        mint: String,
        symbol: String,
        scanner: String,
        marketCapUsd: Double = 0.0,
        liquidityUsd: Double = 0.0,
    ) {
        val now = System.currentTimeMillis()
        totalDiscoveries.incrementAndGet()

        val existing = pendingDiscoveries[mint]

        if (existing != null) {
            // Merge into existing pending entry
            existing.scanners.add(scanner)
            existing.lastSeenAt = now
            existing.discoveryCount++

            val incomingScannerConf = scannerConfidence[scanner] ?: (scannerConfidence["UNKNOWN"] ?: 20)
            val currentBestConf = scannerConfidence[existing.bestScanner] ?: (scannerConfidence["UNKNOWN"] ?: 20)

            if (incomingScannerConf > currentBestConf) {
                existing.bestScanner = scanner
            }

            if (marketCapUsd > existing.marketCapUsd) {
                existing.marketCapUsd = marketCapUsd
            }

            if (liquidityUsd > existing.liquidityUsd) {
                existing.liquidityUsd = liquidityUsd
            }

            if (symbol.length > existing.symbol.length) {
                existing.symbol = symbol
            }

            existing.confidence = calculateMergedConfidence(
                scanners = existing.scanners,
                bestScanner = existing.bestScanner,
                liquidityUsd = existing.liquidityUsd
            )

            totalMerges.incrementAndGet()
            ErrorLogger.debug(
                TAG,
                "🔀 MERGED: ${existing.symbol} | scanners=${existing.scanners.joinToString(",")} | conf=${existing.confidence}"
            )
        } else {
            val initialConfidence = calculateMergedConfidence(
                scanners = setOf(scanner),
                bestScanner = scanner,
                liquidityUsd = liquidityUsd
            )

            pendingDiscoveries[mint] = MergeEntry(
                mint = mint,
                symbol = symbol,
                marketCapUsd = marketCapUsd,
                liquidityUsd = liquidityUsd,
                firstSeenAt = now,
                lastSeenAt = now,
                scanners = mutableSetOf(scanner),
                bestScanner = scanner,
                confidence = initialConfidence,
                discoveryCount = 1,
            )

            ErrorLogger.debug(
                TAG,
                "➕ QUEUED: $symbol | scanner=$scanner | conf=$initialConfidence"
            )
        }
    }

    /**
     * Emit entries whose merge window has expired.
     */
    fun processQueue(): List<MergedToken> {
        val now = System.currentTimeMillis()

        if (now - lastProcessTime < PROCESS_INTERVAL_MS) {
            return emptyList()
        }
        lastProcessTime = now

        val readyToEmit = mutableListOf<MergedToken>()
        val toRemove = mutableListOf<String>()

        for ((mint, entry) in pendingDiscoveries) {
            val elapsed = now - entry.firstSeenAt

            if (elapsed >= MERGE_WINDOW_MS) {
                val merged = MergedToken(
                    mint = entry.mint,
                    symbol = entry.symbol,
                    marketCapUsd = entry.marketCapUsd,
                    liquidityUsd = entry.liquidityUsd,
                    primaryScanner = entry.bestScanner,
                    allScanners = entry.scanners.toSet(),
                    confidence = entry.confidence,
                    multiScannerBoost = entry.scanners.size > 1,
                )

                readyToEmit.add(merged)
                toRemove.add(mint)
                totalEmitted.incrementAndGet()

                val boostLabel = if (merged.multiScannerBoost) " [MULTI-SCANNER BOOST]" else ""
                ErrorLogger.debug(
                    TAG,
                    "📤 EMIT: ${entry.symbol} | scanners=${entry.scanners.joinToString(",")} | conf=${entry.confidence}$boostLabel"
                )
            }
        }

        for (mint in toRemove) {
            pendingDiscoveries.remove(mint)
        }

        return readyToEmit
    }

    /**
     * Confidence model:
     * - Single source = base scanner confidence + small bonus
     * - High-quality single source = extra fast-track
     * - Multi source = strong confirmation boost
     */
    private fun calculateMergedConfidence(
        scanners: Set<String>,
        bestScanner: String,
        liquidityUsd: Double,
    ): Int {
        if (scanners.isEmpty()) {
            return scannerConfidence["UNKNOWN"] ?: 20
        }

        val baseConfidence = scanners.maxOfOrNull {
            scannerConfidence[it] ?: (scannerConfidence["UNKNOWN"] ?: 20)
        } ?: (scannerConfidence["UNKNOWN"] ?: 20)

        val singleSourceBonus = if (scanners.size == 1) SINGLE_SOURCE_BONUS else 0

        val multiSourceBonus = when (scanners.size) {
            1 -> 0
            2 -> MULTI_SOURCE_BOOST
            3 -> MULTI_SOURCE_BOOST + 10
            else -> MULTI_SOURCE_BOOST + 15
        }

        val fastTrackBonus = if (
            scanners.size == 1 &&
            bestScanner in setOf("DEX_BOOSTED", "DEX_TRENDING", "V3_PREMIUM", "WHALE_COPY") &&
            liquidityUsd >= HIGH_QUALITY_SINGLE_LIQUIDITY
        ) {
            HIGH_QUALITY_SINGLE_BONUS
        } else {
            0
        }

        return (baseConfidence + singleSourceBonus + multiSourceBonus + fastTrackBonus)
            .coerceIn(20, 95)
    }

    fun isPending(mint: String): Boolean = pendingDiscoveries.containsKey(mint)

    fun getPendingCount(): Int = pendingDiscoveries.size

    fun getStats(): String {
        return "MergeQ: pending=${pendingDiscoveries.size} " +
            "discoveries=${totalDiscoveries.get()} " +
            "merges=${totalMerges.get()} " +
            "emitted=${totalEmitted.get()}"
    }

    /**
     * Force-emit everything still pending.
     */
    fun flushAll(): List<MergedToken> {
        val all = pendingDiscoveries.values.map { entry ->
            MergedToken(
                mint = entry.mint,
                symbol = entry.symbol,
                marketCapUsd = entry.marketCapUsd,
                liquidityUsd = entry.liquidityUsd,
                primaryScanner = entry.bestScanner,
                allScanners = entry.scanners.toSet(),
                confidence = entry.confidence,
                multiScannerBoost = entry.scanners.size > 1,
            )
        }

        pendingDiscoveries.clear()
        totalEmitted.addAndGet(all.size)
        return all
    }

    fun reset() {
        pendingDiscoveries.clear()
        totalDiscoveries.set(0)
        totalMerges.set(0)
        totalEmitted.set(0)
        lastProcessTime = 0L
    }
}