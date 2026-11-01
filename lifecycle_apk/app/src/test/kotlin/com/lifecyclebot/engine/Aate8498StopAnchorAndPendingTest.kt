package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate8498StopAnchorAndPendingTest {
    @Test fun everyStopIsAnchoredAtOneAndAHalfRoundTripsEvenWithoutCandles() {
        assertEquals(6.75, ExitDoctrine8169.floorStopMag8169(4.0, Double.NaN, Double.NaN, 4.5), 1e-9)
        assertEquals(10.0, ExitDoctrine8169.floorStopMag8169(5.0, 10.0, Double.NaN, 4.5), 1e-9)
        assertEquals(16.0, ExitDoctrine8169.floorStopMag8169(15.0, Double.NaN, 6.0, 4.0), 1e-9)
        assertEquals(25.0, ExitDoctrine8169.floorStopMag8169(25.0, 5.0, 1.0, 2.0), 1e-9)
    }

    @Test fun compactVoterNumbersKeepSixSignificantDigits() {
        val v = 0.123456789012
        val c = Cortex7885.compactNumber8498(v) as Double
        assertEquals(0.123457, c, 1e-12)
        assertTrue(c.toString().length < v.toString().length)
        assertEquals(-1234.57, Cortex7885.compactNumber8498(-1234.5678) as Double, 1e-9)
        assertEquals(0.0, Cortex7885.compactNumber8498(0.0) as Double, 0.0)
        assertEquals(org.json.JSONObject.NULL, Cortex7885.compactNumber8498(Double.NaN))
    }
}

class Aate8498ExecutionTruthTest {
    @Test fun aBusyBuyLeaseHoldsTheTicketInsteadOfDroppingIt() {
        assertEquals("BUY_LEASE_DEFERRED_8496",
            TicketRetry8327.transientCause8327("LIVE_BUY_NOT_OPENED_7948:BUY_LEASE_DEFERRED_8496"))
        assertEquals(null, TicketRetry8327.transientCause8327("LIVE_BUY_NOT_OPENED_7948:MINT_ALREADY_HELD_8489"))
    }

    @Test fun aRetryOnTheSameMintIsNotTimedAsAStepOfTheEarlierBuy() {
        val t = com.lifecyclebot.engine.truth.BuyStageTiming
        val mint = "Mint8498Gap"
        t.onPhase(mint, "BUY_QUOTE_TRY", 1_000L); t.onPhase(mint, "BUY_QUOTE_OK", 2_000L); t.onPhase(mint, "BUY_SIM_OK", 2_500L)
        t.onPhase(mint, "BUY_QUOTE_TRY", 112_500L)
        assertTrue(!t.statusLine().contains("SIM_OK->QUOTE_TRY"))
        assertTrue(!t.slowestStep().startsWith(com.lifecyclebot.engine.truth.BuyStageTiming.GAP_KEY_8498))
    }

    @Test fun ultraMaybeDeliveredGoesToConfirmationNotFailure() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        val i = src.indexOf("❌ Fallback also failed")
        val j = src.indexOf("ultra_execute_unconfirmed_8498")
        val k = src.indexOf("throw lastException ?: fallbackEx")
        assertTrue(i in 0 until j && j < k)
    }
}
