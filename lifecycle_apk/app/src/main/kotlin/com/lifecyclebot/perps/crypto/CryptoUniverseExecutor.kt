package com.lifecyclebot.perps.crypto

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.HostWalletTokenTracker
import com.lifecyclebot.engine.LiveExecutionScope
import com.lifecyclebot.engine.TokenLifecycleTracker
import com.lifecyclebot.engine.UniversalBridgeEngine
import com.lifecyclebot.engine.WalletManager
import com.lifecyclebot.engine.execution.MintIntegrityGate
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SolanaWallet
import com.lifecyclebot.perps.PerpsDirection
import com.lifecyclebot.perps.PerpsMarket
import kotlinx.coroutines.CancellationException

/**
 * V5.9.607 — strict Crypto Universe live executor.
 *
 * Crypto Universe is NOT limited to SOL-backed assets or a curated wrapped list.
 * It can live-trade any non-SOL crypto token that resolves to a real Solana SPL
 * mint and proves a Jupiter route from the app's SOL/USDC capital rail into the
 * intended target mint. Quote/build/sign/send/confirm/wallet-delta all run in
 * LiveExecutionScope so scan/UI/watchlist cancellation cannot abort active txs.
 */
object CryptoUniverseExecutor {

    private const val TAG = "CryptoUniverseExecutor"
    private const val USDC_MINT = UniversalBridgeEngine.USDC_MINT
    private const val SOL_MINT = UniversalBridgeEngine.SOL_MINT
    private const val MAX_PRICE_IMPACT_PCT = 3.0
    private const val SLIPPAGE_BPS = 200

    sealed class Outcome {
        data class Executed(
            val txSig: String,
            val mint: String,
            val filledQtyRaw: java.math.BigInteger,
            val decimals: Int,
            val proofState: String,
        ) : Outcome()
        data class RouteDeferred(val resolution: CryptoUniverseRouteResolver.Resolution) : Outcome()
        data class ExecFailed(
            val resolution: CryptoUniverseRouteResolver.Resolution,
            val reason: String,
        ) : Outcome()
    }

