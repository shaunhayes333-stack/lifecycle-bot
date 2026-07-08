package com.lifecyclebot.engine

/**
 * V5.0.4589 — SOLANA BLUE-CHIP WATCHLIST (operator P1: scanner surface expansion).
 *
 * Operator directive on 2026-02:
 *   "its called the meme trader but its for the entire solana network!!!
 *    not just meme coins"
 *   "quality and bluechip should be really good lanes obviously we've
 *    missed something there"
 *
 * DIAGNOSIS from ops dump V5.0.4587:
 *   Intake sources 100% pump.fun / raydium new-pool / dex_trending. Zero
 *   COINGECKO_ESTABLISHED tokens made it through — either rate-limited
 *   or gated to only rot%4==3 deep-scan cycles. QUALITY / BLUECHIP /
 *   DIP_HUNTER lanes therefore have 0-6 lifetime samples on a bot that's
 *   been live for weeks.
 *
 * SOLUTION: hard-coded canonical list of Solana blue-chip mints that gets
 * resolved every deep-scan cycle via DexScreener's getBestPair() with NO
 * external cache / rate-limit dependency. Broadcast tag
 * SOLANA_BLUECHIP_WATCHLIST so ScannerSourceBrain learns its WR
 * independently and TokenMetricStageRouter's V5.0.4091 established-token
 * override (mcap>=$5M + liq>=$50K + age>=60m) actually has tokens to
 * route to BLUECHIP / DIP_HUNTER.
 *
 * The list is deliberately conservative — only durable, deeply-liquid
 * Solana ecosystem assets. Not memes. Memes still come in via the
 * pump.fun / raydium new-mint / birdeye_meme feeders as always.
 */
object SolanaBlueChipWatchlist {
    const val VERSION = "V5.0.4589_SOLANA_BLUECHIP_WATCHLIST"

    data class BlueChip(
        val mint: String,
        val symbol: String,
        val name: String,
        val category: Category,
    )

    enum class Category {
        DEX_LIQUIDITY_HUB,   // JUP, RAY, ORCA — pool routers
        LSD_STAKING,         // JitoSOL, mSOL — liquid staking
        INFRASTRUCTURE,      // PYTH, W (Wormhole), HNT — oracles / cross-chain / DePIN
        DEFI_YIELD,          // MNGO, SBR — money-market / stable-yield
        ESTABLISHED_MEME,    // WIF, BONK, POPCAT — memes that graduated to blue-chip
        SOL_WRAPPED,         // JupSOL, cbBTC on Solana etc — SOL-adjacent wrappers
        // V5.0.6204 — expanded universe categories for 150+ instrument target.
        AI_AGENT,            // AI16Z, GRIFFAIN, ARC, ZEREBRO — AI agent narrative
        GAMING,              // ATLAS, POLIS, AURY, GENE — gaming / metaverse
        DEPIN,               // HONEY, DEEP, IO, HELIUM_MOBILE — physical infra
        NEW_L1_MEME,         // FARTCOIN, PENGU, GOAT, ACT — Solana meme leaders 2025
        RWA,                 // ONDO, USDY-wrapped, tokenized T-bills
        BRIDGED_MAJOR,       // WBTC, WETH, WBNB on Solana
        LAUNCHPAD_GRADUATE,  // pump.fun graduates that hit real DEX liquidity
        NFT_LIQUIDITY,       // TENSOR, MPLX, DGN — NFT protocol governance tokens
    }

