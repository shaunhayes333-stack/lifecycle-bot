package com.lifecyclebot.engine

import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.666 — PipelineHealthCollector
 *
 * In-app mirror of the CI runtime-test funnel summary, plus ANR
 * detection and a ring buffer of recent forensic events. Operator
 * mandate: "add the pipeline health panel. make it exportable to
 * the clipboard. add as much logging detail you can think will help
 * us including anr errors and their reasons."
 *
 * Wiring strategy:
 *   - ForensicLogger is the single chokepoint for every phase emit
 *     (INTAKE / SAFETY / V3 / LANE_EVAL / FDG / EXEC / EXIT_GATE etc.).
 *     PipelineHealthCollector is incremented inside each ForensicLogger
 *     entry point, so adding new call sites costs nothing — they
 *     automatically register here too.
 *   - TradeHistoryStore wires its own `recordTradeJournal()` call
 *     for the TRADEJRNL_REC counter (the CI funnel's truth that
 *     trades actually persist).
 *   - WalletPositionLock / CryptoUniverseExecutor route their gate
 *     events here too via the `event(...)` API so the operator can
 *     see lane-cap blocks, late-trust-sig fallbacks, and route-forced-
 *     paper events without scrolling logcat.
 *   - ANR detector runs a Choreographer.FrameCallback that measures
 *     time between frames; long gaps (> 700ms) are surfaced as
 *     ANR_HINT events with the elapsed delta.
 *
 * Zero overhead when forensic logging is enabled (we just bump
 * atomic counters). Read paths take a snapshot under a read lock.
 */
object PipelineHealthCollector {

    // ════════════════════════════════════════════════════════════════
    // Counters — atomic, non-blocking, monotonically increasing.
    // ════════════════════════════════════════════════════════════════

    /** Phase counters keyed by phase tag (e.g. "INTAKE", "SAFETY", "V3"). */
    private val phaseCounts = ConcurrentHashMap<String, AtomicLong>()

    /** Phase ALLOW (✅ gate) counts. */
    private val phaseAllow = ConcurrentHashMap<String, AtomicLong>()

    /** Phase BLOCK (🚫 gate) counts. */
    private val phaseBlock = ConcurrentHashMap<String, AtomicLong>()

    /** Decision verdict tallies (e.g. EXECUTE_AGGRESSIVE, REJECT, WATCH). */
    private val verdictCounts = ConcurrentHashMap<String, AtomicLong>()

    /** Custom labelled counters (e.g. lane name, error class). */
    private val labelCounts = ConcurrentHashMap<String, AtomicLong>()

    /** ANR-style hint counter (Choreographer long-frame events). */
    private val anrHintCount = AtomicInteger(0)

    /** Long-frame max delta seen (ms). */
    private val maxFrameGapMs = AtomicLong(0L)

    /** Total long-frame ms accumulated (for "time spent stuttering"). */
    private val totalFrameStallMs = AtomicLong(0L)

    /** Started-at epoch ms (for uptime in dump). */
    private val startedAtMs = AtomicLong(0L)

    // ════════════════════════════════════════════════════════════════
    // Event ring buffer — last N forensic events for clipboard export.
    // ════════════════════════════════════════════════════════════════

    private const val RING_CAP = 200

    data class Event(
        val tsMs: Long,
        val tag: String,         // e.g. "PHASE", "GATE_BLOCK", "ANR_HINT", "WALLET_LOCK"
        val symbol: String,      // optional, "" if N/A
        val message: String,
    )

    // V5.9.668 — switched from synchronized ArrayDeque to a lock-free
    // ConcurrentLinkedDeque + AtomicInteger size counter so callers
    // on hot pipeline paths (INTAKE, SAFETY, SCAN_CB at 5+ events/s)
    // never block waiting on the UI panel's snapshot() reader. The
    // operator's forensics dump showed 1400+ phase events in ~5 min;
    // any synchronization contention at that volume compounds.
    private val ring = java.util.concurrent.ConcurrentLinkedDeque<Event>()
    private val ringSize = java.util.concurrent.atomic.AtomicInteger(0)

