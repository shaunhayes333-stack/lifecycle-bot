package com.lifecyclebot.engine

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.data.Trade
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TradeJournal
 *
 * Aggregates all trades across all tokens and exports them in multiple formats:
 * - CSV
 * - PDF
 * - IRS Form 8949 compatible CSV
 *
 * Decisive-trade rules used everywhere in this class:
 * - WIN    = pnlPct >= 0.5
 * - LOSS   = pnlPct <= -2.0
 * - SCRATCH = between those thresholds
 */
class TradeJournal(private val ctx: Context) {

    companion object {
        private const val WIN_THRESHOLD_PCT = 0.5
        private const val LOSS_THRESHOLD_PCT = -2.0
    }

    private fun isSellLike(side: String): Boolean =
        side.equals("SELL", ignoreCase = true) || side.equals("PARTIAL_SELL", ignoreCase = true)

    private fun isInvalidAccounting(e: JournalEntry): Boolean {
        if (!isSellLike(e.side)) return false
        val t = Trade(
            side = e.side,
            mode = e.mode,
            sol = e.solAmount,
            price = e.entryPrice,
            ts = e.ts,
            reason = e.reason,
            pnlSol = e.pnlSol,
            pnlPct = e.pnlPct,
            score = e.score,
            feeSol = e.feeSol,
            netPnlSol = e.netPnlSol,
            tradingMode = e.tradingMode,
            tradingModeEmoji = e.tradingModeEmoji,
            mint = e.mint,
        )
        return !TradeHistoryStore.isValidAccountingTrade(t)
    }

    data class JournalEntry(
        val ts: Long,
        val symbol: String,
        val mint: String,
        val side: String,
        val entryPrice: Double,
        val exitPrice: Double,
        val solAmount: Double,
        val pnlSol: Double,
        val pnlPct: Double,
        val reason: String,
        val mode: String,
        val score: Double,
        val durationMins: Double,
        val phase: String,
        val tradingMode: String,
        val tradingModeEmoji: String,
        val feeSol: Double,
        val netPnlSol: Double,
        val proofState: String = "",
        val positionId: String = "",
        val entryTsMs: Long = 0L,
        val entryMcapUsd: Double = 0.0,
        val entryQtyToken: Double = 0.0,
        val entryCostSol: Double = 0.0,
        val entryDecimals: Int = 0,
        val soldQtyToken: Double = 0.0,
        val remainingQtyToken: Double = 0.0,
        val entryPriceSource: String = "",
        val entryPoolAddress: String = "",
    )

    private fun journalEntryFromTrade(trade: Trade, symbol: String, mint: String): JournalEntry {
        val sellLike = isSellLike(trade.side)
        val entryPx = trade.entryPriceSnapshot.takeIf { it > 0.0 } ?: if (!sellLike) trade.price else 0.0
        return JournalEntry(
            ts = trade.ts,
            symbol = symbol,
            mint = mint,
            side = trade.side,
            entryPrice = entryPx,
            exitPrice = if (sellLike) trade.price else 0.0,
            solAmount = trade.sol,
            pnlSol = trade.pnlSol,
            pnlPct = trade.pnlPct,
            reason = trade.reason,
            mode = trade.mode,
            score = trade.score,
            durationMins = 0.0,
            phase = "",
            tradingMode = trade.tradingMode,
            tradingModeEmoji = trade.tradingModeEmoji,
            feeSol = trade.feeSol,
            netPnlSol = trade.netPnlSol,
            proofState = trade.proofState,
            positionId = trade.positionId,
            entryTsMs = trade.entryTsMs,
            entryMcapUsd = trade.entryMcapUsd,
            entryQtyToken = trade.entryQtyToken,
            entryCostSol = trade.entryCostSol,
            entryDecimals = trade.entryDecimals,
            soldQtyToken = trade.soldQtyToken,
            remainingQtyToken = trade.remainingQtyToken,
            entryPriceSource = trade.entryPriceSource,
            entryPoolAddress = trade.entryPoolAddress,
        )
    }

    data class JournalStats(
        val totalTrades: Int,
        val totalWins: Int,
        val totalLosses: Int,
        val winRate: Double,
        val totalPnlSol: Double,
        val bestTrade: JournalEntry?,
        val worstTrade: JournalEntry?,
        val avgWinPct: Double,
        val avgLossPct: Double,
        val totalVolumeSol: Double,
        val scratchCount: Int = 0,
    )

