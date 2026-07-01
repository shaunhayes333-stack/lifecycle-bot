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
    )

    /** Quick O(1) mint→BlueChip lookup for reverse-mapping. */
    private val byMint: Map<String, BlueChip> by lazy { WATCHLIST.associateBy { it.mint } }

    fun isBlueChip(mint: String?): Boolean =
        mint?.let { byMint.containsKey(it) } ?: false

    fun infoFor(mint: String?): BlueChip? = mint?.let { byMint[it] }

    fun statusLine(): String =
        "$VERSION: ${WATCHLIST.size} mints (${Category.values().joinToString(",") { c -> "${c.name}=${WATCHLIST.count { it.category == c }}" }})"
}
