package com.lifecyclebot.engine.execution

/**
 * V5.9.495z19 — Pre- + post-execution route validator.
 *
 * Operator spec rule: a buy is only successful if the FINAL target token
 * lands in the host wallet. Receiving USDC (or any other intermediate) does
 * not count.
 *
 * ── Pre-validation (validateRoute) ────────────────────────────────────────
 *   • routePlan.finalOutputMint == intent.targetMint
 *   • routePlan.inputMint        == intent.inputMint
 *   • if intermediates contain USDC, route.atomic must be true UNLESS
 *     routeType == MANUAL_TWO_LEG (and second leg is already executable)
 *   • if routeType == MANUAL_TWO_LEG, the second leg must already be built
 *   • estimated output > 0
 *
 * ── Post-validation (validateFinalOutput) ─────────────────────────────────
 *   • A → targetMint balance INCREASED                    → OK_TARGET_VERIFIED
 *   • B → only USDC INCREASED (and target wasn't USDC)    → INTERMEDIATE_ASSET_HELD
 *   • C → SOL decreased but no output token               → FAILED_TX_CONFIRMED
 *   • D → tx error                                         → FAILED_TX_CONFIRMED
 */
object RouteValidator {

    const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"

    sealed class PreResult {
        data object Ok : PreResult()
        data class Reject(val reason: String, val code: String) : PreResult()
    }

    sealed class PostResult {
        data class TargetVerified(val rawDelta: Long, val uiDelta: Double) : PostResult()
        data class IntermediateHeld(val intermediateMint: String, val rawDelta: Long) : PostResult()
        data class Failed(val reason: String) : PostResult()
    }

    /**
     * Run pre-execution checks. The caller MUST NOT broadcast a transaction
     * if this returns Reject — Forensics.OUTPUT_MISMATCH_BLOCKED logs it.
     */
    fun validateRoute(intent: TradeIntent, route: RoutePlan): PreResult {
        if (route.finalOutputMint != intent.targetMint) {
            return PreResult.Reject(
                reason = "final output ${short(route.finalOutputMint)} != target ${short(intent.targetMint)}",
                code = "OUTPUT_MISMATCH",
            )
        }
        if (route.inputMint != intent.inputMint) {
            return PreResult.Reject(
                reason = "input ${short(route.inputMint)} != intent input ${short(intent.inputMint)}",
                code = "INPUT_MISMATCH",
            )
        }
        val hasUsdcOrUsdtMid = route.intermediateMints.any { it == USDC_MINT || it == USDT_MINT }
        if (hasUsdcOrUsdtMid && !route.atomic && route.routeType != RouteType.MANUAL_TWO_LEG) {
            return PreResult.Reject(
                reason = "non-atomic route with USDC/USDT intermediate is forbidden " +
                         "(use Jupiter multi-hop or atomic route)",
                code = "NON_ATOMIC_INTERMEDIATE",
            )
        }
        if (route.routeType == RouteType.MANUAL_TWO_LEG && route.legs.size < 2) {
            return PreResult.Reject(
                reason = "manual two-leg route requires both legs pre-built",
                code = "MANUAL_TWO_LEG_INCOMPLETE",
            )
        }
        val expected = route.expectedOutRaw?.toLongOrNull() ?: -1L
        if (expected == 0L) {
            return PreResult.Reject(reason = "expected output is 0", code = "ZERO_OUTPUT")
        }
        return PreResult.Ok
    }

    /**
     * Post-execution check. Caller computes the wallet token deltas (post - pre)
     * and feeds the map { mint -> rawDelta } here.
     *
     * @param intent           the original intent
     * @param tokenDeltasRaw   { mint -> rawDelta (signed) } where positive = increased
     * @param targetDecimals   decimals for the target mint (for friendly UI value)
     * @param txConfirmed      true if the tx was confirmed on-chain (any error in meta)
     * @param txMetaErr        non-null if the tx had a program error
     */
    fun validateFinalOutput(
        intent: TradeIntent,
        tokenDeltasRaw: Map<String, Long>,
        targetDecimals: Int,
        txConfirmed: Boolean,
        txMetaErr: String? = null,
    ): PostResult {
        if (!txConfirmed) return PostResult.Failed("tx not confirmed on chain")
        if (!txMetaErr.isNullOrBlank()) return PostResult.Failed("tx meta err: $txMetaErr")

        val targetDelta = tokenDeltasRaw[intent.targetMint] ?: 0L
        if (targetDelta > 0) {
            val ui = targetDelta.toDouble() / Math.pow(10.0, targetDecimals.toDouble())
            return PostResult.TargetVerified(targetDelta, ui)
        }

        // Target didn't land — check for intermediate USDC/USDT residue.
        val usdcDelta = tokenDeltasRaw[USDC_MINT] ?: 0L
        val usdtDelta = tokenDeltasRaw[USDT_MINT] ?: 0L

        if (intent.targetMint != USDC_MINT && usdcDelta > 0) {
            return PostResult.IntermediateHeld(USDC_MINT, usdcDelta)
        }
        if (intent.targetMint != USDT_MINT && usdtDelta > 0) {
            return PostResult.IntermediateHeld(USDT_MINT, usdtDelta)
        }
        return PostResult.Failed("no target delta and no recognised intermediate held")
    }

    private fun short(mint: String): String =
        if (mint.length > 8) mint.take(6) + "…" + mint.takeLast(4) else mint
}
