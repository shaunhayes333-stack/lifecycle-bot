package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ExecutionHealthGuard
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.network.PumpFunDirectApi

/**
 * V5.0.6411 §3.2 + §3.3 + §3.4 + §3.5 — ROUTE RESOLVER.
 *
 * P0 UNBLOCK
 * ──────────
 * Build V5.0.6410 authorised 501 live buys and submitted ZERO because
 * `ExecutionHealthGuard.shouldDeferBuy()` returned true globally as
 * long as Jupiter was unhealthy — even for pump.fun mints that have
 * a direct adapter and never needed Jupiter. `BUY_DEFERRED_JUPITER_DEAD`
 * fired 512 times while direct routes stood idle.
 *
 * This resolver replaces that global gate. It:
 *   1. Classifies the mint into an [ExecutableVenue6411] using
 *      chain/source evidence.
 *   2. Consults [AdapterCapability6411.ALL] for adapters that
 *      declare support for that venue.
 *   3. Filters by [ProviderDomainCircuits6411.isAvailable] and,
 *      for JUPITER_QUOTE_SWAP, also [ExecutionHealthGuard.isJupiterHealthy].
 *   4. Returns the highest-preference healthy adapter, or a typed
 *      terminal outcome when nothing can route.
 *
 * Preference order (§3.3):
 *   PUMP_FUN_BONDING_CURVE  → PUMP_FUN_DIRECT, PUMPPORTAL_AUTO, JUPITER
 *   PUMPSWAP                → PUMPPORTAL_AUTO, JUPITER
 *   RAYDIUM_CPMM / CLMM     → PUMPPORTAL_AUTO, JUPITER
 *   ORCA_WHIRLPOOL          → JUPITER
 *   MULTI_VENUE / UNKNOWN   → PUMPPORTAL_AUTO, JUPITER
 *
 * Jupiter-down NEVER blocks a route that has a healthy direct
 * adapter. This is the fix that resumes trading.
 */
object RouteResolver6411 {

    enum class Outcome {
        DIRECT_ADAPTER_SELECTED,
        JUPITER_SELECTED,
        JUPITER_SKIPPED_CIRCUIT_OPEN,
        DIRECT_ADAPTER_UNAVAILABLE,
        ALL_ADAPTERS_UNAVAILABLE,
        VENUE_UNRESOLVED,
        UNSUPPORTED_VENUE,
    }

    data class Verdict(
        val proceed: Boolean,
        val outcome: Outcome,
        val venue: ExecutableVenue6411,
        val adapter: ExecutionAdapter6411?,
        val candidates: List<ExecutionAdapter6411>,
        val skipped: List<Pair<ExecutionAdapter6411, String>>,
        val reason: String,
    ) {
        fun toLogFields(mint: String, symbol: String, source: String): String =
            "mint=${mint.take(10)} sym=$symbol src=${source.take(48)} venue=$venue " +
                "outcome=$outcome adapter=${adapter?.name ?: "-"} " +
                "candidates=[${candidates.joinToString(",") { it.name }}] " +
                "skipped=[${skipped.joinToString(",") { "${it.first.name}:${it.second}" }}] reason=$reason"
    }

    /**
     * Classify the mint into a venue. Chain-authoritative when
     * possible (pump.fun mint suffix), otherwise best-effort from
     * the discovery source.
     */
    fun resolveVenue(mint: String, source: String): ExecutableVenue6411 {
        // 1) Chain-authoritative: pump.fun bonding-curve mints end in "pump".
        val isPumpFun = try { PumpFunDirectApi.isPumpFunMint(mint) } catch (_: Throwable) { false }
        if (isPumpFun) return ExecutableVenue6411.PUMP_FUN_BONDING_CURVE

        // 2) Source-hint (advisory only when chain proof absent).
        val src = source.uppercase()
        return when {
            src.contains("PUMPSWAP") -> ExecutableVenue6411.PUMPSWAP
            src.contains("PUMP_PORTAL") || src.contains("PUMP_FUN") ->
                ExecutableVenue6411.PUMP_FUN_BONDING_CURVE
            src.contains("RAYDIUM_NEW_POOL") || src.contains("RAYDIUM_CPMM") ->
                ExecutableVenue6411.RAYDIUM_CPMM
            src.contains("RAYDIUM_CLMM") -> ExecutableVenue6411.RAYDIUM_CLMM
            src.contains("ORCA") || src.contains("WHIRLPOOL") ->
                ExecutableVenue6411.ORCA_WHIRLPOOL
            src.contains("METEORA") -> ExecutableVenue6411.JUPITER_ONLY
            src.isBlank() -> ExecutableVenue6411.UNKNOWN_PENDING
            else -> ExecutableVenue6411.MULTI_VENUE
        }
    }

