package com.lifecyclebot.network

import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8750 §EVERY_PUMPSWAP_POOL_HAS_ITS_COIN — external review of 21641a619 (verified on the 8748 report): the PumpSwap
 * program stream decoded 248 swaps and taped none — unknownPool=248. A swap event names its pool, not its coin, and the
 * pool -> coin map was only filled by a watched coin's own decode or a DexScreener row, so the program stream (the one
 * feed that sees every migrated coin) was blind to every coin the bot was not already watching.
 *
 * An unknown pool is now read from chain: queued here, and every [FLUSH_MS] up to [MAX_KEYS] pool accounts go in one
 * getMultipleAccounts (base64, the keyless market ladder). The PumpSwap pool account holds its base mint at byte 43 and its
 * quote mint at byte 75 (discriminator 8, bump 1, index 2, creator 32); a pool owned by the PumpSwap program and quoted in
 * wSOL, whose base is a pump.fun coin (the swap decoder reads 6 decimals), is mapped (PumpSwapTape8307.notePoolMint8307) and
 * the coin's later swaps are taped. A pool that is not one, or that
 * is quoted in another token, is remembered as unusable so it is never asked again.
 */
object PumpSwapPools8750 {
    private const val MAX_KEYS = 100
    private const val FLUSH_MS = 3_000L
    private const val MAX_PENDING = 2_000
    private const val BASE_MINT_AT = 43
    private const val QUOTE_MINT_AT = 75

    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val unusable = ConcurrentHashMap.newKeySet<String>()
    private val scheduled = AtomicBoolean(false)
    private val asked = AtomicLong(); private val mapped = AtomicLong(); private val notPumpSwap = AtomicLong()
    private val nonSolQuote = AtomicLong(); private val failedCalls = AtomicLong(); private val nonPumpBase = AtomicLong()

    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AATE-pumpswap-pools-8750").apply { isDaemon = true }
    }
    private val http: OkHttpClient by lazy {
        SharedHttpClient.builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    }

    /** Pure: the (base mint, quote mint) a PumpSwap pool account's bytes name, or null when too short. */
    fun mintsOf8750(data: ByteArray): Pair<String, String>? {
        if (data.size < QUOTE_MINT_AT + 32) return null
        return try {
            io.github.novacrypto.base58.Base58.base58Encode(data.copyOfRange(BASE_MINT_AT, BASE_MINT_AT + 32)) to
                io.github.novacrypto.base58.Base58.base58Encode(data.copyOfRange(QUOTE_MINT_AT, QUOTE_MINT_AT + 32))
        } catch (_: Throwable) { null }
    }

    /** PumpSwapTape8307: a swap on [pool] whose coin is unknown. Queued once; never blocks. */
    fun resolve8750(pool: String) {
        if (pool.isBlank() || pool in unusable || pending.size >= MAX_PENDING || !pending.add(pool)) return
        if (scheduled.compareAndSet(false, true)) {
            try { scheduler.schedule({ drain() }, FLUSH_MS, TimeUnit.MILLISECONDS) } catch (_: Throwable) { scheduled.set(false) }
        }
    }

    private fun drain() {
        try {
            val batch = pending.take(MAX_KEYS)
            pending.removeAll(batch.toSet())
            if (batch.isNotEmpty()) readBatch(batch)
        } catch (_: Throwable) {
        } finally {
            scheduled.set(false)
            if (pending.isNotEmpty() && scheduled.compareAndSet(false, true)) {
                try { scheduler.schedule({ drain() }, FLUSH_MS, TimeUnit.MILLISECONDS) } catch (_: Throwable) { scheduled.set(false) }
            }
        }
    }

    private fun readBatch(pools: List<String>) {
        asked.addAndGet(pools.size.toLong())
        val params = JSONArray().put(JSONArray().apply { pools.forEach { put(it) } })
            .put(JSONObject().put("encoding", "base64").put("commitment", "confirmed"))
        val arr = KeylessRpc8359.post8359(http, "getMultipleAccounts", params, "8750pool", maxRungs = 3)?.optJSONArray("value")
        if (arr == null) { failedCalls.incrementAndGet(); return }   // the pools are asked again at their next swap
        for ((i, pool) in pools.withIndex()) {
            val acc = arr.optJSONObject(i) ?: run { unusable.add(pool); notPumpSwap.incrementAndGet(); null } ?: continue
            if (acc.optString("owner") != com.lifecyclebot.engine.truth.OnChainDepth8038.PUMP_AMM) { unusable.add(pool); notPumpSwap.incrementAndGet(); continue }
            val b64 = acc.optJSONArray("data")?.optString(0).orEmpty()
            val bytes = try { java.util.Base64.getDecoder().decode(b64) } catch (_: Throwable) { null } ?: continue
            val (base, quote) = mintsOf8750(bytes) ?: continue
            if (quote != PoolBalanceSwap8734.WSOL_8734) { unusable.add(pool); nonSolQuote.incrementAndGet(); continue }
            // the swap decoder reads the base at 6 decimals (pump.fun's); another token's decimals would scale its price
            if (!PumpCurveKeys7269.isPumpMint8658(base)) { unusable.add(pool); nonPumpBase.incrementAndGet(); continue }
            PumpSwapTape8307.notePoolMint8307(pool, base, verifiedSolQuote8751 = true)
            mapped.incrementAndGet()
        }
        if (unusable.size > 20_000) unusable.clear()
        try { PipelineHealthCollector.labelInc("PUMPSWAP_POOLS_RESOLVED_BATCH_8750") } catch (_: Throwable) {}
    }

    fun statusLine8750(): String = "pumpSwapPools8750[asked=${asked.get()} mapped=${mapped.get()} notPumpSwap=${notPumpSwap.get()} " +
        "nonSolQuote=${nonSolQuote.get()} nonPumpBase=${nonPumpBase.get()} failedCalls=${failedCalls.get()} pending=${pending.size}]"
}
