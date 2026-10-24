package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8151 — WHAT A LIVE ROUND TRIP COSTS, AND THE PARTS OF IT THAT CAN BE CUT.
 *
 * Live equity ~0.40 SOL, meme buys seal near 0.029 SOL. At that size the fixed per-transaction costs dominate:
 *
 *  - every entry was priced like an emergency: Jupiter's CU price was the Helius "High" estimate (PriorityFee8009),
 *    every PumpPortal trade paid >= 0.0002 SOL priority, and every Helius Sender transaction carried a 0.0002 SOL tip
 *    although the Sender endpoint in use (`/fast?swqos_only=true`, HeliusSender) only requires 0.000005 SOL. Two such
 *    legs are ~2.8% of a 0.029 SOL trade before any venue fee.
 *  - a pump mint whose curve is confirmed COMPLETE (PumpSwap / Raydium, SellRouteVenueProbe8144) still bought through
 *    PumpPortal, whose local-trade API charges 0.5% per trade; the aggregator routes the same pool without it.
 *  - PumpPortal buys were broadcast without a preflight simulation (Sender and the fan-out skip preflight), so a doomed
 *    buy still paid its fees on chain.
 *  - NetEdge8030's model priced the trip with fixed constants (1% "platform", 0.000205 SOL a leg): no app fee, no
 *    PumpPortal fee, the curve's 1.25% a side read as 0.5%.
 *
 * Urgency decides what a transaction pays ([Urgency8151]): EMERGENCY (tier 1 / drain) and URGENT (tiers 2-3, a
 * grabbed runner, a fast-land exit) keep every fee exactly as before; ORDINARY (discretionary entries, top-ups,
 * non-urgent profit slices) pays the market's MEDIUM priority, capped against the trade size, and the Sender minimum
 * tip with headroom. Pure (no Android, no network); [CostMeter8151] holds the live measurements.
 */
object CostTrim8151 {

    enum class Urgency8151 { EMERGENCY, URGENT, ORDINARY }

    // ── constants (each mirrors the code that charges it) ──

    /** Executor.MEME_TRADING_FEE_PERCENT (0.005): the app's own live trading fee, per side, in percent. */
    const val APP_FEE_PCT_PER_SIDE_8151 = 0.5
    /** PumpPortal local-trade API fee, per trade, in percent. */
    const val PUMPPORTAL_FEE_PCT_PER_SIDE_8151 = 0.5
    /** Jupiter: Metis /swap charges no platform fee here; Ultra charges a few bps. Upper bound, per side. */
    internal const val AGGREGATOR_FEE_PCT_PER_SIDE_8151 = 0.05
    /** HeliusSender posts to `/fast?swqos_only=true`, whose minimum tip is 5,000 lamports (HeliusSenderEnvelope7250). */
    internal const val SWQOS_MIN_TIP_LAMPORTS_8151 = 5_000L
    /** An ordinary transaction's Sender tip: 10x the SWQOS minimum (was the 200,000-lamport dual-routing figure). */
    internal const val ORDINARY_SENDER_TIP_LAMPORTS_8151 = 50_000L
    /** An ordinary transaction's CU priority spend may be at most this share of the trade. */
    internal const val ORDINARY_PRIORITY_SHARE_8151 = 0.003
    /** Compute units a routed swap is costed at when its CU price is derived from a SOL budget. */
    internal const val EST_SWAP_CU_8151 = 300_000L
    /** Ordinary PumpPortal priority fee bounds (SOL) and the value used when there is no market reading. */
    internal const val ORDINARY_PUMP_MIN_SOL_8151 = 0.00002
    internal const val ORDINARY_PUMP_FALLBACK_SOL_8151 = 0.00005
    internal const val ORDINARY_PUMP_CAP_SOL_8151 = 0.0001
    /** Network base fee per signature. */
    internal const val BASE_FEE_LAMPORTS_8151 = 5_000L
    /** Fixed cost of one leg (fee + priority + tip) before any live leg was measured. */
    internal const val FALLBACK_FIXED_LEG_SOL_8151 = 0.0003
    /** Associated token account rent (2,039,280 lamports; Token-2022 accounts are larger). */
    const val ATA_RENT_SOL_8151 = 0.00203928
    /** Ordinary PumpPortal buy slippage floor (percent): enough for a slot or two of normal curve movement. */
    internal const val ORDINARY_PUMP_SLIP_FLOOR_PCT_8151 = 6
    /** A targeted rent close waits this long after the confirmed full exit (balances settle, nothing in flight). */
    const val TARGETED_CLOSE_DELAY_MS_8151 = 60_000L

