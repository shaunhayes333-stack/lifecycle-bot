package com.lifecyclebot.engine

/**
 * V5.0.8140 §AGE_AND_LIQUIDITY_ARE_LANE_RULES.
 *
 * Owner: "we reject a lot of tokens first on age and liquidity. again this isnt just a
 * pumpfun bot". The bot runs launch lanes (SHITCOIN, PROJECT_SNIPER, EXPRESS, MANIPULATED —
 * fresh pump.fun-style launches) and established-token lanes (QUALITY, BLUECHIP, DIP_HUNTER,
 * TREASURY, CORE/V3_CORE, CYCLIC, CASHGEN, and MOONSHOT, which rides mid-cap runners of any
 * age). Gates written for launches were refusing established tokens before the right lane
 * ever saw them:
 *
 *  - V3 EligibilityGate refused every token older than its launch window (live 6 h) as
 *    TOO_OLD before scoring, so CORE could never buy a 3-day-old token with $200k of pool
 *    and no lane got a fresh V3 score for it.
 *  - Lane scorers read the raw tick liquidity, which is 0 until a provider answers, and
 *    refused "LIQ_TOO_LOW: $0" — an unread number reported as a shallow pool.
 *  - QUALITY / DIP_HUNTER / SHITCOIN read watchlist first-seen time as token age: a chain-old
 *    token the bot just discovered was "too new" for QUALITY and "fresh" for SHITCOIN.
 *
 * This object is the pure rule (no Android, no I/O); [LaneAgeLiquidityTruth8140] feeds it the
 * runtime readings. Hard safety is not here and is unchanged: freeze / hostile Token-2022 /
 * rug / sellability, TRUE_ZERO_LIQUIDITY, and PreTradeHardGate's live exit-safe floor
 * (MIN_LIVE_LIQ_HARD_FLOOR_USD, mirrored by [EXIT_SAFE_HARD_FLOOR_USD_8140] and never lowered).
 */
object LaneAgeLiquidityScope8140 {

    enum class Outcome8140 { PASS, DEFER, REFUSE }

    /** [label] names the gate and the lane, so the funnel shows who said no and why. */
    data class Verdict8140(val outcome: Outcome8140, val label: String) {
        val blocksEntry: Boolean get() = outcome != Outcome8140.PASS
    }

    /** PreTradeHardGate.MIN_LIVE_LIQ_HARD_FLOOR_USD (private there): no live lane floor sits below it. */
    const val EXIT_SAFE_HARD_FLOOR_USD_8140 = 500.0

    /**
     * The lowest established-lane liquidity floor (MOONSHOT cold start $2k; QUALITY $3k;
     * BLUECHIP $7k; DIP_HUNTER / TREASURY $10k). A token whose REAL observed pool is at least
     * this deep is a candidate for some established lane, so a launch-era age window must not
     * refuse it at the V3 trunk.
     */
    const val ESTABLISHED_SCOPE_MIN_LIQ_USD_8140 = 2_000.0

    private val LAUNCH_LANES_8140 = setOf(
        "SHITCOIN", "PROJECT_SNIPER", "SNIPER", "EXPRESS", "SHITCOIN_EXPRESS", "SHITCOINEXPRESS",
        "MANIPULATED", "MANIP",
    )

    fun canonical8140(lane: String): String =
        lane.trim().uppercase().replace('-', '_').replace(' ', '_').ifBlank { "UNKNOWN" }

    /** Age is a negative only for launch lanes; every other lane (incl. an unknown one) is not refused on age. */
    fun isLaunchLane8140(lane: String): Boolean = canonical8140(lane) in LAUNCH_LANES_8140

    /**
     * The age a lane should read. A launch lane reads TRUE chain age (mint birth / pair
     * creation) only — watchlist first-seen is when WE saw it, and a chain-old token we just
     * discovered is not a launch; unknown stays unknown (null). An established lane reads the
     * older of the two (both are lower bounds of the real age; chain truth wins when known).
     */
    fun laneAgeMinutes8140(lane: String, chainAgeMinutes: Double?, watchlistAgeMinutes: Double?): Double? {
        val chain = chainAgeMinutes?.takeIf { it.isFinite() && it >= 0.0 }
        if (isLaunchLane8140(lane)) return chain
        val seen = watchlistAgeMinutes?.takeIf { it.isFinite() && it >= 0.0 }
        return if (chain != null && seen != null) maxOf(chain, seen) else chain ?: seen
    }

