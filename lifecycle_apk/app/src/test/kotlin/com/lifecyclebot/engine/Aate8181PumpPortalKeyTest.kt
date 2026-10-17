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
