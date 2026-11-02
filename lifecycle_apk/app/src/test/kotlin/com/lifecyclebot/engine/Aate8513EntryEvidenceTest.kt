package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

/** Shared SOLANA entry authority only. These fixtures are not synthetic chain receipts. */
class Aate8513EntryEvidenceTest {
    private val lanes = listOf("MEME", "SHITCOIN", "MOONSHOT", "EXPRESS", "QUALITY", "TREASURY", "CASHGEN", "BLUECHIP", "MANIPULATED", "DIP_HUNTER", "PROJECT_SNIPER", "CYCLIC", "CORE")
    private fun intent(lane: String) = ExecutableOpenGate.ExecutionIntent(
        attemptId = "LIVE:$lane:8513", candidateId = "c8513", candidateVersion = 8513L,
        mint = "mint8513", mode = "LIVE", canonicalLane = lane, fdgVerdict = "BUY", fdgAllowed = true,
        authorityVersion = 8513L, resolvedSize = 0.04, createdAt = 1_800_000_000_000L, symbol = "TEST",
        finalDecision6613 = ExecutableOpenGate.CanonicalFinalDecision6613.BUY, fdgDecisionId6613 = "fdg8513",
        executableMarkPriceUsd6613 = 1.0, expiresAtMs6613 = 1_800_000_060_000L)
    private val quote = EntryContinuity8418.Quote8418(40_000_000L, 4_000_000L, 0.5, "iris", "mint8513", 300)
    private fun check(a: ExecutableOpenGate.ExecutionIntent, c: ExecutableOpenGate.ExecutionIntent = a, price: Double = 1.0,
                      aq: EntryContinuity8418.Quote8418? = quote, eq: EntryContinuity8418.Quote8418? = quote) =
        EntryContinuity8418.refusal8418(a, c, a.mint, 40_000_000L, price, aq, eq)

    @Test fun missingOrNonPhysicalExecutionPriceIsNeverApproval() {
        for (lane in lanes) for (px in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)) {
            assertNotNull("$lane accepted unobserved execution price $px", check(intent(lane), price = px))
        }
    }
    @Test fun modeMintAndCurrentAuthorityCannotChangeAfterApproval() {
        for (lane in lanes) {
            val a = intent(lane)
            for (c in listOf(a.copy(mode="PAPER"), a.copy(mint="otherMint"), a.copy(fdgAllowed=false),
                a.copy(fdgVerdict="WAIT"), a.copy(finalDecision6613=ExecutableOpenGate.CanonicalFinalDecision6613.UNKNOWN))) {
                assertNotNull("$lane accepted altered current authority: $c", check(a,c))
            }
        }
    }
    @Test fun knownQuoteCannotDisappearOrBecomeNonExecutable() {
        for (lane in lanes) {
            val a=intent(lane)
            assertNotNull(check(a, eq=null))
            assertNotNull(check(a, eq=quote.copy(outRaw=0L)))
            assertNotNull(check(a, eq=quote.copy(outRaw=-1L)))
            assertNull("curve route has no quote pair, but retains observed price and ticket",check(a,aq=null,eq=null))
            assertNull("valid cheaper fill is still allowed",check(a,price=0.95))
        }
    }

    @Test fun missingQuoteUnitsOrRateCannotMasqueradeAsAnExecutableMark() {
        val q=SizingQuoteRecheck8153
        assertTrue(q.executionQuotePrice8513(40_000_000L, 4_000_000L, null, 100.0, 1.0).isNaN())
        assertTrue(q.executionQuotePrice8513(40_000_000L, 4_000_000L, 6, Double.NaN, 1.0).isNaN())
        assertTrue(q.executionQuotePrice8513(40_000_000L, 4_000_000L, 6, 0.0, 1.0).isNaN())
        assertTrue(q.executionQuotePrice8513(40_000_000L, 0L, 6, 100.0, 1.0).isNaN())
        assertEquals(1.0,q.executionQuotePrice8513(40_000_000L,4_000_000L,6,100.0,0.9),1e-12)
        assertEquals(1.1,q.executionQuotePrice8513(40_000_000L,4_000_000L,6,100.0,1.1),1e-12)
    }
}
