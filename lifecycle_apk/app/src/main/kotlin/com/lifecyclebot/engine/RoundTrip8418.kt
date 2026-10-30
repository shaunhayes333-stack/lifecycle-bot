package com.lifecyclebot.engine

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 — THE ROUND TRIP AS IT IS PAID ON CHAIN, COMPONENT BY COMPONENT.
 *
 * Owner: "I don't really understand why the round trip cost is so high anyway. its not in real life." The 9.28 % that
 * widened every stop (FieldManual7715.stopPct8392) and lifted the entry bar (NetEdge8030.preTradeCostPct8329) was not a
 * fee. It was CostLedger7962's MARK friction: SOL paid against the tape mark 3 s before the fill, and SOL received against
 * the mark 3 s before each sale (buy side 7.53 % on 5.0.8390). On a coin moving several percent in those seconds the mark
 * gap is the market's own move — booked as a fee. The clean receipt-backed trip on 5.0.8416 (SHITCOIN, 0.0304 SOL,
 * Jupiter) was 3.32 % all-in, against a 5.54 % model.
 *
 * What a round trip actually pays, and where each number now comes from:
 *  - network base fee   5,000 lamports a signature (protocol constant), per LANDED leg (TradeVerifier meta.fee).
 *  - priority fee       meta.fee - base on landed legs (CostMeter8151 EWMA); before the first leg, the Helius
 *                       getPriorityFeeEstimate MEDIUM level x the swap's CU estimate (PriorityFee8009), never a flat guess.
 *  - Sender tip         what each send set (CostMeter8151): an ordinary trade pays [CostTrim8151.ORDINARY_SENDER_TIP_LAMPORTS_8151]
 *                       (5x the 5,000-lamport SWQoS minimum); a stop / rug / emergency / unlanded retry keeps the legacy tip.
 *  - ATA rent           a refundable deposit: excluded from both legs (TradeVerifier.buySolSpent7928) and charged only at the
 *                       share RentReclaimer7927 cannot take back; every LIVE close stamp now queues the after-exit close.
 *  - route fee          the quote's own fee for the route that executes: Metis /swap (this app sets no platformFeeBps) = 0,
 *                       Ultra = the order's feeBps, weighted by the share of landed legs each route carried.
 *  - venue / LP fee     PaperVenueCost7287 (pump curve 1.25 %, PumpSwap 0.25 % + creator, AMM 0.25 %) — inside the quote.
 *  - impact             the depth model, raised to the fresh quote's own priceImpactPct (scaled to the ticket) when larger.
 *  - slippage           REALISED fill vs the executed quote, per side, from receipts ([RealisedSlip8418]); the 0.5 %/side
 *                       allowance only until [RealisedSlip8418.MIN_N_8418] fills per side are measured. Never the tolerance.
 *                       V5.0.8426: "executed" means the quote the swap transaction was built from (JupiterApi.buildSwapTx),
 *                       never the latest probe quote of the mint; protective sells (stops) are measured apart and never
 *                       charged to an entry (8422: a 3.82 %/side "sell slip" from five stop exits priced every entry at 9.88 %).
 *  - app fee           read from CostTrim8151.APP_FEE_PCT_PER_SIDE_8151 (owned by the fee branch; not set here).
 * Fixed SOL (base + priority + tips + unreclaimed rent) is shown apart from the percentage costs ([itemise8418]).
 */
object RoundTrip8418 {
    /** Metis /swap route fee: Jupiter charges a platform fee only when the integrator sets platformFeeBps (never here). */
    internal const val METIS_FEE_PCT_8418 = 0.0
    /** Ultra / Swap v2 order fee before any order of that side was observed: the documented new-token upper bound. */
    internal const val ULTRA_FEE_UNMEASURED_PCT_8418 = 0.5
    /** A route-share reading needs this many landed Jupiter legs of the side. */
    internal const val ROUTE_SHARE_MIN_N_8418 = 3

