package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.data.TokenState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CyclicTradeEngine — Compound a fixed $500 USD ring by cycling it through winning trades.
 *
 * Live execution is allowed when:
 *   1. User explicitly enabled it in settings (cfg.cyclicTradeLiveEnabled), OR
 *   2. TreasuryManager.treasuryUsd >= 5000.0
 *
 * Otherwise runs in paper mode, tracking virtual compound growth.
 *
 * The engine picks the highest-scored token from the current watchlist,
 * deploys the ring balance, waits for TP/SL, then re-deploys.
 */
object CyclicTradeEngine {

    private const val TAG = "CyclicTrade"
    private const val PREFS_FILE = "cyclic_trade_prefs"
    private const val RING_SIZE_USD = 500.0
    // V5.9.451 — TP/SL now adapt (see adaptiveTpSl()). Constants are the
    // default mature-phase levels; bootstrap and loss-streak modes override.
    // V5.9.1227 — this is the $500→$1M compounding lane, not a scalp lane.
    // 500→1,000,000 = 2000×. Over 15-20 successful cycles that requires
    // roughly +47% to +66% per winner. TP is therefore the RUNNER activation
    // line, not a full-exit ceiling.
    private const val DEFAULT_TP_PCT = 65.0
    private const val DEFAULT_SL_PCT = 8.0
    private const val MIN_SCORE_TO_ENTER = 62.0
    // V5.9.1234 — Cyclic was deploying the full ring while cold (e.g. 1W/4L,
    // ring $3, -99% growth). Keep sampling, but stop full-ring gambling until
    // its own curve has proven profitable.
    private const val COLD_MIN_SCORE_TO_ENTER = 72.0
    private const val COLD_RING_SIZE_MULT = 0.20
    // V5.9.240: During bootstrap (<40% learning) lower the score floor so the
    // ring engine actually trades while FluidLearningAI is still calibrating.
    // Tokens don't have a reliable lastV3Score yet at that stage — entryScore
    // (raw signal) is used as the proxy. 30 is still a real signal, not noise.
    private const val MIN_SCORE_TO_ENTER_BOOTSTRAP = 48.0
    private const val COOLDOWN_MS = 30_000L    // 30s between cycles
    private const val MAX_HOLD_MS = 90 * 60 * 1000L  // 90 min max hold

    // V5.9.451 — loss-streak back-off window (user: "needs loop learning
    // and AI assistance symbolic reasoning and sentience etc for more
    // success"). After 3 consecutive losses we pause 5 min and tighten
    // SL / widen TP until the next win.
    private const val LOSS_STREAK_BREAK_COUNT    = 3
    private const val LOSS_STREAK_BREAK_PAUSE_MS = 5 * 60 * 1000L
    @Volatile private var consecutiveLosses: Int = 0
    // V5.9.696 — Dynamic stop: track high-water pnl per position so profits get locked.
    @Volatile private var positionHighWaterPnlPct: Double = 0.0
    @Volatile private var pauseUntilMs: Long = 0L

    // ── State ─────────────────────────────────────────────────────────────────
    @Volatile var ringBalanceSol: Double = 0.0
        private set
    @Volatile var ringBalanceUsd: Double = RING_SIZE_USD
        private set
    @Volatile var cycleCount: Int = 0
        private set
    @Volatile var totalPnlSol: Double = 0.0
        private set
    @Volatile var winCount: Int = 0
        private set
    @Volatile var lossCount: Int = 0
        private set
    @Volatile var currentMint: String = ""
        private set
    @Volatile var currentSymbol: String = ""
        private set
    @Volatile var entryPriceSol: Double = 0.0
        private set
    @Volatile private var entrySizeSol: Double = 0.0
    @Volatile var entryTimeMs: Long = 0L
        private set
    @Volatile var lastCycleEndMs: Long = 0L
        private set
    @Volatile var isInPosition: Boolean = false
        private set
    @Volatile var isRunning: Boolean = false
        private set
    @Volatile var isLiveMode: Boolean = false
        private set
    @Volatile var statusMessage: String = "Idle"
        private set

    private val enabled = AtomicBoolean(false)

