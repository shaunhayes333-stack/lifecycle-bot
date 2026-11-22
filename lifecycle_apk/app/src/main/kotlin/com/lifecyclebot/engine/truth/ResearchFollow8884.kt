package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8884 §EVERY_DECISION_IS_READ_AGAIN_AT_4H_AND_24H (plan W8) — owner: "there are 60,000% profit opportunities out there
 * literally every day" / "fuck 4x. I want it to look far far beyond that".
 *
 * 8877 writes every decision's 60-minute read as a research row. The coins that make a day's 100x are still running long
 * after the hour, and no record read them there. Now each decision that wrote an hour row is read again 4 hours and 24
 * hours after the decision, and each read is one more research row in the same file (its horizonMs says which): the
 * decision's own facts, then net and gross against the decision's own entry price.
 *
 * The read is the Jupiter price API (lite-api.jup.ag/price/v3, keyless, up to 50 mints a call; the endpoint the app's
 * other Jupiter reads use, through HealthAwareHttp's Jupiter backoff), priced in USD per token as the entry is (LabelMark8386 USD_PER_TOKEN). A coin Jupiter cannot
 * price at that horizon is written as such (net null, reason NO_PRICE_JUP_8884), never as a number: an unroutable coin is
 * usually a dead one, and the row says only that it could not be priced. The entry and the later read come from different
 * sources, so a row carries its source; peak and dip between the hour and the read are not known and are written null.
 *
 * Bounds: at most [MAX_PENDING_8884] decisions waiting (oldest dropped), at most [CALLS_PER_PASS_8884] calls a minute. The
 * waiting list survives restarts (its own file, rewritten every five minutes and on each pass that changed it). Measurement
 * only: no learner reads these rows.
 */
object ResearchFollow8884 {
    private const val FILE_8884 = "research_follow_8884.jsonl"
    internal val HORIZONS_8884 = longArrayOf(4L * 3_600_000L, 24L * 3_600_000L)
    private const val MAX_PENDING_8884 = 30_000
    private const val BATCH_8884 = 50
    private const val CALLS_PER_PASS_8884 = 4
    private const val LATE_GRACE_MS_8884 = 6L * 3_600_000L
    private const val JUP_8884 = "https://lite-api.jup.ag/price/v3?ids="

    /** One decision waiting for its next read ([next] = index into [HORIZONS_8884]). */
    internal data class Wait8884(
        val atMs: Long, val mint: String, val lane: String, val admitted: Boolean, val stage: String, val setup: String,
        val pred: Double, val v3: Double, val mcap: Double, val liq: Double, val cost: Double, val entryUsd: Double,
        val reason: String, val next: Int,
    )

    private val pending = java.util.ArrayDeque<Wait8884>()
    private val written = AtomicLong(0L)
    private val unpriced = AtomicLong(0L)
    private val late = AtomicLong(0L)
    private val failed = AtomicLong(0L)
    private val dropped = AtomicLong(0L)
    private val untradeable8923 = AtomicLong(0L)
    private val implausible8923 = AtomicLong(0L)

    /**
     * V5.0.8923 §A_PRICE_NO_POOL_CAN_HOLD_IS_NO_BASIS — owner, 22 Nov 00:31: "what about coins that ran 1000% plus?" On the
     * 20-22 Nov research rows 58 coins read 4x+ after the first decision; 15 of them were decided at a cap under $1.5k (as
     * low as $3-$80, pools $0-$9 — no live pump.fun curve quotes under ~$2k at SOL $70), and others read +73,000 % to
     * +192,000 % at 4 h / 24 h (a $2k coin at $400M): the decision's price was not the coin's, and the Jupiter re-read
     * against it booked a "run" that never happened. A decision on a pool under [MIN_POOL_USD_8923] (the bot's own minimum to
     * buy at all) is not followed; a re-read past [MAX_RATIO_8923] times the decision price is booked unpriced as an
     * implausible ratio, never as a return.
     */
    internal const val MIN_POOL_USD_8923 = 100.0
    internal const val MAX_RATIO_8923 = 100.0

    /** Pure: is a decision on a pool of [liqUsd] one a buyer could have taken (the pool is read and at least the minimum)? */
    fun tradeable8923(liqUsd: Double): Boolean = !liqUsd.isFinite() || liqUsd >= MIN_POOL_USD_8923

    /** Pure: is a re-read at [usd] against the decision's [entryUsd] a ratio a coin can have moved (at most [MAX_RATIO_8923]x)? */
    fun plausible8923(entryUsd: Double, usd: Double): Boolean =
        entryUsd.isFinite() && entryUsd > 0.0 && usd.isFinite() && usd > 0.0 && usd / entryUsd <= MAX_RATIO_8923
    @Volatile private var loaded = false
    @Volatile private var dirty = false
    @Volatile private var savedAtMs = 0L
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "research-follow-8884").apply { isDaemon = true } }
    @Volatile private var started = false

    private val http: OkHttpClient by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder()
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    }

    private fun file8884(): File? = try { com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir?.let { File(it, FILE_8884) } } catch (_: Throwable) { null }

    // ── pure ──

    /** Pure: (net, gross) % of a read at [usd] against [entryUsd] less the round trip [cost]; null when either price is unusable. */
    fun returns8884(entryUsd: Double, usd: Double, cost: Double): Pair<Double, Double>? {
        if (!entryUsd.isFinite() || entryUsd <= 0.0 || !usd.isFinite() || usd <= 0.0) return null
        val gross = (usd / entryUsd - 1.0) * 100.0
        return (gross - (if (cost.isFinite()) cost else 0.0)) to gross
    }

    /** Pure: the USD price of [mint] in a Jupiter price v3 answer ({"<mint>": {"usdPrice": x}}), or null. */
    fun priceOf8884(body: JSONObject?, mint: String): Double? =
        body?.optJSONObject(mint)?.optDouble("usdPrice", Double.NaN)?.takeIf { it.isFinite() && it > 0.0 }

    // V5.0.8945 — the 19:34 heap map: 30,039 waits held 9.5 MB, mostly their lane / stage / setup / reason strings, one copy
    // each though a few hundred distinct values repeat; each such word is now held once (bounded, a full pool lets a word be)
    private val words8945 = ConcurrentHashMap<String, String>()
    internal fun word8945(s: String): String = if (words8945.size >= 4_000) s else (words8945.putIfAbsent(s, s) ?: s)

    internal fun encode8884(w: Wait8884): String = JSONObject().put("t", w.atMs).put("m", w.mint).put("l", w.lane).put("a", w.admitted)
        .put("st", w.stage).put("su", w.setup).put("p", num(w.pred)).put("v", num(w.v3)).put("mc", num(w.mcap)).put("lq", num(w.liq))
        .put("c", num(w.cost)).put("e", w.entryUsd).put("r", w.reason).put("n", w.next).toString()

    private fun num(v: Double): Any = if (v.isFinite()) v else JSONObject.NULL

    internal fun decode8884(line: String): Wait8884? = try {
        val o = JSONObject(line)
        fun d(k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        val w = Wait8884(o.getLong("t"), o.getString("m"), word8945(o.optString("l")), o.optBoolean("a"), word8945(o.optString("st")),
            word8945(o.optString("su")), d("p"), d("v"), d("mc"), d("lq"), d("c"), o.getDouble("e"), word8945(o.optString("r")), o.optInt("n", 0))
        w.takeIf { it.mint.isNotBlank() && it.entryUsd > 0.0 && it.next in HORIZONS_8884.indices }
    } catch (_: Throwable) { null }

    // ── state ──

    /** ForwardReturnLabeler7731.bookTwoForty7944: the decision that just wrote its hour row waits for its 4 h and 24 h reads. */
    fun note8884(atMs: Long, mint: String, lane: String, admitted: Boolean, stage: String, setup: String, pred: Double, v3: Double,
                 mcap: Double, liq: Double, cost: Double, entryUsd: Double, reason: String) {
        if (mint.isBlank() || !entryUsd.isFinite() || entryUsd <= 0.0) return
        if (!tradeable8923(liq)) { untradeable8923.incrementAndGet(); return }   // V5.0.8923
        ensureStarted8884()
        val w = Wait8884(atMs, mint, word8945(lane), admitted, word8945(stage), word8945(setup), pred, v3, mcap, liq, cost, entryUsd, word8945(reason.take(80)), 0)
        synchronized(pending) {
            pending.addLast(w)
            while (pending.size > MAX_PENDING_8884) { pending.pollFirst(); dropped.incrementAndGet() }
            dirty = true
        }
    }

    @Synchronized private fun ensureStarted8884() {
        if (started) return
        started = true
        exec.scheduleWithFixedDelay({ try { pass8884(System.currentTimeMillis()) } catch (_: Throwable) { failed.incrementAndGet() } },
            60L, 60L, TimeUnit.SECONDS)
    }

    private fun load8884() {
        if (loaded) return
        loaded = true
        val f = file8884() ?: return
        if (!f.exists()) return
        val restored = ArrayList<Wait8884>()
        try { f.forEachLine { l -> decode8884(l)?.let { restored += it } } } catch (_: Throwable) { failed.incrementAndGet() }
        synchronized(pending) {
            val now = ArrayList(pending); pending.clear()
            (restored + now).takeLast(MAX_PENDING_8884).forEach { pending.addLast(it) }
        }
    }

    private fun save8884(nowMs: Long) {
        val f = file8884() ?: return
        val snapshot = synchronized(pending) { dirty = false; ArrayList(pending) }
        try {
            val tmp = File(f.parentFile, "$FILE_8884.tmp")
            tmp.bufferedWriter().use { out -> snapshot.forEach { out.write(encode8884(it)); out.write("\n") } }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            savedAtMs = nowMs
        } catch (_: Throwable) { failed.incrementAndGet() }
    }

    /** One pass: the due decisions (oldest first), read in batches of [BATCH_8884], each read written as a research row. */
    private fun pass8884(nowMs: Long) {
        load8884()
        val due = synchronized(pending) {
            pending.filter { nowMs - it.atMs >= HORIZONS_8884[it.next] }.take(BATCH_8884 * CALLS_PER_PASS_8884)
        }
        if (due.isNotEmpty()) {
            for (batch in due.chunked(BATCH_8884)) {
                val mints = batch.map { it.mint }.distinct()
                val body = fetch8884(mints) ?: continue   // a failed call leaves its decisions waiting for the next pass
                val done = HashSet<Wait8884>()
                val again = ArrayList<Wait8884>()
                for (w in batch) {
                    val horizon = HORIZONS_8884[w.next]
                    val tooLate = nowMs - w.atMs > horizon + LATE_GRACE_MS_8884
                    val usd = priceOf8884(body, w.mint)
                    val implausible = usd != null && !plausible8923(w.entryUsd, usd)   // V5.0.8923
                    if (implausible) implausible8923.incrementAndGet()
                    val r = usd?.takeIf { !implausible }?.let { returns8884(w.entryUsd, it, w.cost) }
                    val why = when {
                        tooLate -> "LATE_READ_8884:" + w.reason
                        implausible -> "IMPLAUSIBLE_RATIO_8923:" + w.reason
                        r == null -> "NO_PRICE_JUP_8884:" + w.reason
                        else -> "JUP_8884:" + w.reason
                    }
                    if (tooLate) late.incrementAndGet() else if (r == null) unpriced.incrementAndGet()
                    try {
                        ResearchRows8877.note8877(ResearchRows8877.line8877(w.atMs, horizon, w.mint, w.lane, w.admitted, w.stage, w.setup,
                            w.pred, w.v3, w.mcap, w.liq, w.cost, r?.first ?: Double.NaN, r?.second ?: Double.NaN,
                            Double.NaN, Double.NaN, Double.NaN, why), nowMs)
                        written.incrementAndGet()
                    } catch (_: Throwable) { failed.incrementAndGet() }
                    done += w
                    if (w.next + 1 in HORIZONS_8884.indices) again += w.copy(next = w.next + 1)
                }
                synchronized(pending) {
                    pending.removeIf { it in done }
                    again.forEach { pending.addLast(it) }
                    dirty = true
                }
            }
        }
        if (dirty && (due.isNotEmpty() || nowMs - savedAtMs >= 5L * 60_000L)) save8884(nowMs)
    }

    // V5.0.8934 — 22 Nov 04:08: waiting4h=15,213 waiting24h=7,918 written=0 failed=20 — every pass's call failed, so no 4 h or
    // 24 h read was ever written and no lane could be judged past the hour (BLUECHIP holds up to 8 h). Two causes: the batch
    // carried the crypto desks' non-Solana ids ("bsc|0x..."), and Price v3 refuses a whole batch with 400 for one such id
    // (KeylessPriceSources6996's 8418 note); and only the keyless lite host was asked (sr 63 % on 8542, 5xx while the bot's
    // other Jupiter reads ran). Now only Solana mints are asked (a non-Solana decision is written unpriced, as before), the
    // app's Jupiter key buys the keyed host first, and the keyless host stays the fallback (calls8934{} on the line).
    private val keyedOk8934 = AtomicLong(0L)
    private val keylessOk8934 = AtomicLong(0L)
    private val notSolana8934 = AtomicLong(0L)

    private fun fetch8884(mints: List<String>): JSONObject? {
        val ids = try { com.lifecyclebot.network.JupiterPriceIds8418.ids8418(mints, Int.MAX_VALUE) } catch (_: Throwable) { mints }
        notSolana8934.addAndGet((mints.size - ids.size).coerceAtLeast(0).toLong())
        if (ids.isEmpty()) return JSONObject()   // nothing Jupiter can price: the batch is written unpriced
        val key = try { com.lifecyclebot.data.DefaultKeys.JUPITER } catch (_: Throwable) { "" }
        if (key.isBlank()) why8945("jupiter_price_keyed", "NO_KEY")   // V5.0.8945
        if (key.isNotBlank()) read8934("https://api.jup.ag/price/v3?ids=" + ids.joinToString(","), "jupiter_price_keyed", key)?.let { keyedOk8934.incrementAndGet(); return it }
        read8934(JUP_8884 + ids.joinToString(","), "jupiter", "")?.let { keylessOk8934.incrementAndGet(); return it }
        failed.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RESEARCH_FOLLOW_8884_FETCH_FAILED") } catch (_: Throwable) {}
        return null
    }

    private fun read8934(url: String, host: String, key: String): JSONObject? = try {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").header("Accept", "application/json")
            .also { if (key.isNotBlank()) it.header("x-api-key", key) }.build()
        // the app's health-aware path for Jupiter (its backoff and host circuit), as the registry's Jupiter reads use
        com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, host).use { resp ->
            if (!resp.isSuccessful) { why8945(host, "HTTP_${resp.code}"); null }
            else resp.body?.string()?.let { JSONObject(it) } ?: run { why8945(host, "EMPTY_BODY"); null }
        }
    } catch (t: Throwable) { why8945(host, t.javaClass.simpleName.ifBlank { "EXCEPTION" }.take(32)); null }

    // V5.0.8945 — 19:34 capture: failed=68 with calls8934{keyed=0 keyless=0}: every 4 h / 24 h read failed on both hosts and
    // nothing said why (Jupiter's lite host is being rate-limited down to retirement, per its migration page). Each failed
    // read is now counted by host and cause (HTTP status, empty body, or the exception's class), and whether a key was set.
    private val why8945 = ConcurrentHashMap<String, AtomicLong>()
    private fun why8945(host: String, cause: String) {
        val k = "$host:$cause"
        (why8945[k] ?: if (why8945.size >= 16) why8945.computeIfAbsent("OTHER") { AtomicLong(0L) } else why8945.computeIfAbsent(k) { AtomicLong(0L) }).incrementAndGet()
    }

    /** Brief: decisions waiting per horizon, rows written, unpriced and late reads. */
    fun statusLine8884(): String {
        val (h4, h24) = synchronized(pending) { pending.count { it.next == 0 } to pending.count { it.next == 1 } }
        return "researchFollow8884[waiting4h=$h4 waiting24h=$h24 written=${written.get()} unpriced=${unpriced.get()} late=${late.get()} " +
            "failed=${failed.get()} dropped=${dropped.get()} untradeable8923=${untradeable8923.get()} implausible8923=${implausible8923.get()} " +
            "calls8934{keyed=${keyedOk8934.get()} keyless=${keylessOk8934.get()} notSolanaIds=${notSolana8934.get()}} " +
            "readFailed8945{${why8945.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}} rule=each hour row's decision read again at 4 h and 24 h (Jupiter price v3, USD per token), same file]"
    }

    internal fun resetForTest8884() { synchronized(pending) { pending.clear() }; loaded = true; written.set(0); unpriced.set(0); late.set(0); dropped.set(0) }
    internal fun pendingForTest8884(): List<Wait8884> = synchronized(pending) { ArrayList(pending) }
}
