package com.lifecyclebot.engine.cortex

import org.json.JSONArray
import org.json.JSONObject

/** Exact decision identities and original setup contribution weights; never a mint-only latest-outcome lookup. */
class LaneSetupEpisodes8372(private val capacity: Int = 8_000) {
    data class Pending(val id: String, val base: String, val lane: String, val atMs: Long, val tag: String, val timing: String?,
                       val independent8871: LanePlaybookDecision8871? = null)
    data class Contribution(val stamp: Double, var net: Double, var gross: Double, val timingStamp: Double = Double.NaN)
    class Grade(val decision: Pending, val contributions: Map<String, Contribution>, var terminal: Boolean = false) {
        val primary: String get() = decision.tag.substringBefore(';').substringBefore('#')
    }

    private val pending = LinkedHashMap<String, Pending>()
    private val inFlight = HashMap<String, Pending>()
    private val latest = HashMap<String, String>()
    private val grades = LinkedHashMap<String, Grade>()
    private data class EpisodeClock8871(val decisionAtMs: Long, val retainedAtMs: Long)
    private val episodeClocks8871 = HashMap<String, EpisodeClock8871>()
    private var clockWatermark8871 = 0L
    private var clockPrunedAt8871 = 0L
    private data class Observation(val id: String, val atMs: Long, var disposition: String? = null)
    private val observations = LinkedHashMap<String, Observation>()
    var evicted: Long = 0L
        private set
    var ignoredLegacy: Long = 0L
        private set

    companion object {
        private const val PENDING_RETENTION_MS_8871 = 90L * 60_000L
        fun key(mint: String, labelLane: String, atMs: Long): String? =
            if (mint.isBlank() || labelLane.isBlank() || atMs <= 0L) null else "$mint|${labelLane.trim().uppercase()}|$atMs"
    }

    @Synchronized fun pendingCount(): Int = pending.size
    @Synchronized fun gradeCount(): Int = grades.size
    @Synchronized fun inFlight(mint: String, lane: String, atMs: Long): Boolean = key(mint, lane, atMs)?.let { inFlight.containsKey(it) } == true

    /** Register a real decision even when no setup classification was captured; no inferred setup or reward. */
    @Synchronized
    fun observe(mint: String, labelLane: String, atMs: Long): Boolean {
        val id = key(mint, labelLane, atMs) ?: return false
        if (!observations.containsKey(id)) observations[id] = Observation(id, atMs)
        while (observations.size > capacity) {
            observations.values.minByOrNull { it.atMs }?.id?.let { observations.remove(it); evicted++ } ?: break
        }
        return observations.containsKey(id)
    }

    @Synchronized fun observed(mint: String, lane: String, atMs: Long): Boolean = key(mint, lane, atMs)?.let { observations.containsKey(it) } == true
    @Synchronized fun disposition(mint: String, lane: String, atMs: Long): String? =
        key(mint, lane, atMs)?.let { observations[it]?.disposition }

    /** Explicit no-mutation terminal receipt. An unknown identity is not silently acknowledged. */
    @Synchronized
    fun dispose(mint: String, lane: String, atMs: Long, reason: String): Boolean {
        val id = key(mint, lane, atMs) ?: return false
        val o = observations[id] ?: return false
        if (reason.isBlank()) return false
        if (o.disposition == null) o.disposition = reason
        inFlight.remove(id)
        return true
    }

    /** Atomic missing-capture disposition: a concurrent capture/grade cannot be mistaken for an untracked experiment. */
    @Synchronized
    fun disposeUncaptured(mint: String, lane: String, atMs: Long, reason: String): Boolean {
        val id = key(mint, lane, atMs) ?: return false
        if (pending.containsKey(id) || inFlight.containsKey(id) || grades.containsKey(id)) return false
        return dispose(mint, lane, atMs, reason)
    }

    @Synchronized
    fun capture(mint: String, labelLane: String, atMs: Long, lane: String, tag: String, timing: String?,
                independent8871: LanePlaybookDecision8871? = null): Boolean {
        val id = key(mint, labelLane, atMs) ?: return false
        if (independent8871 != null && independent8871.decisionId != id) return false
        if (observations[id]?.disposition != null) return false
        if (lane.isBlank() || tag.isBlank() || pending.containsKey(id) || inFlight.containsKey(id) || grades.containsKey(id)) return false
        observe(mint, labelLane, atMs)
        val base = "$mint|${labelLane.trim().uppercase()}"
        pending[id] = Pending(id, base, lane, atMs, tag, timing, independent8871)
        val oldLatest = latest[base]?.let { pending[it] }
        if (oldLatest == null || atMs >= oldLatest.atMs) latest[base] = id
        trimPending()
        pruneEpisodeClocks8871(atMs)
        return pending.containsKey(id)
    }

