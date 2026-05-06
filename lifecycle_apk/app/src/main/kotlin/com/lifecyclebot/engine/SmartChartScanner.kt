package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * SmartChartScanner — Multi-timeframe chart pattern detection.
 * 
 * Analyzes price action across multiple timeframes to detect:
 * - Candlestick patterns (Hammer, Doji, Engulfing, etc.)
 * - Chart patterns (Double Bottom, Breakout, etc.)
 * - Volume analysis
 * - Holder dynamics
 * 
 * DEFENSIVE DESIGN:
 * - Static object, no initialization
 * - All methods wrapped in try/catch
 * - Thread-safe data structures
 * - Feeds insights to SuperBrainEnhancements
 */
object SmartChartScanner {
    
    private const val TAG = "SmartChart"
    
    // ═══════════════════════════════════════════════════════════════════
    // TIMEFRAMES
    // ═══════════════════════════════════════════════════════════════════
    
    enum class Timeframe(val label: String, val minutes: Int) {
        M1("1m", 1),
        M5("5m", 5),
        M15("15m", 15),
        H1("1h", 60),
        H4("4h", 240),
        D1("1D", 1440),
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PATTERN TYPES
    // ═══════════════════════════════════════════════════════════════════
    
    enum class CandlePattern(val emoji: String, val bullish: Boolean) {
        // Bullish reversal candles
        HAMMER("🔨", true),
        INVERTED_HAMMER("🔨", true),
        BULLISH_ENGULFING("🌊", true),
        MORNING_STAR("⭐", true),
        PIERCING_LINE("📈", true),
        // V5.9.495z13 — operator spec "trader's cheat sheet" expansion.
        THREE_WHITE_SOLDIERS("🪖", true),
        TWEEZER_BOTTOM("🪙", true),

        // Neutral
        DOJI("✝️", false),

        // Bearish reversal candles
        HANGING_MAN("🪢", false),
        SHOOTING_STAR("💫", false),
        BEARISH_ENGULFING("🌊", false),
        EVENING_STAR("🌙", false),
        DARK_CLOUD("☁️", false),
        // V5.9.495z13
        THREE_BLACK_CROWS("🐦", false),
        TWEEZER_TOP("🪙", false),
    }

    enum class ChartPattern(val emoji: String, val bullish: Boolean) {
        // Bullish patterns
        DOUBLE_BOTTOM("W", true),
        TRIPLE_BOTTOM("W₃", true),
        INVERSE_HEAD_SHOULDERS("👤", true),
        BULLISH_FLAG("🏁", true),
        CUP_HANDLE("☕", true),
        ASCENDING_TRIANGLE("△", true),
        FALLING_WEDGE("📐", true),
        BREAKOUT("🚀", true),
        // V5.9.495z13
        BULLISH_PENNANT("🚩", true),

        // Bearish patterns
        DOUBLE_TOP("M", false),
        TRIPLE_TOP("M₃", false),
        HEAD_SHOULDERS("👤", false),
        BEARISH_FLAG("🚩", false),
        DESCENDING_TRIANGLE("▽", false),
        RISING_WEDGE("📐", false),
        BREAKDOWN("📉", false),
        // V5.9.495z13 — degen classics
        DEAD_CAT_BOUNCE("🐱", false),
        BEARISH_PENNANT("🚩", false),

        // Neutral / continuation
        SYMMETRIC_TRIANGLE("◇", true),  // marked bullish-by-default; bias is set per-detection
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // SCAN RESULTS
    // ═══════════════════════════════════════════════════════════════════
    
    data class ScanResult(
        val mint: String,
        val symbol: String,
        val timeframe: Timeframe,
        val candlePatterns: List<CandlePattern>,
        val chartPatterns: List<ChartPattern>,
        val volumeSignal: VolumeSignal,
        val holderSignal: HolderSignal,
        val overallBias: String,  // "BULLISH", "BEARISH", "NEUTRAL"
        val confidence: Double,   // 0-100
        val timestampMs: Long,
    )
    
    enum class VolumeSignal {
        SURGE,        // Volume spike (>2x average)
        INCREASING,   // Above average
        NORMAL,       // Around average
        DECREASING,   // Below average
        DRY,          // Very low volume
        UNKNOWN,      // Cannot determine
    }
    
