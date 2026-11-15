package com.lifecyclebot.hyperliquid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8795 / V5.0.8797 §HYPERLIQUID_DESK — the bot's perps trader: Hyperliquid's own perps plus the HIP-3 builder markets
 * (stocks, indices, metals, commodities), on its own background thread, separate from the meme engine. It replaces the
 * retired Solana markets traders (RetiredMarkets8797).
 *
 * Every minute:
 *  - every 5 minutes: every dex's live context (marks, funding, open interest, premium); open interest and premium are
 *    recorded into each coin's history (the exchange keeps none) — HlStore;
 *  - every hour: each universe coin's order book, measured at the desk's own order size — HlCosts;
 *  - once per UTC day (5 minutes after midnight): the universe (the [N_MAIN] most-traded perps and the [N_HIP3] most-traded
 *    HIP-3 markets, less what cannot be traded: open-interest cap, non-USDC collateral, too little history), each coin's
 *    new daily candles and funding (incremental, cached on disk), the brain (HlBrain: five families, chosen on the first
 *    half of the history, graded on the second, blended, armed, half-Kelly leverage), and the day's targets;
 *  - the PAPER book always trades the blend at the marks (each coin's own cost, real hourly funding), so the desk keeps a
 *    forward record whatever live does;
 *  - LIVE trades it only when the owner's settings allow it, the brain is ARMED, and neither the daily stop nor the
 *    drawdown stop has fired (HlRisk sizes it, HlExecutor sends it). Either stop flattens everything and switches live
 *    off until the owner switches it on again.
 */
object HlDesk {
    private const val N_MAIN = 15
    private const val N_HIP3 = 15
    private const val HISTORY_DAYS = 420
    private const val MIN_LISTED_DAYS = 60
    private const val TICK_MS = 60_000L
    private const val MARK_EVERY_MS = 5L * 60_000L
    private const val BOOK_EVERY_MS = 60L * 60_000L
    private const val RISK_EVERY_MS = 5L * 60_000L
    private const val DAY_MS = 86_400_000L
    private const val SETTLE_MS = 5L * 60_000L
    private const val PAPER_START_USD = 1_000.0
    private const val CALL_GAP_MS = 3_000L
    private const val FUNDING_PAGE = 500
    private const val FILE = "hl_desk_8797.json"

    @Volatile private var app: Context? = null
    private val started = AtomicBoolean(false)
    private val lock = Any()

    // market state
    @Volatile private var dexNames: List<String> = listOf("")
    @Volatile private var marks: Map<String, HlApi.Asset> = emptyMap()
    @Volatile private var collateral: Map<String, Int> = emptyMap()
    @Volatile private var marksAt = 0L
    @Volatile private var booksAt = 0L
    @Volatile private var riskAt = 0L
    private val histories = ConcurrentHashMap<String, HlHistory>()
    @Volatile private var universe: List<String> = emptyList()
    @Volatile private var excluded: Map<String, String> = emptyMap()
    @Volatile private var evaluation: HlBrain.Evaluation? = null
    @Volatile private var panelCoins: List<String> = emptyList()
    @Volatile private var targets: Map<String, Double> = emptyMap()
    @Volatile private var vols: Map<String, Double> = emptyMap()
    @Volatile private var lastEvalDay = -1L

    // paper book (USD)
    private var paperCash = PAPER_START_USD
    private val paperUnits = HashMap<String, Double>()
    private val paperDays = ArrayList<Pair<Long, Double>>()
    private var paperFundingHour = -1L

    // live
    @Volatile private var killed = false
    @Volatile private var killReason = ""
    @Volatile private var liveDay = -1L
    @Volatile private var liveDayStartValue = Double.NaN
    @Volatile private var livePeak = Double.NaN
    @Volatile private var liveEquity = Double.NaN
    @Volatile private var liveDexEquity: Map<String, Double> = emptyMap()
    @Volatile private var lastLive = "-"
    private val orderLog = ArrayDeque<String>()
    @Volatile private var status = "not started"

    fun ensureStarted(ctx: Context) {
        app = ctx.applicationContext
        if (!started.compareAndSet(false, true)) return
        try { com.lifecyclebot.desks.DeskJournal8802.init(ctx.applicationContext.filesDir) } catch (_: Throwable) {}
        val t = Thread({
            restore()
            var running = true
            while (running) {
                try { tick(System.currentTimeMillis()) } catch (e: Throwable) { status = "tick error ${e.javaClass.simpleName}" }
                try { Thread.sleep(TICK_MS) } catch (_: InterruptedException) { running = false }
            }
        }, "hl-desk-8795")
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.start()
    }

    private fun epochDay(ms: Long): Long = Math.floorDiv(ms, DAY_MS)
    private fun sleepQuietly(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
    private fun cfg(): HlSettings.Config? = app?.let { HlSettings.read(it) }

    private fun tick(now: Long) {
        val today = epochDay(now)
        if (now - marksAt >= MARK_EVERY_MS) refreshMarks(now, today)
        if (now - booksAt >= BOOK_EVERY_MS && universe.isNotEmpty() && marks.isNotEmpty()) refreshBooks(now)
        if (today != lastEvalDay && now - today * DAY_MS >= SETTLE_MS && marks.isNotEmpty()) daily(now, today)
        synchronized(lock) { paperTick(now, today) }
        if (now - riskAt >= RISK_EVERY_MS) { riskAt = now; liveRisk(now, today) }
        status = "ok"
    }

    // ── data ──

    private fun refreshMarks(now: Long, today: Long) {
        val hip3 = cfg()?.hip3 != false
        if (dexNames.size <= 1 || lastEvalDay != today) HlApi.perpDexs(HlApi.MAINNET)?.let { dexNames = it }
        val all = HashMap<String, HlApi.Asset>()
        val coll = HashMap<String, Int>()
        for ((i, dex) in dexNames.withIndex()) {
            if (i > 0 && !hip3) continue
            val d = HlApi.metaAndCtxs(HlApi.MAINNET, dex, i) ?: continue
            coll[dex] = d.collateralToken
            for (a in d.assets) all[a.name] = a
            if (i > 0) sleepQuietly(300L)
        }
        if (all.isEmpty()) return
        marks = all; collateral = coll; marksAt = now
        for (coin in universe) {
            val a = all[coin] ?: continue
            histories[coin]?.observe(today, a.openInterest, a.markPx, a.premium)
        }
    }

    private fun bookSize(): Double {
        val live = liveEquity.takeIf { it > 0.0 }
        val lev = evaluation?.leverage?.takeIf { it > 0.0 } ?: 1.0
        val gross = (live ?: synchronized(lock) { paperEquity() }) * lev
        return maxOf(25.0, gross * 0.1)                       // a typical day's trade in one coin
    }

    private fun refreshBooks(now: Long) {
        val size = bookSize()
        for (coin in universe) {
            HlApi.book(HlApi.MAINNET, coin)?.let { HlCosts.observeBook(coin, it, size) }
            sleepQuietly(300L)
        }
        booksAt = now
    }

    private fun history(coin: String): HlHistory = histories.getOrPut(coin) { app?.filesDir?.let { HlStore.load(it, coin) } ?: HlHistory(coin) }

    /** Pure: today's tradeable universe and why each other candidate is out. */
    internal fun chooseUniverse(assets: Collection<HlApi.Asset>, collateral: Map<String, Int>, atCap: Set<String>,
                                held: Set<String>, nMain: Int, nHip3: Int): Pair<List<String>, Map<String, String>> {
        val out = LinkedHashMap<String, String>()
        val ok = assets.sortedByDescending { if (it.dayNtlVlm.isFinite()) it.dayNtlVlm else 0.0 }.filter { a ->
            val why = when {
                !(a.markPx > 0.0) -> "NO_MARK"
                (collateral[a.dex] ?: 0) != 0 -> "NON_USDC_COLLATERAL"
                a.name in atCap -> "AT_OPEN_INTEREST_CAP"
                !(a.dayNtlVlm > 0.0) -> "NO_VOLUME"
                else -> null
            }
            if (why != null && a.dayNtlVlm > 0.0) out[a.name] = why
            why == null
        }
        val main = ok.filter { it.dex.isEmpty() }.take(nMain).map { it.name }
        val hip3 = ok.filter { it.dex.isNotEmpty() }.take(nHip3).map { it.name }
        val chosen = (main + hip3 + held.filter { h -> assets.any { it.name == h } }).distinct()
        return chosen to out.filterKeys { it !in chosen }
    }

    private fun fillCandles(h: HlHistory, today: Long, now: Long) {
        val from = maxOf(h.lastBarDay() + 1, today - HISTORY_DAYS)
        if (from >= today) return
        val cs = HlApi.candles(HlApi.MAINNET, h.coin, "1d", from * DAY_MS, now) ?: return
        for (c in cs) {
            val d = epochDay(c.t)
            if (d >= today || !(c.c > 0.0)) continue                 // a day that has not closed is still moving
            h.bars[d] = doubleArrayOf(c.o, c.h, c.l, c.c, if (c.v.isFinite()) c.v * c.c else Double.NaN)
        }
        sleepQuietly(CALL_GAP_MS)
    }

    private fun fillFunding(h: HlHistory, today: Long, now: Long) {
        if (h.fundingFromDay < 0L) h.fundingFromDay = if (h.bars.isNotEmpty()) h.bars.firstKey() else today - HISTORY_DAYS
        var cursor = maxOf(h.fundingFromDay, h.lastFundingDay() + 1) * DAY_MS
        var pages = 0
        while (cursor < today * DAY_MS && pages < 40) {
            val rows = HlApi.fundingHistory(HlApi.MAINNET, h.coin, cursor, now) ?: break
            if (rows.isEmpty()) break
            val byDay = HashMap<Long, Double>()
            for (r in rows) if (r.rate.isFinite()) byDay[epochDay(r.time)] = (byDay[epochDay(r.time)] ?: 0.0) + r.rate
            // a full page may end mid-day: that day is read whole by the next page; today is never stored (still filling)
            val lastDay = epochDay(rows.last().time)
            val full = rows.size >= FUNDING_PAGE
            for ((d, v) in byDay) if (d < today && (!full || d < lastDay)) h.funding[d] = v
            cursor = if (full) lastDay * DAY_MS else now
            pages++
            sleepQuietly(CALL_GAP_MS)
        }
    }

    /** Pure: the aligned panel for [coins] over the [days] days ending [lastDay]. */
    internal fun panelOf(coins: List<HlHistory>, lastDay: Long, days: Int, cost: (String) -> Double): HlBrain.Panel {
        val first = lastDay - days + 1
        fun arr(f: (HlHistory, Long) -> Double) = Array(coins.size) { c -> DoubleArray(days) { i -> f(coins[c], first + i) } }
        return HlBrain.Panel(coins.map { it.coin }, first,
            arr { h, d -> h.bars[d]?.get(3) ?: Double.NaN },
            arr { h, d -> h.bars[d]?.get(4) ?: Double.NaN },
            arr { h, d -> h.funding[d] ?: Double.NaN },
            arr { h, d -> h.oi[d] ?: Double.NaN },
            arr { h, d -> h.premium[d]?.let { if (it[1] > 0.0) it[0] / it[1] else Double.NaN } ?: Double.NaN },
            DoubleArray(coins.size) { cost(coins[it].coin) })
    }

    private fun daily(now: Long, today: Long) {
        val cfg = cfg()
        val atCap = HashSet<String>()
        for ((i, dex) in dexNames.withIndex()) if (i == 0 || cfg?.hip3 != false) HlApi.atOpenInterestCap(HlApi.MAINNET, dex)?.let { atCap += it }
        val held = synchronized(lock) { paperUnits.keys.toSet() } + targets.keys
        val (chosen, out) = chooseUniverse(marks.values, collateral, atCap, held, N_MAIN, if (cfg?.hip3 != false) N_HIP3 else 0)
        if (chosen.isEmpty()) return
        val dir = app?.filesDir
        val ready = ArrayList<HlHistory>()
        val excl = LinkedHashMap(out)
        for (coin in chosen) {
            val h = history(coin)
            fillCandles(h, today, now)
            if (h.bars.isNotEmpty()) fillFunding(h, today, now)
            h.trim(today - HISTORY_DAYS - 30)
            if (dir != null) HlStore.save(dir, h)
            if (h.bars.size >= MIN_LISTED_DAYS) ready.add(h) else excl[coin] = "TOO_NEW_${h.bars.size}d"
        }
        universe = chosen
        excluded = excl
        if (ready.isEmpty()) { lastEvalDay = today; return }
        val panel = panelOf(ready, today - 1, HISTORY_DAYS) { HlCosts.costSide(it) }
        val ev = HlBrain.evaluate(panel, cfg?.maxLeverage ?: HlSettings.DEFAULT_MAX_LEVERAGE)
        evaluation = ev
        panelCoins = panel.coins
        targets = panel.coins.withIndex().filter { ev.targets[it.index] != 0.0 }.associate { it.value to ev.targets[it.index] }
        vols = panel.coins.withIndex().associate { (i, c) -> c to (panel.vol[i].lastOrNull() ?: Double.NaN) }
        lastEvalDay = today
        synchronized(lock) { paperRebalance() }
        liveRebalance("DAILY", now)
        save()
    }

    // ── paper ──

    private fun markOf(coin: String): Double = marks[coin]?.markPx?.takeIf { it > 0.0 } ?: Double.NaN

    private fun paperEquity(): Double = paperCash + paperUnits.entries.sumOf { (c, u) -> markOf(c).let { if (it.isFinite()) u * it else 0.0 } }

    private fun paperRebalance() {
        val eq = paperEquity()
        if (!(eq > 0.0)) return
        val lev = evaluation?.leverage?.takeIf { it > 0.0 } ?: 1.0
        for (coin in (targets.keys + paperUnits.keys).toSet()) {
            val m = markOf(coin)
            if (!m.isFinite()) continue
            val cur = (paperUnits[coin] ?: 0.0) * m
            val want = (targets[coin] ?: 0.0) * eq * lev
            val delta = want - cur
            if (kotlin.math.abs(delta) < 1e-9) continue
            paperCash -= delta + kotlin.math.abs(delta) * HlCosts.costSide(coin)
            // V5.0.8802 — the paper book's fills reach the desk's trade book too
            try {
                com.lifecyclebot.desks.DeskJournal8802.record(com.lifecyclebot.desks.DeskJournal8802.Fill(System.currentTimeMillis(),
                    "HYPERLIQUID", "PAPER", coin, delta / m, m, kotlin.math.abs(delta) * HlCosts.costSide(coin), "DAILY"))
            } catch (_: Throwable) {}
            val units = (paperUnits[coin] ?: 0.0) + delta / m
            if (kotlin.math.abs(units * m) < 1e-6) paperUnits.remove(coin) else paperUnits[coin] = units
        }
    }

    private fun paperTick(now: Long, today: Long) {
        val hour = Math.floorDiv(now, 3_600_000L)
        if (hour != paperFundingHour && marks.isNotEmpty()) {
            if (paperFundingHour >= 0L) for ((coin, u) in paperUnits) {
                val a = marks[coin] ?: continue
                if (a.fundingHourly.isFinite() && a.markPx > 0.0) paperCash -= u * a.markPx * a.fundingHourly * (hour - paperFundingHour).coerceIn(1L, 24L)
            }
            paperFundingHour = hour
        }
        if (marks.isNotEmpty() && (paperDays.isEmpty() || paperDays.last().first != today)) {
            paperDays.add(today to paperEquity())
            while (paperDays.size > 400) paperDays.removeAt(0)
        }
    }

    private fun paperReturns(): DoubleArray {
        if (paperDays.size < 2) return DoubleArray(0)
        return DoubleArray(paperDays.size - 1) { i -> paperDays[i + 1].second / paperDays[i].second - 1.0 }
    }

    // ── live ──

    /** Pure: why live may not trade now, or null. */
    internal fun liveGate(settingsRefusal: String?, armed: Boolean, killed: Boolean): String? = when {
        settingsRefusal != null -> settingsRefusal
        killed -> "STOP_HIT_SWITCH_LIVE_OFF_AND_ON"
        !armed -> "BRAIN_NOT_ARMED"
        else -> null
    }

    /** Pure: which stop (if any) [value] breaks against the day's start and the account's peak. */
    internal fun stopHit(value: Double, dayStart: Double, peak: Double, dailyStopPct: Double, maxDrawdownPct: Double): String? = when {
        !(value > 0.0) -> null
        dayStart > 0.0 && value < dayStart * (1.0 - dailyStopPct / 100.0) -> "DAILY_STOP"
        peak > 0.0 && value < peak * (1.0 - maxDrawdownPct / 100.0) -> "DRAWDOWN_STOP"
        else -> null
    }

    private fun base(cfg: HlSettings.Config) = if (cfg.testnet) HlApi.TESTNET else HlApi.MAINNET
    private fun dexesOf(coins: Collection<String>): Set<String> = coins.filter { it.contains(':') }.map { it.substringBefore(':') }.toSet()

    private fun liveRisk(now: Long, today: Long) {
        val cfg = cfg() ?: return
        if (HlSettings.liveRefusal(cfg) != null || killed) return
        val live = HlExecutor.read(base(cfg), cfg.account, dexesOf(universe + targets.keys)) ?: return
        val value = live.equity
        liveEquity = value
        livePositions8804 = live.positions.mapValues { (c, p) -> p to (live.markets[c]?.markPx ?: Double.NaN) }
        liveDexEquity = live.accounts.mapValues { it.value.accountValue }
        if (!(value > 0.0)) return
        if (liveDay != today) { liveDay = today; liveDayStartValue = value; save() }
        if (!(livePeak >= value)) { livePeak = value; save() }
        val hit = stopHit(value, liveDayStartValue, livePeak, cfg.dailyStopPct, cfg.maxDrawdownPct) ?: return
        if (live.positions.isEmpty()) return
        killed = true; killReason = hit
        note("$hit: value ${"%.2f".format(value)} (day start ${"%.2f".format(liveDayStartValue)}, peak ${"%.2f".format(livePeak)}) -> flatten, live off")
        HlExecutor.flatten(cfg, base(cfg), live, hit, now, ::note)
        save()
    }

    private fun note(s: String) {
        lastLive = s
        synchronized(orderLog) {
            orderLog.addLast(java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()) + " " + s)
            while (orderLog.size > 40) orderLog.removeFirst()
        }
    }

    private fun liveRebalance(why: String, now: Long) {
        val cfg = cfg()
        val ev = evaluation
        val refusal = liveGate(HlSettings.liveRefusal(cfg), ev?.armed == true, killed)
        if (refusal != null || cfg == null || ev == null) { lastLive = "not trading: $refusal"; return }
        val live = HlExecutor.read(base(cfg), cfg.account, dexesOf(universe + targets.keys)) ?: run { note("no answer from ${base(cfg)}"); return }
        liveEquity = live.equity
        liveDexEquity = live.accounts.mapValues { it.value.accountValue }
        livePositions8804 = live.positions.mapValues { (c, p) -> p to (live.markets[c]?.markPx ?: Double.NaN) }
        val plans = HlRisk.plan(targets, ev.leverage, liveDexEquity, cfg.maxNotionalUsd, live.markets, vols)
        val unfunded = dexesOf(targets.keys).filter { !((liveDexEquity[it] ?: 0.0) > 0.0) }
        if (unfunded.isNotEmpty()) note("$why: HIP-3 dex ${unfunded.joinToString(",")} has no balance — its coins wait (move USDC to it on Hyperliquid)")
        val sent = HlExecutor.rebalance(cfg, base(cfg), live, plans, why, now, ::note)
        if (sent == 0) lastLive = "$why: on target (equity \$${"%.2f".format(live.equity)}, lev ${"%.2f".format(ev.leverage)}x)"
    }

    /** HyperliquidActivity: the owner switched live back on after a stop. */
    fun clearKill() { killed = false; killReason = ""; livePeak = Double.NaN; liveDay = -1L; save() }

    /** HyperliquidActivity: close every live position on every dex now (reduce-only), and switch live off. */
    fun flattenNow(): String {
        val ctx = app ?: return "not started"
        val cfg = HlSettings.read(ctx) ?: return "settings unreadable"
        if (!HlSigner.validKey(cfg.agentKey) || !HlSigner.validAddress(cfg.account)) return "no account or API wallet key"
        HlSettings.write(ctx, cfg.copy(live = false, agentKey = ""))
        Thread({
            val base = base(cfg)
            val names = HlApi.perpDexs(base)?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
            HlExecutor.read(base, cfg.account, names)?.let { HlExecutor.flatten(cfg, base, it, "FLATTEN", System.currentTimeMillis(), ::note) }
                ?: note("flatten: no account answer")
        }, "hl-flatten-8795").start()
        return "flattening every dex; live switched off"
    }

    @Volatile private var livePositions8804: Map<String, Pair<HlApi.Position, Double>> = emptyMap()

    /** V5.0.8804 — the Markets screen: every open paper and (last read) live position of the desk. */
    fun positionRows8804(): List<com.lifecyclebot.desks.DeskJournal8802.DeskRow> {
        val rows = ArrayList<com.lifecyclebot.desks.DeskJournal8802.DeskRow>()
        synchronized(lock) {
            for ((coin, u) in paperUnits) {
                val avg = com.lifecyclebot.desks.DeskJournal8802.lot("HYPERLIQUID", "PAPER", coin)?.second ?: Double.NaN
                rows.add(com.lifecyclebot.desks.DeskJournal8802.DeskRow("HYPERLIQUID", "PAPER", coin, u, avg, markOf(coin)))
            }
        }
        for ((coin, pm) in livePositions8804) rows.add(com.lifecyclebot.desks.DeskJournal8802.DeskRow("HYPERLIQUID",
            if (cfg()?.testnet == true) "TESTNET" else "LIVE", coin, pm.first.szi, pm.first.entryPx, pm.second))
        return rows
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
                o.put("paperFundingHour", paperFundingHour)
            }
            o.put("costs", HlCosts.toJson()).put("universe", JSONArray(universe))
            o.put("killed", killed).put("killReason", killReason).put("liveDay", liveDay)
                .put("liveDayStartValue", if (liveDayStartValue.isFinite()) liveDayStartValue else -1.0)
                .put("livePeak", if (livePeak.isFinite()) livePeak else -1.0)
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
                paperFundingHour = o.optLong("paperFundingHour", -1L)
            }
            HlCosts.restore(o.optJSONObject("costs"))
            o.optJSONArray("universe")?.let { a -> universe = (0 until a.length()).map { a.getString(it) } }
            killed = o.optBoolean("killed", false)
            killReason = o.optString("killReason", "")
            liveDay = o.optLong("liveDay", -1L)
            liveDayStartValue = o.optDouble("liveDayStartValue", -1.0).let { if (it > 0.0) it else Double.NaN }
            livePeak = o.optDouble("livePeak", -1.0).let { if (it > 0.0) it else Double.NaN }
        } catch (_: Throwable) {}
    }

    // ── report ──

    private fun line(r: HlBrain.Result?): String = r?.let {
        "n${it.days}d ${"%+.3f".format(it.meanPct)}±${"%.3f".format(it.sePct)}%/d sh${"%.2f".format(it.sharpe)} " +
            "tot${"%+.1f".format(it.totalPct)}% dd${"%.1f".format(it.maxDrawdownPct)}% turn${"%.2f".format(it.turnover)}"
    } ?: "-"

    fun statusLine(): String {
        val ev = evaluation
        val paper = synchronized(lock) {
            val g = HlBrain.grade("paper", paperReturns())
            "equity\$${"%.2f".format(paperEquity())} days=${paperDays.size} positions=${paperUnits.size}" + (g?.let { " record{${line(it)}}" } ?: "")
        }
        val cfg = cfg()
        val gate = liveGate(HlSettings.liveRefusal(cfg), ev?.armed == true, killed)
        val picks = ev?.let { e -> e.picks.joinToString(" ") { p ->
            "${p.family.name.lowercase()}=${p.chosen.id}[train{${line(p.train)}} test{${line(p.test)}}${e.blend[p.chosen]?.let { " w${"%.2f".format(it)}" } ?: ""}]"
        } } ?: "-"
        val nHip3 = universe.count { it.contains(':') }
        return "hyperliquid8797[$status universe=${universe.size}(main ${universe.size - nHip3}, hip3 $nHip3){${universe.joinToString(",")}} " +
            "graded=${panelCoins.size} excluded{${excluded.entries.take(12).joinToString(" ") { "${it.key}:${it.value}" }}} " +
            "history=${ev?.days ?: 0}d ${if (ev?.armed == true) "ARMED" else "SHADOW"} lev=${"%.2f".format(ev?.leverage ?: 0.0)}x " +
            "(halfKelly ${"%.2f".format(ev?.kelly ?: 0.0)}) blend{train{${line(ev?.train)}} test{${line(ev?.test)}}} families{$picks} " +
            "targets{${targets.entries.sortedByDescending { kotlin.math.abs(it.value) }.joinToString(" ") { "${it.key}${"%+.0f".format(it.value * 100)}%" }.ifBlank { "flat" }}} " +
            "costs{${universe.take(8).joinToString(" ") { HlCosts.line(it) }}} paper{$paper} " +
            "live{${gate ?: "ON"}${if (killReason.isNotEmpty()) "($killReason)" else ""} net=${if (cfg?.testnet == true) "testnet" else "mainnet"} " +
            "equity\$${if (liveEquity.isFinite()) "%.2f".format(liveEquity) else "-"} dexes{${liveDexEquity.entries.joinToString(" ") { "${it.key.ifEmpty { "main" }}=\$${"%.2f".format(it.value)}" }}} " +
            "cap\$${cfg?.maxNotionalUsd ?: 0.0} maxLev=${cfg?.maxLeverage ?: 0.0} backoff=${HlExecutor.backoffLine(System.currentTimeMillis())} " +
            "last=${lastLive.take(160)}} rule=each family chosen on the first half of the history and graded on the second; blend by " +
            "first-half Sharpe; armed = second half >= ${HlBrain.MIN_TEST_DAYS}d and mean - SE > 0; leverage = half-Kelly of the weaker half; " +
            "universe = today's most-traded markets (survivorship bias)]"
    }

    fun orderLogLines(): List<String> = synchronized(orderLog) { orderLog.toList().asReversed() }
}
