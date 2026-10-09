package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7943 — spikes are sold INTO, on the print that shows them.
 *
 * 5.0.7941 live: foff peaked +276%, LAMBO +105%, WcErhi18 +71% within one to two
 * minutes of entry and all three closed at about 0%. Every trailing lock in the
 * book sells on the way DOWN, and these spikes were one or two curve prints: the
 * next mark the monitors saw was back at entry. The only way to bank a spike is to
 * sell on the trade print that carries it. Every held-curve trade mark (PumpPortal
 * and Helius) comes through here first; a mark at or above a tier sells that
 * tier's fraction at once (off the socket thread). The remainder keeps the
 * fluid locks and the runner floor, so a real 500% runner still runs.
 */
object SpikeCapture7943 {
    /** (gross % at or above which the tier fires, fraction of the CURRENT holding sold). */
    val TIERS: List<Pair<Double, Double>> = listOf(40.0 to 0.60, 120.0 to 0.70, 400.0 to 0.70)

    private val firedTiers = ConcurrentHashMap<String, Int>()
    private val fired = java.util.concurrent.atomic.AtomicLong(0)
    /** V5.0.7944 — a tier whose sell did not land is re-armed, at most this many times per position. */
    // V5.0.7965 — 4 re-arms ran out on Frank while sells were suppressed; a spike keeps trying while it lasts.
    private const val MAX_REARMS_7944 = 16
    private val rearms = ConcurrentHashMap<String, Int>()
    private val rearmed = java.util.concurrent.atomic.AtomicLong(0)
    /** V5.0.7944 — a non-curve mark must be this fresh to sell on (a stale print is not a spike). */
    private const val FRESH_MARK_MS_7944 = 5_000L

    /**
     * V5.0.7944 — the sell for [tier] did not land (another sell held the lock, no
     * route, slippage): the tier fires again on the next print still above it.
     * Returns true when re-armed.
     */
    fun rearm(ts: TokenState, tier: Int): Boolean {
        val key = "${ts.mint}|${ts.position.entryTime}"
        val n = rearms.merge(key, 1, Int::plus) ?: 1
        if (n > MAX_REARMS_7944 || !ts.position.isOpen) return false
        firedTiers.compute(key) { _, done -> if (done != null && done >= tier) tier - 1 else done }
        rearmed.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SPIKE_CAPTURE_REARMED_7944") } catch (_: Throwable) {}
        return true
    }

    /**
     * V5.0.7944 — the rapid monitor's 500 ms read of every open position. Spikes on
     * graduated / DEX tokens never come through the curve trade feed; this sells them
     * on the freshest mark the row holds. Returns the mark read (the monitor's price).
     */
    fun rapidMark(ts: TokenState, nowMs: Long, sell: (TokenState, Double, String) -> Unit): Double? {
        val px = ts.lastPrice.takeIf { it > 0 } ?: ts.history.lastOrNull()?.priceUsd
        if (px != null && px > 0.0 && ts.lastPriceUpdate > 0L && nowMs - ts.lastPriceUpdate <= FRESH_MARK_MS_7944) {
            try { onMark(ts, px, sell) } catch (_: Throwable) {}
        }
        try { chartExit7950(ts, px, nowMs, sell) } catch (_: Throwable) {}
        // V5.0.7962 — structure break (first lower low after a run): shadow until its own record proves it.
        try { com.lifecyclebot.engine.chart.StructureTracker7962.heldExit7962(ts, px, nowMs, sell) } catch (_: Throwable) {}
        return px
    }

    private val chartExitAt7950 = ConcurrentHashMap<String, Long>()

    /**
     * V5.0.7950 — the chart reader's exit for a held position: the shape now matches
     * tops (or the dev sold). Sells the whole holding; at most once per 20 s per
     * position while the sell lands, and never in the first 90 s after entry.
     */
    private fun chartExit7950(ts: TokenState, px: Double?, nowMs: Long, sell: (TokenState, Double, String) -> Unit) {
        val pos = ts.position
        if (!pos.isOpen || pos.entryTime <= 0L || nowMs - pos.entryTime < 90_000L) return
        val why = com.lifecyclebot.engine.chart.ChartReader7950.exitFor(ts.mint, nowMs, pos.entryTime) ?: return
        val key = "${ts.mint}|${pos.entryTime}"
        val last = chartExitAt7950[key] ?: 0L
        if (nowMs - last < 20_000L) return
        if (chartExitAt7950.size > 2_000) chartExitAt7950.clear()
        chartExitAt7950[key] = nowMs
        val green = px != null && pos.entryPrice > 0.0 && px > pos.entryPrice
        sell(ts, 1.0, if (green) "CHART_CAPTURE_TOP_7950_$why" else "CHART_STOP_7950_$why")
    }

