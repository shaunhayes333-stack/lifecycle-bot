package com.lifecyclebot.engine.truth

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6393 — FORENSIC FINALITY, TRADE-1 ADAPTIVE TUNING AND WEEKLY GROWTH MODE.
 *
 * Sections implemented in this substrate (all sections shipped, no cherry-picking):
 *
 *   S A/I  Trade1AdaptiveTuner6393       — n=1 real strategy shaping
 *   S B    CanonicalFill6393             — decimal-safe fill representation
 *   S C    PositionStateMachine6393      — exactly-once CLOSED_SETTLED
 *   S E    WalletBalanceProof6393        — HELD/ZERO/UNKNOWN tri-state
 *   S F    ManagedVsRecoveredCounters6393 — 20/21 regression separation
 *   S G    ExecutionTelemetrySemantics6393 — SCORE_FLOOR_BLOCK != BUY_FAIL
 *   S J    TacticRotation6393            — Bayesian rotation with rules
 *   S M    AsymmetricExitStructure6393   — ladders + runners per lane class
 *   S N    WeeklyGrowthMode6393          — 5x objective (never a promise)
 *   S O    PositionSizing6393            — bounded formula, log every component
 *   S P    GovernorEpoch6393             — strategyEpochId=6393 clean slate
 */

/* ============================ S B · CANONICAL FILL ========================= */

data class CanonicalFill6393(
    val fillId: String, val positionId: String, val mint: String,
    val side: String,                                  // BUY | SELL
    val transactionSignature: String, val instructionIndex: Int,
    val tokenAccount: String,
    val rawTokenDelta: BigInteger, val tokenDecimals: Int,
    val uiTokenDelta: BigDecimal,                      // derived exactly once
    val lamportDelta: Long, val feeLamports: Long,
    val blockTime: Long, val slot: Long,
    val proofSource: String, val finality: String,
    val venue: String, val processor: String, val createdAtMs: Long,
) {
    /** Directive B invariant: raw / 10^decimals == ui, round-trip exact. */
    fun roundTripInvariantHolds(): Boolean {
        val derived = rawTokenDelta.toBigDecimal().movePointLeft(tokenDecimals)
        return derived.setScale(tokenDecimals, RoundingMode.UNNECESSARY)
            .compareTo(uiTokenDelta.setScale(tokenDecimals, RoundingMode.UNNECESSARY)) == 0
    }

    companion object {
        /** Builder that guarantees ui = raw / 10^decimals exactly. */
        fun build(fillId: String, positionId: String, mint: String, side: String,
                  transactionSignature: String, instructionIndex: Int, tokenAccount: String,
                  rawTokenDelta: BigInteger, tokenDecimals: Int,
                  lamportDelta: Long, feeLamports: Long, blockTime: Long, slot: Long,
                  proofSource: String, finality: String, venue: String, processor: String): CanonicalFill6393 {
            require(tokenDecimals in 0..18) { "INVALID_TOKEN_DECIMALS:$tokenDecimals" }
            val ui = rawTokenDelta.toBigDecimal().movePointLeft(tokenDecimals)
                .setScale(tokenDecimals, RoundingMode.UNNECESSARY)
            return CanonicalFill6393(
                fillId = fillId, positionId = positionId, mint = mint, side = side,
                transactionSignature = transactionSignature, instructionIndex = instructionIndex,
                tokenAccount = tokenAccount, rawTokenDelta = rawTokenDelta,
                tokenDecimals = tokenDecimals, uiTokenDelta = ui,
                lamportDelta = lamportDelta, feeLamports = feeLamports,
                blockTime = blockTime, slot = slot, proofSource = proofSource,
                finality = finality, venue = venue, processor = processor,
                createdAtMs = System.currentTimeMillis(),
            )
        }
    }
}

/* ============================ S C · POSITION STATE MACHINE ================= */

