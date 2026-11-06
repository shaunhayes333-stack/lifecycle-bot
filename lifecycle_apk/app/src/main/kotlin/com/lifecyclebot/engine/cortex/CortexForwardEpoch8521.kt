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
    const val PENDING = "CORTEX_PENDING_8521"
    const val GRADES = "CORTEX_GRADES_8521"
    const val BACKUP = "CORTEX_7885_V8521_BOOT"
    const val OLD_BOOKS = "CORTEX_7885_V8076"
    const val OLD_PENDING = "CORTEX_PENDING_7920"
    const val OLD_GRADES = "CORTEX_GRADES_8144"
    const val HIVE_PREFIX = "E8521|"
    const val HOUR_KIND = "FORWARD_HOUR_8521"
    private const val SCALP_KIND = "FORWARD_SCALP_8521"
    // V5.0.8590 — REVERTED 8588. 8588 moved the hour-lane grade target to 15 min to unstarve it, but matchesTarget requires
    // the observed mark's horizon to EXACTLY equal the target, and ForwardReturnLabeler7731 only produces marks at its fixed
    // grid — H60_MS_7731 = 5 min and H240_MS_7731 = 60 min (the constant NAMES are legacy slot ids, not minutes). A 15-minute
    // target matches NO produced mark, so 8588 would have graded hour lanes ZERO (worse than the sparse 60-min grading it
    // replaced). HOUR_MS must stay aligned to a mark the labeler actually emits (60 min). True 15m/30m grading requires
    // ADDING those checkpoints to the labeler's grid (done15/done30 marks) AND the matching targets together — a coordinated
    // labeler+epoch change, not a target-only flip. SCALP_MS (5 min) matches H60_MS_7731 and is unchanged.
    const val HOUR_MS = 60L * 60_000L
    const val SCALP_MS = 5L * 60_000L

    fun current(root: JSONObject): Boolean = root.optInt(FIELD, 0) == EPOCH

    data class StoredRows(val books: String?, val grades: String?, val pending: String?)

    /** All three rows belong to one checkpoint. A failed read is not an absent first-boot row. */
    fun readStoredRows(read: (String) -> String?, failed: (String) -> Boolean): StoredRows? {
        val books = read(BOOKS)
        val grades = read(GRADES)
        val pending = read(PENDING)
        if (listOf(BOOKS, GRADES, PENDING).any(failed)) return null
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
