package com.lifecyclebot.engine.sell

import java.math.BigInteger

data class BalanceProof(
    val mint: String,
    val owner: String,
    val ata: String?,
    val amountRaw: BigInteger,
    val decimals: Int,
    val source: BalanceProofSource,
    val authoritative: Boolean,
    val observedAtMs: Long,
    val signature: String?,
)

enum class BalanceProofSource {
    RPC_FINALIZED_OWNER_TOKEN_ACCOUNT,
    RPC_CONFIRMED_OWNER_TOKEN_ACCOUNT,
    RPC_CONFIRMED_SECOND_PROVIDER,
    TX_META_OWNER_DELTA,
    BALANCE_UNKNOWN,
    REJECTED_TX_PARSE,
}