    /** A partial slice is not sold when its fixed fees exceed this share of its proceeds (tiers 1-3 exempt). */
    @Volatile var partialFixedFeeMaxShare8151: Double = 0.10

    // ── urgency ──

    /** Pure: the urgency of a transaction. [tierRank] is RunnerExitContract8136.Tier8136.rank (8 = ordinary / entry). */
    fun urgencyOf8151(tierRank: Int, drain: Boolean, fastLand: Boolean): Urgency8151 = when {
        drain || tierRank == 1 -> Urgency8151.EMERGENCY
        fastLand || tierRank == 2 || tierRank == 3 -> Urgency8151.URGENT
        else -> Urgency8151.ORDINARY
    }

    // ── priority fees and tips ──

    /** Pure: the Sender tip for [urgency]. Only ORDINARY changes: capped at the ordinary tip, never below the minimum. */
    fun senderTipLamports8151(urgency: Urgency8151, legacyTipLamports: Long): Long =
        if (urgency != Urgency8151.ORDINARY) legacyTipLamports
        else legacyTipLamports.coerceAtMost(ORDINARY_SENDER_TIP_LAMPORTS_8151).coerceAtLeast(SWQOS_MIN_TIP_LAMPORTS_8151)

    /**
     * Pure: an ORDINARY transaction's CU price (micro-lamports). The market's medium reading (high, then [floorMicro],
     * when there is none), never above the high reading, and never spending more than [ORDINARY_PRIORITY_SHARE_8151]
     * of [tradeLamports] over [EST_SWAP_CU_8151] units; never below [floorMicro].
     */
    fun ordinaryMicroLamports8151(mediumMicro: Long, highMicro: Long, tradeLamports: Long, floorMicro: Long): Long {
        val floor = floorMicro.coerceAtLeast(1L)
        val base = when {
            mediumMicro > 0L -> mediumMicro
            highMicro > 0L -> highMicro
            else -> floor
        }
        var r = base
        if (tradeLamports > 0L) {
            val capMicro = (tradeLamports.toDouble() * ORDINARY_PRIORITY_SHARE_8151 * 1_000_000.0 / EST_SWAP_CU_8151).toLong()
            r = minOf(r, capMicro)
        }
        if (highMicro > 0L) r = minOf(r, highMicro)
        return maxOf(floor, r)
    }

    /**
     * Pure: an ORDINARY PumpPortal priority fee (SOL). The medium reading converted to SOL ([mediumSol], fallback when
     * none), capped by the ordinary cap, the legacy fee and [ORDINARY_PRIORITY_SHARE_8151] of [tradeSol] (when known);
     * never below [ORDINARY_PUMP_MIN_SOL_8151].
     */
    fun ordinaryPumpSol8151(mediumSol: Double, legacySol: Double, tradeSol: Double): Double {
        val base = if (mediumSol.isFinite() && mediumSol > 0.0) mediumSol else ORDINARY_PUMP_FALLBACK_SOL_8151
        var cap = ORDINARY_PUMP_CAP_SOL_8151
        if (legacySol.isFinite() && legacySol > 0.0) cap = minOf(cap, legacySol)
        if (tradeSol.isFinite() && tradeSol > 0.0) cap = minOf(cap, tradeSol * ORDINARY_PRIORITY_SHARE_8151)
        return base.coerceAtMost(maxOf(ORDINARY_PUMP_MIN_SOL_8151, cap)).coerceAtLeast(ORDINARY_PUMP_MIN_SOL_8151)
    }

    // ── slippage ──

