package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7740 §A_COUNCIL_NOT_A_CHAIN_OF_OVERRIDES.
 *
 * Operator: "it needs to stop over riding good logic decisions and choices.
 * those are duplicate or parties of disagreeing logic that are meant to work as
 * a council. again I want the traders cheat sheet used as a baseline of common
 * sense."
 *
 * The 5.0.7738 audit found the members overwriting each other instead of
 * voting: the V3 trunk buys under MOONSHOT/SHITCOIN/SNIPER/CORE without that
 * lane's own evaluator; a lane rewrites a generic WAIT to BUY before the gate
 * reads it; the score-band expectancy refusal returns "allow" outright in live
 * (LIVE_EXPECTANCY_REJECT_BYPASSED 6456 in 396 s); consensus soft blocks,
 * proven-dead and train-first buckets become 0.01-0.02 SOL trades; an
 * extend-hold heuristic with no record skips the stop above -20%; an ELITE
 * profile holds a Moonshot through its stop to -40% on a lane at 3/13, EV -10.7%.
 *
 * The Field Manual sets the rules of the council (§12): "One selected strategy
 * owns the live trade"; "Small samples should remain uncertain and shrink
 * toward broader evidence rather than becoming hard rules"; and "the stack
 * earns authority only when position-bound, mode-matched, net-of-cost
 * finalized outcomes show that a particular source, lane, setup, regime, or
 * tactic improves results out of sample."
 *
 * ENTRY VOTES (live; paper hears the votes and is never refused):
 *   BASELINE   the cheat sheet: FieldManual7715 pass conditions and the
 *              TradePlan7739 plan card. Binding from trade one; it runs before
 *              this council in FinalDecisionGate.
 *   OWNER      the lane that owns the trade, through its own evaluator
 *              (SpecialistBrainBridge7542). The owner's mandate refusal binds:
 *              no other path buys under its name. Operational refusals
 *              (already holding, cooldown, daily limit) are its own too.
 *   EXPECTANCY the lane's score band, once it has [ScoreExpectancyTracker]'s
 *              mature sample: a net mean under its reject bar binds.
 *   ORACLE     binds only once its record shows it discriminates: its refused
 *              closes did worse than its admitted closes by the proof margin.
 *              A refuser whose refusals merely lose as the market loses has
 *              not shown judgement and stays advisory.
 * A member without earned authority still votes; its vote is counted and
 * reported, and the existing shaping layers keep reading it as size.
 *
 * EXIT VOTES: a vote to hold a position through its stop is earned only by a
 * positive measured record for that lane ([holdThroughStopEarned]).
 */
object Council7740 {
    private const val ORACLE_STAMP_MAX_AGE_MS_7740 = 3L * 60_000L
    private const val HOLD_EARNED_MIN_N_7740 = 10

    enum class Member { OWNER, EXPECTANCY, ORACLE }
    enum class Vote { ADMIT, REFUSE, ABSTAIN }
    data class Ballot(val member: Member, val vote: Vote, val binding: Boolean, val why: String)

    private val binding = ConcurrentHashMap<Member, AtomicLong>()
    private val advisoryRefusals = ConcurrentHashMap<Member, AtomicLong>()
    private val refusedLive = AtomicLong(0)
    private val admittedLive = AtomicLong(0)
    private val holdVotesRefused = AtomicLong(0)
    private val holdVotesHonoured = AtomicLong(0)

    private val OWNER_KEYS_7740 = setOf(
        "QUALITY", "BLUECHIP", "SHITCOIN", "EXPRESS", "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER",
        "MANIPULATED", "TREASURY", "CASHGEN", "CYCLIC", "CORE",
    )

    /** FDG lane name to the specialist bridge's owner key (via CanonicalLaneIdentity6506), or null. */
    fun ownerKey(lane: String): String? = CanonicalLaneIdentity6506.canonical(lane).takeIf { it in OWNER_KEYS_7740 }

    /** Pure: the decision from the ballots. The first binding refusal decides; otherwise admit. */
    fun resolve(ballots: List<Ballot>): Ballot? = ballots.firstOrNull { it.vote == Vote.REFUSE && it.binding }

    private fun ownerBallot(ts: TokenState, lane: String): Ballot {
        val key = ownerKey(lane) ?: return Ballot(Member.OWNER, Vote.ABSTAIN, false, "no_specialist_for_$lane")
        val snap = try { com.lifecyclebot.engine.SpecialistBrainBridge7542.evaluate(ts) } catch (_: Throwable) { null }
        val o = snap?.opinions?.get(key) ?: return Ballot(Member.OWNER, Vote.ABSTAIN, false, "owner_unavailable")
        if (!o.authoritative) return Ballot(Member.OWNER, Vote.ABSTAIN, false, "owner_error:${o.reason.take(40)}")
        return if (o.eligible) Ballot(Member.OWNER, Vote.ADMIT, true, "owner_${key}_admits")
        else Ballot(Member.OWNER, Vote.REFUSE, true, "owner_${key}_refuses:${o.reason.take(60)}")
    }

