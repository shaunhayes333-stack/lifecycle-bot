package com.lifecyclebot.v4.meta

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
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

    // V5.0.6826 §FUSION_BUS_DARK_ON_MEME_PATH — the bus had 12 readers but
    // only 4 writers (alt scan, stock scan, trade close, one UI screen), and
    // none of them run on the meme path. Since getSnapshot() hard-nulls a
    // snapshot older than SIGNAL_TTL_MS, UnifiedScorer — which reads the
    // snapshot once per candidate on the highest-frequency lane — saw null
    // unless an alt/stock scan or a close happened in the preceding 60s. Every
    // fused cross-talk signal (killFlags, fragility, narrative heat, regime,
    // per-market caps) was therefore discarded on the lane that trades most.
    // Re-fuse on read when the cache has expired, rate-limited so
    // per-candidate scoring cannot spin it.
    private const val AUTO_REFUSE_MIN_INTERVAL_MS = 5_000L
    private val lastAutoFuseMs = AtomicLong(0L)

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
        val now = System.currentTimeMillis()
        // Bound by age and by source on every publish. The former loop only
        // trimmed when the global queue exceeded 500 and stopped if its head
        // was fresh, so a noisy source could grow the bus without bound.
        signalBuffer.removeIf { now - it.timestamp >= SIGNAL_TTL_MS }
        val sameSource = signalBuffer.filter { it.source == signal.source }
        if (sameSource.size >= MAX_SIGNALS_PER_SOURCE) {
            sameSource.sortedBy { it.timestamp }
                .take(sameSource.size - MAX_SIGNALS_PER_SOURCE + 1)
                .forEach { signalBuffer.remove(it) }
        }
        signalBuffer.add(signal)
        latestBySource[signal.source] = signal
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
        // V5.0.6873 §PER_MARKET_CAPS_FILTERED_ON_KEYS_THAT_CANNOT_MATCH — both
        // filters below were structural never-matches for the market that carries
        // almost all the volume.
        //
        // fragilityMap is keyed by `signal.symbol ?: signal.market`, and every
        // fragility publisher passes a symbol — a meme ticker like BONK. So
        // `key.startsWith("MEME") || key == "MEME"` matched nothing, and
        // perMarketCaps["MEME"].confidenceCap was computed from fragility 0.0 no
        // matter how fragile the book actually was. The signals already carry their
        // own `market` field, so aggregate on that instead of guessing from the key.
        //
        // strategyTrust is keyed by strategy name — "CryptoAltAI", "ForexAI",
        // "MetalsAI", "TokenizedStockAI", and for memes the lane names that
        // TradeLessonRecorder records (MOONSHOT_*, SHITCOIN, STANDARD, ...). None
        // contains the literal "MEME", so that filter also matched nothing and every
        // meme sizeCap fell to the hardcoded 0.5. Fall back to the overall trust mean
        // rather than a constant, so an empty per-market match degrades to real
        // evidence instead of a made-up number.
        val overallTrust6873 = strategyTrust.values.average().takeIf { !it.isNaN() } ?: 0.5
        val perMarketCaps = mutableMapOf<String, MarketCap>()
        for (market in listOf("MEME", "STOCKS", "PERPS", "FOREX", "METALS", "COMMODITIES")) {
            val fragility = liveSignals
                .filter { it.market.equals(market, ignoreCase = true) }
                .mapNotNull { it.fragilityScore }
                .maxOrNull()
                ?: fragilityMap.entries
                    .filter { it.key.startsWith(market, ignoreCase = true) || it.key.equals(market, ignoreCase = true) }
                    .map { it.value }.maxOrNull()
                ?: 0.0

            val trust = strategyTrust.entries
                .filter { it.key.contains(market, ignoreCase = true) }
                .map { it.value }.average().takeIf { !it.isNaN() } ?: overallTrust6873

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

    fun getSnapshot(): CrossTalkSnapshot? {
        val now = System.currentTimeMillis()
        val cached = currentSnapshot.get()
        if (cached != null && now - cached.timestamp < SIGNAL_TTL_MS) return cached

        // Expired or never fused. fuse() only reads already-computed state
        // (atomic getters plus one pass over the TTL-bounded signal queue), so
        // it is safe and cheap to drive from a reader. One winner per interval;
        // losers take whatever the winner published rather than re-fusing.
        val last = lastAutoFuseMs.get()
        if (now - last >= AUTO_REFUSE_MIN_INTERVAL_MS && lastAutoFuseMs.compareAndSet(last, now)) {
            return try { fuse() } catch (_: Throwable) { null }
        }
        // Never hand back a snapshot past its TTL.
        return currentSnapshot.get()?.takeIf { System.currentTimeMillis() - it.timestamp < SIGNAL_TTL_MS }
    }

    fun getLatestSignal(source: String): AATESignal? {
        val signal = latestBySource[source] ?: return null
        if (System.currentTimeMillis() - signal.timestamp >= SIGNAL_TTL_MS) {
            latestBySource.remove(source, signal)
            return null
        }
        return signal
    }

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

    /**
     * V5.0.6878 §THE_MEME_PATH_NEVER_CONSULTED_THE_FUSION_ENGINE — computeGatedScore
     * is called only by CryptoAltTrader:1451 and TokenizedStockTrader:951. The meme
     * book, which carries almost all the volume, never asked the cross-talk engine
     * anything, so the hive's shared view of a candidate reached perps and stocks
     * and not the lane that mattered.
     *
     * It cannot simply be called from the meme path, because two of its seven
     * channels — portfolioSafetyMultiplier and liquiditySafetyMultiplier — were
     * wired DIRECTLY into the Executor sizing stack in V5.0.6853. Calling the whole
     * gate would square portfolio heat and fragility.
     *
     * So this exposes only the channels the meme stack does NOT already have:
     *   strategyTrust      — StrategyTrustAI's learned trust in this lane, fed by
     *                        TradeLessonRecorder (whose context became real in 6859)
     *   narrativePersistence — NarrativeFlowAI heat for the symbol
     *   leadLag            — cross-asset rotation probability
     *   perMarketCaps      — the market's own size cap, which also finally gives
     *                        perMarketCaps a reader; it has been computed on every
     *                        fuse() and consumed nowhere (its two filters were
     *                        structural never-matches until V5.0.6873)
     *
     * Deliberately excluded: portfolioSafety and liquiditySafety (already applied at
     * the Executor), and regimeFit (the Executor's own regimeMult already shapes on
     * RegimeDetector; CrossMarketRegimeAI's GlobalRiskMode is a related enough axis
     * that stacking both risks double-damping the same condition).
     *
     * Bounded to [0.70, 1.30] and fail-open at 1.0 — shape, never veto. The vetoes
     * computeGatedScore would raise are already covered on the meme path by the
     * AdaptiveVetoConsensusAuthority publishers wired in 6853 and 6862.
     */
    fun memeShapeMultiplier6878(symbol: String?, lane: String?, market: String = "MEME"): Double {
        return try {
            val snapshot = getSnapshot() ?: return 1.0
            val trustMult = lane?.trim()?.takeIf { it.isNotBlank() }?.let { l ->
                snapshot.strategyTrust[l] ?: snapshot.strategyTrust[l.uppercase()]
            }?.let { trust ->
                when {
                    trust < 0.2 -> 0.80
                    trust < 0.4 -> 0.90
                    trust < 0.6 -> 1.00
                    trust < 0.8 -> 1.08
                    else -> 1.15
                }
            } ?: 1.0
            val narrMult = symbol?.trim()?.takeIf { it.isNotBlank() }?.let { s ->
                snapshot.narrativeMap[s] ?: snapshot.narrativeMap[s.uppercase()]
            }?.let { heat ->
                when {
                    heat > 0.8 -> 1.15
                    heat > 0.5 -> 1.00
                    heat > 0.2 -> 0.92
                    else -> 0.85
                }
            } ?: 1.0
            val leadLagMult = symbol?.trim()?.takeIf { it.isNotBlank() }?.let { s ->
                snapshot.leadLagLinks.firstOrNull { it.lagger == s || it.lagger.equals(s, true) }
                    ?.let { 1.0 + it.rotationProbability * 0.20 }
            } ?: 1.0
            val marketSizeCap = snapshot.perMarketCaps[market]?.sizeCap?.takeIf { it.isFinite() && it > 0.0 }
                ?.coerceIn(0.70, 1.0) ?: 1.0
            (trustMult * narrMult * leadLagMult * marketSizeCap).coerceIn(0.70, 1.30)
        } catch (_: Throwable) { 1.0 }
    }

    fun computeGatedScore(
        baseScore: Double,
        strategy: String,
        market: String,
        symbol: String? = null,
        leverageRequested: Double = 1.0
    ): GatedScore {
        val snapshot = getSnapshot() ?: return GatedScore(
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
        // V5.9.56: raised thresholds — EXTREME veto moved to match CRITICAL level (0.75),
        // and the minor 0.9x penalty raised from >0.3 to >0.5 so stable assets aren't taxed
        val fragility = snapshot.fragilityMap[symbol ?: market] ?: 0.0
        val liquidityMult = when {
            fragility > 0.75 -> { vetoes.add("EXTREME_FRAGILITY"); 0.0 }
            fragility > 0.6 -> 0.5
            fragility > 0.5 -> 0.75
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

    /**
     * V5.0.6859 — public session read. The trading session was already computed on
     * every fuse() but was only reachable through a snapshot, so callers that needed
     * it outside the fusion cycle (the lesson recorder, most importantly) hardcoded
     * SessionContext.OFF_HOURS instead. Prefer the live snapshot's value and fall
     * back to computing it, so a stale or absent snapshot still yields the truth.
     */
    fun currentSession6859(): SessionContext =
        try { getSnapshot()?.sessionContext ?: detectSession() } catch (_: Throwable) { detectSession() }

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
        lastAutoFuseMs.set(0L)
    }
}
