package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8326 — the 8114 live entry decision (LiveEdgeGate7877.liveRefusal at 379ad2fa0), ported in its exact order:
 *   dead launch -> blind wallet -> live-close setup refusal -> timing record -> picked-setup record -> tactic record ->
 *   proven-only door (proven setup / measured edge / runner ticket / runner forming / shadow audit / bounded cold start /
 *   audited Cortex) -> spike chase -> watch first -> core (Cortex earned refusal, rebuy lockout, NetEdge 1.5x, lane fit,
 *   proven-losing setup, chart BUY, Mayhem, specialist, playbook / stage / cost priors, shape bins, cohort verdict).
 * No CandidateAdmission8136, no data deferrals (8136/8144/8204/8229/8297), no 8290 proof, 8298 restore or 8310 doors.
 * Called by Entry8114Mode8326 inside its legacy scope, so the evidence reads that changed since 8114 answer the 8114 way
 * (ForwardReturnLabeler7731 raw cells, LanePlaybook7907 raw setup records and 8114 setup selection, no 8290 shortfall,
 * Cortex7885.entryRefusal8114, RebuyLockout8019 without the 8130 additions, RunnerTicket8073 without the 8310 suspension).
 * It files nothing itself: Entry8114Mode8326 files the final assessment once.
 */
object Entry8114Gate8326 {
    data class Verdict8326(val reason: String?, val kind: LiveEdgeGate7877.Verdict8083, val setup: String?)

    private val provenOnlyRefused8326 = AtomicLong(0)
    private val admitted8326 = AtomicLong(0)
    private val watched8326 = AtomicLong(0)
    private val chase8326 = AtomicLong(0)

