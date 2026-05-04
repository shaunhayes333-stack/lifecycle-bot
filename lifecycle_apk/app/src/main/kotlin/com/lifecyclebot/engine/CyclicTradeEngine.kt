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
    private const val DEFAULT_TP_PCT = 15.0
    private const val DEFAULT_SL_PCT = 5.0
    private const val MIN_SCORE_TO_ENTER = 55.0
    // V5.9.240: During bootstrap (<40% learning) lower the score floor so the
    // ring engine actually trades while FluidLearningAI is still calibrating.
    // Tokens don't have a reliable lastV3Score yet at that stage — entryScore
    // (raw signal) is used as the proxy. 30 is still a real signal, not noise.
    private const val MIN_SCORE_TO_ENTER_BOOTSTRAP = 30.0
    private const val COOLDOWN_MS = 30_000L    // 30s between cycles
    private const val MAX_HOLD_MS = 90 * 60 * 1000L  // 90 min max hold

    // V5.9.451 — loss-streak back-off window (user: "needs loop learning
    // and AI assistance symbolic reasoning and sentience etc for more
    // success"). After 3 consecutive losses we pause 5 min and tighten
    // SL / widen TP until the next win.
    private const val LOSS_STREAK_BREAK_COUNT    = 3
    private const val LOSS_STREAK_BREAK_PAUSE_MS = 5 * 60 * 1000L
    @Volatile private var consecutiveLosses: Int = 0
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
        // Paper is always allowed; live requires explicit opt-in or $5K treasury
        isLiveMode = (cfg.cyclicTradeEnabled && cfg.cyclicTradeLiveEnabled) ||
                     (cfg.cyclicTradeEnabled && treasuryUsd >= 5_000.0)

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
            val timedOut = holdMs >= MAX_HOLD_MS
            val hitTP    = pnlPct >= tpPct
            val hitSL    = pnlPct <= -slPct

            statusMessage = "IN: $currentSymbol | PnL: ${"%+.1f".format(pnlPct)}% | TP${tpPct.toInt()}/SL${slPct.toInt()} | ${if (isLiveMode) "LIVE" else "PAPER"}"

            when {
                hitTP -> closeCycle(context, ts, executor, wallet, walletSol, pnlPct, "TP", solPrice)
                hitSL -> closeCycle(context, ts, executor, wallet, walletSol, pnlPct, "SL", solPrice)
                timedOut -> closeCycle(context, ts, executor, wallet, walletSol, pnlPct, "TIMEOUT", solPrice)
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
        var effectiveMinScore = if (isBootstrapPhase) MIN_SCORE_TO_ENTER_BOOTSTRAP else MIN_SCORE_TO_ENTER
        if (consecutiveLosses > 0) {
            // Each recent loss raises the bar +5 (caps at +15). Loop-learning
            // the ring's own outcomes into entry quality.
            effectiveMinScore += (consecutiveLosses * 5).coerceAtMost(15)
        }
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
                statusMessage = "Scanning… (need score ≥${effectiveMinScore.toInt()}${if (isBootstrapPhase) " [BOOT]" else ""}${if (consecutiveLosses > 0) " +${consecutiveLosses}L" else ""})"
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
        val sizeSol = ringBalanceSol

        if (isLiveMode) {
            if (walletSol < sizeSol) {
                statusMessage = "⚠️ Insufficient wallet for cyclic live trade (need ${sizeSol.fmt(3)} SOL)"
                ErrorLogger.warn(TAG, "Live mode but insufficient wallet: $walletSol < $sizeSol")
                return
            }
        }

        val (tpPctEntry, slPctEntry) = adaptiveTpSl()
        val entered = executor.treasuryBuy(
            ts          = best,
            sizeSol     = sizeSol,
            walletSol   = walletSol,
            takeProfitPct = tpPctEntry,
            stopLossPct   = slPctEntry,
            wallet      = if (isLiveMode) wallet else null,
            isPaper     = !isLiveMode
        )

        if (entered) {
            isInPosition  = true
            currentMint   = best.mint
            currentSymbol = best.symbol
            entryPriceSol = best.lastPrice
            entryTimeMs   = System.currentTimeMillis()
            isRunning     = true
            statusMessage = "⏳ ${best.symbol} | Size: ${sizeSol.fmt(3)} SOL | TP${tpPctEntry.toInt()}/SL${slPctEntry.toInt()} | ${if (isLiveMode) "🔴 LIVE" else "📄 PAPER"}"
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
        val (baseTp, baseSl) = if (lp < 0.40) Pair(12.0, 6.0) else Pair(DEFAULT_TP_PCT, DEFAULT_SL_PCT)
        return if (consecutiveLosses >= 2) Pair(20.0, 3.0) else Pair(baseTp, baseSl)
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
        val pnlSol = ringBalanceSol * (pnlPct / 100.0)

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
                sizeSol = ringBalanceSol, pnlPct = pnlPct, pnlSol = pnlSol,
                isPaper = !isLiveMode, layer = "CYCLIC",
                exitReason = reason,
                entryScore = ts.lastV3Score ?: ts.entryScore.toInt(),
                holdMinutes = holdMins,
            )
        } catch (_: Throwable) {}

        lastCycleEndMs = System.currentTimeMillis()
        isInPosition   = false
        currentMint    = ""
        currentSymbol  = ""
        entryPriceSol  = 0.0
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
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun Double.fmt(d: Int) = "%.${d}f".format(this)
}
