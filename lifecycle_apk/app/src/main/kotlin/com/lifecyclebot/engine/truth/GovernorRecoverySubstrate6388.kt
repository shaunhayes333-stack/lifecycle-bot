package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6388 — GOVERNOR RECOVERY SUPPORT SUBSTRATE
 *
 * Sole purpose: ship every section of the 6388 directive that is NOT already
 * covered by the standalone GovernorRecovery6388 state-machine module. Every
 * object below maps 1:1 to a directive section so no rule can be silently
 * cherry-picked at wire-time.
 *
 *   S12  EvidenceEpochFilter6388          — recoveryEvidenceEpoch=6388 isolation
 *   S13  PolicyBlockDedup6388             — governor HOLD is a policy block
 *                                            (lives in GovernorRecovery6388.kt)
 *   S14  ReconcilerLivenessAuthority6388  — reconciler MUST start before entry
 *   S15  WalletPositionAuthority6388      — 6-way owner-mint classification
 *   S16  BuyFillLedger6388                — immutable buy fill records
 *   S17  SellFillLedger6388               — immutable sell fill records (partial-safe)
 *   S18  CanonicalTradeSummary6388        — aggregate fills → single lifecycle
 *   S19  PartialExitStateMachineFull6388  — 14-state ladder (extends 6387)
 *   S20  HardStopFullExit6388             — HARD_STOP/RUG/etc default 100%
 *   S21  SellLeaseIntegrity6388           — one lease per (gen,posId,exitIntent)
 *   S22  ForensicExportMode6388           — primary vs fallback visibility
 *   S23  JournalEpoch6388                 — archive-not-delete, epoch linking
 *   S24  RecoveryHealthSnapshot6388       — sole read authority for dumps
 *   S26  AcceptanceScript6388             — invariant checklist for CI
 */

/* ============================ S12 EVIDENCE EPOCH =========================== */

object EvidenceEpochFilter6388 {
    const val EPOCH: Int = 6388

    /** Directive S12: post-fix recovery calculations must filter by epoch. */
    fun isRecoveryEligible(rowEvidenceEpoch: Int, canonicalFinalised: Boolean,
                           accountingReconciled: Boolean, signaturesComplete: Boolean,
                           quantityIntegrity: Boolean, decimalIntegrity: Boolean): Boolean =
        rowEvidenceEpoch >= EPOCH && canonicalFinalised && accountingReconciled &&
        signaturesComplete && quantityIntegrity && decimalIntegrity

    /** Directive S12: mixed historical rows remain visible but excluded. */
    fun isHistoricalAudit(rowEvidenceEpoch: Int): Boolean = rowEvidenceEpoch < EPOCH
}

/* ============================ S14 RECONCILER LIVENESS ====================== */

object ReconcilerLivenessAuthority6388 {
    data class Snapshot(
        val sellReconcilerStarted: Boolean,
        val sellReconcilerActiveJobId: String,
        val sellReconcilerRuntimeGeneration: Long,
        val sellReconcilerStartedAtMs: Long,
        val sellReconcilerTotalTicks: Long,
        val sellReconcilerLastAttemptAtMs: Long,
        val sellReconcilerLastSuccessAtMs: Long,
        val sellReconcilerTickAgeMs: Long,
        val sellReconcilerCheckedMints: Long,
        val sellReconcilerConclusiveMints: Long,
        val sellReconcilerInconclusiveMints: Long,
        val sellReconcilerRestoredPositions: Long,
        val sellReconcilerClosedAbsentPositions: Long,
        val sellReconcilerFailures: Long,
        val sellReconcilerRestartCount: Long,
        val walletReconciliationConclusive: Boolean,
        val canonicalRegistryAvailable: Boolean,
        val fillLedgersAvailable: Boolean,
        val configuredReconcilerHealthLimitMs: Long,
    )

    /** Directive S14 canonical eligibility formula, verbatim. */
    fun liveEntryInfrastructureHealthy(s: Snapshot): Boolean =
        s.sellReconcilerStarted &&
        s.sellReconcilerTotalTicks > 0L &&
        s.sellReconcilerTickAgeMs <= s.configuredReconcilerHealthLimitMs &&
        s.walletReconciliationConclusive &&
        s.canonicalRegistryAvailable &&
        s.fillLedgersAvailable

