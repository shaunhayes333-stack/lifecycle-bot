package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ApiHealthMonitor
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7819 §CANDLE_HISTORY_FROM_THE_KEY_WE_ALREADY_PAY_FOR.
 *
 * 5.0.7813 PAPER snapshot: "Keyless OHLCV (§6916): fetches=1289 served=0
 * barsDelivered=0 host=geckoterminal" (geckoterminal sr=15%, 5xx=82),
 * DexPaprika latched terminal on 403, Birdeye 401. TradePlan7739 needs
 * MIN_BARS_7739=5 one-minute bars inside 30 minutes and got none, so every
 * planned read waited TOO_FEW_BARS. Helius is configured and healthy (sr=92%).
 *
 * This derives real one-minute OHLCV for a mint from Helius' Enhanced
 * Transactions history (GET /v0/addresses/{mint}/transactions): every swap in
 * the window gives an executed price (SOL leg / token leg of the trader) and
 * its block timestamp; the prints are binned into 1m bars. No bar is made
 * where no trade happened, and nothing is interpolated (Field Manual L190:
 * unknown is not a substitute; L325: realistic data, no look-ahead).
 *
 * BOUNDED — the Enhanced API is a paid credit sink:
 *   * only for a mint whose own bars are short (TradePlan7739 asks, on a
 *     TOO_FEW_BARS read), never for a held position (the open-position tick
 *     loop already writes its history at 1 Hz);
 *   * single-flight per mint, at most [MAX_IN_FLIGHT] jobs at once;
 *   * at most [MAX_JOBS_PER_MIN] jobs per minute and [MAX_JOBS_PER_HOUR] per
 *     hour, each at most [MAX_PAGES] pages of 100 transactions;
 *   * a mint is re-asked no sooner than [MINT_RETRY_MS] (or [EMPTY_RETRY_MS]
 *     after a result with no swaps).
 *
 * PRECEDENCE. Bars are merged only into minutes the history does not already
 * hold, so a fetched kline or a locally binned bar for the same minute is never
 * replaced. SOL is converted at the current SOL/USD; over a 30 minute window
 * SOL moves a percent or two, while these tokens move tens of percent, and the
 * same mark is what the rest of the book uses (Field Manual L186: one basis).
 */
object HeliusSwapCandles7819 {

    private const val HOST = "helius-enhanced"
    private const val BASE = "https://api.helius.xyz/v0/addresses"
    private const val WSOL = "So11111111111111111111111111111111111111112"
    private const val BUCKET_MS = 60_000L
    private const val WINDOW_MS = 30L * 60_000L
    private const val MAX_HISTORY = 300
    private const val PAGE_LIMIT = 100
    private const val MAX_PAGES = 2
    private const val MAX_IN_FLIGHT = 2
    private const val MAX_JOBS_PER_MIN = 3
    private const val MAX_JOBS_PER_HOUR = 60
    private const val MINT_RETRY_MS = 5L * 60_000L
    private const val EMPTY_RETRY_MS = 15L * 60_000L
    private const val KEY_TTL_MS = 5L * 60_000L
    /** Dust trades price badly (fees and rounding dominate); not used for OHLC. */
    private const val MIN_TRADE_SOL = 0.001
    /** A print more than this factor off the window median is a mis-parse, not a trade. */
    private const val OUTLIER_FACTOR = 20.0

    private val http = com.lifecyclebot.network.SharedHttpClient.builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /** One executed swap: block time, price in SOL per token, SOL size, side. */
    data class SwapPrint7819(val tsMs: Long, val priceSol: Double, val sol: Double, val isBuy: Boolean)

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val nextAllowedMs = ConcurrentHashMap<String, Long>()
    private val jobStarts = ArrayList<Long>()
    @Volatile private var cachedKey = ""
    @Volatile private var cachedKeyAtMs = 0L

    private val requests = AtomicLong(0L)
    private val jobs = AtomicLong(0L)
    private val pages = AtomicLong(0L)
    private val served = AtomicLong(0L)
    private val barsDelivered = AtomicLong(0L)
    private val barsMerged = AtomicLong(0L)
    private val printsParsed = AtomicLong(0L)
    private val empty = AtomicLong(0L)
    private val httpErrors = AtomicLong(0L)
    private val budgetSkips = AtomicLong(0L)
    private val noKey = AtomicLong(0L)

