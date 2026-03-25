package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import kotlin.math.abs

/**
 * VolumeProfileAnalyzer — Volume at Price (VAP) Analysis
 * ════════════════════════════════════════════════════════════════════
 * 
 * Identifies key price levels based on volume concentration:
 * - POC (Point of Control): Price level with highest volume
 * - VAH (Value Area High): Upper bound of 70% volume concentration
 * - VAL (Value Area Low): Lower bound of 70% volume concentration
 * - HVN (High Volume Nodes): Support/resistance from high activity
 * - LVN (Low Volume Nodes): Breakout zones with low resistance
 * 
 * Trading signals:
 * - Price near POC = consolidation, watch for breakout
 * - Price at VAL + buying = potential long entry
 * - Price at VAH + selling = potential exit
 * - Breaking through LVN = fast move likely (low resistance)
 */
object VolumeProfileAnalyzer {

    private const val NUM_PRICE_BINS = 20
    private const val VALUE_AREA_PCT = 0.70  // 70% of volume defines value area
    private const val HVN_THRESHOLD = 1.5    // 1.5x average = high volume node
    private const val LVN_THRESHOLD = 0.5    // 0.5x average = low volume node

    data class VolumeProfile(
        val poc: Double,                    // Point of Control price
        val vah: Double,                    // Value Area High
        val val_: Double,                   // Value Area Low (val is reserved)
        val hvnLevels: List<Double>,        // High Volume Node prices
        val lvnLevels: List<Double>,        // Low Volume Node prices
        val priceInValueArea: Boolean,      // Current price within VAL-VAH
        val distanceFromPoc: Double,        // % distance from POC
        val volumeSkew: Double,             // >0 = more volume above POC, <0 = below
        val signal: VolumeSignal
    )

    enum class VolumeSignal {
        ACCUMULATION,     // Price at VAL with buying pressure
        DISTRIBUTION,     // Price at VAH with selling pressure
        CONSOLIDATION,    // Price near POC, balanced volume
        BREAKOUT_UP,      // Price breaking above VAH through LVN
        BREAKOUT_DOWN,    // Price breaking below VAL through LVN
        NEUTRAL           // No clear signal
    }

    /**
     * Analyze volume profile for a token
     */
    fun analyze(ts: TokenState): VolumeProfile? {
        val history = ts.history.toList()
        if (history.size < 5) return null

        val currentPrice = ts.lastPrice
        if (currentPrice <= 0) return null

        return buildProfile(history, currentPrice, ts.meta.pressScore)
    }

    /**
     * Build volume profile from candle history
     */
    private fun buildProfile(
        candles: List<Candle>,
        currentPrice: Double,
        buyPressure: Double
    ): VolumeProfile {
        // Find price range
        val prices = candles.map { it.priceUsd }.filter { it > 0 }
        if (prices.isEmpty()) return defaultProfile(currentPrice)

        val minPrice = prices.minOrNull() ?: currentPrice
        val maxPrice = prices.maxOrNull() ?: currentPrice
        val priceRange = maxPrice - minPrice

        if (priceRange <= 0) return defaultProfile(currentPrice)

        // Create price bins and accumulate volume
        val binSize = priceRange / NUM_PRICE_BINS
        val volumeByBin = DoubleArray(NUM_PRICE_BINS) { 0.0 }
        val priceMidpoints = DoubleArray(NUM_PRICE_BINS) { i ->
            minPrice + binSize * (i + 0.5)
        }

        // Distribute volume into bins
        for (candle in candles) {
            val price = candle.priceUsd
            if (price <= 0) continue
            
            val binIndex = ((price - minPrice) / binSize).toInt()
                .coerceIn(0, NUM_PRICE_BINS - 1)
            volumeByBin[binIndex] += candle.vol
        }

        // Find POC (highest volume bin)
        val pocIndex = volumeByBin.indices.maxByOrNull { volumeByBin[it] } ?: 0
        val poc = priceMidpoints[pocIndex]

        // Calculate Value Area (70% of total volume around POC)
        val totalVolume = volumeByBin.sum()
        if (totalVolume <= 0) return defaultProfile(currentPrice)

        val targetVolume = totalVolume * VALUE_AREA_PCT
        var accumulatedVolume = volumeByBin[pocIndex]
        var vahIndex = pocIndex
        var valIndex = pocIndex

        // Expand outward from POC until we capture 70% of volume
        while (accumulatedVolume < targetVolume && (valIndex > 0 || vahIndex < NUM_PRICE_BINS - 1)) {
            val expandUp = vahIndex < NUM_PRICE_BINS - 1
            val expandDown = valIndex > 0
            
            val upVolume = if (expandUp) volumeByBin[vahIndex + 1] else 0.0
            val downVolume = if (expandDown) volumeByBin[valIndex - 1] else 0.0

            if (upVolume >= downVolume && expandUp) {
                vahIndex++
                accumulatedVolume += upVolume
            } else if (expandDown) {
                valIndex--
                accumulatedVolume += downVolume
            } else if (expandUp) {
                vahIndex++
                accumulatedVolume += upVolume
            }
        }

        val vah = priceMidpoints[vahIndex]
        val val_ = priceMidpoints[valIndex]

        // Find HVN and LVN levels
        val avgVolume = totalVolume / NUM_PRICE_BINS
        val hvnLevels = mutableListOf<Double>()
        val lvnLevels = mutableListOf<Double>()

        for (i in volumeByBin.indices) {
            when {
                volumeByBin[i] >= avgVolume * HVN_THRESHOLD -> hvnLevels.add(priceMidpoints[i])
                volumeByBin[i] <= avgVolume * LVN_THRESHOLD && volumeByBin[i] > 0 -> lvnLevels.add(priceMidpoints[i])
            }
        }

        // Analyze current price position
        val priceInValueArea = currentPrice in val_..vah
        val distanceFromPoc = if (poc > 0) ((currentPrice - poc) / poc) * 100 else 0.0

        // Calculate volume skew (volume above vs below POC)
        val volumeAbovePoc = (pocIndex + 1 until NUM_PRICE_BINS).sumOf { volumeByBin[it] }
        val volumeBelowPoc = (0 until pocIndex).sumOf { volumeByBin[it] }
        val volumeSkew = if (volumeAbovePoc + volumeBelowPoc > 0) {
            (volumeAbovePoc - volumeBelowPoc) / (volumeAbovePoc + volumeBelowPoc)
        } else 0.0

        // Determine signal
        val signal = determineSignal(
            currentPrice, poc, vah, val_, lvnLevels, 
            buyPressure, priceInValueArea, distanceFromPoc
        )

        return VolumeProfile(
            poc = poc,
            vah = vah,
            val_ = val_,
            hvnLevels = hvnLevels,
            lvnLevels = lvnLevels,
            priceInValueArea = priceInValueArea,
            distanceFromPoc = distanceFromPoc,
            volumeSkew = volumeSkew,
            signal = signal
        )
    }

