package com.lifecyclebot.engine

import android.os.Handler
import android.os.HandlerThread
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
 * V5.9.666–V5.9.670 — PipelineHealthCollector
 *
 * In-app mirror of the CI runtime-test funnel summary, plus an
 * accurate main-thread watchdog ANR sampler and a deep ring buffer
 * of recent forensic events.
 *
 * V5.9.670 changelog:
 *   - Real watchdog-based ANR sampler: pings main thread Handler
 *     every 250ms; if no ACK in 700ms+ samples mainThread.stackTrace
 *     WHILE STILL BLOCKED. Previous Choreographer-based sampler
 *     captured the stack AFTER the freeze ended — always showed
 *     captureMainThreadStack itself, useless.
 *   - Deep operator diagnostics: per-source intake counters, per-
 *     lane eval counters, per-block-reason histogram, top symbols
 *     seen, top blocked reasons, bot-loop cycle timing (last + max
 *     + avg), recent executions ring, ANR stack frequency (so the
 *     same blocking call is grouped instead of spammed).
 *   - Snapshot dump expanded to surface every counter for clipboard
 *     export.
 *
 * Wiring strategy (unchanged):
 *   - ForensicLogger.phase / gate / decision / exec / lifecycle each
 *     fan into PipelineHealthCollector.* hooks.
 *   - TradeHistoryStore -> onTradeJournal()
 *   - WalletPositionLock + CryptoUniverseExecutor -> event(...)
 *   - BotService.onCreate -> installAnrWatcherOnMainThread()
 *
 * Zero overhead when forensic logging is enabled (we just bump
 * atomic counters). Read paths take a snapshot using lock-free
 * concurrent collections.
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

    /** V5.9.670 — per-source intake counters (PUMP_PORTAL_WS / RAYDIUM_NEW_POOL / etc.). */
    private val intakeBySource = ConcurrentHashMap<String, AtomicLong>()

    /** V5.9.670 — per-lane LANE_EVAL counters (SHITCOIN / MOONSHOT / QUALITY / BLUECHIP). */
    private val laneEvalCounts = ConcurrentHashMap<String, AtomicLong>()

    /** V5.9.670 — block-reason histogram across all gate types (top key on dump). */
    private val blockReasonCounts = ConcurrentHashMap<String, AtomicLong>()

    /** V5.9.670 — per-symbol intake counter (top-10 surfaced in dump). */
    private val symbolIntakeCounts = ConcurrentHashMap<String, AtomicLong>()

    /** V5.9.670 — ANR stack frequency. Grouping by top frame so a 10s freeze
     *  on the same call site is one entry, not 40 separate ANR_HINT lines. */
    private val anrStackCounts = ConcurrentHashMap<String, AtomicLong>()

    /** V5.9.670 — bot-loop cycle timing samples (most recent N). */
    private val recentCycleMsSamples = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    private val recentCycleMsSize = AtomicInteger(0)
    private const val CYCLE_SAMPLE_CAP = 50
    private val totalCycleMs = AtomicLong(0L)
    private val maxCycleMs = AtomicLong(0L)
    private val cycleCount = AtomicLong(0L)

    /** V5.9.670 — recent executions ring (BUY/SELL with size + mode + reason). */
    data class ExecRecord(
        val tsMs: Long,
        val side: String,
        val mode: String,
        val symbol: String,
        val sizeSol: Double,
        val pnlSol: Double,
        val reason: String,
    )
    private val recentExecs = java.util.concurrent.ConcurrentLinkedDeque<ExecRecord>()
    private val recentExecsSize = AtomicInteger(0)
    private const val EXEC_RING_CAP = 30

    /** ANR-style hint counter (watchdog long-frame events). */
    private val anrHintCount = AtomicInteger(0)

    /** Long-frame max delta seen (ms). */
    private val maxFrameGapMs = AtomicLong(0L)

    /** Total long-frame ms accumulated (for "time spent stuttering"). */
    private val totalFrameStallMs = AtomicLong(0L)

    /** V5.9.670 — count of sample attempts where main thread was blocked. */
    private val anrSamplesTaken = AtomicLong(0L)

    /** V5.9.680 — Pre-freeze rolling main-thread sample ring.
     *  Every watchdog tick (250ms) we capture (ts, sinceLastAckMs, topFrame)
     *  whether or not main thread is responsive. When a freeze fires, the
     *  dump now shows the last 30 samples = ~7.5s of pre-freeze history,
     *  letting the operator see what main was doing in the seconds
     *  leading up to the hang — not just its stack at the moment the
     *  watchdog noticed. Capped at STACK_RING_CAP. */
    data class StackSample(val tsMs: Long, val sinceLastAckMs: Long, val topFrame: String)
    private val stackRing = java.util.concurrent.ConcurrentLinkedDeque<StackSample>()
    private val stackRingSize = AtomicInteger(0)
    private const val STACK_RING_CAP = 30

    private fun pushStackSample(sample: StackSample) {
        stackRing.addLast(sample)
        if (stackRingSize.incrementAndGet() > STACK_RING_CAP) {
            stackRing.pollFirst()
            stackRingSize.decrementAndGet()
        }
    }

    /** Started-at epoch ms (for uptime in dump). */
    private val startedAtMs = AtomicLong(0L)

    // ════════════════════════════════════════════════════════════════
    // Event ring buffer — last N forensic events for clipboard export.
    // ════════════════════════════════════════════════════════════════

    private const val RING_CAP = 300  // V5.9.670 — increased from 200 for deeper history

    // V5.9.677 — bumped each release. Printed verbatim at top of every
    // pipeline-health dump alongside BuildConfig.VERSION_NAME so the
    // operator and agent never argue about which APK is on the device.
    private const val BUILD_TAG = "V5.9.682"

    data class Event(
        val tsMs: Long,
        val tag: String,
        val symbol: String,
        val message: String,
    )

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
            // V5.9.670 — extract prevCycleMs and record cycle timing.
            val m = Regex("prevCycleMs=(\\d+)").find(fields)
            if (m != null) {
                m.groupValues[1].toLongOrNull()?.let { recordCycleMs(it) }
            }
        }
        // V5.9.670 — per-source intake bump for INTAKE phase events.
        if (phaseTag == "INTAKE" && symbol.isNotEmpty()) {
            val srcMatch = Regex("src=([^ ]+)").find(fields)
            srcMatch?.groupValues?.get(1)?.let { bump(intakeBySource, it.take(40)) }
            bump(symbolIntakeCounts, symbol.take(20))
        }
        // V5.9.670 — per-lane eval bump for LANE_EVAL phase events.
        if (phaseTag == "LANE_EVAL") {
            val laneMatch = Regex("lane=([A-Z_]+)").find(fields)
            laneMatch?.groupValues?.get(1)?.let { bump(laneEvalCounts, it) }
        }
        appendEvent(Event(System.currentTimeMillis(), "PHASE/$phaseTag", symbol, fields.take(220)))
    }

    fun onGate(phaseTag: String, symbol: String, allow: Boolean, reason: String) {
        if (!attached) return
        bump(phaseCounts, phaseTag)
        if (allow) bump(phaseAllow, phaseTag) else bump(phaseBlock, phaseTag)
        if (!allow) {
            // V5.9.670 — block-reason histogram. Truncate to the first token of
            // the reason so we group "EXCEPTION cls=Foo" across distinct messages.
            val reasonKey = reason.substringBefore(' ').take(40).ifEmpty { "unspecified" }
            bump(blockReasonCounts, "$phaseTag/$reasonKey")
        }
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
        // V5.9.670 — also record cycle timing if this is a LOOP_TOP snapshot.
        if (label == "LOOP_TOP" || label == "BOT_LOOP_TICK") {
            val m = Regex("prevCycleMs=(\\d+)").find(fields)
            if (m != null) m.groupValues[1].toLongOrNull()?.let { recordCycleMs(it) }
        }
        appendEvent(Event(System.currentTimeMillis(), "SNAP/$label", "", fields.take(220)))
    }

    /** Generic event injection point for callers outside ForensicLogger. */
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

    /**
     * V5.9.670 — public hook for executions so the dump can surface the last
     * 30 trades right alongside the funnel counts. Called from TradeHistoryStore
     * after each successful journal write.
     */
    fun recordExec(side: String, mode: String, symbol: String, sizeSol: Double, pnlSol: Double, reason: String) {
        if (!attached) return
        bump(labelCounts, "EXEC_${side.uppercase()}")
        recentExecs.addLast(ExecRecord(System.currentTimeMillis(), side, mode, symbol, sizeSol, pnlSol, reason))
        if (recentExecsSize.incrementAndGet() > EXEC_RING_CAP) {
            recentExecs.pollFirst()
            recentExecsSize.decrementAndGet()
        }
    }

    private fun recordCycleMs(ms: Long) {
        if (ms <= 0) return
        recentCycleMsSamples.addLast(ms)
        if (recentCycleMsSize.incrementAndGet() > CYCLE_SAMPLE_CAP) {
            recentCycleMsSamples.pollFirst()
            recentCycleMsSize.decrementAndGet()
        }
        totalCycleMs.addAndGet(ms)
        cycleCount.incrementAndGet()
        var prev = maxCycleMs.get()
        while (ms > prev && !maxCycleMs.compareAndSet(prev, ms)) { prev = maxCycleMs.get() }
    }

    // ════════════════════════════════════════════════════════════════
    // ANR detector — V5.9.670 watchdog-thread sampler.
    //   Choreographer.FrameCallback (legacy) still measures durations.
    //   New: a 250ms-cadence watchdog thread that pings the main-thread
    //   Handler. If a ping isn't ACKed in > LONG_FRAME_THRESHOLD_MS,
    //   we sample mainThread.stackTrace WHILE STILL BLOCKED — that
    //   shows the actual blocking call site rather than the post-hoc
    //   captureMainThreadStack frames we used to see.
    // ════════════════════════════════════════════════════════════════

    private const val LONG_FRAME_THRESHOLD_MS = 700L  // > 700ms gap = "the bot froze"
    private const val WATCHDOG_INTERVAL_MS    = 250L
    @Volatile private var anrInstalled = false
    private var lastFrameNs: Long = 0L
    @Volatile private var watchdogThread: HandlerThread? = null
    @Volatile private var mainHandler: Handler? = null

    /**
     * Install the main-thread frame monitor + watchdog. Idempotent;
     * safe to call from BotService.onCreate OR from the activity's
     * onCreate (whichever fires first wins). Must be called on the
     * main thread.
     */
    fun installAnrWatcherOnMainThread() {
        if (anrInstalled) return
        if (Looper.myLooper() != Looper.getMainLooper()) return
        anrInstalled = true
        if (startedAtMs.get() == 0L) startedAtMs.set(System.currentTimeMillis())

        val mainThread = Looper.getMainLooper().thread
        mainHandler = Handler(Looper.getMainLooper())

        // Choreographer still records duration of long frames (cheap).
        val choreo = try { Choreographer.getInstance() } catch (_: Throwable) { null }
        choreo?.postFrameCallback(object : Choreographer.FrameCallback {
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
                    }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        })

        // V5.9.670 — watchdog thread that samples mainThread WHILE blocked.
        val hThread = HandlerThread("ANR_Watchdog", Thread.MIN_PRIORITY).apply { start() }
        watchdogThread = hThread
        val watchdogHandler = Handler(hThread.looper)
        val ackTs = AtomicLong(SystemClock.elapsedRealtime())
        val mainPing = Runnable { ackTs.set(SystemClock.elapsedRealtime()) }

        watchdogHandler.post(object : Runnable {
            // De-dup so a 10s freeze on the same call only emits a single ANR_HINT
            // (followed by aggregated count) instead of 40 lines.
            private var lastReportedKey: String = ""
            private var lastReportedAtMs: Long = 0L

            override fun run() {
                try {
                    val now = SystemClock.elapsedRealtime()
                    val gap = now - ackTs.get()

                    // V5.9.680 — sample main-thread top frame EVERY tick (not just
                    // on freeze) and push to the rolling ring. Cheap (~1ms),
                    // gives us 7.5s of pre-freeze history when a hang fires.
                    val tickTrace = try {
                        captureMainThreadStack(mainThread)
                    } catch (_: Throwable) { "" }
                    val tickTop = tickTrace.lineSequence()
                        .firstOrNull { it.contains("com.lifecyclebot") }?.trim()
                        ?: tickTrace.lineSequence().firstOrNull()?.trim()
                        ?: "(idle)"
                    pushStackSample(StackSample(System.currentTimeMillis(), gap, tickTop.take(160)))

                    if (gap > LONG_FRAME_THRESHOLD_MS) {
                        // Main thread hasn't acked in > 700ms — reuse the tick
                        // sample we just captured rather than re-walking the
                        // stack a second time.
                        val trace = tickTrace.ifBlank { "(stack capture failed)" }
                        val topFrame = tickTop
                        bump(anrStackCounts, topFrame.take(120))
                        anrSamplesTaken.incrementAndGet()

                        // Throttle event emission: one ANR_HINT every 1.5s per
                        // unique top frame. Long freezes on the same call get
                        // grouped via the anrStackCounts counter above.
                        val emitNow = topFrame != lastReportedKey ||
                                (System.currentTimeMillis() - lastReportedAtMs) > 1_500L
                        if (emitNow) {
                            lastReportedKey = topFrame
                            lastReportedAtMs = System.currentTimeMillis()
                            // V5.9.680 — include the 30-frame pre-freeze ring
                            // so the operator sees what main was doing in the
                            // ~7.5s leading up to the hang, not just at the
                            // moment of detection.
                            val preRingSb = StringBuilder()
                            preRingSb.append("\n--- pre-freeze rolling main-thread sample (last 30) ---\n")
                            val ringSnapshot = stackRing.toList()
                            for (smp in ringSnapshot.asReversed()) {
                                preRingSb.append("  +").append(smp.sinceLastAckMs.toString().padStart(5))
                                    .append("ms  ").append(smp.topFrame).append('\n')
                            }
                            appendEvent(Event(
                                System.currentTimeMillis(),
                                "ANR_HINT",
                                "",
                                "main thread blocked for ${gap}ms — top frame: $topFrame\n$trace$preRingSb",
                            ))
                        }
                    } else {
                        // Main has been responsive — ping for the next round.
                        mainHandler?.post(mainPing)
                    }
                } catch (_: Throwable) {
                    // Never let the watchdog crash itself.
                }
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        })
    }

    /**
     * Capture a compact main-thread stack-trace string. Used both by
     * the watchdog (during the freeze) and the post-hoc Choreographer
     * sample (no longer relied upon for diagnostics).
     */
    private fun captureMainThreadStack(t: Thread): String {
        val frames = t.stackTrace ?: return "(no frames)"
        if (frames.isEmpty()) return "(empty stack)"
        val sb = StringBuilder()
        var emitted = 0
        for (f in frames) {
            val cls = f.className ?: ""
            // Skip our own watchdog sampler frames so we don't pollute output.
            if (cls.startsWith("com.lifecyclebot.engine.PipelineHealthCollector")) continue
            if (cls.startsWith("java.lang.Thread") && f.methodName == "getStackTrace") continue
            if (cls.startsWith("dalvik.system.VMStack")) continue
            val isOurs = cls.startsWith("com.lifecyclebot")
            if (emitted < 6 || isOurs) {
                sb.append("    at ").append(cls).append('.').append(f.methodName)
                if (f.fileName != null) {
                    sb.append('(').append(f.fileName).append(':').append(f.lineNumber).append(')')
                }
                sb.append('\n')
                emitted++
                if (emitted >= 24) break
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
        val intakeBySource: Map<String, Long>,
        val laneEvalCounts: Map<String, Long>,
        val blockReasonCounts: Map<String, Long>,
        val symbolIntakeCounts: Map<String, Long>,
        val anrStackCounts: Map<String, Long>,
        val recentExecs: List<ExecRecord>,
        val anrHints: Int,
        val anrSamplesTaken: Long,
        val maxFrameGapMs: Long,
        val totalFrameStallMs: Long,
        val recentCycleMsSamples: List<Long>,
        val totalCycleMs: Long,
        val cycleCount: Long,
        val maxCycleMs: Long,
        val recentEvents: List<Event>,
        val stackRing: List<StackSample>,
    )

    fun snapshot(): Snapshot {
        val events = ring.toList()
        return Snapshot(
            startedAtMs            = startedAtMs.get(),
            nowMs                  = System.currentTimeMillis(),
            phaseCounts            = phaseCounts.mapValues { it.value.get() },
            phaseAllow             = phaseAllow.mapValues  { it.value.get() },
            phaseBlock             = phaseBlock.mapValues  { it.value.get() },
            verdictCounts          = verdictCounts.mapValues { it.value.get() },
            labelCounts            = labelCounts.mapValues { it.value.get() },
            intakeBySource         = intakeBySource.mapValues { it.value.get() },
            laneEvalCounts         = laneEvalCounts.mapValues { it.value.get() },
            blockReasonCounts      = blockReasonCounts.mapValues { it.value.get() },
            symbolIntakeCounts     = symbolIntakeCounts.mapValues { it.value.get() },
            anrStackCounts         = anrStackCounts.mapValues { it.value.get() },
            recentExecs            = recentExecs.toList(),
            anrHints               = anrHintCount.get(),
            anrSamplesTaken        = anrSamplesTaken.get(),
            maxFrameGapMs          = maxFrameGapMs.get(),
            totalFrameStallMs      = totalFrameStallMs.get(),
            recentCycleMsSamples   = recentCycleMsSamples.toList(),
            totalCycleMs           = totalCycleMs.get(),
            cycleCount             = cycleCount.get(),
            maxCycleMs             = maxCycleMs.get(),
            recentEvents           = events,
            stackRing              = stackRing.toList(),
        )
    }

    /**
     * Render the full diagnostic dump for clipboard export.
     *
     * V5.9.670 — expanded sections:
     *   - Pipeline funnel (CI parity)
     *   - Bot-loop cycle timing
     *   - Gate allow/block tally
     *   - Top block reasons (histogram)
     *   - Per-source intake (PUMP_PORTAL_WS / RAYDIUM / etc.)
     *   - Per-lane eval (SHITCOIN / MOONSHOT / QUALITY / BLUECHIP)
     *   - Decision verdicts
     *   - Top intaked symbols (Top 15)
     *   - Recent executions (last 30 BUY/SELL)
     *   - ANR / main-thread health (watchdog samples)
     *   - ANR top blocking call sites
     *   - Labelled counters
     *   - Recent forensic events
     *   - Interpretation cheat-sheet
     */
    fun dumpText(): String {
        val s = snapshot()
        val df = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val uptimeSec = if (s.startedAtMs > 0) ((s.nowMs - s.startedAtMs) / 1000L) else 0L

        fun line(label: String, value: Any?, hint: String = ""): String {
            val v = value?.toString() ?: "0"
            val h = if (hint.isNotEmpty()) "  ($hint)" else ""
            return "  ${label.padEnd(28)}$v$h"
        }

        val sb = StringBuilder(16 * 1024)
        sb.append("===== AATE Pipeline Health Snapshot =====\n")
        // V5.9.677 — version stamp at top so we never debate which build
        // is on the device. Tag is a hardcoded const bumped per release;
        // appVer comes from BuildConfig.VERSION_NAME (set by gradle to
        // "5.0.<ciBuildNumber>"). When the operator pastes a dump we can
        // immediately confirm the fixes in this dump actually exist.
        val _appVer = try {
            com.lifecyclebot.BuildConfig.VERSION_NAME
        } catch (_: Throwable) { "unknown" }
        sb.append("  Build:                 ${_appVer}  |  Tag: ${BUILD_TAG}\n")
        sb.append("  Captured at:           ${df.format(Date(s.nowMs))}\n")
        sb.append("  Uptime since start:    ${uptimeSec}s\n")
        sb.append("  Forensic logging:      ${if (ForensicLogger.enabled) "ON" else "OFF"}\n")
        sb.append("\n")

        // ── Funnel ──────────────────────────────────────────────────
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

        // ── Bot loop cycle timing ───────────────────────────────────
        sb.append("===== Bot-loop cycle timing =====\n")
        val avgCycle = if (s.cycleCount > 0) s.totalCycleMs / s.cycleCount else 0L
        sb.append(line("Cycles seen:", s.cycleCount)).append('\n')
        sb.append(line("Avg cycle ms:", avgCycle)).append('\n')
        sb.append(line("Max cycle ms:", s.maxCycleMs)).append('\n')
        if (s.recentCycleMsSamples.isNotEmpty()) {
            val recent = s.recentCycleMsSamples.takeLast(10).joinToString(", ")
            sb.append("  Last 10 cycles (ms):    [$recent]\n")
        }
        if (s.maxCycleMs > 30_000) {
            sb.append("  Status: 🛑 cycles > 30s indicate watchlist or scanner overload.\n")
        }
        sb.append('\n')

        // ── Gate tally ──────────────────────────────────────────────
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

        // ── Block reason histogram ──────────────────────────────────
        if (s.blockReasonCounts.isNotEmpty()) {
            sb.append("===== Top block reasons (gate -> reason) =====\n")
            s.blockReasonCounts.entries.sortedByDescending { it.value }.take(20)
                .forEach { sb.append(line("${it.key}:", it.value)).append('\n') }
            sb.append('\n')
        }

        // ── Per-source intake ──────────────────────────────────────
        if (s.intakeBySource.isNotEmpty()) {
            sb.append("===== Intake by source =====\n")
            s.intakeBySource.entries.sortedByDescending { it.value }.take(20)
                .forEach { sb.append(line("${it.key}:", it.value)).append('\n') }
            sb.append('\n')
        }

        // ── Per-lane eval ───────────────────────────────────────────
        if (s.laneEvalCounts.isNotEmpty()) {
            sb.append("===== LANE_EVAL by lane =====\n")
            s.laneEvalCounts.entries.sortedByDescending { it.value }
                .forEach { sb.append(line("${it.key}:", it.value)).append('\n') }
            sb.append('\n')
        }

        // ── Decision verdicts ───────────────────────────────────────
        if (s.verdictCounts.isNotEmpty()) {
            sb.append("===== Decision verdicts =====\n")
            s.verdictCounts.entries.sortedByDescending { it.value }
                .forEach { sb.append(line("${it.key}:", it.value)).append('\n') }
            sb.append('\n')
        }

        // ── Top intaked symbols ─────────────────────────────────────
        if (s.symbolIntakeCounts.isNotEmpty()) {
            sb.append("===== Top intaked symbols =====\n")
            s.symbolIntakeCounts.entries.sortedByDescending { it.value }.take(15)
                .forEach { sb.append(line("${it.key}:", it.value, "intake hits")).append('\n') }
            sb.append('\n')
        }

        // ── Recent executions ───────────────────────────────────────
        if (s.recentExecs.isNotEmpty()) {
            sb.append("===== Recent executions (last ${s.recentExecs.size}) =====\n")
            for (ex in s.recentExecs.asReversed()) {
                sb.append("  ").append(df.format(Date(ex.tsMs))).append(' ')
                    .append(ex.side.padEnd(4)).append(' ')
                    .append(ex.mode.padEnd(5)).append(' ')
                    .append(ex.symbol.padEnd(10)).append(' ')
                    .append("sol=").append(String.format("%.3f", ex.sizeSol)).append(' ')
                    .append("pnl=").append(String.format("%+.3f", ex.pnlSol))
                if (ex.reason.isNotBlank()) sb.append(" reason=").append(ex.reason.take(60))
                sb.append('\n')
            }
            sb.append('\n')
        }

        // ── ANR health (watchdog) ───────────────────────────────────
        sb.append("===== ANR / main-thread health (watchdog sampler) =====\n")
        sb.append("  ANR_HINTS (>${LONG_FRAME_THRESHOLD_MS}ms):           ${s.anrHints}\n")
        sb.append("  Watchdog samples taken:                ${s.anrSamplesTaken}\n")
        sb.append("  Max frame gap:                          ${s.maxFrameGapMs} ms\n")
        sb.append("  Total stall time accumulated:           ${s.totalFrameStallMs} ms\n")
        val stallPct = if (uptimeSec > 0) (s.totalFrameStallMs / (uptimeSec * 10.0)) else 0.0
        sb.append("  Stall % of uptime:                      ${"%.1f".format(stallPct)}%\n")
        sb.append("  Watcher installed:                      $anrInstalled\n")
        if (s.anrHints == 0) {
            sb.append("  Status: ✅ no long-frame events captured.\n")
        } else if (s.anrHints < 5) {
            sb.append("  Status: ⚠ minor — likely startup hiccup.\n")
        } else {
            sb.append("  Status: 🛑 sustained main-thread blocking. Inspect top blocking call sites below.\n")
        }
        sb.append('\n')

        // ── Top blocking call sites ─────────────────────────────────
        if (s.anrStackCounts.isNotEmpty()) {
            sb.append("===== ANR top blocking call sites (most frequent first) =====\n")
            s.anrStackCounts.entries.sortedByDescending { it.value }.take(20).forEach { (frame, count) ->
                sb.append("  [$count]  $frame\n")
            }
            sb.append('\n')
        }

        // ── V5.9.680 — Pre-freeze rolling main-thread sample ────────
        // Captured every watchdog tick (250ms). Newest first. When a
        // freeze fires, this shows the ~7.5s leading up to it — far
        // more useful than the single stack sampled at detection time.
        if (s.stackRing.isNotEmpty()) {
            sb.append("===== Pre-freeze rolling main-thread sample (last ${s.stackRing.size}, newest first) =====\n")
            for (smp in s.stackRing.asReversed()) {
                sb.append("  ").append(df.format(Date(smp.tsMs)))
                    .append("  gap=").append(smp.sinceLastAckMs.toString().padStart(5)).append("ms  ")
                    .append(smp.topFrame).append('\n')
            }
            sb.append('\n')
        }

        // ── Labelled counters ───────────────────────────────────────
        // V5.9.677 — LIFECYCLE/* and SNAP/* entries are pinned BEFORE the
        // top-40 by-count slice. The previous frequency-sorted-take(40)
        // pushed singleton lifecycle counters (BATTERY_OPT_CHECK=1,
        // LOOP_HEARTBEAT_ALARM=15, CYCLE_PHASE=63 etc.) below the cut
        // whenever a few hundred SCAN_CB / GATE_BLOCK entries flooded
        // labelCounts, so the dump never proved whether the latest
        // version's lifecycle hooks were actually firing on device.
        // Now all LIFECYCLE/* and SNAP/* counters are emitted FIRST in
        // sorted order, then the remaining counters fill up to 40 slots.
        if (s.labelCounts.isNotEmpty()) {
            sb.append("===== Labelled counters (lane / error / lock / etc.) =====\n")
            val all = s.labelCounts.entries.toList()
            val lifecycle = all.filter { it.key.startsWith("LIFECYCLE/") }
                .sortedByDescending { it.value }
            val snaps = all.filter { it.key.startsWith("SNAP/") }
                .sortedByDescending { it.value }
            val pinnedKeys = (lifecycle + snaps).map { it.key }.toSet()
            // Render pinned (lifecycle then snap) regardless of count rank.
            for (e in lifecycle) sb.append(line("${e.key}:", e.value)).append('\n')
            for (e in snaps) sb.append(line("${e.key}:", e.value)).append('\n')
            // Fill remaining slots up to 40 with the highest-count NON-pinned
            // entries so high-volume tags (TRADEJRNL_REC, BOT_LOOP_TICK, gate
            // tags) still appear when they out-rank lifecycle singletons.
            val remainingSlots = (40 - lifecycle.size - snaps.size).coerceAtLeast(0)
            all.asSequence()
                .filter { it.key !in pinnedKeys }
                .sortedByDescending { it.value }
                .take(remainingSlots)
                .forEach { sb.append(line("${it.key}:", it.value)).append('\n') }
            sb.append('\n')
        }

        // ── Recent events ───────────────────────────────────────────
        sb.append("===== Recent events (last ${s.recentEvents.size}) =====\n")
        for (ev in s.recentEvents.asReversed()) {
            sb.append("  ${df.format(Date(ev.tsMs))}  ")
                .append(ev.tag.padEnd(28))
            if (ev.symbol.isNotEmpty()) sb.append(ev.symbol.padEnd(12)) else sb.append("            ")
            sb.append(ev.message).append('\n')
        }
        sb.append('\n')

        // ── Cheat-sheet ─────────────────────────────────────────────
        // V5.9.709 — expanded cheat-sheet with actionable context
        val execBuy  = (labelCounts["EXEC/PAPER_BUY"] ?: 0L) + (labelCounts["EXEC/LIVE_BUY"] ?: 0L)
        val execSell = labelCounts["EXEC_SELL"] ?: 0L
        val stall    = if (uptimeSec > 0) (s.totalFrameStallMs * 100L / (uptimeSec * 1000L)) else 0L
        sb.append("===== Interpretation cheat-sheet =====\n")
        sb.append("  BOT_LOOP_TICK=0           -> botLoop never iterated; check service start.\n")
        sb.append("  SCAN_CB=0 LOOP>0          -> watchlist empty; scanner intake is starving.\n")
        sb.append("  SAFETY=0 SCAN_CB>0        -> tokens rejected before SAFETY (intake gate).\n")
        sb.append("  LANE_EVAL=0 V3>0          -> V3 short-circuiting; check V3EngineEnabled.\n")
        sb.append("  EXEC=0 LANE_EVAL>0        -> Executor not invoked — check FDG block rate and cbState.isPaused.\n")
        sb.append("                               Note: EXEC_BUY/SELL in labelled counters is the true execution signal.\n")
        sb.append("  EXEC_BUY=${execBuy} EXEC_SELL=${execSell}         -> actual buy/sell executions this session.\n")
        sb.append("  TRADEJRNL_REC=0 EXEC>0    -> Executor running but journal not writing.\n")
        sb.append("  INTAKE allow=0            -> all intake blocked; top reason shown in block tally above.\n")
        sb.append("  NO_PAIR_NO_FALLBACK high  -> tokens have no DEX pair/price yet (pump.fun bonding curve only).\n")
        sb.append("                               Normal for new tokens — they clear once Raydium pair is created.\n")
        sb.append("  FDG block=N EXEC=0        -> FDG vetoing all candidates; check CONFIDENCE_FLOOR or DANGER_ZONE.\n")
        sb.append("  ANR_HINTS>0               -> main thread blocked; inspect 'ANR top blocking call sites'.\n")
        sb.append("  Stall%>50%%               -> UI render is blocking main thread. Stall=${stall}%% this session.\n")
        sb.append("                               Fix: reduce renderOpenPositions/buildTokenCard frequency.\n")
        sb.append("  GATE_BLOCK/SAFETY high    -> safety checks rejecting most tokens; check rug-score thresholds.\n")
        sb.append("  GATE_BLOCK/FDG high       -> FDG vetoing; check edge vetoes / brain state / conf floor.\n")
        sb.append("  Max cycle >30s            -> watchlist or scanner overload. Check 'Bot-loop cycle timing'.\n")
        sb.append("  V3_SKIPPED high           -> V3 engine disabled or in learning phase (normal early-run).\n")

        return sb.toString()
    }

    /** Reset all counters — operator-triggered "fresh capture" for export. */
    fun reset() {
        phaseCounts.clear()
        phaseAllow.clear()
        phaseBlock.clear()
        verdictCounts.clear()
        labelCounts.clear()
        intakeBySource.clear()
        laneEvalCounts.clear()
        blockReasonCounts.clear()
        symbolIntakeCounts.clear()
        anrStackCounts.clear()
        anrHintCount.set(0)
        anrSamplesTaken.set(0)
        maxFrameGapMs.set(0L)
        totalFrameStallMs.set(0L)
        startedAtMs.set(System.currentTimeMillis())
        ring.clear()
        ringSize.set(0)
        recentExecs.clear()
        recentExecsSize.set(0)
        recentCycleMsSamples.clear()
        recentCycleMsSize.set(0)
        totalCycleMs.set(0L)
        cycleCount.set(0L)
        maxCycleMs.set(0L)
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
        ring.addLast(ev)
        if (ringSize.incrementAndGet() > RING_CAP) {
            ring.pollFirst()
            ringSize.decrementAndGet()
        }
    }

    @Suppress("unused")
    private fun nowElapsed(): Long = SystemClock.elapsedRealtime()
}
