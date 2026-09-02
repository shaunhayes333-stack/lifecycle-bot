package com.lifecyclebot.perps.crypto

import android.content.Context
import android.util.Base64
import com.lifecyclebot.engine.MultiChainWalletVault6546
import com.lifecyclebot.network.SharedHttpClient
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** V5.0.6646 — real, fail-closed deBridge DLN Solana→EVM adapter. */
object CryptoBridgeAdapter {
    // Real-money invariant: route discovery must remain disabled until the
    // destination signer, ERC-20 approval, sell-back submission, receipt proof,
    // and canonical close coordinator are all wired end-to-end. A funded
    // multichain address alone is not an executable adapter.
    private const val FULL_ROUND_TRIP_IMPLEMENTED = false
    private const val CREATE = "https://dln.debridge.finance/v1.0/dln/order/create-tx"
    private const val TRACK = "https://dln-api.debridge.finance/api/Orders"
    private const val SOLANA_CHAIN = 7_565_164L
    private const val NATIVE_SOL = "11111111111111111111111111111111"
    private const val MIN_DEST_GAS_WEI = "1000000000000000"
    private const val RECOVERY_PREFS = "aate_dln_roundtrip_6649"
    private val terminalOk = setOf("Fulfilled", "SentUnlock", "ClaimedUnlock")
    private val terminalBad = setOf("Cancelled", "OrderCancelled", "FulfillReverted", "UnlockClaimFailed")
    @Volatile private var appContext: Context? = null

    data class Chain(val id: Long, val rpc: String)
    data class LiveRouteReadiness6647(
        val chainSigning: Boolean = false,
        val transactionConstruction: Boolean = false,
        val nonceOrUtxo: Boolean = false,
        val feeEstimation: Boolean = false,
        val submission: Boolean = false,
        val finalityProof: Boolean = false,
        val retryAndIdempotency: Boolean = false,
        val reconciliation: Boolean = false,
        val crashRecovery: Boolean = false,
        val integrationTests: Boolean = false,
    ) {
        val executable: Boolean get() = chainSigning && transactionConstruction && nonceOrUtxo &&
            feeEstimation && submission && finalityProof && retryAndIdempotency && reconciliation &&
            crashRecovery && integrationTests
        fun missing(): List<String> = buildList {
            if (!chainSigning) add("CHAIN_SIGNING")
            if (!transactionConstruction) add("TX_CONSTRUCTION")
            if (!nonceOrUtxo) add("NONCE_OR_UTXO")
            if (!feeEstimation) add("FEE_ESTIMATION")
            if (!submission) add("SUBMISSION")
            if (!finalityProof) add("FINALITY_PROOF")
            if (!retryAndIdempotency) add("RETRY_IDEMPOTENCY")
            if (!reconciliation) add("RECONCILIATION")
            if (!crashRecovery) add("CRASH_RECOVERY")
            if (!integrationTests) add("CHAIN_INTEGRATION_TESTS")
        }
    }
    private val chains = mapOf(
        "ethereum" to Chain(1, "https://ethereum-rpc.publicnode.com"),
        "eth" to Chain(1, "https://ethereum-rpc.publicnode.com"),
        "arbitrum" to Chain(42161, "https://arbitrum-one-rpc.publicnode.com"),
        "base" to Chain(8453, "https://base-rpc.publicnode.com"),
        "polygon" to Chain(137, "https://polygon-bor-rpc.publicnode.com"),
        "bsc" to Chain(56, "https://bsc-rpc.publicnode.com"),
        "binance-smart-chain" to Chain(56, "https://bsc-rpc.publicnode.com"),
        "avalanche" to Chain(43114, "https://avalanche-c-chain-rpc.publicnode.com"),
        "optimism" to Chain(10, "https://optimism-rpc.publicnode.com"),
        "linea" to Chain(59144, "https://linea-rpc.publicnode.com"),
    )
    // Wallet/address derivation is foundation only.  No EVM chain is promoted
    // by address presence; each must explicitly graduate every capability.
    private val implementedReadiness6649 = LiveRouteReadiness6647().copy(
        chainSigning = true,
        transactionConstruction = true,
        nonceOrUtxo = true,
        feeEstimation = true,
        submission = true,
        finalityProof = true,
        retryAndIdempotency = true,
        reconciliation = true,
        crashRecovery = true,
        // A deterministic RPC integration test is necessary but not sufficient
        // to prove a funded public-chain route.  Keep live false until the real
        // chain matrix is exercised and attested.
        integrationTests = false,
    )
    private val liveReadiness6647 = chains.keys.associateWith { implementedReadiness6649 }
    private val http = SharedHttpClient.builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    fun init(context: Context) { appContext = context.applicationContext }
    fun readiness6647(targetChain: String?): LiveRouteReadiness6647? =
        liveReadiness6647[targetChain?.trim()?.lowercase()]
    fun isConfigured(): Boolean = FULL_ROUND_TRIP_IMPLEMENTED && liveReadiness6647.values.any { it.executable } && appContext?.let {
        try { MultiChainWalletVault6546.executable(it) != null } catch (_: Throwable) { false }
    } ?: false
    fun supportsRoundTrip(targetChain: String?, targetToken: String?): Boolean =
        isConfigured() && chains.containsKey(targetChain?.trim()?.lowercase()) &&
            readiness6647(targetChain)?.executable == true && isEvmAddress(targetToken)
    fun ownsPosition6649(positionId: String): Boolean =
        appContext?.let { loadPosition6649(it, positionId) != null } ?: false

