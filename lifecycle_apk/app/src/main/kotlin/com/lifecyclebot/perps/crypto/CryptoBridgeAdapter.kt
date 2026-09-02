package com.lifecyclebot.perps.crypto

import android.content.Context
import android.util.Base64
import com.lifecyclebot.engine.MultiChainWalletVault6546
import com.lifecyclebot.network.SharedHttpClient
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    private val liveReadiness6647 = chains.keys.associateWith { LiveRouteReadiness6647() }
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

    sealed class Execution {
        data class Fulfilled(
            val sourceSignature: String, val orderId: String, val destinationChainId: Long,
            val destinationToken: String, val receivedRaw: BigInteger, val decimals: Int,
            val destinationTx: String,
        ) : Execution()
        data class Rejected(val code: String, val reason: String) : Execution()
    }

    suspend fun buySolToEvm(
        wallet: SolanaWallet, targetSymbol: String, targetChain: String?,
        targetToken: String?, sizeSol: Double,
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
        val before = erc20Balance(chain.rpc, token, stored.ethereumAddress)
        val lamports = BigInteger.valueOf((sizeSol * 1_000_000_000.0).toLong())
        val order = createOrder(SOLANA_CHAIN, NATIVE_SOL, lamports, chain.id, token,
            stored.ethereumAddress, stored.solanaAddress, stored.solanaAddress, stored.ethereumAddress)
        val impact = order.optDouble("usdPriceImpact", 0.0)
        if (!impact.isFinite() || kotlin.math.abs(impact) > 3.0)
            return@withContext Execution.Rejected("PRICE_IMPACT", "deBridge impact $impact% exceeds 3%")
        val estimated = order.optJSONObject("estimation")?.optJSONObject("dstChainTokenOut")
            ?: return@withContext Execution.Rejected("ESTIMATE_MISSING", "destination amount absent")
        val expectedRaw = estimated.optString("amount").toBigIntegerOrNull()
            ?: return@withContext Execution.Rejected("ESTIMATE_INVALID", "destination amount invalid")
        val decimals = estimated.optInt("decimals", -1)
        if (expectedRaw <= BigInteger.ZERO || decimals < 0)
            return@withContext Execution.Rejected("ESTIMATE_INVALID", "destination quantity invalid")

        // Prove the sell-back route before moving any SOL. This is quote/build
        // only; it does not approve or broadcast destination funds.
        try {
            createOrder(chain.id, token, expectedRaw, SOLANA_CHAIN, NATIVE_SOL,
                stored.solanaAddress, stored.ethereumAddress, stored.ethereumAddress, stored.solanaAddress)
        } catch (t: Throwable) {
            return@withContext Execution.Rejected("SELL_BACK_ROUTE_MISSING", t.message ?: "reverse route unavailable")
        }
        val txHex = order.optJSONObject("tx")?.optString("data").orEmpty()
        if (!txHex.startsWith("0x")) return@withContext Execution.Rejected("SOURCE_TX_MISSING", "serialized Solana transaction absent")
        val sourceSig = try {
            wallet.signSendAndConfirm(Base64.encodeToString(hexBytes(txHex), Base64.NO_WRAP))
        } catch (t: Throwable) {
            return@withContext Execution.Rejected("SOURCE_SUBMIT_FAILED", t.message ?: "Solana submit failed")
        }
        val orderId = order.optString("orderId")
        if (orderId.isBlank()) return@withContext Execution.Rejected("ORDER_ID_MISSING", "confirmed source tx has no DLN order id")
        val terminal = awaitTerminal(orderId)
        if (terminal.first !in terminalOk)
            return@withContext Execution.Rejected("BRIDGE_NOT_FULFILLED", "state=${terminal.first}")
        val delta = erc20Balance(chain.rpc, token, stored.ethereumAddress) - before
        if (delta <= BigInteger.ZERO)
            return@withContext Execution.Rejected("DESTINATION_BALANCE_UNPROVEN", "terminal order without token balance increase")
        Execution.Fulfilled(sourceSig, orderId, chain.id, token, delta, decimals, terminal.second)
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
        srcAuthority: String, dstAuthority: String,
    ): JSONObject {
        val q = linkedMapOf(
            "srcChainId" to "$srcChain", "srcChainTokenIn" to srcToken,
            "srcChainTokenInAmount" to "$srcAmount", "dstChainId" to "$dstChain",
            "dstChainTokenOut" to dstToken, "dstChainTokenOutRecipient" to recipient,
            "dstChainTokenOutAmount" to "auto", "senderAddress" to sender,
            "srcChainOrderAuthorityAddress" to srcAuthority, "srcChainRefundAddress" to sender,
            "dstChainOrderAuthorityAddress" to dstAuthority, "prependOperatingExpenses" to "true",
        )
        return getJson(CREATE + "?" + q.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) })
    }

    private fun erc20Balance(rpc: String, token: String, owner: String): BigInteger {
        val word = owner.removePrefix("0x").lowercase().padStart(64, '0')
        return hexBig(rpcResult(rpc, "eth_call", JSONObject().put("to", token).put("data", "0x70a08231$word"), "latest"))
    }

    private fun rpcResult(rpc: String, method: String, vararg params: Any): String {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method)
            .put("params", JSONArray().also { a -> params.forEach { a.put(it) } })
        val req = Request.Builder().url(rpc).post(body.toString().toRequestBody(jsonType)).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("RPC_${resp.code}: ${text.take(240)}")
            val json = JSONObject(text)
            if (json.has("error")) error("RPC_ERROR: ${json.optJSONObject("error")?.optString("message")}")
            return json.optString("result")
        }
    }

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
    private fun hexBytes(v: String): ByteArray {
        val s = v.removePrefix("0x")
        require(s.length % 2 == 0) { "HEX_LENGTH_INVALID" }
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

sealed class BridgeQuoteResult {
    object NotConfigured : BridgeQuoteResult()
    data class Quoted(val provider: String, val destChain: String, val feeSol: Double, val etaSec: Int, val minSol: Double) : BridgeQuoteResult()
    data class Rejected(val code: String, val reason: String) : BridgeQuoteResult()
}
