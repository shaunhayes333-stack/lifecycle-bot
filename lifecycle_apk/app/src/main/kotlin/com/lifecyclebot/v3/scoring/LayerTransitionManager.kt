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
        // ═══════════════════════════════════════════════════════════════
        // V5.9.939 — TIER-AWARE LANE ROUTING (architectural fix).
        //
        // Pre-V5.9.939 this dispatcher only knew 4 of 9 lanes and used
        // enum-internal mcap ranges that DID NOT MATCH the actual lane
        // scorer constants. Result: tokens in $50K-$1M (the PUMP_FUN
        // graduate sweet spot) routed to BLUE_CHIP enum, then rejected
        // by BlueChip scorer's own $1M floor. ~98% V3 rejection.
        //
        // Operator doctrine: "executors, V3, lanes are ALL meant to be
        // mcap-aware for each tier." Lanes cover $500 → $100M+ across
        // the entire Solana universe. This dispatcher now honors that.
        //
        // ROUTING TRUTH = actual lane scorer mcap ranges:
        //   ShitCoinTraderAI:     $500    - $500K
        //   ShitCoinExpress:      $2K     - $300K
        //   ManipulatedTraderAI:  $5K     - $300K
        //   ProjectSniperAI:      $3K     - $500K
        //   QualityTraderAI:      $75K    - $1M
        //   DipHunterAI:          $50K    - $5M
        //   MoonshotTraderAI:     $10K    - $100M
        //   BlueChipTraderAI:     $1M     - ∞
        //
        // Strategy: pick the lane whose mcap range FITS the token and
        // whose secondary signal (age/momentum) is satisfied. Multiple
        // lanes overlap; we pick by mcap-tier-appropriateness.
        // ═══════════════════════════════════════════════════════════════
        return when {
            // ────────────────────────────────────────────────────────────
            // INSTITUTIONAL / BLUE_CHIP tier: ≥ $1M mcap. Mature only.
            // ────────────────────────────────────────────────────────────
            marketCapUsd >= 1_000_000.0 -> TradingLayer.BLUE_CHIP

            // ────────────────────────────────────────────────────────────
            // GROWTH tier: $100K - $1M. Sweet spot for DipHunter & Moonshot.
            // - If recent drawdown signal → DipHunter (mean-revert)
            // - Otherwise → Quality (the actual $75K-$1M lane)
            // ────────────────────────────────────────────────────────────
            marketCapUsd >= 100_000.0 -> {
                if (momentum <= -10.0) TradingLayer.DIP_HUNTER
                else TradingLayer.QUALITY
            }

            // ────────────────────────────────────────────────────────────
            // STANDARD tier: $50K - $100K. Fresh runners + dip plays.
            // - Strong momentum → Moonshot (built for the $10K-$100M run)
            // - Otherwise → DipHunter / Quality
            // ────────────────────────────────────────────────────────────
            marketCapUsd >= 50_000.0 -> {
                if (momentum >= 5.0) TradingLayer.MOONSHOT
                else TradingLayer.QUALITY
            }

            // ────────────────────────────────────────────────────────────
            // MICRO-HIGH tier: $10K - $50K. Active meme zone.
            // - Moonshot if momentum is real
            // - V3 Quality micro band ($5K-$50K) as fallback
            // ────────────────────────────────────────────────────────────
            marketCapUsd >= 10_000.0 -> {
                if (momentum >= 3.0) TradingLayer.MOONSHOT
                else TradingLayer.V3_QUALITY
            }

            // ────────────────────────────────────────────────────────────
            // MICRO tier: $5K - $10K. ShitCoin / Manipulated / Sniper land.
            // ────────────────────────────────────────────────────────────
            marketCapUsd >= 5_000.0 -> {
                when {
                    tokenAgeHours <= 0.5 -> TradingLayer.SHITCOIN     // ultra-fresh
                    momentum >= 5.0      -> TradingLayer.EXPRESS       // momentum
                    else                 -> TradingLayer.V3_QUALITY
                }
            }

            // ────────────────────────────────────────────────────────────
            // SUB-MICRO tier: $500 - $5K. ShitCoin's designed prey.
            // (Pre-graduation pump.fun tokens land here.)
            // ────────────────────────────────────────────────────────────
            marketCapUsd >= 500.0 -> TradingLayer.SHITCOIN

            // ────────────────────────────────────────────────────────────
            // PRE-LIQUIDITY tier: < $500 OR unknown mcap (==0).
            // Don't reject — fresh intakes often arrive with mcap=0 before
            // first hydration. ShitCoin's downstream MCAP_TOO_LOW will
            // gate it once real data lands.
            // ────────────────────────────────────────────────────────────
            else -> TradingLayer.SHITCOIN
        }
    }

    /**
     * V5.9.939 — Tier-aware decision floor.
     *
     * Returns the (scoreFloor, confFloor) pair for a given TradingLayer.
     * Used by DecisionEngine to gate EXECUTE per-tier instead of one-
     * global-floor-fits-all.
     *
     * MICRO lanes get looser floors (high churn, learning territory).
     * BLUE_CHIP gets tighter floors (only A+ entries make sense).
     */
    fun tierFloorsFor(layer: TradingLayer): Pair<Int, Int> {
        val base = when (layer) {
            TradingLayer.BLUE_CHIP    -> 65 to 60   // strict — only A+ for $1M+
            TradingLayer.QUALITY      -> 50 to 45
            TradingLayer.DIP_HUNTER   -> 45 to 40
            TradingLayer.MOONSHOT     -> 40 to 35
            TradingLayer.V3_QUALITY   -> 40 to 35
            TradingLayer.EXPRESS      -> 35 to 30
            TradingLayer.SHITCOIN     -> 30 to 25   // loose — learning + churn
            TradingLayer.TREASURY     -> 50 to 45
            TradingLayer.ORBITAL      -> 40 to 35
            // Planetary scale lanes (LUNAR/MARS/JUPITER) — large-cap territory.
            TradingLayer.LUNAR        -> 55 to 50
            TradingLayer.MARS         -> 60 to 55
            TradingLayer.JUPITER      -> 65 to 60
        }
        // V5.9.979 — z38 LiveLayerGateRelaxer consumer (was file-dead).
        // Operator spec: "gates need to be relaxed in live trading to
        // allow all the layers to trade." We apply BOTH paper+live to
        // preserve paper/live symmetry (Prime Doctrine #2) — paper is
        // our AGI training set so it must mirror live thresholds.
        // Multipliers in [0.5, 1.5] coerced inside Relaxer.setMultiplier.
        val tag = when (layer) {
            TradingLayer.BLUE_CHIP  -> "BLUECHIP"
            TradingLayer.QUALITY    -> "QUALITY"
            TradingLayer.DIP_HUNTER -> "QUALITY"
            TradingLayer.MOONSHOT   -> "MOONSHOT"
            TradingLayer.V3_QUALITY -> "QUALITY"
            TradingLayer.EXPRESS    -> "EXPRESS"
            TradingLayer.SHITCOIN   -> "SHITCOIN"
            TradingLayer.TREASURY   -> "TREASURY"
            TradingLayer.ORBITAL    -> "MEME"
            TradingLayer.LUNAR      -> "BLUECHIP"
            TradingLayer.MARS       -> "BLUECHIP"
            TradingLayer.JUPITER    -> "BLUECHIP"
        }
        return try {
            val m = com.lifecyclebot.engine.LiveLayerGateRelaxer.floorMultiplier(tag)
            if (m == 1.0) base
            else ((base.first  * m).toInt() to (base.second * m).toInt())
        } catch (_: Throwable) { base }
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
            com.lifecyclebot.engine.OpenPnlSanity.inspect(state.entryPrice, currentPrice, context = "LayerTransitionManager_6038", emit = true).takeIf { it.ok }?.pnlPct ?: 0.0
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