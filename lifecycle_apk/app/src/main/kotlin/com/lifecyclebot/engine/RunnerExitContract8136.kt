package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.ProtectiveExitClass7807
import com.lifecyclebot.engine.truth.PositionDisplayTruth8078

/**
 * V5.0.8136 — RUNNER EXIT CONTRACT (pure: no Android, no runtime state, no clock).
 *
 * Owner audit of realised runner profit capture:
 *  1. Runner eligibility came from the LANE (TradeExitContract8077.runnerFromEntryLane8104: every live MOONSHOT /
 *     SHITCOIN / EXPRESS / PROJECT_SNIPER buy), so a QUICK_FLIP scalp rode the runner ladder (first rung +100%), had
 *     its soft exits deferred for up to 6 h and lost its pop-fade clock. Eligibility is now the immutable sealed
 *     entry setup ([sealedRunnerStyle8136]) or a PROVEN promotion ([provenPromotion8136]: the chain-verified,
 *     two-feed-corroborated position peak reached +[PROMOTE_PCT_8136]%). The lane only says the lane CAN host one.
 *  2. Exit intents are ranked by one explicit order ([Tier8136]); no hold overlay may suppress tier 1
 *     ([emergency8136]). RunnerGrab7967 deferred REFLEX_LIQ_DRAIN / LP_PULL / CANNOT_SELL / MINT_AUTH exits
 *     because its integrity word list missed them.
 *  3. A capital-recovery tranche sized as cost / gross value (50% at exactly 2x) does not recover cost after fees
 *     and slippage; the executor's verified-proceeds check then refused to mark it and re-sold the remainder.
 *     [recoveryFraction8136] grosses the tranche up for the sell costs and targets only UNRECOVERED capital.
 *  4. Ladders sold fractions of the CURRENT holding independently; [budgetedFraction8136] is the one residual
 *     share budget (runner residual + moonbag) every discretionary partial passes.
 *  5. The moonbag set a 25% tail aside on ANY ordinary exit, losing ones included. [tailFunded8136]: a tail is
 *     kept only when capital is recovered by confirmed proceeds, or the set-aside sale itself recovers it.
 *  6. A capture decided on one price and filled on another is measured ([captureLossPp8136]).
 *
 * [replay8136] replays a price path through these rules (and through the legacy lane rules) so the tests can
 * report what each policy actually banks.
 */
object RunnerExitContract8136 {

    // ── 1. runner eligibility ──────────────────────────────────────────────

    /** The corroborated peak a position must reach before it is promoted to runner by proof. */
    const val PROMOTE_PCT_8136 = 100.0
    /** A sealed style whose planned hold multiple is at least this is an extended-horizon (runner) setup. */
    private const val RUNNER_HOLD_MULT_8136 = 1.25
    /** A peak this large is a broken basis, never a promotion. */
    private const val BROKEN_BASIS_PCT_8136 = 100_000.0

    enum class RunnerBasis8136 { SEALED_SETUP, PROVEN_PROMOTION, NONE }

    /** Pure: is the immutable entry style (sealed at entry) a runner setup? Unknown or blank is not. */
    fun sealedRunnerStyle8136(style: String): Boolean {
        val normalized = style.trim().uppercase()
        if (normalized.isBlank() || normalized == "UNKNOWN") return false
        val selected = AgenticStyleRouter.Style.values().firstOrNull { it.name == normalized } ?: return false
        return TradeExitContract8077.longHoldStyle(normalized) == true || selected.holdMult >= RUNNER_HOLD_MULT_8136
    }

    /** Pure: proof of a run — the chain-verified, corroborated position peak reached +[PROMOTE_PCT_8136]%. */
    fun provenPromotion8136(corroboratedPeakPct: Double): Boolean =
        corroboratedPeakPct.isFinite() && corroboratedPeakPct >= PROMOTE_PCT_8136 && corroboratedPeakPct < BROKEN_BASIS_PCT_8136

