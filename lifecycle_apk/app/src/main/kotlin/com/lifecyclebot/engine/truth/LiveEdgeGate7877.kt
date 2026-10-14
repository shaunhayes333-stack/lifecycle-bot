package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7877 §ONLY_TRADE_WHAT_THE_BOT_PREDICTS_PAYS.
 *
 * Operator: "realistically we are only meant to be making trades that the bot
 * predicts makes money."
 *
 * 5.0.7876 live: canonical n=149 WR 28.2% PF 0.04; forward labels admitted60
 * net -6.4% (n=300) against refused60 net -2.4% (n=3150) — the live selection
 * was worse than the trades it skipped. LivePivotAuthority7876 already held
 * evidence-negative lanes to paper, but only while a loss limit was breached,
 * and only at lane resolution.
 *
 * This gate is always on in LIVE. A live entry proceeds only when there is a
 * measured, net-of-cost prediction that it pays:
 *
 *   1. the candidate's own forward-label cell (source | lane | mcap | age) has
 *      [CELL_MIN_N]+ 60-minute labels and mean minus one standard error is
 *      above [LIVE_MARGIN_PCT] (labels are already net of round-trip cost; the
 *      margin covers live slippage and latency the labels do not see), or
 *   2. the cell is thin and the lane's own evidence is PROVEN under
 *      LivePivotAuthority7876.verdict (live record, lane labels, or paper above
 *      its cost margin).
 *
 * A cell proven below the margin refuses even when the lane is proven: the
 * narrower evidence wins, and that cell trades paper/shadow until its numbers
 * turn — no code change, no operator toggle. V5.0.7880: a candidate with no
 * measured cell yet explores LIVE (a fresh install has no labels at all), so
 * the gate refuses only what the bot has measured to lose. Exits are never
 * touched here.
 */
object LiveEdgeGate7877 {
    private const val CELL_MIN_N = 30
    private const val LIVE_MARGIN_PCT = 2.0
    /**
     * V5.0.7878 — runner lanes (MOONSHOT, sniper, shitcoin ...) are judged on
     * the label mean itself (already net of the round-trip cost at a $5 ticket):
     * their payoff is a 10-20% runner tail, so a lower 60-minute mean with a
     * fat right tail is the profile, not a defect.
     */
    private const val RUNNER_MARGIN_PCT = 0.0
    private const val PROVEN_NEGATIVE_PCT = -2.0
    /**
     * V5.0.7879 — the tail test. 5.0.7878 on device: PLANWAIT_LAUNCH_REFUSED
     * (n=1043, mean +3.1% net, runner-rate 15%) was overruled 0 times, and
     * MOONSHOT was refused 1,233 times, because mean minus one standard error
     * could not clear zero: the +500% runners that make the mean also blow up
     * the standard error. For a low-win-rate, big-tail lane that is the profile,
     * not noise. A cohort with [TAIL_MIN_N]+ labels, a positive net mean and at
     * least [TAIL_MIN_RUNNER_RATE] of its tokens reaching +50% inside the hour
     * is tradeable on its point estimate.
     */
    private const val TAIL_MIN_N = 100
    private const val TAIL_MIN_RUNNER_RATE = 0.10

    enum class Source { CELL, LANE, NONE }

    /**
     * Pure (V5.0.8022): a cell whose observations mostly LOST their mark before the read is not a measurement
     * of the coins in it — it is a measurement of the few that survived. 8018: CRYPTO_ALT's one cell
     * (XASSET_CRYPTO_ALT_CRYPTO | MC_UNKNOWN | AGE_UNKNOWN) read +14.6% on 97 labels with 69 more lost, and
     * the gate admitted 49 live buys on it (the lane's realised record: 122 closes, -6.65% a trade, -0.69 SOL).
     * Under [MIN_RESOLVED_SHARE_7944] resolved, the cell refuses instead of admitting.
     */
    fun mostlyUnpriced8022(cell: ForwardReturnLabeler7731.CellStat?): Verdict? =
        if (cell != null && cell.n60 >= CELL_MIN_N && cell.resolvedShare < MIN_RESOLVED_SHARE_7944)
            Verdict(false, Source.CELL, Double.NaN, "CELL_MOSTLY_UNPRICED_8022_${(cell.resolvedShare * 100).toInt()}PCT_RESOLVED")
        else null

    data class Verdict(val allow: Boolean, val source: Source, val edgePct: Double, val why: String)

    private val allowed = ConcurrentHashMap<String, AtomicLong>()
    private val refused = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Pure. [cell] is the candidate's forward-label cell (null when unseen);
     * [laneProven] is LivePivotAuthority7876's lane verdict == PROVEN.
     */
    fun judge(cell: ForwardReturnLabeler7731.CellStat?, laneProven: Boolean, marginPct: Double = LIVE_MARGIN_PCT): Verdict {
        mostlyUnpriced8022(cell)?.let { return it }
        if (cell != null && cell.n60 >= CELL_MIN_N) {
            val se = if (cell.stderr60Pct.isFinite()) cell.stderr60Pct else Double.POSITIVE_INFINITY
            val lower = cell.meanNet60Pct - se
            // A launch cell's runners pay after the hour (7769): the 4-hour mean
            // may carry a cell whose 60-minute floor is under the margin.
            val lower240 = if (cell.n240 >= CELL_MIN_N) cell.meanNet240Pct - (if (se.isFinite()) se else 0.0) else Double.NEGATIVE_INFINITY
            val best = maxOf(lower, lower240)
            return if (best > marginPct) {
                Verdict(true, Source.CELL, best, "CELL_EDGE_${"%.1f".format(best)}PCT")
            } else {
                Verdict(false, Source.CELL, best, "CELL_EDGE_BELOW_MARGIN_${"%.1f".format(best)}PCT")
            }
        }
        // V5.0.7880 — no measurement is not a negative measurement. On a fresh
        // install every store is empty (5.0.7879: cells=0/0, observed=0) and the
        // 7877 rule "no predicted edge, no trade" refused every candidate the
        // bot saw (MOONSHOT 1,589, CASHGEN 462, SHITCOIN 341 ...), so it could
        // never make the labels that would have let it trade. Operator: "it has
        // to be tuned or traded from a trade one mindset." An unmeasured cell
        // trades (route-minimum, every other gate still applies) and is refused
        // only once its own labels say it loses.
        return if (laneProven) Verdict(true, Source.LANE, 0.0, "LANE_PROVEN_7876")
        else Verdict(true, Source.NONE, 0.0, "EXPLORE_UNMEASURED")
    }

    /**
     * Pure. V5.0.7878 — a runner-lane candidate is judged on the best measured
     * cohort it actually belongs to: its own cell, and the plan cohort its tape
     * puts it in right now (PLANWAIT_<read>, PLANWAIT_LAUNCH_<verdict>). 5.0.7876:
     * MOONSHOT's own picks n=65 net -14.6% runner-rate 2%, while the fresh
     * launches the selector refused (PLANWAIT_LAUNCH_REFUSED) were n=1043 net
     * +3.1% runner-rate 15% — the tail the lane exists for was in the cohort it
     * was not allowed to buy. Its own cell proven clearly negative still refuses.
     */
    fun judgeRunner(cell: ForwardReturnLabeler7731.CellStat?, cohorts: List<ForwardReturnLabeler7731.CellStat?>, laneProven: Boolean): Verdict {
        mostlyUnpriced8022(cell)?.let { return it }
        if (cell != null && cell.n60 >= CELL_MIN_N) {
            val se = if (cell.stderr60Pct.isFinite()) cell.stderr60Pct else 0.0
            val late = cell.n240 >= CELL_MIN_N && cell.meanNet240Pct >= 0.0
            if (cell.meanNet60Pct + se < PROVEN_NEGATIVE_PCT && !late) {
                return Verdict(false, Source.CELL, cell.meanNet60Pct + se, "RUNNER_CELL_PROVEN_NEGATIVE_${"%.1f".format(cell.meanNet60Pct)}PCT")
            }
        }
        val measured = (listOf(cell) + cohorts).filterNotNull().filter { it.n60 >= CELL_MIN_N }
        if (measured.isEmpty()) return judge(null, laneProven, RUNNER_MARGIN_PCT)
        val verdicts = measured.map { runnerVerdict(it) }
        val best = verdicts.firstOrNull { it.allow } ?: verdicts.maxByOrNull { it.edgePct }!!
        if (best.allow) return best.copy(why = "RUNNER_" + best.why)
        // V5.0.7880 — a runner lane is refused only when every measured cohort it
        // sits in is provably losing (mean plus one standard error below zero).
        // A measured-but-uncertain cohort keeps exploring: the tail needs shots.
        val provenLosing = measured.all { m ->
            val se = if (m.stderr60Pct.isFinite()) m.stderr60Pct else 0.0
            m.meanNet60Pct + se < 0.0 && !(m.n240 >= CELL_MIN_N && m.meanNet240Pct >= 0.0)
        }
        return if (provenLosing) best.copy(why = "RUNNER_COHORT_PROVEN_LOSING")
        else Verdict(true, Source.NONE, best.edgePct, "RUNNER_EXPLORE_UNCERTAIN")
    }