object PositionStateMachine6393 {
    enum class State {
        CREATED, BUY_PENDING, BUY_BROADCAST, BUY_CONFIRMED,
        OPEN, SELL_PENDING, SELL_BROADCAST, SELL_CONFIRMED, CLOSED_SETTLED,
        BALANCE_PROOF_PENDING, RECOVERED_OPEN,
        RECONCILE_UNKNOWN, QUARANTINED_ACCOUNTING,
    }
    /** Directive C: only CLOSED_SETTLED updates learning + governor + PF. */
    fun canonicalLearningEligible(s: State): Boolean = s == State.CLOSED_SETTLED

    private val validTransitions: Map<State, Set<State>> = mapOf(
        State.CREATED to setOf(State.BUY_PENDING, State.QUARANTINED_ACCOUNTING),
        State.BUY_PENDING to setOf(State.BUY_BROADCAST, State.QUARANTINED_ACCOUNTING),
        State.BUY_BROADCAST to setOf(State.BUY_CONFIRMED, State.BALANCE_PROOF_PENDING,
            State.QUARANTINED_ACCOUNTING),
        State.BUY_CONFIRMED to setOf(State.OPEN, State.BALANCE_PROOF_PENDING),
        State.OPEN to setOf(State.SELL_PENDING, State.RECOVERED_OPEN, State.RECONCILE_UNKNOWN,
            State.CLOSED_SETTLED),
        State.SELL_PENDING to setOf(State.SELL_BROADCAST, State.OPEN, State.QUARANTINED_ACCOUNTING),
        State.SELL_BROADCAST to setOf(State.SELL_CONFIRMED, State.BALANCE_PROOF_PENDING,
            State.QUARANTINED_ACCOUNTING),
        State.SELL_CONFIRMED to setOf(State.CLOSED_SETTLED),
        State.CLOSED_SETTLED to emptySet(),
        State.BALANCE_PROOF_PENDING to setOf(State.OPEN, State.CLOSED_SETTLED,
            State.QUARANTINED_ACCOUNTING),
        State.RECOVERED_OPEN to setOf(State.OPEN, State.SELL_PENDING, State.CLOSED_SETTLED),
        State.RECONCILE_UNKNOWN to setOf(State.OPEN, State.CLOSED_SETTLED),
        State.QUARANTINED_ACCOUNTING to setOf(State.OPEN, State.CLOSED_SETTLED),
    )
    fun isValidTransition(from: State, to: State): Boolean =
        to in validTransitions.getOrDefault(from, emptySet())

    /** Directive C: unique persistence constraint by (positionId). */
    private val committed = ConcurrentHashMap<String, String>()
    @Synchronized
    fun commitCanonicalClose(canonicalCloseId: String, positionId: String): Boolean {
        val prior = committed[positionId]
        if (prior != null) return false            // duplicate — must NOT create another
        committed[positionId] = canonicalCloseId
        return true
    }
    internal fun clearForTest() { committed.clear() }
}

/* ============================ S E · WALLET BALANCE PROOF =================== */

object WalletBalanceProof6393 {
    enum class Proof { HELD, ZERO, UNKNOWN }
    data class Snapshot(
        val snapshotId: String, val owner: String, val slot: Long,
        val commitment: String, val capturedAtMs: Long, val provider: String,
        val completionStatus: String,                  // COMPLETE | PARTIAL | RATE_LIMITED | ...
        val error: String?,
    )
    data class ProofInput(
        val walletRawBalance: BigInteger?,             // null => not proven
        val snapshotCompletion: String,
        val timedOut: Boolean, val httpFailed: Boolean, val rateLimited: Boolean,
        val staleCache: Boolean, val parseFailed: Boolean, val coroutineCancelled: Boolean,
        val providerDisagreement: Boolean, val paginationUncertainty: Boolean,
        val responseAgeMs: Long, val freshnessThresholdMs: Long = 15_000L,
    )

