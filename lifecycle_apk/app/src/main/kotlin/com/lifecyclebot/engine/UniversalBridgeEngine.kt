package com.lifecyclebot.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SolanaWallet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ═══════════════════════════════════════════════════════════════════════════════
// 🌉 UNIVERSAL BRIDGE ENGINE — V1.0.0
// ═══════════════════════════════════════════════════════════════════════════════
//
// Allows the bot to operate from ANY token in the wallet — not just SOL.
// Execution flow:
//
//   1. Wallet holds ANY combination: SOL, USDC, USDT, BTC, ETH, BNB, etc.
//   2. UniversalBridgeEngine.prepareCapital(targetMint, sizeUsd) is called
//      → scans all token balances
//      → finds the best source token (prefers USDC > SOL > other stables > other tokens)
//      → if source ≠ USDC: Jupiter swap source → USDC in one hop
//      → if target ≠ USDC: Jupiter swap USDC → target in second hop
//      → returns lamport amount of target token ready for trade
//
//   3. releaseCapital(sourceMint, amount) reverses the process on trade close
//      → swaps target back to USDC (or held as target if preferred)
//
// Jupiter handles all routing — single-hop or multi-hop as needed.
// No bridge contracts, no external bridges, pure DEX aggregation.
//
// SUPPORTED INPUT TOKENS (auto-detected from wallet):
//   • SOL (native)
//   • USDC, USDT (stable preferred)
//   • WBTC, WETH (major wrapped)
//   • BNB, MATIC, AVAX (wrapped on Solana via Wormhole)
//   • Any SPL token with Jupiter liquidity
// ═══════════════════════════════════════════════════════════════════════════════

object UniversalBridgeEngine {

    private const val TAG = "🌉BridgeEngine"

    // ─── Well-known mint addresses ────────────────────────────────────────────
    const val SOL_MINT    = "So11111111111111111111111111111111111111112"
    const val USDC_MINT   = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val USDT_MINT   = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
    const val WBTC_MINT   = "3NZ9JMVBmGAqocybic2c7LQCJScmgsAZ6vQqTDzcqmJh"  // Wormhole WBTC
    const val WETH_MINT   = "7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs"  // Wormhole WETH
    const val WBNB_MINT   = "9gP2kCy3wA1ctvYWQk75guqXuzoJGLxfP1r6eDcAQW5"  // Wormhole BNB
    const val WAVAX_MINT  = "KgV1GvrHQmRBY8sHQQeUKwTm2r2h8t4C8qt12Cg1ZUE"  // Wormhole AVAX
    const val WMATIC_MINT = "Gz7VkD4MacbEB6yC5XD3HcumEiYx2EtDYYrfikGsvopG" // Wormhole MATIC

    // Priority order for source token selection (best → worst)
    private val SOURCE_PRIORITY = listOf(
        USDC_MINT, USDT_MINT, SOL_MINT,
        WBTC_MINT, WETH_MINT, WBNB_MINT, WAVAX_MINT, WMATIC_MINT
    )

    // Known token decimals (fallback if wallet doesn't report)
    private val TOKEN_DECIMALS = mapOf(
        SOL_MINT   to 9,
        USDC_MINT  to 6,
        USDT_MINT  to 6,
        WBTC_MINT  to 8,
        WETH_MINT  to 8,
        WBNB_MINT  to 8,
        WAVAX_MINT to 8,
        WMATIC_MINT to 8,
    )

    // Approximate USD prices for capacity estimation (updated on each balance scan)
    private val approxPricesUsd = ConcurrentHashMap<String, Double>().apply {
        put(USDC_MINT,  1.0)
        put(USDT_MINT,  1.0)
    }

    // Stats
    private val bridgesExecuted = AtomicInteger(0)
    private val bridgesFailed   = AtomicInteger(0)

    // ─── Jupiter instance ─────────────────────────────────────────────────────
    private val jupiter = JupiterApi("")

    // ═══════════════════════════════════════════════════════════════════════════
    // RESULT TYPES
    // ═══════════════════════════════════════════════════════════════════════════

    data class BridgeResult(
        val success: Boolean,
        val sourceMint: String,       // what we pulled capital from
        val targetMint: String,       // what we ended up with
        val targetAmountRaw: Long,    // raw units of target token
        val targetAmountUi: Double,   // human-readable amount
        val swapTxSig: String?,       // Jupiter tx sig (null if no swap needed)
        val errorMsg: String = "",
    )

