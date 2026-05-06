package com.lifecyclebot.engine.execution

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.network.PumpFunDirectApi
import java.util.UUID

/**
 * V5.9.495z20 — Universal Route Engine.
 *
 * Single decision authority for "which venue and route should I use to buy
 * this mint with this much capital?". Returns a fully populated `RoutePlan`
 * that the executor can sign + broadcast.
 *
 * Decision tree (operator spec):
 *   1. If the target is pump-family (mint ends with "pump", or PumpPortal
 *      Lightning advertises a pool) → PUMPPORTAL_DIRECT first. Pump
 *      handles its own bonding curve / PumpSwap AMM / Raydium routing
 *      inside one tx. Atomic by definition.
 *   2. Otherwise → JUPITER_MULTI_HOP atomic single-call with
 *      inputMint = source, outputMint = targetMint. Jupiter Meta-Aggregator
 *      may route internally through USDC, but the entire transaction is
 *      atomic — target lands or it reverts.
 *   3. MANUAL_TWO_LEG is supported but NEVER selected automatically — only
 *      callers that explicitly opt in (e.g. building a USDC reserve before
 *      a separate buy) get this route type.
 *
 * The engine never broadcasts. It only plans + validates. The caller is
 * responsible for signing/sending and feeding the post-tx parse back to
 * RouteValidator.validateFinalOutput.
 */
object UniversalRouteEngine {

    private const val TAG = "UniversalRouteEngine"

    /** Standard mint addresses. */
    const val SOL_MINT  = "So11111111111111111111111111111111111111112"
    const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"

    /** Stablecoins that Jupiter is allowed to use as an internal-hop intermediate. */
    private val ALLOWED_INTERMEDIATES = setOf(USDC_MINT, USDT_MINT)

    /**
     * Build a buy route for the given intent. Returns null if no route is
     * available (caller should mark FAILED_NO_ROUTE).
     *
     * The returned RoutePlan is GUARANTEED to have:
     *   • finalOutputMint == intent.targetMint
     *   • inputMint        == intent.inputMint
     *   • atomic           == true       (we never plan non-atomic two-legs)
     *
     * Pre-validation via RouteValidator.validateRoute() is run before
     * returning so callers don't need to re-validate the obvious cases.
     */
    fun buildBuyRoute(intent: TradeIntent): RoutePlan? {
        Forensics.log(
            Forensics.Event.ROUTE_INTENT_CREATED,
            mint = intent.targetMint,
            msg = "intent=${intent.intentId} ${intent.inputMint.take(6)}…→${intent.targetMint.take(6)}… amt=${intent.inputAmountRaw}",
        )

        val plan = pickRoute(intent) ?: run {
            Forensics.log(Forensics.Event.ROUTE_VALIDATE_PRE_FAILED, intent.targetMint, "no route available")
            return null
        }

        // Enforce the spec rule once more before returning — defense in depth.
        when (val v = RouteValidator.validateRoute(intent, plan)) {
            is RouteValidator.PreResult.Ok -> {
                Forensics.log(
                    Forensics.Event.ROUTE_VALIDATE_PRE_OK,
                    mint = intent.targetMint,
                    msg = "route=${plan.routeType.name} venue=${plan.source} atomic=${plan.atomic}",
                )
                Forensics.log(
                    Forensics.Event.ROUTE_SELECTED,
                    mint = intent.targetMint,
                    msg = "${plan.routeType.name} via ${plan.source}",
                )
                return plan
            }
            is RouteValidator.PreResult.Reject -> {
                Forensics.log(
                    Forensics.Event.OUTPUT_MISMATCH_BLOCKED,
                    mint = intent.targetMint,
                    msg = "${v.code}: ${v.reason}",
                )
                ErrorLogger.warn(TAG, "route rejected: ${v.code} ${v.reason}")
                return null
            }
        }
    }

    /** Pump detection — pump-family mints get PumpPortal first. */
    fun isPumpFamilyMint(mint: String): Boolean = PumpFunDirectApi.isPumpFunMint(mint)

