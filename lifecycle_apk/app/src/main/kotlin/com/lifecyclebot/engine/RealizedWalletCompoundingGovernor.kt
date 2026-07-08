package com.lifecyclebot.engine

import com.lifecyclebot.util.AppDispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import java.util.Calendar
import java.util.TimeZone
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
        val dayStartWalletSol: Double,
        val dayHighWalletSol: Double,
        val dayPnlSol: Double,
        val dayProgressX: Double,
        val drawdownFromDayHighPct: Double,
        val trustedOpenUnrealizedSol: Double = 0.0,
        val trustedOpenRunnerCount: Int = 0,
        val equityPressureX: Double = 1.0,
        val refreshedAtMs: Long,
        val strategyCleanPnlSol: Double = 0.0,
        val moneyMode: String = "unknown",
    )

    private const val REFRESH_TTL_MS = 15_000L
    @Volatile private var cached = Snapshot(1.0, 0.0, 0.0, 0.0, 0.0, 0, "bootstrap", 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0, 1.0, 0L)
    @Volatile private var dayKey: String = ""
    @Volatile private var dayStartWalletSol: Double = 0.0
    @Volatile private var dayHighWalletSol: Double = 0.0
    private val refreshInFlight = AtomicBoolean(false)

    fun sizeMultiplier(): Double {
        refreshAsyncIfStale()
        return cached.multiplier
    }

    /**
     * V5.0.6075 — PER-LANE DEFENSIVE EXEMPTION. The global defensive
     * multiplier (defensive_clean_negative_or_low_wr 0.55, cautious 0.75)
     * is driven by BLENDED wallet stats and was suppressing individually
     * net-positive lanes (operator P0: TREASURY +SOL clamped). Compounding
     * boosts (>=1.0) still apply globally; defensive squeezes (<1.0) are
     * lifted back to 1.0 for lanes that are net-positive on a real sample.
     */
    fun sizeMultiplierForLane(lane: String?): Double {
        val g = sizeMultiplier()
        if (g >= 1.0) return g
        // V5.0.6110 — SMALL-WALLLET DEATH-SPIRAL BREAK.
        // When wallet < 1 SOL AND no open positions, the defensive squeeze
        // creates a death spiral: can't trade → can't recover → stay defensive
        // → smaller sizes → can't trade. With zero open risk, there is nothing
        // to defend — lift to 1.0 so the bot can actually enter trades and
        // start recovering.
        val snap = cached
        if (snap.walletSol < 1.0 && snap.trustedOpenRunnerCount == 0 && snap.trustedOpenUnrealizedSol == 0.0) {
            try { PipelineHealthCollector.labelInc("WALLET_COMPOUND_SMALL_WALLET_DEATH_SPIRAL_BREAK_6110") } catch (_: Throwable) {}
            return 1.0
        }
        return try {
            val adj = LiveStrategyTuner.adjustment(lane)
            // V5.0.6201 — relaxed positive-lane exemption threshold from
            // totalSolPnl > 0.0 to > -0.05. Report 2026-07-08 19:54 showed
            // MOONSHOT LiveStrategyTuner PnL=+0.042 (barely positive → exempt)
            // but BLUECHIP=-0.053 and QUALITY=-0.123 stayed clamped to 0.55x
            // despite BLUECHIP's raw journal EV=+53% and n=101 sample size.
            // Widening the exemption band lets near-breakeven proven lanes
            // trade at full compounded size so they can PROVE the +EV in live
            // and pull themselves out of the defensive spiral.
            if (adj.trades >= 5 && adj.totalSolPnl > -0.05) {
                try { PipelineHealthCollector.labelInc("WALLET_COMPOUND_DEFENSIVE_LANE_EXEMPT_6075") } catch (_: Throwable) {}
                1.0
            } else g
        } catch (_: Throwable) { g }
    }

    fun statusLine(): String {
        refreshAsyncIfStale()
        val s = cached
        return "RealizedWalletCompounding: mult=${s.multiplier.fmt2()} mode=${s.moneyMode} moneyRows=${s.cleanPnlSol.fmt4()} SOL strategyClean=${s.strategyCleanPnlSol.fmt4()} SOL wallet=${s.walletSol.fmt4()} SOL openTrusted=${s.trustedOpenUnrealizedSol.fmt4()} SOL runners=${s.trustedOpenRunnerCount} equityPressure=${s.equityPressureX.fmt2()}x day=${s.dayPnlSol.fmt4()} SOL progress=${s.dayProgressX.fmt2()}x high=${s.dayHighWalletSol.fmt4()} ddHigh=${s.drawdownFromDayHighPct.fmt1()}% WR=${s.wrPct.fmt1()}% PF=${s.profitFactor.fmt2()} n=${s.trades} reason=${s.reason}"
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

    private fun brisbaneDayStartMs(nowMs: Long): Long {
        val tz = TimeZone.getTimeZone("Australia/Brisbane")
        return Calendar.getInstance(tz).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun brisbaneDayKey(nowMs: Long): String {
        val tz = TimeZone.getTimeZone("Australia/Brisbane")
        val c = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        return "%04d-%02d-%02d".format(Locale.US, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private data class TrustedOpenEquity(val pnlSol: Double, val runners: Int, val maxPnlPct: Double)

    private fun trustedOpenLiveEquity(): TrustedOpenEquity {
        return try {
            val paperModeNow6072 = try { com.lifecyclebot.engine.GlobalTradeRegistry.isPaperMode } catch (_: Throwable) { false }
            val rows = try { BotService.status.tokens.values.toList() } catch (_: Throwable) { emptyList() }
            var pnlSol = 0.0
            var runners = 0
            var maxPct = 0.0
            rows.forEach { ts ->
                val p = ts.position
                // V5.0.6072 — PAPER WALLET COMPOUNDING PARITY. Operator: paper wallet
                // must have the same compounding targets as live so learning maturity
                // transfers cleanly. Previously paper positions were excluded from the
                // trusted-open-equity total, which meant runner-pressure compounding
                // tiers (trusted_open_equity_two_x_pressure_6028, etc.) never fired in
                // paper. Result: paper had two +100%+ runners simultaneously but the
                // sizing governor still read equityPressureX=1.00. Now in paper mode
                // we count paper positions; in live mode we keep counting live only.
                val includeForMode = if (paperModeNow6072) p.isPaperPosition else !p.isPaperPosition
                if (!p.isOpen || !includeForMode || p.costSol <= 0.0 || p.qtyToken <= 0.0) return@forEach
                val verdict = try { OpenPnlSanity.inspect(ts, context = "RealizedWalletCompoundingGovernor.open_equity_6072", emit = false) } catch (_: Throwable) { OpenPnlSanity.Verdict(false, reason = "INSPECT_THROW") }
                if (!verdict.ok || verdict.pnlPct <= 0.0) return@forEach
                val currentPrice = ts.ref.takeIf { it.isFinite() && it > 0.0 } ?: return@forEach
                val currentValueSol = (p.qtyToken * currentPrice).takeIf { it.isFinite() && it > 0.0 } ?: return@forEach
                val openPnlSol = (currentValueSol - p.costSol).coerceAtLeast(0.0)
                if (openPnlSol <= 0.0) return@forEach
                pnlSol += openPnlSol
                runners += 1
                maxPct = maxOf(maxPct, verdict.pnlPct)
            }
            TrustedOpenEquity(pnlSol, runners, maxPct)
        } catch (_: Throwable) { TrustedOpenEquity(0.0, 0, 0.0) }
    }

    private fun computeSnapshot(nowMs: Long): Snapshot {
        val raw = try { TradeHistoryStore.getRecentValidClosedTradesRaw(limit = 750, includePartials = true) } catch (_: Throwable) { emptyList() }
        // V5.0.6081 — REALIZED MONEY ROWS, MODE-LOCAL.
        // StrategyTruthLedger intentionally excludes non-terminal partials for final
        // strategy grading. This governor is different: it controls wallet growth,
        // so harvested PARTIAL_SELL SOL is real money and must compound in the
        // current runtime mode only. Never blend paper/live, and never use recovery
        // inventory or bad-basis rows as compounding evidence.
        val paperRuntime6081 = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { GlobalTradeRegistry.isPaperMode }
        val mode6081 = if (paperRuntime6081) "paper" else "live"
        val seenMoneyKeys6081 = LinkedHashSet<String>()
        val terminal = raw.asSequence()
            .filter { it.mode.equals(mode6081, true) }
            .filter { it.side.equals("SELL", true) || it.side.equals("PARTIAL_SELL", true) }
            .filter { !StrategyTruthLedger.isRecoveryInventory(it) }
            .filter { StrategyTruthLedger.hasValidEntryBasis(it) }
            .filter { LearningPnlSanitizer.inspectTrade(it, "RealizedWalletCompounding.moneyRows6081", emit = false).ok }
            .filter {
                val key = listOf(
                    it.positionId.ifBlank { it.mint },
                    it.side.uppercase(),
                    it.ts.toString(),
                    it.reason,
                    it.sig
                ).joinToString("|")
                seenMoneyKeys6081.add(key)
            }
            .toList()
        val strategyCleanRows6128 = try {
            StrategyTruthLedger.clean(raw, 750).rows.filter { it.mode.equals(mode6081, true) }
        } catch (_: Throwable) { emptyList() }
        val strategyCleanPnl6128 = strategyCleanRows6128.sumOf { it.netPnlSol.takeIf { v -> v != 0.0 } ?: it.pnlSol }
        val strategyCleanWins6132 = strategyCleanRows6128.count { (it.netPnlSol.takeIf { v -> v != 0.0 } ?: it.pnlSol) > 0.0 }
        val strategyCleanLosses6132 = strategyCleanRows6128.count { (it.netPnlSol.takeIf { v -> v != 0.0 } ?: it.pnlSol) < 0.0 }
        val strategyCleanGrossWin6132 = strategyCleanRows6128.sumOf { maxOf(0.0, it.netPnlSol.takeIf { v -> v != 0.0 } ?: it.pnlSol) }
        val strategyCleanGrossLoss6132 = abs(strategyCleanRows6128.sumOf { minOf(0.0, it.netPnlSol.takeIf { v -> v != 0.0 } ?: it.pnlSol) })
        val strategyCleanPf6132 = when {
            strategyCleanGrossWin6132 <= 0.0 && strategyCleanGrossLoss6132 <= 0.0 -> 0.0
            strategyCleanGrossLoss6132 <= 0.0000001 -> 9.99
            else -> strategyCleanGrossWin6132 / strategyCleanGrossLoss6132
        }
        val strategyCleanWr6132 = if (strategyCleanWins6132 + strategyCleanLosses6132 > 0) strategyCleanWins6132 * 100.0 / (strategyCleanWins6132 + strategyCleanLosses6132) else 0.0
        val strategyTruthMature6132 = strategyCleanRows6128.size >= 20
        val strategyTruthNegative6128 = strategyTruthMature6132 && (strategyCleanPnl6128 <= 0.0 || strategyCleanPf6132 < 0.95)
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
        // V5.0.6132 — compounding unlocks must not trust partial-inclusive money
        // rows when StrategyTruth clean terminal evidence is mature and worse. Keep
        // money rows for actual harvested-wallet accounting, but use the stricter
        // strategy-clean side for size unlock decisions so raw partial/audit rows
        // cannot fake a green compounding regime.
        val decisionPnl6132 = if (strategyTruthMature6132) minOf(pnl, strategyCleanPnl6128) else pnl
        val decisionWr6132 = if (strategyTruthMature6132) minOf(wr, strategyCleanWr6132) else wr
        val decisionPf6132 = if (strategyTruthMature6132) minOf(pf, strategyCleanPf6132) else pf
        val wallet = try {
            // V5.0.6072 — PAPER WALLET COMPOUNDING PARITY. In paper mode read
            // the simulated paper wallet, not the on-chain SOL balance. Paper
            // and live must use symmetric wallet math or the compounding tier
            // thresholds trigger on the wrong base.
            if (paperRuntime6081) {
                BotService.status.paperWalletSol.takeIf { it.isFinite() && it > 0.0 }
                    ?: BotService.status.walletSol
            } else {
                BotService.status.walletSol
            }
        } catch (_: Throwable) { 0.0 }
        val openEquity6028 = trustedOpenLiveEquity()
        val equityBase6028 = wallet.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val equityPressureX6028 = ((wallet + openEquity6028.pnlSol) / equityBase6028).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val dayStartMs = brisbaneDayStartMs(nowMs)
        val todayTerminal = terminal.filter { it.ts >= dayStartMs }
        val todayPnl = todayTerminal.sumOf { it.netPnlSol.takeIf { v -> v != 0.0 } ?: it.pnlSol }
        val key = brisbaneDayKey(nowMs)
        if (key != dayKey) {
            dayKey = key
            dayStartWalletSol = (wallet - todayPnl).takeIf { it.isFinite() && it > 0.0 } ?: wallet.coerceAtLeast(0.0)
            dayHighWalletSol = maxOf(dayStartWalletSol, wallet)
        }
        if (dayStartWalletSol <= 0.0 && wallet > 0.0) dayStartWalletSol = (wallet - todayPnl).takeIf { it.isFinite() && it > 0.0 } ?: wallet
        if (wallet > dayHighWalletSol) dayHighWalletSol = wallet
        val dayBase = dayStartWalletSol.takeIf { it.isFinite() && it > 0.0 } ?: wallet.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val dayProgressX = (wallet / dayBase).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val drawdownFromHighPct = if (dayHighWalletSol > 0.0 && wallet > 0.0) ((wallet - dayHighWalletSol) / dayHighWalletSol) * 100.0 else 0.0
        val base = wallet.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val gainRatio = decisionPnl6132 / base
        val openRunnerPressure6028 = openEquity6028.runners > 0 && openEquity6028.pnlSol >= maxOf(0.02, wallet * 0.10) && equityPressureX6028 >= 1.10
        val multReason = when {
            drawdownFromHighPct <= -25.0 && todayPnl > 0.0 -> 0.55 to "intraday_high_water_profit_protect"
            drawdownFromHighPct <= -15.0 && todayPnl > 0.0 -> 0.75 to "intraday_high_water_cooling"
            equityPressureX6028 >= 2.0 && openRunnerPressure6028 -> 1.65 to "trusted_open_equity_two_x_pressure_6028"
            equityPressureX6028 >= 1.5 && openRunnerPressure6028 -> 1.35 to "trusted_open_equity_compound_pressure_6028"
            equityPressureX6028 >= 1.15 && openRunnerPressure6028 -> 1.15 to "trusted_open_runner_pressure_6028"
            dayProgressX >= 5.0 && decisionWr6132 >= 35.0 && decisionPf6132 >= 2.0 && decisionPnl6132 > 0.0 -> 1.45 to "five_x_day_protect_compound"
            dayProgressX >= 3.0 && decisionWr6132 >= 35.0 && decisionPf6132 >= 1.8 && decisionPnl6132 > 0.0 -> 1.75 to "three_x_day_compound"
            dayProgressX >= 2.0 && decisionWr6132 >= 32.0 && decisionPf6132 >= 1.5 && decisionPnl6132 > 0.0 -> 2.05 to "two_x_day_compound"
            trades < 20 -> 1.00 to "bootstrap_under_20"
            strategyTruthNegative6128 -> 0.55 to "defensive_strategy_truth_negative_6132"
            decisionPnl6132 <= 0.0 || decisionWr6132 < 20.0 || decisionPf6132 < 0.95 -> 0.55 to "defensive_clean_truth_negative_or_low_wr_6132"
            gainRatio >= 2.0 && decisionWr6132 >= 35.0 && decisionPf6132 >= 2.0 -> 2.25 to "two_x_plus_compound_unlock"
            gainRatio >= 1.0 && decisionWr6132 >= 32.0 && decisionPf6132 >= 1.6 -> 1.85 to "one_x_compound_unlock"
            gainRatio >= 0.75 && decisionWr6132 >= 30.0 && decisionPf6132 >= 1.4 -> 1.55 to "seventyfive_pct_growth_unlock"
            gainRatio >= 0.30 && decisionWr6132 >= 28.0 && decisionPf6132 >= 1.25 -> 1.30 to "thirty_pct_growth_unlock"
            decisionPnl6132 > 0.0 && decisionWr6132 >= 25.0 && decisionPf6132 >= 1.15 -> 1.12 to "positive_clean_edge_6132"
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
            dayStartWalletSol = dayStartWalletSol,
            dayHighWalletSol = dayHighWalletSol,
            dayPnlSol = todayPnl,
            dayProgressX = dayProgressX,
            drawdownFromDayHighPct = drawdownFromHighPct,
            trustedOpenUnrealizedSol = openEquity6028.pnlSol,
            trustedOpenRunnerCount = openEquity6028.runners,
            equityPressureX = equityPressureX6028,
            refreshedAtMs = nowMs,
            strategyCleanPnlSol = strategyCleanPnl6128,
            moneyMode = mode6081,
        )
    }
}
