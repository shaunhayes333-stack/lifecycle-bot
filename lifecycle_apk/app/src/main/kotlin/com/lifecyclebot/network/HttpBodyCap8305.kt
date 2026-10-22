package com.lifecyclebot.network

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** V5.0.8305 — a response body larger than its endpoint's cap: refused while it is read, before it is a String. */
class BodyTooLargeException8305(val endpoint: String, val capBytes: Long) :
    IOException("BODY_CAP_8305 $endpoint over ${capBytes / 1024}KB")

/**
 * V5.0.8305 §BODY_CAP — every response that goes through [SharedHttpClient] is read through a byte-counting source.
 *
 * 5.0.8302 died of OutOfMemoryError after 62 min (512 MB limit) inside org.json parsing a large, nested body on an
 * OkHttp thread. Every caller reads a whole body with `body.string()` and then builds a JSON tree from it: a body of
 * N bytes costs ~N (okio buffer) + ~N-2N (String) + ~5-10N (JSONObject tree of small Strings / LinkedHashMaps) while
 * parsed. One oversized list answer (a token list, an account dump) therefore costs tens to hundreds of MB at once.
 *
 * The cap is applied while the body is read: the first read that passes the endpoint's cap throws
 * [BodyTooLargeException8305] (an IOException — every caller already treats it as a failed request), so an oversized
 * body never becomes a String or a tree. A declared Content-Length over the cap is refused before any byte is read.
 * Execution answers (quotes, swaps, signatures, balances, transactions) are a few KB to a few hundred KB; their caps
 * are far above that, so the cap never changes a sell / fill / balance proof — it only refuses bulk answers no reader
 * can afford. Upgrades (101: websockets) and event streams pass through unwrapped.
 *
 * Counted per endpoint: refusals and the largest body read, for the memory line.
 */
object HttpBodyCap8305 : Interceptor {

    const val DEFAULT_CAP_8305: Long = 6L shl 20          // 6 MB
    const val RPC_CAP_8305: Long = 8L shl 20              // Solana / Helius JSON-RPC (program / account dumps)
    const val MARKET_CAP_8305: Long = 4L shl 20           // DexScreener / Birdeye / GeckoTerminal / Codex / RugCheck
    const val LLM_CAP_8305: Long = 2L shl 20              // LLM answers
    const val TOKEN_LIST_CAP_8305: Long = 12L shl 20      // Jupiter verified / tag token lists (enrichment only)
    private const val ENDPOINT_KEYS_MAX_8305 = 64

    private val refusedTotal = AtomicLong(0L)
    private val readTotal = AtomicLong(0L)
    private val inFlightBytes = AtomicLong(0L)
    private val refusedBy = ConcurrentHashMap<String, AtomicLong>()
    private val largestBy = ConcurrentHashMap<String, AtomicLong>()

    /** Pure: the byte cap for a request to [host] [path]. */
    fun capFor8305(host: String, path: String): Long {
        val h = host.lowercase()
        val p = path.lowercase()
        return when {
            (h.endsWith("jup.ag") && p.startsWith("/tokens")) -> TOKEN_LIST_CAP_8305
            h.contains("helius") || h.contains("solana.com") || h.contains("rpc") || h.contains("quiknode") ||
                h.contains("alchemy") || h.contains("triton") || h.contains("ankr") -> RPC_CAP_8305
            h.contains("dexscreener") || h.contains("birdeye") || h.contains("geckoterminal") || h.contains("coingecko") ||
                h.contains("codex") || h.contains("rugcheck") || h.contains("dexpaprika") || h.contains("pump.fun") ||
                h.contains("pumpportal") || h.contains("raydium") || h.contains("meteora") -> MARKET_CAP_8305
            h.contains("openai") || h.contains("anthropic") || h.contains("groq") || h.contains("openrouter") ||
                h.contains("googleapis") || h.contains("mistral") || h.contains("deepseek") || h.contains("together") ||
                h.contains("perplexity") || h.contains("x.ai") -> LLM_CAP_8305
            else -> DEFAULT_CAP_8305
        }
    }

