package com.lifecyclebot.engine.truth

import java.util.UUID

/**
 * V5.0.6387 — CANONICAL POSITION IDENTITY (Directive A, P0).
 * A position is NEVER identified by symbol / lane / timestamp / tracker.
 */
data class CanonicalPositionId6387(
    val positionId: String,
    val walletPublicKey: String,
    val mintAddress: String,
    val tokenAccount: String,
    val openingSignature: String,
    val openingInstructionIndex: Int,
    val runtimeGeneration: Long,
    val canonicalEpoch: Long,
) {
    init {
        require(positionId.isNotBlank())
        require(walletPublicKey.isNotBlank())
        require(mintAddress.isNotBlank())
        require(openingSignature.isNotBlank())
    }
    companion object {
        fun new(
            walletPublicKey: String, mintAddress: String, tokenAccount: String,
            openingSignature: String, openingInstructionIndex: Int,
            runtimeGeneration: Long, canonicalEpoch: Long,
        ) = CanonicalPositionId6387(
            positionId = UUID.randomUUID().toString(),
            walletPublicKey = walletPublicKey, mintAddress = mintAddress,
            tokenAccount = tokenAccount, openingSignature = openingSignature,
            openingInstructionIndex = openingInstructionIndex,
            runtimeGeneration = runtimeGeneration, canonicalEpoch = canonicalEpoch,
        )
    }
}

enum class PositionStatus6387 {
    PENDING_BUY_PROOF, OPEN, PARTIALLY_CLOSED, CLOSED, RECOVERED_BASIS_UNKNOWN, QUARANTINED,
}

enum class BasisState6387 {
    COMPLETE, PARTIAL_PROVEN, RECOVERED_KNOWN, RECOVERED_UNKNOWN, MISSING,
}

enum class LearningEligibility6387 {
    ELIGIBLE, PENDING_PROOF, INELIGIBLE_UNKNOWN_BASIS, INELIGIBLE_QUARANTINED,
    INELIGIBLE_NON_TRADABLE, INELIGIBLE_LEGACY_PRE_CANONICAL,
}

/** Section: CanonicalPosition record. All quantities RAW (BigInteger). */
data class CanonicalPosition6387(
    val id: CanonicalPositionId6387,
    val symbol: String,
    val laneAtEntry: String,
    val tacticAtEntry: String,
    val status: PositionStatus6387,
    val openedAt: Long,
    val closedAt: Long?,
    val openingSignature: String,
    val closingSignature: String?,
    val entryRawQty: RawTokenAmount,
    val remainingRawQty: RawTokenAmount,
    val tokenDecimals: MintDecimals,
    val entryLamports: Lamports,
    val remainingCostBasisLamports: Lamports,
    val realisedLamports: Lamports,
    val realisedPnlLamports: Long,   // signed
    val basisState: BasisState6387,
    val walletAssetClass: WalletAssetClass6387,
    val learningEligibility: LearningEligibility6387,
    val stateVersion: Long,
    val lastWalletProofSlot: Long,
    val lastTransactionProofSlot: Long,
)

enum class FillSide6387 { BUY, SELL, PARTIAL_SELL }

data class CanonicalFill6387(
    val fillId: String,
    val positionId: String,
    val signature: String,
    val instructionIndex: Int,
    val side: FillSide6387,
    val rawTokenDelta: RawTokenAmount,
    val tokenDecimals: MintDecimals.Known,
    val lamportDelta: Long,               // signed: negative for BUY (SOL out), positive for SELL (SOL in)
    val networkFeeLamports: Lamports,
    val priorityFeeLamports: Lamports,
    val tipLamports: Lamports,
    val slot: Long,
    val blockTime: Long,
    val commitment: String,               // "finalized" / "confirmed"
    val proofSource: String,
    val provider: String,
    val createdAt: Long,
) {
    init {
        require(fillId.isNotBlank())
        require(signature.isNotBlank())
        require(commitment.equals("finalized", true)) {
            "V5.0.6387: CanonicalFill only accepts commitment=finalized (broadcast alone is not truth)"
        }
    }
    /** Section 2 uniqueness key. */
    fun uniqueKey(): String = "$signature|$instructionIndex|${side.name}|${positionId.take(8)}"
}
