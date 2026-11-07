package com.lifecyclebot.engine

/**
 * V5.0.8618 §HEAP_LOG — owner: "add a heap log to the report". The 8613 runs peaked at 99 % of a 512 MB heap with 246 MB the
 * store census could not attribute. This log gives the report what a single snapshot cannot:
 *
 *  - NOW: Java heap (used / total / max), the native heap, the process PSS breakdown (java, native, code, stack, graphics,
 *    private-other, system), the device's available memory / low-memory flag and this app's memory class, live thread count
 *    by thread-name family.
 *  - GC: collections, blocking collections and their time, bytes allocated / freed (ART runtime stats), and the allocation
 *    rate between samples.
 *  - TIMELINE: one sample per census pass (the MemoryGuard7977 guard thread; no thread of its own), last 90 kept: heap used,
 *    native, unattributed (heap minus census), GC deltas, and the store that grew most since the previous sample.
 *  - GROWTH: every census store's size now against the first sample of the session and against ~10 minutes ago, largest
 *    growth first, so a store that leaks shows as a steady climber.
 */
object HeapLog8618 {
    private const val MAX_SAMPLES = 90
    private const val MB = 1024.0 * 1024.0

    private class Sample(
        val atMs: Long, val usedMb: Double, val maxMb: Double, val nativeMb: Double, val censusMb: Double,
        val gcCount: Long, val gcTimeMs: Long, val blockingGc: Long, val allocBytes: Long, val stores: Map<String, Long>,
    )

    private val samples = ArrayDeque<Sample>()
    @Volatile private var first: Sample? = null

    private fun stat(name: String): Long = try { android.os.Debug.getRuntimeStat(name)?.toLongOrNull() ?: -1L } catch (_: Throwable) { -1L }

    /** MemoryGuard7977.refreshCensus8144 (guard thread): record one sample from the census just taken. */
    fun sample8618(rows: List<MemoryRegistry8144.Row8144>, nowMs: Long = System.currentTimeMillis()) {
        try {
            val rt = Runtime.getRuntime()
            val stores = rows.associate { it.name to it.estBytes.coerceAtLeast(0L) }
            val s = Sample(nowMs, (rt.totalMemory() - rt.freeMemory()) / MB, rt.maxMemory() / MB,
                try { android.os.Debug.getNativeHeapAllocatedSize() / MB } catch (_: Throwable) { -1.0 },
                stores.values.sum() / MB, stat("art.gc.gc-count"), stat("art.gc.gc-time"), stat("art.gc.blocking-gc-count"),
                stat("art.gc.bytes-allocated"), stores)
            synchronized(samples) {
                if (first == null) first = s
                samples.addLast(s)
                while (samples.size > MAX_SAMPLES) samples.removeFirst()
            }
        } catch (_: Throwable) {}
    }

    /** Pure: the stores ordered by growth from [from] to [to] (bytes), largest growth first. */
    fun growth8618(from: Map<String, Long>, to: Map<String, Long>, n: Int = 12): List<Pair<String, Long>> =
        to.map { (k, v) -> k to (v - (from[k] ?: 0L)) }.sortedByDescending { it.second }.take(n)

    /** Pure: a thread name folded to its family ("mark-background-8527-3" -> "mark-background-#"). */
    fun family8622(name: String): String = name.trim().replace(Regex("[-_#]?\\d+$"), "").replace(Regex("\\d+"), "#").take(32).ifBlank { "?" }

