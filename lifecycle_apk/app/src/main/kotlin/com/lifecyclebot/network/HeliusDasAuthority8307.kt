package com.lifecyclebot.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8307 §DAS_AUTHORITY_READS_ONE_BATCH_MANY_READERS — a shared helper for the cheap identity / safety facts DAS states
 * for a fungible mint (getAssetBatch: up to 100 mints in one call): mint authority, freeze authority, token program,
 * decimals, supply and Token-2022 extension names. Batched (one call for every mint asked inside [BATCH_GAP_MS_8307]),
 * cached ([TTL_MS_8307]), single-flight, and never a substitute for an unread fact: [peek8307] returns null until DAS
 * answered for that mint, and a field DAS did not state stays null. Hard-safety callers keep their own proof rules — this
 * only saves them a call. (Selection / safety agents: `HeliusDasAuthority8307.request8307(mint)` then `peek8307(mint)`.)
 */
object HeliusDasAuthority8307 {
    internal const val TTL_MS_8307 = 30L * 60_000L
    private const val BATCH_MAX_8307 = 100
    private const val BATCH_GAP_MS_8307 = 1_500L
    private const val CACHE_CAP_8307 = 4_000

    /**
     * One DAS answer. [mintAuthority] / [freezeAuthority]: "" = DAS stated none (renounced), null = not stated.
     * [extensions]: Token-2022 extension names DAS listed (empty = none listed).
     */
    data class Authority8307(
        val mint: String,
        val mintAuthority: String?,
        val freezeAuthority: String?,
        val tokenProgram: String?,
        val decimals: Int?,
        val supply: Double?,
        val extensions: Set<String>,
        val atMs: Long,
    )

    private val cache = ConcurrentHashMap<String, Authority8307>()
    private val wanted = ConcurrentHashMap.newKeySet<String>()
    private val running = AtomicBoolean(false)
    private val calls = AtomicLong(0)
    private val answered = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val hits = AtomicLong(0)
    private val JSON_8307 = "application/json".toMediaType()

    private val http by lazy { SharedHttpClient.builder().callTimeout(8, TimeUnit.SECONDS).build() }

    /** The cached answer for [mint] inside [maxAgeMs], or null (never asked, not answered, or older). */
    fun peek8307(mint: String, nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = TTL_MS_8307): Authority8307? =
        cache[mint]?.takeIf { nowMs - it.atMs in 0L..maxAgeMs }?.also { hits.incrementAndGet() }

    /** Ask for [mints] (no-op for a fresh cache entry); one background batch call serves every mint asked together. */
    fun request8307(vararg mints: String) {
        val now = System.currentTimeMillis()
        for (m in mints) if (HeliusSolanaScope7819.isSolanaMint7819(m) && peekQuiet(m, now) == null) wanted.add(m)
        if (wanted.isEmpty() || !running.compareAndSet(false, true)) return
        try {
            Thread({
                try {
                    Thread.sleep(BATCH_GAP_MS_8307)
                    while (wanted.isNotEmpty()) {
                        val batch = wanted.take(BATCH_MAX_8307)
                        wanted.removeAll(batch.toSet())
                        fetch8307(batch)
                    }
                } catch (_: Throwable) {
                } finally { running.set(false) }
            }, "helius-das-8307").apply { isDaemon = true }.start()
        } catch (_: Throwable) { running.set(false) }
    }

    private fun peekQuiet(mint: String, nowMs: Long): Authority8307? = cache[mint]?.takeIf { nowMs - it.atMs in 0L..TTL_MS_8307 }

    /** Pure: one getAssetBatch result item as an answer (null without an id). */
    internal fun parseAsset8307(o: JSONObject, nowMs: Long): Authority8307? {
        val id = o.optString("id", "")
        if (id.isBlank()) return null
        val ti = o.optJSONObject("token_info")
        fun auth(key: String): String? = when {
            ti == null || !ti.has(key) -> null
            ti.isNull(key) -> ""
            else -> ti.optString(key, "")
        }
        val ext = o.optJSONObject("mint_extensions")
        val names = LinkedHashSet<String>()
        if (ext != null) { val ks = ext.keys(); while (ks.hasNext()) names.add(ks.next()) }
        return Authority8307(
            mint = id,
            mintAuthority = auth("mint_authority"),
            freezeAuthority = auth("freeze_authority"),
            tokenProgram = ti?.optString("token_program", "")?.takeIf { it.isNotBlank() },
            decimals = ti?.takeIf { it.has("decimals") }?.optInt("decimals", -1)?.takeIf { it >= 0 },
            supply = ti?.takeIf { it.has("supply") }?.optDouble("supply", Double.NaN)?.takeIf { it.isFinite() && it >= 0.0 },
            extensions = names,
            atMs = nowMs,
        )
    }

    private fun fetch8307(batch: List<String>) {
        if (batch.isEmpty()) return
        val key = try { com.lifecyclebot.engine.RuntimeProviderAuthority6685.configuredHeliusKey() } catch (_: Throwable) { "" }
        if (key.isBlank()) { failed.incrementAndGet(); return }
        calls.incrementAndGet()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", "das8307").put("method", "getAssetBatch")
            .put("params", JSONObject().put("ids", JSONArray(batch))).toString()
        val text = try {
            http.newCall(Request.Builder().url("https://mainnet.helius-rpc.com/?api-key=$key").post(body.toRequestBody(JSON_8307)).build())
                .execute().use { r -> if (!r.isSuccessful || HostCircuitInterceptor.isSyntheticBlock(r)) null else r.body?.string() }
        } catch (_: Throwable) { null }
        val arr = try { text?.let { JSONObject(it).optJSONArray("result") } } catch (_: Throwable) { null }
        if (arr == null) { failed.incrementAndGet(); return }
        val now = System.currentTimeMillis()
        if (cache.size > CACHE_CAP_8307) cache.entries.removeIf { now - it.value.atMs > TTL_MS_8307 }
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i)?.let { parseAsset8307(it, now) } ?: continue
            cache[a.mint] = a
            answered.incrementAndGet()
        }
    }

    fun line8307(): String = "calls=${calls.get()} answered=${answered.get()} failed=${failed.get()} hits=${hits.get()} cached=${cache.size}"
}
