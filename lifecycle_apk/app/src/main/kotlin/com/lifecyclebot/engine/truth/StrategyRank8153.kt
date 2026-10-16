package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8153 §BEST_EXECUTABLE_STRATEGY_PER_TOKEN.
 *
 * LanePlaybook7907.selectedSetup8094 took the best PROVEN fired setup, else a valid alternative only when the classifier's
 * default was negative, else the default. A fired setup clearing the cold-start bar on its own record lost to an unproven
 * default; a proven setup whose record AT THE COIN'S CURRENT TIMING BAND was losing was still chosen (and then refused
 * as TIMING_RECORD_NEGATIVE while another fired setup paid in that band); and CandidateAdmission8136, the playbook's live
 * refusal, the entry tag, the tactic and the size book each re-derived "the setup" on their own (some from the default).
 *
 * One ranker now judges every fired setup this coin shows in the lane, on its OWN record:
 *   REJECTED   losing on its labels (12+, mean + SE < 0) or losing at the coin's current timing band (12+, mean + SE < 0)
 *   SUPPORTED  30+ labels and mean - SE above the bar (0 for labels already net of cost, else the measured round trip),
 *              or the runner-tail test (100+ labels, a positive mean over the bar, 10%+ running) — CandidateAdmission8136
 *   VALID      12+ labels and mean - 1.5 SE above the bar (the cold-start margin, LanePlaybook7907.coldStartPositive8080)
 *   UNPROVEN   anything else (missing evidence is never edge)
 * ranked by tier, then the conservative executable net (the lower of mean - SE and its timing band's mean - SE, net of
 * cost), then the runner rate, then the sample. The best SUPPORTED / VALID setup is the strategy; with none, the
 * classifier's default stands (and so do its own refusals). The ranker grants nothing by itself: every live gate, the
 * Cortex's walk-forward / realised authority and the hard safety checks still apply to the chosen strategy.
 */
object StrategyRank8153 {

    enum class Tier8153 { SUPPORTED, VALID, UNPROVEN, REJECTED }

    /** One fired setup's own record ([n], mean, SE %) and its record at the coin's current timing band ([timingN] = 0: none). */
    data class Option8153(
        val id: String,
        val n: Int,
        val meanPct: Double,
        val sePct: Double,
        val runnerRate: Double = Double.NaN,
        val netOfCost: Boolean = true,
        val timingN: Int = 0,
        val timingMeanPct: Double = Double.NaN,
        val timingSePct: Double = Double.NaN,
    ) {
        val lowerPct: Double get() = if (meanPct.isFinite() && sePct.isFinite()) meanPct - sePct else Double.NaN
        val upperPct: Double get() = if (meanPct.isFinite() && sePct.isFinite()) meanPct + sePct else Double.NaN
        val compact: String get() = "$id n$n ${fmt8153(meanPct)}±${fmt8153(sePct)}" +
            (if (timingN > 0) " band n$timingN ${fmt8153(timingMeanPct)}" else "")
    }

    /** One option with its tier, conservative executable net (%) and the rule that placed it. */
    data class Ranked8153(val option: Option8153, val tier: Tier8153, val scorePct: Double, val why: String)

    /** The strategy for one token: [id] (null when nothing could be classified), its tier and evidence basis. */
    data class Pick8153(val id: String?, val tier: Tier8153, val basis: String, val overDefault: Boolean, val rejected: List<String>)

    internal const val NO_TRIGGER_8153 = "NO_TRIGGER"
    internal const val LOSING_MIN_N_8153 = 12
    internal const val VALID_MIN_N_8153 = 12
    internal const val VALID_SE_MULT_8153 = 1.5
    internal const val SUPPORT_MIN_N_8153 = 30
    internal const val TAIL_MIN_N_8153 = 100
    internal const val TAIL_MIN_RATE_8153 = 0.10

    internal fun fmt8153(v: Double): String = if (v.isFinite()) "%+.1f".format(v) else "-"

