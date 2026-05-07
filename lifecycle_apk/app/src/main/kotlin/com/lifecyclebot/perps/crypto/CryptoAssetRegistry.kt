package com.lifecyclebot.perps.crypto

/**
 * V5.9.495z30 — CryptoUniverseTrader: per-asset profile.
 *
 * Strict separation from MemeTrader. MemeTrader handles pump.fun /
 * PumpPortal / Raydium / Meteora / Jupiter-routable Solana SPL memes.
 * CryptoUniverseTrader handles the wider crypto universe (BTC, XMR,
 * WBTC, PAXG, AXS, RENDER, TNSR, TON, etc.) via SOL→USDC→target where
 * an executable route exists, and is honest about CEX/bridge gaps
 * otherwise.
 */
data class CryptoAssetProfile(
    val symbol: String,
    val name: String,
    val nativeChain: String?,
    val coingeckoId: String?,
    val possibleSolanaMints: List<String>,
    val wrappedSymbols: List<String>,
    val isNativeOnly: Boolean,
    val isPerpOnly: Boolean,
    val preferredRoute: CryptoExecutionRoute,
    val allowedExecutors: Set<String>,
)

/**
 * Static seed registry. The DynamicAltTokenRegistry already holds the
 * concrete Solana mint addresses we discover at runtime; this registry
 * supplies *intent* (i.e. "BTC is native-only", "PAXG is wrapped on
 * Ethereum + Solana"). DynamicAltTokenRegistry remains the source of
 * truth for executable mint addresses.
 */
object CryptoAssetRegistry {

    // Intentionally short / curated — the dynamic alt registry covers
    // long-tail tokens at runtime. We just need the well-known native
    // and wrapped majors here so the resolver makes correct calls.
    private val seed: Map<String, CryptoAssetProfile> = listOf(
        // ── Native L1s with no Solana SPL representation by default ──
        CryptoAssetProfile(
            symbol = "BTC", name = "Bitcoin", nativeChain = "Bitcoin",
            coingeckoId = "bitcoin",
            possibleSolanaMints = emptyList(),
            wrappedSymbols = listOf("WBTC", "cbBTC"),
            isNativeOnly = true, isPerpOnly = false,
            preferredRoute = CryptoExecutionRoute.CEX_REQUIRED,
            allowedExecutors = setOf("CEX", "BRIDGE", "PERPS"),
        ),
        CryptoAssetProfile(
            symbol = "XMR", name = "Monero", nativeChain = "Monero",
            coingeckoId = "monero",
            possibleSolanaMints = emptyList(),
            wrappedSymbols = emptyList(),
            isNativeOnly = true, isPerpOnly = false,
            preferredRoute = CryptoExecutionRoute.CEX_REQUIRED,
            allowedExecutors = setOf("CEX"),
        ),
        CryptoAssetProfile(
            symbol = "TON", name = "Toncoin", nativeChain = "TON",
            coingeckoId = "the-open-network",
            possibleSolanaMints = emptyList(),
            wrappedSymbols = emptyList(),
            isNativeOnly = true, isPerpOnly = false,
            preferredRoute = CryptoExecutionRoute.BRIDGE_REQUIRED,
            allowedExecutors = setOf("CEX", "BRIDGE"),
        ),
        CryptoAssetProfile(
            symbol = "TRX", name = "Tron", nativeChain = "Tron",
            coingeckoId = "tron",
            possibleSolanaMints = emptyList(),
            wrappedSymbols = emptyList(),
            isNativeOnly = true, isPerpOnly = false,
            preferredRoute = CryptoExecutionRoute.BRIDGE_REQUIRED,
            allowedExecutors = setOf("CEX", "BRIDGE"),
        ),

        // ── Wrapped majors (the ONLY reliable Solana route for these
        // ── is via the wrapped SPL token; resolver verifies dyn registry).
        CryptoAssetProfile(
            symbol = "WBTC", name = "Wrapped BTC", nativeChain = "Solana-wrapped",
            coingeckoId = "wrapped-bitcoin",
            possibleSolanaMints = listOf(
                "3NZ9JMVBmGAqocybic2c7LQCJScmgsAZ6vQqTDzcqmJh", // Sollet WBTC (legacy)
            ),
            wrappedSymbols = listOf("BTC"),
            isNativeOnly = false, isPerpOnly = false,
            preferredRoute = CryptoExecutionRoute.JUPITER_ROUTABLE,
            allowedExecutors = setOf("JUPITER"),
        ),
        CryptoAssetProfile(
            symbol = "PAXG", name = "PAX Gold", nativeChain = "Ethereum",
            coingeckoId = "pax-gold",
            possibleSolanaMints = emptyList(),
            wrappedSymbols = emptyList(),
            isNativeOnly = false, isPerpOnly = false,
            preferredRoute = CryptoExecutionRoute.BRIDGE_REQUIRED,
            allowedExecutors = setOf("BRIDGE", "CEX"),
        ),

        // ── Solana-native or well-bridged SPL alts ──
        CryptoAssetProfile(
            symbol = "RENDER", name = "Render", nativeChain = "Solana",
            coingeckoId = "render-token",
            possibleSolanaMints = listOf("rndrizKT3MK1iimdxRdWabcF7Zg7AR5T4nud4EkHBof"),
            wrappedSymbols = emptyList(),
            isNativeOnly = false, isPerpOnly = false,
            preferredRoute = CryptoExecutionRoute.JUPITER_ROUTABLE,
            allowedExecutors = setOf("JUPITER"),
        ),
        CryptoAssetProfile(
            symbol = "TNSR", name = "Tensor", nativeChain = "Solana",
            coingeckoId = "tensor",
            possibleSolanaMints = listOf("TNSRxcUxoT9xBG3de7PiJyTDYu7kskLqcpddxnEJAS6"),
            wrappedSymbols = emptyList(),
            isNativeOnly = false, isPerpOnly = false,
            preferredRoute = CryptoExecutionRoute.JUPITER_ROUTABLE,
            allowedExecutors = setOf("JUPITER"),
        ),
        CryptoAssetProfile(
            symbol = "AXS", name = "Axie Infinity", nativeChain = "Ethereum/Ronin",
            coingeckoId = "axie-infinity",
            possibleSolanaMints = emptyList(),
            wrappedSymbols = emptyList(),
            isNativeOnly = false, isPerpOnly = false,
            preferredRoute = CryptoExecutionRoute.BRIDGE_REQUIRED,
            allowedExecutors = setOf("BRIDGE", "CEX"),
        ),
        CryptoAssetProfile(
            symbol = "ETH", name = "Ethereum", nativeChain = "Ethereum",
            coingeckoId = "ethereum",
            possibleSolanaMints = listOf("7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs"),  // Sollet wETH
            wrappedSymbols = listOf("WETH"),
            isNativeOnly = false, isPerpOnly = false,
            preferredRoute = CryptoExecutionRoute.JUPITER_ROUTABLE,
            allowedExecutors = setOf("JUPITER", "CEX"),
        ),
    ).associateBy { it.symbol.uppercase() }

    fun get(symbol: String): CryptoAssetProfile? = seed[symbol.uppercase()]

    fun all(): Collection<CryptoAssetProfile> = seed.values

    fun isKnownNativeOnly(symbol: String): Boolean =
        seed[symbol.uppercase()]?.isNativeOnly == true
}
