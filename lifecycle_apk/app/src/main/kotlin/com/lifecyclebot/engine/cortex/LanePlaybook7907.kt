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
    // V5.0.8087 — cohorts for coins that could not be classified (never pooled into NO_TRIGGER).
    const val DATA_INCOMPLETE_8087 = "DATA_INCOMPLETE"
    const val UNRANKABLE_8087 = "MATCHES_UNRANKABLE"
    const val CLASSIFIER_ERROR_8087 = "CLASSIFIER_ERROR"
    val UNCLASSIFIED_8087 = setOf(DATA_INCOMPLETE_8087, UNRANKABLE_8087, CLASSIFIER_ERROR_8087)
    private const val SHRINK_K = 20.0
    private const val NONE_PROOF_N = 40
    private const val NONE_PROOF_PCT = 1.0
    private const val LOSING_N = 30
    const val NEGATIVE_N_8094 = 12.0
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
        /** V5.0.8308 — the token's route truth says the bonding curve completed (migrated / graduated to an AMM pool). */
        val migrated8308: Boolean = false,
    ) {
        /** V5.0.8084 — provenance of the core inputs (bp / chg / shape / age / liq ...): OBSERVED, STALE, UNKNOWN, INVALID, DEFAULT_UNOBSERVED. */
        var obs8084: Map<String, Obs8084> = emptyMap()

        /** V5.0.8084 — SetupFeatureSnapshot version: every setup-dependent input (NaN-stable), so a change re-matches at once. */
        fun fingerprint8084(): Int {
            var h = 17
            fun d(v: Double) { h = 31 * h + (if (v.isNaN()) 0x7ff8 else java.lang.Double.hashCode(v)) }
            fun b(v: Boolean) { h = 31 * h + (if (v) 1 else 0) }
            fun t(v: String) { h = 31 * h + v.hashCode() }
            d(age); d(runup); d(pxPeak); d(dd); d(bp); d(liq); d(mcap); d(top); d(chg5m); d(chg1h); d(holderGrowth); d(volAccel)
            t(planSetup); t(launchPhase); b(crowdForming)
            d(tapeCrowd); d(tapeNetSol); d(tapeBuyersPerMin); d(tapeBuyShare); d(tapeLargest); d(tapeTop3); d(tapeDevSold); d(tapeFromPeak); d(tapeFromFirst)
            d(buyTx15s); d(buyTxPrev15s); d(repeatBuyers60s); t(oppSetup); d(oppPercentile); d(oppRs); d(oppVolAccel); d(oppTxAccel); d(oppLiqDelta)
            d(boost); d(socials); b(cto); d(devTokens); d(devRugRate); b(devKnownRugger); d(holders); d(insider); d(narrativeHeat); d(narrativeExhaustion)
            d(bundleLargest); d(lpLock); d(txVelocity); d(volatility); t(regime); b(launchLadderProven); b(expertEntry7962); b(structureHlReclaim)
            b(secondWave7974); d(holderVel7978); d(turnover7978); d(curve7978); b(live7978); b(beta7978); b(colourBuy7978); b(specialistEdge7978)
            b(migrated8308)
            return h
        }
    }

    // ── V5.0.8084 §SETUP_CLASSIFICATION — observation provenance and structured outcomes ──
    enum class Obs8084 { OBSERVED, STALE, UNKNOWN, INVALID, DEFAULT_UNOBSERVED }

    /**
     * MATCHED: setups fired and one was ranked. NO_MATCH: core inputs observed, nothing fired. DATA_INCOMPLETE: nothing
     * fired and a core input is unobserved/stale/invalid. MATCHES_UNRANKABLE: setups fired, none rankable. NO_PLAYBOOK:
     * the lane has no menu. CLASSIFIER_ERROR: nothing fired and a setup predicate threw. Only NO_MATCH is the historic
     * "no recognised setup" NO_TRIGGER cohort; the rest explain why a coin could not be classified.
     */
    enum class Outcome8084 { MATCHED, NO_MATCH, DATA_INCOMPLETE, MATCHES_UNRANKABLE, NO_PLAYBOOK, CLASSIFIER_ERROR }

    /** One immutable classification of one candidate version (features, matches, choice, reason) — cached atomically. */
    class Classification8084(
        val lane: String, val outcome: Outcome8084, val chosen: String?, val matched: List<String>,
        val missing: List<String>, val errors: List<String>, val featureVersion: Int, val atMs: Long, val reason: String,
    ) {
        /**
         * V5.0.8087 — the learning cohort of this classification. Until 8086 every non-matched outcome was tagged
         * NO_TRIGGER, so coins with unobserved candles / tape, unrankable matches and classifier throws were graded
         * into the NO_TRIGGER record. Each outcome is now its own cohort; only a genuine NO_MATCH is NO_TRIGGER.
         */
        val tag: String? get() = when (outcome) {
            Outcome8084.NO_PLAYBOOK -> null
            Outcome8084.MATCHED -> chosen
            Outcome8084.NO_MATCH -> NO_TRIGGER
            Outcome8084.DATA_INCOMPLETE -> DATA_INCOMPLETE_8087
            Outcome8084.MATCHES_UNRANKABLE -> UNRANKABLE_8087
            Outcome8084.CLASSIFIER_ERROR -> CLASSIFIER_ERROR_8087
        }
        /**
         * V5.0.8087 — the setup other components may act on: the chosen setup, NO_TRIGGER for a genuine no-match, and
         * null (nothing to act on, no economic prior) when the coin could not be classified.
         */
        val consumerTag8087: String? get() = when (outcome) {
            Outcome8084.MATCHED -> chosen
            Outcome8084.NO_MATCH -> NO_TRIGGER
            else -> null
        }
        val alternatives: List<String> get() = matched.filter { it != chosen }
        /** Only a genuine no-match is the NO_TRIGGER cohort a refusal or a NO_TRIGGER grade may read. */
        val noMatchCohort: Boolean get() = outcome == Outcome8084.NO_MATCH
    }

    internal const val OBS_FRESH_MS_8084 = 15L * 60_000L
    internal const val SHAPE_FRESH_MS_8084 = 20L * 60_000L

    /** Pure: buy pressure provenance. A stamped 50/50 is a real balanced tape; an unstamped 50/50 pair is the default. */
    fun bpObs8084(bp: Double, sp: Double, observedAtMs: Long, nowMs: Long): Obs8084 = when {
        !bp.isFinite() || bp < 0.0 || bp > 100.0 -> Obs8084.INVALID
        observedAtMs > 0L -> if (nowMs - observedAtMs <= OBS_FRESH_MS_8084) Obs8084.OBSERVED else Obs8084.STALE
        bp == 50.0 && sp == 50.0 -> Obs8084.DEFAULT_UNOBSERVED
        else -> Obs8084.OBSERVED   // an unstamped legacy writer's non-default reading
    }

    /** Pure: price-change provenance. A stamped 0.0 is a real flat read; an unstamped 0/0 is the default. */
    fun chgObs8084(chg5m: Double, chg1h: Double, observedAtMs: Long, nowMs: Long): Obs8084 = when {
        !chg5m.isFinite() || !chg1h.isFinite() -> Obs8084.INVALID
        observedAtMs > 0L -> if (nowMs - observedAtMs <= OBS_FRESH_MS_8084) Obs8084.OBSERVED else Obs8084.STALE
        chg5m == 0.0 && chg1h == 0.0 -> Obs8084.DEFAULT_UNOBSERVED
        else -> Obs8084.OBSERVED
    }

    /**
     * Pure: price-history (shape) provenance from the last three points: positive finite prices, strictly increasing
     * timestamps spanning 10 s+, no dimensional jump (>1000x between points), newest within 20 min and not in the future.
     */
    fun shapeObs8084(pts: List<com.lifecyclebot.data.Candle>, nowMs: Long): Obs8084 {
        if (pts.size < 3) return Obs8084.UNKNOWN
        // 8211: local history can contain repeated snapshots with the SAME timestamp and price.
        // Three genuine time-separated observations are required; exact repeats are not new data.
        // Conflicting prices at one timestamp remain INVALID rather than being silently reconciled.
        val seen = HashMap<Long, Double>(8)
        val distinct = ArrayList<com.lifecyclebot.data.Candle>(3)
        for (point in pts.takeLast(16).asReversed()) {
            if (!point.priceUsd.isFinite() || point.priceUsd <= 0.0 || point.ts <= 0L) return Obs8084.INVALID
            val prev = seen.putIfAbsent(point.ts, point.priceUsd)
            if (prev != null) {
                if (prev != point.priceUsd) return Obs8084.INVALID
                continue
            }
            distinct.add(point)
            if (distinct.size == 3) break
        }
        if (distinct.size < 3) return Obs8084.UNKNOWN
        val last3 = distinct.asReversed()
        for (i in last3.indices) {
            val c = last3[i]
            if (!c.priceUsd.isFinite() || c.priceUsd <= 0.0 || c.ts <= 0L) return Obs8084.INVALID
            if (i > 0) {
                val p = last3[i - 1]
                if (c.ts <= p.ts) return Obs8084.INVALID
                val r = c.priceUsd / p.priceUsd
                if (!r.isFinite() || r > 1_000.0 || r < 0.001) return Obs8084.INVALID
            }
        }
        if (last3.last().ts - last3.first().ts < 10_000L) return Obs8084.INVALID
        val age = nowMs - last3.last().ts
        if (age < -60_000L) return Obs8084.INVALID
        return if (age > SHAPE_FRESH_MS_8084) Obs8084.STALE else Obs8084.OBSERVED
    }

    /** The inputs a lane's setups most need; one unobserved means a no-fire is DATA_INCOMPLETE, not NO_MATCH. */
    internal val CORE_8084 = listOf("bp", "chg", "shape", "liq")

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
    // V5.0.8170: early native flow can be judged from observed launch prints
    // before 5-minute OHLCV exists. The inputs are native LaunchTape observations,
    // not inferred from unstamped default buy-pressure or fabricated candles.
    // As with every new playbook setup, this has NO special trading authority:
    // its edge must first prove positive after costs on forward labels.
    private val EARLY_BUYER_EXPANSION_8170 = Setup("EARLY_BUYER_EXPANSION_8170", FLOW) { f ->
        le(f.age, 3.0) && ge(f.liq, 3_000.0) && ge(f.tapeCrowd, 5.0) &&
            ge(f.tapeBuyersPerMin, 4.0) && ge(f.tapeBuyShare, 65.0) &&
            ge(f.tapeNetSol, 0.5) && le(f.tapeLargest, 35.0) && le(f.tapeDevSold, 0.0)
    }
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
        le(f.age, 60.0) && ge(f.liq, 15_000.0) && ge(f.chg5m, 0.0) && ge(f.bp, 55.0) && (f.mcap / f.liq).let { it.isFinite() && it <= 6.0 } &&
            postMigrationProven8308(f)
    }

    /**
     * V5.0.8308 — POST_MIGRATION_HOLD needs proof that the migration happened and that the pool can take our sell.
     * 5.0.8306 live: 2bNrYy (MOONSHOT) was bought on this setup at a $23k cap right after migrating and went -85.7 % in
     * 95 s. The setup read only age / liquidity / 5-minute change / buy pressure / cap-to-liquidity: it never asked whether
     * the curve had completed, whether the coin was already far under its peak (a migration completes near the top of the
     * curve, so a $23k cap minutes later is a collapse in progress), who holds the supply, or whether the dev was selling.
     * Every check reads an OBSERVED value; unknown fails closed (no proof, no setup). Nothing here loosens another rule.
     *  - migrated: route truth says the bonding curve completed (TokenMap.migratedOrGraduated);
     *  - not collapsing: price within 30 % of its observed peak (pxPeak >= 0.70) and drawdown known <= 30 %;
     *  - holder concentration: largest holder known and <= 30 % (the AMM pool's own ~21 % share passes);
     *  - sell-side safety: the launch tape shows the dev has NOT sold, no single buyer above 35 % of the crowd, and the
     *    creator is not a known rugger (rug rate, when known, under 30 %).
     */
    fun postMigrationProven8308(f: F): Boolean =
        f.migrated8308 &&
            ge(f.pxPeak, 0.70) && le(f.dd, 30.0) &&
            le(f.top, 30.0) &&
            le(f.tapeDevSold, 0.0) && le(f.tapeLargest, 35.0) &&
            !f.devKnownRugger && leOrUnknown(f.devRugRate, 0.30)
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

    // ── V5.0.8061 — more setups. Owner: "we need more setups if so many tokens miss them". 5.0.8059: SHITCOIN tagged
    // NO_TRIGGER on most coins and the runners it refused (POKEMON +1,077%, Gemini +708%) fired no named setup. Two
    // fixes: (1) the owner's own chart reads as setups that need only price-history facts (always known): buyers in
    // control at the highs, a base breaking out, a 5-minute ignition, the first pullback after a run, a dip inside an
    // hourly uptrend, quiet accumulation, a fresh high on a push; (2) each meme lane now reads the library setups its
    // menu skipped (MOONSHOT never read ACCELERATING_TAPE, SHITCOIN's best record). Every one starts at a flow prior and
    // is graded per lane; live money still waits for a proven record (8059/8060).
    private val BUYERS_DOMINATING = Setup("BUYERS_DOMINATING", FLOW) { f -> ge(f.bp, 65.0) && ge(f.chg5m, 0.0) && ge(f.pxPeak, 0.90) && leOrUnknown(f.runup, 200.0) }
    private val BASE_BREAKOUT_FLOW = Setup("BASE_BREAKOUT_FLOW", FLOW) { f -> le(f.runup, 40.0) && ge(f.pxPeak, 0.98) && ge(f.chg5m, 3.0) && ge(f.bp, 55.0) }
    private val IGNITION_5M = Setup("IGNITION_5M", FLOW) { f -> ge(f.chg5m, 8.0) && ge(f.bp, 60.0) && leOrUnknown(f.runup, 60.0) && le(f.age, 240.0) }
    private val FIRST_PULLBACK_AFTER_RUN = Setup("FIRST_PULLBACK_AFTER_RUN", FLOW) { f -> ge(f.runup, 50.0) && ok(f.dd, 10.0, 25.0) && ge(f.bp, 50.0) && ge(f.chg5m, 0.0) }
    private val DIP_IN_UPTREND = Setup("DIP_IN_UPTREND", FLOW) { f -> ge(f.chg1h, 20.0) && ok(f.dd, 10.0, 30.0) && ge(f.chg5m, 0.0) && ge(f.bp, 50.0) }
    private val QUIET_ACCUMULATION = Setup("QUIET_ACCUMULATION", FLOW) { f -> le(f.runup, 20.0) && le(f.dd, 10.0) && ge(f.bp, 58.0) && ok(f.chg5m, -2.0, 3.0) }
    private val NEW_HIGH_PUSH = Setup("NEW_HIGH_PUSH", FLOW) { f -> ge(f.pxPeak, 0.99) && ok(f.runup, 40.0, 400.0) && ge(f.chg5m, 2.0) && ge(f.bp, 58.0) }
    private val SHAPE_8061 = listOf(BUYERS_DOMINATING, BASE_BREAKOUT_FLOW, IGNITION_5M, FIRST_PULLBACK_AFTER_RUN, DIP_IN_UPTREND, QUIET_ACCUMULATION, NEW_HIGH_PUSH)
    /** V5.0.8061 — library setups each meme lane's menu skipped (appended; never duplicates its own menu). */
    private val CROSS_8061: Map<String, List<Setup>> = mapOf(
        "SHITCOIN" to listOf(BREAKOUT_RUNNER, RS_LEADER, VOLUME_IGNITION, MOMENTUM_PULLBACK_5M, HIGHER_LOW_CONTINUATION, HIGHER_LOW_PULLBACK,
            MICRO_FLAG, FLAG_CONTINUATION, POST_EVENT_RECLAIM, SECOND_LEG, TX_VELOCITY_BREAK, VOLUME_CONTINUATION, HOLDER_EXPANSION,
            OPP_BREAKOUT_EXPANSION, OPP_RS_LEADER),
        "MOONSHOT" to listOf(ACCELERATING_TAPE, PRE_IGNITION_BASE, FIRST_PULLBACK, MOMENTUM_PULLBACK_5M, HIGHER_LOW_PULLBACK, FLAG_CONTINUATION,
            MICRO_FLAG, TX_VELOCITY_BREAK, FIRST_DIP_BOUGHT, BROAD_DISTRIBUTION, DEV_HOLDS_CROWD_BUYS),
        "EXPRESS" to listOf(PRE_IGNITION_BASE, BREAKOUT_RUNNER, RS_LEADER, FIRST_PULLBACK, LAUNCH_CONTINUATION, NET_INFLOW_SURGE, FIRST_DIP_BOUGHT),
        "MANIPULATED" to listOf(VOLUME_IGNITION, BREAKOUT_RUNNER, MOMENTUM_PULLBACK_5M, RS_LEADER, FAST_CROWD, NET_INFLOW_SURGE),
        "PROJECT_SNIPER" to listOf(PRE_IGNITION_BASE, ACCELERATING_TAPE, FAST_CROWD, FIRST_DIP_BOUGHT),
    )

    /** V5.0.8061 — the setups 8061 appends to [lane]'s menu (cross-lane + shapes), never one its menu already has. */
    private fun extras8061(lane: String, existing: List<Setup>): List<Setup> {
        val have = existing.map { it.id }.toHashSet()
        return ((CROSS_8061[lane] ?: emptyList()) + SHAPE_8061 +
            if (lane in MEME_LANES_7978) listOf(EARLY_BUYER_EXPANSION_8170) else emptyList())
            .filter { have.add(it.id) }
    }

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
        val upTo7978 = (if (lane in EXPERT_LANES_7962) base + EXPERT_ENTRY_7962 else base) +
            (if (lane in MEME_LANES_7978) MEME_EXTRAS_7978 else OTHER_EXTRAS_7978)   // V5.0.7978
        upTo7978 + extras8061(lane, upTo7978)   // V5.0.8061
    }

    /** Pure: the setups of [lane]'s menu that [f] fires (empty = no trigger; unknown lane = null). */
    fun matches(lane: String, f: F): List<Setup>? = menuOf7962(lane)?.filter { s ->
        try { s.fires(f) } catch (_: Throwable) {
            try { PipelineHealthCollector.labelInc("SETUP_CLASSIFIER_ERROR_8084_${s.id}") } catch (_: Throwable) {}
            false
        }
    }

    /** V5.0.8084 — every setup of [lane]'s menu evaluated: (fired ids, ids whose predicate threw); null = no playbook. */
    internal fun matchDetailed8084(lane: String, f: F): Pair<List<String>, List<String>>? {
        val menu = menuOf7962(lane) ?: return null
        val fired = ArrayList<String>(4); val errs = ArrayList<String>(0)
        for (s in menu) try { if (s.fires(f)) fired.add(s.id) } catch (_: Throwable) { errs.add(s.id) }
        return fired to errs
    }

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
        // V5.0.8084 — validity from observation metadata, not value guesses: a stamped 50/50 tape and a stamped 0% move
        // are real readings; an unstamped default is not; three points must be time-ordered, spaced, sane and fresh.
        val bpRaw = s?.buyPressurePct ?: ts.lastBuyPressurePct
        val shapeObs = try { shapeObs8084(ts.history.toList(), nowMs) } catch (_: Throwable) { Obs8084.UNKNOWN }
        val bpObs = bpObs8084(bpRaw, ts.lastSellPressurePct, ts.buyPressureObservedAtMs8084, nowMs)
        val chgObs = chgObs8084(ts.lastPriceChange5m, ts.lastPriceChange1h, ts.priceChangeObservedAtMs8084, nowMs)
        // V5.0.8094 entry repair — the 8094 tick-shape fallback is withdrawn: a handful of mixed-source ticks
        // "classified" fresh tokens the minute history could not, and LIVE_PROVEN_SETUP_ONLY then admitted them
        // (live WR 15-35%). Unobserved shape / change stays unobserved (DATA_INCOMPLETE), as through 8093.
        val shapeKnown = shapeObs == Obs8084.OBSERVED
        val bpKnown = bpObs == Obs8084.OBSERVED
        val chgKnown = chgObs == Obs8084.OBSERVED
        // V5.0.8100 — an input its usual source (DexScreener row, provider candles) left unobserved is read from the
        // coin's own EXECUTED pump trades (TradeTape8100, one source, one-minute bars). Never overrides a reading.
        val tape8100 = try { com.lifecyclebot.engine.truth.TradeTape8100.read8100(ts.mint, nowMs) } catch (_: Throwable) { null }
        val tapeShape8100 = if (!shapeKnown) tape8100?.shape else null
        val tapeBp8100 = if (!bpKnown) tape8100?.bp else null
        val tapeChg8100 = if (!chgKnown) tape8100?.chg else null
        try {
            if (tapeShape8100 != null) com.lifecyclebot.engine.truth.TradeTape8100.noteFilled8100("shape")
            if (tapeBp8100 != null) com.lifecyclebot.engine.truth.TradeTape8100.noteFilled8100("bp")
            if (tapeChg8100 != null) com.lifecyclebot.engine.truth.TradeTape8100.noteFilled8100("chg")
        } catch (_: Throwable) {}
        val liqObs = if (pos(s?.liquidityUsd).isFinite()) Obs8084.OBSERVED else Obs8084.UNKNOWN
        // V5.0.8169 — an input the token's own reading and the trade tape both left unobserved is read from any other
        // healthy source that states it (a fresh DexScreener pair, the market sweep row); SetupFeatureResolver8169 names
        // the source, or every source's reason when none can. Never overrides a reading, never a default.
        val swaps8169 = (flow != null && flow.buyTx60s + flow.sellTx60s > 0) || tape8100 != null
        val fill8169 = try {
            com.lifecyclebot.engine.truth.SetupFeatureResolver8169.fillFor8169(ts.mint, nowMs, bpObs.name, if (bpKnown) nn(bpRaw).takeIf { it.isFinite() } else null, tapeBp8100,
                chgObs.name, if (chgKnown) (ts.lastPriceChange5m to ts.lastPriceChange1h) else null, tapeChg8100, shapeObs.name, shapeKnown, tapeShape8100 != null,
                pos(s?.liquidityUsd).takeIf { it.isFinite() }, tape8100 != null, tape != null, nn(s?.ageMin), swaps8169)
        } catch (_: Throwable) { null }
        val altBp8169 = if (!bpKnown && tapeBp8100 == null) fill8169?.bp?.value else null
        val altChg8169 = if (!chgKnown && tapeChg8100 == null) fill8169?.chg?.value else null
        val altLiq8169 = if (liqObs != Obs8084.OBSERVED) fill8169?.liq?.value else null
        // V5.0.8144 — name each missing input separately (per-feature counters), not one DATA_INCOMPLETE.
        try { com.lifecyclebot.engine.ProofDataFeatureCoverage8144.observe8144(ts.mint, nowMs, bp = bpKnown || tapeBp8100 != null || altBp8169 != null, chg = chgKnown || tapeChg8100 != null || altChg8169 != null, launchTape = tape != null, structure = shapeKnown || tapeShape8100 != null, context = !plan.isNullOrBlank() || !phase.isNullOrBlank() || opp != null, liquidity = liqObs == Obs8084.OBSERVED || altLiq8169 != null, birth = nn(s?.ageMin).isFinite(), swaps = swaps8169) } catch (_: Throwable) {}
        return F(
            age = nn(s?.ageMin),
            runup = if (shapeKnown) nn(s?.runupFromLocalLowPct) else tapeShape8100?.runupPct ?: Double.NaN,
            pxPeak = if (shapeKnown) pos(s?.currentVsPeak) else tapeShape8100?.vsPeak ?: Double.NaN,
            dd = if (shapeKnown) nn(s?.drawdownFromPeakPct) else tapeShape8100?.ddPct ?: Double.NaN,
            bp = if (bpKnown) nn(bpRaw) else tapeBp8100 ?: altBp8169 ?: Double.NaN, liq = altLiq8169 ?: pos(s?.liquidityUsd), mcap = pos(s?.marketCapUsd),
            top = nn(s?.topHolderPct),
            chg5m = if (chgKnown) ts.lastPriceChange5m.takeIf { it.isFinite() } ?: Double.NaN else tapeChg8100?.first ?: altChg8169?.first ?: Double.NaN,
            chg1h = if (chgKnown) ts.lastPriceChange1h.takeIf { it.isFinite() } ?: Double.NaN else tapeChg8100?.second ?: altChg8169?.second ?: Double.NaN,
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
            migrated8308 = try { ts.tokenMap.migratedOrGraduated } catch (_: Throwable) { false },
        ).also { f ->
            f.obs8084 = mapOf("bp" to (if (tapeBp8100 != null || altBp8169 != null) Obs8084.OBSERVED else bpObs),
                "chg" to (if (tapeChg8100 != null || altChg8169 != null) Obs8084.OBSERVED else chgObs),
                "shape" to (if (tapeShape8100 != null) Obs8084.OBSERVED else shapeObs),
                "liq" to (if (altLiq8169 != null) Obs8084.OBSERVED else liqObs),
                "age" to (if (f.age.isFinite()) Obs8084.OBSERVED else Obs8084.UNKNOWN),
                "mcap" to (if (f.mcap.isFinite()) Obs8084.OBSERVED else Obs8084.UNKNOWN),
                "tape" to (if (tape != null) Obs8084.OBSERVED else Obs8084.UNKNOWN),
                "flow" to (if (flow != null) Obs8084.OBSERVED else Obs8084.UNKNOWN),
                "holders" to (if (f.holders.isFinite() || f.holderGrowth.isFinite()) Obs8084.OBSERVED else Obs8084.UNKNOWN),
                "opp" to (if (opp != null) Obs8084.OBSERVED else Obs8084.UNKNOWN),
                "volAccel" to (if (f.volAccel.isFinite()) Obs8084.OBSERVED else Obs8084.UNKNOWN))
        }
    }

    // ── learning ──

    private class Book { val stats = HashMap<String, CortexLedger7885.Stat>() }
    /** V5.0.8094 — per lane: "setup|timingBand" -> the same forward grade, split by WHEN in the move the coin was taken. */
    private val timingBooks8094 = HashMap<String, HashMap<String, CortexLedger7885.Stat>>()
    /** V5.0.8094 — mint|labelLane -> the timing band at the decision (session-only; a restart grades without a band). */
    private val pendingTiming8094 = ConcurrentHashMap<String, String>()

    private val books = HashMap<String, Book>()          // lane
    private val pending = ConcurrentHashMap<String, Pair<String, String>>()   // mint|labelLane -> (lane, setup)
    /** V5.0.8309 — census: pending labels / timing stamps (~220 B), lane books (~200 B a Stat), timing books. */
    fun estBytes8309(): Long = (pending.size + pendingTiming8094.size).toLong() * 220L +
        (try { books.values.toList().sumOf { it.stats.size } + timingBooks8094.values.toList().sumOf { it.size } } catch (_: Throwable) { 0 }).toLong() * 200L
    fun memCount8309(): Int = pending.size + pendingTiming8094.size + (try { books.size + timingBooks8094.size } catch (_: Throwable) { 0 })
    private val refusals = ConcurrentHashMap<String, AtomicLong>()
    private val tagged = ConcurrentHashMap<String, AtomicLong>()
    @Volatile private var loaded = false
    private val sincePersist = AtomicLong(0)

    /**
     * V5.0.8187 §EXECUTABLE_LABELS — every authority read of a setup record (proof, expected value, graduated authority,
     * Kelly share, ranking) reads it executable: the lane's label-to-bank gap off its mean, and blended toward the setup's own
     * realised closes where they contradict it (Cortex7885.executableCopy8187). [statRaw8187] is the stored record (display).
     */
    private fun stat(lane: String, setup: String): CortexLedger7885.Stat? {
        val raw = statRaw8187(lane, setup) ?: return null
        val rr = try { Cortex7885.setupRealised8187(lane, setup) } catch (_: Throwable) { null }
        // V5.0.8295 — losing realised closes are applied by LaneRealisedGovernor8295 (from the first close, with the lane's own
        // bank); the 8187 blend keeps only its other direction here so a losing close is never counted twice.
        val rrN = rr?.getOrNull(0) ?: 0.0; val rrM = rr?.getOrNull(1) ?: Double.NaN
        val exec = try {
            Cortex7885.executableCopy8187(raw, Cortex7885.executableOffset8187(lane), if (rrM.isFinite() && rrM > 0.0) rrN else 0.0, rrM)
        } catch (_: Throwable) { raw }
        return try { LaneRealisedGovernor8295.governedCopy8295(exec, rr, laneRealised8295(lane)) } catch (_: Throwable) { exec }
    }

    /** V5.0.8295 — the lane's live realised record [closes, mean %] (account mode), cached; null with none. */
    private fun laneRealised8295(lane: String): DoubleArray? =
        LaneRealisedGovernor8295.laneRealised8295(lane, System.currentTimeMillis()) { l -> Cortex7885.laneRealised8295(l) }

    /** V5.0.8295 — a runner lane's tail exemption holds only while the lane's realised closes are not losing. */
    private fun runnerTail8295(lane: String): Boolean =
        (try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }) &&
            !LaneRealisedGovernor8295.tailRefuted8295(try { laneRealised8295(lane) } catch (_: Throwable) { null })

    private fun statRaw8187(lane: String, setup: String): CortexLedger7885.Stat? {
        val local = books[lane]?.stats?.get(setup)?.takeIf { it.n == 0.0 ||
            LaneEvidence8077.coherent(it.n, it.sum, it.sumSq, it.wins, it.runners) }
        // V5.0.8000 — the rest of the hive's grades of this setup ride on the local book.
        val hive = try { com.lifecyclebot.engine.truth.HiveEdge8000.net8000("PB|$lane|$setup") } catch (_: Throwable) { null } ?: return local
        if (!CortexLedger7885.validHiveBin8076(hive)) return local
        val c = CortexLedger7885.labelStat8187()  // V5.0.8187 — the merged record reads through the same cap
        if (local != null) { c.n = local.n; c.sum = local.sum; c.sumSq = local.sumSq; c.runners = local.runners; c.wins = local.wins }
        // V5.0.8291 — the hive's grades ride on the local record at most as [hiveWeight8291] labels (mean, spread, win and runner
        // rates kept). They arrived as up to 1,000 labels (HiveEdge8000.capWeight8014) graded by other installs on other builds'
        // label definitions, so a setup with a handful of local grades read n824 auth=1.00 on foreign data.
        val k = hiveScale8291(local?.n ?: 0.0, hive[0])
        c.n += hive[0] * k; c.sum += hive[1] * k; c.sumSq += hive[2] * k; c.wins += hive[3] * k; c.runners += hive[4] * k
        return c
    }

    // ── V5.0.8291 §HIVE_NEVER_OUTWEIGHS_LOCAL ──
    /** Below this many local grades the hive may lend at most this many labels: under GRAD_MIN_N_7955, so never authority alone. */
    private const val HIVE_FLOOR_8291 = 10.0
    /** The hive never lends more than this many labels to one setup record. */
    private const val HIVE_MAX_8291 = 60.0

    /** Pure (V5.0.8291): how many labels the hive may lend a record holding [localN] local grades: clamp(localN, 10, 60). */
    fun hiveWeight8291(localN: Double): Double = (if (localN.isFinite()) localN else 0.0).coerceIn(HIVE_FLOOR_8291, HIVE_MAX_8291)

    /** Pure (V5.0.8291): the factor that scales [hiveN] network labels down to [hiveWeight8291] (1 when already under it). */
    fun hiveScale8291(localN: Double, hiveN: Double): Double =
        if (!(hiveN > 0.0) || !hiveN.isFinite()) 0.0 else minOf(1.0, hiveWeight8291(localN) / hiveN)

    /** Caller holds the lock. " local=n hive=n" on the screen when the hive lends labels to this record. */
    private fun hiveNote8291(lane: String, setup: String): String {
        val hive = try { com.lifecyclebot.engine.truth.HiveEdge8000.net8000("PB|$lane|$setup") } catch (_: Throwable) { null } ?: return ""
        if (!CortexLedger7885.validHiveBin8076(hive)) return ""
        val localN = books[lane]?.stats?.get(setup)?.n ?: 0.0
        return " local=${localN.toInt()} hive=${(hive[0] * hiveScale8291(localN, hive[0])).toInt()}/${hive[0].toInt()}"
    }

    /** V5.0.8000 — HiveEdge8000: this instance's setup books ([n, sum, sumSq, wins, runners, 0]). */
    fun hiveSnapshot8000(): Map<String, DoubleArray> = synchronized(this) {
        val out = HashMap<String, DoubleArray>()
        for ((lane, b) in books) for ((setup, s) in b.stats) if (s.n >= 3.0) out["$lane|$setup"] = doubleArrayOf(s.n, s.sum, s.sumSq, s.wins, s.runners, 0.0)
        out
    }

    private fun priorOf(lane: String, setup: String): Double =
        if (setup == NO_TRIGGER) -1.0 else if (setup in UNCLASSIFIED_8087) 0.0 else menuOf7962(lane)?.firstOrNull { it.id == setup }?.prior ?: 0.0

    /** Shrunk expected net % of (lane, setup). Caller holds the lock. */
    private fun expected(lane: String, setup: String): Double {
        val st = stat(lane, setup)
        val n = st?.n ?: 0.0
        return ((st?.readSum8187() ?: 0.0) + SHRINK_K * priorOf(lane, setup)) / (n + SHRINK_K)  // V5.0.8187 — robust sum
    }

    /** Caller holds the lock. Unmeasured setups retain their original cheat-sheet prior. */
    private fun setupEvidence8077(lane: String, id: String): LaneEvidence8077.Setup {
        val st = stat(lane, id)
        return LaneEvidence8077.Setup(id, expected(lane, id), st?.n ?: 0.0, st?.mean() ?: 0.0, st?.variance() ?: 0.0)
    }
    private val bookRevision8077 = AtomicLong(0)
    fun selectionRevision8198(): Long = bookRevision8077.get()

    /**
     * V5.0.8084 §ONE_CACHE. The 8077 cache validated price/mcap/liquidity only, for 15 s, while a separate matchCache
     * held the fired list and could disagree with the chosen tag. Now one entry holds the immutable classification;
     * it is reused while the token-local input key is unchanged (price, bp, chg, history, holders, momentum, exits)
     * and, for the external peeks (tape, flow, opportunity, specialist), for at most [EXTERNAL_TTL_MS_8084]; after that
     * the features are rebuilt and an unchanged fingerprint keeps the classification. A book revision re-ranks the
     * same matches without a feature rebuild. Calls in one decision (same nowMs) see one classification.
     */
    private class Cached8084(val inputKey: Long, val revision: Long, val featuresAt: Long, val pinnedAt: Long, val c: Classification8084) {
        /** V5.0.8283 — the shared feature revision this entry was built / pinned at (report: invalidations after evidence). */
        var featureRev8283: Long = 0L
    }
    private val classifyCache = ConcurrentHashMap<String, Cached8084>()
    /** V5.0.8309 — census: classification cache (~600 B an entry) and per-mint input request stamps (~140 B). */
    fun cacheBytes8309(): Long = classifyCache.size * 600L + inputRequestedAt8085.size * 140L
    internal const val EXTERNAL_TTL_MS_8084 = 5_000L

    /** Pure-ish: the token-local setup inputs, hashed (no provider call, no feature build). */
    internal fun inputKey8084(ts: TokenState): Long {
        var h = 1125899906842597L
        fun l(v: Long) { h = 31 * h + v }
        fun d(v: Double) { h = 31 * h + (if (v.isNaN()) 0x7ff8L else java.lang.Double.doubleToLongBits(v)) }
        l(ts.lastPriceUpdate); d(ts.lastPrice); d(ts.lastMcap); d(ts.lastLiquidityUsd)
        d(ts.lastBuyPressurePct); d(ts.lastSellPressurePct); l(ts.buyPressureObservedAtMs8084)
        d(ts.lastPriceChange5m); d(ts.lastPriceChange1h); l(ts.priceChangeObservedAtMs8084)
        val last = try { ts.history.lastOrNull() } catch (_: Throwable) { null }
        l(ts.history.size.toLong()); l(last?.ts ?: 0L); l((last?.holderCount ?: 0).toLong())
        d(ts.holderGrowthRate); l(if (ts.holderDataResolved) 1L else 0L); l(ts.lastExitTs); l(if (ts.position.isOpen) 1L else 0L)
        d(try { com.lifecyclebot.engine.MomentumPredictorAI.getMomentum(ts.mint)?.volumeAccelerationScore } catch (_: Throwable) { null } ?: Double.NaN)
        // V5.0.8283 — the shared provider-evidence revision (pair flow, sweep, tape, candles, liquidity): new evidence is a new key.
        l(featureRevision8283(ts.mint))
        return h
    }

    /** V5.0.8283 — [mint]'s shared feature revision (FeatureRevision8283); 0 when none or unreadable. */
    private fun featureRevision8283(mint: String): Long =
        try { com.lifecyclebot.engine.truth.FeatureRevision8283.revision8283(mint) } catch (_: Throwable) { 0L }

    /** The setup consumers act on (consumerTag8087): null when the coin could not be classified this tick. */
    fun classify(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): String? =
        classify8084(ts, laneRaw, nowMs)?.consumerTag8087

    /** V5.0.8085 — set by DataOrchestrator.start(): asynchronous re-fetch of missing setup inputs for one mint. */
    @Volatile var inputRefresher8085: ((mint: String, symbol: String, missing: List<String>) -> Unit)? = null
    private val inputRequestedAt8085 = ConcurrentHashMap<String, Long>()
    private const val INPUT_REFRESH_GAP_MS_8085 = 30_000L

    /** Pure throttle: the same feature waits 30 s, but other missing features must not disappear behind it. */
    fun inputRefreshDue8085(lastMs: Long?, nowMs: Long): Boolean = lastMs == null || nowMs - lastMs >= INPUT_REFRESH_GAP_MS_8085

    /** 8196: one mint can need different bp / tape / shape / price inputs for independent qualified specialists. */
    internal fun dueMissingInputs8196(
        missing: List<String>, lastAt: (String) -> Long?, nowMs: Long,
    ): List<String> = missing.distinctBy { it.substringBefore(':').trim().lowercase() }
        .filter { raw ->
            val feature = raw.substringBefore(':').trim().lowercase()
            feature.isNotBlank() && inputRefreshDue8085(lastAt(feature), nowMs)
        }

    private fun requestInputs8085(mint: String, symbol: String, missing: List<String>, nowMs: Long) {
        val r = inputRefresher8085 ?: run {
            PipelineHealthCollector.labelInc("SETUP_INPUT_REFRESH_NO_RECEIVER_8168")
            return
        }
        if (mint.isBlank() || missing.isEmpty()) return
        // A mint-wide throttle used to ignore the second specialist's different missing
        // inputs for 30 s. Per mint + feature keys preserve the same provider budget
        // without letting a shape request silently swallow a tape / pair / liquidity read.
        val due8196 = synchronized(inputRequestedAt8085) {
            val selected = dueMissingInputs8196(missing, { feature -> inputRequestedAt8085["$mint|$feature"] }, nowMs)
            selected.forEach { raw -> inputRequestedAt8085["$mint|${raw.substringBefore(':').trim().lowercase()}"] = nowMs }
            if (inputRequestedAt8085.size > 4_000)
                inputRequestedAt8085.entries.removeIf { nowMs - it.value > 600_000L }
            selected
        }
        if (due8196.isEmpty()) return
        try { PipelineHealthCollector.labelInc("SETUP_INPUT_REFRESH_REQUESTED_8085") } catch (_: Throwable) {}
        try { r(mint, symbol, due8196) } catch (_: Throwable) {
            PipelineHealthCollector.labelInc("SETUP_INPUT_REFRESH_FAILED_8168")
            // An upstream throw did not start the missing-feature fetch. Retry next
            // cycle instead of caching a failed request as if it had been delivered.
            synchronized(inputRequestedAt8085) {
                due8196.forEach { raw -> inputRequestedAt8085.remove("$mint|${raw.substringBefore(':').trim().lowercase()}", nowMs) }
            }
        }
    }

    /** V5.0.8084 — the structured classification of this candidate version (null only on an internal failure). */
    fun classify8084(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Classification8084? {
        val lane = canon(laneRaw)
        if (!MENU.containsKey(lane)) return Classification8084(lane, Outcome8084.NO_PLAYBOOK, null, emptyList(), emptyList(), emptyList(), 0, nowMs, "no playbook for $lane")
        val ck = "${ts.mint}|$lane"
        val revision = bookRevision8077.get()
        val key = try { inputKey8084(ts) } catch (_: Throwable) { 0L }
        val frev8283 = featureRevision8283(ts.mint)
        val cached = classifyCache[ck]
        noteEvidenceInvalidation8283(cached, key, frev8283, nowMs)
        if (cached != null) {
            if (cached.pinnedAt == nowMs && cached.inputKey == key && cached.revision == revision) return cached.c
            val fresh = cached.inputKey == key && nowMs - cached.featuresAt in 0L..EXTERNAL_TTL_MS_8084
            if (fresh) {
                val c = if (cached.revision == revision) cached.c else rerank8084(cached.c, nowMs)
                classifyCache[ck] = Cached8084(key, revision, cached.featuresAt, nowMs, c).also { it.featureRev8283 = frev8283 }
                if (c.outcome == Outcome8084.DATA_INCOMPLETE) requestInputs8085(ts.mint, ts.symbol, c.missing, nowMs)
                com.lifecyclebot.engine.BotImprovementReport8168.noteSetup(ts.mint, lane, c.outcome.name, c.missing, c.reason, nowMs)
                return c
            }
        }
        val f = try { features(ts, nowMs) } catch (_: Throwable) {
            val c = Classification8084(lane, Outcome8084.CLASSIFIER_ERROR, null, emptyList(), emptyList(), listOf("features"), 0, nowMs, "feature build threw")
            count8084(c); return c
        }
        f.specialistEdge7978 = try { com.lifecyclebot.engine.truth.SpecialistMiner7972.peek7978(ts, lane, nowMs) } catch (_: Throwable) { false }
        val fp = f.fingerprint8084()
        val c = if (cached != null && cached.c.featureVersion == fp)
            (if (cached.revision == revision) cached.c else rerank8084(cached.c, nowMs))
        else {
            if (cached != null) try { PipelineHealthCollector.labelInc("SETUP_CACHE_STALE_INVALIDATED_8084") } catch (_: Throwable) {}
            evaluate8084(lane, f, fp, nowMs).also { count8084(it) }
        }
        // V5.0.8085 — a DATA_INCOMPLETE classification asks the data layer to re-fetch the missing inputs now (async,
        // per-mint throttled), instead of waiting for the next ordinary poll; the next cycle re-classifies on new stamps.
        if (c.outcome == Outcome8084.DATA_INCOMPLETE) requestInputs8085(ts.mint, ts.symbol, c.missing, nowMs)
        noteFunnel8283(lane, ts.mint, c)
        if (classifyCache.size > 4_000) classifyCache.entries.removeIf { nowMs - it.value.featuresAt > 60_000L }
        classifyCache[ck] = Cached8084(key, revision, nowMs, nowMs, c).also { it.featureRev8283 = frev8283 }
        com.lifecyclebot.engine.BotImprovementReport8168.noteSetup(ts.mint, lane, c.outcome.name, c.missing, c.reason, nowMs)
        return c
    }

    /** V5.0.8283 — the unique-candidate feature funnel of this lane (FeatureFunnel8283): evaluated, required / present / repaired. */
    private fun noteFunnel8283(lane: String, mint: String, c: Classification8084) {
        try {
            com.lifecyclebot.engine.truth.FeatureFunnel8283.noteClassified8283(lane, mint, c.missing.any { it.substringBefore(':') in CORE_8084 })
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.8283 — a cached classification still inside its 5 s reuse window that new provider evidence (a revision
     * advance) has just invalidated: counted for the report (cacheInvalidatedAfterEvidence). Diagnostic only.
     */
    private fun noteEvidenceInvalidation8283(cached: Cached8084?, key: Long, frev: Long, nowMs: Long) {
        if (cached == null || cached.inputKey == key || cached.featureRev8283 == frev) return
        if (nowMs - cached.featuresAt !in 0L..EXTERNAL_TTL_MS_8084) return
        try { com.lifecyclebot.engine.truth.FeatureRevision8283.noteInvalidation8283() } catch (_: Throwable) {}
    }

    /** The matches of [c] ranked again on the current books (same evidence, new grades). */
    private fun rerank8084(c: Classification8084, nowMs: Long): Classification8084 {
        if (c.matched.isEmpty()) return c
        ensureLoaded()
        val best = synchronized(this) { LaneCheatSheet8323.choose8323(c.lane, c.matched.map { setupEvidence8077(c.lane, it) })?.id }  // V5.0.8323 — favour first, avoid dropped
        val out = if (best == null) Outcome8084.MATCHES_UNRANKABLE else Outcome8084.MATCHED
        return if (best == c.chosen && out == c.outcome) c
        else Classification8084(c.lane, out, best, c.matched, c.missing, c.errors, c.featureVersion, c.atMs, c.reason + " (re-ranked)")
    }

    /** Pure over [f]: match every setup, rank the fired ones, name the outcome and its reason. */
    internal fun evaluate8084(lane: String, f: F, fp: Int, nowMs: Long): Classification8084 {
        val det = matchDetailed8084(lane, f)
            ?: return Classification8084(lane, Outcome8084.NO_PLAYBOOK, null, emptyList(), emptyList(), emptyList(), fp, nowMs, "no playbook")
        val (fired, errs) = det
        val missing = f.obs8084.filterValues { it != Obs8084.OBSERVED }.map { (k, v) -> "$k:${v.name}" }
        val best = if (fired.isEmpty()) null else { ensureLoaded(); synchronized(this) { LaneCheatSheet8323.choose8323(lane, fired.map { setupEvidence8077(lane, it) })?.id } }  // V5.0.8323
        val coreMissing = CORE_8084.filter { (f.obs8084[it] ?: Obs8084.UNKNOWN) != Obs8084.OBSERVED }
            .map { "$it:${f.obs8084[it]?.name ?: Obs8084.UNKNOWN.name}" }
        return decide8084(lane, fired, errs, best, missing, coreMissing, fp, nowMs)
    }

    /** Pure (V5.0.8084): the outcome of one evaluation. A selected setup must be one that fired. */
    fun decide8084(lane: String, fired: List<String>, errs: List<String>, best: String?, missing: List<String>,
                   coreMissing: List<String>, fp: Int, nowMs: Long): Classification8084 = when {
        fired.isNotEmpty() && best != null && best in fired ->
            Classification8084(lane, Outcome8084.MATCHED, best, fired, missing, errs, fp, nowMs, "fired=${fired.size} chose=$best")
        fired.isNotEmpty() ->
            Classification8084(lane, Outcome8084.MATCHES_UNRANKABLE, null, fired, missing, errs, fp, nowMs,
                "fired=${fired.joinToString(",")} but no valid ranking evidence")
        errs.isNotEmpty() -> Classification8084(lane, Outcome8084.CLASSIFIER_ERROR, null, emptyList(), missing, errs, fp, nowMs,
            "no setup fired; predicates threw: ${errs.take(4).joinToString(",")}")
        coreMissing.isNotEmpty() -> Classification8084(lane, Outcome8084.DATA_INCOMPLETE, null, emptyList(), missing, errs, fp, nowMs,
            "no setup fired; unobserved core inputs: ${coreMissing.joinToString(",")}")
        else -> Classification8084(lane, Outcome8084.NO_MATCH, null, emptyList(), missing, errs, fp, nowMs, "core inputs observed; no registered setup fired")
    }

    /**
     * Pure (V5.0.8318): the counter name of a missing input. The "tape" observation in [F.obs8084] is the 45-minute launch
     * tape (LaunchTape7921, [features] line "val tape"), not the coin's local trade tape (TradeTape8100, counted as tape8100);
     * 5.0.8314 reported SETUP_FEATURE_MISSING_8084_tape 18,569 (mostly PAST_LAUNCH_WINDOW, not a core input) and it hid the
     * real gaps (bp, chg, shape). The missing-input string itself keeps its "tape" key: the admission re-fetch schedulers key
     * on it.
     */
    fun counterFeature8318(feat: String): String = if (feat == "tape") "launchTape" else feat

    // Bounded diagnostics: fixed outcome x lane, fixed feature x lane.
    private val outcomeCounts8084 = ConcurrentHashMap<String, AtomicLong>()
    private val missingCounts8084 = ConcurrentHashMap<String, AtomicLong>()
    private fun count8084(c: Classification8084) {
        try {
            val name = when (c.outcome) {
                Outcome8084.MATCHED -> "SETUP_MATCHED_8084"; Outcome8084.NO_MATCH -> "SETUP_NO_MATCH_8084"
                Outcome8084.DATA_INCOMPLETE -> "SETUP_DATA_INCOMPLETE_8084"; Outcome8084.MATCHES_UNRANKABLE -> "SETUP_MATCHES_UNRANKABLE_8084"
                Outcome8084.CLASSIFIER_ERROR -> "SETUP_CLASSIFIER_ERROR_8084"; Outcome8084.NO_PLAYBOOK -> "SETUP_NO_PLAYBOOK_8084"
            }
            PipelineHealthCollector.labelInc(name); PipelineHealthCollector.labelInc("${name}_${c.lane}")
            outcomeCounts8084.computeIfAbsent("${c.lane}|${c.outcome.name}") { AtomicLong(0) }.incrementAndGet()
            if (c.outcome != Outcome8084.MATCHED) for (m in c.missing) {
                val feat = counterFeature8318(m.substringBefore(':'))
                PipelineHealthCollector.labelInc("SETUP_FEATURE_MISSING_8084_$feat")
                missingCounts8084.computeIfAbsent("${c.lane}|$feat") { AtomicLong(0) }.incrementAndGet()
            }
        } catch (_: Throwable) {}
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
        val setup = selectedSetup8094(ts, lane, nowMs)  // V5.0.8153 — scored as the strategy it trades as
        if (setup == null || setup == NO_TRIGGER) null else {
            val also = classify8084(ts, lane, nowMs)?.matched.orEmpty().count { it != setup }
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
    fun liveRefusal(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): String? {
        return try {
            val lane = canon(laneRaw)
            val c8084 = classify8084(ts, lane, nowMs) ?: return null
            // V5.0.8153 — the strategy the coin trades as (a proven-losing default no longer refuses a supported fired one).
            val setup = strategyFor8153(ts, lane, c8084, nowMs) ?: return null
            // V5.0.8084 — DATA_INCOMPLETE / CLASSIFIER_ERROR are not the NO_TRIGGER cohort: no economic refusal is drawn
            // from them (the proven-only live step still needs the candidate's own positive evidence to carry money).
            if (c8084.outcome != Outcome8084.MATCHED && c8084.outcome != Outcome8084.NO_MATCH) return null
            liveRefusalForSetup(ts, lane, setup)
        } catch (_: Throwable) { null }
    }

    /** The admitted setup is already resolved; a classifier refresh cannot replace its identity here. */
    fun liveRefusalForSetup(ts: TokenState, laneRaw: String, setup: String?): String? {
        if (setup == null) return null
        return try {
            val lane = canon(laneRaw)
            val runner = runnerTail8295(lane)  // V5.0.8295 — a losing lane bank refutes the label runner tail
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
                    // V5.0.8083 — NO_TRIGGER means "no recognised setup", not negative expectancy: a measured NO_TRIGGER record
                    // that is merely short of proof no longer refuses here (only PROVEN losing above does). The candidate still
                    // needs its own validated positive evidence at LiveEdgeGate7877's proven-only step to carry live money.
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

    private const val TIMING_KEY_8094 = "_timing8094"
    const val TIMING_UNKNOWN_8094 = "UNK"

    /**
     * Pure (V5.0.8094): WHEN in its move a coin is being taken — PEAK (80%+ off its low and within 12% of the high: the
     * 8065 chase), EXTENDED (30%+ off its low, within 8% of the high), PULLBACK (25%+ below the high), else MID.
     */
    fun timingBand8094(runupPct: Double, currentVsPeak: Double): String = when {
        !runupPct.isFinite() || !currentVsPeak.isFinite() || currentVsPeak <= 0.0 -> TIMING_UNKNOWN_8094
        runupPct >= 80.0 && currentVsPeak >= 0.88 -> "PEAK"
        runupPct >= 30.0 && currentVsPeak >= 0.92 -> "EXTENDED"
        currentVsPeak < 0.75 -> "PULLBACK"
        else -> "MID"
    }

    fun timingBandOf8094(ts: TokenState): String = try {
        val snap = com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts)
        timingBand8094(snap.runupFromLocalLowPct, snap.currentVsPeak)
    } catch (_: Throwable) { TIMING_UNKNOWN_8094 }

    /**
     * V5.0.8094 — "setup@band" when this coin's picked setup, taken at its CURRENT timing band, has a losing record in that
     * band (12+ labels, mean + SE < 0). The coin is not refused as a coin: it waits for a band its setup pays in.
     */
    fun timingNegative8094(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): String? = try {
        val lane = canon(laneRaw)
        val setup = selectedSetup8094(ts, lane, nowMs)
        val band = timingBandOf8094(ts)
        if (setup == null || band == TIMING_UNKNOWN_8094) null else synchronized(this) {
            val st = timingBooks8094[lane]?.get("$setup|$band")
            if (st != null && setupRecordNegative8094(st.n, st.mean(), st.variance())) "$setup@$band" else null
        }
    } catch (_: Throwable) { null }

    /**
     * V5.0.8094 — the strategy this coin trades as in [laneRaw]: the best proven fired setup, else the classified one.
     * V5.0.8153 — first the strategy CandidateAdmission8136 decided for this coin (StrategyIdentity8153, while fresh and
     * still fired), else the best SUPPORTED / VALID fired setup on its own record and timing band (StrategyRank8153).
     */
    fun selectedSetup8094(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): String? =
        (try { admittedSetup8153(ts, laneRaw, nowMs) } catch (_: Throwable) { null })  // V5.0.8153 — the admission's one identity
            ?: (try { rankedSetup8153(ts, laneRaw, nowMs) } catch (_: Throwable) { null })  // V5.0.8153 — best evidenced fired setup
            ?: (try { provenSetup8056(ts, laneRaw, nowMs)?.first } catch (_: Throwable) { null })
            ?: (try { validAlternative8144(ts, laneRaw, nowMs) } catch (_: Throwable) { null })  // V5.0.8144
            ?: classify(ts, canon(laneRaw), nowMs)

    /**
     * V5.0.8144 §COMPETING_STRATEGIES — when the classifier's default setup for this coin is losing on its own record and
     * another setup the coin fired clears the cold-start bar on ITS own record, that one (CortexStrategySelect8144).
     */
    fun validAlternative8144(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): String? {
        val lane = canon(laneRaw)
        val default = classify(ts, lane, nowMs) ?: return null
        val fired = matchedSetups8083(ts, lane, nowMs)
        if (fired.none { it != default }) return null
        val records = synchronized(this) {
            (fired + default).distinct().mapNotNull { id -> stat(lane, id)?.let { CortexStrategySelect8144.Record8144(id, it.n, it.mean(), it.variance()) } }
        }
        val alt = CortexStrategySelect8144.alternative8144(default, records) ?: return null
        try { PipelineHealthCollector.labelInc("SETUP_NEGATIVE_DEFAULT_YIELDS_8144_$lane") } catch (_: Throwable) {}
        return alt
    }

    // ── V5.0.8153 §BEST_EXECUTABLE_STRATEGY_PER_TOKEN (StrategyRank8153) ──

    /**
     * V5.0.8153 — every setup [ts] fired in [laneRaw] with its OWN record in this lane and its record at the coin's CURRENT
     * timing band (labels net of cost). Fired setups with no record are left out (missing evidence is never edge).
     */
    fun strategyOptions8153(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): List<com.lifecyclebot.engine.truth.StrategyRank8153.Option8153> = try {
        val lane = canon(laneRaw)
        val fired = matchedSetups8083(ts, lane, nowMs)
        // One build per candidate version and book revision for a second (selectedSetup8094 is read by many gates).
        val ck = "${ts.mint}|$lane"
        val key = try { inputKey8084(ts) } catch (_: Throwable) { 0L }
        val rev = bookRevision8077.get()
        val hit = options8153[ck]?.takeIf { it.key == key && it.rev == rev && it.fired == fired && nowMs - it.atMs in 0L..OPTIONS_TTL_MS_8153 }
        if (hit != null) hit.opts else if (fired.isEmpty()) emptyList() else {
            // The band is read only when a fired setup has a timing record to look it up in.
            val banded = synchronized(this) { timingBooks8094[lane]?.keys?.any { k -> fired.any { k.startsWith("$it|") } } == true }
            val band = if (banded) timingBandOf8094(ts) else TIMING_UNKNOWN_8094
            val opts = synchronized(this) {
                fired.mapNotNull { id ->
                    val st = stat(lane, id)?.takeIf { it.n >= 1.0 }
                    val tm = if (st == null || band == TIMING_UNKNOWN_8094) null else timingBooks8094[lane]?.get("$id|$band")?.takeIf { it.n >= 1.0 }
                    if (st == null) null else com.lifecyclebot.engine.truth.StrategyRank8153.Option8153(
                        id = id, n = st.n.toInt(), meanPct = st.mean(),
                        sePct = if (st.n > 1.0) kotlin.math.sqrt(st.variance() / st.n) else Double.POSITIVE_INFINITY,
                        runnerRate = st.runnerRate(), netOfCost = true,
                        timingN = tm?.n?.toInt() ?: 0, timingMeanPct = tm?.mean() ?: Double.NaN,
                        timingSePct = if (tm != null && tm.n > 1.0) kotlin.math.sqrt(tm.variance() / tm.n) else Double.NaN,
                    )
                }
            }
            if (options8153.size > 4_000) options8153.entries.removeIf { nowMs - it.value.atMs > OPTIONS_TTL_MS_8153 }
            options8153[ck] = Options8153(nowMs, key, rev, fired, opts)
            opts
        }
    } catch (_: Throwable) { emptyList() }

    private class Options8153(val atMs: Long, val key: Long, val rev: Long, val fired: List<String>, val opts: List<com.lifecyclebot.engine.truth.StrategyRank8153.Option8153>)
    private val options8153 = ConcurrentHashMap<String, Options8153>()
    private const val OPTIONS_TTL_MS_8153 = 1_000L

    /** V5.0.8153 — the ranked strategy pick over this coin's fired setups (default = the classifier's consumer tag). */
    fun strategyPick8153(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): com.lifecyclebot.engine.truth.StrategyRank8153.Pick8153? = try {
        val lane = canon(laneRaw)
        val default8323 = classify(ts, lane, nowMs)
        val opts8323 = strategyOptions8153(ts, lane, nowMs)
        // V5.0.8323 — the lane's cheat sheet: a favoured fired setup first, an avoided one only on its re-probe slot.
        LaneCheatSheet8323.refresh8323(nowMs)
        val memo8323 = HashMap<String, LaneCheatSheet8323.Verdict8323>()
        LaneCheatSheet8323.pickWith8323(com.lifecyclebot.engine.truth.StrategyRank8153.select8153(default8323, opts8323, Double.NaN),
            default8323, opts8323) { id -> memo8323.getOrPut(id) { LaneCheatSheet8323.effectiveVerdict8323(lane, id, ts.mint, nowMs) } }
    } catch (_: Throwable) { null }

    /** V5.0.8153 — the best SUPPORTED / VALID fired setup on its own record and timing band, else null (the default decides). */
    fun rankedSetup8153(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): String? {
        val p = strategyPick8153(ts, laneRaw, nowMs) ?: return null
        if (!com.lifecyclebot.engine.truth.StrategyRank8153.evidenced8153(p.tier) && !LaneCheatSheet8323.favourBasis8323(p.basis)) return null  // V5.0.8323
        if (p.overDefault) try { PipelineHealthCollector.labelInc("SETUP_RANKED_OVER_DEFAULT_8153_${canon(laneRaw)}") } catch (_: Throwable) {}
        return p.id
    }

    /** V5.0.8153 — the strategy CandidateAdmission8136 decided for this coin in this lane, while fresh and still fired. */
    fun admittedSetup8153(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): String? {
        val lane = canon(laneRaw)
        val mode = if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"
        val version = com.lifecyclebot.engine.LaneExecutionCoordinator.candidateVersionFor(ts.mint)
        return com.lifecyclebot.engine.truth.StrategyIdentity8153.heldFor8153(ts.mint, lane, nowMs,
            matchedSetups8083(ts, lane, nowMs), mode, version, selectionRevision8198())
    }

    /** V5.0.8153 — the tier of the strategy this coin trades as (null: not classified / no record). */
    fun selectedTier8153(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): com.lifecyclebot.engine.truth.StrategyRank8153.Tier8153? = try {
        val lane = canon(laneRaw)
        val sel = selectedSetup8094(ts, lane, nowMs)
        if (sel == null) null else strategyOptions8153(ts, lane, nowMs).firstOrNull { it.id == sel }
            ?.let { com.lifecyclebot.engine.truth.StrategyRank8153.tier8153(it, Double.NaN) }
    } catch (_: Throwable) { null }

    /** V5.0.8153 — [n, mean %, SE %] of [setup] (default: the strategy this coin trades as) in this lane, or null. */
    fun selectedRecord8153(ts: TokenState, laneRaw: String, setup: String?, nowMs: Long = System.currentTimeMillis()): DoubleArray? = try {
        val lane = canon(laneRaw)
        val sel = setup ?: selectedSetup8094(ts, lane, nowMs)
        if (sel == null || sel == NO_TRIGGER) null else synchronized(this) {
            stat(lane, sel)?.takeIf { it.n >= 1.0 }?.let { st ->
                doubleArrayOf(st.n, st.mean(), if (st.n > 1.0) kotlin.math.sqrt(st.variance() / st.n) else Double.POSITIVE_INFINITY)
            }
        }
    } catch (_: Throwable) { null }

    /** V5.0.8153 — the strategy this coin trades as is losing on its own record (12+, mean + SE < 0). */
    fun selectedLosing8153(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val r = selectedRecord8153(ts, laneRaw, null, nowMs) ?: return false
        return r[0] >= NEGATIVE_N_8094 && r[1].isFinite() && r[2].isFinite() && r[1] + r[2] < 0.0
    }

    /**
     * V5.0.8153 — a deferred candidate's missing evidence (sparse candle history, unread liquidity, an unobserved 1h change)
     * re-fetched through the same per-mint throttled path as missing setup inputs (requestInputs8085 -> DataOrchestrator:
     * candle seed, forced poll, tape subscriptions). The deferral itself stays bounded by CandidateAdmission8136's window.
     */
    fun requestEvidence8153(ts: TokenState, missing: List<String>, nowMs: Long = System.currentTimeMillis()) {
        if (missing.isNotEmpty()) requestInputs8085(ts.mint, ts.symbol, missing, nowMs)
    }

    /** V5.0.8153 — the playbook's setup for a classification: the strategy the coin trades as when it fired, else the tag. */
    private fun strategyFor8153(ts: TokenState, lane: String, c: Classification8084, nowMs: Long): String? {
        val tag = c.tag ?: return null
        val sel = try { selectedSetup8094(ts, lane, nowMs) } catch (_: Throwable) { null }
        return if (sel != null && sel != tag && sel in c.matched) sel else tag
    }

    /**
     * Pure (V5.0.8153): the decision tag with [primary] (the strategy the coin trades as) first, so the label, the entry
     * snapshot and the realised close file under the same setup. The same fired set is graded either way.
     */
    internal fun primaryTag8153(c: Classification8084, primary: String?): String? {
        val base = decisionTag8091(c) ?: return null
        val tag = c.tag ?: return base
        if (primary == null || primary == tag || primary !in c.matched) return base
        val rest = (listOf(tag) + c.matched).filter { it != primary && it != NO_TRIGGER }.distinct()
        return (if (rest.isEmpty()) primary else primary + ";" + rest.joinToString(",")) + "#O=${c.outcome.name}#V=${c.featureVersion}"
    }

    /** V5.0.8094 — [mean, variance, n] of this coin's picked setup at its CURRENT timing band, or null when unrecorded. */
    fun timingStat8094(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): DoubleArray? = try {
        val lane = canon(laneRaw)
        val setup = selectedSetup8094(ts, lane, nowMs)
        val band = timingBandOf8094(ts)
        if (setup == null || band == TIMING_UNKNOWN_8094) null else synchronized(this) {
            timingBooks8094[lane]?.get("$setup|$band")?.let { doubleArrayOf(it.mean(), it.variance(), it.n) }
        }
    } catch (_: Throwable) { null }

    /** Pure (V5.0.8094): a setup record that is losing on its own evidence — 12+ graded labels and mean + SE below zero. */
    fun setupRecordNegative8094(n: Double, mean: Double, variance: Double): Boolean {
        if (n < NEGATIVE_N_8094 || !mean.isFinite()) return false
        val se = if (variance.isFinite() && variance >= 0.0) kotlin.math.sqrt(variance / n) else return false
        return mean + se < 0.0
    }

    /**
     * V5.0.8094 entry repair — the setup this coin is classified as in this lane, when that setup's own record in this lane
     * is losing (setupRecordNegative8094, no runner-tail exemption). 8094 live: SHITCOIN LAUNCH_CONTINUATION n25 -23.6%
     * kept trading because the proven-losing rule needs n30 and exempts runner lanes. Null otherwise.
     */
    fun pickedSetupNegative8094(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): String? = try {
        val lane = canon(laneRaw)
        val setup = classify(ts, lane, nowMs)
        // V5.0.8144 — a negative default does not refuse the coin when another fired setup is valid on its own record.
        val alt8144 = if (setup == null) null else (try { validAlternative8144(ts, lane, nowMs) } catch (_: Throwable) { null })
            ?: (try { selectedSetup8094(ts, lane, nowMs) } catch (_: Throwable) { null })?.takeIf { it != setup && it != NO_TRIGGER }  // V5.0.8153
        if (setup == null || alt8144 != null) null else synchronized(this) {
            val st = stat(lane, setup)
            if (st != null && setupRecordNegative8094(st.n, st.mean(), st.variance())) setup else null
        }
    } catch (_: Throwable) { null }

    /**
     * V5.0.8290 — caller holds the lock. The executable record [st] of ([lane], [setup]) proves live money: the stored record
     * is not OUTLIER_SUSPECT and mean - SE clears the lane's realised shortfall [extra] (LiveAdmissionProof8290). Counted.
     */
    private fun setupSurvives8290(lane: String, setup: String, st: CortexLedger7885.Stat, extra: Double): Boolean {
        val suspect = statRaw8187(lane, setup)?.suspect8187() == true
        val se = if (st.n > 1.0) kotlin.math.sqrt(st.variance() / st.n) else Double.POSITIVE_INFINITY
        val ok = com.lifecyclebot.engine.truth.LiveAdmissionProof8290.survives8290(st.n, st.mean(), se, suspect, extra, LOSING_N.toDouble())
        if (!ok) try { com.lifecyclebot.engine.truth.LiveAdmissionProof8290.noteRefused8290(suspect, lane) } catch (_: Throwable) {}
        return ok
    }

    /**
     * V5.0.8290 — LiveEdgeGate7877 (setup authority 7955): the selected setup's executable record clears [minN] labels and
     * mean - SE past the lane's realised shortfall, on a record that is not outlier-suspect. False when unrecorded.
     */
    fun selectedSetupSurvives8290(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis(), minN: Double = 15.0): Boolean = try {
        val lane = canon(laneRaw)
        val setup = selectedSetup8094(ts, lane, nowMs)
        val extra = com.lifecyclebot.engine.truth.LiveAdmissionProof8290.laneExtra8290(lane, nowMs)
        if (setup == null || setup == NO_TRIGGER) false else synchronized(this) {
            val st = stat(lane, setup)
            st != null && com.lifecyclebot.engine.truth.LiveAdmissionProof8290.survives8290(st.n, st.mean(),
                if (st.n > 1.0) kotlin.math.sqrt(st.variance() / st.n) else Double.POSITIVE_INFINITY,
                statRaw8187(lane, setup)?.suspect8187() == true, extra, minN)
        }
    } catch (_: Throwable) { false }

    /** Pure: a (lane, setup) record proven to pay: 30+ labels and mean minus one standard error above zero. */
    fun setupProvenPositive7948(st: CortexLedger7885.Stat?): Boolean {
        if (st == null || st.n < LOSING_N) return false
        val se = kotlin.math.sqrt(st.variance() / st.n)
        return st.mean() - se > 0.0
    }

    /**
     * V5.0.8056 — owner: "I want to get back to banking 500% runs". The setup this coin is showing in this lane, when
     * the lane's own record of that setup is proven to pay (30+ graded labels, mean - SE > 0 net of cost: 5.0.8051
     * SHITCOIN ACCELERATING_TAPE n61 +23.1%, MOONSHOT LAUNCH_LADDER_PROVEN n48 +13.8%), with whether it is a runner
     * setup (10%+ of its labels ran, or it averages +10%+). Null otherwise.
     */
    fun provenSetup8056(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Pair<String, Boolean>? = try {
        val lane = canon(laneRaw)
        // V5.0.8083 §MULTI_STRATEGY — every setup this coin fired in this lane is evaluated, and the best PROVEN one is
        // selected for this token (lower bound mean - SE), not only the tag classify() chose. Different tokens in one lane
        // can run different setups at once; losing setups keep their own books.
        // V5.0.8094 — multi-strategy is kept: the best PROVEN fired setup is selected, and the trade then carries that
        // setup's tactic, style and exit (TacticSwitcher.tacticForToken8050 / AgenticStyleRouter), so it is traded as the
        // strategy whose record admitted it.
        val candidates = matchedSetups8083(ts, lane, nowMs)
        // V5.0.8290 — LIVE: proven means proven past the lane's realised shortfall, on a record that is not outlier-suspect.
        val extra8290 = try { com.lifecyclebot.engine.truth.LiveAdmissionProof8290.laneExtra8290(lane, nowMs) } catch (_: Throwable) { 0.0 }
        if (candidates.isEmpty()) null else synchronized(this) {
            candidates.mapNotNull { setup ->
                val st = stat(lane, setup)
                if (st == null || !setupProvenPositive7948(st) || !setupSurvives8290(lane, setup, st, extra8290)) null
                else Triple(setup, st.runnerRate() >= TAIL_RATE || st.mean() >= RUNNER_SETUP_MEAN_8056, st.mean() - kotlin.math.sqrt(st.variance() / st.n))
            }.maxByOrNull { it.third }?.let { best ->
                if (best.first != classify(ts, lane, nowMs)) try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PLAYBOOK_ALT_SETUP_SELECTED_8083_$lane")
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SETUP_ALTERNATIVE_SELECTED_8084_$lane")
                } catch (_: Throwable) {}
                best.first to best.second
            }
        }
    } catch (_: Throwable) { null }

    /**
     * V5.0.8083 — the setups [ts] fired in [laneRaw] (the classified one first), excluding NO_TRIGGER. Empty when the lane
     * has no playbook or nothing fired.
     */
    fun matchedSetups8083(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): List<String> {
        // V5.0.8084 — read from the one immutable classification (chosen first, then every alternative that fired);
        // an unrankable match keeps its fired setups.
        val c = classify8084(ts, laneRaw, nowMs) ?: return emptyList()
        return (listOfNotNull(c.chosen) + c.matched).filter { it != NO_TRIGGER }.distinct()
    }

    private const val RUNNER_SETUP_MEAN_8056 = 10.0

    /**
     * V5.0.8080 §BOUNDED_COLD_START. A fresh install has no setup with 30 graded labels, so [provenSetup8056] refuses
     * every live entry until hours of labels accrue (8078 clean user: LIVE_PROVEN_SETUP_ONLY_8059=4182, zero buys).
     * This is the same exact-strategy test on fewer labels with a stricter margin: the coin's classified setup, graded
     * on its OWN exit family (familyLabel8080), with [COLD_MIN_N_8080]+ labels and mean - 1.5 SE above zero net of
     * cost. Missing evidence is never edge: no record, or a record that does not clear the margin, returns null.
     * The caller (LiveEdgeGate7877) bounds how many live entries this may admit.
     */
    fun coldStartSetup8080(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): String? = try {
        val lane = canon(laneRaw)
        // V5.0.8083 — every fired setup is tried on its own record, not only the classified tag.
        // V5.0.8094 — multi-strategy: each fired setup on its OWN record; a setup whose own record is losing is never the
        // admitting one (setupRecordNegative8094), and the trade then carries the admitting setup's tactic and exit.
        val candidates = matchedSetups8083(ts, lane, nowMs)
        val extra8290 = try { com.lifecyclebot.engine.truth.LiveAdmissionProof8290.laneExtra8290(lane, nowMs) } catch (_: Throwable) { 0.0 }
        if (candidates.isEmpty()) null else synchronized(this) {
            candidates.firstOrNull { setup -> stat(lane, setup)?.let {
                // V5.0.8290 — the same margin past the realised shortfall, never on an outlier-suspect record.
                coldStartPositive8080(it.n, it.mean() - extra8290, it.variance()) && !setupRecordNegative8094(it.n, it.mean(), it.variance()) &&
                    statRaw8187(lane, setup)?.suspect8187() != true
            } == true }
        }
    } catch (_: Throwable) { null }

    /** Pure (V5.0.8080): [n] labels, mean and variance (%, %²) clear the cold-start margin. */
    fun coldStartPositive8080(n: Double, mean: Double, variance: Double): Boolean {
        if (!n.isFinite() || n < COLD_MIN_N_8080 || !mean.isFinite() || !variance.isFinite() || variance < 0.0) return false
        return mean - COLD_SE_MULT_8080 * kotlin.math.sqrt(variance / n) > 0.0
    }

    internal const val COLD_MIN_N_8080 = 12.0
    internal const val COLD_SE_MULT_8080 = 1.5

    /**
     * V5.0.8079 — the coin's classified setup is PROVEN LOSING in this lane (same record and test as [liveRefusal]),
     * returned with liveRefusal's exact reason so the setup-scoped veto audit grades the same key. LiveEdgeGate checks
     * it before the chart-reader BUY and specialist doors, which used to admit ahead of the playbook priors.
     */
    fun provenLosingSetupRefusal8079(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): String? = try {
        provenLosingNamedSetupRefusal(laneRaw, selectedSetup8094(ts, canon(laneRaw), nowMs))
    } catch (_: Throwable) { null }

    /** Check the strategy carried by admission without selecting a different strategy. */
    fun provenLosingNamedSetupRefusal(laneRaw: String, setup: String?): String? = try {
        val lane = canon(laneRaw)
        if (setup == null || setup == NO_TRIGGER) null else {
            val runner = runnerTail8295(lane)  // V5.0.8295
            val losing = synchronized(this) { stat(lane, setup)?.let { provenLosing(it, runner) } ?: false }
            if (losing) "PLAYBOOK_SETUP_PROVEN_LOSING_7907_${lane}_$setup" else null
        }
    } catch (_: Throwable) { null }

    /** V5.0.8057 — [mean %, variance %², n] of the coin's classified setup when that setup is proven (provenSetup8056). */
    fun provenSetupStat8057(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): DoubleArray? = try {
        val lane = canon(laneRaw)
        val setup = selectedSetup8094(ts, lane, nowMs)  // V5.0.8094 — one strategy identity
        if (setup == null || setup == NO_TRIGGER) null else synchronized(this) {
            val st = stat(lane, setup)
            if (st == null || !setupProvenPositive7948(st)) null else doubleArrayOf(st.mean(), st.variance(), st.n)
        }
    } catch (_: Throwable) { null }

    /**
     * V5.0.8136 — CandidateAdmission8136: every setup [ts] fired in [laneRaw] with its OWN record in this lane, as
     * [n, mean %, SE %, runner rate] (SE infinite under two labels). Empty when nothing fired or the lane has no playbook.
     */
    fun firedSetupRecords8136(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): List<Pair<String, DoubleArray>> = try {
        val lane = canon(laneRaw)
        val fired = matchedSetups8083(ts, lane, nowMs)
        if (fired.isEmpty()) emptyList() else synchronized(this) {
            fired.mapNotNull { id ->
                stat(lane, id)?.takeIf { it.n >= 1.0 }?.let { st ->
                    val se = if (st.n > 1.0) kotlin.math.sqrt(st.variance() / st.n) else Double.POSITIVE_INFINITY
                    // V5.0.8290 — [4]: 1.0 when the stored record is OUTLIER_SUSPECT (the executable copy no longer says so).
                    id to doubleArrayOf(st.n, st.mean(), se, st.runnerRate(), if (statRaw8187(lane, id)?.suspect8187() == true) 1.0 else 0.0)
                }
            }
        }
    } catch (_: Throwable) { emptyList() }

    /**
     * V5.0.8136 — the unobserved CORE inputs when this candidate's classification is DATA_INCOMPLETE, else null. A data
     * gap is recoverable (classify8084 has already asked the data layer to re-fetch them, requestInputs8085): it is not
     * the NO_TRIGGER cohort and not a verdict on the token.
     */
    fun dataIncomplete8136(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): List<String>? = try {
        // V5.0.8283 — lifecycle-aware: history the token is too young to have is recorded, not waited for (null when
        // nothing else is missing); critical unknowns and every other gap stay binding (LifecycleData8283.split8283).
        lifecycleSplit8283(ts, laneRaw, nowMs)?.binding?.ifEmpty { null }
    } catch (_: Throwable) { null }

    /**
     * V5.0.8283 — the DATA_INCOMPLETE core inputs of this candidate split by what can exist at the token's age (nothing
     * fired, so no setup reads them); null when the classification is not DATA_INCOMPLETE. Counted per split.
     */
    fun lifecycleSplit8283(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): com.lifecyclebot.engine.truth.LifecycleData8283.Split8283? {
        val c = classify8084(ts, canon(laneRaw), nowMs)
        if (c == null || c.outcome != Outcome8084.DATA_INCOMPLETE) return null
        val core = c.missing.filter { it.substringBefore(':') in CORE_8084 }.ifEmpty { listOf("core:UNKNOWN") }
        val ageMin = try { com.lifecyclebot.engine.truth.CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts, nowMs) } catch (_: Throwable) { null } ?: Double.NaN
        // V5.0.8287 — and a NEW launch's flow its own observed trades cannot state yet is recorded, not waited for.
        val split = com.lifecyclebot.engine.truth.LifecycleData8283.split8287(core, ageMin, null, launchTrades8287(ts.mint, nowMs))
        try { com.lifecyclebot.engine.truth.LifecycleData8283.note8283(split) } catch (_: Throwable) {}
        return split
    }

    /**
     * V5.0.8287 — the trades of [mint] the bot observed since its WITNESSED birth (the launch tape follows a launch from
     * its create; the trade tape's executed prints of the last 5 minutes can only raise that count). Null without a launch
     * record: trades seen on a tape that did not follow the launch from birth prove nothing about how many happened.
     */
    internal fun launchTrades8287(mint: String, nowMs: Long): Int? {
        val launch = try { com.lifecyclebot.engine.market.LaunchTape7921.observedTrades8287(mint, nowMs) } catch (_: Throwable) { null }
            ?: return null
        val tape = try { com.lifecyclebot.engine.truth.TradeTape8100.executedCount8287(mint, nowMs, 5L * 60_000L) } catch (_: Throwable) { null }
        return maxOf(launch, tape ?: 0)
    }

    /** LiveEdgeGate7877: the number of labels behind this candidate's classified setup's record (0 = none). */
    fun classifiedSampleN7948(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Int = try {
        val lane = canon(laneRaw)
        val setup = selectedSetup8094(ts, lane, nowMs)  // V5.0.8153 — the admitted strategy
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

    /** V5.0.8291 — graduated setup authority under the realised cap (Cortex7885.setupRealisedCap8291): only lowers. */
    private fun setupAuthority8291(lane: String, setup: String, st: CortexLedger7885.Stat?): Double {
        val f = setupFraction7955(st)
        if (f <= 0.0) return f
        val cap = try { Cortex7885.setupRealisedCap8291(lane, setup) } catch (_: Throwable) { 1.0 }
        return minOf(f, cap)
    }

    /** LiveEdgeGate7877: [graduated authority, label count] of this candidate's classified setup, or null (none / NO_TRIGGER). */
    fun classifiedAuthority7955(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): DoubleArray? = try {
        val lane = canon(laneRaw)
        val setup = selectedSetup8094(ts, lane, nowMs)  // V5.0.8153 — the admitted strategy
        if (setup == null || setup == NO_TRIGGER) null else synchronized(this) {
            val st = stat(lane, setup)
            if (st == null) null else doubleArrayOf(setupAuthority8291(lane, setup, st), st.n)
        }
    } catch (_: Throwable) { null }

    /**
     * Cortex7885.convictionMult: [mean, variance, authority] (percent units) of this
     * candidate's classified setup when that record holds authority (V5.0.7955:
     * graduated, > 0; 1.0 = proven positive), else null.
     */
    fun provenSetupRecord7948(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): DoubleArray? = try {
        val lane = canon(laneRaw)
        val setup = selectedSetup8094(ts, lane, nowMs)  // V5.0.8153 — conviction sizes the admitted strategy
        if (setup == null || setup == NO_TRIGGER) null else synchronized(this) {
            val st = stat(lane, setup)
            val f = setupAuthority8291(lane, setup, st)
            if (st != null && f > 0.0) doubleArrayOf(st.mean(), st.variance(), f) else null
        }
    } catch (_: Throwable) { null }

    /** V5.0.7962 — CostLedger7962: the classified setup's shrunk expected net % (null: none / NO_TRIGGER). */
    fun classifiedExpected7962(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Double? = try {
        val lane = canon(laneRaw)
        val setup = selectedSetup8094(ts, lane, nowMs)  // V5.0.8094 — one strategy identity
        if (setup == null || setup == NO_TRIGGER) null else synchronized(this) { expected(lane, setup) }
    } catch (_: Throwable) { null }

    /**
     * V5.0.8049 — TacticSwitcher seeds each lane's starting tactic from this: the lane's setup with the best shrunk
     * expectancy (its cheat-sheet prior, moved by every graded label and the hive), with its expectancy; null when the
     * lane has no menu.
     */
    private const val SEED_MIN_N_8052 = 10.0

    /** V5.0.8069 — the Cortex panel: one setup's record in one lane. 8084: lower bound, tier and NO_TRIGGER flag. */
    data class SetupRow8069(val lane: String, val setup: String, val n: Int, val meanPct: Double, val sePct: Double, val proven: Boolean,
                            val lowerPct: Double = Double.NaN, val tier: String = "", val noTrigger: Boolean = false)

    /** Pure (V5.0.8084): tier of a record — PROVEN (30+ and mean-SE > 0), DEVELOPING (mean > 0), NEGATIVE. */
    fun tier8084(n: Double, mean: Double, se: Double): String = when {
        n >= LOSING_N && se.isFinite() && mean - se > 0.0 -> "PROVEN"
        mean > 0.0 -> "DEVELOPING"
        else -> "NEGATIVE"
    }

    /**
     * Pure (V5.0.8084): rank the COMPLETE eligible set before the limit. 8069 cut to the 2*limit largest samples first,
     * so twelve n500 NO_TRIGGER cohorts hid SHITCOIN SPECIALIST_EDGE. NO_TRIGGER rows are excluded (shown separately);
     * proven first, then developing, by confidence-aware lower bound (mean - SE); a few negatives stay visible; at most
     * three rows per lane in the first pass so one lane cannot monopolise the panel.
     */
    fun rankRows8084(rows: List<SetupRow8069>, limit: Int): List<SetupRow8069> {
        val setups = rows.filter { !it.noTrigger }
        fun lb(r: SetupRow8069) = if (r.lowerPct.isFinite()) r.lowerPct else r.meanPct
        val order = compareBy<SetupRow8069>({ when (it.tier) { "PROVEN" -> 0; "DEVELOPING" -> 1; else -> 2 } }).thenByDescending { lb(it) }
        val positive = setups.filter { it.tier != "NEGATIVE" }.sortedWith(order)
        val negative = setups.filter { it.tier == "NEGATIVE" }.sortedBy { it.meanPct }
        val negSlots = minOf(negative.size, maxOf(1, limit / 4))
        val posLimit = limit - negSlots
        val perLane = HashMap<String, Int>()
        val first = positive.filter { r -> (perLane.merge(r.lane, 1, Int::plus) ?: 0) <= 3 }.take(posLimit)
        val fill = positive.filter { it !in first }.take(posLimit - first.size)
        return (first + fill).sortedWith(order) + negative.take(limit - minOf(posLimit, first.size + fill.size))
    }

    private fun allRows8084(): List<SetupRow8069> = synchronized(this) {
        books.flatMap { (lane, b) ->
            b.stats.keys.mapNotNull { setup -> stat(lane, setup)?.let { setup to it } }
                .filter { it.second.n >= 5.0 }.map { (setup, st) ->
                    val se = if (st.n > 1.0) kotlin.math.sqrt(st.variance() / st.n) else Double.NaN
                    SetupRow8069(lane, setup, st.n.toInt(), st.mean(), se, setupProvenPositive7948(st),
                        if (se.isFinite()) st.mean() - se else Double.NaN, tier8084(st.n, st.mean(), se), setup == NO_TRIGGER)
                }
        }
    }

    /** V5.0.8069/8084 — the Cortex panel's setups (ranked on the full set; NO_TRIGGER excluded). Background thread only. */
    fun setupRows8069(limit: Int = 16): List<SetupRow8069> = try {
        ensureLoaded(); rankRows8084(allRows8084(), limit)
    } catch (_: Throwable) { emptyList() }

    /** V5.0.8084 — the NO_TRIGGER (no-match) cohorts per lane, shown in their own section. */
    fun noTriggerRows8084(limit: Int = 12): List<SetupRow8069> = try {
        ensureLoaded(); allRows8084().filter { it.noTrigger }.sortedByDescending { it.n }.take(limit)
    } catch (_: Throwable) { emptyList() }

    fun bestSetup8049(laneRaw: String): Pair<String, Double>? = try {
        ensureLoaded()
        val lane = canon(laneRaw)
        // V5.0.8052 — a setup is a candidate on its cheat-sheet prior (no labels) or on a real record (10+ labels):
        // 5.0.8051 seeded nearly every lane from TURNOVER_SPIKE on 1-4 labels (+45%, +118%) — noise, not a style.
        synchronized(this) {
            menuOf7962(lane)?.filter { val n = stat(lane, it.id)?.n ?: 0.0; n <= 0.0 || n >= SEED_MIN_N_8052 }
                ?.map { setupEvidence8077(lane, it.id) }?.let { LaneCheatSheet8323.choose8323(lane, it) }  // V5.0.8323 — the lane's sheet: favour first
                ?.let { it.id to it.expected }
        }
    } catch (_: Throwable) { null }

    // ── V5.0.8323 — LaneCheatSheet8323 reads (the same executable, 8295-governed record every authority reads) ──

    /** V5.0.8323 — every menu setup of [laneRaw]: its cheat-sheet prior, executable record, runner rate and shrunk expectancy. */
    fun sheetRecords8323(laneRaw: String): List<LaneCheatSheet8323.Record8323> = try {
        ensureLoaded()
        val lane = canon(laneRaw)
        synchronized(this) {
            menuOf7962(lane).orEmpty().map { s ->
                val st = stat(lane, s.id)
                val n = st?.n ?: 0.0
                LaneCheatSheet8323.Record8323(
                    setup = s.id, prior = s.prior, n = n,
                    meanPct = if (st != null && n > 0.0) st.mean() else 0.0,
                    sePct = if (st != null && n > 1.0) kotlin.math.sqrt(st.variance() / n) else Double.POSITIVE_INFINITY,
                    runnerRate = st?.runnerRate() ?: 0.0,
                    suspect = statRaw8187(lane, s.id)?.suspect8187() == true,
                    expectedPct = expected(lane, s.id),
                )
            }
        }
    } catch (_: Throwable) { emptyList() }

    /** V5.0.8323 — the cached classification of [mint] in [laneRaw] (fired setups, chosen tag), never computed here; null when none. */
    fun peekMatched8323(mint: String, laneRaw: String): Pair<List<String>, String?>? {
        val c = classifyCache["$mint|${canon(laneRaw)}"]?.c ?: return null
        return c.matched to c.chosen
    }

    /** Cortex7885.capture: tag the decision with its setup for its forward label. */
    /**
     * V5.0.8084 — the classification at decision time, taken synchronously by Cortex7885.capture before its async job,
     * so the grade credits what the coin showed when it was judged, not the later mutated TokenState.
     */
    fun decisionSnapshot8084(ts: TokenState, labelLane: String, nowMs: Long): Classification8084? =
        try { classify8084(ts, labelLane, nowMs) } catch (_: Throwable) { null }

    fun capture(ts: TokenState, laneRaw: String, labelLane: String, nowMs: Long, snap8084: Classification8084? = null,
                selectedAtDecision8174: String? = null, timingAtDecision8174: String? = null) {
        ensureLoaded()
        val lane = canon(laneRaw)
        val c = snap8084?.takeIf { it.lane == lane } ?: run {
            if (snap8084 != null) try { PipelineHealthCollector.labelInc("SETUP_DECISION_IDENTITY_MISMATCH_8084") } catch (_: Throwable) {}
            classify8084(ts, lane, nowMs)
        } ?: return
        val setup = c.tag ?: return
        // V5.0.7925 — shed half, not all: clearing dropped every pending label at once.
        if (pending.size > 8_000) pending.keys.take(pending.size / 2).forEach { pending.remove(it) }
        val fired = c.matched.filter { it != setup }
        // V5.0.8084 — the tag carries the outcome and feature version of the decision (restart-safe in the same string).
        // V5.0.8153 — the strategy the coin trades as leads the tag (same graded set), matching the entry snapshot.
        val primary8153 = selectedAtDecision8174 ?: try { selectedSetup8094(ts, lane, nowMs) } catch (_: Throwable) { null }
        pending["${ts.mint}|${labelLane.trim().uppercase()}"] = lane to requireNotNull(primaryTag8153(c, primary8153) ?: decisionTag8091(c))
        if (pendingTiming8094.size > 8_000) pendingTiming8094.keys.take(pendingTiming8094.size / 2).forEach { pendingTiming8094.remove(it) }
        pendingTiming8094["${ts.mint}|${labelLane.trim().uppercase()}"] = timingAtDecision8174 ?: timingBandOf8094(ts)
        tagged.computeIfAbsent("$lane|$setup") { AtomicLong(0) }.incrementAndGet()
        for (id in fired) firedCount.computeIfAbsent("$lane|$id") { AtomicLong(0) }.incrementAndGet()
    }

    private fun decisionTag8091(c: Classification8084): String? {
        val setup = c.tag ?: return null
        val fired = c.matched.filter { it != setup }
        return (if (fired.isEmpty()) setup else setup + ";" + fired.joinToString(",")) +
            "#O=${c.outcome.name}#V=${c.featureVersion}"
    }

    /** Exact current classification, independent of an earlier asynchronous forward capture. */
    fun entryTag8091(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): String? =
        decisionSnapshot8084(ts, lane, nowMs)?.let { c ->
            // V5.0.8153 — the sealed entry setup is the strategy admitted and sized (StrategyIdentity8153), not the default.
            primaryTag8153(c, try { selectedSetup8094(ts, c.lane, nowMs) } catch (_: Throwable) { null })
        }

    /** V5.0.8085 — the decision-time tag captured for (mint, label lane), or null; read by the entry snapshot. */
    fun decisionTag8085(mint: String, labelLane: String): String? = pending["$mint|${labelLane.trim().uppercase()}"]?.second

    /** V5.0.8085 — does a realised close grade the same setup + version its entry snapshot recorded? */
    fun sameDecision8085(entryTag: String, gradeTag: String): Boolean =
        entryTag.isNotBlank() && entryTag.substringBefore(';').substringBefore('#') == gradeTag.substringBefore(';').substringBefore('#') &&
            entryTag.substringAfter("#V=", "") == gradeTag.substringAfter("#V=", "")

    private val firedCount = ConcurrentHashMap<String, AtomicLong>()
    /** V5.0.8084 — forward labels by decision outcome per lane: [n, sum net %] (diagnostic; books are unchanged). */
    private val outcomeForward8084 = ConcurrentHashMap<String, DoubleArray>()

    /**
     * Pure (V5.0.8187): one label into an outcome tally [n, sum, wins] — clamped at ingestion to the label cap; a row restored
     * from before 8187 (wins unknown, -1) keeps its winners unknown.
     */
    internal fun addOutcome8187(a: DoubleArray, netPct: Double) {
        if (!netPct.isFinite() || a.size < 2) return
        val y = netPct.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX)  // V5.0.8322 — stored raw (no win cap)
        a[0] += 1.0; a[1] += y
        if (a.size >= 3 && a[2] >= 0.0 && y > 0.0) a[2] += 1.0
    }

    /**
     * Pure (V5.0.8187): an outcome tally's mean for the screen — read through the label cap (no sum of squares or runners is
     * kept, so the bound is winners x cap; unknown winners = every label). The raw mean is shown beside when it differs.
     */
    internal fun outcomeMean8187(a: DoubleArray): String {
        val n = a.getOrElse(0) { 0.0 }; val sum = a.getOrElse(1) { 0.0 }
        if (!(n > 0.0) || !sum.isFinite()) return "-%"
        val wins = a.getOrElse(2) { -1.0 }.let { if (it < 0.0) n else it }
        val raw = sum / n
        val hi = com.lifecyclebot.engine.truth.RobustLabelRead8185.upperMean8185(n, sum, wins, CortexLedger7885.LABEL_CAP_8187)
        return if (raw > hi + 1e-9) "${"%+.1f".format(hi)}%~raw${"%+.1f".format(raw)}%" else "${"%+.1f".format(raw)}%"
    }

    /** Cortex7885.onLabel (graded horizon only): learn (lane, setup) from the forward label. */
    fun onLabel(mint: String, labelLane: String, netPct: Double, grossPct: Double, fam8080: DoubleArray? = null, expectedSetup8084: String? = null) {
        ensureLoaded()
        if (!netPct.isFinite()) return
        val fam = fam8080?.takeIf { it.size == 4 && it.all { v -> v.isFinite() } }
        val (lane, full) = pending.remove("$mint|${labelLane.trim().uppercase()}") ?: return
        val timing8094 = pendingTiming8094.remove("$mint|${labelLane.trim().uppercase()}")
        // V5.0.8085 — the label must grade the decision the entry snapshot recorded (same setup, same feature version).
        try {
            val entryTag = com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.latestFor8085(mint, labelLane)?.cortexSetupTag8085.orEmpty()
            if (entryTag.isNotBlank()) PipelineHealthCollector.labelInc(
                if (sameDecision8085(entryTag, full)) "SETUP_ENTRY_SNAPSHOT_MATCH_8085" else "SETUP_ENTRY_SNAPSHOT_MISMATCH_8085")
        } catch (_: Throwable) {}
        // V5.0.8084 — "setup;alts#O=OUTCOME#V=fp" (a pre-8084 tag has no '#': its outcome is unknown, graded as before).
        val tag = full.substringBefore('#')
        val outcome8084 = full.split('#').firstOrNull { it.startsWith("O=") }?.removePrefix("O=")
        val primary = tag.substringBefore(';')
        if (!expectedSetup8084.isNullOrBlank() && expectedSetup8084 != primary)
            try { PipelineHealthCollector.labelInc("SETUP_DECISION_IDENTITY_MISMATCH_8084") } catch (_: Throwable) {}
        // DATA_INCOMPLETE / CLASSIFIER_ERROR decisions do not grade the NO_TRIGGER (no-match) cohort they are not part of.
        val primaryGraded = !(primary == NO_TRIGGER && (outcome8084 == Outcome8084.DATA_INCOMPLETE.name || outcome8084 == Outcome8084.CLASSIFIER_ERROR.name))
        // V5.0.7924 — the tagged setup and every other setup that fired are each graded.
        val graded = (if (primaryGraded) listOf(primary) else emptyList()) + tag.substringAfter(';', "").split(',').filter { it.isNotBlank() }
        try {
            PipelineHealthCollector.labelInc("SETUP_FORWARD_GRADED_8084")
            val ok = outcome8084 ?: "LEGACY"
            // V5.0.8187 — [n, sum, wins]: clamped at ingestion, winners counted so the read can be bounded.
            outcomeForward8084.computeIfAbsent("$lane|$ok") { DoubleArray(3) }.let { a -> synchronized(a) { addOutcome8187(a, netPct) } }
        } catch (_: Throwable) {}
        if (graded.isEmpty()) return
        // V5.0.7997 — kept so later checkpoints can revise this label (ForwardReturnLabeler7731).
        synchronized(this) {
            val stamps = graded.distinct().associateWith { setup ->
                // V5.0.8080 — each setup is graded on its own exit family (scalp vs long hold) of the same path.
                val (y, g) = familyLabel8080(lane, setup, netPct, grossPct, fam)
                // V5.0.8187 — label books read through the cap; new labels clamped at ingestion.
                val stamp = books.getOrPut(lane) { Book() }.stats.getOrPut(setup) { CortexLedger7885.labelStat8187() }
                    .add(y.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), g.isFinite() && g >= CortexLedger7885.RUNNER_GROSS_PCT)  // V5.0.8322 — raw
                LaneCheatSheet8323.noteLabel8323(lane, setup, y.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), g.isFinite() && g >= CortexLedger7885.RUNNER_GROSS_PCT)  // V5.0.8323 — runner average (raw, 8322)
                if (timing8094 != null && timing8094 != TIMING_UNKNOWN_8094) timingBooks8094.getOrPut(lane) { HashMap() }
                    .getOrPut("$setup|$timing8094") { CortexLedger7885.labelStat8187() }
                    .add(y.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), g.isFinite() && g >= CortexLedger7885.RUNNER_GROSS_PCT)  // V5.0.8322 — raw
                stamp
            }
            if (graded7997.size >= 8_000) graded7997.entries.minByOrNull { it.value.atMs }?.key?.let { graded7997.remove(it) }
            // Revisions only ever move long-hold setups, so the grade keeps the long-hold family's value.
            graded7997["$mint|${labelLane.trim().uppercase()}"] = Grade8076(lane, stamps,
                (fam?.get(2) ?: netPct), fam?.get(3) ?: grossPct, System.currentTimeMillis())  // V5.0.8322 — as booked (raw)
            bookRevision8077.incrementAndGet()
        }
        if (sincePersist.incrementAndGet() >= 25) { sincePersist.set(0); persist() }
    }

    /**
     * Pure (V5.0.8080): does [setup] in [lane] trade on the long-hold exits? Same mapping as the trade itself:
     * TacticSwitcher.seedTactic8049 -> AgenticStyleRouter style -> TradeExitContract8077 long styles
     * (BREAKOUT -> BREAKOUT_RUNNER, REACCUMULATION -> REACCUMULATION hold; PULLBACK / MOMENTUM exit as scalps),
     * and only in a lifecycle lane, as captured7945 credits it.
     */
    fun setupHoldsLong8080(lane: String, setup: String): Boolean {
        val t = try { com.lifecyclebot.engine.learning.TacticSwitcher.seedTactic8049(lane, setup) } catch (_: Throwable) { return false }
        val long = t == com.lifecyclebot.engine.learning.TacticSwitcher.Tactic.BREAKOUT ||
            t == com.lifecyclebot.engine.learning.TacticSwitcher.Tactic.REACCUMULATION
        // V5.0.8106 — or the lane rides every live buy on the runner ladder from entry (TradeExitContract8077, 8104): its
        // setups are graded on that family and revised over 1 h / 4 h / 24 h like any hold, not frozen at the 5-minute read.
        return (long && (try { com.lifecyclebot.engine.LifecycleHold8065.lifecycleLane8065(lane) } catch (_: Throwable) { false })) ||
            (try { com.lifecyclebot.engine.TradeExitContract8077.runnerFromEntryLane8104(lane, false) } catch (_: Throwable) { false })
    }

    /** Pure (V5.0.8080): the (net, gross) a setup is graded on; [fam] = [scalpNet, scalpGross, longNet, longGross]. */
    internal fun familyLabel8080(lane: String, setup: String, net: Double, gross: Double, fam: DoubleArray?): Pair<Double, Double> =
        if (fam == null) net to gross
        else if (setupHoldsLong8080(lane, setup)) fam[2] to fam[3] else fam[0] to fam[1]

    /**
     * V5.0.8144 — Cortex7885.onLabel: a label the hygiene keeps out of the books (unexecutable decision, or a repeat of
     * the coin's graded episode) releases its pending setup tag without grading any setup.
     */
    fun dropPending8144(mint: String, labelLane: String) {
        pending.remove("$mint|${labelLane.trim().uppercase()}")
        pendingTiming8094.remove("$mint|${labelLane.trim().uppercase()}")
        try { PipelineHealthCollector.labelInc("SETUP_LABEL_HYGIENE_DROPPED_8144") } catch (_: Throwable) {}
    }

    private fun runnerHorizonLane8144(lane: String): Boolean =
        (try { com.lifecyclebot.engine.TradeExitContract8077.runnerFromEntryLane8104(lane, false) } catch (_: Throwable) { false }) ||
            (try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false })

    private class Grade8076(val lane: String, val stamps: Map<String, Double>, var net: Double, var gross: Double, val atMs: Long)
    private val graded7997 = ConcurrentHashMap<String, Grade8076>()

    /** V5.0.7997 — ForwardReturnLabeler7731: a later checkpoint revises a graded label in every setup book it went to. */
    fun reviseLabel7997(mint: String, labelLane: String, oldNet: Double, newNet: Double, oldGross: Double, newGross: Double) {
        if (!newNet.isFinite()) return
        synchronized(this) {
            val g = graded7997["$mint|${labelLane.trim().uppercase()}"] ?: return
            // V5.0.8144 — a revision past the lane's executable hold horizon is not a return the strategy can bank.
            if (!CortexLabelHygiene8144.revisionCounts8144(runnerHorizonLane8144(g.lane), System.currentTimeMillis() - g.atMs)) {
                try { PipelineHealthCollector.labelInc("SETUP_REVISION_PAST_EXEC_HORIZON_8144") } catch (_: Throwable) {}
                return
            }
            val yo = g.net.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX)
            val yn = newNet.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX)  // V5.0.8322 — stored raw like any new label
            val ro = g.gross.isFinite() && g.gross >= CortexLedger7885.RUNNER_GROSS_PCT
            val rn = newGross.isFinite() && newGross >= CortexLedger7885.RUNNER_GROSS_PCT
            // V5.0.8080 — a scalp setup keeps its first-read grade: the later path is not a scalp's return.
            for ((setup, stamp) in g.stamps) if (setupHoldsLong8080(g.lane, setup)) books[g.lane]?.stats?.get(setup)?.let { st ->
                st.revise(yo, yn, ro, rn, st.revisionWeight(stamp))
            }
            g.net = yn; g.gross = newGross  // V5.0.8187 — the booked value is the clamped one
            bookRevision8077.incrementAndGet()
        }
    }

    /** Index of the setup in the lane menu (NO_TRIGGER = menu size), for the Cortex voter. */
    fun setupIndex(ts: TokenState, laneRaw: String, nowMs: Long): Double? {
        val lane = canon(laneRaw)
        val id = classify(ts, lane, nowMs) ?: return null
        return setupIndexFor8076(lane, id)
    }

    /** Pure, stable setup identities; appended setups retain their own bin. */
    fun setupIndexFor8076(lane: String, id: String): Double {
        // V5.0.7962 — the 7962 setups are appended AFTER NO_TRIGGER's bin, so every setup the
        // voter learned before (and NO_TRIGGER = base menu size) keeps its bin.
        // V5.0.7974 — RANGE_SUPPORT_SWING / SECOND_WAVE appended after them the same way.
        val added7962 = listOf("HL_RECLAIM", "EXPERT_ENTRY", "RANGE_SUPPORT_SWING", "SECOND_WAVE",
            // V5.0.7978 — appended the same way, so every earlier bin is unchanged.
            "SPECIALIST_EDGE", "FRESH_LAUNCH_MOMENTUM", "MICRO_CAP_IGNITION", "HOLDER_SURGE", "TURNOVER_SPIKE",
            "GRADUATION_APPROACH", "LIVESTREAM_LAUNCH", "COPYCAT_BETA", "COLOUR_SEQUENCE")
        // V5.0.8061 — this lane's 8061 extras are appended after every earlier bin as well.
        val added8061 = MENU[lane]?.let { base ->
            val upTo7978 = (if (lane in EXPERT_LANES_7962) base + EXPERT_ENTRY_7962 else base) +
                (if (lane in MEME_LANES_7978) MEME_EXTRAS_7978 else OTHER_EXTRAS_7978)
            extras8061(lane, upTo7978).map { it.id }
        }.orEmpty()
        val menu = menuIds(lane).filter { it !in added7962 && it !in added8061 }
        val i = menu.indexOf(id)
        val j = (added7962 + added8061).indexOf(id)
        return (if (i >= 0) i else if (j >= 0) menu.size + 1 + j else menu.size).toDouble()
    }

    /** The voter covers the complete current menu, including NO_TRIGGER and appended strategies. */
    fun setupBinEdges8076(): DoubleArray {
        val maximum = MENU.keys.maxOf { lane ->
            (menuIds(lane) + NO_TRIGGER).maxOf { setupIndexFor8076(lane, it).toInt() }
        }
        return DoubleArray(maximum) { it + 0.5 }
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
                    if (lane == "_grades8077" || lane == "_outcomeFwd8085") continue
                    if (lane == TIMING_KEY_8094) {
                        val tj = o.optJSONObject(lane) ?: continue
                        for (l in tj.keys()) {
                            val lj = tj.optJSONObject(l) ?: continue
                            val tb = timingBooks8094.getOrPut(l) { HashMap() }
                            for (k in lj.keys()) tb[k] = CortexLedger7885.labelStat8187().also { it.decode(lj.optString(k)) }
                        }
                        continue
                    }
                    val j = o.optJSONObject(lane) ?: continue
                    val b = books.getOrPut(lane) { Book() }
                    for (k in j.keys()) b.stats[k] = CortexLedger7885.labelStat8187().also { it.decode(j.optString(k)) }
                }
                // V5.0.8085 — forward results per classification outcome survive restarts (were session-only).
                o.optJSONObject("_outcomeFwd8085")?.let { fw ->
                    for (k in fw.keys()) {
                        val parts = fw.optString(k).split(',')
                        val n = parts.getOrNull(0)?.toDoubleOrNull() ?: continue
                        val sum = parts.getOrNull(1)?.toDoubleOrNull() ?: continue
                        // V5.0.8187 — a third field counts winners (rows saved before it read wins as unknown: -1).
                        val wins = parts.getOrNull(2)?.toDoubleOrNull() ?: -1.0
                        if (n > 0.0 && n.isFinite() && sum.isFinite() && k.contains('|')) outcomeForward8084[k] = doubleArrayOf(n, sum, wins)
                    }
                }
                o.optJSONArray("_grades8077")?.let { rows ->
                    val now = System.currentTimeMillis()
                    for (i in 0 until minOf(rows.length(), 600)) {
                        val row = rows.optJSONObject(i) ?: continue
                        val at = row.optLong("at"); val key = row.optString("k"); val lane = row.optString("l")
                        val net = row.optDouble("n", Double.NaN); val gross = row.optDouble("g", Double.NaN)
                        if (key.isBlank() || lane.isBlank() || !net.isFinite() || at <= 0L || at > now || now - at > 8L * 86_400_000L) continue
                        val weights = row.optJSONObject("s") ?: continue
                        val stamps = HashMap<String, Double>()
                        for (id in weights.keys()) {
                            val stamp = weights.optDouble(id, Double.NaN)
                            val st = books[lane]?.stats?.get(id) ?: continue
                            if (stamp.isFinite() && st.logScale <= stamp + 1e-9) stamps[id] = stamp
                        }
                        if (stamps.isNotEmpty()) graded7997[key] = Grade8076(lane, stamps, net, gross, at)
                    }
                }
                bookRevision8077.incrementAndGet()
            } catch (e: Throwable) { LearningPersistence.decodeFailed8086(BOOKS_KEY_7947, e) } finally {
                // V5.0.8086 — the row is on disk but would not read: stay unloaded (persist() is gated) and retry.
                if (LearningPersistence.readFailed8086(BOOKS_KEY_7947)) loaded = false
            }
        }
    }

    private const val PENDING_KEY_7920 = "LANE_PLAYBOOK_PENDING_7920"
    /** V5.0.7947 — setup records graded on the 5-minute, spike-credited read (old key "LANE_PLAYBOOK_7907" left untouched). */
    // V5.0.8064 — setup records restart on labels without spike credit (the 7947 books promised exits live never banked).
    private const val BOOKS_KEY_7947 = "LANE_PLAYBOOK_8076"
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
                    val rows = org.json.JSONArray()
                    graded7997.entries.sortedByDescending { it.value.atMs }.take(600).forEach { (key, g) ->
                        if (g.net.isFinite()) rows.put(org.json.JSONObject().put("k", key).put("l", g.lane).put("at", g.atMs)
                            .put("n", g.net).put("g", g.gross.takeIf { it.isFinite() } ?: org.json.JSONObject.NULL)
                            .put("s", org.json.JSONObject().also { j -> g.stamps.forEach { (id, stamp) ->
                                if (stamp.isFinite()) j.put(id, stamp)
                            } }))
                    }
                    o.put("_grades8077", rows)
                    o.put(TIMING_KEY_8094, org.json.JSONObject().also { tj ->
                        timingBooks8094.forEach { (l, tb) -> tj.put(l, org.json.JSONObject().also { j -> tb.forEach { (k, v) -> j.put(k, v.encode()) } }) }
                    })
                    o.put("_outcomeFwd8085", org.json.JSONObject().also { j ->
                        outcomeForward8084.forEach { (k, a) -> synchronized(a) { if (a[0] > 0.0 && a[1].isFinite()) j.put(k, "${a[0]},${a[1]},${a.getOrElse(2) { -1.0 }}") } }
                    })
                }.toString()
            }
            LearningPersistence.save(BOOKS_KEY_7947, json)
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        ensureLoaded()
        val sheet8323 = try { LaneCheatSheet8323.statusLines8323() } catch (_: Throwable) { "cheatSheet8323[unavailable]" }  // V5.0.8323 — outside the lock
        return synchronized(this) {
            val lanes = MENU.keys.joinToString("\n") { lane ->
                val tags = tagged.entries.filter { it.key.startsWith("$lane|") }.sortedByDescending { it.value.get() }
                    .joinToString(",") { "${it.key.substringAfter('|')}=${it.value.get()}" }.ifBlank { "-" } +
                    " alsoFired{" + firedCount.entries.filter { it.key.startsWith("$lane|") }.sortedByDescending { it.value.get() }.take(8)
                        .joinToString(",") { "${it.key.substringAfter('|')}=${it.value.get()}" }.ifBlank { "-" } + "}"
                val rec = (menuIds(lane) + NO_TRIGGER).joinToString(" ") { id ->
                    val st = statRaw8187(lane, id)
                    val ex8187 = stat(lane, id)
                    // V5.0.7955 — a setup holding graduated authority shows it (auth=0.62).
                    // V5.0.8187 — the stored (raw, capped) record is shown; authority and "exec" are the executable read.
                    val auth = setupAuthority8291(lane, id, ex8187)
                    val labelAuth8291 = setupFraction7955(ex8187)  // V5.0.8291 — shown beside the realised-capped authority
                    "$id[${if (st == null || st.n < 1.0) "prior ${"%+.1f".format(priorOf(lane, id))}" else "n${st.n.toInt()} ${"%+.1f".format(st.mean())}%${if (st.suspect8187()) "~raw${"%+.1f".format(st.rawMean8187())}%" else ""}${if (ex8187 != null && ex8187 !== st) " exec${"%+.1f".format(ex8187.mean())}%" else ""} exp ${"%+.1f".format(expected(lane, id))}"}${if (auth > 0.0 || labelAuth8291 > 0.0) " auth=${"%.2f".format(auth)}${if (auth < labelAuth8291) "(labels ${"%.2f".format(labelAuth8291)}, realisedCap8291)" else ""}" else ""}${hiveNote8291(lane, id)}]"
                }
                val oc = outcomeCounts8084.entries.filter { it.key.startsWith("$lane|") }.sortedByDescending { it.value.get() }
                    .joinToString(",") { "${it.key.substringAfter('|')}=${it.value.get()}" }.ifBlank { "-" }
                val miss = missingCounts8084.entries.filter { it.key.startsWith("$lane|") }.sortedByDescending { it.value.get() }.take(5)
                    .joinToString(",") { "${it.key.substringAfter('|')}=${it.value.get()}" }.ifBlank { "-" }
                val fwd = outcomeForward8084.entries.filter { it.key.startsWith("$lane|") }
                    .joinToString(",") { (k, a) -> "${k.substringAfter('|')} n${a[0].toInt()} ${outcomeMean8187(a)}" }.ifBlank { "-" }
                "      $lane tagged{$tags} record: $rec\n        8084 outcomes{$oc} missing{$miss} forward-by-outcome{$fwd}"
            }
            "rule=live needs a lane setup (NO_TRIGGER refused unless proven positive) · proven-losing setups refused · measured setups expected to lose refused (7948) · setup auth>=0.5 admits over a smaller cell (7955) · paper never refused\n" +
                "      refusals: ${refusals.entries.sortedByDescending { it.value.get() }.take(10).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}\n" +
                "      ${try { LaneRealisedGovernor8295.statusLine8295(MENU.keys, System.currentTimeMillis()) { l -> Cortex7885.laneRealised8295(l) } } catch (_: Throwable) { "realised8295[unavailable]" }}\n" +
                sheet8323.lines().joinToString("\n") { "      $it" } + "\n" + lanes
        }
    }
}
