package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TokenMetricStageRouter.Stage
import com.lifecyclebot.engine.market.LaunchTape7921

/**
 * V5.0.8316 §A_STAGE_FROM_THE_PRINTS — the lifecycle stage of a young coin whose metric stage never formed.
 *
 * Owner, 5.0.8314: "are we hitting tokens early as they build, not dumping?" TokenMetricStageRouter.snapshot reads
 * UNKNOWN exactly when bp / chg / shape are missing (no pair poll yet), and judgeStage7928 returns null on UNKNOWN (no
 * stage evidence), so the cheat sheet's "peak exhaustion and dumping need a confirmed reclaim" rule could not stop a
 * buy on a coin whose stage data never arrived — the coins the launch lanes buy most. The coin's OWN executed prints
 * (TradeTape8100, one venue; else the 45-minute launch tape LaunchTape7921) say what the metrics would have said:
 *
 *  - DUMPING: at least [DUMP_OFF_PEAK_PCT_8316] off its recent peak with a sell-dominated tape over the last
 *    [FLOW_WINDOW_MS_8316] (buy share under [SELL_DOMINATED_BUY_SHARE_PCT_8316] or net SOL out).
 *  - PEAK_EXHAUSTION: extended (at least [EXHAUSTED_FROM_FIRST_PCT_8316] over its first print), off the peak but not
 *    yet a dump, and the tape has turned sell-dominated.
 *  - FRESH_LAUNCH (under 3 min) / BASE_START: early buying is building (LaunchTape7921.earlyPass8165's bars — buy share
 *    at least [BUILD_BUY_SHARE_PCT_8316], price at most [BUILD_MAX_FROM_FIRST_PCT_8316] over its first print, at most
 *    [BUILD_MAX_OFF_PEAK_PCT_8316] off its peak, net SOL in).
 *
 * Fewer than [MIN_PRINTS_8316] executed prints, no flow in the window, or a coin past [MAX_AGE_MIN_8316]: null — the
 * stage stays UNKNOWN. Nothing is fabricated: no prints, no stage. The read only feeds the stage sheet (laneFit /
 * judgeStage7928) the way a metric stage would; every other gate decides as before.
 */
object TapeStage8316 {
    private const val FLOW_WINDOW_MS_8316 = 3L * 60_000L
    private const val PEAK_WINDOW_MS_8316 = 15L * 60_000L
    const val MIN_PRINTS_8316 = 6
    private const val MIN_FLOW_PRINTS_8316 = 3
    const val MAX_AGE_MIN_8316 = 20.0
    private const val FRESH_MAX_AGE_MIN_8316 = 3.0
    private const val DUMP_OFF_PEAK_PCT_8316 = 25.0
    private const val EXHAUSTED_OFF_PEAK_PCT_8316 = 10.0
    private const val EXHAUSTED_FROM_FIRST_PCT_8316 = 70.0
    private const val SELL_DOMINATED_BUY_SHARE_PCT_8316 = 45.0
    private const val BUILD_BUY_SHARE_PCT_8316 = 60.0
    private const val BUILD_MAX_FROM_FIRST_PCT_8316 = 60.0
    private const val BUILD_MAX_OFF_PEAK_PCT_8316 = 20.0

    /** One tape-derived stage read: the stage and the prints it rests on. */
    data class Read8316(
        val stage: Stage,
        val offPeakPct: Double,
        val fromFirstPct: Double,
        val buySharePct: Double,
        val netSol: Double,
        val prints: Int,
        val source: String,
    ) {
        val compact: String get() = "stageFromTape8316=${stage.name} src=$source prints=$prints offPeak=${"%.0f".format(offPeakPct)}% " +
            "fromFirst=${"%+.0f".format(fromFirstPct)}% buyShare=${"%.0f".format(buySharePct)}% net=${"%.2f".format(netSol)}SOL"
    }

