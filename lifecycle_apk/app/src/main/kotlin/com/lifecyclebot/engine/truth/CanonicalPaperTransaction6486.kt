package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.TradeHistoryStore
import com.lifecyclebot.data.Trade
import java.math.BigInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** V5.0.6486 — one typed paper transaction reducer for every trader family. */
object CanonicalPaperTransaction6486 {
    private val lock = ReentrantLock()
    private val syntheticUnit = BigInteger.valueOf(1_000_000_000L)

    /** Background startup reconciliation. Scalars move only after durable
     * journal and canonical raw lots agree exactly. */
    fun reconcileJournalAuthority6663(): Boolean = lock.withLock {
        JournalEconomicReplay6619.repairOrphanedOpenLots6662()
        var replay = JournalEconomicReplay6619.replay()
        if (!replay.reconciled) return@withLock false
        val canonicalRaw = CanonicalPositionAuthority6441.openPositions()
            .filter { it.mode.equals("paper", true) }
            .associate { it.positionId to it.remainingQtyRaw }
        repairJournalQuantityDrift6666(replay.openRawQtyByPosition, canonicalRaw)
        replay = JournalEconomicReplay6619.replay()
        if (canonicalRaw != replay.openRawQtyByPosition) {
            try { PipelineHealthCollector.labelInc("JOURNAL_CANONICAL_RAW_MISMATCH_BLOCKED_6663") } catch (_: Throwable) {}
            return@withLock false
        }
        PaperAccountLedger6430.reconcileFromJournal6663(
            replay.cashSol, replay.openCostBasisSol, replay.realizedPnlSol, replay.feesSol,
        )
    }

    /** Append an immutable raw-quantity correction for old split close paths.
     * It carries no cash, basis, fee or PnL and therefore cannot conceal an
     * economic mismatch; it only makes the journal lot equal the canonical
     * integer quantity which actually remained after the close. */
    private fun repairJournalQuantityDrift6666(
        journalRaw: Map<String, BigInteger>,
        canonicalRaw: Map<String, BigInteger>,
    ) {
        val rows = TradeHistoryStore.getAllValidTradesSnapshot(limit = 20_000)
        canonicalRaw.forEach { (positionId, targetRaw) ->
            val currentRaw = journalRaw[positionId] ?: return@forEach
            if (currentRaw == targetRaw) return@forEach
            val seed = rows.firstOrNull {
                it.mode.equals("paper", true) && it.side.equals("BUY", true) && it.positionId == positionId
            } ?: return@forEach
            val subtract = (currentRaw - targetRaw).coerceAtLeast(BigInteger.ZERO)
            val add = (targetRaw - currentRaw).coerceAtLeast(BigInteger.ZERO)
            val eventId = "PAPER6486:QTY_RECONCILE:$positionId:$currentRaw:$targetRaw"
            PaperEconomicAtomicCommit6632.stampLedger(
                eventId, seed.mint,
                if (subtract > BigInteger.ZERO) PaperEconomicAtomicCommit6632.Side.SELL
                else PaperEconomicAtomicCommit6632.Side.BUY,
                "CanonicalPaperTransaction6486.quantityReconcile6666",
            )
            TradeHistoryStore.recordTrade(Trade(
                side = "QTY_RECONCILE", mode = "paper", sol = 0.0,
                price = seed.entryPriceSnapshot.takeIf { it.isFinite() && it > 0.0 } ?: seed.price,
                ts = System.currentTimeMillis(), reason = "CANONICAL_RAW_QTY_REPAIR_6666",
                mint = seed.mint, proofState = "PAPER_SIMULATED",
                tradingMode = seed.tradingMode, tradingModeEmoji = seed.tradingModeEmoji,
                positionId = positionId, entryTsMs = seed.entryTsMs.takeIf { it > 0L } ?: seed.ts,
                entryPriceSnapshot = seed.entryPriceSnapshot.takeIf { it.isFinite() && it > 0.0 } ?: seed.price,
                entryQtyToken = 0.0, entryCostSol = 0.0,
                entryDecimals = seed.entryDecimals, tokenDecimals = seed.tokenDecimals,
                entryRawQty = add, canonicalConsumedRaw = subtract,
                remainingRawQty = targetRaw, economicEventId = eventId,
            ))
            try {
                ForensicLogger.lifecycle(
                    "JOURNAL_QTY_RECONCILED_TO_CANONICAL_6666",
                    "positionId=$positionId fromRaw=$currentRaw toRaw=$targetRaw subtract=$subtract add=$add",
                )
            } catch (_: Throwable) {}
        }
    }
    data class Result(
        val applied: Boolean,
        val positionId: String,
        val reason: String,
        val economicEventId: String = "",
        val grossProceedsSol: Double = 0.0,
        val soldCostBasisSol: Double = 0.0,
        val feesSol: Double = 0.0,
        val canonicalConsumedRaw: BigInteger = BigInteger.ZERO,
        val preRemainingRaw: BigInteger = BigInteger.ZERO,
        val postRemainingRaw: BigInteger = BigInteger.ZERO,
        val tokenDecimals: Int = -1,
    )

