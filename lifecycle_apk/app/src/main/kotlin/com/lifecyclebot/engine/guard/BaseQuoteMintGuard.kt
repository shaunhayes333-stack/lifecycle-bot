package com.lifecyclebot.engine.guard

/**
 * V5.9.1440 — BASE / QUOTE / STABLE MINT QUARANTINE (P0 data-integrity).
 *
 * CONFIRMED CORRUPTION (operator forensic, build 5.0.3439):
 *   The USDC mint EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v entered the
 *   SHITCOIN lane as a TARGET and produced a fabricated +8722 SOL / +$587k
 *   PnL row that then poisoned realized totals, expectancy, the lane tuner,
 *   the policy head, and the tax/PnL export.
 *
 * ROOT CAUSE: the scanners filtered base/quote pairs by SYMBOL
 *   (pair.baseSymbol in ["SOL","USDC",...]) — but a quote/base mint can reach
 *   the funnel with a different (or blank) symbol, so the symbol filter leaked.
 *   The only correct key is the MINT ADDRESS.
 *
 * This guard is the SINGLE authority, address-keyed, enforced before all five
 * phases: INTAKE, FDG, EXEC, JOURNAL, LEARNING. A rejected mint MUST NOT update
 * expectancy, the lane tuner, the policy head, or tax/PnL totals — callers drop
 * the candidate entirely (a forensic log line is fine; a learning write is not).
 *
 * Pure, allocation-light, no I/O — safe on any thread / hot path.
 */
object BaseQuoteMintGuard {

    const val REJECT_REASON = "BASE_OR_QUOTE_MINT_AS_TARGET"

    // Canonical Solana base / quote / stable / LST mints that must NEVER be a
    // trade TARGET. Verified present across the codebase (Jupiter/Helius paths).
    private val BLOCKED_MINTS: Set<String> = setOf(
        "So11111111111111111111111111111111111111112",   // wSOL / native SOL
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",   // USDC  ← confirmed corruptor
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BENwUPN",   // USDT
        "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So",   // mSOL  (Marinade LST)
        "J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn",   // jitoSOL
        "bSo13r4TkiE4KumL71LsHTPpL2euBYLFx6h9HP3piy1",   // bSOL  (BlazeStake LST)
        "7dHbWXmci3dT8UFYWYZweBLXgycu7Y3iL6trKn1Y7ARj",   // stSOL (Lido LST)
        "USDH1SM1ojwWUga67PGrgFWUHibbjqMvuMaDkRJTgkX",   // USDH
        "USDDUEsX3a2sCY9HBu2bXf1psBHnYE1MEjQ8YzcQXkH",   // USDD (legacy)
    )

    // Symbol fallback — only a SECONDARY safety net. Address is the source of
    // truth; this catches mislabelled rows whose mint we can't read but whose
    // symbol is unambiguously a base/quote/stable/LP token.
    private val BLOCKED_SYMBOLS: Set<String> = setOf(
        "SOL", "WSOL", "USDC", "USDT", "USDH", "USDD",
        "MSOL", "JITOSOL", "BSOL", "STSOL", "INF", "JSOL",
        "LP", "RLP", "WLP",   // raw LP tokens
    )

    /** Address-keyed reject. The canonical check. */
    fun isBlockedMint(mint: String?): Boolean {
        if (mint.isNullOrBlank()) return false
        return BLOCKED_MINTS.contains(mint.trim())
    }

    /** Secondary symbol-keyed reject (mislabelled rows / missing mint). */
    fun isBlockedSymbol(symbol: String?): Boolean {
        if (symbol.isNullOrBlank()) return false
        val s = symbol.trim().uppercase().removePrefix("$")
        return BLOCKED_SYMBOLS.contains(s)
    }

    /**
     * Canonical predicate used at every phase boundary. True ⇒ quarantine the
     * candidate (do NOT intake / FDG / exec / journal / learn from it).
     */
    fun shouldQuarantine(mint: String?, symbol: String? = null): Boolean =
        isBlockedMint(mint) || isBlockedSymbol(symbol)
}