    suspend fun executeLiveTrade(
        positionId: String,
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Double,
        priceUsd: Double,
        traderType: String = "CryptoUniverse",
        assetSymbol6493: String? = null,
        targetMint6493: String? = null,
        targetChainId6544: String? = null,
    ): Outcome = LiveExecutionScope.runAwaited("CU_${assetSymbol6493 ?: market.symbol}_${direction.name}") { job ->
        val symbol = assetSymbol6493?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: market.symbol.uppercase()
        val wallet = try { WalletManager.getWallet() } catch (_: Throwable) { null }
        if (wallet == null) {
            val r = CryptoUniverseRouteResolver.Resolution(
                symbol, CryptoExecutionRoute.NO_ROUTE_AVAILABLE, null,
                CryptoUniverseDiagCodes.ROUTE_NO_EXECUTOR, "No wallet connected.", executable = false,
            )
            return@runAwaited Outcome.RouteDeferred(r)
        }
        val walletSol = try { wallet.getSolBalance() } catch (_: Throwable) { 0.0 }
        val resolution = CryptoUniverseRouteResolver.resolve(
            market, walletSol, sizeSol, assetSymbol6493 = symbol, targetMint6493 = targetMint6493, targetChainId6544 = targetChainId6544,
        )
        val mint = resolution.mint

        CryptoUniverseForensics.logPhase(
            phase = "CU_ROUTE_ELIGIBILITY_CHECK",
            symbol = symbol,
            intendedMint = mint,
            resolvedMint = mint,
            inputMint = USDC_MINT,
            outputMint = mint,
            routeType = resolution.route.name,
            slippageBps = SLIPPAGE_BPS,
            priceImpactPct = null,
            txSignature = null,
            jobId = job.id,
            message = resolution.humanMessage,
        )

        if (!resolution.executable || mint.isNullOrBlank()) {
            CryptoUniverseForensics.logPhase(
                phase = "CU_ROUTE_FORCED_PAPER",
                symbol = symbol,
                intendedMint = mint,
                resolvedMint = mint,
                inputMint = USDC_MINT,
                outputMint = mint,
                routeType = resolution.route.name,
                slippageBps = SLIPPAGE_BPS,
                priceImpactPct = null,
                txSignature = null,
                jobId = job.id,
                message = resolution.diagCode + " | " + resolution.humanMessage,
            )
            CryptoUniverseForensics.logRouteOutcome(symbol, mint ?: "no-mint", resolution.diagCode, resolution.humanMessage, sizeSol)
            return@runAwaited Outcome.RouteDeferred(resolution)
        }

        when (val gate = MintIntegrityGate.validatePreBuy(symbol, mint)) {
            is MintIntegrityGate.Result.Reject -> {
                val paper = resolution.copy(
                    route = CryptoExecutionRoute.PAPER_ONLY,
                    diagCode = CryptoUniverseDiagCodes.ROUTE_DISCOVERY_FAILED,
                    humanMessage = "MintIntegrityGate rejected: ${gate.code} — ${gate.reason}",
                    executable = false,
                )
                CryptoUniverseForensics.logPhase(
                    phase = "CU_ROUTE_FORCED_PAPER",
                    symbol = symbol,
                    intendedMint = mint,
                    resolvedMint = mint,
                    inputMint = USDC_MINT,
                    outputMint = mint,
                    routeType = paper.route.name,
                    slippageBps = SLIPPAGE_BPS,
                    priceImpactPct = null,
                    txSignature = null,
                    jobId = job.id,
                    message = paper.humanMessage,
                )
                return@runAwaited Outcome.RouteDeferred(paper)
            }
            else -> Unit
        }

        val solPriceUsd = WalletManager.lastKnownSolPrice.takeIf { it > 0.0 } ?: 150.0
        val sizeUsd = sizeSol * solPriceUsd
        val routeProbeUsdcRaw = (sizeUsd.coerceAtLeast(1.0) * 1_000_000.0).toLong()

        // Hard route proof: USDC -> target. The capital engine may source SOL or
        // another wallet token, but if Jupiter cannot route the USDC rail to the
        // target mint, Crypto Universe must stay paper-only.
        val routeQuote = try {
            CryptoUniverseForensics.logPhase("CU_QUOTE_REQUEST", symbol, mint, mint, USDC_MINT, mint, resolution.route.name, SLIPPAGE_BPS, null, null, job.id, "probe USDC→target amountUsd=${"%.2f".format(sizeUsd)}")
            JupiterApi("").getQuote(USDC_MINT, mint, routeProbeUsdcRaw, SLIPPAGE_BPS)
        } catch (ce: CancellationException) {
            CryptoUniverseForensics.logPhase("CU_QUOTE_REJECTED", symbol, mint, mint, USDC_MINT, mint, resolution.route.name, SLIPPAGE_BPS, null, null, job.id, "cancelled: ${ce.message}")
            throw ce
        } catch (t: Throwable) {
            val paper = resolution.copy(
                route = CryptoExecutionRoute.PAPER_ONLY,
                diagCode = CryptoUniverseDiagCodes.ROUTE_DISCOVERY_FAILED,
                humanMessage = "No Jupiter USDC→target route: ${t.message ?: t.javaClass.simpleName}",
                executable = false,
            )
            CryptoUniverseForensics.logPhase("CU_QUOTE_REJECTED", symbol, mint, mint, USDC_MINT, mint, paper.route.name, SLIPPAGE_BPS, null, null, job.id, paper.humanMessage)
            CryptoUniverseForensics.logRouteOutcome(symbol, mint, paper.diagCode, paper.humanMessage, sizeSol)
            return@runAwaited Outcome.RouteDeferred(paper)
        }

        val outputCheck = MintIntegrityGate.validateQuoteOutput(symbol, mint, routeQuote.outputMint)
        if (outputCheck is MintIntegrityGate.Result.Reject || routeQuote.priceImpactPct > MAX_PRICE_IMPACT_PCT) {
            val why = if (outputCheck is MintIntegrityGate.Result.Reject) outputCheck.reason else "priceImpact ${routeQuote.priceImpactPct}% > $MAX_PRICE_IMPACT_PCT%"
            val paper = resolution.copy(
                route = CryptoExecutionRoute.PAPER_ONLY,
                diagCode = CryptoUniverseDiagCodes.ROUTE_DISCOVERY_FAILED,
                humanMessage = "Jupiter route rejected: $why",
                executable = false,
            )
            CryptoUniverseForensics.logPhase("CU_QUOTE_REJECTED", symbol, mint, mint, routeQuote.inputMint, routeQuote.outputMint, paper.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, null, job.id, paper.humanMessage)
            return@runAwaited Outcome.RouteDeferred(paper)
        }

        CryptoUniverseForensics.logPhase("CU_QUOTE_OK", symbol, mint, mint, routeQuote.inputMint, routeQuote.outputMint, resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, null, job.id, "out=${routeQuote.outAmount} router=${routeQuote.router}")
        CryptoUniverseForensics.logPhase("CU_ROUTE_ELIGIBLE_LIVE", symbol, mint, mint, routeQuote.inputMint, routeQuote.outputMint, resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, null, job.id, "route proven")

        if (direction != PerpsDirection.LONG) {
            val paper = resolution.copy(
                route = CryptoExecutionRoute.PAPER_ONLY,
                diagCode = CryptoUniverseDiagCodes.ROUTE_PAPER_ONLY,
                humanMessage = "Crypto Universe spot live executor only opens LONG target-token positions; SHORT remains paper/perps-adapter only.",
                executable = false,
            )
            CryptoUniverseForensics.logPhase("CU_ROUTE_FORCED_PAPER", symbol, mint, mint, USDC_MINT, mint, paper.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, null, job.id, paper.humanMessage)
            return@runAwaited Outcome.RouteDeferred(paper)
        }

        val before = readTokenUi(wallet, mint) ?: 0.0
        CryptoUniverseForensics.logPhase("CU_TX_BUILD_START", symbol, mint, mint, "CAPITAL_RAIL", mint, resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, null, job.id, "UniversalBridge prepareCapital sizeUsd=${"%.2f".format(sizeUsd)}")

        val bridge = try {
            UniversalBridgeEngine.prepareCapital(wallet, targetMint = mint, sizeUsd = sizeUsd)
        } catch (ce: CancellationException) {
            CryptoUniverseForensics.logPhase("CU_TX_BUILD_FAILED", symbol, mint, mint, "CAPITAL_RAIL", mint, resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, null, job.id, "cancelled: ${ce.message}")
            throw ce
        } catch (t: Throwable) {
            CryptoExecFailureTracker.recordFailure(symbol)
            CryptoUniverseForensics.logExecutionFailure(symbol, mint, CryptoUniverseDiagCodes.TX_BUILD_FAILED, t.message ?: "tx build threw", sizeSol)
            CryptoUniverseForensics.logPhase("CU_TX_BUILD_FAILED", symbol, mint, mint, "CAPITAL_RAIL", mint, resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, null, job.id, "${t.javaClass.simpleName}: ${t.message}")
            CryptoUniverseForensics.logPhase("CU_CONFIRM_FAILED", symbol, mint, mint, "CAPITAL_RAIL", mint, resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, null, job.id, "tx chain threw before confirmed signature")
            return@runAwaited Outcome.ExecFailed(resolution, t.message ?: "tx build threw")
        }

        val sig = bridge.swapTxSig?.trim().orEmpty()
        if (!bridge.success || sig.isBlank()) {
            CryptoExecFailureTracker.recordFailure(symbol)
            val reason = bridge.errorMsg.ifBlank { "bridge/Jupiter returned no signature" }
            CryptoUniverseForensics.logExecutionFailure(symbol, mint, CryptoUniverseDiagCodes.TX_BUILD_FAILED, reason, sizeSol)
            CryptoUniverseForensics.logPhase("CU_TX_BUILD_FAILED", symbol, mint, mint, bridge.sourceMint, bridge.targetMint, resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, sig.takeIf { it.isNotBlank() }, job.id, reason)
            CryptoUniverseForensics.logPhase("CU_CONFIRM_FAILED", symbol, mint, mint, bridge.sourceMint, bridge.targetMint, resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, sig.takeIf { it.isNotBlank() }, job.id, "no confirmed non-empty signature")
            return@runAwaited Outcome.ExecFailed(resolution, reason)
        }

        CryptoUniverseForensics.logPhase("CU_TX_BUILD_OK", symbol, mint, mint, bridge.sourceMint, bridge.targetMint, resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, sig, job.id, "tx built/sent by bridge")
        CryptoUniverseForensics.logPhase("CU_TX_SIGN_START", symbol, mint, mint, bridge.sourceMint, bridge.targetMint, resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, sig, job.id, "sign/send handled by wallet.signSendAndConfirm")
        CryptoUniverseForensics.logPhase("CU_TX_SEND_OK_SIGNATURE", symbol, mint, mint, bridge.sourceMint, bridge.targetMint, resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, sig, job.id, "signature non-empty")
        CryptoUniverseForensics.logPhase("CU_CONFIRM_OK", symbol, mint, mint, bridge.sourceMint, bridge.targetMint, resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, sig, job.id, "signSendAndConfirm returned")

        if (bridge.targetAmountRaw <= 0L || bridge.targetDecimals <= 0 ||
            !bridge.proofState.contains("CONFIRMED", ignoreCase = true)) {
            val reason = "Bridge returned signature without verified target quantity/proof: raw=${bridge.targetAmountRaw} decimals=${bridge.targetDecimals} proof=${bridge.proofState}"
            CryptoExecFailureTracker.recordFailure(symbol)
            CryptoUniverseForensics.logPhase("CU_CONFIRM_FAILED", symbol, mint, mint, bridge.sourceMint, bridge.targetMint,
                resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, sig, job.id, reason)
            return@runAwaited Outcome.ExecFailed(resolution, reason)
        }
        val filledRaw = java.math.BigInteger.valueOf(bridge.targetAmountRaw)
        val mutation = com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPosition(
            idempotencyKey = "CRYPTO_UNIVERSE6486:OPEN:$positionId:$sig", positionId = positionId,
            mint = mint, symbol = symbol, lane = traderType.uppercase(), runId = sig,
            entryCostSol = sizeSol, openedQtyRaw = filledRaw, tokenDecimals = bridge.targetDecimals,
            feesSol = 0.0, paperMode = false,
        )
        if (mutation != com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.MutateResult.APPLIED &&
            mutation != com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.MutateResult.DUPLICATE) {
            return@runAwaited Outcome.ExecFailed(resolution, "Canonical live open rejected: $mutation")
        }
        try { TokenLifecycleTracker.onTokenLanded(mint, bridge.targetAmountUi) } catch (_: Throwable) {}
        try { HostWalletTokenTracker.recordBuyPending(mint, symbol, sig) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.sell.LiveWalletReconciler.recordBuySignature(mint, sig) } catch (_: Throwable) {}
        CryptoExecFailureTracker.recordSuccess(symbol)
        Outcome.Executed(sig, mint, filledRaw, bridge.targetDecimals, bridge.proofState)
    }

