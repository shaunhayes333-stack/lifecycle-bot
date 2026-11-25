package com.lifecyclebot.engine.truth

/**
 * V5.0.8828 §THE_OWNERS_SIGNALS_ON_THE_TAPE — owner: "build it. wire the signals into the data the bot already has properly".
 *
 * One coin's first hour, print by print, as 10-second bars, and the owner's signals (SignalBible300) that those prints can
 * express, each read when a bar closes. The SAME object is fed by the launch replay (SignalReplay8828: the stored launch
 * hours, graded with the replay's honest fills) and by the live tape (LaunchSniper8748: every witnessed pump.fun print), so
 * a signal is traded exactly as it was graded. Nothing here buys.
 *
 * Bars: every print shapes them (late ones too); a bar closes when a print of a later bar arrives, the signals are read on
 * the closed bar, and only a LIVE closing print can turn a signal on (a backfilled print never triggers, as in the
 * replay). Each signal turns on at most once per coin. Memory: the last [RING] closed bars in primitive arrays, the coin's
 * buyer wallets in a fixed table of [WALLET_SLOTS] (~4.5 KB a coin).
 *
 * Wallets are the economic identity the feed names (a funding cluster is NOT resolved: S001 / S003 count distinct wallets,
 * not distinct funders — the bible's OFF clauses on same-funder hops are not expressible on this data). A print with no
 * wallet counts for flow but not for breadth; a wallet signal on a coin whose prints never named a buyer stays off.
 * The thresholds are the bible's own exploratory seeds where it gives one ("at least three", "20-60 s", "half its impact")
 * and are stated beside each signal where it does not; the replay's record, not the threshold, decides whether one trades.
 */
class SignalTape8828 {
    companion object {
        const val BAR_MS = 10_000L
        const val RING = 40
        private const val WALLET_SLOTS = 512
        private const val MAX_WALLETS = 300
        private const val BAR_WALLETS = 32
        private const val COHORT_MS = 20_000L
        private const val SECOND_WAVE_END_MS = 60_000L

        /**
         * V5.0.8994 §THE_BASE_BREAKOUT_ON_THE_LAUNCH_TAPE — owner, 24 Nov 23:53: "there are real clean multiple hundreds to
         * thousand % trades we are ignoring even the 30% to 200% trades should be bread and butter". The owner's 25 Nov
         * launch-hour export (3,302 pump.fun launches, 22-24 Nov, 10-second bars): of the launches still printing at 30 s,
         * 54 % later peaked +30 % or more, 31 % +100 %, 8.7 % +400 %. Bought blind on those bars with the runner line the
         * result was fill-decided (+27.7 % mean with fills at the line, -8.7 % with every fill at the bar's worst price,
         * 42 % winners). One chart shape held up under both: a base (the coin's bars of the prior 180 s inside a narrow
         * range), broken by a bar that closes above it on buying. 16 parameter settings around it all kept both halves of
         * the launches positive, 54-74 % winners; the wide setting n226, mean +20.4 %, +13.3 % without the best three,
         * halves +20.5 / +20.3, 57 % winners (worst-price fills +0.2 %); the tight one n82, +25.7 %, halves +16.0 / +34.9,
         * 65 % winners (worst-price fills +4.6 %). MemeStrategies8546.BASE_BREAKOUT_FLOW reads the same shape on 8-20
         * closed ONE-MINUTE bars, which a launch does not have (its funnel on the 23:24 capture: m1 empty 2,123, short 419,
         * ready 77), and trades paper only. Here the shape is read on the launch's own 10-second print bars, and it is a
         * signal like the others: SignalReplay8828 grades it on this phone's stored launch hours with the replay's fills
         * and latency, and only a PROVEN record arms it. Those offline figures are bars, not prints: the phone's grade decides.
         */
        const val BASE_WIDE_8994 = "B8994W"
        const val BASE_TIGHT_8994 = "B8994T"
        private const val BASE_LOOK_BARS_8994 = 18L

        /**
         * V5.0.9007 §BOUGHT_AT_ITS_OPEN_NOT_AFTER_ITS_PUMP — owner, 25 Nov 18:41: "we should be at the point of hitting 500%
         * trades winning and better consistently ... the tokens and trades are there". They are. On the owner's launch-hour
         * exports (4,344 pump.fun launches, 22-25 Nov, 10-second bars; decided at 20 s, filled one bar later, 3 % cost, 3 %
         * exit slip, the DOCTRINE ladder with a 35 % stop): a launch bought 20-30 s old peaks +500 % or more inside the hour
         * 6.5 % of the time. What separates the ones worth buying is where the price stands against its own first print:
         *   still within +15 % of its first print, 8+ prints: n 1,344, +16.1 % +- 4.8 a trade, halves +17.2 / +14.9,
         *     1.9 % of trades (26) realise +500 % or more, 7.8 % realise +100 %; median -7 % (it is bought near the curve's
         *     floor, so most losers lose little);
         *   already +40 % or more over its first print, 8+ prints: n 711, +4.9 % (halves -1.1 / +11.2): the top;
         *   the armed rule's shape (cap >= $5k with 3+ buys): n 865, +9.6 % (halves +6.6 / +12.1), median -39.9 %.
         * The same entries decided at 60 s: n 878, +10.4 % (halves +10.6 / +10.1). So two settings join the tape: the launch
         * with 8+ prints still within +15 % of its first print when its 20-40 s bar closes, and when its 60-80 s bar closes.
         * Bars are not prints: SignalReplay8828 grades both on this phone's stored launch hours with the replay's fills and
         * the bot's latency, and only a PROVEN record arms one.
         */
        const val AT_OPEN_20S_9007 = "O9007A"
        const val AT_OPEN_60S_9007 = "O9007B"
        private const val AT_OPEN_MAX_OVER_9007 = 1.15
        private const val AT_OPEN_MIN_PRINTS_9007 = 8

        /** The signals this tape expresses (SignalBible300 ids, and the base breakout's two settings). */
        val IDS: List<String> = listOf("S001", "S003", "S005", "S006", "S009", "S010", "S011", "S012", "S014", "S016", "S023",
            "S049", "S050", "S051", "S052", BASE_WIDE_8994, BASE_TIGHT_8994, AT_OPEN_20S_9007, AT_OPEN_60S_9007)
        /** Those that need the buyer's wallet. */
        val WALLET_IDS: Set<String> = setOf("S001", "S003", "S005", "S010", "S014")
    }

