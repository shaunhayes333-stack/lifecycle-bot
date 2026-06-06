package com.lifecyclebot.v4.meta

import com.lifecyclebot.network.SharedHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ExternalAlphaFeeds — V5.9.1382
 *
 * ADDITIVE, FAIL-OPEN external-alpha feeder. Polls two server-side Base44
 * functions (smart-money flow + token safety) and publishes the results into
 * CrossTalkFusionEngine as ordinary AATESignal sources. The fusion engine
 * already accepts arbitrary publishers, so NOTHING in the trade-critical path
 * changes — this only ADDS signal context.
 *
 * DOCTRINE COMPLIANCE:
 *  - SIGNAL-ONLY: published signals influence score shaping in fusion; they can
 *    NEVER hard-veto a candidate (only the entry #86 veto whitelist may do that).
 *  - FAIL-OPEN: any network/parse error => no signal published that cycle. The
 *    bot behaves exactly as it did before this file existed.
 *  - OFF CRITICAL PATH: runs on its own Dispatchers.IO coroutine on a slow
 *    cadence (default 90s). Never touches the main thread; never blocks scan/exec.
 *  - 500-token pool & scanner untouched: this neither prunes nor gates intake.
 *
 * SERVER FUNCTIONS (deployed under the Vex Superagent app):
 *  - gmgnSmartMoney : GMGN smart-money / momentum rank (Cloudflare-proxied)
 *  - tokenSafetyIntel : DexScreener + RugCheck fused safety per mint
 */
object ExternalAlphaFeeds {

    private const val TAG_SMART = "GMGN_SMART_MONEY"
    private const val TAG_SAFETY = "EXT_TOKEN_SAFETY"

    // Base URL of the Vex Superagent app functions (where these are deployed).
    // Overridable at runtime if the operator relocates the functions.
    @Volatile
    var functionsBaseUrl: String =
        "https://app.base44.com/api/apps/69de889928f0364d975c00cd/functions"

    @Volatile
    var enabled: Boolean = true

    private val running = AtomicBoolean(false)
    private const val POLL_MS = 90_000L

    private val http by lazy {
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
                try {
                    pollSmartMoney()
                } catch (_: Throwable) { /* fail-open */ }
                try {
                    delay(POLL_MS)
                } catch (_: Throwable) { /* cancelled */ }
            }
            running.set(false)
        }
    }

    fun stop() { enabled = false }

    /**
     * On-demand safety enrichment for a single mint the bot is already
     * considering. Publishes a fragility-tagged AATESignal. Fully optional &
     * fail-open — callers may ignore. Returns safetyScore (0..1) or null.
     */
    fun enrichSafety(mint: String, symbol: String?) {
        if (!enabled || mint.length < 32) return
        // fire-and-forget on IO; never blocks the caller.
        try {
            val req = Request.Builder()
                .url("$functionsBaseUrl/tokenSafetyIntel?mint=$mint")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val j = JSONObject(body)
                if (!j.optBoolean("ok", false)) return
                val frag = j.optDouble("fragilityScore", 0.5)
                val flags = jsonStrList(j.optJSONArray("riskFlags"))
                CrossTalkFusionEngine.publish(
                    AATESignal(
                        source = TAG_SAFETY,
                        market = "MEME",
                        symbol = symbol ?: j.optString("symbol", null),
                        confidence = (1.0 - frag).coerceIn(0.0, 1.0),
                        horizonSec = 300,
                        fragilityScore = frag.coerceIn(0.0, 1.0),
                        riskFlags = flags
                    )
                )
            }
        } catch (_: Throwable) { /* fail-open */ }
    }

    // ── internal ──────────────────────────────────────────────────────────

    private fun pollSmartMoney() {
        val req = Request.Builder()
            .url("$functionsBaseUrl/gmgnSmartMoney?chain=sol&tp=6h&limit=20")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            val body = resp.body?.string() ?: return
            val j = JSONObject(body)
            if (!j.optBoolean("ok", false)) return
            val arr = j.optJSONArray("tokens") ?: return
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val sym = t.optString("symbol", "")
                if (sym.isBlank()) continue
                val conf = t.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
                if (conf <= 0.0) continue
                val mom = t.optDouble("mom1hPct", 0.0)
                val flags = jsonStrList(t.optJSONArray("riskFlags"))
                val top10 = t.optDouble("top10Rate", 0.0)
                CrossTalkFusionEngine.publish(
                    AATESignal(
                        source = TAG_SMART,
                        market = "MEME",
                        symbol = sym,
                        confidence = conf,
                        direction = if (mom >= 0) "LONG" else null,
                        horizonSec = 600,
                        narrativeHeat = conf,                       // smart-money interest ~ heat
                        fragilityScore = top10.coerceIn(0.0, 1.0),  // concentration ~ fragility
                        rotationTarget = sym,
                        riskFlags = flags
                    )
                )
            }
        }
    }

    private fun jsonStrList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) {
            val s = a.optString(i, "")
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }
}
