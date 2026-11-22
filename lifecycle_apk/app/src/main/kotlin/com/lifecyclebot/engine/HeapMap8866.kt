package com.lifecyclebot.engine

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8866 — the memory map: an opt-in heap dump, read on the phone down to class names, field names and sizes
 * (HeapMapCore8866), saved as text. The owner asks for it from the report's Save dialog; nothing takes it by itself.
 *
 * The raw dump holds every byte of the heap, the wallet's keys included, so it is handled as a secret:
 *   - written only under the app's private cache directory (never shared storage, never backed up);
 *   - deleted as soon as it has been read, in a finally, and again at every app start ([sweep8866]) in case the
 *     process died in between;
 *   - never exported: what leaves is the text, which carries no value of any field, array or string.
 *
 * Taking the dump stops every thread of the app for as long as the runtime needs to write the heap (seconds to tens
 * of seconds at 500 MB): the dialog says so before the owner agrees.
 */
object HeapMap8866 {
    private const val DIR = "heapmap_8866"
    private const val LAST = "heapmap_last_8866.txt"
    private const val REPORT_LINES = 90

    private val running = AtomicBoolean(false)
    @Volatile private var phase = ""
    @Volatile private var lastStatus = ""
    @Volatile private var lastText: String? = null
    @Volatile private var lastAtMs = 0L
    @Volatile private var appFilesDir: File? = null

    /** Pure: free disk the dump needs: the whole heap limit plus the system's shared heaps, with headroom. */
    fun diskNeeded8866(maxHeapBytes: Long): Long = maxHeapBytes + maxHeapBytes / 2 + (128L shl 20)

    /** Pure: the report's block: the first [maxLines] lines of the last map, or how to take one. */
    fun reportBlock8866(text: String?, takenAtMs: Long, nowMs: Long, status: String, maxLines: Int = REPORT_LINES): String {
        if (text.isNullOrBlank()) {
            return "  none taken in this install" + (if (status.isBlank()) "" else " (last attempt: $status)") +
                ". To take one: Pipeline report > Save > Take memory map (the bot stops while the heap is written; only class names and sizes are kept)\n"
        }
        val lines = text.lineSequence().toList()
        val sb = StringBuilder()
        sb.append("  taken ").append(((nowMs - takenAtMs).coerceAtLeast(0L)) / 60_000L).append(" min before this report; ")
            .append(if (lines.size > maxLines) "first $maxLines of ${lines.size} lines (the full map is in Downloads/AATE)" else "complete").append('\n')
        for (l in lines.take(maxLines)) sb.append("  ").append(l).append('\n')
        return sb.toString()
    }

    /** AATEApp.onCreate: a raw dump a killed process left behind is deleted before anything else runs. */
    fun sweep8866(ctx: Context) {
        try {
            appFilesDir = ctx.filesDir
            File(ctx.cacheDir, DIR).listFiles()?.forEach { f -> if (f.name.endsWith(".hprof")) { try { f.delete() } catch (_: Throwable) {} } }
        } catch (_: Throwable) {}
    }

    fun isRunning8866(): Boolean = running.get()

