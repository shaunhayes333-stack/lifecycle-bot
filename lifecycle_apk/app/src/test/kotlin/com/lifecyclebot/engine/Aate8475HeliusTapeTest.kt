package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLabelFeed8291
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.HeldHotMarkAuthority7419
import com.lifecyclebot.network.HeliusCurveTape8354
import com.lifecyclebot.network.HeliusCurveTape8354.Source8354
import com.lifecyclebot.network.HeliusCurveTape8354.Transport8354
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8475 — owner: "lean on the data we are already getting from helius and not using". 8471 (97 min): the Helius program
 * curve tape sat at transport=NONE (level 1 wanted the shared socket, which was not attached; nothing stepped it back up)
 * while enhancedWs=OK; of the prints it did decode it dropped 1,828 of 2,368 with their reserves; the labeler lost 1,519
 * marks for want of a source and priced 6 from the tape. And the one live position (Candy) was marked by a Jupiter dust-route
 * quote (1.258e-12 USD vs a 2.0e-5 pool mark), read -100 %, was latched DUST_UNROUTABLE and froze every other buy.
 */
class Aate8475HeliusTapeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun theProgramTapeRunsBesidePumpPortalAndNeverSitsWithoutATransport() {
        val d = HeliusCurveTape8354
        assertEquals(Source8354.HELIUS_PROGRAM to Transport8354.ENHANCED, d.decide8354(pumpPortalOn = true, level = 0, inFallbackHold = false, affordable = true, enhancedOk = true, sharedUp = true))
        assertEquals(Source8354.HELIUS_PROGRAM to Transport8354.LOGS, d.decide8354(true, 1, false, true, enhancedOk = true, sharedUp = true))
        assertEquals("logs wanted, no socket: enhanced carries it", Source8354.HELIUS_PROGRAM to Transport8354.ENHANCED, d.decide8354(false, 1, false, true, enhancedOk = true, sharedUp = false))
        assertEquals("the budget still binds", Source8354.HELIUS_PER_MINT to Transport8354.NONE, d.decide8354(true, 0, false, affordable = false, enhancedOk = true, sharedUp = true))
        assertEquals("a fallback hold still binds", Source8354.HELIUS_PER_MINT to Transport8354.NONE, d.decide8354(true, 0, inFallbackHold = true, affordable = true, enhancedOk = true, sharedUp = true))
        val tape = src("network/HeliusCurveTape8354.kt")
        val ev = tape.substringAfter("fun onTradeEvent8354(").substringBefore("internal fun tradesInLogs8354(")
        assertTrue("reserves for every decoded trade, before the care filter",
            ev.indexOf("CurveReserves7951.noteFrame7951(mint, ev.virtualSol, ev.virtualTokens)") in 0 until ev.indexOf("if (!cares8354(mint, nowMs))"))
        assertTrue(tape.contains("ForwardReturnLabeler7731.isPendingMint8475(mint, nowMs)"))
    }

    @Test fun aPendingLabelKeepsItsCoinOnTheTapeAndTheTapePricesLabels() {
        val mint = "Pending8475AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        assertFalse(ForwardReturnLabeler7731.isPendingMint8475(mint, 1_000L))
        assertEquals("CURVE", CortexLabelFeed8291.markFamily8418("CURVE_ACCOUNT"))
        assertEquals("TAPE", CortexLabelFeed8291.markFamily8418("CURVE_FRAME"))
        assertEquals("TAPE", CortexLabelFeed8291.markFamily8418(ForwardReturnLabeler7731.TAPE_MARK_SOURCE_8475))
        assertEquals("DEX", CortexLabelFeed8291.markFamily8418("DEXSCREENER"))
        val frl = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(frl.contains("notePendingMint8475(ts.mint, nowMs)\n        pending[key] = Obs("))
        val mark = frl.substringAfter("internal fun typedMark8386(").substringBefore("private fun markFor(")
        assertTrue(mark.contains("TradeTape8100.lastPrint8360(mint)"))
        assertTrue(mark.contains("TAPE_MARK_SOURCE_8475, independentBasis = true"))
    }

    @Test fun aDustRouteQuoteIsNotAMark() {
        // Candy: Jupiter 1.258e-12 against a reserve / pool price of 2.0e-5
        assertTrue(HeldHotMarkAuthority7419.dustRouteQuote8475(1.258e-12, 2.0e-5, HeldHotMarkAuthority7419.DUST_ROUTE_VS_RESERVE_8475))
        assertTrue(HeldHotMarkAuthority7419.dustRouteQuote8475(1.258e-12, 2.0e-5, HeldHotMarkAuthority7419.DUST_ROUTE_VS_MARK_8475))
        assertFalse("a real quote a little under the reference is a price", HeldHotMarkAuthority7419.dustRouteQuote8475(1.7e-5, 2.0e-5, 10.0))
        assertFalse("at the boundary (with float slack) it is a price", HeldHotMarkAuthority7419.dustRouteQuote8475(1.1e-6, 2.0e-5, 20.0))
        assertTrue(HeldHotMarkAuthority7419.dustRouteQuote8475(0.9e-6, 2.0e-5, 20.0))
        assertFalse("no reference, no verdict", HeldHotMarkAuthority7419.dustRouteQuote8475(1.0e-12, Double.NaN, 10.0))
        assertEquals(60_000L, HeldHotMarkAuthority7419.DUST_ROUTE_MARK_MAX_AGE_MS_8475)
        val q = src("engine/truth/HeldHotMarkAuthority7419.kt").substringAfter("internal fun quoteSellEstimate(").substringBefore("fun dustRouteQuote8475(")
        assertTrue("the reserve estimate replaces a dust route", q.contains("if (reserve8475 != null && dustRouteQuote8475(px, reserve8475.priceUsd, DUST_ROUTE_VS_RESERVE_8475))"))
        assertTrue("with no reserve read, a fresh venue mark refuses it", q.contains("miss8435(mint, \"JUP_DUST_ROUTE\")"))
        assertTrue(q.contains("LabelEvidence8386.independentSource8418(risk8475.source)"))
        assertTrue(HeldHotMarkAuthority7419.dustRouteLine8475().startsWith("dustRoute8475["))
        // the dust test in the sell path is refuted by the venue's own reserves
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("val dustRefuted8475 = isDust && isDeepLoss && reserveProceeds8475 != null && reserveProceeds8475 >= 0.001"))
        assertTrue(ex.contains("if (isDust && isDeepLoss && !dustRefuted8475) {"))
    }

    @Test fun aFailedBuyReleasesTheCoinToTheNextLane() {
        val ex = src("engine/Executor.kt").substringAfter("fun buyTerminalFail(reason: String) {").substringBefore("fun buyTerminalOk(")
        assertTrue(ex.contains("ExecutableOpenGate.releaseFailedBuy8475(failed8475, ts.mint, reason)"))
        val gate = src("engine/ExecutableOpenGate.kt").substringAfter("fun releaseFailedBuy8475(").substringBefore("fun releaseAttemptNonTerminal6514(")
        assertTrue("claim, intent, ticket and index go with the named attempt only", gate.contains("revokeAttempt6514(attemptId, mint, \"\")"))
        assertTrue(gate.contains("if (attemptId.isBlank()) return"))
    }
}
