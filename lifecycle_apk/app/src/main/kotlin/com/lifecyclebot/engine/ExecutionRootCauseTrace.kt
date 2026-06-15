package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.3754 — structured buy/sell root-cause trace.
 *
 * Grep labels:
 *   EXEC_TRACE_BUY
 *   EXEC_TRACE_SELL
 *   EXEC_TRACE_AUTHORITY
 *   EXEC_TRACE_ROUTE
 *   EXEC_TRACE_FINALITY
 *
 * Format is stable k=v pairs so a pasted operational report can be filtered
 * into a causal timeline: authority -> sizing -> route -> broadcast -> finality.
 */
object ExecutionRootCauseTrace {
    private fun clean(v: Any?): String = try {
        val s = (v ?: "-").toString()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('|', '/')
        if (s.length > 180) s.take(180) else s
    } catch (_: Throwable) { "?" }

    private fun base(ts: TokenState?): String = if (ts == null) {
        "mint=- symbol=- lane=- paper=- open=- qty=- entry=- last=-"
    } else {
        "mint=${clean(ts.mint.take(10))} symbol=${clean(ts.symbol)} lane=${clean(ts.position.tradingMode.ifBlank { ts.laneAffinity.firstOrNull() ?: "UNKNOWN" })} " +
            "paper=${ts.position.isPaperPosition} open=${ts.position.isOpen} qty=${clean(ts.position.qtyToken)} entry=${clean(ts.position.entryPrice)} last=${clean(ts.lastPrice)}"
    }

    private fun emit(label: String, side: String, stage: String, ts: TokenState?, fields: String = "") {
        val gen = try { BotRuntimeController.currentGeneration() } catch (_: Throwable) { 0L }
        val mode = try { RuntimeModeAuthority.authority().name } catch (_: Throwable) { "UNKNOWN" }
        val msg = "side=$side stage=${clean(stage)} gen=$gen mode=$mode ${base(ts)} ${fields}".trim()
        try { ForensicLogger.lifecycle(label, msg) } catch (_: Throwable) {}
        try { ErrorLogger.info("ExecTrace", "$label $msg") } catch (_: Throwable) {}
    }

    fun buy(stage: String, ts: TokenState?, fields: String = "") = emit("EXEC_TRACE_BUY", "BUY", stage, ts, fields)
    fun sell(stage: String, ts: TokenState?, fields: String = "") = emit("EXEC_TRACE_SELL", "SELL", stage, ts, fields)
    fun authority(side: String, stage: String, ts: TokenState?, fields: String = "") = emit("EXEC_TRACE_AUTHORITY", side, stage, ts, fields)
    fun route(side: String, stage: String, ts: TokenState?, fields: String = "") = emit("EXEC_TRACE_ROUTE", side, stage, ts, fields)
    fun finality(side: String, stage: String, ts: TokenState?, fields: String = "") = emit("EXEC_TRACE_FINALITY", side, stage, ts, fields)
}