    /** Pure: one measured cohort under the runner bar — mean-se, or the tail test. */
    private fun runnerVerdict(stat: ForwardReturnLabeler7731.CellStat): Verdict {
        val v = judge(stat, false, RUNNER_MARGIN_PCT)
        if (v.allow) return v
        if (stat.n60 >= TAIL_MIN_N && stat.meanNet60Pct > RUNNER_MARGIN_PCT && stat.runnerRate60 >= TAIL_MIN_RUNNER_RATE) {
            return Verdict(true, Source.CELL, stat.meanNet60Pct,
                "TAIL_EV_${"%.1f".format(stat.meanNet60Pct)}PCT_RUN${(stat.runnerRate60 * 100).toInt()}")
        }
        return v
    }

    /** Pure: a runner lane's plan-wait cohort is evidence enough to overrule that wait. */
    fun runnerCohortAllows(stat: ForwardReturnLabeler7731.CellStat?): Boolean =
        stat != null && stat.n60 >= CELL_MIN_N && runnerVerdict(stat).allow

    /** Side-effect-free read for sizing and diagnostics. */
    fun verdictFor(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Verdict {
        val l = CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
        try { TradeShapeLearner7883.shapeRefusal(ts, l) }
            catch (_: Throwable) { null }?.let { return Verdict(false, Source.CELL, 0.0, it) }
        // V5.0.7932 — a launch the ladder has proven is judged on the ladder (the trade
        // the plan will actually run), not on 60-minute hold cohorts.
        if (FreshLaunchSelector7737.ladderProven7932(ts, nowMs)) return Verdict(true, Source.CELL, 0.0, "LAUNCH_LADDER_PROVEN_7932")
        val cell = try { ForwardReturnLabeler7731.cellStatFor(ts, l, nowMs) } catch (_: Throwable) { null }
        // V5.0.7938 — a lane whose own forward labels are proven losing does not trade
        // live on a borrowed cohort. 5.0.7937 (hours live): MOONSHOT lane labels n=251
        // net -8.2% and live realised n=63 at -10%, admitted through the pooled
        // PLANWAIT_LAUNCH_NEGATIVE cohort (+3.3%) it does not trade like. Only the
        // candidate's own measured-positive cell (or the ladder, above) still admits.
        val laneStat7938 = try { ForwardReturnLabeler7731.laneStatFor7737(l) } catch (_: Throwable) { null }
        // V5.0.7948 — the plan cohort this tape puts the candidate in, when its own labels prove it pays.
        val cohort7948 by lazy { provenCohort7948(ts, nowMs) }
        // V5.0.7939 — a runner lane is judged on its runner setups, not its lane mean:
        // a fired playbook setup with a positive expected record still gets its shot.
        if (laneProvenLosing7938(laneStat7938) && !runnerSetupFires7939(ts, l, nowMs)) {
            val own = judge(cell, false)
            // V5.0.7948 review — a pooled cross-lane cohort never overrules the lane's own proven loss
            // (the exact borrowed-cohort admission 7938 was written to stop).
            if (!(own.allow && own.source == Source.CELL)) {
                return Verdict(false, Source.CELL, laneStat7938?.meanNet60Pct ?: 0.0, "LANE_PROVEN_LOSING_7938_${"%.1f".format(laneStat7938?.meanNet60Pct ?: 0.0)}PCT")
            }
        }
        val laneProven = try {
            LivePivotAuthority7876.laneVerdict(l, nowMs) == LivePivotAuthority7876.Evidence.PROVEN
        } catch (_: Throwable) { false }
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(l) } catch (_: Throwable) { false }
        val v = if (!runner) judge(cell, laneProven) else {
            val cohorts = try {
                TradePlan7739.runnerCohortKeys7878(ts, nowMs).map { ForwardReturnLabeler7731.laneStatFor7737(it) }
            } catch (_: Throwable) { emptyList() }
            judgeRunner(cell, cohorts, laneProven)
        }
        if (v.allow) return v
        // V5.0.7948 — the candidate's own cell refused on fewer labels than the proven cohort it sits in.
        val cohort = cohort7948
        if (cohort != null && cohortOverrulesSmaller7948(cohort, cell?.n60 ?: 0)) {
            try { PipelineHealthCollector.labelInc("LIVE_EDGE_COHORT_PROVEN_7948_$l") } catch (_: Throwable) {}
            return Verdict(true, Source.CELL, cohort.meanNet60Pct, "COHORT_PROVEN_7948_${cohort.key.take(28)}")
        }
        // V5.0.7955 — the candidate's classified playbook setup holds graduated authority
        // (from 15 labels, LanePlaybook7907.setupFraction7955) on more labels than its own cell.
        val setup7955 = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.classifiedAuthority7955(ts, l, nowMs) } catch (_: Throwable) { null }
        if (setupAdmits7955(setup7955, cell?.n60 ?: 0, v.why)) {
            try { PipelineHealthCollector.labelInc("LIVE_EDGE_SETUP_AUTHORITY_7955_$l") } catch (_: Throwable) {}
            return Verdict(true, Source.CELL, 0.0, "SETUP_AUTHORITY_7955_${"%.2f".format(setup7955?.getOrNull(0) ?: 0.0)}")
        }
        return v
    }

    /**
     * Pure (V5.0.7955): a classified setup's [authority, labels] admits over a refusal when
     * its authority is at least 0.5 and it rests on more labels than the candidate's cell.
     * A cell or cohort proven negative / losing keeps its refusal.
     */
    fun setupAdmits7955(auth: DoubleArray?, cellN: Int, refusedWhy: String): Boolean {
        if (auth == null || auth.size < 2) return false
        if (refusedWhy.contains("PROVEN_NEGATIVE") || refusedWhy.contains("PROVEN_LOSING")) return false
        return auth[0].isFinite() && auth[0] >= 0.5 && auth[1] > cellN.coerceAtLeast(0)
    }

    // ── V5.0.7948 §THE_LARGER_MEASURED_SAMPLE_DECIDES ──
    //
    // 5.0.7947 live: forward labels admitted n=13 mean -5.4% against refused n=156
    // +9.7% (earlier builds admitted -5.1% vs refused +5.3% on n=8,452), and the
    // refused cohort PLANWAIT_LAUNCH_NEGATIVE n=2,164 read +13.1%. A cohort proven
    // on its own labels was still refused downstream by smaller samples (a lane
    // NO_TRIGGER record n=40, a lane mean, a setup on six labels). A refusal that
    // rests on a measured sample now yields to a proven-positive cohort the
    // candidate sits in when that cohort is measured on MORE labels. Safety
    // (rug-prone, mayhem, structural launch shapes, peak/dump stages) never yields.
    private const val COHORT_MIN_N_7948 = 100

    /**
     * Pure: a plan cohort proven to pay on its own labels: 100+ reads, mostly
     * resolved, a positive net mean, and either mean minus one standard error
     * above zero or the runner tail (>= 10% of its tokens reaching the runner bar).
     */
    fun cohortProvenPositive7948(stat: ForwardReturnLabeler7731.CellStat?): Boolean {
        if (stat == null || stat.n60 < COHORT_MIN_N_7948) return false
        if (stat.resolvedShare < MIN_RESOLVED_SHARE_7944) return false
        if (!(stat.meanNet60Pct > 0.0)) return false
        val se = if (stat.stderr60Pct.isFinite()) stat.stderr60Pct else return false
        // V5.0.7948 review — significantly positive only; a runner tail alone is not proof.
        return stat.meanNet60Pct - se > 0.0
    }

    /** Pure: does [cohort] (proven positive) outweigh a refusal measured on [refusalSampleN] labels? */
    fun cohortOverrulesSmaller7948(cohort: ForwardReturnLabeler7731.CellStat?, refusalSampleN: Int): Boolean =
        cohort != null && cohortProvenPositive7948(cohort) && cohort.n60 > refusalSampleN.coerceAtLeast(0)

    /** The largest proven-positive plan cohort this candidate's tape puts it in now, or null. Side-effect free. */
    fun provenCohort7948(ts: TokenState, nowMs: Long = System.currentTimeMillis()): ForwardReturnLabeler7731.CellStat? = try {
        TradePlan7739.runnerCohortKeys7878(ts, nowMs)
            .mapNotNull { k -> try { ForwardReturnLabeler7731.laneStatFor7737(k) } catch (_: Throwable) { null } }
            .filter { cohortProvenPositive7948(it) }
            .maxByOrNull { it.n60 }
    } catch (_: Throwable) { null }

    /**
     * The label count a prior refusal rests on, or null when it is not a measured
     * read a larger cohort may outweigh (safety, peak/dump stages, plain priors).
     */
    private fun refusalSampleN7948(ts: TokenState, lane: String, prior: String, nowMs: Long = System.currentTimeMillis()): Int? {
        if (prior.endsWith("_PEAK_EXHAUSTION") || prior.endsWith("_DUMPING") || prior.contains("RUG")) return null
        // V5.0.7948 review — a lane's own PROVEN_LOSING record (setup, NO_TRIGGER, stage) is never
        // outweighed by a pooled cohort, nor is a runner lane's runner-setups-only rule (7939).
        if (prior.contains("PROVEN_LOSING")) return null
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        if (runner && prior.startsWith("PLAYBOOK_NO_TRIGGER")) return null
        return when {
            prior.startsWith("PLAYBOOK_") -> try { com.lifecyclebot.engine.cortex.LanePlaybook7907.classifiedSampleN7948(ts, lane, nowMs) } catch (_: Throwable) { null }
            prior.startsWith("STAGE_PROVEN_LOSING_7928") -> try {
                val fit = com.lifecyclebot.engine.TokenMetricStageRouter.laneFit(ts, lane)
                ForwardReturnLabeler7731.stageStatFor7928(fit.lane, fit.stage.name)?.n60 ?: 0
            } catch (_: Throwable) { null }
            else -> null
        }
    }

    /**
     * Pure. V5.0.7938 — the lane's own 60-minute labels prove it loses: 100+ labels,
     * mean plus one standard error under -2% net, and no positive 4-hour record (a
     * runner lane whose tail pays after the hour is not refused).
     */
    fun laneProvenLosing7938(stat: ForwardReturnLabeler7731.CellStat?): Boolean {
        if (stat == null || stat.n60 < LANE_LOSING_MIN_N_7938) return false
        val se = if (stat.stderr60Pct.isFinite()) stat.stderr60Pct else return false
        if (stat.n240 >= CELL_MIN_N && stat.meanNet240Pct >= 0.0) return false
        return stat.meanNet60Pct + se < PROVEN_NEGATIVE_PCT
    }

    private const val LANE_LOSING_MIN_N_7938 = 100

    /**
     * V5.0.7950 — live: the chart reader says BUY and nothing hard refuses (the
     * safety tier's HARD_BLOCK, Mayhem Mode). Learned and plan refusals do not apply:
     * the chart library's thousands of measured outcomes are the evidence.
     */
    fun chartAdmits7950(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) return false
        if (com.lifecyclebot.engine.MayhemMode7943.liveRefusal(ts, nowMs) != null) return false
        val ok = try { com.lifecyclebot.engine.chart.ChartReader7950.saysBuy(ts.mint, nowMs) } catch (_: Throwable) { false }
        if (ok) try { PipelineHealthCollector.labelInc("LIVE_EDGE_CHART_ADMIT_7950_${lane.uppercase()}") } catch (_: Throwable) {}
        return ok
    }
    internal const val MIN_RESOLVED_SHARE_7944 = 0.75

    /**
     * Pure. V5.0.7941 — the lane's own 60-minute labels prove its candidate pool pays:
     * 100+ labels and mean minus one standard error above zero (labels are net of cost).
     */
    fun laneProvenPositive7941(stat: ForwardReturnLabeler7731.CellStat?): Boolean {
        if (stat == null || stat.n60 < LANE_LOSING_MIN_N_7938) return false
        val se = if (stat.stderr60Pct.isFinite()) stat.stderr60Pct else return false
        // V5.0.7944 — vanished marks are now booked inside the mean (last real price, or
        // -100% when the pool died: ForwardReturnLabeler7731.classifyVanished7944); what is
        // still LOST is a data gap. The pool must still be mostly read, not mostly gaps.
        return stat.meanNet60Pct - se > 0.0 && stat.resolvedShare >= MIN_RESOLVED_SHARE_7944
    }

    private fun runnerSetupFires7939(ts: TokenState, lane: String, nowMs: Long): Boolean {
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        if (!runner) return false
        val pb = com.lifecyclebot.engine.cortex.LanePlaybook7907.playbookScore7937(ts, lane, nowMs) ?: return false
        return pb > 50.0
    }

    /** Pure: a refusal resting on a prior alone (no measurement says the entry loses). */
    fun priorOnly7930(why: String): Boolean =
        (why.contains("NO_TRIGGER") || why.contains("STAGE_LANE_MISFIT")) && !why.contains("PROVEN_LOSING") &&
            // Peak exhaustion and dumping need a reclaim; a stage-blind cell edge cannot vouch for that.
            !why.endsWith("_PEAK_EXHAUSTION") && !why.endsWith("_DUMPING")

    private fun measuredOverrules7930(ts: TokenState, lane: String, why: String, nowMs: Long): Boolean {
        val v = try { verdictFor(ts, lane, nowMs) } catch (_: Throwable) { null }
        val ok = (v != null && v.allow && v.source == Source.CELL) ||
            com.lifecyclebot.engine.cortex.Cortex7885.overrulesEdgeRefusal(ts, lane, why)
        if (ok) try { PipelineHealthCollector.labelInc("LIVE_PRIOR_REFUSAL_OVERRULED_BY_EVIDENCE_7930") } catch (_: Throwable) {}
        return ok
    }

    /** LIVE refusal reason, or null to admit. Paper is never refused. */
    /**
     * V5.0.7975 — mints this gate admitted on POSITIVE evidence (a specialist, a cell
     * clearing +15% net over the lane-wide refusal, a full-authority Cortex STRONG read, a
     * proven runner cell). TradePlan7739 plans them instead of waiting for a chart setup:
     * 5.0.7972 missed BUM +32,922%, hehe +2,906%, AIA +1,577% on NO_PLAN_WAIT_7739 after
     * this gate had already let them through.
     */
    private val positive7975 = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun notePositive7975(mint: String, nowMs: Long) {
        positive7975[mint] = nowMs
        if (positive7975.size > 2_000) positive7975.entries.removeIf { nowMs - it.value > POSITIVE_TTL_MS_7975 }
    }

    /** TradePlan7739.liveBlockReason: was [mint] just admitted here on positive evidence? */
    fun positiveAdmit7975(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        positive7975[mint]?.let { nowMs - it in 0L..POSITIVE_TTL_MS_7975 } == true

    private const val POSITIVE_TTL_MS_7975 = 2L * 60_000L

    // ── V5.0.8083 §ONE_CANDIDATE_ASSESSMENT ──
    enum class Verdict8083 { ADMIT, DEFER, REFUSE }

    /** Immutable: the single live entry decision for one candidate (mint + lane + setup), read by FDG / sizing / plan. */
    data class Assessment8083(
        val mint: String, val lane: String, val setup: String, val verdict: Verdict8083, val reason: String,
        val sizeFactor: Double, val lanePriorLosing: Boolean, val atMs: Long,
    )

    private val assessments8083 = ConcurrentHashMap<String, Assessment8083>()
    private val verdictCounts8083 = ConcurrentHashMap<String, AtomicLong>()
    private const val ASSESSMENT_TTL_MS_8083 = 120_000L

    private fun recordAssessment8083(ts: TokenState, lane: String, setup: String?, verdict: Verdict8083, reason: String, sizeFactor: Double, nowMs: Long) {
        val losing = sizeFactor < 1.0
        val a = Assessment8083(ts.mint, lane, setup ?: "UNCLASSIFIED", verdict, reason, sizeFactor.coerceIn(LiveTruth8064.LOSING_LANE_SIZE_FACTOR_8083, 1.0), losing, nowMs)
        assessments8083["${ts.mint}|$lane"] = a
        if (assessments8083.size > 3_000) assessments8083.entries.removeIf { nowMs - it.value.atMs > ASSESSMENT_TTL_MS_8083 }
        verdictCounts8083.computeIfAbsent(verdict.name) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIVE_ENTRY_ASSESSMENT_8083_${verdict.name}_$lane") } catch (_: Throwable) {}
    }

    /** V5.0.8083 — the fresh live assessment of [mint] in [lane], or null. */
    fun assessment8083(mint: String, lane: String, nowMs: Long = System.currentTimeMillis()): Assessment8083? {
        val l = CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
        return assessments8083["$mint|$l"]?.takeIf { nowMs - it.atMs in 0L..ASSESSMENT_TTL_MS_8083 }
    }

    /** V5.0.8083 — the size factor of an ADMITTED live assessment (bounded lane negative prior), 1.0 otherwise. */
    fun sizeFactor8083(mint: String, lane: String, nowMs: Long = System.currentTimeMillis()): Double =
        assessment8083(mint, lane, nowMs)?.takeIf { it.verdict == Verdict8083.ADMIT }?.sizeFactor ?: 1.0

    fun assessmentLine8083(): String = "verdicts=" + verdictCounts8083.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }

    fun liveRefusal(ts: TokenState, lane: String, paper: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        var auditedAdmission8076 = false
        // V5.0.8037 — every edge read below sees the observed liquidity, not a missing tick's 0.
        try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) {}
        // V5.0.8038 — a live candidate's pool is read from the chain in the background, ready for the buy door.
        if (!paper) try { OnChainDepth8038.request8038(ts) } catch (_: Throwable) {}
        // V5.0.8048 — a dead launch (empty curve, launch cap) is refused here, before the FDG / sizing / ticket.
        if (!paper) (try { OnChainDepth8038.earlyLaunchRefusal8048(ts) } catch (_: Throwable) { null })?.let { observeTail7996(ts, lane, it, nowMs); return it }
        // V5.0.8059 — PROVEN-ONLY LIVE. Owner: "its still losing money!!!" — 5.0.8055: 29 live closes, 2 wins, PF 0.11.
        // The forward labeler grades every coin the bot sees (thousands a day) whether it buys or not, so a live buy
        // teaches nothing the labels don't: it only costs SOL. Live money now goes ONLY into a coin showing a setup
        // its lane has proven pays (LanePlaybook7907.provenSetup8056: 30+ graded labels, mean - SE > 0 net of cost).
        // Everything else is watched and graded; as setups prove themselves they unlock automatically. Every gate
        // after this one (watch first, Cortex, net edge, lane fit, dead launch, buy door) still applies.
        if (!paper) {
            val l8059 = CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
            // V5.0.8074 — unmanaged live buys from before a restart are handed to wallet adoption; a blind wallet buys nothing.
            try { LiveBootRecovery8074.tick8074(nowMs) } catch (_: Throwable) {}
            (try { LiveBootRecovery8074.blind8074(nowMs) } catch (_: Throwable) { null })?.let { observeTail7996(ts, lane, it, nowMs); return it }
            // V5.0.8083 §NO_LANE_WIDE_DEADLOCK (replaces the 8064 lane-wide refusal and its 8078 full-authority-only escape).
            // The lane's live book is a bounded NEGATIVE PRIOR: it shapes size and demands this candidate's OWN validated
            // positive evidence below, but never refuses every token, setup and strategy of the lane on its own. Only the
            // candidate's own setup proven losing on live closes refuses (LiveTruth8064.setupRefusal8083).
            // V5.0.8094 — the setup this candidate trades as (multi-strategy selection), so refusals and assessments are
            // filed under the strategy actually traded.
            val setup8083 = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(ts, l8059, nowMs) } catch (_: Throwable) { null }
            (try { LiveTruth8064.setupRefusal8083(l8059, setup8083, nowMs) } catch (_: Throwable) { null })?.let { r8083 ->
                recordAssessment8083(ts, l8059, setup8083, Verdict8083.REFUSE, r8083, 1.0, nowMs)
                observeTail7996(ts, lane, r8083, nowMs); return r8083
            }
            // V5.0.8094 — timing: the picked setup's record AT THIS POINT OF THE MOVE (pullback / mid / extended / peak) is
            // losing — wait for a band where it pays (graded at the executable price).
            // (a token whose own cell / kind is proven has already paid for its timing: the labels start at the fill latency)
            if (!cellOrKindProven8094(ts, l8059, nowMs)) (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.timingNegative8094(ts, l8059, nowMs) } catch (_: Throwable) { null })?.let { at ->
                val r8094 = "TIMING_RECORD_NEGATIVE_8094"
                try { PipelineHealthCollector.labelInc("${r8094}_${l8059}_$at") } catch (_: Throwable) {}
                recordAssessment8083(ts, l8059, setup8083, Verdict8083.DEFER, r8094, 1.0, nowMs)
                observeTail7996(ts, lane, r8094, nowMs); return r8094
            }
            val prior8083 = try { LiveTruth8064.lanePrior8083(l8059, nowMs) } catch (_: Throwable) { null }
            val proven8059 = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.provenSetup8056(ts, l8059, nowMs) } catch (_: Throwable) { null }
            // V5.0.8094 — no fired setup is proven and the classified setup's own label record is losing (12+ labels,
            // mean + SE < 0, no runner-tail exemption): refused. A proven fired setup is the strategy instead (multi-strategy).
            if (proven8059 == null && !cellOrKindProven8094(ts, l8059, nowMs)) (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.pickedSetupNegative8094(ts, l8059, nowMs) } catch (_: Throwable) { null })?.let { neg ->
                val r8094 = "PICKED_SETUP_RECORD_NEGATIVE_8094"
                try { PipelineHealthCollector.labelInc("${r8094}_${l8059}_$neg") } catch (_: Throwable) {}
                recordAssessment8083(ts, l8059, setup8083, Verdict8083.REFUSE, r8094, 1.0, nowMs)
                observeTail7996(ts, lane, r8094, nowMs); return r8094
            }
            // V5.0.8094 — the tactic this coin would trade as has a losing realised record in this lane: only a proven setup
            // (honest labels, mean - SE > 0 on 30+) may still trade it, so the tactic can re-prove itself; nothing else does.
            // (a token whose own cell / kind is proven is not refused on the tactic alone: 8094 missed HULKED +1984%,
            // smol +1862%, MrBeast +972% here.)
            if (proven8059 == null && !cellOrKindProven8094(ts, l8059, nowMs)) (try { com.lifecyclebot.engine.learning.TacticSwitcher.losingTacticFor8094(ts, l8059) } catch (_: Throwable) { null })?.let { t ->
                val r8094 = "TACTIC_RECORD_LOSING_8094"
                try { PipelineHealthCollector.labelInc("${r8094}_${l8059}_$t") } catch (_: Throwable) {}
                recordAssessment8083(ts, l8059, setup8083, Verdict8083.REFUSE, r8094, 1.0, nowMs)
                observeTail7996(ts, lane, r8094, nowMs); return r8094
            }
            // V5.0.8060 — any MEASURED edge counts, not only a named setup: 5.0.8059 refused 4,879 vs 145 admitted and
            // turned away POKEMON +1,077% and Gemini +708% — runners often fire no named setup. The coin's own cell
            // proven (30+ labels, mean - SE > 0), a lane-beating runner cell, a proven specialist or a tail ticket also
            // carry live money.
            // V5.0.8072 — or a runner ticket: a small capped budget of unproven buys in the best honest-label group
            // (graduated PumpSwap coins at $10k-$100k), held on the lifecycle rules (RunnerTicket8073).
            if (proven8059 == null && !measuredEdge8060(ts, l8059, nowMs) &&
                !(try { com.lifecyclebot.engine.RunnerTicket8073.admits8073(ts, nowMs) } catch (_: Throwable) { false }) &&
                !coldStartAdmits8080(ts, l8059, nowMs)) {
                auditedAdmission8076 = try {
                    com.lifecyclebot.engine.cortex.Cortex7885.ownLaneVetoRefusesWinners8076("LIVE_PROVEN_SETUP_ONLY_8059", l8059) ||
                        com.lifecyclebot.engine.cortex.Cortex7885.overrulesEdgeRefusal(ts, l8059, "LIVE_PROVEN_SETUP_ONLY_8059")
                } catch (_: Throwable) { false }
                if (!auditedAdmission8076) {
                    provenOnlyRefused8059.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("LIVE_PROVEN_SETUP_ONLY_8059_$l8059") } catch (_: Throwable) {}
                    // V5.0.8083 — DEFER, not a verdict on the token: no validated positive evidence YET for this exact
                    // candidate. Recovery path: its setup / cell / Cortex setup cell proving out on graded labels.
                    recordAssessment8083(ts, l8059, setup8083, Verdict8083.DEFER, "LIVE_PROVEN_SETUP_ONLY_8059", prior8083?.sizeFactor ?: 1.0, nowMs)
                    observeTail7996(ts, lane, "LIVE_PROVEN_SETUP_ONLY_8059", nowMs)
                    return "LIVE_PROVEN_SETUP_ONLY_8059"
                }
                try { PipelineHealthCollector.labelInc("LIVE_PROVEN_SETUP_AUDIT_CLEARED_8076_$l8059") } catch (_: Throwable) {}
            }
            provenOnlyAdmitted8059.incrementAndGet()
            // V5.0.8065 — no buying the top of a spike: 5.0.8063 filled at $300-700k caps right after the rip and was
            // stopped out within a minute (FdZRSo -22% in 11 s). A coin up 80%+ off its recent low and still within
            // 12% of its high, or up 100%+ in 5 minutes, waits for its pullback / base.
            val snap8065 = try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts) } catch (_: Throwable) { null }
            // V5.0.8094 — a runner is "up 80% off its low" by definition; where this token's own cell / kind is proven (graded
            // from the fill latency, so the chase is already in the record) the spike rule stands down.
            if (snap8065 != null && chasing8065(snap8065.runupFromLocalLowPct, snap8065.currentVsPeak, ts.lastPriceChange5m) &&
                !cellOrKindProven8094(ts, l8059, nowMs)) {
                chaseRefused8065.incrementAndGet()
                try { PipelineHealthCollector.labelInc("CHASING_SPIKE_8065_$l8059") } catch (_: Throwable) {}
                observeTail7996(ts, lane, "CHASING_SPIKE_8065", nowMs)
                return "CHASING_SPIKE_8065"
            }
        }
        // V5.0.7994 — watch first: live money only goes where the bot's own labels already point.
        // The audit can supply missing evidence for a thin cell. A measured-negative
        // cell/band still needs its own existing refusal audit or full Cortex authority.
        if (!paper) watchFirst7994(ts, lane, nowMs, auditedAdmission8076)?.let { observeTail7996(ts, lane, it, nowMs); return it }
        val why = liveRefusalCore7970(ts, lane, paper, nowMs)
        if (!paper) observeTail7996(ts, lane, why, nowMs)
        // V5.0.8083 — one immutable assessment per candidate: the verdict FDG / sizing / trade plan read, and the setup
        // this live buy's close is filed under (LiveTruth8064), so refusals stay attributable to token + strategy.
        if (!paper) try {
            val l8083 = CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
            val setupAt = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.classify(ts, l8083, nowMs) } catch (_: Throwable) { null }
            val factor = if (why == null) (try { LiveTruth8064.lanePrior8083(l8083, nowMs).sizeFactor } catch (_: Throwable) { 1.0 }) else 1.0
            recordAssessment8083(ts, l8083, setupAt, if (why == null) Verdict8083.ADMIT else Verdict8083.REFUSE, why ?: "ADMIT", factor, nowMs)
            if (why == null) LiveTruth8064.noteLiveAdmit8083(ts.mint, l8083, setupAt, nowMs)
        } catch (_: Throwable) {}
        // V5.0.7970 — a live admit in a proven runner cell is held like a runner.
        if (why == null && !paper) try {
            if (com.lifecyclebot.engine.RunnerGrab7967.noteAdmit7970(ts, nowMs)) notePositive7975(ts.mint, nowMs)
        } catch (_: Throwable) {}
        return why
    }

    private val measuredEdgeAdmitted8060 = AtomicLong(0)

    /** Pure (V5.0.8060): a cell's record proves it pays — 30+ graded labels, mean - SE > 0 net of cost. */
    fun cellProven8060(n: Int, meanPct: Double, sePct: Double): Boolean =
        n >= 30 && meanPct.isFinite() && sePct.isFinite() && meanPct - sePct > 0.0

    private val kindProven8063 = AtomicLong(0)
    private val chaseRefused8065 = AtomicLong(0)

    /** Pure (V5.0.8065): is a buy here chasing a spike (extended off its low and at its high, or a 5-minute rip)? */
    fun chasing8065(runupPct: Double, currentVsPeak: Double, change5mPct: Double): Boolean =
        (runupPct.isFinite() && runupPct >= 80.0 && currentVsPeak.isFinite() && currentVsPeak >= 0.88) ||
            (change5mPct.isFinite() && change5mPct >= 100.0)

    /** V5.0.8063 — a 30+ label kind book (lane x venue x cap band, or lane x narrative) for [ts] reads mean - SE > 0. */
    /**
     * V5.0.8094 — this token's own graded cell (source x lane x cap band x age band) or its kind (venue x cap band, narrative)
     * is proven to pay in [lane]: 30+ labels at the executable price, mean - SE above the margin.
     */
    /**
     * Pure (V5.0.8094): a cell proven by its WIN RATE — 30+ labels, the Wilson lower bound of the win rate above 55% and a
     * positive mean. Runner cells' means are carried by a few huge labels whose spread can swamp mean - SE even when three
     * coins in four pay (8094: n65 wr75% +737%); the win count is the robust witness.
     */
    fun cellWinsProven8094(n: Int, winRate: Double, meanPct: Double): Boolean {
        if (n < 30 || !winRate.isFinite() || !meanPct.isFinite() || meanPct <= 0.0) return false
        val z = 1.96; val nn = n.toDouble(); val p = winRate.coerceIn(0.0, 1.0)
        val lb = (p + z * z / (2 * nn) - z * kotlin.math.sqrt(p * (1 - p) / nn + z * z / (4 * nn * nn))) / (1 + z * z / nn)
        return lb > 0.55
    }

    fun cellOrKindProven8094(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        val st = ForwardReturnLabeler7731.cellStatFor(ts, lane, nowMs)
        (st != null && (cellProven8060(st.n60, st.meanNet60Pct, st.stderr60Pct) ||
            cellWinsProven8094(st.n60, st.winRate60, st.meanNet60Pct))) || kindProven8063(ts, lane)
    } catch (_: Throwable) { false }

    private fun kindProven8063(ts: TokenState, lane: String): Boolean {
        val ok = try { ForwardReturnLabeler7731.kindStatsFor8063(ts, lane).any { cellProven8060(it.n60, it.meanNet60Pct, it.stderr60Pct) } } catch (_: Throwable) { false }
        if (ok) kindProven8063.incrementAndGet()
        return ok
    }

    /** V5.0.8060 — measured edge other than a named setup: proven cell, runner cell, specialist, tail ticket. */
    /**
     * V5.0.8080 — bounded cold start: the coin's exact setup clears LanePlaybook7907.coldStartSetup8080 on its own exit
     * family, and fewer than [COLD_BUDGET_8080] live entries were admitted this way in the last [COLD_WINDOW_MS_8080].
     * Every later gate (proven-losing setup, Cortex, net edge, lane fit, dead launch, buy door, route, rug) still applies.
     */
    /** V5.0.8092 — CONFIRMED cold-start entries only (mint -> confirmed live fill); failed/deferred attempts release. */
    internal val coldAdmits8080 = ConcurrentHashMap<String, Long>()
    /** V5.0.8092 — admitted but unconfirmed attempts (mint -> admitMs). */
    internal val coldPending8092 = ConcurrentHashMap<String, Long>()
    internal const val COLD_BUDGET_8080 = 3
    internal const val COLD_WINDOW_MS_8080 = 6L * 3_600_000L
    /** V5.0.8092 — an attempt that never filled releases the budget slot after this window. */
    internal const val COLD_PENDING_MS_8092 = 10L * 60_000L

    /** V5.0.8092 — expire stale pending attempts; a failed/deferred buy must not burn the 6h cold budget. */
    internal fun coldPendingSweep8092(nowMs: Long) {
        coldPending8092.entries.removeIf { nowMs - it.value >= COLD_PENDING_MS_8092 }
    }

    /** V5.0.8092 — a confirmed live fill promotes this mint's pending cold-start attempt into the confirmed budget. */
    fun coldStartConfirm8092(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank()) return
        synchronized(coldAdmits8080) {
            val at = coldPending8092.remove(mint) ?: return
            if (coldAdmits8080.putIfAbsent(mint, at) == null) try {
                PipelineHealthCollector.labelInc("LIVE_COLD_START_CONFIRMED_8092")
                ForensicLogger.lifecycle("LIVE_COLD_START_CONFIRMED_8092", "mint=${mint.take(10)}")
            } catch (_: Throwable) {}
        }
    }

    /**
     * Pure (V5.0.8080): may [mint] be admitted on cold start, given [admits] (mint -> first admission)? A mint already
     * admitted in the window keeps its place (it is re-evaluated every cycle); a new mint needs a free slot of the
     * [COLD_BUDGET_8080] distinct coins per window.
     */
    fun coldBudgetOpen8080(admits: Map<String, Long>, mint: String, nowMs: Long): Boolean {
        val live = admits.filterValues { nowMs - it in 0L until COLD_WINDOW_MS_8080 }
        return mint in live || live.size < COLD_BUDGET_8080
    }

    /** Pure (V5.0.8094): confirmed AND in-flight cold starts share the [COLD_BUDGET_8080]; a mint holding a place keeps it. */
    fun coldBudgetOpen8094(confirmed: Map<String, Long>, pending: Map<String, Long>, mint: String, nowMs: Long): Boolean {
        val live = confirmed.filterValues { nowMs - it in 0L until COLD_WINDOW_MS_8080 }.keys + pending.keys
        return mint in live || live.size < COLD_BUDGET_8080
    }

    private fun coldStartAdmits8080(ts: TokenState, lane: String, nowMs: Long): Boolean {
        val setup = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.coldStartSetup8080(ts, lane, nowMs) } catch (_: Throwable) { null } ?: return false
        synchronized(coldAdmits8080) {
            coldAdmits8080.entries.removeIf { nowMs - it.value >= COLD_WINDOW_MS_8080 }
            coldPendingSweep8092(nowMs)
            // V5.0.8092 — only CONFIRMED fills consume the 3-mint/6h budget. A pending attempt of
            // this mint is already in flight; a failed attempt releases after the pending window.
            // V5.0.8094 entry repair — in-flight attempts count against the 3-coin budget too (8092 let any number of
            // unconfirmed cold starts run at once), and a mint already in flight keeps its place on re-evaluation.
            if (!coldBudgetOpen8094(coldAdmits8080, coldPending8092, ts.mint, nowMs)) {
                try { PipelineHealthCollector.labelInc("LIVE_COLD_START_BUDGET_SPENT_8080") } catch (_: Throwable) {}
                return false
            }
            coldPending8092.putIfAbsent(ts.mint, nowMs)
        }
        try {
            PipelineHealthCollector.labelInc("LIVE_COLD_START_SETUP_ADMIT_8080_$lane")
            ForensicLogger.lifecycle("LIVE_COLD_START_SETUP_ADMIT_8080", "mint=${ts.mint.take(10)} lane=$lane setup=$setup")
        } catch (_: Throwable) {}
        return true
    }

    private fun measuredEdge8060(ts: TokenState, lane: String, nowMs: Long): Boolean {
        val st = try { ForwardReturnLabeler7731.cellStatFor(ts, lane, nowMs) } catch (_: Throwable) { null }
        val ok = (st != null && cellProven8060(st.n60, st.meanNet60Pct, st.stderr60Pct)) ||
            // V5.0.8063 — or the coin's kind has proven it pays in this lane (venue x cap band, or narrative).
            kindProven8063(ts, lane) ||
            (try { com.lifecyclebot.engine.RunnerGrab7967.cellBeatsLane7970(ts, lane, nowMs) } catch (_: Throwable) { false }) ||
            (try { SpecialistMiner7972.match7972(ts, lane, nowMs) } catch (_: Throwable) { null }) != null ||
            // V5.0.8064 — a tail ticket is no longer live money: it is proven on a replayed exit ladder at the peak, the
            // exit live cannot bank (spike tiers fired=0 in 5.0.8063). It still clears watch-first for a proven coin.
            // V5.0.8062 — an expert wallet (owner / top trader) bought it in the last 15 min and the copy book clears
            // this lane's measured round trip.
            (try {
                com.lifecyclebot.engine.ExpertWallets7962.expertEntryLive7962(ts.mint, nowMs) &&
                    SignalSourceProof7291.copyEdgeAtGap8062(NetEdge8030.costPct8030(lane, NetEdge8030.DEFAULT_TICKET_SOL, ts.lastLiquidityUsd) / 100.0)
            } catch (_: Throwable) { false })
        if (ok) measuredEdgeAdmitted8060.incrementAndGet()
        return ok
    }

    // ── V5.0.7994 — WATCH FIRST ──
    //
    // Owner, after a reset: "isn't that pretty slow while risking live capital?" The forward
    // labeler grades every coin the bot looks at (hundreds an hour) whether it buys or not, so a
    // live buy adds almost nothing to learning and costs real SOL. 5.0.7991 bought fresh launches
    // whose cell already read -9.5% (n106) and -5.7% (n74). A live entry now needs its own cell
    // (source | lane | mcap band | age band) to read at least [WATCH_MIN_N_7994] labels with a
    // non-negative 5-minute net mean, or a promoted specialist match. Everything else is watched.
    private const val WATCH_MIN_N_7994 = 10
    private val watched7994 = java.util.concurrent.atomic.AtomicLong(0)
    private val cleared7994 = java.util.concurrent.atomic.AtomicLong(0)

    /** Pure. V5.0.7994 — may live money enter a cell with [n] labels and [meanPct] 5-minute net mean? */
    fun cellEarnsLive7994(n: Int, meanPct: Double): Boolean = n >= WATCH_MIN_N_7994 && meanPct.isFinite() && meanPct >= 0.0

    private val bandRefused8019 = AtomicLong(0)
    private val priorsCleared8019 = AtomicLong(0)
    private const val BAND_MIN_N_8019 = 20

    /**
     * Pure (V5.0.8019): a lane x cap band is proven losing on 20+ labels — its upper bound (mean + SE) is below
     * zero. V5.0.8023: was mean - SE < 0, which "proved" every band whose lower bound dipped under zero.
     */
    fun bandLoses8019(n: Int, meanPct: Double, sePct: Double): Boolean =
        n >= BAND_MIN_N_8019 && meanPct.isFinite() && sePct.isFinite() && meanPct + sePct < 0.0

    private fun watchFirst7994(ts: TokenState, lane: String, nowMs: Long, auditedAdmission8076: Boolean = false): String? {
        // V5.0.8072 — a runner ticket is the deliberate unproven buy: it is not watched first.
        if (try { com.lifecyclebot.engine.RunnerTicket8073.isTicket8073(ts.mint, nowMs) } catch (_: Throwable) { false }) {
            // V5.0.8073 — and it is a positive admit: planned at once (no plan wait) and sized up to the route minimum.
            // 5.0.8072: 3 tickets issued, 0 bought — the size resolver refused them sub-routable (288 refusals).
            cleared7994.incrementAndGet(); notePositive7975(ts.mint, nowMs); return null
        }
        val st = try { ForwardReturnLabeler7731.cellStatFor(ts, lane, nowMs) } catch (_: Throwable) { null }
        if (st != null && cellEarnsLive7994(st.n60, st.meanNet60Pct)) {
            cleared7994.incrementAndGet()
            // V5.0.7995 — a cell that pays (+5% or better at 5 minutes) is bought now, not made to wait
            // for a chart setup: NO_PLAN_WAIT_7739 cost the biggest runners (BUM +32,922%, hehe +2,906%,
            // ORA +1,871%, AIA +1,577%) and its refused candidates read +3.9% (n301) / +11.7% (n34).
            if (cellSkipsPlanWait7995(st.n60, st.meanNet60Pct)) { notePositive7975(ts.mint, nowMs); planSkips7995.incrementAndGet() }
            return null
        }
        val l = CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
        val specialist = try { SpecialistMiner7972.match7972(ts, l, nowMs) } catch (_: Throwable) { null }
        if (specialist != null) { cleared7994.incrementAndGet(); return null }
        // V5.0.8056 — a coin showing a setup this lane has PROVEN pays (30+ labels, mean - SE > 0) is bought now, not
        // watched for its thin cell: runners live in thin cells by nature (5.0.8051: 10 runners 4x+, caught 0). It is
        // planned at once (no plan wait), may be promoted to the route minimum, and a runner setup is held as a runner.
        val proven8056 = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.provenSetup8056(ts, l, nowMs) } catch (_: Throwable) { null }
        if (proven8056 != null) {
            cleared7994.incrementAndGet(); provenSetupCleared8056.incrementAndGet(); notePositive7975(ts.mint, nowMs)
            if (proven8056.second) try { com.lifecyclebot.engine.RunnerGrab7967.holdAsRunner7972(ts, nowMs) } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("WATCH_FIRST_CLEARED_PROVEN_SETUP_8056_${proven8056.first.take(24)}") } catch (_: Throwable) {}
            return null
        }
        // V5.0.8018 — a confirmed run the bot missed or sold early gets a second entry (RunnerPlay8018).
        if (try { com.lifecyclebot.engine.RunnerPlay8018.ticketActive8018(ts.mint, nowMs) } catch (_: Throwable) { false }) {
            cleared7994.incrementAndGet(); notePositive7975(ts.mint, nowMs)
            return null
        }
        // V5.0.7996 — a tail-proven ticket (its cell / fact pair pays on the 4-hour ladder replay).
        if (try { TailHunter7996.ticket7996(ts, l, nowMs) } catch (_: Throwable) { false }) {
            cleared7994.incrementAndGet(); notePositive7975(ts.mint, nowMs)
            return null
        }
        // V5.0.8019 — a thin cell inside a cap band this lane is PROVEN to lose in (20+ labels across every
        // source and age, mean + SE < 0) is refused on the band's record, not waved through as merely thin:
        // 8018's floor-price launches (filed under $10k once the cap agrees with the price) rode the
        // THIN_CELL stand-down into the bot's worst group (27 live closes, -17.4%).
        val band = if (st == null || st.n60 < WATCH_MIN_N_7994) try { ForwardReturnLabeler7731.bandStatFor8019(ts, l) } catch (_: Throwable) { null } else null
        val bandNegative = band != null && bandLoses8019(band.n60, band.meanNet60Pct, band.stderr60Pct)
        if (bandNegative) bandRefused8019.incrementAndGet()
        val why = when {
            bandNegative -> "WATCH_FIRST_8019_BAND_NEGATIVE"
            st == null || st.n60 < WATCH_MIN_N_7994 -> "WATCH_FIRST_7994_THIN_CELL"
            else -> "WATCH_FIRST_7994_CELL_NEGATIVE"
        }
        if (auditedAdmission8076 && why == "WATCH_FIRST_7994_THIN_CELL") {
            cleared7994.incrementAndGet(); notePositive7975(ts.mint, nowMs)
            return null
        }
        // V5.0.8006 — closed loop: watch-first is graded like every other refusal (the veto audit,
        // per lane, on progressively revised labels). When the coins it held back in this lane are
        // proven to win (40+ graded, mean - SE > +2% net of measured cost), it stands down.
        if (try { com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners8006(why, l) } catch (_: Throwable) { false }) {
            standDown8006.incrementAndGet(); cleared7994.incrementAndGet()
            return null
        }
        // V5.0.8016 — a proven Cortex STRONG read clears watch-first like any other soft refusal. Watch-first
        // returned before the Cortex was ever asked, so in 8014 SHITCOIN's STRONG reads went unbought 31 times
        // at +19.4% (the lane's STRONG bucket +17.9%, authority 0.83) while thin cells held back the runners.
        // A thin cell yields at the Cortex's overrule bar (0.5); a cell measured negative needs full authority.
        if (try { com.lifecyclebot.engine.cortex.Cortex7885.overrulesEdgeRefusal(ts, l, why) } catch (_: Throwable) { false }) {
            cortexCleared8016.incrementAndGet(); cleared7994.incrementAndGet(); notePositive7975(ts.mint, nowMs)
            return null
        }
        watched7994.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(why) } catch (_: Throwable) {}
        return why
    }

    private val planSkips7995 = java.util.concurrent.atomic.AtomicLong(0)
    private val standDown8006 = java.util.concurrent.atomic.AtomicLong(0)
    private val cortexCleared8016 = java.util.concurrent.atomic.AtomicLong(0)
    private val provenSetupCleared8056 = java.util.concurrent.atomic.AtomicLong(0)
    private val provenOnlyRefused8059 = java.util.concurrent.atomic.AtomicLong(0)
    private val provenOnlyAdmitted8059 = java.util.concurrent.atomic.AtomicLong(0)

    /** V5.0.7996 — every live decision opens a 4-hour ladder replay (TailHunter7996). */
    private fun observeTail7996(ts: TokenState, lane: String, reason: String?, nowMs: Long) {
        try { TailHunter7996.observe7996(ts, lane, reason, nowMs) } catch (_: Throwable) {}
    }

    /** Pure. V5.0.7995 — a cell whose labels pay +5% net or better is planned at once (no plan wait). */
    fun cellSkipsPlanWait7995(n: Int, meanPct: Double): Boolean = cellEarnsLive7994(n, meanPct) && meanPct >= 5.0

    fun watchLine7994(): String = "watched=${watched7994.get()} cleared=${cleared7994.get()} planWaitSkipped7995=${planSkips7995.get()} auditStoodDown8006=${standDown8006.get()} cortexCleared8016=${cortexCleared8016.get()} provenSetupCleared8056=${provenSetupCleared8056.get()} provenOnly8059[admitted=${provenOnlyAdmitted8059.get()} viaMeasuredEdge8060=${measuredEdgeAdmitted8060.get()} kindProven8063=${kindProven8063.get()} bootRecovery8074[${try { LiveBootRecovery8074.statusLine() } catch (_: Throwable) { "-" }}] chaseRefused8065=${chaseRefused8065.get()} liveTruth8064[${try { LiveTruth8064.statusLine() } catch (_: Throwable) { "-" }}] refused=${provenOnlyRefused8059.get()}] bandRefused8019=${bandRefused8019.get()} priorsCleared8019=${priorsCleared8019.get()} bar=n>=$WATCH_MIN_N_7994,mean>=0"

    private fun liveRefusalCore7970(ts: TokenState, lane: String, paper: Boolean, nowMs: Long): String? {
        // V5.0.7885 — the Cortex refuses first, in both modes, once its record has
        // earned that authority (bar V1); until then this returns null.
        val cortex7950 = com.lifecyclebot.engine.cortex.Cortex7885.entryRefusal(ts, lane, paper)
        if (paper) return cortex7950
        // V5.0.8019 — a coin any lane closed in the last 10 minutes is not reopened (the re-entry ticket excepted).
        com.lifecyclebot.engine.RebuyLockout8019.refusal8019(ts.mint, nowMs)?.let { return it }
        // V5.0.8030 — positive EV only: the lane's expected move must be at least 2x the round trip. Hard: no
        // chart read, specialist, ticket or Cortex overrule buys a trade whose cost eats half its move.
        NetEdge8030.entryRefusal8030(ts, lane)?.let { return it }
        // V5.0.8054 — scalp lanes need a coin that moves (3x cost in its last hour); swing lanes don't buy a finished run.
        NetEdge8030.laneFitRefusal8054(ts, CanonicalLaneIdentity6506.canonical(lane).ifBlank { lane })?.let { return it }
        // V5.0.8030 — a Cortex refusal that has EARNED authority (proven on its own record) is not bypassed by a
        // chart-reader BUY any more; the chart read still clears every unproven soft prior below.
        cortex7950?.let { return it }
        // V5.0.8079 §NO_SIDE_DOOR_FOR_A_PROVEN_LOSER. The chart reader's BUY and a specialist match returned "admit"
        // above the playbook priors, so a setup this lane has PROVEN loses (MOONSHOT LAUNCH_CONTINUATION -32.7%,
        // EXPRESS -38.0%) still bought. It now yields only to its own setup-scoped veto audit proving the refusal
        // refuses winners, or to full evidence-earned Cortex authority (never partial).
        val l8079 = CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
        (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.provenLosingSetupRefusal8079(ts, l8079, nowMs) } catch (_: Throwable) { null })?.let { r ->
            val retired = try { com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners8006(r, l8079) } catch (_: Throwable) { false }
            val full = !retired && (try { com.lifecyclebot.engine.cortex.Cortex7885.fullAuthorityOverrules8078(ts, l8079, r) } catch (_: Throwable) { false })
            if (!retired && !full) {
                try { PipelineHealthCollector.labelInc("LIVE_SETUP_PROVEN_LOSING_NO_SIDE_DOOR_8079_$l8079") } catch (_: Throwable) {}
                return r
            }
            try { PipelineHealthCollector.labelInc("LIVE_SETUP_PROVEN_LOSING_CLEARED_8079_${if (retired) "AUDIT" else "FULL_CORTEX"}_$l8079") } catch (_: Throwable) {}
        }
        // V5.0.7950 — the chart reader's BUY passes every learned/soft refusal; hard safety
        // (the safety tier's HARD_BLOCK, Mayhem) still refuses inside chartAdmits7950.
        if (chartAdmits7950(ts, lane, nowMs)) return null
        val l = CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
        // V5.0.7943 — pump.fun Mayhem Mode coins are not bought live.
        com.lifecyclebot.engine.MayhemMode7943.liveRefusal(ts)?.let { return it }
        // V5.0.7972 — a promoted specialist of this lane (a fact combination whose own labels
        // cleared +15% net after 2 SE on 25+ decisions, still holding out of sample) admits.
        // Hard safety (HARD_BLOCK) and Mayhem have already refused above / at the door.
        if (ts.safety.tier != com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) {
            SpecialistMiner7972.match7972(ts, l, nowMs)?.let { sp ->
                if (SpecialistMiner7972.runs7972(sp)) try { com.lifecyclebot.engine.RunnerGrab7967.holdAsRunner7972(ts, nowMs) } catch (_: Throwable) {}
                notePositive7975(ts.mint, nowMs)
                return null
            }
        }
        // V5.0.7907 — the lane's playbook: a live entry needs one of its setups.
        // V5.0.7928 — the lane's lifecycle stage: buy the stage this lane's play pays in.
        // V5.0.7930 — an unmeasured prior (no setup fired / off the stage sheet) yields to
        // measured evidence: a cell proven above the live margin, or a proven Cortex
        // STRONG read. A proven-losing setup/stage and rug-prone are never overruled.
        val priors7930 = listOfNotNull(
            com.lifecyclebot.engine.cortex.LanePlaybook7907.liveRefusal(ts, l, nowMs),   // V5.0.8084 — same decision clock = same classification
            com.lifecyclebot.engine.TokenMetricStageRouter.liveStageRefusal7928(ts, l),
            CostLedger7962.liveRefusal7962(ts, l),   // V5.0.7962 — measured all-in cost above the setup's gross
        )
        // V5.0.7948 — a small-sample read (the playbook's shrunk expectancy) also yields
        // to measured evidence, and any measured refusal yields to a proven cohort on more labels.
        val cohort7948 by lazy { provenCohort7948(ts, nowMs) }
        for (prior in priors7930) {
            // V5.0.7953 — a refusal the veto audit proves is refusing winners stands down.
            // V5.0.8006 — read in this lane (its own record once it has 40 graded refusals).
            if (com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners8006(prior, l)) continue
            if ((priorOnly7930(prior) || prior.contains("_EXPECTED_NEGATIVE_7948")) && measuredOverrules7930(ts, l, prior, nowMs)) continue
            // V5.0.7970 — "no setup fired" proven losing is a lane-wide aggregate (MOONSHOT n851 -5%).
            // It yields to the narrower cell this token sits in when that cell clears +15% net after
            // one SE (the same lane's PUMP_PORTAL|MC_10K_100K|AGE_LT15M cell: n50 +157%), or to a
            // full-authority Cortex STRONG read (MOONSHOT missed STRONG n48 +24%). A setup proven
            // losing is never overruled here.
            if (prior.startsWith("PLAYBOOK_NO_TRIGGER_PROVEN_LOSING") &&
                com.lifecyclebot.engine.RunnerGrab7967.cellBeatsLane7970(ts, l, nowMs)) { notePositive7975(ts.mint, nowMs); continue }
            // V5.0.8019 — EVERY soft prior (no-trigger, setup expectancy, plan wait) yields to a proven Cortex STRONG
            // read, not only the proven-losing one: 8018 lost QubitCat (+1,840%) and brigitte (+481%) to the plain
            // PLAYBOOK_NO_TRIGGER refusal while SHITCOIN's STRONG reads paid +22% at authority 0.84. Hard safety,
            // Mayhem and a proven-losing record below full authority are still refused inside overrulesEdgeRefusal.
            if (com.lifecyclebot.engine.cortex.Cortex7885.overrulesEdgeRefusal(ts, l, prior)) { priorsCleared8019.incrementAndGet(); notePositive7975(ts.mint, nowMs); continue }
            val sampleN = refusalSampleN7948(ts, l, prior, nowMs)
            if (sampleN != null && cohortOverrulesSmaller7948(cohort7948, sampleN)) {
                try { PipelineHealthCollector.labelInc("LIVE_PRIOR_REFUSAL_OUTWEIGHED_BY_COHORT_7948") } catch (_: Throwable) {}
                continue
            }
            return prior
        }
        // V5.0.7883 — a learned shape rule of this lane (tokenomics/timing bin it
        // has proven to lose in) refuses before the cohort read.
        val shape = try { TradeShapeLearner7883.shapeRefusal(ts, l) } catch (_: Throwable) { null }
        if (shape != null && com.lifecyclebot.engine.cortex.Cortex7885.overrulesEdgeRefusal(ts, l, shape)) return null
        if (shape != null) {
            TradeShapeLearner7883.noteRefusal(l, shape)
            refused.computeIfAbsent("$l|SHAPE") { AtomicLong(0) }.incrementAndGet()
            return "EDGE_7877_$shape"
        }
        val v = verdictFor(ts, l, nowMs)
        if (v.allow) {
            allowed.computeIfAbsent("$l|${v.source.name}") { AtomicLong(0) }.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LIVE_EDGE_ADMIT_7877_${v.source.name}") } catch (_: Throwable) {}
            return null
        }
        // V5.0.7885 — a proven Cortex STRONG read overrules the cohort refusal.
        if (com.lifecyclebot.engine.cortex.Cortex7885.overrulesEdgeRefusal(ts, l, v.why)) return null
        refused.computeIfAbsent("$l|${v.why.substringBefore("_PCT").take(28)}") { AtomicLong(0) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("LIVE_EDGE_REFUSED_7877")
            PipelineHealthCollector.labelInc("LIVE_EDGE_REFUSED_7877_$l")
            if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("LIVE_EDGE_7877", "$l|${ts.mint}")) {
                ForensicLogger.lifecycle(
                    "LIVE_EDGE_REFUSED_7877",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$l why=${v.why} action=live_refused_paper_continues",
                )
            }
        } catch (_: Throwable) {}
        return "EDGE_7877_${v.why}_$l"
    }

    fun statusLine(): String =
        "bar=cellN>=$CELL_MIN_N&&mean-se>+$LIVE_MARGIN_PCT%|laneProven runner=bestCohort(mean-se>$RUNNER_MARGIN_PCT%) " +
            "admit=[${allowed.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}] " +
            "refuse=[${refused.entries.sortedByDescending { it.value.get() }.take(8).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }}]"
}