    /**
     * Ask for a Helius-derived backfill of [ts]'s last 30 minutes. Never blocks:
     * the work runs on a daemon thread and its bars land in ts.history.
     */
    fun request7819(ts: TokenState, nowMs: Long = System.currentTimeMillis()) {
        val mint = ts.mint
        if (!com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(mint)) return // V5.0.7819 HeliusScope — Solana mints only (Field Manual L186)
        try { if (ts.position.isOpen || ts.candleTimeframeMinutes > 1) return } catch (_: Throwable) { return }
        val next = nextAllowedMs[mint] ?: 0L
        if (nowMs < next || inFlight.contains(mint)) return
        requests.incrementAndGet()
        if (inFlight.size >= MAX_IN_FLIGHT || !claimBudget(nowMs)) {
            budgetSkips.incrementAndGet()
            return
        }
        if (!inFlight.add(mint)) return
        nextAllowedMs[mint] = nowMs + MINT_RETRY_MS
        if (nextAllowedMs.size > 4_000) nextAllowedMs.entries.removeIf { it.value < nowMs }
        try {
            Thread({
                try { runJob(ts) } catch (_: Throwable) {
                } finally { inFlight.remove(mint) }
            }, "helius-candles-7819").apply { isDaemon = true }.start()
        } catch (_: Throwable) { inFlight.remove(mint) }
    }

    private fun claimBudget(nowMs: Long): Boolean = synchronized(jobStarts) {
        jobStarts.removeAll { nowMs - it > 3_600_000L }
        val lastMinute = jobStarts.count { nowMs - it <= 60_000L }
        if (lastMinute >= MAX_JOBS_PER_MIN || jobStarts.size >= MAX_JOBS_PER_HOUR) return@synchronized false
        jobStarts.add(nowMs)
        true
    }

    private fun apiKey(nowMs: Long): String {
        if (cachedKey.isNotBlank() && nowMs - cachedKeyAtMs < KEY_TTL_MS) return cachedKey
        val k = try { com.lifecyclebot.engine.RuntimeProviderAuthority6685.configuredHeliusKey() } catch (_: Throwable) { "" }
        cachedKey = if (k.isBlank() || k == "hive-pattern-learn") "" else k
        cachedKeyAtMs = nowMs
        return cachedKey
    }

    private fun runJob(ts: TokenState) {
        val startedAt = System.currentTimeMillis()
        val key = apiKey(startedAt)
        if (key.isBlank()) { noKey.incrementAndGet(); return }
        jobs.incrementAndGet()
        val prints = ArrayList<SwapPrint7819>()
        var before = ""
        var page = 0
        while (page < MAX_PAGES) {
            page++
            val url = "$BASE/${ts.mint}/transactions?api-key=$key&limit=$PAGE_LIMIT" +
                if (before.isNotBlank()) "&before=$before" else ""
            val body = get(url) ?: break
            pages.incrementAndGet()
            val arr = try { JSONArray(body.trim()) } catch (_: Throwable) { null } ?: break
            if (arr.length() == 0) break
            prints.addAll(parseSwapPrints7819(arr, ts.mint))
            val last = arr.optJSONObject(arr.length() - 1)
            before = last?.optString("signature", "").orEmpty()
            val oldestMs = (last?.optLong("timestamp", 0L) ?: 0L) * 1000L
            if (before.isBlank() || arr.length() < PAGE_LIMIT || oldestMs <= 0L || startedAt - oldestMs >= WINDOW_MS) break
        }
        printsParsed.addAndGet(prints.size.toLong())
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val bars = binPrints7819(prints, solUsd, System.currentTimeMillis())
        if (bars.isEmpty()) {
            empty.incrementAndGet()
            nextAllowedMs[ts.mint] = System.currentTimeMillis() + EMPTY_RETRY_MS
            try { PipelineHealthCollector.labelInc("HELIUS_SWAP_CANDLES_EMPTY_7819") } catch (_: Throwable) {}
            return
        }
        val merged = mergeIntoHistory7819(ts, bars)
        served.incrementAndGet()
        barsDelivered.addAndGet(bars.size.toLong())
        barsMerged.addAndGet(merged.toLong())
        try {
            PipelineHealthCollector.labelInc("HELIUS_SWAP_CANDLES_SERVED_7819")
            ForensicLogger.lifecycle(
                "HELIUS_SWAP_CANDLES_SERVED_7819",
                "mint=${ts.mint.take(10)} sym=${ts.symbol} prints=${prints.size} bars=${bars.size} merged=$merged " +
                    "pages=$page histNow=${ts.history.size} source=helius_enhanced_tx",
            )
        } catch (_: Throwable) {}
    }

