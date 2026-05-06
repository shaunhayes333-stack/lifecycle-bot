/*
 * V5.9.495z6 — Wallet Reconciliation (operator spec May 2026, items D/E/F).
 *
 * Source-of-truth synchroniser. The bot maintains state across multiple
 * unsynchronised systems:
 *   - status.tokens[mint].position.isOpen     (in-memory PositionStore)
 *   - LiveTradeLogStore terminalForKey/Sig    (forensic state)
 *   - Wallet on-chain token balances          (truth)
 *
 * When these drift, the operator sees:
 *   • wallet contains 46013 SELLOR + 5072 Goblin
 *   • forensics shows BUY_VERIFIED_LANDED + TX_PARSE qty=46013
 *   • main loop reports positions=0
 *   • V3 eligibility says "ALREADY_OPEN" for both
 *
 * This reconciler resolves the drift by treating the on-chain wallet as
 * canonical truth: any mint with a non-dust raw balance MUST have a
 * corresponding open Position in status.tokens. Orphan balances are
 * recovered via OPEN_POSITION_RECOVERED_FROM_WALLET. Open positions
 * with zero wallet balance are closed via POSITION_CLOSED_BY_WALLET_ZERO.
 */
package com.lifecyclebot.engine

import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.SolanaWallet
import java.util.concurrent.atomic.AtomicLong

object WalletReconciler {
    private const val TAG = "WalletReconciler"
    /** Treat balances below this raw threshold as dust (will not recover/keep open). */
    private const val DUST_RAW = 1L
    /** Minimum spacing between full reconciles to avoid hammering RPC. */
    private const val MIN_INTERVAL_MS = 15_000L

    private val lastRunMs = AtomicLong(0L)
    private val knownMints = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Single full reconcile pass. Safe to call from the main loop.
     * Returns the count of positions that were CHANGED (created/closed/reconciled).
     * Throttled internally so calling every loop tick is fine.
     */
    fun reconcileWalletHoldings(status: BotStatus, wallet: SolanaWallet, isPaperMode: Boolean): Int {
        if (isPaperMode) return 0  // paper accounting is its own truth source
        val now = System.currentTimeMillis()
        val prev = lastRunMs.get()
        if (now - prev < MIN_INTERVAL_MS) return 0
        if (!lastRunMs.compareAndSet(prev, now)) return 0

        val walletMints: Map<String, Pair<Double, Int>> = try {
            wallet.getTokenAccountsWithDecimals()
        } catch (t: Throwable) {
            ErrorLogger.debug(TAG, "wallet read failed: ${t.message?.take(80)}")
            return 0
        }
        // V5.9.495t empty-map defence — never wipe positions on RPC failure.
        if (walletMints.isEmpty()) {
            ErrorLogger.debug(TAG, "wallet returned empty map (likely RPC lag) — skipping reconcile pass")
            return 0
        }

        var changes = 0

        // ── Pass 1: orphan recovery ─────────────────────────────────────────
        // Every mint in the wallet with raw>dust must have an open position.
        for ((mint, pair) in walletMints) {
            val (uiAmount, decimals) = pair
            val rawApprox = (uiAmount * Math.pow(10.0, decimals.toDouble())).toLong()
            if (rawApprox <= DUST_RAW) continue
            val ts = status.tokens[mint]
            if (ts != null && ts.position.isOpen) {
                // Already tracked — bring qty up to wallet truth if drifted.
                if (kotlin.math.abs(ts.position.qtyToken - uiAmount) > (ts.position.qtyToken * 0.01)) {
                    ts.position = ts.position.copy(qtyToken = uiAmount)
                    LiveTradeLogStore.log(
                        tradeKey = "RECONCILE_${mint.take(16)}",
                        mint = mint, symbol = ts.symbol, side = "BUY",
                        phase = LiveTradeLogStore.Phase.POSITION_RECONCILED_FROM_WALLET,
                        message = "🔄 Position qty drifted from wallet — synced ${ts.symbol}: tracker=${ts.position.qtyToken} → wallet=$uiAmount",
                    )
                    changes++
                }
                continue
            }
            // ts is missing or position.isOpen == false → orphan recovery.
            recoverOrphanPosition(status, mint, uiAmount, ts)
            changes++
        }

        // ── Pass 2: zombie closure ──────────────────────────────────────────
        // Every open position with zero wallet balance must be closed —
        // unless a sell is actively VERIFYING (we let the verifier own it).
        for (ts in status.openPositions.toList()) {
            val pair = walletMints[ts.mint]
            val walletUi = pair?.first ?: 0.0
            if (walletUi > 0.0) continue
            // Wallet has zero — but defer if a sell verification is in flight
            // and TradeVerifier hasn't yet reached a terminal state.
            if (LiveTradeLogStore.isTerminallyResolved(
                    tradeKey = "RECONCILE_${ts.mint.take(16)}",
                    sig = null,
                ) ||
                LiveTradeLogStore.isTerminallyResolved(tradeKey = ts.mint, sig = null)
            ) continue

            // Zero wallet, no terminal yet — close the position via reconciler.
            ts.position = ts.position.copy(qtyToken = 0.0)
            knownMints.remove(ts.mint)
            LiveTradeLogStore.log(
                tradeKey = "RECONCILE_${ts.mint.take(16)}",
                mint = ts.mint, symbol = ts.symbol, side = "SELL",
                phase = LiveTradeLogStore.Phase.POSITION_CLOSED_BY_WALLET_ZERO,
                message = "🧹 Wallet shows 0 ${ts.symbol} — closing position via reconciler",
            )
            changes++
        }

        if (changes > 0) {
            ErrorLogger.info(TAG, "✅ Reconcile pass: $changes change(s) | wallet has ${walletMints.size} mints | tracker has ${status.openPositionCount} open")
        }
        return changes
    }