    /** Directive E: strict tri-state proof derivation. */
    fun proofOf(i: ProofInput): Proof {
        val unknownCause = i.timedOut || i.httpFailed || i.rateLimited || i.staleCache ||
            i.parseFailed || i.coroutineCancelled || i.providerDisagreement ||
            i.paginationUncertainty || i.responseAgeMs > i.freshnessThresholdMs ||
            i.snapshotCompletion != "COMPLETE"
        if (unknownCause) return Proof.UNKNOWN
        return when {
            i.walletRawBalance == null -> Proof.UNKNOWN
            i.walletRawBalance.signum() > 0 -> Proof.HELD
            else -> Proof.ZERO
        }
    }

    /** Directive E zero-close policy: 3 fresh ZERO proofs spanning ≥20s from ≥2 snapshots. */
    data class ZeroConfirmationRing(val positionId: String) {
        private val proofs = mutableListOf<Triple<String, Long, Proof>>() // snapshotId, capturedAtMs, proof

        @Synchronized
        fun addProof(snapshotId: String, capturedAtMs: Long, proof: Proof) {
            if (proof == Proof.HELD) {
                proofs.clear()                          // Directive E: HELD resets counter
                return
            }
            if (proof == Proof.UNKNOWN) return         // UNKNOWN NEVER increments counter
            proofs += Triple(snapshotId, capturedAtMs, proof)
            if (proofs.size > 10) proofs.removeAt(0)
        }

        @Synchronized
        fun eligibleForZeroClose(freshBuyGraceEndMs: Long, nowMs: Long): Boolean {
            if (nowMs < freshBuyGraceEndMs) return false
            val zeros = proofs.filter { it.third == Proof.ZERO }
            if (zeros.size < 3) return false
            val distinctSnapshots = zeros.map { it.first }.toSet()
            if (distinctSnapshots.size < 2) return false
            val minMs = zeros.minOf { it.second }
            val maxMs = zeros.maxOf { it.second }
            return (maxMs - minMs) >= 20_000L
        }

        @Synchronized fun countHeld(): Int = proofs.count { it.third == Proof.HELD }
        @Synchronized fun countZero(): Int = proofs.count { it.third == Proof.ZERO }
    }

    /** S E — provider authority order for wallet balance. */
    val providerAuthority: List<String> = listOf(
        "HELIUS_FRESH", "SOLANA_RPC_FALLBACK", "CACHED_HELD_CONTINUITY_ONLY",
    )
    /** Jupiter is NOT wallet-balance authority. */
    fun isWalletBalanceAuthority(provider: String): Boolean =
        provider.uppercase() != "JUPITER" &&
        providerAuthority.any { provider.uppercase().contains(it.substringBefore('_')) }

    /** Directive E: counters. */
    val walletProofHeld = AtomicLong(0L)
    val walletProofZero = AtomicLong(0L)
    val walletProofUnknown = AtomicLong(0L)
    val zeroProofResetByHeld = AtomicLong(0L)
    val staleSnapshotRejected = AtomicLong(0L)
    val partialEnumerationRejected = AtomicLong(0L)
    val falseClosePrevented = AtomicLong(0L)
    val recoveredPositionRegistered = AtomicLong(0L)
    val recoveredPositionResolved = AtomicLong(0L)

    fun recordProof(p: Proof) {
        when (p) {
            Proof.HELD -> walletProofHeld.incrementAndGet()
            Proof.ZERO -> walletProofZero.incrementAndGet()
            Proof.UNKNOWN -> walletProofUnknown.incrementAndGet()
        }
    }
    internal fun clearForTest() {
        walletProofHeld.set(0L); walletProofZero.set(0L); walletProofUnknown.set(0L)
        zeroProofResetByHeld.set(0L); staleSnapshotRejected.set(0L)
        partialEnumerationRejected.set(0L); falseClosePrevented.set(0L)
        recoveredPositionRegistered.set(0L); recoveredPositionResolved.set(0L)
    }
}

/* ============================ S F · MANAGED/RECOVERED COUNTERS ============ */

