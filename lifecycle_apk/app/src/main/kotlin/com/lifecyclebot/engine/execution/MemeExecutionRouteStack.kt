package com.lifecyclebot.engine.execution

import com.lifecyclebot.engine.ForensicLogger

/**
 * V5.0.3713 — Canonical MEME execution route stack.
 *
 * This is the shared live BUY/SELL route contract. Data providers live in
 * RouteIntelligenceSnapshot only; executable providers build transactions or
 * executable swap requests; senders land already-built transactions.
 *
 * This object is intentionally side-effect-light at introduction: it creates a
 * single stack ledger and forensic contract before moving the legacy
 * PumpPortal/Jupiter call sites behind provider adapters. That prevents another
 * broad route rewrite from breaking live trading while still making every live
 * path prove whether it used the full stack or collapsed to the old 3-route
 * ladder.
 */
object MemeExecutionRouteStack {

    enum class Side { BUY, SELL }
    enum class Urgency { NORMAL, TOP_UP, PARTIAL, PROFIT_LOCK, STOP_LOSS, PANIC, ORPHAN, RECONCILER, MANUAL }

    enum class FailureClass {
        NONE,
        UNSUPPORTED,
        BALANCE_AUTHORITY_MISMATCH,
        RPC_EMPTY_MAP_NOT_FATAL_ROTATE,
        RFQ_REJECTED,
        HTTP_400,
        HTTP_429,
        HTTP_500,
        HTTP_503,
        TIMEOUT,
        SIMULATION_FAILED,
        ROUTE_INVALID,
        POOL_CHANGED,
        BLOCKHASH_EXPIRED,
        SEND_FAILED,
        CONFIRMATION_INCONCLUSIVE,
        UNKNOWN,
    }

    data class ExecutionRouteContext(
        val side: Side,
        val mint: String,
        val symbol: String,
        val amountIn: Double = 0.0,
        val amountInRaw: String = "",
        val slippageBps: Int = 0,
        val reason: String = "",
        val urgency: Urgency = Urgency.NORMAL,
        val walletSol: Double = 0.0,
        val tokenBalanceAuthority: String = "UNKNOWN",
        val routeIntelligence: RouteIntelligenceSnapshot = RouteIntelligenceSnapshot(),
        val safetyTier: String = "UNKNOWN",
        val sourceTags: String = "",
        val callSite: String = "UNKNOWN",
    )

    data class RouteIntelligenceSnapshot(
        val birdeyePrice: Double? = null,
        val coingeckoContext: String = "",
        val dexScreenerPair: String = "",
        val pumpPortalSignal: String = "",
        val pumpFunBondingSignal: Boolean = false,
        val pumpSwapPoolFound: Boolean = false,
        val raydiumPoolFound: Boolean = false,
        val meteoraPoolFound: Boolean = false,
        val orcaPoolFound: Boolean = false,
        val liquidityDepthUsd: Double = 0.0,
        val priceConfidence: Int = 0,
        val exitDepthUsd: Double = 0.0,
        val recommendedVenues: List<String> = emptyList(),
        val blockedVenues: List<String> = emptyList(),
        val unsupportedVenues: List<String> = emptyList(),
    ) {
        fun providersUsedForIntel(): List<String> = listOfNotNull(
            "Birdeye".takeIf { birdeyePrice != null },
            "CoinGecko".takeIf { coingeckoContext.isNotBlank() },
            "DexScreener".takeIf { dexScreenerPair.isNotBlank() },
            "PumpPortalWS".takeIf { pumpPortalSignal.isNotBlank() },
            "GlobalTradeRegistry".takeIf { recommendedVenues.isNotEmpty() || blockedVenues.isNotEmpty() || unsupportedVenues.isNotEmpty() },
        )
    }

    data class Support(val supported: Boolean, val reason: String = "")

    data class ExecutableRouteRequest(
        val txBase64: String,
        val providerName: String,
        val requestId: String? = null,
        val isRfqRoute: Boolean = false,
        val senderCompatible: Boolean = false,
        val requiresRebuildOnBlockhashExpiry: Boolean = true,
    )

    sealed class BuildResult {
        data class Built(val request: ExecutableRouteRequest) : BuildResult()
        data class Failed(val failureClass: FailureClass, val reason: String) : BuildResult()
        data class Unsupported(val reason: String) : BuildResult()
    }

    interface ExecutionProvider {
        val providerName: String
        fun supports(context: ExecutionRouteContext): Support
        fun quoteOrBuild(context: ExecutionRouteContext): BuildResult
        fun failureClass(error: Throwable? = null, result: BuildResult? = null): FailureClass
        fun markMintUnsupported(mint: String, reason: String)
    }