    /**
     * Build PAPER mode journal only
     */
    fun buildPaperJournal(tokens: Map<String, TokenState>): List<JournalEntry> {
        return buildJournal(tokens).filter { it.mode.equals("paper", ignoreCase = true) }
    }

    /**
     * Build LIVE mode journal only
     */
    fun buildLiveJournal(tokens: Map<String, TokenState>): List<JournalEntry> {
        return buildJournal(tokens).filter { it.mode.equals("live", ignoreCase = true) }
    }

    /**
     * Build combined journal from current in-memory state + persisted history
     */
    fun buildJournal(tokens: Map<String, TokenState>): List<JournalEntry> {
        val entries = mutableListOf<JournalEntry>()
        val seenKeys = mutableSetOf<String>()

        // Current session, in-memory
        tokens.values.forEach { tokenState ->
            tokenState.trades.forEach { trade ->
                val key = buildKey(trade.ts, trade.mint, trade.side)
                if (key !in seenKeys) {
                    seenKeys.add(key)
                    entries.add(journalEntryFromTrade(trade, tokenState.symbol, tokenState.mint))
                }
            }
        }

        // Persisted history
        try {
            val persistedTrades = TradeHistoryStore.getAllTrades()
            persistedTrades.forEach { trade ->
                val key = buildKey(trade.ts, trade.mint, trade.side)
                if (key !in seenKeys) {
                    seenKeys.add(key)

                    val symbol = tokens.values.find { it.mint == trade.mint }?.symbol ?: trade.mint.take(8)

                    entries.add(journalEntryFromTrade(trade, symbol, trade.mint))
                }
            }
        } catch (e: Exception) {
            ErrorLogger.error("TradeJournal", "Failed to load persisted trades: ${e.message}")
        }

        return entries.sortedByDescending { it.ts }
    }

    /**
     * Export PAPER trades only as CSV
     */
    fun exportPaperCsv(tokens: Map<String, TokenState>): Intent? {
        return exportCsvFiltered(tokens, "paper", "AATE_Paper_Trades")
    }

    /**
     * Export LIVE trades only as CSV
     */
    fun exportLiveCsv(tokens: Map<String, TokenState>): Intent? {
        return exportCsvFiltered(tokens, "live", "AATE_Live_Trades")
    }

    /**
     * Export ALL trades as CSV.
     *
     * V5.0.3683 — operator deep-audit P0 directive: the default tax/accounting
     * export must EXCLUDE PAPER. The "ALL" export now writes LIVE only by
     * default; the legacy combined export is still available via
     * exportCsvFiltered(tokens, modeFilter = null, "AATE_All_Trades_COMBINED")
     * for diagnostic use, but every emitted row carries its mode column so
     * paper rows can never be silently mixed into tax math downstream.
     */
    fun exportCsv(tokens: Map<String, TokenState>): Intent? {
        return exportCsvFiltered(tokens, "live", "AATE_Live_Trades")
    }

    /**
     * Diagnostic-only: emit BOTH paper and live trades into a single CSV with
     * the rowType/mode columns intact. The footer breaks out LIVE / PAPER /
     * COMBINED so downstream readers can distinguish realised tax math from
     * paper simulation math.
     */
    fun exportCombinedDiagnosticCsv(tokens: Map<String, TokenState>): Intent? {
        return exportCsvFiltered(tokens, null, "AATE_All_Trades_COMBINED")
    }

    /**
     * V5.0.3683 — Canonical per-row accounting derivation. Doctrine:
     *
     *   trade.sol (for sells) = COST BASIS allocated to the portion sold.
     *   trade.pnlSol           = REALIZED gain on that portion (proceeds-cost).
     *   trade.feeSol           = total fees paid on that exit leg.
     *
     * Every Trade emitted by Executor.kt now stores trade.sol consistently as
     * cost basis (the old live SELL leg used solBack/proceeds; corrected in
     * V5.0.3683). This function is the SINGLE ledger calculator — every
     * exporter (CSV / PDF / 8949 / Summary) must call it so footer totals
     * exactly equal the sum of emitted rows.
     */
    private data class RowAccounting(
        val costBasisSol: Double,
        val proceedsSol:  Double,
        val gainLossSol:  Double,
        val feeSol:       Double,
        val netGainSol:   Double,
        val costBasisUsd: Double,
        val proceedsUsd:  Double,
        val gainLossUsd:  Double,
        val feeUsd:       Double,
        val netGainUsd:   Double,
        val invariantViolations: List<String>,
    )

