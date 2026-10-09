package com.lifecyclebot.perps.crypto

import android.content.Context
import com.lifecyclebot.engine.MultiChainWalletVault6546
import com.lifecyclebot.network.SharedHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.util.concurrent.TimeUnit

/**
 * V5.0.7962 — native EVM swaps, no bridge per trade.
 *
 * Before this build every non-Solana crypto-universe candidate resolved to
 * BRIDGE_REQUIRED or PAPER_ONLY (CryptoUniverseRouteResolver): the only live
 * rail was a deBridge DLN round trip from the Solana wallet, whose fixed fee
 * needs a 0.40 SOL ticket (CryptoBridgeAdapter.MIN_VIABLE_TICKET_SOL_7734) —
 * twice the whole wallet. This executor swaps native gas token <-> ERC-20
 * directly on the chain, from the vault's EVM signer, through the KEYLESS
 * KyberSwap aggregator:
 *
 *   GET  aggregator-api.kyberswap.com/{chain}/api/v1/routes?tokenIn&tokenOut&amountIn
 *        -> { code: 0, data: { routeSummary: {...}, routerAddress } }
 *   POST aggregator-api.kyberswap.com/{chain}/api/v1/route/build
 *        { routeSummary, sender, recipient, slippageTolerance (bps), deadline, source }
 *        -> { code: 0, data: { data (calldata), routerAddress, amountIn, amountOut, transactionValue? } }
 *
 * The shapes are parsed defensively (data may be at the root, numbers may be
 * strings, transactionValue may be absent): a field we rely on that is missing
 * is a refusal, never a guess. The build must name the same router the quote
 * did and spend exactly the amount we asked for.
 *
 * Signing, nonce, gas, idempotent rebroadcast and receipt finality are the
 * existing EvmBridgeTransactionEngine6649 spine; the signer is
 * MultiChainWalletVault6546.evmCredentials6649. ERC-20 approval (exact amount,
 * to the quoted router) precedes every sell. honeypot.is v2 IsHoneypot
 * (keyless) is read before every live buy; a honeypot, a failed simulation, an
 * unknown verdict or a tax above the cap refuses the buy.
 *
 * Every economic number is a wallet balance delta: spent = native before - after
 * (gas included), received = native after - before (net of the sell's gas).
 */
object EvmSwapExecutor7962 {
    private const val TAG = "EvmSwap7962"
    const val NATIVE_7962 = "0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE"
    private const val KYBER = "https://aggregator-api.kyberswap.com"
    private const val CLIENT_ID = "aate-lifecycle-bot"
    private const val HONEYPOT = "https://api.honeypot.is/v2/IsHoneypot"
    private const val REQ_PREFS = "aate_evm_swap_req_7962"
    private const val SPINE_PREFS = "aate_evm_swap_spine_7962"

    /** One EVM network as every provider names it. */
    data class Chain7962(
        val key: String,
        val chainId: Long,
        val kyberSlug: String,
        val dexScreenerId: String,
        val geckoNetwork: String,
        val nativeSymbol: String,
        val wrappedNative: String,
        val rpcs: List<String>,
        /** honeypot.is chainID; a chain it does not cover returns UNKNOWN and is never bought live. */
        val honeypotChainId: Long,
    )

    val CHAINS_7962: List<Chain7962> = listOf(
        Chain7962("bsc", 56L, "bsc", "bsc", "bsc", "BNB", "0xbb4cdb9cbd36b01bd1cbaebf2de08d9173bc095c",
            listOf("https://bsc-dataseed.binance.org", "https://bsc-rpc.publicnode.com"), 56L),
        Chain7962("base", 8453L, "base", "base", "base", "ETH", "0x4200000000000000000000000000000000000006",
            listOf("https://mainnet.base.org", "https://base-rpc.publicnode.com"), 8453L),
        Chain7962("eth", 1L, "ethereum", "ethereum", "eth", "ETH", "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
            listOf("https://eth.llamarpc.com", "https://ethereum-rpc.publicnode.com"), 1L),
        Chain7962("arbitrum", 42161L, "arbitrum", "arbitrum", "arbitrum", "ETH", "0x82af49447d8a07e3bd95bd0d56f35241523fbab1",
            listOf("https://arb1.arbitrum.io/rpc", "https://arbitrum-one-rpc.publicnode.com"), 42161L),
        Chain7962("polygon", 137L, "polygon", "polygon", "polygon_pos", "POL", "0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270",
            listOf("https://polygon-bor-rpc.publicnode.com"), 137L),
        Chain7962("avax", 43114L, "avalanche", "avalanche", "avax", "AVAX", "0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7",
            listOf("https://avalanche-c-chain-rpc.publicnode.com"), 43114L),
        Chain7962("optimism", 10L, "optimism", "optimism", "optimism", "ETH", "0x4200000000000000000000000000000000000006",
            listOf("https://optimism-rpc.publicnode.com"), 10L),
    )