    /** Pure: why (if at all) a position in a lane that can host runners rides the runner contract. */
    fun runnerBasis8136(laneHostsRunners: Boolean, sealedStyle: String, corroboratedPeakPct: Double): RunnerBasis8136 = when {
        !laneHostsRunners -> RunnerBasis8136.NONE
        sealedRunnerStyle8136(sealedStyle) -> RunnerBasis8136.SEALED_SETUP
        provenPromotion8136(corroboratedPeakPct) -> RunnerBasis8136.PROVEN_PROMOTION
        else -> RunnerBasis8136.NONE
    }

    /**
     * Pure: the peak a verified mark may ratchet to. A step that more than doubles the multiple past +100% needs the
     * mark within 25% of a two-feed corroborated price (PositionDisplayTruth8078, 8128); otherwise the old peak stays.
     */
    fun ratchetPeak8136(oldPeakPct: Double, candidatePct: Double, markPx: Double, agreedPx: Double?): Double {
        val old = if (oldPeakPct.isFinite()) oldPeakPct else 0.0
        if (!candidatePct.isFinite() || candidatePct <= old) return old
        if (PositionDisplayTruth8078.jumpNeedsCorroboration8128(old, candidatePct) &&
            !PositionDisplayTruth8078.markAgrees8128(markPx, agreedPx)) return old
        return candidatePct
    }

    // ── 2. the exit-authority order ────────────────────────────────────────

    /** Lower rank wins. Tier 1 is never suppressed by any hold overlay. */
    enum class Tier8136(val rank: Int) {
        EMERGENCY(1),            // verified emergency, rug, liquidity, freeze, unexecutable, operator
        HARD_RISK(2),            // hard stops and structural invalidation
        RUNNER_CONTINUATION(3),  // the runner contract's own exits (structure break, peak capture, giveback)
        CAPITAL_RECOVERY(4),     // the stake-back tranche
        PROFIT_LOCK(5),          // fee-aware profit locks, trails, spike / scalp ladder
        RUNNER_MILESTONE(6),     // further runner milestones
        MOONBAG(7),              // the residual tail
        ORDINARY(8),             // clocks, flat culls, anything unnamed
    }

    private val EMERGENCY_WORDS_8136 = listOf("RUG", "LIQUIDITY", "LIQ_DRAIN", "LP_PULL", "LP_REMOVED", "HONEYPOT",
        "FREEZE", "FROZEN", "CANNOT_SELL", "UNSELLABLE", "UNEXECUTABLE", "DEV_DUMP", "DEV_SELL", "DEV_SOLD", "MANUAL",
        "KILL_SWITCH", "WALLET_DRAIN", "EMERGENCY")
    private val HARD_RISK_WORDS_8136 = listOf("HARD_STOP", "STOP_FLOOR", "STRUCTURE_STOP", "INVALIDAT", "BASE_BROKE",
        "STRICT_SL", "CATASTROPH")
    private val CONTINUATION_WORDS_8136 = listOf("RUNNER_STRUCTURE_BREAK", "RUNNER_PEAK_CAPTURE", "RUNNER_FROM_ENTRY_GIVEBACK",
        "LIFECYCLE_STOP_TREND_BREAK", "LIFECYCLE_PROFIT_GIVEBACK", "LIFECYCLE_STOP_UNBANKED_GIVEBACK",
        "GREEN_FLOOR_8363")  // V5.0.8363 — a green hold's floor exit is the runner contract's own protective exit
    private val MILESTONE_WORDS_8136 = listOf("TAIL_MILESTONE", "RUNNER_MILESTONE", "LIFECYCLE_PROFIT_TAKE2", "LIFECYCLE_PROFIT_TAKE3")
    private val PROFIT_WORDS_8136 = listOf("SPIKE_CAPTURE", "TAKE_PROFIT", "CHART_CAPTURE", "LOCK", "TRAIL", "HARVEST", "PEAK")