    /**
     * Pure: the Jupiter route fee per side. [v2Fresh] / [v6Fresh] = a fresh quote's own fee for this mint (Ultra / Metis);
     * [ultraShare] = the share of landed legs that went Ultra (null = unknown); [ultraTypical] = the median fee of recent
     * Ultra quotes of this side. Unknown share: the route that quoted, Ultra when both or neither did (never free on no data).
     */
    fun jupiterRouteFee8418(v2Fresh: Double?, v6Fresh: Double?, ultraShare: Double?, ultraTypical: Double?): Double {
        val ultra = v2Fresh ?: ultraTypical ?: ULTRA_FEE_UNMEASURED_PCT_8418
        val metis = v6Fresh ?: METIS_FEE_PCT_8418
        val s = ultraShare?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
            ?: (if (v2Fresh == null && v6Fresh != null) 0.0 else 1.0)
        return s * ultra + (1.0 - s) * metis
    }

    /** Pure: the share of landed legs that went Ultra, or null with fewer than [ROUTE_SHARE_MIN_N_8418] Jupiter legs. */
    fun ultraShare8418(landedUltra: Int, landedMetis: Int): Double? {
        val n = landedUltra.coerceAtLeast(0) + landedMetis.coerceAtLeast(0)
        return if (n < ROUTE_SHARE_MIN_N_8418) null else landedUltra.coerceAtLeast(0).toDouble() / n.toDouble()
    }

    /** Pure: one leg's planned network fee (lamports): the base fee plus [microLamports] x [cuEstimate] (floor when unread). */
    fun plannedLegFeeLamports8418(microLamports: Long, floorMicro: Long, cuEstimate: Long): Long {
        val micro = if (microLamports > 0L) microLamports else floorMicro.coerceAtLeast(0L)
        return CostTrim8151.BASE_FEE_LAMPORTS_8151 + micro * cuEstimate.coerceAtLeast(0L) / 1_000_000L
    }

    /** The planned network fee of one leg now: the market's medium CU price (PriorityFee8009) over the swap CU estimate. */
    fun plannedLegFeeLamportsNow8418(): Long = plannedLegFeeLamports8418(
        try { com.lifecyclebot.network.PriorityFee8009.mediumMicro8169() } catch (_: Throwable) { 0L },
        com.lifecyclebot.network.PriorityFee8009.FLOOR_MICRO_LAMPORTS, CostTrim8151.EST_SWAP_CU_8151,
    )

    /** Pure: the round trip's slippage (%, both sides): each side's realised median vs its quote, else the allowance. */
    fun slipRoundTripPct8418(buyMedianPct: Double?, sellMedianPct: Double?): Double =
        (buyMedianPct?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: RealisedSlip8418.ALLOWANCE_PCT_PER_SIDE_8418) +
            (sellMedianPct?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: RealisedSlip8418.ALLOWANCE_PCT_PER_SIDE_8418)

    /**
     * Pure: round-trip impact (%). The depth model [depthRtPct], raised to the quote's own one-way impact [quotedOneWayPct]
     * (codebase convention: percent) scaled from the quote's size [quoteSizeSol] to [sizeSol], both ways. The quote only
     * ever raises the figure: its unit has not been proven on a device, so it is never used to price below the depth seen.
     */
    fun impactRoundTripPct8418(depthRtPct: Double, quotedOneWayPct: Double?, quoteSizeSol: Double, sizeSol: Double): Double {
        val depth = if (depthRtPct.isFinite() && depthRtPct > 0.0) depthRtPct else 0.0
        if (quotedOneWayPct == null || !quotedOneWayPct.isFinite() || !(quoteSizeSol > 0.0) || !(sizeSol > 0.0)) return depth
        val scaled = (kotlin.math.abs(quotedOneWayPct) * (sizeSol / quoteSizeSol) * 2.0).coerceAtMost(100.0)
        return maxOf(depth, scaled)
    }

