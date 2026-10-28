package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8418 item 13 — the order built is the exact approved decision, or it is refused by name. */
class Aate8418EntryContinuityTest {
    private val now = 1_800_000_000_000L
    private val mint = "mint8418"
    private fun intent() = ExecutableOpenGate.ExecutionIntent(
        attemptId = "LIVE:QUALITY:8418", candidateId = "c8418", candidateVersion = 8418L,
        mint = mint, mode = "LIVE", canonicalLane = "QUALITY", fdgVerdict = "BUY", fdgAllowed = true,
        authorityVersion = 8418L, resolvedSize = 0.04, createdAt = now - 100L, symbol = "T",
        finalDecision6613 = ExecutableOpenGate.CanonicalFinalDecision6613.BUY, fdgDecisionId6613 = "fdg8418",
        executableMarkPriceUsd6613 = 0.001, expiresAtMs6613 = now + 15_000L,
    )
    private val sealedLamports = 40_000_000L
    private fun q(out: Long = 1_000_000L, impact: Double = 0.8, router: String = "iris", inL: Long = sealedLamports) =
        EntryContinuity8418.Quote8418(inL, out, impact, router, mint, 300)
    private fun check(
        approved: ExecutableOpenGate.ExecutionIntent? = intent(), current: ExecutableOpenGate.ExecutionIntent? = intent(),
        spent: Long = sealedLamports, px: Double = 0.00105,
        aq: EntryContinuity8418.Quote8418? = q(), eq: EntryContinuity8418.Quote8418? = q(),
    ) = EntryContinuity8418.refusal8418(approved, current, mint, spent, px, aq, eq)

    @Test fun theApprovedOrderPasses() {
        assertNull(check())
        assertNull("a cheaper fill is no mismatch", check(px = 0.0009))
        assertNull("rounding of the sealed size is absorbed", check(spent = sealedLamports - 1))
    }

    @Test fun strategyMismatchRefuses() {
        assertEquals("ENTRY_CONTINUITY_8418_NO_APPROVAL", check(approved = null))
        assertEquals("ENTRY_CONTINUITY_8418_APPROVAL_GONE", check(current = null))
        assertEquals("ENTRY_CONTINUITY_8418_STRATEGY", check(current = intent().copy(canonicalLane = "MOONSHOT")))
        assertEquals("ENTRY_CONTINUITY_8418_STRATEGY", check(current = intent().copy(candidateVersion = 8419L)))
        assertEquals("ENTRY_CONTINUITY_8418_STRATEGY", check(current = intent().copy(resolvedSize = 0.08)))
        assertEquals("ENTRY_CONTINUITY_8418_STRATEGY", check(approved = intent().copy(finalDecision6613 = ExecutableOpenGate.CanonicalFinalDecision6613.UNKNOWN)))
        assertEquals("ENTRY_CONTINUITY_8418_MINT", check(eq = q().copy(outputMint = "otherMint")))
    }

    @Test fun sizeMustBeTheSealedSize() {
        assertEquals("a planner clamp is a mismatch", "ENTRY_CONTINUITY_8418_SIZE", check(spent = 35_000_000L))
        assertEquals("ENTRY_CONTINUITY_8418_SIZE", check(spent = 48_000_000L))
        assertEquals("ENTRY_CONTINUITY_8418_SIZE", check(eq = q(inL = 30_000_000L)))
        assertTrue(EntryContinuity8418.sizeMatches8418(0.04, 40_000_001L))
        assertTrue(!EntryContinuity8418.sizeMatches8418(0.04, 0L))
    }

    @Test fun aRefreshedQuoteKeepsTheApprovedRouteOutAndImpact() {
        assertEquals("ENTRY_CONTINUITY_8418_ROUTE", check(eq = q(router = "jupiterz")))
        assertEquals("ENTRY_CONTINUITY_8418_OUT_AMOUNT", check(eq = q(out = 960_000L)))
        assertNull("inside the approved 3% slippage", check(eq = q(out = 975_000L)))
        assertEquals("ENTRY_CONTINUITY_8418_IMPACT", check(eq = q(impact = 2.5)))
    }

    @Test fun priceStaysInsideTheChaseBound() {
        assertEquals("ENTRY_CONTINUITY_8418_PRICE", check(px = 0.00116))
        assertNull(check(px = 0.00114))
        assertEquals("ENTRY_CONTINUITY_8418_PRICE_UNAPPROVED",
            check(approved = intent().copy(executableMarkPriceUsd6613 = 0.0), current = intent().copy(executableMarkPriceUsd6613 = 0.0)))
    }

    @Test fun bothBuyRoutesAreWired() {
        val ex = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val recheck = ex.indexOf("SizingQuoteRecheck8153.preBroadcastQuoteRefusal8167(ts, sol, executionQuote7688, intent8198 = intent8198)")
        val cont = ex.indexOf("EntryContinuity8418.jupiterRefusal8418(ts, intent8198, jq7325, executionQuote7688, buyBaseSlippage)")
        val broadcast = ex.indexOf("return JupiterBuyPreparation8090(quote, txResult, raydiumBuy7325, useJito, jitoTip)")
        assertTrue(recheck in 1 until cont && cont < broadcast)
        val pump = ex.indexOf("EntryContinuity8418.pumpRefusal8418(ts, submission8397.attemptId, buySol)")
        val pumpBuild = ex.indexOf("com.lifecyclebot.network.PumpFunDirectApi.buildBuyTx(", pump)
        assertTrue(pump > 0 && pumpBuild > pump)
        assertTrue("approved vs executed ride the entry snapshot onto the position",
            ex.contains("authorizationReason = \"FDG:${'$'}{intent.fdgVerdict};attempt=${'$'}{intent.attemptId}\" + EntryContinuity8418.summary8418(intent.attemptId)"))
        assertEquals("", EntryContinuity8418.summary8418("never-built-8418"))
    }
}