    // ── closed bars, a ring; back = 0 is the newest ──
    private val bIdx = LongArray(RING)
    private val bO = FloatArray(RING); private val bH = FloatArray(RING); private val bL = FloatArray(RING); private val bC = FloatArray(RING)
    private val bBuys = IntArray(RING); private val bSells = IntArray(RING)
    private val bBuySol = FloatArray(RING); private val bSellSol = FloatArray(RING)
    private val bBuyers = IntArray(RING); private val bNew = IntArray(RING); private val bMaxWal = FloatArray(RING)
    private var n = 0; private var head = 0

    // ── the open bar ──
    private var cIdx = -1L
    private var cO = 0f; private var cH = 0f; private var cL = 0f; private var cC = 0f
    private var cBuys = 0; private var cSells = 0; private var cBuySol = 0f; private var cSellSol = 0f; private var cNew = 0
    private val cWal = LongArray(BAR_WALLETS); private val cWalSol = FloatArray(BAR_WALLETS); private var cWalN = 0

    // ── the coin ──
    private val seen = LongArray(WALLET_SLOTS); private var seenN = 0
    private var cohortN = 0; private var secondWave = 0
    private var h30 = Float.NaN; private var broke30 = false
    private var maxHigh = Float.NaN; private var prevMaxHigh = Float.NaN
    private var walletsKnown = false
    private var first9007 = Float.NaN   // V5.0.9007 — the launch's first print
    private val fired = BooleanArray(IDS.size)

    private fun ri(back: Int): Int = ((head - 1 - back) % RING + RING) % RING
    private fun c(b: Int) = bC[ri(b)].toDouble(); private fun h(b: Int) = bH[ri(b)].toDouble(); private fun l(b: Int) = bL[ri(b)].toDouble()
    private fun idx(b: Int) = bIdx[ri(b)]
    private fun buys(b: Int) = bBuys[ri(b)]; private fun sells(b: Int) = bSells[ri(b)]
    private fun buySol(b: Int) = bBuySol[ri(b)].toDouble(); private fun sellSol(b: Int) = bSellSol[ri(b)].toDouble()
    private fun buyers(b: Int) = bBuyers[ri(b)]

    /** Adds [w] to the coin's wallet table; true when it was new. */
    private fun addWallet(w: Long): Boolean {
        val key = if (w == 0L) Long.MIN_VALUE else w
        var i = ((key xor (key ushr 31)).toInt() and Int.MAX_VALUE) % WALLET_SLOTS
        while (true) {
            val v = seen[i]
            if (v == key) return false
            if (v == 0L) { if (seenN >= MAX_WALLETS) return false; seen[i] = key; seenN++; return true }
            i = (i + 1) % WALLET_SLOTS
        }
    }

