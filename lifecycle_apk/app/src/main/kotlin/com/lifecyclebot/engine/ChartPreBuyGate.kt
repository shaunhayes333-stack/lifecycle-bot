package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.6120h — ChartPreBuyGate
 *
 * OPERATOR DIRECTIVE: "the bot is still firing into live tokens that
 * immediately drop. so that should tell us we are buying at the wrong
 * stages in the tokens life. the chart must be consulted before the
 * buy. we have pattern recognition a brain stack better than any
 * crypto degens brain yet get out performed by rajeed from the slums."
 *
 * The chart brain (SmartChartScanner) already exists and emits ScanResult
 * per token with a `overallBias` (BULLISH/BEARISH/NEUTRAL) and confidence
 * 0-100, plus a list of ChartPattern hits (BREAKOUT, DEAD_CAT_BOUNCE,
 * DESCENDING_TRIANGLE, etc). This module wraps the scanner in a hot-path
 * consultation the FDG runs on every live entry:
 *
 *   1. If chart is confidently BEARISH (≥60) → veto (multiplier 0.0)
 *   2. If chart shows a hard bearish pattern (DEAD_CAT_BOUNCE, BREAKDOWN,
 *      DESCENDING_TRIANGLE, RISING_WEDGE) → veto
 *   3. If chart is mildly BEARISH (40-59) → size ×0.5
 *   4. If chart is BULLISH ≥60 + BREAKOUT / CUP_HANDLE / DOUBLE_BOTTOM
 *      → size ×1.3 (chart tailwind)
 *   5. Otherwise → 1.0 (no shape)
 *
 * Cached per mint (30s TTL) so the same token doesn't trigger 5 scans
 * per bot loop. Fail-open: any scanner error yields 1.0 (no interference
 * with the existing entry decision).
 */
object ChartPreBuyGate {

    // ── Tunables ────────────────────────────────────────────────────────
    private const val BEARISH_HARD_VETO_CONF = 60.0
    private const val BEARISH_SIZE_DAMP_CONF = 40.0
    private const val BULLISH_BOOST_CONF     = 60.0

    private const val CACHE_TTL_MS = 30_000L

    // Bearish patterns that should hard-veto entry — buying into these is
    // buying into a live rug pattern the market has already telegraphed.
    private val HARD_BEARISH_PATTERNS = setOf(
        SmartChartScanner.ChartPattern.DEAD_CAT_BOUNCE,
        SmartChartScanner.ChartPattern.BREAKDOWN,
        SmartChartScanner.ChartPattern.DESCENDING_TRIANGLE,
        SmartChartScanner.ChartPattern.RISING_WEDGE,
        SmartChartScanner.ChartPattern.HEAD_SHOULDERS,
    )

    // Bullish patterns that should BOOST size — the chart is confirming
    // the intake signal, buy bigger.
    private val CHART_TAILWIND_PATTERNS = setOf(
        SmartChartScanner.ChartPattern.BREAKOUT,
        SmartChartScanner.ChartPattern.CUP_HANDLE,
        SmartChartScanner.ChartPattern.DOUBLE_BOTTOM,
        SmartChartScanner.ChartPattern.INVERSE_HEAD_SHOULDERS,
        SmartChartScanner.ChartPattern.ASCENDING_TRIANGLE,
        SmartChartScanner.ChartPattern.BULLISH_FLAG,
    )

    // ── State ────────────────────────────────────────────────────────────
    data class ChartVerdict(
        val sizeMult: Double,
        val bias: String,
        val confidence: Double,
        val hardVeto: Boolean,
        val reason: String,
    )

    private data class Cached(val verdict: ChartVerdict, val atMs: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Consult the chart brain about this token. Returns a verdict that
     * FDG can apply as another sizing shape. Hot-path safe: cached 30s,
     * scanner call runs off the main scan cycle so we don't double-invoke.
     */
    fun consult(ts: TokenState): ChartVerdict {
        val nowMs = System.currentTimeMillis()
        try {
            val hit = cache[ts.mint]
            if (hit != null && nowMs - hit.atMs < CACHE_TTL_MS) return hit.verdict

            val scan = try {
                SmartChartScanner.quickScan(ts, SmartChartScanner.Timeframe.M5)
            } catch (_: Throwable) { null }
            if (scan == null) {
                // Not enough candle data yet — no shape.
                val v = ChartVerdict(1.0, "NEUTRAL", 0.0, false, "no_candles_yet")
                cache[ts.mint] = Cached(v, nowMs)
                return v
            }

            val hardBearish = scan.chartPatterns.any { it in HARD_BEARISH_PATTERNS }
            val chartTailwind = scan.chartPatterns.any { it in CHART_TAILWIND_PATTERNS }

            val verdict = when {
                hardBearish -> ChartVerdict(
                    sizeMult = 0.0,
                    bias = "BEARISH_HARD_PATTERN",
                    confidence = scan.confidence,
                    hardVeto = true,
                    reason = "hard_bearish_pattern=${scan.chartPatterns.joinToString(",") { it.name }}",
                )
                scan.overallBias == "BEARISH" && scan.confidence >= BEARISH_HARD_VETO_CONF -> ChartVerdict(
                    sizeMult = 0.0,
                    bias = "BEARISH_HIGH_CONF",
                    confidence = scan.confidence,
                    hardVeto = true,
                    reason = "chart_bearish_conf=${scan.confidence.toInt()}",
                )
                scan.overallBias == "BEARISH" && scan.confidence >= BEARISH_SIZE_DAMP_CONF -> ChartVerdict(
                    sizeMult = 0.5,
                    bias = "BEARISH_MODERATE",
                    confidence = scan.confidence,
                    hardVeto = false,
                    reason = "chart_bearish_moderate_conf=${scan.confidence.toInt()}",
                )
                scan.overallBias == "BULLISH" && scan.confidence >= BULLISH_BOOST_CONF && chartTailwind -> ChartVerdict(
                    sizeMult = 1.3,
                    bias = "BULLISH_TAILWIND",
                    confidence = scan.confidence,
                    hardVeto = false,
                    reason = "chart_bullish_tailwind=${scan.chartPatterns.joinToString(",") { it.name }}",
                )
                else -> ChartVerdict(1.0, scan.overallBias, scan.confidence, false, "chart_neutral")
            }

            cache[ts.mint] = Cached(verdict, nowMs)

            if (verdict.hardVeto) {
                try {
                    ForensicLogger.lifecycle(
                        "CHART_PRE_BUY_VETO_6120h",
                        "mint=${ts.mint.take(10)} sym=${ts.symbol} bias=${verdict.bias} " +
                        "conf=${verdict.confidence.toInt()} patterns=${scan.chartPatterns.joinToString(",") { it.name }}",
                    )
                    PipelineHealthCollector.labelInc("CHART_PRE_BUY_VETO_6120h")
                } catch (_: Throwable) {}
            } else if (verdict.sizeMult >= 1.2) {
                try { PipelineHealthCollector.labelInc("CHART_PRE_BUY_BOOST_6120h") } catch (_: Throwable) {}
            } else if (verdict.sizeMult < 1.0) {
                try { PipelineHealthCollector.labelInc("CHART_PRE_BUY_DAMP_6120h") } catch (_: Throwable) {}
            }

            return verdict
        } catch (_: Throwable) {
            return ChartVerdict(1.0, "ERROR", 0.0, false, "consult_error")
        }
    }
}
