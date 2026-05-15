package com.lifecyclebot.data

/**
 * V5.9.771 EMERGENT MEME-ONLY — single source of truth for mint key
 * normalization. Operator forensic note:
 *
 *   "audit for mismatched keys: truncated mint vs full mint, pump
 *    suffix mints, bonk mints, symbol-keyed safety reports, pair-
 *    address keyed reports, pool-address keyed reports, candidate.
 *    mint vs tokenAddress vs baseMint vs pairAddress."
 *
 * Every subsystem (intake, safety cache, RugCheck cache, FDG,
 * executor, trade journal, live buy guard, host tracker, reconciler,
 * UI open positions) must call `CanonicalMint.normalize(raw)` before
 * using a mint string as a map key or equality target.
 *
 * For now this is a thin trim/whitespace strip — full canonicalization
 * (Pump suffix collapse, pair-address detection) is a follow-up
 * ticket. The helper centralises the call site so a future migration
 * is one-file.
 */
object CanonicalMint {
    /** Returns the canonical map-key form of [raw]. Idempotent. */
    fun normalize(raw: String): String = raw.trim()

    /** True iff [a] and [b] resolve to the same canonical mint. */
    fun matches(a: String, b: String): Boolean = normalize(a) == normalize(b)

    /** Set of well-known non-meme symbols that must never appear in
     *  the MEME watchlist regardless of source. The user's V5.9.770
     *  dump showed `FILTER REJECT USDT` immediately followed by
     *  `WATCHLISTED: USDT`, `MEME_DIRECT_INTAKE: USDT`,
     *  `PROTECTED_INTAKE: USDT` — proving the reject was only on the
     *  scanner path while other paths bypassed it.
     */
    val BLOCKED_MEME_SYMBOLS: Set<String> = setOf(
        "sol", "wsol", "usdt", "usdc", "ray", "jup", "msol", "jto",
        "bsol", "stsol", "lst", "jitosol",
    )

    /** True iff [symbol] is in the blocked-meme list (case-insensitive). */
    fun isBlockedMemeSymbol(symbol: String): Boolean =
        symbol.trim().lowercase() in BLOCKED_MEME_SYMBOLS
}
