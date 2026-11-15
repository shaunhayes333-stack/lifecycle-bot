package com.lifecyclebot.hyperliquid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8795 §HYPERLIQUID_TREND_DESK — runs the trend book (HlTrend) on Hyperliquid perps, separate from the meme engine.
 *
 * Every minute on its own background thread:
 *  - every 5 minutes: marks and funding for every perp (mainnet data, public, no key);
 *  - once per UTC day (5 minutes after midnight): the [UNIVERSE] most-traded perps, their daily candles over [HISTORY_DAYS]
 *    days and their hourly funding (cached on disk), the replay (choose on the first half, grade on the second), and the
 *    day's target weights;
 *  - the PAPER book always trades those targets at the marks (0.1 % per unit of weight traded, real hourly funding), so
 *    the rule keeps a forward record whatever live does;
 *  - LIVE trades them only when the owner's settings allow it (HlSettings.liveRefusal), the replay is ARMED, and the day's
 *    loss stop has not fired. Orders are IOC limits 1 % through the mark, total exposure at most min(account value,
 *    maxNotionalUsd), never leverage beyond 1x, positions it no longer wants closed reduce-only. The daily stop flattens
 *    everything and switches live off until the owner switches it on again.
 */
object HlDesk {
    private const val UNIVERSE = 12
    private const val HISTORY_DAYS = 420
    private const val TICK_MS = 60_000L
    private const val MARK_EVERY_MS = 5L * 60_000L
    private const val DAY_MS = 86_400_000L
    private const val SETTLE_MS = 5L * 60_000L
    private const val PAPER_START_USD = 1_000.0
    private const val MIN_ORDER_USD = 11.0
    private const val SLIP = 0.01
    private const val FUNDING_PAGE = 500
    private const val FILE = "hl_desk_8795.json"

    @Volatile private var app: Context? = null
    private val started = AtomicBoolean(false)
    private val lock = Any()

    // market state
    @Volatile private var marks: Map<String, HlApi.Asset> = emptyMap()
    @Volatile private var marksAt = 0L
    @Volatile private var universe: List<String> = emptyList()
    @Volatile private var evaluation: HlTrend.Evaluation? = null
    @Volatile private var targets: Map<String, Double> = emptyMap()
    @Volatile private var lastEvalDay = -1L
    @Volatile private var historyDays = 0
    private val fundingDaily = ConcurrentHashMap<String, MutableMap<Long, Double>>()

    // paper book (USD)
    private var paperCash = PAPER_START_USD
    private val paperUnits = HashMap<String, Double>()
    private val paperDays = ArrayList<Pair<Long, Double>>()   // (epoch day, equity at its first tick)
    private var paperFundingHour = -1L

    // live
    @Volatile private var killed = false
    @Volatile private var liveDay = -1L
    @Volatile private var liveDayStartValue = Double.NaN
    @Volatile private var lastLive = "-"
    @Volatile private var lastNonce = 0L
    private val orderLog = ArrayDeque<String>()
    @Volatile private var status = "not started"