    /**
     * Pure: an ORDINARY PumpPortal buy's slippage (percent). Three times the one-way price impact plus the floor,
     * never above the legacy tolerance; an unknown impact keeps the legacy tolerance.
     */
    fun ordinaryPumpBuySlipPct8151(impactPctOneWay: Double, legacyPct: Int): Int {
        if (!impactPctOneWay.isFinite() || impactPctOneWay < 0.0) return legacyPct
        val want = kotlin.math.ceil(3.0 * impactPctOneWay).toInt() + ORDINARY_PUMP_SLIP_FLOOR_PCT_8151
        return want.coerceAtMost(legacyPct).coerceAtLeast(minOf(ORDINARY_PUMP_SLIP_FLOOR_PCT_8151, legacyPct))
    }

    // ── simulation before send ──

    /** Pure: a JupiterApi.simulateSwap reply that proves the transaction would fail (not an unavailable simulator). */
    fun simDefiniteReject8151(err: String?): Boolean =
        err != null && err.startsWith("Simulate failed:") && !err.startsWith("Simulate failed: null")

    // ── route cost term ──

    /** Pure: the route's own fee per side, percent ("PUMPPORTAL", "JUPITER", "JUPITER_ULTRA", "RAYDIUM"). */
    fun routeFeePctPerSide8151(route: String): Double = when (route.uppercase()) {
        "PUMPPORTAL" -> PUMPPORTAL_FEE_PCT_PER_SIDE_8151
        "JUPITER", "JUPITER_ULTRA" -> AGGREGATOR_FEE_PCT_PER_SIDE_8151
        else -> 0.0
    }

    /**
     * Pure: should an ORDINARY buy of a PumpPortal-eligible mint go to the aggregator first because it is the cheaper
     * executable route? Only when the venue is CONFIRMED on chain as a complete curve's pool ("PUMPSWAP") or a Raydium
     * pool ("RAYDIUM") — pools the aggregator routes — and only once per mint per window ([recentlyTried] = the
     * previous attempt already went aggregator-first, so a second attempt keeps PumpPortal-first and the cut can never
     * become a no-buy choke).
     */
    fun aggregatorFirstForCost8151(urgency: Urgency8151, confirmedVenue: String, pumpEligible: Boolean, recentlyTried: Boolean): Boolean =
        urgency == Urgency8151.ORDINARY && pumpEligible && !recentlyTried &&
            (confirmedVenue == "PUMPSWAP" || confirmedVenue == "RAYDIUM") &&
            routeFeePctPerSide8151("PUMPPORTAL") > routeFeePctPerSide8151("JUPITER")

    // ── round-trip economics ──

    /**
     * Pure: the round trip (percent of [tradeSol]) from its components — venue, route and app fees per side (both
     * ways), two fixed legs and any rent not reclaimed. NaN for a non-positive trade.
     */
    fun roundTripPct8151(venuePctPerSide: Double, routePctPerSide: Double, appPctPerSide: Double,
                         fixedLegSol: Double, unreclaimedRentSol: Double, tradeSol: Double): Double {
        if (!tradeSol.isFinite() || tradeSol <= 0.0) return Double.NaN
        val pct = 2.0 * (nn(venuePctPerSide) + nn(routePctPerSide) + nn(appPctPerSide))
        val fixed = 2.0 * nn(fixedLegSol) + nn(unreclaimedRentSol)
        return pct + fixed / tradeSol * 100.0
    }

    /** Pure: the fixed (size-independent) share of a round trip, percent of [tradeSol]. */
    fun fixedCostSharePct8151(fixedLegSol: Double, unreclaimedRentSol: Double, tradeSol: Double): Double =
        if (!tradeSol.isFinite() || tradeSol <= 0.0) Double.NaN else (2.0 * nn(fixedLegSol) + nn(unreclaimedRentSol)) / tradeSol * 100.0

    /**
     * Pure: the gross move a trade needs to break even when the round trip costs [roundTripPct]. Buying costs
     * b = rt/2 and selling s = rt/2 of notional: (1 + g)(1 - s) = 1 + b  =>  g = (1 + b) / (1 - s) - 1.
     */
    fun breakEvenMovePct8151(roundTripPct: Double): Double {
        if (!roundTripPct.isFinite() || roundTripPct < 0.0) return Double.NaN
        val half = roundTripPct / 200.0
        if (half >= 1.0) return Double.POSITIVE_INFINITY
        return ((1.0 + half) / (1.0 - half) - 1.0) * 100.0
    }

