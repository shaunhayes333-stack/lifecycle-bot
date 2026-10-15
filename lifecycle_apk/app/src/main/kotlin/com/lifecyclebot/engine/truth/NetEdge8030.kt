package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8030 — positive EV and net profit, always.
 *
 * Owner: "treasury scalps and all trades need to consider total cost — 3% is dumb, that just costs money ...
 * positive EV and net profit always." At a ~0.027 SOL ticket a round trip costs ~4-8% (venue fee both ways,
 * the priority/sender tip on each leg, slippage, impact in and out). Across the 152 live closes in this chat's
 * logs: 20 wins, average +13.1%, 8 of them under +5% — net losses booked as wins — and TREASURY / CASHGEN took
 * profit at 2.5-4%, profit locks armed at 3.5-4.9%, CYCLIC locked +1% / +3%, EXPRESS / SNIPER / MANIPULATED
 * sold at any green print. No exit read the measured cost.
 *
 * One cost figure ([costPct8030]): the lane's measured live round trip (CostLedger7962, once it has 8 trades)
 * or the model at the real ticket and pool depth (FieldManual7715.roundTripCostPct7766), whichever is higher.
 *
 *  ENTRY (live, every lane, hard — LiveEdgeGate7877): the lane's expected move must be at least [EDGE_MULT]x
 *        the round trip ([entryRefusal8030]). The expected move is the Field Manual lane mandate (runner 50%,
 *        momentum 30%, dip 25%, quality 20%, treasury 12%).
 *  EXIT  (live, the sell door — Executor): no profit-taking exit below net break-even + [NET_BUFFER_PCT]
 *        ([exitRefusal8030]). A green position under that line keeps its stop and time limit and holds; stops,
 *        rug / liquidity / dev exits, manual, emergency and operational exits are never held.
 */
object NetEdge8030 {
    const val EDGE_MULT = 2.0
    const val NET_BUFFER_PCT = 1.0
    const val DEFAULT_TICKET_SOL = 0.0273
    private val entryRefused = AtomicLong(0)
    private val exitHeld = AtomicLong(0)
    private val partialHeld = AtomicLong(0)
    private val giveBackReleased = AtomicLong(0)
    @Volatile private var lastCostPct = Double.NaN

    // ── pure ──

    /** The expected move clears [EDGE_MULT]x the round-trip cost. */
    fun entryClears8030(expectedGrossPct: Double, costPct: Double): Boolean =
        expectedGrossPct.isFinite() && costPct.isFinite() && expectedGrossPct >= EDGE_MULT * costPct

    /** The lowest gross gain a profit-taking exit may sell at: the round trip plus the net buffer. */
    fun exitFloorPct8030(costPct: Double): Double = (if (costPct.isFinite() && costPct > 0.0) costPct else 0.0) + NET_BUFFER_PCT

    /** An exit that must never be held for profit: stops, safety, operational, manual. */
    fun structural8030(reason: String): Boolean {
        val r = reason.uppercase()
        // A trailing stop or a profit lock on a green position is profit-taking, so "STOP" alone is not structural.
        return listOf("STOP_LOSS", "STRICT_SL", "CATASTROPH", "HARD_FLOOR", "BACKSTOP", "RUG", "DEV_", "DEV SOLD", "LIQUIDITY",
            "HONEYPOT", "FREEZE", "FROZEN", "MANUAL", "EMERGENCY", "KILL", "SHUTDOWN", "QUARANTINE", "ORPHAN", "RESURRECT",
            "STARTUP", "STALE_FEED", "EVICT", "DUST", "ZOMBIE", "RECONCIL", "DEAD_TOKEN", "NO_PRICE", "WALLET", "MAYHEM").any { r.contains(it) }
    }

    /** Is a green exit at [pnlPct] below the net floor (and not structural)? */
    fun profitExitHeld8030(pnlPct: Double, floorPct: Double, structural: Boolean): Boolean =
        !structural && pnlPct.isFinite() && pnlPct > 0.0 && pnlPct < floorPct

    /**
     * V5.0.8048 — a give-back exit (trail, profit lock, peak capture, drawdown) on a position whose peak already cleared
     * the net floor is protecting what is left, not taking a scalp. Holding it only waits for a worse print: 5.0.8046
     * MINERS peaked +15.8%, the trail fired at ~0% and was held; the profit-lock floor (cost+0.5, model cost) always
     * breached under this door's floor (cost+1, measured-or-model), so every lock sold at <= 0% as
     * REALIZED_LOSS_AFTER_PROFIT_SIGNAL. Pure.
     */
    fun giveBackReleased8048(reason: String, peakPct: Double, floorPct: Double): Boolean {
        if (!peakPct.isFinite() || !floorPct.isFinite() || peakPct < floorPct) return false
        val r = reason.uppercase()
        return listOf("TRAIL", "PROFIT_LOCK", "PEAK", "GIVEBACK", "GIVE_BACK", "DRAWDOWN", "BREAKEVEN", "BREAK_EVEN", "LOCK").any { r.contains(it) }
    }

