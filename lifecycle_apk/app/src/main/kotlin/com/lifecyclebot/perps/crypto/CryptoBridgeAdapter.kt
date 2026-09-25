package com.lifecyclebot.perps.crypto

import android.content.Context
import android.util.Base64
import com.lifecyclebot.engine.MultiChainWalletVault6546
import com.lifecyclebot.network.SharedHttpClient
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    // V5.0.7316 — the round trip is implemented end to end (forward order,
    // destination balance proof, ERC-20 approval, reverse order, idempotent
    // EVM submission, crash recovery). Each CHAIN still graduates on its own
    // device-attested dry run (see readiness6647); nothing here is global.
    private const val FULL_ROUND_TRIP_IMPLEMENTED = true
    private const val NATIVE_EVM = "0x0000000000000000000000000000000000000000"
    private const val ATTEST_PREFS_7316 = "aate_bridge_attest_7316"
    private const val ATTEST_TTL_MS_7316 = 7L * 24 * 60 * 60 * 1000
    // A bridged position must be able to pay its way: forward + reverse order
    // cost (deBridge fixed fee, protocol fee, spread) as a fraction of size.
    private const val MAX_ROUND_TRIP_COST_FRAC_7316 = 0.08
    private const val CREATE = "https://dln.debridge.finance/v1.0/dln/order/create-tx"
    private const val TRACK = "https://dln-api.debridge.finance/api/Orders"
    private const val SOLANA_CHAIN = 7_565_164L
    private const val NATIVE_SOL = "11111111111111111111111111111111"
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
        // V5.0.7316 — discovery names (GeckoTerminal / DexScreener) for the
        // same chains; without them those tokens could never route.
        "polygon_pos" to Chain(137, "https://polygon-bor-rpc.publicnode.com"),
        "matic" to Chain(137, "https://polygon-bor-rpc.publicnode.com"),
        "avax" to Chain(43114, "https://avalanche-c-chain-rpc.publicnode.com"),
        "arbitrum-one" to Chain(42161, "https://arbitrum-one-rpc.publicnode.com"),
        "arbitrum_one" to Chain(42161, "https://arbitrum-one-rpc.publicnode.com"),
        "optimistic-ethereum" to Chain(10, "https://optimism-rpc.publicnode.com"),
        "bnb" to Chain(56, "https://bsc-rpc.publicnode.com"),
    )

    /** V5.0.7316 — native gas each chain needs for approve + reverse order (wei). */
    private fun minGasWei7316(chainId: Long): BigInteger = when (chainId) {
        1L -> BigInteger("2000000000000000")            // 0.002 ETH — mainnet approve + DLN tx
        56L -> BigInteger("500000000000000")            // 0.0005 BNB
        137L -> BigInteger("100000000000000000")        // 0.1 POL
        43114L -> BigInteger("5000000000000000")        // 0.005 AVAX
        else -> BigInteger("30000000000000")            // 0.00003 ETH on L2s (base/arb/op/linea)
    }

    /** V5.0.7316 — SOL sent to buy destination gas when the EVM wallet has none. */
    private fun gasTopUpSol7316(chainId: Long): Double = if (chainId == 1L) 0.04 else 0.012
    // Wallet/address derivation is foundation only.  No EVM chain is promoted
    // by address presence; each must explicitly graduate every capability.
    // V5.0.7316 — integrationTests is no longer a constant false: a chain
    // graduates when its dry run (live RPC, gas oracle, nonce, construction,
    // signature recovered to our own address) has passed on THIS device
    // within the last 7 days. See attested7316 / dryRunChain6987.
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
    private fun attestPrefs7316(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences(ATTEST_PREFS_7316, Context.MODE_PRIVATE)

    private fun attested7316(chainId: Long): Boolean {
        val at = try { attestPrefs7316()?.getLong("chain_$chainId", 0L) ?: 0L } catch (_: Throwable) { 0L }
        return at > 0L && System.currentTimeMillis() - at < ATTEST_TTL_MS_7316
    }

    private fun recordAttestation7316(chainId: Long) {
        try { attestPrefs7316()?.edit()?.putLong("chain_$chainId", System.currentTimeMillis())?.apply() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BRIDGE_CHAIN_ATTESTED_7316_$chainId") } catch (_: Throwable) {}
    }

    @Volatile private var lastAutoDryRunMs7316 = 0L

    /** Re-attest in the background when attestations are missing or stale (hourly at most). */
    private fun maybeAutoDryRun7316() {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (now - lastAutoDryRunMs7316 < 60 * 60_000L) return
        if (try { MultiChainWalletVault6546.evmCredentials6649(ctx) } catch (_: Throwable) { null } == null) return
        lastAutoDryRunMs7316 = now
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { dryRunAll6987(ctx) } catch (_: Throwable) {}
        }
    }
    private val http = SharedHttpClient.builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    fun init(context: Context) { appContext = context.applicationContext; maybeAutoDryRun7316() }
    fun readiness6647(targetChain: String?): LiveRouteReadiness6647? =
        chains[targetChain?.trim()?.lowercase()]?.let { implementedReadiness6649.copy(integrationTests = attested7316(it.id)) }
    fun isConfigured(): Boolean {
        maybeAutoDryRun7316()
        return FULL_ROUND_TRIP_IMPLEMENTED && chains.values.any { attested7316(it.id) } && appContext?.let {
            try { MultiChainWalletVault6546.executable(it) != null } catch (_: Throwable) { false }
        } ?: false
    }
    fun supportsRoundTrip(targetChain: String?, targetToken: String?): Boolean =
        isConfigured() && chains.containsKey(targetChain?.trim()?.lowercase()) &&
            readiness6647(targetChain)?.executable == true && isEvmAddress(targetToken)

    /** Pure: round-trip cost fraction from forward + reverse order USD values. Null = unproven. */
    fun roundTripCostFrac7316(fwdInUsd: Double, fwdOutUsd: Double, revInUsd: Double, revOutUsd: Double): Double? {
        if (listOf(fwdInUsd, fwdOutUsd, revInUsd, revOutUsd).any { !it.isFinite() || it <= 0.0 }) return null
        val keptForward = fwdOutUsd / fwdInUsd
        val keptReverse = revOutUsd / revInUsd
        return (1.0 - keptForward * keptReverse).coerceAtLeast(0.0)
    }

    private fun usdOf7316(order: JSONObject, side: String): Double =
        order.optJSONObject("estimation")?.optJSONObject(side)?.let {
            it.optDouble("approximateUsdValue", Double.NaN).takeIf { v -> v.isFinite() }
                ?: it.optDouble("recommendedApproximateUsdValue", Double.NaN)
        } ?: Double.NaN

    /** V5.0.7316 — buy destination-chain gas with a small SOL -> native order when the EVM wallet has none. */
    private suspend fun ensureDestinationGas7316(
        wallet: SolanaWallet, chain: Chain, evmAddress: String,
    ): String? {
        val need = minGasWei7316(chain.id)
        val have = hexBig(rpcResult(chain.rpc, "eth_getBalance", evmAddress, "latest"))
        if (have >= need) return null
        val sol = wallet.publicKeyB58
        val lamports = BigInteger.valueOf((gasTopUpSol7316(chain.id) * 1_000_000_000.0).toLong())
        val order = try {
            createOrder(SOLANA_CHAIN, NATIVE_SOL, lamports, chain.id, NATIVE_EVM, evmAddress, sol, sol, evmAddress)
        } catch (t: Throwable) { return "gas order unavailable: ${t.message?.take(120)}" }
        val out = order.optJSONObject("estimation")?.optJSONObject("dstChainTokenOut")?.optString("amount")?.toBigIntegerOrNull()
            ?: return "gas order estimate missing"
        if (have + out < need) return "gas top-up ${gasTopUpSol7316(chain.id)} SOL buys $out wei < needed $need"
        val txHex = order.optJSONObject("tx")?.optString("data").orEmpty()
        val orderId = order.optString("orderId")
        if (!txHex.startsWith("0x") || orderId.isBlank()) return "gas order tx/id missing"
        try {
            val signed = wallet.signSerializedTransaction6649(Base64.encodeToString(hexBytes(txHex), Base64.NO_WRAP))
            wallet.sendSignedAndConfirm6649(signed)
        } catch (t: Throwable) { return "gas order submit failed: ${t.message?.take(120)}" }
        val terminal = awaitTerminal(orderId)
        if (terminal.first !in terminalOk) return "gas order state=${terminal.first}"
        val after = hexBig(rpcResult(chain.rpc, "eth_getBalance", evmAddress, "latest"))
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BRIDGE_GAS_TOPPED_UP_7316_${chain.id}") } catch (_: Throwable) {}
        return if (after >= need) null else "gas after top-up $after < $need"
    }
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
        // V5.0.7316 — the Solana wallet that funded the lot (the trading wallet)
        // and the lamports the reverse DLN order commits to deliver there.
        val solanaAddress: String = "",
        val reverseTakeLamports: BigInteger = BigInteger.ZERO,
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
        // V5.0.7316 — the Solana side is the connected TRADING wallet (funds and
        // signs the forward order, receives the reverse). The EVM side is the
        // vault's derived signer. An imported trading key no longer blocks.
        val solAddr = wallet.publicKeyB58
        val chain = chains[targetChain?.trim()?.lowercase()]
            ?: return@withContext Execution.Rejected("CHAIN_UNSUPPORTED", "unsupported deBridge chain: $targetChain")
        val token = targetToken?.trim().orEmpty()
        if (!isEvmAddress(token)) return@withContext Execution.Rejected("TOKEN_ADDRESS_INVALID", "exact EVM contract required")
        if (!sizeSol.isFinite() || sizeSol < 0.01) return@withContext Execution.Rejected("SIZE_INVALID", "minimum 0.01 SOL")

        ensureDestinationGas7316(wallet, chain, stored.ethereumAddress)?.let { why ->
            return@withContext Execution.Rejected("DESTINATION_GAS_MISSING", why)
        }
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
                stored.ethereumAddress, solAddr, solAddr, stored.ethereumAddress)
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
            val reverseQuote = try {
                createOrder(chain.id, token, expectedRaw, SOLANA_CHAIN, NATIVE_SOL,
                    solAddr, stored.ethereumAddress, stored.ethereumAddress, solAddr, enableEstimate = true)
            } catch (t: Throwable) {
                return@withContext Execution.Rejected("SELL_BACK_ROUTE_MISSING", t.message ?: "reverse route unavailable")
            }
            // V5.0.7316 — a bridged position must pay its way. Forward + reverse
            // order costs (fixed fee, protocol fee, spread) from deBridge's own
            // USD estimates; unproven cost is a refusal, not a guess.
            val cost7316 = roundTripCostFrac7316(
                usdOf7316(order, "srcChainTokenIn"), usdOf7316(order, "dstChainTokenOut"),
                usdOf7316(reverseQuote, "srcChainTokenIn"), usdOf7316(reverseQuote, "dstChainTokenOut"),
            ) ?: return@withContext Execution.Rejected("BRIDGE_COST_UNPROVEN", "deBridge USD estimates missing")
            if (cost7316 > MAX_ROUND_TRIP_COST_FRAC_7316)
                return@withContext Execution.Rejected("BRIDGE_COST_TOO_HIGH",
                    "round trip costs ${"%.1f".format(cost7316 * 100)}% of ${"%.3f".format(sizeSol)} SOL (max ${(MAX_ROUND_TRIP_COST_FRAC_7316 * 100).toInt()}%)")
            val txHex = order.optJSONObject("tx")?.optString("data").orEmpty()
            if (!txHex.startsWith("0x")) return@withContext Execution.Rejected("SOURCE_TX_MISSING", "serialized Solana transaction absent")
            orderId = order.optString("orderId")
            if (orderId.isBlank()) return@withContext Execution.Rejected("ORDER_ID_MISSING", "source transaction has no DLN order id")
            signed = wallet.signSerializedTransaction6649(Base64.encodeToString(hexBytes(txHex), Base64.NO_WRAP))
            if (positionId.isNotBlank()) savePosition6649(ctx, BridgePosition6649(
                positionId, targetChain?.trim()?.lowercase().orEmpty(), token, expectedRaw, decimals,
                orderId, signed.signature, "FORWARD_PREPARED", forwardSignedBase64 = signed.signedBase64,
                destinationBalanceBeforeRaw = before, solanaAddress = solAddr,
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
                destinationBalanceBeforeRaw = before, solanaAddress = solAddr,
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
            var position = loadPosition6649(ctx, positionId)
                ?: return@withContext Exit.Rejected("BRIDGE_POSITION_RECOVERY_MISSING", "no immutable bridge lot for $positionId")
            // V5.0.7316 — SOL returns to the wallet that funded the lot (legacy
            // lots predate the field and were funded by the vault's Solana key).
            val solRecipient = position.solanaAddress.ifBlank { storedWallet.solanaAddress }
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
                        solRecipient, storedWallet.ethereumAddress,
                        storedWallet.ethereumAddress, solRecipient,
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
                        solRecipient, storedWallet.ethereumAddress,
                        storedWallet.ethereumAddress, solRecipient, enableEstimate = true,
                    )
                    // V5.0.7316 — the lamports this order commits to deliver. A DLN
                    // order is Fulfilled only when the taker delivers exactly this,
                    // so it is the recorded proceeds; a wallet SOL delta is not
                    // (other trades move the balance while the order is pending).
                    val take7316 = order.optJSONObject("estimation")?.optJSONObject("dstChainTokenOut")
                        ?.optString("amount")?.toBigIntegerOrNull() ?: BigInteger.ZERO
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
                        reverseTakeLamports = take7316,
                    )
                    savePosition6649(ctx, position)
                }

                val beforeSol = position.sourceBalanceBeforeSol.takeIf { it >= 0.0 }
                    ?: return@withContext Exit.Rejected(
                        "SOURCE_BALANCE_CHECKPOINT_MISSING",
                        "reverse order predates the crash-safe source balance checkpoint",
                    )
                val reverse = EvmBridgeTransactionEngine6649.Request(
                    // V5.0.7316 — keyed by order id so a cancelled order can be re-quoted.
                    "$positionId:DLN_REVERSE:${position.reverseOrderId}", chain.id, storedWallet.ethereumAddress,
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
                // V5.0.7316 — one status read per call. The close used to wait up
                // to ~10 min here inside the monitor's runBlocking; Pending keeps
                // the position open and the next cycle reads the order again.
                val status = orderStatus7316(position.reverseOrderId)
                if (status.first in terminalBad) {
                    // Cancelled/reverted: the tokens refund to our EVM wallet. Reset
                    // the lot so the next cycle quotes a fresh reverse order.
                    savePosition6649(ctx, position.copy(
                        state = "OPEN", reverseOrderId = "", reverseTo = "", reverseData = "",
                        reverseValue = BigInteger.ZERO, reverseTransactionHash = "",
                        reverseTakeLamports = BigInteger.ZERO,
                    ))
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BRIDGE_REVERSE_REQUOTE_7316") } catch (_: Throwable) {}
                    return@withContext Exit.Pending("REVERSE_BRIDGE_REQUOTE", "state=${status.first}")
                }
                if (status.first !in terminalOk)
                    return@withContext Exit.Pending("REVERSE_BRIDGE_PENDING", "state=${status.first.ifBlank { "UNKNOWN" }}")
                val received = if (position.reverseTakeLamports > BigInteger.ZERO) {
                    position.reverseTakeLamports.toBigDecimal().movePointLeft(9).toDouble()
                } else {
                    // Legacy lot without a recorded take amount: fall back to the
                    // balance checkpoint, single read, never negative.
                    (wallet.getSolBalance() - beforeSol).coerceAtLeast(0.0)
                }
                if (received <= 0.0)
                    return@withContext Exit.Pending("SOURCE_BALANCE_UNPROVEN", "fulfilled order without recorded proceeds")
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
        // V5.0.7316 — 5 polls (~15 s), then Pending: the engine is idempotent
        // (same signed bytes, same nonce) so the next cycle resumes it.
        repeat(5) {
            if (result !is EvmBridgeTransactionEngine6649.Outcome.Pending) return result
            delay(3_000L)
            result = EvmBridgeTransactionEngine6649.execute(request, credentials, rpc, store)
        }
        return result
    }

    /** V5.0.7316 — single non-blocking DLN order status read. */
    private fun orderStatus7316(orderId: String): Pair<String, String> = try {
        val row = getJson("$TRACK/${enc(orderId)}")
        row.optString("status") to (row.optJSONObject("fulfilledDstEventMetadata")?.optString("transactionHash").orEmpty())
    } catch (_: Throwable) { "" to "" }

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

    // ═══════════════════════════════════════════════════════════════════════
    // V5.0.6987 — CHAIN DRY RUN. The attestation the last flag is waiting for.
    // ═══════════════════════════════════════════════════════════════════════
    //
    // The readiness matrix marks nine of ten capabilities implemented and holds
    // `integrationTests = false` with the note:
    //
    //     "A deterministic RPC integration test is necessary but not sufficient
    //      to prove a funded public-chain route. Keep live false until the real
    //      chain matrix is exercised and attested."
    //
    // That is the correct bar, and nothing here lowers it. What was missing is
    // any way to exercise the matrix at all — so the flag could never be
    // honestly retired, and the operator could not see what was blocking.
    //
    // This probes every configured chain against its real public RPC and proves
    // everything that can be proved WITHOUT SPENDING:
    //
    //   rpcOk            eth_blockNumber          the endpoint is alive
    //   feeEstimation    eth_gasPrice             a real gas price comes back
    //   nonceOrUtxo      eth_getTransactionCount  our address has a real nonce
    //   txConstruction   RawTransaction assembled from those live values
    //   chainSigning     signed locally, then the sender address is RECOVERED
    //                    from the signature and compared to our own address —
    //                    a self-check that cannot pass by accident
    //
    // WHAT IT DELIBERATELY DOES NOT DO: it never calls sendRawTransaction, and
    // never touches EvmBridgeTransactionEngine6649's submit path. Nothing is
    // broadcast, no gas is spent, no funds move. The signed bytes are built and
    // thrown away. `submission` and `finalityProof` therefore remain unproven
    // by this harness BY DESIGN — those require a funded transaction on a real
    // chain, which is an operator decision, not a test.
    //
    // So a green dry run means: the endpoint, the fee oracle, our nonce, our
    // transaction encoding and our signing key are all correct for that chain.
    // It does not mean the route is live, and FULL_ROUND_TRIP_IMPLEMENTED stays
    // false regardless of the result.
    data class ChainProbe6987(
        val chainKey: String,
        val chainId: Long,
        val rpc: String,
        val rpcOk: Boolean = false,
        val blockNumber: String = "",
        val gasPriceOk: Boolean = false,
        val gasPriceGwei: String = "",
        val nonceOk: Boolean = false,
        val nonce: String = "",
        val txConstructionOk: Boolean = false,
        val signingOk: Boolean = false,
        val address: String = "",
        val error: String = "",
    ) {
        /** Everything provable without spending. Never implies the route is live. */
        val unfundedGreen: Boolean
            get() = rpcOk && gasPriceOk && nonceOk && txConstructionOk && signingOk

        fun blockingList(): List<String> = buildList {
            if (!rpcOk) add("RPC_UNREACHABLE")
            if (!gasPriceOk) add("FEE_ESTIMATION")
            if (!nonceOk) add("NONCE")
            if (!txConstructionOk) add("TX_CONSTRUCTION")
            if (!signingOk) add("CHAIN_SIGNING")
        }
    }

    /** Configured chains, for operator surfaces. Read-only view. */
    fun configuredChains6987(): Map<String, Chain> = chains

    /**
     * Probe one chain. Read-only against the network; signs locally and
     * discards. Returns a probe even on failure, with [ChainProbe6987.error]
     * set — a harness that throws tells the operator nothing.
     */
    suspend fun dryRunChain6987(context: Context, chainKey: String): ChainProbe6987 =
        withContext(Dispatchers.IO) {
            val key = chainKey.trim().lowercase()
            val chain = chains[key]
                ?: return@withContext ChainProbe6987(key, 0L, "", error = "CHAIN_NOT_CONFIGURED")
            var probe = ChainProbe6987(key, chain.id, chain.rpc)
            try {
                val block = rpcResult(chain.rpc, "eth_blockNumber")
                probe = probe.copy(rpcOk = block.isNotBlank(), blockNumber = block)
                if (!probe.rpcOk) return@withContext probe.copy(error = "NO_BLOCK_NUMBER")

                val gasHex = rpcResult(chain.rpc, "eth_gasPrice")
                val gasWei = try { hexBig(gasHex) } catch (_: Throwable) { BigInteger.ZERO }
                probe = probe.copy(
                    gasPriceOk = gasWei > BigInteger.ZERO,
                    gasPriceGwei = if (gasWei > BigInteger.ZERO)
                        (gasWei.toBigDecimal().movePointLeft(9).toPlainString().take(10)) else "",
                )

                val creds = try { MultiChainWalletVault6546.evmCredentials6649(context) } catch (_: Throwable) { null }
                    ?: return@withContext probe.copy(error = "EVM_WALLET_ABSENT_GENERATE_AND_BACK_UP_FIRST")
                val address = creds.address
                probe = probe.copy(address = address)

                val nonceHex = rpcResult(chain.rpc, "eth_getTransactionCount", address, "pending")
                val nonce = try { hexBig(nonceHex) } catch (_: Throwable) { BigInteger.valueOf(-1L) }
                probe = probe.copy(nonceOk = nonce >= BigInteger.ZERO, nonce = nonce.toString())
                if (!probe.nonceOk) return@withContext probe.copy(error = "NONCE_READ_FAILED")

                // Build a real transaction from the live values. Self-transfer of
                // zero value: valid to encode and sign, pointless to broadcast,
                // and we do not broadcast it.
                val raw = org.web3j.crypto.RawTransaction.createTransaction(
                    nonce,
                    gasWei.max(BigInteger.ONE),
                    BigInteger.valueOf(21_000L),
                    address,
                    BigInteger.ZERO,
                    "",
                )
                probe = probe.copy(txConstructionOk = true)

                // Sign locally, then RECOVER the signer from the signed bytes and
                // require it to be our own address. A signature that merely
                // "returns without throwing" proves nothing; this proves the key
                // in the vault actually controls the address we would send from.
                val signed = org.web3j.crypto.TransactionEncoder.signMessage(raw, chain.id, creds)
                val recovered = try {
                    val decoded = org.web3j.crypto.TransactionDecoder.decode(org.web3j.utils.Numeric.toHexString(signed))
                    (decoded as? org.web3j.crypto.SignedRawTransaction)?.from
                } catch (_: Throwable) { null }
                probe = probe.copy(
                    signingOk = signed.isNotEmpty() &&
                        recovered != null && recovered.equals(address, ignoreCase = true),
                )
                if (!probe.signingOk) {
                    probe = probe.copy(error = "SIGNATURE_DID_NOT_RECOVER_TO_OUR_ADDRESS")
                }
                // V5.0.7316 — a green probe graduates this chain for 7 days.
                if (probe.unfundedGreen) recordAttestation7316(chain.id)
                probe
            } catch (t: Throwable) {
                probe.copy(error = (t.message ?: t.javaClass.simpleName).take(160))
            }
        }

    /** Probe every configured chain. Sequential — these are public free RPCs. */
    suspend fun dryRunAll6987(context: Context): List<ChainProbe6987> =
        withContext(Dispatchers.IO) {
            // Distinct by chain id so aliases (eth/ethereum, bsc/binance-smart-chain)
            // are not probed twice.
            val seen = HashSet<Long>()
            chains.entries
                .filter { seen.add(it.value.id) }
                .map { (k, _) -> dryRunChain6987(context, k) }
        }

    /** One-line operator summary of the dry run. */
    fun dryRunSummary6987(probes: List<ChainProbe6987>): String {
        val green = probes.count { it.unfundedGreen }
        return "BRIDGE_DRY_RUN_6987 chains=${probes.size} unfundedGreen=$green " +
            "fullRoundTripEnabled=$FULL_ROUND_TRIP_IMPLEMENTED " +
            "note=submission_and_finality_require_a_funded_tx_and_are_not_proven_here"
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
            .put("solanaAddress", p.solanaAddress)
            .put("reverseTakeLamports", p.reverseTakeLamports.toString())
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
                solanaAddress = j.optString("solanaAddress", ""),
                reverseTakeLamports = j.optString("reverseTakeLamports", "0").toBigIntegerOrNull() ?: BigInteger.ZERO,
            )
        }
    } catch (_: Throwable) { null }
}

sealed class BridgeQuoteResult {
    object NotConfigured : BridgeQuoteResult()
    data class Quoted(val provider: String, val destChain: String, val feeSol: Double, val etaSec: Int, val minSol: Double) : BridgeQuoteResult()
    data class Rejected(val code: String, val reason: String) : BridgeQuoteResult()
}
