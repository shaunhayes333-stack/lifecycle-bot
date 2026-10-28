package com.lifecyclebot.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8359 §MARKET_READS_KEYLESS_FIRST — owner: "there are free data points that require no key and don't affect my
 * credit balance ... use all the data Helius is sending ... just don't go stupid".
 *
 * Every market-data JSON-RPC read (curve reserves, pool vault depth, token supply, largest accounts / holder share, the
 * birth signature walk) walked the RPC ladder with the paid Helius endpoint first, so each one spent a credit that the
 * keyless public nodes in the same ladder (api.mainnet-beta.solana.com, solana-rpc.publicnode.com) answer for free.
 *
 *  - [route8359]: a method is MARKET (keyless first, Helius only when every keyless rung declined / 429'd / served a stale
 *    slot), HELIUS_ONLY (Enhanced / DAS methods no public node serves, getProgramAccounts) or EXECUTION (sends, statuses,
 *    blockhash, fees, and every read of the OWNER's wallet / our own transactions — execution truth stays on Helius).
 *  - [order8359]: the ladder re-ordered for the route; a keyless rung whose governor is closed goes behind Helius.
 *  - [Governor8359]: per keyless host, requests per 10 s (api.mainnet-beta documents 100 / 10 s per IP and 40 / 10 s
 *    per method; this app stays well under), and a 429 (HTTP or JSON-RPC -32429 / -32005) closes the host for 10 s
 *    doubling to 5 min; the next served answer reopens the ladder.
 *  - [staleSlot8359]: an answer whose `context.slot` is far behind the newest slot this app has seen is not used — the
 *    next rung is asked (a lagging public node never serves an old curve as the present).
 *  - [MintTtlCache8359]: getTokenSupply (10 min) and getTokenLargestAccounts (60 s) are cached per mint and shared by every
 *    caller (5.0.8341 dupReads{getTokenSupply=114, getTokenLargestAccounts=113} inside 10 s windows).
 *  - [batches8359]: getMultipleAccounts takes at most 100 keys; keys are de-duplicated and kept in order.
 *
 * Nothing is inferred for a rung that did not answer; a declined read stays "unknown" exactly as before.
 */
object KeylessRpc8359 {
    const val VERSION = "V5.0.8359"
    private const val WINDOW_MS_8359 = 10_000L
    private const val MAX_KEYS_8359 = 100
    const val STALE_SLOTS_8359 = 150L          // ~60 s of slots
    private const val BEST_SLOT_FRESH_MS_8359 = 90_000L
    const val SUPPLY_TTL_MS_8359 = 10L * 60_000L
    const val LARGEST_TTL_MS_8359 = 60_000L
    const val BACKOFF_BASE_MS_8359 = 10_000L
    const val BACKOFF_MAX_MS_8359 = 5L * 60_000L

    enum class Route8359 { MARKET, HELIUS_ONLY, EXECUTION }

    private val EXECUTION_METHODS_8359 = setOf(
        "sendTransaction", "simulateTransaction", "getLatestBlockhash", "isBlockhashValid", "getSignatureStatuses",
        "getFeeForMessage", "getRecentPrioritizationFees", "getPriorityFeeEstimate", "getTokenAccountBalance",
    )
    private val HELIUS_ONLY_METHODS_8359 = setOf(
        "getAsset", "getAssetBatch", "searchAssets", "getAssetsByOwner", "getAssetsByCreator", "getAssetsByAuthority",
        "getAssetsByGroup", "getSignaturesForAsset", "getTokenAccounts", "getTransactionsForAddress", "getProgramAccounts",
    )
    private val MARKET_METHODS_8359 = setOf(
        "getAccountInfo", "getMultipleAccounts", "getTokenSupply", "getTokenLargestAccounts", "getSignaturesForAddress",
        "getTransaction", "getParsedTransaction", "getBalance", "getTokenAccountsByOwner", "getBlockTime",
    )

    /**
     * Pure: where [method] is read from. [ownWallet] = the read is about the owner's trading wallet or our own
     * transaction (balances, finality, fills): execution truth, Helius. Unknown methods keep the existing ladder.
     */
    fun route8359(method: String, ownWallet: Boolean = false): Route8359 = when {
        method in EXECUTION_METHODS_8359 -> Route8359.EXECUTION
        method in HELIUS_ONLY_METHODS_8359 -> Route8359.HELIUS_ONLY
        ownWallet -> Route8359.EXECUTION
        method in MARKET_METHODS_8359 -> Route8359.MARKET
        else -> Route8359.EXECUTION
    }

    /** Pure: is [url] one of the keyless public nodes? */
    fun isKeyless8359(url: String, keyless: List<String> = KEYLESS_8359): Boolean {
        val h = host8359(url)
        return h.isNotBlank() && keyless.any { host8359(it) == h }
    }

    /** Keyless nodes in market order (5.0.8341 health: mainnet-beta sr=99%, publicnode sr=92%). */
    val KEYLESS_8359: List<String> = listOf("https://api.mainnet-beta.solana.com", "https://solana-rpc.publicnode.com")

    /** Requests per [WINDOW_MS_8359] per keyless host (documented 100 / 10 s per IP on mainnet-beta; 40 per method). */
    fun limitFor8359(host: String): Int = when {
        host.contains("mainnet-beta.solana.com") -> 30
        host.contains("publicnode.com") -> 40
        else -> 20
    }

    fun host8359(url: String): String = try { java.net.URI(url.trim()).host?.lowercase().orEmpty() } catch (_: Throwable) { "" }

    /**
     * Pure: [ladder] ordered for [route]. MARKET: open keyless rungs first (in [keylessOrder]), then every other rung in
     * its ladder order (Helius, Alchemy, the saved RPC), then keyless rungs whose governor is closed. Other routes: unchanged.
     */
    fun order8359(ladder: List<String>, route: Route8359, isOpen: (String) -> Boolean, keylessOrder: List<String> = KEYLESS_8359): List<String> {
        if (route != Route8359.MARKET) return ladder
        val clean = ladder.filter { it.isNotBlank() }.distinct()
        val keyless = clean.filter { isKeyless8359(it, keylessOrder) }
            .sortedBy { u -> keylessOrder.indexOfFirst { host8359(it) == host8359(u) }.let { if (it < 0) Int.MAX_VALUE else it } }
        val rest = clean.filter { !isKeyless8359(it, keylessOrder) }
        val (openK, closedK) = keyless.partition(isOpen)
        return openK + rest + closedK
    }

    /** Pure: getMultipleAccounts batches — de-duplicated, order kept, at most [max] (<= 100) keys a call. */
    fun batches8359(keys: List<String>, max: Int = MAX_KEYS_8359): List<List<String>> {
        val clean = LinkedHashSet<String>()
        keys.forEach { if (it.isNotBlank()) clean.add(it.trim()) }
        return clean.toList().chunked(max.coerceIn(1, MAX_KEYS_8359))
    }

    /** Pure: an answer at [answerSlot] is stale against the newest slot seen ([bestSlot], 0 = none recently). */
    fun staleSlot8359(answerSlot: Long, bestSlot: Long, maxLag: Long = STALE_SLOTS_8359): Boolean =
        answerSlot > 0L && bestSlot > 0L && bestSlot - answerSlot > maxLag

    /** Pure: the JSON-RPC error codes that mean "rate limited". */
    fun rpcRateLimited8359(code: Int): Boolean = code == -32429 || code == -32005 || code == 429

    /**
     * Per-host keyless governor: [limit] requests per [windowMs]; a 429 closes the host for [BACKOFF_BASE_MS_8359]
     * doubling (cap [BACKOFF_MAX_MS_8359]); a served answer resets the ladder. Thread-safe; time is passed in.
     */
    class Governor8359(private val limit: Int, private val windowMs: Long = WINDOW_MS_8359) {
        private val stamps = ArrayDeque<Long>()
        private var closedUntilMs = 0L
        private var level = 0

        @Synchronized fun tryTake8359(nowMs: Long): Boolean {
            if (nowMs < closedUntilMs) return false
            while (stamps.isNotEmpty() && nowMs - stamps.first() >= windowMs) stamps.removeFirst()
            if (stamps.size >= limit.coerceAtLeast(1)) return false
            stamps.addLast(nowMs)
            return true
        }

        @Synchronized fun open8359(nowMs: Long): Boolean {
            if (nowMs < closedUntilMs) return false
            while (stamps.isNotEmpty() && nowMs - stamps.first() >= windowMs) stamps.removeFirst()
            return stamps.size < limit.coerceAtLeast(1)
        }

        @Synchronized fun on429_8359(nowMs: Long, retryAfterMs: Long? = null) {
            val back = (BACKOFF_BASE_MS_8359 shl level.coerceIn(0, 5)).coerceAtMost(BACKOFF_MAX_MS_8359)
            val pause = if (retryAfterMs != null && retryAfterMs > 0L) maxOf(back, retryAfterMs.coerceAtMost(BACKOFF_MAX_MS_8359)) else back
            closedUntilMs = maxOf(closedUntilMs, nowMs + pause)
            level = (level + 1).coerceAtMost(5)
        }

        @Synchronized fun onOk8359() { level = 0 }
        @Synchronized fun closedLeftMs8359(nowMs: Long): Long = (closedUntilMs - nowMs).coerceAtLeast(0L)
        @Synchronized fun level8359(): Int = level
    }

    /** A per-mint TTL cache shared by every caller. Thread-safe; time is passed in. */
    class MintTtlCache8359(private val ttlMs: Long, private val cap: Int = 4_000) {
        private val map = ConcurrentHashMap<String, Pair<JSONObject, Long>>()
        fun get8359(mint: String, nowMs: Long): JSONObject? {
            val e = map[mint] ?: return null
            if (nowMs - e.second !in 0L until ttlMs) { map.remove(mint); return null }
            return e.first
        }
        fun put8359(mint: String, result: JSONObject, nowMs: Long) {
            if (map.size >= cap) map.entries.removeIf { nowMs - it.value.second >= ttlMs }
            if (map.size >= cap) map.clear()
            map[mint] = result to nowMs
        }
        fun size8359(): Int = map.size
    }

    private val governors = ConcurrentHashMap<String, Governor8359>()
    fun governor8359(host: String): Governor8359 = governors.computeIfAbsent(host) { Governor8359(limitFor8359(it)) }

    val supplyCache8359 = MintTtlCache8359(SUPPLY_TTL_MS_8359)
    val largestCache8359 = MintTtlCache8359(LARGEST_TTL_MS_8359)

    /** The shared cache for a per-mint read, or null when [method] is not cached. */
    fun cacheFor8359(method: String): MintTtlCache8359? = when (method) {
        "getTokenSupply" -> supplyCache8359
        "getTokenLargestAccounts" -> largestCache8359
        else -> null
    }

    // ── counters: rpcRoute8359[keyless{host=calls/ok/429}, helius{calls}, creditsSaved≈] ──
    private class HostCount8359 { val calls = AtomicLong(0L); val ok = AtomicLong(0L); val limited = AtomicLong(0L); val stale = AtomicLong(0L) }
    private val keylessCounts = ConcurrentHashMap<String, HostCount8359>()
    private val heliusCalls = AtomicLong(0L)
    private val otherPaidCalls = AtomicLong(0L)
    private val cacheHits = AtomicLong(0L)
    private val governorSkips = AtomicLong(0L)
    @Volatile private var bestSlot = 0L
    @Volatile private var bestSlotAtMs = 0L

    private fun count(host: String): HostCount8359 = keylessCounts.computeIfAbsent(host) { HostCount8359() }

    /**
     * Before a request to [url]: false = a keyless host whose governor is closed / full (skip the rung, no request).
     * Paid rungs always pass (their own pacing is HeliusRateGate8191) and are counted.
     */
    fun beforeCall8359(url: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val h = host8359(url)
        if (isKeyless8359(url)) {
            if (!governor8359(h).tryTake8359(nowMs)) { governorSkips.incrementAndGet(); return false }
            count(h).calls.incrementAndGet()
            return true
        }
        if (h.contains("helius")) heliusCalls.incrementAndGet() else otherPaidCalls.incrementAndGet()
        return true
    }

    /** After a keyless answer: [httpCode] (0 = transport failure), [rpcErrorCode] (0 = none), [retryAfterMs] when stated. */
    fun afterCall8359(url: String, httpCode: Int, rpcErrorCode: Int = 0, retryAfterMs: Long? = null, nowMs: Long = System.currentTimeMillis()) {
        if (!isKeyless8359(url)) return
        val h = host8359(url)
        val g = governor8359(h)
        when {
            httpCode == 429 || rpcRateLimited8359(rpcErrorCode) -> {
                count(h).limited.incrementAndGet()
                g.on429_8359(nowMs, retryAfterMs)
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("KEYLESS_RPC_429_8359") } catch (_: Throwable) {}
            }
            httpCode in 200..299 && rpcErrorCode == 0 -> { count(h).ok.incrementAndGet(); g.onOk8359() }
        }
    }

    /**
     * An answer's `result.context.slot`: records the newest slot seen; true when the answer is STALE against it (the
     * caller asks the next rung). An answer without a context is never stale.
     */
    fun staleAnswer8359(url: String, root: JSONObject?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val slot = root?.optJSONObject("result")?.optJSONObject("context")?.optLong("slot", 0L) ?: 0L
        if (slot <= 0L) return false
        val best = if (nowMs - bestSlotAtMs <= BEST_SLOT_FRESH_MS_8359) bestSlot else 0L
        if (staleSlot8359(slot, best)) {
            if (isKeyless8359(url)) count(host8359(url)).stale.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("KEYLESS_RPC_STALE_SLOT_8359") } catch (_: Throwable) {}
            return true
        }
        if (slot >= bestSlot || best == 0L) { bestSlot = slot; bestSlotAtMs = nowMs }
        return false
    }

    fun noteCacheHit8359() { cacheHits.incrementAndGet() }

    /** The market-read ladder: [ladder] re-ordered for [method] with the live governors. */
    fun marketLadder8359(ladder: List<String>, method: String, nowMs: Long = System.currentTimeMillis()): List<String> =
        order8359(ladder, route8359(method), { u -> governor8359(host8359(u)).open8359(nowMs) })

    /** The ladder label convention ("helius", else rpc_<host>) shared with the curve / supply readers. */
    fun label8359(url: String): String {
        if (url.contains("helius", ignoreCase = true)) return "helius"
        val h = host8359(url)
        return if (h.isBlank()) "solana_rpc" else "rpc_$h"
    }

    /**
     * One market read down the keyless-first ladder: the shared cache for per-mint methods, the governors, the stale-slot
     * check. Returns the JSON-RPC `result`, or null when no rung answered (unknown — never a fabricated value).
     */
    fun post8359(http: OkHttpClient, method: String, params: JSONArray, idTag: String, maxRungs: Int = 4, cacheKey: String? = null): JSONObject? {
        val now = System.currentTimeMillis()
        val cache = cacheKey?.let { cacheFor8359(method) }
        if (cache != null && cacheKey != null) cache.get8359(cacheKey, now)?.let { cacheHits.incrementAndGet(); return it }
        val ladder = try { com.lifecyclebot.engine.RuntimeProviderAuthority6685.marketReadCandidates8359(null, method) } catch (_: Throwable) { emptyList() }
        val body = JSONObject().put("jsonrpc", "2.0").put("id", idTag).put("method", method).put("params", params).toString()
        var tried = 0
        var why8418 = "noRung"   // V5.0.8418 — why this read came back empty (RpcOutcome8418.line8418)
        for (url in ladder) {
            if (tried >= maxRungs) break
            val label = label8359(url)
            if (try { com.lifecyclebot.engine.ApiBackoff.isLockedOut(label) } catch (_: Throwable) { false }) continue
            if (!beforeCall8359(url)) continue
            tried++
            try {
                val req = Request.Builder().url(url).header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                val got: JSONObject? = com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, label).use { r ->
                    if (HostCircuitInterceptor.isSyntheticBlock(r)) { why8418 = "ownBlock"; return@use null }
                    if (!r.isSuccessful) {
                        why8418 = "http${r.code}"
                        afterCall8359(url, r.code, 0, r.header("Retry-After")?.trim()?.toLongOrNull()?.let { it * 1000L })
                        return@use null
                    }
                    val j = try { JSONObject(r.body?.string().orEmpty()) } catch (_: Throwable) { null }
                    if (j == null) { why8418 = "parse"; afterCall8359(url, 0); return@use null }
                    val err = j.optJSONObject("error")
                    if (err != null) {
                        why8418 = RpcOutcome8418.rpcErrorKey8418(label, err.optInt("code", -1), err.optString("message"))
                        afterCall8359(url, r.code, err.optInt("code", -1)); return@use null
                    }
                    afterCall8359(url, r.code)
                    if (staleAnswer8359(url, j)) { why8418 = "stale"; return@use null }
                    if (j.optJSONObject("result") == null) why8418 = "noResult"
                    j.optJSONObject("result")
                }
                if (got != null) {
                    if (cache != null && cacheKey != null) cache.put8359(cacheKey, got, System.currentTimeMillis())
                    RpcOutcome8418.note8418(idTag, "ok")
                    return got
                }
            } catch (_: Throwable) { why8418 = "transport"; afterCall8359(url, 0) }
        }
        RpcOutcome8418.note8418(idTag, why8418)
        return null
    }

    /** rpcRoute8359[keyless{host=calls/ok/429/stale}, helius{calls}, paid{calls}, cacheHits, governorSkips, creditsSaved≈] */
    fun statusLine8359(nowMs: Long = System.currentTimeMillis()): String {
        val k = keylessCounts.entries.sortedBy { it.key }.joinToString(",") { (h, c) ->
            "$h=${c.calls.get()}/${c.ok.get()}/${c.limited.get()}/${c.stale.get()}" +
                governors[h]?.closedLeftMs8359(nowMs)?.takeIf { it > 0L }?.let { "(closed${it / 1000L}s)" }.orEmpty()
        }.ifBlank { "-" }
        val saved = keylessCounts.values.sumOf { it.ok.get() } + cacheHits.get()
        return "rpcRoute8359[keyless{$k}, helius{calls=${heliusCalls.get()}}, paid{calls=${otherPaidCalls.get()}}, " +
            "cacheHits=${cacheHits.get()}, governorSkips=${governorSkips.get()}, creditsSaved≈$saved]"
    }

    internal fun resetForTests8359() {
        governors.clear(); keylessCounts.clear(); heliusCalls.set(0L); otherPaidCalls.set(0L); cacheHits.set(0L)
        governorSkips.set(0L); bestSlot = 0L; bestSlotAtMs = 0L
    }
}
