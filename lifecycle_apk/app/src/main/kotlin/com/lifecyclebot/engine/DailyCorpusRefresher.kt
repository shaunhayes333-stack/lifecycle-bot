package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * DAILY CORPUS REFRESHER — V5.0.6302
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Operator directive (V5.0.6302): "this historical corpus should be triggered
 * by the bot daily not from here".
 *
 * The CI-side python fetcher (`scripts/fetch_solana_corpus.py`) still ships a
 * baseline `assets/historical_corpus.jsonl.gz` at build time so day-one
 * installs get instant shape samples, but the ONGOING refresh happens on-device:
 * once every 24h, this class pulls fresh Solana pairs from DexScreener's public
 * search endpoint (same rows the python script emits) and folds decisive
 * win/loss shapes into LiveWinDNAStore via captureSeed().
 *
 * Design constraints:
 *   • Runs on Dispatchers.IO, never on the bot loop scheduler — no risk of
 *     stealing throughput from live trading.
 *   • Fetch is best-effort: if DexScreener 429s or errors, we back off and
 *     re-try next boot. No hot retry loops.
 *   • Timestamp of last successful refresh persisted so it survives service
 *     restarts inside a 24h window.
 *   • Idempotent per day: rows use a synthetic ts anchored to today's UTC
 *     day-index so a second refresh within the same day upserts the same
 *     row keys instead of duplicating.
 */
object DailyCorpusRefresher {