    sealed class Execution {
        data class Fulfilled(
            val sourceSignature: String, val orderId: String, val destinationChainId: Long,
            val destinationToken: String, val receivedRaw: BigInteger, val decimals: Int,
            val destinationTx: String,
        ) : Execution()
        data class Rejected(val code: String, val reason: String) : Execution()
    }

    sealed class Exit {
        data class Settled(
            val evmTransactionHash: String,
            val reverseOrderId: String,
            val receivedSol: Double,
            val soldRaw: BigInteger,
        ) : Exit()
        data class Pending(val code: String, val reason: String) : Exit()
        data class Rejected(val code: String, val reason: String) : Exit()
    }

    private data class BridgePosition6649(
        val positionId: String,
        val chainKey: String,
        val token: String,
        val amountRaw: BigInteger,
        val decimals: Int,
        val forwardOrderId: String,
        val sourceSignature: String,
        val state: String,
        val reverseOrderId: String = "",
        val reverseTo: String = "",
        val reverseData: String = "",
        val reverseValue: BigInteger = BigInteger.ZERO,
        val reverseTransactionHash: String = "",
        val receivedSol: Double = 0.0,
        val forwardSignedBase64: String = "",
        val destinationBalanceBeforeRaw: BigInteger = BigInteger.ZERO,
        val sourceBalanceBeforeSol: Double = -1.0,
    )