    /** Pure: our chain for any provider's network name (GeckoTerminal, DexScreener, Kyber), else null. */
    fun chainFor7962(raw: String?): Chain7962? {
        val r = raw?.trim()?.lowercase()?.replace('-', '_').orEmpty()
        if (r.isBlank()) return null
        val key = when (r) {
            "bsc", "bnb", "binance_smart_chain", "bnb_chain" -> "bsc"
            "base" -> "base"
            "eth", "ethereum", "mainnet" -> "eth"
            "arbitrum", "arbitrum_one", "arb" -> "arbitrum"
            "polygon", "polygon_pos", "matic" -> "polygon"
            "avax", "avalanche", "avalanche_c" -> "avax"
            "optimism", "optimistic_ethereum", "op" -> "optimism"
            else -> return null
        }
        return CHAINS_7962.firstOrNull { it.key == key }
    }

    fun isEvmAddress7962(v: String?): Boolean = v?.matches(Regex("^0x[0-9a-fA-F]{40}$")) == true

    // ── pure parsers (unit-tested) ──────────────────────────────────────────

    /** A KyberSwap route quote. [routeSummary] is passed back verbatim to /route/build. */
    data class KyberRoute7962(
        val routeSummary: JSONObject,
        val routerAddress: String,
        val amountIn: BigInteger,
        val amountOut: BigInteger,
        val amountInUsd: Double,
        val amountOutUsd: Double,
        val gasUnits: Long,
        val gasUsd: Double,
    ) {
        /** One-way loss to fee + price impact, percent (NaN when the quote carries no USD values). */
        val swapLossPct: Double get() =
            if (amountInUsd.isFinite() && amountInUsd > 0.0 && amountOutUsd.isFinite() && amountOutUsd >= 0.0)
                ((amountInUsd - amountOutUsd) / amountInUsd * 100.0).coerceAtLeast(-50.0) else Double.NaN
    }

    data class KyberBuild7962(
        val routerAddress: String,
        val calldata: String,
        val amountIn: BigInteger,
        val amountOut: BigInteger,
        /** Native value to send; null when the build did not say (caller decides from tokenIn). */
        val transactionValue: BigInteger?,
    )

    private fun bigOf(v: Any?): BigInteger? {
        val s = v?.toString()?.trim().orEmpty()
        if (s.isBlank() || s == "null") return null
        return try {
            if (s.startsWith("0x", true)) BigInteger(s.substring(2).ifBlank { "0" }, 16)
            else java.math.BigDecimal(s).toBigIntegerExact()
        } catch (_: Throwable) { null }
    }

    private fun dblOf(v: Any?): Double = v?.toString()?.trim()?.toDoubleOrNull() ?: Double.NaN

    /** Kyber envelopes put the payload in "data"; tolerate a bare payload. code != 0 is a refusal. */
    private fun kyberPayload(body: String): JSONObject? {
        val root = try { JSONObject(body) } catch (_: Throwable) { return null }
        if (root.has("code")) {
            val code = root.opt("code")?.toString()?.trim()
            if (code != "0") return null
        }
        return root.optJSONObject("data") ?: root
    }

