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
        "SAFETY_REFUSED", "STRUCTURAL_RISK", "RUG", "FREEZE", "MINT_AUTH", "TOKEN_2022", "TOKEN2022", "T22_", "HOSTILE",
        "HONEYPOT", "SELLAB", "UNSELLABLE", "MAYHEM", "BLACKLIST", "HARD_BLOCK", "LIQ_TOO_LOW", "LIQUIDITY_FLOOR",
        "LIQUIDITY_COLLAPSE", "LANE_LIQ", "EXIT_SAFE", "IDENTITY", "PRICE_BASIS", "BASIS_PROOF", "UNTRUSTED_MARK", "WALLET",
        "BLIND", "BALANCE", "LEDGER_REJECT", "LEDGER_COMMIT", "LEDGER_BASIS", "LEDGER_PRECHECK", "DUPLICATE", "SINGLE_FLIGHT", "KILL_SWITCH", "HALT", "AT_LAUNCH_CAP", "DEAD_LAUNCH",
        "EMPTY_CURVE", "EARLY_LAUNCH", "ROUTE_MIN", "SIZE_NOT_EXECUTABLE", "NON_ECONOMIC_APPROVAL", "QUARANT", "REBUY",
        // V5.0.8606 — this bot's own 24 h loss locks bind at the gate as they do at PreTradeHardGate: 19:23 dump, admitted locked
        // mints were ticketed, refused PRETRADE_BIG_LOSER_8130 after the ticket, backed off and re-ticketed (56 BACKOFF_ENDS_TICKET).
        "BIG_LOSER", "REPEAT_LOSER", "PAPER_LANE_PROVEN_LOSING", "LIVE_COIN_STATE_UNPAID",
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

    /** Pure-ish (V5.0.8609): null when a LIVE buy in [lane] at [launchPhase] is inside the market's measured paying state. */
    private fun liveCoinStateRefusal8609(lane: String, launchPhase: String?): String? {
        val l = lane.trim().uppercase()
        if (try { MemePaperTrader8544.laneProven8572(l) } catch (_: Throwable) { false }) return null
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

    // V5.0.8604 — memeHighRefusal8582 (the runtime read that fed this veto into every LIVE admission) is deleted: LIVE now
    // decides like the paper book. [memeBuysTheHigh8582] stays as the pure read of the two data-backed legs.

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
                  highRefusal8582: String? = null, launchPhase8609: String? = null): CandidateAdmission8136.Decision8136 {
        val r = applyLane8577(c, d, highRefusal8582)
        if (!enabled8577 || !c.live || !r.admitted) return r
        val why = liveCoinStateRefusal8609(c.lane, launchPhase8609) ?: return r
        try { PipelineHealthCollector.labelInc("${why}_${c.lane.uppercase()}") } catch (_: Throwable) {}
        return r.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE, reason = why, specific = true,
            trace = r.trace + "coinState8609=${launchPhase8609 ?: "-"}", laneQualified8577 = false)
    }

    private fun applyLane8577(c: CandidateAdmission8136.Candidate8136, d: CandidateAdmission8136.Decision8136,
                              highRefusal8582: String?): CandidateAdmission8136.Decision8136 {
        if (!enabled8577 || !c.live) return d
        // V5.0.8609 §ONE_MARKET_EDGE_FOR_EVERY_LANE — 7,491 unique coin-minutes across 13 edge exports, split by time: the coin
        // state that pays is EXPANDING (+4.6 % +-1.6, early +1.5 % / late +9.4 %; under 1 min +8.5 % +-2.7, n748). Everything
        // else loses in every lane that saw it: MATURE -2.6 % (n3709), POST_PUMP_FADE -3.8 %, IGNITION -12.0 %, coins a day or
        // older -3.5 % +-0.3 (n2746, the desks' whole mandate). Live buys a coin only in that state, whichever lane found it;
        // PROJECT_SNIPER also keeps PRE_IGNITION (its flow proof: +46 % proven vs -14 % not). A lane whose own paper record is
        // PROVEN (laneProven8572) trades whatever paper trades. The paper book is still fed every candidate.
        }
        // V5.0.8608 §LIVE_TRADES_WHAT_PAPER_PROVES — live decides like the paper book; where that book has PROVEN the lane loses
        // (MemePaperTrader8544.laneProvenLosing8608) live does not spend the wallet on it.
        if (try { MemePaperTrader8544.laneProvenLosing8608(c.lane) } catch (_: Throwable) { false }) {
            try { PipelineHealthCollector.labelInc("PAPER_LANE_PROVEN_LOSING_8608_${c.lane.uppercase()}") } catch (_: Throwable) {}
            return d.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE, reason = PAPER_LOSING_8608, specific = true,
                trace = d.trace + "paperLaneProvenLosing8608", laneQualified8577 = false)
        }
        // V5.0.8582 — a LIVE meme entry at the high is refused whatever admitted it (lane opinion, edge, generic prior).
        if (highRefusal8582 != null && d.terminal != CandidateAdmission8136.Terminal8136.REFUSE) {
            try { PipelineHealthCollector.labelInc("${highRefusal8582}_${c.lane.uppercase()}") } catch (_: Throwable) {}
            return d.copy(terminal = CandidateAdmission8136.Terminal8136.REFUSE, reason = highRefusal8582, specific = true,
                trace = d.trace + "buysTheHigh8582 (pullback / dip entries only)", laneQualified8577 = CandidateAdmission8136.specialistQualified8539(c))
        }
        if (!CandidateAdmission8136.specialistQualified8539(c)) return d
        if (d.admitted) return d.copy(laneQualified8577 = true)
        val costKnown = c.costPct.isFinite() && c.costPct >= 0.0
        if (bindingAdmission8577(d, costKnown)) return d.copy(laneQualified8577 = true)
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
