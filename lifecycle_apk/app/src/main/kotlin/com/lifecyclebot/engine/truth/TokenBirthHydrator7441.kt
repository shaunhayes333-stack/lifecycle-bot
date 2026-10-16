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
    // V5.0.7770 — a launch has a page of history; a mint still paging after this
    // many is an established token whose pool time is its birth evidence. Without
    // the watchlist-time stand-in every intake token can reach this hydrator, so
    // its RPC demand needs a ceiling.
    private const val MAX_PAGES_7770 = 40
    private const val PAGE_BUDGET_COOLDOWN_MS_7770 = 60L * 60_000L

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

    // V5.0.8144 §BIRTH_FROM_EVERY_WITNESS. 5.0.8142: 2,015 PROJECT_SNIPER refusals as BIRTH_METADATA_HYDRATING. Three
    // defects here: (1) the page cursor was the last SUCCESSFUL signature, so a page that ended in failed transactions
    // (a hot launch's failed bot buys) was re-fetched, and a page of only failed ones never advanced until the page
    // budget; (2) the oldest row's missing blockTime ended in NO_BLOCKTIME although its slot answers getBlockTime;
    // (3) nothing learned from a partial read, so a token whose first page already reached back hours stayed "unknown"
    // instead of proven old. pump.fun's own coin record (created_timestamp) is asked first for a pump mint, every page
    // publishes its lower bound (AdmissionBirth8144.noteHistoryFloor8144), and paging stops once the token is proven
    // older than the widest launch window — its exact birth no longer changes any lane's decision.
    private const val ESTABLISHED_PROOF_MS_8144 = 6L * 60L * 60_000L
    private const val PUMP_RECORD_RETRY_MS_8144 = 10L * 60_000L
    private val pumpRecordAskedAt8144 = ConcurrentHashMap<String, Long>()

    /** One getSignaturesForAddress row: [blockTimeSec] 0 when the node had none; [failed] = the transaction errored. */
    data class SigRow8144(val signature: String, val blockTimeSec: Long, val slot: Long, val failed: Boolean)

    /**
     * A page read: [oldestBlockMs] of its successful rows (Long.MAX_VALUE when none had a time), [oldestOkSlotNoTime] the
     * slot of its oldest successful row when that row had no blockTime (0 otherwise), [cursor] the LAST row's signature
     * whatever its status (the next page starts before it), [complete] = the history ends on this page.
     */
    data class PageScan8144(val oldestBlockMs: Long, val oldestOkSlotNoTime: Long, val cursor: String, val complete: Boolean)

    /** Pure: one page of newest-first signatures for the mint. Failed rows never date the mint, but they do page. */
    fun scanPage8144(rows: List<SigRow8144>, pageLimit: Int): PageScan8144 {
        var oldest = Long.MAX_VALUE
        var lastOk: SigRow8144? = null
        for (r in rows) {
            if (r.failed) continue
            lastOk = r
            if (r.blockTimeSec > 0L) oldest = minOf(oldest, r.blockTimeSec * 1000L)
        }
        val slotNoTime = lastOk?.takeIf { it.blockTimeSec <= 0L && it.slot > 0L }?.slot ?: 0L
        return PageScan8144(oldest, slotNoTime, rows.lastOrNull()?.signature.orEmpty(), rows.size < pageLimit)
    }

    /** Pure: the history read so far reaches back far enough that no launch window can still contain the birth. */
    fun provenEstablished8144(oldestBlockMs: Long, nowMs: Long): Boolean =
        oldestBlockMs != Long.MAX_VALUE && oldestBlockMs > 0L && nowMs - oldestBlockMs > ESTABLISHED_PROOF_MS_8144

    private fun rows8144(page: JSONArray): List<SigRow8144> {
        val out = ArrayList<SigRow8144>(page.length())
        for (i in 0 until page.length()) {
            val row = page.optJSONObject(i) ?: continue
            out += SigRow8144(row.optString("signature", ""), row.optLong("blockTime", 0L), row.optLong("slot", 0L), !row.isNull("err"))
        }
        return out
    }

    /** pump.fun's coin record for a pump mint: its created_timestamp IS the create event. True when it resolved the birth. */
    private fun pumpRecord8144(mint: String): Boolean {
        if (!mint.endsWith("pump")) return false
        val now = System.currentTimeMillis()
        val asked = pumpRecordAskedAt8144[mint]
        if (asked != null && now - asked < PUMP_RECORD_RETRY_MS_8144) return false
        if (pumpRecordAskedAt8144.size > PROGRESS_SOFT_CAP_7502) pumpRecordAskedAt8144.entries.removeIf { now - it.value > PUMP_RECORD_RETRY_MS_8144 }
        pumpRecordAskedAt8144[mint] = now
        val req = Request.Builder().url("https://frontend-api-v3.pump.fun/coins/$mint").header("User-Agent", "Mozilla/5.0").build()
        val created = try {
            HealthAwareHttp.execute(http, req, host = "pumpfun").use { resp ->
                if (!resp.isSuccessful) 0L else JSONObject(resp.body?.string() ?: "{}").optLong("created_timestamp", 0L)
            }
        } catch (_: Throwable) { 0L }
        val ms = AdmissionBirth8144.epochMs8144(created)
        if (ms <= 1_577_836_800_000L || ms > now + 60_000L) return false
        AdmissionBirth8144.notePumpCoinRecord8144(mint, ms)
        try {
            val ctx = com.lifecyclebot.AATEApp.appContextOrNull()
            if (ctx != null) TokenMetaCache.get(ctx).register(mint = mint, creationTimeMs = ms)
        } catch (_: Throwable) {}
        progress.remove(mint)
        cooldownUntil.remove(mint)
        try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_PUMP_RECORD_RESOLVED_8144") } catch (_: Throwable) {}
        return true
    }

    private fun blockTimeMs8144(rpc: String, slot: Long): Long? {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", "birth8144").put("method", "getBlockTime").put("params", JSONArray().put(slot))
        val req = Request.Builder().url(rpc).header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return try {
            HealthAwareHttp.execute(http, req, host = "solana_rpc").use { resp ->
                if (!resp.isSuccessful) null
                else JSONObject(resp.body?.string() ?: "{}").optLong("result", 0L).takeIf { it > 0L }?.let { it * 1000L }
            }
        } catch (_: Throwable) { null }
    }

    private fun hydrate(mint: String) {
        if (pumpRecord8144(mint)) return
        val rpcCandidates = RuntimeProviderAuthority6685.rpcCandidates()
        if (rpcCandidates.isEmpty()) {
            cooldownUntil[mint] = System.currentTimeMillis() + NO_RPC_COOLDOWN_MS
            try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_HYDRATE_NO_RPC_7441") } catch (_: Throwable) {}
            return
        }

        val state = progress.computeIfAbsent(mint) { Progress() }
        state.lastTouchedMs = System.currentTimeMillis()
        if (state.pages >= MAX_PAGES_7770) {
            // V5.0.8144 — the budget ends the paging, not the evidence: what was read still proves the age's lower bound.
            if (state.oldestBlockMs != Long.MAX_VALUE) AdmissionBirth8144.noteHistoryFloor8144(mint, state.oldestBlockMs)
            cooldownUntil[mint] = System.currentTimeMillis() + PAGE_BUDGET_COOLDOWN_MS_7770
            try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_HYDRATE_PAGE_BUDGET_7770") } catch (_: Throwable) {}
            return
        }
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
                // V5.0.8144 — pure page read: the cursor is the last row whatever its status; a missing oldest blockTime
                // is asked of its slot; every page's oldest successful time is a published lower bound of the age.
                val scan = scanPage8144(rows8144(page), PAGE_LIMIT)
                state.oldestBlockMs = minOf(state.oldestBlockMs, scan.oldestBlockMs)
                if (scan.complete && scan.oldestOkSlotNoTime > 0L) {
                    // The creation row itself had no time: a newer row's time is NOT the birth (it would read younger).
                    val bt = blockTimeMs8144(rpc, scan.oldestOkSlotNoTime)
                    if (bt == null) {
                        if (state.oldestBlockMs != Long.MAX_VALUE) AdmissionBirth8144.noteHistoryFloor8144(mint, state.oldestBlockMs)
                        cooldownUntil[mint] = System.currentTimeMillis() + RETRY_COOLDOWN_MS
                        try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_SLOT_TIME_PENDING_8144") } catch (_: Throwable) {}
                        return
                    }
                    state.oldestBlockMs = minOf(state.oldestBlockMs, bt)
                }
                if (scan.complete) {
                    publishIfComplete(mint, state)
                    return
                }
                if (state.oldestBlockMs != Long.MAX_VALUE) AdmissionBirth8144.noteHistoryFloor8144(mint, state.oldestBlockMs)
                if (provenEstablished8144(state.oldestBlockMs, System.currentTimeMillis())) {
                    cooldownUntil[mint] = System.currentTimeMillis() + PAGE_BUDGET_COOLDOWN_MS_7770
                    try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_PROVEN_ESTABLISHED_8144") } catch (_: Throwable) {}
                    return
                }
                if (scan.cursor.isBlank()) break
                state.before = scan.cursor
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
        AdmissionBirth8144.noteOldestSignature8144(mint, ms)  // V5.0.8144 — in memory too (the meta cache reads disk lazily)
        progress.remove(mint)
        cooldownUntil.remove(mint)
        try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_RPC_RESOLVED_7441") } catch (_: Throwable) {}
    }
}
