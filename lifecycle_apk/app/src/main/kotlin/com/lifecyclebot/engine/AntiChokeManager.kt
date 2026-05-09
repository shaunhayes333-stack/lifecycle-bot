package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.withTimeoutOrNull

/**
 * V5.9.612 — AntiChokeManager
 *
 * This is not another entry gate. It is the bot's anti-deadlock immune system:
 * watches trade velocity, ghost positions, and copilot/protect pressure.
 * If throughput falls below the 500+/day target, it relaxes soft upstream gates
 * and clears stale internal position state while leaving real anti-rug / anti-drain
 * safety rails intact.
 *
 * HARD INTAKE RULE (V5.9.623): AntiChoke must never prune, evict, shrink,
 * probation-route, or otherwise mutate the scanner/watchlist intake pool. The
 * scanner/watchlist is a 500-token discovery bench for Solana candidates over
 * the scanner floor; qualification belongs upstream.
 */
object AntiChokeManager {
    private const val TAG = "AntiChoke"
    private const val TARGET_TRADES_PER_DAY = 500
    private const val TARGET_TRADES_PER_HOUR = TARGET_TRADES_PER_DAY / 24.0
    private const val TARGET_MS_PER_TRADE = (86_400_000L / TARGET_TRADES_PER_DAY) // ~172.8s
    // V5.9.614 — operator forensics 2026-05-09 00:28-00:35: 7-min stagnation window
    // with 200+ PumpPortal mints arriving but 0 trades. Old 20s cadence + 5x stagnation
    // threshold meant RECOVERY only fired after ~14min — far too slow. Halved cadence
    // and dropped RECOVERY threshold to ~3x (~9min) so the immune system fires before
    // the operator notices. WATCH at 1x, SOFTEN at 1.5x, RECOVERY at 3x.
    private const val CHECK_EVERY_MS = 10_000L
    private const val GHOST_MIN_AGE_MS = 60_000L          // was 90s — clear ghosts faster
    private const val DORMANT_GRACE_MS = 60_000L          // was 90s — start pruning sooner
    private const val DORMANT_CHOKE_MS = 3 * 60_000L      // was 6min — half the dormancy budget
    private const val MAX_PRUNE_PER_CHECK = 80            // was 35 — allow bigger sweeps in firehose conditions
    private const val PRUNE_THRESHOLD = 40                // was 60 — start trimming earlier
    private const val SOFTEN_STAGNATION_MULT = 1.5
    private const val RECOVERY_STAGNATION_MULT = 3.0
    private const val UNPRICED_GRACE_MS = 120_000L        // 2min unpriced → evict in RECOVERY

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

        // V5.9.623 — telemetry only. Unpriced/fat watchlists are NOT a reason
        // to prune scanner intake. They are raw candidates for upstream layers.
        val unpricedFresh = countUnpricedFresh(tokens, now)

        val starving = stagnantMs > TARGET_MS_PER_TRADE * SOFTEN_STAGNATION_MULT ||
            projectedDaily < TARGET_TRADES_PER_DAY * 0.70
        val clogged = false // intake-pool size is not a choke signal; upstream gates decide entries
        val ghostPressure = openInternal >= 12 && openWalletBefore <= 2 && !isPaperMode

        level = when {
            stagnantMs > TARGET_MS_PER_TRADE * RECOVERY_STAGNATION_MULT || ghostPressure -> Level.RECOVERY
            starving || clogged -> Level.SOFTEN
            projectedDaily < TARGET_TRADES_PER_DAY * 0.90 -> Level.WATCH
            else -> Level.CLEAR
        }

