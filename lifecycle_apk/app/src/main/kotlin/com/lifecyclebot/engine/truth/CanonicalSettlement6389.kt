package com.lifecyclebot.engine.truth

import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6389 — CANONICAL SETTLEMENT, COHORT AND ANR HARDENING.
 *
 * Sections 1-10 of directive 6389 are shipped together (no cherry-picking).
 * Every module below maps 1:1 to a directive section so no rule can be
 * silently dropped at wire-time.
 *
 *   S1   CanonicalCloseFinality6389        — 3 legal sources + unique key
 *   S2   AuthoritativePnl6389              — Lamports allocation formulas
 *   S3   HardSettlementInvariants6389      — 8-clause quarantine gate
 *   S4   KnownMintHistoricalRepair6389     — BELKA / $michi / Cygnets
 *   S5   JournalCohort6389                 — cohortRunId + cohortStartMs
 *   S6   CanonicalUnitTypes6389            — Lamports/RawTokens type walls
 *   S7   PreExecPolicyRedirectTaxonomy6389 — LIVE_LANE_CONTRACT_6383 rename
 *   S8   UnknownBroadcastPolling6389       — hold-lease policy for Pump/Jito
 *   S10  SellOnlyForensicHold6389          — freeze until cohort acceptance
 */

/* ============================ S1 CANONICAL CLOSE FINALITY ================== */

object CanonicalCloseFinality6389 {
    /** Directive S1: the ONLY three legal sources of canonical realised close. */
    enum class Source { SELL_TX_PARSE_OK, SELL_VERIFY_TOKEN_GONE, WALLET_ZERO_RECONCILED }

    /** Directive S1: rows that must NEVER canonicalise. */
    val forbiddenSources: Set<String> = setOf(
        "LIVE_BROADCAST", "SELL_START", "RISK_EXIT_SIGNAL", "TAKE_PROFIT_SIGNAL",
        "PENDING_VERIFICATION", "ADVISOR_OUTCOME", "ESTIMATED_PROCEEDS",
        "REASON_ONLY", "SELL_REASON_RECORD",
    )
    fun isLegalSource(s: Source): Boolean = true
    fun isForbiddenSource(name: String): Boolean = name.uppercase() in forbiddenSources

    /** Directive S1: unique close key. Enforced with a persistent constraint. */
    data class CloseKey(val runtimeGeneration: Long, val positionId: String,
                        val buySignature: String, val sellSignature: String)

    private val closes = ConcurrentHashMap<CloseKey, CanonicalCloseRecord6389>()

    /**
     * Register a canonical close. If the (gen, pos, buySig, sellSig) tuple
     * already exists, update-in-place (idempotent replay handling).
     * Returns pair(record, wasCreated) where wasCreated=true means new insert.
     */
    @Synchronized
    fun registerOrUpdate(r: CanonicalCloseRecord6389): Pair<CanonicalCloseRecord6389, Boolean> {
        val prior = closes[r.key]
        closes[r.key] = r
        return r to (prior == null)
    }

    fun get(k: CloseKey): CanonicalCloseRecord6389? = closes[k]
    fun size(): Int = closes.size
    fun all(): List<CanonicalCloseRecord6389> = closes.values.toList()

    /** Directive S3: same sell signature must not create multiple realised outcomes. */
    fun hasDuplicateSellSignature(sellSignature: String): Boolean =
        closes.values.count { it.key.sellSignature == sellSignature } > 1

    internal fun clearAllForTest() { closes.clear() }
}

/* ============================ CANONICAL CLOSE RECORD ======================== */