    fun ensureStarted(ctx: Context) {
        app = ctx.applicationContext
        if (!started.compareAndSet(false, true)) return
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

    private fun tick(now: Long) {
        if (now - marksAt >= MARK_EVERY_MS) {
            HlApi.metaAndCtxs(HlApi.MAINNET)?.let { list -> marks = list.associateBy { it.name }; marksAt = now }
        }
        val today = epochDay(now)
        if (today != lastEvalDay && now - today * DAY_MS >= SETTLE_MS && marks.isNotEmpty()) {
            daily(now, today)
        }
        synchronized(lock) { paperTick(now, today) }
        liveTick(now, today)
        status = "ok"
    }

    // ── daily: universe, history, replay, targets ──

    private fun daily(now: Long, today: Long) {
        val top = marks.values.filter { it.dayNtlVlm.isFinite() && it.markPx > 0.0 }.sortedByDescending { it.dayNtlVlm }.take(UNIVERSE).map { it.name }
        if (top.isEmpty()) return
        val startMs = (today - HISTORY_DAYS) * DAY_MS
        val closes = LinkedHashMap<String, Map<Long, Double>>()
        for (coin in top) {
            val cs = HlApi.candles(HlApi.MAINNET, coin, "1d", startMs, now) ?: continue
            // completed days only: a candle whose day has not ended is still moving
            closes[coin] = cs.filter { it.c > 0.0 && epochDay(it.t) < today }.associate { epochDay(it.t) to it.c }
            fillFunding(coin, startMs, now)
            sleepQuietly(1_200L)
        }
        if (closes.isEmpty()) return
        val firstDay = closes.values.mapNotNull { it.keys.minOrNull() }.minOrNull() ?: return
        val lastDay = today - 1
        val n = (lastDay - firstDay + 1).toInt()
        if (n < 2) return
        val series = closes.map { (coin, byDay) ->
            val f = fundingDaily[coin] ?: emptyMap<Long, Double>()
            HlTrend.Series(coin, DoubleArray(n) { i -> byDay[firstDay + i] ?: Double.NaN }, DoubleArray(n) { i -> f[firstDay + i] ?: 0.0 })
        }
        val ev = HlTrend.evaluate(series)
        val w = ev.chosen?.let { HlTrend.weights(series, n - 1, it) }
        evaluation = ev
        universe = series.map { it.coin }
        historyDays = n
        targets = if (w == null) emptyMap() else series.withIndex().filter { w[it.index] != 0.0 }.associate { it.value.coin to w[it.index] }
        lastEvalDay = today
        synchronized(lock) { paperRebalance() }
        liveRebalance("DAILY")
        save()
    }

    private fun fillFunding(coin: String, startMs: Long, now: Long) {
        val have = fundingDaily.getOrPut(coin) { ConcurrentHashMap() }
        var cursor = maxOf(startMs, ((have.keys.maxOrNull() ?: -1L) + 1L) * DAY_MS)
        var pages = 0
        while (cursor < now - DAY_MS / 2 && pages < 40) {
            val rows = HlApi.fundingHistory(HlApi.MAINNET, coin, cursor, now) ?: break
            if (rows.isEmpty()) break
            val byDay = HashMap<Long, Double>()
            for (r in rows) if (r.rate.isFinite()) byDay[epochDay(r.time)] = (byDay[epochDay(r.time)] ?: 0.0) + r.rate
            // a full page may end mid-day: that day is read whole by the next page; today is never stored (still filling)
            val lastDay = epochDay(rows.last().time)
            val full = rows.size >= FUNDING_PAGE
            for ((d, v) in byDay) if (d < epochDay(now) && (!full || d < lastDay)) have[d] = v
            cursor = if (full) lastDay * DAY_MS else now
            pages++
            sleepQuietly(1_200L)
        }
    }

    private fun sleepQuietly(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    // ── paper ──

    private fun markOf(coin: String): Double = marks[coin]?.markPx?.takeIf { it > 0.0 } ?: Double.NaN

    private fun paperEquity(): Double = paperCash + paperUnits.entries.sumOf { (c, u) -> markOf(c).let { if (it.isFinite()) u * it else 0.0 } }

    private fun paperRebalance() {
        val eq = paperEquity()
        if (!(eq > 0.0)) return
        for (coin in (targets.keys + paperUnits.keys).toSet()) {
            val m = markOf(coin)
            if (!m.isFinite()) continue
            val cur = (paperUnits[coin] ?: 0.0) * m
            val want = (targets[coin] ?: 0.0) * eq
            val delta = want - cur
            if (kotlin.math.abs(delta) < 1e-9) continue
            paperCash -= delta + kotlin.math.abs(delta) * HlTrend.COST_SIDE
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
        killed -> "DAILY_STOP_HIT_SWITCH_LIVE_OFF_AND_ON"
        !armed -> "REPLAY_NOT_ARMED"
        else -> null
    }

    private fun liveTick(now: Long, today: Long) {
        val ctx = app ?: return
        val cfg = HlSettings.read(ctx)
        if (HlSettings.liveRefusal(cfg) != null || cfg == null || killed) return
        if (now % (5L * 60_000L) > TICK_MS) return            // the stop is checked every ~5 minutes
        val base = if (cfg.testnet) HlApi.TESTNET else HlApi.MAINNET
        val acct = HlApi.account(base, cfg.account) ?: return
        if (!acct.accountValue.isFinite()) return
        if (liveDay != today) { liveDay = today; liveDayStartValue = acct.accountValue; save() }
        val start = liveDayStartValue
        if (start > 0.0 && acct.accountValue < start * (1.0 - cfg.dailyStopPct / 100.0) && acct.positions.any { it.szi != 0.0 }) {
            killed = true
            note("DAILY STOP: value ${"%.2f".format(acct.accountValue)} < start ${"%.2f".format(start)} - ${cfg.dailyStopPct}% -> flatten, live off")
            flatten(cfg, base, acct)
            save()
        }
    }

    private fun nextNonce(): Long = synchronized(lock) { maxOf(System.currentTimeMillis(), lastNonce + 1).also { lastNonce = it } }

    private fun note(s: String) {
        lastLive = s
        synchronized(orderLog) {
            orderLog.addLast(java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()) + " " + s)
            while (orderLog.size > 30) orderLog.removeFirst()
        }
    }

    /** Pure: the order that moves a position of [curSz] units toward [targetUsd] at [mark], or null when none is needed. */
    internal fun orderFor(curSz: Double, targetUsd: Double, mark: Double, szDecimals: Int): Triple<Boolean, Double, Boolean>? {
        if (!(mark > 0.0)) return null
        val curUsd = curSz * mark
        if (targetUsd == 0.0) {
            if (curSz == 0.0) return null
            return Triple(curSz < 0.0, kotlin.math.abs(curSz), true)                 // close it all, reduce-only
        }
        val delta = targetUsd - curUsd
        if (kotlin.math.abs(delta) < MIN_ORDER_USD) return null
        val sz = HlWire.roundSz(kotlin.math.abs(delta) / mark, szDecimals)
        if (!(sz > 0.0)) return null
        val reduce = curSz != 0.0 && kotlin.math.sign(targetUsd) == kotlin.math.sign(curUsd) && kotlin.math.abs(targetUsd) < kotlin.math.abs(curUsd)
        return Triple(delta > 0.0, sz, reduce)
    }

    private fun send(cfg: HlSettings.Config, base: String, asset: HlApi.Asset, isBuy: Boolean, sz: Double, reduceOnly: Boolean, why: String) {
        val px = HlWire.roundPx(asset.markPx * (if (isBuy) 1.0 + SLIP else 1.0 - SLIP), asset.szDecimals)
        if (!(px > 0.0)) return
        val action = HlWire.orderAction(asset.index, isBuy, HlWire.floatToWire(px), HlWire.floatToWire(sz), reduceOnly)
        val nonce = nextNonce()
        val resp = try {
            HlApi.exchange(base, action, nonce, HlSigner.signL1Action(action, nonce, cfg.agentKey, mainnet = !cfg.testnet))
        } catch (e: Throwable) { "sign/send failed: ${e.javaClass.simpleName}" }
        note("$why ${if (isBuy) "BUY" else "SELL"} ${asset.name} sz=${HlWire.floatToWire(sz)} px<=${HlWire.floatToWire(px)}${if (reduceOnly) " reduceOnly" else ""} -> ${(resp ?: "no answer").take(160)}")
    }

    private fun flatten(cfg: HlSettings.Config, base: String, acct: HlApi.Account) {
        val metas = HlApi.metaAndCtxs(base)?.associateBy { it.name } ?: return
        for (p in acct.positions) {
            if (p.szi == 0.0) continue
            val a = metas[p.coin] ?: continue
            send(cfg, base, a, p.szi < 0.0, kotlin.math.abs(p.szi), true, "FLATTEN")
        }
    }

    private fun liveRebalance(why: String) {
        val ctx = app ?: return
        val cfg = HlSettings.read(ctx)
        val refusal = liveGate(HlSettings.liveRefusal(cfg), evaluation?.armed == true, killed)
        if (refusal != null || cfg == null) { lastLive = "not trading: $refusal"; return }
        val base = if (cfg.testnet) HlApi.TESTNET else HlApi.MAINNET
        val metas = HlApi.metaAndCtxs(base)?.associateBy { it.name } ?: run { note("no market data from $base"); return }
        val acct = HlApi.account(base, cfg.account) ?: run { note("no account answer for ${cfg.account.take(8)}"); return }
        val budget = minOf(acct.accountValue, cfg.maxNotionalUsd)
        if (!(budget > 0.0)) { note("no budget: account value ${acct.accountValue}"); return }
        val held = acct.positions.associate { it.coin to it.szi }
        // reduce first (frees margin), then add
        val plan = (targets.keys + held.keys).toSet().mapNotNull { coin ->
            val a = metas[coin] ?: return@mapNotNull null
            val o = orderFor(held[coin] ?: 0.0, (targets[coin] ?: 0.0) * budget, a.markPx, a.szDecimals) ?: return@mapNotNull null
            a to o
        }.sortedByDescending { it.second.third }
        for ((a, o) in plan) send(cfg, base, a, o.first, o.second, o.third, why)
        if (plan.isEmpty()) lastLive = "$why: on target (budget \$${"%.2f".format(budget)})"
    }

    /** HyperliquidActivity: the owner switched live back on after a daily stop. */
    fun clearKill() { killed = false; save() }

    /** HyperliquidActivity: close every live position now (reduce-only), and switch live off. */
    fun flattenNow(): String {
        val ctx = app ?: return "not started"
        val cfg = HlSettings.read(ctx) ?: return "settings unreadable"
        if (!HlSigner.validKey(cfg.agentKey) || !HlSigner.validAddress(cfg.account)) return "no account or API wallet key"
        Thread({
            val base = if (cfg.testnet) HlApi.TESTNET else HlApi.MAINNET
            HlApi.account(base, cfg.account)?.let { flatten(cfg, base, it) } ?: note("flatten: no account answer")
        }, "hl-flatten-8795").start()
        HlSettings.write(ctx, cfg.copy(live = false, agentKey = ""))
        return "flattening; live switched off"
    }

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
            o.put("funding", JSONObject().also { fo ->
                for ((coin, m) in fundingDaily) fo.put(coin, JSONObject().also { mo -> for ((d, v) in m) mo.put(d.toString(), v) })
            })
            o.put("killed", killed).put("liveDay", liveDay).put("liveDayStartValue", if (liveDayStartValue.isFinite()) liveDayStartValue else -1.0)
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
            o.optJSONObject("funding")?.let { fo ->
                for (coin in fo.keys()) {
                    val mo = fo.getJSONObject(coin)
                    val m = ConcurrentHashMap<Long, Double>()
                    for (d in mo.keys()) d.toLongOrNull()?.let { m[it] = mo.getDouble(d) }
                    fundingDaily[coin] = m
                }
            }
            killed = o.optBoolean("killed", false)
            liveDay = o.optLong("liveDay", -1L)
            liveDayStartValue = o.optDouble("liveDayStartValue", -1.0).let { if (it > 0.0) it else Double.NaN }
        } catch (_: Throwable) {}
    }