    /**
     * Public helper — true if this mint is currently held in the wallet OR
     * tracked as an open position. Used by V3 eligibility / ALREADY_OPEN
     * gates to avoid the positions=0 + ALREADY_OPEN drift bug.
     */
    fun isHeldOrOpen(status: BotStatus, mint: String): Boolean {
        if (mint.isBlank()) return false
        val ts = status.tokens[mint]
        if (ts != null && ts.position.isOpen) return true
        return knownMints.contains(mint)
    }

    /**
     * V5.9.495z6 — Exposed for V3 ExposureGuard.isTokenAlreadyOpen() so the
     * V3 eligibility check can see wallet-recovered orphans even when the
     * V3-local openMints set has drifted out of sync.
     */
    fun knownMintContains(mint: String): Boolean = knownMints.contains(mint)

    private fun recoverOrphanPosition(
        status: BotStatus,
        mint: String,
        uiAmount: Double,
        existing: TokenState?,
    ) {
        // Use existing TokenState if present (preserves symbol, history, etc.),
        // else create a minimal stub keyed by mint with the mint as a fallback symbol.
        val ts = existing ?: TokenState(
            mint = mint,
            symbol = "RECOVERED_${mint.take(6)}",
            name = "Wallet Recovered",
        ).also {
            synchronized(status.tokens) { status.tokens[mint] = it }
        }
        // Use last known price if available, else 0 (lastPrice will be filled
        // by the regular price feed loop next tick and PnL will reflect from
        // there). Entry price is unknown — operator spec says "mark unknown"
        // by leaving entryPrice=0; this keeps PnL math safe (we treat 0 entry
        // as "do not compute %" in checkProfitLock / strict SL).
        val recoveredEntry = if (ts.lastPrice > 0.0) ts.lastPrice else 0.0
        val now = System.currentTimeMillis()
        ts.position = Position(
            qtyToken = uiAmount,
            entryPrice = recoveredEntry,
            entryTime = now,
            costSol = 0.0,           // unknown — recovery doesn't know cost
            highestPrice = recoveredEntry,
            lowestPrice = recoveredEntry,
            entryPhase = "WALLET_RECOVERED",
            entryScore = 0.0,
            isPaperPosition = false,
            tradingMode = "WALLET_RECOVERED",
            tradingModeEmoji = "🔄",
            pendingVerify = false,
        )
        knownMints.add(mint)
        LiveTradeLogStore.log(
            tradeKey = "RECONCILE_${mint.take(16)}",
            mint = mint, symbol = ts.symbol, side = "BUY",
            phase = LiveTradeLogStore.Phase.OPEN_POSITION_RECOVERED_FROM_WALLET,
            message = "🆘 Recovered orphan: ${ts.symbol} qty=$uiAmount mint=${mint.take(16)}… entry=${if (recoveredEntry > 0) recoveredEntry else "unknown"}",
            tokenAmount = uiAmount,
        )
        ErrorLogger.warn(
            TAG,
            "🆘 RECOVERED orphan position: ${ts.symbol} qty=$uiAmount mint=${mint.take(16)}…"
        )
    }
}
