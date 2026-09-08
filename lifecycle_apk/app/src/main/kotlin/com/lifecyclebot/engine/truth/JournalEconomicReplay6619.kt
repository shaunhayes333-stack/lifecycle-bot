package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TradeHistoryStore
import com.lifecyclebot.data.Trade
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6619 §JOURNAL_DERIVED_HERO_AUTHORITY.
 * Durable paper journal replay is the economic source for paper hero surfaces.
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
    // V5.0.6699 — replay is intentionally repeatable; telemetry for historical
    // rows must not be. Key by immutable replay identity so one old row cannot
    // create a new counter/log event every 5 seconds forever.
    private val reportedReplaySupersessions6699 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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

        // V5.0.6697 — a historical 6659 repair projection is superseded when
        // the same position already has its native durable BUY.
        val nativeBuyPositions6697 = rows.asSequence()
            .filter { it.mode.equals("paper", true) && it.side.equals("BUY", true) }
            .filter { it.positionId.isNotBlank() }
            .filterNot { it.reason.contains("CROSS_ASSET_CANONICAL_OPEN_6659", ignoreCase = true) }
            .map { it.positionId }
            .toSet()

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
                if (reportedInvariantFailures6653.add(identity)) {
                    PipelineHealthCollector.labelInc("JOURNAL_LOT_REPLAY_INVARIANT_FAILURE_6647")
                    ForensicLogger.lifecycle(
                        "JOURNAL_LOT_REPLAY_INVARIANT_FAILURE_6647",
                        "economicEventId=$eventId positionId=${t.positionId} side=${t.side} fillIndex=${t.partialSequence} reason=$reason action=quarantine_exact_event_once",
                    )
                }
            } catch (_: Throwable) {}
        }

        for (t in rows) {
            if (!t.mode.equals("paper", ignoreCase = true)) continue
            val side = t.side.uppercase()
            val eventId = t.economicEventId.ifBlank {
                "LEGACY:${t.positionId}:${t.ts}:$side:${t.partialSequence}"
            }

            // V5.0.6659 — display-only CryptoAlt terminal duplicates are ignored.
            if (t.economicEventId.isBlank() &&
                t.tradingMode.contains("CryptoAlt", ignoreCase = true) &&
                (side == "SELL" || side == "PARTIAL_SELL")
            ) {
                if (reportedReplaySupersessions6699.add("CRYPTO_DISPLAY:$eventId")) {
                    try { PipelineHealthCollector.labelInc("CRYPTO_LEGACY_DISPLAY_ROW_SUPERSEDED_6659") } catch (_: Throwable) {}
                }
                continue
            }

            if (side == "BUY" &&
                t.reason.contains("CROSS_ASSET_CANONICAL_OPEN_6659", ignoreCase = true) &&
                t.positionId in nativeBuyPositions6697
            ) {
                // V5.0.6699 — the row remains economically superseded on every
                // replay, but the INCIDENT is historical and immutable. Emit it
                // once per event instead of hundreds of thousands of times.
                if (reportedReplaySupersessions6699.add("CROSS_ASSET:$eventId")) {
                    try {
                        PipelineHealthCollector.labelInc("JOURNAL_CROSS_ASSET_OPEN_SUPERSEDED_6697")
                        ForensicLogger.lifecycle(
                            "JOURNAL_CROSS_ASSET_OPEN_SUPERSEDED_6697",
                            "positionId=${t.positionId.take(24)} mint=${t.mint.take(10)} eventId=${eventId.take(40)} action=ignore_repair_projection_native_buy_exists_once_6699",
                        )
                    } catch (_: Throwable) {}
                }
                continue
            }

            totalRows++
            if (t.positionId.isBlank()) { reject(t, eventId, "MISSING_POSITION_ID"); continue }
            if (!seenEvents.add(eventId)) { reject(t, eventId, "DUPLICATE_EVENT_ID"); continue }
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
                    val gross = if (t.economicEventId.isNotBlank()) t.grossProceedsSol
                        else t.grossProceedsSol.takeIf { it.isFinite() && it > 0.0 } ?: t.sol
                    val basis = t.soldCostBasisSol
                    val fee = t.feeSol
                    var lot = lots[t.positionId]

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

        try {
            val ledgerCash = PaperCapitalAuthority6577.cashSol()
            val delta = ledgerCash - cash
            ledgerDivergenceLast.set(delta)
            if (kotlin.math.abs(delta) > 0.001) {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619")
                ForensicLogger.lifecycle(
                    "PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619",
                    "ledgerCash=${"%.6f".format(ledgerCash)} journalCash=${"%.6f".format(cash)} " +
                        "delta=${"%.6f".format(delta)} paperRows=$totalRows buys=$buys sells=$sells partials=$partials " +
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
     */
    @Synchronized
    fun repairOrphanedOpenLots6662(): Int {
        val replay = replay()
        if (replay.openBasisByPosition.isEmpty()) return 0
        val allBuys6697 = try {
            TradeHistoryStore.getAllValidTradesSnapshot(limit = 20_000)
                .asSequence()
                .filter { it.mode.equals("paper", true) && it.side.equals("BUY", true) }
                .filter { it.positionId.isNotBlank() }
                .sortedBy { it.ts }
                .toList()
        } catch (_: Throwable) { emptyList() }
        val nativeBuyPositions6697 = allBuys6697.asSequence()
            .filterNot { it.reason.contains("CROSS_ASSET_CANONICAL_OPEN_6659", ignoreCase = true) }
            .map { it.positionId }
            .toSet()
        val buys = allBuys6697
            .filterNot {
                it.reason.contains("CROSS_ASSET_CANONICAL_OPEN_6659", ignoreCase = true) &&
                    it.positionId in nativeBuyPositions6697
            }
            .groupBy { it.positionId }
        var repaired = 0
        replay.openBasisByPosition.forEach { (positionId, basis) ->
            if (!basis.isFinite() || basis <= 1e-9) return@forEach
            val canonical = try { CanonicalPositionAuthority6441.getPosition(positionId) } catch (_: Throwable) { null }
            if (canonical != null) return@forEach
            val positionBuys = buys[positionId].orEmpty()
            val seed = positionBuys.firstOrNull() ?: return@forEach
            val newestBuyAt = positionBuys.maxOfOrNull { it.ts } ?: 0L
            if (System.currentTimeMillis() - newestBuyAt < 10_000L) return@forEach
            val eventId = "PAPER6619:ORPHAN_REFUND:$positionId"
            val raw = replay.openRawQtyByPosition[positionId] ?: java.math.BigInteger.ZERO
            val scale = seed.tokenDecimals.takeIf { it in 0..18 }
                ?: seed.entryDecimals.coerceIn(0, 18)
            val displayQty = try {
                raw.toBigDecimal().movePointLeft(scale).toDouble()
            } catch (_: Throwable) { 0.0 }

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
                    "ledgerDelta=${"%+.4f".format(div)} supersessionIncidents=${reportedReplaySupersessions6699.size}"
             else "result=empty")
    }

    internal fun resetForTest() {
        replays.set(0L); lastResult.set(null); ledgerDivergenceLast.set(0.0)
        reportedInvariantFailures6653.clear()
        reportedEmbeddedEntryRecoveries6664.clear()
        reportedReplaySupersessions6699.clear()
    }
}
