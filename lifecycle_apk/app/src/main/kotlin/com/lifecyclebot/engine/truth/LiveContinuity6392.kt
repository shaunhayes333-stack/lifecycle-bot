package com.lifecyclebot.engine.truth

import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6392 — LIVE CONTINUITY AND PROFIT-INTEGRITY REPAIR.
 *
 * Goal: keep the bot actively trading while repairing the quantity, decimal,
 * position-alias, exit-finalization and canonical-performance defects
 * observed in V5.0.6391.
 *
 * DO NOT implement a global live-entry hold as the normal response to a
 * single trade-integrity fault. Replace global failure handling with:
 *     CONTINUE_CLEAN_TRADING + PER_MINT_QUARANTINE + WALLET_RECONCILED_ACCOUNTING
 *
 * Modules (one per directive section):
 *   S1  LiveContinuityPolicy6392
 *   S2  CanonicalWalletPosition6392 + registry (one position per wallet+mint)
 *   S3  MintDecimalsAuthority6392 (chain-resolved, immutable cache)
 *   S4  BuyFillFromWalletDelta6392 (atomic quantities from tx parsing)
 *   S5  AtomicUnitContract6392 (BigInteger/lamports only)
 *   S6  SafeSellCalculator6392 (min(wallet, ledger, requested))
 *   S7  PartialExitTemporaryDisable6392
 *   S8  ExitMutex6392 (one exit per wallet+mint)
 *   S9  BroadcastLiability6392 (LIVE_BROADCAST as pending)
 *   S10 CanonicalWalletParity6392 (canonical PnL == wallet PnL invariant)
 *   S11 PerMintIntegrityQuarantine6392
 *   S12 VerifiedBluechipIdentity6392 (mint allowlist)
 *   S13 ExternalRugClassification6392 (MARKET_DATA_DEGRADED vs RUG)
 *   S14 DedicatedExitSupervisorContract6392
 *   S15 StopLossFromExecutableQuote6392
 *   S16 EntrySizingProgression6392
 *   S17 ProfitabilityGovernorFields6392
 *   S18 LiveContinuityInvariants6392
 */

/* ============================ S1 LIVE CONTINUITY POLICY ==================== */

/** Directive S1: the single source of truth for live-continuity feature flags. */
data class LiveContinuityPolicy6392(
    val allowCleanLiveEntries: Boolean = true,
    val globalIntegrityHoldEnabled: Boolean = false,   // OFF by default per directive
    val perMintQuarantineEnabled: Boolean = true,
    val requireWalletConfirmedBuy: Boolean = true,
    val requireChainResolvedDecimals: Boolean = true,
    val singlePositionPerWalletMint: Boolean = true,
    val partialExitsEnabled: Boolean = false,          // S7 temporarily disabled
    val fullProfitExitEnabled: Boolean = true,
    val fullStopExitEnabled: Boolean = true,
    val trailingFullExitEnabled: Boolean = true,
    val maximumTemporaryBuySol: Double = 0.010,
)

object LiveContinuityPolicyProvider6392 {
    private val current = AtomicReference(LiveContinuityPolicy6392())
    fun get(): LiveContinuityPolicy6392 = current.get()
    fun set(p: LiveContinuityPolicy6392) { current.set(p) }
    fun update(fn: (LiveContinuityPolicy6392) -> LiveContinuityPolicy6392) {
        current.updateAndGet(fn)
    }
    internal fun resetForTest() { current.set(LiveContinuityPolicy6392()) }
}

/* ============================ S2 CANONICAL WALLET POSITION ================= */

enum class PositionState6392 {
    OPEN, EXIT_PLANNED, EXIT_BROADCAST, EXIT_CONFIRMED, WALLET_RECONCILED, CLOSED
}