    private fun readTokenUi(wallet: SolanaWallet, mint: String): Double? = try {
        wallet.getTokenAccountsWithDecimalsBounded()[mint]?.first ?: 0.0
    } catch (_: Throwable) { null }

    private suspend fun verifyWalletDelta(
        wallet: SolanaWallet,
        mint: String,
        before: Double,
        symbol: String,
        jobId: String,
        sig: String,
        impact: Double?,
    ): Boolean {
        // V5.9.665 — extended from 5×3s = 15s to 8×3s = 24s to give Jupiter
        // ATA settlement more breathing room before the reconciler-async
        // fallback path kicks in.
        repeat(8) { idx ->
            kotlinx.coroutines.delay(3_000L)
            val now = readTokenUi(wallet, mint)
            if (now != null && now > before) {
                CryptoUniverseForensics.logPhase("CU_WALLET_DELTA_OK", symbol, mint, mint, "CAPITAL_RAIL", mint, CryptoExecutionRoute.JUPITER_ROUTABLE.name, SLIPPAGE_BPS, impact, sig, jobId, "before=$before now=$now poll=${idx + 1}/8")
                return true
            }
        }
        val now = readTokenUi(wallet, mint)
        CryptoUniverseForensics.logPhase("CU_WALLET_DELTA_FAILED", symbol, mint, mint, "CAPITAL_RAIL", mint, CryptoExecutionRoute.JUPITER_ROUTABLE.name, SLIPPAGE_BPS, impact, sig, jobId, "before=$before now=${now ?: -1.0} (24s polled)")
        return false
    }
}
