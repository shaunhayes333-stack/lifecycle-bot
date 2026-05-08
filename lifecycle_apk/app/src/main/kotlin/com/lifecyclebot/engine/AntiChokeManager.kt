package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.withTimeoutOrNull

/**
 * V5.9.612 — AntiChokeManager
 *
 * This is not another entry gate. It is the bot's anti-deadlock immune system:
 * watches trade velocity, ghost positions, watchlist dormancy, and copilot/protect
 * pressure. If throughput falls below the 500+/day target, it relaxes soft gates
 * and clears stale internal state while leaving real anti-rug / anti-drain safety
 * rails intact.
 */
object AntiChokeManager {
    private const val TAG = "AntiChoke"
    private const val TARGET_TRADES_PER_DAY = 500
    private const val TARGET_TRADES_PER_HOUR = TARGET_TRADES_PER_DAY / 24.0
    private const val TARGET_MS_PER_TRADE = (86_400_000L / TARGET_TRADES_PER_DAY) // ~172.8s
    private const val CHECK_EVERY_MS = 20_000L
    private const val GHOST_MIN_AGE_MS = 90_000L
    private const val DORMANT_GRACE_MS = 90_000L
    private const val DORMANT_CHOKE_MS = 6 * 60_000L
    private const val MAX_PRUNE_PER_CHECK = 35

    enum class Level { CLEAR, WATCH, SOFTEN, RECOVERY }

    data class Result(
        val level: Level,
        val trades24h: Int,
        val target24h: Int,
        val watchlistSize: Int,
        val openInternal: Int,
        val openWallet: Int,
        val ghostsCleared: Int,
        val dormantPruned: Int,
        val message: String,
    )

    @Volatile private var level: Level = Level.CLEAR
    @Volatile private var lastCheckMs: Long = 0L
    @Volatile private var lastTradeCount24h: Int = -1
    @Volatile private var lastTradeProgressMs: Long = System.currentTimeMillis()
    @Volatile private var lastResult: Result = Result(Level.CLEAR, 0, TARGET_TRADES_PER_DAY, 0, 0, 0, 0, 0, "init")
    @Volatile private var consecutiveEmptyWalletSnapshots: Int = 0

    fun currentLevel(): Level = level
    fun isSoftening(): Boolean = level == Level.SOFTEN || level == Level.RECOVERY
    fun statusLine(): String = lastResult.message

    suspend fun tick(
        isPaperMode: Boolean,
        wallet: SolanaWallet?,
        tokens: MutableMap<String, TokenState>,
        loopCount: Int,
    ): Result? {
        val now = System.currentTimeMillis()
        if (now - lastCheckMs < CHECK_EVERY_MS) return null
        lastCheckMs = now

        val trades24h = try { TradeHistoryStore.getTradeCount24h() } catch (_: Throwable) { 0 }
        if (trades24h != lastTradeCount24h) {
            lastTradeCount24h = trades24h
            lastTradeProgressMs = now
        }

        val stagnantMs = now - lastTradeProgressMs
        val projectedDaily = projectedDailyTrades(trades24h, now)
        val watchlistSize = try { GlobalTradeRegistry.size() } catch (_: Throwable) { 0 }
        val openInternal = internalOpenCount(tokens)
        val openWalletBefore = walletOpenCount(isPaperMode)

        val starving = stagnantMs > TARGET_MS_PER_TRADE * 2 || projectedDaily < TARGET_TRADES_PER_DAY * 0.70
        val clogged = watchlistSize > 80 && openInternal < 8 && stagnantMs > TARGET_MS_PER_TRADE
        val ghostPressure = openInternal >= 12 && openWalletBefore <= 2 && !isPaperMode

        level = when {
            stagnantMs > TARGET_MS_PER_TRADE * 5 || ghostPressure -> Level.RECOVERY
            starving || clogged -> Level.SOFTEN
            projectedDaily < TARGET_TRADES_PER_DAY * 0.90 -> Level.WATCH
            else -> Level.CLEAR
        }

        var ghosts = 0
        var pruned = 0
        if (level == Level.SOFTEN || level == Level.RECOVERY) {
            ghosts += clearGhostInternalPositions(isPaperMode, wallet, tokens, now)
            pruned += pruneDormantWatchlist(tokens, now, aggressive = level == Level.RECOVERY)
            try { TradingCopilot.clearDemotionWeights() } catch (_: Throwable) {}
        }

        val openWalletAfter = walletOpenCount(isPaperMode)
        val msg = "${level.name}: trades24h=$trades24h/$TARGET_TRADES_PER_DAY projected=${projectedDaily.toInt()} " +
            "stagnant=${stagnantMs/1000}s watch=$watchlistSize openInternal=$openInternal walletOpen=$openWalletAfter " +
            "ghosts=$ghosts pruned=$pruned"
        lastResult = Result(level, trades24h, TARGET_TRADES_PER_DAY, watchlistSize, openInternal, openWalletAfter, ghosts, pruned, msg)
        ErrorLogger.info(TAG, msg)
        return lastResult
    }

