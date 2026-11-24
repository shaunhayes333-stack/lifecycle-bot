package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PoolLock8895
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8997 — who holds a Meteora DLMM pool, read from its own accounts: the largest share of the quote side one wallet can
 * remove. Measured and filed on the LP-blocked label; nothing is bought and the LP rule is unchanged.
 */
class Aate8997WhoHoldsThePoolTest {
    private val p = PoolLock8895
    private fun pos(lower: Int, shares: List<Long>, lock: Long = 0L) =
        PoolLock8895.Position8895(lower, lower + shares.size - 1, shares.map { BigInteger.valueOf(it) }, lock)
    private val quote = mapOf(10 to BigInteger.valueOf(600L), 11 to BigInteger.valueOf(400L))

    @Test fun theLargestShareOneWalletCanRemove() {
        // one wallet holds everything
        val (one, n1) = p.topOwnerQuotePct8997(listOf("A" to pos(10, listOf(100, 100))), quote, 1_000L)
        assertEquals(100.0, one, 1e-6); assertEquals(1, n1)
        assertEquals("ONE_HOLDER", p.spreadClass8997(n1, one))
        // four wallets, equal in both bins: each can remove a quarter
        val four = listOf("A", "B", "C", "D").map { it to pos(10, listOf(50, 50)) }
        val (q, n4) = p.topOwnerQuotePct8997(four, quote, 1_000L)
        assertEquals(25.0, q, 1e-6); assertEquals(4, n4)
        assertEquals("SPREAD", p.spreadClass8997(n4, q))
        // A holds 80 % of the bin with 600 and 20 % of the bin with 400: 0.8*600 + 0.2*400 = 560 of 1000
        val skew = listOf("A" to pos(10, listOf(80, 20)), "B" to pos(10, listOf(10, 40)), "C" to pos(10, listOf(10, 40)))
        val (s, n3) = p.topOwnerQuotePct8997(skew, quote, 1_000L)
        assertEquals(56.0, s, 1e-6); assertEquals(3, n3)
        assertEquals("CONCENTRATED", p.spreadClass8997(n3, s))
        // A's position is locked past the hold: it cannot leave, so the largest REMOVABLE share is B's or C's
        val locked = listOf("A" to pos(10, listOf(80, 20), lock = 5_000L), "B" to pos(10, listOf(10, 40)), "C" to pos(10, listOf(10, 40)))
        assertEquals(22.0, p.topOwnerQuotePct8997(locked, quote, 1_000L).first, 1e-6)   // 0.1*600 + 0.4*400 = 220
        // no quote in any bin a position covers: not judged
        assertTrue(p.topOwnerQuotePct8997(skew, mapOf(99 to BigInteger.TEN), 1_000L).first.isNaN())
        assertEquals("NO_QUOTE", p.spreadClass8997(3, Double.NaN))
        assertEquals("two wallets are never SPREAD", "CONCENTRATED", p.spreadClass8997(2, 30.0))
    }

    @Test fun theAccountsAreReadByTheirOwnLayout() {
        val pair = ByteArray(32) { 7 }
        val pairKey = io.github.novacrypto.base58.Base58.base58Encode(pair)
        val arr = ByteArray(PoolLock8895.DLMM_BIN_ARRAY_SIZE_8997)
        PoolLock8895.DLMM_BIN_ARRAY_DISC_8997.forEachIndexed { i, v -> arr[i] = v.toByte() }
        java.nio.ByteBuffer.wrap(arr, 8, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(-2L)        // index -2: bins -140 .. -71
        System.arraycopy(pair, 0, arr, 24, 32)
        fun put(bin: Int, x: Long, y: Long) {
            val off = 56 + 144 * bin
            java.nio.ByteBuffer.wrap(arr, off, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(x).putLong(y)
        }
        put(0, 5L, 900L); put(69, 7L, 0L)
        assertEquals(mapOf(-140 to BigInteger.valueOf(900L)), p.dlmmBinQuote8997(pairKey, arr, quoteIsY = true))
        assertEquals(mapOf(-140 to BigInteger.valueOf(5L), -71 to BigInteger.valueOf(7L)), p.dlmmBinQuote8997(pairKey, arr, quoteIsY = false))
        assertNull("another pair's array", p.dlmmBinQuote8997(io.github.novacrypto.base58.Base58.base58Encode(ByteArray(32) { 9 }), arr, true))
        assertNull(p.dlmmBinQuote8997(pairKey, arr.copyOf(100), true))
        // PositionV2: owner at 40
        val position = ByteArray(8_120); val owner = ByteArray(32) { 3 }
        System.arraycopy(owner, 0, position, 40, 32)
        assertEquals(io.github.novacrypto.base58.Base58.base58Encode(owner), p.dlmmOwner8997(position))
        // LbPair: token_x at 88, token_y at 120
        val wsol = io.github.novacrypto.base58.Base58.base58Decode("So11111111111111111111111111111111111111112")
        val lb = ByteArray(904)
        System.arraycopy(wsol, 0, lb, 120, 32); System.arraycopy(ByteArray(32) { 5 }, 0, lb, 88, 32)
        assertEquals(true, p.dlmmQuoteIsY8997(lb))
        val lbX = ByteArray(904)
        System.arraycopy(wsol, 0, lbX, 88, 32); System.arraycopy(ByteArray(32) { 5 }, 0, lbX, 120, 32)
        assertEquals(false, p.dlmmQuoteIsY8997(lbX))
        assertNull(p.dlmmQuoteIsY8997(ByteArray(904) { 5 }))
    }

    @Test fun aReadIsServedForTenMinutesAndOtherwiseAsked() {
        p.resetSpreadForTest8997()
        val now = 1_800_000_000_000L
        p.putSpreadForTest8997(PoolLock8895.Spread8997("MintA", "PoolA", 5, 9, 18.0, now))
        assertEquals("SPREAD", p.spreadFor8997("MintA", "PoolA", now + 60_000L)!!.cls)
        val sp = PoolLock8895.Spread8997("MintA", "PoolA", 5, 9, 18.0, now)
        assertTrue(p.fresh8997(sp, "MintA", now + 60_000L))
        assertTrue("another coin's read of that pool is not this coin's", !p.fresh8997(sp, "MintB", now + 60_000L))
        assertTrue("a read is kept ten minutes", !p.fresh8997(sp, "MintA", now + 11L * 60_000L))
        assertTrue(p.spreadLine8997().startsWith("poolSpread8997[read{"))
        p.resetSpreadForTest8997()
    }

    @Test fun wired() {
        fun src(f: String) = File("src/main/kotlin/com/lifecyclebot/engine/$f").readText()
        assertTrue(src("truth/LpStateRecord8725.kt").contains("\"\${PREFIX_8725}LP_BLOCKED:\$band/\$cls8997\""))
        assertTrue(src("truth/PoolLock8895.kt").contains("if (quoteIsY8997 != null && spreadWanted8997.remove(pool))"))
        assertTrue(src("DiagnosisBrief8438.kt").contains("PoolLock8895.spreadLine8997()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 182. Who holds the pool (8997)"))
    }
}
