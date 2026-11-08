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
        "MEME_BUYS_THE_HIGH_8582", "LIVE_STAGE_LOSES_8626",
        // V5.0.8627 — a runner lane's live buy of a coin more than a day old binds too (memeEntryRefusal8626).
        "LIVE_RUNNER_STALE_COIN_8627", "EXECUTION_NET_NONPOSITIVE_8629",
        "SAFETY_REFUSED", "STRUCTURAL_RISK", "RUG", "FREEZE", "MINT_AUTH", "TOKEN_2022", "TOKEN2022", "T22_", "HOSTILE",
        "HONEYPOT", "SELLAB", "UNSELLABLE", "MAYHEM", "BLACKLIST", "HARD_BLOCK", "LIQ_TOO_LOW", "LIQUIDITY_FLOOR",
        "LIQUIDITY_COLLAPSE", "LANE_LIQ", "EXIT_SAFE", "IDENTITY", "PRICE_BASIS", "BASIS_PROOF", "UNTRUSTED_MARK", "WALLET",
        "BLIND", "BALANCE", "LEDGER_REJECT", "LEDGER_COMMIT", "LEDGER_BASIS", "LEDGER_PRECHECK", "DUPLICATE", "SINGLE_FLIGHT", "KILL_SWITCH", "HALT", "AT_LAUNCH_CAP", "DEAD_LAUNCH",
        "EMPTY_CURVE", "EARLY_LAUNCH", "ROUTE_MIN", "SIZE_NOT_EXECUTABLE", "NON_ECONOMIC_APPROVAL", "QUARANT", "REBUY",
        // V5.0.8606 — this bot's own 24 h loss locks bind at the gate as they do at PreTradeHardGate: 19:23 dump, admitted locked
        // mints were ticketed, refused PRETRADE_BIG_LOSER_8130 after the ticket, backed off and re-ticketed (56 BACKOFF_ENDS_TICKET).
        "BIG_LOSER", "REPEAT_LOSER", "PAPER_LANE_PROVEN_LOSING", "LIVE_COIN_STATE_UNPAID", "LIVE_POCKET_",
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
        if (try { MemePaperTrader8544.laneProven8572(l) } catch (_: Throwable) { false }) return null
        // V5.0.8616 — a lane whose paper book makes money the runner way (MemePaperTrader8544.laneLeads8616) trades as paper does.
        if (try { MemePaperTrader8544.laneLeads8616(l) } catch (_: Throwable) { false }) return null
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
     * (all meme lanes 3.0 % vs 1.7 %). A launch being bought is up in its first 5 minutes by definition. The two legs that
     * do separate losers stay: at the high after a run (refused -4.7 % / 1.1 % runners vs passed -1.6 % / 2.3 %) and crowded
     * buyers (refused -4.4 % vs passed -0.4 %).
     */
    const val MEME_BUYS_THE_HIGH_8582 = "MEME_BUYS_THE_HIGH_8582"
    private val MEME_LANES_8582 = setOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "DIP_HUNTER",
        "QUALITY", "BLUECHIP", "CORE", "CYCLIC")

    fun memeBuysTheHigh8582(lane: String, pxPeak: Double, runupPct: Double, buyPressurePct: Double): Boolean {
        if (lane.trim().uppercase() !in MEME_LANES_8582) return false
        val atHigh = pxPeak.isFinite() && pxPeak >= 0.97 && runupPct.isFinite() && runupPct >= 5.0
        val buyersCrowded = buyPressurePct.isFinite() && buyPressurePct >= 60.0
        return atHigh || buyersCrowded
    }

    // superseded by V5.0.8626 — 8604 deleted the runtime read that fed [memeBuysTheHigh8582] into LIVE so live would "decide
    // like the paper book". That book's profit was lost-mark closes at stale marks (8622: 198 of 241 closes, +6.3 SOL, while
    // every priced exit lost -0.6 SOL; fixed 8623), and live went on buying tops: 14:12 dump runnerAdmits8297 live
    // LOSS_BOUGHT_TOP=40 of 63. The read is restored below as [memeEntryRefusal8626], with the coin's stage.

    /**
     * V5.0.8626 §NO_LIVE_BUY_AT_THE_PEAK_OR_IN_THE_DUMP — the bot's own forward record by the coin's stage at decision
     * (14:12 dump, 5-min executable net): PEAK_EXHAUSTION SHITCOIN n23 -52.1 %, MOONSHOT n24 -34.2 %, EXPRESS n21 -20.0 %,
     * PROJECT_SNIPER n13 -49.9 %, MANIPULATED n16 -14.7 %, DIP_HUNTER n8 -33.0 %; DUMPING n50 -33.0 %, n55 -38.5 %, n56 -39.5 %,
     * n22 -44.4 %, n46 -39.2 %, n11 -51.9 % — every lane, both stages, and they are ~3/4 of what the lanes are offered. LAUNCH
     * and BREAKOUT were the positive cells. A live meme entry in either stage is refused unless that lane's own record at that
     * stage has turned: n >= 30 and mean - se > 0. Unknown stage never refuses.
     */
    const val LIVE_STAGE_LOSES_8626 = "LIVE_STAGE_LOSES_8626"
    private val LOSING_STAGES_8626 = setOf("PEAK_EXHAUSTION", "DUMPING")

    /** Pure: is a live entry at [stage] refused, given the lane's own record there (n, mean %, se %)? */
    fun stageLoses8626(stage: String?, n: Int, mean: Double, se: Double): Boolean {
        val st = stage?.trim()?.uppercase() ?: return false
        return st in LOSING_STAGES_8626 && !(n >= 30 && mean.isFinite() && se.isFinite() && mean - se > 0.0)
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
        val l = lane.trim().uppercase()
        val age8627 = try { CanonicalTokenBirthTime7440.launchAgeMs7767(ts, nowMs) } catch (_: Throwable) { null }
        if (runnerCoinStale8627(l, age8627)) return LIVE_RUNNER_STALE_COIN_8627
        if (l !in MEME_LANES_8582) return null
        val f = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.features(ts, nowMs) } catch (_: Throwable) { null }
        if (f != null && memeBuysTheHigh8582(l, f.pxPeak, f.runup, f.bp)) return MEME_BUYS_THE_HIGH_8582
        val st = if (stage.isNullOrBlank()) null else try { ForwardReturnLabeler7731.stageStatFor7928(l, stage) } catch (_: Throwable) { null }
        return if (stageLoses8626(stage, st?.n60 ?: 0, st?.meanNet60Pct ?: Double.NaN, st?.stderr60Pct ?: Double.NaN))
            LIVE_STAGE_LOSES_8626 else null
    }

    /** Pure: does [reason] bind under lane authority? Everything not listed is a learned door, advisory for a qualified coin. */
    fun binding8577(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.uppercase()
        if (LEARNED_WAITS_8604.any { r.contains(it) }) return false
        return BINDING_8577.any { r.contains(it) }
    }

    /**
     * Pure: the admission-level refusals that stay binding. A DATA_INCOMPLETE defer binds only as the executable minimum
     * (stale price, a critical fact unobserved, unknown cost); the entry-wait / move-unread variants and the falling-hour
     * wait are learned doors. [costKnown]: the candidate's round-trip cost is a finite, non-negative figure.
     */
    fun bindingAdmission8577(d: CandidateAdmission8136.Decision8136, costKnown: Boolean): Boolean {
        if (d.reason == "DATA_INCOMPLETE_DEFER_8136") {
            if (!costKnown) return true
            return d.trace.any { line ->
                line.startsWith("criticalUnobserved8170=") ||
                    (line.startsWith("missing=") && line.removePrefix("missing=").split(',').any { it.trim() == "price" })
            }
        }
        if (d.reason == CandidateAdmission8136.FALLING_HOUR_DEFER_8418) return false
        return binding8577(d.reason)
    }

    /**
     * CandidateAdmission8136.assess8136 (LIVE): the lane-qualified candidate's refusal by a learned door becomes an ADMIT
     * carrying that door as advisory; a binding refusal and a paper / unqualified candidate pass through unchanged. Every
     * decision of a qualified LIVE candidate is marked [CandidateAdmission8136.Decision8136.laneQualified8577] for the gate.
     */
    fun apply8577(c: CandidateAdmission8136.Candidate8136, d: CandidateAdmission8136.Decision8136,
                  highRefusal8582: String? = null, launchPhase8609: String? = null,
                  pocket8610: PocketLedger8610.Call? = null): CandidateAdmission8136.Decision8136 {
        val r = applyLane8577(c, d, highRefusal8582, pocket8610)
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
                              highRefusal8582: String?, pocket8610: PocketLedger8610.Call? = null): CandidateAdmission8136.Decision8136 {
        if (!enabled8577 || !c.live) return d
        val qualified = CandidateAdmission8136.specialistQualified8539(c)
        val costKnown = c.costPct.isFinite() && c.costPct >= 0.0
        if (!d.admitted && bindingAdmission8577(d, costKnown)) return d.copy(laneQualified8577 = qualified)
        if (costKnown && d.expectedNetPct.isFinite() && d.expectedNetPct <= 0.0) {
            return d.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE, reason = "EXECUTION_NET_NONPOSITIVE_8629",
                specific = true, trace = d.trace + "netAfterCost=${d.expectedNetPct} cost=${c.costPct}", laneQualified8577 = qualified)
        }
        // V5.0.8609 §ONE_MARKET_EDGE_FOR_EVERY_LANE — 7,491 unique coin-minutes across 13 edge exports, split by time: the coin
        // state that pays is EXPANDING (+4.6 % +-1.6, early +1.5 % / late +9.4 %; under 1 min +8.5 % +-2.7, n748). Everything
        // else loses in every lane that saw it: MATURE -2.6 % (n3709), POST_PUMP_FADE -3.8 %, IGNITION -12.0 %, coins a day or
        // older -3.5 % +-0.3 (n2746, the desks' whole mandate). Live buys a coin only in that state, whichever lane found it;
        // PROJECT_SNIPER also keeps PRE_IGNITION (its flow proof: +46 % proven vs -14 % not). A lane whose own paper record is
        // PROVEN (laneProven8572) trades whatever paper trades. The paper book is still fed every candidate. (Applied to the final
        // ADMIT in apply8577; V5.0.8610 — PocketLedger8610 decides once seeded, this state rule is the fallback.)
        // V5.0.8608 §LIVE_TRADES_WHAT_PAPER_PROVES — live decides like the paper book; where that book has PROVEN the lane loses
        // (MemePaperTrader8544.laneProvenLosing8608) live does not spend the wallet on it.
        // V5.0.8615 — the coin's own evidence outranks its lane's average: a coin PocketLedger8610 scores PAYS is bought even in a
        // lane whose paper book proves a loss. 03:50 dump (8613, 9 min): this lane ban refused 1,669 MOONSHOT / EXPRESS / QUALITY /
        // CASHGEN candidates, so the ledger could never pick a winner inside them (the lane's refused record: -16.2 % n35).
        if (pocket8610 != PocketLedger8610.Call.PAYS && (try { MemePaperTrader8544.laneProvenLosing8608(c.lane) } catch (_: Throwable) { false })) {
            // V5.0.8622 — re-armed on its own (the 8609 / 8610 coin-state and pocket rules stay report-only). 09:44 dump (8621,
            // 65 min): paper MOONSHOT n43 -10.2 % +-4.6 PF0.28 on the very coins live admitted (TUNING: MOONSHOT|LIVE_ADMIT n26
            // -17.5 %); live MOONSHOT took 131 tickets for 3 buys and its sealed reservations held the 0.17 SOL wallet while
            // SHITCOIN (paper n24 +40.2 % on live-admitted coins) was refused ROUTE_MIN_EXCEEDS_TRADEABLE_8153 (565 across lanes).
            try { PipelineHealthCollector.labelInc("PAPER_LANE_PROVEN_LOSING_8608_${c.lane.uppercase()}") } catch (_: Throwable) {}
            return d.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE, reason = PAPER_LOSING_8608, specific = true,
                trace = d.trace + "paperLaneProvenLosing8608", laneQualified8577 = false)
        }
        // V5.0.8582 — a LIVE meme entry at the high is refused whatever admitted it (lane opinion, edge, generic prior).
        if (highRefusal8582 != null) {
            try { PipelineHealthCollector.labelInc("${highRefusal8582}_${c.lane.uppercase()}") } catch (_: Throwable) {}
            return d.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE, reason = highRefusal8582, specific = true,
                trace = d.trace + "entryState=$highRefusal8582", laneQualified8577 = qualified)
        }
        if (!qualified) return d
        if (d.admitted) return d.copy(laneQualified8577 = true)
        noteAdvisory8577(d.reason, c.lane)
        return d.copy(
            terminal = CandidateAdmission8136.Terminal8136.ADMIT, reason = REASON_8577, specific = false,
            chosenSetup = d.chosenSetup ?: c.nativeSetup8213 ?: c.selectedSetup,
            trace = d.trace + "advisory8577=${d.reason} laneQualified=${c.nativeSetup8213 ?: c.lane} generic=${"%.1f".format(c.genericScore)} floor=${"%.1f".format(c.genericFloor)}",
            basis = "$REASON_8577:${c.lane}:${c.nativeSetup8213 ?: "-"}",
            laneQualified8577 = true,
        )
    }

    /** LiveEdgeGate7877.liveRefusal (LIVE): is the gate's refusal [reason] advisory for this lane-qualified candidate? */
    // V5.0.8604 — advisory for every ADMITTED LIVE candidate (the paper-equivalent admission), not only a specialist-qualified
    // one: the paper book buys generic-prior admits too, and the gate's learned doors are the layer it never had.
    fun advisoryAtGate8577(reason: String?, admission: CandidateAdmission8136.Decision8136?): Boolean =
        enabled8577 && !reason.isNullOrBlank() && (admission?.laneQualified8577 == true || admission?.admitted == true) &&
            !binding8577(reason)

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
        return "laneAuthority8577[on=$enabled8577 overrides=${total8577.get()} doors{$doors} lanes{$lanes} rule=V3+lane qualify; safety/structure/minimum/kill veto; learned doors advisory]"
    }
}
