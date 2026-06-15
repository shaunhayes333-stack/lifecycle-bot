package com.lifecyclebot.engine.sell

import java.math.BigInteger

/**
 * V5.0.3749 — typed live balance proof state machine.
 *
 * UNKNOWN is intentionally not ZERO. A provider empty-map / timeout / stale cache
 * may only produce UnknownBalanceProof or StalePositiveBalanceProof. Live close
 * finality requires either a confirmed sell signature with owner-token delta, or
 * an independently finalized ZeroBalanceProof.
 */
sealed class TypedBalanceProof {
    abstract val mint: String
    abstract val owner: String
    abstract val observedAtMs: Long
}

data class PositiveBalanceProof(
    override val mint: String,
    override val owner: String,
    val rawAmount: BigInteger,
    val decimals: Int,
    val uiAmount: Double,
    val source: String,
    val signature: String?,
    val slot: Long?,
    override val observedAtMs: Long,
) : TypedBalanceProof()

data class ZeroBalanceProof(
    override val mint: String,
    override val owner: String,
    val sources: Set<String>,
    val slot: Long?,
    override val observedAtMs: Long,
    val finalized: Boolean,
) : TypedBalanceProof() {
    val independentFinality: Boolean get() = finalized && sources.size >= 2
}

data class UnknownBalanceProof(
    override val mint: String,
    override val owner: String,
    val reason: String,
    val provider: String,
    override val observedAtMs: Long,
) : TypedBalanceProof()

data class StalePositiveBalanceProof(
    override val mint: String,
    override val owner: String,
    val lastPositiveRaw: BigInteger,
    val lastPositiveAtMs: Long,
    val reason: String,
    override val observedAtMs: Long,
) : TypedBalanceProof()