    /** Directive S14 mandatory invariant. */
    fun p1FaultLiveReconcilerMissingWithHoldings(
        runtimeActive: Boolean, paperMode: Boolean, walletHeldMints: Int, isStarted: Boolean,
    ): Boolean = runtimeActive && !paperMode && walletHeldMints > 0 && !isStarted
}

/* ============================ S15 WALLET-POSITION AUTHORITY ================ */

object WalletPositionAuthority6388 {
    enum class Class {
        CANONICAL_OPEN, RESTORED_OPEN_KNOWN_BASIS, RESTORED_OPEN_UNKNOWN_BASIS,
        EXTERNAL_WALLET_HOLDING, EXCLUDED_KNOWN_DUST, QUARANTINED_UNSUPPORTED_ASSET,
    }
    data class Distribution(
        val walletHeldMints: Int,
        val canonicalOpen: Int, val restoredKnownBasis: Int, val restoredUnknownBasis: Int,
        val externalHoldings: Int, val excludedDust: Int, val quarantinedAssets: Int,
    ) {
        val sum: Int get() = canonicalOpen + restoredKnownBasis + restoredUnknownBasis +
            externalHoldings + excludedDust + quarantinedAssets
        val balances: Boolean get() = sum == walletHeldMints
    }

    /** Directive S15 reconciliation-equation invariant. */
    fun invariantHolds(d: Distribution): Boolean = d.balances
}

/* ============================ S16 BUY FILL LEDGER ========================== */

data class BuyFillRecord6388(
    val fillId: String, val positionId: String, val mint: String, val symbol: String,
    val lane: String, val tactic: String, val strategy: String,
    val executionAuthority: String, val governorState: String, val recoveryState: String,
    val evidenceEpoch: Int, val signature: String, val slot: Long, val blockTime: Long,
    val requestedSol: Double, val actualSolSpentGross: Double,
    val networkFeeSol: Double, val priorityFeeSol: Double, val platformFeeSol: Double,
    val actualSolSpentNet: Double, val tokenRawReceived: java.math.BigInteger,
    val tokenUiReceived: Double, val tokenDecimals: Int,
    val effectiveEntryPriceUsd: Double, val marketCapAtEntryUsd: Double,
    val liquidityAtEntryUsd: Double, val quoteProvider: String, val executionRoute: String,
    val slippageBps: Int, val finality: String, val runtimeGeneration: Long,
    val createdAtMs: Long,
)

object BuyFillLedger6388 {
    private val bySignature = ConcurrentHashMap<String, BuyFillRecord6388>()
    private val byPosition = ConcurrentHashMap<String, MutableList<BuyFillRecord6388>>()

    /** Directive S16: duplicate signature is rejected. Directive S25 test coverage. */
    @Synchronized
    fun record(r: BuyFillRecord6388): Boolean {
        if (r.signature.isBlank()) return false
        if (bySignature.putIfAbsent(r.signature, r) != null) return false
        byPosition.getOrPut(r.positionId) { mutableListOf() }.add(r)
        return true
    }
    fun bySignature(sig: String): BuyFillRecord6388? = bySignature[sig]
    fun forPosition(positionId: String): List<BuyFillRecord6388> = byPosition[positionId].orEmpty().toList()
    fun size(): Int = bySignature.size
    internal fun clearAllForTest() { bySignature.clear(); byPosition.clear() }
}

/* ============================ S17 SELL FILL LEDGER ========================= */

data class SellFillRecord6388(
    val fillId: String, val positionId: String, val mint: String, val symbol: String,
    val signature: String, val slot: Long, val blockTime: Long,
    val exitIntentId: String, val exitReason: String,
    val requestedRaw: java.math.BigInteger, val requestedUi: Double,
    val actualConsumedRaw: java.math.BigInteger, val actualConsumedUi: Double,
    val preBalanceRaw: java.math.BigInteger, val postBalanceRaw: java.math.BigInteger,
    val remainingRaw: java.math.BigInteger, val tokenDecimals: Int,
    val solReceivedGross: Double, val networkFeeSol: Double,
    val priorityFeeSol: Double, val platformFeeSol: Double, val solReceivedNet: Double,
    val allocatedCostBasisSol: Double, val realisedPnlSol: Double, val realisedPnlPct: Double,
    val fillSequence: Int, val sourceRoute: String, val quoteProvider: String,
    val slippageBps: Int, val finality: String, val runtimeGeneration: Long,
    val evidenceEpoch: Int, val createdAtMs: Long,
)