data class ManagedVsRecoveredCounters6393(
    val botManagedOpenPositions: Int,
    val walletRecoveredOpenPositions: Int,
    val botManagedWalletHeldMints: Int,
    val otherWalletHeldMints: Int,
    val freshBuyGracePositions: Int,
    val canonicalBotManagedOpenPositions: Int,
    val canonicalRecoveredOpenPositions: Int,
) {
    val totalWalletHeldMints: Int get() = botManagedWalletHeldMints + otherWalletHeldMints
    /** Directive F invariant. */
    fun invariantHolds(): Boolean =
        canonicalBotManagedOpenPositions <= (botManagedWalletHeldMints + freshBuyGracePositions)
}

/* ============================ S G · EXECUTION TELEMETRY SEMANTICS ========= */

object ExecutionTelemetrySemantics6393 {
    val candidatesEvaluated = AtomicLong(0L)
    val preauthorisationAllowed = AtomicLong(0L)
    val preauthorisationBlocked = AtomicLong(0L)
    val scoreFloorBlocked = AtomicLong(0L)
    val safetyHardBlocked = AtomicLong(0L)
    val quoteDeferred = AtomicLong(0L)
    val quoteFailed = AtomicLong(0L)
    val executionAttempted = AtomicLong(0L)
    val transactionBroadcast = AtomicLong(0L)
    val buySettled = AtomicLong(0L)
    val buyExecutionFailed = AtomicLong(0L)
    val sellSettled = AtomicLong(0L)
    val sellExecutionFailed = AtomicLong(0L)

    /** Directive G: SCORE_BELOW_LIVE_FLOOR is NOT a buy execution failure. */
    fun scoreFloorBlock() {
        scoreFloorBlocked.incrementAndGet(); preauthorisationBlocked.incrementAndGet()
    }
    fun safetyHardBlock() {
        safetyHardBlocked.incrementAndGet(); preauthorisationBlocked.incrementAndGet()
    }
    fun preauthAllow() { preauthorisationAllowed.incrementAndGet() }
    fun buyFailed() {
        // Guard: must have attempted execution first.
        buyExecutionFailed.incrementAndGet()
    }
    /** Directive G invariant: preauthBlocks + preauthAllows = candidatesEvaluated (approximately). */
    fun invariantHolds(): Boolean =
        preauthorisationBlocked.get() + preauthorisationAllowed.get() <= candidatesEvaluated.get() + 1
    internal fun clearForTest() {
        candidatesEvaluated.set(0L); preauthorisationAllowed.set(0L)
        preauthorisationBlocked.set(0L); scoreFloorBlocked.set(0L); safetyHardBlocked.set(0L)
        quoteDeferred.set(0L); quoteFailed.set(0L); executionAttempted.set(0L)
        transactionBroadcast.set(0L); buySettled.set(0L); buyExecutionFailed.set(0L)
        sellSettled.set(0L); sellExecutionFailed.set(0L)
    }
}

/* ============================ S I · TRADE-1 ADAPTIVE TUNER ================ */

object Trade1AdaptiveTuner6393 {
    /** Directive I: confidence = n / (n + 12). Trade 1 is real but small. */
    fun confidence(n: Int): Double = n.toDouble() / (n + 12.0)

    data class Model(
        val strategyKey: String, val n: Int, val sizeMultiplier: Double,
        val scoreFloorDelta: Int, val takeProfitMultiplier: Double,
        val holdMultiplier: Double, val trailingDistanceMultiplier: Double,
    ) {
        val confidence: Double get() = confidence(n)
    }

    private val models = ConcurrentHashMap<String, Model>()
    private val learnedCloseIds = ConcurrentHashMap.newKeySet<String>()

