package com.lifecyclebot.engine.truth

import android.content.Context
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7717 §A_CRASH_THE_OPERATOR_CANNOT_READ_IS_A_CRASH_THAT_REPEATS.
 *
 * 5.0.7715 killed the app the instant the password was accepted. The global
 * handler logs crashes through ErrorLogger, which is fire-and-forget on an
 * IO handler; the default handler then terminates the process milliseconds
 * later, so the row was never readable, the emulator never reached the
 * path, and the only remedy was a blind revert (5.0.7716).
 *
 * This object does two small things, both synchronous and both before the
 * process is allowed to die:
 *
 *   1. RECORD. The crash head (thread, class, message, first frames) and
 *      the process uptime at the moment of the crash go into their own
 *      SharedPreferences with commit() and into files/last_crash_7717.txt.
 *      The pipeline health report prints the last crash at the top, so the
 *      next snapshot the operator pastes carries the trace.
 *
 *   2. GUARD. A crash inside the first two minutes of a process is a
 *      startup crash. When the previous process died that way, the
 *      optional doctrine layers (FieldManual7715) stay off for the next six
 *      hours and say so, so a bad build can never lock the operator out of
 *      the app a second time while the trace is being read.
 */
object StartupCrashGuard7717 {

    private const val PREFS_7717 = "startup_crash_guard_7717"
    private const val KEY_LAST_CRASH_MS = "last_crash_ms"
    private const val KEY_LAST_CRASH_UPTIME_MS = "last_crash_uptime_ms"
    private const val KEY_LAST_CRASH_THREAD = "last_crash_thread"
    private const val KEY_LAST_CRASH_HEAD = "last_crash_head"
    private const val KEY_LAST_CRASH_BUILD = "last_crash_build"
    private const val KEY_STARTUP_CRASH_STREAK = "startup_crash_streak"
    private const val KEY_SUPPRESS_UNTIL_MS = "suppress_until_ms"
    const val LAST_CRASH_FILE_7717 = "last_crash_7717.txt"

    /** A crash this soon after process start is a startup crash. */
    const val STARTUP_WINDOW_MS_7717 = 120_000L
    /** How long optional doctrine layers stay off after a startup crash. */
    const val SUPPRESS_MS_7717 = 6L * 60L * 60_000L
    private const val HEAD_LINES_7717 = 24

    @Volatile private var processStartMs: Long = System.currentTimeMillis()
    @Volatile private var suppressUntilMs: Long = 0L
    @Volatile private var lastCrashMs: Long = 0L
    @Volatile private var lastCrashUptimeMs: Long = -1L
    @Volatile private var lastCrashThread: String = ""
    @Volatile private var lastCrashHead: String = ""
    @Volatile private var lastCrashBuild: String = ""
    @Volatile private var startupCrashStreak: Int = 0
    @Volatile private var thisBuild: String = ""
    @Volatile private var loaded: Boolean = false
    private val suppressedReads = AtomicLong(0)