    private fun projectedDailyTrades(trades24h: Int, now: Long): Double {
        val dayMs = 86_400_000L
        val elapsedToday = (now % dayMs).coerceAtLeast(1L)
        return trades24h.toDouble() * dayMs.toDouble() / elapsedToday.toDouble()
    }

    private fun internalOpenCount(tokens: MutableMap<String, TokenState>): Int {
        val tokenOpen = synchronized(tokens) { tokens.values.count { it.position.hasTokens || it.position.pendingVerify } }
        val registryOpen = try { GlobalTradeRegistry.getPositionCount() } catch (_: Throwable) { 0 }
        val lifecycleOpen = try { TokenLifecycleTracker.openCount() } catch (_: Throwable) { 0 }
        return maxOf(tokenOpen, registryOpen, lifecycleOpen)
    }

    private fun walletOpenCount(isPaperMode: Boolean): Int {
        if (isPaperMode) return try { GlobalTradeRegistry.getPositionCount() } catch (_: Throwable) { 0 }
        return try { HostWalletTokenTracker.getActuallyHeldCount() } catch (_: Throwable) { 0 }
    }

    private suspend fun clearGhostInternalPositions(
        isPaperMode: Boolean,
        wallet: SolanaWallet?,
        tokens: MutableMap<String, TokenState>,
        now: Long,
    ): Int {
        var cleared = 0
        val mints = mutableSetOf<String>()
        synchronized(tokens) {
            tokens.values.filter { it.position.hasTokens || it.position.pendingVerify }.forEach { mints.add(it.mint) }
        }
        try { GlobalTradeRegistry.getOpenPositions().forEach { mints.add(it.mint) } } catch (_: Throwable) {}
        try { TokenLifecycleTracker.all().filter { it.status.name !in setOf("CLEARED", "RECONCILE_FAILED") }.forEach { mints.add(it.mint) } } catch (_: Throwable) {}

        var walletMap: Map<String, Pair<Double, Int>>? = null
        if (!isPaperMode && wallet != null) {
            walletMap = withTimeoutOrNull(7_000L) { wallet.getTokenAccountsWithDecimals() }
            if (walletMap == null) return 0
            if (walletMap.isEmpty()) {
                // Empty token-account maps are ambiguous: could be a truly empty
                // wallet or an RPC glitch. Only trust repeated empty maps when a
                // separate SOL balance call succeeds, proving the wallet/RPC path
                // is alive. This is the specific "bot thinks 38, wallet empty"
                // recovery path without trusting a single bad response.
                val solOk = try { (withTimeoutOrNull(4_000L) { wallet.getSolBalance() } ?: -1.0) >= 0.0 } catch (_: Throwable) { false }
                consecutiveEmptyWalletSnapshots = if (solOk) consecutiveEmptyWalletSnapshots + 1 else 0
                if (consecutiveEmptyWalletSnapshots < 3 || level != Level.RECOVERY) return 0
                walletMap = emptyMap()
            } else {
                consecutiveEmptyWalletSnapshots = 0
            }
            val confirmedWalletMap = walletMap ?: return 0
            try { HostWalletTokenTracker.applyWalletSnapshot(confirmedWalletMap) } catch (_: Throwable) {}
            walletMap = confirmedWalletMap
        }

        for (mint in mints) {
            val ts = synchronized(tokens) { tokens[mint] }
            val posAge = ts?.position?.entryTime?.takeIf { it > 0 }?.let { now - it } ?: Long.MAX_VALUE
            if (posAge < GHOST_MIN_AGE_MS) continue

            val actuallyHeld = if (isPaperMode) {
                val tokenHasQty = ts?.position?.hasTokens == true || ts?.position?.pendingVerify == true
                tokenHasQty || hasAnySubTraderPositionExcludingRegistry(mint)
            } else {
                val ui = walletMap?.get(mint)?.first ?: 0.0
                ui > 0.000001 || HostWalletTokenTracker.isActuallyHeld(mint)
            }
            if (actuallyHeld) continue

            if (ts != null) {
                synchronized(tokens) { tokens[mint]?.position = Position() }
            }
            try { GlobalTradeRegistry.closePosition(mint) } catch (_: Throwable) {}
            if (!isPaperMode) {
                try { TokenLifecycleTracker.forceClearUnheld(mint, "anti_choke_wallet_zero") } catch (_: Throwable) {}
                try { HostWalletTokenTracker.markUnheldByAntiChoke(mint, "wallet_zero") } catch (_: Throwable) {}
            }
            cleared++
            ErrorLogger.warn(TAG, "👻 cleared ghost hold ${ts?.symbol ?: mint.take(8)} mint=${mint.take(8)}… paper=$isPaperMode")
        }
        return cleared
    }

