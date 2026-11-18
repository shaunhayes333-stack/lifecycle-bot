package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8855 §ONE_GRADE_ON_THE_CLOCK_THE_BOT_FOLLOWS — owner: "we have too many stacked grades, scores etc. and grading at
 * 5 mins is totally dumb I keep saying that. we are now following coins across multiple time stamps".
 *
 * 5.0.8853 (LIVE, 03:06): FDG allowed 0 of 322; 179 were EdgeModel8537's refusal, earned on ONE read per decision (the
 * lane's 5- or 15-minute label) pooled over the whole LANE. The same report's stage records: BASE +1.0 .. +4.4 % with
 * 12-15 % runners in four lanes, LAUNCH -15.5 .. -22.5 %, PEAK about -10 %, DUMP -13.7 .. -20 % — a lane average is its
 * launch / peak / dump coins. And the decision loop's hand boosts (+350 for a coin under a minute on the watchlist, +160
 * for a pump source) spent about five looks in six on those stages.
 *
 * One grade replaces both uses. Every decision ForwardReturnLabeler7731 follows is read at 1, 5 and 15 minutes and, while
 * it keeps running, at 1, 4 and 24 hours (TrajectoryGrades8851). Its [pathOutcome8855] is what a position under the lane's
 * own stop holds after each read: stopped at the first read through the stop (ManagedBands8719.managed8719, the same fill
 * rule the model's target uses), otherwise the latest read. The record holds each decision ONCE, at its latest outcome:
 * a later read REVISES it ([revise8855]), so a coin that is still followed is graded on the clock it reached, and a coin
 * that stopped being followed keeps the outcome it ended on.
 *
 *  - refusal: keyed lane | stage (TokenMetricStageRouter at the decision). Once a lane-stage holds
 *    [EdgeModel8537.AUTH_MIN_N] refused decisions, IT decides whether the model may refuse there
 *    ([authority8855], EdgeModel8537.refusalAuthority8715's own test); until then the lane's label record decides, as
 *    before. One authority at a time, never both.
 *  - looking: the stage's record over every decision ranks the stages; BotService's watchlist order spends the span its
 *    hand freshness + source boosts had on that rank ([stageShare8855]) once two stages are graded.
 * Nothing here admits a trade: safety, structure, sellability, economics and every other door are unchanged.
 */
object TrajectoryVerdict8855 {
    /** The watchlist-order span the hand boosts had (350 freshest + 160 pump source), now spent on the stage record. */
    const val PRIORITY_SPAN_8855 = 510.0
    private const val STAGE_TTL_MS = 10_000L
    private val UNGRADED = setOf("", "UNKNOWN", "NO_SETUP")

    /**
     * Pure: the outcome ([first]) and stopped flag ([second]) of a position after a read of [netPct] (net of the round
     * trip), given what it held before. A stopped position never changes again; an unobserved read changes nothing.
     */
    fun pathOutcome8855(stopped: Boolean, prev: Double, netPct: Double, stopPct: Double, costPct: Double): Pair<Double, Boolean> {
        if (stopped || !netPct.isFinite()) return prev to stopped
        val stop = if (stopPct.isFinite() && stopPct > 0.0) stopPct else 100.0
        val cost = if (costPct.isFinite() && costPct > 0.0) costPct else 0.0
        val distance = (stop + cost).coerceAtMost(100.0)
        return if (netPct <= -distance) ManagedBands8719.managed8719(netPct, stopPct, costPct) to true
        else PocketLedger8610.managed8610(netPct) to false
    }

    /** A cohort whose members are revised in place: n counts decisions, the sums hold each one's latest outcome. */
    internal class Cohort(var n: Int = 0, var sum: Double = 0.0, var sq: Double = 0.0) {
        fun revise(old: Double, new: Double) {
            if (old.isFinite()) { sum -= old; sq -= old * old } else n++
            sum += new; sq += new * new
        }
        val mean: Double get() = if (n > 0) sum / n else Double.NaN
        val se: Double get() = if (n > 1) kotlin.math.sqrt(((sq / n) - mean * mean).coerceAtLeast(0.0) / n) else Double.POSITIVE_INFINITY
    }
    internal class Tally { val refused = Cohort(); val kept = Cohort() }

    private val byLaneStage = ConcurrentHashMap<String, Tally>()
    private val byStage = ConcurrentHashMap<String, Cohort>()
    private fun norm(s: String) = s.trim().uppercase().replace('|', '_').take(32)
    private fun key(lane: String, stage: String) = "${norm(lane)}|${norm(stage)}"
    /** The refusal record clips an outcome as EdgeModel8537's does, so one runner cannot acquit a cohort. */
    private fun clip(y: Double) = y.coerceIn(-100.0, EdgeModel8537.REFUSAL_CLIP_PCT_8715)

    /**
     * A followed decision's outcome moved from [old] (NaN = its first read) to [new]: [pred] is the model's prediction at
     * the decision (NaN = none; the stage record still counts it).
     */
    fun revise8855(lane: String, stage: String, pred: Double, old: Double, new: Double) {
        if (!new.isFinite() || norm(stage) in UNGRADED || lane.isBlank()) return
        val s = byStage.getOrPut(norm(stage)) { Cohort() }
        synchronized(s) { s.revise(if (old.isFinite()) clip(old) else Double.NaN, clip(new)) }
        if (!pred.isFinite()) return
        val t = byLaneStage.getOrPut(key(lane, stage)) { Tally() }
        synchronized(t) { (if (pred < 0.0) t.refused else t.kept).revise(if (old.isFinite()) clip(old) else Double.NaN, clip(new)) }
    }

    /**
     * May the model refuse a negative prediction in [lane] at [stage]? null while this lane-stage holds fewer than
     * [EdgeModel8537.AUTH_MIN_N] refused decisions (the lane's label record decides); else its own record's answer.
     */
    fun authority8855(lane: String, stage: String): Boolean? {
        if (norm(stage) in UNGRADED) return null
        val t = byLaneStage[key(lane, stage)] ?: return null
        synchronized(t) {
            if (t.refused.n < EdgeModel8537.AUTH_MIN_N) return null
            return EdgeModel8537.refusalAuthority8715(t.refused.n, t.refused.mean, t.refused.se, t.kept.n, t.kept.mean, t.kept.se)
        }
    }

    /** Pure: [stage]'s place among the graded [means] (stage -> mean), best 1.0 .. worst 0.0; 0.5 ungraded; null under two graded. */
    fun shareOf8855(means: Map<String, Double>, stage: String): Double? {
        if (means.size < 2) return null
        val order = means.entries.sortedBy { it.value }.map { it.key }
        val i = order.indexOf(norm(stage))
        return if (i < 0) 0.5 else i.toDouble() / (order.size - 1)
    }

    @Volatile private var meansAt = 0L
    @Volatile private var meansCache: Map<String, Double> = emptyMap()
    private fun gradedMeans(nowMs: Long): Map<String, Double> {
        if (nowMs - meansAt in 0L until STAGE_TTL_MS) return meansCache
        val m = HashMap<String, Double>()
        for ((k, c) in byStage) synchronized(c) { if (c.n >= EdgeModel8537.AUTH_MIN_N && c.mean.isFinite()) m[k] = c.mean }
        meansCache = m; meansAt = nowMs
        return m
    }

    /** BotService's watchlist order: [stage]'s share of [PRIORITY_SPAN_8855], or null until two stages are graded. */
    fun stageShare8855(stage: String, nowMs: Long = System.currentTimeMillis()): Double? = shareOf8855(gradedMeans(nowMs), stage)

    private val stageCache = ConcurrentHashMap<String, Pair<Long, String>>()

    /** The coin's stage now (TokenMetricStageRouter), read at most once per [STAGE_TTL_MS] a coin; "UNKNOWN" when unreadable. */
    fun stageOf8855(ts: TokenState, nowMs: Long = System.currentTimeMillis()): String {
        stageCache[ts.mint]?.let { (at, s) -> if (nowMs - at in 0L until STAGE_TTL_MS) return s }
        val s = try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts).stage.name } catch (_: Throwable) { "UNKNOWN" }
        if (stageCache.size > 4_000) stageCache.clear()
        stageCache[ts.mint] = nowMs to s
        return s
    }

    fun encode8855(): String = JSONObject()
        .put("laneStage", JSONObject().also { o -> byLaneStage.forEach { (k, t) -> synchronized(t) {
            o.put(k, JSONArray(listOf(t.refused.n, t.refused.sum, t.refused.sq, t.kept.n, t.kept.sum, t.kept.sq))) } } })
        .put("stage", JSONObject().also { o -> byStage.forEach { (k, c) -> synchronized(c) { o.put(k, JSONArray(listOf(c.n, c.sum, c.sq))) } } })
        .toString()

    fun restore8855(raw: String?) {
        if (raw.isNullOrBlank()) return
        val root = JSONObject(raw)
        fun ok(n: Int, sum: Double, sq: Double) = n >= 0 && sum.isFinite() && sq.isFinite() && sq >= 0.0
        root.optJSONObject("laneStage")?.let { o -> for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            if (!ok(a.optInt(0), a.optDouble(1), a.optDouble(2)) || !ok(a.optInt(3), a.optDouble(4), a.optDouble(5))) continue
            byLaneStage[k] = Tally().also { t ->
                t.refused.n = a.optInt(0); t.refused.sum = a.optDouble(1); t.refused.sq = a.optDouble(2)
                t.kept.n = a.optInt(3); t.kept.sum = a.optDouble(4); t.kept.sq = a.optDouble(5)
            }
        } }
        root.optJSONObject("stage")?.let { o -> for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            if (ok(a.optInt(0), a.optDouble(1), a.optDouble(2))) byStage[k] = Cohort(a.optInt(0), a.optDouble(1), a.optDouble(2))
        } }
        meansAt = 0L
    }

    private fun pct(v: Double) = if (v.isFinite()) "%+.1f".format(java.util.Locale.US, v) else "-"

    /** The report: each stage's record, and each lane-stage's refusal record with who decides there. */
    fun statusLine8855(): String {
        val stages = byStage.entries.sortedByDescending { synchronized(it.value) { it.value.mean.takeIf { m -> m.isFinite() } ?: -1e9 } }
            .joinToString(" ") { (k, c) -> synchronized(c) { "$k=n${c.n}/${pct(c.mean)}%±${pct(c.se).removePrefix("+")}" } }.ifBlank { "-" }
        val doors = byLaneStage.entries.sortedByDescending { synchronized(it.value) { it.value.refused.n } }.take(24).joinToString(" ") { (k, t) ->
            synchronized(t) {
                val who = if (t.refused.n < EdgeModel8537.AUTH_MIN_N) "LANE_RECORD"
                    else if (EdgeModel8537.refusalAuthority8715(t.refused.n, t.refused.mean, t.refused.se, t.kept.n, t.kept.mean, t.kept.se)) "REFUSES" else "STANDS_DOWN"
                "$k[refused=n${t.refused.n}/${pct(t.refused.mean)}%±${pct(t.refused.se).removePrefix("+")} kept=n${t.kept.n}/${pct(t.kept.mean)}% $who]"
            }
        }.ifBlank { "-" }
        return "trajectoryVerdict8855[stages{$stages} doors{$doors} rule=one outcome per followed decision, revised at each read " +
            "(1m/5m/15m, then 1h/4h/24h while it runs) under the lane's stop; a lane-stage with n>=${EdgeModel8537.AUTH_MIN_N} refused decides the model's refusal there]"
    }

    internal fun resetForTest8855() { byLaneStage.clear(); byStage.clear(); stageCache.clear(); meansAt = 0L; meansCache = emptyMap() }
}