    /**
     * Pure: the mark-based measured trip (CostLedger7962) may lift the cost only while realised slippage is NOT yet measured
     * from receipts. Once it is, the fee components are receipts and the mark gap (the market's move in the seconds around
     * each fill) is reported, never charged as a fee.
     */
    // V5.0.8418 §MARK_GAP_IS_NOT_A_FEE — the mark-based "cost" (SOL paid vs the tape 3 s before each fill) is the market's
    // move around the fill plus the decision-to-fill chase; the one receipt-verified trip (8416, SHITCOIN 0.0304 SOL) cost
    // 3.32 % all-in where the mark median read 9.28 %. It is never charged as a fee, measured receipts or not: fees are the
    // itemised model (venue, route, app, tip, priority, impact, slippage) and the chase is charged once, by EntryChase7961.
    @Suppress("UNUSED_PARAMETER")
    fun markLift8418(markPct: Double?, receiptMeasured: Boolean): Double? = null

    /** One priced round trip, fixed SOL apart from percentage costs. */
    data class Itemised8418(
        val ticketSol: Double,
        val baseFeeSol: Double, val prioritySol: Double, val tipSol: Double, val rentSol: Double,
        val venuePctRt: Double, val routePctRt: Double, val appPctRt: Double, val impactPctRt: Double, val slipPctRt: Double,
    ) {
        val fixedSol: Double get() = baseFeeSol + prioritySol + tipSol + rentSol
        val fixedPct: Double get() = if (ticketSol > 0.0) fixedSol / ticketSol * 100.0 else Double.NaN
        val variablePct: Double get() = venuePctRt + routePctRt + appPctRt + impactPctRt + slipPctRt
        val totalPct: Double get() = fixedPct + variablePct
    }

    /**
     * Pure: itemise a round trip at [ticketSol]. Fixed: two base fees, the priority of both legs ([legFeeLamports] each,
     * base included), the entry and sell tips, the rent not coming back ([rentLossRate] x one ATA). Percentages per side x 2.
     */
    fun itemise8418(ticketSol: Double, legFeeLamports: Long, entryTipLamports: Long, sellTipLamports: Long, rentLossRate: Double,
                    venuePctPerSide: Double, routePctPerSide: Double, appPctPerSide: Double, impactPctRt: Double, slipPctRt: Double): Itemised8418 {
        val base = 2.0 * CostTrim8151.BASE_FEE_LAMPORTS_8151 / 1e9
        val prio = 2.0 * (legFeeLamports - CostTrim8151.BASE_FEE_LAMPORTS_8151).coerceAtLeast(0L) / 1e9
        val tips = (entryTipLamports.coerceAtLeast(0L) + sellTipLamports.coerceAtLeast(0L)) / 1e9
        val rent = CostTrim8151.ATA_RENT_SOL_8151 * (if (rentLossRate.isFinite()) rentLossRate.coerceIn(0.0, 1.0) else 0.0)
        fun nn(v: Double) = if (v.isFinite() && v > 0.0) v else 0.0
        return Itemised8418(ticketSol, base, prio, tips, rent, 2.0 * nn(venuePctPerSide), 2.0 * nn(routePctPerSide),
            2.0 * nn(appPctPerSide), nn(impactPctRt), nn(slipPctRt))
    }

    // ── live ──

    /** NetEdge8030.costPct8030: the slippage the trip pays now (realised when measured, else the allowance). */
    fun slipRoundTripPctLive8418(): Double =
        slipRoundTripPct8418(RealisedSlip8418.medianPct8418("BUY"), RealisedSlip8418.medianPct8418("SELL"))

    /** NetEdge8030.costPct8030: the impact the trip pays at [sizeSol] (depth model, raised to a fresh quote for [mint]). */
    fun impactLive8418(mint: String, sizeSol: Double, depthRtPct: Double, nowMs: Long = System.currentTimeMillis()): Double {
        val q = if (mint.isBlank()) null else RealisedSlip8418.freshBuyQuote8418(mint, nowMs)
        return impactRoundTripPct8418(depthRtPct, q?.impactPct, q?.inAmount?.toDouble()?.div(1e9) ?: 0.0, sizeSol)
    }

