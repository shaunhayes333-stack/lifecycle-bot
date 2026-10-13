package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.LearningPersistence
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8082 §OBSERVED_EXCURSION. Per-position maximum favourable / adverse excursion, observed only from
 * marks the peak authority accepts (PositionDisplayTruth8078.ratchetPeak8080: the chain/route-verified
 * EXIT_ECONOMIC mark for LIVE, the paper market price for PAPER). The canonical terminal carries these into
 * every learner. A position that was never observed reports NaN (unknown), never 0 and never its final return.
 * Persisted per position (debounced) so a restart mid-hold keeps the path; released once the terminal is durable.
 */
object PositionExcursion8082 {
    data class Excursion(val mfePct: Double, val maePct: Double, val mfeAtMs: Long, val maeAtMs: Long, val samples: Int, val source: String)

    private val book = ConcurrentHashMap<String, Excursion>()
    private val lastSaveMs = ConcurrentHashMap<String, Long>()
    private const val SAVE_DEBOUNCE_MS = 5_000L
    private const val MAX_TRACKED = 2_000
    private fun key(positionId: String) = "position_excursion_8082_$positionId"

    fun observe(positionId: String, gainPct: Double, source: String, nowMs: Long = System.currentTimeMillis()) {
        if (positionId.isBlank() || !gainPct.isFinite()) return
        if (book.size >= MAX_TRACKED && !book.containsKey(positionId)) return
        val prev = book[positionId] ?: restore(positionId)
        val next = if (prev == null) Excursion(gainPct, gainPct, nowMs, nowMs, 1, source) else Excursion(
            mfePct = maxOf(prev.mfePct, gainPct), maePct = minOf(prev.maePct, gainPct),
            mfeAtMs = if (gainPct > prev.mfePct) nowMs else prev.mfeAtMs,
            maeAtMs = if (gainPct < prev.maePct) nowMs else prev.maeAtMs,
            samples = prev.samples + 1, source = if (gainPct > prev.mfePct) source else prev.source,
        )
        book[positionId] = next
        val extreme = prev == null || next.mfePct != prev.mfePct || next.maePct != prev.maePct
        if (extreme && nowMs - (lastSaveMs[positionId] ?: 0L) >= SAVE_DEBOUNCE_MS) {
            lastSaveMs[positionId] = nowMs
            try {
                LearningPersistence.save(key(positionId), JSONObject().put("mfe", next.mfePct).put("mae", next.maePct)
                    .put("mfeAt", next.mfeAtMs).put("maeAt", next.maeAtMs).put("n", next.samples).put("src", next.source).toString())
            } catch (_: Throwable) {}
        }
    }

    fun forClose(positionId: String): Excursion? = if (positionId.isBlank()) null else book[positionId] ?: restore(positionId)
    fun mfeFor(positionId: String): Double = forClose(positionId)?.mfePct ?: Double.NaN
    fun maeFor(positionId: String): Double = forClose(positionId)?.maePct ?: Double.NaN

    /** The terminal is durable (CanonicalFinalityPersistence6486 carries mfe/mae): drop the hold-path state. */
    fun release(positionId: String) {
        if (positionId.isBlank()) return
        book.remove(positionId); lastSaveMs.remove(positionId)
        try { LearningPersistence.save(key(positionId), "") } catch (_: Throwable) {}
    }

    private fun restore(positionId: String): Excursion? = try {
        val raw = LearningPersistence.load(key(positionId))
        if (raw.isNullOrBlank()) null else JSONObject(raw).let { j ->
            val mfe = j.optDouble("mfe", Double.NaN); val mae = j.optDouble("mae", Double.NaN)
            if (!mfe.isFinite() || !mae.isFinite()) null
            else Excursion(mfe, mae, j.optLong("mfeAt"), j.optLong("maeAt"), j.optInt("n"), j.optString("src")).also { book[positionId] = it }
        }
    } catch (_: Throwable) { null }

    internal fun clearForTest() { book.clear(); lastSaveMs.clear() }
}