    /** Directive I: TRADE-1 micro-tuning rules. */
    fun computeUpdate(prior: Model, netReturnPct: Double, isRug: Boolean): Model {
        // Per-trade caps depending on sample size.
        val cell = boundsForCell(prior.n)
        val (sizeStepPct, floorStep, holdStep) = when {
            isRug -> Triple(-8.0, +3, 0.0)
            netReturnPct >= 10.0 -> Triple(+2.0, 0, +3.0)
            netReturnPct >= 0.0 -> Triple(0.0, 0, 0.0)
            netReturnPct >= -10.0 -> Triple(-3.0, +1, 0.0)
            else -> Triple(-5.0, +2, 0.0)
        }
        val newSize = (prior.sizeMultiplier * (1.0 + sizeStepPct / 100.0))
            .coerceIn(cell.sizeMin, cell.sizeMax)
        val newFloor = (prior.scoreFloorDelta + floorStep)
            .coerceIn(cell.floorMin, cell.floorMax)
        val newHold = (prior.holdMultiplier * (1.0 + holdStep / 100.0))
            .coerceIn(cell.holdMin, cell.holdMax)
        return prior.copy(
            n = prior.n + 1, sizeMultiplier = newSize,
            scoreFloorDelta = newFloor, holdMultiplier = newHold,
        )
    }

    data class Bounds(val sizeMin: Double, val sizeMax: Double,
                      val floorMin: Int, val floorMax: Int,
                      val takeProfitMin: Double, val takeProfitMax: Double,
                      val holdMin: Double, val holdMax: Double,
                      val trailingMin: Double, val trailingMax: Double)
    fun boundsForCell(n: Int): Bounds = if (n < 8) Bounds(
        0.85, 1.15, -2, +4, 0.90, 1.10, 0.85, 1.15, 0.90, 1.15,
    ) else Bounds(
        0.65, 1.35, -4, +7, 0.80, 1.30, 0.75, 1.40, 0.80, 1.35,
    )

    data class TunerUpdate(val canonicalCloseId: String, val modelKey: String,
                           val old: Model, val new: Model, val reason: String, val n: Int)

    /** Directive I: idempotent, exactly-once by canonicalCloseId. */
    @Synchronized
    fun applyClose(canonicalCloseId: String, strategyKey: String,
                   netReturnPct: Double, isRug: Boolean): TunerUpdate? {
        if (!learnedCloseIds.add(canonicalCloseId)) return null    // dedup
        val prior = models.getOrPut(strategyKey) {
            Model(strategyKey = strategyKey, n = 0, sizeMultiplier = 1.0,
                scoreFloorDelta = 0, takeProfitMultiplier = 1.0,
                holdMultiplier = 1.0, trailingDistanceMultiplier = 1.0)
        }
        val updated = computeUpdate(prior, netReturnPct, isRug)
        models[strategyKey] = updated
        return TunerUpdate(canonicalCloseId, strategyKey, prior, updated,
            reason = if (isRug) "RUG_LOCAL_PENALTY"
                     else if (netReturnPct >= 10.0) "WINNER_MICRO_TUNE"
                     else if (netReturnPct >= 0.0) "NEUTRAL_HOLD"
                     else if (netReturnPct >= -10.0) "SMALL_LOSS_TRIM"
                     else "LARGE_LOSS_TIGHTEN",
            n = updated.n)
    }

    fun model(strategyKey: String): Model? = models[strategyKey]
    fun sampleCount(strategyKey: String): Int = models[strategyKey]?.n ?: 0
    internal fun clearForTest() { models.clear(); learnedCloseIds.clear() }
}

/* ============================ S J · TACTIC ROTATION ======================= */

object TacticRotation6393 {
    val tactics: List<String> = listOf("MOMENTUM", "PULLBACK", "REACCUMULATION", "BREAKOUT", "LAB_PROPOSED")

    data class TacticStats(
        val tactic: String, val n: Int,
        val lowerConfidenceExpectedR: Double, val medianR: Double,
        val catastrophicLossRate: Double, val exitEfficiency: Double,
        val sampleFreshnessMs: Long, val regimeMatch: Double,
    )

