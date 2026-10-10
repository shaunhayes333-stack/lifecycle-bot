package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.FirstSight8026
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8026FirstSightTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun evidenceEvAndWinRateMustAllClear() {
        assertTrue(FirstSight8026.edgeClears8026(evidence = 0.8, meanPct = 26.9, sePct = 6.0, winRate = 0.35))   // SHITCOIN-like
        assertFalse(FirstSight8026.edgeClears8026(0.3, 26.9, 6.0, 0.35))      // weak evidence
        assertFalse(FirstSight8026.edgeClears8026(0.8, 4.0, 6.0, 0.35))       // EV lower bound below zero
        assertFalse(FirstSight8026.edgeClears8026(0.8, 26.9, 6.0, 0.15))      // win rate too low
        assertFalse(FirstSight8026.edgeClears8026(Double.NaN, 26.9, 6.0, 0.35))
    }

    @Test fun cashIsProtectedAndSlotsGrowWithMaturity() {
        assertEquals(1, FirstSight8026.maxOpen8026(0.06))                      // immature lane: one probe at a time
        assertEquals(3, FirstSight8026.maxOpen8026(0.5))
        assertEquals(4, FirstSight8026.maxOpen8026(0.95))
        // 0.5 SOL wallet: probes may hold at most 20% of wallet + open
        assertTrue(FirstSight8026.cashAllows8026(openCostSol = 0.0, nextCostSol = 0.03, walletSol = 0.5, lossTodaySol = 0.0, dayStartSol = 0.5))
        assertFalse(FirstSight8026.cashAllows8026(0.09, 0.03, 0.5, 0.0, 0.5))
        assertFalse(FirstSight8026.cashAllows8026(0.0, 0.03, 0.5, 0.06, 0.5))   // today's probe losses hit 10%
        assertFalse(FirstSight8026.cashAllows8026(0.0, 0.03, 0.0, 0.0, 0.0))
        assertTrue(FirstSight8026.standsDown8026(15.0, -8.0, 3.0))
        assertFalse(FirstSight8026.standsDown8026(10.0, -8.0, 3.0))
    }

    @Test fun aCoinSpecificProvenLoserIsNeverAFirstSight() {
        assertTrue(FirstSight8026.coinProvenLoser8026("WATCH_FIRST_7994_CELL_NEGATIVE"))
        assertTrue(FirstSight8026.coinProvenLoser8026("WATCH_FIRST_8019_BAND_NEGATIVE"))
        assertTrue(FirstSight8026.coinProvenLoser8026("PLAYBOOK_SETUP_PROVEN_LOSING_7907_SHITCOIN"))
        assertFalse(FirstSight8026.coinProvenLoser8026("PLAYBOOK_NO_TRIGGER_PROVEN_LOSING_7907_MOONSHOT"))   // lane aggregate
        assertFalse(FirstSight8026.coinProvenLoser8026("EXPLORATION_BUDGET_REFUSED_DUST_PROBE_6967"))
        val c = src("engine/cortex/Cortex7885.kt")
        assertTrue(c.contains("if (firstSight8026(a, ts, refusal)) { inc(\"FIRST_SIGHT_8026\"); return true }"))
        assertTrue(src("engine/truth/TraderSizingBridge6444.kt").contains("FirstSight8026.sizeMult8026(mintForSeal,"))
        assertTrue(src("engine/truth/CanonicalFinalizedTradeBus6464.kt").contains("FirstSight8026.onClose8026(env)"))
        assertEquals(0.5, FirstSight8026.sizeMult8026("NOTICKET", 0.5), 0.0)
        assertTrue(FirstSight8026.statusLine().contains("grants="))
        assertEquals(30_000L, FirstSight8026.PRICE_FRESH_MS)
        assertEquals(0.25, FirstSight8026.MIN_WIN_RATE, 0.0)
        assertEquals(0.20, FirstSight8026.MAX_EXPOSURE, 0.0)
        // a stale or seeded price never passes integrity
        val stale = com.lifecyclebot.data.TokenState(mint = "So11111111111111111111111111111111111111112", symbol = "X").also {
            it.lastPrice = 1.0; it.lastPriceUpdate = 1L; it.lastPriceSource = "LOCKED_VENUE_CURVE_7392"
        }
        assertFalse(FirstSight8026.integrityOk8026(stale, 10_000_000L))
        val seeded = com.lifecyclebot.data.TokenState(mint = "So11111111111111111111111111111111111111112", symbol = "X").also {
            it.lastPrice = 1.0; it.lastPriceUpdate = 9_990_000L; it.lastPriceSource = "PUMP_FUN_BC_SYNTHETIC"
        }
        assertFalse(FirstSight8026.integrityOk8026(seeded, 10_000_000L))
    }
}