    /** Pluggable hook so other singletons can add events without depending on this class first. */
    @Suppress("unused")
    @Volatile var attached: Boolean = true

    // ════════════════════════════════════════════════════════════════
    // Forensic hooks — called from ForensicLogger.
    // ════════════════════════════════════════════════════════════════

    fun onPhase(phaseTag: String, symbol: String, fields: String) {
        if (!attached) return
        bump(phaseCounts, phaseTag)
        if (phaseTag == "SCAN_CB" && fields.contains("BOT_LOOP_TICK")) {
            bump(labelCounts, "BOT_LOOP_TICK")
        }
        appendEvent(Event(System.currentTimeMillis(), "PHASE/$phaseTag", symbol, fields.take(220)))
    }

    fun onGate(phaseTag: String, symbol: String, allow: Boolean, reason: String) {
        if (!attached) return
        bump(phaseCounts, phaseTag)
        if (allow) bump(phaseAllow, phaseTag) else bump(phaseBlock, phaseTag)
        appendEvent(Event(
            System.currentTimeMillis(),
            if (allow) "GATE_ALLOW/$phaseTag" else "GATE_BLOCK/$phaseTag",
            symbol,
            reason.take(220),
        ))
    }

    fun onDecision(phaseTag: String, symbol: String, verdict: String, score: Int, conf: Int, reason: String) {
        if (!attached) return
        bump(phaseCounts, phaseTag)
        bump(verdictCounts, verdict)
        appendEvent(Event(
            System.currentTimeMillis(),
            "DEC/$phaseTag/$verdict",
            symbol,
            "score=$score conf=$conf $reason".take(220),
        ))
    }

    fun onExec(action: String, symbol: String, fields: String) {
        if (!attached) return
        bump(phaseCounts, "EXEC")
        bump(labelCounts, "EXEC/$action")
        appendEvent(Event(System.currentTimeMillis(), "EXEC/$action", symbol, fields.take(220)))
    }

    fun onLifecycle(event: String, fields: String) {
        if (!attached) return
        bump(labelCounts, "LIFECYCLE/$event")
        appendEvent(Event(System.currentTimeMillis(), "LIFECYCLE/$event", "", fields.take(220)))
    }

    fun onSnapshot(label: String, fields: String) {
        if (!attached) return
        bump(labelCounts, "SNAP/$label")
        appendEvent(Event(System.currentTimeMillis(), "SNAP/$label", "", fields.take(220)))
    }

    /**
     * Generic event injection point. Used by callers outside
     * ForensicLogger (WalletPositionLock, CryptoUniverseExecutor, etc.)
     * to surface lane-specific signals without polluting the phase
     * vocabulary.
     */
    fun event(tag: String, symbol: String, message: String) {
        if (!attached) return
        bump(labelCounts, tag)
        appendEvent(Event(System.currentTimeMillis(), tag, symbol, message.take(220)))
    }

    /** Hook for TradeHistoryStore — tracked separately because CI funnel cares about it. */
    fun onTradeJournal() {
        if (!attached) return
        bump(labelCounts, "TRADEJRNL_REC")
    }

    // ════════════════════════════════════════════════════════════════
    // ANR detector — Choreographer-based main-thread health monitor.
    // ════════════════════════════════════════════════════════════════

    private const val LONG_FRAME_THRESHOLD_MS = 700L  // > 700ms gap = "the bot froze"
    @Volatile private var anrInstalled = false
    private var lastFrameNs: Long = 0L

