package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8577 §LANE_AUTHORITY — owner, 8575 log: LIVE evaluated ~800 candidates, admitted 13, bought 0, while the same
 * strategies in the paper book beside LIVE closed SHITCOIN n8 +558 % (post-guard). "Before the Cortex the bot made money on
 * pretty much the whole meme trader."
 *
 * LIVE admission is the designed authority again: the V3 scorer and the lane's own qualification decide; hard safety,
 * structural selling risk, the executable minimum (fresh price, every critical fact, a known cost / route / size) and the
 * kill switch veto. Every learned or proof door added since — the 8136 data / economics defers, the 8418 falling hour, the
 * 8506 entry waits, the 8536 / 8545 / 8559 / 8546 proof doors, the 8238 generic-prior lock, the 8094 record negatives, the
 * 8555 chase sweep, Cortex vetoes, the edge model's NEGATIVE, lane evidence holds and realised locks — is ADVISORY for a
 * lane-qualified LIVE candidate: still evaluated, counted (`LANE_AUTHORITY_ADVISORY_8577_<door>`), traced on the decision
 * and filed on the door's own forward record (GateOutcome8418), so every door keeps learning; none of them refuses.
 * Paper is untouched (its book never had these doors; it is the comparison).
 */
object LaneAuthority8577 {
    const val REASON_8577 = "LANE_AUTHORITY_8577"
    @Volatile var enabled8577: Boolean = true

    /** A refusal the designed authority keeps: hard safety, structure, the executable minimum, the kill switch. */
    private val BINDING_8577 = listOf(
        // V5.0.8626 — the live entry-state refusals (memeEntryRefusal8626) bind.
        "MEME_BUYS_THE_HIGH_8582", "LIVE_STAGE_LOSES_8626", "LIVE_ORIGIN_COHORT_LOSES_8703", "LIVE_STAGE_UNREAD_8711", "LIVE_SETUP_LOSES_8714",
        // V5.0.8627 — a runner lane's live buy of a coin more than a day old binds too (memeEntryRefusal8626).
        "LIVE_RUNNER_STALE_COIN_8627", "EXECUTION_NET_NONPOSITIVE_8629", "EXECUTION_ECONOMICS_8136", "COST_LEDGER_7962_ABOVE_GROSS",
        "SAFETY_REFUSED", "STRUCTURAL_RISK", "RUG", "FREEZE", "MINT_AUTH", "TOKEN_2022", "TOKEN2022", "T22_", "HOSTILE",
        "HONEYPOT", "SELLAB", "UNSELLABLE", "MAYHEM", "BLACKLIST", "HARD_BLOCK", "LIQ_TOO_LOW", "LIQUIDITY_FLOOR",
        "LIQUIDITY_COLLAPSE", "LANE_LIQ", "EXIT_SAFE", "IDENTITY", "PRICE_BASIS", "BASIS_PROOF", "UNTRUSTED_MARK", "WALLET",
        "BLIND", "BALANCE", "LEDGER_REJECT", "LEDGER_COMMIT", "LEDGER_BASIS", "LEDGER_PRECHECK", "DUPLICATE", "SINGLE_FLIGHT", "KILL_SWITCH", "HALT", "AT_LAUNCH_CAP", "DEAD_LAUNCH",
        "EMPTY_CURVE", "EARLY_LAUNCH", "ROUTE_MIN", "SIZE_NOT_EXECUTABLE", "NON_ECONOMIC_APPROVAL", "QUARANT", "REBUY",
        // V5.0.8606 — this bot's own 24 h loss locks bind at the gate as they do at PreTradeHardGate: 19:23 dump, admitted locked
        // mints were ticketed, refused PRETRADE_BIG_LOSER_8130 after the ticket, backed off and re-ticketed (56 BACKOFF_ENDS_TICKET).
        "BIG_LOSER", "REPEAT_LOSER", "LIVE_COIN_STATE_UNPAID", "LIVE_POCKET_",
        "DATA_", "STALE", "_FAILED", "GENERIC_SCORE_FLOOR",
        // V5.0.8869 — the shadow's timing verdict delays a live buy until its entry (a pullback / a confirm) and drops a coin
        // that never offers it inside the wait (no trade, as the verdict counts it). 8687 meant it to bind; as an unlisted
        // learned door 8577 made it advisory, and live bought now (runner admits: 82 bought the top of 120).
        "ENTRY_TIMING_WAIT_", "ENTRY_TIMING_EXPIRED_",
        // superseded by V5.0.8604 — 8582 re-bound "INTO_HIGH", "CHASING_SPIKE", "CHASE_EXTENDED", "NEAR_HIGH",
        // "MEME_BUYS_THE_HIGH_8582" here. LIVE now decides like the paper book beside it, which never had those doors and is
        // the profitable book; they are learned opinions (advisory, still counted on their own forward records), not walls.
        // The bot's own refused-coin record agreed for two of them: LIVE_INTO_HIGH_UNPROVEN refused +20.6 % (n8) and
        // CHASING_SPIKE refused +0.7 % (n16) vs admitted -5.7 % / -15.0 %.
    )

    /**
     * V5.0.8604 — learned waits whose names happen to contain a binding word ("DATA_"). The paper book needs only a fresh
     * mark and a known cost; these wait for a move history / a turned hour it never asks for. The executable minimum
     * (DATA_INCOMPLETE_DEFER_8136: unknown cost / no price) still binds via [bindingAdmission8577].
     */
    const val PAPER_LOSING_8608 = "PAPER_LANE_PROVEN_LOSING_8608"
    const val COIN_STATE_8609 = "LIVE_COIN_STATE_UNPAID_8609"
    const val POCKET_UNPROVEN_8610 = "LIVE_POCKET_UNPROVEN_8610"
    const val POCKET_LOSES_8610 = "LIVE_POCKET_PROVEN_LOSING_8610"

