package com.lifecyclebot.engine.market

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.truth.TradeShapeLearner7883
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8293 — TOKEN HUNTER AUDIT REPAIRS (AUDIT_HUNTER_8293.md).
 *
 * Live 5.0.8286: "went from near money printer to a bot that buys losers". The hunters picked coins their own lane's
 * native scorer refuses (MOONSHOT hunted from $1.5k / $1.5k liquidity while the native floor had walked to its mature
 * $10k / $15k; EXPRESS demanded a 5-minute age and a 0.5-12% 5m move the native does not; MANIPULATED used fixed
 * 8-minute / $1.5k gates instead of the native fluid ones), ranked MOONSHOT hardest into the sub-$10k / sub-3-minute
 * shape the lane's realised record (TradeShapeLearner7883) proves loses, never let the opportunity ranking choose what
 * is evaluated first, and spent the hand-off re-handling one mint once per lane that picked it.
 *
 * Discovery only. Every row here still reaches the lane's own scorer, FDG, sizing and every hard safety gate.
 */
object HunterMandates8293 {

    /**
     * EXPRESS, in the native terms of ShitCoinExpress.evaluate (momentum = the 1h change): continuation at least the
     * bootstrap minimum (1%), at most 30% (CHASE_EXTENDED), and a 5-minute move of at most 20% when known (the native
     * chase line). The native has no age floor (age only scores) and no 5m minimum.
     */
    fun expressFits8293(h1: Double, m5: Double): Boolean =
        h1.isFinite() && h1 >= 1.0 && h1 <= 30.0 && (!m5.isFinite() || m5 <= 20.0)

    /** EXPRESS ranking on the native 5-minute price score shape (2-8% best, 12-20% weak, unknown neutral). */
    fun expressM5Rank8293(m5: Double): Double = when {
        !m5.isFinite() -> 0.6
        m5 in 2.0..8.0 -> 1.5
        m5 in 0.5..12.0 -> 1.0
        m5 > 12.0 && m5 <= 20.0 -> 0.3
        else -> 0.0
    }

    /** MOONSHOT's native market-cap floor right now (MoonshotTraderAI: $1,500 cold start walking to $10k mature). */
    fun moonshotMinCap8293(): Double = try {
        com.lifecyclebot.v3.scoring.MoonshotTraderAI.minMarketCapUsdFluid7719()
    } catch (_: Throwable) { com.lifecyclebot.v3.scoring.MoonshotTraderAI.MIN_MARKET_CAP_BOOTSTRAP_USD_7719 }

    /** MOONSHOT's native liquidity floor right now (MoonshotTraderAI.scoreToken: $2k bootstrap, $15k mature). */
    fun moonshotMinLiq8293(): Double = try {
        com.lifecyclebot.v3.scoring.MoonshotTraderAI.minLiquidityUsdNow8180()
    } catch (_: Throwable) { com.lifecyclebot.v3.scoring.MoonshotTraderAI.MIN_LIQUIDITY_USD_BOOTSTRAP }

    /** Pure: MOONSHOT asymmetry (positive, not-yet-vertical hour) at the native liquidity floor [minLiq]. */
    fun moonshotFits8293(h1: Double, liqUsd: Double, tx1h: Int, minLiq: Double): Boolean =
        h1.isFinite() && h1 > 0.0 && h1 <= 120.0 && liqUsd >= minLiq && tx1h >= 3

    /**
     * Pure: MOONSHOT's market-cap prior. 7796 gave sub-$10k the top weight (6.0); the lane's realised shape record reads
     * MCAP < $10k strongly negative, and the cell the runner grab was built on is $10k-$100k. Peak there now.
     */
    fun moonshotCapPrior8293(mcapUsd: Double): Double = when {
        !mcapUsd.isFinite() || mcapUsd <= 0.0 -> 1.0
        mcapUsd < 10_000.0 -> 3.0
        mcapUsd < 25_000.0 -> 5.0
        mcapUsd < 100_000.0 -> 4.0
        mcapUsd < 500_000.0 -> 2.0
        else -> 0.5
    }

