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
    )

    class Setup(val id: String, val prior: Double, val fires: (F) -> Boolean)

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
    private val MICRO_PULLBACK_TREND = Setup("MICRO_PULLBACK_TREND", FLOW) { f -> ge(f.chg1h, 3.0) && ok(f.dd, 2.0, 8.0) && ge(f.bp, 50.0) }

    /** Lane -> its playbook (FIELD_MANUAL §4 families per lane; doc-derived). */
    private val MENU: Map<String, List<Setup>> = mapOf(
        "QUALITY" to listOf(PLAN_PULLBACK_RECLAIM, PLAN_BASE_BREAKOUT, PLAN_SWEEP_RECLAIM, HIGHER_LOW_PULLBACK, BREAKOUT_HOLD, RECLAIM_AFTER_WEAKNESS),
        "BLUECHIP" to listOf(PLAN_PULLBACK_RECLAIM, PLAN_BASE_BREAKOUT, TREND_PULLBACK, RELATIVE_STRENGTH, FLAG_CONTINUATION),
        "SHITCOIN" to listOf(PLAN_PULLBACK_RECLAIM, PLAN_BASE_BREAKOUT, LAUNCH_CONTINUATION, FIRST_PULLBACK, PRE_IGNITION_BASE),
        "EXPRESS" to listOf(PLAN_BASE_BREAKOUT, VOLUME_CONTINUATION, HIGHER_LOW_CONTINUATION, MICRO_FLAG),
        "MOONSHOT" to listOf(PLAN_BASE_BREAKOUT, LAUNCH_CONTINUATION, BREAKOUT_RUNNER, RS_LEADER, POST_EVENT_RECLAIM),
        "PROJECT_SNIPER" to listOf(PLAN_BASE_BREAKOUT, VERIFIED_LAUNCH, LOW_RUNUP_BASE, FIRST_PULLBACK),
        "DIP_HUNTER" to listOf(PLAN_SWEEP_RECLAIM, SWEEP_RECLAIM_FLOW, CAPITULATION_HIGHER_LOW, SUPPORT_FLIP),
        "MANIPULATED" to listOf(PLAN_SWEEP_RECLAIM, DISTRIBUTION_RECLAIM, SWEEP_RECLAIM_FLOW),
        "TREASURY" to listOf(PLAN_SWEEP_RECLAIM, RANGE_LOW_BOUNCE, RECLAIM_AFTER_WEAKNESS, MICRO_PULLBACK_TREND),
        "CASHGEN" to listOf(PLAN_SWEEP_RECLAIM, RANGE_LOW_BOUNCE, RECLAIM_AFTER_WEAKNESS, MICRO_PULLBACK_TREND),
        "CYCLIC" to listOf(PLAN_SWEEP_RECLAIM, PLAN_BASE_BREAKOUT, RANGE_LOW_BOUNCE, SUPPORT_FLIP),
        "CORE" to listOf(PLAN_PULLBACK_RECLAIM, PLAN_BASE_BREAKOUT, PLAN_SWEEP_RECLAIM, HIGHER_LOW_PULLBACK, RANGE_LOW_BOUNCE),
    )

    /** Pure: the setups of [lane]'s menu that [f] fires (empty = no trigger; unknown lane = null). */
    fun matches(lane: String, f: F): List<Setup>? = MENU[lane]?.filter { s -> try { s.fires(f) } catch (_: Throwable) { false } }

    fun menuIds(lane: String): List<String> = MENU[lane]?.map { it.id }.orEmpty()

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
        return F(
            age = nn(s?.ageMin), runup = nn(s?.runupFromLocalLowPct), pxPeak = pos(s?.currentVsPeak), dd = nn(s?.drawdownFromPeakPct),
            bp = nn(s?.buyPressurePct ?: ts.lastBuyPressurePct), liq = pos(s?.liquidityUsd), mcap = pos(s?.marketCapUsd),
            top = nn(s?.topHolderPct), chg5m = ts.lastPriceChange5m.takeIf { it.isFinite() } ?: Double.NaN,
            chg1h = ts.lastPriceChange1h.takeIf { it.isFinite() } ?: Double.NaN,
            holderGrowth = ts.holderGrowthRate.takeIf { ts.holderDataResolved && it.isFinite() } ?: Double.NaN,
            volAccel = volAcc?.takeIf { it.isFinite() && it > 0.0 } ?: Double.NaN,
            planSetup = plan.orEmpty(), launchPhase = phase.orEmpty(),
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
        if (setup == NO_TRIGGER) -1.0 else MENU[lane]?.firstOrNull { it.id == setup }?.prior ?: 0.0

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

    private fun classifyNow(ts: TokenState, lane: String, nowMs: Long): String? {
        val m = matches(lane, features(ts, nowMs)) ?: return null
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
                    setup == NO_TRIGGER && !noTriggerProvenPositive(st) -> "PLAYBOOK_NO_TRIGGER_7907_$lane"
                    setup != NO_TRIGGER && st != null && provenLosing(st, runner) -> "PLAYBOOK_SETUP_PROVEN_LOSING_7907_${lane}_$setup"
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

    /** Cortex7885.capture: tag the decision with its setup for its forward label. */
    fun capture(ts: TokenState, laneRaw: String, labelLane: String, nowMs: Long) {
        val lane = canon(laneRaw)
        val setup = classify(ts, lane, nowMs) ?: return
        if (pending.size > 8_000) pending.clear()
        pending["${ts.mint}|${labelLane.trim().uppercase()}"] = lane to setup
        tagged.computeIfAbsent("$lane|$setup") { AtomicLong(0) }.incrementAndGet()
    }

    /** Cortex7885.onLabel (graded horizon only): learn (lane, setup) from the forward label. */
    fun onLabel(mint: String, labelLane: String, netPct: Double, grossPct: Double) {
        ensureLoaded()
        val (lane, setup) = pending.remove("$mint|${labelLane.trim().uppercase()}") ?: return
        if (!netPct.isFinite()) return
        synchronized(this) {
            books.getOrPut(lane) { Book() }.stats.getOrPut(setup) { CortexLedger7885.Stat() }
                .add(netPct.coerceIn(CortexLedger7885.Y_MIN, CortexLedger7885.Y_MAX), grossPct.isFinite() && grossPct >= CortexLedger7885.RUNNER_GROSS_PCT)
        }
        if (sincePersist.incrementAndGet() >= 25) { sincePersist.set(0); persist() }
    }

    /** Index of the setup in the lane menu (NO_TRIGGER = menu size), for the Cortex voter. */
    fun setupIndex(ts: TokenState, laneRaw: String, nowMs: Long): Double? {
        val lane = canon(laneRaw)
        val id = classify(ts, lane, nowMs) ?: return null
        val menu = menuIds(lane)
        val i = menu.indexOf(id)
        return (if (i >= 0) i else menu.size).toDouble()
    }

    private fun ensureLoaded() {
        if (loaded) return
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
                val o = org.json.JSONObject(LearningPersistence.load("LANE_PLAYBOOK_7907") ?: return)
                for (lane in o.keys()) {
                    val j = o.optJSONObject(lane) ?: continue
                    val b = books.getOrPut(lane) { Book() }
                    for (k in j.keys()) b.stats[k] = CortexLedger7885.Stat().also { it.decode(j.optString(k)) }
                }
            } catch (_: Throwable) {}
        }
    }

    private const val PENDING_KEY_7920 = "LANE_PLAYBOOK_PENDING_7920"
    @Volatile private var lastPendingPersistMs = 0L

    /** Cortex7885.captureNow: save the pending setup tags at most every 2 minutes. */
    fun persistPendingMaybe(nowMs: Long) {
        if (nowMs - lastPendingPersistMs < 120_000L) return
        lastPendingPersistMs = nowMs
        try {
            val o = org.json.JSONObject()
            pending.entries.take(3_000).forEach { (k, v) -> o.put(k, "${v.first}|${v.second}") }
            LearningPersistence.save(PENDING_KEY_7920, o.toString())
        } catch (_: Throwable) {}
    }

    private fun persist() {
        try {
            val json = synchronized(this) {
                org.json.JSONObject().also { o ->
                    books.forEach { (lane, b) -> o.put(lane, org.json.JSONObject().also { j -> b.stats.forEach { (k, v) -> j.put(k, v.encode()) } }) }
                }.toString()
            }
            LearningPersistence.save("LANE_PLAYBOOK_7907", json)
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        ensureLoaded()
        return synchronized(this) {
            val lanes = MENU.keys.joinToString("\n") { lane ->
                val tags = tagged.entries.filter { it.key.startsWith("$lane|") }.sortedByDescending { it.value.get() }
                    .joinToString(",") { "${it.key.substringAfter('|')}=${it.value.get()}" }.ifBlank { "-" }
                val rec = (menuIds(lane) + NO_TRIGGER).joinToString(" ") { id ->
                    val st = stat(lane, id)
                    "$id[${if (st == null || st.n < 1.0) "prior ${"%+.1f".format(priorOf(lane, id))}" else "n${st.n.toInt()} ${"%+.1f".format(st.mean())}% exp ${"%+.1f".format(expected(lane, id))}"}]"
                }
                "      $lane tagged{$tags} record: $rec"
            }
            "rule=live needs a lane setup (NO_TRIGGER refused unless proven positive) · proven-losing setups refused · paper never refused\n" +
                "      refusals: ${refusals.entries.sortedByDescending { it.value.get() }.take(10).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}\n" + lanes
        }
    }
}
