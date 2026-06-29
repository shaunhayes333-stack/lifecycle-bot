package com.lifecyclebot.engine

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
        val pnlPct: Double = 0.0,
        val pnlSol: Double = 0.0,
        val holdMs: Long = 0L,
        val peakGainPct: Double = 0.0,
        val maxDrawdownPct: Double = 0.0,
        val trainable: Boolean = false,
        val accepted: Boolean = true,
        val components: String = "",
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
            i++
        }
    }

    private fun remember(e: EdgeEvent) = synchronized(recentLock) {
        recent.addLast("${e.kind} stage=${e.stage} lane=${e.lane} src=${e.source} dec=${e.decision} score=${fmt(e.score,1)} conf=${fmt(e.confidence,1)} liq=${fmt(e.liquidityUsd,0)} mcap=${fmt(e.marketCapUsd,0)} size=${fmt(e.finalSol,4)} pnl=${fmt(e.pnlPct,1)}%/${fmt(e.pnlSol,4)}SOL hold=${e.holdMs/60000}m peak=${fmt(e.peakGainPct,1)} reason=${e.reason.take(70)} mint=${e.mint.take(10)} build=${e.build}".take(420))
        while (recent.size > MAX_RECENT) recent.removeFirst()
    }

    fun captureEntryOpportunity(stage: String, lane: String, source: String, mint: String, symbol: String, decision: String, reason: String, score: Double, confidence: Double, liquidityUsd: Double, marketCapUsd: Double, proposedSol: Double = -1.0, finalSol: Double = -1.0, style: String = "", regime: String = "") = submit(EdgeEvent(
        kind = "ENTRY", stage = stage, lane = lane, source = source, mint = mint, symbol = symbol, decision = decision, reason = reason,
        score = score, confidence = confidence, liquidityUsd = liquidityUsd, marketCapUsd = marketCapUsd, proposedSol = proposedSol, finalSol = finalSol, style = style, regime = regime,
    ))

    fun captureSizing(stage: String, lane: String, source: String, mint: String, symbol: String, baseSol: Double, rawMultiplier: Double, clampedMultiplier: Double, finalSol: Double, walletSol: Double, liquidityUsd: Double, score: Double, components: Map<String, Double>, reason: String = "") = submit(EdgeEvent(
        kind = "SIZING", stage = stage, lane = lane, source = source, mint = mint, symbol = symbol, decision = "SIZING", reason = reason,
        score = score, liquidityUsd = liquidityUsd, baseSol = baseSol, finalSol = finalSol, walletSol = walletSol, rawMultiplier = rawMultiplier, clampedMultiplier = clampedMultiplier,
        components = components.entries.filter { kotlin.math.abs(it.value - 1.0) > 0.001 }.sortedBy { it.value }.take(12).joinToString(",") { "${it.key}=${fmt(it.value,3)}" },
    ))

    fun captureTerminal(stage: String, lane: String, source: String, mint: String, symbol: String, side: String, reason: String, pnlPct: Double, pnlSol: Double, sizeSol: Double, holdMs: Long, peakGainPct: Double, maxDrawdownPct: Double, trainable: Boolean, accepted: Boolean) = submit(EdgeEvent(
        kind = "TERMINAL", stage = stage, lane = lane, source = source, mint = mint, symbol = symbol, decision = if (accepted && trainable) "TERMINAL_TRAINABLE" else if (accepted) "TERMINAL_UNTRAINABLE" else "TERMINAL_SUPPRESSED", reason = "$side:$reason",
        pnlPct = pnlPct, pnlSol = pnlSol, finalSol = sizeSol, holdMs = holdMs, peakGainPct = peakGainPct, maxDrawdownPct = maxDrawdownPct, trainable = trainable, accepted = accepted,
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
        return buildString {
            appendLine("\n===== Mathematical Edge Engine (V5.0.4529) =====")
            appendLine("  queued=${queued.get()} processed=${processed.get()} dropped=${dropped.get()} coroutine=AppDispatchers.sideEffect authority=report+learning_data_only")
            appendLine("  stages: $stageTop")
            appendLine("  lane decisions: $laneTop")
            appendLine("  source decisions: $sourceTop")
            appendLine("  terminal by lane: $terminalLaneTop")
            appendLine("  terminal by source: $terminalSourceTop")
            appendLine("  recent:")
            recentLines.forEach { appendLine("    $it") }
        }
    }

    private fun fmt(v: Double, d: Int = 2): String = try { "%.${d}f".format(v) } catch (_: Throwable) { v.toString().take(12) }
}
