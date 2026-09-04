package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TradeHistoryStore
import com.lifecyclebot.data.Trade
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6619 §JOURNAL_DERIVED_HERO_AUTHORITY (operator directive Feb 2026):
 *
 *   "The main UI balance is broken. Look at the journal vs the hero
 *    balance in the main UI. That figure is impossible and should be
 *    solely derived from data directly from the journal."
 *
 * FORENSIC EVIDENCE (fresh install of V5.0.6617):
 *   Journal RAW parity band:  +1.5999 SOL  (~$136, 83 trades)
 *   Journal clean tab:        +$146.08     (5W/7L, 79 trades)
 *   PaperAccountLedger:       +$5,793.45   (+476% start)   ← impossible
 *
 * This reducer is one side of continuous reconciliation. It never
 * replaces the ledger or publishes UI equity by itself: a unified account
 * snapshot is publishable only when this result, the ledger, canonical
 * lots, and the immutable event registry agree exactly.
 *
 * REPLAY EQUATION (walked over paper journal rows):
 *
 *   For each row where mode == "paper":
 *     BUY:            cash -= (sol + feeSol)
 *                     openCost += sol
 *                     fees += feeSol
 *     SELL/PARTIAL:   cash += (grossProceedsSol - feeSol)
 *                     openCost -= soldCostBasisSol
 *                     realizedPnl += (grossProceedsSol - soldCostBasisSol)
 *                     fees += feeSol
 *
 *   startingCashSol comes from the paper-capital facade
 *   (PaperCapitalAuthority6577.startingCashSol), which delegates to
 *   an immutable config field set at init/reset only — not an
 *   accumulator that drifts on trades.
 *
 *   equitySol = cashSol + openCostBasisSol  (conservative — uses cost
 *     basis for open positions; live-mark-based openMV is exposed
 *     separately via CanonicalCapitalAuthority6450 and remains
 *     available to consumers that want it, but the hero derives from
 *     the journal alone per operator directive.)
 *
 * Note on quantities: this authority reads durable journal rows;
 * BigInteger raw quantities remain the source of truth for lot
 * accounting elsewhere. The equation here operates on SOL Doubles per
 * row, which is what the journal records for economic reporting.
 */
object JournalEconomicReplay6619 {

    data class ReplayResult(
        val cashSol: Double,
        val realizedPnlSol: Double,
        val openCostBasisSol: Double,
        val feesSol: Double,
        val equitySol: Double,
        val startingCashSol: Double,
        val paperRows: Int,
        val paperBuys: Int,
        val paperSells: Int,
        val paperPartialSells: Int,
        val emittedAtMs: Long,
        val reconciled: Boolean = true,
        val invariantFailures: List<String> = emptyList(),
        val openRawQtyByPosition: Map<String, java.math.BigInteger> = emptyMap(),
        val openBasisByPosition: Map<String, Double> = emptyMap(),
    )

