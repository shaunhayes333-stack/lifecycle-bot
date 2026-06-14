package com.lifecyclebot.engine

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
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
    private val ioThread: HandlerThread by lazy { HandlerThread("ForensicLoggerIO", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() } }
    private val ioHandler: Handler by lazy { Handler(ioThread.looper) }
    private val pending = AtomicInteger(0)
    private const val MAX_PENDING = 500

    /** Master switch. Default: ON. Operator requested maximum visibility. */
    @Volatile var enabled: Boolean = true

    /** Counter for sequencing — every emit gets a monotonic seq# */
    private val seq = AtomicLong(0L)

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
        EXEC_GATE    ("EXEC_GATE"),
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
        emitAsync(p, "🧬[${p.tag}] #$n $symbol  $fields")
        try { TradeMechanicsTrace.record("Forensic", "PHASE/${p.tag}", symbol, fields) } catch (_: Throwable) {}
        try { PipelineHealthCollector.onPhase(p.tag, symbol, fields) } catch (_: Throwable) {}
    }

    fun gate(p: PHASE, symbol: String, allow: Boolean, reason: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        val mark = if (allow) "✅" else "🚫"
        emitAsync(p, "🧬[${p.tag}] #$n $symbol  $mark $reason")
        try { TradeMechanicsTrace.record("Forensic", "GATE/${p.tag}/${if (allow) "ALLOW" else "BLOCK"}", symbol, reason) } catch (_: Throwable) {}
        try { PipelineHealthCollector.onGate(p.tag, symbol, allow, reason) } catch (_: Throwable) {}
    }

    fun decision(p: PHASE, symbol: String, verdict: String, score: Int, conf: Int, reason: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        emitAsync(p, "🧬[${p.tag}] #$n $symbol  verdict=$verdict score=$score conf=$conf  reason=$reason")
        try { TradeMechanicsTrace.record("Forensic", "DEC/${p.tag}/$verdict", symbol, "score=$score conf=$conf reason=$reason") } catch (_: Throwable) {}
        try { PipelineHealthCollector.onDecision(p.tag, symbol, verdict, score, conf, reason) } catch (_: Throwable) {}
    }

    fun exec(action: String, symbol: String, fields: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        emitAsync(PHASE.EXEC, "🧬[EXEC] #$n $symbol  $action  $fields")
        try { TradeMechanicsTrace.record("Forensic", "EXEC/$action", symbol, fields) } catch (_: Throwable) {}
        try { PipelineHealthCollector.onExec(action, symbol, fields) } catch (_: Throwable) {}
    }

    fun lifecycle(event: String, fields: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        emitAsync(PHASE.LIFECYCLE, "🧬[LIFECYCLE] #$n $event  $fields")
        try { TradeMechanicsTrace.record("Forensic", "LIFECYCLE/$event", event, fields) } catch (_: Throwable) {}
        try { PipelineHealthCollector.onLifecycle(event, fields) } catch (_: Throwable) {}
    }

    fun tick(symbol: String, stage: String, ms: Long, extra: String = "") {
        if (!enabled) return
        val n = seq.incrementAndGet()
        emitAsync(PHASE.TICK, "🧬[TICK] #$n $symbol  $stage  ${ms}ms  $extra")
    }

    fun snapshot(label: String, fields: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        emitAsync(PHASE.LIFECYCLE, "🧬[$label] #$n $fields")
        try { PipelineHealthCollector.onSnapshot(label, fields) } catch (_: Throwable) {}
    }

    private fun emitAsync(phase: PHASE, line: String) {
        // V5.0.3680 — operator forensic snapshot showed emitAsync stalling
        // the main thread for 1012ms (despite the name "Async"). Root cause:
        // when the IO HandlerThread backs up under high write volume, the
        // ioHandler.post() call still takes the underlying MessageQueue
        // lock, and the JIT-inlined caller path (V3/LANE_EVAL/LIFECYCLE
        // emits at thousands of events/sec) was blocking the main thread.
        // Two-part fix: (1) drop more aggressively when pending backlog is
        // material — raise the dropped phase set so any high-volume non-
        // critical phase falls through fast; (2) cheaper queue probe.
        if (!enabled) return
        val p = pending.get()
        if (p > MAX_PENDING) {
            // Drop non-critical phases first; keep EXEC + DECISION + SAFETY
            // because operator dashboards depend on them.
            when (phase) {
                PHASE.LANE_EVAL, PHASE.FDG, PHASE.LIFECYCLE,
                PHASE.V3, PHASE.SCAN_CB, PHASE.TICK,
                PHASE.WATCHLIST, PHASE.INTAKE, PHASE.SCAN_SOURCE,
                PHASE.QUEUE -> return
                else -> { /* allow EXEC / SAFETY / LANE_DEC / EXEC_GATE / PERMIT */ }
            }
        }
        // V5.0.3680 — hard ceiling so we never queue beyond 2× MAX_PENDING.
        if (p > MAX_PENDING * 2) return
        pending.incrementAndGet()
        try {
            ioHandler.post {
                try { ErrorLogger.info("FORENSIC", line) } finally { pending.decrementAndGet() }
            }
        } catch (_: Throwable) {
            // Post failed (looper shutting down etc.); decrement so we don't leak the counter.
            pending.decrementAndGet()
        }
    }
}
