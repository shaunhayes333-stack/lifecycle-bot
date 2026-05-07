package com.lifecyclebot.engine

/**
 * V5.9.495z28 — Slippage Profiles (operator spec item 6).
 *
 * Operator: "The bot used 75% slippage on a profit-lock sell. That is too
 * high for normal profit-taking. Create separate slippage profiles."
 *
 * Single source of truth for any sell-side slippage decision. Every code
 * path that constructs a sell quote should look up its tier here instead
 * of hard-coding 7500 bps everywhere.
 *
 * Defaults (basis points; 100 = 1%):
 *   • NORMAL_PROFIT_LOCK   — 1500 bps  (15% — covers normal volatility)
 *   • CAPITAL_RECOVERY     — 2000 bps  (20% — slightly looser when
 *                                       de-risking the principal)
 *   • EMERGENCY_RUG_DRAIN  — 7500 bps  (75% — only for catastrophe /
 *                                       liquidity-pulled exits)
 *
 * Each tier exposes a broadcast ladder so the executor escalates within
 * the tier rather than jumping straight from 15% to 75%.
 */
object SellSlippageProfile {

    enum class Tier {
        NORMAL_PROFIT_LOCK,
        CAPITAL_RECOVERY,
        EMERGENCY_RUG_DRAIN,
    }

    data class Profile(
        val tier: Tier,
        val initialBps: Int,
        val ladderBps: List<Int>,   // escalation if first attempt fails to land
    )

    private val NORMAL = Profile(
        tier = Tier.NORMAL_PROFIT_LOCK,
        // V5.9.495z38 — operator-reported real-money safety bug:
        // PROFIT_LOCK was running at 75% slippage and consumed 3.6×
        // the intended quantity. Hard cap PROFIT_LOCK at 800 bps (8%).
        initialBps = 300,
        ladderBps = listOf(300, 500, 800),  // 3 → 5 → 8
    )

    private val RECOVERY = Profile(
        tier = Tier.CAPITAL_RECOVERY,
        // z38: cap CAPITAL_RECOVERY at 1500 bps (15%).
        initialBps = 500,
        ladderBps = listOf(500, 1000, 1500),  // 5 → 10 → 15
    )

    private val EMERGENCY = Profile(
        tier = Tier.EMERGENCY_RUG_DRAIN,
        initialBps = 5000,
        ladderBps = listOf(5000, 6500, 7500),  // 50 → 65 → 75
    )

    fun forTier(tier: Tier): Profile = when (tier) {
        Tier.NORMAL_PROFIT_LOCK   -> NORMAL
        Tier.CAPITAL_RECOVERY     -> RECOVERY
        Tier.EMERGENCY_RUG_DRAIN  -> EMERGENCY
    }

    /**
     * Convenience — pick the right tier from a sell `reason` string. Keeps
     * call sites declarative ('CapitalRecoveryAI' code doesn't have to
     * know the bps numbers).
     */
    fun tierForReason(reason: String): Tier {
        val r = reason.uppercase()
        return when {
            r.contains("CATASTROPHE") || r.contains("RUG") || r.contains("DRAIN_EXIT") ||
            r.contains("EMERGENCY") || r.contains("HARD_FLOOR") -> Tier.EMERGENCY_RUG_DRAIN
            r.contains("CAPITAL_RECOVERY") || r.contains("RECOVER") -> Tier.CAPITAL_RECOVERY
            r.contains("PROFIT_LOCK") || r.contains("TAKE_PROFIT") ||
            r.contains("PARTIAL") || r.contains("DRAWDOWN") -> Tier.NORMAL_PROFIT_LOCK
            else -> Tier.NORMAL_PROFIT_LOCK
        }
    }
}
