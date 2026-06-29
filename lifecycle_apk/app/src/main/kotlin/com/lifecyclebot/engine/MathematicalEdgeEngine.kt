package com.lifecyclebot.engine

import com.lifecyclebot.util.AppDispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.4529 — Mathematical Edge Engine.
 *
 * Captures broad, denominator-rich buy/sell path data into a coroutine-drained
 * event queue so the scanner/executor hot path only enqueues. This is the
 * statistical substrate for compounding: opportunity denominators, drop reasons,
 * sizing attribution, fill/route placeholders, terminal outcomes, MFE/MAE, and
 * capital-efficiency summaries. No veto authority, no synchronous I/O, no LLM.
 */
object MathematicalEdgeEngine {
    private const val MAX_QUEUE = 20_000
    private const val MAX_RECENT = 120
    private val started = AtomicBoolean(false)
    private val queued = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val processed = AtomicLong(0)
    private val q = ConcurrentLinkedQueue<EdgeEvent>()
    private val byStage = ConcurrentHashMap<String, Stat>()
    private val byLaneDecision = ConcurrentHashMap<String, Stat>()
    private val bySourceDecision = ConcurrentHashMap<String, Stat>()
    private val byLaneTerminal = ConcurrentHashMap<String, TerminalStat>()
    private val bySourceTerminal = ConcurrentHashMap<String, TerminalStat>()
    private val byStackReadback = ConcurrentHashMap<String, Stat>()
    private val recent = ArrayDeque<String>()
    private val recentLock = Any()

    data class EdgeEvent(
        val kind: String,
        val stage: String,
        val lane: String,
        val source: String,
        val mint: String,
        val symbol: String,
        val decision: String,
        val reason: String,
        val score: Double = -1.0,
        val confidence: Double = -1.0,
        val liquidityUsd: Double = -1.0,
        val marketCapUsd: Double = -1.0,
        val baseSol: Double = -1.0,
        val proposedSol: Double = -1.0,
        val finalSol: Double = -1.0,
        val walletSol: Double = -1.0,
        val rawMultiplier: Double = 1.0,
        val clampedMultiplier: Double = 1.0,
        val quotePx: Double = 0.0,
        val realizedPx: Double = 0.0,
        val slippagePct: Double = 0.0,
        val latencyMs: Long = 0L,
        val pnlPct: Double = 0.0,
        val pnlSol: Double = 0.0,
        val holdMs: Long = 0L,
        val peakGainPct: Double = 0.0,
        val maxDrawdownPct: Double = 0.0,
        val trainable: Boolean = false,
        val accepted: Boolean = true,
        val components: String = "",
        val componentMap: Map<String, Double> = emptyMap(),
        val style: String = "",
        val regime: String = "",
        val build: String = com.lifecyclebot.BuildConfig.VERSION_NAME,
        val tsMs: Long = System.currentTimeMillis(),
    )

    class Stat {
        val n = AtomicLong(0)
        val buys = AtomicLong(0)
        val drops = AtomicLong(0)
        val sizing = AtomicLong(0)
        fun add(e: EdgeEvent) {
            n.incrementAndGet()
            when {
                e.kind == "SIZING" -> sizing.incrementAndGet()
                e.decision.contains("BUY", true) || e.decision.contains("QUALIFIED", true) -> buys.incrementAndGet()
                e.decision.contains("DROP", true) || e.decision.contains("VETO", true) || e.decision.contains("BLOCK", true) -> drops.incrementAndGet()
            }
        }
        fun tag(): String = "n=${n.get()} buy=${buys.get()} drop=${drops.get()} size=${sizing.get()}"
    }

    class TerminalStat {
        val n = AtomicLong(0)
        val wins = AtomicLong(0)
        val losses = AtomicLong(0)
        @Volatile var pnlSol = 0.0
        @Volatile var pnlPct = 0.0
        @Synchronized fun add(e: EdgeEvent) {
            n.incrementAndGet()
            if (e.pnlPct > 1.0 || e.pnlSol > 0.0) wins.incrementAndGet()
            if (e.pnlPct < -1.0 || e.pnlSol < 0.0) losses.incrementAndGet()
            pnlSol += e.pnlSol
            pnlPct += e.pnlPct
        }
        private fun f(v: Double, d: Int = 2): String = try { "%.${d}f".format(v) } catch (_: Throwable) { v.toString().take(12) }
        fun tag(): String {
            val closed = (wins.get() + losses.get()).coerceAtLeast(1)
            val wr = wins.get().toDouble() / closed.toDouble() * 100.0
            return "n=${n.get()} W/L=${wins.get()}/${losses.get()} WR=${f(wr,1)}% PnL=${f(pnlSol,4)}SOL avgPct=${f(pnlPct / n.get().coerceAtLeast(1),1)}%"
        }
    }