    // ── Initialise ─────────────────────────────────────────────────────────────
    fun init(context: Context) {
        val prefs = prefs(context)
        ringBalanceSol = prefs.getFloat("ring_balance_sol", 0f).toDouble()
        ringBalanceUsd = prefs.getFloat("ring_balance_usd", RING_SIZE_USD.toFloat()).toDouble()
        cycleCount     = prefs.getInt("cycle_count", 0)
        totalPnlSol    = prefs.getFloat("total_pnl_sol", 0f).toDouble()
        winCount       = prefs.getInt("win_count", 0)
        lossCount      = prefs.getInt("loss_count", 0)
        isInPosition   = false  // Always start fresh — don't resume mid-position after kill
        currentMint    = ""
        isRunning      = false
        ErrorLogger.info(TAG, "CyclicTradeEngine initialised | ring=\$${ringBalanceUsd.toInt()} | cycles=$cycleCount")
    }

    fun setEnabled(on: Boolean) { enabled.set(on) }
    fun isEnabled(): Boolean = enabled.get()

    // ── Main tick — call every N loops from BotService ─────────────────────────
    fun tick(
        context: Context,
        tokens: Map<String, TokenState>,
        executor: Executor,
        wallet: com.lifecyclebot.network.SolanaWallet?,
        walletSol: Double
    ) {
        val cfg = ConfigStore.load(context)
        // V5.9.222: Always run in paper mode — paper needs no user opt-in.
        // Live execution still requires cyclicTradeLiveEnabled or treasury >= $5K.
        // The old hard-return on !cyclicTradeEnabled was silently killing the engine
        // because the flag defaulted to false and had no UI toggle.
        if (!enabled.get()) { enabled.set(true) }

        // Determine live vs paper
        val treasuryUsd = TreasuryManager.treasuryUsd
        val solPrice = WalletManager.lastKnownSolPrice.takeIf { it > 0.0 } ?: 150.0
        // V5.9.772 EMERGENT MEME — treasury must respect the GLOBAL trade
        // mode. Operator forensic 22:54 (V5.9.771) showed treasury firing
        // PAPER buys while the bot was in LIVE mode, polluting live state
        // ("treasury still continues to buy when the bot is in live mode
        // making paper trades also polluting live trading with its
        // balance"). Root cause: the prior `isLiveMode` formula only
        // looked at the cyclic-specific flags + treasury threshold, never
        // at `cfg.paperMode`. Now:
        //   global PAPER   → cyclic MUST be paper (skip live entirely)
        //   global LIVE    → cyclic MUST be live; if not opted-in, skip
        //                    the cycle rather than firing a paper trade
        //                    that bleeds into the live UI/risk.
        val globalLive = !cfg.paperMode
        val cyclicLiveOptedIn = (cfg.cyclicTradeEnabled && cfg.cyclicTradeLiveEnabled) ||
                                (cfg.cyclicTradeEnabled && treasuryUsd >= 5_000.0)
        if (globalLive && !cyclicLiveOptedIn) {
            // Live mode but the operator hasn't opted into live cyclic
            // (or treasury below threshold) → DO NOT fire a paper cycle.
            // Skip the tick entirely so we never produce a paper position
            // while the rest of the bot is live.
            try {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "TREASURY_LIVE_NOT_OPTED_IN",
                    "globalLive=true cyclicLiveEnabled=${cfg.cyclicTradeLiveEnabled} treasuryUsd=${treasuryUsd.toInt()} → cycle skipped (no paper bleed)",
                )
            } catch (_: Throwable) {}
            statusMessage = "🚫 Live mode active — cyclic live not opted in (treasury \$${treasuryUsd.toInt()})"
            return
        }
        isLiveMode = globalLive   // mirror global; paper-mode → paper cycle

        // Compute ring size in SOL
        if (ringBalanceSol <= 0.0) {
            ringBalanceSol = ringBalanceUsd / solPrice
        }