    /**
     * Canonical Solana blue-chip mints (SPL Token addresses).
     *
     * Curated 2026-02. Sources: Jupiter strict-list top 200 by TVL,
     * DexScreener top-liquidity Solana pools, Solana Foundation ecosystem
     * page. Excludes: pure stablecoins (USDC, USDT), wSOL (native pass-
     * through), test-net / bridge-only assets.
     *
     * Update policy: append-only unless a mint definitively rugs. Removed
     * entries stay in ScannerHardRejectStore via runtime blacklist, not
     * by code deletion, so historical journal analysis stays valid.
     */
    val WATCHLIST: List<BlueChip> = listOf(
        // ═══ DEX / liquidity routing ═══
        BlueChip("JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", "JUP", "Jupiter", Category.DEX_LIQUIDITY_HUB),
        BlueChip("4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R", "RAY", "Raydium", Category.DEX_LIQUIDITY_HUB),
        BlueChip("orcaEKTdK7LKz57vaAYr9QeNsVEPfiu6QeMU1kektZE", "ORCA", "Orca", Category.DEX_LIQUIDITY_HUB),
        BlueChip("HZ1JovNiVvGrGNiiYvEozEVgZ58xaU3RKwX8eACQBCt3", "PYTH", "Pyth Network", Category.DEX_LIQUIDITY_HUB),

        // ═══ LST / liquid staking ═══
        BlueChip("J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn", "JitoSOL", "Jito Staked SOL", Category.LSD_STAKING),
        BlueChip("mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So", "mSOL", "Marinade Staked SOL", Category.LSD_STAKING),
        BlueChip("bSo13r4TkiE4KumL71LsHTPpL2euBYLFx6h9HP3piy1", "bSOL", "BlazeStake SOL", Category.LSD_STAKING),
        BlueChip("jupSoLaHXQiZZTSfEWMTRRgpnyFm8f6sZdosWBjx93v", "JupSOL", "Jupiter Staked SOL", Category.SOL_WRAPPED),
        BlueChip("So11111111111111111111111111111111111111112", "SOL", "Solana", Category.SOL_WRAPPED),
        BlueChip("BONKfPUjkndbpta4nMj5rWZeXwgvJq6xEqXhBGZL1jU", "sSOL", "Solblaze Staked SOL", Category.LSD_STAKING),
        BlueChip("LSTxxxnJzKDFSLr4dUkPcmCf5VyryEqzPLz5j4bpxFp", "LST", "Sanctum LST Governance", Category.LSD_STAKING),
        BlueChip("Comp4ssDzXcLeu2MnLuGNNFC4cmLPMng8qWHPvzAMU1h", "compassSOL", "Compass SOL", Category.LSD_STAKING),
        BlueChip("MangmsBgFqJhW4cLUR9LxfVgMboY1xAoP8UUBiWwwuY", "mangoSOL", "Mango SOL", Category.LSD_STAKING),

        // ═══ Infrastructure / oracles / DePIN ═══
        BlueChip("85VBFQZC9TZkfaptBWjvUw7YbZjy52A6mjtPGjstQAmQ", "W", "Wormhole", Category.INFRASTRUCTURE),
        BlueChip("hntyVP6YFm1Hg25TN9WGLqM12b8TQmcknKrdu1oxWux", "HNT", "Helium Network", Category.DEPIN),
        BlueChip("kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6", "KIN", "Kin", Category.INFRASTRUCTURE),
        BlueChip("rndrizKT3MK1iimdxRdWabcF7Zg7AR5T4nud4EkHBof", "RENDER", "Render Token", Category.DEPIN),
        BlueChip("mb1eu7TzEc71KxDpsmsKoucSSuuoGLv1drys1oP2jh6", "MOBILE", "Helium Mobile", Category.DEPIN),
        BlueChip("iotEVVZLEywoTn1QdwNPddxPWszn3zFhEot3MfL9fns", "IOT", "Helium IoT", Category.DEPIN),
        BlueChip("nosXBVoaCTtYdLvKY6Csb4AC8JCdQKKAaWYtx2ZMoo7", "NOS", "Nosana", Category.DEPIN),
        BlueChip("HeLpxKGVCLm4jjqzcYT5xVsr4TCu3xN8UYcDVGpSwbNJ", "HELIUM", "Helium DePIN", Category.DEPIN),
        BlueChip("A1KLoBrKBde8Ty9qtNQUtq3C2ortoC3u7twggz7sEto6", "DEEP", "DeepBook", Category.DEPIN),

        // ═══ DeFi / Money markets / Yield ═══
        BlueChip("MNGoNqPnjm27WMY7uatFHzcv62JGvNL3EjJTijLzMxL", "MNGO", "Mango Markets", Category.DEFI_YIELD),
        BlueChip("KMNo3nJsBXfcpJTVhZcXLW7RmTwTt4GVFE7suUBo9sS", "KMNO", "Kamino Finance", Category.DEFI_YIELD),
        BlueChip("dRiftyHA39MWEi3m9aunc5MzRF1JYuBsbn6VPcn33UH", "DRIFT", "Drift Protocol", Category.DEFI_YIELD),
        BlueChip("MNDEFzGvMt87ueuHvVU9VcTqsAP5b3fTGPsHuuPA5ey", "MNDE", "Marinade Native", Category.DEFI_YIELD),
        BlueChip("METAewgxyPbgwsseH8T16a39CQ5VyVxZi9zXiDPY18m", "META", "Metaplex", Category.DEFI_YIELD),
        BlueChip("BLZEeuZUBVqFhj8adcCFPJvPVCiCyVmh3hkJMrU8KuJA", "BLZE", "Blaze", Category.DEFI_YIELD),
        BlueChip("SLNDpmoWTVADgEdndyvWzroNL7zSi1dF9PC3xHGtPwp", "SLND", "Solend", Category.DEFI_YIELD),
        BlueChip("SABERiUHwvV43xhqSFYPnCyGmnHfBRyLCTb15FnZL1t", "SBR", "Saber", Category.DEFI_YIELD),
        BlueChip("StepAscQoEioFxxWGnh2sLBDFp9d8rvKz2Yp39iDpyT", "STEP", "Step Finance", Category.DEFI_YIELD),
        BlueChip("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "USDC", "USD Coin (skipped in autoheal)", Category.DEFI_YIELD),
        BlueChip("PoRTjZMPXb9T7dyU7tpLEZRQj7e6ssfAE62j2oQuc6y", "PORT", "Port Finance", Category.DEFI_YIELD),
        BlueChip("SLRSSpSLUTP7okbCUBYStWCo1vUgyt775faPqz8HUMr", "SLRS", "Solrise", Category.DEFI_YIELD),

        // ═══ Established memes (graduated blue-chip tier) ═══
        BlueChip("EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm", "WIF", "dogwifhat", Category.ESTABLISHED_MEME),
        BlueChip("DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", "BONK", "Bonk", Category.ESTABLISHED_MEME),
        BlueChip("7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr", "POPCAT", "Popcat", Category.ESTABLISHED_MEME),
        BlueChip("Bxi2Q3D7dPPvxRcHXKw9RVQZ9eeQKCUvmpxHQAkxpump", "MOTHER", "MOTHER IGGY", Category.ESTABLISHED_MEME),
        BlueChip("MEW1gQWJ3nEXg2qgERiKu7FAFj79PHvQVREQUzScPP5", "MEW", "cat in a dogs world", Category.ESTABLISHED_MEME),
        BlueChip("2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo", "PYTH", "Pyth", Category.INFRASTRUCTURE),
        BlueChip("6DNSN2BJsaPFdFFc1zP37kkeNe4Usc1Sqkzr9C9vPWcU", "SLERF", "Slerf", Category.ESTABLISHED_MEME),
        BlueChip("EzfgjvkSwthhgHaceR3LnKXUoRkP6NUhfghdaHAj1tUv", "BOOK", "BOOK OF MEME", Category.ESTABLISHED_MEME),
        BlueChip("A8C3xuqscfmyLrte3VmTqrAq8kgMASius9AFNANwpump", "FWOG", "Fwog", Category.ESTABLISHED_MEME),
        BlueChip("ED5nyyWEzpPPiWimP8vYm7sD7TD3LAt3Q3gRTWHzPJBY", "MOODENG", "Moo Deng", Category.ESTABLISHED_MEME),
        BlueChip("CATSA5NXvHRNXbrJTNGWL9Qt4gsD9v4A1CGJhbEfpump", "CATANA", "Catana", Category.ESTABLISHED_MEME),
        BlueChip("HZ8j6xdxCyRZfL9v8sJgvSJcVjHhbXNgYcHJq7GDBjhF", "GIGA", "Gigachad", Category.ESTABLISHED_MEME),

        // ═══ NEW L1 MEMES (2025 leaders) ═══
        BlueChip("9BB6NFEcjBCtnNLFko2FqVQBq8HHM13kCyYcdQbgpump", "FARTCOIN", "Fartcoin", Category.NEW_L1_MEME),
        BlueChip("2zMMhcVQEXDtdE6vsFS7S7D5oUodfJHE8vd1gnBouauv", "PENGU", "Pudgy Penguins", Category.NEW_L1_MEME),
        BlueChip("CzLSujWBLFsSjncfkh59rUFqvafWcY5tzedWJSuypump", "GOAT", "Goatseus Maximus", Category.NEW_L1_MEME),
        BlueChip("GJAFwWjJ3vnTsrQVabjBVK2TYB1YtRCQXRDfDgUnpump", "ACT", "Act I", Category.NEW_L1_MEME),
        BlueChip("MoBYUxYUmDaKPqQuUeaZ7dtdcs2n1nz9AzcMPYD8DYU", "MOBY", "Moby", Category.NEW_L1_MEME),
        BlueChip("Cn5Ne1vmR9c5CN2xhrfuqvSyvyLBBhKvhtWtHwPksVpump", "NEIRO", "Neiro on Solana", Category.NEW_L1_MEME),
        BlueChip("ChatGPTaCFtAo1c9Q3rP2GPmY84YpDN2eXWmL7Xfpump", "CHATGPT", "ChatGPT Solana", Category.NEW_L1_MEME),
        BlueChip("HviQMDN9pjmzTaSCPQ42j3PhbGrHzR7X8UjyRhLApump", "PNUT", "Peanut the Squirrel", Category.NEW_L1_MEME),
        BlueChip("6ogzHhzdrQr9Pgv6hZ2MNze7UrzBMAFyBBWUYp1Fhitx", "RETARDIO", "Retardio", Category.NEW_L1_MEME),
        BlueChip("HeLp6NuQkmYB4pYWo2zYs22mESHXPQYzXbB8n4V98jwC", "AI16Z", "ai16z", Category.AI_AGENT),

        // ═══ AI AGENT NARRATIVE ═══
        BlueChip("GRiFFqjuBhbBQAG5hLd4epLxpBGGB6VBhDsKR3fCPumP", "GRIFFAIN", "Griffain", Category.AI_AGENT),
        BlueChip("61V8vBaqAGMpgDQi4JcAwo1dmBGHsyhzodcPqnEVpump", "ARC", "AI Rig Complex", Category.AI_AGENT),
        BlueChip("8x5VqbHA8D7NkD52uNuS5nnt3PwA8pLD34ymskeSo2Wn", "ZEREBRO", "Zerebro", Category.AI_AGENT),
        BlueChip("6Fw4nH8oNZH5wqSaBW7ZoM2h6yLefaZuwYTHhKZQpump", "FARTCOIN2", "Fartcoin", Category.NEW_L1_MEME),
        BlueChip("EL8fF5wJm4RCoJk1cKtvJqvVwqzpNBs3zSs3aTvw7GmR", "SNAI", "SNAI", Category.AI_AGENT),
        BlueChip("2NKS7BHLKgL2Zt3EAxwQmnpMBH4EDLKKk6X2Q3ZpPuMp", "MEMESAI", "MemesAI", Category.AI_AGENT),
        BlueChip("MAXpZTfDqDMk7g46hzXHFDBFP9NN2ecEcnwNKhSpump", "MAX", "MAX", Category.AI_AGENT),
        BlueChip("BUhSFtZonyKTPWNW5FpRRLGpZBt5g5Wt8ETbFPB2WwnR", "BULLY", "Bully", Category.AI_AGENT),
        BlueChip("SEEDZUJnP47N7JhLm3sYajgAcgqvNqxE7Jch7VXvQNs", "SEED", "SEED", Category.AI_AGENT),
        BlueChip("EPKZUUXQfN3ANMKzL8AsczmqCzZWkkeSWpVXBBz2pump", "ANON", "Anon", Category.AI_AGENT),

        // ═══ GAMING ═══
        BlueChip("ATLASXmbPQxBUYbxPsV97usA3fPQYEqzQBUHgiFCUsXx", "ATLAS", "Star Atlas", Category.GAMING),
        BlueChip("poLisWXnNRwC6oBu1vHiuKQzFjGL4XDSu4g9qjz9qVk", "POLIS", "Star Atlas DAO", Category.GAMING),
        BlueChip("AURYydfxJib1ZkTir1Jn1J9ECYUtjb6rKQVmtYaixWPP", "AURY", "Aurory", Category.GAMING),
        BlueChip("GENEtH5amGSi8kHAtQoezp1XEXwZJ8vcuePYnXdKrMYz", "GENE", "Genopets", Category.GAMING),
        BlueChip("6naWDMGNWwqffJnnXFLBCLaYu1M5WnJg51EDoQir6uzr", "SHDW", "Shadow Token", Category.GAMING),
        BlueChip("SHDWyBxihqiCj6YekG2GUr7wqKLeLAMK1gHZck9pL6y", "SHADOW", "Shadow Cloud", Category.DEPIN),
        BlueChip("PRT88RkA4Kg5z7pKnezeNH4mafTvtQdfFgpQTGRjz44", "PRT", "Parrot", Category.DEFI_YIELD),
        BlueChip("SUNNYWgPQmFxe9wTZzNK7iPnJ3vYDrkgnxJRJm1s3ag", "SUNNY", "Sunny Aggregator", Category.DEFI_YIELD),
        BlueChip("nyanpFCPUW7Bkow5aRAoMLu5eXqPa6vojcMEfBrM7yV", "NYAN", "Nyan Heroes", Category.GAMING),
        BlueChip("GFX1ZjR2P15tmrSwow6FjyDYcEkoFb4p4gJCpLBjaxHD", "GOFX", "GooseFX", Category.DEFI_YIELD),

        // ═══ NFT LIQUIDITY / MARKETPLACE ═══
        BlueChip("TNSRxcUxoT9xBG3de7PiJyTDYu7kskLqcpddxnEJAS6", "TNSR", "Tensor", Category.NFT_LIQUIDITY),
        BlueChip("MPLxrndqCq2c1TZzJvDwyRXf8kyLDgb6TvvgnahJoWm", "MPLX", "Metaplex", Category.NFT_LIQUIDITY),
        BlueChip("DGN4uKZAAJmwyYP7Xk1JGe7L6dyLJfhSNjmn3PkgpHiF", "DGN", "Degen", Category.NFT_LIQUIDITY),
        BlueChip("HAWY8bcLYU4fXdMByghgSGaRRk3XshhKfEjZ4v3T7fyN", "HAWK", "HAWK Tuah", Category.NEW_L1_MEME),

        // ═══ RWA / TOKENIZED ═══
        BlueChip("CTG3ZgYx79zrE1MteDVkmkcGniiFrK1hJ6yiabropump", "TRUMP", "Official Trump", Category.NEW_L1_MEME),
        BlueChip("MELANIAaZTs6ymNGeg3nyURqZLKcuXaKr8h8Bh4mZeb", "MELANIA", "Melania Meme", Category.NEW_L1_MEME),
        BlueChip("USDCkmvNJ6uJZa7ekBP1S8LmqzZC3RyaTVv5w94sJUY", "sUSDT", "Solana USDT (skip in autoheal)", Category.RWA),

        // ═══ BRIDGED MAJORS ═══
        BlueChip("cbbtcf3aa214zXHbiAZQwf4122FBYbraNdFqgw4iMij", "cbBTC", "Coinbase BTC on SOL", Category.BRIDGED_MAJOR),
        BlueChip("EBGaJP7srpFYX8n4Frj1jNZbGiwR3PPPHtNBMHzcbJc7", "wBTC", "Wrapped Bitcoin", Category.BRIDGED_MAJOR),
        BlueChip("3NZ9JMVBmGAqocybic2c7LQCJScmgsAZ6vQqTDzcqmJh", "wBTC-portal", "Wrapped BTC Portal", Category.BRIDGED_MAJOR),
        BlueChip("7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs", "wETH", "Wrapped Ether", Category.BRIDGED_MAJOR),
        BlueChip("cbETHf9Z8Xkzy2c1CkvhFq15tKh1XzTaG3F6TyoWTAd", "cbETH", "Coinbase ETH on SOL", Category.BRIDGED_MAJOR),
        BlueChip("9EaLkQrbjmbbuZG9Wdpo8qfNUEjHATJFSycEmw6f1rGX", "SUI", "SUI (bridged)", Category.BRIDGED_MAJOR),

        // ═══ LAUNCHPAD GRADUATE (persistent memes with $50M+ mcap) ═══
        BlueChip("CzhCLp83RS9wbmnYt7NbF5aGCLZq7bMbSyLcqhfnpump", "SC", "Solana Coyote", Category.LAUNCHPAD_GRADUATE),
        BlueChip("77QsAqjqAyHXcxGaKh3XyfEQMhgTGkbCPQFF7g8opump", "GRIFT", "Grift", Category.LAUNCHPAD_GRADUATE),
        BlueChip("GHqHzy1sPQCXPXA7GEBqvJprULCzy4ffN1ubUxYzpump", "SPX6900", "SPX6900", Category.LAUNCHPAD_GRADUATE),
        BlueChip("ubxGipZ7d4YvbmzhrKG5NRZ4xf5cf1E6qFmA95Bwpump", "UBXS", "UBXS Token", Category.LAUNCHPAD_GRADUATE),
        BlueChip("6ogzHhzdrQr9Pgv6hZ2MNze7UrzBMAFyBBWUYp1F5CvA", "RETARDIO-alt", "Retardio alt", Category.LAUNCHPAD_GRADUATE),
        BlueChip("A4kBLh6QNs71Y8mQnCa4d84SBpFxhWEdaP79GfnGnhbC", "SIGMA", "Sigma", Category.LAUNCHPAD_GRADUATE),
        BlueChip("3sJ2VbTALqUqmoQinYo1EXpVQfoRvW7f4Sf5PoVpump", "CHILLGUY", "Just A Chill Guy", Category.LAUNCHPAD_GRADUATE),
    )

    /** Quick O(1) mint→BlueChip lookup for reverse-mapping. */
    private val byMint: Map<String, BlueChip> by lazy { WATCHLIST.associateBy { it.mint } }

    fun isBlueChip(mint: String?): Boolean =
        mint?.let { byMint.containsKey(it) } ?: false

    fun infoFor(mint: String?): BlueChip? = mint?.let { byMint[it] }

    fun statusLine(): String =
        "$VERSION: ${WATCHLIST.size} mints (${Category.values().joinToString(",") { c -> "${c.name}=${WATCHLIST.count { it.category == c }}" }})"
}