    /** Pure: a short, bounded endpoint name (host + first path segment) for the counters. */
    fun endpointKey8305(host: String, path: String): String {
        val seg = path.trim('/').substringBefore('/').take(24)
        return (host.removePrefix("www.").take(40) + if (seg.isNotEmpty()) "/$seg" else "")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        return wrap8305(chain.proceed(req), req.url.host, req.url.encodedPath)
    }

    /** Wrap [response]'s body in the counting source (or refuse a declared oversize body). Visible for tests. */
    internal fun wrap8305(response: Response, host: String, path: String): Response {
        if (response.code == 101) return response
        val body = response.body ?: return response
        val ct: MediaType? = body.contentType()
        if (ct != null && ct.subtype.equals("event-stream", ignoreCase = true)) return response
        val cap = capFor8305(host, path)
        val key = endpointKey8305(host, path)
        val declared = body.contentLength()
        if (declared > cap) {
            noteRefused8305(key)
            try { response.close() } catch (_: Throwable) {}
            throw BodyTooLargeException8305(key, cap)
        }
        return response.newBuilder().body(CappedBody8305(body, cap, key)).build()
    }

    private fun noteRefused8305(key: String) {
        refusedTotal.incrementAndGet()
        boundedCounter8305(refusedBy, key).incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HTTP_BODY_CAP_REFUSED_8305") } catch (_: Throwable) {}
    }

    private fun noteLargest8305(key: String, bytes: Long) {
        readTotal.incrementAndGet()
        val c = boundedCounter8305(largestBy, key)
        while (true) { val cur = c.get(); if (bytes <= cur || c.compareAndSet(cur, bytes)) break }
    }

    private fun boundedCounter8305(m: ConcurrentHashMap<String, AtomicLong>, key: String): AtomicLong {
        m[key]?.let { return it }
        val k = if (m.size >= ENDPOINT_KEYS_MAX_8305) "other" else key
        return m.computeIfAbsent(k) { AtomicLong(0L) }
    }

    private class CappedBody8305(private val delegate: ResponseBody, private val cap: Long, private val key: String) : ResponseBody() {
        private val capped: BufferedSource by lazy { CountingSource8305(delegate.source(), cap, key).buffer() }
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun source(): BufferedSource = capped
    }

    private class CountingSource8305(src: Source, private val cap: Long, private val key: String) : ForwardingSource(src) {
        private var total = 0L
        private var settled = false
        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, byteCount)
            if (n > 0L) {
                total += n
                HttpBodyCap8305.inFlightBytes.addAndGet(n)
                if (total > cap) { settle(); HttpBodyCap8305.noteRefused8305(key); throw BodyTooLargeException8305(key, cap) }
            } else if (n < 0L && !settled) {
                settle(); HttpBodyCap8305.noteLargest8305(key, total)
            }
            return n
        }
        override fun close() { settle(); super.close() }
        private fun settle() { if (!settled) { settled = true; HttpBodyCap8305.inFlightBytes.addAndGet(-total) } }
    }

    // ── read-outs ──
    fun refusedTotal8305(): Long = refusedTotal.get()
    fun inFlightBytes8305(): Long = inFlightBytes.get().coerceAtLeast(0L)

    /** "refused=n[top] largest[top KB]" for the memory line. */
    fun line8305(top: Int = 4): String {
        val ref = refusedBy.entries.sortedByDescending { it.value.get() }.take(top).joinToString(",") { "${it.key}:${it.value.get()}" }
        val big = largestBy.entries.sortedByDescending { it.value.get() }.take(top).joinToString(",") { "${it.key}:${it.value.get() / 1024}KB" }
        return "bodyCap8305[refused=${refusedTotal.get()}${if (ref.isNotEmpty()) "($ref)" else ""} read=${readTotal.get()} " +
            "inFlightKB=${inFlightBytes8305() / 1024} largest=[${big.ifEmpty { "-" }}]]"
    }

    internal fun resetForTest8305() { refusedTotal.set(0L); readTotal.set(0L); inFlightBytes.set(0L); refusedBy.clear(); largestBy.clear() }
}
