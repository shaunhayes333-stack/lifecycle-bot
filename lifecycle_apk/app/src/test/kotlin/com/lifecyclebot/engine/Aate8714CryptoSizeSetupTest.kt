package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.LaneAuthority8577
import com.lifecyclebot.engine.truth.LiveConcentrationDoctrine7697
import com.lifecyclebot.perps.CryptoAltTrader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8714 — owner: "sol majors can trade on the crypto trader again. hell if it qualifies any coin and looks forward
 * positive let it trade it. then increase the base entry size to clear the costs. cortex and setups still show signs of
 * being completely backwards pretty much everywhere."
 */
class Aate8714CryptoSizeSetupTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theCryptoDeskTradesSolanaCoinsAgain() {
        val ct = src("perps/CryptoAltTrader.kt")
        assertFalse(ct.contains("\"SOLANA_COIN_OWNED_BY_MEME_STACK_8043\""))
        assertFalse(ct.contains("cryptoEntryRefusal8042("))
        assertFalse(src("engine/truth/NetEdge8030.kt").contains("NET_EDGE_CRYPTO_TOO_SLOW_8042"))
        // the toggle, the exposure cap and the pre-FDG reason still stand
        assertTrue(ct.contains("if (!paper7803 && cryptoToggleOff8043()) \"CRYPTO_TOGGLE_OFF_8043\" else null"))
        assertTrue(ct.contains("if (remainingExposure7835 <= 0.0) \"CRYPTO_EXPOSURE_CAP_7835\" else null"))
        // a Solana coin the desk refuses is still offered to the meme lanes
        assertTrue(CryptoAltTrader.solanaCoinRefused8714(paper = false, dynamic = true, chainId = " Solana "))
        assertFalse(CryptoAltTrader.solanaCoinRefused8714(paper = true, dynamic = true, chainId = "solana"))
        assertFalse(CryptoAltTrader.solanaCoinRefused8714(paper = false, dynamic = true, chainId = "bsc"))
        assertFalse(CryptoAltTrader.solanaCoinRefused8714(paper = false, dynamic = false, chainId = "solana"))
    }

    @Test fun theBaseTicketClearsTheFixedLegs() {
        val d = LiveConcentrationDoctrine7697
        // 0.000594 SOL of fixed round trip inside a 1 % net buffer is a 0.0594 SOL ticket
        assertEquals(0.0594, d.costClearingSol8714(0.000594), 1e-9)
        assertEquals(0.0, d.costClearingSol8714(Double.NaN), 0.0)
        // 8712: 0.2074 tradeable, 0.0366 ticket (5 %); now the cost-clearing share
        assertEquals(0.0594 / 0.2074, d.shareFor8714(0.2074, 0.000594), 1e-9)
        // a wallet too small for it is capped at half; a wallet where 1/slots already clears it is sized as before
        assertEquals(d.MAX_SHARE_7697, d.shareFor8714(0.05, 0.000594), 0.0)
        assertEquals(0.05, d.shareFor8714(100.0, 0.000594), 0.0)
        assertEquals(0.05, d.shareFor8714(0.2074, 0.0), 0.0)
        assertEquals(0.05, d.shareFor8714(0.0, 0.000594), 0.0)
        // the routable minimum still rules and a wallet below it still gets nothing
        assertTrue(d.positionSol(0.2074, 0.0366) >= 0.0366)
        assertEquals(0.0, d.positionSol(0.02, 0.0366), 0.0)
        assertTrue(src("engine/truth/LiveConcentrationDoctrine7697.kt").contains("fun share(tradeableSol: Double): Double = shareFor8714(tradeableSol, fixedRoundTripSolNow8714())"))
    }

    @Test fun aSetupLosingOnItsOwnRecordRefusesLive() {
        fun v(se: Double, n: Double) = se * se * n
        // the 8712 Cortex setups panel
        assertTrue("MOONSHOT RS_LEADER n12 -29.2 ±7.4", LanePlaybook7907.coldLosing8714(12.0, -29.2, v(7.4, 12.0)))
        assertTrue("MOONSHOT BREAKOUT_RUNNER n12 -17.6 ±4.1", LanePlaybook7907.coldLosing8714(12.0, -17.6, v(4.1, 12.0)))
        assertTrue("MOONSHOT no-setup n22 -13.5 ±6.6", LanePlaybook7907.coldLosing8714(22.0, -13.5, v(6.6, 22.0)))
        assertFalse("under the cold-start label count", LanePlaybook7907.coldLosing8714(11.0, -28.3, v(8.2, 11.0)))
        assertFalse("MOONSHOT FLAG_CONTINUATION -8.8 ±10.3: not proven", LanePlaybook7907.coldLosing8714(12.0, -8.8, v(10.3, 12.0)))
        assertFalse("a positive record", LanePlaybook7907.coldLosing8714(14.0, 25.1, v(32.1, 14.0)))
        assertFalse(LanePlaybook7907.coldLosing8714(20.0, Double.NaN, 4.0))
        // it is the exact mirror of the cold-start proof
        assertTrue(LanePlaybook7907.coldStartPositive8080(12.0, 29.2, v(7.4, 12.0)))
        assertTrue(LaneAuthority8577.binding8577("CANDIDATE_ADMISSION_8136_" + LaneAuthority8577.LIVE_SETUP_LOSES_8714))
        val la = src("engine/truth/LaneAuthority8577.kt")
        assertTrue(la.contains("return setupLoses8714(ts, l, nowMs)"))
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("if (setup == null || setup in UNCLASSIFIED_8087) null else synchronized(this) {"))
    }
}
