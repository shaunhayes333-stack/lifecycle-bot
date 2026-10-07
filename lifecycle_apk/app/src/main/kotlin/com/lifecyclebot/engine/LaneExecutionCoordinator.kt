package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510
import com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1099-pre — per-runtime candidate/lane election guard.
 *
 * This is the execution-side half of the lane fan-out repair: for a given
 * runtimeGeneration + mint + candidateVersion, exactly one primary lane may
 * request execution. Other lanes may continue telemetry, but central gates
 * block TradeAuthorizer/FinalExecutionPermit/Executor side effects.
 */
object LaneExecutionCoordinator {
    data class CandidateKey(
        val runtimeGeneration: Long,
        val mint: String,
        val candidateVersion: Long,
    )

    data class Election(
        val key: CandidateKey,
        val primaryLane: String,
        val secondaryTelemetryLane: String? = null,
        val createdAtMs: Long = System.currentTimeMillis(),
        val electionId: String = "",
        val authorityVersion: Long = 0L,
        val sealed: Boolean = false,
    )

    data class Verdict(
        val allowed: Boolean,
        val reason: String,
        val primaryLane: String,
        val candidateVersion: Long,
        val electionId: String = "",
        val authorityVersion: Long = 0L,
    )

    private const val TTL_MS = 30_000L
    private val versionSeq = AtomicLong(0L)
    private val authoritySeq6494 = AtomicLong(0L)
    private val elections = ConcurrentHashMap<String, Election>()
    private val duplicateOpenSuppressed = AtomicLong(0L)
    private val affinities = ConcurrentHashMap<String, Set<String>>()
    // V5.0.7621 — current native-qualified specialists for an exact candidate
    // generation. Source/scanner affinity is a hint; this is the actual desk
    // qualification set produced by ToolkitSignalSheet for this candidate.
    // V5.0.7803 — preserve each specialist's candidate-specific conviction.
    // The old Set<String> discarded the very signal the specialist produced and
    // then let a static priority table dominate ownership.
    private data class QualifiedContest7621(val scores: Map<String, Double>, val stampedAtMs: Long)
    private val qualifiedContests7621 = ConcurrentHashMap<String, QualifiedContest7621>()

    // V5.9.1135 — lane election must be priority-based, not first-caller-wins.
    private val lanePriority = mapOf(
        "MOONSHOT" to 100,
        "SHITCOIN" to 95,
        "EXPRESS" to 93,
        "MANIPULATED" to 90,
        "DIP_HUNTER" to 85,
        "PROJECT_SNIPER" to 80,
        "CRYPTO" to 75,
        "QUALITY" to 70,
        "BLUECHIP" to 60,
        "V3" to 58,
        "STANDARD" to 56,
        "CORE" to 55,
        // V5.0.7616 — these are real specialists, not unknown lanes.
        // Both already inherited the default priority 50. Make that explicit
        // so future default changes cannot silently change their election rank.
        "CYCLIC" to 50,
        "CASHGEN" to 50,
        "TREASURY" to 40,
        "SHADOW" to 10,
    )

    private fun priority(lane: String): Int = lanePriority[lane.uppercase()] ?: 50

    fun registerAffinity(mint: String, lanes: Set<String>) {
        val clean = lanes.map { it.uppercase() }.filter { it.isNotBlank() }.toSet()
        if (clean.isEmpty()) return
        affinities.merge(mint, clean) { old, new -> old + new }
    }

    // V5.0.6841 §LANE_PRIORITY_IGNORED_REALISED_EXPECTANCY — the static table above
    // is the lane-allocation inversion in its literal form. Operator 5.0.6835:
    //   EXPRESS         priority 93 : n=26 WR=3.8%  PnL=-0.8794 SOL avg=-62.2% -> 31 execs
    //   PROJECT_SNIPER  priority 80 : n=19 WR=42.1% PnL=+2.4484 SOL avg=+237.9% -> 0 execs
    // The lane losing money outranked the lane making it by 13 points, and no
    // expectancy, win-rate or PnL term appeared anywhere in the election — the only
    // modifier was a +30 affinity boost. A hardcoded ranking cannot learn, so the
    // book kept routing capital to its worst performer.
    //
    // Re-rank by the same realised-expectancy signal already trusted for sizing and
    // (since 6838) for the admission floor. LaneExpectancyDamper is mode-keyed and
    // reads the clean same-mode terminal leaderboard, so this is the lane's own
    // settled economics, not a heuristic.
    //
    // Bounded so a static ordering still breaks ties and one rough streak cannot
    // fully inseat a lane: multiplier 1.0 is neutral, and the delta clamps to
    // [-35, +20].
    //   EXPRESS        x0.18 -> -32  -> 93 - 32 = 61
    //   PROJECT_SNIPER x1.18 ->  +7  -> 80 +  7 = 87
    // which puts the profitable lane above the bleeding one for the first time.
    private fun expectancyPriorityDelta6841(lane: String): Int = try {
        val mult = com.lifecyclebot.engine.LaneExpectancyDamper.sizeMultiplier(lane)
        if (!mult.isFinite()) 0
        else ((mult - 1.0) * 40.0).coerceIn(-35.0, 20.0).toInt()
    } catch (_: Throwable) { 0 }