    /**
     * One print at [tMs] since the coin's birth: [px] price (SOL per token), [buy] side, [sol] size, [wallet] the buyer's
     * key (< 0 = unnamed), [live] received within the replay's live lag. Returns the signals that turned on at this print.
     */
    fun onPrint(tMs: Long, px: Double, buy: Boolean, sol: Double, wallet: Long, live: Boolean): List<String> {
        if (tMs < 0L || !(px > 0.0) || !px.isFinite()) return emptyList()
        val b = tMs / BAR_MS
        if (cIdx >= 0L && b < cIdx) return emptyList()            // a late print for a closed bar
        var on: List<String> = emptyList()
        // A bar's trigger must still be executable when it is discovered. If the next
        // print arrives minutes later, the old closed bar is historical evidence, not
        // a live opening-bid / momentum entry. The replay and live reader use this
        // same rule; do not turn delayed historical momentum into a new BUY signal.
        if (cIdx >= 0L && b > cIdx) { val adjacent = b == cIdx + 1L; closeBar(); if (live && adjacent) on = evaluate() }
        if (cIdx < 0L || b > cIdx) openBar(b, px)
        val f = px.toFloat()
        if (first9007.isNaN()) first9007 = f
        if (f > cH) cH = f
        if (f < cL) cL = f
        cC = f
        val s = if (sol.isFinite() && sol > 0.0) sol.toFloat() else 0f
        if (buy) { cBuys++; cBuySol += s } else { cSells++; cSellSol += s }
        if (tMs <= 30_000L) h30 = if (h30.isNaN()) f else maxOf(h30, f)
        if (buy && wallet >= 0L) {
            walletsKnown = true
            var k = 0; while (k < cWalN && cWal[k] != wallet) k++
            if (k < cWalN) cWalSol[k] += s else if (cWalN < BAR_WALLETS) { cWal[cWalN] = wallet; cWalSol[cWalN] = s; cWalN++ }
            if (addWallet(wallet)) {
                cNew++
                if (tMs < COHORT_MS) cohortN++ else if (tMs < SECOND_WAVE_END_MS) secondWave++
            }
        }
        return on
    }

    private fun openBar(b: Long, px: Double) {
        cIdx = b; val f = px.toFloat(); cO = f; cH = f; cL = f; cC = f
        cBuys = 0; cSells = 0; cBuySol = 0f; cSellSol = 0f; cNew = 0; cWalN = 0
    }

    private fun closeBar() {
        val i = head
        bIdx[i] = cIdx; bO[i] = cO; bH[i] = cH; bL[i] = cL; bC[i] = cC
        bBuys[i] = cBuys; bSells[i] = cSells; bBuySol[i] = cBuySol; bSellSol[i] = cSellSol
        bBuyers[i] = cWalN; bNew[i] = cNew
        var mx = 0f; for (k in 0 until cWalN) if (cWalSol[k] > mx) mx = cWalSol[k]
        bMaxWal[i] = mx
        head = (head + 1) % RING; if (n < RING) n++
        prevMaxHigh = maxHigh
        maxHigh = if (maxHigh.isNaN()) cH else maxOf(maxHigh, cH)
        // S006's break of the first-30 s high is a fact of the path, kept whether or not this bar's close could trigger
        if (!h30.isNaN() && cIdx >= 3L && cH.toDouble() > h30.toDouble() * 1.02) broke30 = true
    }

    private fun evaluate(): List<String> {
        var out: ArrayList<String>? = null
        for (k in IDS.indices) {
            if (fired[k]) continue
            val id = IDS[k]
            if (id in WALLET_IDS && !walletsKnown) continue
            if (on(id)) {
                fired[k] = true
                if (out == null) out = ArrayList(2)
                out.add(id)
            }
        }
        return out ?: emptyList()
    }

    /** The close of the newest bar at or before bar index [maxIdx], or NaN when the ring no longer holds one. */
    private fun closeAtOrBefore(maxIdx: Long): Double {
        for (b in 0 until n) if (idx(b) <= maxIdx) return c(b)
        return Double.NaN
    }