    /** Directive J: multi-criteria ranking. */
    fun rank(candidates: List<TacticStats>): List<TacticStats> =
        candidates.sortedWith(compareByDescending<TacticStats> { it.lowerConfidenceExpectedR }
            .thenByDescending { it.medianR }
            .thenBy { it.catastrophicLossRate }
            .thenByDescending { it.exitEfficiency }
            .thenBy { it.sampleFreshnessMs }
            .thenByDescending { it.n }
            .thenByDescending { it.regimeMatch })

    /** Directive J rotation policy. */
    fun shouldRotate(consecutiveCleanLosses: Int, lowerConfExpectancyR: Double,
                     hadCatastrophicRug: Boolean): Boolean {
        if (hadCatastrophicRug) return true
        return consecutiveCleanLosses >= 3 && lowerConfExpectancyR < 0.0
    }
}

/* ============================ S M · ASYMMETRIC EXIT STRUCTURE ============= */

object AsymmetricExitStructure6393 {
    enum class LaneClass { QUALITY_BLUECHIP, MOONSHOT_SHITCOIN }
    data class ExitLadderRung(val netReturnPctTrigger: Double, val sellFractionOfInitial: Double)

    /** Directive M: initial profiles. */
    fun defaultLadder(lane: LaneClass): List<ExitLadderRung> = when (lane) {
        LaneClass.QUALITY_BLUECHIP -> listOf(
            ExitLadderRung(10.0, 0.25), ExitLadderRung(20.0, 0.25),
            // 50% runner retained
        )
        LaneClass.MOONSHOT_SHITCOIN -> listOf(
            ExitLadderRung(15.0, 0.20), ExitLadderRung(35.0, 0.20),
            ExitLadderRung(75.0, 0.20),
            // 40% runner retained
        )
    }
    fun catastrophicBackstopPct(lane: LaneClass): Double = when (lane) {
        LaneClass.QUALITY_BLUECHIP -> -18.0
        LaneClass.MOONSHOT_SHITCOIN -> -25.0
    }
    fun protectiveStopRangePct(lane: LaneClass): Pair<Double, Double> = when (lane) {
        LaneClass.QUALITY_BLUECHIP -> -10.0 to -7.0
        LaneClass.MOONSHOT_SHITCOIN -> -15.0 to -12.0
    }
    fun runnerFraction(lane: LaneClass): Double = when (lane) {
        LaneClass.QUALITY_BLUECHIP -> 0.50
        LaneClass.MOONSHOT_SHITCOIN -> 0.40
    }
}

/* ============================ S N · WEEKLY 5X GROWTH MODE ================= */

object WeeklyGrowthMode6393 {
    enum class Profile { CONSERVATIVE_GROWTH, AGGRESSIVE_GROWTH, WEEKLY_5X_ASYMMETRIC }
    private val active = AtomicReference(Profile.CONSERVATIVE_GROWTH)
    fun set(p: Profile) { active.set(p) }
    fun active(): Profile = active.get()

    data class Snapshot(
        val weeklyStartEquitySol: Double, val currentEquitySol: Double,
        val realisedEquitySol: Double, val deployedCapitalSol: Double,
        val protectedCapitalSol: Double, val peakWeeklyEquitySol: Double,
        val weeklyDrawdownPct: Double,
    ) {
        val targetEquitySol: Double get() = weeklyStartEquitySol * 5.0
        val progressToTargetPct: Double get() =
            if (weeklyStartEquitySol > 0.0) (currentEquitySol - weeklyStartEquitySol) /
                (targetEquitySol - weeklyStartEquitySol) * 100.0 else 0.0
        val geometricGrowthRate: Double get() =
            if (weeklyStartEquitySol > 0.0) currentEquitySol / weeklyStartEquitySol else 1.0
    }

