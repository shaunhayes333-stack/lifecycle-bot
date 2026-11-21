package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PoolLock8895
import io.github.novacrypto.base58.Base58
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8925 — the LP lock read on chain where 8895 could not: a Meteora DAMM v2 pool's vesting locks (DBC's
 * lock_inner_position, and external Vesting accounts) held past the hold, and a Raydium CPMM pool's burned LP plus the LP the
 * Burn & Earn authority holds. 70 % still proves; less, or unreadable, stays unproven.
 */
class Aate8925ChainLockTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val mint = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val pool = "HLnpSz9h2S4hiLQ43rnSD9XkcUThA7B8hQMKmDaiTLcC"
    private val posAddr = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
    private val lpMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val wsol = "So11111111111111111111111111111111111111112"
    private val lockAuth = "3f7GcQFG397GAaEnv51zR6tsTVihYRydnydDD1cXekxH"

    @Before fun setUp() = PoolLock8895.resetForTest8895()
    @After fun tearDown() = PoolLock8895.resetForTest8895()

    private fun put(b: ByteArray, off: Int, key: String) = Base58.base58Decode(key).copyInto(b, off)
    private fun putLe(b: ByteArray, off: Int, v: Long, len: Int) { val x = BigInteger.valueOf(v); for (i in 0 until len) b[off + i] = x.shiftRight(8 * i).toInt().toByte() }
    private fun disc(b: ByteArray, d: IntArray) { for (i in 0 until 8) b[i] = d[i].toByte() }
    private fun vest(b: ByteArray, off: Int, cliff: Long, freq: Long, cliffUnlock: Long, per: Long, released: Long, n: Int) {
        putLe(b, off, cliff, 8); putLe(b, off + 8, freq, 8); putLe(b, off + 16, cliffUnlock, 16)
        putLe(b, off + 32, per, 16); putLe(b, off + 48, released, 16); putLe(b, off + 64, n.toLong(), 2)
    }
    private fun position(permanent: Long, vested: Long, cliff: Long = 0L, cliffUnlock: Long = 0L, ofPool: String = pool) =
        ByteArray(PoolLock8895.DAMM_V2_POSITION_SIZE_8925).also { x ->
            disc(x, PoolLock8895.DAMM_V2_POSITION_DISC_8925); put(x, 8, ofPool)
            putLe(x, 168, vested, 16); putLe(x, 184, permanent, 16)
            if (cliffUnlock > 0L) vest(x, 312, cliff, 0L, cliffUnlock, 0L, 0L, 0)
        }

    @Test fun aVestingScheduleHoldsWhatItHasNotUnlockedByThePoint() {
        val cliffOnly = PoolLock8895.Vesting8925(1_000L, 0L, BigInteger.valueOf(500L), BigInteger.ZERO, BigInteger.ZERO, 0)
        assertEquals(BigInteger.valueOf(500L), PoolLock8895.heldAt8925(cliffOnly, 999L))
        assertEquals(BigInteger.ZERO, PoolLock8895.heldAt8925(cliffOnly, 1_000L))
        val periodic = PoolLock8895.Vesting8925(1_000L, 100L, BigInteger.valueOf(100L), BigInteger.valueOf(50L), BigInteger.valueOf(100L), 4)
        assertEquals("before the cliff: all of it", BigInteger.valueOf(300L), PoolLock8895.heldAt8925(periodic, 999L))
        assertEquals(BigInteger.valueOf(200L), PoolLock8895.heldAt8925(periodic, 1_000L))
        assertEquals("one whole period on", BigInteger.valueOf(150L), PoolLock8895.heldAt8925(periodic, 1_150L))
        assertEquals("past the last period", BigInteger.ZERO, PoolLock8895.heldAt8925(periodic, 9_999L))
        assertEquals("not yet released", BigInteger.valueOf(200L), PoolLock8895.remaining8925(periodic))
    }

    @Test fun aDbcGraduatedPoolVestedPastTheHoldIsLocked() {
        val p = PoolLock8895.dammPosition8925(pool, posAddr, position(permanent = 200_000L, vested = 600_000L, cliff = 10_000L, cliffUnlock = 600_000L))!!
        val open = PoolLock8895.dammPosition8925(pool, "open", position(permanent = 0L, vested = 0L))!!
        val liquidity = BigInteger.valueOf(1_000_000L)
        assertEquals("permanent + vesting held past the hold", 80.0, PoolLock8895.dammLockedPct8925(liquidity, listOf(p, open), emptyMap(), 9_999L)!!, 1e-9)
        assertEquals("the cliff falls inside the hold: only the permanent part", 20.0, PoolLock8895.dammLockedPct8925(liquidity, listOf(p, open), emptyMap(), 10_000L)!!, 1e-9)
        val over = PoolLock8895.dammPosition8925(pool, posAddr, position(permanent = 0L, vested = 300_000L, cliff = 10_000L, cliffUnlock = 600_000L))!!
        assertEquals("never more than the position's remaining vested liquidity", 30.0, PoolLock8895.dammLockedPct8925(liquidity, listOf(over), emptyMap(), 0L)!!, 1e-9)
        val ext = ByteArray(PoolLock8895.DAMM_V2_VESTING_SIZE_8925).also { x ->
            disc(x, PoolLock8895.DAMM_V2_VESTING_DISC_8925); put(x, 8, posAddr); vest(x, 40, 50_000L, 0L, 500_000L, 0L, 0L, 0)
        }
        val v = PoolLock8895.dammVesting8925(posAddr, ext)!!
        val noInner = PoolLock8895.dammPosition8925(pool, posAddr, position(permanent = 300_000L, vested = 500_000L))!!
        assertNull(noInner.inner)
        assertEquals("an external Vesting account counts too", 80.0,
            PoolLock8895.dammLockedPct8925(liquidity, listOf(noInner), mapOf(posAddr to listOf(v)), 0L)!!, 1e-9)
        assertNull("a vesting of another position", PoolLock8895.dammVesting8925("other", ext))
        assertNull("a position of another pool", PoolLock8895.dammPosition8925(pool, posAddr, position(1L, 0L, ofPool = posAddr)))
        assertNull("more locked than the pool holds is a misread", PoolLock8895.dammLockedPct8925(BigInteger.valueOf(10L), listOf(p), emptyMap(), 0L))
        val poolAcct = ByteArray(PoolLock8895.DAMM_V2_SIZE_8895).also { x -> disc(x, PoolLock8895.DAMM_V2_POOL_DISC_8895); x[480] = 1 }
        assertEquals(1, PoolLock8895.dammActivationType8925(poolAcct))
    }

    @Test fun aCpmmPoolsBurnedAndBurnAndEarnLpIsLocked() {
        val acct = ByteArray(PoolLock8895.CPMM_POOL_SIZE_8925).also { x ->
            disc(x, PoolLock8895.CPMM_POOL_DISC_8925); put(x, 136, lpMint); put(x, 168, wsol); put(x, 200, mint); putLe(x, 333, 1_000L, 8)
        }
        val (lp, supply) = PoolLock8895.cpmmPool8925(mint, acct)!!
        assertEquals(lpMint, lp); assertEquals(BigInteger.valueOf(1_000L), supply)
        assertNull("another coin's pool", PoolLock8895.cpmmPool8925(posAddr, acct))
        assertNull("wrong discriminator", PoolLock8895.cpmmPool8925(mint, acct.copyOf().also { it[0] = 0 }))
        // 600 of 1,000 LP burned (mint supply 400), 200 held by the Burn & Earn authority: 80 %
        assertEquals(80.0, PoolLock8895.cpmmLockedPct8925(BigInteger.valueOf(1_000L), BigInteger.valueOf(400L), BigInteger.valueOf(200L))!!, 1e-9)
        assertEquals("nothing burned, nothing locked", 0.0, PoolLock8895.cpmmLockedPct8925(BigInteger.valueOf(1_000L), BigInteger.valueOf(1_000L), BigInteger.ZERO)!!, 1e-9)
        assertNull("a mint supply past lp_supply is a misread", PoolLock8895.cpmmLockedPct8925(BigInteger.valueOf(1_000L), BigInteger.valueOf(1_001L), BigInteger.ZERO))
        assertNull("a vault past the supply is a misread", PoolLock8895.cpmmLockedPct8925(BigInteger.valueOf(1_000L), BigInteger.valueOf(400L), BigInteger.valueOf(401L)))
        val ta = ByteArray(165).also { x -> put(x, 0, lpMint); put(x, 32, lockAuth); putLe(x, 64, 200L, 8) }
        val (m, owner, amount) = PoolLock8895.tokenAccount8925(ta)!!
        assertEquals(lpMint, m); assertEquals(lockAuth, owner); assertEquals(BigInteger.valueOf(200L), amount)
        assertEquals(BigInteger.valueOf(400L), PoolLock8895.mintSupply8925(ByteArray(82).also { putLe(it, 36, 400L, 8) }))
    }

    @Test fun theReaderDispatchesBothAndTheRuleIsUnchanged() {
        val s = src("engine/truth/PoolLock8895.kt")
        assertTrue(s.contains("else readDammVesting8925(mint, pool, data!!, pct)"))
        assertTrue(s.contains("CPMM_PROGRAM_8925 -> readCpmm8925(mint, pool, data)"))
        assertTrue(s.contains("private const val CPMM_LOCK_AUTH_8925 = \"3f7GcQFG397GAaEnv51zR6tsTVihYRydnydDD1cXekxH\""))
        assertTrue(s.contains("internal const val MIN_LOCKED_PCT_8895 = 70.0"))
        assertTrue(PoolLock8895.statusLine8895().contains("Raydium CPMM LP burned + Burn & Earn (8925)"))
        assertNotNull(PoolLock8895.proves8895(PoolLock8895.Lock8895(mint, pool, PoolLock8895.VENUE_CPMM_8925, 70.0, 1L, "x"), mint, pool, 1L).takeIf { it })
        assertTrue(!PoolLock8895.proves8895(PoolLock8895.Lock8895(mint, pool, PoolLock8895.VENUE_CPMM_8925, 69.9, 1L, "x"), mint, pool, 1L))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 113. The LP lock read on chain: DAMM v2 vesting, Raydium CPMM (8925)"))
    }
}
