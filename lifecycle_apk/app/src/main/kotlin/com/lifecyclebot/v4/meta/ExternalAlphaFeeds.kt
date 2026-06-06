package com.lifecyclebot.v4.meta

import com.lifecyclebot.network.SharedHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ExternalAlphaFeeds — V5.9.1383 (FULLY ONBOARD)
 *
 * ADDITIVE, FAIL-OPEN external-alpha feeder. Calls GMGN (smart-money rank),
 * DexScreener (liquidity/flow) and RugCheck (risk) DIRECTLY ON-DEVICE — no
 * server dependency — and publishes the results into CrossTalkFusionEngine as
 * ordinary AATESignal sources. The fusion engine already accepts arbitrary
 * publishers, so NOTHING in the trade-critical path changes; this only ADDS
 * signal context.
 *
 * Everything is self-contained in the APK. The bot already hits DexScreener and
 * RugCheck natively and already defeats Cloudflare with browser headers (see
 * SolanaMarketScanner.get) — this reuses that exact idiom for GMGN.
 *
 * DOCTRINE COMPLIANCE:
 *  - SIGNAL-ONLY: published signals only shape scoring in fusion; they can NEVER
 *    hard-veto a candidate (only the entry #86 veto whitelist may do that).
 *  - FAIL-OPEN: any network/parse error => no signal that cycle. The bot behaves
 *    exactly as it did before this file existed.
 *  - OFF CRITICAL PATH: own Dispatchers.IO coroutine, slow cadence (90s). Never
 *    touches the main thread; never blocks scan/exec.
 *  - 500-token pool & scanner intake untouched; -15% hard SL & FDG veto intact.
 */
object ExternalAlphaFeeds {

    private const val TAG_SMART = "GMGN_SMART_MONEY"
    private const val TAG_SAFETY = "EXT_TOKEN_SAFETY"
    private const val POLL_MS = 90_000L

    // Browser UA — matches SolanaMarketScanner's Cloudflare-capable idiom.
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    @Volatile var enabled: Boolean = true

    private val running = AtomicBoolean(false)

    private val http: OkHttpClient by lazy {
        SharedHttpClient.builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Idempotent start. Safe to call from BotService.onCreate. */
    fun start(scope: CoroutineScope) {
        if (!enabled) return
        if (!running.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            while (isActive && enabled) {
                try { pollSmartMoney() } catch (_: Throwable) { /* fail-open */ }
                try { delay(POLL_MS) } catch (_: Throwable) { return@launch }
            }
            running.set(false)
        }
    }

    fun stop() { enabled = false }

    // ── GMGN smart-money rank (on-device, Cloudflare-fronted) ───────────────

    private fun pollSmartMoney() {
        val url = "https://gmgn.ai/defi/quotation/v1/rank/sol/swaps/6h" +
            "?orderby=swaps&direction=desc&filters[]=not_honeypot&filters[]=renounced"
        val body = httpGet(url, gmgn = true) ?: return
        val root = try { JSONObject(body) } catch (_: Throwable) { return }
        if (root.optInt("code", -1) != 0) return
        val rank = root.optJSONObject("data")?.optJSONArray("rank") ?: return
        val now = System.currentTimeMillis() / 1000L
        val n = minOf(rank.length(), 20)
        for (i in 0 until n) {
            val t = rank.optJSONObject(i) ?: continue
            val sym = t.optString("symbol", "").uppercase().take(16)
            if (sym.isBlank()) continue
            val buys = t.optInt("buys", 0)
            val sells = t.optInt("sells", 0)
            val buyRatio = if (buys + sells > 0) buys.toDouble() / (buys + sells) else 0.5
            val mom1h = t.optDouble("price_change_percent1h", 0.0)
            val holders = t.optInt("holder_count", 0)
            val top10 = t.optDouble("top_10_holder_rate", 0.0)
            val liq = t.optDouble("liquidity", 0.0)

            val buyComp = ((buyRatio - 0.5) * 2.5).coerceIn(0.0, 1.0)
            val momComp = (mom1h / 50.0).coerceIn(0.0, 1.0)
            val holderComp = (holders / 1500.0).coerceIn(0.0, 1.0)
            var conf = 0.45 * buyComp + 0.35 * momComp + 0.20 * holderComp

            val flags = ArrayList<String>(3)
            if (top10 >= 0.40) flags.add("TOP10_CONCENTRATED")
            if (liq in 0.0001..5000.0) flags.add("THIN_LIQUIDITY")
            if (holders in 1..149) flags.add("LOW_HOLDERS")
            if (flags.size >= 2) conf *= 0.5  // soft-shape, never zero-veto
            conf = conf.coerceIn(0.0, 1.0)
            if (conf <= 0.05) continue

            CrossTalkFusionEngine.publish(
                AATESignal(
                    source = TAG_SMART,
                    market = "MEME",
                    symbol = sym,
                    confidence = conf,
                    direction = if (mom1h >= 0) "LONG" else null,
                    horizonSec = 600,
                    narrativeHeat = conf,
                    fragilityScore = top10.coerceIn(0.0, 1.0),
                    rotationTarget = sym,
                    riskFlags = flags
                )
            )
        }
    }

    // ── On-demand per-mint safety enrichment (DexScreener + RugCheck) ───────

    /**
     * Publishes an EXT_TOKEN_SAFETY signal for a mint the bot is already
     * considering. Optional, fire-and-forget, fail-open. Never blocks the caller
     * beyond the two short GETs (call it off the hot path).
     */
    fun enrichSafety(mint: String, symbol: String?) {
        if (!enabled || mint.length < 32) return
        try {
            var safety = 0.5
            val flags = ArrayList<String>(6)

            // DexScreener
            val dexBody = httpGet("https://api.dexscreener.com/latest/dex/tokens/$mint")
            val pair = dexBody?.let { runCatching { JSONObject(it).optJSONArray("pairs") }.getOrNull() }
                ?.let { arr ->
                    var best: JSONObject? = null
                    var bestLiq = -1.0
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        val l = p.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                        if (l > bestLiq) { bestLiq = l; best = p }
                    }
                    best
                }
            if (pair != null) {
                val liq = pair.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                val created = pair.optLong("pairCreatedAt", 0L)
                val ageHr = if (created > 0) (System.currentTimeMillis() - created) / 3_600_000.0 else 0.0
                val txns = pair.optJSONObject("txns")?.optJSONObject("h1")
                val buys = txns?.optInt("buys", 0) ?: 0
                val sells = txns?.optInt("sells", 0) ?: 0
                val buyRatio = if (buys + sells > 0) buys.toDouble() / (buys + sells) else 0.5
                when {
                    liq >= 50_000 -> safety += 0.20
                    liq >= 10_000 -> safety += 0.10
                    liq in 0.0001..2999.99 -> { safety -= 0.20; flags.add("THIN_LIQUIDITY") }
                }
                if (ageHr < 0.5) { safety -= 0.10; flags.add("VERY_FRESH") } else if (ageHr > 24) safety += 0.05
                if (buyRatio >= 0.6) safety += 0.10 else if (buyRatio <= 0.35) { safety -= 0.10; flags.add("SELL_PRESSURE") }
            } else flags.add("NO_DEX_DATA")

            // RugCheck (same endpoint the bot already uses)
            val rcBody = httpGet("https://api.rugcheck.xyz/v1/tokens/$mint/report/summary")
            val rc = rcBody?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (rc != null) {
                val rs = rc.optInt("score_normalised", rc.optInt("score", -1))
                when {
                    rs in 0..1000 -> safety += 0.15
                    rs >= 5000 -> { safety -= 0.25; flags.add("RUGCHECK_HIGH_RISK") }
                    rs >= 2500 -> { safety -= 0.10; flags.add("RUGCHECK_ELEVATED") }
                }
            } else flags.add("NO_RUGCHECK")

            safety = safety.coerceIn(0.0, 1.0)
            val frag = (1.0 - safety).coerceIn(0.0, 1.0)
            CrossTalkFusionEngine.publish(
                AATESignal(
                    source = TAG_SAFETY,
                    market = "MEME",
                    symbol = symbol,
                    confidence = safety,
                    horizonSec = 300,
                    fragilityScore = frag,
                    riskFlags = flags.distinct().take(8)
                )
            )
        } catch (_: Throwable) { /* fail-open */ }
    }

    // ── http helper (browser headers; Cloudflare-capable for gmgn) ──────────

    private fun httpGet(url: String, gmgn: Boolean = false): String? = try {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Connection", "keep-alive")
        if (gmgn) {
            b.header("Referer", "https://gmgn.ai/")
            b.header("Origin", "https://gmgn.ai")
        }
        http.newCall(b.build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Throwable) { null }
}
