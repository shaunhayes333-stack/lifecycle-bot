package com.lifecyclebot.network

import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7968 — free per-trade prices for held bonding-curve tokens.
 *
 * The PumpPortal trade stream needs a funded key (owner: about $10/hour — not
 * feasible). The price of a pump.fun curve token IS its bonding-curve account: every
 * buy or sell rewrites the virtual reserves. Subscribing to that account on the Helius
 * standard WebSocket (accountSubscribe, the key the bot already has) delivers the new
 * reserves on every trade, at processed commitment, for free.
 *
 * Account layout (pump program 6EF8rr..., PDA ["bonding-curve", mint]):
 *   8-byte discriminator, then u64 LE: virtualTokenReserves, virtualSolReserves,
 *   realTokenReserves, realSolReserves, tokenTotalSupply, then bool complete.
 *   price (SOL per token) = (vSol / 1e9) / (vTok / 1e6); mcap (SOL) = price x supply / 1e6.
 * A completed (graduated) curve no longer prices the token and is ignored.
 *
 * Each tick goes through the same path as a PumpPortal trade (BotService:
 * applyPumpTradeMark7278 -> token mark, canonical mark, MarkBars, SpikeCapture;
 * and the candle print). The subscription set follows the open curve positions every
 * open-position tick (at most [MAX_SUBS]).
 */
object CurveTicks7968 {

    private const val MAX_SUBS = 88  // V5.0.8252 — held curves (<=40) plus the freshest / nearest-decision watched candidates (<=48)
    private const val RECONNECT_GAP_MS = 5_000L

    @Volatile private var keyProvider: () -> String = { "" }
    @Volatile private var onTick: (mint: String, priceSol: Double, mcapSol: Double) -> Unit = { _, _, _ -> }
    @Volatile private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var lastConnectMs = 0L

    private val desired = ConcurrentHashMap<String, String>()          // mint -> curve
    private val pending = ConcurrentHashMap<Int, String>()             // request id -> mint
    // V5.0.8095 — when each pending subscribe was sent; an unanswered one is re-sent (4abd6b13a: held=4 subs=0 ticks=0).
    private val pendingAt8095 = ConcurrentHashMap<Int, Long>()
    @Volatile private var lastError8095 = "-"
    private val subErrors8095 = AtomicLong(0)
    private const val ACK_TIMEOUT_MS_8095 = 15_000L
    private val subByMint = ConcurrentHashMap<String, Int>()           // mint -> server sub id
    private val mintBySub = ConcurrentHashMap<Int, String>()
    private val nextId = AtomicInteger(1)
    private val wsEndpointAttempt8079 = AtomicInteger(0)
    private val ticks = AtomicLong(0)
    private val connects = AtomicLong(0)
    private val failures = AtomicLong(0)
    @Volatile private var lastTickMs = 0L
    // V5.0.8102 — 5.0.8098: subErrors8095=1048 lastError=-32601:Method 'accountSubscribe' not found. The selected
    // endpoint does not serve accountSubscribe; every 15 s resend was refused again on the same socket. An endpoint
    // that answers -32601 is closed and benched for [METHOD_MISSING_BENCH_MS_8102]; the next connect takes another.
    private const val METHOD_MISSING_BENCH_MS_8102 = 30L * 60_000L
    private val methodMissingAt8102 = ConcurrentHashMap<String, Long>()
    @Volatile private var currentUrl8102 = ""
    private val benched8102 = AtomicLong(0)

    /** Pure: the endpoints not benched for answering -32601 within [benchMs] of [nowMs], in their order. */
    internal fun usableEndpoints8102(all: List<String>, missingAt: Map<String, Long>, nowMs: Long, benchMs: Long = METHOD_MISSING_BENCH_MS_8102): List<String> =
        all.filter { u -> missingAt[u]?.let { nowMs - it in 0L until benchMs } != true }

