package com.lifecyclebot.network

import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8018 — free per-trade prices for held GRADUATED (PumpSwap) tokens.
 *
 * CurveTicks7968 prices a held coin from its bonding-curve account; once the coin graduates the
 * curve is complete and stops moving, and the position was priced by polling only — exactly the
 * coins that run furthest (and rug hardest) had the slowest marks. The PumpPortal PumpSwap stream
 * needs a funded key ("Minimum balance not met for PumpSwap websocket data").
 *
 * A PumpSwap pool's price IS its two vaults: every swap rewrites the base and quote token
 * accounts. The pool account (program pAMMBay6…) names them; it is read once per coin
 * (getAccountInfo, Helius RPC) from the pool/pair address the bot already holds, and is accepted
 * only when its own base mint is the coin and its quote mint is wrapped SOL. Both vaults are then
 * watched on the Helius standard WebSocket (accountSubscribe, processed) — free, per trade. A
 * price is emitted only when both vaults have reported the same slot (a pair from one swap), and
 * a coin's first price must agree with the bot's current mark within 3x (a wrong pool never
 * prices a position).
 *
 * Pool layout (after the 8-byte discriminator): pool_bump u8, index u16, creator, base_mint,
 * quote_mint, lp_mint, pool_base_token_account, pool_quote_token_account (32 bytes each), ...
 * SPL token account: amount u64 LE at offset 64. Mint account: decimals u8 at offset 44.
 */
object GradTicks8018 {
    private const val MAX_POOLS = 20
    private const val RETRY_MS = 3L * 60_000L
    private const val RECONNECT_GAP_MS = 5_000L
    private const val PUMP_AMM = "pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA"
    private const val WSOL = "So11111111111111111111111111111111111111112"
    private const val OFF_BASE_MINT = 43
    private const val OFF_QUOTE_MINT = 75
    private const val OFF_BASE_VAULT = 139
    private const val OFF_QUOTE_VAULT = 171

    class Pool(val mint: String, val pool: String, val baseVault: String, val quoteVault: String, val baseDecimals: Int)
    private class Side { @Volatile var amount = -1.0; @Volatile var slot = -1L }

    // ── pure ──

    private fun b58(b: ByteArray, off: Int): String =
        io.github.novacrypto.base58.Base58.base58Encode(b.copyOfRange(off, off + 32))

    /** The pool's (base vault, quote vault) when this pool trades [mint] against wrapped SOL, else null. */
    fun vaults8018(data: ByteArray, mint: String): Pair<String, String>? {
        if (data.size < OFF_QUOTE_VAULT + 32) return null
        return try {
            if (b58(data, OFF_BASE_MINT) != mint || b58(data, OFF_QUOTE_MINT) != WSOL) null
            else b58(data, OFF_BASE_VAULT) to b58(data, OFF_QUOTE_VAULT)
        } catch (_: Throwable) { null }
    }

    /** An SPL token account's raw amount (u64 LE at 64), or null. */
    fun tokenAmount8018(data: ByteArray): Double? {
        if (data.size < 72) return null
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (data[64 + i].toLong() and 0xFF)
        return if (v < 0) v.toULong().toDouble() else v.toDouble()
    }

    /** SOL per token from raw vault amounts. */
    fun price8018(baseRaw: Double, quoteRaw: Double, baseDecimals: Int): Double {
        if (!(baseRaw > 0.0) || !(quoteRaw > 0.0) || baseDecimals !in 0..12) return Double.NaN
        return (quoteRaw / 1e9) / (baseRaw / Math.pow(10.0, baseDecimals.toDouble()))
    }

    /** A coin's first vault price must sit within 3x of the bot's own mark (when it has one). */
    fun basisAgrees8018(priceSol: Double, refSol: Double): Boolean =
        !refSol.isFinite() || refSol <= 0.0 || (priceSol / refSol) in (1.0 / 3.0)..3.0

    // ── state ──

    @Volatile private var keyProvider: () -> String = { "" }
    @Volatile private var rpcProvider: () -> String = { "" }
    @Volatile private var poolsFor: (String) -> List<String> = { emptyList() }
    @Volatile private var refSol: (String) -> Double = { Double.NaN }
    @Volatile private var onTick: (mint: String, priceSol: Double, pool: String) -> Unit = { _, _, _ -> }
    @Volatile private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var lastConnectMs = 0L
    @Volatile private var lastTickMs = 0L

