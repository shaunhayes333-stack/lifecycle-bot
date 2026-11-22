package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LpStateRecord8725
import com.lifecyclebot.engine.truth.PoolLock8895
import io.github.novacrypto.base58.Base58
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8937 — the LP lock is the pool's own: a PumpSwap pool's burned LP read on chain (pump.fun burns every LP token its
 * migration mints); a pool proven 70 %+ locked from its own accounts counts at that figure over a provider's lower one for the
 * same pool; and every LIVE coin only the LP rule keeps out is followed forward. The 70 % rule is unchanged.
 */
class Aate8937PoolsOwnLockTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val mint = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val pool = "HLnpSz9h2S4hiLQ43rnSD9XkcUThA7B8hQMKmDaiTLcC"
    private val side = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
    private val lpMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val wsol = "So11111111111111111111111111111111111111112"
    private val now = 1_800_000_000_000L

    @Before fun setUp() = PoolLock8895.resetForTest8895()
    @After fun tearDown() = PoolLock8895.resetForTest8895()

    private fun put(b: ByteArray, off: Int, key: String) = Base58.base58Decode(key).copyInto(b, off)
    private fun putLe(b: ByteArray, off: Int, v: Long, len: Int) { val x = BigInteger.valueOf(v); for (i in 0 until len) b[off + i] = x.shiftRight(8 * i).toInt().toByte() }

    private fun pumpSwapPool(base: String = mint, quote: String = wsol, lpSupply: Long = 4_000_000L) = ByteArray(244).also { b ->
        for (i in 0 until 8) b[i] = PoolLock8895.DAMM_V2_POOL_DISC_8895[i].toByte()   // Anchor sha256("account:Pool")
        put(b, 43, base); put(b, 75, quote); put(b, 107, lpMint); putLe(b, 203, lpSupply, 8)
    }

    @Test fun aPumpSwapPoolsBurnedLpIsReadFromItsOwnAccounts() {
        val (lp, supply) = PoolLock8895.pumpSwapPool8937(mint, pumpSwapPool())!!
        assertEquals(lpMint, lp)
        assertEquals(BigInteger.valueOf(4_000_000L), supply)
        assertNull("another coin's pool", PoolLock8895.pumpSwapPool8937(side, pumpSwapPool()))
        assertNull("not paired with a quote", PoolLock8895.pumpSwapPool8937(mint, pumpSwapPool(quote = side)))
        assertNull("too short", PoolLock8895.pumpSwapPool8937(mint, pumpSwapPool().copyOf(200)))
        // the migration burns every LP it mints: the LP mint's supply is 0, all of lp_supply is burned
        assertEquals(100.0, PoolLock8895.cpmmLockedPct8925(BigInteger.valueOf(4_000_000L), BigInteger.ZERO, BigInteger.ZERO)!!, 1e-9)
        // a later deposit of a quarter as much again: its depositor may withdraw it, the migrated 80 % never
        assertEquals(80.0, PoolLock8895.cpmmLockedPct8925(BigInteger.valueOf(5_000_000L), BigInteger.valueOf(1_000_000L), BigInteger.ZERO)!!, 1e-9)
        val lock = src("engine/truth/PoolLock8895.kt")
        assertTrue(lock.contains("PUMP_AMM_PROGRAM_8937 -> readPumpSwap8937(mint, pool, data)"))
        assertFalse("a pump.fun mint is read now", lock.contains("if (try { com.lifecyclebot.network.PumpCurveKeys7269.isPumpMint8658(mint) } catch (_: Throwable) { false }) return false"))
        assertFalse("PoolLock8895 never blocks", lock.contains("Status.BLOCKED"))
    }

    @Test fun aProvenHigherLockStandsAndALowerOneNeverBlocks() {
        assertEquals(100.0, LiquidityOwnership8522.lockedAt8937(0.0, 100.0), 0.0)
        assertEquals("the reader's lower figure never replaces the provider's", 90.0, LiquidityOwnership8522.lockedAt8937(90.0, 75.0), 0.0)
        assertEquals("an unproven chain figure (< 70 %) changes nothing", 10.0, LiquidityOwnership8522.lockedAt8937(10.0, 60.0), 0.0)
        assertEquals(-1.0, LiquidityOwnership8522.lockedAt8937(-1.0, null), 0.0)
        val l = PoolLock8895.Lock8895(mint, pool, PoolLock8895.VENUE_PUMPSWAP_8937, 100.0, now - 9L * 60_000L, "burned/lp_supply")
        PoolLock8895.putForTest8895(l)
        assertNotNull("a report may carry a burned lock up to 10 minutes old", PoolLock8895.reportProof8937(mint, pool, now))
        assertNull("the buy door keeps its 60 s proof", PoolLock8895.proof8895(mint, pool, now))
        assertNull(PoolLock8895.reportProof8937(mint, pool, now + 2L * 60_000L))
        assertNull("another coin", PoolLock8895.reportProof8937(side, pool, now))
        PoolLock8895.putForTest8895(l.copy(lockedPct = 50.0, atMs = now))
        assertNull("under 70 % proves nothing", PoolLock8895.reportProof8937(mint, pool, now))
    }

    private fun report(vararg pools: LiquidityOwnership8522.Pool, unlocked: Boolean = false, lpLockPct: Double = -1.0) = SafetyReport(
        checkedAt = now - 1_000L, rugcheckScore = 99, rugcheckStatus = "CONFIRMED", evaluatedForPaper8522 = false, lpLockPct = lpLockPct,
        lpOwnership8522 = LiquidityOwnership8522.Evidence(mint, pools.toList(), unlocked, true))

    @Test fun theBuyDoorTakesThePoolsOwnProofOverTheProvidersFigureForThatPool() {
        val lock = PoolLock8895.Lock8895(mint, pool, PoolLock8895.VENUE_PUMPSWAP_8937, 100.0, now - 2_000L, "burned/lp_supply")
        fun assess(r: SafetyReport, l: PoolLock8895.Lock8895?) =
            LiquidityOwnership8522.assess(r, pool, false, nowMs = now, expectedMint = mint, chainLock8895 = l)
        val main = LiquidityOwnership8522.Pool(pool, "pump_fun_amm", 0.0, 40_000.0)
        assertEquals("as before without a read", LiquidityOwnership8522.Status.BLOCKED, assess(report(main), null).status)
        val proven = assess(report(main, lpLockPct = 0.0), lock)
        assertEquals(LiquidityOwnership8522.Status.PROVEN, proven.status)
        assertEquals(PoolLock8895.PROVEN_REASON_8895, proven.reason)
        assertEquals("the named unlocked finding is answered by the only pool with depth", LiquidityOwnership8522.Status.PROVEN,
            assess(report(main, unlocked = true), lock).status)
        val sidePool = LiquidityOwnership8522.Pool(side, "raydium", 10.0, 50_000.0)
        assertEquals("the deeper side pool the chain has not proven still refuses", LiquidityOwnership8522.Status.BLOCKED,
            assess(report(main, sidePool), lock).status)
        assertEquals("and the named finding stands while any pool with depth is unproven", LiquidityOwnership8522.Status.BLOCKED,
            assess(report(main, sidePool.copy(lockedPct = -1.0), unlocked = true), lock).status)
        // the deeper (primary) pool proven by its own held read: no longer under the lock
        PoolLock8895.putForTest8895(PoolLock8895.Lock8895(mint, side, PoolLock8895.VENUE_CPMM_8925, 90.0, now - 1_000L, "burned+burnAndEarn/lp_supply"))
        assertEquals(LiquidityOwnership8522.Status.PROVEN, assess(report(main, sidePool), lock).status)
        assertTrue(LiquidityOwnership8522.allDepthPoolsProven8937(listOf(main, sidePool)) { it.address == pool || it.address == side })
        assertFalse("nothing with depth: nothing proven", LiquidityOwnership8522.allDepthPoolsProven8937(listOf(main.copy(depthUsd = 0.0))) { true })
    }

    @Test fun theReportCarriesTheProofAndTheBlockedCoinsAreFollowed() {
        val safety = src("engine/TokenSafetyChecker.kt")
        assertTrue(safety.contains("val locked = LiquidityOwnership8522.lockedAt8937(providerLocked8937, chain8937?.lockedPct)"))
        assertTrue(safety.contains("lpLockPct = if (lpOwnership8522.explicitUnlocked && !chainAll8937) minOf("))
        assertTrue(safety.contains(".forEach { com.lifecyclebot.engine.truth.PoolLock8895.request8895(mint, it.address) }"))
        assertTrue(src("engine/HardRugPreFilter.kt").contains("com.lifecyclebot.engine.truth.LpStateRecord8725.noteLpBlocked8937(ts, ts.safety.hardBlockReasons)"))
        assertEquals("LT30", LpStateRecord8725.lpOnlyBand8937(listOf("LP only 0% locked — EXTREME RUG RISK, devs can pull liquidity instantly")))
        assertEquals("30_70", LpStateRecord8725.lpOnlyBand8937(listOf("LP only 55% locked — needs ≥70% locked for live buy")))
        assertEquals("UNLOCKED_FLAG", LpStateRecord8725.lpOnlyBand8937(listOf("LP unlocked with low liquidity/holder/provider risk — live rug overlay block")))
        assertEquals("the lower band names a coin with both", "LT30", LpStateRecord8725.lpOnlyBand8937(listOf(
            "LP unlocked with low liquidity/holder/provider risk — live rug overlay block", "LP only 4% locked — EXTREME RUG RISK, devs can pull liquidity instantly")))
        assertNull("also refused for something else: not the LP rule's to measure",
            LpStateRecord8725.lpOnlyBand8937(listOf("LP only 0% locked — EXTREME RUG RISK, devs can pull liquidity instantly", "Mint authority active")))
        assertNull(LpStateRecord8725.lpOnlyBand8937(emptyList()))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 125. The LP lock is the pool's own (8937)"))
    }
}
