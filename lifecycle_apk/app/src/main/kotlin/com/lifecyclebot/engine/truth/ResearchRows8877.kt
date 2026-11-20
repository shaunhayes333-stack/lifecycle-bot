package com.lifecyclebot.engine.truth

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8877 §EVERY_DECISION_IS_READ_AT_THE_HOUR (plan W8, first step) — owner, 20 Nov: "its just aate doesn't trade past 5
 * minutes for some reason" / "there are 60,000% profit opportunities out there".
 *
 * Every learner of the meme stack is taught at the lane's 5- or 15-minute target: the edge dataset holds that one horizon,
 * so no export could say which decisions became runners over the following hour, or whether a learner graded at the hour
 * would rank them differently. ForwardReturnLabeler7731 already reads every eligible decision at 60 minutes (the "240"
 * slot) and tracks its peak and the highest level held 20 s; those reads reached only the lane / stage tallies.
 *
 * Now each 60-minute read is also written as one research row — the decision's own facts (lane, admitted, stage, setup,
 * forecast, V3 score, cap, pool, cost, the refusal it met) beside the hour's net and gross, its peak, the highest level held
 * and the deepest dip — to its own file, exported beside the edge dataset. Its own file and its own line budget: the edge
 * dataset's 60,000-line window, which every learner trains on, is not spent on it, and no learner reads it. A decision whose
 * mark never moved (a lost mark) writes nothing, as it books nothing.
 */
object ResearchRows8877 {
    const val FILE_8877 = "edge_research_8877.jsonl"
    private const val MAX_LINES_8877 = 60_000
    private const val KEEP_LINES_8877 = 45_000
    private const val CHECK_EVERY_8877 = 5_000L
    private val written = AtomicLong(0L)
    private val failed = AtomicLong(0L)
    @Volatile private var lastAtMs = 0L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "research8877").apply { isDaemon = true } }

    fun file8877(): File? = try { com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir?.let { File(it, FILE_8877) } } catch (_: Throwable) { null }

    /** Pure: one research row (JSON, NaN as null). */
    fun line8877(atMs: Long, horizonMs: Long, mint: String, lane: String, admitted: Boolean, stage: String, setup: String,
                 pred: Double, v3: Double, mcapUsd: Double, liqUsd: Double, costPct: Double, net: Double, gross: Double,
                 peakPct: Double, heldPeakPct: Double, minPct: Double, reason: String, diag: String = ""): String {
        fun n(v: Double) = if (v.isFinite()) "%.6g".format(java.util.Locale.US, v) else "null"
        fun s(v: String, max: Int) = v.replace("\\", "").replace("\"", "").take(max)
        return "{\"t\":$atMs,\"horizonMs\":$horizonMs,\"mint\":\"${s(mint, 44)}\",\"lane\":\"${s(lane, 32)}\",\"admitted\":$admitted," +
            "\"stage\":\"${s(stage, 32)}\",\"setup\":\"${s(setup, 40)}\",\"pred\":${n(pred)},\"v3\":${n(v3)},\"mcap\":${n(mcapUsd)}," +
            "\"liq\":${n(liqUsd)},\"cost\":${n(costPct)},\"net\":${n(net)},\"gross\":${n(gross)},\"peakPct\":${n(peakPct)}," +
            "\"heldPeakPct\":${n(heldPeakPct)},\"minPct\":${n(minPct)},\"reason\":\"${s(reason, 80)}\"" +
            // V5.0.8891 — the hour read's path diagnostics (ForwardReturnLabeler7731.diagJson8891), when the labeler gives them
            (if (diag.startsWith("{") && diag.endsWith("}")) ",\"diag8891\":$diag}" else "}")
    }

    /** ForwardReturnLabeler7731.bookTwoForty7944: write one decision's hour read. Never blocks the labeler. */
    fun note8877(line: String, nowMs: Long = System.currentTimeMillis()) {
        try { AuthorityTable8887.ensureStarted8887() } catch (_: Throwable) {}   // V5.0.8887 — the hourly authority table grades these rows
        io.execute {
            try {
                val f = file8877() ?: return@execute
                f.appendText(line + "\n")
                lastAtMs = nowMs
                if (written.incrementAndGet() % CHECK_EVERY_8877 == 0L) trim8877(f)
            } catch (_: Throwable) { failed.incrementAndGet() }
        }
    }

    /** Keep the newest [KEEP_LINES_8877] lines once the file passes [MAX_LINES_8877] (streamed, never read whole). */
    private fun trim8877(file: File) {
        var lines = 0
        file.forEachLine { lines++ }
        if (lines <= MAX_LINES_8877) return
        val skip = lines - KEEP_LINES_8877
        val tmp = File(file.parentFile, "$FILE_8877.trim")
        var idx = 0
        tmp.bufferedWriter().use { out -> file.forEachLine { l -> if (idx++ >= skip) { out.write(l); out.write("\n") } } }
        if (!tmp.renameTo(file)) tmp.delete()
    }

    // V5.0.8891 — hour reads booked on a stale last mark, by the check that refused the marks after it (measurement only)
    private val stale8891 = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    /** ForwardReturnLabeler7731: one hour read whose last accepted mark was older than the labeler's price age bar. */
    fun noteStale8891(lastRefusal: String) {
        val k = lastRefusal.take(48)
        (stale8891[k] ?: if (stale8891.size >= 24) stale8891.computeIfAbsent("OTHER") { AtomicLong() } else stale8891.computeIfAbsent(k) { AtomicLong() }).incrementAndGet()
    }

    /** The brief: rows written this run and how long ago the last one landed. */
    fun statusLine8877(): String =
        "research8877[written=${written.get()} failed=${failed.get()} lastAgoS=${if (lastAtMs > 0L) (System.currentTimeMillis() - lastAtMs) / 1000L else -1L} " +
            "staleHour8891{${stale8891.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}} " +
            "file=$FILE_8877 rule=every decision's 60-minute read with its peak, held peak and dip; its own file, no learner reads it]"
}