    /** Appended to NetEdge8030's breakdown: what the trip was priced on beyond the 8169 fields. */
    fun breakdownTail8418(slipRtPct: Double, impactRtPct: Double, markPct: Double?, receiptMeasured: Boolean): String {
        fun p(v: Double) = if (v.isFinite()) "%.2f".format(v) else "-"
        return " rt8418[slipRt=${p(slipRtPct)}%(${if (RealisedSlip8418.sideMeasured8418("BUY") && RealisedSlip8418.sideMeasured8418("SELL")) "realised" else "allowance"}) " +
            "impactRt=${p(impactRtPct)}% markMeasured=${markPct?.let { p(it) } ?: "-"} markLift=${if (receiptMeasured) "off(receipts)" else "on"}]"
    }

    /** Costs8369.statusLine8369: the trip at the last ticket, fixed SOL apart from percentages. */
    fun statusLine8418(): String {
        fun p(v: Double) = if (v.isFinite()) "%.2f".format(v) else "-"
        val ticket = CostMeter8151.ticketSol8151(com.lifecyclebot.engine.truth.NetEdge8030.DEFAULT_TICKET_SOL)
        val fixed = CostMeter8151.roundTripFixedSol8151()
        return "roundTrip8418[ticket=${"%.4f".format(ticket)} fixedSol=${"%.6f".format(fixed)}(${p(fixed / ticket * 100.0)}%) " +
            "plannedLegFee=${plannedLegFeeLamportsNow8418()}lam ordinaryTip=${CostTrim8151.ORDINARY_SENDER_TIP_LAMPORTS_8151}lam " +
            "slipRt=${p(slipRoundTripPctLive8418())}% ${RealisedSlip8418.statusLine8418()}]"
    }
}

/**
 * V5.0.8418 — realised slippage against the EXECUTED quote, from receipts. JupiterApi notes each quote (through
 * Costs8369.noteJupiterQuote8391); TradeVerifier, on a landed leg, compares what the chain delivered with what that quote
 * promised for the same input. That gap is slippage + MEV + quote-to-land drift: the execution cost the trip really paid.
 * A fill whose input does not match the last quote of its mint and side is not a sample (counted, never guessed).
 */
object RealisedSlip8418 {
    internal const val MIN_N_8418 = 5
    internal const val ALLOWANCE_PCT_PER_SIDE_8418 = 0.5   // FieldManual7715.SLIPPAGE_ALLOWANCE_PCT_7715 / 2
    internal const val QUOTE_MATCH_MS_8418 = 180_000L
    internal const val QUOTE_FRESH_MS_8418 = 60_000L
    private const val RING_8418 = 60
    private const val MAX_SANE_SLIP_PCT_8418 = 50.0
    private const val PERSIST_KEY_8418 = "REALISED_SLIP_8418"
    private const val SOL_8418 = "So11111111111111111111111111111111111111112"

    data class Quote8418(val inAmount: BigInteger, val outAmount: BigInteger, val impactPct: Double, val atMs: Long)

    private val quotes = ConcurrentHashMap<String, Quote8418>()
    /**
     * V5.0.8426 — the quote each swap transaction was BUILT from (JupiterApi.buildSwapTx), by mint and side. A fill is
     * measured against this and nothing else. 8422: `realisedSlip8418{buy=-%/n2 sell=3.82%/n5 unmatched=24 quotes=18}` —
     * fills were matched to the LATEST quote of the mint and side inside 180 s, which on a held coin is the executable-price
     * probe (MarkIdentityRepairAuthority7236.requestExecutableQuote7301 every 5 s under a stop watch) or a sizing re-check at
     * another amount: buys fell out as unmatched (24 of 27), sells "measured" minutes of a dumping tape against a probe quote
     * (3.82 %/side) and the entry cost charged it (used=9.88 % on a 0.03 SOL ticket). The mark gap is not a fee (8418).
     */
    private val executed8426 = ConcurrentHashMap<String, Quote8418>()
    private val buys = ArrayDeque<Double>()
    private val sells = ArrayDeque<Double>()
    /** V5.0.8426 — protective sells (stop / rug / emergency: the larger Sender tip) measured apart: a stop pays its own slippage. */
    private val stops8426 = ArrayDeque<Double>()
    private val unmatched = AtomicLong(0)
    @Volatile private var loaded = false

