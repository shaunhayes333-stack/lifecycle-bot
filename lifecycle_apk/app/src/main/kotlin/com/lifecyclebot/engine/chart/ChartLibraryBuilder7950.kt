package com.lifecyclebot.engine.chart

import android.content.Context
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7950 — builds the chart library in the app, from real charts.
 *
 *  - Top ~200 crypto by 24h quote volume (Binance public market data,
 *    data-api.binance.vision): 1m / 5m / 15m / 1h / 4h / 1d candles with the
 *    taker-buy split.
 *  - Solana memes (GeckoTerminal): PumpSwap and pump.fun curve pools, trending
 *    and top pools, plus the famous pump.fun runners by name; BSC trending and
 *    top pools: 1m / 5m / 15m / 1h / 1d candles.
 *
 * Runs on one low-priority background thread, paced well under each provider's
 * free limit (GeckoTerminal one call per 10 s, backing off on 429),
 * resumes where it stopped after a restart, and rebuilds weekly. Each series
 * contributes at most 40 evenly spread fingerprints, and the
 * library keeps a uniform sample of everything seen. The library file lives in
 * the app's files directory and is loaded at start.
 *
 * V5.0.7955 — the download runs through ChartSources7955: every free market-data
 * source (exchanges, aggregators, DEX discovery, keyed free tiers), each paced and
 * backed off on its own, interleaved, resumable through the same done-set.
 */
object ChartLibraryBuilder7950 {
    private const val FILE = "chart_library_7950.bin"
    private const val PREFS = "chart_library_builder_7950"
    private const val REBUILD_MS = 7L * 24 * 60 * 60_000L

    private val running = AtomicBoolean(false)
    @Volatile private var phase = "idle"
    @Volatile private var file: File? = null

