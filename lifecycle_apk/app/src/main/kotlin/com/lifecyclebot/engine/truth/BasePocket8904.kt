package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8904 §THE_BASE_IS_WHERE_IT_PAYS — owner, 21 Nov 06:05: "do it already. we were meant to have fixed the issues behind
 * those gates as well!!!", then 14:07: "do the suggested work and audit the data".
 *
 * The owner's hour reads (ResearchRows8877, 20-21 Nov, 26.6 h, one read per coin per hour, executable net after cost), by the
 * stage the coin was in when the bot decided: FRESH_LAUNCH n59 -20.8 %, DUMPING n259 -28.4 %, PEAK_EXHAUSTION n158 -14.0 %,
 * MID_ACCUMULATION n47 -5.0 %, CONTROLLED_MARKUP n65 -9.9 %, the rest n1201 -6.8 %; BASE_START n164 +4.0 %. Inside the base,
 * coins NOT minted on pump.fun with a $20k-$100k pool: 51 coins, +18.3 % mean, median +14.6 %, 68 % winners at the hour
 * (pump.fun mints in the same band: n21 -14.7 %). At four hours the base as a whole gives it back (n41 -38.3 %).
 *
 * One record for that pocket, at the hour, each coin once at its WORST meme-lane hour read (AgreementPocket8874's 8895 coin
 * record): on the same rows n49, +13.3 % +- 8.6 (gain kept to the pocket cap), positive in both halves (+6.6 / +19.9) — NOT yet
 * proven at two standard errors (lower bound -3.9 %). It is filed from every hour research row as it is written and seeded
 * from the device's own research file, so it proves (or fails) on the coins that follow. A coin in the pocket carries it as
 * market-cell evidence only once it is PROVEN (PayingCell8723.paying8723: 30+ coins, mean above zero by two standard errors),
 * with AgreementPocket8874's precedence: it answers the lane-wide priors and the aggregate forecast (a specific record, 8843);
 * hard safety, structure, sellability, the LP-lock rule, executable economics, the coin's own chart shapes and the losing
 * record of the setup it trades as all still outrank it. The record keeps the newest [WINDOW_8904] coins, so a pocket that
 * stops paying stops admitting. The pocket is the market's, not one lane's: the lane's own sizing and exits apply.
 */
object BasePocket8904 {
    const val KIND_8904 = "BASE_POCKET"
    private const val ID_8904 = "BASE_OTHER_20K_100K"
    internal const val STAGE_8904 = "BASE_START"
    internal const val LIQ_LO_USD_8904 = 20_000.0
    internal const val LIQ_HI_USD_8904 = 100_000.0
    internal const val HORIZON_MS_8904 = 3_600_000L
    internal const val WINDOW_8904 = 200
    private const val REVISE_MS_8904 = 15L * 60_000L
    private const val PROOF_SE = 2.0

