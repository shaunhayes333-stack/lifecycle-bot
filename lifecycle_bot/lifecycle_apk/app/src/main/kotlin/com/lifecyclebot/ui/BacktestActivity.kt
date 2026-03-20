package com.lifecyclebot.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.data.*
import com.lifecyclebot.engine.*
import com.lifecyclebot.network.BirdeyeApi
import com.lifecyclebot.network.DexscreenerApi
import kotlinx.coroutines.*

/**
 * Backtest screen — runs the v4 strategy against real Birdeye OHLCV data.
 *
 * User flow:
 *   1. Enter any token mint address
 *   2. Choose timeframe and candle count
 *   3. Hit Run — fetches real candles, replays strategy, shows trade log
 *   4. See exact entry/exit points, P&L, which signals fired, win rate
 *
 * This is real validation against actual market data, not simulated candles.
 */
class BacktestActivity : AppCompatActivity() {

    private lateinit var etMint: EditText
    private lateinit var spinnerTf: Spinner
    private lateinit var etCandles: EditText
    private lateinit var etPositionSol: EditText
    private lateinit var btnRun: Button
    private lateinit var btnClear: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var cardBtSummary: android.view.View
    private lateinit var tvSummary: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: android.widget.ScrollView

    private val birdeye  = BirdeyeApi()
    private val dex      = DexscreenerApi()
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backtest)
        supportActionBar?.hide()

        etMint         = findViewById(R.id.etBtMint)
        spinnerTf      = findViewById(R.id.spinnerBtTf)
        etCandles      = findViewById(R.id.etBtCandles)
        etPositionSol  = findViewById(R.id.etBtPositionSol)
        btnRun         = findViewById(R.id.btnBtRun)
        btnClear       = findViewById(R.id.btnBtClear)
        progressBar    = findViewById(R.id.btProgress)
        cardBtSummary  = findViewById(R.id.cardBtSummary)
        tvSummary      = findViewById(R.id.tvBtSummary)
        tvLog          = findViewById(R.id.tvBtLog)
        scrollLog      = findViewById(R.id.scrollBtLog)

        val tfOptions = arrayOf("1m", "3m", "5m", "15m", "30m", "1H")
        spinnerTf.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, tfOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        btnRun.setOnClickListener { runBacktest() }
        btnClear.setOnClickListener {
            tvLog.text = ""; tvSummary.text = ""
            tvSummary.visibility = View.GONE
        }
    }

    private fun runBacktest() {
        val mint = etMint.text.toString().trim()
        if (mint.length < 32) {
            tvLog.text = "Enter a valid Solana mint address (44 chars)"; return
        }
        val tf         = spinnerTf.selectedItem.toString()
        val count      = etCandles.text.toString().toIntOrNull()?.coerceIn(50, 500) ?: 200
        val posSol     = etPositionSol.text.toString().toDoubleOrNull() ?: 0.10

        job?.cancel()
        progressBar.visibility = View.VISIBLE
        btnRun.isEnabled       = false
        tvLog.text             = "Fetching $count × $tf candles from Birdeye…"
        cardBtSummary.visibility = View.GONE

        job = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Fetch real candles
                val candles = birdeye.getCandles(mint, tf, count)
                if (candles.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        tvLog.text = "No candles found. Check the mint address and try again."
                        progressBar.visibility = View.GONE; btnRun.isEnabled = true
                    }
                    return@launch
                }

                // Get token info for display
                val overview = birdeye.getTokenOverview(mint)
                val symbol   = overview?.symbol ?: mint.take(8) + "…"

                // Replay strategy
                val result = replayStrategy(candles, posSol, symbol, tf)

                withContext(Dispatchers.Main) {
                    displayResults(result, symbol, candles.size, tf, posSol)
                    progressBar.visibility = View.GONE
                    btnRun.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvLog.text = "Error: ${e.message}"
                    progressBar.visibility = View.GONE; btnRun.isEnabled = true
                }
            }
        }
    }

    // ── Strategy replay ───────────────────────────────────────────────

    data class BacktestTrade(
        val type: String,      // BUY | SELL | TOP-UP | PARTIAL
        val candleIdx: Int,
        val price: Double,
        val sol: Double,
        val phase: String,
        val entryScore: Int,
        val exitScore: Int,
        val pnlPct: Double = 0.0,
        val signals: String = "",
    )

    data class BacktestResult(
        val trades: List<BacktestTrade>,
        val netSol: Double,
        val winRate: Int,
        val totalTrades: Int,
        val bestTrade: Double,
        val worstTrade: Double,
        val avgWin: Double,
        val avgLoss: Double,
    )

    private fun replayStrategy(
        candles: List<Candle>,
        posSol: Double,
        symbol: String,
        tf: String,
    ): BacktestResult {
        val cfg    = ConfigStore.load(this)
        val status = BotStatus()
        val ts     = TokenState(mint = "backtest", symbol = symbol)
        status.tokens["backtest"] = ts

        val strategy = LifecycleStrategy { cfg }
        val trades   = mutableListOf<BacktestTrade>()

        val mode = when (tf) {
            "1m", "3m"       -> "LAUNCH_OR_RANGE"
            "5m", "15m"      -> "RANGE"
            else             -> "RANGE"
        }

        for (i in candles.indices) {
            val candle = candles[i]
            synchronized(ts.history) {
                ts.history.addLast(candle)
                if (ts.history.size > 300) ts.history.removeFirst()
            }
            ts.lastPrice = candle.priceUsd
            ts.lastMcap  = candle.marketCap

            if (i < 22) continue  // warmup

            val result = strategy.evaluate(ts)
            val meta   = result.meta

            // Active signals summary for log
            val sigs = buildList {
                if (meta.exhaustion)              add("EXHAUST")
                if (meta.spikeDetected)           add("SPIKE")
                if (meta.emafanAlignment=="BULL_FAN") add("BULL_FAN")
                if (meta.emafanAlignment=="BEAR_FAN") add("BEAR_FAN")
                if (meta.topUpReady)              add("TOP-UP")
            }.joinToString(" ")

            // Simulate trades
            if (result.signal == "BUY" && !ts.position.isOpen) {
                val size = posSol
                ts.position = Position(
                    qtyToken     = size / maxOf(candle.marketCap, 1e-12),
                    entryPrice   = candle.marketCap,
                    entryTime    = System.currentTimeMillis() - (candles.size - i) * 60_000L,
                    costSol      = size,
                    highestPrice = candle.marketCap,
                    entryPhase   = result.phase,
                )
                trades.add(BacktestTrade("BUY", i, candle.marketCap, size,
                    result.phase, result.entryScore.toInt(), result.exitScore.toInt(),
                    signals = sigs))
            } else if (result.signal in listOf("EXIT","SELL") && ts.position.isOpen) {
                val gain = if (ts.position.entryPrice > 0)
                    (candle.marketCap - ts.position.entryPrice) / ts.position.entryPrice * 100.0
                else 0.0
                val pnlSol = ts.position.costSol * gain / 100.0
                trades.add(BacktestTrade("SELL", i, candle.marketCap, ts.position.costSol,
                    result.phase, result.entryScore.toInt(), result.exitScore.toInt(),
                    pnlPct = gain, signals = sigs))
                ts.position      = Position()
                ts.lastExitTs    = System.currentTimeMillis()
                ts.lastExitPrice = candle.marketCap
            }
        }

        // Close open position at end
        if (ts.position.isOpen && candles.isNotEmpty()) {
            val lastCandle = candles.last()
            val gain = if (ts.position.entryPrice > 0)
                (lastCandle.marketCap - ts.position.entryPrice) / ts.position.entryPrice * 100.0
            else 0.0
            trades.add(BacktestTrade("SELL(end)", candles.size - 1, lastCandle.marketCap,
                ts.position.costSol, "end_of_data", 0, 0, pnlPct = gain))
        }

        // Calculate P&L
        val sells   = trades.filter { it.type.startsWith("SELL") }
        val wins    = sells.filter { it.pnlPct > 0 }
        val losses  = sells.filter { it.pnlPct <= 0 }
        val netSol  = sells.sumOf { it.sol * it.pnlPct / 100.0 }
        val wr      = if (sells.isNotEmpty()) wins.size * 100 / sells.size else 0

        return BacktestResult(
            trades       = trades,
            netSol       = netSol,
            winRate      = wr,
            totalTrades  = sells.size,
            bestTrade    = wins.maxOfOrNull { it.pnlPct } ?: 0.0,
            worstTrade   = losses.minOfOrNull { it.pnlPct } ?: 0.0,
            avgWin       = if (wins.isNotEmpty()) wins.map { it.pnlPct }.average() else 0.0,
            avgLoss      = if (losses.isNotEmpty()) losses.map { it.pnlPct }.average() else 0.0,
        )
    }

    private fun displayResults(r: BacktestResult, symbol: String,
                                candleCount: Int, tf: String, posSol: Double) {
        val solPrice = 130.0  // approximate

        cardBtSummary.visibility = View.VISIBLE
        tvSummary.text = buildString {
            appendLine("━━━ $symbol  $candleCount×$tf  ${posSol}◎/trade ━━━")
            appendLine("Trades: ${r.totalTrades}  Win rate: ${r.winRate}%")
            appendLine("NET: ${"%.4f".format(r.netSol)}◎  (${"%.2f".format(r.netSol * solPrice)} USD)")
            if (r.totalTrades > 0) {
                appendLine("Avg win: ${"%.1f".format(r.avgWin)}%  |  Avg loss: ${"%.1f".format(r.avgLoss)}%")
                appendLine("Best: ${"%.1f".format(r.bestTrade)}%  |  Worst: ${"%.1f".format(r.worstTrade)}%")
            }
        }

        val log = buildString {
            r.trades.forEach { t ->
                val pnlStr = if (t.pnlPct != 0.0) "  P&L: ${"%.1f".format(t.pnlPct)}%" else ""
                val icon = when (t.type) {
                    "BUY"     -> "📈"
                    "SELL", "SELL(end)" -> if (t.pnlPct > 0) "✅" else "❌"
                    else      -> "·"
                }
                appendLine("$icon [${t.candleIdx}] ${t.type.padEnd(8)} ${t.phase.padEnd(16)} " +
                    "E:${t.entryScore.toString().padStart(3)} X:${t.exitScore.toString().padStart(3)}" +
                    "$pnlStr  ${t.signals}")
            }
            if (r.trades.isEmpty()) appendLine("No trades fired in this period.")
        }
        tvLog.text = log
        scrollLog.post { scrollLog.smoothScrollTo(0, 0) }
    }
}