    /** Pure: the tier of an exit reason. */
    fun tierOf8136(reason: String?): Tier8136 {
        val r = reason.orEmpty().uppercase()
        if (r.isBlank()) return Tier8136.ORDINARY
        // The early-rug BACKSTOP is a soft -10% tick exit, not rug evidence (RunnerGrab7967, V5.0.7969).
        if (r.contains("EARLY_RUG_BACKSTOP")) return Tier8136.HARD_RISK
        val p = ProtectiveExitClass7807.of(r)
        if (p.rank <= ProtectiveExitClass7807.Priority.STRUCTURAL_EMERGENCY.rank || EMERGENCY_WORDS_8136.any { r.contains(it) }) {
            return Tier8136.EMERGENCY
        }
        if (r.startsWith("TAIL_MILESTONE")) return Tier8136.RUNNER_MILESTONE
        if (r.startsWith("TAIL_")) return Tier8136.MOONBAG
        if (CONTINUATION_WORDS_8136.any { r.contains(it) }) return Tier8136.RUNNER_CONTINUATION
        if (p == ProtectiveExitClass7807.Priority.HARD_SL || HARD_RISK_WORDS_8136.any { r.contains(it) }) return Tier8136.HARD_RISK
        if (r.contains("CAPITAL_RECOVERY") || r.contains("LIFECYCLE_PROFIT_TAKE1")) return Tier8136.CAPITAL_RECOVERY
        if (MILESTONE_WORDS_8136.any { r.contains(it) }) return Tier8136.RUNNER_MILESTONE
        if (r.contains("MOONBAG")) return Tier8136.MOONBAG
        if (p == ProtectiveExitClass7807.Priority.PROFIT_PROTECTION || PROFIT_WORDS_8136.any { r.contains(it) }) return Tier8136.PROFIT_LOCK
        return Tier8136.ORDINARY
    }

    /** Pure: tier 1 — no hold overlay (lifecycle hold, runner deferral, moonbag, cortex veto) may suppress it. */
    fun emergency8136(reason: String?): Boolean = tierOf8136(reason) == Tier8136.EMERGENCY

    /** One exit request: [fraction] of the CURRENT holding (1.0 = full exit). */
    data class Intent8136(val reason: String, val fraction: Double)

    /** The exit that acts ([act], null = hold) and its tier. */
    data class Verdict8136(val act: Intent8136?, val tier: Tier8136, val suppressed: List<Intent8136>)

    /**
     * Pure: rank simultaneous exit intents. The highest tier acts. A CONFIRMED runner continuation (tier 3 hold) holds
     * the residual through soft FULL exits (tier 5 locks / trails and tier 8 clocks); it never blocks tiers 1-3 or a
     * budgeted partial tranche (recovery, milestone, moonbag sale).
     */
    fun arbitrate8136(intents: List<Intent8136>, continuationConfirmed: Boolean): Verdict8136 {
        val valid = intents.filter { it.fraction.isFinite() && it.fraction > 0.0 }
        val ranked = valid.sortedWith(compareBy<Intent8136>({ tierOf8136(it.reason).rank }, { -it.fraction }))
        for (candidate in ranked) {
            val t = tierOf8136(candidate.reason)
            val softFull = (t == Tier8136.PROFIT_LOCK || t == Tier8136.ORDINARY) && candidate.fraction >= 1.0 - 1e-9
            if (continuationConfirmed && softFull) continue
            return Verdict8136(candidate, t, ranked.filter { it !== candidate })
        }
        return Verdict8136(null, if (continuationConfirmed) Tier8136.RUNNER_CONTINUATION else Tier8136.ORDINARY, ranked)
    }

    // ── 3. capital recovery ────────────────────────────────────────────────

    /** Pure: capital still unrecovered — entry cost less CONFIRMED net proceeds (never below zero). */
    fun unrecoveredSol8136(entryCostSol: Double, confirmedProceedsSol: Double, feesSol: Double): Double {
        if (!entryCostSol.isFinite() || entryCostSol <= 0.0) return 0.0
        val net = (if (confirmedProceedsSol.isFinite()) confirmedProceedsSol else 0.0) - (if (feesSol.isFinite()) feesSol.coerceAtLeast(0.0) else 0.0)
        return (entryCostSol - net).coerceAtLeast(0.0)
    }

    /** Pure: capital is recovered only when CONFIRMED net proceeds cover the entry cost — never a mark, tier or unconfirmed partial. */
    fun capitalRecoveredConfirmed8136(entryCostSol: Double, confirmedProceedsSol: Double, feesSol: Double): Boolean =
        entryCostSol.isFinite() && entryCostSol > 0.0 && confirmedProceedsSol.isFinite() && feesSol.isFinite() &&
            feesSol >= 0.0 && confirmedProceedsSol - feesSol >= entryCostSol - LAMPORT_SOL_8136