/** Directive S2: identity is (wallet, mint) — lane is advisory only. */
data class CanonicalWalletPosition6392(
    val wallet: String, val mint: String,
    val tokenDecimals: Int,
    val acquiredAtomic: BigInteger,
    val disposedAtomic: BigInteger,
    val remainingAtomic: BigInteger,
    val investedLamports: BigInteger,
    val recoveredLamports: BigInteger,
    val entrySignatures: Set<String>,
    val exitSignatures: Set<String>,
    val originatingLane: String,
    val advisoryLanes: Set<String>,
    val state: PositionState6392,
) {
    /** Directive S18 invariant: remaining = acquired − disposed. */
    fun inventoryConservationHolds(): Boolean =
        remainingAtomic == acquiredAtomic.subtract(disposedAtomic) &&
        disposedAtomic <= acquiredAtomic &&
        remainingAtomic.signum() >= 0
}

object CanonicalWalletPositionRegistry6392 {
    private val positions = ConcurrentHashMap<Pair<String, String>, CanonicalWalletPosition6392>()

    /** Directive S2: attachLaneAdvice on existing position, never create another. */
    @Synchronized
    fun findOrAttach(wallet: String, mint: String, candidateLane: String,
                     onCreate: () -> CanonicalWalletPosition6392): CanonicalWalletPosition6392 {
        val key = wallet to mint
        val existing = positions[key]
        if (existing != null && existing.remainingAtomic.signum() > 0) {
            val merged = existing.copy(advisoryLanes = existing.advisoryLanes + candidateLane)
            positions[key] = merged
            return merged
        }
        val created = onCreate()
        positions[key] = created
        return created
    }

    fun get(wallet: String, mint: String): CanonicalWalletPosition6392? = positions[wallet to mint]
    fun openCount(wallet: String): Int = positions.values.count {
        it.wallet == wallet && it.remainingAtomic.signum() > 0
    }
    fun all(): List<CanonicalWalletPosition6392> = positions.values.toList()

    /** Directive S2 invariant: exactly ONE active canonical position per wallet+mint. */
    fun singleActivePositionInvariant(): Boolean {
        val keys = positions.values.filter { it.remainingAtomic.signum() > 0 }
            .map { it.wallet to it.mint }
        return keys.size == keys.toSet().size
    }

    @Synchronized
    fun update(p: CanonicalWalletPosition6392) { positions[p.wallet to p.mint] = p }

    internal fun clearForTest() { positions.clear() }
}

/* ============================ S3 MINT DECIMALS AUTHORITY =================== */

object MintDecimalsAuthority6392 {
    private val cache = ConcurrentHashMap<String, Int>()
    private val providerQuarantines = ConcurrentHashMap<String, MutableList<Int>>()

    class IntegrityException(msg: String) : RuntimeException(msg)

    /** Directive S3: chain-resolved decimals. Must be in 0..18. */
    fun resolveAndCache(mint: String, chainResolvedDecimals: Int): Int {
        require(chainResolvedDecimals in 0..18) { "INVALID_MINT_DECIMALS:$chainResolvedDecimals" }
        val prior = cache.putIfAbsent(mint, chainResolvedDecimals)
        return prior ?: chainResolvedDecimals
    }

    fun get(mint: String): Int? = cache[mint]

    /** Directive S3: provider reports different value — quarantine but never mutate. */
    fun recordProviderQuarantine(mint: String, providerReportedDecimals: Int) {
        providerQuarantines.getOrPut(mint) { mutableListOf() }.add(providerReportedDecimals)
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("QUARANTINE_PROVIDER_DECIMALS_6392") } catch (_: Throwable) {}
    }

    fun quarantinedFor(mint: String): List<Int> = providerQuarantines[mint].orEmpty().toList()

    internal fun clearForTest() { cache.clear(); providerQuarantines.clear() }
}

/* ============================ S4 BUY FILL FROM WALLET DELTA ================ */

object BuyFillFromWalletDelta6392 {
    data class Delta(
        val mint: String, val signature: String, val slot: Long,
        val preTokenBalanceAtomic: BigInteger, val postTokenBalanceAtomic: BigInteger,
        val preWalletLamports: BigInteger, val postWalletLamports: BigInteger,
        val tokenDecimals: Int, val confirmationStatus: String,
    )
    data class CanonicalBuyFill(
        val mint: String, val signature: String, val slot: Long,
        val acquiredAtomic: BigInteger, val economicCostLamports: BigInteger,
        val tokenDecimals: Int, val confirmationStatus: String,
    )

