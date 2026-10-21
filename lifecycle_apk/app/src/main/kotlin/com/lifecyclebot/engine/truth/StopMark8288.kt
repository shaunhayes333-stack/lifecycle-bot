package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.math.BigDecimal
import java.math.BigInteger

/**
 * V5.0.8288 — A STOP IS NOT HELD FOR A FEED WHEN THE TRADE TAPE ALREADY PROVES IT.
 *
 * 5.0.8286 live (19 min): TICK_HARD_FLOOR_CATASTROPHIC_LANE_SHITCOIN_-41PC=161, RAPID_CATASTROPHE_STOP=59 against a 15%
 * SHITCOIN stop; CATASTROPHIC_EXIT queue avg 161 s / max 540 s; riskClockStale=87, riskClockNoMark=59, mark repair
 * NO_FEED_ANSWERED=1137. MissingMarkExitVeto6835 holds a price-based exit whose feed mark is older than 60 s (or frozen
 * 120 s) for up to 10-20 minutes "until a fresh mark" — but a rugged curve stops trading, so the feed never refreshes
 * while the PumpPortal / Helius / PumpSwap trade stream (TradeTape8100) holds the executed prints the stop needs.
 *
 * Rule ([tapeConfirmsStop8288]): a STOP-family exit (stop / floor / catastrophe / SL) on an open canonical position is
 * released from the stale-mark hold when the newest executed print on the tape (inside [PRINT_WINDOW_MS_8288]) values
 * the position at or below [CONFIRM_LOSS_PCT_8288] against its own entry (SOL per token, from the chain-confirmed cost
 * and quantity). A print that disagrees (not a loss) keeps the hold; no print keeps the hold. Profit exits never use it.
 * The print is an executed trade, not a feed estimate; nothing is invented.
 */
object StopMark8288 {

    /** The newest print must be at most this old. */
    const val PRINT_WINDOW_MS_8288 = 30L * 60_000L
    /** The position must be at least this far under water at the print (the shallowest stop the lanes use is deeper). */
    const val CONFIRM_LOSS_PCT_8288 = -8.0
    /** Outside this band the print and the entry are not on one unit basis: no confirmation. */
    private const val SANE_MIN_PCT_8288 = -100.0
    private const val SANE_MAX_PCT_8288 = 2_000.0

    /** Pure: is [reasonUpper] a loss-side stop (never a take-profit / lock / trail)? */
    fun stopFamily8288(reasonUpper: String): Boolean {
        if (reasonUpper.contains("TAKE_PROFIT") || reasonUpper.contains("PROFIT_LOCK") || reasonUpper.contains("PEAK_LOCK") ||
            reasonUpper.contains("TARGET_HIT") || reasonUpper.contains("TRAIL")) return false
        return reasonUpper.contains("STOP") || reasonUpper.contains("FLOOR") || reasonUpper.contains("CATASTROPH") ||
            Regex("(?:^|_)SL(?:_|$)").containsMatchIn(reasonUpper)
    }

    /** Pure: entry SOL per (ui) token of a position that cost [entryCostSol] for [originalRaw] at [scale]. */
    fun entrySolPerToken8288(entryCostSol: Double, originalRaw: BigInteger, scale: Int): Double {
        if (!entryCostSol.isFinite() || entryCostSol <= 0.0 || originalRaw.signum() <= 0 || scale !in 0..18) return Double.NaN
        val qty = BigDecimal(originalRaw).movePointLeft(scale).toDouble()
        return if (qty > 0.0 && qty.isFinite()) entryCostSol / qty else Double.NaN
    }

    /** Pure: the position's pnl % at an executed print, or NaN when the two are not on one basis. */
    fun printPnlPct8288(printSolPerToken: Double, entrySolPerToken: Double): Double {
        if (!printSolPerToken.isFinite() || printSolPerToken <= 0.0 || !entrySolPerToken.isFinite() || entrySolPerToken <= 0.0) return Double.NaN
        val pct = (printSolPerToken / entrySolPerToken - 1.0) * 100.0
        return if (pct in SANE_MIN_PCT_8288..SANE_MAX_PCT_8288) pct else Double.NaN
    }