    /**
     * Pure: MOONSHOT's youth prior. 7796 gave the first 15 minutes the top weight (3.0) including the first 3 minutes,
     * which the realised shape record reads strongly negative (AGE_MIN < 3). Unknown age earns nothing.
     */
    fun moonshotYouthPrior8293(ageHours: Double): Double = when {
        !ageHours.isFinite() || ageHours <= 0.0 -> 0.0
        ageHours < 3.0 / 60.0 -> 1.0
        ageHours <= 0.25 -> 3.0
        ageHours <= 1.0 -> 2.0
        ageHours <= 6.0 -> 1.0
        else -> 0.0
    }

    /** MANIPULATED's native fluid age ceiling in hours (ManipulatedTraderAI: 12 min bootstrap -> 8 min mature). */
    fun manipulatedMaxAgeHours8293(): Double = try {
        com.lifecyclebot.v3.scoring.ManipulatedTraderAI.getFluidMaxAge() / 60.0
    } catch (_: Throwable) { 8.0 / 60.0 }

    /** MANIPULATED's native fluid liquidity floor (ManipulatedTraderAI: $1.5k bootstrap -> $3k mature). */
    fun manipulatedMinLiq8293(): Double = try {
        com.lifecyclebot.v3.scoring.ManipulatedTraderAI.getFluidMinLiquidity()
    } catch (_: Throwable) { 1_500.0 }

    /** Pure: MANIPULATED discovery at the native age / liquidity limits; the native still needs manipulation proof. */
    fun manipulatedFits8293(ageHours: Double, tx1h: Int, h1: Double, liqUsd: Double, maxAgeHours: Double, minLiq: Double): Boolean =
        ageHours.isFinite() && ageHours > 0.0 && ageHours <= maxAgeHours && tx1h >= 6 && h1.isFinite() && h1 >= 5.0 && liqUsd >= minLiq

    /** The lane's band floor the hunter uses: MOONSHOT reads its native fluid floor, every other lane its constant. */
    fun baseMin8293(lane: String, baseMin: Double): Double =
        if (lane == "MOONSHOT") maxOf(baseMin, moonshotMinCap8293()) else baseMin
}

/**
 * V5.0.8293 — a lane's learned losing shapes (TradeShapeLearner7883: n >= 30, mean + SE below -2% and worse than the
 * lane, runner lanes without a tail) are enforced at the live gate; the hunter spent picks on rows that gate will refuse.
 * Those rows now rank after every other row of the lane (never removed: paper and learning still see them).
 */
object HunterShape8293 {
    private const val CHECK_TOP_N_8293 = 64
    /** Delivery picks come from the lane's best 64 ranks (8170's fresh-first reached any depth: rank 200 over rank 4). */
    const val DELIVERY_HEAD_8293 = 64

    /** Pure: the first learned losing shape [r] sits in for [lane] (age, liquidity, cap, cap/liquidity), or null. */
    fun losingShape8293(
        lane: String, r: MarketSweep7297.Row,
        losing: (String, TradeShapeLearner7883.Feature, Double) -> String?,
    ): String? {
        val age = if (r.ageHours.isFinite() && r.ageHours > 0.0) r.ageHours * 60.0 else -1.0
        val liq = if (r.liquidityUsd.isFinite() && r.liquidityUsd > 0.0) r.liquidityUsd else -1.0
        val cap = if (r.mcapUsd.isFinite() && r.mcapUsd > 0.0) r.mcapUsd else -1.0
        val ratio = if (liq > 0.0 && cap > 0.0) cap / liq else -1.0
        val reads = listOf(
            TradeShapeLearner7883.Feature.AGE_MIN to age,
            TradeShapeLearner7883.Feature.LIQUIDITY_USD to liq,
            TradeShapeLearner7883.Feature.MCAP_USD to cap,
            TradeShapeLearner7883.Feature.MCAP_LIQ to ratio,
        )
        for ((f, v) in reads) {
            if (v < 0.0) continue
            losing(lane, f, v)?.let { return it }
        }
        return null
    }

