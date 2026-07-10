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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.6221 — Historical Pattern Matcher
 * =========================================
 *
 * Operator directive (2026-07-09):
 *   "I want an historical chart database. find the best performing 1000
 *   solana tokens from the last 5 years... enough long range token and
 *   chart and pattern data so that the bot has a massive historical
 *   knowledge base to work off... good patterns so that bot can have
 *   some decent knowledge because its been unable to find any itself
 *   apparently."
 *
 * This module loads the compressed JSONL corpus shipped in
 * `assets/historical_corpus.jsonl.gz` (built weekly by
 * `.github/workflows/refresh-historical-corpus.yml` running
 * `scripts/fetch_solana_corpus.py`) and provides pattern priors the
 * bot's decision stack can consult when it sees live candidates whose
 * shape resembles a labelled historical winner (MEGA_PUMP, LONG_RUNNER,
 * POST_LAUNCH_RECOVERY, STEADY_GRIND, etc.).
 *
 * Design principles:
 *   • Fail-open: any load error leaves the matcher inert. The bot's
 *     existing PatternMemory / AutoTuner still run unchanged.
 *   • Zero cost when unused: corpus is loaded once on background IO,
 *     kept in-memory (typical footprint < 2 MB for 500 tokens).
 *   • Advisory-only: matches are exposed as `historical_prior:<TAG>`
 *     signals — never a hard block or forced buy.
 *
 * Wire-up:
 *   • BotService.onCreate calls `HistoricalPatternMatcher.warm(ctx)` once.
 *   • Any consumer calls `matchLiveShape(peakGainPct, maxDrawdownPct,
 *     netReturnPct, recoveryPct, liqUsd)` to get a top-K nearest set of
 *     historical rows and their pattern tags.
 */
object HistoricalPatternMatcher {
    const val VERSION = "V5.0.6221_HISTORICAL_PATTERN_MATCHER"
    private const val ASSET_PATH = "historical_corpus.jsonl.gz"

    data class HistoricalRow(
        val mint: String,
        val symbol: String,
        val name: String,
        val peakGainPct: Double,
        val maxDrawdownPct: Double,
        val netReturnPct: Double,
        val recoveryFromLowPct: Double,
        val avgLiquidityUsd: Double,
        val avgVolume24hUsd: Double,
        val patterns: List<String>,
    )

    data class MatchResult(
        val row: HistoricalRow,
        val distance: Double,
    )

    private val loaded = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var corpus: List<HistoricalRow> = emptyList()
    @Volatile private var patternIndex: Map<String, List<HistoricalRow>> = emptyMap()
    @Volatile private var loadStats: String = "not_loaded"

    fun size(): Int = corpus.size
    fun stats(): String = loadStats
    fun isReady(): Boolean = loaded.get()
    fun patternsAvailable(): Set<String> = patternIndex.keys

