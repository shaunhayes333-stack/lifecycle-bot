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
    private const val DEFAULT_TP_PCT = 15.0
    private const val DEFAULT_SL_PCT = 5.0
    private const val MIN_SCORE_TO_ENTER = 55.0
    private const val COOLDOWN_MS = 30_000L    // 30s between cycles
    private const val MAX_HOLD_MS = 90 * 60 * 1000L  // 90 min max hold

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
        if (!cfg.cyclicTradeEnabled) return
        if (!enabled.get()) { enabled.set(true) }

        // Determine live vs paper
        val treasuryUsd = TreasuryManager.treasuryUsd
        val solPrice = WalletManager.lastKnownSolPrice.takeIf { it > 0.0 } ?: 150.0
        isLiveMode = cfg.cyclicTradeLiveEnabled || treasuryUsd >= 5_000.0

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

            val holdMs = System.currentTimeMillis() - entryTimeMs
            val timedOut = holdMs >= MAX_HOLD_MS
            val hitTP    = pnlPct >= DEFAULT_TP_PCT
            val hitSL    = pnlPct <= -DEFAULT_SL_PCT

            statusMessage = "IN: $currentSymbol | PnL: ${"%+.1f".format(pnlPct)}% | ${if (isLiveMode) "LIVE" else "PAPER"}"

            when {
                hitTP -> closeCycle(context, ts, executor, wallet, walletSol, pnlPct, "TP", solPrice)
                hitSL -> closeCycle(context, ts, executor, wallet, walletSol, pnlPct, "SL", solPrice)
                timedOut -> closeCycle(context, ts, executor, wallet, walletSol, pnlPct, "TIMEOUT", solPrice)
            }
            return
        }

        // ── 2. Not in position — cooldown check ───────────────────────────────
        val sinceLastCycle = System.currentTimeMillis() - lastCycleEndMs
        if (sinceLastCycle < COOLDOWN_MS) return

        // ── 3. Pick best token ─────────────────────────────────────────────────
        val best = tokens.values
            .filter { ts ->
                !ts.position.isOpen
                    && ts.lastPrice > 0.0
                    && (ts.lastV3Score ?: ts.entryScore.toInt()) >= MIN_SCORE_TO_ENTER.toInt()
                    && ts.mint != currentMint   // don't immediately re-enter same token
            }
            .maxByOrNull { (it.lastV3Score ?: 0) + it.entryScore.toInt() }
            ?: run {
                statusMessage = "Scanning… (need score ≥${MIN_SCORE_TO_ENTER.toInt()})"
                return
            }

        // ── 4. Enter cycle ─────────────────────────────────────────────────────
        val sizeSol = ringBalanceSol

        if (isLiveMode) {
            if (walletSol < sizeSol) {
                statusMessage = "⚠️ Insufficient wallet for cyclic live trade (need ${sizeSol.fmt(3)} SOL)"
                ErrorLogger.warn(TAG, "Live mode but insufficient wallet: $walletSol < $sizeSol")
                return
            }
        }

        val entered = executor.treasuryBuy(
            ts          = best,
            sizeSol     = sizeSol,
            walletSol   = walletSol,
            takeProfitPct = DEFAULT_TP_PCT,
            stopLossPct   = DEFAULT_SL_PCT,
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
            statusMessage = "⏳ ${best.symbol} | Size: ${sizeSol.fmt(3)} SOL | ${if (isLiveMode) "🔴 LIVE" else "📄 PAPER"}"
            ErrorLogger.info(TAG, "Cycle #${cycleCount + 1} entered: ${best.symbol} | $sizeSol SOL | live=$isLiveMode | score=${best.lastV3Score ?: 0}")
            save(context)
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
        if (pnlPct > 0) winCount++ else lossCount++

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