object SellFillLedger6388 {
    private val bySignature = ConcurrentHashMap<String, SellFillRecord6388>()
    private val byPosition = ConcurrentHashMap<String, MutableList<SellFillRecord6388>>()

    /** Directive S17: an on-chain fill CANNOT be discarded because losing/below-floor/etc. */
    @Synchronized
    fun record(r: SellFillRecord6388): Boolean {
        if (r.signature.isBlank()) return false
        if (bySignature.putIfAbsent(r.signature, r) != null) return false
        byPosition.getOrPut(r.positionId) { mutableListOf() }.add(r)
        return true
    }
    fun bySignature(sig: String): SellFillRecord6388? = bySignature[sig]
    fun forPosition(positionId: String): List<SellFillRecord6388> =
        byPosition[positionId].orEmpty().sortedBy { it.fillSequence }
    fun size(): Int = bySignature.size
    internal fun clearAllForTest() { bySignature.clear(); byPosition.clear() }
}

/* ============================ S18 CANONICAL TRADE SUMMARY ================== */

data class CanonicalTradeSummary6388(
    val canonicalTradeId: String, val positionId: String, val mint: String, val symbol: String,
    val lane: String, val tactic: String, val strategy: String, val executionAuthority: String,
    val governorStateAtEntry: String, val recoveryStateAtEntry: String, val evidenceEpoch: Int,
    val buyFillIds: List<String>, val sellFillIds: List<String>,
    val buySignatures: List<String>, val sellSignatures: List<String>,
    val totalEntryCostSol: Double, val totalProceedsSol: Double, val totalFeesSol: Double,
    val realisedPnlSol: Double, val realisedPnlPct: Double,
    val openedAtMs: Long, val closedAtMs: Long, val finalExitReason: String,
    val maximumGainPct: Double, val maximumDrawdownPct: Double,
    val canonicalOutcome: String, val accountingStatus: String, val reconciliationStatus: String,
    val runtimeGeneration: Long,
)

object CanonicalTradeAggregator6388 {
    private val summaries = ConcurrentHashMap<String, CanonicalTradeSummary6388>()

    /** Directive S18: multiple partials → ONE canonical lifecycle. Aggregate. */
    fun aggregate(positionId: String, mint: String, symbol: String, lane: String,
                  tactic: String, strategy: String, executionAuthority: String,
                  governorState: String, recoveryState: String, evidenceEpoch: Int,
                  finalExitReason: String, openedAtMs: Long, closedAtMs: Long,
                  maximumGainPct: Double, maximumDrawdownPct: Double,
                  runtimeGeneration: Long): CanonicalTradeSummary6388 {
        val buys = BuyFillLedger6388.forPosition(positionId)
        val sells = SellFillLedger6388.forPosition(positionId)
        val entryCost = buys.sumOf { it.actualSolSpentNet }
        val proceeds = sells.sumOf { it.solReceivedNet }
        val fees = buys.sumOf { it.networkFeeSol + it.priorityFeeSol + it.platformFeeSol } +
                   sells.sumOf { it.networkFeeSol + it.priorityFeeSol + it.platformFeeSol }
        val pnl = proceeds - entryCost
        val pnlPct = if (entryCost > 0.0) (pnl / entryCost) * 100.0 else 0.0
        val summary = CanonicalTradeSummary6388(
            canonicalTradeId = "CT_${positionId}_${closedAtMs}",
            positionId = positionId, mint = mint, symbol = symbol, lane = lane,
            tactic = tactic, strategy = strategy, executionAuthority = executionAuthority,
            governorStateAtEntry = governorState, recoveryStateAtEntry = recoveryState,
            evidenceEpoch = evidenceEpoch,
            buyFillIds = buys.map { it.fillId }, sellFillIds = sells.map { it.fillId },
            buySignatures = buys.map { it.signature }, sellSignatures = sells.map { it.signature },
            totalEntryCostSol = entryCost, totalProceedsSol = proceeds, totalFeesSol = fees,
            realisedPnlSol = pnl, realisedPnlPct = pnlPct,
            openedAtMs = openedAtMs, closedAtMs = closedAtMs, finalExitReason = finalExitReason,
            maximumGainPct = maximumGainPct, maximumDrawdownPct = maximumDrawdownPct,
            canonicalOutcome = if (pnl > 0.0) "WIN" else if (pnl < 0.0) "LOSS" else "SCRATCH",
            accountingStatus = "RECONCILED", reconciliationStatus = "COMPLETE",
            runtimeGeneration = runtimeGeneration,
        )
        summaries[summary.canonicalTradeId] = summary
        return summary
    }
    fun count(): Int = summaries.size
    fun list(): List<CanonicalTradeSummary6388> = summaries.values.toList()
    internal fun clearAllForTest() { summaries.clear() }
}

