package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8136 §ONE_CANDIDATE_ADMISSION_RECORD.
 *
 * 5.0.8127 top refusals: 729 generic score floor (CANONICAL_V3_SCORE_FLOOR_7243), 230 losing tactic, 192 proven-only,
 * 161 negative timing. Each came from its own gate reading its own slice of the evidence, so a candidate whose OWN
 * record proved it pays (a fired setup on 30+ graded labels with mean - SE above cost) could still be refused by the
 * generic V3 meme score (a prior that scores fresh launches 9-32), and the executor's pre-lease floor refused the
 * same candidate a second time on the same generic number. The reverse also happened: a lane-wide bypass (ladder,
 * lane labels, retired veto population, playbook score) cleared the generic floor for a coin whose selected setup
 * was losing on its own labels.
 *
 * One record per candidate now compares, in one place: lane + setup, regime + lifecycle stage, input freshness,
 * expected executable gross move, round-trip cost (fees, tips, slippage, impact), expected net and downside, the
 * setup / timing / cell / stage evidence with its confidence interval (n, mean, stderr), runner-tail potential and
 * the safety refusals, and returns ONE terminal admission with its evidence trace:
 *
 *   ADMIT       supported candidate-specific edge (specific = true), or the generic prior clears (specific = false)
 *   REFUSE      hard safety, structural selling risk, a candidate-specific loss pattern, adverse timing, bad
 *               execution economics (specific = true) — or no supported edge and the generic prior fails
 *   DEFER_DATA  core inputs unobserved or the price stale: recoverable, re-evaluated within a bounded window
 *               ([deferBudget8136]); never NO_TRIGGER, never a fabricated setup, never a permanent verdict.
 *
 * The generic V3 score is a PRIOR: once this record admits on supported specific edge, a generic score floor does
 * not independently refuse it (FinalDecisionGate.playbookClearsFloor7937 → [floorVerdict8136]). Thin positive
 * evidence (n under [SUPPORT_MIN_N_8136]) never bypasses the generic prior. A runner label never overrides a proven
 * loss pattern, a hostile mint, structural risk or bad economics: those are checked first.
 */
object CandidateAdmission8136 {

    enum class Terminal8136 { ADMIT, REFUSE, DEFER_DATA }

    /** One measured record: [n] graded labels, mean and standard error (%). [netOfCost]: the labels already carry the round trip. */
    data class Evidence8136(
        val kind: String,
        val id: String,
        val n: Int,
        val meanPct: Double,
        val sePct: Double,
        val runnerRate: Double = Double.NaN,
        val netOfCost: Boolean = true,
        /** V5.0.8198 — the late (60-minute, label slot "240") read of the same record: n and mean net (%). */
        val lateN: Int = 0,
        val lateMeanPct: Double = Double.NaN,
    ) {
        val lowerPct: Double get() = if (meanPct.isFinite() && sePct.isFinite()) meanPct - sePct else Double.NaN
        val upperPct: Double get() = if (meanPct.isFinite() && sePct.isFinite()) meanPct + sePct else Double.NaN
        val compact: String get() = "$kind:$id n$n ${fmt8136(meanPct)}±${fmt8136(sePct)}"
    }

    /** Everything the admission compares for one candidate (mint + lane + selected setup). Pure data. */
    data class Candidate8136(
        val mint: String,
        val lane: String,
        /** The strategy the coin trades as (LanePlaybook7907.selectedSetup8094); null when it could not be classified. */
        val selectedSetup: String?,
        val regime: String = "",
        val stage: String = "",
        /** Age of the last observed price; Long.MAX_VALUE when the coin was never priced. */
        val priceAgeMs: Long = 0L,
        /** Core classifier inputs the coin is missing (LanePlaybook7907 DATA_INCOMPLETE), e.g. "shape:UNKNOWN". */
        val missingInputs: List<String> = emptyList(),
        val expectedGrossPct: Double = Double.NaN,
        val costPct: Double = Double.NaN,
        /** V5.0.8209 — the executable size (SOL) [costPct] was quoted for, and that quote's breakdown; immutable with the decision. */
        val costSizeSol: Double = Double.NaN,
        val costBreakdown: String = "",
        val downsidePct: Double = Double.NaN,
        /** Executable move per fired setup; an alternative cannot inherit the selected setup's economics. */
        val expectedGrossBySetup: Map<String, Double> = emptyMap(),
        /** Every fired setup's own record in this lane (the selected one included). */
        val setups: List<Evidence8136> = emptyList(),
        val cell: Evidence8136? = null,
        /** The selected setup's record at the coin's current timing band. */
        val timing: Evidence8136? = null,
        val stageRecord: Evidence8136? = null,
        /** The tactic the coin would be traded as, when its realised record in this lane is losing. */
        val losingTactic: String? = null,
        /** A fresh launch: its price must be fresher ([HOT_STALE_PRICE_MS_8136]). */
        val hotLaunch: Boolean = false,
        val safetyRefusal: String? = null,
        val structuralRisk: String? = null,
        val genericScore: Double = Double.NaN,
        val genericFloor: Double = Double.NaN,
        /** V5.0.8153 — each fired setup's record at the coin's CURRENT timing band (StrategyRank8153 ranks with it). */
        val setupTimings: Map<String, Evidence8136> = emptyMap(),
        /** Candidate generation pinned by the live admission adapter; 0 for pure fixtures. */
        val candidateVersion: Long = 0L,
        /**
         * V5.0.8169 — true (live adapter): a CELL / STAGE loss pattern refuses only on [cellLoss8169] / [stageLoss8169],
         * the records booked since the grading epoch (ForwardReturnLabeler7731.freshCellStatFor8169); older evidence
         * decays to neutral. False (pure fixtures): [cell] / [stageRecord] are the loss records, as before.
         */
        val lossEpochAware8169: Boolean = false,
        /** V5.0.8238 — true (live adapter, LIVE account): the generic prior alone cannot admit real money. */
        val live: Boolean = false,
        val cellLoss8169: Evidence8136? = null,
        val stageLoss8169: Evidence8136? = null,
        /**
         * V5.0.8200 — the Cortex's STRONG read on THIS candidate, as the executable-net record of that lane's STRONG reads
         * (Cortex7885.strongCohortEvidence8200). Null when the read is not STRONG, the lane is uncalibrated / inverted /
         * barred, or the coin is hard-blocked. It carries a lane-wide stage loss at the VALID margin and is a candidate-
         * specific edge at the SUPPORTED margin, exactly like a fired setup; it never carries the coin's own losing cell.
         */
        val cortexStrong8200: Evidence8136? = null,
        /** 8213: fresh qualified, authoritative native verdict of THIS lane, not an inferred score or another lane's claim. */
        val nativeQualified8213: Boolean = false,
        /** Native's own named setup is retained when optional playbook features prevent classification. */
        val nativeSetup8213: String? = null,
        /**
         * V5.0.8245 — RunnerGrab7967: this coin is a grab right now (proven runner cell n >= 20, mean - SE >= +20% net
         * at 60 m; chart firming / not breaking; hard safety and Mayhem clear; the grab record standing). Candidate-
         * specific positive evidence at the SUPPORTED margin beside the setup / cell / Cortex records; it never carries
         * the coin's own losing cell and still answers to economics and the data step's critical inputs.
         */
        val runnerGrab8245: Evidence8136? = null,
    )

    /** The one authoritative terminal admission and the evidence trace behind it. */
    data class Decision8136(
        val terminal: Terminal8136,
        val reason: String,
        /** The terminal rests on candidate-specific evidence (true) or on the generic prior / missing data (false). */
        val specific: Boolean,
        val chosenSetup: String?,
        val expectedNetPct: Double,
        val trace: List<String>,
        /** V5.0.8153 — the evidence the terminal and [chosenSetup] rest on (the one identity sizing, seal and exit read). */
        val basis: String = "",
        /** V5.0.8209 — the cost record this terminal was decided on (cost %, size quoted, breakdown); travels to FDG/sizing unchanged. */
        val costPct: Double = Double.NaN,
        val costSizeSol: Double = Double.NaN,
        val costBreakdown: String = "",
    ) {
        val admitted: Boolean get() = terminal == Terminal8136.ADMIT
        val compact: String get() = "${terminal.name}:$reason${chosenSetup?.let { " setup=$it" } ?: ""} [${trace.joinToString("; ")}]"
    }

    internal const val SUPPORT_MIN_N_8136 = 30
    internal const val LOSING_MIN_N_8136 = 12
    internal const val PROVEN_LOSING_MIN_N_8136 = 30
    internal const val PROVEN_LOSING_PCT_8136 = -2.0
    internal const val TAIL_MIN_N_8136 = 100
    internal const val TAIL_MIN_RATE_8136 = 0.10
    internal const val STALE_PRICE_MS_8136 = 5L * 60_000L
    internal const val HOT_STALE_PRICE_MS_8136 = 60_000L

