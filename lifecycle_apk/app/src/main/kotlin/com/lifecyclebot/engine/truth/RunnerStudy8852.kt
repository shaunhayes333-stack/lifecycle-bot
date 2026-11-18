package com.lifecyclebot.engine.truth

import com.lifecyclebot.AATEApp
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * Selective six-hour paper RESEARCH. Only a native meme READY or crypto strategy READY
 * nominates a mint; discovery and generic QUALIFIED do not. One mint shares one watch
 * across lanes. This has NO live/paper position, entry permit, size or exit authority.
 * $10 values are observed MARK proxies, never executable quote proceeds.
 */
internal object RunnerStudy8852 {
    const val SIX_HOURS_MS = 6L * 3_600_000L
    const val ENTRY_WINDOW_MS = 90_000L
    const val EXIT_GRACE_MS = 5L * 60_000L
    const val MAX_ACTIVE = 256
    private const val REAWAKEN_COOLDOWN_MS = 30L * 60_000L
    private const val MAX_GAP_MS = 5L * 60_000L
    private const val MAX_RECENT = 48

    enum class State { COMPLETE_MARK_ONLY, ENTRY_UNAVAILABLE, EXIT_UNAVAILABLE }
    data class Result(
        val mint: String, val lane: String, val selectedBy: String, val state: State,
        val marks: Int, val holdUsd10: Double?, val peakUsd10: Double?,
        val priceOnlyExitUsd10: Double?, val exitReason: String, val maxGapMs: Long,
        val depthKnown: Boolean, val entryAtMs: Long?, val endAtMs: Long?,
        val entryUsdPerToken: Double?, val endUsdPerToken: Double?, val peakAtMs: Long?,
    )

    private class Episode(val mint: String, val lane: String, val selectedBy: String,
                          val selectedAt: Long, var conviction: Double) {
        var lanes = lane
        var entry: LabelMark8386? = null
        var last: LabelMark8386? = null
        var peakRatio = 1.0
        var peakAtMs = 0L
        var remaining = 1.0
        var bankedRatio = 0.0
        var rungMask = 0
        var stoppedBy = ""
        var maxGapMs = 0L
        var marks = 0
        var costPct = Double.NaN
        var depthKnown = false
        var askedAt = 0L
        fun encode() = JSONObject().put("m", mint).put("l", lane).put("by", selectedBy)
            .put("t", selectedAt).put("c", conviction).put("lanes", lanes)
            .put("entry", entry?.json()).put("last", last?.json())
            .put("peak", peakRatio).put("peakAt", peakAtMs)
            .put("remain", remaining).put("banked", bankedRatio)
            .put("rungs", rungMask).put("stop", stoppedBy).put("gap", maxGapMs)
            .put("marks", marks).put("cost", costPct.toString())
            .put("depth", depthKnown).put("asked", askedAt)
    }

    private val active = ConcurrentHashMap<String, Episode>()
    private val finishedAt = ConcurrentHashMap<String, Long>()
    private val recent = ArrayDeque<Result>()
    /** Append-only on-device evidence, separate from the small restart/UI checkpoint. */
    private const val JOURNAL = "runner_study_8852.jsonl"
    private val journalQueue = ArrayBlockingQueue<String>(1024)
    private val journalWorker by lazy {
        Thread({
            while (true) {
                val row = journalQueue.take()
                try {
                    AATEApp.appContextOrNull()?.let { app ->
                        File(app.filesDir, JOURNAL).appendText(row + "\n")
                    }
                } catch (_: Throwable) {
                    try { PipelineHealthCollector.labelInc("RUNNER_STUDY_JOURNAL_WRITE_FAIL_8852") } catch (_: Throwable) {}
                }
            }
        }, "AATE-runner-study-8852").apply { isDaemon = true; start() }
    }
    /** Shadow-only learning: selection denominator and mark-quality per READY owner, never live authority. */
    private data class Cell(
        var selected: Int = 0, var complete: Int = 0, var unavailable: Int = 0,
        var priced: Int = 0, var markedSum10: Double = 0.0, var markedWins: Int = 0,
        var peaked4x: Int = 0, var denseReplay: Int = 0, var replaySum10: Double = 0.0,
    ) {
        fun encode() = JSONArray(listOf(selected, complete, unavailable, priced, markedSum10,
            markedWins, peaked4x, denseReplay, replaySum10))
        fun line(): String =
            "selected=" + selected + " 6h=" + complete + " unavailable=" + unavailable +
                " priced=" + priced + " markMean10=" +
                (if (priced > 0) "%.2f".format(markedSum10 / priced) else "-") +
                " proxyN=" + denseReplay
    }
    private val byLane = LinkedHashMap<String, Cell>()
    private var selected = 0L
    private var refusedCapacity = 0L
    private var complete = 0L
    private var noEntry = 0L
    private var noExit = 0L

