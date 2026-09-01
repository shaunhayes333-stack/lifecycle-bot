package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6634 §LOCKED_ENTRY_METRICS_AUTHORITY.
 *
 * OPERATOR DIRECTIVE (verbatim, Feb 2026):
 *   > "This bullshit with the token pricing needs to be sorted once
 *   >  and for all.  It arrives with all the information at time of
 *   >  discovery.  Then metrics should be locked in at time of buy.
 *   >  The only thing that changes, changes, is the price, holders
 *   >  etc.  There should not be continual issues with token price.
 *   >  We have multiple token data metrics price etc etc sources —
 *   >  it should be locked in."
 *
 * FORENSIC EVIDENCE (fresh Feb 2026 screenshot, PAPER 2.403◎ at risk):
 *   MACRODUCK / LEGO / fone / CLAWCORP / CROAK / TOESCOIN / BUNK /
 *   catfish all rendering:
 *       "Entry: INVARIANT_BROKEN_6500"
 *       "Size: 0.0500◎ · qty INVALID (invariant broken)"
 *       "+1.9% / +1.2% / +0.4% / +0.1% ... unrealized"
 *   at ~5-30s after entry.  The UI decided qty × entryPrice ≠ costSol×
 *   solPrice and stamped INVARIANT_BROKEN AFTER the buy had already
 *   committed — because the entry-time metrics were being re-derived
 *   from a later mark, decimals, or price snapshot.
 *
 * DESIGN
 * ──────
 * At the moment a paper BUY commits (executor / paper-open / carry
 * replay), the caller invokes `lockAtBuy6634(positionId, ...)` with
 * the ONE canonical snapshot of the entry-time metrics that arrived at
 * discovery:
 *   entryPriceUsd, entryPriceSol, entryCostSol, qtyRaw, qtyTokens,
 *   tokenDecimals, quantityScale, entryPriceSource, solUsdAtEntry.
 *
 * From that moment forward:
 *   `read6634(positionId)`  → returns the locked snapshot verbatim.
 *   `assertLocked6634(positionId, field, currentValue)` → invariant
 *      probe.  If a caller has diverged from the locked value, the
 *      counter `LOCKED_ENTRY_METRICS_DIVERGENCE_6634` fires and the
 *      forensic line names the diverging field.  Read-only, non-
 *      mutating — the caller decides how to react (typically: use
 *      the locked value rather than the divergent one).
 *   Re-locking the same positionId is REJECTED (idempotent).  The
 *   counter `LOCKED_ENTRY_METRICS_RELOCK_REJECTED_6634` fires so the
 *   operator sees every attempted mutation.
 *
 * This module is a WITNESS and READ-ONLY registry.  It does not
 * mutate positions.  It gives every downstream mark/exit/UI path a
 * single trustworthy source for entry-time truth so the "positions
 * with valid entryPriceSource but a later invariant break" defect
 * becomes structurally impossible.
 *
 * Mutable AFTER entry (allowed, expected):
 *   currentPriceUsd, currentPriceSol, currentMarketCapUsd,
 *   currentLiquidityUsd, currentHolderCount, currentBuySellRatio.
 * These are NEVER stored here.  They live on TokenState / the mark
 * registry.  The rule is enforced by convention: this module only
 * ever holds the immutable entry snapshot.
 */
object LockedEntryMetrics6634 {

    data class EntrySnapshot(
        val positionId: String,
        val mint: String,
        val symbol: String,
        val assetClass: AssetClass,
        val entryPriceUsd: Double,
        val entryPriceSol: Double,
        val entryCostSol: Double,
        val qtyRaw: BigInteger,
        val qtyTokens: Double,
        val tokenDecimals: Int,
        val quantityScale: Int,
        val entryPriceSource: String,
        val solUsdAtEntry: Double,
        val lockedAtMs: Long,
    )

    private val locked = ConcurrentHashMap<String, EntrySnapshot>()
    private const val CAP = 8192

    private val locksTotal = AtomicLong(0L)
    private val relockRejected = AtomicLong(0L)
    private val divergences = AtomicLong(0L)
    private val reads = AtomicLong(0L)

