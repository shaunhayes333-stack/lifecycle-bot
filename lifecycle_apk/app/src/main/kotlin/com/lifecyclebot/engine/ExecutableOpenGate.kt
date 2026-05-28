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
        val candidateVersion: Long = 0L,
        val updatedAtMs: Long = System.currentTimeMillis(),
    )

    data class OpenVerdict(
        val allowed: Boolean,
        val reason: String,
        val shadowOnly: Boolean = false,
        val logName: String = "EXEC_OPEN_ALLOWED",
        val attemptId: String = "",
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
    ): String = "$runtimeGeneration:${mode.uppercase()}:${mint.trim()}:${side.uppercase()}:$candidateVersion"

    private val states = ConcurrentHashMap<String, EntryState>()
    private const val TTL_MS = 10 * 60 * 1000L
    private val allowedAttempts = ConcurrentHashMap<String, Pair<String, Long>>()
    private val openRequests = ConcurrentHashMap<String, Long>()
    private val blockedCooldowns = ConcurrentHashMap<String, Pair<String, Long>>()

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
    ): Pair<String, String>? {
        if (state == null) return "EXEC_OPEN_DROPPED_NO_FINAL_CANDIDATE" to "NO_FINAL_BUY_CANDIDATE"
        val selected = canonicalLane(selectedLane)
        val requested = canonicalLane(requestedLane)
        if (!isRealExecutionLane(selected)) return "EXEC_OPEN_DROPPED_SELECTED_LANE_UNKNOWN" to "SELECTED_LANE_${selected}_REQUEST_${requested}"
        if (preFdgVerdict != "BUY") return "EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY" to preFdgVerdict
        if (hardNoReasons.isNotEmpty()) return "EXEC_OPEN_DROPPED_HARD_NO_BUY" to hardNoReasons.joinToString("+")
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
                safetyTier = safetyTier,
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
        val finalVerdict = when {
            finalHardNo.isNotEmpty() -> "HARD_NO_BUY"
            !canExecute -> preFdgVerdict.takeIf { it != "BUY" } ?: "NO_BUY"
            signal.equals("BUY", true) || signal.equals("EXECUTE", true) -> "BUY"
            else -> "WATCH"
        }
        put(mint) { old ->
            (old ?: EntryState(mint = mint, symbol = symbol)).copy(
                symbol = symbol,
                fdgCan = canExecute,
                fdgReason = reason,
                signal = signal.ifBlank { "UNKNOWN" },
                decisionBand = if (finalVerdict == "BUY") "BUY" else (old?.decisionBand ?: finalVerdict),
                selectedLane = lane.uppercase(),
                preFdgVerdict = finalVerdict,
                hardNoReasons = finalHardNo,
                candidateVersion = candidateVersion,
                liquidityUsd = liquidityUsd,
                rugScore = if (rugScore >= 0) rugScore else old?.rugScore ?: -1,
                safetyTier = safetyTier,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        try {
            val hard = finalHardNo.joinToString(prefix = "[", postfix = "]")
            val msg = "symbol=$symbol lane=${lane.uppercase()} preFdg=$finalVerdict hardNo=$hard safety=$safetyTier rug=$rugScore liq=${liquidityUsd.toInt()} duplicate=false circuit=${ToxicModeCircuitBreaker.currentEntryPause().active} sellPressure=${reason ?: "OK"} version=$candidateVersion"
            if (canExecute && finalVerdict == "BUY" && finalHardNo.isEmpty()) {
                ErrorLogger.info("FDG", "FDG_ALLOW $symbol lane=${lane.uppercase()} preFdg=BUY hardNo=[] safety=$safetyTier rug=$rugScore liq=${liquidityUsd.toInt()} duplicate=false circuit=${ToxicModeCircuitBreaker.currentEntryPause().active} sellPressure=OK version=$candidateVersion")
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
    ): OpenVerdict {
        return canOpenExecutablePositionInternal(
            mint = mint,
            symbol = symbol,
            rug = rugScore,
            mode = mode,
            lane = lane,
            source = source,
            attemptId = attemptId,
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
    ): OpenVerdict {
        val state = states[mint]
        val v3Decision = state?.v3Decision ?: "UNKNOWN"
        val fdgCan = state?.fdgCan
        val fdgReason = state?.fdgReason ?: "n/a"
        val signal = state?.signal ?: "UNKNOWN"
        val band = state?.decisionBand ?: v3Decision
        val fatalReason = state?.v3FatalReason ?: fdgReason
        val safetyTier = state?.safetyTier ?: "UNKNOWN"
        val liquidityUsd = state?.liquidityUsd ?: 0.0
        val selectedLane = state?.selectedLane ?: "UNKNOWN"
        val requestedLane = canonicalLane(lane)
        val canonicalSelectedLane = canonicalLane(selectedLane)
        val preFdgVerdict = state?.preFdgVerdict ?: "WATCH"
        val hardNoReasons = state?.hardNoReasons ?: emptyList()
        val candidateVersion = state?.candidateVersion ?: 0L

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
            try {
                val detail = "attemptId=$attemptId symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane preFdg=$preFdgVerdict selectedLane=$selectedLane hardNo=${hardNoReasons.joinToString(prefix="[", postfix="]")} safetyTier=$safetyTier rugScore=$rug liquidityUsd=${liquidityUsd.toInt()} candidateVersion=$candidateVersion reason=$reason"
                ForensicLogger.lifecycle(log, detail)
                ForensicLogger.phase(ForensicLogger.PHASE.EXEC_GATE, symbol, "EXEC_GATE_DROPPED $detail")
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
        if (v3Decision == "BLOCK_FATAL" || v3Decision == "BLOCKED" || band == "BLOCK_FATAL") {
            return blocked("EXEC_OPEN_BLOCKED_FATAL_V3", fatalReason)
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
        )?.let { (log, reason) ->
            if (log.contains("STALE_CANDIDATE")) {
                try { LaneExecutionCoordinator.releaseIfPrimary(mint, laneForRelease(selectedLane, lane), "CANDIDATE_STALE_DROPPED", candidateVersion = candidateVersion) } catch (_: Throwable) {}
                try { ForensicLogger.lifecycle("CANDIDATE_STALE_DROPPED", "mint=${mint.take(10)} symbol=$symbol lane=$lane selectedLane=$selectedLane candidateVersion=$candidateVersion currentVersion=$currentCandidateVersion") } catch (_: Throwable) {}
            }
            return dropped(log, reason)
        }
        if (!selectedLaneMatchesRequest(selectedLane, lane)) {
            return dropped("EXEC_OPEN_DROPPED_SELECTED_LANE_MISMATCH", "SELECTED_LANE_${canonicalSelectedLane}_REQUEST_${requestedLane}")
        }
        if (safetyTier.equals("UNKNOWN", true)) {
            return blocked("EXEC_OPEN_BLOCKED_SAFETY_CONTEXT_MISSING", "PRE_FDG_SAFETY_CONTEXT_MISSING", shadow = mode == "PAPER")
        }
        if (rug < 0 && modeUpper == "LIVE") {
            return blocked("EXEC_OPEN_BLOCKED_RUG_CONTEXT_MISSING", "PRE_FDG_RUG_CONTEXT_MISSING", shadow = false)
        }
        if (liquidityUsd <= 0.0) {
            return blocked("EXEC_OPEN_BLOCKED_ZERO_LIQUIDITY", "ZERO_LIQUIDITY", shadow = mode == "PAPER")
        }
        if (!signal.equals("BUY", true) && !signal.equals("EXECUTE", true)) {
            return blocked("EXEC_OPEN_BLOCKED_SIGNAL_NOT_BUY", signal.ifBlank { "UNKNOWN" }, shadow = mode == "PAPER")
        }
        if (fdgCan != true) {
            return blocked("EXEC_OPEN_BLOCKED_FDG_FINAL", fdgReason, shadow = mode == "PAPER")
        }
        if (signal.isNotBlank() && !signal.equals("UNKNOWN", true) && !signal.equals("BUY", true) && !signal.equals("EXECUTE", true)) {
            return blocked("EXEC_OPEN_BLOCKED_SIGNAL_NOT_BUY", signal, shadow = mode == "PAPER")
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
                return OpenVerdict(true, "finality_clear_recheck", attemptId = execKey)
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
        try {
            val detail = "attemptId=$execKey symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane source=$source preFdg=$preFdgVerdict selectedLane=$selectedLane hardNo=[] candidateVersion=$candidateVersion v3Decision=$v3Decision fdgCan=${fdgCan ?: "unknown"} fdgReason=$fdgReason safetyTier=$safetyTier rugScore=$rug liquidityUsd=${liquidityUsd.toInt()} signal=$signal band=$band"
            ForensicLogger.lifecycle("EXEC_OPEN_REQUEST", detail)
            ForensicLogger.lifecycle("EXEC_GATE_ALLOW", detail)
            ForensicLogger.phase(ForensicLogger.PHASE.EXEC_GATE, symbol, "EXEC_GATE_ALLOW $detail")
            ForensicLogger.gate(ForensicLogger.PHASE.EXEC_GATE, symbol, allow = true, reason = "finality_clear")
            ForensicLogger.lifecycle("EXEC_OPEN_ALLOWED", "attemptId=$execKey symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane reason=finality_clear candidateVersion=$candidateVersion")
        } catch (_: Throwable) {}
        return OpenVerdict(true, "finality_clear", attemptId = execKey)
    }
}