    /** Pure: the stage the shape and flow numbers name (null = no stage from them). */
    fun stageOf8316(offPeakPct: Double, fromFirstPct: Double, buySharePct: Double, netSol: Double, ageMin: Double): Stage? {
        if (!offPeakPct.isFinite() || !fromFirstPct.isFinite() || !buySharePct.isFinite() || !netSol.isFinite()) return null
        val sellDominated = buySharePct < SELL_DOMINATED_BUY_SHARE_PCT_8316 || netSol < 0.0
        return when {
            offPeakPct >= DUMP_OFF_PEAK_PCT_8316 && sellDominated -> Stage.DUMPING
            offPeakPct >= EXHAUSTED_OFF_PEAK_PCT_8316 && fromFirstPct >= EXHAUSTED_FROM_FIRST_PCT_8316 && sellDominated -> Stage.PEAK_EXHAUSTION
            buySharePct >= BUILD_BUY_SHARE_PCT_8316 && netSol > 0.0 &&
                fromFirstPct <= BUILD_MAX_FROM_FIRST_PCT_8316 && offPeakPct <= BUILD_MAX_OFF_PEAK_PCT_8316 ->
                if (ageMin.isFinite() && ageMin <= FRESH_MAX_AGE_MIN_8316) Stage.FRESH_LAUNCH else Stage.BASE_START
            else -> null
        }
    }

    /**
     * Pure: the stage read from [prints] (oldest first, one venue, executed prints only). Price from the peak and from the
     * first print over the whole list; buy share and net SOL over the last [FLOW_WINDOW_MS_8316] before [nowMs].
     */
    fun classify8316(prints: List<TradeTape8100.Print8128>, nowMs: Long, ageMin: Double): Read8316? {
        if (ageMin.isFinite() && ageMin > MAX_AGE_MIN_8316) return null
        val exec = prints.filter { !it.priceOnly && it.priceSol.isFinite() && it.priceSol > 0.0 }
        if (exec.size < MIN_PRINTS_8316) return null
        val first = exec.first().priceSol
        val last = exec.last().priceSol
        val peak = exec.maxOf { it.priceSol }
        val offPeak = ((1.0 - last / peak) * 100.0).coerceAtLeast(0.0)
        val fromFirst = (last / first - 1.0) * 100.0
        val flow = exec.filter { nowMs - it.atMs in 0L..FLOW_WINDOW_MS_8316 && it.sol.isFinite() && it.sol > 0.0 }
        if (flow.size < MIN_FLOW_PRINTS_8316) return null
        val buySol = flow.filter { it.isBuy }.sumOf { it.sol }
        val sellSol = flow.filter { !it.isBuy }.sumOf { it.sol }
        if (!(buySol + sellSol > 0.0)) return null
        val buyShare = buySol / (buySol + sellSol) * 100.0
        val stage = stageOf8316(offPeak, fromFirst, buyShare, buySol - sellSol, ageMin) ?: return null
        return Read8316(stage, offPeak, fromFirst, buyShare, buySol - sellSol, exec.size, "tape8100")
    }

    /** Pure: the same read from the launch tape's features (its whole-life buy share / net and price from first / peak). */
    fun classifyLaunch8316(f: LaunchTape7921.Feat?): Read8316? {
        if (f == null || f.trades8287 < MIN_PRINTS_8316 || !f.fromPeakPct.isFinite() || !f.fromFirstPct.isFinite()) return null
        if (f.ageMin.isFinite() && f.ageMin > MAX_AGE_MIN_8316) return null
        val offPeak = (-f.fromPeakPct).coerceAtLeast(0.0)
        val share = if (f.bpAll8287.isFinite()) f.bpAll8287 else f.buySharePct
        val stage = stageOf8316(offPeak, f.fromFirstPct, share, f.crowdNetSol, f.ageMin) ?: return null
        return Read8316(stage, offPeak, f.fromFirstPct, share, f.crowdNetSol, f.trades8287, "launchTape7921")
    }

    /**
     * Runtime (TokenMetricStageRouter.snapshot, the UNKNOWN arm only): the coin's stage from its own prints, counted as
     * STAGE_FROM_TAPE_8316_<stage>. Null (UNKNOWN stays) without enough prints or past [MAX_AGE_MIN_8316].
     */
    fun stageFromTape8316(mint: String, ageMin: Double, nowMs: Long = System.currentTimeMillis()): Read8316? = try {
        if (mint.isBlank() || (ageMin.isFinite() && ageMin > MAX_AGE_MIN_8316)) null else {
            val launch = try { LaunchTape7921.features(mint, nowMs) } catch (_: Throwable) { null }
            val age = if (ageMin.isFinite()) ageMin else launch?.ageMin ?: Double.NaN
            val read = classify8316(TradeTape8100.prints8128(mint, nowMs, PEAK_WINDOW_MS_8316), nowMs, age) ?: classifyLaunch8316(launch)
            if (read != null) try { PipelineHealthCollector.labelInc("STAGE_FROM_TAPE_8316_${read.stage.name}") } catch (_: Throwable) {}
            read
        }
    } catch (_: Throwable) { null }
}