        // ── 1. In position — check TP/SL/timeout ──────────────────────────────
        if (isInPosition && currentMint.isNotBlank()) {
            val ts = tokens[currentMint]
            if (ts == null) {
                // Token fell off watchlist — abort cycle
                abandonCycle(context, "token_lost", solPrice)
                return
            }
            val currentPrice = ts.lastPrice.takeIf { it > 0.0 } ?: return
            val pnlPct = if (entryPriceSol > 0.0) {
                ((currentPrice - entryPriceSol) / entryPriceSol) * 100.0
            } else 0.0

            val (tpPct, slPct) = adaptiveTpSl()
            val holdMs = System.currentTimeMillis() - entryTimeMs

            // V5.9.696 — Dynamic profit lock (ratchet).
            // Once a position reaches a profit threshold, lock in a floor so
            // gains can't fully evaporate. High-water tracking + ratchet tiers.
            if (pnlPct > positionHighWaterPnlPct) positionHighWaterPnlPct = pnlPct

            // V5.9.898 — RUNNER MODE for the $500→$1M ring.
            // PRE-FIX: hitTP at +15% closed the ENTIRE ring, capping every
            // winner at exactly +15%. A token that ran to +50% / +100% was
            // exited at +15%. That mathematically forbids the compounding
            // path to $1M because EV/cycle is bounded by TP regardless of
            // runner availability.
            //
            // FIX: once we're past TP, ENTER RUNNER MODE — don't close on
            // TP hit, instead RATCHET the profit floor higher. The ring
            // only exits when the runner gives back N% from high-water,
            // letting +50%/+100% moves actually contribute.
            //
            // Trail config (chosen conservatively):
            //   HW ≥ 15%:  floor at +8%      (lock 8 of the 15)
            //   HW ≥ 25%:  floor at +15%     (lock the original TP)
            //   HW ≥ 40%:  floor at +25%
            //   HW ≥ 60%:  floor at +40%
            //   HW ≥ 100%: floor at +70%
            //   HW ≥ 200%: floor at HW*0.70  (continuous 30% trail)
            //
            // If pnlPct ever falls below the active floor → close as
            // RUNNER_TRAIL_<floor>. Otherwise keep riding.
            //
            // Sub-TP profit-lock cascade (V5.9.696 + tightened V5.9.898):
            //   HW ≥ 10%: floor +3% (was: same)
            //   HW ≥  8%: floor +1% (NEW — between 6 and 10 there was a gap)
            //   HW ≥  6%: floor  0% (was: same)
            //   HW ≥  4%: floor -1% (was: same)
            //   HW ≥  2%: floor -3% (NEW — tighten on early gains)
            val runnerFloor: Double? = when {
                positionHighWaterPnlPct >= 200.0 -> positionHighWaterPnlPct * 0.70
                positionHighWaterPnlPct >= 100.0 -> 70.0
                positionHighWaterPnlPct >= 60.0  -> 40.0
                positionHighWaterPnlPct >= 40.0  -> 25.0
                positionHighWaterPnlPct >= 25.0  -> 15.0
                positionHighWaterPnlPct >= 15.0  -> 8.0
                positionHighWaterPnlPct >= 10.0  -> 3.0
                positionHighWaterPnlPct >= 8.0   -> 1.0
                positionHighWaterPnlPct >= 6.0   -> 0.0
                positionHighWaterPnlPct >= 4.0   -> -1.0
                positionHighWaterPnlPct >= 2.0   -> -3.0
                else                              -> null
            }
            val inRunnerMode = positionHighWaterPnlPct >= tpPct

            // V5.9.898 — timeout BYPASS for runners.
            // Pre-fix: any cycle that hadn't TP'd by 90min closed regardless
            // of trajectory. A +12% position at minute 89 still in uptrend
            // would close at TIMEOUT for less than TP. That penalises the
            // exact runners we need for compounding.
            //
            // Bypass rule: skip timeout while HW ≥ TP*0.6 AND pnl ≥ HW*0.7
            // (still close to high-water → trend intact). Hard ceiling at
            // 3× MAX_HOLD_MS to prevent zombies.
            val hwGate = positionHighWaterPnlPct >= tpPct * 0.6
            val trendIntact = pnlPct >= positionHighWaterPnlPct * 0.7
            val absoluteMaxHold = MAX_HOLD_MS * 3L
            val timedOut = when {
                holdMs >= absoluteMaxHold -> true                  // hard ceiling
                hwGate && trendIntact     -> false                 // still riding
                holdMs >= MAX_HOLD_MS     -> true                  // legacy timeout
                else                       -> false
            }

            val floorBreached = runnerFloor != null && pnlPct < runnerFloor
            val hitSL    = pnlPct <= -slPct

            val exitReason: String? = when {
                floorBreached && inRunnerMode ->
                    "RUNNER_TRAIL_${runnerFloor!!.toInt()}_HW${positionHighWaterPnlPct.toInt()}"
                floorBreached ->
                    "PROFIT_LOCK_${positionHighWaterPnlPct.toInt()}PCT_HW"
                pnlPct <= -slPct -> "SL"
                timedOut         -> "TIMEOUT_HW${positionHighWaterPnlPct.toInt()}"
                else              -> null
            }

            val modeTag = if (inRunnerMode) "🚀RUN" else "TP${tpPct.toInt()}/SL${slPct.toInt()}"
            statusMessage = "IN: $currentSymbol | PnL: ${"%+.1f".format(pnlPct)}% | HW:+${positionHighWaterPnlPct.toInt()}% | $modeTag | ${if (isLiveMode) "LIVE" else "PAPER"}"

            if (exitReason != null) {
                closeCycle(context, ts, executor, wallet, walletSol, pnlPct, exitReason, solPrice)
            }
            return
        }

