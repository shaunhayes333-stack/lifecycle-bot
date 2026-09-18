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

    // V5.9.1332 — ANR STRUCTURAL FIX. LaneStrategyEvaluator.evaluateAll() replays
    // EVERY closed trade under multiple exit shapes — an O(trades × profiles) sweep
    // that was running on the MAIN thread inside renderAll() every 4s. The forensic
    // ANR trace flagged LaneStrategyEvaluator.evaluateAll + TuningActivity.addText as
    // top blocking sites. Pre-compute the replay OFF-main and cache it; renderAll just
    // reads the cached result. Recompute at most every 12s (the backtest over history
    // barely moves in 4s). Not a render throttle — the UI still repaints every 4s; we
    // only move the heavy compute off the main thread (operator doctrine).
    @Volatile private var cachedLaneReplay: Map<String, List<com.lifecyclebot.engine.LaneStrategyEvaluator.LaneResult>>? = null
    @Volatile private var cachedLaneReplayAtMs: Long = 0L
    private val laneReplayInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun refreshLaneReplayAsync() {
        val now = System.currentTimeMillis()
        if (now - cachedLaneReplayAtMs < 12_000L && cachedLaneReplay != null) return
        if (!laneReplayInFlight.compareAndSet(false, true)) return
        // Plain daemon thread — no coroutine deps in this Activity. Fire-and-forget;
        // result is read by renderAll on the next main-thread refresh tick.
        Thread {
            val grouped = try {
                val best = com.lifecyclebot.engine.LaneStrategyEvaluator.bestPerLane()
                if (best.isEmpty()) emptyMap()
                else com.lifecyclebot.engine.LaneStrategyEvaluator.evaluateAll().groupBy { it.lane }
            } catch (_: Throwable) { cachedLaneReplay ?: emptyMap() }
            cachedLaneReplay = grouped
            cachedLaneReplayAtMs = System.currentTimeMillis()
            laneReplayInFlight.set(false)
        }.apply { isDaemon = true; name = "lane-replay-bg" }.start()
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (refreshing) {
                refreshLaneReplayAsync()   // kick off-main compute; renderAll reads cache
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
            setBackgroundResource(com.lifecyclebot.R.drawable.aate_screen_bg)
        }
        rootColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // V5.0.7013 — no horizontal padding here any more. Section rules and
            // cards carry their own 16dp gutter so they share one edge; padding
            // the column too would inset the cards twice and break that line.
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad * 4)
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
        currentCard = null

        addHeader("Tuning Console", "READ ONLY", AateUi.PURPLE)
        addText(
            "Signals the brains already compute. Lane Strategy Replay feeds a bounded " +
                "LaneExitTuner TP/SL bias — this screen shows what the bot decided, it does " +
                "not apply anything.",
            AateUi.TEXT_MUTED, small = true,
        )

        // ── 1. PER-LANE EXPECTANCY ─────────────────────────────────────
        addHeader("Per-Lane Expectancy", "n ≥ 5", AateUi.CYAN)
        try {
            val rawBoard = com.lifecyclebot.engine.StrategyTelemetry.computeLeaderboard()
                .sortedByDescending { it.meanPnlPct }
            val board = rawBoard.filter { it.isStatisticallyMeaningful }
            val displayBoard = if (board.isEmpty()) rawBoard.take(12) else board
            if (displayBoard.isEmpty()) {
                addText("(no settled lane/trader rows yet)", AateUi.TEXT_MUTED)
            } else {
                if (board.isEmpty()) {
                    addText(
                        "Warming — below the statistical threshold, but these lanes are contributing.",
                        AateUi.AMBER, small = true,
                    )
                }
                // The session-level shape first, so the per-lane rows have
                // something to be read against.
                val netAll = displayBoard.sumOf { it.totalSolPnl }
                val nAll = displayBoard.sumOf { it.trades }
                val winners = displayBoard.count { it.meanPnlPct > 1.0 }
                addStats(
                    listOf(
                        Triple("LANES", displayBoard.size.toString(), AateUi.TEXT),
                        Triple("PROFITABLE", winners.toString(), if (winners > 0) AateUi.GREEN else AateUi.RED),
                        Triple("TRADES", nAll.toString(), AateUi.TEXT),
                        Triple("NET ◎", "%+.2f".format(netAll), AateUi.signed(netAll)),
                    ),
                )
                for (m in displayBoard) {
                    // Profitable mean = green, bleeding = red, flat = amber.
                    val color = when {
                        m.meanPnlPct > 1.0 -> AateUi.GREEN
                        m.meanPnlPct < -1.0 -> AateUi.RED
                        else -> AateUi.AMBER
                    }
                    val warmTag = if (!m.isStatisticallyMeaningful) " · warm" else ""
                    // V5.0.7013 — the old single mono run carried five figures
                    // at one weight, so nothing in it could be found at a
                    // glance. Net P&L is what the operator is looking for, so it
                    // becomes the row's figure; the rest drops to the sub-line,
                    // and the win rate gets the rail it always deserved.
                    addMetric(
                        label = m.strategy,
                        value = "%+.3f◎".format(m.totalSolPnl),
                        color = AateUi.signed(m.totalSolPnl),
                        sub = "μ ${"%+.1f".format(m.meanPnlPct)}%  ·  n=${m.trades}  ·  " +
                            "W${m.wins}/L${m.losses}/s${m.scratches}$warmTag",
                    )
                    addBar("WIN RATE", m.winRatePct, color)
                }
            }
        } catch (t: Throwable) {
            addText("(leaderboard unavailable: ${t.message?.take(60)})", AateUi.TEXT_MUTED)
        }

        // ── 2. SCORE-BAND CALIBRATION ──────────────────────────────────
        addHeader("Score-Band Calibration", "PREDICTIVE?", AateUi.CYAN)
        addText(
            "Higher bands SHOULD show higher mean PnL. If they don't, the scorer isn't predictive.",
            AateUi.TEXT_MUTED, small = true,
        )
        try {
            val snap = com.lifecyclebot.engine.ScoreExpectancyTracker.snapshot()
            // V5.0.7021 §YOU_CANNOT_SEE_A_CORRELATION_IN_A_LIST.
            //
            // This section's own text says it: "Higher bands SHOULD show higher
            // mean PnL. If they don't, the scorer isn't predictive." That is a
            // question about SLOPE, and it was answered with forty rows of
            // BLUECHIP[70-79]n=22/mu=+103.8%. Nobody can hold forty points in
            // their head and estimate a trend; that is what a scatter is for.
            //
            // The tracker's own line is parsed rather than re-derived, so the
            // plot and the rows beneath it are the same numbers by construction.
            addScatterFromSnapshot7021(snap)
            renderTokenizedSnapshot(snap)
        } catch (t: Throwable) {
            addText("(score expectancy unavailable)", AateUi.TEXT_MUTED)
        }

        // ── 3. EXIT-REASON P&L ─────────────────────────────────────────
        addHeader("Exit-Reason P&L", "CAPTURED vs LEAKED", AateUi.AMBER)
        addText(
            "Where money is captured vs leaked. Negative TP/positive STOP = exits mis-tuned.",
            AateUi.TEXT_MUTED, small = true,
        )
        try {
            val snap = com.lifecyclebot.engine.ExitReasonTracker.snapshot()
            renderTokenizedSnapshot(snap)
        } catch (t: Throwable) {
            addText("(exit-reason tracker unavailable)", AateUi.TEXT_MUTED)
        }

        // ── 4. DANGER BUCKETS ──────────────────────────────────────────
        addHeader("Danger Buckets", "MODE × SCORE BAND", AateUi.RED)
        try {
            val dump = com.lifecyclebot.engine.LosingPatternMemory.formatForPipelineDump()
            if (dump.isBlank()) {
                addText("(no danger buckets — learning still warming up)", AateUi.TEXT_MUTED)
            } else {
                // Strip the section header line; render the rest mono-ish.
                dump.lines().forEach { raw ->
                    val l = raw.trimEnd()
                    if (l.isBlank() || l.startsWith("=====")) return@forEach
                    val color = when {
                        l.contains("✅") -> AateUi.GREEN
                        l.contains("losses=") -> AateUi.RED
                        else -> AateUi.TEXT_SECONDARY
                    }
                    addText(l, color, small = true)
                }
            }
        } catch (t: Throwable) {
            addText("(losing-pattern memory unavailable)", AateUi.TEXT_MUTED)
        }
        // ── 5. MFE CAPTURE RATIO ───────────────────────────────────────
        addHeader("MFE Capture Ratio", "REALIZED ÷ PEAK", AateUi.GREEN)
        addText(
            "How much of each lane's peak gain it actually banks. <40% = exiting too late " +
                "(round-tripping winners); near 100% = exits well-timed. Only counts closed " +
                "outcomes that carried a recorded peak.",
            AateUi.TEXT_MUTED, small = true,
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
                addText("(no closed outcomes with a recorded peak yet)", AateUi.TEXT_MUTED)
            } else {
                byLane.entries.sortedByDescending { it.value.n }.forEach { (lane, a) ->
                    val ratio = if (a.peakSum > 0.0) (a.realizedSum / a.peakSum) * 100.0 else 0.0
                    val color = when {
                        ratio >= 70.0 -> AateUi.GREEN
                        ratio >= 40.0 -> AateUi.AMBER
                        else -> AateUi.RED
                    }
                    // Capture ratio is a proportion, so it gets a rail. The old
                    // line printed it as text inside a parenthesised run with
                    // two other percentages, where the one number that says
                    // whether exits are working was the hardest to find.
                    addMetric(
                        label = lane,
                        value = "${"%.0f".format(ratio)}%",
                        color = color,
                        sub = "peak ${"%+.0f".format(a.peakSum / a.n)}%  ·  " +
                            "banked ${"%+.0f".format(a.realizedSum / a.n)}%  ·  n=${a.n}",
                    )
                    addBar("CAPTURED", ratio.coerceIn(0.0, 100.0), color)
                }
            }
        } catch (t: Throwable) {
            addText("(MFE data unavailable: ${t.message?.take(60)})", AateUi.TEXT_MUTED)
        }

        // ── 6. LANE STRATEGY REPLAY (V5.9.1285) ────────────────────────
        addHeader("Lane Strategy Replay", "HONEST BACKTEST", AateUi.PURPLE)
        addText(
            "Replays each lane's REAL trades under candidate exit shapes using the " +
                "actual peak/drawdown each trade hit — no fabricated upside. If a lane's " +
                "best shape can't beat NO_TRADE, the data says it should stop trading.",
            AateUi.TEXT_MUTED, small = true,
        )
        try {
            // V5.9.1332 — read the OFF-MAIN cached replay (refreshLaneReplayAsync),
            // never run the O(trades×profiles) backtest on the main thread here.
            val byLane = cachedLaneReplay
            if (byLane == null) {
                addText("(computing lane replay… refresh in a moment)", AateUi.TEXT_MUTED)
            } else if (byLane.isEmpty()) {
                addText("(not enough closed outcomes with peak data yet)", AateUi.TEXT_MUTED)
            } else {
                for ((lane, rs) in byLane) {
                    val b = rs.maxByOrNull { it.netSol }!!
                    val noTrade = rs.find { it.profile == "NO_TRADE" }
                    val verdict = when {
                        b.profile == "NO_TRADE" -> "⛔ STOP TRADING"
                        noTrade != null && b.netSol <= noTrade.netSol -> "⛔ STOP TRADING"
                        b.profile == "CURRENT_ACTUAL" -> "✅ KEEP CURRENT"
                        else -> "🔧 SWITCH → ${b.profile}"
                    }
                    val vColor = when {
                        verdict.startsWith("⛔") -> AateUi.RED
                        verdict.startsWith("🔧") -> AateUi.AMBER
                        else -> AateUi.GREEN
                    }
                    addMetric(
                        label = lane,
                        value = verdict.replace(Regex("^[^A-Za-z]*"), "").trim(),
                        color = vColor,
                        sub = "best ${b.profile}  ·  ${"%+.3f".format(b.netSol)}◎",
                    )
                    rs.sortedByDescending { it.netSol }.forEach { r ->
                        addText("   ${r.oneLine()}", AateUi.signed(r.netSol), small = true)
                    }
                    target().addView(AateUi.divider(this))
                }
            }
        } catch (t: Throwable) {
            addText("(lane replay unavailable: ${t.message?.take(80)})", AateUi.TEXT_MUTED)
        }
    }

    /**
     * The trackers return a single space-joined line of "LANE[lo-hi]n=N/μ=±X%"
     * tokens. Split them per-token, one row each, coloured by mean sign.
     */
    private fun renderTokenizedSnapshot(snapshot: String) {
        if (snapshot.isBlank() || snapshot == "no samples yet") {
            addText("(no samples yet)", AateUi.TEXT_MUTED)
            return
        }
        // Tokens are space-separated but lane labels have no internal spaces.
        val tokens = snapshot.split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            addText(snapshot, AateUi.TEXT_SECONDARY, small = true)
            return
        }
        for (tok in tokens) {
            val color = when {
                Regex("μ=\\+").containsMatchIn(tok) -> AateUi.GREEN
                Regex("μ=-").containsMatchIn(tok) -> AateUi.RED
                else -> AateUi.TEXT_SECONDARY
            }
            addText(tok, color, small = true)
        }
    }

    // ── render kit ─────────────────────────────────────────────────────
    //
    // V5.0.7013 §THE_COMPONENT_KIT_NOTHING_CALLED.
    //
    // AateComponents6994 was written from the operator's renders — glow cards,
    // section rules, bimodal type, bar rows, stat strips — and then referenced
    // by exactly zero files. AateUi, its token half, reached three files out of
    // thirty-three. So the V5.0.6998-7012 restyle landed on the XML shell and
    // stopped at the boundary of every screen that paints itself in Kotlin,
    // which is most of them. This screen was the clearest case: six sections of
    // flat monospace lines in a single ungrouped column, exactly as it looked
    // before the restyle, because not one of its pixels came from a layout file.
    //
    // The conversion is deliberately made at the HELPERS rather than at the
    // ~40 call sites. addHeader now opens a card and draws the render's rule;
    // addText lands inside whichever card is open. Every existing call keeps
    // working and the whole screen changes shape. addKv — the "compose five
    // figures into one mono line" helper that WAS the old look — is gone, and
    // its call sites became metric rows with rails.

    /** The card currently accepting rows, or null before the first header. */
    private var currentCard: LinearLayout? = null

    private fun target(): LinearLayout = currentCard ?: rootColumn

    private fun addHeader(text: String, trailing: String?, accent: Int) {
        // Strip the leading emoji + number the old headings carried; the rule
        // itself now does the separating, so "📊 1. Per-Lane Expectancy" becomes
        // "PER-LANE EXPECTANCY" with the qualifier moved to the trailing slot.
        val label = text.replace(Regex("^[^A-Za-z]*"), "").trim()
        rootColumn.addView(AateComponents6994.sectionHeader(this, label, trailing, accent))
        currentCard = AateComponents6994.card(this, accent).also { rootColumn.addView(it) }
    }

    /** A headline figure + caption strip, for a section that has one. */
    private fun addStats(items: List<Triple<String, String, Int>>) {
        if (items.isEmpty()) return
        target().addView(AateComponents6994.statStrip(this, items))
        target().addView(AateUi.divider(this))
    }

    /** A named row with its figure on the right — the render's line shape. */
    private fun addMetric(label: String, value: String, color: Int, sub: String? = null) {
        target().addView(AateComponents6994.metricRow(this, label, value, color, sub))
    }

    /** A labelled 0-100 rail. Used where the old screen printed a bare percent. */
    private fun addBar(label: String, value: Double, accent: Int) {
        target().addView(AateComponents6994.barRow(this, label, value, accent, labelWidthDp = 92))
    }

    private fun addText(s: String, color: Int = AateUi.TEXT_SECONDARY, small: Boolean = false) {
        target().addView(TextView(this).apply {
            text = s
            setTextColor(color)
            textSize = if (small) 11f else 12.5f
            if (small) {
                setLineSpacing(0f, 1.25f)
            }
            val pad = (3 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
        })
    }

    /**
     * V5.0.7021 — plot the calibration the section asks about.
     *
     * Tokens look like `LANE[lo-hi]n=N/mu=+X%`. X is the band midpoint, Y the
     * mean return, dot area the sample count — so a band with n=1 cannot shout
     * down a band with n=22, which reading the list flat encourages.
     *
     * A rising trend means the scorer is predictive. A flat or falling one is
     * the finding, and now it is visible at a glance instead of derivable.
     */
    private fun addScatterFromSnapshot7021(snapshot: String) {
        if (snapshot.isBlank() || snapshot == "no samples yet") return
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val ws = ArrayList<Float>()
        // NOTE: this is a Kotlin RAW string, which does not process \uXXXX
        // escapes — so the mu is written as the character itself. Spelling it
        // \u03bc here would have searched for a literal backslash-u-0-3-b-c and
        // matched nothing, silently, on every refresh.
        val re = Regex("""\[(\d+)\s*-\s*(\d+)\][^\s]*?n=(\d+)[^\s]*?μ=([+-]?[0-9.]+)""")
        for (m in re.findAll(snapshot)) {
            val lo = m.groupValues[1].toFloatOrNull() ?: continue
            val hi = m.groupValues[2].toFloatOrNull() ?: continue
            val n = m.groupValues[3].toFloatOrNull() ?: continue
            val mu = m.groupValues[4].toFloatOrNull() ?: continue
            if (!mu.isFinite()) continue
            xs.add((lo + hi) / 2f)
            ys.add(mu)
            ws.add(n)
        }
        // Two points define a line through themselves and prove nothing; below
        // four bands the plot would imply a confidence the data has not earned.
        if (xs.size < 4) return

        val host = target()
        host.addView(
            ScatterView7021(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (104 * resources.displayMetrics.density).toInt(),
                ).apply { topMargin = (6 * resources.displayMetrics.density).toInt() }
                setPoints(xs.toFloatArray(), ys.toFloatArray(), ws.toFloatArray())
            },
        )
        host.addView(
            android.widget.TextView(this).apply {
                text = "SCORE BAND →   ·   dot = sample size   ·   line = fitted trend"
                textSize = 9.5f
                setTextColor(AateUi.TEXT_MUTED)
                letterSpacing = 0.06f
                setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
            },
        )
    }

}
