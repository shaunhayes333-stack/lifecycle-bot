package com.lifecyclebot.engine.cortex

/**
 * V5.0.7885 — Cortex §2.8 promotion: the versioned, visible bar the fused
 * verdict must clear before it gets a say.
 *
 * Every graded decision is filed by what the Cortex said AT DECISION TIME
 * (prequential: the fused edge stored when the candidate was seen, never
 * recomputed with hindsight):
 *
 *   REFUSE  fused net edge < [REFUSE_EDGE_PCT] (runner lanes: and runner rate < 10%)
 *   STRONG  fused net edge > [STRONG_EDGE_PCT]
 *   NEUTRAL otherwise
 *
 * and, beside it, by what the legacy stack decided (admitted / refused).
 *
 * BAR V1, per lane:
 *   refusal authority  REFUSE n >= minN, mean + 1 SE < -2%, at least 2% worse
 *                      than the lane's non-REFUSE decisions, and (runner
 *                      lanes) runner rate < 10%. minN = 20 paper, 40 live.
 *   overrule authority STRONG n >= 40 and mean - 1 SE > +2%: the Cortex may
 *                      admit a live candidate the edge gate refused (never a
 *                      safety refusal).
 * Both are re-evaluated on every read, so authority is lost as soon as the
 * record stops supporting it.
 */
class CortexScoreboard7885 {
    enum class Bucket { REFUSE, NEUTRAL, STRONG }

    companion object {
        const val BAR_VERSION = "CORTEX_BAR_V1"
        const val REFUSE_EDGE_PCT = -2.0
        const val STRONG_EDGE_PCT = 3.0
        const val PROOF_MARGIN_PCT = 2.0
        const val MIN_N_PAPER = 20
        const val MIN_N_LIVE = 40
        const val TAIL_RUNNER_RATE = 0.10
        const val BOOK_DECAY = 0.997

        /** Pure: where a fused verdict falls. */
        fun bucketOf(edgePct: Double, runnerRate: Double, runnerLane: Boolean): Bucket = when {
            !edgePct.isFinite() -> Bucket.NEUTRAL
            edgePct < REFUSE_EDGE_PCT && !(runnerLane && runnerRate >= TAIL_RUNNER_RATE) -> Bucket.REFUSE
            edgePct > STRONG_EDGE_PCT -> Bucket.STRONG
            else -> Bucket.NEUTRAL
        }

        private fun se(s: CortexLedger7885.Stat): Double =
            if (s.n > 1.0) kotlin.math.sqrt(s.variance() / s.n) else Double.POSITIVE_INFINITY

        /** Pure: does the REFUSE record prove refusing pays? */
        fun refuseProven(refuse: CortexLedger7885.Stat, rest: CortexLedger7885.Stat, runnerLane: Boolean, minN: Int): Boolean {
            if (refuse.n < minN) return false
            if (refuse.mean() + se(refuse) >= -PROOF_MARGIN_PCT) return false
            if (rest.n >= minN && refuse.mean() >= rest.mean() - PROOF_MARGIN_PCT) return false
            if (runnerLane && refuse.runnerRate() >= TAIL_RUNNER_RATE) return false
            return true
        }

        /** Pure: does the STRONG record prove the Cortex finds edge the gates miss? */
        fun overruleProven(strong: CortexLedger7885.Stat): Boolean =
            strong.n >= MIN_N_LIVE && strong.mean() - se(strong) > PROOF_MARGIN_PCT
    }

    class Book {
        val byBucket = Array(Bucket.values().size) { CortexLedger7885.Stat() }
        val legacyAdmitted = CortexLedger7885.Stat()
        val legacyRefused = CortexLedger7885.Stat()
        /** Legacy refused, Cortex STRONG — the trades an overrule would have added. */
        val missedStrong = CortexLedger7885.Stat()

        fun all(): List<CortexLedger7885.Stat> = byBucket.toList() + listOf(legacyAdmitted, legacyRefused, missedStrong)