    private fun deriveRowAccounting(e: JournalEntry, solUsd: Double): RowAccounting {
        val v = mutableListOf<String>()
        val isBuy  = e.side.equals("BUY", ignoreCase = true)
        val isSell = isSellLike(e.side)

        // trade.sol is cost basis allocated. trade.pnlSol is realized gain.
        val costBasisSol = e.solAmount.coerceAtLeast(0.0)
        val gainLossSol  = if (isBuy) 0.0 else e.pnlSol
        val proceedsSol  = if (isBuy) 0.0 else (costBasisSol + gainLossSol).coerceAtLeast(0.0)

        // Fee: trust the recorded value; fall back to 0.5% of cost basis on
        // sells where the legacy paper paths failed to populate it.
        val feeSol = if (e.feeSol > 0.0) e.feeSol
                     else if (isSell) costBasisSol * 0.005
                     else 0.0

        // Net gain ALWAYS = gain - fee. Never zero when gain is non-zero
        // unless fees exactly offset (catastrophe).
        val netGainSol = if (isBuy) 0.0 else (gainLossSol - feeSol)

        val costBasisUsd = costBasisSol * solUsd
        val proceedsUsd  = proceedsSol  * solUsd
        val gainLossUsd  = gainLossSol  * solUsd
        val feeUsd       = feeSol       * solUsd
        val netGainUsd   = netGainSol   * solUsd

        // Hard invariants per operator spec.
        if (isBuy) {
            if (proceedsUsd  != 0.0) v += "BUY_PROCEEDS_NON_ZERO"
            if (gainLossUsd  != 0.0) v += "BUY_GAINLOSS_NON_ZERO"
            if (netGainUsd   != 0.0) v += "BUY_NETGAIN_NON_ZERO"
        }
        if (isSell) {
            if (kotlin.math.abs(gainLossUsd - (proceedsUsd - costBasisUsd)) > 0.01)
                v += "SELL_GAIN_NOT_PROCEEDS_MINUS_COST"
            if (kotlin.math.abs(netGainUsd - (gainLossUsd - feeUsd)) > 0.01)
                v += "SELL_NETGAIN_NOT_GAIN_MINUS_FEE"
            if (proceedsUsd <= 0.0 && gainLossUsd > 0.0)
                v += "ZERO_PROCEEDS_POSITIVE_GAIN_IMPOSSIBLE"
        }
        return RowAccounting(
            costBasisSol, proceedsSol, gainLossSol, feeSol, netGainSol,
            costBasisUsd, proceedsUsd, gainLossUsd, feeUsd, netGainUsd,
            v.toList()
        )
    }