    /**
     * Install the main-thread frame monitor. Idempotent; safe to call
     * from BotService.onCreate. Must be called on the main thread (the
     * Choreographer scope) — caller is responsible for that.
     *
     * V5.9.668 — when a long frame is detected, capture the MAIN
     * thread's stack trace at that exact moment. The captured frames
     * are appended to the ANR_HINT event message so the operator can
     * see EXACTLY which method was blocking for the duration of the
     * freeze. Read-only sampling, no synchronization, no behaviour
     * change to the rest of the bot.
     */
    fun installAnrWatcherOnMainThread() {
        if (anrInstalled) return
        if (Looper.myLooper() != Looper.getMainLooper()) return
        anrInstalled = true
        if (startedAtMs.get() == 0L) startedAtMs.set(System.currentTimeMillis())
        val choreo = try { Choreographer.getInstance() } catch (_: Throwable) { return }
        val mainThread = Looper.getMainLooper().thread
        choreo.postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val prev = lastFrameNs
                lastFrameNs = frameTimeNanos
                if (prev != 0L) {
                    val deltaMs = (frameTimeNanos - prev) / 1_000_000L
                    if (deltaMs > LONG_FRAME_THRESHOLD_MS) {
                        anrHintCount.incrementAndGet()
                        totalFrameStallMs.addAndGet(deltaMs)
                        var prevMax = maxFrameGapMs.get()
                        while (deltaMs > prevMax && !maxFrameGapMs.compareAndSet(prevMax, deltaMs)) {
                            prevMax = maxFrameGapMs.get()
                        }
                        // V5.9.668 — capture the main-thread stack trace at
                        // the moment the long-frame is observed. The trace
                        // shows where the main thread is RIGHT NOW, which
                        // for a sustained freeze is almost certainly the
                        // method that just unblocked (or is still blocking).
                        // Trim to top frames + AATE-package frames for
                        // readability; full Object.wait() / ZygoteInit /
                        // Looper.loop() depths are not useful.
                        val trace = try {
                            captureMainThreadStack(mainThread)
                        } catch (_: Throwable) { "(stack capture failed)" }
                        appendEvent(Event(
                            System.currentTimeMillis(),
                            "ANR_HINT",
                            "",
                            "main thread frame gap=${deltaMs}ms (>${LONG_FRAME_THRESHOLD_MS}ms threshold) " +
                                "— main thread was blocked. Top frames:\n$trace",
                        ))
                    }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    /**
     * Capture a compact main-thread stack-trace string. Uses
     * Thread.getStackTrace() (cheap, non-blocking) and filters
     * down to AATE-package frames + the top 4 standard frames so
     * the operator sees the actual culprit quickly.
     */
    private fun captureMainThreadStack(t: Thread): String {
        val frames = t.stackTrace ?: return "(no frames)"
        if (frames.isEmpty()) return "(empty stack)"
        val sb = StringBuilder()
        var emitted = 0
        for (f in frames) {
            val cls = f.className ?: ""
            val isOurs = cls.startsWith("com.lifecyclebot")
            // Always include first 4 frames (top of stack), plus all our package frames.
            if (emitted < 4 || isOurs) {
                sb.append("    at ").append(cls).append('.').append(f.methodName)
                if (f.fileName != null) {
                    sb.append('(').append(f.fileName).append(':').append(f.lineNumber).append(')')
                }
                sb.append('\n')
                emitted++
                if (emitted >= 18) break
            }
        }
        return sb.toString().trimEnd()
    }

    // ════════════════════════════════════════════════════════════════
    // Read paths — snapshot + clipboard dump.
    // ════════════════════════════════════════════════════════════════

    data class Snapshot(
        val startedAtMs: Long,
        val nowMs: Long,
        val phaseCounts: Map<String, Long>,
        val phaseAllow: Map<String, Long>,
        val phaseBlock: Map<String, Long>,
        val verdictCounts: Map<String, Long>,
        val labelCounts: Map<String, Long>,
        val anrHints: Int,
        val maxFrameGapMs: Long,
        val totalFrameStallMs: Long,
        val recentEvents: List<Event>,
    )

    fun snapshot(): Snapshot {
        // V5.9.668 — lock-free read. ConcurrentLinkedDeque.toList()
        // is a weak-consistency snapshot (may include in-flight
        // additions), which is fine for telemetry display. Crucially
        // it never blocks the UI poll, even if 5+ pipeline threads
        // are appending simultaneously.
        val events = ring.toList()
        return Snapshot(
            startedAtMs        = startedAtMs.get(),
            nowMs              = System.currentTimeMillis(),
            phaseCounts        = phaseCounts.mapValues { it.value.get() },
            phaseAllow         = phaseAllow.mapValues  { it.value.get() },
            phaseBlock         = phaseBlock.mapValues  { it.value.get() },
            verdictCounts      = verdictCounts.mapValues { it.value.get() },
            labelCounts        = labelCounts.mapValues { it.value.get() },
            anrHints           = anrHintCount.get(),
            maxFrameGapMs      = maxFrameGapMs.get(),
            totalFrameStallMs  = totalFrameStallMs.get(),
            recentEvents       = events,
        )
    }

    /**
     * Render a CI-funnel-equivalent text dump suitable for clipboard
     * export, paste into a bug report, or share back to the operator's
     * agent for triage.
     */
    fun dumpText(): String {
        val s = snapshot()
        val df = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val uptimeSec = if (s.startedAtMs > 0) ((s.nowMs - s.startedAtMs) / 1000L) else 0L

        fun line(label: String, value: Any?, hint: String = ""): String {
            val v = value?.toString() ?: "0"
            val h = if (hint.isNotEmpty()) "  ($hint)" else ""
            return "  ${label.padEnd(24)}$v$h"
        }

        val sb = StringBuilder(8 * 1024)
        sb.append("===== AATE Pipeline Health Snapshot =====\n")
        sb.append("  Captured at:           ${df.format(Date(s.nowMs))}\n")
        sb.append("  Uptime since start:    ${uptimeSec}s\n")
        sb.append("  Forensic logging:      ${if (ForensicLogger.enabled) "ON" else "OFF"}\n")
        sb.append("\n")

        sb.append("===== Pipeline funnel (in-app mirror of CI funnel) =====\n")
        val phasesOfInterest = listOf(
            "BOT_LOOP_TICK" to "loop iterations",
            "SCAN_CB"       to "processTokenCycle invocations",
            "INTAKE"        to "watchlist intake events",
            "SAFETY"        to "safety checks",
            "V3"            to "V3 engine ticks",
            "LANE_EVAL"     to "lane evaluations",
            "FDG"           to "FinalDecisionGate evaluations",
            "EXEC"          to "executor invocations",
            "EXIT"          to "exit gate evaluations",
            "TRADEJRNL_REC" to "journal writes",
        )
        phasesOfInterest.forEach { (k, hint) ->
            val v = s.phaseCounts[k] ?: s.labelCounts[k] ?: 0L
            sb.append(line("$k:", v, hint)).append('\n')
        }
        sb.append('\n')

        if (s.phaseAllow.isNotEmpty() || s.phaseBlock.isNotEmpty()) {
            sb.append("===== Gate allow / block tally =====\n")
            val allKeys = (s.phaseAllow.keys + s.phaseBlock.keys).distinct().sorted()
            for (k in allKeys) {
                val a = s.phaseAllow[k] ?: 0L
                val b = s.phaseBlock[k] ?: 0L
                sb.append("  $k:  allow=$a  block=$b\n")
            }
            sb.append('\n')
        }

        if (s.verdictCounts.isNotEmpty()) {
            sb.append("===== Decision verdicts =====\n")
            s.verdictCounts.entries.sortedByDescending { it.value }
                .forEach { sb.append(line("${it.key}:", it.value)).append('\n') }
            sb.append('\n')
        }

        sb.append("===== ANR / main-thread health =====\n")
        sb.append("  ANR_HINTS (>${LONG_FRAME_THRESHOLD_MS}ms frame gaps):  ${s.anrHints}\n")
        sb.append("  Max frame gap:                          ${s.maxFrameGapMs} ms\n")
        sb.append("  Total stall time accumulated:           ${s.totalFrameStallMs} ms\n")
        sb.append("  Watcher installed:                      $anrInstalled\n")
        if (s.anrHints == 0) {
            sb.append("  Status: ✅ no long-frame events captured.\n")
        } else if (s.anrHints < 5) {
            sb.append("  Status: ⚠ minor — likely startup hiccup.\n")
        } else {
            sb.append("  Status: 🛑 sustained main-thread blocking. Inspect recent ANR_HINT events below.\n")
        }
        sb.append('\n')

        if (s.labelCounts.isNotEmpty()) {
            sb.append("===== Labelled counters (lane / error / lock / etc.) =====\n")
            s.labelCounts.entries.sortedByDescending { it.value }.take(30)
                .forEach { sb.append(line("${it.key}:", it.value)).append('\n') }
            sb.append('\n')
        }

        sb.append("===== Recent events (last ${s.recentEvents.size}) =====\n")
        // Print newest-first so the most recent ANR_HINT / GATE_BLOCK is at the top.
        for (ev in s.recentEvents.asReversed()) {
            sb.append("  ${df.format(Date(ev.tsMs))}  ")
                .append(ev.tag.padEnd(28))
            if (ev.symbol.isNotEmpty()) sb.append(ev.symbol.padEnd(12)) else sb.append("            ")
            sb.append(ev.message).append('\n')
        }
        sb.append('\n')

        sb.append("===== Interpretation cheat-sheet =====\n")
        sb.append("  BOT_LOOP_TICK=0           -> botLoop never iterated; check service start.\n")
        sb.append("  SCAN_CB=0 LOOP>0          -> watchlist empty; scanner intake is starving.\n")
        sb.append("  SAFETY=0 SCAN_CB>0        -> tokens rejected before SAFETY (intake gate).\n")
        sb.append("  LANE_EVAL=0 V3>0          -> V3 short-circuiting; check V3EngineEnabled.\n")
        sb.append("  EXEC=0 LANE_EVAL>0        -> all gates pass but Executor not invoked.\n")
        sb.append("  TRADEJRNL_REC=0 EXEC>0    -> Executor running but journal not writing.\n")
        sb.append("  ANR_HINTS>0               -> main thread blocked; inspect ANR_HINT events.\n")
        sb.append("  GATE_BLOCK/SAFETY high    -> safety checks rejecting most tokens.\n")
        sb.append("  GATE_BLOCK/FDG high       -> FDG vetoing; check edge vetoes / brain state.\n")

        return sb.toString()
    }

    /** Reset all counters — operator-triggered "fresh capture" for export. */
    fun reset() {
        phaseCounts.clear()
        phaseAllow.clear()
        phaseBlock.clear()
        verdictCounts.clear()
        labelCounts.clear()
        anrHintCount.set(0)
        maxFrameGapMs.set(0L)
        totalFrameStallMs.set(0L)
        startedAtMs.set(System.currentTimeMillis())
        ring.clear()
        ringSize.set(0)
    }

    // ════════════════════════════════════════════════════════════════
    // Internals
    // ════════════════════════════════════════════════════════════════

    private fun bump(map: ConcurrentHashMap<String, AtomicLong>, key: String) {
        var c = map[key]
        if (c == null) {
            c = map.computeIfAbsent(key) { AtomicLong(0L) }
        }
        c.incrementAndGet()
    }

    private fun appendEvent(ev: Event) {
        // V5.9.668 — lock-free O(1) ring buffer maintenance.
        ring.addLast(ev)
        if (ringSize.incrementAndGet() > RING_CAP) {
            // Trim from the head to keep size at RING_CAP. pollFirst is
            // lock-free; the size counter may briefly read >CAP under
            // heavy contention but converges immediately.
            ring.pollFirst()
            ringSize.decrementAndGet()
        }
    }

    @Suppress("unused")
    private fun nowElapsed(): Long = SystemClock.elapsedRealtime()
}