    /**
     * Lane-scoped age verdict. Established lanes always PASS (freshness may still be a
     * scoring feature). A launch lane DEFERS while its true age is unknown (unknown is never
     * fresh) and REFUSES past its launch window.
     */
    fun ageVerdict8140(lane: String, trueAgeMinutes: Double?, launchWindowMinutes: Double): Verdict8140 {
        val l = canonical8140(lane)
        if (!isLaunchLane8140(l)) return Verdict8140(Outcome8140.PASS, "LANE_AGE_NOT_A_NEGATIVE_8140_$l")
        val age = trueAgeMinutes?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return Verdict8140(Outcome8140.DEFER, "LANE_AGE_UNKNOWN_DEFER_8140_$l")
        if (launchWindowMinutes.isFinite() && launchWindowMinutes > 0.0 && age > launchWindowMinutes) {
            return Verdict8140(Outcome8140.REFUSE, "LANE_AGE_PAST_LAUNCH_WINDOW_8140_$l")
        }
        return Verdict8140(Outcome8140.PASS, "LANE_AGE_IN_LAUNCH_WINDOW_8140_$l")
    }

    /** The floor a lane refuses below: its own floor; in LIVE never below the exit-safe hard floor. */
    fun effectiveLiquidityFloor8140(laneFloorUsd: Double, live: Boolean): Double {
        val own = if (laneFloorUsd.isFinite() && laneFloorUsd > 0.0) laneFloorUsd else 0.0
        return if (live) maxOf(own, EXIT_SAFE_HARD_FLOOR_USD_8140) else own
    }

    /**
     * Lane-scoped liquidity verdict on the OBSERVED pool. Unknown (null / 0 / non-finite —
     * no provider answered, or a non-curve token not read from a curve) DEFERS for hydration;
     * it is never "low" and never replaced by an estimate. Only a real reading below the
     * lane's floor REFUSES.
     */
    fun liquidityVerdict8140(lane: String, observedLiquidityUsd: Double?, laneFloorUsd: Double, live: Boolean): Verdict8140 {
        val l = canonical8140(lane)
        val liq = observedLiquidityUsd?.takeIf { it.isFinite() && it > 0.0 }
            ?: return Verdict8140(Outcome8140.DEFER, "LANE_LIQ_UNHYDRATED_DEFER_8140_$l")
        if (liq < effectiveLiquidityFloor8140(laneFloorUsd, live)) {
            return Verdict8140(Outcome8140.REFUSE, "LANE_LIQ_BELOW_FLOOR_8140_$l")
        }
        return Verdict8140(Outcome8140.PASS, "LANE_LIQ_OK_8140_$l")
    }

    /** The refusal text a lane scorer returns: the label, plus the reading and floor for a real refusal. */
    fun refusalReason8140(v: Verdict8140, observedLiquidityUsd: Double, floorUsd: Double): String? = when (v.outcome) {
        Outcome8140.PASS -> null
        Outcome8140.DEFER -> v.label
        Outcome8140.REFUSE -> "${v.label}: \$${observedLiquidityUsd.toInt()} < \$${floorUsd.toInt()}"
    }

    /**
     * V3 EligibilityGate's age window, lane-scoped. V3 cannot know which lane will take a
     * token, so the launch window refuses only a token no established lane could trade: older
     * than the window AND without a real pool of [ESTABLISHED_SCOPE_MIN_LIQ_USD_8140]. Deeper
     * old tokens are scored (CORE / QUALITY / BLUECHIP / DIP_HUNTER / TREASURY / MOONSHOT need
     * that score); SHITCOIN applies the same window on true age itself (ageVerdict8140).
     */
    fun v3AgeWindowRefuses8140(ageMinutes: Double, windowMinutes: Double, observedLiquidityUsd: Double): Boolean {
        if (!(ageMinutes > windowMinutes)) return false
        return !(observedLiquidityUsd.isFinite() && observedLiquidityUsd >= ESTABLISHED_SCOPE_MIN_LIQ_USD_8140)
    }
}
