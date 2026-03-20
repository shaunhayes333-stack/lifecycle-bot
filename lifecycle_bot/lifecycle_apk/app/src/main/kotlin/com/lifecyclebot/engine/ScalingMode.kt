package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig

/**
 * ScalingMode — Treasury-gated capital deployment tiers
 * ══════════════════════════════════════════════════════════════════════
 *
 * As the treasury grows through USD thresholds, the bot automatically
 * upgrades the token universe it hunts in. Each tier has:
 *
 *   - Minimum pool liquidity it will consider
 *   - Maximum market cap it will consider (to stay in mover territory)
 *   - Adjusted 4% liquidity ownership cap (same %, bigger pools = bigger positions)
 *   - Tighter safety requirements (bigger positions = stricter gates)
 *   - Scanner sources appropriate for the tier
 *
 * TIERS:
 * ──────
 * MICRO      Treasury < $500     Liq $8K+     MC $5K-$2M     Position: liq×4%
 *            Pure pump.fun / early Raydium memecoins. Small positions.
 *
 * STANDARD   Treasury $500-$5K   Liq $25K+    MC $500K-$20M  Position: liq×4%
 *            Established memecoins, pump.fun graduates. Mid positions.
 *
 * GROWTH     Treasury $5K-$25K   Liq $100K+   MC $5M-$100M   Position: liq×3%
 *            Serious Solana tokens. Raydium ecosystem. Larger positions.
 *            Safety: LP lock 90%+, top10 holders < 30%, rugcheck ≥ 80.
 *
 * SCALED     Treasury $25K-$100K Liq $500K+   MC $50M-$1B    Position: liq×2%
 *            Mid-cap Solana ecosystem tokens (JTO, BONK, WIF tier).
 *            Safety: CoinGecko listed, 24h vol > $500K, audit preferred.
 *
 * INSTITUTIONAL Treasury $100K+  Liq $2M+     MC $500M+      Position: liq×1%
 *            Blue-chip Solana (JUP, PYTH, RAY, ORCA etc).
 *            Safety: full CoinGecko profile, 30d vol history, exchange listed.
 *
 * KEY DESIGN DECISIONS:
 * ─────────────────────
 * 1. Ownership % drops as pools get bigger — at SCALED, 2% of a $2M pool is
 *    $40K per position. At INSTITUTIONAL, 1% of a $10M pool is $100K per position.
 *    The absolute dollar size grows massively while % ownership stays conservative.
 *
 * 2. Each tier has its own safety thresholds because the signals that matter
 *    change completely at scale:
 *    - Micro: mint/freeze authority, LP lock are the key checks
 *    - Growth: add LP lock 90%+ and top holder concentration
 *    - Scaled: add 24h volume floor and CoinGecko listing requirement
 *    - Institutional: add audit history and exchange listing
 *
 * 3. The strategy continues running in MICRO mode simultaneously with the
 *    higher tier. Treasury capital funds the bigger trades; pump.fun snipes
 *    keep generating daily cash flow.
 *
 * 4. Treasury gate: the bot only enters a higher tier if the treasury has
 *    already cleared the threshold — it's using house money.
 */
object ScalingMode {

