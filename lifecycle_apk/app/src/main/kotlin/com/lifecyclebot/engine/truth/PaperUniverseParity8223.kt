package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.SafetyTier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8223 §PAPER_TRADES_THE_LIVE_UNIVERSE.
 *
 * Operator: "you need to look at why paper can win constantly... live thats useless". Paper opened positions in coins
 * LIVE can never buy: PreTradeHardGate refuses a sub-$500 pool, a proven-active mint / freeze authority, a confirmed
 * rug, a 35%+ top holder, a blacklisted or quarantined mint — paper sampled all of them, booked exits the pool could
 * not pay, and the learners (labels, lane records, setup books) were fed a universe live never trades. Every "paper
 * wins / live loses" comparison was two different markets.
 *
 * This is the SAME structural wall PreTradeHardGate applies to a live buy, read from the data already on the
 * TokenState (no chain read, no network: paper evaluates thousands of coins). Pending proofs (unknown freeze / mint
 * authority, unknown holders, stale safety) stay paper-sampled exactly as before — live defers those, it does not
 * refuse them — so paper keeps learning where live only waits. Only a wall live would hit refuses here.
 */
object PaperUniverseParity8223 {
    /** PreTradeHardGate.MIN_LIVE_LIQ_HARD_FLOOR_USD — a sub-$500 pool cannot execute any exit near its stop. */
    private const val LIVE_LIQ_HARD_FLOOR_USD_8223 = 500.0
    /** PreTradeHardGate.TOP_HOLDER_FATAL_PCT. */
    private const val TOP_HOLDER_FATAL_PCT_8223 = 35.0

    internal data class Wall8223(
        val liquidityUsd: Double,
        val safetyHardBlocked: Boolean,
        val rugcheckScore: Int?,
        val mintAuthorityDisabled: Boolean?,
        val freezeAuthorityDisabled: Boolean?,
        val topHolderPct: Double,
        val blacklisted: Boolean,
        val quarantined: Boolean,
    )

    /** Pure: the live wall this coin would hit at the buy door, or null when live could buy it (or would only defer). */
    internal fun wall8223(w: Wall8223): String? = when {
        w.blacklisted -> "EXACT_MINT_BLACKLISTED"
        w.quarantined -> "EXACT_MINT_QUARANTINED"
        w.safetyHardBlocked -> "SAFETY_HARD_BLOCK"
        w.rugcheckScore == 0 -> "RUGCHECK_CONFIRMED_RUG"
        w.mintAuthorityDisabled == false -> "MINT_AUTHORITY_ACTIVE"
        w.freezeAuthorityDisabled == false -> "FREEZE_AUTHORITY_ACTIVE"
        w.liquidityUsd.isFinite() && w.liquidityUsd > 0.0 && w.liquidityUsd < LIVE_LIQ_HARD_FLOOR_USD_8223 -> "LIQUIDITY_BELOW_EXIT_SAFE_FLOOR"
        w.topHolderPct.isFinite() && w.topHolderPct >= TOP_HOLDER_FATAL_PCT_8223 -> "TOP_HOLDER_CONCENTRATION"
        else -> null
    }

    private val refused8223 = ConcurrentHashMap<String, AtomicLong>()
    private val passed8223 = AtomicLong(0)

    /** Executor.paperBuy: the live wall for [ts] (null = paper may open). Counted per wall and per lane. */
    fun refusal8223(ts: TokenState, lane: String): String? {
        val s = ts.safety
        val topHolder = listOfNotNull(ts.topHolderPct, s.topHolderPct.takeIf { it >= 0.0 }).maxOrNull() ?: -1.0
        val wall = wall8223(Wall8223(
            liquidityUsd = ts.lastLiquidityUsd,
            safetyHardBlocked = s.tier == SafetyTier.HARD_BLOCK || s.isBlocked,
            rugcheckScore = s.rugcheckScore,
            mintAuthorityDisabled = s.mintAuthorityDisabled,
            freezeAuthorityDisabled = s.freezeAuthorityDisabled,
            topHolderPct = topHolder,
            blacklisted = try { com.lifecyclebot.engine.TokenBlacklist.isBlocked(ts.mint) } catch (_: Throwable) { false },
            quarantined = try { com.lifecyclebot.engine.QuarantineStore.isQuarantined(ts.mint) } catch (_: Throwable) { false },
        ))
        if (wall == null) { passed8223.incrementAndGet(); return null }
        refused8223.computeIfAbsent(wall) { AtomicLong() }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("PAPER_UNIVERSE_LIVE_WALL_8223_$wall")
            PipelineHealthCollector.labelInc("PAPER_UNIVERSE_LIVE_WALL_8223_LANE_${lane.uppercase().ifBlank { "PAPER" }}")
        } catch (_: Throwable) {}
        return wall
    }

    fun statusLine8223(): String =
        "paperUniverse8223[passed=${passed8223.get()} refused=" +
            refused8223.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" } + "]"
}
