package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.LearnerColumns8798
import com.lifecyclebot.engine.truth.PrintWitness8750
import com.lifecyclebot.engine.truth.TokenFacts
import com.lifecyclebot.engine.truth.TradeTape8100
import com.lifecyclebot.network.PumpSwapTape8307
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8801 — owner: "this is 100% why we cant make money … engineer a proper fix". The 8800 consensus removed isolated
 * spikes but not a feed that was wrong all the time: the parsed stream GUESSED an unknown PumpSwap pool's coin ("the one
 * included coin the transaction touched") and kept the guess, so a multi-hop route streamed a foreign pool's prices onto
 * a coin's tape, self-consistent and permanent. Now only chain maps a pool; every print must fit the curve's physics and
 * the coin's independent cap; the consensus is built only from witnessed, independent trades; a frozen curve is no
 * witness; the crypto book dates a mark by when the price was seen; the learners stop trusting old rows' price shape.
 */
class Aate8801PriceIntegrityTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun le(v: Long): ByteArray = ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }
    private fun b58(seed: Int): String = io.github.novacrypto.base58.Base58.base58Encode(ByteArray(32) { (it * 3 + seed).toByte() })

    /** A PumpSwap Buy/SellEvent on the pool whose 32 bytes start at [poolSeed] (layout as Aate8307DataTest's). */
    private fun pumpSwapEvent(poolSeed: Int, buy: Boolean, base: Long, quote: Long, tsSec: Long): ByteArray {
        val b = ByteArray(184)
        val disc = if (buy) byteArrayOf(103, 244.toByte(), 82, 31, 44, 245.toByte(), 119, 119)
            else byteArrayOf(62, 47, 55, 10, 165.toByte(), 3, 220.toByte(), 42)
        disc.copyInto(b, 0)
        le(tsSec).copyInto(b, 8); le(base).copyInto(b, 16)
        le(base * 1_000L).copyInto(b, 48); le(quote * 1_000L).copyInto(b, 56); le(quote).copyInto(b, 64)
        ByteArray(32) { (it * 3 + poolSeed).toByte() }.copyInto(b, 120)
        return b
    }

    @Test fun aMultiHopRouteNeverPutsAForeignPoolOnOurCoin() {
        TradeTape8100.resetForTest8100(); PumpSwapTape8307.clearForTest8307()
        val ours = b58(61); val ourPool = b58(71); val foreignPool = b58(81)
        val now = 1_760_000_100_000L
        val enc = java.util.Base64.getEncoder()
        // coin X -> SOL on the foreign pool (7x our price), then SOL -> our coin on our pool
        val logs = org.json.JSONArray()
            .put("Program data: " + enc.encodeToString(pumpSwapEvent(81, false, 1_000_000_000L, 700_000_000L, 1_760_000_095L)))
            .put("Program data: " + enc.encodeToString(pumpSwapEvent(71, true, 1_000_000_000L, 100_000_000L, 1_760_000_095L)))
        fun tx(sig: String) = org.json.JSONObject().put("signature", sig).put("transaction", org.json.JSONObject()
            .put("transaction", org.json.JSONObject().put("message", org.json.JSONObject()
                .put("accountKeys", org.json.JSONArray().put(org.json.JSONObject().put("pubkey", ours)))))
            .put("meta", org.json.JSONObject().put("err", org.json.JSONObject.NULL).put("logMessages", logs)))
        // Neither pool proven: nothing is taped, nothing is mapped (until 8801 both swaps became OUR prints, for good)
        assertEquals(0, PumpSwapTape8307.onEnhancedTx8307(tx("sigHop1"), setOf(ours), now))
        assertNull(PumpSwapTape8307.mintForPool8307(foreignPool)); assertNull(PumpSwapTape8307.mintForPool8307(ourPool))
        // A DexScreener row / per-mint decode / validator match is only a hint: still nothing mapped
        PumpSwapTape8307.notePoolMint8307(foreignPool, ours, now)
        assertNull(PumpSwapTape8307.mintForPool8307(foreignPool))
        assertTrue(TradeTape8100.prints8128(ours, now, 120_000L).isEmpty())
        // Chain proves our pool: its waiting swap replays; the foreign one never reaches our coin
        PumpSwapTape8307.notePoolMint8307(ourPool, ours, now, verifiedSolQuote8751 = true)
        assertTrue(PumpSwapTape8307.hasProvenPool8801(ours))
        val p = TradeTape8100.prints8128(ours, now, 120_000L)
        assertEquals(1, p.size)
        assertEquals(ourPool, p[0].pool)
        assertEquals(0.1 / 1_000.0, p[0].priceSol, 1e-15)
        // A wrong hint after chain spoke changes nothing
        PumpSwapTape8307.notePoolMint8307(ourPool, b58(99), now)
        assertEquals(ours, PumpSwapTape8307.mintForPool8307(ourPool))
        assertTrue(PumpSwapTape8307.statusLine8307().contains("hintConflicts8801=1"))
    }

    @Test fun everyPoolWriterIsAHintUnlessChainProvedIt() {
        val tape = src("network/PumpSwapTape8307.kt")
        val enhanced = tape.substringAfter("fun onEnhancedTx8307(").substringBefore("V5.0.8734 — any other venue")
        assertFalse("the enhanced path never guesses a pool's coin", enhanced.contains("attributeMint8307(touched, included)"))
        assertTrue(enhanced.contains("bufferUnknownPool8751(ev, sig, nowMs)") && enhanced.contains("PumpSwapPools8750.resolve8750(ev.pool)"))
        assertTrue(tape.contains("if (!verifiedSolQuote8751) {"))
        assertTrue(src("network/PumpSwapPools8750.kt").contains("PumpSwapTape8307.notePoolMint8307(pool, base, verifiedSolQuote8751 = true)"))
        assertTrue(src("network/ChainLaunchFeed8179.kt").contains("val proven8801 = l.quoteMint == WSOL_8179 && PumpCurveKeys7269.isPumpMint8658(l.mint)"))
        val vm = src("network/ValidatorMarket8506.kt")
        assertTrue(vm.contains("if (mint == null) { if (preview == null && matched != null) PumpSwapTape8307.notePoolMint8307(e.pool, matched); continue }"))
        val ws = src("network/HeliusWebSocket.kt")
        assertTrue(ws.contains("if (PumpSwapTape8307.mintForPool8307(ev.pool) != mint) { PumpSwapTape8307.notePoolMint8307(ev.pool, mint); continue }"))
    }

    @Test fun aPriceMustFitTheCurveAndTheCoinsIndependentCap() {
        val sol = 73.0
        assertEquals("NOT_A_PRICE", PrintWitness8750.physicsRefusal8801(Double.NaN, false, 1e9, sol, null))
        // pump.fun curve: ~28 SOL of cap at launch, ~410 at graduation
        assertNull(PrintWitness8750.physicsRefusal8801(2.8e-8, true, 1e9, sol, null))
        assertNull(PrintWitness8750.physicsRefusal8801(4.1e-7, true, 1e9, sol, null))
        assertEquals("CURVE_PHYSICS", PrintWitness8750.physicsRefusal8801(1.0e-8, true, 1e9, sol, null))
        assertEquals("CURVE_PHYSICS", PrintWitness8750.physicsRefusal8801(1.23e-2, true, 1e9, sol, null))   // plumber's 500x phantom
        assertNull("off the curve there is no curve bound", PrintWitness8750.physicsRefusal8801(5.0e-6, false, 1e9, sol, null))
        // the independent cap: plumber at $40k is 5.48e-7 SOL a token
        val cap = 40_000.0; val px = cap / sol / 1e9
        assertNull(PrintWitness8750.physicsRefusal8801(px * 3.0, false, 1e9, sol, cap))
        assertEquals("21x the cap is a foreign stream", "MCAP_ANCHOR", PrintWitness8750.physicsRefusal8801(px * 21.0, false, 1e9, sol, cap))
        assertEquals("a USDC-quoted pool read as SOL", "MCAP_ANCHOR", PrintWitness8750.physicsRefusal8801(px * sol, false, 1e9, sol, cap))
        assertNull("a crash always reaches the stops", PrintWitness8750.physicsRefusal8801(px * 0.02, false, 1e9, sol, cap))
        // the allowance grows with the anchor's age: a real run moves before the next provider read
        assertEquals(4.0, PrintWitness8750.anchorAllowance8801(0L), 1e-12)
        assertEquals(8.0, PrintWitness8750.anchorAllowance8801(60_000L), 1e-12)
        assertEquals("MCAP_ANCHOR", PrintWitness8750.physicsRefusal8801(px * 6.0, false, 1e9, sol, cap, 0L))
        assertNull(PrintWitness8750.physicsRefusal8801(px * 6.0, false, 1e9, sol, cap, 60_000L))
        assertNull("unknown supply: no cap check", PrintWitness8750.physicsRefusal8801(px * 21.0, false, Double.NaN, sol, cap))
        // only independent caps anchor a print, never one derived from the bot's own prints
        assertTrue(TokenFacts.anchorSource8801("DEXSCREENER_WS") && TokenFacts.anchorSource8801("BIRDEYE_OVERVIEW"))
        assertFalse(TokenFacts.anchorSource8801("CHAIN_PRINT_X_SUPPLY") || TokenFacts.anchorSource8801("PRICE_IMPLIED_8019"))
        assertTrue(src("engine/truth/TokenFacts.kt").contains("if (field == Field.MCAP_USD) noteAnchorMcap8801(ts.mint, offered)"))
    }

    @Test fun aFrozenCurveIsNoWitness() {
        assertTrue(PrintWitness8750.curveUsable8801(40.0, 10_000L, false))
        assertFalse("at completion (a missed graduation freezes it there)", PrintWitness8750.curveUsable8801(85.0, 10_000L, false))
        assertFalse("a chain-proven PumpSwap pool", PrintWitness8750.curveUsable8801(40.0, 10_000L, true))
        assertFalse("stale", PrintWitness8750.curveUsable8801(40.0, 5L * 60_000L, false))
        val w = src("engine/truth/PrintWitness8750.kt")
        assertTrue(w.contains("liveCurve8801(mint, nowMs)?.let { o ->"))
        assertTrue(w.contains("val onCurve = pump && liveCurve8801(mint, nowMs) != null"))
    }

    @Test fun onlyWitnessedIndependentTradesBuildOrMoveTheConsensus() {
        val c = PrintWitness8750.Consensus8800()
        var t = 0L
        repeat(6) { assertTrue(PrintWitness8750.consensus8800(c, 7.0, t, witnessed = false)); t += 1_000L }
        assertTrue("unwitnessed prints never build a baseline", c.accepted.isEmpty())
        for (p in listOf(1.00, 1.02, 0.99, 1.01, 1.03)) { PrintWitness8750.consensus8800(c, p, t, sig = "s$t", wallet = "w$t"); t += 1_000L }
        assertEquals(5, c.accepted.size)
        assertFalse("an unwitnessed outlier is refused", PrintWitness8750.consensus8800(c, 7.0, t, witnessed = false))
        assertTrue("and never counts toward a re-pricing", c.outliers.isEmpty())

        fun based(): Pair<PrintWitness8750.Consensus8800, Long> {
            val b = PrintWitness8750.Consensus8800()
            for ((i, p) in listOf(1.00, 1.02, 0.99, 1.01, 1.03).withIndex()) PrintWitness8750.consensus8800(b, p, i * 1_000L, sig = "b$i", wallet = "bw$i")
            return b to 5_000L
        }
        // one feed repeating one wrong price, three times: never a re-pricing
        based().let { (b, t0) -> repeat(3) { assertFalse(PrintWitness8750.consensus8800(b, 0.14, t0 + it * 1_000L, sig = "f$it", wallet = "w$it")) } }
        // one wallet alone cannot re-price either
        based().let { (b, t0) -> repeat(3) { assertFalse(PrintWitness8750.consensus8800(b, 0.14 + it * 0.001, t0 + it * 1_000L, sig = "g$it", wallet = "dev")) } }
        // nor one transaction seen through three feeds
        based().let { (b, t0) -> repeat(3) { assertFalse(PrintWitness8750.consensus8800(b, 0.14 + it * 0.001, t0 + it * 1_000L, sig = "same", wallet = "v$it")) } }
        // a real crash: distinct trades, distinct sellers, prices that move
        based().let { (b, t0) ->
            assertFalse(PrintWitness8750.consensus8800(b, 0.140, t0, sig = "c1", wallet = "a"))
            assertFalse(PrintWitness8750.consensus8800(b, 0.132, t0 + 1_000L, sig = "c2", wallet = "b"))
            assertTrue(PrintWitness8750.consensus8800(b, 0.128, t0 + 2_000L, sig = "c3", wallet = "c"))
            assertTrue(b.accepted.maxOf { it.first } < 0.2)
        }
        val run = listOf(PrintWitness8750.Out8801(1.0, 0L, "", ""), PrintWitness8750.Out8801(1.0, 1L, "", ""))
        assertFalse(PrintWitness8750.shiftIndependent8801(run))
    }

    @Test fun theCryptoBookDatesAMarkByWhenThePriceWasSeen() {
        assertEquals(1_000L, DynamicAltTokenRegistry.priceTime8801(1_000L, 9_000L))
        assertEquals("a writer with no source clock observed it as it wrote", 9_000L, DynamicAltTokenRegistry.priceTime8801(0L, 9_000L))
        val reg = src("perps/DynamicAltTokenRegistry.kt")
        assertTrue(reg.contains("val observedAt = refreshed?.let { priceTime8801(it.priceObservedAtMs8372, it.lastUpdatedMs) } ?: 0L"))
        assertTrue(reg.contains("if (priceTime8801(existing.priceObservedAtMs8372, existing.lastUpdatedMs) > observedAtMs) return false"))
    }

    @Test fun oldRowsPriceShapeIsUnknownToEveryLearner() {
        val old = org.json.JSONObject("""{"modelEpoch":8722,"runup":40,"dd":92,"pxPeak":1e-5,"fromPeak":-92,"liq":9000,"net":170}""")
        assertTrue(EdgeModel8537.column8801(old, "dd").isNaN())
        assertTrue(EdgeModel8537.column8801(old, "runup").isNaN())
        assertEquals("every other column stands", 9_000.0, EdgeModel8537.column8801(old, "liq"), 0.0)
        val fresh = org.json.JSONObject(old.toString()).put("modelEpoch", EdgeModel8537.SHAPE_EPOCH_8801)
        assertEquals(92.0, EdgeModel8537.column8801(fresh, "dd"), 0.0)
        assertTrue(LearnerColumns8798.fromRow(old)[LearnerColumns8798.NAMES.indexOf("fromPeak")].isNaN())
        assertEquals(-92.0, LearnerColumns8798.fromRow(fresh)[LearnerColumns8798.NAMES.indexOf("fromPeak")], 0.0)
        assertTrue(EdgeModel8537.MODEL_EPOCH_8642 >= EdgeModel8537.SHAPE_EPOCH_8801)   // the ridge is rebuilt under the rule
        assertTrue(src("engine/truth/TreeRanker8737.kt").contains("fun d(k: String) = EdgeModel8537.column8801(o, k)"))
        assertTrue(src("engine/lab/StrategyLab8755.kt").contains("fun d(k: String) = EdgeModel8537.column8801(o, k)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 11. Price integrity (8801)"))
    }
}