    // ── live ──

    /** The round trip (%) for [lane] at [sizeSol] against [liquidityUsd]: measured or modelled, the higher. */
    fun costPct8030(lane: String, sizeSol: Double, liquidityUsd: Double): Double {
        val size = if (sizeSol.isFinite() && sizeSol > 0.0) sizeSol else DEFAULT_TICKET_SOL
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val sizeUsd = if (solUsd.isFinite() && solUsd > 0.0) size * solUsd else 0.0
        // V5.0.8037 — unknown depth (0) is not 20% impact here: on 8036 that priced every curve coin at a 23.5% round
        // trip and refused 80 entries. Unobserved liquidity has its own gates (ZERO_LIQUIDITY, Field Manual "no priced
        // liquidity"); this rule charges fees, tips and slippage and the impact only of depth it can see.
        val depthKnown = liquidityUsd.isFinite() && liquidityUsd > 0.0
        if (!depthKnown) unpricedDepth8037.incrementAndGet()
        val model = try { FieldManual7715.roundTripCostPct7766(size, if (depthKnown) sizeUsd else 0.0, if (depthKnown) liquidityUsd else 0.0) } catch (_: Throwable) { 5.0 }
        val measured = try { CostLedger7962.measuredCostPct7962(lane, size) } catch (_: Throwable) { null }
        return maxOf(model, measured ?: 0.0).also { lastCostPct = it }
    }

    /** LiveEdgeGate7877 (live): the refusal when [lane]'s expected move is under 2x the round trip, else null. */
    fun entryRefusal8030(ts: TokenState, lane: String): String? {
        val mandate = try { FieldManual7715.mandateFor(lane).expectedGrossPct } catch (_: Throwable) { return null }
        // V5.0.8035 — owner: "why is it buying bonk ... wouldn't this be common sense everywhere". The lane's textbook
        // move (20% for quality / bluechip) let BLUECHIP buy BONK ($300M cap) whose hour moves 1-3%. Every lane now
        // expects no more than the coin itself is actually moving: its last hour's range (or 1h change).
        val realized = try { realizedMovePct8035(ts) } catch (_: Throwable) { null }
        val expected = expectedMove8035(mandate, realized)
        val cost = costPct8030(lane, DEFAULT_TICKET_SOL, liquidity8037(ts))
        if (entryClears8030(expected, cost)) return null
        entryRefused.incrementAndGet()
        val slow = realized != null && realized < mandate
        if (slow) slowRefused8035.incrementAndGet()
        try { PipelineHealthCollector.labelInc("NET_EDGE_ENTRY_REFUSED_8030_${lane.uppercase().take(16)}") } catch (_: Throwable) {}
        return if (slow) "NET_EDGE_COIN_TOO_SLOW_8035" else "NET_EDGE_BELOW_2X_COST_8030"
    }

    /**
     * V5.0.8136 — CandidateAdmission8136: the same expected executable move [entryRefusal8030] judges (the lane mandate
     * capped by what the coin is actually moving), or NaN when the lane has no mandate. No counters.
     */
    fun expectedGrossPct8136(ts: TokenState, lane: String): Double {
        val mandate = try { FieldManual7715.mandateFor(lane).expectedGrossPct } catch (_: Throwable) { return Double.NaN }
        val realized = try { realizedMovePct8035(ts) } catch (_: Throwable) { null }
        return expectedMove8035(mandate, realized)
    }

    /** V5.0.8136 — the round trip [entryRefusal8030] charges this entry: the default ticket against the observed depth. */
    fun entryCostPct8136(ts: TokenState, lane: String): Double = costPct8030(lane, DEFAULT_TICKET_SOL, liquidity8037(ts))

    private val slowRefused8035 = AtomicLong(0)

