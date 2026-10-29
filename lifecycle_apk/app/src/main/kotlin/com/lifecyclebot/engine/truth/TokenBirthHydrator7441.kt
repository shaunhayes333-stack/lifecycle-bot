package com.lifecyclebot.engine.truth

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
        /** V5.0.8418 — signature of the oldest successful row read so far: the only candidate creation transaction. */
        var oldestOkSig8418: String = "",
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
        if (establishedWitness8417(m, now)) {
            try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_WALK_ALREADY_ESTABLISHED_8417") } catch (_: Throwable) {}
            return
        }
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
        requests8265.incrementAndGet()
        if ((cooldownUntil[m] ?: 0L) > now) { cooldownSkips8265.incrementAndGet(); return }
        if (!inFlight.add(m)) return
        // V5.0.8287 — the birth walk runs on its own small pool, not the report-only ChokeReliefBus (see [pool8287]).
        val launched = try { pool8287.execute(Walk8287(m)); true } catch (_: Throwable) { false }
        if (!launched) { inFlight.remove(m); busDropped8265.incrementAndGet() } else launched8265.incrementAndGet()
    }

    /**
     * V5.0.8287 §A_BIRTH_IS_A_DECISION_INPUT_NOT_A_LOG_LINE. 8286: PROJECT_SNIPER candidateN=0 with
     * NATIVE_BIRTH_METADATA_HYDRATING=470 (+ RECOVERY_BIRTH_UNKNOWN_8168=1325). A witnessed create frame resolves at once
     * (AdmissionBirth8144.createFrames -> CanonicalTokenBirthTime7440); every other launch waits on this walk, and the walk
     * ran on ChokeReliefBus — the bounded REPORT-ONLY side-effect bus (8 low-priority threads shared with every FDG stamp,
     * alert and hook, dropped past 256 in flight). Each walk blocks on up to a dozen seconds of RPC, so a launch's birth
     * queued behind telemetry, or was dropped, until the sniper's ~3-minute window had passed. It now has three daemon
     * threads of its own; when its queue is full the OLDEST request goes (a birth asked minutes ago no longer decides a
     * launch window), the newest launch is walked first.
     */
    private const val POOL_THREADS_8287 = 3
    private const val POOL_QUEUE_8287 = 256
    private val dropped8287 = java.util.concurrent.atomic.AtomicLong(0)

    /** One birth walk; the mint leaves [inFlight] when it ends or when its queued walk is dropped for a newer one. */
    private class Walk8287(val mint: String) : Runnable {
        override fun run() {
            try { TokenBirthHydrator7441.hydrate(mint) } catch (_: Throwable) {
            } finally { TokenBirthHydrator7441.inFlight.remove(mint) }
        }
    }

    /** Queue full: the oldest queued walk is dropped (its mint may be asked again), the new one is queued. */
    private val dropOldest8287 = java.util.concurrent.RejectedExecutionHandler { r, ex ->
        if (ex.isShutdown) {
            (r as? Walk8287)?.let { inFlight.remove(it.mint) }
            return@RejectedExecutionHandler
        }
        (ex.queue.poll() as? Walk8287)?.let { inFlight.remove(it.mint); dropped8287.incrementAndGet() }
        if (!ex.queue.offer(r)) (r as? Walk8287)?.let { inFlight.remove(it.mint) }
    }

    private val pool8287 = java.util.concurrent.ThreadPoolExecutor(
        POOL_THREADS_8287, POOL_THREADS_8287, 30L, TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(POOL_QUEUE_8287),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "birth-hydrate-8287").apply { isDaemon = true } },
        dropOldest8287,
    ).apply { allowCoreThreadTimeOut(true) }

    // V5.0.8265 — 8261: PROJECT_SNIPER refused 1,653 evaluations as BIRTH_METADATA_HYDRATING with no line saying whether
    // this hydrator was asked, launched, dropped by the relief bus, answered by an RPC, or resolved. Now it says.
    private val requests8265 = java.util.concurrent.atomic.AtomicLong(0)
    private val launched8265 = java.util.concurrent.atomic.AtomicLong(0)
    private val busDropped8265 = java.util.concurrent.atomic.AtomicLong(0)
    private val cooldownSkips8265 = java.util.concurrent.atomic.AtomicLong(0)
    private val resolved8265 = java.util.concurrent.atomic.AtomicLong(0)
    private val noRpc8265 = java.util.concurrent.atomic.AtomicLong(0)
    private val noAnswer8265 = java.util.concurrent.atomic.AtomicLong(0)
    fun statusLine7441(): String =
        "birthHydrator7441[requests=${requests8265.get()} launched=${launched8265.get()} busDropped=${busDropped8265.get()} " +
            "cooldownSkips=${cooldownSkips8265.get()} dropped8287=${dropped8287.get()} resolved=${resolved8265.get()} mintInitProven8418=${initProven8418.get()} unproven8418=${initUnproven8418.get()} noRpc=${noRpc8265.get()} noAnswer=${noAnswer8265.get()} " +
            "inFlight=${inFlight.size} progress=${progress.size} cooling=${cooldownUntil.size}] ${walkOutcomes8435()} ${StoredBirth8418.statusLine8418()}"

    /**
     * V5.0.8435 — 8434: launched=1769 resolved=13, and where the other walks ended (another page owed, the page budget, a
     * slot with no time, proven established, a truncated history) was only in counters the export never printed.
     */
    private fun walkOutcomes8435(): String = try {
        val l = PipelineHealthCollector.labelSnapshotByPrefix6607("TOKEN_BIRTH_")
        val keys = listOf("HYDRATE_CONTINUE_7441" to "continue", "HYDRATE_RPC_FAIL_7441" to "rpcFail", "HYDRATE_PAGE_BUDGET_7770" to "pageBudget",
            "SLOT_TIME_PENDING_8144" to "slotTime", "PROVEN_ESTABLISHED_8144" to "established", "HISTORY_TRUNCATED_UNPROVEN_8418" to "truncated",
            "MINT_INIT_TX_PENDING_8418" to "initTxPending", "HYDRATE_NO_BLOCKTIME_7441" to "noBlockTime", "PUMP_RECORD_RESOLVED_8144" to "pumpRecord",
            "WALK_SKIPPED_KNOWN_8346" to "skippedKnown")
        "walk8435{" + keys.joinToString(" ") { (k, n) -> "$n=${l.entries.firstOrNull { it.key.endsWith(k) }?.value ?: 0L}" } + "}"
    } catch (_: Throwable) { "walk8435{-}" }

    // V5.0.8144 §BIRTH_FROM_EVERY_WITNESS. 5.0.8142: 2,015 PROJECT_SNIPER refusals as BIRTH_METADATA_HYDRATING. Three
    // defects here: (1) the page cursor was the last SUCCESSFUL signature, so a page that ended in failed transactions
    // (a hot launch's failed bot buys) was re-fetched, and a page of only failed ones never advanced until the page
    // budget; (2) the oldest row's missing blockTime ended in NO_BLOCKTIME although its slot answers getBlockTime;
    // (3) nothing learned from a partial read, so a token whose first page already reached back hours stayed "unknown"
    // instead of proven old. pump.fun's own coin record (created_timestamp) is asked first for a pump mint, every page
    // publishes its lower bound (AdmissionBirth8144.noteHistoryFloor8144), and paging stops once the token is proven
    // older than the widest launch window — its exact birth no longer changes any lane's decision.
    private const val ESTABLISHED_PROOF_MS_8144 = 6L * 60L * 60_000L
    internal fun establishedWitness8417(mint: String, nowMs: Long): Boolean {
        val existedAt = AdmissionBirth8144.existenceFloorMs8417(mint) ?: return false
        return nowMs - existedAt >= ESTABLISHED_PROOF_MS_8144
    }
    private const val PUMP_RECORD_RETRY_MS_8144 = 10L * 60_000L
    private val pumpRecordAskedAt8144 = ConcurrentHashMap<String, Long>()

    /** One getSignaturesForAddress row: [blockTimeSec] 0 when the node had none; [failed] = the transaction errored. */
    data class SigRow8144(val signature: String, val blockTimeSec: Long, val slot: Long, val failed: Boolean)

    /**
     * A page read: [oldestBlockMs] of its successful rows (Long.MAX_VALUE when none had a time), [oldestOkSlotNoTime] the
     * slot of its oldest successful row when that row had no blockTime (0 otherwise), [cursor] the LAST row's signature
     * whatever its status (the next page starts before it), [complete] = the history ends on this page.
     */
    data class PageScan8144(
        val oldestBlockMs: Long, val oldestOkSlotNoTime: Long, val cursor: String, val complete: Boolean,
        /** V5.0.8418 — the oldest successful row's signature on this page ("" when none succeeded). */
        val oldestOkSignature8418: String = "",
    )

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
        return PageScan8144(oldest, slotNoTime, rows.lastOrNull()?.signature.orEmpty(), rows.size < pageLimit, lastOk?.signature.orEmpty())
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

    /**
     * Pure (V5.0.8346): a queued walk is spent only when no direct witness has arrived since it was asked. The cheapest
     * certain source wins: a PumpPortal create frame, a chain launch note, pump.fun's coin record or the mint's own
     * bonding-curve pair creation (DexScreener, already fetched for liquidity) IS the birth — no RPC page is paid for it.
     */
    fun walkNeeded8346(directWitnessMs: Long?): Boolean = directWitnessMs == null

    private fun hydrate(mint: String) {
        // A pool answer may arrive after this walk queued. The same six-hour lower bound already terminates
        // signature paging below; use the evidence we have before spending any provider request.
        if (establishedWitness8417(mint, System.currentTimeMillis())) return
        // V5.0.8346 — 8344: launched 109 / resolved 4. A birth answered by a frame, a launch note or a pair while this walk
        // waited in the queue still cost its pages; now the walk ends here and the witness is the answer.
        val known8346 = try { LaunchBirth8169.launchBirth8169(mint, System.currentTimeMillis())?.birthMs } catch (_: Throwable) { null }
        if (!walkNeeded8346(known8346)) {
            progress.remove(mint)
            cooldownUntil.remove(mint)
            try { MarketEvidence8346.noteWalkSkippedKnown8346() } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_WALK_SKIPPED_KNOWN_8346") } catch (_: Throwable) {}
            return
        }
        if (pumpRecord8144(mint)) return
        val rpcCandidates = RuntimeProviderAuthority6685.marketReadCandidates8359(null, "getSignaturesForAddress")   // V5.0.8359 — the birth walk is a market read: keyless first
        if (rpcCandidates.isEmpty()) {
            cooldownUntil[mint] = System.currentTimeMillis() + NO_RPC_COOLDOWN_MS
            noRpc8265.incrementAndGet()
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
                    proveAndPublish8418(rpc, mint, state)
                    return
                }
                // V5.0.8144 — pure page read: the cursor is the last row whatever its status; a missing oldest blockTime
                // is asked of its slot; every page's oldest successful time is a published lower bound of the age.
                val scan = scanPage8144(rows8144(page), PAGE_LIMIT)
                state.oldestBlockMs = minOf(state.oldestBlockMs, scan.oldestBlockMs)
                if (scan.oldestOkSignature8418.isNotBlank()) state.oldestOkSig8418 = scan.oldestOkSignature8418
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
                    proveAndPublish8418(rpc, mint, state)
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
        if (!anyRpcAnswered) noAnswer8265.incrementAndGet()
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
        if (!com.lifecyclebot.network.KeylessRpc8359.beforeCall8359(rpc)) return null   // V5.0.8359 — keyless host governor
        return try {
            HealthAwareHttp.execute(http, req, host = "solana_rpc").use { resp ->
                if (!resp.isSuccessful) { com.lifecyclebot.network.KeylessRpc8359.afterCall8359(rpc, resp.code); return null }
                val root8359 = JSONObject(resp.body?.string() ?: return null)
                com.lifecyclebot.network.KeylessRpc8359.afterCall8359(rpc, resp.code, root8359.optJSONObject("error")?.optInt("code", -1) ?: 0)
                root8359.optJSONArray("result")
            }
        } catch (_: Throwable) { com.lifecyclebot.network.KeylessRpc8359.afterCall8359(rpc, 0); null }
    }

    /**
     * V5.0.8418 §OLDEST_RETURNED_IS_NOT_CREATION. A history that "ends" on a page is only the history this node kept: a
     * pruned or truncated ledger ends early, and its oldest row is then some later transfer, read as a far younger
     * birth. The creation is proven only by the transaction that ran spl-token / token-2022 InitializeMint or
     * InitializeMint2 on THIS mint. The oldest successful row is fetched; when it initialised the mint its block time is
     * the birth, otherwise what was read stays a lower bound (HISTORY_FLOOR) and the birth stays unproven.
     */
    enum class MintInit8418 { PROVEN, NOT_IN_TRANSACTION, TRANSACTION_UNAVAILABLE }

    private val MINT_INIT_TYPES_8418 = setOf("initializeMint", "initializeMint2")
    private val TOKEN_PROGRAMS_8418 = setOf(
        "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb",
    )
    private val TOKEN_PROGRAM_NAMES_8418 = setOf("spl-token", "spl-token-2022")

    /** Pure: [tx] (getTransaction "result", jsonParsed) ran InitializeMint / InitializeMint2 on [mint], top level or inner. */
    fun mintInitProof8418(tx: JSONObject?, mint: String): MintInit8418 {
        if (tx == null || mint.isBlank()) return MintInit8418.TRANSACTION_UNAVAILABLE
        if (tx.optJSONObject("meta")?.let { !it.isNull("err") } == true) return MintInit8418.NOT_IN_TRANSACTION
        val ixs = ArrayList<JSONObject>()
        val top = tx.optJSONObject("transaction")?.optJSONObject("message")?.optJSONArray("instructions")
        if (top != null) for (i in 0 until top.length()) top.optJSONObject(i)?.let { ixs.add(it) }
        val inner = tx.optJSONObject("meta")?.optJSONArray("innerInstructions")
        if (inner != null) for (i in 0 until inner.length()) {
            val arr = inner.optJSONObject(i)?.optJSONArray("instructions") ?: continue
            for (j in 0 until arr.length()) arr.optJSONObject(j)?.let { ixs.add(it) }
        }
        for (ix in ixs) {
            val tokenProgram = ix.optString("programId", "") in TOKEN_PROGRAMS_8418 || ix.optString("program", "") in TOKEN_PROGRAM_NAMES_8418
            if (!tokenProgram) continue
            val parsed = ix.optJSONObject("parsed") ?: continue
            if (parsed.optString("type", "") !in MINT_INIT_TYPES_8418) continue
            if (parsed.optJSONObject("info")?.optString("mint", "") == mint) return MintInit8418.PROVEN
        }
        return MintInit8418.NOT_IN_TRANSACTION
    }

    private val initProven8418 = java.util.concurrent.atomic.AtomicLong(0)
    private val initUnproven8418 = java.util.concurrent.atomic.AtomicLong(0)

    private fun fetchTx8418(rpc: String, signature: String): JSONObject? {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", "birth8418").put("method", "getTransaction")
            .put("params", JSONArray().put(signature).put(JSONObject().put("encoding", "jsonParsed")
                .put("maxSupportedTransactionVersion", 0).put("commitment", "confirmed")))
        val req = Request.Builder().url(rpc).header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        if (!com.lifecyclebot.network.KeylessRpc8359.beforeCall8359(rpc)) return null
        return try {
            HealthAwareHttp.execute(http, req, host = "solana_rpc").use { resp ->
                if (!resp.isSuccessful) { com.lifecyclebot.network.KeylessRpc8359.afterCall8359(rpc, resp.code); return null }
                val root = JSONObject(resp.body?.string() ?: return null)
                com.lifecyclebot.network.KeylessRpc8359.afterCall8359(rpc, resp.code, root.optJSONObject("error")?.optInt("code", -1) ?: 0)
                root.optJSONObject("result")
            }
        } catch (_: Throwable) { com.lifecyclebot.network.KeylessRpc8359.afterCall8359(rpc, 0); null }
    }

    /** V5.0.8418 — the history reached its end: publish the birth only when its oldest row initialised the mint. */
    private fun proveAndPublish8418(rpc: String, mint: String, state: Progress) {
        val floor = state.oldestBlockMs
        val sig = state.oldestOkSig8418
        val tx = if (sig.isBlank()) null else fetchTx8418(rpc, sig)
        val verdict = if (sig.isBlank()) MintInit8418.NOT_IN_TRANSACTION else mintInitProof8418(tx, mint)
        if (verdict == MintInit8418.PROVEN) {
            val txMs = (tx?.optLong("blockTime", 0L) ?: 0L).takeIf { it > 0L }?.let { it * 1000L }
            if (txMs != null) state.oldestBlockMs = minOf(state.oldestBlockMs, txMs)
            initProven8418.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_MINT_INIT_PROVEN_8418") } catch (_: Throwable) {}
            publishIfComplete(mint, state)
            return
        }
        // Unproven: what was read is still a lower bound of the age, never the birth.
        if (floor != Long.MAX_VALUE) AdmissionBirth8144.noteHistoryFloor8144(mint, floor)
        if (verdict == MintInit8418.TRANSACTION_UNAVAILABLE) {
            cooldownUntil[mint] = System.currentTimeMillis() + RETRY_COOLDOWN_MS
            try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_MINT_INIT_TX_PENDING_8418") } catch (_: Throwable) {}
            return
        }
        initUnproven8418.incrementAndGet()
        progress.remove(mint)
        cooldownUntil[mint] = System.currentTimeMillis() + PAGE_BUDGET_COOLDOWN_MS_7770
        try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_HISTORY_TRUNCATED_UNPROVEN_8418") } catch (_: Throwable) {}
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
            if (ctx != null) TokenMetaCache.get(ctx).register(mint = mint, creationTimeMs = ms, birthProven8418 = true)   // V5.0.8418 — InitializeMint proven
        } catch (_: Throwable) {}
        AdmissionBirth8144.noteOldestSignature8144(mint, ms)  // V5.0.8144 — in memory too (the meta cache reads disk lazily)
        resolved8265.incrementAndGet()
        progress.remove(mint)
        cooldownUntil.remove(mint)
        try { PipelineHealthCollector.labelInc("TOKEN_BIRTH_RPC_RESOLVED_7441") } catch (_: Throwable) {}
    }
}
