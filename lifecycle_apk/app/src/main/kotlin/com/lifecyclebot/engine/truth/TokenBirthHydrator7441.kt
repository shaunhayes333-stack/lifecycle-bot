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

    private data class Progress(
        var before: String? = null,
        var oldestBlockMs: Long = Long.MAX_VALUE,
        var pages: Int = 0,
        @Volatile var lastTouchedMs: Long = System.currentTimeMillis(),
    )
    private val progress = ConcurrentHashMap<String, Progress>()
    // V5.0.7502 — partial birth paging is useful resumable evidence, but a
    // mint that has not been requested for a full day is dormant state, not
    // live trading state. Retire dormant non-inflight progress and cooldown.
    private const val DORMANT_PROGRESS_TTL_MS_7502 = 24L * 60L * 60_000L
    private const val PROGRESS_SOFT_CAP_7502 = 8_000
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
        if (progress.size > PROGRESS_SOFT_CAP_7502 || cooldownUntil.size > PROGRESS_SOFT_CAP_7502) {
            val cutoff7502 = now - DORMANT_PROGRESS_TTL_MS_7502
            val dormant7502 = progress.entries
                .filter { it.value.lastTouchedMs < cutoff7502 && it.key !in inFlight }
                .map { it.key }
            dormant7502.forEach { key ->
                progress.remove(key)
                cooldownUntil.remove(key)
            }
            cooldownUntil.entries.removeIf {
                it.value < cutoff7502 &&
                    !inFlight.contains(it.key) &&
                    !progress.containsKey(it.key)
            }
            if (dormant7502.isNotEmpty()) try {
                PipelineHealthCollector.labelInc("TOKEN_BIRTH_DORMANT_STATE_PRUNED_7502")
            } catch (_: Throwable) {}
        }
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
        state.lastTouchedMs = System.currentTimeMillis()
        var anyRpcAnswered = false
        for (rpc in rpcCandidates) {
            var pagesThisAttempt = 0
            while (pagesThisAttempt < PAGES_PER_ATTEMPT) {
                val page = fetchPage(rpc, mint, state.before) ?: break
                anyRpcAnswered = true
                pagesThisAttempt++
                state.pages++
                state.lastTouchedMs = System.currentTimeMillis()
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
