package com.lifecyclebot.v4.meta

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * ===============================================================================
 * PORTFOLIO HEAT AI — V4 Meta-Intelligence
 * ===============================================================================
 *
 * Purpose: Prevent correlated stupidity.
 * You may think you have 5 different positions, but really you have:
 *   3 SOL beta bets + 1 meme beta bet + 1 leveraged index beta bet
 * This should see that as one cluster and throttle new entries.
 *
 * ===============================================================================
 */
object PortfolioHeatAI {

    private const val TAG = "PortfolioHeatAI"

    private val currentReport = AtomicReference<PortfolioHeatReport?>(null)

    // Active position tracking
    private val activePositions = ConcurrentHashMap<String, PositionExposure>()

    data class PositionExposure(
        val id: String,
        val symbol: String,
        val market: String,
        val sector: String,
        val direction: String,      // "LONG" or "SHORT"
        val sizeSol: Double,
        val leverage: Double,
        val narrative: String?,     // Theme grouping
        val correlationGroup: String // Beta grouping: "SOL_BETA", "BTC_BETA", "TECH_BETA", etc.
    )

    // Correlation groups for clustering
    private val CORRELATION_GROUPS = mapOf(
        "SOL" to "SOL_BETA", "JUP" to "SOL_BETA", "BONK" to "SOL_BETA", "WIF" to "SOL_BETA", "PYTH" to "SOL_BETA",
        "BTC" to "BTC_BETA", "ETH" to "BTC_BETA",
        "BNB" to "EXCHANGE_BETA", "COIN" to "EXCHANGE_BETA",
        "NVDA" to "TECH_BETA", "AAPL" to "TECH_BETA", "MSFT" to "TECH_BETA", "GOOGL" to "TECH_BETA", "META" to "TECH_BETA", "AMZN" to "TECH_BETA", "TSLA" to "TECH_BETA",
        "SPY" to "INDEX_BETA", "QQQ" to "INDEX_BETA", "IWM" to "INDEX_BETA", "VTI" to "INDEX_BETA",
        "GLD" to "SAFE_HAVEN", "SLV" to "SAFE_HAVEN",
        "XOM" to "ENERGY", "CVX" to "ENERGY", "XLE" to "ENERGY",
        "JPM" to "FINANCE", "BAC" to "FINANCE", "GS" to "FINANCE",
        "JNJ" to "HEALTH", "UNH" to "HEALTH", "PFE" to "HEALTH", "ABBV" to "HEALTH", "LLY" to "HEALTH"
    )

    // ═══════════════════════════════════════════════════════════════════════
    // POSITION TRACKING
    // ═══════════════════════════════════════════════════════════════════════

    fun addPosition(id: String, symbol: String, market: String, sector: String, direction: String, sizeSol: Double, leverage: Double, narrative: String? = null) {
        activePositions[id] = PositionExposure(
            id = id, symbol = symbol, market = market, sector = sector,
            direction = direction, sizeSol = sizeSol, leverage = leverage,
            narrative = narrative,
            // V5.0.6853 §EVERY_MEME_LANDED_IN_ONE_BUCKET — the fallback was
            // "${market}_OTHER", and every spot entry registers with market="MEME"
            // and a symbol that is never in CORRELATION_GROUPS. So the entire meme
            // book — the overwhelming majority of positions — collapsed into the
            // single group "MEME_OTHER". clusterConcentration was therefore 1.0 with
            // one position open and 1.0 with forty, which is not a measurement.
            // The sector carried by the caller IS the lane/layer (QUALITY, MOONSHOT,
            // SHITCOIN, …), and positions sharing a lane genuinely are one bet, so
            // use it as the correlation group when the symbol is not a known beta.
            correlationGroup = CORRELATION_GROUPS[symbol]
                ?: "${market}_${sector.ifBlank { "UNSECTORED" }.uppercase()}"
        )
        recalculate()
    }