    /** BotService start: load the library, then build / refresh it in the background. */
    fun start(context: Context) {
        val app = context.applicationContext
        val f = File(app.filesDir, FILE)
        file = f
        if (!running.compareAndSet(false, true)) return
        startLiveLearner7954(f)
        // V5.0.7955 — provider keys + live backfill of short tapes (held and candidates).
        try { ChartSources7955.start7955(app) } catch (_: Throwable) {}
        Thread({
            try {
                phase = "loading"
                val n = ChartLibrary7950.load(f)
                try { ForensicLogger.lifecycle("CHART_LIBRARY_LOADED_7950", "motifs=$n") } catch (_: Throwable) {}
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val last = prefs.getLong("builtAtMs", 0L)
                var pending = System.currentTimeMillis() - last > REBUILD_MS || n < ChartLibrary7950.CAPACITY / 4
                if (pending) pending = !buildAndMark7955(prefs)
                phase = if (pending) "live(resume-pending)" else "live"
                // Live motifs keep arriving through ChartReader7950; save them periodically.
                // V5.0.7954 — live learning and saving run on their own thread (startLiveLearner7954).
                // V5.0.7955 — a build cut short by resting providers resumes every 3 h (done-set kept).
                while (true) {
                    Thread.sleep(3L * 60L * 60_000L)
                    if (pending) {
                        pending = !buildAndMark7955(prefs)
                        phase = if (pending) "live(resume-pending)" else "live"
                    }
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                phase = "error:${t.javaClass.simpleName}"
            } finally {
                running.set(false)
            }
        }, "chart-library-7950").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** V5.0.7955 — build; when every source drained, stamp the build and clear the done-set. */
    private fun buildAndMark7955(prefs: android.content.SharedPreferences): Boolean {
        val complete = build(prefs)
        if (complete) prefs.edit().putLong("builtAtMs", System.currentTimeMillis()).remove("done").apply()
        return complete
    }

    private fun build(prefs: android.content.SharedPreferences): Boolean {
        phase = "building"
        val done = HashSet(prefs.getStringSet("done", emptySet()) ?: emptySet())
        // V5.0.7955 — every free source, interleaved and paced per provider (ChartSources7955).
        val complete = ChartSources7955.build7955(
            done,
            checkpoint = {
                file?.let { ChartLibrary7950.save(it) }
                prefs.edit().putStringSet("done", HashSet(done)).apply()
            },
            tick = { if (System.currentTimeMillis() - lastLiveLearnMs7953 >= LIVE_LEARN_MS_7953) learnLive7953() },
        )
        file?.let { ChartLibrary7950.save(it) }
        prefs.edit().putStringSet("done", HashSet(done)).apply()
        try {
            PipelineHealthCollector.labelInc(if (complete) "CHART_LIBRARY_BUILT_7950" else "CHART_LIBRARY_BUILD_RESUME_7955")
            ForensicLogger.lifecycle("CHART_LIBRARY_BUILT_7950", "complete=$complete done=${done.size} ${ChartLibrary7950.statusLine()}")
        } catch (_: Throwable) {}
        return complete
    }

    /**
     * V5.0.7953 — every 5 minutes, all live tapes (every chart the bot is watching,
     * hundreds of memes) are fingerprinted into the library, not only the few read by
     * a buy check: 5.0.7951 had 627 live tapes and 19 live motifs.
     */
    private const val LIVE_LEARN_MS_7953 = 5L * 60_000L
    @Volatile private var lastLiveLearnMs7953 = 0L

    private val liveLearned7954 = AtomicLong(0)

    /**
     * V5.0.7954 — live charts are learned on their own thread from the first minutes,
     * independent of the download: 5.0.7953 learned nothing live for 24 minutes because
     * live learning only ran inside the download loop, which was still listing pools.
     */
    private fun startLiveLearner7954(f: File) {
        Thread({
            var ticks = 0
            try {
                Thread.sleep(90_000L)
                while (true) {
                    lastLiveLearnMs7953 = System.currentTimeMillis()
                    val n = try { ChartReader7950.learnAll7953() } catch (_: Throwable) { 0 }
                    liveLearned7954.addAndGet(n.toLong())
                    if (++ticks % 2 == 0) ChartLibrary7950.save(f)
                    Thread.sleep(LIVE_LEARN_MS_7954)
                }
            } catch (_: InterruptedException) {
            } catch (_: Throwable) {}
        }, "chart-live-learner-7954").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
    private const val LIVE_LEARN_MS_7954 = 3L * 60_000L

    private fun learnLive7953() {
        lastLiveLearnMs7953 = System.currentTimeMillis()
        try { ChartReader7950.learnAll7953() } catch (_: Throwable) {}
    }

    /** Pure: alternate [first] and [second] (first leads), keeping the rest of the longer list. */
    fun <T> interleave7953(first: List<T>, second: List<T>): List<T> {
        val out = ArrayList<T>(first.size + second.size)
        val n = maxOf(first.size, second.size)
        for (i in 0 until n) {
            if (i < first.size) out += first[i]
            if (i < second.size) out += second[i]
        }
        return out
    }

    // ── parse ──

    /** Pure: Binance klines -> candles (taker-buy volume is the buy split). */
    fun parseBinance(arr: JSONArray): List<Bar7950> {
        val out = ArrayList<Bar7950>(arr.length())
        for (i in 0 until arr.length()) {
            val k = arr.optJSONArray(i) ?: continue
            val o = k.optString(1).toDoubleOrNull() ?: continue
            val h = k.optString(2).toDoubleOrNull() ?: continue
            val l = k.optString(3).toDoubleOrNull() ?: continue
            val c = k.optString(4).toDoubleOrNull() ?: continue
            val v = k.optString(5).toDoubleOrNull() ?: 0.0
            val bv = k.optString(9).toDoubleOrNull() ?: Double.NaN
            out += Bar7950(k.optLong(0), o, h, l, c, v, bv)
        }
        return out
    }

    /** Pure: GeckoTerminal ohlcv_list (newest first) -> candles oldest first. */
    fun parseGecko(root: JSONObject): List<Bar7950> {
        val list = root.optJSONObject("data")?.optJSONObject("attributes")?.optJSONArray("ohlcv_list") ?: return emptyList()
        val out = ArrayList<Bar7950>(list.length())
        for (i in list.length() - 1 downTo 0) {
            val k = list.optJSONArray(i) ?: continue
            if (k.length() < 6) continue
            val c = k.optDouble(4)
            if (!(c > 0.0)) continue
            out += Bar7950(k.optLong(0) * 1000L, k.optDouble(1), k.optDouble(2), k.optDouble(3), c, k.optDouble(5).takeIf { it.isFinite() } ?: 0.0)
        }
        return out
    }

    fun statusLine(): String =
        "phase=$phase ${ChartSources7955.summary7955()} liveLearned7954=${liveLearned7954.get()}"
}
