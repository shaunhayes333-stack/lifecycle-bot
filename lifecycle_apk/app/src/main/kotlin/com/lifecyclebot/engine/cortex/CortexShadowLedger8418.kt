package com.lifecyclebot.engine.cortex

import org.json.JSONObject

/**
 * V5.0.8418 §SHADOW_LEDGER_ISOLATED — the ONLY book a model-filled shadow exit (ExploreShadow8347 -> Cortex7885.onShadowOutcome8372)
 * teaches. 8372 / 8412 graded those exits into the live voter seats (ledger.grade), the bucket books (board.record), the
 * calibration ring (calibration.learn), the grade book (shadowOutcome8372: authority test8372 and the Kelly bucket stats) and the
 * playbook's setup records (reviseEpisode8372). A shadow result then admitted, refused and sized live trades. This book is read
 * by the report only: assess(), every authority / sizing / admission reader and the playbook never read it.
 *
 * Keyed "lane|bucket" and "lane|bucket|executable" (executable = the original candidate had an executable context). Pure;
 * the caller owns the lock. Persisted inside the Cortex snapshot (key "shadowLedger8418"); a missing or malformed row loads empty.
 */
class CortexShadowLedger8418(private val maxKeys: Int = 512) {
    /** n, sum of net %, wins (net > 0), runners (gross >= [CortexLedger7885.RUNNER_GROSS_PCT]). Never decayed, never capped. */
    class Row8418(var n: Long = 0L, var sumNet: Double = 0.0, var wins: Long = 0L, var runners: Long = 0L) {
        fun mean(): Double = if (n > 0L) sumNet / n else Double.NaN
    }

    private val rows = LinkedHashMap<String, Row8418>()
    var recorded: Long = 0L
        private set

    /** Book one finished shadow exit. Non-finite values teach nothing (unknown stays unknown). */
    fun record(lane: String, bucket: String, executable: Boolean, netPct: Double, grossPct: Double): Boolean {
        if (lane.isBlank() || !netPct.isFinite() || !grossPct.isFinite()) return false
        val b = bucket.ifBlank { "UNKNOWN" }
        for (key in listOf("$lane|$b", "$lane|$b|${if (executable) "EXEC" else "NONEXEC"}")) {
            val r = rows[key] ?: run {
                if (rows.size >= maxKeys) return@run null
                Row8418().also { rows[key] = it }
            } ?: continue
            r.n += 1L; r.sumNet += netPct
            if (netPct > 0.0) r.wins += 1L
            if (grossPct >= CortexLedger7885.RUNNER_GROSS_PCT) r.runners += 1L
        }
        recorded++
        return true
    }

    fun row(key: String): Row8418? = rows[key]

    fun size(): Int = rows.size

    fun encode(): JSONObject = JSONObject().also { o ->
        o.put("recorded", recorded)
        rows.forEach { (k, r) -> o.put(k, "${r.n},${r.sumNet},${r.wins},${r.runners}") }
    }

    fun decode(o: JSONObject?) {
        rows.clear(); recorded = 0L
        if (o == null) return
        recorded = o.optLong("recorded", 0L)
        for (k in o.keys()) {
            if (k == "recorded") continue
            val f = o.optString(k).split(',')
            if (f.size != 4) continue
            val n = f[0].toLongOrNull() ?: continue
            val s = f[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
            if (n <= 0L || rows.size >= maxKeys) continue
            rows[k] = Row8418(n, s, f[2].toLongOrNull() ?: 0L, f[3].toLongOrNull() ?: 0L)
        }
    }

    fun line(): String = "shadowLedger8418[recorded=$recorded " + rows.entries.filter { it.key.count { c -> c == '|' } == 1 }
        .sortedByDescending { it.value.n }.take(12)
        .joinToString(" ") { (k, r) -> "$k:n${r.n}/${"%+.1f".format(r.mean())}%" }.ifBlank { "-" } + "]"
}
