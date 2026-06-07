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
        // V5.9.1384 — immutable terminal no-trade authority. Once a terminal
        // no-trade state is stamped for this mint/candidateVersion, no fallback
        // lane may re-authorize execution. Observations/shadow learning may
        // continue, but EXEC_OPEN_REQUEST / EXEC_GATE_ALLOW / BUY are forbidden.
        val anyTerminalNoTrade: Boolean = false,
        val terminalNoTradeReason: String? = null,
        val terminalNoTradeRank: Int = 0,
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
        if (state.anyTerminalNoTrade) return "EXEC_OPEN_DROPPED_TERMINAL_NO_TRADE" to (state.terminalNoTradeReason ?: "TERMINAL_NO_TRADE")
        val selected = canonicalLane(selectedLane)
        val requested = canonicalLane(requestedLane)
        // V5.9.1320 (Item 6) — lane is resolved upstream (real selected → real requested →
        // UNKNOWN). Reaching here UNKNOWN means NEITHER lane was real: a genuinely unresolved
        // candidate. Surfaced as CANON_LANE_UNRESOLVED, the canonical terminal reason — NOT a
        // post-allow surprise. (Lane defaulting to STANDARD is intentionally NOT done.)
        if (!isRealExecutionLane(selected)) return "EXEC_OPEN_DROPPED_CANON_LANE_UNRESOLVED" to "CANON_LANE_UNRESOLVED_SELECTED_${selected}_REQUEST_${requested}"
        if (preFdgVerdict != "BUY") return "EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY" to preFdgVerdict
        if (hardNoReasons.isNotEmpty()) return "EXEC_OPEN_DROPPED_HARD_NO_BUY" to hardNoReasons.joinToString("+")
        if (candidateVersion != currentVersion) return "EXEC_OPEN_DROPPED_STALE_CANDIDATE" to "STALE_CANDIDATE_VERSION_$candidateVersion"
        return null
    }

    private fun laneKey(mint: String, lane: String): String = mint + ":" + canonicalLane(lane).filter { it.isLetterOrDigit() }
    private const val ALLOWED_ATTEMPT_TTL_MS = 60_000L

    private fun apiLayerDegraded(): Boolean = try {
        val b = BirdeyeBudgetGate.snapshot()
        val api = ApiHealthMonitor.snapshot()
        // V5.9.1386 — providerConservation is a BUDGET POSTURE (Birdeye usage
        // throttling under monthly cap), NOT a health-degradation signal.
        // EMERGENCY_CONSERVATION_MODE is a compile-time `const val = true`
        // in BirdeyeBudgetGate, which made apiLayerDegraded() return TRUE
        // unconditionally — and that in turn made canOpenExecutablePosition
        // mark every rug=-1 paper candidate as TERMINAL_NO_TRADE, breaking
        // the V5.9.1216 "PAPER treats missing RC context as learnable" rule.
        // This is the ACTUAL root cause of the 9-build red streak — the 9
        // V5.9.1385a-i attempts all targeted STALE_CANDIDATE (wrong mode).
        // Conservation alone is no longer treated as degradation.
        if (b.lockedDown || b.providerLockedDown) {
            true
        } else {
            // V5.9.1397 — scope global execution degradation to entry-critical
            // market-data providers. Groq is LLM narrative, Helius creator-history
            // and GeckoTerminal are enrichment/duplicate feeds; if any one of them
            // is rate-limited it must downgrade the missing feature, not poison every
            // candidate's EXEC_GATE while Birdeye/Dex/PumpPortal are healthy.
            val entryCritical = setOf("birdeye", "dexscreener", "pumpfun")
            api.entries.any { (host, st) ->
                host.lowercase() in entryCritical && run {
                    val total = st.successes.get() + st.failures4xx.get() + st.failures5xx.get() + st.networkErrors.get()
                    total >= 5 && st.successRate() < 0.50
                }
            }
        }
    } catch (_: Throwable) { false }

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

    private fun terminalRank(reason: String): Int {
        val r = reason.uppercase()
        return when {
            r.contains("FATAL_BLOCK") || r.contains("BLOCK_FATAL") || r.contains("EXTREME_RUG") -> 100
            r.contains("BLACKLIST_SHADOW") || r.contains("BLACKLIST") || r.contains("BANNED") -> 90
            r.contains("SHADOW_ONLY") || r.contains("SHADOW_TRAIN_ONLY") -> 80
            r.contains("WATCH_ONLY") || r.contains("LIQUIDITY_BELOW_EXECUTION_FLOOR") -> 70
            r.contains("EXPECTANCY_REJECT") -> 60
            r.contains("DATA_DEGRADED") || r.contains("API_DEGRADED") -> 50
            r.contains("REENTRY_LOCKOUT") -> 40
            r.contains("PROBE_ONLY") -> 30
            r.contains("FDG_BLOCK") || r.contains("FDG_FINAL") -> 20
            else -> 10
        }
    }

    private fun terminalLabel(reason: String): String {
        val r = reason.uppercase()
        return when {
            r.contains("FATAL_BLOCK") || r.contains("BLOCK_FATAL") || r.contains("EXTREME_RUG") -> "FATAL_BLOCK"
            r.contains("BLACKLIST_SHADOW") || r.contains("BLACKLIST") || r.contains("BANNED") -> "BLACKLIST_SHADOW"
            r.contains("SHADOW_TRAIN_ONLY") -> "SHADOW_ONLY"
            r.contains("SHADOW_ONLY") -> "SHADOW_ONLY"
            r.contains("LIQUIDITY_BELOW_EXECUTION_FLOOR") || r.contains("WATCH_ONLY") -> "WATCH_ONLY"
            r.contains("EXPECTANCY_REJECT") -> "EXPECTANCY_REJECT"
            r.contains("DATA_DEGRADED") || r.contains("API_DEGRADED") -> "DATA_DEGRADED"
            r.contains("REENTRY_LOCKOUT") -> "REENTRY_LOCKOUT"
            r.contains("PROBE_ONLY") -> "PROBE_ONLY"
            r.contains("FDG_BLOCK") || r.contains("FDG_FINAL") -> "FDG_BLOCK"
            else -> r.take(64)
        }
    }

    fun markTerminalNoTrade(
        mint: String,
        symbol: String,
        reason: String,
        lane: String = "UNKNOWN",
        candidateVersion: Long = LaneExecutionCoordinator.candidateVersionFor(mint),
    ) {
        val label = terminalLabel(reason)
        val rank = terminalRank(reason)
        // V5.9.1387 — idempotency guard. Operator snapshot showed
        // TERMINAL_NO_TRADE_STAMPED and SUPERVISOR_TERMINAL_NO_TRADE_SKIPPED
        // storming the counters (thousands of repeats per cycle) because the
        // same mint+version was being stamped every loop. Stamp once per
        // (mint, candidateVersion, reason) — no-op on duplicate.
        val existing = states[mint]
        if (existing != null &&
            existing.anyTerminalNoTrade &&
            existing.candidateVersion == candidateVersion &&
            existing.terminalNoTradeReason == label &&
            existing.terminalNoTradeRank >= rank) {
            return  // idempotent: already stamped with this reason at this version
        }
        put(mint) { old ->
            val oldRank = old?.terminalNoTradeRank ?: 0
            val keepOld = (old?.anyTerminalNoTrade == true) && oldRank >= rank && old.candidateVersion == candidateVersion
            val finalReason = if (keepOld) old?.terminalNoTradeReason ?: label else label
            (old ?: EntryState(mint = mint, symbol = symbol)).copy(
                symbol = symbol,
                selectedLane = if (isRealExecutionLane(lane)) canonicalLane(lane) else old?.selectedLane ?: lane.uppercase(),
                preFdgVerdict = "NO_BUY",
                signal = "WATCH",
                decisionBand = "WATCH",
                anyTerminalNoTrade = true,
                terminalNoTradeReason = finalReason,
                terminalNoTradeRank = maxOf(oldRank, rank),
                candidateVersion = candidateVersion,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        try {
            ForensicLogger.lifecycle("TERMINAL_NO_TRADE_STAMPED", "mint=${mint.take(10)} symbol=$symbol lane=$lane reason=$label rank=$rank candidateVersion=$candidateVersion")
        } catch (_: Throwable) {}
    }

    fun terminalNoTradeReason(mint: String): String? {
        val state = states[mint] ?: return null
        val currentVersion = LaneExecutionCoordinator.candidateVersionFor(mint)
        return state.terminalNoTradeReason?.takeIf { state.anyTerminalNoTrade && state.candidateVersion == currentVersion }
    }

    fun hasTerminalNoTrade(mint: String): Boolean = terminalNoTradeReason(mint) != null

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
        val terminalSeed = listOf(decision, fatalReason ?: "", decisionBand).joinToString("|")
        val terminal = when {
            terminalSeed.contains("SHADOW_ONLY", ignoreCase = true) -> "SHADOW_ONLY:${fatalReason ?: decision}"
            terminalSeed.contains("LIQUIDITY_BELOW_EXECUTION_FLOOR", ignoreCase = true) || terminalSeed.contains("WATCH_ONLY", ignoreCase = true) -> "WATCH_ONLY:${fatalReason ?: decision}"
            terminalSeed.contains("EXPECTANCY_REJECT", ignoreCase = true) -> "EXPECTANCY_REJECT:${fatalReason ?: decision}"
            terminalSeed.contains("BLOCK_FATAL", ignoreCase = true) || terminalSeed.contains("FATAL_BLOCK", ignoreCase = true) -> "FATAL_BLOCK:${fatalReason ?: decision}"
            else -> null
        }
        if (terminal != null) markTerminalNoTrade(mint, symbol, terminal, lane = "V3")
        put(mint) { old ->
            val keepTerminal = old?.anyTerminalNoTrade == true
            (old ?: EntryState(mint = mint, symbol = symbol)).copy(
                symbol = symbol,
                v3Decision = decision,
                v3FatalReason = fatalReason,
                decisionBand = if (keepTerminal) "WATCH" else decisionBand,
                rugScore = if (rugScore >= 0) rugScore else old?.rugScore ?: -1,
                safetyTier = safetyTier,
                preFdgVerdict = if (keepTerminal) "NO_BUY" else old?.preFdgVerdict ?: "WATCH",
                signal = if (keepTerminal) "WATCH" else old?.signal ?: "UNKNOWN",
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
        val finalVerdict = when {
            finalHardNo.isNotEmpty() -> "HARD_NO_BUY"
            !canExecute -> preFdgVerdict.takeIf { it != "BUY" } ?: "NO_BUY"
            signal.equals("BUY", true) || signal.equals("EXECUTE", true) -> "BUY"
            else -> "WATCH"
        }
        val terminalSeed = listOf(reason ?: "", signal, preFdgVerdict, finalVerdict, finalHardNo.joinToString("|")).joinToString("|")
        val terminal = when {
            terminalSeed.contains("EXPECTANCY_REJECT", ignoreCase = true) -> "EXPECTANCY_REJECT:${reason ?: finalVerdict}"
            terminalSeed.contains("LIQUIDITY_BELOW_EXECUTION_FLOOR", ignoreCase = true) -> "WATCH_ONLY:LIQUIDITY_BELOW_EXECUTION_FLOOR"
            terminalSeed.contains("SHADOW_ONLY", ignoreCase = true) || terminalSeed.contains("SHADOW_TRAIN_ONLY", ignoreCase = true) -> "SHADOW_ONLY:${reason ?: finalVerdict}"
            terminalSeed.contains("DATA_DEGRADED", ignoreCase = true) || terminalSeed.contains("API_DEGRADED", ignoreCase = true) -> "DATA_DEGRADED:${reason ?: finalVerdict}"
            terminalSeed.contains("REENTRY_LOCKOUT", ignoreCase = true) -> "REENTRY_LOCKOUT:${reason ?: finalVerdict}"
            finalVerdict == "HARD_NO_BUY" -> "FATAL_BLOCK:${reason ?: finalHardNo.firstOrNull() ?: finalVerdict}"
            else -> null
        }
        if (terminal != null) markTerminalNoTrade(mint, symbol, terminal, lane = lane, candidateVersion = candidateVersion)
        put(mint) { old ->
            val keepTerminal = old?.anyTerminalNoTrade == true && old.candidateVersion == candidateVersion
            (old ?: EntryState(mint = mint, symbol = symbol)).copy(
                symbol = symbol,
                fdgCan = canExecute,
                fdgReason = reason,
                signal = if (keepTerminal) "WATCH" else signal.ifBlank { "UNKNOWN" },
                decisionBand = if (keepTerminal) "WATCH" else if (finalVerdict == "BUY") "BUY" else (old?.decisionBand ?: finalVerdict),
                selectedLane = lane.uppercase(),
                preFdgVerdict = if (keepTerminal) "NO_BUY" else finalVerdict,
                hardNoReasons = if (keepTerminal) (finalHardNo + (old?.terminalNoTradeReason ?: "TERMINAL_NO_TRADE")).distinct() else finalHardNo,
                candidateVersion = candidateVersion,
                entryScore = if (entryScore >= 0) entryScore else old?.entryScore ?: -1,
                liquidityUsd = liquidityUsd,
                rugScore = if (rugScore >= 0) rugScore else old?.rugScore ?: -1,
                safetyTier = safetyTier,
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
            val verdictLabel = if (canExecute && finalVerdict == "BUY" && finalHardNo.isEmpty()) "BUY" else "BLOCK"
            try { ForensicLogger.decision(ForensicLogger.PHASE.FDG, symbol, verdictLabel, 0, 0, reason ?: finalHardNo.firstOrNull() ?: verdictLabel) } catch (_: Throwable) {}
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

        if (state?.anyTerminalNoTrade == true && candidateVersion == LaneExecutionCoordinator.candidateVersionFor(mint)) {
            return OpenVerdict(false, state.terminalNoTradeReason ?: "TERMINAL_NO_TRADE", shadowOnly = true, logName = "EXEC_OPEN_DROPPED_TERMINAL_NO_TRADE", attemptId = attemptId)
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
            try {
                val detail = "attemptId=$attemptId symbol=${symbol} mint=${mint.take(10)} mode=$mode lane=$lane preFdg=$preFdgVerdict selectedLane=$selectedLane hardNo=${hardNoReasons.joinToString(prefix="[", postfix="]")} safetyTier=$safetyTier rugScore=$rug liquidityUsd=${liquidityUsd.toInt()} candidateVersion=$candidateVersion reason=$reason"
                ForensicLogger.lifecycle(log, detail)
                ForensicLogger.phase(ForensicLogger.PHASE.EXEC_GATE, symbol, "EXEC_GATE_DROPPED $detail")
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
        // V5.9.1373 — SHADOW_TRAIN_ONLY EXECUTION GATE (P0 spec #1).
        // Provably-toxic buckets (matured: n>=20, lossRate>=75% OR mean<=-10%)
        // must remain TRAINABLE but must NOT create an executable BUY. All
        // learning (counterfactual, MFE/MAE, hypothesis, forward model, memory)
        // already ran UPSTREAM of this gate, and the blocked() path emits a
        // NoTradeObservation row, so the bucket keeps learning — it just stops
        // bleeding real (paper/live) capital and contaminating headline WR.
        // Gate by the canonical execution lane + the recorded entry score.
        // Fail-open: unknown score (-1) or any error => execute (BucketExecutionState
        // is itself fail-open). Does NOT touch SL/TP, scanner, or tuning.
        run {
            val gateScore = state?.entryScore ?: -1
            if (gateScore >= 0 && isRealExecutionLane(canonicalSelectedLane)) {
                if (BucketExecutionState.isShadowTrainOnly(canonicalSelectedLane, gateScore)) {
                    return blocked(
                        "EXEC_OPEN_BLOCKED_SHADOW_TRAIN_ONLY",
                        "SHADOW_TRAIN_ONLY:${BucketExecutionState.describe(canonicalSelectedLane, gateScore)}",
                        shadow = true,
                    )
                }
            }
        }

        // V5.9.1375 — RE-ENTRY LOCKOUT (P0 #6). After a stop-loss on this mint /
        // symbol-family, refuse to re-open for >=10 min (or until cleared by a new
        // ATH). Kills the BUY->STOP_LOSS->BUY bleed loop. Fail-open. Learning paths
        // are upstream and unaffected; a blocked re-entry still emits a NoTradeObs.
        run {
            val fam = symbol.uppercase().trim().filter { it.isLetterOrDigit() }.take(8)
            val lockReason = ReEntryLockout.lockReason(mint, fam)
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
        // V5.9.1384 — API degraded is EXECUTION-AUTHORITY, not a warning.
        // During Helius/Groq/Gecko/Pumpfun/Birdeye degradation, allow only
        // watch/shadow/counterfactual learning unless the minimum concrete fields
        // required for an executable fill are fresh enough to be represented here:
        // liquidity, rug/safety, real selected lane, BUY signal, and FDG final allow.
        if (apiLayerDegraded()) {
            val concreteFresh = liquidityUsd > 0.0 && rug >= 0 &&
                !safetyTier.equals("UNKNOWN", true) &&
                isRealExecutionLane(canonicalSelectedLane) &&
                (signal.equals("BUY", true) || signal.equals("EXECUTE", true)) &&
                fdgCan == true
            if (!concreteFresh) {
                // V5.9.1397 — V3Orchestrator runs before BotService's canonical
                // lane FDG/finality pass. It may see selectedLane/preFdg from a
                // previous/secondary lane and would terminal-stamp DATA_DEGRADED
                // before the actual primary lane finishes FDG. For that source,
                // return a block verdict for telemetry only; BotService's real
                // TradeAuthorizer/FinalExecutionPermit path remains the authority.
                if (!source.equals("V3Orchestrator", true)) {
                    markTerminalNoTrade(mint, symbol, "DATA_DEGRADED:minimum_fresh_fields_missing", lane, candidateVersion)
                }
                return blocked(
                    "EXEC_OPEN_BLOCKED_API_LAYER_DEGRADED",
                    "DATA_DEGRADED_MIN_FRESH_MISSING priceFresh=false liquidityFresh=${liquidityUsd > 0.0} routeFresh=${isRealExecutionLane(canonicalSelectedLane)} rugSafetyFresh=${rug >= 0 && !safetyTier.equals("UNKNOWN", true)} slippageFresh=false",
                    shadow = true,
                )
            }
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