    // ── pure ──

    /** Pure: a raw amount from a quote field (string or number); null when absent or not positive. */
    fun amount8418(v: Any?): BigInteger? {
        val s = when (v) { null, org.json.JSONObject.NULL -> return null; is Number -> BigDecimal(v.toString()).toBigInteger().toString(); else -> v.toString().trim() }
        return try { BigInteger(s).takeIf { it.signum() > 0 } } catch (_: Throwable) { null }
    }

    /**
     * Pure: a buy's realised slippage (%): the tokens [receivedRaw] against the quote's [quoteOutRaw]. Only when the SOL the
     * swap took ([spentNetLamports] = the owner's spend less the network fee, rent excluded) is the quote's input plus at most
     * a tip (<= 0.001 SOL) and a 2 % fee — otherwise the fill was another quote. Positive = worse than quoted.
     */
    fun buySlipPct8418(quoteInLamports: BigInteger, quoteOutRaw: BigInteger, spentNetLamports: Long, receivedRaw: BigInteger): Double? {
        if (quoteInLamports.signum() <= 0 || quoteOutRaw.signum() <= 0 || receivedRaw.signum() <= 0 || spentNetLamports <= 0L) return null
        val qin = quoteInLamports.toDouble()
        if (spentNetLamports < qin * 0.995 || spentNetLamports > qin * 1.02 + 1_000_000.0) return null
        val got = BigDecimal(receivedRaw).divide(BigDecimal(quoteOutRaw), MathContext.DECIMAL64).toDouble()
        val slip = (1.0 - got) * 100.0
        return slip.takeIf { it.isFinite() && kotlin.math.abs(it) <= MAX_SANE_SLIP_PCT_8418 }
    }

    /**
     * Pure: a sell's realised slippage (%): SOL delivered ([receivedGrossLamports] = net received + network fee + tip, rent
     * excluded) against the quote's output scaled to the tokens actually sold. Only within 2 % of the quoted input.
     */
    fun sellSlipPct8418(quoteInRaw: BigInteger, quoteOutLamports: BigInteger, soldRaw: BigInteger, receivedGrossLamports: Long): Double? {
        if (quoteInRaw.signum() <= 0 || quoteOutLamports.signum() <= 0 || soldRaw.signum() <= 0 || receivedGrossLamports <= 0L) return null
        val ratio = BigDecimal(soldRaw).divide(BigDecimal(quoteInRaw), MathContext.DECIMAL64).toDouble()
        if (ratio < 0.98 || ratio > 1.02) return null
        val expected = quoteOutLamports.toDouble() * ratio
        if (!(expected > 0.0)) return null
        val slip = (1.0 - receivedGrossLamports.toDouble() / expected) * 100.0
        return slip.takeIf { it.isFinite() && kotlin.math.abs(it) <= MAX_SANE_SLIP_PCT_8418 }
    }