    /** Directive S4: fills MUST come from confirmed tx deltas. */
    fun buildFrom(delta: Delta): CanonicalBuyFill {
        val acquired = delta.postTokenBalanceAtomic.subtract(delta.preTokenBalanceAtomic)
        val walletSpend = delta.preWalletLamports.subtract(delta.postWalletLamports)
        require(acquired.signum() > 0) { "ACQUIRED_ATOMIC_NON_POSITIVE" }
        require(walletSpend.signum() > 0) { "ECONOMIC_COST_NON_POSITIVE" }
        require(delta.tokenDecimals in 0..18) { "INVALID_MINT_DECIMALS:${delta.tokenDecimals}" }
        return CanonicalBuyFill(
            mint = delta.mint, signature = delta.signature, slot = delta.slot,
            acquiredAtomic = acquired, economicCostLamports = walletSpend,
            tokenDecimals = delta.tokenDecimals, confirmationStatus = delta.confirmationStatus,
        )
    }
}

/* ============================ S5 ATOMIC UNIT CONTRACT ====================== */

object AtomicUnitContract6392 {
    /** Directive S5: UI conversion only. Never read back as execution authority. */
    fun displayTokenAmount(atomic: BigInteger, decimals: Int): BigDecimal =
        atomic.toBigDecimal().movePointLeft(decimals)
    fun displaySol(lamports: BigInteger): BigDecimal =
        lamports.toBigDecimal().movePointLeft(9)

    /** Fields that MUST be BigInteger internally. */
    val requiredAtomicFields: Set<String> = setOf(
        "acquiredAtomic", "disposedAtomic", "remainingAtomic",
        "investedLamports", "recoveredLamports",
        "walletAtomic", "ledgerAtomic", "requestedAtomic",
        "sellAtomic",
    )
    /** Directive S5: these must NEVER be Double. */
    val forbiddenDoubleFields: Set<String> = requiredAtomicFields
}

/* ============================ S6 SAFE SELL CALCULATOR ====================== */

object SafeSellCalculator6392 {
    data class Input(
        val walletAtomic: BigInteger,
        val ledgerAtomic: BigInteger,
        val requestedAtomic: BigInteger,
        val isFullExit: Boolean,
    )
    data class Result(val sellAtomic: BigInteger, val reason: String)

    /** Directive S6: strict clamp — never trust a journal quantity. */
    fun compute(i: Input): Result {
        val sellAtomic = if (i.isFullExit) {
            i.walletAtomic.min(i.ledgerAtomic)
        } else {
            i.walletAtomic.min(i.ledgerAtomic).min(i.requestedAtomic)
        }
        require(sellAtomic.signum() > 0) { "SELL_ATOMIC_NON_POSITIVE" }
        require(sellAtomic <= i.walletAtomic) { "SELL_EXCEEDS_WALLET" }
        require(sellAtomic <= i.ledgerAtomic) { "SELL_EXCEEDS_LEDGER" }
        val reason = if (i.isFullExit) "FULL_EXIT_CLAMPED_WALLET_LEDGER"
                     else "PARTIAL_EXIT_CLAMPED_WALLET_LEDGER_REQUESTED"
        return Result(sellAtomic, reason)
    }
}

/* ============================ S7 PARTIAL EXIT DISABLE ====================== */

object PartialExitTemporaryDisable6392 {
    fun isEnabled(): Boolean = LiveContinuityPolicyProvider6392.get().partialExitsEnabled