    /** Pure: a /routes response, or null when anything we rely on is missing. */
    fun parseKyberRoute7962(body: String): KyberRoute7962? {
        val d = kyberPayload(body) ?: return null
        val rs = d.optJSONObject("routeSummary") ?: return null
        val router = d.optString("routerAddress", "").trim()
        if (!isEvmAddress7962(router)) return null
        val amountIn = bigOf(rs.opt("amountIn")) ?: return null
        val amountOut = bigOf(rs.opt("amountOut")) ?: return null
        if (amountIn <= BigInteger.ZERO || amountOut <= BigInteger.ZERO) return null
        return KyberRoute7962(
            routeSummary = rs,
            routerAddress = router,
            amountIn = amountIn,
            amountOut = amountOut,
            amountInUsd = dblOf(rs.opt("amountInUsd")),
            amountOutUsd = dblOf(rs.opt("amountOutUsd")),
            gasUnits = bigOf(rs.opt("gas"))?.toLong() ?: 0L,
            gasUsd = dblOf(rs.opt("gasUsd")),
        )
    }

    /**
     * Pure: a /route/build response, or null when it is incomplete or does not match the quote:
     * a different router than [expectRouter] or a different spend than [expectAmountIn] is refused.
     */
    fun parseKyberBuild7962(body: String, expectRouter: String?, expectAmountIn: BigInteger?): KyberBuild7962? {
        val d = kyberPayload(body) ?: return null
        val router = d.optString("routerAddress", "").trim()
        if (!isEvmAddress7962(router)) return null
        if (!expectRouter.isNullOrBlank() && !router.equals(expectRouter, ignoreCase = true)) return null
        val calldata = d.optString("data", "").trim()
        if (!calldata.startsWith("0x") || calldata.length < 10) return null
        val amountIn = bigOf(d.opt("amountIn")) ?: expectAmountIn ?: return null
        if (expectAmountIn != null && amountIn != expectAmountIn) return null
        val amountOut = bigOf(d.opt("amountOut")) ?: return null
        if (amountOut <= BigInteger.ZERO) return null
        return KyberBuild7962(router, calldata, amountIn, amountOut, bigOf(d.opt("transactionValue")))
    }

    /** honeypot.is verdict. [ok] false = never buy. Taxes are percent. */
    data class HoneypotVerdict7962(
        val ok: Boolean,
        val code: String,
        val buyTaxPct: Double,
        val sellTaxPct: Double,
    )

    /**
     * Pure: honeypot.is /v2/IsHoneypot. Refuses a honeypot, a failed or missing
     * simulation, a high/very-high risk summary, and any buy/sell/transfer tax above
     * [maxTaxPct]. A smaller tax is admitted and charged in the trade's cost.
     */
    fun parseHoneypot7962(body: String, maxTaxPct: Double = 10.0): HoneypotVerdict7962 {
        val root = try { JSONObject(body) } catch (_: Throwable) { return HoneypotVerdict7962(false, "UNPARSEABLE", Double.NaN, Double.NaN) }
        val hp = root.optJSONObject("honeypotResult")
        val sim = root.optJSONObject("simulationResult")
        val risk = root.optJSONObject("summary")?.optString("risk", "")?.trim()?.lowercase().orEmpty()
        val buy = sim?.let { dblOf(it.opt("buyTax")) } ?: Double.NaN
        val sell = sim?.let { dblOf(it.opt("sellTax")) } ?: Double.NaN
        val transfer = sim?.let { dblOf(it.opt("transferTax")) } ?: Double.NaN
        fun v(ok: Boolean, code: String) = HoneypotVerdict7962(ok, code, buy, sell)
        if (hp?.optBoolean("isHoneypot", false) == true || risk == "honeypot") return v(false, "HONEYPOT")
        if (root.has("simulationSuccess") && !root.optBoolean("simulationSuccess", false)) return v(false, "SIMULATION_FAILED")
        if (hp == null || sim == null) return v(false, "UNKNOWN")
        if (!buy.isFinite() || !sell.isFinite()) return v(false, "TAX_UNKNOWN")
        if (buy > maxTaxPct || sell > maxTaxPct || (transfer.isFinite() && transfer > maxTaxPct)) return v(false, "HIGH_TAX")
        if (risk == "high" || risk == "very_high" || risk == "very high") return v(false, "HIGH_RISK")
        return v(true, "OK")
    }

    /** Pure: slippage tolerance in basis points for a leg that pays [taxPct] (Kyber range 0..2000). */
    fun slippageBps7962(taxPct: Double, basePct: Double): Int {
        val t = if (taxPct.isFinite() && taxPct > 0.0) taxPct else 0.0
        return ((t + basePct) * 100.0).toInt().coerceIn(50, 2_000)
    }