    /** Pure: does a print at [printPnlPct] confirm a stop? */
    fun confirms8288(printPnlPct: Double): Boolean = printPnlPct.isFinite() && printPnlPct <= CONFIRM_LOSS_PCT_8288

    /** The risk clock may read a print at most this old as the held mark (no trade on the venue = its price has not moved). */
    const val RISK_PRINT_MAX_AGE_MS_8288 = 10L * 60_000L

    /**
     * Pure: the risk-clock mark (USD) from the newest executed print — [printSol] SOL per token at [printAtMs] — or null:
     * older than [RISK_PRINT_MAX_AGE_MS_8288], before the position opened, no SOL price, or off the entry's unit basis
     * (outside 0.02x..50x of [entryPriceUsd], the same guard RiskClockMark7960 uses).
     */
    fun tapeMarkUsd8288(printSol: Double, printAtMs: Long, solUsd: Double, entryPriceUsd: Double, entryTimeMs: Long, nowMs: Long): Double? {
        if (!printSol.isFinite() || printSol <= 0.0 || !solUsd.isFinite() || solUsd <= 0.0) return null
        if (!entryPriceUsd.isFinite() || entryPriceUsd <= 0.0) return null
        if (printAtMs <= 0L || nowMs - printAtMs !in 0L..RISK_PRINT_MAX_AGE_MS_8288 || printAtMs < entryTimeMs) return null
        val usd = printSol * solUsd
        return if ((usd / entryPriceUsd) in 0.02..50.0) usd else null
    }

    /**
     * BotService.riskClockMark7960 (after the canonical and runtime marks): the trade tape's newest executed print as the
     * held mark, with its age. 8286: riskClockStale=87 / riskClockNoMark=59 while the trade stream had the prints.
     */
    fun tapeRiskMark8288(mint: String, entryPriceUsd: Double, entryTimeMs: Long, nowMs: Long): Pair<Double, Long>? = try {
        val p = TradeTape8100.prints8128(mint, nowMs, RISK_PRINT_MAX_AGE_MS_8288).maxByOrNull { it.atMs }
        val solUsd = com.lifecyclebot.engine.WalletManager.freshSolPrice8163(nowMs)
        val usd = p?.let { tapeMarkUsd8288(it.priceSol, it.atMs, solUsd, entryPriceUsd, entryTimeMs, nowMs) }
        if (p != null && usd != null) usd to (nowMs - p.atMs).coerceAtLeast(0L) else null
    } catch (_: Throwable) { null }

    /** MissingMarkExitVeto6835: may the stale-mark hold of [exitReason] on [mint] be released on the tape's newest print? */
    fun tapeConfirmsStop8288(mint: String, exitReason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank() || !stopFamily8288(exitReason.uppercase())) return false
        return try {
            val pos = CanonicalPositionAuthority6441.openPositions().filter { it.mint == mint }.maxByOrNull { it.lastMutationMs } ?: return false
            val print = TradeTape8100.prints8128(mint, nowMs, PRINT_WINDOW_MS_8288).maxByOrNull { it.atMs } ?: return false
            if (print.atMs < pos.openedAtMs) return false
            val pnl = printPnlPct8288(print.priceSol, entrySolPerToken8288(pos.entryCostSol, pos.originalQtyRaw, pos.quantityScale))
            val ok = confirms8288(pnl)
            PipelineHealthCollector.labelInc(if (ok) "STOP_CONFIRMED_BY_TAPE_PRINT_8288" else "STOP_TAPE_PRINT_NOT_CONFIRMING_8288")
            if (ok) ForensicLogger.lifecycle(
                "STOP_CONFIRMED_BY_TAPE_PRINT_8288",
                "mint=${mint.take(10)} reason=${exitReason.take(60)} printPnl=${"%.1f".format(pnl)}% printAgeMs=${nowMs - print.atMs} " +
                    "source=${print.source} pool=${print.pool.take(16)} action=release_stale_mark_hold",
            )
            ok
        } catch (_: Throwable) { false }
    }
}
