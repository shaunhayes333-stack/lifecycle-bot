package com.lifecyclebot.network

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.truth.MintSupply8418
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8533 §HELIUS_MATE — the supply a market cap is waiting on, read from Helius DAS in batches.
 *
 * 8529 live (6 min): discoveryMcap8400 unresolved=1,397 (NO_VERIFIED_SUPPLY@jupiter_strict=1,050, @market_hunt=325),
 * MOONSHOT / MANIPULATED NATIVE_MCAP_UNHYDRATED_DEFER=1,381 each — coins no lane could read — while Helius answered 100 %
 * on 74.9k of a 400k daily credit budget and the keyless supply rungs answered 13 of 575. getAssetBatch (showFungible)
 * returns token_info.supply / decimals for up to [BATCH_KEYS] mints a call (10 credits). DiscoveryCapture8400 asks here
 * when price x supply has no supply; the record is accepted into MintSupply8418 (DAS rank; a later mint-account read
 * replaces it) and the next cap read resolves. Bounded: [MAX_CALLS_PER_MIN] calls a minute, one ask per mint per
 * [NEGATIVE_TTL_MS]; never on a caller's thread.
 */
object HeliusSupplyBatch8533 {
    private const val BATCH_KEYS = 100
    private const val GATHER_MS = 1_500L
    private const val MAX_CALLS_PER_MIN = 6
    const val NEGATIVE_TTL_MS = 10L * 60_000L
    private const val MAX_PENDING = 2_000

    private val pending = ConcurrentHashMap<String, Long>()
    private val askedAt = ConcurrentHashMap<String, Long>()
    private val running = AtomicBoolean(false)
    private val callStamps = ArrayDeque<Long>()
    private val requested = AtomicLong(0L)
    private val calls = AtomicLong(0L)
    private val resolved = AtomicLong(0L)
    private val throttled = AtomicLong(0L)
    private val failed = AtomicLong(0L)
    private val nonSolana = AtomicLong(0L)

    private val http by lazy {
        SharedHttpClient.builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }

    /** Pure: may another call go at [nowMs] given the last minute's [stamps]? */
    fun callAllowed8533(stamps: List<Long>, nowMs: Long, perMin: Int = MAX_CALLS_PER_MIN): Boolean =
        stamps.count { nowMs - it in 0L until 60_000L } < perMin

    /** Pure: is [mint] due an ask — never asked, or last asked at least the negative TTL ago? */
    fun due8533(lastAskedMs: Long?, nowMs: Long): Boolean = lastAskedMs == null || nowMs - lastAskedMs >= NEGATIVE_TTL_MS

    /** Queue [mint] for a DAS supply read; a no-op when its supply is already held, it was asked recently, or it is not a Solana mint. */
    fun request(mint: String, nowMs: Long = System.currentTimeMillis()) {
        val m = mint.trim()
        if (m.isBlank()) return
        if (!HeliusSolanaScope7819.isSolanaMint7819(m)) { nonSolana.incrementAndGet(); return }
        if (try { MintSupply8418.wholeOrZero8418(m) } catch (_: Throwable) { 0.0 } > 0.0) return
        if (!due8533(askedAt[m], nowMs)) return
        if (pending.size >= MAX_PENDING) return
        if (pending.putIfAbsent(m, nowMs) != null) return
        requested.incrementAndGet()
        startDrain()
    }

    private fun startDrain() {
        if (!running.compareAndSet(false, true)) return
        try { Thread({ drain() }, "helius-supply-8533").apply { isDaemon = true }.start() } catch (_: Throwable) { running.set(false) }
    }

    private fun drain() {
        try {
            while (true) {
                val until = System.currentTimeMillis() + GATHER_MS
                while (pending.size < BATCH_KEYS && System.currentTimeMillis() < until) {
                    try { Thread.sleep(100L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
                }
                val now = System.currentTimeMillis()
                val ids = pending.keys.take(BATCH_KEYS)
                if (ids.isEmpty()) break
                val allowed = synchronized(callStamps) {
                    while (callStamps.isNotEmpty() && now - callStamps.first() >= 60_000L) callStamps.removeFirst()
                    if (callAllowed8533(callStamps.toList(), now)) { callStamps.addLast(now); true } else false
                }
                if (!allowed) {
                    throttled.incrementAndGet()
                    try { Thread.sleep(5_000L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
                    continue
                }
                for (id in ids) { pending.remove(id); askedAt[id] = now }
                if (askedAt.size > 20_000) askedAt.entries.removeIf { now - it.value >= NEGATIVE_TTL_MS }
                readBatch(ids, now)
            }
        } catch (_: Throwable) {
        } finally {
            running.set(false)
            if (pending.isNotEmpty()) startDrain()
        }
    }

    private fun readBatch(ids: List<String>, nowMs: Long) {
        val url = try { com.lifecyclebot.engine.RuntimeProviderAuthority6685.configuredHeliusRpc() } catch (_: Throwable) { "" }
        if (url.isBlank()) { failed.incrementAndGet(); return }
        calls.incrementAndGet()
        try {
            val payload = """{"jsonrpc":"2.0","id":"8533","method":"getAssetBatch",""" +
                """"params":{"ids":[${ids.joinToString(",") { "\"$it\"" }}],"displayOptions":{"showFungible":true}}}"""
            val req = Request.Builder().url(url).post(payload.toRequestBody("application/json".toMediaType())).build()
            com.lifecyclebot.engine.HealthAwareHttp.execute(http, req, host = "helius").use { resp ->
                if (!resp.isSuccessful) { failed.incrementAndGet(); return }
                val body = resp.body?.string() ?: run { failed.incrementAndGet(); return }
                val arr = JSONObject(body).optJSONArray("result") ?: run { failed.incrementAndGet(); return }
                var n = 0L
                for (i in 0 until arr.length()) {
                    val s = MintSupply8418.fromDasAsset8418(arr.optJSONObject(i), nowMs) ?: continue
                    if (MintSupply8418.accept8418(s, notifyMetrics = false)) n++
                }
                resolved.addAndGet(n)
                if (n > 0L) try { PipelineHealthCollector.labelAdd("HELIUS_DAS_SUPPLY_RESOLVED_8533", n) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) { failed.incrementAndGet() }
    }

    fun statusLine(): String =
        "heliusSupply8533[requested=${requested.get()} calls=${calls.get()} resolved=${resolved.get()} throttled=${throttled.get()} " +
            "failed=${failed.get()} nonSolana=${nonSolana.get()} pending=${pending.size}]"
}
