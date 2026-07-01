package com.lifecyclebot.network

import com.iwebpp.crypto.TweetNaclFast
import io.github.novacrypto.base58.Base58
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.net.ssl.TrustManager

/**
 * Minimal Solana wallet for:
 *  - Keypair loading from base58 private key
 *  - SOL balance reads via JSON-RPC
 *  - Signing versioned transactions via TweetNaCl (ed25519)
 *  - Broadcasting via sendTransaction
 *
 * NOTE: Versioned transactions produced by Jupiter v6 use a compact-array
 * prefix and an account key table. We sign the message bytes directly
 * (the bytes after the signatures prefix in a VersionedTransaction).
 * This works for Jupiter's single-signer swap transactions.
 */
class SolanaWallet(privateKeyB58: String, val rpcUrl: String) {

    private val keyPair: TweetNaclFast.Signature.KeyPair
    val publicKeyB58: String

    init {
        var raw: ByteArray? = null
        try {
            raw = Base58.base58Decode(privateKeyB58)
            // Solana keypairs are 64 bytes: 32 seed + 32 pubkey
            // TweetNaCl expects the 64-byte secret key
            val secretKey = when (raw.size) {
                64   -> raw.copyOf()
                32   -> {
                    val kp = TweetNaclFast.Signature.keyPair_fromSeed(raw)
                    kp.secretKey
                }
                else -> throw IllegalArgumentException(
                    "Invalid private key length: ${raw.size}. Expected 32 or 64 bytes."
                )
            }
            keyPair      = TweetNaclFast.Signature.keyPair_fromSecretKey(secretKey)
            publicKeyB58 = Base58.base58Encode(keyPair.publicKey)
            // Zero the secret key bytes from the local variable immediately
            secretKey.fill(0)
        } finally {
            // Zero the raw decoded bytes regardless of success/failure
            raw?.fill(0)
        }
    }

    /**
     * Returns a copy of the public key only — never exposes secret key bytes.
     * The keyPair object is kept in memory for signing but never serialised or logged.
     */
    fun getPublicKeyOnly(): String = publicKeyB58

    private val http = SharedHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MT = "application/json".toMediaType()
    private val idGen   = AtomicLong(1)

