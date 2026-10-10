package com.lifecyclebot.engine

import com.lifecyclebot.network.SharedHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7968 — pump.fun Callouts, read live.
 *
 * pump.fun users "call" a coin in the app (one call per 6 h each); every call is
 * scored by the multiple it reached afterwards, and the site ranks callers on a public
 * leaderboard (pump.fun/leaderboard: avg / median multiple, % of calls that 2x, time to
 * peak). A caller with a long record of 2x calls is an expert whose fresh call is an
 * entry signal — and their followers get a push the moment they call, so the flow
 * arrives right after.
 *
 * Source: advanced-api-v2.pump.fun (the API behind the site; schema from the public
 * BankkRoll/pumpfun-apis capture):
 *   GET /callout/leaderboard?limit=N  -> {callouts:[{userId, primaryWallet, wallets[], totalCallouts,
 *        avgMultiple, medianMultiple, pct2xOrMore, averageTimeToPeak, topCallouts:[{coinMint, marketCap, multiple, createdAt}]}]}
 *   GET /callout/list/{userId}?limit&sortBy=createdAt&sortOrder=desc -> {callouts:[{coinMint, marketCap, multiple, createdAt, thesis}]}
 * No key. If the API ever demands a login token it answers 401/403: the reader
 * counts it in the diag and does nothing else (fail soft).
 *
 * What it does:
 *   1. every 5 min: the leaderboard -> callers ranked; PROVEN = enough calls and a
 *      median multiple / 2x rate that clear the bar ([provenCaller7968]).
 *   2. every 30 s: the newest calls of up to [LISTS_PER_TICK] proven callers (round robin).
 *      A call made in the last [FRESH_MS] on a coin not seen before:
 *        - goes to the meme scanner through the smart-money intake
 *          (InsiderCopyEngine.copyBuyFromSmartMoney7277, label CALLOUT_7968:<caller>);
 *        - marks the mint an EXPERT_ENTRY for 15 min (graded playbook setup, ExpertWallets7962).
 *      V3, the safety tiers, sizing and the learned gates still decide every buy.
 *   3. callers' wallets are added to ExpertWallets7962 as TOP experts, so their real
 *      trade histories teach charts, entry cells and exits.
 *   4. learns from the callouts themselves: per market-cap band at call time, how
 *      often a call reached 2x and the median multiple (the diag shows where calls pay).
 */
object PumpCallouts7968 {

    private const val TAG = "PumpCallouts7968"
    private const val API = "https://advanced-api-v2.pump.fun"
    private const val LEADERBOARD_MS = 5L * 60_000L
    private const val TICK_MS = 30_000L
    private const val LISTS_PER_TICK = 4
    private const val FRESH_MS = 15L * 60_000L
    private const val MAX_CALLER_WALLETS = 10

    data class Caller7968(
        val userId: String, val primaryWallet: String, val wallets: List<String>,
        val total: Int, val avgMultiple: Double, val medianMultiple: Double, val pct2x: Double, val timeToPeakS: Double,
    )

    data class Call7968(val userId: String, val mint: String, val marketCapUsd: Double, val multiple: Double, val createdMs: Long)

    // ── pure ──

    /** Pure: a percentage the API may send as 0..1 or 0..100, as a fraction. */
    fun frac7968(x: Double): Double = if (!x.isFinite() || x < 0.0) 0.0 else if (x > 1.0) (x / 100.0).coerceAtMost(1.0) else x

    /** Pure: createdAt as epoch ms (number in s or ms, or an ISO-8601 string); 0 when unreadable. */
    fun timeMs7968(v: Any?): Long = when (v) {
        is Number -> v.toLong().let { if (it in 1 until 100_000_000_000L) it * 1000L else it }
        is String -> v.toLongOrNull()?.let { timeMs7968(it) } ?: try { Instant.parse(v).toEpochMilli() } catch (_: Throwable) { 0L }
        else -> 0L
    }