    private fun get(url: String): String? {
        val started = System.currentTimeMillis()
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        return try {
            http.newCall(req).execute().use { resp ->
                try { ApiHealthMonitor.record(HOST, resp.code, System.currentTimeMillis() - started) } catch (_: Throwable) {}
                if (!resp.isSuccessful) {
                    httpErrors.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("HELIUS_SWAP_CANDLES_HTTP_${resp.code}_7819") } catch (_: Throwable) {}
                    null
                } else resp.body?.string()
            }
        } catch (e: Throwable) {
            httpErrors.incrementAndGet()
            try { ApiHealthMonitor.recordNetworkError(HOST, e.message) } catch (_: Throwable) {}
            null
        }
    }

    private fun num(o: JSONObject?, name: String): Double {
        if (o == null) return 0.0
        val v = o.optString(name, "").toDoubleOrNull() ?: o.optDouble(name, 0.0)
        return if (v.isFinite() && v > 0.0) v else 0.0
    }

    private fun rawUi(o: JSONObject?): Double {
        val raw = o?.optJSONObject("rawTokenAmount") ?: return 0.0
        val amt = raw.optString("tokenAmount", "").toDoubleOrNull() ?: return 0.0
        val dec = raw.optInt("decimals", -1)
        if (dec !in 0..18 || !amt.isFinite() || amt <= 0.0) return 0.0
        return amt / Math.pow(10.0, dec.toDouble())
    }

    /**
     * Pure: the trader's executed swaps of [mint] in a Helius Enhanced
     * Transactions page. The trader is the fee payer. events.swap is read when
     * Helius parsed one; otherwise the trader's net token transfer of [mint] and
     * the largest single SOL/WSOL transfer the other way (fees, tips and rent are
     * smaller and are not the swap's SOL leg). Anything ambiguous is skipped.
     */
    fun parseSwapPrints7819(arr: JSONArray, mint: String): List<SwapPrint7819> {
        val out = ArrayList<SwapPrint7819>()
        for (i in 0 until arr.length()) {
            val tx = arr.optJSONObject(i) ?: continue
            val err = tx.opt("transactionError")
            if (err != null && err != JSONObject.NULL) continue
            val tsMs = tx.optLong("timestamp", 0L) * 1000L
            if (tsMs <= 0L) continue
            val trader = tx.optString("feePayer", "")
            var tokenNet = 0.0
            var solLeg = 0.0
            val swap = tx.optJSONObject("events")?.optJSONObject("swap")
            if (swap != null) {
                var tokOut = 0.0
                var tokIn = 0.0
                val outs = swap.optJSONArray("tokenOutputs")
                if (outs != null) for (k in 0 until outs.length()) {
                    val o = outs.optJSONObject(k) ?: continue
                    if (o.optString("mint", "") == mint) tokOut += rawUi(o)
                }
                val ins = swap.optJSONArray("tokenInputs")
                if (ins != null) for (k in 0 until ins.length()) {
                    val o = ins.optJSONObject(k) ?: continue
                    if (o.optString("mint", "") == mint) tokIn += rawUi(o)
                }
                val nIn = num(swap.optJSONObject("nativeInput"), "amount") / 1e9
                val nOut = num(swap.optJSONObject("nativeOutput"), "amount") / 1e9
                if (tokOut > 0.0 && tokIn == 0.0 && nIn > 0.0) { tokenNet = tokOut; solLeg = nIn }
                else if (tokIn > 0.0 && tokOut == 0.0 && nOut > 0.0) { tokenNet = -tokIn; solLeg = nOut }
            }
            if (solLeg <= 0.0 && trader.isNotBlank()) {
                tokenNet = 0.0
                val tt = tx.optJSONArray("tokenTransfers")
                if (tt != null) for (k in 0 until tt.length()) {
                    val t = tt.optJSONObject(k) ?: continue
                    if (t.optString("mint", "") != mint) continue
                    val amt = num(t, "tokenAmount")
                    if (t.optString("toUserAccount", "") == trader) tokenNet += amt
                    if (t.optString("fromUserAccount", "") == trader) tokenNet -= amt
                }
                if (tokenNet == 0.0) continue
                val buy = tokenNet > 0.0
                if (tt != null) for (k in 0 until tt.length()) {
                    val t = tt.optJSONObject(k) ?: continue
                    if (t.optString("mint", "") != WSOL) continue
                    val from = t.optString("fromUserAccount", "")
                    val to = t.optString("toUserAccount", "")
                    val dir = if (buy) from == trader && to != trader else to == trader && from != trader
                    if (dir) solLeg = maxOf(solLeg, num(t, "tokenAmount"))
                }
                val nt = tx.optJSONArray("nativeTransfers")
                if (nt != null) for (k in 0 until nt.length()) {
                    val t = nt.optJSONObject(k) ?: continue
                    val from = t.optString("fromUserAccount", "")
                    val to = t.optString("toUserAccount", "")
                    val dir = if (buy) from == trader && to != trader else to == trader && from != trader
                    if (dir) solLeg = maxOf(solLeg, num(t, "amount") / 1e9)
                }
                // A curve sell credits the trader by direct lamport debit, which
                // is no system transfer: the trader's own balance change is the
                // SOL received (net of the network fee, a ~0.01% understatement).
                if (solLeg <= 0.0 && !buy) {
                    val ad = tx.optJSONArray("accountData")
                    if (ad != null) for (k in 0 until ad.length()) {
                        val a = ad.optJSONObject(k) ?: continue
                        if (a.optString("account", "") != trader) continue
                        val ch = a.optDouble("nativeBalanceChange", 0.0)
                        if (ch.isFinite() && ch > 0.0) solLeg = ch / 1e9
                    }
                }
            }
            val tok = kotlin.math.abs(tokenNet)
            if (tok <= 0.0 || solLeg < MIN_TRADE_SOL) continue
            val px = solLeg / tok
            if (!px.isFinite() || px <= 0.0) continue
            out.add(SwapPrint7819(tsMs, px, solLeg, tokenNet > 0.0))
        }
        return out
    }

    /**
     * Pure: 1-minute OHLCV in USD from swap prints inside the 30-minute window
     * ending at [nowMs]. Gross mis-parses (more than 20x off the window median)
     * are dropped. A minute with no swap gets no bar. Volume is the swaps' USD
     * size; buy/sell counts are the swaps'. Not synthetic: every value is a trade.
     */
    fun binPrints7819(prints: List<SwapPrint7819>, solUsd: Double, nowMs: Long): List<Candle> {
        if (!solUsd.isFinite() || solUsd <= 0.0) return emptyList()
        val inWindow = prints.filter { it.tsMs in (nowMs - WINDOW_MS)..(nowMs + BUCKET_MS) }
        if (inWindow.isEmpty()) return emptyList()
        val sorted = inWindow.map { it.priceSol }.sorted()
        val median = sorted[sorted.size / 2]
        val clean = inWindow.filter { it.priceSol <= median * OUTLIER_FACTOR && it.priceSol >= median / OUTLIER_FACTOR }
        val byMinute = java.util.TreeMap<Long, MutableList<SwapPrint7819>>()
        for (p in clean.sortedBy { it.tsMs }) byMinute.getOrPut(p.tsMs / BUCKET_MS) { ArrayList() }.add(p)
        return byMinute.entries.map { (m, ps) ->
            val prices = ps.map { it.priceSol * solUsd }
            Candle(
                ts = m * BUCKET_MS,
                priceUsd = prices.last(),
                marketCap = 0.0,
                volumeH1 = ps.sumOf { it.sol } * solUsd,
                volume24h = 0.0,
                buysH1 = ps.count { it.isBuy },
                sellsH1 = ps.count { !it.isBuy },
                highUsd = prices.maxOrNull() ?: prices.last(),
                lowUsd = prices.minOrNull() ?: prices.last(),
                openUsd = prices.first(),
            )
        }
    }

    /** Merge [bars] into minutes ts.history does not already hold; returns the number added. */
    fun mergeIntoHistory7819(ts: TokenState, bars: List<Candle>): Int = try {
        synchronized(ts) {
            if (ts.candleTimeframeMinutes > 1) return@synchronized 0
            val held = HashSet<Long>()
            for (c in ts.history) if (c.ts > 0L) held.add(c.ts / BUCKET_MS)
            val add = bars.filter { it.ts / BUCKET_MS !in held }
            if (add.isEmpty()) return@synchronized 0
            val all = (ts.history.toList() + add).sortedBy { it.ts }
            ts.history.clear()
            for (c in all.takeLast(MAX_HISTORY)) ts.history.addLast(c)
            add.size
        }
    } catch (_: Throwable) { 0 }

    /** Served / bars for the per-source split in the §6916 and §7055 lines. */
    fun servedBars7819(): String = "served=${served.get()} bars=${barsDelivered.get()}"

    fun statusLine7819(): String =
        "requests=${requests.get()} jobs=${jobs.get()} pages=${pages.get()} prints=${printsParsed.get()} " +
            "served=${served.get()} barsDelivered=${barsDelivered.get()} merged=${barsMerged.get()} " +
            "empty=${empty.get()} httpErr=${httpErrors.get()} budgetSkips=${budgetSkips.get()} noKey=${noKey.get()} " +
            "inFlight=${inFlight.size} budget=${MAX_JOBS_PER_MIN}/min,${MAX_JOBS_PER_HOUR}/h,x${MAX_PAGES}pages"

    internal fun resetForTest() {
        inFlight.clear(); nextAllowedMs.clear(); synchronized(jobStarts) { jobStarts.clear() }
        requests.set(0L); jobs.set(0L); pages.set(0L); served.set(0L); barsDelivered.set(0L)
        barsMerged.set(0L); printsParsed.set(0L); empty.set(0L); httpErrors.set(0L); budgetSkips.set(0L); noKey.set(0L)
    }
}
