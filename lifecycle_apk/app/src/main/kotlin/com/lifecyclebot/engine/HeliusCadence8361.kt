package com.lifecyclebot.engine

import com.lifecyclebot.network.KeylessRpc8359
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * V5.0.8361 §HELIUS_CONSUMERS_ON_THE_OWNER_CADENCE — owner: "the market sweep should be maybe hourly or daily depending
 * on urgency of data, smart money wallet discovery is only meant to compile a live list of the alpha sol traders and
 * pumpfun traders too, 25, to watch their trades and copy trade where appropriate. realistically the trump wallet etc
 * only need a big buy or big sell signal."
 *
 *  SWEEP   The keyless sweep sources (Jupiter lists, Raydium, GMGN, DexScreener enrichment) keep their own clocks. The one
 *          RPC-costed source (HELIUS_SWAPS: a PumpSwap signature page + its transactions) runs at most once an hour and
 *          reads keyless-first through [KeylessRpc8359] (Helius only when every public rung declined).
 *  ALPHA   Once a day (and on boot when the stored list is older than 24 h) the top [ALPHA_MAX_8361] wallets are ranked
 *          from the closed round trips already recorded in ExpertWallets7962 (V5.0.8362: by the lower confidence bound
 *          mean − 1.0 × SE on the clipped per-trip net, then count, then mean; at least [ALPHA_MIN_TRIPS_8361] closed
 *          trips and a bound above 0 — see [rankAlpha8362]). They ride the existing smart-money
 *          transactionSubscribe (one socket, one accountInclude with the whales); their swaps on that stream extend
 *          their histories and their buys go to the existing copy path unchanged (copy stays behind its proof bar).
 *  WHALES  The tracked big-name wallets (InsiderWalletTracker) ride the same subscription, but only a move of at least
 *          [WHALE_BIG_SOL_8361] SOL or [WHALE_BIG_USD_8361] notional (whichever is reached first) is a signal
 *          (WHALE_BIG_BUY_8361 / WHALE_BIG_SELL_8361 into the existing insider-signal consumer). A smaller move is dropped:
 *          no store, no follow-up read, no holdings scan.
 *
 * Nothing here estimates a price or a size: SOL legs are the wallet's own lamport / WSOL balance change in the
 * transaction, USD notional is SOL x a FRESH SOL price (or a stablecoin leg); an unknown price leaves USD unknown.
 */
object HeliusCadence8361 {
    const val VERSION = "V5.0.8361"

    // ── sweep ──
    const val SWEEP_HELIUS_EVERY_MS_8361 = 60L * 60_000L

    /** Pure: is the sweep's RPC-costed source due ([lastMs] = its previous run, 0 = never)? At most hourly. */
    fun sweepHeliusDue8361(lastMs: Long, nowMs: Long): Boolean = lastMs <= 0L || nowMs - lastMs >= SWEEP_HELIUS_EVERY_MS_8361

    // ── alpha list ──
    const val ALPHA_MAX_8361 = 25
    const val ALPHA_MIN_TRIPS_8361 = 5
    const val ALPHA_REBUILD_MS_8361 = 24L * 60L * 60_000L
    const val ALPHA_BACKFILL_WALLETS_8361 = 10
    const val ALPHA_BACKFILL_SIGS_8361 = 20

    /**
     * One wallet's record from its recorded closed round trips. V5.0.8362: [sdNetPct] = the sample standard deviation of
     * the clipped per-trip net (0 when fewer than two trips), so the ranking can use a lower confidence bound.
     */
    data class AlphaCandidate8361(val wallet: String, val closed: Int, val wins: Int, val meanNetPct: Double, val pumpTrips: Int, val sdNetPct: Double = 0.0) {
        val winRate: Double get() = if (closed > 0) wins.toDouble() / closed else 0.0
        /** V5.0.8362 — mean − [LCB_Z_8362] × SE (SE = sd / √n); NaN when there are no trips. */
        val lcbNetPct: Double get() = HeliusCadence8361.lcbOf8362(meanNetPct, sdNetPct, closed)
    }