    /**
     * Kick off async corpus load. Idempotent — safe to call multiple times.
     * Boot from BotService.onCreate; the matcher is inert until this
     * completes but will never block the caller.
     */
    fun warm(ctx: Context) {
        if (loaded.get() || !loading.compareAndSet(false, true)) return
        scope.launch {
            try {
                // V5.0.6236 — original 6233 filter was too aggressive for a
                // real memecoin corpus. Rugs frequently hit ≥99.9% drawdown,
                // and true moonshots exceed +5000% net. The 6235 field
                // reports showed HistoricalCorpus rows=0 in the pipeline
                // dump — the filter was eating the entire corpus. Loosen
                // bounds so real-world tails survive; still drop the
                // pathological synthetic blowups (e.g. 522155% peakGain)
                // that motivated the original 6233 filter.
                val rows = loadCorpus(ctx.applicationContext).filter {
                    kotlin.math.abs(it.netReturnPct) <= 20_000.0 &&
                        it.peakGainPct <= 50_000.0 &&
                        it.maxDrawdownPct <= 100.0
                }
                corpus = rows
                patternIndex = rows.flatMap { r -> r.patterns.map { it to r } }
                    .groupBy({ it.first }, { it.second })
                loaded.set(true)
                val tagSummary = patternIndex.entries
                    .sortedByDescending { it.value.size }
                    .take(6)
                    .joinToString(",") { "${it.key}=${it.value.size}" }
                loadStats = "loaded rows=${rows.size} patterns=${patternIndex.size} top=[$tagSummary]"
                try {
                    ForensicLogger.lifecycle(
                        "HISTORICAL_CORPUS_LOADED_6221",
                        "rows=${rows.size} patterns=${patternIndex.size} top=$tagSummary"
                    )
                    PipelineHealthCollector.labelInc("HISTORICAL_CORPUS_LOADED_6221")
                } catch (_: Throwable) {}
            } catch (t: Throwable) {
                loadStats = "load_failed: ${t.message?.take(120)}"
                try {
                    ErrorLogger.warn("HistoricalPatternMatcher", "corpus load failed: ${t.message}")
                } catch (_: Throwable) {}
            } finally {
                loading.set(false)
            }
        }
    }

