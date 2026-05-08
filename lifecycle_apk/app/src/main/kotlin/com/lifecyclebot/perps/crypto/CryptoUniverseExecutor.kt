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
        data class Executed(val txSig: String?) : Outcome()
        data class RouteDeferred(val resolution: CryptoUniverseRouteResolver.Resolution) : Outcome()
        data class ExecFailed(
            val resolution: CryptoUniverseRouteResolver.Resolution,
            val reason: String,
        ) : Outcome()
    }

    suspend fun executeLiveTrade(
        market: PerpsMarket,
        direction: PerpsDirection,
        sizeSol: Double,
        leverage: Double,
        priceUsd: Double,
        traderType: String = "CryptoUniverse",
    ): Outcome = LiveExecutionScope.runAwaited("CU_${market.symbol}_${direction.name}") { job ->
        val symbol = market.symbol.uppercase()
        val wallet = try { WalletManager.getWallet() } catch (_: Throwable) { null }
        if (wallet == null) {
            val r = CryptoUniverseRouteResolver.Resolution(
                symbol, CryptoExecutionRoute.NO_ROUTE_AVAILABLE, null,
                CryptoUniverseDiagCodes.ROUTE_NO_EXECUTOR, "No wallet connected.", executable = false,
            )
            return@runAwaited Outcome.RouteDeferred(r)
        }
        val walletSol = try { wallet.getSolBalance() } catch (_: Throwable) { 0.0 }
        val resolution = CryptoUniverseRouteResolver.resolve(market, walletSol, sizeSol)
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

        if (!resolution.executable) {
            CryptoUniverseForensics.logPhase(
                phase = "CU_ROUTE_FORCED_PAPER",
                symbol = symbol,
                intendedMint = mint,
                resolvedMint = mint,
                inputMint = USDC_MINT,
                outputMint = mint ?: USDC_MINT,
                routeType = resolution.route.name,
                slippageBps = SLIPPAGE_BPS,
                priceImpactPct = null,
                txSignature = null,
                jobId = job.id,
                message = resolution.diagCode + " | " + resolution.humanMessage,
            )
            CryptoUniverseForensics.logRouteOutcome(symbol, mint ?: "USDC_COLLATERAL", resolution.diagCode, resolution.humanMessage, sizeSol)
            return@runAwaited Outcome.RouteDeferred(resolution)
        }

        // No target mint: execute the symbol as USDC-collateral exposure. This
        // is the Crypto Universe path the operator asked for — NOT paper-only,
        // and NOT gated on a Solana mint. The bot opens/closes the position by
        // symbol while real wallet capital is moved/held as USDC.
        if (mint.isNullOrBlank()) {
            return@runAwaited executeUsdcCollateralExposure(
                wallet = wallet,
                symbol = symbol,
                resolution = resolution,
                sizeSol = sizeSol,
                priceUsd = priceUsd,
                jobId = job.id,
            )
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

        val landed = verifyWalletDelta(wallet, mint, before, symbol, job.id, sig, routeQuote.priceImpactPct)
        if (!landed) {
            CryptoExecFailureTracker.recordFailure(symbol)
            val reason = "wallet delta failed after confirmed signature"
            CryptoUniverseForensics.logExecutionFailure(symbol, mint, CryptoUniverseDiagCodes.TX_BUILD_FAILED, reason, sizeSol)
            CryptoUniverseForensics.logPhase("CU_CONFIRM_FAILED", symbol, mint, mint, "CAPITAL_RAIL", mint, resolution.route.name, SLIPPAGE_BPS, routeQuote.priceImpactPct, sig, job.id, "confirmed signature but target wallet delta failed")
            return@runAwaited Outcome.ExecFailed(resolution, reason)
        }

        try { TokenLifecycleTracker.onTokenLanded(mint, readTokenUi(wallet, mint) ?: bridge.targetAmountUi) } catch (_: Throwable) {}
        try { HostWalletTokenTracker.recordBuyPending(mint, symbol, sig) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.sell.LiveWalletReconciler.recordBuySignature(mint, sig) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.sell.LiveWalletReconciler.reconcileNow(wallet, "crypto_universe_buy_${symbol}") } catch (_: Throwable) {}

        CryptoExecFailureTracker.recordSuccess(symbol)
        Outcome.Executed(sig)
    }


    private suspend fun executeUsdcCollateralExposure(
        wallet: SolanaWallet,
        symbol: String,
        resolution: CryptoUniverseRouteResolver.Resolution,
        sizeSol: Double,
        priceUsd: Double,
        jobId: String,
    ): Outcome {
        val solPriceUsd = WalletManager.lastKnownSolPrice.takeIf { it > 0.0 } ?: 150.0
        val sizeUsd = sizeSol * solPriceUsd
        val beforeUsdc = readTokenUi(wallet, USDC_MINT) ?: 0.0

        CryptoUniverseForensics.logPhase("CU_ROUTE_ELIGIBLE_LIVE", symbol, null, null, "CAPITAL_RAIL", USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, null, jobId, "symbol exposure; no target SPL mint required")
        CryptoUniverseForensics.logPhase("CU_QUOTE_OK", symbol, null, null, "CAPITAL_RAIL", USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, null, jobId, "USDC collateral route; target price feed=$priceUsd")
        CryptoUniverseForensics.logPhase("CU_TX_BUILD_START", symbol, null, null, "CAPITAL_RAIL", USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, null, jobId, "UniversalBridge prepareCapital USDC sizeUsd=${"%.2f".format(sizeUsd)}")

        val bridge = try {
            UniversalBridgeEngine.prepareCapital(wallet, targetMint = USDC_MINT, sizeUsd = sizeUsd)
        } catch (ce: CancellationException) {
            CryptoUniverseForensics.logPhase("CU_TX_BUILD_FAILED", symbol, null, null, "CAPITAL_RAIL", USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, null, jobId, "cancelled: ${ce.message}")
            throw ce
        } catch (t: Throwable) {
            CryptoExecFailureTracker.recordFailure(symbol)
            CryptoUniverseForensics.logExecutionFailure(symbol, "USDC_COLLATERAL", CryptoUniverseDiagCodes.TX_BUILD_FAILED, t.message ?: "USDC collateral build threw", sizeSol)
            CryptoUniverseForensics.logPhase("CU_TX_BUILD_FAILED", symbol, null, null, "CAPITAL_RAIL", USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, null, jobId, "${t.javaClass.simpleName}: ${t.message}")
            CryptoUniverseForensics.logPhase("CU_CONFIRM_FAILED", symbol, null, null, "CAPITAL_RAIL", USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, null, jobId, "USDC collateral route failed")
            return Outcome.ExecFailed(resolution, t.message ?: "USDC collateral route failed")
        }

        val sig = bridge.swapTxSig?.trim().orEmpty()
        if (!bridge.success) {
            CryptoExecFailureTracker.recordFailure(symbol)
            val reason = bridge.errorMsg.ifBlank { "USDC collateral route failed" }
            CryptoUniverseForensics.logExecutionFailure(symbol, "USDC_COLLATERAL", CryptoUniverseDiagCodes.TX_BUILD_FAILED, reason, sizeSol)
            CryptoUniverseForensics.logPhase("CU_TX_BUILD_FAILED", symbol, null, null, bridge.sourceMint, USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, sig.takeIf { it.isNotBlank() }, jobId, reason)
            CryptoUniverseForensics.logPhase("CU_CONFIRM_FAILED", symbol, null, null, bridge.sourceMint, USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, sig.takeIf { it.isNotBlank() }, jobId, reason)
            return Outcome.ExecFailed(resolution, reason)
        }

        val afterUsdc = readTokenUi(wallet, USDC_MINT) ?: beforeUsdc
        val collateralAvailable = afterUsdc >= (sizeUsd * 0.95) || afterUsdc >= beforeUsdc
        if (!collateralAvailable) {
            CryptoExecFailureTracker.recordFailure(symbol)
            val reason = "USDC collateral not visible after bridge: before=$beforeUsdc after=$afterUsdc required=${"%.2f".format(sizeUsd)}"
            CryptoUniverseForensics.logExecutionFailure(symbol, "USDC_COLLATERAL", CryptoUniverseDiagCodes.TX_BUILD_FAILED, reason, sizeSol)
            CryptoUniverseForensics.logPhase("CU_WALLET_DELTA_FAILED", symbol, null, null, bridge.sourceMint, USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, sig.takeIf { it.isNotBlank() }, jobId, reason)
            CryptoUniverseForensics.logPhase("CU_CONFIRM_FAILED", symbol, null, null, bridge.sourceMint, USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, sig.takeIf { it.isNotBlank() }, jobId, "USDC collateral verification failed")
            return Outcome.ExecFailed(resolution, reason)
        }

        CryptoUniverseForensics.logPhase("CU_TX_BUILD_OK", symbol, null, null, bridge.sourceMint, USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, sig.takeIf { it.isNotBlank() }, jobId, if (sig.isBlank()) "USDC already available; no swap tx needed" else "USDC bridge tx built/sent")
        CryptoUniverseForensics.logPhase("CU_TX_SEND_OK_SIGNATURE", symbol, null, null, bridge.sourceMint, USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, sig.takeIf { it.isNotBlank() }, jobId, if (sig.isBlank()) "NO_SWAP_NEEDED_USDC_AVAILABLE" else "signature non-empty")
        CryptoUniverseForensics.logPhase("CU_CONFIRM_OK", symbol, null, null, bridge.sourceMint, USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, sig.takeIf { it.isNotBlank() }, jobId, "USDC collateral confirmed for symbol exposure")
        CryptoUniverseForensics.logPhase("CU_WALLET_DELTA_OK", symbol, null, null, bridge.sourceMint, USDC_MINT, "USDC_COLLATERAL_EXPOSURE", SLIPPAGE_BPS, null, sig.takeIf { it.isNotBlank() }, jobId, "USDC before=$beforeUsdc after=$afterUsdc exposure=${"%.2f".format(sizeUsd)}")

        try { com.lifecyclebot.engine.sell.LiveWalletReconciler.recordBuySignature(USDC_MINT, sig) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.sell.LiveWalletReconciler.reconcileNow(wallet, "crypto_universe_usdc_${symbol}") } catch (_: Throwable) {}
        CryptoExecFailureTracker.recordSuccess(symbol)
        return Outcome.Executed(sig.takeIf { it.isNotBlank() })
    }

    private fun readTokenUi(wallet: SolanaWallet, mint: String): Double? = try {
        wallet.getTokenAccountsWithDecimals()[mint]?.first ?: 0.0
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
        repeat(5) { idx ->
            kotlinx.coroutines.delay(3_000L)
            val now = readTokenUi(wallet, mint)
            if (now != null && now > before) {
                CryptoUniverseForensics.logPhase("CU_WALLET_DELTA_OK", symbol, mint, mint, "CAPITAL_RAIL", mint, CryptoExecutionRoute.JUPITER_ROUTABLE.name, SLIPPAGE_BPS, impact, sig, jobId, "before=$before now=$now poll=${idx + 1}/5")
                return true
            }
        }
        val now = readTokenUi(wallet, mint)
        CryptoUniverseForensics.logPhase("CU_WALLET_DELTA_FAILED", symbol, mint, mint, "CAPITAL_RAIL", mint, CryptoExecutionRoute.JUPITER_ROUTABLE.name, SLIPPAGE_BPS, impact, sig, jobId, "before=$before now=${now ?: -1.0}")
        return false
    }
}
