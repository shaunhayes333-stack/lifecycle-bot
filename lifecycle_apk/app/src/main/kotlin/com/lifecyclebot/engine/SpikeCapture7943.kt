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

    // V5.0.8153 — the fired / previous / pending-since tier maps are gone: a rung's CONFIRMED tier and its one pending
    // tranche live in RunnerRungBook8153 (RunnerLive8153), the confirmed part persisted. `firedTiers[key] = reached`
    // was written before the sale was even dispatched, so a refused tranche consumed its milestone.
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
     * V5.0.8153 — the request is REJECTED and goes back; its confirmed milestone never moved, so nothing is consumed.
     * After [MAX_REARMS_7944] refusals it is no longer dropped for good: it retries on the slow backoff (60 s).
     */
    fun rearm(ts: TokenState, tier: Int): Boolean {
        val key = "${ts.mint}|${ts.position.entryTime}"
        rearms.merge(key, 1, Int::plus)
        if (!ts.position.isOpen) return false
        try { RunnerLive8153.revert8153(ts, RunnerRungBook8153.Revert8153.REJECTED, System.currentTimeMillis(), MAX_REARMS_7944) } catch (_: Throwable) {}
        // V5.0.8144 — the rung's sale did not land: it gives back the arbitration ticket it held.
        try { ExitTruthArbiterLive8144.releaseTranche8144(ts.mint, "SPIKE_CAPTURE_7943_T$tier") } catch (_: Throwable) {}
        rearmed.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SPIKE_CAPTURE_REARMED_7944") } catch (_: Throwable) {}
        return true
    }

    /**
     * A dispatched spike order isn't an on-chain fill. If no partial reached
     * chain finality and the sell lock is no longer held, make the tier
     * retryable after the existing close backoff. A confirmed tier has no
     * pending timestamp and is never reopened by this recovery.
     * V5.0.8153 — the live rule is RunnerRungBook8153.graceMs8153 (REQUESTED keeps this 5 s; a handed-off or
     * in-flight tranche waits for its sale); this 8081 predicate is kept as the record of the REQUESTED grace.
     */
    internal fun pendingExpired8081(atMs: Long?, nowMs: Long): Boolean =
        atMs != null && nowMs - atMs >= 5_000L

    fun onConfirmedPartial8081(ts: TokenState, reason: String, consumedRaw: java.math.BigInteger) {
        if (consumedRaw.signum() <= 0) return
        val tier = Regex("SPIKE_CAPTURE_7943_T([0-9]+)").find(reason)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return
        if (tier !in 1..3) return
        // V5.0.8153 — chain-verified consumption CONFIRMS the tier (idempotent: Executor.commitVerifiedLiveSlice7835 has
        // already confirmed it and booked the slice's proceeds once, RunnerLive8153.bookSlice8153).
        try { RunnerLive8153.confirmTier8153(ts, tier) } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("SPIKE_CAPTURE_CHAIN_PARTIAL_CONFIRMED_8081") } catch (_: Throwable) {}
    }

    /**
     * V5.0.8153 — the rung was handed to the sell door (the arbiter's dispatch, or BotService.sellIntoSpike7943 off the
     * print path): its pending tranche is TICKET_ACCEPTED. Not a fill.
     */
    fun dispatched8153(ts: TokenState, reason: String) {
        if (RunnerLive8153.rungOf8153(reason) <= 0) return
        try { RunnerLive8153.advance8153(ts, RunnerRungBook8153.Stage8153.TICKET_ACCEPTED, System.currentTimeMillis()) } catch (_: Throwable) {}
    }

    /**
     * V5.0.8153 — BotService.sellIntoSpike7943: the executor's receipt says the slice APPLIED. Live, that receipt is
     * issued only after the chain-confirmed slice was committed (the tier is already confirmed — idempotent); paper has
     * no chain, its applied receipt is its fill.
     */
    fun onApplied8153(ts: TokenState, reason: String) {
        val tier = RunnerLive8153.rungOf8153(reason)
        if (tier <= 0) return
        try { RunnerLive8153.confirmTier8153(ts, tier) } catch (_: Throwable) {}
    }

    /**
     * V5.0.7944 — the rapid monitor's 500 ms read of every open position. Spikes on
     * graduated / DEX tokens never come through the curve trade feed; this sells them
     * on the freshest mark the row holds. Returns the mark read (the monitor's price).
     */
    fun rapidMark(ts: TokenState, nowMs: Long, sell: (TokenState, Double, String) -> Unit): Double? {
        // V5.0.8144 — one arbitration ticket per position per tick (ExitTruthArbiterLive8144): every overlay below OFFERS
        // its exit, the one authority ranks them and only the winner is sold. A spike rung and a structure break on the
        // same print no longer both reach the sell door.
        val tick8144 = ExitTruthArbiterLive8144.Tick8144(ts, sell)
        val px8144 = rapidMarkOffers8144(ts, nowMs, tick8144)
        try { tick8144.dispatch8144(px8144) } catch (_: Throwable) {}
        return px8144
    }

    private fun rapidMarkOffers8144(ts: TokenState, nowMs: Long, tick8144: ExitTruthArbiterLive8144.Tick8144): Double? {
        val sell = tick8144.offer8144
        val px = ts.lastPrice.takeIf { it > 0 } ?: ts.history.lastOrNull()?.priceUsd
        // V5.0.8065 — a lifecycle hold runs its own exits (floor, 2x take, 15m base / trend breaks), not the scalp ones.
        // V5.0.8144 — but a tier-1 exit the chart reader sees beside it (dev sold, rug) is offered too: never held.
        if (try { LifecycleHold8065.onMark8065(ts, px, nowMs, sell) } catch (_: Throwable) { false }) {
            try { chartExit7950(ts, px, nowMs, tick8144.tier1Only8144) } catch (_: Throwable) {}
            return px
        }
        if (px != null && px > 0.0 && ts.lastPriceUpdate > 0L && nowMs - ts.lastPriceUpdate <= FRESH_MARK_MS_7944) {
            try { onMark(ts, px, sell) } catch (_: Throwable) {}
        }
        try { chartExit7950(ts, px, nowMs, sell) } catch (_: Throwable) {}
        // V5.0.7967 — a grabbed runner's own exits (1m structure break, -35%).
        try { RunnerGrab7967.heldExit7967(ts, px, nowMs, sell) } catch (_: Throwable) {}
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

    /**
     * V5.0.8144 — the rung offered on this tick lost the arbitration (or its ticket): the tier goes back to the rung
     * confirmed or attempted before it, without spending one of its [MAX_REARMS_7944] re-arms.
     */
    fun restoreTier8144(ts: TokenState) {
        // V5.0.8153 — the pending tranche is SUPERSEDED (no backoff: it may ask again on the next print).
        try { RunnerLive8153.revert8153(ts, RunnerRungBook8153.Revert8153.SUPERSEDED, System.currentTimeMillis()) } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("SPIKE_CAPTURE_TIER_RESTORED_8144") } catch (_: Throwable) {}
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

    /**
     * When the next executable print skips several tiers, sell the compound
     * fraction of the remaining bag in one request, not only the last rung.
     */
    internal fun crossedTierFraction8079(tiers: List<Pair<Double, Double>>, done: Int, reached: Int): Double {
        if (done < 0 || reached <= done || reached > tiers.size) return 0.0
        var remainder = 1.0
        for (i in done until reached) remainder *= 1.0 - tiers[i].second.coerceIn(0.0, 1.0)
        return 1.0 - remainder
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
        // V5.0.8144 — a print after a full exit measures what the exit left on the table (post-exit opportunity).
        if (!pos.isOpen) { ExitTruthArbiterLive8144.notePostExit8144(ts.mint, priceUsd); return 0 }
        if (pos.entryPrice <= 0.0 || !priceUsd.isFinite() || priceUsd <= 0.0) return 0
        // V5.0.8144 — a lifecycle hold sells only its own takes (Executor refuses the rung at LifecycleHold8065.partialHeld8065);
        // on the print path the rung would only claim the arbitration ticket its own take needs.
        if (try { LifecycleHold8065.holding8065(ts) } catch (_: Throwable) { false }) return 0
        val gross = (priceUsd / pos.entryPrice - 1.0) * 100.0
        // A basis in the wrong units reads as a 100,000% "spike"; that is not a price.
        if (gross > 100_000.0) return 0
        // V5.0.7955 — the position's own learned ladder (lane x setup); TIERS when unlearned.
        val now7955 = System.currentTimeMillis()
        try { ExitProfile7955.notePeak7955(ts, gross, now7955) } catch (_: Throwable) {}
        val plan7955 = try { ExitProfile7955.planFor7955(ts, now7955) } catch (_: Throwable) { null }
        // V5.0.8018 — a position forming a run (structure firming at +25% or better) is promoted to the
        // runner hold before its scalp rung sells 60% at +40%; graded against that ladder on close.
        try { RunnerPlay8018.onMark8018(ts, gross, now7955) } catch (_: Throwable) {}
        // V5.0.7967 — a grabbed runner sells on the owner's ladder (half at 2x, then 5x, 11x).
        val runnerTiers8153 = try { RunnerGrab7967.tiersFor7967(ts) } catch (_: Throwable) { null }
        val tiers = runnerTiers8153 ?: plan7955?.tiers?.takeIf { it.isNotEmpty() } ?: TIERS
        val reached = tierReached(gross, tiers)
        if (reached == 0) return 0
        // V5.0.8153 — the rung lifecycle (RunnerRungBook8153): one pending tranche per position; while it is pending (a
        // sale in flight, or handed off inside its grace) no other request is made — no duplicate sell. A pending tranche
        // that never became a sale expires and is retryable; the CONFIRMED tier moves only on the chain-confirmed fill.
        val ladder8153 = RunnerLive8153.ladderFor8153(runnerTiers8153 != null)
        val inFlight8153 = com.lifecyclebot.engine.sell.SellExecutionLocks.isLocked(ts.mint) ||
            com.lifecyclebot.engine.sell.CloseLease.sellInFlight7948(ts.mint)
        if (!RunnerLive8153.settlePending8153(ts, inFlight8153, now7955)) return 0
        val done = RunnerLive8153.confirmedTier8153(ts, ladder8153)
        if (reached <= done) return 0
        // V5.0.8144 — another chosen exit (a structure break, an in-flight tranche) holds this position's arbitration
        // ticket: the rung waits for the next print — not consumed, no re-arm spent.
        if (ExitTruthArbiterLive8144.ticketHeldByOther8144(ts, "SPIKE_CAPTURE_7943_T${reached}_${gross.toInt()}PCT", now7955)) {
            try { PipelineHealthCollector.labelInc("SPIKE_CAPTURE_WAITS_FOR_TICKET_8144") } catch (_: Throwable) {}
            return 0
        }
        val reason8153 = "SPIKE_CAPTURE_7943_T${reached}_${gross.toInt()}PCT"
        // REQUESTED (refused inside a rejection's backoff: the tier waits, it is not consumed).
        if (!RunnerLive8153.request8153(ts, ladder8153, reached, reason8153, now7955)) return 0
        // V5.0.8136 — one residual share budget across the spike, runner, tail and lock ladders (RunnerExitLive8136):
        // a runner's residual is not sold by a ladder that overlaps another.
        // V5.0.8153 — a tier the budget refuses is REJECTED (backoff, retryable), never consumed.
        val frac = RunnerExitLive8136.budget8136(ts, crossedTierFraction8079(tiers, done, reached), reason8153)
        if (frac <= 0.0) {
            RunnerLive8153.revert8153(ts, RunnerRungBook8153.Revert8153.REJECTED, now7955, MAX_REARMS_7944)
            return 0
        }
        fired.incrementAndGet()
        try { ExitProfile7955.onTierFired7955(plan7955, reached) } catch (_: Throwable) {}
        try {
            PipelineHealthCollector.labelInc("SPIKE_CAPTURE_7943_TIER$reached")
            ForensicLogger.lifecycle(
                "SPIKE_CAPTURE_7943",
                "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${pos.tradingMode} gross=${"%.1f".format(gross)}% tier=$reached sell=${(frac * 100).toInt()}% key7955=${plan7955?.key ?: "PRIOR"}/${plan7955?.source ?: "PRIOR"} action=sell_into_spike",
            )
        } catch (_: Throwable) {}
        try {
            sell(ts, frac, reason8153)
        } catch (failure: Exception) {
            // Dispatch failed before the asynchronous executor accepted the sale:
            // do not permanently consume the spike's profit tiers.
            rearm(ts, reached)
            try { PipelineHealthCollector.labelInc("SPIKE_CAPTURE_DISPATCH_REARMED_8079") } catch (_: Throwable) {}
            return 0
        }
        if (rearms.size > 2_000) rearms.clear()
        return reached
    }

    /**
     * V5.0.7955 — PipelineHealthCollector.dumpText: the spike line, the mayhem line and the
     * learned exit profiles (ExitProfile7955: n per key, top keys' tiers/trail/maxHold, tier fires by key).
     */
    fun diagLines7955(): String =
        statusLine() + " · mayhem " + MayhemMode7943.statusLine() +
            // V5.0.8065 — the lifecycle holds.
            "\n  Lifecycle hold (§8065):      " + (try { LifecycleHold8065.statusLine() } catch (_: Throwable) { "unavailable" }) +
            "\n  Exit profiles (§7955):       " +
            (try { ExitProfile7955.statusLine7955() } catch (_: Throwable) { "unavailable" }) +
            // V5.0.7961 — fill price against decision price, per lane (feeds every label's cost).
            "\n  Entry chase (§7961):         " + (try { com.lifecyclebot.engine.truth.EntryChase7961.statusLine() } catch (_: Throwable) { "unavailable" }) +
            // V5.0.7962 — market structure (swings, HL_RECLAIM, learned structure-break exit) and the measured all-in cost.
            "\n  Structure (§7962):           " + (try { com.lifecyclebot.engine.chart.StructureTracker7962.statusLine7962() } catch (_: Throwable) { "unavailable" }) +
            "\n  Cost ledger (§7962):         " + (try { com.lifecyclebot.engine.truth.CostLedger7962.statusLine7962() } catch (_: Throwable) { "unavailable" }) +
            // V5.0.7962 — slots, size and priority on the best decision cells (Thompson bandit).
            "\n  Cell allocator (§7962):      " + (try { CellAllocator7962.statusLine() } catch (_: Throwable) { "unavailable" }) +
            // V5.0.7967 — runner grab / hold and the missed-runner audit.
            "\n  Runner grab (§7967):         " + (try { RunnerGrab7967.statusLine7967() } catch (_: Throwable) { "unavailable" }) +
            // V5.0.8293 — runs detected on the executed-trade tape and handed to MOONSHOT / SHITCOIN.
            "\n  Run tape (§8293):            " + (try { RunTape8293.statusLine8293() } catch (_: Throwable) { "unavailable" }) +
            // V5.0.8136 — the runner exit contract: capture loss, share budget, recovery tranches, unfunded tails.
            "\n  Runner exits (§8136):        " + (try { RunnerExitLive8136.statusLine8136() } catch (_: Throwable) { "unavailable" }) +
            "\n  Curve ticks (§7968):         " + (try { com.lifecyclebot.network.CurveTicks7968.statusLine7968() } catch (_: Throwable) { "unavailable" })

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
    /** V5.0.7979 — is this a mayhem-mode coin? Cached supply or the mcap/price-implied supply; no side effects. */
    fun isMayhem7979(ts: TokenState): Boolean {
        if (!ts.mint.endsWith("pump")) return false
        val onChain = try { com.lifecyclebot.engine.truth.OnChainSupplyAuthority7075.supplyOf7075(ts.mint) } catch (_: Throwable) { 0.0 }
        if (onChain > 0.0) return mayhemSupply(onChain)
        val implied = if (ts.lastMcap > 0.0 && ts.lastPrice > 0.0) ts.lastMcap / ts.lastPrice else 0.0
        return mayhemSupply(implied)
    }

    private val goodPassed7979 = java.util.concurrent.atomic.AtomicLong(0)

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
                // V5.0.7979 — owner: "only good mayhem coins can trade." A mayhem coin that matches a
                // promoted specialist whose combination includes mayhem (its own labels cleared +15%
                // net after 2 SE on 25+ mayhem decisions, still holding out of sample) is not refused.
                if (try { com.lifecyclebot.engine.truth.SpecialistMiner7972.goodMayhem7979(ts, nowMs) } catch (_: Throwable) { false } ||
                    // V5.0.7996 — or the Mayhem rule's own cell pays on the 4-hour ladder replay.
                    try { com.lifecyclebot.engine.truth.TailHunter7996.goodMayhemTail7996(ts, nowMs) } catch (_: Throwable) { false }) {
                    goodPassed7979.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("MAYHEM_GOOD_SPECIALIST_PASSED_7979") } catch (_: Throwable) {}
                    return null
                }
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

    fun statusLine(): String = "refusedOnChain=${refused.get()} waitedUnverified7947=${deferred.get()} clearedAfterWait7947=${cleared.get()} goodPassed7979=${goodPassed7979.get()}"
}