    /** Pure: a wallet's candidate row from its closed trips' realized % (each clipped to -100..+300 so one outlier cannot carry it). */
    fun candidateOf8361(wallet: String, realizedPcts: List<Double>, pumpTrips: Int): AlphaCandidate8361 {
        val ok = realizedPcts.filter { it.isFinite() }
        val clipped = ok.map { it.coerceIn(-100.0, 300.0) }
        val mean = if (clipped.isEmpty()) 0.0 else clipped.sum() / clipped.size
        // V5.0.8362 — sample sd of the clipped per-trip net (n − 1), for the confidence-bound ranking.
        val sd = if (clipped.size < 2) 0.0 else sqrt(clipped.sumOf { (it - mean) * (it - mean) } / (clipped.size - 1))
        return AlphaCandidate8361(wallet, ok.size, ok.count { it > 0.0 }, mean, pumpTrips, sd)
    }

    // ── V5.0.8362 §ALPHA_RANKED_BY_CONFIDENCE — owner chose: rank by a lower confidence bound on mean net per closed trip ──
    const val LCB_Z_8362 = 1.0

    /** Pure: mean − z × sd / √n (NaN when n ≤ 0 or an input is not finite). */
    fun lcbOf8362(mean: Double, sd: Double, n: Int, z: Double = LCB_Z_8362): Double {
        if (n <= 0 || !mean.isFinite() || !sd.isFinite()) return Double.NaN
        return mean - z * (sd.coerceAtLeast(0.0) / sqrt(n.toDouble()))
    }

    /** Pure: does a candidate qualify for the alpha list? At least [minTrips] closed trips and a confidence bound above 0. */
    fun qualifies8362(c: AlphaCandidate8361, minTrips: Int = ALPHA_MIN_TRIPS_8361): Boolean {
        val lcb = c.lcbNetPct
        return c.wallet.isNotBlank() && c.closed >= minTrips && c.meanNetPct.isFinite() && lcb.isFinite() && lcb > 0.0
    }

    /**
     * Pure: the alpha list (V5.0.8362, replaces the 8361 win-rate-first order) — qualifiers ranked by the lower confidence
     * bound on mean net per closed round trip, then more trips, then higher mean, then address (a stable tie-break), at
     * most [max]. A 5-for-5 small sample no longer outranks a long record with a positive mean.
     */
    fun rankAlpha8362(cands: List<AlphaCandidate8361>, max: Int = ALPHA_MAX_8361, minTrips: Int = ALPHA_MIN_TRIPS_8361): List<AlphaCandidate8361> =
        cands.asSequence()
            .filter { qualifies8362(it, minTrips) }
            .distinctBy { it.wallet }
            .sortedWith(compareByDescending<AlphaCandidate8361> { it.lcbNetPct }
                .thenByDescending { it.closed }
                .thenByDescending { it.meanNetPct }
                .thenBy { it.wallet })
            .take(max.coerceAtLeast(0))
            .toList()

    /** Pure: the status fragment for the top [k] listed wallets — `addr6:n12/m+14.2/lcb+6.1`, comma separated, "-" when none. */
    fun topLine8362(rows: List<AlphaCandidate8361>, k: Int = 5): String =
        rows.take(k.coerceAtLeast(0)).joinToString(",") {
            "${it.wallet.take(6)}:n${it.closed}/m${"%+.1f".format(it.meanNetPct)}/lcb${"%+.1f".format(it.lcbNetPct)}"
        }.ifBlank { "-" }

    /** Pure: is the daily alpha rebuild due ([lastMs] = previous rebuild, 0 = never / not stored)? */
    fun alphaRebuildDue8361(lastMs: Long, nowMs: Long): Boolean = lastMs <= 0L || nowMs - lastMs >= ALPHA_REBUILD_MS_8361

