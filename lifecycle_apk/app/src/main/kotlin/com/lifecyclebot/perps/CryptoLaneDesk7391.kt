package com.lifecyclebot.perps

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.LaneEntryContract6342
import com.lifecyclebot.engine.ModeRouter
import com.lifecyclebot.engine.MovementPatternSignal
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.ToolkitSignalSheet
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7391 — crypto trades through the meme desk.
 *
 * Operator: "crypto needs the same lanes, trading tools, signals, specialists as
 * the meme trader." Crypto already called five meme specialists, but with an
 * untrusted market cap zeroed, no candles and no lane election, so almost every
 * token fell through as CRYPTO_BRAIN_NO_ACTIONABLE_SIGNAL_7244.
 *
 * This desk presents a crypto token to the meme machinery as a TokenState:
 *  - a per-identity price series recorded at scan time (one candle per >= 20 s),
 *    marked synthetic because it carries no per-candle volume, so the pattern
 *    tools read price structure and keep volume patterns neutral (as 5.0.7389
 *    does for memes);
 *  - ModeRouter.classify, ToolkitSignalSheet.build (desk hypotheses per lane) and
 *    MovementPatternSignal.from, exactly as the meme cycle reads them;
 *  - the meme election rule: lane identity eligibility, the strongest desk owns
 *    the token, and CORE (the ensemble lane) owns it when two or more lanes rate
 *    it >= 45 and none clearly leads (a lane at >= 75 keeps it).
 *
 * The elected lane rides on the signal as a "LANE7391=<lane>" reason, which the
 * position keeps, so exits and learning read the same lane.
 */
object CryptoLaneDesk7391 {

    /** Lanes a crypto asset can be elected to. Launch-only and retired buyers are excluded. */
    private val CRYPTO_DESK_LANES = setOf(
        "CORE", "EXPRESS", "DIP_HUNTER", "TREASURY", "CASHGEN", "QUALITY", "BLUECHIP", "SHITCOIN", "MOONSHOT",
    )
    const val LANE_REASON_PREFIX = "LANE7391="

    data class Election(
        val lane: String,
        val conviction: Double,
        val setup: String,
        val movementPattern: String,
        val voters: Int,
    )

    private const val MAX_CANDLES = 120
    private const val MIN_CANDLE_SPACING_MS = 20_000L
    private const val MAX_TRACKED = 6_000
    private val series = ConcurrentHashMap<String, ArrayDeque<Candle>>()

    /** Record one scan-time observation of [identity]; at most one candle per 20 s. */
    fun recordTick(identity: String, priceUsd: Double, marketCapUsd: Double, volume24h: Double, buys24h: Int, sells24h: Int) {
        if (identity.isBlank() || !priceUsd.isFinite() || priceUsd <= 0.0) return
        if (series.size > MAX_TRACKED) series.clear()
        val q = series.getOrPut(identity) { ArrayDeque(MAX_CANDLES) }
        val now = System.currentTimeMillis()
        synchronized(q) {
            val last = q.lastOrNull()
            if (last != null && now - last.ts < MIN_CANDLE_SPACING_MS) {
                // Same bucket: widen its high/low with this observation.
                q.removeLast()
                q.addLast(last.copy(
                    priceUsd = priceUsd,
                    highUsd = maxOf(last.highUsd, priceUsd),
                    lowUsd = if (last.lowUsd > 0.0) minOf(last.lowUsd, priceUsd) else priceUsd,
                ))
                return
            }
            q.addLast(Candle(
                ts = now, priceUsd = priceUsd, marketCap = marketCapUsd.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                volumeH1 = 0.0, volume24h = volume24h.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                buys24h = buys24h, sells24h = sells24h,
                highUsd = priceUsd, lowUsd = priceUsd, openUsd = priceUsd,
                synthetic = true,
            ))
            while (q.size > MAX_CANDLES) q.removeFirst()
        }
    }

    /** Recent price series for [identity] (oldest first). */
    fun prices(identity: String): List<Double> =
        series[identity]?.let { q -> synchronized(q) { q.map { it.priceUsd } } } ?: emptyList()

