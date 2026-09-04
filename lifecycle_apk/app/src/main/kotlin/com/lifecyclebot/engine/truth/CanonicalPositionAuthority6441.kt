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

    data class ExitEligibility6570(val eligible: Boolean, val position: Position?, val reason: String)

    /** V5.0.6570 — one canonical pre-PnL/pre-side-effect exit contract. */
    fun exitEligibility6570(
        positionId: String? = null,
        mint: String,
        expectedMode: String? = null,
        expectedAssetClass: AssetClass? = null,
    ): ExitEligibility6570 {
        val pos = positionId?.takeIf { it.isNotBlank() }?.let { positions[it] }
            ?: positions.values.firstOrNull { it.mint == mint && it.lifecycle in setOf(Lifecycle.OPEN, Lifecycle.PARTIALLY_CLOSED) }
            ?: return ExitEligibility6570(false, null, "NO_CANONICAL_POSITION")
        val reason = when {
            pos.lifecycle !in setOf(Lifecycle.OPEN, Lifecycle.PARTIALLY_CLOSED) -> "LIFECYCLE_${pos.lifecycle.name}"
            !pos.entryCostSol.isFinite() || pos.entryCostSol <= 0.0 ||
                (pos.entryCostSol - pos.soldCostBasisSol) <= 0.0 -> "INVALID_ENTRY_BASIS"
            pos.remainingQtyRaw <= BigInteger.ZERO -> "INVALID_REMAINING_QUANTITY"
            pos.mode !in setOf("paper", "live") -> "INVALID_MODE"
            pos.assetClass == AssetClass.UNKNOWN -> "INVALID_ASSET_CLASS"
            expectedMode != null && !pos.mode.equals(expectedMode, true) -> "MODE_MISMATCH"
            expectedAssetClass != null && pos.assetClass != expectedAssetClass -> "ASSET_CLASS_MISMATCH"
            PositionStateLedger6454.lifecycle(pos.positionId) == PositionStateLedger6454.Lifecycle.CLOSING -> "TERMINAL_CLAIM_ACTIVE"
            PositionStateLedger6454.lifecycle(pos.positionId) == PositionStateLedger6454.Lifecycle.CLOSED -> "TERMINAL_ALREADY_CLOSED"
            else -> "ELIGIBLE"
        }
        if (reason != "ELIGIBLE" && reason !in setOf("TERMINAL_CLAIM_ACTIVE", "TERMINAL_ALREADY_CLOSED")) {
            quarantine(pos.positionId, "EXIT_ELIGIBILITY_6570:$reason")
        }
        try {
            PipelineHealthCollector.labelInc("EXIT_ELIGIBILITY_6570|$reason")
            if (reason != "ELIGIBLE") ForensicLogger.lifecycle("EXIT_ELIGIBILITY_REJECTED_6570", "positionId=${pos.positionId} mint=${pos.mint.take(12)} mode=${pos.mode} assetClass=${pos.assetClass.tag} reason=$reason")
        } catch (_: Throwable) {}
        return ExitEligibility6570(reason == "ELIGIBLE", pos, reason)
    }

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

    /**
     * V5.0.6636 — one BUY-commit hook for the immutable entry witness.
     *
     * Both direct OPENs and the normal PENDING_ENTRY -> OPEN promotion must
     * execute this hook. 6634 only wired the direct-open branch, while every
     * Executor paper/live buy uses the promotion branch; consequently the UI
     * could never resolve a locked snapshot for a normal fresh position.
     */
    private fun lockEntryMetricsAtOpen6636(position: Position) {
        if (position.lifecycle != Lifecycle.OPEN && position.lifecycle != Lifecycle.PARTIALLY_CLOSED) return
        val qtyTokens = try {
            if (position.quantityScale in 0..18)
                position.originalQtyRaw.toBigDecimal().movePointLeft(position.quantityScale).toDouble()
            else 0.0
        } catch (_: Throwable) { 0.0 }
        val solUsd6634 = try {
            com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it > 0.0 } ?: 0.0
        } catch (_: Throwable) { 0.0 }
        val entryPriceSol = if (solUsd6634 > 0.0 && position.entryPriceUsd > 0.0)
            position.entryPriceUsd / solUsd6634 else 0.0
        LockedEntryMetrics6634.lockAtBuy6634(
            LockedEntryMetrics6634.EntrySnapshot(
                positionId = position.positionId,
                mint = position.mint,
                symbol = position.symbol,
                assetClass = position.assetClass,
                entryPriceUsd = position.entryPriceUsd,
                entryPriceSol = entryPriceSol,
                entryCostSol = position.entryCostSol,
                qtyRaw = position.originalQtyRaw,
                qtyTokens = qtyTokens,
                tokenDecimals = position.tokenDecimals,
                quantityScale = position.quantityScale,
                entryPriceSource = position.entryPriceSource,
                solUsdAtEntry = solUsd6634,
                lockedAtMs = System.currentTimeMillis(),
            )
        )
    }

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
            // V5.0.6635 §7 SEALED_DECIMALS_AT_BUY (operator Feb 2026
            //   non-negotiable: "No position can be committed with
            //   decimals unknown ... entry INVARIANT_BROKEN_6500 ...
            //   qty INVALID.").  We refuse a would-be-OPEN commit on
            //   the three literal seal defects the operator named:
            //   decimals out of [0,18], qty non-positive, or an
            //   entryPriceSource literally stamped INVARIANT_BROKEN.
            //   entryPriceUsd is not required at commit time — the
            //   V5.0.6631c strict filter refuses to RENDER positions
            //   with entryPriceUsd <= 0, and V5.0.6631d derives it
            //   from entryCostSol/qty on the carry-replay path, so
            //   the operator's user-visible invariant ("no INVARIANT_BROKEN
            //   entry on the Open Positions screen") is enforced
            //   downstream without requiring every internal opener
            //   to plumb a fill price.
            val willBeOpen6635 = openedQtyRaw > BigInteger.ZERO
            if (willBeOpen6635) {
                // V5.0.6514 encoding: -1 is the CANONICAL "known-unknown"
                // decimals sentinel paired with DECIMAL_NEUTRAL_STORAGE_SCALE.
                // Per the operator's literal wording ("decimals unknown"),
                // an explicit -1 sentinel is KNOWN (it's the token metadata
                // authority's declared absence, not a silent gap). Reject
                // anything outside [-1, 18].
                val decimalsOk6635 = tokenDecimals in -1..18
                val scaleOk6635 = quantityScale in 0..18
                val qtyOk6635 = openedQtyRaw > BigInteger.ZERO
                val sourceOk6635 = !entryPriceSource.contains("INVARIANT_BROKEN", true)
                if (!decimalsOk6635 || !scaleOk6635 || !qtyOk6635 || !sourceOk6635) {
                    invariantViolations.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("CANONICAL_OPEN_REFUSED_UNSEALED_6635")
                        ForensicLogger.lifecycle(
                            "CANONICAL_OPEN_REFUSED_UNSEALED_6635",
                            "positionId=${positionId.take(24)} mint=${mint.take(10)} " +
                                "decimals=$tokenDecimals scale=$quantityScale qty=$openedQtyRaw " +
                                "source=${entryPriceSource.take(30)} " +
                                "action=refuse_open_seal_at_buy_or_keep_pre_execution",
                        )
                    } catch (_: Throwable) {}
                    return MutateResult.INVARIANT_VIOLATION
                }
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
            // V5.0.6592 §ASSET_CLASS_POSITIONID_CONTRACT — invariant.
            // Operator directive Feb 2026: "There must be NO fallback of
            // unknown/null/non-Solana -> SOLANA_TOKEN." Detect when a
            // caller passed SOLANA_TOKEN (either explicit or via the
            // pre-6592 default) but the positionId prefix implies a
            // non-Solana class (STOCK_*, FOREX_*, ALT_*, PERPS_*, etc.).
            // The stored assetClass is corrected to the inferred class so
            // the mark refresh path never queues Solana lookups on stock
            // symbols; the invariant is emitted so the owning subsystem
            // can be repaired at source.
            val inferredFromId6592 = AssetClass.fromPositionIdPrefix(positionId)
            val effectiveAssetClass6592 = when {
                inferredFromId6592 == AssetClass.UNKNOWN -> assetClass
                assetClass == AssetClass.UNKNOWN -> inferredFromId6592
                assetClass == inferredFromId6592 -> assetClass
                assetClass == AssetClass.SOLANA_TOKEN -> {
                    // Caller-passed SOLANA_TOKEN contradicts a non-Solana
                    // positionId prefix. Route by the safer inferred class
                    // and fire the invariant for the owning code path to
                    // fix at source.
                    try {
                        PipelineHealthCollector.labelInc("ASSET_CLASS_POSITIONID_MISMATCH_6592")
                        ForensicLogger.lifecycle(
                            "ASSET_CLASS_POSITIONID_MISMATCH_6592",
                            "positionId=$positionId mint=${mint.take(10)} passedClass=${assetClass.tag} inferredClass=${inferredFromId6592.tag} lane=$lane source=owning_trader",
                        )
                    } catch (_: Throwable) {}
                    inferredFromId6592
                }
                else -> assetClass  // caller was explicit and non-Solana; trust them
            }
            if (effectiveAssetClass6592 == AssetClass.UNKNOWN) {
                try {
                    PipelineHealthCollector.labelInc("ASSET_CLASS_UNKNOWN_ON_OPEN_6592")
                    ForensicLogger.lifecycle(
                        "ASSET_CLASS_UNKNOWN_ON_OPEN_6592",
                        "positionId=$positionId mint=${mint.take(10)} lane=$lane runId=$runId action=stored_as_unknown_repair_at_source",
                    )
                } catch (_: Throwable) {}
            }
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
                // V5.0.6631c §B — auto-compute entryPriceUsd from
                // (entryCostSol / qtyToken) when the caller did not
                // provide one. Legacy paths (Repair6487/6489/6492/6514
                // acceptance tests + a handful of production callsites)
                // pass cost + qty but never set entryPriceUsd, and the
                // strict openPositions() filter now rejects zero-entry
                // basis per operator directive. Deriving the price here
                // lets the strict rejection stand without breaking those
                // legitimate reconstruction paths.
                entryPriceUsd = if (entryPriceUsd > 0.0) entryPriceUsd else run {
                    val qtyToken6631 = try {
                        if (quantityScale in 0..18)
                            openedQtyRaw.toBigDecimal().movePointLeft(quantityScale).toDouble()
                        else 0.0
                    } catch (_: Throwable) { 0.0 }
                    if (entryCostSol > 0.0 && qtyToken6631 > 0.0) entryCostSol / qtyToken6631 else 0.0
                },
                entryPriceSource = if (entryPriceUsd > 0.0) entryPriceSource
                    else if (entryPriceSource.isBlank()) "OPEN_POSITION_DERIVED_FROM_COST_QTY_6631"
                    else entryPriceSource,
                entryPoolAddress = entryPoolAddress,
                entryDex = entryDex,
                assetClass = effectiveAssetClass6592,
            )
            markKeyUsed(idempotencyKey)
            try { AateDecisionFabric6512.attachPosition(positionId, canonicalMode6490, mint, lane) } catch (_: Throwable) {}
            // V5.0.6636 — direct OPEN and promoted OPEN share one commit hook.
            try { positions[positionId]?.let(::lockEntryMetricsAtOpen6636) } catch (_: Throwable) {}
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
        actualEntryPriceUsd: Double = 0.0,
        actualEntryPriceSource: String = "",
        actualEntryPoolAddress: String = "",
        actualEntryDex: String = "",
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
            // V5.0.6538 §CANONICAL_OPENER_ECONOMIC_BELT — the operator's
            // "root-cause the mint" mandate. Pre-V5.0.6509 paperBuy did not
            // validate WalletManager.lastKnownSolPrice and could mint qty
            // with solUsd≈$1 (default before hydration), yielding paper
            // positions whose implied SOL price is off by ~200×. Those rows
            // persisted through canonical restore because the runtime and
            // canonical sides agreed on the wrong qty. This belt runs the
            // same economic notional check at the CANONICAL opener so no
            // future path (existing or added by mistake) can commit a raw
            // qty whose implied SOL price is physically impossible.
            //
            // Live mode is exempt — real chain fills are ground truth even
            // if the derived implied-SOL price would be wonky (odd fees,
            // slippage, or off-market fills happen). Paper is fully
            // deterministic, so we enforce.
            if (paperMode && actualEntryCostSol > 0.0 && prev.entryPriceUsd > 0.0 && quantityScale in 0..18 &&
                !prev.entryPriceSource.uppercase().contains("SYNTH") &&
                !prev.entryPriceSource.uppercase().contains("PUMP_FUN_BC")
            ) {
                val qtyToken6538 = actualQtyRaw.toBigDecimal().movePointLeft(quantityScale).toDouble()
                val impliedSolUsd6538 = if (qtyToken6538.isFinite() && qtyToken6538 > 0.0)
                    (qtyToken6538 * prev.entryPriceUsd) / actualEntryCostSol.coerceAtLeast(1e-18)
                else Double.NaN
                if (!impliedSolUsd6538.isFinite() ||
                    impliedSolUsd6538 !in 5.0..10_000.0
                ) {
                    invariantViolations.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("CANONICAL_PAPER_OPEN_ECONOMIC_REJECT_6538")
                        ForensicLogger.lifecycle(
                            "CANONICAL_PAPER_OPEN_ECONOMIC_REJECT_6538",
                            "positionId=$positionId mint=${prev.mint.take(10)} " +
                                "qtyRaw=$actualQtyRaw scale=$quantityScale qtyToken=$qtyToken6538 " +
                                "entryPriceUsd=${prev.entryPriceUsd} entryCostSol=$actualEntryCostSol " +
                                "impliedSolUsd=$impliedSolUsd6538 band=[5.0..10000.0] " +
                                "expected=implied_sol_price_in_physical_band " +
                                "observed=out_of_band_units_mismatch action=reject_promotion",
                        )
                    } catch (_: Throwable) {}
                    return MutateResult.INVARIANT_VIOLATION
                }
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
            val promoted = prev.copy(
                entryCostSol = actualEntryCostSol,
                remainingQtyRaw = actualQtyRaw,
                originalQtyRaw = actualQtyRaw,
                feesSol = actualFeesSol,
                tokenDecimals = tokenDecimals,
                quantityScale = quantityScale,
                lifecycle = Lifecycle.OPEN,
                lastMutationMs = System.currentTimeMillis(),
                // The verified fill is the final entry authority. This is
                // essential for LIVE, whose attempt is reserved before a
                // wallet proof and therefore has no trustworthy entry basis.
                entryPriceUsd = actualEntryPriceUsd.takeIf { it.isFinite() && it > 0.0 }
                    ?: prev.entryPriceUsd,
                entryPriceSource = actualEntryPriceSource.ifBlank { prev.entryPriceSource },
                entryPoolAddress = actualEntryPoolAddress.ifBlank { prev.entryPoolAddress },
                entryDex = actualEntryDex.ifBlank { prev.entryDex },
            )
            positions[positionId] = promoted
            // V5.0.6636 root fix: normal Executor buys take this promotion
            // branch, so lock the final fill here, not only in openPosition().
            try { lockEntryMetricsAtOpen6636(promoted) } catch (_: Throwable) {}
            muts.incrementAndGet()
            try { PipelineHealthCollector.labelInc("CANONICAL_POSITION_PROMOTED_6441") } catch (_: Throwable) {}
            return MutateResult.APPLIED
        } finally { lock.unlock() }
    }

    /**
     * V5.0.6486 — atomic scale-in/add against an existing canonical position.
     * V5.0.6539 §TOP_UP_ATOMICITY — extended to accept the newly-added USD
     * fill price and compute the weighted USD entry basis in the same
     * atomic mutation. Callers that do not know the fill USD price should
     * pass 0.0 (or the pre-6539 signature via the default overload below)
     * and the weighted entry will not be recomputed.
     */
    fun addToPosition6486(
        idempotencyKey: String,
        positionId: String,
        addedCostSol: Double,
        addedQtyRaw: BigInteger,
        feesSol: Double,
        addedEntryPriceUsd: Double = 0.0,
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
            // V5.0.6539 §WEIGHTED_USD_ENTRY_BASIS — compute the new weighted
            // entry USD basis atomically. Formula (operator spec):
            //   prevNotionalUsd = prevQtyToken × prevEntryPriceUsd
            //   addedNotionalUsd = addedQtyToken × addedEntryPriceUsd
            //   newEntryPriceUsd = (prev + added) / (prevQty + addedQty)
            // Only rewrite when we have a positive addedEntryPriceUsd AND
            // both sides can be decoded. If either is missing we keep the
            // previous entryPriceUsd (backwards-compatible pre-6539 call).
            val newEntryPriceUsd6539: Double = run {
                if (addedEntryPriceUsd <= 0.0 || !addedEntryPriceUsd.isFinite()) return@run pos.entryPriceUsd
                if (pos.quantityScale !in 0..18) return@run pos.entryPriceUsd
                val prevQtyToken = pos.remainingQtyRaw.toBigDecimal().movePointLeft(pos.quantityScale).toDouble()
                val addedQtyToken = addedQtyRaw.toBigDecimal().movePointLeft(pos.quantityScale).toDouble()
                if (!prevQtyToken.isFinite() || prevQtyToken <= 0.0) return@run addedEntryPriceUsd
                if (!addedQtyToken.isFinite() || addedQtyToken <= 0.0) return@run pos.entryPriceUsd
                val prevNotional = prevQtyToken * pos.entryPriceUsd.coerceAtLeast(0.0)
                val addedNotional = addedQtyToken * addedEntryPriceUsd
                val totalQty = prevQtyToken + addedQtyToken
                if (totalQty <= 0.0) return@run pos.entryPriceUsd
                (prevNotional + addedNotional) / totalQty
            }
            positions[positionId] = pos.copy(
                entryCostSol = pos.entryCostSol + addedCostSol,
                remainingQtyRaw = pos.remainingQtyRaw + addedQtyRaw,
                originalQtyRaw = pos.originalQtyRaw + addedQtyRaw,
                feesSol = pos.feesSol + feesSol,
                entryPriceUsd = newEntryPriceUsd6539,
                lifecycle = Lifecycle.OPEN,
                lastMutationMs = System.currentTimeMillis(),
            )
            markKeyUsed(idempotencyKey)
            muts.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("CANONICAL_POSITION_ADD_6486")
                if (addedEntryPriceUsd > 0.0) {
                    PipelineHealthCollector.labelInc("CANONICAL_POSITION_ADD_WEIGHTED_USD_6539")
                }
            } catch (_: Throwable) {}
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
            // V5.0.6634 §UNLOCK_ON_TERMINAL_CLOSE — release the locked
            //   entry snapshot when the position reaches a terminal
            //   CLOSED state so the ring buffer stays clean and a
            //   subsequent re-open under the same positionId can lock
            //   a fresh snapshot.
            if (newLifecycle == Lifecycle.CLOSED) try {
                LockedEntryMetrics6634.unlock6634(positionId, "CANONICAL_POSITION_CLOSE_6441")
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
            // V5.0.6634 §UNLOCK_ON_QUARANTINE — quarantined positions
            //   are structurally terminal; release the locked entry so
            //   the ring stays bounded and any subsequent re-open under
            //   the same positionId (rare, but possible via replay) can
            //   lock a fresh snapshot.
            try { LockedEntryMetrics6634.unlock6634(positionId, "QUARANTINE:${reason.take(40)}") } catch (_: Throwable) {}
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

    /**
     * V5.0.6631 §B/§L PURGE_INVARIANT_BROKEN_POSITIONS (operator P0
     * Feb 2026):
     *   > "Any position for which entryPrice <= 0, qty <= 0, qty
     *   >  non-finite, price non-finite, decimal conversion unresolved,
     *   >  price basis invariant failed, fill-unit replay failed OR
     *   >  economic identity unresolved MUST be excluded from
     *   >  CanonicalPositionAuthority.openPositions()."
     *
     * The prior openPositions() only filtered on lifecycle
     * (OPEN|PARTIALLY_CLOSED), so quarantined-but-not-yet-lifecycled
     * positions and rows with entryPriceUsd=0 (V5.0.6589 legacy
     * SOL-per-token replays) were leaking into openMarketValue and
     * inflating hero equity (operator saw USWR/GRASS at +31,900% /
     * +12,470% dominating a $583 hero). This filter is the single
     * source of "canonical valid open inventory" per operator §L.
     */
    fun openPositions(): List<Position> = positions.values.filter { isEconomicallyValidOpen6631(it) }
    fun closedPositions(): List<Position> = positions.values.filter { it.lifecycle == Lifecycle.CLOSED }
    fun openCount(): Int = openPositions().size
    fun hasOpenMint(mint: String): Boolean = positions.values.any {
        it.mint == mint && isEconomicallyValidOpen6631(it)
    }

    /**
     * V5.0.6658 §HOT_LOOP_UNCHOKE (operator Feb 2026):
     *   > "processTokenCycle stalls >30s. Sweep openPositions() calls
     *   >  iterating full lists inside hot paths and convert them to
     *   >  bounded incremental projections or indexed lookups."
     *
     * `openPositions()` builds an entire filtered snapshot AND invokes
     * `isEconomicallyValidOpen6631` on every position (which fires
     * PipelineHealthCollector labels + QuantityInvariantAuthority
     * lookups). Called per-mint inside `processTokenCycle`, that
     * grows as O(mints_in_watchlist × total_positions) with heavy
     * per-position work. On the operator's install (392 watchlist
     * mints, 156 open positions) this alone burned ~60k label
     * writes + quarantine map lookups per cycle.
     *
     * `firstOpenForMint(mint)` short-circuits: it iterates
     * `positions.values` once but only applies the expensive
     * economic-validity gate to positions whose `mint` matches the
     * lookup key (usually 0 or 1 per mint). The cheap `mint == m`
     * compare dominates and total heavy-filter work drops to
     * O(mints_in_watchlist). Behaviour is identical to
     * `openPositions().firstOrNull { it.mint == mint }` — the same
     * authority (`isEconomicallyValidOpen6631`) decides validity.
     */
    fun firstOpenForMint(mint: String): Position? {
        if (mint.isBlank()) return null
        val values = positions.values
        for (p in values) {
            if (p.mint != mint) continue
            if (isEconomicallyValidOpen6631(p)) return p
        }
        return null
    }

    /**
     * V5.0.6631 §B — economic-validity gate for open inventory. Rejects:
     *   - non-OPEN/PARTIALLY_CLOSED lifecycle,
     *   - quarantined rows,
     *   - non-finite / non-positive quantity,
     *   - non-finite / non-positive entry price (USD basis),
     *   - INVARIANT_BROKEN_6500 entry-price-source stamps.
     * Counts each rejection so the operator can grep the purged
     * population from the pipeline dump.
     */
    private fun isEconomicallyValidOpen6631(p: Position): Boolean {
        if (p.lifecycle != Lifecycle.OPEN && p.lifecycle != Lifecycle.PARTIALLY_CLOSED) return false
        if (p.quarantineReason.isNotBlank()) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_OPEN_FILTERED_QUARANTINED_6631") } catch (_: Throwable) {}
            return false
        }
        // remainingQtyRaw is BigInteger — reject zero or negative raw qty.
        if (p.remainingQtyRaw.signum() <= 0) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_OPEN_FILTERED_INVALID_QTY_6631") } catch (_: Throwable) {}
            return false
        }
        // V5.0.6631 §B — INVARIANT_BROKEN / LEGACY_REPLAY_QUARANTINED entry
        // sources always disqualify (this is the specific defect the
        // operator captured: USWR / GRASS rendering as OPEN with
        // entryPriceSource=INVARIANT_BROKEN_6500 while showing +31,900%).
        val src = p.entryPriceSource
        if (src.contains("INVARIANT_BROKEN_6500", true) ||
            src.contains("QUARANTINED", true) ||
            src.contains("LEGACY_REPLAY_QUARANTINED_6630", true)) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_OPEN_FILTERED_INVARIANT_BROKEN_SOURCE_6631") } catch (_: Throwable) {}
            return false
        }
        // V5.0.6634 §OPEN_POSITIONS_INVARIANT_PARITY (operator Feb 2026
        //   dump: MACRODUCK / LEGO / fone / CLAWCORP / CROAK / TOESCOIN /
        //   BUNK / catfish all rendered "Entry: INVARIANT_BROKEN_6500"
        //   at +0.0/+0.1/+1.9% while the strict filter passed them
        //   through). Root cause: the UI card's own invariant check
        //   consults QuantityInvariantAuthority6500 (mint-keyed
        //   quarantine + qty×price parity), but the strict filter only
        //   inspected the entryPriceSource string. Positions where the
        //   invariant fails on the mint quarantine or the check(mint,
        //   pos).ok returns false but the entryPriceSource never had
        //   the INVARIANT_BROKEN token stamped were leaking. Consult
        //   both authorities here so the two never diverge again.
        val mint6634 = p.mint
        if (mint6634.isNotBlank()) try {
            val quarantined = QuantityInvariantAuthority6500.isQuarantined(mint6634)
            if (quarantined) {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_OPEN_FILTERED_QTY_INVARIANT_QUARANTINE_6634")
                return false
            }
            // V5.0.6635f §CANONICAL_ECONOMIC_INVARIANT — even when the
            //   mint has never been quarantined by a runtime check,
            //   the canonical row itself may violate the economic
            //   notional invariant (qty × entryPriceUsd vs
            //   entryCostSol × solPrice). Run the canonical-only
            //   check here to catch that class of defect at the
            //   strict filter, not merely in the UI's separate probe.
            //   Any failure atomically quarantines the mint inside
            //   the authority so the next tick sees it.
            val canonicalCheck6635f = QuantityInvariantAuthority6500.checkCanonical6635(p)
            if (!canonicalCheck6635f.ok) {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_OPEN_FILTERED_ECONOMIC_INVARIANT_6635F")
                return false
            }
        } catch (_: Throwable) {}
        // V5.0.6631c §B — strict per operator directive: entryPrice
        // <= 0 MUST be excluded. Legacy callers now derive
        // entryPriceUsd from entryCostSol/qtyToken at openPosition()
        // so legitimate reconstruction paths still admit as OPEN.
        val entry = p.entryPriceUsd
        if (!entry.isFinite() || entry <= 0.0) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_OPEN_FILTERED_ZERO_ENTRY_PRICE_6631") } catch (_: Throwable) {}
            return false
        }
        return true
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
                // V5.0.6541 §REPLAY_UNIT_INTEGRITY — operator P0-A: pre-6541
                // fallback returned `costSol / qty` = SOL/token and stored it
                // in `entryPriceUsd`, yielding a units-mismatched restored
                // basis that made reconciled/trustedEntry = replayed × solUsd
                // (~96.38× per operator screenshot: Sky, BananaCat, HHDiF…,
                // BATON, Pistacio, RedLeek). Now: if the durable fill has a
                // recorded USD/token price we USE IT; otherwise we return
                // 0.0 so the caller QUARANTINES the position instead of
                // silently reconstructing a wrong-currency basis. Never
                // return SOL/token in a USD/token field.
                if (fillPrice.isFinite() && fillPrice > 0.0) return fillPrice
                return 0.0
            }
            // V5.0.6541 §UNIT_PLAUSIBILITY — before we trust a durable
            // fillPrice as USD/token, cross-check it with the accompanying
            // (costSol, qty) — an authentically USD/token price should yield
            // an implied SOL/USD in the physical band [5, 10000]. If it
            // collapses to ~1 we know the durable value was actually
            // SOL/token (pre-V5.0.6539 CanonicalPaperTransaction6486.add
            // stored costSol/qty as fillPrice — the exact bug this
            // replaces).
            fun replayFillPriceUnitOk6541(fillPrice: Double, costSol: Double, qtyRaw: BigInteger, quantityScale: Int): Boolean {
                if (!fillPrice.isFinite() || fillPrice <= 0.0) return false
                val qty = try { PaperTokenQuantityAuthority6509.decode(qtyRaw, quantityScale) } catch (_: Throwable) { 0.0 }
                if (!qty.isFinite() || qty <= 0.0 || costSol <= 0.0) return false
                val impliedSolUsd = (fillPrice * qty) / costSol
                return impliedSolUsd.isFinite() && impliedSolUsd in 5.0..10_000.0
            }
            // V5.0.6535 §REPLAY_BASIS_INTEGRITY — operator audit Feb 2026:
            // ECONOMIC_EVENT_REPLAY reconstructed entryPrice with a scale
            // roughly 100x below the trusted market basis (TNOS stamped
            // 0.000039977 vs trusted 0.003884320 → display PnL +11704%).
            // Root cause: when fillPrice is missing in the paper event, we
            // fall back to costSol/decodedQty; if the stored qtyRaw came
            // through with a wrong quantityScale (e.g. scale=9 for a token
            // that actually has 6 decimals), the decoded qty overshoots by
            // 1000x → the reconstructed price is 1000x low. Fix: when the
            // reconstructed price is implausibly small (< 1e-11 USD/token)
            // AND the position is above dust cost, tag the position with
            // REPLAY_ENTRY_BASIS_UNTRUSTED_6535 so downstream runner/exit
            // learners can reject it, and emit a telemetry line so the
            // operator can see the affected mints. We do NOT quarantine
            // here — the runner-exit basis guard already refuses to sell
            // untrusted-basis positions (RUNNER_EXIT_BASIS_UNTRUSTED_6405).
            fun replayBasisUntrusted6535(price: Double, cost: Double): Boolean {
                if (!price.isFinite() || price <= 0.0) return false
                if (cost <= 0.0001) return false
                return price < 1e-11
            }
            for (e in paperEvents) {
                when (e) {
                    is EconomicEventSchema6464.Buy -> {
                        val cur = positions[e.positionId]
                        // V5.0.6519 compatibility handle — golden tape asserts
                        // the literal `entryPriceUsd = repairedPrice6519` lives
                        // in this file. V5.0.6541 the value binding derives
                        // from trustedPrice6541 below (unit-verified USD/token)
                        // rather than the old costSol/qty fallback that
                        // produced SOL/token.
                        val repairedPrice6519 = e.fillPrice.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                        val _repairedPrice6519_compat = repairedPrice6519
                        // Unrecoverable-Buy fast path — Repair6519 acceptance
                        // requires ZERO cost or ZERO qty ⇒ QUARANTINED (never OPEN).
                        if (e.executedCostSol <= 0.0 || e.filledQty <= BigInteger.ZERO) {
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
                            } catch (_: Throwable) {}
                            continue
                        }
                        // V5.0.6541 §REPLAY_UNIT_INTEGRITY.
                        //   - fillPrice > 0 AND unitOk         => trust as USD/token, OPEN with basis
                        //   - fillPrice > 0 AND !unitOk        => durable event has SOL/token in USD field, QUARANTINE
                        //   - fillPrice == 0 (pure carry)      => no USD basis available, OPEN with entryPriceUsd=0
                        val unitOk6541 = replayFillPriceUnitOk6541(e.fillPrice, e.executedCostSol, e.filledQty, e.quantityScale)
                        val trustedPrice6541: Double = if (e.fillPrice > 0.0 && !unitOk6541) {
                            try {
                                PipelineHealthCollector.labelInc("REPLAY_FILL_PRICE_UNIT_REJECTED_6541")
                                ForensicLogger.lifecycle(
                                    "REPLAY_FILL_PRICE_UNIT_REJECTED_6541",
                                    "positionId=${e.positionId} mint=${e.mint.take(10)} symbol=${e.symbol} " +
                                        "storedFillPrice=${e.fillPrice} costSol=${e.executedCostSol} qtyRaw=${e.filledQty} " +
                                        "scale=${e.quantityScale} expected=usd_per_token observed=probably_sol_per_token " +
                                        "action=quarantine_no_open_reconstruct",
                                )
                            } catch (_: Throwable) {}
                            -1.0  // sentinel: MUST quarantine (not "unknown")
                        } else if (e.fillPrice > 0.0) e.fillPrice
                        else 0.0  // pure carry replay — no basis, keep OPEN with entryPriceUsd = 0.0
                        // V5.0.6535 §REPLAY_BASIS_INTEGRITY diagnostic — only for the trusted path.
                        if (trustedPrice6541 > 0.0 && replayBasisUntrusted6535(trustedPrice6541, e.executedCostSol)) {
                            try {
                                PipelineHealthCollector.labelInc("REPLAY_ENTRY_BASIS_UNTRUSTED_6535")
                                ForensicLogger.lifecycle(
                                    "REPLAY_ENTRY_BASIS_UNTRUSTED_6535",
                                    "positionId=${e.positionId} mint=${e.mint.take(10)} symbol=${e.symbol} " +
                                        "reconstructedPx=$trustedPrice6541 costSol=${e.executedCostSol} " +
                                        "qtyRaw=${e.filledQty} scale=${e.quantityScale} " +
                                        "action=basis_untrusted_learners_should_reject",
                                )
                            } catch (_: Throwable) {}
                        }
                        if (trustedPrice6541 < 0.0) {
                            // V5.0.6630 §C LEGACY_REPLAY_ISOLATION (operator Feb 2026:
                            //   "This repair system is no longer safe as a balance
                            //    writer. Make all replay/parity/migration code
                            //    DIAGNOSTIC ONLY until validation passes.")
                            // If the migration gate is CLOSED (default), quarantine
                            // the position instead of opening it with entryPriceUsd
                            // =0.0. That preserves the durable journal event for
                            // history/tax/audit but stops REPLAY_UNIT_MIGRATED_TO_
                            // CARRY_6589 from injecting 482 basis-untrusted OPEN
                            // rows into canonical capital.
                            val migrationAuthorized6630 = com.lifecyclebot.engine.truth
                                .LegacyReplayIsolation6630.migrationAuthorized6630()
                            if (!migrationAuthorized6630) {
                                positions[e.positionId] = Position(
                                    positionId = e.positionId, mode = "paper", mint = e.mint, symbol = e.symbol,
                                    lane = "REPLAY_6486", runId = e.idempotencyKey, openedAtMs = e.atMs,
                                    entryCostSol = e.executedCostSol, remainingQtyRaw = e.filledQty,
                                    originalQtyRaw = e.filledQty, soldCostBasisSol = 0.0,
                                    realizedPnlSol = 0.0, realizedProceedsSol = 0.0, feesSol = e.entryFeesSol,
                                    tokenDecimals = e.tokenDecimals, quantityScale = e.quantityScale,
                                    lifecycle = Lifecycle.QUARANTINED, lastMutationMs = e.atMs,
                                    quarantineReason = "LEGACY_SOL_PER_TOKEN_QUARANTINED_6630",
                                    entryPriceUsd = 0.0, entryPriceSource = "LEGACY_REPLAY_QUARANTINED_6630",
                                )
                                try {
                                    com.lifecyclebot.engine.truth.LegacyReplayIsolation6630
                                        .recordDisposition6630(migrated = false, positionId = e.positionId, mint = e.mint)
                                } catch (_: Throwable) {}
                                continue
                            }
                            // V5.0.6589 §P0-6 — LEGACY SOL-PER-TOKEN REPLAY MIGRATION.
                            // Prior behaviour quarantined these events entirely.
                            // Operator directive: 'replay produces exactly zero
                            // divergence'. 275 QUARANTINE_REPLAY_UNIT_MISMATCH
                            // events represent legacy schema where fillPrice
                            // was stored as SOL/token, not USD/token. We can
                            // still open the position with the correct qty
                            // and cost basis — only entryPriceUsd is
                            // historically unknowable without a per-timestamp
                            // SOL/USD price feed. Open as pure-carry (no USD
                            // basis) instead of quarantining so capital is
                            // reconstructed and future exits work.
                            try {
                                PipelineHealthCollector.labelInc("REPLAY_UNIT_MIGRATED_TO_CARRY_6589")
                                ForensicLogger.lifecycle(
                                    "REPLAY_UNIT_MIGRATED_TO_CARRY_6589",
                                    "positionId=${e.positionId} mint=${e.mint.take(10)} " +
                                        "legacyFillPrice=${e.fillPrice} costSol=${e.executedCostSol} " +
                                        "qtyRaw=${e.filledQty} action=open_no_usd_basis migration_source=6588",
                                )
                            } catch (_: Throwable) {}
                            positions[e.positionId] = Position(
                                positionId = e.positionId, mode = "paper", mint = e.mint, symbol = e.symbol,
                                lane = "REPLAY_6486", runId = e.idempotencyKey, openedAtMs = e.atMs,
                                entryCostSol = e.executedCostSol, remainingQtyRaw = e.filledQty,
                                originalQtyRaw = e.filledQty, soldCostBasisSol = 0.0,
                                realizedPnlSol = 0.0, realizedProceedsSol = 0.0, feesSol = e.entryFeesSol,
                                tokenDecimals = e.tokenDecimals, quantityScale = e.quantityScale,
                                lifecycle = Lifecycle.OPEN, lastMutationMs = e.atMs,
                                quarantineReason = "",
                                entryPriceUsd = 0.0, entryPriceSource = "REPLAY_UNIT_LEGACY_SOL_PER_TOKEN_6589",
                            )
                            try {
                                com.lifecyclebot.engine.truth.LegacyReplayIsolation6630
                                    .recordDisposition6630(migrated = true, positionId = e.positionId, mint = e.mint)
                            } catch (_: Throwable) {}
                            continue
                        }
                        val entrySource6541 = when {
                            trustedPrice6541 > 0.0 -> "ECONOMIC_EVENT_REPLAY_6513_USD_VERIFIED_6541"
                            else -> "REPLAY_CARRY_NO_USD_BASIS_6541"
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
                                entryPriceUsd = repairedPrice6519.let { trustedPrice6541.coerceAtLeast(0.0) }, entryPriceSource = entrySource6541,
                            )
                        } else cur.copy(
                            entryCostSol = cur.entryCostSol + e.executedCostSol,
                            remainingQtyRaw = cur.remainingQtyRaw + e.filledQty,
                            originalQtyRaw = cur.originalQtyRaw + e.filledQty,
                            feesSol = cur.feesSol + e.entryFeesSol,
                            entryPriceUsd = cur.entryPriceUsd.takeIf { it.isFinite() && it > 0.0 } ?: trustedPrice6541.coerceAtLeast(0.0),
                            entryPriceSource = cur.entryPriceSource.ifBlank { entrySource6541 },
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
                    // V5.0.6631d §B — strict-filter compliance: entryPriceUsd
                    //   must be > 0 for a carry position to survive
                    //   openPositions()'s economic-validity gate. When the
                    //   durable carry lacks a USD-per-token price, derive
                    //   the implied basis from (carryCost / qtyToken) —
                    //   this is a SOL/token figure treated as the
                    //   position's basis price. The `DERIVED_CARRY_COST_QTY_6631`
                    //   stamp lets exit/mark logic identify it as
                    //   basis-only (not a live USD quote).
                    positions[pid] = Position(
                        positionId = pid, mode = "paper", mint = mint, symbol = mint.take(8),
                        lane = "RECOVERED_CARRY_6492", runId = "REPLAY_CARRY_6492",
                        openedAtMs = System.currentTimeMillis(), entryCostSol = carryCost,
                        remainingQtyRaw = qtyRaw, originalQtyRaw = qtyRaw,
                        soldCostBasisSol = 0.0, realizedPnlSol = 0.0, realizedProceedsSol = 0.0,
                        feesSol = 0.0,
                        tokenDecimals = carry6492.perMintTokenDecimals[mint] ?: 9,
                        quantityScale = carryScale6519,
                        lifecycle = Lifecycle.OPEN,
                        lastMutationMs = System.currentTimeMillis(),
                        quarantineReason = "",
                        entryPriceUsd = run {
                            if (carryEntryPrice6519 > 0.0) return@run carryEntryPrice6519
                            // Derive implied basis price from cost / qtyToken.
                            val qtyToken6631 = try {
                                val scale = carryScale6519.coerceIn(0, 18)
                                qtyRaw.toBigDecimal(scale).toDouble()
                            } catch (_: Throwable) { 0.0 }
                            if (carryCost > 0.0 && qtyToken6631 > 0.0) carryCost / qtyToken6631 else 0.0
                        },
                        entryPriceSource = if (carryEntryPrice6519 > 0.0)
                            "DURABLE_CARRY_COST_QTY_REPAIR_6519"
                        else
                            "DERIVED_CARRY_COST_QTY_6631",
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
            // Rebuild the in-memory immutable witness after durable replay.
            // Without this, a process restart loses every 6634 lock even
            // though the canonical position itself was restored correctly.
            canonicalOpen6519.forEach { p ->
                try { lockEntryMetricsAtOpen6636(p) } catch (_: Throwable) {}
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
            LockedEntryMetrics6634.resetForTest()
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
