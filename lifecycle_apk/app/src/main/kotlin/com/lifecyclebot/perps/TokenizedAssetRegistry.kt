package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.UniversalBridgeEngine

/**
 * V5.9.16 — REAL TOKENIZED ASSET REGISTRY
 * ════════════════════════════════════════════════════════════════════════════
 * Maps our synthetic market symbols to actual on-chain mints so the bot
 * takes REAL exposure instead of rehypothecating SOL into USDC.
 *
 * Sources (all verified on solscan/explorer as of Feb 2026):
 *   • xStocks (Backed Finance, Token-2022 on Solana) — AAPLx / TSLAx / NVDAx / SPYx / QQQx
 *   • EURC (Circle-native EUR stablecoin on Solana)
 *   • PAXG (Paxos Gold, Wormhole-wrapped on Solana)
 *
 * Any ticker NOT in this map should remain paper-only. No silent SOL → USDC
 * drains for unknown synthetic markets.
 */
object TokenizedAssetRegistry {

    private const val TAG = "TokenizedRegistry"

    /** Map of market symbol → real Solana mint. Only verified mints here. */
    private val MINTS = mapOf(
        // ─── Tokenized Stocks (xStocks, Token-2022) ──────────────────────────
        "AAPL" to "XsbEhLAtcf6HdfpFZ5xEMdqW8nfAvcsP5bdudRLJzJp",  // AAPLx
        "TSLA" to "XsDoVfqeBukxuZHWhdvWHBhgEHjGNst4MLodqsJHzoB",  // TSLAx
        "NVDA" to "Xsc9qvGR1efVDFGLrVsmkzv3qi45LTBjeUKSPmx9qEh",  // NVDAx
        "SPY"  to "XsoCS1TfEyfFhfvj8EtZ528L3CaKBDBRqRapnBbDF2W",  // SPYx
        "QQQ"  to "Xs8S1uUs1zvS2p7iwtsG3b6fkhpvmwz4GYU3gWAmWHZ",  // QQQx

        // ─── Metals (Wormhole-wrapped) ───────────────────────────────────────
        "XAU"  to "C6oFsE8nXRDThzrMEQ5SxaNFGKoyyfWDDVPw37JKvPTe",  // PAXG wrapped
        "GOLD" to "C6oFsE8nXRDThzrMEQ5SxaNFGKoyyfWDDVPw37JKvPTe",  // PAXG alias

        // ─── Forex (Circle-native stablecoins) ───────────────────────────────
        "EUR"    to "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr",  // EURC
        "EURUSD" to "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr",  // EURC alias
    )

    /** User-provided additions at runtime (community / future backfills). */
    private val userMints = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Strip common suffixes / normalize a market symbol before lookup. */
    private fun normalize(symbol: String): String {
        val up = symbol.uppercase().trim()
        // Strip common synthetic suffixes: "USD", "USDT", "USDC", "-PERP", "/USD"
        return up
            .removeSuffix("-PERP")
            .removeSuffix("/USD")
            .removeSuffix("USDT")
            .removeSuffix("USDC")
            .removeSuffix("USD")
            .trim()
    }

    /**
     * Returns the Solana mint for [symbol] if we have a verified real route,
     * else null (caller should skip live execution and fall back to paper).
     */
    fun mintFor(symbol: String): String? {
        val key = normalize(symbol)
        return userMints[key] ?: MINTS[key]
    }

    /** Is there a real on-chain route for this symbol? */
    fun hasRealRoute(symbol: String): Boolean = mintFor(symbol) != null

    /** Human label used in logs. */
    fun routeLabel(symbol: String): String {
        val mint = mintFor(symbol) ?: return "NO_ROUTE"
        return "${normalize(symbol)}→${mint.take(4)}…${mint.takeLast(4)}"
    }

    /** Allow extending the registry at runtime (e.g., from config). */
    fun register(symbol: String, mint: String) {
        val key = normalize(symbol)
        userMints[key] = mint
        ErrorLogger.info(TAG, "✚ Registered route: $key → ${mint.take(6)}…${mint.takeLast(4)}")
    }

    /** Known symbols (built-in + user). */
    fun knownSymbols(): Set<String> = MINTS.keys + userMints.keys

    /** SOL mint re-exported for convenience. */
    const val SOL_MINT  = UniversalBridgeEngine.SOL_MINT
    const val USDC_MINT = UniversalBridgeEngine.USDC_MINT
}