    private class Coin(val firstMs: Long, var worstNet: Double)
    private val coins = LinkedHashMap<String, Coin>()   // guarded by itself; oldest first
    @Volatile private var ready = false
    @Volatile private var loading = false
    private val learned = AtomicLong(0L)
    private val served = AtomicLong(0L)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "basePocket8904").apply { isDaemon = true } }

    /** Pure: is a coin minted at [mint], in [stage], with a pool of [liqUsd], in the pocket? */
    fun inPocket8904(mint: String, stage: String, liqUsd: Double): Boolean =
        com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(mint) && MarketCohort8701.origin8701(mint) == "OTHER" &&
            stage.trim().equals(STAGE_8904, ignoreCase = true) && liqUsd.isFinite() && liqUsd >= LIQ_LO_USD_8904 && liqUsd < LIQ_HI_USD_8904

    /** V5.0.8904 — how long a coin that could base into the pocket stays on the hot watchlist (both stale demotions skip it). */
    internal const val BASE_WATCH_MS_8904 = 6L * 3_600_000L

    /** Pure: should the watchlist keep a coin minted at [mint] with a pool of [liqUsd], on it for [onWatchMs], hot? */
    fun watches8904(mint: String, liqUsd: Double, onWatchMs: Long): Boolean =
        onWatchMs in 0L until BASE_WATCH_MS_8904 && com.lifecyclebot.network.HeliusSolanaScope7819.isSolanaMint7819(mint) &&
            MarketCohort8701.origin8701(mint) == "OTHER" && liqUsd.isFinite() && liqUsd >= LIQ_LO_USD_8904 && liqUsd < LIQ_HI_USD_8904

    /** One hour read of a meme lane; a coin's reads within [REVISE_MS_8904] of its first keep the worst. */
    private fun add(mint: String, lane: String, atMs: Long, net: Double) {
        if (!AgreementPocket8874.memeLane8895(lane) || !net.isFinite()) return
        synchronized(coins) {
            val c = coins[mint]
            if (c == null) {
                coins[mint] = Coin(atMs, net)
                while (coins.size > WINDOW_8904) coins.remove(coins.keys.first())
                learned.incrementAndGet()
            } else if (kotlin.math.abs(atMs - c.firstMs) < REVISE_MS_8904 && net < c.worstNet) c.worstNet = net
        }
    }

    /** Pure: the pocket's read of one research row (ResearchRows8877.line8877), or null when the row is not one of its coins. */
    internal fun rowOf8904(o: JSONObject): Triple<String, String, Pair<Long, Double>>? {
        if (o.optLong("horizonMs", 0L) != HORIZON_MS_8904) return null
        val net = if (o.isNull("net")) Double.NaN else o.optDouble("net", Double.NaN)
        val liq = if (o.isNull("liq")) Double.NaN else o.optDouble("liq", Double.NaN)
        val mint = o.optString("mint")
        if (!net.isFinite() || !inPocket8904(mint, o.optString("stage"), liq)) return null
        return Triple(mint, o.optString("lane"), o.optLong("t", 0L) to net)
    }

    /** ResearchRows8877.note8877: every hour row as it is written (cheap text test first; only base rows are parsed). */
    fun learnLine8904(line: String) {
        if (!line.contains("\"horizonMs\":$HORIZON_MS_8904") || !line.contains("\"stage\":\"$STAGE_8904\"")) return
        ensureLoaded()
        try { rowOf8904(JSONObject(line))?.let { (mint, lane, tn) -> add(mint, lane, tn.first, tn.second) } } catch (_: Throwable) {}
    }

    /** (n, sum, sum of squares, runners) of the record: each coin at its worst meme-lane hour read, gain kept to the cap. */
    internal fun record8904(): DoubleArray = synchronized(coins) {
        var n = 0.0; var sum = 0.0; var sq = 0.0; var ran = 0.0
        for (c in coins.values) {
            val y = PayingCell8723.outcome8723(c.worstNet)
            n += 1.0; sum += y; sq += y * y; if (c.worstNet >= 100.0) ran += 1.0
        }
        doubleArrayOf(n, sum, sq, ran)
    }

    private fun se(r: DoubleArray): Double {
        if (r[0] < 2.0) return Double.NaN
        val m = r[1] / r[0]
        return kotlin.math.sqrt(((r[2] / r[0]) - m * m).coerceAtLeast(0.0) / r[0])
    }

    /** Pure: the record [r] as [lane]'s evidence; null unless [lane] is a meme lane and the record is proven. */
    internal fun evidenceOf8904(lane: String, r: DoubleArray): CandidateAdmission8136.Evidence8136? {
        if (!AgreementPocket8874.memeLane8895(lane) || !PayingCell8723.paying8723(r[0], r[1], r[2])) return null
        return CandidateAdmission8136.Evidence8136(KIND_8904, ID_8904, r[0].toInt(), r[1] / r[0], PROOF_SE * se(r), runnerRate = r[3] / r[0])
    }

    /** CandidateAdmission8136: the pocket's proven record when [ts] is in it now (same stage read and pool as the hour rows). */
    fun evidence8904(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): CandidateAdmission8136.Evidence8136? = try {
        ensureLoaded()
        if (!ready) null else {
            val stage = try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts).stage.name } catch (_: Throwable) { "" }
            if (!inPocket8904(ts.mint, stage, ts.lastLiquidityUsd)) null
            else evidenceOf8904(lane, record8904())?.also {
                served.incrementAndGet()
                try { PipelineHealthCollector.labelInc("BASE_POCKET_8904_EVIDENCE_${lane.uppercase()}") } catch (_: Throwable) {}
            }
        }
    } catch (_: Throwable) { null }

    /** Seed once from the device's research file, oldest first (streamed). */
    private fun ensureLoaded() {
        if (ready || loading) return
        synchronized(this) { if (ready || loading) return; loading = true }
        io.execute {
            try {
                ResearchRows8877.file8877()?.takeIf { it.exists() }?.useLines { lines ->
                    lines.forEach { l ->
                        if (l.contains("\"stage\":\"$STAGE_8904\"") && l.contains("\"horizonMs\":$HORIZON_MS_8904"))
                            try { rowOf8904(JSONObject(l))?.let { (mint, lane, tn) -> add(mint, lane, tn.first, tn.second) } } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) { try { PipelineHealthCollector.labelInc("BASE_POCKET_8904_LOAD_FAILED") } catch (_: Throwable) {} }
            finally { ready = true; loading = false }
        }
    }

    /** Brief: the record, whether it is proven, and how often it served as evidence. */
    fun statusLine8904(): String {
        val r = record8904()
        val rec = if (r[0] < 1.0) "n0" else "n${r[0].toInt()}/${"%+.1f".format(r[1] / r[0])}%±${"%.1f".format(se(r))}" +
            "/lower${"%+.1f".format(r[1] / r[0] - PROOF_SE * se(r))}%/run${(100.0 * r[3] / r[0]).toInt()}%"
        val proven = PayingCell8723.paying8723(r[0], r[1], r[2])
        return "basePocket8904[${if (proven) "PROVEN" else "UNPROVEN"} $rec loaded=$ready learned=${learned.get()} served=${served.get()} " +
            "rule=not pump.fun, stage BASE_START, pool \$20k-\$100k; each coin at its worst meme-lane HOUR read; admits once 30+ coins and mean-2SE>0]"
    }

    internal fun resetForTest8904() { synchronized(coins) { coins.clear() }; learned.set(0L); served.set(0L); ready = true }
    internal fun addForTest8904(lane: String, mint: String, net: Double, atMs: Long) = add(mint, lane, atMs, net)
}