    suspend fun buySolToEvm(
        wallet: SolanaWallet, targetSymbol: String, targetChain: String?,
        targetToken: String?, sizeSol: Double, positionId: String = "",
    ): Execution = withContext(Dispatchers.IO) {
        if (!FULL_ROUND_TRIP_IMPLEMENTED)
            return@withContext Execution.Rejected("ROUND_TRIP_EXECUTOR_INCOMPLETE", "paper-only/unavailable; missing=${readiness6647(targetChain)?.missing()?.joinToString(",") ?: "UNSUPPORTED_CHAIN"}")
        val ctx = appContext ?: return@withContext Execution.Rejected("BRIDGE_NOT_INITIALISED", "adapter context missing")
        val stored = MultiChainWalletVault6546.executable(ctx)
            ?: return@withContext Execution.Rejected("MULTICHAIN_WALLET_NOT_ACTIVE", "backup-confirmed main wallet required")
        if (wallet.publicKeyB58 != stored.solanaAddress)
            return@withContext Execution.Rejected("SOURCE_SIGNER_MISMATCH", "connected signer differs from multichain main wallet")
        val chain = chains[targetChain?.trim()?.lowercase()]
            ?: return@withContext Execution.Rejected("CHAIN_UNSUPPORTED", "unsupported deBridge chain: $targetChain")
        val token = targetToken?.trim().orEmpty()
        if (!isEvmAddress(token)) return@withContext Execution.Rejected("TOKEN_ADDRESS_INVALID", "exact EVM contract required")
        if (!sizeSol.isFinite() || sizeSol < 0.01) return@withContext Execution.Rejected("SIZE_INVALID", "minimum 0.01 SOL")

        val gas = hexBig(rpcResult(chain.rpc, "eth_getBalance", stored.ethereumAddress, "latest"))
        if (gas < BigInteger(MIN_DEST_GAS_WEI))
            return@withContext Execution.Rejected("DESTINATION_GAS_MISSING", "fund 0.001 native gas at ${stored.ethereumAddress}")
        val recovery = positionId.takeIf { it.isNotBlank() }?.let { loadPosition6649(ctx, it) }
        val prepared = if (recovery?.state == "FORWARD_PREPARED" && recovery.forwardSignedBase64.isNotBlank()) recovery else null
        val before: BigInteger
        val expectedRaw: BigInteger
        val decimals: Int
        val orderId: String
        val signed: SolanaWallet.SignedSerializedTransaction6649
        if (prepared != null) {
            before = prepared.destinationBalanceBeforeRaw
            expectedRaw = prepared.amountRaw
            decimals = prepared.decimals
            orderId = prepared.forwardOrderId
            signed = SolanaWallet.SignedSerializedTransaction6649(prepared.sourceSignature, prepared.forwardSignedBase64)
        } else {
            before = erc20Balance(chain.rpc, token, stored.ethereumAddress)
            val lamports = BigInteger.valueOf((sizeSol * 1_000_000_000.0).toLong())
            val order = createOrder(SOLANA_CHAIN, NATIVE_SOL, lamports, chain.id, token,
                stored.ethereumAddress, stored.solanaAddress, stored.solanaAddress, stored.ethereumAddress)
            val impact = order.optDouble("usdPriceImpact", 0.0)
            if (!impact.isFinite() || kotlin.math.abs(impact) > 3.0)
                return@withContext Execution.Rejected("PRICE_IMPACT", "deBridge impact $impact% exceeds 3%")
            val estimated = order.optJSONObject("estimation")?.optJSONObject("dstChainTokenOut")
                ?: return@withContext Execution.Rejected("ESTIMATE_MISSING", "destination amount absent")
            expectedRaw = estimated.optString("amount").toBigIntegerOrNull()
                ?: return@withContext Execution.Rejected("ESTIMATE_INVALID", "destination amount invalid")
            decimals = estimated.optInt("decimals", -1)
            if (expectedRaw <= BigInteger.ZERO || decimals < 0)
                return@withContext Execution.Rejected("ESTIMATE_INVALID", "destination quantity invalid")
            try {
                createOrder(chain.id, token, expectedRaw, SOLANA_CHAIN, NATIVE_SOL,
                    stored.solanaAddress, stored.ethereumAddress, stored.ethereumAddress, stored.solanaAddress)
            } catch (t: Throwable) {
                return@withContext Execution.Rejected("SELL_BACK_ROUTE_MISSING", t.message ?: "reverse route unavailable")
            }
            val txHex = order.optJSONObject("tx")?.optString("data").orEmpty()
            if (!txHex.startsWith("0x")) return@withContext Execution.Rejected("SOURCE_TX_MISSING", "serialized Solana transaction absent")
            orderId = order.optString("orderId")
            if (orderId.isBlank()) return@withContext Execution.Rejected("ORDER_ID_MISSING", "source transaction has no DLN order id")
            signed = wallet.signSerializedTransaction6649(Base64.encodeToString(hexBytes(txHex), Base64.NO_WRAP))
            if (positionId.isNotBlank()) savePosition6649(ctx, BridgePosition6649(
                positionId, targetChain?.trim()?.lowercase().orEmpty(), token, expectedRaw, decimals,
                orderId, signed.signature, "FORWARD_PREPARED", forwardSignedBase64 = signed.signedBase64,
                destinationBalanceBeforeRaw = before,
            ))
        }
        val sourceSig = try { wallet.sendSignedAndConfirm6649(signed) } catch (t: Throwable) {
            return@withContext Execution.Rejected("SOURCE_SUBMIT_FAILED", t.message ?: "Solana submit failed")
        }
        val terminal = awaitTerminal(orderId)
        if (terminal.first !in terminalOk)
            return@withContext Execution.Rejected("BRIDGE_NOT_FULFILLED", "state=${terminal.first}")
        val delta = erc20Balance(chain.rpc, token, stored.ethereumAddress) - before
        if (delta <= BigInteger.ZERO)
            return@withContext Execution.Rejected("DESTINATION_BALANCE_UNPROVEN", "terminal order without token balance increase")
        if (positionId.isNotBlank()) {
            savePosition6649(ctx, BridgePosition6649(
                positionId, targetChain?.trim()?.lowercase().orEmpty(), token, delta, decimals,
                orderId, sourceSig, "OPEN", forwardSignedBase64 = signed.signedBase64,
                destinationBalanceBeforeRaw = before,
            ))
        }
        Execution.Fulfilled(sourceSig, orderId, chain.id, token, delta, decimals, terminal.second)
    }