    /** Pure-ish (V5.0.8609): null when a LIVE buy in [lane] at [launchPhase] is inside the market's measured paying state. */
    // V5.0.8610 — the learned PocketLedger8610 decides once it is seeded: a coin whose cells have proven they pay (and none
    // proven a loss) is bought in ANY state — a mature bounce, a post-pump base, a day-old coin — and a coin in an unproven or
    // losing pocket is left to the paper book. The hard-coded 8609 EXPANDING rule is only the fallback before the seed.
    private fun liveCoinStateRefusal8609(lane: String, launchPhase: String?, pocket: PocketLedger8610.Call?): String? {
        val l = lane.trim().uppercase()
        // V5.0.8770 — code audit (learning 2): an unproven pocket is not a refusal (it was counted as one, advisory, since 8620).
        if (pocket != null) return if (pocket == PocketLedger8610.Call.LOSES) POCKET_LOSES_8610 else null
        return if (coinStatePays8609(l, launchPhase)) null else COIN_STATE_8609
    }

    /** Pure (V5.0.8609): EXPANDING for every lane; PRE_IGNITION too for the sniper. */
    fun coinStatePays8609(lane: String, launchPhase: String?): Boolean {
        val p = launchPhase?.trim()?.uppercase().orEmpty()
        return p == "EXPANDING" || (p == "PRE_IGNITION" && lane.trim().uppercase() == "PROJECT_SNIPER")
    }

    private val LEARNED_WAITS_8604 = listOf("DATA_INCOMPLETE_MOVE_HISTORY", "FALLING_HOUR", "DATA_WAIT_SPENT")

