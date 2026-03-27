package com.lifecyclebot.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.lifecyclebot.data.Trade
import com.lifecyclebot.data.TokenState
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * TradeJournal
 *
 * Aggregates all trades across all tokens and exports them as a CSV.
 * TAX-FRIENDLY FORMAT for accountants:
 * - Date/Time, Token Symbol, Token Address (Mint), Transaction Type (BUY/SELL)
 * - Quantity (SOL), Price (USD), Cost Basis (USD), Proceeds (USD)
 * - Gain/Loss (USD), Gain/Loss (%), Fee (SOL), Net Gain (USD)
 * - Trading Mode, Hold Duration, Notes
 *
 * CSV is saved to the app's cache dir and shared via Android's share sheet
 * so you can open it in Google Sheets, Excel, etc.
 */
class TradeJournal(private val ctx: Context) {

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
        val mode: String,  // paper or live
        val score: Double,
        val durationMins: Double,
        val phase: String,
        val tradingMode: String,      // MOONSHOT, PUMP_SNIPER, etc.
        val tradingModeEmoji: String, // 🚀, 🔫, etc.
        val feeSol: Double,           // Transaction fees
        val netPnlSol: Double,        // P&L after fees
    )

    fun buildJournal(tokens: Map<String, TokenState>): List<JournalEntry> {
        val entries = mutableListOf<JournalEntry>()

        tokens.values.forEach { ts ->
            ts.trades.forEach { trade ->
                entries.add(JournalEntry(
                    ts           = trade.ts,
                    symbol       = ts.symbol,
                    mint         = ts.mint,
                    side         = trade.side,
                    entryPrice   = trade.price,
                    exitPrice    = if (trade.side == "SELL") trade.price else 0.0,
                    solAmount    = trade.sol,
                    pnlSol       = trade.pnlSol,
                    pnlPct       = trade.pnlPct,
                    reason       = trade.reason,
                    mode         = trade.mode,
                    score        = trade.score,
                    durationMins = 0.0,
                    phase        = "",
                    tradingMode      = trade.tradingMode,
                    tradingModeEmoji = trade.tradingModeEmoji,
                    feeSol           = trade.feeSol,
                    netPnlSol        = trade.netPnlSol,
                ))
            }
        }
        return entries.sortedByDescending { it.ts }
    }

    /**
     * Export as TAX-FRIENDLY CSV for accountants.
     * Opens in Excel, Google Sheets, Numbers, etc.
     */
    fun exportCsv(tokens: Map<String, TokenState>): Intent? {
        val entries = buildJournal(tokens)
        if (entries.isEmpty()) return null

        val sdf  = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb   = StringBuilder()

        // Get current SOL price for USD conversion
        val solPrice = try {
            BotService.instance?.currencyManager?.getSolUsd() ?: 0.0
        } catch (e: Exception) { 0.0 }

        // TAX-FRIENDLY HEADER
        sb.appendLine(listOf(
            "Date/Time",
            "Token Symbol",
            "Token Address (Mint)",
            "Transaction Type",
            "Trading Mode",
            "Mode Emoji",
            "Quantity (SOL)",
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
            "Notes"
        ).joinToString(","))

        // ROWS - Each trade
        entries.forEach { e ->
            val priceUsd = e.entryPrice * solPrice
            val costBasisUsd = e.solAmount * solPrice
            val proceedsUsd = if (e.side == "SELL") (e.solAmount + e.pnlSol) * solPrice else 0.0
            val pnlUsd = e.pnlSol * solPrice
            val feeUsd = e.feeSol * solPrice
            val netPnlUsd = e.netPnlSol * solPrice
            
            sb.appendLine(listOf(
                sdf.format(Date(e.ts)),
                e.symbol.csvEscape(),
                e.mint,
                e.side,
                e.tradingMode.ifEmpty { "STANDARD" },
                e.tradingModeEmoji.ifEmpty { "📈" },
                "%.6f".format(e.solAmount),
                "%.10f".format(priceUsd),
                "%.2f".format(costBasisUsd),
                "%.2f".format(proceedsUsd),
                "%.6f".format(e.pnlSol),
                "%.2f".format(pnlUsd),
                "%.2f".format(e.pnlPct),
                "%.6f".format(e.feeSol),
                "%.2f".format(feeUsd),
                "%.6f".format(e.netPnlSol),
                "%.2f".format(netPnlUsd),
                e.mode.uppercase(),
                "%.0f".format(e.score),
                e.reason.csvEscape(),
                "Trading bot automated trade"
            ).joinToString(","))
        }
        
        // Add summary section at the bottom
        val sells = entries.filter { it.side == "SELL" }
        val buys = entries.filter { it.side == "BUY" }
        val totalPnlSol = sells.sumOf { it.pnlSol }
        val totalPnlUsd = totalPnlSol * solPrice
        val totalFeeSol = sells.sumOf { it.feeSol }
        val totalFeeUsd = totalFeeSol * solPrice
        val totalVolumeSol = entries.sumOf { it.solAmount }
        val totalVolumeUsd = totalVolumeSol * solPrice
        
        sb.appendLine("")
        sb.appendLine("=== SUMMARY FOR TAX REPORTING ===")
        sb.appendLine("Report Generated,${sdf.format(Date())}")
        sb.appendLine("SOL Price at Export,\$${String.format("%.2f", solPrice)}")
        sb.appendLine("")
        sb.appendLine("Total Trades,${entries.size}")
        sb.appendLine("Buy Transactions,${buys.size}")
        sb.appendLine("Sell Transactions,${sells.size}")
        sb.appendLine("")
        sb.appendLine("Total Volume (SOL),${String.format("%.4f", totalVolumeSol)}")
        sb.appendLine("Total Volume (USD),\$${String.format("%.2f", totalVolumeUsd)}")
        sb.appendLine("")
        sb.appendLine("Realized Gain/Loss (SOL),${String.format("%.6f", totalPnlSol)}")
        sb.appendLine("Realized Gain/Loss (USD),\$${String.format("%.2f", totalPnlUsd)}")
        sb.appendLine("")
        sb.appendLine("Total Fees (SOL),${String.format("%.6f", totalFeeSol)}")
        sb.appendLine("Total Fees (USD),\$${String.format("%.2f", totalFeeUsd)}")
        sb.appendLine("")
        sb.appendLine("Net Realized Gain/Loss (USD),\$${String.format("%.2f", totalPnlUsd - totalFeeUsd)}")
        sb.appendLine("")
        sb.appendLine("DISCLAIMER: This report is for informational purposes only. Consult a tax professional for accurate tax advice.")

        // Write to cache file
        val filename = "lifecycle_trades_${SimpleDateFormat("yyyyMMdd_HHmm",Locale.US).format(Date())}.csv"
        val file = File(ctx.cacheDir, filename)
        file.writeText(sb.toString())

        // Build share intent
        val uri = FileProvider.getUriForFile(
            ctx,
            "${ctx.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type    = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AATE Trade Journal - Tax Report")
            putExtra(Intent.EXTRA_TEXT, "${entries.size} trades exported for tax reporting")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Summary stats for the journal screen */
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

    fun getStats(tokens: Map<String, TokenState>): JournalStats {
        val sells = buildJournal(tokens).filter { it.side == "SELL" }
        val meaningfulTrades = sells.filter { it.pnlPct < -2.0 || it.pnlPct > 2.0 }
        val scratchTrades = sells.filter { it.pnlPct >= -2.0 && it.pnlPct <= 2.0 }
        val wins  = meaningfulTrades.filter { it.pnlPct > 2.0 }
        val loss  = meaningfulTrades.filter { it.pnlPct < -2.0 }
        return JournalStats(
            totalTrades   = meaningfulTrades.size,
            totalWins     = wins.size,
            totalLosses   = loss.size,
            winRate       = if (meaningfulTrades.isNotEmpty()) wins.size.toDouble() / meaningfulTrades.size * 100 else 0.0,
            totalPnlSol   = sells.sumOf { it.pnlSol },
            bestTrade     = sells.maxByOrNull { it.pnlPct },
            worstTrade    = sells.minByOrNull { it.pnlPct },
            avgWinPct     = if (wins.isNotEmpty()) wins.map { it.pnlPct }.average() else 0.0,
            avgLossPct    = if (loss.isNotEmpty()) loss.map { it.pnlPct }.average() else 0.0,
            totalVolumeSol = sells.sumOf { it.solAmount },
            scratchCount  = scratchTrades.size,
        )
    }

    private fun String.csvEscape(): String =
        if (contains(",") || contains("\"") || contains("\n"))
            "\"${replace("\"", "\\\"")}\""
        else this
}
