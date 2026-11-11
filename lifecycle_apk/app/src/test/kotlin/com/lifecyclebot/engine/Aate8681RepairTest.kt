package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LabelEvidence8386
import com.lifecyclebot.engine.truth.LabelMark8386
import com.lifecyclebot.network.HeliusWebSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8681 — repairs from the 5.0.8680 live capture (98 min, 18 closes, PF 0.64):
 *  1. catastrophe stops filled at -3.8 .. -5.9 % (the venue leg could not refute an unnamed-depth stop on a red read);
 *  2. a chain-feed NAME outranked pool identity in the stop basis test;
 *  3. a re-entry ticket measured from a pre-loss decision cleared the 24 h big-loser lock (bro: -7.9 % then -4 %);
 *  4. <= 0.1x labels were booked across venues with no chain proof;
 *  5. the Helius subscription cap read uncollected garbage as heap pressure and sat at 8 all session.
 */
class Aate8681RepairTest {
    private val poolA = "FEDnfEgLT8ZfmGH4wkWZeNB8BVBC8a2UiK3kVNgV3mXc"
    private val poolB = "3ancQhxTdhz8kJwA9FcrSC5F8fRAcvTyLnG34185bERJ"

    @Test fun catastropheStopsCarryTheirImpliedDepthToTheVenueCheck() {
        assertEquals(-15.0, StopMarkConfirm8418.impliedStopDepthPct8681("RAPID_CATASTROPHE_STOP")!!, 0.0)
        assertEquals(-30.0, StopMarkConfirm8418.impliedStopDepthPct8681("DEEP_CATASTROPHE_NET")!!, 0.0)
        assertEquals(-15.0, StopMarkConfirm8418.impliedStopDepthPct8681("RAPID_SUB_TRADER_HARD_FLOOR")!!, 0.0)
        assertNull(StopMarkConfirm8418.impliedStopDepthPct8681("RAPID_FLUID_STOP"))
        // 8680: the pool read the bag at about -4 % while the mark claimed a catastrophe -> refuted (hold, re-ask)
        assertTrue(StopMarkConfirm8418.venueRefutes8489(-4.0, StopMarkConfirm8418.impliedStopDepthPct8681("RAPID_CATASTROPHE_STOP")))
        // a real collapse is confirmed by the venue and still sells
        assertFalse(StopMarkConfirm8418.venueRefutes8489(-40.0, StopMarkConfirm8418.impliedStopDepthPct8681("RAPID_CATASTROPHE_STOP")))
        assertFalse(StopMarkConfirm8418.venueRefutes8489(-75.0, StopMarkConfirm8418.impliedStopDepthPct8681("DEEP_CATASTROPHE_NET")))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/StopMarkConfirm8418.kt").readText()
        assertTrue(src.contains("val depth = stopDepthPct8489(reason) ?: impliedStopDepthPct8681(reason)"))
    }

    @Test fun poolIdentityOutranksAChainFeedName() {
        assertTrue(StopMarkConfirm8418.poolsDisagree8681(poolA, poolB))
        assertFalse(StopMarkConfirm8418.poolsDisagree8681(poolA, poolA))
        assertFalse("an unread pool proves nothing", StopMarkConfirm8418.poolsDisagree8681("", poolA))
        assertFalse("a mint-route position has no pool", StopMarkConfirm8418.poolsDisagree8681(poolA, "MINT_ROUTE:$poolB"))
        assertFalse(StopMarkConfirm8418.onBasis8418("PUMPSWAP_VAULT_WS_8018", poolB, poolA))
        assertFalse(StopMarkConfirm8418.onBasis8418("JUPITER_QUOTE", poolB, poolA))
        assertTrue(StopMarkConfirm8418.onBasis8418("PUMPSWAP_VAULT_WS_8018", poolA, poolA))
        assertTrue(StopMarkConfirm8418.onBasis8418("PUMP_PORTAL_TRADE_WS_7278", "", ""))
        assertTrue(StopMarkConfirm8418.offBasis8419(false, "PUMP_PORTAL_TRADE_WS_7278", poolB, poolA))
        assertFalse(StopMarkConfirm8418.offBasis8419(true, "PUMP_PORTAL_TRADE_WS_7278", "", "curveX"))
    }

    @Test fun aReentryTicketClearsOnlyALockItsRunPostdates() {
        val close = 1_800_000_000_000L
        assertFalse("run measured from before the losing close", RunnerPlay8018.ticketPostdates8681(close - 60 * 60_000L, close))
        assertTrue(RunnerPlay8018.ticketPostdates8681(close + 60_000L, close))
        assertFalse("no ticket", RunnerPlay8018.ticketPostdates8681(null, close))
        assertFalse("no lock time", RunnerPlay8018.ticketPostdates8681(close, null))
    }

    @Test fun extremeDownsideAcrossVenuesNeedsAChainRead() {
        fun m(venue: String, px: Double, at: Long, source: String = "DEXSCREENER_BATCH") =
            LabelMark8386("m1", venue, px, at, at + 100, source, decimals = 6)
        assertEquals("OBSERVED_ONLY_VENUE_MISMATCH_EXTREME",
            LabelEvidence8386.status(m("pump.curve", 1.0, 1_000), m("pairB", 0.02, 60_000)))
        assertEquals("VALID_8386", LabelEvidence8386.status(m("pairB", 1.0, 1_000), m("pairB", 0.02, 60_000)))
        assertEquals("VALID_8386", LabelEvidence8386.status(m("pump.curve", 1.0, 1_000), m("pairB", 0.02, 60_000, "CURVE_ACCOUNT")))
        assertEquals("unnamed venues are not a disagreement", "VALID_8386",
            LabelEvidence8386.status(m("", 1.0, 1_000), m("pairB", 0.02, 60_000)))
        assertTrue(LabelEvidence8386.venuesDisagree8681("pump.curve", "chain.pool"))
        assertFalse(LabelEvidence8386.venuesDisagree8681("Chain.Pool", "chain.pool"))
    }

    @Test fun heliusCapReadsTheLiveSetNotTheGarbage() {
        val ws = HeliusWebSocket("", { _, _, _, _, _, _ -> }, { _, _, _, _ -> }, {})
        assertEquals(0.62, ws.liveHeapFraction8681(0.62, 0.95), 0.0)
        assertEquals(0.80, ws.liveHeapFraction8681(1.0, 0.80), 0.0)
        assertEquals(12, ws.capForLoad8666(12, 6_100_000L, ws.liveHeapFraction8681(0.62, 0.85), true))
        assertEquals(8, ws.capForLoad8666(12, 6_100_000L, ws.liveHeapFraction8681(0.78, 0.95), true))
    }
}
