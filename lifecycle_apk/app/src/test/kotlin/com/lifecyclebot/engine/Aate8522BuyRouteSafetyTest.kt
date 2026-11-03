package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SwapQuote
import com.lifecyclebot.network.SwapTxResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8522BuyRouteSafetyTest {
    private val now = 1_800_000_000_000L
    private val mint = "Route8522AAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump"
    private fun pool(id: String, lock: Double = 100.0, depth: Double = 10_000.0) =
        LiquidityOwnership8522.Pool(id, "raydium", lock, depth)
    private fun report(vararg pools: LiquidityOwnership8522.Pool) = SafetyReport(
        checkedAt = now - 1_000L, evaluatedForPaper8522 = false,
        mintAuthorityDisabled = true, freezeAuthorityDisabled = true,
        lpOwnership8522 = LiquidityOwnership8522.Evidence(mint, pools.toList(), false, true),
    )
    private fun hop(input: String, output: String, pool: String) = JSONObject().put("swapInfo",
        JSONObject().put("inputMint", input).put("outputMint", output).put("ammKey", pool))
    private fun quote(vararg pools: String, router: String = "metis", legacyRfq: Boolean = false) = SwapQuote(
        raw = JSONObject().put("routePlan", JSONArray().apply { pools.forEach { put(hop(JupiterApi.SOL_MINT, mint, it)) } }),
        outAmount = 1_000_000L, priceImpactPct = 0.1, inputMint = JupiterApi.SOL_MINT,
        outputMint = mint, inAmount = 30_000_000L, router = router, isRfqRoute = legacyRfq, receivedAtMs8521 = now - 500L,
    )
    private fun assess(r: SafetyReport, route: BuyRouteSafety8522.Route, curve: Boolean = false) =
        BuyRouteSafety8522.assess(mint, r, route, curve, "curve", false, now)

    @Test fun multiHopUsesTargetTokenPoolInsteadOfTheSolIntermediate() {
        val q = quote().copy(raw = JSONObject().put("routePlan", JSONArray()
            .put(hop(JupiterApi.SOL_MINT, "stable", "sol-stable"))
            .put(hop("stable", mint, "target"))))
        val route = BuyRouteSafety8522.jupiter(q)
        assertEquals(listOf("target"), route.pools)
        assertEquals(LiquidityOwnership8522.Status.PROVEN, assess(report(pool("target")), route).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(report(pool("sol-stable")), route).status)
    }

    @Test fun splitRouteMustProveEveryTargetPool() {
        val route = BuyRouteSafety8522.jupiter(quote("main", "side"))
        assertEquals(LiquidityOwnership8522.Status.PROVEN, assess(report(pool("main"), pool("side")), route).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(report(pool("main")), route).status)
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(report(pool("main"), pool("side", 5.0)), route).status)
    }

    @Test fun selectedThinOrEmptyPoolCannotBorrowDeepPriorPoolLiquidity() {
        val route = BuyRouteSafety8522.jupiter(quote("side"))
        val safety = report(pool("main", depth = 100_000.0), pool("side", depth = 100.0))
        assertEquals("BUY_ROUTE_LIQUIDITY_BELOW_EXIT_FLOOR_8522", assess(safety, route).reason)
        val observedEmpty = report(pool("side")).copy(liquidityObservedUsd8522 = 0.0,
            liquidityObservedAtMs8522 = now - 10L, liquidityObservedPool8522 = "side")
        assertEquals("BUY_ROUTE_LIQUIDITY_BELOW_EXIT_FLOOR_8522", assess(observedEmpty, route).reason)
        val unknown = report(pool("side", depth = -1.0))
        assertEquals("BUY_ROUTE_LIQUIDITY_PROOF_PENDING_8522", assess(unknown, route).reason)
    }

    @Test fun changedPolicyOrExpiredRiskReportCannotAuthorizeFinalSigning() {
        val route = BuyRouteSafety8522.jupiter(quote("main"))
        val safety = report(pool("main"))
        assertEquals("BUY_FINAL_SAFETY_REPORT_PENDING_8522", assess(safety.copy(evaluatedForPaper8522 = true), route).reason)
        assertEquals("BUY_FINAL_SAFETY_REPORT_PENDING_8522", assess(safety.copy(evaluatedForPaper8522 = null), route).reason)
        assertEquals("BUY_FINAL_SAFETY_REPORT_PENDING_8522", assess(safety.copy(checkedAt = now - 120_001L), route).reason)
    }

    @Test fun authorityDowngradeDuringFinalReadCannotBorrowTheEarlierApproval() {
        val route = BuyRouteSafety8522.jupiter(quote("main"))
        val safety = report(pool("main"))
        assertEquals(LiquidityOwnership8522.Status.PROVEN, assess(safety, route).status)
        assertEquals("BUY_FINAL_AUTHORITY_ACTIVE_8522", assess(safety.copy(mintAuthorityDisabled = false), route).reason)
        assertEquals("BUY_FINAL_AUTHORITY_ACTIVE_8522", assess(safety.copy(freezeAuthorityDisabled = false), route).reason)
        assertEquals("BUY_FINAL_AUTHORITY_PROOF_PENDING_8522", assess(safety.copy(mintAuthorityDisabled = null), route).reason)
        assertEquals("BUY_FINAL_AUTHORITY_PROOF_PENDING_8522", assess(safety.copy(freezeAuthorityDisabled = null), route).reason)
    }

    @Test fun finalBuilderQuoteCannotBorrowTheIndicativeRoutesApproval() {
        val indicative = quote("safe")
        val built = SwapTxResult("unsigned", executionQuote8521 = quote("unlocked"))
        val safety = report(pool("safe"), pool("unlocked", 0.0, 1_000.0))
        assertEquals(LiquidityOwnership8522.Status.PROVEN, assess(safety, BuyRouteSafety8522.jupiter(indicative)).status)
        assertEquals(LiquidityOwnership8522.Status.BLOCKED,
            assess(safety, BuyRouteSafety8522.jupiter(built.executionQuote8521)).status)
    }

    @Test fun finalAssessmentUsesTheUpdatedAdverseReportAfterBuild() {
        val route = BuyRouteSafety8522.jupiter(quote("main"))
        val before = report(pool("main"))
        assertEquals(LiquidityOwnership8522.Status.PROVEN, assess(before, route).status)
        val after = before.copy(lpOwnership8522 = before.lpOwnership8522.copy(explicitUnlocked = true))
        assertEquals("LP_UNLOCKED_CONFIRMED_8522", assess(after, route).reason)
    }

    @Test fun signingGuardActuallyStopsWhenTokenBecomesBlockedDuringBuild() {
        val token = TokenState(mint)
        val route = BuyRouteSafety8522.jupiter(quote("main"))
        token.safety = report(pool("main"))
        // Asynchronous safety update after the quote was produced, before a signing call.
        token.safety = token.safety.copy(tier = SafetyTier.HARD_BLOCK, hardBlockReasons = listOf("LP unlocked"))
        var signed = false
        try {
            BuyRouteSafety8522.requireAllowed(token, route, "TEST_FINAL_SIGN")
            signed = true
            fail("Current adverse safety must stop the signing boundary")
        } catch (e: BuyRouteSafety8522.Refused) {
            assertEquals("SAFETY_HARD_BLOCK", e.reason)
        }
        assertFalse(signed)
    }

    @Test fun raydiumRetainsExactQuotePoolAndOriginalRequestClock() {
        val raw = JSONObject().put("data", JSONObject().put("inputMint", JupiterApi.SOL_MINT)
            .put("outputMint", mint).put("routePlan", JSONArray().put(JSONObject()
                .put("inputMint", JupiterApi.SOL_MINT).put("outputMint", mint).put("poolId", "main"))))
        val route = BuyRouteSafety8522.raydium(raw, mint, now - 12_000L)
        assertEquals(now - 12_000L, route.observedAtMs)
        assertEquals(listOf("main"), route.pools)
        assertEquals(LiquidityOwnership8522.Status.PROVEN, assess(report(pool("main")), route).status)
        assertEquals("BUY_ROUTE_PROOF_STALE_8522", assess(report(pool("main")), route.copy(observedAtMs = now - 30_001L)).reason)
    }

    @Test fun legacyRfqFlagCannotExemptOnchainRouterPoolProof() {
        for (router in listOf("iris", "dflow", "okx", "hashflow", "rfq", "unknown")) {
            val route = BuyRouteSafety8522.jupiter(quote("unlocked", router = router, legacyRfq = true))
            assertFalse("$router is not proven JupiterZ maker inventory", route.rfq)
            assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(report(pool("unlocked", 0.0)), route).status)
        }
    }

    @Test fun jupiterZUsesFreshPrimaryExitMarketProofWithoutMistakingMakerForLp() {
        val route = BuyRouteSafety8522.jupiter(quote("maker", router = "jupiterz"))
        assertTrue(route.rfq)
        assertEquals(LiquidityOwnership8522.Status.PROVEN, assess(report(pool("main")), route).status)
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, assess(report(pool("main", 0.0)), route).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(report(), route).status)
    }

    @Test fun malformedOpaqueAndWrongMintRoutesStayPending() {
        val safety = report(pool("main"))
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(safety, BuyRouteSafety8522.jupiter(null)).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING, assess(safety, BuyRouteSafety8522.jupiter(quote())).status)
        val wrong = BuyRouteSafety8522.jupiter(quote("main").copy(outputMint = "other"))
        assertEquals("BUY_ROUTE_MINT_MISMATCH_8522", assess(safety, wrong).reason)
        val conflicting = quote("main").copy(raw = quote("main").raw.put("outputMint", "other"))
        assertEquals("BUY_ROUTE_MINT_MISMATCH_8522", assess(safety, BuyRouteSafety8522.jupiter(conflicting)).reason)
        val future = BuyRouteSafety8522.jupiter(quote("main").copy(receivedAtMs8521 = now + 1L))
        assertEquals("BUY_ROUTE_PROOF_STALE_8522", assess(safety, future).reason)
    }

    @Test fun curveAccountProofCannotAuthorizeAnUnknownOrdinaryRoute() {
        val safety = report().copy(lpOwnership8522 = LiquidityOwnership8522.Evidence())
        assertEquals(LiquidityOwnership8522.Status.PROVEN,
            assess(safety, BuyRouteSafety8522.pumpCurve(mint, "curve", now - 1_000L), curve = true).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING,
            assess(safety, BuyRouteSafety8522.jupiter(quote("some-amm")), curve = true).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING,
            assess(safety, BuyRouteSafety8522.pumpCurve(mint, "curve", now - 1_000L), curve = false).status)
    }

    @Test fun allLiveBuyBuildersUseTheFinalGuardAndPreserveRefusals() {
        val file = listOf(File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt"),
            File("app/src/main/kotlin/com/lifecyclebot/engine/Executor.kt")).first { it.exists() }
        val source = file.readText()
        val jupiter = source.substringAfter("private fun buySend8085(").substringBefore("\n    private fun ")
        assertTrue(jupiter.indexOf("BuyRouteSafety8522.requireAllowed") < jupiter.indexOf("wallet.signSendAndConfirm"))
        assertTrue(source.contains("buySend8085(wallet, ts, txResult")) // same wrapper on top-ups
        val pump = source.substringAfter("private fun tryPumpPortalBuy(")
        assertTrue(pump.contains("pool8522        = \"pump\""))
        assertTrue(pump.indexOf("BuyRouteSafety8522.requireAllowed") < pump.indexOf("wallet.signSendAndConfirm"))
        val ray = source.substringAfter("private fun tryRaydiumBuy7325(").substringBefore("private fun tryPumpPortalBuy(")
        assertTrue(ray.contains("BuyRouteSafety8522.requireAllowed(ts, route8522, \"RAYDIUM_FINAL_SIGN\")"))
        assertTrue(ray.contains("submittedSig8397?.let { throw SolanaWallet.SentButUnconfirmed7733(it, e) }"))
    }
}
