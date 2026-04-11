package com.lifecyclebot.v4.meta

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * ===============================================================================
 * NARRATIVE FLOW AI — V4 Meta-Intelligence
 * ===============================================================================
 *
 * Not "sentiment" in the generic sense. Narrative flow.
 * Track where attention is moving before raw price confirms fully.
 *
 * Usage:
 *   High heat + low persistence = scalp only
 *   High heat + rising breadth = allow swing bias
 *   High heat + exhausted breadth = sell into strength / suppress new entries
 *
 * ===============================================================================
 */
object NarrativeFlowAI {

    private const val TAG = "NarrativeFlowAI"
    private const val HEAT_DECAY = 0.95           // Per-period decay
    private const val PERSISTENCE_WINDOW = 30     // Periods to measure persistence

    // Narrative tracking per theme
    private val narratives = ConcurrentHashMap<String, NarrativeState>()

    // Symbol-to-narrative mapping
    private val symbolNarratives = ConcurrentHashMap<String, String>()

    data class NarrativeState(
        val theme: String,
        var heat: Double,               // 0.0 - 1.0 current heat
        var persistence: Double,        // 0.0 - 1.0 how long heat sustained
        var breadth: Double,            // 0.0 - 1.0 how many symbols participating
        var exhaustion: Double,         // 0.0 - 1.0 signs of topping
        var phase: NarrativePhase,
        val symbols: MutableSet<String>,
        val heatHistory: MutableList<Double>,
        var lastUpdated: Long = System.currentTimeMillis()
    )

    // ═══════════════════════════════════════════════════════════════════════
    // INITIALIZATION — Seed known narratives
    // ═══════════════════════════════════════════════════════════════════════

