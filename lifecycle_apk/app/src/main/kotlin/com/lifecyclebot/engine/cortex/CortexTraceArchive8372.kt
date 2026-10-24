package com.lifecyclebot.engine.cortex

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.TreeMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded cold storage for the ORIGINAL sparse voter/revision trace of a decision.
 *
 * A busy lane can produce more than 600 grades before its runner-hold label is known. Dropping
 * those traces silently makes a later winner revise calibration but not its original voters.
 * This archive preserves the caller's exact trace bytes, including prediction/decay stamps,
 * without keeping thousands of expanded Assessment objects resident. It performs no grading,
 * no outcome reconstruction and no I/O. Save [snapshot] in the SAME checkpoint as the books.
 *
 * Limits are independent of the trade result. Expired rows go first, then the oldest decision
 * if the explicit compressed-byte/row budget is exhausted. Every exclusion is counted.
 */
class CortexTraceArchive8372(
    private val maxRecords: Int = 8_000,
    private val maxCompressedBytes: Long = 32L * 1024L * 1024L,
) {
    companion object {
        const val MAX_EXPANDED_BYTES = 512 * 1024
        private const val MAX_ID_CHARS = 1_024
        private const val FORMAT = 1
        // gzip of an incompressible, maximum-sized record can be a little larger than its input.
        private const val MAX_COMPRESSED_RECORD_BYTES = MAX_EXPANDED_BYTES + 1_024
        private const val MAX_ENCODED_CHARS = ((MAX_COMPRESSED_RECORD_BYTES + 2) / 3) * 4
    }

    init {
        require(maxRecords >= 0) { "maxRecords must not be negative" }
        require(maxCompressedBytes >= 0L) { "maxCompressedBytes must not be negative" }
    }

    private data class Entry(val atMs: Long, val expiresAtMs: Long, val gzip: ByteArray)
    private data class Order(val atMs: Long, val id: String) : Comparable<Order> {
        override fun compareTo(other: Order): Int {
            val time = atMs.compareTo(other.atMs)
            return if (time != 0) time else id.compareTo(other.id)
        }
    }

    data class Counts(
        val capacityEvicted: Long,
        val capacityRejected: Long,
        val expired: Long,
        val invalid: Long,
        val corrupt: Long,
    )

    private val entries = HashMap<String, Entry>()
    private val byDecision = TreeMap<Order, String>()
    private val byExpiry = TreeMap<Order, String>()
    private var compressedBytes = 0L
    private var capacityEvicted = 0L
    private var capacityRejected = 0L
    private var expired = 0L
    private var invalid = 0L
    private var corrupt = 0L

    @Synchronized fun size(): Int = entries.size
    @Synchronized fun bytes(): Long = compressedBytes
    @Synchronized fun counts(): Counts = Counts(capacityEvicted, capacityRejected, expired, invalid, corrupt)

    /** Replaces only the same episode. An invalid update does not destroy a previously valid trace. */
    @Synchronized
    fun put(id: String, atMs: Long, expiresAtMs: Long, payload: String, nowMs: Long): Boolean {
        prune(nowMs)
        if (!validIdentity(id, atMs, expiresAtMs, nowMs) || payload.length > MAX_EXPANDED_BYTES ||
            (entries[id]?.atMs?.let { it != atMs } == true)) {
            invalid++
            return false
        }
        val utf8 = payload.toByteArray(Charsets.UTF_8)
        if (utf8.isEmpty() || utf8.size > MAX_EXPANDED_BYTES) {
            invalid++
            return false
        }
        val gzip = try {
            ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(utf8) } }.toByteArray()
        } catch (_: Exception) {
            invalid++
            return false
        }
        return store(id, Entry(atMs, expiresAtMs, gzip))
    }

    /** Returns the original text, or a named/countable absence; invalid compressed data is never a partial trace. */
    @Synchronized
    fun get(id: String, nowMs: Long): String? {
        prune(nowMs)
        val entry = entries[id] ?: return null
        return inflate(entry.gzip) ?: run {
            removeInternal(id)
            corrupt++
            null
        }
    }

    @Synchronized fun remove(id: String): Boolean = removeInternal(id) != null

    /** Model-book replacement invalidates every original revision weight, but never erases diagnostic history. */
    @Synchronized
    fun clearRevisionCoordinates8372(): Int {
        val removed = entries.size
        entries.clear()
        byDecision.clear()
        byExpiry.clear()
        compressedBytes = 0L
        return removed
    }

    /** Expiry is exclusive: a trace at its expiry instant can no longer be revised. */
    @Synchronized
    fun prune(nowMs: Long): Int {
        var removed = 0
        while (byExpiry.isNotEmpty()) {
            val first = byExpiry.firstEntry()
            if (first.key.atMs > nowMs) break
            if (removeInternal(first.value) != null) { expired++; removed++ }
        }
        return removed
    }

    /** Snapshot order is deterministic and chronological; gzip bytes are encoded, not re-compressed. */
    @Synchronized
    fun snapshot(nowMs: Long): JSONObject {
        prune(nowMs)
        val rows = JSONArray()
        for ((_, id) in byDecision) {
            val entry = entries[id] ?: continue
            rows.put(JSONObject().put("id", id).put("at", entry.atMs).put("until", entry.expiresAtMs)
                .put("gz", Base64.getEncoder().encodeToString(entry.gzip)))
        }
        return JSONObject().put("v", FORMAT).put("rows", rows)
            .put("evicted", capacityEvicted).put("rejected", capacityRejected)
            .put("expired", expired).put("invalid", invalid).put("corrupt", corrupt)
    }

    /** Restore only bounded, valid records. A malformed record cannot erase the other episodes. */
    @Synchronized
    fun restore(snapshot: JSONObject?, nowMs: Long) {
        if (snapshot == null) return
        if (snapshot.optInt("v", -1) != FORMAT) { invalid++; return }
        val rows = snapshot.optJSONArray("rows") ?: run { invalid++; return }
        entries.clear(); byDecision.clear(); byExpiry.clear(); compressedBytes = 0L
        capacityEvicted = snapshot.optLong("evicted", 0L).coerceAtLeast(0L)
        capacityRejected = snapshot.optLong("rejected", 0L).coerceAtLeast(0L)
        expired = snapshot.optLong("expired", 0L).coerceAtLeast(0L)
        invalid = snapshot.optLong("invalid", 0L).coerceAtLeast(0L)
        corrupt = snapshot.optLong("corrupt", 0L).coerceAtLeast(0L)
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i)
            if (row == null) { invalid++; continue }
            val id = row.optString("id")
            val atMs = row.optLong("at", 0L)
            val until = row.optLong("until", 0L)
            if (until > atMs && until <= nowMs && atMs > 0L) { expired++; continue }
            if (!validIdentity(id, atMs, until, nowMs) || entries.containsKey(id)) { invalid++; continue }
            val encoded = row.optString("gz")
            if (encoded.isEmpty() || encoded.length > MAX_ENCODED_CHARS) { corrupt++; continue }
            val gzip = try { Base64.getDecoder().decode(encoded) } catch (_: Exception) { null }
            if (gzip == null || gzip.size > MAX_COMPRESSED_RECORD_BYTES || inflate(gzip) == null) {
                corrupt++
                continue
            }
            store(id, Entry(atMs, until, gzip))
        }
    }

    private fun validIdentity(id: String, atMs: Long, expiresAtMs: Long, nowMs: Long): Boolean =
        id.isNotBlank() && id.length <= MAX_ID_CHARS && atMs > 0L && atMs <= nowMs &&
            expiresAtMs > atMs && expiresAtMs > nowMs

    private fun store(id: String, entry: Entry): Boolean {
        if (maxRecords == 0 || entry.gzip.size.toLong() > maxCompressedBytes || entry.gzip.size > MAX_COMPRESSED_RECORD_BYTES) {
            capacityRejected++
            return false
        }
        // Plan first so a quota-rejected replacement cannot destroy its smaller, valid predecessor.
        // An out-of-order old spill must not evict a newer decision merely because it arrived later.
        val previous = entries[id]
        var nextCount = entries.size + if (previous == null) 1 else 0
        var nextBytes = compressedBytes - (previous?.gzip?.size ?: 0) + entry.gzip.size
        val incomingOrder = Order(entry.atMs, id)
        val evict = ArrayList<String>()
        for ((order, candidate) in byDecision) {
            if (nextCount <= maxRecords && nextBytes <= maxCompressedBytes) break
            if (candidate == id) continue
            if (incomingOrder <= order) { capacityRejected++; return false }
            evict.add(candidate)
            nextCount--
            nextBytes -= entries[candidate]?.gzip?.size ?: 0
        }
        if (nextCount > maxRecords || nextBytes > maxCompressedBytes) { capacityRejected++; return false }
        for (candidate in evict) {
            removeInternal(candidate)
            capacityEvicted++
        }
        removeInternal(id)
        entries[id] = entry
        byDecision[Order(entry.atMs, id)] = id
        byExpiry[Order(entry.expiresAtMs, id)] = id
        compressedBytes += entry.gzip.size
        return true
    }

    private fun removeInternal(id: String): Entry? {
        val removed = entries.remove(id) ?: return null
        byDecision.remove(Order(removed.atMs, id))
        byExpiry.remove(Order(removed.expiresAtMs, id))
        compressedBytes -= removed.gzip.size
        return removed
    }

    /** Bound expanded data while streaming; neither a gzip bomb nor invalid UTF-8 may become a reconstructed trace. */
    private fun inflate(gzip: ByteArray): String? {
        return try {
            if (gzip.isEmpty() || gzip.size > MAX_COMPRESSED_RECORD_BYTES) null
            else GZIPInputStream(ByteArrayInputStream(gzip)).use { input ->
                val out = ByteArrayOutputStream(minOf(MAX_EXPANDED_BYTES, gzip.size * 4))
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_EXPANDED_BYTES) return null
                    out.write(buffer, 0, n)
                }
                if (total == 0) null else Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray())).toString()
            }
        } catch (_: Exception) { null }
    }
}