    private fun clean(x: String?, fallback: String = "UNKNOWN"): String = x?.ifBlank { fallback }?.replace('\n',' ')?.replace('\r',' ')?.take(160) ?: fallback
    private fun key(vararg parts: String): String = parts.joinToString("|") { clean(it).uppercase().take(48) }.take(160)
    private fun topStageKey(e: EdgeEvent): String = key(e.kind, e.stage)
    private fun ensureStarted() {
        if (started.compareAndSet(false, true)) {
            GlobalScope.launch(AppDispatchers.sideEffect) {
                while (true) {
                    try { drain(512) } catch (_: Throwable) {}
                    delay(750L)
                }
            }
        }
    }

    fun submit(e: EdgeEvent) {
        ensureStarted()
        if (queued.get() - processed.get() > MAX_QUEUE) { dropped.incrementAndGet(); return }
        queued.incrementAndGet()
        q.offer(e)
    }

    private fun drain(max: Int) {
        var i = 0
        while (i < max) {
            val e = q.poll() ?: break
            processed.incrementAndGet()
            byStage.computeIfAbsent(topStageKey(e)) { Stat() }.add(e)
            byLaneDecision.computeIfAbsent(key(e.lane, e.decision)) { Stat() }.add(e)
            bySourceDecision.computeIfAbsent(key(e.source, e.decision)) { Stat() }.add(e)
            if (e.kind == "TERMINAL") {
                byLaneTerminal.computeIfAbsent(clean(e.lane).uppercase()) { TerminalStat() }.add(e)
                bySourceTerminal.computeIfAbsent(clean(e.source).uppercase()) { TerminalStat() }.add(e)
            }
            remember(e)
            fanoutToExistingEdgeStack(e)
            i++
        }
    }

    private fun remember(e: EdgeEvent) = synchronized(recentLock) {
        recent.addLast("${e.kind} stage=${e.stage} lane=${e.lane} src=${e.source} dec=${e.decision} score=${fmt(e.score,1)} conf=${fmt(e.confidence,1)} liq=${fmt(e.liquidityUsd,0)} mcap=${fmt(e.marketCapUsd,0)} size=${fmt(e.finalSol,4)} pnl=${fmt(e.pnlPct,1)}%/${fmt(e.pnlSol,4)}SOL hold=${e.holdMs/60000}m peak=${fmt(e.peakGainPct,1)} reason=${e.reason.take(70)} mint=${e.mint.take(10)} build=${e.build}".take(420))
        while (recent.size > MAX_RECENT) recent.removeFirst()
    }

    private fun rememberLine(line: String) = synchronized(recentLock) {
        recent.addLast(line.take(420))
        while (recent.size > MAX_RECENT) recent.removeFirst()
    }

    private fun readback(tag: String) { byStackReadback.computeIfAbsent(tag.take(80)) { Stat() }.add(EdgeEvent(kind = "READBACK", stage = tag, lane = "", source = "", mint = "", symbol = "", decision = "READBACK", reason = "")) }

