package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MintSupply8418
import com.lifecyclebot.engine.truth.MintSupply8418.Source8418
import com.lifecyclebot.engine.truth.MintSupply8418.Supply8418
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/** V5.0.8418 — the full mint-supply record (raw, decimals, slot, observation time, source) survives a restart. */
class Aate8418SupplyPersistenceTest {

    private val mintA = "Aate8418PersistMintAAAAAAAAAAAAAAAAAAAApump"
    private val mintB = "Aate8418PersistMintBBBBBBBBBBBBBBBBBBBBBBBB"
    private val bigRaw = BigInteger("123456789012345678901234567")

    @Before fun reset() { MintSupply8418.resetForTest8418() }

    @Test fun aRecordRoundTripsEveryField() {
        val s = Supply8418(mintA, bigRaw, 9, 312_345_678L, 1_760_000_000_123L, Source8418.MINT_ACCOUNT)
        val back = MintSupply8418.decodeLine8418(MintSupply8418.encodeLine8418(s))
        assertNotNull(back)
        assertEquals(s, back)
        assertEquals(bigRaw, back!!.rawAmount)
        assertEquals(9, back.decimals)
        assertEquals(312_345_678L, back.slot)
        assertEquals(1_760_000_000_123L, back.observedAtMs)
        assertEquals(Source8418.MINT_ACCOUNT, back.source)
        val das = Supply8418(mintB, BigInteger("1000"), 0, 0L, 5_000L, Source8418.DAS)
        assertEquals(das, MintSupply8418.decodeLine8418(MintSupply8418.encodeLine8418(das)))
    }

    @Test fun malformedOrInvalidLinesAreDroppedNeverFilledIn() {
        assertNull(MintSupply8418.decodeLine8418(""))
        assertNull(MintSupply8418.decodeLine8418("$mintA\t1000\t6\t5"))                       // missing fields
        assertNull(MintSupply8418.decodeLine8418("$mintA\t1000.5\t6\t5\t9\tMINT_ACCOUNT"))     // not whole base units
        assertNull(MintSupply8418.decodeLine8418("$mintA\t0\t6\t5\t9\tMINT_ACCOUNT"))          // zero supply
        assertNull(MintSupply8418.decodeLine8418("$mintA\t1000\t25\t5\t9\tMINT_ACCOUNT"))      // impossible decimals
        assertNull(MintSupply8418.decodeLine8418("$mintA\t1000\t6\t5\t0\tMINT_ACCOUNT"))       // never observed
        assertNull(MintSupply8418.decodeLine8418("$mintA\t1000\t6\t5\t9\tPROVIDER"))           // unknown source
    }

    @Test fun loadRestoresTheRecordWithItsSlotAndTime() {
        val a = Supply8418(mintA, BigInteger("1000000000000000"), 6, 900L, 10_000L, Source8418.TOKEN_SUPPLY)
        val b = Supply8418(mintB, BigInteger("5000"), 2, 0L, 11_000L, Source8418.DAS)
        val text = MintSupply8418.encodeLine8418(a) + "\n" + "garbage line\n" + MintSupply8418.encodeLine8418(b) + "\n"
        MintSupply8418.resetForTest8418()
        assertEquals(2, MintSupply8418.loadText8418(text, notifyMetrics = false))
        assertEquals(a, MintSupply8418.of8418(mintA))
        assertEquals(b, MintSupply8418.of8418(mintB))
        assertEquals(1e9, MintSupply8418.wholeOrZero8418(mintA), 0.0)
        assertEquals(50.0, MintSupply8418.wholeOrZero8418(mintB), 0.0)
        assertTrue(MintSupply8418.statusLine8418().contains("loadedFromDisk=2"))
    }

    @Test fun aFresherReadStillWinsOverALoadedRecord() {
        val stored = Supply8418(mintA, BigInteger("1000000000000000"), 6, 900L, 10_000L, Source8418.TOKEN_SUPPLY)
        MintSupply8418.loadText8418(MintSupply8418.encodeLine8418(stored), notifyMetrics = false)
        // A later slot of the same strength replaces it.
        val later = stored.copy(rawAmount = BigInteger("999000000000000"), slot = 950L, observedAtMs = 20_000L)
        assertTrue(MintSupply8418.accept8418(later, notifyMetrics = false))
        assertEquals(950L, MintSupply8418.of8418(mintA)!!.slot)
        // An older slot, or a weaker slotless source, never does.
        assertFalse(MintSupply8418.accept8418(stored.copy(slot = 800L, observedAtMs = 30_000L), notifyMetrics = false))
        assertFalse(MintSupply8418.accept8418(stored.copy(slot = 0L, observedAtMs = 40_000L, source = Source8418.DAS), notifyMetrics = false))
        assertEquals(later, MintSupply8418.of8418(mintA))
    }

    @Test fun aLoadArrivingAfterAFresherReadDoesNotOverwriteIt() {
        val fresh = Supply8418(mintA, BigInteger("2000"), 3, 1_000L, 50_000L, Source8418.MINT_ACCOUNT)
        assertTrue(MintSupply8418.accept8418(fresh, notifyMetrics = false))
        val older = fresh.copy(rawAmount = BigInteger("1500"), slot = 400L, observedAtMs = 9_000L)
        assertEquals(0, MintSupply8418.loadText8418(MintSupply8418.encodeLine8418(older), notifyMetrics = false))
        assertEquals(fresh, MintSupply8418.of8418(mintA))
        // What would be saved is exactly what is held.
        assertEquals(MintSupply8418.encodeLine8418(fresh) + "\n", MintSupply8418.encodeAll8418())
    }
}