    /**
     * V5.0.6659 — project a cross-asset paper open through the same immutable
     * economic identity used by the canonical position, fill-lot ledger and
     * durable journal.  The old generic open path mutated cash + position but
     * emitted no BUY journal row and no fill-lot row.  Crypto Universe could
     * therefore show funded open positions while UnifiedAccountSnapshot6635
     * correctly failed reconciliation with ACCOUNT_UNAVAILABLE.
     *
     * The identity is stable across process restarts.  TradeHistoryStore and
     * FillLotLedger are independently idempotent, so calling this during both
     * open and rehydrate repairs already-funded legacy positions without a
     * second debit or a duplicate journal row.
     */
    fun ensureOpenProjection6659(position: CanonicalPositionAuthority6441.Position): String {
        if (!position.mode.equals("paper", true) || position.positionId.isBlank() ||
            position.mint.isBlank() || position.entryCostSol <= 0.0 ||
            position.originalQtyRaw <= BigInteger.ZERO
        ) return ""

        val eventId = "PAPER6486:OPEN:${position.positionId}"
        val event = CanonicalEconomicEvent6635.Event(
            economicEventId = eventId,
            positionId = position.positionId,
            mint = position.mint,
            canonicalMint = ExecutorCanonicalMirror6442.canonicalMint(position.mint),
            symbol = position.symbol,
            mode = "paper",
            lane = position.lane,
            side = CanonicalEconomicEvent6635.Side.BUY,
            timestampMs = position.openedAtMs,
            qtyRaw = position.originalQtyRaw,
            decimals = position.quantityScale,
            executionPriceUsd = position.entryPriceUsd,
            executionPriceSol = position.entryPriceUsd,
            notionalSol = position.entryCostSol,
            feeSol = position.feesSol,
            cashDeltaSol = -(position.entryCostSol + position.feesSol),
            positionQtyDeltaRaw = position.originalQtyRaw,
            realizedPnlDeltaSol = 0.0,
            terminalFillIndex = 0,
        )
        CanonicalEconomicEvent6635.openEvent(event) // false means the stable event already exists

        // This projection is also used while rehydrating an already-funded
        // canonical position.  Stamp the witness side without moving cash so
        // the subsequent durable journal insert cannot age into a false
        // JOURNAL_ONLY half-commit.
        PaperEconomicAtomicCommit6632.stampLedger(
            eventId, position.mint, PaperEconomicAtomicCommit6632.Side.BUY,
            "CanonicalPaperTransaction6486.openProjection6659",
        )

        val fillId = FillLotLedger6504.recordBuyFill(
            mint = position.mint,
            lotId = eventId,
            qtyTokenRaw = position.originalQtyRaw,
            lamports = BigInteger.valueOf(
                (position.entryCostSol.coerceAtLeast(0.0) * 1_000_000_000.0).toLong().coerceAtLeast(0L)
            ),
            isPaper = true,
            source = position.lane,
            note = "paperOpen.crossAsset6659",
        )
        listOf(
            CanonicalEconomicEvent6635.Store.POSITION,
            CanonicalEconomicEvent6635.Store.LEDGER,
            CanonicalEconomicEvent6635.Store.TERMINAL_EXEC,
        ).forEach { CanonicalEconomicEvent6635.markCommitted(eventId, it, "CanonicalPaperTransaction6486.open6659") }
        if (fillId > 0L) {
            CanonicalEconomicEvent6635.markCommitted(
                eventId, CanonicalEconomicEvent6635.Store.FILL_LOT,
                "CanonicalPaperTransaction6486.open6659",
            )
        } else {
            try { PipelineHealthCollector.labelInc("CROSS_ASSET_OPEN_FILL_LOT_PENDING_6659") } catch (_: Throwable) {}
        }

        val qtyToken = try {
            position.originalQtyRaw.toBigDecimal().movePointLeft(position.quantityScale.coerceIn(0, 18)).toDouble()
        } catch (_: Throwable) { 0.0 }
        TradeHistoryStore.recordTrade(
            Trade(
                side = "BUY", mode = "paper", sol = position.entryCostSol,
                price = position.entryPriceUsd, ts = position.openedAtMs,
                reason = "CROSS_ASSET_CANONICAL_OPEN_6659",
                score = 0.0, feeSol = position.feesSol,
                tradingMode = position.lane, tradingModeEmoji = "🪙",
                mint = position.mint, proofState = "PAPER_SIMULATED",
                positionId = position.positionId, entryTsMs = position.openedAtMs,
                entryPriceSnapshot = position.entryPriceUsd,
                entryQtyToken = qtyToken, remainingQtyToken = qtyToken,
                entryCostSol = position.entryCostSol,
                entryDecimals = position.quantityScale,
                entryRawQty = position.originalQtyRaw,
                remainingRawQty = position.originalQtyRaw,
                tokenDecimals = position.quantityScale,
                entryPriceSource = position.entryPriceSource,
                entryPoolAddress = position.entryPoolAddress,
                economicEventId = eventId,
            )
        )
        try { PipelineHealthCollector.labelInc("CROSS_ASSET_OPEN_JOURNAL_PROJECTED_6659") } catch (_: Throwable) {}
        return eventId
    }

