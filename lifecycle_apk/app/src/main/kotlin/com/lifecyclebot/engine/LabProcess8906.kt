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
 * Stage 2 (8907): BaseFamily8754's grading in LaunchReplay8744's 30-minute run — the paths go to the lab as a binary file,
 * the graded rules come back as (rule index, numbers); the replay thread waits up to [FAMILY_WAIT_MS_8906] and grades here
 * if the lab did not answer. baseFamily8754[... where8906=lab|here] says which ran.
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
        val inPath = intent?.getStringExtra(EXTRA_IN_8906).orEmpty()
        Thread({
            try { run8906(applicationContext, kind, token, unit, inPath) } finally { try { stopSelf(startId) } catch (_: Throwable) {} }
        }, "lab8906").start()
        return START_NOT_STICKY
    }

    companion object {
        internal const val PROCESS_SUFFIX_8906 = ":lab8906"
        internal const val KIND_LAUNCH_HOUR_8906 = "LAUNCH_HOUR"
        internal const val KIND_BASE_FAMILY_8906 = "BASE_FAMILY"
        private const val EXTRA_IN_8906 = "in8906"
        internal const val FAMILY_WAIT_MS_8906 = 90_000L
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
        private fun run8906(ctx: Context, kind: String, token: String, unit: Long, inPath: String) {
            if (token.isBlank()) return
            val marker = File(dir8906(ctx), "$token.done")
            val result = try {
                when (kind) {
                    KIND_LAUNCH_HOUR_8906 -> com.lifecyclebot.engine.truth.LaunchHour8628.export(dir8906(ctx), System.currentTimeMillis(), unit)?.absolutePath ?: NONE_8906
                    KIND_BASE_FAMILY_8906 -> familyJob8906(ctx, inPath, token)
                    else -> FAIL_8906
                }
            } catch (_: Throwable) { FAIL_8906 }
            try { File(marker.path + ".tmp").apply { writeText(result) }.renameTo(marker) } catch (_: Throwable) {}
        }

        // ── stage 2: BaseFamily8754's grading (04:38 CPU sample on 5.0.8900: BaseFamily8754.simulate 6.4 s of 30 s) ──

        /** Write [set] and the run's inputs (binary, streamed). */
        internal fun writePaths8906(f: File, set: List<com.lifecyclebot.engine.truth.LaunchReplay8744.Path8748>, solUsd: Double, costPct: Double, needMs: Long) {
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(f))).use { o ->
                o.writeDouble(solUsd); o.writeDouble(costPct); o.writeLong(needMs); o.writeInt(set.size)
                for (x in set) {
                    val n = x.t.size; o.writeInt(n); o.writeInt(x.spikes); o.writeLong(x.bornMs)
                    for (i in 0 until n) { o.writeLong(x.t[i]); o.writeDouble(x.p[i]); o.writeBoolean(x.buy[i]); o.writeBoolean(x.live[i]) }
                    val sol = x.sol; o.writeBoolean(sol != null); if (sol != null) for (v in sol) o.writeDouble(v)
                    val w = x.wallet; o.writeBoolean(w != null); if (w != null) for (v in w) o.writeInt(v)
                }
            }
        }

        /** Read what [writePaths8906] wrote: (solUsd, cost, need) and the paths. */
        internal fun readPaths8906(f: File): Pair<Triple<Double, Double, Long>, List<com.lifecyclebot.engine.truth.LaunchReplay8744.Path8748>> =
            java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f))).use { i ->
                val sol = i.readDouble(); val cost = i.readDouble(); val need = i.readLong(); val count = i.readInt()
                val out = ArrayList<com.lifecyclebot.engine.truth.LaunchReplay8744.Path8748>(count)
                repeat(count) {
                    val n = i.readInt(); val spikes = i.readInt(); val born = i.readLong()
                    val t = LongArray(n); val p = DoubleArray(n); val b = BooleanArray(n); val l = BooleanArray(n)
                    for (k in 0 until n) { t[k] = i.readLong(); p[k] = i.readDouble(); b[k] = i.readBoolean(); l[k] = i.readBoolean() }
                    val sv = if (i.readBoolean()) DoubleArray(n) { i.readDouble() } else null
                    val wv = if (i.readBoolean()) IntArray(n) { i.readInt() } else null
                    out += com.lifecyclebot.engine.truth.LaunchReplay8744.Path8748(t, p, b, l, spikes, sv, wv, born)
                }
                Triple(sol, cost, need) to out
            }

        /** Write graded rules as (index into BaseFamily8754.rules8906(need), the nine numbers). */
        internal fun writeGraded8906(f: File, res: List<com.lifecyclebot.engine.truth.BaseFamily8754.Graded>, needMs: Long) {
            val rules = com.lifecyclebot.engine.truth.BaseFamily8754.rules8906(needMs)
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(f))).use { o ->
                o.writeInt(res.size)
                for (g in res) {
                    o.writeInt(rules.indexOf(g.rule)); o.writeInt(g.n)
                    for (v in doubleArrayOf(g.mean, g.median, g.exTop3, g.exTop3Se, g.win, g.twoX, g.tenX, g.fiftyX)) o.writeDouble(v)
                }
            }
        }

        /** Read [writeGraded8906]'s file; null when a rule index does not resolve (the two processes disagree on the grid). */
        internal fun readGraded8906(f: File, needMs: Long): List<com.lifecyclebot.engine.truth.BaseFamily8754.Graded>? {
            val rules = com.lifecyclebot.engine.truth.BaseFamily8754.rules8906(needMs)
            return java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f))).use { i ->
                val out = ArrayList<com.lifecyclebot.engine.truth.BaseFamily8754.Graded>()
                repeat(i.readInt()) {
                    val idx = i.readInt(); val n = i.readInt(); val v = DoubleArray(8) { i.readDouble() }
                    val rule = rules.getOrNull(idx) ?: return@use null
                    out += com.lifecyclebot.engine.truth.BaseFamily8754.Graded(rule, n, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])
                }
                out
            }
        }

        /** In the lab: grade the family on the paths at [inPath]; the result file's path, or [FAIL_8906]. */
        private fun familyJob8906(ctx: Context, inPath: String, token: String): String {
            val inFile = File(inPath).takeIf { it.exists() } ?: return FAIL_8906
            val (args, set) = readPaths8906(inFile)
            val res = com.lifecyclebot.engine.truth.BaseFamily8754.grade8906(set, args.first, args.second, args.third)
            val out = File(dir8906(ctx), "$token.graded")
            writeGraded8906(out, res, args.third)
            return out.absolutePath
        }

        /**
         * In the trading process (LaunchReplay8744's own thread): BaseFamily8754's grading, run in the lab. Null when the lab did
         * not answer in [FAMILY_WAIT_MS_8906] — the caller then grades here, exactly as before 8906.
         */
        fun baseFamily8906(set: List<com.lifecyclebot.engine.truth.LaunchReplay8744.Path8748>, solUsd: Double, costPct: Double,
                           needMs: Long): List<com.lifecyclebot.engine.truth.BaseFamily8754.Graded>? {
            val app = com.lifecyclebot.AATEApp.appContextOrNull() ?: return null
            sweep8906(app, System.currentTimeMillis())
            val token = "bf_${System.currentTimeMillis()}_${(Math.random() * 1e9).toLong()}"
            val inFile = File(dir8906(app), "$token.paths")
            val marker = File(dir8906(app), "$token.done")
            try {
                writePaths8906(inFile, set, solUsd, costPct, needMs)
                val started = app.startService(Intent(app, LabProcess8906::class.java).putExtra(EXTRA_KIND_8906, KIND_BASE_FAMILY_8906)
                    .putExtra(EXTRA_TOKEN_8906, token).putExtra(EXTRA_IN_8906, inFile.absolutePath)) != null
                if (!started) return null
                val until = System.currentTimeMillis() + FAMILY_WAIT_MS_8906
                while (System.currentTimeMillis() < until && !marker.exists()) { try { Thread.sleep(250L) } catch (_: InterruptedException) { break } }
                if (!marker.exists()) return null
                val (handled, file) = readMarker8906(marker.readText())
                if (!handled || file == null) return null
                return try { readGraded8906(file, needMs) } finally { file.delete() }
            } catch (_: Throwable) { return null }
            finally {
                try { inFile.delete() } catch (_: Throwable) {}
                try { marker.delete() } catch (_: Throwable) {}
            }
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