    // V5.0.8170: incomplete optional chart context is not equal to missing executable proof.
    // Even a genuinely profitable, graded setup must NOT compensate for an unobserved
    // sellable venue, liquidity, supply/decimals, price or cost.
    private val CRITICAL_MISSING_8170 = setOf(
        "LIQ", "LIQUIDITY", "PRICE", "ECONOMICS", "ROUTE", "SELLABILITY",
        "TOKEN_MAP", "MINT", "IDENTITY", "DECIMALS", "SUPPLY", "DEPTH", "QUOTE",
    )
    fun criticalMissing8170(missing: String): Boolean =
        missing.substringBefore(':').trim().uppercase() in CRITICAL_MISSING_8170


    internal fun fmt8136(v: Double): String = if (v.isFinite()) "%+.1f".format(v) else "-"

    // ── pure evidence tests ──

    /**
     * Pure: a record supports admission — [SUPPORT_MIN_N_8136]+ labels and mean - SE above the bar (0 for labels already
     * net of cost, else [costPct]); or, on [TAIL_MIN_N_8136]+ labels, a positive point estimate over the bar with
     * [TAIL_MIN_RATE_8136]+ of its labels running (the runner-tail test, LiveEdgeGate7877.runnerVerdict).
     */
    fun supported8136(e: Evidence8136?, costPct: Double): Boolean {
        if (e == null || e.n < SUPPORT_MIN_N_8136 || !e.lowerPct.isFinite()) return false
        val bar = if (e.netOfCost) 0.0 else if (costPct.isFinite() && costPct > 0.0) costPct else return false
        if (e.lowerPct > bar) return true
        return e.n >= TAIL_MIN_N_8136 && e.meanPct > bar && e.runnerRate.isFinite() && e.runnerRate >= TAIL_MIN_RATE_8136
    }

    /** Pure: losing on its own evidence — [LOSING_MIN_N_8136]+ labels and mean + SE below zero (LanePlaybook7907.setupRecordNegative8094). */
    fun losing8136(e: Evidence8136?): Boolean =
        e != null && e.n >= LOSING_MIN_N_8136 && e.upperPct.isFinite() && e.upperPct < 0.0

    /** Pure: proven losing — [PROVEN_LOSING_MIN_N_8136]+ labels and mean + SE below [PROVEN_LOSING_PCT_8136]. */
    internal fun provenLosing8136(e: Evidence8136?): Boolean =
        e != null && e.n >= PROVEN_LOSING_MIN_N_8136 && e.upperPct.isFinite() && e.upperPct < PROVEN_LOSING_PCT_8136

    /**
     * Pure (V5.0.8198) — the 8114 stage rule's runner exemption (TokenMetricStageRouter.judgeStage7928: "the late read that
     * spares runner lanes"): a stage whose 5-minute labels lose but whose 60-minute labels pay (STAGE_LATE_MIN_N_8198+, mean
     * net > 0) is a runner stage, not a losing one. 8136 re-implemented the stage veto here without it, so every stage that
     * pays late (the 600-700% runs) was refused on its 5-minute read while LiveEdgeGate's own 7928 read still spared it.
     */
    fun stageLatePays8198(e: Evidence8136?): Boolean =
        e != null && e.lateN >= STAGE_LATE_MIN_N_8198 && e.lateMeanPct.isFinite() && e.lateMeanPct > 0.0

    /** Same bar as TokenMetricStageRouter.STAGE_MIN_N_7928 (one rule for both readers). */
    const val STAGE_LATE_MIN_N_8198 = 30

    internal const val STALE_CELL_TRACE_8169 = "staleCellLossDecayed8169="
    internal const val STALE_STAGE_TRACE_8169 = "staleStageLossDecayed8169="

    /** Pure (V5.0.8169): the all-time record [full] proves a loss that the post-epoch record [fresh] does not. */
    fun staleLoss8169(epochAware: Boolean, full: Evidence8136?, fresh: Evidence8136?): Boolean =
        epochAware && provenLosing8136(full) && !provenLosing8136(fresh)

    /** Pure: positive but thin — under [SUPPORT_MIN_N_8136] labels; it never bypasses the generic prior. */
    fun thinPositive8136(e: Evidence8136?): Boolean =
        e != null && e.n in 1 until SUPPORT_MIN_N_8136 && e.meanPct.isFinite() && e.meanPct > 0.0

    /** Pure: is the price too old to trade on? A hot launch moves faster, so its bar is tighter. */
    fun priceStale8136(priceAgeMs: Long, hotLaunch: Boolean): Boolean =
        priceAgeMs < 0L || priceAgeMs > (if (hotLaunch) HOT_STALE_PRICE_MS_8136 else STALE_PRICE_MS_8136)

    /**
     * Shared across all twelve native specialists. Missing optional classifier inputs are NOT an execution-safety
     * failure when a fresh authoritative native lane verdict, a named native strategy, a passing score and a sized,
     * positive-after-cost quote are already available. Never synthesise a price, a route, a quote or an edge:
     * absent/stale executable facts still defer, and known-negative evidence is handled before this helper.
     */
    internal fun optionalNativeContextClears8213(c: Candidate8136, gross: Double): Boolean =
        c.nativeQualified8213 &&
            !c.nativeSetup8213.isNullOrBlank() &&
            !c.nativeSetup8213.equals("NONE", true) &&
            c.genericScore.isFinite() && c.genericFloor.isFinite() && c.genericScore >= c.genericFloor &&
            c.costPct.isFinite() && c.costPct > 0.0 &&
            !priceStale8136(c.priceAgeMs, c.hotLaunch) &&
            c.missingInputs.none(::criticalMissing8170) &&
            c.safetyRefusal == null && c.structuralRisk == null &&
            NetEdge8030.entryClears8030(gross, c.costPct)

    // ── the decision ──

