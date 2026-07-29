package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6387 — CANONICAL LEDGER (Directive A P0 - Sections: canonical DB records,
 * atomic BUY/SELL finalisation, quantity conservation, unique position identity).
 *
 * This is the SOLE authority for position lifecycle, cost basis, realised P&L
 * and learning eligibility. UI, journal, brains and forensic export must
 * project FROM this ledger — never mutate independently.
 *
 * In-memory implementation with atomic transactions. Room persistence layer
 * is the follow-on wiring bundle; the API contract is stable here.
 */
object CanonicalLedger6387 {

    /** Canonical epoch — incremented by explicit journal-migration action. */
    private val canonicalEpoch = AtomicLong(1L)
    fun currentEpoch(): Long = canonicalEpoch.get()
    fun rollEpoch(): Long = canonicalEpoch.incrementAndGet()

    /** Runtime generation — incremented per process launch. */
    private val runtimeGeneration = AtomicLong(System.currentTimeMillis())
    fun currentGeneration(): Long = runtimeGeneration.get()

    // Section 2/3: positions keyed by positionId, unique constraint on
    // (wallet, mint, canonicalEpoch) among status ∈ {PENDING_BUY_PROOF, OPEN, PARTIALLY_CLOSED}.
    private val positions = ConcurrentHashMap<String, AtomicReference<CanonicalPosition6387>>()
    // Fills keyed by CanonicalFill.uniqueKey() — enforces (sig + insn + side + mint).
    private val fillIndex = ConcurrentHashMap<String, CanonicalFill6387>()
    // Wallet asset classifications keyed by (wallet, mint, tokenAccount).
    private val classifications = ConcurrentHashMap<String, WalletAssetClassification6387>()

    /* ─── BUY lifecycle: BUY_INTENT_CREATED → BUY_BROADCAST → BUY_CONFIRMED →
       BUY_TRANSACTION_PARSED → BUY_WALLET_DELTA_PROVEN → BUY_CANONICAL_COMMITTED →
       POSITION_OPEN_PUBLISHED ─── */

    data class BuyCommitInput(
        val intent: ExecutionIntent6386,
        val proof: ProofState6386.FinalizedProofComplete,
        val tokenAccount: String,
        val slot: Long,
        val blockTime: Long,
        val commitment: String,
        val symbol: String,
        val lane: String,
        val tactic: String,
        val networkFeeLamports: Lamports,
        val priorityFeeLamports: Lamports,
        val tipLamports: Lamports,
        val provider: String,
        val instructionIndex: Int,
    )

    sealed class CommitResult {
        data class Committed(val position: CanonicalPosition6387, val fill: CanonicalFill6387) : CommitResult()
        data class Rejected(val reason: String) : CommitResult()
    }