    private val pools = ConcurrentHashMap<String, Pool>()                 // mint -> resolved pool
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val resolving = ConcurrentHashMap.newKeySet<String>()
    private val desired = ConcurrentHashMap<String, Pool>()               // mint -> watched pool
    private val verified = ConcurrentHashMap.newKeySet<String>()
    private val vaultMint = ConcurrentHashMap<String, String>()           // vault -> mint
    private val sides = ConcurrentHashMap<String, Side>()                 // vault -> latest amount/slot
    private val pending = ConcurrentHashMap<Int, String>()                // request id -> vault
    private val subByVault = ConcurrentHashMap<String, Int>()
    private val vaultBySub = ConcurrentHashMap<Int, String>()
    private val nextId = AtomicInteger(1)
    private val ticks = AtomicLong(0)
    private val resolved = AtomicLong(0)
    private val unresolved = AtomicLong(0)
    private val basisRefused = AtomicLong(0)
    private val resolver = Executors.newSingleThreadExecutor { r -> Thread(r, "GradTicks8018").apply { isDaemon = true } }

    private val client: OkHttpClient by lazy {
        SharedHttpClient.builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }
    private val http: OkHttpClient by lazy { SharedHttpClient.builder().callTimeout(6, TimeUnit.SECONDS).build() }

    /** BotService.wireExternalStreams: keys, the coin's candidate pool addresses, its current SOL mark, and the tick sink. */
    fun start8018(
        key: () -> String,
        rpc: () -> String,
        candidates: (String) -> List<String>,
        ref: (String) -> Double,
        cb: (mint: String, priceSol: Double, pool: String) -> Unit,
    ) {
        keyProvider = key; rpcProvider = rpc; poolsFor = candidates; refSol = ref; onTick = cb
    }

    /** Open-position tick: follow the held mints that trade on a PumpSwap pool. */
    fun sync8018(mints: Set<String>) {
        val now = System.currentTimeMillis()
        val want = HashMap<String, Pool>()
        for (m in mints) {
            if (want.size >= MAX_POOLS) break
            val p = pools[m]
            if (p != null) { want[m] = p; continue }
            if (now - (failedAt[m] ?: 0L) >= RETRY_MS && resolving.add(m)) {
                try { resolver.execute { try { resolve(m) } finally { resolving.remove(m) } } } catch (_: Throwable) { resolving.remove(m) }
            }
        }
        for (m in desired.keys.toList()) if (m !in want) { desired.remove(m)?.let { unwatch(it) } }
        for ((m, p) in want) if (desired.put(m, p) == null) watch(p)
        if (want.isNotEmpty() && ws == null) connect()
    }

    private fun resolve(mint: String) {
        val now = System.currentTimeMillis()
        for (addr in poolsFor(mint).map { it.trim() }.filter { it.length in 32..44 && it != mint }.distinct()) {
            val (owner, data) = accountInfo(addr) ?: continue
            if (owner != PUMP_AMM) continue
            val (bv, qv) = vaults8018(data, mint) ?: continue
            val dec = accountInfo(mint)?.second?.takeIf { it.size > 44 }?.let { it[44].toInt() and 0xFF } ?: continue
            pools[mint] = Pool(mint, addr, bv, qv, dec)
            failedAt.remove(mint)
            resolved.incrementAndGet()
            try { PipelineHealthCollector.labelInc("PUMPSWAP_POOL_RESOLVED_8018") } catch (_: Throwable) {}
            return
        }
        if (failedAt.size > 2_000) failedAt.clear()
        failedAt[mint] = now
        unresolved.incrementAndGet()
    }