/* ============================ S19 PARTIAL EXIT STATE MACHINE (FULL) ======== */

object PartialExitStateMachineFull6388 {
    enum class State {
        NONE, EXIT_INTENT_CREATED, PARTIAL_REQUESTED, PARTIAL_QUOTED, PARTIAL_BROADCAST,
        PARTIAL_CONFIRMED, PARTIAL_RECONCILING, PARTIAL_COOLDOWN, FULL_EXIT_REQUIRED,
        FULL_EXIT_BROADCAST, FULL_EXIT_RECONCILING, CLOSED,
        EXIT_FAILED_RETRYABLE, EXIT_FAILED_TERMINAL,
    }
    data class Entry(val state: State, val enteredAtMs: Long, val cooldownUntilMs: Long)

    private val states = ConcurrentHashMap<String, Entry>()
    const val PARTIAL_COOLDOWN_MS: Long = 30_000L

    fun currentState(positionId: String): State = states[positionId]?.state ?: State.NONE

    private val validTransitions: Map<State, Set<State>> = mapOf(
        State.NONE to setOf(State.EXIT_INTENT_CREATED),
        State.EXIT_INTENT_CREATED to setOf(State.PARTIAL_REQUESTED, State.FULL_EXIT_REQUIRED, State.EXIT_FAILED_TERMINAL),
        State.PARTIAL_REQUESTED to setOf(State.PARTIAL_QUOTED, State.EXIT_FAILED_RETRYABLE),
        State.PARTIAL_QUOTED to setOf(State.PARTIAL_BROADCAST, State.EXIT_FAILED_RETRYABLE),
        State.PARTIAL_BROADCAST to setOf(State.PARTIAL_CONFIRMED, State.EXIT_FAILED_RETRYABLE),
        State.PARTIAL_CONFIRMED to setOf(State.PARTIAL_RECONCILING),
        State.PARTIAL_RECONCILING to setOf(State.PARTIAL_COOLDOWN, State.CLOSED),
        State.PARTIAL_COOLDOWN to setOf(State.PARTIAL_REQUESTED, State.FULL_EXIT_REQUIRED, State.CLOSED),
        State.FULL_EXIT_REQUIRED to setOf(State.FULL_EXIT_BROADCAST, State.EXIT_FAILED_RETRYABLE),
        State.FULL_EXIT_BROADCAST to setOf(State.FULL_EXIT_RECONCILING, State.EXIT_FAILED_RETRYABLE),
        State.FULL_EXIT_RECONCILING to setOf(State.CLOSED),
        State.EXIT_FAILED_RETRYABLE to setOf(State.PARTIAL_REQUESTED, State.FULL_EXIT_REQUIRED, State.EXIT_FAILED_TERMINAL),
        State.CLOSED to emptySet(),
        State.EXIT_FAILED_TERMINAL to emptySet(),
    )

    @Synchronized
    fun transition(positionId: String, next: State,
                   nowMs: Long = System.currentTimeMillis()): Boolean {
        val cur = states[positionId]?.state ?: State.NONE
        if (next !in validTransitions[cur].orEmpty()) return false
        val cooldownUntil = if (next == State.PARTIAL_COOLDOWN) nowMs + PARTIAL_COOLDOWN_MS else 0L
        states[positionId] = Entry(next, nowMs, cooldownUntil)
        return true
    }

