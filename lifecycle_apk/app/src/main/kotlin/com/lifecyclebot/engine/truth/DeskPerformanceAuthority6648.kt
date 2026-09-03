package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.StrategyTruthLedger
import com.lifecyclebot.engine.TradeHistoryStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6648 — fail-closed desk/book performance projection.
 *
 * Performance is never inferred from the screen that asks for it. Every clean
 * terminal is assigned to exactly one explicit book. Unknown legacy rows stay
 * in UNCLASSIFIED and cannot affect a desk WR, streak, reward, sizing or PnL.
 * PORTFOLIO is the only deliberately mixed projection and remains named as such.
 */
object DeskPerformanceAuthority6648 {
    enum class Book { MEME, CRYPTO, PERPS, STOCKS, FOREX, METALS, COMMODITIES, UNCLASSIFIED, PORTFOLIO }

    data class Snapshot(
        val book: Book,
        val mode: String = "paper",
        val trades: Int = 0,
        val wins: Int = 0,
        val losses: Int = 0,
        val scratches: Int = 0,
        val realizedPnlSol: Double? = null,
        val profitFactor: Double = 0.0,
        val avgWinPct: Double = 0.0,
        val avgLossPct: Double = 0.0,
        val refreshedAtMs: Long = 0L,
        val source: String = "WARMUP",
    ) {
        val decisive: Int get() = wins + losses
        val winRate: Double get() = if (decisive > 0) wins * 100.0 / decisive else 0.0
        val ready: Boolean get() = refreshedAtMs > 0L
    }

