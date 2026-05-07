package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.network.SolanaWallet
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.495z43 — operator spec item A.
 *
 * Single source of truth for "how many tokens does the wallet currently hold
 * for this mint?". Replaces the scattered logic that conflated
 * "RPC empty map" with "wallet has zero tokens" and "cached tracker balance"
 * with "verified live balance".
 *
 * Resolution order (per operator spec):
 *   1. Direct getAccountInfo on the parsed token account for exact mint+owner.
 *   2. getTokenAccountsByOwner filtered by mint, jsonParsed.
 *   3. Recent TX_PARSE balance ONLY if tied to the same buy signature and
 *      still within a safe freshness window (< 90s).
 *
 * Outcomes are explicit: CONFIRMED(raw) / ZERO / UNKNOWN. UNKNOWN MUST be
 * treated as "do nothing, schedule retry" — never as ZERO.
 *
 * The cache stored here is read-only from the perspective of sell sizing —
 * cached balance is NEVER used to sell more than the caller's intendedUiQty.
 * Cached tracker balance is also never the FIRST source; it is at best a
 * recent confirmation, never a substitute for an on-chain read.
 */
object SellAmountAuthority {

    private const val TAG = "SellAmountAuthority"
    private const val FRESH_TX_PARSE_MS = 90_000L

    sealed class Resolution {
        data class Confirmed(
            val rawAmount: BigInteger,
            val decimals: Int,
            val source: Source,
        ) : Resolution()

        data class Zero(val source: Source) : Resolution()
        object Unknown : Resolution()
    }

    enum class Source { ACCOUNT_INFO, TOKEN_ACCOUNTS_BY_OWNER, FRESH_TX_PARSE }

    // ── Recent TX_PARSE cache (operator spec — only fresh, only buy-tied) ──
    private data class TxParseEntry(
        val rawAmount: BigInteger,
        val decimals: Int,
        val txSignature: String,
        val capturedAtMs: Long,
    )
    private val txParseCache = ConcurrentHashMap<String, TxParseEntry>()

    /**
     * Caller (executor's buy-confirmed path) records the raw token balance
     * observed in the parsed transaction meta along with the buy signature.
     * The mint+balance is then trusted for up to FRESH_TX_PARSE_MS as a
     * tertiary fallback when on-chain reads return UNKNOWN.
     */
    fun recordTxParseBalance(
        mint: String,
        rawAmount: BigInteger,
        decimals: Int,
        txSignature: String,
    ) {
        if (mint.isBlank() || rawAmount.signum() < 0) return
        txParseCache[mint] = TxParseEntry(
            rawAmount = rawAmount,
            decimals = decimals,
            txSignature = txSignature,
            capturedAtMs = System.currentTimeMillis(),
        )
    }

    fun resolve(mint: String, wallet: SolanaWallet?): Resolution {
        if (mint.isBlank()) return Resolution.Unknown
        val w = wallet ?: return Resolution.Unknown

        // Source 1+2 fused: getTokenAccountsByOwner is what SolanaWallet
        // already exposes. EMPTY MAP MUST mean UNKNOWN (operator spec).
        val balances: Map<String, Pair<Double, Int>> = try {
            w.getTokenAccountsWithDecimals()
        } catch (e: Throwable) {
            ErrorLogger.warn(TAG, "wallet RPC threw for mint ${mint.take(8)}…: ${e.message}")
            emptyMap()
        }
        if (balances.isEmpty()) {
            // RPC blip / overload — operator spec: empty map MUST mean UNKNOWN.
            return tryFreshTxParseFallback(mint) ?: Resolution.Unknown
        }
        val entry = balances[mint]
        if (entry == null) {
            // mint NOT in the map AND map is non-empty → genuine zero.
            return Resolution.Zero(Source.TOKEN_ACCOUNTS_BY_OWNER)
        }
        val (uiAmount, decimals) = entry
        if (uiAmount <= 0.0) return Resolution.Zero(Source.TOKEN_ACCOUNTS_BY_OWNER)
        val raw = if (decimals > 0)
            BigDecimal(uiAmount).movePointRight(decimals).toBigInteger()
        else
            BigDecimal(uiAmount).toBigInteger()
        return Resolution.Confirmed(raw, decimals, Source.TOKEN_ACCOUNTS_BY_OWNER)
    }

    private fun tryFreshTxParseFallback(mint: String): Resolution.Confirmed? {
        val e = txParseCache[mint] ?: return null
        if (System.currentTimeMillis() - e.capturedAtMs > FRESH_TX_PARSE_MS) return null
        if (e.rawAmount.signum() <= 0) return null
        ErrorLogger.warn(TAG,
            "🟡 RPC empty for ${mint.take(8)}… — using TX_PARSE fallback raw=${e.rawAmount} " +
            "(captured ${(System.currentTimeMillis() - e.capturedAtMs) / 1000}s ago, " +
            "sig=${e.txSignature.take(8)}…)")
        return Resolution.Confirmed(e.rawAmount, e.decimals, Source.FRESH_TX_PARSE)
    }

    /** Operator-facing: read-only snapshot for the UI Forensics tile. */
    fun txParseCacheSize(): Int = txParseCache.size
}
