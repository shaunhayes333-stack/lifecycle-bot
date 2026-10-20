package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.SellRouteFinality8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.Balance8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.ProviderRead8144
import com.lifecyclebot.engine.sell.SellRouteFinality8144.Read8144
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.8250 — DqPWFV (5.0.8246): a CATASTROPHE latch re-dispatched eight times against a wallet two providers called
 * absent, because the partial (Token-2022-failed) snapshot kept the 7733 complete-absence zero read from ever firing.
 */
class Aate8250AbsentZeroReadTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun twoProvidersAbsentIsZeroFinalityOneIsNot() {
        val two = SellRouteFinality8144.classifyBalance8144(listOf(ProviderRead8144("a", Read8144.ABSENT), ProviderRead8144("b", Read8144.ABSENT)))
        assertEquals(Balance8144.CONFIRMED_ABSENT, two.verdict)
        assertTrue(SellRouteFinality8144.zeroFinalityAllowed8144(two))
        val one = SellRouteFinality8144.classifyBalance8144(listOf(ProviderRead8144("a", Read8144.ABSENT), ProviderRead8144("b", Read8144.FAILED)))
        assertEquals(Balance8144.UNCORROBORATED_ABSENT, one.verdict)
        assertFalse(SellRouteFinality8144.zeroFinalityAllowed8144(one))
        // the 48,664,757,393 raw the proof row showed, from one provider, is HELD — the opposite proof
        val held = SellRouteFinality8144.classifyBalance8144(listOf(
            ProviderRead8144("a", Read8144.HELD, BigInteger("48664757393"), 5), ProviderRead8144("b", Read8144.ABSENT)))
        assertEquals(Balance8144.HELD, held.verdict)
    }

    @Test fun pollerUnknownConsultsTheMultiProviderAbsenceAfterTheCompleteSnapshotPath() {
        val poller = src("engine/sell/BalanceProofPoller.kt")
        val unknown = poller.substringAfter("is SellAmountAuthority.Resolution.Unknown -> {").substringBefore("}")
        val complete = unknown.indexOf("absentFromCompleteIsZeroRead7733(entry)")
        val multi = unknown.indexOf("multiProviderAbsentIsZeroRead8250(entry)")
        val poll = unknown.indexOf("BalanceProofWaitState.scheduleNextPoll(entry.mint)")
        assertTrue(complete in 0 until multi)
        assertTrue(multi < poll)
        assertTrue(unknown.contains("onZeroRead7733(entry, \"MULTI_PROVIDER_CONFIRMED_ABSENT_8250\")"))
        val fn = poller.substringAfter("private fun multiProviderAbsentIsZeroRead8250(").substringBefore("private fun heldRefutesAbsence8144(")
        // one zero READ, not finality: the streak, the frozen guards and zeroCorroborated8144 stay between it and a close
        assertTrue(fn.contains("SellRouteProbe8144.balance8144(wallet, entry.mint)"))
        assertTrue(fn.contains("if (SellRouteProbe8144.held8144(v)) {"))
        assertTrue(fn.contains("if (!SellRouteFinality8144.zeroFinalityAllowed8144(v)) return false"))
        assertTrue(fn.contains("it.side == \"SELL\" && it.unresolved"))
        assertTrue(fn.contains("if (freshBuyGrace7733(entry)) return false"))
        assertFalse(fn.contains("ZERO_BALANCE_CONFIRMED"))
        assertFalse(fn.contains("onZeroConfirmed"))
        assertTrue(poller.contains("if (streak >= 2) {"))
        assertTrue(poller.contains("if (!zeroCorroborated8144(entry)) return"))
    }
}
