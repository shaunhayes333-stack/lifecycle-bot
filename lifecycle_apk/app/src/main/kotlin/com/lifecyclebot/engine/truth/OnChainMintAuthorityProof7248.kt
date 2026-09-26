package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.HealthAwareHttp
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.RuntimeProviderAuthority6685
import com.lifecyclebot.network.SharedHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * V5.0.7248 — one on-chain authority proof used by safety and the final live gate.
 * V5.0.7365: public RPCs first, configured Helius as the last resort (credits).
 */
object OnChainMintAuthorityProof7248 {
    data class Proof(
        val mintAuthorityDisabled: Boolean,
        val freezeAuthorityDisabled: Boolean,
        val provider: String,
    )

    private val http by lazy {
        SharedHttpClient.builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /**
     * V5.0.7365 — a revoked authority can never be re-set on an SPL mint, so a
     * proof of "both disabled" is permanent: cached for the process. A proof of
     * an ACTIVE authority is cached briefly (the owner may still revoke it). An
     * unanswered probe is negatively cached for a short window so one mint can't
     * be re-probed on every cycle. The same token was re-proved per buy attempt,
     * spending credits each time.
     */
    private val provenDisabled7365 = java.util.concurrent.ConcurrentHashMap<String, Proof>()
    private val activeProof7365 = java.util.concurrent.ConcurrentHashMap<String, Pair<Proof, Long>>()
    private val unansweredAt7365 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val ACTIVE_PROOF_TTL_MS_7365 = 5 * 60_000L
    private const val UNANSWERED_RETRY_MS_7365 = 20_000L

    fun resolve(mint: String, maxProviders: Int = 3): Proof? {
        if (mint.isBlank()) return null
        provenDisabled7365[mint]?.let {
            try { PipelineHealthCollector.labelInc("MINT_AUTHORITY_PROOF_CACHED_7365") } catch (_: Throwable) {}
            return it
        }
        val now7365 = System.currentTimeMillis()
        activeProof7365[mint]?.let { (p, at) -> if (now7365 - at < ACTIVE_PROOF_TTL_MS_7365) return p }
        unansweredAt7365[mint]?.let { at -> if (now7365 - at < UNANSWERED_RETRY_MS_7365) return null }
        val helius = RuntimeProviderAuthority6685.configuredHeliusRpc()
        // V5.0.7365 — public RPCs first, Helius last. With Helius at 429 (credits
        // exhausted) the old order sent every probe to Helius first, and when the
        // candidate list itself led with Helius-hosted URLs all three attempts
        // could be Helius: 45 live buys refused FREEZE_AUTHORITY_UNVERIFIED on
        // 5.0.7364 while the public RPC answered 93% of calls. The proof is the
        // same account read from whichever validator answers; strictness is
        // unchanged (unknown still blocks). Distinct non-Helius hosts are tried
        // first, and Helius is kept only as the last resort.
        val candidates7365 = LinkedHashSet<String>().apply {
            addAll(RuntimeProviderAuthority6685.rpcCandidates())
            if (helius.isNotBlank()) add(helius)
        }
        val (heliusEps7365, publicEps7365) = candidates7365.partition { it.contains("helius", ignoreCase = true) }
        val endpoints = (publicEps7365.take(maxProviders.coerceIn(1, 5)) + heliusEps7365.take(1))

        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", "authority-7248")
            .put("method", "getAccountInfo")
            .put("params", org.json.JSONArray()
                .put(mint)
                .put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed")))
            .toString()

        for (rpc in endpoints) {
            val provider = if (rpc.contains("helius", ignoreCase = true)) "helius" else "solana_rpc"
            try {
                val req = Request.Builder()
                    .url(rpc)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                HealthAwareHttp.execute(http, req, host = provider).use { resp ->
                    if (!resp.isSuccessful) return@use
                    val info = JSONObject(resp.body?.string().orEmpty())
                        .optJSONObject("result")
                        ?.optJSONObject("value")
                        ?.optJSONObject("data")
                        ?.optJSONObject("parsed")
                        ?.optJSONObject("info") ?: return@use
                    val mintAuth = info.opt("mintAuthority")
                    val freezeAuth = info.opt("freezeAuthority")
                    val proof = Proof(
                        mintAuthorityDisabled = mintAuth == null || mintAuth == JSONObject.NULL,
                        freezeAuthorityDisabled = freezeAuth == null || freezeAuth == JSONObject.NULL,
                        provider = provider,
                    )
                    try {
                        PipelineHealthCollector.labelInc("MINT_AUTHORITY_ONCHAIN_PROOF_7248")
                        ForensicLogger.lifecycle(
                            "MINT_AUTHORITY_ONCHAIN_PROOF_7248",
                            "mint=${mint.take(10)} provider=$provider mintDisabled=${proof.mintAuthorityDisabled} freezeDisabled=${proof.freezeAuthorityDisabled}",
                        )
                    } catch (_: Throwable) {}
                    unansweredAt7365.remove(mint)
                    if (proof.freezeAuthorityDisabled && proof.mintAuthorityDisabled) {
                        if (provenDisabled7365.size > 20_000) provenDisabled7365.clear()
                        provenDisabled7365[mint] = proof
                    } else {
                        if (activeProof7365.size > 5_000) activeProof7365.clear()
                        activeProof7365[mint] = proof to System.currentTimeMillis()
                    }
                    return proof
                }
            } catch (_: Throwable) {
                // Rotate to the next configured endpoint; unknown remains fail-closed.
            }
        }
        try { PipelineHealthCollector.labelInc("MINT_AUTHORITY_ONCHAIN_PROOF_UNAVAILABLE_7248") } catch (_: Throwable) {}
        if (unansweredAt7365.size > 5_000) unansweredAt7365.clear()
        unansweredAt7365[mint] = System.currentTimeMillis()
        return null
    }
}
