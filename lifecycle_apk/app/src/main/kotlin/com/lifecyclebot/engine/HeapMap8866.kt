package com.lifecyclebot.engine

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8866 — the memory map: an opt-in heap dump, read on the phone down to class names, field names and sizes
 * (HeapMapCore8866), saved as text. The owner asks for it from the report's Save dialog; since 8982 the run also takes one
 * map by itself when a hard trim frees nothing at 85 %+ ([maybeAuto8982]), read in the lab process.
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
    private const val REPORT_LINES = 130   // V5.0.9033 (was 90): room for the holder section
    internal const val REPORT_CLASS_ROWS_9033 = 18
    internal const val OWNER_CHAINS_9033 = 3

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
        val shown = excerpt9033(lines, maxLines)   // V5.0.9033 — the holders, not 70 rows of class names
        val sb = StringBuilder()
        sb.append("  taken ").append(((nowMs - takenAtMs).coerceAtLeast(0L)) / 60_000L).append(" min before this report; ")
            .append(when {
                lines.size <= maxLines -> "complete"
                shown == lines.take(maxLines) -> "first $maxLines of ${lines.size} lines (the full map is in Downloads/AATE)"
                else -> "${shown.size} of ${lines.size} lines: the largest $REPORT_CLASS_ROWS_9033 classes, then every owner row (the full map is in Downloads/AATE)"
            }).append('\n')
        for (l in shown) sb.append("  ").append(l).append('\n')
        return sb.toString()
    }

    /**
     * Pure (V5.0.9033): the lines of a map the report shows. The 26 Nov 23:38 report showed the first 90 lines of a 712-line
     * map: 70 rows of class names (byte[] 132 MB in 1.5 million arrays, which is every String) and 9 rows of the section
     * that names their owners, so the report could not say what held the heap. Now: the header, the largest [classRows]
     * classes, then the holder section: every owner row (the rows with a share: "12.6% n=") and the chains under the
     * first [OWNER_CHAINS_9033] owners. A text without the two section headings is cut at [maxLines] as before.
     */
    internal fun excerpt9033(lines: List<String>, maxLines: Int, classRows: Int = REPORT_CLASS_ROWS_9033): List<String> {
        if (lines.size <= maxLines) return lines
        val cls = lines.indexOfFirst { it.startsWith("-- classes holding the most bytes") }
        val hold = lines.indexOfFirst { it.startsWith("-- what holds the bytes") }
        val headEnd = cls + 1 + classRows
        if (cls < 0 || hold <= headEnd || headEnd + 2 >= maxLines) return lines.take(maxLines)
        val out = ArrayList<String>(maxLines)
        out.addAll(lines.subList(0, headEnd))
        out.add("      (and ${lines.subList(headEnd, hold).count { it.isNotBlank() }} more classes in the full map)")
        var owners = 0
        for (i in hold until lines.size) {
            if (out.size >= maxLines) break
            val owner = lines[i].contains("% n=")
            if (owner) owners++
            if (owner || owners <= OWNER_CHAINS_9033) out.add(lines[i])
        }
        return out
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
    fun start8866(app: Context, why: String, save: (File, String) -> String?, auto8982: Boolean = false, onDone: (String) -> Unit = {}): Boolean {
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
                    // V5.0.8982 — the reader needs room for a 400+ MB file's tables: it runs in the lab process (its own heap),
                    // not in a trading heap that was full enough to need the map. The lab deletes the dump when it has read it.
                    phase = "reading the map in the lab process"
                    var result = try { LabProcess8906.heapMap8982(app, file, header) } catch (_: Throwable) { null }
                    if (result != null) readInLab8982.incrementAndGet()
                    if (result == null && !auto8982) {
                        // the owner's map when the lab did not answer: read here, as before 8982
                        // the heap is on disk now: the caches may go, so the reader below has room in a heap that was full
                        try { MemoryGuard7977.emergencyTrim8305("HEAP_MAP_8866") } catch (_: Throwable) {}
                        result = HeapMapCore8866.analyse8866(file, header, progress = { stage ->
                            phase = stage
                            try { android.util.Log.i("AATE_HEAPMAP", "HEAP_MAP_PHASE_8866 $stage +${System.currentTimeMillis() - t0}ms") } catch (_: Throwable) {}
                        })
                    }
                    try { file.delete() } catch (_: Throwable) {}
                    raw = null
                    if (result == null) {
                        line = "LAB_NO_ANSWER_8982 (an automatic map is never read in the trading process) dumpMs=$dumpMs"
                        try { PipelineHealthCollector.labelInc("HEAP_MAP_LAB_NO_ANSWER_8982") } catch (_: Throwable) {}
                        return@Thread
                    }
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

    // ── V5.0.8982 §THE_MAP_TAKES_ITSELF ──
    // Owner, 24 Nov 12:11 ("nope. much worse"): 5.0.8978 died of OutOfMemoryError at 3 h 22 min (HeliusCallbacks7863, a 32-byte
    // allocation with the heap at its 512 MB limit) and traded nothing for its last 2 h 47 min. Every map so far was taken by
    // hand in the first two hours (259-454 MB), so none shows what holds the heap when it runs out. When a hard trim frees
    // nothing ([MemoryGuard7977], heap still 85 %+ a tick later) after 30 minutes of uptime, the map is taken once in that run
    // and read in the lab; its text is kept in app storage, so the next report carries it even after the process dies.
    // Taking it stops the app for the dump (8.3 s at 320 MB, 24 Nov 05:29): once a run, on a heap that is already spending
    // most of each second collecting.
    internal const val AUTO_MIN_UPTIME_MS_8982 = 30L * 60_000L
    internal const val AUTO_FRAC_8982 = 0.85
    private val autoTaken8982 = AtomicBoolean(false)
    private val readInLab8982 = java.util.concurrent.atomic.AtomicLong(0L)

    /** Pure: is an automatic map due — after [uptimeMs] of the run, a hard trim that left the heap at [fracAfter], none taken yet? */
    internal fun autoDue8982(uptimeMs: Long, fracAfter: Double, takenThisRun: Boolean): Boolean =
        !takenThisRun && uptimeMs >= AUTO_MIN_UPTIME_MS_8982 && fracAfter.isFinite() && fracAfter >= AUTO_FRAC_8982

    /** Pure (V5.0.9033): has this build already taken its automatic map? An unknown build name never blocks the map. */
    internal fun autoTakenForBuild9033(savedBuild: String?, thisBuild: String): Boolean =
        thisBuild.isNotBlank() && savedBuild == thisBuild

    /** MemoryGuard7977, after a hard trim that freed nothing: take the run's one automatic map when it is due. */
    fun maybeAuto8982(fracAfter: Double) {
        val app = com.lifecyclebot.AATEApp.appContextOrNull() ?: return
        val uptime = try { android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() } catch (_: Throwable) { return }
        if (!autoDue8982(uptime, fracAfter, autoTaken8982.get())) return
        // V5.0.9033 — the automatic map stopped the app for 70.6 s on 26 Nov 22:53 (434 MB; 8.3 s was the figure this rule was
        // written on) and then read a 538 MB file for 227 s beside the trading loop. It is now taken once per BUILD, not
        // once per run: the first run of a build that reaches the trigger takes it, later runs of that build do not. The
        // owner's own map (the tap after a report save) is unchanged.
        val build9033 = try { com.lifecyclebot.BuildConfig.VERSION_NAME } catch (_: Throwable) { "" }
        val prefs9033 = try { app.getSharedPreferences("heap_map_auto_9033", android.content.Context.MODE_PRIVATE) } catch (_: Throwable) { null }
        if (autoTakenForBuild9033(prefs9033?.getString("build", null), build9033)) { autoTaken8982.set(true); return }
        if (!autoTaken8982.compareAndSet(false, true)) return
        try { prefs9033?.edit()?.putString("build", build9033)?.apply() } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("HEAP_MAP_AUTO_8982") } catch (_: Throwable) {}
        // saved to Downloads/AATE like the owner's map (only class names, field names and sizes)
        start8866(app, "auto-pressure", save = { file, name -> com.lifecyclebot.ui.ReportCopyDialog8198.saveDataset(app, file, name) }, auto8982 = true)
    }

    /** Brief: the automatic map's state. */
    fun autoLine8982(): String = "heapMapAuto8982[taken=${autoTaken8982.get()} readInLab=${readInLab8982.get()} " +
        "rule=once a build (9033; was once a run), when a hard trim leaves the heap at 85 %+ after 30 min; read in the lab process; kept for the next report]"

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
        return reportBlock8866(text, at, nowMs, lastStatus) + "  " + autoLine8982() + "\n"   // V5.0.8982
    }
}