    private fun effectivePriority(mint: String, lane: String): Int {
        val laneUpper = lane.uppercase()
        val registryAffinity = try { GlobalTradeRegistry.getLaneAffinity(mint) } catch (_: Throwable) { emptySet() }
        val allAffinity = (affinities[mint] ?: emptySet()) + registryAffinity
        val boost = if (allAffinity.contains(laneUpper)) 30 else 0
        return priority(laneUpper) + boost + expectancyPriorityDelta6841(laneUpper)
    }

    // ── FAIR LANE ROTATION (V5.9.1335) ───────────────────────────────
    // Fairness remains available for pre-seal/fresh elections. Once FDG has
    // published an immutable ExecutionDecisionSnapshot, V5.0.6679 binds this
    // coordinator to that sealed owner instead of re-running any local contest.
    private const val FAIRNESS_DECAY_MS = 120_000L
    private const val FAIRNESS_LEAD_GRACE = 3
    private const val FAIRNESS_PER_LEAD = 6.0
    private val laneWinTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private fun recordPrimaryWin(lane: String) {
        val l = lane.uppercase()
        val now = System.currentTimeMillis()
        val dq = laneWinTimestamps.computeIfAbsent(l) { ArrayDeque() }
        synchronized(dq) {
            dq.addLast(now)
            while (dq.isNotEmpty() && now - dq.first() > FAIRNESS_DECAY_MS) dq.removeFirst()
        }
    }

    private fun recentWins(lane: String): Int {
        val dq = laneWinTimestamps[lane.uppercase()] ?: return 0
        val now = System.currentTimeMillis()
        synchronized(dq) {
            while (dq.isNotEmpty() && now - dq.first() > FAIRNESS_DECAY_MS) dq.removeFirst()
            return dq.size
        }
    }

    private fun minRecentWinsAcross(lanes: Collection<String>): Int =
        lanes.minOfOrNull { recentWins(it) } ?: 0

    private fun qualifiedContestKey7621(mint: String, candidateVersion: Long): String =
        "${mint.trim()}::$candidateVersion"

