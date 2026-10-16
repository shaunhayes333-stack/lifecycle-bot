package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * V5.0.8144 — the durable book of CONFIRMED partial proceeds the canonical ledger did not take (pure: no Android, no
 * clock; RecoveryFallbackLedger8141 owns one and persists [encode8144]).
 *
 * 8141 kept the legacy-fallback proceeds in two memory maps: a process death between a confirmed slice and the next
 * capital-recovery decision forgot them (the next tranche re-sold capital that had already come back), and the
 * seen-fill set was CLEARED at capacity, so a replayed signature could be counted a second time.
 *
 * Here every fill is one row keyed by its transaction signature (globally unique on chain), carrying the immutable
 * positionId, the token quantity it consumed and the actual SOL delta the verifier proved:
 *  - a signature is never counted twice: active rows are never evicted (a full book refuses new rows instead), and a
 *    retired or reconciled row's signature stays in a bounded dedup ring;
 *  - [reconcile8144]: a row whose signature the canonical economic events now carry (a later backfill / canonical
 *    commit of the same fill) leaves the fallback sum — canonical + fallback never counts one fill twice;
 *  - [retire8144]: a terminal position's rows leave the book, their signatures stay deduplicated.
 */
class ExitTruthProceedsBook8144(private val maxFills: Int = 4_096, private val maxRetired: Int = 8_192) {

    data class Fill8144(
        val positionId: String,
        val signature: String,
        val tokenQtyRaw: String,
        val solDelta: Double,
        val atMs: Long,
        val reconciled: Boolean = false,
    )

    enum class Note8144 { COUNTED, DUPLICATE_SIGNATURE, UNCONFIRMED, INVALID, RETIRED_POSITION, FULL }

    private val fills = LinkedHashMap<String, Fill8144>()          // signature -> fill, oldest first
    private val retiredSignatures = LinkedHashSet<String>()         // bounded dedup ring
    private val retiredPositions = LinkedHashSet<String>()          // bounded

    private fun ring(set: LinkedHashSet<String>, value: String) {
        set.add(value)
        while (set.size > maxRetired) { val oldest = set.iterator(); oldest.next(); oldest.remove() }
    }

    /**
     * A partial of [positionId] filled in [signature], consuming [tokenQtyRaw] for an actual SOL delta of [solDelta].
     * Counted only when [confirmed] (the verifier proved the landed delta), once per signature.
     */
    @Synchronized
    fun note8144(positionId: String, signature: String, tokenQtyRaw: java.math.BigInteger?, solDelta: Double, confirmed: Boolean, atMs: Long): Note8144 {
        if (!confirmed) return Note8144.UNCONFIRMED
        val sig = signature.trim()
        if (positionId.isBlank() || sig.isEmpty() || !solDelta.isFinite() || solDelta <= 0.0) return Note8144.INVALID
        if (fills.containsKey(sig) || sig in retiredSignatures) return Note8144.DUPLICATE_SIGNATURE
        if (positionId in retiredPositions) return Note8144.RETIRED_POSITION
        if (fills.size >= maxFills) {
            // Only rows the canonical book already carries may leave (their signature stays deduplicated).
            val out = fills.values.filter { it.reconciled }.take(fills.size - maxFills + 1)
            for (f in out) { fills.remove(f.signature); ring(retiredSignatures, f.signature) }
            if (fills.size >= maxFills) return Note8144.FULL
        }
        fills[sig] = Fill8144(positionId, sig, (tokenQtyRaw?.takeIf { it.signum() >= 0 } ?: java.math.BigInteger.ZERO).toString(), solDelta, atMs)
        return Note8144.COUNTED
    }

    /** Rows of [positionId] whose signature the canonical economic events carry are reconciled. Returns how many changed. */
    @Synchronized
    fun reconcile8144(positionId: String, canonicalSignatures: Set<String>): Int {
        if (canonicalSignatures.isEmpty()) return 0
        var n = 0
        for ((sig, f) in fills.entries.toList()) {
            if (f.positionId == positionId && !f.reconciled && sig in canonicalSignatures) { fills[sig] = f.copy(reconciled = true); n++ }
        }
        return n
    }

    /** The confirmed fallback proceeds of [positionId] the canonical book does NOT already carry. */
    @Synchronized
    fun unreconciledSol8144(positionId: String): Double =
        fills.values.filter { it.positionId == positionId && !it.reconciled }.sumOf { it.solDelta }

    @Synchronized
    fun hasFills8144(positionId: String): Boolean = fills.values.any { it.positionId == positionId }

    @Synchronized
    fun fillsOf8144(positionId: String): List<Fill8144> = fills.values.filter { it.positionId == positionId }

    @Synchronized
    fun size8144(): Int = fills.size

    @Synchronized
    fun counted8144(signature: String): Boolean = fills.containsKey(signature.trim()) || signature.trim() in retiredSignatures

    /** [positionId] is terminal: its rows leave the book; their signatures (and the position) stay deduplicated. */
    @Synchronized
    fun retire8144(positionId: String): Int {
        if (positionId.isBlank()) return 0
        val out = fills.values.filter { it.positionId == positionId }
        for (f in out) { fills.remove(f.signature); ring(retiredSignatures, f.signature) }
        ring(retiredPositions, positionId)
        return out.size
    }

    @Synchronized
    fun encode8144(): String {
        val rows = JSONArray()
        for (f in fills.values) {
            rows.put(JSONObject().apply {
                put("p", f.positionId); put("s", f.signature); put("q", f.tokenQtyRaw)
                put("sol", f.solDelta); put("at", f.atMs); put("r", f.reconciled)
            })
        }
        return JSONObject().apply {
            put("v", 1)
            put("fills", rows)
            put("retiredSigs", JSONArray(retiredSignatures.toList()))
            put("retiredPos", JSONArray(retiredPositions.toList()))
        }.toString()
    }

    /** Rebuild from [encode8144] text (a restart). Merges: a signature already present is kept as it is. Returns rows read. */
    @Synchronized
    fun restore8144(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        return try {
            val j = JSONObject(text)
            j.optJSONArray("retiredSigs")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { ring(retiredSignatures, it) } }
            j.optJSONArray("retiredPos")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { ring(retiredPositions, it) } }
            var n = 0
            val rows = j.optJSONArray("fills")
            if (rows != null) for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                val sig = o.optString("s").trim()
                val pid = o.optString("p")
                val sol = o.optDouble("sol", Double.NaN)
                if (sig.isEmpty() || pid.isBlank() || !sol.isFinite() || sol <= 0.0) continue
                if (fills.containsKey(sig) || sig in retiredSignatures) continue
                fills[sig] = Fill8144(pid, sig, o.optString("q", "0"), sol, o.optLong("at", 0L), o.optBoolean("r", false))
                n++
            }
            n
        } catch (_: Throwable) { 0 }
    }

    @Synchronized
    fun clear8144() { fills.clear(); retiredSignatures.clear(); retiredPositions.clear() }
}
