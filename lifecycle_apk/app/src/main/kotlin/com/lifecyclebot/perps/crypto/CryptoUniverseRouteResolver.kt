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

        // 1. Try wrapped/SPL representation (curated → dynamic registry).
        val wrappedMint = if (cfg.cryptoUniverseAllowWrappedAssets) {
            CryptoWrappedAssetMapper.resolveWrappedMint(sym)
        } else null

        if (wrappedMint != null) {
            // V5.9.495z36 — sticky-failure cool-down. If Jupiter has
            // failed THRESHOLD times in the rolling window for this
            // symbol, paper-only it for COOLDOWN_MS rather than
            // hammering tx-build again.
            if (CryptoExecFailureTracker.isCooledDown(sym)) {
                val secs = CryptoExecFailureTracker.cooldownRemainingMs(sym) / 1000
                return Resolution(sym, CryptoExecutionRoute.PAPER_ONLY, wrappedMint,
                    CryptoUniverseDiagCodes.ROUTE_NO_EXECUTOR,
                    "Jupiter has returned no signature ${"%d"}+ times for $sym; cooling down ${secs}s. Paper-only until liquidity returns.",
                    executable = false)
            }
            return Resolution(sym, CryptoExecutionRoute.JUPITER_ROUTABLE, wrappedMint,
                CryptoUniverseDiagCodes.ROUTE_JUPITER,
                "Jupiter route via SPL mint ${wrappedMint.take(8)}…",
                executable = cfg.cryptoUniverseLiveEnabled)
        }

        // 2. No SPL route available — classify by native-only flag.
        val isNative = CryptoWrappedAssetMapper.isNativeOnly(sym)
        if (isNative) {
            return when {
                cfg.cryptoUniverseAllowCexAdapters && CryptoCexAdapter.isConfigured() ->
                    Resolution(sym, CryptoExecutionRoute.CEX_REQUIRED, null,
                        CryptoUniverseDiagCodes.ROUTE_CEX_REQUIRED,
                        "Native-only asset routed via CEX adapter.",
                        executable = true)
                cfg.cryptoUniverseAllowBridgeAdapters && CryptoBridgeAdapter.isConfigured() ->
                    Resolution(sym, CryptoExecutionRoute.BRIDGE_REQUIRED, null,
                        CryptoUniverseDiagCodes.ROUTE_BRIDGE_REQUIRED,
                        "Native-only asset routed via bridge adapter.",
                        executable = true)
                cfg.cryptoUniversePaperOnlyWhenNoExecutor ->
                    Resolution(sym, CryptoExecutionRoute.PAPER_ONLY, null,
                        CryptoUniverseDiagCodes.ROUTE_PAPER_ONLY,
                        "Native-only asset — no executor configured. Paper-only learning.",
                        executable = false)
                else ->
                    Resolution(sym, CryptoExecutionRoute.CEX_REQUIRED, null,
                        CryptoUniverseDiagCodes.ROUTE_CEX_REQUIRED,
                        "Native-only asset (e.g. BTC/XMR/TON) — CEX or bridge adapter required.",
                        executable = false)
            }
        }

        // 3. Non-native, no wrapped SPL found. Could still be reachable
        //    via bridge (PAXG / AXS / RENDER on non-Solana chains).
        return when {
            cfg.cryptoUniverseAllowBridgeAdapters && CryptoBridgeAdapter.isConfigured() ->
                Resolution(sym, CryptoExecutionRoute.BRIDGE_REQUIRED, null,
                    CryptoUniverseDiagCodes.ROUTE_BRIDGE_REQUIRED,
                    "Non-native asset — routed via bridge adapter.",
                    executable = true)
            cfg.cryptoUniverseAllowCexAdapters && CryptoCexAdapter.isConfigured() ->
                Resolution(sym, CryptoExecutionRoute.CEX_REQUIRED, null,
                    CryptoUniverseDiagCodes.ROUTE_CEX_REQUIRED,
                    "Non-native asset — CEX adapter route.",
                    executable = true)
            cfg.cryptoUniversePaperOnlyWhenNoExecutor ->
                Resolution(sym, CryptoExecutionRoute.PAPER_ONLY, null,
                    CryptoUniverseDiagCodes.ROUTE_NO_WRAPPED_ASSET,
                    "No Solana wrapped representation and no bridge/CEX adapter — paper-only.",
                    executable = false)
            else ->
                Resolution(sym, CryptoExecutionRoute.NO_ROUTE_AVAILABLE, null,
                    CryptoUniverseDiagCodes.ROUTE_NO_EXECUTOR,
                    "No executor configured for this asset.",
                    executable = false)
        }.also {
            ErrorLogger.debug(TAG, "Resolved ${sym} → ${it.route} (${it.diagCode})")
        }
    }
}
