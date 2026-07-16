package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.TokenState

/**
 * V5.0.4001 — Full-stack provider quorum for LIVE entry.
 *
 * This is deliberately NOT a hot-path API caller. It evaluates the provider
 * evidence already cached on TokenState plus passive ApiHealthMonitor state.
 * Birdeye/Gecko/LLM degradation may reduce confidence, but cannot globally
 * stall live execution when executable route + free-market quorum are present.
 */
object LiveProviderQuorum {
    data class Verdict(
        val allowed: Boolean,
        val hardBlock: Boolean,
        val routeCriticalOk: Boolean,
        val marketProofCount: Int,
        val providers: List<String>,
        val degraded: List<String>,
        val reason: String,
        val multiplier: Double,
    ) {
        fun summary(): String = "allowed=$allowed routeCritical=$routeCriticalOk marketProof=$marketProofCount providers=${providers.joinToString("+")} degraded=${degraded.joinToString("+")} reason=$reason mult=${"%.2f".format(multiplier)}"
    }

    fun evaluate(ts: TokenState, routeAllowed: Boolean, cfg: BotConfig): Verdict {
        val src = listOf(ts.source, ts.lastPriceSource, ts.lastPriceDex, ts.pairUrl, ts.pairAddress, ts.lastPricePoolAddr)
            .joinToString("|")
            .uppercase()
        fun hostHealthy(vararg names: String): Boolean = names.any { ApiHealthMonitor.successRate(it) >= 0.45 }
        fun hostDegraded(vararg names: String): Boolean = names.any { ApiHealthMonitor.successRate(it) < 0.45 }

        val routeCriticalOk = routeAllowed && (
            src.contains("JUP") || src.contains("PUMP") || src.contains("RAYDIUM") ||
            src.contains("PUMPSWAP") || src.contains("METEORA") || src.contains("ORCA") ||
            ts.lastPrice > 0.0
        )
        if (!routeCriticalOk) {
            return Verdict(false, true, false, 0, emptyList(), emptyList(), "NO_EXECUTABLE_ROUTE_QUORUM", 0.0)
        }

        val providers = mutableListOf<String>()
        if (src.contains("DEX") || ts.pairAddress.isNotBlank() || hostHealthy("dexscreener", "api.dexscreener.com")) providers += "DEXSCREENER"
        if (src.contains("PUMP") || hostHealthy("pumpfun", "pump.fun", "frontend-api.pump.fun", "pumpportal")) providers += "PUMPFUN"
        if (src.contains("BIRDEYE") || hostHealthy("birdeye", "public-api.birdeye.so")) providers += "BIRDEYE"
        if (src.contains("GECKO") || hostHealthy("geckoterminal", "api.geckoterminal.com")) providers += "GECKOTERMINAL"
        if (hostHealthy("coingecko", "api.coingecko.com") || ts.lastPrice > 0.0) providers += "COINGECKO_SOL_CONTEXT"

        val degraded = mutableListOf<String>()
        if (hostDegraded("birdeye", "public-api.birdeye.so")) degraded += "BIRDEYE"
        if (hostDegraded("geckoterminal", "api.geckoterminal.com")) degraded += "GECKOTERMINAL"
        if (hostDegraded("gemini", "groq")) degraded += "LLM_ADVISORY_ONLY"

        val marketCount = providers.distinct().size
        // V5.0.6266 — PROVIDER FAIL-OPEN. Operator report V5.0.6265 showed
        // 0 live executions during a whole-ecosystem outage (Helius 429,
        // Birdeye 401, Dexscreener 5xx timeouts). The strict marketCount >= 2
        // rule then hard-blocked every buy even when Jupiter route was up and
        // TokenState carried a live price/pair from PumpPortal WS. That is a
        // provider-outage self-DOS, not risk control: with an executable route
        // and at least one live-price proof, the bot must trade instead of
        // sitting on funds. Fail-open when providers collapse but route is
        // live. Sizing multiplier drops to 0.60 as a defensive shape.
        val livePriceProof = ts.lastPrice > 0.0
        val failOpenEligible = marketCount < 2 && routeCriticalOk &&
            (livePriceProof || providers.any { it == "PUMPFUN" || it == "DEXSCREENER" }) &&
            degraded.size >= 2
        val allowed = marketCount >= 2 || failOpenEligible
        val multiplier = when {
            !allowed -> 0.0
            failOpenEligible -> 0.60
            degraded.size >= 2 -> 0.85
            degraded.isNotEmpty() -> 0.93
            else -> 1.0
        }
        val reason = when {
            marketCount >= 2 -> "PROVIDER_QUORUM_OK"
            failOpenEligible -> "PROVIDER_QUORUM_FAILOPEN_6266 marketCount=$marketCount degraded=${degraded.size} livePriceProof=$livePriceProof"
            else -> "MARKET_PROOF_QUORUM_INSUFFICIENT"
        }
        return Verdict(
            allowed = allowed,
            hardBlock = !allowed,
            routeCriticalOk = true,
            marketProofCount = marketCount,
            providers = providers.distinct(),
            degraded = degraded.distinct(),
            reason = reason,
            multiplier = multiplier,
        )
    }
}