        var ghosts = 0
        val pruned = 0
        val unpricedEvicted = 0
        if (level == Level.SOFTEN || level == Level.RECOVERY) {
            ghosts += clearGhostInternalPositions(isPaperMode, wallet, tokens, now)
            try { TradingCopilot.clearDemotionWeights() } catch (_: Throwable) {}
            // V5.9.616 — UNCHOKE BRIDGE: force FDG to drop confidence floors
            // immediately. Operator rule: "the choke manager isnt doing its
            // job. its meant to be able to drop scoring if need be unchoke
            // trading the scanner and watchlist instantly". This is the
            // call that pulls FDG's adaptive relaxation lever directly.
            try {
                FinalDecisionGate.forceAdaptiveRelaxation("AntiChoke=${level.name}")
            } catch (_: Throwable) {}
        }
        if (level == Level.CLEAR) {
            // Trade-rate recovered — clear the relaxation that AntiChoke
            // forced earlier so FDG returns to its normal selectivity.
            try {
                FinalDecisionGate.clearAdaptiveRelaxation("AntiChoke=CLEAR")
            } catch (_: Throwable) {}
        }
        if (level == Level.RECOVERY) {
            // V5.9.623 — recovery may relax upstream gates and clear confirmed ghost
            // positions only. It must NOT evict unpriced/pre-grad scanner candidates
            // or reset the scanner because the intake bench is full/fat. The separate
            // inert-loop watchdog owns true scanner-dead recovery.
        }

        val openWalletAfter = walletOpenCount(isPaperMode)
        val msg = "${level.name}: trades24h=$trades24h/$TARGET_TRADES_PER_DAY projected=${projectedDaily.toInt()} " +
            "stagnant=${stagnantMs/1000}s watch=$watchlistSize unpricedFresh=$unpricedFresh " +
            "openInternal=$openInternal walletOpen=$openWalletAfter " +
            "ghosts=$ghosts pruned=$pruned unpricedEvicted=$unpricedEvicted"
        lastResult = Result(level, trades24h, TARGET_TRADES_PER_DAY, watchlistSize, openInternal, openWalletAfter, ghosts, pruned + unpricedEvicted, msg)
        ErrorLogger.info(TAG, msg)
        return lastResult
    }

    /**
     * V5.9.614 — count tokens in the watchlist that are old enough to expect
     * a price by now but still have no usable price. These are the firehose
     * symptoms: pump.fun pre-graduation mints with no DexScreener pair.
     */
    private fun countUnpricedFresh(tokens: MutableMap<String, TokenState>, now: Long): Int {
        return synchronized(tokens) {
            tokens.values.count { ts ->
                val age = (ts.addedToWatchlistAt.takeIf { it > 0 })?.let { now - it } ?: 0L
                val priceAge = if (ts.lastPriceUpdate > 0L) now - ts.lastPriceUpdate else Long.MAX_VALUE
                val noPrice = ts.lastPrice <= 0.0 || priceAge > 90_000L
                age > 30_000L && noPrice && !ts.position.hasTokens && !ts.position.pendingVerify
            }
        }
    }

    /**
     * V5.9.623 — scanner/watchlist intake is protected.
     * Unpriced/pre-grad candidates must remain available for upstream qualification;
     * AntiChoke is not allowed to evict them.
     */
    private fun evictUnpricedStale(tokens: MutableMap<String, TokenState>, now: Long): Int = 0

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

    /**
     * V5.9.636 — restore build-2488 dormant-watchlist pruning.
     *
     * V5.9.623 turned this into a `= 0` no-op as part of "protected intake
     * pool" doctrine, but combined with the V5.9.626 scanner letting every
     * token through, the watchlist accumulated thousands of stale entries
     * with no positions, no trades, no live price feed — crowding out
     * fresh high-quality candidates from the merge queue.
     *
     * Restored implementation only prunes entries that:
     *   1. Are old enough (past DORMANT_GRACE_MS, past DORMANT_CHOKE_MS).
     *   2. Have NO open position in any sub-trader (ShitCoin/Quality/etc.).
     *   3. Have NO open position on the host wallet (real on-chain holdings).
     *   4. Have no recent trades AND a thin history (< 3 ticks).
     * It walks at most MAX_PRUNE_PER_CHECK and only when watchlist size > 60.
     * Real candidates and held positions are never touched.
     */
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