    fun init() {
        val seeds = mapOf(
            "AI_TOKENS" to listOf("NVDA", "AI", "RNDR", "FET", "AGIX"),
            "MEME_DOGS" to listOf("BONK", "WIF", "FLOKI", "SHIB"),
            "MEME_CATS" to listOf("MEW", "POPCAT"),
            "SOL_ECOSYSTEM" to listOf("SOL", "JUP", "PYTH", "ORCA", "RAY"),
            "RWA" to listOf("ONDO", "CRCL", "MSTR"),
            "DEPIN" to listOf("HNT", "MOBILE", "IOTX"),
            "TECH_STOCKS" to listOf("AAPL", "MSFT", "GOOGL", "META", "AMZN", "NVDA", "TSLA"),
            "FINANCE_STOCKS" to listOf("JPM", "GS", "BAC", "COIN"),
            "ENERGY_STOCKS" to listOf("XOM", "CVX", "XLE"),
            "HEALTH_STOCKS" to listOf("UNH", "LLY", "JNJ", "PFE", "ABBV"),
            "CRYPTO_MAJORS" to listOf("BTC", "ETH", "SOL", "BNB", "XRP"),
            "TRUMP_POLITICS" to listOf("TRUMP", "MELANIA"),
        )

        seeds.forEach { (theme, symbols) ->
            narratives[theme] = NarrativeState(
                theme = theme, heat = 0.0, persistence = 0.0, breadth = 0.0,
                exhaustion = 0.0, phase = NarrativePhase.DEAD,
                symbols = symbols.toMutableSet(), heatHistory = mutableListOf()
            )
            symbols.forEach { symbolNarratives[it] = theme }
        }

        ErrorLogger.info(TAG, "NarrativeFlowAI initialized with ${seeds.size} themes")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECORD ACTIVITY — Feed market activity
    // ═══════════════════════════════════════════════════════════════════════

    fun recordActivity(symbol: String, volumeSpike: Boolean = false, priceMovePct: Double = 0.0, mentionCount: Int = 0) {
        val theme = symbolNarratives[symbol] ?: return
        val narrative = narratives[theme] ?: return

        // Update heat
        var heatDelta = 0.0
        if (volumeSpike) heatDelta += 0.15
        if (kotlin.math.abs(priceMovePct) > 3.0) heatDelta += 0.10
        if (kotlin.math.abs(priceMovePct) > 1.0) heatDelta += 0.05
        if (mentionCount > 10) heatDelta += 0.10
        if (mentionCount > 50) heatDelta += 0.15

        narrative.heat = (narrative.heat * HEAT_DECAY + heatDelta).coerceIn(0.0, 1.0)
        narrative.heatHistory.add(narrative.heat)
        if (narrative.heatHistory.size > PERSISTENCE_WINDOW) narrative.heatHistory.removeAt(0)

        // Update persistence (how long heat has been above 0.3)
        val hotPeriods = narrative.heatHistory.count { it > 0.3 }
        narrative.persistence = (hotPeriods.toDouble() / PERSISTENCE_WINDOW).coerceIn(0.0, 1.0)

        // Update breadth (what % of theme symbols are active)
        narrative.breadth = if (narrative.symbols.isNotEmpty()) {
            narrative.symbols.count { s ->
                val sNarr = narratives[symbolNarratives[s]]
                sNarr != null && sNarr.heat > 0.2
            }.toDouble() / narrative.symbols.size
        } else 0.0

        // Update exhaustion (declining heat after peak)
        val recentHeat = narrative.heatHistory.takeLast(5).average()
        val olderHeat = narrative.heatHistory.take(5).average().takeIf { !it.isNaN() } ?: 0.0
        narrative.exhaustion = if (olderHeat > 0.3 && recentHeat < olderHeat) {
            ((olderHeat - recentHeat) / olderHeat).coerceIn(0.0, 1.0)
        } else 0.0

        // Determine phase
        narrative.phase = when {
            narrative.heat < 0.1 -> NarrativePhase.DEAD
            narrative.heat < 0.3 && narrative.persistence < 0.2 -> NarrativePhase.EMERGING
            narrative.heat > 0.3 && narrative.breadth > 0.3 && narrative.exhaustion < 0.3 -> NarrativePhase.EXPANDING
            narrative.heat > 0.5 && narrative.breadth > 0.6 -> NarrativePhase.MATURE
            narrative.exhaustion > 0.4 -> NarrativePhase.EXHAUSTING
            else -> NarrativePhase.EMERGING
        }

        narrative.lastUpdated = System.currentTimeMillis()

        // Publish high-heat narratives to CrossTalk
        if (narrative.heat > 0.3) {
            CrossTalkFusionEngine.publish(AATESignal(
                source = TAG,
                market = theme,
                symbol = symbol,
                confidence = narrative.heat,
                horizonSec = 600,
                narrativeHeat = narrative.heat,
                riskFlags = buildList {
                    if (narrative.exhaustion > 0.5) add("NARRATIVE_EXHAUSTING")
                    if (narrative.heat > 0.8 && narrative.persistence < 0.3) add("HYPE_SPIKE_LOW_PERSIST")
                }
            ))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════════

    fun getNarrativeReport(theme: String): NarrativeReport? {
        val state = narratives[theme] ?: return null
        return NarrativeReport(
            theme = state.theme,
            narrativeHeat = state.heat,
            narrativePersistence = state.persistence,
            themeBreadth = state.breadth,
            themeExhaustion = state.exhaustion,
            copyTradeRisk = if (state.heat > 0.7 && state.persistence > 0.5) 0.7 else 0.2,
            phase = state.phase,
            relatedSymbols = state.symbols.toList()
        )
    }

    fun getNarrativeForSymbol(symbol: String): NarrativeReport? {
        val theme = symbolNarratives[symbol] ?: return null
        return getNarrativeReport(theme)
    }

    fun getNarrativeHeat(symbol: String): Double {
        val theme = symbolNarratives[symbol] ?: return 0.0
        return narratives[theme]?.heat ?: 0.0
    }

    fun getNarrativeMultiplier(symbol: String): Double {
        val report = getNarrativeForSymbol(symbol) ?: return 1.0
        return when (report.phase) {
            NarrativePhase.EXPANDING -> 1.2
            NarrativePhase.MATURE -> 1.0
            NarrativePhase.EMERGING -> 0.9
            NarrativePhase.EXHAUSTING -> 0.5
            NarrativePhase.DEAD -> 0.7
        }
    }

    fun getHotNarratives(): List<NarrativeReport> {
        return narratives.values
            .filter { it.heat > 0.3 }
            .sortedByDescending { it.heat }
            .mapNotNull { getNarrativeReport(it.theme) }
    }

    fun clear() {
        narratives.values.forEach { it.heat = 0.0; it.persistence = 0.0; it.breadth = 0.0; it.exhaustion = 0.0; it.phase = NarrativePhase.DEAD; it.heatHistory.clear() }
    }
}
