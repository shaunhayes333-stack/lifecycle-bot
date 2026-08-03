package com.lifecyclebot.engine.truth

/**
 * V5.0.6411 §3.2 — EXECUTABLE VENUE CLASSIFICATION.
 *
 * Before execution authorisation, resolve every candidate into exactly
 * one venue. Do NOT infer the venue from the scanner source alone —
 * scanner sources are noisy (a mint discovered on Raydium may already
 * have graduated to Pump AMM or vice-versa). The venue is resolved
 * from chain/pool evidence in [RouteResolver6411].
 */
enum class ExecutableVenue6411 {
    PUMP_FUN_BONDING_CURVE,
    PUMPSWAP,
    RAYDIUM_CPMM,
    RAYDIUM_CLMM,
    ORCA_WHIRLPOOL,
    JUPITER_ONLY,
    MULTI_VENUE,
    UNKNOWN_PENDING,
    UNSUPPORTED,
}

/**
 * V5.0.6411 §3.3 — ADAPTER CAPABILITY MATRIX.
 *
 * Each execution adapter advertises the venues it can execute.
 * The route resolver consults this matrix rather than making
 * assumptions from a single source label.
 */
enum class ExecutionAdapter6411 {
    PUMP_FUN_DIRECT,     // pump.fun bonding-curve direct via PumpFunDirectApi
    PUMPPORTAL_AUTO,     // PumpPortal Lightning pool="auto" (pump/pumpswap/raydium)
    RAYDIUM_CPMM_DIRECT, // future
    RAYDIUM_CLMM_DIRECT, // future
    ORCA_DIRECT,         // future
    JUPITER_QUOTE_SWAP,  // Jupiter Ultra / Metis via network stack
}

data class AdapterCapability6411(
    val adapter: ExecutionAdapter6411,
    val venues: Set<ExecutableVenue6411>,
    val supportsBuy: Boolean,
    val supportsSell: Boolean,
    val supportsToken2022: Boolean,
    val requiresQuoteProvider: Boolean,
    val requiresPoolKeys: Boolean,
) {
    companion object {
        val ALL: List<AdapterCapability6411> = listOf(
            AdapterCapability6411(
                adapter = ExecutionAdapter6411.PUMP_FUN_DIRECT,
                venues = setOf(ExecutableVenue6411.PUMP_FUN_BONDING_CURVE),
                supportsBuy = true, supportsSell = true,
                supportsToken2022 = false,
                requiresQuoteProvider = false, requiresPoolKeys = false,
            ),
            AdapterCapability6411(
                adapter = ExecutionAdapter6411.PUMPPORTAL_AUTO,
                venues = setOf(
                    ExecutableVenue6411.PUMP_FUN_BONDING_CURVE,
                    ExecutableVenue6411.PUMPSWAP,
                    ExecutableVenue6411.RAYDIUM_CPMM,
                    ExecutableVenue6411.MULTI_VENUE,
                ),
                supportsBuy = true, supportsSell = true,
                supportsToken2022 = false,
                requiresQuoteProvider = false, requiresPoolKeys = false,
            ),
            AdapterCapability6411(
                adapter = ExecutionAdapter6411.JUPITER_QUOTE_SWAP,
                venues = setOf(
                    ExecutableVenue6411.PUMP_FUN_BONDING_CURVE,
                    ExecutableVenue6411.PUMPSWAP,
                    ExecutableVenue6411.RAYDIUM_CPMM,
                    ExecutableVenue6411.RAYDIUM_CLMM,
                    ExecutableVenue6411.ORCA_WHIRLPOOL,
                    ExecutableVenue6411.JUPITER_ONLY,
                    ExecutableVenue6411.MULTI_VENUE,
                ),
                supportsBuy = true, supportsSell = true,
                supportsToken2022 = true,
                requiresQuoteProvider = true, requiresPoolKeys = false,
            ),
        )
    }
}
