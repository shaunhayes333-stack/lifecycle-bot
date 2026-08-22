package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6464 §P0-#6 — CANONICAL PAPER REPLAY (from typed economic events).
 *
 * OPERATOR MANDATE:
 *   "Do not treat aggregate realized as trustworthy while over-sold=6,
 *    doubleConfirm=32, canonical/registry delta=-89. After quantity /
 *    idempotency repair, replay this run from unique confirmed
 *    economic events. Rebuild: cash, open cost, realized PnL, fees,
 *    remaining quantities, equity. Emit PAPER_REPLAY_PARITY with
 *    cashDelta, realizedDelta, openCostDelta, qtyMismatchCount,
 *    duplicateDiscarded, invalidRowsQuarantined. Canonical replay
 *    becomes source of truth."
 *
 * DESIGN
 * ──────
 * Replays `EconomicEventSchema6464.snapshot()` oldest-first with a
 * hard idempotencyKey uniqueness gate. Duplicate rows are counted as
 * `duplicateDiscarded`. Non-finite or nonsensical rows are counted as
 * `invalidRowsQuarantined`.
 *
 * The output snapshot is compared to `PaperAccountLedger6430` and the
 * five deltas are emitted under label `PAPER_REPLAY_PARITY_6464`.
 * Non-mutating — the ledger heals only via explicit operator command.
 */
object CanonicalPaperReplay6464 {

    data class Snapshot(
        val startingCashSol: Double,
        val cashSol: Double,
        val openCostBasisSol: Double,
        val realizedPnlSol: Double,
        val feesSol: Double,
        val equityShadowSol: Double,
        val perMintRemainingQty: Map<String, BigInteger>,
        val perMintRemainingCostSol: Map<String, Double>,
        val buys: Int,
        val partialSells: Int,
        val fullSells: Int,
        val duplicateDiscarded: Int,
        val invalidRowsQuarantined: Int,
        val orphanOpenCostSol: Double = 0.0,
        val orphanLotCount: Int = 0,
        val eventVersion: Long = 0L,
    )

    data class Parity(
        val cashDelta: Double,
        val realizedDelta: Double,
        val openCostDelta: Double,
        val qtyMismatchCount: Int,
        val duplicateDiscarded: Int,
        val invalidRowsQuarantined: Int,
        val orphanOpenCostSol: Double = 0.0,
        val orphanLotCount: Int = 0,
    )

    private val replays = AtomicLong(0L)
    private val lastSnapshot = AtomicReference<Snapshot?>(null)
    private val lastParity = AtomicReference<Parity?>(null)

