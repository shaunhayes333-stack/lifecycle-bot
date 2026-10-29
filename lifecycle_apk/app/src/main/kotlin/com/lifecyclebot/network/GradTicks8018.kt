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
 *
 * V5.0.8144 — the device showed pools=0 while every feed ran. A coin was resolved only from addresses the bot happened
 * to hold (pair / token-map / last-price pool), which for a graduated pump.fun coin are usually the bonding curve or a
 * DexScreener pair id — never the PumpSwap pool — and only through the configured Helius RPC (blank key: nothing ever
 * resolved). The resolver failed the same way every 3 minutes for as long as the coin was held, silently. Now the
 * canonical pool PDA (the address pump.fun migrates every coin to) is the first candidate, the read falls back to the
 * configured public RPCs, every unresolved coin is counted under its reason (NO_CANDIDATE / NOT_PUMPSWAP /
 * RPC_UNANSWERED), retries are coalesced and capped per hour, and refused or unanswered vault subscriptions are counted
 * and re-sent instead of sitting pending forever.
 */
object GradTicks8018 {
    private const val MAX_POOLS = 60   // V5.0.8418 — every held pool, any venue OnChainDepth8038 knows
    private const val RETRY_MS = 3L * 60_000L
    private const val RECONNECT_GAP_MS = 5_000L
    private const val PUMP_AMM = "pAMMBay6oceH9fJKBRHGP5D4bD4sWpmSwMn52FMfXEA"
    private const val WSOL = "So11111111111111111111111111111111111111112"
    private const val OFF_BASE_MINT = 43
    private const val OFF_QUOTE_MINT = 75
    private const val OFF_BASE_VAULT = 139
    private const val OFF_QUOTE_VAULT = 171

    // V5.0.8418 §A_HELD_POSITION_IS_NEVER_PRICED_BY_A_POLL — owner: "there should never be a stale mark for a held token".
    // Only pump curves (CurveTicks7968) and PumpSwap vaults were on a chain feed; a QUALITY / CASHGEN / BLUECHIP position
    // on Raydium, Meteora or Orca was priced by DexScreener polls and an aggregator's price API, and its stops fired on
    // marks the fill then contradicted (8418 journal). This feed now follows every held pool whose layout
    // OnChainDepth8038 decodes: constant-product venues by their two vaults, concentrated venues (CLMM / Whirlpool /
    // DLMM) by the pool account itself through ConcentratedPrice8418. [owner] is the pool program; a concentrated pool
    // sets [stateAccount] (= pool) and no quote vault.
    class Pool(val mint: String, val pool: String, val baseVault: String, val quoteVault: String, val baseDecimals: Int,
               val owner: String = PUMP_AMM, val stateAccount: String = "")

    /** V5.0.8418 — the mark source a tick of this pool carries (a VAULT / POOL_STATE source is on the position's basis). */
    fun sourceFor8418(owner: String, concentrated: Boolean): String = when {
        owner == PUMP_AMM -> "PUMPSWAP_VAULT_WS_8018"
        concentrated -> "POOL_STATE_WS_8418_" + com.lifecyclebot.engine.truth.OnChainDepth8038.venueName8418(owner)
        else -> "POOL_VAULT_WS_8418_" + com.lifecyclebot.engine.truth.OnChainDepth8038.venueName8418(owner)
    }
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

    private fun b58Decode8144(s: String): ByteArray? = try {
        io.github.novacrypto.base58.Base58.base58Decode(s.trim()).takeIf { it.size == 32 }
    } catch (_: Throwable) { null }

    /**
     * V5.0.8144 — the canonical PumpSwap pool for a pump.fun coin: PDA ["pool", 0u16 LE, creator, mint, WSOL] under the
     * AMM, where creator is the pump program's PDA ["pool-authority", mint]. Verified on read like any candidate (owner
     * is the AMM, base mint is the coin, quote is WSOL), so a coin that never migrated simply has no such account.
     */
    fun canonicalPool8144(mint: String): String? {
        val m = b58Decode8144(mint) ?: return null
        val wsol = b58Decode8144(WSOL) ?: return null
        val authority = PumpCurveKeys7269.findProgramAddress7392(listOf("pool-authority".toByteArray(), m), PUMP_PROGRAM_8144) ?: return null
        val auth = b58Decode8144(authority) ?: return null
        return PumpCurveKeys7269.findProgramAddress7392(listOf("pool".toByteArray(), byteArrayOf(0, 0), auth, m, wsol), PUMP_AMM)
    }

