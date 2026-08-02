package com.lifecyclebot.engine

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.util.concurrent.ConcurrentHashMap
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

    // V5.0.6368 — CENTRALIZED LOCALE-FREE FORMAT HELPERS (source-of-creation
    // ANR cure). Before this pass, every ~700 forensic call-site called
    // its own `"%.2f".format(x)` which on Android ART hits `Locale.clone`
    // under lock and can stall the main thread by ~200ms per burst. Now
    // callers can (and should) use these helpers which force `Locale.ROOT`
    // and never touch the default-locale lock. Kept as public inline where
    // possible so the JIT can eliminate the boxing.
    private val LR: java.util.Locale = java.util.Locale.ROOT
    fun fmt1(v: Double): String = if (v.isNaN() || v.isInfinite()) "0.0" else String.format(LR, "%.1f", v)
    fun fmt2(v: Double): String = if (v.isNaN() || v.isInfinite()) "0.00" else String.format(LR, "%.2f", v)
    fun fmt4(v: Double): String = if (v.isNaN() || v.isInfinite()) "0.0000" else String.format(LR, "%.4f", v)
    fun fmtPct(v: Double): String = if (v.isNaN() || v.isInfinite()) "0.00%" else String.format(LR, "%.2f%%", v)
    fun fmtUsd(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "$0"
        val a = Math.abs(v)
        return when {
            a >= 1_000_000.0 -> String.format(LR, "$%.2fM", v / 1_000_000.0)
            a >= 1_000.0     -> String.format(LR, "$%.1fk", v / 1_000.0)
            a >= 1.0         -> String.format(LR, "$%.2f", v)
            else             -> String.format(LR, "$%.4f", v)
        }
    }
    fun fmtInt(v: Double): String = if (v.isNaN() || v.isInfinite()) "0" else v.toLong().toString()

    // V5.0.6368 — ZERO-LIQ LANE-EVAL QUARANTINE. Operator snapshot showed
    // dead tokens (V3-rejected as ZERO_LIQUIDITY) still burning cycles across
    // EXPRESS + PROJECT_SNIPER + CASHGEN + BLUECHIP LANE_EVAL emits and
    // health-collector counters for up to 30% of pipeline work per token.
    // Root cause: LANE_EVAL emits were re-fired downstream after the V3
    // reject in the same tick. Fix at source: `lifecycle("REJECTED_FATAL_V3", …)`
    // registers the symbol here; subsequent `phase(LANE_EVAL, symbol, …)`
    // calls short-circuit for QUARANTINE_TTL_MS. No BotService.kt changes.
    private val zeroLiqQuarantine = ConcurrentHashMap<String, Long>()
    private const val QUARANTINE_TTL_MS = 120_000L
    private const val QUARANTINE_MAX_ENTRIES = 4096

    private fun quarantineSymbol(symbol: String) {
        if (symbol.isBlank()) return
        val k = symbol.take(48)
        val now = System.currentTimeMillis()
        // Cheap cap. When the map crosses the ceiling, sweep expired entries.
        if (zeroLiqQuarantine.size > QUARANTINE_MAX_ENTRIES) {
            val cutoff = now - QUARANTINE_TTL_MS
            val it = zeroLiqQuarantine.entries.iterator()
            while (it.hasNext()) { if (it.next().value < cutoff) it.remove() }
        }
        zeroLiqQuarantine[k] = now
    }

    private fun isZeroLiqQuarantined(symbol: String): Boolean {
        if (symbol.isBlank()) return false
        val k = symbol.take(48)
        val ts = zeroLiqQuarantine[k] ?: return false
        val now = System.currentTimeMillis()
        if (now - ts > QUARANTINE_TTL_MS) {
            zeroLiqQuarantine.remove(k, ts)
            return false
        }
        return true
    }

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
        // V5.0.6368 — zero-liq LANE_EVAL suppression at source. If this
        // symbol was V3-rejected as ZERO_LIQUIDITY in the last 2 minutes,
        // drop every LANE_EVAL emit for it AND skip health-collector
        // counters — no bandaid downstream, no ~30% pipeline waste per
        // dead token, no LANE_EVAL choke.
        if (p == PHASE.LANE_EVAL && isZeroLiqQuarantined(symbol)) {
            try { PipelineHealthCollector.labelInc("LANE_EVAL_SUPPRESSED_ZERO_LIQ_6368") } catch (_: Throwable) {}
            return
        }
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
        // V5.0.6368 — hook into REJECTED_FATAL_V3 to populate the zero-liq
        // quarantine set at the true source of the reject. sym=… is emitted
        // by the V3 fatal-reject path in BotService.
        if (event == "REJECTED_FATAL_V3" && fields.contains("ZERO_LIQUIDITY")) {
            val symMarker = "sym="
            val i = fields.indexOf(symMarker)
            if (i >= 0) {
                val j = i + symMarker.length
                var k = j
                while (k < fields.length && fields[k] != ' ' && fields[k] != '\t') k++
                val sym = fields.substring(j, k)
                if (sym.isNotBlank()) quarantineSymbol(sym)
            }
        }
        val n = seq.incrementAndGet()
        emitAsync(PHASE.LIFECYCLE, "🧬[LIFECYCLE] #$n $event  $fields")
        try { PipelineHealthCollector.onLifecycle(event, fields) } catch (_: Throwable) {}
        // V5.0.6405 §7/§14 — CANONICAL EVENT STREAM BRIDGE.
        // Every legacy lifecycle tag is routed through the journal
        // migration adapter. Mapped tags append to the canonical event
        // stream so the operator dashboard and journal read from a
        // single source; unmapped tags are counted (never silently
        // dropped) by JournalMigrationAdapter6405.
        //
        // Recursion guard: skip tags that are themselves emitted by
        // the bridge (JOURNAL_UNMAPPED_TAG_6405 emits from map(); the
        // CANONICAL_EVENT_* family emits from CanonicalEventStream6405
        // .append). Without this, map() would recurse forever on any
        // unmapped tag.
        if (event != "JOURNAL_UNMAPPED_TAG_6405" &&
            !event.startsWith("CANONICAL_EVENT_")
        ) {
            // V5.0.6410 §A — HOT-PATH BRIDGE FAST-EXIT.
            // Operator emergency dump: JOURNAL_UNMAPPED_TAG_6405 = 143 766
            // (~82/sec) with the report builder timing out at 8s and cycles
            // at 65-226s. Every lifecycle emit was doing a 10-branch string
            // scan inside JournalMigrationAdapter6405.map() + an atomic
            // labelInc on the unmapped counter. 99% of lifecycle events do
            // NOT match any canonical prefix, so the cost was pure churn on
            // the emit thread. Short-circuit here: only invoke the adapter
            // when the tag actually contains one of the six canonical
            // prefixes. Skipped tags no longer bump the meaningless
            // JOURNAL_UNMAPPED_TAG_6405 counter — its 143k reading was
            // just "every unmapped lifecycle tag once" which is useless
            // signal.
            val bridgeCandidate6410 = event.contains("BUY_INTENT") ||
                event.contains("BUY_LANDED") ||
                event.contains("BUY_VERIFIED") ||
                event.contains("SELL_INTENT") ||
                event.contains("SELL_LANDED") ||
                event.contains("SELL_VERIFIED") ||
                event.contains("POSITION_TERMINAL") ||
                event.contains("CLOSED_FULL_EXIT") ||
                event.contains("CLOSED_STOP") ||
                event.contains("CLOSED_LIQUIDATED") ||
                event.contains("DECIMAL_INTEGRITY_HARD_BLOCK") ||
                event.contains("SELL_ABORTED_DECIMAL_INTEGRITY") ||
                event.contains("PRICE_INTEGRITY_HARD_BLOCK") ||
                event.contains("DUPLICATE_EXIT_BLOCKED")
            if (bridgeCandidate6410) {
                try {
                    val canonicalType = com.lifecyclebot.engine.truth
                        .JournalMigrationAdapter6405.map(event)
                    if (canonicalType != null) {
                        com.lifecyclebot.engine.truth.CanonicalEventStream6405.append(
                            wallet = "",
                            mint = extractField(fields, "mint"),
                            positionGeneration = 0L,
                            type = canonicalType,
                            source = "FORENSIC_LOGGER_BRIDGE",
                            note = fields.take(200),
                        )
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Tiny helper: extract `key=value` fragment (whitespace-terminated)
     * from a forensic fields string. Used by the canonical-stream
     * bridge to lift `mint=…` out of legacy log lines.
     */
    private fun extractField(fields: String, key: String): String {
        val marker = "$key="
        val i = fields.indexOf(marker)
        if (i < 0) return ""
        val j = i + marker.length
        var k = j
        while (k < fields.length && fields[k] != ' ' && fields[k] != '\t') k++
        return fields.substring(j, k)
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
