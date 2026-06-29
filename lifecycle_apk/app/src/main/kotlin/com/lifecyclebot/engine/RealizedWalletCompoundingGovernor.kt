package com.lifecyclebot.engine

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import kotlin.math.abs

/**
 * V5.0.4511 — REALIZED WALLET COMPOUNDING GOVERNOR.
 *
 * The daily 2x-5x goal must be driven by wallet-realized / StrategyTruthLedger
 * money truth, never raw journal PnL, duplicate terminal rows, recovered inventory,
 * or pct-only EV. This object is deliberately cached/background-refreshed: executor
 * hot paths read the latest multiplier in O(1) and merely request an async refresh
 * when stale.
 */
object RealizedWalletCompoundingGovernor {
    data class Snapshot(
        val multiplier: Double,
        val cleanPnlSol: Double,
        val walletSol: Double,
        val wrPct: Double,
        val profitFactor: Double,
        val trades: Int,
        val reason: String,
        val refreshedAtMs: Long,
    )

    private const val REFRESH_TTL_MS = 15_000L
    @Volatile private var cached = Snapshot(1.0, 0.0, 0.0, 0.0, 0.0, 0, "bootstrap", 0L)
    private val refreshInFlight = AtomicBoolean(false)

    fun sizeMultiplier(): Double {
        refreshAsyncIfStale()
        return cached.multiplier
    }

    fun statusLine(): String {
        refreshAsyncIfStale()
        val s = cached
        return "RealizedWalletCompounding: mult=${s.multiplier.fmt2()} clean=${s.cleanPnlSol.fmt4()} SOL wallet=${s.walletSol.fmt4()} SOL WR=${s.wrPct.fmt1()}% PF=${s.profitFactor.fmt2()} n=${s.trades} reason=${s.reason}"
    }

    private fun Double.fmt1(): String = String.format(Locale.US, "%.1f", this)
    private fun Double.fmt2(): String = String.format(Locale.US, "%.2f", this)
    private fun Double.fmt4(): String = String.format(Locale.US, "%.4f", this)

    fun snapshot(): Snapshot {
        refreshAsyncIfStale()
        return cached
    }

    private fun refreshAsyncIfStale(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - cached.refreshedAtMs < REFRESH_TTL_MS) return
        if (!refreshInFlight.compareAndSet(false, true)) return
        GlobalScope.launch(AppDispatchers.sideEffect) {
            try { cached = computeSnapshot(System.currentTimeMillis()) }
            catch (_: Throwable) {}
            finally { refreshInFlight.set(false) }
        }
    }

    private fun computeSnapshot(nowMs: Long): Snapshot {
        val raw = try { TradeHistoryStore.getRecentValidClosedTradesRaw(limit = 750, includePartials = true) } catch (_: Throwable) { emptyList() }
        val clean = try { StrategyTruthLedger.clean(raw, 750).rows } catch (_: Throwable) { emptyList() }
        val terminal = clean.filter { it.side.equals("SELL", true) }
        val trades = terminal.size
        val wins = terminal.count { (it.netPnlSol.takeIf { v -> v != 0.0 } ?: it.pnlSol) > 0.0 }
        val losses = terminal.count { (it.netPnlSol.takeIf { v -> v != 0.0 } ?: it.pnlSol) < 0.0 }
        val pnl = terminal.sumOf { it.netPnlSol.takeIf { v -> v != 0.0 } ?: it.pnlSol }
        val grossWin = terminal.sumOf { maxOf(0.0, it.netPnlSol.takeIf { v -> v != 0.0 } ?: it.pnlSol) }
        val grossLoss = abs(terminal.sumOf { minOf(0.0, it.netPnlSol.takeIf { v -> v != 0.0 } ?: it.pnlSol) })
        val pf = when {
            grossWin <= 0.0 && grossLoss <= 0.0 -> 0.0
            grossLoss <= 0.0000001 -> 9.99
            else -> grossWin / grossLoss
        }
        val wr = if (wins + losses > 0) wins * 100.0 / (wins + losses) else 0.0
        val wallet = try { BotService.status.walletSol } catch (_: Throwable) { 0.0 }
        val base = wallet.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val gainRatio = pnl / base
        val multReason = when {
            trades < 20 -> 1.00 to "bootstrap_under_20"
            pnl <= 0.0 || wr < 20.0 || pf < 0.95 -> 0.55 to "defensive_clean_negative_or_low_wr"
            gainRatio >= 2.0 && wr >= 35.0 && pf >= 2.0 -> 2.25 to "two_x_plus_compound_unlock"
            gainRatio >= 1.0 && wr >= 32.0 && pf >= 1.6 -> 1.85 to "one_x_compound_unlock"
            gainRatio >= 0.75 && wr >= 30.0 && pf >= 1.4 -> 1.55 to "seventyfive_pct_growth_unlock"
            gainRatio >= 0.30 && wr >= 28.0 && pf >= 1.25 -> 1.30 to "thirty_pct_growth_unlock"
            pnl > 0.0 && wr >= 25.0 && pf >= 1.15 -> 1.12 to "positive_clean_edge"
            else -> 0.75 to "cautious_uncertain_edge"
        }
        return Snapshot(
            multiplier = multReason.first.coerceIn(0.45, 2.25),
            cleanPnlSol = pnl,
            walletSol = wallet,
            wrPct = wr,
            profitFactor = pf,
            trades = trades,
            reason = multReason.second,
            refreshedAtMs = nowMs,
        )
    }
}
