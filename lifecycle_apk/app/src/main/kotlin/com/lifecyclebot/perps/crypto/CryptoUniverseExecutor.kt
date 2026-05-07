package com.lifecyclebot.perps.crypto

import com.lifecyclebot.engine.WalletManager
import com.lifecyclebot.perps.MarketsLiveExecutor
import com.lifecyclebot.perps.PerpsDirection
import com.lifecyclebot.perps.PerpsMarket

/**
 * V5.9.495z30 — CryptoUniverseExecutor.
 *
 * Single entry point for live execution of CryptoUniverseTrader
 * signals. It does NOT replace MarketsLiveExecutor — it wraps it
 * with a route-resolver gate so that:
 *
 *   • Non-executable assets (BTC w/ no CEX, XMR w/ no route, …) are
 *     classified up-front, logged with a precise diag code, and
 *     skipped WITHOUT being counted as a buy failure.
 *   • Executable assets (Jupiter-routable wrapped SPL mints) flow
 *     through the existing MarketsLiveExecutor.executeLiveTrade()
 *     unchanged.
 *
 * MemeTrader is NOT touched by this code — meme tokens remain on the
 * pump.fun / PumpPortal / Solana SPL meme path managed by the
 * existing core Executor.
 */
object CryptoUniverseExecutor {

    /** Decision returned to the caller. The caller (CryptoAltTrader)
     *  uses this to decide whether to flip its LiveAttemptStats
     *  outcome to FAILED or to a new ROUTE_DEFERRED bucket. */
    sealed class Outcome {
        data class Executed(val txSig: String?) : Outcome()
        /** Route discovered to be non-executable. Not a failure. */
        data class RouteDeferred(val resolution: CryptoUniverseRouteResolver.Resolution) : Outcome()
        /** Real execution attempted and failed. */
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
    ): Outcome {
        val walletSol = try {
            WalletManager.getWallet()?.getSolBalance() ?: 0.0
        } catch (_: Exception) { 0.0 }

        val resolution = CryptoUniverseRouteResolver.resolve(
            market = market,
            walletSolBalance = walletSol,
            sizeSol = sizeSol,
        )

        if (!resolution.executable) {
            CryptoUniverseForensics.logRouteOutcome(
                symbol = resolution.symbol,
                mintOrPlaceholder = resolution.mint ?: "no-mint",
                diagCode = resolution.diagCode,
                humanMessage = resolution.humanMessage,
                sizeSol = sizeSol,
            )
            return Outcome.RouteDeferred(resolution)
        }

        // Executable route — delegate to the existing live executor.
        // The MarketsLiveExecutor already handles Jupiter SPOT,
        // UniversalBridge two-hop, and Flash perps for crypto markets.
        return try {
            val (success, txSig) = MarketsLiveExecutor.executeLiveTrade(
                market     = market,
                direction  = direction,
                sizeSol    = sizeSol,
                leverage   = leverage,
                priceUsd   = priceUsd,
                traderType = traderType,
            )
            if (success) Outcome.Executed(txSig)
            else Outcome.ExecFailed(resolution, "Jupiter/Bridge swap returned no signature.")
        } catch (e: Throwable) {
            CryptoUniverseForensics.logExecutionFailure(
                symbol = resolution.symbol,
                mint = resolution.mint ?: "no-mint",
                diagCode = CryptoUniverseDiagCodes.TX_BUILD_FAILED,
                humanMessage = e.message ?: "tx build/broadcast threw",
                sizeSol = sizeSol,
            )
            Outcome.ExecFailed(resolution, e.message ?: "unknown")
        }
    }
}
