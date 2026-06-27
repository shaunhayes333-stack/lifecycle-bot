package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.4308 — report-only Treasury/CashGen cashflow mission telemetry.
 *
 * CashGenerationAI is supposed to be the steady wallet feeder.  Before we
 * alter its ponds/gates/arb feeds, this records the live funnel: evaluated,
 * rejected, accepted, opened, closed, and realized treasury feed.  It has no
 * scoring, sizing, FDG, route, wallet, or execution authority.
 */
object TreasuryCashflowMissionReport {
    private val evaluated = AtomicLong(0)
    private val accepted = AtomicLong(0)
    private val rejected = AtomicLong(0)
    private val opened = AtomicLong(0)
    private val closed = AtomicLong(0)
    private val wins = AtomicLong(0)
    private val losses = AtomicLong(0)
    private val treasuryFeedBps = AtomicLong(0)
    private val byMode = ConcurrentHashMap<String, AtomicLong>()
    private val rejectReasons = ConcurrentHashMap<String, AtomicLong>()
    private val exitReasons = ConcurrentHashMap<String, AtomicLong>()

    private fun inc(map: ConcurrentHashMap<String, AtomicLong>, key: String) {
        map.computeIfAbsent(key.take(120).ifBlank { "UNKNOWN" }) { AtomicLong(0) }.incrementAndGet()
    }

    fun recordEvaluation(symbol: String, mode: String, isPaper: Boolean, liquidityUsd: Double, score: Int, confidence: Int) {
        evaluated.incrementAndGet()
        inc(byMode, "${if (isPaper) "PAPER" else "LIVE"}/$mode")
        try { PipelineHealthCollector.labelInc("TREASURY_EVALUATED_4308") } catch (_: Throwable) {}
        try {
            if (evaluated.get() % 50L == 1L) {
                ForensicLogger.lifecycle(
                    "TREASURY_CASHFLOW_EVAL_4308",
                    "symbol=${symbol.take(16)} mode=$mode paper=$isPaper liq=${liquidityUsd.toInt()} score=$score conf=$confidence total=${evaluated.get()} report_only=true"
                )
            }
        } catch (_: Throwable) {}
    }

    fun recordRejected(reason: String, mode: String, isPaper: Boolean) {
        rejected.incrementAndGet()
        reason.split(',').map { it.trim() }.filter { it.isNotBlank() }.take(6).forEach { inc(rejectReasons, it) }
        try { PipelineHealthCollector.labelInc("TREASURY_REJECTED_4308") } catch (_: Throwable) {}
    }

    fun recordAccepted(reason: String, mode: String, isPaper: Boolean, sizeSol: Double) {
        accepted.incrementAndGet()
        try { PipelineHealthCollector.labelInc("TREASURY_ACCEPTED_4308") } catch (_: Throwable) {}
        try {
            ForensicLogger.lifecycle(
                "TREASURY_CASHFLOW_ACCEPT_4308",
                "mode=$mode paper=$isPaper size=${sizeSol.fmt(4)} reason=${reason.take(180)} report_only=true"
            )
        } catch (_: Throwable) {}
    }

    fun recordOpened(mint: String, symbol: String, isPaper: Boolean, sizeSol: Double, entryScore: Int) {
        opened.incrementAndGet()
        try { PipelineHealthCollector.labelInc("TREASURY_OPENED_4308") } catch (_: Throwable) {}
        try {
            ForensicLogger.lifecycle(
                "TREASURY_CASHFLOW_OPEN_4308",
                "mint=${mint.take(10)} symbol=${symbol.take(16)} paper=$isPaper size=${sizeSol.fmt(4)} entryScore=$entryScore report_only=true"
            )
        } catch (_: Throwable) {}
    }

    fun recordClosed(mint: String, symbol: String, isPaper: Boolean, pnlSol: Double, exitReason: String) {
        closed.incrementAndGet()
        if (pnlSol >= 0.0) wins.incrementAndGet() else losses.incrementAndGet()
        inc(exitReasons, exitReason)
        try { PipelineHealthCollector.labelInc("TREASURY_CLOSED_4308") } catch (_: Throwable) {}
        try {
            ForensicLogger.lifecycle(
                "TREASURY_CASHFLOW_CLOSE_4308",
                "mint=${mint.take(10)} symbol=${symbol.take(16)} paper=$isPaper pnl=${pnlSol.fmt(5)} reason=$exitReason report_only=true"
            )
        } catch (_: Throwable) {}
    }

    fun recordTreasuryFeed(profitSol: Double, isPaper: Boolean) {
        if (profitSol <= 0.0) return
        treasuryFeedBps.addAndGet((profitSol * 10_000.0).toLong())
        try { PipelineHealthCollector.labelInc("TREASURY_FEED_REALIZED_4308") } catch (_: Throwable) {}
    }

    fun status(): String {
        val e = evaluated.get().coerceAtLeast(1L)
        val acceptRate = accepted.get().toDouble() / e.toDouble() * 100.0
        val wrDen = (wins.get() + losses.get()).coerceAtLeast(1L)
        val wr = wins.get().toDouble() / wrDen.toDouble() * 100.0
        val topRejects = rejectReasons.entries.sortedByDescending { it.value.get() }.take(6).joinToString("|") { "${it.key}:${it.value.get()}" }
        val topExits = exitReasons.entries.sortedByDescending { it.value.get() }.take(6).joinToString("|") { "${it.key}:${it.value.get()}" }
        return "TREASURY_CASHFLOW_MISSION_4308 eval=${evaluated.get()} accepted=${accepted.get()} rejected=${rejected.get()} opened=${opened.get()} closed=${closed.get()} wr=${wr.fmt(1)}% acceptRate=${acceptRate.fmt(1)}% fedSol=${(treasuryFeedBps.get()/10000.0).fmt(5)} rejects=$topRejects exits=$topExits report_only=true no_gate_change=true"
    }

    fun emitSnapshot() {
        try {
            ForensicLogger.lifecycle("TREASURY_CASHFLOW_MISSION_4308", status().take(900))
            PipelineHealthCollector.labelInc("TREASURY_CASHFLOW_MISSION_4308")
        } catch (_: Throwable) {}
    }
}

private fun Double.fmt(decimals: Int): String = try { java.lang.String.format(java.util.Locale.US, "% ." + decimals + "f", this).replace(" ", "") } catch (_: Throwable) { this.toString() }