    /** V5.0.8144 — pure: why a coin's pool did not resolve (null = it resolved). */
    fun unresolvedReason8144(candidates: Int, anyAnswered: Boolean, resolved: Boolean): String? = when {
        resolved -> null
        candidates == 0 -> "NO_CANDIDATE"
        anyAnswered -> "NOT_PUMPSWAP"
        else -> "RPC_UNANSWERED"
    }

    /** A coin's first vault price must sit within 3x of the bot's own mark (when it has one). */
    fun basisAgrees8018(priceSol: Double, refSol: Double): Boolean =
        !refSol.isFinite() || refSol <= 0.0 || (priceSol / refSol) in (1.0 / 3.0)..3.0

    // ── state ──

    @Volatile private var keyProvider: () -> String = { "" }
    @Volatile private var rpcProvider: () -> String = { "" }
    @Volatile private var poolsFor: (String) -> List<String> = { emptyList() }
    @Volatile private var refSol: (String) -> Double = { Double.NaN }
    @Volatile private var onTick: (mint: String, priceSol: Double, pool: String, source: String, quoteSol: Double) -> Unit = { _, _, _, _, _ -> }
    @Volatile private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var lastConnectMs = 0L
    @Volatile private var lastTickMs = 0L

    private val pools = ConcurrentHashMap<String, Pool>()                 // mint -> resolved pool
    private val failedAt = ConcurrentHashMap<String, Long>()              // V5.0.8144: basis-refused bench only
    // V5.0.8144 — one resolve per coin at a time, 3 min -> 30 min back-off after a futile one, at most 6 per hour.
    private val resolveGate8144 = com.lifecyclebot.engine.ProofDataCoalescer8144(
        windowMs = 60L * 60_000L, maxAttemptsPerWindow = 6, baseRetryMs = RETRY_MS, maxRetryMs = 30L * 60_000L,
    )
    private val noCandidate8144 = AtomicLong(0)
    private val notPumpSwap8144 = AtomicLong(0)
    private val rpcUnanswered8144 = AtomicLong(0)
    private val subErrors8144 = AtomicLong(0)
    private val subResent8144 = AtomicLong(0)
    @Volatile private var lastError8144 = "-"
    private val pendingAt8144 = ConcurrentHashMap<Int, Long>()
    private const val ACK_TIMEOUT_MS_8144 = 15_000L
    private const val PUMP_PROGRAM_8144 = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
    private const val MAX_RPC_TRIES_8144 = 3
    private val desired = ConcurrentHashMap<String, Pool>()               // mint -> watched pool
    private val verified = ConcurrentHashMap.newKeySet<String>()
    private val vaultMint = ConcurrentHashMap<String, String>()           // vault -> mint
    private val sides = ConcurrentHashMap<String, Side>()                 // vault -> latest amount/slot
    private val pending = ConcurrentHashMap<Int, String>()                // request id -> vault
    private val subByVault = ConcurrentHashMap<String, Int>()
    private val vaultBySub = ConcurrentHashMap<Int, String>()
    private val nextId = AtomicInteger(1)
    private val wsEndpointAttempt8079 = AtomicInteger(0)
    private val methodMissingAt8191 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var currentUrl8191 = ""
    private val benched8191 = java.util.concurrent.atomic.AtomicLong(0)
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
        cb: (mint: String, priceSol: Double, pool: String, source: String, quoteSol: Double) -> Unit,
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
            if (now - (failedAt[m] ?: 0L) < RETRY_MS) continue
            if (resolveGate8144.admit8144(m, now) != com.lifecyclebot.engine.ProofDataCoalescer8144.Admit8144.RUN) continue
            try {
                resolver.execute {
                    var ok = false
                    try { ok = resolve(m) } finally { resolveGate8144.done8144(m, ok, System.currentTimeMillis()) }
                }
            } catch (_: Throwable) { resolveGate8144.done8144(m, false, now) }
        }
        for (m in desired.keys.toList()) if (m !in want) { desired.remove(m)?.let { unwatch(it) } }
        for ((m, p) in want) if (desired.put(m, p) == null) watch(p)
        // V5.0.8144 — a vault subscribe neither acknowledged nor refused within the ack window is sent again.
        if (open) {
            pendingAt8144.entries.removeIf { (id, at) -> (now - at > ACK_TIMEOUT_MS_8144).also { if (it) pending.remove(id) } }
            for (p in desired.values) for (v in accounts8418(p)) {
                if (!subByVault.containsKey(v) && !pending.containsValue(v)) { subscribe(v); subResent8144.incrementAndGet() }
            }
        }
        if (want.isNotEmpty() && ws == null) connect()
    }

    /** True when [mint]'s PumpSwap pool resolved; otherwise counts the named reason (the gate schedules the retry). */
    private fun resolve(mint: String): Boolean {
        // V5.0.8418 — the sealed / token-register pools join the candidates (a held position's own venue first).
        val register8418 = try { com.lifecyclebot.AATEApp.appContextOrNull()?.let { com.lifecyclebot.engine.TokenMetaCache.get(it).lookup(mint) } } catch (_: Throwable) { null }
        val candidates = (listOfNotNull(try { canonicalPool8144(mint) } catch (_: Throwable) { null }) + poolsFor(mint).map { it.trim() } +
            listOfNotNull(register8418?.pairAddress, register8418?.lastPricePoolAddr).map { it.trim() })
            .filter { it.length in 32..44 && it != mint }.distinct()
        var anyAnswered = false
        for (addr in candidates) {
            val read = accountRead8144(addr) ?: continue
            anyAnswered = true
            val data = read.second ?: continue
            val owner8418 = read.first
            val dec = accountInfo(mint)?.second?.takeIf { it.size > 44 }?.let { it[44].toInt() and 0xFF } ?: continue
            val found8418: Pool? = when {
                owner8418 == PUMP_AMM -> vaults8018(data, mint)?.let { (bv, qv) -> Pool(mint, addr, bv, qv, dec) }
                com.lifecyclebot.engine.truth.OnChainDepth8038.priceInState8418(owner8418) ->
                    // the decode proves the layout and that the pool is coin / WSOL; a null is "not this coin's pool"
                    com.lifecyclebot.engine.truth.ConcentratedPrice8418.decode8418(owner8418, data, mint, dec, 9)
                        ?.let { Pool(mint, addr, addr, "", dec, owner8418, stateAccount = addr) }
                else -> com.lifecyclebot.engine.truth.OnChainDepth8038.vaultKeys8038(owner8418, data)?.let { (a, b) ->
                    // which vault holds the coin and which holds SOL: the SPL token account's mint is its first 32 bytes
                    val da = accountInfo(a)?.second?.takeIf { it.size >= 72 }
                    val db = accountInfo(b)?.second?.takeIf { it.size >= 72 }
                    val ma = da?.let { b58(it, 0) }; val mb = db?.let { b58(it, 0) }
                    when {
                        ma == mint && mb == WSOL -> Pool(mint, addr, a, b, dec, owner8418)
                        mb == mint && ma == WSOL -> Pool(mint, addr, b, a, dec, owner8418)
                        else -> null
                    }
                }
            }
            if (found8418 == null) continue
            pools[mint] = found8418
            failedAt.remove(mint)
            resolved.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("PUMPSWAP_POOL_RESOLVED_8018")
                PipelineHealthCollector.labelInc("HELD_POOL_RESOLVED_8418_" + com.lifecyclebot.engine.truth.OnChainDepth8038.venueName8418(owner8418))
            } catch (_: Throwable) {}
            return true
        }
        unresolved.incrementAndGet()
        val why = unresolvedReason8144(candidates.size, anyAnswered, resolved = false) ?: return false
        when (why) {
            "NO_CANDIDATE" -> noCandidate8144.incrementAndGet()
            "NOT_PUMPSWAP" -> notPumpSwap8144.incrementAndGet()
            else -> rpcUnanswered8144.incrementAndGet()
        }
        try { PipelineHealthCollector.labelInc("PUMPSWAP_POOL_UNRESOLVED_8144_$why") } catch (_: Throwable) {}
        return false
    }

    /** V5.0.8144 — the configured Helius RPC first, then the configured public RPCs (a blank key no longer means "never"). */
    private fun rpcUrls8144(): List<String> {
        val primary = try { rpcProvider() } catch (_: Throwable) { "" }
        val rest = try { com.lifecyclebot.engine.RuntimeProviderAuthority6685.rpcCandidates() } catch (_: Throwable) { emptyList() }
        return (listOf(primary) + rest).map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_RPC_TRIES_8144)
    }

    /** (owner, data) of an account — data null when the account does not exist — or null when no RPC answered. */
    private fun accountRead8144(address: String): Pair<String, ByteArray?>? {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", "grad8018").put("method", "getAccountInfo")
            .put("params", JSONArray().put(address).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed")))
            .toString()
        for (url in rpcUrls8144()) {
            val read: Pair<String, ByteArray?>? = try {
                http.newCall(Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val res = JSONObject(r.body?.string().orEmpty())
                    if (!res.has("result")) return@use null
                    val v = res.optJSONObject("result")?.optJSONObject("value") ?: return@use ("" to null)
                    val b64 = v.optJSONArray("data")?.optString(0).orEmpty()
                    v.optString("owner") to (if (b64.isBlank()) null else java.util.Base64.getDecoder().decode(b64))
                }
            } catch (_: Throwable) { null }
            if (read != null) return read
        }
        return null
    }

    /** (owner, data) of an existing account via the Helius RPC (public RPCs behind it), or null. */
    private fun accountInfo(address: String): Pair<String, ByteArray>? {
        val read = accountRead8144(address) ?: return null
        val data = read.second ?: return null
        return read.first to data
    }

    /** V5.0.8418 — the accounts a pool is followed by: its two vaults, or the pool account alone on a concentrated venue. */
    private fun accounts8418(p: Pool): List<String> = if (p.stateAccount.isNotBlank()) listOf(p.stateAccount) else listOf(p.baseVault, p.quoteVault)

    private fun watch(p: Pool) {
        for (a in accounts8418(p)) { vaultMint[a] = p.mint; sides[a] = Side() }
        if (open) accounts8418(p).forEach { subscribe(it) }
    }

    private fun unwatch(p: Pool) {
        verified.remove(p.mint)
        for (v in accounts8418(p)) {
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
        val preferredRpc = try { rpcProvider() } catch (_: Throwable) { "" }
        // V5.0.8191 — an endpoint that answered -32601 (no accountSubscribe) is benched like CurveTicks7968's (8102).
        val endpoints = CurveTicks7968.usableEndpoints8102(com.lifecyclebot.engine.RuntimeProviderAuthority6685.websocketCandidates8079(key, preferredRpc), methodMissingAt8191, now)
        if (endpoints.isEmpty()) return
        val selected = Math.floorMod(wsEndpointAttempt8079.getAndIncrement(), endpoints.size)
        currentUrl8191 = endpoints[selected]
        if (selected > 0) try { PipelineHealthCollector.labelInc("GRAD_WS_PROVIDER_FALLBACK_8079") } catch (_: Throwable) {}
        ws = client.newWebSocket(Request.Builder().url(endpoints[selected]).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                pending.clear(); subByVault.clear(); vaultBySub.clear()
                desired.values.forEach { p -> accounts8418(p).forEach { subscribe(it) } }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // V5.0.8066 — metered like every Helius stream (2 credits per 0.1 MB).
                try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.meterWs("GRAD_TICKS", text.length) } catch (_: Throwable) {}
                try { handle(text) } catch (_: Throwable) {}
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed(webSocket) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed(webSocket) }
        })
    }

    private fun closed(s: WebSocket) { if (ws === s) { ws = null; open = false } }

    private fun subscribe(vault: String) {
        val id = nextId.getAndIncrement()
        pending[id] = vault
        pendingAt8144[id] = System.currentTimeMillis()
        val ok = ws?.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "accountSubscribe")
            .put("params", JSONArray().put(vault).put(JSONObject().put("encoding", "base64").put("commitment", "processed"))).toString()) == true
        if (!ok) { pending.remove(id); pendingAt8144.remove(id) }
    }

    private fun handle(text: String) {
        val o = NetParseGuard8305.parseObject8305("GRAD_TICKS", text) ?: return  // V5.0.8305
        // V5.0.8144 — a refused vault subscribe was dropped silently (pending forever, pools>0 subs=0): counted, named,
        // re-sent on the next sync; an endpoint without accountSubscribe is dropped so the next connect rotates.
        if (o.has("id") && o.has("error")) {
            val id = o.optInt("id")
            pendingAt8144.remove(id)
            if (pending.remove(id) != null) {
                val e = o.optJSONObject("error")
                val code = e?.optInt("code", 0) ?: 0
                subErrors8144.incrementAndGet()
                lastError8144 = "$code:${e?.optString("message").orEmpty().take(60)}"
                try { PipelineHealthCollector.labelInc("PUMPSWAP_SUB_ERROR_8144_${if (code < 0) "M${-code}" else code.toString()}") } catch (_: Throwable) {}
                if (code == -32601) {
                    if (currentUrl8191.isNotBlank()) { methodMissingAt8191[currentUrl8191] = System.currentTimeMillis(); benched8191.incrementAndGet() }
                    ws?.let { s -> try { s.close(1000, "accountSubscribe unsupported") } catch (_: Throwable) {}; closed(s) }
                }
            }
            return
        }
        if (o.has("id") && o.opt("result") is Number) {
            pendingAt8144.remove(o.optInt("id"))
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
        val bytes8418 = try { java.util.Base64.getDecoder().decode(data) } catch (_: Throwable) { return }
        var quoteSol8418 = Double.NaN
        val px = if (pool.stateAccount.isNotBlank()) {
            // V5.0.8418 — a concentrated pool's spot price is in the pool account (sqrt price / active bin), decoded by layout
            com.lifecyclebot.engine.truth.ConcentratedPrice8418.decode8418(pool.owner, bytes8418, mint, pool.baseDecimals, 9) ?: return
        } else {
            val amount = tokenAmount8018(bytes8418) ?: return
            val side = sides[vault] ?: return
            side.amount = amount; side.slot = res.optJSONObject("context")?.optLong("slot", -1L) ?: -1L
            val base = sides[pool.baseVault] ?: return
            val quote = sides[pool.quoteVault] ?: return
            if (base.slot < 0L || base.slot != quote.slot) return
            quoteSol8418 = quote.amount / 1e9
            price8018(base.amount, quote.amount, pool.baseDecimals)
        }
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
        try { onTick(mint, px, pool.pool, sourceFor8418(pool.owner, pool.stateAccount.isNotBlank()), quoteSol8418) } catch (_: Throwable) {}
        // V5.0.8169 — the PumpSwap pool's own price reaches the executed-trade tape (price only: change / shape, never buy pressure).
        if (pool.owner == PUMP_AMM) try { com.lifecyclebot.engine.truth.TradeTape8100.notePrice8169(mint, px, com.lifecyclebot.engine.truth.TradeTape8100.SRC_PUMPSWAP_8169) } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("PUMPSWAP_TICK_8018") } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val age = if (lastTickMs > 0L) "${(System.currentTimeMillis() - lastTickMs) / 1000}s" else "-"
        return "open=$open pools=${desired.size} subs=${subByVault.size} ticks=${ticks.get()} lastTick=$age resolved=${resolved.get()} unresolved=${unresolved.get()} basisRefused=${basisRefused.get()} " +
            "noCandidate8144=${noCandidate8144.get()} notPumpSwap8144=${notPumpSwap8144.get()} rpcUnanswered8144=${rpcUnanswered8144.get()} " +
            "subErrors8144=${subErrors8144.get()} subResent8144=${subResent8144.get()} lastError8144=$lastError8144 benched8191=${benched8191.get()} resolveGate8144[${resolveGate8144.line8144()}]"
    }
}
