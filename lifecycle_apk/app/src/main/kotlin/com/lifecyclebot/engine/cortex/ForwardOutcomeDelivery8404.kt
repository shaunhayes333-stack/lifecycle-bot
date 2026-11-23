package com.lifecyclebot.engine.cortex

import org.json.JSONArray
import org.json.JSONObject

/** A delivered value owns its original prediction and observation proof, not a later token read. */
data class ForwardOutcomeDelivery8404(
    val mint: String, val lane: String, val atMs: Long, val net: Double, val gross: Double,
    val family: DoubleArray?, val flat: Boolean, val witness: Boolean,
    val proof: CortexOutcomeProof8395?, val frozenPrediction: String,
    val revision: Boolean = false, val previousNet: Double = net, val previousGross: Double = gross,
    val playbookPrediction8871: String? = null,
) {
    val id: String get() = "$mint|$lane|$atMs"
    val deliveryId: String get() = if (revision) "$id|R|${proof?.observedAtMs}|${proof?.horizonMs}" else id
    fun encode(): String {
        require(mint.isNotBlank() && lane.isNotBlank() && atMs > 0 && net.isFinite() && gross.isFinite())
        require(family == null || family.all { it.isFinite() })
        require(!revision || (proof != null && previousNet.isFinite() && previousGross.isFinite()))
        return JSONObject().put("v", 1).put("mint", mint).put("lane", lane).put("at", atMs)
            .put("net", net).put("gross", gross).put("family", family?.let { JSONArray(it.toList()) })
            .put("flat", flat).put("witness", witness).put("proof", proof?.encode8395())
            .put("prediction", frozenPrediction).put("revision", revision)
            .put("previousNet", previousNet).put("previousGross", previousGross)
            .put("playbook8871", playbookPrediction8871).toString()
    }
    companion object {
        fun decode(raw: String): ForwardOutcomeDelivery8404 {
            val j = JSONObject(raw); require(j.getInt("v") == 1)
            val family = j.optJSONArray("family")?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            return ForwardOutcomeDelivery8404(j.getString("mint"), j.getString("lane"), j.getLong("at"),
                j.getDouble("net"), j.getDouble("gross"), family, j.getBoolean("flat"), j.getBoolean("witness"),
                j.optString("proof").takeIf { it.isNotBlank() }?.let { requireNotNull(CortexOutcomeProof8395.decode8395(it)) },
                j.getString("prediction"), j.optBoolean("revision"), j.optDouble("previousNet", j.getDouble("net")),
                j.optDouble("previousGross", j.getDouble("gross")),
                j.optString("playbook8871").takeIf { it.isNotBlank() && it != "null" }).also {
                require(it.mint.isNotBlank() && it.lane.isNotBlank() && it.atMs > 0 && it.net.isFinite() && it.gross.isFinite())
                require(it.family == null || it.family.all { value -> value.isFinite() })
                require(it.proof == null || it.proof.decisionId == it.id)
                require(!it.revision || (it.proof != null && it.previousNet.isFinite() && it.previousGross.isFinite()))
            }
        }
    }
}

/**
 * Bounded first-value outbox. Storage ownership and consumer acknowledgement are separate states.
 *
 * V5.0.8977 §THE_OUTBOX_IS_INDEXED — the 24 Nov 04:13 CPU sample (5.0.8970): ForwardReturnLabeler7731's tick was 3.99 s of
 * 30 s, the labeler's single largest cost and its top allocator. [firstRevisionForHorizon8521] walked every held row (up to
 * 18,000) building a fresh "<episode>|R|" String per row on each hour grade (1.88 s), and [drain] copied and sorted every key
 * into four lists on every tick (1.62 s). The rows are now indexed twice: [sorted8977] (the drain's order) and
 * [revisions8977] (each episode's revision ids, in the order they were held). Same rows, same order, same answers: the first
 * revision returned is still the first held, the drain still rotates past its cursor in key order; only the scans and copies
 * are gone. Every change to [rows] goes through [hold8977] / [drop8977], so the indexes cannot drift.
 */
internal class ForwardOutcomeOutbox8404(private val capacity: Int) {
    private val rows = LinkedHashMap<String, String>()
    private val durable = HashSet<String>()
    private val acknowledged = HashSet<String>()
    private var cursor = ""
    private val sorted8977 = java.util.TreeSet<String>()
    private val revisions8977 = HashMap<String, LinkedHashSet<String>>()

    companion object {
        internal const val REVISION_MARK_8977 = "|R|"

        /** Pure: the episode a revision id belongs to ("<episode>|R|<observedAt>|<horizon>"), or null for a first value. */
        internal fun episodeOf8977(id: String): String? {
            val at = id.indexOf(REVISION_MARK_8977)
            return if (at > 0) id.substring(0, at) else null
        }

        /** Pure: the horizon a revision id names (its last field), without building a substring. */
        internal fun horizonOf8977(id: String): Long? {
            val bar = id.lastIndexOf('|')
            if (bar < 0 || bar == id.length - 1) return null
            var v = 0L
            for (i in bar + 1 until id.length) {
                val c = id[i]
                if (c < '0' || c > '9' || v > (Long.MAX_VALUE - 9L) / 10L) return id.substring(bar + 1).toLongOrNull()
                v = v * 10L + (c - '0')
            }
            return v
        }
    }

