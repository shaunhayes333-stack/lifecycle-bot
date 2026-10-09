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

    data class Verdict(val allow: Boolean, val source: Source, val edgePct: Double, val why: String)

    private val allowed = ConcurrentHashMap<String, AtomicLong>()
    private val refused = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Pure. [cell] is the candidate's forward-label cell (null when unseen);
     * [laneProven] is LivePivotAuthority7876's lane verdict == PROVEN.
     */
    fun judge(cell: ForwardReturnLabeler7731.CellStat?, laneProven: Boolean, marginPct: Double = LIVE_MARGIN_PCT): Verdict {
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
    private fun refusalSampleN7948(ts: TokenState, lane: String, prior: String): Int? {
        if (prior.endsWith("_PEAK_EXHAUSTION") || prior.endsWith("_DUMPING") || prior.contains("RUG")) return null
        // V5.0.7948 review — a lane's own PROVEN_LOSING record (setup, NO_TRIGGER, stage) is never
        // outweighed by a pooled cohort, nor is a runner lane's runner-setups-only rule (7939).
        if (prior.contains("PROVEN_LOSING")) return null
        val runner = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        if (runner && prior.startsWith("PLAYBOOK_NO_TRIGGER")) return null
        return when {
            prior.startsWith("PLAYBOOK_") -> try { com.lifecyclebot.engine.cortex.LanePlaybook7907.classifiedSampleN7948(ts, lane) } catch (_: Throwable) { null }
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
    fun liveRefusal(ts: TokenState, lane: String, paper: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        val why = liveRefusalCore7970(ts, lane, paper, nowMs)
        // V5.0.7970 — a live admit in a proven runner cell is held like a runner.
        if (why == null && !paper) try { com.lifecyclebot.engine.RunnerGrab7967.noteAdmit7970(ts, nowMs) } catch (_: Throwable) {}
        return why
    }

    private fun liveRefusalCore7970(ts: TokenState, lane: String, paper: Boolean, nowMs: Long): String? {
        // V5.0.7885 — the Cortex refuses first, in both modes, once its record has
        // earned that authority (bar V1); until then this returns null.
        val cortex7950 = com.lifecyclebot.engine.cortex.Cortex7885.entryRefusal(ts, lane, paper)
        if (paper) return cortex7950
        // V5.0.7950 — the chart reader's BUY passes every learned/soft refusal; hard safety
        // (the safety tier's HARD_BLOCK, Mayhem) still refuses inside chartAdmits7950.
        if (chartAdmits7950(ts, lane, nowMs)) return null
        cortex7950?.let { return it }
        val l = CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
        // V5.0.7943 — pump.fun Mayhem Mode coins are not bought live.
        com.lifecyclebot.engine.MayhemMode7943.liveRefusal(ts)?.let { return it }
        // V5.0.7972 — a promoted specialist of this lane (a fact combination whose own labels
        // cleared +15% net after 2 SE on 25+ decisions, still holding out of sample) admits.
        // Hard safety (HARD_BLOCK) and Mayhem have already refused above / at the door.
        if (ts.safety.tier != com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) {
            SpecialistMiner7972.match7972(ts, l, nowMs)?.let { sp ->
                if (SpecialistMiner7972.runs7972(sp)) try { com.lifecyclebot.engine.RunnerGrab7967.holdAsRunner7972(ts, nowMs) } catch (_: Throwable) {}
                return null
            }
        }
        // V5.0.7907 — the lane's playbook: a live entry needs one of its setups.
        // V5.0.7928 — the lane's lifecycle stage: buy the stage this lane's play pays in.
        // V5.0.7930 — an unmeasured prior (no setup fired / off the stage sheet) yields to
        // measured evidence: a cell proven above the live margin, or a proven Cortex
        // STRONG read. A proven-losing setup/stage and rug-prone are never overruled.
        val priors7930 = listOfNotNull(
            com.lifecyclebot.engine.cortex.LanePlaybook7907.liveRefusal(ts, l),
            com.lifecyclebot.engine.TokenMetricStageRouter.liveStageRefusal7928(ts, l),
            CostLedger7962.liveRefusal7962(ts, l),   // V5.0.7962 — measured all-in cost above the setup's gross
        )
        // V5.0.7948 — a small-sample read (the playbook's shrunk expectancy) also yields
        // to measured evidence, and any measured refusal yields to a proven cohort on more labels.
        val cohort7948 by lazy { provenCohort7948(ts, nowMs) }
        for (prior in priors7930) {
            // V5.0.7953 — a refusal the veto audit proves is refusing winners stands down.
            if (com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners7953(prior)) continue
            if ((priorOnly7930(prior) || prior.contains("_EXPECTED_NEGATIVE_7948")) && measuredOverrules7930(ts, l, prior, nowMs)) continue
            // V5.0.7970 — "no setup fired" proven losing is a lane-wide aggregate (MOONSHOT n851 -5%).
            // It yields to the narrower cell this token sits in when that cell clears +15% net after
            // one SE (the same lane's PUMP_PORTAL|MC_10K_100K|AGE_LT15M cell: n50 +157%), or to a
            // full-authority Cortex STRONG read (MOONSHOT missed STRONG n48 +24%). A setup proven
            // losing is never overruled here.
            if (prior.startsWith("PLAYBOOK_NO_TRIGGER_PROVEN_LOSING") &&
                (com.lifecyclebot.engine.RunnerGrab7967.cellBeatsLane7970(ts, l, nowMs) ||
                    com.lifecyclebot.engine.cortex.Cortex7885.overrulesEdgeRefusal(ts, l, prior))) continue
            val sampleN = refusalSampleN7948(ts, l, prior)
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
