package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** V5.0.4312 — report-only ShitCoin/MemeTrader core decision matrix. */
object ShitCoinDecisionMatrixReport {
    private val rejected = AtomicLong(0)
    private val accepted = AtomicLong(0)
    private val opened = AtomicLong(0)
    private val closed = AtomicLong(0)
    private val wins = AtomicLong(0)
    private val losses = AtomicLong(0)
    private val rejectReasons = ConcurrentHashMap<String, AtomicLong>()
    private val exitReasons = ConcurrentHashMap<String, AtomicLong>()
    private val platforms = ConcurrentHashMap<String, AtomicLong>()

    private fun inc(map: ConcurrentHashMap<String, AtomicLong>, key: String) {
        map.computeIfAbsent(key.take(96).ifBlank { "UNKNOWN" }) { AtomicLong(0) }.incrementAndGet()
    }

    fun recordReject(reason: String, mode: String, platform: String, isPaper: Boolean) {
        rejected.incrementAndGet(); inc(rejectReasons, reason.substringBefore(':').take(64)); inc(platforms, "${if (isPaper) "PAPER" else "LIVE"}/$platform/$mode")
        ChokeReliefBus.launch("SHITCOIN_MATRIX_REJECT_4312") { PipelineHealthCollector.labelInc("SHITCOIN_MATRIX_REJECT_4312") }
    }

    fun recordAccepted(reason: String, score: Int, confidence: Int, mode: String, platform: String, isPaper: Boolean, sizeSol: Double) {
        accepted.incrementAndGet(); inc(platforms, "${if (isPaper) "PAPER" else "LIVE"}/$platform/$mode")
        ChokeReliefBus.launch("SHITCOIN_MATRIX_ACCEPT_4312") {
            PipelineHealthCollector.labelInc("SHITCOIN_MATRIX_ACCEPT_4312")
            ForensicLogger.lifecycle("SHITCOIN_MATRIX_ACCEPT_4312", "score=$score conf=$confidence mode=$mode platform=$platform paper=$isPaper size=${sizeSol.fmtLocal(4)} reason=${reason.take(180)} report_only=true")
        }
    }

    fun recordOpened(mint: String, symbol: String, platform: String, isPaper: Boolean, sizeSol: Double, entryScore: Int) {
        opened.incrementAndGet(); inc(platforms, "${if (isPaper) "PAPER" else "LIVE"}/$platform")
        ChokeReliefBus.launch("SHITCOIN_MATRIX_OPEN_4312", mint) { PipelineHealthCollector.labelInc("SHITCOIN_MATRIX_OPEN_4312") }
    }

    fun recordClosed(mint: String, symbol: String, isPaper: Boolean, pnlPct: Double, pnlSol: Double, exitReason: String, entryScore: Int) {
        closed.incrementAndGet(); if (pnlPct > 0.0) wins.incrementAndGet() else losses.incrementAndGet(); inc(exitReasons, exitReason)
        ChokeReliefBus.launch("SHITCOIN_MATRIX_CLOSE_4312", mint) {
            PipelineHealthCollector.labelInc("SHITCOIN_MATRIX_CLOSE_4312")
            ForensicLogger.lifecycle("SHITCOIN_MATRIX_CLOSE_4312", "mint=${mint.take(10)} symbol=${symbol.take(16)} paper=$isPaper pnlPct=${pnlPct.fmtLocal(2)} pnlSol=${pnlSol.fmtLocal(5)} reason=$exitReason entryScore=$entryScore report_only=true")
        }
    }

    fun status(): String {
        val total = (wins.get() + losses.get()).coerceAtLeast(1L)
        val wr = wins.get().toDouble() / total.toDouble() * 100.0
        val topRejects = rejectReasons.entries.sortedByDescending { it.value.get() }.take(6).joinToString("|") { "${it.key}:${it.value.get()}" }
        val topExits = exitReasons.entries.sortedByDescending { it.value.get() }.take(6).joinToString("|") { "${it.key}:${it.value.get()}" }
        return "SHITCOIN_DECISION_MATRIX_4312 accepted=${accepted.get()} rejected=${rejected.get()} opened=${opened.get()} closed=${closed.get()} wr=${wr.fmtLocal(1)}% rejects=$topRejects exits=$topExits report_only=true no_gate_change=true"
    }
}
private fun Double.fmtLocal(decimals: Int): String = try { java.lang.String.format(java.util.Locale.US, "% ." + decimals + "f", this).replace(" ", "") } catch (_: Throwable) { this.toString() }