    /**
     * ATOMIC BUY COMMIT — one lock, all four steps: insert fill, create/update
     * position, replace provisional qty, establish cost basis. Publishing to
     * downstream projections happens ONLY after this returns Committed.
     */
    @Synchronized
    fun commitBuy(input: BuyCommitInput): CommitResult {
        // Validate proof matches intent.
        val validate = FinalizedBuyProof6386.validate(input.intent.walletAddress, input.intent.mintAddress, input.proof)
        if (!validate.proofComplete) return CommitResult.Rejected("BUY_PROOF_INCOMPLETE: ${validate.reason}")

        val fillId = "${input.proof.signature}|${input.instructionIndex}|BUY"
        val uniqueKey = "${input.proof.signature}|${input.instructionIndex}|BUY|${input.intent.mintAddress.take(8)}"

        // Idempotency: same (sig, insn, side, mint) reprocessed = no-op.
        fillIndex[uniqueKey]?.let { existingFill ->
            val existingPos = positions[existingFill.positionId]?.get()
            if (existingPos != null) return CommitResult.Committed(existingPos, existingFill)
        }

        // Unique constraint: one canonical active position per (wallet, mint, canonicalEpoch).
        val epoch = canonicalEpoch.get()
        val prior = findActive(input.intent.walletAddress, input.intent.mintAddress, epoch)
        if (prior != null) {
            // Directive: "Reprocessing the same wallet snapshot or transaction must be idempotent."
            return CommitResult.Rejected("ACTIVE_POSITION_EXISTS_SAME_WALLET_MINT_EPOCH pid=${prior.id.positionId.take(8)}")
        }

        val positionId = CanonicalPositionId6387.new(
            walletPublicKey = input.intent.walletAddress,
            mintAddress = input.intent.mintAddress,
            tokenAccount = input.tokenAccount,
            openingSignature = input.proof.signature,
            openingInstructionIndex = input.instructionIndex,
            runtimeGeneration = runtimeGeneration.get(),
            canonicalEpoch = epoch,
        )

        val fill = CanonicalFill6387(
            fillId = fillId,
            positionId = positionId.positionId,
            signature = input.proof.signature,
            instructionIndex = input.instructionIndex,
            side = FillSide6387.BUY,
            rawTokenDelta = validate.quantityDelta,
            tokenDecimals = input.proof.decimals,
            lamportDelta = -validate.netLamportsSpent.value.toLong(),   // negative: SOL out
            networkFeeLamports = input.networkFeeLamports,
            priorityFeeLamports = input.priorityFeeLamports,
            tipLamports = input.tipLamports,
            slot = input.slot,
            blockTime = input.blockTime,
            commitment = input.commitment,
            proofSource = "WALLET_DELTA_PROVEN",
            provider = input.provider,
            createdAt = System.currentTimeMillis(),
        )

        val position = CanonicalPosition6387(
            id = positionId,
            symbol = input.symbol,
            laneAtEntry = input.lane,
            tacticAtEntry = input.tactic,
            status = PositionStatus6387.OPEN,
            openedAt = System.currentTimeMillis(),
            closedAt = null,
            openingSignature = input.proof.signature,
            closingSignature = null,
            entryRawQty = validate.quantityDelta,
            remainingRawQty = validate.quantityDelta,
            tokenDecimals = input.proof.decimals,
            entryLamports = validate.netLamportsSpent,
            remainingCostBasisLamports = validate.netLamportsSpent,
            realisedLamports = Lamports.ZERO,
            realisedPnlLamports = 0L,
            basisState = BasisState6387.COMPLETE,
            walletAssetClass = WalletAssetClass6387.BOT_POSITION_ACTIVE,
            learningEligibility = LearningEligibility6387.PENDING_PROOF,
            stateVersion = 1L,
            lastWalletProofSlot = input.slot,
            lastTransactionProofSlot = input.slot,
        )

        positions[positionId.positionId] = AtomicReference(position)
        fillIndex[uniqueKey] = fill

        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_BUY_COMMITTED_6387")
        } catch (_: Throwable) {}
        return CommitResult.Committed(position, fill)
    }

    /* ─── SELL lifecycle: SELL_INTENT_CREATED → SELL_WALLET_BALANCE_PROVEN →
       SELL_BROADCAST → SELL_CONFIRMED → SELL_TRANSACTION_PARSED →
       SELL_TOKEN_DELTA_PROVEN → SELL_SOL_DELTA_PROVEN →
       SELL_CANONICAL_COMMITTED → JOURNAL_PROJECTION_UPDATED ─── */

    data class SellCommitInput(
        val positionId: String,
        val proof: ProofState6386.FinalizedProofComplete,
        val slot: Long,
        val blockTime: Long,
        val commitment: String,
        val networkFeeLamports: Lamports,
        val priorityFeeLamports: Lamports,
        val tipLamports: Lamports,
        val provider: String,
        val instructionIndex: Int,
        val ataClosedProven: Boolean,
    )

    @Synchronized
    fun commitSell(input: SellCommitInput): CommitResult {
        val posRef = positions[input.positionId]
            ?: return CommitResult.Rejected("POSITION_NOT_FOUND pid=${input.positionId.take(8)}")
        val pos = posRef.get()

        val validate = FinalizedSellProof6386.validate(pos.id.walletPublicKey, pos.id.mintAddress, input.proof)
        if (!validate.proofComplete) return CommitResult.Rejected("SELL_PROOF_INCOMPLETE: ${validate.reason}")

        val uniqueKey = "${input.proof.signature}|${input.instructionIndex}|${if (input.ataClosedProven) "SELL" else "PARTIAL_SELL"}|${pos.id.mintAddress.take(8)}"
        fillIndex[uniqueKey]?.let { existingFill ->
            return CommitResult.Committed(posRef.get(), existingFill)
        }

        // Conservation.
        val newRemainingRaw = pos.remainingRawQty - validate.rawQuantitySold
        if (newRemainingRaw.value.signum() < 0) {
            ConservationInvariants6387.onFailure("SELL_RAW_QTY_EXCEEDS_REMAINING pid=${input.positionId.take(8)} sold=${validate.rawQuantitySold.value} remaining=${pos.remainingRawQty.value}")
            return CommitResult.Rejected("SELL_QTY_CONSERVATION_FAIL")
        }
        // Proportional cost basis allocation.
        val basisRatio = if (pos.entryRawQty.value.signum() > 0) {
            java.math.BigDecimal(validate.rawQuantitySold.value)
                .divide(java.math.BigDecimal(pos.entryRawQty.value), 30, java.math.RoundingMode.HALF_UP)
        } else java.math.BigDecimal.ZERO
        val allocatedBasis = java.math.BigDecimal(pos.remainingCostBasisLamports.value)
            .multiply(basisRatio)
            .setScale(0, java.math.RoundingMode.DOWN).toBigInteger()
        val newRemainingBasis = pos.remainingCostBasisLamports.value.subtract(allocatedBasis)
        // Realised P&L on THIS partial: proceeds - allocated basis.
        val partialPnl = validate.netLamportsReceived.value.subtract(allocatedBasis).toLong()

        val allClosed = input.ataClosedProven && newRemainingRaw.isZero()
        val newStatus = when {
            allClosed -> PositionStatus6387.CLOSED
            newRemainingRaw.isPositive() -> PositionStatus6387.PARTIALLY_CLOSED
            else -> pos.status
        }

        val fill = CanonicalFill6387(
            fillId = "${input.proof.signature}|${input.instructionIndex}|SELL",
            positionId = input.positionId,
            signature = input.proof.signature,
            instructionIndex = input.instructionIndex,
            side = if (allClosed) FillSide6387.SELL else FillSide6387.PARTIAL_SELL,
            rawTokenDelta = validate.rawQuantitySold,
            tokenDecimals = input.proof.decimals,
            lamportDelta = validate.netLamportsReceived.value.toLong(),
            networkFeeLamports = input.networkFeeLamports,
            priorityFeeLamports = input.priorityFeeLamports,
            tipLamports = input.tipLamports,
            slot = input.slot,
            blockTime = input.blockTime,
            commitment = input.commitment,
            proofSource = "WALLET_DELTA_PROVEN",
            provider = input.provider,
            createdAt = System.currentTimeMillis(),
        )
        val updated = pos.copy(
            status = newStatus,
            closedAt = if (allClosed) System.currentTimeMillis() else null,
            closingSignature = if (allClosed) input.proof.signature else null,
            remainingRawQty = newRemainingRaw,
            remainingCostBasisLamports = Lamports(newRemainingBasis.coerceAtLeast(java.math.BigInteger.ZERO)),
            realisedLamports = pos.realisedLamports + validate.netLamportsReceived,
            realisedPnlLamports = pos.realisedPnlLamports + partialPnl,
            learningEligibility = when {
                pos.walletAssetClass == WalletAssetClass6387.BOT_POSITION_RECOVERABLE_BASIS_UNKNOWN -> LearningEligibility6387.INELIGIBLE_UNKNOWN_BASIS
                allClosed -> LearningEligibility6387.ELIGIBLE
                else -> pos.learningEligibility
            },
            stateVersion = pos.stateVersion + 1,
            lastWalletProofSlot = input.slot,
            lastTransactionProofSlot = input.slot,
        )
        posRef.set(updated)
        fillIndex[uniqueKey] = fill
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                if (allClosed) "CANONICAL_SELL_COMMITTED_6387" else "CANONICAL_PARTIAL_COMMITTED_6387",
            )
        } catch (_: Throwable) {}
        return CommitResult.Committed(updated, fill)
    }

    fun findActive(wallet: String, mint: String, epoch: Long): CanonicalPosition6387? =
        positions.values.map { it.get() }.firstOrNull { p ->
            p.id.walletPublicKey == wallet && p.id.mintAddress == mint &&
                p.id.canonicalEpoch == epoch &&
                p.status in setOf(PositionStatus6387.PENDING_BUY_PROOF, PositionStatus6387.OPEN, PositionStatus6387.PARTIALLY_CLOSED)
        }

    fun canonicalRiskPositionMints(): Set<String> = positions.values.asSequence()
        .map { it.get() }
        .filter { it.walletAssetClass.countsAsRiskExposure() && it.status != PositionStatus6387.CLOSED }
        .map { it.id.mintAddress }.toSet()

    fun canonicalRiskPositionIds(): Set<String> = positions.values.asSequence()
        .map { it.get() }
        .filter { it.walletAssetClass.countsAsRiskExposure() && it.status != PositionStatus6387.CLOSED }
        .map { it.id.positionId }.toSet()

    fun canonicalClosedIdsCurrentEpoch(): Set<String> = positions.values.asSequence()
        .map { it.get() }
        .filter { it.status == PositionStatus6387.CLOSED && it.id.canonicalEpoch == canonicalEpoch.get() }
        .map { it.id.positionId }.toSet()

    fun setClassification(c: WalletAssetClassification6387) {
        classifications["${c.mint}|${c.tokenAccount}"] = c
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("WALLET_ASSET_CLASSIFIED_6387_${c.classification.name}") } catch (_: Throwable) {}
    }
    fun classificationsSnapshot(): List<WalletAssetClassification6387> = classifications.values.toList()

    internal fun clearAllForTest() {
        positions.clear(); fillIndex.clear(); classifications.clear()
        canonicalEpoch.set(1L)
    }
}
