package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7976OwnerHoldingsCryptoTest {

    @Test fun ownerManualBuysAreNotBotInventory() {
        val botSigs = setOf("botSig1")
        assertTrue(OwnerManualHoldings7976.ownerManual7976(false, "ownerSig", botSigs, false))   // TNSR bought by hand
        assertFalse(OwnerManualHoldings7976.ownerManual7976(true, "ownerSig", botSigs, false))   // a BOT_BUY row is the bot's
        assertFalse(OwnerManualHoldings7976.ownerManual7976(false, "botSig1", botSigs, false))   // a parsed bot buy
        assertFalse(OwnerManualHoldings7976.ownerManual7976(false, "ownerSig", botSigs, true))   // the bot holds a lot for it
        assertFalse(OwnerManualHoldings7976.ownerManual7976(false, null, botSigs, false))        // no evidence: stays fail-closed
    }

    @Test fun cryptoGateReservesASlotInsteadOfFreezing() {
        val src = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(src.contains("CRYPTO_LIVE_UNRESOLVED_HOLDING_SLOT_RESERVED_7976"))
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/sell/LiveBuyAdmissionGate.kt").readText()
        assertTrue(gate.contains("OwnerManualHoldings7976.isOwnerManual7976(p.mint)"))
    }
}