    data class WalletCapacity(
        val totalUsdValue: Double,
        val bestSourceMint: String,
        val bestSourceUsdValue: Double,
        val allBalances: Map<String, Double>,  // mint → UI amount
        val allUsdValues: Map<String, Double>, // mint → USD value
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Scan wallet and return total capacity + best source token.
     * Call before deciding trade size so you know what's available.
     */
    suspend fun scanWalletCapacity(wallet: SolanaWallet): WalletCapacity = withContext(Dispatchers.IO) {
        val balances  = mutableMapOf<String, Double>()
        val usdValues = mutableMapOf<String, Double>()

        try {
            // Native SOL
            val sol = wallet.getSolBalance()
            if (sol > 0.001) {
                balances[SOL_MINT] = sol
                val solPriceUsd = WalletManager.lastKnownSolPrice.takeIf { it > 0 } ?: 150.0
                usdValues[SOL_MINT] = sol * solPriceUsd
                approxPricesUsd[SOL_MINT] = solPriceUsd
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "SOL balance error: ${e.message}")
        }

        try {
            // SPL tokens
            val tokens = wallet.getTokenAccountsWithDecimals()
            tokens.forEach { (mint, pair) ->
                val (amount, _) = pair
                if (amount > 0.000001) {
                    balances[mint] = amount
                    // Get USD value from known prices or Jupiter quote
                    val usdVal = estimateUsdValue(mint, amount)
                    usdValues[mint] = usdVal
                }
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "SPL token scan error: ${e.message}")
        }

        // Find best source by priority list, then by USD value
        val bestMint = SOURCE_PRIORITY.firstOrNull { balances.containsKey(it) && (usdValues[it] ?: 0.0) > 1.0 }
            ?: balances.maxByOrNull { usdValues[it.key] ?: 0.0 }?.key
            ?: SOL_MINT

        val totalUsd   = usdValues.values.sum()
        val bestUsd    = usdValues[bestMint] ?: 0.0

        ErrorLogger.info(TAG, "💼 Wallet capacity: \$${"%.2f".format(totalUsd)} total | best=${mintLabel(bestMint)} \$${"%.2f".format(bestUsd)}")

        WalletCapacity(
            totalUsdValue      = totalUsd,
            bestSourceMint     = bestMint,
            bestSourceUsdValue = bestUsd,
            allBalances        = balances.toMap(),
            allUsdValues       = usdValues.toMap(),
        )
    }

    /**
     * Convert any held token → USDC (intermediate step before buying target).
     * If source IS USDC already, no swap needed.
     *
     * @param wallet  Connected wallet
     * @param sourceMint  Token to convert FROM (use scanWalletCapacity to pick best)
     * @param sizeUsd  How many USD worth to convert
     * @return BridgeResult
     */
    suspend fun bridgeToUsdc(
        wallet: SolanaWallet,
        sourceMint: String,
        sizeUsd: Double,
    ): BridgeResult = withContext(Dispatchers.IO) {

        if (sourceMint == USDC_MINT) {
            // Already USDC — just confirm balance is enough
            val usdcBal = try {
                wallet.getTokenAccountsWithDecimals()[USDC_MINT]?.first ?: 0.0
            } catch (_: Exception) { 0.0 }

            if (usdcBal >= sizeUsd * 0.99) {
                val rawAmount = (minOf(usdcBal, sizeUsd) * 1_000_000).toLong()
                return@withContext BridgeResult(
                    success        = true,
                    sourceMint     = USDC_MINT,
                    targetMint     = USDC_MINT,
                    targetAmountRaw= rawAmount,
                    targetAmountUi = rawAmount / 1_000_000.0,
                    swapTxSig      = null,
                )
            } else {
                return@withContext BridgeResult(false, USDC_MINT, USDC_MINT, 0, 0.0, null, "Insufficient USDC balance")
            }
        }

        // Calculate how much of source token = sizeUsd
        val sourcePrice = approxPricesUsd[sourceMint] ?: estimateTokenPriceUsd(sourceMint)
        if (sourcePrice <= 0) {
            return@withContext BridgeResult(false, sourceMint, USDC_MINT, 0, 0.0, null, "Cannot price $sourceMint")
        }

        val sourceAmount = sizeUsd / sourcePrice
        val sourceDecimals = TOKEN_DECIMALS[sourceMint] ?: 9
        val sourceAmountRaw: Long

        if (sourceMint == SOL_MINT) {
            sourceAmountRaw = (sourceAmount * 1_000_000_000L).toLong()
        } else {
            val balance = try { wallet.getTokenAccountsWithDecimals()[sourceMint] } catch (_: Exception) { null }
            if (balance == null || balance.first < sourceAmount * 0.95) {
                return@withContext BridgeResult(false, sourceMint, USDC_MINT, 0, 0.0, null,
                    "Insufficient ${mintLabel(sourceMint)}: have ${balance?.first ?: 0.0}, need $sourceAmount")
            }
            sourceAmountRaw = (sourceAmount * Math.pow(10.0, sourceDecimals.toDouble())).toLong()
        }

        ErrorLogger.info(TAG, "🌉 Bridging ${mintLabel(sourceMint)} → USDC | \$${"%.2f".format(sizeUsd)} | ${sourceAmount.fmt(6)} ${mintLabel(sourceMint)}")

        val txSig = executeJupiterSwap(
            wallet        = wallet,
            inputMint     = sourceMint,
            outputMint    = USDC_MINT,
            amountRaw     = sourceAmountRaw,
            slippageBps   = 150,
        )

        if (txSig == null) {
            bridgesFailed.incrementAndGet()
            return@withContext BridgeResult(false, sourceMint, USDC_MINT, 0, 0.0, null, "Jupiter swap failed")
        }

        bridgesExecuted.incrementAndGet()

        // Return expected USDC output
        val usdcRaw  = (sizeUsd * 1_000_000 * 0.985).toLong()  // 1.5% slippage buffer
        ErrorLogger.info(TAG, "✅ Bridge complete: ~${usdcRaw / 1_000_000.0} USDC | tx=${txSig.take(16)}")

        BridgeResult(
            success        = true,
            sourceMint     = sourceMint,
            targetMint     = USDC_MINT,
            targetAmountRaw= usdcRaw,
            targetAmountUi = usdcRaw / 1_000_000.0,
            swapTxSig      = txSig,
        )
    }

    /**
     * Full pipeline: ANY held token → target token (2 hops max: source → USDC → target).
     *
     * For SOL purchases (the main meme engine): sourceMint=SOL, targetMint=SOL → direct swap.
     * For crypto alts: source → USDC → target token mint.
     * For stocks/commodities/forex: source → USDC (used as collateral, tracked in-app).
     *
     * @param wallet       Connected wallet
     * @param targetMint   Token you want to end up with (USDC for collateral-based trades)
     * @param sizeUsd      USD value to allocate
     * @param sourceMint   Force a specific source (null = auto-select best)
     */
    suspend fun prepareCapital(
        wallet: SolanaWallet,
        targetMint: String,
        sizeUsd: Double,
        sourceMint: String? = null,
    ): BridgeResult = withContext(Dispatchers.IO) {

        // Step 1: scan wallet to find best source
        val capacity = scanWalletCapacity(wallet)
        val src      = sourceMint ?: capacity.bestSourceMint

        ErrorLogger.info(TAG, "🌉 prepareCapital: \$${"%.2f".format(sizeUsd)} | src=${mintLabel(src)} → target=${mintLabel(targetMint)}")

        // Step 2: if source == target, just confirm balance
        if (src == targetMint) {
            return@withContext bridgeToUsdc(wallet, src, sizeUsd).copy(targetMint = targetMint)
        }

        // Step 3: if target == USDC, single hop source → USDC
        if (targetMint == USDC_MINT) {
            return@withContext bridgeToUsdc(wallet, src, sizeUsd)
        }

        // Step 4: if source == USDC, single hop USDC → target
        if (src == USDC_MINT) {
            val usdcBal = try { wallet.getTokenAccountsWithDecimals()[USDC_MINT]?.first ?: 0.0 } catch (_: Exception) { 0.0 }
            val usdcRaw  = (minOf(usdcBal, sizeUsd) * 1_000_000).toLong()

            if (usdcRaw <= 0) return@withContext BridgeResult(false, USDC_MINT, targetMint, 0, 0.0, null, "No USDC available")

            val txSig = executeJupiterSwap(
                wallet      = wallet,
                inputMint   = USDC_MINT,
                outputMint  = targetMint,
                amountRaw   = usdcRaw,
                slippageBps = 150,
            ) ?: return@withContext BridgeResult(false, USDC_MINT, targetMint, 0, 0.0, null, "USDC→target swap failed")

            bridgesExecuted.incrementAndGet()
            return@withContext BridgeResult(
                success        = true,
                sourceMint     = USDC_MINT,
                targetMint     = targetMint,
                targetAmountRaw= usdcRaw,
                targetAmountUi = usdcRaw / 1_000_000.0,
                swapTxSig      = txSig,
            )
        }

        // ════════════════════════════════════════════════════════════════════
        // Step 5 — V5.9.495z19 SINGLE-HOP REWRITE.
        //
        // OPERATOR-MANDATED FIX: do NOT do a manual SOL → USDC then USDC →
        // target. That's two separate non-atomic Jupiter swaps that left the
        // wallet stranded with USDC if leg-2 failed (the "STRIKE only bought
        // USDC" bug). It also poisoned EntryAI with phantom successes.
        //
        // The correct path on Solana is ONE Jupiter call with
        //   inputMint  = source mint (e.g. SOL)
        //   outputMint = target mint (e.g. STRIKE)
        // Jupiter Meta-Aggregator routes through USDC internally as needed,
        // and the route is atomic — either the target lands or the whole
        // transaction reverts. No intermediate-asset stranding possible.
        //
        // If the user genuinely wants a non-atomic two-leg flow (e.g. to
        // accumulate USDC reserve first), they should call bridgeToUsdc()
        // directly. prepareCapital() now ONLY does atomic single-call routes.
        // ════════════════════════════════════════════════════════════════════

        val sourcePrice = approxPricesUsd[src] ?: estimateTokenPriceUsd(src)
        if (sourcePrice <= 0) {
            return@withContext BridgeResult(false, src, targetMint, 0, 0.0, null, "Cannot price ${mintLabel(src)}")
        }
        val sourceAmount = sizeUsd / sourcePrice
        val sourceDecimals = TOKEN_DECIMALS[src] ?: 9
        val sourceAmountRaw: Long = if (src == SOL_MINT) {
            (sourceAmount * 1_000_000_000L).toLong()
        } else {
            (sourceAmount * Math.pow(10.0, sourceDecimals.toDouble())).toLong()
        }
        if (sourceAmountRaw <= 0) {
            return@withContext BridgeResult(false, src, targetMint, 0, 0.0, null,
                "Computed source amount is 0 (${mintLabel(src)} bal too low for \$${"%.2f".format(sizeUsd)})")
        }

        // Pre-trade target balance — used to verify the swap actually delivered.
        val preTargetBal: Double = try {
            wallet.getTokenAccountsWithDecimals()[targetMint]?.first ?: 0.0
        } catch (_: Exception) { 0.0 }

        com.lifecyclebot.engine.execution.Forensics.log(
            com.lifecyclebot.engine.execution.Forensics.Event.JUPITER_V2_ORDER_REQUEST,
            mint = targetMint,
            msg = "single-hop ${mintLabel(src)} → ${mintLabel(targetMint)} amt=$sourceAmountRaw",
        )

        val txSig = executeJupiterSwap(
            wallet      = wallet,
            inputMint   = src,
            outputMint  = targetMint,        // ← SINGLE atomic call to the real target
            amountRaw   = sourceAmountRaw,
            slippageBps = 200,
        ) ?: run {
            bridgesFailed.incrementAndGet()
            com.lifecyclebot.engine.execution.ExecutionStatusRegistry.stamp(
                targetMint,
                com.lifecyclebot.engine.execution.ExecutionStatus.FAILED_TX_CONFIRMED,
            )
            com.lifecyclebot.engine.execution.Forensics.log(
                com.lifecyclebot.engine.execution.Forensics.Event.BUY_FAILED_NO_TARGET_TOKEN,
                mint = targetMint,
                msg = "single-hop swap failed (no signature)",
            )
            return@withContext BridgeResult(false, src, targetMint, 0, 0.0, null,
                "Atomic ${mintLabel(src)}→${mintLabel(targetMint)} swap failed — no fallback to two-leg per V5.9.495z19 rule")
        }

        // ── Post-trade verification: did the target token actually land? ──
        // V5.9.495z46 P0 — operator forensics 0508_143519 spec item B.
        //
        // Old code did a single 2.5s RPC poll then declared BUY_FAILED if
        // delta=0. Forensics showed TRUMP/KMNO marked BUY_FAILED while the
        // wallet UI clearly held the tokens — a stale RPC read was killing
        // legitimate buys.
        //
        // New flow:
        //   1. Parse the confirmed transaction's pre/postTokenBalances via
        //      TxParseHelper. This is the on-chain ground truth and is not
        //      subject to RPC token-account propagation lag.
        //   2. If parser shows positive target delta → BUY_CONFIRMED.
        //   3. Otherwise retry wallet RPC with backoff over up to ~22s
        //      (5 attempts at 2/4/6/4/6s) before declaring failure.
        //
        // The combined window covers the operator-spec 15–30s requirement
        // while still surfacing a hard failure when the swap genuinely
        // didn't land.
        var deltaUi: Double = 0.0
        var deltaRaw: Long = 0L
        var targetDecimals: Int = TOKEN_DECIMALS[targetMint] ?: 6
        var verifySource: String = "tx_parse_pending"

        // Step 1 — chain-truth parse via TxParseHelper.
        try {
            kotlinx.coroutines.delay(1_500)   // brief settle for RPC to see the tx
            val parsed = com.lifecyclebot.engine.execution.TxParseHelper.parseAll(wallet, txSig)
            val targetDelta = parsed?.tokenDeltas?.get(targetMint)
            if (targetDelta != null && targetDelta.rawDelta.signum() > 0) {
                targetDecimals = targetDelta.decimals
                deltaRaw = targetDelta.rawDelta.toLong().coerceAtLeast(0L)
                deltaUi = java.math.BigDecimal(targetDelta.rawDelta)
                    .movePointLeft(targetDecimals).toDouble()
                verifySource = "tx_parse_meta"
                com.lifecyclebot.engine.execution.Forensics.log(
                    com.lifecyclebot.engine.execution.Forensics.Event.TX_PARSE_INTERMEDIATE_ONLY,
                    mint = targetMint,
                    msg = "tx_parse_OK target+=$deltaRaw raw (${"%.6f".format(deltaUi)} ui) sig=${txSig.take(12)}",
                )
            }
        } catch (_: Throwable) { /* fall through to RPC retry */ }

        // Step 2 — only if tx_parse missed, retry wallet RPC with backoff.
        if (deltaRaw <= 0L) {
            val backoffsMs = longArrayOf(2_000L, 4_000L, 6_000L, 4_000L, 6_000L)
            var attempt = 0
            while (attempt < backoffsMs.size && deltaRaw <= 0L) {
                kotlinx.coroutines.delay(backoffsMs[attempt])
                attempt++
                val postEntry: Pair<Double, Int>? = try {
                    wallet.getTokenAccountsWithDecimals()[targetMint]
                } catch (_: Exception) { null }
                val postTargetBal = postEntry?.first ?: 0.0
                targetDecimals = postEntry?.second ?: targetDecimals
                val attemptDeltaUi = postTargetBal - preTargetBal
                if (attemptDeltaUi > 0.0) {
                    deltaUi = attemptDeltaUi
                    deltaRaw = (deltaUi * Math.pow(10.0, targetDecimals.toDouble())).toLong().coerceAtLeast(0L)
                    verifySource = "rpc_retry_attempt_$attempt"
                    com.lifecyclebot.engine.execution.Forensics.log(
                        com.lifecyclebot.engine.execution.Forensics.Event.TX_PARSE_INTERMEDIATE_ONLY,
                        mint = targetMint,
                        msg = "rpc_retry_OK attempt=$attempt postTarget=$postTargetBal preTarget=$preTargetBal",
                    )
                    break
                }
            }
        }

        if (deltaRaw <= 0L) {
            // Target token did NOT land. This should be impossible on an atomic
            // route, but we double-check the wallet for residual USDC/USDT
            // and create an IntermediateAssetRecovery so the orphan asset is
            // tracked, not silently ignored.
            val accounts = try { wallet.getTokenAccountsWithDecimals() } catch (_: Exception) { emptyMap() }
            val usdcUi = accounts[USDC_MINT]?.first ?: 0.0
            val usdtUi = accounts[USDT_MINT]?.first ?: 0.0
            if (targetMint != USDC_MINT && usdcUi > 0.001) {
                val usdcRaw = (usdcUi * 1_000_000).toLong()
                com.lifecyclebot.engine.execution.IntermediateAssetRecovery.create(
                    originalIntentId = "legacy-prep-$txSig",
                    intendedTargetMint = targetMint,
                    intermediateMint = USDC_MINT,
                    intermediateRawAmount = usdcRaw,
                    firstLegSignature = txSig,
                )
                com.lifecyclebot.engine.execution.Forensics.log(
                    com.lifecyclebot.engine.execution.Forensics.Event.TX_PARSE_INTERMEDIATE_ONLY,
                    mint = targetMint,
                    msg = "USDC residue=$usdcUi after target-bound swap (atomic route violated?)",
                )
            } else if (targetMint != USDT_MINT && usdtUi > 0.001) {
                val usdtRaw = (usdtUi * 1_000_000).toLong()
                com.lifecyclebot.engine.execution.IntermediateAssetRecovery.create(
                    originalIntentId = "legacy-prep-$txSig",
                    intendedTargetMint = targetMint,
                    intermediateMint = USDT_MINT,
                    intermediateRawAmount = usdtRaw,
                    firstLegSignature = txSig,
                )
            }
            bridgesFailed.incrementAndGet()
            com.lifecyclebot.engine.execution.ExecutionStatusRegistry.stamp(
                targetMint,
                com.lifecyclebot.engine.execution.ExecutionStatus.INTERMEDIATE_ASSET_HELD,
            )
            com.lifecyclebot.engine.execution.Forensics.log(
                com.lifecyclebot.engine.execution.Forensics.Event.BUY_FAILED_NO_TARGET_TOKEN,
                mint = targetMint,
                msg = "tx=${txSig.take(12)} preTarget=$preTargetBal verify=$verifySource (no positive delta after tx_parse + rpc retry)",
            )
            return@withContext BridgeResult(
                success = false,
                sourceMint = src,
                targetMint = targetMint,
                targetAmountRaw = 0,
                targetAmountUi = 0.0,
                swapTxSig = txSig,
                errorMsg = "Target token did not land after Jupiter swap — recovery record created if intermediate held",
            )
        }

        bridgesExecuted.incrementAndGet()
        com.lifecyclebot.engine.execution.ExecutionStatusRegistry.stamp(
            targetMint,
            com.lifecyclebot.engine.execution.ExecutionStatus.FINAL_TOKEN_VERIFIED,
        )
        com.lifecyclebot.engine.execution.Forensics.log(
            com.lifecyclebot.engine.execution.Forensics.Event.FINAL_TOKEN_VERIFIED,
            mint = targetMint,
            msg = "raw=$deltaRaw ui=$deltaUi tx=${txSig.take(12)}",
        )
        ErrorLogger.info(TAG, "✅ Single-hop atomic complete: ${mintLabel(src)} → ${mintLabel(targetMint)} | " +
            "ui=${"%.6f".format(deltaUi)} | tx=${txSig.take(16)}")

        BridgeResult(
            success        = true,
            sourceMint     = src,
            targetMint     = targetMint,
            targetAmountRaw= deltaRaw,
            targetAmountUi = deltaUi,
            swapTxSig      = txSig,
        )
    }

    /**
     * Close a trade position — swap target token back to preferred holding.
     * After close: target → USDC or SOL (based on original source).
     */
    suspend fun releaseCapital(
        wallet: SolanaWallet,
        targetMint: String,
        returnToMint: String = USDC_MINT,
    ): BridgeResult = withContext(Dispatchers.IO) {

        if (targetMint == returnToMint) {
            return@withContext BridgeResult(true, targetMint, returnToMint, 0, 0.0, null)
        }

        // Get actual balance of the target token
        val balRaw: Long = try {
            val accounts = wallet.getTokenAccountsWithDecimals()
            val entry    = accounts[targetMint]
            if (entry != null) {
                val (amount, decimals) = entry
                (amount * Math.pow(10.0, decimals.toDouble())).toLong()
            } else if (targetMint == SOL_MINT) {
                val sol = wallet.getSolBalance()
                ((sol - 0.01).coerceAtLeast(0.0) * 1_000_000_000L).toLong()
            } else 0L
        } catch (_: Exception) { 0L }

        if (balRaw <= 0) return@withContext BridgeResult(false, targetMint, returnToMint, 0, 0.0, null, "Nothing to release")

        val txSig = executeJupiterSwap(
            wallet      = wallet,
            inputMint   = targetMint,
            outputMint  = returnToMint,
            amountRaw   = balRaw,
            slippageBps = 200,
        ) ?: return@withContext BridgeResult(false, targetMint, returnToMint, 0, 0.0, null, "Release swap failed")

        ErrorLogger.info(TAG, "✅ Capital released: ${mintLabel(targetMint)} → ${mintLabel(returnToMint)} | tx=${txSig.take(16)}")
        BridgeResult(true, targetMint, returnToMint, balRaw, balRaw / 1_000_000.0, txSig)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    internal suspend fun executeJupiterSwap(
        wallet: SolanaWallet,
        inputMint: String,
        outputMint: String,
        amountRaw: Long,
        slippageBps: Int,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val quote = jupiter.getQuote(
                inputMint  = inputMint,
                outputMint = outputMint,
                amountRaw  = amountRaw,
                slippageBps= slippageBps,
            )
            if (quote.outAmount <= 0) {
                ErrorLogger.warn(TAG, "Quote returned 0 output for ${mintLabel(inputMint)} → ${mintLabel(outputMint)}")
                return@withContext null
            }
            if (quote.priceImpactPct > 5.0) {
                ErrorLogger.warn(TAG, "Price impact too high: ${quote.priceImpactPct}% > 5%")
                return@withContext null
            }

            val txResult = jupiter.buildSwapTx(quote, wallet.publicKeyB58)
            if (txResult.txBase64.isEmpty()) {
                ErrorLogger.warn(TAG, "Empty swap tx for bridge")
                return@withContext null
            }

            wallet.signSendAndConfirm(
                txBase64      = txResult.txBase64,
                useJito       = false,
                jitoTipLamports = 0,
                ultraRequestId  = if (quote.isUltra) txResult.requestId else null,
                jupiterApiKey   = "",
                isRfqRoute      = txResult.isRfqRoute,
            )
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Jupiter bridge swap error: ${e.message}", e)
            null
        }
    }

    private fun estimateUsdValue(mint: String, amount: Double): Double {
        val known = approxPricesUsd[mint]
        if (known != null) return amount * known
        return when (mint) {
            SOL_MINT    -> amount * (WalletManager.lastKnownSolPrice.takeIf { it > 0 } ?: 150.0)
            USDC_MINT,
            USDT_MINT   -> amount
            WBTC_MINT   -> amount * 85_000.0   // rough estimate
            WETH_MINT   -> amount * 3_000.0
            WBNB_MINT   -> amount * 600.0
            WAVAX_MINT  -> amount * 35.0
            WMATIC_MINT -> amount * 0.8
            else        -> 0.0  // unknown token
        }
    }

    private fun estimateTokenPriceUsd(mint: String): Double {
        return approxPricesUsd[mint] ?: when (mint) {
            SOL_MINT    -> WalletManager.lastKnownSolPrice.takeIf { it > 0 } ?: 150.0
            USDC_MINT,
            USDT_MINT   -> 1.0
            WBTC_MINT   -> 85_000.0
            WETH_MINT   -> 3_000.0
            WBNB_MINT   -> 600.0
            else        -> 0.0
        }
    }

    fun mintLabel(mint: String): String = when (mint) {
        SOL_MINT    -> "SOL"
        USDC_MINT   -> "USDC"
        USDT_MINT   -> "USDT"
        WBTC_MINT   -> "WBTC"
        WETH_MINT   -> "WETH"
        WBNB_MINT   -> "WBNB"
        WAVAX_MINT  -> "WAVAX"
        WMATIC_MINT -> "WMATIC"
        else        -> mint.take(8) + "…"
    }

    fun getBridgeStats() = mapOf(
        "executed" to bridgesExecuted.get(),
        "failed"   to bridgesFailed.get(),
        "successRate" to if (bridgesExecuted.get() + bridgesFailed.get() > 0)
            "${(bridgesExecuted.get() * 100.0 / (bridgesExecuted.get() + bridgesFailed.get())).toInt()}%"
            else "N/A"
    )

    private fun Double.fmt(d: Int) = "%.${d}f".format(this)
}
