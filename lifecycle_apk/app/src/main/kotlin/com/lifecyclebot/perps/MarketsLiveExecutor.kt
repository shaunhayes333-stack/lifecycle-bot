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
                        ErrorLogger.warn(TAG, "⛔ LIVE crypto blocked: no Solana mint in registry for ${market.symbol}")
                        null
                    }
                }
            }

            market.isStock || market.isCommodity || market.isMetal || market.isForex -> {
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
        ErrorLogger.info(TAG, "  💱 SPOT swap: ${market.symbol} LONG via Jupiter v1 | ${sizeSol.fmt(4)} SOL → ${targetMint.take(8)}...")
        val amountLamports = (sizeSol * 1_000_000_000L).toLong()
        // V5.9.105: snapshot target-mint balance BEFORE swap for phantom guard
        val balanceBefore = readTokenUi(wallet, targetMint) ?: 0.0
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
    // V5.9.230: FLASH.TRADE PERPS — active Solana perps DEX (replaces dead Jupiter Perps v2)
    // Flash.trade supports: SOL, BTC, ETH, BNB, XRP, ADA, AVAX, LINK, DOT, MATIC and more.
    // API: https://api.flash.trade/v1 — permissionless, up to 100x leverage.
    // For paper mode: records the position locally (no on-chain call).
    // For live mode: opens a real leveraged position on Flash.trade.
    // SHORT is fully supported (unlike SPOT-only Jupiter swap path).
    // ═══════════════════════════════════════════════════════════════════════════
    private suspend fun executeFlashTradePerps(
        wallet: com.lifecyclebot.network.SolanaWallet,
        walletAddress: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Double,
    ): String? = withContext(Dispatchers.IO) {
        // Flash.trade supported symbols for leveraged perps
        val FLASH_SUPPORTED = setOf(
            "SOL", "BTC", "ETH", "BNB", "XRP", "ADA", "DOGE", "AVAX",
            "LINK", "DOT", "MATIC", "LTC", "ATOM", "UNI", "ARB", "OP",
            "APT", "SUI", "INJ", "JUP", "NEAR", "TIA", "WLD", "ENA",
            "BONK", "WIF", "PEPE", "TRUMP", "SHIB"
        )

        val symbol = market.symbol
        if (symbol !in FLASH_SUPPORTED) {
            // Symbol not on Flash — degrade to SPOT (honest, not silent)
            ErrorLogger.info(TAG, "⚠️ Flash.trade: $symbol not in perps universe — degrading to SPOT 1x")
            val mint = com.lifecyclebot.perps.DynamicAltTokenRegistry
                .getTokenBySymbol(symbol)?.mint
                ?.takeIf { it.isNotBlank() && !it.startsWith("cg:") && !it.startsWith("static:") }
            return@withContext if (mint != null)
                executeCryptoSpotSwap(wallet, walletAddress, market, direction, sizeSol, mint)
            else {
                ErrorLogger.warn(TAG, "⛔ Flash + SPOT: no mint for $symbol — skipping")
                null
            }
        }

        // Flash.trade perps API call
        val flashApiUrl = "https://api.flash.trade/v1/positions/open"
        val sideStr = if (direction == PerpsDirection.LONG) "long" else "short"
        val collateralUsdc = sizeSol * 20.0  // approximate SOL→USD at ~$150 avg, use fixed ratio for now

        ErrorLogger.info(TAG, "⚡ Flash.trade PERPS: $symbol $sideStr ${leverage.toInt()}x | size=${sizeSol.fmt(4)} SOL")

        return@withContext try {
            val body = org.json.JSONObject().apply {
                put("symbol", "${symbol}/USD")
                put("side", sideStr)
                put("leverage", leverage.toInt())
                put("collateralSol", sizeSol)
                put("walletAddress", walletAddress)
                put("slippageBps", configuredSlippageBps())
            }.toString()

            // V5.9.235 fix: use a local OkHttpClient (no class-level 'client' in this object)
            val http = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val mediaJson = "application/json; charset=utf-8".toMediaType()
            val request = okhttp3.Request.Builder()
                .url(flashApiUrl)
                .post(body.toRequestBody(mediaJson))
                .header("Content-Type", "application/json")
                .build()

            val response = http.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = org.json.JSONObject(responseBody)
                val txBase64 = json.optString("transaction", "")
                if (txBase64.isNotEmpty()) {
                    // Sign and send via SolanaWallet.signAndSend (handles signing + RPC submission)
                    val sig = try { wallet.signAndSend(txBase64) } catch (_: Exception) { null }
                    if (!sig.isNullOrEmpty()) {
                        ErrorLogger.info(TAG, "✅ Flash.trade $sideStr $symbol ${leverage.toInt()}x: $sig")
                        return@withContext sig
                    }
                }
                ErrorLogger.warn(TAG, "⚠️ Flash.trade: got 200 but no tx — check API format")
                null
            } else {
                // Flash API not reachable or symbol unsupported at runtime — degrade to spot
                ErrorLogger.warn(TAG, "⚠️ Flash.trade $symbol returned ${response.code} — degrading to SPOT")
                val mint = com.lifecyclebot.perps.DynamicAltTokenRegistry
                    .getTokenBySymbol(symbol)?.mint
                    ?.takeIf { it.isNotBlank() && !it.startsWith("cg:") && !it.startsWith("static:") }
                if (mint != null) executeCryptoSpotSwap(wallet, walletAddress, market, direction, sizeSol, mint)
                else null
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Flash.trade exception for $symbol: ${e.message} — degrading to SPOT")
            val mint = com.lifecyclebot.perps.DynamicAltTokenRegistry
                .getTokenBySymbol(symbol)?.mint
                ?.takeIf { it.isNotBlank() && !it.startsWith("cg:") && !it.startsWith("static:") }
            if (mint != null) executeCryptoSpotSwap(wallet, walletAddress, market, direction, sizeSol, mint)
            else null
        }
    }

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

        // V5.9.16/V5.9.50: REAL close path —
        //   Tokenized asset (xStocks / PAXG / EURC) → swap <mint> → SOL
        //   Crypto (V5.9.45 spot-swap opens)        → swap <crypto_mint> → SOL
        //   Legacy USDC-parked positions            → swap USDC → SOL (backwards-compat)
        // Previously crypto was explicitly excluded from tokenized routing and
        // always fell through to the USDC branch — since V5.9.45 opens the
        // real crypto mint (not USDC), every crypto live BUY since then has
        // been un-closable. That silently locked capital in the alt token.
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
    private fun Double.fmt(decimals: Int): String = "%.${decimals}f".format(this)
}