    /**
     * Takes the map on its own thread. [onDone] receives one line (where it was saved, or why it was not) on that thread.
     * False when one is already running.
     */
    fun start8866(app: Context, why: String, save: (File, String) -> String?, onDone: (String) -> Unit = {}): Boolean {
        if (!running.compareAndSet(false, true)) { onDone("a memory map is already being taken"); return false }
        phase = "starting"
        Thread({
            var raw: File? = null
            var line = "FAILED"
            try {
                appFilesDir = app.filesDir
                val dir = File(app.cacheDir, DIR).apply { mkdirs() }
                dir.listFiles()?.forEach { f -> if (f.name.endsWith(".hprof")) { try { f.delete() } catch (_: Throwable) {} } }
                val rt = Runtime.getRuntime()
                val need = diskNeeded8866(rt.maxMemory())
                val free = dir.usableSpace
                if (free < need) {
                    line = "NOT_ENOUGH_DISK free=${free shr 20}MB need=${need shr 20}MB"
                } else {
                    val startedAt = System.currentTimeMillis()
                    // V5.0.8867 — no collection first: the garbage in the dump is what the app allocates and drops (5.0.8866
                    // phone: 49 GB allocated in 11 minutes, 4-8 GB a minute), and the map names it by structure. Retained
                    // owners are unchanged: garbage has no static or root holder.
                    val usedMb = (rt.totalMemory() - rt.freeMemory()) shr 20
                    val maxMb = rt.maxMemory() shr 20
                    val file = File(dir, "heap_$startedAt.hprof")
                    raw = file
                    phase = "writing the heap (the app is stopped)"
                    val t0 = System.currentTimeMillis()
                    try { android.util.Log.i("AATE_HEAPMAP", "HEAP_MAP_PHASE_8866 dumping usedMb=$usedMb maxMb=$maxMb") } catch (_: Throwable) {}
                    android.os.Debug.dumpHprofData(file.absolutePath)
                    val dumpMs = System.currentTimeMillis() - t0
                    // V5.0.8955 — 8954's device check waited 480 s for a map with no line to say where it was; each stage is logged
                    try { android.util.Log.i("AATE_HEAPMAP", "HEAP_MAP_PHASE_8866 dumped dumpMs=$dumpMs fileMb=${file.length() shr 20} usedMb=$usedMb") } catch (_: Throwable) {}
                    val uptimeS = try { (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1000L } catch (_: Throwable) { -1L }
                    val header = "AATE heap map (§8866) version=" + (try { com.lifecyclebot.BuildConfig.VERSION_NAME } catch (_: Throwable) { "?" }) +
                        " taken=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(startedAt)) +
                        " uptimeS=" + uptimeS + " heapUsedMb=" + usedMb + " heapMaxMb=" + maxMb + " dumpMs=" + dumpMs + " gcFirst=false why=" + why.take(24)
                    // the heap is on disk now: the caches may go, so the reader below has room in a heap that was full
                    try { MemoryGuard7977.emergencyTrim8305("HEAP_MAP_8866") } catch (_: Throwable) {}
                    val result = HeapMapCore8866.analyse8866(file, header, progress = { stage ->
                        phase = stage
                        try { android.util.Log.i("AATE_HEAPMAP", "HEAP_MAP_PHASE_8866 $stage +${System.currentTimeMillis() - t0}ms") } catch (_: Throwable) {}
                    })
                    try { file.delete() } catch (_: Throwable) {}
                    raw = null
                    val name = "aate_heapmap_8866_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt)) + ".txt"
                    val out = File(app.filesDir, LAST)
                    out.writeText(result.text)
                    lastText = result.text
                    lastAtMs = startedAt
                    val saved = try { save(out, name) } catch (_: Throwable) { null }
                    line = (if (result.complete) "COMPLETE" else "STOPPED(" + result.stopped.take(80) + ")") +
                        " objects=" + result.objects + " mb=" + (result.bytes shr 20) + " samples=" + result.samples +
                        " dumpMs=" + dumpMs + " saved=" + (saved ?: "app storage only")
                    try { PipelineHealthCollector.labelInc(if (result.complete) "HEAP_MAP_COMPLETE_8866" else "HEAP_MAP_STOPPED_8866") } catch (_: Throwable) {}
                    try { android.util.Log.i("AATE_HEAPMAP", "HEAP_MAP_WRITTEN_8866 path=" + out.absolutePath + " " + line) } catch (_: Throwable) {}
                    try { ForensicLogger.lifecycle("HEAP_MAP_WRITTEN_8866", "path=" + out.absolutePath + " " + line) } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                line = "FAILED " + t.javaClass.simpleName + ": " + (t.message ?: "").take(100)
                try { PipelineHealthCollector.labelInc("HEAP_MAP_FAILED_8866") } catch (_: Throwable) {}
                try { android.util.Log.w("AATE_HEAPMAP", "HEAP_MAP_FAILED_8866 $line") } catch (_: Throwable) {}
            } finally {
                try { raw?.delete() } catch (_: Throwable) {}
                lastStatus = line
                phase = ""
                running.set(false)
                try { onDone(line) } catch (_: Throwable) {}
            }
        }, "heap-map-8866").start()
        return true
    }

    /** The pipeline report's HEAP MAP section. */
    fun section8866(nowMs: Long = System.currentTimeMillis()): String {
        if (running.get()) return "  being taken now: $phase\n"
        var text = lastText
        var at = lastAtMs
        if (text == null) {
            val f = appFilesDir?.let { File(it, LAST) }
            if (f != null && f.exists() && f.length() in 1L..(1L shl 20)) {
                try { text = f.readText(); at = f.lastModified(); lastText = text; lastAtMs = at } catch (_: Throwable) {}
            }
        }
        return reportBlock8866(text, at, nowMs, lastStatus)
    }
}