    fun removePosition(id: String) {
        activePositions.remove(id)
        recalculate()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECALCULATE — Core portfolio heat assessment
    // ═══════════════════════════════════════════════════════════════════════

    private fun recalculate() {
        val positions = activePositions.values.toList()
        if (positions.isEmpty()) {
            currentReport.set(PortfolioHeatReport(0.0, emptyMap(), 0.0, 0.0, false, 0.0, "NONE", 0))
            return
        }

        // 1. Sector crowding
        val sectorCrowding = mutableMapOf<String, Double>()
        val bySector = positions.groupBy { it.sector }
        val totalExposure = positions.sumOf { it.sizeSol * it.leverage }
        bySector.forEach { (sector, sectorPos) ->
            val sectorExposure = sectorPos.sumOf { it.sizeSol * it.leverage }
            sectorCrowding[sector] = if (totalExposure > 0) sectorExposure / totalExposure else 0.0
        }

        // V5.0.6853 §CONCENTRATION_WITHOUT_A_DIVERSIFICATION_BASELINE — every
        // concentration term below used to be a raw share, which is 1.0 by
        // construction whenever there is one position, or one group, or (for an
        // all-LONG spot book) one direction. Heat therefore read ~0.80 — "nearly
        // blocked", forcedDeRisk one notch away — for a *single* open meme. That is
        // why none of newEntryPenalty / forcedDeRisk / isNewEntryAllowed /
        // getSafetyMultiplier was ever wired to anything: wiring them would have
        // shut the bot down on trade one.
        // Normalise against the best diversification actually achievable with n
        // positions: the smallest possible largest-share is 1/n, so express every
        // share as excess over that floor. 1 position → 0 (nothing to diversify),
        // n positions spread over n groups → 0, n positions in one group → 1.
        val n = positions.size
        fun excess(share: Double): Double =
            if (n <= 1) 0.0 else ((share - 1.0 / n) / (1.0 - 1.0 / n)).coerceIn(0.0, 1.0)

        // 2. Correlation clustering — exposure-weighted, not head-count, so one
        //    oversized position in a group counts for what it actually risks.
        val byCorrelation = positions.groupBy { it.correlationGroup }
        val groupExposure = byCorrelation.mapValues { (_, g) -> g.sumOf { it.sizeSol * it.leverage } }
        val largestCluster = byCorrelation.maxByOrNull { groupExposure[it.key] ?: 0.0 }
        val largestShare = if (totalExposure > 0.0 && largestCluster != null) {
            (groupExposure[largestCluster.key] ?: 0.0) / totalExposure
        } else 0.0
        val clusterConcentration = excess(largestShare)

        // 3. Correlation stress — Herfindahl over correlation groups (sum of squared
        //    exposure shares), scaled by direction agreement. Direction only carries
        //    information when the book actually holds both sides; a spot-only book is
        //    100% LONG by definition and must not be charged for it.
        val hhi = if (totalExposure > 0.0) {
            groupExposure.values.sumOf { e -> val s = e / totalExposure; s * s }
        } else 0.0
        val longExp = positions.filter { it.direction == "LONG" }.sumOf { it.sizeSol * it.leverage }
        val shortExp = positions.filter { it.direction == "SHORT" }.sumOf { it.sizeSol * it.leverage }
        val directionAgreement = if (longExp > 0.0 && shortExp > 0.0) {
            (maxOf(longExp, shortExp) / totalExposure.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
        } else 1.0
        val correlationStress = (excess(hhi) * directionAgreement).coerceIn(0.0, 1.0)

        // 4. Leverage concentration
        val leveragedPositions = positions.filter { it.leverage > 1.0 }
        val leverageConcentration = if (positions.isNotEmpty()) {
            leveragedPositions.sumOf { it.sizeSol * it.leverage } / totalExposure.coerceAtLeast(0.01)
        } else 0.0

        // 5. Narrative stacking — same narrative = higher risk. Normalised the same
        //    way; note narrative is "${source}:${phase}" for memes, so this catches a
        //    book that is really one scanner firing repeatedly into one phase.
        val byNarrative = positions.filter { it.narrative != null }.groupBy { it.narrative }
        val narrativeConcentration = excess(
            byNarrative.values.maxOfOrNull { it.size }?.toDouble()?.div(n.coerceAtLeast(1)) ?: 0.0
        )

        // 6. Portfolio heat composite.
        //    V5.0.6853 — renormalise over the terms that actually carry information.
        //    The leverage term is structurally 0 for a spot-only book (every meme
        //    registers leverage=1.0, and the filter is `> 1.0`), so its 0.20 weight
        //    used to cap heat at 0.80 no matter how concentrated the book was. That
        //    put forcedDeRisk (>0.85) and isNewEntryAllowed (<0.9) permanently out of
        //    reach on the spot path — two more reasons those outputs were never wired.
        val hasLeverage = leveragedPositions.isNotEmpty()
        val weighted =
            clusterConcentration * 0.30 +
            correlationStress * 0.25 +
            (if (hasLeverage) leverageConcentration.coerceIn(0.0, 1.0) * 0.20 else 0.0) +
            narrativeConcentration * 0.15 +
            excess(sectorCrowding.values.maxOrNull() ?: 0.0) * 0.10
        val weightSum = if (hasLeverage) 1.00 else 0.80
        val portfolioHeat = (weighted / weightSum).coerceIn(0.0, 1.0)

        // 7. New entry penalty
        val newEntryPenalty = when {
            portfolioHeat > 0.8 -> 0.9  // Nearly blocked
            portfolioHeat > 0.6 -> 0.5  // Half penalty
            portfolioHeat > 0.4 -> 0.2  // Light penalty
            else -> 0.0
        }

        // 8. Forced de-risk
        val forcedDeRisk = portfolioHeat > 0.85

        val report = PortfolioHeatReport(
            portfolioHeat = portfolioHeat,
            sectorCrowding = sectorCrowding,
            correlationStress = correlationStress,
            newEntryPenalty = newEntryPenalty,
            forcedDeRisk = forcedDeRisk,
            leverageConcentration = leverageConcentration.coerceIn(0.0, 1.0),
            largestCluster = largestCluster?.key ?: "NONE",
            clusterSize = largestCluster?.value?.size ?: 0
        )

        currentReport.set(report)

        // V5.0.6853 §FORCED_DERISK_AND_ENTRY_BAN_HAD_NO_CONSUMER — shouldDeRisk()
        // and isNewEntryAllowed() had zero callers, so "the portfolio is one bet and
        // it is on fire" reached nothing that could act. Rather than give this one
        // module a unilateral veto — which would choke throughput the moment a lane
        // runs hot, against the standing unchoke doctrine — publish it as capital
        // evidence into the scoped consensus authority. That authority only hard-vetoes
        // when >=3 INDEPENDENT signal families agree (ExecutableOpenGate:2642), so heat
        // alone throttles via size + score floor, and only heat PLUS an advisor block
        // PLUS a losing-streak/outcome signal actually stops admission.
        try {
            val mode6853 = if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"
            // Read back through the public API so both outputs have real callers.
            val breach6853 = shouldDeRisk() || !isNewEntryAllowed()
            if (breach6853) {
                com.lifecyclebot.engine.truth.AdaptiveVetoConsensusAuthority6728.raise(
                    com.lifecyclebot.engine.truth.AdaptiveVetoConsensusAuthority6728.Signal.CAPITAL_CREED_BREACH,
                    mode = mode6853, lane = "", mint = "",
                    evidenceId = "PORTFOLIO_HEAT_6853:${largestCluster?.key ?: "NONE"}:${"%.2f".format(portfolioHeat)}",
                )
            } else {
                com.lifecyclebot.engine.truth.AdaptiveVetoConsensusAuthority6728.clear(
                    com.lifecyclebot.engine.truth.AdaptiveVetoConsensusAuthority6728.Signal.CAPITAL_CREED_BREACH,
                    mode = mode6853, lane = "", mint = "",
                )
            }
        } catch (_: Throwable) {}

        // Publish to CrossTalk
        CrossTalkFusionEngine.publish(AATESignal(
            source = TAG,
            market = "PORTFOLIO",
            confidence = 1.0 - portfolioHeat,
            horizonSec = 60,
            riskFlags = buildList {
                if (portfolioHeat > 0.8) add("PORTFOLIO_OVERHEATED")
                if (forcedDeRisk) add("FORCED_DERISK")
                if (correlationStress > 0.7) add("HIGH_CORRELATION")
                if (leverageConcentration > 0.6) add("LEVERAGE_CONCENTRATED")
            }
        ))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════════

    fun getReport(): PortfolioHeatReport? = currentReport.get()
    fun getPortfolioHeat(): Double = currentReport.get()?.portfolioHeat ?: 0.0
    fun getNewEntryPenalty(): Double = currentReport.get()?.newEntryPenalty ?: 0.0
    fun shouldDeRisk(): Boolean = currentReport.get()?.forcedDeRisk ?: false
    fun isNewEntryAllowed(): Boolean = (currentReport.get()?.portfolioHeat ?: 0.0) < 0.9

    fun getSafetyMultiplier(): Double {
        val heat = getPortfolioHeat()
        return (1.0 - heat * 0.8).coerceIn(0.1, 1.0)
    }

    fun clear() {
        activePositions.clear()
        currentReport.set(null)
    }
}
