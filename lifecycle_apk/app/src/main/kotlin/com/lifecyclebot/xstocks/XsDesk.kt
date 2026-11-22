package com.lifecyclebot.xstocks

import android.content.Context
import com.lifecyclebot.desks.DeskJournal8802
import com.lifecyclebot.hyperliquid.HlBrain
import com.lifecyclebot.hyperliquid.HlDesk
import com.lifecyclebot.hyperliquid.HlHistory
import com.lifecyclebot.hyperliquid.HlStore
import com.lifecyclebot.perps.PerpsDirection
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.TokenizedAssetRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * V5.0.8802 §XSTOCKS_SPOT_DESK — owner, on GPT's audit of 8800: "the stock trader had genuine trading functionality" and
 * retiring it whole (8797) was too broad. Solana xStocks are real tokenized shares, bought and sold with SOL; what was
 * wrong was the trader around them (a "SHORT" that was a SOL -> USDC swap, "3x leverage" that bought three times the
 * tokens, a price that was the underlying's Pyth quote, a hardcoded $10M of liquidity, US-hours-only trading).
 *
 * This desk trades them as what they are — long or flat, 1x, out of the trading wallet:
 *  - universe: every stock / ETF / metal xStock the registry maps to a mint (TokenizedAssetRegistry);
 *  - history: each token's own daily candles on its deepest pool (XsApi.dailyBars), so the record is the instrument held;
 *  - prices and costs: an executable Jupiter round trip every hour at the desk's own size (XsApi.roundTrip): the ask it
 *    pays, the bid it gets, the spread + impact + route fees measured, plus the 0.5 % app fee MarketsLiveExecutor takes
 *    on every spot trade; an unmeasured token is charged [UNMEASURED_COST_SIDE] until quoted;
 *  - the brain: HlBrain, long-only (every family graded as it can actually be held), chosen on the first half of the
 *    history, graded on the second, ARMED only when the second half's mean less one SE is above zero; exposure is
 *    half-Kelly of the weaker half, at most 1 (spot has no leverage);
 *  - 24/7: xStocks trade on Solana around the clock; the measured cost is what the hour's market charges;
 *  - PAPER always trades the blend (USD book); LIVE only when the owner's switch is on, the wallet exists, the brain is
 *    ARMED and no stop has fired. Live orders are MarketsLiveExecutor's proof routes: a position opens canonically only on
 *    the token delta of its own transaction, and closes on that transaction's SOL proceeds; every fill also reaches the
 *    desk's trade book (DeskJournal8802). A position is sized when it opens and sold whole when its weight goes to zero.
 */
object XsDesk {
    private const val TICK_MS = 60_000L
    private const val QUOTE_EVERY_MS = 60L * 60_000L
    private const val RISK_EVERY_MS = 5L * 60_000L
    private const val DAY_MS = 86_400_000L
    private const val SETTLE_MS = 10L * 60_000L
    private const val HISTORY_DAYS = 420
    private const val MIN_LISTED_DAYS = 60
    private const val POOL_REFRESH_DAYS = 7L
    private const val CALL_GAP_MS = 2_500L
    private const val QUOTE_MAX_AGE_MS = 24L * 60L * 60_000L
    private const val MARK_MAX_AGE_MS = 3L * 60L * 60_000L
    private const val PAPER_START_USD = 1_000.0
    private const val APP_FEE_SIDE = 0.005
    private const val UNMEASURED_COST_SIDE = 0.02
    private const val MIN_LIVE_SOL = 0.02
    private const val LANE = "XSTOCKS"
    private const val FILE = "xs_desk_8802.json"

    data class Sym(val symbol: String, val mint: String, val market: PerpsMarket) { val coin: String get() = "xs:$symbol" }

    @Volatile private var app: Context? = null
    private val started = AtomicBoolean(false)
    private val lock = Any()

    private val pools = ConcurrentHashMap<String, Pair<String, Long>>()      // symbol -> (pool, day read)
    private val mints = ConcurrentHashMap<String, XsApi.MintInfo>()
    private val quotes = ConcurrentHashMap<String, XsApi.RoundTrip>()
    private val histories = ConcurrentHashMap<String, HlHistory>()
    @Volatile private var universe: List<Sym> = emptyList()
    @Volatile private var excluded: Map<String, String> = emptyMap()
    @Volatile private var evaluation: HlBrain.Evaluation? = null
    @Volatile private var panelSyms: List<String> = emptyList()
    @Volatile private var targets: Map<String, Double> = emptyMap()
    @Volatile private var lastEvalDay = -1L
    // V5.0.8810 — a day whose history reads went unanswered is asked again, not marked evaluated (8809: every stock
    // NO_HISTORY for the day after 34 of 36 reads were refused locally).
    @Volatile private var historyRetryAt8810 = 0L
    @Volatile private var historyUnanswered8810 = 0
    @Volatile private var historyPasses8810 = 0
    private const val HISTORY_RETRY_MS_8810 = 10L * 60_000L
    private const val HISTORY_DEADLINE_MS_8810 = 6L * 60L * 60_000L
    @Volatile private var quotesAt = 0L
    @Volatile private var riskAt = 0L

    // paper book (USD; units are raw / 10^decimals of each token)
    private var paperCash = PAPER_START_USD
    private val paperUnits = HashMap<String, Double>()
    private val paperDays = ArrayList<Pair<Long, Double>>()

    // live (SOL)
    @Volatile private var killed = false
    @Volatile private var killReason = ""
    @Volatile private var realisedSol = 0.0
    @Volatile private var liveDay = -1L
    @Volatile private var dayStartNav = Double.NaN
    @Volatile private var peakNav = Double.NaN
    @Volatile private var lastNav = Double.NaN
    @Volatile private var lastLive = "-"
    private val orderLog = ArrayDeque<String>()
    @Volatile private var status = "not started"

    fun ensureStarted(ctx: Context) {
        app = ctx.applicationContext
        if (!started.compareAndSet(false, true)) return
        try { DeskJournal8802.init(ctx.applicationContext.filesDir) } catch (_: Throwable) {}
        val t = Thread({
            restore()
            var running = true
            while (running) {
                try { tick(System.currentTimeMillis()) } catch (e: Throwable) { status = "tick error ${e.javaClass.simpleName}" }
                try { Thread.sleep(TICK_MS) } catch (_: InterruptedException) { running = false }
            }
        }, "xs-desk-8802")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.start()
    }

    private fun epochDay(ms: Long): Long = Math.floorDiv(ms, DAY_MS)
    private fun sleepQuietly(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
    private fun cfg(): XsSettings.Config? = app?.let { XsSettings.read(it) }
    private fun solUsd(): Double? = try { com.lifecyclebot.engine.SolUsd8418.liveOrNull() } catch (_: Throwable) { null }
    private fun hasWallet(): Boolean = try { com.lifecyclebot.engine.WalletManager.getWallet() != null } catch (_: Throwable) { false }

    /** Pure: the tradeable xStocks of [markets]: stocks and metals the registry maps to a mint, one per mint. */
    internal fun universeOf(markets: List<PerpsMarket>, mintFor: (String) -> String?): List<Sym> =
        markets.filter { it.isStock || it.isMetal }.mapNotNull { m -> mintFor(m.symbol)?.let { Sym(m.symbol, it, m) } }.distinctBy { it.mint }

    private fun tick(now: Long) {
        if (universe.isEmpty()) universe = universeOf(PerpsMarket.values().toList()) { TokenizedAssetRegistry.mintFor(it) }
        val today = epochDay(now)
        serviceLiveRisk8957()
        if (now - quotesAt >= QUOTE_EVERY_MS) refreshQuotes(now)
        if (today != lastEvalDay && now - today * DAY_MS >= SETTLE_MS && now >= historyRetryAt8810) daily(now, today)
        synchronized(lock) { paperDay(today) }
        serviceLiveRisk8957()
        status = "ok"
    }

    /** History/quote sweeps can take minutes. Service held risk between reads, using the actual current clock. */
    private fun serviceLiveRisk8957() {
        val now = System.currentTimeMillis()
        if (now - riskAt < RISK_EVERY_MS) return
        liveRisk(now, epochDay(now))
    }

    // ── data ──

    private fun probeLamports(): Long {
        val budget = cfg()?.budgetSol?.takeIf { it > 0.0 } ?: XsSettings.DEFAULT_BUDGET_SOL
        return (maxOf(0.05, budget * 0.25) * 1e9).toLong()       // a typical position of the desk
    }

    private fun refreshQuotes(now: Long) {
        val held = heldSymbols()
        val want = universe.filter { panelSyms.isEmpty() || it.symbol in panelSyms || targets.containsKey(it.symbol) || it.symbol in held }
        val lamports = probeLamports()
        for (s in want.sortedByDescending { it.symbol in held }) {
            if (mints[s.symbol] == null) XsApi.mintInfo(s.mint)?.let { mints[s.symbol] = it }
            XsApi.roundTrip(s.mint, lamports)?.let { quotes[s.symbol] = it }
            serviceLiveRisk8957()
            sleepQuietly(CALL_GAP_MS)
        }
        quotesAt = now
    }

    /** Pure: one side's cost of [symbol]: the measured half round trip (when fresh) plus the app fee. */
    internal fun costSideOf(q: XsApi.RoundTrip?, nowMs: Long): Double {
        val measured = q?.takeIf { nowMs - it.atMs in 0L..QUOTE_MAX_AGE_MS }?.costSide?.takeIf { it.isFinite() }
        return (measured ?: UNMEASURED_COST_SIDE) + APP_FEE_SIDE
    }

    private fun costSide(symbol: String): Double = costSideOf(quotes[symbol], System.currentTimeMillis())

    private fun history(s: Sym): HlHistory = histories.getOrPut(s.symbol) { app?.filesDir?.let { HlStore.load(it, s.coin) } ?: HlHistory(s.coin) }

    /** Brings [h] up to yesterday; false when a read went unanswered (true when current, or answered even if empty). */
    private fun fillBars(s: Sym, h: HlHistory, today: Long): Boolean {
        if (h.lastBarDay() >= today - 1) return true
        val cached = pools[s.symbol]
        val pool = if (cached != null && today - cached.second < POOL_REFRESH_DAYS) cached.first else {
            sleepQuietly(CALL_GAP_MS)
            val found = XsApi.poolsOrNull8810(s.mint)
            if (found == null && cached == null) return false                                         // unanswered, nothing known
            found?.firstOrNull()?.address?.also { pools[s.symbol] = it to today } ?: cached?.first ?: return true   // answered: no pool
        }
        sleepQuietly(CALL_GAP_MS)
        val bars = XsApi.dailyBarsOrNull8810(pool, s.mint) ?: return false
        for ((d, b) in bars) if (d < today) h.bars[d] = b      // a day still trading is not a bar
        return true
    }

    /** V5.0.8930 — the panel's copy of [s]'s history: a stock's own bars with its share's before them; the stored [h] unchanged. */
    private fun panelHistory8930(s: Sym, h: HlHistory, today: Long): HlHistory {
        if (!s.market.isStock) return h
        val fromDay = today - 1 - HISTORY_DAYS + 1
        val bars = try { XsUnderlying8930.panelBars8930(s.symbol, h.bars, fromDay, today) } catch (_: Throwable) { h.bars }
        if (bars === h.bars) return h
        sleepQuietly(CALL_GAP_MS)
        return HlHistory(h.coin).also { it.bars.putAll(bars) }
    }

    /** Pure (V5.0.8810): ask the day's history again (not evaluate) when reads went unanswered and the deadline is ahead. */
    internal fun historyRetry8810(unanswered: Int, sinceMidnightMs: Long): Boolean = unanswered > 0 && sinceMidnightMs < HISTORY_DEADLINE_MS_8810

    private fun daily(now: Long, today: Long) {
        val dir = app?.filesDir
        val ready = ArrayList<HlHistory>()
        val excl = LinkedHashMap<String, String>()
        var unanswered = 0
        for ((i, s) in universe.withIndex()) {
            serviceLiveRisk8957()
            status = "loading history ${i + 1}/${universe.size}"   // V5.0.8805
            val h = history(s)
            if (!fillBars(s, h, today)) unanswered++
            h.trim(today - HISTORY_DAYS - 30)
            if (dir != null) HlStore.save(dir, h)
            // V5.0.8930 — listed on the token's own bars; graded on them with the share's history before them (XsUnderlying8930)
            if (h.bars.size >= MIN_LISTED_DAYS) ready.add(panelHistory8930(s, h, today))
            else excl[s.symbol] = if (h.bars.isEmpty()) "NO_HISTORY" else "TOO_NEW_${h.bars.size}d"
        }
        excluded = excl
        historyUnanswered8810 = unanswered
        historyPasses8810++
        if (historyRetry8810(unanswered, now - today * DAY_MS)) { historyRetryAt8810 = now + HISTORY_RETRY_MS_8810; return }
        // A provider outage on startup is not a completed daily evaluation. Keep retrying after the six-hour deadline.
        if (ready.isEmpty()) { historyRetryAt8810 = System.currentTimeMillis() + HISTORY_RETRY_MS_8810; return }
        lastEvalDay = today
        historyPasses8810 = 0
        val panel = HlDesk.panelOf(ready, today - 1, HISTORY_DAYS) { coin -> costSide(coin.removePrefix("xs:")) }
        val ev = HlBrain.evaluate(panel, 1.0, longOnly = true)
        evaluation = ev
        panelSyms = panel.coins.map { it.removePrefix("xs:") }
        targets = panelSyms.withIndex().filter { ev.targets[it.index] > 0.0 }.associate { it.value to ev.targets[it.index] }
        synchronized(lock) { paperRebalance(now) }
        liveRebalance("DAILY", now)
        save()
    }

    // ── marks ──

    /** Pure: [symbol]'s USD mark per unit: the fresh quote's mid, else the last daily close. */
    internal fun markOf(q: XsApi.RoundTrip?, decimals: Int?, solUsd: Double?, lastClose: Double?, nowMs: Long): Double {
        if (q != null && decimals != null && solUsd != null && solUsd > 0.0 && nowMs - q.atMs in 0L..MARK_MAX_AGE_MS) {
            val mid = (q.askUsd(decimals, solUsd) + q.bidUsd(decimals, solUsd)) / 2.0
            if (mid.isFinite() && mid > 0.0) return mid
        }
        return lastClose?.takeIf { it > 0.0 } ?: Double.NaN
    }

    private fun mark(symbol: String): Double {
        val h = histories[symbol]
        val close = h?.bars?.lastEntry()?.value?.get(3)
        return markOf(quotes[symbol], mints[symbol]?.decimals, solUsd(), close, System.currentTimeMillis())
    }

    // ── paper ──

    private fun paperEquity(): Double = paperCash + paperUnits.entries.sumOf { (s, u) -> mark(s).let { if (it.isFinite()) u * it else 0.0 } }

    private fun paperRebalance(now: Long) {
        val eq = paperEquity()
        if (!(eq > 0.0)) return
        val exposure = evaluation?.leverage?.takeIf { it > 0.0 } ?: 1.0
        for (s in (targets.keys + paperUnits.keys).toSet()) {
            val m = mark(s)
            if (!m.isFinite()) continue
            val cur = (paperUnits[s] ?: 0.0) * m
            val want = (targets[s] ?: 0.0) * eq * exposure
            val delta = want - cur
            if (abs(delta) < 1.0) continue                                     // under a dollar: not worth a swap
            val fee = abs(delta) * costSide(s)
            paperCash -= delta + fee
            val units = (paperUnits[s] ?: 0.0) + delta / m
            if (abs(units * m) < 0.01) paperUnits.remove(s) else paperUnits[s] = units
            try { DeskJournal8802.record(DeskJournal8802.Fill(now, "XSTOCKS", "PAPER", s, delta / m, m, fee, "DAILY")) } catch (_: Throwable) {}
        }
    }

    private fun paperDay(today: Long) {
        if (paperDays.isEmpty() || paperDays.last().first != today) {
            val eq = paperEquity()
            if (eq > 0.0) { paperDays.add(today to eq); while (paperDays.size > 400) paperDays.removeAt(0) }
        }
    }

    private fun paperReturns(): DoubleArray =
        if (paperDays.size < 2) DoubleArray(0) else DoubleArray(paperDays.size - 1) { i -> paperDays[i + 1].second / paperDays[i].second - 1.0 }

    // ── live ──

    private fun livePositions(): List<com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.Position> = try {
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions().filter { it.lane == LANE && it.mode == "live" }
    } catch (_: Throwable) { emptyList() }

    private fun heldSymbols(): Set<String> = livePositions().map { it.symbol }.toSet()

    /** Pure: why live may not trade now, or null. */
    internal fun liveGate(settingsRefusal: String?, armed: Boolean, killed: Boolean): String? = when {
        settingsRefusal != null -> settingsRefusal
        killed -> "STOP_HIT_SWITCH_LIVE_OFF_AND_ON"
        !armed -> "BRAIN_NOT_ARMED"
        else -> null
    }

    /** Pure: which stop (if any) [nav] breaks against the day's start and the peak. */
    internal fun stopHit(nav: Double, dayStart: Double, peak: Double, dailyStopPct: Double, maxDrawdownPct: Double): String? = when {
        !(nav > 0.0) -> null
        dayStart > 0.0 && nav < dayStart * (1.0 - dailyStopPct / 100.0) -> "DAILY_STOP"
        peak > 0.0 && nav < peak * (1.0 - maxDrawdownPct / 100.0) -> "DRAWDOWN_STOP"
        else -> null
    }

    /** Pure: the SOL each target gets to open with: weight x exposure x budget, none under [MIN_LIVE_SOL]. */
    internal fun openSizes(targets: Map<String, Double>, exposure: Double, budgetSol: Double, held: Set<String>): Map<String, Double> =
        targets.filterKeys { it !in held }.mapValues { (_, w) -> w * exposure.coerceIn(0.0, 1.0) * budgetSol }.filterValues { it >= MIN_LIVE_SOL }

    /** The desk's live value in SOL: budget + realised + what its open positions would sell for now less their cost. */
    private fun nav(budgetSol: Double): Double? {
        val sol = solUsd() ?: return null
        var unrealised = 0.0
        for (p in livePositions()) {
            val bid = quotes[p.symbol]?.takeIf { System.currentTimeMillis() - it.atMs in 0L..MARK_MAX_AGE_MS }?.bidUsd(p.tokenDecimals, sol) ?: return null
            val units = p.remainingQtyRaw.toBigDecimal().movePointLeft(p.tokenDecimals).toDouble()
            unrealised += units * bid / sol - (p.entryCostSol - p.soldCostBasisSol)
        }
        return budgetSol + realisedSol + unrealised
    }

    private fun note(s: String) {
        lastLive = s
        synchronized(orderLog) {
            orderLog.addLast(java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()) + " " + s)
            while (orderLog.size > 40) orderLog.removeFirst()
        }
    }

    private fun open(s: Sym, sizeSol: Double, why: String, now: Long) {
        val sol = solUsd() ?: run { note("$why ${s.symbol}: SOL/USD unknown, no order"); return }
        val ask = quotes[s.symbol]?.let { q -> mints[s.symbol]?.let { q.askUsd(it.decimals, sol) } } ?: Double.NaN
        if (!ask.isFinite()) { note("$why ${s.symbol}: no executable quote, no order"); return }
        val id = "XS8802:${s.symbol}:$now"
        val fill = try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                com.lifecyclebot.perps.MarketsLiveExecutor.executeLiveTradeProof6486(id, s.market, PerpsDirection.LONG, sizeSol, 1.0, ask, LANE)
            }
        } catch (e: Throwable) { note("$why BUY ${s.symbol}: ${e.javaClass.simpleName}"); return }
        if (!fill.confirmed) { note("$why BUY ${s.symbol} ${"%.4f".format(sizeSol)} SOL: ${fill.state} ${fill.reason.take(80)}"); return }
        val units = fill.filledQtyRaw.toBigDecimal().movePointLeft(fill.decimals).toDouble()
        val px = if (units > 0.0) fill.principalSol * sol / units else ask
        note("$why BUY ${s.symbol} ${"%.4f".format(fill.principalSol)} SOL -> ${"%.6g".format(units)} @ \$${"%.4f".format(px)} sig=${fill.signature?.take(12)}")
        try { DeskJournal8802.record(DeskJournal8802.Fill(now, "XSTOCKS", "LIVE", s.symbol, units, px, fill.feeSol * sol, why, fill.signature.orEmpty())) } catch (_: Throwable) {}
    }

    private fun close(p: com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.Position, why: String, now: Long) {
        val s = universe.firstOrNull { it.symbol == p.symbol } ?: return
        val r = try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                com.lifecyclebot.perps.MarketsLiveExecutor.closeLivePositionProof6486(p.positionId, s.market, PerpsDirection.LONG,
                    p.entryCostSol, 1.0, LANE, exitReason = "XS8802_$why", entryTactic = "XS8802")
            }
        } catch (e: Throwable) { note("$why SELL ${p.symbol}: ${e.javaClass.simpleName}"); return }
        if (!r.confirmed) { note("$why SELL ${p.symbol}: ${r.reason.take(80)}"); return }
        val basis = p.entryCostSol - p.soldCostBasisSol
        realisedSol += r.proceedsSol - basis
        val sol = solUsd() ?: 0.0
        val units = r.soldQtyRaw.toBigDecimal().movePointLeft(p.tokenDecimals).toDouble()
        note("$why SELL ${p.symbol} ${"%.6g".format(units)} -> ${"%.4f".format(r.proceedsSol)} SOL (cost ${"%.4f".format(basis)})")
        if (units > 0.0 && sol > 0.0) try {
            DeskJournal8802.record(DeskJournal8802.Fill(now, "XSTOCKS", "LIVE", p.symbol, -units, r.proceedsSol * sol / units, 0.0, why, r.signature.orEmpty()))
        } catch (_: Throwable) {}
        save()
    }

    private fun liveRebalance(why: String, now: Long) {
        val cfg = cfg()
        val ev = evaluation
        val refusal = liveGate(XsSettings.liveRefusal(cfg, hasWallet()), ev?.armed == true, killed)
        if (refusal != null || cfg == null || ev == null) { lastLive = "not trading: $refusal"; return }
        val held = livePositions()
        for (p in held) if ((targets[p.symbol] ?: 0.0) <= 0.0) close(p, why, now)
        val sizes = openSizes(targets, ev.leverage, cfg.budgetSol, held.map { it.symbol }.toSet())
        for ((sym, size) in sizes) universe.firstOrNull { it.symbol == sym }?.let { open(it, size, why, now) }
        if (sizes.isEmpty()) lastLive = "$why: on target (${held.size} held, exposure ${"%.2f".format(ev.leverage)})"
    }

    private fun liveRisk(now: Long, today: Long) {
        // A stop closes on confirmed fills. Failed sells remain owned and must be retried after the desk is killed.
        if (killed) {
            riskAt = now
            for (p in livePositions()) close(p, killReason.ifBlank { "STOP_RETRY" }, now)
            return
        }
        val cfg = cfg() ?: return
        if (XsSettings.liveRefusal(cfg, hasWallet()) != null) return
        val nav = nav(cfg.budgetSol) ?: return
        riskAt = now
        lastNav = nav
        if (liveDay != today) { liveDay = today; dayStartNav = nav; save() }
        if (!(peakNav >= nav)) { peakNav = nav; save() }
        val hit = stopHit(nav, dayStartNav, peakNav, cfg.dailyStopPct, cfg.maxDrawdownPct) ?: return
        val held = livePositions()
        if (held.isEmpty()) return
        killed = true; killReason = hit
        note("$hit: NAV ${"%.4f".format(nav)} SOL (day start ${"%.4f".format(dayStartNav)}, peak ${"%.4f".format(peakNav)}) -> sell all, live off")
        for (p in held) close(p, hit, now)
        save()
    }

    /** HyperliquidActivity (xStocks section): the owner switched live back on after a stop. */
    fun clearKill() { killed = false; killReason = ""; peakNav = Double.NaN; liveDay = -1L; save() }

    /** HyperliquidActivity: sell every live xStocks position now and switch live off. */
    fun sellAllNow(): String {
        val ctx = app ?: return "not started"
        val cfg = XsSettings.read(ctx) ?: return "settings unreadable"
        XsSettings.write(ctx, cfg.copy(live = false))
        Thread({ val now = System.currentTimeMillis(); for (p in livePositions()) close(p, "SELL_ALL", now) }, "xs-sell-8802").start()
        return "selling ${livePositions().size} xStocks positions; live switched off"
    }

    /** V5.0.8804 — the Markets screen: every open paper and live xStocks position. */
    fun positionRows8804(): List<DeskJournal8802.DeskRow> {
        val rows = ArrayList<DeskJournal8802.DeskRow>()
        synchronized(lock) {
            for ((s, u) in paperUnits) rows.add(DeskJournal8802.DeskRow("XSTOCKS", "PAPER", s, u,
                DeskJournal8802.lot("XSTOCKS", "PAPER", s)?.second ?: Double.NaN, mark(s)))
        }
        val sol = solUsd()
        for (p in livePositions()) {
            val units = p.remainingQtyRaw.toBigDecimal().movePointLeft(p.tokenDecimals).toDouble()
            val avg = if (units > 0.0 && sol != null) (p.entryCostSol - p.soldCostBasisSol) * sol / units else Double.NaN
            rows.add(DeskJournal8802.DeskRow("XSTOCKS", "LIVE", p.symbol, units, avg, mark(p.symbol), "desk:XSTOCKS:${p.positionId}"))
        }
        return rows
    }

    /** V5.0.8804 — the Markets screen's close button on one live xStocks position (sold for SOL now). */
    fun closeLive8804(positionId: String): Boolean {
        val p = livePositions().firstOrNull { it.positionId == positionId } ?: return false
        close(p, "USER", System.currentTimeMillis())
        return livePositions().none { it.positionId == positionId }
    }

    fun isRunning8804(): Boolean = started.get()

    // ── persistence ──

    private fun file(): File? = app?.filesDir?.let { File(it, FILE) }

    private fun save() {
        try {
            val f = file() ?: return
            val o = JSONObject()
            synchronized(lock) {
                o.put("paperCash", paperCash)
                o.put("paperUnits", JSONObject().also { u -> for ((k, v) in paperUnits) u.put(k, v) })
                o.put("paperDays", JSONArray().also { a -> for ((d, e) in paperDays) a.put(JSONArray().put(d).put(e)) })
            }
            o.put("pools", JSONObject().also { p -> for ((k, v) in pools) p.put(k, JSONArray().put(v.first).put(v.second)) })
            o.put("killed", killed).put("killReason", killReason).put("realisedSol", realisedSol).put("liveDay", liveDay)
                .put("dayStartNav", if (dayStartNav.isFinite()) dayStartNav else -1.0).put("peakNav", if (peakNav.isFinite()) peakNav else -1.0)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        } catch (_: Throwable) {}
    }

    private fun restore() {
        try {
            val f = file()?.takeIf { it.exists() } ?: return
            val o = JSONObject(f.readText())
            synchronized(lock) {
                paperCash = o.optDouble("paperCash", PAPER_START_USD)
                o.optJSONObject("paperUnits")?.let { u -> for (k in u.keys()) paperUnits[k] = u.getDouble(k) }
                o.optJSONArray("paperDays")?.let { a -> for (i in 0 until a.length()) { val p = a.getJSONArray(i); paperDays.add(p.getLong(0) to p.getDouble(1)) } }
            }
            o.optJSONObject("pools")?.let { p -> for (k in p.keys()) { val v = p.getJSONArray(k); pools[k] = v.getString(0) to v.getLong(1) } }
            killed = o.optBoolean("killed", false); killReason = o.optString("killReason", "")
            realisedSol = o.optDouble("realisedSol", 0.0); liveDay = o.optLong("liveDay", -1L)
            dayStartNav = o.optDouble("dayStartNav", -1.0).let { if (it > 0.0) it else Double.NaN }
            peakNav = o.optDouble("peakNav", -1.0).let { if (it > 0.0) it else Double.NaN }
        } catch (_: Throwable) {}
    }

    // ── report ──

    private fun line(r: HlBrain.Result?): String = r?.let {
        "n${it.days}d ${"%+.3f".format(it.meanPct)}±${"%.3f".format(it.sePct)}%/d sh${"%.2f".format(it.sharpe)} tot${"%+.1f".format(it.totalPct)}% " +
            "dd${"%.1f".format(it.maxDrawdownPct)}% turn${"%.2f".format(it.turnover)}"
    } ?: "-"

    fun statusLine(): String {
        val ev = evaluation
        val now = System.currentTimeMillis()
        val paper = synchronized(lock) {
            val g = HlBrain.grade("paper", paperReturns())
            "equity\$${"%.2f".format(paperEquity())} days=${paperDays.size} positions=${paperUnits.size}" + (g?.let { " record{${line(it)}}" } ?: "")
        }
        val cfg = cfg()
        val gate = liveGate(XsSettings.liveRefusal(cfg, hasWallet()), ev?.armed == true, killed)
        val picks = ev?.let { e -> e.picks.joinToString(" ") { p ->
            "${p.family.name.lowercase()}=${p.chosen.id}[train{${line(p.train)}} test{${line(p.test)}}${e.blend[p.chosen]?.let { " w${"%.2f".format(it)}" } ?: ""}]"
        } } ?: "-"
        val costs = quotes.entries.sortedBy { it.key }.take(10).joinToString(" ") { (s, q) ->
            "$s=${"%.2f".format(costSideOf(q, now) * 100)}%${if (now - q.atMs > QUOTE_MAX_AGE_MS) "(stale)" else ""}"
        }
        val held = livePositions()
        return "xstocks8802[$status ${XsApi.line()} ${XsUnderlying8930.line8930()} history8810{unanswered=$historyUnanswered8810 passesToday=$historyPasses8810 " +
            "retryInS=${((historyRetryAt8810 - now) / 1000).coerceAtLeast(0)}} universe=${universe.size} graded=${panelSyms.size} excluded{${excluded.entries.take(10).joinToString(" ") { "${it.key}:${it.value}" }}} " +
            "history=${ev?.days ?: 0}d ${if (ev?.armed == true) "ARMED" else "SHADOW"} exposure=${"%.2f".format(ev?.leverage ?: 0.0)} " +
            "blend{train{${line(ev?.train)}} test{${line(ev?.test)}}} families{$picks} " +
            "targets{${targets.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}${"%.0f".format(it.value * 100)}%" }.ifBlank { "flat" }}} " +
            "costSide{${costs.ifBlank { "unquoted" }}} scaledUi{${mints.entries.filter { it.value.multiplier != 1.0 }.joinToString(" ") { "${it.key}x${"%.4f".format(it.value.multiplier)}" }.ifBlank { "none" }}} " +
            "paper{$paper} live{${gate ?: "ON"}${if (killReason.isNotEmpty()) "($killReason)" else ""} held=${held.size}{${held.joinToString(",") { it.symbol }}} " +
            "budget=${cfg?.budgetSol ?: 0.0}SOL nav=${if (lastNav.isFinite()) "%.4f".format(lastNav) else "-"} realised=${"%+.4f".format(realisedSol)}SOL last=${lastLive.take(140)}} " +
            "rule=long or flat, 1x; executable Jupiter round trip + 0.5% app fee per side; history = the token's own pool; armed = second half >= ${HlBrain.MIN_TEST_DAYS}d and mean - SE > 0; 24/7]"
    }

    fun orderLogLines(): List<String> = synchronized(orderLog) { orderLog.toList().asReversed() }
}