    /** One lamport: the tolerance a SOL equality is read with. */
    private const val LAMPORT_SOL_8136 = 1e-9

    private fun keepAfterCosts8136(sellCostPct: Double): Double =
        1.0 - (if (sellCostPct.isFinite()) sellCostPct.coerceIn(0.0, 90.0) else 90.0) / 100.0

    /**
     * Pure: the fraction of the CURRENT holding whose sale nets [unrecoveredSol] after [sellCostPct] of fees and
     * slippage, the holding being worth [grossValueSol] at the executable mark. 0 when nothing is unrecovered or the
     * value is unknown; capped at 1.
     */
    fun recoveryFraction8136(unrecoveredSol: Double, grossValueSol: Double, sellCostPct: Double): Double {
        if (!unrecoveredSol.isFinite() || unrecoveredSol <= 0.0) return 0.0
        if (!grossValueSol.isFinite() || grossValueSol <= 0.0) return 0.0
        return (unrecoveredSol / (grossValueSol * keepAfterCosts8136(sellCostPct))).coerceIn(0.0, 1.0)
    }

    // ── 4. one residual share budget ───────────────────────────────────────

    /** The share of the ORIGINAL quantity a runner keeps as residual runner + moonbag against discretionary ladders. */
    const val RUNNER_RESIDUAL_SHARE_8136 = 0.20

    /**
     * Pure: the fraction of the CURRENT holding a partial [reason] may sell when [remainingShare] of the original is
     * held and [reserveShare] is reserved. Full exits, tiers 1-3 and the moonbag's own milestones are never clamped.
     */
    fun budgetedFraction8136(requestedOfCurrent: Double, remainingShare: Double, reserveShare: Double, reason: String?): Double {
        if (!requestedOfCurrent.isFinite() || requestedOfCurrent <= 0.0) return 0.0
        val req = requestedOfCurrent.coerceAtMost(1.0)
        if (req >= 1.0 - 1e-9) return req
        val r = reason.orEmpty().uppercase()
        if (r.startsWith("TAIL_MILESTONE") || r.startsWith("LIFECYCLE_")) return req
        if (tierOf8136(r).rank <= Tier8136.RUNNER_CONTINUATION.rank) return req
        if (!reserveShare.isFinite() || reserveShare <= 0.0 || !remainingShare.isFinite() || remainingShare <= 0.0) return req
        if (remainingShare <= reserveShare + 1e-9) return 0.0
        return minOf(req, 1.0 - reserveShare / remainingShare).coerceAtLeast(0.0)
    }

    // ── 5. the moonbag needs a funded contract ─────────────────────────────

    /** Pure: a tail of [tailFraction] is funded — capital is recovered, or selling the rest at [pnlPct] after costs recovers it. */
    fun tailFunded8136(pnlPct: Double, capitalRecovered: Boolean, sellCostPct: Double, tailFraction: Double): Boolean {
        if (capitalRecovered) return true
        if (!pnlPct.isFinite() || !tailFraction.isFinite() || tailFraction < 0.0 || tailFraction >= 1.0) return false
        return (1.0 - tailFraction) * (1.0 + pnlPct / 100.0) * keepAfterCosts8136(sellCostPct) >= 1.0
    }

    enum class TailFilter8136 { KEEP, REFUSE_UNFUNDED_SET_ASIDE, RELEASE_UNFUNDED_LOSING_TAIL }

    /**
     * Pure: the funding contract over a moonbag decision. An unfunded set-aside is refused (the ordinary exit sells
     * everything); a held tail whose capital never came back by confirmed proceeds is released once it is red.
     */
    fun tailFilter8136(setAside: Boolean, holdingTail: Boolean, pnlPct: Double, capitalRecovered: Boolean,
                       sellCostPct: Double, tailFraction: Double): TailFilter8136 = when {
        setAside && !tailFunded8136(pnlPct, capitalRecovered, sellCostPct, tailFraction) -> TailFilter8136.REFUSE_UNFUNDED_SET_ASIDE
        holdingTail && !capitalRecovered && (!pnlPct.isFinite() || pnlPct < 0.0) -> TailFilter8136.RELEASE_UNFUNDED_LOSING_TAIL
        else -> TailFilter8136.KEEP
    }