    @Synchronized fun latestTag(mint: String, labelLane: String): String? =
        latest["$mint|${labelLane.trim().uppercase()}"]?.let { pending[it]?.tag }

    @Synchronized fun pending(mint: String, labelLane: String, atMs: Long): Pending? = key(mint, labelLane, atMs)?.let { pending[it] }
    @Synchronized fun grade(mint: String, labelLane: String, atMs: Long): Grade? = key(mint, labelLane, atMs)?.let { grades[it] }

    /** The durable producer owns this exact decision, including its original setup and eligibility. */
    @Synchronized
    fun freezeIndependent8871(mint: String, labelLane: String, atMs: Long): String? = key(mint, labelLane, atMs)?.let { id ->
        (pending[id] ?: inFlight[id] ?: grades[id]?.decision)?.takeIf { it.independent8871 != null }?.let { encodePending(it).toString() }
    }

    /** Decode separately from restore: a malformed or cross-episode payload must never change held history. */
    fun decodeIndependent8871(raw: String, mint: String, labelLane: String, atMs: Long, nowMs: Long): Pending? = try {
        decodePending(JSONObject(raw), nowMs)?.takeIf {
            it.id == key(mint, labelLane, atMs) && it.independent8871?.decisionId == it.id
        }
    } catch (_: Exception) { null }

    /** Validate an owned original without admitting it to the ordinary, capacity-limited pending queue. */
    @Synchronized
    fun matchesIndependent8871(original: Pending): Boolean {
        val existing = pending[original.id] ?: inFlight[original.id] ?: grades[original.id]?.decision
        return existing == null || existing == original
    }

    /** The producer already owns this original. Reserve it only for the worker's immediate label application. */
    @Synchronized
    fun reserveIndependent8871(original: Pending): Pending? {
        if (original.independent8871?.decisionId != original.id || !matchesIndependent8871(original) ||
            inFlight.containsKey(original.id) || grades.containsKey(original.id) ||
            observations[original.id]?.disposition != null) return null
        val leased = removePending(original.id) ?: original
        inFlight[original.id] = leased
        observations.putIfAbsent(original.id, Observation(original.id, original.atMs))
        return leased
    }

    /** A failed application remains owned by the durable producer; never remove another caller's lease. */
    @Synchronized
    fun releaseIndependent8871(leased: Pending) {
        if (inFlight[leased.id] === leased) inFlight.remove(leased.id)
    }

    /** Same six-hour refusal-episode rule as the full graph, now shared by both routes into this setup book. */
    @Synchronized
    fun lastGradedAt8871(mint: String, labelLane: String): Long? {
        val base = "$mint|${labelLane.trim().uppercase()}"
        return episodeClocks8871[base]?.decisionAtMs
    }

    @Synchronized
    fun take(mint: String, labelLane: String, atMs: Long): Pending? =
        key(mint, labelLane, atMs)?.let { id -> removePending(id)?.also { inFlight[id] = it } }

    @Synchronized
    fun record(decision: Pending, contributions: Map<String, Contribution>, receivedAtMs8871: Long = decision.atMs): Boolean {
        if (contributions.isEmpty() || grades.containsKey(decision.id)) return false
        inFlight.remove(decision.id)
        observations.putIfAbsent(decision.id, Observation(decision.id, decision.atMs))
        grades[decision.id] = Grade(decision, contributions)
        val previous = episodeClocks8871[decision.base]
        episodeClocks8871[decision.base] = EpisodeClock8871(maxOf(previous?.decisionAtMs ?: 0L, decision.atMs),
            maxOf(previous?.retainedAtMs ?: 0L, receivedAtMs8871, decision.atMs))
        pruneEpisodeClocks8871(receivedAtMs8871)
        while (grades.size > capacity) {
            grades.entries.minByOrNull { it.value.decision.atMs }?.key?.let { grades.remove(it); evicted++ } ?: break
        }
        return grades.containsKey(decision.id)
    }

    /** Detail eviction cannot forget the existing refusal episode. Retention clocks never replace decision clocks. */
    private fun pruneEpisodeClocks8871(nowMs: Long, force: Boolean = false) {
        clockWatermark8871 = maxOf(clockWatermark8871, nowMs)
        // Cleanup is not part of the hot capture cost for every lane of the same token.
        if (!force && clockPrunedAt8871 > 0L && clockWatermark8871 - clockPrunedAt8871 < 60_000L) return
        clockPrunedAt8871 = clockWatermark8871
        val oldestPending = (pending.values.asSequence() + inFlight.values.asSequence())
            .map { it.atMs }.filter { clockWatermark8871 - it in 0L..PENDING_RETENTION_MS_8871 }.minOrNull()
        val anchor = minOf(clockWatermark8871, oldestPending ?: clockWatermark8871)
        episodeClocks8871.entries.removeIf { anchor - it.value.retainedAtMs >= CortexLabelHygiene8144.EPISODE_MS_8144 }
    }

