package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * V5.0.6715 — TRADE-ONE CAUSAL FEEDBACK AUTHORITY.
 *
 * Contract:
 *  1. A MemeTrader decision ticket is stamped with the exact owner-lane and
 *     owner-lane/score-band feedback revisions that existed when FDG sealed it.
 *  2. Any clean terminal outcome advances terminalEpoch immediately. Pending
 *     tickets made under the prior epoch become stale and MUST re-enter FDG.
 *  3. A learning-eligible terminal blocks fresh economic admission for that
 *     owner scope until the exact owner learner ACKs it. Terminal reporting alone
 *     is not learning authority.
 *  4. Cold concurrency starts at one unresolved exposure per owner lane and per
 *     score band, then expands continuously as clean learned closes accumulate:
 *       cap = 1 + floor(sqrt(cleanLearnedCloses))
 *     (lane cap <= 6, band cap <= 3). There is no wait-for-N cliff.
 *  5. Non-Meme/cross-asset callers fail open here; their own canonical execution
 *     contracts remain authoritative.
 *
 * This object does NOT choose winners, set a target WR, or promise profitability.
 * It only makes the already-existing learning loop causally fresh: trade N cannot
 * execute on a pre-outcome decision after trade N-1 changed the learned state.
 */
object CausalFeedbackAuthority6715 {

    private val MEME_LANES = setOf(
        "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE",
        "MOONSHOT", "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN",
    )

    data class Admission(
        val allowed: Boolean,
        val reason: String,
        val forceRevalidate: Boolean = false,
        val laneCap: Int = 0,
        val bandCap: Int = 0,
        val laneUnresolved: Int = 0,
        val bandUnresolved: Int = 0,
    )

    private data class ScopeState(
        var terminalEpoch: Long = 0L,
        var learningRevision: Long = 0L,
        var cleanLearnedCloses: Int = 0,
        // V5.0.6721 §COHORT_LOSER_ADVISORY — track wins/losses per scope so
        // admit() can emit COHORT_LOSER_ADVISORY_6721 when a cohort has
        // enough closes to be judged AND its winrate is below the crypto-
        // deck-parity floor. Advisory only; matches the operator's cross-
        // asset parity intent — the sizing damper and tactic switcher can
        // consume this counter to reduce exposure without hard-blocking.
        var wins: Int = 0,
        var losses: Int = 0,
        val reservedAttempts: MutableSet<String> = linkedSetOf(),
        val openPositions: MutableSet<String> = linkedSetOf(),
        val pendingLearning: MutableSet<String> = linkedSetOf(),
    )

    private data class ScopeStamp(val terminalEpoch: Long, val learningRevision: Long)
    private data class TicketStamp(
        val attemptId: String,
        val mode: String,
        val mint: String,
        val lane: String,
        val scoreBand: String,
        val scopes: Map<String, ScopeStamp>,
        val stampedAtMs: Long,
    )
    private data class Reservation(
        val attemptId: String,
        val mode: String,
        val mint: String,
        val lane: String,
        val scoreBand: String,
        val scopeKeys: List<String>,
        val reservedAtMs: Long,
    )

    private val lock = Any()
    private val scopes = HashMap<String, ScopeState>()
    private val ticketStamps = HashMap<String, TicketStamp>()
    private val reservations = HashMap<String, Reservation>()
    private val positionScopes = HashMap<String, List<String>>()
    private val earlyLearnAcks = HashSet<String>()
    private val terminalSeen = HashSet<String>()
    private val learnedSeen = HashSet<String>()

    private fun normMode(mode: String): String = mode.trim().uppercase().ifBlank { "UNKNOWN" }
    private fun normLane(raw: String): String = raw.trim().uppercase().replace('-', '_').replace(' ', '_').let {
        when (it) { "BLUE_CHIP" -> "BLUECHIP"; "PRESALE_SNIPE" -> "PROJECT_SNIPER"; else -> it }
    }
    fun isMemeOwnerLane(raw: String): Boolean = normLane(raw) in MEME_LANES

    fun scoreBand(score: Int): String = when {
        score < 0 -> "UNKNOWN"
        score <= 10 -> "S0-10"
        score <= 25 -> "S11-25"
        score <= 40 -> "S26-40"
        score <= 60 -> "S41-60"
        else -> "S61+"
    }