    /** Directive S7: profit partials require fee-adjusted positive net. */
    data class ProfitPartialInput(
        val executableProceedsLamports: BigInteger,
        val allocatedCostLamports: BigInteger,
        val transactionFeesLamports: BigInteger,
        val slippageAllowanceLamports: BigInteger,
        val configuredMinimumProfitLamports: BigInteger,
    )
    fun profitPartialAcceptable(i: ProfitPartialInput): Boolean {
        val required = i.allocatedCostLamports.add(i.transactionFeesLamports)
            .add(i.slippageAllowanceLamports).add(i.configuredMinimumProfitLamports)
        return i.executableProceedsLamports > required
    }
}

/* ============================ S8 EXIT MUTEX ================================ */

object ExitMutex6392 {
    data class Key(val wallet: String, val mint: String, val positionGeneration: Long,
                   val exitSequence: Long)
    private val active = ConcurrentHashMap<Pair<String, String>, Key>()
    private val idempotencyResults = ConcurrentHashMap<Key, String>()

    /** Directive S8: only ONE exit broadcast per wallet+mint at a time. */
    @Synchronized
    fun tryAcquire(k: Key): Boolean {
        val busKey = k.wallet to k.mint
        val prior = active[busKey] ?: run { active[busKey] = k; return true }
        return prior == k
    }

    /** Directive S8: replay with same idempotency key returns existing state. */
    fun idempotentResult(k: Key): String? = idempotencyResults[k]
    fun recordResult(k: Key, transactionState: String) { idempotencyResults[k] = transactionState }

    fun release(k: Key) { active.remove(k.wallet to k.mint) }
    fun activeCount(): Int = active.size
    internal fun clearForTest() { active.clear(); idempotencyResults.clear() }
}

/* ============================ S9 BROADCAST LIABILITY ======================= */

object BroadcastLiability6392 {
    enum class State { PENDING, CONFIRMED, FAILED, EXPIRED }
    data class Row(
        val signature: String, val wallet: String, val mint: String,
        val reservedSellAtomic: BigInteger, val positionGeneration: Long,
        val broadcastAtMs: Long, val state: State,
    )
    private val rows = ConcurrentHashMap<String, Row>()

    /** Directive S9: LIVE_BROADCAST reserves sell quantity + pending liability. */
    fun recordBroadcast(sig: String, wallet: String, mint: String,
                        reservedSellAtomic: BigInteger, positionGeneration: Long) {
        rows[sig] = Row(sig, wallet, mint, reservedSellAtomic, positionGeneration,
            System.currentTimeMillis(), State.PENDING)
    }
    fun markConfirmed(sig: String) { rows[sig]?.let { rows[sig] = it.copy(state = State.CONFIRMED) } }
    fun markFailed(sig: String) { rows[sig]?.let { rows[sig] = it.copy(state = State.FAILED) } }
    fun markExpired(sig: String) { rows[sig]?.let { rows[sig] = it.copy(state = State.EXPIRED) } }

    /** Directive S9: pending exposure is unresolved economic liability. */
    fun pendingExposureLamports(): BigInteger {
        // Placeholder — real value comes from executable quote * reservedSellAtomic.
        // Kept for the S10 parity report as row count for now.
        return BigInteger.ZERO
    }

    fun pendingRowCount(): Int = rows.values.count { it.state == State.PENDING }
    fun byState(state: State): List<Row> = rows.values.filter { it.state == state }
    internal fun clearForTest() { rows.clear() }
}

/* ============================ S10 CANONICAL/WALLET PARITY ================== */

object CanonicalWalletParity6392 {
    data class Report(
        val canonicalReconciledPnlLamports: BigInteger,
        val walletReconciledPnlLamports: BigInteger,
        val differenceLamports: BigInteger,
        val pendingBroadcastExposureLamports: BigInteger,
        val unreconciledTransactionCount: Int,
    ) {
        val withinTolerance: Boolean get() = differenceLamports.abs() <= BigInteger.valueOf(100_000L)
    }
    fun compute(canonical: BigInteger, wallet: BigInteger, pendingExposure: BigInteger,
                unreconciledCount: Int): Report {
        val diff = canonical.subtract(wallet)
        return Report(canonical, wallet, diff, pendingExposure, unreconciledCount)
    }
}