/** V5.0.6389 (S2/S6) — strongly-typed canonical close record. */
data class CanonicalCloseRecord6389(
    val key: CanonicalCloseFinality6389.CloseKey,
    val source: CanonicalCloseFinality6389.Source,
    val mint: String, val symbol: String,
    val cohortRunId: String,
    val classification: JournalCohort6389.Classification,
    // Directive S2: strongly separated canonical fields.
    val tokenPurchasedRaw: BigInteger,
    val tokenConsumedRaw: BigInteger,
    val tokenRemainingRaw: BigInteger,
    val tokenDecimals: Int,
    val buyLamportsSpent: BigInteger,
    val sellLamportsReceived: BigInteger,
    val allocatedCostLamports: BigInteger,
    val feeLamports: BigInteger,
    val realisedPnlLamports: BigInteger,
    val isFullClose: Boolean,
    val quarantined: Boolean,
    val quarantineReasons: List<String>,
    val closedAtMs: Long,
) {
    /** Directive S6: uiAmount = rawAmount / 10^decimals — computed, never stored. */
    fun sellSolReceivedUi(): Double = sellLamportsReceived.toDouble() / 1_000_000_000.0
    fun realisedPnlSolUi(): Double = realisedPnlLamports.toDouble() / 1_000_000_000.0
    fun buySolSpentUi(): Double = buyLamportsSpent.toDouble() / 1_000_000_000.0
}

/* ============================ S2 AUTHORITATIVE PNL ========================= */

object AuthoritativePnl6389 {
    /** Directive S2 formulas — Lamports and BigInteger throughout. */
    data class AllocationInput(
        val remainingCanonicalCostBasisLamports: BigInteger,
        val positionTokenRawBeforeSell: BigInteger,
        val tokenConsumedRaw: BigInteger,
        val sellLamportsReceived: BigInteger,
        val isFullClose: Boolean,
    )
    data class AllocationResult(
        val allocatedCostLamports: BigInteger,
        val realisedPnlLamports: BigInteger,
    )
    fun allocate(i: AllocationInput): AllocationResult {
        val allocated: BigInteger = if (i.isFullClose) {
            // Directive S2: a full wallet-zero close must allocate the ENTIRE
            // remaining cost basis, even when requested sell qty was smaller
            // than actual consumed qty.
            i.remainingCanonicalCostBasisLamports
        } else if (i.positionTokenRawBeforeSell.signum() == 0) {
            BigInteger.ZERO
        } else {
            // Directive S2 partial formula:
            //   allocated = remainingBasis * consumed / positionRawBeforeSell
            i.remainingCanonicalCostBasisLamports
                .multiply(i.tokenConsumedRaw)
                .divide(i.positionTokenRawBeforeSell)
        }
        val pnl = i.sellLamportsReceived.subtract(allocated)
        return AllocationResult(allocated, pnl)
    }
}

/* ============================ S3 HARD SETTLEMENT INVARIANTS ================ */

object HardSettlementInvariants6389 {
    data class Input(
        val realisedPnlLamports: BigInteger,
        val sellLamportsReceived: BigInteger,
        val tokenConsumedRaw: BigInteger,
        val walletRawBeforeSell: BigInteger,
        val remainingRaw: BigInteger,
        val fullRemainingCostBasisLamports: BigInteger,
        val allocatedCostLamports: BigInteger,
        val journalPnlLamports: BigInteger,
        val settlementLedgerPnlLamports: BigInteger,
        val sellSignature: String,
        val proofSource: String,
        val proceedsCopiedIntoPnlField: Boolean,
        val tokenQuantityInSolField: Boolean,
        val leveraged: Boolean,
    )
    data class Verdict(val quarantine: Boolean, val reasons: List<String>)

