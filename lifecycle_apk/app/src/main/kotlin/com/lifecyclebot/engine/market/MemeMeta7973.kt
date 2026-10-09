package com.lifecyclebot.engine.market

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.PipelineHealthCollector
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
import kotlin.math.sqrt

/**
 * V5.0.7973 — the meme meta: three things pump.fun traders watch that the bot did not.
 *
 * 1. GRADUATION PROGRESS. A pump.fun coin graduates when its curve has taken ~85 SOL
 *    (virtual SOL 30 -> 115). The curve is constant-product with k = 30 SOL x 1.073B
 *    tokens, so market cap in SOL alone gives the virtual SOL: vSol = sqrt(32.19 x mcapSol),
 *    and progress = (vSol - 30) / 85. Traders front-run graduation (80-95%) and King of
 *    the Hill; the bot now knows where every pump coin sits on that road without a
 *    single extra call.
 * 2. COPYCAT / BETA ROTATION. When a coin runs (+400% on the bot's own labels), the
 *    words in its name and ticker become a live theme for 6 hours; a new coin that
 *    shares one is a beta play on that leader.
 * 3. LIVESTREAMS + KING OF THE HILL. pump.fun's currently-live list and its KOTH coin
 *    (frontend-api-v3, keyless) are polled every minute: live coins enter the scanner
 *    (lanes SHITCOIN / MOONSHOT / EXPRESS), and both are facts.
 *
 * None of these decides a trade by itself: they are facts the specialist miner
 * (SpecialistMiner7972) grades on the labels — a combination that pays becomes a
 * specialist, one that does not stays silent.
 */
object MemeMeta7973 {

    private const val TAG = "MemeMeta7973"
    private const val K_OVER_SUPPLY = 32.19        // 30 SOL x 1.073e9 tokens / 1e9 supply
    private const val GRAD_REAL_SOL = 85.0
    private const val THEME_MS = 6L * 3_600_000L
    private const val POLL_MS = 60_000L
    private const val INTAKE_GAP_MS = 60L * 60_000L
    private val STOP = setOf(
        "the", "coin", "token", "sol", "solana", "pump", "fun", "inu", "official", "meme", "and", "for", "of",
        "on", "in", "a", "an", "to", "my", "is", "it", "this", "that", "with", "new", "real", "first", "best",
    )

    // ── pure ──

    /** Pure: pump.fun bonding-curve progress 0..1 from market cap; NaN when unknown, 1.0 at/after graduation. */
    fun curveProgress7973(mcapUsd: Double, solUsd: Double): Double {
        if (!(mcapUsd > 0.0) || !(solUsd > 0.0)) return Double.NaN
        val vSol = sqrt(K_OVER_SUPPLY * (mcapUsd / solUsd))
        return ((vSol - 30.0) / GRAD_REAL_SOL).coerceIn(0.0, 1.0)
    }

    /** Pure: the progress bin the miner reads. */
    fun gradBin7973(progress: Double): String = when {
        !progress.isFinite() -> "NA"
        progress >= 1.0 -> "DONE"
        progress >= 0.95 -> "95_100"
        progress >= 0.80 -> "80_95"
        progress >= 0.50 -> "50_80"
        progress >= 0.25 -> "25_50"
        else -> "LT25"
    }

    /** Pure: a coin's theme words (lowercase letters, 3+ long, not filler). */
    fun words7973(symbol: String, name: String): Set<String> =
        (symbol + " " + name).lowercase().split(Regex("[^a-z]+")).filter { it.length >= 3 && it !in STOP }.toSet()

    // ── state ──

    private val themes = ConcurrentHashMap<String, Long>()       // word -> leader seen at
    private val leaders = ConcurrentHashMap<String, Long>()      // mint -> noted at
    private val live = ConcurrentHashMap<String, Long>()         // mint -> last seen live
    @Volatile private var koth = ""
    @Volatile private var kothAtMs = 0L
    private val intakeAt = ConcurrentHashMap<String, Long>()
    private val started = AtomicBoolean(false)
    private val polls = AtomicLong(0)
    private val pollOk = AtomicLong(0)
    private val intaken = AtomicLong(0)
    private val betaHits = AtomicLong(0)
    @Volatile private var lastStatus = "-"

    /** ForwardReturnLabeler7731: a labelled decision ran +400% — its words become a theme. */
    fun noteLeader7973(mint: String, symbol: String, name: String, nowMs: Long = System.currentTimeMillis()) {
        if (leaders.putIfAbsent(mint, nowMs) != null) return
        for (w in words7973(symbol, name)) themes[w] = nowMs
        if (themes.size > 2_000) themes.entries.removeIf { nowMs - it.value > THEME_MS }
        if (leaders.size > 2_000) leaders.entries.removeIf { nowMs - it.value > THEME_MS }
        try { PipelineHealthCollector.labelInc("MEME_META_LEADER_7973") } catch (_: Throwable) {}
    }

