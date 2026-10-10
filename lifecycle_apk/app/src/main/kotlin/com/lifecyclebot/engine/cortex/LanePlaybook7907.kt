package com.lifecyclebot.engine.cortex

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7907 — per-lane playbooks: no lane trades on one idea.
 *
 * Operator: "no lane ever should be on just one idea ... the per lane traders
 * cheat sheet scoring etc and how the bot should learn and then tune from that
 * starting point." The 08 Oct audit: every lane's trader scores one snapshot
 * with one weight set, TacticSwitcher left every lane on MOMENTUM, and every
 * planned live entry was LAUNCH_EARLY (784/784).
 *
 * Each lane now carries a menu of 3-4 setups taken from FIELD_MANUAL §4 and the
 * cheat sheet (A trend pullback, B base breakout/retest, C range reversion, D
 * sweep/reclaim, E momentum continuation, F launch, G relative strength). A
 * setup fires on measurable conditions (stage snapshot, 5m/1h change, flow,
 * holders, TradePlan7739's bar read, FreshLaunchSelector7737's launch phase).
 * Numbers are derived from the manual's rules (its own text gives none).
 *
 * Educated start, then learning:
 *   - prior: a structure-confirmed setup starts at +1.0% net, a flow/feature
 *     setup at +0.5%, a launch probe at 0, and "no trigger" at -1.0% (§9: "no
 *     clear trigger" is a PASS). Every (lane, setup) is graded on its forward
 *     label from trade one (admitted and refused alike) and shrunk to its prior
 *     by n/(n+20).
 *   - when several setups match, the candidate is tagged with the one whose
 *     record is best, and that id is a Cortex voter so its edge is seated.
 *   - LIVE, binding: no matching setup refuses (PLAYBOOK_NO_TRIGGER) unless the
 *     lane's own NO_TRIGGER record is proven positive (n>=40, mean-SE>+1%); a
 *     setup proven losing in that lane (n>=30, mean+SE<-2%, runner lanes without
 *     a 10% runner tail) refuses. Paper never refuses: it keeps every label coming.
 */
object LanePlaybook7907 {
    const val NO_TRIGGER = "NO_TRIGGER"
    private const val SHRINK_K = 20.0
    private const val NONE_PROOF_N = 40
    private const val NONE_PROOF_PCT = 1.0
    private const val LOSING_N = 30
    private const val LOSING_PCT = -2.0
    private const val TAIL_RATE = 0.10

    /** Inputs a setup reads; NaN = unknown. */
    class F(
        val age: Double, val runup: Double, val pxPeak: Double, val dd: Double, val bp: Double,
        val liq: Double, val mcap: Double, val top: Double, val chg5m: Double, val chg1h: Double,
        val holderGrowth: Double, val volAccel: Double, val planSetup: String, val launchPhase: String,
        val crowdForming: Boolean = false,
        // V5.0.7924 — the wider feature set the expanded setup library reads (NaN/blank = unknown).
        val tapeCrowd: Double = Double.NaN, val tapeNetSol: Double = Double.NaN, val tapeBuyersPerMin: Double = Double.NaN,
        val tapeBuyShare: Double = Double.NaN, val tapeLargest: Double = Double.NaN, val tapeTop3: Double = Double.NaN,
        val tapeDevSold: Double = Double.NaN, val tapeFromPeak: Double = Double.NaN, val tapeFromFirst: Double = Double.NaN,
        val buyTx15s: Double = Double.NaN, val buyTxPrev15s: Double = Double.NaN, val repeatBuyers60s: Double = Double.NaN,
        val oppSetup: String = "", val oppPercentile: Double = Double.NaN, val oppRs: Double = Double.NaN,
        val oppVolAccel: Double = Double.NaN, val oppTxAccel: Double = Double.NaN, val oppLiqDelta: Double = Double.NaN,
        val boost: Double = Double.NaN, val socials: Double = Double.NaN, val cto: Boolean = false,
        val devTokens: Double = Double.NaN, val devRugRate: Double = Double.NaN, val devKnownRugger: Boolean = false,
        val holders: Double = Double.NaN, val insider: Double = Double.NaN, val narrativeHeat: Double = Double.NaN,
        val narrativeExhaustion: Double = Double.NaN, val bundleLargest: Double = Double.NaN, val lpLock: Double = Double.NaN,
        val txVelocity: Double = Double.NaN, val volatility: Double = Double.NaN, val regime: String = "",
        // V5.0.7926 — FreshLaunchSelector7737's -15% ladder has PROVEN this launch's setup cell.
        val launchLadderProven: Boolean = false,
        // V5.0.7962 — an expert wallet (OWNER / top trader / smart money) bought this mint minutes ago.
        val expertEntry7962: Boolean = false,
        // V5.0.7962 — StructureTracker7962: higher low after a higher high, reclaimed, buyers >= 50%.
        val structureHlReclaim: Boolean = false,
        // V5.0.7974 — the bot sold this mint within 2 h and it is back near that price with a fresh higher low.
        val secondWave7974: Boolean = false,
        // V5.0.7978 — the facts the runners carried that no setup read (NaN / false = unknown).
        val holderVel7978: Double = Double.NaN,      // holders gained per minute over ~5 min
        val turnover7978: Double = Double.NaN,       // 1h volume / market cap
        val curve7978: Double = Double.NaN,          // pump.fun bonding-curve progress 0..1
        val live7978: Boolean = false,               // pump.fun livestream or King of the Hill
        val beta7978: Boolean = false,               // copycat of a coin that ran +400% in the last 6 h
        val colourBuy7978: Boolean = false,          // a proven candle-colour sequence
        /** Lane-specific: a promoted specialist of the lane matches (set by classifyNow). */
        var specialistEdge7978: Boolean = false,
    )

    class Setup(val id: String, val prior: Double, val fires: (F) -> Boolean)

    /**
     * Pure. V5.0.7974 — the price side of a second wave: the bot sold this mint 2-120 minutes
     * ago (no position open) and price is back within -40%..+10% of that exit.
     */
    fun secondWave7974(lastExitMs: Long, lastExitPx: Double, px: Double, nowMs: Long, open: Boolean): Boolean {
        if (open || lastExitMs <= 0L || !(lastExitPx > 0.0) || !(px > 0.0)) return false
        val dt = nowMs - lastExitMs
        if (dt < 2L * 60_000L || dt > 120L * 60_000L) return false
        val r = px / lastExitPx
        return r in 0.60..1.10
    }

    private fun ok(v: Double, lo: Double, hi: Double) = v.isFinite() && v >= lo && v <= hi
    private fun ge(v: Double, x: Double) = v.isFinite() && v >= x
    private fun le(v: Double, x: Double) = v.isFinite() && v <= x
    private fun leOrUnknown(v: Double, x: Double) = !v.isFinite() || v <= x
    private fun geOrUnknown(v: Double, x: Double) = !v.isFinite() || v >= x

    private const val STRUCT = 1.0
    private const val FLOW = 0.5
    private const val PROBE = 0.0

    // ── setup library (FIELD_MANUAL §4 families) ──
    private val HIGHER_LOW_PULLBACK = Setup("HIGHER_LOW_PULLBACK", FLOW) { f ->
        ok(f.dd, 8.0, 25.0) && ok(f.pxPeak, 0.75, 0.92) && ge(f.bp, 45.0) && ge(f.chg5m, 0.0)
    }
    private val TREND_PULLBACK = Setup("TREND_PULLBACK", FLOW) { f ->
        ok(f.dd, 5.0, 15.0) && ge(f.bp, 45.0) && ge(f.chg1h, 0.0) && ge(f.chg5m, 0.0)
    }
    private val PLAN_PULLBACK_RECLAIM = Setup("PLAN_PULLBACK_RECLAIM", STRUCT) { f -> f.planSetup == "PULLBACK_RECLAIM" }
    private val PLAN_BASE_BREAKOUT = Setup("PLAN_BASE_BREAKOUT", STRUCT) { f -> f.planSetup == "BASE_BREAKOUT" }
    private val PLAN_SWEEP_RECLAIM = Setup("PLAN_SWEEP_RECLAIM", STRUCT) { f -> f.planSetup == "SWEEP_RECLAIM" }
    private val BREAKOUT_HOLD = Setup("BREAKOUT_HOLD", FLOW) { f ->
        ge(f.pxPeak, 0.95) && leOrUnknown(f.runup, 60.0) && ge(f.bp, 55.0) && ge(f.chg5m, 0.0)
    }
    private val RECLAIM_AFTER_WEAKNESS = Setup("RECLAIM_AFTER_WEAKNESS", FLOW) { f ->
        ge(f.chg5m, 1.0) && le(f.chg1h, 0.0) && ge(f.bp, 50.0)
    }
    private val RELATIVE_STRENGTH = Setup("RELATIVE_STRENGTH", FLOW) { f ->
        ge(f.chg1h, 5.0) && ge(f.bp, 55.0) && leOrUnknown(f.runup, 60.0) && le(f.dd, 15.0)
    }
    private val FLAG_CONTINUATION = Setup("FLAG_CONTINUATION", FLOW) { f ->
        ge(f.chg1h, 10.0) && ok(f.chg5m, -2.0, 2.0) && ge(f.bp, 50.0)
    }
    private val LAUNCH_CONTINUATION = Setup("LAUNCH_CONTINUATION", PROBE) { f ->
        le(f.age, 120.0) && leOrUnknown(f.top, 20.0) && ge(f.bp, 50.0) && leOrUnknown(f.runup, 150.0) &&
            (f.launchPhase == "PRE_IGNITION" || f.launchPhase == "EXPANDING")
    }
    private val PRE_IGNITION_BASE = Setup("PRE_IGNITION_BASE", FLOW) { f ->
        le(f.age, 120.0) && le(f.runup, 30.0) && ge(f.bp, 55.0)
    }
    private val FIRST_PULLBACK = Setup("FIRST_PULLBACK", FLOW) { f ->
        le(f.age, 240.0) && ok(f.dd, 20.0, 40.0) && ge(f.bp, 50.0) && ge(f.chg5m, 0.0)
    }
    private val VOLUME_CONTINUATION = Setup("VOLUME_CONTINUATION", FLOW) { f ->
        ge(f.chg5m, 0.0) && ge(f.bp, 50.0) && leOrUnknown(f.runup, 60.0) && ge(f.pxPeak, 0.85) &&
            geOrUnknown(f.volAccel, 55.0) && !(ge(f.pxPeak, 0.97) && ge(f.chg5m, 15.0))
    }
    private val HIGHER_LOW_CONTINUATION = Setup("HIGHER_LOW_CONTINUATION", FLOW) { f ->
        ok(f.dd, 5.0, 15.0) && ge(f.chg5m, 0.0) && ge(f.bp, 50.0)
    }
    private val MICRO_FLAG = Setup("MICRO_FLAG", FLOW) { f -> ok(f.chg5m, -3.0, 3.0) && ge(f.chg1h, 15.0) && ge(f.bp, 50.0) }
    private val BREAKOUT_RUNNER = Setup("BREAKOUT_RUNNER", FLOW) { f ->
        ge(f.pxPeak, 0.90) && ok(f.runup, 30.0, 300.0) && ge(f.bp, 55.0)
    }
    private val RS_LEADER = Setup("RS_LEADER", FLOW) { f -> ge(f.chg1h, 30.0) && ge(f.bp, 55.0) && le(f.dd, 20.0) }
    private val POST_EVENT_RECLAIM = Setup("POST_EVENT_RECLAIM", FLOW) { f -> ok(f.dd, 25.0, 50.0) && ge(f.chg5m, 3.0) && ge(f.bp, 55.0) }
    private val VERIFIED_LAUNCH = Setup("VERIFIED_LAUNCH", PROBE) { f -> le(f.age, 60.0) && le(f.top, 20.0) && ge(f.bp, 50.0) }
    private val LOW_RUNUP_BASE = Setup("LOW_RUNUP_BASE", FLOW) { f -> le(f.runup, 40.0) && ge(f.pxPeak, 0.90) && ge(f.bp, 55.0) }
    private val SWEEP_RECLAIM_FLOW = Setup("SWEEP_RECLAIM_FLOW", FLOW) { f -> ok(f.dd, 15.0, 40.0) && ge(f.chg5m, 3.0) && ge(f.bp, 50.0) }
    private val CAPITULATION_HIGHER_LOW = Setup("CAPITULATION_HIGHER_LOW", FLOW) { f ->
        ok(f.dd, 40.0, 75.0) && ge(f.chg5m, 0.5) && ge(f.bp, 55.0)
    }
    private val SUPPORT_FLIP = Setup("SUPPORT_FLIP", FLOW) { f -> ok(f.dd, 10.0, 25.0) && ge(f.chg1h, 0.0) && ge(f.bp, 50.0) && ge(f.chg5m, 0.0) }
    private val DISTRIBUTION_RECLAIM = Setup("DISTRIBUTION_RECLAIM", FLOW) { f ->
        ok(f.dd, 20.0, 50.0) && ge(f.chg5m, 2.0) && ge(f.bp, 55.0) && leOrUnknown(f.top, 35.0)
    }
    private val RANGE_LOW_BOUNCE = Setup("RANGE_LOW_BOUNCE", FLOW) { f ->
        le(f.pxPeak, 0.85) && ok(f.dd, 5.0, 20.0) && ge(f.chg5m, 0.0) && ok(f.chg1h, -15.0, 15.0)
    }
    // V5.0.7921/7923 — the launch tape since birth shows a crowd forming (LaunchTape7921).
    private val CROWD_FORMING = Setup("CROWD_FORMING", FLOW) { f -> f.crowdForming }
    // V5.0.7926 — a fresh launch whose setup cell (phase|flow|concentration|multiple)
    // the launch selector has PROVEN on its own graded record (n15 >= 20, ev15 net of
    // cost > 0). 5.0.7925: PRE_IGNITION|FLOW_OK|CONC_ONE n15=26 ev15=+22.5% was refused
    // live as NO_TRIGGER because no hand-written setup matched it.
    private val LAUNCH_LADDER_PROVEN = Setup("LAUNCH_LADDER_PROVEN", STRUCT) { f -> f.launchLadderProven }
    // V5.0.7962 — the owner's read: buyers dominate, lows refill into new highs (15 s / 1 m swings).
    private val HL_RECLAIM = Setup("HL_RECLAIM", STRUCT) { f -> f.structureHlReclaim }

    // ── V5.0.7924 — the expanded library. Each fires on measurable conditions;
    //    each starts at an educated prior and is graded per lane from trade one.
    //    Launch tape (first minutes since birth) ──
    private val FAST_CROWD = Setup("FAST_CROWD", FLOW) { f ->
        le(f.age, 10.0) && ge(f.tapeBuyersPerMin, 8.0) && ge(f.tapeBuyShare, 60.0) && le(f.tapeLargest, 25.0) && le(f.tapeDevSold, 0.0)
    }
    private val BROAD_DISTRIBUTION = Setup("BROAD_DISTRIBUTION", FLOW) { f ->
        ge(f.tapeCrowd, 40.0) && le(f.tapeTop3, 35.0) && ge(f.tapeBuyShare, 55.0) && le(f.tapeDevSold, 0.0)
    }
    private val NET_INFLOW_SURGE = Setup("NET_INFLOW_SURGE", FLOW) { f ->
        le(f.age, 15.0) && ge(f.tapeNetSol, 10.0) && ge(f.tapeFromPeak, -25.0) && le(f.tapeLargest, 35.0)
    }
    private val FIRST_DIP_BOUGHT = Setup("FIRST_DIP_BOUGHT", FLOW) { f ->
        ok(f.age, 3.0, 20.0) && ok(f.tapeFromPeak, -35.0, -15.0) && ge(f.buyTx15s, 3.0) && f.buyTx15s > f.buyTxPrev15s &&
            ge(f.tapeBuyShare, 55.0) && le(f.tapeDevSold, 0.0)
    }
    private val DEV_HOLDS_CROWD_BUYS = Setup("DEV_HOLDS_CROWD_BUYS", FLOW) { f ->
        le(f.age, 20.0) && le(f.tapeDevSold, 0.0) && ge(f.tapeCrowd, 20.0) && ge(f.tapeBuyShare, 55.0) && leOrUnknown(f.devTokens, 3.0)
    }
    private val CLEAN_DEV_LAUNCH = Setup("CLEAN_DEV_LAUNCH", FLOW) { f ->
        le(f.age, 60.0) && le(f.devTokens, 2.0) && leOrUnknown(f.devRugRate, 0.2) && !f.devKnownRugger &&
            ge(f.tapeCrowd, 10.0) && ge(f.tapeBuyShare, 55.0)
    }
    private val ACCELERATING_TAPE = Setup("ACCELERATING_TAPE", FLOW) { f ->
        ge(f.buyTx15s, 5.0) && f.buyTx15s > f.buyTxPrev15s && leOrUnknown(f.tapeLargest, 35.0) && geOrUnknown(f.bp, 55.0)
    }
    private val GRADUATION_RUN = Setup("GRADUATION_RUN", FLOW) { f ->
        ge(f.tapeNetSol, 40.0) && ge(f.tapeFromPeak, -15.0) && ge(f.tapeBuyShare, 60.0) && le(f.tapeDevSold, 0.0)
    }
    private val NO_BUNDLE_CLEAN = Setup("NO_BUNDLE_CLEAN", FLOW) { f ->
        le(f.age, 120.0) && le(f.bundleLargest, 20.0) && ge(f.bp, 55.0) && leOrUnknown(f.runup, 150.0)
    }
    // ── social, narrative, insiders ──
    private val SOCIAL_LAUNCH = Setup("SOCIAL_LAUNCH", FLOW) { f ->
        le(f.age, 60.0) && ge(f.socials, 2.0) && ge(f.bp, 55.0) && geOrUnknown(f.tapeCrowd, 10.0)
    }
    private val BOOSTED_LAUNCH = Setup("BOOSTED_LAUNCH", PROBE) { f -> le(f.age, 120.0) && ge(f.boost, 100.0) && ge(f.bp, 55.0) }
    private val CTO_REVIVAL = Setup("CTO_REVIVAL", PROBE) { f -> f.cto && ge(f.chg1h, 0.0) && ge(f.bp, 55.0) }
    private val NARRATIVE_WAVE = Setup("NARRATIVE_WAVE", FLOW) { f ->
        ge(f.narrativeHeat, 0.6) && leOrUnknown(f.narrativeExhaustion, 0.5) && ge(f.bp, 55.0)
    }
    private val INSIDER_ACCUMULATION = Setup("INSIDER_ACCUMULATION", FLOW) { f ->
        ge(f.insider, 30.0) && ge(f.bp, 55.0) && leOrUnknown(f.runup, 100.0)
    }
    private val POST_MIGRATION_HOLD = Setup("POST_MIGRATION_HOLD", FLOW) { f ->
        le(f.age, 60.0) && ge(f.liq, 15_000.0) && ge(f.chg5m, 0.0) && ge(f.bp, 55.0) && (f.mcap / f.liq).let { it.isFinite() && it <= 6.0 }
    }
    // ── market-wide opportunity ranking (MarketSweep7297) ──
    private val OPP_EARLY_IGNITION = Setup("OPP_EARLY_IGNITION", FLOW) { f -> f.oppSetup == "EARLY_MOMENTUM_IGNITION" && ge(f.oppPercentile, 0.5) }
    private val OPP_BREAKOUT_EXPANSION = Setup("OPP_BREAKOUT_EXPANSION", FLOW) { f -> f.oppSetup == "BREAKOUT_EXPANSION" && ge(f.oppPercentile, 0.6) }
    private val OPP_RS_LEADER = Setup("OPP_RS_LEADER", FLOW) { f -> f.oppSetup == "RELATIVE_STRENGTH_LEADER" && ge(f.oppPercentile, 0.6) }
    private val OPP_DIP_RECOVERY = Setup("OPP_DIP_RECOVERY", FLOW) { f -> f.oppSetup == "DIP_RECOVERY" && ge(f.oppPercentile, 0.4) }
    private val OPP_LIQUIDITY_EXPANSION = Setup("OPP_LIQUIDITY_EXPANSION", FLOW) { f -> f.oppSetup == "LIQUIDITY_EXPANSION" && ge(f.oppPercentile, 0.4) }
    private val OPP_CONTINUATION = Setup("OPP_CONTINUATION", FLOW) { f -> f.oppSetup == "CONTINUATION" && ge(f.oppPercentile, 0.6) }
    // ── momentum / structure ──
    private val VOLUME_IGNITION = Setup("VOLUME_IGNITION", FLOW) { f ->
        ge(f.oppVolAccel, 2.0) && geOrUnknown(f.oppTxAccel, 1.5) && ge(f.chg5m, 2.0) && leOrUnknown(f.runup, 80.0)
    }
    private val HOLDER_EXPANSION = Setup("HOLDER_EXPANSION", FLOW) { f -> ge(f.holderGrowth, 10.0) && ge(f.bp, 55.0) && leOrUnknown(f.dd, 25.0) }
    private val TX_VELOCITY_BREAK = Setup("TX_VELOCITY_BREAK", FLOW) { f -> ge(f.txVelocity, 3.0) && ge(f.pxPeak, 0.90) && ge(f.bp, 55.0) }
    private val SECOND_LEG = Setup("SECOND_LEG", FLOW) { f ->
        ok(f.age, 30.0, 240.0) && ok(f.dd, 25.0, 50.0) && ge(f.chg5m, 3.0) && ge(f.bp, 60.0) && geOrUnknown(f.holderGrowth, 0.0)
    }
    private val LOW_VOL_COIL = Setup("LOW_VOL_COIL", FLOW) { f ->
        le(f.volatility, 15.0) && ge(f.pxPeak, 0.90) && ge(f.bp, 50.0) && ok(f.chg1h, -5.0, 10.0)
    }
    private val DEEP_LIQ_TREND = Setup("DEEP_LIQ_TREND", FLOW) { f -> ge(f.liq, 100_000.0) && ge(f.chg1h, 3.0) && le(f.dd, 10.0) && ge(f.bp, 50.0) }
    private val MEAN_REVERSION_OVERSOLD = Setup("MEAN_REVERSION_OVERSOLD", FLOW) { f ->
        ge(f.dd, 30.0) && ge(f.chg5m, 1.0) && le(f.chg1h, -15.0) && ge(f.liq, 50_000.0)
    }
    private val LP_LOCKED_BASE = Setup("LP_LOCKED_BASE", FLOW) { f ->
        ge(f.lpLock, 90.0) && ge(f.pxPeak, 0.85) && ge(f.bp, 55.0) && le(f.mcap, 2_000_000.0)
    }
    private val MOMENTUM_PULLBACK_5M = Setup("MOMENTUM_PULLBACK_5M", FLOW) { f -> ge(f.chg1h, 10.0) && ok(f.chg5m, -6.0, -1.0) && ge(f.bp, 50.0) }
    private val RS_IN_WEAK_MARKET = Setup("RS_IN_WEAK_MARKET", FLOW) { f ->
        (f.regime == "DUMP" || f.regime == "CHOP" || f.regime == "DEAD") && ge(f.chg1h, 0.0) && ge(f.chg5m, 0.0) && ge(f.bp, 55.0)
    }
    // V5.0.7974 — established memes ($1M-$50M, deep pool) swing inside their range: buy the
    // lower part of the range once the 5-minute turns up with buyers in control (owner: BERT,
    // Jean Phil, baton, apeonfone ranges). Graded per lane like every setup.
    private val RANGE_SUPPORT_SWING = Setup("RANGE_SUPPORT_SWING", FLOW) { f ->
        ok(f.mcap, 1_000_000.0, 50_000_000.0) && ge(f.liq, 100_000.0) && ok(f.dd, 25.0, 65.0) &&
            ge(f.chg5m, 0.5) && ge(f.bp, 52.0) && geOrUnknown(f.chg1h, -12.0)
    }
    // V5.0.7974 — second wave: re-buy a coin the bot already sold once it builds a higher low
    // again near the exit (owner: Spiralism / Jean Phil second legs).
    private val SECOND_WAVE = Setup("SECOND_WAVE", STRUCT) { f -> f.secondWave7974 }
    // ── V5.0.7978 — the runner setups. Owner: "the no setup bulletin needs to be fixed.
    // obviously it needs more setups." 5.0.7976: SHITCOIN tagged NO_TRIGGER 156 of 171, and
    // the runners it refused (Memecoins +1,032%, POORELON +1,438%, $CCOW +2,162%) sat there.
    // Each is learned per lane like every setup (prior, then its own labels; a proven loser refuses).
    private val SPECIALIST_EDGE = Setup("SPECIALIST_EDGE", STRUCT) { f -> f.specialistEdge7978 }
    private val FRESH_LAUNCH_MOMENTUM = Setup("FRESH_LAUNCH_MOMENTUM", FLOW) { f ->
        le(f.age, 15.0) && le(f.mcap, 100_000.0) && ge(f.tapeBuyersPerMin, 6.0) &&
            geOrUnknown(f.tapeBuyShare, 60.0) && leOrUnknown(f.tapeLargest, 30.0)
    }
    private val MICRO_CAP_IGNITION = Setup("MICRO_CAP_IGNITION", FLOW) { f ->
        le(f.mcap, 10_000.0) && le(f.age, 15.0) && ge(f.chg5m, 10.0) && geOrUnknown(f.bp, 60.0)
    }
    private val HOLDER_SURGE = Setup("HOLDER_SURGE", FLOW) { f -> ge(f.holderVel7978, 5.0) && geOrUnknown(f.bp, 55.0) }
    private val TURNOVER_SPIKE = Setup("TURNOVER_SPIKE", FLOW) { f ->
        ge(f.turnover7978, 3.0) && ge(f.chg5m, 0.0) && geOrUnknown(f.bp, 55.0)
    }
    private val GRADUATION_APPROACH = Setup("GRADUATION_APPROACH", FLOW) { f ->
        ok(f.curve7978, 0.80, 0.97) && geOrUnknown(f.bp, 55.0) && geOrUnknown(f.chg5m, 0.0)
    }
    private val LIVESTREAM_LAUNCH = Setup("LIVESTREAM_LAUNCH", FLOW) { f -> f.live7978 && geOrUnknown(f.bp, 55.0) }
    private val COPYCAT_BETA = Setup("COPYCAT_BETA", FLOW) { f -> f.beta7978 && le(f.age, 60.0) && geOrUnknown(f.bp, 55.0) }
    private val COLOUR_SEQUENCE = Setup("COLOUR_SEQUENCE", FLOW) { f -> f.colourBuy7978 }
    private val MEME_EXTRAS_7978 = listOf(SPECIALIST_EDGE, FRESH_LAUNCH_MOMENTUM, MICRO_CAP_IGNITION, HOLDER_SURGE, TURNOVER_SPIKE,
        GRADUATION_APPROACH, LIVESTREAM_LAUNCH, COPYCAT_BETA, COLOUR_SEQUENCE)
    private val OTHER_EXTRAS_7978 = listOf(SPECIALIST_EDGE, HOLDER_SURGE, TURNOVER_SPIKE, COLOUR_SEQUENCE)
    private val MEME_LANES_7978 = setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED")
    private val MICRO_PULLBACK_TREND = Setup("MICRO_PULLBACK_TREND", FLOW) { f -> ge(f.chg1h, 3.0) && ok(f.dd, 2.0, 8.0) && ge(f.bp, 50.0) }

    /** Lane -> its playbook (FIELD_MANUAL §4 families per lane; doc-derived). */
    private val MENU: Map<String, List<Setup>> = mapOf(
        "QUALITY" to listOf(PLAN_PULLBACK_RECLAIM, PLAN_BASE_BREAKOUT, PLAN_SWEEP_RECLAIM, HIGHER_LOW_PULLBACK, BREAKOUT_HOLD, RECLAIM_AFTER_WEAKNESS,
            HOLDER_EXPANSION, OPP_LIQUIDITY_EXPANSION, OPP_BREAKOUT_EXPANSION, LP_LOCKED_BASE, LOW_VOL_COIL, SECOND_LEG, MOMENTUM_PULLBACK_5M, RS_IN_WEAK_MARKET, HL_RECLAIM, RANGE_SUPPORT_SWING, SECOND_WAVE),
        "BLUECHIP" to listOf(PLAN_PULLBACK_RECLAIM, PLAN_BASE_BREAKOUT, TREND_PULLBACK, RELATIVE_STRENGTH, FLAG_CONTINUATION,
            DEEP_LIQ_TREND, LOW_VOL_COIL, MEAN_REVERSION_OVERSOLD, OPP_RS_LEADER, OPP_LIQUIDITY_EXPANSION, OPP_CONTINUATION, RS_IN_WEAK_MARKET, RANGE_SUPPORT_SWING),
        "SHITCOIN" to listOf(PLAN_PULLBACK_RECLAIM, PLAN_BASE_BREAKOUT, LAUNCH_CONTINUATION, FIRST_PULLBACK, PRE_IGNITION_BASE, CROWD_FORMING, LAUNCH_LADDER_PROVEN,
            FAST_CROWD, BROAD_DISTRIBUTION, NET_INFLOW_SURGE, FIRST_DIP_BOUGHT, DEV_HOLDS_CROWD_BUYS, CLEAN_DEV_LAUNCH, ACCELERATING_TAPE,
            GRADUATION_RUN, NO_BUNDLE_CLEAN, SOCIAL_LAUNCH, NARRATIVE_WAVE, INSIDER_ACCUMULATION, OPP_EARLY_IGNITION, HL_RECLAIM, SECOND_WAVE),
        "EXPRESS" to listOf(PLAN_BASE_BREAKOUT, VOLUME_CONTINUATION, HIGHER_LOW_CONTINUATION, MICRO_FLAG, CROWD_FORMING, LAUNCH_LADDER_PROVEN,
            ACCELERATING_TAPE, FAST_CROWD, VOLUME_IGNITION, TX_VELOCITY_BREAK, MOMENTUM_PULLBACK_5M, OPP_EARLY_IGNITION, OPP_RS_LEADER, HL_RECLAIM),
        "MOONSHOT" to listOf(PLAN_BASE_BREAKOUT, LAUNCH_CONTINUATION, BREAKOUT_RUNNER, RS_LEADER, POST_EVENT_RECLAIM, CROWD_FORMING, LAUNCH_LADDER_PROVEN,
            FAST_CROWD, NET_INFLOW_SURGE, GRADUATION_RUN, POST_MIGRATION_HOLD, BOOSTED_LAUNCH, SOCIAL_LAUNCH, NARRATIVE_WAVE,
            VOLUME_IGNITION, OPP_BREAKOUT_EXPANSION, OPP_EARLY_IGNITION, SECOND_LEG, CTO_REVIVAL, HL_RECLAIM, SECOND_WAVE),
        "PROJECT_SNIPER" to listOf(PLAN_BASE_BREAKOUT, VERIFIED_LAUNCH, LOW_RUNUP_BASE, FIRST_PULLBACK, CROWD_FORMING, LAUNCH_LADDER_PROVEN,
            CLEAN_DEV_LAUNCH, SOCIAL_LAUNCH, BROAD_DISTRIBUTION, NO_BUNDLE_CLEAN, LP_LOCKED_BASE, DEV_HOLDS_CROWD_BUYS, POST_MIGRATION_HOLD, HL_RECLAIM),
        "DIP_HUNTER" to listOf(PLAN_SWEEP_RECLAIM, SWEEP_RECLAIM_FLOW, CAPITULATION_HIGHER_LOW, SUPPORT_FLIP,
            MEAN_REVERSION_OVERSOLD, SECOND_LEG, FIRST_DIP_BOUGHT, HOLDER_EXPANSION, OPP_DIP_RECOVERY, HL_RECLAIM),
        "MANIPULATED" to listOf(PLAN_SWEEP_RECLAIM, DISTRIBUTION_RECLAIM, SWEEP_RECLAIM_FLOW, CTO_REVIVAL, ACCELERATING_TAPE, FIRST_DIP_BOUGHT, HL_RECLAIM),
        "TREASURY" to listOf(PLAN_SWEEP_RECLAIM, RANGE_LOW_BOUNCE, RECLAIM_AFTER_WEAKNESS, MICRO_PULLBACK_TREND,
            DEEP_LIQ_TREND, LOW_VOL_COIL, MEAN_REVERSION_OVERSOLD, MOMENTUM_PULLBACK_5M, OPP_DIP_RECOVERY, RANGE_SUPPORT_SWING),
        "CASHGEN" to listOf(PLAN_SWEEP_RECLAIM, RANGE_LOW_BOUNCE, RECLAIM_AFTER_WEAKNESS, MICRO_PULLBACK_TREND,
            DEEP_LIQ_TREND, LOW_VOL_COIL, MEAN_REVERSION_OVERSOLD, MOMENTUM_PULLBACK_5M, OPP_CONTINUATION, RANGE_SUPPORT_SWING),
        "CYCLIC" to listOf(PLAN_SWEEP_RECLAIM, PLAN_BASE_BREAKOUT, RANGE_LOW_BOUNCE, SUPPORT_FLIP,
            MEAN_REVERSION_OVERSOLD, LOW_VOL_COIL, OPP_LIQUIDITY_EXPANSION, SECOND_LEG, OPP_DIP_RECOVERY, RANGE_SUPPORT_SWING),
        "CORE" to listOf(PLAN_PULLBACK_RECLAIM, PLAN_BASE_BREAKOUT, PLAN_SWEEP_RECLAIM, HIGHER_LOW_PULLBACK, RANGE_LOW_BOUNCE,
            OPP_BREAKOUT_EXPANSION, OPP_RS_LEADER, HOLDER_EXPANSION, VOLUME_IGNITION, DEEP_LIQ_TREND, CROWD_FORMING, HL_RECLAIM, RANGE_SUPPORT_SWING),
    )

    // V5.0.7962 — EXPERT_ENTRY: an expert wallet's live buy on this mint (ExpertWallets7962), graded
    // per lane on forward labels like every setup (flow prior; a proven loser refuses as usual).
    private val EXPERT_ENTRY_7962 = Setup("EXPERT_ENTRY", FLOW) { f -> f.expertEntry7962 }
    private val EXPERT_LANES_7962 = setOf("SHITCOIN", "EXPRESS", "MOONSHOT", "PROJECT_SNIPER")
    private fun menuOf7962(lane: String): List<Setup>? = MENU[lane]?.let { base ->
        (if (lane in EXPERT_LANES_7962) base + EXPERT_ENTRY_7962 else base) +
            (if (lane in MEME_LANES_7978) MEME_EXTRAS_7978 else OTHER_EXTRAS_7978)   // V5.0.7978
    }

    /** Pure: the setups of [lane]'s menu that [f] fires (empty = no trigger; unknown lane = null). */
    fun matches(lane: String, f: F): List<Setup>? = menuOf7962(lane)?.filter { s -> try { s.fires(f) } catch (_: Throwable) { false } }

    fun menuIds(lane: String): List<String> = menuOf7962(lane)?.map { it.id }.orEmpty()

    // ── features ──

    private fun canon(lane: String): String {
        val c = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(lane).uppercase() } catch (_: Throwable) { "" }
        return if (c.isBlank()) lane.trim().uppercase() else c
    }

    fun features(ts: TokenState, nowMs: Long = System.currentTimeMillis()): F {
        val s = try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts) } catch (_: Throwable) { null }
        fun pos(v: Double?) = if (v != null && v.isFinite() && v > 0.0) v else Double.NaN
        fun nn(v: Double?) = if (v != null && v.isFinite() && v >= 0.0) v else Double.NaN
        val plan = try { com.lifecyclebot.engine.truth.TradePlan7739.readForEntry7837(ts, nowMs).setup?.name } catch (_: Throwable) { null }
        val phase = try { com.lifecyclebot.engine.truth.FreshLaunchSelector7737.shapingRead7871(ts, nowMs)?.first?.substringBefore('|') } catch (_: Throwable) { null }
        val volAcc = try { com.lifecyclebot.engine.MomentumPredictorAI.getMomentum(ts.mint)?.volumeAccelerationScore } catch (_: Throwable) { null }
        val tape = try { com.lifecyclebot.engine.market.LaunchTape7921.features(ts.mint, nowMs) } catch (_: Throwable) { null }
        val flow = try { com.lifecyclebot.engine.WhaleDetector.launchFlow7401(ts.mint, nowMs = nowMs) } catch (_: Throwable) { null }
        val opp = try { com.lifecyclebot.engine.market.MarketSweep7297.opportunityFor7777(ts.mint) } catch (_: Throwable) { null }
        val social = try { com.lifecyclebot.network.DexScreenerSocialSource.peek7895(ts.mint) } catch (_: Throwable) { null }
        val creator = try {
            com.lifecyclebot.engine.OperatorRegistry.getDevWallet(ts.mint)?.let { com.lifecyclebot.network.HeliusCreatorHistory.peek7895(it) }
        } catch (_: Throwable) { null }
        val narrative = try { com.lifecyclebot.v4.meta.NarrativeFlowAI.getNarrativeForSymbol(ts.symbol) } catch (_: Throwable) { null }
        // V5.0.7925 — a default is not a reading. Buy pressure sits at its 50/50
        // default until a tape or DexScreener writes it; price changes sit at 0 and
        // peak/drawdown/run-up read 1.0/0/0 on a one-point history. Those defaults
        // satisfied setups (e.g. VOLUME_CONTINUATION on a token with no data), so a
        // candidate with no evidence escaped the NO_TRIGGER refusal. Unobserved = NaN.
        val shapeKnown = ts.history.size >= 3
        val bpRaw = s?.buyPressurePct ?: ts.lastBuyPressurePct
        val bpKnown = !(bpRaw == 50.0 && ts.lastSellPressurePct == 50.0)
        val chgKnown = ts.history.size >= 2 || ts.lastPriceChange5m != 0.0 || ts.lastPriceChange1h != 0.0
        return F(
            age = nn(s?.ageMin),
            runup = if (shapeKnown) nn(s?.runupFromLocalLowPct) else Double.NaN,
            pxPeak = if (shapeKnown) pos(s?.currentVsPeak) else Double.NaN,
            dd = if (shapeKnown) nn(s?.drawdownFromPeakPct) else Double.NaN,
            bp = if (bpKnown) nn(bpRaw) else Double.NaN, liq = pos(s?.liquidityUsd), mcap = pos(s?.marketCapUsd),
            top = nn(s?.topHolderPct),
            chg5m = if (chgKnown) ts.lastPriceChange5m.takeIf { it.isFinite() } ?: Double.NaN else Double.NaN,
            chg1h = if (chgKnown) ts.lastPriceChange1h.takeIf { it.isFinite() } ?: Double.NaN else Double.NaN,
            holderGrowth = ts.holderGrowthRate.takeIf { ts.holderDataResolved && it.isFinite() } ?: Double.NaN,
            // V5.0.7925 — 0 is a real reading (flat or falling volume), not "unknown".
            volAccel = volAcc?.takeIf { it.isFinite() } ?: Double.NaN,
            planSetup = plan.orEmpty(), launchPhase = phase.orEmpty(),
            crowdForming = try {
                com.lifecyclebot.engine.market.LaunchTape7921.promoted(ts.mint) ||
                    tape?.let { com.lifecyclebot.engine.market.LaunchTape7921.priorPass(it) } == true
            } catch (_: Throwable) { false },
            // V5.0.7924 — every input below is a read-only cache peek; none fetches.
            tapeCrowd = tape?.crowdBuyers?.toDouble() ?: Double.NaN,
            tapeNetSol = tape?.crowdNetSol ?: Double.NaN,
            tapeBuyersPerMin = tape?.buyersPerMin ?: Double.NaN,
            tapeBuyShare = tape?.takeIf { it.crowdBuyers > 0 }?.buySharePct ?: Double.NaN,
            tapeLargest = tape?.takeIf { it.crowdBuyers > 0 }?.largestBuyerPct ?: Double.NaN,
            tapeTop3 = tape?.takeIf { it.crowdBuyers > 0 }?.top3Pct ?: Double.NaN,
            tapeDevSold = tape?.let { if (it.devSold) 1.0 else 0.0 } ?: Double.NaN,
            tapeFromPeak = tape?.fromPeakPct ?: Double.NaN,
            tapeFromFirst = tape?.fromFirstPct ?: Double.NaN,
            buyTx15s = flow?.buyTx15s?.toDouble() ?: Double.NaN,
            buyTxPrev15s = flow?.buyTxPrev15s?.toDouble() ?: Double.NaN,
            repeatBuyers60s = flow?.repeatBuyerWallets60s?.toDouble() ?: Double.NaN,
            oppSetup = opp?.setup.orEmpty(),
            oppPercentile = opp?.percentile ?: Double.NaN,
            oppRs = opp?.relativeStrengthPct ?: Double.NaN,
            oppVolAccel = opp?.volumeAcceleration ?: Double.NaN,
            oppTxAccel = opp?.txAcceleration ?: Double.NaN,
            oppLiqDelta = opp?.liquidityDeltaPct ?: Double.NaN,
            boost = social?.boostTotal ?: Double.NaN,
            socials = social?.socialCount?.toDouble() ?: Double.NaN,
            cto = social?.communityTakeover == true,
            devTokens = creator?.takeIf { it.tokensCreated > 0 }?.tokensCreated?.toDouble() ?: Double.NaN,
            devRugRate = creator?.takeIf { it.tokensCreated > 0 }?.rugRate ?: Double.NaN,
            devKnownRugger = creator?.isKnownRugger == true,
            holders = ts.history.lastOrNull()?.holderCount?.takeIf { it > 0 }?.toDouble() ?: Double.NaN,
            insider = try { com.lifecyclebot.v3.scoring.InsiderTrackerAI.accumulationScore7925(ts.mint).takeIf { it > 0 }?.toDouble() } catch (_: Throwable) { null } ?: Double.NaN,
            narrativeHeat = narrative?.narrativeHeat ?: Double.NaN,
            narrativeExhaustion = narrative?.themeExhaustion ?: Double.NaN,
            bundleLargest = try {
                com.lifecyclebot.engine.BundleDetector.cachedFresh7763(ts.mint)
                    ?.takeIf { it.bundleRisk != com.lifecyclebot.engine.BundleDetector.BundleRisk.UNKNOWN }?.largestBundlePct
            } catch (_: Throwable) { null } ?: Double.NaN,
            lpLock = ts.safety.lpLockPct.takeIf { it >= 0.0 && it.isFinite() } ?: Double.NaN,
            txVelocity = try { com.lifecyclebot.engine.DataPipeline.cachedAlphaSignals6486(ts.mint)?.txVelocity } catch (_: Throwable) { null } ?: Double.NaN,
            volatility = ts.volatility ?: Double.NaN,
            regime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "" },
            launchLadderProven = try {
                val cost = com.lifecyclebot.engine.truth.FieldManual7715.allInCostPct(10.0, ts.lastLiquidityUsd.takeIf { it.isFinite() } ?: 0.0)
                com.lifecyclebot.engine.truth.FreshLaunchSelector7737.launchRead7742(ts, cost, nowMs).verdict ==
                    com.lifecyclebot.engine.truth.FreshLaunchSelector7737.LaunchVerdict.PROVEN
            } catch (_: Throwable) { false },
            expertEntry7962 = try { com.lifecyclebot.engine.ExpertWallets7962.expertEntryLive7962(ts.mint, nowMs) } catch (_: Throwable) { false },
            structureHlReclaim = try { com.lifecyclebot.engine.chart.StructureTracker7962.hlReclaim7962(ts.mint, nowMs) } catch (_: Throwable) { false },
            secondWave7974 = try {
                secondWave7974(ts.lastExitTs, ts.lastExitPrice, ts.lastPrice, nowMs, ts.position.isOpen) &&
                    com.lifecyclebot.engine.chart.StructureTracker7962.hlReclaim7962(ts.mint, nowMs)
            } catch (_: Throwable) { false },
            holderVel7978 = try {
                val h = ts.history
                val last = h.lastOrNull()
                val then = h.lastOrNull { it.ts <= (last?.ts ?: 0L) - 5 * 60_000L && it.holderCount > 0 }
                if (last == null || then == null) Double.NaN
                else com.lifecyclebot.engine.truth.SpecialistMiner7972.holderVelocity7972(last.holderCount, then.holderCount, (last.ts - then.ts) / 60_000.0)
            } catch (_: Throwable) { Double.NaN },
            turnover7978 = try {
                val v = ts.history.lastOrNull()?.volumeH1 ?: Double.NaN
                if (ts.lastMcap > 0.0 && v.isFinite() && v > 0.0) v / ts.lastMcap else Double.NaN
            } catch (_: Throwable) { Double.NaN },
            curve7978 = try {
                if (!ts.mint.endsWith("pump")) Double.NaN
                else com.lifecyclebot.engine.market.MemeMeta7973.curveProgress7973(ts.lastMcap, com.lifecyclebot.engine.WalletManager.lastKnownSolPrice)
            } catch (_: Throwable) { Double.NaN },
            live7978 = try {
                com.lifecyclebot.engine.market.MemeMeta7973.live7973(ts.mint, nowMs) || com.lifecyclebot.engine.market.MemeMeta7973.koth7973(ts.mint, nowMs)
            } catch (_: Throwable) { false },
            beta7978 = try { com.lifecyclebot.engine.market.MemeMeta7973.beta7973(ts.mint, ts.symbol, ts.name, nowMs) } catch (_: Throwable) { false },
            colourBuy7978 = try { com.lifecyclebot.engine.chart.ChartReader7950.cachedRead7955(ts.mint)?.colorBuy7968 == true } catch (_: Throwable) { false },
        )
    }

    // ── learning ──

    private class Book { val stats = HashMap<String, CortexLedger7885.Stat>() }

    private val books = HashMap<String, Book>()          // lane
    private val pending = ConcurrentHashMap<String, Pair<String, String>>()   // mint|labelLane -> (lane, setup)
    private val refusals = ConcurrentHashMap<String, AtomicLong>()
    private val tagged = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var loaded = false
    private val sincePersist = AtomicLong(0)

    private fun stat(lane: String, setup: String): CortexLedger7885.Stat? = books[lane]?.stats?.get(setup)

    private fun priorOf(lane: String, setup: String): Double =
        if (setup == NO_TRIGGER) -1.0 else menuOf7962(lane)?.firstOrNull { it.id == setup }?.prior ?: 0.0

    /** Shrunk expected net % of (lane, setup). Caller holds the lock. */
    private fun expected(lane: String, setup: String): Double {
        val st = stat(lane, setup)
        val n = st?.n ?: 0.0
        return ((st?.sum ?: 0.0) + SHRINK_K * priorOf(lane, setup)) / (n + SHRINK_K)
    }

    /** The setup this candidate is traded as: the matching setup with the best record, or NO_TRIGGER. */
    private val classifyCache = ConcurrentHashMap<String, Pair<Long, String?>>()

    fun classify(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): String? {
        val lane = canon(laneRaw)
        val ck = "${ts.mint}|$lane"
        classifyCache[ck]?.let { (at, v) -> if (nowMs - at in 0L..15_000L) return v }
        val v = classifyNow(ts, lane, nowMs)
        if (classifyCache.size > 4_000) classifyCache.entries.removeIf { nowMs - it.value.first > 15_000L }
        classifyCache[ck] = nowMs to v
        return v
    }

    /** V5.0.7924 — every setup that fired on the last classification (all are graded, not only the tag). */
    private val matchCache = ConcurrentHashMap<String, List<String>>()

    private fun classifyNow(ts: TokenState, lane: String, nowMs: Long): String? {
        // V5.0.7931 — a lane with no playbook (crypto universe, Markets) costs no feature build.
        if (!MENU.containsKey(lane)) return null
        val f7978 = features(ts, nowMs)
        // V5.0.7978 — a promoted specialist of this lane is a setup (graded like the rest).
        f7978.specialistEdge7978 = try { com.lifecyclebot.engine.truth.SpecialistMiner7972.peek7978(ts, lane, nowMs) } catch (_: Throwable) { false }
        val m = matches(lane, f7978) ?: return null
        if (matchCache.size > 4_000) matchCache.clear()
        matchCache["${ts.mint}|$lane"] = m.map { it.id }
        if (m.isEmpty()) return NO_TRIGGER
        ensureLoaded()
        return synchronized(this) { m.maxByOrNull { expected(lane, it.id) }?.id ?: NO_TRIGGER }
    }

    /** Pure: is a (lane, setup) record a proven loser? */
    fun provenLosing(st: CortexLedger7885.Stat, runnerLane: Boolean): Boolean {
        if (st.n < LOSING_N) return false
        val se = kotlin.math.sqrt(st.variance() / st.n)
        if (st.mean() + se >= LOSING_PCT) return false
        if (runnerLane && st.runnerRate() >= TAIL_RATE) return false
        return true
    }

    /**
     * V5.0.7937 — the lane's playbook score for this candidate: the best fired
     * setup's expected net return (learned record shrunk to its prior), on the
     * score scale (50 = break-even, +10 per expected point, +3 per extra setup
     * that also fired), or null when only NO_TRIGGER applies or the lane has no
     * playbook. Scoring reflects what the lane trades, not the generic V3 read.
     */
    fun playbookScore7937(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Double? = try {
        val lane = canon(laneRaw)
        val setup = classify(ts, lane, nowMs)
        if (setup == null || setup == NO_TRIGGER) null else {
            val also = matchCache["${ts.mint}|$lane"].orEmpty().count { it != setup }
            val exp = synchronized(this) { expected(lane, setup) }
            scoreOf7937(exp, also)
        }
    } catch (_: Throwable) { null }

    /** Pure: expected net % and confluence → score (0..100). */
    fun scoreOf7937(expectedPct: Double, extraSetups: Int): Double =
        (50.0 + 10.0 * expectedPct + 3.0 * extraSetups.coerceIn(0, 5)).coerceIn(0.0, 100.0)

    /** Pure: V5.0.7936 — does the lane have a mature NO_TRIGGER record to judge by? */
    fun noTriggerMeasured7936(st: CortexLedger7885.Stat?): Boolean = st != null && st.n >= NONE_PROOF_N

    /** Pure: has a lane's NO_TRIGGER record proven that trading without a setup pays? */
    fun noTriggerProvenPositive(st: CortexLedger7885.Stat?): Boolean {
        if (st == null || st.n < NONE_PROOF_N) return false
        val se = kotlin.math.sqrt(st.variance() / st.n)
        return st.mean() - se > NONE_PROOF_PCT
    }

    /** LiveEdgeGate7877.liveRefusal (LIVE only): the playbook's binding refusal, or null. */
    fun liveRefusal(ts: TokenState, laneRaw: String): String? {
        return try {
            val lane = canon(laneRaw)
            val setup = classify(ts, lane) ?: return null
            val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
            val why: String? = synchronized(this) {
                val st = stat(lane, setup)
                when {
                    // V5.0.7930 — a NO_TRIGGER record proven to lose is evidence, not a prior.
                    setup == NO_TRIGGER && st != null && provenLosing(st, runner) -> "PLAYBOOK_NO_TRIGGER_PROVEN_LOSING_7907_$lane"
                    // V5.0.7936 — an unmeasured NO_TRIGGER explores (it has to trade to
                    // learn); once its own record is mature it must have proven it pays.
                    // V5.0.7939 — a runner lane (MOONSHOT hunts 500%+) buys runner setups only:
                    // 5.0.7937 MOONSHOT bought NO_TRIGGER 442 of 514 times, 4h labels n29 -24%.
                    // V5.0.7975 — trade-one: a runner lane's unmeasured NO_TRIGGER explores like every
                    // lane (labels arrive within minutes); refused once 40 labels measure it short of proof.
                    setup == NO_TRIGGER && noTriggerMeasured7936(st) && !noTriggerProvenPositive(st) -> "PLAYBOOK_NO_TRIGGER_7907_$lane"
                    setup != NO_TRIGGER && st != null && provenLosing(st, runner) -> "PLAYBOOK_SETUP_PROVEN_LOSING_7907_${lane}_$setup"
                    // V5.0.7948 — the best setup that fired has a measured record whose shrunk
                    // expectancy is below zero (5.0.7947 SHITCOIN LAUNCH_CONTINUATION n6 -11.0%).
                    setup != NO_TRIGGER && st != null && expectedNegative7948(st, expected(lane, setup), runner) ->
                        "PLAYBOOK_SETUP_EXPECTED_NEGATIVE_7948_${lane}_$setup"
                    else -> null
                }
            }
            if (why != null) {
                refusals.computeIfAbsent(why.removeSuffix("_$lane").take(60)) { AtomicLong(0) }.incrementAndGet()
                try {
                    PipelineHealthCollector.labelInc("PLAYBOOK_7907_REFUSED_$lane")
                    if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("PLAYBOOK_7907", "$lane|$setup")) {
                        ForensicLogger.lifecycle("PLAYBOOK_7907_REFUSED", "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$lane setup=$setup why=$why menu=${menuIds(lane)}")
                    }
                } catch (_: Throwable) {}
            }
            why
        } catch (_: Throwable) { null }
    }

    // ── V5.0.7948 — prefer what the labels proved, refuse what they measured losing ──
    //
    // 5.0.7947 records: MOONSHOT LAUNCH_LADDER_PROVEN n3 +61.2%, SHITCOIN
    // LAUNCH_LADDER_PROVEN n4 +41.4%, PRE_IGNITION_BASE n6 +24.1%, while
    // LAUNCH_CONTINUATION n6 -11.0% still traded live: "proven losing" needs 30
    // labels, so a setup measured negative kept its live slot until then. The
    // classifier already tags the best-expected setup that fired; a candidate is
    // now refused live when even that one is expected (record shrunk to its prior
    // by n/(n+20)) to lose on [MEASURED_MIN_N_7948]+ labels. Runner-lane setups
    // with a 10% runner tail keep their shots. LiveEdgeGate7877 lets measured
    // evidence (a proven cell or cohort on more labels) outweigh it.
    private const val MEASURED_MIN_N_7948 = 5.0

    /** Pure: is a (lane, setup) record measured, with a shrunk expectancy below zero? */
    fun expectedNegative7948(st: CortexLedger7885.Stat, expectedPct: Double, runnerLane: Boolean): Boolean {
        if (st.n < MEASURED_MIN_N_7948 || !expectedPct.isFinite()) return false
        if (runnerLane && st.runnerRate() >= TAIL_RATE) return false
        return expectedPct < 0.0
    }

    /** Pure: a (lane, setup) record proven to pay: 30+ labels and mean minus one standard error above zero. */
    fun setupProvenPositive7948(st: CortexLedger7885.Stat?): Boolean {
        if (st == null || st.n < LOSING_N) return false
        val se = kotlin.math.sqrt(st.variance() / st.n)
        return st.mean() - se > 0.0
    }

    /** LiveEdgeGate7877: the number of labels behind this candidate's classified setup's record (0 = none). */
    fun classifiedSampleN7948(ts: TokenState, laneRaw: String): Int = try {
        val lane = canon(laneRaw)
        val setup = classify(ts, lane)
        if (setup == null) 0 else synchronized(this) { stat(lane, setup)?.n?.toInt() ?: 0 }
    } catch (_: Throwable) { 0 }

    // ── V5.0.7955 §GRADUATED_AUTHORITY for setup records ──
    //
    // A setup with a strong positive record waited for 30 labels before it could size
    // a trade, and nothing let it admit one. Its authority is now graduated like the
    // Cortex's (CortexScoreboard7885.graduatedFraction7955): from 15 labels, on the
    // edge over a [SETUP_SMALL_N_MARGIN_7955] margin at 1 SE (n < 25) / 0.75 SE (n >= 25),
    // reaching 1.0 at the old proof (30+, mean - SE > 0). Proven-losing is unchanged.
    private const val SETUP_SMALL_N_MARGIN_7955 = 2.0

    /** Pure: a (lane, setup) record's graduated authority in [0, 1]. */
    fun setupFraction7955(st: CortexLedger7885.Stat?): Double {
        if (st == null || st.n < 1.0) return 0.0
        val se = if (st.n > 1.0) kotlin.math.sqrt(st.variance() / st.n) else Double.POSITIVE_INFINITY
        return CortexScoreboard7885.graduatedFraction7955(st.n, st.mean(), se, 0.0, SETUP_SMALL_N_MARGIN_7955, LOSING_N, setupProvenPositive7948(st))
    }

    /** LiveEdgeGate7877: [graduated authority, label count] of this candidate's classified setup, or null (none / NO_TRIGGER). */
    fun classifiedAuthority7955(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): DoubleArray? = try {
        val lane = canon(laneRaw)
        val setup = classify(ts, lane, nowMs)
        if (setup == null || setup == NO_TRIGGER) null else synchronized(this) {
            val st = stat(lane, setup)
            if (st == null) null else doubleArrayOf(setupFraction7955(st), st.n)
        }
    } catch (_: Throwable) { null }

    /**
     * Cortex7885.convictionMult: [mean, variance, authority] (percent units) of this
     * candidate's classified setup when that record holds authority (V5.0.7955:
     * graduated, > 0; 1.0 = proven positive), else null.
     */
    fun provenSetupRecord7948(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): DoubleArray? = try {
        val lane = canon(laneRaw)
        val setup = classify(ts, lane, nowMs)
        if (setup == null || setup == NO_TRIGGER) null else synchronized(this) {
            val st = stat(lane, setup)
            val f = setupFraction7955(st)
            if (st != null && f > 0.0) doubleArrayOf(st.mean(), st.variance(), f) else null
        }
    } catch (_: Throwable) { null }

    /** V5.0.7962 — CostLedger7962: the classified setup's shrunk expected net % (null: none / NO_TRIGGER). */
    fun classifiedExpected7962(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Double? = try {
        val lane = canon(laneRaw)
        val setup = classify(ts, lane, nowMs)
        if (setup == null || setup == NO_TRIGGER) null else synchronized(this) { expected(lane, setup) }
    } catch (_: Throwable) { null }

    /** Cortex7885.capture: tag the decision with its setup for its forward label. */
    fun capture(ts: TokenState, laneRaw: String, labelLane: String, nowMs: Long) {
        ensureLoaded()
        val lane = canon(laneRaw)
        val setup = classify(ts, lane, nowMs) ?: return
        // V5.0.7925 — shed half, not all: clearing dropped every pending label at once.
        if (pending.size > 8_000) pending.keys.take(pending.size / 2).forEach { pending.remove(it) }
        val fired = matchCache["${ts.mint}|$lane"].orEmpty().filter { it != setup }
        pending["${ts.mint}|${labelLane.trim().uppercase()}"] = lane to (if (fired.isEmpty()) setup else setup + ";" + fired.joinToString(","))
        tagged.computeIfAbsent("$lane|$setup") { AtomicLong(0) }.incrementAndGet()
        for (id in fired) firedCount.computeIfAbsent("$lane|$id") { AtomicLong(0) }.incrementAndGet()
    }

    private val firedCount = ConcurrentHashMap<String, AtomicLong>()

    /** Cortex7885.onLabel (graded horizon only): learn (lane, setup) from the forward label. */
    fun onLabel(mint: String, labelLane: String, netPct: Double, grossPct: Double) {
        ensureLoaded()
        val (lane, tag) = pending.remove("$mint|${labelLane.trim().uppercase()}") ?: return
        if (!netPct.isFinite()) return
        // V5.0.7924 — the tagged setup and every other setup that fired are each graded.
        val graded = listOf(tag.substringBefore(';')) + tag.substringAfter(';', "").split(',').filter { it.isNotBlank() }
        synchronized(this) {
            for (setup in graded.distinct()) {
                books.getOrPut(lane) { Book() }.stats.getOrPut(setup) { CortexLedger7885.Stat() }
                    .add(netPct.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), grossPct.isFinite() && grossPct >= CortexLedger7885.RUNNER_GROSS_PCT)
            }
        }
        if (sincePersist.incrementAndGet() >= 25) { sincePersist.set(0); persist() }
    }

    /** Index of the setup in the lane menu (NO_TRIGGER = menu size), for the Cortex voter. */
    fun setupIndex(ts: TokenState, laneRaw: String, nowMs: Long): Double? {
        val lane = canon(laneRaw)
        val id = classify(ts, lane, nowMs) ?: return null
        // V5.0.7962 — the 7962 setups are appended AFTER NO_TRIGGER's bin, so every setup the
        // voter learned before (and NO_TRIGGER = base menu size) keeps its bin.
        // V5.0.7974 — RANGE_SUPPORT_SWING / SECOND_WAVE appended after them the same way.
        val added7962 = listOf("HL_RECLAIM", "EXPERT_ENTRY", "RANGE_SUPPORT_SWING", "SECOND_WAVE",
            // V5.0.7978 — appended the same way, so every earlier bin is unchanged.
            "SPECIALIST_EDGE", "FRESH_LAUNCH_MOMENTUM", "MICRO_CAP_IGNITION", "HOLDER_SURGE", "TURNOVER_SPIKE",
            "GRADUATION_APPROACH", "LIVESTREAM_LAUNCH", "COPYCAT_BETA", "COLOUR_SEQUENCE")
        val menu = menuIds(lane).filter { it !in added7962 }
        val i = menu.indexOf(id)
        val j = added7962.indexOf(id)
        return (if (i >= 0) i else if (j >= 0) menu.size + 1 + j else menu.size).toDouble()
    }

    private fun ensureLoaded() {
        if (loaded) return
        // V5.0.7930 — never latch "loaded" before the store opens: an early read came back
        // empty and the next save overwrote the real ledgers with it.
        if (!LearningPersistence.ready()) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            // V5.0.7920 — pending setup tags survive a restart (labels mature 60-240 min later).
            try {
                val p = org.json.JSONObject(LearningPersistence.load(PENDING_KEY_7920) ?: "{}")
                for (k in p.keys()) {
                    val v = p.optString(k)
                    if (!pending.containsKey(k) && v.contains('|')) pending[k] = v.substringBefore('|') to v.substringAfter('|')
                }
            } catch (_: Throwable) {}
            try {
                val o = org.json.JSONObject(LearningPersistence.load(BOOKS_KEY_7947) ?: return)
                for (lane in o.keys()) {
                    val j = o.optJSONObject(lane) ?: continue
                    val b = books.getOrPut(lane) { Book() }
                    for (k in j.keys()) b.stats[k] = CortexLedger7885.Stat().also { it.decode(j.optString(k)) }
                }
            } catch (_: Throwable) {}
        }
    }

    private const val PENDING_KEY_7920 = "LANE_PLAYBOOK_PENDING_7920"
    /** V5.0.7947 — setup records graded on the 5-minute, spike-credited read (old key "LANE_PLAYBOOK_7907" left untouched). */
    private const val BOOKS_KEY_7947 = "LANE_PLAYBOOK_7947"
    @Volatile private var lastPendingPersistMs = 0L

    /** Cortex7885.captureNow: save the pending setup tags at most every 2 minutes. */
    fun persistPendingMaybe(nowMs: Long) {
        // V5.0.7930 — restore before the first save, or the save erases what was pending.
        ensureLoaded()
        if (!loaded) return
        if (nowMs - lastPendingPersistMs < 120_000L) return
        lastPendingPersistMs = nowMs
        try {
            val o = org.json.JSONObject()
            pending.entries.take(3_000).forEach { (k, v) -> o.put(k, "${v.first}|${v.second}") }
            LearningPersistence.save(PENDING_KEY_7920, o.toString())
        } catch (_: Throwable) {}
    }

    /** V5.0.7930 — BotService.onDestroy: save now (graded state between periodic saves was lost on restart). */
    fun persistNow7930() {
        if (!loaded) return
        persist()
        lastPendingPersistMs = 0L
        persistPendingMaybe(System.currentTimeMillis())
    }

    private fun persist() {
        if (!loaded) return
        try {
            val json = synchronized(this) {
                org.json.JSONObject().also { o ->
                    books.forEach { (lane, b) -> o.put(lane, org.json.JSONObject().also { j -> b.stats.forEach { (k, v) -> j.put(k, v.encode()) } }) }
                }.toString()
            }
            LearningPersistence.save(BOOKS_KEY_7947, json)
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        ensureLoaded()
        return synchronized(this) {
            val lanes = MENU.keys.joinToString("\n") { lane ->
                val tags = tagged.entries.filter { it.key.startsWith("$lane|") }.sortedByDescending { it.value.get() }
                    .joinToString(",") { "${it.key.substringAfter('|')}=${it.value.get()}" }.ifBlank { "-" } +
                    " alsoFired{" + firedCount.entries.filter { it.key.startsWith("$lane|") }.sortedByDescending { it.value.get() }.take(8)
                        .joinToString(",") { "${it.key.substringAfter('|')}=${it.value.get()}" }.ifBlank { "-" } + "}"
                val rec = (menuIds(lane) + NO_TRIGGER).joinToString(" ") { id ->
                    val st = stat(lane, id)
                    // V5.0.7955 — a setup holding graduated authority shows it (auth=0.62).
                    val auth = setupFraction7955(st)
                    "$id[${if (st == null || st.n < 1.0) "prior ${"%+.1f".format(priorOf(lane, id))}" else "n${st.n.toInt()} ${"%+.1f".format(st.mean())}% exp ${"%+.1f".format(expected(lane, id))}"}${if (auth > 0.0) " auth=${"%.2f".format(auth)}" else ""}]"
                }
                "      $lane tagged{$tags} record: $rec"
            }
            "rule=live needs a lane setup (NO_TRIGGER refused unless proven positive) · proven-losing setups refused · measured setups expected to lose refused (7948) · setup auth>=0.5 admits over a smaller cell (7955) · paper never refused\n" +
                "      refusals: ${refusals.entries.sortedByDescending { it.value.get() }.take(10).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}\n" + lanes
        }
    }
}