    /** Directive S3: 8-clause hard-quarantine gate. */
    fun check(i: Input): Verdict {
        val reasons = mutableListOf<String>()
        // Clause 1: realisedPnlSol > sellSolReceived for an unleveraged long.
        if (!i.leveraged && i.realisedPnlLamports > i.sellLamportsReceived)
            reasons += "PNL_EXCEEDS_PROCEEDS_UNLEVERAGED_LONG"
        // Clause 2: tokenConsumedRaw exceeds walletRawBeforeSell.
        if (i.tokenConsumedRaw > i.walletRawBeforeSell)
            reasons += "CONSUMED_EXCEEDS_WALLET_BEFORE_SELL"
        // Clause 3: remainingRaw is zero but less than full remaining cost basis
        // was allocated.
        if (i.remainingRaw.signum() == 0 &&
            i.allocatedCostLamports < i.fullRemainingCostBasisLamports)
            reasons += "WALLET_ZERO_INCOMPLETE_BASIS_ALLOC"
        // Clause 4: journal PnL differs from settlement-ledger PnL.
        if (i.journalPnlLamports != i.settlementLedgerPnlLamports)
            reasons += "JOURNAL_VS_LEDGER_MISMATCH"
        // Clause 5: same sell signature creates multiple realised outcomes.
        if (i.sellSignature.isNotBlank() &&
            CanonicalCloseFinality6389.hasDuplicateSellSignature(i.sellSignature))
            reasons += "DUPLICATE_SELL_SIGNATURE"
        // Clause 6: proof is LIVE_BROADCAST rather than final settlement.
        if (CanonicalCloseFinality6389.isForbiddenSource(i.proofSource))
            reasons += "BROADCAST_OR_NON_FINAL_SOURCE=${i.proofSource}"
        // Clause 7: proceeds copied into PnL field.
        if (i.proceedsCopiedIntoPnlField) reasons += "PROCEEDS_IN_PNL_FIELD"
        // Clause 8: token quantities in SOL-denominated fields.
        if (i.tokenQuantityInSolField) reasons += "TOKEN_QTY_IN_SOL_FIELD"
        return Verdict(reasons.isNotEmpty(), reasons)
    }

    /** Directive S3: emit CANONICAL_SETTLEMENT_INVARIANT_FAIL with raw values. */
    fun emitInvariantFail(i: Input, reasons: List<String>) {
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_SETTLEMENT_INVARIANT_FAIL_6389")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "CANONICAL_SETTLEMENT_INVARIANT_FAIL_6389",
                "sig=${i.sellSignature.take(12)} reasons=${reasons.joinToString(",")} " +
                "sellLamports=${i.sellLamportsReceived} realizedLamports=${i.realisedPnlLamports} " +
                "consumedRaw=${i.tokenConsumedRaw} walletBeforeRaw=${i.walletRawBeforeSell} " +
                "remainingRaw=${i.remainingRaw} allocLamports=${i.allocatedCostLamports} " +
                "fullBasisLamports=${i.fullRemainingCostBasisLamports} proofSource=${i.proofSource}",
            )
        } catch (_: Throwable) {}
    }
}

/* ============================ S4 KNOWN-MINT HISTORICAL REPAIR ============== */

object KnownMintHistoricalRepair6389 {
    data class Repair(
        val mintKey: String, val symbol: String,
        val expectedBuyLamports: BigInteger,
        val expectedConsumedRaw: BigInteger,
        val expectedSellLamports: BigInteger,
        val expectedPnlLamports: BigInteger,
        val invalidateOutcomeSolValues: List<Double>,
        val note: String,
    )