    /** Restore the durable journal side of historical cross-asset paper events.
     * Legacy Stock/Forex/Commodity/Metal closes wrote no canonical row, while
     * CryptoAlt wrote a display-only row or none on stop. The typed economic
     * sidecar still has the exact basis/proceeds/quantity receipt, so project
     * it rather than inventing values or resetting the operator's account. */
    fun repairCryptoHistory6659() {
        val positions = (CanonicalPositionAuthority6441.openPositions() +
            CanonicalPositionAuthority6441.closedPositions())
            .filter { it.mode.equals("paper", true) && it.assetClass != AssetClass.SOLANA_TOKEN }
            .associateBy { it.positionId }
        if (positions.isEmpty()) return

        positions.values.forEach { ensureOpenProjection6659(it) }
        val typedEvents = EconomicEventSchema6464.snapshot()
            .filter { it.mode.equals("paper", true) && positions.containsKey(it.positionId) }
            .sortedBy { it.atMs }
        val buyByPosition = typedEvents.filterIsInstance<EconomicEventSchema6464.Buy>()
            .associateBy { it.positionId }
        val sellSequence = mutableMapOf<String, Long>()
        typedEvents.filterIsInstance<EconomicEventSchema6464.Sell>().forEach { sell ->
            val position = positions[sell.positionId] ?: return@forEach
            val eventId = sell.idempotencyKey.ifBlank {
                "PAPER6486:SELL:${sell.positionId}:${sell.atMs}"
            }
            val scale = position.quantityScale.coerceIn(0, 18)
            val soldQty = try { sell.soldQty.toBigDecimal().movePointLeft(scale).toDouble() } catch (_: Throwable) { 0.0 }
            val remainingQty = try { sell.remainingQty.toBigDecimal().movePointLeft(scale).toDouble() } catch (_: Throwable) { 0.0 }
            val originalQty = try { position.originalQtyRaw.toBigDecimal().movePointLeft(scale).toDouble() } catch (_: Throwable) { 0.0 }
            val exitPrice = if (soldQty > 0.0) sell.grossProceedsSol / soldQty else position.entryPriceUsd
            val sequence = (sellSequence[sell.positionId] ?: 0L) + 1L
            sellSequence[sell.positionId] = sequence

            CanonicalEconomicEvent6635.openEvent(CanonicalEconomicEvent6635.Event(
                economicEventId = eventId, positionId = sell.positionId,
                mint = sell.mint, canonicalMint = ExecutorCanonicalMirror6442.canonicalMint(sell.mint),
                symbol = sell.symbol, mode = "paper", lane = position.lane,
                side = if (sell.partial) CanonicalEconomicEvent6635.Side.PARTIAL_SELL else CanonicalEconomicEvent6635.Side.SELL,
                timestampMs = sell.atMs, qtyRaw = sell.soldQty, decimals = scale,
                executionPriceUsd = exitPrice, executionPriceSol = exitPrice,
                notionalSol = sell.grossProceedsSol, feeSol = sell.exitFeesSol,
                cashDeltaSol = sell.grossProceedsSol - sell.exitFeesSol,
                positionQtyDeltaRaw = sell.soldQty.negate(),
                realizedPnlDeltaSol = sell.realizedPnlSol - sell.exitFeesSol,
                terminalFillIndex = sequence.toInt(),
            ))
            val fillId = FillLotLedger6504.recordSellFill(
                mint = sell.mint, lotId = eventId, qtyTokenRaw = sell.soldQty,
                lamports = BigInteger.valueOf((sell.grossProceedsSol.coerceAtLeast(0.0) * 1_000_000_000.0).toLong()),
                finalized = true, isPaper = true, source = position.lane,
                note = "cryptoHistory.repair6659",
            )
            listOf(CanonicalEconomicEvent6635.Store.POSITION, CanonicalEconomicEvent6635.Store.LEDGER,
                CanonicalEconomicEvent6635.Store.TERMINAL_EXEC).forEach {
                CanonicalEconomicEvent6635.markCommitted(eventId, it, "repairCryptoHistory6659")
            }
            if (fillId > 0L) CanonicalEconomicEvent6635.markCommitted(
                eventId, CanonicalEconomicEvent6635.Store.FILL_LOT, "repairCryptoHistory6659")
            PaperEconomicAtomicCommit6632.stampLedger(
                eventId, sell.mint,
                if (sell.partial) PaperEconomicAtomicCommit6632.Side.PARTIAL_SELL
                else PaperEconomicAtomicCommit6632.Side.SELL,
                "CanonicalPaperTransaction6486.historyProjection6660",
            )
            val buy = buyByPosition[sell.positionId]
            val grossPnl = sell.grossProceedsSol - sell.allocatedCostBasisSol
            TradeHistoryStore.recordTrade(Trade(
                side = if (sell.partial) "PARTIAL_SELL" else "SELL", mode = "paper",
                sol = sell.grossProceedsSol, price = exitPrice, ts = sell.atMs,
                reason = "CROSS_ASSET_HISTORY_REPAIR_6660", pnlSol = grossPnl,
                pnlPct = sell.realizedReturnPct, feeSol = sell.exitFeesSol,
                netPnlSol = grossPnl - sell.exitFeesSol, tradingMode = position.lane,
                tradingModeEmoji = "🪙", mint = sell.mint, proofState = "PAPER_SIMULATED",
                positionId = sell.positionId, entryTsMs = buy?.atMs ?: position.openedAtMs,
                entryPriceSnapshot = buy?.fillPrice ?: position.entryPriceUsd,
                entryQtyToken = originalQty, entryCostSol = buy?.executedCostSol ?: position.entryCostSol,
                entryDecimals = scale, soldQtyToken = soldQty, remainingQtyToken = remainingQty,
                entryRawQty = position.originalQtyRaw, canonicalConsumedRaw = sell.soldQty,
                remainingRawQty = sell.remainingQty, tokenDecimals = scale,
                partialSequence = sequence, soldCostBasisSol = sell.allocatedCostBasisSol,
                grossProceedsSol = sell.grossProceedsSol, economicEventId = eventId,
            ))
        }
        try {
            PipelineHealthCollector.labelInc("CRYPTO_HISTORY_REPROJECTED_6659")
            PipelineHealthCollector.labelInc("CROSS_ASSET_HISTORY_REPROJECTED_6660")
        } catch (_: Throwable) {}
    }

