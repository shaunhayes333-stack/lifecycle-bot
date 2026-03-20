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
        val resp = rpc("getBalance", JSONArray().put(publicKeyB58))
        val lam  = resp.optJSONObject("result")?.optLong("value", 0L) ?: 0L
        return lam / 1_000_000_000.0
    }

    // ── sign + broadcast ───────────────────────────────────

    /**
     * Takes a base64 versioned transaction from Jupiter, signs it, broadcasts it.
     * Returns the transaction signature string.
     */
    fun signAndSend(txBase64: String): String {
        val txBytes    = android.util.Base64.decode(txBase64, android.util.Base64.DEFAULT)
        val signedBytes = signVersionedTx(txBytes)
        val signedB64   = android.util.Base64.encodeToString(signedBytes, android.util.Base64.NO_WRAP)

        val params = JSONArray()
            .put(signedB64)
            .put(JSONObject().put("encoding", "base64")
                             .put("preflightCommitment", "confirmed"))

        val resp = rpc("sendTransaction", params)
        return resp.optString("result")
            ?: throw RuntimeException("sendTransaction error: ${resp.optJSONObject("error")}")
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
     */
    fun signSendAndConfirm(txBase64: String): String {
        val sig = signAndSend(txBase64)
        awaitConfirmation(sig)
        return sig
    }

    // ── JSON-RPC helper ────────────────────────────────────

        private fun rpc(method: String, params: JSONArray): JSONObject {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id",      idGen.getAndIncrement())
            .put("method",  method)
            .put("params",  params)
        val body = payload.toString()
        // Retry once on rate-limit (429) or server error (5xx)
        var lastBody = "{}"
        for (attempt in 0..1) {
            val req  = Request.Builder().url(rpcUrl)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MT)).build()
            val resp = http.newCall(req).execute()
            val code = resp.code
            lastBody = resp.body?.string() ?: "{}"
            if (code != 429 && code < 500) return JSONObject(lastBody)
            if (attempt == 0) Thread.sleep(2_000)
        }
        throw RuntimeException("RPC $method rate-limited after retry")
    }
    /**
     * Transfer SOL to a destination address — used for treasury withdrawals.
     * Returns transaction signature on success.
     *
     * Note: full raw SOL transfer (SystemProgram.transfer) implementation.
     * Uses getLatestBlockhash + manual transaction construction + NaCl signing.
     */
    fun getTokenAccounts(): Map<String, Double> {
        return try {
            val params = JSONArray()
                .put(publicKeyB58)
                .put(JSONObject().put("encoding","jsonParsed")
                    .put("filters", JSONArray().put(JSONObject().put("dataSize",165))))
            val resp = rpc("getTokenAccountsByOwner", params)
            val out  = mutableMapOf<String, Double>()
            resp.optJSONObject("result")?.optJSONArray("value")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val info = arr.optJSONObject(i)
                        ?.optJSONObject("account")?.optJSONObject("data")
                        ?.optJSONObject("parsed")?.optJSONObject("info") ?: continue
                    val mint = info.optString("mint","")
                    val qty  = info.optJSONObject("tokenAmount")
                        ?.optString("uiAmountString","0")?.toDoubleOrNull() ?: 0.0
                    if (mint.isNotBlank() && qty > 0) out[mint] = qty
                }
            }
            out
        } catch (_: Exception) { emptyMap() }
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