package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7088 — ASK EVERY FEED AT ONCE, AND BELIEVE THE ONES THAT AGREE.
 *
 * Operator: "well feed the data feed bro. dont do a fall back chain run then
 * in parallel please. use helius again. there's too many holes. add more free
 * keyless data sources, use raydiums data feed, pump funds data feed, dex,
 * etc. in parallel not serial."
 *
 * TWO DEFECTS, AND THE SECOND IS THE EXPENSIVE ONE.
 *
 * 1. THE CHAIN IS SERIAL. KeylessPriceSources6996.fillMissing runs DefiLlama,
 *    then Jupiter for whatever DefiLlama missed, and only AFTER DexScreener
 *    has already returned nothing. Every hole costs a full round trip before
 *    the next source is even asked, and the 5.0.7082 report shows what that
 *    buys:
 *
 *      dexscreener    sr=0%    4xx=10
 *      jupiter_quote  sr=20%   4xx=64
 *      MARK_BATCH_EMPTY_6970  cause=rate_limited_or_no_data
 *      QUOTE_STALE_6452: 502, with three open positions stale 83-159s
 *
 * 2. ONE SOURCE CANNOT BE CHECKED. This is what actually cost money. A single
 *    provider reported a market cap of $822,358,177 for a token whose real cap
 *    was $675,220, and with nothing to compare it against, V5.0.7069 believed
 *    it and manufactured a price 1211x too high (see V5.0.7087). A lone number
 *    is unfalsifiable. Five numbers are evidence.
 *
 * SO THIS ASKS EVERYTHING AT ONCE AND USES AGREEMENT AS THE PROOF:
 *
 *      DexScreener   batch  /tokens/v1/solana/a,b,c
 *      DefiLlama     batch  coins.llama.fi, separate infrastructure
 *      Jupiter       batch  lite price surface, sr=96% while DexScreener was 0%
 *      Raydium       batch  api-v3.raydium.io/mint/price, sr=100% on device,
 *                           and the #1 native Solana DEX, so it covers the
 *                           freshly-graduated memes DexScreener has not indexed
 *      Helius DAS    batch  getAssetBatch -> token_info.price_info, sr=100%
 *      pump.fun      fan    frontend-api-v3/coins/{mint} for bonding-curve
 *                           mints that NO aggregator lists yet
 *
 * All six start together on one pool and the merge runs at a fixed deadline.
 * Total latency is the SLOWEST source, not the sum, and a dead provider costs
 * nothing but its own absence — which is the whole point, because on this
 * device two of the six are dead at any given moment.
 *
 * THE MERGE IS A MEASUREMENT, NOT A PREFERENCE. V5.0.7087's lesson is that
 * picking a winner between disagreeing numbers is an inference, and inference
 * is what invented the 1211x. So there is no source ranking here and no
 * "trusted" feed:
 *
 *   >=2 sources agree within AGREE_TOLERANCE  -> CORROBORATED, median of the
 *                                               agreeing cluster
 *   exactly 1 source answered                 -> uncorroborated, returned and
 *                                               labelled as such
 *   sources answered and none agree           -> CONTESTED, median of all,
 *                                               uncorroborated
 *
 * The $822M cap could not survive this: four other feeds would have reported
 * $675k, the cluster of four wins on count alone, and the outlier is visible
 * in the spread rather than written into the book.
 *
 * NOTHING HERE DECIDES A TRADE. It returns marks with a corroboration flag.
 * DataLegitimacyAuthority7077 remains the authority on whether a mint may be
 * traded, and an uncorroborated mark simply does not carry the evidence to
 * qualify one. No cap, no band, no clamp — a genuine 1000x agreed by five
 * feeds is a corroborated 1000x.
 *
 * OFF THE HOT PATH. resolve7088 BLOCKS for up to DEADLINE_MS and must only be
 * called from a background mark-refresh pass, never from a decision path
 * (HOT_PATH_PROVIDER_CALL_SENTINEL_4295).
 */
object ParallelMarkFanout7088 {

    private const val TAG = "ParallelMark7088"