    private fun fanoutToExistingEdgeStack(e: EdgeEvent) {
        try {
            when (e.kind) {
                "ENTRY" -> {
                    if (e.stage.contains("candidate", true)) {
                        try { SourceFamilyOpportunityScorecard.recordDiscovered(e.source, hasRugOverlay = e.reason.contains("rug", true)) } catch (_: Throwable) {}
                    }
                    if (e.decision.contains("QUALIFIED", true) || e.decision.contains("PROBE", true) || e.decision.contains("ADMIT", true)) {
                        try { SourceFamilyOpportunityScorecard.recordAdmitted(e.source, hasRugOverlay = e.reason.contains("rug", true)) } catch (_: Throwable) {}
                    }
                    if (e.score >= 60.0 || e.decision.contains("QUALIFIED", true) || e.decision.contains("DROP", true)) {
                        try {
                            UltimateEdgeEngine.enqueueRefresh(
                                mint = e.mint,
                                symbol = e.symbol,
                                lane = e.lane,
                                source = e.source,
                                entryScore = e.score.toInt().coerceIn(0, 100),
                                reason = "mee_entry ${e.stage} ${e.decision} ${e.reason}".take(220),
                            )
                        } catch (_: Throwable) {}
                    }
                    try {
                        val lp = LiveProbabilityEngine.forecast(e.lane, e.score.toInt().coerceIn(0, 100), e.style.ifBlank { "U" }, e.regime.ifBlank { "NORMAL" }, e.stage, candidateConfidence = (e.confidence / 100.0).coerceIn(0.0, 1.0))
                        val fwd = ForwardOutcomeModel.forecast(e.lane, e.score.toInt().coerceIn(0, 100), e.style.ifBlank { "U" }, e.regime.ifBlank { "NORMAL" }, e.stage)
                        val sem = SemanticPatternGraph.entryDnaBias(setup = "${e.symbol} ${e.lane} ${e.source} score_${e.score.toInt()} ${e.reason.take(60)}", lane = e.lane, deployer = "", source = e.source, dnaKey = "mee_${e.lane}_${e.source}_${e.score.toInt()}")
                        val uph = UnifiedPolicyHead.predictWinProb(e.lane, UnifiedPolicyHead.Signals(mlEntryConf = (e.confidence / 100.0).coerceIn(0.0,1.0), symGreenLight = sem.sizeMult.coerceIn(0.0,1.0), evRatio = ((lp.expectedPnlPct + 50.0) / 100.0).coerceIn(0.0,1.0), metaConviction = lp.sizeMult.coerceIn(0.0,1.0), fwdPWin = fwd.pWin, candConf = (e.confidence / 100.0).coerceIn(0.0,1.0)))
                        readback("LiveProbabilityEngine"); readback("ForwardOutcomeModel"); readback("UnifiedPolicyHead"); readback("SemanticPatternGraph")
                        if (e.score >= 70.0) rememberLine("EDGE_READBACK entry lane=${e.lane} src=${e.source} score=${e.score.toInt()} liveP=${fmt(lp.pWin,2)} exp=${fmt(lp.expectedPnlPct,1)} fwdP=${fmt(fwd.pWin,2)} uph=${fmt(uph,2)} sem=${fmt(sem.sizeMult,2)} mint=${e.mint.take(10)}")
                    } catch (_: Throwable) {}
                }
                "SIZING" -> {
                    val scoreInt = e.score.toInt().coerceIn(0, 100)
                    val regimeForEdge = e.regime.ifBlank { "NORMAL" }
                    try { MultiplierAttributionLedger.recordEntry("MEE", e.lane, e.source, e.mint, e.symbol, e.baseSol, e.rawMultiplier, e.componentMap) ; readback("MultiplierAttributionLedger") } catch (_: Throwable) {}
                    try {
                        val lp = LiveProbabilityEngine.forecast(e.lane, scoreInt, e.style.ifBlank { "MEE" }, regimeForEdge, e.stage)
                        val fwd = ForwardOutcomeModel.forecast(e.lane, scoreInt, e.style.ifBlank { "MEE" }, regimeForEdge, e.stage)
                        ForwardOutcomeModel.stamp(e.mint, e.lane, scoreInt, e.style.ifBlank { "MEE" }, regimeForEdge, e.stage)
                        UnifiedPolicyHead.stamp(e.mint, e.lane, UnifiedPolicyHead.Signals(
                            mlEntryConf = (scoreInt / 100.0).coerceIn(0.0, 1.0),
                            symGreenLight = e.clampedMultiplier.coerceIn(0.0, 1.0),
                            evRatio = ((lp.expectedPnlPct + 50.0) / 100.0).coerceIn(0.0, 1.0),
                            metaConviction = lp.sizeMult.coerceIn(0.0, 1.0),
                            fwdPWin = fwd.pWin,
                            candConf = (scoreInt / 100.0).coerceIn(0.0, 1.0),
                        ))
                        readback("ForwardOutcomeModel.stamp"); readback("UnifiedPolicyHead.stamp"); readback("LiveProbabilityEngine.sizing")
                    } catch (_: Throwable) {}
                    try { LiveStrategyTuner.adjustment(e.lane); LaneExpectancyDamper.sizeMultiplier(e.lane); CapitalEfficiencyBrain.sizeMultiplier(e.lane, e.source); StrategyHypothesisEngine.getSizeBias(e.lane, scoreInt, regimeForEdge, e.mint); StrategyHypothesisEngine.getStopBias(e.lane, scoreInt, regimeForEdge, e.mint); readback("LiveStrategyTuner"); readback("LaneExpectancyDamper"); readback("CapitalEfficiencyBrain"); readback("StrategyHypothesisEngine.sizing") } catch (_: Throwable) {}
                    if (e.score >= 75.0 && e.clampedMultiplier < 0.35) {
                        try {
                            ChokeReliefBus.launch("MEE_SIZING_ANOMALY_HYPOTHESIS_4530", e.mint) {
                                AsyncStrategyLab.submitBackgroundHypothesis(
                                    provider = AsyncStrategyLab.Provider.LOCAL_ONLY,
                                    lane = e.lane,
                                    expectedMetric = "raise realized net SOL per high-score opportunity without increasing hard-safety rejects",
                                    proposal = "MathematicalEdgeEngine observed high-score candidate score=${e.score.toInt()} lane=${e.lane} source=${e.source} compressed to multiplier=${fmt(e.clampedMultiplier,3)} finalSol=${fmt(e.finalSol,4)}. Test bounded recovery-size lift only for matching lane/source/regime when terminal expectancy is positive.",
                                    rollbackCondition = "rollback if lane/source terminal pnlSol or win-rate deteriorates over the next 30 trainable terminal closes",
                                    symbolicChecked = true,
                                )
                            }
                        } catch (_: Throwable) {}
                    }
                }
                "FILL" -> {
                    if (e.liquidityUsd > 0.0 && e.quotePx > 0.0 && e.realizedPx > 0.0) {
                        try { com.lifecyclebot.v3.scoring.ExecutionCostPredictorAI.learn(e.liquidityUsd, e.quotePx, e.realizedPx) } catch (_: Throwable) {}
                    }
                }
                "TERMINAL" -> {
                    val scoreInt = e.score.toInt().coerceIn(0, 100)
                    if (scoreInt > 0) {
                        try { ScoreExpectancyTracker.record("MEE_LANE_${e.lane.uppercase().take(32)}", scoreInt, e.pnlPct) } catch (_: Throwable) {}
                        try { ScoreExpectancyTracker.record("MEE_SOURCE_${e.source.uppercase().take(48)}", scoreInt, e.pnlPct) } catch (_: Throwable) {}
                        if (e.regime.isNotBlank()) try { ScoreExpectancyTracker.record("MEE_REGIME_${e.regime.uppercase().take(24)}", scoreInt, e.pnlPct) } catch (_: Throwable) {}
                    }
                    try { CounterfactualReplayEngine.policyHints(e.lane); ExitCostMicrobrain.exitUrgencyHint(e.lane, e.liquidityUsd, e.reason); CapitalEfficiencyBrain.sizeMultiplier(e.lane, e.source); StrategyHypothesisEngine.getSizeBias(e.lane, scoreInt, e.regime.ifBlank { "NORMAL" }, e.mint); readback("CounterfactualReplayEngine"); readback("ExitCostMicrobrain"); readback("CapitalEfficiencyBrain"); readback("StrategyHypothesisEngine") } catch (_: Throwable) {}
                    if ((e.peakGainPct - e.pnlPct) >= 80.0 || (e.pnlPct < -25.0 && e.score >= 70.0)) {
                        try {
                            ChokeReliefBus.launch("MEE_EXIT_ANOMALY_HYPOTHESIS_4530", e.mint) {
                                AsyncStrategyLab.submitBackgroundHypothesis(
                                    provider = AsyncStrategyLab.Provider.LOCAL_ONLY,
                                    lane = e.lane,
                                    expectedMetric = "improve peak-to-realized capture and reduce high-score terminal bleed",
                                    proposal = "MathematicalEdgeEngine terminal anomaly lane=${e.lane} source=${e.source} score=${e.score.toInt()} pnl=${fmt(e.pnlPct,1)} peak=${fmt(e.peakGainPct,1)} draw=${fmt(e.maxDrawdownPct,1)} reason=${e.reason.take(120)}. Test bounded exit-bias adjustment for matching DNA only; do not override hard safety.",
                                    rollbackCondition = "rollback if peak capture ratio or net SOL worsens over 30 trainable terminal closes in this lane/source bucket",
                                    symbolicChecked = true,
                                )
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    fun captureEntryOpportunity(stage: String, lane: String, source: String, mint: String, symbol: String, decision: String, reason: String, score: Double, confidence: Double, liquidityUsd: Double, marketCapUsd: Double, proposedSol: Double = -1.0, finalSol: Double = -1.0, style: String = "", regime: String = "") = submit(EdgeEvent(
        kind = "ENTRY", stage = stage, lane = lane, source = source, mint = mint, symbol = symbol, decision = decision, reason = reason,
        score = score, confidence = confidence, liquidityUsd = liquidityUsd, marketCapUsd = marketCapUsd, proposedSol = proposedSol, finalSol = finalSol, style = style, regime = regime,
    ))

    fun captureSizing(stage: String, lane: String, source: String, mint: String, symbol: String, baseSol: Double, rawMultiplier: Double, clampedMultiplier: Double, finalSol: Double, walletSol: Double, liquidityUsd: Double, score: Double, components: Map<String, Double>, reason: String = "", regime: String = "", style: String = "") = submit(EdgeEvent(
        kind = "SIZING", stage = stage, lane = lane, source = source, mint = mint, symbol = symbol, decision = "SIZING", reason = reason,
        score = score, liquidityUsd = liquidityUsd, baseSol = baseSol, finalSol = finalSol, walletSol = walletSol, rawMultiplier = rawMultiplier, clampedMultiplier = clampedMultiplier, regime = regime, style = style,
        components = components.entries.filter { kotlin.math.abs(it.value - 1.0) > 0.001 }.sortedBy { it.value }.take(12).joinToString(",") { "${it.key}=${fmt(it.value,3)}" },
        componentMap = components,
    ))


    fun captureFill(stage: String, lane: String, source: String, mint: String, symbol: String, side: String, quotePx: Double, realizedPx: Double, liquidityUsd: Double, slippagePct: Double, latencyMs: Long, reason: String = "") = submit(EdgeEvent(
        kind = "FILL", stage = stage, lane = lane, source = source, mint = mint, symbol = symbol, decision = "FILL_$side", reason = reason, liquidityUsd = liquidityUsd, quotePx = quotePx, realizedPx = realizedPx, slippagePct = slippagePct, latencyMs = latencyMs,
    ))

    fun captureTerminal(stage: String, lane: String, source: String, mint: String, symbol: String, side: String, reason: String, pnlPct: Double, pnlSol: Double, sizeSol: Double, holdMs: Long, peakGainPct: Double, maxDrawdownPct: Double, trainable: Boolean, accepted: Boolean, score: Double = -1.0, regime: String = "") = submit(EdgeEvent(
        kind = "TERMINAL", stage = stage, lane = lane, source = source, mint = mint, symbol = symbol, decision = if (accepted && trainable) "TERMINAL_TRAINABLE" else if (accepted) "TERMINAL_UNTRAINABLE" else "TERMINAL_SUPPRESSED", reason = "$side:$reason",
        score = score, regime = regime, pnlPct = pnlPct, pnlSol = pnlSol, finalSol = sizeSol, holdMs = holdMs, peakGainPct = peakGainPct, maxDrawdownPct = maxDrawdownPct, trainable = trainable, accepted = accepted,
    ))

    fun captureExitDecision(stage: String, lane: String, source: String, mint: String, symbol: String, decision: String, reason: String, pnlPct: Double, peakGainPct: Double, holdMs: Long, liquidityUsd: Double) = submit(EdgeEvent(
        kind = "EXIT_DECISION", stage = stage, lane = lane, source = source, mint = mint, symbol = symbol, decision = decision, reason = reason, pnlPct = pnlPct, peakGainPct = peakGainPct, holdMs = holdMs, liquidityUsd = liquidityUsd,
    ))

    fun formatForPipelineDump(): String {
        drain(1024)
        fun <T> top(m: ConcurrentHashMap<String, T>, n: Int = 8, f: (Map.Entry<String,T>) -> String): String = m.entries.sortedByDescending {
            when (val v = it.value) { is Stat -> v.n.get(); is TerminalStat -> v.n.get(); else -> 0L }
        }.take(n).joinToString(" · ", transform = f).ifBlank { "none" }
        val recentLines = synchronized(recentLock) { recent.toList().takeLast(10) }
        val stageTop = top(byStage) { "${it.key} ${it.value.tag()}" }
        val laneTop = top(byLaneDecision) { "${it.key} ${it.value.tag()}" }
        val sourceTop = top(bySourceDecision) { "${it.key} ${it.value.tag()}" }
        val terminalLaneTop = top(byLaneTerminal) { "${it.key} ${it.value.tag()}" }
        val terminalSourceTop = top(bySourceTerminal) { "${it.key} ${it.value.tag()}" }
        val readbackTop = top(byStackReadback) { "${it.key} ${it.value.tag()}" }
        val stackStatus = listOf(
            try { CounterfactualReplayEngine.policyHints("ALL").take(140) } catch (_: Throwable) { "CounterfactualReplayEngine:error" },
            try { SemanticPatternGraph.summary().take(140) } catch (_: Throwable) { "SemanticPatternGraph:error" },
            try { ExitCostMicrobrain.status().take(140) } catch (_: Throwable) { "ExitCostMicrobrain:error" },
            try { CapitalEfficiencyBrain.status().take(140) } catch (_: Throwable) { "CapitalEfficiencyBrain:error" },
            try { LiveProbabilityEngine.statusLine().take(140) } catch (_: Throwable) { "LiveProbabilityEngine:error" },
            try { ForwardOutcomeModel.formatForPipelineDump().lineSequence().firstOrNull()?.take(140) ?: "ForwardOutcomeModel:empty" } catch (_: Throwable) { "ForwardOutcomeModel:error" },
            try { UnifiedPolicyHead.formatForPipelineDump().lineSequence().firstOrNull()?.take(140) ?: "UnifiedPolicyHead:empty" } catch (_: Throwable) { "UnifiedPolicyHead:error" },
            try { UnifiedExitPolicyHead.formatForPipelineDump().lineSequence().firstOrNull()?.take(140) ?: "UnifiedExitPolicyHead:empty" } catch (_: Throwable) { "UnifiedExitPolicyHead:error" },
            try { StrategyHypothesisEngine.formatForPipelineDump().lineSequence().firstOrNull()?.take(140) ?: "StrategyHypothesisEngine:empty" } catch (_: Throwable) { "StrategyHypothesisEngine:error" },
        ).joinToString(" || ")
        return buildString {
            appendLine("\n===== Mathematical Edge Engine (V5.0.4529) =====")
            appendLine("  queued=${queued.get()} processed=${processed.get()} dropped=${dropped.get()} coroutine=AppDispatchers.sideEffect authority=report+learning_data_only integrations=UltimateEdgeEngine+CounterfactualReplayEngine+SemanticPatternGraph+AsyncStrategyLab+MultiplierAttributionLedger+ExitCostMicrobrain+CapitalEfficiencyBrain+SourceFamilyOpportunityScorecard+ScoreExpectancyTracker+ExecutionCostPredictorAI+LiveProbabilityEngine+ForwardOutcomeModel+UnifiedPolicyHead+UnifiedExitPolicyHead+StrategyHypothesisEngine+ChokeReliefBus")
            appendLine("  stages: $stageTop")
            appendLine("  lane decisions: $laneTop")
            appendLine("  source decisions: $sourceTop")
            appendLine("  terminal by lane: $terminalLaneTop")
            appendLine("  terminal by source: $terminalSourceTop")
            appendLine("  stack readbacks: $readbackTop")
            appendLine("  stack status: $stackStatus")
            appendLine("  recent:")
            recentLines.forEach { appendLine("    $it") }
        }
    }

    private fun fmt(v: Double, d: Int = 2): String = try { "%.${d}f".format(v) } catch (_: Throwable) { v.toString().take(12) }
}
