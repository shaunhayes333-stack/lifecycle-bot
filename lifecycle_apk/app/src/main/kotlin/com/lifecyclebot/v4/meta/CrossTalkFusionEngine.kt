package com.lifecyclebot.v4.meta

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * ===============================================================================
 * CROSS-TALK FUSION ENGINE — V4 Meta-Intelligence Bus
 * ===============================================================================
 *
 * Central aggregation layer. Every AI module publishes AATESignal objects.
 * This engine fuses them into a single CrossTalkSnapshot consumed by
 * FinalDecisionEngine.
 *
 * Architecture position: Between Base Analyzers and FinalDecisionEngine
 *
 * Signal flow:
 *   [StrategyTrustAI]  ──┐
 *   [FragilityAI]       ──┤
 *   [LeadLagAI]         ──┼──→ CrossTalkFusionEngine ──→ CrossTalkSnapshot ──→ FDE
 *   [RegimeAI]          ──┤
 *   [PortfolioHeatAI]   ──┤
 *   [LeverageSurvivalAI]──┤
 *   [NarrativeFlowAI]   ──┤
 *   [ExecutionPathAI]   ──┘
 *
 * ===============================================================================
 */
object CrossTalkFusionEngine {

    private const val TAG = "CrossTalkFusion"
    private const val SIGNAL_TTL_MS = 60_000L       // Signals expire after 60s
    private const val MAX_SIGNALS_PER_SOURCE = 50    // Cap per module

    // Signal buffer — all modules publish here
    private val signalBuffer = ConcurrentLinkedQueue<AATESignal>()

    // Latest snapshot
    private val currentSnapshot = AtomicReference<CrossTalkSnapshot?>(null)

    // Per-module latest signals for quick access
    private val latestBySource = ConcurrentHashMap<String, AATESignal>()

    // Module references (set during init)
    private var strategyTrustAI: StrategyTrustAI? = null
    private var liquidityFragilityAI: LiquidityFragilityAI? = null
    private var crossAssetLeadLagAI: CrossAssetLeadLagAI? = null
    private var crossMarketRegimeAI: CrossMarketRegimeAI? = null
    private var portfolioHeatAI: PortfolioHeatAI? = null
    private var leverageSurvivalAI: LeverageSurvivalAI? = null
    private var narrativeFlowAI: NarrativeFlowAI? = null
    private var executionPathAI: ExecutionPathAI? = null

    // ═══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════

