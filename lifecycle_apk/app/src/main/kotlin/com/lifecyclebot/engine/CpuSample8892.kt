package com.lifecyclebot.engine

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8892 §WHAT_THE_CPU_IS_DOING — owner: "proceed with the required changes". The 20 Nov phone: 5–7 GB allocated a
 * minute, the collector on ~31 s of CPU a minute, the bot cycle 3.5–5 s, the heap at 100 % after 25 minutes on 5.0.8882.
 * The heap map (§8866) names what HOLDS the heap; at 295 MB it caught 16 MB of garbage, too little to name what churns it.
 * The job meter (§8813) sees only its nine batch jobs, and only as an upper bound.
 *
 * This takes ART's own sampling profile of the app, on the owner's tap after a report save (beside the memory map): for
 * [SECONDS_8892] s, every [INTERVAL_US_8892] µs, the method stack of every thread (android.os.Debug.startMethodTracingSampling),
 * written to the app's cache, saved to Downloads/AATE and deleted. The code that runs most is the code that allocates and
 * computes most, named by method, with each thread's share. What it holds: thread names, class and method names, sample
 * times — no field or variable value, no key, nothing of the wallet. The bot keeps trading while it runs (each sample pauses
 * the threads for a moment; the interval is long so the pause is short).
 */
object CpuSample8892 {
    private const val DIR_8892 = "cpu_sample_8892"
    internal const val SECONDS_8892 = 30L
    internal const val INTERVAL_US_8892 = 20_000
    private const val BUFFER_BYTES_8892 = 32 * 1024 * 1024
    private val running = AtomicBoolean(false)
    @Volatile private var last8892 = "none taken"

    fun isRunning8892(): Boolean = running.get()

    /** AATEApp: a sample file left by a process that died mid-sample is deleted at start. */
    fun sweep8892(ctx: Context) {
        try { File(ctx.cacheDir, DIR_8892).listFiles()?.forEach { f -> try { f.delete() } catch (_: Throwable) {} } } catch (_: Throwable) {}
    }

    /**
     * ReportCopyDialog8198 (the owner's tap): samples on its own thread, saves through [save] and deletes the file. [onDone]
     * gets one line (where it was saved, or why it was not). False when one is already running.
     */
    fun start8892(app: Context, save: (File, String) -> String?, onDone: (String) -> Unit = {}): Boolean {
        if (!running.compareAndSet(false, true)) { onDone("a CPU sample is already running"); return false }
        Thread({
            var file: File? = null
            var line = "FAILED"
            try {
                val dir = File(app.cacheDir, DIR_8892).apply { mkdirs() }
                dir.listFiles()?.forEach { f -> try { f.delete() } catch (_: Throwable) {} }
                val at = System.currentTimeMillis()
                val f = File(dir, "cpu_$at.trace")
                file = f
                android.os.Debug.startMethodTracingSampling(f.absolutePath, BUFFER_BYTES_8892, INTERVAL_US_8892)
                try { Thread.sleep(SECONDS_8892 * 1000L) } finally { android.os.Debug.stopMethodTracing() }
                line = if (f.exists() && f.length() > 0L) {
                    val saved = save(f, "aate_cpu_sample_8892_$at.trace")
                    if (saved != null) "saved Downloads/AATE/$saved (${f.length() shr 10} KB, ${SECONDS_8892}s at ${INTERVAL_US_8892 / 1000}ms)" else "SAVE_FAILED"
                } else "NOT_WRITTEN"
            } catch (t: Throwable) {
                line = "FAILED:${t.javaClass.simpleName}:${t.message.orEmpty().take(80)}"
            } finally {
                try { file?.delete() } catch (_: Throwable) {}
                last8892 = line
                running.set(false)
                try { PipelineHealthCollector.labelInc(if (line.startsWith("saved")) "CPU_SAMPLE_SAVED_8892" else "CPU_SAMPLE_NOT_SAVED_8892") } catch (_: Throwable) {}
                try { onDone(line) } catch (_: Throwable) {}
            }
        }, "cpu-sample-8892").apply { isDaemon = true }.start()
        return true
    }

    /** Brief: the last sample's outcome. */
    fun statusLine8892(): String =
        "cpuSample8892[${if (running.get()) "running" else last8892} rule=on the owner's tap after Save: ${SECONDS_8892}s of ART's sampling profile, " +
            "every ${INTERVAL_US_8892 / 1000} ms, method names and times only, saved to Downloads/AATE and deleted]"
}
