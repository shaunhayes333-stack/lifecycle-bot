package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/**
 * V5.0.8140 — runtime readings for [LaneAgeLiquidityScope8140] (the pure rule).
 *
 * Age: chain truth from CanonicalTokenBirthTime7440 (token-meta creation, pump.fun create
 * event, first pool creation — never watchlist insertion); unresolved births are requested
 * from TokenBirthHydrator7441 by that authority. Liquidity: LiquidityTruth8037 (curve
 * account, on-chain vaults, tick, token map), then Raydium's own TVL for a non-pump mint
 * (RaydiumMintPools8128, cached, never blocking). Nothing here estimates a number: an
 * unread pool stays 0 and DEFERS.
 *
 * Every refusal / deferral counts a label naming the gate and the lane.
 */
object LaneAgeLiquidityTruth8140 {

    private fun count8140(label: String) {
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
    }

    /**
     * V5.0.8144 — bounded deferral episodes. An unread pool / unresolved birth deferred on every evaluation for as long
     * as the candidate stayed watched (an unbounded loop with no outcome). Each mint now defers for at most
     * [DEFER_BUDGET_MS_8144]; past that the deferral becomes a named terminal for [TERMINAL_REST_MS_8144] (still a
     * refusal — never an approval), then a fresh bounded episode may start. Arriving data ends the episode at once.
     */
    private const val DEFER_BUDGET_MS_8144 = 3 * 60_000L
    private const val TERMINAL_REST_MS_8144 = 10 * 60_000L
    private val liqDeferral8144 = ProofDataDeferral8144(DEFER_BUDGET_MS_8144, TERMINAL_REST_MS_8144)
    private val birthDeferral8144 = ProofDataDeferral8144(DEFER_BUDGET_MS_8144, TERMINAL_REST_MS_8144)

    /** Pure: the V3 reason for an unread pool at deferral [step]. Neither reason is ZERO_LIQUIDITY (no quarantine). */
    fun v3LiquidityReason8144(step: ProofDataDeferral8144.Step8144): String =
        if (step == ProofDataDeferral8144.Step8144.TERMINAL) "LIQ_UNHYDRATED_TERMINAL_8144" else "LIQ_UNHYDRATED_DEFER_8140"

    fun deferralLine8144(): String =
        "deferrals8144 liq[terminals=${liqDeferral8144.terminals8144()} open=${liqDeferral8144.size8144()}] " +
            "birth[terminals=${birthDeferral8144.terminals8144()} open=${birthDeferral8144.size8144()}] " +
            (try { IntakeUnknownTruth8418.statusLine8418() } catch (_: Throwable) { "intake8418[unavailable]" })   // V5.0.8418

