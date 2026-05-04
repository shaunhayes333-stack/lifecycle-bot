package com.lifecyclebot.perps

import com.lifecyclebot.network.SharedHttpClient

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.WalletManager
import com.lifecyclebot.engine.UniversalBridgeEngine
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SolanaWallet
import com.lifecyclebot.network.SwapQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * V5.7.6b: MARKETS LIVE EXECUTOR
 * 
 * Unified live execution engine for all Markets traders:
 * - TokenizedStockTrader
 * - CommoditiesTrader  
 * - MetalsTrader
 * - ForexTrader
 * 
 * Uses Jupiter API for swaps and wallet signing for on-chain execution.
 * 
 * EXECUTION FLOW:
 * 1. Get wallet from WalletManager
 * 2. Convert signal to swap parameters (SOL -> synthetic token or vice versa)
 * 3. Get quote from Jupiter
 * 4. Build transaction
 * 5. Sign and send via wallet
 * 6. Collect trading fee (split 50/50)
 * 7. Return confirmation
 * 
 * NOTE: For tokenized stocks/commodities/metals/forex on Solana, we use
 * Jupiter swaps to synthetic tokens backed by Pyth oracles, similar to
 * how perps work but without leverage on the base layer.
 */
object MarketsLiveExecutor {
    
    private const val TAG = "MarketsLiveExecutor"
    
    // V5.7.7: Trading fee configuration (consistent with Executor.kt and PerpsTraderAI.kt)
    private const val FEE_WALLET_1 = "A8QPQrPwoc7kxhemPxoUQev67bwA5kVUAuiyU8Vxkkpd"
    private const val FEE_WALLET_2 = "82CAPB9HxXKZK97C12pqkWcjvnkbpMLCg2Ex2hPrhygA"
    private const val SPOT_TRADING_FEE_PERCENT = 0.005    // 0.5% for spot trades (1x)
    private const val LEVERAGE_TRADING_FEE_PERCENT = 0.01 // 1.0% for leverage trades (3x+)
    private const val MIN_FEE_SOL = 0.0001                // Minimum fee to send
    
    // Jupiter API instance
    private var jupiterApi: JupiterApi? = null
    
    // Stats
    private val totalExecutions = AtomicInteger(0)
    private val successfulExecutions = AtomicInteger(0)
    private val failedExecutions = AtomicInteger(0)
    private val lastExecutionTime = AtomicLong(0)
    private var totalFeesCollectedSol = 0.0
    
    // Constants
    private const val SOL_MINT = "So11111111111111111111111111111111111111112"
    private const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    // V5.9.104: hard slippage ceiling for ALL perps-family live swaps
    private const val MAX_SLIPPAGE_BPS = 500   // 5% — matches Executor.kt memecoin cap

    private fun configuredSlippageBps(): Int {
        val ctx = com.lifecyclebot.AATEApp.appContextOrNull() ?: return 100
        return try {
            val cfg = com.lifecyclebot.data.ConfigStore.load(ctx)
            cfg.slippageBps.coerceIn(10, MAX_SLIPPAGE_BPS)
        } catch (_: Exception) { 100 }
    }

