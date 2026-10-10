package com.lifecyclebot.engine

import com.lifecyclebot.network.HeliusSubscriptionTelemetry7807
import com.lifecyclebot.network.HeliusWebSocket
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.7807 — Helius / memory / subscription lifecycle. Runtime 5.0.7792/7806 OOM'd in
 * HeliusWebSocket.subscribeToken <- DataOrchestrator.onTokenAdded.
 */
class Aate7807HeliusLifecycleTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    private fun newWs(): HeliusWebSocket = HeliusWebSocket(
        apiKey = "",
        onSwap = { _, _, _, _, _, _ -> },
        onLargeWalletMove = { _, _, _, _ -> },
        onLog = { },
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(ws: HeliusWebSocket, name: String): T =
        HeliusWebSocket::class.java.getDeclaredField(name).apply { isAccessible = true }.get(ws) as T

    private fun desired(ws: HeliusWebSocket): Map<String, Int?> = field(ws, "subscriptions")
    private fun requests(ws: HeliusWebSocket): MutableMap<Int, String> = field(ws, "requestToMint7765")
    private fun serverSubs(ws: HeliusWebSocket): MutableMap<Int, String> = field(ws, "subscriptionToMint7765")
    private fun sentAt(ws: HeliusWebSocket): MutableMap<Int, Long> = field(ws, "requestSentAtMs7807")

    private fun parse(ws: HeliusWebSocket, json: String) {
        HeliusWebSocket::class.java.getDeclaredMethod("parseMessage", String::class.java)
            .apply { isAccessible = true }.invoke(ws, json)
    }

    @Test fun capStaysAt64AndHeldPositionIsNeverTheEvictionVictim() {
        val ws = newWs()
        ws.setPinnedMintsProvider7807 { setOf("HELD_MINT") }
        ws.subscribeToken("HELD_MINT")
        for (i in 0 until 400) ws.subscribeToken("DISCOVERY_$i")
        val d = desired(ws)
        assertEquals(64, d.size)
        assertTrue("held position must keep its subscription", d.containsKey("HELD_MINT"))
        assertTrue(d.containsKey("DISCOVERY_399"))
        assertFalse(d.containsKey("DISCOVERY_0"))
        assertTrue(HeliusSubscriptionTelemetry7807.evictions.get() > 0)
    }

    @Test fun evictedHeldMintIsReArmedByHousekeeping() {
        val ws = newWs()
        val held = mutableSetOf<String>()
        ws.setPinnedMintsProvider7807 { held.toSet() }
        ws.subscribeToken("LATE_BUY")
        for (i in 0 until 200) ws.subscribeToken("SCAN_$i")
        assertFalse(desired(ws).containsKey("LATE_BUY"))
        held.add("LATE_BUY") // bought after its discovery slot rolled off
        ws.ensurePinnedSubscriptions7807()
        assertTrue(desired(ws).containsKey("LATE_BUY"))
        assertEquals(64, desired(ws).size)
    }

    @Test fun lateAckForEvictedMintIsUnsubscribedNotResurrected() {
        val ws = newWs()
        val before = HeliusSubscriptionTelemetry7807.lateAckUnsubscribes.get()
        requests(ws)[9001] = "EVICTED"
        parse(ws, """{"jsonrpc":"2.0","id":9001,"result":77}""")
        assertTrue(requests(ws).isEmpty())
        assertFalse(serverSubs(ws).containsKey(77))
        assertFalse(desired(ws).containsKey("EVICTED"))
        assertEquals(before + 1, HeliusSubscriptionTelemetry7807.lateAckUnsubscribes.get())
    }

    @Test fun duplicateAckForSameMintIsUnsubscribed() {
        val ws = newWs()
        ws.subscribeToken("DUP")
        requests(ws)[9002] = "DUP"
        parse(ws, """{"jsonrpc":"2.0","id":9002,"result":11}""")
        assertEquals(11, desired(ws)["DUP"])
        assertEquals("DUP", serverSubs(ws)[11])
        val before = HeliusSubscriptionTelemetry7807.lateAckUnsubscribes.get()
        requests(ws)[9003] = "DUP"
        parse(ws, """{"jsonrpc":"2.0","id":9003,"result":12}""")
        assertNull(serverSubs(ws)[12])
        assertEquals(11, desired(ws)["DUP"])
        assertEquals(before + 1, HeliusSubscriptionTelemetry7807.lateAckUnsubscribes.get())
    }

    @Test fun errorReplyReleasesRequestAndDeadSlot() {
        val ws = newWs()
        ws.subscribeToken("REFUSED")
        requests(ws)[9004] = "REFUSED"
        parse(ws, """{"jsonrpc":"2.0","id":9004,"error":{"code":-32602,"message":"bad"}}""")
        assertTrue(requests(ws).isEmpty())
        assertFalse(desired(ws).containsKey("REFUSED"))
    }

    @Test fun pendingRequestWithoutAckExpires() {
        val ws = newWs()
        ws.subscribeToken("SILENT")
        requests(ws)[9005] = "SILENT"
        sentAt(ws)[9005] = System.currentTimeMillis() - 120_000L
        HeliusWebSocket::class.java.getDeclaredMethod("expireStaleRequests7807", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(ws, System.currentTimeMillis())
        assertTrue(requests(ws).isEmpty())
        assertTrue(sentAt(ws).isEmpty())
        assertFalse(desired(ws).containsKey("SILENT"))
    }

    @Test fun reconnectRegistersOncePerMemberAndOrphansAreUnsubscribed() {
        val h = src("network/HeliusWebSocket.kt")
        assertTrue(h.contains("MAX_TOKEN_SUBSCRIPTIONS_7794 = 64"))
        val open = h.substringAfter("override fun onOpen").substringBefore("override fun onMessage")
        assertTrue(open.contains("if (!requestToMint7765.containsValue(mint))"))
        assertFalse("onOpen must not wipe requests queued on the new socket", open.contains("requestToMint7765.clear()"))
        assertTrue(h.contains("unsubscribeOrphan7807(\"logsUnsubscribe\""))
        assertTrue(h.contains("if (current != null && current !== webSocket) return"))
        assertFalse(h.contains("fun unsubscribeToken("))
    }

    @Test fun oneSubscriptionAuthorityAndBoundedOrchestratorMaps() {
        val o = src("engine/DataOrchestrator.kt")
        assertFalse(o.contains("fun onTokenRemoved("))
        assertTrue(o.contains("heliusWs?.setPinnedMintsProvider7807 { heldMintsForSubscriptions7807() }"))
        assertTrue(o.contains("protectiveInventoryMints7807()"))
        assertTrue(o.contains("startSubscriptionHousekeeping7807()"))
        assertTrue(o.contains("lastWsEventMs.entries.removeIf"))
        assertTrue(o.contains("lastPumpPortalTradeMs7773.entries.removeIf"))
        assertTrue(o.contains("onHeldTradeMark7787?.invoke(mint, safeSol / tokenAmt)"))
    }

    @Test fun cardinalityTelemetryIsInTheHealthDump() {
        val p = src("engine/PipelineHealthCollector.kt")
        assertTrue(p.contains("HeliusSubscriptionTelemetry7807.line7807()"))
        val line = HeliusSubscriptionTelemetry7807.line7807()
        listOf("desiredTokenSubs=", "serverTokenSubs=", "requestMapSize=", "walletSubs=", "evictions=",
            "lateAckUnsubscribes=", "reconnectResubscribes=").forEach { assertTrue(it, line.contains(it)) }
    }
}