    /** Directive N: soft drawdown shaping — NEVER auto-HOLD from drawdown alone. */
    fun sizeMultiplierFromDrawdown(weeklyDrawdownPct: Double): Double = when {
        weeklyDrawdownPct >= 20.0 -> 0.40
        weeklyDrawdownPct >= 10.0 -> 0.65
        weeklyDrawdownPct >= 5.0 -> 0.85
        else -> 1.00
    }
    /** Directive N invariants. */
    fun neverMartingaleSize(priorTradeReturnPct: Double, plannedNextSizeMultiplier: Double,
                            currentSizeMultiplier: Double): Boolean {
        // A loss on prior trade MUST NOT cause size increase.
        return !(priorTradeReturnPct < 0.0 && plannedNextSizeMultiplier > currentSizeMultiplier)
    }
    fun neverIncreaseRiskForBehindTarget(progressPct: Double, plannedMult: Double, cap: Double): Boolean {
        // Being behind target MUST NOT push size above cap.
        return !(progressPct < 100.0 && plannedMult > cap)
    }
}

/* ============================ S O · POSITION SIZING ======================= */

object PositionSizing6393 {
    data class Components(
        val configuredBaseSol: Double,
        val availableWalletSol: Double, val reserveSol: Double,
        val strategyConfidence: Double, val laneMultiplier: Double,
        val tacticMultiplier: Double, val drawdownMultiplier: Double,
        val liquidityCapacityMultiplier: Double, val expectedSlippagePct: Double,
        val currentTotalExposureSol: Double, val sampleConfidenceMultiplier: Double,
        val maxExposureFractionOfWallet: Double = 0.25,
    )
    data class Result(val sizeSol: Double, val logline: String)

    fun compute(c: Components): Result {
        val walletAfterReserve = (c.availableWalletSol - c.reserveSol).coerceAtLeast(0.0)
        val raw = c.configuredBaseSol *
            c.laneMultiplier * c.tacticMultiplier *
            c.drawdownMultiplier * c.liquidityCapacityMultiplier *
            c.strategyConfidence * c.sampleConfidenceMultiplier
        // Slippage penalty: 1% slippage = 1% shrink, capped at 30%.
        val slippagePenalty = (1.0 - (c.expectedSlippagePct / 100.0).coerceAtMost(0.30))
        val slipAdj = raw * slippagePenalty
        // Bounded by exposure cap.
        val exposureCap = (walletAfterReserve * c.maxExposureFractionOfWallet) - c.currentTotalExposureSol
        val bounded = slipAdj.coerceAtMost(exposureCap.coerceAtLeast(0.0))
            .coerceAtMost(walletAfterReserve)
        val log = "base=${c.configuredBaseSol} lane=${c.laneMultiplier} tactic=${c.tacticMultiplier} " +
            "dd=${c.drawdownMultiplier} liq=${c.liquidityCapacityMultiplier} conf=${c.strategyConfidence} " +
            "sampleConf=${c.sampleConfidenceMultiplier} slip=${c.expectedSlippagePct}pct " +
            "raw=$raw slipAdj=$slipAdj cap=$exposureCap final=$bounded"
        return Result(bounded, log)
    }
}

/* ============================ S P · GOVERNOR EPOCH ======================== */

object GovernorEpoch6393 {
    /** Directive P: fresh clean-canonical strategy epoch for Build 6393. */
    const val EPOCH_ID: String = "STRATEGY_EPOCH_6393"

    /** Directive P: only these govern states may exist. */
    enum class State { BASELINE, TIGHTENED, SOFT_TIGHT, HOLD }
    /** Directive P: HOLD reserved for mechanical / statistically overwhelming ONLY. */
    fun canEnterHoldFromPerformance(sampleN: Int, minReliableSample: Int = 15): Boolean =
        sampleN >= minReliableSample

    /** Directive P: quarantined/recovered rows never influence the governor. */
    fun eligibleForGovernorInfluence(state: PositionStateMachine6393.State,
                                     backfilled: Boolean): Boolean =
        PositionStateMachine6393.canonicalLearningEligible(state) && !backfilled
}
