package com.lifecyclebot.engine.truth

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * V5.0.9030 §WHY_THE_PROCESS_LAST_DIED — owner, 26 Nov 19:44: "its randomly dropping the connected wallet pumpfun api and
 * others".
 *
 * When the app's process dies and Android starts it again, everything held in memory goes with it: the wallet signer, every
 * socket (PumpPortal, Helius, DexScreener), every subscription. From the screen that is the wallet and the feeds dropping
 * at once. The reports carried no record of it:
 *  - StartupCrashGuard7717 records an uncaught Java exception, and its report line hides a crash from an earlier build —
 *    26 Nov 14:24 printed "Last crash: none recent" beside `lastCrashAgeMin=146`: the 5.0.9023 process had died at 11:58
 *    (the 12:31 heap map: uptimeS=2014) and the owner had installed 5.0.9026 since;
 *  - a death that is not a Java exception — Android's low-memory kill, an ANR kill, a native crash — was recorded nowhere.
 * Android keeps the reason itself (ActivityManager.getHistoricalProcessExitReasons, Android 11+): LOW_MEMORY, ANR, CRASH,
 * CRASH_NATIVE, SIGNALED, USER_REQUESTED ..., with the time and the memory the process held. This reads it for the report.
 * Read-only: nothing here changes what the app does.
 */
object ProcessExits9030 {

    /** Pure: Android's exit reason code as its name (ApplicationExitInfo.REASON_*). */
    fun reasonName9030(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "REASON_$reason"
    }

    /** Pure: one process exit as a report row. Sizes arrive in kB. */
    fun row9030(reason: Int, status: Int, atMs: Long, nowMs: Long, process: String, description: String?, pssKb: Long, rssKb: Long): String {
        val agoMin = (nowMs - atMs).coerceAtLeast(0L) / 60_000L
        val proc = process.substringAfterLast(':', "main").ifBlank { "main" }
        val desc = description?.takeIf { it.isNotBlank() }?.let { " desc=" + it.replace('\n', ' ').take(160) } ?: ""
        return "${reasonName9030(reason)} ${agoMin}min ago process=$proc status=$status pss=${pssKb / 1024}MB rss=${rssKb / 1024}MB$desc"
    }

    /** The last [max] exits of this app's processes as Android recorded them, newest first. */
    fun lines9030(ctx: Context, max: Int = 8, nowMs: Long = System.currentTimeMillis()): List<String> = try {
        if (Build.VERSION.SDK_INT < 30) listOf("not recorded by Android below version 11")
        else {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, max)
            if (exits.isEmpty()) listOf("none recorded by Android")
            else exits.map { row9030(it.reason, it.status, it.timestamp, nowMs, it.processName.orEmpty(), it.description, it.pss, it.rss) }
        }
    } catch (t: Throwable) { listOf("unreadable: ${t.javaClass.simpleName}") }

    /** The report block: one indented row per exit. */
    fun block9030(ctx: Context?, max: Int = 8): String =
        if (ctx == null) "      no context" else lines9030(ctx, max).joinToString("\n") { "      $it" }
}