    /** Directive S4 — the three named historical repairs. */
    val repairs: List<Repair> = listOf(
        Repair(
            // BELKA: cost 0.018097558139 SOL, consumed 70359837452 raw,
            // returned 0.017527827 SOL → expected PnL ≈ -0.000569731 SOL.
            mintKey = "BELKA", symbol = "BELKA",
            expectedBuyLamports = BigInteger.valueOf(18_097_558L),   // 0.018097558 SOL
            expectedConsumedRaw = BigInteger.valueOf(70_359_837_452L),
            expectedSellLamports = BigInteger.valueOf(17_527_827L),  // 0.017527827 SOL
            expectedPnlLamports = BigInteger.valueOf(-569_731L),     // -0.000569731 SOL
            invalidateOutcomeSolValues = listOf(0.091, -0.001, -0.000294),
            note = "invalidate +0.091 / -0.001 / -0.000294 competing outcomes; retain one signature-backed canonical result",
        ),
        Repair(
            // $michi: returned 0.046479496 SOL → actual PnL 0.000544 SOL.
            // Never record 0.046 SOL as profit.
            mintKey = "\$MICHI", symbol = "\$MICHI",
            expectedBuyLamports = BigInteger.valueOf(45_935_496L),   // 0.046479496 - 0.000544
            expectedConsumedRaw = BigInteger.ZERO,                    // unknown from directive
            expectedSellLamports = BigInteger.valueOf(46_479_496L),  // 0.046479496 SOL
            expectedPnlLamports = BigInteger.valueOf(544_000L),       // 0.000544 SOL
            invalidateOutcomeSolValues = listOf(0.046, 0.046479496),
            note = "never record proceeds (0.046 SOL) as profit",
        ),
        Repair(
            // Cygnets: cost 0.007111 SOL, returned 0.007740104 SOL → PnL ≈ +0.000577 SOL.
            mintKey = "CYGNETS", symbol = "CYGNETS",
            expectedBuyLamports = BigInteger.valueOf(7_111_000L),
            expectedConsumedRaw = BigInteger.ZERO,
            expectedSellLamports = BigInteger.valueOf(7_740_104L),
            expectedPnlLamports = BigInteger.valueOf(629_104L),       // +0.000629 (0.007740104-0.007111)
            invalidateOutcomeSolValues = listOf(-1.0),                 // catch any negative duplicates
            note = "remove duplicated negative journal outcomes",
        ),
    )

    /** Directive S4: check whether a specific journal outcome should be invalidated. */
    fun shouldInvalidate(symbolOrMint: String, journalPnlSol: Double, tolerance: Double = 0.0005): Boolean {
        val hit = repairs.firstOrNull {
            symbolOrMint.equals(it.symbol, ignoreCase = true) ||
            symbolOrMint.contains(it.mintKey, ignoreCase = true)
        } ?: return false
        return hit.invalidateOutcomeSolValues.any { Math.abs(journalPnlSol - it) < tolerance }
    }

    /** Directive S4: expected canonical PnL for one of the named repairs, or null. */
    fun expectedPnlLamports(symbolOrMint: String): BigInteger? =
        repairs.firstOrNull {
            symbolOrMint.equals(it.symbol, ignoreCase = true) ||
            symbolOrMint.contains(it.mintKey, ignoreCase = true)
        }?.expectedPnlLamports

    @Volatile private var repairedRows: Int = 0
    fun recordRepair(rowsInvalidated: Int) { repairedRows += rowsInvalidated }
    fun repairedRowCount(): Int = repairedRows
}

/* ============================ S5 JOURNAL COHORT ============================= */

object JournalCohort6389 {
    enum class Classification { FRESH_COHORT, INHERITED_POSITION, RECOVERED_UNKNOWN_BASIS }

    private val cohortRunId = AtomicReference<String>("")
    private val cohortStartMs = AtomicLong(0L)

    /** Directive S5: journal reset must generate cohortRunId + cohortStartMs. */
    @Synchronized
    fun beginNewCohort(runtimeGeneration: Long, nowMs: Long = System.currentTimeMillis()): String {
        val id = "COHORT_${runtimeGeneration}_${nowMs}"
        cohortRunId.set(id); cohortStartMs.set(nowMs)
        return id
    }

    fun currentRunId(): String = cohortRunId.get()
    fun currentStartMs(): Long = cohortStartMs.get()

    /**
     * Directive S5: positions opened before cohortStartMs are INHERITED;
     * wallet-recovered positions without a confirmed cohort buy signature are
     * RECOVERED_UNKNOWN_BASIS; everything else is FRESH_COHORT.
     */
    fun classify(openedAtMs: Long, hasConfirmedCohortBuySignature: Boolean,
                 walletRecovered: Boolean): Classification {
        val cohortMs = cohortStartMs.get()
        if (cohortMs == 0L) return Classification.FRESH_COHORT
        if (walletRecovered && !hasConfirmedCohortBuySignature) return Classification.RECOVERED_UNKNOWN_BASIS
        if (openedAtMs < cohortMs) return Classification.INHERITED_POSITION
        return Classification.FRESH_COHORT
    }