    // ── network ─────────────────────────────────────────────────────────────

    private val http: OkHttpClient by lazy {
        SharedHttpClient.builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    }
    private val jsonType by lazy { "application/json".toMediaType() }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? = try {
        val b = Request.Builder().url(url).header("Accept", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.get().build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (_: Throwable) { null }

    private fun httpPost(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): String? = try {
        val b = Request.Builder().url(url).header("Accept", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.post(body.toString().toRequestBody(jsonType)).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (_: Throwable) { null }

    /** JSON-RPC over the chain's endpoints: a transport failure tries the next one; a node error is final. */
    private class Rpc7962(private val urls: List<String>) : EvmBridgeTransactionEngine6649.Rpc {
        fun value(method: String, vararg params: Any): Any? {
            var last: Throwable? = null
            for (url in urls) {
                val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method)
                    .put("params", JSONArray().also { a -> params.forEach { a.put(it) } })
                val text = try {
                    http.newCall(Request.Builder().url(url).post(body.toString().toRequestBody(jsonType)).build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("RPC_HTTP_${resp.code}")
                        resp.body?.string().orEmpty()
                    }
                } catch (t: Throwable) { last = t; continue }
                val json = try { JSONObject(text) } catch (t: Throwable) { last = t; continue }
                if (json.has("error")) throw IllegalStateException("RPC_ERROR: ${json.optJSONObject("error")?.optString("message")}")
                return json.opt("result")
            }
            throw IllegalStateException("RPC_UNREACHABLE: ${last?.message}")
        }
        fun str(method: String, vararg params: Any): String = value(method, *params)?.toString().orEmpty()
        fun big(method: String, vararg params: Any): BigInteger = hexBig(str(method, *params))
        override fun pendingNonce(address: String): BigInteger = big("eth_getTransactionCount", address, "pending")
        override fun gasPrice(): BigInteger = big("eth_gasPrice")
        override fun estimateGas(from: String, to: String, value: BigInteger, data: String): BigInteger =
            big("eth_estimateGas", JSONObject().put("from", from).put("to", to).put("value", "0x${value.toString(16)}").put("data", data))
        override fun sendRawTransaction(rawTransaction: String): String = str("eth_sendRawTransaction", rawTransaction)
        override fun receipt(transactionHash: String): EvmBridgeTransactionEngine6649.Receipt? {
            val raw = value("eth_getTransactionReceipt", transactionHash)
            if (raw == null || raw == JSONObject.NULL) return null
            val row = raw as? JSONObject ?: JSONObject(raw.toString())
            return EvmBridgeTransactionEngine6649.Receipt(
                transactionHash = row.optString("transactionHash", transactionHash),
                blockNumber = hexBig(row.optString("blockNumber")),
                successful = hexBig(row.optString("status")) == BigInteger.ONE,
            )
        }
        override fun blockNumber(): BigInteger = big("eth_blockNumber")
    }

    private fun hexBig(v: String): BigInteger = try {
        BigInteger(v.removePrefix("0x").ifBlank { "0" }, 16)
    } catch (_: Throwable) { BigInteger.ZERO }

    private class Store7962(private val context: Context) : EvmBridgeTransactionEngine6649.Store {
        private val prefs get() = context.getSharedPreferences(SPINE_PREFS, Context.MODE_PRIVATE)
        override fun load(idempotencyKey: String): EvmBridgeTransactionEngine6649.Record? = try {
            prefs.getString(idempotencyKey, null)?.let { raw ->
                val j = JSONObject(raw)
                EvmBridgeTransactionEngine6649.Record(
                    j.getString("k"), j.getLong("c"), BigInteger(j.getString("n")), BigInteger(j.getString("gp")),
                    BigInteger(j.getString("gl")), j.getString("raw"), j.getString("h"),
                    EvmBridgeTransactionEngine6649.Stage.valueOf(j.getString("s")), j.getInt("a"), j.optString("e"),
                )
            }
        } catch (_: Throwable) { null }
        override fun save(record: EvmBridgeTransactionEngine6649.Record) {
            val j = JSONObject().put("k", record.idempotencyKey).put("c", record.chainId).put("n", record.nonce.toString())
                .put("gp", record.gasPrice.toString()).put("gl", record.gasLimit.toString()).put("raw", record.rawTransaction)
                .put("h", record.transactionHash).put("s", record.stage.name).put("a", record.submitAttempts).put("e", record.error)
            check(prefs.edit().putString(record.idempotencyKey, j.toString()).commit()) { "EVM_SWAP_RECOVERY_WRITE_FAILED" }
        }
    }

    private fun rpcFor(chain: Chain7962) = Rpc7962(chain.rpcs)

    /** Native balance of [address] in wei, null when every endpoint failed. */
    fun nativeBalanceWei7962(chain: Chain7962, address: String): BigInteger? = try {
        rpcFor(chain).big("eth_getBalance", address, "latest")
    } catch (_: Throwable) { null }

    /** Gas price in wei, null when unreadable. */
    fun gasPriceWei7962(chain: Chain7962): BigInteger? = try {
        rpcFor(chain).gasPrice().takeIf { it > BigInteger.ZERO }
    } catch (_: Throwable) { null }

    private fun erc20Balance(rpc: Rpc7962, token: String, owner: String): BigInteger {
        val word = owner.removePrefix("0x").lowercase().padStart(64, '0')
        return rpc.big("eth_call", JSONObject().put("to", token).put("data", "0x70a08231$word"), "latest")
    }

    private fun erc20Allowance(rpc: Rpc7962, token: String, owner: String, spender: String): BigInteger =
        rpc.big("eth_call", JSONObject().put("to", token).put("data", EvmBridgeTransactionEngine6649.allowanceData(owner, spender)), "latest")

    /** ERC-20 decimals(), null when unreadable. */
    fun decimals7962(chain: Chain7962, token: String): Int? = try {
        rpcFor(chain).big("eth_call", JSONObject().put("to", token).put("data", "0x313ce567"), "latest").toInt().takeIf { it in 0..36 }
    } catch (_: Throwable) { null }

    /** A Kyber quote, null when no route. */
    fun quote7962(chain: Chain7962, tokenIn: String, tokenOut: String, amountIn: BigInteger): KyberRoute7962? {
        if (amountIn <= BigInteger.ZERO) return null
        val url = "$KYBER/${chain.kyberSlug}/api/v1/routes?tokenIn=$tokenIn&tokenOut=$tokenOut&amountIn=$amountIn&gasInclude=true"
        val body = httpGet(url, mapOf("x-client-id" to CLIENT_ID)) ?: return null
        return parseKyberRoute7962(body)
    }

    private fun build(chain: Chain7962, route: KyberRoute7962, from: String, slippageBps: Int): KyberBuild7962? {
        val req = JSONObject()
            .put("routeSummary", route.routeSummary)
            .put("sender", from)
            .put("recipient", from)
            .put("slippageTolerance", slippageBps)
            .put("deadline", System.currentTimeMillis() / 1000L + 600L)
            .put("source", CLIENT_ID)
        val body = httpPost("$KYBER/${chain.kyberSlug}/api/v1/route/build", req, mapOf("x-client-id" to CLIENT_ID)) ?: return null
        return parseKyberBuild7962(body, route.routerAddress, route.amountIn)
    }

    /** honeypot.is verdict for [token]; UNREACHABLE is a refusal like any unknown. */
    fun honeypot7962(chain: Chain7962, token: String, maxTaxPct: Double = 10.0): HoneypotVerdict7962 {
        val body = httpGet("$HONEYPOT?address=$token&chainID=${chain.honeypotChainId}")
            ?: return HoneypotVerdict7962(false, "UNREACHABLE", Double.NaN, Double.NaN)
        return parseHoneypot7962(body, maxTaxPct)
    }

    // ── execution ───────────────────────────────────────────────────────────

    sealed class Swap7962 {
        /** [nativeDeltaWei] = spent on a buy (gas included), received on a sell (net of gas, may be negative). */
        data class Filled(val txHash: String, val nativeDeltaWei: BigInteger, val tokenDeltaRaw: BigInteger, val quoteLossPct: Double) : Swap7962()
        data class Pending(val reason: String) : Swap7962()
        data class Rejected(val code: String, val reason: String) : Swap7962()
    }

    /** A prepared swap persisted before its first submission, so a restart resumes the same bytes. */
    private data class Prepared(
        val key: String, val chainId: Long, val from: String, val to: String, val data: String,
        val value: BigInteger, val nativeBefore: BigInteger, val tokenBefore: BigInteger, val quoteLossPct: Double,
        val done: String = "", val txHash: String = "", val nativeDelta: BigInteger = BigInteger.ZERO, val tokenDelta: BigInteger = BigInteger.ZERO,
    )

    private fun reqPrefs(ctx: Context) = ctx.getSharedPreferences(REQ_PREFS, Context.MODE_PRIVATE)

    private fun savePrepared(ctx: Context, p: Prepared) {
        val j = JSONObject().put("k", p.key).put("c", p.chainId).put("f", p.from).put("t", p.to).put("d", p.data)
            .put("v", p.value.toString()).put("nb", p.nativeBefore.toString()).put("tb", p.tokenBefore.toString())
            .put("q", if (p.quoteLossPct.isFinite()) p.quoteLossPct else 0.0)
            .put("done", p.done).put("h", p.txHash).put("nd", p.nativeDelta.toString()).put("td", p.tokenDelta.toString())
        check(reqPrefs(ctx).edit().putString(p.key, j.toString()).commit()) { "EVM_SWAP_PREPARED_WRITE_FAILED" }
    }

    private fun loadPrepared(ctx: Context, key: String): Prepared? = try {
        reqPrefs(ctx).getString(key, null)?.let { raw ->
            val j = JSONObject(raw)
            Prepared(
                j.getString("k"), j.getLong("c"), j.getString("f"), j.getString("t"), j.getString("d"),
                BigInteger(j.getString("v")), BigInteger(j.getString("nb")), BigInteger(j.getString("tb")), j.optDouble("q", 0.0),
                j.optString("done"), j.optString("h"), BigInteger(j.optString("nd", "0")), BigInteger(j.optString("td", "0")),
            )
        }
    } catch (_: Throwable) { null }

    /** Drops the persisted request of a finished position (its outcome is already booked). */
    fun forget7962(ctx: Context, key: String) {
        try { reqPrefs(ctx).edit().remove(key).apply() } catch (_: Throwable) {}
    }

    private suspend fun await(
        request: EvmBridgeTransactionEngine6649.Request,
        creds: org.web3j.crypto.Credentials,
        rpc: Rpc7962,
        store: Store7962,
    ): EvmBridgeTransactionEngine6649.Outcome {
        var r = try { EvmBridgeTransactionEngine6649.execute(request, creds, rpc, store) }
        catch (t: Throwable) { return thrown(request, store, t) }
        repeat(6) {
            if (r !is EvmBridgeTransactionEngine6649.Outcome.Pending) return r
            delay(2_500L)
            r = try { EvmBridgeTransactionEngine6649.execute(request, creds, rpc, store) }
            catch (t: Throwable) { return thrown(request, store, t) }
        }
        return r
    }

    /**
     * An exception (RPC outage on a receipt / block read) once a signed record exists is
     * transient: the tx may already be on chain, so it stays Pending and is never booked failed.
     */
    private fun thrown(
        request: EvmBridgeTransactionEngine6649.Request,
        store: Store7962,
        t: Throwable,
    ): EvmBridgeTransactionEngine6649.Outcome {
        val why = t.message ?: t.javaClass.simpleName
        val rec = store.load(request.idempotencyKey)
        return if (rec != null && rec.stage != EvmBridgeTransactionEngine6649.Stage.FAILED)
            EvmBridgeTransactionEngine6649.Outcome.Pending(rec, "EVM_RPC_RETRY:$why")
        else EvmBridgeTransactionEngine6649.Outcome.Failed(null, why)
    }

    private fun settle(ctx: Context, chain: Chain7962, p: Prepared, rpc: Rpc7962, token: String, buy: Boolean, hash: String): Swap7962 {
        val nativeAfter = try { rpc.big("eth_getBalance", p.from, "latest") } catch (_: Throwable) { return Swap7962.Pending("BALANCE_READ_RETRY") }
        val tokenAfter = try { erc20Balance(rpc, token, p.from) } catch (_: Throwable) { return Swap7962.Pending("BALANCE_READ_RETRY") }
        val nativeDelta = if (buy) p.nativeBefore - nativeAfter else nativeAfter - p.nativeBefore
        val tokenDelta = if (buy) tokenAfter - p.tokenBefore else p.tokenBefore - tokenAfter
        // A confirmed receipt whose balance has not moved yet is a lagging endpoint, not a failure: tokens may
        // have landed, so the position is never dropped here (the lane times an unsettled buy out visibly).
        if (tokenDelta <= BigInteger.ZERO) return Swap7962.Pending("TOKEN_DELTA_UNPROVEN_${chain.key.uppercase()}")
        savePrepared(ctx, p.copy(done = "FILLED", txHash = hash, nativeDelta = nativeDelta, tokenDelta = tokenDelta))
        return Swap7962.Filled(hash, nativeDelta, tokenDelta, p.quoteLossPct)
    }

    private fun outcomeOf(
        ctx: Context, chain: Chain7962, p: Prepared, rpc: Rpc7962, token: String, buy: Boolean,
        o: EvmBridgeTransactionEngine6649.Outcome,
    ): Swap7962 = when (o) {
        is EvmBridgeTransactionEngine6649.Outcome.Confirmed -> settle(ctx, chain, p, rpc, token, buy, o.record.transactionHash)
        is EvmBridgeTransactionEngine6649.Outcome.Pending -> Swap7962.Pending(o.reason)
        is EvmBridgeTransactionEngine6649.Outcome.Failed -> {
            savePrepared(ctx, p.copy(done = "FAILED"))
            Swap7962.Rejected(if (o.reason.contains("REVERTED")) "REVERTED" else "SUBMIT_FAILED", o.reason)
        }
    }

    private fun previous(p: Prepared): Swap7962? = when (p.done) {
        "FILLED" -> Swap7962.Filled(p.txHash, p.nativeDelta, p.tokenDelta, p.quoteLossPct)
        "FAILED" -> Swap7962.Rejected("PREVIOUS_FAILURE", "swap ${p.key} already failed")
        else -> null
    }

    /** Native -> [token] for [amountInWei]. Idempotent on [key]: a second call resumes or returns the first outcome. */
    suspend fun buy7962(ctx: Context, chain: Chain7962, token: String, amountInWei: BigInteger, key: String, slippageBps: Int): Swap7962 =
        withContext(Dispatchers.IO) {
            if (!isEvmAddress7962(token)) return@withContext Swap7962.Rejected("TOKEN_ADDRESS_INVALID", token)
            val creds = try { MultiChainWalletVault6546.evmCredentials6649(ctx) } catch (_: Throwable) { null }
                ?: return@withContext Swap7962.Rejected("EVM_SIGNER_MISSING", "multi-chain signer not active")
            val rpc = rpcFor(chain)
            val store = Store7962(ctx)
            val existing = loadPrepared(ctx, key)
            existing?.let { prev -> previous(prev)?.let { return@withContext it } }
            val prepared: Prepared = existing ?: run {
                val route = quote7962(chain, NATIVE_7962, token, amountInWei)
                    ?: return@withContext Swap7962.Rejected("NO_ROUTE", "kyber returned no route")
                val built = build(chain, route, creds.address, slippageBps)
                    ?: return@withContext Swap7962.Rejected("BUILD_FAILED", "kyber build incomplete or mismatched")
                // Never send more native than the ticket asked for.
                if (built.transactionValue != null && built.transactionValue != built.amountIn)
                    return@withContext Swap7962.Rejected("BUILD_VALUE_MISMATCH", "value=${built.transactionValue} amountIn=${built.amountIn}")
                val nativeBefore = try { rpc.big("eth_getBalance", creds.address, "latest") } catch (t: Throwable) {
                    return@withContext Swap7962.Rejected("RPC_UNREACHABLE", t.message ?: "balance")
                }
                val tokenBefore = try { erc20Balance(rpc, token, creds.address) } catch (t: Throwable) {
                    return@withContext Swap7962.Rejected("RPC_UNREACHABLE", t.message ?: "token balance")
                }
                Prepared(key, chain.chainId, creds.address, built.routerAddress, built.calldata,
                    built.transactionValue ?: built.amountIn, nativeBefore, tokenBefore, route.swapLossPct)
                    .also { savePrepared(ctx, it) }
            }
            val req = EvmBridgeTransactionEngine6649.Request(key, chain.chainId, prepared.from, prepared.to, prepared.data, prepared.value, minimumConfirmations = 1)
            outcomeOf(ctx, chain, prepared, rpc, token, true, await(req, creds, rpc, store))
        }

    /** [qtyRaw] of [token] -> native. Approves the quoted router (exact amount) first. Idempotent on [key]. */
    suspend fun sell7962(ctx: Context, chain: Chain7962, token: String, qtyRaw: BigInteger, key: String, slippageBps: Int): Swap7962 =
        withContext(Dispatchers.IO) {
            if (qtyRaw <= BigInteger.ZERO) return@withContext Swap7962.Rejected("QTY_ZERO", "nothing to sell")
            val creds = try { MultiChainWalletVault6546.evmCredentials6649(ctx) } catch (_: Throwable) { null }
                ?: return@withContext Swap7962.Rejected("EVM_SIGNER_MISSING", "multi-chain signer not active")
            val rpc = rpcFor(chain)
            val store = Store7962(ctx)
            val existing = loadPrepared(ctx, key)
            existing?.let { prev -> previous(prev)?.let { return@withContext it } }
            val prepared: Prepared = existing ?: run {
                val route = quote7962(chain, token, NATIVE_7962, qtyRaw)
                    ?: return@withContext Swap7962.Rejected("NO_ROUTE", "kyber returned no sell route")
                val allowance = try { erc20Allowance(rpc, token, creds.address, route.routerAddress) } catch (t: Throwable) {
                    return@withContext Swap7962.Pending("ALLOWANCE_READ_RETRY:${t.message}")
                }
                if (allowance < qtyRaw) {
                    val approve = EvmBridgeTransactionEngine6649.Request(
                        "$key:APPROVE", chain.chainId, creds.address, token,
                        EvmBridgeTransactionEngine6649.approvalData(route.routerAddress, qtyRaw), minimumConfirmations = 1,
                    )
                    when (val a = await(approve, creds, rpc, store)) {
                        is EvmBridgeTransactionEngine6649.Outcome.Confirmed -> Unit
                        is EvmBridgeTransactionEngine6649.Outcome.Pending -> return@withContext Swap7962.Pending("APPROVE_PENDING:${a.reason}")
                        is EvmBridgeTransactionEngine6649.Outcome.Failed -> return@withContext Swap7962.Rejected("APPROVE_FAILED", a.reason)
                    }
                }
                val built = build(chain, route, creds.address, slippageBps)
                    ?: return@withContext Swap7962.Rejected("BUILD_FAILED", "kyber sell build incomplete or mismatched")
                // A token -> native sell never carries native value.
                if (built.transactionValue != null && built.transactionValue > BigInteger.ZERO)
                    return@withContext Swap7962.Rejected("BUILD_VALUE_MISMATCH", "sell value=${built.transactionValue}")
                val nativeBefore = try { rpc.big("eth_getBalance", creds.address, "latest") } catch (t: Throwable) {
                    return@withContext Swap7962.Pending("BALANCE_READ_RETRY:${t.message}")
                }
                val tokenBefore = try { erc20Balance(rpc, token, creds.address) } catch (t: Throwable) {
                    return@withContext Swap7962.Pending("BALANCE_READ_RETRY:${t.message}")
                }
                Prepared(key, chain.chainId, creds.address, built.routerAddress, built.calldata,
                    built.transactionValue ?: BigInteger.ZERO, nativeBefore, tokenBefore, route.swapLossPct)
                    .also { savePrepared(ctx, it) }
            }
            val req = EvmBridgeTransactionEngine6649.Request(key, chain.chainId, prepared.from, prepared.to, prepared.data, prepared.value, minimumConfirmations = 1)
            outcomeOf(ctx, chain, prepared, rpc, token, false, await(req, creds, rpc, store))
        }

    /** The token balance the wallet holds now (reconciles a position after partial sells), null when unreadable. */
    fun tokenBalance7962(chain: Chain7962, token: String, owner: String): BigInteger? = try {
        erc20Balance(rpcFor(chain), token, owner)
    } catch (_: Throwable) { null }
}