    // ═══════════════════════════════════════════════════════════════════
    // V5.9.105: POST-SWAP TOKEN VERIFICATION
    // Same class of fix as Executor.kt phantom guard (V5.9.102). On Alt /
    // Forex / Metals / Commodities / Stocks live paths, a Jupiter swap can
    // return a valid signature but deliver 0 tokens (rug / pool drain /
    // MEV sandwich). Previously we recorded a phantom position and kept
    // burning tx fees trying to "manage" it. Now: poll the on-chain
    // balance of the target mint. If it didn't increase, we return null
    // so the caller never records the phantom.
    //
    // Poll strategy: 3 polls × 4s = up to 12s. On RPC error, keep polling
    // (inconclusive != phantom — never wipe on error alone).
    // ═══════════════════════════════════════════════════════════════════
    private fun readTokenUi(wallet: SolanaWallet, mint: String): Double? {
        return try {
            wallet.getTokenAccountsWithDecimals()[mint]?.first ?: 0.0
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "  RPC read failed for ${mint.take(6)}…: ${e.message}")
            null  // null = inconclusive (RPC error), not zero
        }
    }

    /**
     * Verify a BUY actually delivered tokens on-chain. Returns true if
     * the target mint balance increased by at least minDelta above the
     * pre-swap snapshot.
     *
     * Returns false ONLY when ALL polls returned a definitive 0-delta
     * reading (no RPC errors). On any inconclusive result we return true
     * and log a warning — the position will be adopted later by
     * StartupReconciler rather than wiped silently.
     */
    private suspend fun verifyBuyDelivered(
        wallet: SolanaWallet,
        targetMint: String,
        balanceBefore: Double,
        symbol: String,
        minDelta: Double = 0.0,
    ): Boolean {
        val maxPolls = 3
        val pollIntervalMs = 4_000L
        var anyRpcError = false
        for (attempt in 1..maxPolls) {
            kotlinx.coroutines.delay(pollIntervalMs)
            val now = readTokenUi(wallet, targetMint)
            if (now == null) {
                anyRpcError = true
                continue
            }
            val delta = now - balanceBefore
            if (delta > minDelta) {
                ErrorLogger.info(
                    TAG,
                    "  ✅ POST-BUY OK: $symbol | +${"%.6f".format(delta)} tokens confirmed on-chain (poll $attempt/$maxPolls)"
                )
                return true
            }
        }
        return if (anyRpcError) {
            ErrorLogger.warn(
                TAG,
                "  ⚠️ POST-BUY INCONCLUSIVE: $symbol — RPC errors masked verification. Treating as delivered so reconciler can adopt."
            )
            true  // inconclusive → let StartupReconciler / periodic loop adopt
        } else {
            ErrorLogger.warn(
                TAG,
                "  🚨 PHANTOM BUY: $symbol — 0 tokens delivered after ${maxPolls * pollIntervalMs / 1000}s. Discarding."
            )
            false
        }
    }

    /**
     * Verify a SELL actually reduced the holding. Returns true if the
     * input mint balance decreased by at least minDelta.
     *
     * Same inconclusive-on-error policy as verifyBuyDelivered — we do
     * NOT want to double-sell because an RPC blip hid the burn.
     */
    private suspend fun verifySellExecuted(
        wallet: SolanaWallet,
        inputMint: String,
        balanceBefore: Double,
        symbol: String,
        minDelta: Double = 0.0,
    ): Boolean {
        val maxPolls = 3
        val pollIntervalMs = 4_000L
        var anyRpcError = false
        for (attempt in 1..maxPolls) {
            kotlinx.coroutines.delay(pollIntervalMs)
            val now = readTokenUi(wallet, inputMint)
            if (now == null) {
                anyRpcError = true
                continue
            }
            val delta = balanceBefore - now
            if (delta > minDelta) {
                ErrorLogger.info(
                    TAG,
                    "  ✅ POST-SELL OK: $symbol | -${"%.6f".format(delta)} tokens burned on-chain (poll $attempt/$maxPolls)"
                )
                return true
            }
        }
        return if (anyRpcError) {
            ErrorLogger.warn(
                TAG,
                "  ⚠️ POST-SELL INCONCLUSIVE: $symbol — RPC errors masked verification. Trusting tx sig."
            )
            true
        } else {
            ErrorLogger.warn(
                TAG,
                "  🚨 PHANTOM SELL: $symbol — balance unchanged after ${maxPolls * pollIntervalMs / 1000}s. Tx landed but no burn detected."
            )
            false
        }
    }
    
    /**
     * Initialize with Jupiter API key (optional)
     */
    fun init(apiKey: String = "") {
        jupiterApi = JupiterApi(apiKey)
        ErrorLogger.info(TAG, "MarketsLiveExecutor INITIALIZED")
    }
    
    /**
     * Execute a LIVE market trade
     * 
     * @param market The market to trade
     * @param direction LONG or SHORT
     * @param sizeSol Position size in SOL
     * @param leverage Leverage multiplier (1x for spot, 3x+ for leverage)
     * @param priceUsd Current price in USD
     * @param traderType Which trader is executing (for logging)
     * @return Pair<Boolean, String?> - (success, txSignature)
     */
    suspend fun executeLiveTrade(
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Double,
        priceUsd: Double,
        traderType: String = "Markets",
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        
        totalExecutions.incrementAndGet()
        
        // V5.7.7: Determine fee rate based on leverage
        val feePercent = if (leverage <= 1.0) SPOT_TRADING_FEE_PERCENT else LEVERAGE_TRADING_FEE_PERCENT
        val feeAmountSol = sizeSol * feePercent
        
        ErrorLogger.info(TAG, "LIVE TRADE: $traderType | ${direction.emoji} ${market.symbol}")
        ErrorLogger.info(TAG, "  Size: ${sizeSol.fmt(4)} SOL | Leverage: ${leverage.fmt(1)}x | Price: \$${priceUsd.fmt(2)}")
        ErrorLogger.info(TAG, "  Fee: ${feeAmountSol.fmt(6)} SOL (${(feePercent * 100).fmt(1)}%)")
        
        // Step 1: Get wallet
        val wallet: SolanaWallet?
        val walletAddress: String?
        
        try {
            wallet = WalletManager.getWallet()
            walletAddress = wallet?.publicKeyB58
            
            if (wallet == null || walletAddress.isNullOrEmpty()) {
                ErrorLogger.warn(TAG, "No wallet connected - cannot execute LIVE trade")
                failedExecutions.incrementAndGet()
                return@withContext Pair(false, null)
            }
            
            // V5.9.310: Balance check — UniversalBridgeEngine can use USDC/other tokens as capital,
            // so we only need enough SOL to cover tx fees + rent + fee collection (not full sizeSol).
            // Full capital check happens inside UniversalBridgeEngine.prepareCapital().
            val balance = wallet.getSolBalance()
            val minSolRequired = feeAmountSol + 0.015  // tx fees + ATA rent + fee send buffer
            if (balance < minSolRequired) {
                // Check if wallet has enough total value via UniversalBridgeEngine scan
                // If SOL is critically low even for fees, we can't proceed
                val totalUsdValue = try {
                    com.lifecyclebot.engine.UniversalBridgeEngine.scanWalletCapacity(wallet).totalUsdValue
                } catch (_: Exception) { 0.0 }
                val solPriceUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it > 0 } ?: 150.0
                val totalSolEquiv = totalUsdValue / solPriceUsd
                if (totalSolEquiv < sizeSol * 0.5) {
                    ErrorLogger.warn(TAG, "Insufficient capital: ${balance.fmt(4)} SOL (total wallet ~${totalSolEquiv.fmt(3)} SOL equiv) for ${sizeSol.fmt(4)} SOL trade")
                    failedExecutions.incrementAndGet()
                    return@withContext Pair(false, null)
                }
                if (balance < 0.005) {
                    ErrorLogger.warn(TAG, "SOL critically low (${balance.fmt(4)}) — cannot cover tx fees")
                    failedExecutions.incrementAndGet()
                    return@withContext Pair(false, null)
                }
                ErrorLogger.info(TAG, "  Low SOL (${balance.fmt(4)}) but wallet has ~\$${totalUsdValue.fmt(2)} — proceeding via UniversalBridge")
            }
            
            ErrorLogger.info(TAG, "  Wallet: ${walletAddress.take(8)}... | Balance: ${balance.fmt(4)} SOL")
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Wallet access error: ${e.message}", e)
            failedExecutions.incrementAndGet()
            return@withContext Pair(false, null)
        }
        
        // Step 2: Determine execution strategy based on market type
        // V5.9.16: Real tokenized asset routing. For non-crypto markets we look
        // up the real on-chain mint via TokenizedAssetRegistry.
        // V5.9.45: Crypto now also routes via Jupiter SPOT swap (leverage<=1)
        // instead of the dead Jupiter Perps v2 endpoint. For leveraged crypto
        // trades with no mint available we fall back to SPOT at 1x leverage
        // and log a warning — the user explicitly wants live execution over
        // silent failure.
        val txSignature = when {
            market.isCrypto -> {
                // Resolve Solana mint for the crypto symbol
                val mint = com.lifecyclebot.perps.DynamicAltTokenRegistry
                    .getTokenBySymbol(market.symbol)
                    ?.mint
                    ?.takeIf { it.isNotBlank() && !it.startsWith("cg:") && !it.startsWith("static:") }

                when {
                    leverage > 1.0 -> {
                        // V5.9.230: Jupiter Perps v2 is dead. Route to Flash.trade perps API
                        // which is active on Solana mainnet (SOL, BTC, ETH, etc.).
                        // For symbols not supported on Flash, degrade to SPOT (transparent).
                        executeFlashTradePerps(wallet, walletAddress, market, direction, sizeSol, leverage)
                    }
                    mint != null -> executeCryptoSpotSwap(wallet, walletAddress, market, direction, sizeSol, mint)
                    else -> {
                        // V5.9.310: No static mint — try UniversalBridgeEngine two-hop route.
                        // Routes SOL→USDC→target via Jupiter. Covers entire Solana DeFi universe:
                        // any token with Jupiter liquidity is reachable even without a cached mint.
                        ErrorLogger.info(TAG, "🌉 No static mint for ${market.symbol} — trying UniversalBridge two-hop")
                        executeCryptoViaBridge(wallet, walletAddress, market, direction, sizeSol)
                    }
                }
            }

            market.isStock || market.isCommodity || market.isMetal || market.isForex -> {
                // V5.9.464 — OPERATOR-REPORTED BUG: 'it's never made a leverage
                // trade anywhere in the markets universe ever'. RCA (expert
                // troubleshoot agent, V5.9.463 baseline): there is NO code
                // path in MarketsLiveExecutor that executes a leverage trade
                // for non-crypto symbols. `executeFlashTradePerps` is only
                // reachable when `market.isCrypto == true`. Stocks /
                // commodities / metals / forex hit this branch and the
                // `leverage` parameter is silently discarded, degrading to
                // spot swaps via Jupiter.
                //
                // This is a HONEST-DEGRADATION fix: we now LOG clearly when
                // a leverage request was downgraded to spot so users see
                // the real capability gap instead of believing leverage
                // worked. Fixing the real cause requires a non-crypto
                // leverage provider (Drift synthetic perps, Parcl for RWAs,
                // Mango v4 for FX/metals, or a broker bridge). That's a
                // new third-party integration and is queued for operator
                // approval.
                if (leverage > 1.0) {
                    ErrorLogger.warn(TAG,
                        "⚠️ LEVERAGE NOT AVAILABLE for ${market.symbol} " +
                        "(asset class=${if (market.isStock) "STOCK" else if (market.isCommodity) "COMMODITY" else if (market.isMetal) "METAL" else "FOREX"}): " +
                        "${"%.1fx".format(leverage)} requested but Markets universe has no leverage provider wired. " +
                        "Degrading to SPOT at 1x. [V5.9.464: needs Drift/Parcl/Mango integration].")
                }
                val mint = TokenizedAssetRegistry.mintFor(market.symbol)
                if (mint != null) {
                    // Real tokenized asset on-chain (xStocks / PAXG / EURC) — Jupiter swap
                    executeTokenizedAssetTrade(wallet, walletAddress, market, direction, sizeSol, mint)
                } else {
                    // No verified on-chain route for this symbol. Do NOT execute a fake/proxy
                    // trade — that would silently move funds without real market exposure.
                    // Log clearly and return null so the caller skips this trade.
                    ErrorLogger.warn(TAG,
                        "⛔ LIVE skipped for ${market.symbol}: no on-chain route. " +
                        "Register a real Solana mint via TokenizedAssetRegistry.register() to enable live trading.")
                    null
                }
            }

            else -> {
                ErrorLogger.warn(TAG, "Unknown market type for ${market.symbol}")
                null
            }
        }

        if (txSignature != null) {
            collectTradingFee(wallet, feeAmountSol, market.symbol, "OPEN")
            successfulExecutions.incrementAndGet()
            lastExecutionTime.set(System.currentTimeMillis())
            ErrorLogger.info(TAG, "LIVE TRADE SUCCESS: ${txSignature.take(24)}")
            return@withContext Pair(true, txSignature)
        } else {
            failedExecutions.incrementAndGet()
            ErrorLogger.warn(TAG, "LIVE TRADE FAILED for ${market.symbol}")
            return@withContext Pair(false, null)
        }
    }
    
    /**
     * V5.7.7: Collect trading fee (split 50/50 between two wallets)
     */
    suspend fun collectTradingFeePublic(wallet: com.lifecyclebot.network.SolanaWallet, feeAmountSol: Double, symbol: String, tradeAction: String) {
        try { collectTradingFee(wallet, feeAmountSol, symbol, tradeAction) } catch (_: Exception) {}
    }

    private suspend fun collectTradingFee(
        wallet: SolanaWallet,
        feeAmountSol: Double,
        symbol: String,
        tradeAction: String
    ) {
        if (feeAmountSol < MIN_FEE_SOL) {
            ErrorLogger.debug(TAG, "Fee too small to collect: ${feeAmountSol.fmt(6)} SOL")
            return
        }
        
        try {
            val feeWallet1 = feeAmountSol * 0.5
            val feeWallet2 = feeAmountSol * 0.5

            // V5.9.309: Pre-check wallet has SOL for the fee transfer + tx fee.
            // Without this, FeeRetryQueue exhaustion (22+ dropped fees observed)
            // because a fee send right after a swap can race the swap's own SOL
            // settlement and find the wallet temporarily empty.
            val walletSol = try { wallet.getSolBalance() } catch (_: Exception) { 0.0 }
            val FEE_TX_RESERVE_SOL = 0.005  // tx fee + small buffer
            if (walletSol < (feeAmountSol + FEE_TX_RESERVE_SOL)) {
                ErrorLogger.warn(TAG, "  Fee deferred ($symbol): wallet=${walletSol.fmt(6)} SOL < need=${(feeAmountSol + FEE_TX_RESERVE_SOL).fmt(6)} SOL — enqueuing for retry")
                try {
                    com.lifecyclebot.engine.FeeRetryQueue.enqueue(FEE_WALLET_1, feeWallet1, "markets_${tradeAction}_w1")
                    com.lifecyclebot.engine.FeeRetryQueue.enqueue(FEE_WALLET_2, feeWallet2, "markets_${tradeAction}_w2")
                } catch (_: Exception) {}
                return
            }

            // Send to wallet 1
            if (feeWallet1 >= MIN_FEE_SOL) {
                try {
                    wallet.sendSol(FEE_WALLET_1, feeWallet1)
                    ErrorLogger.debug(TAG, "  Fee sent to wallet 1: ${feeWallet1.fmt(6)} SOL")
                } catch (e: Exception) {
                    ErrorLogger.warn(TAG, "  Fee wallet 1 send failed: ${e.message}")
                    try { com.lifecyclebot.engine.FeeRetryQueue.enqueue(FEE_WALLET_1, feeWallet1, "markets_${tradeAction}_w1") } catch (_: Exception) {}
                }
            }

            // Send to wallet 2
            if (feeWallet2 >= MIN_FEE_SOL) {
                try {
                    wallet.sendSol(FEE_WALLET_2, feeWallet2)
                    ErrorLogger.debug(TAG, "  Fee sent to wallet 2: ${feeWallet2.fmt(6)} SOL")
                } catch (e: Exception) {
                    ErrorLogger.warn(TAG, "  Fee wallet 2 send failed: ${e.message}")
                    try { com.lifecyclebot.engine.FeeRetryQueue.enqueue(FEE_WALLET_2, feeWallet2, "markets_${tradeAction}_w2") } catch (_: Exception) {}
                }
            }
            
            totalFeesCollectedSol += feeAmountSol
            ErrorLogger.info(TAG, "💸 MARKETS FEE ($tradeAction $symbol): ${feeAmountSol.fmt(6)} SOL collected")
            
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Fee collection error: ${e.message}")
        }
    }
    
    /**
     * Execute a tokenized stock trade via Jupiter swaps
     * 
     * For tokenized stocks, we swap SOL to a synthetic token that tracks
     * the stock price via Pyth oracles. This is how platforms like
     * Drift Protocol and Parcl handle tokenized assets.
     */
    private suspend fun executeStockTrade(
        wallet: SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        priceUsd: Double,
    ): String? {
        // V2.0: Use UniversalBridgeEngine — works from ANY token, not just SOL
        // Source = best token in wallet → bridge to USDC as collateral
        val solPriceUsd = WalletManager.lastKnownSolPrice.takeIf { it > 0 } ?: 150.0
        val sizeUsd     = sizeSol * solPriceUsd
        ErrorLogger.info(TAG, "  Executing STOCK trade: ${direction.emoji} ${market.symbol} | \$${sizeUsd.fmt(2)} via UniversalBridge")
        val bridge = UniversalBridgeEngine.prepareCapital(wallet, UniversalBridgeEngine.USDC_MINT, sizeUsd)
        if (!bridge.success) {
            ErrorLogger.warn(TAG, "  Bridge failed: ${bridge.errorMsg} — falling back to direct SOL swap")
            return executeJupiterSwap(wallet, walletAddress, SOL_MINT, USDC_MINT, (sizeSol * 1_000_000_000).toLong(), configuredSlippageBps())
        }
        return bridge.swapTxSig  // V5.9: null = no swap needed (was placeholder string)
    }

    /**
     * Execute a commodity trade
     */
    private suspend fun executeCommodityTrade(
        wallet: SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        priceUsd: Double,
    ): String? {
        val solPriceUsd = WalletManager.lastKnownSolPrice.takeIf { it > 0 } ?: 150.0
        val sizeUsd     = sizeSol * solPriceUsd
        ErrorLogger.info(TAG, "  Executing COMMODITY trade: ${direction.emoji} ${market.symbol} | \$${sizeUsd.fmt(2)} via UniversalBridge")
        val bridge = UniversalBridgeEngine.prepareCapital(wallet, UniversalBridgeEngine.USDC_MINT, sizeUsd)
        if (!bridge.success) { return executeJupiterSwap(wallet, walletAddress, SOL_MINT, USDC_MINT, (sizeSol * 1_000_000_000).toLong(), configuredSlippageBps()) }
        return bridge.swapTxSig  // V5.9: null = no swap needed (was placeholder string)
    }

    /**
     * Execute a metal trade
     */
    private suspend fun executeMetalTrade(
        wallet: SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        priceUsd: Double,
    ): String? {
        ErrorLogger.info(TAG, "  Executing METAL trade: ${direction.emoji} ${market.symbol} (collateral post SOL→USDC)")

        return executeJupiterSwap(
            wallet = wallet,
            walletAddress = walletAddress,
            inputMint = SOL_MINT,
            outputMint = USDC_MINT,
            amountLamports = (sizeSol * 1_000_000_000).toLong(),
            slippageBps = configuredSlippageBps(),
        )
    }

    /**
     * Execute a forex trade
     */
    private suspend fun executeForexTrade(
        wallet: SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        priceUsd: Double,
    ): String? {
        val solPriceUsd = WalletManager.lastKnownSolPrice.takeIf { it > 0 } ?: 150.0
        val sizeUsd     = sizeSol * solPriceUsd
        ErrorLogger.info(TAG, "  Executing FOREX trade: ${direction.emoji} ${market.symbol} | \$${sizeUsd.fmt(2)} via UniversalBridge")
        val bridge = UniversalBridgeEngine.prepareCapital(wallet, UniversalBridgeEngine.USDC_MINT, sizeUsd)
        if (!bridge.success) { return executeJupiterSwap(wallet, walletAddress, SOL_MINT, USDC_MINT, (sizeSol * 1_000_000_000).toLong(), configuredSlippageBps()) }
        return bridge.swapTxSig  // V5.9: null = no swap needed (was placeholder string)
    }
    
    /**
     * Execute a crypto perp trade
     */
    private suspend fun executeCryptoTrade(
        wallet: SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        priceUsd: Double,
    ): String? {
        ErrorLogger.info(TAG, "  Executing CRYPTO trade: ${market.symbol}")
        
        // For crypto, use JupiterPerps for actual perps execution
        return try {
            val order = JupiterPerps.openPosition(
                market = market,
                direction = direction,
                sizeSol = sizeSol,
                leverage = 1.0,
                isPaper = false,
            )
            order.txSignature
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Crypto trade error: ${e.message}", e)
            null
        }
    }

    /**
     * V5.9.45: Real on-chain SPOT swap for crypto markets.
     * - LONG: swap SOL → target mint
     * - SHORT: swap target mint → USDC (requires existing token balance;
     *   logs a warning and returns null if not held).
     *
     * This replaces the dead Jupiter Perps v2 quote endpoint. Routed
     * through the working `/swap/v1` path used by xStocks swaps.
     */
    private suspend fun executeCryptoSpotSwap(
        wallet: SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        targetMint: String,
    ): String? {
        if (direction == PerpsDirection.SHORT) {
            ErrorLogger.warn(TAG,
                "⚠️ SHORT not supported on SPOT crypto swap for ${market.symbol} — perps retired, skipping live.")
            return null
        }
        // V5.9.310: Use UniversalBridgeEngine to pick best source token from wallet.
        // Previously always used SOL_MINT as input — if wallet had USDC/USDT but low SOL,
        // the swap would fail or get trimmed to near-zero.
        // Now: scan wallet, pick best source (USDC→SOL→other), bridge to USDC if needed,
        // then USDC→target. This makes the entire Solana DeFi universe accessible from
        // any held token.
        val solPriceUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it > 0 } ?: 150.0
        val sizeUsd = sizeSol * solPriceUsd

        ErrorLogger.info(TAG, "  💱 SPOT swap: ${market.symbol} LONG | \$${sizeUsd.fmt(2)} via UniversalBridge → ${targetMint.take(8)}...")

        val balanceBefore = readTokenUi(wallet, targetMint) ?: 0.0

        val bridge = com.lifecyclebot.engine.UniversalBridgeEngine.prepareCapital(
            wallet     = wallet,
            targetMint = targetMint,
            sizeUsd    = sizeUsd,
        )

        if (!bridge.success) {
            // UniversalBridge failed — fall back to direct SOL→target with rent reserve
            ErrorLogger.warn(TAG, "  Bridge failed (${bridge.errorMsg}) — SOL direct fallback")
            val walletSol = try { wallet.getSolBalance() } catch (_: Exception) { 0.0 }
            val RENT_RESERVE_SOL = 0.012
            val effectiveSizeSol = minOf(sizeSol, walletSol - RENT_RESERVE_SOL)
            if (effectiveSizeSol < 0.005) {
                ErrorLogger.warn(TAG, "⛔ ${market.symbol} SPOT skipped: insufficient SOL for fallback (${walletSol.fmt(4)} SOL)")
                return null
            }
            val amountLamports = (effectiveSizeSol * 1_000_000_000L).toLong()
            val sig = executeJupiterSwap(
                wallet         = wallet,
                walletAddress  = walletAddress,
                inputMint      = SOL_MINT,
                outputMint     = targetMint,
                amountLamports = amountLamports,
                slippageBps    = configuredSlippageBps(),
            ) ?: return null
            return if (verifyBuyDelivered(wallet, targetMint, balanceBefore, market.symbol)) sig else null
        }

        val sig = bridge.swapTxSig ?: return null
        return if (verifyBuyDelivered(wallet, targetMint, balanceBefore, market.symbol)) sig else null
    }

    /**
     * V5.9.310: Bridge-only crypto swap — for tokens with no static Solana mint.
     * Routes SOL (or best wallet token) → USDC → target via UniversalBridgeEngine two-hop.
     * Jupiter discovers the target mint from its strict token list at runtime.
     * If still no mint found after runtime lookup, parks capital as USDC collateral.
     */
    private suspend fun executeCryptoViaBridge(
        wallet: SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
    ): String? {
        if (direction == PerpsDirection.SHORT) {
            ErrorLogger.warn(TAG, "⚠️ ${market.symbol} SHORT via bridge not supported on SPOT — skipping")
            return null
        }
        val solPriceUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it > 0 } ?: 150.0
        val sizeUsd = sizeSol * solPriceUsd

        // Runtime mint lookup — Jupiter strict list is fetched at startup into DynamicAltTokenRegistry
        val runtimeMint = com.lifecyclebot.perps.DynamicAltTokenRegistry
            .getTokenBySymbol(market.symbol)?.mint
            ?.takeIf { it.isNotBlank() && !it.startsWith("cg:") && !it.startsWith("static:") }

        if (runtimeMint == null) {
            // No mint at all — park capital as USDC so it's not sitting idle in SOL
            ErrorLogger.warn(TAG, "🌉 ${market.symbol}: no runtime mint — parking \$${sizeUsd.fmt(2)} as USDC collateral")
            val bridge = com.lifecyclebot.engine.UniversalBridgeEngine.prepareCapital(
                wallet     = wallet,
                targetMint = com.lifecyclebot.engine.UniversalBridgeEngine.USDC_MINT,
                sizeUsd    = sizeUsd,
            )
            return if (bridge.success) bridge.swapTxSig else null
        }

        ErrorLogger.info(TAG, "🌉 ${market.symbol}: runtime mint=${runtimeMint.take(8)}... | \$${sizeUsd.fmt(2)} two-hop bridge")
        val balanceBefore = readTokenUi(wallet, runtimeMint) ?: 0.0
        val bridge = com.lifecyclebot.engine.UniversalBridgeEngine.prepareCapital(
            wallet     = wallet,
            targetMint = runtimeMint,
            sizeUsd    = sizeUsd,
        )
        if (!bridge.success) {
            ErrorLogger.warn(TAG, "🌉 ${market.symbol} bridge failed: ${bridge.errorMsg}")
            return null
        }
        val sig = bridge.swapTxSig ?: return null
        return if (verifyBuyDelivered(wallet, runtimeMint, balanceBefore, market.symbol)) sig else null
    }

    /**
     * V5.9.16: REAL tokenized-asset trade via Jupiter.
     *  - LONG:  SOL → target mint (buy the tokenized asset, real on-chain exposure)
     *  - SHORT: skipped on spot (xStocks can't be shorted on spot DEXes);
     *           returns null so the upstream trader falls back to paper.
     */
    private suspend fun executeTokenizedAssetTrade(
        wallet: SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        targetMint: String,
    ): String? {
        if (direction == PerpsDirection.SHORT) {
            ErrorLogger.warn(TAG,
                "⚠️ SHORT not supported on tokenized spot asset ${market.symbol} — skipping live (use perps or paper).")
            return null
        }
        val label = TokenizedAssetRegistry.routeLabel(market.symbol)
        // V5.9.309: WALLET BALANCE PRE-CHECK with RENT_RESERVE (same fix as crypto SPOT path)
        val walletSol = try { wallet.getSolBalance() } catch (_: Exception) { 0.0 }
        val RENT_RESERVE_SOL = 0.012
        val effectiveSizeSol = minOf(sizeSol, walletSol - RENT_RESERVE_SOL)
        if (effectiveSizeSol < 0.005) {
            ErrorLogger.warn(TAG,
                "⛔ ${market.symbol} ${label} skipped: wallet=${walletSol.fmt(4)} SOL, reserve=${RENT_RESERVE_SOL} SOL — not enough for swap+fees")
            return null
        }
        if (effectiveSizeSol < sizeSol) {
            ErrorLogger.info(TAG, "  ✂️ ${market.symbol}: trimmed sizeSol ${sizeSol.fmt(4)} → ${effectiveSizeSol.fmt(4)} (rent reserve)")
        }
        ErrorLogger.info(TAG, "  🧾 REAL tokenized trade: ${market.symbol} LONG via $label | ${effectiveSizeSol.fmt(4)} SOL")
        val amountLamports = (effectiveSizeSol * 1_000_000_000L).toLong()
        // V5.9.105: snapshot target-mint balance BEFORE swap for phantom guard
        val balanceBefore = readTokenUi(wallet, targetMint) ?: 0.0
        // Tokenized-asset pools (xStocks/PAXG/EURC) are thinner than SOL/USDC —
        // use 2% slippage tolerance to avoid spurious Jupiter rejections.
        val sig = executeJupiterSwap(
            wallet         = wallet,
            walletAddress  = walletAddress,
            inputMint      = SOL_MINT,
            outputMint     = targetMint,
            amountLamports = amountLamports,
            slippageBps    = configuredSlippageBps(),  // V5.9.104: user-config capped at 5%
        ) ?: return null
        // V5.9.105: verify tokens actually arrived — otherwise phantom
        return if (verifyBuyDelivered(wallet, targetMint, balanceBefore, market.symbol)) sig else null
    }
    
    /**
     * Execute a Jupiter swap
     * 
     * Core swap execution using Jupiter API:
     * 1. Get quote
     * 2. Build transaction
     * 3. Sign with wallet
     * 4. Send and confirm
     */
    private suspend fun executeJupiterSwap(
        wallet: SolanaWallet,
        walletAddress: String,
        inputMint: String,
        outputMint: String,
        amountLamports: Long,
        slippageBps: Int,
    ): String? {
        val api = jupiterApi ?: JupiterApi("")
        // Retry once on 422 (stale blockhash) by fetching a completely fresh quote+tx
        for (attempt in 0..1) {
            try {
                // Step 1: Get quote
                ErrorLogger.debug(TAG, "  Getting Jupiter quote (attempt ${attempt + 1})...")
                val quote = try {
                    api.getQuote(
                        inputMint = inputMint,
                        outputMint = outputMint,
                        amountRaw = amountLamports,
                        slippageBps = slippageBps,
                    )
                } catch (e: Exception) {
                    ErrorLogger.warn(TAG, "  Quote failed: ${e.message}")
                    return null
                }

                if (quote.outAmount <= 0) {
                    ErrorLogger.warn(TAG, "  Quote returned 0 output")
                    return null
                }

                ErrorLogger.debug(TAG, "  Quote OK: outAmount=${quote.outAmount} impact=${quote.priceImpactPct}%")

                if (quote.priceImpactPct > 3.0) {
                    ErrorLogger.warn(TAG, "  Price impact too high: ${quote.priceImpactPct}% > 3%")
                    return null
                }

                // Step 2: Build transaction
                ErrorLogger.debug(TAG, "  Building transaction...")
                val txResult = try {
                    api.buildSwapTx(quote, walletAddress)
                } catch (e: Exception) {
                    ErrorLogger.warn(TAG, "  Build tx failed: ${e.message}")
                    return null
                }

                if (txResult.txBase64.isEmpty()) {
                    ErrorLogger.warn(TAG, "  Empty transaction returned")
                    return null
                }

                // Step 3: Sign and send
                ErrorLogger.debug(TAG, "  Signing and sending transaction...")
                val signature = try {
                    wallet.signSendAndConfirm(
                        txBase64 = txResult.txBase64,
                        useJito = false,
                        jitoTipLamports = 0,
                        ultraRequestId = if (quote.isUltra) txResult.requestId else null,
                        jupiterApiKey = "",
                        isRfqRoute = txResult.isRfqRoute,
                    )
                } catch (e: Exception) {
                    // 422 means the transaction was stale — retry with a fresh quote
                    if (attempt == 0 && e.message?.contains("422") == true) {
                        ErrorLogger.warn(TAG, "  422 invalid signature — retrying with fresh quote")
                        continue
                    }
                    ErrorLogger.error(TAG, "  Sign/send failed: ${e.message}", e)
                    return null
                }

                ErrorLogger.info(TAG, "  Transaction confirmed: ${signature.take(24)}...")
                return signature

            } catch (e: Exception) {
                ErrorLogger.error(TAG, "Jupiter swap error: ${e.message}", e)
                return null
            }
        }
        return null
    }
    
    /**
     * Close a LIVE position
     * 
     * @param market The market
     * @param direction Original direction (to reverse)
     * @param sizeSol Position size to close
     * @param leverage Original leverage (for fee calculation)
     * @param traderType Which trader is closing
     * @return Pair<Boolean, String?> - (success, txSignature)
     */

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.320: FLASH.TRADE PERPS — FULLY WIRED TO REAL API
    //
    // API base: https://flashapi.trade  (NOT api.flash.trade — that was wrong)
    // No API key required. Rate limit: 10 req/s.
    //
    // OPEN:  POST /transaction-builder/open-position
    //   → inputTokenSymbol: "USDC", outputTokenSymbol: "SOL"/"BTC"/etc.
    //   → inputAmountUi: USD amount as string
    //   → leverage: float, tradeType: "LONG"/"SHORT"
    //   → owner: wallet pubkey
    //   → Response: { transactionBase64, err }
    //
    // CLOSE: POST /transaction-builder/close-position
    //   → positionKey: Flash position account pubkey (from GET /positions/owner/{wallet})
    //   → inputUsdUi: full position size USD
    //   → withdrawTokenSymbol: "USDC"
    //   → owner: wallet pubkey
    //
    // FIND KEY: GET /positions/owner/{wallet}?includePnlInLeverageDisplay=true
    //   → returns array with .key and .marketSymbol/.sideUi for matching
    //
    // Flash supports: SOL, BTC, ETH, BNB, XRP, ADA, DOGE, AVAX, LINK, DOT,
    //                 MATIC, LTC, ATOM, UNI, ARB, OP, APT, SUI, INJ, JUP,
    //                 NEAR, TIA, WLD, ENA, BONK, WIF, PEPE, TRUMP, SHIB
    //
    // For unsupported symbols: degrade transparently to Jupiter SPOT swap.
    // ═══════════════════════════════════════════════════════════════════════════

    private val FLASH_API_BASE = "https://flashapi.trade"

    // Shared OkHttp client for all Flash API calls (reused, not recreated per call)
    private val flashHttp by lazy {
        com.lifecyclebot.network.SharedHttpClient.builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // Flash supported symbols — confirmed on-chain markets
    // Public so CryptoAltTrader can check before storing Flash position keys.
    val FLASH_SUPPORTED_PUBLIC = setOf(
        "SOL", "BTC", "ETH", "BNB", "XRP", "ADA", "DOGE", "AVAX",
        "LINK", "DOT", "MATIC", "LTC", "ATOM", "UNI", "ARB", "OP",
        "APT", "SUI", "INJ", "JUP", "NEAR", "TIA", "WLD", "ENA",
        "BONK", "WIF", "PEPE", "TRUMP", "SHIB"
    )
    private val FLASH_SUPPORTED = FLASH_SUPPORTED_PUBLIC

    /** Fetch all open positions for a wallet from Flash API. Returns list of position objects. */
    private fun fetchFlashPositions(walletAddress: String): org.json.JSONArray? {
        return try {
            val request = okhttp3.Request.Builder()
                .url("$FLASH_API_BASE/positions/owner/$walletAddress?includePnlInLeverageDisplay=true")
                .get()
                .build()
            val response = flashHttp.newCall(request).execute()
            val body = response.body?.string() ?: return null
            if (!response.isSuccessful) {
                ErrorLogger.warn(TAG, "Flash fetchPositions ${response.code}: $body")
                return null
            }
            org.json.JSONArray(body)
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Flash fetchPositions exception: ${e.message}")
            null
        }
    }

    /**
     * Find the Flash position key for an open position by matching symbol + side.
     * Returns the positionKey (account pubkey) needed for the close endpoint.
     */
    fun findFlashPositionKey(walletAddress: String, symbol: String, direction: PerpsDirection): String? {
        val positions = fetchFlashPositions(walletAddress) ?: return null
        val sideTarget = if (direction == PerpsDirection.LONG) "Long" else "Short"
        for (i in 0 until positions.length()) {
            val pos = positions.optJSONObject(i) ?: continue
            val mktSymbol = pos.optString("marketSymbol", "")
            val side = pos.optString("sideUi", "")
            if (mktSymbol.equals(symbol, ignoreCase = true) && side.equals(sideTarget, ignoreCase = true)) {
                val key = pos.optString("key", "")
                if (key.isNotBlank()) return key
            }
        }
        return null
    }

    /**
     * Open a leveraged perps position on Flash.trade.
     * Uses USDC as collateral input (most liquid route).
     * Returns tx signature on success, null on failure.
     * On unsupported symbol: degrades transparently to Jupiter SPOT.
     *
     * IMPORTANT: caller must store the returned flashPositionKey from the
     * position lookup after open to enable proper close later.
     */
    private suspend fun executeFlashTradePerps(
        wallet: com.lifecyclebot.network.SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Double,
    ): String? = withContext(Dispatchers.IO) {

        val symbol = market.symbol

        if (symbol !in FLASH_SUPPORTED) {
            ErrorLogger.info(TAG, "⚠️ Flash: $symbol not in perps universe — degrading to SPOT 1x")
            val mint = com.lifecyclebot.perps.DynamicAltTokenRegistry
                .getTokenBySymbol(symbol)?.mint
                ?.takeIf { it.isNotBlank() && !it.startsWith("cg:") && !it.startsWith("static:") }
            return@withContext if (mint != null)
                executeCryptoSpotSwap(wallet, walletAddress, market, direction, sizeSol, mint)
            else {
                ErrorLogger.warn(TAG, "⛔ Flash + SPOT fallback: no mint for $symbol")
                null
            }
        }

        val tradeType = if (direction == PerpsDirection.LONG) "LONG" else "SHORT"
        val solPriceUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice.takeIf { it > 0 } ?: 150.0
        val inputAmountUsd = sizeSol * solPriceUsd  // collateral in USD
        val slippagePct = (configuredSlippageBps() / 100.0).coerceIn(0.1, 5.0)

        ErrorLogger.info(TAG, "⚡ Flash.trade PERPS: $symbol $tradeType ${leverage}x | \$${inputAmountUsd.fmt(2)} collateral")

        return@withContext try {
            val reqBody = org.json.JSONObject().apply {
                put("inputTokenSymbol",   "USDC")       // pay with USDC
                put("outputTokenSymbol",  symbol)        // open perp on this market
                put("inputAmountUi",      inputAmountUsd.fmt(2))
                put("leverage",           leverage)
                put("tradeType",          tradeType)
                put("owner",              walletAddress)
                put("slippagePercentage", slippagePct.toString())
            }.toString()

            val mediaJson = "application/json; charset=utf-8".toMediaType()
            val request = okhttp3.Request.Builder()
                .url("$FLASH_API_BASE/transaction-builder/open-position")
                .post(reqBody.toRequestBody(mediaJson))
                .header("Content-Type", "application/json")
                .build()

            val response = flashHttp.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                ErrorLogger.warn(TAG, "⚠️ Flash open-position ${response.code}: $responseBody — degrading to SPOT")
                return@withContext degradeFlashToSpot(wallet, walletAddress, market, direction, sizeSol)
            }

            val json = org.json.JSONObject(responseBody)
            val err = json.optString("err", "null")
            if (err != "null" && err.isNotBlank()) {
                ErrorLogger.warn(TAG, "⚠️ Flash API err for $symbol: $err — degrading to SPOT")
                return@withContext degradeFlashToSpot(wallet, walletAddress, market, direction, sizeSol)
            }

            val txBase64 = json.optString("transactionBase64", "")
            if (txBase64.isBlank()) {
                ErrorLogger.warn(TAG, "⚠️ Flash: no transaction in response for $symbol")
                return@withContext degradeFlashToSpot(wallet, walletAddress, market, direction, sizeSol)
            }

            // Sign and send the Flash transaction
            val sig = try {
                wallet.signAndSend(txBase64)
            } catch (ex: Exception) {
                ErrorLogger.warn(TAG, "⚠️ Flash sign/send failed for $symbol: ${ex.message}")
                return@withContext degradeFlashToSpot(wallet, walletAddress, market, direction, sizeSol)
            }

            if (sig.isNullOrBlank()) {
                ErrorLogger.warn(TAG, "⚠️ Flash: empty sig for $symbol — degrading to SPOT")
                return@withContext degradeFlashToSpot(wallet, walletAddress, market, direction, sizeSol)
            }

            ErrorLogger.info(TAG, "✅ Flash.trade $tradeType $symbol ${leverage}x OPEN: ${sig.take(24)}...")
            sig

        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Flash exception for $symbol: ${e.message} — degrading to SPOT")
            degradeFlashToSpot(wallet, walletAddress, market, direction, sizeSol)
        }
    }

    /** Degrade a Flash perps open to a Jupiter SPOT swap (1x, LONG only). */
    private suspend fun degradeFlashToSpot(
        wallet: com.lifecyclebot.network.SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
    ): String? {
        val mint = com.lifecyclebot.perps.DynamicAltTokenRegistry
            .getTokenBySymbol(market.symbol)?.mint
            ?.takeIf { it.isNotBlank() && !it.startsWith("cg:") && !it.startsWith("static:") }
        return if (mint != null)
            executeCryptoSpotSwap(wallet, walletAddress, market, direction, sizeSol, mint)
        else null
    }

    /**
     * Close a Flash.trade leveraged position.
     * Looks up the open position key from Flash API by matching symbol+side,
     * then calls /transaction-builder/close-position to get a ready-to-sign tx.
     *
     * @param positionKeyHint  Optional pre-stored position key. If null, we query Flash API.
     */
    suspend fun closeFlashPosition(
        wallet: com.lifecyclebot.network.SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeUsd: Double,
        positionKeyHint: String? = null,
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {

        // 1. Resolve position key
        val positionKey = positionKeyHint
            ?: findFlashPositionKey(walletAddress, market.symbol, direction)

        if (positionKey.isNullOrBlank()) {
            ErrorLogger.warn(TAG, "⛔ Flash close: no position key found for ${market.symbol} ${direction.name}")
            return@withContext Pair(false, null)
        }

        ErrorLogger.info(TAG, "⚡ Flash.trade CLOSE: ${market.symbol} ${direction.name} | key=${positionKey.take(12)}... | \$${sizeUsd.fmt(2)}")

        val slippagePct = (configuredSlippageBps() / 100.0).coerceIn(0.1, 5.0)

        return@withContext try {
            val reqBody = org.json.JSONObject().apply {
                put("positionKey",           positionKey)
                put("inputUsdUi",            sizeUsd.fmt(2))  // full position USD for full close
                put("withdrawTokenSymbol",   "USDC")          // receive USDC back
                put("owner",                 walletAddress)
                put("slippagePercentage",    slippagePct.toString())
            }.toString()

            val mediaJson = "application/json; charset=utf-8".toMediaType()
            val request = okhttp3.Request.Builder()
                .url("$FLASH_API_BASE/transaction-builder/close-position")
                .post(reqBody.toRequestBody(mediaJson))
                .header("Content-Type", "application/json")
                .build()

            val response = flashHttp.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                ErrorLogger.warn(TAG, "⚠️ Flash close ${response.code}: $responseBody")
                return@withContext Pair(false, null)
            }

            val json = org.json.JSONObject(responseBody)
            val err = json.optString("err", "null")
            if (err != "null" && err.isNotBlank()) {
                ErrorLogger.warn(TAG, "⚠️ Flash close API err: $err")
                return@withContext Pair(false, null)
            }

            val txBase64 = json.optString("transactionBase64", "")
            if (txBase64.isBlank()) {
                ErrorLogger.warn(TAG, "⚠️ Flash close: no transaction in response")
                return@withContext Pair(false, null)
            }

            val sig = try {
                wallet.signAndSend(txBase64)
            } catch (ex: Exception) {
                ErrorLogger.warn(TAG, "⚠️ Flash close sign/send failed: ${ex.message}")
                return@withContext Pair(false, null)
            }

            if (sig.isNullOrBlank()) {
                return@withContext Pair(false, null)
            }

            ErrorLogger.info(TAG, "✅ Flash.trade CLOSE ${market.symbol}: ${sig.take(24)}...")
            Pair(true, sig)

        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Flash close exception for ${market.symbol}: ${e.message}")
            Pair(false, null)
        }
    }

    suspend fun closeLivePosition(
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Double = 1.0,
        traderType: String = "Markets",
        flashPositionKey: String? = null,  // V5.9.320: Flash perps key for leveraged crypto closes
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        
        ErrorLogger.info(TAG, "CLOSE LIVE POSITION: $traderType | ${market.symbol}")
        
        val wallet = try {
            WalletManager.getWallet()
        } catch (_: Exception) { null }
        
        if (wallet == null) {
            ErrorLogger.warn(TAG, "No wallet for close")
            return@withContext Pair(false, null)
        }
        
        val walletAddress = wallet.publicKeyB58
        if (walletAddress.isNullOrEmpty()) {
            return@withContext Pair(false, null)
        }

        // V5.9.320: REAL close path —
        //   Flash leveraged crypto (leverage>1, posKey provided) → closeFlashPosition API
        //   Tokenized asset (xStocks / PAXG / EURC) → Jupiter swap <mint> → SOL
        //   Crypto SPOT (V5.9.310 opens)            → Jupiter swap <crypto_mint> → SOL
        //   Legacy USDC-parked positions            → Jupiter swap USDC → SOL

        // ── Flash leveraged close (fastest path) ──────────────────────────────
        if (leverage > 1.0 && market.isCrypto && market.symbol in FLASH_SUPPORTED_PUBLIC) {
            val solPrice = WalletManager.lastKnownSolPrice.takeIf { it > 0 } ?: 150.0
            val sizeUsd  = sizeSol * solPrice * leverage  // full notional
            val (flashOk, flashSig) = closeFlashPosition(
                wallet           = wallet,
                walletAddress    = walletAddress!!,
                market           = market,
                direction        = direction,
                sizeUsd          = sizeUsd,
                positionKeyHint  = flashPositionKey,
            )
            if (flashOk) {
                // Collect close fee
                val feePercent = LEVERAGE_TRADING_FEE_PERCENT
                val feeAmountSol = sizeSol * feePercent
                collectTradingFee(wallet, feeAmountSol, market.symbol, "CLOSE")
                ErrorLogger.info(TAG, "✅ Flash leveraged close success: ${market.symbol}")
                return@withContext Pair(true, flashSig)
            }
            ErrorLogger.warn(TAG, "⚠️ Flash close failed for ${market.symbol} — falling through to Jupiter SPOT close")
            // Fall through to Jupiter close if Flash fails
        }

        val tokenizedMint = TokenizedAssetRegistry.mintFor(market.symbol)
        val cryptoMint = if (market.isCrypto) {
            com.lifecyclebot.perps.DynamicAltTokenRegistry
                .getTokenBySymbol(market.symbol)
                ?.mint
                ?.takeIf { it.isNotBlank() && !it.startsWith("cg:") }
        } else null
        val targetMint = tokenizedMint ?: cryptoMint
        val useTokenized = targetMint != null &&
            (market.isStock || market.isCommodity || market.isMetal || market.isForex || market.isCrypto)

        val (inputMint, amountUnits) = try {
            val balances = wallet.getTokenAccountsWithDecimals()
            // V5.9.475 — RPC RESCUE for Markets close path (mirrors V5.9.467
            // in Executor.liveSell). Operator: 'stocks land in host wallet
            // but never complete a sell.' When DexScreener/Triton/Helius
            // return an empty balance map (transient RPC failure), the old
            // code conflated this with 'mint absent from non-empty map'
            // (genuine 'tokens not held') and silently aborted with a
            // misleading 'No <symbol> balance to close — skipping.' log.
            // The token IS in the wallet — the RPC is just broken right
            // now. Returning (false,null) here makes the caller retry,
            // which is correct behaviour, but the log was deceptive.
            // Emit a clearer warn so the operator can distinguish RPC
            // hiccups from genuine empty wallets.
            if (balances.isEmpty()) {
                ErrorLogger.warn(TAG, "🛟 Markets close RPC blip for ${market.symbol}: balance map EMPTY — RPC degraded, will retry next tick (NOT a real 'no balance')")
                return@withContext Pair(false, null)
            }
            if (useTokenized) {
                val tokenData = balances[targetMint!!]
                if (tokenData == null || tokenData.first <= 0) {
                    ErrorLogger.warn(TAG, "No ${market.symbol} (${targetMint.take(6)}…) balance to close — non-empty map, mint genuinely absent. Skipping (token not held).")
                    return@withContext Pair(false, null)
                }
                val decimals = tokenData.second
                val units = (tokenData.first * Math.pow(10.0, decimals.toDouble())).toLong()
                Pair(targetMint, units)
            } else {
                // V5.9.310: USDC-parked or no-mint position close.
                // Use UniversalBridgeEngine.releaseCapital to swap back to SOL.
                // Cap USDC sell at the position's proportional value — not the whole wallet USDC.
                val usdcData = balances[USDC_MINT]
                if (usdcData == null || usdcData.first <= 0) {
                    ErrorLogger.warn(TAG, "No USDC balance to close position ${market.symbol} — skipping.")
                    return@withContext Pair(false, null)
                }
                val solPrice = WalletManager.lastKnownSolPrice.takeIf { it > 10.0 } ?: 85.0
                val positionUsdcValue = sizeSol * solPrice
                // Cap at actual USDC balance but never over-sell (protects other concurrent USDC positions)
                val usdcToSell = minOf(positionUsdcValue, usdcData.first * 0.95)  // 5% safety margin
                ErrorLogger.info(TAG, "  🌉 ${market.symbol}: USDC-parked close | \$${usdcToSell.fmt(2)} USDC → SOL")
                Pair(USDC_MINT, (usdcToSell * 1_000_000).toLong())
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Failed to read balances on close: ${e.message}")
            return@withContext Pair(false, null)
        }

        if (amountUnits <= 0) {
            ErrorLogger.warn(TAG, "Nothing to close for ${market.symbol}")
            return@withContext Pair(false, null)
        }

        val label = if (useTokenized) TokenizedAssetRegistry.routeLabel(market.symbol) else "USDC-legacy"
        ErrorLogger.info(TAG, "  🧾 CLOSE ${market.symbol} via $label | units=$amountUnits")

        // V5.9.105: snapshot input-mint balance BEFORE swap so we can
        // verify the burn actually happened on-chain. Prevents the sell
        // side of the same phantom class that Executor.kt V5.9.102 fixed.
        val sellBalanceBefore = readTokenUi(wallet, inputMint) ?: 0.0

        val signature = executeJupiterSwap(
            wallet        = wallet,
            walletAddress = walletAddress,
            inputMint     = inputMint,
            outputMint    = SOL_MINT,
            amountLamports = amountUnits,
            slippageBps   = configuredSlippageBps(),
        )

        // V5.9.105: verify sell actually burned the input mint on-chain
        if (signature != null && sellBalanceBefore > 0.0) {
            val sellOk = verifySellExecuted(wallet, inputMint, sellBalanceBefore, market.symbol)
            if (!sellOk) {
                ErrorLogger.warn(TAG, "CLOSE FAILED VERIFY: ${market.symbol} — tx sig landed but no burn. Not recording close.")
                return@withContext Pair(false, null)
            }
        }
        
        return@withContext if (signature != null) {
            // V5.7.7: Collect fee on close as well
            val feePercent = if (leverage <= 1.0) SPOT_TRADING_FEE_PERCENT else LEVERAGE_TRADING_FEE_PERCENT
            val feeAmountSol = sizeSol * feePercent
            collectTradingFee(wallet, feeAmountSol, market.symbol, "CLOSE")
            
            ErrorLogger.info(TAG, "CLOSE SUCCESS: ${signature.take(24)}...")
            Pair(true, signature)
        } else {
            Pair(false, null)
        }
    }
    
    // Stats getters
    fun getTotalExecutions(): Int = totalExecutions.get()
    fun getSuccessfulExecutions(): Int = successfulExecutions.get()
    fun getFailedExecutions(): Int = failedExecutions.get()
    fun getLastExecutionTime(): Long = lastExecutionTime.get()
    fun getTotalFeesCollected(): Double = totalFeesCollectedSol
    
    fun getSuccessRate(): Double {
        val total = totalExecutions.get()
        return if (total > 0) (successfulExecutions.get().toDouble() / total * 100) else 0.0
    }
    
    fun getStats(): Map<String, Any> = mapOf(
        "totalExecutions" to totalExecutions.get(),
        "successfulExecutions" to successfulExecutions.get(),
        "failedExecutions" to failedExecutions.get(),
        "successRate" to getSuccessRate(),
        "totalFeesCollectedSol" to totalFeesCollectedSol,
        "spotFeePercent" to SPOT_TRADING_FEE_PERCENT * 100,
        "leverageFeePercent" to LEVERAGE_TRADING_FEE_PERCENT * 100,
    )
    
    // Helper
    // V5.9.321: Removed private Double.fmt — uses public PerpsModels.fmt
}