    fun open(positionId: String, mint: String, symbol: String, lane: String, source: String,
             costSol: Double, feeSol: Double = 0.0, qtyRaw: BigInteger = syntheticUnit,
             decimals: Int = 9, entryScore: Int = 0, tactic: String = lane,
             quantityScale: Int = decimals,
             // V5.0.6525 §ASSET_CLASS_AXIS + §ENTRY_PRICE_PROPAGATION —
             // Operator audit Feb 2026: the paper bridge threw away
             // signal.price and forced 1e9 qty @ 9 decimals on every
             // asset class. ForexTrader.open() then produced canonical
             // rows with entryPriceUsd=0.0, mark=0.0, and the exit
             // scheduler queued Birdeye lookups on "GBPJPY". Accept the
             // asset class, the real entry price, and the price-source
             // metadata so the canonical row is economically valid on
             // non-Solana assets. Defaults preserve the pre-6525 SOL
             // token behaviour.
             assetClass: AssetClass = AssetClass.SOLANA_TOKEN,
             entryPriceUsd: Double = 0.0,
             entryPriceSource: String = "",
             entryPoolAddress: String = "",
             entryDex: String = "",
             executionIntent: com.lifecyclebot.engine.ExecutableOpenGate.ExecutionIntent? = null): Result = lock.withLock {
        // V5.0.6551 — every non-Solana paper open must be authorized before
        // debit. Missing/mismatched intent is rejected without mutation.
        if (assetClass != AssetClass.SOLANA_TOKEN) {
            val intent = executionIntent ?: CanonicalEntryAuthority6551.findPending(mint, "PAPER")
                ?: return@withLock Result(false, positionId, "MISSING_CANONICAL_EXECUTION_INTENT")
            if (intent.assetClassTag != assetClass.tag || intent.mint != mint || intent.candidateVersion <= 0L || !intent.fdgAllowed ||
                intent.authoritativeSignal.uppercase() != "BUY" ||
                intent.fdgVerdict.uppercase() !in setOf("BUY", "PROBE_ONLY") ||
                intent.resolvedSize <= 0.0 || kotlin.math.abs(intent.resolvedSize - costSol) > 1e-9 ||
                intent.mode.uppercase() != "PAPER")
                return@withLock Result(false, positionId, "CANONICAL_EXECUTION_INTENT_MISMATCH")
            CanonicalEntryAuthority6551.markDispatch(intent)
        }
        if (positionId.isBlank() || mint.isBlank() || !costSol.isFinite() || costSol <= 0.0 ||
            !feeSol.isFinite() || feeSol < 0.0 || qtyRaw <= BigInteger.ZERO)
            return@withLock Result(false, positionId, "INVALID_OPEN")
        if (CanonicalPositionAuthority6441.getPosition(positionId) != null)
            return@withLock Result(false, positionId, "POSITION_EXISTS")
        // V5.0.6605 §REPAIR_H (operator directive Feb 2026 — CANONICAL SAME-MINT OCCUPANCY):
        //   Operator forensic V5.0.6604 dump captured D1cdMQ opened in QUALITY
        //   at 01:10:43 then again ~15s later in PROJECT_SNIPER — despite
        //   `SameMintDedupAuthority6441` reporting raw=97 accepts=97
        //   coalesces=0 blocks=0 across 81 canonical opens. The scan-time
        //   dedup gate correctly rejects duplicates WITHIN a single scan
        //   cycle, but the specialist election → sizing → executor path
        //   can accept a second candidate for the same mint after the
        //   first has already opened (different positionId, same mint).
        //   Bind the invariant HERE at the canonical reducer: if the mint
        //   already has an OPEN canonical position, refuse a second open
        //   and emit CANONICAL_SAME_MINT_OCCUPANCY_BLOCK_6605 so the
        //   contributor lane can be routed to "influence existing
        //   position" or discarded. This is the last write barrier
        //   before capital debit; nothing downstream can bypass it.
        //   Different execution modes (paper vs live) are still allowed
        //   to co-exist per (mode, mint) — the check is scoped to the
        //   same runtime mode as the incoming open.
        val incomingMode6605 = "paper"
        val duplicateOpenSameMode6605 = try {
            CanonicalPositionAuthority6441.openPositions().any { p ->
                p.mint == mint && p.mode.equals(incomingMode6605, ignoreCase = true)
            }
        } catch (_: Throwable) { false }
        if (duplicateOpenSameMode6605) {
            try {
                PipelineHealthCollector.labelInc("CANONICAL_SAME_MINT_OCCUPANCY_BLOCK_6605")
                PipelineHealthCollector.labelInc("CANONICAL_SAME_MINT_OCCUPANCY_BLOCK_6605_${lane.uppercase()}")
                ForensicLogger.lifecycle(
                    "CANONICAL_SAME_MINT_OCCUPANCY_BLOCK_6605",
                    "mint=${mint.take(10)} lane=$lane positionId=${positionId.take(24)} " +
                        "mode=$incomingMode6605 action=refuse_duplicate_open",
                )
            } catch (_: Throwable) {}
            return@withLock Result(false, positionId, "CANONICAL_SAME_MINT_ALREADY_OPEN_POSITION_6605")
        }
        val idem = "PAPER6486:OPEN:$positionId"
        // Use the same immutable event id on the ledger and journal sides.
        // The legacy onBuy() call supplied a blank key while
        // ensureOpenProjection6659 journaled PAPER6486:OPEN:<positionId>,
        // manufacturing a JOURNAL_ONLY half-commit for every cross-asset open.
        if (!PaperAccountLedger6430.onBuyAtomic6632(costSol, feeSol, mint, idem))
            return@withLock Result(false, positionId, "INSUFFICIENT_CANONICAL_CASH")
        val opened = CanonicalPositionAuthority6441.openPosition(
            idempotencyKey = idem, positionId = positionId, mint = mint, symbol = symbol,
            lane = lane, runId = positionId.substringAfterLast(':', positionId),
            entryCostSol = costSol, openedQtyRaw = qtyRaw, tokenDecimals = decimals,
            feesSol = feeSol, paperMode = false, modeOverride = "paper", quantityScale = quantityScale,
            entryPriceUsd = entryPriceUsd, entryPriceSource = entryPriceSource,
            entryPoolAddress = entryPoolAddress, entryDex = entryDex,
            assetClass = assetClass)
        if (opened != CanonicalPositionAuthority6441.MutateResult.APPLIED) {
            PaperAccountLedger6430.rollbackBuy(costSol, feeSol, "PAPER6486_OPEN_$opened")
            return@withLock Result(false, positionId, "POSITION_$opened")
        }
        CanonicalLotQuantity6464.onBuyFilled(positionId, mint, qtyRaw)
        PositionStateLedger6454.onEntry(positionId)
        SellQtyBoundaryClamp6427.syncAuthoritativeRaw(positionId, qtyRaw, qtyRaw)
        EconomicEventSchema6464.recordBuy("paper", positionId, mint, symbol, idem, costSol,
            qtyRaw, costSol / qtyRaw.toDouble(), feeSol, decimals, quantityScale)
        EntryStrategySnapshot6450.setEntry(EntryStrategySnapshot6450.Snapshot(
            positionId, mint, lane, "", tactic, "", "", source, entryScore, 0.0, 0.0,
            System.currentTimeMillis(), "",
            entryMarketRegime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "UNKNOWN" },
            assetClassTag = assetClass.tag))
        CanonicalMintOccupancyRegistry6464.markOpen("paper", mint, symbol, source)
        CanonicalPositionAuthority6441.getPosition(positionId)?.let { ensureOpenProjection6659(it) }
        try { PipelineHealthCollector.labelInc("PAPER_TRANSACTION_OPEN_COMMITTED_6486") } catch (_: Throwable) {}
        // V5.0.6551 — intent/dispatch were sealed before debit; only the
        // successful canonical commit emits OPEN_CONFIRMED.
        if (assetClass != AssetClass.SOLANA_TOKEN) {
            // Confirm the exact immutable intent supplied by the caller. A
            // mint/mode lookup can select a different concurrent attempt and
            // break the one-intent/one-terminal invariant.
            executionIntent?.let { CanonicalEntryAuthority6551.markConfirmed(it, positionId) }
        }
        // Canonical BUY projection — one journal event per canonical open.
        // It is emitted only after the authority-backed commit succeeds.
        try {
            PipelineHealthCollector.labelInc("CANONICAL_BUY_JOURNAL_PROJECTED_6543")
            ForensicLogger.lifecycle(
                "CANONICAL_BUY_JOURNAL_PROJECTED_6543",
                "assetClass=${assetClass.tag} symbol=$symbol positionId=$positionId costSol=$costSol entryPriceUsd=$entryPriceUsd source=$source",
            )
        } catch (_: Throwable) {}
        Result(true, positionId, "OPEN_COMMITTED")
    }

    fun add(positionId: String, mint: String, symbol: String, addedCostSol: Double,
            addedFeeSol: Double = 0.0, addedQtyRaw: BigInteger = syntheticUnit,
            // V5.0.6539 §TOP_UP_ATOMICITY — accept the fill's authoritative
            // USD/token price so canonical row weighted-average USD entry
            // basis is updated in the SAME atomic mutation, and the
            // durable economic-event fillPrice is USD/token rather than
            // SOL/raw. Defaults to 0.0 (skip rewrite; pre-6539 semantics).
            addedEntryPriceUsd: Double = 0.0,
            quantityScale: Int = 9): Result = lock.withLock {
        val pos = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return@withLock Result(false, positionId, "UNKNOWN_POSITION")
        if (!addedCostSol.isFinite() || addedCostSol <= 0.0 || addedQtyRaw <= BigInteger.ZERO)
            return@withLock Result(false, positionId, "INVALID_ADD")
        val idem = "PAPER6486:ADD:$positionId:${pos.originalQtyRaw}"
        // Top-ups are full economic fills, not an in-memory position detail.
        // Key ledger debit and journal projection with the same immutable id.
        if (!PaperAccountLedger6430.onBuyAtomic6632(addedCostSol, addedFeeSol, mint, idem))
            return@withLock Result(false, positionId, "INSUFFICIENT_CANONICAL_CASH")
        val applied = CanonicalPositionAuthority6441.addToPosition6486(
            idem, positionId, addedCostSol, addedQtyRaw, addedFeeSol, addedEntryPriceUsd)
        if (applied != CanonicalPositionAuthority6441.MutateResult.APPLIED) {
            PaperAccountLedger6430.rollbackBuy(addedCostSol, addedFeeSol, "PAPER6486_ADD_$applied")
            return@withLock Result(false, positionId, "POSITION_$applied")
        }
        CanonicalLotQuantity6464.onBuyFilled(positionId, mint, addedQtyRaw)
        CanonicalPositionAuthority6441.getPosition(positionId)?.let { updated6498 ->
            PositionStateLedger6454.onEntry(positionId)
            SellQtyBoundaryClamp6427.syncAuthoritativeRaw(positionId, updated6498.originalQtyRaw, updated6498.remainingQtyRaw)
        }
        // V5.0.6539 §DURABLE_ECONOMIC_EVENT — fillPrice is USD/token when
        // available so replay reproduces the same weighted USD basis
        // (previously we recorded SOL/rawUnit which is a nonsense unit and
        // cannot be replayed into a USD-basis position).
        val fillPrice6539 = if (addedEntryPriceUsd > 0.0 && addedEntryPriceUsd.isFinite())
            addedEntryPriceUsd else addedCostSol / addedQtyRaw.toDouble()
        EconomicEventSchema6464.recordBuy("paper", positionId, mint, symbol, idem, addedCostSol,
            addedQtyRaw, fillPrice6539, addedFeeSol, tokenDecimals = quantityScale, quantityScale = quantityScale)
        val updated = CanonicalPositionAuthority6441.getPosition(positionId)
        fun displayAdded(raw: BigInteger): Double = try {
            raw.toBigDecimal().movePointLeft(quantityScale.coerceIn(0, 18)).toDouble()
        } catch (_: Throwable) { 0.0 }
        TradeHistoryStore.recordTrade(Trade(
            side = "BUY", mode = "paper", sol = addedCostSol,
            price = fillPrice6539.coerceAtLeast(0.000000000001),
            ts = System.currentTimeMillis(), reason = "CANONICAL_POSITION_ADD_6660",
            feeSol = addedFeeSol, tradingMode = pos.lane, tradingModeEmoji = "🪙",
            mint = mint, proofState = "PAPER_SIMULATED",
            positionId = positionId, entryTsMs = pos.openedAtMs,
            entryPriceSnapshot = fillPrice6539.coerceAtLeast(0.000000000001),
            entryQtyToken = displayAdded(addedQtyRaw),
            remainingQtyToken = displayAdded(updated?.remainingQtyRaw ?: BigInteger.ZERO),
            entryCostSol = addedCostSol, entryDecimals = quantityScale,
            entryRawQty = addedQtyRaw,
            remainingRawQty = updated?.remainingQtyRaw ?: BigInteger.ZERO,
            tokenDecimals = quantityScale,
            entryPriceSource = pos.entryPriceSource,
            entryPoolAddress = pos.entryPoolAddress,
            economicEventId = idem,
        ))
        try { PipelineHealthCollector.labelInc("PAPER_TRANSACTION_ADD_COMMITTED_6486") } catch (_: Throwable) {}
        Result(true, positionId, "ADD_COMMITTED")
    }

    data class PartialResult(
        val applied: Boolean, val positionId: String, val reason: String,
        val operationId: String = "", val partialSequence: Long = 0L,
        val remainingCostSol: Double = 0.0, val realizedPnlSol: Double = 0.0,
    )

    /** V5.0.6566 — typed cross-asset partial. Canonical receipt commits first;
     * local trader maps may mirror remainingCostSol only when applied=true. */
    fun partial(positionId: String, mint: String, symbol: String, fraction: Double,
                currentPnlPct: Double, feeRate: Double, exitReason: String): PartialResult = lock.withLock {
        val eligibility6570 = CanonicalPositionAuthority6441.exitEligibility6570(positionId, mint, expectedMode = "paper")
        val pos = eligibility6570.position
            ?: return@withLock PartialResult(false, positionId, eligibility6570.reason)
        if (!eligibility6570.eligible) return@withLock PartialResult(false, positionId, eligibility6570.reason)
        if (fraction <= 0.0 || fraction >= 1.0 ||
            !currentPnlPct.isFinite() || !feeRate.isFinite() || feeRate < 0.0)
            return@withLock PartialResult(false, positionId, "INVALID_PARTIAL")
        val remainingBasis = (pos.entryCostSol - pos.soldCostBasisSol).coerceAtLeast(0.0)
        val soldBasis = remainingBasis * fraction
        val grossProceeds = (soldBasis * (1.0 + currentPnlPct / 100.0)).coerceAtLeast(0.0)
        val fees = grossProceeds * feeRate
        val receipt = CanonicalPaperPartialOperation6510.commit(
            positionId, mint, symbol, fraction, grossProceeds, fees, exitReason,
        )
        if (!receipt.applied) return@withLock PartialResult(false, positionId, receipt.reason,
            receipt.operationId, receipt.partialSequence, receipt.postCost, receipt.realizedPnl)
        try { PipelineHealthCollector.labelInc("PAPER_TRANSACTION_PARTIAL_COMMITTED_6566") } catch (_: Throwable) {}
        PartialResult(true, positionId, receipt.reason, receipt.operationId,
            receipt.partialSequence, receipt.postCost, receipt.realizedPnl)
    }

    fun close(positionId: String, mint: String, symbol: String, grossProceedsSol: Double,
              soldQtyRaw: BigInteger? = null, soldCostBasisSol: Double? = null,
              sellFeeSol: Double = 0.0, exitReason: String, terminalSequence: Long,
              expectedRealizedPnlSol6569: Double? = null, leveragedReturnPct6569: Double? = null): Result = lock.withLock {
        val eligibility6570 = CanonicalPositionAuthority6441.exitEligibility6570(positionId, mint, expectedMode = "paper")
        val pos = eligibility6570.position
            ?: return@withLock Result(false, positionId, eligibility6570.reason)
        if (!eligibility6570.eligible) return@withLock Result(false, positionId, eligibility6570.reason)
        val qty = soldQtyRaw ?: pos.remainingQtyRaw
        val basis = soldCostBasisSol ?: (pos.entryCostSol - pos.soldCostBasisSol).coerceAtLeast(0.0)
        if (qty <= BigInteger.ZERO || qty > pos.remainingQtyRaw || !grossProceedsSol.isFinite() ||
            grossProceedsSol < 0.0 || !basis.isFinite() || basis < 0.0)
            return@withLock Result(false, positionId, "INVALID_CLOSE")
        val terminal = qty >= pos.remainingQtyRaw
        val canonicalRealizedPnl6569 = grossProceedsSol - basis - sellFeeSol
        val expected6569 = expectedRealizedPnlSol6569
        val return6569 = leveragedReturnPct6569
        val tolerance6569 = maxOf(0.000001, kotlin.math.abs(expected6569 ?: 0.0) * 0.02)
        val arithmeticDivergence6569 = expected6569 != null && kotlin.math.abs(canonicalRealizedPnl6569 - expected6569) > tolerance6569
        val impossibleZero6569 = return6569 != null && kotlin.math.abs(return6569) > 5.0 && kotlin.math.abs(canonicalRealizedPnl6569) < 0.0005
        if (arithmeticDivergence6569 || impossibleZero6569) {
            CanonicalPerformanceFilter6395.quarantine(positionId, CanonicalPerformanceFilter6395.QuarantineReason.REPLAY_UNIT_MISMATCH)
            PaperLearningEligibility6519.record(mint, positionId, false, "LEVERAGED_TERMINAL_ARITHMETIC_DIVERGENCE_6569")
            try {
                PipelineHealthCollector.labelInc("LEVERAGED_TERMINAL_ARITHMETIC_DIVERGENCE_6569")
                ForensicLogger.lifecycle("LEVERAGED_TERMINAL_ARITHMETIC_DIVERGENCE_6569", "positionId=$positionId symbol=$symbol basis=$basis gross=$grossProceedsSol fee=$sellFeeSol expected=$expected6569 realized=$canonicalRealizedPnl6569 returnPct=$return6569 action=settle_but_quarantine_learning")
            } catch (_: Throwable) {}
        }
        val r = CanonicalPaperTerminalBridge6469.finalizeSell(
            positionId = positionId, mint = mint, symbol = symbol,
            generation = pos.openedAtMs, sellSig = "PAPER6486:$positionId:$terminalSequence",
            soldQtyRaw = qty, preRemainingRaw = pos.remainingQtyRaw,
            preRemainingCostBasisSol = (pos.entryCostSol - pos.soldCostBasisSol).coerceAtLeast(0.0),
            grossProceedsSol = grossProceedsSol, soldCostBasisSol = basis,
            feesSol = sellFeeSol, lane = pos.lane, exitReason = exitReason,
            terminal = terminal, directPositionMutation6486 = true,
        )
        if (!r.applied) return@withLock Result(false, positionId, r.reason)
        // Journal the exact canonical receipt here, before returning to the
        // asset-specific trader.  That keeps fast shutdown and every normal
        // close on one transaction path; callers can no longer mutate the
        // ledger and then return before writing the matching journal row.
        recordCloseProjection6659(pos, r, exitReason, terminal)
        if (terminal) CanonicalMintOccupancyRegistry6464.markClosed("paper", mint)
        try { PipelineHealthCollector.labelInc(if (terminal) "PAPER_TRANSACTION_CLOSE_COMMITTED_6486" else "PAPER_TRANSACTION_PARTIAL_COMMITTED_6486") } catch (_: Throwable) {}
        Result(
            applied = true,
            positionId = positionId,
            reason = if (terminal) "CLOSE_COMMITTED" else "PARTIAL_COMMITTED",
            economicEventId = r.economicEventId,
            grossProceedsSol = r.grossProceedsSol,
            soldCostBasisSol = r.soldCostBasisSol,
            feesSol = r.feesSol,
            canonicalConsumedRaw = r.canonicalConsumedRaw,
            preRemainingRaw = r.preRemainingRaw,
            postRemainingRaw = r.postRemainingRaw,
            tokenDecimals = r.tokenDecimals,
        )
    }

    private fun recordCloseProjection6659(
        position: CanonicalPositionAuthority6441.Position,
        receipt: CanonicalPaperTerminalBridge6469.Result,
        exitReason: String,
        terminal: Boolean,
    ) {
        if (receipt.economicEventId.isBlank()) return
        val scale = receipt.tokenDecimals.takeIf { it in 0..18 }
            ?: position.quantityScale.coerceIn(0, 18)
        fun tokenQty(raw: BigInteger): Double = try {
            raw.toBigDecimal().movePointLeft(scale).toDouble()
        } catch (_: Throwable) { 0.0 }
        val basis = receipt.soldCostBasisSol
        val gross = receipt.grossProceedsSol
        val fee = receipt.feesSol
        val realized = gross - basis - fee
        val pnlPct = if (basis > 0.0) realized * 100.0 / basis else 0.0
        val soldQty = tokenQty(receipt.canonicalConsumedRaw)
        val exitPrice = if (soldQty > 0.0) gross / soldQty else position.entryPriceUsd
        TradeHistoryStore.recordTrade(
            Trade(
                side = if (terminal) "SELL" else "PARTIAL_SELL",
                mode = "paper",
                sol = gross,
                price = exitPrice,
                ts = System.currentTimeMillis(),
                reason = exitReason,
                pnlSol = realized,
                pnlPct = pnlPct,
                feeSol = fee,
                netPnlSol = realized,
                tradingMode = position.lane,
                tradingModeEmoji = "🪙",
                mint = position.mint,
                proofState = "PAPER_SIMULATED",
                positionId = position.positionId,
                entryTsMs = position.openedAtMs,
                entryPriceSnapshot = position.entryPriceUsd,
                entryQtyToken = tokenQty(position.originalQtyRaw),
                entryCostSol = position.entryCostSol,
                entryDecimals = scale,
                soldQtyToken = soldQty,
                remainingQtyToken = tokenQty(receipt.postRemainingRaw),
                entryRawQty = position.originalQtyRaw,
                canonicalConsumedRaw = receipt.canonicalConsumedRaw,
                remainingRawQty = receipt.postRemainingRaw,
                tokenDecimals = scale,
                soldCostBasisSol = basis,
                grossProceedsSol = gross,
                economicEventId = receipt.economicEventId,
            )
        )
        try {
            PipelineHealthCollector.labelInc("CROSS_ASSET_ROUND_TRIP_JOURNAL_COMMITTED_6660")
        } catch (_: Throwable) {}
    }

    fun refund(positionId: String, reason: String): Result {
        val pos = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return Result(false, positionId, "NO_CANONICAL_DEBIT")
        if (pos.mode != "paper") return Result(false, positionId, "NOT_PAPER")
        return refund(positionId, pos.mint, pos.symbol, reason)
    }

    fun refund(positionId: String, mint: String, symbol: String, reason: String): Result {
        val pos = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return Result(false, positionId, "NO_CANONICAL_DEBIT")
        val basis = (pos.entryCostSol - pos.soldCostBasisSol).coerceAtLeast(0.0)
        return close(positionId, mint, symbol, basis, pos.remainingQtyRaw, basis, 0.0,
            "REFUND:$reason", System.currentTimeMillis())
    }
    data class DuplicateMintRepair6490(val duplicateMints: Int, val refundedLots: Int, val refundedBasisSol: Double, val failures: Int)

    /**
     * V5.0.6490 — startup correction for historical same-mint paper opens.
     * Keep the earliest funded position; refund every alias lot at remaining
     * basis (zero strategy PnL) and suppress learning. This restores deployable
     * cash without inventing profit or deleting economic history.
     */
    fun refundDuplicateActiveMintLots6490(): DuplicateMintRepair6490 {
        val groups = CanonicalPositionAuthority6441.openPositions()
            .filter { it.mode == "paper" && it.remainingQtyRaw > BigInteger.ZERO }
            .groupBy { it.mint }
            .filterValues { it.size > 1 }
        var refunded = 0; var failures = 0; var basisTotal = 0.0
        groups.values.forEach { lots ->
            val keep = lots.minWithOrNull(compareBy<CanonicalPositionAuthority6441.Position> { it.openedAtMs }.thenBy { it.positionId })
            lots.filter { it.positionId != keep?.positionId }.forEach { pos ->
                val basis = (pos.entryCostSol - pos.soldCostBasisSol).coerceAtLeast(0.0)
                val result = CanonicalPaperTerminalBridge6469.finalizeSell(
                    positionId = pos.positionId, mint = pos.mint, symbol = pos.symbol,
                    generation = pos.openedAtMs,
                    sellSig = "INVENTORY_CORRECTION_6490:${pos.positionId}",
                    soldQtyRaw = pos.remainingQtyRaw, preRemainingRaw = pos.remainingQtyRaw,
                    preRemainingCostBasisSol = basis, grossProceedsSol = basis,
                    soldCostBasisSol = basis, feesSol = 0.0, lane = pos.lane,
                    exitReason = "DUPLICATE_SAME_MINT_REFUND_6490", terminal = true,
                    directPositionMutation6486 = true, suppressLearningFanout6490 = true,
                )
                if (result.applied) { refunded++; basisTotal += basis } else failures++
            }
        }
        if (groups.isNotEmpty()) try {
            PipelineHealthCollector.labelInc("DUPLICATE_SAME_MINT_INVENTORY_REPAIRED_6490")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("DUPLICATE_SAME_MINT_INVENTORY_REPAIRED_6490", "duplicateMints=${groups.size} refundedLots=$refunded refundedBasis=${"%.6f".format(basisTotal)} failures=$failures")
        } catch (_: Throwable) {}
        return DuplicateMintRepair6490(groups.size, refunded, basisTotal, failures)
    }


}