    /** Directive S19: suppress the same partial trigger while cooldown active. */
    fun isInCooldown(positionId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val e = states[positionId] ?: return false
        return e.state == State.PARTIAL_COOLDOWN && nowMs < e.cooldownUntilMs
    }

    internal fun clearAllForTest() { states.clear() }
}

/* ============================ S20 HARD-STOP FULL EXIT ====================== */

object HardStopFullExit6388 {
    val fullExitReasons: Set<String> = setOf(
        "HARD_STOP", "UNIVERSAL_STOP_LOSS", "RUG_CONFIRMED",
        "LIQUIDITY_COLLAPSE_CONFIRMED", "DEAD_TOKEN_CONFIRMED",
        "SELLABILITY_DETERIORATION", "POSITION_INTEGRITY_EMERGENCY",
    )

    /** Directive S20: hard stop => 100% liquidation. NEVER repeated 25%. */
    fun requiresFullExit(exitReason: String): Boolean =
        exitReason.uppercase() in fullExitReasons

    data class ChunkPlan(val exitMode: String, val targetRaw: java.math.BigInteger, val exitIntentId: String)

    /** Directive S20: route-chunked full exit shares ONE exitIntentId. */
    fun buildFullExitPlan(currentWalletConfirmedRawBalance: java.math.BigInteger,
                          exitIntentId: String, routeMustChunk: Boolean): ChunkPlan {
        val mode = if (routeMustChunk) "ROUTE_CHUNKED_FULL_EXIT" else "FULL_EXIT"
        return ChunkPlan(mode, currentWalletConfirmedRawBalance, exitIntentId)
    }
}

/* ============================ S21 SELL LEASE INTEGRITY ===================== */

object SellLeaseIntegrity6388 {
    data class Key(val runtimeGeneration: Long, val positionId: String, val exitIntentId: String)
    private val leases = ConcurrentHashMap<Key, Long>()

    /** Directive S21: one lease per (gen, positionId, exitIntentId). */
    @Synchronized
    fun acquire(k: Key): Boolean {
        // Reject overlapping partial exits: any other exitIntent for the same
        // (gen, positionId) must have been released first.
        val overlap = leases.keys.any { it.runtimeGeneration == k.runtimeGeneration &&
                                        it.positionId == k.positionId && it != k }
        if (overlap) return false
        return leases.putIfAbsent(k, System.currentTimeMillis()) == null
    }

    /** Release ONLY after fill recorded, post-balance confirmed, state advanced. */
    @Synchronized
    fun release(k: Key, fillRecorded: Boolean, postBalanceConfirmed: Boolean,
                stateAdvanced: Boolean): Boolean {
        if (!fillRecorded || !postBalanceConfirmed || !stateAdvanced) return false
        return leases.remove(k) != null
    }
    fun isHeld(k: Key): Boolean = leases.containsKey(k)
    fun activeCount(): Int = leases.size
    internal fun clearAllForTest() { leases.clear() }
}

/* ============================ S22 FORENSIC EXPORT MODE ===================== */

object ForensicExportMode6388 {
    enum class Mode { PRIMARY, FALLBACK }
    data class Metadata(
        val exporterMode: Mode, val primaryExporterFailure: String?,
        val exporterStartedAtMs: Long, val exporterCompletedAtMs: Long,
        val runtimeGeneration: Long,
        val oldestIncludedTimestamp: Long, val newestIncludedTimestamp: Long,
        val eventContinuityStatus: String, val omittedSections: List<String>,
        val omittedEventCount: Long, val canonicalFillCoverage: Double,
        val canonicalLifecycleCoverage: Double,
        val checksumAlgorithm: String, val checksum: String,
    )

    /** Directive S22: fallback CANNOT pass full forensic regression guard. */
    fun canPassForensicRegressionGuard(m: Metadata): Boolean =
        m.exporterMode == Mode.PRIMARY &&
        m.canonicalFillCoverage >= 1.0 &&
        m.canonicalLifecycleCoverage >= 1.0 &&
        m.omittedSections.isEmpty() && m.omittedEventCount == 0L

