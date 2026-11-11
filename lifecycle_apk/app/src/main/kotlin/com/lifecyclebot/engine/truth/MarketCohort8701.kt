package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8701 §THE_WHOLE_NETWORK — owner: "it's meant to be the entire sol network, not just pump fun".
 *
 * The 8689 edge dataset (8,929 decisions, one per coin per 15 minutes, net of cost, both halves of the run):
 *   pump.fun-minted coins, every pool size and age     -4 .. -20 %   (6,259 decisions; 73 % of everything the bot judged)
 *   other Solana coins, pool $5k - $20k                +8.8 % / +6.1 % (267 decisions; a few large winners carry it)
 *   other Solana coins, pool under $5k                 -8 .. -11 %
 *   other Solana coins, pool $100k and over            -2 .. -4 %    (the median coin does not move; the cost is the loss)
 * No lane, source or score separated winners from losers in that data; where the coin was minted and how deep its pool is did.
 *
 * Nothing here is trusted on one dataset. Every Solana coin a lane judges is also filed, once per 15 minutes, under its
 * origin x pool-depth cohort (a pseudo-lane on ForwardReturnLabeler7731: executable marks, net of cost). A cohort becomes a
 * LIVE edge only when its own record is proven positive on the bar every plan cohort is held to
 * (LiveEdgeGate7877.cohortProvenPositive7948: 100+ labels, mean minus one standard error above zero, not outlier-suspect).
 * Nothing here refuses a coin.
 */
object MarketCohort8701 {
    private const val PREFIX_8701 = "ORIGIN8701_"
    private const val REFILE_MS_8701 = 15L * 60_000L
    private val ORIGINS_8701 = listOf("PUMP", "OTHER")
    private val BANDS_8701 = listOf("POOL_LT5K", "POOL_5K_20K", "POOL_20K_100K", "POOL_GE100K", "POOL_UNREAD")
    private val lastFiled8701 = ConcurrentHashMap<String, Long>()

    /** Pure: where the coin was minted. pump.fun mints its coins with addresses ending "pump", on its curve and after it. */
    fun origin8701(mint: String): String = if (mint.trim().endsWith("pump")) "PUMP" else "OTHER"

    /** Pure: the pool-depth band of [liqUsd]. An unread depth is its own band, never the thinnest one. */
    fun poolBand8701(liqUsd: Double): String = when {
        !liqUsd.isFinite() || liqUsd <= 0.0 -> "POOL_UNREAD"
        liqUsd < 5_000.0 -> "POOL_LT5K"
        liqUsd < 20_000.0 -> "POOL_5K_20K"
        liqUsd < 100_000.0 -> "POOL_20K_100K"
        else -> "POOL_GE100K"
    }

    /** Pure: the forward-label cohort of a coin minted at [mint] with a pool of [liqUsd]. */
    fun cohort8701(mint: String, liqUsd: Double): String = PREFIX_8701 + origin8701(mint) + "_" + poolBand8701(liqUsd)

    /** Pure: is [lane] an origin cohort (a pseudo-lane: no stage, trigger, liquidity, kind, source or aggregate books)? */
    fun isCohort8701(lane: String): Boolean = lane.startsWith(PREFIX_8701)

    /** Pure: is a coin last filed at [lastMs] due again at [nowMs]? */
    internal fun due8701(lastMs: Long?, nowMs: Long): Boolean = lastMs == null || nowMs < lastMs || nowMs - lastMs >= REFILE_MS_8701

    private fun solana(ts: TokenState): Boolean =
        try { com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(ts.mint) } catch (_: Throwable) { false }

    private fun depth(ts: TokenState): Double =
        try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }

    /** ForwardReturnLabeler7731.observe: file a judged Solana coin under its cohort, once per coin per 15 minutes. */
    fun observe8701(ts: TokenState, nowMs: Long) {
        if (!solana(ts)) return
        if (!due8701(lastFiled8701[ts.mint], nowMs)) return
        if (lastFiled8701.size > 6_000) lastFiled8701.entries.removeIf { nowMs - it.value > REFILE_MS_8701 }
        lastFiled8701[ts.mint] = nowMs
        ForwardReturnLabeler7731.observe(ts, cohort8701(ts.mint, depth(ts)), false, "ORIGIN_COHORT_8701", nowMs)
    }

    /** LiveEdgeGate7877: [ts]'s cohort when its own forward record is proven positive, else null. */
    fun measuredProven8701(ts: TokenState): String? {
        if (!solana(ts)) return null
        val cohort = cohort8701(ts.mint, depth(ts))
        val stat = try { ForwardReturnLabeler7731.laneStatFor7737(cohort) } catch (_: Throwable) { null }
        return if (LiveEdgeGate7877.cohortProvenPositive7948(stat)) cohort else null
    }

    /**
     * V5.0.8703 — LaneAuthority8577: [ts]'s cohort when its own forward record is proven LOSING, else null. Live capital is
     * steered by the same record that can grant an edge: a coin from a cohort the bot has watched lose 100+ times (net of
     * cost, at executable marks) is bought LIVE only on proven evidence of its own. The record is live — a cohort that
     * stops losing stops refusing — and the door itself retires on the one selection-gate rule if the coins it refuses win.
     */
    fun provenLosing8703(ts: TokenState): String? {
        if (!solana(ts)) return null
        val cohort = cohort8701(ts.mint, depth(ts))
        val stat = try { ForwardReturnLabeler7731.laneStatFor7737(cohort) } catch (_: Throwable) { null }
        return if (LiveEdgeGate7877.cohortProvenLosing8703(stat)) cohort else null
    }

    fun statusLine8701(): String = "marketCohort8701[" + ORIGINS_8701.joinToString(" | ") { o ->
        o + "{" + BANDS_8701.joinToString(",") { b ->
            val s = try { ForwardReturnLabeler7731.laneStatFor7737("$PREFIX_8701${o}_$b") } catch (_: Throwable) { null }
            b.removePrefix("POOL_") + "=" + (if (s == null) "n0" else "n${s.n60}/${"%+.1f".format(s.meanNet60Pct)}%" +
                (if (LiveEdgeGate7877.cohortProvenPositive7948(s)) "/PROVEN" else if (LiveEdgeGate7877.cohortProvenLosing8703(s)) "/LOSING" else ""))
        } + "}"
    } + "]"
}