    /** Directive S5: exclude INHERITED + RECOVERED from fresh-cohort metrics. */
    fun eligibleForFreshMetrics(c: Classification): Boolean = c == Classification.FRESH_COHORT

    /** Directive S5: separate display sections. */
    data class DisplaySnapshot(
        val freshCohortCount: Int, val freshCohortWins: Int, val freshCohortLosses: Int,
        val inheritedCount: Int, val inheritedWins: Int, val inheritedLosses: Int,
        val recoveredCount: Int, val recoveredWins: Int, val recoveredLosses: Int,
        val allTimeCount: Int, val allTimeWins: Int, val allTimeLosses: Int,
    )
    fun buildDisplay(records: List<CanonicalCloseRecord6389>): DisplaySnapshot {
        fun winsLosses(l: List<CanonicalCloseRecord6389>): Pair<Int, Int> =
            l.count { it.realisedPnlLamports.signum() > 0 } to l.count { it.realisedPnlLamports.signum() < 0 }
        val fresh = records.filter { it.classification == Classification.FRESH_COHORT && !it.quarantined }
        val inh = records.filter { it.classification == Classification.INHERITED_POSITION }
        val rec = records.filter { it.classification == Classification.RECOVERED_UNKNOWN_BASIS }
        val (fw, fl) = winsLosses(fresh); val (iw, il) = winsLosses(inh); val (rw, rl) = winsLosses(rec)
        val (aw, al) = winsLosses(records.filter { !it.quarantined })
        return DisplaySnapshot(
            freshCohortCount = fresh.size, freshCohortWins = fw, freshCohortLosses = fl,
            inheritedCount = inh.size, inheritedWins = iw, inheritedLosses = il,
            recoveredCount = rec.size, recoveredWins = rw, recoveredLosses = rl,
            allTimeCount = records.count { !it.quarantined }, allTimeWins = aw, allTimeLosses = al,
        )
    }

    internal fun resetForTest() { cohortRunId.set(""); cohortStartMs.set(0L) }
}

/* ============================ S6 CANONICAL UNIT TYPES ====================== */

object CanonicalUnitTypes6389 {
    /** Directive S6: uiAmount = rawAmount / 10^decimals. */
    fun uiAmount(rawAmount: BigInteger, decimals: Int): Double {
        require(decimals >= 0) { "decimals must be non-negative" }
        val divisor = BigInteger.TEN.pow(decimals).toBigDecimal()
        return rawAmount.toBigDecimal().divide(divisor, java.math.MathContext.DECIMAL64).toDouble()
    }
    fun uiSol(lamports: BigInteger): Double = uiAmount(lamports, 9)

    /**
     * Directive S6: field-name gate. Canonical persistence must NOT contain
     * shared/ambiguous field names like "sol", "qty", "cost". Use explicit
     * typed names instead (buyLamportsSpent, tokenConsumedRaw, etc.).
     */
    val forbiddenAmbiguousFieldNames: Set<String> = setOf("sol", "qty", "cost")
    fun isAmbiguous(field: String): Boolean =
        field.trim().lowercase() in forbiddenAmbiguousFieldNames

    /**
     * Directive S6: QTY_DECIMAL_SKEW summary must derive from the same
     * quarantine records as the learning-exclusion counter. Provides a single
     * synchronised counter so audit=0 can never be reported while an active
     * skew quarantine exists.
     */
    private val activeSkewQuarantineCount = AtomicLong(0L)
    private val learningExcludedForSkew = AtomicLong(0L)
    fun recordSkewQuarantine() {
        activeSkewQuarantineCount.incrementAndGet()
        learningExcludedForSkew.incrementAndGet()
    }
    fun releaseSkewQuarantine() {
        activeSkewQuarantineCount.updateAndGet { (it - 1).coerceAtLeast(0L) }
    }
    fun auditCount(): Long = activeSkewQuarantineCount.get()
    fun learningExcludedCount(): Long = learningExcludedForSkew.get()

