package com.lifecyclebot.network

import com.lifecyclebot.engine.truth.CanonicalTokenAmount

import com.iwebpp.crypto.TweetNaclFast
import io.github.novacrypto.base58.Base58
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException

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
        /** V5.0.8085 — one durable ledger wiring per process; one bounded finality-read pool shared by every wallet. */
        @Volatile private var ledgerWired8085 = false
        private val resolver8085 = java.util.concurrent.ScheduledThreadPoolExecutor(2) { r ->
            Thread(r, "aate-tx-resolve-8085").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        // V5.0.7927 — token programs and wrapped SOL (rent reclaim).
        private const val TOKEN_PROGRAM_7927 = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        private const val TOKEN_2022_PROGRAM_7927 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
        private const val WSOL_MINT_7927 = "So11111111111111111111111111111111111111112"
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

        /**
         * V5.0.7210 §ROUND_ROBIN_THREW_AWAY_THE_ONLY_ENDPOINT_THAT_WORKED.
         *
         * Operator went live and their wallet showed ten freshly-bought tokens
         * worth $58.91 while the app listed them as "HELD RECOVERED_… basis
         * unknown" with "0.000◎ at risk" — live positions with no entry price,
         * therefore no stop-loss and no take-profit.
         *
         * The chain: the durable live basis receipt is written by the
         * wallet-proof promotion, that promotion needs getTokenAccountsByOwner,
         * and the 5.0.7206 device logged it "failed on 14 wallet endpoints"
         * with WALLET_TOKEN_READ_INDETERMINATE=224 and
         * WALLET_RPC_ENDPOINT_COOLDOWN_4595=3754. No read, no proof, no
         * receipt — LIVE_BUY_SIDE_EFFECTS_DEFERRED_6637=2 against
         * COMMITTED=1, FILL_LOT_LEDGER_APPEND_BUY_6344=1 and
         * CANONICAL_BUY_FILL_RECORDED_6320=1 against EXEC_LIVE_BUY_OK=4.
         *
         * In the SAME snapshot: `helius live=true http=200 HELIUS_HEALTHY
         * getTokenAccountsByOwner o` and helius sr=99% s=389. The one
         * authenticated provider, explicitly verified for this exact call, was
         * healthy throughout.
         *
         * RuntimeProviderAuthority6685.rpcCandidates builds a deliberate
         * PREFERENCE LADDER — primary, configured rpcUrl, Helius, then 13
         * public endpoints — and every other consumer of it walks that order
         * (WalletManager:333, JupiterApi:712, SolanaWallet:626). This function
         * was the sole caller that rotated it. With ~15 candidates, Helius was
         * first on roughly one call in fifteen; the rest of the time the loop
         * spent its budget on free.rpcpool.com (timeout), solana-mainnet.c
         * (HTTP401), mainnet.rpcpool (HTTP403) and solana-mainnet.p
         * (UnknownHostException) before ever asking the provider that answers.
         *
         * Round-robin is right for spreading load across INTERCHANGEABLE
         * endpoints. It is wrong applied to a ladder whose head is
         * authenticated and whose tail is anonymous best-effort. So: keep the
         * preferred head in its authored order, and rotate only the public
         * tail, which is what round-robin was for. Membership is decided by
         * PUBLIC_SOLANA_RPCS rather than by index, so adding another
         * authenticated provider automatically joins the head and the two
         * cannot drift apart.
         *
         * The 4595 per-endpoint 30s cooldown filter is retained exactly as it
         * was, including its fail-safe of returning the full list when every
         * endpoint is cooling, so the snapshot still never goes dark.
         */
        internal fun applyRoundRobin(endpoints: List<String>): List<String> {
            if (endpoints.size <= 1) return endpoints
            val now = System.currentTimeMillis()
            val healthy = endpoints.filter { (rpcCooldownUntil[it] ?: 0L) <= now }
            val pool = if (healthy.isNotEmpty()) healthy else endpoints
            // Both sides go through sanitizeRpc, the same normaliser
            // rpcCandidates used to build `pool`. Comparing a raw constant
            // against a sanitised pool entry would silently match nothing,
            // classify every endpoint as preferred, and turn this whole fix
            // into a no-op that still logs success — so the normaliser is
            // asked rather than assumed.
            val publicSet7210 = try {
                com.lifecyclebot.engine.RuntimeProviderAuthority6685.PUBLIC_SOLANA_RPCS
                    .map { com.lifecyclebot.engine.RuntimeProviderAuthority6685.sanitizeRpc(it) }
                    .filter { it.isNotBlank() }
                    .toSet()
            } catch (_: Throwable) { emptySet<String>() }
            val preferred7210 = pool.filter { it !in publicSet7210 }
            val rotatable7210 = pool.filter { it in publicSet7210 }
            if (preferred7210.isEmpty() || rotatable7210.size <= 1) {
                val startIdx = (rpcRoundRobinIndex.getAndIncrement() % pool.size)
                    .let { if (it < 0) it + pool.size else it }
                return pool.drop(startIdx) + pool.take(startIdx)
            }
            val startIdx = (rpcRoundRobinIndex.getAndIncrement() % rotatable7210.size)
                .let { if (it < 0) it + rotatable7210.size else it }
            try {
                com.lifecyclebot.engine.PipelineHealthCollector
                    .labelInc("WALLET_RPC_PREFERRED_LADDER_PRESERVED_7210")
            } catch (_: Throwable) {}
            return preferred7210 + rotatable7210.drop(startIdx) + rotatable7210.take(startIdx)
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

    // ── V5.0.8009 — execution speed ──
    @Volatile private var solBalance8009: Pair<Long, Double>? = null
    private val SOL_BALANCE_TTL_MS_8009 = 1_500L
    /** signature -> (signed tx, sent via Sender) while it awaits confirmation: re-broadcast until it lands. */
    private val inFlight8009 = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Boolean>>()
    private val echoing8009 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** V5.0.8085 — per-thread: did this sendTransaction possibly reach an endpoint (timeout / 5xx), or provably not. */
    private val sendMaybeDelivered8085 = ThreadLocal<Boolean?>()
    private val resolving8085 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val echo8009 = java.util.concurrent.ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "TxEcho8009").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    /** V5.0.8083 — parallel per-endpoint echo workers (the echo task itself waits on them, never the send path). */
    private val fanout8083 = java.util.concurrent.ThreadPoolExecutor(0, 6, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue()) { r -> Thread(r, "aate-tx-fanout-8083").apply { isDaemon = true } }
        .apply { rejectedExecutionHandler = java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy() }

    /** The same signed bytes again (idempotent: one signature lands at most once), off the caller's thread. */
    private fun echoSend8009(sig: String, signedB64: String, viaSender: Boolean) {
        if (!echoing8009.add(sig)) return
        echo8009.execute {
            try {
                if (viaSender) try { com.lifecyclebot.network.HeliusSender.send(signedB64) } catch (_: Throwable) {}
                // V5.0.8083 — the same signed bytes go to EVERY paid endpoint (Helius RPC + Alchemy + saved) at once,
                // not just the ladder's first responder. Identical bytes = one signature: it lands at most once.
                val targets8083 = try {
                    com.lifecyclebot.engine.RuntimeProviderAuthority6685.authenticatedRpcCandidates8083(rpcUrl)
                } catch (_: Throwable) { emptyList() }
                if (targets8083.isEmpty()) {
                    try {
                        rpc("sendTransaction", JSONArray().put(signedB64).put(JSONObject()
                            .put("encoding", "base64").put("skipPreflight", true).put("maxRetries", 0)))
                    } catch (_: Throwable) {}
                } else {
                    val body8083 = JSONObject().put("jsonrpc", "2.0").put("id", idGen.getAndIncrement()).put("method", "sendTransaction")
                        .put("params", JSONArray().put(signedB64).put(JSONObject().put("encoding", "base64").put("skipPreflight", true).put("maxRetries", 0)))
                        .toString()
                    val latch8083 = java.util.concurrent.CountDownLatch(targets8083.size)
                    for (endpoint in targets8083) fanout8083.execute {
                        try {
                            http.newCall(Request.Builder().url(endpoint).header("Content-Type", "application/json")
                                .post(body8083.toRequestBody(JSON_MT)).build()).execute().use { r ->
                                // V5.0.8085 — HTTP 200 is transport only: acceptance is a JSON-RPC result equal to the
                                // signature. A failing endpoint never erases another endpoint's acceptance.
                                val provider = if (endpoint.contains("alchemy", true)) "alchemy" else if (endpoint.contains("helius", true)) "helius_rpc" else "saved_rpc"
                                val body = try { JSONObject(r.body?.string().orEmpty()) } catch (_: Throwable) { null }
                                val ack = SignedTxLedger8085.classifyRpcReply(r.isSuccessful && body != null, body?.optString("result", ""),
                                    body?.optJSONObject("error")?.optString("message", ""), sig)
                                if (ack == SignedTxLedger8085.Submit.ACCEPTED) {
                                    SignedTxLedger8085.transition(sig, SignedTxLedger8085.State.ACCEPTED, acceptedBy = provider)
                                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                                        if (provider == "alchemy") "TX_FANOUT_ACCEPTED_ALCHEMY_8083"
                                        else if (provider == "helius_rpc") "TX_FANOUT_ACCEPTED_HELIUS_RPC_8083"
                                        else "TX_FANOUT_ACCEPTED_SAVED_RPC_8083") } catch (_: Throwable) {}
                                } else if (ack == SignedTxLedger8085.Submit.REJECTED) try {
                                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TX_RPC_REJECTED_8085")
                                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TX_RPC_REJECTED_${provider.uppercase()}_8085")
                                } catch (_: Throwable) {}
                            }
                        } catch (_: Throwable) {} finally { latch8083.countDown() }
                    }
                    try { latch8083.await(4, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Throwable) {}
                }
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TX_ECHO_SENT_8009") } catch (_: Throwable) {}
            } finally { echoing8009.remove(sig) }
        }
    }

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
        // V5.0.8009 — one read serves a buy attempt: the planner asked for the same balance three
        // or four times in a row (each a round trip, with 300/600 ms retry sleeps on a miss).
        solBalance8009?.let { (at, v) -> if (System.currentTimeMillis() - at < SOL_BALANCE_TTL_MS_8009) return v }
        var lastEx: Exception? = null
        repeat(3) { attempt ->
            try {
                val resp = rpc("getBalance", JSONArray().put(publicKeyB58))
                val error = resp.optJSONObject("error")
                if (error != null) {
                    throw RuntimeException("RPC error: ${error.optString("message", "unknown")}")
                }
                val lam = resp.optJSONObject("result")?.optLong("value", 0L) ?: 0L
                return (lam / 1_000_000_000.0).also { solBalance8009 = System.currentTimeMillis() to it }
            } catch (e: Exception) {
                lastEx = e
                if (attempt < 2) Thread.sleep((300L shl attempt))
            }
        }
        android.util.Log.e("SolanaWallet", "getSolBalance failed after 3 attempts: ${lastEx?.message}", lastEx)
        throw lastEx ?: RuntimeException("getSolBalance: unknown error")
    }

    /**
     * V5.0.7694 — SOL balance of an arbitrary address (one attempt, null on any
     * failure). Used by FeeAccumulator to see whether a fee destination still
     * exists on-chain: a system transfer that would leave a NEW account below
     * the rent-exempt minimum (~0.00089 SOL) fails preflight with "insufficient
     * funds for rent", so a swept-to-zero fee wallet cannot be re-created by a
     * 0.0001 SOL flush. The caller holds the bucket until it can.
     */
    fun getSolBalanceOf7694(address: String): Double? {
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) return null
        return try {
            val resp = rpc("getBalance", JSONArray().put(address))
            if (resp.optJSONObject("error") != null) return null
            val result = resp.optJSONObject("result") ?: return null
            val lam = result.optLong("value", Long.MIN_VALUE)
            if (lam == Long.MIN_VALUE) null else lam / 1_000_000_000.0
        } catch (_: Throwable) { null }
    }

    /**
     * V5.0.6685 — connect-time primary-only balance probe.
     * WalletManager owns endpoint failover. This method deliberately performs
     * exactly one JSON-RPC call to this wallet's rpcUrl so Connect cannot create
     * an outer-RPC × inner-RPC × retry explosion.
     */
    fun getSolBalancePrimaryOnly6685(): Double {
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
            throw IllegalStateException("SolanaWallet.getSolBalancePrimaryOnly6685 called from Dispatchers.Main")
        }
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", idGen.getAndIncrement())
            .put("method", "getBalance")
            .put("params", JSONArray().put(publicKeyB58))
        val req = Request.Builder().url(rpcUrl)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MT))
            .build()
        val text = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("RPC HTTP ${resp.code}")
            resp.body?.string() ?: throw RuntimeException("RPC empty response")
        }
        val json = JSONObject(text)
        val err = json.optJSONObject("error")
        if (err != null) throw RuntimeException("RPC error: ${err.optString("message", "unknown")}")
        val result = json.optJSONObject("result") ?: throw RuntimeException("RPC missing result")
        val lamports = result.optLong("value", Long.MIN_VALUE)
        if (lamports == Long.MIN_VALUE) throw RuntimeException("RPC missing result.value")
        return lamports / 1_000_000_000.0
    }

    // ── sign + broadcast ───────────────────────────────────

    /**
     * Takes a base64 versioned transaction from Jupiter, signs it, broadcasts it.
     * Returns the transaction signature string.
     * 
     * If useJito=true, sends via Jito bundle for MEV protection.
     */
    fun signAndSend(
        txBase64: String,
        useJito: Boolean = false,
        jitoTipLamports: Long = 10000,
        senderCompatible: Boolean = false,
        awaitFinality: Boolean = true,
    ): String {
        // V5.0.8085 — the transaction id is derived from the signed bytes here and persisted before any send.
        val local8085 = signTracked8085(txBase64, SignedTxLedger8085.consumeIntent())
        val signedB64   = local8085.signedBase64
        val localSig8085 = local8085.signature
        var maybeElsewhere8085 = false
        fun finalized(signature: String): String {
            if (awaitFinality) awaitConfirmationKeepingSignature7733(signature)
            return signature
        }
        
        // V5.0.8094 — every endpoint at once, from the moment of signing. The fan-out to all paid RPCs used to start
        // only after Helius Sender (10 s call timeout) and the ladder submit had answered; with Helius out of credit
        // every exit waited on both before a working endpoint saw the bytes. Identical signed bytes = one signature,
        // so it still lands at most once; the primary paths below now race the fan-out instead of gating it.
        SignedTxLedger8085.transition(localSig8085, SignedTxLedger8085.State.SUBMIT_ATTEMPTED, "fanout_8094")
        echoSend8009(localSig8085, signedB64, viaSender = false)
        maybeElsewhere8085 = true
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TX_FANOUT_AT_SIGN_8094") } catch (_: Throwable) {}
        val heliusExhausted8094 = try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.exhaustedLatched8094() } catch (_: Throwable) { false }

        // V5.0.7250 — HELIUS SENDER FIRST only after the v0 message has proved
        // both AATE fee-contract instructions: Jupiter's CU price plus a locally
        // appended Helius tip transfer. Sender is transport, not a swap builder;
        // on any miss the same signed transaction falls through to Jito/RPC.
        if (senderCompatible && !heliusExhausted8094) {
            SignedTxLedger8085.transition(localSig8085, SignedTxLedger8085.State.SUBMIT_ATTEMPTED, "helius_sender")
            val sent8085 = try {
                com.lifecyclebot.network.HeliusSender.send8085(signedB64, localSig8085)
            } catch (_: Throwable) { null to SignedTxLedger8085.Submit.UNKNOWN }
            val senderSig = if (sent8085.second == SignedTxLedger8085.Submit.ACCEPTED) localSig8085 else null
            if (sent8085.second == SignedTxLedger8085.Submit.UNKNOWN) maybeElsewhere8085 = true
            if (!senderSig.isNullOrBlank()) {
                SignedTxLedger8085.transition(localSig8085, SignedTxLedger8085.State.ACCEPTED, acceptedBy = "helius_sender")
                com.lifecyclebot.engine.ErrorLogger.info("SolanaWallet",
                    "⚡ Broadcast via Helius Sender: ${senderSig.take(16)}…")
                // V5.0.8009 — the same bytes also go out through RPC at once (two routes, not one).
                solBalance8009 = null
                if (inFlight8009.size > 200) inFlight8009.clear()
                inFlight8009[senderSig] = signedB64 to true
                echoSend8009(senderSig, signedB64, viaSender = false)
                return finalized(senderSig)
            }
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("HELIUS_SENDER_DEGRADED",
                "action=rotate_sender_not_jupiter err=${com.lifecyclebot.network.HeliusSender.lastError?.take(80)}") } catch (_: Throwable) {}
            com.lifecyclebot.engine.ErrorLogger.warn("SolanaWallet",
                "⚠️ Helius Sender miss (${com.lifecyclebot.network.HeliusSender.lastError}) — rotating to Jito/RPC sender (NOT Jupiter)")
        }

        // Try Jito MEV protection if enabled
        var jitoSent8085 = false
        if (useJito) {
            try {
                // V5.0.6489 — bounded no-wait Jito submission. The same signed
                // transaction is immediately sent through normal RPC below, so this
                // preserves dual-route finality without nested runBlocking or a bundle
                // status wait on the execution thread.
                // V5.0.8009 — submitted off the send path: the bundle carries no tip transfer and never
                // reported landed, so waiting on it (up to 2.5 s) only delayed the RPC send below.
                echo8009.execute {
                    try { com.lifecyclebot.engine.JitoMEVProtection.submitProtectedNoWait6489(signedB64, jitoTipLamports) } catch (_: Throwable) {}
                }
                jitoSent8085 = true
                val jitoResult = com.lifecyclebot.engine.JitoMEVProtection.BundleResult(false, null, null, "ASYNC_8009")

                
                if (jitoResult.success && jitoResult.bundleId != null) {
                    com.lifecyclebot.engine.ErrorLogger.info("SolanaWallet", 
                        "⚡ Jito bundle sent: ${jitoResult.bundleId?.take(16)}... landed=${jitoResult.landed}")
                    
                    // If Jito succeeded and landed, we need to get the actual tx signature
                    // For now, fall through to normal send as backup confirmation
                    if (jitoResult.landed && jitoResult.signature != null) {
                        return finalized(jitoResult.signature)
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
                    if (jErr != "ASYNC_8009") com.lifecyclebot.engine.ErrorLogger.warn("SolanaWallet", 
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

        // V5.0.8085 — the reply is classified against the local signature: acceptance needs result == local id; a
        // JSON-RPC error is a rejection only when no other copy may be in flight; a timeout / 5xx / blank reply is
        // OUTCOME_UNKNOWN and goes to confirmation under the local id (never "NO_SIGNATURE").
        val result = submitRpc8085(signedB64, params, localSig8085, maybeElsewhere8085, jitoSent8085)
        solBalance8009 = null
        if (inFlight8009.size > 200) inFlight8009.clear()
        inFlight8009[result] = signedB64 to false
        // V5.0.8083 — preflight passed on the ladder head: the same bytes fan out to Alchemy + every paid RPC now
        // (previously only the confirm loop's later re-broadcast reached a second endpoint).
        echoSend8009(result, signedB64, viaSender = false)
        return finalized(result)
    }

    // ── V5.0.8085 §SIGNED_TX_IDENTITY ─────────────────────

    /** Rejected by every transport that could have received it: no copy is in flight, a new transaction is safe. */
    class SubmitRejected8085(msg: String) : RuntimeException("sendTransaction failed: $msg")
    /** Chain truth: the signature is in a block with meta.err — no fill. */
    class OnChainFailed8085(val signature: String, err: String) : RuntimeException("Transaction failed on-chain: $err (sig=$signature)")
    /** Chain truth: signature unseen while its recent blockhash is no longer valid — it can never land. */
    class TxExpiredProven8085(val signature: String, elapsedS: Long) :
        RuntimeException("Transaction not found after ${elapsedS}s — blockhash expired (proven): $signature")
    /** A previous transaction for the same mint+side is still unresolved: no second order is signed. */
    class DuplicateOrderPrevented8085(val existingSignature: String, side: String) :
        RuntimeException("TX_DUPLICATE_ORDER_PREVENTED_8085 side=$side pending=${existingSignature.take(16)}")

    /** Sign, derive the id from the signed bytes, refuse a competing order, persist — all before any network call. */
    private fun signTracked8085(txBase64: String, intent: SignedTxLedger8085.Intent?): SignedSerializedTransaction6649 {
        ensureLedgerDurable8085()
        if (intent != null) guardDuplicate8085(intent)
        val signed = signSerializedTransaction6649(txBase64)
        SignedTxLedger8085.record(signed.signature, signed.signedBase64, signed.blockhash8085, intent, requireDurable = true)
        return signed
    }

    private fun guardDuplicate8085(intent: SignedTxLedger8085.Intent) {
        val prev = SignedTxLedger8085.unresolvedFor(intent.mint, intent.side) ?: return
        try { resolveOnce8085(prev) } catch (_: Throwable) {}
        if (!prev.unresolved) return
        // V5.0.8094 — elapsed time is not chain evidence: a previous signature stays UNKNOWN (and blocks a second
        // order) until signature status or blockhash expiry proves it. The status / blockhash reads walk the RPC
        // ladder (an exhausted Helius fails over to Alchemy / public nodes), and scheduleResolve8085 keeps retrying.
        try { scheduleResolve8085(prev.signature) } catch (_: Throwable) {}
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TX_DUPLICATE_ORDER_PREVENTED_8085")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("TX_DUPLICATE_ORDER_PREVENTED_8085",
                "mint=${intent.mint.take(10)} side=${intent.side} pending=${prev.signature.take(16)} state=${prev.state} route=${intent.route}")
        } catch (_: Throwable) {}
        throw DuplicateOrderPrevented8085(prev.signature, intent.side)
    }

    /** One bounded finality read for a ledger row: status first, then (signature unseen) the blockhash expiry proof. */
    private fun resolveOnce8085(e: SignedTxLedger8085.Entry): SignedTxLedger8085.State {
        val status = signatureStatus8085(e.signature)
        when {
            status == null -> {}
            status.first.isNotBlank() && confirmedEnough7980(status.second) -> SignedTxLedger8085.transition(e.signature, SignedTxLedger8085.State.FAILED_ON_CHAIN, status.first.take(120))
            confirmedEnough7980(status.second) -> SignedTxLedger8085.transition(e.signature, SignedTxLedger8085.State.CONFIRMED, "status=${status.second}")
        }
        if (status == null && SignedTxLedger8085.expiredProven(false, blockhashValid8085(e.blockhash)))
            SignedTxLedger8085.transition(e.signature, SignedTxLedger8085.State.EXPIRED_PROVEN, "blockhash_invalid_signature_unseen")
        return e.state
    }

    /** (errText, confirmationStatus) when the signature is seen; null when unseen. Throws on a failed read. */
    private fun signatureStatus8085(sig: String): Pair<String, String>? {
        val v = signatureStatusObject8090(sig) ?: return null
        val err = if (v.has("err") && !v.isNull("err")) (v.optJSONObject("err")?.toString() ?: v.opt("err").toString()) else ""
        return err to v.optString("confirmationStatus", "")
    }

    private fun signatureStatusObject8090(sig: String): JSONObject? {
        val resp = rpc("getSignatureStatuses", JSONArray().put(JSONArray().put(sig)).put(JSONObject().put("searchTransactionHistory", true)))
        check(!resp.has("error")) { "SIGNATURE_STATUS_RPC_ERROR" }
        val values = resp.getJSONObject("result").getJSONArray("value")
        check(values.length() == 1) { "SIGNATURE_STATUS_MALFORMED" }
        return if (values.isNull(0)) null else values.getJSONObject(0)
    }

    /** isBlockhashValid at finalized commitment: true / false, or null when unknown (no proof either way). */
    private fun blockhashValid8085(blockhash: String): Boolean? {
        if (blockhash.isBlank()) return null
        return try {
            val r = rpc("isBlockhashValid", JSONArray().put(blockhash).put(JSONObject().put("commitment", "finalized")))
            val v = r.optJSONObject("result") ?: return null
            v.opt("value") as? Boolean
        } catch (_: Throwable) { null }
    }

    /**
     * One JSON-RPC sendTransaction of the exact signed bytes, classified against the local id. Returns the id when the
     * transaction may be in flight (accepted or unknown); throws [SubmitRejected8085] only on a definite rejection.
     */
    private fun submitRpc8085(signedB64: String, params: JSONArray, localSig: String, maybeElsewhere: Boolean, jitoSent: Boolean): String {
        SignedTxLedger8085.transition(localSig, SignedTxLedger8085.State.SUBMIT_ATTEMPTED, "rpc")
        sendMaybeDelivered8085.set(false)
        val resp = try { rpc("sendTransaction", params) } catch (t: Throwable) {
            val maybe = maybeElsewhere || jitoSent || sendMaybeDelivered8085.get() == true
            val why = t.message ?: t.javaClass.simpleName
            if (!maybe) {
                SignedTxLedger8085.transition(localSig, SignedTxLedger8085.State.SUBMIT_REJECTED, "no_endpoint_reached: ${why.take(100)}")
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TX_RPC_REJECTED_8085") } catch (_: Throwable) {}
                throw SubmitRejected8085("no endpoint reached: $why")
            }
            SignedTxLedger8085.transition(localSig, SignedTxLedger8085.State.OUTCOME_UNKNOWN, "rpc_threw: ${why.take(100)}")
            unknownInFlight8085(localSig, signedB64)
            return localSig
        }
        val result = resp.optString("result", "")
        val error = resp.optJSONObject("error")?.optString("message", "unknown RPC error")
        when (SignedTxLedger8085.classifyRpcReply(true, result, error, localSig)) {
            SignedTxLedger8085.Submit.ACCEPTED -> {
                SignedTxLedger8085.transition(localSig, SignedTxLedger8085.State.ACCEPTED, acceptedBy = "rpc")
                return localSig
            }
            SignedTxLedger8085.Submit.REJECTED -> {
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TX_RPC_REJECTED_8085") } catch (_: Throwable) {}
                val msg = error ?: "unknown RPC error"
                if (SignedTxLedger8085.rejectionIsDefinite(msg, maybeElsewhere || sendMaybeDelivered8085.get() == true, jitoSent)) {
                    SignedTxLedger8085.transition(localSig, SignedTxLedger8085.State.SUBMIT_REJECTED, msg.take(120))
                    throw SubmitRejected8085(msg)
                }
                SignedTxLedger8085.transition(localSig, SignedTxLedger8085.State.OUTCOME_UNKNOWN, "rpc_rejected_other_copy_in_flight: ${msg.take(90)}")
                unknownInFlight8085(localSig, signedB64)
                return localSig
            }
            SignedTxLedger8085.Submit.UNKNOWN -> {
                if (result.isNotBlank() && result != localSig) try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TX_SIGNATURE_MISMATCH_8085")
                    com.lifecyclebot.engine.ForensicLogger.lifecycle("TX_SIGNATURE_MISMATCH_8085", "local=${localSig.take(16)} returned=${result.take(16)}")
                } catch (_: Throwable) {}
                SignedTxLedger8085.transition(localSig, SignedTxLedger8085.State.OUTCOME_UNKNOWN, if (result.isBlank()) "blank_jsonrpc_result" else "signature_mismatch")
                unknownInFlight8085(localSig, signedB64)
                return localSig
            }
        }
    }

    /** Unknown outcome: keep re-broadcasting the identical bytes while confirmation decides (one signature lands once). */
    private fun unknownInFlight8085(sig: String, signedB64: String) {
        if (inFlight8009.size > 200) inFlight8009.clear()
        inFlight8009[sig] = signedB64 to false
        echoSend8009(sig, signedB64, viaSender = false)
    }

    /** One scheduled read per unresolved signature; waiting consumes no worker thread. */
    fun scheduleResolve8085(sig: String, recovered: Boolean = false) {
        if (!resolving8085.add(sig)) return
        fun enqueue(delaySeconds: Long) {
            try {
                resolver8085.schedule({
                    val e = SignedTxLedger8085.get(sig)
                    if (e == null || !e.unresolved) {
                        resolving8085.remove(sig)
                        return@schedule
                    }
                    try {
                        val before = e.state
                        resolveOnce8085(e)
                        if (e.state == SignedTxLedger8085.State.CONFIRMED && before != e.state)
                            onFinalityRecovered8085(e, recovered)
                        if (e.unresolved && e.signedB64.isNotBlank() && blockhashValid8085(e.blockhash) == true)
                            echoSend8009(sig, e.signedB64, viaSender = false)
                    } catch (_: Exception) {
                        // A failed read is UNKNOWN, not expiry. Preserve identity and try again.
                    }
                    if (e.unresolved) enqueue(15L) else resolving8085.remove(sig)
                }, delaySeconds, TimeUnit.SECONDS)
            } catch (_: java.util.concurrent.RejectedExecutionException) { resolving8085.remove(sig) }
        }
        enqueue(0L)
    }

    private fun onFinalityRecovered8085(e: SignedTxLedger8085.Entry, recovered: Boolean) {
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TX_FINALITY_RECOVERED_8085")
            com.lifecyclebot.engine.ForensicLogger.lifecycle("TX_FINALITY_RECOVERED_8085",
                "sig=${e.signature.take(16)} mint=${e.mint.take(10)} side=${e.side} route=${e.route} afterRestart=$recovered")
        } catch (_: Throwable) {}
        // A row restored after a restart has no live caller holding its signature: the reconcile queue takes it
        // (deduplicated by signature) so the wallet delta is booked once through the canonical reconcile path.
        if (!recovered || e.mint.isBlank()) return
        try {
            val key = "RECOVERED_8085_${e.signature.take(12)}"
            if (e.side == "BUY") com.lifecyclebot.engine.PendingReconcileQueue.registerBuy(sig = e.signature, mint = e.mint, symbol = "",
                tradeKey = key, traderTag = "MEME", wallet = this)
            else com.lifecyclebot.engine.PendingReconcileQueue.registerSell(sig = e.signature, mint = e.mint, symbol = "",
                tradeKey = key, traderTag = "MEME", wallet = this)
        } catch (_: Throwable) {}
    }

    /** Durable rows: encrypted prefs, written synchronously before the send; restored once per process (restart). */
    private fun ensureLedgerDurable8085() {
        if (ledgerWired8085) return
        synchronized(SolanaWallet::class.java) {
            if (ledgerWired8085) return
            val prefs = ledgerPrefs8085() ?: error("SIGNED_ORDER_STORAGE_UNAVAILABLE")
            SignedTxLedger8085.persister = { rows ->
                check(prefs.edit().putString("rows", rows.joinToString("\n") { SignedTxLedger8085.encode(it) }).commit()) {
                    "SIGNED_ORDER_STORAGE_COMMIT_FAILED"
                }
            }
            val saved = prefs.getString("rows", "").orEmpty().split('\n').filter { it.isNotBlank() }
            SignedTxLedger8085.restore(saved) { e -> scheduleResolve8085(e.signature, recovered = true) }
            ledgerWired8085 = true
        }
    }

    /** Called at wallet connect: a restart with unresolved signed transactions resumes their finality reads. */
    fun restoreSignedLedger8085() { try { ensureLedgerDurable8085() } catch (_: Throwable) {} }

    private fun ledgerPrefs8085(): android.content.SharedPreferences? = try {
        val ctx = com.lifecyclebot.AATEApp.appContextOrNull()
        if (ctx == null) null else {
            val master = androidx.security.crypto.MasterKey.Builder(ctx.applicationContext)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
            androidx.security.crypto.EncryptedSharedPreferences.create(ctx.applicationContext, "aate_signed_tx_8085", master,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }
    } catch (_: Throwable) { null }


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
        val envelope = SolanaSigningEnvelope.validate(txBytes, keyPair.publicKey)
        val msgBytes = txBytes.sliceArray(envelope.messageOffset until txBytes.size)

        val sig        = TweetNaclFast.Signature(null, keyPair.secretKey)
            .detached(msgBytes)

        val result     = txBytes.copyOf()
        System.arraycopy(sig, 0, result, envelope.signatureOffset, 64)
        return result
    }

    /** V5.0.6649 — split signing from submission for crash-safe bridge jobs.
     * The deterministic signature is persisted before broadcast, so restart
     * recovery can rebroadcast the exact same bytes instead of creating a
     * second order with a fresh blockhash. */
    data class SignedSerializedTransaction6649(val signature: String, val signedBase64: String, val blockhash8085: String = "")

    fun signSerializedTransaction6649(txBase64: String): SignedSerializedTransaction6649 {
        val txBytes = android.util.Base64.decode(txBase64, android.util.Base64.DEFAULT)
        val signedBytes = signVersionedTx(txBytes)
        val envelope = SolanaSigningEnvelope.validate(signedBytes, keyPair.publicKey)
        val signatureBytes = signedBytes.copyOfRange(envelope.signatureOffset, envelope.signatureOffset + 64)
        return SignedSerializedTransaction6649(
            signature = Base58.base58Encode(signatureBytes),
            signedBase64 = android.util.Base64.encodeToString(signedBytes, android.util.Base64.NO_WRAP),
            blockhash8085 = if (envelope.blockhashOffset >= 0)
                Base58.base58Encode(signedBytes.copyOfRange(envelope.blockhashOffset, envelope.blockhashOffset + 32)) else "",
        )
    }

    fun sendSignedAndConfirm6649(signed: SignedSerializedTransaction6649): String {
        // V5.0.8085 — the bridge source tx joins the ledger under its own id; a send timeout / blank reply is no longer
        // a "submit failed" (the source could still land and the bridge order fill): confirmation decides.
        ensureLedgerDurable8085()
        SignedTxLedger8085.record(signed.signature, signed.signedBase64, signed.blockhash8085,
            SignedTxLedger8085.consumeIntent(), requireDurable = true)
        sendRawTransaction(signed.signedBase64, signed.signature)
        awaitConfirmationKeepingSignature7733(signed.signature)
        return signed.signature
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
        // V5.0.7980 — speed. This waited for "finalized" (~32 slots, ~13 s after "confirmed")
        // and polled every 1.5-2 s, so every buy and sell sat ~15 s+ past its real landing —
        // a peak capture filled well after the print it chased. "confirmed" (a supermajority
        // has voted the block) is the trading-grade finality every venue and wallet uses; it
        // is returned as soon as it is seen. Polls every 400 ms for the first 10 s, then 1 s.
        // An on-chain error still throws; not found after 30 s still means expired.
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        // V5.0.8009 — while the signature is not yet seen, the signed bytes are re-broadcast every
        // 2 s (both routes) until ~25 s: Sender/RPC were sent once with maxRetries 0, so a dropped
        // transaction used to wait the full 30 s "not found" window before anything happened.
        val echo = inFlight8009[signature] ?: SignedTxLedger8085.get(signature)?.signedB64?.takeIf { it.isNotBlank() }?.let { it to false }
        var lastEcho = start
        var lastExpiryCheck8085 = 0L
        try {
        while (System.currentTimeMillis() < deadline) {
            val elapsed = System.currentTimeMillis() - start
            val pause = if (elapsed < 10_000L) 400L else 1_000L
            if (echo != null && elapsed in 1_500L..25_000L && System.currentTimeMillis() - lastEcho >= 2_000L) {
                lastEcho = System.currentTimeMillis()
                echoSend8009(signature, echo.first, echo.second)
            }
            try {
                val value = signatureStatusObject8090(signature)

                when {
                    value == null -> {
                        // Not yet propagated — normal for the first second or two.
                        // V5.0.8085 — "not found after 30 s" was thrown as if expired. Expiry is now PROVEN (signature
                        // unseen + recent blockhash invalid) before the caller may build a new transaction; otherwise
                        // the wait runs to its deadline and the outcome stays unknown under this signature.
                        if (elapsed > 30_000L && System.currentTimeMillis() - lastExpiryCheck8085 >= 4_000L) {
                            lastExpiryCheck8085 = System.currentTimeMillis()
                            val bh = SignedTxLedger8085.get(signature)?.blockhash.orEmpty()
                            if (SignedTxLedger8085.expiredProven(false, blockhashValid8085(bh))) {
                                SignedTxLedger8085.transition(signature, SignedTxLedger8085.State.EXPIRED_PROVEN, "await_unseen_blockhash_invalid")
                                throw TxExpiredProven8085(signature, elapsed / 1000)
                            }
                        }
                        Thread.sleep(pause)
                    }
                    value.has("err") && !value.isNull("err") && confirmedEnough7980(value.optString("confirmationStatus")) -> {
                        val err = value.optJSONObject("err")?.toString() ?: value.opt("err").toString()
                        SignedTxLedger8085.transition(signature, SignedTxLedger8085.State.FAILED_ON_CHAIN, err.take(120))
                        throw OnChainFailed8085(signature, err)
                    }
                    else -> {
                        if (confirmedEnough7980(value.optString("confirmationStatus", ""))) {
                            SignedTxLedger8085.transition(signature, SignedTxLedger8085.State.CONFIRMED, "await")
                            return true
                        }
                        Thread.sleep(pause)
                    }
                }
            } catch (e: RuntimeException) {
                throw e   // re-throw explicit failures
            } catch (_: Exception) {
                Thread.sleep(pause)
            }
        }
        throw RuntimeException("Confirmation timeout after ${timeoutMs/1000}s: $signature")
        } finally { inFlight8009.remove(signature) }
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
        
        val sig = signAndSend(
            txBase64,
            useJito,
            jitoTipLamports,
            senderCompatible,
            awaitFinality = false,
        )
        awaitConfirmationKeepingSignature7733(sig)
        return sig
    }

    /**
     * V5.0.7733 §A_THROW_AFTER_THE_SEND_IS_NOT_A_FAILED_BUY.
     *
     * The transaction named by [signature] has been sent. The caller's
     * confirmation wait threw, so whether it landed is unknown; the signature
     * is not. Executor.liveBuy used to see only the throw ("Buy threw:
     * unknown", 5.0.7732 $WIF, five seconds after BUY_BROADCAST), file the buy
     * as failed, and meet its own tokens eight seconds later as a recovered
     * orphan at the observed mark in WALLET_RECOVERED.
     */
    class SentButUnconfirmed7733(val signature: String, cause: Throwable) :
        RuntimeException("SENT_UNCONFIRMED sig=${signature.take(16)}: ${cause.message ?: cause.javaClass.simpleName}", cause)

    private fun awaitConfirmationKeepingSignature7733(sig: String) {
        try {
            awaitConfirmation(sig)
        } catch (e: SentButUnconfirmed7733) {
            throw e
        } catch (e: OnChainFailed8085) {
            throw e          // V5.0.8085 — chain truth: no fill, a new transaction is safe
        } catch (e: TxExpiredProven8085) {
            throw e          // V5.0.8085 — chain truth: can never land
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TX_SENT_CONFIRMATION_THREW_7733") } catch (_: Throwable) {}
            SignedTxLedger8085.transition(sig, SignedTxLedger8085.State.OUTCOME_UNKNOWN, "confirmation_window_ended: ${(e.message ?: "").take(80)}")
            scheduleResolve8085(sig)
            throw SentButUnconfirmed7733(sig, e)
        }
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
        val local8085 = signTracked8085(txBase64, SignedTxLedger8085.consumeIntent())
        val signedB64 = local8085.signedBase64
        val localSig8085 = local8085.signature
        var executeMaybeDelivered8085 = false
        
        // ═══════════════════════════════════════════════════════════════════
        // CRITICAL FIX: Retry /execute for RFQ routes, only self-broadcast for Metis
        // RFQ routes have Jupiter-held market maker signatures that only /execute can add
        // ═══════════════════════════════════════════════════════════════════
        val maxAttempts = 3
        var lastException: Exception? = null
        
        for (attempt in 1..maxAttempts) {
            try {
                val jupiter = JupiterApi(jupiterApiKey)
                SignedTxLedger8085.transition(localSig8085, SignedTxLedger8085.State.SUBMIT_ATTEMPTED, "jupiter_ultra_execute")
                val returned8085 = jupiter.executeUltra(signedB64, requestId)
                // V5.0.8085 — /execute's reply is provider acknowledgement; the transaction id is the local one.
                if (returned8085 != localSig8085) try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TX_SIGNATURE_MISMATCH_8085")
                    com.lifecyclebot.engine.ForensicLogger.lifecycle("TX_SIGNATURE_MISMATCH_8085", "route=ULTRA local=${localSig8085.take(16)} returned=${returned8085.take(16)}")
                } catch (_: Throwable) {}
                SignedTxLedger8085.transition(localSig8085, SignedTxLedger8085.State.ACCEPTED, acceptedBy = "jupiter_ultra")
                val signature = localSig8085
                // V5.0.8083 §ULTRA_WAS_SINGLE_ROUTE — most Jupiter buys AND sells land through /execute, which had no
                // Helius RPC / Alchemy copy and no re-broadcast. A non-RFQ Ultra tx is complete with our signature, so
                // the identical bytes now fan out to every paid endpoint and join the confirm-loop re-broadcast.
                // RFQ bytes carry only half the signatures (the market maker signs inside /execute): never self-sent.
                if (!isRfqRoute) {
                    solBalance8009 = null
                    if (inFlight8009.size > 200) inFlight8009.clear()
                    inFlight8009[signature] = signedB64 to false
                    echoSend8009(signature, signedB64, viaSender = false)
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("TX_FANOUT_ULTRA_8083") } catch (_: Throwable) {}
                }

                // Still await confirmation to be safe
                // V5.0.7733 — a confirmation throw after /execute returned a
                // signature carries that signature out; it is not retried as
                // if nothing had been sent.
                awaitConfirmationKeepingSignature7733(signature)
                return signature
            } catch (e: SentButUnconfirmed7733) {
                throw e
            } catch (e: OnChainFailed8085) {
                throw e
            } catch (e: TxExpiredProven8085) {
                throw e
            } catch (e: Exception) {
                lastException = e
                // V5.0.8085 — /execute that reached Jupiter may have submitted these bytes; only a request-validation
                // refusal (400/422/invalid) proves it did not.
                if (!ultraRequestRefused8085(e.message.orEmpty())) executeMaybeDelivered8085 = true
                android.util.Log.w("SolanaWallet", "⚠️ Ultra execute attempt $attempt/$maxAttempts failed: ${e.message?.take(60)}")
                
                // Check if error is retryable
                val errorMsg = e.message?.lowercase() ?: ""
                // V5.0.8089 — a 5xx from /execute is Jupiter's outage: an RFQ order can only land through /execute,
                // so it is retried; a non-RFQ order skips the retries and goes straight to RPC self-broadcast below.
                val post5xx8089 = JupiterApi.isTransientPostFailure8089(e.message) && !errorMsg.contains("timeout")
                if (post5xx8089 && !isRfqRoute) {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ULTRA_EXECUTE_5XX_RPC_FALLBACK_8089") } catch (_: Throwable) {}
                    break
                }
                val isRetryable = errorMsg.contains("timeout") || 
                                  errorMsg.contains("rate") ||
                                  errorMsg.contains("network") ||
                                  errorMsg.contains("server") ||
                                  post5xx8089 ||                  // V5.0.8089 — RFQ: /execute 5xx retried with backoff
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
            // V5.0.8085 — never self-broadcast an RFQ order, but an /execute that may have submitted it is not a
            // failure: its own id goes to confirmation (unknown -> SentButUnconfirmed7733 for the caller).
            if (executeMaybeDelivered8085) {
                SignedTxLedger8085.transition(localSig8085, SignedTxLedger8085.State.OUTCOME_UNKNOWN, "rfq_execute_unconfirmed")
                awaitConfirmationKeepingSignature7733(localSig8085)
                return localSig8085
            }
            SignedTxLedger8085.transition(localSig8085, SignedTxLedger8085.State.SUBMIT_REJECTED, "rfq_execute_refused")
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
            val signature = sendRawTransaction(signedB64, localSig8085, executeMaybeDelivered8085)
            android.util.Log.i("SolanaWallet", "✅ RPC fallback broadcast succeeded! sig=${signature.take(20)}...")
            awaitConfirmationKeepingSignature7733(signature)
            return signature
        } catch (sent: SentButUnconfirmed7733) {
            // V5.0.7733 — the fallback DID broadcast; the caller gets the signature, not the older Ultra error.
            throw sent
        } catch (e: OnChainFailed8085) {
            throw e
        } catch (e: TxExpiredProven8085) {
            throw e
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
    private fun sendRawTransaction(signedTxB64: String, localSig8085: String, maybeElsewhere8085: Boolean = false): String {
        val params = JSONArray()
            .put(signedTxB64)
            .put(JSONObject()
                .put("encoding", "base64")
                .put("skipPreflight", false)
                .put("preflightCommitment", "confirmed")
                .put("maxRetries", 3)
            )
        
        // V5.0.8085 — classified against the local id (accepted / definite rejection / unknown -> confirmation).
        val signature = submitRpc8085(signedTxB64, params, localSig8085, maybeElsewhere8085, jitoSent = false)
        // V5.0.8083 — the same bytes fan out to Helius RPC + Alchemy and join the re-broadcast loop.
        solBalance8009 = null
        if (inFlight8009.size > 200) inFlight8009.clear()
        inFlight8009[signature] = signedTxB64 to false
        echoSend8009(signature, signedTxB64, viaSender = false)
        return signature
    }

    private fun ultraRequestRefused8085(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("400") || m.contains("422") || m.contains("invalid") || m.contains("unauthorized") || m.contains("401")
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

        // V5.0.6685 — one runtime provider authority. Explicit wallet RPC
        // stays first; configured encrypted Helius precedes keyless fallbacks.
        val rpcsToTry = com.lifecyclebot.engine.RuntimeProviderAuthority6685.rpcCandidates(rpcUrl)

        var lastBody = "{}"
        var lastError: Exception? = null

        for (endpoint in rpcsToTry) {
            for (attempt in 0..1) {
                try {
                    // V5.0.7131 — same bypass as the strict snapshot below. This
                    // is the other multi-endpoint wallet read, and it walks the
                    // same fallback list, so leaving it behind the host breaker
                    // would keep exactly half the blindness.
                    val req = Request.Builder().url(endpoint)
                        .header("Content-Type", "application/json")
                        .header(com.lifecyclebot.network.HostCircuitInterceptor.PROBE_HEADER_6976, "1")
                        .post(body.toRequestBody(JSON_MT)).build()
                    val resp = http.newCall(req).execute()
                    val code = resp.code
                    lastBody = resp.body?.string() ?: "{}"

                    // V5.9.267 — fail over on auth failures (HTTP 401/403)
                    // The user's primary RPC API key was rejected with
                    // 'invalid api key provided' but rpc() was returning that
                    // error verbatim instead of trying fallbacks. Result: every
                    // sendTransaction failed and trading fees got dropped.
                    if (code == 429 && rpcExhaustedReply8094(code, lastBody)) {
                        if (endpoint.contains("helius", ignoreCase = true)) try {
                            com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.noteExhausted8094()
                        } catch (_: Throwable) {}
                        break
                    }
                    if (code == 402) {
                        if (endpoint.contains("helius", ignoreCase = true)) try {
                            com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.noteExhausted8094()
                        } catch (_: Throwable) {}
                        break
                    }
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
                            // V5.0.8094 — a plan out of credits answers every call with an error; it is a dead
                            // endpoint for now, not the caller's answer (8093 sells: status reads never failed over,
                            // the previous signature stayed unresolved, every new sell was refused: no signature).
                            if (rpcExhaustedReply8094(code, errMsg)) {
                                if (endpoint.contains("helius", ignoreCase = true)) try {
                                    com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.noteExhausted8094()
                                } catch (_: Throwable) {}
                                break
                            }
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
                    // V5.0.8083 — a sendTransaction 429/5xx fails over to the next paid endpoint at once, no 1 s sleep.
                    // V5.0.8085 — a 5xx may have forwarded the bytes: the outcome of this send is no longer provable.
                    if (method == "sendTransaction" && code >= 500) sendMaybeDelivered8085.set(true)
                    if (method == "sendTransaction") break
                    if (attempt == 0) Thread.sleep(1_000)
                } catch (e: Exception) {
                    lastError = e
                    // V5.0.8085 — only a refused connection / unresolved host / TLS rejection proves nothing was sent.
                    if (method == "sendTransaction" && e !is java.net.ConnectException && e !is java.net.UnknownHostException &&
                        !isTlsTrustFailure(e)) sendMaybeDelivered8085.set(true)
                    if (isTlsTrustFailure(e)) {
                        markEndpointUnhealthy(endpoint, "TLS_REJECTED")
                        try {
                            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                                "WALLET_RPC_TLS_REJECTED",
                                "method=$method endpoint=${endpoint.take(48)} action=failover err=${e.message?.take(80)}",
                            )
                            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("WALLET_RPC_TLS_REJECTED")
                        } catch (_: Throwable) {}
                        break
                    }
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
    fun getTokenAccountsWithDecimals(): Map<String, CanonicalTokenAmount> = try {
        getTokenAccountsWithDecimalsStrict()
    } catch (e: Exception) {
        android.util.Log.w("SolanaWallet", "getTokenAccountsWithDecimals non-strict failed: ${e.message}")
        emptyMap()
    }

    private fun walletRpcEndpointsForTokenSnapshot(): List<String> {
        // V5.0.6686 — PATCH-ROT REPAIR. 6685 migrated generic RPC calls to the
        // encrypted runtime provider authority but this token-account path was
        // accidentally left on rpcUrl + the legacy fallback list. That made
        // wallet reconciliation use a different provider ladder from execution.
        // One authority, one round-robin/cooldown policy, no credential drift.
        val candidates = com.lifecyclebot.engine.RuntimeProviderAuthority6685
            .rpcCandidates(rpcUrl)
        return applyRoundRobin(candidates)
    }

    private fun rpcTokenAccountsByOwnerFast(programId: String): JSONObject =
        rpcTokenAccountsByOwnerFiltered(JSONObject().put("programId", programId), programId)

    /**
     * V5.0.7374 — the same endpoint ladder, for any getTokenAccountsByOwner filter.
     * [programId] is the label used in logs; [overallDeadlineMs] (>0) stops the
     * ladder once a caller's own budget is spent.
     */
    private fun rpcTokenAccountsByOwnerFiltered(filter: JSONObject, programId: String, overallDeadlineMs: Long = 0L): JSONObject {
        val params = JSONArray()
            .put(publicKeyB58)
            .put(filter)
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
        // V5.0.7210 §THE_FAST_BUDGET_WAS_SHORTER_THAN_THE_PROVIDER_THAT_WORKS.
        //
        // Was connect 900 / read 1_400 / call 1_800 ms. Measured on the
        // operator's own 5.0.7206 run, from the API health block:
        //
        //   helius      sr=99%   avg=1071ms   s=389
        //   helius_rpc  sr=100%  avg=1699ms   s=2
        //   solana_rpc  sr=95%   avg=1005ms   s=61
        //
        // helius_rpc's MEAN is 1699ms against a 1400ms read budget and an
        // 1800ms call budget. A provider whose average response exceeds the
        // deadline fails more often than it succeeds by definition, and
        // getTokenAccountsByOwner is one of the heaviest reads on the wire —
        // it returns every token account the owner holds. That is precisely
        // the InterruptedIOException:timeout in the device log.
        //
        // So the budget is set from the measurement rather than from a guess:
        // read 3_500ms is ~2x helius_rpc's mean, with connect and call sized
        // around it. "Fast" has to mean fast enough to get an answer.
        //
        // This does not make the ladder slow. With 7210's preference fix the
        // authenticated endpoint is asked FIRST, so the common path is one
        // call at ~1.1-1.7s — quicker end to end than the old budget, which
        // timed out repeatedly before reaching a provider that would answer.
        //
        // A total wall-clock deadline replaces the per-attempt squeeze as the
        // thing that bounds this function. That is the correct place for it:
        // bounding the whole ladder protects the caller, while bounding each
        // attempt so tightly that no healthy provider can reply protects
        // nothing and blinds the position ledger.
        val fastHttp = http.newBuilder()
            .connectTimeout(1_500, TimeUnit.MILLISECONDS)
            .readTimeout(3_500, TimeUnit.MILLISECONDS)
            .callTimeout(4_000, TimeUnit.MILLISECONDS)
            .build()
        val ladderDeadlineMs7210 = System.currentTimeMillis() + 9_000L
        val failures = mutableListOf<String>()
        for ((idx7210, endpoint) in endpoints.withIndex()) {
            if (overallDeadlineMs > 0L && System.currentTimeMillis() > overallDeadlineMs) {
                failures.add("CALLER_DEADLINE_7374_after_$idx7210")
                break
            }
            // Never abandon the ladder before the FIRST endpoint has answered:
            // the preferred head is index 0 and it is the one most likely to
            // succeed, so the deadline may only cut the anonymous tail.
            if (idx7210 > 0 && System.currentTimeMillis() > ladderDeadlineMs7210) {
                failures.add("LADDER_DEADLINE_7210_after_$idx7210")
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector
                        .labelInc("WALLET_RPC_LADDER_DEADLINE_7210")
                } catch (_: Throwable) {}
                break
            }
            try {
                // V5.0.7131 — THE FALLBACK CHAIN WAS NEVER CALLED.
                //
                // Operator: "there shouldn't be rpc issues we have fall back to
                // guarantee coverage." They are right, and the coverage was
                // defeated locally rather than by any provider.
                //
                // Their 5.0.7129 device logged "Tokenkeg:getTokenAccountsByOwner
                // failed on 14 wallet endpoints: HTTP599|HTTP599|HTTP599...".
                // HTTP 599 is not a provider response. SharedHttpClient documents
                // it as ours: HostCircuitInterceptor "short-circuits NXDOMAIN and
                // 5xx/429 storms with a 599 SYNTHETIC response — zero TLS, zero
                // TCP, zero DNS during cool-down."
                //
                // So all fourteen endpoints were refused by our own breaker
                // without a packet leaving the device, and the identical
                // synthetic code on every one of them is the tell: fourteen
                // independent providers do not fail in the same instant with the
                // same non-standard status. The same snapshot has KeyValidator
                // reporting helius live=true http=200 with getTokenAccountsByOwner
                // explicitly OK, while this read called the wallet unreadable.
                //
                // What that cost: the reconciler read the resulting empty wallet
                // as proof of an empty wallet and booked six live positions as
                // EXTERNAL_RUG_CLOSE at -100% each. V5.0.7130 stopped the
                // write-off; this stops the blindness that caused it.
                //
                // PROBE_HEADER_6976 already exists for exactly this — V5.0.6976
                // added it so a readiness probe could opt out of the circuit, and
                // isProbe bypasses the breaker for every provider except Birdeye
                // (whose paid budget is enforced separately and is untouched
                // here). The wallet token snapshot is the ground truth the entire
                // position ledger rests on; it must be able to ASK, and a
                // provider that genuinely fails still fails on the wire.
                //
                // This does not remove rate-limit protection from this path. The
                // wallet keeps its own per-endpoint 30s cooldown (V5.0.4595) plus
                // round-robin across all endpoints, and that layer is the correct
                // one: it reacts to real responses, and its fail-safe already
                // falls back to the full list so the snapshot never goes dark.
                // The host breaker was redundant here and turned a degraded
                // provider into total blindness.
                val req = Request.Builder().url(endpoint)
                    .header("Content-Type", "application/json")
                    .header(com.lifecyclebot.network.HostCircuitInterceptor.PROBE_HEADER_6976, "1")
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
                    // V5.0.6686 — ANY JSON-RPC error from getTokenAccountsByOwner
                    // is an endpoint failure, never a usable wallet snapshot.
                    // In 6685 an unsupported/free-plan provider returned
                    // "chain is not available on free plan"; because it was not
                    // classified as auth/rate-limit, the function returned that
                    // JSON immediately and never tried the next healthy RPC.
                    val msg = err.optString("message", err.toString())
                    markEndpointUnhealthy(endpoint, "RPC:${msg.take(24)}")
                    failures.add("${endpoint.take(24)}:RPC:${msg.take(48)}")
                    try {
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "WALLET_RPC_PROVIDER_ERROR_FAILOVER_6686",
                            "program=${programId.take(8)} endpoint=${endpoint.take(48)} err=${msg.take(96)} action=continue_next_rpc",
                        )
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("WALLET_RPC_PROVIDER_ERROR_FAILOVER_6686")
                    } catch (_: Throwable) {}
                    continue
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

    private fun heliusDasFungibleTokensByOwner(): Map<String, CanonicalTokenAmount> {
        val apiKey = com.lifecyclebot.engine.RuntimeProviderAuthority6685.configuredHeliusKey()
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
        val out = mutableMapOf<String, CanonicalTokenAmount>()
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
            val amount = try { CanonicalTokenAmount.fromRpcAmount(rawText, decimals) } catch (_: Throwable) { null }
            if (mint.isNotBlank() && amount != null && amount.raw.signum() > 0) out[mint] = amount
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
    fun getTokenAccountsWithDecimalsStrict(): Map<String, CanonicalTokenAmount> {
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
        val out = mutableMapOf<String, CanonicalTokenAmount>()
        val parsedMints7253 = mutableSetOf<String>()
        val frozenMints7253 = mutableSetOf<String>()
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
                if (mint.isNotBlank()) {
                    parsedMints7253 += mint
                    val frozen = info.optString("state", "").equals("frozen", ignoreCase = true)
                    if (com.lifecyclebot.engine.truth.WalletAssetClassifier6387.classifyFrozen(frozen) != null) {
                        frozenMints7253 += mint
                        try {
                            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("WALLET_FROZEN_TOKEN_IGNORED_7253")
                            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                                "WALLET_FROZEN_TOKEN_IGNORED_7253",
                                "mint=${mint.take(12)} program=${programId.take(12)} action=exclude_from_wallet_inventory",
                            )
                        } catch (_: Throwable) {}
                    }
                }
                val tokenAmount = info.optJSONObject("tokenAmount")
                val rawAmount = tokenAmount?.optString("amount", "") ?: ""
                val decimals = tokenAmount?.optInt("decimals", -1) ?: -1
                val amount = try { CanonicalTokenAmount.fromRpcAmount(rawAmount, decimals) } catch (_: Throwable) { null }
                if (mint.isNotBlank() && mint !in frozenMints7253 && amount != null && amount.raw.signum() > 0) out[mint] = amount
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
            // V5.0.8074 — a failed token read makes the wallet blind: no new live buys until one succeeds.
            try { com.lifecyclebot.engine.truth.LiveBootRecovery8074.noteTokenRead8074(false) } catch (_: Throwable) {}
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("WALLET_TOKEN_READ_INDETERMINATE", "successPrograms=0/2 required=Tokenkeg failures=${failures.joinToString(";").take(220)}") } catch (_: Throwable) {}
            throw RuntimeException("wallet token snapshot indeterminate: required SPL Token program failed; ${failures.joinToString(";")}")
        }
        if (!token2022Ok) {
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("WALLET_TOKEN_2022_OPTIONAL_FAILED", "action=continue_with_spl successPrograms=1/2 failures=${failures.joinToString(";").take(180)}") } catch (_: Throwable) {}
        }
        // Remove a mint even if another account/program supplied a positive
        // amount. Operator policy is mint-conservative: any observed frozen
        // account makes that mint non-executable for this wallet snapshot.
        frozenMints7253.forEach(out::remove)
        com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253
            .applyParsedSnapshot(parsedMints7253, frozenMints7253)
        com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253
            .snapshot().forEach(out::remove)
        // V5.0.7140 — the snapshot now carries whether it is whole.
        //
        // Continuing on a Token-2022 failure is correct; calling the result
        // COMPLETE is not. The operator's wallet held ten tokens while this
        // read reported one, because Token-2022 did not answer (36 times) and
        // the SPL-only result was published as if it were the wallet. Every
        // consumer that asks "is this mint still held?" then read nine real
        // holdings as gone — ABSENT_MINT_ZERO_CONFIRM 187, and a mint confirmed
        // absent twice is closed as an external rug at -100%.
        //
        // Positive balances from a partial read stay trustworthy: a mint that
        // IS in the map is held, and that needs no completeness. Only the
        // absence inference does, and WalletSnapshotCompleteness7140 is what
        // that inference now has to consult.
        // V5.0.8074 — the SPL token program read: the wallet can be seen.
        try { com.lifecyclebot.engine.truth.LiveBootRecovery8074.noteTokenRead8074(true) } catch (_: Throwable) {}
        try {
            if (token2022Ok) {
                com.lifecyclebot.engine.truth.WalletSnapshotCompleteness7140.markComplete()
            } else {
                com.lifecyclebot.engine.truth.WalletSnapshotCompleteness7140.markPartial(
                    "TOKEN_2022_PROGRAM_UNREAD:${failures.joinToString(";").take(120)}",
                )
            }
        } catch (_: Throwable) {}
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
    ): Map<String, CanonicalTokenAmount> {
        val fut: java.util.concurrent.Future<Map<String, CanonicalTokenAmount>> = try {
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
            knownMintsFallbackBounded7374()?.let { return it }
            throw RuntimeException("wallet token snapshot timeout after ${timeoutMs}ms", e)
        } catch (e: Throwable) {
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("WALLET_TOKEN_READ_INDETERMINATE", "reason=${e.message?.take(160)}") } catch (_: Throwable) {}
            knownMintsFallbackBounded7374()?.let { return it }
            throw RuntimeException("wallet token snapshot failed: ${e.message}", e)
        }
    }

    /**
     * V5.0.7868 — the ONE mint a protective sell needs, bounded. 5.0.7866: a
     * normal stop took 7.6 s trigger->broadcast; the sell path waited on the
     * whole-wallet two-program scan (5 s ceiling, then the known-mints
     * fallback up to 6 s more). A fresh cache hit is instant; otherwise one
     * mint-filtered getTokenAccountsByOwner (cheap, served by public nodes).
     * Returns null when not answered or not positive — the caller keeps the
     * existing whole-wallet path and its indeterminate semantics.
     */
    fun getSingleMintBalanceBounded7868(mint: String, timeoutMs: Long = 2_500L): CanonicalTokenAmount? {
        if (mint.isBlank()) return null
        try {
            com.lifecyclebot.engine.WalletAccountCache.snapshot(ttlMs = 5_000L)?.get(mint)
                ?.takeIf { it.raw > java.math.BigInteger.ONE }?.let { return it }
        } catch (_: Throwable) {}
        val fut = try {
            boundedRpcExecutor().submit(java.util.concurrent.Callable {
                readKnownMints7374(listOf(mint), System.currentTimeMillis() + timeoutMs - 100L)
            })
        } catch (_: Throwable) { return null }
        return try {
            fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS).first[mint]
                ?.takeIf { it.raw > java.math.BigInteger.ONE }
        } catch (_: Throwable) {
            try { fut.cancel(true) } catch (_: Throwable) {}
            null
        }
    }

    /**
     * V5.0.7374 — when the whole-wallet scan fails, read the mints the bot already
     * knows about, one at a time. getTokenAccountsByOwner over a whole token
     * program is one of the heaviest reads there is; public nodes refuse it (403)
     * and Helius was at 429, so on 5.0.7371 every read failed on all 14 endpoints
     * and the bot saw an empty wallet. The same method filtered by one mint is
     * cheap, is served by those nodes, and covers both token programs.
     *
     * The result is a lower bound: it is marked partial, so it proves the listed
     * mints are held and can never be read as proof that anything else is gone.
     * Returns null (caller keeps its indeterminate path) when no mint was answered.
     */
    private fun knownMintsFallbackBounded7374(budgetMs: Long = 6_000L): Map<String, CanonicalTokenAmount>? {
        val mints = knownBotMints7374()
        if (mints.isEmpty()) return null
        val fut = try {
            boundedRpcExecutor().submit(java.util.concurrent.Callable { readKnownMints7374(mints, System.currentTimeMillis() + budgetMs - 250L) })
        } catch (_: Throwable) { return null }
        val result = try {
            fut.get(budgetMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            try { fut.cancel(true) } catch (_: Throwable) {}
            null
        } ?: return null
        val (held, answered) = result
        if (answered == 0) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("WALLET_KNOWN_MINTS_FALLBACK_UNANSWERED_7374") } catch (_: Throwable) {}
            return null
        }
        try {
            com.lifecyclebot.engine.truth.WalletSnapshotCompleteness7140.markPartial("KNOWN_MINTS_FALLBACK_7374 answered=$answered/${mints.size}")
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("WALLET_KNOWN_MINTS_FALLBACK_OK_7374")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "WALLET_KNOWN_MINTS_FALLBACK_OK_7374",
                "known=${mints.size} answered=$answered held=${held.size} action=partial_snapshot_positive_only",
            )
        } catch (_: Throwable) {}
        return held
    }

    private fun readKnownMints7374(mints: List<String>, deadlineMs: Long): Pair<Map<String, CanonicalTokenAmount>, Int> {
        if (mints.isEmpty()) return emptyMap<String, CanonicalTokenAmount>() to 0
        val workerCount = minOf(6, mints.size)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(workerCount) { r ->
            Thread(r, "SolanaWallet-known-mint").apply { isDaemon = true }
        }
        val completed = java.util.concurrent.ExecutorCompletionService<Triple<String, CanonicalTokenAmount?, Boolean>>(workers)
        val tasks = mints.map { mint ->
            completed.submit(java.util.concurrent.Callable {
                if (System.currentTimeMillis() >= deadlineMs) return@Callable Triple(mint, null, false)
                try {
                    val json = rpcTokenAccountsByOwnerFiltered(
                        JSONObject().put("mint", mint),
                        "mint:${mint.take(8)}",
                        deadlineMs,
                    )
                    val arr = json.optJSONObject("result")?.optJSONArray("value")
                        ?: return@Callable Triple(mint, null, false)
                    var total = java.math.BigInteger.ZERO
                    var decimals = -1
                    for (i in 0 until arr.length()) {
                        val info = arr.optJSONObject(i)?.optJSONObject("account")?.optJSONObject("data")
                            ?.optJSONObject("parsed")?.optJSONObject("info") ?: continue
                        if (info.optString("state", "").equals("frozen", ignoreCase = true)) {
                            total = java.math.BigInteger.ZERO
                            decimals = -1
                            break
                        }
                        val ta = info.optJSONObject("tokenAmount") ?: continue
                        val amt = try {
                            CanonicalTokenAmount.fromRpcAmount(ta.optString("amount", ""), ta.optInt("decimals", -1))
                        } catch (_: Throwable) { null } ?: continue
                        total = total.add(amt.raw)
                        decimals = amt.decimals
                    }
                    val amount = if (total.signum() > 0 && decimals >= 0) {
                        try { CanonicalTokenAmount.fromRpcAmount(total.toString(), decimals) } catch (_: Throwable) { null }
                    } else null
                    Triple(mint, amount, true)
                } catch (_: Throwable) {
                    Triple(mint, null, false)
                }
            })
        }
        val out = mutableMapOf<String, CanonicalTokenAmount>()
        var answered = 0
        var received = 0
        try {
            while (received < tasks.size) {
                val remainingMs = deadlineMs - System.currentTimeMillis()
                if (remainingMs <= 0L) break
                val future = completed.poll(remainingMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                received++
                val (mint, amount, didAnswer) = try { future.get() } catch (_: Throwable) { continue }
                if (didAnswer) answered++
                if (amount != null) out[mint] = amount
            }
        } catch (_: Throwable) {
            // A timeout is still a partial positive-only snapshot; callers must
            // preserve unqueried mints and may not infer wallet-zero from absence.
        } finally {
            tasks.filterNot { it.isDone }.forEach { try { it.cancel(true) } catch (_: Throwable) {} }
            workers.shutdownNow()
        }
        return out to answered
    }

    /**
     * Bot-owned mints that still need wallet proof. Keep active exit inventory
     * first, then pending buys, host-held rows, durable live fill lots, and every
     * quarantined live row. A partial wallet scan is only useful if it asks about
     * the very buys that fell out of OPEN; the old first-20 cap silently omitted
     * quarantined rows and stranded real holdings outside exit scope.
     */
    private fun knownBotMints7374(): List<String> {
        val s = LinkedHashSet<String>()
        try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
                .filter { it.mode.equals("live", true) }.forEach { s += it.mint }
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.pendingEntryPositions6461()
                .filter { it.mode.equals("live", true) }.forEach { s += it.mint }
        } catch (_: Throwable) {}
        try {
            com.lifecyclebot.engine.HostWalletTokenTracker.snapshot()
                .filter { !it.status.name.startsWith("CLOSED") && it.status.name != "SOLD_CONFIRMED" }
                .forEach { s += it.mint }
        } catch (_: Throwable) {}
        try {
            val owner = publicKeyB58
            com.lifecyclebot.engine.FillLotLedger6344.snapshotForWallet(owner)
                .filter { it.remainingQty.isFinite() && it.remainingQty > 1e-9 }
                .forEach { s += it.mintAddress }
        } catch (_: Throwable) {}
        try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.quarantinedLivePositions7454()
                .forEach { s += it.mint }
        } catch (_: Throwable) {}
        return s.filter { it.isNotBlank() }
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

    /** V5.0.7927 — one empty token account the wallet can close for its rent. */
    data class EmptyTokenAccount7927(val address: String, val mint: String, val programId: String, val lamports: Long)

    /**
     * V5.0.7927 — the wallet's token accounts holding zero tokens. Every buy opens an
     * associated token account (~0.00204 SOL rent, more for Token-2022); a full sell
     * leaves it empty and its rent locked. Read-only.
     */
    fun emptyTokenAccounts7927(): List<EmptyTokenAccount7927> {
        val out = ArrayList<EmptyTokenAccount7927>()
        for (program in listOf(TOKEN_PROGRAM_7927, TOKEN_2022_PROGRAM_7927)) {
            val resp = rpc("getTokenAccountsByOwner", JSONArray()
                .put(publicKeyB58)
                .put(JSONObject().put("programId", program))
                .put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed")))
            val arr = resp.optJSONObject("result")?.optJSONArray("value") ?: continue
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val acct = row.optJSONObject("account") ?: continue
                val info = acct.optJSONObject("data")?.optJSONObject("parsed")?.optJSONObject("info") ?: continue
                val amount = info.optJSONObject("tokenAmount")?.optString("amount").orEmpty()
                if (amount != "0") continue
                if (info.optString("state") != "initialized") continue
                if (info.optString("owner") != publicKeyB58) continue
                val closeAuth = info.optString("closeAuthority", "")
                if (closeAuth.isNotBlank() && closeAuth != publicKeyB58) continue
                val mint = info.optString("mint")
                if (mint.isBlank() || mint == WSOL_MINT_7927) continue
                out.add(EmptyTokenAccount7927(row.optString("pubkey"), mint, program, acct.optLong("lamports", 0L)))
            }
        }
        return out
    }

    /**
     * V5.0.7927 — close [accounts] (1..18, all holding zero tokens) in ONE legacy
     * transaction, rent returned to this wallet. The token program refuses to close
     * an account that holds any balance, so this can never destroy tokens: a race
     * with a buy fails the whole transaction instead. Returns the signature.
     */
    fun closeTokenAccounts7927(accounts: List<EmptyTokenAccount7927>): String {
        require(accounts.isNotEmpty() && accounts.size <= 18) { "1..18 accounts per transaction" }
        val bhResp = rpc("getLatestBlockhash", JSONArray().put(JSONObject().put("commitment", "confirmed")))
        val blockhash = bhResp.optJSONObject("result")?.optJSONObject("value")?.optString("blockhash")
            ?.takeIf { it.isNotBlank() } ?: throw Exception("Failed to fetch blockhash")
        val programs = accounts.map { it.programId }.distinct()
        // Keys: [owner (signer, writable)] + token accounts (writable) + programs (read-only).
        val keys = listOf(publicKeyB58) + accounts.map { it.address } + programs
        fun compactU16(n: Int): ByteArray = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x4000 -> byteArrayOf((n and 0x7F or 0x80).toByte(), (n shr 7).toByte())
            else -> byteArrayOf((n and 0x7F or 0x80).toByte(), ((n shr 7) and 0x7F or 0x80).toByte(), (n shr 14).toByte())
        }
        val msg = java.io.ByteArrayOutputStream().apply {
            write(byteArrayOf(0x01, 0x00, programs.size.toByte()))   // 1 signer, 0 ro-signed, N ro-unsigned (programs)
            write(compactU16(keys.size))
            for (k in keys) write(Base58.base58Decode(k))
            write(Base58.base58Decode(blockhash))
            write(compactU16(accounts.size))
            accounts.forEachIndexed { i, a ->
                write(byteArrayOf((1 + accounts.size + programs.indexOf(a.programId)).toByte()))  // program id index
                write(compactU16(3))
                write(byteArrayOf((1 + i).toByte(), 0x00, 0x00))   // account, destination = owner, authority = owner
                write(compactU16(1))
                write(byteArrayOf(0x09))                           // TokenInstruction::CloseAccount
            }
        }.toByteArray()
        val sig = TweetNaclFast.Signature(null, keyPair.secretKey).detached(msg)
        val txB64 = android.util.Base64.encodeToString(byteArrayOf(0x01) + sig + msg, android.util.Base64.NO_WRAP)
        val txResult = rpc("sendTransaction", JSONArray().put(txB64)
            .put(JSONObject().put("encoding", "base64").put("preflightCommitment", "confirmed")))
        val txSig = txResult.optString("result", "")
        if (txSig.isBlank()) throw Exception("sendTransaction failed: ${txResult.optJSONObject("error")?.optString("message") ?: "unknown error"}")
        awaitConfirmation(txSig)
        return txSig
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

/** V5.0.7980 — trading-grade finality: "confirmed" or "finalized" (pure; see awaitConfirmation). */
internal fun confirmedEnough7980(status: String): Boolean = status == "confirmed" || status == "finalized"

/** V5.0.8094 — pure: is this JSON-RPC error a plan/credit exhaustion (fail over), not an answer? */
internal fun rpcExhaustedReply8094(httpCode: Int, errMsg: String): Boolean {
    if (httpCode == 402) return true
    val low = errMsg.lowercase()
    return low.contains("credit") || low.contains("max usage") || low.contains("quota") ||
        low.contains("upgrade your plan") || low.contains("exceeded your") || low.contains("insufficient balance")
}