    private const val TAG = "DailyCorpusRefresher"
    private const val PREFS_NAME = "corpus_refresher_v6302"
    private const val KEY_LAST_MS = "last_refresh_ms"
    private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
    // Boot delay so we don't hammer the network the same second the app cold-starts.
    private const val BOOT_DELAY_MS = 90_000L
    private const val REQUEST_TIMEOUT_MS = 15_000
    private const val SLEEP_BETWEEN_QUERIES_MS = 400L
    private const val TARGET_ROWS = 200
    // Reuse the same search vocabulary as the python fetcher.
    private val SEARCH_TERMS = listOf("sol", "meme", "pump", "bonk", "wif", "dog", "cat", "ai", "moon")

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var prefs: SharedPreferences? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        scope.launch {
            delay(BOOT_DELAY_MS)
            while (isActive) {
                val lastMs = prefs?.getLong(KEY_LAST_MS, 0L) ?: 0L
                val nowMs = System.currentTimeMillis()
                val nextDueMs = lastMs + ONE_DAY_MS
                if (nowMs >= nextDueMs) {
                    runRefreshOnce()
                    prefs?.edit()?.putLong(KEY_LAST_MS, System.currentTimeMillis())?.apply()
                    // sleep the full window plus small jitter so multiple devices
                    // don't collide on DexScreener at the exact same wall clock.
                    delay(ONE_DAY_MS + Random.nextLong(30_000L, 5L * 60_000L))
                } else {
                    val waitMs = (nextDueMs - nowMs).coerceAtLeast(60_000L)
                    delay(waitMs.coerceAtMost(ONE_DAY_MS))
                }
            }
        }
        try { ForensicLogger.lifecycle("DAILY_CORPUS_REFRESHER_STARTED_6302", "boot_delay=${BOOT_DELAY_MS}ms") } catch (_: Throwable) {}
    }

    private suspend fun runRefreshOnce() {
        var ingested = 0
        val seenMints = HashSet<String>()
        val dayIndex = System.currentTimeMillis() / ONE_DAY_MS
        // Synthetic ts base — anchored to today's UTC day so a second refresh
        // within the same day upserts the same row keys. Offset well past the
        // asset-seeded synthetic ts range (1.7e12) to avoid collision.
        val syntheticTsBase = 1_800_000_000_000L + dayIndex * 100_000L
        var rowIdx = 0
        LiveWinDNAStore.beginBulk()
        try {
            for (term in SEARCH_TERMS) {
                if (ingested >= TARGET_ROWS) break
                val pairs = try { fetchSearch(term) } catch (_: Throwable) { emptyList() }
                for (pair in pairs) {
                    if (ingested >= TARGET_ROWS) break
                    val base = pair.optJSONObject("baseToken") ?: continue
                    val mint = base.optString("address")
                    if (mint.isBlank() || mint in seenMints) continue
                    seenMints.add(mint)
                    if (ingestPair(pair, syntheticTsBase + rowIdx.toLong())) {
                        ingested += 1
                    }
                    rowIdx += 1
                }
                delay(SLEEP_BETWEEN_QUERIES_MS)
            }
        } finally {
            LiveWinDNAStore.endBulk()
        }
        try {
            ForensicLogger.lifecycle(
                "DAILY_CORPUS_REFRESH_DONE_6302",
                "ingested=$ingested target=$TARGET_ROWS terms=${SEARCH_TERMS.size} dayIdx=$dayIndex",
            )
        } catch (_: Throwable) {}
    }

    private fun fetchSearch(term: String): List<JSONObject> {
        val url = URL("https://api.dexscreener.com/latest/dex/search?q=$term")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = REQUEST_TIMEOUT_MS
            conn.readTimeout = REQUEST_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val arr = root.optJSONArray("pairs") ?: return emptyList()
            val out = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                if (p.optString("chainId").equals("solana", ignoreCase = true)) out.add(p)
            }
            return out
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun ingestPair(pair: JSONObject, ts: Long): Boolean {
        val base = pair.optJSONObject("baseToken") ?: return false
        val mint = base.optString("address").ifBlank { return false }
        val symbol = base.optString("symbol")
        val liq = pair.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
        val vol24 = pair.optJSONObject("volume")?.optDouble("h24", 0.0) ?: 0.0
        val priceChange = pair.optJSONObject("priceChange")
        val change24 = priceChange?.optDouble("h24", 0.0) ?: 0.0
        val change1 = priceChange?.optDouble("h1", 0.0) ?: 0.0
        val fdv = pair.optDouble("fdv", 0.0)
        val band = when {
            change24 >= 20.0 -> "win"
            change24 <= -20.0 -> "loss"
            else -> "scratch"
        }
        if (band == "scratch") return false
        val pattern = classifyPattern(liq, vol24, change1, change24, fdv, pair.optLong("pairCreatedAt", 0L))
        val lane = classifyLane(fdv, liq, change24)
        val entrySetup = "corpus_" + pattern
        val pnlPct = when (band) {
            "win" -> change24.coerceAtLeast(20.0)
            else -> change24.coerceAtMost(-20.0)
        }
        try {
            LiveWinDNAStore.captureSeed(
                mint = mint,
                symbol = symbol,
                lane = lane,
                source = "DEXSCREENER_DAILY",
                phase = "HISTORICAL_SEED_DAILY",
                entrySetup = entrySetup,
                chartPattern = pattern,
                entryScore = estimateEntryScore(liq, vol24, change1),
                entryMcap = fdv,
                exitMcap = fdv * (1.0 + pnlPct / 100.0),
                entryLiquidity = liq,
                holdTimeMinutes = estimateHoldMinutes(change1, change24),
                buyPercent = 0.0,
                pnlPct = pnlPct,
                peakPnl = if (band == "win") pnlPct * 1.15 else pnlPct,
                exitReason = if (band == "win") "CORPUS_WIN_DAILY_6302" else "CORPUS_LOSS_DAILY_6302",
                paperOrLive = "CORPUS",
                ts = ts,
            )
        } catch (_: Throwable) {
            return false
        }
        return true
    }

    private fun classifyPattern(
        liq: Double, vol24: Double, change1: Double, change24: Double, fdv: Double, ageMs: Long,
    ): String {
        val nowMs = System.currentTimeMillis()
        val ageHours = if (ageMs > 0) ((nowMs - ageMs).coerceAtLeast(0L)) / 3_600_000.0 else 999.0
        return when {
            ageHours < 24 && liq > 1000 -> "fresh_pool_momentum"
            change24 > 50 && vol24 > liq * 2 -> "volume_ignition"
            change1 > 15 && change24 > 30 -> "breakout_continuation"
            liq > 250_000 && fdv > 5_000_000 -> "quality_accumulation_swing"
            liq > 100_000 && vol24 > 500_000 -> "liquidity_depth_quality"
            change24 > 100 -> "runner_near_high"
            kotlin.math.abs(change24) < 8 && vol24 > 0 -> "accumulation_compression"
            else -> "neutral_structure"
        }
    }

    private fun classifyLane(fdv: Double, liq: Double, change24: Double): String = when {
        fdv >= 100_000_000.0 && liq >= 250_000.0 -> "BLUECHIP"
        fdv >= 5_000_000.0 && liq >= 100_000.0 -> "SWING"
        change24 >= 100.0 -> "MOONSHOT"
        liq < 25_000.0 -> "DEGEN"
        else -> "STANDARD"
    }

    private fun estimateEntryScore(liq: Double, vol24: Double, change1: Double): Int {
        var score = 50
        if (liq > 100_000) score += 15
        if (vol24 > 500_000) score += 15
        if (change1 > 10) score += 10
        if (change1 < -10) score -= 15
        return score.coerceIn(0, 100)
    }

    private fun estimateHoldMinutes(change1: Double, change24: Double): Int {
        if (change1 > 25 && change24 > 40) return 60
        if (change1 > 10) return 180
        if (change24 > 30) return 360
        return 720
    }
}