    /** Production read of the learner (fail-open: no rule known, no demotion). */
    fun live8293(lane: String, f: TradeShapeLearner7883.Feature, v: Double): String? =
        try { TradeShapeLearner7883.provenLosingBin8293(lane, f, v) } catch (_: Throwable) { null }

    /**
     * Pure: [ranked] reordered — top-opportunity rows first, then the rest, then rows in a learned losing shape. Only the
     * first [CHECK_TOP_N_8293] rows are inspected (the lane picks 12); the tail keeps its order. Nothing is removed.
     */
    fun order8293(ranked: List<String>, top: Set<String>, demoted: (String) -> Boolean): List<String> {
        if (ranked.isEmpty()) return ranked
        val head = ranked.take(CHECK_TOP_N_8293)
        val front = ArrayList<String>(); val mid = ArrayList<String>(); val back = ArrayList<String>()
        for (m in head) {
            when {
                demoted(m) -> back.add(m)
                m in top -> front.add(m)
                else -> mid.add(m)
            }
        }
        return front + mid + ranked.drop(head.size) + back
    }

    /** Production: [order8293] for [lane] with the live learner and the current opportunity leaders, counted. */
    fun orderLive8293(lane: String, ranked: List<String>, byMint: Map<String, MarketSweep7297.Row>, top: Set<String>): List<String> {
        var demotedN = 0
        val out = order8293(ranked, top) { m ->
            val r = byMint[m]
            val hit = r != null && losingShape8293(lane, r, HunterShape8293::live8293) != null
            if (hit) demotedN++
            hit
        }
        if (demotedN > 0) try { PipelineHealthCollector.labelAdd("LANE_HUNT_SHAPE_DEMOTED_8293_$lane", demotedN.toLong()) } catch (_: Throwable) {}
        return out
    }

    /**
     * Pure: the lane's delivery picks — LaneHunter7297.freshFirst8170 applied only to rows still deliverable and not in
     * a losing shape ([isBack]); those follow, so a novel losing-shape row never jumps a qualified one.
     */
    fun deliveryPicks8293(ranked: List<String>, watched: (String) -> Boolean, isBack: (String) -> Boolean, pickLimit: Int): List<String> {
        if (pickLimit <= 0) return emptyList()
        val (good, back) = ranked.take(DELIVERY_HEAD_8293).partition { !isBack(it) }
        return (LaneHunter7297.freshFirst8170(good, watched, pickLimit) + back).take(pickLimit)
    }
}

/**
 * V5.0.8293 — what happens to the opportunity intelligence's leaders. 8286: JONATHAN (BREAKOUT_EXPANSION 90-99),
 * Alpenglow, PHP ranked top every rebuild and were never bought; the rank only multiplied a row's score INSIDE lanes
 * whose band and mandate it already passed, and never touched which watched token is evaluated first. Now:
 *  - a leader that fits a lane is put at the front of that lane's picks (ordering only);
 *  - a leader on the watchlist rides the cycle window like a hand-off target (bounded, revisited at most every 15 s);
 *  - every leader's route is recorded: which lanes fit, which refused it by band or by mandate, which picked it.
 */
object OpportunityRoute8293 {
    val CONSTRUCTIVE_8293 = setOf(
        "EARLY_MOMENTUM_IGNITION", "BREAKOUT_EXPANSION", "RELATIVE_STRENGTH_LEADER", "LIQUIDITY_EXPANSION", "CONTINUATION",
    )
    const val TOP_RANK_8293 = 10
    const val TOP_SCORE_8293 = 80.0
    const val MAX_WINDOW_TARGETS_8293 = 4

