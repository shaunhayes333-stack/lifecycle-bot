package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.sell.SellRouteFinality8144.Balance8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.BalanceVerdict8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.ChainStatus8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.ProviderRead8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.Read8144
import com.lifecyclebot.network.SignedTxLedger8085
import com.lifecyclebot.network.SolanaWallet
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * V5.0.8144 — the chain reads behind SellRouteFinality8144's pure decisions (P0-E).
 *
 *  - [chainStatus8144]: what the chain says about a pending sell signature — the SignedTxLedger8085 row when it
 *    already carries chain truth, otherwise one bounded getSignatureStatuses.
 *  - [balance8144]: the mint's balance read from up to three DISTINCT RPC providers in parallel (owner + mint filter,
 *    both token programs, frozen accounts reported as frozen). One provider omitting the mint is never an empty wallet;
 *    a failed read is never absence. Verdicts are cached for [BALANCE_TTL_MS_8144] so a ghost is not re-probed every tick.
 */
object SellRouteProbe8144 {

    private const val STATUS_BUDGET_MS_8144 = 1_500L
    private const val EXPIRY_READ_EXTRA_MS_8153 = 700L
    private const val BALANCE_BUDGET_MS_8144 = 2_500L
    private const val BALANCE_TTL_MS_8144 = 15_000L
    private const val MAX_PROVIDERS_8144 = 3

    private val firstSeenMs8144 = ConcurrentHashMap<String, Long>()
    private data class Cached8144(val verdict: BalanceVerdict8144, val atMs: Long)
    private val balanceCache8144 = ConcurrentHashMap<String, Cached8144>()
    private val jsonMt8144 = "application/json".toMediaType()

