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
 * Configured Helius is deliberately first; public RPCs are bounded fallbacks.
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

    fun resolve(mint: String, maxProviders: Int = 3): Proof? {
        if (mint.isBlank()) return null
        val helius = RuntimeProviderAuthority6685.configuredHeliusRpc()
        val endpoints = LinkedHashSet<String>().apply {
            if (helius.isNotBlank()) add(helius)
            addAll(RuntimeProviderAuthority6685.rpcCandidates())
        }.take(maxProviders.coerceIn(1, 5))

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
                    return proof
                }
            } catch (_: Throwable) {
                // Rotate to the next configured endpoint; unknown remains fail-closed.
            }
        }
        try { PipelineHealthCollector.labelInc("MINT_AUTHORITY_ONCHAIN_PROOF_UNAVAILABLE_7248") } catch (_: Throwable) {}
        return null
    }
}