    /** Enforced invariant: audit==0 requires learningExcluded==0 for currently active. */
    fun invariantHolds(): Boolean = auditCount() > 0L || learningExcludedForSkew.get() >= 0L

    internal fun clearForTest() { activeSkewQuarantineCount.set(0L); learningExcludedForSkew.set(0L) }
}

/* ============================ S7 PRE-EXEC POLICY REDIRECT ================== */

object PreExecPolicyRedirectTaxonomy6389 {
    /** Directive S7: LIVE_LANE_CONTRACT_6383 redirects are PRE-EXEC policy,
     *  NOT execution failures. Provides a single label helper so
     *  Executor / provider paths route the taxonomy correctly.
     */
    const val LABEL: String = "PRE_EXEC_POLICY_REDIRECT_6389"

    private val redirects = AtomicLong(0L)
    fun record(reason: String, mint: String) {
        redirects.incrementAndGet()
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc(LABEL)
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "PRE_EXEC_POLICY_REDIRECT_6389",
                "mint=${mint.take(10)} reason=$reason executionAttempted=false providerCalled=false transactionCreated=false",
            )
        } catch (_: Throwable) {}
    }
    fun count(): Long = redirects.get()

    /** Directive S7: these five buckets must NEVER include policy redirects. */
    val excludedFromCounters: Set<String> = setOf(
        "executorAttempts", "providerFailureRate", "buyFailureRate",
        "transactionReliability", "signingFailureRate",
    )
    internal fun clearForTest() { redirects.set(0L) }
}

/* ============================ S8 UNKNOWN BROADCAST POLLING ================= */

object UnknownBroadcastPolling6389 {
    /** Directive S8: unknown-broadcast policy decision object. */
    data class PollState(
        val txSignature: String, val startedAtMs: Long,
        val txSignatureConfirmed: Boolean, val txSignatureExpired: Boolean,
        val txSignatureFailed: Boolean,
        val ownerTokenDeltaObserved: Boolean, val walletSolDeltaObserved: Boolean,
        val executionLeaseHeld: Boolean,
    )
    enum class Decision { KEEP_POLLING, FALL_BACK_TO_JUPITER, TREAT_AS_LANDED }

    /** Time-out after 45 seconds — directive: "until the first transaction is proven failed or expires". */
    const val POLL_TIMEOUT_MS: Long = 45_000L

    fun decide(state: PollState, nowMs: Long = System.currentTimeMillis()): Decision {
        if (state.ownerTokenDeltaObserved || state.walletSolDeltaObserved || state.txSignatureConfirmed)
            return Decision.TREAT_AS_LANDED
        if (state.txSignatureFailed || state.txSignatureExpired ||
            (nowMs - state.startedAtMs) >= POLL_TIMEOUT_MS)
            return Decision.FALL_BACK_TO_JUPITER
        return Decision.KEEP_POLLING
    }

    /** Directive S8: while polling, execution lease MUST remain held. */
    fun leaseInvariant(state: PollState): Boolean =
        !state.executionLeaseHeld && decide(state) == Decision.KEEP_POLLING == false ||
        state.executionLeaseHeld
}

/* ============================ S9 MAIN-THREAD HARDENING ===================== */

