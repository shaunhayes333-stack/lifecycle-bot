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
    // V5.0.8069 — the Cortex panel's data, read off the UI thread (the Cortex locks its books while it reads them).
    @Volatile private var cachedCortex8069: com.lifecyclebot.engine.cortex.Cortex7885.Ui8069? = null
    @Volatile private var cachedSetups8069: List<com.lifecyclebot.engine.cortex.LanePlaybook7907.SetupRow8069> = emptyList()
    @Volatile private var cachedNoTrigger8084: List<com.lifecyclebot.engine.cortex.LanePlaybook7907.SetupRow8069> = emptyList()
    @Volatile private var cachedTruth8069: List<Pair<String, String>> = emptyList()

    private val backgroundPrewarm = object : Runnable {
        override fun run() {
            if (!refreshing) return
            cachedTradeHistorySize = try {
                TradeHistoryStore.getStatsCached().totalStoredTrades.toString()
            } catch (_: Throwable) { "?" }
            cachedCortex8069 = try { com.lifecyclebot.engine.cortex.Cortex7885.ui8069() } catch (_: Throwable) { null }
            cachedSetups8069 = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.setupRows8069(14) } catch (_: Throwable) { emptyList() }
            cachedNoTrigger8084 = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.noTriggerRows8084(6) } catch (_: Throwable) { emptyList() }
            cachedTruth8069 = listOf(
                "Live book (§8064)" to (try { com.lifecyclebot.engine.truth.LiveTruth8064.statusLine() } catch (_: Throwable) { "unavailable" }),
                "Lifecycle holds (§8065)" to (try { com.lifecyclebot.engine.LifecycleHold8065.statusLine() } catch (_: Throwable) { "unavailable" }),
                "Helius credits (§8066)" to (try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.statusLine().lineSequence().firstOrNull()?.trim().orEmpty() } catch (_: Throwable) { "unavailable" }),
            )
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

        // ── Section 0: the Cortex (V5.0.8069) ─────────────────────────
        renderCortex8069()

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

    // ── V5.0.8069 — the Cortex panel ──────────────────────────────────
    //
    // Owner: "do you want to make a Cortex display ui in the learning panel? it would be cool to see the details".
    // What the Cortex has learned per lane (what its STRONG and REFUSE reads did next), which setups have proven
    // themselves, which voters earned a seat, whether its refusals are blocking losers or winners, and the
    // live-book / lifecycle-hold / Helius-budget lines. Every figure is a net 5-minute (lifecycle lanes: lifecycle)
    // forward return after costs, from the honest labels (no spike credit, §8064).

    private fun pct8069(v: Double): String = if (v.isFinite()) "${if (v >= 0) "+" else ""}${"%.1f".format(v)}%" else "–"

    private fun signColor8069(v: Double): Int = when {
        !v.isFinite() -> AateUi.TEXT_MUTED
        v > 0.0 -> AateUi.GREEN
        v < 0.0 -> AateUi.RED
        else -> AateUi.TEXT
    }

    private fun renderCortex8069() {
        val ui = cachedCortex8069
        addHeader("🧠 Cortex")
        if (ui == null) { addText("loading the Cortex…", AateUi.TEXT_MUTED); return }
        target7023().addView(AateComponents6994.metricRow(this, "Decisions assessed", ui.assessed.toString(), AateUi.TEXT,
            "every buy / refuse the bot ruled on, read by the Cortex"))
        target7023().addView(AateComponents6994.metricRow(this, "Graded on their outcome", ui.graded.toString(), AateUi.CYAN,
            "${ui.pending} still waiting for their forward read"))
        target7023().addView(AateComponents6994.metricRow(this, "Voters with a seat", ui.seated.toString(),
            if (ui.seated > 0) AateUi.GREEN else AateUi.AMBER, "a seat = predictions that beat the lane average out of sample"))

        addHeader("🛣️ Cortex · lanes")
        if (ui.lanes.isEmpty()) addText("no graded decisions yet", AateUi.TEXT_MUTED)
        ui.lanes.forEach { l ->
            // 8269: the LEFT/RIGHT performance comparison must answer the
            // operator's actual question, "what AATE would TAKE versus REFUSE?"
            // The Cortex STRONG/REFUSE model verdict is a separate diagnostic,
            // NOT the historical decision label and NOT verified live P&L.
            // V5.0.8322 — the panel reads the SAME statistic as the report and the authority: the net mean of the lane's
            // graded objective (a runner lane's hold-window label). 8198/8269 led with GROSS MEDIANS: on a tail-dependent pond
            // the typical coin is flat at five minutes, so every cohort printed "+0.0%" (TREASURY STRONG n56 +0.0% vs REFUSE
            // n99 +0.0%) while the report read the same cohorts at -13.8% / -20.9% net mean. Medians stay beside, labelled.
            val gap = if (l.aateTakeN8269 >= 5 && l.aateRefuseN8269 >= 5)
                l.aateTakeNetMean8269 - l.aateRefuseNetMean8269 else Double.NaN
            target7023().addView(AateComponents6994.metricRow(this, l.lane,
                "${pct8069(l.aateTakeNetMean8269)} / ${pct8069(l.aateRefuseNetMean8269)}",
                when { !gap.isFinite() -> AateUi.TEXT_MUTED; gap > 0.0 -> AateUi.GREEN; else -> AateUi.RED },
                "AATE would TAKE n${l.aateTakeN8269} vs REFUSE n${l.aateRefuseN8269} · net mean, same executable forward-label population · " +
                    "net median ${pct8069(l.aateTakeNetMedian8269)} / ${pct8069(l.aateRefuseNetMedian8269)} · gross median ${pct8069(l.aateTakeGrossMedian8269)} / ${pct8069(l.aateRefuseGrossMedian8269)} · " +
                    "Cortex predicted STRONG n${l.strongN} net mean ${pct8069(l.strongMean)} vs REFUSE n${l.refuseN} ${pct8069(l.refuseMean)} · " +
                    "neutral n${l.neutralN} ${pct8069(l.neutralMean)} · " +
                    "Cortex net median ${pct8069(l.strongMedian8165)} / ${pct8069(l.refuseMedian8165)} · gross median ${pct8069(l.strongGrossMedian8198)} / ${pct8069(l.refuseGrossMedian8198)} · " +
                    "exec offset -${"%.1f".format(l.execOffset8198)}pt · graded ${l.graded} · slope ${"%.2f".format(l.slope)} · ${l.powers}" +
                    (if (l.source8153.isNotBlank()) " · executable first-of-episode on-time labels; old decayed cohorts display-only" else "")))
            target7023().addView(AateComponents6994.barRow(this, "authority", (l.auth * 100.0).coerceIn(0.0, 100.0),
                if (l.auth > 0.0) AateUi.GREEN else AateUi.TEXT_MUTED))
        }
        addText("Left = net mean forward return (runner lanes: their hold-window label) of tokens AATE would TAKE; right = ones it would REFUSE. These are decision-time counterfactual labels, not confirmed trading profits. Cortex STRONG/REFUSE predictions remain in each lane's detail. Colour needs 5+ qualified labels on both sides; the authority bar reports STRONG-side power, not refusal power.", AateUi.TEXT_MUTED, small = true)

        addHeader("🎯 Cortex · setups")
        val setups = cachedSetups8069
        if (setups.isEmpty()) addText("no setup has 5+ graded labels yet (fresh honest books since 8064)", AateUi.TEXT_MUTED)
        // V5.0.8084 — ranked on the whole set (proven, developing, then negative) by lower bound; NO_TRIGGER shown apart.
        setups.forEach { r ->
            target7023().addView(AateComponents6994.metricRow(this,
                "${if (r.proven) "✅ " else ""}${r.setup}",
                "${pct8069(r.meanPct)} ±${if (r.sePct.isFinite()) "%.1f".format(r.sePct) else "–"}",
                if (r.proven) AateUi.GREEN else if (r.meanPct > 0.0) AateUi.AMBER else AateUi.RED,
                "${r.lane} · n${r.n} · lb ${if (r.lowerPct.isFinite()) pct8069(r.lowerPct) else "–"} · ${r.tier.ifBlank { "-" }}" +
                    (if (r.proven) " · PROVEN on forward labels (not realised live P&L)" else "")))
        }
        val noTrig = cachedNoTrigger8084
        if (noTrig.isNotEmpty()) {
            addText("NO_TRIGGER (no recognised setup) cohorts:", AateUi.TEXT_MUTED, small = true)
            noTrig.forEach { r ->
                target7023().addView(AateComponents6994.metricRow(this, "NO_TRIGGER",
                    "${pct8069(r.meanPct)} ±${if (r.sePct.isFinite()) "%.1f".format(r.sePct) else "–"}", AateUi.TEXT_MUTED, "${r.lane} · n${r.n}"))
            }
        }
        addText("n = effective graded weight (decayed and capped), not a lifetime count. Forward labels are forecasts, not realised live trades.", AateUi.TEXT_MUTED, small = true)

        addHeader("🗳️ Cortex · voters")
        if (ui.seats.isEmpty()) addText("no voter has been graded yet", AateUi.TEXT_MUTED)
        ui.seats.forEach { v ->
            target7023().addView(AateComponents6994.metricRow(this, v.key.replace('|', ' '),
                "skill ${pct8069(v.skillPct)}",
                if (v.authority > 0.0) AateUi.GREEN else signColor8069(v.skillPct),
                "n${v.n}${if (v.authority > 0.0) " · seated, authority ${"%.2f".format(v.authority)}" else " · not seated"}"))
        }

        addHeader("🚫 Cortex · refusals audit")
        if (ui.vetoes.isEmpty()) addText("no refusal reason has 5+ graded coins yet", AateUi.TEXT_MUTED)
        ui.vetoes.forEach { v ->
            // A refusal whose coins went DOWN saved money (green); one whose coins went UP refused winners (red).
            target7023().addView(AateComponents6994.metricRow(this, v.reason.take(34),
                pct8069(v.meanPct), if (v.meanPct < 0.0) AateUi.GREEN else AateUi.RED,
                "n${v.n} refused coins · ${if (v.meanPct < 0.0) "blocked losers" else "blocked winners"}"))
        }

        addHeader("🧬 Live book · holds · data budget")
        cachedTruth8069.forEach { (k, v) -> addKv(k, ""); addText(v, AateUi.TEXT_SECONDARY, small = true) }
    }

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
