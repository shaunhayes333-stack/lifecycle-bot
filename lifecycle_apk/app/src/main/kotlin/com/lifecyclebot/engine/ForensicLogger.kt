package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.651 — ForensicLogger
 *
 * Centralized, structured, operator-readable forensic logging for every
 * stage of the trade pipeline. Operator: "I need the entire workings
 * lovable so I can use them to report to you. full forensic logging".
 *
 * EMITTED FORMAT (one INFO line per call):
 *   🧬[PHASE] #seq SYMBOL  field=val field=val ...
 *
 * Each phase emits at INFO level so it survives operator log exports
 * (which capture INFO/WARN/ERROR but drop DEBUG).
 *
 * Forensic mode can be DISABLED by setting ForensicLogger.enabled=false
 * (e.g. for production live mode where log volume is a concern).
 */
object ForensicLogger {

    /** Master switch. Default: ON. Operator requested maximum visibility. */
    @Volatile var enabled: Boolean = true

    /** Counter for sequencing — every emit gets a monotonic seq# */
    private val seq = AtomicLong(0L)

    // V5.9.1095 — forensic logging can be extremely hot during storms. Keep
    // collector counters flowing, but bound ErrorLogger/logcat/SQLite pressure.
    private val logWindowStartMs = AtomicLong(System.currentTimeMillis())
    private val logWindowCount = AtomicInteger(0)
    private val droppedForensicLogs = AtomicLong(0L)
    private val lastDropTelemetryMs = AtomicLong(0L)
    private const val LOG_WINDOW_MS = 10_000L
    private const val MAX_ERROR_LOG_LINES_PER_WINDOW = 600
    private const val DROP_TELEMETRY_INTERVAL_MS = 5_000L

    private fun shouldEmitErrorLog(): Boolean {
        val now = System.currentTimeMillis()
        val start = logWindowStartMs.get()
        if (now - start > LOG_WINDOW_MS && logWindowStartMs.compareAndSet(start, now)) {
            logWindowCount.set(0)
        }
        val n = logWindowCount.incrementAndGet()
        if (n <= MAX_ERROR_LOG_LINES_PER_WINDOW) return true
        val dropped = droppedForensicLogs.incrementAndGet()
        val prev = lastDropTelemetryMs.get()
        if (now - prev >= DROP_TELEMETRY_INTERVAL_MS && lastDropTelemetryMs.compareAndSet(prev, now)) {
            try { PipelineHealthCollector.onLifecycle("FORENSIC_LOG_DROPPED_RATE_LIMITED", "count=$dropped windowCount=$n") } catch (_: Throwable) {}
        }
        return false
    }

    enum class PHASE(val tag: String) {
        INTAKE       ("INTAKE"),
        QUEUE        ("QUEUE"),
        SCAN_SOURCE  ("SCAN_SRC"),
        SCAN_CB      ("SCAN_CB"),
        WATCHLIST    ("WATCHLIST"),
        TICK         ("TICK"),
        SAFETY       ("SAFETY"),
        V3           ("V3"),
        LANE_EVAL    ("LANE_EVAL"),
        LANE_DEC     ("LANE_DEC"),
        FDG          ("FDG"),
        PERMIT       ("PERMIT"),
        EXEC         ("EXEC"),
        EXIT_GATE    ("EXIT"),
        LIFECYCLE    ("LIFECYCLE"),
        WATCHDOG     ("WATCHDOG"),
        SCANNER_HEAL ("HEAL"),
    }

    fun phase(p: PHASE, symbol: String, fields: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        if (shouldEmitErrorLog()) ErrorLogger.info("FORENSIC", "🧬[${p.tag}] #$n $symbol  ${fields.take(700)}")
        try { PipelineHealthCollector.onPhase(p.tag, symbol, fields) } catch (_: Throwable) {}
    }

    fun gate(p: PHASE, symbol: String, allow: Boolean, reason: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        val mark = if (allow) "✅" else "🚫"
        if (shouldEmitErrorLog()) ErrorLogger.info("FORENSIC", "🧬[${p.tag}] #$n $symbol  $mark ${reason.take(700)}")
        try { PipelineHealthCollector.onGate(p.tag, symbol, allow, reason) } catch (_: Throwable) {}
    }

    fun decision(p: PHASE, symbol: String, verdict: String, score: Int, conf: Int, reason: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        if (shouldEmitErrorLog()) ErrorLogger.info("FORENSIC", "🧬[${p.tag}] #$n $symbol  verdict=$verdict score=$score conf=$conf  reason=${reason.take(500)}")
        try { PipelineHealthCollector.onDecision(p.tag, symbol, verdict, score, conf, reason) } catch (_: Throwable) {}
    }

    fun exec(action: String, symbol: String, fields: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        if (shouldEmitErrorLog()) ErrorLogger.info("FORENSIC", "🧬[EXEC] #$n $symbol  $action  ${fields.take(700)}")
        try { PipelineHealthCollector.onExec(action, symbol, fields) } catch (_: Throwable) {}
    }

    fun lifecycle(event: String, fields: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        if (shouldEmitErrorLog()) ErrorLogger.info("FORENSIC", "🧬[LIFECYCLE] #$n $event  ${fields.take(700)}")
        try { PipelineHealthCollector.onLifecycle(event, fields) } catch (_: Throwable) {}
    }

    fun tick(symbol: String, stage: String, ms: Long, extra: String = "") {
        if (!enabled) return
        val n = seq.incrementAndGet()
        if (shouldEmitErrorLog()) ErrorLogger.info("FORENSIC", "🧬[TICK] #$n $symbol  $stage  ${ms}ms  ${extra.take(500)}")
    }

    fun snapshot(label: String, fields: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        if (shouldEmitErrorLog()) ErrorLogger.info("FORENSIC", "🧬[$label] #$n ${fields.take(700)}")
        try { PipelineHealthCollector.onSnapshot(label, fields) } catch (_: Throwable) {}
    }
}