    /** Pure: the one terminal admission for [c]. Order: safety, structure, loss patterns, timing, economics, data, edge, prior. */
    fun decide8136(c: Candidate8136): Decision8136 {
        val trace = ArrayList<String>(10)
        trace += "lane=${c.lane} version=${c.candidateVersion} setup=${c.selectedSetup ?: "-"} regime=${c.regime.ifBlank { "-" }} stage=${c.stage.ifBlank { "-" }}${if (c.hotLaunch) " hot" else ""}"
        var decisionGross = c.expectedGrossPct
        var net = if (decisionGross.isFinite() && c.costPct.isFinite()) decisionGross - c.costPct else Double.NaN
        trace += "gross=${fmt8136(decisionGross)} cost=${fmt8136(c.costPct)} net=${fmt8136(net)} downside=${fmt8136(c.downsidePct)}" +
            (if (c.costSizeSol.isFinite()) " costSize=${"%.4f".format(c.costSizeSol)}SOL" else "")
        fun done(t: Terminal8136, reason: String, specific: Boolean, setup: String?, basis: String = ""): Decision8136 =
            Decision8136(t, reason, specific, setup, net, trace.toList(), basis.ifBlank { reason },
                costPct = c.costPct, costSizeSol = c.costSizeSol, costBreakdown = c.costBreakdown)

        // 1. hard safety and structural selling risk: nothing overrides them.
        c.safetyRefusal?.let { trace += "safety=$it"; return done(Terminal8136.REFUSE, "SAFETY_REFUSED_8136", true, c.selectedSetup) }
        // V5.0.8221 §P0d — a FRESH_LAUNCH statistical refusal (learned negative
        // setup record from FreshLaunchSelector7737) is NOT a hard structural
        // hazard. Pre-8221 the structuralRisk field conflated both and short-
        // circuited admission before alternative supported setups were
        // evaluated, so cell/kind-proven alternatives from the same lane never
        // got their chance. Operator 8220 forensic: MONEROCHAN +27139% refused
        // on FRESH_LAUNCH_SETUP_STOPS_FIRST_7737 while the lane had other
        // qualified evidence. The learned refusal falls through here into the
        // alternative-setup evaluation; a true RUG_PRONE still refuses hard.
        c.structuralRisk?.let {
            val isLaunchLearned = it.startsWith("FRESH_LAUNCH_")
            if (!isLaunchLearned) {
                trace += "structure=$it"; return done(Terminal8136.REFUSE, "STRUCTURAL_RISK_8136", true, c.selectedSetup)
            }
            trace += "launchLearned=$it"
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ADMISSION_LAUNCH_LEARNED_FALLTHROUGH_8221") } catch (_: Throwable) {}
        }

        // 2. candidate-specific loss patterns. The coin's own cell proven to pay, or another fired setup supported on its
        //    own record, is the strategy instead (LiveEdgeGate7877: cellOrKindProven8094 / provenSetup8056 multi-strategy).
        // Pre-8210 all-mode cells are audit history, not candidate-specific live approval evidence.
        val liveCell8210 = if (c.lossEpochAware8169) c.cellLoss8169 else c.cell
        val cellSupported = supported8136(liveCell8210, c.costPct)
        // V5.0.8153 — every fired setup ranked on its own record AND its timing band (StrategyRank8153): a supported setup
        //    losing at this point of the move is not the strategy; ties of the conservative net go to the runner rate.
        val bestSetup = bestSetup8153(c)
        val selectedRec = c.setups.firstOrNull { it.id == c.selectedSetup }
        var chosen = c.selectedSetup ?: c.nativeSetup8213?.takeIf {
            c.nativeQualified8213 && it.isNotBlank() && !it.equals("NONE", true)
        }
        fun chooseSetup(id: String) {
            chosen = id
            decisionGross = if (id == c.selectedSetup) c.expectedGrossPct
                else c.expectedGrossBySetup[id] ?: Double.NaN
            net = if (decisionGross.isFinite() && c.costPct.isFinite()) decisionGross - c.costPct else Double.NaN
            trace += "economicsSetup=$id gross=${fmt8136(decisionGross)} cost=${fmt8136(c.costPct)} net=${fmt8136(net)}"
        }
        if (losing8136(selectedRec)) {
            // V5.0.8198 — precedence 2 before 3: the selected setup's own losing record is candidate-specific negative
            //    evidence about THIS executable strategy. Only an independently supported alternative setup (a different
            //    strategy, with its own economics) replaces it; a positive cell record names no strategy and no longer
            //    lets the proven-losing setup trade ("carried by cell" admitted it under SPECIFIC_EDGE_PROVEN).
            if (bestSetup == null) {
                trace += "setupLosing=${selectedRec?.compact}${if (cellSupported) " cellSupported=${c.cell?.compact} names_no_strategy" else ""}"
                return done(Terminal8136.REFUSE, "SETUP_RECORD_LOSING_8136", true, c.selectedSetup)
            }
            trace += "altSetup=${bestSetup.compact} over ${selectedRec?.compact}"
            chooseSetup(bestSetup.id)
        }
        // V5.0.8169 — a loss pattern refuses on the record graded since the epoch; an older one is noted and decays to
        //    neutral (the candidate goes on to the generic prior — never to an ADMIT the stale record cannot support).
        val cellLoss = if (c.lossEpochAware8169) c.cellLoss8169 else c.cell
        // V5.0.8212: UNKNOWN is not a lifecycle stage and carries no loss authority.
        // TokenMetricStageRouter.judgeStage7928 explicitly ignores UNKNOWN; 8136 had
        // independently been blocking the same unclassified tokens on STAGE|LANE|UNKNOWN.
        val stageLoss = if (c.stage.equals("UNKNOWN", ignoreCase = true)) null
            else if (c.lossEpochAware8169) c.stageLoss8169 else c.stageRecord
        if (provenLosing8136(cellLoss)) {
            // V5.0.8240 — the cell (source|lane|mcap|age) is a lane-wide prior over every coin of that shape, exactly like
            //    the stage record below: it names no strategy. 8241 runner ledger: MONPAD +30584% and KimWhale +2962% were
            //    MISSED(CELL_PROVEN_LOSING) while a SUPPORTED setup (n, mean - SE and timing band clearing cost — the
            //    token-specific strategy evidence) had fired on them. As for the stage loss (8173), the supported setup is
            //    the strategy; without one the cell prior refuses. Economics, data and the edge test below still decide.
            if (bestSetup == null) { trace += "cellLosing=${cellLoss?.compact}"; return done(Terminal8136.REFUSE, "CELL_PROVEN_LOSING_8136", true, chosen) }
            trace += "cellLosing=${cellLoss?.compact} carried by supported setup ${bestSetup.compact}"
            if (chosen != bestSetup.id) chooseSetup(bestSetup.id)
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CELL_LOSS_CARRIED_BY_SUPPORTED_SETUP_8240") } catch (_: Throwable) {}
        }
        if (staleLoss8169(c.lossEpochAware8169, c.cell, cellLoss)) trace += "$STALE_CELL_TRACE_8169${c.cell?.compact}"
        if (provenLosing8136(stageLoss) && !cellSupported && stageLatePays8198(stageLoss)) {
            trace += "stageLosing5m=${stageLoss?.compact} spared late60m=n${stageLoss?.lateN} ${fmt8136(stageLoss?.lateMeanPct ?: Double.NaN)}"
        }
        if (provenLosing8136(stageLoss) && !cellSupported && !stageLatePays8198(stageLoss)) {
            // V5.0.8173 — the stage record is a lane-wide prior (every coin of the lane at this lifecycle stage). A fired
            //    setup supported on its own record (StrategyRank8153: n, mean - SE, its own timing band) is the token-specific
            //    edge and is the strategy instead, exactly as for a losing selected setup or tactic above — unless the
            //    coin's own cell is losing. 5.0.8172: STAGE_PROVEN_LOSING refused 432 while LAUNCH_LADDER_PROVEN (n210, auth
            //    1.00) fired on the same launches. Economics, data and the edge test below still decide.
            // V5.0.8180 — bestSetup (SUPPORTED) was null on nearly every live candidate (thin books): the coin's own
            //    evidence at the VALID margin (a fired setup, else its own cell) carries the lane-wide stage loss too.
            val carry8180 = bestSetup ?: StageCarry8180.carry8180(c)
            if (carry8180 == null || (losing8136(cellLoss) && bestSetup == null)) {
                trace += "stageLosing=${stageLoss?.compact}"
                return done(Terminal8136.REFUSE, "STAGE_PROVEN_LOSING_8136", true, chosen)
            }
            trace += "stageLosing=${stageLoss?.compact} carried by ${carry8180.kind.lowercase()} ${carry8180.compact}"
            // A VALID carry is not an edge: the strategy (and its measured economics) changes only for a SUPPORTED setup.
            if (bestSetup != null && bestSetup.id != chosen) chooseSetup(bestSetup.id)
        }
        if (!cellSupported && staleLoss8169(c.lossEpochAware8169, c.stageRecord, stageLoss)) trace += "$STALE_STAGE_TRACE_8169${c.stageRecord?.compact}"
        if (c.losingTactic != null && bestSetup == null && !cellSupported) {
            trace += "tacticLosing=${c.losingTactic}"
            return done(Terminal8136.REFUSE, "TACTIC_RECORD_LOSING_8136", true, chosen)
        }
        // 3. timing: the selected setup's record at this point of the move. A token whose own cell pays has already paid
        //    for its timing (its labels start at the fill latency).
        // V5.0.8144 — the default (selected) setup's adverse timing no longer vetoes before the token's own proven
        //    alternatives are weighed: another fired setup supported on its own record is the strategy instead (the
        //    timing book belongs to the selected setup, not to the alternative).
        if (chosen == c.selectedSetup && losing8136(c.timing) && !cellSupported && bestSetup != null && bestSetup.id != c.selectedSetup) {
            trace += "altSetup=${bestSetup.compact} over timing ${c.timing?.compact}"
            chooseSetup(bestSetup.id)
        }
        if (chosen == c.selectedSetup && losing8136(c.timing) && !cellSupported) {
            trace += "timing=${c.timing?.compact}"
            return done(Terminal8136.REFUSE, "TIMING_ADVERSE_8136", true, chosen)
        }

        val chosenRec = c.setups.firstOrNull { it.id == chosen }
        val edge = listOfNotNull(bestSetup ?: chosenRec?.takeIf { supported8136(it, c.costPct) }, liveCell8210?.takeIf { cellSupported },
            c.cortexStrong8200?.takeIf { supported8136(it, c.costPct) && !losing8136(cellLoss) },  // V5.0.8200
            c.runnerGrab8245?.takeIf { grabSupported8245(it) && !losing8136(cellLoss) })   // V5.0.8245
            .maxByOrNull { it.lowerPct }
        if (edge?.kind == KIND_RUNNER_GRAB_8245) {
            runnerEntry8252[c.mint] = System.currentTimeMillis()
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ADMISSION_EDGE_RUNNER_GRAB_8245")
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("RUNNER_FUNNEL_8254_ADMISSION_EDGE")
            } catch (_: Throwable) {}
        }
        // Resolve the winning strategy before validating its executable economics.
        if (edge?.kind == KIND_SETUP && edge.id != chosen) chooseSetup(edge.id)

