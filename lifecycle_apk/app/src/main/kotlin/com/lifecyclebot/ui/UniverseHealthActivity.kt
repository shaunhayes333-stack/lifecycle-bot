/*
 * V5.9.790 — AATE Universe Health Screen (operator audit Critical Fix 9).
 *
 * Single-glance "ground truth" panel covering the six pillars the operator
 * called out in the post-V5.9.788 audit:
 *
 *   1. RUNTIME       — paper/live, build tag, battery whitelist
 *   2. SCORING       — UnifiedScorer mode (CLASSIC vs MODERN), LLM status
 *   3. LEARNING      — canonical counters: total / rich / incomplete /
 *                      strategy-trainable / execution-only / rejected.
 *                      Plus per-layer readiness summary.
 *   4. EXECUTION     — sell-job registry size, reconciler ticks, executed
 *                      vs failed totals.
 *   5. AUTHORITY     — EnabledTraderAuthority snapshot + Sniper proof-off.
 *   6. WALLET        — HostWalletTokenTracker open/held + reconciler drift.
 *
 * Pure programmatic UI. Auto-refresh every 3s.
 */
package com.lifecyclebot.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class UniverseHealthActivity : Activity() {

    private lateinit var rootScroll: ScrollView
    private lateinit var rootColumn: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (refreshing) {
                renderAll()
                handler.postDelayed(this, 3_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "🛰 AATE Universe Health"
        rootScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundResource(com.lifecyclebot.R.drawable.aate_screen_bg)
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
        // V5.0.7023 — MUST reset with the column. removeAllViews detaches the
        // open card but leaves this field pointing at it, so every subsequent
        // add* would land in an orphan and the screen would render blank.
        currentCard7023 = null

        // V5.9.1240 — single Brain-hub nav. This screen is the 6-pillar
        // superset; the buttons jump to the deeper single-purpose views
        // (Pipeline funnel / Learning canonical / Tuning console) so the
        // operator has ONE entry tile instead of four scattered ones.
        renderNavBar()

        // ── 1. RUNTIME ────────────────────────────────────────────────
        addHeader("🚦 1. Runtime")
        val mode = try { com.lifecyclebot.engine.RuntimeModeAuthority.authority().name } catch (_: Throwable) { "?" }
        val modeColor = if (mode == "LIVE") "#FF4D6D" else "#4C8DFF"
        addKvHighlight("RuntimeModeAuthority.mode", mode, modeColor)
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Throwable) { "?" }
        addKv("APK versionName", versionName)
        val batteryWhitelisted: Boolean = try {
            val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        } catch (_: Throwable) { false }
        addKvHighlight(
            "Battery optimisation whitelisted",
            if (batteryWhitelisted) "YES" else "NO — Doze may suspend bot",
            if (batteryWhitelisted) "#16E6A1" else "#FFB020",
        )

        // ── 2. SCORING ────────────────────────────────────────────────
        addHeader("🏛 2. Scoring & Sentience")
        val scoringMode = try { com.lifecyclebot.v3.scoring.UnifiedScorer.modeLabel() } catch (_: Throwable) { "?" }
        // CLASSIC means the modern symbolic outer ring is bypassed — colour amber so the
        // operator never reads 'sentient symbolic mode active' from the UI when it isn't.
        val scoringColor = if (scoringMode.startsWith("CLASSIC")) "#FFB020" else "#16E6A1"
        addKvHighlight("UnifiedScorer.mode", scoringMode, scoringColor)
        val sentLabel = if (scoringMode.startsWith("CLASSIC"))
            "CLASSIC (full sentient OFF — outer symbolic ring bypassed)"
        else
            "MODERN (sentient symbolic outer ring active)"
        addKvHighlight("Effective sentience mode", sentLabel, scoringColor)
        val llmStatus = try { com.lifecyclebot.engine.SentienceHooks.llmStatus() } catch (_: Throwable) { "UNAVAILABLE" }
        val llmColor = when (llmStatus) {
            "READY" -> "#16E6A1"
            "DEGRADED" -> "#FFB020"
            else -> "#FF4D6D"
        }
        addKvHighlight("LLM_STATUS", llmStatus, llmColor)

        // ── 3. LEARNING ───────────────────────────────────────────────
        addHeader("🧠 3. Learning (canonical pipeline)")
        val snap: Map<String, Long> = try { com.lifecyclebot.engine.CanonicalLearningCounters.snapshot() } catch (_: Throwable) { emptyMap() }
        addKv("canonicalOutcomesTotal", (snap["canonicalOutcomesTotal"] ?: 0L).toString())
        val rich = snap["richFeatureOutcomes"] ?: 0L
        val incomplete = snap["incompleteFeatureOutcomes"] ?: 0L
        val richPct = if (rich + incomplete > 0L) "%.1f%%".format(100.0 * rich.toDouble() / (rich + incomplete).toDouble()) else "—"
        val richColor = if (rich > 0L) "#16E6A1" else "#FF4D6D"
        addKvHighlight("richFeatureOutcomes", "$rich  ($richPct of total)", richColor)
        addKv("incompleteFeatureOutcomes", incomplete.toString())
        addKv("strategyTrainableOutcomes", (snap["strategyTrainableOutcomes"] ?: 0L).toString())
        addKv("executionOnlyOutcomes", (snap["executionOnlyOutcomes"] ?: 0L).toString())
        // V5.9.793 — operator audit Item 5: BC-sim-only outcomes excluded from production WR.
        val bcSim = snap["bcSimOnlyOutcomes"] ?: 0L
        addKvHighlight(
            "bcSimOnlyOutcomes (excl. from WR)",
            bcSim.toString(),
            if (bcSim > 0L) "#FFB020" else "#63759B",
        )
        addKvHighlight("rejectedBadLabels", (snap["rejectedBadLabels"] ?: 0L).toString(), "#FF4D6D")
        // Per-layer summary — fold counts into the four buckets the operator audit named.
        val readiness: Map<String, com.lifecyclebot.engine.LayerReadiness> = try {
            com.lifecyclebot.engine.LayerReadinessRegistry.snapshot()
        } catch (_: Throwable) { emptyMap() }
        val buckets = mutableMapOf(
            "TRUSTED" to 0,
            "LIVE_ELIGIBLE" to 0,
            "PAPER_ELIGIBLE" to 0,
            "LEARNING_ONLY" to 0,
            "RECEIVING_SIGNALS" to 0,
            "DEGRADED_BAD_EV" to 0,
            "DEGRADED_FEATURE_STARVED" to 0,
            "DEGRADED_NO_ADAPTER" to 0,
            "DEGRADED_NO_VOTES" to 0,
            "DEGRADED" to 0,
            "DISCONNECTED" to 0,
        )
        for ((_, st) in readiness) {
            val k = st.name
            buckets[k] = (buckets[k] ?: 0) + 1
        }
        for ((bucket, count) in buckets) {
            if (count <= 0) continue
            val c = when (bucket) {
                "TRUSTED" -> "#16E6A1"
                "LIVE_ELIGIBLE" -> "#4C8DFF"
                "PAPER_ELIGIBLE" -> "#9A4DFF"
                "LEARNING_ONLY", "DEGRADED_FEATURE_STARVED" -> "#FFB020"
                "DEGRADED", "DEGRADED_BAD_EV" -> "#FF4D6D"
                else -> "#63759B"
            }
            addKvHighlight("Layers $bucket", count.toString(), c)
        }

        // ── 4. EXECUTION ──────────────────────────────────────────────
        addHeader("⚙️ 4. Execution")
        addKv("executedTradesTotal", (snap["executedTradesTotal"] ?: 0L).toString())
        addKvHighlight("failedExecutionsTotal", (snap["failedExecutionsTotal"] ?: 0L).toString(), "#FFB020")
        addKv("openTrades (in-flight)", (snap["openTrades"] ?: 0L).toString())
        addKv("recoveredTrades", (snap["recoveredTrades"] ?: 0L).toString())
        val sellJobs = try { com.lifecyclebot.engine.sell.SellJobRegistry.snapshot().size.toLong() } catch (_: Throwable) { -1L }
        addKv("SellJobRegistry size", sellJobs.toString())
        // V5.9.791 — operator audit Item 1 + 2 visibility: PositionExitArbiter counters.
        val arb: Map<String, Long> = try { com.lifecyclebot.engine.PositionArbiterCounters.snapshot() } catch (_: Throwable) { emptyMap() }
        addKvHighlight("arbiter terminalSells", (arb["terminalSells"] ?: 0L).toString(), "#16E6A1")
        val suppressed = arb["suppressedDuplicates"] ?: 0L
        addKvHighlight(
            "arbiter suppressedDuplicates",
            suppressed.toString(),
            if (suppressed > 0L) "#FFB020" else "#16E6A1",
        )
        addKv("arbiter partialSells", (arb["partialSells"] ?: 0L).toString())
        addKv("arbiter staleSlotEvictions", (arb["staleSlotEvictions"] ?: 0L).toString())
        // V5.9.793 — operator audit Item 6: cycle timing tracker.
        // Target: avg < 12s, max < 30s.
        try {
            val ct = com.lifecyclebot.engine.CycleTimingTracker.snapshot()
            addHeader("⏱ Scanner Cycle Timing")
            val avgColor = if (ct.avgMs <= ct.targetMs) "#16E6A1" else if (ct.avgMs <= ct.hardLimitMs) "#FFB020" else "#FF4D6D"
            addKvHighlight("avg (last ${ct.windowSize})", "${ct.avgMs}ms (target ≤${ct.targetMs}ms)", avgColor)
            val p95Color = if (ct.p95Ms <= ct.hardLimitMs) "#16E6A1" else "#FF4D6D"
            addKvHighlight("p95 (last ${ct.windowSize})", "${ct.p95Ms}ms", p95Color)
            val maxColor = if (ct.maxMs <= ct.hardLimitMs) "#16E6A1" else "#FF4D6D"
            addKvHighlight("max (last ${ct.windowSize})", "${ct.maxMs}ms (hard ≤${ct.hardLimitMs}ms)", maxColor)
            addKv("last cycle", "${ct.lastMs}ms")
            addKvHighlight("cycles over hard limit", ct.overHardLimitCycles.toString(),
                if (ct.overHardLimitCycles > 0L) "#FF4D6D" else "#16E6A1")
            addKv("cycles over target", ct.overTargetCycles.toString())
            addKv("total cycles", ct.totalCycles.toString())
        } catch (_: Throwable) {}
        // V5.9.794 — operator audit Item 6: PumpPortal active-candidate cap visibility.
        try {
            val cur = com.lifecyclebot.engine.GlobalTradeRegistry.pumpPortalConcurrentCount()
            val cap = com.lifecyclebot.engine.GlobalTradeRegistry.pumpPortalCapMax()
            val rej = com.lifecyclebot.engine.GlobalTradeRegistry.pumpPortalRejectionCount()
            val ratioColor = when {
                cur >= cap -> "#FF4D6D"
                cur >= cap * 0.8 -> "#FFB020"
                else -> "#16E6A1"
            }
            addKvHighlight("PumpPortal concurrent / cap", "$cur / $cap", ratioColor)
            addKvHighlight(
                "PumpPortal cap rejections",
                rej.toString(),
                if (rej > 0L) "#FFB020" else "#63759B",
            )
        } catch (_: Throwable) {}

        // ── 5. AUTHORITY ──────────────────────────────────────────────
        addHeader("🛡 5. Authority")
        val enabledSet = try { com.lifecyclebot.engine.EnabledTraderAuthority.snapshotStr() } catch (_: Throwable) { "?" }
        val enabledColor = if (enabledSet.isNotBlank()) "#16E6A1" else "#FFB020"
        addKvHighlight("EnabledTraderAuthority", enabledSet.ifBlank { "(empty — bot stopped?)" }, enabledColor)
        val sniperEnabled = try {
            com.lifecyclebot.engine.EnabledTraderAuthority.isEnabled(
                com.lifecyclebot.engine.EnabledTraderAuthority.Trader.PROJECT_SNIPER,
            )
        } catch (_: Throwable) { false }
        val sniperColor = if (sniperEnabled) "#FFB020" else "#16E6A1"
        addKvHighlight(
            "PROJECT_SNIPER enabled",
            if (sniperEnabled) "YES (live missions allowed)" else "NO (proven off — FATAL_AUTH_BREACH guard armed)",
            sniperColor,
        )

        // ── 6. WALLET ─────────────────────────────────────────────────
        addHeader("💰 6. Wallet")
        val hostOpen = try { com.lifecyclebot.engine.HostWalletTokenTracker.getOpenCount() } catch (_: Throwable) { -1 }
        val hostHeld = try { com.lifecyclebot.engine.HostWalletTokenTracker.getActuallyHeldCount() } catch (_: Throwable) { -1 }
        addKvHighlight("HostWalletTokenTracker.openCount", hostOpen.toString(), "#16E6A1")
        addKvHighlight("HostWalletTokenTracker.actuallyHeldCount", hostHeld.toString(), "#16E6A1")
        val reconcilerKnown: Int = try {
            com.lifecyclebot.engine.WalletReconciler::class.java.declaredFields
                .find { it.name == "knownMints" }?.let { f ->
                    f.isAccessible = true
                    (f.get(com.lifecyclebot.engine.WalletReconciler) as? Set<*>)?.size
                }
        } catch (_: Throwable) { null } ?: -1
        addKv("WalletReconciler.knownMints", reconcilerKnown.toString())
        val drift = if (hostOpen >= 0 && reconcilerKnown >= 0) hostOpen - reconcilerKnown else 0
        val driftColor = when {
            hostOpen < 0 || reconcilerKnown < 0 -> "#63759B"
            kotlin.math.abs(drift) <= 1 -> "#16E6A1"
            kotlin.math.abs(drift) <= 5 -> "#FFB020"
            else -> "#FF4D6D"
        }
        addKvHighlight("drift (host - reconciler)", drift.toString(), driftColor)

        // Footer note clarifying CLASSIC vs MODERN — operator audit Fix 4.
        addText(
            "ⓘ When scoring mode shows CLASSIC, the modern symbolic outer ring is bypassed. " +
                "The bot is NOT running 'full sentient symbolic trading' in that mode — it's the " +
                "20-layer build-1920 pipeline. Flip the toggle in Settings → Scoring Mode to switch.",
            AateUi.TEXT_SECONDARY,
            small = true,
        )
    }

    private fun renderNavBar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad * 2)
        }
        fun navBtn(label: String, hex: String, target: Class<*>) {
            bar.addView(Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                setTextColor(AateUi.TEXT)
                setBackgroundColor(Color.parseColor(hex))
                val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                val m = (3 * resources.displayMetrics.density).toInt()
                lp.setMargins(m, 0, m, 0)
                layoutParams = lp
                setOnClickListener {
                    try { startActivity(Intent(this@UniverseHealthActivity, target)) } catch (_: Throwable) {}
                }
            })
        }
        navBtn("🩺 Pipeline", "#16E6A1", PipelineHealthActivity::class.java)
        navBtn("🧠 Learning", "#4C8DFF", LearningCounterActivity::class.java)
        navBtn("🎚 Tuning", "#FFB020", TuningActivity::class.java)
        rootColumn.addView(bar)
    }

    // ── Helpers ───────────────────────────────────────────────────────
    // V5.0.7023 — same conversion V5.0.7013 made to TuningActivity, for the
    // same reason: this screen is painted entirely in Kotlin, so no edit to
    // res/values or res/drawable could ever reach it. Four restyle passes went
    // past it untouched.
    //
    // Done at the HELPERS, not the call sites: addHeader draws the render's
    // tracked rule and opens a card, and every add* lands inside whichever card
    // is open. Every existing call keeps working and the whole screen changes
    // shape at once.

    /** The card currently accepting rows, or null before the first header. */
    private var currentCard7023: LinearLayout? = null

    private fun target7023(): LinearLayout = currentCard7023 ?: rootColumn

    private fun addHeader(text: String) {
        val label = text.replace(Regex("^[^A-Za-z]*"), "").trim().ifBlank { text }
        rootColumn.addView(AateComponents6994.sectionHeader(this, label, null, AateUi.CYAN))
        currentCard7023 = AateComponents6994.card(this, AateUi.CYAN).also { rootColumn.addView(it) }
    }

    private fun addKv(label: String, value: String) {
        target7023().addView(AateComponents6994.metricRow(this, label, value, AateUi.TEXT))
    }

    private fun addKvHighlight(label: String, value: String, hex: String) {
        target7023().addView(
            AateComponents6994.metricRow(this, label, value, Color.parseColor(hex)),
        )
    }

    private fun addText(s: String, color: Int = AateUi.TEXT_SECONDARY, small: Boolean = false) {
        target7023().addView(TextView(this).apply {
            text = s
            setTextColor(color)
            textSize = if (small) 11f else 12.5f
            setLineSpacing(0f, 1.2f)
            val pad = (3 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
        })
    }

    private fun makeKvRow(label: String, value: String, valueColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (3 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
            addView(TextView(this@UniverseHealthActivity).apply {
                text = label
                setTextColor(AateUi.TEXT_SECONDARY)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(this@UniverseHealthActivity).apply {
                text = value
                setTextColor(valueColor)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
            })
        }
    }
}