    private fun expectancyBallot(lane: String, laneScore: Int): Ballot {
        val mean = try { com.lifecyclebot.engine.ScoreExpectancyTracker.bucketMean(lane.trim().uppercase(), laneScore) } catch (_: Throwable) { null }
            ?: return Ballot(Member.EXPECTANCY, Vote.ABSTAIN, false, "band_immature")
        return if (mean < EXPECTANCY_REJECT_MEAN_PCT_7740) Ballot(Member.EXPECTANCY, Vote.REFUSE, true, "band_mean_${"%.1f".format(mean)}pct")
        else Ballot(Member.EXPECTANCY, Vote.ADMIT, true, "band_mean_${"%.1f".format(mean)}pct")
    }

    /** Mirrors ScoreExpectancyTracker's own reject bar (REJECT_MEAN_PNL_PCT). */
    private const val EXPECTANCY_REJECT_MEAN_PCT_7740 = -8.0

    private fun oracleBallot(ts: TokenState, nowMs: Long): Ballot {
        val v = try { OracleEdgeProof7263.latestVerdict7740(ts.mint, ORACLE_STAMP_MAX_AGE_MS_7740, nowMs) } catch (_: Throwable) { null }
            ?: return Ballot(Member.ORACLE, Vote.ABSTAIN, false, "no_recent_forecast")
        val earned = try { OracleEdgeProof7263.discriminates7740() } catch (_: Throwable) { false }
        val vote = if (v == PredictiveEntryOracle6915.Verdict.REFUSE) Vote.REFUSE else Vote.ADMIT
        return Ballot(Member.ORACLE, vote, earned, "oracle_${v.name}${if (earned) "_earned" else "_unproven"}")
    }

    /** Live-entry verdict of the council, or null to admit. Paper hears the votes and is never refused. */
    fun liveBlockReason(ts: TokenState, lane: String, laneScore: Int, paper: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        val ballots = listOf(ownerBallot(ts, lane), expectancyBallot(lane, laneScore), oracleBallot(ts, nowMs))
        for (b in ballots) if (b.vote == Vote.REFUSE && !b.binding) advisoryRefusals.computeIfAbsent(b.member) { AtomicLong(0) }.incrementAndGet()
        if (paper) return null
        val decider = resolve(ballots)
        if (decider == null) {
            admittedLive.incrementAndGet()
            return null
        }
        refusedLive.incrementAndGet()
        binding.computeIfAbsent(decider.member) { AtomicLong(0) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("COUNCIL_REFUSED_7740_${decider.member.name}")
            if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("COUNCIL_REFUSED_7740", ts.mint)) {
                ForensicLogger.lifecycle(
                    "COUNCIL_REFUSED_7740",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=$lane decider=${decider.member.name} why=${decider.why} " +
                        "ballots=${ballots.joinToString(";") { "${it.member.name}:${it.vote.name}${if (it.binding) "!" else "?"}:${it.why}" }}",
                )
            }
        } catch (_: Throwable) {}
        return "COUNCIL_REFUSED_7740_${decider.member.name}:${decider.why}"
    }

    /**
     * Exit council: may [lane] hold a position through its stop on a member's
     * say-so? Only on a positive measured record for the lane at
     * [HOLD_EARNED_MIN_N_7740] closes or more.
     */
    fun holdThroughStopEarned(lane: String): Boolean {
        val s = try { com.lifecyclebot.engine.ScoreExpectancyTracker.laneStats7380(lane.trim().uppercase()) } catch (_: Throwable) { null }
        val earned = s != null && s.first >= HOLD_EARNED_MIN_N_7740 && s.third > 0.0
        if (earned) holdVotesHonoured.incrementAndGet() else holdVotesRefused.incrementAndGet()
        try { PipelineHealthCollector.labelInc(if (earned) "COUNCIL_HOLD_THROUGH_STOP_HONOURED_7740" else "COUNCIL_HOLD_THROUGH_STOP_REFUSED_7740") } catch (_: Throwable) {}
        return earned
    }

    fun statusLine(): String =
        "live[admitted=${admittedLive.get()} refused=${refusedLive.get()}] bindingRefusals[${binding.entries.joinToString(",") { "${it.key.name}=${it.value.get()}" }.ifBlank { "none" }}] " +
            "advisoryRefusals[${advisoryRefusals.entries.joinToString(",") { "${it.key.name}=${it.value.get()}" }.ifBlank { "none" }}] " +
            "holdThroughStop[honoured=${holdVotesHonoured.get()} refused=${holdVotesRefused.get()}] " +
            "oracleEarned=${try { OracleEdgeProof7263.discriminates7740() } catch (_: Throwable) { false }}"
}