    /** A coin (not a leader itself) that shares a theme word with a leader of the last 6 h. */
    fun beta7973(mint: String, symbol: String, name: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (themes.isEmpty() || leaders.containsKey(mint)) return false
        val hit = words7973(symbol, name).any { w -> themes[w]?.let { nowMs - it <= THEME_MS } == true }
        if (hit) betaHits.incrementAndGet()
        return hit
    }

    fun live7973(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean = live[mint]?.let { nowMs - it <= 3 * POLL_MS } == true
    fun koth7973(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean = mint == koth && nowMs - kothAtMs <= 3 * POLL_MS

    // ── feed ──

    private val http by lazy {
        SharedHttpClient.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }

    /** SmartMoneyDiscovery7277.start: livestream + KOTH poller (IO). */
    fun start7973(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            delay(30_000L)
            while (isActive) {
                try { poll(System.currentTimeMillis()) } catch (t: Throwable) { ErrorLogger.debug(TAG, "poll: ${t.message?.take(100)}") }
                delay(POLL_MS)
            }
        }
    }

    private fun get(url: String): String? {
        polls.incrementAndGet()
        return try {
            val req = Request.Builder().url(url).header("Accept", "application/json")
                .header("Origin", "https://pump.fun").header("Referer", "https://pump.fun/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36").build()
            http.newCall(req).execute().use { r ->
                lastStatus = r.code.toString()
                val b = r.body?.string()
                if (!r.isSuccessful || b.isNullOrBlank()) null else { pollOk.incrementAndGet(); b }
            }
        } catch (t: Throwable) { lastStatus = t.javaClass.simpleName; null }
    }

    /** Pure: (mint, symbol, usd market cap) rows from a pump coin list or single coin body. */
    fun coins7973(body: String): List<Triple<String, String, Double>> {
        val t = body.trimStart()
        val arr = when {
            t.startsWith("[") -> JSONArray(t)
            else -> JSONObject(t).let { it.optJSONArray("coins") ?: JSONArray().put(it) }
        }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val mint = o.optString("mint")
            if (mint.length < 32) null else Triple(mint, o.optString("symbol"), o.optDouble("usd_market_cap", 0.0).let { if (it.isFinite()) it else 0.0 })
        }
    }

    private fun poll(now: Long) {
        get("https://frontend-api-v3.pump.fun/coins/currently-live?limit=60&offset=0&includeNsfw=false")?.let { body ->
            for ((mint, sym, mc) in try { coins7973(body) } catch (_: Throwable) { emptyList() }) {
                live[mint] = now
                val last = intakeAt[mint] ?: 0L
                if (now - last >= INTAKE_GAP_MS) {
                    intakeAt[mint] = now
                    try {
                        com.lifecyclebot.engine.TokenMergeQueue.enqueue(
                            mint = mint, symbol = sym.ifBlank { mint.take(6) }, scanner = "PUMP_LIVESTREAM_7973", marketCapUsd = mc,
                            laneAffinity = setOf("SHITCOIN", "MOONSHOT", "EXPRESS"),
                        )
                        intaken.incrementAndGet()
                    } catch (_: Throwable) {}
                }
            }
            if (live.size > 2_000) live.entries.removeIf { now - it.value > 10 * POLL_MS }
            if (intakeAt.size > 4_000) intakeAt.entries.removeIf { now - it.value > INTAKE_GAP_MS }
        }
        get("https://frontend-api-v3.pump.fun/coins/king-of-the-hill?includeNsfw=false")?.let { body ->
            (try { coins7973(body) } catch (_: Throwable) { emptyList() }).firstOrNull()?.let { koth = it.first; kothAtMs = now }
        }
    }

    fun statusLine7973(): String {
        val now = System.currentTimeMillis()
        val liveNow = live.values.count { now - it <= 3 * POLL_MS }
        val themeNow = themes.entries.filter { now - it.value <= THEME_MS }.sortedByDescending { it.value }.take(6).joinToString(",") { it.key }.ifBlank { "-" }
        return "polls=${polls.get()} ok=${pollOk.get()} last=$lastStatus liveNow=$liveNow intake=${intaken.get()} koth=${koth.take(6).ifBlank { "-" }} " +
            "leaders=${leaders.size} themes[$themeNow] betaHits=${betaHits.get()}"
    }
}
