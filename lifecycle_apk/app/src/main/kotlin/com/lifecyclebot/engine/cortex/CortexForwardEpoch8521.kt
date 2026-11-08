package com.lifecyclebot.engine.cortex

import org.json.JSONArray
import org.json.JSONObject

/**
 * The old forward model mixed pre-horizon observations and inferred total losses with timed outcomes. Its aggregate
 * sums cannot identify which contributions were genuine. Keep those snapshots intact at their original keys while
 * starting the corrected forward contract separately. Canonical fills, closes and their bound predictions carry over.
 * This changes predictive evidence only; the native strategy engine and account risk controls retain their state.
 */
internal object CortexForwardEpoch8521 {
    const val EPOCH = 8521
    const val SCORE_VERSION = 2
    const val FIELD = "forwardEpoch8521"
    const val BOOKS = "CORTEX_7885_V8521"
    // V5.0.8644 — the unbounded 8622 voter checkpoint was 26.7M characters and crashed the
    // phone. Leave it untouched and start a fresh, sampled-prediction companion ledger.
    const val PENDING = "CORTEX_PENDING_8644"
    const val GRADES = "CORTEX_GRADES_8521"
    const val BACKUP = "CORTEX_7885_V8521_BOOT"
    const val OLD_BOOKS = "CORTEX_7885_V8076"
    const val OLD_PENDING = "CORTEX_PENDING_7920"
    const val OLD_GRADES = "CORTEX_GRADES_8144"
    const val HIVE_PREFIX = "E8521|"
    const val HOUR_KIND = "FORWARD_HOUR_8521"
    private const val SCALP_KIND = "FORWARD_SCALP_8521"
    // V5.0.8592 — THE COORDINATED 15-MIN HOUR TARGET (the change 8590 said 8588 lacked). 8588 moved HOUR_MS to 15 min alone;
    // the labeler still only booked/staged the hour entry at its 60-min read (completeHour8397), and its hour-target machinery
    // was gated on the literal `== H240_MS_7731` (60 min) in ~8 places, so a bare constant flip made every one of those gates
    // go false and graded hour lanes ZERO. 8592 does the coordinated version: HOUR_MS = 15 min AND the labeler now (a) fires
    // its hour completion at the per-obs targetHorizon8521 (15 min for new hour lanes, still 60 min for old persisted obs),
    // (b) replaces the hour-target `== H240_MS_7731` gates with the per-obs target so booking/staging/coalescing key off the
    // same horizon, (c) emits the revision proof at revisionHorizon8521 = CK_MS_7997[0] = 15 min, which matchesTarget now
    // accepts (horizon equality holds, window [+15m,+22.5m]). The cortex side is driven entirely by HOUR_MS already
    // (HOUR_GRADE_MIN_MS_8415, deferHour8415/matchesTarget), so it propagates with no cortex edit. 60 min was unreachable for
    // memecoins (they die first) — the grader produced ~0 closes/h and the whole adaptive stack sat starved at ADVISORY. The
    // single entry grade still contributes once at the frozen target; held runners keep being observed past it for exit
    // learning. SCALP_MS (5 min) matches H60_MS_7731 and is unchanged. Revert = flip HOUR_MS back to 60 min.
    const val HOUR_MS = 15L * 60_000L
    const val SCALP_MS = 5L * 60_000L

    fun current(root: JSONObject): Boolean = root.optInt(FIELD, 0) == EPOCH

    data class StoredRows(val books: String?, val grades: String?, val pending: String?)

    /** All three rows belong to one checkpoint. A failed read is not an absent first-boot row. */
    fun readStoredRows(read: (String) -> String?, failed: (String) -> Boolean): StoredRows? = readRows8623(read, failed, true)

    /** V5.0.8623 — the same read without the pending row (the retired voter graph's 26.7 M-char blob is never loaded). */
    fun readStoredBooks8623(read: (String) -> String?, failed: (String) -> Boolean): StoredRows? = readRows8623(read, failed, false)

    private fun readRows8623(read: (String) -> String?, failed: (String) -> Boolean, readPending: Boolean): StoredRows? {
        val books = read(BOOKS)
        val grades = read(GRADES)
        val pending = if (readPending) read(PENDING) else null
        if (listOf(BOOKS, GRADES).any(failed) || (readPending && failed(PENDING))) return null
        return StoredRows(books, grades, pending)
    }

    fun targetKind(lane: String): String = if (LaneGradeHorizon8406.hourGraded8406(lane)) HOUR_KIND else SCALP_KIND
    fun targetHorizon(kind: String): Long = if (kind == HOUR_KIND) HOUR_MS else SCALP_MS
    fun frozenTarget(kind: String): Boolean = kind == HOUR_KIND || kind == SCALP_KIND
    private fun targetGraceMs(horizonMs: Long): Long = (horizonMs / 2L).coerceIn(60_000L, 10L * 60_000L)
    fun targetDeadlineMs(kind: String, decisionAtMs: Long): Long =
        decisionAtMs + targetHorizon(kind) + targetGraceMs(targetHorizon(kind))

    /** An hour policy cannot learn the five-minute mark or have its hour target replaced by tomorrow's return. */
    fun matchesTarget(kind: String, decisionAtMs: Long, horizonMs: Long, observedAtMs: Long): Boolean =
        frozenTarget(kind) && horizonMs == targetHorizon(kind) &&
            decisionAtMs > 0L && observedAtMs in (decisionAtMs + targetHorizon(kind))..targetDeadlineMs(kind, decisionAtMs)

    /** No forward ledger, calibration, joint, veto or prior is allowed into the new aggregate from this bridge. */
    fun realisedBooks(old: JSONObject): JSONObject = JSONObject().put(FIELD, EPOCH).also { out ->
        old.optJSONObject("real")?.let { out.put("real", it) }
        old.optJSONObject("board")?.optJSONObject("_realized")?.let {
            out.put("board", JSONObject().put("_realized", it))
        }
        // A shadow exit remains an explicitly separate simulator result, never a forward authority label.
        old.optJSONObject("shadowLedger8418")?.let { out.put("shadowLedger8418", it) }
    }

    /** The wire rows are retained byte-for-byte; only canonical trade records cross the target boundary. */
    fun realisedGrades(raw: String?): String = raw.orEmpty().split('\u001E')
        .filter { it.substringBefore('\u001F') == "T" }.joinToString("\u001E")

    /** Preserve in-flight real position ownership while excluding old forward predictions and revision coordinates. */
    fun positionPending(old: JSONObject): JSONObject = JSONObject().put(FIELD, EPOCH)
        .put("dict", JSONArray()).put("p", JSONArray()).put("g8013", JSONArray()).also { out ->
            old.optJSONArray("bound8076")?.let { out.put("bound8076", it) }
            old.optJSONArray("seal8395")?.let { out.put("seal8395", it) }
        }
}