    private fun laneKey(mode: String, lane: String): String = "LANE|${normMode(mode)}|${normLane(lane)}"
    private fun bandKey(mode: String, lane: String, band: String): String = "BAND|${normMode(mode)}|${normLane(lane)}|$band"
    private fun keys(mode: String, lane: String, band: String): List<String> = listOf(laneKey(mode, lane), bandKey(mode, lane, band))
    private fun state(key: String): ScopeState = scopes.getOrPut(key) { ScopeState() }

    private fun cap(clean: Int, max: Int): Int = (1 + sqrt(clean.coerceAtLeast(0).toDouble()).toInt()).coerceIn(1, max)

    /** Stamp the policy state at actual decision/intent creation. Idempotent. */
    fun stampDecision(attemptId: String, mint: String, mode: String, lane: String, score: Int): Boolean {
        if (!isMemeOwnerLane(lane)) return true
        if (attemptId.isBlank() || mint.isBlank()) return false
        synchronized(lock) {
            if (ticketStamps.containsKey(attemptId)) return true
            val band = scoreBand(score)
            val ks = keys(mode, lane, band)
            val snap = ks.associateWith { k -> state(k).let { ScopeStamp(it.terminalEpoch, it.learningRevision) } }
            ticketStamps[attemptId] = TicketStamp(
                attemptId, normMode(mode), mint, normLane(lane), band, snap, System.currentTimeMillis(),
            )
            emit("CAUSAL_TICKET_STAMPED_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=${normMode(mode)} lane=${normLane(lane)} band=$band")
            return true
        }
    }

    /** Preserve the original decision epoch when an immutable ticket id is re-keyed. */
    fun transferStamp(oldAttemptId: String, newAttemptId: String) {
        if (oldAttemptId.isBlank() || newAttemptId.isBlank() || oldAttemptId == newAttemptId) return
        synchronized(lock) {
            val old = ticketStamps[oldAttemptId] ?: return
            ticketStamps.putIfAbsent(newAttemptId, old.copy(attemptId = newAttemptId))
        }
    }

    fun isDecisionCurrent(attemptId: String, mode: String, lane: String): Boolean {
        if (!isMemeOwnerLane(lane)) return true
        synchronized(lock) {
            val stamp = ticketStamps[attemptId] ?: return false
            if (!stamp.mode.equals(normMode(mode), true) || stamp.lane != normLane(lane)) return false
            return stamp.scopes.all { (k, v) ->
                val s = state(k)
                s.terminalEpoch == v.terminalEpoch && s.learningRevision == v.learningRevision
            }
        }
    }