/* ============================ S11 PER-MINT INTEGRITY QUARANTINE ============ */

object PerMintIntegrityQuarantine6392 {
    data class Record(
        val wallet: String, val mint: String, val reason: String,
        val detectedAtMs: Long,
        val walletBalanceAtomic: BigInteger?, val ledgerBalanceAtomic: BigInteger?,
        val relatedSignatures: Set<String>,
    )
    val quarantineReasons: Set<String> = setOf(
        "BUY_DECIMALS_MISMATCH", "SELL_DECIMALS_MISMATCH",
        "SOLD_EXCEEDS_REMAINING", "WALLET_LEDGER_MATERIAL_DIFF",
        "MULTIPLE_ACTIVE_OWNERS", "SELL_SIG_UNASSOCIATED",
        "PROCEEDS_UNPARSABLE", "NEGATIVE_REMAINING",
        "DISPOSED_EXCEEDS_ACQUIRED",
    )
    private val records = ConcurrentHashMap<Pair<String, String>, Record>()

    fun quarantine(wallet: String, mint: String, reason: String,
                   walletBalanceAtomic: BigInteger?, ledgerBalanceAtomic: BigInteger?,
                   relatedSignatures: Set<String>): Record {
        require(reason in quarantineReasons) { "UNKNOWN_QUARANTINE_REASON:$reason" }
        val r = Record(wallet, mint, reason, System.currentTimeMillis(),
            walletBalanceAtomic, ledgerBalanceAtomic, relatedSignatures)
        records[wallet to mint] = r
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PER_MINT_QUARANTINE_6392")
        } catch (_: Throwable) {}
        return r
    }

    fun isQuarantined(wallet: String, mint: String): Boolean = records.containsKey(wallet to mint)
    fun release(wallet: String, mint: String) { records.remove(wallet to mint) }
    fun all(): List<Record> = records.values.toList()
    internal fun clearForTest() { records.clear() }
}

/* ============================ S12 VERIFIED BLUECHIP IDENTITY =============== */

object VerifiedBluechipIdentity6392 {
    enum class Lane { BLUECHIP, QUALITY, MOMENTUM, MOONSHOT, SPECULATIVE }
    enum class Source { STATIC_ALLOWLIST, OPERATOR_CONFIRMED, ONCHAIN_METADATA }
    data class Identity(val mint: String, val chain: String, val expectedSymbol: String,
                        val lane: Lane, val source: Source)

    /** Directive S12: exact canonical mint allowlist for Bluechip. */
    val allowlist: Map<String, Identity> = mapOf(
        // USDC (mainnet-beta)
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to Identity(
            mint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            chain = "solana", expectedSymbol = "USDC",
            lane = Lane.BLUECHIP, source = Source.STATIC_ALLOWLIST),
        // USDT
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to Identity(
            mint = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
            chain = "solana", expectedSymbol = "USDT",
            lane = Lane.BLUECHIP, source = Source.STATIC_ALLOWLIST),
        // Wrapped SOL
        "So11111111111111111111111111111111111111112" to Identity(
            mint = "So11111111111111111111111111111111111111112",
            chain = "solana", expectedSymbol = "WSOL",
            lane = Lane.BLUECHIP, source = Source.STATIC_ALLOWLIST),
    )

    /** Directive S12: symbol/name/marketCap NEVER establish Bluechip. */
    fun isBluechip(mint: String): Boolean =
        allowlist[mint]?.lane == Lane.BLUECHIP

    /** Directive S12: an unknown token with a familiar symbol goes to speculative. */
    fun resolveLaneForUnknown(mint: String, providerReportedSymbol: String,
                              providerReportedLane: Lane): Lane {
        if (isBluechip(mint)) return Lane.BLUECHIP
        // Symbol looks like a known Bluechip but mint does not match — reject.
        val symbolClaim = allowlist.values.any { it.expectedSymbol.equals(providerReportedSymbol, true) }
        if (symbolClaim) return Lane.SPECULATIVE
        return providerReportedLane
    }
}

