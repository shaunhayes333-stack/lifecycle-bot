package com.lifecyclebot.engine.truth

/**
 * V5.0.6386 — PROOF STATE MACHINE (Section 9 of the directive).
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   "Use strict mutually exclusive proof states: INTENT_CREATED,
 *    TRANSACTION_BUILT, SIGNATURE_RECEIVED, BROADCAST_PENDING,
 *    FINALIZED_PROOF_COMPLETE, FAILED_CONFIRMED, PENDING_RECONCILIATION,
 *    QUARANTINED. Only FINALIZED_PROOF_COMPLETE contributes to: wallet PnL,
 *    canonical performance, win/loss, profit factor, expectancy, strategy
 *    learning, governor state, tactic switching, sizing, treasury.
 *    A nonblank signature alone does not establish finality."
 *
 * All downstream truth-model consumers (PnL, WR, PF, expectancy, learning,
 * treasury, governor, tactic switcher, sizing) MUST call
 * `contributesToTruth()` before reading a row's numbers. Any row not in
 * FINALIZED_PROOF_COMPLETE contributes ZERO.
 */
sealed class ProofState6386 {
    /** Intent registered but no tx built. */
    object IntentCreated : ProofState6386()

    /** Tx built (route + quote + instructions). No signature. */
    object TransactionBuilt : ProofState6386()

    /** Signature received from signer. Not broadcast yet. */
    data class SignatureReceived(val signature: String) : ProofState6386() {
        init { require(signature.isNotBlank()) { "SignatureReceived requires non-blank sig" } }
    }

    /** Broadcast to RPC. Waiting for confirmation. */
    data class BroadcastPending(val signature: String) : ProofState6386() {
        init { require(signature.isNotBlank()) { "BroadcastPending requires non-blank sig" } }
    }

    /**
     * The ONLY state that contributes to PnL and learning.
     * Contains the full proof envelope with matching wallet/mint, pre/post
     * balances, decimals proof, and net lamports.
     */
    data class FinalizedProofComplete(
        val signature: String,
        val walletAddress: String,
        val mintAddress: String,
        val decimals: MintDecimals.Known,   // MUST be Known here — Unknown is a proof failure
        val preRawBalance: RawTokenAmount,
        val postRawBalance: RawTokenAmount,
        val preLamports: Lamports,
        val postLamports: Lamports,
        val feeLamports: Lamports,
    ) : ProofState6386() {
        init {
            require(signature.isNotBlank())
            require(walletAddress.isNotBlank())
            require(mintAddress.isNotBlank())
        }
    }

    /** Tx confirmed FAILED on-chain — a real, evidence-backed loss (usually just fee). */
    data class FailedConfirmed(val signature: String, val reason: String) : ProofState6386()

    /**
     * We think it happened but proof is not yet retrievable.
     * Per directive: do not learn, do not allocate treasury, do not
     * classify win/loss, do not update expectancy from these.
     */
    data class PendingReconciliation(val reason: String) : ProofState6386()

    /** Row is corrupted or unverifiable — permanently excluded from truth. */
    data class Quarantined(val reason: String) : ProofState6386()

    /**
     * TRUTH INVARIANT — the single choke point every consumer must call.
     * Returns true ONLY for FinalizedProofComplete. Anything else is
     * insufficient evidence for accounting.
     */
    fun contributesToTruth(): Boolean = this is FinalizedProofComplete

    /**
     * Human-readable state name for logs and telemetry keys.
     */
    fun stateName(): String = when (this) {
        IntentCreated -> "INTENT_CREATED"
        TransactionBuilt -> "TRANSACTION_BUILT"
        is SignatureReceived -> "SIGNATURE_RECEIVED"
        is BroadcastPending -> "BROADCAST_PENDING"
        is FinalizedProofComplete -> "FINALIZED_PROOF_COMPLETE"
        is FailedConfirmed -> "FAILED_CONFIRMED"
        is PendingReconciliation -> "PENDING_RECONCILIATION"
        is Quarantined -> "QUARANTINED"
    }
}
