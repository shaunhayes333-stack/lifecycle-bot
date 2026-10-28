package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 §ONE_CALL_PER_HUNDRED_KEYS — 5.0.8416: "On-chain pools ... reads=1148 failed=5446" beside
 * "rpcRoute8359 ... governorSkips=8269". Every background pool read was its own two calls (getAccountInfo for the pool,
 * then getMultipleAccounts for two vaults) on a 2-thread executor with a 32-slot queue that dropped the overflow, so the
 * keyless governors (30 / 40 requests per 10 s) were full most of the time and most reads were refused locally before
 * they reached a node.
 *
 * Background reads ([OnChainDepth8038.request8038]) now queue here, one entry per mint (a repeat request while queued is
 * coalesced), and drain every [FLUSH_MS] in two phases, each a getMultipleAccounts of at most [MAX_KEYS] keys:
 *   A. pool accounts not yet decoded (base64) -> vault keys + owner program (OnChainDepth8038.adoptPool8418);
 *   B. every queued mint's vaults (+ the pool account on a price-in-state venue), jsonParsed, keys of one mint never
 *      split across calls -> OnChainDepth8038.accept8418 (the same acceptance the blocking buy-door read uses).
 * These are analytics reads: they ride the keyless-first market ladder (KeylessRpc8359.post8359, tags "8418pool" /
 * "8418vault", causes counted by RpcOutcome8418). Execution reads and sends are untouched and stay on Helius.
 */
object PoolBatch8418 {
    const val MAX_KEYS = 100
    private const val FLUSH_MS = 2_000L
    private const val MAX_PENDING = 400
    private const val MAX_MINTS_PER_DRAIN = 150