    // ── V5.0.8054 — lane fit: scalp lanes need a coin that moves; swing lanes don't buy the top of a moonshot ──
    //
    // Owner (wallet screenshot, 5.0.8051): CASHGEN bought NEET ($53.6M cap), OTC ($4.6M) and SLOPCORE ($1.9M) — two
    // stopped out within minutes; QUALITY bought Normie at a $364K cap after a +4,576% day (now -17%). "yes!!" to:
    // stop CASHGEN buying big caps that barely move, stop QUALITY buying coins that just made a huge run.
    private val SCALP_LANES_8054 = setOf("CASHGEN", "TREASURY")
    private val SWING_LANES_8054 = setOf("QUALITY", "BLUECHIP")
    private const val SCALP_EDGE_MULT_8054 = 3.0
    private const val OVEREXTENDED_1H_PCT_8054 = 100.0
    private const val OVEREXTENDED_LAUNCH_MULT_8054 = 50.0
    private const val OVEREXTENDED_MAX_AGE_MIN_8054 = 48.0 * 60.0
    private const val LAUNCH_CAP_SOL_8054 = 28.0
    private val scalpRefused8054 = AtomicLong(0)
    private val overextendedRefused8054 = AtomicLong(0)

    /** Pure: a scalp lane needs the coin's own last-hour move (known) at 3x the round trip. */
    fun scalpMoveTooSmall8054(realizedPct: Double?, costPct: Double): Boolean =
        realizedPct == null || !realizedPct.isFinite() || !(costPct.isFinite() && costPct > 0.0) || realizedPct < SCALP_EDGE_MULT_8054 * costPct

    /**
     * Pure: a coin that already made its run — up 100%+ in the last hour, or a launchpad coin under 48 h old already
     * at 50x+ its launch cap (pump.fun opens at ~28 SOL; Normie $364K = ~83x).
     */
    fun overextended8054(change1hPct: Double, launchpad: Boolean, ageMin: Double?, capSol: Double): Boolean =
        (change1hPct.isFinite() && change1hPct >= OVEREXTENDED_1H_PCT_8054) ||
            (launchpad && ageMin != null && ageMin.isFinite() && ageMin <= OVEREXTENDED_MAX_AGE_MIN_8054 &&
                capSol.isFinite() && capSol >= OVEREXTENDED_LAUNCH_MULT_8054 * LAUNCH_CAP_SOL_8054)

