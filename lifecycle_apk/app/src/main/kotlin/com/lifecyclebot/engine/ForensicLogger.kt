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

    // V5.0.6362 — batched flush. Old design posted one Runnable per emit, so
    // every call took the underlying MessageQueue lock on the main thread. On
    // hot bursts (30-50 emits/ms) that lock queued and stalled the main
    // thread. Now emits enqueue into a lock-free deque and only ONE drain
    // Runnable is scheduled at a time; the drain sweeps up to BATCH_MAX_DRAIN
    // events per pass, then re-schedules itself if the deque still has items.
    // Effect: one main-thread MessageQueue op per ~128 events instead of one
    // per event. Never blocks on disk — writes happen on ioThread.
    private val eventQueue = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val drainScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val BATCH_MAX_DRAIN = 128

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
        try { PipelineHealthCollector.onPhase(p.tag, symbol, fields) } catch (_: Throwable) {}
    }

    fun gate(p: PHASE, symbol: String, allow: Boolean, reason: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        val mark = if (allow) "✅" else "🚫"
        emitAsync(p, "🧬[${p.tag}] #$n $symbol  $mark $reason")
        try { PipelineHealthCollector.onGate(p.tag, symbol, allow, reason) } catch (_: Throwable) {}
    }

    fun decision(p: PHASE, symbol: String, verdict: String, score: Int, conf: Int, reason: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        emitAsync(p, "🧬[${p.tag}] #$n $symbol  verdict=$verdict score=$score conf=$conf  reason=$reason")
        try { PipelineHealthCollector.onDecision(p.tag, symbol, verdict, score, conf, reason) } catch (_: Throwable) {}
    }

    fun exec(action: String, symbol: String, fields: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        emitAsync(PHASE.EXEC, "🧬[EXEC] #$n $symbol  $action  $fields")
        try { PipelineHealthCollector.onExec(action, symbol, fields) } catch (_: Throwable) {}
    }

    fun lifecycle(event: String, fields: String) {
        if (!enabled) return
        val n = seq.incrementAndGet()
        emitAsync(PHASE.LIFECYCLE, "🧬[LIFECYCLE] #$n $event  $fields")
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
        //
        // V5.0.6362 — batched drain. The `ioHandler.post` per-event pattern
        // still took the MessageQueue lock on every call. Now we enqueue
        // into a lock-free deque and schedule at most one drain Runnable
        // at a time; the drain sweeps up to BATCH_MAX_DRAIN events per
        // pass. Main thread pays one MessageQueue op per burst, not per
        // event.
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
        eventQueue.addLast(line)
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) {
            try {
                ioHandler.post(drainRunnable)
            } catch (_: Throwable) {
                // Post failed (looper shutting down etc.); reset the guard so a
                // future emit can try again.
                drainScheduled.set(false)
            }
        }
    }

    private val drainRunnable: Runnable = Runnable {
        try {
            var drained = 0
            while (drained < BATCH_MAX_DRAIN) {
                val next = eventQueue.pollFirst() ?: break
                try { ErrorLogger.info("FORENSIC", next) } catch (_: Throwable) {}
                pending.decrementAndGet()
                drained += 1
            }
        } finally {
            drainScheduled.set(false)
            // If new events arrived during the drain, re-schedule once so
            // they don't stall waiting for the next emit.
            if (!eventQueue.isEmpty()) scheduleDrain()
        }
    }
}