    private val replays = AtomicLong(0L)
    private val lastResult = AtomicReference<ReplayResult?>(null)
    private val ledgerDivergenceLast = AtomicReference<Double>(0.0)
    private val reportedInvariantFailures6653 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val reportedEmbeddedEntryRecoveries6664 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Deterministically compute paper economics from durable journal
     * rows. Non-clamping, no fallback to the ledger. Returns a stable
     * ReplayResult even when the journal is empty (returns
     * startingCashSol + zeros).
     *
     * V5.0.6619b §MAIN_THREAD_SAFETY — TradeHistoryStore.ensureInitialized
     * opens the SQLite writable database + loads all rows into memory
     * synchronously on the calling thread. MainActivity's cold-open
     * hydration path (onResume → hydratePaperWalletForColdOpen →
     * PaperAccountLedger6430.initPersistent6487 → notifyEconomicMutation
     * → replay) runs on the Main thread. On a CI emulator this pushed
     * the initial UI-ready wait past 5 s and the smoke test's btnToggle
     * lookup failed. Fix: on the Main thread we DO NOT walk the durable
     * journal. We return a fast result seeded with startingCashSol and
     * the last cached values; the next background tick (BotService
     * loop, ~5-12s) picks up the full replay off-thread. This preserves
     * the "hero derived solely from journal" doctrine — pre-first-trade
     * the journal IS empty so cash = startingCash is the correct
     * journal-derived answer.
     */
    fun replay(): ReplayResult {
        replays.incrementAndGet()
        val startingSol = try {
            PaperCapitalAuthority6577.startingCashSol().coerceAtLeast(0.0)
        } catch (_: Throwable) { 0.0 }

        val onMainThread = try {
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        } catch (_: Throwable) { false }
        if (onMainThread) {
            try { PipelineHealthCollector.labelInc("JOURNAL_REPLAY_MAIN_THREAD_DEFERRED_6619") } catch (_: Throwable) {}
            val prior = lastResult.get()
            val fast = if (prior != null) {
                // Preserve last full replay; only bump startingCashSol
                // in case it changed (reset). No DB read.
                prior.copy(startingCashSol = startingSol, emittedAtMs = System.currentTimeMillis())
            } else {
                ReplayResult(
                    cashSol = startingSol,
                    realizedPnlSol = 0.0,
                    openCostBasisSol = 0.0,
                    feesSol = 0.0,
                    equitySol = startingSol,
                    startingCashSol = startingSol,
                    paperRows = 0, paperBuys = 0, paperSells = 0, paperPartialSells = 0,
                    emittedAtMs = System.currentTimeMillis(),
                    reconciled = false,
                    invariantFailures = listOf("MAIN_THREAD_REPLAY_DEFERRED"),
                )
            }
            lastResult.set(fast)
            return fast
        }

        data class Lot(var basisSol: Double, var rawQty: java.math.BigInteger, var displayQty: Double)
        var cash = startingSol
        var realized = 0.0
        var openCost = 0.0
        var fees = 0.0
        var buys = 0
        var sells = 0
        var partials = 0
        var totalRows = 0

        val rows = try {
            TradeHistoryStore.getAllValidTradesSnapshot(limit = 20_000)
        } catch (_: Throwable) { emptyList() }.sortedBy { it.ts }
        val lots = mutableMapOf<String, Lot>()
        val seenEvents = mutableSetOf<String>()
        val seenFills = mutableSetOf<String>()
        val failures = mutableListOf<String>()

        fun displayToRaw(value: Double, decimals: Int): java.math.BigInteger {
            if (!value.isFinite() || value <= 0.0 || decimals !in 0..18) return java.math.BigInteger.ZERO
            return try {
                java.math.BigDecimal.valueOf(value)
                    .movePointRight(decimals)
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .toBigIntegerExact()
            } catch (_: Throwable) { java.math.BigInteger.ZERO }
        }

        fun reject(t: com.lifecyclebot.data.Trade, eventId: String, reason: String) {
            val identity = "$eventId:$reason"
            failures += identity
            try {
                LearningQuarantineGate6470.quarantinePositionId("EVENT:$eventId", reason)
                if (t.positionId.isNotBlank()) LearningQuarantineGate6470.quarantinePositionId(t.positionId, "EVENT:$eventId:$reason")
                // V5.0.6653 — one alarm per immutable bad event.  A single
                // legacy row used to increment on every 5-second replay and
                // masquerade as hundreds of new accounting failures.
                if (reportedInvariantFailures6653.add(identity)) {
                    PipelineHealthCollector.labelInc("JOURNAL_LOT_REPLAY_INVARIANT_FAILURE_6647")
                    ForensicLogger.lifecycle("JOURNAL_LOT_REPLAY_INVARIANT_FAILURE_6647", "economicEventId=$eventId positionId=${t.positionId} side=${t.side} fillIndex=${t.partialSequence} reason=$reason action=quarantine_exact_event_once")
                }
            } catch (_: Throwable) {}
        }

        for (t in rows) {
            if (!t.mode.equals("paper", ignoreCase = true)) continue
            val side = t.side.uppercase()
            val eventId = t.economicEventId.ifBlank {
                // Deterministic legacy repair identity. Historical rows are
                // retained; no purge/reset or floating-value identity is used.
                "LEGACY:${t.positionId}:${t.ts}:$side:${t.partialSequence}"
            }
            // V5.0.6659 — pre-repair CryptoAlt SELL rows were display-only
            // duplicates of EconomicEventSchema receipts: no position id and
            // no immutable event id. repairCryptoHistory6659 projects the
            // typed receipt, so never apply (or fail on) the legacy duplicate.
            if (t.economicEventId.isBlank() &&
                t.tradingMode.contains("CryptoAlt", ignoreCase = true) &&
                (side == "SELL" || side == "PARTIAL_SELL")
            ) {
                try { PipelineHealthCollector.labelInc("CRYPTO_LEGACY_DISPLAY_ROW_SUPERSEDED_6659") } catch (_: Throwable) {}
                continue
            }
            totalRows++
            if (t.positionId.isBlank()) { reject(t, eventId, "MISSING_POSITION_ID"); continue }
            if (!seenEvents.add(eventId)) { reject(t, eventId, "DUPLICATE_EVENT_ID"); continue }
            // BUY/ADD rows historically share partialSequence=0. Their sealed
            // economic event is the fill identity; sell rows use the terminal
            // or partial fill index supplied by the canonical reducer.
            val fillKey = if (side == "BUY" || side == "QTY_RECONCILE") eventId
                else "${t.positionId}:$side:${t.partialSequence}"
            if (!seenFills.add(fillKey)) { reject(t, eventId, "DUPLICATE_FILL_INDEX"); continue }
            when {
                side == "QTY_RECONCILE" -> {
                    val lot = lots[t.positionId]
                    if (lot == null) { reject(t, eventId, "QTY_RECONCILE_WITHOUT_LOT"); continue }
                    val subtract = t.canonicalConsumedRaw.coerceAtLeast(java.math.BigInteger.ZERO)
                    val add = t.entryRawQty.coerceAtLeast(java.math.BigInteger.ZERO)
                    if ((subtract == java.math.BigInteger.ZERO) == (add == java.math.BigInteger.ZERO)) {
                        reject(t, eventId, "QTY_RECONCILE_DIRECTION_INVALID"); continue
                    }
                    val nextRaw = lot.rawQty - subtract + add
                    if (nextRaw < java.math.BigInteger.ZERO) {
                        reject(t, eventId, "QTY_RECONCILE_NEGATIVE_LOT"); continue
                    }
                    lot.rawQty = nextRaw
                    try { PipelineHealthCollector.labelInc("JOURNAL_QTY_RECONCILED_TO_CANONICAL_6666") } catch (_: Throwable) {}
                }
                side == "BUY" -> {
                    val cost = t.sol
                    val fee = t.feeSol
                    if (!cost.isFinite() || cost <= 0.0 || !fee.isFinite() || fee < 0.0) {
                        reject(t, eventId, "INVALID_BUY_BASIS_OR_FEE"); continue
                    }
                    // Deterministic legacy repair: old rows may lack raw fields
                    // but retain quantity + decimals. Convert once with decimal
                    // arithmetic; never infer quantity from price or PnL.
                    val raw = t.entryRawQty.takeIf { it > java.math.BigInteger.ZERO }
                        ?: displayToRaw(t.entryQtyToken, t.tokenDecimals.takeIf { it >= 0 } ?: t.entryDecimals)
                    val display = t.entryQtyToken.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                    val prior = lots[t.positionId]
                    if (prior == null) lots[t.positionId] = Lot(cost, raw, display)
                    else { prior.basisSol += cost; prior.rawQty += raw; prior.displayQty += display }
                    cash -= (cost + fee)
                    openCost += cost
                    fees += fee
                    buys++
                }
                side == "SELL" || side == "PARTIAL_SELL" -> {
                    // A sealed modern row may legitimately have zero proceeds.
                    // Legacy rows predate the explicit field and are repaired
                    // deterministically from their historical `sol` column.
                    val gross = if (t.economicEventId.isNotBlank()) t.grossProceedsSol
                        else t.grossProceedsSol.takeIf { it.isFinite() && it > 0.0 } ?: t.sol
                    val basis = t.soldCostBasisSol
                    val fee = t.feeSol
                    var lot = lots[t.positionId]
                    // V5.0.6664 — historical canonical terminals can outlive the
                    // pre-6659 BUY projection that created their position.  6648's
                    // strict lot replay correctly detected the missing row, but then
                    // made every later valid mutation unreconciled forever.  A modern
                    // immutable terminal receipt already carries the exact entry basis,
                    // consumed raw quantity, decimals and entry-price snapshot.  For a
                    // FULL SELL only, reconstruct that missing opening leg in the replay
                    // before applying the terminal.  This is not a balance clamp and it
                    // does not invent PnL: debit the receipt's sealed basis, then credit
                    // its sealed proceeds.  Partials and incomplete receipts remain hard
                    // failures because their original full lot cannot be proven.
                    if (lot == null && side == "SELL" && t.economicEventId.startsWith("paper_full_")) {
                        val recoveredRaw = t.canonicalConsumedRaw.takeIf { it > java.math.BigInteger.ZERO }
                            ?: displayToRaw(t.soldQtyToken, t.tokenDecimals.takeIf { it >= 0 } ?: t.entryDecimals)
                        val recoveredDisplay = t.soldQtyToken.takeIf { it.isFinite() && it > 0.0 }
                            ?: t.entryQtyToken.takeIf { it.isFinite() && it > 0.0 }
                            ?: 0.0
                        val receiptProvesEntry = basis.isFinite() && basis > 0.0 &&
                            recoveredRaw > java.math.BigInteger.ZERO &&
                            t.entryPriceSnapshot.isFinite() && t.entryPriceSnapshot > 0.0
                        if (receiptProvesEntry) {
                            lot = Lot(basis, recoveredRaw, recoveredDisplay)
                            lots[t.positionId] = lot
                            cash -= basis
                            openCost += basis
                            try {
                                if (reportedEmbeddedEntryRecoveries6664.add(eventId)) {
                                    PipelineHealthCollector.labelInc("JOURNAL_EMBEDDED_ENTRY_RECOVERED_6664")
                                    ForensicLogger.lifecycle(
                                        "JOURNAL_EMBEDDED_ENTRY_RECOVERED_6664",
                                        "economicEventId=${eventId.take(40)} positionId=${t.positionId.take(24)} " +
                                            "mint=${t.mint.take(10)} basis=${"%.6f".format(basis)} raw=$recoveredRaw " +
                                            "action=replay_sealed_terminal_entry_then_close",
                                    )
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    if (lot == null) { reject(t, eventId, "SELL_WITHOUT_MATCHING_BUY_LOT"); continue }
                    if (!basis.isFinite() || basis <= 0.0) { reject(t, eventId, "MISSING_OR_NEGATIVE_BASIS"); continue }
                    if (basis > lot.basisSol + 1e-9) { reject(t, eventId, "BASIS_EXCEEDS_REMAINING_LOT"); continue }
                    if (!gross.isFinite() || gross < 0.0 || !fee.isFinite() || fee < 0.0) { reject(t, eventId, "INVALID_PROCEEDS_OR_FEE"); continue }
                    val soldRaw = t.canonicalConsumedRaw.takeIf { it > java.math.BigInteger.ZERO }
                        ?: displayToRaw(t.soldQtyToken, t.tokenDecimals.takeIf { it >= 0 } ?: t.entryDecimals)
                    if (soldRaw > java.math.BigInteger.ZERO && lot.rawQty > java.math.BigInteger.ZERO && soldRaw > lot.rawQty) {
                        reject(t, eventId, "SELL_QTY_EXCEEDS_REMAINING_LOT"); continue
                    }
                    val soldDisplay = t.soldQtyToken.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                    val nextBasis = lot.basisSol - basis
                    val nextRaw = if (soldRaw > java.math.BigInteger.ZERO) lot.rawQty - soldRaw else lot.rawQty
                    val nextDisplay = if (soldDisplay > 0.0) lot.displayQty - soldDisplay else lot.displayQty
                    if (nextBasis < -1e-9 || nextRaw < java.math.BigInteger.ZERO || nextDisplay < -1e-9) {
                        reject(t, eventId, "NEGATIVE_REMAINING_LOT"); continue
                    }
                    if (side == "SELL" && (kotlin.math.abs(nextBasis) > 1e-9 ||
                            (lot.rawQty > java.math.BigInteger.ZERO && nextRaw != java.math.BigInteger.ZERO))) {
                        reject(t, eventId, "TERMINAL_SELL_INCOMPLETE_LOT"); continue
                    }
                    cash += (gross - fee)
                    openCost -= basis
                    // Match PaperAccountLedger6430 exactly: realized is gross
                    // P&L and fees remain a separate economic line.
                    realized += (gross - basis)
                    fees += fee
                    lot.basisSol = nextBasis
                    lot.rawQty = nextRaw
                    lot.displayQty = nextDisplay
                    if (side == "SELL" || lot.basisSol <= 1e-9) lots.remove(t.positionId)
                    if (side == "SELL") sells++ else partials++
                }
            }
        }
        if (openCost < -1e-9) {
            failures += "GLOBAL:NEGATIVE_OPEN_BASIS"
            try { PipelineHealthCollector.labelInc("JOURNAL_NEGATIVE_BASIS_INVARIANT_6647") } catch (_: Throwable) {}
        }
        val equity = cash + openCost
        val result = ReplayResult(
            cashSol = cash,
            realizedPnlSol = realized,
            openCostBasisSol = openCost,
            feesSol = fees,
            equitySol = equity,
            startingCashSol = startingSol,
            paperRows = totalRows,
            paperBuys = buys,
            paperSells = sells,
            paperPartialSells = partials,
            emittedAtMs = System.currentTimeMillis(),
            reconciled = failures.isEmpty(),
            invariantFailures = failures.toList(),
            openRawQtyByPosition = lots.mapValues { it.value.rawQty },
            openBasisByPosition = lots.mapValues { it.value.basisSol },
        )
        lastResult.set(result)

        // Divergence probe — compare journal-replayed cash against
        // ledger cash. Non-mutating; emits a counter + forensic line
        // when they disagree by > 0.001 SOL so operator sees exactly
        // how much the ledger drifted from the journal.
        try {
            val ledgerCash = PaperCapitalAuthority6577.cashSol()
            val delta = ledgerCash - cash
            ledgerDivergenceLast.set(delta)
            if (kotlin.math.abs(delta) > 0.001) {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619")
                ForensicLogger.lifecycle(
                    "PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619",
                    "ledgerCash=${"%.6f".format(ledgerCash)} " +
                        "journalCash=${"%.6f".format(cash)} " +
                        "delta=${"%.6f".format(delta)} " +
                        "paperRows=$totalRows buys=$buys sells=$sells partials=$partials " +
                        "action=fail_closed_retain_last_reconciled_account",
                )
            } else {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_JOURNAL_PARITY_HEALTHY_6619")
            }
        } catch (_: Throwable) {}

        return result
    }

    /**
     * V5.0.6662 — settle durable journal lots whose canonical position was
     * deliberately removed by an earlier Stop/restart implementation.
     *
     * The startup ledger rebuild already returns this basis to cash after the
     * canonical projection is gone.  Historically it did not append the paired
     * journal terminal, leaving the journal lower than the ledger forever.
     * Preserve every BUY and append one immutable, zero-PnL refund SELL instead
     * of deleting history or forcing the UI balance.
     */
    @Synchronized
    fun repairOrphanedOpenLots6662(): Int {
        val replay = replay()
        if (replay.openBasisByPosition.isEmpty()) return 0
        val buys = try {
            TradeHistoryStore.getAllValidTradesSnapshot(limit = 20_000)
                .asSequence()
                .filter { it.mode.equals("paper", true) && it.side.equals("BUY", true) }
                .filter { it.positionId.isNotBlank() }
                .sortedBy { it.ts }
                .groupBy { it.positionId }
        } catch (_: Throwable) { emptyMap() }
        var repaired = 0
        replay.openBasisByPosition.forEach { (positionId, basis) ->
            if (!basis.isFinite() || basis <= 1e-9) return@forEach
            val canonical = try { CanonicalPositionAuthority6441.getPosition(positionId) } catch (_: Throwable) { null }
            // A CLOSED canonical row is not an orphan. Its terminal journal
            // insert is asynchronous and may still be crossing the durable
            // boundary. Refunding it here creates a second SELL; when the real
            // terminal arrives replay correctly rejects the over-consumption
            // as NEGATIVE_REMAINING_LOT. Only positions absent from canonical
            // authority altogether are eligible for the legacy stop repair.
            if (canonical != null) return@forEach
            val positionBuys = buys[positionId].orEmpty()
            val seed = positionBuys.firstOrNull() ?: return@forEach
            // Canonical position and journal terminal persistence are not one
            // CPU instruction. Never refund a just-written BUY while its
            // canonical/open or close receipt is still crossing that boundary.
            val newestBuyAt = positionBuys.maxOfOrNull { it.ts } ?: 0L
            if (System.currentTimeMillis() - newestBuyAt < 10_000L) return@forEach
            val eventId = "PAPER6619:ORPHAN_REFUND:$positionId"
            val raw = replay.openRawQtyByPosition[positionId] ?: java.math.BigInteger.ZERO
            val scale = seed.tokenDecimals.takeIf { it in 0..18 }
                ?: seed.entryDecimals.coerceIn(0, 18)
            val displayQty = try {
                raw.toBigDecimal().movePointLeft(scale).toDouble()
            } catch (_: Throwable) { 0.0 }

            // The ledger side was already applied by the identity rebuild.
            // Witness it with the same immutable id before durable journaling.
            PaperEconomicAtomicCommit6632.stampLedger(
                eventId, seed.mint, PaperEconomicAtomicCommit6632.Side.SELL,
                "JournalEconomicReplay6619.orphanRefund6662",
            )
            TradeHistoryStore.recordTrade(Trade(
                side = "SELL", mode = "paper", sol = basis,
                price = seed.entryPriceSnapshot.takeIf { it.isFinite() && it > 0.0 }
                    ?: seed.price.coerceAtLeast(0.000000000001),
                ts = System.currentTimeMillis(),
                reason = "ORPHANED_STOP_LOT_REFUND_6662",
                pnlSol = 0.0, pnlPct = 0.0, feeSol = 0.0, netPnlSol = 0.0,
                tradingMode = seed.tradingMode, tradingModeEmoji = seed.tradingModeEmoji,
                mint = seed.mint, proofState = "PAPER_SIMULATED",
                positionId = positionId, entryTsMs = seed.entryTsMs.takeIf { it > 0L } ?: seed.ts,
                entryPriceSnapshot = seed.entryPriceSnapshot.takeIf { it.isFinite() && it > 0.0 }
                    ?: seed.price.coerceAtLeast(0.000000000001),
                entryQtyToken = positionBuys.sumOf { it.entryQtyToken.coerceAtLeast(0.0) },
                entryCostSol = basis, entryDecimals = scale,
                soldQtyToken = displayQty, remainingQtyToken = 0.0,
                entryRawQty = positionBuys.fold(java.math.BigInteger.ZERO) { acc, row -> acc + row.entryRawQty },
                canonicalConsumedRaw = raw, remainingRawQty = java.math.BigInteger.ZERO,
                tokenDecimals = scale, soldCostBasisSol = basis,
                grossProceedsSol = basis, economicEventId = eventId,
            ))
            repaired++
            try {
                PipelineHealthCollector.labelInc("JOURNAL_ORPHAN_LOT_REFUNDED_6662")
                ForensicLogger.lifecycle(
                    "JOURNAL_ORPHAN_LOT_REFUNDED_6662",
                    "positionId=${positionId.take(24)} mint=${seed.mint.take(10)} basis=${"%.6f".format(basis)} action=durable_zero_pnl_terminal",
                )
            } catch (_: Throwable) {}
        }
        if (repaired > 0) {
            try { JournalEconomicAuthority6616.forcePublish("ORPHAN_LOT_REFUND_6662") } catch (_: Throwable) {}
        }
        return repaired
    }

    fun latest(): ReplayResult? = lastResult.get()

    fun latestLedgerDivergenceSol(): Double = ledgerDivergenceLast.get() ?: 0.0

    fun statusLine(): String {
        val r = lastResult.get()
        val div = ledgerDivergenceLast.get() ?: 0.0
        return "replays=${replays.get()} " +
            (if (r != null)
                "rows=${r.paperRows} buys=${r.paperBuys} sells=${r.paperSells} partials=${r.paperPartialSells} " +
                    "cash=${"%.4f".format(r.cashSol)} realized=${"%+.4f".format(r.realizedPnlSol)} " +
                    "openCost=${"%.4f".format(r.openCostBasisSol)} equity=${"%.4f".format(r.equitySol)} " +
                    "ledgerDelta=${"%+.4f".format(div)}"
             else "result=empty")
    }

    internal fun resetForTest() {
        replays.set(0L); lastResult.set(null); ledgerDivergenceLast.set(0.0)
        reportedEmbeddedEntryRecoveries6664.clear()
    }
}