    /**
     * Pure: the one smart-money subscription's accountInclude — the whales, the owner when he is on the copy list, the
     * ranked alpha list, then (only while the ranked list is short of [max]) the existing copy wallets in their order.
     */
    fun watchListOf8361(whales: List<String>, alpha: List<String>, copyActive: List<String>, owner: String, max: Int = ALPHA_MAX_8361): List<String> {
        val slots = LinkedHashSet<String>()
        alpha.forEach { if (it.isNotBlank() && slots.size < max) slots.add(it) }
        copyActive.forEach { if (it.isNotBlank() && it != owner && slots.size < max) slots.add(it) }
        val out = LinkedHashSet<String>()
        whales.forEach { if (it.isNotBlank()) out.add(it) }
        if (owner.isNotBlank() && owner in copyActive) out.add(owner)
        out.addAll(slots)
        return out.toList()
    }

    // ── whales ──
    const val WHALE_BIG_SOL_8361 = 25.0
    const val WHALE_BIG_USD_8361 = 5_000.0

    /** Pure: is a whale move big? [sol] its SOL leg, [stableUsd] a USDC/USDT leg, [solUsd] a fresh SOL price (0 = unknown). */
    fun isBigMove8361(sol: Double, stableUsd: Double, solUsd: Double): Boolean {
        val s = if (sol.isFinite() && sol > 0.0) sol else 0.0
        val st = if (stableUsd.isFinite() && stableUsd > 0.0) stableUsd else 0.0
        val usd = st + (if (solUsd.isFinite() && solUsd > 0.0) s * solUsd else 0.0)
        return s >= WHALE_BIG_SOL_8361 || usd >= WHALE_BIG_USD_8361
    }

    // ── one parsed swap of a watched wallet ──
    private const val WSOL = "So11111111111111111111111111111111111111112"
    private val STABLES = setOf("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB")
    private const val MIN_LEG_SOL_8361 = 0.005

    data class Swap8361(
        val wallet: String, val sig: String, val tsMs: Long, val mint: String, val isBuy: Boolean,
        val tokens: Double, val sol: Double, val stableUsd: Double,
    )

