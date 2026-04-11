package com.lifecyclebot.v4.meta

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * ===============================================================================
 * CROSS-ASSET LEAD-LAG AI — V4 Meta-Intelligence
 * ===============================================================================
 *
 * Purpose: Detect when one market is leading another. This is the real
 * multi-market edge.
 *
 * Examples:
 *   - BTC impulse leads SOL beta expansion
 *   - NVDA/QQQ tokenized stock momentum leads AI-related onchain sectors
 *   - SOL weakness warns of meme liquidity collapse 5-20 minutes early
 *   - Large-cap strength rotates into mid-cap/meme sectors after delay
 *
 * Instead of waiting for a coin to look perfect, this layer says:
 * "The leader already moved. Sector beta usually follows in 4-12 minutes."
 *
 * ===============================================================================
 */
object CrossAssetLeadLagAI {

    private const val TAG = "LeadLagAI"
    private const val PRICE_HISTORY_SIZE = 120      // ~2 hours at 1-min intervals
    private const val MIN_CORRELATION = 0.5
    private const val MIN_LAG_SAMPLES = 10

    // Price histories per symbol (1-minute returns)
    private val returnHistory = ConcurrentHashMap<String, MutableList<TimedReturn>>()

    // Known lead-lag pairs (learned + hardcoded)
    private val knownPairs = ConcurrentHashMap<String, LeadLagPair>()

    // Active links (currently firing)
    private val activeLinks = ConcurrentHashMap<String, LeadLagLink>()

    data class TimedReturn(val returnPct: Double, val timestamp: Long)

    data class LeadLagPair(
        val leader: String,
        val lagger: String,
        val historicalCorrelation: Double,
        val typicalDelaySec: Int,
        val direction: String,       // "SAME" or "INVERSE"
        val confidence: Double,
        val sampleCount: Int
    )

    // ═══════════════════════════════════════════════════════════════════════
    // INITIALIZATION — Seed known lead-lag relationships
    // ═══════════════════════════════════════════════════════════════════════