    // ── partial slices ──

    /**
     * Pure: a partial slice whose fixed fees ([fixedFeeSol]) exceed [maxShare] of its proceeds costs more than it is
     * worth to send. Tier 1-3 exits ([tierRank] 1..3: emergency, hard risk, runner continuation) are never held; an
     * unknown proceeds figure never holds.
     */
    fun partialFixedFeeRefused8151(fixedFeeSol: Double, proceedsSol: Double, maxShare: Double, tierRank: Int): Boolean {
        if (tierRank in 1..3) return false
        if (!proceedsSol.isFinite() || proceedsSol <= 0.0 || !fixedFeeSol.isFinite() || fixedFeeSol <= 0.0) return false
        if (!maxShare.isFinite() || maxShare <= 0.0) return false
        return fixedFeeSol / proceedsSol > maxShare
    }

    // ── rent: close after a confirmed zero ──

    /**
     * Pure: may the token account of a mint the bot just fully exited be closed now? Only when the on-chain amount is
     * exactly "0", the account is initialized (a frozen account reads "frozen" and is never closed), the bot holds no
     * open position, no signed buy or sell for the mint is unresolved, the tracker reads a terminal state (priority
     * >= 8, unknown = not the bot's, refused), it is not a dust/manual-ignored holding, and the settle delay passed.
     */
    fun targetedCloseEligible8151(amountRaw: String, state: String, positionOpen: Boolean, unresolvedSigned: Boolean,
                                  trackerPriority: Int?, dustIgnored: Boolean, dueAtMs: Long, nowMs: Long): Boolean =
        amountRaw == "0" && state == "initialized" && !positionOpen && !unresolvedSigned &&
            trackerPriority != null && trackerPriority >= 8 && !dustIgnored && nowMs >= dueAtMs

    /** Pure: exponentially weighted mean; the first sample seeds it. */
    fun ewma8151(prev: Double, sample: Double, alpha: Double): Double = when {
        !sample.isFinite() -> prev
        !prev.isFinite() || prev <= 0.0 -> sample
        else -> prev + alpha.coerceIn(0.0, 1.0) * (sample - prev)
    }

    private fun nn(v: Double): Double = if (v.isFinite() && v > 0.0) v else 0.0
}

/**
 * V5.0.8151 — live cost measurements: network fee per confirmed leg (TradeVerifier meta.fee, which includes the CU
 * priority), the Sender tip each send set, rent opened and closed, failed legs that still paid, and what the cuts did.
 * In memory only; no Android.
 */
object CostMeter8151 {
    private const val ALPHA = 0.2
    @Volatile private var feeLamportsEwma = Double.NaN
    @Volatile private var tipLamportsEwma = Double.NaN
    @Volatile private var lastTicketSol = Double.NaN
    @Volatile private var lastRoundTripPct = Double.NaN
    private val legs = AtomicLong(0)
    private val failedLegs = AtomicLong(0)
    private val failedFeeLamports = AtomicLong(0)
    private val rentOpenedLamports = AtomicLong(0)
    private val rentClosedInTxLamports = AtomicLong(0)
    private val ordinaryTips = AtomicLong(0)
    private val tipSavedLamports = AtomicLong(0)
    private val aggregatorFirst = AtomicLong(0)
    private val simRefused = AtomicLong(0)
    private val partialFixedHeld = AtomicLong(0)
    private val aggregatorTriedAt = ConcurrentHashMap<String, Long>()
    private const val AGGREGATOR_RETRY_WINDOW_MS = 10L * 60_000L

    /** TradeVerifier: one confirmed leg's network fee (base + priority) and the rent it opened / closed. */
    fun noteLeg8151(feeLamports: Long, rentOpened: Long, rentClosed: Long) {
        legs.incrementAndGet()
        if (feeLamports > 0L) feeLamportsEwma = CostTrim8151.ewma8151(feeLamportsEwma, feeLamports.toDouble(), ALPHA)
        if (rentOpened > 0L) rentOpenedLamports.addAndGet(rentOpened)
        if (rentClosed > 0L) rentClosedInTxLamports.addAndGet(rentClosed)
    }