        // V5.0.8171: an unformed optional chart has no gross-move forecast;
        // that does NOT erase a statistically supported NET executable cohort.
        // Translate only the conservative measured net lower bound back to gross
        // using this candidate's KNOWN round-trip cost. Never use a prior, a
        // thin positive, a hypothetical runner tail or an unknown cost.
        // V5.0.8172 — only when the candidate's own move is unformed (no chart). A measured chart with an unmeasured
        // ALTERNATIVE setup defers on economics: the alternative never borrows a move it was not measured on (8153).
        if (!decisionGross.isFinite() && !c.expectedGrossPct.isFinite() && edge != null && edge.netOfCost &&
            edge.lowerPct.isFinite() && edge.lowerPct > 0.0 &&
            c.costPct.isFinite() && c.costPct >= 0.0) {
            decisionGross = edge.lowerPct + c.costPct
            net = edge.lowerPct
            trace += "grossFromProvenNetLower8171=${fmt8136(edge.lowerPct)} cost=${fmt8136(c.costPct)}"
        }

        // 4. execution economics: the expected executable move must clear NetEdge8030.EDGE_MULT x the round trip.
        val econKnown = decisionGross.isFinite() && c.costPct.isFinite()
        if (econKnown && !NetEdge8030.entryClears8030(decisionGross, c.costPct)) {
            // V5.0.8204 — the refusal names its evidence: expected gross, round-trip cost at the ticket it was costed at
            // (CostMeter8151 last ticket / default), and the cost breakdown. 5.0.8202: 319 economic refusals, no record of
            // which notional or liquidity they were judged at.
            trace += "econRefused8204 gross=${fmt8136(decisionGross)} cost=${fmt8136(c.costPct)} needMult=${NetEdge8030.EDGE_MULT} " +
                "breakdown=${c.costBreakdown.ifBlank { try { NetEdge8030.lastEntryBreakdown8204() } catch (_: Throwable) { "-" } }}"
            return done(Terminal8136.REFUSE, "EXECUTION_ECONOMICS_8136", true, chosen)
        }

        // 5. data: a stale price or unknown economics defers every candidate; missing classifier inputs defer a candidate
        //    with no supported evidence of its own (its setup cannot be judged yet).
        val stale = priceStale8136(c.priceAgeMs, c.hotLaunch)
        val criticalMissing8170 = c.missingInputs.filter(::criticalMissing8170)
        val nativeOptional8213 = c.missingInputs.isNotEmpty() && edge == null &&
            optionalNativeContextClears8213(c, decisionGross)
        if (stale || !econKnown || criticalMissing8170.isNotEmpty() ||
            (c.missingInputs.isNotEmpty() && edge == null && !nativeOptional8213)) {
            val missing = c.missingInputs + (if (stale) listOf("price") else emptyList()) + (if (!econKnown) listOf("economics") else emptyList())
            trace += "missing=${missing.joinToString(",")}"
            if (criticalMissing8170.isNotEmpty()) trace += "criticalUnobserved8170=${criticalMissing8170.joinToString(",")}"
            return done(Terminal8136.DEFER_DATA, "DATA_INCOMPLETE_DEFER_8136", false, chosen)
        }
        // A price/route/cost-proven candidate with POSITIVE candidate-specific graded
        // evidence may trade through an OPTIONAL shape/tape/context gap. Do not
        // reclassify optional absence as a fake signal; retain the gap in its seal.
        if (c.missingInputs.isNotEmpty()) trace += "optionalUnobserved8170=${c.missingInputs.joinToString(",")}"
        if (nativeOptional8213) trace += "nativeOptionalCleared8213=${c.nativeSetup8213} score=${fmt8136(c.genericScore)}"

        // 6. supported candidate-specific edge: the generic score is only a prior.
        if (edge != null) {
            trace += "edge=${edge.compact}${if (edge.runnerRate.isFinite()) " run=${(edge.runnerRate * 100).toInt()}%" else ""}"
            return done(Terminal8136.ADMIT, "SPECIFIC_EDGE_PROVEN_8136", true, chosen, edge.compact)
        }