    /** Pure: the bar an option's lower bound must clear — 0 for labels net of cost, else the round trip (NaN: unknown). */
    internal fun bar8153(o: Option8153, costPct: Double): Double =
        if (o.netOfCost) 0.0 else if (costPct.isFinite() && costPct > 0.0) costPct else Double.NaN

    /** Pure: losing on its own labels — 12+ and mean + SE below zero. */
    internal fun losing8153(o: Option8153): Boolean = o.n >= LOSING_MIN_N_8153 && o.upperPct.isFinite() && o.upperPct < 0.0

    /** Pure: losing at the coin's current timing band — 12+ labels in that band and mean + SE below zero. */
    internal fun timingLosing8153(o: Option8153): Boolean =
        o.timingN >= LOSING_MIN_N_8153 && o.timingMeanPct.isFinite() && o.timingSePct.isFinite() && o.timingMeanPct + o.timingSePct < 0.0

    /** Pure: the option's tier (see the object doc). */
    fun tier8153(o: Option8153, costPct: Double): Tier8153 {
        if (losing8153(o) || timingLosing8153(o)) return Tier8153.REJECTED
        val bar = bar8153(o, costPct)
        if (!bar.isFinite() || !o.meanPct.isFinite()) return Tier8153.UNPROVEN
        val lower = o.lowerPct
        if (o.n >= SUPPORT_MIN_N_8153 && lower.isFinite()) {
            if (lower > bar) return Tier8153.SUPPORTED
            if (o.n >= TAIL_MIN_N_8153 && o.meanPct > bar && o.runnerRate.isFinite() && o.runnerRate >= TAIL_MIN_RATE_8153) return Tier8153.SUPPORTED
        }
        if (o.n >= VALID_MIN_N_8153 && o.sePct.isFinite() && o.meanPct - VALID_SE_MULT_8153 * o.sePct > bar) return Tier8153.VALID
        return Tier8153.UNPROVEN
    }

    /**
     * Pure: the conservative executable net (%) — mean - SE net of cost, capped by the timing band's mean - SE when that
     * band is measured (12+). NaN when the record has no finite lower bound.
     */
    fun score8153(o: Option8153, costPct: Double): Double {
        val adj = if (o.netOfCost) 0.0 else if (costPct.isFinite() && costPct > 0.0) costPct else return Double.NaN
        val lower = o.lowerPct
        if (!lower.isFinite()) return Double.NaN
        val band = if (o.timingN >= LOSING_MIN_N_8153 && o.timingMeanPct.isFinite() && o.timingSePct.isFinite())
            o.timingMeanPct - o.timingSePct else Double.POSITIVE_INFINITY
        return minOf(lower, band) - adj
    }

    private fun why8153(o: Option8153, t: Tier8153): String = when {
        t != Tier8153.REJECTED -> t.name
        losing8153(o) -> "LOSING"
        else -> "TIMING_LOSING"
    }

    /** Pure: every option (NO_TRIGGER and blank ids excluded) ranked — tier, conservative net, runner rate, sample. */
    fun rank8153(options: List<Option8153>, costPct: Double): List<Ranked8153> =
        options.filter { it.id.isNotBlank() && it.id != NO_TRIGGER_8153 }.map { o ->
            val t = tier8153(o, costPct)
            Ranked8153(o, t, score8153(o, costPct), why8153(o, t))
        }.sortedWith(
            compareBy<Ranked8153>({ it.tier.ordinal }, { if (it.scorePct.isFinite()) -it.scorePct else Double.MAX_VALUE },
                { if (it.option.runnerRate.isFinite()) -it.option.runnerRate else 0.0 }, { -it.option.n }),
        )

    /** Pure: the best SUPPORTED option (CandidateAdmission8136's specific edge among setups), or null. */
    fun bestSupported8153(options: List<Option8153>, costPct: Double): Option8153? =
        rank8153(options, costPct).firstOrNull { it.tier == Tier8153.SUPPORTED }?.option

