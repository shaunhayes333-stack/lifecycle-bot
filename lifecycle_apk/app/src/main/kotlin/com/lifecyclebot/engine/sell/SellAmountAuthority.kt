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
            w.getTokenAccountsWithDecimalsBounded()
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

    /**
     * V5.9.1533 — BALANCE RESOLUTION broadcast authority (operator spec item 5).
     *
     * A LIVE sell may broadcast ONLY when the balance is confirmed by an on-chain
     * read (ACCOUNT_INFO / TOKEN_ACCOUNTS_BY_OWNER == RPC_CONFIRMED / WALLET_SCAN_CONFIRMED).
     * A FRESH_TX_PARSE-only balance (HOST_TRACKER_TX_PARSE_ONLY) may QUEUE recovery but
     * must NOT broadcast — it is not authoritative wallet truth. UNKNOWN never broadcasts.
     */
    enum class BalanceSource { RPC_CONFIRMED, WALLET_SCAN_CONFIRMED, HOST_TRACKER_TX_PARSE_ONLY, UNKNOWN }

    fun balanceSource(resolution: Resolution?): BalanceSource = when (resolution) {
        is Resolution.Confirmed -> when (resolution.source) {
            Source.ACCOUNT_INFO -> BalanceSource.RPC_CONFIRMED
            Source.TOKEN_ACCOUNTS_BY_OWNER -> BalanceSource.WALLET_SCAN_CONFIRMED
            Source.FRESH_TX_PARSE -> BalanceSource.HOST_TRACKER_TX_PARSE_ONLY
        }
        is Resolution.Zero -> BalanceSource.RPC_CONFIRMED   // confirmed empty is authoritative
        else -> BalanceSource.UNKNOWN
    }

    /** True only for RPC_CONFIRMED / WALLET_SCAN_CONFIRMED — the only sources allowed to broadcast a live sell. */
    fun canBroadcastLive(resolution: Resolution?): Boolean = when (balanceSource(resolution)) {
        BalanceSource.RPC_CONFIRMED, BalanceSource.WALLET_SCAN_CONFIRMED -> true
        else -> false
    }

    /**
     * V5.0.3682 — EMERGENCY LIQUIDATION BYPASS (operator P0 directive).
     *
     * Normal partial / discretionary exits MUST require RPC_CONFIRMED or
     * WALLET_SCAN_CONFIRMED. But catastrophic exits (strict SL, hard floor,
     * rug, drain, shutdown, manual emergency, liquidate, full bot stop)
     * must NOT be deferred forever just because the wallet RPC briefly
     * returned an empty token map. In that case we fall through to the
     * authoritative TX_PARSE cache that was recorded at LIVE_BUY_LANDED
     * (raw amount + buy signature + capture-time), provided:
     *   • the TX_PARSE entry is still fresh (<= FRESH_TX_PARSE_MS / 90s)
     *   • a buy signature was captured (so we know the wallet really got tokens)
     *   • the requested raw amount is ≤ the recorded raw amount (never over-sell)
     *   • the operator reason is one of the catastrophic-exit set
     *
     * Anything else still returns false — discretionary exits queue retries.
     */
    private val EMERGENCY_REASON_TOKENS = listOf(
        "STRICT_SL", "HARD_FLOOR", "STOP_LOSS", "CATASTROPHE", "RUG", "DRAIN",
        "SHUTDOWN", "LIQUIDATE", "EMERGENCY", "MANUAL_EMERGENCY",
    )

    fun isEmergencyExitReason(reason: String): Boolean {
        val r = reason.uppercase()
        return EMERGENCY_REASON_TOKENS.any { r.contains(it) }
    }

    /**
     * Allow a broadcast when:
     *   • the resolution is RPC_CONFIRMED / WALLET_SCAN_CONFIRMED (normal path), OR
     *   • the exit reason is catastrophic AND the FRESH_TX_PARSE cache has a
     *     valid entry whose recorded raw amount ≥ requestedRawAmount.
     *
     * Caller passes the requestedRawAmount it intends to sell. Pass null if
     * the caller has not derived a raw amount yet (a non-null TX_PARSE entry
     * is still required, but the size-cap check is skipped).
     */
    fun canBroadcastLiveOrEmergency(
        resolution: Resolution?,
        reason: String,
        mint: String,
        requestedRawAmount: java.math.BigInteger? = null,
    ): Boolean {
        if (canBroadcastLive(resolution)) return true
        if (!isEmergencyExitReason(reason)) return false
        val cached = txParseCache[mint] ?: return false
        if (System.currentTimeMillis() - cached.capturedAtMs > FRESH_TX_PARSE_MS) return false
        if (cached.rawAmount.signum() <= 0) return false
        if (cached.txSignature.isBlank()) return false
        if (requestedRawAmount != null && requestedRawAmount > cached.rawAmount) return false
        ErrorLogger.warn(TAG,
            "🚨 EMERGENCY_BROADCAST_BYPASS: mint=${mint.take(8)}… reason=$reason " +
            "using FRESH_TX_PARSE raw=${cached.rawAmount} sig=${cached.txSignature.take(8)}… " +
            "(captured ${(System.currentTimeMillis() - cached.capturedAtMs) / 1000}s ago)")
        return true
    }

    /** Read-only snapshot of the cached TX_PARSE entry for a mint (for callers
     *  that want the exact raw amount to size an emergency sell). */
    data class TxParseSnapshot(val rawAmount: java.math.BigInteger, val decimals: Int, val txSignature: String, val capturedAtMs: Long)
    fun txParseSnapshot(mint: String): TxParseSnapshot? {
        val e = txParseCache[mint] ?: return null
        return TxParseSnapshot(e.rawAmount, e.decimals, e.txSignature, e.capturedAtMs)
    }

    /** Operator-facing: read-only snapshot for the UI Forensics tile. */
    fun txParseCacheSize(): Int = txParseCache.size
}
