package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1083 — executable-open finality firewall.
 *
 * FDG/V3/safety fatal decisions are FINAL for real paper/live execution.
 * Learning/probe paths may shadow-simulate, but must not create paper-wallet
 * positions, live swaps, open-position records, or normal BUY journal rows.
 */
object ExecutableOpenGate {
    data class EntryState(
        val mint: String,
        val symbol: String,
        val v3Decision: String = "UNKNOWN",
        val v3FatalReason: String? = null,
        val fdgCan: Boolean? = null,
        val fdgReason: String? = null,
        val safetyTier: String = "UNKNOWN",
        val rugScore: Int = -1,
        val liquidityUsd: Double = 0.0,
        val signal: String = "UNKNOWN",
        val decisionBand: String = "UNKNOWN",
        val selectedLane: String = "UNKNOWN",
        val preFdgVerdict: String = "WATCH",
        val hardNoReasons: List<String> = emptyList(),
        val tokenMapRouteStatus: String = "LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP",
        val tokenMapHydrationComplete: Boolean = false,
        val tokenMapExpectedOut: Double = 0.0,
        val tokenMapProviderAttempts: Int = 0,
        val entryScore: Int = -1,  // V5.9.1373 — for SHADOW_TRAIN_ONLY bucket lookup
        val candidateVersion: Long = 0L,
        val updatedAtMs: Long = System.currentTimeMillis(),
    )

    data class ExecutionTicket(
        val attemptId: String,
        val mint: String,
        val symbol: String,
        val lane: String,
        val mode: String,
        val candidateVersion: Long,
        val candidateId: String = "$mint:$candidateVersion",
        val primaryLane: String = lane,
        val fdgVerdict: String = "BUY",
        val authoritativeSignal: String = "BUY",
        val safetyVerdict: String = "UNKNOWN",
        val authorityVersion: Long = 0L,
        val electionId6494: String = "",
        val authorityVersion6494: Long = 0L,
        val fdgReason: String?,
        val signal: String,
        val safetyTier: String,
        val liquidityUsd: Double,
        val rugScore: Int,
        val hardNoReasons: List<String>,
        val resolvedSizeSol: Double = 0.0,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    data class OpenVerdict(
        val allowed: Boolean,
        val reason: String,
        val shadowOnly: Boolean = false,
        val logName: String = "EXEC_OPEN_ALLOWED",
        val attemptId: String = "",
        val scorePenalty: Int = 0,
        val sizeMultiplier: Double = 1.0,
        val restoreReason: String = "",
        val liquidityOverrideUsd: Double = 0.0,
    )

    private val attemptSeq = AtomicLong(0L)
    fun nextAttemptId(mint: String, lane: String): String = canonicalExecutionKey(mint, lane = lane, ticketId = attemptSeq.incrementAndGet())
    fun canonicalExecutionKey(
        mint: String,
        mode: String = if (FinalExecutionPermit.isPaperMode) "PAPER" else "LIVE",
        side: String = "BUY",
        lane: String = "PRIMARY",
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
        candidateVersion: Long = LaneExecutionCoordinator.candidateVersionFor(mint),
        ticketId: Long = attemptSeq.incrementAndGet(),
    ): String = "$runtimeGeneration:${mode.uppercase()}:${sanitizeMintForKey(mint)}:${side.uppercase()}:${canonicalLane(lane)}:$candidateVersion:$ticketId"

    /**
     * V5.9.1537 — SECURITY: a Solana mint is base58, 32..44 chars, [1-9A-HJ-NP-Za-km-z].
     * Forensic snapshot 5.0.3554 showed an attemptId whose mint slot contained a
     * leaked Groq API key string (a log message had been mis-assigned into a mint
     * variable upstream, then the platform secret-scanner caught it). attemptIds are
     * emitted into forensic logs/telemetry, so ANY non-mint payload here is a secret-
     * exfiltration vector. We hard-clamp the mint slot to a valid base58 shape; if it
     * doesn't match, we substitute a safe redacted token (never the raw value), so a
     * contaminated mint can never carry a secret into a log line again.
     */
    private fun sanitizeMintForKey(mint: String): String {
        val m = mint.trim()
        val base58 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")
        return if (base58.matches(m)) m else "INVALID_MINT_REDACTED"
    }

    /** V5.0.6509 — finalized decision tuple; raw scanner signal is diagnostic only. */
    internal fun canonicalExecutableIntent6509(
        fdgCan: Boolean?, preFdgVerdict: String, hardNoReasons: List<String>,
    ): Boolean = fdgCan == true && hardNoReasons.isEmpty() &&
        (preFdgVerdict.equals("BUY", true) || preFdgVerdict.equals("PROBE_ONLY", true))

    private val states = ConcurrentHashMap<String, EntryState>()
    private val fdgElectionLocks6512 = ConcurrentHashMap<String, Any>()
    private const val TTL_MS = 10 * 60 * 1000L
    private val allowedAttempts = ConcurrentHashMap<String, Pair<String, Long>>()
    private val executionTickets = ConcurrentHashMap<String, ExecutionTicket>()
    private const val EXECUTION_TICKET_TTL_MS = 45_000L
    private val openRequests = ConcurrentHashMap<String, Long>()
    private val blockedCooldowns = ConcurrentHashMap<String, Pair<String, Long>>()
    private val restorePenalties = ConcurrentHashMap<String, OpenVerdict>()
    private val entryAuthority6487 = ConcurrentHashMap<String, com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450.Decision>()
    private val executableBuyClaim6487 = ConcurrentHashMap<String, String>()

    private fun authorityKey6487(mint: String, candidateVersion: Long): String =
        "${BotRuntimeController.currentGeneration()}:${mint.trim()}:$candidateVersion"

    private fun executableClaimKey6487(mode: String, mint: String, candidateVersion: Long): String =
        "${BotRuntimeController.currentGeneration()}:${mode.uppercase()}:${mint.trim()}:$candidateVersion"

    private fun isShadowReadOnlyLane6487(rawLane: String): Boolean =
        rawLane.uppercase().trim().replace('-', '_').replace(' ', '_') in setOf("V3_CORE", "STANDARD", "CASHGEN")

    fun recordEntryAuthority6487(
        mint: String,
        candidateVersion: Long,
        decision: com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450.Decision,
    ) {
        entryAuthority6487[authorityKey6487(mint, candidateVersion)] = decision
        try {
            ForensicLogger.lifecycle(
                "PREFDG_ENTRY_AUTHORITY_6487",
                "mint=${mint.take(10)} version=$candidateVersion verdict=${decision.verdict} recommended=${decision.recommendedSizeSol} reason=${decision.reason}",
            )
        } catch (_: Throwable) {}
    }

    fun restorePenaltyForAttempt(attemptId: String): OpenVerdict? = restorePenalties[attemptId]
    fun consumeRestorePenalty(attemptId: String): OpenVerdict? = restorePenalties.remove(attemptId)

    private fun ticketLive(ticket: ExecutionTicket, now: Long = System.currentTimeMillis()): Boolean =
        now - ticket.createdAtMs <= EXECUTION_TICKET_TTL_MS

    fun ticketForAttempt(attemptId: String): ExecutionTicket? = executionTickets[attemptId]?.takeIf { ticketLive(it) }

    /** V5.0.6514 — revoke every transient ticket/allowed-attempt residue after dispatch. */
    private fun revokeAttempt6514(attemptId: String, mint: String, lane: String) {
        if (attemptId.isNotBlank()) executionTickets.remove(attemptId)
        allowedAttempts.entries.removeIf { (attemptId.isNotBlank() && it.value.first == attemptId) ||
            it.key == mint.trim() || it.key == laneKey(mint, lane) }
        restorePenalties.remove(attemptId)
        executableBuyClaim6487.entries.removeIf { (attemptId.isNotBlank() && it.value.startsWith("$attemptId:")) ||
            it.key.contains(":${mint.trim()}:") }
    }

    fun releaseAttemptNonTerminal6514(attemptId: String, mint: String, lane: String, reason: String) {
        revokeAttempt6514(attemptId, mint, lane)
        try { PipelineHealthCollector.labelInc("PAPER_TICKET_NONTERMINAL_RELEASE_6514") } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle("PAPER_TICKET_NONTERMINAL_RELEASE_6514", "attemptId=$attemptId mint=${mint.take(10)} lane=$lane reason=$reason ticketReleased=true") } catch (_: Throwable) {}
    }

    fun terminalizeAttempt6514(attemptId: String, mint: String, lane: String) = revokeAttempt6514(attemptId, mint, lane)

