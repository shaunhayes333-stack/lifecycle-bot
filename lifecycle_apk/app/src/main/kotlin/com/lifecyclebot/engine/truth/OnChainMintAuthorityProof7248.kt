package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ApiBackoff
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
        /** V5.0.8123 — a Token-2022 extension that can trap a holder (null = none). */
        val hostileExtension8123: String? = null,
        /** V5.0.8136 — a Token-2022 extension that is benign now but has a live authority that can change it. */
        val mutableExtension8136: String? = null,
    )

    /** A missing parsed field is unknown, not a revoked authority. Only mint accounts can prove mint safety. */
    internal fun parseAccountProof8253(value: JSONObject, provider: String): Proof? {
        val owner = value.optString("owner")
        if (owner != "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA" &&
            owner != "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb") return null
        val parsed = value.optJSONObject("data")?.optJSONObject("parsed") ?: return null
        if (parsed.optString("type") != "mint") return null
        val info = parsed.optJSONObject("info") ?: return null
        if (!info.has("mintAuthority") || !info.has("freezeAuthority")) return null
        val mintAuth = info.opt("mintAuthority")
        val freezeAuth = info.opt("freezeAuthority")
        if (listOf(mintAuth, freezeAuth).any { it != JSONObject.NULL && (it !is String || it.isBlank()) }) return null
        return Proof(mintAuth == JSONObject.NULL, freezeAuth == JSONObject.NULL, provider,
            hostileExtension8123(info.optJSONArray("extensions")), mutableExtension8136(info.optJSONArray("extensions")))
    }

    /**
     * V5.0.8136 — extensions whose config a live authority can still change after the proof: a transfer-fee authority
     * can raise the fee, a transfer-hook authority can install a hook. Extensions themselves are fixed at mint init,
     * and a defaultAccountState change needs the (revoked) freeze authority, so everything else is immutable.
     * Pure: [extensions] is the jsonParsed mint `info.extensions` array.
     */
    fun mutableExtension8136(extensions: org.json.JSONArray?): String? {
        if (extensions == null) return null
        for (i in 0 until extensions.length()) {
            val e = extensions.optJSONObject(i) ?: continue
            val st = e.optJSONObject("state")
            when (val name = e.optString("extension", "")) {
                "transferFeeConfig" -> if (liveKey8136(st, "transferFeeConfigAuthority")) return name
                "transferHook" -> if (liveKey8136(st, "authority")) return name
            }
        }
        return null
    }

    private fun liveKey8136(st: JSONObject?, key: String): Boolean {
        val v = st?.opt(key) ?: return false
        return v != JSONObject.NULL && v.toString().isNotBlank()
    }

    /** V5.0.8136 — one chain ask per mint at a time; the live gate, scans and the prefetch join one another. */
    private val flight8136 = SingleFlight8136<Proof>()
    private const val LIVE_JOIN_MS_8136 = 7_000L
    private const val SCAN_JOIN_MS_8136 = 7_000L

    fun inFlight8136(mint: String): Boolean = flight8136.inFlight(mint)

    /** V5.0.8136 — reuse window of a cached non-permanent proof (clean-but-mutable is short, hostile 5 min). */
    private fun cacheTtlMs8136(p: Proof): Long =
        if (p.freezeAuthorityDisabled && p.hostileExtension8123 == null && p.mutableExtension8136 != null) AuthorityProofState8136.CLEAN_MUTABLE_TTL_MS_8136
        else ACTIVE_PROOF_TTL_MS_7365

    /**
     * V5.0.8123 — Token-2022 extensions that can stop a holder selling even with freeze authority revoked:
     * pause the mint, move/burn holders' tokens (permanent delegate), run arbitrary code on every transfer
     * (transfer hook), open accounts frozen, forbid transfers, or tax the sell away (fee > 10%).
     * Pure: [extensions] is the jsonParsed mint `info.extensions` array.
     */
    fun hostileExtension8123(extensions: org.json.JSONArray?): String? {
        if (extensions == null) return null
        for (i in 0 until extensions.length()) {
            val e = extensions.optJSONObject(i) ?: continue
            val name = e.optString("extension", "")
            val st = e.optJSONObject("state")
            when (name) {
                "permanentDelegate" -> if (st?.opt("delegate").let { it != null && it != JSONObject.NULL }) return name
                "pausableConfig" -> if (st?.opt("authority").let { it != null && it != JSONObject.NULL } || st?.optBoolean("paused") == true) return name
                "transferHook" -> if (st?.opt("programId").let { it != null && it != JSONObject.NULL }) return name
                "defaultAccountState" -> if (st?.optString("accountState", "")?.equals("frozen", true) == true) return name
                "nonTransferable" -> return name
                "transferFeeConfig" -> {
                    val bps = maxOf(
                        st?.optJSONObject("newerTransferFee")?.optInt("transferFeeBasisPoints", 0) ?: 0,
                        st?.optJSONObject("olderTransferFee")?.optInt("transferFeeBasisPoints", 0) ?: 0,
                    )
                    if (bps > MAX_TRANSFER_FEE_BPS_8123) return "$name:${bps}bps"
                }
            }
        }
        return null
    }

    private const val MAX_TRANSFER_FEE_BPS_8123 = 1_000

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

    fun resolve(mint: String, maxProviders: Int = 3, liveGate8094: Boolean = false): Proof? {
        if (mint.isBlank()) return null
        provenDisabled7365[mint]?.let {
            try { PipelineHealthCollector.labelInc("MINT_AUTHORITY_PROOF_CACHED_7365") } catch (_: Throwable) {}
            return it
        }
        val now7365 = System.currentTimeMillis()
        activeProof7365[mint]?.let { (p, at) -> if (now7365 - at < cacheTtlMs8136(p)) return p }
        // V5.0.8094 — the live pre-broadcast gate always asks the chain: a scan-time miss cached for 20 s refused live
        // buys outright (4abd6b13a: 13 of 17 live buy attempts refused FREEZE_AUTHORITY_UNVERIFIED).
        if (!liveGate8094) unansweredAt7365[mint]?.let { at -> if (now7365 - at < UNANSWERED_RETRY_MS_7365) return null }
        // V5.0.8136 — single flight: a second caller for the same mint waits for the ask already on the wire. A live
        // gate that joined an ask which came back unanswered asks once more itself (Helius first); a scan does not.
        val flight = flight8136.run(mint, if (liveGate8094) LIVE_JOIN_MS_8136 else SCAN_JOIN_MS_8136) { askChain8136(mint, maxProviders, liveGate8094) }
        // V5.0.8144 — an unanswered scan read is not dropped: the prefetch queue schedules a bounded BACKGROUND retry.
        if (flight.value == null && !flight.joined && !liveGate8094) try { AuthorityProofPrefetch8136.noteScanUnanswered8144(mint) } catch (_: Throwable) {}
        if (flight.value != null || !flight.joined || !liveGate8094) return flight.value
        try { PipelineHealthCollector.labelInc("MINT_AUTHORITY_PROOF_JOINED_UNANSWERED_REASK_8136") } catch (_: Throwable) {}
        return flight8136.run(mint, LIVE_JOIN_MS_8136) { askChain8136(mint, maxProviders, true) }.value
    }

    private fun askChain8136(mint: String, maxProviders: Int, liveGate8094: Boolean): Proof? {
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
        val (heliusEps7365, publicAll7369) = candidates7365.partition { it.contains("helius", ignoreCase = true) }
        // V5.0.7369 — each endpoint has its own backoff key. They all shared
        // "solana_rpc", so one 429 from api.mainnet-beta locked out every public
        // node and 36 live buys on 5.0.7368 still refused FREEZE_AUTHORITY_UNVERIFIED.
        // Endpoints already in lockout are skipped so the three probes go to
        // nodes that can answer.
        val publicEps7365 = publicAll7369.filter { !ApiBackoff.isLockedOut(backoffKey7369(it)) }
            .ifEmpty { publicAll7369 }
        val endpoints = (publicEps7365.take(maxProviders.coerceIn(1, 5)) + heliusEps7365.take(1))

        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", "authority-7248")
            .put("method", "getAccountInfo")
            .put("params", org.json.JSONArray()
                .put(mint)
                .put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed")))
            .toString()

        // V5.0.8130 — a live buy asks Helius first, then every public node (8127: 4 of 5 live buys refused
        // FREEZE_AUTHORITY_UNVERIFIED while publicnode answered 0-33% and Helius answered). Scans keep public-first.
        val ordered8130 = if (liveGate8094) (heliusEps7365.take(1) + publicAll7369).distinct() else endpoints
        for (rpc in ordered8130) {
            val provider = if (rpc.contains("helius", ignoreCase = true)) "helius" else "solana_rpc"
            val hostKey7369 = if (provider == "helius") provider else backoffKey7369(rpc)
            try {
                val req = Request.Builder()
                    .url(rpc)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                HealthAwareHttp.execute(http, req, host = hostKey7369).use { resp ->
                    if (!resp.isSuccessful) { miss8094("HTTP_${resp.code}"); return@use }
                    val value = JSONObject(resp.body?.string().orEmpty()).optJSONObject("result")?.optJSONObject("value")
                    if (value == null) { miss8094("ACCOUNT_NOT_FOUND"); return@use }
                    val proof = parseAccountProof8253(value, provider) ?: run {
                        miss8094("INVALID_OR_INCOMPLETE_MINT_ACCOUNT")
                        return@use // Keep trying eligible RPC providers; this is not a safety verdict.
                    }
                    // V5.0.8136 — an answer impossible after an immutable-clean proof is not trusted either way.
                    val state8136 = AuthorityProofState8136.observe8136(
                        mint, proof.freezeAuthorityDisabled, proof.mintAuthorityDisabled,
                        proof.hostileExtension8123, proof.mutableExtension8136, provider,
                    ).state
                    if (state8136 == AuthorityProofState8136.State.CONTRADICTED) {
                        miss8094("CONTRADICTED_8136")
                        try { ForensicLogger.lifecycle("MINT_AUTHORITY_PROOF_CONTRADICTED_8136", "mint=${mint.take(10)} provider=$provider freezeDisabled=${proof.freezeAuthorityDisabled} mintDisabled=${proof.mintAuthorityDisabled} action=non_executable_reask") } catch (_: Throwable) {}
                        provenDisabled7365.remove(mint)
                        activeProof7365.remove(mint)
                        return@use
                    }
                    try {
                        PipelineHealthCollector.labelInc("MINT_AUTHORITY_ONCHAIN_PROOF_7248")
                        ForensicLogger.lifecycle(
                            "MINT_AUTHORITY_ONCHAIN_PROOF_7248",
                            "mint=${mint.take(10)} provider=$provider mintDisabled=${proof.mintAuthorityDisabled} freezeDisabled=${proof.freezeAuthorityDisabled}",
                        )
                    } catch (_: Throwable) {}
                    unansweredAt7365.remove(mint)
                    if (proof.freezeAuthorityDisabled && proof.mintAuthorityDisabled &&
                        proof.hostileExtension8123 == null && proof.mutableExtension8136 == null) {
                        if (provenDisabled7365.size > 20_000) provenDisabled7365.clear()
                        provenDisabled7365[mint] = proof
                    } else {
                        if (activeProof7365.size > 5_000) activeProof7365.clear()
                        activeProof7365[mint] = proof to System.currentTimeMillis()
                    }
                    // Publish before waking consumers: a retry must read the answer, not race its cache write.
                    CandidateAdmission8136.evidenceArrived8252(mint, setOf("safety", "freeze", "authority"))
                    com.lifecyclebot.engine.DataPipeline8191.noteNearDecision8191(mint)
                    return proof
                }
            } catch (t: Throwable) {
                // Rotate to the next configured endpoint; unknown remains fail-closed.
                miss8094("THREW_${t.javaClass.simpleName.take(24)}")
            }
        }
        try { PipelineHealthCollector.labelInc("MINT_AUTHORITY_ONCHAIN_PROOF_UNAVAILABLE_7248") } catch (_: Throwable) {}
        if (unansweredAt7365.size > 5_000) unansweredAt7365.clear()
        unansweredAt7365[mint] = System.currentTimeMillis()
        AuthorityProofState8136.observeUnanswered8136(mint)
        return null
    }

    /** V5.0.8136 — memory pressure: drop expired proofs; immutable-clean proofs stay (they never change). */
    fun trim8136(): Int {
        val now = System.currentTimeMillis()
        val before = activeProof7365.size + unansweredAt7365.size
        activeProof7365.entries.removeIf { now - it.value.second >= cacheTtlMs8136(it.value.first) }
        unansweredAt7365.entries.removeIf { now - it.value >= UNANSWERED_RETRY_MS_7365 }
        AuthorityProofState8136.trim8136(5_000, now)
        return (before - activeProof7365.size - unansweredAt7365.size).coerceAtLeast(0)
    }

    /** V5.0.8094 — why one endpoint gave no proof, so an UNVERIFIED refusal names its cause. */
    private fun miss8094(kind: String) {
        try { PipelineHealthCollector.labelInc("MINT_AUTHORITY_PROOF_MISS_8094_$kind") } catch (_: Throwable) {}
    }

    private fun backoffKey7369(rpc: String): String =
        "solana_rpc:" + (try { java.net.URI(rpc).host } catch (_: Throwable) { null } ?: rpc)
}