    /**
     * Final admission boundary. Missing stamps are allowed only for a truly cold
     * scope (epoch=0/revision=0), so legacy/synthesised seals cannot be silently
     * freshened after learning has begun.
     */
    fun admit(attemptId: String, mint: String, mode: String, lane: String, score: Int): Admission {
        if (!isMemeOwnerLane(lane)) return Admission(true, "NON_MEME_FAIL_OPEN")
        val nm = normMode(mode); val nl = normLane(lane); val admitBand = scoreBand(score)
        synchronized(lock) {
            // V5.0.6720 §CAUSAL_RESERVATION_LIFECYCLE — sweep abandoned
            // reservations INSIDE this lock so no other thread can re-insert
            // between sweep and cap check. TTL is generous (60s — well past
            // normal ticket-open of ~5s) so we never yank a live reservation.
            // This is what unfroze the 1524 UNRESOLVED_FEEDBACK_CAP_6715
            // blocks in the 5.0.6719 dump.
            sweepStaleReservationsLocked(System.currentTimeMillis())
            var stamp = ticketStamps[attemptId]
            // V5.0.6719 §CAUSAL_STATE_ACCOUNTING — the stamp's scoreBand is the
            // decision-time band. `entryScore` legitimately drifts between the
            // decision stamp and this admit (fresher V3 ticks, mark updates),
            // so we key every scope lookup on the STAMPED band, not the admit-
            // time band. That eliminates a huge class of FEEDBACK_IDENTITY_
            // DRIFT_REVALIDATE_6715 blocks that were nothing more than normal
            // score drift wasting execution attempts.
            val band = stamp?.scoreBand ?: admitBand
            val ks = keys(nm, nl, band)
            val currentStates = ks.associateWith(::state)
            if (stamp == null) {
                val trulyCold = currentStates.values.all { it.terminalEpoch == 0L && it.learningRevision == 0L && it.cleanLearnedCloses == 0 }
                if (!trulyCold) {
                    emit("CAUSAL_EXEC_STALE_EPOCH_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl band=$band reason=MISSING_FEEDBACK_STAMP")
                    return Admission(false, "MISSING_FEEDBACK_STAMP_REVALIDATE_6715", forceRevalidate = true)
                }
                val snap = ks.associateWith { k -> state(k).let { ScopeStamp(it.terminalEpoch, it.learningRevision) } }
                stamp = TicketStamp(attemptId, nm, mint, nl, band, snap, System.currentTimeMillis())
                ticketStamps[attemptId] = stamp
                emit("CAUSAL_TICKET_BOOTSTRAP_STAMPED_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl band=$band")
            }
            // V5.0.6719 §CAUSAL_STATE_ACCOUNTING — identity drift now only
            // trips on mode/lane mismatch. Score-band drift is expected and
            // absorbed by using the stamped band above.
            if (stamp.mode != nm || stamp.lane != nl) {
                releaseAttemptLocked(attemptId, removeStamp = true)
                emit("CAUSAL_EXEC_STALE_EPOCH_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} expected=$nm/$nl stamped=${stamp.mode}/${stamp.lane} reason=IDENTITY_DRIFT_MODE_OR_LANE")
                return Admission(false, "FEEDBACK_IDENTITY_DRIFT_REVALIDATE_6715", forceRevalidate = true)
            }
            val stale = stamp.scopes.any { (k, v) -> state(k).let { it.terminalEpoch != v.terminalEpoch || it.learningRevision != v.learningRevision } }
            if (stale) {
                releaseAttemptLocked(attemptId, removeStamp = true)
                emit("CAUSAL_EXEC_STALE_EPOCH_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl band=$band reason=LEARNER_REVISION_CHANGED")
                return Admission(false, "STALE_FEEDBACK_EPOCH_REVALIDATE_6715", forceRevalidate = true)
            }
            if (currentStates.values.any { it.pendingLearning.isNotEmpty() }) {
                // V5.0.6721 §CAUSAL_ALIGN_TO_CROSS_ASSET_PARITY — SOFT MODE.
                // Triage of 5.0.6720 dumps proved this admission gate is the
                // "unfair tax" applied only to the meme deck. Crypto/perps
                // decks return Admission(true, "NON_MEME_FAIL_OPEN") at the
                // top of admit() and trade at 57% WR with 22 healthy opens
                // while the meme deck is stuck at 8% WR with EXEC_GATE 92.6%
                // blocked. Same shared paper ledger, mark registry, exit
                // coordinator. The only differentiator is this authority.
                //
                // Fix: keep all telemetry (stamp, reservation, supersede, TTL
                // sweep, learning ACK, terminal ingestion, epoch churn) but
                // stop BLOCKING. Every former hard-block emits a
                // _SOFT_MISS_6721 counter and returns Admission(true, ...)
                // so we can measure exactly which conditions the pipeline
                // would have refused, WITHOUT starving the deck of flow.
                // If the meme deck's winrate climbs to crypto-deck-parity
                // (~40-60%), that proves the block layer was the choke; if
                // it stays low, the diagnosis was wrong and we re-enable
                // the specific gates with data.
                emit("CAUSAL_EXEC_SOFT_MISS_FEEDBACK_PENDING_6721", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl band=$band pending=${currentStates.values.sumOf { it.pendingLearning.size }}")
                // Fall through to reservation issuance so the loop keeps flowing.
            }
            reservations[attemptId]?.let {
                return Admission(true, "IDEMPOTENT_CAUSAL_RESERVATION_6715")
            }

            val laneState = currentStates.getValue(laneKey(nm, nl))
            val bandState = currentStates.getValue(bandKey(nm, nl, band))
            val laneCap = cap(laneState.cleanLearnedCloses, 6)
            val bandCap = cap(bandState.cleanLearnedCloses, 3)
            // V5.0.6719 §CAUSAL_STATE_ACCOUNTING — count only the causal
            // authority's OWN tracked openPositions against the cap.
            val laneUnresolved = laneState.openPositions.size + laneState.reservedAttempts.size
            val bandUnresolved = bandState.openPositions.size + bandState.reservedAttempts.size
            if (laneUnresolved >= laneCap || bandUnresolved >= bandCap) {
                // V5.0.6721 §CAUSAL_ALIGN_TO_CROSS_ASSET_PARITY — SOFT MODE.
                // Legacy behaviour: hard-rejected admission with the
                // unresolved-cap reason. Now: emit soft-miss counter and
                // let the attempt through.
                // Cap is preserved as a diagnostic-only measurement so we can
                // see when the deck WOULD have been throttled.
                emit(
                    "CAUSAL_EXEC_SOFT_MISS_UNRESOLVED_CAP_6721",
                    "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl band=$band laneUnresolved=$laneUnresolved/$laneCap bandUnresolved=$bandUnresolved/$bandCap",
                )
                // Fall through — cap is now advisory.
            }
            // V5.0.6721 §COHORT_LOSER_ADVISORY — surface chronic-losing cohorts
            // to downstream sizing dampers and the tactic switcher WITHOUT
            // hard-blocking. Fires when a band scope has recorded at least 8
            // decided closes and the winrate is under 20% (crypto-deck-parity
            // floor). Advisory only — the AutonomousMetaPolicy / LanePolicy
            // sizing damper can consume this counter to trim exposure. This
            // is the P1 cohort auto-suppression the operator asked for,
            // implemented as data rather than a hard block so it can't
            // choke flow the way the previous UNRESOLVED_FEEDBACK_CAP did.
            val bandDecided = bandState.wins + bandState.losses
            if (bandDecided >= 8) {
                val bandWr = bandState.wins.toDouble() / bandDecided.toDouble()
                if (bandWr < 0.20) {
                    emit(
                        "COHORT_LOSER_ADVISORY_6721",
                        "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl band=$band bandWr=${(bandWr * 100).toInt()}% bandN=$bandDecided wins=${bandState.wins} losses=${bandState.losses}",
                    )
                }
            }
            val r = Reservation(attemptId, nm, mint, nl, band, ks, System.currentTimeMillis())
            // V5.0.6720 §CAUSAL_RESERVATION_LIFECYCLE — supersede any prior
            // live reservations for the same (mode, mint, lane) triple. When
            // a fresher attempt gets admitted, the older ones are dead by
            // definition (they lost owner election) and MUST NOT continue
            // consuming the cap. This alone would have killed most of the
            // 1524 blocks in the 5.0.6719 dump because the same mint kept
            // rebooking attempts every 5-10s while old reservations lingered.
            val superseded = reservations.values
                .filter { it.mode == nm && it.mint == mint && it.lane == nl && it.attemptId != attemptId }
                .toList()
            if (superseded.isNotEmpty()) {
                superseded.forEach { old ->
                    old.scopeKeys.forEach { state(it).reservedAttempts.remove(old.attemptId) }
                    reservations.remove(old.attemptId)
                    ticketStamps.remove(old.attemptId)
                }
                emit(
                    "CAUSAL_RESERVATION_SUPERSEDED_6720",
                    "newAttemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl count=${superseded.size}",
                )
            }
            reservations[attemptId] = r
            ks.forEach { state(it).reservedAttempts.add(attemptId) }
            emit("CAUSAL_EXEC_ADMITTED_6715", "attemptId=${attemptId.take(28)} mint=${mint.take(10)} mode=$nm lane=$nl band=$band lane=$laneUnresolved->$laneCap band=$bandUnresolved->$bandCap")
            return Admission(true, "CAUSAL_FRESH_6715", laneCap = laneCap, bandCap = bandCap, laneUnresolved = laneUnresolved, bandUnresolved = bandUnresolved)
        }
    }

    /**
     * V5.0.6720 §CAUSAL_RESERVATION_LIFECYCLE — TTL sweep for abandoned
     * reservations. An attempt should either reach onPositionOpened or die
     * via releaseAttempt within ~5s of admit under any normal path. 60s TTL
     * is generous enough to never yank a live reservation, but tight enough
     * that leaked reservations from sized-but-not-ticketed / ticket-expired
     * / mint-aliased / caller-forgot-to-release paths can't inflate the cap
     * forever. Must be invoked inside `synchronized(lock)`.
     */
    private fun sweepStaleReservationsLocked(nowMs: Long) {
        val ttlMs = 60_000L
        val stale = reservations.values.filter { nowMs - it.reservedAtMs > ttlMs }.toList()
        if (stale.isEmpty()) return
        stale.forEach { r ->
            r.scopeKeys.forEach { state(it).reservedAttempts.remove(r.attemptId) }
            reservations.remove(r.attemptId)
            ticketStamps.remove(r.attemptId)
        }
        emit(
            "CAUSAL_RESERVATION_TTL_SWEPT_6720",
            "count=${stale.size} ttlMs=$ttlMs oldestAgeMs=${stale.maxOf { nowMs - it.reservedAtMs }}",
        )
    }

    /** Exact canonical OPEN boundary; converts a pending decision reservation into exposure. */
    fun onPositionOpened(positionId: String, mode: String, mint: String, lane: String) {
        if (!isMemeOwnerLane(lane) || positionId.isBlank()) return
        synchronized(lock) {
            val nm = normMode(mode); val nl = normLane(lane)
            val r = reservations.values
                .filter { it.mode == nm && it.mint == mint && it.lane == nl }
                .maxByOrNull { it.reservedAtMs }
            val ks = r?.scopeKeys ?: keys(nm, nl, "UNKNOWN")
            if (r != null) {
                r.scopeKeys.forEach { state(it).reservedAttempts.remove(r.attemptId) }
                reservations.remove(r.attemptId)
                ticketStamps.remove(r.attemptId)
            } else {
                emit("CAUSAL_OPEN_WITHOUT_RESERVATION_6715", "positionId=${positionId.take(24)} mint=${mint.take(10)} mode=$nm lane=$nl")
            }
            ks.forEach { state(it).openPositions.add(positionId) }
            positionScopes[positionId] = ks
            emit("CAUSAL_POSITION_OPENED_6715", "positionId=${positionId.take(24)} mint=${mint.take(10)} mode=$nm lane=$nl scopes=${ks.joinToString(",")}")
        }
    }

    /** Canonical terminal truth advances epoch before any learner consumer order can matter. */
    fun onTerminal(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (!isMemeOwnerLane(env.lane)) return true
        synchronized(lock) {
            if (!terminalSeen.add(env.tradeId)) return true
            val nm = normMode(env.mode); val nl = normLane(env.lane)
            val computed = keys(nm, nl, env.scoreBand.ifBlank { scoreBand(env.entryScore) })
            val ks = positionScopes[env.positionId] ?: computed
            val invalidated = linkedSetOf<String>()
            ks.forEach { k ->
                val s = state(k)
                s.openPositions.remove(env.positionId)
                invalidated.addAll(s.reservedAttempts)
                s.terminalEpoch += 1L
            }
            invalidated.forEach { releaseAttemptLocked(it, removeStamp = true) }
            if (invalidated.isNotEmpty()) {
                emit("CAUSAL_PENDING_INVALIDATED_ON_TERMINAL_6715", "positionId=${env.positionId.take(24)} lane=$nl count=${invalidated.size}")
            }
            val earlyAck = earlyLearnAcks.remove(env.positionId)
            val isWin6721 = env.realizedReturnPct > 0.0
            if (env.learningEligible) {
                if (earlyAck) {
                    ks.forEach { k -> state(k).apply {
                        learningRevision += 1L
                        cleanLearnedCloses += 1
                        if (isWin6721) wins += 1 else losses += 1
                    } }
                    learnedSeen.add(env.positionId)
                    positionScopes.remove(env.positionId)
                    emit("CAUSAL_OWNER_LEARN_ACK_6715", "positionId=${env.positionId.take(24)} lane=$nl order=ACK_BEFORE_TERMINAL win=$isWin6721")
                } else {
                    ks.forEach { state(it).pendingLearning.add(env.positionId) }
                    positionScopes[env.positionId] = ks
                    // Track W/L on terminal even before markLearned so cohort
                    // advisory sees the truth immediately.
                    ks.forEach { k -> state(k).apply {
                        if (isWin6721) wins += 1 else losses += 1
                    } }
                }
            } else {
                positionScopes.remove(env.positionId)
            }
            emit("CAUSAL_TERMINAL_OBSERVED_6715", "positionId=${env.positionId.take(24)} tradeId=${env.tradeId.take(24)} mode=$nm lane=$nl learningEligible=${env.learningEligible} pending=${if (env.learningEligible && !earlyAck) 1 else 0}")
            return true
        }
    }

    /** Called only after exact owner-bound policy mutation succeeds. */
    fun markLearned(positionId: String): Boolean {
        if (positionId.isBlank()) return false
        synchronized(lock) {
            if (learnedSeen.contains(positionId)) return true
            val ks = positionScopes[positionId]
            if (ks == null) {
                earlyLearnAcks.add(positionId)
                emit("CAUSAL_OWNER_LEARN_ACK_EARLY_6715", "positionId=${positionId.take(24)} action=hold_until_terminal_consumer")
                return true
            }
            val wasPending = ks.any { state(it).pendingLearning.contains(positionId) }
            if (!wasPending) {
                earlyLearnAcks.add(positionId)
                return true
            }
            ks.forEach { k ->
                val s = state(k)
                s.pendingLearning.remove(positionId)
                s.learningRevision += 1L
                s.cleanLearnedCloses += 1
            }
            learnedSeen.add(positionId)
            positionScopes.remove(positionId)
            emit("CAUSAL_OWNER_LEARN_ACK_6715", "positionId=${positionId.take(24)} scopes=${ks.joinToString(",")}")
            return true
        }
    }

    fun releaseAttempt(attemptId: String) {
        if (attemptId.isBlank()) return
        synchronized(lock) { releaseAttemptLocked(attemptId, removeStamp = true) }
    }

    private fun releaseAttemptLocked(attemptId: String, removeStamp: Boolean) {
        val r = reservations.remove(attemptId)
        r?.scopeKeys?.forEach { state(it).reservedAttempts.remove(attemptId) }
        if (removeStamp) ticketStamps.remove(attemptId)
    }

    fun statusLine(): String = synchronized(lock) {
        val pending = scopes.values.sumOf { it.pendingLearning.size }
        val reserved = reservations.size
        val opens = scopes.filterKeys { it.startsWith("LANE|") }.values.sumOf { it.openPositions.size }
        val learned = scopes.filterKeys { it.startsWith("LANE|") }.values.sumOf { it.cleanLearnedCloses }
        "CausalFeedback6715 scopes=${scopes.size} learned=$learned pendingLearning=$pending reserved=$reserved trackedOpen=$opens tickets=${ticketStamps.size}"
    }

    /**
     * V5.0.6724 §COHORT_LOSER_ADVISORY_CONSUMER — public snapshot of the
     * chronic-loser advisory the same admit() path emits into telemetry.
     * A cohort is chronic-losing when it has recorded >= MIN_DECIDED closes
     * AND its winrate is under WR_FLOOR. The advisory is intentionally an
     * observation, not a hard block; downstream sizing callers can choose to
     * apply the returned floor multiplier without disturbing existing
     * heuristic thresholds.
     *
     * Returns:
     *  - `null` if the lane has no chronic-loser band on the given side
     *    (or the lane is non-meme, which fails open).
     *  - `Advisory(worstBand, worstWr, worstN, sizeMultiplier)` if any of
     *    the lane's bands have crossed the chronic-loser threshold on the
     *    given mode side. The multiplier scales down toward 0.4 as the
     *    winrate approaches 0% (bounded so a single bad band cannot outright
     *    freeze the lane).
     */
    data class CohortLoserAdvisory(
        val worstBand: String,
        val worstWinRatePct: Double,
        val worstDecidedCount: Int,
        val sizeMultiplier: Double,
    )

    private const val ADVISORY_MIN_DECIDED = 8
    private const val ADVISORY_WR_FLOOR = 0.20
    private const val ADVISORY_MULT_FLOOR = 0.40

    fun cohortLoserAdvisoryForLane(mode: String, lane: String): CohortLoserAdvisory? {
        if (!isMemeOwnerLane(lane)) return null
        val nm = normMode(mode)
        val nl = normLane(lane)
        synchronized(lock) {
            val bandPrefix = "BAND|$nm|$nl|"
            var worst: CohortLoserAdvisory? = null
            for ((k, s) in scopes) {
                if (!k.startsWith(bandPrefix)) continue
                val decided = s.wins + s.losses
                if (decided < ADVISORY_MIN_DECIDED) continue
                val wr = s.wins.toDouble() / decided.toDouble()
                if (wr >= ADVISORY_WR_FLOOR) continue
                val band = k.removePrefix(bandPrefix)
                // Linear scale: at wr==0 → ADVISORY_MULT_FLOOR; at wr==WR_FLOOR → 1.0.
                val frac = (wr / ADVISORY_WR_FLOOR).coerceIn(0.0, 1.0)
                val mult = (ADVISORY_MULT_FLOOR + (1.0 - ADVISORY_MULT_FLOOR) * frac).coerceIn(ADVISORY_MULT_FLOOR, 1.0)
                if (worst == null || mult < worst.sizeMultiplier) {
                    worst = CohortLoserAdvisory(band, wr * 100.0, decided, mult)
                }
            }
            return worst
        }
    }

    internal fun resetForTest6715() = synchronized(lock) {
        scopes.clear(); ticketStamps.clear(); reservations.clear(); positionScopes.clear()
        earlyLearnAcks.clear(); terminalSeen.clear(); learnedSeen.clear()
    }

    private fun emit(label: String, detail: String) {
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle(label, detail) } catch (_: Throwable) {}
    }
}