    /** Pure: CPU ms per family from /proc/self/task/<tid>/stat lines (comm in parens; utime, stime = fields 14, 15 in ticks). */
    fun cpuByFamily8622(stats: List<String>, msPerTick: Double = 10.0): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (line in stats) {
            val open = line.indexOf('('); val close = line.lastIndexOf(')')
            if (open < 0 || close <= open) continue
            val rest = line.substring(close + 1).trim().split(' ')
            // rest[0] = state (field 3); utime = field 14 -> rest[11], stime = field 15 -> rest[12]
            val ticks = (rest.getOrNull(11)?.toLongOrNull() ?: continue) + (rest.getOrNull(12)?.toLongOrNull() ?: 0L)
            val fam = family8622(line.substring(open + 1, close))
            out[fam] = (out[fam] ?: 0.0) + ticks * msPerTick
        }
        return out
    }

    @Volatile private var lastCpu8622: Pair<Long, Map<String, Double>>? = null

    /**
     * V5.0.8622 — where the CPU (and so the allocation) goes. 8621 spent 2.9 ks of 3.9 ks in blocking GC at 1.5-11 GB/min with
     * no store growing: the allocator is a busy thread, not a resident store. Per thread family: CPU since start and since the
     * last report, busiest first.
     */
    private fun cpuLine8622(nowMs: Long): String {
        val dir = java.io.File("/proc/self/task")
        val stats = dir.listFiles()?.mapNotNull { t -> try { java.io.File(t, "stat").readText() } catch (_: Throwable) { null } } ?: return ""
        val now = cpuByFamily8622(stats)
        val prev = lastCpu8622; lastCpu8622 = nowMs to now
        val sinceMin = prev?.let { ((nowMs - it.first) / 60_000.0).coerceAtLeast(1.0 / 60.0) }
        val rows = now.entries.map { (k, v) -> Triple(k, v, prev?.second?.let { v - (it[k] ?: 0.0) } ?: Double.NaN) }
            .sortedByDescending { if (it.third.isFinite()) it.third else it.second }.take(14)
        return "  CPU        by thread family (total s / s per min since last report" +
            (if (sinceMin != null) ", ${"%.0f".format(sinceMin)}m" else ", first report") + "): " +
            rows.joinToString(",") { r -> "${r.first}:${"%.0f".format(r.second / 1000.0)}" +
                (if (r.third.isFinite() && sinceMin != null) "/${"%.1f".format(r.third / 1000.0 / sinceMin)}" else "") } + "\n"
    }

    private fun f(v: Double) = if (v.isFinite() && v >= 0.0) "%.0f".format(v) else "?"

    /** The report section (built on the report thread; reads only — the PSS query is the one call that touches the OS). */
    fun section8618(nowMs: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder()
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / MB
        sb.append("  NOW        java heap used=").append(f(used)).append("MB total=").append(f(rt.totalMemory() / MB))
            .append("MB max=").append(f(rt.maxMemory() / MB)).append("MB (").append(f(100.0 * used * MB / rt.maxMemory())).append("%)")
            .append(" native=").append(f(try { android.os.Debug.getNativeHeapAllocatedSize() / MB } catch (_: Throwable) { -1.0 })).append("MB\n")
        try {
            val mi = android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(mi)
            fun st(k: String) = mi.getMemoryStat(k)?.toLongOrNull()?.let { "%.0f".format(it / 1024.0) } ?: "?"
            sb.append("  PSS (MB)   total=").append(st("summary.total-pss")).append(" java=").append(st("summary.java-heap"))
                .append(" native=").append(st("summary.native-heap")).append(" code=").append(st("summary.code"))
                .append(" stack=").append(st("summary.stack")).append(" graphics=").append(st("summary.graphics"))
                .append(" privateOther=").append(st("summary.private-other")).append(" system=").append(st("summary.system")).append('\n')
        } catch (_: Throwable) { sb.append("  PSS        unavailable\n") }
        try {
            val ctx = com.lifecyclebot.AATEApp.appContextOrNull()
            val am = ctx?.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (am != null) {
                val info = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(info)
                sb.append("  DEVICE     avail=").append(f(info.availMem / MB)).append("MB total=").append(f(info.totalMem / MB))
                    .append("MB threshold=").append(f(info.threshold / MB)).append("MB lowMemory=").append(info.lowMemory)
                    .append(" memoryClass=").append(am.memoryClass).append("MB largeMemoryClass=").append(am.largeMemoryClass).append("MB\n")
            }
        } catch (_: Throwable) {}
        sb.append("  GC         count=").append(stat("art.gc.gc-count")).append(" timeMs=").append(stat("art.gc.gc-time"))
            .append(" blocking=").append(stat("art.gc.blocking-gc-count")).append(" blockingMs=").append(stat("art.gc.blocking-gc-time"))
            .append(" allocatedMB=").append(f(stat("art.gc.bytes-allocated") / MB)).append(" freedMB=").append(f(stat("art.gc.bytes-freed") / MB)).append('\n')
        try {
            val fam = Thread.getAllStackTraces().keys.groupingBy { t -> family8622(t.name) }.eachCount().entries.sortedByDescending { it.value }
            sb.append("  THREADS    live=").append(fam.sumOf { it.value }).append(" top=")
                .append(fam.take(12).joinToString(",") { "${it.key}:${it.value}" }).append('\n')
        } catch (_: Throwable) {}
        try { sb.append(cpuLine8622(nowMs)) } catch (_: Throwable) {}
        val snap = synchronized(samples) { samples.toList() }
        val first0 = first
        if (snap.isEmpty() || first0 == null) { sb.append("  TIMELINE   no census samples yet\n"); return sb.toString() }
        sb.append("  TIMELINE   (one row per ~5 samples; usedMB / nativeMB / censusMB / unattributedMB / gc+ / gcMs+ / blocking+ / allocMB/min / top grower)\n")
        var prev: Sample? = null
        for ((i, s) in snap.withIndex()) {
            val p = prev; prev = s
            if (p == null || (i % 5 != 0 && i != snap.lastIndex)) continue
            val mins = ((s.atMs - p.atMs) / 60_000.0).coerceAtLeast(1.0 / 60.0)
            val alloc = if (s.allocBytes >= 0 && p.allocBytes >= 0) (s.allocBytes - p.allocBytes) / MB / mins else -1.0
            val grower = growth8618(p.stores, s.stores, 1).firstOrNull()
            sb.append("    -").append((nowMs - s.atMs) / 60_000L).append("m ").append(f(s.usedMb)).append('/').append(f(s.nativeMb)).append('/')
                .append(f(s.censusMb)).append('/').append(f(s.usedMb - s.censusMb)).append(" gc+").append(s.gcCount - p.gcCount)
                .append(" gcMs+").append(s.gcTimeMs - p.gcTimeMs).append(" blocking+").append(s.blockingGc - p.blockingGc)
                .append(" alloc=").append(f(alloc)).append("MB/min")
                .append(if (grower != null && grower.second > 0) " grew=${grower.first}+${grower.second / 1024}KB" else "").append('\n')
        }
        val last = snap.last()
        val tenAgo = snap.lastOrNull { last.atMs - it.atMs >= 10 * 60_000L } ?: snap.first()
        sb.append("  GROWTH     since session start (").append((last.atMs - first0.atMs) / 60_000L).append("m): ")
            .append(growth8618(first0.stores, last.stores).joinToString(",") { "${it.first}:${if (it.second >= 0) "+" else ""}${it.second / 1024}KB" }).append('\n')
        sb.append("  GROWTH     last ~").append((last.atMs - tenAgo.atMs) / 60_000L).append("m: ")
            .append(growth8618(tenAgo.stores, last.stores).joinToString(",") { "${it.first}:${if (it.second >= 0) "+" else ""}${it.second / 1024}KB" }).append('\n')
        sb.append("  READ       unattributed = heap used - census; a steady rise with no store growing is memory outside the census (JSON / HTTP bodies, caches with no registration)\n")
        return sb.toString()
    }
}
