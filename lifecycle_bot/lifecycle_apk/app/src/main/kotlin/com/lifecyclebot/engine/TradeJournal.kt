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
 * Each row: timestamp, token, side, entry_price, exit_price, sol_amount,
 *           pnl_sol, pnl_pct, reason, mode, signal, entry_score, duration_mins
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
        val mode: String,
        val score: Double,
        val durationMins: Double,
        val phase: String,
    )

    fun buildJournal(tokens: Map<String, TokenState>): List<JournalEntry> {
        val entries = mutableListOf<JournalEntry>()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

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
                    durationMins = 0.0,   // would need entry timestamp tracking
                    phase        = "",
                ))
            }
        }
        return entries.sortedByDescending { it.ts }
    }

    fun exportCsv(tokens: Map<String, TokenState>): Intent? {
        val entries = buildJournal(tokens)
        if (entries.isEmpty()) return null

        val sdf  = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb   = StringBuilder()

        // Header
        sb.appendLine("Timestamp,Symbol,Mint,Side,Price,SOL Amount,PnL SOL,PnL %,Reason,Mode,Score")

        // Rows
        entries.forEach { e ->
            sb.appendLine(listOf(
                sdf.format(Date(e.ts)),
                e.symbol.csvEscape(),
                e.mint,
                e.side,
                "%.10f".format(e.entryPrice),
                "%.4f".format(e.solAmount),
                "%.4f".format(e.pnlSol),
                "%.2f".format(e.pnlPct),
                e.reason.csvEscape(),
                e.mode,
                "%.0f".format(e.score),
            ).joinToString(","))
        }

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
            putExtra(Intent.EXTRA_SUBJECT, "Lifecycle Bot Trade Journal")
            putExtra(Intent.EXTRA_TEXT, "${entries.size} trades exported")
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
    )

    fun getStats(tokens: Map<String, TokenState>): JournalStats {
        val sells = buildJournal(tokens).filter { it.side == "SELL" }
        val wins  = sells.filter { it.pnlSol > 0 }
        val loss  = sells.filter { it.pnlSol <= 0 }
        return JournalStats(
            totalTrades   = sells.size,
            totalWins     = wins.size,
            totalLosses   = loss.size,
            winRate       = if (sells.isNotEmpty()) wins.size.toDouble() / sells.size * 100 else 0.0,
            totalPnlSol   = sells.sumOf { it.pnlSol },
            bestTrade     = sells.maxByOrNull { it.pnlPct },
            worstTrade    = sells.minByOrNull { it.pnlPct },
            avgWinPct     = if (wins.isNotEmpty()) wins.map { it.pnlPct }.average() else 0.0,
            avgLossPct    = if (loss.isNotEmpty()) loss.map { it.pnlPct }.average() else 0.0,
            totalVolumeSol = sells.sumOf { it.solAmount },
        )
    }

    private fun String.csvEscape(): String =
        if (contains(",") || contains("\"") || contains("\n"))
            "\"${replace("\"", "\\\"")}\""
        else this
}
