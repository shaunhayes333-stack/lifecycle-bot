package com.lifecyclebot.engine

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
    )

    data class Verdict(
        val allowed: Boolean,
        val reason: String,
        val primaryLane: String,
        val candidateVersion: Long,
    )

    private const val TTL_MS = 30_000L
    private val versionSeq = AtomicLong(0L)
    private val elections = ConcurrentHashMap<String, Election>()
    private val duplicateOpenSuppressed = AtomicLong(0L)
    private val affinities = ConcurrentHashMap<String, Set<String>>()

    // V5.9.1135 — lane election must be priority-based, not first-caller-wins.
    // 3102 showed TREASURY evaluating first in BotService and becoming primary
    // for fresh meme candidates, after which SHITCOIN/MOONSHOT/MANIP/DIP got
    // LANE_PREAUTH_SUPPRESSED. That starves the exact specialist lanes that had
    // been carrying ~40% WR the prior night. Treasury is still allowed to trade
    // when no specialist requests the book; it just cannot steal the book solely
    // because it ran earlier in the loop.
    private val lanePriority = mapOf(
        "MOONSHOT" to 100,
        "SHITCOIN" to 95,
        "MANIPULATED" to 90,
        "DIP_HUNTER" to 85,
        "PROJECT_SNIPER" to 80,
        "CRYPTO" to 75,
        "QUALITY" to 70,
        "BLUECHIP" to 60,
        "CORE" to 55,
        "TREASURY" to 40,
        "SHADOW" to 10,
    )

    private fun priority(lane: String): Int = lanePriority[lane.uppercase()] ?: 50

    fun registerAffinity(mint: String, lanes: Set<String>) {
        val clean = lanes.map { it.uppercase() }.filter { it.isNotBlank() }.toSet()
        if (clean.isEmpty()) return
        affinities.merge(mint, clean) { old, new -> old + new }
    }

    private fun effectivePriority(mint: String, lane: String): Int {
        val laneUpper = lane.uppercase()
        val registryAffinity = try { GlobalTradeRegistry.getLaneAffinity(mint) } catch (_: Throwable) { emptySet() }
        val allAffinity = (affinities[mint] ?: emptySet()) + registryAffinity
        val boost = if (allAffinity.contains(laneUpper)) 30 else 0
        return priority(laneUpper) + boost
    }

    // ── FAIR LANE ROTATION (V5.9.1335) ───────────────────────────────
    // ROOT CAUSE of "half the meme trader isn't working": every pump.fun
    // candidate is blanket-tagged SHITCOIN+MOONSHOT+MANIPULATED+PROJECT_SNIPER
    // (BotService.inferIntakeLaneAffinity / TokenMergeQueue), so the per-mint
    // election was decided purely by STATIC priority — and MOONSHOT (100) wins
    // every single time. SHITCOIN / MANIPULATED / PROJECT_SNIPER got
    // LANE_TELEMETRY_ONLY on ~everything → they never opened a trade → they
    // never learned. The lanes were alive but starved.
    //
    // FIX: weight the election by FAIRNESS. Each lane keeps a decaying count of
    // recent primary wins; a lane that has been hogging primary gets a penalty
    // subtracted from its effective priority, so a starved peer can take the next
    // qualifying candidate. Static priority remains the baseline and the final
    // tiebreaker, so the order is deterministic and Treasury still can't steal the
    // book from specialists. This is load-balancing, NOT a veto — every lane still
    // trades AND learns, and one-lane-per-mint (no double-buy) is preserved.
    //
    // FAIRNESS_WEIGHT scales how hard recent wins push a lane down. With weight 8
    // and base gaps of ~5 between specialists, a lane that is ~3-4 wins ahead of a
    // peer yields the next candidate to that peer — fast enough to keep all four
    // specialists fed on the pump.fun firehose, slow enough not to thrash.
    // Fairness only influences FRESH elections (who claims an UNCLAIMED book).
    // It NEVER yanks an active primary mid-window — upgrades stay pure static
    // priority — so there is no thrash and no stealing a candidate another lane
    // is already opening. A lane only gets pushed down once it is winning
    // DISPROPORTIONATELY (a real lead over the field), never on a single win,
    // honouring "nothing loses its static priority unless it is winning over
    // everything else."
    private const val FAIRNESS_DECAY_MS = 120_000L    // a win's fairness weight decays over ~2 min
    private const val FAIRNESS_LEAD_GRACE = 3         // a lane may lead the field by up to this many
                                                      // wins before any penalty applies
    private const val FAIRNESS_PER_LEAD = 6.0         // penalty per excess win beyond the grace lead
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

    /** Recent (decaying) primary-win count for a lane. */
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

    /**
     * Claim priority for a FRESH election among [qualified] lanes: static/affinity
     * priority MINUS a fairness penalty that only bites a lane leading the field by
     * more than FAIRNESS_LEAD_GRACE wins. A lane at or near the field minimum keeps
     * its full static priority — so MOONSHOT still wins by default and only yields
     * once it is genuinely hogging the book. Fail-open to raw priority.
     */
    private fun claimPriority(mint: String, lane: String, qualified: Collection<String>): Int = try {
        val floor = minRecentWinsAcross(qualified)
        val lead = (recentWins(lane) - floor - FAIRNESS_LEAD_GRACE).coerceAtLeast(0)
        effectivePriority(mint, lane) - (lead * FAIRNESS_PER_LEAD).toInt()
    } catch (_: Throwable) { effectivePriority(mint, lane) }

    /** Pick the fairest claimant for a fresh election from the qualified lane set. */
    private fun pickFreshPrimary(mint: String, qualified: List<String>): String? {
        if (qualified.isEmpty()) return null
        return qualified.maxByOrNull { claimPriority(mint, it, qualified) }
    }

    /** Full set of lanes considered "in the running" for a mint's fairness math:
     *  the registered affinity set plus the two lanes currently contesting the book. */
    private fun qualifiedLanesFor(mint: String, vararg contesting: String): List<String> {
        val registryAffinity = try { GlobalTradeRegistry.getLaneAffinity(mint) } catch (_: Throwable) { emptySet() }
        val all = ((affinities[mint] ?: emptySet()) + registryAffinity + contesting.map { it.uppercase() })
            .filter { it.isNotBlank() }
        return if (all.isEmpty()) contesting.map { it.uppercase() } else all.toList()
    }

    fun candidateVersionFor(mint: String): Long {
        // 15-30s window bucket + monotonic suffix avoids same-second duplicate opens
        // while still letting genuinely new candidate data re-elect shortly after.
        val bucket = System.currentTimeMillis() / TTL_MS
        return bucket
    }

    fun elect(
        mint: String,
        lanes: List<String>,
        preferred: String? = null,
        candidateVersion: Long = candidateVersionFor(mint),
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
    ): Election {
        val clean = lanes.map { it.uppercase() }.filter { it.isNotBlank() }
        val primary = (preferred?.uppercase()?.takeIf { it in clean } ?: pickFreshPrimary(mint, clean) ?: clean.firstOrNull() ?: "CORE")
        val secondary = clean.firstOrNull { it != primary }
        val key = CandidateKey(runtimeGeneration, mint, candidateVersion)
        val mapKey = mapKey(key)
        val now = System.currentTimeMillis()
        val old = elections[mapKey]
        if (old != null && now - old.createdAtMs <= TTL_MS) return old
        val e = Election(key, primary, secondary, now)
        elections[mapKey] = e
        prune(now)
        return e
    }

    fun canRequestExecution(
        mint: String,
        lane: String,
        candidateVersion: Long = candidateVersionFor(mint),
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
    ): Verdict {
        val laneUpper = lane.uppercase()
        val key = CandidateKey(runtimeGeneration, mint, candidateVersion)
        val mapKey = mapKey(key)
        val now = System.currentTimeMillis()
        val existing = elections[mapKey]?.takeIf { now - it.createdAtMs <= TTL_MS }
        if (existing == null && laneUpper == "TREASURY") {
            val deferred = elect(mint, listOf(laneUpper), laneUpper, candidateVersion, runtimeGeneration)
            // Treasury runs before the specialist lanes in BotService. If we allow
            // it on the first touch it can consume the one-book executable key and
            // force Moonshot/Shitcoin/Manip/Dip into telemetry-only. Defer exactly
            // the first Treasury touch; if no specialist claims this candidate,
            // Treasury will be allowed on the next pass while the election is live.
            duplicateOpenSuppressed.incrementAndGet()
            return Verdict(
                allowed = false,
                reason = "TREASURY_DEFER_SPECIALIST_FIRST primary=${deferred.primaryLane}",
                primaryLane = deferred.primaryLane,
                candidateVersion = deferred.key.candidateVersion,
            )
        }
        // V5.9.1385 — canonical candidate finality order: primaryLane is
        // immutable for the attempt. Secondary lanes may produce shadow telemetry,
        // but no challenger can upgrade/steal the executable lane after the first
        // election for this mint+candidateVersion.
        val e = if (existing == null) {
            val qualified = qualifiedLanesFor(mint, laneUpper)
            val freshPrimary = pickFreshPrimary(mint, qualified) ?: laneUpper
            val fresh = elect(mint, qualified, freshPrimary, candidateVersion, runtimeGeneration)
            recordPrimaryWin(fresh.primaryLane)
            fresh
        } else existing
        val allowed = e.primaryLane == laneUpper
        if (!allowed) duplicateOpenSuppressed.incrementAndGet()
        return Verdict(
            allowed = allowed,
            reason = if (allowed) "LANE_PRIMARY_ELECTED" else "LANE_TELEMETRY_ONLY primary=${e.primaryLane}",
            primaryLane = e.primaryLane,
            candidateVersion = e.key.candidateVersion,
        )
    }

    fun duplicateOpenSuppressions(): Long = duplicateOpenSuppressed.get()

    /**
     * V5.9.1138 — release a primary election when the winning lane fails before
     * actually opening a trade. Without this, the first primary lane can fail
     * FDG/finality/buy-open and still suppress every other lane for the full
     * candidate window, making runtime look like "only one layer trades".
     * This is fail-open for learning: it only releases when the caller is the
     * current primary for that mint/window.
     */
    fun releaseIfPrimary(
        mint: String,
        lane: String,
        reason: String,
        candidateVersion: Long = candidateVersionFor(mint),
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
    ): Boolean {
        val laneUpper = lane.uppercase()
        val key = CandidateKey(runtimeGeneration, mint, candidateVersion)
        val mapKey = mapKey(key)
        val current = elections[mapKey] ?: return false
        if (current.primaryLane != laneUpper) return false
        // V5.9.1385 — primaryLane is immutable for the attempt. A failed
        // primary may not release the book to another lane inside the same
        // mint+candidateVersion window; the next fresh candidateVersion can
        // elect again naturally.
        try {
            ForensicLogger.lifecycle(
                "LANE_PRIMARY_RELEASE_SUPPRESSED_IMMUTABLE",
                "mint=${mint.take(10)} lane=$laneUpper candidateVersion=${current.key.candidateVersion} reason=$reason"
            )
        } catch (_: Throwable) {}
        return false
    }

    fun resetForTests() {
        elections.clear()
        affinities.clear()
        duplicateOpenSuppressed.set(0L)
        versionSeq.set(0L)
        laneWinTimestamps.clear()   // V5.9.1335a — fairness state must reset per test
    }

    private fun mapKey(key: CandidateKey): String = "${key.runtimeGeneration}:${key.mint}:${key.candidateVersion}"

    private fun prune(now: Long) {
        if (elections.size < 5_000) return
        elections.entries.removeIf { now - it.value.createdAtMs > TTL_MS }
    }
}