    /**
     * V5.0.7803 — publish specialist conviction, not just lane membership.
     * Values are candidate-local and do not become execution authority until
     * TradeAuthorizer publishes READY for that same lane/mint/version.
     */
    internal fun registerQualifiedContest7803(
        mint: String,
        candidateVersion: Long,
        laneScores: Map<String, Double>,
    ) {
        if (mint.isBlank() || candidateVersion <= 0L) return
        val clean = laneScores.entries.mapNotNull { (laneRaw, scoreRaw) ->
            val lane = laneRaw.trim().uppercase()
            if (!laneCanOwnExecution6910(lane)) null
            else lane to scoreRaw.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0).orDefault7803(50.0)
        }.toMap()
        if (clean.isEmpty()) {
            qualifiedContests7621.remove(qualifiedContestKey7621(mint, candidateVersion))
            return
        }
        qualifiedContests7621[qualifiedContestKey7621(mint, candidateVersion)] =
            QualifiedContest7621(clean, System.currentTimeMillis())
        try {
            PipelineHealthCollector.labelInc("SPECIALIST_QUALIFIED_CONTEST_PUBLISHED_7621")
            PipelineHealthCollector.labelInc("SPECIALIST_CONVICTION_CONTEST_PUBLISHED_7803")
            clean.keys.forEach { PipelineHealthCollector.labelInc("SPECIALIST_QUALIFIED_CONTEST_7621_$it") }
        } catch (_: Throwable) {}
    }

    private fun Double?.orDefault7803(default: Double): Double = this ?: default

    /** Legacy callers retain neutral conviction; Toolkit uses the 7803 API. */
    internal fun registerQualifiedContest7621(mint: String, candidateVersion: Long, lanes: Collection<String>) =
        registerQualifiedContest7803(mint, candidateVersion, lanes.associate { it to 50.0 })

    private fun currentQualifiedScores7803(mint: String, candidateVersion: Long): Map<String, Double> {
        val key = qualifiedContestKey7621(mint, candidateVersion)
        val q = qualifiedContests7621[key] ?: return emptyMap()
        if (System.currentTimeMillis() - q.stampedAtMs > TTL_MS) {
            qualifiedContests7621.remove(key, q)
            return emptyMap()
        }
        return q.scores
    }

    private fun currentQualifiedContest7621(mint: String, candidateVersion: Long): Set<String> =
        currentQualifiedScores7803(mint, candidateVersion).keys

    private fun readyProposalScores7803(mint: String, candidateVersion: Long): Map<String, Double> = try {
        com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.readyFor(mint, candidateVersion)
            .mapValues { it.value.conviction.coerceIn(0.0, 100.0) }
    } catch (_: Throwable) { emptyMap() }

    private fun qualifiedLanesFor(mint: String, candidateVersion: Long, vararg contesting: String): List<String> {
        // V5.0.7803 — once any specialist is actually READY, only READY desks
        // arbitrate. WATCHING/QUALIFIED desks remain resident and keep observing;
        // they do not steal an executable proposal merely through static priority.
        val ready7803 = readyProposalScores7803(mint, candidateVersion)
        if (ready7803.isNotEmpty()) return ready7803.keys.toList()

        val currentQualified7621 = currentQualifiedContest7621(mint, candidateVersion)
        if (currentQualified7621.isNotEmpty()) return currentQualified7621.toList()

        // Bootstrap only: before a candidate-specific specialist opinion exists,
        // affinity may keep the old path alive, but it is never stronger than a
        // resident qualified/ready specialist set.
        val registryAffinity = try { GlobalTradeRegistry.getLaneAffinity(mint) } catch (_: Throwable) { emptySet() }
        val all = ((affinities[mint] ?: emptySet()) + registryAffinity + contesting.map { it.uppercase() })
            .filter { it.isNotBlank() }
        return if (all.isEmpty()) contesting.map { it.uppercase() } else all.toList()
    }

    /**
     * V5.0.7803 — candidate-specific election score.
     *
     * Primary term: this lane's own current READY conviction, otherwise its
     * current QUALIFIED conviction. Realized expectancy, affinity and fairness
     * are bounded modifiers. The historical static priority table is a tiny
     * deterministic tie-break only; it can no longer overpower strategy fit.
     */
    private fun electionScore7803(
        mint: String,
        candidateVersion: Long,
        lane: String,
        qualified: Collection<String>,
        ready: Map<String, Double>,
        qualifiedScores: Map<String, Double>,
    ): Double {
        val laneUpper = lane.uppercase()
        val base = ready[laneUpper] ?: qualifiedScores[laneUpper] ?: 50.0
        val expectancy = expectancyPriorityDelta6841(laneUpper).coerceIn(-8, 8).toDouble()
        val registryAffinity = try { GlobalTradeRegistry.getLaneAffinity(mint) } catch (_: Throwable) { emptySet() }
        val affinity = if (laneUpper in ((affinities[mint] ?: emptySet()) + registryAffinity)) 2.0 else 0.0
        val floor = minRecentWinsAcross(qualified)
        val lead = (recentWins(laneUpper) - floor - FAIRNESS_LEAD_GRACE).coerceAtLeast(0)
        val fairnessPenalty = (lead * 2.0).coerceAtMost(6.0)
        val staticTieBreak = priority(laneUpper) / 1000.0
        return base + expectancy + affinity - fairnessPenalty + staticTieBreak
    }

    private fun pickFreshPrimary(mint: String, candidateVersion: Long, qualified: List<String>): String? {
        if (qualified.isEmpty()) return null
        val ready = readyProposalScores7803(mint, candidateVersion)
        val q = currentQualifiedScores7803(mint, candidateVersion)
        return qualified.maxByOrNull { electionScore7803(mint, candidateVersion, it, qualified, ready, q) }
    }

    /**
     * V5.0.7820 — native READY owner routing authority.
     *
     * Resident specialist brains can all qualify the same mint, but only one
     * executable owner is allowed for a mint/version. The 7819 runtime proved
     * that READY proposals could exist while the style/fanout lane set omitted
     * the eventual owner, leaving residentReady>0 with ownerSelected=0.
     *
     * Expose the exact same learned/fair READY election used by
     * canRequestExecution so upstream lane routing can guarantee the winning
     * specialist is actually evaluated. This does not authorize, size, seal
     * FDG, or execute anything; it only returns the lane that would win the
     * existing READY contest.
     */
    internal fun preferredReadyOwner7820(
        mint: String,
        candidateVersion: Long = candidateVersionFor(mint),
    ): String? {
        val ready = readyProposalScores7803(mint, candidateVersion)
        if (ready.isEmpty()) return null
        val contenders = ready.keys
            .filter { laneCanOwnExecution6910(it) }
            .filter { ownExecutorRefusal7807(mint, it) == null }
            .filterNot { nativeRefused7774(mint, it) }
            .distinct()
        val winner = pickFreshPrimary(mint, candidateVersion, contenders) ?: return null
        try {
            PipelineHealthCollector.labelInc("NATIVE_READY_OWNER_RESOLVED_7820")
            PipelineHealthCollector.labelInc("NATIVE_READY_OWNER_RESOLVED_7820_$winner")
        } catch (_: Throwable) {}
        return winner
    }

    private fun secondaryFresh7803(
        mint: String,
        candidateVersion: Long,
        qualified: List<String>,
        primary: String,
    ): String? {
        val ready = readyProposalScores7803(mint, candidateVersion)
        val q = currentQualifiedScores7803(mint, candidateVersion)
        return qualified.filter { it != primary }
            .maxByOrNull { electionScore7803(mint, candidateVersion, it, qualified, ready, q) }
    }

    fun candidateVersionFor(mint: String): Long {
        val mode = if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"
        val sealed = try {
            ExecutionDecisionSnapshot6510.latestExecutableForMint7251(mint, mode, TTL_MS)
        } catch (_: Throwable) { null }
        if (sealed != null) return sealed.candidateVersion
        // V5.0.7276 §THE CLOCK IS NOT A NEWER CANDIDATE.
        //
        // 7251 keeps a SEALED executable generation stable across a wall-clock
        // bucket boundary. An FDG allow that had not yet been sealed had no
        // such protection: the allow was recorded under bucket N, the bucket
        // rolled to N+1 before the gate ran, `currentCandidateVersion` read
        // N+1, and the gate — correctly, by 7220's rule — refused to honour
        // an allow for "a superseded candidate". Nobody had superseded it. On
        // 5.0.7274: FDG_ALLOW_STATE_SUPERSEDED_BY_NEWER_CANDIDATE_7220=114
        // against EXEC_GATE allow=57, the largest post-FDG loss in the run.
        //
        // An allowed provisional state younger than the same TTL keeps its
        // version, so the seal that follows lands under the version the gate
        // will ask for. After TTL_MS the bucket advances as before and the
        // next evaluation is fresh; a verdict that stops being an allow drops
        // the latch at once (fdgAllowedAtMs7276 resets to 0).
        val allowed7276 = try {
            ExecutableOpenGate.allowedCandidateVersionWithin7276(mint, TTL_MS)
        } catch (_: Throwable) { null }
        if (allowed7276 != null) {
            try { PipelineHealthCollector.labelInc("CANDIDATE_VERSION_LATCHED_TO_FDG_ALLOW_7276") } catch (_: Throwable) {}
            return allowed7276
        }
        return System.currentTimeMillis() / TTL_MS
    }

    fun elect(
        mint: String,
        lanes: List<String>,
        preferred: String? = null,
        candidateVersion: Long = candidateVersionFor(mint),
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
    ): Election {
        val clean = lanes.map { it.uppercase() }.filter { it.isNotBlank() }.distinct()
        // V5.0.7619 — the coordinator already owns learned expectancy priority,
        // affinity weighting and recent-win fairness, but fresh elect() bypassed
        // all of it by taking clean.firstOrNull(). That made caller/list order an
        // undeclared ownership authority and could starve otherwise-qualified lanes.
        //
        // Preserve an explicit valid preferred lane: the caller may already have a
        // canonical source/style owner. When no explicit preference exists, use the
        // existing learned/fair selector instead of insertion order.
        val explicitPreferred7619 = preferred?.uppercase()?.takeIf { it in clean }
        val primary = explicitPreferred7619 ?: pickFreshPrimary(mint, candidateVersion, clean) ?: "CORE"
        val secondary = secondaryFresh7803(mint, candidateVersion, clean, primary)
        val key = CandidateKey(runtimeGeneration, mint, candidateVersion)
        val mapKey = mapKey(key)
        val now = System.currentTimeMillis()
        val old = elections[mapKey]
        if (old != null && now - old.createdAtMs <= TTL_MS) return old
        val authorityVersion6494 = authoritySeq6494.incrementAndGet()
        val e = Election(
            key = key,
            primaryLane = primary,
            secondaryTelemetryLane = secondary,
            createdAtMs = now,
            electionId = "${runtimeGeneration}:${candidateVersion}:$authorityVersion6494",
            authorityVersion = authorityVersion6494,
        )
        elections[mapKey] = e
        prune(now)
        return e
    }

    fun currentElection6600(
        mint: String,
        candidateVersion: Long = candidateVersionFor(mint),
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
    ): Election? {
        val now = System.currentTimeMillis()
        return elections[mapKey(CandidateKey(runtimeGeneration, mint, candidateVersion))]
            ?.takeIf { now - it.createdAtMs <= TTL_MS }
    }

    /**
     * V5.0.6679 §SEALED_FDG_OWNER_BEFORE_CALLER_ORDER.
     *
     * The V5.0.6614 implementation assumed canonicalCycleLaneFor had already
     * elected the strongest specialist, but the coordinator did not actually
     * consume that authority. On a fresh key it simply elected `listOf(laneUpper)`,
     * making the first wrapper caller the immutable owner. Runtime 5.0.5720
     * captured the failure directly: FDG sealed PROJECT_SNIPER, then a CORE
     * TradeAuthorizer wrapper reached this method first, CORE won the election,
     * claimed the mint/version, and the true PROJECT_SNIPER attempt was later
     * suppressed by ONE_EXECUTABLE_BUY_PER_MINT_VERSION.
     *
     * Once FDG has a canonical decision for this exact mint/version/mode, caller
     * order has zero authority. Bind the election to ExecutionDecisionSnapshot6510.
     * If no sealed FDG snapshot exists yet, preserve the legacy pre-seal behavior;
     * nothing is fabricated and no lane is disabled.
     *
     * Do not re-elect it here using static priority once a sealed FDG owner exists;
     * the sealed specialist decision is the causal execution authority.
     */
    /**
     * V5.0.6910 — the single definition of "this lane cannot own execution".
     *
     * These four labels were an inline `setOf(...)` inside
     * sealedFdgOwnerLane6679 below. ExecutableOpenGate now has to make the same
     * judgement when it decides which lane to record as `selectedLane` — the
     * value that BECOMES `executionLane` in the snapshot this function reads —
     * so the set had to become shared rather than copied. A second copy is how
     * a writer and its gate end up disagreeing about the same word, which is
     * the failure this whole chain already suffered once.
     */
    private val NON_OWNER_LANES_6910 = setOf("UNKNOWN", "STANDARD", "V3_CORE", "SHADOW")

    /**
     * V5.0.7815 — execution ownership must obey the canonical enabled-trader
     * authority, not whichever wrapper happened to reach the coordinator.
     * PAPER remains learn-everything because EnabledTraderAuthority.isEnabled()
     * deliberately returns true for every trader there.
     */
    private fun enabledOwnerTrader7815(lane: String): EnabledTraderAuthority.Trader? = when (
        CanonicalLaneIdentity6506.canonical(lane)
    ) {
        "CORE" -> EnabledTraderAuthority.Trader.MEME
        "SHITCOIN" -> EnabledTraderAuthority.Trader.SHITCOIN
        "MOONSHOT" -> EnabledTraderAuthority.Trader.MOONSHOT
        "EXPRESS" -> EnabledTraderAuthority.Trader.EXPRESS
        "QUALITY" -> EnabledTraderAuthority.Trader.QUALITY
        "TREASURY" -> EnabledTraderAuthority.Trader.TREASURY
        "CASHGEN" -> EnabledTraderAuthority.Trader.CASHGEN
        "BLUECHIP" -> EnabledTraderAuthority.Trader.BLUECHIP
        "MANIPULATED" -> EnabledTraderAuthority.Trader.MANIPULATED
        "DIP_HUNTER" -> EnabledTraderAuthority.Trader.DIP_HUNTER
        "PROJECT_SNIPER" -> EnabledTraderAuthority.Trader.PROJECT_SNIPER
        "CYCLIC" -> EnabledTraderAuthority.Trader.CYCLIC
        else -> null
    }

    /** True when `lane` can hold canonical execution ownership. */
    fun laneCanOwnExecution6910(lane: String?): Boolean {
        val u = CanonicalLaneIdentity6506.canonical(lane.orEmpty())
        if (u.isBlank() || u in NON_OWNER_LANES_6910) return false
        val trader7815 = enabledOwnerTrader7815(u) ?: return false
        val enabled7815 = try { EnabledTraderAuthority.isEnabled(trader7815) } catch (_: Throwable) { false }
        if (!enabled7815) {
            try {
                PipelineHealthCollector.labelInc("LANE_OWNER_DISABLED_BY_AUTHORITY_7815_$u")
            } catch (_: Throwable) {}
        }
        return enabled7815
    }

    private fun sealedFdgOwnerLane6679(mint: String, candidateVersion: Long): String? = try {
        val mode6679 = if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"
        com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510
            .currentForMint(mint, candidateVersion, mode6679)
            ?.executionLane
            ?.trim()
            ?.uppercase()
            ?.takeIf { laneCanOwnExecution6910(it) }
    } catch (_: Throwable) { null }

    /**
     * V5.0.7774 §THE_OWNER_MUST_WANT_THE_TRADE. A lane whose own evaluator has
     * authoritatively refused this mint (SpecialistBrainBridge7542's cached opinion,
     * read-only) cannot own it. On 5.0.7771 the pre-seal fallback elected by static
     * priority alone, so EXPRESS (93) owned RENDER while its trader answered
     * MCAP_TOO_HIGH, and CASHGEN, which qualified it, was suppressed as
     * LANE_TELEMETRY_ONLY (832 preauth suppressions; BLUECHIP/QUALITY/CASHGEN 0
     * tickets on 255 FDG allows). Field Manual §12: one selected strategy owns the
     * live trade, the one whose evaluator chose it. Still one primary per mint.
     */
    private fun nativeRefused7774(mint: String, lane: String): Boolean = try {
        val o = SpecialistBrainBridge7542.cachedSnapshot7650(mint)?.opinions?.get(lane.uppercase())
        o != null && o.authoritative && !o.eligible
    } catch (_: Throwable) { false }

    /**
     * V5.0.7807 — a lane may not win ownership of a candidate its own executor
     * deterministically refuses. LIVE PROJECT_SNIPER reads the same launch-
     * identity predicate Executor 7385 enforces
     * (LaneEntryContract6342.sniperLaunchIdentityRefusal7807); before this the
     * sniper kept winning the election, the live buy refused it late
     * (LIVE_SNIPER_NOT_A_LAUNCH_7385), and the next cycle elected it again.
     * Paper never had the 7385 refusal, so paper is unchanged (Field Manual L153).
     */
    private fun ownExecutorRefusal7807(mint: String, lane: String): String? {
        if (!lane.equals("PROJECT_SNIPER", true)) return null
        if (try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }) return null
        val ts = try { BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return null
        return try { LaneEntryContract6342.sniperLaunchIdentityRefusal7807(ts) } catch (_: Throwable) { null }
    }

    fun canRequestExecution(
        mint: String,
        lane: String,
        candidateVersion: Long = candidateVersionFor(mint),
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
    ): Verdict {
        val laneUpper = lane.uppercase()
        ownExecutorRefusal7807(mint, laneUpper)?.let { why7807 ->
            try {
                PipelineHealthCollector.labelInc("LANE_OWNERSHIP_REFUSED_OWN_EXECUTOR_7807_$laneUpper")
                ForensicLogger.lifecycle(
                    "LANE_OWNERSHIP_REFUSED_OWN_EXECUTOR_7807",
                    "mint=${mint.take(10)} lane=$laneUpper why=$why7807 version=$candidateVersion action=pass_before_attempt",
                )
            } catch (_: Throwable) {}
            return Verdict(
                allowed = false,
                reason = "LANE_EXECUTOR_WOULD_REFUSE_7807:$why7807",
                primaryLane = "",
                candidateVersion = candidateVersion,
            )
        }
        val key = CandidateKey(runtimeGeneration, mint, candidateVersion)
        val mapKey = mapKey(key)
        val now = System.currentTimeMillis()
        var existing = elections[mapKey]?.takeIf { now - it.createdAtMs <= TTL_MS }

        // V5.0.7541 — sealed FDG ownership is authoritative even when a wrapper
        // created a pre-FDG election first. The old code only consulted FDG when
        // existing == null, so caller order could permanently own the mint/version
        // and starve the specialist that actually won FDG.
        val sealedFdgOwner6679 = sealedFdgOwnerLane6679(mint, candidateVersion)
        if (sealedFdgOwner6679 != null && existing != null &&
            existing.primaryLane != sealedFdgOwner6679) {
            try {
                PipelineHealthCollector.labelInc("PRESEAL_OWNER_REPLACED_BY_FDG_7541")
                ForensicLogger.lifecycle(
                    "PRESEAL_OWNER_REPLACED_BY_FDG_7541",
                    "mint=${mint.take(10)} version=$candidateVersion prior=${existing.primaryLane} sealed=$sealedFdgOwner6679",
                )
            } catch (_: Throwable) {}
            elections.remove(mapKey, existing)
            existing = null
        }
        // V5.0.7803 — an earlier WATCHING/QUALIFIED election has no right to
        // survive once a specialist publishes an executable READY proposal.
        // Re-elect once from the READY set before anything is sealed.
        val readyLanes7803 = readyProposalScores7803(mint, candidateVersion).keys
        if (existing != null && sealedFdgOwner6679 == null && !existing.sealed &&
            readyLanes7803.isNotEmpty()
        ) {
            try {
                PipelineHealthCollector.labelInc("LANE_PRESEAL_REELECTED_FROM_READY_7803")
                ForensicLogger.lifecycle(
                    "LANE_PRESEAL_REELECTED_FROM_READY_7803",
                    "mint=${mint.take(10)} version=$candidateVersion prior=${existing.primaryLane} ready=${readyLanes7803.joinToString(",")}",
                )
            } catch (_: Throwable) {}
            elections.remove(mapKey, existing)
            existing = null
        }

        // V5.0.7774 — an unclaimed pre-seal election whose owner refuses the mint
        // yields to a caller whose own evaluator does not.
        val prior7774 = existing
        if (prior7774 != null && sealedFdgOwner6679 == null && !prior7774.sealed &&
            prior7774.primaryLane != laneUpper && nativeRefused7774(mint, prior7774.primaryLane) &&
            !nativeRefused7774(mint, laneUpper)) {
            try { PipelineHealthCollector.labelInc("PRESEAL_OWNER_NATIVE_REFUSED_REELECTED_7774") } catch (_: Throwable) {}
            elections.remove(mapKey, prior7774)
            existing = null
        }
        val e = existing ?: if (sealedFdgOwner6679 != null) {
            if (sealedFdgOwner6679 != laneUpper) {
                try {
                    PipelineHealthCollector.labelInc("LANE_CALLER_DEFERRED_TO_SEALED_FDG_OWNER_6679")
                    ForensicLogger.lifecycle(
                        "LANE_ELECTION_BOUND_TO_SEALED_FDG_6679",
                        "mint=${mint.take(10)} version=$candidateVersion caller=$laneUpper sealedOwner=$sealedFdgOwner6679 action=caller_order_has_no_authority",
                    )
                } catch (_: Throwable) {}
            }
            try { PipelineHealthCollector.labelInc("LANE_ELECTION_BOUND_TO_SEALED_FDG_6679") } catch (_: Throwable) {}
            elect(
                mint = mint,
                lanes = listOf(sealedFdgOwner6679),
                preferred = sealedFdgOwner6679,
                candidateVersion = candidateVersion,
                runtimeGeneration = runtimeGeneration,
            )
        } else {
            // V5.0.7620 — pre-FDG ownership must not be first-caller-wins.
            // Build the actual qualified contest from registered affinities plus
            // the requesting specialist, then let the coordinator's existing
            // learned/fair selector choose. Once FDG seals a canonical owner,
            // the branch above still replaces this pre-seal election.
            val qualified7620 = qualifiedLanesFor(mint, candidateVersion, laneUpper)
                .filter { laneCanOwnExecution6910(it) }
                .filter { ownExecutorRefusal7807(mint, it) == null }
                .distinct()
            // V5.0.7774 — contenders whose own evaluator refused the mint sit out;
            // if every contender refused, the contest is unchanged (no new choke).
            val willing7774 = qualified7620.filterNot { nativeRefused7774(mint, it) }
            if (willing7774.size < qualified7620.size) {
                try { PipelineHealthCollector.labelInc("LANE_ELECTION_NATIVE_REFUSED_FILTERED_7774") } catch (_: Throwable) {}
            }
            elect(
                mint = mint,
                lanes = willing7774.ifEmpty { qualified7620.ifEmpty { listOf(laneUpper) } },
                preferred = null,
                candidateVersion = candidateVersion,
                runtimeGeneration = runtimeGeneration,
            )
        }

        val allowed = e.primaryLane == laneUpper
        val finalElection6494 = if (allowed && !e.sealed) {
            recordPrimaryWin(e.primaryLane)
            try {
                PipelineHealthCollector.labelInc("LANE_PRIMARY_FAIR_WIN_RECORDED_7620")
                PipelineHealthCollector.labelInc("LANE_PRIMARY_FAIR_WIN_RECORDED_7620_" + e.primaryLane)
            } catch (_: Throwable) {}
            e.copy(sealed = true).also { elections[mapKey] = it }
        } else e
        if (!allowed) duplicateOpenSuppressed.incrementAndGet()
        return Verdict(
            allowed = allowed,
            reason = if (allowed) "LANE_PRIMARY_ELECTED" else "LANE_TELEMETRY_ONLY primary=${finalElection6494.primaryLane}",
            primaryLane = finalElection6494.primaryLane,
            candidateVersion = finalElection6494.key.candidateVersion,
            electionId = finalElection6494.electionId,
            authorityVersion = finalElection6494.authorityVersion,
        )
    }

    fun duplicateOpenSuppressions(): Long = duplicateOpenSuppressed.get()

    fun releaseIfPrimary(
        mint: String,
        lane: String,
        reason: String,
        candidateVersion: Long = candidateVersionFor(mint),
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
    ): Boolean {
        val laneUpper = lane.uppercase()
        val key = CandidateKey(runtimeGeneration, mint, candidateVersion)
        var mapKey = mapKey(key)
        var current = elections[mapKey]
        if (current == null) {
            val now = System.currentTimeMillis()
            val active = elections.entries
                .filter { (_, e) -> e.key.runtimeGeneration == runtimeGeneration && e.key.mint == mint && now - e.createdAtMs <= TTL_MS }
                .maxByOrNull { it.value.createdAtMs }
            if (active != null) {
                mapKey = active.key
                current = active.value
            }
        }
        if (current == null) {
            // V5.0.7868 — no election held for this candidate: there is nothing to
            // release. A rejected candidate that never elected an owner used to
            // launch a coroutine + forensic row per lane per cycle
            // (LANE_PRIMARY_RELEASE_FALSE_VISIBLE_4419 MISSING_ELECTION fan-out).
            return false
        }
        if (current.primaryLane != laneUpper) {
            ChokeReliefBus.launch("LANE_PRIMARY_RELEASE_FALSE_VISIBLE_4421", mint) {
                try { PipelineHealthCollector.labelInc("LANE_PRIMARY_RELEASE_FALSE_VISIBLE_4419_NOT_PRIMARY") } catch (_: Throwable) {}
                try {
                    ForensicLogger.lifecycle(
                        "LANE_PRIMARY_RELEASE_FALSE_VISIBLE_4419",
                        "mint=${mint.take(10)} lane=$laneUpper primary=${current.primaryLane} candidateVersion=${current.key.candidateVersion} reason=$reason outcome=NOT_PRIMARY via=ChokeReliefBus"
                    )
                } catch (_: Throwable) {}
            }
            return false
        }
        val removed = elections.remove(mapKey, current)
        if (removed) {
            try {
                ForensicLogger.lifecycle(
                    "LANE_PRIMARY_RELEASED",
                    "mint=${mint.take(10)} lane=$laneUpper candidateVersion=${current.key.candidateVersion} reason=$reason"
                )
            } catch (_: Throwable) {}
        }
        return removed
    }

    fun resetForTests() {
        elections.clear()
        affinities.clear()
        qualifiedContests7621.clear()
        try { com.lifecyclebot.engine.market.SpecialistCandidateBooks7803.resetForTests() } catch (_: Throwable) {}
        duplicateOpenSuppressed.set(0L)
        versionSeq.set(0L)
        authoritySeq6494.set(0L)
        laneWinTimestamps.clear()
    }

    private fun mapKey(key: CandidateKey): String = "${key.runtimeGeneration}:${key.mint}:${key.candidateVersion}"

    private fun prune(now: Long) {
        if (elections.size < 5_000) return
        elections.entries.removeIf { now - it.value.createdAtMs > TTL_MS }
    }
}