    /** A repeated READY updates attribution, not the six-hour entry clock. */
    @Synchronized fun nominate(mint: String, lane: String, source: String, conviction: Double,
                                nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank() || lane.isBlank() || nowMs <= 0L || !conviction.isFinite()) return false
        matureMissing(nowMs)
        val prior = active[mint]
        if (prior != null) {
            if (lane !in prior.lanes.split(',')) prior.lanes = (prior.lanes.split(',') + lane).take(8).joinToString(",")
            prior.conviction = maxOf(prior.conviction, conviction)
            return true
        }
        // A repeating READY on a completed, unchanged coin is not a fresh runner
        // opportunity. A new native READY after a 30m cooldown may re-enrol it.
        if (nowMs - (finishedAt[mint] ?: 0L) in 0L until REAWAKEN_COOLDOWN_MS) return false
        if (active.size >= MAX_ACTIVE) {
            refusedCapacity++
            try { PipelineHealthCollector.labelInc("RUNNER_STUDY_CAPACITY_8852") } catch (_: Throwable) {}
            return false
        }
        active[mint] = Episode(mint, lane, source.take(96), nowMs, conviction)
        selected++
        byLane.getOrPut(lane) { Cell() }.selected++
        try { PipelineHealthCollector.labelInc("RUNNER_STUDY_READY_8852") } catch (_: Throwable) {}
        return true
    }

    /** $10 cost is a modelled round trip at the entry depth, NOT a $10 swap quote. */
    fun markedUsd10(ratio: Double, costPct: Double): Double? =
        if (ratio.isFinite() && ratio >= 0.0 && costPct.isFinite() && costPct in 0.0..100.0)
            (10.0 * ratio - costPct / 10.0).coerceAtLeast(0.0) else null

    /**
     * Pure price-only runner proxy: -35% stop, 35%-from-peak giveback after 3x,
     * 25%/18%/67% of remaining at 2x/5x/10x. Never represents the full metrics,
     * liquidity, structure, or hard-integrity exit engine.
     */
    fun step(ratio: Double, peak: Double, remaining0: Double, banked0: Double, mask0: Int): DoubleArray {
        if (!ratio.isFinite() || ratio <= 0.0) return doubleArrayOf(remaining0, banked0, mask0.toDouble(), 0.0)
        if (ratio <= 0.65 || (peak >= 3.0 && ratio <= peak * 0.65))
            return doubleArrayOf(0.0, banked0 + remaining0 * ratio, mask0.toDouble(), 1.0)
        var remaining = remaining0; var banked = banked0; var mask = mask0
        val steps = listOf(2.0 to 0.25, 5.0 to 0.18, 10.0 to 0.67)
        for (i in steps.indices) if (ratio >= steps[i].first && mask and (1 shl i) == 0) {
            val share = remaining * steps[i].second
            banked += share * ratio; remaining -= share; mask = mask or (1 shl i)
        }
        return doubleArrayOf(remaining, banked, mask.toDouble(), 0.0)
    }

    /** Only source-fresh and independently attributable marks; a cached repeat is not a tick. */
    @Synchronized fun observe(mint: String, mark: LabelMark8386?, nowMs: Long,
                               costPct10: Double = Double.NaN, depthKnown: Boolean = false) {
        val e = active[mint] ?: return
        if (mark?.known() != true || nowMs - mark.observedAtMs !in 0L..45_000L ||
            mark.observedAtMs < e.selectedAt) return
        if (e.entry == null) {
            if (mark.observedAtMs - e.selectedAt > ENTRY_WINDOW_MS) return
            e.entry = mark; e.last = mark; e.marks = 1; e.peakAtMs = mark.observedAtMs
            if (costPct10.isFinite() && costPct10 in 0.0..100.0) {
                e.costPct = costPct10; e.depthKnown = depthKnown
            }
            return
        }
        val entry = e.entry ?: return
        if (mark.observedAtMs <= (e.last?.observedAtMs ?: 0L) ||
            !LabelEvidence8386.eligible(entry, mark)) return
        val target = entry.observedAtMs + SIX_HOURS_MS
        if (mark.observedAtMs > target + EXIT_GRACE_MS) return
        val ratio = mark.usd / entry.usd
        if (!ratio.isFinite() || ratio <= 0.0) return
        e.maxGapMs = maxOf(e.maxGapMs, mark.observedAtMs - (e.last?.observedAtMs ?: entry.observedAtMs))
        e.last = mark; e.marks++
        if (ratio > e.peakRatio) { e.peakRatio = ratio; e.peakAtMs = mark.observedAtMs }
        if (e.stoppedBy.isBlank()) {
            val s = step(ratio, e.peakRatio, e.remaining, e.bankedRatio, e.rungMask)
            e.remaining = s[0]; e.bankedRatio = s[1]; e.rungMask = s[2].toInt()
            if (s[3] == 1.0) e.stoppedBy =
                if (ratio <= 0.65) "PRICE_STOP_35" else "PRICE_PEAK_GIVEBACK_35"
        }
        if (mark.observedAtMs >= target) finish(e, State.COMPLETE_MARK_ONLY, nowMs)
    }

    /** Missing pricing after the deadline stays UNAVAILABLE, never counted as a -100% loss. */
    @Synchronized fun matureMissing(nowMs: Long) {
        active.values.toList().forEach { e ->
            val entry = e.entry
            if (entry == null && nowMs > e.selectedAt + ENTRY_WINDOW_MS + EXIT_GRACE_MS)
                finish(e, State.ENTRY_UNAVAILABLE, nowMs)
            else if (entry != null && nowMs > entry.observedAtMs + SIX_HOURS_MS + EXIT_GRACE_MS)
                finish(e, State.EXIT_UNAVAILABLE, nowMs)
        }
    }

    private fun finish(e: Episode, state: State, finishedMs: Long) {
        if (!active.remove(e.mint, e)) return
        finishedAt[e.mint] = finishedMs
        if (finishedAt.size > 4_096) {
            val oldest = (finishedAt.values.maxOrNull() ?: 0L) - SIX_HOURS_MS
            finishedAt.entries.removeIf { it.value < oldest }
        }
        val ratio = if (state == State.COMPLETE_MARK_ONLY && e.entry != null && e.last != null)
            e.last!!.usd / e.entry!!.usd else Double.NaN
        val hold = markedUsd10(ratio, e.costPct)
        val peak = if (state == State.COMPLETE_MARK_ONLY) markedUsd10(e.peakRatio, e.costPct) else null
        val proxy = if (state == State.COMPLETE_MARK_ONLY && e.maxGapMs <= MAX_GAP_MS && e.marks >= 3)
            markedUsd10(e.bankedRatio + e.remaining * ratio, e.costPct) else null
        val r = Result(e.mint, e.lane, e.selectedBy, state, e.marks, hold, peak, proxy,
            e.stoppedBy.ifBlank { "HOLD_TO_6H" }, e.maxGapMs, e.depthKnown,
            e.entry?.observedAtMs, e.last?.observedAtMs, e.entry?.usd, e.last?.usd,
            e.peakAtMs.takeIf { it > 0L })
        // The journal records every selected episode, even if pricing never
        // arrives; only priced, witnessed rows enter the paper-research cells.
        try {
            val row = JSONObject().put("kind", "RUNNER_STUDY_8852").put("mint", e.mint)
                .put("lane", e.lane).put("supportingLanes", e.lanes)
                .put("selectedMs", e.selectedAt).put("selectedBy", e.selectedBy)
                .put("conviction", e.conviction).put("state", state.name)
                .put("observedMarks", e.marks).put("maxGapMs", e.maxGapMs)
                .put("entryMs", e.entry?.observedAtMs ?: JSONObject.NULL)
                .put("entryUsdPerToken", e.entry?.usd ?: JSONObject.NULL)
                .put("entrySource", e.entry?.source ?: JSONObject.NULL)
                .put("endMs", e.last?.observedAtMs ?: JSONObject.NULL)
                .put("endUsdPerToken", e.last?.usd ?: JSONObject.NULL)
                .put("endSource", e.last?.source ?: JSONObject.NULL)
                .put("peakMs", r.peakAtMs ?: JSONObject.NULL)
                .put("peakRatio", if (state == State.COMPLETE_MARK_ONLY) e.peakRatio else JSONObject.NULL)
                .put("cost10PctModel", e.costPct.takeIf { it.isFinite() } ?: JSONObject.NULL)
                .put("entryDepthObserved", e.depthKnown)
                .put("holdMarkedUsd10", hold ?: JSONObject.NULL)
                .put("peakMarkedUsd10", peak ?: JSONObject.NULL)
                .put("priceOnlyProxyUsd10", proxy ?: JSONObject.NULL)
                .put("priceOnlyProxyReason", r.exitReason)
                .put("buyQuoteObserved", false).put("sellQuoteObserved", false)
                .put("realizedWalletPnl", JSONObject.NULL)
            if (AATEApp.appContextOrNull() != null) {
                journalWorker
                if (!journalQueue.offer(row.toString()))
                    PipelineHealthCollector.labelInc("RUNNER_STUDY_JOURNAL_QUEUE_FULL_8852")
            }
        } catch (_: Throwable) {}
        val cell = byLane.getOrPut(e.lane) { Cell() }
        if (state == State.COMPLETE_MARK_ONLY) cell.complete++ else cell.unavailable++
        if (state == State.COMPLETE_MARK_ONLY && e.depthKnown && hold != null) {
            cell.priced++; cell.markedSum10 += hold
            if (hold > 10.0) cell.markedWins++
            if (e.peakRatio >= 4.0) cell.peaked4x++
            if (proxy != null) { cell.denseReplay++; cell.replaySum10 += proxy }
        }
        recent.addLast(r)
        while (recent.size > MAX_RECENT) recent.removeFirst()
        when (state) {
            State.COMPLETE_MARK_ONLY -> complete++
            State.ENTRY_UNAVAILABLE -> noEntry++
            State.EXIT_UNAVAILABLE -> noExit++
        }
        try {
            PipelineHealthCollector.labelInc("RUNNER_STUDY_" + state.name + "_8852")
            ForensicLogger.lifecycle("RUNNER_STUDY_6H_8852",
                "mint=" + e.mint.take(12) + " lane=" + e.lane + " by=" + e.selectedBy.take(48) +
                    " state=" + state.name + " marks=" + e.marks + " maxGapMs=" + e.maxGapMs +
                    " entryAtMs=" + (e.entry?.observedAtMs ?: "UNAVAILABLE") +
                    " endAtMs=" + (e.last?.observedAtMs ?: "UNAVAILABLE") +
                    " holdMarkedUsd10=" + (hold ?: "UNAVAILABLE") +
                    " peakMarkedUsd10=" + (peak ?: "UNAVAILABLE") +
                    " priceOnlyProxyUsd10=" + (proxy ?: "UNAVAILABLE") +
                    " buyQuote=NONE sellQuote=NONE liveFill=NONE")
        } catch (_: Throwable) {}
    }

    /** Shared low-priority off-watch batch, at most four mints per tick. */
    @Synchronized fun dueRefresh(nowMs: Long): List<String> =
        active.values.asSequence().filter { e ->
            val gap = if (e.entry == null) 15_000L else 5L * 60_000L
            nowMs - e.askedAt >= gap && nowMs - (e.last?.observedAtMs ?: 0L) >= 45_000L
        }.sortedBy { it.askedAt }.take(4).map { it.askedAt = nowMs; it.mint }.toList()

    fun activeMints(): Set<String> = active.keys.toSet()
    fun needsEntry(mint: String): Boolean = active[mint]?.entry == null
    @Synchronized fun line(): String =
        "runnerStudy8852[ready=$selected active=" + active.size + "/$MAX_ACTIVE complete=$complete" +
            " missingEntry=$noEntry missingExit=$noExit capacity=$refusedCapacity" +
            " last=" + (recent.lastOrNull()?.let { it.lane + ":" + it.state.name + ":$" + (it.holdUsd10 ?: "-") } ?: "-") +
            " markedOnly=NO_QUOTES] cells=" +
            byLane.entries.sortedByDescending { it.value.complete }.take(8)
                .joinToString(";") { it.key + "{" + it.value.line() + "}" }

    @Synchronized fun encode(): String = JSONObject().put("selected", selected).put("capacity", refusedCapacity)
        .put("complete", complete).put("noEntry", noEntry).put("noExit", noExit)
        .put("cooldown", JSONObject().also { j -> finishedAt.forEach { (m, at) -> j.put(m, at) } })
        .put("cells", JSONObject().also { j -> byLane.forEach { (lane, cell) -> j.put(lane, cell.encode()) } })
        .put("active", JSONArray().also { a -> active.values.forEach { a.put(it.encode()) } })
        .put("recent", JSONArray().also { a -> recent.forEach { r -> a.put(JSONObject()
            .put("m", r.mint).put("l", r.lane).put("by", r.selectedBy).put("state", r.state.name)
            .put("marks", r.marks).put("hold", r.holdUsd10 ?: JSONObject.NULL)
            .put("peak", r.peakUsd10 ?: JSONObject.NULL).put("proxy", r.priceOnlyExitUsd10 ?: JSONObject.NULL)
            .put("reason", r.exitReason).put("gap", r.maxGapMs).put("depth", r.depthKnown)
            .put("entryAt", r.entryAtMs ?: JSONObject.NULL).put("endAt", r.endAtMs ?: JSONObject.NULL)
            .put("entryPx", r.entryUsdPerToken ?: JSONObject.NULL).put("endPx", r.endUsdPerToken ?: JSONObject.NULL)
            .put("peakAt", r.peakAtMs ?: JSONObject.NULL)) } }).toString()

    /** Save report: full durable result rows, plus today's unresolved READY cohort. */
    @Synchronized fun export(dir: File, nowMs: Long): File? {
        val app = AATEApp.appContextOrNull() ?: return null
        val out = File.createTempFile("runner_study_8852_", ".jsonl", dir)
        try {
            out.bufferedWriter().use { w ->
                w.appendLine(JSONObject().put("kind", "RUNNER_STUDY_META_8852").put("exportMs", nowMs)
                    .put("nominated", selected).put("active", active.size)
                    .put("complete", complete).put("entryUnavailable", noEntry)
                    .put("exitUnavailable", noExit).put("capacityRefused", refusedCapacity)
                    .put("liveAuthority", false).put("quoteBackedUsd10", false).toString())
                val original = File(app.filesDir, JOURNAL)
                if (original.exists()) original.forEachLine { row -> w.appendLine(row) }
                active.values.forEach { e ->
                    w.appendLine(JSONObject().put("kind", "RUNNER_STUDY_PENDING_8852")
                        .put("snapshotMs", nowMs).put("episode", e.encode()).toString())
                }
            }
            return out
        } catch (e: Exception) { out.delete(); throw e }
    }

    @Synchronized fun restore(raw: String?, nowMs: Long = System.currentTimeMillis()) {
        if (raw.isNullOrBlank()) return
        try {
            val j = JSONObject(raw)
            selected = maxOf(selected, j.optLong("selected"))
            refusedCapacity = maxOf(refusedCapacity, j.optLong("capacity"))
            complete = maxOf(complete, j.optLong("complete"))
            noEntry = maxOf(noEntry, j.optLong("noEntry"))
            noExit = maxOf(noExit, j.optLong("noExit"))
            j.optJSONObject("cooldown")?.let { cooldown ->
                for (mint in cooldown.keys()) {
                    val at = cooldown.optLong(mint)
                    if (finishedAt.size >= 4_096) break
                    if (at > 0L && nowMs - at in 0L..SIX_HOURS_MS) finishedAt.putIfAbsent(mint, at)
                }
            }
            j.optJSONObject("cells")?.let { cells ->
                for (lane in cells.keys()) {
                    val a = cells.optJSONArray(lane) ?: continue
                    if (byLane.size >= 64 || a.length() != 9) break
                    val cell = Cell(a.optInt(0), a.optInt(1), a.optInt(2), a.optInt(3),
                        a.optDouble(4), a.optInt(5), a.optInt(6), a.optInt(7), a.optDouble(8))
                    if (cell.selected >= 0 && cell.complete >= 0 && cell.unavailable >= 0 &&
                        cell.priced >= 0 && cell.markedSum10.isFinite()) byLane.putIfAbsent(lane, cell)
                }
            }
            val arr = j.optJSONArray("active") ?: JSONArray()
            for (i in 0 until minOf(arr.length(), MAX_ACTIVE)) {
                val x = arr.optJSONObject(i) ?: continue
                val mint = x.optString("m"); val at = x.optLong("t")
                if (mint.isBlank() || at <= 0L || nowMs - at !in 0L..(SIX_HOURS_MS + 2 * EXIT_GRACE_MS)) continue
                val e = Episode(mint, x.optString("l"), x.optString("by"), at, x.optDouble("c"))
                e.lanes = x.optString("lanes", e.lane)
                e.entry = x.optJSONObject("entry")?.let { LabelMark8386.decode(it) }
                e.last = x.optJSONObject("last")?.let { LabelMark8386.decode(it) }
                e.peakRatio = x.optDouble("peak", 1.0); e.peakAtMs = x.optLong("peakAt")
                e.remaining = x.optDouble("remain", 1.0)
                e.bankedRatio = x.optDouble("banked"); e.rungMask = x.optInt("rungs")
                e.stoppedBy = x.optString("stop"); e.maxGapMs = x.optLong("gap")
                e.marks = x.optInt("marks"); e.costPct = x.optString("cost").toDoubleOrNull() ?: Double.NaN
                e.depthKnown = x.optBoolean("depth"); e.askedAt = x.optLong("asked")
                if (e.peakRatio.isFinite() && e.peakRatio >= 1.0 && e.remaining in 0.0..1.0 &&
                    e.bankedRatio.isFinite() && e.bankedRatio >= 0.0) active.putIfAbsent(mint, e)
            }
            val done = j.optJSONArray("recent") ?: JSONArray()
            for (i in 0 until minOf(done.length(), MAX_RECENT)) {
                val x = done.optJSONObject(i) ?: continue
                val state = State.values().firstOrNull { it.name == x.optString("state") } ?: continue
                recent.addLast(Result(x.optString("m"), x.optString("l"), x.optString("by"), state,
                    x.optInt("marks"), x.optDouble("hold").takeIf { !x.isNull("hold") },
                    x.optDouble("peak").takeIf { !x.isNull("peak") },
                    x.optDouble("proxy").takeIf { !x.isNull("proxy") },
                    x.optString("reason"), x.optLong("gap"), x.optBoolean("depth"),
                    x.optLong("entryAt").takeIf { !x.isNull("entryAt") },
                    x.optLong("endAt").takeIf { !x.isNull("endAt") },
                    x.optDouble("entryPx").takeIf { !x.isNull("entryPx") },
                    x.optDouble("endPx").takeIf { !x.isNull("endPx") },
                    x.optLong("peakAt").takeIf { !x.isNull("peakAt") }))
            }
            while (recent.size > MAX_RECENT) recent.removeFirst()
            matureMissing(nowMs)
        } catch (_: Exception) {
            try { PipelineHealthCollector.labelInc("RUNNER_STUDY_RESTORE_FAILED_8852") } catch (_: Throwable) {}
        }
    }

    @Synchronized internal fun resultsForTest(): List<Result> = recent.toList()
    @Synchronized internal fun resetForTest() {
        active.clear(); finishedAt.clear(); recent.clear(); byLane.clear()
        selected = 0L; refusedCapacity = 0L
        complete = 0L; noEntry = 0L; noExit = 0L
    }
}