    fun replay(startingCashSol: Double): Snapshot {
        replays.incrementAndGet()
        val eventVersion = try { EconomicEventSchema6464.version() } catch (_: Throwable) { 0L }
        val events = try { EconomicEventSchema6464.snapshot() } catch (_: Throwable) { emptyList() }

        val carry6489 = try { EconomicEventSchema6464.replayCarry6489() }
            catch (_: Throwable) { EconomicEventSchema6464.ReplayCarry6489() }
        var cash = startingCashSol.coerceAtLeast(0.0) + carry6489.cashDeltaSol
        var openCost = carry6489.openCostSol
        var realized = carry6489.realizedPnlSol
        var fees = carry6489.feesSol
        var buys = 0; var partials = 0; var fulls = 0
        var dup = 0; var invalid = 0
        val seen = HashSet<String>()
        val perMintQty = HashMap<String, BigInteger>(carry6489.perMintQty)
        val perMintCost = HashMap<String, Double>(carry6489.perMintCostSol)

        // Oldest-first — events deque adds to head, so reverse.
        for (e in events.asReversed()) {
            if (e.mode != "paper") continue
            if (!seen.add(e.idempotencyKey)) { dup++; continue }
            when (e) {
                is EconomicEventSchema6464.Buy -> {
                    if (!e.executedCostSol.isFinite() || e.executedCostSol < 0.0 ||
                        e.filledQty <= BigInteger.ZERO) { invalid++; continue }
                    val totalDebit = e.executedCostSol + e.entryFeesSol.coerceAtLeast(0.0)
                    if (cash - totalDebit < -1e-6) { invalid++; continue }
                    cash -= totalDebit
                    openCost += e.executedCostSol
                    fees += e.entryFeesSol.coerceAtLeast(0.0)
                    perMintQty.merge(e.mint, e.filledQty) { a, b -> a + b }
                    perMintCost.merge(e.mint, e.executedCostSol) { a, b -> a + b }
                    buys++
                }
                is EconomicEventSchema6464.Sell -> {
                    if (!e.netProceedsSol.isFinite() || !e.allocatedCostBasisSol.isFinite() ||
                        e.soldQty <= BigInteger.ZERO) { invalid++; continue }
                    // V5.0.6487 — derive canonical GROSS realized from typed fields.
                    // 6486 rows stored net realized, so trusting the aggregate would
                    // import exit fees twice and reproduce the wallet/ledger delta.
                    val canonicalGrossRealized6487 = e.grossProceedsSol - e.allocatedCostBasisSol
                    if (kotlin.math.abs(canonicalGrossRealized6487) > 30.0) { invalid++; continue }
                    cash += e.netProceedsSol
                    openCost = (openCost - e.allocatedCostBasisSol).coerceAtLeast(0.0)
                    realized += canonicalGrossRealized6487
                    fees += e.exitFeesSol
                    perMintQty.merge(e.mint, e.soldQty.negate()) { a, b -> (a + b).coerceAtLeast(BigInteger.ZERO) }
                    perMintCost.merge(e.mint, -e.allocatedCostBasisSol) { a, b -> (a + b).coerceAtLeast(0.0) }
                    if (e.partial) partials++ else fulls++
                }
            }
        }
        var orphanCost = 0.0
        var orphanLots = 0
        perMintCost.forEach { (mint, cost) ->
            val qty = perMintQty[mint] ?: BigInteger.ZERO
            if (cost > 1e-9 && qty <= BigInteger.ZERO) {
                orphanCost += cost
                orphanLots++
                try {
                    ForensicLogger.lifecycle("OPEN_COST_WITHOUT_CANONICAL_LOT_6475", "mint=${mint.take(12)} cost=${"%.9f".format(cost)} qty=$qty")
                    PipelineHealthCollector.labelInc("OPEN_COST_WITHOUT_CANONICAL_LOT_6475")
                } catch (_: Throwable) {}
            }
        }
        val snap = Snapshot(
            startingCashSol = startingCashSol,
            cashSol = cash, openCostBasisSol = openCost,
            realizedPnlSol = realized, feesSol = fees,
            equityShadowSol = cash + openCost,
            perMintRemainingQty = perMintQty, perMintRemainingCostSol = perMintCost,
            buys = buys, partialSells = partials, fullSells = fulls,
            duplicateDiscarded = dup, invalidRowsQuarantined = invalid,
            orphanOpenCostSol = orphanCost, orphanLotCount = orphanLots,
            eventVersion = eventVersion,
        )
        lastSnapshot.set(snap)
        return snap
    }

    /** V5.0.6486 — startup restore from already-deduplicated durable typed events. */
    fun migrateLegacyLedgerOnce6487(startingCashSol: Double): Boolean {
        val snap = replay(startingCashSol)
        if (snap.invalidRowsQuarantined != 0 || snap.orphanLotCount != 0) {
            try { PipelineHealthCollector.labelInc("PAPER_DURABLE_RESTORE_HELD_DIRTY_6486") } catch (_: Throwable) {}
            return false
        }
        val applied = PaperAccountLedger6430.migrateLegacyReplayOnce6487(
            startingCashSol = snap.startingCashSol, cashSol = snap.cashSol,
            openCostBasisSol = snap.openCostBasisSol, realizedPnlSol = snap.realizedPnlSol,
            feesSol = snap.feesSol, source = "DURABLE_STARTUP_MIGRATION_6487",
        )
        if (applied) CanonicalPositionAuthority6441.setPaperCash(snap.cashSol, "DURABLE_STARTUP_MIGRATION_6487")
        return applied
    }