    fun init() {
        // Hardcoded well-known lead-lag relationships
        val seeds = listOf(
            LeadLagPair("BTC", "SOL", 0.75, 300, "SAME", 0.7, 0),
            LeadLagPair("BTC", "ETH", 0.85, 120, "SAME", 0.8, 0),
            LeadLagPair("SOL", "MEME_SECTOR", 0.65, 600, "SAME", 0.6, 0),
            LeadLagPair("BTC", "MEME_SECTOR", 0.55, 900, "SAME", 0.5, 0),
            LeadLagPair("QQQ", "TECH_STOCKS", 0.80, 180, "SAME", 0.7, 0),
            LeadLagPair("NVDA", "AI_SECTOR", 0.70, 360, "SAME", 0.6, 0),
            LeadLagPair("SPY", "BROAD_MARKET", 0.85, 120, "SAME", 0.8, 0),
            LeadLagPair("ETH", "DEFI_SECTOR", 0.60, 480, "SAME", 0.5, 0),
            LeadLagPair("SOL", "SOL_ECOSYSTEM", 0.70, 300, "SAME", 0.65, 0),
            LeadLagPair("GLD", "RISK_OFF", 0.50, 600, "INVERSE", 0.5, 0),
        )
        seeds.forEach { pair ->
            knownPairs["${pair.leader}->${pair.lagger}"] = pair
        }
        ErrorLogger.info(TAG, "CrossAssetLeadLagAI initialized with ${seeds.size} known pairs")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECORD PRICE — Feed price data for correlation analysis
    // ═══════════════════════════════════════════════════════════════════════

    fun recordReturn(symbol: String, returnPct: Double) {
        val history = returnHistory.getOrPut(symbol) { mutableListOf() }
        synchronized(history) {
            history.add(TimedReturn(returnPct, System.currentTimeMillis()))
            if (history.size > PRICE_HISTORY_SIZE) history.removeAt(0)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SCAN — Check all known pairs for active lead signals
    // ═══════════════════════════════════════════════════════════════════════

    fun scan(): List<LeadLagLink> {
        val newLinks = mutableListOf<LeadLagLink>()

        knownPairs.values.forEach { pair ->
            val leaderReturns = getRecentReturns(pair.leader)
            if (leaderReturns.isEmpty()) return@forEach

            // Check if leader has made a significant move recently
            val recentLeaderMove = leaderReturns.takeLast(5).sumOf { it.returnPct }

            if (abs(recentLeaderMove) > 0.5) { // Leader moved > 0.5% in last 5 periods
                val link = LeadLagLink(
                    leader = pair.leader,
                    lagger = pair.lagger,
                    correlation = pair.historicalCorrelation,
                    expectedDelaySec = pair.typicalDelaySec,
                    rotationProbability = calculateRotationProbability(pair, recentLeaderMove),
                    direction = pair.direction
                )
                val key = "${pair.leader}->${pair.lagger}"
                activeLinks[key] = link
                newLinks.add(link)

                // Publish to CrossTalk
                CrossTalkFusionEngine.publish(AATESignal(
                    source = TAG,
                    market = pair.lagger,
                    confidence = link.rotationProbability,
                    direction = if (pair.direction == "SAME") {
                        if (recentLeaderMove > 0) "LONG" else "SHORT"
                    } else {
                        if (recentLeaderMove > 0) "SHORT" else "LONG"
                    },
                    horizonSec = pair.typicalDelaySec,
                    rotationTarget = pair.lagger,
                    riskFlags = if (link.rotationProbability > 0.7) listOf("STRONG_LEAD_LAG") else emptyList()
                ))
            }
        }

        // Clean expired links (older than 2x expected delay)
        val now = System.currentTimeMillis()
        activeLinks.entries.removeAll { entry ->
            val pair = knownPairs[entry.key]
            pair != null && now - entry.value.let { System.currentTimeMillis() } > pair.typicalDelaySec * 2000L
        }

        return newLinks
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LEARN — Update pair statistics from observed outcomes
    // ═══════════════════════════════════════════════════════════════════════

    fun learnFromOutcome(leader: String, lagger: String, leaderMovePct: Double, laggerMovePct: Double, delaySec: Int) {
        val key = "$leader->$lagger"
        val existing = knownPairs[key] ?: return

        val wasCorrectDirection = if (existing.direction == "SAME") {
            (leaderMovePct > 0 && laggerMovePct > 0) || (leaderMovePct < 0 && laggerMovePct < 0)
        } else {
            (leaderMovePct > 0 && laggerMovePct < 0) || (leaderMovePct < 0 && laggerMovePct > 0)
        }

        val newSamples = existing.sampleCount + 1
        val newCorrelation = if (wasCorrectDirection) {
            existing.historicalCorrelation * 0.95 + 0.05 * 1.0
        } else {
            existing.historicalCorrelation * 0.95 + 0.05 * 0.0
        }

        // Exponential moving average for delay
        val newDelay = ((existing.typicalDelaySec * 0.9) + (delaySec * 0.1)).toInt()

        knownPairs[key] = existing.copy(
            historicalCorrelation = newCorrelation.coerceIn(0.0, 1.0),
            typicalDelaySec = newDelay,
            confidence = newCorrelation,
            sampleCount = newSamples
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════════

    fun getActiveLinks(): List<LeadLagLink> = activeLinks.values.toList()

    fun getLeadSignalFor(lagger: String): LeadLagLink? =
        activeLinks.values.firstOrNull { it.lagger == lagger }

    fun getRotationProbability(lagger: String): Double =
        getLeadSignalFor(lagger)?.rotationProbability ?: 0.0

    fun getLeadLagMultiplier(symbol: String): Double {
        val link = getLeadSignalFor(symbol)
        return if (link != null) 1.0 + link.rotationProbability * 0.3 else 1.0
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun getRecentReturns(symbol: String): List<TimedReturn> {
        val history = returnHistory[symbol] ?: return emptyList()
        return synchronized(history) { history.toList() }
    }

    private fun calculateRotationProbability(pair: LeadLagPair, leaderMove: Double): Double {
        val moveStrength = abs(leaderMove) / 2.0 // Normalize: 2% move = 1.0
        return (pair.historicalCorrelation * moveStrength * pair.confidence)
            .coerceIn(0.0, 0.95)
    }

    fun clear() {
        returnHistory.clear()
        activeLinks.clear()
    }
}
