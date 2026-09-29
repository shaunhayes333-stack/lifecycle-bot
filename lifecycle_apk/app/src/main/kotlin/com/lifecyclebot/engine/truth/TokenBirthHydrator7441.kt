package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ChokeReliefBus
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.RuntimeProviderAuthority6685
import com.lifecyclebot.engine.TokenMetaCache
import com.lifecyclebot.engine.HealthAwareHttp
import com.lifecyclebot.network.SharedHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object TokenBirthHydrator7441 {
    private const val PAGE_LIMIT = 1000
    private const val PAGES_PER_ATTEMPT = 4
    private const val RETRY_COOLDOWN_MS = 15_000L
    private const val NO_RPC_COOLDOWN_MS = 60_000L

    private data class Progress(var before: String? = null, var oldestBlockMs: Long = Long.MAX_VALUE, var pages: Int = 0)
    private val progress = ConcurrentHashMap<String, Progress>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    private val http by lazy {
        SharedHttpClient.builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    fun request(mint: String) {
        val m = mint.trim()
        if (m.length !in 32..44) return
        val now = System.currentTimeMillis()
        if ((cooldownUntil[m] ?: 0L) > now) return
        if (!inFlight.add(m)) return
        val launched = ChokeReliefBus.launch("TOKEN_BIRTH_HYDRATE_7441", m) {
            try { hydrate(m) } finally { inFlight.remove(m) }
        }
        if (!launched) inFlight.remove(m)
    }

    private fun hydrate(mint: String) {
        val rpcCandidates = RuntimeProviderAuthority6685.rpcCandidates()
        if (rpcCandidates.isEmpty()) {
            cooldownUntil[mint] = System.currentTimeMillis() + NO_RPC_COOLDOWN_MS
            try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_HYDRATE_NO_RPC_7441") } catch (_: Throwable) {}
            return
        }

        val state = progress.computeIfAbsent(mint) { Progress() }
        var anyRpcAnswered = false
        for (rpc in rpcCandidates) {
            var pagesThisAttempt = 0
            while (pagesThisAttempt < PAGES_PER_ATTEMPT) {
                val page = fetchPage(rpc, mint, state.before) ?: break
                anyRpcAnswered = true
                pagesThisAttempt++
                state.pages++
                if (page.length() == 0) {
                    publishIfComplete(mint, state)
                    return
                }
                var lastSig = ""
                for (i in 0 until page.length()) {
                    val row = page.optJSONObject(i) ?: continue
                    if (!row.isNull("err")) continue
                    val btSec = row.optLong("blockTime", 0L)
                    if (btSec > 0L) state.oldestBlockMs = minOf(state.oldestBlockMs, btSec * 1000L)
                    val sig = row.optString("signature", "")
                    if (sig.isNotBlank()) lastSig = sig
                }
                if (page.length() < PAGE_LIMIT) {
                    publishIfComplete(mint, state)
                    return
                }
                if (lastSig.isBlank()) break
                state.before = lastSig
            }
            if (anyRpcAnswered) break
        }

        cooldownUntil[mint] = System.currentTimeMillis() + RETRY_COOLDOWN_MS
        try {
            PipelineHealthCollector.labelInc(if (anyRpcAnswered) "TOKEN_BIRTH_HYDRATE_CONTINUE_7441" else "TOKEN_BIRTH_HYDRATE_RPC_FAIL_7441")
        } catch (_: Throwable) {}
    }

    private fun fetchPage(rpc: String, mint: String, before: String?): JSONArray? {
        val opts = JSONObject().put("limit", PAGE_LIMIT).put("commitment", "confirmed")
        if (!before.isNullOrBlank()) opts.put("before", before)
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", "birth7441")
            .put("method", "getSignaturesForAddress")
            .put("params", JSONArray().put(mint).put(opts))
        val req = Request.Builder()
            .url(rpc)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            HealthAwareHttp.execute(http, req, host = "solana_rpc").use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string() ?: return null).optJSONArray("result")
            }
        } catch (_: Throwable) { null }
    }

    private fun publishIfComplete(mint: String, state: Progress) {
        val ms = state.oldestBlockMs
        if (ms == Long.MAX_VALUE || ms <= 1_577_836_800_000L) {
            cooldownUntil[mint] = System.currentTimeMillis() + NO_RPC_COOLDOWN_MS
            try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_HYDRATE_NO_BLOCKTIME_7441") } catch (_: Throwable) {}
            return
        }
        try {
            val ctx = com.lifecyclebot.AATEApp.appContextOrNull()
            if (ctx != null) TokenMetaCache.get(ctx).register(mint = mint, creationTimeMs = ms)
        } catch (_: Throwable) {}
        progress.remove(mint)
        cooldownUntil.remove(mint)
        try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_RPC_RESOLVED_7441") } catch (_: Throwable) {}
    }
}