    private fun hold8977(id: String, raw: String) {
        if (rows.put(id, raw) == null) {
            sorted8977.add(id)
            episodeOf8977(id)?.let { revisions8977.getOrPut(it) { LinkedHashSet() }.add(id) }
        }
    }

    private fun drop8977(id: String) {
        if (rows.remove(id) == null) return
        sorted8977.remove(id)
        episodeOf8977(id)?.let { ep -> revisions8977[ep]?.let { set -> set.remove(id); if (set.isEmpty()) revisions8977.remove(ep) } }
    }

    @Synchronized fun restore(saved: Map<String, String>) {
        saved.forEach { (id, raw) -> hold8977(id, raw); durable.add(id) }
    }
    @Synchronized fun contains(id: String): Boolean = rows.containsKey(id)
    @Synchronized fun payload(id: String): String? = rows[id]
    /** A fixed target is immutable even if its durable write/consumer acknowledgement has not finished yet. */
    @Synchronized fun firstRevisionForHorizon8521(episode: String, horizonMs: Long): Pair<String, String>? {
        if (episode.isEmpty() || episode.contains(REVISION_MARK_8977)) {   // not an indexable episode: the pre-8977 scan
            val prefix = episode + REVISION_MARK_8977
            return rows.entries.firstOrNull { it.key.startsWith(prefix) && horizonOf8977(it.key) == horizonMs }?.let { it.key to it.value }
        }
        val ids = revisions8977[episode] ?: return null
        for (id in ids) if (horizonOf8977(id) == horizonMs) return rows[id]?.let { id to it }
        return null
    }
    @Synchronized fun size(): Int = rows.size

    @Synchronized fun stage(id: String, payload: () -> String?, write: (String, String) -> Boolean): Boolean {
        if (!rows.containsKey(id)) {
            if (rows.size >= capacity) return false
            hold8977(id, payload() ?: return false)
        }
        if (id in durable) return true
        if (!write(id, rows.getValue(id))) return false
        durable.add(id)
        return true
    }

    @Synchronized fun stagePayload(id: String, payload: () -> String?, write: (String, String) -> Boolean): String? =
        if (stage(id, payload, write)) rows[id] else null

    /**
     * V5.0.8418 §OUTBOX_PER_OBSERVATION — 440f2009b stages every due horizon (15m/30m/1h/4h/24h) of every observation, so one
     * observation could hold five revision rows and the shared capacity starved first values. Before staging revision [keep]
     * of an episode, every older revision of it ([prefix]) that the consumer has NOT acknowledged is dropped (its newer value
     * replaces it; a revision replaces the contribution, it never adds one). An acknowledged row waits for its own removal.
     * Returns the rows dropped; [remove] deletes the durable copy first, and a row whose delete fails stays.
     * V5.0.8977 — a "<episode>|R|" prefix reads the episode's index; any other prefix still scans.
     */
    @Synchronized fun supersedeRevisions8418(prefix: String, keep: String, protectedHorizonMs: Long = 0L, remove: (String) -> Boolean): Int {
        if (prefix.isBlank()) return 0
        val candidates: List<String> = if (prefix.endsWith(REVISION_MARK_8977) && episodeOf8977(prefix) == prefix.dropLast(REVISION_MARK_8977.length))
            revisions8977[prefix.dropLast(REVISION_MARK_8977.length)]?.toList().orEmpty()
        else rows.keys.filter { it.startsWith(prefix) }
        var dropped = 0
        for (id in candidates) {
            if (id == keep || id in acknowledged) continue
            if (protectedHorizonMs > 0L && horizonOf8977(id) == protectedHorizonMs) continue
            if (id in durable && !remove(id)) continue
            drop8977(id); durable.remove(id); dropped++
        }
        return dropped
    }

    /** V5.0.8418 — rows of one episode (first value + revisions) currently held. */
    @Synchronized fun rowsFor8418(episode: String): Int = (if (rows.containsKey(episode)) 1 else 0) + (revisions8977[episode]?.size ?: 0)

    /** Work is bounded and rotates past failures; a failed delete never causes a second consumer mutation. */
    @Synchronized fun drain(limit: Int, write: (String, String) -> Boolean,
                            consume: (String) -> Boolean, remove: (String) -> Boolean) {
        val want = limit.coerceAtLeast(0)
        if (want == 0 || rows.isEmpty()) return
        // V5.0.8977 — the keys after the cursor, then from the start: the order the sorted copy gave, without the copy
        val batch = ArrayList<String>(minOf(want, rows.size))
        for (id in sorted8977.tailSet(cursor, false)) { if (batch.size >= want) break; batch.add(id) }
        if (batch.size < want) for (id in sorted8977.headSet(cursor, true)) { if (batch.size >= want) break; batch.add(id) }
        for (id in batch) {
            cursor = id
            val raw = rows[id] ?: continue
            try {
                if (id !in durable) {
                    if (!write(id, raw)) continue
                    durable.add(id)
                }
                if (id !in acknowledged) {
                    if (!consume(raw)) continue
                    acknowledged.add(id)
                }
                if (remove(id)) { drop8977(id); durable.remove(id); acknowledged.remove(id) }
            } catch (_: Exception) { /* retain the immutable value; a later tick or restart retries */ }
        }
    }
}