    private fun preferenceOrder(venue: ExecutableVenue6411): List<ExecutionAdapter6411> = when (venue) {
        ExecutableVenue6411.PUMP_FUN_BONDING_CURVE -> listOf(
            ExecutionAdapter6411.PUMP_FUN_DIRECT,
            ExecutionAdapter6411.PUMPPORTAL_AUTO,
            ExecutionAdapter6411.JUPITER_QUOTE_SWAP,
        )
        ExecutableVenue6411.PUMPSWAP,
        ExecutableVenue6411.RAYDIUM_CPMM,
        ExecutableVenue6411.RAYDIUM_CLMM,
        ExecutableVenue6411.MULTI_VENUE -> listOf(
            ExecutionAdapter6411.PUMPPORTAL_AUTO,
            ExecutionAdapter6411.JUPITER_QUOTE_SWAP,
        )
        ExecutableVenue6411.ORCA_WHIRLPOOL,
        ExecutableVenue6411.JUPITER_ONLY -> listOf(
            ExecutionAdapter6411.JUPITER_QUOTE_SWAP,
        )
        ExecutableVenue6411.UNKNOWN_PENDING -> listOf(
            ExecutionAdapter6411.PUMPPORTAL_AUTO,
            ExecutionAdapter6411.JUPITER_QUOTE_SWAP,
        )
        ExecutableVenue6411.UNSUPPORTED -> emptyList()
    }

    private fun adapterHealthy(adapter: ExecutionAdapter6411): Pair<Boolean, String> {
        val circuit = ProviderDomainCircuits6411.isAvailable(adapter)
        if (!circuit) return false to "circuit_open"
        if (adapter == ExecutionAdapter6411.JUPITER_QUOTE_SWAP) {
            val jupHealthy = try { ExecutionHealthGuard.isJupiterHealthy() } catch (_: Throwable) { true }
            if (!jupHealthy) return false to "jupiter_unhealthy"
        }
        return true to "healthy"
    }

    /**
     * Resolve the best adapter for [mint] on [source]. This is the
     * replacement for `ExecutionHealthGuard.shouldDeferBuy()` at the
     * buy-side gate. Emits typed forensic events for every terminal
     * decision so operators see the exact bottleneck stage.
     */
    fun resolve(mint: String, symbol: String, source: String): Verdict {
        val venue = resolveVenue(mint, source)
        val candidates = preferenceOrder(venue)
        if (candidates.isEmpty()) {
            val v = Verdict(
                proceed = false, outcome = Outcome.UNSUPPORTED_VENUE,
                venue = venue, adapter = null, candidates = emptyList(),
                skipped = emptyList(), reason = "no_candidate_adapters_for_venue",
            )
            emit(v, mint, symbol, source)
            return v
        }
        val skipped = mutableListOf<Pair<ExecutionAdapter6411, String>>()
        for (adapter in candidates) {
            val (ok, reason) = adapterHealthy(adapter)
            if (ok) {
                val outcome = when (adapter) {
                    ExecutionAdapter6411.JUPITER_QUOTE_SWAP -> Outcome.JUPITER_SELECTED
                    else -> Outcome.DIRECT_ADAPTER_SELECTED
                }
                val v = Verdict(
                    proceed = true, outcome = outcome, venue = venue,
                    adapter = adapter, candidates = candidates, skipped = skipped,
                    reason = "adapter_healthy",
                )
                emit(v, mint, symbol, source)
                return v
            } else {
                skipped.add(adapter to reason)
                if (adapter == ExecutionAdapter6411.JUPITER_QUOTE_SWAP && reason == "circuit_open") {
                    try { PipelineHealthCollector.labelInc("ROUTE_JUPITER_SKIPPED_CIRCUIT_OPEN_6411") } catch (_: Throwable) {}
                }
            }
        }
        // All candidates unavailable.
        val v = Verdict(
            proceed = false, outcome = Outcome.ALL_ADAPTERS_UNAVAILABLE,
            venue = venue, adapter = null, candidates = candidates, skipped = skipped,
            reason = "no_healthy_adapter",
        )
        emit(v, mint, symbol, source)
        return v
    }

    private fun emit(v: Verdict, mint: String, symbol: String, source: String) {
        try {
            val tag = when (v.outcome) {
                Outcome.DIRECT_ADAPTER_SELECTED -> "EXEC_ADAPTER_SELECTED_6411"
                Outcome.JUPITER_SELECTED -> "EXEC_ADAPTER_SELECTED_6411"
                Outcome.JUPITER_SKIPPED_CIRCUIT_OPEN -> "EXEC_ADAPTER_SKIPPED_CIRCUIT_6411"
                Outcome.DIRECT_ADAPTER_UNAVAILABLE -> "EXEC_ADAPTER_SKIPPED_CIRCUIT_6411"
                Outcome.ALL_ADAPTERS_UNAVAILABLE -> "EXEC_ROUTE_TERMINAL_6411"
                Outcome.VENUE_UNRESOLVED -> "EXEC_VENUE_UNRESOLVED_6411"
                Outcome.UNSUPPORTED_VENUE -> "EXEC_ROUTE_TERMINAL_6411"
            }
            ForensicLogger.lifecycle(tag, v.toLogFields(mint, symbol, source))
            PipelineHealthCollector.labelInc(tag)
            if (v.outcome == Outcome.DIRECT_ADAPTER_SELECTED || v.outcome == Outcome.JUPITER_SELECTED) {
                PipelineHealthCollector.labelInc("EXEC_ROUTE_RESOLVED_6411")
            }
            PipelineHealthCollector.labelInc("EXEC_VENUE_RESOLVED_6411")
        } catch (_: Throwable) {}
    }
}
