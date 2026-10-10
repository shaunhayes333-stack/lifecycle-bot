package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8010 — staying awake, measured.
 *
 * The loop's own gap check used the wall clock, which cannot tell "the CPU was asleep" from "a
 * cycle ran long" (or a clock change). Android keeps two monotonic clocks: elapsedRealtime
 * counts while the CPU sleeps, uptimeMillis stops. Between two loop ticks their difference is
 * exactly how long the phone suspended the app — the one number that says whether the bot was
 * really awake while you were not looking. Recorded per tick, kept as count / max / last, and
 * shown in the diag with the battery-whitelist and exact-alarm state and the phone maker's
 * own background-kill setting (OEM skins kill foreground services that stock Android keeps).
 */
object Awake8010 {
    private const val SUSPEND_MIN_MS = 5_000L
    private var lastElapsed = 0L
    private var lastUptime = 0L
    private val suspends = AtomicLong(0)
    private val suspendedMs = AtomicLong(0)
    @Volatile private var maxSuspendMs = 0L
    @Volatile private var lastSuspendAtMs = 0L
    @Volatile private var maxTickGapMs = 0L
    @Volatile private var whitelisted: Boolean? = null
    @Volatile private var exactAlarms: Boolean? = null

    /** Pure: ms the CPU was suspended between two ticks (elapsed-realtime delta minus uptime delta). */
    fun suspendedBetween8010(dElapsedMs: Long, dUptimeMs: Long): Long = (dElapsedMs - dUptimeMs).coerceAtLeast(0L)

    @Synchronized
    fun noteTick(elapsedRealtimeMs: Long, uptimeMs: Long, wallMs: Long = System.currentTimeMillis()) {
        if (lastElapsed > 0L) {
            val dE = elapsedRealtimeMs - lastElapsed
            val slept = suspendedBetween8010(dE, uptimeMs - lastUptime)
            if (dE > maxTickGapMs) maxTickGapMs = dE
            if (slept >= SUSPEND_MIN_MS) {
                suspends.incrementAndGet(); suspendedMs.addAndGet(slept)
                if (slept > maxSuspendMs) maxSuspendMs = slept
                lastSuspendAtMs = wallMs
                try {
                    PipelineHealthCollector.labelInc("CPU_SUSPENDED_GAP_8010_" + when {
                        slept < 30_000L -> "LT30S"; slept < 120_000L -> "LT2M"; slept < 600_000L -> "LT10M"; else -> "GE10M"
                    })
                } catch (_: Throwable) {}
            }
        }
        lastElapsed = elapsedRealtimeMs; lastUptime = uptimeMs
    }

    fun noteEnvironment(batteryWhitelisted: Boolean?, canScheduleExact: Boolean?) {
        whitelisted = batteryWhitelisted; exactAlarms = canScheduleExact
    }

    /** Pure: the setting a phone maker's skin needs so it stops killing a background trader. */
    fun oemHint8010(manufacturer: String): String {
        val m = manufacturer.lowercase()
        val how = when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                "Settings › Apps › AATE › Autostart ON, Battery saver › No restrictions"
            m.contains("samsung") ->
                "Settings › Battery › Background usage limits › Never sleeping apps › add AATE"
            m.contains("huawei") || m.contains("honor") ->
                "Settings › Battery › App launch › AATE › Manage manually (all ON)"
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ->
                "Settings › Battery › AATE › Allow background activity + Auto-launch ON"
            m.contains("vivo") || m.contains("iqoo") ->
                "Settings › Battery › Background power consumption › AATE › Allow"
            else -> "Settings › Apps › AATE › Battery › Unrestricted"
        }
        return "$how (dontkillmyapp.com/${m.substringBefore(' ').ifBlank { "general" }})"
    }

    fun statusLine(): String {
        val last = if (lastSuspendAtMs > 0L) "${(System.currentTimeMillis() - lastSuspendAtMs) / 60_000L}m ago" else "never"
        return "cpuSuspends=${suspends.get()} total=${suspendedMs.get() / 1000}s max=${maxSuspendMs / 1000}s last=$last " +
            "tickGapMax=${maxTickGapMs / 1000}s batteryWhitelisted=${whitelisted ?: "?"} exactAlarms=${exactAlarms ?: "?"}" +
            (if (whitelisted == false) " FIX: ${oemHint8010(android.os.Build.MANUFACTURER.orEmpty())}" else "")
    }
}
