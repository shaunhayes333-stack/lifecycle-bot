package com.lifecyclebot.engine

import com.lifecyclebot.network.HeliusEnhancedWS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A dead socket with a previous subscription ID is never an active data source. */
class Aate8795HeliusSocketLivenessTest {
    @Test fun socketPresenceAloneDoesNotProveFeedAvailability() {
        assertFalse(HeliusEnhancedWS.connectedFeed8795(true, false, true))
        assertFalse(HeliusEnhancedWS.connectedFeed8795(false, true, true))
        assertFalse(HeliusEnhancedWS.connectedFeed8795(true, true, false))
        assertTrue(HeliusEnhancedWS.connectedFeed8795(true, true, true))
    }

    @Test fun disconnectedTransportDemotesAllAckStatesAndPreventsStaleSend() {
        val s = File("src/main/kotlin/com/lifecyclebot/network/HeliusEnhancedWS.kt").readText()
        val enhanced = s.substringAfter("fun enhancedOk8354()").substringBefore("fun curveSubState8354()")
        assertTrue(enhanced.contains("connectedFeed8795(running.get(), socketOpen8795, ws != null)"))
        val setCurve = s.substringAfter("fun setCurveProgram8354(").substringBefore("private fun sendCurveSubscribe8354(")
        assertTrue(setCurve.contains("ws?.takeIf { socketOpen8795 } ?: return"))
        val updateTape = s.substringAfter("fun updateTapeAccounts8307(").substringBefore("private fun sendTapeSubscribe8307(")
        assertTrue(updateTape.contains("ws?.takeIf { socketOpen8795 } ?: return"))
        assertTrue(updateTape.contains("recoverMissingSubscriptionAcks8795(nowMs)"))
        val recover = s.substringAfter("private fun recoverMissingSubscriptionAcks8795(").substringBefore("fun start(")
        assertTrue(recover.contains("curveAckOverdue8794(whaleReqId8307"))
        assertTrue(recover.contains("curveAckOverdue8794(tapeReqId8307"))
        assertTrue(recover.contains("sendSubscribe(sock, nowMs)"))
        assertTrue(recover.contains("nowMs < tapeRetryAtMs8794"))
        val ack = s.substringAfter("override fun onMessage(").substringBefore("override fun onFailure(")
        assertTrue(ack.contains("whaleReqAtMs8795 = 0L"))
        assertTrue(ack.contains("tapeReqAtMs8795 = 0L"))
        assertTrue(ack.contains("if (!socketOpen8795 || ws !== webSocket) return"))
        for (cb in listOf("override fun onFailure(", "override fun onClosed(")) {
            val body = s.substringAfter(cb).substringBefore("scheduleReconnect(")
            assertTrue(body.contains("if (ws !== webSocket) return"))
            assertTrue(body.contains("socketOpen8795 = false"))
            assertTrue(body.contains("curveSubId8354 = -1L"))
            assertTrue(body.contains("tapeSubId8307 = -1L"))
            assertTrue(body.contains("subscriptionId = -1L"))
        }
        val reconnect = s.substringAfter("private fun scheduleReconnect(")
        assertTrue(reconnect.contains("reconnectPending8795.compareAndSet(false, true)"))
        assertTrue(reconnect.contains("lifecycleEpoch8795.get() != epoch8795"))
        assertTrue(reconnect.contains("reconnectPending8795.set(false)"))
        val opened = s.substringAfter("override fun onOpen(").substringBefore("override fun onMessage(")
        assertTrue(opened.contains("if (ws !== webSocket)"))
    }
}
