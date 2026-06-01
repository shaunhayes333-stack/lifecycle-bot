/*
 * V5.9.1239 — AATE Tuning Console.
 *
 * Surfaces the decision-quality signals the bot ALREADY computes but never
 * exposed in any UI tab. These are the levers for tuning win-rate, profit,
 * and per-lane behaviour by hand:
 *
 *   1. PER-LANE EXPECTANCY   — StrategyTelemetry leaderboard (winners/bleeders).
 *                              Which lanes make money, which bleed.
 *   2. SCORE-BAND CALIBRATION— ScoreExpectancyTracker. Is the scorer predictive?
 *                              (Higher score bands should show higher mean PnL.)
 *   3. EXIT-REASON P&L       — ExitReasonTracker. Where is money captured vs leaked
 *                              (TP ladder vs stop-loss vs time exits).
 *   4. DANGER BUCKETS        — LosingPatternMemory. TradingMode × ScoreBand combos
 *                              that have crossed the loss-rate danger threshold.
 *
 * Pure programmatic UI, read-only, auto-refresh every 4s. No tuning is applied
 * here — this is a read surface so the operator can SEE what to change.
 * No trading / scanner / FDG / execution code is touched.
 */
package com.lifecyclebot.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class TuningActivity : Activity() {

    private lateinit var rootScroll: ScrollView
    private lateinit var rootColumn: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (refreshing) {
                renderAll()
                handler.postDelayed(this, 4_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "🎚 AATE Tuning Console"
        rootScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        rootColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        rootScroll.addView(rootColumn)
        setContentView(rootScroll)
    }

    override fun onResume() {
        super.onResume()
        refreshing = true
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshing = false
        handler.removeCallbacks(refreshRunnable)
    }

    private fun renderAll() {
        rootColumn.removeAllViews()

        addText(
            "Read-only tuning signals (already computed by the brains). " +
                "Use these to decide what to change — nothing is auto-applied here.",
            Color.GRAY, small = true,
        )

        // ── 1. PER-LANE EXPECTANCY ─────────────────────────────────────
        addHeader("📊 1. Per-Lane Expectancy (≥5 trades)")
        try {
            val board = com.lifecyclebot.engine.StrategyTelemetry.computeLeaderboard()
                .filter { it.isStatisticallyMeaningful }
                .sortedByDescending { it.meanPnlPct }
            if (board.isEmpty()) {
                addText("(no lane has ≥5 settled trades yet)", Color.GRAY)
            } else {
                for (m in board) {
                    // Profitable mean = green, bleeding = red, flat = amber.
                    val color = when {
                        m.meanPnlPct > 1.0 -> "#10B981"
                        m.meanPnlPct < -1.0 -> "#EF4444"
                        else -> "#F59E0B"
                    }
                    val line = "${m.strategy}: WR=${"%.0f".format(m.winRatePct)}% " +
                        "μ=${"%+.1f".format(m.meanPnlPct)}% " +
                        "net=${"%+.3f".format(m.totalSolPnl)}◎ " +
                        "(n=${m.trades} W${m.wins}/L${m.losses}/s${m.scratches})"
                    addKv(line, color)
                }
            }
        } catch (t: Throwable) {
            addText("(leaderboard unavailable: ${t.message?.take(60)})", Color.GRAY)
        }

        // ── 2. SCORE-BAND CALIBRATION ──────────────────────────────────
        addHeader("🎯 2. Score-Band Calibration")
        addText(
            "Higher bands SHOULD show higher mean PnL. If they don't, the scorer isn't predictive.",
            Color.GRAY, small = true,
        )
        try {
            val snap = com.lifecyclebot.engine.ScoreExpectancyTracker.snapshot()
            renderTokenizedSnapshot(snap)
        } catch (t: Throwable) {
            addText("(score expectancy unavailable)", Color.GRAY)
        }

        // ── 3. EXIT-REASON P&L ─────────────────────────────────────────
        addHeader("🚪 3. Exit-Reason P&L")
        addText(
            "Where money is captured vs leaked. Negative TP/positive STOP = exits mis-tuned.",
            Color.GRAY, small = true,
        )
        try {
            val snap = com.lifecyclebot.engine.ExitReasonTracker.snapshot()
            renderTokenizedSnapshot(snap)
        } catch (t: Throwable) {
            addText("(exit-reason tracker unavailable)", Color.GRAY)
        }

        // ── 4. DANGER BUCKETS ──────────────────────────────────────────
        addHeader("☠️ 4. Danger Buckets (TradingMode × ScoreBand)")
        try {
            val dump = com.lifecyclebot.engine.LosingPatternMemory.formatForPipelineDump()
            if (dump.isBlank()) {
                addText("(no danger buckets — learning still warming up)", Color.GRAY)
            } else {
                // Strip the section header line; render the rest mono-ish.
                dump.lines().forEach { raw ->
                    val l = raw.trimEnd()
                    if (l.isBlank() || l.startsWith("=====")) return@forEach
                    val color = when {
                        l.contains("✅") -> "#10B981"
                        l.contains("losses=") -> "#EF4444"
                        else -> Color.LTGRAY.let { "#D1D5DB" }
                    }
                    addText(l, Color.parseColor(color), small = true)
                }
            }
        } catch (t: Throwable) {
            addText("(losing-pattern memory unavailable)", Color.GRAY)
        }
        // ── 5. MFE CAPTURE RATIO ───────────────────────────────────────
        addHeader("📈 5. MFE Capture Ratio (realized ÷ peak)")
        addText(
            "How much of each lane's peak gain it actually banks. <40% = exiting too late " +
                "(round-tripping winners); near 100% = exits well-timed. Only counts closed " +
                "outcomes that carried a recorded peak.",
            Color.GRAY, small = true,
        )
        try {
            val outcomes = com.lifecyclebot.engine.CanonicalOutcomeBus.recentSnapshot()
            // Group by lane (mode); only winners with a positive recorded peak are
            // meaningful for capture ratio (losers never had a peak to capture).
            data class Acc(var realizedSum: Double = 0.0, var peakSum: Double = 0.0, var n: Int = 0)
            val byLane = HashMap<String, Acc>()
            for (o in outcomes) {
                val peak = o.maxGainPct ?: continue          // skip null-peak (legacy bridge) rows
                if (peak <= 0.0) continue                     // no upside to capture
                val realized = o.realizedPnlPct ?: continue
                val lane = o.mode.name
                val a = byLane.getOrPut(lane) { Acc() }
                a.realizedSum += realized
                a.peakSum += peak
                a.n += 1
            }
            if (byLane.isEmpty()) {
                addText("(no closed outcomes with a recorded peak yet)", Color.GRAY)
            } else {
                byLane.entries.sortedByDescending { it.value.n }.forEach { (lane, a) ->
                    val ratio = if (a.peakSum > 0.0) (a.realizedSum / a.peakSum) * 100.0 else 0.0
                    val color = when {
                        ratio >= 70.0 -> "#10B981"
                        ratio >= 40.0 -> "#F59E0B"
                        else -> "#EF4444"
                    }
                    addKv(
                        "$lane: capture=${"%.0f".format(ratio)}% " +
                            "(avgPeak=${"%+.0f".format(a.peakSum / a.n)}% " +
                            "avgRealized=${"%+.0f".format(a.realizedSum / a.n)}% n=${a.n})",
                        color,
                    )
                }
            }
        } catch (t: Throwable) {
            addText("(MFE data unavailable: ${t.message?.take(60)})", Color.GRAY)
        }

        // ── 6. LANE STRATEGY REPLAY (V5.9.1285) ────────────────────────
        addHeader("🧪 6. Lane Strategy Replay (honest backtest)")
        addText(
            "Replays each lane's REAL trades under candidate exit shapes using the " +
                "actual peak/drawdown each trade hit — no fabricated upside. If a lane's " +
                "best shape can't beat NO_TRADE, the data says it should stop trading.",
            Color.GRAY, small = true,
        )
        try {
            val best = com.lifecyclebot.engine.LaneStrategyEvaluator.bestPerLane()
            if (best.isEmpty()) {
                addText("(not enough closed outcomes with peak data yet)", Color.GRAY)
            } else {
                val all = com.lifecyclebot.engine.LaneStrategyEvaluator.evaluateAll()
                val byLane = all.groupBy { it.lane }
                for ((lane, rs) in byLane) {
                    val b = rs.maxByOrNull { it.netSol }!!
                    val noTrade = rs.find { it.profile == "NO_TRADE" }
                    val verdict = when {
                        b.profile == "NO_TRADE" -> "⛔ STOP TRADING"
                        noTrade != null && b.netSol <= noTrade.netSol -> "⛔ STOP TRADING"
                        b.profile == "CURRENT_ACTUAL" -> "✅ KEEP CURRENT"
                        else -> "🔧 SWITCH → ${b.profile}"
                    }
                    val vColor = if (verdict.startsWith("⛔")) "#EF4444"
                        else if (verdict.startsWith("🔧")) "#F59E0B" else "#10B981"
                    addKv("$lane — $verdict", vColor)
                    rs.sortedByDescending { it.netSol }.forEach { r ->
                        val rc = if (r.netSol > 0) "#10B981" else if (r.netSol < 0) "#EF4444" else "#9CA3AF"
                        addText("   ${r.oneLine()}", Color.parseColor(rc), small = true)
                    }
                }
            }
        } catch (t: Throwable) {
            addText("(lane replay unavailable: ${t.message?.take(80)})", Color.GRAY)
        }
    }

    /**
     * The trackers return a single space-joined line of "LANE[lo-hi]n=N/μ=±X%"
     * tokens. Split them per-token, one row each, coloured by mean sign.
     */
    private fun renderTokenizedSnapshot(snapshot: String) {
        if (snapshot.isBlank() || snapshot == "no samples yet") {
            addText("(no samples yet)", Color.GRAY)
            return
        }
        // Tokens are space-separated but lane labels have no internal spaces.
        val tokens = snapshot.split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            addText(snapshot, Color.LTGRAY, small = true)
            return
        }
        for (tok in tokens) {
            val color = when {
                Regex("μ=\\+").containsMatchIn(tok) -> "#10B981"
                Regex("μ=-").containsMatchIn(tok) -> "#EF4444"
                else -> "#D1D5DB"
            }
            addText(tok, Color.parseColor(color), small = true)
        }
    }

    // ── helpers (match house style from UniverseHealthActivity) ────────
    private fun addHeader(text: String) {
        rootColumn.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, pad * 2, 0, pad)
        })
    }

    private fun addKv(text: String, hex: String) {
        rootColumn.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(hex))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            val pad = (3 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
        })
    }

    private fun addText(s: String, color: Int = Color.LTGRAY, small: Boolean = false) {
        rootColumn.addView(TextView(this).apply {
            text = s
            setTextColor(color)
            textSize = if (small) 11f else 13f
            if (small) typeface = Typeface.MONOSPACE
            val pad = (2 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
        })
    }
}
