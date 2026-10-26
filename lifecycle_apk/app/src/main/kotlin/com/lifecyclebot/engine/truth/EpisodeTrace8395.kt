package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8395 (R10) — one exportable trace per decision EPISODE, and the frozen chronological replay that reads it.
 *
 * SpecialistCausalFunnel6625 already carries the causal identity (run, mode, mint, lane, authority version, intent)
 * for the lane acceptance windows, but it keeps only the time each stage was first stamped, lives for 30 minutes and
 * never leaves the phone. Nothing on the device could answer "for THIS mint, THIS episode, THIS candidate version and
 * THIS lane: which scope read it natively, which receiver/task took it, who elected it, what admission and final
 * economics said, which ticket, submission and fill, which position, how it was disposed, and which learner acked it"
 * — nor tell a modelled number from a realised one. This is that record. It is an observer: it never changes a
 * decision, holds no money and owns no position (CanonicalPositionAuthority6441 and the canonical ledger still do).
 *
 * Identity. episode = mode | mint | candidate version | canonical lane | runtime generation. Events seen before the
 * candidate version exists (native scope, receiver, task, admission, FDG) are buffered per mint and bound to the
 * episode at its ELECTION — only events at or before the election time, so a later read can never be filed into an
 * earlier decision. After the ticket every hop carries the attempt / position id.
 *
 * Money. Every value carries a [Basis]: DECISION / MODELLED values (predicted p(win), expected net %, quoted cost)
 * are never summed as outcomes; only REALISED values (a canonical paper close, or a live close whose proof state is
 * chain-proven) count in the replay. A live close without chain proof is UNVERIFIED and excluded.
 *
 * Recovery. Only DISPOSED episodes are restored from the export (original frozen prediction + exact outcome + its
 * availability). An episode the process lost before its disposition is never rebuilt from later prices.
 */
object EpisodeTrace8395 {

    enum class Stage { NATIVE_SCOPE, RECEIVER, TASK, ELECTION, ADMISSION, FDG, ECONOMICS, TICKET, SUBMISSION, FILL,
        POSITION, DISPOSITION, ACK }

    enum class Basis { DECISION, MODELLED, REALISED, UNVERIFIED, OBSERVED }

    /** Every role the replay must cover. */
    val ROLES_8395 = listOf("CORE", "MOONSHOT", "SHITCOIN", "TREASURY", "EXPRESS", "CASHGEN", "QUALITY",
        "PROJECT_SNIPER", "BLUECHIP", "DIP_HUNTER", "CYCLIC", "MANIPULATED")

    data class Event(val stage: Stage, val verdict: String, val reason: String, val basis: Basis, val atMs: Long,
                     val value: Double = Double.NaN, val lane: String = "", val materialAtMs: Long = 0L)

    class Episode(val key: String, val mode: String, val mint: String, val version: Long, val lane: String,
                  val generation: Long, val electedAtMs: Long) {
        val events = ArrayList<Event>(12)
        @Volatile var attemptId: String = ""
        @Volatile var positionId: String = ""
        @Volatile var materialAtMs: Long = 0L
        @Volatile var orderViolations: Int = 0
        fun has(s: Stage): Boolean = synchronized(this) { events.any { it.stage == s } }
        fun snapshot(): List<Event> = synchronized(this) { events.toList() }
    }

    private const val MAX_EPISODES_8395 = 1_500
    private const val MAX_EVENTS_8395 = 32
    private const val MAX_PRE_MINTS_8395 = 3_000
    private const val MAX_PRE_EVENTS_8395 = 12
    private const val PRE_TTL_MS_8395 = 30 * 60_000L
    internal const val FILE_8395 = "episode_traces_8395.jsonl"

