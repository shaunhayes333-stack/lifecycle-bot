package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8243 §THE_STRATEGY_WITH_THE_PROVEN_FORWARD_EDGE_OWNS_THE_TRADE.
 *
 * 8241 runner ledger: ZFROG +129,601% — natives MOONSHOT:Y:80, SHITCOIN:Y:37; CandidateAdmission8136 said
 * ADMIT:SPECIFIC_EDGE_PROVEN @SHITCOIN (IGNITION_5M n216 +15.8±10.2, SUPPORTED); the election handed the version to
 * MOONSHOT on native conviction (80 > 37), MOONSHOT's own admission was DATA_INCOMPLETE_DEFER, the FDG refused, and the
 * hand-off expired. 62 live ADMITs this session, 0 FDG allows: the lane that owned the candidate was never the lane
 * whose strategy had the proven forward edge.
 *
 * Every natively qualified lane is appraised with the SAME candidate-specific admission record it will face at the
 * FDG (CandidateAdmission8136: safety > specific negative > specific positive > lane priors). The owner election then
 * ranks in tiers, conviction only breaking ties inside a tier:
 *   1. lanes whose admission is a candidate-specific ADMIT (a SUPPORTED setup / cell / validated Cortex read clearing
 *      the round trip) — the best conservative net of cost first;
 *   2. lanes not yet judged (data deferral, generic prior, no record);
 *   3. lanes whose admission is a candidate-specific REFUSE — they would only end the version as FDG_REFUSED.
 * Nothing is bypassed: the elected lane still runs the full FDG, the live edge gate, sizing, risk and the buy door.
 * Pure ranking in [ownerOrder8243] / [bestStrategy8243]; the admission read is memoised per candidate generation.
 */
object StrategyElection8243 {

    /** One lane's candidate-specific admission read for the election. */
    data class Verdict8243(val lane: String, val terminal: CandidateAdmission8136.Terminal8136?, val specific: Boolean,
                           val netPct: Double, val setup: String?, val reason: String) {
        val provenAdmit: Boolean get() = terminal == CandidateAdmission8136.Terminal8136.ADMIT && specific
        val specificRefuse: Boolean get() = terminal == CandidateAdmission8136.Terminal8136.REFUSE && specific
    }

    private const val TIER_ADMIT = 0
    private const val TIER_UNJUDGED = 1
    private const val TIER_REFUSE = 2
    internal const val MEMO_MS_8243 = 5_000L
    private const val MAX_MEMO_8243 = 4_000

    private class Memo8243(val atMs: Long, val verdict: Verdict8243?)
    private val memos8243 = ConcurrentHashMap<String, Memo8243>()

    /** Pure: the election tier of [v] (null = no read = unjudged). */
    fun tier8243(v: Verdict8243?): Int = when {
        v == null -> TIER_UNJUDGED
        v.provenAdmit -> TIER_ADMIT
        v.specificRefuse -> TIER_REFUSE
        else -> TIER_UNJUDGED
    }

    /** Pure: the lane that owns the candidate — best tier, then (ADMIT tier) best net of cost, then conviction, then name. */
    fun ownerOrder8243(scores: Map<String, Double>, verdicts: Map<String, Verdict8243?>): String? {
        if (scores.isEmpty()) return null
        fun v(l: String) = verdicts[l] ?: verdicts[l.uppercase()]
        fun net(l: String): Double = v(l)?.netPct?.takeIf { it.isFinite() } ?: Double.NEGATIVE_INFINITY
        return scores.keys.sortedWith(
            compareBy<String> { tier8243(v(it)) }
                .thenByDescending { if (tier8243(v(it)) == TIER_ADMIT) net(it) else 0.0 }
                .thenByDescending { scores[it] ?: Double.NEGATIVE_INFINITY }
                .thenBy { it },
        ).firstOrNull()
    }

    /** Pure: the best proven strategy among [verdicts] (highest finite net of cost), or null when no lane admits. */
    fun bestStrategy8243(verdicts: Collection<Verdict8243?>): Verdict8243? =
        verdicts.filterNotNull().filter { it.provenAdmit }
            .sortedWith(compareByDescending<Verdict8243> { it.netPct.takeIf { n -> n.isFinite() } ?: Double.NEGATIVE_INFINITY }.thenBy { it.lane })
            .firstOrNull()

    /** Pure: did the strategy election move ownership away from the conviction winner? */
    fun electedOverConviction8243(owner: String?, convictionWinner: String?): Boolean =
        owner != null && convictionWinner != null && !owner.equals(convictionWinner, true)

    /** The lane's candidate-specific admission read for this generation (memoised [MEMO_MS_8243]); null = no read. */
    private fun verdictFor8243(ts: TokenState, lane: String, candidateVersion: Long, nowMs: Long): Verdict8243? {
        val l = lane.trim().uppercase()
        val key = "${ts.mint}|$l|$candidateVersion"
        memos8243[key]?.takeIf { nowMs - it.atMs in 0L..MEMO_MS_8243 }?.let { return it.verdict }
        val d = try { CandidateAdmission8136.assess8136(ts, l, nowMs, candidateVersion) } catch (_: Throwable) { null }
        val v = d?.let { Verdict8243(l, it.terminal, it.specific, it.expectedNetPct, it.chosenSetup, it.reason) }
        if (memos8243.size > MAX_MEMO_8243) memos8243.entries.removeIf { nowMs - it.value.atMs > MEMO_MS_8243 }
        memos8243[key] = Memo8243(nowMs, v)
        if (v?.provenAdmit == true) try { com.lifecyclebot.engine.SpecialistElection8136.noteStrategyAdmit8243(ts.mint, l) } catch (_: Throwable) {}
        return v
    }

    /** The election's verdict map for [lanes] (one memoised admission read per lane). */
    fun verdictsFor8243(ts: TokenState, lanes: Collection<String>, candidateVersion: Long, nowMs: Long = System.currentTimeMillis()): Map<String, Verdict8243?> =
        lanes.associate { it.uppercase() to verdictFor8243(ts, it, candidateVersion, nowMs) }

    /** Counts a strategy-elected owner (and the demoted conviction winner) for the pipeline health report. */
    fun noteElection8243(owner: String?, convictionWinner: String?, verdicts: Map<String, Verdict8243?>) {
        try {
            if (electedOverConviction8243(owner, convictionWinner)) {
                PipelineHealthCollector.labelInc("STRATEGY_ELECTED_OVER_CONVICTION_8243")
                PipelineHealthCollector.labelInc("STRATEGY_ELECTED_OVER_CONVICTION_8243_${owner}_OVER_$convictionWinner")
                if (verdicts[convictionWinner?.uppercase()]?.specificRefuse == true) PipelineHealthCollector.labelInc("STRATEGY_REFUSING_LANE_DEMOTED_8243_$convictionWinner")
            }
            if (owner != null && verdicts[owner.uppercase()]?.provenAdmit == true) PipelineHealthCollector.labelInc("STRATEGY_OWNER_PROVEN_ADMIT_8243_$owner")
        } catch (_: Throwable) {}
    }

    fun statusLine8243(): String = "memo=${memos8243.size}"
}