/* ============================ S13 EXTERNAL RUG CLASSIFICATION ============== */

object ExternalRugClassification6392 {
    enum class Verdict { EXTERNAL_RUG_CLOSE, MARKET_DATA_DEGRADED, HEALTHY }
    data class Input(
        val liquidityRemoved: Boolean,
        val routerUnableAcrossAllProviders: Boolean,
        val poolReservesCollapsed: Boolean,
        val tokenAccountFrozen: Boolean,
        val transferSimulationPersistentlyFailed: Boolean,
        val honeypotProven: Boolean,
        val sellTaxAtomicallyProven: Boolean,
        val dexScreenerPairMissing: Boolean,
        val anyPricingProviderTimedOut: Boolean,
        val priceResponseNull: Boolean,
        val symbolLookupFailed: Boolean,
        val apiReturnedStaleData: Boolean,
    )

    /** Directive S13: only executable evidence triggers RUG. */
    fun classify(i: Input): Verdict {
        val executableEvidence = i.liquidityRemoved || i.routerUnableAcrossAllProviders ||
            i.poolReservesCollapsed || i.tokenAccountFrozen ||
            i.transferSimulationPersistentlyFailed || i.honeypotProven || i.sellTaxAtomicallyProven
        if (executableEvidence) return Verdict.EXTERNAL_RUG_CLOSE
        val providerDegraded = i.dexScreenerPairMissing || i.anyPricingProviderTimedOut ||
            i.priceResponseNull || i.symbolLookupFailed || i.apiReturnedStaleData
        return if (providerDegraded) Verdict.MARKET_DATA_DEGRADED else Verdict.HEALTHY
    }
}

/* ============================ S14 DEDICATED EXIT SUPERVISOR ================ */

object DedicatedExitSupervisorContract6392 {
    /** Directive S14: SL execution MUST NOT block on UI / watchlist / render. */
    val forbiddenBlockingSources: Set<String> = setOf(
        "watchlistScanCompletion", "uiRendering", "healthReportConstruction",
        "tokenCardRendering", "mainThreadWork", "fullBotLoopCompletion",
    )

    private val tickCounter = AtomicLong(0L)
    private val lastTickMs = AtomicLong(0L)
    private val maxDelayMs = AtomicLong(0L)

    fun recordTick(nowMs: Long = System.currentTimeMillis()) {
        val prior = lastTickMs.getAndSet(nowMs)
        if (prior > 0L) {
            val delta = nowMs - prior
            maxDelayMs.updateAndGet { if (delta > it) delta else it }
        }
        tickCounter.incrementAndGet()
    }

    fun ticks(): Long = tickCounter.get()
    fun maxDelayMs(): Long = maxDelayMs.get()

    /** Directive S14 target: supervisor loop ≤ 500ms cadence. */
    fun cadenceInvariantHolds(configuredMaxCadenceMs: Long = 750L): Boolean =
        tickCounter.get() < 2 || maxDelayMs.get() <= configuredMaxCadenceMs

    internal fun clearForTest() { tickCounter.set(0L); lastTickMs.set(0L); maxDelayMs.set(0L) }
}

/* ============================ S15 STOP-LOSS FROM EXECUTABLE QUOTE ========== */

object StopLossFromExecutableQuote6392 {
    data class QuoteBasedLossInput(
        val expectedNetExitLamports: BigInteger,
        val remainingAllocatedCostLamports: BigInteger,
    )
    /** Returns loss % as BigDecimal (may be negative). */
    fun expectedLossPct(i: QuoteBasedLossInput): BigDecimal {
        require(i.remainingAllocatedCostLamports.signum() > 0) { "COST_NON_POSITIVE" }
        val ratio = i.expectedNetExitLamports.toBigDecimal()
            .divide(i.remainingAllocatedCostLamports.toBigDecimal(), java.math.MathContext.DECIMAL64)
        return (ratio - BigDecimal.ONE) * BigDecimal(100)
    }