    fun incompleteFlag(m: Metadata): Boolean = m.exporterMode == Mode.FALLBACK
}

/* ============================ S23 JOURNAL EPOCH ============================ */

object JournalEpoch6388 {
    data class Header(
        val epochId: String, val previousEpochId: String?, val previousEpochChecksum: String?,
        val startingWalletSol: Double, val startingWalletTokenMints: List<String>,
        val startingCanonicalOpenPositions: Int, val startingRestoredPositions: Int,
        val createdAtMs: Long, val runtimeGeneration: Long, val buildNumber: Int,
        val evidenceEpoch: Int, val recoveryEligible: Boolean,
    )
    enum class LegacyStatus { LEGACY_MIXED, POST_6388_CLEAN }

    /** Directive S23: archive-not-delete. Return the new header linked to the old checksum. */
    fun archiveAndStartNewEpoch(
        oldEpochId: String?, oldEpochChecksum: String?,
        startingWalletSol: Double, startingWalletTokenMints: List<String>,
        startingCanonicalOpenPositions: Int, startingRestoredPositions: Int,
        runtimeGeneration: Long, buildNumber: Int,
    ): Header = Header(
        epochId = "POST_6388_CLEAN_${System.currentTimeMillis()}",
        previousEpochId = oldEpochId, previousEpochChecksum = oldEpochChecksum,
        startingWalletSol = startingWalletSol, startingWalletTokenMints = startingWalletTokenMints,
        startingCanonicalOpenPositions = startingCanonicalOpenPositions,
        startingRestoredPositions = startingRestoredPositions,
        createdAtMs = System.currentTimeMillis(), runtimeGeneration = runtimeGeneration,
        buildNumber = buildNumber, evidenceEpoch = EvidenceEpochFilter6388.EPOCH,
        recoveryEligible = true,
    )

    fun legacyStatusFor(rowEvidenceEpoch: Int): LegacyStatus =
        if (rowEvidenceEpoch >= EvidenceEpochFilter6388.EPOCH) LegacyStatus.POST_6388_CLEAN
        else LegacyStatus.LEGACY_MIXED
}

/* ============================ S24 RECOVERY HEALTH SNAPSHOT ================= */

object RecoveryHealthSnapshot6388 {
    data class GovernorBlock(
        val confidenceState: String, val recoveryState: String, val evidenceEpoch: Int,
        val stateEnteredAt: Long, val postEpochCanonicalN: Int, val tradesCompletedInState: Int,
        val rollingWinRate: Double, val rollingProfitFactor: Double,
        val rollingExpectancySol: Double, val rollingDrawdownSol: Double,
        val nextPromotionRequirements: String, val lastPromotionReason: String,
        val lastDemotionReason: String,
    )
    data class ProbationBlock(
        val openPositionCount: Int, val entriesLastHour: Int,
        val lastEntryAt: Long, val nextEligibleEntryAt: Long,
        val sizeMinSol: Double, val sizeMaxSol: Double, val currentSizeSol: Double,
    )
    data class JournalBlock(
        val journalEpoch: String, val buyFillCount: Int, val sellFillCount: Int,
        val completedLifecycleCount: Int, val unresolvedBuyFillCount: Int,
        val unresolvedSellFillCount: Int, val duplicateFillCount: Int,
        val duplicateLifecycleCount: Int, val accountingQuarantineCount: Int,
        val missingSignatureCount: Int, val checksumStatus: String,
    )
    data class Snapshot(
        val entryAuthority: GovernorRecovery6388.State,
        val governor: GovernorBlock, val probation: ProbationBlock,
        val reconciler: ReconcilerLivenessAuthority6388.Snapshot,
        val positionAuthority: WalletPositionAuthority6388.Distribution,
        val journal: JournalBlock, val exportMetadata: ForensicExportMode6388.Metadata?,
    )

    private val lastSnapshot = AtomicReference<Snapshot?>(null)
    fun publish(s: Snapshot) { lastSnapshot.set(s) }
    fun current(): Snapshot? = lastSnapshot.get()
}

/* ============================ PROBATION ENTRY RATE-LIMIT (S5) ============== */

object ProbationEntryLimiter6388 {
    const val MAX_OPEN: Int = 1
    const val MAX_PER_HOUR: Int = 3
    const val MIN_SPACING_MS: Long = 180_000L