    /** One lane's discovery test for a row: inside its band, passing its mandate. */
    class LaneTest8293(val lane: String, val inBand: (MarketSweep7297.Row) -> Boolean, val fits: (MarketSweep7297.Row) -> Boolean)

    data class Route8293(val mint: String, val symbol: String, val rank: Int, val score: Int, val setup: String,
                         val fit: List<String>, val refused: Map<String, String>, val picked: List<String>)

    /** Pure: is [o] an opportunity leader (top ranks, high score, constructive setup)? */
    fun isTop8293(o: MarketSweep7297.OpportunitySignal?): Boolean =
        o != null && o.rank in 1..TOP_RANK_8293 && o.score >= TOP_SCORE_8293 && o.setup in CONSTRUCTIVE_8293

    /** Pure: the route of one leader row through every lane's band and mandate, and which lanes picked it. */
    fun route8293(r: MarketSweep7297.Row, o: MarketSweep7297.OpportunitySignal, lanes: List<LaneTest8293>,
                  picks: Map<String, List<String>>): Route8293 {
        val fit = ArrayList<String>()
        val refused = LinkedHashMap<String, String>()
        for (t in lanes) {
            when {
                !t.inBand(r) -> refused[t.lane] = "BAND"
                !t.fits(r) -> refused[t.lane] = "MANDATE"
                else -> fit += t.lane
            }
        }
        val picked = picks.filter { (_, ms) -> r.mint in ms }.keys.toList()
        return Route8293(r.mint, r.symbol, o.rank, o.score.toInt(), o.setup, fit, refused, picked)
    }

    /** Pure: leaders among [present] whose last evaluation is at least [revisitMs] old, best rank first, bounded. */
    fun select8293(top: List<MarketSweep7297.OpportunitySignal>, present: Set<String>, lastProcessedAt: (String) -> Long,
                   nowMs: Long, revisitMs: Long, max: Int = MAX_WINDOW_TARGETS_8293): List<String> =
        top.asSequence().filter { isTop8293(it) && it.mint in present }
            .filter { o -> val last = lastProcessedAt(o.mint); last <= 0L || nowMs - last >= revisitMs }
            .sortedBy { it.rank }.map { it.mint }.distinct().take(max).toList()

    @Volatile private var lastLine8293 = "no hunt yet"
    private val routed = java.util.concurrent.atomic.AtomicLong(0)
    private val noLane = java.util.concurrent.atomic.AtomicLong(0)

    /** LaneHunter7297.hunt: record every leader's route this sweep. */
    fun noteHunt8293(rows: List<MarketSweep7297.Row>, lanes: List<LaneTest8293>, picks: Map<String, List<String>>) {
        try {
            val leaders = rows.mapNotNull { r -> MarketSweep7297.opportunityFor7777(r.mint)?.takeIf { isTop8293(it) }?.let { r to it } }
                .sortedBy { it.second.rank }.take(6)
            val routes = leaders.map { (r, o) -> route8293(r, o, lanes, picks) }
            routes.forEach { rt ->
                if (rt.fit.isEmpty()) { noLane.incrementAndGet(); PipelineHealthCollector.labelInc("OPPORTUNITY_TOP_NO_LANE_FIT_8293") }
                else { routed.incrementAndGet(); PipelineHealthCollector.labelInc("OPPORTUNITY_TOP_ROUTED_8293") }
            }
            lastLine8293 = "opportunityRoute8293[routed=${routed.get()} noLane=${noLane.get()} " + routes.joinToString(" | ") { rt ->
                val row = rows.firstOrNull { it.mint == rt.mint }
                val facts = row?.let { "cap=${(it.mcapUsd / 1000).toInt()}k liq=${(it.liquidityUsd / 1000).toInt()}k ageMin=${(it.ageHours * 60).toInt()} h1=${it.priceChangeH1Pct.toInt()}%" } ?: ""
                "#${rt.rank} ${rt.symbol.ifBlank { rt.mint.take(6) }.take(12)} ${rt.setup} s${rt.score} $facts fit=${rt.fit.joinToString(",").ifBlank { "NONE" }} " +
                    "picked=${rt.picked.joinToString(",").ifBlank { "-" }} mandateRefused=${rt.refused.filterValues { it == "MANDATE" }.keys.joinToString(",").ifBlank { "-" }}"
            } + "]"
        } catch (_: Throwable) {}
    }