    enum class Tier(
        val label: String,
        val treasuryThresholdUsd: Double,
        val minLiquidityUsd: Double,
        val maxLiquidityUsd: Double,    // avoid illiquid whales at wrong tier
        val minMcapUsd: Double,
        val maxMcapUsd: Double,
        val ownershipCapPct: Double,    // max % of pool we own
        val minRugcheckScore: Int,
        val requireLpLock90: Boolean,
        val requireTopHolder30: Boolean,
        val requireCoinGeckoListed: Boolean,
        val requireVolFloor24h: Double, // min 24h volume USD
        val scanSources: Set<String>,   // which scanner sources to use
        val icon: String,
    ) {
        MICRO(
            label                   = "Micro",
            treasuryThresholdUsd    = 0.0,
            minLiquidityUsd         = 8_000.0,
            maxLiquidityUsd         = 500_000.0,
            minMcapUsd              = 5_000.0,
            maxMcapUsd              = 5_000_000.0,
            ownershipCapPct         = 0.04,
            minRugcheckScore        = 70,
            requireLpLock90         = false,
            requireTopHolder30      = false,
            requireCoinGeckoListed  = false,
            requireVolFloor24h      = 0.0,
            scanSources             = setOf("PUMP_FUN_NEW","PUMP_FUN_GRADUATE","DEX_TRENDING","DEX_GAINERS","RAYDIUM_NEW_POOL"),
            icon                    = "🌱",
        ),
        STANDARD(
            label                   = "Standard",
            treasuryThresholdUsd    = 500.0,
            minLiquidityUsd         = 25_000.0,
            maxLiquidityUsd         = 2_000_000.0,
            minMcapUsd              = 500_000.0,
            maxMcapUsd              = 20_000_000.0,
            ownershipCapPct         = 0.04,
            minRugcheckScore        = 72,
            requireLpLock90         = false,
            requireTopHolder30      = false,
            requireCoinGeckoListed  = false,
            requireVolFloor24h      = 0.0,
            scanSources             = setOf("PUMP_FUN_GRADUATE","DEX_TRENDING","DEX_GAINERS","BIRDEYE_TRENDING"),
            icon                    = "📊",
        ),
        GROWTH(
            label                   = "Growth",
            treasuryThresholdUsd    = 5_000.0,
            minLiquidityUsd         = 100_000.0,
            maxLiquidityUsd         = 10_000_000.0,
            minMcapUsd              = 5_000_000.0,
            maxMcapUsd              = 100_000_000.0,
            ownershipCapPct         = 0.03,    // 3% — larger pools, more cautious
            minRugcheckScore        = 80,
            requireLpLock90         = true,
            requireTopHolder30      = true,
            requireCoinGeckoListed  = false,
            requireVolFloor24h      = 50_000.0,
            scanSources             = setOf("DEX_TRENDING","BIRDEYE_TRENDING","COINGECKO_TRENDING","NARRATIVE_SCAN"),
            icon                    = "🚀",
        ),
        SCALED(
            label                   = "Scaled",
            treasuryThresholdUsd    = 25_000.0,
            minLiquidityUsd         = 500_000.0,
            maxLiquidityUsd         = 50_000_000.0,
            minMcapUsd              = 50_000_000.0,
            maxMcapUsd              = 1_000_000_000.0,
            ownershipCapPct         = 0.02,    // 2% — mid-cap territory
            minRugcheckScore        = 85,
            requireLpLock90         = true,
            requireTopHolder30      = true,
            requireCoinGeckoListed  = true,
            requireVolFloor24h      = 500_000.0,
            scanSources             = setOf("COINGECKO_TRENDING","BIRDEYE_TRENDING","DEX_TRENDING"),
            icon                    = "📈",
        ),
        INSTITUTIONAL(
            label                   = "Institutional",
            treasuryThresholdUsd    = 100_000.0,
            minLiquidityUsd         = 2_000_000.0,
            maxLiquidityUsd         = Double.MAX_VALUE,
            minMcapUsd              = 500_000_000.0,
            maxMcapUsd              = Double.MAX_VALUE,
            ownershipCapPct         = 0.01,    // 1% — blue-chip, must be able to exit
            minRugcheckScore        = 90,
            requireLpLock90         = true,
            requireTopHolder30      = true,
            requireCoinGeckoListed  = true,
            requireVolFloor24h      = 5_000_000.0,
            scanSources             = setOf("COINGECKO_TRENDING","BIRDEYE_TRENDING"),
            icon                    = "🏛",
        );

        /** Max position in SOL = ownership cap × pool liquidity / SOL price */
        fun maxPositionSol(liquidityUsd: Double, solPriceUsd: Double): Double {
            if (liquidityUsd <= 0 || solPriceUsd <= 0) return 20.0
            return (liquidityUsd * ownershipCapPct) / solPriceUsd
        }

        /** Is this token's liquidity within the valid range for this tier? */
        fun liquidityInRange(liquidityUsd: Double): Boolean =
            liquidityUsd >= minLiquidityUsd && liquidityUsd <= maxLiquidityUsd

        /** Is this token's market cap within the valid range for this tier? */
        fun mcapInRange(mcapUsd: Double): Boolean =
            mcapUsd >= minMcapUsd && mcapUsd <= maxMcapUsd
    }

    /**
     * Determine which tier a token belongs to based on its liquidity + mcap.
     * Returns the HIGHEST tier the token qualifies for — a $500M mcap token
     * won't be evaluated as MICRO even if treasury hasn't unlocked INSTITUTIONAL.
     * Used for filtering, not sizing — sizing always uses the active tier.
     */
    fun tierForToken(liquidityUsd: Double, mcapUsd: Double): Tier {
        return Tier.values().reversed().firstOrNull { tier ->
            tier.liquidityInRange(liquidityUsd) && (mcapUsd <= 0 || tier.mcapInRange(mcapUsd))
        } ?: Tier.MICRO
    }

    /**
     * Determine the ACTIVE scaling tier based on treasury USD value.
     * This is what gates position sizing and scanner configuration.
     * Treasury is used because it's profit — we only scale with locked gains.
     */
    fun activeTier(treasuryUsd: Double): Tier {
        return Tier.values().reversed().firstOrNull { it.treasuryThresholdUsd <= treasuryUsd }
            ?: Tier.MICRO
    }

    /**
     * Returns ALL tiers currently active (treasury qualifies).
     * The bot runs multiple tiers simultaneously — micro snipes continue
     * while the treasury funds growth-tier positions.
     */
    fun activeTiers(treasuryUsd: Double): List<Tier> =
        Tier.values().filter { it.treasuryThresholdUsd <= treasuryUsd }

    /**
     * Max position for a specific token given the current treasury.
     * Picks the highest active tier whose range fits the token.
     */
    fun maxPositionForToken(
        liquidityUsd: Double,
        mcapUsd: Double,
        treasuryUsd: Double,
        solPriceUsd: Double,
    ): Pair<Tier, Double> {
        val active   = activeTiers(treasuryUsd)
        val bestTier = active.reversed().firstOrNull { tier ->
            tier.liquidityInRange(liquidityUsd) &&
            (mcapUsd <= 0 || tier.mcapInRange(mcapUsd))
        } ?: Tier.MICRO
        return bestTier to bestTier.maxPositionSol(liquidityUsd, solPriceUsd)
    }
}
