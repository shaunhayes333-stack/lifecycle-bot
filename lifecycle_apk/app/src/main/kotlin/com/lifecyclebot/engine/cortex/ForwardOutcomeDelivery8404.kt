package com.lifecyclebot.engine.cortex

import org.json.JSONArray
import org.json.JSONObject

/** A delivered value owns its original prediction and observation proof, not a later token read. */
data class ForwardOutcomeDelivery8404(
    val mint: String, val lane: String, val atMs: Long, val net: Double, val gross: Double,
    val family: DoubleArray?, val flat: Boolean, val witness: Boolean,
    val proof: CortexOutcomeProof8395?, val frozenPrediction: String,
    val revision: Boolean = false, val previousNet: Double = net, val previousGross: Double = gross,
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
            .put("previousNet", previousNet).put("previousGross", previousGross).toString()
    }
    companion object {
        fun decode(raw: String): ForwardOutcomeDelivery8404 {
            val j = JSONObject(raw); require(j.getInt("v") == 1)
            val family = j.optJSONArray("family")?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            return ForwardOutcomeDelivery8404(j.getString("mint"), j.getString("lane"), j.getLong("at"),
                j.getDouble("net"), j.getDouble("gross"), family, j.getBoolean("flat"), j.getBoolean("witness"),
                j.optString("proof").takeIf { it.isNotBlank() }?.let { requireNotNull(CortexOutcomeProof8395.decode8395(it)) },
                j.getString("prediction"), j.optBoolean("revision"), j.optDouble("previousNet", j.getDouble("net")),
                j.optDouble("previousGross", j.getDouble("gross"))).also {
                require(it.mint.isNotBlank() && it.lane.isNotBlank() && it.atMs > 0 && it.net.isFinite() && it.gross.isFinite())
                require(it.family == null || it.family.all { value -> value.isFinite() })
                require(it.proof == null || it.proof.decisionId == it.id)
                require(!it.revision || (it.proof != null && it.previousNet.isFinite() && it.previousGross.isFinite()))
            }
        }
    }
}

/** Bounded first-value outbox. Storage ownership and consumer acknowledgement are separate states. */
internal class ForwardOutcomeOutbox8404(private val capacity: Int) {
    private val rows = LinkedHashMap<String, String>()
    private val durable = HashSet<String>()
    private val acknowledged = HashSet<String>()
    private var cursor = ""

    @Synchronized fun restore(saved: Map<String, String>) {
        saved.forEach { (id, raw) -> rows[id] = raw; durable.add(id) }
    }
    @Synchronized fun contains(id: String): Boolean = rows.containsKey(id)
    @Synchronized fun payload(id: String): String? = rows[id]
    @Synchronized fun size(): Int = rows.size

    @Synchronized fun stage(id: String, payload: () -> String?, write: (String, String) -> Boolean): Boolean {
        if (!rows.containsKey(id)) {
            if (rows.size >= capacity) return false
            rows[id] = payload() ?: return false
        }
        if (id in durable) return true
        if (!write(id, rows.getValue(id))) return false
        durable.add(id)
        return true
    }

    @Synchronized fun stagePayload(id: String, payload: () -> String?, write: (String, String) -> Boolean): String? =
        if (stage(id, payload, write)) rows[id] else null

    /** Work is bounded and rotates past failures; a failed delete never causes a second consumer mutation. */
    @Synchronized fun drain(limit: Int, write: (String, String) -> Boolean,
                            consume: (String) -> Boolean, remove: (String) -> Boolean) {
        val keys = rows.keys.sorted()
        val start = keys.indexOfFirst { it > cursor }.takeIf { it >= 0 } ?: 0
        val batch = (keys.drop(start) + keys.take(start)).take(limit.coerceAtLeast(0))
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
                if (remove(id)) { rows.remove(id); durable.remove(id); acknowledged.remove(id) }
            } catch (_: Exception) { /* retain the immutable value; a later tick or restart retries */ }
        }
    }
}
