package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * V5.0.6441 §1 — CANONICAL POSITION + CAPITAL AUTHORITY.
 *
 * OPERATOR (V5.0.6441 mandate §1):
 *   "Establish ONE authoritative position/account state used by PAPER
 *    executor, LIVE executor, exit engine, slot accounting, journal,
 *    learner, reconciler, UI/reporting and recovery. No subsystem may
 *    maintain an independently mutable shadow truth."
 *
 * This module is the SINGLE authoritative store for:
 *   • per-position state (quantity, cost basis, lifecycle)
 *   • account cash (paper + live) — cash cannot go negative
 *   • the mutation gate — every mutation must supply an idempotency key
 *
 * DESIGN
 * ──────
 * • Positions are keyed by canonical positionId ("$mint#$runIdShort").
 * • Every mutation (buy, partial-sell, full-sell, quarantine) is
 *   serialised through a single ReentrantLock and gated on an
 *   idempotency key so a replayed callback CANNOT double-mutate.
 * • Cash is a single BigDecimal-flavoured Double (SOL) — writes must
 *   pass the cash floor invariant (>= 0 in paper mode).
 * • Sibling stores (GlobalTradeRegistry, PortfolioStore6405,
 *   PositionCloseLedger, PaperAccountLedger) are read-only mirrors
 *   from V5.0.6441 forward; V5.0.6442+ ships migrate their writers.
 *
 * This is the PRODUCER. Consumers query via read-only accessors.
 * Writers must go through the mutate*() methods.
 */
object CanonicalPositionAuthority6441 {

    enum class Lifecycle { PENDING_ENTRY, OPEN, PARTIALLY_CLOSED, CLOSED, QUARANTINED }

    data class Position(
        val positionId: String,
        val mode: String,
        val mint: String,
        val symbol: String,
        val lane: String,
        val runId: String,
        val openedAtMs: Long,
        val entryCostSol: Double,
        val remainingQtyRaw: BigInteger,
        val originalQtyRaw: BigInteger,
        val soldCostBasisSol: Double,
        val realizedPnlSol: Double,
        val realizedProceedsSol: Double,
        val feesSol: Double,
        val tokenDecimals: Int,
        /** Decimal-neutral PAPER raw storage scale. This is NOT mint metadata. */
        val quantityScale: Int = tokenDecimals,
        val lifecycle: Lifecycle,
        val lastMutationMs: Long,
        val quarantineReason: String,
        val entryPriceUsd: Double = 0.0,
        val entryPriceSource: String = "",
        val entryPoolAddress: String = "",
        val entryDex: String = "",
        // V5.0.6525 §ASSET_CLASS_AXIS — one axis every canonical lifecycle,
        // mark refresh, and telemetry path can dispatch on. Written at open,
        // never mutated. Defaults to SOLANA_TOKEN so historical positions
        // (pre-6525) reload with the correct implicit class.
        val assetClass: AssetClass = AssetClass.SOLANA_TOKEN,
    )

    enum class MutateResult { APPLIED, DUPLICATE, INVARIANT_VIOLATION, UNKNOWN_POSITION, LIFECYCLE_FORBIDDEN }

    private val positions = ConcurrentHashMap<String, Position>()
    private val mutationKeys = ConcurrentHashMap<String, Long>()   // key -> whenMs (idempotency)
    private val lock = ReentrantLock()

    private val paperCashSol = AtomicReference<Double>(0.0)
    private val paperCashInitialisedMs = AtomicLong(0L)
    // Live cash is read from WalletManager; we only track the last observed value
    // for the acceptance-invariant audit.
    private val liveCashObservedSol = AtomicReference<Double>(0.0)

    // Telemetry counters.
    private val muts = AtomicLong(0L)
    private val duplicates = AtomicLong(0L)
    private val invariantViolations = AtomicLong(0L)
    private val quarantines = AtomicLong(0L)

    // ─── Cash authority ────────────────────────────────────────────────────

    fun paperCashSol(): Double = paperCashSol.get()