    /** Pure: the strategy for this token — the best SUPPORTED / VALID fired setup, else the classifier's [defaultId]. */
    fun select8153(defaultId: String?, options: List<Option8153>, costPct: Double): Pick8153 {
        val ranked = rank8153(options, costPct)
        val rejected = ranked.filter { it.tier == Tier8153.REJECTED }.map { "${it.option.id}:${it.why}" }
        val best = ranked.firstOrNull { it.tier == Tier8153.SUPPORTED || it.tier == Tier8153.VALID }
        if (best != null) {
            return Pick8153(best.option.id, best.tier, "${best.tier.name}:${best.option.compact}", best.option.id != defaultId, rejected)
        }
        val def = defaultId?.takeIf { it.isNotBlank() } ?: return Pick8153(null, Tier8153.UNPROVEN, "NONE", false, rejected)
        val defTier = ranked.firstOrNull { it.option.id == def }?.tier ?: Tier8153.UNPROVEN
        return Pick8153(def, defTier, "DEFAULT_${defTier.name}", false, rejected)
    }

    /** Pure: may this tier carry the token's identity over the classifier default? */
    fun evidenced8153(t: Tier8153?): Boolean = t == Tier8153.SUPPORTED || t == Tier8153.VALID
}

/**
 * V5.0.8153 §ONE_IDENTITY_END_TO_END — the strategy CandidateAdmission8136.assess8136 decided for (mint, lane), held for
 * [TTL_MS_8153]. LanePlaybook7907.selectedSetup8094 answers with it first, so the live edge gate, the playbook refusals,
 * the tactic (TacticSwitcher.tacticForToken8050) and its entry / exit style (AgenticStyleRouter -> TradeExitContract8077),
 * the size books (Cortex conviction, StrategyHypothesisEngine, LiveSizeTarget8094) and the sealed entry identity
 * (entryTag8091 -> StrategyHypothesisEngine -> AateEntryIdentity8136 / EntryStrategySnapshot6450) all read the same id.
 * A held identity counts only while that setup is still among the coin's fired setups.
 */
object StrategyIdentity8153 {
    data class Held8153(val setup: String, val basis: String, val atMs: Long)

    const val TTL_MS_8153 = 90_000L
    private const val MAX_8153 = 4_000

    private val held = ConcurrentHashMap<String, Held8153>()

    private fun key8153(mint: String, lane: String) = "$mint|${lane.trim().uppercase()}"

    /** Pure: is [h] still this coin's identity at [nowMs] (fresh, and the setup still fired)? */
    fun valid8153(h: Held8153?, nowMs: Long, fired: Collection<String>): Boolean =
        h != null && nowMs - h.atMs in 0L..TTL_MS_8153 && h.setup in fired

    /** CandidateAdmission8136.assess8136: the admission's chosen strategy for (mint, lane). */
    fun record8153(mint: String, lane: String, setup: String?, basis: String, nowMs: Long) {
        if (mint.isBlank() || lane.isBlank() || setup.isNullOrBlank()) return
        held[key8153(mint, lane)] = Held8153(setup, basis, nowMs)
        if (held.size > MAX_8153) held.entries.removeIf { nowMs - it.value.atMs > TTL_MS_8153 }
    }

    /** The held strategy for (mint, lane) when [valid8153], else null. */
    fun heldFor8153(mint: String, lane: String, nowMs: Long, fired: Collection<String>): String? =
        held[key8153(mint, lane)]?.takeIf { valid8153(it, nowMs, fired) }?.setup

    /** The held strategy's evidence basis ("" when none is fresh), for decision traces. */
    fun basisFor8153(mint: String, lane: String, nowMs: Long): String =
        held[key8153(mint, lane)]?.takeIf { nowMs - it.atMs in 0L..TTL_MS_8153 }?.let { "${it.setup}(${it.basis.take(48)})" }.orEmpty()
}