    abstract class DeclaredProvider(final override val providerName: String) : ExecutionProvider {
        private val unsupportedByMint = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun markMintUnsupported(mint: String, reason: String) {
            if (mint.isNotBlank()) unsupportedByMint[mint] = reason
        }
        protected fun mintUnsupported(context: ExecutionRouteContext): Support? = unsupportedByMint[context.mint]?.let { Support(false, it) }
        override fun quoteOrBuild(context: ExecutionRouteContext): BuildResult = BuildResult.Unsupported("provider_adapter_not_yet_wired")
        override fun failureClass(error: Throwable?, result: BuildResult?): FailureClass = when (result) {
            is BuildResult.Unsupported -> FailureClass.UNSUPPORTED
            is BuildResult.Failed -> result.failureClass
            else -> FailureClass.UNKNOWN
        }
    }

    object PumpFunDirectProvider : DeclaredProvider("PumpFunDirect") {
        override fun supports(context: ExecutionRouteContext): Support = mintUnsupported(context)
            ?: Support(context.routeIntelligence.pumpFunBondingSignal || context.mint.endsWith("pump", true), "requires pump.fun bonding signal")
    }

    object PumpPortalProvider : DeclaredProvider("PumpPortal") {
        override fun supports(context: ExecutionRouteContext): Support = mintUnsupported(context) ?: Support(true, "pool=auto fallback available")
    }

    object PumpSwapDirectProvider : DeclaredProvider("PumpSwapDirect") {
        override fun supports(context: ExecutionRouteContext): Support = mintUnsupported(context)
            ?: Support(context.routeIntelligence.pumpSwapPoolFound, "requires PumpSwap/graduated pool signal")
    }

    object RaydiumDirectProvider : DeclaredProvider("RaydiumDirect") {
        override fun supports(context: ExecutionRouteContext): Support = mintUnsupported(context)
            ?: Support(context.routeIntelligence.raydiumPoolFound, "requires Raydium pool/source signal")
    }

    object MeteoraDirectProvider : DeclaredProvider("MeteoraDirect") {
        override fun supports(context: ExecutionRouteContext): Support = mintUnsupported(context)
            ?: Support(context.routeIntelligence.meteoraPoolFound, "requires Meteora DLMM pool/source signal")
    }

    object OrcaDirectProvider : DeclaredProvider("OrcaDirect") {
        override fun supports(context: ExecutionRouteContext): Support = mintUnsupported(context)
            ?: Support(context.routeIntelligence.orcaPoolFound, "requires Orca/Whirlpool pool/source signal")
    }

    object JupiterUltraProvider : DeclaredProvider("JupiterUltra") {
        override fun supports(context: ExecutionRouteContext): Support = mintUnsupported(context) ?: Support(true, "aggregator fallback")
    }

    object JupiterMetisProvider : DeclaredProvider("JupiterMetis") {
        override fun supports(context: ExecutionRouteContext): Support = mintUnsupported(context) ?: Support(true, "Metis/v6 fallback")
    }

    interface SenderProvider {
        val senderName: String
        fun supports(transaction: ExecutableRouteRequest): Support
        fun send(transaction: ExecutableRouteRequest): SenderResult
        fun confirm(signature: String): SenderResult
        fun failureClass(error: Throwable? = null, result: SenderResult? = null): FailureClass
    }

    sealed class SenderResult {
        data class Sent(val signature: String) : SenderResult()
        data class Failed(val failureClass: FailureClass, val reason: String) : SenderResult()
        data class Unsupported(val reason: String) : SenderResult()
    }

    abstract class DeclaredSender(final override val senderName: String) : SenderProvider {
        override fun send(transaction: ExecutableRouteRequest): SenderResult = SenderResult.Unsupported("sender_adapter_not_yet_wired")
        override fun confirm(signature: String): SenderResult = SenderResult.Unsupported("confirmation_adapter_not_yet_wired")
        override fun failureClass(error: Throwable?, result: SenderResult?): FailureClass = when (result) {
            is SenderResult.Unsupported -> FailureClass.UNSUPPORTED
            is SenderResult.Failed -> result.failureClass
            else -> FailureClass.UNKNOWN
        }
    }

    object StandardRpcSender : DeclaredSender("standardRpc") {
        override fun supports(transaction: ExecutableRouteRequest): Support = Support(true, "baseline RPC sender")
    }

    object HeliusSenderProvider : DeclaredSender("HeliusSender") {
        override fun supports(transaction: ExecutableRouteRequest): Support =
            Support(transaction.senderCompatible, "requires prebuilt Jito-tip-compatible tx")
    }

    object JitoSenderProvider : DeclaredSender("Jito") {
        override fun supports(transaction: ExecutableRouteRequest): Support = Support(true, "tipped/bundle fallback")
    }

    private val executionProviders: List<ExecutionProvider> = listOf(
        PumpFunDirectProvider,
        PumpPortalProvider,
        PumpSwapDirectProvider,
        RaydiumDirectProvider,
        MeteoraDirectProvider,
        OrcaDirectProvider,
        JupiterUltraProvider,
        JupiterMetisProvider,
    )