    private fun loadCorpus(ctx: Context): List<HistoricalRow> {
        val rows = ArrayList<HistoricalRow>(600)
        val am = ctx.assets
        val exists = try { am.list("")?.contains(ASSET_PATH) == true } catch (_: Throwable) { false }
        if (!exists) return emptyList()
        am.open(ASSET_PATH).use { rawIn ->
            GZIPInputStream(rawIn).use { gz ->
                BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).use { br ->
                    var line = br.readLine()
                    while (line != null) {
                        try {
                            rows.add(parseRow(line))
                        } catch (_: Throwable) {
                            // skip malformed row, keep loading
                        }
                        line = br.readLine()
                    }
                }
            }
        }
        return rows
    }

    private fun parseRow(line: String): HistoricalRow {
        val j = JSONObject(line)
        val f = j.optJSONObject("features") ?: JSONObject()
        val patternsArr = j.optJSONArray("patterns")
        val patterns = if (patternsArr == null) emptyList<String>() else
            (0 until patternsArr.length()).mapNotNull { patternsArr.optString(it, null) }
        return HistoricalRow(
            mint = j.optString("mint"),
            symbol = j.optString("symbol"),
            name = j.optString("name"),
            peakGainPct = f.optDouble("peakGainPct", 0.0),
            maxDrawdownPct = f.optDouble("maxDrawdownPct", 0.0),
            netReturnPct = f.optDouble("netReturnPct", 0.0),
            recoveryFromLowPct = f.optDouble("recoveryFromLowPct", 0.0),
            avgLiquidityUsd = f.optDouble("avgLiquidityUsd", 0.0),
            avgVolume24hUsd = f.optDouble("avgVolume24hUsd", 0.0),
            patterns = patterns,
        )
    }

    /**
     * Find the K historical rows whose recent-shape features most closely
     * match the given live snapshot. Distance is a weighted L2 across the
     * scale-normalised feature axes (percent-based) — cheap and
     * order-invariant. Returns empty list if corpus isn't loaded yet.
     */
    fun matchLiveShape(
        peakGainPct: Double,
        maxDrawdownPct: Double,
        netReturnPct: Double,
        recoveryFromLowPct: Double,
        k: Int = 5,
    ): List<MatchResult> {
        if (!loaded.get() || corpus.isEmpty()) return emptyList()
        val out = ArrayList<MatchResult>(corpus.size)
        for (r in corpus) {
            val d1 = (peakGainPct - r.peakGainPct) / 100.0
            val d2 = (maxDrawdownPct - r.maxDrawdownPct) / 100.0
            val d3 = (netReturnPct - r.netReturnPct) / 100.0
            val d4 = (recoveryFromLowPct - r.recoveryFromLowPct) / 100.0
            val d = kotlin.math.sqrt(d1 * d1 + d2 * d2 + d3 * d3 + d4 * d4)
            out.add(MatchResult(r, d))
        }
        out.sortBy { it.distance }
        return if (out.size <= k) out else out.subList(0, k)
    }

    /**
     * Return the aggregate pattern-tag prior for the K nearest neighbours.
     * The returned map is `pattern -> support ratio` (0..1) — e.g. if 4 of
     * the 5 nearest historical shapes were labelled MEGA_PUMP, the map
     * contains {"MEGA_PUMP" -> 0.8}. Consumers (FDG, PatternMemory) can
     * multiply this into their own score as a soft prior boost.
     */
    fun neighbourPatternPrior(
        peakGainPct: Double,
        maxDrawdownPct: Double,
        netReturnPct: Double,
        recoveryFromLowPct: Double,
        k: Int = 5,
    ): Map<String, Double> {
        val nn = matchLiveShape(peakGainPct, maxDrawdownPct, netReturnPct, recoveryFromLowPct, k)
        if (nn.isEmpty()) return emptyMap()
        val counts = HashMap<String, Int>()
        for (m in nn) for (p in m.row.patterns) counts.merge(p, 1) { a, _ -> a + 1 }
        val denom = nn.size.toDouble()
        return counts.mapValues { it.value / denom }
    }

    /**
     * Diagnostic: quick access to rows tagged with a specific pattern.
     * Bot's Strategy Hypothesis Engine can use these as "aspirational
     * shapes" to A/B test against live conditions.
     */
    fun rowsWithPattern(tag: String): List<HistoricalRow> =
        patternIndex[tag].orEmpty()

    // V5.0.6233 — HISTORICAL PRIOR → ENTRY MULTIPLIER.
    // Operator directive: "use the historical data we added last night ...
    // there is no reason the bot should be trading like this". The corpus
    // was loaded but consumed by NOBODY. This is the single actuation
    // surface: FDG feeds the live token's shape, gets back a bounded soft
    // multiplier (0.80..1.15) from the K-nearest historical patterns.
    private val BULL_TAGS_6233 = setOf(
        "MEGA_PUMP", "STRONG_PUMP", "POST_LAUNCH_RECOVERY", "ACCUMULATION_BASE",
        "STEADY_GRIND", "LONG_RUNNER_HIGH_VOLUME", "LARGE_CAP", "MEGA_LARGE_CAP",
    )
    private val BEAR_TAGS_6233 = setOf(
        "HEAVY_DUMP", "BLEEDER", "DEAD_CAT_BOUNCE", "DEAD_LAUNCH",
    )

    /**
     * Bounded entry prior from historical nearest-neighbour patterns.
     * Returns (multiplier 0.80..1.15, human-readable tag summary).
     * Neutral (1.0, "no_corpus") when the corpus isn't loaded.
     */
    fun entryPriorMult(
        peakGainPct: Double,
        maxDrawdownPct: Double,
        netReturnPct: Double,
        recoveryFromLowPct: Double,
        k: Int = 7,
    ): Pair<Double, String> {
        val prior = neighbourPatternPrior(peakGainPct, maxDrawdownPct, netReturnPct, recoveryFromLowPct, k)
        if (prior.isEmpty()) return 1.0 to "no_corpus"
        val bull = prior.filterKeys { it in BULL_TAGS_6233 }.values.sum()
        val bear = prior.filterKeys { it in BEAR_TAGS_6233 }.values.sum()
        val net = (bull - bear).coerceIn(-1.0, 1.0)
        val mult = (1.0 + net * 0.15).coerceIn(0.80, 1.15)
        val top = prior.entries.sortedByDescending { it.value }.take(2)
            .joinToString(",") { "${it.key}=${"%.2f".format(it.value)}" }
        return mult to top
    }

    fun statusLine(): String {
        val state = if (loaded.get()) loadStats else "not_loaded"
        return "$VERSION rows=${corpus.size} patterns=${patternIndex.size} $state"
    }
}
