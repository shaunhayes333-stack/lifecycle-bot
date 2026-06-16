package com.lifecyclebot.engine

import java.math.BigInteger

/** V5.0.3783 — canonical per-mint wallet truth. No UNKNOWN state exists. */
sealed class WalletAuthoritySnapshot {
    abstract val mint: String
    abstract val observedAtMs: Long

    data class HELD(
        override val mint: String,
        val raw: BigInteger,
        val uiAmount: Double,
        val decimals: Int,
        val source: String,
        override val observedAtMs: Long,
    ) : WalletAuthoritySnapshot()

    data class ABSENT_CONFIRMED(
        override val mint: String,
        val sources: Set<String>,
        override val observedAtMs: Long,
    ) : WalletAuthoritySnapshot()

    /** Provider failed/degraded or proof is unavailable. This is diagnostic only, never open/sellable/held. */
    data class NO_CURRENT_HELD_PROOF(
        override val mint: String,
        val reason: String,
        override val observedAtMs: Long,
    ) : WalletAuthoritySnapshot()
}
