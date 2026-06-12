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
        val entryScore: Int = -1,  // V5.9.1373 — for SHADOW_TRAIN_ONLY bucket lookup
        val candidateVersion: Long = 0L,
        val updatedAtMs: Long = System.currentTimeMillis(),
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
    fun nextAttemptId(mint: String, lane: String): String = canonicalExecutionKey(mint, lane = lane)
    fun canonicalExecutionKey(
        mint: String,
        mode: String = if (FinalExecutionPermit.isPaperMode) "PAPER" else "LIVE",
        side: String = "BUY",
        lane: String = "PRIMARY",
        runtimeGeneration: Long = BotRuntimeController.currentGeneration(),
        candidateVersion: Long = LaneExecutionCoordinator.candidateVersionFor(mint),
    ): String = "$runtimeGeneration:${mode.uppercase()}:${sanitizeMintForKey(mint)}:${side.uppercase()}:$candidateVersion"

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

    private val states = ConcurrentHashMap<String, EntryState>()
    private const val TTL_MS = 10 * 60 * 1000L
    private val allowedAttempts = ConcurrentHashMap<String, Pair<String, Long>>()
    private val openRequests = ConcurrentHashMap<String, Long>()
    private val blockedCooldowns = ConcurrentHashMap<String, Pair<String, Long>>()
    private val restorePenalties = ConcurrentHashMap<String, OpenVerdict>()

    fun restorePenaltyForAttempt(attemptId: String): OpenVerdict? = restorePenalties[attemptId]
    fun consumeRestorePenalty(attemptId: String): OpenVerdict? = restorePenalties.remove(attemptId)

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
        if (state == null) return "EXEC_OPEN_DROPPED_NO_FINAL_CANDIDATE" to "NO_FINAL_BUY_CANDIDATE"
        val selected = canonicalLane(selectedLane)
        val requested = canonicalLane(requestedLane)
        // V5.9.1559 — live unchoke: stale context hardNos must not survive
        // after the current candidate proves the context is valid. This strips
        // only derived context labels; real rug/fatal hardNo reasons remain.
        val effectiveHardNoReasons = hardNoReasons.filterNot { hn ->
            (hn.equals("ZERO_LIQUIDITY", true) && currentLiquidityUsd > 0.0) ||
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
            val latestAllows = state?.fdgCan == true || state?.preFdgVerdict.equals("BUY", true) || state?.preFdgVerdict.equals("PROBE_ONLY", true)
            val safetyOk = currentSafetyTier.equals("SAFE", true) || currentSafetyTier.equals("CAUTION", true) ||
                state?.safetyTier.equals("SAFE", true) || state?.safetyTier.equals("CAUTION", true) || mode.equals("LIVE", true)
            // V5.9.1559 — LIVE finality restore must use the CURRENT candidate
            // liquidity, not the stale EntryState liquidity. Operator log showed
            // current liq=$1599 but cached finality liq=0 → preFdg WATCH dropped
            // a lane-approved live candidate.
            val effectiveLiq = maxOf(currentLiquidityUsd, state?.liquidityUsd ?: 0.0)
            val liqOk = effectiveLiq >= 1200.0
            if (mode.equals("LIVE", true) && latestAllows && safetyOk && liqOk && effectiveHardNoReasons.isEmpty()) {
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_RESTORE_STALE_WATCH_SOFT_ALLOW",
                        "mint=${mint.take(10)} symbol=$symbol preFdg=$preFdgVerdict fdgCan=${state?.fdgCan} stateLiq=${(state?.liquidityUsd ?: 0.0).toInt()} currentLiq=${currentLiquidityUsd.toInt()} penalty=WATCH_FINALITY_SOFT_ALLOW"
                    )
                } catch (_: Throwable) {}
                return null
            }
            return "EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY" to preFdgVerdict
        }
        if (effectiveHardNoReasons.isNotEmpty()) return "EXEC_OPEN_DROPPED_HARD_NO_BUY" to effectiveHardNoReasons.joinToString("+")
        if (candidateVersion != currentVersion) return "EXEC_OPEN_DROPPED_STALE_CANDIDATE" to "STALE_CANDIDATE_VERSION_$candidateVersion"
        return null
    }

    private fun laneKey(mint: String, lane: String): String = mint + ":" + canonicalLane(lane).filter { it.isLetterOrDigit() }
    private const val ALLOWED_ATTEMPT_TTL_MS = 60_000L

    fun resetForTests() {
        states.clear()
        allowedAttempts.clear()
        openRequests.clear()
        blockedCooldowns.clear()
    }

    private fun cooldownMsFor(log: String, reason: String): Long {
        val r = reason.uppercase()
        return when {
            log.contains("RUNTIME") || r.contains("CIRCUIT") || r.contains("LOCKDOWN") -> 120_000L
            log.contains("FATAL_V3") || r.contains("EXTREME_RUG") || r.contains("ZERO_LIQUIDITY") -> 10 * 60_000L
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
    ) {
        val paperRuntime = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }
        val finalHardNo = hardNoReasons.toMutableList().apply {
            if (liquidityUsd <= 0.0) add("ZERO_LIQUIDITY")
            if (safetyTier.equals("UNKNOWN", true)) add("PRE_FDG_SAFETY_CONTEXT_MISSING")
            // V5.9.1216 — PAPER treats missing RC context as learnable unknown,
            // same philosophy as RC_PENDING / low-RC sampling. LIVE keeps strict
            // pre-FDG rug context finality.
            if (rugScore < 0 && !paperRuntime) add("PRE_FDG_RUG_CONTEXT_MISSING")
            // V5.9.1214 — in PAPER only confirmed rug score 0 is fatal.
            // Scores 1..10 are learnable low-RC samples with soft penalties
            // upstream; LIVE still treats 1..10 as hard no-buy finality.
            if (rugScore == 0 || (rugScore in 1..10 && !paperRuntime)) add("RC_SCORE_$rugScore")
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
            val keepOld = sameVersion && rank(old?.preFdgVerdict) > rank(finalVerdict)
            val effectiveVerdict = if (keepOld) old!!.preFdgVerdict else finalVerdict
            val effectiveCan = if (keepOld) old!!.fdgCan else canExecute
            (old ?: EntryState(mint = mint, symbol = symbol)).copy(
                symbol = symbol,
                fdgCan = effectiveCan,
                fdgReason = if (keepOld) old?.fdgReason else reason,
                signal = signal.ifBlank { "UNKNOWN" },
                decisionBand = if (effectiveVerdict == "BUY") "BUY" else (old?.decisionBand ?: effectiveVerdict),
                selectedLane = lane.uppercase(),
                preFdgVerdict = effectiveVerdict,
                hardNoReasons = if (keepOld) (old?.hardNoReasons ?: finalHardNo) else finalHardNo,
                candidateVersion = candidateVersion,
                entryScore = if (entryScore >= 0) entryScore else old?.entryScore ?: -1,
                liquidityUsd = if (liquidityUsd > 0.0) liquidityUsd else old?.liquidityUsd ?: 0.0,
                rugScore = if (rugScore >= 0) rugScore else old?.rugScore ?: -1,
                safetyTier = if (safetyTier.isNotBlank() && !safetyTier.equals("UNKNOWN", true)) safetyTier else old?.safetyTier ?: "UNKNOWN",
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        try {
            val hard = finalHardNo.joinToString(prefix = "[", postfix = "]")
            val msg = "symbol=$symbol lane=${lane.uppercase()} preFdg=$finalVerdict hardNo=$hard safety=$safetyTier rug=$rugScore liq=${liquidityUsd.toInt()} duplicate=false circuit=${ToxicModeCircuitBreaker.currentEntryPause().active} sellPressure=${reason ?: "OK"} version=$candidateVersion"
            // V5.9.1320 (Item 6) — emit a canonical FDG DECISION so the health snapshot's
            // "verdicts produced" counter increments whenever FDG produces an allow/block
            // verdict. ForensicLogger.decision() had ZERO callers, which is why the funnel
            // always showed verdicts produced=0 despite FDG running. phase() bumps phaseCounts;
            // decision() bumps verdictCounts — both are needed.
            val executableFdg = canExecute && finalHardNo.isEmpty() && (finalVerdict == "BUY" || finalVerdict == "PROBE_ONLY")
            val verdictLabel = if (executableFdg) finalVerdict else "BLOCK"
            try { ForensicLogger.decision(ForensicLogger.PHASE.FDG, symbol, verdictLabel, 0, 0, reason ?: finalHardNo.firstOrNull() ?: verdictLabel) } catch (_: Throwable) {}
            if (executableFdg) {
                ErrorLogger.info("FDG", "FDG_ALLOW $symbol lane=${lane.uppercase()} preFdg=$finalVerdict hardNo=[] safety=$safetyTier rug=$rugScore liq=${liquidityUsd.toInt()} duplicate=false circuit=${ToxicModeCircuitBreaker.currentEntryPause().active} sellPressure=${reason ?: "OK"} version=$candidateVersion")
                ForensicLogger.phase(ForensicLogger.PHASE.FDG, symbol, "FDG_ALLOW $msg")
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
    ): OpenVerdict {
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
        )
    }

    fun canOpenExecutablePosition(
        ts: TokenState,
        mode: String,
        lane: String,
        source: String,
        attemptId: String = nextAttemptId(ts.mint, lane),
    ): OpenVerdict {
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
    ): OpenVerdict {
        val state = states[mint]
        val v3Decision = state?.v3Decision ?: "UNKNOWN"
        val fdgCan = state?.fdgCan
        val fdgReason = state?.fdgReason ?: "n/a"
        val signal = state?.signal ?: "UNKNOWN"
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
        // V5.9.1320 (Item 6) — RESOLVE THE LANE BEFORE the FDG/EXEC finality checks.
        // The 89 EXEC_OPEN_DROPPED_SELECTED_LANE_UNKNOWN came from candidates whose state
        // carried selectedLane=UNKNOWN (state created by a non-FDG path, or recordFdg lagged
        // the EXEC request) even though a REAL specialist lane was actively requesting the
        // open. Resolution order: a real state.selectedLane wins; otherwise fall back to the
        // real REQUESTING lane (it is the lane trying to execute). Only truly UNKNOWN when
        // NEITHER is a real execution lane → then we block with CANON_LANE_UNRESOLVED.
        val selectedLane = when {
            isRealExecutionLane(rawSelectedLane) -> canonicalLane(rawSelectedLane)
            isRealExecutionLane(lane) -> requestedLane
            else -> "UNKNOWN"
        }
        val canonicalSelectedLane = canonicalLane(selectedLane)
        val preFdgVerdict = state?.preFdgVerdict ?: "WATCH"
        val hardNoReasons = state?.hardNoReasons ?: emptyList()
        val candidateVersion = state?.candidateVersion ?: 0L
        var restorePenalty = LiveRestoreExecutionPolicy.fromRuntimeDrift(liquidityUsd)
        val staleApprovedVerdict = mode.equals("LIVE", true) &&
            preFdgVerdict.uppercase() in setOf("WATCH", "PROBE", "NO_BUY") &&
            fdgCan == true && hardNoReasons.isEmpty() && liquidityUsd >= 1200.0
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

        val modeUpper = mode.uppercase()
        if (modeUpper !in setOf("PAPER", "LIVE", "SHADOW")) {
            return blocked("EXEC_OPEN_BLOCKED_MODE_AUTHORITY", "MIXED_OR_UNKNOWN_MODE_$mode")
        }
        if (modeUpper == "LIVE" && RuntimeModeAuthority.isPaper()) {
            return blocked("EXEC_OPEN_BLOCKED_MODE_AUTHORITY", "LIVE_REQUEST_WHILE_RUNTIME_PAPER")
        }
        if (modeUpper == "PAPER" && RuntimeModeAuthority.isLive()) {
            return blocked("EXEC_OPEN_BLOCKED_MODE_AUTHORITY", "PAPER_REQUEST_WHILE_RUNTIME_LIVE")
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
            val lockReason = ReEntryLockout.lockReasonAdaptive(mint, fam, candidateConf)
            if (lockReason != null) {
                return blocked("EXEC_OPEN_BLOCKED_REENTRY_LOCKOUT", lockReason, shadow = true)
            }
        }



        val pause = ToxicModeCircuitBreaker.currentEntryPause()
        if (pause.active && modeUpper == "LIVE") {
            ToxicModeCircuitBreaker.emitExecutionStateBlockedIfDue(symbol, "ExecutableOpenGate")
            return blocked("EXEC_OPEN_BLOCKED_CIRCUIT_BREAKER", pause.reason.ifBlank { "CIRCUIT_BREAKER" })
        }
        if (pause.active && modeUpper == "PAPER") {
            try { ForensicLogger.lifecycle("PAPER_EXEC_CIRCUIT_PAUSE_BYPASSED", "symbol=$symbol lane=$lane reason=${pause.reason}") } catch (_: Throwable) {}
        }
        if (RuntimeConfigOverlay.isTradingPaused()) {
            return blocked("EXEC_OPEN_BLOCKED_RUNTIME_PAUSED", "RUNTIME_MITIGATION_PAUSE")
        }
        if (BirdeyeBudgetGate.isEntryBudgetLockedDown()) {
            return blocked("EXEC_OPEN_BLOCKED_API_BUDGET_LOCKDOWN", "BIRDEYE_LOCKDOWN")
        }
        // V5.9.1230 — RC=1 is the RugCheck PENDING/UNKNOWN sentinel, not a
        // confirmed rug. Upstream paper policy already allows RC=1 so learning
        // can collect labelled outcomes; however V3 may still stamp the state
        // BLOCK_FATAL as EXTREME_RUG_* before the pending RC resolves. In PAPER
        // CYCLIC only (the original failing lane), when the executable-open rug
        // score is exactly 1 and the fatal reason is rug-score based, treat that
        // V3 fatal as learnable pending.
        // Live remains strict, and confirmed RC=0 / other fatal categories still
        // hard-block unconditionally.
        val paperRcPendingV3Fatal = modeUpper == "PAPER" && requestedLane == "CYCLIC" && rug == 1 && (
            fatalReason.contains("EXTREME_RUG_CRITICAL_score=1", ignoreCase = true) ||
                fatalReason.contains("EXTREME_RUG_RISK_100", ignoreCase = true)
        )
        if ((v3Decision == "BLOCK_FATAL" || v3Decision == "BLOCKED" || band == "BLOCK_FATAL") && !paperRcPendingV3Fatal) {
            return blocked("EXEC_OPEN_BLOCKED_FATAL_V3", fatalReason)
        }
        if (paperRcPendingV3Fatal) {
            try {
                ForensicLogger.lifecycle(
                    "EXEC_OPEN_PAPER_RC_PENDING_V3_FATAL_BYPASSED",
                    "attemptId=$attemptId symbol=$symbol mint=${mint.take(10)} lane=$lane fatalReason=$fatalReason rugScore=$rug"
                )
            } catch (_: Throwable) {}
        }

        val currentCandidateVersion = LaneExecutionCoordinator.candidateVersionFor(mint)
        candidateInvalidReason(
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
        if (!selectedLaneMatchesRequest(selectedLane, lane)) {
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
        if (safetyTier.equals("UNKNOWN", true)) {
            return blocked("EXEC_OPEN_BLOCKED_SAFETY_CONTEXT_MISSING", "PRE_FDG_SAFETY_CONTEXT_MISSING", shadow = mode == "PAPER")
        }
        // V5.9.1504 — RUG-CONTEXT STRICT FALLBACK (master throughput unblock).
        // Operator forensics 14:40: EVERY live candidate (incl. a 95%-pnl KNOWN
        // WINNER that FDG itself flagged "FAST APPROVE") was hard-blocked here
        // with PRE_FDG_RUG_CONTEXT_MISSING → 0/35 open, nothing could buy live.
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
            // PENDING rugcheck (-1). Apply the same STRICT fallback FDG uses.
            val sEntry = state?.entryScore ?: -1
            val tierKnown = !safetyTier.equals("UNKNOWN", true)
            // Floor: real DEX liquidity AND (a resolved safety tier OR a strong
            // V3 entry score). $8K liq matches FDG's weak-fallback liq floor.
            val passesStrictFallback = liquidityUsd >= 8_000.0 && (tierKnown || sEntry >= 50)
            if (!passesStrictFallback) {
                return blocked("EXEC_OPEN_BLOCKED_RUG_CONTEXT_MISSING", "PRE_FDG_RUG_CONTEXT_MISSING", shadow = false)
            }
            try {
                ForensicLogger.lifecycle("PRE_FDG_RUG_PENDING_FALLBACK_PASS",
                    "symbol=$symbol mint=${mint.take(10)} liq=${liquidityUsd.toInt()} tier=$safetyTier entry=$sEntry → routed to FDG")
            } catch (_: Throwable) {}
            // fall through — FDG downstream makes the final allow/block call.
        }
        if (liquidityUsd <= 0.0) {
            // V5.9.1336 — ZERO LIQUIDITY IS UNCONDITIONAL, EVEN IN PAPER.
            // Previously shadow=PAPER let liq=$0 tokens execute in paper "to learn".
            // But a $0-liquidity mint is STRUCTURALLY UNTRADEABLE: no buyer exists,
            // so any paper fill is fictional and every modelled stop-loss is a max
            // loss that could never fill at that price in live. The live snapshot
            // showed BODEN/RUGS (liq=$0) walking through V3 vol_gate soft-shaping
            // straight into EXEC, then dying on SHITCOIN_STOP_LOSS — the dominant
            // source of the 12.9% WR / 45-loss cold streak. This is INVALID DATA,
            // not a -EV judgement, so the Train-First policy (1321) explicitly
            // permits hard-blocking it. Upstream learning surfaces still SEE the
            // token (intake/V3/danger-bucket training are untouched); we just stop
            // manufacturing impossible fills that poison the WR signal.
            return blocked("EXEC_OPEN_BLOCKED_ZERO_LIQUIDITY", "ZERO_LIQUIDITY", shadow = false)
        }
        if (!signal.equals("BUY", true) && !signal.equals("EXECUTE", true)) {
            if (modeUpper == "LIVE" && fdgCan == true && hardNoReasons.isEmpty() && liquidityUsd >= 1200.0) {
                restorePenalty = restorePenalty.combine(LiveRestoreExecutionPolicy.fromStaleWatch(liquidityUsd))
                try { ForensicLogger.lifecycle("LIVE_RESTORE_SIGNAL_SOFT_ALLOW", "symbol=$symbol mint=${mint.take(10)} signal=$signal fdgCan=true liq=${liquidityUsd.toInt()}") } catch (_: Throwable) {}
            } else {
                return blocked("EXEC_OPEN_BLOCKED_SIGNAL_NOT_BUY", signal.ifBlank { "UNKNOWN" }, shadow = mode == "PAPER")
            }
        }
        if (fdgCan != true) {
            return blocked("EXEC_OPEN_BLOCKED_FDG_FINAL", fdgReason, shadow = mode == "PAPER")
        }
        if (signal.isNotBlank() && !signal.equals("UNKNOWN", true) && !signal.equals("BUY", true) && !signal.equals("EXECUTE", true)) {
            if (modeUpper == "LIVE" && fdgCan == true && hardNoReasons.isEmpty() && liquidityUsd >= 1200.0) {
                restorePenalty = restorePenalty.combine(LiveRestoreExecutionPolicy.fromStaleWatch(liquidityUsd))
                try { ForensicLogger.lifecycle("LIVE_RESTORE_SIGNAL_SOFT_ALLOW", "symbol=$symbol mint=${mint.take(10)} signal=$signal fdgCan=true liq=${liquidityUsd.toInt()}") } catch (_: Throwable) {}
            } else {
                return blocked("EXEC_OPEN_BLOCKED_SIGNAL_NOT_BUY", signal, shadow = mode == "PAPER")
            }
        }
        // V5.9.1214 — mirror PAPER low-RC learning policy at final open.
        // Paper blocks only confirmed rug score 0; scores 1..10 are allowed
        // to produce labelled samples. LIVE keeps 1..10 hard-blocked.
        if (rug == 0 || (rug in 1..10 && modeUpper == "LIVE")) {
            return blocked("EXEC_OPEN_BLOCKED_RUG_SCORE", "RC_SCORE_$rug", shadow = mode == "PAPER")
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
        val execKey = canonicalExecutionKey(mint, mode = mode, side = "BUY", lane = lane)
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
            val detail = "attemptId=$execKey symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane source=$source preFdg=$preFdgVerdict selectedLane=$selectedLane hardNo=[] candidateVersion=$candidateVersion v3Decision=$v3Decision fdgCan=${fdgCan ?: "unknown"} fdgReason=$fdgReason safetyTier=$safetyTier rugScore=$rug liquidityUsd=${liquidityUsd.toInt()} signal=$signal band=$band restorePenalty=${restorePenalty.reason} sizeMult=${restorePenalty.sizeMultiplier}"
            ForensicLogger.lifecycle("EXEC_OPEN_REQUEST", detail)
            ForensicLogger.lifecycle("EXEC_GATE_ALLOW", detail)
            ForensicLogger.phase(ForensicLogger.PHASE.EXEC_GATE, symbol, "EXEC_GATE_ALLOW $detail")
            ForensicLogger.gate(ForensicLogger.PHASE.EXEC_GATE, symbol, allow = true, reason = "finality_clear")
            ForensicLogger.lifecycle("EXEC_OPEN_ALLOWED", "attemptId=$execKey symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane reason=finality_clear candidateVersion=$candidateVersion")
        } catch (_: Throwable) {}
        return allowedVerdict
    }
}