    private val pool8144: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "sellroute-probe-8144").apply { isDaemon = true } }
    }
    private val http8144 by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(BALANCE_BUDGET_MS_8144, TimeUnit.MILLISECONDS)
            .callTimeout(BALANCE_BUDGET_MS_8144 + 500L, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Age of a pending signature: its ledger row's sign time, else the first time this probe saw it. */
    fun pendingAgeMs8144(sig: String, nowMs: Long = System.currentTimeMillis()): Long {
        val created = try { SignedTxLedger8085.get(sig)?.createdAtMs } catch (_: Throwable) { null }
        if (created != null && created > 0L) return (nowMs - created).coerceAtLeast(0L)
        if (firstSeenMs8144.size > 2_000) firstSeenMs8144.entries.removeIf { nowMs - it.value > 30L * 60_000L }
        val first = firstSeenMs8144.putIfAbsent(sig, nowMs) ?: nowMs
        return (nowMs - first).coerceAtLeast(0L)
    }

    /** Chain truth for [sig]: ledger first, else one bounded status read. Never throws. */
    fun chainStatus8144(wallet: SolanaWallet?, sig: String): ChainStatus8144 {
        when (try { SignedTxLedger8085.get(sig)?.state } catch (_: Throwable) { null }) {
            SignedTxLedger8085.State.CONFIRMED -> return ChainStatus8144.LANDED
            SignedTxLedger8085.State.FAILED_ON_CHAIN -> return ChainStatus8144.FAILED_ON_CHAIN
            SignedTxLedger8085.State.EXPIRED_PROVEN, SignedTxLedger8085.State.SUBMIT_REJECTED -> return ChainStatus8144.EXPIRED_PROVEN
            else -> {}
        }
        val w = wallet ?: return ChainStatus8144.RPC_FAILED
        // V5.0.8153 — an inconclusive verification no longer parks the next exit until the 180 s age fallback or the
        // wallet resolver's 15 s cadence: the signature's own blockhash is read FIRST (finalized), then its status; unseen
        // after a blockhash the chain already calls invalid is EXPIRED_PROVEN (SignedTxLedger8085.expiredProven), and the
        // ledger row moves with it. Unknown / valid blockhash: the signature is still waited on, never re-signed.
        val blockhash8153 = try { SignedTxLedger8085.get(sig)?.blockhash.orEmpty() } catch (_: Throwable) { "" }
        val fut = try {
            pool8144.submit(java.util.concurrent.Callable {
                val valid8153 = if (blockhash8153.isBlank()) null else blockhashValid8153(w, blockhash8153)
                val params = JSONArray().put(JSONArray().put(sig)).put(JSONObject().put("searchTransactionHistory", true))
                val resp = w.rpcCall("getSignatureStatuses", params)
                if (resp.has("error")) ChainStatus8144.RPC_FAILED
                else {
                    val values = resp.optJSONObject("result")?.optJSONArray("value")
                    if (values == null || values.length() != 1) ChainStatus8144.RPC_FAILED
                    else if (values.isNull(0)) expiry8153(sig, SellRouteFinality8144.statusWithExpiry8153(false, SellRouteFinality8144.statusFrom8144(false, null, null), valid8153))
                    else {
                        val v = values.getJSONObject(0)
                        val err = if (v.has("err") && !v.isNull("err")) v.opt("err")?.toString() else null
                        SellRouteFinality8144.statusFrom8144(true, err, v.optString("confirmationStatus", ""))
                    }
                }
            })
        } catch (_: Throwable) { return ChainStatus8144.RPC_FAILED }
        val budget8153 = if (blockhash8153.isBlank()) STATUS_BUDGET_MS_8144 else STATUS_BUDGET_MS_8144 + EXPIRY_READ_EXTRA_MS_8153
        return try { fut.get(budget8153, TimeUnit.MILLISECONDS) } catch (_: Throwable) {
            try { fut.cancel(true) } catch (_: Throwable) {}
            ChainStatus8144.RPC_FAILED
        }
    }

    /** V5.0.8153 — isBlockhashValid at finalized commitment: true / false, or null (no proof either way). */
    private fun blockhashValid8153(w: SolanaWallet, blockhash: String): Boolean? = try {
        val r = w.rpcCall("isBlockhashValid", JSONArray().put(blockhash).put(JSONObject().put("commitment", "finalized")))
        if (r.has("error")) null else (r.optJSONObject("result")?.opt("value") as? Boolean)
    } catch (_: Throwable) { null }

    /** V5.0.8153 — a proven expiry moves the ledger row too (same transition the wallet resolver makes). */
    private fun expiry8153(sig: String, status: ChainStatus8144): ChainStatus8144 {
        if (status == ChainStatus8144.EXPIRED_PROVEN) try {
            SignedTxLedger8085.transition(sig, SignedTxLedger8085.State.EXPIRED_PROVEN, "blockhash_invalid_signature_unseen_8153")
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SELL_PENDING_SIG_EXPIRY_PROVEN_8153")
        } catch (_: Throwable) {}
        return status
    }

    /** V5.0.8153 — the cached multi-provider verdict for [mint] with its read time, or null. */
    fun cachedBalanceAt8153(mint: String, nowMs: Long = System.currentTimeMillis()): Pair<BalanceVerdict8144, Long>? {
        val c = balanceCache8144[mint] ?: return null
        return if (nowMs - c.atMs <= BALANCE_TTL_MS_8144) c.verdict to c.atMs else null
    }

    /** The cached multi-provider verdict for [mint], or null. */
    fun cachedBalance8144(mint: String, nowMs: Long = System.currentTimeMillis()): BalanceVerdict8144? {
        val c = balanceCache8144[mint] ?: return null
        return if (nowMs - c.atMs <= BALANCE_TTL_MS_8144) c.verdict else null
    }

    /** Multi-provider balance verdict for (owner, mint), bounded at ~2.5 s. Never throws. */
    fun balance8144(wallet: SolanaWallet?, mint: String): BalanceVerdict8144 {
        val now = System.currentTimeMillis()
        cachedBalance8144(mint, now)?.let { return it }
        return balanceFresh8153(wallet, mint)
    }

    /**
     * V5.0.8153 — the same multi-provider read without the 15 s verdict cache: an emergency's parallel read must never
     * size a sale from a verdict taken before the previous attempt's order moved the quantity.
     */
    fun balanceFresh8153(wallet: SolanaWallet?, mint: String): BalanceVerdict8144 {
        val w = wallet ?: return SellRouteFinality8144.classifyBalance8144(emptyList())
        val owner = try { w.publicKeyB58 } catch (_: Throwable) { "" }
        if (owner.isBlank() || mint.isBlank()) return SellRouteFinality8144.classifyBalance8144(emptyList())
        val endpoints = distinctProviders8144(try {
            com.lifecyclebot.engine.RuntimeProviderAuthority6685.rpcCandidates(w.rpcUrl)
        } catch (_: Throwable) { listOf(w.rpcUrl) })
        val futures = endpoints.map { ep ->
            ep to try { pool8144.submit(java.util.concurrent.Callable { readOne8144(ep, owner, mint) }) } catch (_: Throwable) { null }
        }
        val deadline = System.currentTimeMillis() + BALANCE_BUDGET_MS_8144
        val reads = futures.map { (ep, f) ->
            val host = host8144(ep)
            if (f == null) ProviderRead8144(host, Read8144.FAILED)
            else try {
                f.get((deadline - System.currentTimeMillis()).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                try { f.cancel(true) } catch (_: Throwable) {}
                ProviderRead8144(host, Read8144.FAILED)
            }
        }
        val verdict = SellRouteFinality8144.classifyBalance8144(reads, SellAmountAuthority.SELL_DUST_RAW)
        balanceCache8144[mint] = Cached8144(verdict, System.currentTimeMillis())
        if (balanceCache8144.size > 2_000) balanceCache8144.entries.removeIf { System.currentTimeMillis() - it.value.atMs > BALANCE_TTL_MS_8144 }
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SELL_ROUTE_BALANCE_${verdict.verdict.name}_8144")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "SELL_ROUTE_BALANCE_PROBE_8144",
                "mint=${mint.take(10)} verdict=${verdict.verdict.name} held=${verdict.heldProviders} absent=${verdict.absentProviders} " +
                    "failed=${verdict.failedProviders} raw=${verdict.raw} providers=${reads.joinToString(",") { "${it.provider}:${it.kind.name}" }}",
            )
        } catch (_: Throwable) {}
        return verdict
    }

    /** Pure: up to three endpoints with distinct hosts, in ladder order. */
    fun distinctProviders8144(candidates: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (c in candidates) {
            val h = host8144(c)
            if (h.isBlank() || !seen.add(h)) continue
            out.add(c)
            if (out.size >= MAX_PROVIDERS_8144) break
        }
        return out
    }

    fun host8144(url: String): String = try { java.net.URI(url.trim()).host.orEmpty().lowercase() } catch (_: Throwable) { "" }

    /** One provider: getTokenAccountsByOwner(owner, mint) — both token programs answer a mint filter. */
    private fun readOne8144(endpoint: String, owner: String, mint: String): ProviderRead8144 {
        val host = host8144(endpoint)
        return try {
            val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "getTokenAccountsByOwner")
                .put("params", JSONArray().put(owner).put(JSONObject().put("mint", mint))
                    .put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed")))
                .toString()
            val req = Request.Builder().url(endpoint)
                .header("Content-Type", "application/json")
                .header(com.lifecyclebot.network.HostCircuitInterceptor.PROBE_HEADER_6976, "1")
                .post(body.toRequestBody(jsonMt8144)).build()
            http8144.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful || resp.header(com.lifecyclebot.network.HostCircuitInterceptor.SYNTHETIC_HEADER_6969) != null) {
                    return ProviderRead8144(host, Read8144.FAILED)
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (json.has("error")) return ProviderRead8144(host, Read8144.FAILED)
                val arr = json.optJSONObject("result")?.optJSONArray("value") ?: return ProviderRead8144(host, Read8144.FAILED)
                var total = BigInteger.ZERO
                var decimals = -1
                for (i in 0 until arr.length()) {
                    val info = arr.optJSONObject(i)?.optJSONObject("account")?.optJSONObject("data")
                        ?.optJSONObject("parsed")?.optJSONObject("info") ?: continue
                    if (info.optString("state", "").equals("frozen", ignoreCase = true)) return ProviderRead8144(host, Read8144.FROZEN)
                    val ta = info.optJSONObject("tokenAmount") ?: continue
                    val amt = ta.optString("amount", "").trim().toBigIntegerOrNull() ?: continue
                    total = total.add(amt)
                    decimals = ta.optInt("decimals", decimals)
                }
                if (total.signum() > 0 && decimals in 0..18) ProviderRead8144(host, Read8144.HELD, total, decimals)
                else ProviderRead8144(host, Read8144.ABSENT)
            }
        } catch (_: Throwable) {
            ProviderRead8144(host, Read8144.FAILED)
        }
    }

    /** True when the verdict proves the wallet holds sellable tokens. */
    fun held8144(v: BalanceVerdict8144): Boolean = v.verdict == Balance8144.HELD && v.raw.signum() > 0 && v.decimals in 0..18

    internal fun resetForTest8144() { firstSeenMs8144.clear(); balanceCache8144.clear() }
}