    private val entryTimestampsMs = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    private val openCount = AtomicLong(0)
    @Volatile private var lastEntryMs: Long = 0L

    @Synchronized
    fun canOpen(nowMs: Long = System.currentTimeMillis()): Pair<Boolean, String> {
        if (openCount.get() >= MAX_OPEN) return false to "PROBATION_MAX_OPEN_REACHED"
        if (nowMs - lastEntryMs < MIN_SPACING_MS && lastEntryMs > 0L)
            return false to "PROBATION_MIN_SPACING_${(nowMs - lastEntryMs)}ms"
        // Trim entries older than one hour.
        val cutoff = nowMs - 3_600_000L
        while (entryTimestampsMs.isNotEmpty() && (entryTimestampsMs.peekFirst() ?: 0L) < cutoff) {
            entryTimestampsMs.pollFirst()
        }
        if (entryTimestampsMs.size >= MAX_PER_HOUR) return false to "PROBATION_HOURLY_LIMIT_${entryTimestampsMs.size}"
        return true to "OK"
    }

    @Synchronized
    fun recordOpen(nowMs: Long = System.currentTimeMillis()) {
        entryTimestampsMs.addLast(nowMs); lastEntryMs = nowMs; openCount.incrementAndGet()
    }
    @Synchronized fun recordClose() { openCount.updateAndGet { (it - 1).coerceAtLeast(0L) } }
    fun openPositionCount(): Int = openCount.get().toInt()
    fun entriesLastHour(nowMs: Long = System.currentTimeMillis()): Int {
        val cutoff = nowMs - 3_600_000L
        return entryTimestampsMs.count { it >= cutoff }
    }
    fun nextEligibleEntryAt(nowMs: Long = System.currentTimeMillis()): Long =
        if (lastEntryMs == 0L) nowMs else lastEntryMs + MIN_SPACING_MS

    internal fun clearAllForTest() { entryTimestampsMs.clear(); openCount.set(0); lastEntryMs = 0L }
}

/* ============================ ROLLING EVIDENCE PROVIDER (S6/S8) ============ */

/**
 * V5.0.6388 — canonical evidence provider for the state machine. Owns the
 * counters that drive automatic promotion/demotion. Aggregates only rows in
 * evidence epoch ≥ 6388 with complete signatures/quantity/decimal integrity.
 */
object PostFixEvidenceCollector6388 {
    private data class Entry(val evidenceEpoch: Int, val pnlSol: Double, val closedAtMs: Long,
                             val signaturesComplete: Boolean, val quantityIntegrity: Boolean,
                             val decimalIntegrity: Boolean, val quarantined: Boolean)
    private val entries = java.util.concurrent.ConcurrentLinkedDeque<Entry>()
    private val consecutiveLosses = AtomicLong(0)

    @Synchronized
    fun recordCanonicalClose(evidenceEpoch: Int, pnlSol: Double,
                             signaturesComplete: Boolean, quantityIntegrity: Boolean,
                             decimalIntegrity: Boolean, quarantined: Boolean) {
        entries.addLast(Entry(evidenceEpoch, pnlSol, System.currentTimeMillis(),
            signaturesComplete, quantityIntegrity, decimalIntegrity, quarantined))
        // Cap to a bounded ring — last 200 rows are enough for rolling-20.
        while (entries.size > 200) entries.pollFirst()
        if (pnlSol < 0.0) consecutiveLosses.incrementAndGet()
        else consecutiveLosses.set(0)
    }

    private fun clean(e: Entry): Boolean =
        e.evidenceEpoch >= EvidenceEpochFilter6388.EPOCH &&
        e.signaturesComplete && e.quantityIntegrity && e.decimalIntegrity && !e.quarantined