        // ── 2. Not in position — cooldown + loss-streak-pause check ───────────
        val now = System.currentTimeMillis()
        if (now < pauseUntilMs) {
            val remainingSec = (pauseUntilMs - now) / 1000
            statusMessage = "🧠 WR brake: ${consecutiveLosses} losses → pausing ${remainingSec}s (relearning)"
            return
        }
        val sinceLastCycle = now - lastCycleEndMs
        if (sinceLastCycle < COOLDOWN_MS) return

        // ── 2a. V5.9.451 — SENTIENCE + SYMBOLIC + PERSONALITY veto stack ──────
        // User: "needs the loop learning and AI assistance symbolic reasoning
        // and sentience etc for more success".
        try {
            // Personality filter — if user told the bot to avoid e.g. memes in chat,
            // skip the cycle entirely while personality says no.
            if (SentienceHooks.shouldFilterByPersonality("CYCLIC", "spot")) {
                statusMessage = "🧠 Personality filter: paused by user intent"
                return
            }
        } catch (_: Throwable) {}

        // ── 3. Pick best token ────────────────────────────────────────────────
        // V5.9.240: Use a lower score floor during bootstrap.
        // V5.9.451: ALSO tighten the floor if we're on a loss streak — the
        // loop-learning + sentience feedback into entry quality.
        val isBootstrapPhase = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() < 0.40
        } catch (_: Exception) { false }
        val cyclicWrPct = if (cycleCount > 0) (winCount * 100.0 / cycleCount.toDouble()) else 0.0
        val cyclicCold = cycleCount >= 3 && (cyclicWrPct < 35.0 || ringBalanceUsd < RING_SIZE_USD * 0.80)
        var effectiveMinScore = when {
            cyclicCold -> COLD_MIN_SCORE_TO_ENTER
            isBootstrapPhase -> MIN_SCORE_TO_ENTER_BOOTSTRAP
            else -> MIN_SCORE_TO_ENTER
        }
        if (consecutiveLosses > 0) {
            // Each recent loss raises the bar +5 (caps at +15). Loop-learning
            // the ring's own outcomes into entry quality.
            effectiveMinScore += (consecutiveLosses * 5).coerceAtMost(15)
        }

        // ── V5.9.885 — BehaviorAI entry-threshold wire-up for CYCLIC lane ──
        // Cyclic is a 'fixed pool, compound through wins' trader — it deploys
        // the full RING_BALANCE_SOL every cycle, NOT a sized percentage. So
        // BehaviorAI sizing doesn't apply here the way it does for the other
        // 7 lanes (V5.9.817-884). What DOES apply is the entry threshold:
        //   - getEntryThresholdMod() returns +15..-20 based on aggression band
        //   - positive value = harder bar (shave eligibility)
        //   - negative value = easier bar (boost eligibility)
        //
        // Cyclic already has TWO loop-learning gates (bootstrap + consecutive
        // losses). BehaviorAI is the THIRD: global cross-lane tilt state.
        // When the bot is on tilt across all lanes, raise Cyclic's bar too
        // so the ring stops cycling into losing setups.
        //
        // Per doctrine #86: bounded modifier, fail-open, no hard veto. The
        // ring will still trade — just demand higher-quality scores during
        // tilt. Min effective floor = 25.0 even at maximum boost.
        try {
            val behaviorMod = com.lifecyclebot.v3.scoring.BehaviorAI.getEntryThresholdMod()
            effectiveMinScore = (effectiveMinScore + behaviorMod).coerceIn(25.0, 90.0)
        } catch (_: Throwable) { /* fail-open per FDG doctrine */ }
        val best = tokens.values
            .filter { ts ->
                val tokenScore = (ts.lastV3Score ?: ts.entryScore.toInt()).toDouble()
                !ts.position.isOpen
                    && ts.lastPrice > 0.0
                    && tokenScore >= effectiveMinScore
                    && ts.mint != currentMint   // don't immediately re-enter same token
                    // V5.9.451 — TokenBlacklist + MemeLossStreakGuard respect.
                    // The ring cycles $500 through trades, it should respect
                    // every guard the main bot uses so a known-bad setup
                    // can't bleed the ring.
                    && !TokenBlacklist.isBlocked(ts.mint)
                    && !MemeLossStreakGuard.isBlocked(ts.mint)
            }
            .maxByOrNull { (it.lastV3Score ?: 0) + it.entryScore.toInt() }
            ?: run {
                statusMessage = "Scanning… (need score ≥${effectiveMinScore.toInt()}${if (cyclicCold) " [COLD]" else if (isBootstrapPhase) " [BOOT]" else ""}${if (consecutiveLosses > 0) " +${consecutiveLosses}L" else ""})"
                return
            }

        // ── 3a. V5.9.451 — ChopFilter (phase/source) — skip the known chop pool.
        try {
            val src = best.source.uppercase()
            val phase = best.phase.lowercase()
            if (src in setOf("DEX_BOOSTED", "DEX_TRENDING") &&
                phase in setOf("early_unknown", "pre_pump", "unknown", "scanning", "idle")) {
                val gate = 50 + ChopFilter.chopPenalty()
                val tokenScore = (best.lastV3Score ?: best.entryScore.toInt())
                if (tokenScore < gate) {
                    statusMessage = "🔪 ChopFilter: ${best.symbol} score=$tokenScore < $gate (src=$src phase=$phase)"
                    return
                }
            }
        } catch (_: Throwable) {}

        // ── 4. Enter cycle ─────────────────────────────────────────────────────
        val sizeSol = if (cyclicCold) (ringBalanceSol * COLD_RING_SIZE_MULT).coerceAtLeast(0.001) else ringBalanceSol

        if (isLiveMode) {
            if (walletSol < sizeSol) {
                statusMessage = "⚠️ Insufficient wallet for cyclic live trade (need ${sizeSol.fmt(3)} SOL)"
                ErrorLogger.warn(TAG, "Live mode but insufficient wallet: $walletSol < $sizeSol")
                return
            }
        }

        val (tpPctEntry, slPctEntry) = adaptiveTpSl()

        // V5.9.1210 — CYCLIC must use the same executable-open finality
        // contract as the other lanes. Restoring paper cyclic in 1207 exposed
        // an old shortcut: CyclicTradeEngine called executor.treasuryBuy()
        // directly, so Executor's wrapper preflight saw no recorded BUY
        // candidate and emitted TREASURY_BUY_BLOCKED_FINALITY /
        // NO_FINAL_BUY_CANDIDATE every cycle. That looks like a frozen bot:
        // scanner alive, candidates flowing, but zero executable buys.
        // Record a CYCLIC/TREASURY BUY candidate and authorize it first, then
        // pass finalityPrechecked=true into treasuryBuy. This preserves FDG /
        // rug / safety finality instead of bypassing it.
        val cyclicScore = (best.lastV3Score ?: best.entryScore.toInt()).coerceIn(0, 100)
        val cyclicFdg = try {
            FinalDecisionGate.evaluate(
                ts = best,
                candidate = com.lifecyclebot.data.CandidateDecision(
                    entryScore = cyclicScore.toDouble(),
                    exitScore = 0.0,
                    phase = best.phase.ifBlank { "cyclic" },
                    signal = "BUY",
                    setupQuality = "B",
                    edgeQuality = "B",
                    finalQuality = "B",
                    edgePhase = "CYCLIC",
                    edgeConfidence = cyclicScore.toDouble().coerceAtLeast(effectiveMinScore),
                    isOptimalEntry = true,
                    edgeVeto = false,
                    shouldTrade = true,
                    finalSignal = "BUY",
                    blockReason = "",
                    qualityPenalty = 1.0,
                    aiConfidence = cyclicScore.toDouble().coerceAtLeast(effectiveMinScore),
                    meta = com.lifecyclebot.data.StrategyMeta(),
                ),
                config = cfg,
                proposedSizeSol = sizeSol,
                brain = executor.brain,
                tradingModeTag = try { ModeSpecificGates.fromTradingMode("CYCLIC") } catch (_: Throwable) { null },
            )
        } catch (e: Throwable) {
            ErrorLogger.warn(TAG, "CYCLIC_FDG_ERROR ${best.symbol}: ${e.message} — fail-open to authorizer")
            null
        }
        try {
            ExecutableOpenGate.recordFdg(
                mint = best.mint,
                symbol = best.symbol,
                lane = "CYCLIC",
                canExecute = cyclicFdg?.canExecute() ?: true,
                reason = cyclicFdg?.blockReason,
                signal = "BUY",
                rugScore = best.safety.rugcheckScore,
                safetyTier = best.safety.tier.name,
                liquidityUsd = best.lastLiquidityUsd,
                hardNoReasons = best.safety.hardBlockReasons,
            )
        } catch (_: Throwable) {}
        // V5.9.1227 — cyclic owns its own execution book/lane. Borrowing
        // TREASURY made the ring inherit treasury cooldown/fatality baggage and
        // UI showed FINALITY_EXEC_OPEN_BLOCKED_COOLDOWN_* instead of behaving
        // like an independent compounding lane.
        try { LaneExecutionCoordinator.canRequestExecution(best.mint, "CYCLIC") } catch (_: Throwable) {}
        val cyclicAuth = TradeAuthorizer.authorize(
            mint = best.mint,
            symbol = best.symbol,
            score = cyclicScore,
            confidence = cyclicScore.toDouble().coerceAtLeast(effectiveMinScore),
            quality = "B",
            isPaperMode = !isLiveMode,
            requestedBook = TradeAuthorizer.ExecutionBook.CYCLIC,
            rugcheckScore = best.safety.rugcheckScore.takeIf { it >= 0 } ?: 100,
            liquidity = best.lastLiquidityUsd,
            isBanned = BannedTokens.isBanned(best.mint),
        )
        if (!cyclicAuth.isExecutable()) {
            statusMessage = "⏸️ Cyclic finality blocked ${best.symbol}: ${cyclicAuth.reason.take(60)}"
            ErrorLogger.info(TAG, "CYCLIC_FINALITY_BLOCKED ${best.symbol} | ${cyclicAuth.reason}")
            return
        }
        val cyclicAttemptId = ExecutableOpenGate.recentAllowedAttemptId(best.mint, "CYCLIC") ?: cyclicAuth.attemptId
        val entered = executor.treasuryBuy(
            ts          = best,
            sizeSol     = sizeSol,
            walletSol   = walletSol,
            takeProfitPct = tpPctEntry,
            stopLossPct   = slPctEntry,
            wallet      = if (isLiveMode) wallet else null,
            isPaper     = !isLiveMode,
            finalityPrechecked = true,
            attemptId = cyclicAttemptId,
        )

        if (entered) {
            // V5.9.1227 — treasuryBuy is reused only as the low-level spot buy
            // wrapper. Re-stamp the opened position as CYCLIC so exits, UI,
            // journal attribution, and TradeAuthorizer release don't treat the
            // ring as Treasury.
            try {
                best.position.tradingMode = "CYCLIC"
                best.position.tradingModeEmoji = "🔁"
                best.position.isTreasuryPosition = false
                best.position.treasuryTakeProfit = tpPctEntry
                best.position.treasuryStopLoss = slPctEntry
            } catch (_: Throwable) {}
            isInPosition  = true
            currentMint   = best.mint
            currentSymbol = best.symbol
            entryPriceSol = best.lastPrice
            entrySizeSol  = sizeSol
            entryTimeMs   = System.currentTimeMillis()
            isRunning     = true
            statusMessage = "⏳ ${best.symbol} | Size: ${sizeSol.fmt(3)} SOL | TP${tpPctEntry.toInt()}/SL${slPctEntry.toInt()} | ${if (isLiveMode) "🔴 LIVE" else "📄 PAPER"}"
            positionHighWaterPnlPct = 0.0  // V5.9.696: reset high water on new entry
            ErrorLogger.info(TAG, "Cycle #${cycleCount + 1} entered: ${best.symbol} | $sizeSol SOL | live=$isLiveMode | score=${best.lastV3Score ?: 0} | TP=${tpPctEntry.toInt()}% SL=${slPctEntry.toInt()}%")
            // V5.9.451 — journal BUY via V3JournalRecorder so the cycle
            // shows in the user's Journal alongside main-bot trades and
            // feeds ScoreExpectancyTracker/HoldDurationTracker/ExitReasonTracker.
            try {
                V3JournalRecorder.recordOpen(
                    symbol = best.symbol, mint = best.mint,
                    entryPrice = best.lastPrice, sizeSol = sizeSol,
                    isPaper = !isLiveMode, layer = "CYCLIC",
                    entryScore = best.lastV3Score ?: best.entryScore.toInt(),
                    entryReason = "RING_ENTRY_TP${tpPctEntry.toInt()}SL${slPctEntry.toInt()}",
                )
            } catch (_: Throwable) {}
            save(context)
        }
    }

    /**
     * V5.9.451 — adaptive TP/SL driven by learning progress + loss streak.
     *
     * Bootstrap (<40% learning): patient TP=12, tight SL=6 — ring only
     *   pulls when there's clear signal, doesn't bleed on noise.
     * Mature: default 15/5.
     * On loss streak (≥2 consecutive losses): widen TP to 20, tighten SL
     *   to 3 — hunt fatter moves, exit mediocre setups faster.
     */
    private fun adaptiveTpSl(): Pair<Double, Double> {
        val lp = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        } catch (_: Throwable) { 1.0 }
        // Compounding profile: bootstrap still needs runners. Use TP as runner
        // activation, not scalp exit. Loss streak tightens SL and demands even
        // fatter continuation before the ring declares victory.
        val (baseTp, baseSl) = if (lp < 0.40) Pair(50.0, 10.0) else Pair(DEFAULT_TP_PCT, DEFAULT_SL_PCT)
        return when {
            consecutiveLosses >= 2 -> Pair(80.0, 6.0)
            consecutiveLosses == 1 -> Pair(maxOf(baseTp, 65.0), minOf(baseSl, 8.0))
            else -> Pair(baseTp, baseSl)
        }
    }

    // ── Close a cycle ─────────────────────────────────────────────────────────
    private fun closeCycle(
        context: Context,
        ts: TokenState,
        executor: Executor,
        wallet: com.lifecyclebot.network.SolanaWallet?,
        walletSol: Double,
        pnlPct: Double,
        reason: String,
        solPrice: Double
    ) {
        val deployedSizeSol = entrySizeSol.takeIf { it > 0.0 } ?: ringBalanceSol
        val pnlSol = deployedSizeSol * (pnlPct / 100.0)

        // Execute sell
        if (isLiveMode) {
            executor.requestSell(ts, "CYCLIC_$reason", wallet, walletSol)
        } else {
            executor.paperSell(ts, "CYCLIC_$reason")
        }

        // Update ring
        ringBalanceSol = (ringBalanceSol + pnlSol).coerceAtLeast(0.001)
        ringBalanceUsd = ringBalanceSol * solPrice
        totalPnlSol   += pnlSol
        cycleCount++
        val won = pnlPct > 0
        if (won) {
            winCount++
            consecutiveLosses = 0    // V5.9.451 — reset streak on win
        } else {
            lossCount++
            consecutiveLosses++
            // V5.9.451 — engage pause after N consecutive losses (loop-learning brake)
            if (consecutiveLosses >= LOSS_STREAK_BREAK_COUNT) {
                pauseUntilMs = System.currentTimeMillis() + LOSS_STREAK_BREAK_PAUSE_MS
                ErrorLogger.warn(TAG, "🧠 Loss-streak brake ENGAGED: $consecutiveLosses consecutive losses — pausing ${LOSS_STREAK_BREAK_PAUSE_MS / 60000}min")
            }
        }

        // V5.9.451 — journal SELL via V3JournalRecorder so the cycle close
        // shows in the user's Journal and feeds outcome-attribution trackers
        // (ScoreExpectancyTracker / HoldDurationTracker / ExitReasonTracker).
        try {
            val holdMins = if (entryTimeMs > 0) (System.currentTimeMillis() - entryTimeMs) / 60_000L else 0L
            V3JournalRecorder.recordClose(
                symbol = ts.symbol, mint = ts.mint,
                entryPrice = entryPriceSol, exitPrice = ts.lastPrice,
                sizeSol = deployedSizeSol, pnlPct = pnlPct, pnlSol = pnlSol,
                isPaper = !isLiveMode, layer = "CYCLIC",
                exitReason = reason,
                entryScore = ts.lastV3Score ?: ts.entryScore.toInt(),
                holdMinutes = holdMins,
            )
        } catch (_: Throwable) {}

        try { TradeAuthorizer.releasePosition(ts.mint, "CYCLIC_$reason", TradeAuthorizer.ExecutionBook.CYCLIC) } catch (_: Throwable) {}
        try { LaneExecutionCoordinator.releaseIfPrimary(ts.mint, "CYCLIC", "CYCLIC_$reason") } catch (_: Throwable) {}

        lastCycleEndMs = System.currentTimeMillis()
        isInPosition   = false
        currentMint    = ""
        currentSymbol  = ""
        entryPriceSol  = 0.0
        entrySizeSol   = 0.0
        entryTimeMs    = 0L

        val winRate = if (cycleCount > 0) (winCount.toDouble() / cycleCount * 100).toInt() else 0
        statusMessage = "✅ $reason | PnL: ${"%+.1f".format(pnlPct)}% | Ring: \$${ringBalanceUsd.toInt()} | WR: $winRate%"
        ErrorLogger.info(TAG, "Cycle closed [$reason] | pnl=${"%+.2f".format(pnlPct)}% | ring=${ringBalanceSol.fmt(3)} SOL (\$${ringBalanceUsd.toInt()}) | total cycles=$cycleCount")
        save(context)
    }

    private fun abandonCycle(context: Context, reason: String, solPrice: Double) {
        isInPosition  = false
        currentMint   = ""
        currentSymbol = ""
        entryPriceSol = 0.0
        entrySizeSol  = 0.0
        entryTimeMs   = 0L
        lastCycleEndMs = System.currentTimeMillis()
        statusMessage = "⚠️ Abandoned: $reason"
        ErrorLogger.warn(TAG, "Cycle abandoned: $reason")
        save(context)
    }

    // ── Persistence ───────────────────────────────────────────────────────────
    fun save(context: Context) {
        prefs(context).edit()
            .putFloat("ring_balance_sol", ringBalanceSol.toFloat())
            .putFloat("ring_balance_usd", ringBalanceUsd.toFloat())
            .putInt("cycle_count", cycleCount)
            .putFloat("total_pnl_sol", totalPnlSol.toFloat())
            .putInt("win_count", winCount)
            .putInt("loss_count", lossCount)
            .apply()
    }

    fun reset(context: Context) {
        val solPrice = WalletManager.lastKnownSolPrice.takeIf { it > 0.0 } ?: 150.0
        ringBalanceSol = RING_SIZE_USD / solPrice
        ringBalanceUsd = RING_SIZE_USD
        cycleCount    = 0
        totalPnlSol   = 0.0
        winCount      = 0
        lossCount     = 0
        isInPosition  = false
        currentMint   = ""
        currentSymbol = ""
        entryPriceSol = 0.0
        entrySizeSol  = 0.0
        entryTimeMs   = 0L
        lastCycleEndMs = 0L
        statusMessage = "Reset"
        save(context)
        ErrorLogger.info(TAG, "Ring reset to \$500 USD = ${ringBalanceSol.fmt(3)} SOL")
    }

    fun getStats(): Map<String, Any> = mapOf(
        "ring_usd"     to ringBalanceUsd,
        "ring_sol"     to ringBalanceSol,
        "cycles"       to cycleCount,
        "wins"         to winCount,
        "losses"       to lossCount,
        "total_pnl_sol" to totalPnlSol,
        "win_rate"     to if (cycleCount > 0) winCount.toDouble() / cycleCount else 0.0,
        "in_position"  to isInPosition,
        "live_mode"    to isLiveMode,
        "status"       to statusMessage,
        "target_cycles" to "15-20",
        "target_win_pct" to "47-66",
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun Double.fmt(d: Int) = "%.${d}f".format(this)
}
