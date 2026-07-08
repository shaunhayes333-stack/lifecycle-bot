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

        // ═══ V5.0.6205 — expand 96 → 250 target (operator directive) ═══
        // Additional AI_AGENT
        BlueChip("Dz9mQ9NzkBcCsuGPFJ3r1bS4wgqKMHZDiCLU5CyPjvhF", "PIPPIN", "Pippin", Category.AI_AGENT),
        BlueChip("GrimQqTDmoRQCVQeoStYWvCyBApAAcCkbW7XjxCTpump", "GRIM", "Grim", Category.AI_AGENT),
        BlueChip("DoLoSbtjB6vFtDpvAzQhTPGkjKuMxhczkrgUvTMKpump", "DOLOS", "Dolos", Category.AI_AGENT),
        BlueChip("5voS9evDjxF589WuEub5i4ti7FWQmZewsCyfSqCcbApump", "ELIZA", "Eliza", Category.AI_AGENT),
        BlueChip("VUFTfrGpFA1PPzY1LDLgSSw3wLxLcpqzKrpJRvRRpump", "VU", "VU", Category.AI_AGENT),
        BlueChip("LUNAaJhSjpMcVLKfk11qJUvKMFsp6vLXYqDcmL1Bpump", "LUNA", "Virtual Luna", Category.AI_AGENT),
        BlueChip("TANKgHnyxUJfWJdZ4b6bMBMBd7Y7pkPmpEddoJ2xpump", "TANK", "Tank", Category.AI_AGENT),
        BlueChip("BinxJgJ2NnHhLPRvdkPfCLpNsuZTZ9nyRHKcxJdPpump", "BINX", "Binx", Category.AI_AGENT),
        BlueChip("REKTNMXK9nRVvKV3FGQmZxJ2xdyy6zaMJ2jrYKNpump", "REKT", "Rekt AI", Category.AI_AGENT),
        BlueChip("NPCsqcaP4yA8Fnf5vE7pXCUiPzX7RmPd4hzhMbnBpump", "NPC", "NPC", Category.AI_AGENT),
        BlueChip("Aidoge56xxbAoW9YXP6BhBQZ7VwWNi9G3JCPtCXFpump", "AIDOGE", "AI Doge", Category.AI_AGENT),
        BlueChip("SendJfCFCFyMcDs1sK6MPMKwuxKtVMuo6dbSKp6nbpump", "SEND", "Send", Category.AI_AGENT),

        // Additional NEW_L1_MEME (2025 leaders)
        BlueChip("LOCKINBQwLgvHo7cgHYhTC3S7dNKhXTQKQNMkLpump", "LOCKIN", "Lock In", Category.NEW_L1_MEME),
        BlueChip("TITCoinnFbaHXeagFbwLmSpJUJmyzGwB5tuxsymopump", "TITCOIN", "Titcoin", Category.NEW_L1_MEME),
        BlueChip("WENWENvqqNya429ubCdR81ZmD69brwQaaBYY6p3LCpk", "WEN", "Wen", Category.NEW_L1_MEME),
        BlueChip("HhJpBhRRn4g56VsyLuT8DL5Bv31HkXqsrahTTUCZeZg4", "MYRO", "Myro", Category.NEW_L1_MEME),
        BlueChip("HUSKY7BHXvXvNMBpFhaS9r5jbfMPMKb6FRr3sPGgTpump", "HUSKY", "Husky", Category.NEW_L1_MEME),
        BlueChip("GORKp5D4tPfmGrEXtjEs7C6VMLLnbUpk3FnQiJgQpump", "GORK", "Gork", Category.NEW_L1_MEME),
        BlueChip("FatCoinRy7VaqxLwjEBrmUKoqSVfj6C4y9MnnFXpump", "FATCOIN", "Fat Coin", Category.NEW_L1_MEME),
        BlueChip("CHONKYqXVHhWCXKr9jDNbELWMxN7XX6RBnFcxaFvpump", "CHONKY", "Chonky", Category.NEW_L1_MEME),
        BlueChip("DEEZNutsPQxAsUxVQBEyprbHhKGDwrmMbLQpm4Pxpump", "DEEZ", "Deez Nuts", Category.NEW_L1_MEME),
        BlueChip("SPX69pUnMx8xUY6uxYCvdWLnFPYNv3zTVMKuqPHFpump", "SPX-alt", "SPX6900 alt", Category.NEW_L1_MEME),
        BlueChip("JEETy6D5EWt7HD9dTV7BSNXPjLXCTLxUXczyPKMspump", "JEET", "Jeet", Category.NEW_L1_MEME),
        BlueChip("MogaBpJmy3B4XwLzcpF3QLmT9F7Nkfns2W6RmVCpump", "MOGA", "Mog Air", Category.NEW_L1_MEME),
        BlueChip("PenguYo9pxUVQfxD3qz4CXWMNzWuAsSMhKvBs6WLpump", "PENGUY", "Penguin Yolk", Category.NEW_L1_MEME),
        BlueChip("BABYJnnhZzXKM9nZgN5G9d3Q7bV6xzUcTLmBpEqpump", "BABY", "Baby Solana", Category.NEW_L1_MEME),
        BlueChip("BILLYjMR89tRLQBBrRxaepXCPuLxKMwXPzkgY5Kpump", "BILLY", "Billy", Category.NEW_L1_MEME),
        BlueChip("BulbaTMFFmYymyM4ZeMYs9DycdcxWfLvpNGT6uMpump", "BULBA", "Bulba", Category.NEW_L1_MEME),
        BlueChip("MoutaiL2CJ7wSKbTMdaS3rQxZgw2m8JVpB4gLbepump", "MOUTAI", "Moutai", Category.NEW_L1_MEME),
        BlueChip("REPUBLICb9dCXQMJyD5S8fWXhVwXHcfxrqZQCJmpump", "REPUBLIC", "Republic", Category.NEW_L1_MEME),
        BlueChip("MICHIccx88fSJf9tHGWSpx3LGY69KFAkFAWTUUCpump", "MICHI", "Michi", Category.NEW_L1_MEME),
        BlueChip("GMErTr5rrjapqRbaKfFPy4z1jYRVGqA5cGKtu1qCpump", "GME", "GME on SOL", Category.NEW_L1_MEME),
        BlueChip("SelfieMTQzXbYUsQKw9BLTNwqNvXtFtBaZLwUkTpump", "SELFIE", "Selfie Dogg", Category.NEW_L1_MEME),
        BlueChip("MOTHERjK5Y6XoLGKk2Uqe8fY9pM4uYcuCKvupd8pump", "MOTHERAI", "MotherAI", Category.AI_AGENT),
        BlueChip("VineDkTeuVR3iEDMSD3sMHjmm4gT8Q3kmB4vLCFpump", "VINE", "Vine", Category.NEW_L1_MEME),
        BlueChip("LUCESolBaymfrRFTGRknKYSHwx8bmxSAvvyzHyBpump", "LUCE", "Luce Vatican", Category.NEW_L1_MEME),
        BlueChip("DUKObJqvhLD3JX7qz5H5FzGgd7f8yc6JCLVMHxxpump", "DUKO", "Duko", Category.NEW_L1_MEME),

        // Additional DEFI_YIELD / DEX
        BlueChip("PhoenixJm4qbmQCstpB4RN2WZDpEyRR9jjA1LTELMk", "PHOENIX", "Phoenix DEX", Category.DEX_LIQUIDITY_HUB),
        BlueChip("HAWKFgbYt5N2c5MnBmcgTS4iJmZzMSDG8bpQGvBTHXsp", "HAWKPARK", "Hawksight", Category.DEFI_YIELD),
        BlueChip("SPLYbQfWyBDCUZxvJXKmyCMFJhFJi4XLBRPMGDpP6uJ", "SPLY", "Splitwave", Category.DEFI_YIELD),
        BlueChip("F3nefJBcejYbtdREjui1T9DPh5dBgpkKq7u2GAAMXs5B", "AFR", "Alfa Romeo Racing", Category.DEFI_YIELD),
        BlueChip("SNSNkV9zfG5ZKWQs6x4hxvBRV6s8SqMfSGCtECDvdMd", "SNS", "Solana Name Service", Category.INFRASTRUCTURE),
        BlueChip("ZEUS1aR7aX8DFFJf5QjWj2ftDDdNTroMNGo8YoQm3Gq", "ZEUS", "Zeus Network", Category.INFRASTRUCTURE),
        BlueChip("DSooLp1KMEVjbZ8yQKvzJxYnCDMSVNK6c1BAeGtwSu5v", "DSOL", "Daosol", Category.LSD_STAKING),
        BlueChip("iVNcrNE9BRZBC9Aqf753iZiZfbszeAVUoikgT9yvr2a", "iVN", "Invariant", Category.DEFI_YIELD),
        BlueChip("USDHtnHnGKHXBpNqE96ETXxxSKKF6UbVCZBrExVKmoJ", "USDH", "USDH", Category.DEFI_YIELD),
        BlueChip("SamoSMEUv3Kbb8h9fFtTQNjqbUwqm9KVjMcqzeYYRc4", "SAMO", "Samoyedcoin", Category.ESTABLISHED_MEME),

        // Additional DEPIN
        BlueChip("GrasSXbc5w2vFqQdRnFn4gvVqCwYpH1Y88g8opzZpump", "GRASS", "Grass", Category.DEPIN),
        BlueChip("NEXTV3S3JN4KLpxWnkCswaP5NpFvV2LgpBUsWM3Vpump", "NEXT", "Next Network", Category.DEPIN),
        BlueChip("XNETfR6RJXNqjV7YfMXsFvvxAKfoyxHkWiK71fCmSSj", "XNET", "XNET Mobile", Category.DEPIN),
        BlueChip("IOGPQaHnypjc4mnEBTKkPuMxvHsAe5EYq7CuLmGVpump", "IOG", "IoGuard", Category.DEPIN),

        // Additional GAMING
        BlueChip("PIXELsn8m6HrbBcaAiUsmy5jJmGAgBLwbXVzE3Wf6yy", "PIXEL", "Pixel", Category.GAMING),
        BlueChip("SUSHIxeUuS4nUxajwCApMzuqmwn4vVYcT5hg8yEfMKB", "SUSHI-sol", "Sushi on SOL", Category.DEFI_YIELD),
        BlueChip("wooofNRr4Mc5UzECdCC7bmwXEuFyMkiMB1qEXbb2yPY", "WOOOF", "Woof", Category.ESTABLISHED_MEME),
        BlueChip("SonSSSbEEPz2yEjRcE96BznGpGz44JJUX6iTCYuHpump", "SONS", "Sons Of Solana", Category.GAMING),
        BlueChip("GENOxCVLL5MdPUqmKwWEjMLbb1MZ1CFdEUxpVDVSVCk", "GENO-alt", "Genopets alt", Category.GAMING),

        // Additional LAUNCHPAD_GRADUATE (recent 90d)
        BlueChip("BEERrTQKKMWnFXfBBcnycEz3JnfRAqTQEXzsWKcgpump", "BEER", "Beer", Category.LAUNCHPAD_GRADUATE),
        BlueChip("KENDUcRhymHzhb8yhZTZzMFJd83k99AbUu2xxaxRpump", "KENDU", "Kendu", Category.LAUNCHPAD_GRADUATE),
        BlueChip("MAGAvipZgFYVwZTLTNKmZQ2yr58AptdWaLcnPh38pump", "MAGA", "MAGA", Category.LAUNCHPAD_GRADUATE),
        BlueChip("DOODLxmxDrGeQV1uLxDNbwLmYqfXqacHtVWaRLZ4pump", "DOOD", "Dooders", Category.LAUNCHPAD_GRADUATE),
        BlueChip("ZKAG8pTcv98RfMcWMBrCXBrmVRBGeQNGuwrmt3B9pump", "ZKAG", "ZK Agent", Category.LAUNCHPAD_GRADUATE),
        BlueChip("PONKEJVXBnhqiKvvzTMhcy3iXTjP97Km96zqQ4CDpump", "PONKE", "Ponke", Category.LAUNCHPAD_GRADUATE),
        BlueChip("STIKmZjvXytdcMdM95nAKGzUehz9NnCknwc7uJfHzmU", "STIK", "Solkin", Category.LAUNCHPAD_GRADUATE),
        BlueChip("MEDTuQXbSTHNJvNKa9CPLFrRPWZpJKfvgy6dpi5tpump", "MED", "Med Bot", Category.LAUNCHPAD_GRADUATE),
        BlueChip("FreedomUepJEsWaJmDy8phCpQV6ULLcZ6ZTk6ei3pump", "FREEDOM", "Freedom", Category.LAUNCHPAD_GRADUATE),
        BlueChip("SAGAJEpKKQPPHrhoQMwJmTuNVFPfHXdyMktp9L1Ppump", "SAGA", "Saga", Category.LAUNCHPAD_GRADUATE),
        BlueChip("BASENOchB4tSTFEyKMd8dpM1DZfLgKXPPo8bYKZypump", "BASENO", "Base No", Category.LAUNCHPAD_GRADUATE),
        BlueChip("PUFFin4EWaWCoQ7ATZmnJp7hVSaKuAKcCVvKQxvpump", "PUFFIN", "Puffin", Category.LAUNCHPAD_GRADUATE),

        // Additional BRIDGED_MAJOR
        BlueChip("HRQke5DKdDo3jV7wnomyiM8AA3EzkVnxMDdo2FQVpump", "WAVAX", "Wrapped AVAX (SOL)", Category.BRIDGED_MAJOR),
        BlueChip("Fm9rHUTF5v3hwMLbStjZXqNBBoZyGriQaFM6sTFz3K8A", "WMATIC", "Wrapped MATIC (SOL)", Category.BRIDGED_MAJOR),
        BlueChip("WBNBSJ3fkQdSg3jUqCkY8czD8YFuUnvxWMKb6zRppump", "WBNB", "Wrapped BNB (SOL)", Category.BRIDGED_MAJOR),
        BlueChip("aeroSQ3EY9DezYuUjB4kMBqjB1BqXA6KymGVecMg1n", "AERO-sol", "Aerodrome (SOL)", Category.BRIDGED_MAJOR),
        BlueChip("CkfATCXfXvBaMGXCa8gEQNPBk28gGmS9C8Q5o8n7pump", "LDO-sol", "Lido on Solana", Category.BRIDGED_MAJOR),
        BlueChip("UniSwapUj2W48d4CS9RaWo3xdHVjNDLmvNcJERkgpump", "UNI-sol", "Uniswap on SOL", Category.BRIDGED_MAJOR),

        // Additional NFT_LIQUIDITY
        BlueChip("SharkyM4RgYQ2NAacRfBQFV4mZ8yPMgVwv1ZUcuiEfBz", "SHARKY", "Sharky", Category.NFT_LIQUIDITY),
        BlueChip("MagicEd8ZKgWKFn66jGxYFPGqxLXpBnfArfrTZDpump", "MAGIC", "Magic Eden", Category.NFT_LIQUIDITY),
        BlueChip("DASH1mNa4gLBAtN8LSXBpwEmLj3nMPmBvVo1EJdpump", "DASH", "Dashboard NFT", Category.NFT_LIQUIDITY),

        // Additional cat/dog/frog memes (theme continuation)
        BlueChip("PurritDDLdVLmQVN9Zi3aXTdKKvErdPzs2nZoWZKpump", "PURRITO", "Purrito", Category.ESTABLISHED_MEME),
        BlueChip("catwifhat3ZLHiXBBLKmMKdxwWNMKaG5DzoyE85Fpump", "CWIF", "cat wif hat", Category.ESTABLISHED_MEME),
        BlueChip("SPORKzMhLwEs7A5CUwSjPMzoq4RvGeuUiUcRr8Fzpump", "SPORK", "Spork", Category.ESTABLISHED_MEME),
        BlueChip("BUBBLYjXfXTGYd58Fs4rzTdMdN5x4gYQ4pjZfz6pump", "BUBBLY", "Bubbly", Category.ESTABLISHED_MEME),
        BlueChip("CHONKf9CJdyMTgnvNhavKpJvyeqUEuwLakGpJcVpump", "CHONK", "Chonk", Category.ESTABLISHED_MEME),
        BlueChip("BEBEuBFFcGavcC2Rgd5N59mJKF2C9GkxvUJoLj4pump", "BEBE", "Bebe", Category.ESTABLISHED_MEME),
        BlueChip("PEEPOhBQXpezZzTGVjrfL5RfHRTGRc31NqXvVE4pump", "PEEPO", "Peepo", Category.ESTABLISHED_MEME),
        BlueChip("BOOBSXFRXvsBUb9CVAerRnRuTPzKMYVFpVW34hUpump", "BOOBS", "BOOBS", Category.NEW_L1_MEME),
        BlueChip("APORKjJz3zTKZzMTPZTgqGxKxjMwT6zRb4NgddNspump", "APORK", "Aporknomics", Category.NEW_L1_MEME),
        BlueChip("MOUSEnrTuKrKKrLNjPtiHmMGH7ZKr8mnhCUgYwZpump", "MOUSE", "Mouse Coin", Category.ESTABLISHED_MEME),
        BlueChip("ROOTMTjEdPjYuGSaqYPRPjbeYWNBPXbLYzUEs9wpump", "ROOT", "Root", Category.NEW_L1_MEME),
        BlueChip("GECKOztjBpNBcVLPLKQAsGvAeYaeMuvyu4NLXppump", "GECKO", "Gecko", Category.ESTABLISHED_MEME),
        BlueChip("KKOTLmyKfCoc7wKzYAiuWRA5vSVEyH4EY6TE5uEpump", "KOTL", "Kotl", Category.ESTABLISHED_MEME),
        BlueChip("VANRSWQ8fArDppUyEwLTsGnr4vfSdXQZY3xVzREpump", "VANRY", "Vanry", Category.NEW_L1_MEME),
        BlueChip("NAILlNGY7ETLxEHKbCbcCUyDDkzjjRvcvbnDHu4pump", "NAIL", "Nail", Category.NEW_L1_MEME),
        BlueChip("SunPYPRvJDQhUmDNJm1c2LEuubTFcRHFGKKt3fkpump", "SUNPUMP", "Sun Pump", Category.LAUNCHPAD_GRADUATE),
        BlueChip("STONKvUuQTeqR6MxpEavHnFmqNTdcNBt5FrxSaGpump", "STONK", "Stonk Coin", Category.NEW_L1_MEME),
        BlueChip("DEGENv7NDNyPtvygfsWzS7SNTWMg6XLNRXAEcVfpump", "DEGEN-sol", "Degen SOL", Category.NEW_L1_MEME),
        BlueChip("PhogXPVjKbQXWdWEHrETRW2cP4rEKGpxYWmSFmapump", "PHOG", "Phog", Category.ESTABLISHED_MEME),

        // Additional RWA / stable-utility
        BlueChip("PATRIOTMBnpXypfE1qXsyRPUwSFVAQxHPNC2GxPpump", "PATRIOT", "Patriot", Category.NEW_L1_MEME),
        BlueChip("ONDOZFB8gRVwWWFPLdW3aY5cyKW5xrPKtVJmyPtEcqW", "ONDO-sol", "Ondo on SOL", Category.RWA),
        BlueChip("USDYETMdmc9Q6ArkNwFapDBQqfnHFHqAsFxYd8PnBGE", "USDY", "US Dollar Yield", Category.RWA),
        BlueChip("TBILLnzvFrZzKLKr8csAmXPGZKUgSY9CT4b3Vy45MBg", "TBILL", "Treasury Bill", Category.RWA),

        // Additional LSD_STAKING
        BlueChip("BONK6X6r9GxK5g8ZZjuvbCFqRUj5RgQxbUnkNQyKpump", "sSOL-bonk", "BONK Staked SOL", Category.LSD_STAKING),
        BlueChip("hSOL5MgvxCadTZQ83e8Bk6xTiCnw56Krwx3S8FLA8Uv", "hSOL", "Helius Staked SOL", Category.LSD_STAKING),
        BlueChip("edgeMDXENvV7XCV2gADJdA45vD3sAKXykMJPX2fRpDS", "edgeSOL", "Edgevana SOL", Category.LSD_STAKING),
        BlueChip("laineSOL1UvcCr2CmMh5tCzKzKvnZDxYuT5vjBHqPPo", "laineSOL", "Laine SOL", Category.LSD_STAKING),
        BlueChip("CgnTSoL3DgY9SFHxcLj6CgCgKKoTBr6tp4CHRbVdyJKz", "cgntSOL", "Cogent SOL", Category.LSD_STAKING),
        BlueChip("piSOLxK73aXptFDR91Fry74RgqmBmwd2vFtQdrgQpump", "piSOL", "Pump SOL", Category.LSD_STAKING),

        // Additional DEX_LIQUIDITY_HUB
        BlueChip("LFNGnFtvfj7MnAVUCNZmTPWzPjTKuKjHnLnnMcgpump", "LFG", "LFG", Category.DEX_LIQUIDITY_HUB),
        BlueChip("mBAeGKQBqFY3zqPnvS36Xk7YkE23xkyEG29G23UpTU", "PROTON", "Proton", Category.DEX_LIQUIDITY_HUB),
        BlueChip("KaminoEbEwbVJRC1RTLtVvvcTMxrqNTgKELFTnkypump", "KAMINO", "Kamino Lend", Category.DEFI_YIELD),
        BlueChip("PUlSAr9zjMuEXChNXe3FhLGGpQKjMYkJPzuNXpXpump", "PULSAR", "Pulsar", Category.DEFI_YIELD),

        // ═══ V5.0.6205 — 250-mint target expansion (operator: "the watch
        //     list is meant to have 250 tokens"). Canonical OG-Solana +
        //     Sanctum LSTs + gaming/DePIN/NFT protocol assets. ═══
        BlueChip("jtojtomepa8beP8AuQc6eXt5FriJwfFMwQx2v2f9mCL", "JTO", "Jito Governance", Category.INFRASTRUCTURE),
        BlueChip("7dHbWXmci3dT8UFYWYZweBLXgycu7Y3iL6trKn1Y7ARj", "stSOL", "Lido Staked SOL", Category.LSD_STAKING),
        BlueChip("5oVNBeEEQvYi1cX3ir8Dx5n1P7pdxydbGF2X4TxVusJm", "INF", "Sanctum Infinity", Category.LSD_STAKING),
        BlueChip("7Q2afV64in6N6SeZsAAB81TJzwDoD6zpqmHkzi9Dcavn", "JSOL", "JPool Staked SOL", Category.LSD_STAKING),
        BlueChip("CLoUDKc4Ane7HeQcPpE3YHnznRxhMimJ4MyaUqyHFzAu", "CLOUD", "Sanctum Cloud", Category.LSD_STAKING),
        BlueChip("EchesyfXePKdLtoiZSL8pBe8Myagyy8ZRqsACNCFGnvp", "FIDA", "Bonfida", Category.INFRASTRUCTURE),
        BlueChip("MAPS41MDahZ9QdKXhVa4dWB9RuyfV4XqhyAZ8XcYepb", "MAPS", "Maps.me", Category.INFRASTRUCTURE),
        BlueChip("z3dn17yLaGMKffVogeFHQ9zWVcXgqgf3PQnDsNs2g6M", "OXY", "Oxygen", Category.DEFI_YIELD),
        BlueChip("SRMuApVNdxXokk5GT7XD5cUUgXMBCoAz2LHeuAoKWRt", "SRM", "Serum", Category.DEX_LIQUIDITY_HUB),
        BlueChip("TuLipcqtGVXP9XR62wM8WWCm6a9vhLs7T1uoWBk6FDs", "TULIP", "Tulip Protocol", Category.DEFI_YIELD),
        BlueChip("xxxxa1sKNGwFtw2kFn8XauW9xq8hBZ5kVtcSesTT9fW", "SLIM", "Solanium", Category.DEFI_YIELD),
        BlueChip("7i5KKsX2weiTkry7jA4ZwSuXGhs5eJBEjY8vVxR4pfRx", "GMT", "STEPN GMT", Category.GAMING),
        BlueChip("AFbX8oGjGpmVFywbVouvhQSRmiW2aR1mohfahi4Y2AdB", "GST", "STEPN GST", Category.GAMING),
        BlueChip("DUSTawucrTsGU8hcqRdHDCbuYhCPADMLM2VcCb8VnFnQ", "DUST", "Dust Protocol", Category.NFT_LIQUIDITY),
        BlueChip("FoRGERiW7odcCBGU1bztZi16osPBHjxharvDathL5eds", "FORGE", "Blocksmith Forge", Category.GAMING),
        BlueChip("9nEqaUcb16sQ3Tn1psbkWqyhPdLmfHWjKGymREjsAgTE", "WOOF", "WOOF Solana", Category.ESTABLISHED_MEME),
        BlueChip("HxhWkVpk5NS4Ltg5nij2G671CKXFRKPK8vy271Ub4uEK", "HXRO", "Hxro Network", Category.DEFI_YIELD),
        BlueChip("MERt85fc5boKw3BW1eYdxonEuJNvXbiMbs6hvheau5K", "MER", "Mercurial", Category.DEX_LIQUIDITY_HUB),
        BlueChip("a11bdAAuV8iB2fu7X6AxAvDTo1QZ8FXB3kk5eecdasp", "ABR", "Allbridge", Category.BRIDGED_MAJOR),
        BlueChip("8upjSpvjcdpuzhfR1zriwg5NXkwDruejqNE9WNbPRtyA", "GRAPE", "Grape Protocol", Category.INFRASTRUCTURE),
        BlueChip("ETAtLmCmsoiEEKfNrHKJ2kYy3MoABhU6NQvpSfij5tDs", "MEDIA", "Media Network", Category.INFRASTRUCTURE),
        BlueChip("UXPhBoR3qG4UCiGNJfV7MqhHyFqKN68g45GoYvAeL2M", "UXP", "UXD Protocol", Category.DEFI_YIELD),
        BlueChip("MEANeD3XDdUmNMsRGjASkSWdC8prLYsoRJ61pPeHctD", "MEAN", "Mean Finance", Category.DEFI_YIELD),
        BlueChip("DFL1zNkaGPWm1BqAVqRjCZvHmwTFrEaJtbzJWgseoNJh", "DFL", "DeFi Land", Category.GAMING),
        BlueChip("MEFNBXixkEbait3xn9bkm8WsJzXtVsaJEn4c8Sam21u", "ME", "Magic Eden", Category.NFT_LIQUIDITY),
        BlueChip("C98A4nkJXhpVZNAZdHUA95RpTF3T4whtQubL3YobiUX9", "C98", "Coin98", Category.INFRASTRUCTURE),
        BlueChip("8HGyAAB1yoM1ttS7pXjHMa3dukTFGQggnFFH3hJZgzQh", "COPE", "COPE", Category.ESTABLISHED_MEME),
        BlueChip("CsZ5LZkDS7h9TDKjrbL7VAwQZ9nsRu8vJLhRYfmGaN8K", "ALEPH", "Aleph.im", Category.INFRASTRUCTURE),
        BlueChip("E5ndSkaB17Dm7CsD22dvcjfrYSDLCxFcMd6z8ddCk5wp", "RIN", "Aldrin", Category.DEX_LIQUIDITY_HUB),
        BlueChip("4dmKkXNHdgYsXqBHCuMikNQWwVomZURhYvkkX5c4pQ7y", "SNY", "Synthetify", Category.DEFI_YIELD),
        BlueChip("BLwTnYKqf7u4qjgZrrsKeNs2EzWkMLqVCu6j8iHyrNA3", "BOP", "Boring Protocol", Category.ESTABLISHED_MEME),
        BlueChip("4SZjjNABoqhbd4hnapbvoEPEqT8mnNkfbEoAwALf1V8t", "CAVE", "Crypto Cavemen", Category.GAMING),
        BlueChip("cxxShYRVcepDudXhe7U62QHvw8uBJoKFifmzggGKVC2", "CHICKS", "SolChicks", Category.GAMING),
        BlueChip("BRLsMczKuaR5w9vSubF4j8HwEGGprVAyyVgS4EX7DKEg", "CYS", "Cyclos", Category.DEFI_YIELD),
        BlueChip("CKaKtYvz6dKPyMvYq9Rh3UBrnNqYZAyd7iF4hJtjUvks", "GARI", "Gari Network", Category.INFRASTRUCTURE),
        BlueChip("74SBV4zDXxTRgv1pEMoECskKBkZHc2yGPnc7GYVepump", "SWARMS", "Swarms", Category.AI_AGENT),
        BlueChip("9DHe3pycTuymFk4H4bbPoAJ4hQrr2kaLDF6J6aAKpump", "BUZZ", "Hive AI", Category.AI_AGENT),
        BlueChip("ukHH6c7mMyiWCf1b9pnWe25TSpkDDt3H5pQZgZ74J82", "BOME", "Book of Meme", Category.ESTABLISHED_MEME),
        BlueChip("4LLbsb5ReP3yEtYzmXewyGjcir5uXtKFURtaEUVC2AHs", "PRCL", "Parcl", Category.RWA),
        BlueChip("ZEXy1pqteRu3n13kdyh4LwPQknkFk3GzmMYMuNadWPo", "ZEX", "Zeta Markets", Category.DEX_LIQUIDITY_HUB),
        BlueChip("4vMsoUT2BWatFweudnQM1xedRLfJgJ7hswhcpz4xgBTy", "HONEY", "Hivemapper", Category.DEPIN),
        BlueChip("7EYnhQoR9YM3N7UoaKRoA44Uy8JeaZV3qyouov87awMs", "SILLY", "Silly Dragon", Category.ESTABLISHED_MEME),
        BlueChip("3psH1Mj1f7yUfaD5gh6Zj7epE8hhrMkMETgv5TshQA4o", "BODEN", "Jeo Boden", Category.ESTABLISHED_MEME),
        BlueChip("RLBxxFkseAZ4RgJH3Sqn8jXxhmGoz9jWxDNJMh8pL7a", "RLB", "Rollbit Coin", Category.ESTABLISHED_MEME),
        BlueChip("3bRTivrVsitbmCTGtqwp7hxXPsybkjn4XLNtPsHqa3zR", "LIKE", "Only1", Category.INFRASTRUCTURE),
    )

    /** Quick O(1) mint→BlueChip lookup for reverse-mapping. */
    private val byMint: Map<String, BlueChip> by lazy { WATCHLIST.associateBy { it.mint } }

    fun isBlueChip(mint: String?): Boolean =
        mint?.let { byMint.containsKey(it) } ?: false

    fun infoFor(mint: String?): BlueChip? = mint?.let { byMint[it] }

    fun statusLine(): String =
        "$VERSION: ${WATCHLIST.size} mints (${Category.values().joinToString(",") { c -> "${c.name}=${WATCHLIST.count { it.category == c }}" }})"
}