        // 7. no supported edge: thin positives are noted, never admitted; the generic prior decides.
        listOfNotNull(chosenRec, c.cell).filter { thinPositive8136(it) }
            .forEach { trace += "thinPositive=${it.compact} needs n>=$SUPPORT_MIN_N_8136" }
        if (c.genericScore.isFinite() && c.genericFloor.isFinite()) {
            trace += "generic=${fmt8136(c.genericScore)} floor=${fmt8136(c.genericFloor)}"
            if (c.genericScore < c.genericFloor) return done(Terminal8136.REFUSE, "GENERIC_SCORE_FLOOR_8136", false, chosen)
            // V5.0.8238 §LIVE_IS_PROVEN_EVIDENCE_ONLY — 8237 live book: every recorded live loss (CASHGEN|NO_TRIGGER x3,
            // CASHGEN|NONE, MOONSHOT|NONE, …) was a generic-prior admit: a V3 score over a fluid floor of 15 with no setup
            // record, no supported cell, no validated Cortex read. The 700% wins were proven setups. Live money needs a
            // supported record (step 6); the prior alone admits paper and the shadow book, where it is learnt from.
            if (c.live && !cellSupported) {
                trace += "liveGenericPriorUnsupported8238 cell=${c.cell?.compact ?: "-"}"
                return done(Terminal8136.REFUSE, "LIVE_GENERIC_PRIOR_UNSUPPORTED_8238", false, chosen)
            }
            return done(Terminal8136.ADMIT, "GENERIC_PRIOR_CLEARS_8136", false, chosen,
                if (nativeOptional8213) "NATIVE_QUALIFIED_8213:${c.nativeSetup8213}" else "GENERIC_PRIOR:${chosen ?: "-"}")
        }
        return done(Terminal8136.REFUSE, "NO_SUPPORTED_EDGE_8136", false, chosen)
    }

    /**
     * Pure (V5.0.8153): the best SUPPORTED fired setup of [c] — the same evidence test as [supported8136] / [losing8136],
     * ranked by StrategyRank8153 (tier, conservative net capped by the setup's own timing band, runner rate, sample).
     */
    internal fun bestSetup8153(c: Candidate8136): Evidence8136? {
        val options = c.setups.map { e ->
            val t = c.setupTimings[e.id]
            StrategyRank8153.Option8153(e.id, e.n, e.meanPct, e.sePct, e.runnerRate, e.netOfCost,
                t?.n ?: 0, t?.meanPct ?: Double.NaN, t?.sePct ?: Double.NaN)
        }
        val best = StrategyRank8153.bestSupported8153(options, c.costPct) ?: return null
        return c.setups.firstOrNull { it.id == best.id }
    }

    const val KIND_SETUP = "SETUP"
    const val KIND_CELL = "CELL"
    /** V5.0.8245 — RunnerGrab7967's proven runner cell with the chart firming now. */
    const val KIND_RUNNER_GRAB_8245 = "RUNNER_GRAB"

    /**
     * V5.0.8252 §RUNNER_ENTRY_INTENT — the explicit runner-entry intent: this record ADMITTED the candidate on the
     * runner-grab edge (RunnerGrab7967 forming run in a proven runner cell). Written once per admission, read by
     * RunnerPlan8153 for the position that entry opened; a sealed scalp style can no longer demote it to a scalp plan.
     */
    private val runnerEntry8252 = ConcurrentHashMap<String, Long>()
    private const val RUNNER_ENTRY_WINDOW_MS_8252 = 5L * 60_000L

    /** Was the position that opened at [entryTimeMs] admitted on the runner-grab edge (inside the entry window)? */
    fun runnerEntryAdmitted8252(mint: String, entryTimeMs: Long): Boolean {
        val at = runnerEntry8252[mint] ?: return false
        return entryTimeMs > 0L && entryTimeMs >= at && entryTimeMs - at <= RUNNER_ENTRY_WINDOW_MS_8252
    }

    /** Test seam: the runner-entry admit stamp for [mint] at [atMs]. */
    internal fun stampRunnerEntryForTest8252(mint: String, atMs: Long) { runnerEntry8252[mint] = atMs }
    internal const val GRAB_MIN_N_8245 = 20
    internal const val GRAB_MARGIN_PCT_8245 = 20.0

    /** Pure (V5.0.8245): the grab's own proven bar — fewer labels than [SUPPORT_MIN_N_8136], a far higher net lower bound. */
    fun grabSupported8245(e: Evidence8136?): Boolean =
        e != null && e.n >= GRAB_MIN_N_8245 && e.lowerPct.isFinite() && e.lowerPct >= GRAB_MARGIN_PCT_8245
    /** V5.0.8200 — the Cortex's STRONG cohort on this candidate's lane (executable-net labels). */
    const val KIND_CORTEX_8200 = "CORTEX"
    const val KIND_TIMING = "TIMING"
    internal const val KIND_STAGE = "STAGE"

    /**
     * Pure: what the record means for a generic score floor — true clears it (supported specific edge), false holds it
     * with no lane-wide bypass (a specific refusal), null leaves the floor's own bypasses to decide.
     */
    fun floorVerdict8136(d: Decision8136?): Boolean? = when {
        d == null || !d.specific -> null
        d.terminal == Terminal8136.ADMIT -> true
        d.terminal == Terminal8136.REFUSE -> false
        else -> null
    }

    // ── bounded DATA_INCOMPLETE re-evaluation ──

    /** One mint's deferral window: first deferral and how many re-evaluations it has had. */
    data class Defer8136(val firstMs: Long, val count: Int)

    const val DEFER_WINDOW_MS_8136 = 10L * 60_000L
    const val DEFER_MAX_8136 = 40
    const val DEFER_RESET_MS_8136 = 30L * 60_000L

    /** Pure: the next deferral state. A window older than [DEFER_RESET_MS_8136] starts over, so no deferral is permanent. */
    fun nextDefer8136(prev: Defer8136?, nowMs: Long): Defer8136 =
        if (prev == null || nowMs < prev.firstMs || nowMs - prev.firstMs >= DEFER_RESET_MS_8136) Defer8136(nowMs, 1)
        else prev.copy(count = prev.count + 1)

    /** Pure: may this candidate still wait for its data ([DEFER_MAX_8136] re-evaluations inside [DEFER_WINDOW_MS_8136])? */
    fun deferOpen8136(s: Defer8136, nowMs: Long): Boolean =
        s.count <= DEFER_MAX_8136 && nowMs - s.firstMs in 0L..DEFER_WINDOW_MS_8136

    private val defers8136 = ConcurrentHashMap<String, Defer8136>()

    /** Counts one DATA_INCOMPLETE re-evaluation of [mint]; false once its bounded window is spent (the ordinary refusal then stands). */
    fun deferBudget8136(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank()) return false
        val s = defers8136.compute(mint) { _, p -> nextDefer8136(p, nowMs) } ?: return false
        if (defers8136.size > 4_000) defers8136.entries.removeIf { nowMs - it.value.firstMs >= DEFER_RESET_MS_8136 }
        val open = deferOpen8136(s, nowMs)
        if (!open) try { PipelineHealthCollector.labelInc("ADMISSION_DEFER_WINDOW_SPENT_8136") } catch (_: Throwable) {}
        return open
    }

    // ── V5.0.8144 §COALESCED_MISSING_DATA_REEVALUATION ──
    //
    // 5.0.8142: 458 DATA_INCOMPLETE FDG deferrals. Every cycle re-ran the whole admission record (playbook classification,
    // forward-label cells, timing / stage books, cost model) for a candidate whose missing input had not had time to
    // arrive, and every re-run spent one of its 40 bounded re-evaluations. A deferral is now scheduled per mint + missing
    // feature with a backoff ([REEVAL_BASE_MS_8144] doubling to [REEVAL_MAX_MS_8144]): until one of its features is due,
    // the last DEFER_DATA decision is returned as it was and no budget is spent; when one is due, the record is decided
    // again in full. The deferral window, its budget and its reset are unchanged, and nothing but DEFER_DATA is cached.

    /** One mint + missing feature: when it is next due and how many times it has been re-scheduled. */
    data class Reeval8144(val dueMs: Long, val attempts: Int)

    const val REEVAL_BASE_MS_8144 = 5_000L
    const val REEVAL_MAX_MS_8144 = 60_000L

    /** Pure: the next schedule after a re-evaluation at [nowMs] — the backoff doubles per attempt up to the cap. */
    fun nextReeval8144(prev: Reeval8144?, nowMs: Long): Reeval8144 {
        val attempts = (prev?.attempts ?: 0).coerceAtMost(16)
        val wait = (REEVAL_BASE_MS_8144 shl attempts.coerceAtMost(10)).coerceAtMost(REEVAL_MAX_MS_8144)
        return Reeval8144(nowMs + wait, attempts + 1)
    }

    /** Pure: is this feature due (never scheduled, its time reached, or a schedule from a clock that moved back)? */
    fun reevalDue8144(s: Reeval8144?, nowMs: Long): Boolean =
        s == null || nowMs >= s.dueMs || s.dueMs - nowMs > 2 * REEVAL_MAX_MS_8144

    /** Pure: the features a DEFER_DATA decision is waiting for (its "missing=" trace entry). */
    fun missingOf8144(d: Decision8136): List<String> =
        d.trace.firstOrNull { it.startsWith("missing=") }?.removePrefix("missing=")?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

    private class Held8144(
        val candidate: Candidate8136, val decision: Decision8136, val features: List<String>, val atMs: Long,
        val candidateVersion: Long, val mode: String,
    )

    private val reevals8144 = ConcurrentHashMap<String, Reeval8144>()
    /** A cached verdict is valid only for the same candidate evidence and generation. */
    private data class Memo8153(val atMs: Long, val candidate: Candidate8136, val decision: Decision8136)
    private val memos8153 = ConcurrentHashMap<String, Memo8153>()
    private const val MEMO_MS_8153 = 1_500L
    /** V5.0.8223 — the FDG snapshot prior (score, fluid floor, admission size) carried per candidate generation. */
    private data class SnapshotPrior8223(val score: Double, val floor: Double, val sizeSol: Double, val atMs: Long)
    private val snapshotPriors8223 = ConcurrentHashMap<String, SnapshotPrior8223>()
    private const val PRIOR_CARRY_MS_8223 = 120_000L
    private val held8144 = ConcurrentHashMap<String, Held8144>()
    private val coalesced8144 = AtomicLong(0)

    // ── V5.0.8252 §EVIDENCE_ARRIVAL ──
    // A held DEFER_DATA waited for its backoff even after the missing input had arrived (5.0.8251: DATA_INCOMPLETE_DEFER
    // 577 FDG blocks, SETUP_INPUT_REFRESH_REQUESTED 1,676). When a source answers for [mint] with one of the features a
    // held defer is waiting for, that feature is due NOW: the next decide8136 re-decides the record in full on the new
    // evidence instead of returning the coalesced defer, and the pipeline marks the mint near a decision. Nothing is
    // decided here and no feature is assumed present — the record still reads its own inputs.
    private val arrivalReevals8252 = AtomicLong(0)
    val TAPE_FEATURES_8252: Set<String> = setOf("tape", "swaps", "bp")
    val PAIR_FEATURES_8252: Set<String> = setOf("bp", "chg", "liq", "liquidity", "mcap")

    fun evidenceArrived8252(mint: String, features: Set<String>, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || held8144.isEmpty()) return
        var woke = false
        for ((key, h) in held8144) {
            if (!key.startsWith("$mint|")) continue
            for (f in h.features) {
                if (f.substringBefore(':') !in features) continue
                val rk = "$mint|$f"
                reevals8144[rk] = Reeval8144(nowMs, reevals8144[rk]?.attempts ?: 0)
                woke = true
            }
        }
        if (!woke) return
        arrivalReevals8252.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EVIDENCE_ARRIVED_REEVAL_DUE_8252") } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.DataPipeline8191.noteNearDecision8191(mint, nowMs) } catch (_: Throwable) {}
    }

    /** Test seam: is the held defer's [feature] for [mint] due right now? */
    internal fun reevalDueForTest8252(mint: String, feature: String, nowMs: Long): Boolean = reevalDue8144(reevals8144["$mint|$feature"], nowMs)

    /** Test seam: hold a DEFER_DATA for [mint] in [lane] waiting on [features], scheduled with the normal backoff. */
    internal fun holdDeferForTest8252(mint: String, lane: String, features: List<String>, nowMs: Long) {
        scheduleDue8144(mint, features, nowMs)
        val c = Candidate8136(mint, lane, null, missingInputs = features)
        held8144["$mint|$lane"] = Held8144(c, Decision8136(Terminal8136.DEFER_DATA, "DATA_INCOMPLETE_DEFER_8136", false, null, Double.NaN, listOf("missing=${features.joinToString(",")}")), features, nowMs, 1L, "LIVE")
    }

    private fun features8144(missing: List<String>): List<String> = missing.ifEmpty { listOf("core:UNKNOWN") }

    /** Schedules every due feature of [mint]; true when at least one was due (the caller re-evaluates now). */
    private fun scheduleDue8144(mint: String, features: List<String>, nowMs: Long): Boolean {
        var due = false
        for (f in features) {
            val key = "$mint|$f"
            val prev = reevals8144[key]
            if (reevalDue8144(prev, nowMs)) { reevals8144[key] = nextReeval8144(prev, nowMs); due = true }
        }
        if (reevals8144.size > 8_000) reevals8144.entries.removeIf { nowMs - it.value.dueMs > DEFER_RESET_MS_8136 }
        return due
    }

    /** The held DEFER_DATA decision for [mint] in [laneRaw] while none of its missing features is due yet, else null. */
    private fun coalescedDefer8144(mint: String, laneRaw: String, candidate: Candidate8136, candidateVersion: Long,
                                   mode: String, nowMs: Long): Decision8136? {
        val h = held8144["$mint|$laneRaw"] ?: return null
        if (nowMs - h.atMs !in 0L..REEVAL_MAX_MS_8144) return null
        if (h.candidateVersion != candidateVersion || h.mode != mode) return null
        // A data response, changed price/economics, changed strategy, or new label evidence invalidates the held defer.
        // Only reuse it when the exact evidence that produced it is still present.
        if (h.candidate != cacheIdentity8144(candidate)) return null
        if (h.features.any { reevalDue8144(reevals8144["$mint|$it"], nowMs) }) return null
        coalesced8144.incrementAndGet()
        return h.decision
    }

    /** Holds a DEFER_DATA decision (scheduling its features) and files the decision in the runner trace. */
    private fun noteDecision8144(mint: String, laneRaw: String, candidate: Candidate8136, d: Decision8136,
                                 candidateVersion: Long, mode: String, nowMs: Long) {
        if (mint.isBlank()) return
        val key = "$mint|$laneRaw"
        if (d.terminal == Terminal8136.DEFER_DATA) {
            val f = features8144(missingOf8144(d))
            scheduleDue8144(mint, f, nowMs)
            if (held8144.size > 4_000) held8144.entries.removeIf { nowMs - it.value.atMs > REEVAL_MAX_MS_8144 }
            held8144[key] = Held8144(cacheIdentity8144(candidate), d, f, nowMs, candidateVersion, mode)
        } else {
            held8144.remove(key)
        }
        if (d.admitted && d.trace.any { it.startsWith("nativeOptionalCleared8213=") }) try {
            PipelineHealthCollector.labelInc("ADMISSION_NATIVE_OPTIONAL_DATA_ADMITTED_8213_${candidate.lane}")
        } catch (_: Throwable) {}
        try { AdmissionRunnerTrace8144.noteAdmission8144(mint, laneRaw, d.terminal.name, d.reason) } catch (_: Throwable) {}
        try { AdmissionEdge8180.note8180(mode, mint, laneRaw, candidateVersion, d, nowMs) } catch (_: Throwable) {}  // V5.0.8180 / 8198 — this lane's own proof, sizers read it
        // V5.0.8169 — a loss refusal that decayed to neutral on its epoch, counted once per fresh decision.
        try {
            if (d.trace.any { it.startsWith(STALE_CELL_TRACE_8169) }) com.lifecyclebot.engine.ExitDoctrine8169.noteStaleDecay8169(cell = true)
            if (d.trace.any { it.startsWith(STALE_STAGE_TRACE_8169) }) com.lifecyclebot.engine.ExitDoctrine8169.noteStaleDecay8169(cell = false)
        } catch (_: Throwable) {}
        // V5.0.8153 — the one strategy identity: every later reader of LanePlaybook7907.selectedSetup8094 gets this choice.
        try {
            val lane = CanonicalLaneIdentity6506.canonical(laneRaw).uppercase().ifBlank { laneRaw.trim().uppercase() }
            StrategyIdentity8153.record8153(mint, lane, d.chosenSetup, d.basis, nowMs,
                mode, candidateVersion, com.lifecyclebot.engine.cortex.LanePlaybook7907.selectionRevision8198(), d.admitted)
            AdmissionRunnerTrace8144.noteIdentity8153(mint, d.chosenSetup, d.basis)
        } catch (_: Throwable) {}
        if (memos8153.size > 4_000) memos8153.entries.removeIf { nowMs - it.value.atMs > MEMO_MS_8153 }
        memos8153["$mode|$mint|${candidate.lane}|$candidateVersion"] = Memo8153(nowMs, cacheIdentity8144(candidate), d)
    }

    /** Candidate price age affects admission only at the stale/fresh boundary. */
    private fun cacheIdentity8144(c: Candidate8136): Candidate8136 = c.copy(
        priceAgeMs = if (priceStale8136(c.priceAgeMs, c.hotLaunch)) Long.MAX_VALUE else 0L,
        costBreakdown = "",   // V5.0.8209 — the numbers (costPct, costSizeSol) are identity; the prose is not
    )

    /**
     * LiveEdgeGate7877.dataDefer8136: [deferBudget8136] coalesced per mint + missing feature. A call while none of
     * [missing] is due keeps the open window without spending a re-evaluation; a due feature re-evaluates (counted).
     */
    private val firstMissing8204 = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /**
     * V5.0.8204 — the inputs this mint was FIRST deferred on (per deferral window). Exhausting the budget while every one
     * of them is still missing means the requested fetch never completed. Pure given the recorded first set.
     */
    fun deferUnanswered8204(mint: String, missingNow: List<String>, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank()) return false
        val first = firstMissing8204[mint] ?: return false
        return first.isNotEmpty() && missingNow.map { it.trim() }.containsAll(first)
    }

    fun deferBudgetFor8144(mint: String, missing: List<String>, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isNotBlank()) {
            val prev = defers8136[mint]
            if (prev == null || nowMs < prev.firstMs || nowMs - prev.firstMs >= DEFER_RESET_MS_8136) firstMissing8204[mint] = missing.map { it.trim() }.filter { it.isNotBlank() }.toSet()
            if (firstMissing8204.size > 4096) firstMissing8204.keys.take(1024).forEach { firstMissing8204.remove(it) }
        }
        if (mint.isBlank()) return false
        if (scheduleDue8144(mint, features8144(missing), nowMs)) return deferBudget8136(mint, nowMs)
        val s = defers8136[mint] ?: return deferBudget8136(mint, nowMs)
        coalesced8144.incrementAndGet()
        try { PipelineHealthCollector.labelInc("ADMISSION_REEVAL_COALESCED_8144") } catch (_: Throwable) {}
        return deferOpen8136(s, nowMs)
    }

    // ── the ledger: counts per terminal admission reason ──

    private val counts8136 = ConcurrentHashMap<String, AtomicLong>()
    private const val MAX_KEYS_8136 = 64

    /** Pure: a data-gap reason (recoverable) rather than a verdict on the token. */
    internal fun dataReason8136(reason: String): Boolean =
        reason.startsWith("DATA_INCOMPLETE") || reason.contains("TOO_FEW_BARS")

    /** Pure: an existing gate's verdict (LiveEdgeGate7877.Verdict8083 name / plan wait) as a terminal admission. */
    fun terminalOf8136(verdict: String, reason: String): Terminal8136 = when {
        verdict == Terminal8136.ADMIT.name -> Terminal8136.ADMIT
        dataReason8136(reason) -> Terminal8136.DEFER_DATA
        else -> Terminal8136.REFUSE
    }

    /** Pure: the bounded ledger key — source, terminal and the reason without its lane / numeric tail. */
    fun ledgerKey8136(source: String, terminal: Terminal8136, reason: String): String {
        val r = reason.substringBefore(':').substringBefore(' ').replace(Regex("_-?\\d+(\\.\\d+)?PCT.*\$"), "")
        // Cut after the first version tag that ends a named reason (drops the lane / setup tail), e.g.
        // PLAYBOOK_SETUP_PROVEN_LOSING_7907_MOONSHOT_X -> PLAYBOOK_SETUP_PROVEN_LOSING_7907.
        val m = Regex("_\\d{4}").findAll(r).firstOrNull { it.range.last >= 11 }
        val stem = if (m != null) r.substring(0, m.range.last + 1) else r
        return "$source|${terminal.name}:${stem.take(44)}"
    }

    fun note8136(source: String, terminal: Terminal8136, reason: String) {
        try {
            val key = ledgerKey8136(source, terminal, reason)
            val k = if (counts8136.size < MAX_KEYS_8136 || counts8136.containsKey(key)) key else "$source|${terminal.name}:OTHER"
            counts8136.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet()
            PipelineHealthCollector.labelInc("CANDIDATE_ADMISSION_8136_${source}_${terminal.name}")
        } catch (_: Throwable) {}
    }

    /** LiveEdgeGate7877.recordAssessment8083 / TradePlan7739: an existing gate's terminal, filed in this ledger. */
    fun noteVerdict8136(source: String, verdict: String, reason: String) = note8136(source, terminalOf8136(verdict, reason), reason)

    fun statusLine8136(): String {
        val byTerminal = Terminal8136.values().joinToString(",") { t ->
            "${t.name}=${counts8136.entries.filter { it.key.contains("|${t.name}:") }.sumOf { it.value.get() }}"
        }
        val top = counts8136.entries.sortedByDescending { it.value.get() }.take(12)
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }
        return "admission8136[$byTerminal reasons{$top}] coalescedReevals8144=${coalesced8144.get()} arrivalReevals8252=${arrivalReevals8252.get()}"
    }

    // ── live adapter: the record from the bot's own stores (read-only peeks; no fetch, no synthetic data) ──

    private fun stdErr8136(variance: Double, n: Double): Double =
        if (n > 1.0 && variance.isFinite() && variance >= 0.0) kotlin.math.sqrt(variance / n) else Double.POSITIVE_INFINITY

    /** The admission record for [ts] in [laneRaw] now, decided. Null only on an internal failure. */
    fun assess8136(
        ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis(), candidateVersion: Long = 0L,
        /**
         * V5.0.8198 P0-B — the generic prior from the SAME decision snapshot (FinalDecisionGate: the lane's authoritative
         * entry score and its fluid CanonicalEntryFloor7266). Without them the record's generic branch was unreachable at
         * runtime (both defaulted NaN and candidateFor8136 never set them): a VALID carry ended as NO_SUPPORTED_EDGE with no
         * score in its trace. Callers without a snapshot score pass nothing; nothing is guessed.
         */
        genericScore: Double = Double.NaN, genericFloor: Double = Double.NaN,
        proposedSizeSol: Double = Double.NaN,   // V5.0.8209 — cost at THIS candidate's size; a material size change re-quotes (identity miss)
    ): Decision8136? = try {
        val mode = try { if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE" } catch (_: Throwable) { "UNKNOWN" }
        // V5.0.8212: an unrouteable 0.0100 SOL proposal has a hypothetical 11.6% round trip
        // in 8211. The live sizing gate can promote to the wallet's routable minimum when
        // permitted; price admission on that achievable ticket (only if this wallet can
        // actually carry it). The later risk/size/route gates still authorize or refuse
        // the exact sealed SOL amount and re-quote if it materially changes.
        val admissionSize8212 = if (mode == "LIVE") try {
            com.lifecyclebot.engine.truth.NetEdge8030.admissionQuoteSize8212(
                proposedSizeSol,
                (com.lifecyclebot.engine.BotService.status.walletSol -
                    com.lifecyclebot.engine.truth.LiveSpendReserveAuthority7255.RESERVE_SOL).coerceAtLeast(0.0),
                com.lifecyclebot.engine.WalletManager.lastKnownSolPrice,
            )
        } catch (_: Throwable) { proposedSizeSol } else proposedSizeSol
        val version = candidateVersion.takeIf { it > 0L }
            ?: try { com.lifecyclebot.engine.LaneExecutionCoordinator.candidateVersionFor(ts.mint) } catch (_: Throwable) { 0L }
        // V5.0.8223 — one canonical prior per candidate generation. FDG is the only caller that knows the authoritative
        // entry score and fluid floor; LiveEdgeGate7877 / TradePlan7739 / the FDG floor fallback re-assess the same
        // candidate WITHOUT them, so the memo identity missed (NaN score) and the record re-decided on a prior-less
        // candidate: GENERIC_PRIOR_CLEARS at the FDG became NO_SUPPORTED_EDGE in the plan (8220 forensic). The snapshot
        // FDG supplied is carried by (mode, mint, lane, version) and read by every later caller of the same generation.
        // Nothing is guessed: a candidate FDG never scored still carries no prior.
        val laneKey8223 = CanonicalLaneIdentity6506.canonical(laneRaw).uppercase().ifBlank { laneRaw.trim().uppercase() }
        val priorKey8223 = "$mode|${ts.mint}|$laneKey8223|$version"
        val prior8223 = if (genericScore.isFinite() && genericFloor.isFinite()) {
            SnapshotPrior8223(genericScore, genericFloor, admissionSize8212, nowMs).also {
                if (snapshotPriors8223.size > 4_000) snapshotPriors8223.entries.removeIf { e -> nowMs - e.value.atMs > PRIOR_CARRY_MS_8223 }
                snapshotPriors8223[priorKey8223] = it
            }
        } else snapshotPriors8223[priorKey8223]?.takeIf { nowMs - it.atMs in 0L..PRIOR_CARRY_MS_8223 }?.also {
            try { PipelineHealthCollector.labelInc("ADMISSION_PRIOR_CARRIED_8223_$laneKey8223") } catch (_: Throwable) {}
        }
        val sizeForQuote8223 = if (admissionSize8212.isFinite() && admissionSize8212 > 0.0) admissionSize8212 else prior8223?.sizeSol ?: admissionSize8212
        val built = candidateFor8136(ts, laneRaw, nowMs, sizeForQuote8223)
        val rawCandidate = if (prior8223 != null) built.copy(genericScore = prior8223.score, genericFloor = prior8223.floor) else built
        val candidate = rawCandidate.copy(candidateVersion = version)
        val key = "$mode|${ts.mint}|${candidate.lane}|$version"
        val identity = cacheIdentity8144(candidate)
        val cached = memos8153[key]?.takeIf { nowMs - it.atMs in 0L..MEMO_MS_8153 && it.candidate == identity }?.decision
        cached ?: coalescedDefer8144(ts.mint, candidate.lane, identity, version, mode, nowMs) ?: decide8136(candidate).also {
            noteDecision8144(ts.mint, candidate.lane, candidate, it, version, mode, nowMs)
        }
    } catch (_: Throwable) { null }

    /** Builds the candidate record from the playbook, forward labels, net-edge cost model and safety report. */
    internal fun candidateFor8136(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis(), proposedSizeSol: Double = Double.NaN): Candidate8136 {
        val lane = CanonicalLaneIdentity6506.canonical(laneRaw).uppercase().ifBlank { laneRaw.trim().uppercase() }
        val nativeOpinion8213 = try {
            com.lifecyclebot.engine.SpecialistBrainBridge7542.cachedSnapshot7650(ts.mint)
                ?.takeIf { it.mint == ts.mint }?.opinions?.get(lane)
                ?.takeIf { it.eligible && it.authoritative &&
                    com.lifecyclebot.engine.LaneExecutionCoordinator.laneCanOwnExecution6910(lane) }
        } catch (_: Throwable) { null }
        val pb = com.lifecyclebot.engine.cortex.LanePlaybook7907
        // V5.0.8153 — the strategy is ranked fresh over every fired setup (StrategyRank8153), not read back from the
        //    identity this record itself holds; its choice is then that identity for every later reader.
        val pick8153 = try { pb.strategyPick8153(ts, lane, nowMs) } catch (_: Throwable) { null }
        val options8153 = try { pb.strategyOptions8153(ts, lane, nowMs) } catch (_: Throwable) { emptyList() }
        val selected = pick8153?.id ?: try { pb.selectedSetup8094(ts, lane, nowMs) } catch (_: Throwable) { null }
        // V5.0.8217 — the lane's native qualification must not depend on one per-mint snapshot surviving in the cache.
        // A live QUALIFIED/READY entry in that lane's own candidate book (its hunter+brain qualified THIS mint) is the
        // same native verdict; its setup is the lane's own fired pick. Nothing is qualified that the lane did not qualify.
        val nativeBook8217 = if (nativeOpinion8213 != null) null else try {
            com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.entry(lane, ts.mint)?.takeIf {
                (it.state == com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.State.QUALIFIED ||
                    it.state == com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.State.READY) &&
                    com.lifecyclebot.engine.LaneExecutionCoordinator.laneCanOwnExecution6910(lane)
            }
        } catch (_: Throwable) { null }
        // V5.0.8221 §P0a — preserve native qualification when the playbook has no selected setup. Pre-8221 this field
        // required BOTH nativeBook8217 != null AND pick8153.id != null. A QUALIFIED/READY candidate-book entry with
        // no playbook pick (optional data gap) therefore lost its native qualification and could be deferred before
        // intent. Operator 8220 forensic: MOONSHOT 4832 native approvals, 0 live buys — exactly this failure mode.
        // Fix: when the book is QUALIFIED/READY, treat the lane's own hunter+brain verdict as native qualification
        // using a NATIVE_BOOK sentinel when no playbook id is available. The lane still owns the strategy identity;
        // nothing bypasses safety, rug, FDG or sizing downstream.
        val nativeSetupBook8217 = when {
            nativeBook8217 == null -> null
            pick8153?.id != null && pick8153.id.isNotBlank() && !pick8153.id.equals("NONE", true) -> pick8153.id
            else -> "NATIVE_BOOK_8221"
        }
        if (nativeSetupBook8217 != null) try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ADMISSION_NATIVE_FROM_BOOK_8217_$lane")
            if (nativeSetupBook8217 == "NATIVE_BOOK_8221") com.lifecyclebot.engine.PipelineHealthCollector
                .labelInc("ADMISSION_NATIVE_BOOK_WITHOUT_PICK_8221_$lane")
        } catch (_: Throwable) {}
        val setups = try {
            pb.firedSetupRecords8136(ts, lane, nowMs).map { (id, r) -> Evidence8136(KIND_SETUP, id, r[0].toInt(), r[1], r[2], r[3]) }
        } catch (_: Throwable) { emptyList() }
        val band8153 = try { pb.timingBandOf8094(ts) } catch (_: Throwable) { pb.TIMING_UNKNOWN_8094 }
        val setupTimings = options8153.filter { it.timingN > 0 }.associate { o ->
            o.id to Evidence8136(KIND_TIMING, "${o.id}@$band8153", o.timingN, o.timingMeanPct, o.timingSePct)
        }
        val cellStat = try { ForwardReturnLabeler7731.cellStatFor(ts, lane, nowMs) } catch (_: Throwable) { null }
        // A cell whose observations mostly lost their mark is not a measurement of its coins (LiveEdgeGate7877.mostlyUnpriced8022).
        val cell = cellStat?.takeIf { it.resolvedShare >= LiveEdgeGate7877.MIN_RESOLVED_SHARE_7944 }
            ?.let { Evidence8136(KIND_CELL, it.key.take(40), it.n60, it.meanNet60Pct, it.stderr60Pct, it.runnerRate60) }
        // V5.0.8153 — the timing record of the strategy selected here (not of whatever selectedSetup8094 held).
        val timing = if (selected != null && options8153.any { it.id == selected }) setupTimings[selected] else try {
            pb.timingStat8094(ts, lane, nowMs)?.let { a ->
                Evidence8136(KIND_TIMING, "${selected ?: "-"}@${pb.timingBandOf8094(ts)}", a[2].toInt(), a[0], stdErr8136(a[1], a[2]))
            }
        } catch (_: Throwable) { null }
        val fit = try { com.lifecyclebot.engine.TokenMetricStageRouter.laneFit(ts, lane) } catch (_: Throwable) { null }
        val stageRecord = try {
            if (fit == null) null else ForwardReturnLabeler7731.stageStatFor7928(fit.lane, fit.stage.name)
                ?.let { st -> Evidence8136(KIND_STAGE, fit.stage.name, st.n60, st.meanNet60Pct, st.stderr60Pct, st.runnerRate60, lateN = st.n240, lateMeanPct = if (st.n240 > 0) st.meanNet240Pct else Double.NaN) }
        } catch (_: Throwable) { null }
        // V5.0.8169 — the same cell and stage, booked since the grading epoch: the only records a loss pattern refuses on.
        val cellLoss8169 = try {
            ForwardReturnLabeler7731.freshCellStatFor8169(ts, lane, nowMs)
                ?.let { Evidence8136(KIND_CELL, it.key.take(40), it.n60, it.meanNet60Pct, it.stderr60Pct, it.runnerRate60) }
        } catch (_: Throwable) { null }
        val stageLoss8169 = try {
            if (fit == null) null else ForwardReturnLabeler7731.freshStageStatFor8169(fit.lane, fit.stage.name)
                ?.let { st -> Evidence8136(KIND_STAGE, fit.stage.name, st.n60, st.meanNet60Pct, st.stderr60Pct, st.runnerRate60, lateN = st.n240, lateMeanPct = if (st.n240 > 0) st.meanNet240Pct else Double.NaN) }
        } catch (_: Throwable) { null }
        // V5.0.8153 — unread depth is UNKNOWN, never LOW: the launch probe is costed without impact nobody measured.
        val launch = try {
            FreshLaunchSelector7737.launchRead7742(ts, FieldManual7715.allInCostPct(StrategyMove8153.probeSizeUsd8153(ts.lastLiquidityUsd), ts.lastLiquidityUsd), nowMs)
        } catch (_: Throwable) { null }
        val costRec8209 = try { NetEdge8030.entryCost8209(ts, lane, proposedSizeSol) } catch (_: Throwable) { null }
        val cost8153 = costRec8209?.costPct ?: Double.NaN
        val curveActive8153 = try { CurveReserves7951.realSolIfActive8046(ts.mint, nowMs) != null } catch (_: Throwable) { false }
        // V5.0.8170: a live DexScreener pair or sweep already priced the actual
        // liquidity, but the token-local scalar hadn't received the ordinary poll.
        // Use the same dated source the setup resolver reads to avoid needless
        // DATA_INCOMPLETE; the on-chain pool/route hard gates remain later.
        val observedLiq8170 = try {
            SetupFeatureResolver8169.observedLiquidity8170(ts.mint,
                try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts)?.ageMin ?: Double.NaN } catch (_: Throwable) { Double.NaN },
                nowMs)
        } catch (_: Throwable) { null }
        val liquidityMissing8153 = !curveActive8153 &&
            StrategyMove8153.liquidityRead8153(ts.lastLiquidityUsd) == "UNKNOWN" &&
            observedLiq8170 == null
        val safety = if (ts.safety.tier == com.lifecyclebot.engine.SafetyTier.HARD_BLOCK)
            "HARD_BLOCK:${ts.safety.hardBlockReasons.firstOrNull().orEmpty().take(40)}" else null
        val priceAge = if (ts.lastPrice > 0.0 && ts.lastPriceUpdate > 0L) nowMs - ts.lastPriceUpdate else Long.MAX_VALUE
        return Candidate8136(
            mint = ts.mint,
            lane = lane,
            selectedSetup = selected,
            regime = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name } catch (_: Throwable) { "" },
            stage = fit?.stage?.name.orEmpty(),
            priceAgeMs = priceAge,
            missingInputs = (try { pb.dataIncomplete8136(ts, lane, nowMs).orEmpty() } catch (_: Throwable) { emptyList() }) +
                (if (liquidityMissing8153) listOf("liquidity:UNKNOWN") else emptyList()),
            // V5.0.8153 — the selected strategy's expected executable move (NetEdge8030.executableMove8153), as the gate judges it.
            expectedGrossPct = try { NetEdge8030.expectedGrossFor8153(ts, lane, selected, cost8153) } catch (_: Throwable) { Double.NaN },
            costPct = cost8153,
            costSizeSol = costRec8209?.sizeSol ?: Double.NaN,
            costBreakdown = costRec8209?.breakdown.orEmpty(),
            downsidePct = try { com.lifecyclebot.engine.cortex.StopAuthority7887.laneStopMag8029(lane) } catch (_: Throwable) { Double.NaN },
            expectedGrossBySetup = (options8153.map { it.id } + listOfNotNull(selected)).distinct().associateWith { setup ->
                try { NetEdge8030.expectedGrossFor8153(ts, lane, setup, cost8153) } catch (_: Throwable) { Double.NaN }
            },
            setups = setups,
            cell = cell,
            timing = timing,
            stageRecord = stageRecord,
            losingTactic = try { com.lifecyclebot.engine.learning.TacticSwitcher.losingTacticFor8094(ts, lane) } catch (_: Throwable) { null },
            hotLaunch = launch != null && launch.verdict != FreshLaunchSelector7737.LaunchVerdict.NOT_FRESH,
            safetyRefusal = safety,
            structuralRisk = if (fit?.stage == com.lifecyclebot.engine.TokenMetricStageRouter.Stage.RUG_PRONE) "STAGE_RUG_PRONE"
                else launch?.takeIf { it.verdict == FreshLaunchSelector7737.LaunchVerdict.REFUSED }?.why,
            setupTimings = setupTimings,
            lossEpochAware8169 = true,
            live = try { !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false },
            cellLoss8169 = cellLoss8169,
            stageLoss8169 = stageLoss8169,
            cortexStrong8200 = try { com.lifecyclebot.engine.cortex.Cortex7885.strongCohortEvidence8200(ts, lane) } catch (_: Throwable) { null },
            runnerGrab8245 = try {
                com.lifecyclebot.engine.RunnerGrab7967.grabEvidence8245(ts, nowMs)?.let { Evidence8136(KIND_RUNNER_GRAB_8245, it.key, it.n, it.meanNetPct, it.sePct) }
            } catch (_: Throwable) { null },
            nativeQualified8213 = nativeOpinion8213 != null || nativeSetupBook8217 != null,
            nativeSetup8213 = nativeOpinion8213?.setup?.takeIf { it.isNotBlank() && !it.equals("NONE", true) } ?: nativeSetupBook8217,
        )
    }
}
