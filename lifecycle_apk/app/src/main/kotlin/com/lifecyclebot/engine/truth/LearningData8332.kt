package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8332 §LEARNING_DELIVERY_AND_DATA_COVERAGE — the pure helpers behind four repairs the 5.0.8326 runtime audit asked for.
 *
 *  • [EntrySnapPersist8332] — a live entry's exact strategy snapshot is durable at seal time or queued until the store can take
 *    it (5.0.8326: 17 NO_ENTRY_SNAPSHOT refusals in the bridge history). The write is idempotent; the read tolerates a boot.
 *  • [GradeBind8332] — a finalised close's grade and its lifecycle record reconcile on one canonical key: the lane and the open
 *    time sealed in the entry snapshot (5.0.8326: realised binding 67%). Every unbound close names why.
 *  • [SpecialistDelivery8332] — the owner lane sealed in the entry snapshot delivers the close outcome to the specialist that
 *    bought (5.0.8326: specialist learning 3 missing) when the in-memory envelope and the owner ledger are both gone.
 *  • [CurveMark8332] / [IdentitySkew8332] — the mark repair reads an un-graduated pump coin's own curve (NO_FEED_ANSWERED
 *    342/1,312), and the price-vs-cap identity is compared only inside one write (METRICS_IDENTITY_BROKEN_7069 = 2,973).
 *
 * Nothing here invents a number: a snapshot is written by the fill that sealed it, a curve mark is the venue's own observed
 * reserves at their observed time, and a skew verdict passes the provider's price through unchanged with no gate feed.
 */
object EntrySnapPersist8332 {
    private val pending = ConcurrentHashMap<String, String>()   // positionId -> encoded snapshot awaiting a durable store
    private val written = AtomicLong(0L)
    private val readOk = AtomicLong(0L)
    private val missing = AtomicLong(0L)
    private val queued = AtomicLong(0L)
    private val flushed = AtomicLong(0L)

    /** Pure: a durable write is needed only when nothing identical is already stored (a re-seal of the same entry is a no-op). */
    fun needsWrite8332(storedRaw: String?, newRaw: String): Boolean = storedRaw == null || storedRaw != newRaw

    /** Pure: the snapshot is durable when the reset-proof copy took it or the open learning store did. */
    fun durable8332(prefsOk: Boolean, kvReady: Boolean): Boolean = prefsOk || kvReady

    fun noteWritten() { written.incrementAndGet() }
    fun noteReadOk() { readOk.incrementAndGet() }
    fun noteMissing() { missing.incrementAndGet() }

    /** A seal the store could not take yet (no context / store not open): kept for [flush8332], idempotent per position. */
    fun queue8332(positionId: String, raw: String) {
        if (positionId.isBlank() || raw.isBlank()) return
        if (pending.put(positionId, raw) == null) queued.incrementAndGet()
        try { PipelineHealthCollector.labelInc("ENTRY_SNAPSHOT_WRITE_QUEUED_8332") } catch (_: Throwable) {}
    }

    /** Retry every queued seal through [writer] (true = durable now); each one leaves the queue exactly once. Returns the count flushed. */
    fun flush8332(writer: (String, String) -> Boolean): Int {
        if (pending.isEmpty()) return 0
        var n = 0
        for ((pid, raw) in pending.entries.toList()) {
            val ok = try { writer(pid, raw) } catch (_: Throwable) { false }
            if (ok && pending.remove(pid, raw)) { n++; flushed.incrementAndGet(); written.incrementAndGet() }
        }
        if (n > 0) try { PipelineHealthCollector.labelInc("ENTRY_SNAPSHOT_WRITE_FLUSHED_8332") } catch (_: Throwable) {}
        return n
    }

    fun pendingCount8332(): Int = pending.size

    fun statusLine8332(): String =
        "entrySnap8332[written=${written.get()}, readOk=${readOk.get()}, missing=${missing.get()}, pending=${pending.size}, queued=${queued.get()}, flushed=${flushed.get()}]"

    internal fun resetForTest8332() {
        pending.clear(); written.set(0L); readOk.set(0L); missing.set(0L); queued.set(0L); flushed.set(0L)
    }
}

object GradeBind8332 {
    private val bound = AtomicLong(0L)
    private val unbound = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Pure: the canonical lane a close's grade binds under — the lane sealed in THIS position's entry snapshot (written at
     * the fill, same mint), else the sealed identity's owner lane, else the close's own lane. Always upper-cased, trimmed.
     */
    fun canonicalLane8332(snapLane: String?, snapMint: String?, sealedLane: String?, envMint: String, envLane: String): String = when {
        !snapLane.isNullOrBlank() && snapMint == envMint -> snapLane.trim().uppercase()
        !sealedLane.isNullOrBlank() -> sealedLane.trim().uppercase()
        else -> envLane.trim().uppercase()
    }

    /** Pure: the position's open time — the sealed entry timestamp when it exists and precedes the close, else close minus holding. */
    fun openedAt8332(snapEntryMs: Long?, closeAtMs: Long, holdingMs: Long): Long =
        if (snapEntryMs != null && snapEntryMs > 0L && snapEntryMs <= closeAtMs) snapEntryMs else closeAtMs - holdingMs.coerceAtLeast(0L)

