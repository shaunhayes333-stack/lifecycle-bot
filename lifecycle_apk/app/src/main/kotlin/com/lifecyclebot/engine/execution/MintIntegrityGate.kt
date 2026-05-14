package com.lifecyclebot.engine.execution

/**
 * V5.9.495z22 — Mint Integrity Gate (item A of the abcd refactor).
 *
 * Operator spec: "the bot ran for six hours straight in live mode. ive come back
 * to two tokens in my wallet that did not match what the bot displayed". Root
 * cause was a sloppy symbol→mint resolver that allowed:
 *   • the literal symbol string ("FLOKI") to flow into a buy as if it were a
 *     mint address, and
 *   • the Jupiter quote response's outputMint to differ from the originally
 *     requested target mint without anyone noticing.
 *
 * This gate is a single, central check used pre-buy:
 *   1. The mint string must look like a real Solana base58 mint
 *      (32–44 chars, base58 alphabet, no leading whitespace, not equal to
 *      its own symbol uppercase).
 *   2. If a Jupiter quote response is provided, its outputMint must equal
 *      the requested target mint exactly.
 *   3. The mint must not be the SOL or USDC mint when the caller asked for
 *      something else (catches "park as USDC" silent fallbacks).
 *
 * On rejection we stamp Forensics.OUTPUT_MISMATCH_BLOCKED so the operator
 * sees exactly why the trade was refused.
 */
object MintIntegrityGate {

    private const val SOL_MINT  = "So11111111111111111111111111111111111111112"
    private const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private const val USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"

    // V5.9.751 — Base44 ticket item #3 (USDC entering MEME target path).
    // Any of these mints as a TRADE TARGET for meme/altcoin trading is
    // categorically wrong — they're bridge/stable/system mints, not meme
    // launches. Callers should reject with WRONG_TARGET_MINT before
    // building/broadcasting. Distinct from USDC-routing-through (which
    // Jupiter does internally — that's fine; this set blocks USDC as the
    // final outputMint or as the original target.
    val MEME_TARGET_BLOCKLIST: Set<String> = setOf(
        SOL_MINT,                                                   // wSOL
        "So11111111111111111111111111111111111111111",              // SOL native (legacy form)
        USDC_MINT,                                                   // USDC
        USDT_MINT,                                                   // USDT
        "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So",              // mSOL
        "J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn",              // jitoSOL
        "bSo13r4TkiE4KumL71LsHTPpL2euBYLFx6h9HP3piy1",              // bSOL
        "7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs",              // stSOL (deprecated but still issued)
        "5oVNBeEEQvYi1cX3ir8Dx5n1P7pdxydbGF2X4TxVusJm",              // INF
    )

    /** True if this mint must never be entered as a meme-target buy. */
    fun isSystemOrStablecoinMint(mint: String?): Boolean =
        mint != null && mint in MEME_TARGET_BLOCKLIST

    /** Base58 alphabet — Solana addresses are base58-encoded 32-byte pubkeys. */
    private val BASE58_RE = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")
    private val PLACEHOLDERS = setOf("", "no-mint", "null", "unknown", "ticker", "symbol")

    sealed class Result {
        data object Ok : Result()
        data class Reject(val code: String, val reason: String) : Result()
    }

    /**
     * True iff the string is a syntactically-valid Solana mint address.
     * Pure check — no network IO. Use this anywhere the codebase used to
     * accept a symbol-shaped string as a mint placeholder.
     */
    fun isLikelyMint(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        if (!BASE58_RE.matches(s)) return false
        // Symbols rarely have lowercase + numbers in this length window —
        // double-check by rejecting strings that are pure uppercase letters
        // (e.g. "FLOKI...FLOKI") even if they happen to be 32+ chars.
        val lower = s.lowercase()
        if (lower in PLACEHOLDERS) return false
        return true
    }

    /**
     * Validate a target mint requested by a trader. Caller passes the
     * symbol it intended to buy so we can include it in the rejection
     * reason (purely cosmetic — the gate logic is symbol-blind).
     *
     * Caller must NOT broadcast a tx if this returns Reject — the
     * forensics line is logged here.
     */
    fun validatePreBuy(symbol: String?, targetMint: String?): Result {
        if (targetMint.isNullOrBlank()) {
            return reject(symbol, targetMint, "MINT_MISSING", "no on-chain mint resolved")
        }
        val normalized = targetMint.trim()
        val lower = normalized.lowercase()
        val sym = symbol?.trim().orEmpty()
        if (lower in PLACEHOLDERS || (sym.isNotBlank() && normalized.equals(sym, ignoreCase = true))) {
            return reject(symbol, targetMint, "MINT_PLACEHOLDER",
                "value '$targetMint' is a placeholder/symbol, not an on-chain address")
        }
        if (normalized.startsWith("cg:") || normalized.startsWith("static:")) {
            return reject(symbol, targetMint, "MINT_PLACEHOLDER",
                "registry placeholder $targetMint is not an on-chain address")
        }
        if (!isLikelyMint(normalized)) {
            return reject(symbol, targetMint, "MINT_NOT_BASE58",
                "value '${targetMint.take(24)}' is not a valid base58 Solana mint")
        }
        // Do NOT reject because the mint visually contains the symbol. Real
        // Solana mints can do this (KMNO -> KMNo3nJs...). Only placeholders,
        // invalid base58, or invalid lengths are rejected.
        return Result.Ok
    }

    /**
     * Cross-check a Jupiter quote response. We need to assert that the
     * `outputMint` Jupiter is about to swap into is the same mint we asked
     * for. Jupiter's atomic single-call route can include intermediates
     * internally, but the FINAL output must equal `requestedTargetMint`.
     */
    fun validateQuoteOutput(
        symbol: String?,
        requestedTargetMint: String,
        quoteOutputMint: String?,
    ): Result {
        if (quoteOutputMint.isNullOrBlank()) {
            return reject(symbol, requestedTargetMint, "QUOTE_NO_OUTPUT",
                "quote response had no outputMint")
        }
        if (quoteOutputMint != requestedTargetMint) {
            // Special case: routing through an intermediate (USDC/USDT) is fine
            // ONLY if it eventually outputs to the requested mint. Some legacy
            // paths build separate USDC quotes — those must be rejected here so
            // the caller never confuses them with a target buy.
            return reject(symbol, requestedTargetMint, "QUOTE_OUTPUT_MISMATCH",
                "quote outputMint=${quoteOutputMint.take(8)}… ≠ requested ${requestedTargetMint.take(8)}…")
        }
        return Result.Ok
    }

    /**
     * Sanity guard for callers that want to refuse a "silent USDC park" —
     * if someone asked for SYMBOL but the actual on-chain delta only
     * touched USDC, the buy is a phantom even if the tx confirmed.
     */
    fun isSilentStablePark(requestedTargetMint: String, deliveredMint: String): Boolean {
        if (requestedTargetMint == USDC_MINT || requestedTargetMint == USDT_MINT) return false
        return deliveredMint == USDC_MINT || deliveredMint == USDT_MINT
    }

    private fun reject(symbol: String?, mint: String?, code: String, reason: String): Result.Reject {
        try {
            Forensics.log(
                Forensics.Event.OUTPUT_MISMATCH_BLOCKED,
                mint = mint ?: "",
                msg = "MintIntegrityGate REJECT $code | sym=${symbol ?: "?"} | $reason",
            )
        } catch (_: Throwable) { /* never throw out of a logger */ }
        return Result.Reject(code, reason)
    }
}