    fun init(
        strategyTrust: StrategyTrustAI? = null,
        fragility: LiquidityFragilityAI? = null,
        leadLag: CrossAssetLeadLagAI? = null,
        regime: CrossMarketRegimeAI? = null,
        portfolioHeat: PortfolioHeatAI? = null,
        leverageSurvival: LeverageSurvivalAI? = null,
        narrative: NarrativeFlowAI? = null,
        executionPath: ExecutionPathAI? = null
    ) {
        strategyTrustAI = strategyTrust
        liquidityFragilityAI = fragility
        crossAssetLeadLagAI = leadLag
        crossMarketRegimeAI = regime
        portfolioHeatAI = portfolioHeat
        leverageSurvivalAI = leverageSurvival
        narrativeFlowAI = narrative
        executionPathAI = executionPath
        ErrorLogger.info(TAG, "CrossTalkFusionEngine initialized")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PUBLISH — Any module calls this
    // ═══════════════════════════════════════════════════════════════════════

    fun publish(signal: AATESignal) {
        signalBuffer.add(signal)
        latestBySource[signal.source] = signal

        // Trim old signals
        val now = System.currentTimeMillis()
        while (signalBuffer.size > MAX_SIGNALS_PER_SOURCE * 10) {
            val oldest = signalBuffer.peek()
            if (oldest != null && now - oldest.timestamp > SIGNAL_TTL_MS) {
                signalBuffer.poll()
            } else {
                break
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // FUSE — Aggregate all signals into a snapshot
    // ═══════════════════════════════════════════════════════════════════════

    fun fuse(): CrossTalkSnapshot {
        val now = System.currentTimeMillis()
        val liveSignals = signalBuffer.filter { now - it.timestamp < SIGNAL_TTL_MS }

        // 1. Determine global risk mode
        val regime = crossMarketRegimeAI?.getCurrentRegime() ?: GlobalRiskMode.RISK_ON
        val session = detectSession()

        // 2. Build market bias from all directional signals
        val marketBias = mutableMapOf<String, Double>()
        liveSignals.groupBy { it.market }.forEach { (market, signals) ->
            val avgConf = signals.mapNotNull { s ->
                when (s.direction) {
                    "LONG" -> s.confidence
                    "SHORT" -> -s.confidence
                    else -> null
                }
            }.average().takeIf { !it.isNaN() } ?: 0.0
            marketBias[market] = avgConf.coerceIn(-1.0, 1.0)
        }

        // 3. Aggregate strategy trust scores
        val strategyTrust = mutableMapOf<String, Double>()
        strategyTrustAI?.getAllTrustScores()?.forEach { (strategy, record) ->
            strategyTrust[strategy] = record.trustScore
        }

        // 4. Build narrative map
        val narrativeMap = mutableMapOf<String, Double>()
        liveSignals.filter { it.narrativeHeat != null }.forEach { s ->
            val key = s.symbol ?: s.market
            narrativeMap[key] = maxOf(narrativeMap[key] ?: 0.0, s.narrativeHeat ?: 0.0)
        }

        // 5. Build fragility map
        val fragilityMap = mutableMapOf<String, Double>()
        liveSignals.filter { it.fragilityScore != null }.forEach { s ->
            val key = s.symbol ?: s.market
            fragilityMap[key] = maxOf(fragilityMap[key] ?: 0.0, s.fragilityScore ?: 0.0)
        }

        // 6. Collect lead-lag links
        val leadLagLinks = crossAssetLeadLagAI?.getActiveLinks() ?: emptyList()

        // 7. Determine leverage cap
        val leverageVerdict = leverageSurvivalAI?.getVerdict()
        val leverageCap = if (leverageVerdict?.noLeverageOverride == true) 0.0
                          else leverageVerdict?.allowedLeverage ?: 5.0

        // 8. Portfolio heat
        val portfolioReport = portfolioHeatAI?.getReport()
        val portfolioHeat = portfolioReport?.portfolioHeat ?: 0.0

        // 9. Build kill flags
        val killFlags = mutableListOf<String>()
        if (leverageCap <= 0) killFlags.add("NO_LEVERAGE")
        if (portfolioHeat > 0.9) killFlags.add("PORTFOLIO_OVERHEATED")
        if (regime == GlobalRiskMode.CHAOTIC) killFlags.add("CHAOTIC_REGIME")
        liveSignals.flatMap { it.riskFlags }.distinct().forEach { flag ->
            if (flag !in killFlags) killFlags.add(flag)
        }

        // 10. Per-market caps
        val perMarketCaps = mutableMapOf<String, MarketCap>()
        for (market in listOf("MEME", "STOCKS", "PERPS", "FOREX", "METALS", "COMMODITIES")) {
            val fragility = fragilityMap.entries
                .filter { it.key.startsWith(market) || it.key == market }
                .map { it.value }.maxOrNull() ?: 0.0

            val trust = strategyTrust.entries
                .filter { it.key.contains(market, ignoreCase = true) }
                .map { it.value }.average().takeIf { !it.isNaN() } ?: 0.5

            perMarketCaps[market] = MarketCap(
                market = market,
                confidenceCap = (1.0 - fragility * 0.5).coerceIn(0.1, 1.0),
                sizeCap = (trust * (1.0 - fragility * 0.3)).coerceIn(0.1, 1.0),
                leverageCap = if (fragility > 0.7) 1.0 else leverageCap,
                capitalBias = marketBias[market] ?: 0.0
            )
        }

        val snapshot = CrossTalkSnapshot(
            globalRiskMode = regime,
            sessionContext = session,
            marketBias = marketBias,
            strategyTrust = strategyTrust,
            narrativeMap = narrativeMap,
            fragilityMap = fragilityMap,
            leadLagLinks = leadLagLinks,
            leverageCap = leverageCap,
            portfolioHeat = portfolioHeat,
            killFlags = killFlags,
            perMarketCaps = perMarketCaps,
            timestamp = now
        )

        currentSnapshot.set(snapshot)
        return snapshot
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY — Quick access for any module
    // ═══════════════════════════════════════════════════════════════════════

    fun getSnapshot(): CrossTalkSnapshot? = currentSnapshot.get()

    fun getLatestSignal(source: String): AATESignal? = latestBySource[source]

    fun getSignalsForMarket(market: String): List<AATESignal> {
        val now = System.currentTimeMillis()
        return signalBuffer.filter { it.market == market && now - it.timestamp < SIGNAL_TTL_MS }
    }

    fun getSignalsForSymbol(symbol: String): List<AATESignal> {
        val now = System.currentTimeMillis()
        return signalBuffer.filter { it.symbol == symbol && now - it.timestamp < SIGNAL_TTL_MS }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GATED SCORING — Multiplicative scoring with hard vetoes
    // ═══════════════════════════════════════════════════════════════════════

    fun computeGatedScore(
        baseScore: Double,
        strategy: String,
        market: String,
        symbol: String? = null,
        leverageRequested: Double = 1.0
    ): GatedScore {
        val snapshot = currentSnapshot.get() ?: return GatedScore(
            baseScore, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, emptyList()
        )

        val vetoes = mutableListOf<String>()

        // Strategy trust multiplier
        val trustMult = snapshot.strategyTrust[strategy]?.let { trust ->
            when {
                trust < 0.2 -> { vetoes.add("STRATEGY_DISTRUSTED:$strategy"); 0.0 }
                trust < 0.4 -> 0.5
                trust < 0.6 -> 0.8
                trust < 0.8 -> 1.0
                else -> 1.2
            }
        } ?: 1.0

        // Regime fit multiplier
        val regimeMult = when (snapshot.globalRiskMode) {
            GlobalRiskMode.RISK_ON -> 1.1
            GlobalRiskMode.TRENDING -> 1.0
            GlobalRiskMode.ROTATIONAL -> 0.9
            GlobalRiskMode.MEAN_REVERT -> 0.8
            GlobalRiskMode.RISK_OFF -> 0.6
            GlobalRiskMode.CHAOTIC -> { vetoes.add("CHAOTIC_REGIME"); 0.3 }
        }

        // Execution quality multiplier
        val execMult = 1.0 // From ExecutionPathAI when available

        // Narrative persistence multiplier
        val narrMult = symbol?.let { s ->
            snapshot.narrativeMap[s]?.let { heat ->
                when {
                    heat > 0.8 -> 1.2   // Hot narrative
                    heat > 0.5 -> 1.0   // Active
                    heat > 0.2 -> 0.8   // Cooling
                    else -> 0.6          // Dead narrative
                }
            }
        } ?: 1.0

        // Lead-lag multiplier
        val leadLagMult = symbol?.let { s ->
            val link = snapshot.leadLagLinks.firstOrNull { it.lagger == s || it.lagger == market }
            link?.let { 1.0 + it.rotationProbability * 0.3 }
        } ?: 1.0

        // Portfolio safety multiplier
        val portfolioMult = when {
            snapshot.portfolioHeat > 0.9 -> { vetoes.add("PORTFOLIO_OVERHEATED"); 0.0 }
            snapshot.portfolioHeat > 0.7 -> 0.5
            snapshot.portfolioHeat > 0.5 -> 0.8
            else -> 1.0
        }

        // Liquidity safety multiplier
        val fragility = snapshot.fragilityMap[symbol ?: market] ?: 0.0
        val liquidityMult = when {
            fragility > 0.9 -> { vetoes.add("EXTREME_FRAGILITY"); 0.0 }
            fragility > 0.7 -> 0.4
            fragility > 0.5 -> 0.7
            fragility > 0.3 -> 0.9
            else -> 1.0
        }

        // Leverage veto
        if (leverageRequested > 1.0 && leverageRequested > snapshot.leverageCap) {
            vetoes.add("LEVERAGE_EXCEEDS_CAP:requested=${leverageRequested},cap=${snapshot.leverageCap}")
        }

        // Kill flags
        snapshot.killFlags.forEach { flag ->
            if (flag == "NO_LEVERAGE" && leverageRequested > 1.0) vetoes.add("KILL:$flag")
            if (flag == "NO_NEW_ENTRIES") vetoes.add("KILL:$flag")
            if (flag == "NO_MEME" && market == "MEME") vetoes.add("KILL:$flag")
        }

        return GatedScore(
            baseOpportunityScore = baseScore,
            strategyTrustMultiplier = trustMult,
            regimeFitMultiplier = regimeMult,
            executionQualityMultiplier = execMult,
            narrativePersistenceMultiplier = narrMult,
            leadLagMultiplier = leadLagMult,
            portfolioSafetyMultiplier = portfolioMult,
            liquiditySafetyMultiplier = liquidityMult,
            vetoes = vetoes
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════

    private fun detectSession(): SessionContext {
        val utcHour = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .get(java.util.Calendar.HOUR_OF_DAY)
        return when (utcHour) {
            in 0..7 -> SessionContext.ASIA
            8 -> SessionContext.ASIA_LONDON
            in 9..12 -> SessionContext.LONDON
            in 13..15 -> SessionContext.LONDON_NY
            in 16..20 -> SessionContext.NY
            else -> SessionContext.OFF_HOURS
        }
    }

    fun getStats(): Map<String, Any> = mapOf(
        "buffer_size" to signalBuffer.size,
        "sources" to latestBySource.keys.toList(),
        "has_snapshot" to (currentSnapshot.get() != null),
        "snapshot_age_ms" to (System.currentTimeMillis() - (currentSnapshot.get()?.timestamp ?: 0))
    )

    fun clear() {
        signalBuffer.clear()
        latestBySource.clear()
        currentSnapshot.set(null)
    }
}
