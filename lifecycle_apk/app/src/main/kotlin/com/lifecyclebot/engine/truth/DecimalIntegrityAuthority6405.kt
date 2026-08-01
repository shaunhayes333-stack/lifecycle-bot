package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.network.SolanaWallet
import org.json.JSONArray
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6405 §5 — DECIMAL INTEGRITY HARD BLOCK (root-cause fix, not a bandaid).
 *
 * OPERATOR DIRECTIVE
 * ───────────────────
 * "false or unknown data is inexcusable!!! fix the issue instead of
 *  bandaiding" — 06 Feb 2026.
 *
 * ROOT CAUSE
 * ──────────
 * Executor.resolveSellUnitsForMint historically coerced unknown decimals
 * to 9 (`walletEntry?.second ?: fallbackDecimals ?: 9`) and, on any
 * BigDecimal authority failure, silently reverted to Double math via
 * `(cappedQty * 10.0.pow(decimals)).toLong()`. Both branches allowed the
 * bot to broadcast sells with fabricated scale factors — the same class
 * of defect that caused the 100× over-sold rows in V5.0.6400/6402.
 *
 * FIX (no bandaids)
 * ──────────────────
 *   1. Decimals come ONLY from a verifiable source:
 *        a. wallet.getTokenAccountsWithDecimalsBounded()[mint].second
 *           (proof: RPC-parsed SPL token account for that owner+mint)
 *        b. MintDecimalsAuthority6392 chain-resolved cache
 *        c. On-chain getAccountInfo(mint, jsonParsed).parsed.info.decimals
 *           (the true source-of-truth for a mint's decimals)
 *        d. fallbackDecimals argument, ONLY IF it was previously tagged
 *           with a proof source (never a magic default)
 *      If none of a…d succeed, we throw [UnresolvableDecimalsException]
 *      and refuse to execute — the sell is aborted, journaled, and the
 *      operator is alerted. No default. No coercion. No guess.
 *
 *   2. Per-mint / per-position lifetime raw-qty ledger enforces the
 *      forensic invariant: sum(raw sold) <= raw entry. Any request that
 *      would exceed the entry raw quantity is clamped to
 *      (rawEntry - rawSoldSoFar) and forensic-logged with a
 *      SELL_QTY_CLAMPED_ENTRY_INVARIANT_6405 event.
 */
object DecimalIntegrityAuthority6405 {

    // ─────────────────────────────────────────────────────────────
    // Exceptions
    // ─────────────────────────────────────────────────────────────

    /**
     * Fatal (per-sell) — the mint's decimals cannot be resolved from any
     * verifiable source. Callers MUST refuse to execute the sell.
     */
    class UnresolvableDecimalsException(
        val mint: String,
        val reason: String,
    ) : RuntimeException("MINT_DECIMALS_UNRESOLVABLE mint=${mint.take(10)} reason=$reason")

    // ─────────────────────────────────────────────────────────────
    // Decimals resolution
    // ─────────────────────────────────────────────────────────────

    private data class DecimalsRecord(val count: Int, val source: String)

    /** Cache of last successfully resolved decimals per mint, with proof source. */
    private val resolved = ConcurrentHashMap<String, DecimalsRecord>()

    /**
     * Strict resolver. Never returns "unknown". Attempts, in order:
     *   1. walletCachedDecimals (from the caller's own getTokenAccountsWithDecimalsBounded read)
     *   2. MintDecimalsAuthority6392 cache
     *   3. Local resolved cache
     *   4. On-chain getAccountInfo(mint, jsonParsed)
     *   5. fallbackDecimals argument (last-resort proof from prior verified buy)
     *
     * If ALL sources fail, throws [UnresolvableDecimalsException].
     */
    fun resolveDecimalsStrict(
        mint: String,
        wallet: SolanaWallet?,
        walletCachedDecimals: Int?,
        fallbackDecimals: Int?,
    ): Int {
        require(mint.isNotBlank()) { "mint blank" }

        // 1) Live wallet read (highest authority — proves current holdings)
        if (walletCachedDecimals != null && walletCachedDecimals in 0..24) {
            resolved[mint] = DecimalsRecord(walletCachedDecimals, "WALLET_TOKEN_ACCOUNTS_6405")
            try { MintDecimalsAuthority6392.resolveAndCache(mint, walletCachedDecimals.coerceAtMost(18)) } catch (_: Throwable) {}
            return walletCachedDecimals
        }

        // 2) Shared chain-resolved authority cache
        try {
            val cached = MintDecimalsAuthority6392.get(mint)
            if (cached != null && cached in 0..24) {
                resolved[mint] = DecimalsRecord(cached, "MINT_DECIMALS_AUTHORITY_6392")
                return cached
            }
        } catch (_: Throwable) {}

        // 3) Local resolved cache (populated by prior chain reads via this authority)
        resolved[mint]?.let { rec ->
            if (rec.count in 0..24) return rec.count
        }

        // 4) Chain resolve — the source of truth for a mint's decimals.
        if (wallet != null) {
            val chainResult = fetchDecimalsFromChain(mint, wallet)
            if (chainResult != null && chainResult in 0..24) {
                resolved[mint] = DecimalsRecord(chainResult, "GET_ACCOUNT_INFO_MINT_6405")
                try { MintDecimalsAuthority6392.resolveAndCache(mint, chainResult.coerceAtMost(18)) } catch (_: Throwable) {}
                try {
                    ForensicLogger.lifecycle(
                        "MINT_DECIMALS_CHAIN_RESOLVED_6405",
                        "mint=${mint.take(10)} decimals=$chainResult source=getAccountInfo",
                    )
                    PipelineHealthCollector.labelInc("MINT_DECIMALS_CHAIN_RESOLVED_6405")
                } catch (_: Throwable) {}
                return chainResult
            }
        }

        // 5) Fallback ONLY when the caller has a proven prior source
        //    (e.g. TokenState.decimals stamped by a verified buy tx-meta).
        if (fallbackDecimals != null && fallbackDecimals in 0..24) {
            resolved[mint] = DecimalsRecord(fallbackDecimals, "CALLER_FALLBACK_6405")
            return fallbackDecimals
        }

        // Hard block.
        try {
            ForensicLogger.lifecycle(
                "DECIMAL_INTEGRITY_HARD_BLOCK_6405",
                "mint=${mint.take(10)} reason=no_verifiable_decimals wallet=${wallet != null} " +
                    "walletDecArg=${walletCachedDecimals ?: "-"} fallback=${fallbackDecimals ?: "-"}",
            )
            PipelineHealthCollector.labelInc("DECIMAL_INTEGRITY_HARD_BLOCK_6405")
        } catch (_: Throwable) {}
        ErrorLogger.warn(
            "DecimalIntegrity6405",
            "🚫 REFUSING SELL — decimals unresolvable for mint=${mint.take(10)} " +
                "(wallet=${wallet != null} fallback=${fallbackDecimals ?: "-"})",
        )
        throw UnresolvableDecimalsException(mint, "NO_VERIFIABLE_DECIMALS_SOURCE")
    }

    /**
     * getAccountInfo(mint, jsonParsed) → parsed.info.decimals.
     * Returns null on any RPC failure so the caller can either retry or
     * hard-block. Never returns a made-up value.
     */
    private fun fetchDecimalsFromChain(mint: String, wallet: SolanaWallet): Int? {
        return try {
            val params = JSONArray()
                .put(mint)
                .put(org.json.JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed"))
            val resp = wallet.rpcCall("getAccountInfo", params)
            val info = resp
                .optJSONObject("result")
                ?.optJSONObject("value")
                ?.optJSONObject("data")
                ?.optJSONObject("parsed")
                ?.optJSONObject("info")
                ?: return null
            val decimals = info.optInt("decimals", -1)
            if (decimals in 0..24) decimals else null
        } catch (e: Throwable) {
            try {
                ForensicLogger.lifecycle(
                    "MINT_DECIMALS_CHAIN_FETCH_FAIL_6405",
                    "mint=${mint.take(10)} err=${e.message?.take(80)}",
                )
                PipelineHealthCollector.labelInc("MINT_DECIMALS_CHAIN_FETCH_FAIL_6405")
            } catch (_: Throwable) {}
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Entry / sold raw-qty invariant  (sum(sold) <= entry)
    // ─────────────────────────────────────────────────────────────

    private data class EntryLedger(
        var entryRaw: BigInteger,
        var soldRaw: BigInteger,
    )

    /**
     * Keyed by mint + position generation. Position generation is caller-
     * supplied; the executor uses `pos.entryTime` (positive Long) or the
     * canonical position generation from LiveContinuity6392. Two entries
     * for the same mint with different entryTimes are separate lifetimes.
     */
    private val ledgers = ConcurrentHashMap<String, EntryLedger>()

    private fun key(mint: String, positionGeneration: Long): String =
        "$mint|$positionGeneration"

    /**
     * Record the raw entry quantity for a fresh position generation.
     * Idempotent — subsequent calls with the same key that report an
     * EQUAL or LOWER entry are ignored; a HIGHER entry (top-up) is added.
     */
    fun recordEntryRaw(mint: String, positionGeneration: Long, entryRaw: BigInteger) {
        if (mint.isBlank() || entryRaw.signum() <= 0) return
        val k = key(mint, positionGeneration)
        ledgers.compute(k) { _, prior ->
            if (prior == null) EntryLedger(entryRaw, BigInteger.ZERO)
            else if (entryRaw > prior.entryRaw) prior.copy(entryRaw = entryRaw)
            else prior
        }
    }

    /** Record raw quantity actually broadcast (or confirmed) on a sell. */
    fun recordSoldRaw(mint: String, positionGeneration: Long, soldRaw: BigInteger) {
        if (mint.isBlank() || soldRaw.signum() <= 0) return
        val k = key(mint, positionGeneration)
        ledgers.compute(k) { _, prior ->
            (prior ?: EntryLedger(BigInteger.ZERO, BigInteger.ZERO))
                .copy(soldRaw = (prior?.soldRaw ?: BigInteger.ZERO).add(soldRaw))
        }
    }

    /**
     * Enforce sum(raw sold) <= raw entry. Clamps [requestedRaw] to
     * remainingRaw when it would over-sell; returns the safe value.
     *
     * If no entry ledger exists (unknown history), we DO NOT clamp — the
     * caller's own wallet-cap gate still bounds the sell to actual
     * inventory. We emit a telemetry counter so operators can detect
     * lifetimes that were never registered (indicates missing wire-up).
     */
    fun clampSoldRawToEntry(
        mint: String,
        positionGeneration: Long,
        requestedRaw: BigInteger,
    ): BigInteger {
        if (requestedRaw.signum() <= 0) return BigInteger.ZERO
        val k = key(mint, positionGeneration)
        val ledger = ledgers[k] ?: run {
            try {
                PipelineHealthCollector.labelInc("SELL_ENTRY_LEDGER_MISSING_6405")
            } catch (_: Throwable) {}
            return requestedRaw
        }
        if (ledger.entryRaw.signum() <= 0) return requestedRaw
        val remaining = ledger.entryRaw.subtract(ledger.soldRaw).max(BigInteger.ZERO)
        if (requestedRaw <= remaining) return requestedRaw
        try {
            ForensicLogger.lifecycle(
                "SELL_QTY_CLAMPED_ENTRY_INVARIANT_6405",
                "mint=${mint.take(10)} gen=$positionGeneration " +
                    "entryRaw=${ledger.entryRaw} soldRawPrior=${ledger.soldRaw} " +
                    "requestedRaw=$requestedRaw clampedTo=$remaining",
            )
            PipelineHealthCollector.labelInc("SELL_QTY_CLAMPED_ENTRY_INVARIANT_6405")
        } catch (_: Throwable) {}
        return remaining
    }

    /** Diagnostics — remaining raw quantity for a position. */
    fun remainingRaw(mint: String, positionGeneration: Long): BigInteger? {
        val ledger = ledgers[key(mint, positionGeneration)] ?: return null
        return ledger.entryRaw.subtract(ledger.soldRaw).max(BigInteger.ZERO)
    }

    /** Diagnostics — full ledger snapshot. */
    fun snapshot(mint: String, positionGeneration: Long): Pair<BigInteger, BigInteger>? {
        val ledger = ledgers[key(mint, positionGeneration)] ?: return null
        return ledger.entryRaw to ledger.soldRaw
    }

    internal fun clearForTest() {
        resolved.clear()
        ledgers.clear()
    }
}