    /** Called once from AATEApp.onCreate. Loads the previous verdict and decides this run's suppression. */
    fun markProcessStart(ctx: Context, buildTag: String) {
        processStartMs = System.currentTimeMillis()
        try {
            val p = ctx.getSharedPreferences(PREFS_7717, Context.MODE_PRIVATE)
            lastCrashMs = p.getLong(KEY_LAST_CRASH_MS, 0L)
            lastCrashUptimeMs = p.getLong(KEY_LAST_CRASH_UPTIME_MS, -1L)
            lastCrashThread = p.getString(KEY_LAST_CRASH_THREAD, "") ?: ""
            lastCrashHead = p.getString(KEY_LAST_CRASH_HEAD, "") ?: ""
            lastCrashBuild = p.getString(KEY_LAST_CRASH_BUILD, "") ?: ""
            startupCrashStreak = p.getInt(KEY_STARTUP_CRASH_STREAK, 0)
            suppressUntilMs = p.getLong(KEY_SUPPRESS_UNTIL_MS, 0L)
            loaded = true
            thisBuild = buildTag
            // V5.0.7721 — suppression protects against the SAME build crashing
            // again. A new build is a new fact (5.0.7720 ran with the manual
            // off for 313 min because 5.0.7718 had crashed): lift it, keep the
            // crash record for the report.
            if (suppressUntilMs > processStartMs && lastCrashBuild.isNotBlank() && lastCrashBuild != buildTag) {
                suppressUntilMs = 0L
                try { p.edit().putLong(KEY_SUPPRESS_UNTIL_MS, 0L).apply() } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("STARTUP_CRASH_GUARD_LIFTED_NEW_BUILD_7721") } catch (_: Throwable) {}
            }
            if (suppressUntilMs > processStartMs) {
                try { PipelineHealthCollector.labelInc("STARTUP_CRASH_GUARD_SUPPRESSING_7717") } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /** Milliseconds since this process started. */
    fun uptimeMs(): Long = (System.currentTimeMillis() - processStartMs).coerceAtLeast(0L)

    /**
     * Synchronous. Called first thing in the uncaught-exception handler so the
     * record exists before anything else runs and before the process dies.
     */
    fun recordCrash(ctx: Context, thread: Thread, t: Throwable, buildTag: String) {
        val now = System.currentTimeMillis()
        val uptime = uptimeMs()
        val head = buildHead(thread, t)
        val startupCrash = uptime <= STARTUP_WINDOW_MS_7717
        try {
            val p = ctx.getSharedPreferences(PREFS_7717, Context.MODE_PRIVATE)
            val streak = if (startupCrash) p.getInt(KEY_STARTUP_CRASH_STREAK, 0) + 1 else 0
            val e = p.edit()
                .putLong(KEY_LAST_CRASH_MS, now)
                .putLong(KEY_LAST_CRASH_UPTIME_MS, uptime)
                .putString(KEY_LAST_CRASH_THREAD, thread.name)
                .putString(KEY_LAST_CRASH_HEAD, head)
                .putString(KEY_LAST_CRASH_BUILD, buildTag)
                .putInt(KEY_STARTUP_CRASH_STREAK, streak)
            if (startupCrash) e.putLong(KEY_SUPPRESS_UNTIL_MS, now + SUPPRESS_MS_7717)
            e.commit()
            // V5.0.7719 — mirror in memory so this dying process's restart
            // scheduling (AATEApp crash handler) sees the crash it just had.
            lastCrashMs = now
            lastCrashUptimeMs = uptime
            lastCrashThread = thread.name
            lastCrashHead = head
            lastCrashBuild = buildTag
            startupCrashStreak = streak
            if (startupCrash) suppressUntilMs = now + SUPPRESS_MS_7717
            loaded = true
        } catch (_: Throwable) {}
        try {
            val f = java.io.File(ctx.filesDir, LAST_CRASH_FILE_7717)
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            f.writeText(
                "build=$buildTag at=$now uptimeMs=$uptime thread=${thread.name} startupCrash=$startupCrash\n" + sw.toString().take(32_000),
            )
        } catch (_: Throwable) {}
    }

    private fun buildHead(thread: Thread, t: Throwable): String {
        val sb = StringBuilder()
        sb.append(thread.name).append(" | ")
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 4) {
            if (depth > 0) sb.append(" <- ")
            sb.append(cur.javaClass.name).append(": ").append(cur.message?.take(160) ?: "")
            cur = cur.cause
            depth++
        }
        sb.append('\n')
        var lines = 0
        var frames: Throwable? = t
        while (frames != null && lines < HEAD_LINES_7717) {
            for (el in frames.stackTrace) {
                if (lines >= HEAD_LINES_7717) break
                sb.append("  at ").append(el.toString()).append('\n')
                lines++
            }
            frames = frames.cause
            if (frames != null && lines < HEAD_LINES_7717) { sb.append("Caused by: ").append(frames.javaClass.simpleName).append('\n'); lines++ }
        }
        return sb.toString().take(4_000)
    }

    // ─────────────────────────────────────────────────────────────────────
    // V5.0.7719 §A_CRASH_LOOP_MUST_NOT_HOLD_THE_LOGIN_SCREEN_HOSTAGE.
    //
    // Operator on 5.0.7717/7718: "it runs but I can't get past the login
    // screen. I can't get logs." BotService shares the UI process; a crash
    // at bot start was followed by the handler's forced 3 s restart and by
    // the login screen's own pre-kick, so the process died again and again
    // while the operator was on (or just past) the PIN screen. While the last
    // crash was a startup crash in the last CRASH_LOOP_WINDOW_MS_7719, no
    // automatic start runs (BotService refuses non-user ACTION_START and
    // sticky resurrection; the handler schedules no restart). A user Start
    // still works. The login screen shows the crash with tap-to-copy.
    // ─────────────────────────────────────────────────────────────────────
    const val CRASH_LOOP_WINDOW_MS_7719 = 15L * 60_000L
    private const val DISPLAY_WINDOW_MS_7719 = 24L * 60L * 60_000L

    /** True when the most recent crash was a startup crash within the crash-loop window. */
    fun inCrashLoop(): Boolean =
        loaded && lastCrashMs > 0L &&
            System.currentTimeMillis() - lastCrashMs <= CRASH_LOOP_WINDOW_MS_7719 &&
            lastCrashUptimeMs in 0..STARTUP_WINDOW_MS_7717

    /** The last crash in operator-readable form for the login screen, or null when none in the last day. */
    fun crashForDisplay(): String? {
        if (!loaded || lastCrashMs <= 0L) return null
        val ageMs = System.currentTimeMillis() - lastCrashMs
        if (ageMs > DISPLAY_WINDOW_MS_7719) return null
        val kind = if (lastCrashUptimeMs in 0..STARTUP_WINDOW_MS_7717) "startup crash" else "crash"
        val safe = if (inCrashLoop()) " — auto-start paused; tap Start in the app when ready" else ""
        return "Last $kind (${lastCrashBuild}, ${ageMs / 60_000L} min ago)$safe — tap to copy\n" +
            lastCrashHead.lines().take(6).joinToString("\n")
    }

    /** Full trace for the clipboard: the file when present, else the stored head. */
    fun fullCrashText(ctx: Context): String = try {
        val f = java.io.File(ctx.filesDir, LAST_CRASH_FILE_7717)
        if (f.exists()) f.readText().take(16_000) else lastCrashHead
    } catch (_: Throwable) { lastCrashHead }

    /** True while optional doctrine layers must stay off because the previous process died at startup. */
    fun manualSuppressed(): Boolean {
        val s = loaded && suppressUntilMs > System.currentTimeMillis()
        if (s) suppressedReads.incrementAndGet()
        return s
    }

    /** Operator-facing: the last crash, for the top of the pipeline report. */
    fun lastCrashSummary(): String {
        if (!loaded) return "guard not initialised"
        if (lastCrashMs <= 0L) return "none recorded"
        val ageMin = (System.currentTimeMillis() - lastCrashMs) / 60_000L
        val kind = if (lastCrashUptimeMs in 0..STARTUP_WINDOW_MS_7717) "STARTUP_CRASH" else "RUNTIME_CRASH"
        val sup = if (suppressUntilMs > System.currentTimeMillis()) " manualSuppressedForMin=${(suppressUntilMs - System.currentTimeMillis()) / 60_000L}" else ""
        return "$kind build=$lastCrashBuild ageMin=$ageMin uptimeAtCrashMs=$lastCrashUptimeMs streak=$startupCrashStreak$sup\n" +
            lastCrashHead.lines().take(14).joinToString("\n") { "      $it" }
    }

    fun statusLine(): String =
        "build=$thisBuild uptimeMs=${uptimeMs()} suppressed=${manualSuppressed()} suppressedReads=${suppressedReads.get()} lastCrashAgeMin=${if (lastCrashMs > 0L) (System.currentTimeMillis() - lastCrashMs) / 60_000L else -1} streak=$startupCrashStreak"
}