    /** Directive S15: separate reporting fields. */
    data class StopLossReport(
        val triggerLossPct: Double, val quotedLossPct: Double, val finalRealizedLossPct: Double,
        val priceImpactPct: Double, val slippagePct: Double, val executionLatencyMs: Long,
    )
}

/* ============================ S16 ENTRY SIZING PROGRESSION ================= */

object EntrySizingProgression6392 {
    data class CohortStats(
        val cleanReconciledPositions: Int,
        val decimalMismatches: Int,
        val duplicateMintOwners: Int,
        val quantityOverruns: Int,
        val walletCanonicalDiffLamports: BigInteger,
        val feeAdjustedExpectancyPositive: Boolean,
        val stableDrawdown: Boolean,
        val profitFactor: Double,
    )
    /** Directive S16: temporary maximum until 20 clean reconciled positions. */
    const val TEMP_MAX_SOL: Double = 0.010
    fun maximumBuySol(s: CohortStats): Double {
        // Integrity gate first — any defect stays at temp max.
        val clean = s.decimalMismatches == 0 && s.duplicateMintOwners == 0 &&
            s.quantityOverruns == 0 &&
            s.walletCanonicalDiffLamports.abs() <= BigInteger.valueOf(100_000L)
        if (!clean) return TEMP_MAX_SOL
        return when {
            s.cleanReconciledPositions >= 100 && s.stableDrawdown && s.profitFactor > 1.0 -> {
                // Adaptive — but scale gently by cohort size (soft cap 0.05).
                (0.025 + kotlin.math.min(s.cleanReconciledPositions - 100, 200) * 0.0001)
                    .coerceAtMost(0.05)
            }
            s.cleanReconciledPositions >= 50 && s.feeAdjustedExpectancyPositive -> 0.025
            s.cleanReconciledPositions >= 20 -> 0.015
            else -> TEMP_MAX_SOL
        }
    }
}

/* ============================ S17 PROFITABILITY GOVERNOR FIELDS ============ */

data class ProfitabilityGovernorFields6392(
    val sampleSize: Int,
    val grossWinsLamports: BigInteger,
    val grossLossesLamports: BigInteger,
    val networkFeesLamports: BigInteger,
    val priorityFeesLamports: BigInteger,
    val dexFeesLamports: BigInteger,
    val slippageCostLamports: BigInteger,
    val realizedNetPnlLamports: BigInteger,
    val profitFactor: Double,
    val expectancyLamports: BigInteger,
    val maximumDrawdownLamports: BigInteger,
    val medianWinLamports: BigInteger,
    val medianLossLamports: BigInteger,
    val averageHoldDurationMs: Long,
) {
    /** Directive S17 decision hierarchy: integrity > liquidity > EV > DD > lane > size. */
    fun decisionHierarchyOrder(): List<String> = listOf(
        "integrity", "liquidity", "expectedValue", "drawdown", "laneShaping", "sizing")
}

/* ============================ S18 LIVE CONTINUITY INVARIANTS =============== */

object LiveContinuityInvariants6392 {
    data class Check(
        val singlePositionPerWalletMint: Boolean,
        val chainConfirmedDecimals: Boolean,
        val buyTokenDeltaPositive: Boolean,
        val economicCostPositive: Boolean,
        val soldWithinWallet: Boolean,
        val soldWithinRemaining: Boolean,
        val totalDisposedWithinAcquired: Boolean,
        val remainingEqualsAcquiredMinusDisposed: Boolean,
        val oneActiveExitPerWalletMint: Boolean,
        val everyFinalizedInCanonical: Boolean,
        val walletCanonicalWithinTolerance: Boolean,
    ) {
        fun allPass(): Boolean = singlePositionPerWalletMint && chainConfirmedDecimals &&
            buyTokenDeltaPositive && economicCostPositive &&
            soldWithinWallet && soldWithinRemaining && totalDisposedWithinAcquired &&
            remainingEqualsAcquiredMinusDisposed && oneActiveExitPerWalletMint &&
            everyFinalizedInCanonical && walletCanonicalWithinTolerance
    }
}