    enum class HolderSignal {
        ACCUMULATION,  // Holders increasing
        DISTRIBUTION,  // Holders decreasing
        STABLE,        // No significant change
        UNKNOWN,
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PRICE DATA CACHE
    // ═══════════════════════════════════════════════════════════════════
    
    data class PriceCandle(
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
        val timestampMs: Long,
    ) {
        val body: Double get() = abs(close - open)
        val upperWick: Double get() = high - maxOf(open, close)
        val lowerWick: Double get() = minOf(open, close) - low
        val range: Double get() = high - low
        val isBullish: Boolean get() = close > open
        val isBearish: Boolean get() = close < open
        val isDoji: Boolean get() = body < range * 0.1
    }
    
    private val priceCache = ConcurrentHashMap<String, MutableList<PriceCandle>>()
    private const val MAX_CANDLES = 200
    
    // ═══════════════════════════════════════════════════════════════════
    // MAIN SCAN FUNCTION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Scan a token across all timeframes.
     * 
     * @param ts TokenState with price history
     * @return List of ScanResults for each timeframe
     */
    fun scan(ts: TokenState): List<ScanResult> {
        return try {
            val results = mutableListOf<ScanResult>()
            
            // Build candles from token history
            val candles = buildCandles(ts)
            if (candles.size < 5) {
                return emptyList()  // Not enough data
            }
            
            // Cache for later analysis
            priceCache[ts.mint] = candles.toMutableList()
            
            // Analyze each timeframe we have data for
            Timeframe.values().forEach { tf ->
                val tfCandles = aggregateToTimeframe(candles, tf)
                if (tfCandles.size >= 3) {
                    val result = analyzeTimeframe(ts.mint, ts.symbol, tf, tfCandles)
                    results.add(result)
                    
                    // Record insights to SuperBrain
                    recordInsights(result)
                }
            }
            
            results
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "scan error: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Quick scan for a specific timeframe.
     */
    fun quickScan(ts: TokenState, timeframe: Timeframe = Timeframe.M5): ScanResult? {
        return try {
            val candles = buildCandles(ts)
            if (candles.size < 5) return null
            
            val tfCandles = aggregateToTimeframe(candles, timeframe)
            if (tfCandles.size < 3) return null
            
            analyzeTimeframe(ts.mint, ts.symbol, timeframe, tfCandles)
        } catch (e: Exception) {
            null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // CANDLE BUILDING
    // ═══════════════════════════════════════════════════════════════════
    
    private fun buildCandles(ts: TokenState): List<PriceCandle> {
        return try {
            ts.history.mapNotNull { candle ->
                try {
                    // Use actual OHLC if available, otherwise derive from priceUsd
                    val open = if (candle.openUsd > 0) candle.openUsd else candle.priceUsd
                    val high = if (candle.highUsd > 0) candle.highUsd else candle.priceUsd * 1.005
                    val low = if (candle.lowUsd > 0) candle.lowUsd else candle.priceUsd * 0.995
                    val close = candle.priceUsd
                    
                    PriceCandle(
                        open = open,
                        high = high,
                        low = low,
                        close = close,
                        volume = candle.vol,
                        timestampMs = candle.ts,
                    )
                } catch (e: Exception) {
                    null
                }
            }.takeLast(MAX_CANDLES)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun aggregateToTimeframe(candles: List<PriceCandle>, tf: Timeframe): List<PriceCandle> {
        return try {
            if (candles.isEmpty()) return emptyList()
            
            val periodMs = tf.minutes * 60_000L
            val grouped = candles.groupBy { it.timestampMs / periodMs }
            
            grouped.map { (_, group) ->
                PriceCandle(
                    open = group.first().open,
                    high = group.maxOf { it.high },
                    low = group.minOf { it.low },
                    close = group.last().close,
                    volume = group.sumOf { it.volume },
                    timestampMs = group.first().timestampMs,
                )
            }.sortedBy { it.timestampMs }
        } catch (e: Exception) {
            candles
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PATTERN DETECTION
    // ═══════════════════════════════════════════════════════════════════
    
    private fun analyzeTimeframe(
        mint: String,
        symbol: String,
        tf: Timeframe,
        candles: List<PriceCandle>,
    ): ScanResult {
        val candlePatterns = detectCandlePatterns(candles)
        val chartPatterns = detectChartPatterns(candles)
        val volumeSignal = analyzeVolume(candles)
        val holderSignal = HolderSignal.UNKNOWN  // Would need holder data
        
        // Calculate overall bias
        var bullishScore = 0
        var bearishScore = 0
        
        candlePatterns.forEach { p ->
            if (p.bullish) bullishScore++ else bearishScore++
        }
        chartPatterns.forEach { p ->
            if (p.bullish) bullishScore += 2 else bearishScore += 2
        }
        
        when (volumeSignal) {
            VolumeSignal.SURGE -> bullishScore++
            VolumeSignal.DRY -> bearishScore++
            else -> {}
        }
        
        val total = bullishScore + bearishScore
        val bias = when {
            total == 0 -> "NEUTRAL"
            bullishScore > bearishScore * 1.5 -> "BULLISH"
            bearishScore > bullishScore * 1.5 -> "BEARISH"
            else -> "NEUTRAL"
        }
        
        val confidence = if (total > 0) {
            maxOf(bullishScore, bearishScore).toDouble() / total * 100
        } else 50.0
        
        return ScanResult(
            mint = mint,
            symbol = symbol,
            timeframe = tf,
            candlePatterns = candlePatterns,
            chartPatterns = chartPatterns,
            volumeSignal = volumeSignal,
            holderSignal = holderSignal,
            overallBias = bias,
            confidence = confidence.coerceIn(0.0, 100.0),
            timestampMs = System.currentTimeMillis(),
        )
    }
    
    private fun detectCandlePatterns(candles: List<PriceCandle>): List<CandlePattern> {
        if (candles.size < 3) return emptyList()

        val patterns = mutableListOf<CandlePattern>()
        val last = candles.last()
        val prev = candles[candles.size - 2]
        val third = if (candles.size >= 3) candles[candles.size - 3] else null

        // Recent trend context for hammer-vs-hanging-man discrimination
        val recentCloses = candles.takeLast(8).map { it.close }
        val priorAvg = if (recentCloses.size >= 4) recentCloses.dropLast(2).average() else last.close
        val isInUptrend   = last.close > priorAvg * 1.01
        val isInDowntrend = last.close < priorAvg * 0.99

        try {
            // ── Doji ──────────────────────────────────────────────────────
            if (last.isDoji) patterns.add(CandlePattern.DOJI)

            // ── Hammer / Hanging Man (long lower wick, small body) ────────
            if (last.lowerWick > last.body * 2 && last.upperWick < last.body * 0.5) {
                if (isInDowntrend && (last.isBullish || last.isDoji)) {
                    patterns.add(CandlePattern.HAMMER)
                } else if (isInUptrend) {
                    // Same shape but appears at uptrend top → bearish reversal.
                    patterns.add(CandlePattern.HANGING_MAN)
                } else if (last.isBullish || last.isDoji) {
                    // Default to hammer when trend is ambiguous.
                    patterns.add(CandlePattern.HAMMER)
                }
            }

            // ── Inverted Hammer / Shooting Star (long upper wick) ─────────
            if (last.upperWick > last.body * 2 && last.lowerWick < last.body * 0.5) {
                if (isInDowntrend) {
                    patterns.add(CandlePattern.INVERTED_HAMMER)
                } else if (last.isBearish || last.isDoji) {
                    patterns.add(CandlePattern.SHOOTING_STAR)
                }
            }

            // ── Bullish / Bearish Engulfing ───────────────────────────────
            if (prev.isBearish && last.isBullish &&
                last.open < prev.close && last.close > prev.open) {
                patterns.add(CandlePattern.BULLISH_ENGULFING)
            }
            if (prev.isBullish && last.isBearish &&
                last.open > prev.close && last.close < prev.open) {
                patterns.add(CandlePattern.BEARISH_ENGULFING)
            }

            // ── Piercing Line (bullish 2-bar reversal) ────────────────────
            //   Bear candle, then bull candle that opens below prev low and
            //   closes above prev midpoint (but below prev open).
            if (prev.isBearish && last.isBullish && prev.body > 0) {
                val prevMid = (prev.open + prev.close) / 2.0
                if (last.open < prev.close && last.close > prevMid && last.close < prev.open) {
                    patterns.add(CandlePattern.PIERCING_LINE)
                }
            }

            // ── Dark Cloud Cover (bearish counterpart of piercing line) ───
            if (prev.isBullish && last.isBearish && prev.body > 0) {
                val prevMid = (prev.open + prev.close) / 2.0
                if (last.open > prev.close && last.close < prevMid && last.close > prev.open) {
                    patterns.add(CandlePattern.DARK_CLOUD)
                }
            }

            // ── Morning Star (3-candle bullish reversal) ──────────────────
            //   Big bear, small body (any color), big bull closing above
            //   the midpoint of the first bear candle.
            if (third != null && third.isBearish && last.isBullish &&
                third.body > 0 && last.body > 0) {
                val starBodyTiny = prev.body < third.body * 0.4
                val thirdMid = (third.open + third.close) / 2.0
                if (starBodyTiny && last.close > thirdMid) {
                    patterns.add(CandlePattern.MORNING_STAR)
                }
            }

            // ── Evening Star (3-candle bearish reversal) ──────────────────
            if (third != null && third.isBullish && last.isBearish &&
                third.body > 0 && last.body > 0) {
                val starBodyTiny = prev.body < third.body * 0.4
                val thirdMid = (third.open + third.close) / 2.0
                if (starBodyTiny && last.close < thirdMid) {
                    patterns.add(CandlePattern.EVENING_STAR)
                }
            }

            // ── Three White Soldiers (3 strong consecutive bull candles) ──
            if (third != null && third.isBullish && prev.isBullish && last.isBullish &&
                prev.close > third.close && last.close > prev.close) {
                val avgBody = (third.body + prev.body + last.body) / 3.0
                if (avgBody > 0 && prev.upperWick < prev.body * 0.5 && last.upperWick < last.body * 0.5) {
                    patterns.add(CandlePattern.THREE_WHITE_SOLDIERS)
                }
            }

            // ── Three Black Crows (3 strong consecutive bear candles) ─────
            if (third != null && third.isBearish && prev.isBearish && last.isBearish &&
                prev.close < third.close && last.close < prev.close) {
                val avgBody = (third.body + prev.body + last.body) / 3.0
                if (avgBody > 0 && prev.lowerWick < prev.body * 0.5 && last.lowerWick < last.body * 0.5) {
                    patterns.add(CandlePattern.THREE_BLACK_CROWS)
                }
            }

            // ── Tweezer Bottom (matching lows after downtrend → bullish) ──
            if (isInDowntrend && prev.body > 0 && last.body > 0) {
                val lowDelta = abs(prev.low - last.low)
                val sizeRef  = maxOf(prev.body, last.body)
                if (sizeRef > 0 && lowDelta < sizeRef * 0.15 && prev.isBearish && last.isBullish) {
                    patterns.add(CandlePattern.TWEEZER_BOTTOM)
                }
            }

            // ── Tweezer Top (matching highs after uptrend → bearish) ──────
            if (isInUptrend && prev.body > 0 && last.body > 0) {
                val highDelta = abs(prev.high - last.high)
                val sizeRef   = maxOf(prev.body, last.body)
                if (sizeRef > 0 && highDelta < sizeRef * 0.15 && prev.isBullish && last.isBearish) {
                    patterns.add(CandlePattern.TWEEZER_TOP)
                }
            }
        } catch (_: Exception) {
            // Pattern detection is non-critical; never crash the scanner.
        }

        return patterns
    }

    private fun detectChartPatterns(candles: List<PriceCandle>): List<ChartPattern> {
        if (candles.size < 10) return emptyList()

        val patterns = mutableListOf<ChartPattern>()

        try {
            val prices = candles.map { it.close }
            val highs  = candles.map { it.high }
            val lows   = candles.map { it.low }
            val recent = prices.takeLast(20)

            if (recent.size < 10) return patterns

            val high     = recent.max()
            val low      = recent.min()
            val last     = recent.last()
            val avgPrice = recent.average()

            // ── Breakout / Breakdown (wide range take-out) ────────────────
            val priorHigh = prices.dropLast(5).takeLast(20).maxOrNull() ?: high
            if (last > priorHigh * 1.05) patterns.add(ChartPattern.BREAKOUT)
            val priorLow = prices.dropLast(5).takeLast(20).minOrNull() ?: low
            if (last < priorLow * 0.95) patterns.add(ChartPattern.BREAKDOWN)

            // ── Double / Triple Bottom + Top ──────────────────────────────
            val firstHalf  = recent.take(recent.size / 2)
            val secondHalf = recent.drop(recent.size / 2)
            val firstLow   = firstHalf.min()
            val secondLow  = secondHalf.min()
            val firstHigh  = firstHalf.max()
            val secondHigh = secondHalf.max()
            val tol        = avgPrice * 0.03

            if (abs(firstLow - secondLow) < tol && last > avgPrice) {
                patterns.add(ChartPattern.DOUBLE_BOTTOM)
            }
            if (abs(firstHigh - secondHigh) < tol && last < avgPrice) {
                patterns.add(ChartPattern.DOUBLE_TOP)
            }

            // Triple bottom: split last 30 into thirds.
            if (prices.size >= 30) {
                val window = prices.takeLast(30)
                val a = window.subList(0, 10)
                val b = window.subList(10, 20)
                val c = window.subList(20, 30)
                val la = a.min(); val lb = b.min(); val lc = c.min()
                val ha = a.max(); val hb = b.max(); val hc = c.max()
                val tighten = avgPrice * 0.04
                if (abs(la - lb) < tighten && abs(lb - lc) < tighten && last > avgPrice) {
                    patterns.add(ChartPattern.TRIPLE_BOTTOM)
                }
                if (abs(ha - hb) < tighten && abs(hb - hc) < tighten && last < avgPrice) {
                    patterns.add(ChartPattern.TRIPLE_TOP)
                }
            }

            // ── Head & Shoulders (bearish 3-peak) / Inverse (bullish 3-trough)
            if (prices.size >= 30) {
                val window = prices.takeLast(30)
                val left  = window.subList(0, 10)
                val mid   = window.subList(10, 20)
                val right = window.subList(20, 30)

                val lh = left.max(); val mh = mid.max(); val rh = right.max()
                val ll = left.min(); val ml = mid.min(); val rl = right.min()
                val shoulderTol = avgPrice * 0.05

                // H&S: middle peak (head) is highest, shoulders ~equal, then sells off.
                if (mh > lh * 1.02 && mh > rh * 1.02 && abs(lh - rh) < shoulderTol && last < avgPrice) {
                    patterns.add(ChartPattern.HEAD_SHOULDERS)
                }
                // Inverse H&S: middle trough lowest, shoulders ~equal, recovery.
                if (ml < ll * 0.98 && ml < rl * 0.98 && abs(ll - rl) < shoulderTol && last > avgPrice) {
                    patterns.add(ChartPattern.INVERSE_HEAD_SHOULDERS)
                }
            }

            // ── Bullish / Bearish Flag (sharp move + tight counter channel)
            if (recent.size >= 15) {
                val poleWindow = recent.subList(0, recent.size - 8)
                val flagWindow = recent.takeLast(8)
                val poleStart  = poleWindow.first()
                val poleEnd    = poleWindow.last()
                val poleMovePct = (poleEnd - poleStart) / poleStart * 100.0
                val flagRangePct = (flagWindow.max() - flagWindow.min()) / flagWindow.average() * 100.0

                // Bullish flag: pole > +10%, flag drifts down/sideways < 6% range.
                if (poleMovePct > 10.0 && flagRangePct < 6.0 && flagWindow.last() < flagWindow.first()) {
                    patterns.add(ChartPattern.BULLISH_FLAG)
                }
                // Bearish flag: pole < -10%, flag drifts up/sideways < 6%.
                if (poleMovePct < -10.0 && flagRangePct < 6.0 && flagWindow.last() > flagWindow.first()) {
                    patterns.add(ChartPattern.BEARISH_FLAG)
                }
                // Pennant variant: same pole but flag is symmetric (compresses both sides)
                if (abs(poleMovePct) > 10.0 && flagRangePct < 4.0) {
                    if (poleMovePct > 0) patterns.add(ChartPattern.BULLISH_PENNANT)
                    else                  patterns.add(ChartPattern.BEARISH_PENNANT)
                }
            }

            // ── Ascending / Descending / Symmetric Triangle ───────────────
            if (highs.size >= 20 && lows.size >= 20) {
                val rh = highs.takeLast(20)
                val rl = lows.takeLast(20)
                val highSlope = linearSlope(rh)
                val lowSlope  = linearSlope(rl)
                val avgRange  = rh.average() - rl.average()
                val flatThr   = avgRange * 0.005   // < 0.5% drift = "flat"

                if (abs(highSlope) < flatThr && lowSlope > flatThr) {
                    patterns.add(ChartPattern.ASCENDING_TRIANGLE)
                }
                if (highSlope < -flatThr && abs(lowSlope) < flatThr) {
                    patterns.add(ChartPattern.DESCENDING_TRIANGLE)
                }
                if (highSlope < -flatThr && lowSlope > flatThr) {
                    patterns.add(ChartPattern.SYMMETRIC_TRIANGLE)
                }

                // ── Wedges: both slopes same direction, converging.
                //   Falling wedge → both descending, lows descending slower
                //   (bullish reversal). Rising wedge → both rising, highs
                //   rising slower (bearish reversal).
                if (highSlope < -flatThr && lowSlope < -flatThr && lowSlope > highSlope) {
                    patterns.add(ChartPattern.FALLING_WEDGE)
                }
                if (highSlope > flatThr && lowSlope > flatThr && highSlope < lowSlope) {
                    patterns.add(ChartPattern.RISING_WEDGE)
                }
            }

            // ── Cup & Handle (rounded U + small consolidation + breakout) ──
            //   Look at last 30 bars: dip in the middle ~equal start/end,
            //   followed by a tight 5-8 bar consolidation that doesn't
            //   exceed the prior peak by more than ~2% and now is breaking
            //   out.
            if (prices.size >= 30) {
                val cup     = prices.takeLast(30).take(22)   // first 22 of last 30 = the cup
                val handle  = prices.takeLast(8)              // last 8 = the handle
                val cupStart = cup.first()
                val cupEnd   = cup.last()
                val cupLow   = cup.min()
                val handleHi = handle.max()
                val handleLo = handle.min()
                val handleRangePct = if (handle.average() > 0)
                    (handleHi - handleLo) / handle.average() * 100.0 else 0.0

                val rim = (cupStart + cupEnd) / 2.0
                val cupOk = cupLow < rim * 0.92 && abs(cupStart - cupEnd) < rim * 0.04
                val handleOk = handleRangePct < 5.0 && handleHi < rim * 1.02
                if (cupOk && handleOk && last > rim * 1.005) {
                    patterns.add(ChartPattern.CUP_HANDLE)
                }
            }

            // ── Dead Cat Bounce (sharp drop → modest bounce → resumption) ─
            //   Window: last 18 bars. First 6 = drop ≥ -15%. Middle 6 =
            //   bounce reclaiming ≤ 40% of the drop. Last 6 = price rolling
            //   back over below bounce midpoint.
            if (prices.size >= 18) {
                val w = prices.takeLast(18)
                val drop      = w.subList(0, 6)
                val bounce    = w.subList(6, 12)
                val resumption = w.subList(12, 18)
                val dropStart = drop.first()
                val dropEnd   = drop.last()
                val dropPct   = (dropEnd - dropStart) / dropStart * 100.0
                if (dropPct < -15.0) {
                    val bounceHi  = bounce.max()
                    val recovered = (bounceHi - dropEnd) / (dropStart - dropEnd)
                    val resumeMid = (resumption.first() + resumption.last()) / 2.0
                    val bounceMid = (bounce.first() + bounce.last()) / 2.0
                    if (recovered in 0.10..0.40 && resumeMid < bounceMid) {
                        patterns.add(ChartPattern.DEAD_CAT_BOUNCE)
                    }
                }
            }

        } catch (_: Exception) {
            // Pattern detection is non-critical; never crash the scanner.
        }

        return patterns
    }

    /**
     * Simple least-squares slope of a numeric series. Used by triangle/wedge
     * detection to decide whether the highs and lows are flat, rising, or
     * falling. Returns price units per bar.
     */
    private fun linearSlope(series: List<Double>): Double {
        if (series.size < 2) return 0.0
        val n = series.size
        val xs = (0 until n).map { it.toDouble() }
        val xMean = xs.average()
        val yMean = series.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (xs[i] - xMean) * (series[i] - yMean)
            den += (xs[i] - xMean) * (xs[i] - xMean)
        }
        return if (den == 0.0) 0.0 else num / den
    }
    
    private fun analyzeVolume(candles: List<PriceCandle>): VolumeSignal {
        return try {
            if (candles.size < 5) return VolumeSignal.NORMAL
            
            val volumes = candles.map { it.volume }
            val avgVolume = volumes.dropLast(1).average()
            val lastVolume = volumes.last()
            
            if (avgVolume <= 0) return VolumeSignal.UNKNOWN
            
            val ratio = lastVolume / avgVolume
            
            when {
                ratio >= 2.0 -> VolumeSignal.SURGE
                ratio >= 1.2 -> VolumeSignal.INCREASING
                ratio >= 0.8 -> VolumeSignal.NORMAL
                ratio >= 0.3 -> VolumeSignal.DECREASING
                else -> VolumeSignal.DRY
            }
        } catch (e: Exception) {
            VolumeSignal.NORMAL
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // SUPERBRAIN INTEGRATION
    // ═══════════════════════════════════════════════════════════════════
    
    private fun recordInsights(result: ScanResult) {
        try {
            // Record candle patterns
            result.candlePatterns.forEach { pattern ->
                SuperBrainEnhancements.recordChartInsight(
                    mint = result.mint,
                    symbol = result.symbol,
                    pattern = pattern.name,
                    timeframe = result.timeframe.label,
                    confidence = result.confidence,
                    priceAtDetection = 0.0,  // Not tracked here
                )
            }
            
            // Record chart patterns
            result.chartPatterns.forEach { pattern ->
                SuperBrainEnhancements.recordChartInsight(
                    mint = result.mint,
                    symbol = result.symbol,
                    pattern = pattern.name,
                    timeframe = result.timeframe.label,
                    confidence = result.confidence + 10,  // Chart patterns are higher confidence
                    priceAtDetection = 0.0,
                )
            }
            
            // Record aggregated signal
            val signalType = when (result.overallBias) {
                "BULLISH" -> "BULLISH"
                "BEARISH" -> "BEARISH"
                else -> "NEUTRAL"
            }
            SuperBrainEnhancements.recordSignal(
                mint = result.mint,
                symbol = result.symbol,
                source = "SmartChart_${result.timeframe.label}",
                signalType = signalType,
            )
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordInsights error: ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PUBLIC HELPERS
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Get summary for display.
     */
    fun getScanSummary(mint: String): String {
        return try {
            val cache = priceCache[mint] ?: return "No data"
            
            val lastCandle = cache.lastOrNull() ?: return "No candles"
            val patterns = detectCandlePatterns(cache)
            val volume = analyzeVolume(cache)
            
            buildString {
                if (patterns.isNotEmpty()) {
                    append(patterns.joinToString(" ") { it.emoji })
                    append(" ")
                }
                append("Vol: ${volume.name.lowercase()}")
            }
        } catch (e: Exception) {
            "Scan error"
        }
    }
    
    /**
     * Get bias emoji for quick display.
     */
    fun getBiasEmoji(bias: String): String {
        return when (bias.uppercase()) {
            "BULLISH" -> "🟢"
            "BEARISH" -> "🔴"
            else -> "🟡"
        }
    }
    
    /**
     * Clear cache for a token.
     */
    fun clearCache(mint: String) {
        priceCache.remove(mint)
    }
    
    /**
     * Clear all caches.
     */
    fun clearAllCaches() {
        priceCache.clear()
    }
}
