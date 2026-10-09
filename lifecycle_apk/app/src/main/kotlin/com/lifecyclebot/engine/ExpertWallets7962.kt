package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.Bar7950
import com.lifecyclebot.engine.chart.ChartLibrary7950
import com.lifecyclebot.engine.chart.ChartLibraryBuilder7950
import com.lifecyclebot.engine.chart.ChartMotif7950
import com.lifecyclebot.engine.chart.ChartParsers7955
import com.lifecyclebot.engine.chart.MotifOutcome7950
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
import com.lifecyclebot.network.SharedHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * V5.0.7962 §LEARN_FROM_THE_TRADERS_WHO_ALREADY_PRINT.
 *
 * Owner: the bot should learn from the best human traders' real trade histories,
 * built in-app on the phone, and trade alongside proven wallets. The owner turned
 * $1,200 into $100k+ reading chart shape, volume and buy/sell flow on Solana memes;
 * his wallet is the seed (OWNER). Top traders come from the leaderboard route
 * (CopyTradeEngine.discoverTopWallets: pump.fun, then GMGN's wallet rank; label
 * TopTrader_*) and smart money from SmartMoneyDiscovery7277 (early buyers on
 * runners) and the copy list.
 *
 *  HISTORY   Each wallet's swaps are paged from Helius Enhanced Transactions
 *            (/v0/addresses/{wallet}/transactions, 100 credits a page) on the
 *            SMART_MONEY_DISCOVERY enrichment share of HeliusCreditEconomy7881:
 *            a few wallets per 10-minute cycle, two pages each, backfill first
 *            (owner first), then a tail refresh every two hours. Off the hot path,
 *            resumable (cursors + legs persisted per wallet), fail-soft.
 *  TRIPS     Swap legs per mint become round trips (buys until the bag is back to
 *            dust): cost SOL, proceeds SOL, entry/exit, realized %, hold.
 *  LEARNING  For each closed trip:
 *            - the 1m chart that ended at the expert's entry (pump.fun candles,
 *              then a time-anchored GeckoTerminal pool read, keyless) is
 *              fingerprinted with ChartMotif7950.encode into ChartLibrary7950 as
 *              SRC_EXPERT, labelled by what the chart did next (or by the
 *              expert's own trade when the bars stop at entry). OWNER x2.
 *            - the entry's MC band x AGE band (ForwardReturnLabeler7731 taxonomy)
 *              accumulates the expert's realized return: expertCellLift7962.
 *            - the exit (peak, time to peak, give-back) is an ExitProfile7955
 *              sample under EXPERT|OWNER / EXPERT|TOP / EXPERT|SMART.
 *  LIVE      A tracked expert's buy (CopyTradeEngine push) marks the mint for 15
 *            minutes: LanePlaybook7907's EXPERT_ENTRY setup fires on it and is
 *            graded per lane on forward labels like every setup. The buy itself
 *            still routes through V3 / FDG / sizing; nothing here bypasses safety.
 */
object ExpertWallets7962 {
    private const val TAG = "ExpertWallets7962"
    const val OWNER_WALLET_7962 = "7dN2CS4yarnx236p7ik8EEWxW7bxnzb2nBZ9aL1kGfbz"

    enum class Tier7962 { OWNER, TOP, SMART }

    /** One swap leg of a wallet: [tokens] UI amount of [mint], [sol] the SOL side. */
    data class Leg7962(val sig: String, val tsMs: Long, val mint: String, val isBuy: Boolean, val tokens: Double, val sol: Double)

    /** A round trip on one mint: buys until the bag returned to dust (closed) or the history ended (open). */
    data class RoundTrip7962(
        val mint: String,
        val entryMs: Long,
        val exitMs: Long,
        val buys: Int,
        val sells: Int,
        val costSol: Double,
        val proceedsSol: Double,
        val tokensBought: Double,
        val entryPriceSol: Double,
        val closed: Boolean,
    ) {
        val realizedPct: Double get() = if (costSol > 0.0) (proceedsSol / costSol - 1.0) * 100.0 else Double.NaN
        val holdMs: Long get() = (exitMs - entryMs).coerceAtLeast(0L)
    }

    // ── tuning ──
    private const val CYCLE_MS = 10L * 60_000L
    private const val FIRST_DELAY_MS = 4L * 60_000L
    private const val WALLETS_PER_CYCLE = 3
    private const val PAGES_PER_WALLET = 2
    private const val PAGE_LIMIT = 100
    private const val PAGE_CREDITS = 100.0
    private const val MAX_BACKFILL_PAGES = 8
    private const val OWNER_BACKFILL_PAGES = 30
    private const val TAIL_REFRESH_MS = 2L * 60L * 60_000L
    private const val MAX_EXPERTS = 60
    private const val MAX_LEGS = 1_200
    private const val MAX_LEARNED = 1_500
    private const val MINTS_PER_CYCLE = 4
    private const val DUST_FRAC = 0.02
    private const val MIN_LEG_SOL = 0.005
    private const val LIVE_ENTRY_MS = 15L * 60_000L
    private const val CELL_SHRINK_K = 10.0
    private const val CELL_CLIP_PCT = 300.0
    private const val CALL_GAP_MS = 1_500L
    private const val PUMP_SUPPLY = 1_000_000_000.0
    private const val WSOL = "So11111111111111111111111111111111111111112"
    private val IGNORE_MINTS = setOf(
        WSOL,
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", // USDT
    )
    private const val INDEX_KEY = "EXPERT_WALLETS_7962"
    private const val HIST_KEY_PREFIX = "EXPERT_HIST_7962_"
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** Promotion of a signal source on forward-labelled evidence (SignalSourceProof7291.liveEligible7962). */
    private const val PROMOTE_MIN_N_7962 = 20
    private const val PROMOTE_MIN_PF_7962 = 1.5
    /** Labels read +20% where live read -8% (5.0.7958): a label must clear this gap, not just zero. */
    private const val LABEL_LIVE_GAP_7962 = 0.10

    /** Leaderboard routes, tried in order (CopyTradeEngine.discoverTopWallets). */
    val LEADERBOARD_URLS_7962 = listOf(
        "https://frontend-api-v3.pump.fun/leaderboard?timeframe=7d&limit=20",
        "https://gmgn.ai/defi/quotation/v1/rank/sol/wallets/7d?orderby=pnl_7d&direction=desc",
    )

    // ── pure ──

    private fun num(o: JSONObject?, name: String): Double {
        if (o == null || !o.has(name)) return 0.0
        val v = o.optString(name, "").toDoubleOrNull() ?: o.optDouble(name, 0.0)
        return if (v.isFinite() && v > 0.0) v else 0.0
    }

    private fun isAddress(s: String): Boolean = s.length in 32..44 && !s.startsWith("0x") && s.all { it.isLetterOrDigit() }

    /**
     * Pure: (wallet, realized PnL in SOL) rows of a leaderboard body. Tolerates a bare
     * array or a wrapper (data / data.rank / leaderboard / users / traders / items),
     * pump.fun-style SOL fields (lamports when implausibly large) and GMGN-style USD
     * fields (converted at [solUsd]). Rows with a win rate under 40% or bot-like
     * activity (over 3,000 trades a week) are skipped. Malformed input -> empty.
     */
    fun parseLeaderboard7962(body: String, solUsd: Double): List<Pair<String, Double>> {
        val t = body.trim()
        if (t.isEmpty()) return emptyList()
        val arr: JSONArray = try {
            if (t.startsWith("[")) JSONArray(t) else {
                val o = JSONObject(t)
                val d = o.optJSONObject("data")
                o.optJSONArray("data")
                    ?: d?.optJSONArray("rank") ?: d?.optJSONArray("items") ?: d?.optJSONArray("leaderboard") ?: d?.optJSONArray("users")
                    ?: o.optJSONArray("leaderboard") ?: o.optJSONArray("users") ?: o.optJSONArray("traders")
                    ?: o.optJSONArray("items") ?: o.optJSONArray("rank") ?: JSONArray()
            }
        } catch (_: Throwable) { return emptyList() }
        val sol = if (solUsd.isFinite() && solUsd > 0.0) solUsd else 150.0
        val out = ArrayList<Pair<String, Double>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val addr = listOf("user", "address", "wallet", "wallet_address", "userAddress", "owner", "trader")
                .map { o.optString(it, "").trim() }.firstOrNull { isAddress(it) } ?: continue
            val wr = listOf("winrate_7d", "winrate", "win_rate").map { o.optDouble(it, Double.NaN) }.firstOrNull { it.isFinite() }
            if (wr != null && (if (wr > 1.0) wr / 100.0 else wr) < 0.40) continue
            val trades = listOf("txs_7d", "buy_7d", "trades", "trade_count").map { o.optDouble(it, Double.NaN) }.firstOrNull { it.isFinite() }
            if (trades != null && trades > 3_000.0) continue
            var pnl = listOf("realized_pnl", "realizedPnl", "pnl_sol", "total_pnl", "profit", "pnl").map { num(o, it) }.firstOrNull { it > 0.0 } ?: 0.0
            if (pnl > 1e7) pnl /= 1e9
            if (pnl <= 0.0) {
                val usd = listOf("realized_profit_7d", "realized_profit", "pnl_usd", "realizedPnlUsd").map { num(o, it) }.firstOrNull { it > 0.0 } ?: 0.0
                pnl = usd / sol
            }
            if (pnl > 0.0 && out.none { it.first == addr }) out += addr to pnl
        }
        return out
    }

    /**
     * Pure: the swap legs [wallet] made in a Helius Enhanced Transactions page. A leg is
     * a transaction where exactly one non-SOL, non-stable mint moved in or out of the
     * wallet (token-for-token swaps are skipped). Its SOL side: the parsed swap event's
     * native leg, else the largest SOL/WSOL transfer the other way, else the wallet's
     * own balance change (net of the network fee when it paid it).
     */
    fun parseWalletLegs7962(arr: JSONArray, wallet: String): List<Leg7962> {
        val out = ArrayList<Leg7962>()
        for (i in 0 until arr.length()) {
            val tx = arr.optJSONObject(i) ?: continue
            val err = tx.opt("transactionError")
            if (err != null && err != JSONObject.NULL) continue
            val tsMs = tx.optLong("timestamp", 0L) * 1000L
            val sig = tx.optString("signature", "")
            if (tsMs <= 0L || sig.isBlank()) continue
            val net = HashMap<String, Double>()
            val tt = tx.optJSONArray("tokenTransfers")
            if (tt != null) for (k in 0 until tt.length()) {
                val t = tt.optJSONObject(k) ?: continue
                val mint = t.optString("mint", "")
                if (mint.isBlank() || mint == WSOL) continue
                val amt = num(t, "tokenAmount")
                if (amt <= 0.0) continue
                if (t.optString("toUserAccount", "") == wallet) net[mint] = (net[mint] ?: 0.0) + amt
                if (t.optString("fromUserAccount", "") == wallet) net[mint] = (net[mint] ?: 0.0) - amt
            }
            val moved = net.entries.filter { abs(it.value) > 0.0 }
            if (moved.size != 1) continue
            val mint = moved[0].key
            val tokNet = moved[0].value
            if (mint in IGNORE_MINTS) continue
            val buy = tokNet > 0.0
            var sol = 0.0
            val swap = tx.optJSONObject("events")?.optJSONObject("swap")
            if (swap != null) {
                val leg = swap.optJSONObject(if (buy) "nativeInput" else "nativeOutput")
                val acct = leg?.optString("account", "").orEmpty()
                if (acct.isBlank() || acct == wallet) sol = num(leg, "amount") / 1e9
            }
            if (sol <= 0.0 && tt != null) for (k in 0 until tt.length()) {
                val t = tt.optJSONObject(k) ?: continue
                if (t.optString("mint", "") != WSOL) continue
                val from = t.optString("fromUserAccount", "")
                val to = t.optString("toUserAccount", "")
                val dir = if (buy) from == wallet && to != wallet else to == wallet && from != wallet
                if (dir) sol = maxOf(sol, num(t, "tokenAmount"))
            }
            if (sol <= 0.0) {
                val nt = tx.optJSONArray("nativeTransfers")
                if (nt != null) for (k in 0 until nt.length()) {
                    val t = nt.optJSONObject(k) ?: continue
                    val from = t.optString("fromUserAccount", "")
                    val to = t.optString("toUserAccount", "")
                    val dir = if (buy) from == wallet && to != wallet else to == wallet && from != wallet
                    if (dir) sol = maxOf(sol, num(t, "amount") / 1e9)
                }
            }
            if (sol <= 0.0) {
                val ad = tx.optJSONArray("accountData")
                if (ad != null) for (k in 0 until ad.length()) {
                    val a = ad.optJSONObject(k) ?: continue
                    if (a.optString("account", "") != wallet) continue
                    val ch = a.optDouble("nativeBalanceChange", 0.0)
                    if (!ch.isFinite()) continue
                    val fee = if (tx.optString("feePayer", "") == wallet) tx.optDouble("fee", 0.0).coerceAtLeast(0.0) else 0.0
                    if (!buy && ch > 0.0) sol = (ch + fee) / 1e9
                    if (buy && ch < 0.0) sol = ((-ch) - fee).coerceAtLeast(0.0) / 1e9
                }
            }
            if (sol < MIN_LEG_SOL) continue
            out += Leg7962(sig, tsMs, mint, buy, abs(tokNet), sol)
        }
        return out
    }

    private class Acc(val mint: String, val entryMs: Long, val entryPriceSol: Double) {
        var buys = 0; var sells = 0
        var cost = 0.0; var proceeds = 0.0
        var bought = 0.0; var held = 0.0
        var lastMs = entryMs
        fun trip(closed: Boolean) = RoundTrip7962(mint, entryMs, lastMs, buys, sells, cost, proceeds, bought, entryPriceSol, closed)
    }

    /**
     * Pure: round trips per mint, in entry order. A trip opens on a buy with no bag,
     * adds every buy, and closes when sells bring the bag to [DUST_FRAC] of what was
     * bought. Sells of tokens bought before the history window are ignored; a sell
     * larger than the tracked bag is credited pro rata to the tracked part.
     */
    fun reconstructRoundTrips7962(legs: List<Leg7962>): List<RoundTrip7962> {
        val out = ArrayList<RoundTrip7962>()
        for ((mint, ls) in legs.groupBy { it.mint }) {
            var open: Acc? = null
            val sorted = ls.distinctBy { "${it.sig}|${it.isBuy}" }.sortedWith(compareBy<Leg7962>({ it.tsMs }, { if (it.isBuy) 0 else 1 }))
            for (l in sorted) {
                if (!(l.tokens > 0.0) || !(l.sol > 0.0)) continue
                if (l.isBuy) {
                    var a = open
                    if (a == null) { a = Acc(mint, l.tsMs, l.sol / l.tokens); open = a }
                    a.buys++; a.cost += l.sol; a.bought += l.tokens; a.held += l.tokens; a.lastMs = l.tsMs
                } else {
                    val a = open ?: continue
                    val q = minOf(l.tokens, a.held)
                    if (q <= 0.0) continue
                    a.sells++; a.proceeds += l.sol * (q / l.tokens); a.held -= q; a.lastMs = l.tsMs
                    if (a.held <= a.bought * DUST_FRAC) { out += a.trip(closed = true); open = null }
                }
            }
            open?.let { out += it.trip(closed = false) }
        }
        return out.sortedBy { it.entryMs }
    }

    /** Pure: forward-label cell of an entry (ForwardReturnLabeler7731 MC band x AGE band). */
    fun cellKey7962(mcapUsd: Double, ageMs: Long): String =
        "${ForwardReturnLabeler7731.mcapBand(mcapUsd)}|${ForwardReturnLabeler7731.ageBand(ageMs)}"

    /** Pure: an expert cell's lift — mean realized % shrunk toward zero by n/(n+10). */
    fun cellLiftOf7962(n: Int, sumPct: Double): Double = if (n <= 0 || !sumPct.isFinite()) 0.0 else sumPct / (n + CELL_SHRINK_K)

    /** Pure: sample standard deviation from (n, sum, sum of squares); NaN under two samples. */
    fun sdOf7962(n: Int, sum: Double, sumSq: Double): Double {
        if (n < 2 || !sum.isFinite() || !sumSq.isFinite()) return Double.NaN
        val v = (sumSq - sum * sum / n) / (n - 1)
        return sqrt(v.coerceAtLeast(0.0))
    }

    /**
     * Pure: does forward-labelled evidence promote a source to live? n >= 20, the mean
     * net return minus one standard error clears [costFrac] (the label-to-live gap), and
     * profit factor >= 1.5. Re-read on every call, so a source demotes itself the moment
     * its record stops holding.
     */
    fun labeledPromotes7962(n: Int, meanFrac: Double, sdFrac: Double, pf: Double, costFrac: Double = LABEL_LIVE_GAP_7962): Boolean {
        if (n < PROMOTE_MIN_N_7962 || !meanFrac.isFinite() || !sdFrac.isFinite() || sdFrac < 0.0) return false
        val se = sdFrac / sqrt(n.toDouble())
        return meanFrac - se > costFrac && pf >= PROMOTE_MIN_PF_7962
    }

    /**
     * Pure: peak % and time to peak over a trip from 1m [bars], relative to the close of
     * the bar [entryIdx] that ended at entry; null when the bars do not cover the hold.
     */
    fun peakFromBars7962(bars: List<Bar7950>, entryIdx: Int, entryMs: Long, exitMs: Long): Pair<Double, Long>? {
        if (entryIdx !in bars.indices || exitMs < entryMs) return null
        val c = bars[entryIdx].c
        if (!(c > 0.0) || bars.last().t < exitMs - 120_000L) return null
        var hi = c
        var at = entryMs
        for (i in entryIdx + 1 until bars.size) {
            val b = bars[i]
            if (b.t > exitMs) break
            if (b.h > hi) { hi = b.h; at = b.t + 30_000L }
        }
        return ((hi / c - 1.0) * 100.0) to (at - entryMs).coerceAtLeast(0L)
    }

    /** Pure: a motif label from the expert's own trade when the chart after entry is not available. */
    fun tripOutcome7962(realizedPct: Double, peakPct: Double): MotifOutcome7950 {
        val r = if (realizedPct.isFinite()) realizedPct else 0.0
        val up = maxOf(if (peakPct.isFinite()) peakPct else r, r, 0.0)
        return MotifOutcome7950(hitUpFirst = r >= 10.0, maxUpPct = up.toFloat(), maxDnPct = minOf(r, 0.0).toFloat(), endPct = r.toFloat())
    }

    // ── state ──

    private class Expert(val address: String, @Volatile var tier: Tier7962, @Volatile var label: String)

    private class Hist(val addr: String) {
        var newestSig = ""
        var oldestSig = ""
        var pages = 0
        var backfillDone = false
        var lastFetchMs = 0L
        val legs = ArrayList<Leg7962>()
        val learned = LinkedHashSet<String>()
        var trips = 0
        var closedTrips = 0
    }

    private class Cell(var n: Int = 0, var sum: Double = 0.0, var sumSq: Double = 0.0, var wins: Int = 0, var owner: Int = 0)

    private val started = AtomicBoolean(false)
    @Volatile private var loaded = false
    private val experts = ConcurrentHashMap<String, Expert>()
    private val hists = ConcurrentHashMap<String, Hist>()
    private val cells = HashMap<String, Cell>()
    private val pendingLearn = LinkedHashMap<String, MutableList<Pair<String, RoundTrip7962>>>()
    private val recentExpertBuys = ConcurrentHashMap<String, Pair<Long, Tier7962>>()
    private val poolCache = ConcurrentHashMap<String, String>()
    private val createdCache = ConcurrentHashMap<String, Long>()

    private val cycles = AtomicLong(0)
    private val pagesFetched = AtomicLong(0)
    private val creditDeferred = AtomicLong(0)
    private val motifsAdded = AtomicLong(0)
    private val tripsLearned = AtomicLong(0)
    private val barsMissing = AtomicLong(0)
    private val exitSamples = AtomicLong(0)
    private val liveBuys = AtomicLong(0)
    private val untrackedTape = AtomicLong(0)
    @Volatile private var leaderboard = "-"
    @Volatile private var ownerCopyListed = false
    private const val GECKO_CALLS_PER_CYCLE = 6

    private val http by lazy {
        SharedHttpClient.builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    // ── live hooks ──

    /** CopyTradeEngine: a tracked wallet bought [mint]. Marks the mint for EXPERT_ENTRY when the wallet is an expert. */
    fun onTrackedBuy7962(mint: String, wallet: String, nowMs: Long = System.currentTimeMillis()) {
        val tier = (if (wallet == OWNER_WALLET_7962) Tier7962.OWNER else experts[wallet]?.tier) ?: return
        if (mint.length < 30) return
        recentExpertBuys[mint] = nowMs to tier
        liveBuys.incrementAndGet()
        if (recentExpertBuys.size > 2_000) recentExpertBuys.entries.removeIf { nowMs - it.value.first > LIVE_ENTRY_MS }
        try {
            PipelineHealthCollector.labelInc("EXPERT_LIVE_BUY_7962_${tier.name}")
            ForensicLogger.lifecycle("EXPERT_LIVE_BUY_7962", "mint=${mint.take(10)} wallet=${wallet.take(8)} tier=${tier.name}")
        } catch (_: Throwable) {}
    }

    /** CopyTradeEngine: a tape trade by a wallet nobody tracks (not a smart-money detection). */
    fun noteUntrackedTape7962() { untrackedTape.incrementAndGet() }

    /** CopyTradeEngine.discoverTopWallets: which leaderboard route answered and with how many rows. */
    fun noteLeaderboard7962(url: String, rows: Int) {
        val host = url.substringAfter("://").substringBefore('/')
        leaderboard = "$host:$rows"
    }

    /** LanePlaybook7907 EXPERT_ENTRY: an expert wallet bought [mint] within the last 15 minutes. */
    fun expertEntryLive7962(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val (at, _) = recentExpertBuys[mint] ?: return false
        return nowMs - at in 0L..LIVE_ENTRY_MS
    }

    /** Where experts buy: the shrunk mean realized % of expert entries in the (MC band, AGE band) cell; 0 = no evidence. */
    fun expertCellLift7962(mcapUsd: Double, ageMs: Long): Double {
        val k = cellKey7962(mcapUsd, ageMs)
        return synchronized(cells) { cells[k]?.let { cellLiftOf7962(it.n, it.sum) } } ?: 0.0
    }

    /** HeliusEnhancedWS: the requested watch list with OWNER, then TOP wallets first (the affordable prefix keeps them). */
    fun prioritise7962(requested: List<String>): List<String> {
        fun rank(a: String): Int = when {
            a == OWNER_WALLET_7962 -> 0
            experts[a]?.tier == Tier7962.TOP -> 1
            else -> 2
        }
        return requested.withIndex().sortedWith(compareBy<IndexedValue<String>>({ rank(it.value) }, { it.index })).map { it.value }
    }

    // ── cycle ──

    /** SmartMoneyDiscovery7277.start: the background learner (IO, every 10 minutes, never on the hot path). */
    fun start7962(
        scope: CoroutineScope,
        heliusKey: () -> String,
        copyEngine: () -> CopyTradeEngine?,
        onWatchlistChanged: (List<String>) -> Unit,
    ) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            delay(FIRST_DELAY_MS)
            while (isActive) {
                try { runCycle(heliusKey(), copyEngine(), onWatchlistChanged) } catch (t: Throwable) {
                    ErrorLogger.debug(TAG, "cycle error: ${t.message?.take(120)}")
                }
                delay(CYCLE_MS)
            }
        }
    }

    private fun runCycle(key: String, engine: CopyTradeEngine?, onWatchlistChanged: (List<String>) -> Unit) {
        ensureLoaded()
        if (!loaded) return
        cycles.incrementAndGet()
        refreshExperts(engine, onWatchlistChanged)
        if (key.isNotBlank() && KeyValidator.isUsableEnhancedHeliusKey(key)) fetchHistories(key)
        learnPending()
        persistIndex()
    }

    /** The bot's first durable live buy (EconomicEventSchema6464), or 0 when unknown (then nothing on a shared wallet is treated as the owner's). */
    private fun firstBotLiveBuyMs7962(): Long = try {
        com.lifecyclebot.engine.truth.EconomicEventSchema6464.snapshot()
            .filterIsInstance<com.lifecyclebot.engine.truth.EconomicEventSchema6464.Buy>()
            .filter { it.mode == "live" }.minOfOrNull { it.atMs } ?: 0L
    } catch (_: Throwable) { 0L }

    private fun isBotOwnWallet(addr: String): Boolean = try { WalletManager.currentPubkey() == addr } catch (_: Throwable) { false }

    private fun tierOfLabel(label: String): Tier7962 = when {
        label == "OWNER" -> Tier7962.OWNER
        label.startsWith("TopTrader_") || label.startsWith("TOP") -> Tier7962.TOP
        else -> Tier7962.SMART
    }

    private fun refreshExperts(engine: CopyTradeEngine?, onWatchlistChanged: (List<String>) -> Unit) {
        experts.getOrPut(OWNER_WALLET_7962) { Expert(OWNER_WALLET_7962, Tier7962.OWNER, "OWNER") }.tier = Tier7962.OWNER
        val copy = try { engine?.getWallets() } catch (_: Throwable) { null } ?: emptyList()
        for (w in copy) {
            if (w.address == OWNER_WALLET_7962 || !w.isActive) continue
            val e = experts[w.address]
            if (e == null) {
                if (experts.size >= MAX_EXPERTS) continue
                experts[w.address] = Expert(w.address, tierOfLabel(w.label), w.label)
            } else { e.tier = tierOfLabel(w.label); e.label = w.label }
        }
        // The owner trades alongside the bot: his buys arrive on the push stream like any copy wallet
        // (V3 / FDG / sizing still decide). Never when the bot itself trades from that wallet.
        if (engine != null && !ownerCopyListed && copy.none { it.address == OWNER_WALLET_7962 } && !isBotOwnWallet(OWNER_WALLET_7962)) {
            try {
                engine.addWallet(OWNER_WALLET_7962, "OWNER")
                ownerCopyListed = true // once: an owner who removes it later is respected
                val all = engine.getWallets().filter { it.isActive && !it.isPaused }.map { it.address }.distinct()
                onWatchlistChanged(all)
                PipelineHealthCollector.labelInc("EXPERT_OWNER_COPY_LIST_7962")
            } catch (_: Throwable) {}
        }
    }

    private fun fetchHistories(key: String) {
        val now = System.currentTimeMillis()
        val order = experts.values.sortedWith(compareBy<Expert>(
            { if (it.tier == Tier7962.OWNER && hists[it.address]?.backfillDone != true) 0 else 1 },
            { if (hists[it.address]?.backfillDone == true) 1 else 0 },
            { hists[it.address]?.lastFetchMs ?: 0L },
        ))
        var done = 0
        for (e in order) {
            if (done >= WALLETS_PER_CYCLE) break
            val h = hist(e.address)
            if (h.backfillDone && now - h.lastFetchMs < TAIL_REFRESH_MS) continue
            done++
            val newLegs = fetchWallet(key, h)
            h.lastFetchMs = now
            if (newLegs < 0) { saveHist(h); break } // credits deferred: stop this cycle
            enqueueTrips(h, e.tier)
            saveHist(h)
        }
    }

    /** Returns new legs, or -1 when the credit economy deferred the page. */
    private fun fetchWallet(key: String, h: Hist): Int {
        var added = 0
        val known = HashSet<String>(h.legs.size * 2).apply { h.legs.forEach { add("${it.sig}|${it.mint}") } }
        val tail = h.backfillDone
        var before = if (tail) "" else h.oldestSig
        var firstNewest = ""
        for (p in 0 until PAGES_PER_WALLET) {
            if (!HeliusCreditEconomy7881.admit(HeliusCreditEconomy7881.Consumer.SMART_MONEY_DISCOVERY, PAGE_CREDITS)) {
                creditDeferred.incrementAndGet()
                return if (added > 0) added else -1
            }
            val url = "https://api.helius.xyz/v0/addresses/${h.addr}/transactions?api-key=$key&limit=$PAGE_LIMIT" +
                if (before.isNotBlank()) "&before=$before" else ""
            val body = try {
                HealthAwareHttp.execute(http, Request.Builder().url(url).header("Accept", "application/json").build(), host = "helius")
                    .use { r -> if (r.isSuccessful) r.body?.string() else null }
            } catch (_: Throwable) { null } ?: break
            val arr = try { JSONArray(body.trim()) } catch (_: Throwable) { null } ?: break
            pagesFetched.incrementAndGet()
            h.pages++
            if (arr.length() == 0) { if (!tail) h.backfillDone = true; break }
            val sigs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("signature", "")?.takeIf { s -> s.isNotBlank() } }
            if (firstNewest.isBlank() && before.isBlank()) firstNewest = sigs.firstOrNull().orEmpty()
            for (l in parseWalletLegs7962(arr, h.addr)) if (known.add("${l.sig}|${l.mint}")) { h.legs += l; added++ }
            val reachedKnown = tail && h.newestSig.isNotBlank() && h.newestSig in sigs
            if (!tail) {
                h.oldestSig = sigs.lastOrNull() ?: h.oldestSig
                if (arr.length() < PAGE_LIMIT || h.pages >= (if (h.addr == OWNER_WALLET_7962) OWNER_BACKFILL_PAGES else MAX_BACKFILL_PAGES)) h.backfillDone = true
            }
            if (reachedKnown || arr.length() < PAGE_LIMIT || (!tail && h.backfillDone)) break
            before = sigs.lastOrNull() ?: break
        }
        if (firstNewest.isNotBlank() && (h.newestSig.isBlank() || tail)) h.newestSig = firstNewest
        if (h.legs.size > MAX_LEGS) {
            val keep = h.legs.sortedByDescending { it.tsMs }.take(MAX_LEGS)
            h.legs.clear(); h.legs.addAll(keep)
        }
        return added
    }

    private fun enqueueTrips(h: Hist, tier: Tier7962) {
        val trips = reconstructRoundTrips7962(h.legs)
        h.trips = trips.size
        h.closedTrips = trips.count { it.closed }
        synchronized(pendingLearn) {
            // V5.0.7962 — if the owner's wallet is also the bot's trading wallet, only his own
            // trades (before the bot's first live buy, or not bot-signed) are the owner's.
            val botCutoff = if (tier == Tier7962.OWNER && isBotOwnWallet(h.addr)) firstBotLiveBuyMs7962() else Long.MAX_VALUE
            for (t in trips) {
                if (!t.closed || "${t.mint}|${t.entryMs}" in h.learned) continue
                if (t.entryMs >= botCutoff) { try { PipelineHealthCollector.labelInc("EXPERT_OWNER_TRIP_IS_BOT_TRADE_7962") } catch (_: Throwable) {}; continue }
                val list = pendingLearn.getOrPut(t.mint) { ArrayList() }
                if (list.none { it.first == h.addr && it.second.entryMs == t.entryMs }) list += h.addr to t
            }
        }
        if (tier == Tier7962.OWNER) try { PipelineHealthCollector.labelInc("EXPERT_OWNER_HISTORY_7962") } catch (_: Throwable) {}
    }

    private fun learnPending() {
        val batch = synchronized(pendingLearn) {
            val keys = pendingLearn.keys.take(MINTS_PER_CYCLE)
            keys.map { it to (pendingLearn.remove(it) ?: mutableListOf()) }
        }
        if (batch.isEmpty()) return
        val solUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val touched = HashSet<String>()
        var geckoCalls = 0
        for ((mint, trips) in batch) {
            var bars = try { pumpBars(mint) } catch (_: Throwable) { emptyList() }
            val created = try { createdMs(mint, bars) } catch (_: Throwable) { -1L }
            for ((wallet, trip) in trips) {
                var useBars = bars
                if (entryIndex(useBars, trip.entryMs) < ChartMotif7950.WINDOW && geckoCalls++ < GECKO_CALLS_PER_CYCLE) {
                    val g = try { geckoBars(mint, trip.entryMs) } catch (_: Throwable) { emptyList() }
                    if (g.size > useBars.size || entryIndex(g, trip.entryMs) >= ChartMotif7950.WINDOW) useBars = g
                    if (bars.isEmpty()) bars = g
                }
                val tier = if (wallet == OWNER_WALLET_7962) Tier7962.OWNER else experts[wallet]?.tier ?: Tier7962.SMART
                try { learnTrip(tier, trip, useBars, created, solUsd) } catch (_: Throwable) {}
                hists[wallet]?.let { h ->
                    h.learned += "${trip.mint}|${trip.entryMs}"
                    while (h.learned.size > MAX_LEARNED) h.learned.remove(h.learned.first())
                    touched += wallet
                }
            }
        }
        for (w in touched) hists[w]?.let { saveHist(it) }
    }

    private fun entryIndex(bars: List<Bar7950>, entryMs: Long): Int = bars.indexOfLast { it.t + 60_000L <= entryMs }

    private fun learnTrip(tier: Tier7962, trip: RoundTrip7962, bars: List<Bar7950>, createdMs: Long, solUsd: Double) {
        val realized = trip.realizedPct
        if (!realized.isFinite()) return
        val idx = entryIndex(bars, trip.entryMs)
        val pk = if (idx >= 0) peakFromBars7962(bars, idx, trip.entryMs, trip.exitMs) else null
        // 1. chart at entry -> labelled motif (the library must be loaded first, or a load would overwrite it).
        if (idx >= ChartMotif7950.WINDOW && ChartLibrary7950.size() > 0) {
            val f = ChartMotif7950.encode(bars, idx)
            if (f != null) {
                val o = ChartMotif7950.outcome(bars, idx) ?: tripOutcome7962(realized, pk?.first ?: Double.NaN)
                val reps = if (tier == Tier7962.OWNER) 2 else 1
                repeat(reps) { ChartLibrary7950.add(f, o, ChartLibrary7950.SRC_EXPERT) }
                motifsAdded.addAndGet(reps.toLong())
            }
        } else barsMissing.incrementAndGet()
        // 2. where experts buy: MC band x AGE band at entry.
        val pumpSupply = trip.mint.endsWith("pump") || trip.mint.endsWith("bonk")
        val mcap = if (pumpSupply && solUsd > 0.0 && trip.entryPriceSol > 0.0) trip.entryPriceSol * PUMP_SUPPLY * solUsd else 0.0
        val age = if (createdMs > 0L && trip.entryMs >= createdMs) trip.entryMs - createdMs else -1L
        val r = realized.coerceIn(-100.0, CELL_CLIP_PCT)
        synchronized(cells) {
            val c = cells.getOrPut(cellKey7962(mcap, age)) { Cell() }
            c.n++; c.sum += r; c.sumSq += r * r
            if (realized > 0.0) c.wins++
            if (tier == Tier7962.OWNER) c.owner++
        }
        // 3. how experts exit.
        val peak = pk?.first?.let { maxOf(it, realized) } ?: maxOf(realized, 0.0)
        val ttp = pk?.second ?: trip.holdMs
        val gb = if (pk != null) ExitProfile7955.giveback7955(peak, realized) else Double.NaN
        try {
            ExitProfile7955.onLabel7955("EXPERT", tier.name, peak, ttp, gb, Double.NaN, keyOnly = true)
            exitSamples.incrementAndGet()
        } catch (_: Throwable) {}
        tripsLearned.incrementAndGet()
    }

    // ── chart sources (keyless) ──

    private fun httpGet(url: String, gecko: Boolean = false): String? = try {
        Thread.sleep(CALL_GAP_MS)
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", if (gecko) "application/json;version=20230302" else "application/json")
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string()
            if (!r.isSuccessful || body.isNullOrBlank() || ChartParsers7955.isChallenge7955(r.code, body)) null else body
        }
    } catch (e: InterruptedException) { throw e } catch (_: Throwable) { null }

    private fun candleArray(body: String): JSONArray {
        val t = body.trimStart()
        if (t.startsWith("[")) return JSONArray(t)
        val o = JSONObject(t)
        return o.optJSONArray("candles") ?: o.optJSONArray("data") ?: JSONArray()
    }

    /** pump.fun 1m candles for a pump mint: swap-api first, then the v3 route (from creation). */
    private fun pumpBars(mint: String): List<Bar7950> {
        if (!(mint.endsWith("pump") || mint.endsWith("bonk"))) return emptyList()
        val a = httpGet("https://swap-api.pump.fun/v2/coins/$mint/candles?interval=1m&limit=1000&currency=USD")
            ?.let { try { ChartParsers7955.pumpFun7955(candleArray(it)) } catch (_: Throwable) { null } }.orEmpty()
        if (a.size > ChartMotif7950.WINDOW) return a
        return httpGet("https://frontend-api-v3.pump.fun/candlesticks/$mint?offset=0&limit=1000&timeframe=1")
            ?.let { try { ChartParsers7955.pumpFun7955(candleArray(it)) } catch (_: Throwable) { null } }.orEmpty()
    }

    /** Mint creation time: pump.fun coin record, else the first bar of a from-creation series. */
    private fun createdMs(mint: String, bars: List<Bar7950>): Long {
        createdCache[mint]?.let { return it }
        var at = -1L
        if (mint.endsWith("pump")) {
            httpGet("https://frontend-api-v3.pump.fun/coins/$mint")?.let { b ->
                val ts = try { JSONObject(b).optLong("created_timestamp", 0L) } catch (_: Throwable) { 0L }
                if (ts > 0L) at = if (ts < 100_000_000_000L) ts * 1000L else ts
            }
        }
        if (at <= 0L && bars.isNotEmpty() && mint.endsWith("pump")) at = bars.first().t
        if (createdCache.size > 5_000) createdCache.clear()
        if (at > 0L) createdCache[mint] = at
        return at
    }

    /** GeckoTerminal 1m bars ending 25 minutes after [entryMs] (time-anchored), when the live feed's slot is free. */
    private fun geckoBars(mint: String, entryMs: Long): List<Bar7950> {
        val slot = try { com.lifecyclebot.network.SolanaOhlcvFeed6916.providerSlotWait7809() } catch (_: Throwable) { null }
        if (slot != null && (slot.cooldown || slot.waitMs > 0L)) return emptyList()
        val base = "https://api.geckoterminal.com/api/v2/networks/solana"
        val pool = poolCache[mint] ?: run {
            val body = httpGet("$base/tokens/$mint/pools?page=1", gecko = true) ?: return emptyList()
            val arr = try { JSONObject(body).optJSONArray("data") } catch (_: Throwable) { null } ?: return emptyList()
            var best = ""
            var bestLiq = -1.0
            for (i in 0 until arr.length()) {
                val attrs = arr.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val addr = attrs.optString("address", "")
                val liq = attrs.optString("reserve_in_usd", "0").toDoubleOrNull() ?: 0.0
                if (addr.isNotBlank() && liq > bestLiq) { best = addr; bestLiq = liq }
            }
            if (best.isBlank()) return emptyList()
            if (poolCache.size > 5_000) poolCache.clear()
            poolCache[mint] = best
            best
        }
        val beforeSec = entryMs / 1000L + 25L * 60L
        val body = httpGet("$base/pools/$pool/ohlcv/minute?aggregate=1&before_timestamp=$beforeSec&limit=60&currency=usd", gecko = true) ?: return emptyList()
        return try { ChartLibraryBuilder7950.parseGecko(JSONObject(body)) } catch (_: Throwable) { emptyList() }
    }

    // ── persistence ──

    private fun hist(addr: String): Hist = hists.getOrPut(addr) { loadHist(addr) }

    private fun ensureLoaded() {
        if (loaded || !LearningPersistence.ready()) return
        synchronized(this) {
            if (loaded) return
            try {
                val o = JSONObject(LearningPersistence.load(INDEX_KEY) ?: "{}")
                o.optJSONArray("experts")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        val a = e.optString("a", "")
                        if (a.isBlank()) continue
                        val tier = try { Tier7962.valueOf(e.optString("t", "SMART")) } catch (_: Throwable) { Tier7962.SMART }
                        experts[a] = Expert(a, tier, e.optString("l", ""))
                    }
                }
                o.optJSONObject("cells")?.let { j ->
                    synchronized(cells) {
                        for (k in j.keys()) {
                            val f = j.optString(k).split(',')
                            if (f.size < 5) continue
                            cells[k] = Cell(f[0].toIntOrNull() ?: 0, f[1].toDoubleOrNull() ?: 0.0, f[2].toDoubleOrNull() ?: 0.0,
                                f[3].toIntOrNull() ?: 0, f[4].toIntOrNull() ?: 0)
                        }
                    }
                }
                motifsAdded.set(o.optLong("motifs", 0L))
                tripsLearned.set(o.optLong("learned", 0L))
                ownerCopyListed = o.optBoolean("ownerCopy", false)
            } catch (_: Throwable) {}
            loaded = true
        }
        // Resume: every stored history re-queues its unlearned closed trips.
        for (e in experts.values) {
            val h = hist(e.address)
            if (h.legs.isNotEmpty()) enqueueTrips(h, e.tier)
        }
    }

    private fun persistIndex() {
        try {
            val o = JSONObject()
            val arr = JSONArray()
            for (e in experts.values) arr.put(JSONObject().put("a", e.address).put("t", e.tier.name).put("l", e.label))
            o.put("experts", arr)
            val cj = JSONObject()
            synchronized(cells) { cells.forEach { (k, c) -> cj.put(k, "${c.n},${c.sum},${c.sumSq},${c.wins},${c.owner}") } }
            o.put("cells", cj)
            o.put("motifs", motifsAdded.get())
            o.put("learned", tripsLearned.get())
            o.put("ownerCopy", ownerCopyListed)
            LearningPersistence.save(INDEX_KEY, o.toString())
        } catch (_: Throwable) {}
    }

    private fun loadHist(addr: String): Hist {
        val h = Hist(addr)
        try {
            val o = JSONObject(LearningPersistence.load(HIST_KEY_PREFIX + addr) ?: return h)
            h.newestSig = o.optString("newest", "")
            h.oldestSig = o.optString("oldest", "")
            h.pages = o.optInt("pages", 0)
            h.backfillDone = o.optBoolean("done", false)
            h.lastFetchMs = o.optLong("last", 0L)
            o.optString("legs", "").split(';').forEach { row ->
                val f = row.split(',')
                if (f.size != 6) return@forEach
                val ts = f[1].toLongOrNull() ?: return@forEach
                val tok = f[4].toDoubleOrNull() ?: return@forEach
                val sol = f[5].toDoubleOrNull() ?: return@forEach
                h.legs += Leg7962(f[0], ts, f[2], f[3] == "b", tok, sol)
            }
            o.optJSONArray("learned")?.let { arr -> for (i in 0 until arr.length()) h.learned += arr.optString(i) }
        } catch (_: Throwable) {}
        return h
    }

    private fun saveHist(h: Hist) {
        try {
            val o = JSONObject()
                .put("newest", h.newestSig).put("oldest", h.oldestSig).put("pages", h.pages)
                .put("done", h.backfillDone).put("last", h.lastFetchMs)
                .put("legs", h.legs.joinToString(";") { "${it.sig},${it.tsMs},${it.mint},${if (it.isBuy) "b" else "s"},${it.tokens},${it.sol}" })
                .put("learned", JSONArray(h.learned.toList()))
            LearningPersistence.save(HIST_KEY_PREFIX + h.addr, o.toString())
        } catch (_: Throwable) {}
    }

    // ── diag ──

    fun statusLine7962(): String {
        val histories = hists.values.count { it.pages > 0 }
        val roundTrips = hists.values.sumOf { it.closedTrips }
        val ownerTrades = hists[OWNER_WALLET_7962]?.closedTrips ?: 0
        val top = synchronized(cells) {
            cells.entries.filter { it.value.n >= 5 }.sortedByDescending { cellLiftOf7962(it.value.n, it.value.sum) }.take(3)
                .joinToString(",") { "${it.key}:n${it.value.n}:${"%+.0f".format(cellLiftOf7962(it.value.n, it.value.sum))}%" }
        }.ifBlank { "-" }
        val tier = try { com.lifecyclebot.engine.truth.SignalSourceProof7291.copyTier7962() } catch (_: Throwable) { "?" }
        val pending = synchronized(pendingLearn) { pendingLearn.values.sumOf { it.size } }
        return "wallets=${experts.size} histories=$histories roundTrips=$roundTrips ownerTrades=$ownerTrades motifs=${motifsAdded.get()} " +
            "topCells=$top copyTier=$tier | learned=${tripsLearned.get()} pendingLearn=$pending noBars=${barsMissing.get()} " +
            "exitSamples=${exitSamples.get()} pages=${pagesFetched.get()} creditDeferred=${creditDeferred.get()} cycles=${cycles.get()} " +
            "liveExpertBuys=${liveBuys.get()} untrackedTape=${untrackedTape.get()} leaderboard=$leaderboard"
    }
}
