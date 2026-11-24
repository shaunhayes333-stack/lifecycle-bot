package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8996 — the thin-pool backstop is triggered by a price reading, so it is checked against the coin's own pool like
 * every other price stop (it sold a bag at -2.2 % on a "-10 %" mark); rug, drain and liquidity exits are still never held.
 */
class Aate8996ThinPoolBackstopAsksTheVenueTest {
    private val s = StopMarkConfirm8418

    @Test fun theBackstopIsAPriceLossStop() {
        assertTrue(s.priceLossStop8489("THIN_LIQ_EARLY_RUG_BACKSTOP_-10"))
        assertTrue(s.priceLossStop8489("THIN_LIQ_EARLY_RUG_BACKSTOP_-15"))
        assertEquals(-10.0, s.stopDepthPct8489("THIN_LIQ_EARLY_RUG_BACKSTOP_-10")!!, 1e-9)
        // the read that sold at -2.2 % refutes a -10 % stop; a real fall confirms it
        assertTrue(s.venueRefutes8489(-2.2, -10.0))
        assertFalse(s.venueRefutes8489(-9.0, -10.0))
        assertFalse(s.venueRefutes8489(-14.0, -15.0))
    }

    @Test fun rugDrainAndLiquidityExitsAreStillNeverHeld() {
        for (r in listOf("RUG_DRAIN_EXIT", "RUG_PULL_DETECTED", "LIQUIDITY_COLLAPSE", "LIQUIDITY_DRAIN_EXIT_8153_40PCT", "LP_PULL",
                "DEV_DUMP", "HONEYPOT_DETECTED", "STALE_PRICE_RUG_ESCAPE"))
            assertFalse(r, s.priceLossStop8489(r))
    }

    @Test fun wired() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/StopMarkConfirm8418.kt").readText()
        assertTrue(src.contains("if (r.startsWith(THIN_POOL_BACKSTOP_8996)) return true"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
            .contains("doSell(ts, \"THIN_LIQ_EARLY_RUG_BACKSTOP_-\${if (freshThinLiq) \"10\" else \"15\"}\", wallet, walletSol)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 181. The thin-pool backstop asks the venue (8996)"))
    }
}
