package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.WalletAccountCache

/** One owner/mint balance observation; the clock is the read START, never callback delivery. */
data class WalletHoldingRead8527(
    val mint: String,
    val owner: String,
    val amount: CanonicalTokenAmount,
    val observedAtMs: Long,
)

/** Shared freshness contract for recovery and the existing protective-exit proof cache. */
object WalletHoldingFreshness8527 {
    fun fromSnapshot(
        evidence: WalletAccountCache.Evidence?, mint: String, owner: String,
        nowMs: Long, maxAgeMs: Long,
    ): WalletHoldingRead8527? {
        if (evidence == null || owner.isBlank() || evidence.owner != owner ||
            evidence.observedAtMs <= 0L || nowMs - evidence.observedAtMs !in 0L..maxAgeMs) return null
        val amount = evidence.tokens[mint] ?: return null
        if (amount.raw.signum() <= 0 || amount.decimals !in 0..18) return null
        return WalletHoldingRead8527(mint, owner, amount, evidence.observedAtMs)
    }

    /**
     * A signed unresolved sell owns finality. After a proven sell, an old buy receipt or
     * retained tracker quantity cannot recreate its inventory. A genuinely newer positive
     * owner/mint read may protect residuals or a new buy; no zero/close is inferred here.
     * Never-sold liabilities keep the existing protective recovery path.
     */
    fun mayRecover(
        mint: String, amount: CanonicalTokenAmount, owner: String,
        proof: WalletHoldingRead8527?, lastMovingSellAtMs: Long,
        signedSellUnresolved: Boolean, nowMs: Long,
    ): Boolean {
        if (mint.isBlank() || amount.raw.signum() <= 0 || amount.decimals !in 0..18 ||
            signedSellUnresolved) return false
        if (lastMovingSellAtMs <= 0L) return true
        return proof != null && owner.isNotBlank() && proof.owner == owner && proof.mint == mint &&
            proof.amount.raw == amount.raw && proof.amount.decimals == amount.decimals &&
            proof.observedAtMs > lastMovingSellAtMs && nowMs - proof.observedAtMs in 0L..60_000L
    }
}