    /** TradeVerifier: a leg that failed on chain still paid its fee. */
    fun noteFailedLeg8151(feeLamports: Long) {
        failedLegs.incrementAndGet()
        if (feeLamports > 0L) failedFeeLamports.addAndGet(feeLamports)
    }

    /** V5.0.8366 — the average fee (SOL) a failed leg paid (ShadowAlign8366's unlanded entry); the fixed-leg fallback before one failed. */
    fun failedLegFeeSol8366(): Double {
        val n = failedLegs.get()
        val sum = failedFeeLamports.get()
        return if (n > 0L && sum > 0L) sum.toDouble() / n.toDouble() / 1e9 else CostTrim8151.FALLBACK_FIXED_LEG_SOL_8151
    }

    /** The Sender tip a send carries ([legacy] = what the old policy would have paid); [entry] = a buy leg. */
    fun noteTip8151(tipLamports: Long, legacy: Long, entry: Boolean) {
        if (entry && tipLamports > 0L) tipLamportsEwma = CostTrim8151.ewma8151(tipLamportsEwma, tipLamports.toDouble(), ALPHA)
        if (entry) LandingMeter8169.noteEntryTip8169(tipLamports)   // V5.0.8169 — per-buy tip for the landing report
        if (legacy > tipLamports) { ordinaryTips.incrementAndGet(); tipSavedLamports.addAndGet(legacy - tipLamports) }
    }

    /** Sell legs outside the ordinary-slice path keep the legacy tip (Executor.effectiveSenderTipLamports floor). */
    private const val SELL_TIP_ASSUMED_LAMPORTS = 200_000.0

    /**
     * One round trip's fixed cost (SOL): two measured network fees (base + priority, TradeVerifier), the measured entry
     * tip and the legacy sell tip; the fallback before any leg was measured. Conservative: an ordinary slice that paid
     * less still counts the legacy sell tip.
     */
    fun roundTripFixedSol8151(): Double {
        val fee = feeLamportsEwma
        val tip = tipLamportsEwma
        if (!fee.isFinite() && !tip.isFinite()) return 2.0 * CostTrim8151.FALLBACK_FIXED_LEG_SOL_8151
        val feePart = if (fee.isFinite()) fee else CostTrim8151.BASE_FEE_LAMPORTS_8151.toDouble()
        val entryTip = if (tip.isFinite()) tip else SELL_TIP_ASSUMED_LAMPORTS
        return (2.0 * feePart + entryTip + SELL_TIP_ASSUMED_LAMPORTS) / 1e9
    }

    /** One sell leg's fixed cost (SOL): measured network fee + the legacy sell tip; the fallback before any leg. */
    fun sellLegFixedSol8151(): Double {
        val fee = feeLamportsEwma
        return if (!fee.isFinite()) CostTrim8151.FALLBACK_FIXED_LEG_SOL_8151 else (fee + SELL_TIP_ASSUMED_LAMPORTS) / 1e9
    }

