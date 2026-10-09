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

    private const val MAX_SUBS = 40
    private const val RECONNECT_GAP_MS = 5_000L

    @Volatile private var keyProvider: () -> String = { "" }
    @Volatile private var onTick: (mint: String, priceSol: Double, mcapSol: Double) -> Unit = { _, _, _ -> }
    @Volatile private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var lastConnectMs = 0L

    private val desired = ConcurrentHashMap<String, String>()          // mint -> curve
    private val pending = ConcurrentHashMap<Int, String>()             // request id -> mint
    private val subByMint = ConcurrentHashMap<String, Int>()           // mint -> server sub id
    private val mintBySub = ConcurrentHashMap<Int, String>()
    private val nextId = AtomicInteger(1)
    private val ticks = AtomicLong(0)
    private val connects = AtomicLong(0)
    private val failures = AtomicLong(0)
    @Volatile private var lastTickMs = 0L

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
        if (want.isEmpty()) return
        if (ws == null) connect()
    }

    private fun connect() {
        val now = System.currentTimeMillis()
        if (now - lastConnectMs < RECONNECT_GAP_MS) return
        lastConnectMs = now
        val key = try { keyProvider() } catch (_: Throwable) { "" }
        if (key.isBlank()) return
        connects.incrementAndGet()
        val req = Request.Builder().url("wss://mainnet.helius-rpc.com/?api-key=$key").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                pending.clear(); subByMint.clear(); mintBySub.clear()
                desired.forEach { (m, c) -> subscribe(m, c) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) { try { handle(text) } catch (_: Throwable) {} }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failures.incrementAndGet(); closed(webSocket) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed(webSocket) }
        })
    }

    private fun closed(s: WebSocket) {
        if (ws === s) { ws = null; open = false }
    }

    private fun subscribe(mint: String, curve: String) {
        val id = nextId.getAndIncrement()
        pending[id] = mint
        val ok = ws?.send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "accountSubscribe")
            .put("params", JSONArray().put(curve).put(JSONObject().put("encoding", "base64").put("commitment", "processed"))).toString()) == true
        if (!ok) pending.remove(id)
    }

    private fun unsubscribe(mint: String) {
        val sub = subByMint.remove(mint) ?: return
        mintBySub.remove(sub)
        ws?.send(JSONObject().put("jsonrpc", "2.0").put("id", nextId.getAndIncrement()).put("method", "accountUnsubscribe")
            .put("params", JSONArray().put(sub)).toString())
    }

    private fun handle(text: String) {
        val o = JSONObject(text)
        if (o.has("id") && o.has("result") && o.opt("result") is Number) {
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
        try { PipelineHealthCollector.labelInc("CURVE_TICK_7968") } catch (_: Throwable) {}
    }

    fun statusLine7968(): String {
        val age = if (lastTickMs > 0L) "${(System.currentTimeMillis() - lastTickMs) / 1000}s" else "-"
        return "open=$open held=${desired.size} subs=${subByMint.size} ticks=${ticks.get()} lastTick=$age connects=${connects.get()} failures=${failures.get()}"
    }
}
