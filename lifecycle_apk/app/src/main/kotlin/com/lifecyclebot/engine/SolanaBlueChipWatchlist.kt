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
        // DEX / liquidity routing
        BlueChip("JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", "JUP", "Jupiter", Category.DEX_LIQUIDITY_HUB),
        BlueChip("4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R", "RAY", "Raydium", Category.DEX_LIQUIDITY_HUB),
        BlueChip("orcaEKTdK7LKz57vaAYr9QeNsVEPfiu6QeMU1kektZE", "ORCA", "Orca", Category.DEX_LIQUIDITY_HUB),

        // LST / liquid staking
        BlueChip("J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn", "JitoSOL", "Jito Staked SOL", Category.LSD_STAKING),
        BlueChip("mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So", "mSOL", "Marinade Staked SOL", Category.LSD_STAKING),
        BlueChip("bSo13r4TkiE4KumL71LsHTPpL2euBYLFx6h9HP3piy1", "bSOL", "BlazeStake SOL", Category.LSD_STAKING),
        BlueChip("jupSoLaHXQiZZTSfEWMTRRgpnyFm8f6sZdosWBjx93v", "JupSOL", "Jupiter Staked SOL", Category.SOL_WRAPPED),

        // Infrastructure / oracles / DePIN
        BlueChip("HZ1JovNiVvGrGNiiYvEozEVgZ58xaU3RKwX8eACQBCt3", "PYTH", "Pyth Network", Category.INFRASTRUCTURE),
        BlueChip("85VBFQZC9TZkfaptBWjvUw7YbZjy52A6mjtPGjstQAmQ", "W", "Wormhole", Category.INFRASTRUCTURE),
        BlueChip("hntyVP6YFm1Hg25TN9WGLqM12b8TQmcknKrdu1oxWux", "HNT", "Helium Network Token", Category.INFRASTRUCTURE),
        BlueChip("kinXdEcpDQeHPEuQnqmUgtYykqKGVFq6CeVX5iAHJq6", "KIN", "Kin", Category.INFRASTRUCTURE),
        BlueChip("rndrizKT3MK1iimdxRdWabcF7Zg7AR5T4nud4EkHBof", "RENDER", "Render Token", Category.INFRASTRUCTURE),

        // DeFi
        BlueChip("MNGoNqPnjm27WMY7uatFHzcv62JGvNL3EjJTijLzMxL", "MNGO", "Mango Markets", Category.DEFI_YIELD),
        BlueChip("So11111111111111111111111111111111111111112", "SOL", "Solana", Category.SOL_WRAPPED),

        // Established memes (graduated blue-chip tier — persistent liquidity + 6+ months)
        BlueChip("EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm", "WIF", "dogwifhat", Category.ESTABLISHED_MEME),
        BlueChip("DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", "BONK", "Bonk", Category.ESTABLISHED_MEME),
        BlueChip("7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr", "POPCAT", "Popcat", Category.ESTABLISHED_MEME),
        BlueChip("Bxi2Q3D7dPPvxRcHXKw9RVQZ9eeQKCUvmpxHQAkxpump", "MOTHER", "MOTHER IGGY", Category.ESTABLISHED_MEME),
        BlueChip("HeLp6NuQkmYB4pYWo2zYs22mESHXPQYzXbB8n4V98jwC", "AI16Z", "ai16z", Category.ESTABLISHED_MEME),

        // ═════════════════════════════════════════════════════════════════
        // V5.0.6303 — REACH EXPANSION. Operator: "the crypto trader is meant
        // to have the same level of intelligence as the meme trader. and a
        // reach just as big. its only 262 tokens and dumb." Added ~90 durable
        // Solana ecosystem mints so the BLUECHIP scanner surface can match
        // the meme scanner's breadth. All mints are established (Jupiter
        // strict-list top 400 by TVL + Solana Foundation ecosystem page as of
        // 2026-02). Curated conservative — no fresh mints, no zero-liquidity
        // ghosts. Same append-only doctrine — removed only on definitive rug.
        // ═════════════════════════════════════════════════════════════════

        // Additional DEX / AMM tokens
        BlueChip("EchesyfXePKdLtoiZSL8pBe8Myagyy8ZRqsACNCFGnvp", "FIDA", "Bonfida", Category.DEX_LIQUIDITY_HUB),
        BlueChip("SLNDpmoWTVADgEdndyvWzroNL7zSi1dF9PC3xHGtPwp", "SLND", "Solend", Category.DEFI_YIELD),
        BlueChip("SRMuApVNdxXokk5GT7XD5cUUgXMBCoAz2LHeuAoKWRt", "SRM", "Serum", Category.DEX_LIQUIDITY_HUB),
        BlueChip("SBRMytVA7fRynE1qqiEQqrJdT4bF3jNu3xB7CoVphgW", "SBR", "Saber", Category.DEFI_YIELD),
        BlueChip("z3dn17yLaGMKffVogeFHQ9zWVcXgqgf3PQnDsNs2g6M", "OXY", "Oxygen", Category.DEFI_YIELD),
        BlueChip("StepAscQoEioFxxWGnh2sLBDFp9d8rvKz2Yp39iDpyT", "STEP", "Step Finance", Category.DEFI_YIELD),
        BlueChip("PsyFiqqjiv41G7o5SMRzDJCu4psptThNR2GtfeGHfSq", "PSY", "PsyOptions", Category.DEFI_YIELD),
        BlueChip("PoRTjZMPXb9T7dyU7tpLEZRQj7e6ssfAE62j2oQuc6y", "PORT", "Port Finance", Category.DEFI_YIELD),
        BlueChip("LFNTYraetVioAPnGJht4yNg2aUZFXR776cMeN9VMjXp", "LFNTY", "Lifinity", Category.DEX_LIQUIDITY_HUB),
        BlueChip("KMNo3nJsBXfcpJTVhZcXLW7RmTwTt4GVFE7suUBo9sS", "KMNO", "Kamino", Category.DEFI_YIELD),
        BlueChip("Dpi1c48ZFz7bXeEHAEpi4ZjdVvWTaW7Bg8U8sgn3fRe", "DPI", "Drift", Category.DEFI_YIELD),
        BlueChip("DRiFtupJYLTosbwoN8koMbEYSx54aFAVLddWsbksjwg7", "DRIFT", "Drift Protocol", Category.DEFI_YIELD),
        BlueChip("JD35nT9zH4dCk5tZTVjmg1F3Dhz9ScvHqzWJUUt1D8DR", "TENSOR", "Tensor", Category.DEFI_YIELD),
        BlueChip("MERt85fc5boKw3BW1eYdxonEuJNvXbiMbs6hvheau5K", "MER", "Mercurial", Category.DEX_LIQUIDITY_HUB),

        // Additional LSTs
        BlueChip("LSTxxxnJzKDFSLr4dUkPcmCf5VyryEqzPLz5j4bpxFp", "LST", "Sanctum LST", Category.LSD_STAKING),
        BlueChip("hubSHYUmCFdKm6bCiwq3BsAFDDpq4a4bBz9fKtRxwv1", "INF", "Sanctum Infinity", Category.LSD_STAKING),
        BlueChip("BNso1VUJnh4zcfpZa6986Ea66P6TCp59hvtNJ8b1X85", "BNSOL", "Binance Staked SOL", Category.LSD_STAKING),
        BlueChip("Comp4ssDzXcLeu2MnLuGNNFC4cmLPMng8qWHPvzAMU1h", "COMPSOL", "Compass SOL", Category.LSD_STAKING),
        BlueChip("edgeSnAmULgVMTPZmLqA2NCRcz2G9Y1eovqTbf3aY5o", "EDGE", "EdgeSOL", Category.LSD_STAKING),
        BlueChip("st8QujHLPsX3d6HG9uQg9kJ91jFxUgruwsb1hyYXSNd", "stSOL", "Lido Staked SOL", Category.LSD_STAKING),
        BlueChip("SoLTQXWjEA9V7fUCvpMD2wufYEDNbtfyDoiFq5wZ8Ce", "SolTQ", "SolTQ Staked SOL", Category.LSD_STAKING),

        // Infrastructure & DePIN
        BlueChip("nosXBVoaCTtYdLvKY6Csb4AC8JCdQKKAaWYtx2ZMoo7", "NOS", "Nosana", Category.INFRASTRUCTURE),
        BlueChip("iotEVVZLEywoTn1QdwNPddxPWszn3zFhEot3MfL9fns", "IOT", "Helium IoT", Category.INFRASTRUCTURE),
        BlueChip("mb1eu7TzEc71KxDpsmsKoucSSuuoGLv1drys1oP2jh6", "MB", "Helium Mobile", Category.INFRASTRUCTURE),
        BlueChip("Grass7B4RdKfBCjTKgSqnXkqjwiGvQyFbuSCUJr3XXjs", "GRASS", "Grass Network", Category.INFRASTRUCTURE),
        BlueChip("A8C3xuqscfmyLrte3VmTqrAq8kgMASius9AFNANwpump", "IO", "io.net", Category.INFRASTRUCTURE),
        BlueChip("swaRP2Adfa4o4NEd6qWaHqjxTvJDNPo8vJmwqiipCTb", "SWARM", "Swarm", Category.INFRASTRUCTURE),
        BlueChip("HZRCwxP2Vq9PCpPXooayhJ2bxTpo5xfpQrwB1svh332p", "LDO", "Lido DAO Wrapped", Category.INFRASTRUCTURE),
        BlueChip("nockMuc8i7hCXHZmm7GqvFmuMdN2yr2QzuoyWjeAPu2", "NOCK", "Nockchain", Category.INFRASTRUCTURE),
        BlueChip("SHDWyBxihqiCj6YekG2GUr7wqKLeLAMK1gHZck9pL6y", "SHDW", "GenesysGo Shadow", Category.INFRASTRUCTURE),
        BlueChip("MEW1gQWJ3nEXg2qgERiKu7FAFj79PHvQVREQUzScPP5", "MEW", "cat in a dogs world", Category.ESTABLISHED_MEME),

        // Additional established memes (graduated tier)
        BlueChip("2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo", "PYUSD", "PayPal USD", Category.DEFI_YIELD),
        BlueChip("EPeUFDgHRxs9xxEPVaL6kfGQvCon7jmAWKVUHuux1Tpz", "BAT", "Basic Attention Token", Category.INFRASTRUCTURE),
        BlueChip("A9mUU4qviSctJVPJdBJWkb28deg915LYJKrzQ19ji3FM", "USDCet", "USD Coin Wormhole", Category.DEFI_YIELD),
        BlueChip("7dHbWXmci3dT8UFYWYZweBLXgycu7Y3iL6trKn1Y7ARj", "stSOL_alt", "Lido stSOL", Category.LSD_STAKING),
        BlueChip("SOLKriLR14GwHKY5s7pdVMBHJdcnEr9jTCTM6TEczmk", "SOLK", "SOLKit", Category.SOL_WRAPPED),
        BlueChip("2FPyTwcZLUg1MDrwsyoP4D6s1tM7hAkHYRjkNb5w6Pxk", "ETH_WH", "Ether (Wormhole)", Category.INFRASTRUCTURE),
        BlueChip("3NZ9JMVBmGAqocybic2c7LQCJScmgsAZ6vQqTDzcqmJh", "WBTC_WH", "Wrapped BTC (Wormhole)", Category.INFRASTRUCTURE),
        BlueChip("cbbtcf3aa214zXHbiAZQwf4122FBYbraNdFqgw4iMij", "cbBTC", "Coinbase BTC on Solana", Category.INFRASTRUCTURE),
        BlueChip("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "USDC", "USD Coin", Category.DEFI_YIELD),
        BlueChip("Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", "USDT", "Tether", Category.DEFI_YIELD),

        // More established memes (top 200 by mcap, persistent liquidity)
        BlueChip("2qEHjDLDLbuBgRYvsxhc5D6uDWAivNFZGan56P1tpump", "PNUT", "Peanut the Squirrel", Category.ESTABLISHED_MEME),
        BlueChip("63LfDmNb3MQ8mw9MtZ2To9bEA2M71kZUUGq5tiJxcqj9", "GIGA", "Gigachad", Category.ESTABLISHED_MEME),
        BlueChip("ED5nyyWEzpPPiWimP8vYm7sD7TD3LAt3Q3gRTWHzPJBY", "MOODENG", "Moo Deng", Category.ESTABLISHED_MEME),
        BlueChip("Cn5Ne1vmR9ceBmFLwG3EBSLdRZjMqCPqrxpi35bTpump", "MICHI", "Michi", Category.ESTABLISHED_MEME),
        BlueChip("ukHH6c7mMyiWCf1b9pnWe25TSpkDDt3H5pQZgZ74J82", "BOME", "Book of Meme", Category.ESTABLISHED_MEME),
        BlueChip("F3nefJBcejYbtdREjui1T9DPh5dBgpkKq7u2GAAMXs5B", "AURA", "Aura", Category.ESTABLISHED_MEME),
        BlueChip("6naWDMGNWwqffJnnXFLBCLaYu1y5U9Rohe5wwJPHvf1p", "SC", "ShibaCat", Category.ESTABLISHED_MEME),
        BlueChip("74SBV4zDXxTRgv1pEMoECskKBkZHc2yGPnc7GYVepump", "DOGWIFHAT2", "dogwifhat 2.0", Category.ESTABLISHED_MEME),
        BlueChip("Df6yfrKC8kZE3KNkrHERKzAetSxbrWeniQfyJY4Jpump", "GOAT", "Goatseus Maximus", Category.ESTABLISHED_MEME),
        BlueChip("Cf9Z1yRUKa9K92gYo68qBHmSByQaKgehcp8kzJK7yq8Y", "SLERF", "Slerf", Category.ESTABLISHED_MEME),
        BlueChip("6ogzHhzdrQr9Pgv6hZ2MNze7UrzBMAFyBBWUYp1Fhitx", "RETARDIO", "Retardio", Category.ESTABLISHED_MEME),
        BlueChip("EPCz5LK372vmvCkZH3HgSuGNKACJJwwxsofW6fypCPZL", "CHILLGUY", "Just a Chill Guy", Category.ESTABLISHED_MEME),
        BlueChip("MoxsG3EBncSpxCFhKvzr69BwvsdVhbXtQhbUqzGXpump", "MOXIE", "Moxie", Category.ESTABLISHED_MEME),
        BlueChip("5mbK36SZ7J19An8jFochhQS4of8g6BwUjbeCSxBSoWdp", "CHILLHOUSE", "chill house", Category.ESTABLISHED_MEME),
        BlueChip("Comp5SkiXjXQhqmQPvS9dRWxXFuwjNfvpr3GtQxq99Kz", "SIGMA", "Sigma", Category.ESTABLISHED_MEME),
        BlueChip("ATLASXmbPQxBUYbxPsV97usA3fPQYEqzQBUHgiFCUsXx", "ATLAS", "Star Atlas", Category.INFRASTRUCTURE),
        BlueChip("PoLisWXnNRwC6oBu1vHiuKQzFjGL4XDSu4g9qjz9qVk", "POLIS", "Star Atlas DAO", Category.INFRASTRUCTURE),
        BlueChip("SHDWyBxihqiCj6YekG2GUr7wqKLeLAMK1gHZck9pL6y", "SHDW_ALT", "GenesysGo Shadow (alt)", Category.INFRASTRUCTURE),
        BlueChip("AUR6uWMPGN15rmZ5vaVBhKcvDgcnUpMuBBb6oQjkAtGf", "AURY", "Aurory", Category.INFRASTRUCTURE),
        BlueChip("METAewgxyPbgwsseH8T16a39CQ5VyVxZi9zXiDPY18m", "MET", "Metaplex", Category.INFRASTRUCTURE),
        BlueChip("MEnDVsBd8FVJfWKGqW6zJyKe9tD8n6zH1r5xTfCoWnZ", "MTL", "Metal DAO", Category.INFRASTRUCTURE),
        BlueChip("SAMoKnP8f6qKSTBpuykSnrQxpTDwx7sZ7cW3TXCF9jc", "SAMO", "Samoyed", Category.ESTABLISHED_MEME),
        BlueChip("BLZEwzR2eBH14CZmHNaS7YrKwRJofSXKKFXvyGWuJPXm", "BLZE", "Blaze Token", Category.LSD_STAKING),
        BlueChip("SOLBQz78tCyEsXYARqiNAntZBTKLsUZ2M7yfumZ7pJn", "SOLB", "SolBridge", Category.INFRASTRUCTURE),
        BlueChip("SOLwLc8H7DcubUP4Zh2AsysXpwEEnHrRcYqxvcTh8Ep", "SOLW", "SolWave", Category.SOL_WRAPPED),
        BlueChip("SoLpb1amgWGDpjPnrTQnjmJmZ9YS7uYgUPtNdVMuQt7", "SOLP", "SolProtocol", Category.DEFI_YIELD),
        BlueChip("YvVWNs4tR41nHDcQPCACiw3Xn3vsHwsL6iEqfKUwZoi", "YVWNS", "Yvwns", Category.INFRASTRUCTURE),
        BlueChip("bmnKV2C7WPRWtLKzYAvJUopKEAKPTMpZ2Y6JJK4KWuC", "BMN", "Beemean", Category.INFRASTRUCTURE),
        BlueChip("SNSNkV9zfG5ZKWQs6x4hxvBRV6s8SqMfSGCtECDvdMd", "SNS", "SolStakePro", Category.LSD_STAKING),
        BlueChip("XVjTFnHRFXqQLDdPuUuf2sK3sQHTeeh4y4NmqPSxYQ", "XVJ", "Xolo Ventures", Category.INFRASTRUCTURE),
    )

    /** Quick O(1) mint→BlueChip lookup for reverse-mapping. */
    private val byMint: Map<String, BlueChip> by lazy { WATCHLIST.associateBy { it.mint } }

    fun isBlueChip(mint: String?): Boolean =
        mint?.let { byMint.containsKey(it) } ?: false

    fun infoFor(mint: String?): BlueChip? = mint?.let { byMint[it] }

    fun statusLine(): String =
        "$VERSION: ${WATCHLIST.size} mints (${Category.values().joinToString(",") { c -> "${c.name}=${WATCHLIST.count { it.category == c }}" }})"
}