    private val client: OkHttpClient by lazy {
        SharedHttpClient.builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    /** Pure: (priceSol per token, mcap SOL) from a bonding-curve account, or null (short, empty or complete). */
    fun decode7968(b: ByteArray): Pair<Double, Double>? {
        if (b.size < 49) return null
        fun u64(off: Int): Double {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return if (v < 0) v.toULong().toDouble() else v.toDouble()
        }
        val vTok = u64(8)
        val vSol = u64(16)
        val supply = u64(40)
        if (b[48].toInt() != 0) return null
        if (!(vTok > 0.0) || !(vSol > 0.0)) return null
        val price = (vSol / 1e9) / (vTok / 1e6)
        if (!price.isFinite() || price <= 0.0) return null
        return price to (if (supply > 0.0) price * supply / 1e6 else 0.0)
    }

    /** BotService.wireExternalStreams: where ticks go and which Helius key to use. */
    fun start7968(key: () -> String, cb: (mint: String, priceSol: Double, mcapSol: Double) -> Unit) {
        keyProvider = key
        onTick = cb
    }

    /**
     * V5.0.8252 §THE_CURVE_ACCOUNT_IS_THE_TAPE — 5.0.8251: 4,519 curve ticks reached the tape as price-only prints
     * while `swaps` was missing on 12,092 evaluations (tape8100:NO_PRINTS) and bp on 5,011; PumpPortal delivered 94
     * prints. Every accountNotification carries the reserves AFTER a trade, so two consecutive snapshots of the same
     * account ARE the trade: SOL in and tokens out (buy) or SOL out and tokens in (sell), exact, on-chain, one source.
     * The first snapshot and a rewrite that moved nothing stay price-only. DataOrchestrator decides whether a keyed
     * stream already delivered this mint's trade (then the print stays price-only, nothing is counted twice).
     */
    data class Reserves8252(val vTok: Double, val vSol: Double)
    data class CurveTrade8252(val sol: Double, val tokens: Double, val isBuy: Boolean)

    /** Pure: the virtual reserves of an active curve account, or null (short, empty or complete). */
    fun decodeReserves8252(b: ByteArray): Reserves8252? {
        if (b.size < 49 || b[48].toInt() != 0) return null
        fun u64(off: Int): Double {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return if (v < 0) v.toULong().toDouble() else v.toDouble()
        }
        val vTok = u64(8); val vSol = u64(16)
        return if (vTok > 0.0 && vSol > 0.0) Reserves8252(vTok, vSol) else null
    }

    /** Pure: the executed trade between two reserve snapshots (SOL and tokens in UI units), or null when none is stated. */
    fun tradeBetween8252(prev: Reserves8252?, cur: Reserves8252): CurveTrade8252? {
        if (prev == null) return null
        val dSol = cur.vSol - prev.vSol
        val dTok = cur.vTok - prev.vTok
        return when {
            dSol > 0.0 && dTok < 0.0 -> CurveTrade8252(dSol / 1e9, -dTok / 1e6, true)
            dSol < 0.0 && dTok > 0.0 -> CurveTrade8252(-dSol / 1e9, dTok / 1e6, false)
            else -> null
        }
    }

    private val lastReserves8252 = ConcurrentHashMap<String, Reserves8252>()
    private val tradePrints8252 = AtomicLong(0)
    @Volatile private var onTrade8252: (mint: String, priceSol: Double, sol: Double, isBuy: Boolean) -> Boolean = { _, _, _, _ -> false }

    /** BotService.wireExternalStreams: the receiver of a reserve-delta trade; true when it was taped as a trade. */
    fun onTrade8252(cb: (mint: String, priceSol: Double, sol: Double, isBuy: Boolean) -> Boolean) { onTrade8252 = cb }

    /** Open-position tick: follow the held curve mints. */
    fun sync7968(mints: Set<String>) {
        val want = HashMap<String, String>()
        for (m in mints) {
            if (want.size >= MAX_SUBS) break
            val c = try { PumpCurveKeys7269.canonicalCurveKey7392(m) } catch (_: Throwable) { null } ?: continue
            want[m] = c
        }
        for (m in desired.keys.toList()) if (m !in want) { desired.remove(m); unsubscribe(m) }
        for ((m, c) in want) if (desired.put(m, c) == null && open) subscribe(m, c)
        // V5.0.8095 — a desired curve neither subscribed nor awaiting a fresh answer is asked again.
        if (open) {
            val now = System.currentTimeMillis()
            pendingAt8095.entries.removeIf { (id, at) -> (now - at > ACK_TIMEOUT_MS_8095).also { if (it) pending.remove(id) } }
            for ((m, c) in desired) if (!subByMint.containsKey(m) && !pending.containsValue(m)) {
                subscribe(m, c)
                try { PipelineHealthCollector.labelInc("CURVE_SUB_RESENT_8095") } catch (_: Throwable) {}
            }
        }
        if (want.isEmpty()) return
        if (ws == null) connect()
    }

    private fun connect() {
        val now = System.currentTimeMillis()
        if (now - lastConnectMs < RECONNECT_GAP_MS) return
        lastConnectMs = now
        val key = try { keyProvider() } catch (_: Throwable) { "" }
        val endpoints = usableEndpoints8102(com.lifecyclebot.engine.RuntimeProviderAuthority6685.websocketCandidates8079(key), methodMissingAt8102, now)
        if (endpoints.isEmpty()) return
        val attempt = wsEndpointAttempt8079.getAndIncrement()
        val selected = Math.floorMod(attempt, endpoints.size)
        connects.incrementAndGet()
        if (selected > 0) try { PipelineHealthCollector.labelInc("CURVE_WS_PROVIDER_FALLBACK_8079") } catch (_: Throwable) {}
        currentUrl8102 = endpoints[selected]
        val req = Request.Builder().url(endpoints[selected]).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                pending.clear(); pendingAt8095.clear(); subByMint.clear(); mintBySub.clear()
                desired.forEach { (m, c) -> subscribe(m, c) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // V5.0.8066 — metered like every Helius stream (2 credits per 0.1 MB).
                try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.meterWs("CURVE_TICKS", text.length) } catch (_: Throwable) {}
                try { handle(text) } catch (_: Throwable) {}
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failures.incrementAndGet(); closed(webSocket) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed(webSocket) }
        })
    }

    private fun benchCurrent8102() {
        val url = currentUrl8102
        val now = System.currentTimeMillis()
        if (url.isBlank() || usableEndpoints8102(listOf(url), methodMissingAt8102, now).isEmpty()) return
        methodMissingAt8102[url] = now
        benched8102.incrementAndGet()
        try { PipelineHealthCollector.labelInc("CURVE_WS_METHOD_MISSING_BENCHED_8102") } catch (_: Throwable) {}
        val s = ws ?: return
        try { s.close(1000, "accountSubscribe unsupported") } catch (_: Throwable) {}
        closed(s)
    }

    private fun closed(s: WebSocket) {
        if (ws === s) { ws = null; open = false }
    }

    private fun subscribe(mint: String, curve: String) {
        val id = nextId.getAndIncrement()
        pending[id] = mint
        pendingAt8095[id] = System.currentTimeMillis()
        val ok = ws?.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "accountSubscribe")
            .put("params", JSONArray().put(curve).put(JSONObject().put("encoding", "base64").put("commitment", "processed"))).toString()) == true
        if (!ok) { pending.remove(id); pendingAt8095.remove(id) }
    }

    private fun unsubscribe(mint: String) {
        lastReserves8252.remove(mint)
        val sub = subByMint.remove(mint) ?: return
        mintBySub.remove(sub)
        ws?.send(JSONObject().put("jsonrpc", "2.0").put("id", nextId.getAndIncrement()).put("method", "accountUnsubscribe")
            .put("params", JSONArray().put(sub)).toString())
    }

    private fun handle(text: String) {
        val o = JSONObject(text)
        // V5.0.8095 — a refused subscribe was dropped silently; it is now counted and named, and re-sent on the next sync.
        if (o.has("id") && o.has("error")) {
            val id = o.optInt("id")
            pendingAt8095.remove(id)
            if (pending.remove(id) != null) {
                val e = o.optJSONObject("error")
                val code = e?.optInt("code", 0) ?: 0
                subErrors8095.incrementAndGet()
                lastError8095 = "$code:${e?.optString("message").orEmpty().take(60)}"
                try { PipelineHealthCollector.labelInc("CURVE_SUB_ERROR_8095_${if (code < 0) "M${-code}" else code.toString()}") } catch (_: Throwable) {}
                if (code == -32601) benchCurrent8102()
            }
            return
        }
        if (o.has("id") && o.has("result") && o.opt("result") is Number) {
            pendingAt8095.remove(o.optInt("id"))
            val mint = pending.remove(o.optInt("id")) ?: return
            val sub = o.optInt("result")
            if (!desired.containsKey(mint)) {
                ws?.send(JSONObject().put("jsonrpc", "2.0").put("id", nextId.getAndIncrement()).put("method", "accountUnsubscribe").put("params", JSONArray().put(sub)).toString())
                return
            }
            subByMint[mint] = sub; mintBySub[sub] = mint
            return
        }
        if (o.optString("method") != "accountNotification") return
        val p = o.optJSONObject("params") ?: return
        val mint = mintBySub[p.optInt("subscription", -1)] ?: return
        val data = p.optJSONObject("result")?.optJSONObject("value")?.optJSONArray("data") ?: return
        val bytes = try { java.util.Base64.getDecoder().decode(data.optString(0)) } catch (_: Throwable) { return }
        val (px, mc) = decode7968(bytes) ?: return
        ticks.incrementAndGet()
        lastTickMs = System.currentTimeMillis()
        try { onTick(mint, px, mc) } catch (_: Throwable) {}
        // V5.0.8252 — two snapshots of the same account are the trade (side, SOL, tokens); the orchestrator tapes it
        // unless a keyed stream already delivered this mint. Otherwise (first snapshot, no movement, stream covered):
        // V5.0.8169 — the curve's own price reaches the executed-trade tape (price only: change / shape, never buy pressure).
        val cur = decodeReserves8252(bytes)
        val trade = if (cur != null) tradeBetween8252(lastReserves8252.put(mint, cur), cur) else null
        val taped = trade != null && (try { onTrade8252(mint, px, trade.sol, trade.isBuy) } catch (_: Throwable) { false })
        if (taped) {
            tradePrints8252.incrementAndGet()
            try { PipelineHealthCollector.labelInc(if (trade!!.isBuy) "CURVE_TRADE_PRINT_BUY_8252" else "CURVE_TRADE_PRINT_SELL_8252") } catch (_: Throwable) {}
        } else {
            try { com.lifecyclebot.engine.truth.TradeTape8100.notePrice8169(mint, px, com.lifecyclebot.engine.truth.TradeTape8100.SRC_CURVE_8169) } catch (_: Throwable) {}
        }
        try { PipelineHealthCollector.labelInc("CURVE_TICK_7968") } catch (_: Throwable) {}
    }

    fun statusLine7968(): String {
        val age = if (lastTickMs > 0L) "${(System.currentTimeMillis() - lastTickMs) / 1000}s" else "-"
        return "open=$open held=${desired.size} subs=${subByMint.size} ticks=${ticks.get()} tradePrints8252=${tradePrints8252.get()} lastTick=$age connects=${connects.get()} failures=${failures.get()} subErrors8095=${subErrors8095.get()} lastError=$lastError8095 benched8102=${benched8102.get()}"
    }
}