        fun rest(): CortexLedger7885.Stat = CortexLedger7885.Stat().also { r ->
            for (b in listOf(Bucket.NEUTRAL, Bucket.STRONG)) {
                val s = byBucket[b.ordinal]; r.n += s.n; r.sum += s.sum; r.sumSq += s.sumSq; r.runners += s.runners
            }
        }

        fun encode(): String = (byBucket.map { it.encode() } + listOf(legacyAdmitted.encode(), legacyRefused.encode(), missedStrong.encode())).joinToString("|")
        fun decode(s: String) {
            val f = s.split('|'); if (f.size != byBucket.size + 3) return
            byBucket.forEachIndexed { i, st -> st.decode(f[i]) }
            legacyAdmitted.decode(f[byBucket.size]); legacyRefused.decode(f[byBucket.size + 1]); missedStrong.decode(f[byBucket.size + 2])
        }
    }

    val books = HashMap<String, Book>()

    fun record(lane: String, bucket: Bucket, legacyAdmitted: Boolean, netPct: Double, grossPct: Double) {
        if (!netPct.isFinite()) return
        val y = netPct.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX)
        val runner = grossPct.isFinite() && grossPct >= CortexLedger7885.RUNNER_GROSS_PCT
        val b = books.getOrPut(lane) { Book() }
        // Decayed memory (~330 decisions): authority earned in an old regime fades
        // unless the recent record keeps supporting it (v1 §2.8 automatic demotion).
        for (st in b.all()) st.scale(BOOK_DECAY)
        b.byBucket[bucket.ordinal].add(y, runner)
        if (legacyAdmitted) b.legacyAdmitted.add(y, runner) else b.legacyRefused.add(y, runner)
        if (!legacyAdmitted && bucket == Bucket.STRONG) b.missedStrong.add(y, runner)
    }

    fun refusalAuthority(lane: String, runnerLane: Boolean, paper: Boolean): Boolean {
        val b = books[lane] ?: return false
        return refuseProven(b.byBucket[Bucket.REFUSE.ordinal], b.rest(), runnerLane, if (paper) MIN_N_PAPER else MIN_N_LIVE)
    }

    fun overruleAuthority(lane: String): Boolean {
        val b = books[lane] ?: return false
        return overruleProven(b.byBucket[Bucket.STRONG.ordinal])
    }

    /**
     * OutcomeTruth cross-check (v1 §2.7): the whole-position realised return of
     * every canonical close, filed by the Cortex verdict at its entry, per mode.
     * Forward labels grade selection; this shows whether that selection survives
     * real fills and real exits.
     */
    val realized = HashMap<String, Array<CortexLedger7885.Stat>>()   // MODE|lane

    fun recordRealized(mode: String, lane: String, bucket: Bucket, returnPct: Double) {
        if (!returnPct.isFinite()) return
        val a = realized.getOrPut("${mode.uppercase()}|$lane") { Array(Bucket.values().size) { CortexLedger7885.Stat() } }
        a[bucket.ordinal].add(returnPct.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), returnPct >= CortexLedger7885.RUNNER_GROSS_PCT)
    }

    fun encode(): org.json.JSONObject = org.json.JSONObject().also { o ->
        books.forEach { (k, v) -> o.put(k, v.encode()) }
        o.put("_realized", org.json.JSONObject().also { j -> realized.forEach { (k, v) -> j.put(k, v.joinToString("|") { it.encode() }) } })
    }

    fun decode(o: org.json.JSONObject) {
        for (k in o.keys()) {
            if (k == "_realized") continue
            books[k] = Book().also { it.decode(o.optString(k)) }
        }
        o.optJSONObject("_realized")?.let { j ->
            for (k in j.keys()) {
                val f = j.optString(k).split('|')
                if (f.size != Bucket.values().size) continue
                realized[k] = Array(f.size) { i -> CortexLedger7885.Stat().also { it.decode(f[i]) } }
            }
        }
    }
}
