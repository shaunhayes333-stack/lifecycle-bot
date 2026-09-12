#!/usr/bin/env python3
from pathlib import Path
import hashlib
import json
import os
import re

ROOT = Path(__file__).resolve().parents[2]
touched = []


def read(rel):
    return (ROOT / rel).read_text()


def write(rel, text):
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)
    if rel not in touched:
        touched.append(rel)


def sub_once(rel, pattern, replacement, flags=re.S):
    text = read(rel)
    out, n = re.subn(pattern, replacement, text, count=1, flags=flags)
    if n != 1:
        raise SystemExit(f"6743 expected exactly one replacement in {rel}, got {n}: {pattern[:120]}")
    write(rel, out)


def replace_once(rel, old, new):
    text = read(rel)
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"6743 expected exactly one literal in {rel}, got {n}: {old[:120]}")
    write(rel, text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# 1) CanonicalPositionAuthority6441: never put SOL/token in entryPriceUsd.
#    Missing USD valuation is represented explicitly as unknown while funded
#    exposure remains canonical inventory.
# ---------------------------------------------------------------------------
CPA = "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt"
sub_once(
    CPA,
    r'''                entryPriceUsd = if \(entryPriceUsd > 0\.0\) entryPriceUsd else run \{.*?                entryPoolAddress = entryPoolAddress,''',
    '''                // V5.0.6743 §UNIT_SAFE_ENTRY_BASIS — entryPriceUsd is
                // USD/token only. entryCostSol / tokenQty is SOL/token and
                // must never be written into this field. Keep funded exposure
                // with an explicit unknown-USD-basis provenance instead.
                entryPriceUsd = entryPriceUsd.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                entryPriceSource = when {
                    entryPriceUsd.isFinite() && entryPriceUsd > 0.0 -> entryPriceSource
                    entryPriceSource.contains("USD_BASIS_UNKNOWN", ignoreCase = true) -> entryPriceSource
                    entryPriceSource.isBlank() -> "OPEN_POSITION_USD_BASIS_UNKNOWN_6743"
                    else -> "$entryPriceSource|OPEN_POSITION_USD_BASIS_UNKNOWN_6743"
                },
                entryPoolAddress = entryPoolAddress,'''
)

# Remove the stale comment claiming a price is derived from cost/qty.
text = read(CPA)
text = text.replace(
'''            //   entryPriceUsd is not required at commit time — the
            //   V5.0.6631c strict filter refuses to RENDER positions
            //   with entryPriceUsd <= 0, and V5.0.6631d derives it
            //   from entryCostSol/qty on the carry-replay path, so
            //   the operator's user-visible invariant ("no INVARIANT_BROKEN
            //   entry on the Open Positions screen") is enforced
            //   downstream without requiring every internal opener
            //   to plumb a fill price.
''',
'''            //   entryPriceUsd is not required at commit time. When a
            //   verified USD/token fill basis is unavailable, the position
            //   remains funded canonical exposure with entryPriceUsd=0 and
            //   explicit unresolved-basis provenance. No unit substitution.
''')
write(CPA, text)

sub_once(
    CPA,
    r'''        val unresolvedBasis6741 = p\.entryPriceSource\.contains\("CARRY_USD_BASIS_UNKNOWN_6741"\) \|\|\n            p\.entryPriceSource\.contains\("UNRESOLVED_VALUATION_6741"\)\n        if \(!entry\.isFinite\(\) \|\| \(entry <= 0\.0 && !unresolvedBasis6741\)\) \{''',
    '''        // V5.0.6743 §FUNDED_EXPOSURE_NOT_VALUATION — every funded
        // restore/open source that explicitly declares an unknown USD basis
        // stays in canonical inventory. Valuation consumers must handle the
        // unknown basis; inventory/occupancy must not pretend the exposure
        // disappeared.
        val unresolvedBasis6743 = p.entryPriceSource.contains("CARRY_USD_BASIS_UNKNOWN_6741") ||
            p.entryPriceSource.contains("UNRESOLVED_VALUATION_6741") ||
            p.entryPriceSource.contains("REPLAY_CARRY_NO_USD_BASIS_6541") ||
            p.entryPriceSource.contains("OPEN_POSITION_USD_BASIS_UNKNOWN_6743")
        if (!entry.isFinite() || (entry <= 0.0 && !unresolvedBasis6743)) {'''
)

# ---------------------------------------------------------------------------
# 2) QuantityInvariantAuthority6500: remove source-name exemptions that let
#    positive wrong-unit prices bypass the physical notional check. Unknown
#    (<=0) prices already return entry_price_not_set before this point.
# ---------------------------------------------------------------------------
QIA = "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/QuantityInvariantAuthority6500.kt"
sub_once(
    QIA,
    r'''        // DERIVED_CARRY_COST_QTY_6631 & DURABLE_CARRY sources produce.*?        val qtyNotionalUsd = qtyToken \* entry''',
    '''        // V5.0.6743 §NO_SOURCE_NAME_INVARIANT_BYPASS — a positive
        // entryPriceUsd must satisfy the economic identity regardless of the
        // provenance label. Unknown USD basis already exits above with
        // entry_price_not_set; provenance may not excuse a positive
        // SOL/token value masquerading as USD/token.
        val qtyNotionalUsd = qtyToken * entry'''
)

# ---------------------------------------------------------------------------
# 3) CanonicalPaperReplay6464: parity verification is READ ONLY. One atomic
#    ledger snapshot, stable event+journal revisions, no carry mutation to
#    make the comparator agree with the authority it is verifying.
# ---------------------------------------------------------------------------
CPR = "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperReplay6464.kt"
sub_once(
    CPR,
    r'''        val journalRevisionAtStart = try \{ JournalEconomicAuthority6616\.revision\(\) \} catch \(_: Throwable\) \{ 0L \}\n        var snap = replay\(startingCashSol\)\n        val ledgerCash = try \{ PaperCapitalAuthority6577\.cashSol\(\) \} catch \(_: Throwable\) \{ Double\.NaN \}\n        val ledgerRealized = try \{ PaperCapitalAuthority6577\.realizedPnlSol\(\) \} catch \(_: Throwable\) \{ Double\.NaN \}\n        val ledgerOpen = try \{ PaperCapitalAuthority6577\.openCostBasisSol\(\) \} catch \(_: Throwable\) \{ Double\.NaN \}\n        val ledgerFees = try \{ PaperCapitalAuthority6577\.feesSol\(\) \} catch \(_: Throwable\) \{ Double\.NaN \}''',
    '''        val journalRevisionAtStart = try { JournalEconomicAuthority6616.revision() } catch (_: Throwable) { 0L }
        val eventRevisionAtStart = try { EconomicEventSchema6464.version() } catch (_: Throwable) { 0L }
        val snap = replay(startingCashSol)
        // V5.0.6743 §ATOMIC_READ_ONLY_PARITY — one lock/one instant for
        // cash, open cost, realized and fees. Never combine four snapshots.
        val ledgerSnapshot6743 = try { PaperCapitalAuthority6577.snapshot() } catch (_: Throwable) { null }
        val ledgerCash = ledgerSnapshot6743?.availableCashSol ?: Double.NaN
        val ledgerRealized = ledgerSnapshot6743?.realizedPnlSol ?: Double.NaN
        val ledgerOpen = ledgerSnapshot6743?.openMarketValueSol ?: Double.NaN
        val ledgerFees = ledgerSnapshot6743?.feesSol ?: Double.NaN'''
)

# Delete BOTH compare-time carry establishment and carry reconciliation.
sub_once(
    CPR,
    r'''        // V5\.0\.6489 — one explicit migration from pre-event-authority state\..*?        val qtyMismatches = snap\.perMintRemainingQty\.values\.count \{ it < BigInteger\.ZERO \}''',
    '''        // V5.0.6743 §READ_ONLY_VERIFIER — carry establishment/repair is
        // an explicit migration concern, never a side effect of comparison.
        // A verifier must not rewrite its historical baseline from the ledger
        // deltas it is trying to independently measure.
        val cashDelta = if (ledgerCash.isFinite()) snap.cashSol - ledgerCash else 0.0
        val realizedDelta = if (ledgerRealized.isFinite()) snap.realizedPnlSol - ledgerRealized else 0.0
        val openDelta = if (ledgerOpen.isFinite()) snap.openCostBasisSol - ledgerOpen else 0.0
        val qtyMismatches = snap.perMintRemainingQty.values.count { it < BigInteger.ZERO }'''
)

sub_once(
    CPR,
    r'''        val journalRevisionAtEnd = try \{ JournalEconomicAuthority6616\.revision\(\) \} catch \(_: Throwable\) \{ 0L \}\n        val revisionRaceObserved = journalRevisionAtEnd != journalRevisionAtStart''',
    '''        val eventRevisionAtEnd = try { EconomicEventSchema6464.version() } catch (_: Throwable) { 0L }
        val journalRevisionAtEnd = try { JournalEconomicAuthority6616.revision() } catch (_: Throwable) { 0L }
        val revisionRaceObserved = journalRevisionAtEnd != journalRevisionAtStart ||
            eventRevisionAtEnd != eventRevisionAtStart || snap.eventVersion != eventRevisionAtStart'''
)
text = read(CPR)
text = text.replace(
    'action=stamp_race_guard_fail_open',
    'action=stamp_inconclusive_guard_fail_closed_6743'
)
write(CPR, text)

# ---------------------------------------------------------------------------
# 4) PaperLedgerDivergenceGuard6731: no-parity / stale / racing evidence is
#    INCONCLUSIVE, not evidence of safety. Fail closed for NEW PAPER opens.
#    This authority is admission-only; exits continue to drain inventory.
# ---------------------------------------------------------------------------
PLG = "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/PaperLedgerDivergenceGuard6731.kt"
write(PLG, r'''package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6743 — PAPER economic-integrity admission guard.
 *
 * New PAPER exposure requires a current, revision-consistent parity proof.
 * Missing, stale, or mutation-raced parity is INCONCLUSIVE and therefore
 * blocks NEW admissions. This guard is not consulted by exits, so recovery
 * and inventory drainage remain available while evidence reconverges.
 *
 * Never mutates ledger, journal, replay carry, positions, or capital.
 */
object PaperLedgerDivergenceGuard6731 {
    private const val CASH_DELTA_HARD_STOP_SOL = 3.0
    private const val OPEN_COST_DELTA_HARD_STOP_SOL = 5.0
    private const val POSITION_COUNT_GAP_HARD_STOP = 10
    private const val PARITY_MAX_AGE_MS = 15_000L

    data class Verdict(
        val allow: Boolean,
        val reason: String,
        val cashDelta: Double,
        val openCostDelta: Double,
        val realizedDelta: Double,
        val positionCountGap: Int,
        val divergenceTag: String,
        val eventSchemaRevision: Long = 0L,
        val journalRevisionAtStart: Long = 0L,
        val journalRevisionAtEnd: Long = 0L,
        val revisionRaceObserved: Boolean = false,
    )

    fun evaluate(): Verdict {
        val parity = try { CanonicalPaperReplay6464.lastParity() } catch (_: Throwable) { null }
        if (parity == null) {
            try { PipelineHealthCollector.labelInc("PAPER_LEDGER_PARITY_INCONCLUSIVE_NO_SNAPSHOT_6743") } catch (_: Throwable) {}
            return Verdict(false, "PAPER_LEDGER_PARITY_UNAVAILABLE_6743", 0.0, 0.0, 0.0, 0, "INCONCLUSIVE_NO_PARITY")
        }

        fun stamped(
            allow: Boolean, reason: String, cashDelta: Double, openCostDelta: Double,
            realizedDelta: Double, positionCountGap: Int, tag: String,
            race: Boolean = parity.revisionRaceObserved,
        ) = Verdict(
            allow = allow, reason = reason, cashDelta = cashDelta,
            openCostDelta = openCostDelta, realizedDelta = realizedDelta,
            positionCountGap = positionCountGap, divergenceTag = tag,
            eventSchemaRevision = parity.eventSchemaRevision,
            journalRevisionAtStart = parity.journalRevisionAtStart,
            journalRevisionAtEnd = parity.journalRevisionAtEnd,
            revisionRaceObserved = race,
        )

        if (parity.revisionRaceObserved) {
            try { PipelineHealthCollector.labelInc("PAPER_LEDGER_PARITY_INCONCLUSIVE_REVISION_RACE_6743") } catch (_: Throwable) {}
            return stamped(
                false, "PAPER_LEDGER_PARITY_REVISION_RACE_6743",
                parity.cashDelta, parity.openCostDelta, parity.realizedDelta,
                parity.orphanLotCount,
                "INCONCLUSIVE_REVISION_RACE_${parity.journalRevisionAtStart}_${parity.journalRevisionAtEnd}",
                race = true,
            )
        }

        val parityAge = try { CanonicalPaperReplay6464.lastParityAgeMs() } catch (_: Throwable) { Long.MAX_VALUE }
        if (parityAge > PARITY_MAX_AGE_MS) {
            try { PipelineHealthCollector.labelInc("PAPER_LEDGER_PARITY_INCONCLUSIVE_STALE_6743") } catch (_: Throwable) {}
            return stamped(
                false, "PAPER_LEDGER_PARITY_STALE_6743",
                parity.cashDelta, parity.openCostDelta, parity.realizedDelta,
                parity.orphanLotCount, "INCONCLUSIVE_STALE_${parityAge}ms", race = false,
            )
        }

        val cashDeltaAbs = kotlin.math.abs(parity.cashDelta)
        val openDeltaAbs = kotlin.math.abs(parity.openCostDelta)
        val posGap = parity.orphanLotCount
        val tag = "orphans=${parity.orphanLotCount}_qtyMM=${parity.qtyMismatchCount}"

        if (cashDeltaAbs >= CASH_DELTA_HARD_STOP_SOL) {
            try {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_CASH_6731")
                PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_CASH_6731_${bucketFor(cashDeltaAbs)}")
            } catch (_: Throwable) {}
            return stamped(false, "PAPER_LEDGER_DIVERGENCE_CASH_6731",
                parity.cashDelta, parity.openCostDelta, parity.realizedDelta, posGap, tag, race = false)
        }
        if (openDeltaAbs >= OPEN_COST_DELTA_HARD_STOP_SOL) {
            try {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_OPEN_COST_6731")
                PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_OPEN_COST_6731_${bucketFor(openDeltaAbs)}")
            } catch (_: Throwable) {}
            return stamped(false, "PAPER_LEDGER_DIVERGENCE_OPEN_COST_6731",
                parity.cashDelta, parity.openCostDelta, parity.realizedDelta, posGap, tag, race = false)
        }
        if (posGap >= POSITION_COUNT_GAP_HARD_STOP) {
            try { PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_POS_GAP_6731") } catch (_: Throwable) {}
            return stamped(false, "PAPER_LEDGER_DIVERGENCE_POS_GAP_6731",
                parity.cashDelta, parity.openCostDelta, parity.realizedDelta, posGap, tag, race = false)
        }
        return stamped(true, "OK", parity.cashDelta, parity.openCostDelta, parity.realizedDelta, posGap, tag, race = false)
    }

    private fun bucketFor(delta: Double): String = when {
        delta < 5.0 -> "LT_5SOL"
        delta < 10.0 -> "LT_10SOL"
        delta < 20.0 -> "LT_20SOL"
        delta < 50.0 -> "LT_50SOL"
        else -> "GT_50SOL"
    }

    fun diagnosticLine(): String {
        val v = evaluate()
        return "PAPER_LEDGER_GUARD_6731 allow=${v.allow} reason=${v.reason} cashΔ=${"%.4f".format(v.cashDelta)} openCostΔ=${"%.4f".format(v.openCostDelta)} realizedΔ=${"%.4f".format(v.realizedDelta)} posGap=${v.positionCountGap} tag=${v.divergenceTag}"
    }
}
''')

# ---------------------------------------------------------------------------
# 5) CanonicalRoundTripReconciler6738: atomic per-position stages, ALL FOUR
#    stages required, sticky divergence cannot later become reconciled.
# ---------------------------------------------------------------------------
RTR = "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalRoundTripReconciler6738.kt"
write(RTR, r'''package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6743 — owner-bound four-stage round-trip verifier.
 *
 * A reconciled terminal trade requires, in order:
 * BUY_COMMITTED -> EXIT_DECIDED -> SELL_COMMITTED -> LEARNING_DELIVERED.
 * Transitions are serialized per positionId. Accounting/stage divergence is
 * sticky: later callbacks may complete telemetry but can never turn a known
 * divergent trip back into a reconciled one.
 *
 * Observational only: never mutates capital, lots, risk, or execution state.
 */
object CanonicalRoundTripReconciler6738 {
    enum class Stage { BUY_COMMITTED, EXIT_DECIDED, SELL_COMMITTED, LEARNING_DELIVERED }

    data class TripState(
        val positionId: String,
        val lane: String,
        val mode: String,
        @Volatile var buyAtMs: Long = 0L,
        @Volatile var exitAtMs: Long = 0L,
        @Volatile var sellAtMs: Long = 0L,
        @Volatile var learningAtMs: Long = 0L,
        @Volatile var reconciled: Boolean = false,
        @Volatile var divergenceReason: String = "",
    )

    data class Summary(
        val openTrips: Int,
        val reconciled: Int,
        val diverged: Int,
        val diverged_reasons: Map<String, Int>,
        val learningDeliveredOnce: Long,
        val learningDeliveredDuplicateRefused: Long,
        val shadowTripSkipped: Long,
    )

    private val trips = ConcurrentHashMap<String, TripState>()
    private val learningDeliveredOnce = AtomicLong(0L)
    private val learningDuplicateRefused = AtomicLong(0L)
    private val shadowTripSkipped = AtomicLong(0L)

    fun record(
        positionId: String,
        stage: Stage,
        lane: String = "",
        mode: String = "PAPER",
        eventId: String = "",
    ): Boolean {
        if (positionId.isBlank()) return false
        if (eventId.isNotBlank() && ProvenanceAuthority6737.isExcludedFromParity(eventId)) {
            shadowTripSkipped.incrementAndGet()
            try { PipelineHealthCollector.labelInc("ROUND_TRIP_SHADOW_TRIP_SKIPPED_6738") } catch (_: Throwable) {}
            return false
        }
        val st = trips.computeIfAbsent(positionId) { TripState(positionId, lane, mode) }
        val now = System.currentTimeMillis()
        synchronized(st) {
            fun diverge(reason: String) {
                if (st.divergenceReason.isBlank()) st.divergenceReason = reason
                st.reconciled = false
                try {
                    PipelineHealthCollector.labelInc("ROUND_TRIP_STAGE_DIVERGENCE_6743")
                    ForensicLogger.lifecycle(
                        "ROUND_TRIP_STAGE_DIVERGENCE_6743",
                        "positionId=$positionId stage=$stage lane=${st.lane} mode=${st.mode} reason=${st.divergenceReason}",
                    )
                } catch (_: Throwable) {}
            }

            when (stage) {
                Stage.BUY_COMMITTED -> {
                    if (st.buyAtMs != 0L) return false
                    st.buyAtMs = now
                    if (st.exitAtMs != 0L || st.sellAtMs != 0L || st.learningAtMs != 0L) diverge("BUY_AFTER_LATER_STAGE")
                }
                Stage.EXIT_DECIDED -> {
                    if (st.exitAtMs != 0L) return false
                    st.exitAtMs = now
                    if (st.buyAtMs == 0L) diverge("EXIT_WITHOUT_BUY")
                    if (st.sellAtMs != 0L || st.learningAtMs != 0L) diverge("EXIT_AFTER_TERMINAL_STAGE")
                }
                Stage.SELL_COMMITTED -> {
                    if (st.sellAtMs != 0L) return false
                    st.sellAtMs = now
                    if (st.buyAtMs == 0L) diverge("SELL_WITHOUT_BUY")
                    else if (st.exitAtMs == 0L) diverge("SELL_WITHOUT_EXIT_DECISION")
                    if (st.learningAtMs != 0L) diverge("SELL_AFTER_LEARNING")
                }
                Stage.LEARNING_DELIVERED -> {
                    if (st.learningAtMs != 0L) {
                        learningDuplicateRefused.incrementAndGet()
                        try {
                            PipelineHealthCollector.labelInc("LEARNING_DELIVERY_DUPLICATE_REFUSED_6738")
                            ForensicLogger.lifecycle(
                                "LEARNING_DELIVERY_DUPLICATE_REFUSED_6738",
                                "positionId=$positionId lane=${st.lane} mode=${st.mode} action=refuse_second_delivery",
                            )
                        } catch (_: Throwable) {}
                        return false
                    }
                    st.learningAtMs = now
                    learningDeliveredOnce.incrementAndGet()
                    if (st.buyAtMs == 0L) diverge("LEARNING_WITHOUT_BUY")
                    else if (st.exitAtMs == 0L) diverge("LEARNING_WITHOUT_EXIT_DECISION")
                    else if (st.sellAtMs == 0L) diverge("LEARNING_WITHOUT_SELL")
                }
            }
            verifyAndMarkLocked6743(st)
            return true
        }
    }

    fun markTerminal(
        positionId: String,
        lane: String = "",
        mode: String = "PAPER",
        learningDelivered: Boolean = true,
    ): Boolean {
        val exitOk = record(positionId, Stage.EXIT_DECIDED, lane, mode)
        val soldOk = record(positionId, Stage.SELL_COMMITTED, lane, mode)
        val learnOk = if (learningDelivered) record(positionId, Stage.LEARNING_DELIVERED, lane, mode) else false
        return exitOk || soldOk || learnOk
    }

    fun observeAccountingDivergence(positionId: String, eventId: String, reason: String) {
        if (positionId.isBlank() || reason.isBlank()) return
        val st = trips[positionId] ?: return
        synchronized(st) {
            if (st.divergenceReason.isBlank()) st.divergenceReason = reason
            st.reconciled = false
        }
        if (eventId.isNotBlank()) {
            ProvenanceAuthority6737.classifyOnce(
                eventId,
                ProvenanceAuthority6737.Origin.QUARANTINE_AMBIGUOUS,
                "ROUND_TRIP_DIVERGENCE_$reason",
            )
        }
        try {
            PipelineHealthCollector.labelInc("ROUND_TRIP_DIVERGENCE_OBSERVED_6738")
            ForensicLogger.lifecycle(
                "ROUND_TRIP_DIVERGENCE_OBSERVED_6738",
                "positionId=$positionId eventId=${eventId.take(24)} reason=$reason action=sticky_divergence_tag_event_ambiguous",
            )
        } catch (_: Throwable) {}
    }

    private fun verifyAndMarkLocked6743(st: TripState) {
        // Known divergence is sticky. A later callback cannot erase it.
        if (st.divergenceReason.isNotBlank()) {
            st.reconciled = false
            return
        }
        val complete = st.buyAtMs > 0L && st.exitAtMs > 0L && st.sellAtMs > 0L && st.learningAtMs > 0L
        if (!complete) return
        val ordered = st.buyAtMs <= st.exitAtMs && st.exitAtMs <= st.sellAtMs && st.sellAtMs <= st.learningAtMs
        if (!ordered) {
            st.divergenceReason = "STAGE_ORDER_VIOLATED"
            st.reconciled = false
            try { PipelineHealthCollector.labelInc("ROUND_TRIP_STAGE_ORDER_VIOLATED_6738") } catch (_: Throwable) {}
            return
        }
        st.reconciled = true
        try { PipelineHealthCollector.labelInc("ROUND_TRIP_RECONCILED_6738") } catch (_: Throwable) {}
    }

    fun summary(): Summary {
        val open = trips.values.count { !it.reconciled && it.divergenceReason.isBlank() }
        val ok = trips.values.count { it.reconciled }
        val bad = trips.values.count { !it.reconciled && it.divergenceReason.isNotBlank() }
        val reasons = HashMap<String, Int>()
        for (t in trips.values) if (t.divergenceReason.isNotBlank()) reasons.merge(t.divergenceReason, 1, Int::plus)
        return Summary(
            openTrips = open,
            reconciled = ok,
            diverged = bad,
            diverged_reasons = reasons,
            learningDeliveredOnce = learningDeliveredOnce.get(),
            learningDeliveredDuplicateRefused = learningDuplicateRefused.get(),
            shadowTripSkipped = shadowTripSkipped.get(),
        )
    }

    fun tripOf(positionId: String): TripState? = trips[positionId]

    internal fun resetForTest6738() {
        trips.clear()
        learningDeliveredOnce.set(0L)
        learningDuplicateRefused.set(0L)
        shadowTripSkipped.set(0L)
    }
}
''')

# ---------------------------------------------------------------------------
# 6) Update old tests that codified the superseded defects.
# ---------------------------------------------------------------------------
T6631 = "lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate6631InvariantBrokenPurgeCoverageTest.kt"
replace_once(
    T6631,
'''        assertTrue("V5.0.6631c §B: openPosition must auto-derive entryPriceUsd from cost/qty for legacy callers",
            src.contains("OPEN_POSITION_DERIVED_FROM_COST_QTY_6631"))''',
'''        assertTrue("V5.0.6743: openPosition must never derive USD/token from SOL cost divided by token qty",
            !src.contains("OPEN_POSITION_DERIVED_FROM_COST_QTY_6631") &&
                !src.contains("entryCostSol / qtyToken6631"))'''
)

T6733 = "lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate6733LedgerParityAndSkewTest.kt"
text = read(T6733)
text = text.replace('import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue',
                    'import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue')
text = text.replace(
'''    fun `guard fails open when no parity has ever been computed`() {
        val v = PaperLedgerDivergenceGuard6731.evaluate()
        assertTrue("no parity ever computed must fail-open, got reason=${v.reason}", v.allow)
        assertEquals("OK_NO_PARITY", v.reason)
    }''',
'''    fun `guard fails closed when no parity has ever been computed`() {
        val v = PaperLedgerDivergenceGuard6731.evaluate()
        assertFalse("new admission requires a current parity proof, got reason=${v.reason}", v.allow)
        assertEquals("PAPER_LEDGER_PARITY_UNAVAILABLE_6743", v.reason)
    }''')
write(T6733, text)

T6738 = "lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate6738RoundTripAndExitRecoveryTest.kt"
text = read(T6738)
# Every genuine terminal path must include EXIT_DECIDED before SELL.
text = text.replace(
'CanonicalRoundTripReconciler6738.record(pid, Stage.BUY_COMMITTED, "CRYPTO_ALT", "PAPER"))\n        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.SELL_COMMITTED, "CRYPTO_ALT", "PAPER"))',
'CanonicalRoundTripReconciler6738.record(pid, Stage.BUY_COMMITTED, "CRYPTO_ALT", "PAPER"))\n        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.EXIT_DECIDED, "CRYPTO_ALT", "PAPER"))\n        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.SELL_COMMITTED, "CRYPTO_ALT", "PAPER"))')
text = text.replace(
'CanonicalRoundTripReconciler6738.record(pid, Stage.BUY_COMMITTED))\n        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.SELL_COMMITTED))',
'CanonicalRoundTripReconciler6738.record(pid, Stage.BUY_COMMITTED))\n        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.EXIT_DECIDED))\n        assertTrue(CanonicalRoundTripReconciler6738.record(pid, Stage.SELL_COMMITTED))')
text = text.replace(
'CanonicalRoundTripReconciler6738.record(genuinePid, Stage.BUY_COMMITTED)\n        CanonicalRoundTripReconciler6738.record(genuinePid, Stage.SELL_COMMITTED)',
'CanonicalRoundTripReconciler6738.record(genuinePid, Stage.BUY_COMMITTED)\n        CanonicalRoundTripReconciler6738.record(genuinePid, Stage.EXIT_DECIDED)\n        CanonicalRoundTripReconciler6738.record(genuinePid, Stage.SELL_COMMITTED)')
write(T6738, text)

T6742 = "lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate6742RevisionParityAndRoundTripWiringTest.kt"
text = read(T6742)
text = text.replace(
'''    fun `revision-race parity is source-contract enforced to fail open`() {''',
'''    fun `revision-race parity is source-contract enforced inconclusive fail closed`() {''')
text = text.replace(
'''            "guard MUST fail-open when revisionRaceObserved",
            guardSrc.contains("PAPER_LEDGER_DIVERGENCE_REVISION_RACE_FAIL_OPEN_6742"),''',
'''            "guard MUST fail-closed/inconclusive when revisionRaceObserved",
            guardSrc.contains("PAPER_LEDGER_PARITY_INCONCLUSIVE_REVISION_RACE_6743"),''')
text = text.replace(
'CanonicalRoundTripReconciler6738.record(pid, CanonicalRoundTripReconciler6738.Stage.BUY_COMMITTED, "MEME", "PAPER"))\n        assertTrue(CanonicalRoundTripReconciler6738.record(pid, CanonicalRoundTripReconciler6738.Stage.SELL_COMMITTED, "MEME", "PAPER"))',
'CanonicalRoundTripReconciler6738.record(pid, CanonicalRoundTripReconciler6738.Stage.BUY_COMMITTED, "MEME", "PAPER"))\n        assertTrue(CanonicalRoundTripReconciler6738.record(pid, CanonicalRoundTripReconciler6738.Stage.EXIT_DECIDED, "MEME", "PAPER"))\n        assertTrue(CanonicalRoundTripReconciler6738.record(pid, CanonicalRoundTripReconciler6738.Stage.SELL_COMMITTED, "MEME", "PAPER"))')
write(T6742, text)

# ---------------------------------------------------------------------------
# 7) New regression locks the completed contracts directly against source and
#    verifies four-stage + sticky-divergence behavior at runtime.
# ---------------------------------------------------------------------------
T6743 = "lifecycle_apk/app/src/test/kotlin/com/lifecyclebot/engine/Aate6743SourceAuthorityCompletionTest.kt"
write(T6743, r'''package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalRoundTripReconciler6738
import com.lifecyclebot.engine.truth.CanonicalRoundTripReconciler6738.Stage
import com.lifecyclebot.engine.truth.ProvenanceAuthority6737
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class Aate6743SourceAuthorityCompletionTest {
    @Before
    fun reset() {
        ProvenanceAuthority6737.resetForTest6737()
        CanonicalRoundTripReconciler6738.resetForTest6738()
    }

    @Test
    fun `USD entry basis is never fabricated from SOL cost divided by quantity`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        assertFalse(src.contains("entryCostSol / qtyToken6631"))
        assertFalse(src.contains("OPEN_POSITION_DERIVED_FROM_COST_QTY_6631"))
        assertTrue(src.contains("OPEN_POSITION_USD_BASIS_UNKNOWN_6743"))
        assertTrue(src.contains("REPLAY_CARRY_NO_USD_BASIS_6541"))
    }

    @Test
    fun `positive USD basis is never exempted from economic invariant by source name`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/QuantityInvariantAuthority6500.kt").readText()
        assertFalse(src.contains("carry_or_derived_basis_skipped"))
        assertFalse(src.contains("OPEN_POSITION_DERIVED_FROM_COST_QTY_6631"))
    }

    @Test
    fun `parity compare is read-only and uses one atomic ledger snapshot`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperReplay6464.kt").readText()
        val compare = src.substringAfter("fun compareToLedger(").substringBefore("fun lastSnapshot()")
        assertTrue(compare.contains("PaperCapitalAuthority6577.snapshot()"))
        assertFalse(compare.contains("establishReplayCarry6489("))
        assertFalse(compare.contains("reconcileReplayCarry6498("))
        assertTrue(compare.contains("eventRevisionAtStart"))
        assertTrue(compare.contains("eventRevisionAtEnd"))
    }

    @Test
    fun `inconclusive parity cannot authorize new admission`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperLedgerDivergenceGuard6731.kt").readText()
        assertTrue(src.contains("PAPER_LEDGER_PARITY_INCONCLUSIVE_NO_SNAPSHOT_6743"))
        assertTrue(src.contains("PAPER_LEDGER_PARITY_INCONCLUSIVE_REVISION_RACE_6743"))
        assertTrue(src.contains("PAPER_LEDGER_PARITY_INCONCLUSIVE_STALE_6743"))
        assertFalse(src.contains("OK_REVISION_RACE_6742"))
        assertFalse(src.contains("OK_STALE_PARITY_6732"))
    }

    @Test
    fun `round trip requires all four stages and divergence is sticky`() {
        val missingExit = "missing-exit"
        assertTrue(CanonicalRoundTripReconciler6738.record(missingExit, Stage.BUY_COMMITTED))
        assertTrue(CanonicalRoundTripReconciler6738.record(missingExit, Stage.SELL_COMMITTED))
        assertTrue(CanonicalRoundTripReconciler6738.record(missingExit, Stage.LEARNING_DELIVERED))
        assertFalse(CanonicalRoundTripReconciler6738.tripOf(missingExit)!!.reconciled)
        assertTrue(CanonicalRoundTripReconciler6738.tripOf(missingExit)!!.divergenceReason.isNotBlank())

        val good = "good-four-stage"
        assertTrue(CanonicalRoundTripReconciler6738.record(good, Stage.BUY_COMMITTED))
        assertTrue(CanonicalRoundTripReconciler6738.record(good, Stage.EXIT_DECIDED))
        assertTrue(CanonicalRoundTripReconciler6738.record(good, Stage.SELL_COMMITTED))
        assertTrue(CanonicalRoundTripReconciler6738.record(good, Stage.LEARNING_DELIVERED))
        assertTrue(CanonicalRoundTripReconciler6738.tripOf(good)!!.reconciled)

        val sticky = "sticky-divergence"
        assertTrue(CanonicalRoundTripReconciler6738.record(sticky, Stage.BUY_COMMITTED))
        assertTrue(CanonicalRoundTripReconciler6738.record(sticky, Stage.EXIT_DECIDED))
        assertTrue(CanonicalRoundTripReconciler6738.record(sticky, Stage.SELL_COMMITTED))
        CanonicalRoundTripReconciler6738.observeAccountingDivergence(sticky, "evt-sticky", "CASH_DELTA")
        assertTrue(CanonicalRoundTripReconciler6738.record(sticky, Stage.LEARNING_DELIVERED))
        assertFalse(CanonicalRoundTripReconciler6738.tripOf(sticky)!!.reconciled)
        assertTrue(CanonicalRoundTripReconciler6738.tripOf(sticky)!!.divergenceReason == "CASH_DELTA")
    }
}
''')

# Version bump after source/test mutations.
for rel in ("AATE_VERSION", "lifecycle_apk/AATE_VERSION"):
    write(rel, "5.0.6743\n")

# Self-audit: fail before compilation if any known contradiction remains.
cpa = read(CPA)
qia = read(QIA)
cpr = read(CPR)
rt = read(RTR)
guard = read(PLG)
if "entryCostSol / qtyToken6631" in cpa or "OPEN_POSITION_DERIVED_FROM_COST_QTY_6631" in cpa:
    raise SystemExit("6743 self-audit: SOL/token -> USD/token producer still present")
if "carry_or_derived_basis_skipped" in qia or "OPEN_POSITION_DERIVED_FROM_COST_QTY_6631" in qia:
    raise SystemExit("6743 self-audit: source-name economic invariant bypass still present")
compare = cpr.split("fun compareToLedger(", 1)[1].split("fun lastSnapshot()", 1)[0]
if "establishReplayCarry6489(" in compare or "reconcileReplayCarry6498(" in compare:
    raise SystemExit("6743 self-audit: parity verifier still mutates replay carry")
if "PaperCapitalAuthority6577.snapshot()" not in compare:
    raise SystemExit("6743 self-audit: parity does not use one atomic ledger snapshot")
if "st.buyAtMs > 0L && st.exitAtMs > 0L && st.sellAtMs > 0L && st.learningAtMs > 0L" not in rt:
    raise SystemExit("6743 self-audit: round-trip does not require all four stages")
if "if (st.divergenceReason.isNotBlank())" not in rt:
    raise SystemExit("6743 self-audit: round-trip divergence is not sticky")
if 'return Verdict(false, "PAPER_LEDGER_PARITY_UNAVAILABLE_6743"' not in guard:
    raise SystemExit("6743 self-audit: missing parity still authorizes admission")

# Evidence manifest used by workflow to ensure tests did not rewrite source.
outdir = Path(os.environ.get("RUNNER_TEMP", "/tmp")) / "aate-6743"
outdir.mkdir(parents=True, exist_ok=True)
manifest = []
for rel in touched:
    b = (ROOT / rel).read_bytes()
    manifest.append({"path": rel, "sha256": hashlib.sha256(b).hexdigest()})
(outdir / "manifest.json").write_text(json.dumps({"files": manifest}, indent=2))
(outdir / "paths.txt").write_text("\n".join(touched) + "\n")
print(f"V5.0.6743 staged {len(touched)} files")
for rel in touched:
    print("  ", rel)