    private fun determineSignal(
        price: Double,
        poc: Double,
        vah: Double,
        val_: Double,
        lvnLevels: List<Double>,
        buyPressure: Double,
        inValueArea: Boolean,
        distFromPoc: Double
    ): VolumeSignal {
        val nearPoc = abs(distFromPoc) < 3.0  // Within 3% of POC
        val nearVah = price >= vah * 0.98
        val nearVal = price <= val_ * 1.02
        val aboveVah = price > vah
        val belowVal = price < val_

        // Check if price is in a low volume zone (breakout territory)
        val inLvn = lvnLevels.any { abs((price - it) / it) < 0.02 }

        return when {
            // Breakout signals
            aboveVah && inLvn -> VolumeSignal.BREAKOUT_UP
            belowVal && inLvn -> VolumeSignal.BREAKOUT_DOWN
            
            // Value area signals
            nearVal && buyPressure >= 60 -> VolumeSignal.ACCUMULATION
            nearVah && buyPressure <= 40 -> VolumeSignal.DISTRIBUTION
            nearPoc -> VolumeSignal.CONSOLIDATION
            
            else -> VolumeSignal.NEUTRAL
        }
    }

    private fun defaultProfile(price: Double) = VolumeProfile(
        poc = price,
        vah = price * 1.02,
        val_ = price * 0.98,
        hvnLevels = emptyList(),
        lvnLevels = emptyList(),
        priceInValueArea = true,
        distanceFromPoc = 0.0,
        volumeSkew = 0.0,
        signal = VolumeSignal.NEUTRAL
    )

    /**
     * Get entry/exit score adjustment based on volume profile
     * Returns: Pair(entryAdjust, exitAdjust) - both in range [-20, +20]
     */
    fun getScoreAdjustment(profile: VolumeProfile?): Pair<Int, Int> {
        if (profile == null) return Pair(0, 0)

        var entryAdj = 0
        var exitAdj = 0

        when (profile.signal) {
            VolumeSignal.ACCUMULATION -> {
                entryAdj += 15  // Strong buy signal at VAL
                exitAdj -= 10   // Don't exit during accumulation
            }
            VolumeSignal.DISTRIBUTION -> {
                entryAdj -= 15  // Don't buy during distribution
                exitAdj += 15   // Strong exit signal at VAH
            }
            VolumeSignal.BREAKOUT_UP -> {
                entryAdj += 10  // Breakout momentum
                exitAdj -= 5    // Let it run
            }
            VolumeSignal.BREAKOUT_DOWN -> {
                entryAdj -= 20  // Don't catch falling knife
                exitAdj += 20   // Exit immediately
            }
            VolumeSignal.CONSOLIDATION -> {
                entryAdj += 5   // POC is support, mild bullish
            }
            VolumeSignal.NEUTRAL -> { /* no adjustment */ }
        }

        // Volume skew adjustment
        if (profile.volumeSkew > 0.3) {
            // More volume above = resistance overhead
            entryAdj -= 5
            exitAdj += 5
        } else if (profile.volumeSkew < -0.3) {
            // More volume below = support underneath
            entryAdj += 5
            exitAdj -= 5
        }

        return Pair(
            entryAdj.coerceIn(-20, 20),
            exitAdj.coerceIn(-20, 20)
        )
    }

    /**
     * Quick summary for logging
     */
    fun summarize(profile: VolumeProfile?): String {
        if (profile == null) return "VP: insufficient data"
        
        return "VP: ${profile.signal.name} | " +
               "POC=${formatPrice(profile.poc)} | " +
               "VA=[${formatPrice(profile.val_)}-${formatPrice(profile.vah)}] | " +
               "HVN=${profile.hvnLevels.size} LVN=${profile.lvnLevels.size}"
    }

    private fun formatPrice(p: Double): String = when {
        p >= 1.0 -> "%.2f".format(p)
        p >= 0.01 -> "%.4f".format(p)
        else -> "%.6f".format(p)
    }
}