    private fun canon(lane: String): String =
        CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }

    fun liveVerdict8114(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Verdict8326 {
        var auditedAdmission8076 = false
        try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) {}
        try { OnChainDepth8038.request8038(ts) } catch (_: Throwable) {}
        try { OnChainDepth8038.standDownIfTailProven8094(ts, lane, nowMs) } catch (_: Throwable) {}
        (try { OnChainDepth8038.earlyLaunchRefusal8048(ts, nowMs) } catch (_: Throwable) { null })?.let {
            return Verdict8326(it, LiveEdgeGate7877.Verdict8083.REFUSE, null)
        }
        val l = canon(lane)
        try { LiveBootRecovery8074.tick8074(nowMs) } catch (_: Throwable) {}
        // V5.0.8339 — the wallet door binds a LIVE runtime only: a paper (rehearsal) book has no wallet to be blind about.
        if (Rehearsal8339.walletDoorBindsNow8339()) (try { LiveBootRecovery8074.blind8074(nowMs) } catch (_: Throwable) { null })?.let {
            return Verdict8326(it, LiveEdgeGate7877.Verdict8083.REFUSE, null)
        }
        val setup = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(ts, l, nowMs) } catch (_: Throwable) { null }
        (try { LiveTruth8064.setupRefusal8083(l, setup, nowMs) } catch (_: Throwable) { null })?.let {
            return Verdict8326(it, LiveEdgeGate7877.Verdict8083.REFUSE, setup)
        }
        // V5.0.8342 — the label / record doors below are ADVISORY for a PAPER entry under rehearsal (Rehearsal8339.doorBindsNow8342:
        // recorded as the live verdict, the decision continues); they bind LIVE as before. Hard safety, chase and economics bind in both.
        val timing8342 = if (!cellOrKindProven8114(ts, l, nowMs)) (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.timingNegative8094(ts, l, nowMs) } catch (_: Throwable) { null }) else null
        if (timing8342 != null && Rehearsal8339.doorBindsNow8342("TIMING_RECORD_NEGATIVE_8094", l)) {
            return Verdict8326("TIMING_RECORD_NEGATIVE_8094", LiveEdgeGate7877.Verdict8083.DEFER, setup)
        }
        val proven8059 = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.provenSetup8056(ts, l, nowMs) } catch (_: Throwable) { null }
        val picked8342 = if (proven8059 == null && !cellOrKindProven8114(ts, l, nowMs)) (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.pickedSetupNegative8094(ts, l, nowMs) } catch (_: Throwable) { null }) else null
        if (picked8342 != null && Rehearsal8339.doorBindsNow8342("PICKED_SETUP_RECORD_NEGATIVE_8094", l)) {
            return Verdict8326("PICKED_SETUP_RECORD_NEGATIVE_8094", LiveEdgeGate7877.Verdict8083.REFUSE, setup)
        }
        val tactic8342 = if (proven8059 == null && !cellOrKindProven8114(ts, l, nowMs)) (try { com.lifecyclebot.engine.learning.TacticSwitcher.losingTacticFor8094(ts, l) } catch (_: Throwable) { null }) else null
        if (tactic8342 != null && Rehearsal8339.doorBindsNow8342("TACTIC_RECORD_LOSING_8094", l)) {
            return Verdict8326("TACTIC_RECORD_LOSING_8094", LiveEdgeGate7877.Verdict8083.REFUSE, setup)
        }
        if (proven8059 == null && !measuredEdge8114(ts, l, nowMs) &&
            !(try { com.lifecyclebot.engine.RunnerTicket8073.admits8073(ts, nowMs) } catch (_: Throwable) { false }) &&
            !runnerForming8114(ts, nowMs) && !shadowAuditAdmits8114(ts, l) &&
            !coldStartAdmits8114(ts, l, nowMs)) {
            auditedAdmission8076 = try {
                com.lifecyclebot.engine.cortex.Cortex7885.ownLaneVetoRefusesWinners8076("LIVE_PROVEN_SETUP_ONLY_8059", l) ||
                    com.lifecyclebot.engine.cortex.Cortex7885.overrulesEdgeRefusal(ts, l, "LIVE_PROVEN_SETUP_ONLY_8059")
            } catch (_: Throwable) { false }
            if (!auditedAdmission8076 && Rehearsal8339.doorBindsNow8342("LIVE_PROVEN_SETUP_ONLY_8059", l)) {
                provenOnlyRefused8326.incrementAndGet()
                return Verdict8326("LIVE_PROVEN_SETUP_ONLY_8059", LiveEdgeGate7877.Verdict8083.DEFER, setup)
            }
        }
        // 8114: the spike rule stood down for a forming run (8113), a tail-proven rule (8097) or a proven cell / kind (8094).
        val snap = try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts) } catch (_: Throwable) { null }
        // V5.0.8337 (6b) — only an EARLY run (8117) that is also a grab is exempt from the chase rule: a coin that already ran is not bought.
        if (snap != null && LiveEdgeGate7877.chasing8065(snap.runupFromLocalLowPct, snap.currentVsPeak, ts.lastPriceChange5m) &&
            !(LiveEdgeGate7877.earlyRun8117(snap.runupFromLocalLowPct, ts.lastPriceChange5m) && (try { com.lifecyclebot.engine.RunnerGrab7967.grab7967(ts.mint, nowMs) } catch (_: Throwable) { false })) &&
            !LiveEdgeGate7877.spikeRuleRefusesRunners8097(ts, l, nowMs) &&
            !cellOrKindProven8114(ts, l, nowMs)) {
            chase8326.incrementAndGet()
            return Verdict8326("CHASING_SPIKE_8065", LiveEdgeGate7877.Verdict8083.REFUSE, setup)
        }
        val watch8342 = watchFirst8114(ts, lane, nowMs, auditedAdmission8076)
        if (watch8342 != null && Rehearsal8339.doorBindsNow8342(watch8342, l)) {   // V5.0.8342 — WATCH_FIRST_* advisory in paper
            watched8326.incrementAndGet()
            return Verdict8326(watch8342, LiveEdgeGate7877.Verdict8083.REFUSE, setup)
        }
        val why = core8114(ts, lane, nowMs)
        if (why == null) admitted8326.incrementAndGet()
        return Verdict8326(why, if (why == null) LiveEdgeGate7877.Verdict8083.ADMIT else LiveEdgeGate7877.Verdict8083.REFUSE, setup)
    }

    /** 8114: the token's own cell (30+ labels, mean - SE > 0, or the Wilson win-rate proof) or its kind is proven. */
    private fun cellOrKindProven8114(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        val st = ForwardReturnLabeler7731.cellStatFor(ts, lane, nowMs)
        (st != null && (LiveEdgeGate7877.cellProven8060(st.n60, st.meanNet60Pct, st.stderr60Pct) ||
            LiveEdgeGate7877.cellWinsProven8094(st.n60, st.winRate60, st.meanNet60Pct))) || kindProven8114(ts, lane)
    } catch (_: Throwable) { false }

    private fun kindProven8114(ts: TokenState, lane: String): Boolean =
        try { ForwardReturnLabeler7731.kindStatsFor8063(ts, lane).any { LiveEdgeGate7877.cellProven8060(it.n60, it.meanNet60Pct, it.stderr60Pct) } } catch (_: Throwable) { false }

    /** V5.0.8337 (6a) — only a grab counts as a forming run (8115/8117 narrowed this); runner tickets never exempt. */
    private fun runnerForming8114(ts: TokenState, nowMs: Long) = try { com.lifecyclebot.engine.RunnerGrab7967.grab7967(ts.mint, nowMs) } catch (_: Throwable) { false }

    private fun shadowAuditAdmits8114(ts: TokenState, lane: String): Boolean = try {
        com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.entry(lane, ts.mint)?.state == com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.State.READY &&
            com.lifecyclebot.engine.SpecialistOwnership7951.shadowRefusesWinners8094(lane)
    } catch (_: Throwable) { false }

    private fun measuredEdge8114(ts: TokenState, lane: String, nowMs: Long): Boolean {
        val st = try { ForwardReturnLabeler7731.cellStatFor(ts, lane, nowMs) } catch (_: Throwable) { null }
        return (st != null && LiveEdgeGate7877.cellProven8060(st.n60, st.meanNet60Pct, st.stderr60Pct)) ||
            kindProven8114(ts, lane) ||
            (try { com.lifecyclebot.engine.RunnerGrab7967.cellBeatsLane7970(ts, lane, nowMs) } catch (_: Throwable) { false }) ||
            (try { SpecialistMiner7972.match7972(ts, lane, nowMs) } catch (_: Throwable) { null }) != null ||
            (try {
                com.lifecyclebot.engine.ExpertWallets7962.expertEntryLive7962(ts.mint, nowMs) &&
                    SignalSourceProof7291.copyEdgeAtGap8062(NetEdge8030.costPct8030(lane, NetEdge8030.DEFAULT_TICKET_SOL, ts.lastLiquidityUsd) / 100.0)
            } catch (_: Throwable) { false })
    }

    /** 8114 bounded cold start: the same 3-coin / 6 h budget LiveEdgeGate7877 keeps (confirmed + in-flight). */
    private fun coldStartAdmits8114(ts: TokenState, lane: String, nowMs: Long): Boolean {
        (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.coldStartSetup8080(ts, lane, nowMs) } catch (_: Throwable) { null }) ?: return false
        val admits = LiveEdgeGate7877.coldAdmits8080
        synchronized(admits) {
            admits.entries.removeIf { nowMs - it.value >= LiveEdgeGate7877.COLD_WINDOW_MS_8080 }
            LiveEdgeGate7877.coldPendingSweep8092(nowMs)
            if (!LiveEdgeGate7877.coldBudgetOpen8094(admits, LiveEdgeGate7877.coldPending8092, ts.mint, nowMs)) return false
            LiveEdgeGate7877.coldPending8092.putIfAbsent(ts.mint, nowMs)
        }
        try { PipelineHealthCollector.labelInc("ENTRY8114_COLD_START_ADMIT_8326_$lane") } catch (_: Throwable) {}
        return true
    }

    private const val WATCH_MIN_N_8114 = 10

    /** 8114 watch first (no 8153 release, no 8290 shortfall: the cell is read raw inside the legacy scope). */
    private fun watchFirst8114(ts: TokenState, lane: String, nowMs: Long, auditedAdmission8076: Boolean): String? {
        // V5.0.8337 (6c) — a RunnerTicket8073 / RunnerPlay8018 ticket is no admit evidence here: a coin that already ran is watched.
        val st = try { ForwardReturnLabeler7731.cellStatFor(ts, lane, nowMs) } catch (_: Throwable) { null }
        if (st != null && LiveEdgeGate7877.cellEarnsLive7994(st.n60, st.meanNet60Pct)) {
            if (LiveEdgeGate7877.cellSkipsPlanWait7995(st.n60, st.meanNet60Pct)) LiveEdgeGate7877.notePositive7975(ts.mint, nowMs)
            return null
        }
        val l = canon(lane)
        if ((try { SpecialistMiner7972.match7972(ts, l, nowMs) } catch (_: Throwable) { null }) != null) return null
        val proven8056 = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.provenSetup8056(ts, l, nowMs) } catch (_: Throwable) { null }
        if (proven8056 != null) {
            LiveEdgeGate7877.notePositive7975(ts.mint, nowMs)
            if (proven8056.second) try { com.lifecyclebot.engine.RunnerGrab7967.holdAsRunner7972(ts, nowMs) } catch (_: Throwable) {}
            return null
        }
        if (try { TailHunter7996.ticket7996(ts, l, nowMs) } catch (_: Throwable) { false }) {
            LiveEdgeGate7877.notePositive7975(ts.mint, nowMs); return null
        }
        val band = if (st == null || st.n60 < WATCH_MIN_N_8114) try { ForwardReturnLabeler7731.bandStatFor8019(ts, l) } catch (_: Throwable) { null } else null
        val bandNegative = band != null && LiveEdgeGate7877.bandLoses8019(band.n60, band.meanNet60Pct, band.stderr60Pct)
        val why = when {
            bandNegative -> "WATCH_FIRST_8019_BAND_NEGATIVE"
            st == null || st.n60 < WATCH_MIN_N_8114 -> "WATCH_FIRST_7994_THIN_CELL"
            else -> "WATCH_FIRST_7994_CELL_NEGATIVE"
        }
        if (auditedAdmission8076 && why == "WATCH_FIRST_7994_THIN_CELL") {
            LiveEdgeGate7877.notePositive7975(ts.mint, nowMs); return null
        }
        if (try { com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners8006(why, l) } catch (_: Throwable) { false }) return null
        if (try { com.lifecyclebot.engine.cortex.Cortex7885.overrulesEdgeRefusal(ts, l, why) } catch (_: Throwable) { false }) {
            LiveEdgeGate7877.notePositive7975(ts.mint, nowMs); return null
        }
        return why
    }

    /** 8114 liveRefusalCore7970 (paper never reaches here). */
    private fun core8114(ts: TokenState, lane: String, nowMs: Long): String? {
        val l = canon(lane)
        val cortex = try { com.lifecyclebot.engine.cortex.Cortex7885.entryRefusal8114(ts, lane) } catch (_: Throwable) { null }
        // V5.0.8342 — REPEAT_LOSER_8022 (a label-book door) is advisory for a paper entry under rehearsal; the lockouts bind.
        val lockout8342 = com.lifecyclebot.engine.RebuyLockout8019.refusal8019(ts.mint, nowMs)
        if (lockout8342 != null && Rehearsal8339.doorBindsNow8342(lockout8342, l)) return lockout8342
        // Economics bind in every mode (owner's 1.5x). A sparse-history DEFER (8153) did not exist at 8114: not a refusal.
        NetEdge8030.entryRefusal8030(ts, lane)?.let { r ->
            if (r == StrategyMove8153.DEFER_REASON_8153) Entry8114Mode8326.noteBypass8326("NET_EDGE_MOVE_HISTORY_DEFER_8153", lane) else return r
        }
        NetEdge8030.laneFitRefusal8054(ts, CanonicalLaneIdentity6506.canonical(lane).ifBlank { lane })?.let { return it }
        // V5.0.8342 — the Cortex C6 setup-dead door is advisory in paper under rehearsal; an earned Cortex refusal binds.
        if (cortex != null && Rehearsal8339.doorBindsNow8342(cortex, l)) return cortex
        val losing8342 = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.provenLosingSetupRefusal8079(ts, l, nowMs) } catch (_: Throwable) { null }
        if (losing8342 != null) {
            val retired = try { com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners8006(losing8342, l) } catch (_: Throwable) { false }
            val full = !retired && (try { com.lifecyclebot.engine.cortex.Cortex7885.fullAuthorityOverrules8078(ts, l, losing8342) } catch (_: Throwable) { false })
            if (!retired && !full && Rehearsal8339.doorBindsNow8342(losing8342, l)) return losing8342   // V5.0.8342 — advisory in paper
        }
        if (LiveEdgeGate7877.chartAdmits7950(ts, lane, nowMs)) return null
        com.lifecyclebot.engine.MayhemMode7943.liveRefusal(ts, nowMs)?.let { return it }
        if (ts.safety.tier != com.lifecyclebot.engine.SafetyTier.HARD_BLOCK) {
            SpecialistMiner7972.match7972(ts, l, nowMs)?.let { sp ->
                if (SpecialistMiner7972.runs7972(sp)) try { com.lifecyclebot.engine.RunnerGrab7967.holdAsRunner7972(ts, nowMs) } catch (_: Throwable) {}
                LiveEdgeGate7877.notePositive7975(ts.mint, nowMs)
                return null
            }
        }
        val priors = listOfNotNull(
            com.lifecyclebot.engine.cortex.LanePlaybook7907.liveRefusal(ts, l, nowMs),
            com.lifecyclebot.engine.TokenMetricStageRouter.liveStageRefusal7928(ts, l),
            CostLedger7962.liveRefusal7962(ts, l),
        )
        val cohort by lazy { LiveEdgeGate7877.provenCohort7948(ts, nowMs) }
        for (prior in priors) {
            if (com.lifecyclebot.engine.cortex.Cortex7885.vetoRefusesWinners8006(prior, l)) continue
            if ((LiveEdgeGate7877.priorOnly7930(prior) || prior.contains("_EXPECTED_NEGATIVE_7948")) && measuredOverrules8114(ts, l, prior, nowMs)) continue
            if (prior.startsWith("PLAYBOOK_NO_TRIGGER_PROVEN_LOSING") &&
                com.lifecyclebot.engine.RunnerGrab7967.cellBeatsLane7970(ts, l, nowMs)) { LiveEdgeGate7877.notePositive7975(ts.mint, nowMs); continue }
            if (com.lifecyclebot.engine.cortex.Cortex7885.overrulesEdgeRefusal(ts, l, prior)) { LiveEdgeGate7877.notePositive7975(ts.mint, nowMs); continue }
            val sampleN = refusalSampleN8114(ts, l, prior, nowMs)
            if (sampleN != null && LiveEdgeGate7877.cohortOverrulesSmaller7948(cohort, sampleN)) continue
            if (!Rehearsal8339.doorBindsNow8342(prior, l)) continue   // V5.0.8342 — PLAYBOOK_* / STAGE_PROVEN_LOSING advisory in paper; cost priors bind
            return prior
        }
        val shape = try { TradeShapeLearner7883.shapeRefusal(ts, l) } catch (_: Throwable) { null }
        if (shape != null && com.lifecyclebot.engine.cortex.Cortex7885.overrulesEdgeRefusal(ts, l, shape)) return null
        if (shape != null) {
            try { TradeShapeLearner7883.noteRefusal(l, shape) } catch (_: Throwable) {}
            return "EDGE_7877_$shape"
        }
        val v = LiveEdgeGate7877.verdictFor(ts, l, nowMs)
        if (v.allow) return null
        if (com.lifecyclebot.engine.cortex.Cortex7885.overrulesEdgeRefusal(ts, l, v.why)) return null
        return "EDGE_7877_${v.why}_$l"
    }

    private fun measuredOverrules8114(ts: TokenState, lane: String, why: String, nowMs: Long): Boolean {
        val v = try { LiveEdgeGate7877.verdictFor(ts, lane, nowMs) } catch (_: Throwable) { null }
        return (v != null && v.allow && v.source == LiveEdgeGate7877.Source.CELL) ||
            com.lifecyclebot.engine.cortex.Cortex7885.overrulesEdgeRefusal(ts, lane, why)
    }

    /** 8114 refusalSampleN7948 (without the 8153 pooled-plan exception). */
    private fun refusalSampleN8114(ts: TokenState, lane: String, prior: String, nowMs: Long): Int? {
        if (prior.endsWith("_PEAK_EXHAUSTION") || prior.endsWith("_DUMPING") || prior.contains("RUG")) return null
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

    fun statusLine8326(): String =
        "gate8114[admitted=${admitted8326.get()} provenOnlyRefused=${provenOnlyRefused8326.get()} chase=${chase8326.get()} watched=${watched8326.get()}]"
}