    private fun exportCsvFiltered(
        tokens: Map<String, TokenState>,
        modeFilter: String?,
        filePrefix: String
    ): Intent? {
        val allEntries = buildJournal(tokens)
        val entries = if (modeFilter != null) {
            allEntries.filter { it.mode.equals(modeFilter, ignoreCase = true) }
        } else {
            allEntries
        }

        if (entries.isEmpty()) return null

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()

        val solPrice = try {
            BotService.instance?.currencyManager?.getSolUsd() ?: 0.0
        } catch (_: Exception) {
            0.0
        }

        // V5.0.3683 — rowType column added so footer/summary rows are never
        // emitted under the trade schema. TRADE rows hold accounting; SUMMARY
        // rows hold footer totals. Downstream readers filter by rowType.
        sb.appendLine(
            listOf(
                "rowType",
                "Date/Time",
                "Token Symbol",
                "Token Address (Mint)",
                "Transaction Type",
                "Trading Mode",
                "Mode Emoji",
                "Cost Basis (SOL)",
                "Price per Token (USD)",
                "Cost Basis (USD)",
                "Proceeds (USD)",
                "Gain/Loss (SOL)",
                "Gain/Loss (USD)",
                "Gain/Loss (%)",
                "Fee (SOL)",
                "Fee (USD)",
                "Net Gain (SOL)",
                "Net Gain (USD)",
                "Paper/Live",
                "Entry Score",
                "Exit Reason",
                "Invariants",
                "Notes",
                "Position ID",
                "Entry Timestamp",
                "Entry Price Snapshot (SOL)",
                "Exit Price (SOL)",
                "Entry Market Cap (USD)",
                "Entry Token Qty",
                "Sold Token Qty",
                "Remaining Token Qty",
                "Entry Cost Snapshot (SOL)",
                "Token Decimals",
                "Proof State",
                "Entry Price Source",
                "Entry Pool"
            ).joinToString(",")
        )

        // Canonical row emission. Each row uses deriveRowAccounting; rows that
        // violate hard invariants are flagged but still emitted with an
        // INVARIANT_VIOLATED note so downstream tooling can audit.
        data class EmittedRow(val entry: JournalEntry, val acct: RowAccounting)
        val emitted = mutableListOf<EmittedRow>()

        entries.forEach { e ->
            val legacyInvalid = isInvalidAccounting(e)
            val acct = deriveRowAccounting(e, solPrice)
            val invariantStr = if (acct.invariantViolations.isEmpty()) "OK"
                               else acct.invariantViolations.joinToString("|")
            val notes = if (legacyInvalid) "INVALID_LEGACY: zero price/proceeds with non-zero PnL"
                        else if (acct.invariantViolations.isNotEmpty()) "INVARIANT_VIOLATED"
                        else "Trading bot automated trade"
            sb.appendLine(
                listOf(
                    "TRADE",
                    sdf.format(Date(e.ts)),
                    e.symbol.csvEscape(),
                    e.mint,
                    e.side,
                    e.tradingMode.ifEmpty { "STANDARD" },
                    e.tradingModeEmoji.ifEmpty { "📈" },
                    "%.6f".format(acct.costBasisSol),
                    "%.10f".format((if (isSellLike(e.side) && e.exitPrice > 0.0) e.exitPrice else e.entryPrice) * solPrice),
                    "%.2f".format(acct.costBasisUsd),
                    "%.2f".format(acct.proceedsUsd),
                    "%.6f".format(acct.gainLossSol),
                    "%.2f".format(acct.gainLossUsd),
                    "%.2f".format(e.pnlPct),
                    "%.6f".format(acct.feeSol),
                    "%.2f".format(acct.feeUsd),
                    "%.6f".format(acct.netGainSol),
                    "%.2f".format(acct.netGainUsd),
                    e.mode.uppercase(),
                    "%.0f".format(e.score),
                    e.reason.csvEscape(),
                    invariantStr,
                    notes.csvEscape(),
                    e.positionId.csvEscape(),
                    if (e.entryTsMs > 0L) sdf.format(Date(e.entryTsMs)).csvEscape() else "",
                    "%.12f".format(e.entryPrice),
                    "%.12f".format(e.exitPrice),
                    "%.2f".format(e.entryMcapUsd),
                    "%.6f".format(e.entryQtyToken),
                    "%.6f".format(e.soldQtyToken),
                    "%.6f".format(e.remainingQtyToken),
                    "%.6f".format(e.entryCostSol),
                    e.entryDecimals.toString(),
                    e.proofState.csvEscape(),
                    e.entryPriceSource.csvEscape(),
                    e.entryPoolAddress.csvEscape()
                ).joinToString(",")
            )
            if (acct.invariantViolations.isEmpty() && !legacyInvalid) {
                emitted += EmittedRow(e, acct)
            }
        }

        // V5.0.3683 — Footer derived strictly from emitted rows that PASSED
        // invariants. Eliminates the dual-source drift the operator audit
        // flagged (row sum != footer sum).
        val tradeRows = emitted.map { it.entry }
        val tradeAcct = emitted.map { it.acct }

        val sells = emitted.filter { isSellLike(it.entry.side) }
        val decisiveSells = sells.filter { isDecisive(it.entry.pnlPct) }
        val wins = decisiveSells.count { isWin(it.entry.pnlPct) }
        val losses = decisiveSells.count { isLoss(it.entry.pnlPct) }
        val scratches = sells.count { isScratch(it.entry.pnlPct) }

        val totalGainSol   = sells.sumOf { it.acct.gainLossSol }
        val totalGainUsd   = sells.sumOf { it.acct.gainLossUsd }
        val totalFeeSol    = tradeAcct.sumOf { it.feeSol }
        val totalFeeUsd    = tradeAcct.sumOf { it.feeUsd }
        val totalNetSol    = sells.sumOf { it.acct.netGainSol }
        val totalNetUsd    = sells.sumOf { it.acct.netGainUsd }
        val totalVolumeSol = tradeAcct.sumOf { it.costBasisSol }
        val totalVolumeUsd = tradeAcct.sumOf { it.costBasisUsd }
        val winRate = if (wins + losses > 0) wins * 100.0 / (wins + losses) else 0.0

        val liveOnly = sells.filter { it.entry.mode.equals("live", ignoreCase = true) }
        val paperOnly = sells.filter { it.entry.mode.equals("paper", ignoreCase = true) }
        val liveNetUsd = liveOnly.sumOf { it.acct.netGainUsd }
        val paperNetUsd = paperOnly.sumOf { it.acct.netGainUsd }

        fun summary(kv: String, value: String) {
            sb.appendLine("SUMMARY,${kv.csvEscape()},,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,," + value.csvEscape())
        }

        sb.appendLine("")
        summary("=== SUMMARY (derived from emitted TRADE rows) ===", "")
        summary("Report Generated", sdf.format(Date()))
        summary("SOL Price at Export", "\$${"%.2f".format(solPrice)}")
        summary("Total Trades", tradeRows.size.toString())
        summary("Buy Transactions", tradeRows.count { it.side.equals("BUY", true) }.toString())
        summary("Sell Transactions", sells.size.toString())
        summary("Decisive Sells", decisiveSells.size.toString())
        summary("Scratch Sells", scratches.toString())
        summary("Wins", wins.toString())
        summary("Losses", losses.toString())
        summary("Win Rate", "${"%.1f".format(winRate)}%")
        summary("Total Volume (Cost Basis SOL)", "%.4f".format(totalVolumeSol))
        summary("Total Volume (Cost Basis USD)", "\$${"%.2f".format(totalVolumeUsd)}")
        summary("Realized Gain/Loss (SOL)", "%.6f".format(totalGainSol))
        summary("Realized Gain/Loss (USD)", "\$${"%.2f".format(totalGainUsd)}")
        summary("Total Fees (SOL)", "%.6f".format(totalFeeSol))
        summary("Total Fees (USD)", "\$${"%.2f".format(totalFeeUsd)}")
        summary("Net Realized Gain/Loss (SOL)", "%.6f".format(totalNetSol))
        summary("Net Realized Gain/Loss (USD)", "\$${"%.2f".format(totalNetUsd)}")
        summary("LIVE Net Realized (USD)", "\$${"%.2f".format(liveNetUsd)}")
        summary("PAPER Simulated Net (USD non-tax)", "\$${"%.2f".format(paperNetUsd)}")
        summary("DISCLAIMER", "Informational. PAPER lines are simulation only and must not be used for tax. Consult a tax professional.")

        val modeLabel = when (modeFilter?.lowercase()) {
            "paper" -> "PAPER"
            "live" -> "LIVE"
            else -> "ALL"
        }

        val filename = "${filePrefix}_${modeLabel}_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.csv"
        val file = File(ctx.cacheDir, filename)
        file.writeText(sb.toString())

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AATE Trade Journal - $modeLabel Trades - Tax Report")
            putExtra(Intent.EXTRA_TEXT, "${tradeRows.size} ${modeLabel.lowercase()} trades exported for tax reporting")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Get stats for ALL trades
     */
    fun getStats(tokens: Map<String, TokenState>): JournalStats {
        return getStatsFiltered(buildJournal(tokens))
    }

    /**
     * Get stats for PAPER trades only
     */
    fun getPaperStats(tokens: Map<String, TokenState>): JournalStats {
        return getStatsFiltered(buildPaperJournal(tokens))
    }

    /**
     * Get stats for LIVE trades only
     */
    fun getLiveStats(tokens: Map<String, TokenState>): JournalStats {
        return getStatsFiltered(buildLiveJournal(tokens))
    }

    fun getStatsFiltered(entries: List<JournalEntry>): JournalStats {
        val sells = entries.filter { isSellLike(it.side) && !isInvalidAccounting(it) }
        val decisiveTrades = sells.filter { isDecisive(it.pnlPct) }
        val scratchTrades = sells.filter { isScratch(it.pnlPct) }
        val wins = decisiveTrades.filter { isWin(it.pnlPct) }
        val losses = decisiveTrades.filter { isLoss(it.pnlPct) }

        val cappedWinsPct = wins.map { it.pnlPct }
        val cappedLossPct = losses.map { it.pnlPct }

        return JournalStats(
            totalTrades = decisiveTrades.size,
            totalWins = wins.size,
            totalLosses = losses.size,
            winRate = if (decisiveTrades.isNotEmpty()) {
                wins.size.toDouble() / decisiveTrades.size.toDouble() * 100.0
            } else {
                0.0
            },
            totalPnlSol = sells.sumOf { it.pnlSol },
            bestTrade = sells.maxByOrNull { it.pnlPct },
            worstTrade = sells.minByOrNull { it.pnlPct },
            avgWinPct = if (cappedWinsPct.isNotEmpty()) cappedWinsPct.average() else 0.0,
            avgLossPct = if (cappedLossPct.isNotEmpty()) cappedLossPct.average() else 0.0,
            totalVolumeSol = sells.sumOf { it.solAmount },
            scratchCount = scratchTrades.size,
        )
    }

    /**
     * Export as PDF - professional tax report format
     */
    fun exportPdf(tokens: Map<String, TokenState>): Intent? {
        val entries = buildJournal(tokens)
        val sells = entries.filter { isSellLike(it.side) && !isInvalidAccounting(it) }
        if (sells.isEmpty()) return null

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val solPrice = try {
            BotService.instance?.currencyManager?.getSolUsd() ?: 0.0
        } catch (_: Exception) {
            0.0
        }

        val document = PdfDocument()
        var pageNumber = 1
        val pageWidth = 612
        val pageHeight = 792

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val headerPaint = Paint().apply {
            color = Color.parseColor("#374151")
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val greenPaint = Paint(textPaint).apply { color = Color.parseColor("#10B981") }
        val redPaint = Paint(textPaint).apply { color = Color.parseColor("#EF4444") }
        val amberPaint = Paint(textPaint).apply { color = Color.parseColor("#F59E0B") }

        val decisiveSells = sells.filter { isDecisive(it.pnlPct) }
        val wins = decisiveSells.count { isWin(it.pnlPct) }
        val losses = decisiveSells.count { isLoss(it.pnlPct) }
        val scratches = sells.count { isScratch(it.pnlPct) }

        val totalPnlSol = sells.sumOf { it.pnlSol }
        val totalPnlUsd = totalPnlSol * solPrice
        val totalFeeSol = sells.sumOf { it.feeSol }
        val totalFeeUsd = totalFeeSol * solPrice
        val winRate = if (wins + losses > 0) wins * 100.0 / (wins + losses) else 0.0

        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = document.startPage(pageInfo)
        var canvas: Canvas = page.canvas
        var y = 50f

        canvas.drawText("AATE - Cryptocurrency Trading Tax Report", 50f, y, titlePaint)
        y += 30f
        canvas.drawText(
            "Generated: ${SimpleDateFormat("MMMM dd, yyyy 'at' HH:mm", Locale.US).format(Date())}",
            50f,
            y,
            textPaint
        )
        y += 40f

        canvas.drawText("═══════════════════════════════════════════════════════════", 50f, y, textPaint)
        y += 20f
        canvas.drawText("SUMMARY", 50f, y, headerPaint)
        y += 25f

        canvas.drawText("Total Sell Transactions:  ${sells.size}", 50f, y, textPaint)
        y += 18f
        canvas.drawText("Decisive Sells:           ${decisiveSells.size}", 50f, y, textPaint)
        y += 18f
        canvas.drawText("Scratch Sells:            $scratches", 50f, y, textPaint)
        y += 18f
        canvas.drawText("Win Rate:                 ${String.format("%.1f", winRate)}% ($wins wins / $losses losses)", 50f, y, textPaint)
        y += 25f

        canvas.drawText("SOL Price at Export:      \$${String.format("%.2f", solPrice)}", 50f, y, textPaint)
        y += 25f

        val pnlPaint = if (totalPnlUsd >= 0.0) greenPaint else redPaint
        canvas.drawText("Total Realized Gain/Loss: ", 50f, y, textPaint)
        canvas.drawText("\$${String.format("%,.2f", totalPnlUsd)} (${String.format("%.4f", totalPnlSol)} SOL)", 220f, y, pnlPaint)
        y += 18f

        canvas.drawText(
            "Total Transaction Fees:   \$${String.format("%.2f", totalFeeUsd)} (${String.format("%.6f", totalFeeSol)} SOL)",
            50f,
            y,
            textPaint
        )
        y += 18f

        val netPnl = totalPnlUsd - totalFeeUsd
        val netPaint = if (netPnl >= 0.0) greenPaint else redPaint
        canvas.drawText("NET Gain/Loss (after fees): ", 50f, y, textPaint)
        canvas.drawText("\$${String.format("%,.2f", netPnl)}", 230f, y, netPaint)
        y += 30f

        canvas.drawText("═══════════════════════════════════════════════════════════", 50f, y, textPaint)
        y += 40f

        canvas.drawText("DETAILED TRADE LOG", 50f, y, headerPaint)
        y += 25f
        canvas.drawText("Date/Time            Token          P&L (USD)     P&L (%)    Mode", 50f, y, textPaint)
        y += 5f
        canvas.drawText("─────────────────────────────────────────────────────────────", 50f, y, textPaint)
        y += 18f

        val itemsPerPage = 35
        var itemCount = 0

        for (entry in sells) {
            if (itemCount > 0 && itemCount % itemsPerPage == 0) {
                document.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = document.startPage(pageInfo)
                canvas = page.canvas
                y = 50f

                canvas.drawText("AATE Tax Report - Page $pageNumber", 50f, y, headerPaint)
                y += 30f
                canvas.drawText("Date/Time            Token          P&L (USD)     P&L (%)    Mode", 50f, y, textPaint)
                y += 5f
                canvas.drawText("─────────────────────────────────────────────────────────────", 50f, y, textPaint)
                y += 18f
            }

            val dateStr = sdf.format(Date(entry.ts))
            val symbolStr = entry.symbol.take(12).padEnd(12)
            val pnlUsd = entry.pnlSol * solPrice
            val pnlStr = String.format("%+,.2f", pnlUsd).padStart(10)
            val pctStr = String.format("%+.1f%%", entry.pnlPct).padStart(8)
            val modeStr = entry.tradingMode.take(10)

            val linePaint = when {
                isWin(entry.pnlPct) -> greenPaint
                isLoss(entry.pnlPct) -> redPaint
                else -> amberPaint
            }

            canvas.drawText("$dateStr  $symbolStr  $pnlStr   $pctStr    $modeStr", 50f, y, linePaint)
            y += 16f
            itemCount++
        }

        y += 20f
        canvas.drawText("─────────────────────────────────────────────────────────────", 50f, y, textPaint)
        y += 20f
        canvas.drawText("DISCLAIMER: This report is for informational purposes only.", 50f, y, textPaint)
        y += 14f
        canvas.drawText("Consult a qualified tax professional for accurate tax advice.", 50f, y, textPaint)

        document.finishPage(page)

        val filename = "AATE_TaxReport_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.pdf"
        val file = File(ctx.cacheDir, filename)
        document.writeTo(FileOutputStream(file))
        document.close()

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AATE Cryptocurrency Tax Report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Export in IRS Form 8949 compatible format
     */
    fun exportIrs8949(tokens: Map<String, TokenState>): Intent? {
        val entries = buildJournal(tokens)
        val sells = entries.filter { isSellLike(it.side) && !isInvalidAccounting(it) }
        if (sells.isEmpty()) return null

        val sdf = SimpleDateFormat("MM/dd/yyyy", Locale.US)
        val solPrice = try {
            BotService.instance?.currencyManager?.getSolUsd() ?: 0.0
        } catch (_: Exception) {
            0.0
        }

        val sb = StringBuilder()

        sb.appendLine("IRS FORM 8949 - SALES AND DISPOSITIONS OF CAPITAL ASSETS")
        sb.appendLine("Part I - Short-Term Capital Gains and Losses (Assets Held One Year or Less)")
        sb.appendLine("Check Box A, B, or C: [X] Box A - Short-term transactions reported on Form 1099-B")
        sb.appendLine("")
        sb.appendLine("(a) Description of property,(b) Date acquired,(c) Date sold,(d) Proceeds (USD),(e) Cost basis (USD),(f) Code,(g) Adjustment,(h) Gain or (loss)")

        val tradesByMint = entries.groupBy { it.mint }

        for (sell in sells) {
            val buyForThisSell = tradesByMint[sell.mint]
                ?.filter { it.side == "BUY" && it.ts < sell.ts }
                ?.maxByOrNull { it.ts }

            val dateAcquired = if (buyForThisSell != null) sdf.format(Date(buyForThisSell.ts)) else "VARIOUS"
            val dateSold = sdf.format(Date(sell.ts))

            val costBasisUsd = sell.solAmount * solPrice
            val proceedsUsd = (sell.solAmount + sell.pnlSol) * solPrice
            val gainLossUsd = sell.pnlSol * solPrice

            val description = "${String.format("%.6f", sell.solAmount)} SOL - ${sell.symbol} (Solana)"

            sb.appendLine(
                listOf(
                    description.csvEscape(),
                    dateAcquired,
                    dateSold,
                    String.format("%.2f", proceedsUsd),
                    String.format("%.2f", costBasisUsd),
                    "",
                    "",
                    String.format("%.2f", gainLossUsd)
                ).joinToString(",")
            )
        }

        val totalProceeds = sells.sumOf { (it.solAmount + it.pnlSol) * solPrice }
        val totalCostBasis = sells.sumOf { it.solAmount * solPrice }
        val totalGainLoss = sells.sumOf { it.pnlSol * solPrice }

        sb.appendLine("")
        sb.appendLine("TOTALS:,,,$${String.format("%.2f", totalProceeds)},\$${String.format("%.2f", totalCostBasis)},,,\$${String.format("%.2f", totalGainLoss)}")
        sb.appendLine("")
        sb.appendLine("═══════════════════════════════════════════════════════════════════")
        sb.appendLine("NOTES FOR TAX PREPARER:")
        sb.appendLine("- All transactions are SHORT-TERM (held less than 1 year)")
        sb.appendLine("- Asset type: Cryptocurrency (Solana network tokens)")
        sb.appendLine("- Trading via: Autonomous Algorithmic Trading Engine (AATE)")
        sb.appendLine("- SOL/USD rate at export: \$${String.format("%.2f", solPrice)}")
        sb.appendLine("- Report generated: ${SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.US).format(Date())}")
        sb.appendLine("")
        sb.appendLine("DISCLAIMER: This is a summary for informational purposes.")
        sb.appendLine("Consult IRS Publication 544 and a qualified tax professional.")

        val filename = "AATE_IRS8949_${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.csv"
        val file = File(ctx.cacheDir, filename)
        file.writeText(sb.toString())

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AATE IRS Form 8949 Export")
            putExtra(Intent.EXTRA_TEXT, "IRS Form 8949 compatible cryptocurrency trade report - ${sells.size} transactions")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Export all formats
     */
    fun exportAll(tokens: Map<String, TokenState>): List<Pair<String, Intent>> {
        val results = mutableListOf<Pair<String, Intent>>()

        exportCsv(tokens)?.let { results.add("CSV Spreadsheet" to it) }
        exportPdf(tokens)?.let { results.add("PDF Tax Report" to it) }
        exportIrs8949(tokens)?.let { results.add("IRS Form 8949" to it) }

        return results
    }

    private fun buildKey(ts: Long, mint: String, side: String): String {
        return "${ts}_${mint}_$side"
    }

    private fun isWin(pnlPct: Double): Boolean = pnlPct >= WIN_THRESHOLD_PCT

    private fun isLoss(pnlPct: Double): Boolean = pnlPct <= LOSS_THRESHOLD_PCT

    private fun isScratch(pnlPct: Double): Boolean = !isWin(pnlPct) && !isLoss(pnlPct)

    private fun isDecisive(pnlPct: Double): Boolean = isWin(pnlPct) || isLoss(pnlPct)

    private fun String.csvEscape(): String {
        return if (contains(",") || contains("\"") || contains("\n")) {
            "\"${replace("\"", "\"\"")}\""
        } else {
            this
        }
    }
}