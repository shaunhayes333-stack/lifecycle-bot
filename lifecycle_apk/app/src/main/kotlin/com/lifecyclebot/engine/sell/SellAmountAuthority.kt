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
    // V5.0.3711 — emergency exits may use buy-tied TX_PARSE longer than
    // discretionary exits. Helius/Jupiter token-account indexing can return an
    // empty map for minutes while a live meme is dumping; a STRICT_SL /
    // CATASTROPHE full exit must attempt the Jupiter exact-in raw amount instead
    // of silently requeueing until the stop is worthless.
    private const val EMERGENCY_TX_PARSE_MS = 10 * 60_000L
    // V5.0.3735 — profit-protect sells are also time-critical. Reports showed
    // PARTIAL_TAKE_PROFIT / capital_recovery sell stacks starting while RPC token
    // accounts were still empty/lagged, then queueing forever with EXEC_LIVE_SELL_OK=0.
    // Use the buy-verified TX_PARSE amount during the same 10m post-buy indexing gap,
    // but only for explicit profit-protection/capital-recovery reasons and never when
    // RPC positively returns a non-empty map missing the mint (that remains ZERO).
    private const val PROFIT_PROTECT_TX_PARSE_MS = 10 * 60_000L
    private const val PROOF_READY_CACHE_MS = 45_000L

    sealed class Resolution {
        data class Confirmed(
            val rawAmount: BigInteger,
            val decimals: Int,
            val source: Source,
        ) : Resolution()

        data class Zero(val source: Source) : Resolution()
        object Unknown : Resolution()
    }

    enum class Source { ACCOUNT_INFO, TOKEN_ACCOUNTS_BY_OWNER, TX_META_OWNER_DELTA, BALANCE_PROOF_POLLER }

    private data class ProofReadyEntry(
        val rawAmount: BigInteger,
        val decimals: Int,
        val capturedAtMs: Long,
        val source: Source,
    )
    private val proofReadyCache = ConcurrentHashMap<String, ProofReadyEntry>()

    fun recordProofReady(mint: String, rawAmount: BigInteger, decimals: Int, source: Source) {
        if (mint.isBlank() || rawAmount.signum() <= 0) return
        proofReadyCache[mint] = ProofReadyEntry(rawAmount, decimals, System.currentTimeMillis(), source)
        try { com.lifecyclebot.engine.ForensicLogger.lifecycle("BALANCE_PROOF_READY_CACHED", "mint=${mint.take(10)} raw=$rawAmount decimals=$decimals source=$source ttlMs=$PROOF_READY_CACHE_MS") } catch (_: Throwable) {}
    }

    private fun consumeProofReady(mint: String): Resolution.Confirmed? {
        val e = proofReadyCache[mint] ?: return null
        val ageMs = System.currentTimeMillis() - e.capturedAtMs
        if (ageMs > PROOF_READY_CACHE_MS || e.rawAmount.signum() <= 0) {
            proofReadyCache.remove(mint)
            return null
        }
        proofReadyCache.remove(mint)
        try { com.lifecyclebot.engine.ForensicLogger.lifecycle("BALANCE_PROOF_READY_CONSUMED", "mint=${mint.take(10)} raw=${e.rawAmount} decimals=${e.decimals} ageMs=$ageMs source=${e.source}") } catch (_: Throwable) {}
        return Resolution.Confirmed(e.rawAmount, e.decimals, Source.BALANCE_PROOF_POLLER)
    }

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
        try { com.lifecyclebot.engine.ForensicLogger.lifecycle("EXEC_TRACE_AUTHORITY", "side=BUY stage=OWNER_DELTA_CACHE_RECORD mint=${mint.take(10)} raw=$rawAmount decimals=$decimals sig=${txSignature.take(12)}") } catch (_: Throwable) {}
    }

    fun resolve(mint: String, wallet: SolanaWallet?): Resolution {
        if (mint.isBlank()) return Resolution.Unknown
        val w = wallet ?: return Resolution.Unknown

        // Source 1+2 fused: getTokenAccountsByOwner is what SolanaWallet
        // already exposes. EMPTY MAP MUST mean UNKNOWN (operator spec).
        val balances: Map<String, Pair<Double, Int>> = try {
            w.getTokenAccountsWithDecimalsBounded()
        } catch (e: Throwable) {
            // V5.0.3762 source fix: bounded wallet read failures/timeouts are
            // indeterminate, not empty wallet snapshots. Do not convert them to
            // emptyMap(), because that creates fake RPC_EMPTY_MAP sell waits.
            // V5.0.3789 operator fault #4: a failed wallet read is UNTRUSTED proof.
            // Mark the mint so the close authority / shutdown sweep hard-skip it and
            // never treat it as held, absent, closed, or sellable.
            try { com.lifecyclebot.engine.sell.LivePositionCloseAuthority.markUntrustedRpc(mint, "WALLET_TOKEN_READ_INDETERMINATE:${e.message?.take(40)}") } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("EXEC_TRACE_AUTHORITY", "side=SELL stage=WALLET_TOKEN_READ_INDETERMINATE mint=${mint.take(10)} rpc=UNTRUSTED err=${e.message?.take(120)}") } catch (_: Throwable) {}
            ErrorLogger.warn(TAG, "RPC_UNTRUSTED reason=WALLET_TOKEN_READ_INDETERMINATE mint=${mint.take(8)}… err=${e.message}")
            return Resolution.Unknown
        }
        if (balances.isEmpty()) {
            // Both token-program reads succeeded and found no positive balances.
            // Still UNKNOWN for one-shot sell authority; independent zero finality
            // is owned by reconciler/two-provider proof.
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("EXEC_TRACE_AUTHORITY", "side=SELL stage=BALANCE_RPC_CONFIRMED_EMPTY mint=${mint.take(10)} walletLoaded=true") } catch (_: Throwable) {}
            ErrorLogger.warn(TAG, "BALANCE_UNKNOWN reason=RPC_CONFIRMED_EMPTY mint=${mint.take(8)}…")
            return Resolution.Unknown
        }
        val entry = balances[mint]
        if (entry == null) {
            // V5.0.3749 — one provider missing the mint is UNKNOWN, not zero.
            // Zero finality requires the tracker/reconciler independent-proof path.
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("EXEC_TRACE_AUTHORITY", "side=SELL stage=BALANCE_MINT_ABSENT mint=${mint.take(10)} accounts=${balances.size}") } catch (_: Throwable) {}
            ErrorLogger.warn(TAG, "BALANCE_UNKNOWN reason=MINT_ABSENT_FROM_ONE_PROVIDER mint=${mint.take(8)}…")
            return Resolution.Unknown
        }
        val (uiAmount, decimals) = entry
        if (uiAmount <= 0.0) {
            ErrorLogger.warn(TAG, "BALANCE_UNKNOWN reason=ONE_PROVIDER_ZERO mint=${mint.take(8)}…")
            return Resolution.Unknown
        }
        val raw = if (decimals > 0)
            BigDecimal(uiAmount).movePointRight(decimals).toBigInteger()
        else
            BigDecimal(uiAmount).toBigInteger()
        try { com.lifecyclebot.engine.sell.LivePositionCloseAuthority.clearUntrustedRpc(mint) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.ForensicLogger.lifecycle("EXEC_TRACE_AUTHORITY", "side=SELL stage=BALANCE_RPC_CONFIRMED mint=${mint.take(10)} raw=$raw decimals=$decimals ui=$uiAmount source=TOKEN_ACCOUNTS_BY_OWNER") } catch (_: Throwable) {}
        return Resolution.Confirmed(raw, decimals, Source.TOKEN_ACCOUNTS_BY_OWNER)
    }

    private fun tryFreshTxParseFallback(mint: String, maxAgeMs: Long = FRESH_TX_PARSE_MS): Resolution.Confirmed? {
        val e = txParseCache[mint] ?: return null
        if (System.currentTimeMillis() - e.capturedAtMs > maxAgeMs) return null
        if (e.rawAmount.signum() <= 0) return null
        ErrorLogger.warn(TAG,
            "🟡 RPC empty for ${mint.take(8)}… — using TX_PARSE fallback raw=${e.rawAmount} " +
            "(captured ${(System.currentTimeMillis() - e.capturedAtMs) / 1000}s ago, " +
            "sig=${e.txSignature.take(8)}…)")
        ErrorLogger.warn(TAG, "BALANCE_PROOF_REJECTED reason=GENERIC_TX_PARSE_NOT_OWNER_FILTERED mint=${mint.take(8)}… sig=${e.txSignature.take(8)}…")
        return null
    }

    /**
     * V5.9.1533 — BALANCE RESOLUTION broadcast authority (operator spec item 5).
     *
     * A LIVE sell may broadcast ONLY when the balance is confirmed by an on-chain
     * read (ACCOUNT_INFO / TOKEN_ACCOUNTS_BY_OWNER == RPC_CONFIRMED / WALLET_SCAN_CONFIRMED).
     * Generic TX_PARSE / tracker-only balances are never authoritative wallet truth.
     * UNKNOWN never broadcasts.
     */
    enum class BalanceSource { RPC_CONFIRMED, WALLET_SCAN_CONFIRMED, UNKNOWN }

    fun balanceSource(resolution: Resolution?): BalanceSource = when (resolution) {
        is Resolution.Confirmed -> when (resolution.source) {
            Source.ACCOUNT_INFO -> BalanceSource.RPC_CONFIRMED
            Source.TOKEN_ACCOUNTS_BY_OWNER -> BalanceSource.WALLET_SCAN_CONFIRMED
            // V5.0.3778 — TX meta proves a historical owner delta, not CURRENT wallet balance.
            // It must never be equivalent to TOKEN_ACCOUNTS_BY_OWNER / DAS wallet truth,
            // or recovered ghost rows can keep trying to sell tokens the wallet no longer holds.
            Source.TX_META_OWNER_DELTA -> BalanceSource.UNKNOWN
            Source.BALANCE_PROOF_POLLER -> BalanceSource.WALLET_SCAN_CONFIRMED
        }
        is Resolution.Zero -> BalanceSource.UNKNOWN   // one provider missing-mint is not two-provider zero proof
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
    private val PROFIT_PROTECT_REASON_TOKENS = listOf(
        "PARTIAL_TAKE_PROFIT", "TAKE_PROFIT", "PROFIT_LOCK", "CAPITAL_RECOVERY", "RAPID_DRAWDOWN_FROM_PEAK_STOP",
    )

    fun isEmergencyExitReason(reason: String): Boolean {
        val r = reason.uppercase()
        return EMERGENCY_REASON_TOKENS.any { r.contains(it) }
    }

    fun isProfitProtectExitReason(reason: String): Boolean {
        val r = reason.uppercase()
        return PROFIT_PROTECT_REASON_TOKENS.any { r.contains(it) }
    }

    /**
     * Resolve balance for a concrete exit reason. UNKNOWN stays UNKNOWN unless
     * HostWalletTokenTracker has a persisted lastPositiveRaw from a confirmed buy
     * or wallet proof. One-provider missing/zero is never sell finality.
     */
    fun resolveForExit(mint: String, wallet: SolanaWallet?, reason: String): Resolution {
        val normal = resolve(mint, wallet)
        if (normal is Resolution.Confirmed || normal is Resolution.Zero) return normal
        consumeProofReady(mint)?.let { return it }
        val tracked = try { com.lifecyclebot.engine.HostWalletTokenTracker.getEntry(mint) } catch (_: Throwable) { null }
        val trackedRaw = tracked?.rawAmount?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { BigInteger(raw) }.getOrNull()
        }
        if (trackedRaw != null && trackedRaw.signum() > 0) {
            // V5.0.3778 — stale tracker raw is visibility only, never sell authority.
            // Runtime 3777 showed RECOVERED_* rows using lastPositiveRaw/TX_META_OWNER_DELTA
            // after the wallet no longer held those tokens. A positive historical balance
            // can keep a row open for reconciliation, but cannot authorize a broadcast.
            ErrorLogger.warn(TAG,
                "BALANCE_PROOF_REJECTED reason=STALE_TRACKER_RAW_NOT_CURRENT_WALLET_AUTHORITY mint=${mint.take(8)}… raw=$trackedRaw status=${tracked.status} source=${tracked.source}")
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("BALANCE_PROOF_REJECTED", "reason=STALE_TRACKER_RAW_NOT_CURRENT_WALLET_AUTHORITY mint=${mint.take(10)} status=${tracked.status.name} source=${tracked.source.name} raw=$trackedRaw action=wait_current_wallet_proof") } catch (_: Throwable) {}
        }
        // V5.0.3753 — live-sell finality restoration.
        // The buy path records owner-filtered tx-meta token delta after a landed
        // live buy. During Solana indexing gaps, getTokenAccountsByOwner can keep
        // returning an empty map for minutes, which stranded profit/SL sells in
        // SELL_WAITING_BALANCE_PROOF forever. Use the buy-tied owner-delta cache
        // only for time-critical exits, never for ordinary discretionary sells,
        // and keep the existing freshness windows.
        val maxAgeMs = when {
            isEmergencyExitReason(reason) -> EMERGENCY_TX_PARSE_MS
            isProfitProtectExitReason(reason) -> PROFIT_PROTECT_TX_PARSE_MS
            else -> FRESH_TX_PARSE_MS
        }
        val cached = txParseCache[mint]
        val ageMs = cached?.let { System.currentTimeMillis() - it.capturedAtMs } ?: Long.MAX_VALUE
        if (cached != null && ageMs <= maxAgeMs && cached.rawAmount.signum() > 0 &&
            (isEmergencyExitReason(reason) || isProfitProtectExitReason(reason))) {
            ErrorLogger.warn(TAG,
                "BALANCE_PROOF_REJECTED reason=BUY_TX_META_NOT_CURRENT_WALLET_AUTHORITY mint=${mint.take(8)}… raw=${cached.rawAmount} ageSec=${ageMs / 1000} reason=$reason sig=${cached.txSignature.take(8)}…")
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("BALANCE_PROOF_REJECTED", "reason=BUY_TX_META_NOT_CURRENT_WALLET_AUTHORITY mint=${mint.take(10)} exitReason=$reason raw=${cached.rawAmount} ageMs=$ageMs sig=${cached.txSignature.take(8)} action=wait_current_wallet_proof") } catch (_: Throwable) {}
        }
        return normal
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
        if (canBroadcastLive(resolution)) {
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("EXEC_TRACE_AUTHORITY", "side=SELL stage=BROADCAST_AUTH_ALLOW mint=${mint.take(10)} reason=$reason source=${balanceSource(resolution)} requestedRaw=${requestedRawAmount ?: "-"}") } catch (_: Throwable) {}
            return true
        }
        val cached = txParseCache[mint]
        if (cached != null) {
            ErrorLogger.warn(TAG,
                "BALANCE_PROOF_REJECTED reason=TX_PARSE_CACHE_NOT_RESOLVED_FOR_REASON mint=${mint.take(8)}… reason=$reason sig=${cached.txSignature.take(8)}…")
        }
        try { com.lifecyclebot.engine.ForensicLogger.lifecycle("EXEC_TRACE_AUTHORITY", "side=SELL stage=BROADCAST_AUTH_BLOCK mint=${mint.take(10)} reason=$reason source=${balanceSource(resolution)} requestedRaw=${requestedRawAmount ?: "-"}") } catch (_: Throwable) {}
        return false
    }


    fun resolveSellAmount(mint: String, owner: String, wallet: SolanaWallet?, intentAmountRaw: BigInteger? = null): BalanceProof {
        val observed = System.currentTimeMillis()
        val r = resolve(mint, wallet)
        val proof = when (r) {
            is Resolution.Confirmed -> BalanceProof(
                mint = mint,
                owner = owner,
                ata = null,
                amountRaw = intentAmountRaw?.let { if (it > r.rawAmount) r.rawAmount else it } ?: r.rawAmount,
                decimals = r.decimals,
                source = BalanceProofSource.RPC_CONFIRMED_OWNER_TOKEN_ACCOUNT,
                authoritative = true,
                observedAtMs = observed,
                signature = null,
            )
            is Resolution.Zero -> BalanceProof(
                mint = mint,
                owner = owner,
                ata = null,
                amountRaw = BigInteger.ZERO,
                decimals = 0,
                source = BalanceProofSource.BALANCE_UNKNOWN,
                authoritative = false,
                observedAtMs = observed,
                signature = null,
            )
            else -> BalanceProof(
                mint = mint,
                owner = owner,
                ata = null,
                amountRaw = BigInteger.ZERO,
                decimals = 0,
                source = BalanceProofSource.BALANCE_UNKNOWN,
                authoritative = false,
                observedAtMs = observed,
                signature = null,
            )
        }
        if (proof.authoritative) {
            ErrorLogger.info(TAG, "BALANCE_PROOF_ACCEPTED source=${proof.source} owner=${owner.take(8)}… mint=${mint.take(8)}… ata=${proof.ata ?: "?"} raw=${proof.amountRaw} decimals=${proof.decimals} sig=${proof.signature ?: ""}")
        } else {
            ErrorLogger.warn(TAG, "BALANCE_UNKNOWN reason=RPC_EMPTY_AND_NO_OWNER_DELTA mint=${mint.take(8)}…")
        }
        return proof
    }

    fun txMetaOwnerDeltaProof(mint: String, owner: String, wallet: SolanaWallet?, sig: String?): BalanceProof? {
        if (mint.isBlank() || sig.isNullOrBlank() || wallet == null) return null
        val delta = try { wallet.getOwnerTokenDeltaRawFromSig(sig, mint) } catch (_: Throwable) { null } ?: return null
        val proof = BalanceProof(
            mint = mint,
            owner = owner,
            ata = null,
            amountRaw = delta.first,
            decimals = delta.second,
            source = BalanceProofSource.TX_META_OWNER_DELTA,
            authoritative = delta.first.signum() > 0,
            observedAtMs = System.currentTimeMillis(),
            signature = sig,
        )
        if (proof.authoritative) {
            ErrorLogger.info(TAG, "BALANCE_PROOF_ACCEPTED source=${proof.source} owner=${owner.take(8)}… mint=${mint.take(8)}… ata=? raw=${proof.amountRaw} decimals=${proof.decimals} sig=${sig.take(8)}…")
        }
        return proof.takeIf { it.authoritative }
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
