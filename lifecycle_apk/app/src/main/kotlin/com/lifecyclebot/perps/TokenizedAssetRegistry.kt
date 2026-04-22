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
        // ═══════════════════════════════════════════════════════════════════
        // TOKENIZED STOCKS — xStocks (Backed Finance, Token-2022)
        // All mints verified via solscan / explorer.solana.com / phantom.com
        // ═══════════════════════════════════════════════════════════════════
        // Big Tech
        "AAPL"  to "XsbEhLAtcf6HdfpFZ5xEMdqW8nfAvcsP5bdudRLJzJp",  // AAPLx
        "TSLA"  to "XsDoVfqeBukxuZHWhdvWHBhgEHjGNst4MLodqsJHzoB",  // TSLAx
        "NVDA"  to "Xsc9qvGR1efVDFGLrVsmkzv3qi45LTBjeUKSPmx9qEh",  // NVDAx
        "MSFT"  to "XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX",  // MSFTx
        "META"  to "Xsa62P5mvPszXL1krVUnU5ar38bBSVcWAB6fmPCo5Zu",  // METAx
        "GOOGL" to "XsCPL9dNWBMvFtTmwcCA5v3xWPSMEBCszbQdiLLq6aN",  // GOOGLx
        "GOOG"  to "XsCPL9dNWBMvFtTmwcCA5v3xWPSMEBCszbQdiLLq6aN",  // alias
        "AMZN"  to "Xs3eBt7uRfJX8QUs4suhyU8p2M6DoUDrJyWBa8LLZsg",  // AMZNx
        "AMD"   to "XsXcJ6GZ9kVnjqGsjBnktRcuwMBmvKWh8S93RefZ1rF",  // AMDx
        "INTC"  to "XshPgPdXFRWB8tP1j82rebb2Q9rPgGX37RuqzohmArM",  // INTCx
        "AVGO"  to "XsgSaSvNSqLTtFuyWPBhK9196Xb9Bbdyjj4fH3cPJGo",  // AVGOx
        "ORCL"  to "XsGtpmjhmC8kyjVSWL4VicGu36ceq9u55PTgF8bhGv6",  // OPENx/ORCLx (same mint observed)

        // Crypto / Fintech
        "COIN"  to "Xs7ZdzSHLU9ftNJsii5fCeJhoRWSC32SQGzGQtePxNu",  // COINx
        "MSTR"  to "XsP7xzNPvEHS1m6qfanPUGjNmdnmsLKEoNAnHjdxxyZ",  // MSTRx
        "CRCL"  to "XsueG8BtpquVJX9LVLLEGuViXUungE6WmK5YZ3p3bd1",  // CRCLx (Circle)
        "HOOD"  to "XsvNBAYkrDRNhA7wPHQfX3ZUXZyZLdnCQDfHZ56bzpg",  // HOODx (Robinhood)

        // Finance / Banking
        "JPM"   to "XsMAqkcKsUewDrzVkait4e5u4y8REgtyS7jWgCpLV2C",  // JPMx
        "BRKB"  to "Xs6B6zawENwAbWVi7w92rjazLuAr5Az59qgWKcNb45x",  // BRKBx (Berkshire B)
        "BRK.B" to "Xs6B6zawENwAbWVi7w92rjazLuAr5Az59qgWKcNb45x",  // alias
        "MA"    to "XsApJFV9MAktqnAc6jqzsHVujxkGm9xcSUffaBoYLKC",  // MAx (Mastercard)

        // Consumer / Retail
        "MCD"   to "XsqE9cRRpzxcGKDXj1BJ7Xmg4GRhZoyY1KpmGSxAWT2",  // MCDx (McDonald's)
        "HD"    to "XszjVtyhowGjSC5odCqBpW1CtXXwXjYokymrk7fGKD3",  // HDx (Home Depot)
        "NFLX"  to "XsEH7wWfJJu2ZT3UCFeVfALnVA6CP5ur7Ee11KmzVpL",  // NFLXx

        // Defense / Data
        "PLTR"  to "XsoBhf2ufR8fTyNSjqfU71DYGaE6Z3SUGAidpzriAA4",  // PLTRx (Palantir)

        // Healthcare
        "LLY"   to "Xsnuv4omNoHozR6EEW5mXkw8Nrny5rB3jVfLqi6gKMH",  // LLYx (Eli Lilly)

        // Energy
        "XOM"   to "XsaHND8sHyfMfsWPj6kSdd5VwvCayZvjYgKmmcNL5qh",  // XOMx (Exxon)
        "CVX"   to "XsNNMt7WTNA2sV3jrb1NNfNgapxRF5i4i6GcnTRRHts",  // CVXx (Chevron)

        // ETFs
        "SPY"   to "XsoCS1TfEyfFhfvj8EtZ528L3CaKBDBRqRapnBbDF2W",  // SPYx (S&P 500)
        "QQQ"   to "Xs8S1uUs1zvS2p7iwtsG3b6fkhpvmwz4GYU3gWAmWHZ",  // QQQx (Nasdaq)

        // Crypto-asset xStocks (tokenized L1 equities)
        "TRON"  to "XsRrH4fDA27xfyDjpcwefvcBdHCXs34krUuaXGEXzqz",  // TRONx

        // ═══════════════════════════════════════════════════════════════════
        // COMMODITIES — gold
        // ═══════════════════════════════════════════════════════════════════
        "GLD"   to "Xsv9hRk1z5ystj9MhnA7Lq4vjSsLwzL2nxrwmwtD3re",  // GLDx (Backed, xStocks)
        "XAU"   to "C6oFsE8nXRDThzrMEQ5SxaNFGKoyyfWDDVPw37JKvPTe",  // PAXG (Wormhole wrapped)
        "GOLD"  to "C6oFsE8nXRDThzrMEQ5SxaNFGKoyyfWDDVPw37JKvPTe",  // alias
        "PAXG"  to "C6oFsE8nXRDThzrMEQ5SxaNFGKoyyfWDDVPw37JKvPTe",  // alias

        // ═══════════════════════════════════════════════════════════════════
        // FOREX — Circle-native stablecoins on Solana
        // ═══════════════════════════════════════════════════════════════════
        "EUR"    to "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr",  // EURC
        "EURUSD" to "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr",  // alias
        "EURC"   to "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr",  // alias
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

    /**
     * V5.9.110: All Solana mints registered to Markets traders. Used by
     * StartupReconciler to skip auto-selling these on restart — they are
     * legit Markets-trader holdings, NOT orphans.
     */
    fun allMints(): Set<String> = MINTS.values.toSet() + userMints.values.toSet()

    /** SOL mint re-exported for convenience. */
    const val SOL_MINT  = UniversalBridgeEngine.SOL_MINT
    const val USDC_MINT = UniversalBridgeEngine.USDC_MINT
}