    private fun hasAnySubTraderPosition(mint: String): Boolean = try {
        GlobalTradeRegistry.hasOpenPosition(mint) || hasAnySubTraderPositionExcludingRegistry(mint)
    } catch (_: Throwable) { false }

    private fun hasAnySubTraderPositionExcludingRegistry(mint: String): Boolean = try {
        com.lifecyclebot.v3.scoring.QualityTraderAI.hasPosition(mint) ||
            com.lifecyclebot.v3.scoring.BlueChipTraderAI.hasPosition(mint) ||
            com.lifecyclebot.v3.scoring.ShitCoinTraderAI.hasPosition(mint) ||
            com.lifecyclebot.v3.scoring.MoonshotTraderAI.hasPosition(mint) ||
            com.lifecyclebot.v3.scoring.ManipulatedTraderAI.hasPosition(mint) ||
            com.lifecyclebot.v3.scoring.CashGenerationAI.hasPosition(mint) ||
            com.lifecyclebot.v3.scoring.DipHunterAI.hasDip(mint) ||
            com.lifecyclebot.v3.scoring.ShitCoinExpress.hasRide(mint)
    } catch (_: Throwable) { false }

    private fun pruneDormantWatchlist(tokens: MutableMap<String, TokenState>, now: Long, aggressive: Boolean): Int {
        val entries = try { GlobalTradeRegistry.getWatchlistEntries() } catch (_: Throwable) { emptyList() }
        if (entries.size < 60) return 0
        val maxAge = if (aggressive) DORMANT_CHOKE_MS else DORMANT_CHOKE_MS * 2
        val candidates = entries
            .asSequence()
            .filter { now - it.addedAt > DORMANT_GRACE_MS }
            .filter { !hasAnySubTraderPosition(it.mint) }
            .filter { !HostWalletTokenTracker.hasOpenPosition(it.mint) }
            .mapNotNull { e ->
                val ts = synchronized(tokens) { tokens[e.mint] }
                val tokenAge = now - (ts?.addedToWatchlistAt ?: e.addedAt)
                val noTrade = ts?.trades?.isEmpty() ?: true
                val weakHistory = (ts?.history?.size ?: 0) < 3
                val stale = tokenAge > maxAge || (ts != null && noTrade && weakHistory && tokenAge > DORMANT_CHOKE_MS)
                if (stale) e else null
            }
            .sortedBy { it.addedAt }
            .take(MAX_PRUNE_PER_CHECK)
            .toList()

        var pruned = 0
        for (e in candidates) {
            val removed = try { GlobalTradeRegistry.removeFromWatchlistForced(e.mint, "ANTI_CHOKE_DORMANT") } catch (_: Throwable) { false }
            if (removed) {
                synchronized(tokens) { tokens.remove(e.mint) }
                pruned++
            }
        }
        return pruned
    }
}