    /** Reverse the exact persisted bridge lot back to the source Solana wallet.
     * Every signed EVM payload is stored before submission; retries and process
     * restarts therefore reuse the same nonce and bytes. */
    suspend fun sellEvmToSol(wallet: SolanaWallet, positionId: String): Exit =
        withContext(Dispatchers.IO) {
            if (!FULL_ROUND_TRIP_IMPLEMENTED)
                return@withContext Exit.Rejected("ROUND_TRIP_EXECUTOR_INCOMPLETE", "paper-only/unavailable")
            val ctx = appContext ?: return@withContext Exit.Rejected("BRIDGE_NOT_INITIALISED", "adapter context missing")
            val storedWallet = MultiChainWalletVault6546.executable(ctx)
                ?: return@withContext Exit.Rejected("MULTICHAIN_WALLET_NOT_ACTIVE", "backup-confirmed main wallet required")
            if (wallet.publicKeyB58 != storedWallet.solanaAddress)
                return@withContext Exit.Rejected("SOURCE_SIGNER_MISMATCH", "connected signer differs from multichain main wallet")
            var position = loadPosition6649(ctx, positionId)
                ?: return@withContext Exit.Rejected("BRIDGE_POSITION_RECOVERY_MISSING", "no immutable bridge lot for $positionId")
            if (position.state == "CLOSED") return@withContext Exit.Settled(
                position.reverseTransactionHash, position.reverseOrderId, position.receivedSol, position.amountRaw,
            )
            val chain = chains[position.chainKey]
                ?: return@withContext Exit.Rejected("CHAIN_UNSUPPORTED", "stored chain ${position.chainKey} unsupported")
            val credentials = MultiChainWalletVault6546.evmCredentials6649(ctx)
                ?: return@withContext Exit.Rejected("EVM_SIGNER_MISSING", "encrypted EVM signer unavailable")
            if (!credentials.address.equals(storedWallet.ethereumAddress, true))
                return@withContext Exit.Rejected("EVM_SIGNER_MISMATCH", "derived signer/address mismatch")
            val rpc = EvmRpc6649(chain.rpc)
            val txStore = PersistentEvmStore6649(ctx)
            try {
                val held = erc20Balance(chain.rpc, position.token, storedWallet.ethereumAddress)
                if (held < position.amountRaw)
                    return@withContext Exit.Rejected("DESTINATION_BALANCE_UNPROVEN", "held=$held required=${position.amountRaw}")

                if (position.reverseData.isBlank()) {
                    val quote = createOrder(
                        chain.id, position.token, position.amountRaw, SOLANA_CHAIN, NATIVE_SOL,
                        storedWallet.solanaAddress, storedWallet.ethereumAddress,
                        storedWallet.ethereumAddress, storedWallet.solanaAddress,
                    )
                    val spender = quote.optJSONObject("tx")?.optString("allowanceTarget").orEmpty()
                    if (!isEvmAddress(spender))
                        return@withContext Exit.Rejected("ALLOWANCE_TARGET_MISSING", "deBridge did not return an ERC-20 spender")
                    if (erc20Allowance(chain.rpc, position.token, storedWallet.ethereumAddress, spender) < position.amountRaw) {
                        val approval = EvmBridgeTransactionEngine6649.Request(
                            "$positionId:ERC20_APPROVAL", chain.id, storedWallet.ethereumAddress, position.token,
                            EvmBridgeTransactionEngine6649.approvalData(spender, position.amountRaw),
                        )
                        when (val approved = awaitEvm6649(approval, credentials, rpc, txStore)) {
                            is EvmBridgeTransactionEngine6649.Outcome.Confirmed -> Unit
                            is EvmBridgeTransactionEngine6649.Outcome.Pending ->
                                return@withContext Exit.Pending("ERC20_APPROVAL_PENDING", approved.reason)
                            is EvmBridgeTransactionEngine6649.Outcome.Failed ->
                                return@withContext Exit.Rejected("ERC20_APPROVAL_FAILED", approved.reason)
                        }
                    }
                    val order = createOrder(
                        chain.id, position.token, position.amountRaw, SOLANA_CHAIN, NATIVE_SOL,
                        storedWallet.solanaAddress, storedWallet.ethereumAddress,
                        storedWallet.ethereumAddress, storedWallet.solanaAddress, enableEstimate = true,
                    )
                    val tx = order.optJSONObject("tx")
                        ?: return@withContext Exit.Rejected("REVERSE_TX_MISSING", "deBridge reverse transaction absent")
                    val orderId = order.optString("orderId")
                    val to = tx.optString("to")
                    val data = tx.optString("data")
                    val value = quantity6649(tx.optString("value", "0"))
                    if (orderId.isBlank() || !isEvmAddress(to) || !data.startsWith("0x"))
                        return@withContext Exit.Rejected("REVERSE_TX_INVALID", "order/to/data incomplete")
                    val sourceBalanceBeforeSol = wallet.getSolBalance()
                    position = position.copy(
                        state = "REVERSE_PREPARED", reverseOrderId = orderId, reverseTo = to,
                        reverseData = data, reverseValue = value,
                        sourceBalanceBeforeSol = sourceBalanceBeforeSol,
                    )
                    savePosition6649(ctx, position)
                }

                val beforeSol = position.sourceBalanceBeforeSol.takeIf { it >= 0.0 }
                    ?: return@withContext Exit.Rejected(
                        "SOURCE_BALANCE_CHECKPOINT_MISSING",
                        "reverse order predates the crash-safe source balance checkpoint",
                    )
                val reverse = EvmBridgeTransactionEngine6649.Request(
                    "$positionId:DLN_REVERSE", chain.id, storedWallet.ethereumAddress,
                    position.reverseTo, position.reverseData, position.reverseValue,
                )
                val confirmed = when (val result = awaitEvm6649(reverse, credentials, rpc, txStore)) {
                    is EvmBridgeTransactionEngine6649.Outcome.Confirmed -> result
                    is EvmBridgeTransactionEngine6649.Outcome.Pending ->
                        return@withContext Exit.Pending("REVERSE_EVM_PENDING", result.reason)
                    is EvmBridgeTransactionEngine6649.Outcome.Failed ->
                        return@withContext Exit.Rejected("REVERSE_EVM_FAILED", result.reason)
                }
                position = position.copy(state = "REVERSE_SUBMITTED", reverseTransactionHash = confirmed.record.transactionHash)
                savePosition6649(ctx, position)
                val terminal = withTimeout(620_000L) { awaitTerminal(position.reverseOrderId) }
                if (terminal.first !in terminalOk)
                    return@withContext Exit.Pending("REVERSE_BRIDGE_PENDING", "state=${terminal.first}")
                var received = 0.0
                for (attempt in 0 until 20) {
                    received = (wallet.getSolBalance() - beforeSol).coerceAtLeast(0.0)
                    if (received > 0.0) break
                    delay(3_000L)
                }
                if (received <= 0.0)
                    return@withContext Exit.Pending("SOURCE_BALANCE_UNPROVEN", "fulfilled order without positive SOL delta")
                position = position.copy(state = "CLOSED", receivedSol = received)
                savePosition6649(ctx, position)
                Exit.Settled(confirmed.record.transactionHash, position.reverseOrderId, received, position.amountRaw)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Exit.Pending("BRIDGE_RETRY", t.message ?: t.javaClass.simpleName)
            }
        }