    /** Resolved chain-birth age in minutes; null while the birth is unresolved. */
    private fun chainAgeMinutes8140(ts: TokenState): Double? = try {
        com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts)
    } catch (_: Throwable) { null }

    /**
     * V5.0.8144 — a proven LOWER BOUND of the chain age (minutes) while the birth is unresolved: the oldest successful
     * signature already read from the mint's history, or the bot's own first sighting (AdmissionBirth8144). Never the
     * age itself; null when nothing proves one.
     */
    private fun provenMinAgeMinutes8144(ts: TokenState): Double? = try {
        com.lifecyclebot.engine.truth.AdmissionBirth8144.provenMinAgeMs8144(ts)?.div(60_000.0)
    } catch (_: Throwable) { null }

    /** Resolved chain-birth age in whole seconds; null while unresolved. */
    fun chainAgeSecs8140(ts: TokenState): Long? = chainAgeMinutes8140(ts)?.let { (it * 60.0).toLong() }

    /**
     * Established-lane age input: the lane's own lower-bound reading (watchlist / candle span)
     * raised to chain truth, so a chain-old token the bot just discovered is not "too new".
     */
    fun establishedAgeMinutes8140(ts: TokenState, laneMinutes: Double): Double {
        val seen = if (laneMinutes.isFinite() && laneMinutes >= 0.0) laneMinutes else null
        // V5.0.8144 — a proven lower bound of the chain age is one more lower bound (the older reading wins).
        val bound = provenMinAgeMinutes8144(ts)
        val seenOrBound = if (seen != null && bound != null) maxOf(seen, bound) else seen ?: bound
        return LaneAgeLiquidityScope8140.laneAgeMinutes8140("QUALITY", chainAgeMinutes8140(ts), seenOrBound) ?: 0.0
    }

    /** Launch-lane age input: chain truth, else [fallbackMinutes] (entry is then held by [launchLaneAgeBlocked8140]). */
    fun launchAgeMinutesOr8140(ts: TokenState, fallbackMinutes: Double): Double =
        LaneAgeLiquidityScope8140.laneAgeMinutes8140("SHITCOIN", chainAgeMinutes8140(ts), null) ?: fallbackMinutes

    /**
     * True when a launch lane must not take [ts] on age: past V3's launch window on TRUE age
     * (refuse), or true age not yet known (defer). Always false for an established lane.
     */
    fun launchLaneAgeBlocked8140(ts: TokenState, lane: String): Boolean {
        val window = try {
            com.lifecyclebot.v3.V3EngineManager.launchAgeWindowMinutes8140()
        } catch (_: Throwable) { 360.0 }
        val age = chainAgeMinutes8140(ts)
        // V5.0.8144 — unknown defers, but a proven lower bound past the window refuses (old, not unknown).
        val bound8418 = if (age == null) provenMinAgeMinutes8144(ts) else null
        val v = LaneAgeLiquidityScope8140.ageVerdictBounded8144(lane, age, bound8418, window)
        if (age != null) birthDeferral8144.resolved8144(ts.mint)
        if (!v.blocksEntry) return false
        count8140(v.label)
        // V5.0.8144 — a birth that stays unresolved past the deferral budget is a named terminal (still blocked).
        if (v.outcome == LaneAgeLiquidityScope8140.Outcome8140.DEFER &&
            birthDeferral8144.step8144(ts.mint, System.currentTimeMillis()) == ProofDataDeferral8144.Step8144.TERMINAL
        ) count8140("LANE_AGE_BIRTH_UNRESOLVED_TERMINAL_8144")
        try {
            ForensicLogger.lifecycle(
                v.label,
                // V5.0.8418 — trueAgeMin=-1 is the UNRESOLVED exact birth; a refusal then rests on the proven lower bound
                // named here (a witnessed sighting / signature / pool older than the window), never on the unknown itself.
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} trueAgeMin=${age?.toInt() ?: -1} " +
                    "provenMinAgeMin8418=${bound8418?.toInt() ?: -1} boundSource8418=${if (bound8418 == null) "-" else (try { com.lifecyclebot.engine.truth.AdmissionBirth8144.readFor8144(ts).source } catch (_: Throwable) { "?" })} " +
                    "windowMin=${window.toInt()} action=${if (v.outcome == LaneAgeLiquidityScope8140.Outcome8140.DEFER) "defer_birth_hydrating" else "refuse_past_launch_window"}",
            )
        } catch (_: Throwable) {}
        return true
    }

    /**
     * The liquidity a decision should read for [ts]: LiquidityTruth8037 first, then Raydium's
     * own pool TVL for a non-pump mint whose tick is still unread (filled into the tick, as
     * LiquidityTruth8037 does). 0.0 means unknown, never "low".
     */
    private fun laneLiquidityUsd8140(ts: TokenState): Double {
        val hydrated = try {
            com.lifecyclebot.engine.truth.LiquidityTruth8037.hydrate8037(ts)
        } catch (_: Throwable) { ts.lastLiquidityUsd }
        if (hydrated.isFinite() && hydrated > 0.0) return hydrated
        if (ts.mint.isBlank()) return 0.0
        val pump = try { com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(ts.mint) } catch (_: Throwable) { false }
        if (pump) return 0.0
        val tvl = try {
            com.lifecyclebot.network.RaydiumMintPools8128.standInLiquidity8128(
                0.0, com.lifecyclebot.network.RaydiumMintPools8128.cached8128(ts.mint),
            )
        } catch (_: Throwable) { 0.0 }
        if (!(tvl.isFinite() && tvl > 0.0)) return 0.0
        if (!(ts.lastLiquidityUsd > 0.0)) {
            HydrationReeval8343.writeTick8345(ts, tvl, "RAYDIUM_TVL_8128")  // V5.0.8345 — a first fill resumes the deferred hand-off task
            count8140("LANE_LIQ_RAYDIUM_TVL_FILLED_8140")
        }
        return tvl
    }

    /**
     * V3 trunk: hydrate first; when the pool is still unread and the token map has NOT proven
     * a true zero, defer (V3 reason LIQ_UNHYDRATED_DEFER_8140 — not ZERO_LIQUIDITY, which the
     * ShitCoin path's REJECTED_FATAL_V3 hook would quarantine). null = proceed; a proven zero
     * proceeds so EligibilityGate's ZERO_LIQUIDITY still stands.
     */
    fun v3LiquidityDeferReason8140(ts: TokenState): String? {
        return try {
            if (laneLiquidityUsd8140(ts) > 0.0) { liqDeferral8144.resolved8144(ts.mint); return null }
            val hardZero = try { TokenMapAuthority.liquidityVerdict(ts).hardZero } catch (_: Throwable) { false }
            if (hardZero) { liqDeferral8144.resolved8144(ts.mint); return null }
            // V5.0.8144 — bounded: past the budget the deferral is named terminal instead of looping on every evaluation.
            val step8144 = liqDeferral8144.step8144(ts.mint, System.currentTimeMillis())
            if (step8144 == ProofDataDeferral8144.Step8144.TERMINAL) {
                count8140("V3_LIQ_UNHYDRATED_TERMINAL_8144")
                return v3LiquidityReason8144(step8144)
            }
            count8140("V3_LIQ_UNHYDRATED_DEFER_8140")
            "LIQ_UNHYDRATED_DEFER_8140"
        } catch (_: Throwable) { null }
    }

    /**
     * A lane scorer's liquidity floor, lane-scoped and truthful: unknown -> the DEFER label;
     * a real reading below the lane's floor (LIVE: never below the exit-safe floor) -> the
     * REFUSE label with the reading; else null (the scorer continues).
     */
    fun laneLiquidityRefusal8140(lane: String, liquidityUsd: Double, laneFloorUsd: Double): String? {
        val live = try { RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        val v = LaneAgeLiquidityScope8140.liquidityVerdict8140(lane, liquidityUsd, laneFloorUsd, live)
        if (!v.blocksEntry) return null
        count8140(v.label)
        return LaneAgeLiquidityScope8140.refusalReason8140(
            v, liquidityUsd, LaneAgeLiquidityScope8140.effectiveLiquidityFloor8140(laneFloorUsd, live),
        )
    }

    /**
     * V5.0.8319 §AN_ACTIVE_CURVE_IS_EXIT_SAFE_ON_ITS_OWN_RESERVES — the $500 real-SOL floor was written for AMM pools.
     * 8316: PUMP +2,140% missed on NATIVE_LANE_LIQ_BELOW_FLOOR while the curve waited for ~2.9 SOL of real SOL, though a
     * ~0.03 SOL exit trades against the curve's 30+ SOL of virtual reserves. An ACTIVE (not graduated) pump.fun curve
     * whose reserves were observed inside the curve TTL (CurveReserves7951.realSolIfActive8046) and holding at least
     * [CURVE_EXIT_MIN_REAL_SOL_8319] real SOL is exit-safe for the launch lanes. Unknown, stale or graduated reserves are
     * not (false). Non-curve pools, graduated curves and established lanes keep their floors;
     * LaneAgeLiquidityScope8140.EXIT_SAFE_HARD_FLOOR_USD_8140 stays 500.0.
     */
    const val CURVE_EXIT_MIN_REAL_SOL_8319 = 0.5
    private val CURVE_FLOOR_LANES_8319 = setOf("SHITCOIN", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "MOONSHOT")

    fun activeCurveExitSafe8319(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        val real = if (mint.isBlank()) null else com.lifecyclebot.engine.truth.CurveReserves7951.realSolIfActive8046(mint, nowMs)
        real != null && real.isFinite() && real >= CURVE_EXIT_MIN_REAL_SOL_8319
    } catch (_: Throwable) { false }

    /** V5.0.8319 — [laneLiquidityRefusal8140] for a launch lane that knows the mint: an exit-safe active curve passes. */
    fun laneLiquidityRefusal8140(lane: String, liquidityUsd: Double, laneFloorUsd: Double, mint: String): String? {
        val l = LaneAgeLiquidityScope8140.canonical8140(lane)
        if (l in CURVE_FLOOR_LANES_8319 && activeCurveExitSafe8319(mint)) { count8140("LANE_LIQ_CURVE_EXIT_SAFE_8319_$l"); return null }
        return laneLiquidityRefusal8140(lane, liquidityUsd, laneFloorUsd)
    }
}
