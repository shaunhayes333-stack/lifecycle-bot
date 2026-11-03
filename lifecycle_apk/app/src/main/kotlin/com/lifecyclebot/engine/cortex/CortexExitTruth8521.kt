package com.lifecyclebot.engine.cortex

import com.lifecyclebot.engine.truth.LabelMark8386
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable two-stage exit learning. Confirmed banked proceeds teach immediately; the later
 * HOLD counterfactual teaches only from a timestamped mark in its own horizon. A receipt,
 * the updated books and remaining queue are one transaction, before memory changes/ACK.
 */
internal class CortexExitTruth8521(
    private val capacity: Int = 400,
    private val load: () -> String?,
    private val commit: (String, String) -> Receipt,
) {
    enum class Receipt { COMMITTED, ALREADY_COMMITTED, RETRY }

    data class Pending(
        val key: String, val mint: String, val lane: String, val mode: String, val costSol: Double,
        val slices: List<LearningExitTruth8153.Slice8153>, val dueMs: Long,
    ) {
        val bookKey: String get() = "$mode|$lane"
        fun json(): JSONObject = JSONObject().put("key", key).put("mint", mint).put("lane", lane)
            .put("mode", mode).put("cost", costSol).put("due", dueMs)
            .put("slices", JSONArray().also { arr -> slices.forEach { s ->
                arr.put(JSONArray().put(s.soldFraction).put(s.netProceedsSol)
                    .put(s.exitPriceUsd.takeIf { it.isFinite() } ?: 0.0).put(s.atMs))
            } })

        companion object {
            fun decode(j: JSONObject): Pending {
                val arr = j.getJSONArray("slices")
                val slices = (0 until arr.length()).map { i -> arr.getJSONArray(i).let {
                    LearningExitTruth8153.Slice8153(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getLong(3))
                } }
                return Pending(j.getString("key"), j.getString("mint"), j.getString("lane"), j.getString("mode"),
                    j.getDouble("cost"), slices, j.getLong("due")).also { p ->
                    require(p.key.isNotBlank() && p.mint.isNotBlank() && p.lane.isNotBlank())
                    require(p.mode == "LIVE" || p.mode == "PAPER")
                    require(p.costSol.isFinite() && p.costSol > 0.0 && p.dueMs > 0L && p.slices.isNotEmpty())
                    require(p.slices.all { it.netProceedsSol.isFinite() && it.netProceedsSol >= 0.0 })
                }
            }
        }
    }

    private val pending = LinkedHashMap<String, Pending>()
    private val books = LinkedHashMap<String, LearningExitTruth8153.Book8153>()
    private var reloadBeforeWrite = false
    private var durableSnapshotKnown = false

    @Synchronized fun pending(): List<Pending> = pending.values.toList()
    @Synchronized fun contains(key: String): Boolean = key in pending
    @Synchronized fun estimatedBytes(): Long = pending.values.sumOf { 320L + it.slices.size * 64L } + books.size * 200L
    @Synchronized fun book(key: String): LearningExitTruth8153.Book8153? = books[key]?.let(::copy)
    @Synchronized fun lines(): String = books.entries.sortedBy { it.key }.take(8)
        .joinToString(" · ") { (k, b) -> "$k ${b.line()}" }.ifBlank { "no graded closes yet" }

    private fun copy(b: LearningExitTruth8153.Book8153): LearningExitTruth8153.Book8153 =
        LearningExitTruth8153.Book8153().also { require(it.decode(b.encode())) }

    private fun state(nextPending: Map<String, Pending>, nextBooks: Map<String, LearningExitTruth8153.Book8153>): String =
        JSONObject().put("version", 1).put("pending", JSONArray(nextPending.values.map { it.json() }))
            .put("books", JSONObject().also { out -> nextBooks.forEach { (k, b) -> out.put(k, b.encode()) } }).toString()

    /** A commit can land before its response is lost. Reload the committed snapshot before ACK or another write. */
    private fun restoreCommitted(requireRow: Boolean = true): Boolean {
        reloadBeforeWrite = true
        if (requireRow) durableSnapshotKnown = true
        return try {
            val raw = load()
            if (raw == null && durableSnapshotKnown) false else {
                if (raw != null) restore(raw)
                reloadBeforeWrite = false
                true
            }
        } catch (_: Exception) { false }
    }

    private fun commitState(receipt: String, state: String): Receipt = try {
        commit(receipt, state).also { if (it == Receipt.RETRY) reloadBeforeWrite = true }
    } catch (e: Exception) { reloadBeforeWrite = true; throw e }

    /** The caller supplies only canonical, finality-verified sell slices, partitioned by mode. */
    @Synchronized fun freeze(p: Pending, trackCounterfactual: Boolean = true): Boolean {
        if (reloadBeforeWrite && !restoreCommitted(requireRow = false)) return false
        if (pending.containsKey(p.key)) return true
        if ((trackCounterfactual && pending.size >= capacity) || p.mode !in setOf("LIVE", "PAPER") || p.key.isBlank() ||
            p.mint.isBlank() || p.lane.isBlank() || p.dueMs <= 0L || p.slices.isEmpty() ||
            p.slices.any { !it.netProceedsSol.isFinite() || it.netProceedsSol < 0.0 } ||
            (books.size >= 200 && p.bookKey !in books)) return false
        val grade = LearningExitTruth8153.grade8153(p.costSol, p.slices, 0.0, Double.NaN) ?: return false
        if (!grade.totalPct.isFinite()) return false
        val b = books[p.bookKey]?.let(::copy) ?: LearningExitTruth8153.Book8153()
        b.addBanked8521(grade)
        // A delayed canonical close still has exact proceeds. Its expired future
        // horizon cannot be reconstructed, but must not suppress banked learning
        // or occupy a slot needed by a current close.
        val nextPending = LinkedHashMap(pending).also { if (trackCounterfactual) it[p.key] = p }
        val nextBooks = LinkedHashMap(books).also { it[p.bookKey] = b }
        return when (commitState("cortex_exit_truth_started_8521_${p.key}", state(nextPending, nextBooks))) {
            Receipt.RETRY -> false
            Receipt.ALREADY_COMMITTED -> restoreCommitted() // no double grade after replay or a lost commit response
            Receipt.COMMITTED -> {
                if (trackCounterfactual) pending[p.key] = p
                books[p.bookKey] = b; durableSnapshotKnown = true; true
            }
        }
    }

    /** Null at expiry means unknown regret, not zero regret, and never another banked sample. */
    @Synchronized fun complete(key: String, mark: LabelMark8386?, nowMs: Long, graceMs: Long): Boolean {
        if (reloadBeforeWrite && !restoreCommitted(requireRow = false)) return false
        val p = pending[key] ?: return true
        val px = horizonPrice(p.mint, p.dueMs, mark, nowMs, graceMs)
        if (px == null && nowMs <= p.dueMs + graceMs) return false
        val b = books[p.bookKey]?.let(::copy) ?: return false
        LearningExitTruth8153.grade8153(p.costSol, p.slices, 0.0, px ?: Double.NaN)?.let(b::addRegret8521)
        val nextPending = LinkedHashMap(pending).also { it.remove(key) }
        val nextBooks = LinkedHashMap(books).also { it[p.bookKey] = b }
        return when (commitState("cortex_exit_truth_graded_8521_$key", state(nextPending, nextBooks))) {
            Receipt.RETRY -> false
            Receipt.ALREADY_COMMITTED -> restoreCommitted()
            Receipt.COMMITTED -> { pending.remove(key); books[p.bookKey] = b; durableSnapshotKnown = true; true }
        }
    }

    @Synchronized fun restore(raw: String) {
        val j = JSONObject(raw)
        require(j.getInt("version") == 1)
        val arr = j.getJSONArray("pending")
        require(arr.length() <= capacity)
        val rows = (0 until arr.length()).map { Pending.decode(arr.getJSONObject(it)) }
        require(rows.map { it.key }.distinct().size == rows.size)
        val restored = decodeBooks(j.getJSONObject("books"))
        require(rows.all { it.bookKey in restored })
        pending.clear(); rows.forEach { pending[it.key] = it }
        books.clear(); books.putAll(restored)
        durableSnapshotKnown = true
    }

    @Synchronized fun restoreLegacyBooks(j: JSONObject) { books.clear(); books.putAll(decodeBooks(j)) }

    private fun decodeBooks(j: JSONObject): Map<String, LearningExitTruth8153.Book8153> =
        j.keys().asSequence().associateWith { key -> LearningExitTruth8153.Book8153().also {
            require(it.decode(j.getString(key)))
            require(it.n >= 0 && it.sumTotal.isFinite() && it.regretN in 0..it.n &&
                it.sumRegret.isFinite() && it.sumSqRegret.isFinite() && it.sumSqRegret >= 0.0 && it.tailCuts in 0..it.regretN)
        } }

    companion object {
        /** Optional counterfactual slices may be unavailable while the confirmed whole-position return is valid. */
        fun recordUnavailableSlices(mode: String, positionId: String, eventId: String, mint: String, lane: String,
                                    proof: String, netSol: Double, returnPct: Double,
                                    write: (String, String) -> Receipt): Boolean {
            if (mode !in setOf("LIVE", "PAPER") || positionId.isBlank() || mint.isBlank() ||
                !netSol.isFinite() || !returnPct.isFinite()) return false
            val key = "cortex_exit_no_slices_8521_${mode}_$positionId"
            val raw = JSONObject().put("positionId", positionId).put("economicEventId", eventId).put("mode", mode)
                .put("mint", mint).put("lane", lane).put("proof", proof).put("netSol", netSol).put("returnPct", returnPct)
                .put("target", "ACTUAL_WHOLE_POSITION_REALIZED").put("regretUnavailable", "NO_CANONICAL_SELL_SLICES").toString()
            return write(key, raw) != Receipt.RETRY
        }

        /** Cached pre-horizon prices, mismatched assets and after-grace/future prices cannot train this target. */
        fun horizonPrice(mint: String, dueMs: Long, mark: LabelMark8386?, nowMs: Long, graceMs: Long): Double? =
            mark?.takeIf { it.known() && it.mint == mint && dueMs > 0L && nowMs >= dueMs &&
                it.observedAtMs in dueMs..(dueMs + graceMs.coerceAtLeast(0L)) &&
                it.observedAtMs <= nowMs && it.readAtMs <= nowMs }?.usd
    }
}