    private val cache = ConcurrentHashMap<String, Snapshot>()
    private val refreshRunning = AtomicBoolean(false)
    private val lastRefreshMs = AtomicLong(0L)
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DeskPerformance6648").apply { isDaemon = true }
    }

    fun snapshot(book: Book, mode: String = "paper"): Snapshot {
        refreshAsync()
        val normalizedMode = requestedMode(mode)
        return cache[key(book, normalizedMode)] ?: Snapshot(book = book, mode = normalizedMode)
    }

    fun refreshAsync() {
        if (System.currentTimeMillis() - lastRefreshMs.get() < 15_000L) return
        if (!refreshRunning.compareAndSet(false, true)) return
        io.execute {
            try {
                refreshNow()
                lastRefreshMs.set(System.currentTimeMillis())
            } finally { refreshRunning.set(false) }
        }
    }

    internal fun classify(row: Trade): Book {
        val byId = AssetClass.fromPositionIdPrefix(row.positionId)
        val byLane = AssetClass.fromLane(row.tradingMode)
        val assetClass = when {
            byId != AssetClass.UNKNOWN -> byId
            byLane != AssetClass.UNKNOWN -> byLane
            else -> explicitLegacyMode(row.tradingMode)
        }
        return when (assetClass) {
            AssetClass.SOLANA_TOKEN -> Book.MEME
            AssetClass.CRYPTO_ALT -> Book.CRYPTO
            AssetClass.PERPS -> Book.PERPS
            AssetClass.STOCK -> Book.STOCKS
            AssetClass.FOREX -> Book.FOREX
            AssetClass.METAL -> Book.METALS
            AssetClass.COMMODITY -> Book.COMMODITIES
            AssetClass.UNKNOWN -> Book.UNCLASSIFIED
        }
    }

    private fun explicitLegacyMode(mode: String): AssetClass {
        val value = mode.trim().uppercase()
        return when {
            value.startsWith("STOCK") || value.startsWith("MARKETS_STOCK") -> AssetClass.STOCK
            value.startsWith("FOREX") || value.startsWith("FX_") -> AssetClass.FOREX
            value.startsWith("METAL") -> AssetClass.METAL
            value.startsWith("COMMOD") -> AssetClass.COMMODITY
            value.startsWith("PERP") || value.startsWith("MARKETS_PERP") -> AssetClass.PERPS
            value.startsWith("ALT") || value.startsWith("CRYPTO_ALT") || value.startsWith("CRYPTO_UNIVERSE") -> AssetClass.CRYPTO_ALT
            else -> AssetClass.UNKNOWN
        }
    }

    internal fun reduce(rows: List<Trade>, mode: String, accountingAvailable: Boolean): Map<Book, Snapshot> {
        val now = System.currentTimeMillis()
        val normalizedMode = requestedMode(mode)
        val modeRows = rows.filter { rowMode(it.mode) == normalizedMode }
        val grouped = modeRows.groupBy(::classify)
        val deskBooks = Book.values().filter { it != Book.PORTFOLIO }
        val reduced = LinkedHashMap<Book, Snapshot>()
        for (book in deskBooks) {
            val bookRows = grouped[book].orEmpty()
            val pnl = if (accountingAvailable) bookRows.sumOf(::economicPnl) else null
            val wins = bookRows.count { economicPnl(it) > 0.0 }
            val losses = bookRows.count { economicPnl(it) < 0.0 }
            val grossWin = bookRows.sumOf { economicPnl(it).coerceAtLeast(0.0) }
            val grossLoss = -bookRows.sumOf { economicPnl(it).coerceAtMost(0.0) }
            val winPcts = bookRows.filter { economicPnl(it) > 0.0 }.map { it.pnlPct }
            val lossPcts = bookRows.filter { economicPnl(it) < 0.0 }.map { it.pnlPct }
            reduced[book] = Snapshot(
                book = book,
                mode = normalizedMode,
                trades = bookRows.size,
                wins = wins,
                losses = losses,
                scratches = bookRows.size - wins - losses,
                realizedPnlSol = pnl,
                profitFactor = if (grossLoss > 0.0) grossWin / grossLoss else if (grossWin > 0.0) 9.99 else 0.0,
                avgWinPct = winPcts.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                avgLossPct = lossPcts.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                refreshedAtMs = now,
                source = "STRATEGY_TRUTH_LEDGER:${if (accountingAvailable) "RECONCILED" else "ACCOUNT_UNAVAILABLE"}",
            )
        }
        val portfolioRows = modeRows.filter { classify(it) != Book.UNCLASSIFIED }
        val portfolioWins = portfolioRows.count { economicPnl(it) > 0.0 }
        val portfolioLosses = portfolioRows.count { economicPnl(it) < 0.0 }
        val portfolioGrossWin = portfolioRows.sumOf { economicPnl(it).coerceAtLeast(0.0) }
        val portfolioGrossLoss = -portfolioRows.sumOf { economicPnl(it).coerceAtMost(0.0) }
        val portfolioWinPcts = portfolioRows.filter { economicPnl(it) > 0.0 }.map { it.pnlPct }
        val portfolioLossPcts = portfolioRows.filter { economicPnl(it) < 0.0 }.map { it.pnlPct }
        reduced[Book.PORTFOLIO] = Snapshot(
            book = Book.PORTFOLIO,
            mode = normalizedMode,
            trades = portfolioRows.size,
            wins = portfolioWins,
            losses = portfolioLosses,
            scratches = portfolioRows.size - portfolioWins - portfolioLosses,
            realizedPnlSol = if (accountingAvailable) portfolioRows.sumOf(::economicPnl) else null,
            profitFactor = if (portfolioGrossLoss > 0.0) portfolioGrossWin / portfolioGrossLoss else if (portfolioGrossWin > 0.0) 9.99 else 0.0,
            avgWinPct = portfolioWinPcts.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
            avgLossPct = portfolioLossPcts.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
            refreshedAtMs = now,
            source = "EXPLICIT_PORTFOLIO:${if (accountingAvailable) "RECONCILED" else "ACCOUNT_UNAVAILABLE"}",
        )
        return reduced
    }

    private fun refreshNow() {
        val raw = TradeHistoryStore.getRecentValidClosedTradesRaw(limit = 50_000, includePartials = true)
        val clean = StrategyTruthLedger.clean(raw, raw.size).rows
        for (mode in listOf("paper", "live")) {
            val account = UnifiedAccountSnapshot6635.lastSnapshot()
            val available = mode == "live" ||
                (account.mode == mode && account.status == UnifiedAccountSnapshot6635.Status.RECONCILED && account.accountAvailable)
            reduce(clean, mode, available).forEach { (book, snapshot) -> cache[key(book, mode)] = snapshot }
        }
        val unclassified = listOf("paper", "live").sumOf { cache[key(Book.UNCLASSIFIED, it)]?.trades ?: 0 }
        if (unclassified > 0) {
            PipelineHealthCollector.labelInc("DESK_PERFORMANCE_UNCLASSIFIED_ROWS_6648")
            ErrorLogger.warn("DeskPerformance6648", "UNCLASSIFIED terminal rows=$unclassified excludedFromAllDeskStats=true")
        }
    }

    private fun economicPnl(row: Trade): Double = when {
        row.netPnlSol.isFinite() && row.netPnlSol != 0.0 -> row.netPnlSol
        row.pnlSol.isFinite() -> row.pnlSol
        else -> 0.0
    }

    private fun requestedMode(mode: String): String = if (mode.equals("live", true)) "live" else "paper"
    private fun rowMode(mode: String): String = when {
        mode.equals("paper", true) -> "paper"
        mode.equals("live", true) -> "live"
        else -> "unclassified"
    }
    private fun key(book: Book, mode: String): String = "${requestedMode(mode)}:${book.name}"

    internal fun resetForTest() {
        cache.clear()
        refreshRunning.set(false)
        lastRefreshMs.set(0L)
    }
}
