package com.lifecyclebot.engine

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.io.File

/**
 * V5.0.8906 §THE_LAB_HAS_ITS_OWN_HEAP — owner, 21 Nov 14:21: "we cant move any thing into helps or co routines to reduce
 * the memory", then "yees" to a second process. A thread or coroutine shares the trading process's 512 MB heap; a second
 * Android process has its own. This is that process (android:process=":lab8906"), for offline work that allocates heavily
 * and is not on the trading path.
 *
 * Stage 1, the owner's Save: LaunchHour8628.export, the launch-hour dataset (02:09 CPU sample on 5.0.8899: 14.4 s of CPU in
 * 30 s, its garbage landing while the report is captured). The lab opens the same database read-only, as the export always
 * did, and writes the file under cacheDir/lab8906; the one in-process value the export prints (the unit-mismatch counter) is
 * handed over in the intent. The trading process waits for a done marker and saves the file as before; if the lab does not
 * answer in [WAIT_MS_8906] (or cannot start), the trading process exports it itself, exactly as before 8906. AATEApp.onCreate
 * returns at once in this process: no sweep, crash mark, learning reset, memory guard, journal or bot runs here.
 */
class LabProcess8906 : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kind = intent?.getStringExtra(EXTRA_KIND_8906).orEmpty()
        val token = intent?.getStringExtra(EXTRA_TOKEN_8906).orEmpty()
        val unit = intent?.getLongExtra(EXTRA_UNIT_8906, 0L) ?: 0L
        Thread({
            try { run8906(applicationContext, kind, token, unit) } finally { try { stopSelf(startId) } catch (_: Throwable) {} }
        }, "lab8906").start()
        return START_NOT_STICKY
    }

    companion object {
        internal const val PROCESS_SUFFIX_8906 = ":lab8906"
        internal const val KIND_LAUNCH_HOUR_8906 = "LAUNCH_HOUR"
        private const val EXTRA_KIND_8906 = "kind8906"
        private const val EXTRA_TOKEN_8906 = "token8906"
        private const val EXTRA_UNIT_8906 = "unit8906"
        internal const val WAIT_MS_8906 = 120_000L
        internal const val NONE_8906 = "NONE"
        internal const val FAIL_8906 = "FAIL"

        /** Is this process the lab? (Application.getProcessName from API 28; /proc/self/cmdline before.) */
        fun isLabProcess8906(ctx: Context): Boolean = try {
            val name = if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
                else File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
            isLabName8906(name.orEmpty(), ctx.packageName)
        } catch (_: Throwable) { false }

        /** Pure: is [processName] the lab process of [packageName]? */
        internal fun isLabName8906(processName: String, packageName: String): Boolean = processName == packageName + PROCESS_SUFFIX_8906

        internal fun dir8906(ctx: Context): File = File(ctx.cacheDir, "lab8906").apply { mkdirs() }

        /** Pure: what a done marker's text means — the exported file, no rows (null, handled), or not handled (failed). */
        internal fun readMarker8906(text: String): Pair<Boolean, File?> = when (val t = text.trim()) {
            NONE_8906 -> true to null
            FAIL_8906, "" -> false to null
            else -> File(t).takeIf { it.exists() }?.let { true to it } ?: (false to null)
        }

        /** Leftovers of a lab job the trading process stopped waiting for (older than ten minutes) are deleted. */
        private fun sweep8906(ctx: Context, nowMs: Long) {
            try { dir8906(ctx).listFiles()?.forEach { if (nowMs - it.lastModified() > 10L * 60_000L) it.delete() } } catch (_: Throwable) {}
        }

        /** In the lab: run one job and leave its done marker (the file's path, [NONE_8906] or [FAIL_8906]). */
        private fun run8906(ctx: Context, kind: String, token: String, unit: Long) {
            if (token.isBlank()) return
            val marker = File(dir8906(ctx), "$token.done")
            val result = try {
                when (kind) {
                    KIND_LAUNCH_HOUR_8906 -> com.lifecyclebot.engine.truth.LaunchHour8628.export(dir8906(ctx), System.currentTimeMillis(), unit)?.absolutePath ?: NONE_8906
                    else -> FAIL_8906
                }
            } catch (_: Throwable) { FAIL_8906 }
            try { File(marker.path + ".tmp").apply { writeText(result) }.renameTo(marker) } catch (_: Throwable) {}
        }

        /**
         * In the trading process (off the main thread): the launch-hour export, made in the lab; this process's own export
         * when the lab did not answer. Null when there are no launch observations, as before.
         */
        fun launchHourExport8906(app: Context): File? {
            sweep8906(app, System.currentTimeMillis())
            val token = "lh_${System.currentTimeMillis()}_${(Math.random() * 1e9).toLong()}"
            val marker = File(dir8906(app), "$token.done")
            val started = try {
                app.startService(Intent(app, LabProcess8906::class.java).putExtra(EXTRA_KIND_8906, KIND_LAUNCH_HOUR_8906)
                    .putExtra(EXTRA_TOKEN_8906, token)
                    .putExtra(EXTRA_UNIT_8906, com.lifecyclebot.engine.truth.LaunchHour8628.unitMismatchRejected8906())) != null
            } catch (_: Throwable) { false }
            if (started) {
                val until = System.currentTimeMillis() + WAIT_MS_8906
                while (System.currentTimeMillis() < until && !marker.exists()) { try { Thread.sleep(250L) } catch (_: InterruptedException) { break } }
                if (marker.exists()) {
                    val (handled, file) = try { readMarker8906(marker.readText()) } catch (_: Throwable) { false to null }
                    try { marker.delete() } catch (_: Throwable) {}
                    if (handled) {
                        try { PipelineHealthCollector.labelInc("LAB_8906_EXPORT_OK") } catch (_: Throwable) {}
                        return file
                    }
                }
            }
            try { PipelineHealthCollector.labelInc("LAB_8906_EXPORT_FALLBACK") } catch (_: Throwable) {}
            return com.lifecyclebot.engine.truth.LaunchHour8628.export(app.cacheDir, System.currentTimeMillis())
        }
    }
}