    private fun publishTicket(ticket: ExecutionTicket) {
        val now = System.currentTimeMillis()
        executionTickets.entries.removeIf { now - it.value.createdAtMs > EXECUTION_TICKET_TTL_MS }
        allowedAttempts.entries.removeIf { now - it.value.second > ALLOWED_ATTEMPT_TTL_MS }
        executionTickets[ticket.attemptId] = ticket
        try { com.lifecyclebot.engine.truth.AateDecisionFabric6512.sealForExecution(ticket.attemptId, ticket.mode, ticket.mint, ticket.candidateVersion, ticket.lane) } catch (_: Throwable) {}
        allowedAttempts[laneKey(ticket.mint, ticket.lane)] = ticket.attemptId to now
        allowedAttempts[ticket.mint.trim()] = ticket.attemptId to now
        try { PipelineHealthCollector.labelInc("EXEC_TICKET_CREATED") } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle("EXEC_TICKET_CREATED", "attemptId=${ticket.attemptId} mint=${ticket.mint.take(10)} symbol=${ticket.symbol} lane=${ticket.lane} version=${ticket.candidateVersion} liq=${ticket.liquidityUsd.toInt()} safety=${ticket.safetyTier}") } catch (_: Throwable) {}
    }

    private fun trueHardTicketKill(reason: String): Boolean {
        val r = reason.uppercase()
        return r.contains("TRUE_ZERO_LIQUIDITY") || r.contains("NO_EXECUTABLE_ROUTE") ||
            r.contains("NO_SELL_ROUTE") || r.contains("SOURCE_IDENTITY_BAD") ||
            r.contains("DUPLICATE_OPEN") || r.contains("CONFIRMED_RUG") ||
            r.contains("RUGCHECK_100") || r.contains("RC_SCORE_0") ||
            r.contains("TRUE_DUPLICATE_OPEN")
    }

    // V5.9.1476 (spec item 4) — per-(mint,log) last-emit ms for PRE_FDG_NOT_BUY drop throttle.
    private val preFdgDropDedupe = ConcurrentHashMap<String, Long>()

    private fun canonicalLane(lane: String): String {
        val raw = lane.uppercase().trim().replace('-', '_').replace(' ', '_')
        return when (raw) {
            "BLUE_CHIP" -> "BLUECHIP"
            "SHIT_COIN" -> "SHITCOIN"
            "MANIP", "MANIPULATED" -> "MANIPULATED"
            "DIP", "DIP_HUNTER" -> "DIP_HUNTER"
            "PROJECT", "PROJECT_SNIPER", "SNIPER" -> "PROJECT_SNIPER"
            "CASHGEN", "CASH_GENERATION" -> "TREASURY"
            else -> raw
        }
    }

    private fun isSourceBucketLane(lane: String): Boolean {
        return canonicalLane(lane) in setOf(
            "CORE", "UNKNOWN", "WATCHLIST", "PUMP_PORTAL", "PUMP_PORTAL_WS",
            "PUMP_FUN", "PUMP_FUN_NEW", "PUMP_FUN_GRADUATE",
            "DEX_TREND", "DEX_TRENDING", "DEX_BOOST", "DEX_BOOSTED",
            "RAYDIUM", "RAYDIUM_N", "RAYDIUM_NEW_POOL", "COINGECKO", "COINGECKO_TRENDING"
        )
    }

    private fun selectedLaneMatchesRequest(selectedLane: String, requestedLane: String): Boolean {
        val selected = canonicalLane(selectedLane)
        val requested = canonicalLane(requestedLane)
        if (selected == requested) return true
        // V5.9.1169 — source buckets are not execution lanes. If FDG selected
        // a real specialist lane and the downstream executor asks via CORE/DEX/
        // RAYDIUM/etc, keep selected specialist authority and continue to the
        // BUY/finality checks. This fixes false SELECTED_LANE_*_REQUEST_CORE
        // blocks without allowing UNKNOWN/WATCH candidates.
        return selected !in setOf("", "UNKNOWN") && isSourceBucketLane(requested)
    }

    fun lanesCompatibleForTests(selectedLane: String, requestedLane: String): Boolean =
        selectedLaneMatchesRequest(selectedLane, requestedLane)

    private fun isRealExecutionLane(lane: String): Boolean {
        val l = canonicalLane(lane)
        return l !in setOf(
            "", "UNKNOWN", "CORE", "WATCHLIST", "PUMP_PORTAL", "PUMP_PORTAL_WS",
            "PUMP_FUN", "PUMP_FUN_NEW", "PUMP_FUN_GRADUATE",
            "DEX_TREND", "DEX_TRENDING", "DEX_BOOST", "DEX_BOOSTED",
            "RAYDIUM", "RAYDIUM_N", "RAYDIUM_NEW", "RAYDIUM_NEW_POOL",
            "SCANNER_DIRECT", "SCANNER_DIRECT_RAYDIUM_NEW_POOL", "SCANNER_DIRECT_DEX_TRENDING",
            "SCANNER_DIRECT_PUMP_FUN_NEW", "SCANNER_DIRECT_PUMP_FUN_GRADUATE"
        )
    }

    private fun laneForRelease(selectedLane: String, requestedLane: String): String {
        val selected = canonicalLane(selectedLane)
        return if (isRealExecutionLane(selected)) selected else canonicalLane(requestedLane)
    }

    private fun candidateInvalidReason(
        state: EntryState?,
        selectedLane: String,
        requestedLane: String,
        preFdgVerdict: String,
        hardNoReasons: List<String>,
        candidateVersion: Long,
        currentVersion: Long,
        mode: String = "",
        lastSafetyCheckMs: Long = -1L,
        mint: String = "",
        symbol: String = "",
        currentLiquidityUsd: Double = 0.0,
        currentSafetyTier: String = "UNKNOWN",
    ): Pair<String, String>? {
        val selected = canonicalLane(selectedLane)
        val requested = canonicalLane(requestedLane)
        // V5.0.6496 §4 → V5.0.6497 §3 — RELAXED SNAPSHOT DRIFT CHECK.
        // Tuple now: (primaryLane, safetyAuthorityTier,
        // canonicalOccupancy, resolvedOrderSizeSol). Volatile fields
        // (candidateVersion, preFdgVerdict) refresh rather than reject.
        if (mint.isNotBlank()) {
            val resolvedForDriftCheck = try {
                com.lifecyclebot.engine.truth.SealedOrderSizeAuthority6497.sealedSize(mint) ?: 0.0
            } catch (_: Throwable) { 0.0 }
            val drift = try {
                com.lifecyclebot.engine.truth.ExecutionSnapshotAuthority6496.matchOrDriftReason(
                    mint = mint,
                    primaryLane = selected,
                    safetyAuthorityTier = currentSafetyTier,
                    canonicalOccupancy = "${mode.uppercase()}:$mint",
                    resolvedOrderSizeSol = resolvedForDriftCheck,
                )
            } catch (_: Throwable) { null }
            if (drift != null) {
                return "EXEC_OPEN_DROPPED_SNAPSHOT_DRIFT_6496" to drift
            }
        }
        if (state == null) {
            // V5.0.4003 — SOURCE FIX: final-candidate state can be swept or
            // overwritten between FDG_ALLOW and liveBuy handoff during scanner storms.
            // Runtime 5.0.4002: FDG allow=110, EXEC_GATE allow=586, BUY ok=0,
            // BUY fail=90 with TOKEN_STATE_CHANGED_NO_FINAL_CANDIDATE. That is not
            // market rejection; it is missing transient state after an approved ticket.
            // Restore ONLY when the caller carries a real execution lane, current
            // liquidity is positive, SAFE/CAUTION safety is present, and no true-hard
            // safety reason is present. Missing-state restore has no FDG state to
            // prove provider-blind approval, so UNKNOWN safety remains blocked.
            val restoredHardNoReasons = hardNoReasons.filterNot { hn ->
                ((hn.equals("ZERO_LIQUIDITY", true) || hn.equals("TRUE_ZERO_LIQUIDITY", true) || hn.equals("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP", true)) && currentLiquidityUsd > 0.0) ||
                    (hn.equals("PRE_FDG_SAFETY_CONTEXT_MISSING", true) &&
                        currentSafetyTier.isNotBlank() && !currentSafetyTier.equals("UNKNOWN", true))
            }
            val currentSafetyOk = currentSafetyTier.equals("SAFE", true) || currentSafetyTier.equals("CAUTION", true)
            val liveExecutableContext = mode.equals("LIVE", true) && isRealExecutionLane(selected) &&
                currentLiquidityUsd > 0.0 && currentSafetyOk && restoredHardNoReasons.none { trueHardTicketKill(it) } &&
                preFdgVerdict.uppercase() in setOf("BUY", "PROBE_ONLY", "WATCH", "PROBE")
            if (liveExecutableContext) {
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_RESTORE_MISSING_FINAL_CANDIDATE_SOFT_ALLOW",
                        "mint=${mint.take(10)} symbol=$symbol selected=$selected requested=$requested preFdg=$preFdgVerdict currentVersion=$currentVersion liq=${currentLiquidityUsd.toInt()} safety=$currentSafetyTier reason=state_missing_after_fdg_allow"
                    )
                    PipelineHealthCollector.labelInc("LIVE_RESTORE_MISSING_FINAL_CANDIDATE_SOFT_ALLOW")
                } catch (_: Throwable) {}
                return null
            }
            // V5.0.6499 §5 — SNAPSHOT EXECUTION RACE. If ExecutionSnapshotAuthority6496
            // has a frozen tuple for this mint (primaryLane +
            // safetyAuthorityTier + canonicalOccupancy + resolvedOrderSizeSol
            // sealed at FDG allow), that IS the frozen authoritative state.
            // We do NOT need the transient FDG state row that scanner storms
            // may have swept — the operator's spec: "The execution ticket
            // should freeze mint + candidateVersion + primaryLane +
            // safetyEpoch + decisionEpoch and only revalidate genuinely
            // safety-critical state before commit. A telemetry/style/lane
            // reshuffle must not invalidate an otherwise accepted paper
            // ticket." Missing FDG state row alone is exactly that class
            // of non-safety drift.
            if (mint.isNotBlank() && isRealExecutionLane(selected) &&
                currentLiquidityUsd > 0.0 &&
                (currentSafetyTier.equals("SAFE", true) || currentSafetyTier.equals("CAUTION", true))) {
                val hasFrozenSnap = try {
                    com.lifecyclebot.engine.truth.ExecutionSnapshotAuthority6496
                        .matchOrDriftReason(
                            mint = mint,
                            primaryLane = selected,
                            safetyAuthorityTier = currentSafetyTier,
                            canonicalOccupancy = "${mode.uppercase()}:$mint",
                            resolvedOrderSizeSol = try {
                                com.lifecyclebot.engine.truth.SealedOrderSizeAuthority6497.sealedSize(mint) ?: 0.0
                            } catch (_: Throwable) { 0.0 },
                        ) == null &&
                        try {
                            com.lifecyclebot.engine.truth.SealedOrderSizeAuthority6497.sealedSize(mint) != null
                        } catch (_: Throwable) { false }
                } catch (_: Throwable) { false }
                if (hasFrozenSnap) {
                    try {
                        ForensicLogger.lifecycle(
                            "EXEC_STATE_RESTORED_FROM_FROZEN_SNAPSHOT_6499",
                            "mint=${mint.take(10)} symbol=$symbol selected=$selected safety=$currentSafetyTier liq=${currentLiquidityUsd.toInt()} action=allow_from_snapshot",
                        )
                        PipelineHealthCollector.labelInc("EXEC_STATE_RESTORED_FROM_FROZEN_SNAPSHOT_6499")
                    } catch (_: Throwable) {}
                    return null
                }
            }
            return "EXEC_OPEN_DROPPED_TOKEN_STATE_CHANGED" to "TOKEN_STATE_CHANGED_NO_FINAL_CANDIDATE"
        }
        // V5.9.1559 — live unchoke: stale context hardNos must not survive
        // after the current candidate proves the context is valid. This strips
        // only derived context labels; real rug/fatal hardNo reasons remain.
        val effectiveHardNoReasons = hardNoReasons.filterNot { hn ->
            ((hn.equals("ZERO_LIQUIDITY", true) || hn.equals("TRUE_ZERO_LIQUIDITY", true) || hn.equals("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP", true)) && currentLiquidityUsd > 0.0) ||
                (hn.equals("PRE_FDG_SAFETY_CONTEXT_MISSING", true) &&
                    currentSafetyTier.isNotBlank() && !currentSafetyTier.equals("UNKNOWN", true))
        }
        // V5.9.1320 (Item 6) — lane is resolved upstream (real selected → real requested →
        // UNKNOWN). Reaching here UNKNOWN means NEITHER lane was real: a genuinely unresolved
        // candidate. Surfaced as CANON_LANE_UNRESOLVED, the canonical terminal reason — NOT a
        // post-allow surprise. (Lane defaulting to STANDARD is intentionally NOT done.)
        if (!isRealExecutionLane(selected)) return "EXEC_OPEN_DROPPED_CANON_LANE_UNRESOLVED" to "CANON_LANE_UNRESOLVED_SELECTED_${selected}_REQUEST_${requested}"
        // V5.9.1483 — PROBE_ONLY IS AN APPROVED BUY (single biggest volume choke).
        // The boolean authority (FinalDecisionGate.canExecute(), line ~44) and the
        // internal finality gate (fdgCan path, line ~588) already treat PROBE_ONLY
        // as executable (dust-size approved buy, NOT a veto). But THIS earlier
        // string-equality precheck demanded literal "BUY", so any candidate whose
        // cached preFdgVerdict resolved to PROBE_ONLY (or whose last lane write was
        // PROBE_ONLY) got dropped as PRE_FDG_NOT_BUY — killing the entire V3 EXECUTE
        // path in the live log (CAINYABEL/MUMU: EXECUTE_AGGRESSIVE -> NO_BUY ->
        // no_open_committed_blocked_finality). Accept PROBE_ONLY here so the string
        // gate matches the boolean contract. Real vetoes (NO_BUY/HARD_NO_BUY/WATCH)
        // still drop. -15% floor, FDG hard-veto, and hardNo gating untouched.
        if (preFdgVerdict != "BUY" && preFdgVerdict != "PROBE_ONLY") {
            // V5.9.1496 — FINALITY REASON NORMALIZATION (spec 5.0.3501 §1).
            // In LIVE mode, when the candidate's verdict is NO_BUY *because*
            // safety is stale/missing, report the SAME canonical reason FDG uses
            // (SAFETY_NOT_READY_STALE / _MISSING) instead of the generic
            // PRE_FDG_NOT_BUY, so FDG, EXEC_GATE, and TradeAuth all agree on the
            // final reason. We also signal an immediate safety refresh so the
            // candidate can be re-evaluated on the next tick (deferred, not a
            // silent discard). FDG's veto + the -15% floor are untouched.
            // Only normalize a STALE-SAFETY NO_BUY — never a real hard veto. A
            // HARD_NO_BUY / RUG / rugScore<0 verdict is a genuine block and MUST
            // keep reporting PRE_FDG_NOT_BUY (invariant tests guard this). We also
            // require a REAL positive safety timestamp (lastSafetyCheckMs > 0) that
            // is genuinely past the stale window — a default/unknown -1 is NOT
            // treated as "missing safety" (that would swallow hard vetoes).
            val verdictUpper = preFdgVerdict.uppercase()
            val isStaleEligibleVerdict =
                verdictUpper == "NO_BUY" || verdictUpper == "WATCH" || verdictUpper == "PROBE"
            if (mode.equals("LIVE", true) && isStaleEligibleVerdict && lastSafetyCheckMs > 0L) {
                val safetyStale =
                    (System.currentTimeMillis() - lastSafetyCheckMs) >
                        com.lifecyclebot.engine.sell.LiveBuyAdmissionGate.SAFETY_STALE_MS
                if (safetyStale) {
                    val canon = "SAFETY_NOT_READY_STALE"
                    try {
                        com.lifecyclebot.engine.ForensicLogger.lifecycle(
                            "EXEC_OPEN_DEFERRED_SAFETY_STALE",
                            "mint=${mint.take(10)} symbol=$symbol reason=$canon refreshRequested=true (finality reason normalized to match FDG)",
                        )
                    } catch (_: Throwable) {}
                    // Request an out-of-band safety refresh for this mint so the
                    // next pipeline pass sees fresh safety and can finalize.
                    try { com.lifecyclebot.engine.SafetyRefreshQueue.request(mint) } catch (_: Throwable) {}
                    return "EXEC_OPEN_DEFERRED_$canon" to canon
                }
            }
            val currentStateVersion = state?.candidateVersion == currentVersion && candidateVersion == currentVersion
            // V5.0.3911 — FDG-approved WATCH/PROBE is a stale string verdict, not
            // a terminal live veto, when the boolean FDG authority allowed the same
            // current candidate and live safety/liquidity are resolved. Report 3909
            // still showed FINALITY_BLOCK:WATCH after FDG live allow. The later
            // staleApprovedVerdict branch could not fire because this function returned
            // WATCH first. Keep HARD_NO/true NO_BUY blocked; restore only FDG-approved
            // WATCH/PROBE/PROBE_ONLY/BUY with no hardNo.
            val verdictAllowedByFdg = state?.fdgCan == true && verdictUpper in setOf("BUY", "PROBE_ONLY", "WATCH", "PROBE")
            val latestAllows = (currentStateVersion || mode.equals("LIVE", true)) && verdictAllowedByFdg
            val safetyKnownOk = currentSafetyTier.equals("SAFE", true) || currentSafetyTier.equals("CAUTION", true) ||
                state?.safetyTier.equals("SAFE", true) || state?.safetyTier.equals("CAUTION", true)
            // V5.0.3955 — FDG-approved provider-blind/UNKNOWN safety is a penalty,
            // not a WATCH finality veto, when liquidity is nonzero and hardNo is empty.
            // Confirmed rugs and zero-liquidity still block later in the gate.
            val safetyBlindSoftAllow = mode.equals("LIVE", true) && state?.fdgCan == true && effectiveHardNoReasons.isEmpty()
            val safetyOk = safetyKnownOk || safetyBlindSoftAllow
            // V5.9.1559 — LIVE finality restore must use the CURRENT candidate
            // liquidity, not the stale EntryState liquidity. Operator log showed
            // current liq=$1599 but cached finality liq=0 → preFdg WATCH dropped
            // a lane-approved live candidate.
            val effectiveLiq = maxOf(currentLiquidityUsd, state?.liquidityUsd ?: 0.0)
            // V5.0.3952 — LOW-LIQ WATCH RESTORE ALIGNMENT.
            // Low but nonzero liquidity is a sizing/quote penalty, not an
            // executable-open finality block. Runtime 3951 still showed one
            // FINALITY_BLOCK:WATCH while TokenSafetyChecker correctly emitted
            // LOW_LIQUIDITY_SIZE_REDUCED. Restore the FDG-approved WATCH and let
            // LiveRestoreExecutionPolicy/realisticLiveEntrySize clamp size.
            val liqOk = effectiveLiq > 0.0
            if (mode.equals("LIVE", true) && latestAllows && safetyOk && liqOk && effectiveHardNoReasons.isEmpty()) {
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_RESTORE_STALE_WATCH_SOFT_ALLOW",
                        "mint=${mint.take(10)} symbol=$symbol preFdg=$preFdgVerdict fdgCan=${state?.fdgCan} stateVersion=${state?.candidateVersion} currentVersion=$currentVersion stateLiq=${(state?.liquidityUsd ?: 0.0).toInt()} currentLiq=${currentLiquidityUsd.toInt()} currentSafety=$currentSafetyTier stateSafety=${state?.safetyTier} safetyKnownOk=$safetyKnownOk safetyBlindSoftAllow=$safetyBlindSoftAllow penalty=WATCH_FINALITY_SOFT_ALLOW"
                    )
                } catch (_: Throwable) {}
                return null
            }
            return "EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY" to preFdgVerdict
        }
        if (effectiveHardNoReasons.isNotEmpty()) return "EXEC_OPEN_DROPPED_HARD_NO_BUY" to effectiveHardNoReasons.joinToString("+")
        if (candidateVersion != currentVersion) {
            // V5.0.4003 — restore approved live handoff across version churn.
            // Historical invariant: EXEC_GATE_ALLOW>0 but EXEC_LIVE_ATTEMPT=0 must
            // not recur from approved candidate-version churn.
            // 5.0.3861 disabled this with literal false/false, which was safe for
            // preventing stale buys but fatal under current scanner churn: tickets age
            // out as STALE_CANDIDATE_VERSION even though the same mint still has an
            // FDG-approved BUY/PROBE, real liquidity, and no true hard safety kill.
            val verdictUpper = preFdgVerdict.uppercase()
            val latestAllows = mode.equals("LIVE", true) && state.fdgCan == true &&
                verdictUpper in setOf("BUY", "PROBE_ONLY", "WATCH", "PROBE")
            val safetyOk = currentSafetyTier.equals("SAFE", true) || currentSafetyTier.equals("CAUTION", true) ||
                state.safetyTier.equals("SAFE", true) || state.safetyTier.equals("CAUTION", true) ||
                (mode.equals("LIVE", true) && state.fdgCan == true && effectiveHardNoReasons.none { trueHardTicketKill(it) })
            val effectiveLiq = maxOf(currentLiquidityUsd, state.liquidityUsd)
            val liqOk = effectiveLiq > 0.0
            if (latestAllows && safetyOk && liqOk && effectiveHardNoReasons.none { trueHardTicketKill(it) }) {
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_RESTORE_STALE_CANDIDATE_SOFT_ALLOW",
                        "mint=${mint.take(10)} symbol=$symbol candidateVersion=$candidateVersion currentVersion=$currentVersion stateVersion=${state.candidateVersion} liq=${effectiveLiq.toInt()} safety=$currentSafetyTier stateSafety=${state.safetyTier} reason=approved_handoff_version_churn"
                    )
                    PipelineHealthCollector.labelInc("LIVE_RESTORE_STALE_CANDIDATE_SOFT_ALLOW")
                } catch (_: Throwable) {}
                return null
            }
            return "EXEC_OPEN_DROPPED_STALE_CANDIDATE" to "STALE_CANDIDATE_VERSION_$candidateVersion"
        }
        return null
    }

    private fun laneKey(mint: String, lane: String): String = mint + ":" + canonicalLane(lane).filter { it.isLetterOrDigit() }
    private const val ALLOWED_ATTEMPT_TTL_MS = 60_000L

    fun resetForTests() {
        states.clear()
        allowedAttempts.clear()
        executionTickets.clear()
        openRequests.clear()
        blockedCooldowns.clear()
        entryAuthority6487.clear()
        executableBuyClaim6487.clear()
    }

    private fun cooldownMsFor(log: String, reason: String): Long {
        val r = reason.uppercase()
        return when {
            log.contains("RUNTIME") || r.contains("CIRCUIT") || r.contains("LOCKDOWN") -> 120_000L
            log.contains("FATAL_V3") || r.contains("EXTREME_RUG") || r.contains("TRUE_ZERO_LIQUIDITY") -> 10 * 60_000L
            r.contains("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP") || r.contains("TOKEN_MAP_PENDING") -> 30_000L
            r.contains("WAIT") -> 60_000L
            r.contains("INSUFFICIENT") -> 30_000L
            r.contains("LOW_LIQUIDITY") || r.contains("LIQUIDITY_BELOW") -> 60_000L
            log.contains("FDG") -> 30_000L
            else -> 15_000L
        }
    }

    fun recentAllowedAttemptId(mint: String, lane: String): String? {
        val now = System.currentTimeMillis()
        allowedAttempts.entries.removeIf { now - it.value.second > ALLOWED_ATTEMPT_TTL_MS }
        return allowedAttempts[laneKey(mint, lane)]?.takeIf { now - it.second <= ALLOWED_ATTEMPT_TTL_MS }?.first
    }

    // V5.0.3731 — lane-agnostic approved handoff lookup.
    // Runtime 5.0.3730 showed FDG/TradeAuthorizer approving MOONSHOT for BANNED
    // (candidateVersion=59383825), then the V3 executor looked only for CORE/V3 and
    // fell into a blank/STANDARD attempt, producing candidateVersion=0 and
    // NO_FINAL_BUY_CANDIDATE. When an approved lane exists for the same mint inside
    // the handoff TTL, downstream V3/liveBuy must reuse it instead of inventing a
    // STANDARD/WATCH candidate.
    fun recentAllowedAttemptIdAnyLane(mint: String): String? {
        val now = System.currentTimeMillis()
        allowedAttempts.entries.removeIf { now - it.value.second > ALLOWED_ATTEMPT_TTL_MS }
        val sanitized = sanitizeMintForKey(mint)
        return allowedAttempts.entries
            .asSequence()
            .filter { (_, v) -> now - v.second <= ALLOWED_ATTEMPT_TTL_MS }
            .map { it.value.first }
            .firstOrNull { it.contains(":${sanitized}:BUY:") }
    }


    private fun staleCutoff() = System.currentTimeMillis() - TTL_MS

    private fun put(mint: String, update: (EntryState?) -> EntryState) {
        try {
            states.entries.removeIf { it.value.updatedAtMs < staleCutoff() }
            states[mint] = update(states[mint])
        } catch (_: Throwable) {}
    }

    fun recordV3(
        mint: String,
        symbol: String,
        decision: String,
        fatalReason: String? = null,
        decisionBand: String = decision,
        rugScore: Int = -1,
        safetyTier: String = "UNKNOWN",
    ) {
        put(mint) { old ->
            (old ?: EntryState(mint = mint, symbol = symbol)).copy(
                symbol = symbol,
                v3Decision = decision,
                v3FatalReason = fatalReason,
                decisionBand = decisionBand,
                rugScore = if (rugScore >= 0) rugScore else old?.rugScore ?: -1,
                // V5.9.1559 — do not let a V3 call with default UNKNOWN erase
                // live safety context written by FDG/safety. This exact overwrite
                // produced EXEC_GATE safetyTier=UNKNOWN after SAFETY_WRITE SAFE.
                safetyTier = if (safetyTier.isNotBlank() && !safetyTier.equals("UNKNOWN", true)) safetyTier else old?.safetyTier ?: "UNKNOWN",
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun recordFdg(
        mint: String,
        symbol: String,
        lane: String,
        canExecute: Boolean,
        reason: String?,
        signal: String = "BUY",
        rugScore: Int = -1,
        safetyTier: String = "UNKNOWN",
        liquidityUsd: Double = 0.0,
        hardNoReasons: List<String> = emptyList(),
        preFdgVerdict: String = if (canExecute) "BUY" else "NO_BUY",
        candidateVersion: Long = LaneExecutionCoordinator.candidateVersionFor(mint),
        entryScore: Int = -1,  // V5.9.1373 — drives SHADOW_TRAIN_ONLY gate
        tokenMapRouteStatus: String = "",
        tokenMapHydrationComplete: Boolean = false,
        tokenMapExpectedOut: Double = 0.0,
        tokenMapProviderAttempts: Int = 0,
    ) {
        val paperRuntime = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
        if (isShadowReadOnlyLane6487(lane)) {
            try {
                PipelineHealthCollector.labelInc("SHADOW_LANE_FDG_SUPPRESSED_6487_${lane.uppercase()}")
                ForensicLogger.lifecycle("SHADOW_LANE_FDG_SUPPRESSED_6487", "mint=${mint.take(10)} symbol=$symbol lane=${lane.uppercase()} version=$candidateVersion action=score_report_learn_only")
            } catch (_: Throwable) {}
            return
        }
        val preEntry6487 = entryAuthority6487[authorityKey6487(mint, candidateVersion)]
        if (preEntry6487 != null && preEntry6487.verdict !in setOf(
                com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450.Verdict.ALLOW,
                com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450.Verdict.ALLOW_PROBE,
            )) {
            try {
                PipelineHealthCollector.labelInc("FDG_SUPPRESSED_ENTRY_AUTHORITY_6487")
                ForensicLogger.lifecycle("FDG_SUPPRESSED_ENTRY_AUTHORITY_6487", "mint=${mint.take(10)} symbol=$symbol lane=${lane.uppercase()} version=$candidateVersion verdict=${preEntry6487.verdict} reason=${preEntry6487.reason}")
            } catch (_: Throwable) {}
            return
        }
        val tokenRouteUpper = tokenMapRouteStatus.uppercase()
        val tokenMapExecutable = tokenRouteUpper == "PUMPFUN_BONDING_CURVE_EXECUTABLE" || tokenRouteUpper == "DEX_ROUTABLE"
        val tokenMapNoRoute = tokenRouteUpper in setOf("NO_ROUTE", "TRUE_ZERO_LIQUIDITY")
        val tokenMapTrueZero = tokenMapHydrationComplete && tokenMapNoRoute && tokenMapExpectedOut <= 0.0 && tokenMapProviderAttempts >= 2
        val finalHardNo = hardNoReasons.toMutableList().apply {
            if (liquidityUsd <= 0.0 && tokenMapTrueZero) add("TRUE_ZERO_LIQUIDITY")
            if (liquidityUsd <= 0.0 && !tokenMapTrueZero && !tokenMapExecutable) add("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP")
            // V5.0.3915 — hard-block residue purge. FDG/ticket hardNo may only
            // encode mechanical impossibility / confirmed rug. Missing safety, pending
            // RC, low-but-nonzero RC, LP/mint/dev/holder warnings are penalty/size
            // clamps downstream, not pre-submit finality killers.
            if (rugScore == 0) add("RC_SCORE_0")
        }.distinct()
        // V5.9.1545 — STALE-VERDICT LEAK ROOT FIX (re-surfaced choke).
        // The snapshot showed candidates with FDG_ALLOW=PROBE_ONLY (canExecute=true)
        // being WRITTEN to state as WATCH, then read back by candidateInvalidReason
        // and DROPPED as EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY (preFdg=WATCH). Cause:
        // this when() only recognised literal signal=="BUY"/"EXECUTE" as executable.
        // A PROBE_ONLY approval (canExecute=true, but signal not literally "BUY")
        // fell through to the else->"WATCH" branch — overwriting an APPROVED dust-buy
        // with a hard-drop verdict. That directly contradicts canExecute() (FDG line
        // ~44) and the V5.9.1483 string-gate fix, both of which treat PROBE_ONLY as
        // executable. FIX: if FDG says canExecute (no hard-no), the state verdict is
        // EXECUTABLE — preserve an explicit PROBE_ONLY (so the dust-size path stays
        // intact) and otherwise BUY. Never downgrade an approved candidate to WATCH.
        val incomingProbe = preFdgVerdict.equals("PROBE_ONLY", true) ||
                            (reason?.equals("PROBE_ONLY", true) == true)
        val finalVerdict = when {
            finalHardNo.isNotEmpty() -> "HARD_NO_BUY"
            !canExecute -> preFdgVerdict.takeIf { it != "BUY" } ?: "NO_BUY"
            incomingProbe -> "PROBE_ONLY"   // approved dust-buy — must NOT become WATCH
            signal.equals("BUY", true) || signal.equals("EXECUTE", true) -> "BUY"
            // canExecute=true with no hard-no is an APPROVAL regardless of the raw
            // signal label — treat as executable PROBE_ONLY rather than WATCH-dropping it.
            else -> "PROBE_ONLY"
        }
        val winningState6512 = synchronized(fdgElectionLocks6512.computeIfAbsent(mint) { Any() }) {
            var resolvedWinner6512: EntryState? = null
            put(mint) { old ->
            // V5.9.1545 — VERDICT PRECEDENCE (multi-lane last-write-wins clobber fix).
            // A single candidate (e.g. KNECKS) is evaluated across many lanes in one
            // tick; each lane calls this writer. Plain last-write-wins meant a later
            // lane resolving WATCH/NO_BUY could OVERWRITE an earlier lane's approved
            // BUY/PROBE_ONLY for the SAME candidateVersion, then the finality gate read
            // that stale WATCH and dropped the token. Rank verdicts and only let a
            // verdict overwrite when it is >= the stored one (within the same version);
            // a newer candidateVersion always wins (genuinely fresh evaluation).
            fun rank(v: String?): Int = when (v?.uppercase()) {
                "BUY" -> 3; "PROBE_ONLY" -> 2; "WATCH", "PROBE" -> 1
                "NO_BUY" -> 0; "HARD_NO_BUY" -> 0; else -> 1
            }
            val sameVersion = old != null && old.candidateVersion == candidateVersion
            val keepOld = sameVersion && rank(old?.preFdgVerdict) >= rank(finalVerdict)
            val effectiveVerdict = if (keepOld) old!!.preFdgVerdict else finalVerdict
            val effectiveCan = if (keepOld) old!!.fdgCan else canExecute
            (old ?: EntryState(mint = mint, symbol = symbol)).copy(
                symbol = if (keepOld) old?.symbol ?: symbol else symbol,
                fdgCan = effectiveCan,
                fdgReason = if (keepOld) old?.fdgReason else reason,
                signal = if (keepOld) old?.signal ?: "UNKNOWN" else signal.ifBlank { "UNKNOWN" },
                decisionBand = if (keepOld) old?.decisionBand ?: effectiveVerdict else if (effectiveVerdict == "BUY") "BUY" else effectiveVerdict,
                selectedLane = if (keepOld) old?.selectedLane ?: "UNKNOWN" else lane.uppercase(),
                preFdgVerdict = effectiveVerdict,
                hardNoReasons = if (keepOld) (old?.hardNoReasons ?: finalHardNo) else finalHardNo,
                candidateVersion = if (keepOld) old?.candidateVersion ?: candidateVersion else candidateVersion,
                entryScore = if (entryScore >= 0) entryScore else old?.entryScore ?: -1,
                liquidityUsd = if (liquidityUsd > 0.0) liquidityUsd else old?.liquidityUsd ?: 0.0,
                tokenMapRouteStatus = tokenMapRouteStatus.ifBlank { old?.tokenMapRouteStatus ?: "LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP" },
                tokenMapHydrationComplete = tokenMapHydrationComplete || old?.tokenMapHydrationComplete == true,
                tokenMapExpectedOut = if (tokenMapExpectedOut > 0.0) tokenMapExpectedOut else old?.tokenMapExpectedOut ?: 0.0,
                tokenMapProviderAttempts = if (tokenMapProviderAttempts > 0) tokenMapProviderAttempts else old?.tokenMapProviderAttempts ?: 0,
                rugScore = if (rugScore >= 0) rugScore else old?.rugScore ?: -1,
                safetyTier = if (safetyTier.isNotBlank() && !safetyTier.equals("UNKNOWN", true)) safetyTier else old?.safetyTier ?: "UNKNOWN",
                updatedAtMs = System.currentTimeMillis(),
            ).also { resolvedWinner6512 = it }
        }
            val winner = resolvedWinner6512
            if (winner?.fdgCan == true && winner.hardNoReasons.isEmpty() && winner.preFdgVerdict in setOf("BUY", "PROBE_ONLY")) {
                try {
                    val identity6512 = TradeIdentityManager.getOrCreate(mint, winner.symbol)
                    identity6512.executionLane = winner.selectedLane
                    identity6512.fdgCandidateVersion = winner.candidateVersion
                    identity6512.fdgVerdictSnapshot = winner.preFdgVerdict
                    com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510.record(
                        com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot(
                            mint = mint, candidateVersion = winner.candidateVersion,
                            verdict = winner.preFdgVerdict, executionLane = winner.selectedLane,
                            score = winner.entryScore.toDouble(), generatedAtMs = System.currentTimeMillis(),
                            authoritativeSignal = "BUY", safetyVerdict = winner.safetyTier,
                            resolvedSizeSol = try { com.lifecyclebot.engine.truth.SealedOrderSizeAuthority6497.sealedSize(mint) ?: 0.0 } catch (_: Throwable) { 0.0 },
                        )
                    )
                    val mode6512 = if (paperRuntime) "PAPER" else "LIVE"
                    if (com.lifecyclebot.engine.truth.AateDecisionFabric6512.get(mode6512, mint, winner.candidateVersion, winner.selectedLane) == null) {
                        com.lifecyclebot.engine.truth.AateDecisionFabric6512.record(
                            com.lifecyclebot.engine.truth.PolicySynthesizer6512.synthesize(
                                context = com.lifecyclebot.engine.truth.AateStrategyContext6512("$mint:${winner.candidateVersion}", BotRuntimeController.currentGeneration(), mode6512, mint, winner.symbol, winner.candidateVersion, winner.selectedLane, "FDG_HANDOFF", "UNKNOWN"),
                                proposedAction = winner.preFdgVerdict, scoreBase = winner.entryScore.toDouble(), scoreFinal = winner.entryScore.toDouble(),
                                sizeBase = 0.0, sizeFinal = 0.0, tactic = winner.selectedLane, hardSafety = winner.hardNoReasons,
                                contributors = listOf(com.lifecyclebot.engine.truth.AateBrainContribution6512("FinalDecisionGate", "CONTRIBUTOR", 1.0, 0.25, pWin = if (winner.fdgCan == true) 0.65 else 0.35)),
                                learningState = "FDG_FALLBACK_ENVELOPE",
                            )
                        )
                    }
                } catch (_: Throwable) {}
            }
            winner
        }
        // V5.0.6487 — FDG records state only. Execution tickets are created
        // atomically at final EXEC_GATE allow after lane election and entry authority.
        try {
            val hard = finalHardNo.joinToString(prefix = "[", postfix = "]")
            val msg = "symbol=$symbol lane=${lane.uppercase()} preFdg=$finalVerdict hardNo=$hard safety=$safetyTier rug=$rugScore liq=${liquidityUsd.toInt()} duplicate=false circuit=${ToxicModeCircuitBreaker.currentEntryPause().active} sellPressure=${reason ?: "OK"} version=$candidateVersion"
            // V5.9.1320 (Item 6) — emit a canonical FDG DECISION so the health snapshot's
            // "verdicts produced" counter increments whenever FDG produces an allow/block
            // verdict. ForensicLogger.decision() had ZERO callers, which is why the funnel
            // always showed verdicts produced=0 despite FDG running. phase() bumps phaseCounts;
            // decision() bumps verdictCounts — both are needed.
            val executableFdg = winningState6512?.fdgCan == true && winningState6512.hardNoReasons.isEmpty() && winningState6512.preFdgVerdict in setOf("BUY", "PROBE_ONLY")
            val verdictLabel = if (executableFdg) finalVerdict else "BLOCK"
            try { ForensicLogger.decision(ForensicLogger.PHASE.FDG, symbol, verdictLabel, 0, 0, reason ?: finalHardNo.firstOrNull() ?: verdictLabel) } catch (_: Throwable) {}
            if (executableFdg) {
                ErrorLogger.info("FDG", "FDG_ALLOW $symbol lane=${lane.uppercase()} preFdg=$finalVerdict hardNo=[] safety=$safetyTier rug=$rugScore liq=${liquidityUsd.toInt()} duplicate=false circuit=${ToxicModeCircuitBreaker.currentEntryPause().active} sellPressure=${reason ?: "OK"} version=$candidateVersion")
                ForensicLogger.phase(ForensicLogger.PHASE.FDG, symbol, "FDG_ALLOW $msg")
                // V5.0.6497 §3 — RELAXED SNAPSHOT TUPLE.
                // 6496 sealed (candidateVersion, primaryLane, preFdgVerdict,
                // authorityVersion). candidateVersion + preFdgVerdict are
                // volatile by design (state churn on fresh ticks) and were
                // rejecting 39 legitimate candidates. Operator's exact
                // spec: seal (primaryLane, safetyAuthorityTier,
                // canonicalOccupancy, resolvedOrderSizeSol). Volatile
                // market data refreshes rather than rejects.
                try {
                    val resolvedSizeForSeal = try {
                        com.lifecyclebot.engine.truth.SealedOrderSizeAuthority6497
                            .sealedSize(mint) ?: 0.0
                    } catch (_: Throwable) { 0.0 }
                    com.lifecyclebot.engine.truth.ExecutionSnapshotAuthority6496.record(
                        mint = mint,
                        primaryLane = winningState6512?.selectedLane ?: lane.uppercase(),
                        safetyAuthorityTier = winningState6512?.safetyTier ?: safetyTier,
                        canonicalOccupancy = "${if (paperRuntime) "PAPER" else "LIVE"}:$mint",
                        resolvedOrderSizeSol = resolvedSizeForSeal,
                    )
                } catch (_: Throwable) {}
            } else {
                ErrorLogger.info("FDG", "FDG_BLOCK $symbol lane=${lane.uppercase()} preFdg=$finalVerdict hardNo=$hard reason=${reason ?: finalHardNo.firstOrNull() ?: "FDG_BLOCK"}")
                ForensicLogger.phase(ForensicLogger.PHASE.FDG, symbol, "FDG_BLOCK $msg reason=${reason ?: finalHardNo.firstOrNull() ?: "FDG_BLOCK"}")
            }
        } catch (_: Throwable) {}
    }

    fun clearExecutableApproval(mint: String, symbol: String, reason: String = "EXECUTE") {
        put(mint) { old ->
            (old ?: EntryState(mint = mint, symbol = symbol)).copy(
                symbol = symbol,
                v3Decision = reason,
                v3FatalReason = null,
                decisionBand = reason,
                fdgCan = old?.fdgCan,
                fdgReason = old?.fdgReason,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun canOpenExecutablePosition(
        mint: String,
        symbol: String,
        rugScore: Int,
        mode: String,
        lane: String,
        source: String,
        attemptId: String = nextAttemptId(mint, lane),
        liveLiquidityUsd: Double = -1.0,
        liveSafetyTier: String = "",
        lastSafetyCheckMs: Long = -1L,
        preResolvedSizeSol6490: Double = -1.0,
        electedLane6494: String = "",
        electedCandidateVersion6494: Long = 0L,
        electionId6494: String = "",
        authorityVersion6494: Long = 0L,
    ): OpenVerdict {
        // V5.0.6506 §P0-2 — canonical lane alias fold at boundary.
        @Suppress("NAME_SHADOWING") val lane = com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(lane)
        @Suppress("NAME_SHADOWING") val electedLane6494 = com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(electedLane6494)
        return canOpenExecutablePositionInternal(
            mint = mint,
            symbol = symbol,
            rug = rugScore,
            mode = mode,
            lane = lane,
            source = source,
            attemptId = attemptId,
            liveLiquidityUsd = liveLiquidityUsd,
            liveSafetyTier = liveSafetyTier,
            lastSafetyCheckMs = lastSafetyCheckMs,
            preResolvedSizeSol6490 = preResolvedSizeSol6490,
            electedLane6494 = electedLane6494,
            electedCandidateVersion6494 = electedCandidateVersion6494,
            electionId6494 = electionId6494,
            authorityVersion6494 = authorityVersion6494,
        )
    }

    fun canOpenExecutablePosition(
        ts: TokenState,
        mode: String,
        lane: String,
        source: String,
        attemptId: String = nextAttemptId(ts.mint, lane),
        preResolvedSizeSol6490: Double = -1.0,
        electedLane6494: String = "",
        electedCandidateVersion6494: Long = 0L,
        electionId6494: String = "",
        authorityVersion6494: Long = 0L,
    ): OpenVerdict {
        // V5.0.6506 §P0-2 — CANONICAL LANE ALIAS FOLD AT BOUNDARY.
        // Legacy aliases (BLUE_CHIP, MOON_SHOT, PROJECT-SNIPER, MICROCAP)
        // migrate on read to their canonical form so lane comparisons,
        // snapshots and authority-version stamps cannot fragment
        // (previously produced EXEC_OPEN_DROPPED_CANON_LANE_UNRESOLVED).
        @Suppress("NAME_SHADOWING") val lane = com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(lane)
        @Suppress("NAME_SHADOWING") val electedLane6494 = com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(electedLane6494)
        // V5.0.6387 — CANONICAL_LEDGER_PARITY_HOLD_6387 (Directive A P0) +
        // FALSE_PROFIT_TRIGGER_HOLD_6387 (Directive B P0). Either hold blocks
        // ALL new live BUYs until parity + price-identity are validated for
        // 5 consecutive clean cycles.
        if (mode.equals("LIVE", ignoreCase = true)) {
            // V5.0.6401 §4 — attempt to auto-clear the startup exit-only
            // umbrella BEFORE evaluating it. This prevents transient
            // startup deferral (activeReason == "STARTUP_DEFAULT") from
            // producing terminal BUY_FAILs like the 19-row snapshot showed
            // in V5.0.6400. Real runtime exit-only reasons are preserved.
            try { com.lifecyclebot.engine.truth.StartupExitOnlyLatch6401.checkAndClearStartupDefault() } catch (_: Throwable) {}
            // V5.0.6387 P0 — LIVE_EXIT_ONLY umbrella. Blocks all new live BUYs
            // whenever any of the 9 startup invariants is false. Stops, emergency,
            // operator + reconciler sells continue via their own paths.
            val exitOnly = com.lifecyclebot.engine.truth.LiveExitOnlyMode6387.isActive()
            if (exitOnly) {
                val reason = com.lifecyclebot.engine.truth.LiveExitOnlyMode6387.activeReason() ?: "LIVE_EXIT_ONLY_ACTIVE"
                // V5.0.6401 §4 — STARTUP_DEFAULT never becomes a terminal
                // failure. Emit a DEFERRAL signal that Executor routes to
                // BUY_DEFERRED_STARTUP (requeue, no BUY_FAIL, no counter
                // inflation).
                if (reason == "STARTUP_DEFAULT") {
                    try {
                        com.lifecyclebot.engine.truth.StartupExitOnlyLatch6401.classifyDeferral()
                        PipelineHealthCollector.labelInc("BUY_DEFERRED_STARTUP_6401")
                        ForensicLogger.lifecycle("BUY_DEFERRED_STARTUP_6401",
                            "attemptId=$attemptId mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${canonicalLane(lane)} source=$source reason=$reason")
                    } catch (_: Throwable) {}
                    return OpenVerdict(allowed = false,
                        reason = "BUY_DEFERRED_STARTUP_6401",
                        shadowOnly = true,
                        logName = "BUY_DEFERRED_STARTUP_6401",
                        attemptId = attemptId)
                }
                try {
                    PipelineHealthCollector.labelInc("LIVE_EXIT_ONLY_BUY_BLOCKED_6387")
                    ForensicLogger.lifecycle("LIVE_EXIT_ONLY_BUY_BLOCKED_6387",
                        "attemptId=$attemptId mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${canonicalLane(lane)} source=$source reason=$reason")
                } catch (_: Throwable) {}
                return OpenVerdict(allowed = false,
                    reason = "LIVE_EXIT_ONLY_ACTIVE:$reason",
                    shadowOnly = true,
                    logName = "LIVE_EXIT_ONLY_BUY_BLOCKED_6387",
                    attemptId = attemptId)
            }
            val ledgerHold = com.lifecyclebot.engine.truth.CanonicalLedgerParityHold6387.isActive()
            val priceHold = com.lifecyclebot.engine.truth.FalseProfitTriggerHold6387.isActive()
            if (ledgerHold || priceHold) {
                val holdReason = when {
                    ledgerHold && priceHold -> "CANONICAL_LEDGER_PARITY_HOLD_6387+FALSE_PROFIT_TRIGGER_HOLD_6387"
                    ledgerHold -> com.lifecyclebot.engine.truth.CanonicalLedgerParityHold6387.BLOCK_REASON
                    else -> com.lifecyclebot.engine.truth.FalseProfitTriggerHold6387.BLOCK_REASON
                }
                try {
                    PipelineHealthCollector.labelInc(holdReason)
                    ForensicLogger.lifecycle(holdReason,
                        "attemptId=$attemptId mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${canonicalLane(lane)} source=$source action=blocked_6387_safety_hold cleanCycles=${com.lifecyclebot.engine.truth.CanonicalLedgerParityHold6387.cleanCycleCount()}")
                } catch (_: Throwable) {}
                return OpenVerdict(
                    allowed = false, reason = holdReason, shadowOnly = true,
                    logName = holdReason, attemptId = attemptId,
                )
            }
        }
        // V5.0.6385 — LIVE ACCOUNTING REPAIR MODE (operator directive Section 1).
        // Hard-reject every new LIVE BUY signature until Bundles 6386-6390 land
        // the finalized-proof BUY/SELL rails. Paper + shadow evaluation, existing
        // live monitoring, and verified exits (SELL path) are unaffected — we only
        // block NEW live openings. Once the canary gate criteria pass, Bundle 6390
        // will call `LiveAccountingRepairMode6385.disable()`.
        if (mode.equals("LIVE", ignoreCase = true) && LiveAccountingRepairMode6385.isActive()) {
            try {
                val canonLane = canonicalLane(lane)
                LiveAccountingRepairMode6385.recordLiveBuyBlocked(
                    "attemptId=$attemptId mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=$canonLane source=$source action=blocked_sell_only_repair",
                )
            } catch (_: Throwable) {}
            return OpenVerdict(
                allowed = false,
                reason = LiveAccountingRepairMode6385.BLOCK_REASON,
                shadowOnly = true,
                logName = LiveAccountingRepairMode6385.BLOCK_REASON,
                attemptId = attemptId,
            )
        }
        // V5.0.6382 — WAVE ENTRY QUALITY GATE (operator directive: "buys in the
        // wrong waves of the chart"). Reject candidates already blown off the top
        // of their own recent wave before any lane finality logic runs. Fail-open
        // (null = allow). Score-band aware; self-clearing when the wave cools.
        // Applies to PAPER + LIVE — paper trades train the model too, so buying
        // top-ticks in paper poisons the learning signal exactly the same way.
        run {
            val modeUpper = mode.uppercase()
            if (modeUpper == "LIVE" || modeUpper == "PAPER") {
                val entryScore = try {
                    ts.lastV3Score ?: states[ts.mint]?.entryScore ?: -1
                } catch (_: Throwable) { -1 }
                if (entryScore >= 0) {
                    val waveVeto = try { WaveEntryQualityGate6382.evaluate(ts, entryScore) } catch (_: Throwable) { null }
                    if (waveVeto != null) {
                        try {
                            val canonLane = canonicalLane(lane)
                            PipelineHealthCollector.labelInc("EXEC_OPEN_BLOCKED_WAVE_TOO_LATE_6382|${canonLane}")
                            ForensicLogger.lifecycle(
                                "EXEC_OPEN_BLOCKED_WAVE_TOO_LATE_6382",
                                "mint=${ts.mint.take(10)} symbol=${ts.symbol} mode=$modeUpper lane=$canonLane $waveVeto attemptId=$attemptId action=blocked_wrong_wave",
                            )
                        } catch (_: Throwable) {}
                        return OpenVerdict(
                            allowed = false,
                            reason = waveVeto,
                            shadowOnly = modeUpper == "PAPER",
                            logName = "EXEC_OPEN_BLOCKED_WAVE_TOO_LATE_6382",
                            attemptId = attemptId,
                        )
                    }
                }
            }
        }
        return canOpenExecutablePositionInternal(
            mint = ts.mint,
            symbol = ts.symbol,
            rug = ts.safety.rugcheckScore,
            mode = mode,
            lane = lane,
            source = source,
            attemptId = attemptId,
            // V5.9.1367 — DATA INTEGRITY: feed the LIVE token context straight from
            // the TokenState the caller is holding. The gate previously read liq/tier
            // ONLY from the shared per-mint EntryState (populated by recordFdg). If that
            // record lagged, ran with stale context, or belonged to a different lane,
            // the gate saw liquidityUsd=0 / safetyTier=UNKNOWN for a token that demonstrably
            // had real liquidity (e.g. SantaHat $13,928) and wrongly blocked it. The live
            // ts numbers are the ground truth at decision time — pass them so the gate
            // never trusts a stale zero over a known-good live value.
            liveLiquidityUsd = ts.lastLiquidityUsd,
            liveSafetyTier = ts.safety.tier.name,
            lastSafetyCheckMs = ts.lastSafetyCheck,
            preResolvedSizeSol6490 = preResolvedSizeSol6490,
            electedLane6494 = electedLane6494,
            electedCandidateVersion6494 = electedCandidateVersion6494,
            electionId6494 = electionId6494,
            authorityVersion6494 = authorityVersion6494,
        )
    }

    private fun canOpenExecutablePositionInternal(
        mint: String,
        symbol: String,
        rug: Int,
        mode: String,
        lane: String,
        source: String,
        attemptId: String,
        liveLiquidityUsd: Double = -1.0,
        liveSafetyTier: String = "",
        lastSafetyCheckMs: Long = -1L,  // V5.9.1496 — for finality reason normalization
        preResolvedSizeSol6490: Double = -1.0,
        electedLane6494: String = "",
        electedCandidateVersion6494: Long = 0L,
        electionId6494: String = "",
        authorityVersion6494: Long = 0L,
    ): OpenVerdict {
        val modeUpper = mode.uppercase()
        val requestedLaneForSynth = canonicalLane(lane)
        val existingState = states[mint]
        // V5.0.3722/V5.0.3910 — direct-lane finality restore.
        // Paper had this rescue already; live still died as
        // TOKEN_STATE_CHANGED_NO_FINAL_CANDIDATE even when the caller supplied a real
        // lane, current positive liquidity, resolved SAFE/CAUTION safety, and no
        // confirmed rug. That made paper BUY ok=109 while live BUY ok=0. Restore the
        // current live candidate at the source instead of relying on stale shared
        // EntryState. This does not bypass live safety: unknown/unsafe tier, zero liq,
        // and confirmed rug still block below.
        val syntheticPaperState: EntryState? = if (existingState == null &&
            modeUpper == "LIVE" &&
            isRealExecutionLane(requestedLaneForSynth) &&
            liveLiquidityUsd > 0.0 &&
            (liveSafetyTier.equals("SAFE", true) || liveSafetyTier.equals("CAUTION", true)) &&
            rug != 0
        ) {
            val cv = LaneExecutionCoordinator.candidateVersionFor(mint).takeIf { it > 0L } ?: 1L
            EntryState(
                mint = mint,
                symbol = symbol,
                v3Decision = if (modeUpper == "LIVE") "EXECUTE_LIVE_DIRECT" else "EXECUTE_PAPER_DIRECT",
                fdgCan = true,
                fdgReason = if (modeUpper == "LIVE") "LIVE_SYNTHETIC_FINAL_CANDIDATE" else "PAPER_SYNTHETIC_FINAL_CANDIDATE",
                safetyTier = liveSafetyTier,
                rugScore = rug,
                liquidityUsd = liveLiquidityUsd,
                signal = "BUY",
                decisionBand = "BUY",
                selectedLane = requestedLaneForSynth,
                preFdgVerdict = "BUY",
                hardNoReasons = emptyList(),
                candidateVersion = cv,
                updatedAtMs = System.currentTimeMillis(),
            ).also {
                states[mint] = it
                try {
                    val synthLabel = "LIVE_EXEC_OPEN_SYNTHETIC_FINAL_CANDIDATE"
                    ForensicLogger.lifecycle(
                        synthLabel,
                        "attemptId=$attemptId symbol=$symbol mint=${mint.take(10)} lane=$requestedLaneForSynth liq=${liveLiquidityUsd.toInt()} safety=$liveSafetyTier rug=$rug source=$source"
                    )
                    PipelineHealthCollector.labelInc(synthLabel)
                } catch (_: Throwable) {}
            }
        } else null
        val provisionalState6513 = existingState ?: syntheticPaperState
        val authorityCandidateVersion6513 = electedCandidateVersion6494.takeIf { it > 0L }
            ?: provisionalState6513?.candidateVersion
            ?: LaneExecutionCoordinator.candidateVersionFor(mint)
        val immutableAuthority6513 = com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510.currentForMint(
            mint, authorityCandidateVersion6513, modeUpper,
        )
        val state = provisionalState6513 ?: immutableAuthority6513?.let { a ->
            EntryState(mint = mint, symbol = symbol, fdgCan = true, fdgReason = a.verdict,
                safetyTier = a.safetyVerdict, signal = a.authoritativeSignal, decisionBand = a.verdict,
                selectedLane = a.executionLane, preFdgVerdict = a.verdict,
                candidateVersion = a.candidateVersion, updatedAtMs = a.generatedAtMs)
        }
        val v3Decision = state?.v3Decision ?: "UNKNOWN"
        val fdgCan = if (immutableAuthority6513 != null) true else state?.fdgCan
        val fdgReason = immutableAuthority6513?.verdict ?: state?.fdgReason ?: "n/a"
        val signal = immutableAuthority6513?.authoritativeSignal ?: state?.signal ?: "UNKNOWN"
        val band = state?.decisionBand ?: v3Decision
        val fatalReason = state?.v3FatalReason ?: fdgReason
        // V5.9.1367 — prefer LIVE context (ground truth at decision time) over a stale
        // state value. A positive live liq always wins over a state zero; a known live
        // tier always wins over a state UNKNOWN. Falls back to state, then defaults.
        val stateTier = state?.safetyTier ?: "UNKNOWN"
        val safetyTier = when {
            liveSafetyTier.isNotBlank() && !liveSafetyTier.equals("UNKNOWN", true) -> liveSafetyTier
            else -> stateTier
        }
        val stateLiq = state?.liquidityUsd ?: 0.0
        val liquidityUsd = if (liveLiquidityUsd > 0.0) liveLiquidityUsd else stateLiq
        val rawSelectedLane = state?.selectedLane ?: "UNKNOWN"
        val requestedLane = canonicalLane(lane)
        val receiptLane6494 = canonicalLane(electedLane6494)
        // V5.9.1320 (Item 6) — RESOLVE THE LANE BEFORE the FDG/EXEC finality checks.
        // The 89 EXEC_OPEN_DROPPED_SELECTED_LANE_UNKNOWN came from candidates whose state
        // carried selectedLane=UNKNOWN (state created by a non-FDG path, or recordFdg lagged
        // the EXEC request) even though a REAL specialist lane was actively requesting the
        // open. Resolution order: a real state.selectedLane wins; otherwise fall back to the
        // real REQUESTING lane (it is the lane trying to execute). Only truly UNKNOWN when
        // NEITHER is a real execution lane → then we block with CANON_LANE_UNRESOLVED.
        val selectedLane = when {
            immutableAuthority6513 != null -> canonicalLane(immutableAuthority6513.executionLane)
            isRealExecutionLane(receiptLane6494) -> receiptLane6494
            isRealExecutionLane(rawSelectedLane) -> canonicalLane(rawSelectedLane)
            isRealExecutionLane(lane) -> requestedLane
            else -> "UNKNOWN"
        }
        val canonicalSelectedLane = canonicalLane(selectedLane)
        val preFdgVerdict = immutableAuthority6513?.verdict ?: state?.preFdgVerdict ?: "WATCH"
        val hardNoReasons = state?.hardNoReasons ?: emptyList()
        val candidateVersion = immutableAuthority6513?.candidateVersion
            ?: electedCandidateVersion6494.takeIf { it > 0L } ?: state?.candidateVersion ?: 0L
        var restorePenalty = LiveRestoreExecutionPolicy.fromRuntimeDrift(liquidityUsd)
        val staleApprovedVerdict = mode.equals("LIVE", true) &&
            preFdgVerdict.uppercase() in setOf("WATCH", "PROBE", "NO_BUY") &&
            fdgCan == true && hardNoReasons.isEmpty() && liquidityUsd > 0.0
        if (staleApprovedVerdict) {
            restorePenalty = restorePenalty.combine(LiveRestoreExecutionPolicy.fromStaleWatch(liquidityUsd))
        }

        fun blocked(log: String, reason: String, shadow: Boolean = false): OpenVerdict {
            try {
                val coolMs = cooldownMsFor(log, reason)
                if (coolMs > 0L) blockedCooldowns[laneKey(mint, lane)] = reason to (System.currentTimeMillis() + coolMs)
            } catch (_: Throwable) {}
            try {
                val detail = "attemptId=$attemptId symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane preFdg=$preFdgVerdict selectedLane=$selectedLane hardNo=${hardNoReasons.joinToString(prefix="[", postfix="]")} safetyTier=$safetyTier rugScore=$rug liquidityUsd=${liquidityUsd.toInt()} candidateVersion=$candidateVersion ${if (log.contains("FDG")) "fdgReason=$reason" else if (log.contains("V3")) "fatalReason=$reason" else if (log.contains("SIGNAL")) "signal=$signal" else "reason=$reason"}"
                ForensicLogger.lifecycle(log, detail)
                ForensicLogger.phase(ForensicLogger.PHASE.EXEC_GATE, symbol, "EXEC_GATE_BLOCK $detail")
                ForensicLogger.gate(ForensicLogger.PHASE.EXEC_GATE, symbol, allow = false, reason = reason)
            } catch (_: Throwable) {}
            // No PAPER_LEARNING_PROBE_NOT_EXECUTED spam here. A blocked open is
            // already represented by its EXEC_OPEN_BLOCKED_* reason; probe spam was
            // self-DOSing the loop and hiding real executor demand.
            return OpenVerdict(false, reason, shadowOnly = shadow, logName = log, attemptId = attemptId)
        }

        fun dropped(log: String, reason: String): OpenVerdict {
            // V5.9.1476 (spec item 4) — PRE_FDG_NOT_BUY is a benign observation
            // (the candidate's last FDG verdict was WATCH/PROBE/NO_BUY, i.e. it was
            // never a real executable BUY intent). It was emitting a full forensic
            // lifecycle line EVERY loop for EVERY such candidate (590+/snapshot),
            // self-DOSing the log and inflating the EXEC funnel with non-buy noise.
            // Throttle the noisy emit to at most once per (mint,reason) per 60s while
            // STILL returning the block verdict + counters below. This does not change
            // any execution decision — purely log-volume / observability hygiene.
            val quietDrop = log == "EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY"
            val emitForensic = if (quietDrop) {
                val k = "${mint}_${log}"
                val now = System.currentTimeMillis()
                val last = preFdgDropDedupe[k] ?: 0L
                if (now - last >= 60_000L) { preFdgDropDedupe[k] = now; true } else false
            } else true
            try {
                if (emitForensic) {
                    val detail = "attemptId=$attemptId symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane preFdg=$preFdgVerdict selectedLane=$selectedLane hardNo=${hardNoReasons.joinToString(prefix="[", postfix="]")} safetyTier=$safetyTier rugScore=$rug liquidityUsd=${liquidityUsd.toInt()} candidateVersion=$candidateVersion reason=$reason"
                    ForensicLogger.lifecycle(log, detail)
                    ForensicLogger.phase(ForensicLogger.PHASE.EXEC_GATE, symbol, "EXEC_GATE_DROPPED $detail")
                }
            } catch (_: Throwable) {}
            // V5.9.1324 — P1-8 surgical: every executable-open drop emits a
            // NoTradeObservation row so dropped candidates remain trainable.
            try {
                val priceForObs = if (liquidityUsd > 0.0) 0.000001 else 0.0  // sentinel non-zero so the obs is admitted
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXEC_OPEN_DROPPED_ALL")
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXEC_OPEN_DROPPED_LANE|${(selectedLane.ifBlank { lane }).uppercase().take(24)}")
                if (priceForObs > 0.0) {
                    com.lifecyclebot.engine.learning.NoTradeObservationStore.recordBlock(
                        mint = mint,
                        symbol = symbol,
                        lane = selectedLane.ifBlank { lane },
                        scoreBand = "",
                        score = 0,
                        confidence = 0,
                        entryLiqUsd = liquidityUsd,
                        entryMcapUsd = 0.0,
                        entryPrice = priceForObs,
                        source = mode,
                        blockReason = "${log}_${reason.take(40)}",
                        verdictTag = "BLOCK_EXEC_OPEN_DROPPED",
                    )
                }
            } catch (_: Throwable) {}
            return OpenVerdict(false, reason, shadowOnly = true, logName = log, attemptId = attemptId)
        }

        val nowPre = System.currentTimeMillis()
        blockedCooldowns.entries.removeIf { it.value.second <= nowPre }
        blockedCooldowns[laneKey(mint, lane)]?.let { cd ->
            if (cd.second > nowPre) return OpenVerdict(false, "COOLDOWN_${cd.first}", shadowOnly = mode == "PAPER", logName = "EXEC_OPEN_BLOCKED_COOLDOWN", attemptId = attemptId)
        }

        if (modeUpper !in setOf("PAPER", "LIVE", "SHADOW")) {
            return blocked("EXEC_OPEN_BLOCKED_MODE_AUTHORITY", "MIXED_OR_UNKNOWN_MODE_$mode")
        }
        if (modeUpper == "LIVE" && RuntimeModeAuthority.isPaper()) {
            return blocked("EXEC_OPEN_BLOCKED_MODE_AUTHORITY", "LIVE_REQUEST_WHILE_RUNTIME_PAPER")
        }
        if (modeUpper == "PAPER" && RuntimeModeAuthority.isLive()) {
            return blocked("EXEC_OPEN_BLOCKED_MODE_AUTHORITY", "PAPER_REQUEST_WHILE_RUNTIME_LIVE")
        }

        // V5.0.6371 — OPEN-GATE SAME-MINT PAPER COOLDOWN.
        // 6370 correctly blocked cross-TokenState same-mint paper reopens inside
        // paperBuy(), but the runtime still showed 112
        // PAPER_SAME_MINT_ALREADY_OPEN_6370 blocks after candidates had already
        // burned executor/buy-path work. Check the global open registry here so
        // blocked() installs the normal per-(mint,lane) cooldown and stops churn.
        if (modeUpper == "PAPER") {
            val existingLayer6371 = try { EmergentGuardrails.getPositionLayer(mint) } catch (_: Throwable) { null }
            if (!existingLayer6371.isNullOrBlank()) {
                // V5.0.6402 §H — same-mint already open. Route through
                // SameMintCandidateEpoch6402 so the second, third … Nth
                // attempt on the same mint doesn't produce a full
                // lifecycle row on every gate. First hit gets the loud
                // block row; subsequent hits within the cooldown are
                // silently deduped into a single counter.
                val alreadyDeduped = try {
                    com.lifecyclebot.engine.truth.SameMintCandidateEpoch6402
                        .shouldSuppress(mint, sameMintAlreadyOpen = true)
                } catch (_: Throwable) { false }
                if (!alreadyDeduped) {
                    try {
                        PipelineHealthCollector.labelInc("EXEC_OPEN_SAME_MINT_ALREADY_OPEN_COOLDOWN_6371")
                        ForensicLogger.lifecycle(
                            "EXEC_OPEN_SAME_MINT_ALREADY_OPEN_COOLDOWN_6371",
                            "attemptId=$attemptId mint=${mint.take(10)} symbol=$symbol existing=$existingLayer6371 requestedLane=$lane action=blocked_before_paper_buy",
                        )
                    } catch (_: Throwable) {}
                }
                return blocked("EXEC_OPEN_BLOCKED_SAME_MINT_ALREADY_OPEN_6371", "PAPER_SAME_MINT_ALREADY_OPEN_6371 existing=$existingLayer6371")
            }
        }

        // ──────────────────────────────────────────────────────────────────
        // V5.9.1549 — SHADOW_TRAIN_ONLY is NOT an execution veto.
        // Operator hard rule: the bot has to trade to learn, and LIVE should mirror
        // PAPER volume/decision shape while respecting real-money sizing/settlement.
        // A learned toxic bucket is valuable telemetry for soft shaping, but using it
        // as an EXEC hard block created the observed 36× TREASURY shadow-train choke
        // and kept live at ~3 trades. FDG/original hard vetoes remain authoritative;
        // this layer now emits telemetry and allows the executable BUY to proceed.
        run {
            val gateScore = state?.entryScore ?: -1
            if (gateScore >= 0 && isRealExecutionLane(canonicalSelectedLane)) {
                if (BucketExecutionState.isShadowTrainOnly(canonicalSelectedLane, gateScore)) {
                    try {
                        ForensicLogger.lifecycle(
                            "EXEC_OPEN_SHADOW_TRAIN_SOFT_ALLOW",
                            "lane=$canonicalSelectedLane score=$gateScore mode=$modeUpper ${BucketExecutionState.describe(canonicalSelectedLane, gateScore)} attemptId=$attemptId"
                        )
                    } catch (_: Throwable) {}
                }
            }
        }

        // V5.0.6489 — learned lane toxicity is evidence for existing
        // LiveProbabilityEngine/LaneAdaptiveDamping score+size shaping, never
        // a global executable veto. Preserve the full canonical lane in
        // telemetry so SHITCOIN is never collapsed to an ambiguous "S".
        run {
            val toxicShape6489 = try {
                com.lifecyclebot.engine.LiveProbabilityEngine.toxicShapeReason6489(canonicalSelectedLane)
            } catch (_: Throwable) { null }
            if (toxicShape6489 != null) {
                val fullLane6489 = canonicalSelectedLane.uppercase()
                try {
                    PipelineHealthCollector.labelInc("LEARNED_TOXIC_LANE_SOFT_SHAPED_6489|lane=$fullLane6489")
                    ForensicLogger.lifecycle(
                        "LEARNED_TOXIC_LANE_SOFT_SHAPED_6489",
                        "$toxicShape6489 lane=$fullLane6489 symbol=$symbol mint=${mint.take(10)} mode=$modeUpper attemptId=$attemptId action=allow_existing_bounded_shapers",
                    )
                } catch (_: Throwable) {}
            }
        }

        // V5.9.1375 — RE-ENTRY LOCKOUT (P0 #6). After a stop-loss on this mint /
        // symbol-family, refuse to re-open for >=10 min (or until cleared by a new
        // ATH). Kills the BUY->STOP_LOSS->BUY bleed loop. Fail-open. Learning paths
        // are upstream and unaffected; a blocked re-entry still emits a NoTradeObs.
        run {
            val fam = symbol.uppercase().trim().filter { it.isLetterOrDigit() }.take(8)
            // V5.9.1466 — ADAPTIVE lockout (spec item 9): same mint that stopped out
            // keeps the full lock; a DIFFERENT mint of the same family with materially
            // stronger confirmation (entryScore as the proxy here) gets a shorter floor.
            val candidateConf = (state?.entryScore ?: 0).toDouble()
            val lockDecision = ReEntryLockout.lockDecisionAdaptive(mint, fam, candidateConf)
            if (lockDecision != null) {
                if (lockDecision.sameMint) {
                    return blocked("EXEC_OPEN_BLOCKED_REENTRY_LOCKOUT", lockDecision.reason, shadow = true)
                }
                // V5.0.6036 — family-wide stop-loss lockouts are not hard safety. They
                // were top EXEC_GATE blocks in the 6030 live report and amputated volume
                // across different mints. Keep same-mint repeat-bleed protection hard;
                // soft-allow family-only hits so FDG/brain/safety can decide the trade.
                try {
                    ForensicLogger.lifecycle(
                        "EXEC_OPEN_REENTRY_FAMILY_SOFT_ALLOW_6036",
                        "symbol=$symbol mint=${mint.take(10)} family=$fam reason=${lockDecision.reason} conf=${candidateConf.toInt()} adaptive=${lockDecision.adaptiveFamily} remaining=${lockDecision.remainingSec}s attemptId=$attemptId",
                    )
                    PipelineHealthCollector.labelInc("EXEC_OPEN_REENTRY_FAMILY_SOFT_ALLOW_6036")
                } catch (_: Throwable) {}
            }
        }



        val pause = ToxicModeCircuitBreaker.currentEntryPause()
        if (pause.active && modeUpper == "LIVE") {
            ToxicModeCircuitBreaker.emitExecutionStateBlockedIfDue(symbol, "ExecutableOpenGate")
            // V5.0.3918 — revoke the FDG-stage ticket so recentAllowedAttemptId
            // returns null when the live circuit breaker blocks the open. Matches
            // RuntimePipelineGatesTest::live_circuit_breaker_blocks_before_
            // executable_open_allowed which asserts no allowed-attempt residue
            // survives a downstream blocking gate.
            try { allowedAttempts.remove(laneKey(mint, lane)) } catch (_: Throwable) {}
            return blocked("EXEC_OPEN_BLOCKED_CIRCUIT_BREAKER", pause.reason.ifBlank { "CIRCUIT_BREAKER" })
        }
        if (pause.active && modeUpper == "PAPER") {
            try { ForensicLogger.lifecycle("PAPER_EXEC_CIRCUIT_PAUSE_BYPASSED", "symbol=$symbol lane=$lane reason=${pause.reason}") } catch (_: Throwable) {}
        }
        if (RuntimeConfigOverlay.isTradingPaused()) {
            return blocked("EXEC_OPEN_BLOCKED_RUNTIME_PAUSED", "RUNTIME_MITIGATION_PAUSE")
        }
        val currentCandidateVersion = LaneExecutionCoordinator.candidateVersionFor(mint)
        val immutableTicket = ticketForAttempt(attemptId) // output/recheck only; never prerequisite for pre-execution FDG intent
        val stateTokenMapRouteStatus = state?.tokenMapRouteStatus ?: "LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP"
        val stateTokenMapHydrationComplete = state?.tokenMapHydrationComplete == true
        val stateTokenMapExpectedOut = state?.tokenMapExpectedOut ?: 0.0
        val stateTokenMapProviderAttempts = state?.tokenMapProviderAttempts ?: 0

        if (BirdeyeBudgetGate.isEntryBudgetLockedDown()) {
            // V5.0.4167 — BIRDEYE LOCKDOWN MUST NEVER HALT TRADING.
            // Operator mandate (2026-06-26): "birdeye should not stop trading.
            // ever. we have more than enough data to cover birdeye." We have
            // DexScreener (96% sr), Helius (100% sr), Pyth, GeckoTerminal,
            // Jupiter quote, PumpPortal, and on-device caches as data
            // fallbacks for every metric Birdeye provides (price, liq, vol,
            // holders, security). Halting LIVE entry on a paid-API budget
            // event is a self-inflicted choke — soft-tag and proceed.
            // BirdeyeBudgetGate still throttles the SCANNER lane (saves CU)
            // and ProviderProofWalker still deprioritizes birdeye in
            // cascades — those remain active and are the correct way to
            // conserve budget. This gate is the ENTRY chokepoint and was
            // turning a budget event into a trading halt.
            try {
                if (modeUpper == "PAPER") {
                    ForensicLogger.lifecycle("PAPER_API_BUDGET_LOCKDOWN_BYPASSED", "symbol=$symbol lane=$lane source=$source")
                    PipelineHealthCollector.labelInc("PAPER_API_BUDGET_LOCKDOWN_BYPASSED")
                } else {
                    ForensicLogger.lifecycle(
                        "LIVE_BIRDEYE_LOCKDOWN_BYPASSED_4167",
                        "symbol=$symbol lane=$lane source=$source — fallback data (dexscreener/helius/pyth/geckoterminal) provides coverage"
                    )
                    PipelineHealthCollector.labelInc("LIVE_BIRDEYE_LOCKDOWN_BYPASSED_4167")
                }
            } catch (_: Throwable) {}
        }
        // V5.9.1230/V5.9.1568 — RC=1 is RugCheck PENDING/UNKNOWN, not a
        // confirmed rug. Upstream paper policy allows RC=1 so learning can
        // collect labelled outcomes. This must apply to ALL paper meme lanes,
        // not CYCLIC only: the live regression showed SHITCOIN/MEME fresh
        // launches stamped BLOCK_FATAL EXTREME_RUG_RISK_100 and then blocked
        // here even after FDG allowed execution. Live remains strict, and
        // confirmed RC=0 / structural fatal categories still hard-block.
        val paperRcPendingV3Fatal = modeUpper == "PAPER" && rug == 1 && (
            fatalReason.contains("EXTREME_RUG_CRITICAL_score=1", ignoreCase = true) ||
                fatalReason.contains("EXTREME_RUG_RISK", ignoreCase = true)
        )
        val paperModelRugFatal = modeUpper == "PAPER" && fatalReason.contains("EXTREME_RUG_RISK", ignoreCase = true)
        val paperLearnableV3Fatal = paperRcPendingV3Fatal || paperModelRugFatal
        if (immutableTicket == null && (v3Decision == "BLOCK_FATAL" || v3Decision == "BLOCKED" || band == "BLOCK_FATAL") && !paperLearnableV3Fatal) {
            return blocked("EXEC_OPEN_BLOCKED_FATAL_V3", fatalReason)
        }
        if (paperLearnableV3Fatal) {
            try {
                ForensicLogger.lifecycle(
                    "EXEC_OPEN_PAPER_LEARNABLE_V3_FATAL_BYPASSED",
                    "attemptId=$attemptId symbol=$symbol mint=${mint.take(10)} lane=$lane fatalReason=$fatalReason rugScore=$rug"
                )
            } catch (_: Throwable) {}
        }

        if (immutableTicket != null && modeUpper == "LIVE") {
            val ticketExpired = !ticketLive(immutableTicket)
            val ticketHardNo = immutableTicket.hardNoReasons.firstOrNull { trueHardTicketKill(it) }
            if (ticketExpired) return blocked("EXEC_OPEN_BLOCKED_STALE_TICKET", "TICKET_TTL_EXPIRED")
            if (ticketHardNo != null) return blocked("EXEC_OPEN_BLOCKED_TICKET_HARD_SAFETY", ticketHardNo)
            val ticketRouteUpper = stateTokenMapRouteStatus.uppercase()
            val ticketNoRoute = ticketRouteUpper in setOf("NO_ROUTE", "TRUE_ZERO_LIQUIDITY")
            val ticketExecutableRoute = ticketRouteUpper in setOf("PUMPFUN_BONDING_CURVE_EXECUTABLE", "DEX_ROUTABLE")
            if (liquidityUsd <= 0.0 && immutableTicket.liquidityUsd <= 0.0 && stateTokenMapHydrationComplete && ticketNoRoute && stateTokenMapProviderAttempts >= 2) return blocked("EXEC_OPEN_BLOCKED_TRUE_ZERO_LIQUIDITY", "TRUE_ZERO_LIQUIDITY")
            if (liquidityUsd <= 0.0 && immutableTicket.liquidityUsd <= 0.0 && !ticketExecutableRoute) return blocked("EXEC_OPEN_DEFERRED_TOKEN_MAP", "LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP", shadow = true)
            try {
                ForensicLogger.lifecycle(
                    "EXEC_TICKET_RESTORED_IMMUTABLE",
                    "attemptId=$attemptId mint=${mint.take(10)} symbol=$symbol lane=$lane ticketLane=${immutableTicket.lane} ticketVersion=${immutableTicket.candidateVersion} currentVersion=$currentCandidateVersion preFdg=$preFdgVerdict"
                )
                PipelineHealthCollector.labelInc("EXEC_TICKET_RESTORED_IMMUTABLE")
            } catch (_: Throwable) {}
        } else candidateInvalidReason(
            state = state,
            selectedLane = selectedLane,
            requestedLane = lane,
            preFdgVerdict = preFdgVerdict,
            hardNoReasons = hardNoReasons,
            candidateVersion = candidateVersion,
            currentVersion = currentCandidateVersion,
            mode = mode,                          // V5.9.1496
            lastSafetyCheckMs = lastSafetyCheckMs, // V5.9.1496
            mint = mint,                          // V5.9.1496
            symbol = symbol,                      // V5.9.1496
            currentLiquidityUsd = liquidityUsd,    // V5.9.1559 live stale-WATCH restore
            currentSafetyTier = safetyTier,        // V5.9.1559 live stale-WATCH restore
        )?.let { (log, reason) ->
            if (log.contains("STALE_CANDIDATE")) {
                try { LaneExecutionCoordinator.releaseIfPrimary(mint, laneForRelease(selectedLane, lane), "CANDIDATE_STALE_DROPPED", candidateVersion = candidateVersion) } catch (_: Throwable) {}
                try { ForensicLogger.lifecycle("CANDIDATE_STALE_DROPPED", "mint=${mint.take(10)} symbol=$symbol lane=$lane selectedLane=$selectedLane candidateVersion=$candidateVersion currentVersion=$currentCandidateVersion") } catch (_: Throwable) {}
            }
            return dropped(log, reason)
        }
        if (immutableTicket == null && immutableAuthority6513 == null && !selectedLaneMatchesRequest(selectedLane, lane)) {
            // V5.9.1499 — LANE-CONTENTION DEDUP (not lost volume). When two REAL
            // specialist lanes both qualify the same mint, LaneExecutionCoordinator
            // elects ONE primary (priority + recent-WR based, with upgrade-steal).
            // The non-primary lane's executor reaching here is CORRECT dedup — it
            // must NOT double-open the same mint under a second lane's sizing/exits.
            // Previously this logged as EXEC_OPEN_DROPPED_SELECTED_LANE_MISMATCH,
            // which read as silently-lost throughput in the funnel. Two changes:
            //   1) If the requesting lane happens to hold the primary (selected was a
            //      stale UNKNOWN/bucket), prefer the requester instead of dropping —
            //      this rescues genuine entries where state.selectedLane lagged.
            //   2) Otherwise emit it under a DEDUP label so it stops masquerading as
            //      lost volume; the primary lane still opens on its own pass.
            // Rescue case: state.selectedLane was never a real lane (stale
            // UNKNOWN/bucket) while the requester IS a real specialist — it is the
            // lane actually trying to open and nothing else holds authority, so let
            // it proceed. Otherwise this is genuine lane contention → dedup.
            val primaryHasAllowedHandoff = try { recentAllowedAttemptId(mint, canonicalSelectedLane) != null } catch (_: Throwable) { false }
            val rescueRequester = isRealExecutionLane(requestedLane) && (
                !isRealExecutionLane(rawSelectedLane) ||
                    // V5.9.1559 — live unchoke: if lane election picked a primary
                    // but that primary never produced an executable handoff, blocking
                    // the requesting lane is lost volume, not dedup. Operator log:
                    // PRIMARY_MANIPULATED_LOST_SHITCOIN while the SHITCOIN/EXPRESS
                    // lane had can=true and the primary did not open.
                    (modeUpper == "LIVE" && !primaryHasAllowedHandoff)
            )
            if (!rescueRequester) {
                return dropped("EXEC_OPEN_DEDUP_LANE_CONTENTION", "PRIMARY_${canonicalSelectedLane}_LOST_${requestedLane}")
            }
            if (modeUpper == "LIVE" && isRealExecutionLane(requestedLane) && !primaryHasAllowedHandoff) {
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_LANE_CONTENTION_RESCUED_REQUESTER",
                        "mint=${mint.take(10)} symbol=$symbol selected=$canonicalSelectedLane requested=$requestedLane reason=primary_no_allowed_handoff"
                    )
                } catch (_: Throwable) {}
            }
        }
        if (safetyTier.equals("UNKNOWN", true) && immutableTicket == null) {
            return blocked("EXEC_OPEN_BLOCKED_SAFETY_CONTEXT_MISSING", "PRE_FDG_SAFETY_CONTEXT_MISSING", shadow = mode == "PAPER")
        }
        // V5.9.1504 — RUG-CONTEXT STRICT FALLBACK (master throughput unblock).
        // Operator forensics 14:40: EVERY live candidate (incl. a 95%-pnl KNOWN
        // WINNER that FDG itself flagged "FAST APPROVE") was hard-blocked here
        // with missing rug context → 0/35 open, nothing could buy live.
        // Cause: rugcheck.xyz returns -1 (PENDING/no-report-yet) for essentially
        // every FRESH meme mint, and this gate treated rug<0 as an unconditional
        // LIVE hard-no — STRICTER than FDG, which already approves pending-rug
        // tokens via a STRICT safety fallback (liq+buy+vol floor). We now mirror
        // FDG: a CONFIRMED rug (rug==0) is always blocked; a PENDING rug (rug==-1)
        // is allowed THROUGH TO FDG only when it clears a hard safety floor, so
        // FDG makes the final call instead of the candidate dying pre-FDG.
        // Known-ruggers are still caught by the TokenBlacklist veto at liveBuy
        // (V5.9.1502) and -15% SL is unchanged.
        if (rug == 0 && modeUpper == "LIVE") {
            return blocked("EXEC_OPEN_BLOCKED_CONFIRMED_RUG", "PRE_FDG_CONFIRMED_RUG_SCORE_0", shadow = false)
        }
        if (rug < 0 && modeUpper == "LIVE") {
            try {
                ForensicLogger.lifecycle(
                    "LIVE_PENDING_RUG_CONTEXT_SOFT_ALLOWED",
                    "attemptId=$attemptId symbol=$symbol mint=${mint.take(10)} lane=$lane liq=${liquidityUsd.toInt()} safety=$safetyTier fdgCan=$fdgCan ticket=${immutableTicket != null}"
                )
                PipelineHealthCollector.labelInc("LIVE_PENDING_RUG_CONTEXT_SOFT_ALLOWED")
            } catch (_: Throwable) {}
        }
        if (liquidityUsd <= 0.0) {
            val routeUpper = stateTokenMapRouteStatus.uppercase()
            val executableTokenMap = routeUpper == "PUMPFUN_BONDING_CURVE_EXECUTABLE" || routeUpper == "DEX_ROUTABLE"
            val routeNoRoute = routeUpper in setOf("NO_ROUTE", "TRUE_ZERO_LIQUIDITY")
            val trueZeroTokenMap = stateTokenMapHydrationComplete && routeNoRoute && stateTokenMapExpectedOut <= 0.0 && stateTokenMapProviderAttempts >= 2
            if (!trueZeroTokenMap && !executableTokenMap) {
                try {
                    ForensicLogger.lifecycle(
                        "TOKEN_MAP_PENDING",
                        "attemptId=$attemptId mint=${mint.take(10)} symbol=$symbol stage=ExecutableOpenGate route=$stateTokenMapRouteStatus providers=$stateTokenMapProviderAttempts expectedOut=$stateTokenMapExpectedOut action=defer_not_zero_liquidity",
                    )
                    PipelineHealthCollector.labelInc("TOKEN_MAP_PENDING")
                } catch (_: Throwable) {}
                return blocked("EXEC_OPEN_DEFERRED_TOKEN_MAP", "LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP", shadow = true)
            }
            if (trueZeroTokenMap) {
                return blocked("EXEC_OPEN_BLOCKED_TRUE_ZERO_LIQUIDITY", "TRUE_ZERO_LIQUIDITY", shadow = false)
            }
        }
        if (fdgCan == true && hardNoReasons.isEmpty() && immutableAuthority6513 == null) {
            try {
                PipelineHealthCollector.labelInc("AUTHORITY_INVARIANT_FAILURE")
                PipelineHealthCollector.labelInc("EXEC_AUTHORITY_STATE_MISMATCH")
                ForensicLogger.lifecycle("AUTHORITY_INVARIANT_FAILURE", "attemptId=$attemptId mint=${mint.take(10)} candidateVersion=$candidateVersion currentVersion=$currentCandidateVersion requestedLane=$requestedLane selectedLane=$canonicalSelectedLane preFdg=$preFdgVerdict reason=FDG_ALLOW_WITHOUT_CURRENT_IMMUTABLE_AUTHORITY")
            } catch (_: Throwable) {}
            return blocked("AUTHORITY_INVARIANT_FAILURE", "FDG_ALLOW_WITHOUT_CURRENT_IMMUTABLE_AUTHORITY", shadow = mode == "PAPER")
        }
        if (immutableAuthority6513 != null && (immutableAuthority6513.candidateVersion != currentCandidateVersion ||
                immutableAuthority6513.executionLane != canonicalSelectedLane || immutableAuthority6513.authoritativeSignal != "BUY" ||
                immutableAuthority6513.verdict !in setOf("BUY", "PROBE_ONLY"))) {
            try {
                PipelineHealthCollector.labelInc("AUTHORITY_INVARIANT_FAILURE")
                PipelineHealthCollector.labelInc("EXEC_AUTHORITY_STATE_MISMATCH")
                ForensicLogger.lifecycle("AUTHORITY_INVARIANT_FAILURE", "attemptId=$attemptId mint=${mint.take(10)} ticketLane=$canonicalSelectedLane authorityLane=${immutableAuthority6513.executionLane} authorityVersion=${immutableAuthority6513.authorityVersion} candidateVersion=${immutableAuthority6513.candidateVersion} currentVersion=$currentCandidateVersion verdict=${immutableAuthority6513.verdict} signal=${immutableAuthority6513.authoritativeSignal}")
            } catch (_: Throwable) {}
            return blocked("AUTHORITY_INVARIANT_FAILURE", "IMMUTABLE_AUTHORITY_NOT_CURRENT", shadow = mode == "PAPER")
        }
        val canonicalExecutableIntent6509 = canonicalExecutableIntent6509(
            fdgCan = fdgCan,
            preFdgVerdict = preFdgVerdict,
            hardNoReasons = hardNoReasons,
        )
        if (!signal.equals("BUY", true) && !signal.equals("EXECUTE", true)) {
            try { com.lifecyclebot.engine.truth.CanonicalFdgBuyStamp6508.reportMismatch(mint, signal, candidateVersion) } catch (_: Throwable) {}
            if (canonicalExecutableIntent6509) {
                try {
                    PipelineHealthCollector.labelInc("EXEC_RAW_SIGNAL_DIAGNOSTIC_IGNORED_6509")
                    ForensicLogger.lifecycle("EXEC_RAW_SIGNAL_DIAGNOSTIC_IGNORED_6509", "symbol=$symbol mint=${mint.take(10)} signal=${signal.ifBlank { "UNKNOWN" }} preFdg=$preFdgVerdict authority=PRE_EXEC_FDG")
                } catch (_: Throwable) {}
            } else {
                return blocked("EXEC_OPEN_BLOCKED_SIGNAL_NOT_BUY", "SIGNAL_NOT_BUY:${signal.ifBlank { "UNKNOWN" }}", shadow = mode == "PAPER")
            }
        }
        if (fdgCan != true) {
            return blocked("EXEC_OPEN_BLOCKED_FDG_FINAL", fdgReason, shadow = mode == "PAPER")
        }
        // V5.0.3915 — only confirmed rug is a final-open hard block. Low/nonzero
        // RC scores are risk penalties, not mechanical impossibility.
        if (rug == 0) {
            return blocked("EXEC_OPEN_BLOCKED_RUG_SCORE", "RC_SCORE_0", shadow = mode == "PAPER")
        }
        if (fdgCan == false) {
            return blocked("EXEC_OPEN_BLOCKED_FDG_FINAL", fdgReason, shadow = mode == "PAPER")
        }
        if ((v3Decision == "WATCH" || band == "WATCH" || v3Decision == "DECISION_WATCH") && fdgCan != true) {
            // V5.9.1097 — WATCH is a soft V3 timing opinion, not a hard finality veto.
            // Pre-1093 doctrine allowed FDG-approved lane probes through WATCH to preserve
            // throughput/learning. 1093 accidentally made WATCH block every executable open,
            // causing TREASURY/BLUECHIP/etc to report EXEC_OPEN_BLOCKED_SIGNAL_WAIT forever.
            return blocked("EXEC_OPEN_BLOCKED_SIGNAL_WAIT", "DECISION_WATCH", shadow = mode == "PAPER")
        }
        if ((signal.equals("WAIT", ignoreCase = true) || fdgReason.contains("WAIT", ignoreCase = true)) && fdgCan != true) {
            return blocked("EXEC_OPEN_BLOCKED_SIGNAL_WAIT", signal.ifBlank { fdgReason }, shadow = mode == "PAPER")
        }
        val effectiveEntryDecision6487 = entryAuthority6487[authorityKey6487(mint, candidateVersion)] ?: try {
            com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450.gate(lane, mint, 1.0).also {
                entryAuthority6487[authorityKey6487(mint, candidateVersion)] = it
            }
        } catch (_: Throwable) {
            com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450.Decision(
                com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450.Verdict.DENY_LOSING_STREAK, 0.0, "gate_error_fail_closed_6487",
            )
        }
        if (effectiveEntryDecision6487.verdict !in setOf(
                com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450.Verdict.ALLOW,
                com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450.Verdict.ALLOW_PROBE,
            )) {
            try { PipelineHealthCollector.labelInc("EXEC_GATE_BLOCKED_ENTRY_AUTHORITY_6487") } catch (_: Throwable) {}
            return blocked("EXEC_OPEN_BLOCKED_ENTRY_AUTHORITY_6487", effectiveEntryDecision6487.reason, shadow = true)
        }
        if (isShadowReadOnlyLane6487(lane) && immutableAuthority6513 == null) {
            return blocked("EXEC_OPEN_BLOCKED_SHADOW_LANE_6487", "${lane.uppercase()}_READ_ONLY", shadow = true)
        }
        val execKey = attemptId.ifBlank { canonicalExecutionKey(mint, mode = mode, side = "BUY", lane = lane) }
        // V5.0.6491 — FINALITY PRECHECK IS NOT EXECUTION AUTHORIZATION.
        // TradeAuthorizer can inspect safety before canonical size exists, but it
        // may not claim occupancy, publish a ticket, or emit EXEC_OPEN_ALLOWED.
        // FinalExecutionPermit supplies resolved size and is the first authority
        // allowed to cross this boundary.
        val minExecutable6491 = if (modeUpper == "PAPER")
            com.lifecyclebot.engine.truth.OrderSizeResolver6441.paperExecutableMinimumSol() else 0.001
        // V5.0.6497 §1 — SEALED ORDER SIZE AUTHORITY. If the canonical
        // OrderSizeResolver has sealed a larger executable size for
        // this mint, use it. This prevents a stale/duplicated caller
        // from passing 0.01 SOL while the canonical resolver produced
        // 2.00 SOL — the exact mismatch operator observed in 6496.
        val authoritativeSize6497 = try {
            com.lifecyclebot.engine.truth.SealedOrderSizeAuthority6497
                .authoritativeSize(mint, preResolvedSizeSol6490.coerceAtLeast(0.0))
        } catch (_: Throwable) { preResolvedSizeSol6490.coerceAtLeast(0.0) }
        val effectiveResolvedSize6497 = if (preResolvedSizeSol6490 < 0.0) preResolvedSizeSol6490
            else authoritativeSize6497
        if (effectiveResolvedSize6497 < 0.0) {
            try {
                PipelineHealthCollector.labelInc("EXEC_OPEN_PRECHECK_SIZE_PENDING_6491")
                ForensicLogger.lifecycle("EXEC_OPEN_PRECHECK_SIZE_PENDING_6491", "attemptId=$execKey mint=${mint.take(10)} symbol=$symbol mode=$modeUpper lane=$lane action=safety_precheck_only_no_claim_no_ticket_no_allow")
            } catch (_: Throwable) {}
            return OpenVerdict(true, "SIZE_PENDING_PRECHECK_ONLY_6491", shadowOnly = true,
                logName = "EXEC_OPEN_PRECHECK_SIZE_PENDING_6491", attemptId = execKey)
        }
        if (!com.lifecyclebot.engine.truth.OrderSizeResolver6441.meetsMinimum6491(effectiveResolvedSize6497, minExecutable6491)) {
            return blocked("EXEC_OPEN_BLOCKED_SIZE_NOT_EXECUTABLE_6491",
                "resolvedSize=$effectiveResolvedSize6497 minimum=$minExecutable6491 sealedSize=${try { com.lifecyclebot.engine.truth.SealedOrderSizeAuthority6497.sealedSize(mint) } catch (_: Throwable) { null }}", shadow = true)
        }
        val claimKey6487 = executableClaimKey6487(modeUpper, mint, candidateVersion)
        val priorClaim6487 = executableBuyClaim6487.putIfAbsent(claimKey6487, execKey)
        if (priorClaim6487 != null && priorClaim6487 != execKey) {
            try {
                PipelineHealthCollector.labelInc("EXEC_BUY_MINT_VERSION_DUPLICATE_SUPPRESSED_6487")
                ForensicLogger.lifecycle("EXEC_BUY_MINT_VERSION_DUPLICATE_SUPPRESSED_6487", "mint=${mint.take(10)} symbol=$symbol lane=$lane version=$candidateVersion winner=$priorClaim6487 loser=$execKey")
            } catch (_: Throwable) {}
            return blocked("EXEC_OPEN_DEDUP_MINT_VERSION_6487", "ONE_EXECUTABLE_BUY_PER_MINT_VERSION", shadow = true)
        }
        val now = System.currentTimeMillis()
        openRequests.entries.removeIf { now - it.value > ALLOWED_ATTEMPT_TTL_MS }
        val laneAttemptKey = laneKey(mint, lane)
        val prior = openRequests.putIfAbsent(execKey, now)
        if (prior != null && now - prior <= ALLOWED_ATTEMPT_TTL_MS) {
            // V5.9.1182 — same approved attempt is idempotent, not a hard block.
            // The execution chain can legally touch finality multiple times:
            // TradeAuthorizer/FinalExecutionPermit/Executor wrappers all verify the
            // same attemptId before side effects. Counting that as DUPLICATE_EXECUTION_KEY
            // inflates block telemetry and can starve the handoff even though no second
            // candidate/book is being opened. Different/stale attempts still block below.
            val allowed = allowedAttempts[laneAttemptKey] ?: allowedAttempts[mint.trim()]
            if (allowed?.first == execKey && now - allowed.second <= ALLOWED_ATTEMPT_TTL_MS) {
                try {
                    val detail = "attemptId=$execKey symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane source=$source ageMs=${now - prior} candidateVersion=$candidateVersion"
                    ForensicLogger.lifecycle("EXEC_OPEN_IDEMPOTENT_RECHECK", detail)
                    ForensicLogger.phase(ForensicLogger.PHASE.EXEC_GATE, symbol, "EXEC_GATE_ALLOW_RECHECK $detail")
                } catch (_: Throwable) {}
                return restorePenalties[execKey] ?: OpenVerdict(true, "finality_clear_recheck", attemptId = execKey)
            }
            try { TradeOutcomeLedger.recordSuppressedDuplicateOpen() } catch (_: Throwable) {}
            try {
                val detail = "attemptId=$execKey symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane selectedLane=$selectedLane source=$source ageMs=${now - prior} candidateVersion=$candidateVersion"
                ForensicLogger.lifecycle("EXEC_OPEN_DUPLICATE_SUPPRESSED", detail)
                ForensicLogger.phase(ForensicLogger.PHASE.EXEC_GATE, symbol, "EXEC_GATE_DUPLICATE_SUPPRESSED $detail")
            } catch (_: Throwable) {}
            return OpenVerdict(false, "DUPLICATE_EXECUTION_KEY_SUPPRESSED", shadowOnly = true, logName = "EXEC_OPEN_DUPLICATE_SUPPRESSED", attemptId = execKey)
        }
        try {
            allowedAttempts[laneAttemptKey] = execKey to System.currentTimeMillis()
            allowedAttempts[mint.trim()] = execKey to System.currentTimeMillis()
            if (executionTickets[execKey] == null) {
                publishTicket(
                    ExecutionTicket(
                        attemptId = execKey,
                        mint = mint,
                        symbol = symbol,
                        lane = canonicalSelectedLane,
                        mode = modeUpper,
                        candidateVersion = candidateVersion,
                        electionId6494 = electionId6494,
                        authorityVersion6494 = immutableAuthority6513?.authorityVersion ?: authorityVersion6494,
                        fdgReason = fdgReason,
                        signal = immutableAuthority6513?.authoritativeSignal ?: signal,
                        safetyTier = immutableAuthority6513?.safetyVerdict?.takeIf { it.isNotBlank() && it != "UNKNOWN" } ?: safetyTier,
                        primaryLane = immutableAuthority6513?.executionLane ?: canonicalSelectedLane,
                        fdgVerdict = if (immutableAuthority6513?.verdict in setOf("BUY", "PROBE_ONLY")) "BUY" else preFdgVerdict,
                        authoritativeSignal = immutableAuthority6513?.authoritativeSignal ?: "BUY",
                        safetyVerdict = immutableAuthority6513?.safetyVerdict?.takeIf { it.isNotBlank() } ?: safetyTier,
                        authorityVersion = immutableAuthority6513?.authorityVersion ?: authorityVersion6494,
                        liquidityUsd = liquidityUsd,
                        rugScore = rug,
                        hardNoReasons = hardNoReasons,
                        resolvedSizeSol = effectiveResolvedSize6497.coerceAtLeast(0.0),
                    )
                )
            }
        } catch (_: Throwable) {}
        val allowedVerdict = OpenVerdict(
            true,
            if (restorePenalty.reason == "NONE") "finality_clear" else "LIVE_RESTORE_PENALTY_EXEC:${restorePenalty.reason}",
            attemptId = execKey,
            scorePenalty = restorePenalty.scorePenalty,
            sizeMultiplier = restorePenalty.sizeMultiplier,
            restoreReason = restorePenalty.reason,
            liquidityOverrideUsd = restorePenalty.liquidityOverrideUsd,
        )
        if (restorePenalty.reason != "NONE") {
            try { restorePenalties[execKey] = allowedVerdict } catch (_: Throwable) {}
        }
        try {
            // V5.0.6506 §P0-1 — EXEC_NON_BUY_INTENT_INVARIANT counter.
            // Executable-open construction may ONLY proceed with canonical
            // BUY intent. If the caller reached EXEC_GATE_ALLOW while the
            // upstream signal is anything other than BUY (or a legacy
            // BUY-equivalent), we bump the invariant-fail counter so it
            // stays visible in the dump. This does NOT change the current
            // flow — the ExecutableOpenGate remains the last safety
            // validation, not a garbage collector. The counter surfaces
            // any producer that leaked non-BUY intent into the gate.
            val canonicalBuy6506 = signal.trim().uppercase() in setOf("BUY", "EXECUTE", "PROBE_ONLY", "PROBE")
            if (!canonicalBuy6506) {
                PipelineHealthCollector.labelInc("EXEC_NON_BUY_INTENT_INVARIANT_FAIL_6506")
                ForensicLogger.lifecycle(
                    "EXEC_NON_BUY_INTENT_INVARIANT_FAIL_6506",
                    "attemptId=$execKey mint=${mint.take(10)} symbol=$symbol lane=$lane " +
                        "signal=${signal.ifBlank { "UNKNOWN" }} preFdg=$preFdgVerdict source=$source " +
                        "action=continue_with_diagnostic_only",
                )
            }
            val detail = "attemptId=$execKey symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane source=$source preFdg=$preFdgVerdict selectedLane=$selectedLane hardNo=[] candidateVersion=$candidateVersion v3Decision=$v3Decision fdgCan=${fdgCan ?: "unknown"} fdgReason=$fdgReason safetyTier=$safetyTier rugScore=$rug liquidityUsd=${liquidityUsd.toInt()} signal=$signal band=$band restorePenalty=${restorePenalty.reason} sizeMult=${restorePenalty.sizeMultiplier}"
            ForensicLogger.lifecycle("EXEC_OPEN_REQUEST", detail)
            ForensicLogger.lifecycle("EXEC_GATE_ALLOW", detail)
            ForensicLogger.phase(ForensicLogger.PHASE.EXEC_GATE, symbol, "EXEC_GATE_ALLOW $detail")
            ForensicLogger.gate(ForensicLogger.PHASE.EXEC_GATE, symbol, allow = true, reason = "finality_clear")
            ForensicLogger.lifecycle("EXEC_OPEN_ALLOWED", "attemptId=$execKey symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane reason=finality_clear candidateVersion=$candidateVersion")
            // V5.0.6508 §P0-1 — CANONICAL FDG BUY STAMP.
            // Successful BUY reached EXEC_GATE_ALLOW. Consume any prior
            // stamp (so a re-entry starts fresh) and stamp the CURRENT
            // decision so any concurrent snapshot restoration that
            // fires an UNKNOWN signal at this mint bumps the
            // EXEC_SIGNAL_AUTHORITY_MISMATCH_6508 counter.
            try {
                com.lifecyclebot.engine.truth.CanonicalFdgBuyStamp6508.consume(mint)
                com.lifecyclebot.engine.truth.CanonicalFdgBuyStamp6508.stamp(
                    mint = mint,
                    symbol = symbol,
                    canonicalLane = lane,
                    decisionId = execKey,
                    candidateVersion = candidateVersion,
                )
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
        return allowedVerdict
    }
}
