package com.lifecyclebot.perps.crypto

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.perps.PerpsMarket

/**
 * V5.9.495z30 — CryptoUniverseRouteResolver.
 *
 * Decides exactly ONE route for a crypto signal before any tx is
 * built. Honest about what we can and cannot execute today:
 *
 *   1. Solana wrapped/SPL representation present + Jupiter routable
 *      → JUPITER_ROUTABLE / SOLANA_SPL_DIRECT
 *   2. Native-only chain (BTC, XMR, TON, TRX) → CEX_REQUIRED /
 *      BRIDGE_REQUIRED depending on adapter availability
 *   3. Non-native asset with no SPL representation → BRIDGE_REQUIRED
 *      → falls to CEX_REQUIRED if bridge not configured
 *      → falls to PAPER_ONLY if cryptoUniversePaperOnlyWhenNoExecutor
 *      → otherwise NO_ROUTE_AVAILABLE
 *   4. Insufficient SOL on the wallet → INSUFFICIENT_SOL
 *
 * Route-discovery outcomes are NEVER counted as buy failures. They
 * are emitted as WARNING forensics with an exact diagnostic code.
 */
object CryptoUniverseRouteResolver {

    private const val TAG = "CryptoUniverseRouteResolver"

    data class Resolution(
        val symbol: String,
        val route: CryptoExecutionRoute,
        val mint: String?,           // null when no SPL representation
        val diagCode: String,
        val humanMessage: String,
        /** Whether the trader should attempt live execution. */
        val executable: Boolean,
        /** Whether to log this as a route-discovery warning vs an
         *  execution failure. Route-discovery never counts as a buy
         *  failure (operator spec G). */
        val countsAsBuyFailure: Boolean = false,
    )

    /**
     * Resolve a crypto signal to one route.
     *
     * @param market           the perps market enum entry (BTC / XMR / WBTC / …)
     * @param walletSolBalance current trading-wallet SOL balance
     * @param sizeSol          intended trade size in SOL
     */
    fun resolve(
        market: PerpsMarket,
        walletSolBalance: Double,
        sizeSol: Double,
    ): Resolution {
        val cfg = CryptoUniverseConfigStore.get()
        val sym = market.symbol.uppercase()

        // Solana meme tokens belong to MemeTrader. The PerpsMarket
        // enum doesn't include pump.fun memes, so this is mostly a
        // safety net for any future overlap.
        if (sym == "SOL") {
            return Resolution(sym, CryptoExecutionRoute.SOLANA_SPL_DIRECT, null,
                CryptoUniverseDiagCodes.ROUTE_SOLANA_SPL,
                "SOL is the base asset — handled by core executor.",
                executable = true)
        }

        // Insufficient SOL — surface immediately (operator spec).
        // 0.01 SOL floor matches CryptoAltTrader.executeLiveTrade().
        if (walletSolBalance < 0.01 || sizeSol < 0.01) {
            return Resolution(sym, CryptoExecutionRoute.INSUFFICIENT_SOL, null,
                CryptoUniverseDiagCodes.ROUTE_INSUFFICIENT_SOL,
                "wallet=${"%.4f".format(walletSolBalance)} SOL, size=${"%.4f".format(sizeSol)} SOL — below 0.01 floor.",
                executable = false)
        }

        // 1. Resolve ANY real Solana SPL mint. This is deliberately NOT a
        // curated "wrapped asset only" gate. Crypto Universe is funded via the
        // app's SOL/USDC bridge rail and can trade any crypto token that has a
        // real Solana mint and a Jupiter route.
        val resolvedMint = CryptoWrappedAssetMapper.resolveWrappedMint(sym)
        if (resolvedMint != null) {
            if (CryptoExecFailureTracker.isCooledDown(sym)) {
                val secs = CryptoExecFailureTracker.cooldownRemainingMs(sym) / 1000
                return Resolution(sym, CryptoExecutionRoute.PAPER_ONLY, resolvedMint,
                    CryptoUniverseDiagCodes.ROUTE_NO_EXECUTOR,
                    "Jupiter has returned no signature repeatedly for $sym; cooling down ${secs}s. Paper-only until liquidity returns.",
                    executable = false)
            }
            return Resolution(sym, CryptoExecutionRoute.JUPITER_ROUTABLE, resolvedMint,
                CryptoUniverseDiagCodes.ROUTE_JUPITER,
                "Resolved real Solana mint ${resolvedMint.take(8)}…; live eligibility will be proven by USDC/Jupiter quote.",
                executable = cfg.cryptoUniverseLiveEnabled)
        }

        // 2. No real SPL mint means the bridge rail has no target. This is a
        // route-discovery outcome, not a tx failure. Do not call live executor.
        return when {
            cfg.cryptoUniverseAllowBridgeAdapters && CryptoBridgeAdapter.isConfigured() ->
                Resolution(sym, CryptoExecutionRoute.BRIDGE_REQUIRED, null,
                    CryptoUniverseDiagCodes.ROUTE_BRIDGE_REQUIRED,
                    "No Solana mint resolved; external bridge adapter required/configured.",
                    executable = true)
            cfg.cryptoUniverseAllowCexAdapters && CryptoCexAdapter.isConfigured() ->
                Resolution(sym, CryptoExecutionRoute.CEX_REQUIRED, null,
                    CryptoUniverseDiagCodes.ROUTE_CEX_REQUIRED,
                    "No Solana mint resolved; CEX adapter route configured.",
                    executable = true)
            else ->
                Resolution(sym, CryptoExecutionRoute.PAPER_ONLY, null,
                    CryptoUniverseDiagCodes.ROUTE_NO_WRAPPED_ASSET,
                    "No verified Solana SPL mint/Jupiter target resolved — paper-only until registry discovers one.",
                    executable = false)
        }.also {
            ErrorLogger.debug(TAG, "Resolved ${sym} → ${it.route} (${it.diagCode})")
        }
    }
}
