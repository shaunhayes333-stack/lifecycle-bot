package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8788 §CRYPTO_SPOT_LIVE_FOLLOWS_ITS_OWN_PAPER_RECORD — owner, Tuning console: "crypto spot shows high expectation but
 * 0 live trades". The CRYPTO_SPOT paper book read +4.075 SOL (n169, W19/L13/s137, 59 % win rate) while the 8756 live crypto
 * funnel blocked 99 of 99 candidates at the canonical contract; 11 of them as LIVE_NON_POSITIVE_EXPECTANCY_7828, an expected
 * value taken from the meme-built oracle / forward model / formula fallback — never from this lane's own record. The owner
 * chose (as for the MOONSHOT mirror, 8757): a LIVE CRYPTO_SPOT candidate that expectancy refusal would stop is admitted when
 * the lane's own paper record backs it ([paperBacks8788]: 20+ decisive closes, a positive mean, positive SOL and a positive
 * profit-factor edge).
 *
 * Everything else stays binding and runs before this point in CanonicalAssetEntryContract6551: hard safety, the live route,
 * the crypto toggle, the wallet lock / routable floor, the oracle's hard-safety verdict. Bounds: at most [MAX_PER_HOUR_8788]
 * backed admits an hour, and an automatic stop once live CRYPTO_SPOT closes since the first backed admit realise
 * -[LOSS_STOP_SOL_8788] SOL. The window and the trip are persisted (read at boot by [load8788]).
 *
 * Note (StrategyTelemetry 3974): paper was declared hypothesis-only for live expectancy; this is the owner's explicit,
 * bounded exception for one lane.
 */
object CryptoSpotPaperBacking8788 {
    const val LANE_8788 = "CRYPTO_SPOT"
    const val REASON_8788 = "CRYPTO_SPOT_PAPER_BACKED_8788"
    const val MIN_DECISIVE_8788 = 20
    const val LOSS_STOP_SOL_8788 = 0.1
    const val MAX_PER_HOUR_8788 = 4
    @Volatile var enabled8788: Boolean = true

    @Volatile private var startMs8788 = 0L
    @Volatile private var tripped8788 = false
    @Volatile private var stopCheckedAtMs8788 = 0L
    @Volatile private var recordAtMs8788 = 0L
    @Volatile private var recordBacks8788 = false
    @Volatile private var recordLine8788 = "-"
    private val backed8788 = AtomicLong(0L)
    private val admitTimes8788 = java.util.ArrayDeque<Long>()
    @Volatile private var prefs8788: android.content.SharedPreferences? = null

    /** Pure: does a lane record of [wins] / [losses] decisive closes, [meanPct], [totalSol] and [pfEdgePp] back live entries? */
    fun paperBacks8788(wins: Int, losses: Int, meanPct: Double, totalSol: Double, pfEdgePp: Double): Boolean =
        wins + losses >= MIN_DECISIVE_8788 && meanPct.isFinite() && meanPct > 0.0 &&
            totalSol.isFinite() && totalSol > 0.0 && pfEdgePp.isFinite() && pfEdgePp > 0.0

    /** Pure: has [realisedSol] since the first backed admit reached the automatic stop? */
    fun lossStopHit8788(realisedSol: Double): Boolean = realisedSol.isFinite() && realisedSol <= -LOSS_STOP_SOL_8788

    /** Boot (LearningResetSweep7781.applyPendingAtBoot): read the persisted window and trip. */
    fun load8788(ctx: android.content.Context) {
        val p = try { ctx.applicationContext.getSharedPreferences("aate_crypto_spot_backing_8788", android.content.Context.MODE_PRIVATE) } catch (_: Throwable) { return }
        prefs8788 = p
        startMs8788 = maxOf(startMs8788, p.getLong("start_ms", 0L))
        tripped8788 = tripped8788 || p.getBoolean("tripped", false)
    }

    private fun persist8788() {
        try { prefs8788?.edit()?.putLong("start_ms", startMs8788)?.putBoolean("tripped", tripped8788)?.apply() } catch (_: Throwable) {}
    }

    private fun recordBacks8788(nowMs: Long): Boolean {
        if (nowMs - recordAtMs8788 < 60_000L) return recordBacks8788
        recordAtMs8788 = nowMs
        val m = try {
            com.lifecyclebot.engine.StrategyTelemetry.computeLeaderboard(environment = "paper")
                .firstOrNull { it.strategy.equals(LANE_8788, ignoreCase = true) }
        } catch (_: Throwable) { null }
        recordBacks8788 = m != null && paperBacks8788(m.wins, m.losses, m.meanPnlPct, m.totalSolPnl, m.pfExpectancyPp)
        recordLine8788 = if (m == null) "none" else
            "n${m.trades} W${m.wins}/L${m.losses} mean=${"%+.1f".format(m.meanPnlPct)}% sol=${"%+.3f".format(m.totalSolPnl)} pfEdge=${"%+.1f".format(m.pfExpectancyPp)}"
        return recordBacks8788
    }

    private fun stopped8788(nowMs: Long): Boolean {
        if (tripped8788) return true
        if (startMs8788 <= 0L || nowMs - stopCheckedAtMs8788 < 60_000L) return false
        stopCheckedAtMs8788 = nowMs
        val realised = try {
            com.lifecyclebot.engine.LiveEntrySafetyHold.LiveConfidenceStats.byLane8280(startMs8788)
                .filterKeys { it.trim().equals(LANE_8788, ignoreCase = true) }.values.sumOf { it.expectancySol * it.canonicalN }
        } catch (_: Throwable) { 0.0 }
        if (lossStopHit8788(realised)) {
            tripped8788 = true
            persist8788()
            try { PipelineHealthCollector.labelInc("CRYPTO_SPOT_PAPER_BACKING_LOSS_STOP_8788") } catch (_: Throwable) {}
        }
        return tripped8788
    }

    @Synchronized private fun rateRoom8788(nowMs: Long): Boolean {
        while (admitTimes8788.isNotEmpty() && nowMs - admitTimes8788.peekFirst() > 3_600_000L) admitTimes8788.pollFirst()
        if (admitTimes8788.size >= MAX_PER_HOUR_8788) return false
        admitTimes8788.addLast(nowMs)
        return true
    }

    /**
     * CanonicalAssetEntryContract6551 (LIVE, at the LIVE_NON_POSITIVE_EXPECTANCY_7828 refusal only): does the CRYPTO_SPOT paper
     * record back this candidate in [lane]? Consumes an hourly slot when it does.
     */
    fun backs8788(lane: String?, nowMs: Long): Boolean {
        if (!enabled8788 || !lane.equals(LANE_8788, ignoreCase = true)) return false
        if (stopped8788(nowMs) || !recordBacks8788(nowMs)) return false
        if (!rateRoom8788(nowMs)) { try { PipelineHealthCollector.labelInc("CRYPTO_SPOT_PAPER_BACKING_RATE_CAP_8788") } catch (_: Throwable) {}; return false }
        if (startMs8788 <= 0L) { startMs8788 = nowMs; persist8788() }
        backed8788.incrementAndGet()
        try { PipelineHealthCollector.labelInc("CRYPTO_SPOT_PAPER_BACKED_8788") } catch (_: Throwable) {}
        return true
    }

    fun statusLine8788(): String =
        "cryptoSpotPaperBacking8788[on=$enabled8788 backed=${backed8788.get()} record=$recordLine8788 stop=-${LOSS_STOP_SOL_8788}SOL tripped=$tripped8788 maxPerHour=$MAX_PER_HOUR_8788]"
}