    /**
     * V5.0.8582 §BUY_THE_PULLBACK — the 8577 edge report (2,105 decisions, 26 h, net at the lane's target horizon), meme lanes:
     *   at the high (within 3 % of the peak) n119 -26.9 % PF 0.02 · up >= 10 % in 5 min n175 ~-24 % PF ~0.1 ·
     *   buy pressure >= 60 % n187 -17..-31 % PF <= 0.03 · ran up 5-60 % n83 ~-35 %
     *   10-25 % off the peak n63 +12.5 % PF 1.81 · buy pressure < 40 % n115 +9.4 % PF 1.72 · down > 10 % in 5 min n105 +2.2 %.
     * 8577 made the chase doors advisory and LIVE went 8W/9L PF 0.64 buying tops. A LIVE meme entry at the high is refused
     * whatever else admitted it; pullbacks and dips are what the meme lanes buy. Pure; unknown features never refuse.
     *
     * V5.0.8603 — the "spiking" leg (up >= 10 % in 5 min) is removed. On 18,735 labelled decisions (edge 1794038024285,
     * managed stop -20 / ride +400) the coins it refused were the RUNNERS: 3.1 % went +100 % vs 1.7 % of the coins it passed
     * (all meme lanes 3.0 % vs 1.7 %). A launch being bought is up in its first 5 minutes by definition.
     * V5.0.8642 — buy pressure alone is not an at-the-high signal. BASE_BREAKOUT_FLOW requires >=60% buy share,
     * and the November 9 filled-horizon export does not justify vetoing that feature independently of price.
     * Keep the actual price-versus-peak chase guard; leave tape quality to the setup.
     */
    const val MEME_BUYS_THE_HIGH_8582 = "MEME_BUYS_THE_HIGH_8582"
    private val MEME_LANES_8582 = setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "DIP_HUNTER",
        "QUALITY", "BLUECHIP", "CORE", "CYCLIC")

    fun memeBuysTheHigh8582(lane: String, pxPeak: Double, runupPct: Double, buyPressurePct: Double): Boolean {
        if (lane.trim().uppercase() !in MEME_LANES_8582) return false
        val atHigh = pxPeak.isFinite() && pxPeak >= 0.97 && runupPct.isFinite() && runupPct >= 5.0
        return atHigh
    }

    // superseded by V5.0.8626 — 8604 deleted the runtime read that fed [memeBuysTheHigh8582] into LIVE so live would "decide
    // like the paper book". That book's profit was lost-mark closes at stale marks (8622: 198 of 241 closes, +6.3 SOL, while
    // every priced exit lost -0.6 SOL; fixed 8623), and live went on buying tops: 14:12 dump runnerAdmits8297 live
    // LOSS_BOUGHT_TOP=40 of 63. The read is restored below as [memeEntryRefusal8626], with the coin's stage.

    /**
     * V5.0.8706 §A_STAGE_IS_REFUSED_ON_ITS_OWN_RECORD — the live stage door, rewritten. 8626 named two stages by hand
     * (PEAK_EXHAUSTION, DUMPING: refused until proven positive) from one dump in which "LAUNCH and BREAKOUT were the positive
     * cells", and every other stage was never refused whatever its record said. The 8704 capture, same lanes, 5-min net:
     *   MOONSHOT  LAUNCH n287 -17.3 %  BASE n192 -0.2 % (16 % ran)  BREAKOUT n135 -3.0 %  PULLBACK n72 -8.7 %  PEAK n773 -12.3 %  DUMP n1007 -24.7 %
     *   SHITCOIN  LAUNCH n403 -24.9 %  EXPRESS LAUNCH n371 -22.2 %  — and live runner admits LOSS_BOUGHT_TOP=76 of 110.
     * LAUNCH was unlisted, so live kept buying the worst cell on the board. There is no list now: a live meme entry at a known
     * stage is refused when that lane's own forward record AT THAT STAGE is proven losing (n >= 30, mean + se < 0), and at no
     * other time. A thin record, an unknown stage and a stage that stops losing never refuse; paper trades every stage, so
     * every record keeps moving. The door retires on the one selection-gate rule (memeEntryRefusal8626).
     */
    const val LIVE_STAGE_LOSES_8626 = "LIVE_STAGE_LOSES_8626"
    private const val STAGE_RECORD_MIN_N_8706 = 30

    /** Pure: is a live entry at [stage] refused, given the lane's own record there (n, mean %, se %)? */
    fun stageLoses8626(stage: String?, n: Int, mean: Double, se: Double): Boolean {
        if (stage.isNullOrBlank()) return false
        return n >= STAGE_RECORD_MIN_N_8706 && mean.isFinite() && se.isFinite() && mean + se < 0.0
    }

    /**
     * V5.0.8627 §RUNNER_LANES_BUY_YOUNG_COINS — 8537 edge export (14:12 / 8622, 33,008 rows, age in minutes, 5-min executable
     * net, the 13 seed-basis labels excluded): the runner lanes' decisions on coins more than 24 h old were n1,541 mean -6.2 %
     * PF 0.24 with two 2x moves in 1,541; LIVE admitted 202 of them (28 % of its runner-lane admits: MOONSHOT, EXPRESS) and
     * none went 2x — the "chip trades". Coins under 24 h held every other runner. A runner lane does not buy a coin whose
     * known launch age is over [RUNNER_MAX_AGE_MS_8627]; an unknown age never refuses.
     */
    const val LIVE_RUNNER_STALE_COIN_8627 = "LIVE_RUNNER_STALE_COIN_8627"
    const val RUNNER_MAX_AGE_MS_8627 = 24L * 3_600_000L
    // superseded by V5.0.8631 — the runner lanes are the keys of the one per-lane age table (DiscoveryFresh8631), whose
    // tighter caps stop those lanes evaluating older coins at BotService.laneEvaluationAllowed7951; this 24 h live refusal
    // stays as the outer bound at the gate. was: setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED")
    private val RUNNER_LANES_8627: Set<String> = com.lifecyclebot.engine.DiscoveryFresh8631.RUNNER_LANE_MAX_AGE_MS_8631.keys

    /** Pure: is a live buy in [lane] of a coin [launchAgeMs] old refused as stale? */
    fun runnerCoinStale8627(lane: String, launchAgeMs: Long?): Boolean =
        com.lifecyclebot.engine.cortex.LaneCheatSheet8323.key8323(lane) in RUNNER_LANES_8627 && launchAgeMs != null && launchAgeMs > RUNNER_MAX_AGE_MS_8627

    /** CandidateAdmission8136.assess8136 (LIVE): the refusal of a meme entry at the high or at a losing stage, else null. */
    fun memeEntryRefusal8626(ts: com.lifecyclebot.data.TokenState, lane: String, stage: String?, nowMs: Long, pocket8874: Boolean = false): String? {
        val first8724 = memeEntryState8696(ts, lane, stage, nowMs) ?: return null
        // V5.0.8723 / 8724 — the lane's own record of the coin's market cell (PayingCell8723) answers the lane-wide and broader
        // entry-state priors ([CELL_ANSWERS_8724]). The coin's own chart shapes and the losing record of the setup it trades
        // as are narrower evidence: they are read again and still refuse.
        // V5.0.8874 — a coin carrying its lane's proven agreement pocket ([pocket8874], [answersPriors8874]) answers the same
        // priors: the same per-lane, per-label proof over a narrower group of coins than the lane-wide and origin-wide records.
        val why = if (first8724 in CELL_ANSWERS_8724 && (pocket8874 || (try { PayingCell8723.pays8723(ts, lane, nowMs) } catch (_: Throwable) { false }))) {
            try { PipelineHealthCollector.labelInc("${first8724}_ANSWERED_BY_MARKET_CELL_8724") } catch (_: Throwable) {}
            narrowerRefusal8724(ts, lane.trim().uppercase(), nowMs) ?: return null
        } else first8724
        // V5.0.8895 — the no-setup cohort is the lane's residual, not a strategy: a coin carrying proven pocket evidence is answered
        // there too ([residualAnswered8895]); a named losing setup and the expert avoid shapes still refuse.
        if (why == LIVE_SETUP_LOSES_8714 && residualAnswered8895(ts, lane, nowMs, pocket8874)) return null
        // V5.0.8696 — every entry-state door is learned by the one rule: it stands aside once its own graded refusals prove
        // it refuses winners.
        if (GateOutcome8418.selectionGateRetired8696("CANDIDATE_ADMISSION_8136_$why", lane)) return null
        try { PipelineHealthCollector.labelInc("${why}_BOUND_8696") } catch (_: Throwable) {}
        return why
    }

    /**
     * V5.0.8874 — the market-cell evidence kinds, besides a paying cell, that answer the same priors: a per-lane record proven
     * on its own labels over a narrower group of coins than the lane-wide and origin-wide records.
     */
    private val PRIOR_ANSWERING_KINDS_8874: Set<String> = setOf(AgreementPocket8874.KIND_8874, LlmBands8875.KIND_8875,   // V5.0.8875: the LLM's proven band
        BasePocket8904.KIND_8904,   // V5.0.8904: the proven base pocket
        LanePocket8934.KIND_8934,   // V5.0.8934: the lane's own proven in-band cell
        LaunchSniper8748.KIND_8748, // V5.0.8949: the launch replay's proven rule, armed on this coin's own prints
        RunnerModel8861.KIND_8861,  // V5.0.8987: the runner model's armed pick (the note below)
        BaseBreakout8995.KIND_8995) // V5.0.8995: the base breakout armed on its own forward record for the coin's venue class

    /*
     * V5.0.8987 §THE_ARMED_RUNNER_PICK_ANSWERS_THE_LANE_PRIORS — owner, 24 Nov 22:14: "where's the runner capture gone????
     * theres still heaps of tokens and coins moving thousands of percent a day and we still entirely ignore those". The
     * research rows to 24 Nov (3,435 decisions read at 4 h, an unpriced read counted -100 %): 1.2 % of everything the bot
     * saw was 4x+ four hours on, and the cohort that holds them is pump.fun coins under a $5k cap (n228, +37.3 % mean,
     * 5.3 % at 4x+) — the launch floor, where RunnerModel8861 picks (tape features at the $5,000 cross -> doubled before a
     * 20 % stop). That model was ARMED on its own forward record (picks n1984 +13.5 % +/- 3.5, 2x+ 9 %; every cross n5683
     * +5.5 %) and in 103 min made 38 armed picks: of the 184 candidate episodes carrying them 2 were admitted and none
     * bought — 36 refused LIVE_STAGE_LOSES_8626, the lane-wide record of the launch stage, which the pick is a proven
     * narrower group of (8874 gave the pockets that standing and left the model without it; 8949 gave it to the launch
     * rule). The armed pick now answers the same entry-state priors the pockets do. It is served only while the model is
     * armed (n>=100, mean - 2 SE > 0, positive without its best pick). Safety, structure (RUG_PRONE), economics, the buy
     * door and a NAMED losing setup still refuse it.
     */

    /** Pure (V5.0.8874): does market-cell evidence of [kind] answer the lane-wide entry-state priors? */
    fun answersPriors8874(kind: String?): Boolean = kind != null && kind in PRIOR_ANSWERING_KINDS_8874

    /** The entry-state doors a lane's paying market cell answers: priors over more coins than the cell holds. */
    private val CELL_ANSWERS_8724: Set<String> get() = setOf(LIVE_ORIGIN_COHORT_LOSES_8703, MEME_BUYS_THE_HIGH_8582, LIVE_STAGE_UNREAD_8711, LIVE_STAGE_LOSES_8626)

    /**
     * V5.0.8895 §THE_RESIDUAL_COHORT_IS_NOT_A_STRATEGY — owner, 20 Nov: "how do we improve the trade selection logic??". On 5.0.8893
     * the paying cell's coins were refused 72 times by LIVE_SETUP_LOSES_8714 (payCell8732 admission{...}) and bought 0 times.
     * 8714 refuses the strategy a coin trades as when its lane record loses — and a coin no named setup fired trades as the
     * lane's NO-SETUP cohort (NO_TRIGGER, or OUTSIDE_ENVELOPE_8383): every coin of the lane that matched nothing, the widest
     * record there is. 8724 / 8874 already let a coin's proven paying cell or agreement pocket answer the lane-wide priors,
     * because the cell is proven over a narrower group of coins than they are; the no-setup cohort is the same kind of prior.
     * It is answered for a carried coin ([pocket8874], or the lane's paying cell); a NAMED losing setup still refuses, as do
     * the expert avoid shapes, and every safety, LP, sellability and economics check after this is unchanged. Counted per
     * lane and cohort so the next report says how often it decided.
     */
    private val RESIDUAL_COHORTS_8895: Set<String> = setOf(
        com.lifecyclebot.engine.cortex.LanePlaybook7907.NO_TRIGGER, com.lifecyclebot.engine.cortex.LanePlaybook7907.OUTSIDE_ENVELOPE_8383)
    private val residualCounts8895 = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    /** Pure: is [setup] a lane's no-setup cohort rather than a named strategy? */
    fun residualCohort8895(setup: String?): Boolean = setup != null && setup in RESIDUAL_COHORTS_8895

    private fun residualAnswered8895(ts: com.lifecyclebot.data.TokenState, lane: String, nowMs: Long, pocket8874: Boolean): Boolean {
        val l = lane.trim().uppercase()
        val setup = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.losingSetup8714(ts, l, nowMs) } catch (_: Throwable) { null }
        if (!residualCohort8895(setup)) return false
        if (!(pocket8874 || (try { PayingCell8723.pays8723(ts, lane, nowMs) } catch (_: Throwable) { false }))) return false
        val k = "$l|$setup"
        if (residualCounts8895.size < 64 || residualCounts8895.containsKey(k))
            residualCounts8895.computeIfAbsent(k) { java.util.concurrent.atomic.AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIVE_SETUP_LOSES_8714_RESIDUAL_ANSWERED_8895_$l") } catch (_: Throwable) {}
        return true
    }

    /** Brief (V5.0.8895): how often a carried coin's losing no-setup cohort was answered, by lane and cohort. */
    fun residualLine8895(): String = "residual8895{" + residualCounts8895.entries.sortedByDescending { it.value.get() }
        .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } + "}"

    /** The refusals narrower than a market cell: the coin's own avoid shape, else the losing record of the setup it trades as. */
    private fun narrowerRefusal8724(ts: com.lifecyclebot.data.TokenState, l: String, nowMs: Long): String? {
        return if (l in MEME_LANES_8582) expertAvoid8692(ts, nowMs) ?: setupLoses8714(ts, l, nowMs) else null
    }

    private fun memeEntryState8696(ts: com.lifecyclebot.data.TokenState, lane: String, stage: String?, nowMs: Long): String? {
        val l = lane.trim().uppercase()
        val age8627 = try { CanonicalTokenBirthTime7440.launchAgeMs7767(ts, nowMs) } catch (_: Throwable) { null }
        if (runnerCoinStale8627(l, age8627)) return LIVE_RUNNER_STALE_COIN_8627
        if (originLoses8703(ts, l, nowMs)) return LIVE_ORIGIN_COHORT_LOSES_8703
        if (l !in MEME_LANES_8582) return null
        val f = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.features(ts, nowMs) } catch (_: Throwable) { null }
        if (f != null && memeBuysTheHigh8582(l, f.pxPeak, f.runup, f.bp)) return MEME_BUYS_THE_HIGH_8582
        // V5.0.8692 — the expert playbook's avoid shapes (spike-and-fade, staircase down).
        expertAvoid8692(ts, nowMs)?.let { return it }
        if (stageUnread8711(stage)) return LIVE_STAGE_UNREAD_8711
        val st = if (stage.isNullOrBlank()) null else try { ForwardReturnLabeler7731.stageStatFor7928(l, stage) } catch (_: Throwable) { null }
        if (stageLoses8626(stage, st?.n60 ?: 0, st?.meanNet60Pct ?: Double.NaN, st?.stderr60Pct ?: Double.NaN)) return LIVE_STAGE_LOSES_8626
        return setupLoses8714(ts, l, nowMs)
    }

    /**
     * V5.0.8714 §LIVE_DOES_NOT_BUY_THE_SETUP_ITS_OWN_RECORD_SAYS_LOSES — owner: "cortex and setups still show signs of being
     * completely backwards pretty much everywhere". The Cortex setups panel, 8712: MOONSHOT RS_LEADER n12 -29.2 % ±7.4,
     * BREAKOUT_RUNNER n12 -17.6 % ±4.1, HIGHER_LOW_PULLBACK -23.4 %, no-setup (NO_TRIGGER) n22 -13.5 % ±6.6 — and live bought
     * them: a setup's record refused only through the playbook's own doors, which stopped binding with 8577/8696, and those
     * waited for 30 labels. The strategy a coin trades as now refuses a live entry when its own record in that lane is
     * losing by the mirror of the cold-start proof (LanePlaybook7907.coldLosing8714: the same 12 labels, mean + 1.5 SE below
     * zero). Paper keeps trading every setup, so a record that turns stops refusing, and the door retires on the one
     * selection-gate rule if the coins it refuses win (memeEntryRefusal8626).
     */
    const val LIVE_SETUP_LOSES_8714 = "LIVE_SETUP_LOSES_8714"

    private fun setupLoses8714(ts: com.lifecyclebot.data.TokenState, lane: String, nowMs: Long): String? {
        val setup = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.losingSetup8714(ts, lane, nowMs) } catch (_: Throwable) { null } ?: return null
        try { PipelineHealthCollector.labelInc("LIVE_SETUP_LOSES_8714_${lane}_${setup.take(32)}") } catch (_: Throwable) {}
        return LIVE_SETUP_LOSES_8714
    }

    /**
     * V5.0.8711 §A_COIN_WHOSE_STAGE_NOBODY_READ_IS_NOT_BOUGHT_LIVE — 8708 live, every admit by the stage it was bought at
     * (runnerAdmits8297 byStage): MOONSHOT|UNKNOWN n7 0 wins, 7 bought the top; CASHGEN|UNKNOWN n2 0 wins; EXPRESS / MOONSHOT
     * NO_SETUP n4 0 wins — all through NATIVE_QUALIFIED_ADMIT_8259, 17 of 17 at the top. The stage door "never refuses an
     * unknown stage", so the coins it could say nothing about were the ones live bought, while the only stage that pays on
     * the bot's own record (BASE: MOONSHOT +2.2 %, SHITCOIN +2.3 %, MANIPULATED +4.5 %, 14-16 % running) needs the stage
     * read to be recognised at all. A live meme entry whose stage is unread is refused until it is read; the coin is
     * judged again every cycle, and the door retires on the one selection-gate rule if the coins it refuses win.
     */
    const val LIVE_STAGE_UNREAD_8711 = "LIVE_STAGE_UNREAD_8711"

    /** Pure: is [stage] unread (absent or the UNKNOWN placeholder)? */
    fun stageUnread8711(stage: String?): Boolean = stage.isNullOrBlank() || stage.trim().equals("UNKNOWN", ignoreCase = true)

    const val LIVE_ORIGIN_COHORT_LOSES_8703 = "LIVE_ORIGIN_COHORT_LOSES_8703"

    /**
     * V5.0.8703 — owner: "it's meant to be the entire sol network, not just pump fun". 8689: 73 % of everything judged was
     * pump.fun-minted and every band of it lost (-4 .. -20 % net), so the wallet's one or two slots filled with it. A coin
     * whose origin x pool-depth cohort is proven losing on its own forward record takes LIVE capital only when it carries a
     * proven edge of its own (LiveEdgeGate7877.edge8114Live8538: proven setup / cell / kind, measured edge, runner forming,
     * shadow audit, chart BUY, Cortex strong).
     */
    /**
     * Pure (V5.0.8770 §A_CELL_VETO_BEATS_THE_LANE_NOT_ZERO) — code audit (learning 2): the cohort door compared its cell's mean
     * with zero. In a market whose base rate is negative nearly every cell is below zero (log: marketCohort LOSING in almost
     * every band; the door n259 PROTECTING), so it vetoed every candidate the lane picked whatever the lane's own record. It
     * binds now only when the cohort does worse than the coins the lane keeps ([kept]: its admitted record) by more than two
     * standard errors of the difference — the bar refusalAuthority8715 holds the edge model to. No kept record of 30, no veto.
     */
    fun cohortWorseThanKept8770(cohort: ForwardReturnLabeler7731.CellStat?, kept: ForwardReturnLabeler7731.CellStat?): Boolean {
        if (cohort == null || kept == null || kept.n60 < 30) return false
        val se = kotlin.math.hypot(cohort.stderr60Pct, kept.stderr60Pct)
        return se.isFinite() && cohort.meanNet60Pct.isFinite() && kept.meanNet60Pct.isFinite() &&
            cohort.meanNet60Pct < kept.meanNet60Pct - 2.0 * se
    }

    private fun originLoses8703(ts: com.lifecyclebot.data.TokenState, lane: String, nowMs: Long): Boolean {
        val cohort = try { MarketCohort8701.provenLosing8703(ts) } catch (_: Throwable) { null } ?: return false
        val kept8770 = try { ForwardReturnLabeler7731.laneDecisionStats8094(lane).first } catch (_: Throwable) { null }
        if (!cohortWorseThanKept8770(try { ForwardReturnLabeler7731.laneStatFor7737(cohort) } catch (_: Throwable) { null }, kept8770)) {
            try { PipelineHealthCollector.labelInc("ORIGIN_COHORT_NOT_WORSE_THAN_LANE_8770_$cohort") } catch (_: Throwable) {}
            return false
        }
        val ownEdge = try { LiveEdgeGate7877.edge8114Live8538(ts, lane, nowMs, ticket = false) } catch (_: Throwable) { null }
        try { PipelineHealthCollector.labelInc("ORIGIN_COHORT_LOSING_8703_${cohort}_${if (ownEdge != null) "OWN_EDGE_$ownEdge" else "REFUSED"}") } catch (_: Throwable) {}
        return ownEdge == null
    }

    private fun expertAvoid8692(ts: com.lifecyclebot.data.TokenState, nowMs: Long): String? {
        val m1 = try { MultiTimeframeCharts8521.snapshot8521(ts, MultiTimeframeCharts8521.Frame8521.M1, nowMs) } catch (_: Throwable) { return null }
        val flow = try { FlowRead8546.read8546(ts.mint, nowMs) } catch (_: Throwable) { null }
        val sellDominant = flow?.takeIf { it.known }?.let { it.sellSol2m > it.buySol2m }
        return when {
            ExpertSetups8692.spikeFade8692(m1, sellDominant) -> ExpertSetups8692.SPIKE_FADE_8692
            ExpertSetups8692.staircaseDown8692(m1) -> ExpertSetups8692.STAIRCASE_DOWN_8692
            else -> null
        }
    }

    /** Does [reason] bind under lane authority? The listed integrity doors always; a learned door once its record protects (8715). */
    fun binding8577(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.uppercase()
        // V5.0.8650 — the single admission's actual negative setup/cell/timing verdict cannot
        // be reversed by a generic native score at this layer or again at LiveEdgeGate.
        if (CandidateAdmission8136.verifiedRecordDoor8650(r.removePrefix("CANDIDATE_ADMISSION_8136_"))) return true
        if (LEARNED_WAITS_8604.any { r.contains(it) }) return false
        if (BINDING_8577.any { r.contains(it) }) return true
        // V5.0.8715 — a learned door binds once its own graded record proves the coins it says no to lose.
        return try { GateOutcome8418.doorProtects8715(r) } catch (_: Throwable) { false }
    }

    /**
     * Pure: the admission-level refusals that stay binding. A DATA_INCOMPLETE defer binds only as the executable minimum
     * (stale price, a critical fact unobserved, unknown cost); the entry-wait / move-unread variants and the falling-hour
     * wait are learned doors. [costKnown]: the candidate's round-trip cost is a finite, non-negative figure.
     */
    fun bindingAdmission8577(d: CandidateAdmission8136.Decision8136, costKnown: Boolean): Boolean {
        if (d.reason == "DATA_INCOMPLETE_DEFER_8136") {
            // V5.0.8649 — a known fee is not a known, executable forecast.
            // 8648 promoted a native-qualified DATA defer with cost=known,
            // expected move=NaN to LIVE ADMIT, only to fail at the send boundary.
            if (!costKnown || !d.expectedNetPct.isFinite()) return true
            return d.trace.any { line ->
                line.startsWith("criticalUnobserved8170=") ||
                    (line.startsWith("missing=") && line.removePrefix("missing=").split(',').any { it.trim() == "price" })
            }
        }
        if (d.reason == CandidateAdmission8136.FALLING_HOUR_DEFER_8418) return false
        return binding8577(d.reason)
    }

    internal fun withPaperPrior(d: CandidateAdmission8136.Decision8136, losing: Boolean): CandidateAdmission8136.Decision8136 =
        if (losing) d.copy(trace = d.trace + "paperLanePrior=$PAPER_LOSING_8608") else d

    /**
     * CandidateAdmission8136.assess8136 (LIVE): the lane-qualified candidate's refusal by a learned door becomes an ADMIT
     * carrying that door as advisory; a binding refusal and a paper / unqualified candidate pass through unchanged. Every
     * decision of a qualified LIVE candidate is marked [CandidateAdmission8136.Decision8136.laneQualified8577] for the gate.
     */
    fun apply8577(c: CandidateAdmission8136.Candidate8136, d: CandidateAdmission8136.Decision8136,
                  highRefusal8582: String? = null, launchPhase8609: String? = null,
                  pocket8610: PocketLedger8610.Call? = null): CandidateAdmission8136.Decision8136 {
        val r = applyLane8577(c, d, highRefusal8582)
        if (!enabled8577 || !c.live || !r.admitted) return r
        val why = liveCoinStateRefusal8609(c.lane, launchPhase8609, pocket8610) ?: return r
        // V5.0.8620 §REPORT_ONLY_UNTIL_PROVEN — owner (8616 live): "still has 0 live buys". 8607 traded live and was net positive
        // (10 closes, PF 1.15); 8608 / 8609 / 8610 then each refused nearly every live candidate in a cold market. The coin-state /
        // pocket verdict is now counted and traced on the admit (the tuning report shows what it would have refused and how those
        // coins did), never a refusal, until its record against live closes earns it the right to bind.
        try { PipelineHealthCollector.labelInc("${why}_ADVISORY_8620_${c.lane.uppercase()}") } catch (_: Throwable) {}
        return r.copy(trace = r.trace + "advisory8620=$why coinState8609=${launchPhase8609 ?: "-"}")
    }

    // ── V5.0.9022 §THE_MODELS_PICK_IS_BOUGHT ─────────────────────────────────────────────────────────────────────────────
    // Owner, 26 Nov 01:07: "the model has to buy the winning trades". 5.0.9020 research rows, pump.fun mints, one worst read
    // per coin-hour, under an approximation of the bot's own exits: forecast >= +5 % n473 +18.6 % +/- 14.4 with 8.0 % reaching
    // +100 % and 2.1 % +400 %; forecast < 0 n2257 +0.4 % +/- 3.1 with 3.6 % / 0.5 %. The model finds where the runners are —
    // and its picks met the same learned short-term refusals as every other candidate (24 h: ~110 pump.fun coins picked, 0
    // bought in the capture's session). A pick is a LIVE candidate the model calls POSITIVE (its own out-of-sample authority,
    // or an earned conviction tier) with a forecast of [PICK_MIN_PRED_PCT_9022]+. For a pick the learned short-term
    // refusals ([PICK_ADVISORY_9022]: the coin's own move against cost, setup / cell / timing / stage records, the lane's
    // probe slot, watch-first, the chase door) are carried as advisory, at the admission and at the gate. Everything else
    // binds as before: safety, structural risk, the buy-the-high refusal, critical data, LP / RugCheck / holder / authority
    // proofs at the door, the kill switch, sizing and the wallet. Inside the noise on the record above — the owner's call;
    // every pick is counted by what happened to it ([pickLine9022]).
    const val PICK_MIN_PRED_PCT_9022 = 5.0
    const val PICK_TRACE_9022 = "modelPick9022"
    private val PICK_ADVISORY_9022 = listOf(
        "EXECUTION_ECONOMICS_8136", "EXECUTION_NET_NONPOSITIVE_8629", "SHEET_AVOID_8323", "CELL_PROVEN_LOSING_8136",
        "SETUP_RECORD_LOSING_8136", "LIVE_SETUP_LOSES_8714", "LIVE_STAGE_LOSES_8626", "TIMING_ADVERSE_8136",
        "TIMING_RECORD_NEGATIVE_8094", "PICKED_SETUP_RECORD_NEGATIVE_8094", "TACTIC_RECORD_LOSING_8094",
        "NET_EDGE_COIN_TOO_SLOW_8035", "NET_EDGE_SCALP_COIN_TOO_FLAT_8054", "LANE_REALISED_LOSING_PROBE_OCCUPIED_8424",
        "LANE_REALISED_LOSING_PROVEN_PROBE_WAIT_8424", "WATCH_FIRST_7994", "CELL_PROOF_NEGATIVE_7731",
        "STAGE_PROVEN_LOSING_7928", "LIVE_SOURCE_PROVEN_LOSING_8536", "CHASING_SPIKE_8065", "LIVE_NON_POSITIVE_EXPECTANCY_7828",
        "BIG_LOSER_8130", "EDGE_7877",
        // V5.0.9023 — the structural-risk judgement (stage RUG_PRONE: a selection judgement, the largest wrong refusal of
        // runners on the 5.0.9020 trace) is advisory for a pick; "STAGE_RUG_PRONE" is its own why-text, not a RugCheck result.
        "STRUCTURAL_RISK_8136",
    )
    private val PICK_NEVER_9022 = listOf("SAFETY", "MEME_BUYS_THE_HIGH", "DATA_INCOMPLETE", "RUGCHECK", "RUG_DRAIN", "HONEYPOT", "LP_", "HOLDER", "KILL_SWITCH")

    /** Pure: is [c] a pick — LIVE, the model's POSITIVE call, a forecast of [PICK_MIN_PRED_PCT_9022]+? */
    fun modelPick9022(c: CandidateAdmission8136.Candidate8136): Boolean =
        c.live && EdgeModel8537.callOf(c.edge8537) == EdgeModel8537.Call.POSITIVE && (c.edge8537?.predPct ?: Double.NaN) >= PICK_MIN_PRED_PCT_9022

    /** Pure: is [reason] a learned short-term refusal a pick is not stopped by? */
    fun pickAdvisory9022(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.uppercase()
        return PICK_NEVER_9022.none { r.contains(it) } && PICK_ADVISORY_9022.any { r.contains(it) }
    }

    /** LiveEdgeGate7877: the gate's learned refusal [reason] does not stop an admitted pick; counted either way. */
    fun pickPassesAtGate9022(reason: String?, admission: CandidateAdmission8136.Decision8136?): Boolean {
        if (admission == null || admission.trace.none { it.startsWith(PICK_TRACE_9022) }) return false
        val pass = pickAdvisory9022(reason)
        notePick9022(if (pass) "GATE_PASSED" else "GATE_STOPPED", reason)
        return pass
    }

    private val picks9022 = ConcurrentHashMap<String, AtomicLong>()
    private val lastPicks9022 = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private fun notePick9022(outcome: String, reason: String?) {
        val why = (reason ?: "").removePrefix("CANDIDATE_ADMISSION_8136_").substringBefore(':').take(44)
        picks9022.computeIfAbsent(if (why.isBlank()) outcome else "$outcome:$why") { AtomicLong() }.incrementAndGet()
    }

    /** The decision a pick leaves the admission with: its learned short-term refusal carried as advisory; marked for the gate. */
    internal fun pickDecision9022(c: CandidateAdmission8136.Candidate8136, d: CandidateAdmission8136.Decision8136): CandidateAdmission8136.Decision8136 {
        if (!modelPick9022(c)) return d
        val pred = c.edge8537?.predPct ?: return d
        val net = if (d.expectedNetPct.isFinite()) maxOf(d.expectedNetPct, pred) else pred
        val out = when {
            d.admitted -> { notePick9022("ADMITTED", null); d.copy(expectedNetPct = net, trace = d.trace + "$PICK_TRACE_9022 pred=$pred") }
            pickAdvisory9022(d.reason) -> {
                notePick9022("ADMITTED_PAST", d.reason)
                d.copy(terminal = CandidateAdmission8136.Terminal8136.ADMIT, reason = CandidateAdmission8136.EDGE_MODEL_PROVEN_8537,
                    specific = true, expectedNetPct = net, trace = d.trace + "$PICK_TRACE_9022 pred=$pred advisory=${d.reason}")
            }
            else -> { notePick9022("STOPPED", d.reason); d }
        }
        lastPicks9022.addLast("${c.mint.take(6)}|${c.lane}|${"%+.0f".format(pred)}|${if (out.admitted) "ADMIT" else d.reason.take(28)}")
        while (lastPicks9022.size > 8) lastPicks9022.pollFirst()
        return out
    }

    /** Brief: what happened to the model's picks this session, by outcome and refusal, and the last eight. */
    fun pickLine9022(): String =
        "modelPicks9022[min=+${PICK_MIN_PRED_PCT_9022.toInt()}% {" + picks9022.entries.sortedByDescending { it.value.get() }.take(14)
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" } + "} last{" + lastPicks9022.joinToString(" ").ifBlank { "-" } +
            "} rule=a LIVE model pick is not stopped by a learned short-term refusal at the admission or the gate; safety, structure, the high, critical data, the door's proofs and sizing bind; counted per decision, not per coin]"

    private fun applyLane8577(c: CandidateAdmission8136.Candidate8136, d0: CandidateAdmission8136.Decision8136,
                              highRefusal8582: String?): CandidateAdmission8136.Decision8136 {
        if (!enabled8577 || !c.live) return d0
        val d = pickDecision9022(c, d0)   // V5.0.9022
        val qualified = CandidateAdmission8136.specialistQualified8539(c)
        val costKnown = c.costPct.isFinite() && c.costPct >= 0.0
        if (!d.admitted && bindingAdmission8577(d, costKnown)) return d.copy(laneQualified8577 = qualified)
        // V5.0.8649 — lane authority changes only the WEIGHT of learned refusals;
        // it must not reverse CandidateAdmission's immutable executable economics.
        // The native opinion does not create a missing move forecast or waive the
        // same net-at-size/margin requirement the executor checks at broadcast.
        if (!d.admitted && !NetEdge8030.entryClears8030(
                d.expectedNetPct + d.costPct, d.costPct, d.costMargin8391)) {
            try { PipelineHealthCollector.labelInc("LANE_AUTHORITY_ECONOMICS_STILL_BIND_8649_${c.lane.uppercase()}") } catch (_: Throwable) {}
            return d.copy(laneQualified8577 = qualified)
        }
        if (costKnown && d.expectedNetPct.isFinite() && d.expectedNetPct <= 0.0) {
            return d.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE, reason = "EXECUTION_NET_NONPOSITIVE_8629",
                specific = true, trace = d.trace + "netAfterCost=${d.expectedNetPct} cost=${c.costPct}", laneQualified8577 = qualified)
        }
        // A paper lane aggregate describes experiments across many setups. It
        // cannot replace this candidate's safety, setup, timing and cost verdict.
        // Keep it in the record so its counterfactual remains measurable.
        val paperLosing = try { MemePaperTrader8544.laneProvenLosing8608(c.lane) } catch (_: Throwable) { false }
        val resolved = withPaperPrior(d, paperLosing)
        // V5.0.8582 — a LIVE meme entry at the high is refused whatever admitted it (lane opinion, edge, generic prior).
        if (highRefusal8582 != null) {
            try { PipelineHealthCollector.labelInc("${highRefusal8582}_${c.lane.uppercase()}") } catch (_: Throwable) {}
            return d.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE, reason = highRefusal8582, specific = true,
                trace = d.trace + "entryState=$highRefusal8582", laneQualified8577 = qualified)
        }
        return if (qualified) resolved.copy(laneQualified8577 = true) else resolved
    }

    /** LiveEdgeGate7877.liveRefusal (LIVE): is the gate's refusal [reason] advisory for this lane-qualified candidate? */
    // V5.0.8604 — advisory for every ADMITTED LIVE candidate (the paper-equivalent admission), not only a specialist-qualified
    // one: the paper book buys generic-prior admits too, and the gate's learned doors are the layer it never had.
    fun advisoryAtGate8577(reason: String?, admission: CandidateAdmission8136.Decision8136?): Boolean =
        enabled8577 && admission?.admitted == true && !reason.isNullOrBlank() &&
            (!binding8577(reason) || PaperMirror8757.passesAtGate8757(reason, admission) || pickPassesAtGate9022(reason, admission))   // V5.0.8757, V5.0.9022

    private val advisory8577 = ConcurrentHashMap<String, AtomicLong>()
    private val admittedByLane8577 = ConcurrentHashMap<String, AtomicLong>()
    private val total8577 = AtomicLong(0L)

    fun noteAdvisory8577(reason: String, lane: String) {
        val door = reason.substringBefore(':').take(60)
        advisory8577.computeIfAbsent(door) { AtomicLong() }.incrementAndGet()
        admittedByLane8577.computeIfAbsent(lane.trim().uppercase()) { AtomicLong() }.incrementAndGet()
        total8577.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LANE_AUTHORITY_ADVISORY_8577_$door") } catch (_: Throwable) {}
    }

    fun advisoryCount8577(door: String): Long = advisory8577[door]?.get() ?: 0L

    /** Brief: the doors lane authority overrode this session, most often first, and the lanes it admitted through them. */
    fun statusLine8577(): String {
        val doors = advisory8577.entries.sortedByDescending { it.value.get() }.take(10)
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val lanes = admittedByLane8577.entries.sortedByDescending { it.value.get() }
            .joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        return "laneAuthority8577[on=$enabled8577 overrides=${total8577.get()} doors{$doors} lanes{$lanes} rule=V3+lane qualify; safety/structure/minimum/kill veto; a learned door binds once its own record protects (8715)]"
    }
}
