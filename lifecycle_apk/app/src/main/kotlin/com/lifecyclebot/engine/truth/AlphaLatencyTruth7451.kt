package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/** V5.0.7451 — end-to-end entry latency truth. */
object AlphaLatencyTruth7451 {
    data class Snapshot(
        val mint: String,
        val birthMs: Long?,
        val firstObservedMs: Long,
        val intakeMs: Long,
        val fdgMs: Long,
        val submitMs: Long,
        val confirmedMs: Long,
    ) {
        val birthToObservationMs: Long? get() =
            birthMs?.takeIf { it > 0L && firstObservedMs >= it }?.let { firstObservedMs - it }
        val intakeToFdgMs: Long? get() = if (fdgMs >= intakeMs && intakeMs > 0L) fdgMs - intakeMs else null
        val intakeToSubmitMs: Long? get() = if (submitMs >= intakeMs && intakeMs > 0L) submitMs - intakeMs else null
        val intakeToConfirmedMs: Long? get() = if (confirmedMs >= intakeMs && intakeMs > 0L) confirmedMs - intakeMs else null
    }

    private data class Mutable(
        var birthMs: Long? = null,
        var firstObservedMs: Long = 0L,
        var intakeMs: Long = 0L,
        var lastIntakeTouchMs: Long = 0L,
        var fdgMs: Long = 0L,
        var submitMs: Long = 0L,
        var confirmedMs: Long = 0L,
    )

    private const val BURST_GAP_MS = 15_000L
    private const val MAX_ROWS = 12_000
    private val rows = ConcurrentHashMap<String, Mutable>()
    private fun key(raw: String): String = raw.trim()

    fun markIntake(mint: String, nowMs: Long = System.currentTimeMillis()) {
        val k = key(mint)
        if (k.isBlank()) return
        val r = rows.computeIfAbsent(k) { Mutable() }
        synchronized(r) {
            if (r.firstObservedMs <= 0L) {
                r.firstObservedMs = nowMs
                r.birthMs = try { CanonicalTokenBirthTime7440.resolve(k, nowMs)?.birthMs } catch (_: Throwable) { null }
                try { PipelineHealthCollector.labelInc("ALPHA_FIRST_OBSERVED_7451") } catch (_: Throwable) {}
            }
            if (r.intakeMs <= 0L || nowMs - r.lastIntakeTouchMs > BURST_GAP_MS || r.confirmedMs > 0L) {
                r.intakeMs = nowMs
                r.fdgMs = 0L
                r.submitMs = 0L
                r.confirmedMs = 0L
            }
            r.lastIntakeTouchMs = nowMs
        }
        trim(nowMs)
    }

    fun markFdg(mint: String, nowMs: Long = System.currentTimeMillis()) {
        val r = rows[key(mint)] ?: return
        synchronized(r) { if (r.intakeMs > 0L && r.fdgMs <= 0L) r.fdgMs = nowMs }
    }

    fun markSubmit(assetId: String, nowMs: Long = System.currentTimeMillis()) {
        val k = key(assetId)
        if (k.isBlank()) return
        val r = rows.computeIfAbsent(k) { Mutable(firstObservedMs = nowMs, intakeMs = nowMs, lastIntakeTouchMs = nowMs) }
        synchronized(r) { if (r.submitMs <= 0L) r.submitMs = nowMs }
    }

    fun markConfirmed(assetId: String, nowMs: Long = System.currentTimeMillis()) {
        val r = rows[key(assetId)] ?: return
        synchronized(r) {
            if (r.confirmedMs <= 0L) r.confirmedMs = nowMs
            if (r.confirmedMs >= r.intakeMs && r.intakeMs > 0L) {
                val ms = r.confirmedMs - r.intakeMs
                try {
                    PipelineHealthCollector.labelInc("ALPHA_CONFIRMED_7451")
                    PipelineHealthCollector.labelInc("ALPHA_INTAKE_TO_OPEN_" + bucket(ms))
                } catch (_: Throwable) {}
            }
            val birth = r.birthMs
            if (birth != null && r.firstObservedMs >= birth) {
                val late = r.firstObservedMs - birth
                try { PipelineHealthCollector.labelInc("ALPHA_BIRTH_TO_DISCOVERY_" + bucket(late)) } catch (_: Throwable) {}
            }
        }
    }

    fun candidateLatencyMs(mint: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val r = rows[key(mint)] ?: return null
        val start = synchronized(r) { r.intakeMs }
        if (start <= 0L || nowMs < start) return null
        return nowMs - start
    }

    fun snapshot(mint: String): Snapshot? {
        val k = key(mint)
        val r = rows[k] ?: return null
        return synchronized(r) { Snapshot(k, r.birthMs, r.firstObservedMs, r.intakeMs, r.fdgMs, r.submitMs, r.confirmedMs) }
    }

    fun operationalLatencyMs(mint: String, lastPriceUpdateMs: Long, nowMs: Long = System.currentTimeMillis()): Long {
        candidateLatencyMs(mint, nowMs)?.takeIf { it in 0L..60_000L }?.let { return it }
        return if (lastPriceUpdateMs > 0L && nowMs >= lastPriceUpdateMs)
            (nowMs - lastPriceUpdateMs).coerceAtMost(60_000L)
        else 10_000L
    }

    fun feedHealthy(lastPriceUpdateMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastPriceUpdateMs > 0L && nowMs >= lastPriceUpdateMs && nowMs - lastPriceUpdateMs <= 10_000L

    private fun bucket(ms: Long): String = when {
        ms <= 1_000L -> "LE1S"
        ms <= 3_000L -> "LE3S"
        ms <= 5_000L -> "LE5S"
        ms <= 10_000L -> "LE10S"
        ms <= 20_000L -> "LE20S"
        ms <= 30_000L -> "LE30S"
        else -> "GT30S"
    }

    private fun trim(nowMs: Long) {
        if (rows.size <= MAX_ROWS) return
        val cutoff = nowMs - 30L * 60_000L
        try {
            rows.entries.removeIf { (_, r) ->
                synchronized(r) { maxOf(r.lastIntakeTouchMs, r.confirmedMs, r.firstObservedMs) < cutoff }
            }
        } catch (_: Throwable) {}
        if (rows.size > MAX_ROWS) rows.keys.take(rows.size - MAX_ROWS).forEach { rows.remove(it) }
    }
}