    // ── 6. capture loss and fresh executable marks ─────────────────────────

    /** A profit capture decides only on a mark at most this old. */
    const val FRESH_MARK_MS_8136 = 5_000L

    /** Pure: is a mark [ageMs] old fresh enough to decide a profit capture on? */
    fun freshForCapture8136(ageMs: Long): Boolean = ageMs in 0L..FRESH_MARK_MS_8136

    /** Pure: the gross % a capture reason was decided at (its last `_<n>PCT` token), or NaN. */
    fun decisionGrossOf8136(reason: String): Double {
        val m = Regex("_(-?\\d+)PCT").findAll(reason).lastOrNull() ?: return Double.NaN
        return m.groupValues[1].toDoubleOrNull() ?: Double.NaN
    }

    /** Pure: percentage points the confirmed fill gave up against the gross that decided the capture (>= 0), or NaN. */
    fun captureLossPp8136(decisionGrossPct: Double, proceedsSol: Double, costBasisSol: Double): Double {
        if (!decisionGrossPct.isFinite() || !proceedsSol.isFinite() || proceedsSol < 0.0 || !costBasisSol.isFinite() || costBasisSol <= 0.0) return Double.NaN
        val realised = (proceedsSol / costBasisSol - 1.0) * 100.0
        return (decisionGrossPct - realised).coerceAtLeast(0.0)
    }

    // ── replay: a price path through the legacy lane rules or this contract ─

    /**
     * One print: the feed mark, what a sell route pays per token, the two-feed price (null = none), pool / freeze
     * state, and [sellFails] (every sale sent on this print fails to land: no fill, no proceeds).
     */
    data class Mark8136(
        val px: Double,
        val quotePx: Double = px,
        val agreedPx: Double? = quotePx,
        val liquidityDrained: Boolean = false,
        val frozen: Boolean = false,
        val sellFails: Boolean = false,
    )

    enum class Policy8136 { LEGACY_LANE_RUNNER, CONTRACT_8136 }

    /** Replay outcome in units of the entry cost (1.0). [capturedFraction] = net gain / best executable gain. */
    data class Replay8136(
        val runner: Boolean,
        val proceeds: Double,
        val residualShare: Double,
        val residualValue: Double,
        val net: Double,
        val capturedFraction: Double,
        val capitalRecovered: Boolean,
        val exits: List<String>,
        val maxCaptureLossPp: Double,
        val unexecutable: Int,
        val tailKept: Boolean,
    )

    private class ReplayState8136(val legacy: Boolean) {
        var share = 1.0
        var proceeds = 0.0
        var legacyCostBasis = 1.0
        var recovered = false
        var peak = 0.0
        var runner = legacy
        var scalpDone = 0
        var runnerDone = 0
        var tail = false
        var tailTaken = 0
        var maxLoss = 0.0
        var unexecutable = 0
        val exits = mutableListOf<String>()
    }

