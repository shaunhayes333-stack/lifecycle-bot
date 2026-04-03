package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LAYER TRANSITION MANAGER - "RIDE THE WAVE" v2.0
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Cleaned up so ALL boundaries match:
 *
 * LAYERS:
 * - SHITCOIN:     0 → 5K
 * - EXPRESS:      0 → 5K (hot momentum microcaps)
 * - V3_QUALITY:   5K → 50K
 * - BLUE_CHIP:    50K+
 *
 * RULES:
 * - transitions only happen upward
 * - original entry price is preserved
 * - current layer is tracked independently
 * - transition thresholds use a buffer to prevent oscillation
 *
 * NOTE:
 * QUALITY / DIP_HUNTER / MOONSHOT family are kept in the enum for compatibility,
 * but the core transition path in this manager is:
 *
 * SHITCOIN / EXPRESS → V3_QUALITY → BLUE_CHIP
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object LayerTransitionManager {

    private const val TAG = "LayerTransition"

    // ═══════════════════════════════════════════════════════════════════════════
    // CANONICAL LAYER BOUNDARIES (USD)
    // These are the ONLY source of truth.
    // ═══════════════════════════════════════════════════════════════════════════

    const val SHITCOIN_MIN_MCAP = 0.0
    const val SHITCOIN_MAX_MCAP = 5_000.0
    const val SHITCOIN_MAX_AGE_HOURS = 6.0

    const val EXPRESS_MIN_MCAP = 0.0
    const val EXPRESS_MAX_MCAP = 5_000.0
    const val EXPRESS_MIN_MOMENTUM = 5.0

    const val V3_MIN_MCAP = 5_000.0
    const val V3_MAX_MCAP = 50_000.0

    const val BLUECHIP_MIN_MCAP = 50_000.0

    const val TRANSITION_BUFFER_PCT = 10.0

    // Compatibility-only ranges for other layers still referenced elsewhere
    const val QUALITY_MIN_MCAP = 50_000.0
    const val QUALITY_MAX_MCAP = 1_000_000.0

    const val DIP_HUNTER_MIN_MCAP = 50_000.0
    const val DIP_HUNTER_MAX_MCAP = 5_000_000.0

    const val MOONSHOT_MIN_MCAP = 5_000.0
    const val MOONSHOT_MAX_MCAP = 5_000_000.0

    // ═══════════════════════════════════════════════════════════════════════════
    // LAYER DEFINITIONS
    // ═══════════════════════════════════════════════════════════════════════════

    enum class TradingLayer(
        val emoji: String,
        val displayName: String,
        val minMcap: Double,
        val maxMcap: Double,
        val defaultTP: Double,
        val defaultSL: Double,
        val maxHoldMins: Int,
    ) {
        SHITCOIN(
            "💩", "ShitCoin",
            SHITCOIN_MIN_MCAP, SHITCOIN_MAX_MCAP,
            25.0, 10.0, 15
        ),

        EXPRESS(
            "💩🚂", "Express",
            EXPRESS_MIN_MCAP, EXPRESS_MAX_MCAP,
            30.0, 8.0, 10
        ),

        V3_QUALITY(
            "🎯", "V3 Quality",
            V3_MIN_MCAP, V3_MAX_MCAP,
            35.0, 12.0, 60
        ),

        QUALITY(
            "⭐", "Quality",
            QUALITY_MIN_MCAP, QUALITY_MAX_MCAP,
            35.0, 12.0, 60
        ),

        DIP_HUNTER(
            "📉", "DipHunter",
            DIP_HUNTER_MIN_MCAP, DIP_HUNTER_MAX_MCAP,
            20.0, 15.0, 360
        ),

        BLUE_CHIP(
            "🔵", "BlueChip",
            BLUECHIP_MIN_MCAP, Double.MAX_VALUE,
            40.0, 15.0, 120
        ),

        TREASURY(
            "💰", "Treasury",
            0.0, Double.MAX_VALUE,
            15.0, 8.0, 30
        ),

        MOONSHOT(
            "🚀", "Moonshot",
            MOONSHOT_MIN_MCAP, MOONSHOT_MAX_MCAP,
            200.0, 12.0, 60
        ),

        ORBITAL(
            "🛸", "Orbital",
            50_000.0, 100_000.0,
            100.0, 10.0, 45
        ),

        LUNAR(
            "🌙", "Lunar",
            100_000.0, 500_000.0,
            200.0, 12.0, 60
        ),

        MARS(
            "🔴", "Mars",
            500_000.0, 1_000_000.0,
            500.0, 15.0, 120
        ),

        JUPITER(
            "🪐", "Jupiter",
            1_000_000.0, 50_000_000.0,
            1000.0, 20.0, 240
        ),
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE TRACKING
    // ═══════════════════════════════════════════════════════════════════════════

    private val positionLayers = ConcurrentHashMap<String, LayerState>()

    data class LayerState(
        val mint: String,
        val symbol: String,
        var currentLayer: TradingLayer,
        var entryLayer: TradingLayer,
        var entryMcap: Double,
        var entryPrice: Double,
        var highestMcap: Double,
        var transitionCount: Int = 0,
        val transitionHistory: MutableList<TransitionRecord> = mutableListOf(),
    )

    data class TransitionRecord(
        val fromLayer: TradingLayer,
        val toLayer: TradingLayer,
        val mcapAtTransition: Double,
        val priceAtTransition: Double,
        val timestamp: Long,
        val pnlAtTransition: Double,
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Volatile
    private var initialized = false

    fun init() {
        if (initialized) {
            ErrorLogger.warn(TAG, "⚠️ init() called again - BLOCKED (already initialized)")
            return
        }

        initialized = true

        ErrorLogger.info(TAG, "🔄 Layer Transition Manager initialized (ONE-TIME)")
        ErrorLogger.info(TAG, "   ShitCoin: <\$${(SHITCOIN_MAX_MCAP / 1_000).fmt(1)}K")
        ErrorLogger.info(TAG, "   V3 Quality: \$${(V3_MIN_MCAP / 1_000).fmt(1)}K - \$${(V3_MAX_MCAP / 1_000).fmt(1)}K")
        ErrorLogger.info(TAG, "   Blue Chip: >=\$${(BLUECHIP_MIN_MCAP / 1_000).fmt(1)}K")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LAYER DETERMINATION
    // ═══════════════════════════════════════════════════════════════════════════

    fun determineLayer(
        marketCapUsd: Double,
        tokenAgeHours: Double,
        momentum: Double = 0.0,
        isQualitySetup: Boolean = false,
    ): TradingLayer {
        return when {
            marketCapUsd >= BLUECHIP_MIN_MCAP -> TradingLayer.BLUE_CHIP

            marketCapUsd in V3_MIN_MCAP..V3_MAX_MCAP -> TradingLayer.V3_QUALITY

            marketCapUsd <= EXPRESS_MAX_MCAP && momentum >= EXPRESS_MIN_MOMENTUM ->
                TradingLayer.EXPRESS

            marketCapUsd <= SHITCOIN_MAX_MCAP && tokenAgeHours <= SHITCOIN_MAX_AGE_HOURS ->
                TradingLayer.SHITCOIN

            // Fallbacks
            marketCapUsd < V3_MIN_MCAP -> TradingLayer.SHITCOIN
            else -> TradingLayer.V3_QUALITY
        }
    }

    fun isV3QualityCandidate(
        marketCapUsd: Double,
        tokenAgeHours: Double,
        liquidityUsd: Double,
        buyPressurePct: Double,
        holderConcentration: Double,
    ): Boolean {
        if (marketCapUsd < V3_MIN_MCAP || marketCapUsd > V3_MAX_MCAP) return false

        val hasGoodLiquidity = liquidityUsd >= marketCapUsd * 0.10
        val hasGoodBuyPressure = buyPressurePct >= 45.0
        val hasGoodDistribution = holderConcentration <= 40.0
        val hasAge = tokenAgeHours >= 0.5

        val qualitySignals = listOf(
            hasGoodLiquidity,
            hasGoodBuyPressure,
            hasGoodDistribution,
            hasAge,
        )

        return qualitySignals.count { it } >= 3
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION TRACKING
    // ═══════════════════════════════════════════════════════════════════════════

    fun registerPosition(
        mint: String,
        symbol: String,
        layer: TradingLayer,
        entryMcap: Double,
        entryPrice: Double,
    ) {
        positionLayers[mint] = LayerState(
            mint = mint,
            symbol = symbol,
            currentLayer = layer,
            entryLayer = layer,
            entryMcap = entryMcap,
            entryPrice = entryPrice,
            highestMcap = entryMcap,
        )

        ErrorLogger.info(
            TAG,
            "📝 Registered: $symbol | ${layer.emoji} ${layer.displayName} | mcap=${formatMcap(entryMcap)}"
        )
    }

    fun getCurrentLayer(mint: String): TradingLayer? = positionLayers[mint]?.currentLayer

    fun getLayerState(mint: String): LayerState? = positionLayers[mint]

    fun removePosition(mint: String) {
        positionLayers.remove(mint)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TRANSITION LOGIC
    // ═══════════════════════════════════════════════════════════════════════════

    data class TransitionSignal(
        val shouldTransition: Boolean,
        val fromLayer: TradingLayer,
        val toLayer: TradingLayer,
        val reason: String,
        val newTakeProfit: Double,
        val newStopLoss: Double,
    )

    fun checkTransition(
        mint: String,
        currentMcap: Double,
        currentPrice: Double,
    ): TransitionSignal {
        val state = positionLayers[mint] ?: return TransitionSignal(
            shouldTransition = false,
            fromLayer = TradingLayer.V3_QUALITY,
            toLayer = TradingLayer.V3_QUALITY,
            reason = "NOT_TRACKED",
            newTakeProfit = 0.0,
            newStopLoss = 0.0
        )

        val currentLayer = state.currentLayer

        if (currentMcap > state.highestMcap) {
            state.highestMcap = currentMcap
        }

        val pnlPct = if (state.entryPrice > 0) {
            (currentPrice - state.entryPrice) / state.entryPrice * 100.0
        } else {
            0.0
        }

        // Require profit and meaningful growth from original mcap before upgrading
        if (pnlPct <= 0.0 || currentMcap <= state.entryMcap * 1.10) {
            return TransitionSignal(
                false, currentLayer, currentLayer, "NOT_PROFITABLE", 0.0, 0.0
            )
        }

        val v3Trigger = V3_MIN_MCAP * (1.0 + TRANSITION_BUFFER_PCT / 100.0)          // 5.5K
        val blueChipTrigger = BLUECHIP_MIN_MCAP * (1.0 + TRANSITION_BUFFER_PCT / 100.0) // 55K

        val newLayer = when (currentLayer) {
            TradingLayer.SHITCOIN, TradingLayer.EXPRESS -> {
                when {
                    currentMcap >= blueChipTrigger -> TradingLayer.BLUE_CHIP
                    currentMcap >= v3Trigger -> TradingLayer.V3_QUALITY
                    else -> null
                }
            }

            TradingLayer.V3_QUALITY -> {
                if (currentMcap >= blueChipTrigger) TradingLayer.BLUE_CHIP else null
            }

            TradingLayer.DIP_HUNTER -> {
                if (currentMcap >= blueChipTrigger) TradingLayer.BLUE_CHIP else null
            }

            else -> null
        }

        if (newLayer != null && newLayer != currentLayer) {
            val record = TransitionRecord(
                fromLayer = currentLayer,
                toLayer = newLayer,
                mcapAtTransition = currentMcap,
                priceAtTransition = currentPrice,
                timestamp = System.currentTimeMillis(),
                pnlAtTransition = pnlPct,
            )

            state.transitionHistory.add(record)
            state.transitionCount += 1
            state.currentLayer = newLayer

            ErrorLogger.info(
                TAG,
                "🚀 TRANSITION: ${state.symbol} | ${currentLayer.emoji} ${currentLayer.displayName} → " +
                    "${newLayer.emoji} ${newLayer.displayName} | mcap=${formatMcap(currentMcap)} | " +
                    "P&L=+${pnlPct.fmt(1)}% | Transition #${state.transitionCount}"
            )

            return TransitionSignal(
                shouldTransition = true,
                fromLayer = currentLayer,
                toLayer = newLayer,
                reason = "MCAP_BREAKOUT: ${formatMcap(currentMcap)}",
                newTakeProfit = newLayer.defaultTP,
                newStopLoss = newLayer.defaultSL,
            )
        }

        return TransitionSignal(
            false, currentLayer, currentLayer, "NO_TRANSITION", 0.0, 0.0
        )
    }

    fun getAdjustedTargets(
        mint: String,
        baseTakeProfit: Double,
        baseStopLoss: Double,
    ): Pair<Double, Double> {
        val state = positionLayers[mint] ?: return Pair(baseTakeProfit, baseStopLoss)

        val transitionBonus = state.transitionCount * 10.0
        val adjustedTP = baseTakeProfit + transitionBonus

        val adjustedSL = baseStopLoss *
            (1.0 - state.transitionCount * 0.1).coerceAtLeast(0.5)

        return Pair(adjustedTP, adjustedSL)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════

    data class TransitionStats(
        val totalPositions: Int,
        val positionsByLayer: Map<TradingLayer, Int>,
        val totalTransitions: Int,
        val multiTransitionPositions: Int,
    )

    fun getStats(): TransitionStats {
        val positions = positionLayers.values.toList()

        return TransitionStats(
            totalPositions = positions.size,
            positionsByLayer = positions.groupBy { it.currentLayer }.mapValues { it.value.size },
            totalTransitions = positions.sumOf { it.transitionCount },
            multiTransitionPositions = positions.count { it.transitionCount >= 2 },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun formatMcap(value: Double): String {
        return when {
            value >= 1_000_000.0 -> "\$${(value / 1_000_000.0).fmt(2)}M"
            value >= 1_000.0 -> "\$${(value / 1_000.0).fmt(1)}K"
            else -> "\$${value.fmt(0)}"
        }
    }

    private fun Double.fmt(decimals: Int): String =
        String.format("%.${decimals}f", this)
}