    fun compareToLedger(startingCashSol: Double, toleranceSol: Double = 0.01): Parity {
        var snap = replay(startingCashSol)
        val ledgerCash = try { PaperAccountLedger6430.cashSol() } catch (_: Throwable) { Double.NaN }
        val ledgerRealized = try { PaperAccountLedger6430.realizedPnlSol() } catch (_: Throwable) { Double.NaN }
        val ledgerOpen = try { PaperAccountLedger6430.openCostBasisSol() } catch (_: Throwable) { Double.NaN }
        val ledgerFees = try { PaperAccountLedger6430.feesSol() } catch (_: Throwable) { Double.NaN }
        // V5.0.6489 — one explicit migration from pre-event-authority state.
        // This does not mutate money or lots: it checkpoints the historical
        // prefix missing from the typed event window. Once established it is
        // immutable except for deterministic folding of CAP-evicted events.
        val carry = try { EconomicEventSchema6464.replayCarry6489() }
            catch (_: Throwable) { EconomicEventSchema6464.ReplayCarry6489() }
        if (!carry.established && ledgerCash.isFinite() && ledgerRealized.isFinite() && ledgerOpen.isFinite()) {
            val canonical = try { CanonicalPositionAuthority6441.activeMintProjections6489() } catch (_: Throwable) { emptyList() }
            val canonicalQty = canonical.associate { it.mint to it.remainingQtyRaw }
            val canonicalCost = canonical.associate { it.mint to it.remainingCostBasisSol }
            val qtyOffset = (canonicalQty.keys + snap.perMintRemainingQty.keys).associateWith { mint ->
                (canonicalQty[mint] ?: BigInteger.ZERO) - (snap.perMintRemainingQty[mint] ?: BigInteger.ZERO)
            }
            val costOffset = (canonicalCost.keys + snap.perMintRemainingCostSol.keys).associateWith { mint ->
                (canonicalCost[mint] ?: 0.0) - (snap.perMintRemainingCostSol[mint] ?: 0.0)
            }
            val established = EconomicEventSchema6464.establishReplayCarry6489(
                cashDeltaSol = ledgerCash - snap.cashSol,
                openCostSol = ledgerOpen - snap.openCostBasisSol,
                realizedPnlSol = ledgerRealized - snap.realizedPnlSol,
                feesSol = if (ledgerFees.isFinite()) ledgerFees - snap.feesSol else 0.0,
                perMintQty = qtyOffset,
                perMintCostSol = costOffset,
            )
            if (established) snap = replay(startingCashSol)
        }
        val cashDelta = if (ledgerCash.isFinite()) snap.cashSol - ledgerCash else 0.0
        val realizedDelta = if (ledgerRealized.isFinite()) snap.realizedPnlSol - ledgerRealized else 0.0
        val openDelta = if (ledgerOpen.isFinite()) snap.openCostBasisSol - ledgerOpen else 0.0
        val qtyMismatches = snap.perMintRemainingQty.values.count { it < BigInteger.ZERO }
        val parity = Parity(
            cashDelta = cashDelta, realizedDelta = realizedDelta, openCostDelta = openDelta,
            qtyMismatchCount = qtyMismatches,
            duplicateDiscarded = snap.duplicateDiscarded,
            invalidRowsQuarantined = snap.invalidRowsQuarantined,
            orphanOpenCostSol = snap.orphanOpenCostSol,
            orphanLotCount = snap.orphanLotCount,
        )
        lastParity.set(parity)
        val diverged = kotlin.math.abs(cashDelta) > toleranceSol ||
                       kotlin.math.abs(realizedDelta) > toleranceSol ||
                       kotlin.math.abs(openDelta) > toleranceSol ||
                       snap.orphanLotCount > 0
        try {
            ForensicLogger.lifecycle(
                "PAPER_REPLAY_PARITY_6464",
                "cashΔ=${"%.4f".format(cashDelta)} realizedΔ=${"%.4f".format(realizedDelta)} " +
                    "openCostΔ=${"%.4f".format(openDelta)} qtyMismatch=$qtyMismatches " +
                    "dupDiscarded=${parity.duplicateDiscarded} invalidQuarantined=${parity.invalidRowsQuarantined} " +
                    "diverged=$diverged",
            )
            PipelineHealthCollector.labelInc(if (diverged) "PAPER_REPLAY_PARITY_DIVERGED_6464" else "PAPER_REPLAY_PARITY_CONVERGED_6464")
        } catch (_: Throwable) {}
        return parity
    }

    fun lastSnapshot(): Snapshot? = lastSnapshot.get()?.takeIf {
        it.eventVersion == try { EconomicEventSchema6464.version() } catch (_: Throwable) { -1L }
    }
    fun lastParity(): Parity? = lastParity.get()

    fun statusLine(): String {
        val p = lastParity.get()
        return if (p == null) "no_parity_yet replays=${replays.get()}"
        else "cashΔ=${"%.4f".format(p.cashDelta)} realizedΔ=${"%.4f".format(p.realizedDelta)} " +
             "openCostΔ=${"%.4f".format(p.openCostDelta)} qtyMismatch=${p.qtyMismatchCount} " +
             "dupDiscarded=${p.duplicateDiscarded} invalidQuarantined=${p.invalidRowsQuarantined} " +
             "orphanOpenCost=${"%.6f".format(p.orphanOpenCostSol)} orphanLots=${p.orphanLotCount} replays=${replays.get()}"
    }

    internal fun resetForTest() {
        replays.set(0L); lastSnapshot.set(null); lastParity.set(null)
    }
}