    private val pending = LinkedHashMap<String, List<String>>()   // mint -> candidate pools (guarded by this)
    private val scheduled = AtomicBoolean(false)
    private val queued = AtomicLong(0)
    private val coalesced = AtomicLong(0)
    private val refused = AtomicLong(0)
    private val calls = AtomicLong(0)
    private val keysAsked = AtomicLong(0)
    private val answered = AtomicLong(0)

    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AATE-pool-batch-8418").apply { isDaemon = true }
    }

    private val http: OkHttpClient by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    }

    /** Queue [mint]'s pools for the next drain. False = refused (queue full): the caller falls back to its own read. */
    fun enqueue8418(mint: String, pools: List<String>): Boolean {
        if (mint.isBlank() || pools.isEmpty()) return false
        synchronized(this) {
            if (pending.containsKey(mint)) { coalesced.incrementAndGet(); pending[mint] = pools; return true }
            if (pending.size >= MAX_PENDING) { refused.incrementAndGet(); return false }
            pending[mint] = pools
            queued.incrementAndGet()
        }
        if (scheduled.compareAndSet(false, true)) {
            try { scheduler.schedule({ drain() }, FLUSH_MS, TimeUnit.MILLISECONDS) } catch (_: Throwable) { scheduled.set(false) }
        }
        return true
    }

    /**
     * Pure: [groups] (one key list per mint, kept whole) packed in order into calls of at most [max] keys, with keys
     * de-duplicated inside a call. A group larger than [max] on its own is dropped (no call could carry it whole).
     */
    fun pack8418(groups: List<List<String>>, max: Int = MAX_KEYS): List<List<Int>> {
        val cap = max.coerceIn(1, MAX_KEYS)
        val out = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        var curKeys = LinkedHashSet<String>()
        for ((i, g) in groups.withIndex()) {
            val distinct = g.filter { it.isNotBlank() }.distinct()
            if (distinct.isEmpty() || distinct.size > cap) continue
            val added = distinct.count { it !in curKeys }
            if (curKeys.size + added > cap && cur.isNotEmpty()) {
                out.add(cur); cur = ArrayList(); curKeys = LinkedHashSet()
            }
            cur.add(i); curKeys.addAll(distinct)
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun takeBatch(): List<Pair<String, List<String>>> = synchronized(this) {
        val out = ArrayList<Pair<String, List<String>>>()
        val it = pending.entries.iterator()
        while (it.hasNext() && out.size < MAX_MINTS_PER_DRAIN) {
            val e = it.next()
            out.add(e.key to e.value)
            it.remove()
        }
        out
    }

    private fun getMultiple(keys: List<String>, encoding: String, tag: String): JSONArray? {
        calls.incrementAndGet()
        keysAsked.addAndGet(keys.size.toLong())
        val params = JSONArray().put(JSONArray().apply { keys.forEach { put(it) } })
            .put(JSONObject().put("encoding", encoding).put("commitment", "confirmed"))
        return com.lifecyclebot.network.KeylessRpc8359.post8359(http, "getMultipleAccounts", params, tag, maxRungs = 3)
            ?.optJSONArray("value")
    }

    private fun drain() {
        try {
            val batch = takeBatch()
            if (batch.isNotEmpty()) try { drainOnce(batch) } finally { batch.forEach { OnChainDepth8038.batchDone8418(it.first) } }
        } catch (_: Throwable) {
        } finally {
            scheduled.set(false)
            val more = synchronized(this) { pending.isNotEmpty() }
            if (more && scheduled.compareAndSet(false, true)) {
                try { scheduler.schedule({ drain() }, FLUSH_MS, TimeUnit.MILLISECONDS) } catch (_: Throwable) { scheduled.set(false) }
            }
        }
    }

    private class Job8418(val mint: String, val pool: String, val owner: String, val keys: List<String>)

    private fun drainOnce(batch: List<Pair<String, List<String>>>) {
        val now = System.currentTimeMillis()
        // Phase A — pool accounts not decoded yet.
        val unknown = batch.flatMap { it.second }.distinct()
            .filter { OnChainDepth8038.knownPool8418(it) == null && !OnChainDepth8038.unsupportedNow8418(it, now) }
        for (chunk in unknown.chunked(MAX_KEYS)) {
            val arr = getMultiple(chunk, "base64", "8418pool") ?: continue
            for ((i, poolAddr) in chunk.withIndex()) {
                val acct = arr.optJSONObject(i)
                if (acct == null) { OnChainDepth8038.noPoolAccount8418(poolAddr); continue }
                OnChainDepth8038.adoptPool8418(poolAddr, acct.optString("owner"), OnChainDepth8038.accountData8418(acct))
            }
        }
        // Phase B — vaults (+ pool state) of each mint's first decoded pool.
        val jobs = ArrayList<Job8418>()
        for ((mint, pools) in batch) {
            val chosen = pools.firstNotNullOfOrNull { p -> OnChainDepth8038.knownPool8418(p)?.let { p to it } } ?: continue
            val (p, known) = chosen
            jobs.add(Job8418(mint, p, known.second, OnChainDepth8038.readKeys8418(known.first, p, known.second)))
        }
        for (callIdx in pack8418(jobs.map { it.keys })) {
            val callJobs = callIdx.map { jobs[it] }
            val keys = LinkedHashSet<String>().apply { callJobs.forEach { addAll(it.keys) } }.toList()
            val observedAt = System.currentTimeMillis()
            val arr = getMultiple(keys, "jsonParsed", "8418vault")
            if (arr == null) { callJobs.forEach { OnChainDepth8038.batchFailed8418(it.mint) }; continue }
            val byKey = HashMap<String, JSONObject?>()
            for ((i, k) in keys.withIndex()) byKey[k] = arr.optJSONObject(i)
            for (j in callJobs) {
                answered.incrementAndGet()
                OnChainDepth8038.accept8418(j.mint, j.pool, j.owner, byKey[j.keys[0]], byKey[j.keys[1]], j.keys.getOrNull(2)?.let { byKey[it] }, observedAt)
            }
        }
        try { PipelineHealthCollector.labelInc("ONCHAIN_POOL_BATCH_DRAIN_8418") } catch (_: Throwable) {}
    }

    /** queued=.. coalesced=.. refused=.. calls=.. keys=.. answered=.. pending=.. */
    fun statusLine8418(): String {
        val p = synchronized(this) { pending.size }
        return "queued=${queued.get()} coalesced=${coalesced.get()} refused=${refused.get()} calls=${calls.get()} " +
            "keys=${keysAsked.get()} answered=${answered.get()} pending=$p"
    }
}