    /**
     * How close two independent feeds must be to count as agreeing.
     *
     * This is NOT a band on price movement — it never refuses a mark for being
     * large. It is the width of ordinary cross-venue spread plus sampling skew
     * between feeds that polled a few hundred milliseconds apart. 2% is wide
     * enough that honest disagreement does not read as a fault, and narrow
     * enough that a 1211x outlier can never join a cluster.
     */
    private const val AGREE_TOLERANCE = 0.02

    /** Whole fan-out budget. The slowest source, not the sum of all six. */
    private const val DEADLINE_MS = 4_000L

    /** pump.fun is per-mint, so its fan-out is bounded to stay polite. */
    private const val PUMPFUN_MAX_MINTS = 12

    // V5.0.7090 — the PUMPFUN_SUPPLY constant is gone. PumpFunPriceUnits7017
    // owns the mcap -> price derivation for this endpoint and reads the payload's
    // own supply; a second copy of the 1e9 constant here was exactly the
    // assumption V5.0.7089 had to remove from intake.

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "mark-fanout-7088").apply { isDaemon = true }
    }

    private val http = SharedHttpClient.builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    /** One reusable client; DexscreenerApi is a class, so it needs an instance. */
    private val dexClient7088 by lazy { DexscreenerApi() }

    @Volatile
    private var rpcUrl: String = ""

    private val passes = AtomicLong(0L)
    private val corroborated = AtomicLong(0L)
    private val single = AtomicLong(0L)
    private val contested = AtomicLong(0L)
    private val sourceWins = ConcurrentHashMap<String, AtomicLong>()

    /** Helius DAS needs the same RPC endpoint V5.0.7075 already resolves. */
    fun installRpc7088(url: String) {
        if (url.isNotBlank()) rpcUrl = url
    }

    data class Mark7088(
        val priceUsd: Double,
        val sourceCount: Int,
        val agreeingCount: Int,
        val corroborated: Boolean,
        val spreadPct: Double,
        val sources: String,
    )

    /**
     * Price [mints] from every available feed at once. Blocking, background
     * only. Returns only mints at least one feed answered for; an absent mint
     * means "nobody answered this pass", never "worthless" (V5.0.6982).
     */
    fun resolve7088(mints: List<String>): Map<String, Mark7088> {
        val wanted = mints.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        passes.incrementAndGet()

        // source name -> (mint -> price). Written once per source, from that
        // source's own thread, then read after the latch. No shared mutation.
        val results = ConcurrentHashMap<String, Map<String, Double>>()

        val tasks: List<Pair<String, () -> Map<String, Double>>> = listOf(
            // DexScreener is a CLASS, not an object — it is reached through an
            // instance (BotService:10753 does `dex.batchPriceFetch`). Calling it
            // statically is the exact mistake ci/static_call_check.py exists to
            // catch, and it has caught me three times this session.
            // V5.0.7090 — CHUNKED AT 30, because batchPriceFetch does not chunk:
            // it does `mints.take(30)` and SILENTLY DISCARDS the rest. BotService
            // chunks at 30 before calling it (:10742) so that truncation never
            // fired there — but V5.0.7088 passed the whole list, so every mint
            // past the thirtieth was dropped with no counter and no log. A silent
            // drop in a mark path is precisely the kind of hole this fan-out was
            // built to close, and I introduced one while closing the others.
            "DEXSCREENER" to {
                safe {
                    val acc = HashMap<String, Double>(wanted.size)
                    wanted.chunked(30).forEach { c -> acc.putAll(dexClient7088.batchPriceFetch(c)) }
                    acc
                }
            },
            "DEFILLAMA" to { safe { KeylessPriceSources6996.defiLlamaBatch(wanted) } },
            "JUPITER" to { safe { KeylessPriceSources6996.jupiterBatch(wanted) } },
            "RAYDIUM" to { safe { raydiumBatch7088(wanted) } },
            "HELIUS_DAS" to { safe { heliusAssetBatch7088(wanted) } },
            "PUMPFUN" to { safe { pumpFunFanout7088(wanted) } },
        )

        val latch = CountDownLatch(tasks.size)
        for ((name, fetch) in tasks) {
            try {
                pool.execute {
                    try {
                        val got = fetch()
                        if (got.isNotEmpty()) results[name] = got
                    } catch (_: Throwable) {
                    } finally {
                        latch.countDown()
                    }
                }
            } catch (_: Throwable) {
                // Pool refused the task; do not leave the latch hanging.
                latch.countDown()
            }
        }
        try {
            latch.await(DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Snapshot whatever landed inside the deadline. A source that is still
        // running is simply absent — it is never waited on and never cancels
        // the pass, which is the entire difference from the serial chain.
        val landed = results.toMap()
        for ((name, map) in landed) {
            if (map.isNotEmpty()) {
                sourceWins.computeIfAbsent(name) { AtomicLong(0L) }.addAndGet(map.size.toLong())
            }
        }

        val out = HashMap<String, Mark7088>(wanted.size)
        for (mint in wanted) {
            val quotes = ArrayList<Pair<String, Double>>(landed.size)
            for ((name, map) in landed) {
                val p = map[mint] ?: continue
                if (p.isFinite() && p > 0.0) quotes += name to p
            }
            if (quotes.isEmpty()) continue
            out[mint] = merge7088(mint, quotes)
        }

        if (out.isNotEmpty()) {
            try {
                PipelineHealthCollector.labelInc("PARALLEL_MARK_FANOUT_PASS_7088")
                ErrorLogger.info(
                    TAG,
                    "fanout priced ${out.size}/${wanted.size} from ${landed.size}/${tasks.size} live feeds",
                )
            } catch (_: Throwable) {}
        }
        return out
    }

    /**
     * Agreement, not preference. See the class doc — no source outranks
     * another, because ranking is the inference that invented V5.0.7069's
     * 1211x.
     */
    private fun merge7088(mint: String, quotes: List<Pair<String, Double>>): Mark7088 {
        val names = quotes.joinToString("+") { it.first }
        if (quotes.size == 1) {
            single.incrementAndGet()
            try { PipelineHealthCollector.labelInc("MARK_SINGLE_SOURCE_UNCORROBORATED_7088") } catch (_: Throwable) {}
            return Mark7088(quotes[0].second, 1, 1, corroborated = false, spreadPct = 0.0, sources = names)
        }

        // Largest set of quotes that all sit within AGREE_TOLERANCE of one
        // candidate. O(n^2) over at most six values.
        var best: List<Double> = emptyList()
        for ((_, anchor) in quotes) {
            val cluster = quotes.map { it.second }.filter { v ->
                val ratio = if (anchor > 0.0) v / anchor else 0.0
                ratio in (1.0 - AGREE_TOLERANCE)..(1.0 + AGREE_TOLERANCE)
            }
            if (cluster.size > best.size) best = cluster
        }

        val all = quotes.map { it.second }.sorted()
        val spread = if (all.first() > 0.0) (all.last() / all.first()) - 1.0 else 0.0

        if (best.size >= 2) {
            corroborated.incrementAndGet()
            return Mark7088(median(best), quotes.size, best.size, corroborated = true, spreadPct = spread * 100.0, sources = names)
        }

        // Everyone answered, nobody agrees. Report it loudly and hand back the
        // median WITHOUT a corroboration claim — 7077 will decline to qualify
        // the mint, which is the honest response to "these numbers disagree".
        contested.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("MARK_CONTESTED_NO_AGREEMENT_7088")
            if (contested.get() % 20L == 1L) {
                ForensicLogger.lifecycle(
                    "MARK_CONTESTED_NO_AGREEMENT_7088",
                    "mint=${mint.take(10)} sources=$names " +
                        "quotes=${quotes.joinToString(",") { "${it.first}=${it.second}" }} " +
                        "spreadPct=${"%.1f".format(spread * 100.0)} " +
                        "action=return_median_uncorroborated_7077_will_not_qualify",
                )
            }
        } catch (_: Throwable) {}
        return Mark7088(median(all), quotes.size, 1, corroborated = false, spreadPct = spread * 100.0, sources = names)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    private inline fun safe(block: () -> Map<String, Double>): Map<String, Double> =
        try { block() } catch (_: Throwable) { emptyMap() }

    /**
     * Raydium v3 batches on a comma-separated mint list and answers
     * `{"data": {"<mint>": "<price>"}}`. PriceAggregator has used the
     * single-mint form since V5.0.6065; this is the same endpoint asked for
     * many mints at once.
     */
    private fun raydiumBatch7088(mints: List<String>): Map<String, Double> {
        val out = HashMap<String, Double>()
        mints.chunked(40).forEach { chunk ->
            try {
                val url = "https://api-v3.raydium.io/mint/price?mints=${chunk.joinToString(",")}"
                val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                // V5.0.7092 — same reasoning as the pump.fun source below. Raydium
                // is healthy today (sr=100%, 670 quotes) but a raw execute() means
                // the day it is not, nothing throttles.
                com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, host = "raydium").use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val data = JSONObject(body).optJSONObject("data") ?: return@use
                    for (m in chunk) {
                        val v = data.optString(m, "").toDoubleOrNull() ?: continue
                        if (v.isFinite() && v > 0.0) out[m] = v
                    }
                }
            } catch (_: Throwable) {}
        }
        return out
    }

    /**
     * Helius DAS getAssetBatch. The operator asked for Helius to be used again
     * and it is the healthiest provider on the device (sr=100%); this reads
     * `token_info.price_info.price_per_token`, which is a different derivation
     * from every DEX aggregator above and therefore a genuinely independent
     * vote rather than a fifth copy of the same number.
     */
    private fun heliusAssetBatch7088(mints: List<String>): Map<String, Double> {
        val url = rpcUrl
        if (url.isBlank()) return emptyMap()
        val out = HashMap<String, Double>()
        mints.chunked(100).forEach { chunk ->
            try {
                val ids = chunk.joinToString(",") { "\"$it\"" }
                // V5.0.7188 §THE_SIXTH_FEED_HAS_NEVER_RETURNED_A_SINGLE_QUOTE.
                //
                // Operator 5.0.7186, 884s: quotesBySource=[RAYDIUM=4887,
                // JUPITER=4158, DEFILLAMA=2044, DEXSCREENER=805, PUMPFUN=2].
                // HELIUS_DAS is ABSENT — sourceWins only records a feed that
                // returned a non-empty map, so this one has answered zero times
                // since it was written, while `rpc=set` and helius health reads
                // 100%. The fan-out that exists to make agreement possible has
                // been running on four feeds, averaging 2.16 quotes per
                // multi-source mark.
                //
                // Cause: Helius DAS treats fungible tokens as opt-in. Without
                // `displayOptions.showFungible`, getAssetBatch omits the
                // `token_info` object entirely for SPL tokens — and
                // `token_info.price_info.price_per_token` is the only field
                // this feed reads. So every response parsed cleanly, found no
                // token_info, and produced an empty map. No error, no 4xx, no
                // counter: a silent zero. The parse loop below was never wrong;
                // it was being handed assets with the price section stripped.
                //
                // Recording is fixed in the same edit. This was the one feed
                // calling http.newCall raw, so ApiBackoff/ApiHealthMonitor never
                // observed it — `helius s=1` in that snapshot is one call for
                // the whole app, which is why the outage was invisible. Every
                // other feed in this file was moved onto HealthAwareHttp in
                // V5.0.7092 for exactly this reason and this one was missed.
                val payload =
                    """{"jsonrpc":"2.0","id":"7088","method":"getAssetBatch",""" +
                        """"params":{"ids":[$ids],"displayOptions":{"showFungible":true}}}"""
                val req = Request.Builder()
                    .url(url)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, host = "helius").use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val arr = JSONObject(body).optJSONArray("result") ?: return@use
                    for (i in 0 until arr.length()) {
                        val asset = arr.optJSONObject(i) ?: continue
                        val id = asset.optString("id", "")
                        if (id.isBlank()) continue
                        val price = asset.optJSONObject("token_info")
                            ?.optJSONObject("price_info")
                            ?.optDouble("price_per_token", 0.0) ?: 0.0
                        if (price.isFinite() && price > 0.0) out[id] = price
                    }
                }
            } catch (_: Throwable) {}
        }
        return out
    }

    /**
     * pump.fun's own feed, for the mints that matter most and that nobody else
     * carries: bonding-curve tokens too new to be indexed by any aggregator.
     * The 5.0.7082 intake was 43 of 75 events from PUMP_PORTAL_WS, so this is
     * the largest coverage hole in the book.
     *
     * `/coins/{mint}` reports usd_market_cap, and pump.fun fixes bonding-curve
     * supply at 1e9 by protocol, so price = cap / 1e9 is exact ON THAT BASIS —
     * a protocol constant, not an assumed supply. Per-mint, so it is bounded
     * and runs its own small fan-out inside this one task.
     */
    private fun pumpFunFanout7088(mints: List<String>): Map<String, Double> {
        val targets = mints.filter { PumpFunDirectApi.isPumpFunMint(it) }.take(PUMPFUN_MAX_MINTS)
        if (targets.isEmpty()) return emptyMap()
        val out = ConcurrentHashMap<String, Double>()
        val latch = CountDownLatch(targets.size)
        for (mint in targets) {
            try {
                pool.execute {
                    try {
                        val url = "https://frontend-api-v3.pump.fun/coins/$mint"
                        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                        // V5.0.7092 — through HealthAwareHttp so ApiBackoff can SEE
                        // this host fail. V5.0.7088 called execute() raw, which is
                        // why the operator's 5.0.7091 device reports
                        // `pumpfun sr=0% 5xx=233`: the circuit breaker never
                        // observed the failures, so nothing ever backed off and the
                        // fan-out kept hammering a host that was returning 500 to
                        // every request. DexscreenerApi's own V5.0.6495 note says
                        // exactly this — "never bypass HealthAwareHttp/ApiBackoff" —
                        // and I bypassed it while building the thing meant to make
                        // provider failure cheap.
                        com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, host = "pumpfun").use { resp ->
                            if (!resp.isSuccessful) return@use
                            val body = resp.body?.string() ?: return@use
                            // V5.0.7090 — use the authority that already owns this
                            // derivation. V5.0.7088 (mine, one build ago) divided
                            // usd_market_cap by a HARDCODED 1e9, which is the exact
                            // assumption class that produced V5.0.7089's fake entry
                            // prices. PumpFunPriceUnits7017 exists because
                            // `total_supply` from this endpoint is in RAW BASE
                            // UNITS — 1e15 for a standard 1e9-token supply at 6
                            // decimals — so dividing by it as though it were whole
                            // tokens is wrong by exactly 1e6. Its ladder reads the
                            // payload's own supply first and only falls back to the
                            // 1e9 protocol constant when the payload cannot support
                            // a number. Writing a second derivation beside it was
                            // the duplicate-authority defect, committed by me while
                            // fixing the same defect elsewhere.
                            val px = try {
                                com.lifecyclebot.engine.PumpFunPriceUnits7017
                                    .priceUsd(JSONObject(body))
                            } catch (_: Throwable) { 0.0 }
                            if (px.isFinite() && px > 0.0) out[mint] = px
                        }
                    } catch (_: Throwable) {
                    } finally {
                        latch.countDown()
                    }
                }
            } catch (_: Throwable) { latch.countDown() }
        }
        try { latch.await(3_000L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return out.toMap()
    }

    /** Diagnostic line for the pipeline report. */
    fun status(): String {
        val wins = sourceWins.entries
            .sortedByDescending { it.value.get() }
            .joinToString(",") { "${it.key}=${it.value.get()}" }
            .ifBlank { "none" }
        return "passes=${passes.get()} corroborated=${corroborated.get()} " +
            "singleSource=${single.get()} contested=${contested.get()} " +
            "rpc=${if (rpcUrl.isBlank()) "unset" else "set"} quotesBySource=[$wins]"
    }
}