    private fun windowStats(n: Int): Triple<Int, Int, Double> {
        val clean = entries.toList().reversed().filter { clean(it) }.take(n)
        val wins = clean.count { it.pnlSol > 0.0 }
        val losses = clean.count { it.pnlSol <= 0.0 }
        val gross = clean.filter { it.pnlSol > 0.0 }.sumOf { it.pnlSol }
        val loss = -clean.filter { it.pnlSol < 0.0 }.sumOf { it.pnlSol }
        val pf = if (loss > 0.0) gross / loss else if (gross > 0.0) Double.POSITIVE_INFINITY else 0.0
        return Triple(wins, losses, pf)
    }
    private fun expectancy(n: Int): Double {
        val clean = entries.toList().reversed().filter { clean(it) }.take(n)
        if (clean.isEmpty()) return 0.0
        return clean.sumOf { it.pnlSol } / clean.size
    }

    fun snapshot(tradesCompletedInState: Int, reconcilerHealthyThroughout: Boolean): GovernorRecovery6388.RollingEvidence {
        val (w5, l5, pf5) = windowStats(5)
        val (w10, l10, pf10) = windowStats(10)
        val (_, _, pf20) = windowStats(20)
        return GovernorRecovery6388.RollingEvidence(
            postEpochCanonicalN = entries.toList().count { clean(it) },
            rollingLast5Wins = w5, rollingLast5Losses = l5,
            rollingLast10Wins = w10, rollingLast10Losses = l10,
            rollingLast5ProfitFactor = pf5, rollingLast10ProfitFactor = pf10, rollingLast20ProfitFactor = pf20,
            rollingLast5ExpectancySol = expectancy(5), rollingLast10ExpectancySol = expectancy(10),
            rollingLast20ExpectancySol = expectancy(20),
            consecutiveLosses = consecutiveLosses.get().toInt(),
            tradesCompletedInState = tradesCompletedInState,
            reconcilerHealthyThroughout = reconcilerHealthyThroughout,
            allSignaturesComplete = entries.toList().all { it.signaturesComplete },
            noDecimalSkew = entries.toList().all { it.decimalIntegrity },
            noQtyIntegrityFault = entries.toList().all { it.quantityIntegrity },
            noAccountingQuarantineOfConfirmedFill = entries.toList().none { it.quarantined },
        )
    }
    internal fun clearAllForTest() { entries.clear(); consecutiveLosses.set(0) }
}

/* ============================ S26 ACCEPTANCE CHECKLIST ===================== */

object AcceptanceScript6388 {
    /** Directive S26/S27: automated integration acceptance criteria. */
    data class Checklist(
        val liveSellReconcilerStartsAndSupervised: Boolean,
        val walletReconcilesToAuthority: Boolean,
        val governorHoldNoLongerDeadlocks: Boolean,
        val controlledProbationCanExecute: Boolean,
        val probationSizeAndFrequencyLimited: Boolean,
        val hardSafetyEnforced: Boolean,
        val policyBlocksNotBuyFailures: Boolean,
        val everyBuyCreatesImmutableFill: Boolean,
        val everySellCreatesImmutableFill: Boolean,
        val partialsAggregateOneLifecycle: Boolean,
        val hardStopsDefaultFullExit: Boolean,
        val geometric25CascadePrevented: Boolean,
        val onChainFillsCannotBeDiscarded: Boolean,
        val historicalMixedRemainsAudit: Boolean,
        val postFixEvidenceEpochIsolated: Boolean,
        val autoPromoteProbationToSoft: Boolean,
        val autoPromoteSoftToBaseline: Boolean,
        val baselineRestoresConfigured: Boolean,
        val noManualResetRequired: Boolean,
        val demoteAndReRecover: Boolean,
        val integrationTestProven: Boolean,
    ) {
        fun allPass(): Boolean = liveSellReconcilerStartsAndSupervised &&
            walletReconcilesToAuthority && governorHoldNoLongerDeadlocks &&
            controlledProbationCanExecute && probationSizeAndFrequencyLimited &&
            hardSafetyEnforced && policyBlocksNotBuyFailures &&
            everyBuyCreatesImmutableFill && everySellCreatesImmutableFill &&
            partialsAggregateOneLifecycle && hardStopsDefaultFullExit &&
            geometric25CascadePrevented && onChainFillsCannotBeDiscarded &&
            historicalMixedRemainsAudit && postFixEvidenceEpochIsolated &&
            autoPromoteProbationToSoft && autoPromoteSoftToBaseline &&
            baselineRestoresConfigured && noManualResetRequired &&
            demoteAndReRecover && integrationTestProven
    }
}
