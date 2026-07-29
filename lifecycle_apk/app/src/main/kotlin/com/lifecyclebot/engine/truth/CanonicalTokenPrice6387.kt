package com.lifecyclebot.engine.truth

import java.math.BigDecimal

/**
 * V5.0.6387 — DIRECTIVE B P0 — STRONGLY TYPED PRICE OBJECT + IDENTITY INVARIANT.
 *
 * Naked Double prices are the root cause of QUICK_RUNNER_10X_FULL_EXIT firing
 * on losing trades (AE8Wq7 in the operator's export: entry USD 0.0002925 vs
 * entry SOL 0.0000015878757 — ratio ~184× falsely interpreted as appreciation).
 */
enum class PriceDenomination6387 {
    USD_PER_TOKEN,
    SOL_PER_TOKEN,
    LAMPORTS_PER_RAW_TOKEN,
    QUOTE_TOKEN_PER_TOKEN,
    UNKNOWN,
}

enum class PriceSource6387 {
    DEXSCREENER_PAIR, JUPITER_QUOTE, ONCHAIN_SWAP, ORCA_POOL,
    RAYDIUM_POOL, HELIUS_PRICE_FEED, MARK_TO_MARKET, UNKNOWN,
}

enum class PriceValidity6387 {
    VALID, STALE, RECONSTRUCTED_FROM_ESTIMATE, MARKET_CAP_DIVIDED_UNVERIFIED_SUPPLY,
    ZERO_INVALID, UNKNOWN_DENOMINATION,
}

data class CanonicalTokenPrice6387(
    val mint: String,
    val value: BigDecimal,
    val denomination: PriceDenomination6387,
    val quoteMint: String?,
    val source: PriceSource6387,
    val pairAddress: String?,
    val observedAtMs: Long,
    val observedSlot: Long?,
    val decimals: Int,
    val identityHash: String,
    val validity: PriceValidity6387,
) {
    init {
        require(mint.isNotBlank()) { "CanonicalTokenPrice6387 requires mint" }
    }
    fun isAuthoritativeForExit(): Boolean =
        validity == PriceValidity6387.VALID &&
        denomination != PriceDenomination6387.UNKNOWN &&
        value.signum() > 0
    companion object {
        /** Deterministic identity hash across mint + denomination + quote + decimals. */
        fun computeIdentityHash(mint: String, denomination: PriceDenomination6387, quoteMint: String?, decimals: Int): String {
            val src = "${mint}|${denomination.name}|${quoteMint ?: ""}|${decimals}"
            return src.hashCode().toString(16)
        }
    }
}

/**
 * PRICE IDENTITY INVARIANT — the choke point. Every profit calculation must
 * go through this before comparing entry and current.
 */
object PriceIdentityInvariant6387 {
    data class CheckResult(val compatible: Boolean, val reason: String)
    fun check(entry: CanonicalTokenPrice6387, current: CanonicalTokenPrice6387): CheckResult {
        if (entry.mint != current.mint)
            return CheckResult(false, "PRICE_IDENTITY_MISMATCH_MINT entry=${entry.mint.take(8)} current=${current.mint.take(8)}")
        if (entry.denomination != current.denomination)
            return CheckResult(false, "PRICE_IDENTITY_MISMATCH_DENOMINATION entry=${entry.denomination} current=${current.denomination}")
        if (entry.quoteMint != current.quoteMint)
            return CheckResult(false, "PRICE_IDENTITY_MISMATCH_QUOTE entry=${entry.quoteMint} current=${current.quoteMint}")
        if (entry.decimals != current.decimals)
            return CheckResult(false, "PRICE_IDENTITY_MISMATCH_DECIMALS entry=${entry.decimals} current=${current.decimals}")
        if (!entry.isAuthoritativeForExit())
            return CheckResult(false, "PRICE_IDENTITY_ENTRY_NOT_AUTHORITATIVE validity=${entry.validity}")
        if (!current.isAuthoritativeForExit())
            return CheckResult(false, "PRICE_IDENTITY_CURRENT_NOT_AUTHORITATIVE validity=${current.validity}")
        if (entry.value.signum() <= 0 || current.value.signum() <= 0)
            return CheckResult(false, "PRICE_IDENTITY_ZERO_OR_NEGATIVE")
        return CheckResult(true, "OK")
    }
    fun emitFailure(reason: String, entry: CanonicalTokenPrice6387?, current: CanonicalTokenPrice6387?) {
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PRICE_IDENTITY_MISMATCH_6387")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "PRICE_IDENTITY_MISMATCH_6387",
                "reason=$reason entry=${entry?.identityHash} entryDen=${entry?.denomination} currentDen=${current?.denomination}",
            )
        } catch (_: Throwable) {}
    }
}
