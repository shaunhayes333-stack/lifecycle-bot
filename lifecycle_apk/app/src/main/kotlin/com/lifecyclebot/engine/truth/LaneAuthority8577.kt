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
        if (pocket != null) return if (pocket == PocketLedger8610.Call.PAYS) null else if (pocket == PocketLedger8610.Call.LOSES) POCKET_LOSES_8610 else POCKET_UNPROVEN_8610
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
    fun memeEntryRefusal8626(ts: com.lifecyclebot.data.TokenState, lane: String, stage: String?, nowMs: Long): String? {
        val first8724 = memeEntryState8696(ts, lane, stage, nowMs) ?: return null
        // V5.0.8723 / 8724 — the lane's own record of the coin's market cell (PayingCell8723) answers the lane-wide and broader
        // entry-state priors ([CELL_ANSWERS_8724]). The coin's own chart shapes and the losing record of the setup it trades
        // as are narrower evidence: they are read again and still refuse.
        val why = if (first8724 in CELL_ANSWERS_8724 && (try { PayingCell8723.pays8723(ts, lane, nowMs) } catch (_: Throwable) { false })) {
            try { PipelineHealthCollector.labelInc("${first8724}_ANSWERED_BY_MARKET_CELL_8724") } catch (_: Throwable) {}
            narrowerRefusal8724(ts, lane.trim().uppercase(), nowMs) ?: return null
        } else first8724
        // V5.0.8696 — every entry-state door is learned by the one rule: it stands aside once its own graded refusals prove
        // it refuses winners.
        if (GateOutcome8418.selectionGateRetired8696("CANDIDATE_ADMISSION_8136_$why", lane)) return null
        try { PipelineHealthCollector.labelInc("${why}_BOUND_8696") } catch (_: Throwable) {}
        return why
    }

    /** The entry-state doors a lane's paying market cell answers: priors over more coins than the cell holds. */
    private val CELL_ANSWERS_8724: Set<String> get() = setOf(LIVE_ORIGIN_COHORT_LOSES_8703, MEME_BUYS_THE_HIGH_8582, LIVE_STAGE_UNREAD_8711, LIVE_STAGE_LOSES_8626)

    /** The refusals narrower than a market cell: the coin's own avoid shape, else the losing record of the setup it trades as. */
    private fun narrowerRefusal8724(ts: com.lifecyclebot.data.TokenState, l: String, nowMs: Long): String? {
        if (l !in MEME_LANES_8582) return null
        return expertAvoid8692(ts, nowMs) ?: setupLoses8714(ts, l, nowMs)
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
    private fun originLoses8703(ts: com.lifecyclebot.data.TokenState, lane: String, nowMs: Long): Boolean {
        val cohort = try { MarketCohort8701.provenLosing8703(ts) } catch (_: Throwable) { null } ?: return false
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

    private fun applyLane8577(c: CandidateAdmission8136.Candidate8136, d: CandidateAdmission8136.Decision8136,
                              highRefusal8582: String?): CandidateAdmission8136.Decision8136 {
        if (!enabled8577 || !c.live) return d
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
        enabled8577 && admission?.admitted == true && !reason.isNullOrBlank() && !binding8577(reason)

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