    // ── internals ─────────────────────────────────────────────────────────────

    private fun pickRoute(intent: TradeIntent): RoutePlan? {
        // Step 1 — pump-family → PumpPortal direct.
        // Note: PumpPortal currently only supports SOL as input. If someone
        // is buying with USDC, we skip pump and let Jupiter take it.
        if (intent.side == Side.BUY &&
            intent.inputMint == SOL_MINT &&
            isPumpFamilyMint(intent.targetMint)
        ) {
            return planPumpPortalDirect(intent)
        }

        // Step 2 — Jupiter atomic single-call. Source → target with internal
        // USDC/USDT hop allowed. The execution layer (UniversalBridgeEngine
        // or its successor) calls executeJupiterSwap with these mints.
        return planJupiterAtomic(intent)
    }

    private fun planPumpPortalDirect(intent: TradeIntent): RoutePlan {
        val routeId = "rp_pump_" + UUID.randomUUID().toString().take(10)
        val leg = RouteLeg(
            legIndex = 0,
            inputMint = intent.inputMint,
            outputMint = intent.targetMint,
            amountRaw = intent.inputAmountRaw,
            venue = Venue.PUMPPORTAL.name,
            status = LegStatus.PENDING,
            signature = null,
        )
        return RoutePlan(
            routePlanId = routeId,
            intentId = intent.intentId,
            routeType = RouteType.PUMPPORTAL_DIRECT,
            inputMint = intent.inputMint,
            finalOutputMint = intent.targetMint,
            expectedOutRaw = null,                  // PumpPortal doesn't pre-quote
            intermediateMints = emptyList(),
            legs = listOf(leg),
            atomic = true,
            source = Venue.PUMPPORTAL.name,
        )
    }

    private fun planJupiterAtomic(intent: TradeIntent): RoutePlan {
        val routeId = "rp_jup_" + UUID.randomUUID().toString().take(10)

        // We don't know yet if Jupiter will internally hop through USDC for
        // this pair. Be conservative and declare USDC + USDT as POSSIBLE
        // intermediates so the validator allows them. Jupiter's atomic flag
        // makes this safe.
        val intermediates = if (intent.inputMint == USDC_MINT || intent.targetMint == USDC_MINT)
            emptyList()
        else listOf(USDC_MINT, USDT_MINT)

        val leg = RouteLeg(
            legIndex = 0,
            inputMint = intent.inputMint,
            outputMint = intent.targetMint,
            amountRaw = intent.inputAmountRaw,
            venue = Venue.JUPITER_SWAP_V2.name,
            status = LegStatus.PENDING,
            signature = null,
        )
        return RoutePlan(
            routePlanId = routeId,
            intentId = intent.intentId,
            routeType = RouteType.JUPITER_MULTI_HOP,
            inputMint = intent.inputMint,
            finalOutputMint = intent.targetMint,
            expectedOutRaw = null,                  // populated post-quote by the executor
            intermediateMints = intermediates,
            legs = listOf(leg),
            atomic = true,                          // single Jupiter tx is atomic by definition
            source = Venue.JUPITER_SWAP_V2.name,
        )
    }

    /** Convenience builder for the meme/crypto-alt lanes. */
    fun newBuyIntent(
        targetMint: String,
        solAmountUi: Double,
        walletPubkey: String,
        env: Environment,
        reason: String,
        preferredVenue: Venue? = null,
        maxSlippageBps: Int? = null,
    ): TradeIntent {
        val lamports = (solAmountUi * 1_000_000_000L).toLong().coerceAtLeast(0L)
        return TradeIntent(
            intentId = "ti_" + UUID.randomUUID().toString().take(12),
            side = Side.BUY,
            inputMint = SOL_MINT,
            outputMint = targetMint,
            targetMint = targetMint,
            inputAmountRaw = lamports.toString(),
            inputUiAmount = solAmountUi,
            wallet = walletPubkey,
            environment = env,
            preferredVenue = preferredVenue,
            maxSlippageBps = maxSlippageBps,
            reason = reason,
        )
    }
}