    /** V5.0.7945 — the fill a spike sell is assumed to give up against its tier price. */
    private const val CAPTURE_SLIP_7945 = 0.05

    /**
     * V5.0.7945 — Pure: the gross % the tiers bank on a path that peaked at
     * [peakGrossPct] and sits at [horizonGrossPct]. Each tier the peak reached sells
     * its fraction at the tier's own threshold (the first print at or above it, not
     * the peak) less [CAPTURE_SLIP_7945]; the remainder is worth the horizon price.
     * foff (+276% peak, 0% at the hour) banks about +50%, not 0.
     */
    fun realisableGrossPct(peakGrossPct: Double, horizonGrossPct: Double, tiers: List<Pair<Double, Double>> = TIERS): Double {
        if (!horizonGrossPct.isFinite()) return horizonGrossPct
        val peak = if (peakGrossPct.isFinite()) maxOf(peakGrossPct, horizonGrossPct) else horizonGrossPct
        var remaining = 1.0
        var banked = 0.0
        // V5.0.7955 — [tiers] is the key's learned ladder (ExitProfile7955); TIERS is its prior.
        for ((pct, frac) in tiers) {
            if (peak < pct) break
            val sold = remaining * frac
            banked += sold * ((1.0 + pct / 100.0) * (1.0 - CAPTURE_SLIP_7945) - 1.0) * 100.0
            remaining -= sold
        }
        return banked + remaining * horizonGrossPct
    }

    /** Pure: the highest tier index (1-based) this gross gain reaches, or 0. */
    fun tierReached(grossPct: Double, tiers: List<Pair<Double, Double>> = TIERS): Int {
        if (!grossPct.isFinite()) return 0
        var t = 0
        tiers.forEachIndexed { i, (pct, _) -> if (grossPct >= pct) t = i + 1 }
        return t
    }

    /**
     * Called with every fresh trade mark of a held position. Returns the tier fired
     * (and calls [sell] with the fraction and reason), or 0 when nothing fires.
     */
    fun onMark(ts: TokenState, priceUsd: Double, sell: (TokenState, Double, String) -> Unit): Int {
        val pos = ts.position
        if (!pos.isOpen || pos.entryPrice <= 0.0 || !priceUsd.isFinite() || priceUsd <= 0.0) return 0
        val gross = (priceUsd / pos.entryPrice - 1.0) * 100.0
        // A basis in the wrong units reads as a 100,000% "spike"; that is not a price.
        if (gross > 100_000.0) return 0
        // V5.0.7955 — the position's own learned ladder (lane x setup); TIERS when unlearned.
        val now7955 = System.currentTimeMillis()
        try { ExitProfile7955.notePeak7955(ts, gross, now7955) } catch (_: Throwable) {}
        val plan7955 = try { ExitProfile7955.planFor7955(ts, now7955) } catch (_: Throwable) { null }
        val tiers = plan7955?.tiers?.takeIf { it.isNotEmpty() } ?: TIERS
        val reached = tierReached(gross, tiers)
        if (reached == 0) return 0
        val key = "${ts.mint}|${pos.entryTime}"
        val done = firedTiers[key] ?: 0
        if (reached <= done) return 0
        if (firedTiers.size > 2_000) firedTiers.clear()
        firedTiers[key] = reached
        val frac = tiers[reached - 1].second
        fired.incrementAndGet()
        try { ExitProfile7955.onTierFired7955(plan7955, reached) } catch (_: Throwable) {}
        try {
            PipelineHealthCollector.labelInc("SPIKE_CAPTURE_7943_TIER$reached")
            ForensicLogger.lifecycle(
                "SPIKE_CAPTURE_7943",
                "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${pos.tradingMode} gross=${"%.1f".format(gross)}% tier=$reached sell=${(frac * 100).toInt()}% key7955=${plan7955?.key ?: "PRIOR"}/${plan7955?.source ?: "PRIOR"} action=sell_into_spike",
            )
        } catch (_: Throwable) {}
        sell(ts, frac, "SPIKE_CAPTURE_7943_T${reached}_${gross.toInt()}PCT")
        if (rearms.size > 2_000) rearms.clear()
        return reached
    }

