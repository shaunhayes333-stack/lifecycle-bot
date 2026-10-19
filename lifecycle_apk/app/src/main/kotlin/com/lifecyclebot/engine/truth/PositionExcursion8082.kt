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
    data class Excursion(val mfePct: Double, val maePct: Double, val mfeAtMs: Long, val maeAtMs: Long, val samples: Int, val source: String, val revision8239: Long = 0L)

    private val book = ConcurrentHashMap<String, Excursion>()
    private val lastSaveMs = ConcurrentHashMap<String, Long>()
    private const val SAVE_DEBOUNCE_MS = 5_000L
    private const val MAX_TRACKED = 2_000
    private fun key(positionId: String) = "position_excursion_8082_$positionId"

    fun observe(positionId: String, gainPct: Double, source: String, nowMs: Long = System.currentTimeMillis()) {
        if (positionId.isBlank() || !gainPct.isFinite() || nowMs <= 0L) return
        if (book.size >= MAX_TRACKED && !book.containsKey(positionId)) return
        val prev = book[positionId] ?: restore(positionId)
        // Re-reading the same cached price is not another observation or another peak clock.
        if (prev != null && nowMs == prev.mfeAtMs && gainPct == prev.mfePct) return
        val next = if (prev == null) Excursion(gainPct, gainPct, nowMs, nowMs, 1, source) else Excursion(
            mfePct = if (prev.mfePct.isFinite()) maxOf(prev.mfePct, gainPct) else gainPct,
            maePct = if (prev.maePct.isFinite()) minOf(prev.maePct, gainPct) else gainPct,
            mfeAtMs = if (!prev.mfePct.isFinite() || gainPct > prev.mfePct) nowMs else prev.mfeAtMs,
            maeAtMs = if (!prev.maePct.isFinite() || gainPct < prev.maePct) nowMs else prev.maeAtMs,
            samples = prev.samples + 1, source = if (!prev.mfePct.isFinite() || gainPct > prev.mfePct) source else prev.source,
            revision8239 = prev.revision8239,
        )
        book[positionId] = next
        val extreme = prev == null || next.mfePct != prev.mfePct || next.maePct != prev.maePct
        if (extreme && nowMs - (lastSaveMs[positionId] ?: 0L) >= SAVE_DEBOUNCE_MS) {
            lastSaveMs[positionId] = nowMs
            persist8239(positionId, next)
        }
    }

    private fun persist8239(positionId: String, e: Excursion) {
        try { LearningPersistence.save(key(positionId), JSONObject()
            .put("mfe", e.mfePct.takeIf { it.isFinite() } ?: JSONObject.NULL)
            .put("mae", e.maePct.takeIf { it.isFinite() } ?: JSONObject.NULL)
            .put("mfeAt", e.mfeAtMs).put("maeAt", e.maeAtMs).put("n", e.samples).put("src", e.source)
            .put("revision8239", e.revision8239).toString()) } catch (_: Throwable) {}
    }

    /** Proven bad observation, not a lower new quote: remove its authority durably and notify dependent runner books. */
    @Synchronized
    fun invalidateObservation8239(positionId: String, mint: String, observedAtMs: Long, reason: String): Boolean {
        if (positionId.isBlank() || reason.isBlank() || observedAtMs <= 0L) return false
        val previous = forClose(positionId)
        var changed = false
        if (previous != null) {
            val mfeBad = RunnerEvidence8239.invalidates(previous.mfeAtMs, observedAtMs)
            val maeBad = RunnerEvidence8239.invalidates(previous.maeAtMs, observedAtMs)
            if (mfeBad || maeBad) {
                val next = previous.copy(mfePct = if (mfeBad) Double.NaN else previous.mfePct,
                    maePct = if (maeBad) Double.NaN else previous.maePct, source = "INVALIDATED_8239:${reason.take(100)}",
                    revision8239 = previous.revision8239 + 1L)
                book[positionId] = next; persist8239(positionId, next); changed = true
            }
        }
        try { com.lifecyclebot.engine.RunnerLive8153.invalidatePeakEvidence8239(positionId, mint, observedAtMs, reason) } catch (_: Throwable) {}
        return changed
    }

    @Synchronized
    fun rebaseEntry8239(positionId: String, oldEntryUsd: Double, newEntryUsd: Double): Boolean {
        if (oldEntryUsd == newEntryUsd || oldEntryUsd <= 0.0 || newEntryUsd <= 0.0) return false
        val p = forClose(positionId) ?: return false
        val next = p.copy(mfePct = RunnerEvidence8239.rebaseReturn(p.mfePct, oldEntryUsd, newEntryUsd),
            maePct = RunnerEvidence8239.rebaseReturn(p.maePct, oldEntryUsd, newEntryUsd), revision8239 = p.revision8239 + 1L)
        book[positionId] = next; persist8239(positionId, next)
        return true
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
            if ((!mfe.isFinite() || !mae.isFinite()) && j.optLong("revision8239", 0L) <= 0L) null
            else Excursion(mfe, mae, j.optLong("mfeAt"), j.optLong("maeAt"), j.optInt("n"), j.optString("src"), j.optLong("revision8239", 0L)).also { book[positionId] = it }
        }
    } catch (_: Throwable) { null }

    internal fun clearForTest() { book.clear(); lastSaveMs.clear() }
}
