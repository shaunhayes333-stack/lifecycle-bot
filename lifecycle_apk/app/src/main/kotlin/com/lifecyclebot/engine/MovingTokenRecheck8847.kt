package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.BuyerFlow8666
import com.lifecyclebot.engine.truth.FeatureKind8283
import java.util.concurrent.ConcurrentHashMap

/**
 * 5.0.8847 — a declined coin is not a declined *future observation*.
 *
 * The existing FeatureRevision8283 invalidates score caches whenever a validated print lands, but previously
 * CandidateAdmission8136 prioritised the mint for another supervisor pass only when an earlier verdict was DEFER_DATA.
 * A fully answered negative model/FDG verdict therefore left a freshly accelerating mint in cold round-robin.
 * This is ONLY scheduler priority. Nothing here grants FDG authority, relaxes safety or turns an unverified price
 * into a buyer. The normal V3/V4 + specialist + FDG decision is rerun from current witnessed data.
 */
object MovingTokenRecheck8847 {
    internal const val PRIORITY_TTL_MS_8847 = 60_000L
    internal const val RECHECK_GAP_MS_8847 = 10_000L
    private const val KEEP_MS_8847 = 10L * 60_000L
    private const val CAP_8847 = 2_000
    data class Anchor8847(val price: Double, val observedMs: Long, val lastPriorityMs: Long)
    private val anchors = ConcurrentHashMap<String, Anchor8847>()
    private val active = ConcurrentHashMap<String, Long>()

    /** Pure: progress is fresh, economically non-dust, and either lifting or attracting verified net new buyers. */
    fun due8847(price: Double, markAtMs: Long, liquidityUsd: Double, marketCapUsd: Double,
                change5mPct: Double, anchorPrice: Double?, newBuyers15s: Int?, buySharePct: Double?, netSol60s: Double?,
                lastPriorityMs: Long, nowMs: Long): Boolean {
        if (!(price.isFinite() && price > 0.0) || !(liquidityUsd.isFinite() && liquidityUsd >= 500.0) ||
            !(marketCapUsd.isFinite() && marketCapUsd >= 2_000.0) ||
            markAtMs <= 0L || nowMs - markAtMs !in 0L..15_000L ||
            lastPriorityMs > 0L && nowMs - lastPriorityMs in 0L until RECHECK_GAP_MS_8847) return false
        val climbedSinceAssessment = anchorPrice != null && anchorPrice.isFinite() && anchorPrice > 0.0 &&
            price >= anchorPrice * 1.04
        val climbingOnChart = change5mPct.isFinite() && change5mPct >= 7.0
        val buyerIgnition = (newBuyers15s ?: 0) >= 2 && (buySharePct ?: Double.NaN) >= 58.0 &&
            (netSol60s ?: Double.NaN) > 0.0
        return climbedSinceAssessment || climbingOnChart || buyerIgnition
    }

    /** Called after a producer actually accepted price/flow/shape evidence, before the DEFER_DATA-only early return. */
    fun onEvidence8847(mint: String, kinds: Set<FeatureKind8283>, nowMs: Long): Boolean {
        if (mint.isBlank() || kinds.none { it == FeatureKind8283.PRICE || it == FeatureKind8283.POOL_FLOW ||
                    it == FeatureKind8283.TAPE || it == FeatureKind8283.SHAPE }) return false
        val ts = try { BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return false
        if (ts.position.isOpen) return false // held assets have their own higher-priority exit supervision
        if (!ts.lastPrice.isFinite() || ts.lastPrice <= 0.0 || ts.lastPriceUpdate <= 0L ||
            nowMs - ts.lastPriceUpdate !in 0L..15_000L || ts.lastLiquidityUsd < 500.0 || ts.lastMcap < 2_000.0)
            return false
        val previous = anchors[mint]?.takeIf { nowMs - it.observedMs in 0L..KEEP_MS_8847 }
        if (previous != null && nowMs - previous.lastPriorityMs in 0L until RECHECK_GAP_MS_8847) return false
        // Read the signed wallet tape only for an accepted TAPE event; a price-only tick has no buyers to invent.
        val buyers = if (FeatureKind8283.TAPE in kinds) try { BuyerFlow8666.read(mint, nowMs) } catch (_: Throwable) { null } else null
        val promoted = due8847(ts.lastPrice, ts.lastPriceUpdate, ts.lastLiquidityUsd, ts.lastMcap,
            ts.lastPriceChange5m, previous?.price, buyers?.newBuyers15s, buyers?.buySharePct,
            buyers?.netSol60s, previous?.lastPriorityMs ?: 0L, nowMs)
        anchors[mint] = if (promoted || previous == null) Anchor8847(ts.lastPrice, nowMs,
            if (promoted) nowMs else previous?.lastPriorityMs ?: 0L) else previous
        if (promoted) {
            active[mint] = nowMs + PRIORITY_TTL_MS_8847
            DataPipeline8191.noteNearDecision8191(mint, nowMs)
            PipelineHealthCollector.labelInc("MOVING_TOKEN_REASSESSMENT_8847")
            if (active.size > CAP_8847) active.entries.removeIf { it.value < nowMs }
        }
        if (anchors.size > CAP_8847) anchors.entries.removeIf { nowMs - it.value.observedMs > KEEP_MS_8847 }
        return promoted
    }

    /** Watched, current movers ready to be reevaluated; bounded before any of the costly specialist work. */
    fun isRecentlyMoving8847(mint: String, nowMs: Long): Boolean = (active[mint] ?: 0L) >= nowMs

    fun targets8847(present: Collection<String>, nowMs: Long = System.currentTimeMillis(),
                    limit: Int = 8, lastProcessedAt: (String) -> Long = {
                        try { GlobalTradeRegistry.getEntry(it)?.lastProcessedAt ?: 0L } catch (_: Throwable) { 0L }
                    }): List<String> {
        if (present.isEmpty() || limit <= 0) return emptyList()
        val set = present.toHashSet()
        return active.entries.asSequence().filter { it.key in set && it.value >= nowMs }
            .sortedByDescending { it.value }.map { it.key }
            // BotService's must-include window runs before the ordinary watchlist pre-lane purge. Do not resurrect a
            // banned/quarantined/blocked mint merely because its price moved. Later hard safety still runs as usual.
            .filter { mint -> try { !BannedTokens.isBanned(mint) && !QuarantineStore.isQuarantined(mint) &&
                !TokenBlacklist.isBlocked(mint) } catch (_: Throwable) { false } }
            .filter { mint -> nowMs - lastProcessedAt(mint) >= RECHECK_GAP_MS_8847 }
            .take(limit).toList()
    }

    internal fun resetForTests8847() { anchors.clear(); active.clear() }
}