    companion object {
        // V5.0.4595 — WALLET RPC ROUND-ROBIN + PER-ENDPOINT COOLDOWN
        // (operator P0 "harden api and rpc connections").
        // Prior behavior: walletRpcEndpointsForTokenSnapshot() returned the
        // primary rpcUrl + FALLBACK_RPCS in fixed order every cycle. If
        // Helius (index 0) returned 429/500, we'd retry Helius first the
        // NEXT cycle too — hammering the same rate-limited provider and
        // slowing the whole loop while it retries. This state adds:
        //   1. AtomicInteger round-robin index rotates the STARTING endpoint
        //      each cycle so load spreads across all healthy providers.
        //   2. ConcurrentHashMap tracks unhealthy endpoints with a 30s
        //      cooldown — an endpoint that returns 429/500/TLS-fail gets
        //      skipped for 30s before being retried.
        // Fail-safe: if ALL endpoints are in cooldown, fall back to the
        // full unfiltered list so the wallet snapshot NEVER goes dark.
        private val rpcRoundRobinIndex = java.util.concurrent.atomic.AtomicInteger(0)
        private val rpcCooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private const val RPC_COOLDOWN_MS = 30_000L

        internal fun markEndpointUnhealthy(endpoint: String, code: String) {
            try {
                rpcCooldownUntil[endpoint] = System.currentTimeMillis() + RPC_COOLDOWN_MS
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("WALLET_RPC_ENDPOINT_COOLDOWN_4595")
            } catch (_: Throwable) {}
        }

        internal fun applyRoundRobin(endpoints: List<String>): List<String> {
            if (endpoints.size <= 1) return endpoints
            val now = System.currentTimeMillis()
            val healthy = endpoints.filter { (rpcCooldownUntil[it] ?: 0L) <= now }
            val pool = if (healthy.isNotEmpty()) healthy else endpoints
            val startIdx = (rpcRoundRobinIndex.getAndIncrement() % pool.size).let { if (it < 0) it + pool.size else it }
            return pool.drop(startIdx) + pool.take(startIdx)
        }

        // V5.9.999b — Dedicated daemon executor for bounded RPC wrappers.
        //
        // Why a separate pool (not ForkJoinPool.commonPool nor Dispatchers.IO):
        //   * ForkJoinPool.commonPool is shared with Kotlin parallel collections
        //     and `parallelStream`; a hung RPC starves them too.
        //   * Dispatchers.IO threads are scarce (default 64) and feed the bot
        //     loop, exit sweep, persistence saves, etc. If a 60-180s RPC hang
        //     parked one of those threads we'd lose work-slicing capacity for
        //     OTHER coroutines.
        // This pool's only job is to host orphan RPC threads after a timeout
        // fires. Daemon threads let JVM exit cleanly even if a thread is
        // still blocked in OkHttp socket read.
        @Volatile private var rpcBoundedExec: java.util.concurrent.ExecutorService? = null
        private fun boundedRpcExecutor(): java.util.concurrent.ExecutorService {
            rpcBoundedExec?.let { return it }
            synchronized(this) {
                rpcBoundedExec?.let { return it }
                val tf = java.util.concurrent.ThreadFactory { r ->
                    Thread(r, "SolanaWallet-RPC-bounded").apply {
                        isDaemon = true
                        priority = Thread.NORM_PRIORITY
                    }
                }
                val ex = java.util.concurrent.Executors.newCachedThreadPool(tf)
                rpcBoundedExec = ex
                return ex
            }
        }

        @Volatile private var unsafeWalletRpcHttp: OkHttpClient? = null
        private fun unsafeWalletRpcClient(): OkHttpClient {
            unsafeWalletRpcHttp?.let { return it }
            synchronized(this) {
                unsafeWalletRpcHttp?.let { return it }
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                }
                val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom()) }
                val client = SharedHttpClient.builder()
                    .sslSocketFactory(ssl.socketFactory, trustAll)
                    .hostnameVerifier { _, _ -> true }
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                unsafeWalletRpcHttp = client
                return client
            }
        }

        private fun isTlsTrustFailure(t: Throwable?): Boolean {
            var cur = t
            var depth = 0
            while (cur != null && depth++ < 8) {
                val msg = cur.message ?: ""
                if (cur is SSLHandshakeException) return true
                if (cur is java.security.cert.CertPathValidatorException) return true
                if (msg.contains("Trust anchor", ignoreCase = true) || msg.contains("certification path", ignoreCase = true)) return true
                cur = cur.cause
            }
            return false
        }
    }

    // ── balance ────────────────────────────────────────────

    fun getSolBalance(): Double {
        // V5.9.771 — EMERGENT-MEME #5: ANR / main-thread guard.
        // Operator dump V5.9.770: ANR top blocking call sites
        //   [41] com.lifecyclebot.network.SolanaWallet.rpc
        //   [3]  com.lifecyclebot.network.SolanaWallet.getSolBalance
        //   Max frame gap 77433 ms, stall 47% of uptime.
        // Required by spec: "No getSolBalance call may run on
        // Dispatchers.Main." We fail FAST with a forensic emit
        // instead of blocking — any caller invoking this from main
        // gets an immediate exception they can catch, and the
        // forensic dump reveals the culprit. Background refreshes
        // continue to work normally.
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
            try {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "WALLET_RPC_ON_MAIN_THREAD",
                    "fn=getSolBalance blocked=true — caller must use Dispatchers.IO",
                )
            } catch (_: Throwable) {}
            throw IllegalStateException("SolanaWallet.getSolBalance() called from Dispatchers.Main — wrap in withContext(Dispatchers.IO)")
        }
        var lastEx: Exception? = null
        repeat(3) { attempt ->
            try {
                val resp = rpc("getBalance", JSONArray().put(publicKeyB58))
                val error = resp.optJSONObject("error")
                if (error != null) {
                    throw RuntimeException("RPC error: ${error.optString("message", "unknown")}")
                }
                val lam = resp.optJSONObject("result")?.optLong("value", 0L) ?: 0L
                return lam / 1_000_000_000.0
            } catch (e: Exception) {
                lastEx = e
                if (attempt < 2) Thread.sleep((300L shl attempt))
            }
        }
        android.util.Log.e("SolanaWallet", "getSolBalance failed after 3 attempts: ${lastEx?.message}", lastEx)
        throw lastEx ?: RuntimeException("getSolBalance: unknown error")
    }

    // ── sign + broadcast ───────────────────────────────────

    /**
     * Takes a base64 versioned transaction from Jupiter, signs it, broadcasts it.
     * Returns the transaction signature string.
     * 
     * If useJito=true, sends via Jito bundle for MEV protection.
     */
    fun signAndSend(txBase64: String, useJito: Boolean = false, jitoTipLamports: Long = 10000, senderCompatible: Boolean = false): String {
        val txBytes    = android.util.Base64.decode(txBase64, android.util.Base64.DEFAULT)
        val signedBytes = signVersionedTx(txBytes)
        val signedB64   = android.util.Base64.encodeToString(signedBytes, android.util.Base64.NO_WRAP)
        
        // V5.9.1524 — HELIUS SENDER FIRST. When useJito=true the router baked a
        // Jito tip into this tx (PumpPortal priorityFee==tip / Jupiter prio fee),
        // so it satisfies Sender's mandatory tip requirement. Sender dual-routes
        // to validators + Jito and lands in ~1 slot — this is the primary cure
        // for "broadcast times out / sells stick". On ANY Sender miss we fall
        // straight through to the legacy Jito-bundle → RPC path below.
        if (useJito && senderCompatible) {
            val senderSig = try {
                com.lifecyclebot.network.HeliusSender.send(signedB64)
            } catch (_: Throwable) { null }
            if (!senderSig.isNullOrBlank()) {
                com.lifecyclebot.engine.ErrorLogger.info("SolanaWallet",
                    "⚡ Broadcast via Helius Sender: ${senderSig.take(16)}…")
                return senderSig
            }
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("HELIUS_SENDER_DEGRADED",
                "action=rotate_sender_not_jupiter err=${com.lifecyclebot.network.HeliusSender.lastError?.take(80)}") } catch (_: Throwable) {}
            com.lifecyclebot.engine.ErrorLogger.warn("SolanaWallet",
                "⚠️ Helius Sender miss (${com.lifecyclebot.network.HeliusSender.lastError}) — rotating to Jito/RPC sender (NOT Jupiter)")
        }

        // Try Jito MEV protection if enabled
        if (useJito) {
            try {
                val jitoResult = kotlinx.coroutines.runBlocking {
                    com.lifecyclebot.engine.JitoMEVProtection.sendProtectedTransaction(
                        signedTxBase64 = signedB64,
                        tipLamports = jitoTipLamports,
                        maxRetries = 2,
                    )
                }
                
                if (jitoResult.success && jitoResult.bundleId != null) {
                    com.lifecyclebot.engine.ErrorLogger.info("SolanaWallet", 
                        "⚡ Jito bundle sent: ${jitoResult.bundleId?.take(16)}... landed=${jitoResult.landed}")
                    
                    // If Jito succeeded and landed, we need to get the actual tx signature
                    // For now, fall through to normal send as backup confirmation
                    if (jitoResult.landed && jitoResult.signature != null) {
                        return jitoResult.signature
                    }
                } else {
                    // V5.9.1533 — spec item 8: a JITO_PAYLOAD_INVALID (undecodable signed
                    // tx) fails fast here WITHOUT burning bundle retries; we fall straight
                    // through to the single normal-RPC send below.
                    val jErr = jitoResult.error ?: ""
                    if (jErr.startsWith("JITO_PAYLOAD_INVALID")) {
                        try { com.lifecyclebot.engine.ForensicLogger.lifecycle("JITO_FALLBACK_SINGLE_RPC",
                            "reason=payload_invalid action=single_rpc_send") } catch (_: Throwable) {}
                    }
                    com.lifecyclebot.engine.ErrorLogger.warn("SolanaWallet", 
                        "⚠️ Jito failed: ${jitoResult.error}, falling back to normal RPC")
                }
            } catch (e: Exception) {
                com.lifecyclebot.engine.ErrorLogger.warn("SolanaWallet", 
                    "⚠️ Jito exception: ${e.message}, falling back to normal RPC")
            }
        }

        // Normal RPC send (fallback or if Jito disabled)
        val params = JSONArray()
            .put(signedB64)
            .put(JSONObject().put("encoding", "base64")
                             .put("preflightCommitment", "confirmed"))

        val resp = rpc("sendTransaction", params)
        
        // ═══════════════════════════════════════════════════════════════════
        // CRITICAL FIX: optString returns "" (empty string) on RPC errors,
        // NOT null. We must check for both null AND empty string, otherwise
        // sells silently "succeed" with no actual transaction broadcast!
        // ═══════════════════════════════════════════════════════════════════
        val result = resp.optString("result", "")
        if (result.isBlank()) {
            val errorObj = resp.optJSONObject("error")
            val errorMsg = errorObj?.optString("message", "unknown RPC error") ?: "unknown error"
            throw RuntimeException("sendTransaction failed: $errorMsg")
        }
        return result
    }

    /**
     * Versioned Transaction binary layout:
     *   [1 byte: num_signatures]
     *   [num_signatures * 64 bytes: signatures (zeroed initially)]
     *   [message bytes...]
     *
     * We locate the message start, sign those bytes, and write our signature
     * into the first signature slot.
     */
    private fun signVersionedTx(txBytes: ByteArray): ByteArray {
        val numSigs    = txBytes[0].toInt() and 0xFF
        val sigsEnd    = 1 + numSigs * 64
        val msgBytes   = txBytes.sliceArray(sigsEnd until txBytes.size)

        val sig        = TweetNaclFast.Signature(null, keyPair.secretKey)
            .detached(msgBytes)

        val result     = txBytes.copyOf()
        // Write sig into first slot (offset 1)
        System.arraycopy(sig, 0, result, 1, 64)
        return result
    }

    // ── transaction confirmation ──────────────────────────

    /**
     * Wait for a transaction to be confirmed on-chain.
     * Polls getSignatureStatuses every 2 seconds for up to 45 seconds.
     * Throws if transaction fails or times out.
     *
     * This is critical — without confirmation we don't know if the trade
     * actually executed. Especially important for sells: if a sell tx fails
     * silently we'd clear the position while still holding tokens.
     */
    fun awaitConfirmation(signature: String, timeoutMs: Long = 45_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var pollCount = 0
        while (System.currentTimeMillis() < deadline) {
            try {
                val sigArray = JSONArray().put(signature)
                val params   = JSONArray()
                    .put(sigArray)
                    .put(JSONObject().put("searchTransactionHistory", true))
                val resp = rpc("getSignatureStatuses", params)
                val value = resp.optJSONObject("result")
                    ?.optJSONArray("value")
                    ?.let { arr ->
                        // value is array of status-or-null; find first non-null
                        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull()
                    }

                when {
                    value == null -> {
                        // Not yet propagated — normal for first few seconds
                        pollCount++
                        // After 15 polls (~30s) with no result, likely blockhash expired
                        if (pollCount > 15) {
                            throw RuntimeException(
                                "Transaction not found after ${pollCount * 2}s — " +
                                "blockhash may have expired: $signature")
                        }
                        Thread.sleep(2_000)
                    }
                    value.has("err") && !value.isNull("err") -> {
                        val err = value.optJSONObject("err")?.toString() ?: value.opt("err").toString()
                        throw RuntimeException("Transaction failed on-chain: $err (sig=$signature)")
                    }
                    else -> {
                        val status = value.optString("confirmationStatus", "")
                        if (status in listOf("confirmed", "finalized")) return true
                        Thread.sleep(1_500)
                    }
                }
            } catch (e: RuntimeException) {
                throw e   // re-throw explicit failures
            } catch (_: Exception) {
                Thread.sleep(2_000)
            }
        }
        throw RuntimeException("Confirmation timeout after ${timeoutMs/1000}s: $signature")
    }

    /**
     * Sign, send, and await confirmation.
     * Returns signature only after confirmed — throws on failure.
     * 
     * @param useJito If true, attempts to send via Jito bundle for MEV protection
     * @param jitoTipLamports Tip amount for Jito validators
     * @param ultraRequestId If provided, uses Jupiter Ultra execute endpoint for better MEV protection
     * @param jupiterApiKey Jupiter API key for Ultra execute (required since March 2025)
     */
    fun signSendAndConfirm(
        txBase64: String, 
        useJito: Boolean = false, 
        jitoTipLamports: Long = 10000,
        ultraRequestId: String? = null,
        jupiterApiKey: String = "",
        isRfqRoute: Boolean = false,  // NEW: RFQ routes can't self-broadcast
        senderCompatible: Boolean = false,
    ): String {
        // If we have an Ultra requestId, use Jupiter's execute endpoint
        // This provides built-in MEV protection via Jupiter Beam
        if (!ultraRequestId.isNullOrBlank()) {
            return signAndExecuteUltra(txBase64, ultraRequestId, jupiterApiKey, isRfqRoute)
        }
        
        val sig = signAndSend(txBase64, useJito, jitoTipLamports, senderCompatible)
        awaitConfirmation(sig)
        return sig
    }
    
    /**
     * Sign transaction and execute via Jupiter Ultra API.
     * Jupiter handles MEV protection and optimal execution.
     * 
     * CRITICAL FIX #3: SMART FALLBACK BASED ON ROUTER TYPE
     * - RFQ routes (iris, dflow, okx): MUST use /execute - retry /execute on failure
     * - Metis/JupiterZ routes: Can self-broadcast via RPC as fallback
     * 
     * @param jupiterApiKey The Jupiter API key from config
     * @param isRfqRoute True if this is an RFQ route that requires Jupiter's signature
     */
    private fun signAndExecuteUltra(
        txBase64: String, 
        requestId: String, 
        jupiterApiKey: String = "",
        isRfqRoute: Boolean = false,
    ): String {
        val txBytes = android.util.Base64.decode(txBase64, android.util.Base64.DEFAULT)
        val signedBytes = signVersionedTx(txBytes)
        val signedB64 = android.util.Base64.encodeToString(signedBytes, android.util.Base64.NO_WRAP)
        
        // ═══════════════════════════════════════════════════════════════════
        // CRITICAL FIX: Retry /execute for RFQ routes, only self-broadcast for Metis
        // RFQ routes have Jupiter-held market maker signatures that only /execute can add
        // ═══════════════════════════════════════════════════════════════════
        val maxAttempts = 3
        var lastException: Exception? = null
        
        for (attempt in 1..maxAttempts) {
            try {
                val jupiter = JupiterApi(jupiterApiKey)
                val signature = jupiter.executeUltra(signedB64, requestId)
                
                // Still await confirmation to be safe
                awaitConfirmation(signature)
                return signature
            } catch (e: Exception) {
                lastException = e
                android.util.Log.w("SolanaWallet", "⚠️ Ultra execute attempt $attempt/$maxAttempts failed: ${e.message?.take(60)}")
                
                // Check if error is retryable
                val errorMsg = e.message?.lowercase() ?: ""
                val isRetryable = errorMsg.contains("timeout") || 
                                  errorMsg.contains("rate") ||
                                  errorMsg.contains("network") ||
                                  errorMsg.contains("server") ||
                                  errorMsg.contains("-1000") ||  // Failed to land
                                  errorMsg.contains("-1005") ||  // Expired after attempts
                                  errorMsg.contains("-1006")     // Timed out
                
                if (isRetryable && attempt < maxAttempts) {
                    android.util.Log.w("SolanaWallet", "⚠️ Retrying /execute in ${attempt * 500}ms...")
                    Thread.sleep(attempt * 500L)
                    continue
                }
                
                // Non-retryable or max attempts reached
                break
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // FALLBACK LOGIC: Only self-broadcast for NON-RFQ routes
        // RFQ routes REQUIRE Jupiter's market maker signature from /execute
        // ═══════════════════════════════════════════════════════════════════
        if (isRfqRoute) {
            android.util.Log.e("SolanaWallet", "❌ RFQ route failed - cannot self-broadcast, Jupiter signature required")
            throw lastException ?: RuntimeException("RFQ execute failed after $maxAttempts attempts")
        }
        
        android.util.Log.w("SolanaWallet", "⚠️ Ultra /execute failed (non-RFQ) — falling back to legacy RPC self-broadcast")

        // V5.0.3690 — DO NOT send untipped Ultra fallback txs through Helius
        // Sender. Sender requires a real Jito tip transfer instruction. Ultra
        // /execute orders are not built through our Sender-compatible Jupiter v6
        // builder, so they are not tagged/tip-guaranteed. Sending them to Sender
        // recreated the old helius_sender 503 loop. Sender is used by signAndSend
        // only when tx was explicitly built senderCompatible=true.
        try {
            // Last resort: plain RPC self-broadcast (already signed).
            val signature = sendRawTransaction(signedB64)
            android.util.Log.i("SolanaWallet", "✅ RPC fallback broadcast succeeded! sig=${signature.take(20)}...")
            awaitConfirmation(signature)
            return signature
        } catch (fallbackEx: Exception) {
            android.util.Log.e("SolanaWallet", "❌ Fallback also failed: ${fallbackEx.message}")
            // Throw original Ultra error as it's more descriptive
            throw lastException ?: fallbackEx
        }
    }
    
    /**
     * Send already-signed raw transaction bytes to RPC.
     * Used as fallback when Ultra execute fails.
     */
    private fun sendRawTransaction(signedTxB64: String): String {
        val params = JSONArray()
            .put(signedTxB64)
            .put(JSONObject()
                .put("encoding", "base64")
                .put("skipPreflight", false)
                .put("preflightCommitment", "confirmed")
                .put("maxRetries", 3)
            )
        
        val result = rpc("sendTransaction", params)
        
        // Check for error
        val error = result.optJSONObject("error")
        if (error != null) {
            val msg = error.optString("message", "unknown")
            val code = error.optInt("code", -1)
            throw RuntimeException("sendTransaction RPC error ($code): $msg")
        }
        
        val signature = result.optString("result", "")
        if (signature.isBlank()) {
            throw RuntimeException("sendTransaction returned empty signature")
        }
        
        return signature
    }

    // ── JSON-RPC helper ────────────────────────────────────

    /** V5.9.495y — public wrapper for TradeVerifier (which needs raw RPC access). */
    fun rpcCall(method: String, params: JSONArray): JSONObject = rpc(method, params)

    private fun rpc(method: String, params: JSONArray): JSONObject {
        // V5.9.771 — EMERGENT-MEME #5: ANR / main-thread guard.
        // Top ANR blocker in V5.9.770 dump was this function (41
        // samples). Reject main-thread invocation immediately with
        // a forensic emit so the offending caller is surfaced and
        // can be wrapped in withContext(Dispatchers.IO).
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
            try {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "WALLET_RPC_ON_MAIN_THREAD",
                    "fn=rpc method=$method blocked=true — caller must use Dispatchers.IO",
                )
            } catch (_: Throwable) {}
            throw IllegalStateException("SolanaWallet.rpc($method) called from Dispatchers.Main — wrap in withContext(Dispatchers.IO)")
        }
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id",      idGen.getAndIncrement())
            .put("method",  method)
            .put("params",  params)
        val body = payload.toString()

        // V5.7.8: Try primary RPC first, then ALL fallback RPCs
        val rpcsToTry = mutableListOf(rpcUrl)
        com.lifecyclebot.engine.WalletManager.FALLBACK_RPCS.forEach { fallback ->
            if (fallback != rpcUrl && fallback !in rpcsToTry) rpcsToTry.add(fallback)
        }

        var lastBody = "{}"
        var lastError: Exception? = null

        for (endpoint in rpcsToTry) {
            for (attempt in 0..1) {
                try {
                    val req = Request.Builder().url(endpoint)
                        .header("Content-Type", "application/json")
                        .post(body.toRequestBody(JSON_MT)).build()
                    val resp = try {
                        http.newCall(req).execute()
                    } catch (tls: Throwable) {
                        if (!isTlsTrustFailure(tls)) throw tls
                        try {
                            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                                "WALLET_RPC_TLS_FALLBACK_USED",
                                "method=$method endpoint=${endpoint.take(48)} err=${tls.message?.take(80)}",
                            )
                        } catch (_: Throwable) {}
                        unsafeWalletRpcClient().newCall(req).execute()
                    }
                    val code = resp.code
                    lastBody = resp.body?.string() ?: "{}"

                    // V5.9.267 — fail over on auth failures (HTTP 401/403)
                    // The user's primary RPC API key was rejected with
                    // 'invalid api key provided' but rpc() was returning that
                    // error verbatim instead of trying fallbacks. Result: every
                    // sendTransaction failed and trading fees got dropped.
                    if (code == 401 || code == 403) {
                        android.util.Log.w("SolanaWallet", "🚨 RPC AUTH REJECTED on $endpoint (HTTP $code) — trying next fallback")
                        break
                    }

                    if (code != 429 && code < 500) {
                        val json = JSONObject(lastBody)
                        // Check for RPC-level errors
                        if (json.has("error")) {
                            val errMsg = json.optJSONObject("error")?.optString("message", "") ?: ""
                            val low = errMsg.lowercase()
                            if (low.contains("rate") || low.contains("limit")) {
                                // Rate limited — try next endpoint
                                break
                            }
                            // V5.9.267 — auth errors must also trigger fallback,
                            // not be returned to the caller as fatal RPC error.
                            if (low.contains("api key") || low.contains("unauthorized") ||
                                low.contains("forbidden") || low.contains("invalid key")) {
                                android.util.Log.w("SolanaWallet", "🚨 RPC AUTH error on $endpoint: '$errMsg' — failing over")
                                break
                            }
                        }
                        return json
                    }
                    if (attempt == 0) Thread.sleep(1_000)
                } catch (e: Exception) {
                    lastError = e
                    if (attempt == 0) Thread.sleep(500)
                }
            }
        }
        throw lastError ?: RuntimeException("RPC $method failed on all ${rpcsToTry.size} endpoints")
    }
    /**
     * Transfer SOL to a destination address — used for treasury withdrawals.
     * Returns transaction signature on success.
     *
     * Note: full raw SOL transfer (SystemProgram.transfer) implementation.
     * Uses getLatestBlockhash + manual transaction construction + NaCl signing.
     */
    /**
     * Get token accounts with BOTH balance AND decimals.
     * Returns Map<mint, Pair<uiAmount, decimals>>
     */
    fun getTokenAccountsWithDecimals(): Map<String, Pair<Double, Int>> = try {
        getTokenAccountsWithDecimalsStrict()
    } catch (e: Exception) {
        android.util.Log.w("SolanaWallet", "getTokenAccountsWithDecimals non-strict failed: ${e.message}")
        emptyMap()
    }

    private fun walletRpcEndpointsForTokenSnapshot(): List<String> {
        val endpoints = mutableListOf<String>()
        fun addClean(raw: String) {
            val v = raw.trim()
            if (v.isBlank()) return
            // V5.0.3775: solana.public-rpc.com is cert-broken on some Android trust stores.
            // It must never be used as wallet balance authority / reconciler proof.
            if (v.contains("solana.public-rpc.com", ignoreCase = true)) {
                try { com.lifecyclebot.engine.ForensicLogger.lifecycle("WALLET_RPC_ENDPOINT_SKIPPED_BAD_TLS", "endpoint=solana.public-rpc.com site=token_snapshot") } catch (_: Throwable) {}
                return
            }
            if (v !in endpoints) endpoints.add(v)
        }
        addClean(rpcUrl)
        try { com.lifecyclebot.engine.WalletManager.FALLBACK_RPCS.forEach { addClean(it) } } catch (_: Throwable) {}
        return endpoints
    }

    private fun rpcTokenAccountsByOwnerFast(programId: String): JSONObject {
        val params = JSONArray()
            .put(publicKeyB58)
            .put(JSONObject().put("programId", programId))
            .put(JSONObject()
                .put("encoding", "jsonParsed")
                .put("commitment", "confirmed"))
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", idGen.getAndIncrement())
            .put("method", "getTokenAccountsByOwner")
            .put("params", params)
        val body = payload.toString()
        val endpoints = walletRpcEndpointsForTokenSnapshot()
        val fastHttp = http.newBuilder()
            .connectTimeout(900, TimeUnit.MILLISECONDS)
            .readTimeout(1_400, TimeUnit.MILLISECONDS)
            .callTimeout(1_800, TimeUnit.MILLISECONDS)
            .build()
        val failures = mutableListOf<String>()
        for (endpoint in endpoints) {
            try {
                val req = Request.Builder().url(endpoint)
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(JSON_MT)).build()
                val resp = try {
                    fastHttp.newCall(req).execute()
                } catch (tls: Throwable) {
                    if (!isTlsTrustFailure(tls)) throw tls
                    // V5.0.4595 — TLS-trust failure → 30s cooldown (some hosts
                    // are permanently cert-broken on Android trust stores;
                    // spamming them wastes cycles every snapshot).
                    markEndpointUnhealthy(endpoint, "TLS")
                    failures.add("${endpoint.take(24)}:TLS")
                    try { com.lifecyclebot.engine.ForensicLogger.lifecycle("WALLET_RPC_TLS_REJECTED_TOKEN_SNAPSHOT", "program=${programId.take(8)} endpoint=${endpoint.take(48)} err=${tls.message?.take(80)}") } catch (_: Throwable) {}
                    continue
                }
                val code = resp.code
                val text = resp.body?.string() ?: "{}"
                if (code == 429 || code >= 500 || code == 401 || code == 403) {
                    // V5.0.4595 — mark endpoint unhealthy for 30s cooldown
                    markEndpointUnhealthy(endpoint, "HTTP$code")
                    failures.add("${endpoint.take(24)}:HTTP$code")
                    continue
                }
                val json = JSONObject(text)
                val err = json.optJSONObject("error")
                if (err != null) {
                    val msg = err.optString("message", err.toString())
                    val low = msg.lowercase()
                    if (low.contains("rate") || low.contains("limit") || low.contains("unauthorized") || low.contains("forbidden") || low.contains("api key") || low.contains("invalid key")) {
                        // V5.0.4595 — RPC-level rate-limit / auth-fail → cooldown
                        markEndpointUnhealthy(endpoint, "RPC:${msg.take(24)}")
                        failures.add("${endpoint.take(24)}:RPC:${msg.take(36)}")
                        continue
                    }
                }
                return json
            } catch (e: Throwable) {
                // V5.0.4595 — network-level throw → 30s cooldown before retry
                markEndpointUnhealthy(endpoint, e.javaClass.simpleName)
                failures.add("${endpoint.take(24)}:${e.javaClass.simpleName}:${e.message?.take(32) ?: "?"}")
            }
        }
        throw RuntimeException("getTokenAccountsByOwner failed on ${endpoints.size} wallet endpoints: ${failures.joinToString("|").take(420)}")
    }

    private fun heliusDasFungibleTokensByOwner(): Map<String, Pair<Double, Int>> {
        val apiKey = try { com.lifecyclebot.data.DefaultKeys.HELIUS } catch (_: Throwable) { "" }
        if (apiKey.isBlank()) throw RuntimeException("Helius DAS unavailable: missing api key")
        val url = "https://mainnet.helius-rpc.com/?api-key=$apiKey"
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", idGen.getAndIncrement())
            .put("method", "getAssetsByOwner")
            .put("params", JSONObject()
                .put("ownerAddress", publicKeyB58)
                .put("page", 1)
                .put("limit", 1000)
                .put("displayOptions", JSONObject()
                    .put("showFungible", true)
                    .put("showNativeBalance", false)))
        val client = http.newBuilder()
            .connectTimeout(1_200, TimeUnit.MILLISECONDS)
            .readTimeout(2_000, TimeUnit.MILLISECONDS)
            .callTimeout(2_500, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MT)).build()
        val text = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Helius DAS HTTP ${resp.code}")
            resp.body?.string() ?: "{}"
        }
        val json = JSONObject(text)
        val err = json.optJSONObject("error")
        if (err != null) throw RuntimeException("Helius DAS RPC ${err.optString("message", err.toString())}")
        val result = json.optJSONObject("result") ?: throw RuntimeException("Helius DAS missing result")
        val items = result.optJSONArray("items") ?: JSONArray()
        val out = mutableMapOf<String, Pair<Double, Int>>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val mint = item.optString("id", item.optString("mint", ""))
            val tokenInfo = item.optJSONObject("token_info") ?: item.optJSONObject("tokenInfo") ?: continue
            val decimals = tokenInfo.optInt("decimals", 0).coerceAtLeast(0)
            val rawText = when (val rawAny = tokenInfo.opt("balance")) {
                is Number -> rawAny.toString()
                is String -> rawAny
                else -> tokenInfo.optString("amount", "0")
            }
            val raw = rawText.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            if (mint.isNotBlank() && raw.signum() > 0) {
                val ui = try { raw.movePointLeft(decimals).toDouble() } catch (_: Throwable) { 0.0 }
                if (ui > 0.0 && ui.isFinite()) out[mint] = Pair(ui, decimals)
            }
        }
        try { com.lifecyclebot.engine.ForensicLogger.lifecycle("WALLET_TOKENS_DAS_OK", "count=${out.size} source=HELIUS_DAS") } catch (_: Throwable) {}
        return out
    }

    /**
     * V5.0.3762 — strict wallet token snapshot.
     * Timeout, transport failure, malformed RPC, or partial token-program
     * failure is indeterminate. SPL Token (Tokenkeg) is required because normal
     * meme holdings live there. Token-2022 is additive only: a Token-2022 read
     * failure must not poison an otherwise successful SPL wallet snapshot.
     */
    fun getTokenAccountsWithDecimalsStrict(): Map<String, Pair<Double, Int>> {
        // V5.0.4173 — WALLET CACHE 5s. Operator dump: 281 GB / 29 days.
        // This RPC fires every cycle (~6s) returning the full wallet SPL
        // state (5–100 KB payload) — across a day that's ~14,400 calls
        // × ~30 KB = ~430 MB/day re-reading state that almost never
        // changes mid-cycle. 5s TTL is conservative (half the 10s default)
        // to keep wallet reads fresh enough for the meme trader's exit
        // timing while still cutting traffic ~80% on this path.
        //
        // Cache is BUSTED by sell/buy post-broadcast paths via
        // WalletAccountCache.bustNow(), so confirmed trades never stale.
        com.lifecyclebot.engine.WalletAccountCache.snapshot(ttlMs = 5_000L)?.let { return it }

        val TOKEN_PROGRAM    = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        val TOKEN_2022_PROG  = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
        val out = mutableMapOf<String, Pair<Double, Int>>()
        val failures = mutableListOf<String>()
        var splProgramOk = false
        var token2022Ok = false

        fun mergeFrom(programId: String, json: JSONObject) {
            val err = json.optJSONObject("error")
            if (err != null) throw RuntimeException("RPC error: ${err.optString("message", err.toString())}")
            val result = json.optJSONObject("result") ?: throw RuntimeException("missing result")
            val arr = result.optJSONArray("value") ?: throw RuntimeException("missing result.value")
            for (i in 0 until arr.length()) {
                val info = arr.optJSONObject(i)
                    ?.optJSONObject("account")?.optJSONObject("data")
                    ?.optJSONObject("parsed")?.optJSONObject("info") ?: continue
                val mint = info.optString("mint", "")
                val tokenAmount = info.optJSONObject("tokenAmount")
                val qty = tokenAmount?.optString("uiAmountString", "0")?.toDoubleOrNull() ?: 0.0
                val decimals = tokenAmount?.optInt("decimals", 9) ?: 9
                if (mint.isNotBlank() && qty > 0) out[mint] = Pair(qty, decimals)
            }
        }

        try {
            mergeFrom(TOKEN_PROGRAM, rpcTokenAccountsByOwnerFast(TOKEN_PROGRAM))
            splProgramOk = true
        } catch (e: Exception) {
            failures.add("Tokenkeg:${e.message ?: "unknown"}")
            try {
                val das = heliusDasFungibleTokensByOwner()
                out.putAll(das)
                splProgramOk = true
                try { com.lifecyclebot.engine.ForensicLogger.lifecycle("WALLET_TOKEN_READ_DAS_FALLBACK_USED", "reason=Tokenkeg_failed count=${das.size} err=${e.message?.take(90)}") } catch (_: Throwable) {}
            } catch (dasErr: Exception) {
                failures.add("HeliusDAS:${dasErr.message ?: "unknown"}")
            }
        }

        try {
            mergeFrom(TOKEN_2022_PROG, rpcTokenAccountsByOwnerFast(TOKEN_2022_PROG))
            token2022Ok = true
        } catch (e: Exception) {
            failures.add("TokenzQd:${e.message ?: "unknown"}")
            // Token-2022 is additive for our meme wallet reconciliation. Do not poison
            // normal SPL wallet proof if Tokenkeg succeeded; most AATE positions are SPL Token.
        }

        if (!splProgramOk) {
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("WALLET_TOKEN_READ_INDETERMINATE", "successPrograms=0/2 required=Tokenkeg failures=${failures.joinToString(";").take(220)}") } catch (_: Throwable) {}
            throw RuntimeException("wallet token snapshot indeterminate: required SPL Token program failed; ${failures.joinToString(";")}")
        }
        if (!token2022Ok) {
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("WALLET_TOKEN_2022_OPTIONAL_FAILED", "action=continue_with_spl successPrograms=1/2 failures=${failures.joinToString(";").take(180)}") } catch (_: Throwable) {}
        }
        try { com.lifecyclebot.engine.ForensicLogger.lifecycle("WALLET_TOKENS_ROBUST_OK", "spl=true token2022=$token2022Ok count=${out.size}") } catch (_: Throwable) {}
        // V5.0.4173 — populate the wallet snapshot cache so subsequent
        // reads within 5s skip the RPC entirely.
        try { com.lifecyclebot.engine.WalletAccountCache.put(out.toMap()) } catch (_: Throwable) {}
        return out
    }

    /**
     * V5.9.999b — BOUNDED variant of getTokenAccountsWithDecimals.
     *
     * The synchronous parent method can hang for 60-180 s when Helius / Triton
     * is overloaded (3 retries × 2 token programs × OkHttp 25 s timeout + the
     * 300/600 ms backoffs). Operator triage of the V5.9.998 bot-loop death
     * proved this single call site (per-tick pendingVerify watchdog in
     * BotService.botLoop @ ~line 7904) is enough to wedge the whole bot loop
     * coroutine — and there's no way to wrap botLoop in withTimeoutOrNull
     * because the method body is already at the JVM 64 KB cap.
     *
     * This wrapper offloads the call to a dedicated daemon executor (NOT
     * Dispatchers.IO — we don't want a single hung RPC to consume an IO
     * worker that the bot loop, sweepers, or persistence rely on) and
     * applies a hard `Future.get(timeoutMs)` ceiling. On timeout/transport
     * failure it throws WALLET_TOKEN_READ_INDETERMINATE. It must never invent
     * emptyMap(), because emptyMap() was misread downstream as "wallet has no
     * tokens" and stranded live sells in SELL_WAITING_BALANCE_PROOF.
     *
     * Default ceiling is 5 s: balances tolerance for normal RPC latency
     * against the desire to keep the bot loop ticking. Critical sell paths
     * can pass a tighter budget, treasury sweeps a looser one.
     */
    fun getTokenAccountsWithDecimalsBounded(
        timeoutMs: Long = 5_000L
    ): Map<String, Pair<Double, Int>> {
        val fut: java.util.concurrent.Future<Map<String, Pair<Double, Int>>> = try {
            boundedRpcExecutor().submit(java.util.concurrent.Callable {
                getTokenAccountsWithDecimalsStrict()
            })
        } catch (e: Throwable) {
            throw RuntimeException("wallet token bounded executor unavailable: ${e.message}", e)
        }
        return try {
            fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            try { fut.cancel(true) } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("WALLET_TOKEN_READ_INDETERMINATE", "reason=TIMEOUT timeoutMs=$timeoutMs") } catch (_: Throwable) {}
            android.util.Log.w("SolanaWallet", "getTokenAccountsWithDecimalsBounded: RPC exceeded ${timeoutMs} ms — indeterminate, not empty wallet")
            throw RuntimeException("wallet token snapshot timeout after ${timeoutMs}ms", e)
        } catch (e: Throwable) {
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("WALLET_TOKEN_READ_INDETERMINATE", "reason=${e.message?.take(160)}") } catch (_: Throwable) {}
            throw RuntimeException("wallet token snapshot failed: ${e.message}", e)
        }
    }

    /**
     * V5.9.265 — Authoritative post-buy token verification.
     *
     * After Jupiter Ultra/RFQ swaps, `getTokenAccountsByOwner` can return
     * stale 0 for the new mint for 30+ seconds even though the tokens
     * actually landed. The forensics tile caught this red-handed:
     *   ✅ Tx confirmed on-chain
     *   ❌ All 5 polls returned wallet qty = 0
     *   ✅ User's wallet UI shows the tokens
     *
     * Fix: parse the actual transaction signature via getTransaction and
     * read postTokenBalances[]. This is the on-chain ground truth, not a
     * derived index that lags.
     *
     * Returns the UI-amount delta the wallet's owner address received for
     * the given mint, or null if the tx couldn't be parsed yet (callers
     * should retry).
     */
    fun getTokenAmountFromSig(sig: String, mint: String): Double? {
        return try {
            val params = JSONArray()
                .put(sig)
                .put(JSONObject()
                    .put("encoding", "jsonParsed")
                    .put("commitment", "confirmed")
                    .put("maxSupportedTransactionVersion", 0))
            val resp = rpc("getTransaction", params)
            val result = resp.optJSONObject("result") ?: return null
            val meta = result.optJSONObject("meta") ?: return null

            val pre  = meta.optJSONArray("preTokenBalances")  ?: JSONArray()
            val post = meta.optJSONArray("postTokenBalances") ?: JSONArray()

            // Find owner=publicKeyB58 + mint=mint entries in pre and post
            fun extract(arr: JSONArray): Double {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("mint") != mint) continue
                    if (o.optString("owner") != publicKeyB58) continue
                    val ta = o.optJSONObject("uiTokenAmount") ?: continue
                    val ui = ta.optString("uiAmountString", "0").toDoubleOrNull()
                        ?: ta.optDouble("uiAmount", 0.0)
                    return ui
                }
                return 0.0
            }
            val before = extract(pre)
            val after  = extract(post)
            (after - before).coerceAtLeast(0.0)
        } catch (e: Exception) {
            android.util.Log.w("SolanaWallet", "getTokenAmountFromSig failed: ${e.message}")
            null
        }
    }



    /**
     * V5.0.3740 — owner/mint-filtered raw tx-meta token delta.
     * Generic TX_PARSE is not balance authority. This helper only returns a
     * positive delta when pre/postTokenBalances contain rows where owner == this
     * wallet public key AND mint == target mint. Pool/reserve/inner transfer rows
     * are ignored by construction.
     */
    fun getOwnerTokenDeltaRawFromSig(sig: String, mint: String): Pair<java.math.BigInteger, Int>? {
        return try {
            val params = JSONArray()
                .put(sig)
                .put(JSONObject()
                    .put("encoding", "jsonParsed")
                    .put("commitment", "confirmed")
                    .put("maxSupportedTransactionVersion", 0))
            val resp = rpc("getTransaction", params)
            val result = resp.optJSONObject("result") ?: return null
            val meta = result.optJSONObject("meta") ?: return null
            val pre  = meta.optJSONArray("preTokenBalances")  ?: JSONArray()
            val post = meta.optJSONArray("postTokenBalances") ?: JSONArray()

            fun extract(arr: JSONArray): Pair<java.math.BigInteger, Int> {
                var raw = java.math.BigInteger.ZERO
                var decimals = 0
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("mint") != mint) continue
                    if (o.optString("owner") != publicKeyB58) continue
                    val ta = o.optJSONObject("uiTokenAmount") ?: continue
                    val amount = ta.optString("amount", "0").toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
                    raw = raw.add(amount)
                    decimals = ta.optInt("decimals", decimals)
                }
                return raw to decimals
            }
            val (before, preDec) = extract(pre)
            val (after, postDec) = extract(post)
            val delta = after.subtract(before)
            val dec = if (postDec > 0) postDec else preDec
            if (delta.signum() > 0) delta to dec else null
        } catch (e: Exception) {
            android.util.Log.w("SolanaWallet", "getOwnerTokenDeltaRawFromSig failed: ${e.message}")
            null
        }
    }

    fun getTokenAccounts(): Map<String, Double> {
        return try {
            getTokenAccountsWithDecimalsStrict().mapValues { it.value.first }
        } catch (e: Exception) {
            android.util.Log.w("SolanaWallet", "getTokenAccounts failed via strict authority: ${e.message}")
            emptyMap()
        }
    }


    /**
     * V5.9.1531 — ROBUST wallet-token read for connect-time reconciliation.
     *
     * THE GHOST BUG: getTokenAccounts() swallows RPC exceptions per-program and
     * returns whatever (possibly empty) map it has. On a cold post-install connect
     * the RPC is frequently rate-limited/timing-out, so the adoption sweep saw an
     * empty wallet and adopted NOTHING — every real holding became an unmanaged
     * ghost (operator: ~100 ghosts/24h across update installs).
     *
     * This variant tracks whether AT LEAST ONE program query SUCCEEDED. If every
     * query threw (total RPC failure), it returns ok=false so callers RETRY instead
     * of trusting a false-empty snapshot. A genuinely empty wallet returns ok=true
     * with an empty map. Result: we never orphan holdings on a transient RPC blip.
     */
    data class WalletTokenSnapshot(val ok: Boolean, val tokens: Map<String, Double>)

    fun getTokenAccountsChecked(): WalletTokenSnapshot {
        return try {
            val strict = getTokenAccountsWithDecimalsStrict().mapValues { it.value.first }
            WalletTokenSnapshot(ok = true, tokens = strict)
        } catch (e: Exception) {
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("WALLET_TOKENS_CHECKED_INDETERMINATE", "err=${e.message?.take(180)}") } catch (_: Throwable) {}
            WalletTokenSnapshot(ok = false, tokens = emptyMap())
        }
    }


    /**
     * Retry getTokenAccountsChecked until a trustworthy snapshot is obtained
     * (ok=true) or attempts exhaust. Backoff 400/800/1600/3200ms. Never throws.
     */
    fun getTokenAccountsRobust(maxAttempts: Int = 5): WalletTokenSnapshot {
        var last = WalletTokenSnapshot(false, emptyMap())
        var delay = 400L
        for (attempt in 1..maxAttempts) {
            last = try { getTokenAccountsChecked() } catch (_: Throwable) { WalletTokenSnapshot(false, emptyMap()) }
            if (last.ok) {
                try { com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "WALLET_TOKENS_ROBUST_OK", "attempt=$attempt count=${last.tokens.size}") } catch (_: Throwable) {}
                return last
            }
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "WALLET_TOKENS_RPC_RETRY", "attempt=$attempt/$maxAttempts backoffMs=$delay") } catch (_: Throwable) {}
            try { Thread.sleep(delay) } catch (_: InterruptedException) {}
            delay = (delay * 2).coerceAtMost(3200L)
        }
        return last
    }

    /**
     * Transfer SOL to a destination address — used for treasury withdrawals.
     * Constructs a legacy SystemProgram.transfer transaction, signs it with
     * the loaded keypair, broadcasts and awaits on-chain confirmation.
     *
     * Fixed from original:
     */
    fun sendSol(destinationAddress: String, amountSol: Double): String {
        val lamports = (amountSol * 1_000_000_000L).toLong()
        require(lamports >= 5_000)               { "Amount too small (min 0.000005 SOL)" }
        require(destinationAddress.length in 32..44) { "Invalid destination address length" }

        // Fetch latest blockhash using class-level http + rpc() helper
        val bhResp = rpc("getLatestBlockhash",
            JSONArray().put(JSONObject().put("commitment", "confirmed")))
        val blockhash = bhResp.optJSONObject("result")
            ?.optJSONObject("value")
            ?.optString("blockhash")
            ?: throw Exception("Failed to fetch blockhash")

        // Decode addresses + blockhash
        val fromPkBytes = Base58.base58Decode(publicKeyB58)
        val toPkBytes   = Base58.base58Decode(destinationAddress)
        val bhBytes     = Base58.base58Decode(blockhash)
        val systemProg  = ByteArray(32)  // SystemProgram = 32 zero bytes

        // Transfer instruction data: SystemProgram.transfer wire format
        //   bytes 0-3:  opcode as u32 little-endian = [0x02, 0x00, 0x00, 0x00]
        //   bytes 4-11: lamports as u64 little-endian (8 bytes)
        // CRITICAL: opcode MUST be 4 bytes (u32), not 1 byte.
        // The original code used instrData[0]=2 with lamports in bytes 1-8,
        // which produced [0x02, lam0..lam7, 0x00, 0x00, 0x00] — 12 bytes but
        // with the lamports shifted by 3 bytes from what Solana expects.
        // RPC response: "invalid instruction data" — FEE_SEND_FAILED on EVERY call.
        val instrData = ByteArray(12)
        instrData[0] = 0x02; instrData[1] = 0x00; instrData[2] = 0x00; instrData[3] = 0x00
        var lam = lamports
        for (i in 4..11) { instrData[i] = (lam and 0xFF).toByte(); lam = lam ushr 8 }

        // Compact-array encoding (Solana wire format)
        fun compactU16(n: Int): ByteArray = when {
            n < 0x80   -> byteArrayOf(n.toByte())
            n < 0x4000 -> byteArrayOf((n and 0x7F or 0x80).toByte(), (n shr 7).toByte())
            else       -> byteArrayOf(
                (n and 0x7F or 0x80).toByte(),
                ((n shr 7) and 0x7F or 0x80).toByte(),
                (n shr 14).toByte()
            )
        }

        // Build legacy transaction message
        val msg = java.io.ByteArrayOutputStream().apply {
            write(byteArrayOf(0x01, 0x00, 0x01))  // 1 signer, 0 read-only signed, 1 read-only unsigned
            write(compactU16(3))                    // 3 accounts: from, to, SystemProgram
            write(fromPkBytes)
            write(toPkBytes)
            write(systemProg)
            write(bhBytes)                          // recent blockhash (32 bytes)
            write(compactU16(1))                    // 1 instruction
            write(byteArrayOf(0x02))                // programIdIndex = 2 (SystemProgram)
            write(compactU16(2))                    // 2 account references
            write(byteArrayOf(0x00, 0x01))          // accounts: from=0, to=1
            write(compactU16(instrData.size))
            write(instrData)
        }.toByteArray()

        // Sign the message with the loaded keypair (keyPair is a val, not a function)
        val sig    = TweetNaclFast.Signature(null, keyPair.secretKey).detached(msg)
        val sigB64 = android.util.Base64.encodeToString(sig, android.util.Base64.NO_WRAP)

        // Build and broadcast transaction (legacy format: [numSigs][sig][message])
        val txBytes  = byteArrayOf(0x01.toByte()) + sig + msg
        val txB64    = android.util.Base64.encodeToString(txBytes, android.util.Base64.NO_WRAP)
        val txResult = rpc("sendTransaction",
            JSONArray()
                .put(txB64)
                .put(JSONObject()
                    .put("encoding", "base64")
                    .put("preflightCommitment", "confirmed")))

        val txSig = txResult.optString("result", "")
        if (txSig.isBlank()) {
            val err = txResult.optJSONObject("error")?.optString("message") ?: "unknown error"
            throw Exception("sendTransaction failed: $err")
        }

        // Await on-chain confirmation before returning
        awaitConfirmation(txSig)
        return txSig
    }


}