    /**
     * Pure: replay [path] (path[0] is the entry print) for a live meme-lane buy sealed as [sealedStyle], every sale
     * paying [sellCostPct] of its gross. LEGACY_LANE_RUNNER: runner ladder from entry, decisions on the feed mark,
     * raw peak, flat 50% at 2x plus the executor's cost / value recovery, a tail on every ordinary exit.
     * CONTRACT_8136: everything above in this object.
     */
    fun replay8136(path: List<Mark8136>, policy: Policy8136, sealedStyle: String, sellCostPct: Double = 3.0): Replay8136 {
        val entry = path.firstOrNull()?.px ?: 0.0
        val st = ReplayState8136(policy == Policy8136.LEGACY_LANE_RUNNER)
        if (!st.legacy && sealedRunnerStyle8136(sealedStyle)) st.runner = true
        var maxExecGain = 0.0
        var lastQuote = entry
        if (entry > 0.0) for (i in 1 until path.size) {
            if (st.share <= 1e-9) break
            val m = path[i]
            lastQuote = m.quotePx
            maxExecGain = maxOf(maxExecGain, m.quotePx / entry - 1.0)
            val execGross = (m.quotePx / entry - 1.0) * 100.0
            val g = if (st.legacy) (m.px / entry - 1.0) * 100.0 else execGross
            st.peak = if (st.legacy) maxOf(st.peak, g) else ratchetPeak8136(st.peak, execGross, m.quotePx, m.agreedPx)
            if (!st.runner && provenPromotion8136(st.peak)) st.runner = true
            val intents = replayIntents8136(st, m, g, entry, sellCostPct)
            val act = arbitrate8136(intents, continuationConfirmed = false).act ?: continue
            replayExecute8136(st, m, act, g, entry, sellCostPct)
        }
        val keep = keepAfterCosts8136(sellCostPct)
        val residualValue = if (entry > 0.0) st.share * lastQuote / entry * keep else 0.0
        val net = st.proceeds + residualValue - 1.0
        val recovered = if (st.legacy) st.recovered else capitalRecoveredConfirmed8136(1.0, st.proceeds, 0.0)
        return Replay8136(st.runner, st.proceeds, st.share, residualValue, net,
            if (maxExecGain > 0.0) net / maxExecGain else Double.NaN, recovered, st.exits.toList(), st.maxLoss,
            st.unexecutable, st.tail && st.share > 1e-9)
    }

    private fun replayIntents8136(st: ReplayState8136, m: Mark8136, g: Double, entry: Double, sellCostPct: Double): List<Intent8136> {
        val out = mutableListOf<Intent8136>()
        if (m.frozen) out += Intent8136("FREEZE_AUTHORITY_FROZEN_EXIT", 1.0)
        if (m.liquidityDrained) out += Intent8136("LIQUIDITY_DRAIN_EXIT", 1.0)
        val gi = g.toInt()
        if (st.tail) {
            if (g <= -LifecycleHold8065.FLOOR_PCT) out += Intent8136("HARD_STOP_TAIL_FLOOR_${gi}PCT", 1.0)
            if (!st.legacy && !st.recovered && g < 0.0) out += Intent8136("HARD_STOP_UNFUNDED_TAIL_8136_${gi}PCT", 1.0)
            TailBag8034.milestoneAt8034(g, st.tailTaken)?.let { (idx, f) -> out += Intent8136("TAIL_MILESTONE_8034_${idx + 1}_${gi}PCT", f) }
            return out
        }
        if (st.runner) {
            if (g <= -35.0) out += Intent8136("RUNNER_HARD_STOP_7967_${gi}PCT", 1.0)
            if (RunnerGrab7967.peakGiveback7980(st.peak, g)) out += Intent8136("RUNNER_PEAK_CAPTURE_7980_${st.peak.toInt()}PK_${gi}PCT", 1.0)
            else if (RunnerGrab7967.fromEntryGiveback8104(st.peak, g)) out += Intent8136("RUNNER_FROM_ENTRY_GIVEBACK_8104_${st.peak.toInt()}PK_${gi}PCT", 1.0)
            val reached = SpikeCapture7943.tierReached(g, RunnerGrab7967.TIERS_7967)
            if (reached >= 1 && st.runnerDone == 0) {
                if (st.legacy) {
                    out += Intent8136("SPIKE_CAPTURE_7943_T1_${gi}PCT", RunnerGrab7967.TIERS_7967[0].second)
                } else {
                    // The first rung is the capital-recovery tranche, sized net of costs on what is still unrecovered.
                    val f = recoveryFraction8136(unrecoveredSol8136(1.0, st.proceeds, 0.0), st.share * m.quotePx / entry, sellCostPct)
                    if (f > 0.0) out += Intent8136("CAPITAL_RECOVERY_8136_${gi}PCT", f) else st.runnerDone = 1
                }
            }
            if (st.runnerDone >= 1 && reached > st.runnerDone) {
                out += Intent8136("RUNNER_MILESTONE_8136_T${reached}_${gi}PCT",
                    SpikeCapture7943.crossedTierFraction8079(RunnerGrab7967.TIERS_7967, st.runnerDone, reached))
            }
            // The executor's capital recovery (Executor.checkProfitLock) at 2x: legacy sizes it cost basis / gross value.
            if (st.legacy && !st.recovered && g >= 100.0) {
                out += Intent8136("capital_recovery_legacy_${gi}PCT", (st.legacyCostBasis / (st.share * m.px / entry)).coerceIn(0.0, 1.0))
            }
        } else {
            if (g <= -20.0) out += Intent8136("STRICT_SL_-20_${gi}PCT", 1.0)
            val reached = SpikeCapture7943.tierReached(g)
            if (reached > st.scalpDone) out += Intent8136("SPIKE_CAPTURE_7943_T${reached}_${gi}PCT",
                SpikeCapture7943.crossedTierFraction8079(SpikeCapture7943.TIERS, st.scalpDone, reached))
            if (st.peak >= 40.0 && g <= st.peak * 0.5) out += Intent8136("TICK_PROFIT_LOCK_peak${st.peak.toInt()}_${gi}PCT", 1.0)
        }
        return out
    }