    private suspend fun awaitEvm6649(
        request: EvmBridgeTransactionEngine6649.Request,
        credentials: org.web3j.crypto.Credentials,
        rpc: EvmBridgeTransactionEngine6649.Rpc,
        store: EvmBridgeTransactionEngine6649.Store,
    ): EvmBridgeTransactionEngine6649.Outcome {
        var result = EvmBridgeTransactionEngine6649.execute(request, credentials, rpc, store)
        repeat(20) {
            if (result !is EvmBridgeTransactionEngine6649.Outcome.Pending) return result
            delay(3_000L)
            result = EvmBridgeTransactionEngine6649.execute(request, credentials, rpc, store)
        }
        return result
    }

    private suspend fun awaitTerminal(orderId: String): Pair<String, String> {
        repeat(120) {
            val row = getJson("$TRACK/${enc(orderId)}")
            val status = row.optString("status")
            val tx = row.optJSONObject("fulfilledDstEventMetadata")?.optString("transactionHash").orEmpty()
            if (status in terminalOk || status in terminalBad) return status to tx
            delay(5_000)
        }
        return "TIMEOUT" to ""
    }

    private fun createOrder(
        srcChain: Long, srcToken: String, srcAmount: BigInteger, dstChain: Long,
        dstToken: String, recipient: String, sender: String,
        srcAuthority: String, dstAuthority: String, enableEstimate: Boolean = false,
    ): JSONObject {
        val q = linkedMapOf(
            "srcChainId" to "$srcChain", "srcChainTokenIn" to srcToken,
            "srcChainTokenInAmount" to "$srcAmount", "dstChainId" to "$dstChain",
            "dstChainTokenOut" to dstToken, "dstChainTokenOutRecipient" to recipient,
            "dstChainTokenOutAmount" to "auto", "senderAddress" to sender,
            "srcChainOrderAuthorityAddress" to srcAuthority, "srcChainRefundAddress" to sender,
            "dstChainOrderAuthorityAddress" to dstAuthority, "prependOperatingExpenses" to "true",
            "enableEstimate" to enableEstimate.toString(),
        )
        return getJson(CREATE + "?" + q.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) })
    }

    private fun erc20Balance(rpc: String, token: String, owner: String): BigInteger {
        val word = owner.removePrefix("0x").lowercase().padStart(64, '0')
        return hexBig(rpcResult(rpc, "eth_call", JSONObject().put("to", token).put("data", "0x70a08231$word"), "latest"))
    }

    private fun erc20Allowance(rpc: String, token: String, owner: String, spender: String): BigInteger =
        hexBig(rpcResult(rpc, "eth_call", JSONObject().put("to", token).put(
            "data", EvmBridgeTransactionEngine6649.allowanceData(owner, spender),
        ), "latest"))

    private class EvmRpc6649(private val rpcUrl: String) : EvmBridgeTransactionEngine6649.Rpc {
        override fun pendingNonce(address: String): BigInteger =
            hexBig(rpcResult(rpcUrl, "eth_getTransactionCount", address, "pending"))
        override fun gasPrice(): BigInteger = hexBig(rpcResult(rpcUrl, "eth_gasPrice"))
        override fun estimateGas(from: String, to: String, value: BigInteger, data: String): BigInteger =
            hexBig(rpcResult(rpcUrl, "eth_estimateGas", JSONObject()
                .put("from", from).put("to", to).put("value", "0x${value.toString(16)}").put("data", data)))
        override fun sendRawTransaction(rawTransaction: String): String =
            rpcResult(rpcUrl, "eth_sendRawTransaction", rawTransaction)
        override fun receipt(transactionHash: String): EvmBridgeTransactionEngine6649.Receipt? {
            val raw = rpcValue(rpcUrl, "eth_getTransactionReceipt", transactionHash)
            if (raw == null || raw == JSONObject.NULL) return null
            val row = raw as? JSONObject ?: JSONObject(raw.toString())
            return EvmBridgeTransactionEngine6649.Receipt(
                transactionHash = row.optString("transactionHash", transactionHash),
                blockNumber = hexBig(row.optString("blockNumber")),
                successful = hexBig(row.optString("status")) == BigInteger.ONE,
            )
        }
        override fun blockNumber(): BigInteger = hexBig(rpcResult(rpcUrl, "eth_blockNumber"))
    }

    private class PersistentEvmStore6649(
        private val context: Context,
    ) : EvmBridgeTransactionEngine6649.Store {
        private val prefs get() = context.getSharedPreferences("aate_evm_tx_spine_6649", Context.MODE_PRIVATE)
        override fun load(idempotencyKey: String): EvmBridgeTransactionEngine6649.Record? = try {
            prefs.getString(idempotencyKey, null)?.let(::decodeEvmRecord6649)
        } catch (_: Throwable) { null }
        override fun save(record: EvmBridgeTransactionEngine6649.Record) {
            check(prefs.edit().putString(record.idempotencyKey, encodeEvmRecord6649(record).toString()).commit()) {
                "EVM_RECOVERY_WRITE_FAILED"
            }
        }
    }

    private fun rpcValue(rpc: String, method: String, vararg params: Any): Any? {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method)
            .put("params", JSONArray().also { a -> params.forEach { a.put(it) } })
        val req = Request.Builder().url(rpc).post(body.toString().toRequestBody(jsonType)).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("RPC_${resp.code}: ${text.take(240)}")
            val json = JSONObject(text)
            if (json.has("error")) error("RPC_ERROR: ${json.optJSONObject("error")?.optString("message")}")
            return json.opt("result")
        }
    }

    private fun rpcResult(rpc: String, method: String, vararg params: Any): String =
        rpcValue(rpc, method, *params)?.toString().orEmpty()

    private fun getJson(url: String): JSONObject {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP_${resp.code}: ${text.take(300)}")
            return JSONObject(text).also { if (it.has("error")) error(it.optString("error")) }
        }
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun isEvmAddress(v: String?) = v?.matches(Regex("^0x[0-9a-fA-F]{40}$")) == true
    private fun hexBig(v: String) = v.removePrefix("0x").ifBlank { "0" }.toBigInteger(16)
    private fun quantity6649(v: String): BigInteger =
        if (v.startsWith("0x", true)) hexBig(v) else v.ifBlank { "0" }.toBigInteger()
    private fun hexBytes(v: String): ByteArray {
        val s = v.removePrefix("0x")
        require(s.length % 2 == 0) { "HEX_LENGTH_INVALID" }
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun encodeEvmRecord6649(r: EvmBridgeTransactionEngine6649.Record) = JSONObject()
        .put("idempotencyKey", r.idempotencyKey).put("chainId", r.chainId)
        .put("nonce", r.nonce.toString()).put("gasPrice", r.gasPrice.toString())
        .put("gasLimit", r.gasLimit.toString()).put("rawTransaction", r.rawTransaction)
        .put("transactionHash", r.transactionHash).put("stage", r.stage.name)
        .put("submitAttempts", r.submitAttempts).put("error", r.error)

    private fun decodeEvmRecord6649(raw: String): EvmBridgeTransactionEngine6649.Record {
        val j = JSONObject(raw)
        return EvmBridgeTransactionEngine6649.Record(
            j.getString("idempotencyKey"), j.getLong("chainId"), j.getString("nonce").toBigInteger(),
            j.getString("gasPrice").toBigInteger(), j.getString("gasLimit").toBigInteger(),
            j.getString("rawTransaction"), j.getString("transactionHash"),
            EvmBridgeTransactionEngine6649.Stage.valueOf(j.getString("stage")),
            j.getInt("submitAttempts"), j.optString("error"),
        )
    }

    private fun savePosition6649(context: Context, p: BridgePosition6649) {
        val json = JSONObject().put("positionId", p.positionId).put("chainKey", p.chainKey)
            .put("token", p.token).put("amountRaw", p.amountRaw.toString()).put("decimals", p.decimals)
            .put("forwardOrderId", p.forwardOrderId).put("sourceSignature", p.sourceSignature)
            .put("state", p.state).put("reverseOrderId", p.reverseOrderId).put("reverseTo", p.reverseTo)
            .put("reverseData", p.reverseData).put("reverseValue", p.reverseValue.toString())
            .put("reverseTransactionHash", p.reverseTransactionHash).put("receivedSol", p.receivedSol)
            .put("forwardSignedBase64", p.forwardSignedBase64)
            .put("destinationBalanceBeforeRaw", p.destinationBalanceBeforeRaw.toString())
            .put("sourceBalanceBeforeSol", p.sourceBalanceBeforeSol)
        check(context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE).edit()
            .putString(p.positionId, json.toString()).commit()) { "BRIDGE_POSITION_RECOVERY_WRITE_FAILED" }
    }

    private fun loadPosition6649(context: Context, positionId: String): BridgePosition6649? = try {
        context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE).getString(positionId, null)?.let { raw ->
            val j = JSONObject(raw)
            BridgePosition6649(
                positionId = j.getString("positionId"), chainKey = j.getString("chainKey"), token = j.getString("token"),
                amountRaw = j.getString("amountRaw").toBigInteger(), decimals = j.getInt("decimals"),
                forwardOrderId = j.getString("forwardOrderId"), sourceSignature = j.getString("sourceSignature"),
                state = j.getString("state"), reverseOrderId = j.optString("reverseOrderId"),
                reverseTo = j.optString("reverseTo"), reverseData = j.optString("reverseData"),
                reverseValue = j.optString("reverseValue", "0").toBigInteger(),
                reverseTransactionHash = j.optString("reverseTransactionHash"),
                receivedSol = j.optDouble("receivedSol", 0.0),
                forwardSignedBase64 = j.optString("forwardSignedBase64"),
                destinationBalanceBeforeRaw = j.optString("destinationBalanceBeforeRaw", "0").toBigInteger(),
                sourceBalanceBeforeSol = j.optDouble("sourceBalanceBeforeSol", -1.0),
            )
        }
    } catch (_: Throwable) { null }
}

sealed class BridgeQuoteResult {
    object NotConfigured : BridgeQuoteResult()
    data class Quoted(val provider: String, val destChain: String, val feeSol: Double, val etaSec: Int, val minSol: Double) : BridgeQuoteResult()
    data class Rejected(val code: String, val reason: String) : BridgeQuoteResult()
}