    /** Pure: median of [xs] once [minN]+ exist, else null. */
    fun median8418(xs: List<Double>, minN: Int = MIN_N_8418): Double? {
        val s = xs.filter { it.isFinite() }.sorted()
        if (s.size < minN || s.isEmpty()) return null
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    // ── capture ──

    /** Costs8369.noteJupiterQuote8391: every Jupiter quote (Ultra or Metis) of a SOL pair, by mint and side. */
    fun noteQuote8418(input: String, output: String, json: org.json.JSONObject, nowMs: Long = System.currentTimeMillis()) {
        val key = when {
            input == SOL_8418 && output != SOL_8418 -> "$output|BUY"
            output == SOL_8418 && input != SOL_8418 -> "$input|SELL"
            else -> return
        }
        val inA = amount8418(json.opt("inAmount") ?: json.opt("inputAmount")) ?: return
        val outA = amount8418(json.opt("outAmount") ?: json.opt("outputAmount")) ?: return
        val imp = (json.opt("priceImpactPct") ?: json.opt("priceImpact"))?.toString()?.toDoubleOrNull() ?: Double.NaN
        quotes[key] = Quote8418(inA, outA, imp, nowMs)
        if (quotes.size > 1024) quotes.entries.removeIf { nowMs - it.value.atMs > QUOTE_MATCH_MS_8418 }
    }

    /** RoundTrip8418.impactLive8418: the fresh BUY quote of [mint] (null when none in the last minute). */
    internal fun freshBuyQuote8418(mint: String, nowMs: Long): Quote8418? =
        quotes["$mint|BUY"]?.takeIf { nowMs - it.atMs in 0L..QUOTE_FRESH_MS_8418 }

    /**
     * V5.0.8426 — JupiterApi.buildSwapTx: the quote this swap transaction is built from (Ultra order or Metis /swap). Only
     * this quote answers for the fill; a later probe or sizing quote of the same mint never replaces it.
     */
    fun noteExecuted8426(inputMint: String, outputMint: String, inAmountRaw: Long, outAmountRaw: Long, impactPct: Double,
                         nowMs: Long = System.currentTimeMillis()) {
        val key = when {
            inputMint == SOL_8418 && outputMint != SOL_8418 -> "$outputMint|BUY"
            outputMint == SOL_8418 && inputMint != SOL_8418 -> "$inputMint|SELL"
            else -> return
        }
        if (inAmountRaw <= 0L || outAmountRaw <= 0L) return
        executed8426[key] = Quote8418(BigInteger.valueOf(inAmountRaw), BigInteger.valueOf(outAmountRaw), impactPct, nowMs)
        if (executed8426.size > 512) executed8426.entries.removeIf { nowMs - it.value.atMs > QUOTE_MATCH_MS_8418 }
    }

    /** Pure (V5.0.8426): a sell sent with more than the ordinary Sender tip is a protective exit (stop / rug / emergency). */
    fun protectiveSell8426(tipLamports: Long): Boolean = tipLamports > CostTrim8151.ORDINARY_SENDER_TIP_LAMPORTS_8151

    /** TradeVerifier.verifyBuy (landed): tokens received for the SOL spent (rent excluded), the leg's network fee. */
    fun noteBuyFill8418(mint: String, receivedRaw: BigInteger, solSpentLamports: Long, feeLamports: Long, nowMs: Long = System.currentTimeMillis()) {
        // V5.0.8426 — against the EXECUTED quote only (superseded: the latest quote of the mint and side, a probe more often than not)
        val q = executed8426["$mint|BUY"]?.takeIf { nowMs - it.atMs in 0L..QUOTE_MATCH_MS_8418 }
        val slip = if (q == null) null else buySlipPct8418(q.inAmount, q.outAmount, solSpentLamports - feeLamports.coerceAtLeast(0L), receivedRaw)
        record8418(true, slip)
    }

    /** TradeVerifier.verifySell (landed): tokens sold, SOL received net (rent excluded), the leg's fee and tip. */
    fun noteSellFill8418(mint: String, soldRaw: BigInteger, solReceivedLamports: Long, feeLamports: Long, tipLamports: Long,
                         nowMs: Long = System.currentTimeMillis()) {
        val q = executed8426["$mint|SELL"]?.takeIf { nowMs - it.atMs in 0L..QUOTE_MATCH_MS_8418 }   // V5.0.8426
        val gross = solReceivedLamports + feeLamports.coerceAtLeast(0L) + tipLamports.coerceAtLeast(0L)
        val slip = if (q == null) null else sellSlipPct8418(q.inAmount, q.outAmount, soldRaw, gross)
        record8418(false, slip, protective = protectiveSell8426(tipLamports))
    }

    private fun record8418(buy: Boolean, slip: Double?, protective: Boolean = false) {
        if (slip == null) { unmatched.incrementAndGet(); return }
        synchronized(this) {
            ensureLoaded8418()
            val q = if (buy) buys else if (protective) stops8426 else sells   // V5.0.8426 — a stop's slippage is the stop's
            q.addLast(slip)
            while (q.size > RING_8418) q.removeFirst()
            persist8418()
        }
    }

    // ── reads ──

    /**
     * The realised median slippage (%) of [side] ("BUY" / "SELL") once [MIN_N_8418] fills are measured, else null. The SELL
     * median is the ORDINARY exits' (V5.0.8426): what the entry's planned exit will pay; protective exits read [stopMedianPct8426].
     */
    fun medianPct8418(side: String): Double? = synchronized(this) {
        ensureLoaded8418()
        median8418((if (side.equals("BUY", true)) buys else sells).toList())
    }

    /**
     * V5.0.8463 — ProfitExitTruth8163: the realised sell slippage (%, the sell's fill against its own quote) and how many sells
     * measured it; null before the first. Replaces the lane's mark-based sell-side median (market drift, not friction).
     */
    fun sellReceipt8463(): Pair<Double, Int>? = synchronized(this) {
        ensureLoaded8418()
        median8418(sells.toList(), 1)?.let { it to sells.size }
    }

    /** V5.0.8426 — the protective exits' realised median slippage (%), for the report and the stop books; never the entry cost. */
    fun stopMedianPct8426(): Double? = synchronized(this) { ensureLoaded8418(); median8418(stops8426.toList()) }

    internal fun sideMeasured8418(side: String): Boolean = medianPct8418(side) != null

    /** Both sides of the trip are receipt-measured: the mark-based lift retires ([RoundTrip8418.markLift8418]). */
    fun receiptMeasured8418(): Boolean = sideMeasured8418("BUY") && sideMeasured8418("SELL")

    fun statusLine8418(): String = synchronized(this) {
        ensureLoaded8418()
        fun p(v: Double?) = if (v != null && v.isFinite()) "%.2f".format(v) else "-"
        "realisedSlip8418{buy=${p(median8418(buys.toList()))}%/n${buys.size} sell=${p(median8418(sells.toList()))}%/n${sells.size} " +
            "stopSell8426=${p(median8418(stops8426.toList()))}%/n${stops8426.size} unmatched=${unmatched.get()} minN=$MIN_N_8418 " +
            "quotes=${quotes.size} executed8426=${executed8426.size}}"
    }

    // ── persistence ──

    private fun ensureLoaded8418() {
        if (loaded) return
        val ready = try { LearningPersistence.ready() } catch (_: Throwable) { false }
        if (!ready) return
        loaded = true
        try {
            val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY_8418) ?: return)
            fun read(name: String, into: ArrayDeque<Double>) {
                val a = o.optJSONArray(name) ?: return
                for (i in 0 until a.length()) { val v = a.optDouble(i); if (v.isFinite()) into.addLast(v) }
                while (into.size > RING_8418) into.removeFirst()
            }
            // V5.0.8426 — the pre-8426 "sell" ring measured fills against probe quotes (minutes of tape, not slippage): it is not
            // restored; the executed-quote samples start from this build. "buy" rows were executed-amount checked and stand.
            read("buy", buys); read("sell8426", sells); read("stop8426", stops8426)
        } catch (_: Throwable) {}
    }

    private fun persist8418() {
        if (!loaded) return
        try {
            LearningPersistence.save(PERSIST_KEY_8418, org.json.JSONObject()
                .put("buy", org.json.JSONArray(buys.toList())).put("sell8426", org.json.JSONArray(sells.toList()))
                .put("stop8426", org.json.JSONArray(stops8426.toList())).toString())
        } catch (_: Throwable) {}
    }

    internal fun resetForTest8418() = synchronized(this) { quotes.clear(); executed8426.clear(); buys.clear(); sells.clear(); stops8426.clear(); unmatched.set(0); loaded = false }
}