    /** Pure: why a finalised close found no entry verdict. */
    fun unboundWhy8332(replay: Boolean, alreadyFiled: Boolean, hasSnapshot: Boolean, snapLaneDiffers: Boolean, hasSealed: Boolean): String = when {
        replay && alreadyFiled -> "REPLAY_FILED"
        replay -> "REPLAY_UNMATCHED"
        !hasSnapshot && !hasSealed -> "NO_ENTRY_IDENTITY"
        snapLaneDiffers -> "LANE_KEY_MISMATCH"
        else -> "NO_DECISION_RECORD"
    }

    fun noteBound8332() { bound.incrementAndGet() }
    fun noteUnbound8332(why: String) {
        unbound.computeIfAbsent(why) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("GRADE_UNBOUND_8332_$why") } catch (_: Throwable) {}
    }

    fun line8332(): String {
        val total = unbound.values.sumOf { it.get() }
        val why = unbound.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }
        return "gradeBind8332[bound=${bound.get()}, unbound=$total why{$why}]"
    }

    internal fun resetForTest8332() { bound.set(0L); unbound.clear() }
}

object SpecialistDelivery8332 {
    private val delivered = AtomicLong(0L)

    /** Pure: the owner lane a close trains — the BUY-pending ledger stamp first, else the entry snapshot's lane (same mint). */
    fun ownerLane8332(ledgerLane: String?, snapLane: String?, snapMint: String?, envMint: String): String? =
        ledgerLane?.takeIf { it.isNotBlank() } ?: snapLane?.takeIf { it.isNotBlank() && snapMint == envMint }

    /**
     * The owner lane sealed in the immutable entry snapshot of THIS position (same mint), else null. The snapshot was written
     * by the fill under the sealed intent's canonical lane, so it is an entry-time owner stamp, never a later inference.
     */
    fun sealedEntryLane8332(positionId: String, mint: String): String? {
        val s = try { EntryStrategySnapshot6450.snapshot(positionId) } catch (_: Throwable) { null } ?: return null
        val lane = ownerLane8332(null, s.entryLane, s.mint, mint) ?: return null
        delivered.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SPECIALIST_LEARNING_OWNER_FROM_ENTRY_SNAPSHOT_8332_${lane.uppercase()}") } catch (_: Throwable) {}
        return lane
    }

    fun statusLine8332(): String = "specialistDelivery8332[fromEntrySnapshot=${delivered.get()}]"
}

object CurveMark8332 {
    const val CURVE_MARK_MAX_AGE_MS_8332 = 60_000L
    const val SOURCE_8332 = "PUMP_CURVE_RESERVES_8332"

    data class Mark8332(val priceUsd: Double, val observedAtMs: Long)

    /** Pure: the curve is the coin's own venue only while it is an un-graduated pump curve read inside the age window. */
    fun curveIsVenue8332(graduated: Boolean, curveKnown: Boolean, readingAtMs: Long?, nowMs: Long): Boolean =
        !graduated && curveKnown && readingAtMs != null && nowMs - readingAtMs in 0L..CURVE_MARK_MAX_AGE_MS_8332

    /** Pure: a USD mark from a SOL-per-token curve price, or null when either side is not a positive finite number. */
    fun markUsd8332(priceSol: Double, solUsd: Double): Double? =
        if (priceSol.isFinite() && priceSol > 0.0 && solUsd.isFinite() && solUsd > 0.0) priceSol * solUsd else null

    /** The coin's own curve as the mark: its observed reserves at their observed time, or null when the curve is not its venue. */
    fun read8332(mint: String, solUsd: Double, nowMs: Long): Mark8332? {
        val bare = mint.removePrefix("solana|").trim()
        if (bare.isBlank()) return null
        val graduated = try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(bare) } catch (_: Throwable) { true }
        val known = try { CurveReserves7951.known7951(bare) } catch (_: Throwable) { false }
        val atMs = try { CurveReserves7951.readingAtMs8173(bare) } catch (_: Throwable) { null } ?: return null
        if (!curveIsVenue8332(graduated, known, atMs, nowMs)) return null
        val priceSol = try { CurveReserves7951.priceSolFor7951(bare, nowMs, CURVE_MARK_MAX_AGE_MS_8332) } catch (_: Throwable) { 0.0 }
        val px = markUsd8332(priceSol, solUsd) ?: return null
        return Mark8332(px, atMs)
    }
}

object IdentitySkew8332 {
    /** Two values changed inside this window belong to the same write and may be compared against each other. */
    const val SAME_WRITE_MS_8332 = 1_500L
    const val SKEW_RATIO_MIN_8332 = 0.5
    const val SKEW_RATIO_MAX_8332 = 2.0

    enum class Verdict8332 { HELD, SKEW, BROKEN }

    /**
     * Pure: price and cap/supply are an identity only inside one write. Values written at different times that disagree
     * within a market move ([SKEW_RATIO_MIN_8332]..[SKEW_RATIO_MAX_8332]) are SKEW: unverifiable, the price passes through,
     * no gate feed. A unit break (outside that band) is BROKEN whatever the timing, and so is any disagreement inside one write.
     */
    fun classify8332(ratio: Double, epsilon: Double, priceChangedAtMs: Long, capChangedAtMs: Long): Verdict8332 = when {
        !ratio.isFinite() || ratio <= 0.0 -> Verdict8332.BROKEN
        ratio in (1.0 - epsilon)..(1.0 + epsilon) -> Verdict8332.HELD
        kotlin.math.abs(priceChangedAtMs - capChangedAtMs) <= SAME_WRITE_MS_8332 -> Verdict8332.BROKEN
        ratio in SKEW_RATIO_MIN_8332..SKEW_RATIO_MAX_8332 -> Verdict8332.SKEW
        else -> Verdict8332.BROKEN
    }
}