    private fun on(id: String): Boolean = when (id) {
        // S001 Genuine opening bids — by 20 s: >= 3 buys from >= 3 distinct wallets, buy SOL over sell SOL
        "S001" -> idx(0) <= 1 && cohortN >= 3 && (0 until n).filter { idx(it) <= 1 }.let { bs ->
            bs.sumOf { buys(it) } >= 3 && bs.sumOf { buySol(it) } > bs.sumOf { sellSol(it) } }
        // S003 Second-wave arrival — >= 3 buyer wallets first seen 20-60 s after birth, after an opening cohort
        "S003" -> idx(0) <= 5 && cohortN >= 1 && secondWave >= 3
        // S005 Rising participation density — distinct buyers rise across three consecutive 10 s buckets
        "S005" -> n >= 3 && idx(0) - idx(2) == 2L && buyers(0) > buyers(1) && buyers(1) > buyers(2) && buyers(2) > 0
        // S006 Open-high retest — the first-30 s high broken (+2 %), revisited (within 5 %) and held on buy count
        "S006" -> broke30 && idx(0) >= 3L && l(0) <= h30.toDouble() * 1.05 && c(0) >= h30.toDouble() && buys(0) > sells(0)
        // S009 First-minute staircase — three higher lows inside the first minute, buys not falling
        "S009" -> n >= 3 && idx(0) <= 5 && l(0) > l(1) && l(1) > l(2) && buys(0) >= buys(1) && buys(1) >= buys(2) && buys(0) > 0
        // S010 Early bid quality — mean buy size rising over three bars, buyers holding, no wallet over half the bar's buying
        "S010" -> n >= 3 && (0..2).all { buys(it) > 0 && buySol(it) > 0.0 } &&
            buySol(0) / buys(0) > buySol(1) / buys(1) && buySol(1) / buys(1) > buySol(2) / buys(2) &&
            buyers(0) >= buyers(1) && buyers(1) >= buyers(2) && buyers(2) > 0 && bMaxWal[ri(0)].toDouble() < 0.5 * buySol(0)
        // S011 Launch volatility compression — three narrowing bar ranges with net SOL inflow over the last four bars
        "S011" -> n >= 4 && (0..2).map { (h(it) - l(it)) / l(it) }.let { r -> r[0] < r[1] && r[1] < r[2] } &&
            (0..3).sumOf { buySol(it) } > (0..3).sumOf { sellSol(it) }
        // S012 Fresh launch reactivation — 30-90 s quiet, then a new high on buys (with new buyer wallets when named)
        "S012" -> n >= 2 && (idx(0) - idx(1)) in 3L..9L && !prevMaxHigh.isNaN() && h(0) > prevMaxHigh.toDouble() && buys(0) > sells(0) &&
            (!walletsKnown || bNew[ri(0)] >= 2)
        // S014 Curve step-up with breadth — two successive higher closes, each with more distinct buyers
        "S014" -> n >= 3 && c(0) > c(1) && c(1) > c(2) && buyers(0) > buyers(1) && buyers(1) > buyers(2)
        // S016 Sell-shock recovery — a sell-led bar down >= 15 %, half of it recovered within two bars on net buying
        "S016" -> n >= 4 && (c(2) / c(3) - 1.0).let { drop ->
            drop <= -0.15 && sellSol(2) > buySol(2) && c(0) >= c(3) * (1.0 + drop / 2.0) && buySol(0) + buySol(1) > sellSol(0) + sellSol(1) }
        // S023 Sell-exhaustion wedge — three sell-led bars, each falling less, then a buy-led bar
        "S023" -> n >= 5 && (1..3).all { sells(it) > buys(it) } && buys(0) > sells(0) &&
            (c(3) / c(4) - 1.0).let { d3 -> (c(2) / c(3) - 1.0).let { d2 -> (c(1) / c(2) - 1.0).let { d1 -> d3 < d2 && d2 < d1 && d1 < 0.0 } } }
        // S049 Three-horizon agreement — the 15 s (the bar 20 s back: bars are 10 s), 1 m and 5 m returns all positive
        "S049" -> idx(0) >= 30L && listOf(2L, 6L, 30L).all { back -> closeAtOrBefore(idx(0) - back).let { ref -> ref.isFinite() && c(0) > ref } }
        // S050 Return acceleration — the last six bars gained more than the six before, which gained, on more buys
        "S050" -> n >= 13 && (c(0) / c(6) - 1.0).let { a -> (c(6) / c(12) - 1.0).let { b -> a > b && b > 0.0 } } &&
            (0..5).sumOf { buys(it) } > (6..11).sumOf { buys(it) }
        // S051 Clean higher-high structure — three rising confirmed swing highs with two rising swing lows between them
        "S051" -> higherHighs()
        // S052 Momentum after consolidation — six bars inside 10 %, then a close above them on net SOL buying
        "S052" -> n >= 7 && (1..6).maxOf { h(it) }.let { top -> (1..6).minOf { l(it) }.let { low ->
            low > 0.0 && top / low - 1.0 < 0.10 && c(0) > top && buySol(0) > sellSol(0) } }
        // V5.0.8994 — a base broken on buying, on the launch's own 10 s bars: wide (>= 60 s old, >= 3 base bars inside 35 %,
        // close >= 2 % over the base, the bar's buy SOL >= the base's mean bar volume, buys >= 55 % of the bar's SOL) and
        // tight (>= 90 s, >= 4 bars inside 25 %, >= 3 % over, buy SOL >= 1.5x, buys >= 65 %)
        BASE_WIDE_8994 -> baseBreakout8994(minIdx = 6L, minBars = 3, rangeMax = 0.35, overMin = 0.02, volX = 1.0, shareMin = 0.55)
        BASE_TIGHT_8994 -> baseBreakout8994(minIdx = 9L, minBars = 4, rangeMax = 0.25, overMin = 0.03, volX = 1.5, shareMin = 0.65)
        AT_OPEN_20S_9007 -> atOpen9007(1L, 3L)
        AT_OPEN_60S_9007 -> atOpen9007(5L, 7L)
        else -> false
    }