    /**
     * Lock entry-time metrics at BUY commit.  Idempotent — the second
     * lock for the same positionId is rejected (returns false) and
     * emits `LOCKED_ENTRY_METRICS_RELOCK_REJECTED_6634`.
     */
    fun lockAtBuy6634(snap: EntrySnapshot): Boolean {
        if (snap.positionId.isBlank()) return false
        val prev = locked.putIfAbsent(snap.positionId, snap)
        if (prev != null) {
            relockRejected.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("LOCKED_ENTRY_METRICS_RELOCK_REJECTED_6634")
                ForensicLogger.lifecycle(
                    "LOCKED_ENTRY_METRICS_RELOCK_REJECTED_6634",
                    "positionId=${snap.positionId.take(24)} mint=${snap.mint.take(10)} " +
                        "attemptedNewSource=${snap.entryPriceSource} lockedSource=${prev.entryPriceSource} " +
                        "action=refuse_second_lock",
                )
            } catch (_: Throwable) {}
            return false
        }
        locksTotal.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("LOCKED_ENTRY_METRICS_LOCKED_6634")
            PipelineHealthCollector.labelInc("LOCKED_ENTRY_METRICS_LOCKED_${snap.assetClass.tag}_6634")
        } catch (_: Throwable) {}
        maybeEvictOldest()
        return true
    }

    fun read6634(positionId: String): EntrySnapshot? {
        reads.incrementAndGet()
        return locked[positionId]
    }

    /**
     * Invariant probe — a downstream caller believes an entry-time
     * value.  If the belief diverges from the locked snapshot by more
     * than the field's tolerance, fire a divergence label + forensic
     * line so operator sees WHICH field drifted and WHERE.
     */
    fun assertLocked6634(
        positionId: String,
        fieldName: String,
        currentDoubleValue: Double? = null,
        currentIntValue: Int? = null,
        currentStringValue: String? = null,
        toleranceDouble: Double = 1e-9,
        callSite: String = "",
    ): Boolean {
        val snap = locked[positionId] ?: return true // not locked yet — silent
        val expected: Any? = when (fieldName) {
            "entryPriceUsd" -> snap.entryPriceUsd
            "entryPriceSol" -> snap.entryPriceSol
            "entryCostSol" -> snap.entryCostSol
            "qtyTokens" -> snap.qtyTokens
            "solUsdAtEntry" -> snap.solUsdAtEntry
            "tokenDecimals" -> snap.tokenDecimals
            "quantityScale" -> snap.quantityScale
            "entryPriceSource" -> snap.entryPriceSource
            "mint" -> snap.mint
            "symbol" -> snap.symbol
            else -> null
        } ?: return true
        val diverged = when (expected) {
            is Double -> currentDoubleValue != null &&
                kotlin.math.abs(currentDoubleValue - expected) > toleranceDouble
            is Int -> currentIntValue != null && currentIntValue != expected
            is String -> currentStringValue != null && currentStringValue != expected
            else -> false
        }
        if (diverged) {
            divergences.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("LOCKED_ENTRY_METRICS_DIVERGENCE_6634")
                PipelineHealthCollector.labelInc("LOCKED_ENTRY_METRICS_DIVERGENCE_${fieldName.uppercase()}_6634")
                ForensicLogger.lifecycle(
                    "LOCKED_ENTRY_METRICS_DIVERGENCE_6634",
                    "positionId=${positionId.take(24)} field=$fieldName " +
                        "lockedValue=$expected observedValue=${currentDoubleValue ?: currentIntValue ?: currentStringValue} " +
                        "callSite=${callSite.take(60)} action=prefer_locked_value",
                )
            } catch (_: Throwable) {}
            return false
        }
        return true
    }

    /** Explicit unlock (position CLOSED / purged). Callers wire on terminal close. */
    fun unlock6634(positionId: String, reason: String) {
        val removed = locked.remove(positionId) ?: return
        try {
            PipelineHealthCollector.labelInc("LOCKED_ENTRY_METRICS_UNLOCKED_6634")
            ForensicLogger.lifecycle(
                "LOCKED_ENTRY_METRICS_UNLOCKED_6634",
                "positionId=${positionId.take(24)} mint=${removed.mint.take(10)} " +
                    "lockedForMs=${System.currentTimeMillis() - removed.lockedAtMs} reason=${reason.take(60)}",
            )
        } catch (_: Throwable) {}
    }

    private fun maybeEvictOldest() {
        if (locked.size <= CAP) return
        val oldest = locked.entries.minByOrNull { it.value.lockedAtMs }?.key ?: return
        locked.remove(oldest)
    }

    fun size(): Int = locked.size

    fun statusLine6634(): String =
        "locked=${locked.size} locksTotal=${locksTotal.get()} " +
            "relockRejected=${relockRejected.get()} divergences=${divergences.get()} reads=${reads.get()}"

    internal fun resetForTest() {
        locked.clear()
        locksTotal.set(0L); relockRejected.set(0L); divergences.set(0L); reads.set(0L)
    }
}