    // ── report ──

    private fun line(r: HlTrend.Result?): String = r?.let {
        "n${it.days}d mean${"%+.3f".format(it.meanPct)}±${"%.3f".format(it.sePct)}%/day sharpe${"%.2f".format(it.sharpe)} " +
            "total${"%+.1f".format(it.totalPct)}% maxDD${"%.1f".format(it.maxDrawdownPct)}% held${"%.0f".format(it.exposure * 100)}%"
    } ?: "-"

    fun statusLine(): String {
        val ev = evaluation
        val paper = synchronized(lock) {
            val r = paperReturns()
            val g = ev?.chosen?.let { HlTrend.grade(it, r) }
            "equity\$${"%.2f".format(paperEquity())} days=${paperDays.size} positions=${paperUnits.size}" + (g?.let { " record{${line(it)}}" } ?: "")
        }
        val cfg = app?.let { HlSettings.read(it) }
        val gate = liveGate(HlSettings.liveRefusal(cfg), ev?.armed == true, killed)
        return "hyperliquid8795[$status universe=${universe.size}{${universe.joinToString(",")}} history=${historyDays}d " +
            "rule=${ev?.chosen?.id ?: "-"} ${if (ev?.armed == true) "ARMED" else "SHADOW"} train{${line(ev?.train)}} test{${line(ev?.test)}} " +
            "targets{${targets.entries.joinToString(" ") { "${it.key}${"%+.0f".format(it.value * 100)}%" }.ifBlank { "flat" }}} " +
            "paper{$paper} live{${gate ?: "ON"} net=${if (cfg?.testnet == true) "testnet" else "mainnet"} cap\$${cfg?.maxNotionalUsd ?: 0.0} " +
            "last=${lastLive.take(160)}} rule=chosen on the first half of the history, graded on the second; armed = test >= " +
            "${HlTrend.MIN_TEST_DAYS}d and mean - SE > 0; universe = today's most-traded perps (survivorship bias)]"
    }

    fun orderLogLines(): List<String> = synchronized(orderLog) { orderLog.toList().asReversed() }
}