    fun line8293(): String = lastLine8293
}

/**
 * V5.0.8293 — the hunt hand-off without churn. 8286: requeue cooldown 579, alreadyWatched 620, multi-lane 606. The same
 * mint picked by four lanes was handled four times per sweep (one requeue, then three "cooldown" counts in the same
 * pass), and a mint the intake bounced (requeued, still not watched) was requeued again every 10 minutes forever.
 */
object HuntDelivery8293 {
    const val BASE_COOLDOWN_MS_8293 = 10L * 60_000L
    const val MAX_COOLDOWN_MS_8293 = 60L * 60_000L
    private const val HISTORY_TTL_MS_8293 = 3L * 60L * 60_000L
    private const val MAX_HISTORY_8293 = 6_000

    private class Requeue8293(@Volatile var lastAtMs: Long, @Volatile var count: Int)
    private val history = ConcurrentHashMap<String, Requeue8293>()

    /** Pure: the cooldown after [count] requeues that did not stick: 10, 20, 40, then 60 minutes. */
    fun cooldownMs8293(count: Int): Long =
        (BASE_COOLDOWN_MS_8293 shl (count - 1).coerceIn(0, 3)).coerceAtMost(MAX_COOLDOWN_MS_8293)

    /** Pure: may a mint requeued [count] times, last at [lastAtMs], be requeued at [nowMs]? */
    fun due8293(lastAtMs: Long, count: Int, nowMs: Long): Boolean =
        lastAtMs <= 0L || count <= 0 || nowMs - lastAtMs >= cooldownMs8293(count) || nowMs < lastAtMs

    fun requeueDue8293(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val h = history[mint] ?: return true
        return due8293(h.lastAtMs, h.count, nowMs)
    }

    /** Is [mint] inside its (escalated) requeue cooldown — a re-pick of it cannot be delivered? */
    fun cooling8293(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        history.containsKey(mint) && !requeueDue8293(mint, nowMs)

    /** [mint] was requeued now. A requeue inside the history window means the last one did not stick: it escalates. */
    fun noteRequeued8293(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        if (history.size > MAX_HISTORY_8293) history.entries.removeIf { nowMs - it.value.lastAtMs > HISTORY_TTL_MS_8293 }
        val h = history[mint]
        if (h == null || nowMs - h.lastAtMs > HISTORY_TTL_MS_8293) history[mint] = Requeue8293(nowMs, 1)
        else {
            h.lastAtMs = nowMs; h.count++
            if (h.count >= 2) try { PipelineHealthCollector.labelInc("MARKET_HUNT_REQUEUE_BOUNCED_8293") } catch (_: Throwable) {}
        }
    }

    /** A pick found on the watchlist: its requeue stuck, the escalation resets. */
    fun noteWatched8293(mint: String) { history.remove(mint) }

    /** Pure: mint -> every lane that picked it this hunt, in lane order (the first lane handles the hand-off). */
    fun lanesByMint8293(picks: Map<String, List<String>>): Map<String, Set<String>> {
        val out = LinkedHashMap<String, LinkedHashSet<String>>()
        for ((lane, mints) in picks) for (m in mints) out.getOrPut(m) { LinkedHashSet() }.add(lane)
        return out
    }

    internal fun resetForTest8293() = history.clear()
}
