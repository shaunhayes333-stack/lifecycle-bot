package com.lifecyclebot.perps

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
    private const val DEFAULT_SLIPPAGE_BPS = 100  // 1%
    
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
            
            // Check wallet balance (include fee in requirement)
            val balance = wallet.getSolBalance()
            val requiredSol = sizeSol + feeAmountSol + 0.01  // Size + fee + gas
            if (balance < requiredSol) {
                ErrorLogger.warn(TAG, "Insufficient balance: have ${balance.fmt(4)} SOL, need ${requiredSol.fmt(4)} SOL (incl. fee)")
                failedExecutions.incrementAndGet()
                return@withContext Pair(false, null)
            }
            
            ErrorLogger.info(TAG, "  Wallet: ${walletAddress.take(8)}... | Balance: ${balance.fmt(4)} SOL")
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Wallet access error: ${e.message}", e)
            failedExecutions.incrementAndGet()
            return@withContext Pair(false, null)
        }
        
        // Step 2: Determine execution strategy based on market type
        // V5.9.16: Real tokenized asset routing. For non-crypto markets we look
        // up the real on-chain mint via TokenizedAssetRegistry. If present,
        // execute a REAL Jupiter swap SOL → <mint> (LONG) or <mint> → USDC (SHORT).
        // Only if no verified route exists do we refuse live execution.
        val txSignature = when {
            market.isCrypto -> executeCryptoTrade(wallet, walletAddress, market, direction, sizeSol, priceUsd)

            market.isStock || market.isCommodity || market.isMetal || market.isForex -> {
                val mint = TokenizedAssetRegistry.mintFor(market.symbol)
                if (mint == null) {
                    ErrorLogger.warn(TAG,
                        "⛔ LIVE blocked for ${market.symbol}: no verified on-chain route. " +
                        "Add via TokenizedAssetRegistry.register(\"${market.symbol}\", \"<mint>\") or use paper mode.")
                    null
                } else {
                    executeTokenizedAssetTrade(wallet, walletAddress, market, direction, sizeSol, mint)
                }
            }

            else -> {
                ErrorLogger.warn(TAG, "Unknown market type for ${market.symbol}")
                null
            }
        }
        
        // V5.9.15: Non-crypto synthetic trades are blocked above; only crypto
        // markets reach here and require a real on-chain swap signature.
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
            
            // Send to wallet 1
            if (feeWallet1 >= MIN_FEE_SOL) {
                try {
                    wallet.sendSol(FEE_WALLET_1, feeWallet1)
                    ErrorLogger.debug(TAG, "  Fee sent to wallet 1: ${feeWallet1.fmt(6)} SOL")
                } catch (e: Exception) {
                    ErrorLogger.warn(TAG, "  Fee wallet 1 send failed: ${e.message}")
                }
            }
            
            // Send to wallet 2
            if (feeWallet2 >= MIN_FEE_SOL) {
                try {
                    wallet.sendSol(FEE_WALLET_2, feeWallet2)
                    ErrorLogger.debug(TAG, "  Fee sent to wallet 2: ${feeWallet2.fmt(6)} SOL")
                } catch (e: Exception) {
                    ErrorLogger.warn(TAG, "  Fee wallet 2 send failed: ${e.message}")
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
            return executeJupiterSwap(wallet, walletAddress, SOL_MINT, USDC_MINT, (sizeSol * 1_000_000_000).toLong(), DEFAULT_SLIPPAGE_BPS)
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
        if (!bridge.success) { return executeJupiterSwap(wallet, walletAddress, SOL_MINT, USDC_MINT, (sizeSol * 1_000_000_000).toLong(), DEFAULT_SLIPPAGE_BPS) }
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
            slippageBps = DEFAULT_SLIPPAGE_BPS,
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
        if (!bridge.success) { return executeJupiterSwap(wallet, walletAddress, SOL_MINT, USDC_MINT, (sizeSol * 1_000_000_000).toLong(), DEFAULT_SLIPPAGE_BPS) }
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
        ErrorLogger.info(TAG, "  🧾 REAL tokenized trade: ${market.symbol} LONG via $label | ${sizeSol.fmt(4)} SOL")
        val amountLamports = (sizeSol * 1_000_000_000L).toLong()
        // Tokenized-asset pools (xStocks/PAXG/EURC) are thinner than SOL/USDC —
        // use 2% slippage tolerance to avoid spurious Jupiter rejections.
        return executeJupiterSwap(
            wallet         = wallet,
            walletAddress  = walletAddress,
            inputMint      = SOL_MINT,
            outputMint     = targetMint,
            amountLamports = amountLamports,
            slippageBps    = 200,
        )
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
    suspend fun closeLivePosition(
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Double = 1.0,
        traderType: String = "Markets",
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

        // V5.9.16: REAL close path —
        //   Tokenized asset (xStocks / PAXG / EURC) → swap <mint> → SOL
        //   Legacy USDC-parked positions        → swap USDC → SOL (backwards-compat)
        //   Crypto perps should close via JupiterPerps, not here.
        val targetMint = TokenizedAssetRegistry.mintFor(market.symbol)
        val useTokenized = (market.isStock || market.isCommodity || market.isMetal || market.isForex) && targetMint != null

        val (inputMint, amountUnits) = try {
            val balances = wallet.getTokenAccountsWithDecimals()
            if (useTokenized) {
                val tokenData = balances[targetMint!!]
                if (tokenData == null || tokenData.first <= 0) {
                    ErrorLogger.warn(TAG, "No ${market.symbol} (${targetMint.take(6)}…) balance to close — skipping.")
                    return@withContext Pair(false, null)
                }
                val decimals = tokenData.second
                val units = (tokenData.first * Math.pow(10.0, decimals.toDouble())).toLong()
                Pair(targetMint, units)
            } else {
                // Legacy / fallback: USDC-parked position → USDC → SOL
                val usdcData = balances[USDC_MINT]
                if (usdcData == null || usdcData.first <= 0) {
                    ErrorLogger.warn(TAG, "No USDC balance to close legacy position — skipping.")
                    return@withContext Pair(false, null)
                }
                val solPrice = WalletManager.lastKnownSolPrice.takeIf { it > 10.0 } ?: 85.0
                val positionUsdcValue = sizeSol * solPrice
                val usdcToSell = minOf(positionUsdcValue, usdcData.first)
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

        val signature = executeJupiterSwap(
            wallet        = wallet,
            walletAddress = walletAddress,
            inputMint     = inputMint,
            outputMint    = SOL_MINT,
            amountLamports = amountUnits,
            slippageBps   = if (useTokenized) 200 else DEFAULT_SLIPPAGE_BPS,
        )
        
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
    private fun Double.fmt(decimals: Int): String = "%.${decimals}f".format(this)
}
