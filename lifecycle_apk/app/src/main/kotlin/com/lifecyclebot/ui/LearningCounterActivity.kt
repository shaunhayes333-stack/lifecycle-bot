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
import android.os.HandlerThread
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

    // V5.0.3869 — operator instruction: high-latency DB queries MUST NOT run
    // from UI refresh surfaces. Use TradeHistoryStore cached stats instead of
    // copying full getAllTrades() every 2s. Solution: keep the existing
    // 2-second refresh cadence, but pre-warm the cached count on a
    // background HandlerThread so the UI-thread render reads a snapshot
    // value instead of issuing a fresh DB scan.
    private val ioThread = HandlerThread("LearningCounter-IO").apply { start() }
    private val ioHandler = Handler(ioThread.looper)
    @Volatile private var cachedTradeHistorySize: String = "loading…"

    private val backgroundPrewarm = object : Runnable {
        override fun run() {
            if (!refreshing) return
            cachedTradeHistorySize = try {
                TradeHistoryStore.getStatsCached().totalStoredTrades.toString()
            } catch (_: Throwable) { "?" }
            ioHandler.postDelayed(this, 2_000L)
        }
    }

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
        ioHandler.post(backgroundPrewarm)   // V5.9.868
    }

    override fun onPause() {
        super.onPause()
        refreshing = false
        handler.removeCallbacks(refreshRunnable)
        ioHandler.removeCallbacks(backgroundPrewarm)   // V5.9.868
    }

    override fun onDestroy() {
        super.onDestroy()
        try { ioThread.quitSafely() } catch (_: Throwable) {}   // V5.9.868
    }

    private fun renderAll() {
        rootColumn.removeAllViews()
        // V5.0.7023 — MUST reset with the column. removeAllViews detaches the
        // open card but leaves this field pointing at it, so every subsequent
        // add* would land in an orphan and the screen would render blank.
        currentCard7023 = null

        // ── Section 1: Wallet truth digest ────────────────────────────
        // V5.9.781 — operator audit item I: read HostWalletTokenTracker as
        // PRIMARY source of live wallet positions (it's the authoritative
        // open-position registry keyed by mint). WalletReconciler.knownMints
        // is a secondary mirror that lags behind during reconcile and used
        // to be the only source the digest displayed — which caused the
        // dashboard to show "0 mints" while host tracker had real open
        // positions. The drift line below makes the lag visible.
        addHeader("🔄 Wallet Truth Digest")
        val hostOpen: Int = try { com.lifecyclebot.engine.HostWalletTokenTracker.getOpenCount() } catch (_: Throwable) { -1 }
        val hostHeld: Int = try { com.lifecyclebot.engine.HostWalletTokenTracker.getActuallyHeldCount() } catch (_: Throwable) { -1 }
        val reconcilerKnown: Int = try {
            com.lifecyclebot.engine.WalletReconciler::class.java.declaredFields.find { it.name == "knownMints" }?.let { f ->
                f.isAccessible = true
                (f.get(com.lifecyclebot.engine.WalletReconciler) as? Set<*>)?.size
            }
        } catch (_: Throwable) { null } ?: -1
        val drift = if (hostOpen >= 0 && reconcilerKnown >= 0) hostOpen - reconcilerKnown else 0
        addKvHighlight("HostWalletTokenTracker.openCount", hostOpen.toString(), "#16E6A1")
        addKvHighlight("HostWalletTokenTracker.actuallyHeldCount", hostHeld.toString(), "#16E6A1")
        addKv("WalletReconciler.knownMints", reconcilerKnown.toString())
        val driftColor = when {
            hostOpen < 0 || reconcilerKnown < 0 -> "#63759B"
            kotlin.math.abs(drift) <= 1 -> "#16E6A1"
            kotlin.math.abs(drift) <= 5 -> "#FFB020"
            else -> "#FF4D6D"
        }
        addKvHighlight("drift (host - reconciler)", drift.toString(), driftColor)
        addKv("last digest", "see logcat (60s interval)")

        // ── Section 1b: Scoring mode + LLM status ─────────────────────
        // V5.9.781 — operator audit items G & H: surface the active scoring
        // pipeline and live LLM availability so the UI never implies modern
        // symbolic AI is reasoning while classicMode silently bypasses the
        // outer ring or while the LLM defaults ALLOW on cache miss.
        addHeader("🏛️ Scoring & Sentience Mode")
        val scoringMode = try { com.lifecyclebot.v3.scoring.UnifiedScorer.modeLabel() } catch (_: Throwable) { "?" }
        val scoringColor = if (scoringMode.startsWith("CLASSIC")) "#FFB020" else "#16E6A1"
        addKvHighlight("UnifiedScorer.mode", scoringMode, scoringColor)
        val llmStatus = try { com.lifecyclebot.engine.SentienceHooks.llmStatus() } catch (_: Throwable) { "UNAVAILABLE" }
        val llmColor = when (llmStatus) {
            "READY" -> "#16E6A1"
            "DEGRADED" -> "#FFB020"
            else -> "#FF4D6D"
        }
        addKvHighlight("LLM_STATUS", llmStatus, llmColor)

        // ── Section 2: Canonical pipeline ─────────────────────────────
        addHeader("📊 Canonical Pipeline (single source of truth)")
        val snap = CanonicalLearningCounters.snapshot()
        addKvHighlight("canonicalOutcomesTotal", snap["canonicalOutcomesTotal"]?.toString() ?: "?", "#16E6A1")
        addKv("liveOutcomesTotal", snap["liveOutcomesTotal"]?.toString() ?: "?")
        addKv("paperOutcomesTotal", snap["paperOutcomesTotal"]?.toString() ?: "?")
        addKv("shadowOutcomesTotal", snap["shadowOutcomesTotal"]?.toString() ?: "?")
        addKv("executedTradesTotal", snap["executedTradesTotal"]?.toString() ?: "?")
        addKvHighlight("failedExecutionsTotal", snap["failedExecutionsTotal"]?.toString() ?: "?", "#FFB020")
        addKv("settledWins", snap["settledWins"]?.toString() ?: "?")
        addKv("settledLosses", snap["settledLosses"]?.toString() ?: "?")
        addKv("openTrades", snap["openTrades"]?.toString() ?: "?")
        addKv("inconclusiveTrades", snap["inconclusiveTrades"]?.toString() ?: "?")
        addKv("recoveredTrades", snap["recoveredTrades"]?.toString() ?: "?")
        addKvHighlight("rejectedBadLabels", snap["rejectedBadLabels"]?.toString() ?: "?", "#FF4D6D")
        // V5.9.782 — operator audit items A, C, D, J: rich vs incomplete-feature
        // outcomes. Strategy learners only train on rich samples; incomplete ones
        // still tick counters and educate execution layers.
        addKvHighlight("richFeatureOutcomes", snap["richFeatureOutcomes"]?.toString() ?: "?", "#16E6A1")
        addKvHighlight("incompleteFeatureOutcomes", snap["incompleteFeatureOutcomes"]?.toString() ?: "?", "#FFB020")
        // V5.9.790 — operator audit Critical Fix 2: split rich into the subset that
        // actually trains strategy patterns vs the subset that may only train
        // execution learners (route, slippage, fee retry).
        addKvHighlight("strategyTrainableOutcomes", snap["strategyTrainableOutcomes"]?.toString() ?: "?", "#16E6A1")
        addKvHighlight("executionOnlyOutcomes", snap["executionOnlyOutcomes"]?.toString() ?: "?", "#FFB020")
        addKv("Bus subscribers", CanonicalOutcomeBus.subscriberCount().toString())
        // V5.9.790 — operator audit Critical Fix 5: surface how many legacy
        // direct BehaviorLearning.recordTrade() calls were made vs how many
        // wrote to pattern memory. By default ALL legacy direct calls are
        // compatibility-only (pattern memory comes from canonical bus alone).
        val legacyDirect = try { com.lifecyclebot.engine.BehaviorLearning.getLegacyDirectRecorded().toLong() } catch (_: Throwable) { -1L }
        val legacyPrimary = try { com.lifecyclebot.engine.BehaviorLearning.strategyLearningFromLegacy } catch (_: Throwable) { false }
        val legacyColor = if (legacyPrimary) "#FFB020" else "#63759B"
        addKvHighlight(
            "BehaviorLearning legacy direct calls",
            "$legacyDirect ${if (legacyPrimary) "(WRITING PATTERNS)" else "(counter-only · bus is primary)"}",
            legacyColor,
        )

        // ── Section 3: Legacy consumer counts (drift detection) ───────
        addHeader("📈 Legacy Consumer Counts (drift = fragmentation)")
        // V5.9.807 — operator audit: compare legacy consumers against
        // SETTLED TRADES (settledWins + settledLosses), NOT canonicalOutcomesTotal.
        // canonicalOutcomesTotal counts every event flowing through the bus
        // including OPEN events, mode-shadows, and historic preserved labels,
        // which made the drift look "doubled" against tradeCount-style
        // counters that only ever increment on settlement. The honest
        // comparison baseline is settled trades.
        val settledWins   = snap["settledWins"] ?: 0L
        val settledLosses = snap["settledLosses"] ?: 0L
        val settledTrades = settledWins + settledLosses
        val canonicalTotalRaw = snap["canonicalOutcomesTotal"] ?: 0L
        addKvHighlight(
            "drift baseline (settledWins + settledLosses)",
            "$settledTrades (canonicalRaw=$canonicalTotalRaw)",
            "#16E6A1",
        )
        val fluidSession = try { FluidLearningAI.getSessionTradeCount().toLong() } catch (_: Throwable) { -1L }
        val fluidBaseline = try { FluidLearningAI.getHistoricalBaseline().toLong() } catch (_: Throwable) { 0L }
        addKvDrift(
            "FluidLearningAI.sessionTrades (baseline+$fluidBaseline)",
            fluidSession,
            settledTrades,
        )
        // V5.9.1035 — operator triage: prior code surfaced session-only counters
        // for AdaptiveLearning + raw feature-gated counter for BehaviorLearning.
        // Both intentionally lag the canonical settled baseline by design
        // (AdaptiveLearning's session counter rebases per process restart;
        // BehaviorLearning skips featuresIncomplete=true samples — see
        // V5.9.802 doc on getCanonicalAlignedTradeCount). The drift display
        // was therefore lying about fragmentation when in fact the brains
        // ARE canonical-aligned through their dedicated bus-aware getters.
        // Switch to the canonical-aligned accessors so Δ=0 reflects reality.
        addKvDrift(
            "AdaptiveLearningEngine (canonical-aligned)",
            try { AdaptiveLearningEngine.getTradeCount().toLong() } catch (_: Throwable) { -1L },
            settledTrades,
        )
        addKvDrift(
            "BehaviorLearning (canonical-aligned)",
            try { BehaviorLearning.getCanonicalAlignedTradeCount().toLong() } catch (_: Throwable) { -1L },
            settledTrades,
        )
        addKvDrift(
            "MetaCognitionAI.totalTradesAnalyzed",
            try { MetaCognitionAI.getTotalTradesAnalyzed().toLong() } catch (_: Throwable) { -1L },
            settledTrades,
        )
        // V5.9.868 — read pre-warmed snapshot instead of hitting the DB on
        // the UI thread. Snapshot refreshes on ioThread every 2s (see
        // backgroundPrewarm) — at most one render-tick of staleness.
        addKv(
            "TradeHistoryStore.size",
            cachedTradeHistorySize,
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
            addText("(no layers have received outcomes yet — start trading to populate)", AateUi.TEXT_MUTED)
        } else {
            for ((layer, state) in readiness.entries.sortedBy { it.key }) {
                val color = when (state) {
                    com.lifecyclebot.engine.LayerReadiness.TRUSTED -> "#16E6A1"
                    com.lifecyclebot.engine.LayerReadiness.LIVE_ELIGIBLE -> "#4C8DFF"
                    com.lifecyclebot.engine.LayerReadiness.PAPER_ELIGIBLE -> "#9A4DFF"
                    com.lifecyclebot.engine.LayerReadiness.LEARNING_ONLY -> "#FFB020"
                    com.lifecyclebot.engine.LayerReadiness.DEGRADED -> "#FF4D6D"
                    // V5.9.790 — operator audit Critical Fix 2 sub-classifications:
                    // FEATURE_STARVED renders amber (not red) because the layer isn't
                    // broken — its producer is — and operator must not be told the
                    // layer's signal is bad when truth is it never got rich samples.
                    com.lifecyclebot.engine.LayerReadiness.DEGRADED_BAD_EV -> "#FF4D6D"
                    com.lifecyclebot.engine.LayerReadiness.DEGRADED_FEATURE_STARVED -> "#FFB020"
                    com.lifecyclebot.engine.LayerReadiness.DEGRADED_NO_ADAPTER -> "#63759B"
                    com.lifecyclebot.engine.LayerReadiness.DEGRADED_NO_VOTES -> "#63759B"
                    com.lifecyclebot.engine.LayerReadiness.RECEIVING_SIGNALS -> "#63759B"
                    com.lifecyclebot.engine.LayerReadiness.DISCONNECTED -> "#63759B"
                }
                // V5.9.790 — show per-layer rich vs incomplete sample counts so
                // the operator can see at a glance whether a DEGRADED label is
                // truthful (rich>0) or just feature-starved (rich==0).
                val (settled, rich, incomplete) = try {
                    LayerReadinessRegistry.countersOf(layer)
                } catch (_: Throwable) { Triple(0L, 0L, 0L) }
                val annotated = "${state.name}  n=$settled rich=$rich incomplete=$incomplete"
                addKvHighlight(layer, annotated, color)
            }
        }

        // ── Section 5: Recent canonical events ────────────────────────
        addHeader("📜 Recent Canonical Outcomes (last 50)")
        val recent = CanonicalOutcomeBus.recentSnapshot().take(50)
        if (recent.isEmpty()) {
            addText("(no canonical events yet — close a trade to populate)", AateUi.TEXT_MUTED)
        } else {
            for (o in recent) {
                val resultColor = when (o.result) {
                    com.lifecyclebot.engine.TradeResult.WIN -> "#16E6A1"
                    com.lifecyclebot.engine.TradeResult.LOSS -> "#FF4D6D"
                    com.lifecyclebot.engine.TradeResult.OPEN -> "#4C8DFF"
                    com.lifecyclebot.engine.TradeResult.INCONCLUSIVE_PENDING -> "#FFB020"
                    com.lifecyclebot.engine.TradeResult.BREAKEVEN -> "#9A4DFF"
                    else -> "#63759B"
                }
                val pnl = o.realizedPnlPct?.let { "%+.1f%%".format(it) } ?: "—"
                val msg = "${o.symbol.ifBlank { o.mint.take(6) }} · ${o.environment.name} · ${o.mode.name} · ${o.result.name} · $pnl · ${o.executionResult.name} · ${o.closeReason ?: ""}"
                addText(msg, Color.parseColor(resultColor), small = true)
            }
        }
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

    /** Highlights drift between a legacy counter and the canonical total. */
    private fun addKvDrift(label: String, legacyValue: Long, canonicalValue: Long) {
        val color = when {
            legacyValue < 0 -> "#63759B"  // unavailable
            kotlin.math.abs(legacyValue - canonicalValue) <= 5 -> "#16E6A1"  // aligned
            kotlin.math.abs(legacyValue - canonicalValue) <= 50 -> "#FFB020"  // mild drift
            else -> "#FF4D6D"  // major drift
        }
        val driftLabel = if (legacyValue < 0) "n/a" else "$legacyValue (Δ=${legacyValue - canonicalValue})"
        // V5.0.7023 — into the open card like every other row. Left on
        // rootColumn this would draw full-bleed BETWEEN cards, which reads as a
        // rendering fault rather than as the drift warning it is.
        target7023().addView(makeKvRow(label, driftLabel, Color.parseColor(color)))
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
            addView(TextView(this@LearningCounterActivity).apply {
                text = label
                setTextColor(AateUi.TEXT_SECONDARY)
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
