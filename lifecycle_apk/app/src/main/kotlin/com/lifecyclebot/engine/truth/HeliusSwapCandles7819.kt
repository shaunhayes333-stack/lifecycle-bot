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
 * Transactions history for the actual pool/bonding-curve ACCOUNT (not the mint): each executed swap in
 * the window gives an executed price (SOL leg / token leg of the trader) and
 * its block timestamp; the prints are binned into 1m bars. No bar is made
 * where no trade happened, and nothing is interpolated (Field Manual L190:
 * unknown is not a substitute; L325: realistic data, no look-ahead).
 *
 * BOUNDED — the Enhanced API is a paid credit sink:
 *   * only for a mint whose own bars are short (TradePlan7739 asks, on a
 *     TOO_FEW_BARS read); V5.0.8284: a held position or a token on 4 h bars
 *     gets its 1 m bars in CandleCoverage8284's compatible store, never in the
 *     open-position tick tape;
 *   * single-flight per mint, at most [MAX_IN_FLIGHT] jobs at once;
 *   * at most [MAX_JOBS_PER_MIN] jobs per minute and [MAX_JOBS_PER_HOUR] per
 *     hour, each at most [MAX_PAGES] pages of 100 transactions;
 *   * a mint is re-asked no sooner than [MINT_RETRY_MS] (or [EMPTY_RETRY_MS]
 *     after a result with no swaps).
 *
 * PRECEDENCE (V5.0.8284, superseding "only into empty minutes"). Bars merge
 * by minute and quality (CandleCoverage8284): TRADE_OHLCV > VALID_POOL_OHLCV >
 * TRADE_AGGREGATED > MARK_ONLY > INCOMPLETE > UNKNOWN; a better candle replaces
 * a worse one of a closed minute, an equal or better one is kept. SOL is converted at the current SOL/USD; over a 30 minute window
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
    // V5.0.7925 — 3/min, 60/h starved the bars every setup and plan reads (5.0.7914:
    // 24 jobs ran, 654 skipped on budget, keyless OHLCV delivered 0 bars) while Helius
    // spend sat near 10% of its daily budget. Doubled+ within that headroom.
    // V5.0.8382 — 8/min, 180/h left shape missing on most decision coins (5.0.8381: budgetSkips=212 in 4 min, core
    // features complete 3,479/9,834) while Helius ran at 15.2k of its 300k-credit day (pace 43k). Every job already passes
    // HeliusCreditEconomy7881 (admitCredits8307: the day's pace bounds total spend), so the credit economy, not a fixed
    // job count, is the budget: held / near-decision 24/min, 720/h; a hand-off 12/min, 360/h; bulk unchanged.
    private const val MAX_JOBS_PER_MIN = 24
    private const val MAX_JOBS_PER_HOUR = 720
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
    data class SwapPrint7819(val tsMs: Long, val priceSol: Double, val sol: Double, val isBuy: Boolean,
                             val signature: String = "")   // V5.0.8307 — the transaction (one trade across feeds)

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val nextAllowedMs = ConcurrentHashMap<String, Long>()
    private val jobStarts = ArrayList<Long>() // retained as the started-only history
    private val admission8386 = CandleAdmission8386(MAX_IN_FLIGHT, jobStarts, ::budgetAdmits8284)
    private val refused8386 = ConcurrentHashMap<String, AtomicLong>()
    private fun refusal8386(reason: String) { refused8386.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet() }
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
    private val noMarketAddress8269 = AtomicLong(0L)

    /**
     * A Solana mint is an asset identifier, not the account touched by every market trade.
     * The old /v0/addresses/{mint}/transactions read returned almost no usable swaps.
     * Never spend 100 Helius credits on that mistaken address; require a real venue account.
     * A completed pump curve is not an active market, so its old PDA is never used after migration.
     */
    internal fun historyAddress8269(mint: String, pool: String, curve: String, graduated: Boolean): String? {
        fun valid(a: String): String? = a.trim().takeIf {
            it != mint && com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(it)
        }
        return if (graduated) valid(pool) else valid(curve) ?: valid(pool)
    }

    private fun activeMarketAddress8269(ts: TokenState): String? {
        val mint = ts.mint
        val graduated = try { com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(mint) } catch (_: Throwable) { false }
        val pool = listOf(ts.pairAddress, ts.lastPricePoolAddr).firstOrNull {
            it != mint && com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(it)
        }.orEmpty()
        val remembered = try { com.lifecyclebot.network.PumpCurveKeys7269.keyFor(mint) } catch (_: Throwable) { null }
        val pumpCurve = if (!graduated && (mint.endsWith("pump", ignoreCase = true) || remembered != null)) {
            try { com.lifecyclebot.network.PumpCurveKeys7269.canonicalCurveKey7392(mint) } catch (_: Throwable) { null }
                ?: remembered
        } else null
        return historyAddress8269(mint, pool, pumpCurve.orEmpty(), graduated)
    }

    /**
     * Ask for a Helius-derived backfill of [ts]'s last 30 minutes. Never blocks:
     * the work runs on a daemon thread and its bars land in ts.history.
     */
    fun request7819(ts: TokenState, nowMs: Long = System.currentTimeMillis()) {
        val mint = ts.mint
        if (!com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(mint)) return // V5.0.7819 HeliusScope — Solana mints only (Field Manual L186)
        // V5.0.8284 — 1 m coverage (not the bar count, not the active timeframe) decides; a held or 4 h token's bars go to
        // the compatible 1 m store (CandleCoverage8284.merge8284). Too young for 1 m bars: its own observed trades only.
        if (inFlight.contains(mint)) { refusal8386("duplicate"); return }
        val priority = priority8284(mint, nowMs)
        val cov1m = CandleCoverage8284.noteNeed8284(ts, nowMs)
        if (cov1m == CandleCoverage8284.Coverage8284.COVERED) { coveredSkips8284.incrementAndGet(); return }
        if (cov1m == CandleCoverage8284.Coverage8284.NOT_YET_POSSIBLE) { localTape8284(ts, nowMs, "young"); priceBars8287(ts, nowMs); return }
        val marketAddress = activeMarketAddress8269(ts) ?: run {
            noMarketAddress8269.incrementAndGet()
            localTape8284(ts, nowMs, "noMarket"); priceBars8287(ts, nowMs)
            return
        }
        val next = nextAllowedMs[mint] ?: 0L
        if (nowMs < next) { localTape8284(ts, nowMs, "retryWait"); priceBars8287(ts, nowMs); return }
        if (!heliusAfterFree8418(ts, nowMs, priority)) return   // V5.0.8418 — free sources first; Helius is the fallback
        // V5.0.8453 — the 100-credit Enhanced history read is for HELD positions only (their exits read the chart). 8450:
        // SWAP_CANDLES spent 109.7k of the day's 300k credits (near-decision coins claimed the decision reserve with a
        // priority lease) while the 1-credit safety-proof reads were refused. Everything else keeps the free bars above.
        if (!heliusCandlesAllowed8453(priority)) { refusal8386("not_held_8453"); return }
        requests.incrementAndGet()
        // V5.0.7881 — up to two 100-credit pages per job (288k credits/day at the old
        // 60/h cap); now also paced on the economy's SWAP_CANDLES share.
        // V5.0.8284 — the per-minute / per-hour job budget is tiered: held > near-decision > hand-off > bulk.
        // V5.0.8307 — held / near-decision coins may use the day's on-pace headroom beyond the candles' own share.
        val reservation = admission8386.reserve(mint, nowMs, priority)
        if (reservation != "reserved") {
            refusal8386(reservation); budgetSkips.incrementAndGet()
            localTape8284(ts, nowMs, reservation); priceBars8287(ts, nowMs)
            return
        }
        inFlight.add(mint)
        val credits = HeliusCreditEconomy7881.reserve8386(HeliusCreditEconomy7881.Consumer.SWAP_CANDLES,
            200.0, priority >= PRIORITY_NEAR_DECISION_8284, nowMs)
        if (credits == null) {
            refusal8386("credit_pacing"); budgetSkips.incrementAndGet()
            inFlight.remove(mint); admission8386.finish(mint)
            localTape8284(ts, nowMs, "credit_pacing"); priceBars8287(ts, nowMs)
            return
        }
        try {
            Thread({
                try {
                    val startedAt = System.currentTimeMillis()
                    val key = apiKey(startedAt)
                    if (key.isBlank()) { noKey.incrementAndGet(); refusal8386("missing_key") }
                    else if (!Thread.currentThread().isInterrupted && admission8386.start(mint, startedAt)) {
                        nextAllowedMs[mint] = startedAt + MINT_RETRY_MS
                        if (nextAllowedMs.size > 4_000) nextAllowedMs.entries.removeIf { it.value < startedAt }
                        runJob(ts, marketAddress, key, credits)
                    } else refusal8386("cancelled_before_start")
                } catch (_: Exception) { refusal8386("worker_failure") }
                finally {
                    HeliusCreditEconomy7881.finish8386(credits)
                    inFlight.remove(mint); admission8386.finish(mint)
                }
            }, "helius-candles-7819").apply { isDaemon = true }.start()
        } catch (_: Exception) {
            refusal8386("worker_start_failed")
            HeliusCreditEconomy7881.finish8386(credits)
            inFlight.remove(mint); admission8386.finish(mint)
        }
    }

    /**
     * V5.0.8418 — the free pass every request runs before any credit is reserved: the mint's own local tape, its observed
     * price bars and (curve coins) the cached keyless pump.fun candles. True only when HeliusCandleGate8418 leaves Helius
     * as the fallback: still short of 1 m bars, not a bulk coin, past the free grace (a held position does not wait).
     */
    /** Pure (V5.0.8453): only a held position's chart may cost Enhanced-API credits. */
    fun heliusCandlesAllowed8453(priority: Int): Boolean = priority >= PRIORITY_HELD_8284

    private fun heliusAfterFree8418(ts: TokenState, nowMs: Long, priority: Int): Boolean {
        val firstAsk = HeliusCandleGate8418.firstAsk8418(ts.mint, nowMs)
        localTape8284(ts, nowMs, "free_first_8418"); priceBars8287(ts, nowMs)
        pumpFunBars8418(ts, nowMs)
        val covered = try {
            CandleCoverage8284.coverage8284(ts, nowMs)[1] == CandleCoverage8284.Coverage8284.COVERED
        } catch (_: Throwable) { false }
        val d = HeliusCandleGate8418.decide8418(priority, covered, firstAsk, nowMs)
        HeliusCandleGate8418.note8418(d)
        if (d == HeliusCandleGate8418.Decision8418.HELIUS_FALLBACK) return true
        if (d == HeliusCandleGate8418.Decision8418.COVERED_BY_FREE) coveredSkips8284.incrementAndGet()
        else refusal8386(d.name.lowercase())
        return false
    }

    /** V5.0.8418 — the keyless pump.fun curve candles (cached, fetched off-thread by their owner) merged by quality. */
    private fun pumpFunBars8418(ts: TokenState, nowMs: Long) {
        val bars = try { com.lifecyclebot.network.PumpFunCandleStep8359.forCurveCoin8359(ts.mint, 30, nowMs) } catch (_: Throwable) { null } ?: return
        val r = try {
            CandleCoverage8284.merge8284(ts, bars, CandleCoverage8284.Quality8284.VALID_POOL_OHLCV, "pumpfun8359", "", nowMs)
        } catch (_: Throwable) { null } ?: return
        if (r.changed > 0) featureArrived8284(ts, nowMs)
    }

    const val PRIORITY_HELD_8284 = 3
    const val PRIORITY_NEAR_DECISION_8284 = 2
    const val PRIORITY_HANDOFF_8284 = 1

    /** V5.0.8284 — held 3 > near-decision 2 > open hand-off 1 > bulk 0. */
    fun priority8284(mint: String, nowMs: Long = System.currentTimeMillis()): Int {
        val held = try { com.lifecyclebot.engine.HeldPositionSupervisor7246.isHeld(mint) } catch (_: Throwable) { false }
        if (held) return PRIORITY_HELD_8284
        val near = try { com.lifecyclebot.engine.DataPipeline8191.nearDecision8191(mint, nowMs) } catch (_: Throwable) { false }
        if (near) return PRIORITY_NEAR_DECISION_8284
        val handoff = try { AdmissionHandoff8144.handoffOf8144(mint, nowMs)?.open == true } catch (_: Throwable) { false }
        return if (handoff) PRIORITY_HANDOFF_8284 else 0
    }

    /**
     * Pure (V5.0.8284): may a job of [priority] start with [lastMinute] / [lastHour] jobs already started? 8282: 4,704 of
     * 4,808 requests were skipped on budget, first come first served. Held and near-decision coins keep the whole
     * 24/min, 720/h (8382; 8/min, 180/h before); a hand-off 12/min, 360/h; bulk 4/min, 100/h, so bulk can never spend
     * the decision coins' share. The day's credit pace (HeliusCreditEconomy7881) bounds the total.
     */
    fun budgetAdmits8284(priority: Int, lastMinute: Int, lastHour: Int): Boolean {
        // superseded by V5.0.8418 (review 7ce191799) — the job count follows HeliusCandleGate8418: a bulk coin is keyless-only
        // (0 Helius jobs; was 4/min, 100/h), and the decision tiers keep their budgets only for jobs the gate let through
        // after the free sources (local tape, observed bars, pump.fun candles, the GeckoTerminal grace) left 1 m short.
        if (HeliusCandleGate8418.decide8418(priority, coveredByFree = false, firstFreeAskAtMs = 0L, nowMs = Long.MAX_VALUE) !=
            HeliusCandleGate8418.Decision8418.HELIUS_FALLBACK) return false
        val perMin = when { priority >= PRIORITY_NEAR_DECISION_8284 -> MAX_JOBS_PER_MIN; priority == PRIORITY_HANDOFF_8284 -> 12; else -> 0 }
        val perHour = when { priority >= PRIORITY_NEAR_DECISION_8284 -> MAX_JOBS_PER_HOUR; priority == PRIORITY_HANDOFF_8284 -> 360; else -> 0 }
        return lastMinute < perMin && lastHour < perHour
    }

    /** V5.0.8284 — the integrator's hook (FeatureRevision bump, re-evaluation of waiting candidates): called with the mint after a merge added or replaced candles. */
    @Volatile var onCandlesMerged8284Listener: (String) -> Unit = {}

    private val localTapeAt8284 = ConcurrentHashMap<String, Long>()
    private const val LOCAL_TAPE_GAP_MS_8284 = 30_000L
    /** V5.0.8317 — a launch-window coin's tape is re-binned every 10 s (its first minutes are the trade). */
    private const val LOCAL_TAPE_GAP_YOUNG_MS_8317 = 10_000L

    /** Pure (V5.0.8317): the local-tape pace for a coin [ageMin] old — 10 s inside the launch window, else 30 s. */
    fun localTapeGapMs8317(ageMin: Double): Long =
        if (LifecycleData8283.lifecycleOf8283(ageMin) == Lifecycle8283.NEW) LOCAL_TAPE_GAP_YOUNG_MS_8317 else LOCAL_TAPE_GAP_MS_8284

    private fun ageMin8317(ts: TokenState, nowMs: Long): Double =
        (try { CanonicalTokenBirthTime7440.launchAgeMs7767(ts, nowMs) } catch (_: Throwable) { null })?.let { it / 60_000.0 } ?: Double.NaN
    private val localTapeRuns8284 = AtomicLong(0L)
    private val localTapeBarCount8284 = AtomicLong(0L)
    private val localTapeChanged8284 = AtomicLong(0L)
    private val localTapeByReason8284 = ConcurrentHashMap<String, AtomicLong>()
    private val coveredSkips8284 = AtomicLong(0L)
    private val heliusReplaced8284 = AtomicLong(0L)
    private val heliusKept8284 = AtomicLong(0L)

    /** Pure (V5.0.8284): 1 m bars from observed executed tape prints only; a minute without a print gets no bar. */
    fun localTapeBars8284(prints: List<TradeTape8100.Print8128>, solUsd: Double, nowMs: Long): List<Candle> =
        binPrints7819(prints.map { SwapPrint7819(it.atMs, it.priceSol, it.sol, it.isBuy) }, solUsd, nowMs)

    /**
     * V5.0.8284 — the cheap path for a request that will not reach Helius (budget, retry wait, no market account, too
     * young): the mint's own executed prints already on the local tape, binned into 1 m bars (TRADE_AGGREGATED, below a
     * Helius trade candle), at most once per mint per [LOCAL_TAPE_GAP_MS_8284] ([localTapeGapMs8317]: 10 s inside the
     * launch window). No request, no credits.
     */
    internal fun localTape8284(ts: TokenState, nowMs: Long, reason: String, solUsd: Double = solUsd8284()): CandleCoverage8284.Merge8284? {
        val mint = ts.mint
        val last = localTapeAt8284[mint] ?: 0L
        if (nowMs - last in 0L until localTapeGapMs8317(ageMin8317(ts, nowMs))) return null   // V5.0.8317 — 10 s for a launch
        localTapeAt8284[mint] = nowMs
        if (localTapeAt8284.size > 4_000) MemoryBounds8289.capByAge8289(localTapeAt8284, 4_000, 600_000L, nowMs) { it }  // V5.0.8289 — hard cap
        localTapeRuns8284.incrementAndGet()
        localTapeByReason8284.computeIfAbsent(reason) { AtomicLong(0L) }.incrementAndGet()
        val prints = try { TradeTape8100.prints8128(mint, nowMs, WINDOW_MS) } catch (_: Throwable) { emptyList() }
        val bars = localTapeBars8284(prints, solUsd, nowMs)
        if (bars.isEmpty()) return null
        localTapeBarCount8284.addAndGet(bars.size.toLong())
        val r = CandleCoverage8284.merge8284(ts, bars, CandleCoverage8284.Quality8284.TRADE_AGGREGATED, "local_tape8100", "", nowMs)
        if (r.changed > 0) {
            localTapeChanged8284.addAndGet(r.changed.toLong())
            featureArrived8284(ts, nowMs)
        }
        return r
    }

    // V5.0.8287 — the fresh SOL price, else the last observed (8286: a cold start's 0.0 dropped every local bar).
    private fun solUsd8284(): Double = PriceShapeBars8287.solUsd8287()

    /**
     * V5.0.8287 — beside the executed-tape bars: the minutes in which the mint's venue was only PRICED (curve snapshots,
     * pool-state prices) become MARK_ONLY bars (zero volume, zero trades), so shape is resolvable from observed prices.
     */
    private fun priceBars8287(ts: TokenState, nowMs: Long) {
        try { PriceShapeBars8287.mergePriceOnly8287(ts, nowMs, WINDOW_MS) } catch (_: Throwable) {}
    }

    /** V5.0.8284 — repaired candles landed for [ts]: wake the shape feature, time-to-feature, and the integrator's hook. */
    private fun featureArrived8284(ts: TokenState, nowMs: Long) {
        try { CandidateAdmission8136.evidenceArrived8252(ts.mint, setOf("shape"), nowMs) } catch (_: Throwable) {}
        try { CandleCoverage8284.noteArrived8284(ts, nowMs) } catch (_: Throwable) {}
        // V5.0.8284 FEATURE_ARRIVED_SHAPE(mint)
        try { onCandlesMerged8284Listener(ts.mint) } catch (_: Throwable) {}
    }

    private fun apiKey(nowMs: Long): String {
        if (cachedKey.isNotBlank() && nowMs - cachedKeyAtMs < KEY_TTL_MS) return cachedKey
        val k = try { com.lifecyclebot.engine.RuntimeProviderAuthority6685.configuredHeliusKey() } catch (_: Throwable) { "" }
        cachedKey = if (k.isBlank() || k == "hive-pattern-learn") "" else k
        cachedKeyAtMs = nowMs
        return cachedKey
    }

    private fun runJob(ts: TokenState, marketAddress: String, key: String, credits: HeliusCreditEconomy7881.Reservation8386) {
        val startedAt = System.currentTimeMillis()
        jobs.incrementAndGet()
        val prints = ArrayList<SwapPrint7819>()
        var before = ""
        var page = 0
        while (page < MAX_PAGES) {
            page++
            // Correct Helius Enhanced History pagination is before-signature, NOT before.
            // A time floor excludes ancient transfers on very busy market accounts.
            val url = "$BASE/$marketAddress/transactions?api-key=$key&limit=$PAGE_LIMIT&gte-time=${(startedAt - WINDOW_MS) / 1000L}" +
                if (before.isNotBlank()) "&before-signature=$before" else ""
            val body = get(url, credits) ?: break
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
        tapeBackfill8307(ts.mint, prints, marketAddress)   // V5.0.8307 — the same paid page also fills the trade tape
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val bars = binPrints7819(prints, solUsd, System.currentTimeMillis())
        if (bars.isEmpty()) {
            empty.incrementAndGet()
            nextAllowedMs[ts.mint] = System.currentTimeMillis() + EMPTY_RETRY_MS
            try { PipelineHealthCollector.labelInc("HELIUS_SWAP_CANDLES_EMPTY_7819") } catch (_: Throwable) {}
            return
        }
        // V5.0.8284 — quality-aware: an authentic Helius trade candle replaces a lower-quality candle of its minute.
        val r8284 = CandleCoverage8284.merge8284(ts, bars, CandleCoverage8284.Quality8284.TRADE_OHLCV, "helius_enhanced_tx", marketAddress, System.currentTimeMillis())
        val merged = r8284.changed
        served.incrementAndGet()
        barsDelivered.addAndGet(bars.size.toLong())
        barsMerged.addAndGet(merged.toLong())
        heliusReplaced8284.addAndGet(r8284.replaced.toLong()); heliusKept8284.addAndGet(r8284.kept.toLong())
        if (merged > 0) try {
            featureArrived8284(ts, System.currentTimeMillis())
            com.lifecyclebot.engine.DataPipeline8191.noteSeed8191("helius", "merged")
        } catch (_: Throwable) {}
        try {
            PipelineHealthCollector.labelInc("HELIUS_SWAP_CANDLES_SERVED_7819")
            ForensicLogger.lifecycle(
                "HELIUS_SWAP_CANDLES_SERVED_7819",
                "mint=${ts.mint.take(10)} sym=${ts.symbol} prints=${prints.size} bars=${bars.size} merged=$merged replaced=${r8284.replaced} kept=${r8284.kept} " +
                    "pages=$page histNow=${ts.history.size} source=helius_enhanced_tx",
            )
        } catch (_: Throwable) {}
    }

    private val backfillTaped8307 = AtomicLong(0L)

    /** Pure: the tape venue of a backfilled print — the curve venue for the mint's bonding curve, else the pool address. */
    internal fun backfillVenue8307(marketAddress: String, curveKey: String?): String =
        if (!curveKey.isNullOrBlank() && marketAddress == curveKey) TradeTape8100.VENUE_CURVE_8285 else marketAddress

    /**
     * V5.0.8307 — the executed swaps of the one Enhanced-history page reach TradeTape8100 (source helius, venue, signature,
     * side, SOL, token size, block time) instead of being dropped after the bars: a newly evaluated coin has 30 minutes of
     * tape (bp / change / shape) at once. Signature dedup keeps a swap another feed already taped as one trade.
     */
    private fun tapeBackfill8307(mint: String, prints: List<SwapPrint7819>, marketAddress: String) {
        if (prints.isEmpty()) return
        val curve = try {
            com.lifecyclebot.network.PumpCurveKeys7269.keyFor(mint) ?: com.lifecyclebot.network.PumpCurveKeys7269.canonicalCurveKey7392(mint)
        } catch (_: Throwable) { null }
        val venue = backfillVenue8307(marketAddress, curve)
        val now = System.currentTimeMillis()
        for (p in prints) try {
            if (!(p.priceSol > 0.0 && p.sol > 0.0)) continue
            if (TradeTape8100.note8285(mint, p.priceSol, p.sol, p.sol / p.priceSol, p.isBuy, TradeTape8100.SRC_HELIUS_8169,
                    pool = venue, signature = p.signature, eventMs = p.tsMs, recvMs = now)) backfillTaped8307.incrementAndGet()
        } catch (_: Throwable) {}
    }

    private fun get(url: String, credits: HeliusCreditEconomy7881.Reservation8386): String? {
        val started = System.currentTimeMillis()
        val req = Request.Builder().url(url).header("Accept", "application/json").tag(HeliusCreditEconomy7881.Reservation8386::class.java, credits).build()
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
            out.add(SwapPrint7819(tsMs, px, solLeg, tokenNet > 0.0, tx.optString("signature", "")))
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

    /**
     * Merge fetched [bars] (their quality read from their face: CandleCoverage8284.infer8284) into ts.history by minute;
     * returns the candles added or replaced. V5.0.8284: a bar replaces only a lower-quality candle of a closed minute; an
     * equal or better one is kept, and a minute never ends up with two candles from this merge.
     */
    fun mergeIntoHistory7819(ts: TokenState, bars: List<Candle>): Int {
        val nowMs = System.currentTimeMillis()
        val r: CandleCoverage8284.Merge8284 = (try {
            synchronized(ts) {
                if (ts.candleTimeframeMinutes > 1) null else CandleCoverage8284.mergeInto8284(ts.history, bars, nowMs, MAX_HISTORY)
            }
        } catch (_: Throwable) { null }) ?: return 0
        CandleCoverage8284.noteMerge8284("fetched_kline", r)
        if (r.changed > 0) featureArrived8284(ts, nowMs)
        return r.changed
    }

    /** Served / bars for the per-source split in the §6916 and §7055 lines. */
    fun servedBars7819(): String = "served=${served.get()} bars=${barsDelivered.get()}"

    fun statusLine7819(): String =
        "requests=${requests.get()} jobs=${jobs.get()} pages=${pages.get()} prints=${printsParsed.get()} " +
            "served=${served.get()} barsDelivered=${barsDelivered.get()} merged=${barsMerged.get()} " +
            "empty=${empty.get()} httpErr=${httpErrors.get()} budgetSkips=${budgetSkips.get()} noKey=${noKey.get()} noMarket8269=${noMarketAddress8269.get()} " +
            "admission8386=$refused8386 inFlight=${inFlight.size} budget=${MAX_JOBS_PER_MIN}/min,${MAX_JOBS_PER_HOUR}/h,x${MAX_PAGES}pages backfillTaped8307=${backfillTaped8307.get()}"

    /** V5.0.8284 — Helius candles returned vs merged vs replaced vs kept, the local-tape path and coverage / time-to-feature. */
    fun statusLine8284(): String =
        "helius8284[returned=${barsDelivered.get()} merged=${barsMerged.get()} replaced=${heliusReplaced8284.get()} kept=${heliusKept8284.get()} " +
            "coveredSkips=${coveredSkips8284.get()}] localTape8284[runs=${localTapeRuns8284.get()} bars=${localTapeBarCount8284.get()} " +
            "changed=${localTapeChanged8284.get()} why{" + localTapeByReason8284.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" } +
            "}] " + CandleCoverage8284.statusLine8284() + " " + PriceShapeBars8287.statusLine8287()

    internal fun resetForTest() {
        inFlight.clear(); nextAllowedMs.clear(); admission8386.reset()
        requests.set(0L); jobs.set(0L); pages.set(0L); served.set(0L); barsDelivered.set(0L)
        barsMerged.set(0L); printsParsed.set(0L); empty.set(0L); httpErrors.set(0L); budgetSkips.set(0L); noKey.set(0L); noMarketAddress8269.set(0L)
        localTapeAt8284.clear(); localTapeRuns8284.set(0L); localTapeBarCount8284.set(0L); localTapeChanged8284.set(0L)
        localTapeByReason8284.clear(); coveredSkips8284.set(0L); heliusReplaced8284.set(0L); heliusKept8284.set(0L)
        onCandlesMerged8284Listener = {}
    }
}

/** The production mint, worker and rate reservation. No provider or Android dependency. */
internal class CandleAdmission8386(private val maxWorkers: Int, private val starts: MutableList<Long>,
                                  private val admits: (Int, Int, Int) -> Boolean) {
    private val reservations = LinkedHashMap<String, Boolean>()
    @Synchronized fun reserve(mint: String, now: Long, priority: Int): String {
        if (mint in reservations) return "duplicate"
        if (reservations.size >= maxWorkers) return "worker_full"
        starts.removeAll { now - it >= 3_600_000L }
        val waiting = reservations.values.count { !it }
        if (!admits(priority, starts.count { now - it < 60_000L } + waiting, starts.size + waiting)) return "job_rate"
        reservations[mint] = false
        return "reserved"
    }
    @Synchronized fun start(mint: String, now: Long): Boolean {
        if (reservations[mint] != false) return false
        reservations[mint] = true; starts.add(now)
        return true
    }
    @Synchronized fun finish(mint: String) { reservations.remove(mint) }
    @Synchronized fun reset() { reservations.clear(); starts.clear() }
}