    private val senderProviders: List<SenderProvider> = listOf(StandardRpcSender, HeliusSenderProvider, JitoSenderProvider)

    fun providerOrder(context: ExecutionRouteContext): List<ExecutionProvider> = executionProviders
    fun senderOrder(transaction: ExecutableRouteRequest? = null): List<SenderProvider> = senderProviders

    data class StackCoverage(
        val providerNames: List<String>,
        val supportedProviderNames: List<String>,
        val unsupportedProviderNames: List<String>,
        val senderNames: List<String>,
    ) {
        fun oldThreeRouteCollapse(): Boolean {
            val covered = providerNames.toSet()
            return !covered.containsAll(setOf("PumpSwapDirect", "RaydiumDirect", "MeteoraDirect", "OrcaDirect"))
        }
    }

    fun coverage(context: ExecutionRouteContext): StackCoverage {
        val providers = providerOrder(context)
        val support = providers.associateWith { it.supports(context) }
        val supported = support.filterValues { it.supported }.keys.map { it.providerName }
        val unsupported = support.filterValues { !it.supported }.keys.map { it.providerName }
        val senders = senderOrder().map { it.senderName }
        return StackCoverage(providers.map { it.providerName }, supported, unsupported, senders)
    }

    fun start(context: ExecutionRouteContext): StackCoverage {
        val providers = providerOrder(context)
        val support = providers.associateWith { it.supports(context) }
        val supported = support.filterValues { it.supported }.keys.map { it.providerName }
        val unsupported = support.filterValues { !it.supported }.keys.map { it.providerName }
        val senders = senderOrder().map { it.senderName }

        lifecycle("EXEC_STACK_START", "side=${context.side.name} mint=${context.mint.take(12)} symbol=${context.symbol} reason=${context.reason} urgency=${context.urgency.name} callSite=${context.callSite}")
        lifecycle("EXEC_BALANCE_AUTHORITY_USED", "source=${context.tokenBalanceAuthority} amountIn=${context.amountIn} raw=${context.amountInRaw} walletSol=${context.walletSol} side=${context.side.name}")
        lifecycle("EXEC_ROUTE_INTELLIGENCE_USED", "providers=${context.routeIntelligence.providersUsedForIntel()} liq=${context.routeIntelligence.liquidityDepthUsd} priceConf=${context.routeIntelligence.priceConfidence} recommended=${context.routeIntelligence.recommendedVenues} blocked=${context.routeIntelligence.blockedVenues} unsupported=${context.routeIntelligence.unsupportedVenues}")
        providers.forEach { p ->
            val s = support.getValue(p)
            lifecycle("EXEC_PROVIDER_TRY", "provider=${p.providerName} side=${context.side.name} supported=${s.supported} reason=${s.reason}")
            if (!s.supported) lifecycle("EXEC_PROVIDER_FAIL", "provider=${p.providerName} reason=UNSUPPORTED:${s.reason}")
        }
        senders.forEach { lifecycle("EXEC_SENDER_TRY", "sender=$it planned=true") }
        lifecycle("EXEC_STACK_COVERAGE", "providers=${providers.joinToString(",") { it.providerName }} supported=$supported unsupported=$unsupported senders=$senders")
        return StackCoverage(providers.map { it.providerName }, supported, unsupported, senders)
    }

    fun providerFail(providerName: String, failureClass: FailureClass, reason: String) =
        lifecycle("EXEC_PROVIDER_FAIL", "provider=$providerName reason=${failureClass.name}:${reason.take(140)}")

    fun providerSuccess(providerName: String, detail: String) =
        lifecycle("EXEC_PROVIDER_SUCCESS", "provider=$providerName $detail")

    fun senderFail(senderName: String, failureClass: FailureClass, reason: String) =
        lifecycle("EXEC_SENDER_FAIL", "sender=$senderName reason=${failureClass.name}:${reason.take(140)}")

    fun senderSuccess(senderName: String, signature: String) =
        lifecycle("EXEC_SENDER_SUCCESS", "sender=$senderName sig=${signature.take(18)}")

    fun confirmationSource(source: String, detail: String) =
        lifecycle("EXEC_CONFIRMATION_SOURCE", "source=$source $detail")

    fun finalProvider(providerName: String, senderName: String) {
        lifecycle("EXEC_FINAL_PROVIDER", "provider=$providerName")
        lifecycle("EXEC_FINAL_SENDER", "sender=$senderName")
    }

    fun stackExhausted(context: ExecutionRouteContext, failureClass: FailureClass, reason: String) =
        lifecycle("EXEC_STACK_EXHAUSTED", "side=${context.side.name} mint=${context.mint.take(12)} failure=${failureClass.name} reason=${reason.take(160)}")

    private fun lifecycle(event: String, fields: String) {
        try { ForensicLogger.lifecycle(event, fields) } catch (_: Throwable) {}
    }
}
