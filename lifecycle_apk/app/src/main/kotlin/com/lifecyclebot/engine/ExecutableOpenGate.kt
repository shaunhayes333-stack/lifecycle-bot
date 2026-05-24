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
        val signal: String = "UNKNOWN",
        val decisionBand: String = "UNKNOWN",
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
    private fun laneKey(mint: String, lane: String): String = mint + ":" + lane.uppercase().filter { it.isLetterOrDigit() }
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
        signal: String = "UNKNOWN",
        rugScore: Int = -1,
        safetyTier: String = "UNKNOWN",
    ) {
        put(mint) { old ->
            (old ?: EntryState(mint = mint, symbol = symbol)).copy(
                symbol = symbol,
                fdgCan = canExecute,
                fdgReason = reason,
                signal = signal,
                rugScore = if (rugScore >= 0) rugScore else old?.rugScore ?: -1,
                safetyTier = safetyTier,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
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

        fun blocked(log: String, reason: String, shadow: Boolean = false): OpenVerdict {
            try {
                val coolMs = cooldownMsFor(log, reason)
                if (coolMs > 0L) blockedCooldowns[laneKey(mint, lane)] = reason to (System.currentTimeMillis() + coolMs)
            } catch (_: Throwable) {}
            try {
                ForensicLogger.lifecycle(log, "attemptId=$attemptId symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane ${if (log.contains("FDG")) "fdgReason=$reason" else if (log.contains("V3")) "fatalReason=$reason" else if (log.contains("SIGNAL")) "signal=$signal" else "reason=$reason"}")
            } catch (_: Throwable) {}
            // No PAPER_LEARNING_PROBE_NOT_EXECUTED spam here. A blocked open is
            // already represented by its EXEC_OPEN_BLOCKED_* reason; probe spam was
            // self-DOSing the loop and hiding real executor demand.
            return OpenVerdict(false, reason, shadowOnly = shadow, logName = log, attemptId = attemptId)
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
        if (pause.active) {
            ToxicModeCircuitBreaker.emitExecutionStateBlockedIfDue(symbol, "ExecutableOpenGate")
            return blocked("EXEC_OPEN_BLOCKED_CIRCUIT_BREAKER", pause.reason.ifBlank { "CIRCUIT_BREAKER" })
        }
        if (RuntimeConfigOverlay.isTradingPaused()) {
            return blocked("EXEC_OPEN_BLOCKED_RUNTIME_PAUSED", "RUNTIME_MITIGATION_PAUSE")
        }
        if (BirdeyeBudgetGate.isEntryBudgetLockedDown()) {
            return blocked("EXEC_OPEN_BLOCKED_API_BUDGET_LOCKDOWN", "BIRDEYE_LOCKDOWN")
        }
        if (v3Decision == "BLOCK_FATAL" || v3Decision == "BLOCKED" || v3Decision == "REJECTED" || band == "BLOCK_FATAL" || band == "REJECT") {
            return blocked("EXEC_OPEN_BLOCKED_FATAL_V3", fatalReason)
        }
        if (signal.isNotBlank() && !signal.equals("UNKNOWN", true) && !signal.equals("BUY", true) && !signal.equals("EXECUTE", true)) {
            return blocked("EXEC_OPEN_BLOCKED_SIGNAL_NOT_BUY", signal, shadow = mode == "PAPER")
        }
        if (rug <= 10 && rug >= 0) {
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
        val prior = openRequests.putIfAbsent(execKey, now)
        if (prior != null && now - prior <= ALLOWED_ATTEMPT_TTL_MS) {
            val sameLanePreAuth = try {
                allowedAttempts[laneKey(mint, lane)]?.let { it.first == execKey && now - it.second <= ALLOWED_ATTEMPT_TTL_MS } == true
            } catch (_: Throwable) { false }
            if (sameLanePreAuth) {
                try {
                    ForensicLogger.lifecycle(
                        "EXEC_OPEN_ALLOWED_REUSE",
                        "attemptId=$execKey symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane source=$source reason=same_lane_preauth_reuse",
                    )
                } catch (_: Throwable) {}
                return OpenVerdict(true, "finality_clear_reuse", attemptId = execKey)
            }
            try { TradeOutcomeLedger.recordSuppressedDuplicateOpen() } catch (_: Throwable) {}
            return blocked("EXEC_OPEN_BLOCKED_DUPLICATE_KEY", "DUPLICATE_EXECUTION_KEY")
        }
        try { allowedAttempts[laneKey(mint, lane)] = execKey to System.currentTimeMillis() } catch (_: Throwable) {}
        try {
            ForensicLogger.lifecycle(
                "EXEC_OPEN_REQUEST",
                "attemptId=$execKey symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane source=$source v3Decision=$v3Decision fdgCan=${fdgCan ?: "unknown"} fdgReason=$fdgReason safetyTier=$safetyTier rugScore=$rug signal=$signal band=$band",
            )
            ForensicLogger.lifecycle("EXEC_OPEN_ALLOWED", "attemptId=$execKey symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane reason=finality_clear")
        } catch (_: Throwable) {}
        return OpenVerdict(true, "finality_clear", attemptId = execKey)
    }
}
