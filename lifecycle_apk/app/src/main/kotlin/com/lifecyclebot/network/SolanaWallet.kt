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

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MT = "application/json".toMediaType()
    private val idGen   = AtomicLong(1)

    // ── balance ────────────────────────────────────────────

    fun getSolBalance(): Double {
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
    fun signAndSend(txBase64: String, useJito: Boolean = false, jitoTipLamports: Long = 10000): String {
        val txBytes    = android.util.Base64.decode(txBase64, android.util.Base64.DEFAULT)
        val signedBytes = signVersionedTx(txBytes)
        val signedB64   = android.util.Base64.encodeToString(signedBytes, android.util.Base64.NO_WRAP)
        
        // Try Jito MEV protection first if enabled
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
    ): String {
        // If we have an Ultra requestId, use Jupiter's execute endpoint
        // This provides built-in MEV protection via Jupiter Beam
        if (!ultraRequestId.isNullOrBlank()) {
            return signAndExecuteUltra(txBase64, ultraRequestId, jupiterApiKey, isRfqRoute)
        }
        
        val sig = signAndSend(txBase64, useJito, jitoTipLamports)
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
        
        android.util.Log.w("SolanaWallet", "⚠️ Falling back to standard broadcast (non-RFQ route)...")
        
        try {
            // Try standard send (already signed, so just broadcast)
            val signature = sendRawTransaction(signedB64)
            android.util.Log.i("SolanaWallet", "✅ Fallback broadcast succeeded! sig=${signature.take(20)}...")
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

    private fun rpc(method: String, params: JSONArray): JSONObject {
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
                    val resp = http.newCall(req).execute()
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
    fun getTokenAccountsWithDecimals(): Map<String, Pair<Double, Int>> {
        // V5.9.254 FIX: Two root causes patched here:
        //
        // 1. dataSize=165 filter — this is the standard SPL Token account size, but
        //    Token-2022 program accounts are a DIFFERENT size (varies by extension,
        //    typically 170+ bytes). Many new meme tokens on Pump.fun/Raydium use
        //    Token-2022. The filter was silently dropping all Token-2022 holdings,
        //    making the phantom guard see qty=0 and wipe real positions.
        //    Fix: query BOTH token programs explicitly without a dataSize filter.
        //
        // 2. No commitment level — defaults to "finalized" which is 32+ slots behind
        //    "confirmed". With a 30s verify window, a brand-new buy might not be
        //    finalized yet even though it's confirmed and the tokens are real.
        //    Fix: use commitment="confirmed" for all token account reads.
        val TOKEN_PROGRAM    = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        val TOKEN_2022_PROG  = "TokenzQdBNbequivDy2Cv5VhM9xAZWQ8HHv2Q3ZUVV1"
        val out = mutableMapOf<String, Pair<Double, Int>>()
        for (programId in listOf(TOKEN_PROGRAM, TOKEN_2022_PROG)) {
            repeat(3) { attempt ->
                try {
                    val params = JSONArray()
                        .put(publicKeyB58)
                        .put(JSONObject()
                            .put("encoding", "jsonParsed")
                            .put("commitment", "confirmed")
                            .put("programId", programId))
                    val resp = rpc("getTokenAccountsByOwner", params)
                    resp.optJSONObject("result")?.optJSONArray("value")?.let { arr ->
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
                    return@repeat  // success for this program
                } catch (e: Exception) {
                    android.util.Log.w("SolanaWallet", "getTokenAccountsWithDecimals [$programId] attempt ${attempt+1}/3 failed: ${e.message}")
                    if (attempt < 2) Thread.sleep((300L shl attempt))
                }
            }
        }
        return out
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

    fun getTokenAccounts(): Map<String, Double> {
        // V5.9.254 FIX: Same as getTokenAccountsWithDecimals — query both token programs
        // with commitment=confirmed and no dataSize filter.
        val TOKEN_PROGRAM    = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        val TOKEN_2022_PROG  = "TokenzQdBNbequivDy2Cv5VhM9xAZWQ8HHv2Q3ZUVV1"
        val out = mutableMapOf<String, Double>()
        for (programId in listOf(TOKEN_PROGRAM, TOKEN_2022_PROG)) {
            try {
                val params = JSONArray()
                    .put(publicKeyB58)
                    .put(JSONObject()
                        .put("encoding", "jsonParsed")
                        .put("commitment", "confirmed")
                        .put("programId", programId))
                val resp = rpc("getTokenAccountsByOwner", params)
                resp.optJSONObject("result")?.optJSONArray("value")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val info = arr.optJSONObject(i)
                            ?.optJSONObject("account")?.optJSONObject("data")
                            ?.optJSONObject("parsed")?.optJSONObject("info") ?: continue
                        val mint = info.optString("mint", "")
                        val qty  = info.optJSONObject("tokenAmount")
                            ?.optString("uiAmountString", "0")?.toDoubleOrNull() ?: 0.0
                        if (mint.isNotBlank() && qty > 0) out[mint] = qty
                    }
                }
            } catch (_: Exception) { /* continue to next program */ }
        }
        return out
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

        // Transfer instruction data: [2 (transfer opcode), amount as little-endian u64]
        val instrData = ByteArray(12)
        instrData[0]  = 2
        var lam = lamports
        for (i in 1..8) { instrData[i] = (lam and 0xFF).toByte(); lam = lam ushr 8 }

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