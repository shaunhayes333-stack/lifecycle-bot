/*
 * V5.9.495z8 — Learning Counter Screen.
 *
 * Single-glance dashboard that answers the operator's question:
 *   'Is education actually happening, and which counter is the truth?'
 *
 * Sections (top → bottom):
 *   1. Wallet truth digest     (positions, mints held, drift)
 *   2. Canonical pipeline      (CanonicalLearningCounters snapshot)
 *   3. Legacy consumer counts  (FluidLearningAI, AdaptiveLearningEngine,
 *                                RunTracker30D, BehaviorLearning,
 *                                MetaCognitionAI, TradeHistoryStore)
 *   4. Layer readiness         (LayerReadinessRegistry snapshot)
 *   5. Recent canonical events (last 50 outcomes from the bus)
 *
 * Auto-refreshes every 2s. Pure programmatic UI — no XML resource IDs.
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
import com.lifecyclebot.engine.AdaptiveLearningEngine
import com.lifecyclebot.engine.BehaviorLearning
import com.lifecyclebot.engine.CanonicalLearningCounters
import com.lifecyclebot.engine.CanonicalOutcomeBus
import com.lifecyclebot.engine.LayerReadinessRegistry
import com.lifecyclebot.engine.RunTracker30D
import com.lifecyclebot.engine.TradeHistoryStore
import com.lifecyclebot.v3.scoring.FluidLearningAI
import com.lifecyclebot.v3.scoring.MetaCognitionAI

class LearningCounterActivity : Activity() {

    private lateinit var rootScroll: ScrollView
    private lateinit var rootColumn: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (refreshing) {
                renderAll()
                handler.postDelayed(this, 2_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "🧠 Learning Pipeline"
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

        // ── Section 1: Wallet truth digest ────────────────────────────
        addHeader("🔄 Wallet Truth Digest")
        val mints = try { com.lifecyclebot.engine.WalletReconciler::class.java.declaredFields.find { it.name == "knownMints" }?.let { f -> f.isAccessible = true; (f.get(com.lifecyclebot.engine.WalletReconciler) as? Set<*>)?.size } } catch (_: Throwable) { null } ?: -1
        addKv("Known mints in registry", mints.toString())
        addKv("Last digest", "see logcat (60s interval)")

        // ── Section 2: Canonical pipeline ─────────────────────────────
        addHeader("📊 Canonical Pipeline (single source of truth)")
        val snap = CanonicalLearningCounters.snapshot()
        addKvHighlight("canonicalOutcomesTotal", snap["canonicalOutcomesTotal"]?.toString() ?: "?", "#10B981")
        addKv("liveOutcomesTotal", snap["liveOutcomesTotal"]?.toString() ?: "?")
        addKv("paperOutcomesTotal", snap["paperOutcomesTotal"]?.toString() ?: "?")
        addKv("shadowOutcomesTotal", snap["shadowOutcomesTotal"]?.toString() ?: "?")
        addKv("executedTradesTotal", snap["executedTradesTotal"]?.toString() ?: "?")
        addKvHighlight("failedExecutionsTotal", snap["failedExecutionsTotal"]?.toString() ?: "?", "#F59E0B")
        addKv("settledWins", snap["settledWins"]?.toString() ?: "?")
        addKv("settledLosses", snap["settledLosses"]?.toString() ?: "?")
        addKv("openTrades", snap["openTrades"]?.toString() ?: "?")
        addKv("inconclusiveTrades", snap["inconclusiveTrades"]?.toString() ?: "?")
        addKv("recoveredTrades", snap["recoveredTrades"]?.toString() ?: "?")
        addKvHighlight("rejectedBadLabels", snap["rejectedBadLabels"]?.toString() ?: "?", "#EF4444")
        addKv("Bus subscribers", CanonicalOutcomeBus.subscriberCount().toString())

        // ── Section 3: Legacy consumer counts (drift detection) ───────
        addHeader("📈 Legacy Consumer Counts (drift = fragmentation)")
        val canonicalTotal = snap["canonicalOutcomesTotal"] ?: 0L
        addKvDrift(
            "FluidLearningAI.totalTradeCount",
            try { FluidLearningAI.getTotalTradeCount().toLong() } catch (_: Throwable) { -1L },
            canonicalTotal,
        )
        addKvDrift(
            "AdaptiveLearningEngine.tradeCount",
            try { AdaptiveLearningEngine.getTradeCount().toLong() } catch (_: Throwable) { -1L },
            canonicalTotal,
        )
        addKvDrift(
            "BehaviorLearning.tradeCount",
            try { BehaviorLearning.getTradeCount().toLong() } catch (_: Throwable) { -1L },
            canonicalTotal,
        )
        addKvDrift(
            "MetaCognitionAI.totalTradesAnalyzed",
            try { MetaCognitionAI.getTotalTradesAnalyzed().toLong() } catch (_: Throwable) { -1L },
            canonicalTotal,
        )
        addKv(
            "TradeHistoryStore.size",
            try { TradeHistoryStore.getAllTrades().size.toString() } catch (_: Throwable) { "?" },
        )
        // RunTracker30D doesn't expose totalTrades directly; show learning string.
        addKv(
            "RunTracker30D",
            try { RunTracker30D.getLearningString() } catch (_: Throwable) { "?" }.take(80),
        )

        // ── Section 4: Layer readiness ────────────────────────────────
        addHeader("🎓 Layer Readiness")
        val readiness = LayerReadinessRegistry.snapshot()
        if (readiness.isEmpty()) {
            addText("(no layers have received outcomes yet — start trading to populate)", Color.GRAY)
        } else {
            for ((layer, state) in readiness.entries.sortedBy { it.key }) {
                val color = when (state) {
                    com.lifecyclebot.engine.LayerReadiness.TRUSTED -> "#10B981"
                    com.lifecyclebot.engine.LayerReadiness.LIVE_ELIGIBLE -> "#3B82F6"
                    com.lifecyclebot.engine.LayerReadiness.PAPER_ELIGIBLE -> "#8B5CF6"
                    com.lifecyclebot.engine.LayerReadiness.LEARNING_ONLY -> "#F59E0B"
                    com.lifecyclebot.engine.LayerReadiness.DEGRADED -> "#EF4444"
                    com.lifecyclebot.engine.LayerReadiness.RECEIVING_SIGNALS -> "#6B7280"
                    com.lifecyclebot.engine.LayerReadiness.DISCONNECTED -> "#374151"
                }
                addKvHighlight(layer, state.name, color)
            }
        }

        // ── Section 5: Recent canonical events ────────────────────────
        addHeader("📜 Recent Canonical Outcomes (last 50)")
        val recent = CanonicalOutcomeBus.recentSnapshot().take(50)
        if (recent.isEmpty()) {
            addText("(no canonical events yet — close a trade to populate)", Color.GRAY)
        } else {
            for (o in recent) {
                val resultColor = when (o.result) {
                    com.lifecyclebot.engine.TradeResult.WIN -> "#10B981"
                    com.lifecyclebot.engine.TradeResult.LOSS -> "#EF4444"
                    com.lifecyclebot.engine.TradeResult.OPEN -> "#3B82F6"
                    com.lifecyclebot.engine.TradeResult.INCONCLUSIVE_PENDING -> "#F59E0B"
                    com.lifecyclebot.engine.TradeResult.BREAKEVEN -> "#8B5CF6"
                    else -> "#6B7280"
                }
                val pnl = o.realizedPnlPct?.let { "%+.1f%%".format(it) } ?: "—"
                val msg = "${o.symbol.ifBlank { o.mint.take(6) }} · ${o.environment.name} · ${o.mode.name} · ${o.result.name} · $pnl · ${o.executionResult.name} · ${o.closeReason ?: ""}"
                addText(msg, Color.parseColor(resultColor), small = true)
            }
        }
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

    /** Highlights drift between a legacy counter and the canonical total. */
    private fun addKvDrift(label: String, legacyValue: Long, canonicalValue: Long) {
        val color = when {
            legacyValue < 0 -> "#6B7280"  // unavailable
            kotlin.math.abs(legacyValue - canonicalValue) <= 5 -> "#10B981"  // aligned
            kotlin.math.abs(legacyValue - canonicalValue) <= 50 -> "#F59E0B"  // mild drift
            else -> "#EF4444"  // major drift
        }
        val driftLabel = if (legacyValue < 0) "n/a" else "$legacyValue (Δ=${legacyValue - canonicalValue})"
        rootColumn.addView(makeKvRow(label, driftLabel, Color.parseColor(color)))
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
            addView(TextView(this@LearningCounterActivity).apply {
                text = label
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(this@LearningCounterActivity).apply {
                text = value
                setTextColor(valueColor)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
            })
        }
    }
}
