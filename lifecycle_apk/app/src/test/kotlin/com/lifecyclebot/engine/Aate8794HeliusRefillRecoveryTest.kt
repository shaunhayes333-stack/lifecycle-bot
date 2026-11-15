package com.lifecyclebot.engine

import com.lifecyclebot.network.HeliusEnhancedWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Helius 429 while out of credits must not permanently kill program or tape discovery. */
class Aate8794HeliusRefillRecoveryTest {
    @Test fun quotaErrorsAreRetryableEvenWhenProviderSaysPlan() {
        assertEquals("RETRYABLE_QUOTA", HeliusEnhancedWS.capabilityFromError8307(
            429, "Current plan credits exhausted; try again after renewal"))
        assertEquals("RETRYABLE_QUOTA", HeliusEnhancedWS.capabilityFromError8307(
            -32005, "Your project has exceeded its monthly credit quota"))
        assertEquals("RETRYABLE_QUOTA", HeliusEnhancedWS.capabilityFromError8307(
            -32603, "Too many requests on this plan"))
        assertEquals("UNSUPPORTED_PLAN", HeliusEnhancedWS.capabilityFromError8307(
            -32603, "transactionSubscribe is not available on your plan"))
        assertFalse(HeliusEnhancedWS.retryableSubscriptionRefusal8794(
            -32601, "Method not found"))
    }

    @Test fun transientProgramRefusalCanExpireButPermanentPlanRefusalCannot() {
        assertFalse(HeliusEnhancedWS.retryDue8794(0L, 2_000L))
        assertFalse(HeliusEnhancedWS.retryDue8794(10_000L, 9_999L))
        assertTrue(HeliusEnhancedWS.retryDue8794(10_000L, 10_000L))
        assertFalse(HeliusEnhancedWS.curveAckOverdue8794(-1L, 1_000L, 100_000L))
        assertFalse(HeliusEnhancedWS.curveAckOverdue8794(1L, 1_000L, 90_999L))
        assertTrue(HeliusEnhancedWS.curveAckOverdue8794(1L, 1_000L, 91_000L))
        assertTrue(HeliusEnhancedWS.enhancedCanProbe8794("UNPROBED"))
        assertTrue(HeliusEnhancedWS.enhancedCanProbe8794("OK"))
        assertFalse(HeliusEnhancedWS.enhancedCanProbe8794("RETRYABLE_QUOTA"))
        assertFalse(HeliusEnhancedWS.enhancedCanProbe8794("UNSUPPORTED_PLAN"))
    }

    @Test fun stopStartAndHealthPollingRestoreTheCurveRetryPath() {
        val s = File("src/main/kotlin/com/lifecyclebot/network/HeliusEnhancedWS.kt").readText()
        val start = s.substringAfter("fun start(").substringBefore("fun whaleSubLive8361(")
        assertTrue(start.contains("curveRefused8354 = \"-\""))
        assertTrue(start.contains("curveRetryAtMs8794 = 0L"))
        assertTrue(start.contains("tapeRetryAtMs8794 = 0L"))
        val ok = s.substringAfter("fun enhancedOk8354()").substringBefore("fun curveSubState8354()")
        assertTrue(ok.contains("recoverTransientCurveRefusal8794()"))
        assertTrue(ok.contains("enhancedCanProbe8794(capability8307)"))
        val recovery = s.substringAfter("private fun recoverTransientCurveRefusal8794(")
            .substringBefore("internal fun enhancedCanProbe8794(")
        assertTrue(recovery.contains("capability8307 == \"RETRYABLE_QUOTA\""))
        assertTrue(recovery.contains("capability8307 = \"UNPROBED\""))
        assertTrue(recovery.contains("retryDue8794(tapeRetryAtMs8794, nowMs)"))
        assertTrue(recovery.contains("curveAckOverdue8794(curveReqId8354, curveReqAtMs8794, nowMs)"))
        assertTrue(s.contains("curveRetryS8794="))
        assertTrue(s.contains("tapeRetryS8794="))
        val tape = s.substringAfter("private fun sendTapeSubscribe8307(")
            .substringBefore("fun start(")
        assertTrue(tape.contains("if (nowMs < tapeRetryAtMs8794) return"))
        val refusal = s.substringAfter("if (curveReqId8354 >= 0L && msg.optLong(\"id\", -1L) == curveReqId8354)")
            .substringBefore("if (msg.optLong(\"id\", -1L) == tapeReqId8307")
        assertTrue(refusal.contains("retryableSubscriptionRefusal8794"))
        assertTrue(refusal.contains("curveRetryAtMs8794"))
        val subscribe = s.substringAfter("private fun sendCurveSubscribe8354(")
            .substringBefore("private fun sendCurveUnsubscribe8354(")
        assertTrue(subscribe.contains("if (!queued8794)"))
        assertTrue(subscribe.contains("curveReqId8354 = -1L"))
        val ack = s.substringAfter("// Subscription confirmation").substringBefore("// V5.0.8307 — a refused subscribe")
        assertTrue(ack.contains("ackId8354 == whaleReqId8307"))
        assertTrue(ack.contains("HELIUS_STALE_SUB_ACK_8794"))
        assertFalse(ack.contains("ackId8354 != tapeReqId8307 && ackId8354 != curveReqId8354"))
    }
}