    fun setPaperCash(sol: Double, reason: String) {
        if (sol < 0.0) {
            invariantViolations.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_CASH_INVARIANT_VIOLATION_6441",
                    "attempt=setPaperCash sol=${"%.6f".format(sol)} reason=$reason — REJECTED",
                )
            } catch (_: Throwable) {}
            return
        }
        paperCashSol.set(sol)
        if (paperCashInitialisedMs.get() == 0L) paperCashInitialisedMs.set(System.currentTimeMillis())
    }

    fun canAffordPaperBuy(sizeSol: Double): Boolean = paperCashSol.get() >= sizeSol

    fun observeLiveCash(sol: Double) { if (sol >= 0.0) liveCashObservedSol.set(sol) }

    // ─── Position mutation gate ────────────────────────────────────────────

    fun openPosition(
        idempotencyKey: String,
        positionId: String,
        mint: String,
        symbol: String,
        lane: String,
        runId: String,
        entryCostSol: Double,
        openedQtyRaw: BigInteger,
        tokenDecimals: Int,
        feesSol: Double,
        paperMode: Boolean,
        modeOverride: String? = null,
        entryPriceUsd: Double = 0.0,
        entryPriceSource: String = "",
        entryPoolAddress: String = "",
        entryDex: String = "",
        quantityScale: Int = tokenDecimals,
        // V5.0.6525 §ASSET_CLASS_AXIS — accept the asset class at open so
        // downstream mark/exit routers do not have to guess from the mint
        // string. Defaults to SOLANA_TOKEN (backwards-compatible with
        // pre-6525 callers).
        assetClass: AssetClass = AssetClass.SOLANA_TOKEN,
    ): MutateResult {
        lock.lock()
        try {
            if (isDuplicate(idempotencyKey)) return MutateResult.DUPLICATE
            val canonicalMode6490 = modeOverride?.trim()?.lowercase()?.takeIf { it in setOf("paper", "live") }
                ?: if (paperMode) "paper" else "live"
            val existingSameMint6490 = positions.values.firstOrNull {
                it.mode == canonicalMode6490 && it.mint == mint &&
                    it.lifecycle in setOf(Lifecycle.PENDING_ENTRY, Lifecycle.OPEN, Lifecycle.PARTIALLY_CLOSED) &&
                    (it.lifecycle == Lifecycle.PENDING_ENTRY || it.remainingQtyRaw > BigInteger.ZERO)
            }
            if (existingSameMint6490 != null && existingSameMint6490.positionId != positionId) {
                duplicates.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("CANONICAL_SAME_MODE_MINT_OPEN_REJECTED_6490")
                    ForensicLogger.lifecycle("CANONICAL_SAME_MODE_MINT_OPEN_REJECTED_6490", "mode=$canonicalMode6490 mint=${mint.take(10)} existingPid=${existingSameMint6490.positionId.take(20)} rejectedPid=${positionId.take(20)} action=use_explicit_add")
                } catch (_: Throwable) {}
                return MutateResult.DUPLICATE
            }
            if (entryCostSol < 0.0 || openedQtyRaw < BigInteger.ZERO) {
                invariantViolations.incrementAndGet()
                return MutateResult.INVARIANT_VIOLATION
            }
            if (paperMode) {
                val prevCash = paperCashSol.get()
                if (prevCash < entryCostSol + feesSol) {
                    invariantViolations.incrementAndGet()
                    try {
                        ForensicLogger.lifecycle(
                            "CANONICAL_INSUFFICIENT_PAPER_CASH_6441",
                            "positionId=$positionId cash=${"%.5f".format(prevCash)} needed=${"%.5f".format(entryCostSol + feesSol)}",
                        )
                    } catch (_: Throwable) {}
                    return MutateResult.INVARIANT_VIOLATION
                }
                paperCashSol.set(prevCash - entryCostSol - feesSol)
            }
            val lifecycle = if (openedQtyRaw == BigInteger.ZERO)
                Lifecycle.PENDING_ENTRY else Lifecycle.OPEN
            positions[positionId] = Position(
                positionId = positionId, mode = canonicalMode6490,
                mint = mint, symbol = symbol, lane = lane, runId = runId,
                openedAtMs = System.currentTimeMillis(),
                entryCostSol = entryCostSol,
                remainingQtyRaw = openedQtyRaw,
                originalQtyRaw = openedQtyRaw,
                soldCostBasisSol = 0.0,
                realizedPnlSol = 0.0,
                realizedProceedsSol = 0.0,
                feesSol = feesSol,
                tokenDecimals = tokenDecimals,
                quantityScale = quantityScale,
                lifecycle = lifecycle,
                lastMutationMs = System.currentTimeMillis(),
                quarantineReason = "",
                entryPriceUsd = entryPriceUsd,
                entryPriceSource = entryPriceSource,
                entryPoolAddress = entryPoolAddress,
                entryDex = entryDex,
                assetClass = assetClass,
            )
            markKeyUsed(idempotencyKey)
            try { AateDecisionFabric6512.attachPosition(positionId, canonicalMode6490, mint, lane) } catch (_: Throwable) {}
            muts.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc(
                    if (lifecycle == Lifecycle.PENDING_ENTRY) "CANONICAL_POSITION_PENDING_6441"
                    else "CANONICAL_POSITION_OPEN_6441",
                )
            } catch (_: Throwable) {}
            return MutateResult.APPLIED
        } finally { lock.unlock() }
    }

    /**
     * V5.0.6442 — promote a PENDING_ENTRY to OPEN once the fill is known.
     * Idempotent: subsequent calls overwrite qty/cost with the observed fill.
     * Callers that never went through openPosition first are auto-upgraded.
     */
    fun promotePendingToOpen(
        positionId: String,
        actualQtyRaw: BigInteger,
        actualEntryCostSol: Double,
        actualFeesSol: Double,
        tokenDecimals: Int,
        paperMode: Boolean,
        quantityScale: Int = tokenDecimals,
    ): MutateResult {
        lock.lock()
        try {
            val prev = positions[positionId]
            if (prev == null) {
                // Never saw an open; caller should have used openPosition — refuse.
                invariantViolations.incrementAndGet()
                return MutateResult.UNKNOWN_POSITION
            }
            if (prev.lifecycle == Lifecycle.CLOSED || prev.lifecycle == Lifecycle.QUARANTINED) {
                return MutateResult.LIFECYCLE_FORBIDDEN
            }
            if (actualQtyRaw <= BigInteger.ZERO || actualEntryCostSol < 0.0) {
                invariantViolations.incrementAndGet()
                return MutateResult.INVARIANT_VIOLATION
            }
            // Cash adjustment — refund the placeholder debit and re-debit actual.
            if (paperMode) {
                val cash = paperCashSol.get()
                val netDelta = (prev.entryCostSol + prev.feesSol) - (actualEntryCostSol + actualFeesSol)
                val newCash = cash + netDelta
                if (newCash < 0.0) {
                    invariantViolations.incrementAndGet()
                    return MutateResult.INVARIANT_VIOLATION
                }
                paperCashSol.set(newCash)
            }
            positions[positionId] = prev.copy(
                entryCostSol = actualEntryCostSol,
                remainingQtyRaw = actualQtyRaw,
                originalQtyRaw = actualQtyRaw,
                feesSol = actualFeesSol,
                tokenDecimals = tokenDecimals,
                quantityScale = quantityScale,
                lifecycle = Lifecycle.OPEN,
                lastMutationMs = System.currentTimeMillis(),
            )
            muts.incrementAndGet()
            try { PipelineHealthCollector.labelInc("CANONICAL_POSITION_PROMOTED_6441") } catch (_: Throwable) {}
            return MutateResult.APPLIED
        } finally { lock.unlock() }
    }

    /** V5.0.6486 — atomic scale-in/add against an existing canonical position. */
    fun addToPosition6486(
        idempotencyKey: String,
        positionId: String,
        addedCostSol: Double,
        addedQtyRaw: BigInteger,
        feesSol: Double,
    ): MutateResult {
        lock.lock()
        try {
            if (isDuplicate(idempotencyKey)) return MutateResult.DUPLICATE
            val pos = positions[positionId] ?: return MutateResult.UNKNOWN_POSITION
            if (pos.lifecycle !in setOf(Lifecycle.OPEN, Lifecycle.PARTIALLY_CLOSED)) return MutateResult.LIFECYCLE_FORBIDDEN
            if (!addedCostSol.isFinite() || addedCostSol <= 0.0 || addedQtyRaw <= BigInteger.ZERO || !feesSol.isFinite() || feesSol < 0.0) {
                invariantViolations.incrementAndGet()
                return MutateResult.INVARIANT_VIOLATION
            }
            positions[positionId] = pos.copy(
                entryCostSol = pos.entryCostSol + addedCostSol,
                remainingQtyRaw = pos.remainingQtyRaw + addedQtyRaw,
                originalQtyRaw = pos.originalQtyRaw + addedQtyRaw,
                feesSol = pos.feesSol + feesSol,
                lifecycle = Lifecycle.OPEN,
                lastMutationMs = System.currentTimeMillis(),
            )
            markKeyUsed(idempotencyKey)
            muts.incrementAndGet()
            try { PipelineHealthCollector.labelInc("CANONICAL_POSITION_ADD_6486") } catch (_: Throwable) {}
            return MutateResult.APPLIED
        } finally { lock.unlock() }
    }

    fun partialSell(
        idempotencyKey: String,
        positionId: String,
        soldQtyRaw: BigInteger,
        proceedsSol: Double,
        soldCostBasisSol: Double,
        feesSol: Double,
        paperMode: Boolean,
    ): MutateResult {
        lock.lock()
        try {
            if (isDuplicate(idempotencyKey)) return MutateResult.DUPLICATE
            val pos = positions[positionId] ?: return MutateResult.UNKNOWN_POSITION
            if (pos.lifecycle == Lifecycle.CLOSED || pos.lifecycle == Lifecycle.QUARANTINED) {
                return MutateResult.LIFECYCLE_FORBIDDEN
            }
            if (soldQtyRaw <= BigInteger.ZERO) return MutateResult.INVARIANT_VIOLATION
            if (soldQtyRaw > pos.remainingQtyRaw) {
                invariantViolations.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "CANONICAL_OVERSOLD_QTY_6441",
                        "positionId=$positionId soldRaw=$soldQtyRaw remainingRaw=${pos.remainingQtyRaw}",
                    )
                } catch (_: Throwable) {}
                return MutateResult.INVARIANT_VIOLATION
            }
            val newRemaining = pos.remainingQtyRaw - soldQtyRaw
            val newLifecycle = if (newRemaining == BigInteger.ZERO) Lifecycle.CLOSED else Lifecycle.PARTIALLY_CLOSED
            val realizedDelta = proceedsSol - soldCostBasisSol - feesSol
            positions[positionId] = pos.copy(
                remainingQtyRaw = newRemaining,
                soldCostBasisSol = pos.soldCostBasisSol + soldCostBasisSol,
                realizedProceedsSol = pos.realizedProceedsSol + proceedsSol,
                realizedPnlSol = pos.realizedPnlSol + realizedDelta,
                feesSol = pos.feesSol + feesSol,
                lifecycle = newLifecycle,
                lastMutationMs = System.currentTimeMillis(),
            )
            if (paperMode) paperCashSol.getAndUpdate { it + proceedsSol - feesSol }
            markKeyUsed(idempotencyKey)
            muts.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc(
                    if (newLifecycle == Lifecycle.CLOSED) "CANONICAL_POSITION_CLOSE_6441"
                    else "CANONICAL_POSITION_PARTIAL_6441",
                )
            } catch (_: Throwable) {}
            return MutateResult.APPLIED
        } finally { lock.unlock() }
    }

    /** V5.0.6485 — remove an uncommitted entry; never expose it to schedulers. */
    fun abortEntry6485(positionId: String, refundPaperFacade: Boolean, reason: String): Boolean {
        lock.lock()
        try {
            val pos = positions[positionId] ?: return false
            if (pos.lifecycle == Lifecycle.CLOSED || pos.lifecycle == Lifecycle.PARTIALLY_CLOSED) return false
            positions.remove(positionId)
            if (refundPaperFacade) paperCashSol.getAndUpdate { it + pos.entryCostSol + pos.feesSol }
            try { PipelineHealthCollector.labelInc("CANONICAL_ENTRY_ABORTED_6485") } catch (_: Throwable) {}
            return true
        } finally { lock.unlock() }
    }

    fun quarantine(positionId: String, reason: String) {
        lock.lock()
        try {
            val pos = positions[positionId] ?: return
            positions[positionId] = pos.copy(
                lifecycle = Lifecycle.QUARANTINED,
                quarantineReason = reason.take(120),
                lastMutationMs = System.currentTimeMillis(),
            )
            quarantines.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_POSITION_QUARANTINED_6441",
                    "positionId=$positionId reason=${reason.take(60)}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("CANONICAL_POSITION_QUARANTINED_6441") } catch (_: Throwable) {}
        } finally { lock.unlock() }
    }

    private fun isDuplicate(key: String): Boolean {
        if (key.isBlank()) return false
        val prev = mutationKeys.putIfAbsent(key, System.currentTimeMillis())
        if (prev != null) {
            duplicates.incrementAndGet()
            try { PipelineHealthCollector.labelInc("CANONICAL_MUTATION_DUPLICATE_6441") } catch (_: Throwable) {}
            return true
        }
        return false
    }

    private fun markKeyUsed(key: String) {
        if (key.isBlank()) return
        // Trim old keys periodically.
        if (mutationKeys.size > 50_000) {
            val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            mutationKeys.entries.removeIf { it.value < cutoff }
        }
    }

    // ─── Read-only accessors for consumers ─────────────────────────────────
    fun getPosition(positionId: String): Position? = positions[positionId]
    fun openPositions(): List<Position> = positions.values.filter {
        it.lifecycle == Lifecycle.OPEN || it.lifecycle == Lifecycle.PARTIALLY_CLOSED
    }
    fun closedPositions(): List<Position> = positions.values.filter { it.lifecycle == Lifecycle.CLOSED }
    fun openCount(): Int = openPositions().size
    fun hasOpenMint(mint: String): Boolean = positions.values.any {
        it.mint == mint && (it.lifecycle == Lifecycle.OPEN || it.lifecycle == Lifecycle.PARTIALLY_CLOSED)
    }

    /**
     * V5.0.6461 §P0-#2 — explicit PENDING_ENTRY iterator.
     * Callers must never treat these as open positions for capital,
     * lane, or slot counts. Exposed only for the sweep + audit.
     */
    /** V5.0.6485 — purge paper lifecycle rows that have no funded lot. */
    fun healUnfundedPaperEntries6485(): List<Position> {
        lock.lock()
        try {
            val victims = positions.values.filter { p ->
                p.positionId.startsWith("PAPER:") &&
                    p.lifecycle in setOf(Lifecycle.PENDING_ENTRY, Lifecycle.OPEN) &&
                    !CanonicalLotQuantity6464.hasFundedOpenLot6485(p.positionId)
            }
            victims.forEach { positions.remove(it.positionId) }
            if (victims.isNotEmpty()) try { PipelineHealthCollector.labelInc("UNFUNDED_PAPER_ENTRY_PURGED_6485") } catch (_: Throwable) {}
            return victims
        } finally { lock.unlock() }
    }

    /** V5.0.6486 — rebuild paper position authority from durable typed events. */
    fun rebuildPaperFromEvents6486(source: List<EconomicEventSchema6464.Event>): Int {
        lock.lock()
        try {
            positions.entries.removeIf { it.value.mode == "paper" }
            mutationKeys.entries.removeIf { it.key.startsWith("REPLAY6486:") }
            val paperEvents = source.filter { it.mode == "paper" }.sortedBy { it.atMs }
            fun repairedEntryPrice6519(fillPrice: Double, costSol: Double, qtyRaw: BigInteger, quantityScale: Int): Double {
                if (fillPrice.isFinite() && fillPrice > 0.0) return fillPrice
                val qty = try { PaperTokenQuantityAuthority6509.decode(qtyRaw, quantityScale) } catch (_: Throwable) { 0.0 }
                return if (costSol.isFinite() && costSol > 0.0 && qty.isFinite() && qty > 0.0) costSol / qty else 0.0
            }
            for (e in paperEvents) {
                when (e) {
                    is EconomicEventSchema6464.Buy -> {
                        val cur = positions[e.positionId]
                        val repairedPrice6519 = repairedEntryPrice6519(e.fillPrice, e.executedCostSol, e.filledQty, e.quantityScale)
                        if (!repairedPrice6519.isFinite() || repairedPrice6519 <= 0.0) {
                            positions[e.positionId] = Position(
                                positionId = e.positionId, mode = "paper", mint = e.mint, symbol = e.symbol,
                                lane = "REPLAY_6486", runId = e.idempotencyKey, openedAtMs = e.atMs,
                                entryCostSol = e.executedCostSol, remainingQtyRaw = e.filledQty,
                                originalQtyRaw = e.filledQty, soldCostBasisSol = 0.0,
                                realizedPnlSol = 0.0, realizedProceedsSol = 0.0, feesSol = e.entryFeesSol,
                                tokenDecimals = e.tokenDecimals, quantityScale = e.quantityScale,
                                lifecycle = Lifecycle.QUARANTINED, lastMutationMs = e.atMs,
                                quarantineReason = "QUARANTINE_POSITION_BAD_ENTRY_6519",
                                entryPriceUsd = 0.0, entryPriceSource = "UNRECOVERABLE_DURABLE_BUY_EVENT",
                            )
                            try {
                                PipelineHealthCollector.labelInc("QUARANTINE_POSITION_BAD_ENTRY_6519")
                                ForensicLogger.lifecycle("QUARANTINE_POSITION_BAD_ENTRY_6519", "positionId=${e.positionId} mint=${e.mint.take(10)} cost=${e.executedCostSol} qty=${e.filledQty} scale=${e.quantityScale}")
                            } catch (_: Throwable) {}
                            continue
                        }
                        positions[e.positionId] = if (cur == null || cur.lifecycle == Lifecycle.CLOSED || cur.lifecycle == Lifecycle.QUARANTINED) {
                            Position(
                                positionId = e.positionId, mode = "paper", mint = e.mint, symbol = e.symbol,
                                lane = "REPLAY_6486", runId = e.idempotencyKey, openedAtMs = e.atMs,
                                entryCostSol = e.executedCostSol, remainingQtyRaw = e.filledQty,
                                originalQtyRaw = e.filledQty, soldCostBasisSol = 0.0,
                                realizedPnlSol = 0.0, realizedProceedsSol = 0.0,
                                feesSol = e.entryFeesSol, tokenDecimals = e.tokenDecimals,
                                quantityScale = e.quantityScale, lifecycle = Lifecycle.OPEN,
                                lastMutationMs = e.atMs, quarantineReason = "",
                                entryPriceUsd = repairedPrice6519, entryPriceSource = if (e.fillPrice > 0.0) "ECONOMIC_EVENT_REPLAY_6513" else "DURABLE_COST_QTY_REPAIR_6519",
                            )
                        } else cur.copy(
                            entryCostSol = cur.entryCostSol + e.executedCostSol,
                            remainingQtyRaw = cur.remainingQtyRaw + e.filledQty,
                            originalQtyRaw = cur.originalQtyRaw + e.filledQty,
                            feesSol = cur.feesSol + e.entryFeesSol,
                            entryPriceUsd = cur.entryPriceUsd.takeIf { it.isFinite() && it > 0.0 } ?: repairedPrice6519,
                            entryPriceSource = cur.entryPriceSource.ifBlank { "DURABLE_COST_QTY_REPAIR_6519" },
                            lifecycle = Lifecycle.OPEN, lastMutationMs = e.atMs,
                        )
                    }
                    is EconomicEventSchema6464.Sell -> {
                        val cur = positions[e.positionId] ?: continue
                        val life = if (e.remainingQty <= BigInteger.ZERO) Lifecycle.CLOSED else Lifecycle.PARTIALLY_CLOSED
                        positions[e.positionId] = cur.copy(
                            remainingQtyRaw = e.remainingQty,
                            soldCostBasisSol = cur.soldCostBasisSol + e.allocatedCostBasisSol,
                            realizedPnlSol = cur.realizedPnlSol + e.realizedPnlSol,
                            realizedProceedsSol = cur.realizedProceedsSol + e.grossProceedsSol,
                            feesSol = cur.feesSol + e.exitFeesSol,
                            lifecycle = life, lastMutationMs = e.atMs,
                        )
                    }
                }
            }
            // V5.0.6492 — replay carry is the confirmed prefix older than
            // the bounded event window. 6489 restored it into paper capital but
            // omitted it here, producing openCost>0 with zero paper positions.
            val carry6492 = try { EconomicEventSchema6464.replayCarry6489() } catch (_: Throwable) { null }
            carry6492?.perMintQty?.forEach { (mint, qtyRaw) ->
                val carryCost = carry6492.perMintCostSol[mint] ?: 0.0
                if (mint.isBlank() || qtyRaw <= BigInteger.ZERO || !carryCost.isFinite() || carryCost <= 0.0) return@forEach
                val existing = positions.values.firstOrNull {
                    it.mode == "paper" && it.mint == mint &&
                        it.lifecycle in setOf(Lifecycle.OPEN, Lifecycle.PARTIALLY_CLOSED) && it.remainingQtyRaw > BigInteger.ZERO
                }
                if (existing != null) {
                    positions[existing.positionId] = existing.copy(
                        entryCostSol = existing.entryCostSol + carryCost,
                        remainingQtyRaw = existing.remainingQtyRaw + qtyRaw,
                        originalQtyRaw = existing.originalQtyRaw + qtyRaw,
                        lastMutationMs = System.currentTimeMillis(),
                    )
                    try { PipelineHealthCollector.labelInc("CANONICAL_CARRY_MERGED_6492") } catch (_: Throwable) {}
                } else {
                    val pid = "PAPER:CARRY6492:$mint"
                    val carryScale6519 = carry6492.perMintQuantityScale[mint] ?: (carry6492.perMintTokenDecimals[mint] ?: 9)
                    val carryEntryPrice6519 = repairedEntryPrice6519(0.0, carryCost, qtyRaw, carryScale6519)
                    positions[pid] = Position(
                        positionId = pid, mode = "paper", mint = mint, symbol = mint.take(8),
                        lane = "RECOVERED_CARRY_6492", runId = "REPLAY_CARRY_6492",
                        openedAtMs = System.currentTimeMillis(), entryCostSol = carryCost,
                        remainingQtyRaw = qtyRaw, originalQtyRaw = qtyRaw,
                        soldCostBasisSol = 0.0, realizedPnlSol = 0.0, realizedProceedsSol = 0.0,
                        feesSol = 0.0,
                        tokenDecimals = carry6492.perMintTokenDecimals[mint] ?: 9,
                        quantityScale = carryScale6519,
                        lifecycle = if (carryEntryPrice6519 > 0.0) Lifecycle.OPEN else Lifecycle.QUARANTINED,
                        lastMutationMs = System.currentTimeMillis(),
                        quarantineReason = if (carryEntryPrice6519 > 0.0) "" else "QUARANTINE_POSITION_BAD_ENTRY_6519",
                        entryPriceUsd = carryEntryPrice6519,
                        entryPriceSource = if (carryEntryPrice6519 > 0.0) "DURABLE_CARRY_COST_QTY_REPAIR_6519" else "UNRECOVERABLE_DURABLE_CARRY",
                    )
                    try { PositionStateLedger6454.onEntry(pid) } catch (_: Throwable) {}
                    try { PipelineHealthCollector.labelInc("CANONICAL_CARRY_POSITION_RESTORED_6492") } catch (_: Throwable) {}
                }
            }
            // PositionStateLedger is terminal-CAS projection only. Rebuild it
            // from canonical lifecycle; it may never invent independent opens.
            val canonicalOpen6519 = positions.values.filter {
                it.mode == "paper" && it.lifecycle in setOf(Lifecycle.OPEN, Lifecycle.PARTIALLY_CLOSED) &&
                    it.remainingQtyRaw > BigInteger.ZERO && it.entryPriceUsd.isFinite() && it.entryPriceUsd > 0.0
            }
            try { PositionStateLedger6454.syncFromCanonical6519(canonicalOpen6519) } catch (_: Throwable) {}
            try { SellQtyBoundaryClamp6427.syncFromCanonical6519(canonicalOpen6519) } catch (_: Throwable) {}
            try { CanonicalMintOccupancyRegistry6464.reconcileActiveFromCanonical6489(canonicalOpen6519) } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("POSITION_STATE_PROJECTED_FROM_CANONICAL_6492") } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("SELL_QTY_BOUNDARY_PROJECTED_FROM_CANONICAL_6498") } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("CANONICAL_PAPER_POSITIONS_REBUILT_6486") } catch (_: Throwable) {}
            return positions.values.count { it.mode == "paper" && it.lifecycle != Lifecycle.CLOSED }
        } finally { lock.unlock() }
    }

    fun pendingEntryPositions6461(): List<Position> = positions.values.filter {
        it.lifecycle == Lifecycle.PENDING_ENTRY
    }


    /**
     * V5.0.6489 — funded active projection by canonical mint identity.
     * Economic lots remain positionId-keyed; registries/slots are mint-keyed,
     * so projection must SUM lots instead of silently keeping the last row.
     */
    data class ActiveMintProjection6489(
        val mint: String,
        val symbol: String,
        val lane: String,
        val primaryMode: String,
        val modes: Set<String>,
        val openedAtMs: Long,
        val remainingQtyRaw: BigInteger,
        val remainingCostBasisSol: Double,
        val lotCount: Int,
    )

    fun activeMintProjections6489(): List<ActiveMintProjection6489> = activeMintProjections6490()

    /** V5.0.6490 — one inventory identity is mode + mint, never bare mint. */
    fun activeMintProjections6490(mode: String? = null): List<ActiveMintProjection6489> = openPositions()
        .filter { it.remainingQtyRaw > BigInteger.ZERO && (mode == null || it.mode.equals(mode, true)) }
        .groupBy { "${it.mode.lowercase()}|${it.mint}" }
        .map { (_, lots) ->
            val representative = lots.maxByOrNull { it.lastMutationMs } ?: lots.first()
            ActiveMintProjection6489(
                mint = representative.mint,
                symbol = representative.symbol,
                lane = representative.lane,
                primaryMode = representative.mode,
                modes = lots.map { it.mode.lowercase() }.toSet(),
                openedAtMs = lots.minOf { it.openedAtMs },
                remainingQtyRaw = lots.fold(BigInteger.ZERO) { acc, p -> acc + p.remainingQtyRaw },
                remainingCostBasisSol = lots.sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) },
                lotCount = lots.size,
            )
        }

    /**
     * V5.0.6461 §P0-#2 — cancel PENDING_ENTRY rows older than ttlMs.
     * Moves them to QUARANTINED with reason "PENDING_ENTRY_TTL_CANCELLED_6461"
     * and refunds any placeholder cash debit for paper positions.
     * Returns the list of positionIds cancelled.
     */
    fun cancelStalePendingEntries6461(ttlMs: Long): List<String> {
        val now = System.currentTimeMillis()
        val victims = pendingEntryPositions6461().filter { (now - it.lastMutationMs) > ttlMs }
        if (victims.isEmpty()) return emptyList()
        val cancelledIds = mutableListOf<String>()
        for (v in victims) {
            lock.lock()
            try {
                val cur = positions[v.positionId] ?: continue
                if (cur.lifecycle != Lifecycle.PENDING_ENTRY) continue
                // Refund the placeholder debit so cash returns to the pre-open state.
                val refund = cur.entryCostSol + cur.feesSol
                if (refund > 0.0) paperCashSol.getAndUpdate { it + refund }
                positions[cur.positionId] = cur.copy(
                    lifecycle = Lifecycle.QUARANTINED,
                    quarantineReason = "PENDING_ENTRY_TTL_CANCELLED_6461",
                    lastMutationMs = now,
                )
                cancelledIds += cur.positionId
                quarantines.incrementAndGet()
            } finally { lock.unlock() }
        }
        return cancelledIds
    }

    /**
     * V5.0.6456 §P0-#2 CANONICAL POSITION STATE INVARIANT.
     * Emits (total, byLifecycle) so callers can assert
     *   total == Σ(byLifecycle.values)
     * with NO invisible/default/null state permitted. Every position is
     * accounted to exactly one of PENDING_ENTRY / OPEN / PARTIALLY_CLOSED
     * / CLOSED / QUARANTINED.
     */
    data class LifecycleClassification(val total: Int, val byLifecycle: Map<Lifecycle, Int>, val unaccounted: Int)

    fun classifyLifecycles(): LifecycleClassification {
        val snapshot = positions.values.toList()
        val counts = Lifecycle.values().associateWith { life -> snapshot.count { it.lifecycle == life } }
        val classified = counts.values.sum()
        val unaccounted = snapshot.size - classified
        if (unaccounted != 0) {
            try {
                ForensicLogger.lifecycle(
                    "POSITION_STATE_SUM_VIOLATION_6456",
                    "total=${snapshot.size} classified=$classified unaccounted=$unaccounted breakdown=${counts.entries.joinToString(",") { "${it.key}=${it.value}" }}",
                )
                PipelineHealthCollector.labelInc("POSITION_STATE_SUM_VIOLATION_6456")
            } catch (_: Throwable) {}
        }
        return LifecycleClassification(total = snapshot.size, byLifecycle = counts, unaccounted = unaccounted)
    }

    internal fun resetForTest() {
        lock.lock()
        try {
            positions.clear(); mutationKeys.clear()
            paperCashSol.set(0.0); paperCashInitialisedMs.set(0L); liveCashObservedSol.set(0.0)
            muts.set(0L); duplicates.set(0L); invariantViolations.set(0L); quarantines.set(0L)
        } finally { lock.unlock() }
    }

    fun statusLine(): String {
        val open = openCount()
        val closed = closedPositions().size
        val cash = paperCashSol.get()
        val classification = classifyLifecycles()
        val breakdown = classification.byLifecycle.entries.joinToString(",") { "${it.key}=${it.value}" }
        return "positions=${positions.size} open=$open closed=$closed paperCashSol=${"%.5f".format(cash)} " +
            "muts=${muts.get()} dups=${duplicates.get()} invViol=${invariantViolations.get()} quarantines=${quarantines.get()} " +
            "sumCheck(total=${classification.total} classified=${classification.byLifecycle.values.sum()} " +
            "unaccounted=${classification.unaccounted} $breakdown)"
    }
}
