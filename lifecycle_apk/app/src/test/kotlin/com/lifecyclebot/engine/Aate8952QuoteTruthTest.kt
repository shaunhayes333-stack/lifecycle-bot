package com.lifecyclebot.engine

import com.lifecyclebot.network.ChainLaunchFeed8179
import com.lifecyclebot.network.PoolVirtualQuote8952
import com.lifecyclebot.network.PumpFunWS
import com.lifecyclebot.network.QuoteTruth8952
import io.github.novacrypto.base58.Base58
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * V5.0.8952 — a pump.fun curve is read in its own quote (SOL, USDC or a pump.fun coin), a PumpSwap pool on its effective
 * quote reserves, the curve's own price writes the cap, and pump.fun's paid per-trade stream carries only deserving coins.
 */
class Aate8952QuoteTruthTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private val mint = "7veuDdmUVkmaKBPHkRk3pBK2ro8WtgEzEgdt4LJupump"
    private val user = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val pumpQuote = "Bz9kQ5Wq4BdFy9x2dhtJcSmDqHppGv8nR2iT6qkWpump"

    private fun u64(o: ByteArrayOutputStream, v: Long) { for (i in 0 until 8) o.write(((v ushr (8 * i)) and 0xFF).toInt()) }
    private fun u32(o: ByteArrayOutputStream, v: Int) { for (i in 0 until 4) o.write((v ushr (8 * i)) and 0xFF) }

    /** A TradeEvent: the fixed head, then (when [quote] is given) the 2026 tail up to holder_reward. */
    private fun event(sol: Long, tokens: Long, vSol: Long, vTok: Long, realSol: Long, quote: String?, quoteAmt: Long = 0L,
                      vQuote: Long = 0L, rQuote: Long = 0L, holders: Int = 0, ixName: String = "buy"): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(189.toByte(), 219.toByte(), 127, 211.toByte(), 78, 230.toByte(), 97, 238.toByte()))
        o.write(Base58.base58Decode(mint)); u64(o, sol); u64(o, tokens); o.write(1); o.write(Base58.base58Decode(user))
        u64(o, 1_795_000_000L); u64(o, vSol); u64(o, vTok); u64(o, realSol); u64(o, 700_000_000_000_000L)
        if (quote == null) return o.toByteArray()
        o.write(ByteArray(32)); u64(o, 95); u64(o, 0); o.write(ByteArray(32)); u64(o, 30); u64(o, 0)   // fee_recipient … creator_fee
        o.write(0); u64(o, 0); u64(o, 0); u64(o, 0); u64(o, 0)                                            // track_volume … last_update_ts
        u32(o, ixName.length); o.write(ixName.toByteArray())
        o.write(0); u64(o, 0); u64(o, 0); u64(o, 0); u64(o, 0)                                            // mayhem, cashback, buyback
        u32(o, holders); repeat(holders) { o.write(ByteArray(32)); o.write(0x10); o.write(0x27) }          // {address, share_bps u16}
        o.write(Base58.base58Decode(quote)); u64(o, quoteAmt); u64(o, vQuote); u64(o, rQuote); u64(o, 0); u64(o, 0)
        return o.toByteArray()
    }

    @Test fun aSolCurveReadsAsBefore() {
        QuoteTruth8952.resetForTest8952()
        val legacy = ChainLaunchFeed8179.decodeTradeEvent8952(event(1_500_000_000L, 52_000_000_000_000L, 41_500_000_000L, 775_000_000_000_000L, 11_500_000_000L, null)) { Double.NaN }!!
        assertEquals(QuoteTruth8952.SOL, legacy.quote8952); assertEquals(1.5, legacy.sol, 1e-9); assertEquals(41.5, legacy.virtualSol, 1e-9)
        assertNull(QuoteTruth8952.tail8952(event(1L, 1L, 1L, 1L, 1L, null)))
        val wsol = ChainLaunchFeed8179.decodeTradeEvent8952(event(1_500_000_000L, 52_000_000_000_000L, 41_500_000_000L, 775_000_000_000_000L,
            11_500_000_000L, ChainLaunchFeed8179.WSOL_8179, 1_500_000_000L, 41_500_000_000L, 11_500_000_000L)) { Double.NaN }!!
        assertEquals(QuoteTruth8952.SOL, wsol.quote8952); assertEquals(1.5, wsol.sol, 1e-9)
        assertEquals(ChainLaunchFeed8179.WSOL_8179, wsol.quoteMint8952)
        assertFalse(QuoteTruth8952.nonSol8952(mint))
    }

    @Test fun aUsdcCurveIsConvertedToSolNeverReadAsLamports() {
        QuoteTruth8952.resetForTest8952()
        // 1.4 USDC for tokens on a curve holding 4,200 USDC virtual / 140 real, SOL at $140
        val b = event(1_400_000L, 52_000_000_000_000L, 4_200_000_000L, 775_000_000_000_000L, 140_000_000L,
            ChainLaunchFeed8179.USDC_8179, 1_400_000L, 4_200_000_000L, 140_000_000L)
        val ev = ChainLaunchFeed8179.decodeTradeEvent8952(b) { 1.0 / 140.0 }!!
        assertEquals(QuoteTruth8952.USDC, ev.quote8952)
        assertEquals(0.01, ev.sol, 1e-12); assertEquals(30.0, ev.virtualSol, 1e-9); assertEquals(1.0, ev.realSol, 1e-9)
        assertTrue("its 30-SOL curve math is refused from now on", QuoteTruth8952.nonSol8952(mint))
        assertNull("an unpriced quote is dropped, never read as SOL", ChainLaunchFeed8179.decodeTradeEvent8952(b) { Double.NaN })
        assertTrue(QuoteTruth8952.statusLine8952().contains("USDC:priced=1"))
        assertTrue(QuoteTruth8952.statusLine8952().contains("USDC:unpriced=1"))
    }

    @Test fun aPumpCoinQuoteAndSharedFeesParse() {
        QuoteTruth8952.resetForTest8952()
        val b = event(0L, 52_000_000_000_000L, 0L, 775_000_000_000_000L, 0L, pumpQuote, 2_000_000_000L, 60_000_000_000L, 0L, holders = 2, ixName = "buy_v3")
        val t = QuoteTruth8952.tail8952(b)!!
        assertEquals(pumpQuote, t.quoteMint); assertEquals(2e9, t.quoteAmount, 0.0); assertEquals(6e10, t.virtualQuote, 0.0)
        val ev = ChainLaunchFeed8179.decodeTradeEvent8952(b) { q -> if (q == pumpQuote) 1e-5 else Double.NaN }!!
        assertEquals(QuoteTruth8952.PUMP, ev.quote8952); assertEquals(0.02, ev.sol, 1e-12); assertEquals(0.6, ev.virtualSol, 1e-12)
        assertEquals(QuoteTruth8952.SOL, QuoteTruth8952.kind8952("11111111111111111111111111111111"))
        assertEquals(QuoteTruth8952.SOL, QuoteTruth8952.kind8952(""))
        assertEquals(QuoteTruth8952.OTHER, QuoteTruth8952.kind8952(user))
        assertEquals(-1, QuoteTruth8952.decimals8952(QuoteTruth8952.OTHER))
        assertNull("an unknown quote's decimals are never guessed", ChainLaunchFeed8179.decodeTradeEvent8952(
            event(0L, 1_000_000L, 0L, 775_000_000_000_000L, 0L, user, 1L, 1L, 0L)) { 1.0 })
    }

    @Test fun theCreateEventNamesTheQuoteAtBirth() {
        fun borsh(x: String): ByteArray { val b = x.toByteArray(); val o = ByteArrayOutputStream(); u32(o, b.size); o.write(b); return o.toByteArray() }
        val head = byteArrayOf(27, 114, 169.toByte(), 77, 222.toByte(), 235.toByte(), 99, 118) + borsh("Moon") + borsh("M") + borsh("u") +
            Base58.base58Decode(mint) + ByteArray(32) + Base58.base58Decode(user)
        val full = head + ByteArray(32) + ByteArray(40) + ByteArray(32) + byteArrayOf(0, 0) + Base58.base58Decode(ChainLaunchFeed8179.USDC_8179) + ByteArray(17)
        assertEquals(ChainLaunchFeed8179.USDC_8179, ChainLaunchFeed8179.decodeCreateEvent8840(full)!!.quoteMint8952)
        assertEquals("an older create names none", "", ChainLaunchFeed8179.decodeCreateEvent8840(head + ByteArray(40))!!.quoteMint8952)
    }

    @Test fun theConsumersHonourTheQuote() {
        val cr = src("engine/truth/CurveReserves7951.kt")
        assertTrue(cr.contains("if (com.lifecyclebot.network.QuoteTruth8952.nonSol8952(m)) { com.lifecyclebot.network.QuoteTruth8952.noteCurveRefused8952(); return null }"))
        val tape = src("network/HeliusCurveTape8354.kt")
        assertTrue(tape.contains("val vSolIn8952 = if (ev.quote8952 == QuoteTruth8952.SOL) ev.virtualSol"))
        assertTrue(tape.contains("bad8952(\"EXEC_VS_RESERVES_\" + ev.quote8952)"))
        assertTrue(tape.contains("QuoteTruth8952.noteNonSol8952(ev.mint, k, Double.NaN, nowMs)"))
        assertTrue(src("network/PumpFunWS.kt").contains("if (QuoteTruth8952.nonSol8952(mint)) { nonPumpCurveFrame8657(\"NON_SOL_QUOTE_8952\"); return }"))
    }

    @Test fun aPumpSwapPoolPricesOnItsEffectiveQuote() {
        assertEquals(12.0, PoolVirtualQuote8952.effective8952(10.0, 2e9)!!, 1e-12)
        assertEquals(8.0, PoolVirtualQuote8952.effective8952(10.0, -2e9)!!, 1e-12)
        assertNull(PoolVirtualQuote8952.effective8952(1.0, -2e9))
        PoolVirtualQuote8952.resetForTest8952()
        val pool = "Ezf1SaxtMiGpXfZ6QXtFZqpREcngTJLGvxmyAoT2uDBh"
        assertEquals("no event yet: the vault ratio as before", 5.0, PoolVirtualQuote8952.effectiveSol8952(pool, 5.0)!!, 0.0)
        PoolVirtualQuote8952.note8952(pool, 3e9)
        assertEquals(8.0, PoolVirtualQuote8952.effectiveSol8952(pool, 5.0)!!, 1e-12)
        val d = src("engine/truth/OnChainDepth8038.kt")
        assertTrue(d.contains("if (owner8418 == PUMP_AMM) com.lifecyclebot.network.PoolVirtualQuote8952.effectiveSol8952(p, sides.first)"))
        assertTrue(src("network/HeliusWebSocket.kt").contains("virtualQuoteReserves8870(b, buy)?.let { PoolVirtualQuote8952.note8952(pool, it.toDouble()) }"))
    }

    @Test fun theCurveWritesItsOwnCap() {
        val cr = src("engine/truth/CurveReserves7951.kt")
        assertTrue(cr.contains("if (accountObserved) try { capFromCurve8952(m, vSol, vTok, atMs) } catch (_: Throwable) {}"))
        assertTrue(src("engine/truth/CapTruth8951.kt").contains("if (b != \"agree<1.1x\") try { OnChainDepth8038.needCurve8039(mint, nowMs) }"))
    }

    @Test fun onlyDeservingCoinsTakeThePaidStream() {
        val now = 10_000_000L
        assertTrue(PumpFunWS.deservingLive8952(now - 60_000L, now, held = false))
        assertFalse(PumpFunWS.deservingLive8952(now - 16 * 60_000L, now, held = false))
        assertTrue("a held coin never lapses", PumpFunWS.deservingLive8952(now - 16 * 60_000L, now, held = true))
        val set = mapOf("a" to now - 20 * 60_000L, "b" to now - 5_000L, "c" to now - 9_000L, "h" to now - 99_000L)
        assertEquals(listOf("a"), PumpFunWS.deserveEvictions8952(set, "n", now, 4) { it == "h" })
        assertEquals("over the cap the eldest non-held leaves, never the held",
            listOf("a", "c"), PumpFunWS.deserveEvictions8952(set, "n", now, 3) { it == "h" })
        val ws = src("network/PumpFunWS.kt")
        assertTrue(ws.contains("for (m in wanted) try { deserve8952(m, \"HELD\") } catch (_: Throwable) {}"))
        assertTrue(ws.contains("if (try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { true }) { deservePaper8952.incrementAndGet(); return false }"))
        assertTrue(ws.contains("if (!held && !launchSubAllowed8192(framesToday8192.get())) { deserveSkippedBudget8952.incrementAndGet(); return false }"))
        assertTrue(src("engine/truth/LaunchSniper8748.kt").contains("PumpFunWS.deserve8952(f.mint, \"LAUNCH_RULE\")"))
        assertTrue(src("engine/truth/EvidenceWait8948.kt").contains("PumpFunWS.deserve8952(mint, \"PROVEN_WAIT\", nowMs)"))
        assertTrue(src("engine/RunTape8293.kt").contains("PumpFunWS.deserve8952(mint, \"RUN_FORMING\", nowMs)"))
        assertTrue(src("engine/RunnerPlay8018.kt").contains("PumpFunWS.deserve8952(mint, \"RUN_FOLLOWED\", nowMs)"))
    }
}
