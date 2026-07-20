package com.lifecyclebot.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * HISTORICAL CORPUS SEEDER — V5.0.6302
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Bootstraps LiveWinDNAStore from `assets/historical_corpus.jsonl.gz` on every
 * cold-start so a fresh install (or a wiped SharedPreferences after debug reinstall)
 * has real Solana market shape samples on day one, instead of an empty DNA store
 * that would take days of live paper trading to fill.
 *
 * Corpus rows come from `scripts/fetch_solana_corpus.py` (weekly / manual GH
 * workflow) and daily in-app refresh via DailyCorpusRefresher. Row schema:
 *   {
 *     "mint", "symbol", "priceUsd", "liquidityUsd", "volume24hUsd",
 *     "priceChange24hPct", "priceChange1hPct", "fdv",
 *     "patterns": [str, ...], "outcomeBand": "win|loss|scratch",
 *     "sourceFingerprint": str, "priceHistory7d"?: [float, ...]
 *   }
 *
 * OPERATOR DIRECTIVE (V5.0.6302):
 *   • Merge/upsert every cold-start (idempotent by synthetic mint:ts key so
 *     re-seeding does not create duplicates and does not evict real captured
 *     wins from live paper trading).
 *   • Only "win" and "loss" bands are ingested — "scratch" rows would dilute
 *     the shape learning without adding a directional signal.
 */
object HistoricalCorpusSeeder {

    private const val TAG = "HistoricalCorpusSeeder"
    private const val ASSET_PATH = "historical_corpus.jsonl.gz"
    // V5.0.6302 — stable synthetic timestamp so re-seeding does not upsert
    // 55 fresh rows with new "ts" every boot (which would poison hold-time
    // percentiles and blow past MAX_ROWS after a few days).
    private const val CORPUS_SYNTHETIC_TS = 1_700_000_000_000L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun seedFromAssetsAsync(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            try {
                val ingested = seedFromAssetsBlocking(appCtx)
                try {
                    ForensicLogger.lifecycle(
                        "HISTORICAL_CORPUS_SEED_6302",
                        "ingested=$ingested asset=$ASSET_PATH",
                    )
                } catch (_: Throwable) {}
            } catch (t: Throwable) {
                try {
                    ErrorLogger.warn(TAG, "seed failed: ${t.javaClass.simpleName}:${t.message?.take(120)}")
                } catch (_: Throwable) {}
            }
        }
    }

    private fun seedFromAssetsBlocking(appCtx: Context): Int {
        val assetMgr = appCtx.assets
        val exists = try { assetMgr.list("")?.contains(ASSET_PATH) == true } catch (_: Throwable) { false }
        if (!exists) return 0
        var ingested = 0
        LiveWinDNAStore.beginBulk()
        try {
            assetMgr.open(ASSET_PATH).use { input ->
                GZIPInputStream(input).use { gz ->
                    BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).use { br ->
                        var line = br.readLine()
                        var idx = 0
                        while (line != null) {
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty() && trimmed.startsWith("{")) {
                                if (ingestRow(trimmed, idx)) ingested += 1
                            }
                            idx += 1
                            line = br.readLine()
                        }
                    }
                }
            }
        } finally {
            LiveWinDNAStore.endBulk()
        }
        return ingested
    }

    private fun ingestRow(json: String, rowIndex: Int): Boolean {
        val obj = try { JSONObject(json) } catch (_: Throwable) { return false }
        val mint = obj.optString("mint").ifBlank { return false }
        val band = obj.optString("outcomeBand", "scratch").lowercase()
        // Only decisive bands feed shape learning.
        if (band != "win" && band != "loss") return false
        val symbol = obj.optString("symbol")
        val liq = obj.optDouble("liquidityUsd", 0.0)
        val fdv = obj.optDouble("fdv", 0.0)
        val vol24 = obj.optDouble("volume24hUsd", 0.0)
        val change24 = obj.optDouble("priceChange24hPct", 0.0)
        val change1 = obj.optDouble("priceChange1hPct", 0.0)
        val patterns = obj.optJSONArray("patterns")
        val primaryPattern = patterns?.optString(0, "")?.ifBlank { "neutral_structure" }
            ?: "neutral_structure"
        // V5.0.6302 — synthetic setup label derives the coarser lane classification
        // that best matches DexScreener corpus rows. Kept namespaced so downstream
        // aggregators can distinguish corpus-seeded shape from live-captured wins.
        val entrySetup = "corpus_" + primaryPattern
        val lane = classifyLane(fdv, liq, change24)
        val phase = "HISTORICAL_SEED"
        val source = "DEXSCREENER_CORPUS"
        val pnlPct = when {
            band == "win" -> change24.coerceAtLeast(20.0)
            else -> change24.coerceAtMost(-20.0)
        }
        val exitReason = if (band == "win") "CORPUS_WIN_HISTORICAL_6302" else "CORPUS_LOSS_HISTORICAL_6302"
        // Idempotent capture — we do NOT go through LiveWinDNAStore.capture()
        // because that stamps System.currentTimeMillis() into `ts`, which would
        // produce 55 fresh rows on every boot. Instead we mint a stable synthetic
        // ts derived from row index so a re-seed upserts the SAME row key.
        try {
            LiveWinDNAStore.captureSeed(
                mint = mint,
                symbol = symbol,
                lane = lane,
                source = source,
                phase = phase,
                entrySetup = entrySetup,
                chartPattern = primaryPattern,
                entryScore = estimateEntryScore(liq, vol24, change1),
                entryMcap = fdv,
                exitMcap = if (band == "win") fdv * (1.0 + pnlPct / 100.0) else fdv * (1.0 + pnlPct / 100.0),
                entryLiquidity = liq,
                holdTimeMinutes = estimateHoldMinutes(change1, change24),
                buyPercent = 0.0,
                pnlPct = pnlPct,
                peakPnl = if (band == "win") pnlPct * 1.15 else pnlPct,
                exitReason = exitReason,
                paperOrLive = "CORPUS",
                ts = CORPUS_SYNTHETIC_TS + rowIndex.toLong(),
            )
        } catch (_: Throwable) {
            return false
        }
        return true
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
        // If the run happened mostly in the last hour, treat as a quick scalp.
        if (change1 > 25 && change24 > 40) return 60
        if (change1 > 10) return 180
        if (change24 > 30) return 360
        return 720
    }
}