    /**
     * V5.0.7955 — PipelineHealthCollector.dumpText: the spike line, the mayhem line and the
     * learned exit profiles (ExitProfile7955: n per key, top keys' tiers/trail/maxHold, tier fires by key).
     */
    fun diagLines7955(): String =
        statusLine() + " · mayhem " + MayhemMode7943.statusLine() + "\n  Exit profiles (§7955):       " +
            (try { ExitProfile7955.statusLine7955() } catch (_: Throwable) { "unavailable" }) +
            // V5.0.7961 — fill price against decision price, per lane (feeds every label's cost).
            "\n  Entry chase (§7961):         " + (try { com.lifecyclebot.engine.truth.EntryChase7961.statusLine() } catch (_: Throwable) { "unavailable" }) +
            // V5.0.7962 — market structure (swings, HL_RECLAIM, learned structure-break exit) and the measured all-in cost.
            "\n  Structure (§7962):           " + (try { com.lifecyclebot.engine.chart.StructureTracker7962.statusLine7962() } catch (_: Throwable) { "unavailable" }) +
            "\n  Cost ledger (§7962):         " + (try { com.lifecyclebot.engine.truth.CostLedger7962.statusLine7962() } catch (_: Throwable) { "unavailable" }) +
            // V5.0.7962 — slots, size and priority on the best decision cells (Thompson bandit).
            "\n  Cell allocator (§7962):      " + (try { CellAllocator7962.statusLine() } catch (_: Throwable) { "unavailable" })

    fun statusLine(): String = "fired=${fired.get()} rearmed7944=${rearmed.get()} tiers=${TIERS.joinToString(",") { "+${it.first.toInt()}%:${(it.second * 100).toInt()}%" }}"
}

/**
 * V5.0.7943 — pump.fun Mayhem Mode coins (created with create_v2 in mayhem mode:
 * 2 billion supply and a protocol agent trading the curve for its first 24 h) are
 * not organic demand. Detected by supply: the on-chain supply when resolved, else
 * the supply implied by market cap / price. Refused for LIVE entry.
 */
object MayhemMode7943 {
    private const val MAYHEM_SUPPLY_LO = 1.6e9
    private const val MAYHEM_SUPPLY_HI = 2.6e9
    private val refused = java.util.concurrent.atomic.AtomicLong(0)

    /** Pure: is this supply a mayhem-mode pump.fun supply? */
    fun mayhemSupply(supply: Double): Boolean = supply.isFinite() && supply in MAYHEM_SUPPLY_LO..MAYHEM_SUPPLY_HI

    /**
     * V5.0.7947 — only the on-chain supply convicts. 5.0.7944 refused 1,286 coins in
     * 27 minutes while only 591 supplies had resolved: most refusals rested on the
     * supply implied by market cap / price, two prints from different feeds and times
     * (TokenMetrics: supplyConflicts=25, worst break 102x). A coin whose implied supply
     * looks like mayhem waits up to [UNVERIFIED_WAIT_MS_7947] for its chain read, then
     * is judged on that read, or let through if the chain never answers.
     */
    private const val UNVERIFIED_WAIT_MS_7947 = 60_000L
    private val firstSuspectAt = ConcurrentHashMap<String, Long>()
    private val deferred = java.util.concurrent.atomic.AtomicLong(0)
    private val cleared = java.util.concurrent.atomic.AtomicLong(0)

    /** Pure: "MAYHEM" (chain-proven), "WAIT" (implied only, inside the wait), or null. */
    fun verdict7947(onChainSupply: Double, impliedSupply: Double, suspectForMs: Long): String? = when {
        onChainSupply > 0.0 -> if (mayhemSupply(onChainSupply)) "MAYHEM" else null
        mayhemSupply(impliedSupply) && suspectForMs < UNVERIFIED_WAIT_MS_7947 -> "WAIT"
        else -> null
    }

    /** LiveEdgeGate7877: the live refusal for a mayhem coin, or null. */
    fun liveRefusal(ts: TokenState, nowMs: Long = System.currentTimeMillis()): String? {
        if (!ts.mint.endsWith("pump")) return null
        val onChain = try { com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075.supplyOf7075(ts.mint) } catch (_: Throwable) { 0.0 }
        if (onChain <= 0.0) try { com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075.requestAsync7075(ts.mint) } catch (_: Throwable) {}
        val implied = if (ts.lastMcap > 0.0 && ts.lastPrice > 0.0) ts.lastMcap / ts.lastPrice else 0.0
        val since = if (onChain <= 0.0 && mayhemSupply(implied)) {
            if (firstSuspectAt.size > 5_000) firstSuspectAt.clear()
            nowMs - firstSuspectAt.getOrPut(ts.mint) { nowMs }
        } else 0L
        return when (verdict7947(onChain, implied, since)) {
            "MAYHEM" -> {
                refused.incrementAndGet()
                try { PipelineHealthCollector.labelInc("MAYHEM_MODE_REFUSED_7943") } catch (_: Throwable) {}
                "MAYHEM_MODE_7943"
            }
            "WAIT" -> {
                deferred.incrementAndGet()
                "MAYHEM_UNVERIFIED_WAIT_7947"
            }
            else -> {
                if (firstSuspectAt.remove(ts.mint) != null) cleared.incrementAndGet()
                null
            }
        }
    }

    fun statusLine(): String = "refusedOnChain=${refused.get()} waitedUnverified7947=${deferred.get()} clearedAfterWait7947=${cleared.get()}"
}