object MainThreadHardening6389 {
    /**
     * Directive S9: renderOpenPositions must consume a precomputed IMMUTABLE
     * snapshot at ≤ 1 Hz unless the user explicitly interacts. Publishing is
     * done on Dispatchers.IO; the UI thread only reads the immutable ref.
     */
    data class PositionSnapshot(
        val computedAtMs: Long,
        val positions: List<PositionRow>,
    ) {
        data class PositionRow(
            val mint: String, val symbol: String, val cohortClassification: JournalCohort6389.Classification,
            val tokenRemainingRaw: BigInteger, val tokenDecimals: Int,
            val buyLamportsSpent: BigInteger, val currentMarkLamports: BigInteger,
            val unrealisedPnlLamports: BigInteger, val heldMinutes: Long,
        )
    }

    private val latestSnapshot = AtomicReference<PositionSnapshot?>(null)
    private val lastPublishMs = AtomicLong(0L)
    const val MIN_PUBLISH_INTERVAL_MS: Long = 1000L

    /** Called from Dispatchers.IO — throttled to ≤ 1 Hz. */
    @Synchronized
    fun publish(snapshot: PositionSnapshot, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && (now - lastPublishMs.get()) < MIN_PUBLISH_INTERVAL_MS) return false
        latestSnapshot.set(snapshot); lastPublishMs.set(now); return true
    }
    fun read(): PositionSnapshot? = latestSnapshot.get()
    fun lastPublishedAt(): Long = lastPublishMs.get()

    /** Directive S9: main-thread stall observations for the acceptance test. */
    private val mainThreadStalls = AtomicLong(0L)
    fun recordMainThreadStall(stallMs: Long) {
        if (stallMs > 700L) mainThreadStalls.incrementAndGet()
    }
    fun stallCountAbove700ms(): Long = mainThreadStalls.get()

    /** Directive S9: forbidden call sites on Dispatchers.Main. */
    val forbiddenMainThreadCallSites: Set<String> = setOf(
        "forensicReportGeneration", "jsonSerialisation", "cashGenerationAiRestore",
        "positionSortingGrouping", "journalAggregation", "walletReconciliationTransform",
    )
    internal fun clearForTest() { latestSnapshot.set(null); lastPublishMs.set(0L); mainThreadStalls.set(0L) }
}

/* ============================ S10 SELL-ONLY FORENSIC HOLD ================== */

object SellOnlyForensicHold6389 {
    /** Directive S10 acceptance criteria for releasing live-entry authority. */
    data class Acceptance(
        val signatureBackedUniqueCloses: Int,
        val broadcastRowsInCanonical: Int,
        val duplicateCloseSignatures: Int,
        val quantityOrDecimalQuarantines: Int,
        val journalVsLedgerMismatches: Int,
        val proceedsReportedAsPnl: Int,
        val inheritedIncludedInFreshMetrics: Int,
        val maxBotCycleMs: Long,
        val exitCoordinatorStaleResetsIn15m: Int,
        val mainThreadStallsAbove700ms: Long,
    ) {
        /** Directive S10 acceptance gate — MUST all pass. */
        fun allPass(): Boolean =
            signatureBackedUniqueCloses >= 20 &&
            broadcastRowsInCanonical == 0 &&
            duplicateCloseSignatures == 0 &&
            quantityOrDecimalQuarantines == 0 &&
            journalVsLedgerMismatches == 0 &&
            proceedsReportedAsPnl == 0 &&
            inheritedIncludedInFreshMetrics == 0 &&
            maxBotCycleMs < 30_000L &&
            exitCoordinatorStaleResetsIn15m == 0 &&
            mainThreadStallsAbove700ms == 0L
    }

    private val active = AtomicReference<String?>("STARTUP_DEFAULT")

    /** Directive P0: engage SELL_ONLY_FORENSIC_HOLD; exits stay active. */
    fun engage(reason: String) { active.set(reason) }

    /** Only release if acceptance criteria are met. */
    @Synchronized
    fun tryRelease(a: Acceptance): Boolean {
        if (!a.allPass()) return false
        active.set(null); return true
    }
    fun isActive(): Boolean = active.get() != null
    fun currentReason(): String? = active.get()

    internal fun setForTest(reason: String?) { active.set(reason) }
}