    /**
     * Pure: the swaps [wallets] made in one jsonParsed transaction (a getTransaction result or a transactionSubscribe
     * push). A swap is a transaction where exactly one non-SOL, non-stable mint moved for the wallet; its SOL side is the
     * wallet's lamport change net of the fee it paid, or its WSOL change; a stablecoin leg is kept as USD. Failed
     * transactions and anything else are skipped.
     */
    fun swapsOf8361(result: JSONObject, wallets: Set<String>, fallbackTsMs: Long): List<Swap8361> {
        if (wallets.isEmpty()) return emptyList()
        val outer = result.optJSONObject("transaction") ?: return emptyList()
        val meta = outer.optJSONObject("meta") ?: result.optJSONObject("meta") ?: return emptyList()
        if (meta.has("err") && !meta.isNull("err")) return emptyList()
        val inner = outer.optJSONObject("transaction") ?: outer
        val message = inner.optJSONObject("message") ?: outer.optJSONObject("message") ?: return emptyList()
        val keys = ArrayList<String>()
        val ak = message.optJSONArray("accountKeys") ?: JSONArray()
        for (i in 0 until ak.length()) {
            val k = ak.opt(i)
            keys.add(if (k is JSONObject) k.optString("pubkey", "") else if (k is String) k else "")
        }
        val sig = result.optString("signature", "").ifBlank {
            inner.optJSONArray("signatures")?.optString(0, "").orEmpty().ifBlank { outer.optJSONArray("signatures")?.optString(0, "").orEmpty() }
        }
        val bt = result.optLong("blockTime", 0L)
        val tsMs = if (bt > 0L) bt * 1000L else fallbackTsMs
        val pre = meta.optJSONArray("preBalances") ?: JSONArray()
        val post = meta.optJSONArray("postBalances") ?: JSONArray()
        val fee = meta.optLong("fee", 0L).coerceAtLeast(0L)
        val net = HashMap<String, HashMap<String, Double>>()
        fun add(arr: JSONArray?, sign: Double) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val owner = b.optString("owner", "")
                val mint = b.optString("mint", "")
                if (owner !in wallets || mint.isBlank()) continue
                val amt = b.optJSONObject("uiTokenAmount")?.optDouble("uiAmount", 0.0) ?: 0.0
                if (!amt.isFinite()) continue
                val m = net.getOrPut(owner) { HashMap() }
                m[mint] = (m[mint] ?: 0.0) + sign * amt
            }
        }
        add(meta.optJSONArray("preTokenBalances"), -1.0)
        add(meta.optJSONArray("postTokenBalances"), 1.0)
        val out = ArrayList<Swap8361>()
        for (w in wallets) {
            val m = net[w] ?: continue
            val moved = m.entries.filter { it.key != WSOL && it.key !in STABLES && abs(it.value) > 0.0 }
            if (moved.size != 1) continue
            val mint = moved[0].key
            val tok = moved[0].value
            val buy = tok > 0.0
            val idx = keys.indexOf(w)
            val lamports = if (idx >= 0 && idx < pre.length() && idx < post.length()) post.optLong(idx, 0L) - pre.optLong(idx, 0L) else 0L
            val paidFee = if (idx == 0) fee else 0L
            val wsol = m[WSOL] ?: 0.0
            val stable = STABLES.sumOf { m[it] ?: 0.0 }
            val sol = if (buy) maxOf((-lamports - paidFee).toDouble() / 1e9, -wsol, 0.0) else maxOf((lamports + paidFee).toDouble() / 1e9, wsol, 0.0)
            val stableUsd = if (buy) maxOf(-stable, 0.0) else maxOf(stable, 0.0)
            if (sol < MIN_LEG_SOL_8361 && stableUsd <= 0.0) continue
            out += Swap8361(w, sig, tsMs, mint, buy, abs(tok), sol, stableUsd)
        }
        return out
    }

    // ── counters ──
    private val startedAtMs = System.currentTimeMillis()
    private val sweepHeliusRuns = AtomicLong(0L)
    private val sweepSkippedNotDue = AtomicLong(0L)
    private val sweepCredits = AtomicLong(0L)
    @Volatile private var lastSweepHeliusMs = 0L
    private val alphaCredits = AtomicLong(0L)
    private val buysSeen = AtomicLong(0L)
    private val sellsSeen = AtomicLong(0L)
    private val copyFed = AtomicLong(0L)
    private val bigBuys = AtomicLong(0L)
    private val bigSells = AtomicLong(0L)
    private val droppedSmall = AtomicLong(0L)
    private val scansSkipped = AtomicLong(0L)
    @Volatile private var whales: Set<String> = emptySet()
    @Volatile private var alphaListed: Set<String> = emptySet()
    @Volatile private var watching = 0

    fun noteSweepHelius8361(nowMs: Long) { sweepHeliusRuns.incrementAndGet(); lastSweepHeliusMs = nowMs }
    fun noteSweepNotDue8361() { sweepSkippedNotDue.incrementAndGet() }
    fun noteAlphaCredits8361(credits: Long) { alphaCredits.addAndGet(credits.coerceAtLeast(0L)) }
    fun noteWhaleScanSkipped8361() { scansSkipped.incrementAndGet() }

    /** BotService: the subscription's watch list (whales + owner + alpha) built from the live lists. */
    fun watchList8361(whaleList: List<String>, copyActive: List<String>): List<String> {
        whales = whaleList.filter { it.isNotBlank() }.toSet()
        val alpha = try { ExpertWallets7962.alphaList8361() } catch (_: Throwable) { emptyList() }
        alphaListed = alpha.toSet()
        val out = watchListOf8361(whaleList, alpha, copyActive, ExpertWallets7962.OWNER_WALLET_7962)
        watching = out.size - whales.size
        return out
    }

    /**
     * BotService push callback (V5.0.8361): routes one smart-money push. Whale-only wallets: a big move becomes a
     * WHALE_BIG_* signal, a small one is dropped. Alpha / copy wallets: their swaps extend the expert histories and are
     * counted. Returns [watched] without the whale-only wallets, for the unchanged copy-trade / sell-exit path.
     */
    fun copyWatched8361(raw: JSONObject, watched: List<String>, nowMs: Long = System.currentTimeMillis()): List<String> {
        val whaleOnly = watched.filter { it in whales && it !in alphaListed }.toSet()
        try {
            val swaps = swapsOf8361(raw, watched.toHashSet(), nowMs)
            val solUsd = try { WalletManager.freshSolPrice8163(nowMs) } catch (_: Throwable) { 0.0 }
            for (s in swaps) {
                if (s.wallet in whaleOnly) {
                    if (!isBigMove8361(s.sol, s.stableUsd, solUsd)) { droppedSmall.incrementAndGet(); continue }
                    if (s.isBuy) bigBuys.incrementAndGet() else bigSells.incrementAndGet()
                    val usd = s.stableUsd + (if (solUsd > 0.0) s.sol * solUsd else 0.0)
                    com.lifecyclebot.perps.InsiderWalletTracker.emitBigMove8361(s.wallet, s.mint, s.isBuy, s.sol, usd)
                    continue
                }
                if (s.isBuy) { buysSeen.incrementAndGet(); copyFed.incrementAndGet() } else sellsSeen.incrementAndGet()
                if (s.sol >= MIN_LEG_SOL_8361 && s.tokens > 0.0 && s.sig.isNotBlank()) {
                    ExpertWallets7962.noteStreamLeg8361(s.wallet, ExpertWallets7962.Leg7962(s.sig, s.tsMs, s.mint, s.isBuy, s.tokens, s.sol))
                }
            }
        } catch (_: Throwable) {}
        return if (whaleOnly.isEmpty()) watched else watched.filter { it !in whaleOnly }
    }

    const val CALLER_SWEEP_8361 = "sweep"
    const val CALLER_ALPHA_8361 = "alpha"

    // ── keyless JSON-RPC (whole answer, so array results such as getSignaturesForAddress work) ──

    /**
     * One market read down the keyless-first ladder (KeylessRpc8359 governors, labels and stale-slot check). Returns the
     * JSON-RPC root, or null when no rung answered. [keylessOnly] = never fall back to a paid rung.
     */
    private fun postRoot8361(http: OkHttpClient, method: String, params: JSONArray, keylessOnly: Boolean, caller: String, maxRungs: Int = 2): JSONObject? {
        val ladder0 = try { RuntimeProviderAuthority6685.marketReadCandidates8359(null, method) } catch (_: Throwable) { emptyList() }
        val ladder = if (keylessOnly) ladder0.filter { KeylessRpc8359.isKeyless8359(it) } else ladder0
        val body = JSONObject().put("jsonrpc", "2.0").put("id", "c8361").put("method", method).put("params", params).toString()
        var tried = 0
        for (url in ladder) {
            if (tried >= maxRungs) break
            val label = KeylessRpc8359.label8359(url)
            if (try { ApiBackoff.isLockedOut(label) } catch (_: Throwable) { false }) continue
            if (!KeylessRpc8359.beforeCall8359(url)) continue
            tried++
            if (!KeylessRpc8359.isKeyless8359(url) && url.contains("helius", ignoreCase = true)) {
                if (caller == CALLER_SWEEP_8361) sweepCredits.incrementAndGet() else alphaCredits.incrementAndGet()   // 1 credit a standard call
            }
            try {
                val req = Request.Builder().url(url).header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                val got: JSONObject? = HealthAwareHttp.execute(http, req, label).use { r ->
                    if (!r.isSuccessful) { KeylessRpc8359.afterCall8359(url, r.code); return@use null }
                    val j = try { JSONObject(r.body?.string().orEmpty()) } catch (_: Throwable) { null }
                    if (j == null) { KeylessRpc8359.afterCall8359(url, 0); return@use null }
                    val err = j.optJSONObject("error")
                    if (err != null) { KeylessRpc8359.afterCall8359(url, r.code, err.optInt("code", -1)); return@use null }
                    KeylessRpc8359.afterCall8359(url, r.code)
                    if (j.isNull("result")) null else j
                }
                if (got != null) return got
            } catch (_: Throwable) { KeylessRpc8359.afterCall8359(url, 0) }
        }
        return null
    }

    /** Signatures (newest first, failed ones skipped) for [address], keyless-first. */
    fun signatures8361(http: OkHttpClient, address: String, limit: Int, before: String?, keylessOnly: Boolean, caller: String): List<String> {
        val opts = JSONObject().put("limit", limit.coerceIn(1, 1000)).put("commitment", "confirmed")
        if (!before.isNullOrBlank()) opts.put("before", before)
        val arr = postRoot8361(http, "getSignaturesForAddress", JSONArray().put(address).put(opts), keylessOnly, caller)?.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.isNull("err")) continue
            val s = o.optString("signature", "")
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    /** One parsed transaction, keyless-first; null when no rung answered. */
    fun transaction8361(http: OkHttpClient, sig: String, keylessOnly: Boolean, caller: String): JSONObject? {
        val opts = JSONObject().put("encoding", "jsonParsed").put("maxSupportedTransactionVersion", 0).put("commitment", "confirmed")
        return postRoot8361(http, "getTransaction", JSONArray().put(sig).put(opts), keylessOnly, caller)?.optJSONObject("result")
    }

    // ── status ──

    private fun perHour(n: Long, nowMs: Long): String {
        val h = ((nowMs - startedAtMs).coerceAtLeast(60_000L)) / 3_600_000.0
        return "%.0f".format(n / h)
    }

    fun statusLine8361(nowMs: Long = System.currentTimeMillis()): String {
        val ago = if (lastSweepHeliusMs > 0L) "${(nowMs - lastSweepHeliusMs) / 60_000L}m" else "never"
        val rebuilt = try { ExpertWallets7962.alphaRebuiltAt8361() } catch (_: Throwable) { 0L }
        val rebuiltAgo = if (rebuilt > 0L) "${(nowMs - rebuilt) / 3_600_000L}h" else "never"
        val listed = try { ExpertWallets7962.alphaList8361().size } catch (_: Throwable) { 0 }
        val top = try { topLine8362(ExpertWallets7962.alphaRows8362(), 5) } catch (_: Throwable) { "-" }   // V5.0.8362
        val removed = try { com.lifecyclebot.perps.InsiderWalletTracker.removedCount8362() } catch (_: Throwable) { 0 }
        return "sweep8361[keylessEvery=45s heliusEvery=60m lastHelius=$ago runs=${sweepHeliusRuns.get()} notDue=${sweepSkippedNotDue.get()}] " +
            "alpha8361[list=$listed/$ALPHA_MAX_8361 rank=lcb rebuiltAgo=$rebuiltAgo watching=$watching buysSeen=${buysSeen.get()} sellsSeen=${sellsSeen.get()} copyFed=${copyFed.get()} top=$top] " +
            "whales8361[watched=${whales.size} removed=$removed bigBuys=${bigBuys.get()} bigSells=${bigSells.get()} droppedSmall=${droppedSmall.get()} scansSkipped=${scansSkipped.get()}]"
    }

    /** creditsByCaller8361 — Helius credits these three spent since start (paid fallbacks + Enhanced pages), per hour, against the pre-8361 ceilings. */
    fun creditsLine8361(nowMs: Long = System.currentTimeMillis()): String =
        "creditsByCaller8361{sweep=${perHour(sweepCredits.get(), nowMs)}/h(was<=480/h) alpha=${perHour(alphaCredits.get(), nowMs)}/h(was<=4872/h) " +
            "whales=0/h REST(was 1 holdings scan per push) stream=shared}"
}
