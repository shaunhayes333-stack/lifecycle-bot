package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PrintWitness8750
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8800 — owner, on "plumber" (9a13…pump): "we should have bought this! literally no excuses". The bot watched it from
 * +78 s and its lanes admitted it 566 times; a print 500x its price at +18.5 min (and a stream at a fixed price 7x under it)
 * made every reader see "96 % under its peak" while it ran. 34 % of launches carried such prints. A print now has to agree
 * with the coin's own recent prints; a real re-pricing is taken on three agreeing prints.
 */
class Aate8800PrintConsensusTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theMedianNeedsFivePrintsInsideTheWindow() {
        val p = listOf(1.0 to 0L, 2.0 to 1_000L, 3.0 to 2_000L, 4.0 to 3_000L)
        assertNull(PrintWitness8750.median8800(p, 3_000L))
        assertEquals(3.0, PrintWitness8750.median8800(p + (5.0 to 4_000L), 4_000L)!!, 0.0)
        assertNull("prints older than three minutes do not count", PrintWitness8750.median8800(p + (5.0 to 4_000L), 4_000L + 3 * 60_000L))
    }

    @Test fun plumbersPhantomPrintsNeverSetAPeakOrALow() {
        val c = PrintWitness8750.Consensus8800()
        var t = 1_000_000L
        val real = listOf(2.40e-5, 2.35e-5, 2.50e-5, 2.56e-5, 2.47e-5, 2.60e-5, 2.70e-5)
        for (p in real) assertTrue(PrintWitness8750.consensus8800(c, p, t).also { t += 2_000L })
        // the 5.0.8797 export's phantoms: a fixed print 7x under the price, and one 500x over it, interleaved with real trades
        val tape = listOf(3.4349e-6, 2.75e-5, 0.012296, 2.80e-5, 3.4349e-6, 2.90e-5, 0.012334, 3.00e-5)
        val kept = tape.filter { p -> PrintWitness8750.consensus8800(c, p, t).also { t += 2_000L } }
        assertEquals(listOf(2.75e-5, 2.80e-5, 2.90e-5, 3.00e-5), kept)
        assertTrue("the peak is the coin's own", c.accepted.maxOf { it.first } < 1.0e-4)
        assertTrue("so is the low", c.accepted.minOf { it.first } > 1.0e-5)
    }

    @Test fun aRealRunIsFollowedAndARepricingIsConfirmedByThreePrints() {
        val c = PrintWitness8750.Consensus8800()
        var t = 0L
        var p = 1.0
        repeat(40) { assertTrue("a steady run passes", PrintWitness8750.consensus8800(c, p, t)); p *= 1.08; t += 3_000L }
        assertTrue(p > 20.0)                                            // a 20x run, print by print
        assertFalse(PrintWitness8750.consensus8800(c, p * 6.0, t))      // one stray print is refused ...
        assertFalse(PrintWitness8750.consensus8800(c, p * 6.1, t + 1_000L))
        assertTrue(PrintWitness8750.consensus8800(c, p * 6.2, t + 2_000L))   // ... three agreeing within a minute re-price the coin
        assertTrue(PrintWitness8750.consensus8800(c, p * 6.3, t + 3_000L))
        val d = PrintWitness8750.Consensus8800()
        for (i in 0 until 5) PrintWitness8750.consensus8800(d, 1.0, i * 1_000L)
        assertFalse(PrintWitness8750.consensus8800(d, 10.0, 10_000L)); assertFalse(PrintWitness8750.consensus8800(d, 50.0, 11_000L))
        assertFalse("outliers that disagree with each other never re-price", PrintWitness8750.consensus8800(d, 0.05, 12_000L))
    }

    @Test fun everyPricePathIsGuarded() {
        val w = src("engine/truth/PrintWitness8750.kt")
        // superseded by V5.0.8801 — the consensus is told whether the print was witnessed, and by which trade
        assertTrue(w.contains("!consensusAccepts8800(mint, priceSol, atMs, nowMs, witnessed = v0 != Verdict.UNWITNESSED, sig = sig, wallet = wallet)"))
        assertTrue(src("engine/DataOrchestrator.kt").contains("PrintWitness8750.consensusAllows8800(mint, priceSolPerToken)"))
        val crypto = src("perps/CryptoAltTrader.kt")
        assertTrue(crypto.contains("if (!fresh8800) chainHeldMark8800(resolved!!)"))
        assertTrue(crypto.contains("DynamicAltTokenRegistry.observeHeldMarkAt8800(token.canonicalIdentity6544, px, at)"))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("com.lifecyclebot.perps.RetiredMarkets8797.watchdog(applicationContext)"))
        assertFalse(bot.contains("com.lifecyclebot.perps.ForexTrader.start()"))
    }
}