    /** (owner, data) of an account via the Helius RPC, or null. */
    private fun accountInfo(address: String): Pair<String, ByteArray>? {
        val url = try { rpcProvider() } catch (_: Throwable) { "" }
        if (url.isBlank()) return null
        val body = JSONObject().put("jsonrpc", "2.0").put("id", "grad8018").put("method", "getAccountInfo")
            .put("params", JSONArray().put(address).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed")))
        return try {
            http.newCall(Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val v = JSONObject(r.body?.string().orEmpty()).optJSONObject("result")?.optJSONObject("value") ?: return null
                val b64 = v.optJSONArray("data")?.optString(0).orEmpty()
                if (b64.isBlank()) return null
                v.optString("owner") to java.util.Base64.getDecoder().decode(b64)
            }
        } catch (_: Throwable) { null }
    }

    private fun watch(p: Pool) {
        vaultMint[p.baseVault] = p.mint; vaultMint[p.quoteVault] = p.mint
        sides[p.baseVault] = Side(); sides[p.quoteVault] = Side()
        if (open) { subscribe(p.baseVault); subscribe(p.quoteVault) }
    }

    private fun unwatch(p: Pool) {
        verified.remove(p.mint)
        for (v in listOf(p.baseVault, p.quoteVault)) {
            vaultMint.remove(v); sides.remove(v)
            val sub = subByVault.remove(v) ?: continue
            vaultBySub.remove(sub)
            ws?.send(JSONObject().put("jsonrpc", "2.0").put("id", nextId.getAndIncrement()).put("method", "accountUnsubscribe").put("params", JSONArray().put(sub)).toString())
        }
    }

    private fun connect() {
        val now = System.currentTimeMillis()
        if (now - lastConnectMs < RECONNECT_GAP_MS) return
        lastConnectMs = now
        val key = try { keyProvider() } catch (_: Throwable) { "" }
        if (key.isBlank()) return
        ws = client.newWebSocket(Request.Builder().url("wss://mainnet.helius-rpc.com/?api-key=$key").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                pending.clear(); subByVault.clear(); vaultBySub.clear()
                desired.values.forEach { subscribe(it.baseVault); subscribe(it.quoteVault) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) { try { handle(text) } catch (_: Throwable) {} }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed(webSocket) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed(webSocket) }
        })
    }

    private fun closed(s: WebSocket) { if (ws === s) { ws = null; open = false } }

    private fun subscribe(vault: String) {
        val id = nextId.getAndIncrement()
        pending[id] = vault
        val ok = ws?.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "accountSubscribe")
            .put("params", JSONArray().put(vault).put(JSONObject().put("encoding", "base64").put("commitment", "processed"))).toString()) == true
        if (!ok) pending.remove(id)
    }

    private fun handle(text: String) {
        val o = JSONObject(text)
        if (o.has("id") && o.opt("result") is Number) {
            val vault = pending.remove(o.optInt("id")) ?: return
            val sub = o.optInt("result")
            if (!vaultMint.containsKey(vault)) {
                ws?.send(JSONObject().put("jsonrpc", "2.0").put("id", nextId.getAndIncrement()).put("method", "accountUnsubscribe").put("params", JSONArray().put(sub)).toString())
                return
            }
            subByVault[vault] = sub; vaultBySub[sub] = vault
            return
        }
        if (o.optString("method") != "accountNotification") return
        val res = o.optJSONObject("params")?.optJSONObject("result") ?: return
        val vault = vaultBySub[o.optJSONObject("params")?.optInt("subscription", -1) ?: -1] ?: return
        val mint = vaultMint[vault] ?: return
        val pool = desired[mint] ?: return
        val data = res.optJSONObject("value")?.optJSONArray("data")?.optString(0) ?: return
        val amount = tokenAmount8018(try { java.util.Base64.getDecoder().decode(data) } catch (_: Throwable) { return }) ?: return
        val side = sides[vault] ?: return
        side.amount = amount; side.slot = res.optJSONObject("context")?.optLong("slot", -1L) ?: -1L
        val base = sides[pool.baseVault] ?: return
        val quote = sides[pool.quoteVault] ?: return
        if (base.slot < 0L || base.slot != quote.slot) return
        val px = price8018(base.amount, quote.amount, pool.baseDecimals)
        if (!px.isFinite() || px <= 0.0) return
        if (mint !in verified) {
            if (!basisAgrees8018(px, try { refSol(mint) } catch (_: Throwable) { Double.NaN })) {
                basisRefused.incrementAndGet()
                try { PipelineHealthCollector.labelInc("PUMPSWAP_TICK_BASIS_REFUSED_8018") } catch (_: Throwable) {}
                desired.remove(mint)?.let { unwatch(it) }
                pools.remove(mint); failedAt[mint] = System.currentTimeMillis() + 30L * 60_000L
                return
            }
            verified.add(mint)
        }
        ticks.incrementAndGet()
        lastTickMs = System.currentTimeMillis()
        try { onTick(mint, px, pool.pool) } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("PUMPSWAP_TICK_8018") } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val age = if (lastTickMs > 0L) "${(System.currentTimeMillis() - lastTickMs) / 1000}s" else "-"
        return "open=$open pools=${desired.size} subs=${subByVault.size} ticks=${ticks.get()} lastTick=$age resolved=${resolved.get()} unresolved=${unresolved.get()} basisRefused=${basisRefused.get()}"
    }
}