    private fun num(o: JSONObject, k: String): Double {
        val v = o.opt(k) ?: return Double.NaN
        return when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull() ?: Double.NaN; else -> Double.NaN }
    }

    private fun parseCall(o: JSONObject, fallbackUser: String): Call7968? {
        val mint = o.optString("coinMint").ifBlank { o.optString("mint") }
        if (mint.length < 30) return null
        return Call7968(o.optString("userId").ifBlank { fallbackUser }, mint, num(o, "marketCap"), num(o, "multiple"), timeMs7968(o.opt("createdAt")))
    }

    /** Pure: the leaderboard body -> callers and every call they list (top callouts). */
    fun parseLeaderboard7968(body: String): Pair<List<Caller7968>, List<Call7968>> {
        val t = body.trimStart()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { it.optJSONArray("callouts") ?: it.optJSONArray("data") ?: JSONArray() }
        val callers = ArrayList<Caller7968>()
        val calls = ArrayList<Call7968>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uid = o.optString("userId").ifBlank { o.optString("user_uuid") }
            if (uid.isBlank()) continue
            val wl = ArrayList<String>()
            o.optString("primaryWallet").takeIf { it.length in 32..44 }?.let { wl += it }
            o.optJSONArray("wallets")?.let { w ->
                for (j in 0 until w.length()) {
                    val a = w.opt(j)
                    val s = if (a is JSONObject) a.optString("address").ifBlank { a.optString("wallet") } else a?.toString().orEmpty()
                    if (s.length in 32..44 && s !in wl) wl += s
                }
            }
            callers += Caller7968(uid, o.optString("primaryWallet"), wl, o.optInt("totalCallouts", 0),
                num(o, "avgMultiple"), num(o, "medianMultiple"), frac7968(num(o, "pct2xOrMore")), num(o, "averageTimeToPeak"))
            o.optJSONArray("topCallouts")?.let { tc -> for (j in 0 until tc.length()) tc.optJSONObject(j)?.let { c -> parseCall(c, uid)?.let { calls += it } } }
        }
        return callers to calls
    }

    /** Pure: a caller's list body -> calls. */
    fun parseCallList7968(body: String, userId: String): List<Call7968> {
        val t = body.trimStart()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { it.optJSONArray("callouts") ?: it.optJSONArray("data") ?: JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> parseCall(o, userId) } }
    }

    /**
     * Pure: is this caller's record good enough to act on? At least 8 scored calls, a
     * median call that reached 1.5x, and at least 35% of calls doubling.
     */
    fun provenCaller7968(c: Caller7968): Boolean =
        c.total >= 8 && c.medianMultiple.isFinite() && c.medianMultiple >= 1.5 && c.pct2x >= 0.35

    /** Pure: confidence 0..100 handed to the intake (rank only — the gates still decide). */
    fun confidence7968(c: Caller7968): Int =
        (40.0 + 25.0 * c.pct2x + 8.0 * (c.medianMultiple - 1.0).coerceIn(0.0, 3.0) + (c.total / 10.0).coerceAtMost(10.0)).toInt().coerceIn(0, 95)

    /** Pure: a call is actionable when it is fresh and has a real coin. */
    fun fresh7968(call: Call7968, nowMs: Long): Boolean = call.createdMs > 0L && nowMs - call.createdMs in 0L..FRESH_MS

    /** Pure: the market-cap band of a call. */
    fun mcBand7968(mcUsd: Double): String = when {
        !mcUsd.isFinite() || mcUsd <= 0.0 -> "MC_NA"
        mcUsd < 10_000.0 -> "MC_LT10K"
        mcUsd < 30_000.0 -> "MC_10_30K"
        mcUsd < 100_000.0 -> "MC_30_100K"
        mcUsd < 1_000_000.0 -> "MC_100K_1M"
        else -> "MC_GE1M"
    }

    // ── state ──

    private class Band(var n: Int = 0, var x2: Int = 0, val mults: ArrayList<Double> = ArrayList())

    private val started = AtomicBoolean(false)
    private val callers = ConcurrentHashMap<String, Caller7968>()
    private val seenCalls = ConcurrentHashMap<String, Long>()
    private val learnedCalls = ConcurrentHashMap.newKeySet<String>()
    private val bands = HashMap<String, Band>()
    private val recent = ArrayDeque<String>()
    private var rr = 0
    private val polls = AtomicLong(0)
    private val okPolls = AtomicLong(0)
    private val authRefused = AtomicLong(0)
    private val freshCalls = AtomicLong(0)
    private val intaken = AtomicLong(0)
    private val walletsAdded = AtomicLong(0)
    @Volatile private var lastStatus = "-"
    @Volatile private var lastBoardMs = 0L

    private val http by lazy {
        SharedHttpClient.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }

    /** SmartMoneyDiscovery7277.start: the live callout reader (IO, never on the hot path). */
    fun start7968(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            delay(45_000L)
            while (isActive) {
                try { tick(System.currentTimeMillis()) } catch (t: Throwable) { ErrorLogger.debug(TAG, "tick: ${t.message?.take(100)}") }
                delay(TICK_MS)
            }
        }
    }

    private fun get(path: String): String? {
        polls.incrementAndGet()
        return try {
            val req = Request.Builder().url("$API$path")
                .header("Accept", "application/json")
                .header("Origin", "https://pump.fun").header("Referer", "https://pump.fun/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
                .build()
            http.newCall(req).execute().use { r ->
                lastStatus = r.code.toString()
                if (r.code == 401 || r.code == 403) { authRefused.incrementAndGet(); return null }
                val b = r.body?.string()
                if (!r.isSuccessful || b.isNullOrBlank()) null else { okPolls.incrementAndGet(); b }
            }
        } catch (t: Throwable) { lastStatus = t.javaClass.simpleName; null }
    }

    private fun tick(now: Long) {
        if (now - lastBoardMs >= LEADERBOARD_MS) {
            lastBoardMs = now
            get("/callout/leaderboard?limit=50")?.let { body ->
                val (cs, calls) = try { parseLeaderboard7968(body) } catch (_: Throwable) { emptyList<Caller7968>() to emptyList<Call7968>() }
                cs.forEach { callers[it.userId] = it }
                calls.forEach { learnCall(it) }
                val proven = cs.filter { provenCaller7968(it) }.sortedByDescending { it.pct2x * it.medianMultiple }
                val wallets = proven.flatMap { it.wallets }.distinct().take(MAX_CALLER_WALLETS)
                if (wallets.isNotEmpty()) walletsAdded.addAndGet(try { ExpertWallets7962.addCallerWallets7968(wallets).toLong() } catch (_: Throwable) { 0L })
                // Leaderboard top calls can be fresh too.
                calls.forEach { c -> callers[c.userId]?.let { onCall(c, it, now) } }
            }
        }
        val proven = callers.values.filter { provenCaller7968(it) }.sortedByDescending { it.pct2x * it.medianMultiple }
        if (proven.isEmpty()) return
        repeat(minOf(LISTS_PER_TICK, proven.size)) {
            val c = proven[(rr++ and Int.MAX_VALUE) % proven.size]
            get("/callout/list/${c.userId}?limit=10&sortBy=createdAt&sortOrder=desc")?.let { body ->
                (try { parseCallList7968(body, c.userId) } catch (_: Throwable) { emptyList<Call7968>() }).forEach { call -> learnCall(call); onCall(call, c, now) }
            }
        }
        if (seenCalls.size > 5_000) seenCalls.entries.removeIf { now - it.value > 6L * 3_600_000L }
    }

    /** A scored call teaches where calls pay (mcap band at call -> multiple). Counted once, after 1 h. */
    private fun learnCall(c: Call7968) {
        if (!c.multiple.isFinite() || c.multiple <= 0.0) return
        if (c.createdMs > 0L && System.currentTimeMillis() - c.createdMs < 3_600_000L) return
        if (!learnedCalls.add("${c.userId}|${c.mint}")) return
        if (learnedCalls.size > 20_000) learnedCalls.clear()
        synchronized(bands) {
            val b = bands.getOrPut(mcBand7968(c.marketCapUsd)) { Band() }
            b.n++; if (c.multiple >= 2.0) b.x2++
            b.mults += c.multiple; if (b.mults.size > 500) b.mults.removeAt(0)
        }
    }

    private fun onCall(call: Call7968, caller: Caller7968, now: Long) {
        if (!provenCaller7968(caller) || !fresh7968(call, now)) return
        if (seenCalls.putIfAbsent(call.mint, now) != null) return
        freshCalls.incrementAndGet()
        try { ExpertWallets7962.noteCallout7968(call.mint, now) } catch (_: Throwable) {}
        try {
            InsiderCopyEngine.copyBuyFromSmartMoney7277(call.mint, call.mint.take(6), "CALLOUT_7968:${caller.userId.take(8)}", confidence7968(caller))
            intaken.incrementAndGet()
        } catch (_: Throwable) {}
        synchronized(recent) {
            recent.addLast("${call.mint.take(6)}@${"%.0f".format(call.marketCapUsd / 1000.0)}k/${caller.userId.take(6)}")
            while (recent.size > 5) recent.removeFirst()
        }
        try {
            PipelineHealthCollector.labelInc("PUMP_CALLOUT_FRESH_7968")
            ForensicLogger.lifecycle("PUMP_CALLOUT_7968", "mint=${call.mint.take(10)} mc=${call.marketCapUsd.toInt()} caller=${caller.userId.take(8)} med=${"%.2f".format(caller.medianMultiple)} x2=${"%.0f".format(caller.pct2x * 100)}%")
        } catch (_: Throwable) {}
    }

    /** V5.0.7977 — MemoryGuard7977. */
    fun trim7977() {
        val now = System.currentTimeMillis()
        seenCalls.entries.removeIf { now - it.value > 3_600_000L }
        learnedCalls.clear()
    }

    fun statusLine7968(): String {
        val bandTxt = synchronized(bands) {
            bands.entries.sortedBy { it.key }.joinToString(" ") { (k, b) ->
                val med = b.mults.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
                "$k:n${b.n}/2x${if (b.n > 0) b.x2 * 100 / b.n else 0}%/med${"%.1f".format(med)}"
            }.ifBlank { "-" }
        }
        val rec = synchronized(recent) { recent.joinToString(",").ifBlank { "-" } }
        return "polls=${polls.get()} ok=${okPolls.get()} authRefused=${authRefused.get()} last=$lastStatus callers=${callers.size} " +
            "proven=${callers.values.count { provenCaller7968(it) }} fresh=${freshCalls.get()} intake=${intaken.get()} walletsAdded=${walletsAdded.get()} " +
            "recent[$rec] bands[$bandTxt]"
    }
}
