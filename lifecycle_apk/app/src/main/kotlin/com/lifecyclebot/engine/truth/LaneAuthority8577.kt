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
        "DATA_", "STALE", "_FAILED", "GENERIC_SCORE_FLOOR",
        // V5.0.8582 — buying a meme coin at its high is proven to lose (edge report below): these doors bind again.
        // V5.0.8603 — CHASE_AT_HIGH removed: the 8555 setup guard behind it is deleted (it kept the worse half; LanePlaybook7907).
        "INTO_HIGH", "CHASING_SPIKE", "CHASE_EXTENDED", "NEAR_HIGH", "MEME_BUYS_THE_HIGH_8582",
    )

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

    /** Reads [ts]'s playbook features (the edge model's own frame); a read that fails never refuses. */
    fun memeHighRefusal8582(ts: com.lifecyclebot.data.TokenState, lane: String, nowMs: Long): String? {
        val f = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.features(ts, nowMs) } catch (_: Throwable) { null } ?: return null
        return if (memeBuysTheHigh8582(lane, f.pxPeak, f.runup, f.bp))
            MEME_BUYS_THE_HIGH_8582 else null
    }

    /** Pure: does [reason] bind under lane authority? Everything not listed is a learned door, advisory for a qualified coin. */
    fun binding8577(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.uppercase()
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
                  highRefusal8582: String? = null): CandidateAdmission8136.Decision8136 {
        if (!enabled8577 || !c.live) return d
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
    fun advisoryAtGate8577(reason: String?, admission: CandidateAdmission8136.Decision8136?): Boolean =
        enabled8577 && !reason.isNullOrBlank() && admission?.laneQualified8577 == true && !binding8577(reason)

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