    private fun replayExecute8136(st: ReplayState8136, m: Mark8136, act: Intent8136, g: Double, entry: Double, sellCostPct: Double) {
        var reason = act.reason
        var frac = act.fraction.coerceIn(0.0, 1.0)
        val tier = tierOf8136(reason)
        var settingTail = false
        // The moonbag: the first ordinary full exit keeps a quarter (legacy: always; contract: only when funded).
        if (!st.tail && frac >= 1.0 - 1e-9 && tier != Tier8136.EMERGENCY && g > -LifecycleHold8065.FLOOR_PCT) {
            val funded = st.legacy || tailFunded8136(g, st.recovered, sellCostPct, TailBag8034.TAIL_FRACTION)
            if (funded) { frac = 1.0 - TailBag8034.TAIL_FRACTION; reason = "TAIL_SET_ASIDE_8034_$reason"; settingTail = true }
        }
        if (!st.legacy && st.runner && !settingTail) frac = budgetedFraction8136(frac, st.share, RUNNER_RESIDUAL_SHARE_8136, reason)
        if (frac <= 0.0) { advanceLadders8136(st, reason, g); return }
        if (m.frozen || m.sellFails) {
            // Unexecutable / not landed: no fill, no proceeds, no rung consumed, the position is still held (a booked
            // exit or a "capital recovered" here would be a ghost).
            st.unexecutable++
            st.exits += (if (m.frozen) "UNEXECUTABLE:" else "UNFILLED:") + reason
            return
        }
        val sold = st.share * frac
        val fill = sold * m.quotePx / entry * keepAfterCosts8136(sellCostPct)
        val loss = captureLossPp8136(decisionGrossOf8136(reason), fill, sold)
        if (loss.isFinite()) st.maxLoss = maxOf(st.maxLoss, loss)
        if (st.legacy && reason.startsWith("capital_recovery")) {
            // Executor.executeProfitLockSell: recovered only when verified proceeds >= 98% of the remaining cost basis.
            if (fill >= st.legacyCostBasis * 0.98) st.recovered = true
        }
        st.legacyCostBasis *= (1.0 - frac)
        st.proceeds += fill
        st.share -= sold
        if (!st.legacy) st.recovered = capitalRecoveredConfirmed8136(1.0, st.proceeds, 0.0)
        if (settingTail) st.tail = true
        st.exits += reason
        advanceLadders8136(st, reason, g)
    }

    private fun advanceLadders8136(st: ReplayState8136, reason: String, g: Double) {
        when {
            reason.startsWith("TAIL_MILESTONE") -> st.tailTaken++
            reason.startsWith("CAPITAL_RECOVERY_8136") -> st.runnerDone = maxOf(st.runnerDone, 1)
            reason.startsWith("RUNNER_MILESTONE_8136") || (st.runner && reason.startsWith("SPIKE_CAPTURE_7943_T")) ->
                st.runnerDone = maxOf(st.runnerDone, SpikeCapture7943.tierReached(g, RunnerGrab7967.TIERS_7967))
            reason.startsWith("SPIKE_CAPTURE_7943_T") -> st.scalpDone = maxOf(st.scalpDone, SpikeCapture7943.tierReached(g))
        }
    }
}
