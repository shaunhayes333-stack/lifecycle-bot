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
 * waiting list survives restarts (its own file, rewritten every five minutes and on each pass that changed it).
 * LanePocket8934 consumes valid rows at its declared horizon.
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
        /** V5.0.8983 — a lost-mark decision: its first read is the hour (then 4 h and 24 h as usual). */
        val hour8983: Boolean = false,
    ) {
        // V5.0.8973 — the path since the decision: the highest and lowest sampled USD price and when it was last sampled.
        // Outside the constructor, so equality (the pass's done set) never moves while a wait is held; copied by hand.
        @Volatile var hiUsd8973: Double = Double.NaN
        @Volatile var loUsd8973: Double = Double.NaN
        @Volatile var sampledAtMs8973: Long = 0L
        fun carry8973(to: Wait8884): Wait8884 { to.hiUsd8973 = hiUsd8973; to.loUsd8973 = loUsd8973; to.sampledAtMs8973 = sampledAtMs8973; return to }
        fun see8973(usd: Double, atMs: Long) {
            if (!(usd.isFinite() && usd > 0.0)) return
            if (!(hiUsd8973 >= usd)) hiUsd8973 = usd
            if (!(loUsd8973 <= usd)) loUsd8973 = usd
            sampledAtMs8973 = atMs
        }
    }

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

    // ── V5.0.8983 §A_DEAD_COIN_IS_A_ROW (ResearchRows8877) ──
    internal const val HOUR_MS_8983 = 3_600_000L
    internal const val LOST_TAG_8983 = "LOSTMARK8983/"
    private val lostHourNoted8983 = AtomicLong(0L)
    private val lostHourPriced8983 = AtomicLong(0L)
    private val lostHourUnpriced8983 = AtomicLong(0L)

    /** Pure: the horizon a wait is read at next: the hour for a lost mark's first read, else its 4 h / 24 h slot. */
    internal fun horizonOf8983(w: Wait8884): Long = if (w.hour8983) HOUR_MS_8983 else HORIZONS_8884[w.next]

    /**
     * ForwardReturnLabeler7731: a decision whose mark was lost (no hour row) is read at the hour by Jupiter, then at 4 h and
     * 24 h. A coin Jupiter prices writes its real hour row; one it cannot price is asked of the chain once (8993), then writes NO_PRICE (unknown, 8992).
     */
    fun noteLostHour8983(atMs: Long, mint: String, lane: String, admitted: Boolean, stage: String, setup: String, pred: Double, v3: Double,
                         mcap: Double, liq: Double, cost: Double, entryUsd: Double, reason: String) {
        lostHourNoted8983.incrementAndGet()
        note8884(atMs, mint, lane, admitted, stage, setup, pred, v3, mcap, liq, cost, entryUsd, reason, hour8983 = true)
    }

    /** Brief: the lost marks sent to the hour read and what the read found. */
    internal fun lostHourLine8983(): String = "lostHour8983{noted=${lostHourNoted8983.get()} priced=${lostHourPriced8983.get()} " +
        "unpriced=${lostHourUnpriced8983.get()}}"

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
        .put("c", num(w.cost)).put("e", w.entryUsd).put("r", w.reason).put("n", w.next)
        .put("hi", num(w.hiUsd8973)).put("lo", num(w.loUsd8973)).put("ps", w.sampledAtMs8973).put("h1", w.hour8983).toString()

    private fun num(v: Double): Any = if (v.isFinite()) v else JSONObject.NULL

    internal fun decode8884(line: String): Wait8884? = try {
        val o = JSONObject(line)
        fun d(k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        val w = Wait8884(o.getLong("t"), o.getString("m"), word8945(o.optString("l")), o.optBoolean("a"), word8945(o.optString("st")),
            word8945(o.optString("su")), d("p"), d("v"), d("mc"), d("lq"), d("c"), o.getDouble("e"), word8945(o.optString("r")), o.optInt("n", 0),
            hour8983 = o.optBoolean("h1", false))   // V5.0.8983 (absent before: false)
        w.hiUsd8973 = d("hi"); w.loUsd8973 = d("lo"); w.sampledAtMs8973 = o.optLong("ps", 0L)   // V5.0.8973 (absent before: NaN / 0)
        w.takeIf { it.mint.isNotBlank() && it.entryUsd > 0.0 && it.next in HORIZONS_8884.indices }
    } catch (_: Throwable) { null }

    // ── state ──

    /** ForwardReturnLabeler7731.bookTwoForty7944: the decision that just wrote its hour row waits for its 4 h and 24 h reads. */
    fun note8884(atMs: Long, mint: String, lane: String, admitted: Boolean, stage: String, setup: String, pred: Double, v3: Double,
                 mcap: Double, liq: Double, cost: Double, entryUsd: Double, reason: String,
                 peakPct8973: Double = Double.NaN, minPct8973: Double = Double.NaN, hour8983: Boolean = false) {
        if (mint.isBlank() || !entryUsd.isFinite() || entryUsd <= 0.0) return
        if (!tradeable8923(liq)) { untradeable8923.incrementAndGet(); return }   // V5.0.8923
        ensureStarted8884()
        val w = Wait8884(atMs, mint, word8945(lane), admitted, word8945(stage), word8945(setup), pred, v3, mcap, liq, cost, entryUsd,
            word8945(if (hour8983) LOST_TAG_8983 + reason.take(64) else reason.take(80)), 0, hour8983)
        // V5.0.8973 — the labeler's own high and low since the decision seed the path (gross % -> USD)
        if (peakPct8973.isFinite()) w.see8973(entryUsd * (1.0 + peakPct8973 / 100.0), System.currentTimeMillis())
        if (minPct8973.isFinite()) w.see8973(entryUsd * (1.0 + minPct8973 / 100.0), System.currentTimeMillis())
        if (labMode8978) { buffer8978(encode8884(w)); return }   // V5.0.8978 — the lab holds the waits, not this process
        synchronized(pending) {
            pending.addLast(w)
            while (pending.size > MAX_PENDING_8884) { pending.pollFirst(); dropped.incrementAndGet() }
            dirty = true
        }
    }

    /** Start at the bot heartbeat as well as on new work: persisted waits must not need a new hour outcome to resume. */
    @Synchronized fun ensureStarted8884() {
        if (started || labRole8978) return
        started = true
        // V5.0.8978 — in the app, the lab process follows; the trading process sends the waits and takes the rows back
        labMode8978 = labWanted8978()
        if (labMode8978) {
            labSinceMs8978 = System.currentTimeMillis()
            try { dir8978()?.let { File(it, OWNER_TRADER_8978).delete() } } catch (_: Throwable) {}
            try { exec.execute { ping8978() } } catch (_: Throwable) {}
        }
        exec.scheduleWithFixedDelay({ try { tick8978(System.currentTimeMillis()) } catch (_: Throwable) { failed.incrementAndGet() } },
            60L, 60L, TimeUnit.SECONDS)
    }

    private fun load8884() {
        if (loaded) return
        val f = file8884() ?: return
        loaded = true
        if (!f.exists()) return
        val restored = ArrayList<Wait8884>()
        try { f.forEachLine { l -> decode8884(l)?.let { restored += it } } } catch (_: Throwable) { failed.incrementAndGet() }
        synchronized(pending) {
            val now = ArrayList(pending); pending.clear()
            (restored + now).takeLast(MAX_PENDING_8884).forEach { pending.addLast(it) }
        }
    }

    private fun save8884(nowMs: Long) {
        if (labRole8978 && traderOwns8978()) return   // V5.0.8978 — the trading process took the follow back: its file now
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
        try { samplePaths8973(nowMs) } catch (_: Throwable) { failed.incrementAndGet() }
        val due = synchronized(pending) {
            pending.filter { nowMs - it.atMs >= horizonOf8983(it) }.take(BATCH_8884 * CALLS_PER_PASS_8884)
        }
        if (due.isNotEmpty()) {
            for (batch in due.chunked(BATCH_8884)) {
                val mints = batch.filter { nowMs - it.atMs <= horizonOf8983(it) + LATE_GRACE_MS_8884 }.map { it.mint }.distinct()
                // Expired horizons advance even during an outage, so they cannot monopolise every batch forever.
                val body = if (mints.isEmpty()) JSONObject() else fetch8884(mints)
                // V5.0.9015 — what Jupiter did not price (or did not answer for) is asked of DexScreener, in a pool a holder could sell into
                val dex9015 = try { dexPrices9015(mints.filter { priceOf8884(body, it) == null }) } catch (_: Throwable) { emptyMap() }
                val done = HashSet<Wait8884>()
                val again = ArrayList<Wait8884>()
                for (w in batch) {
                    val horizon = horizonOf8983(w)
                    val tooLate = nowMs - w.atMs > horizon + LATE_GRACE_MS_8884
                    if (!tooLate && body == null && w.mint !in dex9015) continue   // a timely unanswered read stays pending
                    val jup9015 = priceOf8884(body, w.mint)
                    val usd = jup9015 ?: dex9015[w.mint]
                    val implausible = usd != null && !plausible8923(w.entryUsd, usd)   // V5.0.8923
                    if (implausible) implausible8923.incrementAndGet()
                    // A late mark is not a return at the requested horizon. Never feed it to a horizon learner.
                    val r = usd?.takeIf { !implausible && !tooLate }?.let { returns8884(w.entryUsd, it, w.cost) }
                    // V5.0.8973 — the read price is a point of the path too; the row carries the path's high and low (gross %)
                    if (usd != null && !implausible && !tooLate) w.see8973(usd, nowMs)
                    val (peak8973, low8973) = pathPct8973(w.entryUsd, w.hiUsd8973, w.loUsd8973)
                    val why = when {
                        tooLate -> "LATE_READ_8884:" + w.reason
                        implausible -> "IMPLAUSIBLE_RATIO_8923:" + w.reason
                        r == null -> "NO_PRICE_JUP_8884:" + w.reason
                        else -> (if (jup9015 != null) "JUP_8884:" else DEX_PREFIX_9015) + w.reason
                    }
                    if (tooLate) late.incrementAndGet() else if (r == null) unpriced.incrementAndGet()
                    if (w.hour8983 && !tooLate) (if (r == null) lostHourUnpriced8983 else lostHourPriced8983).incrementAndGet()
                    try {
                        val row8993 = ResearchRows8877.line8877(w.atMs, horizon, w.mint, w.lane, w.admitted, w.stage, w.setup,
                            w.pred, w.v3, w.mcap, w.liq, w.cost, r?.first ?: Double.NaN, r?.second ?: Double.NaN,
                            if (r != null) peak8973 else Double.NaN, if (r != null) peak8973 else Double.NaN,
                            if (r != null) low8973 else Double.NaN, why)
                        // V5.0.8993 — a read Jupiter could not price carries what the chain's answer needs to finish it
                        emit8978(if (r == null && !tooLate && !implausible) carry8993(row8993, w.entryUsd, peak8973, low8973) else row8993, nowMs)
                        written.incrementAndGet()
                    } catch (_: Throwable) { failed.incrementAndGet() }
                    done += w
                    // V5.0.8983 — a lost mark's hour read is followed by its 4 h and 24 h reads like every other decision
                    if (w.hour8983) again += w.carry8973(w.copy(hour8983 = false))
                    else if (w.next + 1 in HORIZONS_8884.indices) again += w.carry8973(w.copy(next = w.next + 1))
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

    /**
     * V5.0.8973 §THE_PATH_NOT_JUST_THE_END — owner, 24 Nov 02:50: the established and crypto lanes need their setups rebuilt.
     * Their coins move over hours, not minutes, and the 4 h / 24 h reads recorded only the end price (peakPct / minPct null on
     * every such row), so no stop, target or trail could be graded where they trade: the joined 24 Nov research could grade
     * exits only at the hour, where in-band QUALITY / BLUECHIP sat at their round trip (-2.9 / -2.6 % medians). Every waiting
     * decision's coin is now priced every [SAMPLE_GAP_MS_8973] (one Jupiter batch per [BATCH_8884] distinct coins, the
     * longest-unsampled first, at most [SAMPLE_CALLS_8973] calls a pass), and the 4 h / 24 h row carries the path's high and
     * low (gross % from the decision's price; heldPeakPct = the sampled high). The path is sampled, not tick-exact: a move
     * inside 15 minutes is not seen, so a stop is graded on the sampled lows (stated, not hidden).
     */
    internal const val SAMPLE_GAP_MS_8973 = 15L * 60_000L
    internal const val SAMPLE_CALLS_8973 = 6
    private val sampled8973 = AtomicLong(0L)

    /** Pure: the path's high and low as gross % from [entryUsd]; NaN when unsampled. */
    internal fun pathPct8973(entryUsd: Double, hiUsd: Double, loUsd: Double): Pair<Double, Double> {
        if (!(entryUsd.isFinite() && entryUsd > 0.0)) return Double.NaN to Double.NaN
        val hi = if (hiUsd.isFinite() && hiUsd > 0.0) (hiUsd / entryUsd - 1.0) * 100.0 else Double.NaN
        val lo = if (loUsd.isFinite() && loUsd > 0.0) (loUsd / entryUsd - 1.0) * 100.0 else Double.NaN
        return hi to lo
    }

    private fun samplePaths8973(nowMs: Long) {
        val byMint = LinkedHashMap<String, MutableList<Wait8884>>()
        synchronized(pending) {
            pending.filter { nowMs - it.sampledAtMs8973 >= SAMPLE_GAP_MS_8973 && nowMs - it.atMs < HORIZONS_8884.last() }
                .sortedBy { it.sampledAtMs8973 }
                .forEach { if (byMint.size < BATCH_8884 * SAMPLE_CALLS_8973 || byMint.containsKey(it.mint)) byMint.getOrPut(it.mint) { ArrayList() }.add(it) }
        }
        if (byMint.isEmpty()) return
        for (chunk in byMint.keys.toList().chunked(BATCH_8884)) {
            val body = fetch8884(chunk) ?: continue
            for (m in chunk) {
                val usd = priceOf8884(body, m)
                for (w in byMint[m].orEmpty()) {
                    if (usd != null && plausible8923(w.entryUsd, usd)) w.see8973(usd, nowMs) else w.sampledAtMs8973 = nowMs
                }
                if (usd != null) sampled8973.incrementAndGet()
            }
        }
        dirty = true
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

    /**
     * V5.0.9015 §A_SECOND_FREE_PRICE_FOR_THE_READS_JUPITER_CANNOT_GIVE — owner, 25 Nov 20:13: "we can read prices for free
     * from multiple other sources tho. dex is great barely ever fails and covers millions." The 4 h and 24 h reads (and an
     * hour read the labeler lost) asked ONE source, Jupiter's price API: the 22-25 Nov research export has 21.0 % of 4 h
     * rows and 14.7 % of 24 h rows unpriced (NO_PRICE_JUP_8884 3,974 + 2,537), and the 18:23 capture shows that source
     * failing outright (HTTP 599 x12, 503 x10, 429 x2), which left timely reads pending until they went late (LATE_READ
     * 2,865 + 1,641). The labeler's own 15-minute marks already read DexScreener's batch endpoint (/tokens/v1/solana, 30
     * mints a call, keyless); this follow never did.
     *
     * A coin Jupiter did not price, or did not answer for, is now asked of that same endpoint. Its price counts only when
     * the pool it came from holds [MIN_POOL_USD_8923]+ of liquidity (the row's own tradeable bar): 8884 wrote an unroutable
     * coin as unpriced because it is usually a dead one, and a last print in an empty pool is not a price a holder gets, so
     * that stays unpriced. A row priced this way says so (reason [DEX_PREFIX_9015]); the plausibility check (8923), the
     * late rule and the chain re-read of what is still unpriced (8993) are unchanged. Solana mints only.
     */
    internal const val DEX_PREFIX_9015 = "DEX_9015:"
    private const val DEX_SLOT_WAIT_MS_9015 = 1_500L
    private val dexAsked9015 = AtomicLong(); private val dexPriced9015 = AtomicLong(); private val dexThin9015 = AtomicLong()

    /** Pure: does a DexScreener price from a pool of [poolLiqUsd] count as the read? */
    internal fun dexUsable9015(priceUsd: Double?, poolLiqUsd: Double?): Boolean =
        priceUsd != null && priceUsd.isFinite() && priceUsd > 0.0 && poolLiqUsd != null && poolLiqUsd.isFinite() && poolLiqUsd >= MIN_POOL_USD_8923

    private fun dexPrices9015(missing: List<String>): Map<String, Double> {
        val ids = (try { com.lifecyclebot.network.JupiterPriceIds8418.ids8418(missing, Int.MAX_VALUE) } catch (_: Throwable) { missing }).distinct()
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, Double>()
        for (chunk in ids.chunked(30)) {
            dexAsked9015.addAndGet(chunk.size.toLong())
            val r = (try { com.lifecyclebot.network.DexscreenerApi().batchPriceFetchTyped8087(chunk, DEX_SLOT_WAIT_MS_9015, heldMarkTraffic = false) } catch (_: Throwable) { null }) ?: continue
            for ((m, px) in r.prices) {
                // the liquidity of the very pool the price came from (the same answer's rows)
                val liq = try { com.lifecyclebot.network.DexPools8285.poolsOf8285(m).filter { it.priceUsd == px }.maxOfOrNull { it.liquidityUsd } } catch (_: Throwable) { null }
                if (dexUsable9015(px, liq)) out[m] = px else dexThin9015.incrementAndGet()
            }
        }
        dexPriced9015.addAndGet(out.size.toLong())
        return out
    }
    fun dexLine9015(): String = "dexSecondPrice9015[asked=${dexAsked9015.get()} priced=${dexPriced9015.get()} thinPool=${dexThin9015.get()}]"

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
        if (labMode8978) {   // V5.0.8978 — the lab's own line (it writes it each pass), then this process's channel
            val lab = try { dir8978()?.let { File(it, STATUS_8978) }?.takeIf { it.exists() }?.readText()?.trim() } catch (_: Throwable) { null }
            return (lab?.takeIf { it.isNotBlank() } ?: "researchFollow8884[lab not reporting yet]") + " " + channelLine8978() + " " + chainLine8993()
        }
        return ownLine8884() + " " + channelLine8978() + " " + chainLine8993()
    }

    private fun ownLine8884(): String {
        val (h4, h24) = synchronized(pending) { pending.count { it.next == 0 } to pending.count { it.next == 1 } }
        return "researchFollow8884[waiting4h=$h4 waiting24h=$h24 written=${written.get()} unpriced=${unpriced.get()} late=${late.get()} " +
            "failed=${failed.get()} dropped=${dropped.get()} untradeable8923=${untradeable8923.get()} implausible8923=${implausible8923.get()} " +
            "calls8934{keyed=${keyedOk8934.get()} keyless=${keylessOk8934.get()} notSolanaIds=${notSolana8934.get()}} " +
            "pathSampled8973=${sampled8973.get()} ${lostHourLine8983()} ${dexLine9015()} readFailed8945{${why8945.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}} rule=each hour row's decision read again at 4 h and 24 h (Jupiter price v3, USD per token), its path sampled every 15 min (8973), same file]"
    }

    // ── V5.0.8978 §THE_FOLLOW_RUNS_IN_THE_LAB ──
    // Owner, 24 Nov 04:20: "we need to move things off the main stream that dont need to be on main stream". The follow-up is
    // research: it decides no trade. It held up to 30,000 waits (9.5 MB, 19:34 heap map), rewrote all of them as JSON about
    // once a minute (a pass that read anything or sampled a path marks the list dirty) and parsed every Jupiter answer, all in
    // the trading process. Now LabProcess8906's process (android:process=":lab8906", its own heap and its own garbage
    // collector) holds the waits, reads Jupiter and writes the rows; the trading process keeps only what it must:
    //  - a decision's wait is one line in a small buffer, written each minute as one file (lab8978/follow_in_*.rows, written
    //    whole then renamed, so the lab never reads half a file);
    //  - the lab answers with the finished research rows (lab8978/follow_out_*.rows) and the trading process files each one
    //    through ResearchRows8877.note8877 exactly as before, so the pockets, the authority table and the research file see
    //    the same rows; a row file is deleted once read (a row is filed at most once);
    //  - each minute the trading process asks Android to keep the lab's follow running (a killed lab restarts on the next ask;
    //    the waits are in the lab's file, as they were in this process's);
    //  - if the lab has not reported for [FALLBACK_MS_8978] (it cannot start, or keeps dying) this process takes the follow
    //    back for the rest of the run: it marks itself owner (the lab then stops writing), loads the waits the lab saved and
    //    the ones not yet sent, and follows here exactly as before 8978. Unit tests (no app) follow here as before.
    internal const val LAB_DIR_8978 = "lab8978"
    internal const val IN_PREFIX_8978 = "follow_in_"
    internal const val OUT_PREFIX_8978 = "follow_out_"
    internal const val ROWS_SUFFIX_8978 = ".rows"
    private const val STATUS_8978 = "follow_status_8978.txt"
    private const val OWNER_TRADER_8978 = "follow_owner_trader_8978"
    internal const val FALLBACK_MS_8978 = 10L * 60_000L
    private const val MAX_BUFFER_8978 = 5_000
    private const val MAX_OUT_8978 = 20_000

    @Volatile private var labMode8978 = false   // the trading process: the lab follows
    @Volatile private var labRole8978 = false   // the lab process: this process follows
    @Volatile private var labSinceMs8978 = 0L
    private val inBuffer8978 = ArrayList<String>()
    private val outBuffer8978 = ArrayList<String>()
    private val seq8978 = AtomicLong(0L)
    private val sentIn8978 = AtomicLong(0L)
    private val bufferDropped8978 = AtomicLong(0L)
    private val rowsBack8978 = AtomicLong(0L)
    private val rowsOut8978 = AtomicLong(0L)
    private val fellBack8978 = AtomicLong(0L)
    private val pingFailed8978 = AtomicLong(0L)
    private val channelFailed8978 = AtomicLong(0L)

    private fun dir8978(): File? = try {
        com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir?.let { File(it, LAB_DIR_8978).apply { mkdirs() } }
    } catch (_: Throwable) { null }

    private fun labWanted8978(): Boolean = try {
        val ctx = com.lifecyclebot.AATEApp.appContextOrNull()
        ctx != null && !com.lifecyclebot.engine.LabProcess8906.isLabProcess8906(ctx)
    } catch (_: Throwable) { false }

    private fun traderOwns8978(): Boolean = try { dir8978()?.let { File(it, OWNER_TRADER_8978).exists() } == true } catch (_: Throwable) { false }

    /** Pure: write [lines] as one file "<prefix><ms>_<seq>.rows" in [dir] (whole, then renamed). */
    internal fun writeAtomic8978(dir: File, prefix: String, lines: List<String>, nowMs: Long, seq: Long): Boolean = try {
        val name = prefix + nowMs + "_" + seq.toString().padStart(9, '0') + ROWS_SUFFIX_8978
        val tmp = File(dir, "$name.tmp")
        tmp.bufferedWriter().use { out -> for (l in lines) { out.write(l); out.write("\n") } }
        val dest = File(dir, name)
        tmp.renameTo(dest) || run { tmp.delete(); false }
    } catch (_: Throwable) { false }

    /** Pure: every finished "<prefix>*.rows" file in [dir], oldest first, read and deleted; their lines in order. */
    internal fun takeFiles8978(dir: File, prefix: String): List<String> {
        val files = (dir.listFiles() ?: return emptyList()).filter { it.name.startsWith(prefix) && it.name.endsWith(ROWS_SUFFIX_8978) }
            .sortedBy { it.name }
        val out = ArrayList<String>()
        for (f in files) {
            val lines = try { f.readLines() } catch (_: Throwable) { continue }
            if (!f.delete()) continue   // a file that cannot be deleted is not filed (it would be filed twice)
            for (l in lines) if (l.isNotBlank()) out.add(l)
        }
        return out
    }

    /** Pure: has the lab been silent too long — nothing written since the later of the ask and its last report? */
    internal fun fellSilent8978(nowMs: Long, sinceMs: Long, reportedAtMs: Long): Boolean = nowMs - maxOf(sinceMs, reportedAtMs) > FALLBACK_MS_8978

    private fun buffer8978(line: String) {
        synchronized(inBuffer8978) {
            inBuffer8978.add(line)
            while (inBuffer8978.size > MAX_BUFFER_8978) { inBuffer8978.removeAt(0); bufferDropped8978.incrementAndGet() }
        }
    }

    /** One research row: filed here (the trading process), or held for the trading process (the lab). */
    private fun emit8978(line: String, nowMs: Long) {
        if (!labRole8978) { file8993(line, nowMs); return }
        synchronized(outBuffer8978) {
            outBuffer8978.add(line)
            while (outBuffer8978.size > MAX_OUT_8978) { outBuffer8978.removeAt(0); channelFailed8978.incrementAndGet() }
        }
    }

    private fun ping8978() {
        val ok = try { com.lifecyclebot.AATEApp.appContextOrNull()?.let { com.lifecyclebot.engine.LabProcess8906.pingFollow8978(it) } == true } catch (_: Throwable) { false }
        if (!ok) pingFailed8978.incrementAndGet()
    }

    /** The trading process, each minute: file the lab's rows; send the waits; keep the lab following, or follow here. */
    private fun tick8978(nowMs: Long) {
        dir8978()?.let { dir ->
            for (line in takeFiles8978(dir, OUT_PREFIX_8978)) {
                try { file8993(line, nowMs); rowsBack8978.incrementAndGet() } catch (_: Throwable) { failed.incrementAndGet() }
            }
        }
        try { settle8993(nowMs) } catch (_: Throwable) { failed.incrementAndGet() }   // V5.0.8993 — the chain's answers to last minute's asks
        if (labMode8978) {
            val dir = dir8978()
            val lines = synchronized(inBuffer8978) { ArrayList(inBuffer8978).also { inBuffer8978.clear() } }
            if (lines.isNotEmpty()) {
                if (dir != null && writeAtomic8978(dir, IN_PREFIX_8978, lines, nowMs, seq8978.incrementAndGet())) sentIn8978.addAndGet(lines.size.toLong())
                else { channelFailed8978.incrementAndGet(); synchronized(inBuffer8978) { inBuffer8978.addAll(0, lines) } }
            }
            val reported = try { dir?.let { File(it, STATUS_8978).lastModified() } ?: 0L } catch (_: Throwable) { 0L }
            if (!fellSilent8978(nowMs, labSinceMs8978, reported)) { ping8978(); return }
            fallBack8978(nowMs)
        }
        // a wait buffered while this process was taking the follow back is followed here too
        ingest8978(synchronized(inBuffer8978) { ArrayList(inBuffer8978).also { inBuffer8978.clear() } })
        pass8884(nowMs)
    }

    /** The lab never reported: this process follows for the rest of the run, from the lab's saved waits and the unsent ones. */
    private fun fallBack8978(nowMs: Long) {
        labMode8978 = false
        fellBack8978.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RESEARCH_FOLLOW_LAB_FALLBACK_8978") } catch (_: Throwable) {}
        val dir = dir8978()
        try { dir?.let { File(it, OWNER_TRADER_8978).writeText(nowMs.toString()) } } catch (_: Throwable) {}
        load8884()
        val lines = (dir?.let { takeFiles8978(it, IN_PREFIX_8978) } ?: emptyList()) +
            synchronized(inBuffer8978) { ArrayList(inBuffer8978).also { inBuffer8978.clear() } }
        ingest8978(lines)
    }

    private fun ingest8978(lines: List<String>) {
        if (lines.isEmpty()) return
        val ws = lines.mapNotNull { decode8884(it) }
        synchronized(pending) {
            for (w in ws) pending.addLast(w)
            while (pending.size > MAX_PENDING_8884) { pending.pollFirst(); dropped.incrementAndGet() }
            dirty = true
        }
    }

    /** The lab process (LabProcess8906, kind FOLLOW): start following here. Idempotent. */
    @Synchronized fun startInLab8978() {
        if (labRole8978 || started) return
        labRole8978 = true
        exec.scheduleWithFixedDelay({ try { labPass8978(System.currentTimeMillis()) } catch (_: Throwable) { failed.incrementAndGet() } },
            0L, 60L, TimeUnit.SECONDS)
    }

    /** Is this process the lab's follower? (LabProcess8906 keeps its service started while it is.) */
    fun labFollowing8978(): Boolean = labRole8978

    private fun labPass8978(nowMs: Long) {
        val dir = dir8978() ?: return
        if (traderOwns8978()) return   // the trading process took the follow back for this run
        load8884()
        ingest8978(takeFiles8978(dir, IN_PREFIX_8978))
        pass8884(nowMs)
        val rows = synchronized(outBuffer8978) { ArrayList(outBuffer8978).also { outBuffer8978.clear() } }
        if (rows.isNotEmpty()) {
            if (writeAtomic8978(dir, OUT_PREFIX_8978, rows, nowMs, seq8978.incrementAndGet())) rowsOut8978.addAndGet(rows.size.toLong())
            else { channelFailed8978.incrementAndGet(); synchronized(outBuffer8978) { outBuffer8978.addAll(0, rows) } }
        }
        try {
            val tmp = File(dir, "$STATUS_8978.tmp"); tmp.writeText(ownLine8884() + " " + channelLine8978())
            if (!tmp.renameTo(File(dir, STATUS_8978))) tmp.delete()
        } catch (_: Throwable) {}
    }

    /** Brief: where the follow runs and what crossed between the two processes. */
    internal fun channelLine8978(): String =
        "followLab8978[where=${when { labRole8978 -> "labProcess"; labMode8978 -> "lab"; fellBack8978.get() > 0 -> "here(fellBack)"; else -> "here" }} " +
            "sentIn=${sentIn8978.get()} buffered=${synchronized(inBuffer8978) { inBuffer8978.size }} bufferDropped=${bufferDropped8978.get()} " +
            "rowsOut=${rowsOut8978.get()} rowsBack=${rowsBack8978.get()} fellBack=${fellBack8978.get()} pingFailed=${pingFailed8978.get()} " +
            "channelFailed=${channelFailed8978.get()}]"

    /**
     * V5.0.8993 §THE_CHAIN_PRICES_WHAT_JUPITER_CANNOT — owner, 24 Nov 23:43: "we have helius and other sources we can reprice
     * off. a one time chain confirmation when needed is authorised". The 23:24 research file: Jupiter could not price about
     * 79 % of the pockets' own cohort (off-pump, v3 >= 35) at the read, about 9 % elsewhere; since 8992 such a read is
     * unknown, which leaves a pocket proving on the coins Jupiter happens to cover. A coin's price is on the chain whatever
     * an aggregator lists: its pump.fun curve account, its pool's vaults, its concentrated pool's state.
     * So a row Jupiter left unpriced is held by the trading process (which knows the coin's pools; the lab does not), the
     * chain is asked once for that coin ([ChainConfirm8950.confirmMint8950], use RESEARCH: the curve or the pool read, on
     * the same credit economy as every other ask), and at the next minute's tick the answer finishes the row
     * ([CHAIN_PREFIX_8993], net and gross from the chain price against the decision's price, less the same cost). A coin the
     * chain cannot answer inside [WAIT_MS_8993] is written unpriced, as before. One ask per read, never a retry loop.
     * Stated: the chain read lands up to two minutes after the horizon; a held row lives in memory (a process death inside
     * those two minutes loses it); the 15-minute path samples are still Jupiter's.
     */
    internal const val CHAIN_PREFIX_8993 = "CHAIN_8993:"
    private const val UNPRICED_PREFIX_8993 = "NO_PRICE_JUP_8884:"
    internal const val WAIT_MS_8993 = 110_000L
    private const val CHAIN_AGE_MS_8993 = 180_000L
    private const val MAX_PARKED_8993 = 2_000
    private class Parked8993(val line: String, val mint: String, val atMs: Long)
    private val parked8993 = ArrayList<Parked8993>()
    private val chainAsked8993 = AtomicLong(0L)
    private val chainPriced8993 = AtomicLong(0L)
    private val chainUnpriced8993 = AtomicLong(0L)
    private val chainImplausible8993 = AtomicLong(0L)
    private val chainOverflow8993 = AtomicLong(0L)

    /** Pure: [row] (an unpriced read) with the decision's USD price and its sampled path (gross %), for the chain's answer. */
    internal fun carry8993(row: String, entryUsd: Double, peakPct: Double, lowPct: Double): String {
        if (!row.endsWith("}") || !(entryUsd.isFinite() && entryUsd > 0.0)) return row
        fun n(v: Double) = if (v.isFinite()) "%.9g".format(java.util.Locale.US, v) else "null"
        return row.dropLast(1) + ",\"e8993\":${n(entryUsd)},\"pk8993\":${n(peakPct)},\"lo8993\":${n(lowPct)}}"
    }

    /** Pure: is [line] an unpriced read the chain can still finish (it carries the decision's price)? */
    internal fun wantsChain8993(line: String): Boolean =
        line.contains("\"reason\":\"$UNPRICED_PREFIX_8993") && line.contains("\"e8993\":") && !line.contains("\"e8993\":null")

    /** Pure: [line] finished with the chain's [usd]; null when the price is unusable or the ratio is not one a coin can have moved. */
    internal fun finish8993(line: String, usd: Double): String? {
        val o = try { JSONObject(line) } catch (_: Throwable) { return null }
        fun d(k: String) = if (o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
        val entry = d("e8993")
        if (!plausible8923(entry, usd)) return null
        val r = returns8884(entry, usd, d("cost")) ?: return null
        val peak = listOf(d("pk8993"), r.second).filter { it.isFinite() }.maxOrNull() ?: r.second
        val low = listOf(d("lo8993"), r.second).filter { it.isFinite() }.minOrNull() ?: r.second
        return ResearchRows8877.line8877(o.optLong("t", 0L), o.optLong("horizonMs", 0L), o.optString("mint"), o.optString("lane"),
            o.optBoolean("admitted"), o.optString("stage"), o.optString("setup"), d("pred"), d("v3"), d("mcap"), d("liq"), d("cost"),
            r.first, r.second, peak, peak, low, CHAIN_PREFIX_8993 + o.optString("reason").removePrefix(UNPRICED_PREFIX_8993))
    }

    /** [mint]'s USD price as the chain last answered inside [CHAIN_AGE_MS_8993]: its pool (vaults or state), else its active curve. */
    private fun chainUsd8993(mint: String, nowMs: Long): Double {
        val pool = try { OnChainDepth8038.chainPriceUsd8540(mint, CHAIN_AGE_MS_8993, nowMs) } catch (_: Throwable) { Double.NaN }
        if (pool.isFinite() && pool > 0.0) return pool
        val sol = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val curve = try { CurveReserves7951.priceSolFor7951(mint, nowMs, CHAIN_AGE_MS_8993) } catch (_: Throwable) { 0.0 }
        return if (curve.isFinite() && curve > 0.0 && sol.isFinite() && sol > 0.0) curve * sol else Double.NaN
    }

    private fun askChain8993(mint: String, nowMs: Long) {
        chainAsked8993.incrementAndGet()
        try { ChainConfirm8950.confirmMint8950(mint, listOf("PRICE"), "RESEARCH", nowMs) } catch (_: Throwable) {}
        try { OnChainDepth8038.requestMint8540(mint, CHAIN_AGE_MS_8993) } catch (_: Throwable) {}   // the pools known by the mint alone
        try { OnChainDepth8038.needCurve8039(mint, nowMs) } catch (_: Throwable) {}   // a pump.fun coin's curve account (no-op off pump.fun)
    }

    /** The trading process's row sink: a row the chain can still price is held and asked once; every other row is filed. */
    internal fun file8993(line: String, nowMs: Long, ask: (String, Long) -> Unit = ::askChain8993) {
        if (!wantsChain8993(line)) { ResearchRows8877.note8877(line, nowMs); return }
        val mint = try { JSONObject(line).optString("mint") } catch (_: Throwable) { "" }
        val full = synchronized(parked8993) { parked8993.size >= MAX_PARKED_8993 }
        if (mint.isBlank() || full) {
            if (full) chainOverflow8993.incrementAndGet()
            chainUnpriced8993.incrementAndGet(); ResearchRows8877.note8877(line, nowMs); return
        }
        synchronized(parked8993) { parked8993.add(Parked8993(line, mint, nowMs)) }
        ask(mint, nowMs)
    }

    /** Each tick: a held row whose coin the chain has answered is finished and filed; one past [WAIT_MS_8993] is filed unpriced. */
    internal fun settle8993(nowMs: Long, price: (String, Long) -> Double = ::chainUsd8993) {
        val held = synchronized(parked8993) { ArrayList(parked8993) }
        if (held.isEmpty()) return
        val done = ArrayList<Parked8993>()
        for (p in held) {
            val usd = price(p.mint, nowMs)
            val expired = nowMs - p.atMs >= WAIT_MS_8993
            if (!(usd.isFinite() && usd > 0.0) && !expired) continue
            val finished = if (usd.isFinite() && usd > 0.0) finish8993(p.line, usd) else null
            if (finished != null) chainPriced8993.incrementAndGet()
            else { if (usd.isFinite() && usd > 0.0) chainImplausible8993.incrementAndGet(); chainUnpriced8993.incrementAndGet() }
            try { ResearchRows8877.note8877(finished ?: p.line, nowMs) } catch (_: Throwable) { failed.incrementAndGet() }
            done += p
        }
        if (done.isNotEmpty()) synchronized(parked8993) { parked8993.removeAll(done.toSet()) }
    }

    /** Brief: the reads Jupiter left unpriced and what the chain made of them. */
    internal fun chainLine8993(): String =
        "chainRead8993[asked=${chainAsked8993.get()} priced=${chainPriced8993.get()} unpriced=${chainUnpriced8993.get()} " +
            "implausible=${chainImplausible8993.get()} holding=${synchronized(parked8993) { parked8993.size }} overflow=${chainOverflow8993.get()}]"

    internal fun resetForTest8993() {
        synchronized(parked8993) { parked8993.clear() }
        chainAsked8993.set(0L); chainPriced8993.set(0L); chainUnpriced8993.set(0L); chainImplausible8993.set(0L); chainOverflow8993.set(0L)
    }

    internal fun resetForTest8884() { synchronized(pending) { pending.clear() }; loaded = true; written.set(0); unpriced.set(0); late.set(0); dropped.set(0) }
    internal fun pendingForTest8884(): List<Wait8884> = synchronized(pending) { ArrayList(pending) }
}