    private val boundedExec8151: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.ThreadPoolExecutor(2, 2, 30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(16),
            java.util.concurrent.ThreadFactory { r -> Thread(r, "cost-sim-8151").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
    }

    /** Run [block] for at most [budgetMs]; null when it did not answer in time (the caller proceeds as before). */
    fun runBounded8151(budgetMs: Long, block: () -> String?): String? {
        val task = try { boundedExec8151.submit(java.util.concurrent.Callable<String?> { block() }) }
            catch (_: java.util.concurrent.RejectedExecutionException) { return null }
        return try { task.get(budgetMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }
        catch (_: Exception) { null }
        finally { if (!task.isDone) task.cancel(true) }
    }

    fun noteTicket8151(sol: Double) { if (sol.isFinite() && sol > 0.0) lastTicketSol = sol }
    fun noteSimRefused8151() { simRefused.incrementAndGet() }
    fun notePartialFixedHeld8151() { partialFixedHeld.incrementAndGet() }

    /** Aggregator-first was chosen for [mint] now (one try per window). */
    fun noteAggregatorFirst8151(mint: String, nowMs: Long = System.currentTimeMillis()) {
        aggregatorFirst.incrementAndGet()
        aggregatorTriedAt[mint] = nowMs
        if (aggregatorTriedAt.size > 500) aggregatorTriedAt.entries.removeIf { nowMs - it.value > AGGREGATOR_RETRY_WINDOW_MS }
    }

    fun aggregatorTriedRecently8151(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = aggregatorTriedAt[mint] ?: return false
        return nowMs - at in 0L..AGGREGATOR_RETRY_WINDOW_MS
    }

    fun noteRoundTrip8151(pct: Double) { if (pct.isFinite()) lastRoundTripPct = pct }

    fun ticketSol8151(fallback: Double): Double = lastTicketSol.takeIf { it.isFinite() && it > 0.0 } ?: fallback

    fun statusLine8151(ticketFallbackSol: Double = 0.0273): String {
        val ticket = ticketSol8151(ticketFallbackSol)
        val fixed = roundTripFixedSol8151()
        val share = CostTrim8151.fixedCostSharePct8151(fixed / 2.0, 0.0, ticket)
        val rt = lastRoundTripPct
        return "ticket=${"%.4f".format(ticket)} fixedRoundTrip=${"%.6f".format(fixed)} fixedShare=${if (share.isFinite()) "%.2f".format(share) + "%" else "-"} " +
            "feesRoundTrip=${if (rt.isFinite()) "%.2f".format(rt) + "%" else "-"} breakEvenMove=${if (rt.isFinite()) "%.2f".format(CostTrim8151.breakEvenMovePct8151(rt)) + "%" else "-"} " +
            "legs=${legs.get()} failedLegs=${failedLegs.get()} failedFeeSol=${"%.6f".format(failedFeeLamports.get() / 1e9)} " +
            "ordinaryTips=${ordinaryTips.get()} tipSavedSol=${"%.6f".format(tipSavedLamports.get() / 1e9)} aggregatorFirst=${aggregatorFirst.get()} " +
            "simRefused=${simRefused.get()} partialFixedHeld=${partialFixedHeld.get()} rentOpenedSol=${"%.5f".format(rentOpenedLamports.get() / 1e9)} " +
            "rentClosedInTxSol=${"%.5f".format(rentClosedInTxLamports.get() / 1e9)} appFee=${CostTrim8151.APP_FEE_PCT_PER_SIDE_8151}%/side" +
            " " + landing8169()
    }

    /** V5.0.8169 — landing per route, what each buy paid to land, and the Sender minimum check. */
    private fun landing8169(): String = try {
        LandingMeter8169.statusLine8169(
            com.lifecyclebot.network.SignedTxLedger8085.landRows8169(),
            com.lifecyclebot.network.HeliusSender.minTipLamports8169(),
            CostTrim8151.ORDINARY_SENDER_TIP_LAMPORTS_8151,
            com.lifecyclebot.network.PriorityFee8009.mediumMicro8169(),
        ) + " fixedFloor8169=${"%.4f".format(CostLanding8169.fixedCostFloorSol8169(roundTripFixedSol8151()))}SOL@${CostLanding8169.MAX_FIXED_SHARE_PCT_8169}%" +
            " " + com.lifecyclebot.engine.truth.LaunchCohort8173.statusLine8173()   // V5.0.8173 — launch-cohort landing per route
    } catch (_: Throwable) { "landing8169=-" }

    internal fun resetForTest8151() {
        feeLamportsEwma = Double.NaN; tipLamportsEwma = Double.NaN; lastTicketSol = Double.NaN; lastRoundTripPct = Double.NaN
        legs.set(0); failedLegs.set(0); failedFeeLamports.set(0); rentOpenedLamports.set(0); rentClosedInTxLamports.set(0)
        ordinaryTips.set(0); tipSavedLamports.set(0); aggregatorFirst.set(0); simRefused.set(0); partialFixedHeld.set(0)
        aggregatorTriedAt.clear()
    }
}
