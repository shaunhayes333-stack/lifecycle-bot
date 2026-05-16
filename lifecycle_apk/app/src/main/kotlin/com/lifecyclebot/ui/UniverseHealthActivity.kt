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

        // ── 1. RUNTIME ────────────────────────────────────────────────
        addHeader("🚦 1. Runtime")
        val mode = try { com.lifecyclebot.engine.RuntimeModeAuthority.authority().name } catch (_: Throwable) { "?" }
        val modeColor = if (mode == "LIVE") "#EF4444" else "#3B82F6"
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
            if (batteryWhitelisted) "#10B981" else "#F59E0B",
        )

        // ── 2. SCORING ────────────────────────────────────────────────
        addHeader("🏛 2. Scoring & Sentience")
        val scoringMode = try { com.lifecyclebot.v3.scoring.UnifiedScorer.modeLabel() } catch (_: Throwable) { "?" }
        // CLASSIC means the modern symbolic outer ring is bypassed — colour amber so the
        // operator never reads 'sentient symbolic mode active' from the UI when it isn't.
        val scoringColor = if (scoringMode.startsWith("CLASSIC")) "#F59E0B" else "#10B981"
        addKvHighlight("UnifiedScorer.mode", scoringMode, scoringColor)
        val sentLabel = if (scoringMode.startsWith("CLASSIC"))
            "CLASSIC (full sentient OFF — outer symbolic ring bypassed)"
        else
            "MODERN (sentient symbolic outer ring active)"
        addKvHighlight("Effective sentience mode", sentLabel, scoringColor)
        val llmStatus = try { com.lifecyclebot.engine.SentienceHooks.llmStatus() } catch (_: Throwable) { "UNAVAILABLE" }
        val llmColor = when (llmStatus) {
            "READY" -> "#10B981"
            "DEGRADED" -> "#F59E0B"
            else -> "#EF4444"
        }
        addKvHighlight("LLM_STATUS", llmStatus, llmColor)

        // ── 3. LEARNING ───────────────────────────────────────────────
        addHeader("🧠 3. Learning (canonical pipeline)")
        val snap: Map<String, Long> = try { com.lifecyclebot.engine.CanonicalLearningCounters.snapshot() } catch (_: Throwable) { emptyMap() }
        addKv("canonicalOutcomesTotal", (snap["canonicalOutcomesTotal"] ?: 0L).toString())
        val rich = snap["richFeatureOutcomes"] ?: 0L
        val incomplete = snap["incompleteFeatureOutcomes"] ?: 0L
        val richPct = if (rich + incomplete > 0L) "%.1f%%".format(100.0 * rich.toDouble() / (rich + incomplete).toDouble()) else "—"
        val richColor = if (rich > 0L) "#10B981" else "#EF4444"
        addKvHighlight("richFeatureOutcomes", "$rich  ($richPct of total)", richColor)
        addKv("incompleteFeatureOutcomes", incomplete.toString())
        addKv("strategyTrainableOutcomes", (snap["strategyTrainableOutcomes"] ?: 0L).toString())
        addKv("executionOnlyOutcomes", (snap["executionOnlyOutcomes"] ?: 0L).toString())
        addKvHighlight("rejectedBadLabels", (snap["rejectedBadLabels"] ?: 0L).toString(), "#EF4444")
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
                "TRUSTED" -> "#10B981"
                "LIVE_ELIGIBLE" -> "#3B82F6"
                "PAPER_ELIGIBLE" -> "#8B5CF6"
                "LEARNING_ONLY", "DEGRADED_FEATURE_STARVED" -> "#F59E0B"
                "DEGRADED", "DEGRADED_BAD_EV" -> "#EF4444"
                else -> "#6B7280"
            }
            addKvHighlight("Layers $bucket", count.toString(), c)
        }

        // ── 4. EXECUTION ──────────────────────────────────────────────
        addHeader("⚙️ 4. Execution")
        addKv("executedTradesTotal", (snap["executedTradesTotal"] ?: 0L).toString())
        addKvHighlight("failedExecutionsTotal", (snap["failedExecutionsTotal"] ?: 0L).toString(), "#F59E0B")
        addKv("openTrades (in-flight)", (snap["openTrades"] ?: 0L).toString())
        addKv("recoveredTrades", (snap["recoveredTrades"] ?: 0L).toString())
        val sellJobs = try { com.lifecyclebot.engine.sell.SellJobRegistry.snapshot().size.toLong() } catch (_: Throwable) { -1L }
        addKv("SellJobRegistry size", sellJobs.toString())
        // V5.9.791 — operator audit Item 1 + 2 visibility: PositionExitArbiter counters.
        val arb: Map<String, Long> = try { com.lifecyclebot.engine.PositionArbiterCounters.snapshot() } catch (_: Throwable) { emptyMap() }
        addKvHighlight("arbiter terminalSells", (arb["terminalSells"] ?: 0L).toString(), "#10B981")
        val suppressed = arb["suppressedDuplicates"] ?: 0L
        addKvHighlight(
            "arbiter suppressedDuplicates",
            suppressed.toString(),
            if (suppressed > 0L) "#F59E0B" else "#10B981",
        )
        addKv("arbiter partialSells", (arb["partialSells"] ?: 0L).toString())
        addKv("arbiter staleSlotEvictions", (arb["staleSlotEvictions"] ?: 0L).toString())

        // ── 5. AUTHORITY ──────────────────────────────────────────────
        addHeader("🛡 5. Authority")
        val enabledSet = try { com.lifecyclebot.engine.EnabledTraderAuthority.snapshotStr() } catch (_: Throwable) { "?" }
        val enabledColor = if (enabledSet.isNotBlank()) "#10B981" else "#F59E0B"
        addKvHighlight("EnabledTraderAuthority", enabledSet.ifBlank { "(empty — bot stopped?)" }, enabledColor)
        val sniperEnabled = try {
            com.lifecyclebot.engine.EnabledTraderAuthority.isEnabled(
                com.lifecyclebot.engine.EnabledTraderAuthority.Trader.PROJECT_SNIPER,
            )
        } catch (_: Throwable) { false }
        val sniperColor = if (sniperEnabled) "#F59E0B" else "#10B981"
        addKvHighlight(
            "PROJECT_SNIPER enabled",
            if (sniperEnabled) "YES (live missions allowed)" else "NO (proven off — FATAL_AUTH_BREACH guard armed)",
            sniperColor,
        )

        // ── 6. WALLET ─────────────────────────────────────────────────
        addHeader("💰 6. Wallet")
        val hostOpen = try { com.lifecyclebot.engine.HostWalletTokenTracker.getOpenCount() } catch (_: Throwable) { -1 }
        val hostHeld = try { com.lifecyclebot.engine.HostWalletTokenTracker.getActuallyHeldCount() } catch (_: Throwable) { -1 }
        addKvHighlight("HostWalletTokenTracker.openCount", hostOpen.toString(), "#10B981")
        addKvHighlight("HostWalletTokenTracker.actuallyHeldCount", hostHeld.toString(), "#10B981")
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
            hostOpen < 0 || reconcilerKnown < 0 -> "#6B7280"
            kotlin.math.abs(drift) <= 1 -> "#10B981"
            kotlin.math.abs(drift) <= 5 -> "#F59E0B"
            else -> "#EF4444"
        }
        addKvHighlight("drift (host - reconciler)", drift.toString(), driftColor)

        // Footer note clarifying CLASSIC vs MODERN — operator audit Fix 4.
        addText(
            "ⓘ When scoring mode shows CLASSIC, the modern symbolic outer ring is bypassed. " +
                "The bot is NOT running 'full sentient symbolic trading' in that mode — it's the " +
                "20-layer build-1920 pipeline. Flip the toggle in Settings → Scoring Mode to switch.",
            Color.parseColor("#94A3B8"),
            small = true,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────
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

    private fun addKv(label: String, value: String) {
        rootColumn.addView(makeKvRow(label, value, Color.LTGRAY))
    }

    private fun addKvHighlight(label: String, value: String, hex: String) {
        rootColumn.addView(makeKvRow(label, value, Color.parseColor(hex)))
    }

    private fun addText(s: String, color: Int = Color.LTGRAY, small: Boolean = false) {
        rootColumn.addView(TextView(this).apply {
            text = s
            setTextColor(color)
            textSize = if (small) 11f else 13f
            typeface = Typeface.MONOSPACE
            val pad = (2 * resources.displayMetrics.density).toInt()
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
                setTextColor(Color.parseColor("#94A3B8"))
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
