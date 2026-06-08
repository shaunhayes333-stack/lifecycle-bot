package com.lifecyclebot.perps.crypto

/**
 * V5.9.1442 — Crypto universe filter.
 *
 * Operator mandate: "ensure solana network is isolated to the memetrader
 * outside of the major sol tokens." The crypto universe is a multi-chain
 * universe (BTC, ETH, BNB, XRP, ADA, …) and must NOT poach Solana-native
 * long-tail / meme tokens that belong to the memetrader's universe.
 *
 * Allowlist policy
 * ────────────────
 * The only Solana-native symbols admitted into the crypto universe are
 * the **major SOL L1 + top-tier SOL ecosystem majors** that legitimately
 * trade on every exchange (Binance/Coinbase/Kraken) alongside BTC/ETH:
 *
 *   SOL  — the L1 itself
 *   JTO  — Jito (Solana liquid staking)
 *   JUP  — Jupiter (Solana DEX aggregator)
 *   RAY  — Raydium (legacy DEX, exchange-listed)
 *   ORCA — Orca (exchange-listed)
 *   PYTH — Pyth Network (exchange-listed oracle)
 *   W    — Wormhole (cross-chain, exchange-listed)
 *
 * Everything else on the Solana network — every meme, every pump.fun
 * launch, every Bonk-derivative, every long-tail SOL token — belongs to
 * the memetrader's universe and MUST NOT enter the crypto trader's scan
 * universe even if its symbol happens to look like a major.
 *
 * Implementation: a denylist of common Solana-native meme/launch patterns
 * + a strict allowlist of major SOL ecosystem tickers. Any candidate that
 * is *Solana-native* and *not* in the allowlist is rejected at the
 * universe-filter boundary before scoring.
 */
object CryptoUniverseFilter {

    /** Major SOL ecosystem symbols admitted into the crypto universe. */
    val SOL_MAJORS_ADMITTED: Set<String> = setOf(
        "SOL", "JTO", "JUP", "RAY", "ORCA", "PYTH", "W",
    )

    /**
     * Common SOL-native long-tail / meme symbols explicitly blocked from
     * the crypto universe. Non-exhaustive — the canonical check is
     * isSolanaNative + admit-list, but listing the most-seen offenders
     * gives the operator a clear audit trail in the Live Forensics dump.
     */
    val SOL_MEMES_QUARANTINED: Set<String> = setOf(
        "BONK", "WIF", "PEPE", "MEME", "POPCAT", "MEW", "MYRO", "FLOKI",
        "BOME", "SLERF", "PONKE", "WEN", "AURA", "GIGA", "NEIRO", "TRUMP",
        "MOTHER", "FWOG", "CHILLGUY", "TURBO", "BOOK", "DADDY", "DEGEN",
        "PNUT", "GOAT", "ACT", "LUCE", "CHUD", "SHOG", "RETARDIO", "MICHI",
        "MUMU", "BAN", "MOODENG", "FARTCOIN", "SC", "BOBO",
    )

    /**
     * Return true iff a candidate is allowed into the CRYPTO universe.
     *
     * @param symbol         the ticker (case-insensitive)
     * @param isSolanaNative true if the token's primary venue is a Solana DEX
     */
    fun isAdmittedToCryptoUniverse(symbol: String, isSolanaNative: Boolean): Boolean {
        val s = symbol.uppercase().trim()
        if (s.isEmpty()) return false
        if (SOL_MEMES_QUARANTINED.contains(s)) return false
        if (!isSolanaNative) return true                 // multi-chain → always admit
        return SOL_MAJORS_ADMITTED.contains(s)
    }

    /**
     * Reason string for the universe-filter rejection (used by forensic
     * dump). Returns null if the candidate is admitted.
     */
    fun rejectionReason(symbol: String, isSolanaNative: Boolean): String? {
        val s = symbol.uppercase().trim()
        if (s.isEmpty()) return "EMPTY_SYMBOL"
        if (SOL_MEMES_QUARANTINED.contains(s)) return "SOL_MEME_QUARANTINED"
        if (isSolanaNative && !SOL_MAJORS_ADMITTED.contains(s)) return "SOL_NATIVE_NOT_MAJOR"
        return null
    }
}