    private val episodes = ConcurrentHashMap<String, Episode>()
    private val byAttempt = ConcurrentHashMap<String, String>()
    private val byPosition = ConcurrentHashMap<String, String>()
    private val pre = ConcurrentHashMap<String, ArrayList<Event>>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val io by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "episode-trace-8395").apply { isDaemon = true } } }

    private fun inc(name: String) {
        counters.computeIfAbsent(name) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc(name) } catch (_: Throwable) {}
    }

    fun canonLane(lane: String?): String {
        val raw = lane?.trim()?.uppercase().orEmpty()
        // the desk identity (SHITCOIN_EXPRESS files as EXPRESS); no alias-collision telemetry from an observer
        val l = try { CanonicalLaneIdentity6506.desk8392(lane) } catch (_: Throwable) { raw }
        return l.ifBlank { raw }
    }

    fun episodeKey(mode: String, mint: String, version: Long, lane: String, generation: Long): String =
        "${mode.uppercase()}|${mint.trim()}|v$version|${canonLane(lane)}|g$generation"

    private fun generation(): Long = try { com.lifecyclebot.engine.BotRuntimeController.currentGeneration() } catch (_: Throwable) { 0L }

    // ── recording ────────────────────────────────────────────────────────────────────────────────────────────────

    /** A pre-election hop (no candidate version yet). Bounded per mint; stale buffers age out. */
    fun notePre(mint: String, stage: Stage, verdict: String, reason: String, atMs: Long, lane: String = "",
                basis: Basis = Basis.DECISION, value: Double = Double.NaN, materialAtMs: Long = 0L) {
        if (mint.isBlank()) return
        if (pre.size >= MAX_PRE_MINTS_8395 && !pre.containsKey(mint)) prunePre(atMs)
        if (pre.size >= MAX_PRE_MINTS_8395 && !pre.containsKey(mint)) { inc("EPISODE_TRACE_PRE_FULL_8395"); return }
        val list = pre.computeIfAbsent(mint) { ArrayList(MAX_PRE_EVENTS_8395) }
        synchronized(list) {
            if (list.size >= MAX_PRE_EVENTS_8395) list.removeAt(0)
            list.add(Event(stage, verdict.take(40), reason.take(90), basis, atMs, value, canonLane(lane), materialAtMs))
        }
    }

    /** ELECTION opens the episode and binds the buffered hops decided at or before it (never a later read). */
    fun onElection(mode: String, mint: String, version: Long, lane: String, atMs: Long, pWin: Double?,
                   expectedNetPct: Double?, action: String): Episode? {
        if (mint.isBlank() || version <= 0L || lane.isBlank()) return null
        val gen = generation()
        val key = episodeKey(mode, mint, version, lane, gen)
        episodes[key]?.let { return it }
        if (episodes.size >= MAX_EPISODES_8395) evict()
        val ep = Episode(key, mode.uppercase(), mint, version, canonLane(lane), gen, atMs)
        if (episodes.putIfAbsent(key, ep) != null) return episodes[key]
        val buffered = pre[mint]?.let { l -> synchronized(l) { l.filter { it.atMs in 1..atMs &&
            (it.lane.isBlank() || it.lane == ep.lane || it.stage == Stage.NATIVE_SCOPE || it.stage == Stage.RECEIVER) } } }.orEmpty()
        synchronized(ep) {
            buffered.sortedBy { it.atMs }.forEach { ep.events.add(it); if (it.materialAtMs > ep.materialAtMs) ep.materialAtMs = it.materialAtMs }
            ep.events.add(Event(Stage.ELECTION, action.take(40), "P_WIN", Basis.DECISION, atMs, pWin ?: Double.NaN, ep.lane))
            if (expectedNetPct != null) ep.events.add(Event(Stage.ELECTION, action.take(40), "EXPECTED_NET_PCT", Basis.MODELLED, atMs, expectedNetPct, ep.lane))
        }
        inc("EPISODE_TRACE_OPENED_8395")
        return ep
    }

    private fun add(ep: Episode, e: Event) {
        synchronized(ep) {
            val lastAt = ep.events.maxOfOrNull { it.atMs } ?: 0L
            if (e.atMs + 1_000L < lastAt) { ep.orderViolations++; inc("EPISODE_TRACE_ORDER_VIOLATION_8395") }
            if (ep.events.size >= MAX_EVENTS_8395) {
                val i = ep.events.indexOfFirst { it.stage != Stage.ELECTION && it.stage.ordinal < Stage.TICKET.ordinal }
                ep.events.removeAt(if (i >= 0) i else ep.events.indexOfFirst { it.stage != Stage.ELECTION }.coerceAtLeast(0))
            }
            ep.events.add(e)
        }
    }

    /** Post-election hops that still carry only mint + lane (final economics run after the version is published). */
    fun noteForLatest(mode: String, mint: String, lane: String, stage: Stage, verdict: String, reason: String, atMs: Long,
                      basis: Basis = Basis.DECISION, value: Double = Double.NaN) {
        val l = canonLane(lane)
        val ep = episodes.values.asSequence()
            .filter { it.mint == mint && it.lane == l && (mode.isBlank() || it.mode.equals(mode, true)) && it.electedAtMs <= atMs && !it.has(Stage.TICKET) }
            .maxByOrNull { it.electedAtMs }
        if (ep == null) { notePre(mint, stage, verdict, reason, atMs, lane, basis, value); return }
        add(ep, Event(stage, verdict.take(40), reason.take(90), basis, atMs, value, l))
    }

    fun onTicket(attemptId: String, mode: String, mint: String, version: Long, lane: String, atMs: Long, sizeSol: Double) {
        val ep = episodes[episodeKey(mode, mint, version, lane, generation())] ?: run { inc("EPISODE_TRACE_TICKET_UNBOUND_8395"); return }
        ep.attemptId = attemptId; byAttempt[attemptId] = ep.key
        add(ep, Event(Stage.TICKET, "SEALED", attemptId.take(40), Basis.DECISION, atMs, sizeSol, ep.lane))
    }

    fun onSubmission(attemptId: String, mode: String, mint: String, version: Long, lane: String, atMs: Long, sizeSol: Double) {
        val ep = byAttempt[attemptId]?.let { episodes[it] } ?: locate(mode, mint, version, lane)
            ?: run { inc("EPISODE_TRACE_SUBMISSION_UNBOUND_8395"); return }
        add(ep, Event(Stage.SUBMISSION, "ENTERED", attemptId.take(40), Basis.DECISION, atMs, sizeSol, ep.lane))
    }

    /** The confirmed fill: actual cost is realised money (paper canonical ledger or the verified live receipt). */
    fun onFill(mode: String, mint: String, version: Long, lane: String, positionId: String, atMs: Long, actualCostSol: Double) {
        val ep = locate(mode, mint, version, lane) ?: run { inc("EPISODE_TRACE_FILL_UNBOUND_8395"); return }
        add(ep, Event(Stage.FILL, "CONFIRMED", "", Basis.REALISED, atMs, actualCostSol, ep.lane))
        if (positionId.isNotBlank()) {
            ep.positionId = positionId; byPosition[positionId] = ep.key
            add(ep, Event(Stage.POSITION, "OPEN", positionId.take(40), Basis.OBSERVED, atMs, Double.NaN, ep.lane))
        }
    }

    fun onDisposition(positionId: String, mode: String, lane: String, atMs: Long, realizedPnlSol: Double,
                      realizedReturnPct: Double, proofState: String, terminal: Boolean, chainProven: Boolean) {
        val ep = byPosition[positionId]?.let { episodes[it] } ?: run { inc("EPISODE_TRACE_DISPOSITION_UNBOUND_8395"); return }
        val basis = if (mode.equals("PAPER", true) || chainProven) Basis.REALISED else Basis.UNVERIFIED
        add(ep, Event(Stage.DISPOSITION, if (terminal) "TERMINAL" else "PARTIAL", proofState.take(40), basis, atMs, realizedPnlSol, canonLane(lane)))
        if (realizedReturnPct.isFinite()) add(ep, Event(Stage.DISPOSITION, "RETURN_PCT", "", basis, atMs, realizedReturnPct, canonLane(lane)))
        if (terminal) persistAsync(ep)
    }

    fun onAck(positionId: String, consumer: String, acked: Boolean, atMs: Long) {
        val ep = byPosition[positionId]?.let { episodes[it] } ?: return
        add(ep, Event(Stage.ACK, if (acked) "ACKED" else "RETRY", consumer.take(40), Basis.OBSERVED, atMs, Double.NaN, ep.lane))
        if (acked) persistAsync(ep)
    }

    // ── call-site wrappers: one plain call on the trading path, never a throw, no decision effect ─────────────────

    fun submissionSafe8395(attemptId: String, mode: String, mint: String, version: Long, lane: String, sizeSol: Double = Double.NaN) {
        try { onSubmission(attemptId, mode, mint, version, lane, System.currentTimeMillis(), sizeSol) } catch (_: Throwable) {}
    }

    fun fillSafe8395(mode: String, mint: String, version: Long, lane: String, positionId: String, actualCostSol: Double, attemptId: String) {
        try {
            val ep = byAttempt[attemptId]?.let { episodes[it] }
            if (ep != null) onFill(ep.mode, ep.mint, ep.version, ep.lane, positionId, System.currentTimeMillis(), actualCostSol)
            else onFill(mode, mint, version, lane, positionId, System.currentTimeMillis(), actualCostSol)
        } catch (_: Throwable) {}
    }

    fun preSafe8395(mint: String, stage: Stage, verdict: String, reason: String, lane: String = "", materialAtMs: Long = 0L) {
        try { notePre(mint, stage, verdict, reason, System.currentTimeMillis(), lane, materialAtMs = materialAtMs) } catch (_: Throwable) {}
    }

    fun latestSafe8395(mint: String, lane: String, stage: Stage, verdict: String, reason: String, value: Double = Double.NaN,
                       basis: Basis = Basis.DECISION) {
        try { noteForLatest("", mint, lane, stage, verdict, reason, System.currentTimeMillis(), basis, value) } catch (_: Throwable) {}
    }

    private fun locate(mode: String, mint: String, version: Long, lane: String): Episode? {
        val gen = generation()
        if (lane.isNotBlank()) episodes[episodeKey(mode, mint, version, lane, gen)]?.let { return it }
        // a hop without (or with an alias of) its lane binds only on an unambiguous (mode, mint, version) match
        return episodes.values.filter { it.mode.equals(mode, true) && it.mint == mint && it.version == version && it.generation == gen }.singleOrNull()
    }

    private fun prunePre(nowMs: Long) {
        pre.entries.removeIf { e -> synchronized(e.value) { e.value.isEmpty() || nowMs - e.value.last().atMs > PRE_TTL_MS_8395 } }
    }

    private fun evict() {
        // disposed episodes leave first (they are already exported); open ones only when nothing else is left
        val victims = episodes.values.sortedWith(compareBy<Episode> { !it.has(Stage.DISPOSITION) }.thenBy { it.electedAtMs })
            .take(MAX_EPISODES_8395 / 10)
        victims.forEach { ep -> episodes.remove(ep.key, ep); byAttempt.remove(ep.attemptId); byPosition.remove(ep.positionId) }
        inc("EPISODE_TRACE_EVICTED_8395")
    }

    // ── terminal classification (pure) ──────────────────────────────────────────────────────────────────────────

    private val PIPELINE_8395 = listOf(Stage.ELECTION, Stage.TICKET, Stage.SUBMISSION, Stage.FILL, Stage.POSITION, Stage.DISPOSITION, Stage.ACK)

    /** Where an episode stands, named: DISPOSED_ACKED / DISPOSED_UNACKED / STOPPED_AFTER_<stage>[:<binding gate>]. */
    fun terminal8395(events: List<Event>): String {
        val stages = events.map { it.stage }.toSet()
        if (Stage.DISPOSITION in stages && events.any { it.stage == Stage.DISPOSITION && it.verdict == "TERMINAL" })
            return if (events.any { it.stage == Stage.ACK && it.verdict == "ACKED" }) "DISPOSED_ACKED" else "DISPOSED_UNACKED"
        val reached = PIPELINE_8395.lastOrNull { it in stages } ?: return "NOT_ELECTED"
        val binding = events.lastOrNull { (it.stage == Stage.ECONOMICS && it.verdict != "CLEAR") || (it.stage == Stage.FDG && it.verdict == "REFUSE") ||
            (it.stage == Stage.ADMISSION && !it.verdict.startsWith("ADMIT")) }
        return "STOPPED_AFTER_${reached.name}" + (binding?.let { ":${it.stage.name}_${it.verdict}" } ?: "")
    }

    // ── codec ───────────────────────────────────────────────────────────────────────────────────────────────────

    fun toJson(ep: Episode): JSONObject = JSONObject().put("k", ep.key).put("mode", ep.mode).put("mint", ep.mint)
        .put("v", ep.version).put("lane", ep.lane).put("g", ep.generation).put("el", ep.electedAtMs).put("pid", ep.positionId)
        .put("mat", ep.materialAtMs).put("ov", ep.orderViolations)
        .put("ev", JSONArray().also { a -> ep.snapshot().forEach { e -> a.put(JSONObject().put("s", e.stage.name).put("vd", e.verdict)
            .put("r", e.reason).put("b", e.basis.name).put("t", e.atMs).put("x", if (e.value.isFinite()) e.value else JSONObject.NULL).put("l", e.lane)) } })

    fun parseJsonl(text: String): List<Episode> = text.lineSequence().map { it.trim() }.filter { it.startsWith("{") }.mapNotNull { line ->
        try {
            val j = JSONObject(line)
            val ep = Episode(j.getString("k"), j.getString("mode"), j.getString("mint"), j.getLong("v"), j.getString("lane"), j.optLong("g"), j.getLong("el"))
            ep.positionId = j.optString("pid"); ep.materialAtMs = j.optLong("mat"); ep.orderViolations = j.optInt("ov")
            val a = j.getJSONArray("ev")
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                ep.events.add(Event(Stage.valueOf(e.getString("s")), e.optString("vd"), e.optString("r"), Basis.valueOf(e.getString("b")), e.getLong("t"),
                    if (e.isNull("x")) Double.NaN else e.getDouble("x"), e.optString("l")))
            }
            ep
        } catch (_: Exception) { null }
    }.toList()

    // ── persistence (off the trading path) ──────────────────────────────────────────────────────────────────────

    private fun persistAsync(ep: Episode) {
        val line = try { toJson(ep).toString() } catch (_: Throwable) { return }
        try {
            io.execute {
                try {
                    val ctx = com.lifecyclebot.AATEApp.appContextOrNull() ?: return@execute
                    val f = File(ctx.filesDir, FILE_8395)
                    if (f.exists() && f.length() > 4_000_000L) {
                        val kept = merged(parseJsonl(f.readText())).takeLast(MAX_EPISODES_8395)
                        f.writeText(kept.joinToString("\n") { toJson(it).toString() } + "\n")
                    }
                    f.appendText(line + "\n")
                } catch (_: Throwable) { inc("EPISODE_TRACE_PERSIST_FAILED_8395") }
            }
        } catch (_: Throwable) {}
    }

    /** Later lines of the same episode supersede earlier ones; only terminally DISPOSED episodes are replayable history. */
    fun merged(list: List<Episode>): List<Episode> = LinkedHashMap<String, Episode>().also { m -> list.forEach { m.remove(it.key); m[it.key] = it } }
        .values.filter { ep -> ep.events.any { it.stage == Stage.DISPOSITION && it.verdict == "TERMINAL" } }

    private fun restoredHistory(): List<Episode> = try {
        val f = com.lifecyclebot.AATEApp.appContextOrNull()?.let { File(it.filesDir, FILE_8395) }
        if (f == null || !f.exists()) emptyList() else merged(parseJsonl(f.readText()))
    } catch (_: Throwable) { emptyList() }

    // ── frozen chronological replay (pure) ──────────────────────────────────────────────────────────────────────

    data class LaneReplay(val lane: String, val trainN: Int, val trainNetSol: Double, val trainWins: Int,
                          val validN: Int, val validNetSol: Double, val validWins: Int, val excludedUnverified: Int,
                          val straddled: Int, val stops: Map<String, Int>)

    data class Replay(val cutoffMs: Long, val lanes: List<LaneReplay>, val unknownLane: Int)

    private fun terminalClose(ep: Episode): Event? = ep.events.lastOrNull { it.stage == Stage.DISPOSITION && it.verdict == "TERMINAL" }

    /**
     * Train = decided AND disposed before [cutoffMs]; validation = decided at/after it. An episode decided before the
     * cutoff but disposed after it straddles and is in neither set (its outcome is not known at the cutoff; counting it
     * in training would leak the future). Modelled / unverified values never count as outcomes. Every role is reported,
     * including roles with no episodes, so a silent desk is visible rather than missing.
     */
    fun replay8395(history: List<Episode>, cutoffMs: Long, live: Boolean? = null): Replay {
        val eps = history.filter { live == null || it.mode.equals("LIVE", true) == live }.sortedBy { it.electedAtMs }
        val rows = ROLES_8395.map { role ->
            var tN = 0; var tNet = 0.0; var tW = 0; var vN = 0; var vNet = 0.0; var vW = 0; var unv = 0; var str = 0
            val stops = HashMap<String, Int>()
            for (ep in eps) {
                if (ep.lane != role) continue
                val term = terminal8395(ep.events); stops[term] = (stops[term] ?: 0) + 1
                val close = terminalClose(ep) ?: continue
                if (close.basis != Basis.REALISED || !close.value.isFinite()) { unv++; continue }
                val net = close.value
                when {
                    ep.electedAtMs < cutoffMs && close.atMs < cutoffMs -> { tN++; tNet += net; if (net > 0.0) tW++ }
                    ep.electedAtMs >= cutoffMs -> { vN++; vNet += net; if (net > 0.0) vW++ }
                    else -> str++
                }
            }
            LaneReplay(role, tN, tNet, tW, vN, vNet, vW, unv, str, stops)
        }
        return Replay(cutoffMs, rows, eps.count { it.lane !in ROLES_8395 })
    }

    fun render8395(r: Replay): String = buildString {
        append("===== EPISODE REPLAY (V5.0.8395 R10) cutoff=${r.cutoffMs} unknownLane=${r.unknownLane} =====\n")
        append("  realised money only (canonical paper close / chain-proven live close); straddlers excluded from both periods\n")
        for (l in r.lanes) {
            append("  ").append("%-15s".format(l.lane))
                .append(" train n=${l.trainN} net=${"%+.4f".format(l.trainNetSol)} wins=${l.trainWins}")
                .append(" | valid n=${l.validN} net=${"%+.4f".format(l.validNetSol)} wins=${l.validWins}")
                .append(" | unverified=${l.excludedUnverified} straddle=${l.straddled} | ")
                .append(l.stops.entries.sortedByDescending { it.value }.take(3).joinToString(",") { "${it.key}=${it.value}" })
                .append('\n')
        }
    }

    /** Chronological split: the earliest [trainShare] of elections train, the rest validate. Pure. */
    fun chronologicalCutoff8395(history: List<Episode>, trainShare: Double = 0.7): Long {
        val t = history.map { it.electedAtMs }.filter { it > 0L }.sorted()
        if (t.isEmpty()) return 0L
        return t[(t.size * trainShare).toInt().coerceIn(0, t.size - 1)]
    }

    @Volatile private var replayCache8395: Pair<Long, String>? = null

    /** The operator report's replay of the exported history, at most once per 10 minutes, report thread only. */
    fun replayReport8395(nowMs: Long = System.currentTimeMillis()): String {
        replayCache8395?.let { if (nowMs - it.first < 600_000L) return it.second }
        val h = restoredHistory()
        val out = if (h.isEmpty()) "===== EPISODE REPLAY (V5.0.8395 R10) no disposed episodes exported yet =====\n" else {
            val cut = chronologicalCutoff8395(h)
            "LIVE " + render8395(replay8395(h, cut, live = true)) + "PAPER " + render8395(replay8395(h, cut, live = false))
        }
        replayCache8395 = nowMs to out
        return out
    }

    // ── status ──────────────────────────────────────────────────────────────────────────────────────────────────

    fun statusLine8395(): String {
        val terms = HashMap<String, Int>()
        episodes.values.forEach { val t = terminal8395(it.snapshot()); terms[t] = (terms[t] ?: 0) + 1 }
        return "episodes8395=${episodes.size} preMints=${pre.size} file=$FILE_8395 terminals=[" +
            terms.entries.sortedByDescending { it.value }.take(6).joinToString(",") { "${it.key}=${it.value}" } + "] " +
            counters.entries.filter { it.key.contains("UNBOUND") || it.key.contains("VIOLATION") }
                .joinToString(",") { "${it.key.removePrefix("EPISODE_TRACE_")}=${it.value.get()}" }
    }

    internal fun episodeFor(key: String): Episode? = episodes[key]
    internal fun episodeForPosition(positionId: String): Episode? = byPosition[positionId]?.let { episodes[it] }

    internal fun resetForTest() { replayCache8395 = null; episodes.clear(); byAttempt.clear(); byPosition.clear(); pre.clear(); counters.clear() }
}