    @Synchronized
    fun episodeClocksSnapshot8871(nowMs: Long): JSONObject {
        pruneEpisodeClocks8871(nowMs, force = true)
        return JSONObject().put("v8871", 1).put("rows", JSONObject().also { rows ->
            episodeClocks8871.forEach { (base, clock) -> rows.put(base, JSONArray().put(clock.decisionAtMs).put(clock.retainedAtMs)) }
        })
    }

    @Synchronized
    fun restoreEpisodeClocks8871(root: JSONObject?, nowMs: Long) {
        if (root?.optInt("v8871") == 1) root.optJSONObject("rows")?.let { rows ->
            for (base in rows.keys()) {
                val values = rows.optJSONArray(base) ?: continue
                val at = values.optLong(0); val retained = values.optLong(1)
                if (!base.contains('|') || at <= 0L || at > nowMs || retained < at || retained > nowMs) continue
                val previous = episodeClocks8871[base]
                episodeClocks8871[base] = EpisodeClock8871(maxOf(previous?.decisionAtMs ?: 0L, at),
                    maxOf(previous?.retainedAtMs ?: 0L, retained))
            }
        }
        // Old snapshots rebuild only the clocks justified by their retained grade details; no history is invented.
        pruneEpisodeClocks8871(nowMs, force = true)
    }

