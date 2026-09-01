package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6629 §6 PAPER_ECONOMIC_SNAPSHOT_SINGLE_AUTHORITY.
 *
 * Operator (Feb 2026):
 *   > "Create ONE PaperEconomicSnapshot: cash, reserved, openCost,
 *   >  openMarketValue, unrealizedPnl, realizedPnl, fees, equity, revision.
 *   >  MEME / CRYPTO / MARKETS hero surfaces consume this snapshot verbatim.
 *   >  No screen may independently calculate cash / equity / PnL / open
 *   >  exposure. Delete/fence any UI code that mixes TradeHistoryStore
 *   >  cash with CapitalAuthority equity.
 *   >  Acceptance:
 *   >    all hero surfaces same revision
 *   >    heroCash == authorityCash
 *   >    heroEquity == authorityEquity
 *   >    HERO_JOURNAL_PARITY_FAIL_6616 = 0"
 *
 * This facade forwards to `JournalEconomicAuthority6616.currentSnapshot()`
 * which is already the revision-tracked, journal-derived source. The
 * value-add of this module is a SINGLE typed call every hero surface
 * uses so:
 *   1. There is exactly one code path to grep when the operator asks
 *      "where does the MEME hero cash come from?".
 *   2. Every read is counted so the operator can grep per-surface
 *      consumption and detect a hero card that silently stopped
 *      reading the canonical snapshot.
 *   3. A divergence probe compares the journal cash to the
 *      CanonicalCapitalAuthority6450 ledger cash and fires
 *      `PAPER_ECONOMIC_SNAPSHOT_DIVERGENCE_6629` when they disagree
 *      beyond `TOLERANCE_SOL_6629`, exposing ledger/journal drift at
 *      the very moment a hero surface reads.
 *
 * IMPORTANT: This module NEVER computes cash or equity itself. It only
 * routes through the canonical journal snapshot. All hero surfaces
 * calling `read6629(surface)` become causally identical.
 */
object PaperEconomicSnapshot6629 {

    private val readsTotal = AtomicLong(0L)
    private val readsPerSurface = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private val divergences = AtomicLong(0L)
    private val nullSnapshotReads = AtomicLong(0L)

    /** V5.0.6629 tolerance mirrors JournalEconomicAuthority6616.probeHeroBinding. */
    const val TOLERANCE_SOL_6629 = 0.001

    /**
     * Hero-facing typed snapshot. All hero surfaces MUST call this
     * instead of independently pulling ledger / journal fields.
     *
     * `surface` is the hero identity (`"MEME"`, `"CRYPTO"`, `"MARKETS"`).
     * Returns null when the journal authority has not published yet;
     * the surface must render "Restoring account…" in that case.
     */
    fun read6629(surface: String): Snapshot6629? {
        readsTotal.incrementAndGet()
        readsPerSurface.computeIfAbsent(surface.uppercase()) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("PAPER_ECONOMIC_SNAPSHOT_HERO_READ_${surface.uppercase()}_6629") } catch (_: Throwable) {}
        val journal = try {
            JournalEconomicAuthority6616.currentSnapshot()
        } catch (_: Throwable) { null }
        if (journal == null) {
            nullSnapshotReads.incrementAndGet()
            try { PipelineHealthCollector.labelInc("PAPER_ECONOMIC_SNAPSHOT_NULL_6629") } catch (_: Throwable) {}
            return null
        }
        // Divergence probe — compare journal cash to the ledger cash the
        // hero surfaces used to consume. Fires on drift so the operator
        // can grep the exact moment the two sources disagreed.
        try {
            val ledgerCash = CanonicalCapitalAuthority6450.snapshot().cashSol
            val delta = kotlin.math.abs(ledgerCash - journal.cashSol)
            if (delta > TOLERANCE_SOL_6629) {
                divergences.incrementAndGet()
                PipelineHealthCollector.labelInc("PAPER_ECONOMIC_SNAPSHOT_DIVERGENCE_6629")
                PipelineHealthCollector.labelInc("PAPER_ECONOMIC_SNAPSHOT_DIVERGENCE_${surface.uppercase()}_6629")
                ForensicLogger.lifecycle(
                    "PAPER_ECONOMIC_SNAPSHOT_DIVERGENCE_6629",
                    "surface=${surface.uppercase()} rev=${journal.revision} " +
                        "journalCash=${"%.6f".format(journal.cashSol)} " +
                        "ledgerCash=${"%.6f".format(ledgerCash)} " +
                        "delta=${"%.6f".format(delta)} action=hero_uses_journal",
                )
            }
        } catch (_: Throwable) {}
        return Snapshot6629(
            revision = journal.revision,
            cashSol = journal.cashSol,
            reservedSol = journal.reservedSol,
            openMarketValueSol = journal.openMarketValueSol,
            unrealizedPnlSol = journal.unrealizedPnlSol,
            realizedPnlSol = journal.realizedPnlSol,
            feesSol = journal.feesSol,
            equitySol = journal.equitySol,
            source = journal.source,
        )
    }

    /**
     * Typed view of the canonical economic snapshot. Same fields as
     * `JournalEconomicAuthority6616.CanonicalEconomicSnapshot` but
     * exposed at the `PaperEconomicSnapshot6629` boundary so the
     * operator has one place to grep hero contract changes.
     */
    data class Snapshot6629(
        val revision: Long,
        val cashSol: Double,
        val reservedSol: Double,
        val openMarketValueSol: Double,
        val unrealizedPnlSol: Double,
        val realizedPnlSol: Double,
        val feesSol: Double,
        val equitySol: Double,
        val source: String,
    )

    fun statusLine6629(): String {
        val per = readsPerSurface.entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        return "reads=${readsTotal.get()} [$per] nullSnap=${nullSnapshotReads.get()} divergences=${divergences.get()}"
    }

    /** V5.0.6629 test-only reset. */
    fun resetForTest() {
        readsTotal.set(0L)
        readsPerSurface.clear()
        divergences.set(0L)
        nullSnapshotReads.set(0L)
    }
}