    /**
     * V5.0.9007 — the newest closed bar is one of bars [fromIdx]..[toIdx] (10 s each from birth), the launch has printed
     * [AT_OPEN_MIN_PRINTS_9007]+ times and that bar closed no more than +15 % over the launch's first print.
     */
    private fun atOpen9007(fromIdx: Long, toIdx: Long): Boolean {
        if (n == 0 || idx(0) !in fromIdx..toIdx || !(first9007 > 0f)) return false
        var prints = 0
        for (b in 0 until n) prints += buys(b) + sells(b)
        return prints >= AT_OPEN_MIN_PRINTS_9007 && c(0) <= first9007.toDouble() * AT_OPEN_MAX_OVER_9007
    }

    /**
     * V5.0.8994 — the newest closed bar breaks its base: the bars of the [BASE_LOOK_BARS_8994] bar slots before it (180 s;
     * only bars that printed exist) number at least [minBars] and sit inside [rangeMax]; the bar closes at least [overMin]
     * above their high; its buy SOL is at least [volX] times their mean bar volume and at least [shareMin] of its own.
     */
    private fun baseBreakout8994(minIdx: Long, minBars: Int, rangeMax: Double, overMin: Double, volX: Double, shareMin: Double): Boolean {
        if (n < minBars + 1 || idx(0) < minIdx) return false
        val from = idx(0) - BASE_LOOK_BARS_8994
        var bars = 0; var top = 0.0; var low = Double.MAX_VALUE; var vol = 0.0
        var b = 1
        while (b < n && idx(b) >= from) {
            bars++
            if (h(b) > top) top = h(b)
            if (l(b) < low) low = l(b)
            vol += buySol(b) + sellSol(b)
            b++
        }
        if (bars < minBars || !(low > 0.0) || top / low - 1.0 > rangeMax) return false
        val barVol = buySol(0) + sellSol(0)
        return c(0) >= top * (1.0 + overMin) && buySol(0) >= volX * (vol / bars) && barVol > 0.0 && buySol(0) / barVol >= shareMin
    }

    /** S051 on the ring, oldest to newest: a swing high is above its older neighbour and not below its newer one (confirmed). */
    private fun higherHighs(): Boolean {
        if (n < 7) return false
        val hs = ArrayList<Int>(); val ls = ArrayList<Int>()   // ring positions as "back", newest first
        for (b in 1 until n - 1) {
            if (h(b) > h(b + 1) && h(b) >= h(b - 1)) hs.add(b)
            if (l(b) < l(b + 1) && l(b) <= l(b - 1)) ls.add(b)
        }
        if (hs.size < 3) return false
        // the newest swing high must be the one just confirmed (back 1), so the signal fires on its confirmation
        if (hs[0] != 1) return false
        val (h0, h1, h2) = Triple(hs[0], hs[1], hs[2])
        if (!(h(h0) > h(h1) && h(h1) > h(h2))) return false
        val between = ls.filter { it in (h0 + 1) until h2 }
        return between.size >= 2 && l(between[0]) > l(between[1])
    }
}