    /** Replace an existing contribution at its ORIGINAL weight; a terminal selected exit is immutable to forward labels. */
    @Synchronized
    fun revise(mint: String, labelLane: String, atMs: Long, net: Double, gross: Double, terminal: Boolean,
               select: (String) -> Boolean, book: (String, String) -> CortexLedger7885.Stat?,
               timingBook: (String, String, String) -> CortexLedger7885.Stat?): Boolean {
        if (!net.isFinite()) return false
        val g = grade(mint, labelLane, atMs) ?: return false
        if (disposition(mint, labelLane, atMs) != null) return terminal
        if (g.terminal) return terminal  // terminal redelivery is acknowledged; a future forward mark cannot overwrite it
        val selected = g.contributions.filterKeys { if (terminal) it == g.primary else select(it) }
        if (selected.isEmpty()) return false
        // Verify every selected contribution before mutation. Re-created books cannot receive a predecessor's delta.
        val targets = selected.map { (setup, c) ->
            val st = book(g.decision.lane, setup) ?: return false
            if (!c.stamp.isFinite() || !c.net.isFinite() || st.logScale > c.stamp + 1e-9) return false
            Triple(setup, c, st)
        }
        val y = net.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX)
        val rn = gross.isFinite() && gross >= CortexLedger7885.RUNNER_GROSS_PCT
        for ((setup, c, st) in targets) {
            val old = c.net.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX)
            val ro = c.gross.isFinite() && c.gross >= CortexLedger7885.RUNNER_GROSS_PCT
            st.revise(old, y, ro, rn, st.revisionWeight(c.stamp))
            val timing = g.decision.timing
            if (timing != null && c.timingStamp.isFinite()) timingBook(g.decision.lane, setup, timing)?.let { ts ->
                if (ts.logScale <= c.timingStamp + 1e-9) ts.revise(old, y, ro, rn, ts.revisionWeight(c.timingStamp))
            }
            c.net = y; c.gross = gross
        }
        if (terminal) g.terminal = true
        return true
    }

    @Synchronized
    fun pendingSnapshot(): JSONObject = JSONObject().put("v8372", 1).put("rows", JSONArray().also { rows ->
        // A snapshot racing the gap between take() and record() restores the ungraded original, not a missing identity.
        (pending.values + inFlight.values).distinctBy { it.id }.sortedBy { it.atMs }.forEach { rows.put(encodePending(it)) }
    })

    @Synchronized
    fun gradesSnapshot(): JSONArray = JSONArray().also { rows ->
        grades.values.sortedBy { it.decision.atMs }.forEach { g ->
            rows.put(encodePending(g.decision).put("terminal", g.terminal).put("contributions", JSONObject().also { cs ->
                g.contributions.forEach { (setup, c) ->
                    cs.put(setup, JSONObject().put("stamp", c.stamp).put("net", c.net)
                        .put("gross", c.gross.takeIf { it.isFinite() } ?: JSONObject.NULL)
                        .put("timingStamp", c.timingStamp.takeIf { it.isFinite() } ?: JSONObject.NULL))
                }
            }))
        }
    }

    @Synchronized
    fun observationsSnapshot(): JSONArray = JSONArray().also { rows ->
        observations.values.sortedBy { it.atMs }.forEach {
            rows.put(JSONObject().put("id", it.id).put("at", it.atMs).put("reason", it.disposition ?: JSONObject.NULL))
        }
    }

    @Synchronized
    fun restoreObservations(rows: JSONArray?, nowMs: Long) {
        if (rows == null) return
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id"); val at = row.optLong("at")
            if (at <= 0L || at > nowMs || nowMs - at > 8L * 86_400_000L || !id.endsWith("|$at")) continue
            val reason = row.optString("reason").takeIf { it.isNotBlank() && it != "null" }
            val existing = observations[id]
            if (existing == null) observations[id] = Observation(id, at, reason)
            else if (existing.atMs == at && existing.disposition == null) existing.disposition = reason
        }
        while (observations.size > capacity) {
            observations.values.minByOrNull { it.atMs }?.id?.let { observations.remove(it); evicted++ } ?: break
        }
    }

    @Synchronized
    fun restorePending(root: JSONObject, nowMs: Long) {
        if (root.optInt("v8372") != 1) { ignoredLegacy += root.length(); return }
        val rows = root.optJSONArray("rows") ?: return
        for (i in 0 until rows.length()) {
            val p = decodePending(rows.optJSONObject(i), nowMs) ?: continue
            if (nowMs - p.atMs > PENDING_RETENTION_MS_8871 || pending.containsKey(p.id) || grades.containsKey(p.id)) continue
            pending[p.id] = p
            observations.putIfAbsent(p.id, Observation(p.id, p.atMs))
            val old = latest[p.base]?.let { pending[it] }
            if (old == null || p.atMs >= old.atMs) latest[p.base] = p.id
        }
        trimPending()
    }

    @Synchronized
    fun restoreGrades(rows: JSONArray?, nowMs: Long) {
        if (rows == null) return
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val p = decodePending(row, nowMs) ?: continue
            if (nowMs - p.atMs > 8L * 86_400_000L || grades.containsKey(p.id)) continue
            val cs = row.optJSONObject("contributions") ?: continue
            val contributions = LinkedHashMap<String, Contribution>()
            for (setup in cs.keys()) {
                val c = cs.optJSONObject(setup) ?: continue
                val stamp = c.optDouble("stamp", Double.NaN); val net = c.optDouble("net", Double.NaN)
                if (stamp.isFinite() && net.isFinite()) contributions[setup] = Contribution(stamp, net,
                    c.optDouble("gross", Double.NaN), c.optDouble("timingStamp", Double.NaN))
            }
            if (record(p, contributions)) grades[p.id]?.terminal = row.optBoolean("terminal")
        }
    }

    private fun encodePending(p: Pending): JSONObject = JSONObject().put("id", p.id).put("base", p.base)
        .put("lane", p.lane).put("decisionAt", p.atMs).put("tag", p.tag)
        .put("timing", p.timing ?: JSONObject.NULL).put("independent8871", p.independent8871?.json())

    private fun decodePending(row: JSONObject?, nowMs: Long): Pending? {
        if (row == null) return null
        val id = row.optString("id"); val base = row.optString("base"); val lane = row.optString("lane")
        val at = row.optLong("decisionAt"); val tag = row.optString("tag")
        if (base.isBlank() || lane.isBlank() || tag.isBlank() || at <= 0L || at > nowMs || id != "$base|$at") return null
        val independent = row.optJSONObject("independent8871")?.let { LanePlaybookDecision8871.decode(it) }
        if (row.has("independent8871") && !row.isNull("independent8871") && independent?.decisionId != id) return null
        return Pending(id, base, lane, at, tag, row.optString("timing").takeIf { it.isNotBlank() && it != "null" }, independent)
    }

    private fun removePending(id: String): Pending? {
        val p = pending.remove(id) ?: return null
        if (latest[p.base] == id) {
            val remaining = pending.values.filter { it.base == p.base }.maxByOrNull { it.atMs }
            if (remaining == null) latest.remove(p.base) else latest[p.base] = remaining.id
        }
        return p
    }

    private fun trimPending() {
        while (pending.size > capacity) {
            pending.values.minByOrNull { it.atMs }?.id?.let { removePending(it); evicted++ } ?: break
        }
    }
}