    /** LiveEdgeGate7877 (live): the scalp-lane and swing-lane fit refusals, or null. */
    fun laneFitRefusal8054(ts: TokenState, laneRaw: String): String? {
        val lane = laneRaw.trim().uppercase()
        if (lane in SCALP_LANES_8054) {
            val realized = try { realizedMovePct8035(ts) } catch (_: Throwable) { null }
            val cost = costPct8030(lane, DEFAULT_TICKET_SOL, liquidity8037(ts))
            if (scalpMoveTooSmall8054(realized, cost)) {
                scalpRefused8054.incrementAndGet()
                try { PipelineHealthCollector.labelInc("NET_EDGE_SCALP_COIN_TOO_FLAT_8054_$lane") } catch (_: Throwable) {}
                return "NET_EDGE_SCALP_COIN_TOO_FLAT_8054"
            }
        }
        if (lane in SWING_LANES_8054) {
            val launchpad = ts.mint.endsWith("pump", true) || ts.mint.endsWith("bonk", true) ||
                (try { com.lifecyclebot.network.PumpCurveKeys7269.keyFor(ts.mint) } catch (_: Throwable) { null }) != null
            val age = try { CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts) } catch (_: Throwable) { null }
            val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
            val capSol = try { EconomicUnitInvariant7061.usdToSol(TrustedMcap8019.mcap8019(ts), solUsd) } catch (_: Throwable) { Double.NaN }
            if (overextended8054(ts.lastPriceChange1h, launchpad, age, capSol)) {
                overextendedRefused8054.incrementAndGet()
                try { PipelineHealthCollector.labelInc("SWING_LANE_OVEREXTENDED_8054_$lane") } catch (_: Throwable) {}
                return "SWING_LANE_ALREADY_RAN_8054"
            }
        }
        return null
    }
    private val cryptoRefused8042 = AtomicLong(0)

    /**
     * V5.0.8042 — the crypto lane through the same rule. 5.0.8041: CRYPTO_ALT bought SHIB live (a large-cap wrapped
     * token whose day moves a few percent) on a lane whose 120 live closes read WR 19%, -6.7% a trade, -0.69 SOL. The
     * expected move is no more than the coin's own 24 h move (the crypto mandate caps it); it must clear 2x the round
     * trip at this size and depth. Returns the refusal, or null.
     */
    fun cryptoEntryRefusal8042(change24hPct: Double, sizeSol: Double, liquidityUsd: Double): String? {
        val mandate = try { FieldManual7715.mandateFor("CRYPTO_ALT").expectedGrossPct } catch (_: Throwable) { 12.0 }
        val realized = kotlin.math.abs(change24hPct).takeIf { it.isFinite() }
        val cost = costPct8030("CRYPTO_ALT", sizeSol, liquidityUsd)
        if (entryClears8030(expectedMove8035(mandate, realized), cost)) return null
        cryptoRefused8042.incrementAndGet()
        try { PipelineHealthCollector.labelInc("NET_EDGE_CRYPTO_REFUSED_8042") } catch (_: Throwable) {}
        return "NET_EDGE_CRYPTO_TOO_SLOW_8042"
    }
    private val unpricedDepth8037 = AtomicLong(0)

    private fun liquidity8037(ts: TokenState): Double =
        try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }

    /** Pure: the move to expect — the lane's mandate, capped by what the coin is actually moving when that is known. */
    fun expectedMove8035(mandatePct: Double, realizedPct: Double?): Double =
        if (realizedPct != null && realizedPct.isFinite() && realizedPct >= 0.0) minOf(mandatePct, realizedPct) else mandatePct

    /** Pure: the range (%) of [prices] (the last hour's prints), or null with fewer than [minPrints]. */
    fun rangePct8035(prices: List<Double>, minPrints: Int = 6): Double? {
        val p = prices.filter { it.isFinite() && it > 0.0 }
        if (p.size < minPrints) return null
        return (p.max() / p.min() - 1.0) * 100.0
    }

    /** The coin's own recent move: the last hour's high-low range from its real candles, or its 1h change; null if unknown. */
    private fun realizedMovePct8035(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Double? {
        val bars = synchronized(ts.history) { ts.history.filter { !it.synthetic && nowMs - it.ts in 0L..3_600_000L }.toList() }
        val range = rangePct8035(bars.flatMap { listOf(it.priceUsd, it.highUsd, it.lowUsd) })
        val h1 = kotlin.math.abs(ts.lastPriceChange1h).takeIf { it.isFinite() && it > 0.0 }
        return when {
            range != null && h1 != null -> maxOf(range, h1)
            else -> range ?: h1
        }
    }

    /** Executor sell door (live): true holds a green profit-taking exit that would not clear the net floor. */
    fun exitRefusal8030(ts: TokenState, reason: String, markPrice: Double, partial: Boolean): Boolean {
        val pos = ts.position
        if (!pos.isOpen || pos.isPaperPosition || !(pos.entryPrice > 0.0) || !(markPrice > 0.0)) return false
        val pnl = (markPrice / pos.entryPrice - 1.0) * 100.0
        if (!(pnl > 0.0)) return false
        val structural = structural8030(reason)
        if (structural) return false
        try { OnChainDepth8038.request8038(ts) } catch (_: Throwable) {}
        val cost = costPct8030(pos.tradingMode.ifBlank { "UNKNOWN" }, pos.costSol, liquidity8037(ts))
        val floor = exitFloorPct8030(cost)
        if (!profitExitHeld8030(pnl, floor, structural)) return false
        val peak = if (pos.highestPrice > 0.0) (pos.highestPrice / pos.entryPrice - 1.0) * 100.0 else Double.NaN
        if (giveBackReleased8048(reason, peak, floor)) {
            giveBackReleased.incrementAndGet()
            try { PipelineHealthCollector.labelInc("NET_PROFIT_GIVEBACK_RELEASED_8048") } catch (_: Throwable) {}
            return false
        }
        if (partial) partialHeld.incrementAndGet() else exitHeld.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(if (partial) "NET_PROFIT_PARTIAL_HELD_8030" else "NET_PROFIT_EXIT_HELD_8030")
            if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("NET_PROFIT_8030", ts.mint)) {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "NET_PROFIT_EXIT_HELD_8030",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${pos.tradingMode} pnl=${"%.2f".format(pnl)} cost=${"%.2f".format(cost)} floor=${"%.2f".format(floor)} partial=$partial reason=${reason.take(50)}",
                )
            }
        } catch (_: Throwable) {}
        return true
    }

    fun statusLine(): String =
        "entryRefused=${entryRefused.get()} coinTooSlow=${slowRefused8035.get()} scalpTooFlat8054=${scalpRefused8054.get()} swingAlreadyRan8054=${overextendedRefused8054.get()} cryptoRefused=${cryptoRefused8042.get()} depthUnknown=${unpricedDepth8037.get()} profitExitsHeld=${exitHeld.get()} giveBackReleased8048=${giveBackReleased.get()} partialsHeld=${partialHeld.get()} lastCost=${if (lastCostPct.isFinite()) "%.2f".format(lastCostPct) + "%" else "-"} " +
            "rule=entry move>=${EDGE_MULT.toInt()}x cost · exit only at >= cost+${NET_BUFFER_PCT}% (stops never held)"
}
