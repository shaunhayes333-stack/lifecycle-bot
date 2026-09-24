package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7289 §A PRICE CANNOT CORROBORATE ITSELF.
 *
 * Operator 5.0.7288, paper, 59 minutes:
 *
 *   CASH 27.30  OPEN COST 16.31  OPEN MARKET VALUE 259.79
 *   UNREALIZED +243.63  TOTAL EQUITY 287.09
 *   STALE_PRICE_QUARANTINED gainMultiple=2689x / 4720x / 4898x / 28051x
 *   MARK_BASIS_RECONCILED_7017 WOTF entryMcap=48126 curMcap=125528525 move=+260730%
 *   MCAP_REFRESHED_FROM_STACK_7269 = 28504
 *   HERO_RUNNER_CORROBORATED_BY_MCAP_7060 = 754
 *
 * The exit path refused those marks as absurd and would not trade on them;
 * equity counted them at full value. The 6604 clamp holds a >100x mark at
 * cost unless the market cap corroborates it (7060), and 7269 rebuilds the
 * cap as price × on-chain supply. A cap computed from the price agrees with
 * that price by construction, so every wrong tick that 7269 re-capped came
 * back "corroborated" and walked through the clamp. 7273 only rejects a
 * rebuild whose ratio DISAGREES with the price ratio — the circular case is
 * exactly the one that agrees.
 *
 * This remembers the cap value 7269 wrote for a mint. While ts.lastMcap still
 * holds that value, the cap is the price restated, not a second witness.
 * Any independent writer (a DexScreener, pump.fun or Birdeye payload) replaces
 * the value and the cap becomes evidence again. Nothing here changes a mark,
 * a price or an accounting figure; it only answers whether a cap is derived.
 */
object DerivedMarketCap7289 {
    private val derived = ConcurrentHashMap<String, Double>()

    fun onRebuiltFromPrice(mint: String, cap: Double) {
        if (mint.isBlank() || !cap.isFinite() || cap <= 0.0) return
        derived[mint] = cap
    }

    /** True while [currentCap] is still the value rebuilt from price for [mint]. */
    fun isDerived(mint: String, currentCap: Double): Boolean {
        val d = derived[mint] ?: return false
        if (!currentCap.isFinite() || currentCap <= 0.0) return false
        val same = kotlin.math.abs(d - currentCap) <= d * 1e-9
        if (!same) derived.remove(mint, d)
        return same
    }
}