    /** The crypto token as the meme desk reads a token. */
    fun tokenState(
        identity: String, symbol: String, name: String, priceUsd: Double, marketCapUsd: Double,
        liquidityUsd: Double, buyPressurePct: Double, source: String, ageHours: Double,
        brainScore: Int, brainConfidence: Int,
    ): TokenState {
        val ts = TokenState(mint = identity, symbol = symbol, name = name)
        ts.lastPrice = priceUsd
        ts.lastPriceSource = "CRYPTO_ALT_DESK_7391"
        ts.lastMcap = marketCapUsd.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        ts.lastLiquidityUsd = liquidityUsd.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        ts.lastBuyPressurePct = buyPressurePct.takeIf { it.isFinite() } ?: 50.0
        ts.source = source
        // CryptoBrain is this asset's scorer, as V3 is the meme scorer.
        ts.entryScore = brainScore.toDouble().coerceIn(0.0, 100.0)
        ts.lastV3Score = brainScore.coerceIn(0, 100)
        ts.lastV3Confidence = brainConfidence.coerceIn(0, 100)
        if (ageHours.isFinite() && ageHours in 0.0..9_000.0) {
            ts.addedToWatchlistAt = System.currentTimeMillis() - (ageHours * 3_600_000.0).toLong()
        }
        series[identity]?.let { q -> synchronized(q) { ts.history.addAll(q) } }
        return ts
    }

    /** Run the meme desk on [ts] and elect its owner lane ("" when no lane qualifies). */
    fun elect(ts: TokenState): Election {
        return try {
            val cls = ModeRouter.classify(ts)
            val sheet = ToolkitSignalSheet.build(ts, cls)
            val move = try { MovementPatternSignal.from(ts).pattern } catch (_: Throwable) { "" }
            val ranked = sheet.deskHypotheses.values
                .filter { it.lane.uppercase() in CRYPTO_DESK_LANES }
                .map { if (it.lane.equals("CASHGEN", true)) it.copy(lane = "TREASURY") else it }
                .filter { LaneEntryContract6342.isLaneIdentityEligible7252(ts, it.lane) }
                .filter { !it.lane.equals("CORE", true) }
                .sortedByDescending { it.conviction }
                .distinctBy { it.lane.uppercase() }
            val strongest = ranked.firstOrNull()
            val second = ranked.getOrNull(1)
            val voters = ranked.filter { it.conviction >= 45.0 }
            val coreFit = strongest != null && second != null && voters.size >= 2 &&
                strongest.conviction < 75.0 &&
                (strongest.conviction - second.conviction <= 10.0 || strongest.conviction < 65.0) &&
                LaneEntryContract6342.isLaneIdentityEligible7252(ts, "CORE")
            val election = when {
                coreFit -> Election("CORE", voters.map { it.conviction }.average(), strongest!!.setup.name, move, voters.size)
                strongest != null -> Election(strongest.lane.uppercase(), strongest.conviction, strongest.setup.name, move, voters.size)
                else -> Election("", 0.0, sheet.setup.name, move, 0)
            }
            try { PipelineHealthCollector.labelInc("CRYPTO_DESK_ELECTED_7391_${election.lane.ifBlank { "NONE" }}") } catch (_: Throwable) {}
            election
        } catch (_: Throwable) {
            Election("", 0.0, "", "", 0)
        }
    }

    /** Desk lane stamped on a signal/position, or "" when none. */
    fun laneFromReasons(reasons: List<String>): String =
        reasons.firstOrNull { it.startsWith(LANE_REASON_PREFIX) }
            ?.removePrefix(LANE_REASON_PREFIX)?.substringBefore(' ')?.trim().orEmpty()

    /**
     * Meme-style bounce confirmation on the recorded series: the lowest of the last
     * eight prices is not one of the last two, price is >= 2% off it, buyers lead.
     */
    fun bounceConfirmed(identity: String, buyPressurePct: Double): Boolean {
        val px = prices(identity).takeLast(8)
        if (px.size < 4) return false
        val lowIdx = px.indices.minByOrNull { px[it] } ?: return false
        return lowIdx <= px.size - 3 && px.last() >= px[lowIdx] * 1.02 && buyPressurePct >= 50.0
    }
}
