package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TOKEN MERGE QUEUE - V4.1 FAST MERGE / NO STARVATION
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * PURPOSE:
 * - Merge duplicate discoveries from multiple scanners
 * - Prevent duplicate watchlist inserts / duplicate execution paths
 * - Preserve multi-scanner confirmation as a confidence boost
 * - Stop starving single-source discoveries
 *
 * KEY CHANGES:
 * - Faster merge window
 * - Faster processing interval
 * - Higher baseline for legit single-source scanners
 * - Single-source entries now get a modest confidence bonus
 * - Safe refresh of stale pending entries
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object TokenMergeQueue {

    private const val TAG = "TokenMergeQ"

    // Faster batching so scanner pipeline keeps moving
    private const val MERGE_WINDOW_MS = 1_500L
    private const val PROCESS_INTERVAL_MS = 500L

    // Safety valve: don't let ancient pending entries hang around forever
    private const val STALE_ENTRY_MS = 10_000L

    // Base scanner confidence
    // These are now balanced to avoid starving good single-source discoveries
    private val scannerConfidence = mapOf(
        "DEX_BOOSTED" to 60,
        "V3_PREMIUM" to 55,
        "PUMP_FUN_GRADUATE" to 45,
        "WHALE_COPY" to 50,
        "RAYDIUM_NEW_POOL" to 50,
        "RAYDIUM_NEW" to 50,
        "MOONSHOT" to 45,
        "V3_SCANNER" to 50,
        "SOCIAL_TRENDING" to 35,
        "DEX_TRENDING" to 45,
        "USER_ADDED" to 30,
        "UNKNOWN" to 20,
    )

    // Multi-source confirmation bonus
    private const val MULTI_SOURCE_BOOST = 20

    // Pending discoveries by mint
    private val pendingDiscoveries = ConcurrentHashMap<String, MergeEntry>()

    @Volatile
    private var lastProcessTime = 0L

    private val totalDiscoveries = AtomicInteger(0)
    private val totalMerges = AtomicInteger(0)
    private val totalEmitted = AtomicInteger(0)
    private val totalStaleFlushes = AtomicInteger(0)

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
     * Enqueue a token discovery from a scanner.
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
            existing.scanners.add(scanner)
            existing.lastSeenAt = now
            existing.discoveryCount++

            val incomingScannerConf = scannerConfidence[scanner] ?: 20
            val existingScannerConf = scannerConfidence[existing.bestScanner] ?: 20
            if (incomingScannerConf > existingScannerConf) {
                existing.bestScanner = scanner
            }

            existing.confidence = calculateMergedConfidence(existing.scanners)

            if (marketCapUsd > existing.marketCapUsd) {
                existing.marketCapUsd = marketCapUsd
            }
            if (liquidityUsd > existing.liquidityUsd) {
                existing.liquidityUsd = liquidityUsd
            }
            if (symbol.length > existing.symbol.length) {
                existing.symbol = symbol
            }

            totalMerges.incrementAndGet()
            ErrorLogger.debug(
                TAG,
                "🔀 MERGED: $symbol | scanners=${existing.scanners.joinToString(",")} | conf=${existing.confidence}"
            )
        } else {
            val baseConfidence = calculateMergedConfidence(setOf(scanner))

            pendingDiscoveries[mint] = MergeEntry(
                mint = mint,
                symbol = symbol,
                marketCapUsd = marketCapUsd,
                liquidityUsd = liquidityUsd,
                firstSeenAt = now,
                lastSeenAt = now,
                scanners = mutableSetOf(scanner),
                bestScanner = scanner,
                confidence = baseConfidence,
                discoveryCount = 1,
            )

            ErrorLogger.debug(TAG, "➕ QUEUED: $symbol | scanner=$scanner | conf=$baseConfidence")
        }
    }

    /**
     * Process queue and return ready merged tokens.
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
            val ageSinceFirstSeen = now - entry.firstSeenAt
            val ageSinceLastSeen = now - entry.lastSeenAt

            val shouldEmit =
                ageSinceFirstSeen >= MERGE_WINDOW_MS || ageSinceLastSeen >= STALE_ENTRY_MS

            if (!shouldEmit) continue

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

            if (ageSinceLastSeen >= STALE_ENTRY_MS) {
                totalStaleFlushes.incrementAndGet()
            }

            val boostLabel = if (merged.multiScannerBoost) " [MULTI-SCANNER BOOST]" else ""
            ErrorLogger.debug(
                TAG,
                "📤 EMIT: ${entry.symbol} | scanners=${entry.scanners.joinToString(",")} | conf=${entry.confidence}$boostLabel"
            )
        }

        for (mint in toRemove) {
            pendingDiscoveries.remove(mint)
        }

        return readyToEmit
    }

    /**
     * Confidence model:
     * - Single source gets a modest positive bump so legit discoveries aren't starved
     * - Multi-source gets stronger confirmation boosts
     */
    private fun calculateMergedConfidence(scanners: Set<String>): Int {
        if (scanners.isEmpty()) return 20

        val baseConfidence = scanners.maxOfOrNull { scannerConfidence[it] ?: 20 } ?: 20

        val bonus = when (scanners.size) {
            1 -> 15
            2 -> 20
            3 -> 30
            else -> 35
        }

        return (baseConfidence + bonus).coerceAtMost(95)
    }

    fun isPending(mint: String): Boolean = pendingDiscoveries.containsKey(mint)

    fun getPendingCount(): Int = pendingDiscoveries.size

    fun getStats(): String {
        return "MergeQ: pending=${pendingDiscoveries.size} " +
            "discoveries=${totalDiscoveries.get()} " +
            "merges=${totalMerges.get()} " +
            "emitted=${totalEmitted.get()} " +
            "staleFlushes=${totalStaleFlushes.get()}"
    }

    /**
     * Flush everything immediately.
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
        totalStaleFlushes.set(0)
        lastProcessTime = 0L
    }
}