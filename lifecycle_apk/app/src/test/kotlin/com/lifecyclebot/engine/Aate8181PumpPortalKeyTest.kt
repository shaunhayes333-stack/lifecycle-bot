package com.lifecyclebot.engine

import com.lifecyclebot.network.PumpFunWS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8181 — the PumpPortal data key reaches the socket and a funded wallet is re-checked on a fresh connection. */
class Aate8181PumpPortalKeyTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun pumpSwapBalanceRefusalIsTheSameFundingDenial() {
        assertTrue(PumpFunWS.isTradeAuthRefusal7432("{\"errors\":\"Minimum balance not met for PumpSwap websocket data.\"}"))
        assertTrue(PumpFunWS.isTradeAuthRefusal7432(
            "'subscribeTokenTrade' and 'subscribeAccountTrade' methods are only available when connecting with an API key funded with at least 0.02 SOL."))
        assertFalse(PumpFunWS.isTradeAuthRefusal7432("Successfully subscribed to keys."))
    }

    @Test fun settingsKeyReplacesTheConnectedKeyButBlankNeverErasesIt() {
        assertTrue(PumpFunWS.keyChanged8181("", "abcd1234"))
        assertTrue(PumpFunWS.keyChanged8181("old0000", "new1111"))
        assertFalse(PumpFunWS.keyChanged8181("same9999", "same9999"))
        assertFalse(PumpFunWS.keyChanged8181("working5555", ""))
    }

    @Test fun keyStateNamesTheLinkedWalletWithoutPrintingTheKey() {
        val denied = PumpFunWS.keyState8181("secretkeyWXYZ", denied = true, connectedKeyed = true)
        assertTrue(denied.contains("..WXYZ"))
        assertFalse(denied.contains("secretkey"))
        assertTrue(denied.contains("LINKED"))
        assertTrue(PumpFunWS.keyState8181("", denied = false, connectedKeyed = false).startsWith("NONE"))
    }

    @Test fun everyReprobeReconnectsAndTheKeyIsReReadFromSettings() {
        val ws = src("network/PumpFunWS.kt")
        val reprobe = ws.substringAfter("private fun maybeReprobeAuth8094(").substringBefore("// ── V5.0.8181 — the key is re-read")
        assertTrue(reprobe.contains("refreshKey8181(nowMs)"))
        assertTrue(reprobe.contains("try { ws?.close(1000, \"reprobe keyed 8181\") }"))
        assertFalse(reprobe.contains("if (!connectedKeyed8094) try { ws?.close"))
        assertTrue(src("engine/BotService.kt").contains("PumpFunWS.setKeySupplier8181 { com.lifecyclebot.data.ConfigStore.load(applicationContext).pumpPortalApiKey }"))
        assertTrue(src("ui/MainActivity.kt").contains("DataKeysCard7962.pumpPortal(this)?.takeIf { it.isNotBlank() }"))
        assertTrue(src("ui/DataKeysCard7962.kt").contains("val pp = pumpPortal(a)?.takeIf { it.isNotBlank() } ?: return"))
    }
}

/** V5.0.8182 — no 10-minute false lockout, a changed key is never ignored, one connection, and trade frames prove the stream. */
class Aate8182PumpPortalStreamTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun refusalIsRecheckedSoonThenBacksOffToTenMinutes() {
        assertTrue(PumpFunWS.reprobeDelayMs8182(1L) == 60_000L)
        assertTrue(PumpFunWS.reprobeDelayMs8182(2L) == 120_000L)
        assertTrue(PumpFunWS.reprobeDelayMs8182(4L) == 480_000L)
        assertTrue(PumpFunWS.reprobeDelayMs8182(5L) == PumpFunWS.AUTH_REPROBE_MS_8094)
        assertTrue(PumpFunWS.reprobeDelayMs8182(50L) == PumpFunWS.AUTH_REPROBE_MS_8094)
        assertTrue(PumpFunWS.authReprobeDue8094(denied = true, deniedAtMs = 1_000L, nowMs = 61_000L, delayMs = PumpFunWS.reprobeDelayMs8182(1L)))
    }

    @Test fun oneConnectionAndAChangedKeyReconnects() {
        val ws = src("network/PumpFunWS.kt")
        assertTrue(ws.contains("ws?.let { old -> ws = null; try { old.close(1000, \"replaced 8182\") }"))
        assertTrue(ws.contains("if (running.get() && current8182) scheduleReconnect()"))
        assertTrue(ws.contains("if (current8182) scheduleReconnect()"))
        val start = ws.substringAfter("fun start(").substringBefore("onNewTokenCb = onNewToken")
        assertTrue(start.contains("if (keyChanged8181(this.apiKey7284, apiKey7284.trim()))"))
        assertTrue(start.contains("ws?.close(1000, \"key changed 8182\")"))
        assertTrue(ws.contains("tradeConfirmed8182="))
        assertTrue(ws.contains("authDenials8182.set(0L)"))
    }
}
