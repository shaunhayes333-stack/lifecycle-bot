package com.lifecyclebot.engine.truth

import android.content.Context
import android.os.PowerManager
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6440 — TRADING RUNTIME HEALTH WATCHDOG.
 *
 * OPERATOR DIRECTIVE:
 *   "Foreground Trading Service: Extract TradingRuntimeService so trading
 *    survives UI backgrounding on Android Doze."
 *
 * PRIOR STATE (audit):
 *   BotService is ALREADY declared as a foreground service in the manifest
 *   (`android:foregroundServiceType="dataSync|specialUse"`,
 *   `android:stopWithTask="false"`), acquires a `PARTIAL_WAKE_LOCK`
 *   ("lifecyclebot:trading:always_on_6031") + a high-perf WifiLock, and
 *   schedules a 60s AlarmManager keepalive using `setExactAndAllowWhileIdle`
 *   for Doze survival. BotService.kt has no UI-lifecycle coupling (no
 *   references to onPause / isVisible / isInForeground). The "extraction"
 *   the operator requested is therefore ALREADY DONE at the JVM level —
 *   the trading engine runs in a service, not an activity.
 *
 * WHAT WAS MISSING:
 *   An operator-facing signal that PROVES the service is still foreground
 *   and the wake-lock is still held across a Doze window. Without this
 *   telemetry, the operator can't tell whether the bot silently dropped
 *   off the CPU or is genuinely running.
 *
 * WHAT THIS MODULE DOES:
 *   • Every cycle notes the current foreground state (via BotService flag
 *     when supplied) and PowerManager idle state (Doze detection).
 *   • Emits TRADING_RUNTIME_ALIVE_6440 lifecycle heartbeat every 60s so
 *     the ForensicLog shows a proof-of-life stream.
 *   • On Doze transition emits TRADING_RUNTIME_DOZE_ENTER_6440 /
 *     TRADING_RUNTIME_DOZE_EXIT_6440 so the operator can correlate a
 *     dump gap with the OS state at that moment.
 *   • statusLine() lands in the pipeline health dump so operator sees
 *     current fg + wake + doze + last-heartbeat-age at a glance.
 *
 * The watchdog is passive: it never toggles wake-lock or foreground
 * state. Those are owned by BotService.ensureAlwaysOnRuntimeGuards6031.
 * This module is diagnostic-only.
 */
object TradingRuntimeHealthWatchdog6440 {

    private const val HEARTBEAT_INTERVAL_MS: Long = 60_000L

    private val lastHeartbeatMs = AtomicLong(0L)
    private val heartbeats = AtomicLong(0L)
    private val dozeEnterCount = AtomicLong(0L)
    private val dozeExitCount = AtomicLong(0L)
    private val wasDoze = AtomicReference<Boolean>(false)
    private val lastForeground = AtomicReference<Boolean>(false)
    private val lastWakeHeld = AtomicReference<Boolean>(false)

    /**
     * Called from the bot loop each cycle. Cheap: only emits the
     * heartbeat when HEARTBEAT_INTERVAL_MS has elapsed since the
     * previous one. Doze transitions emit immediately.
     */
    fun onCycleTick(context: Context, isServiceForeground: Boolean, isWakeHeld: Boolean) {
        val now = System.currentTimeMillis()
        val pm = try { context.getSystemService(Context.POWER_SERVICE) as? PowerManager } catch (_: Throwable) { null }
        val doze = try { pm?.isDeviceIdleMode ?: false } catch (_: Throwable) { false }
        lastForeground.set(isServiceForeground)
        lastWakeHeld.set(isWakeHeld)

        val prevDoze = wasDoze.getAndSet(doze)
        if (doze != prevDoze) {
            if (doze) {
                dozeEnterCount.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "TRADING_RUNTIME_DOZE_ENTER_6440",
                        "fg=$isServiceForeground wake=$isWakeHeld — expect longer cycles until doze exit",
                    )
                } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("TRADING_RUNTIME_DOZE_ENTER_6440") } catch (_: Throwable) {}
            } else {
                dozeExitCount.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "TRADING_RUNTIME_DOZE_EXIT_6440",
                        "fg=$isServiceForeground wake=$isWakeHeld",
                    )
                } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("TRADING_RUNTIME_DOZE_EXIT_6440") } catch (_: Throwable) {}
            }
        }

        val lastHb = lastHeartbeatMs.get()
        if (now - lastHb >= HEARTBEAT_INTERVAL_MS) {
            lastHeartbeatMs.set(now)
            heartbeats.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "TRADING_RUNTIME_ALIVE_6440",
                    "fg=$isServiceForeground wake=$isWakeHeld doze=$doze heartbeatN=${heartbeats.get()}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("TRADING_RUNTIME_ALIVE_6440") } catch (_: Throwable) {}
        }
    }

    fun statusLine(): String {
        val hb = heartbeats.get()
        val lastAgeSec =
            if (lastHeartbeatMs.get() <= 0L) -1L
            else (System.currentTimeMillis() - lastHeartbeatMs.get()) / 1000L
        val doze = wasDoze.get()
        val fg = lastForeground.get()
        val wake = lastWakeHeld.get()
        return "heartbeats=$hb lastHbAgeSec=$lastAgeSec fg=$fg wake=$wake doze=$doze " +
            "dozeEnters=${dozeEnterCount.get()} dozeExits=${dozeExitCount.get()}"
    }
}
