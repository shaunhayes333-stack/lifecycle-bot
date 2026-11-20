package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AgreementPocket8874
import com.lifecyclebot.engine.truth.LaneAuthority8577
import com.lifecyclebot.engine.truth.MarketCohort8701
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
 * V5.0.8895 — selection, additive only: (1) the LP lock of a Meteora DAMM v2 / DLMM pool is read from its own accounts and a
 * 70 %+ lock answers an UNREAD LP proof (nothing else changes; unlocked stays unbought); (2) the agreement pocket is proven on
 * coins, each at its worst meme-lane label; (3) a carried coin's losing NO-SETUP cohort is answered, a named setup still
 * refuses; (4) the watchlist looks first at the origin x age x pool cohorts that pay; (5) the funnel names what a data
 * deferral of an evidence coin waited for.
 */
class Aate8895SelectionTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val mint = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val pool = "HLnpSz9h2S4hiLQ43rnSD9XkcUThA7B8hQMKmDaiTLcC"
    private val other = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"
    private val wsol = "So11111111111111111111111111111111111111112"
    private val now = 50_000_000L

    @Before fun setUp() { PoolLock8895.resetForTest8895(); AgreementPocket8874.resetForTest8874(); DecisionEpisode8535.clearForTest8535() }
    @After fun tearDown() { PoolLock8895.resetForTest8895(); AgreementPocket8874.resetForTest8874(); DecisionEpisode8535.clearForTest8535() }

    private fun put(b: ByteArray, off: Int, key: String) = Base58.base58Decode(key).copyInto(b, off)
    private fun putLe(b: ByteArray, off: Int, v: BigInteger, len: Int) { for (i in 0 until len) b[off + i] = v.shiftRight(8 * i).toInt().toByte() }
    private fun putI32(b: ByteArray, off: Int, v: Int) { for (i in 0 until 4) b[off + i] = (v ushr (8 * i)).toByte() }
    private fun disc(b: ByteArray, d: IntArray) { for (i in 0 until 8) b[i] = d[i].toByte() }

    private fun damm(a: String = mint, b: String = wsol, liquidity: Long = 1_000_000L, locked: Long = 800_000L) =
        ByteArray(PoolLock8895.DAMM_V2_SIZE_8895).also { x ->
            disc(x, PoolLock8895.DAMM_V2_POOL_DISC_8895); put(x, 168, a); put(x, 200, b)
            putLe(x, 360, BigInteger.valueOf(liquidity), 16); putLe(x, 552, BigInteger.valueOf(locked), 16)
        }

    @Test fun aDammV2PoolsPermanentLockIsReadFromItsOwnAccount() {
        assertEquals(80.0, PoolLock8895.dammV2LockedPct8895(mint, damm())!!, 1e-9)
        assertEquals("either side may be the coin", 80.0, PoolLock8895.dammV2LockedPct8895(mint, damm(a = wsol, b = mint))!!, 1e-9)
        assertNull("another coin's pool proves nothing", PoolLock8895.dammV2LockedPct8895(other, damm()))
        assertNull("the other side must be a quote mint", PoolLock8895.dammV2LockedPct8895(mint, damm(b = other)))
        assertNull("more locked than exists is a misread", PoolLock8895.dammV2LockedPct8895(mint, damm(locked = 2_000_000L)))
        assertNull("empty pool", PoolLock8895.dammV2LockedPct8895(mint, damm(liquidity = 0L, locked = 0L)))
        assertNull("wrong discriminator", PoolLock8895.dammV2LockedPct8895(mint, damm().also { it[0] = 0 }))
        assertNull("short account", PoolLock8895.dammV2LockedPct8895(mint, damm().copyOf(600)))
    }

    private fun pair(type: Int = 1) = ByteArray(PoolLock8895.DLMM_PAIR_MIN_SIZE_8895).also { x ->
        disc(x, PoolLock8895.DLMM_PAIR_DISC_8895); x[86] = type.toByte(); put(x, 88, mint); put(x, 120, wsol)
    }

    private fun position(lower: Int, shares: List<Long>, lock: BigInteger, ofPair: String = pool): ByteArray {
        val extra = (shares.size - PoolLock8895.DLMM_BASE_BINS_8895).coerceAtLeast(0)
        return ByteArray(PoolLock8895.DLMM_POSITION_BASE_8895 + extra * PoolLock8895.DLMM_BIN_ENTRY_8895).also { x ->
            disc(x, PoolLock8895.DLMM_POSITION_DISC_8895); put(x, 8, ofPair)
            shares.forEachIndexed { i, s ->
                val off = if (i < 70) 72 + 16 * i else PoolLock8895.DLMM_POSITION_BASE_8895 + (i - 70) * PoolLock8895.DLMM_BIN_ENTRY_8895
                putLe(x, off, BigInteger.valueOf(s), 16)
            }
            putI32(x, 7912, lower); putI32(x, 7916, lower + shares.size - 1); putLe(x, 7992, lock, 8)
        }
    }

    @Test fun aDlmmPairsLockIsTheSmallestLockedShareOfAnyBin() {
        assertEquals(1, PoolLock8895.dlmmActivationType8895(mint, pair(1)))
        assertEquals(0, PoolLock8895.dlmmActivationType8895(mint, pair(0)))
        assertNull(PoolLock8895.dlmmActivationType8895(other, pair()))
        val min = 1_000_000L
        val locked = PoolLock8895.dlmmPosition8895(pool, position(100, listOf(80L, 50L), BigInteger.valueOf(min + 1)))!!
        val open = PoolLock8895.dlmmPosition8895(pool, position(100, listOf(20L, 0L), BigInteger.ZERO))!!
        // bin 100: 80 of 100 locked; bin 101: 50 of 50 — at least 80 % of every bin stays
        assertEquals(80.0, PoolLock8895.dlmmLockedPct8895(listOf(locked, open), min)!!, 1e-9)
        assertEquals("a lock that releases inside the margin is no lock", 0.0,
            PoolLock8895.dlmmLockedPct8895(listOf(locked.copy(lockReleasePoint = min)), min)!!, 1e-9)
        assertNull("no liquidity, nothing to prove", PoolLock8895.dlmmLockedPct8895(listOf(open.copy(shares = listOf(BigInteger.ZERO))), min))
        assertNull("a position of another pair", PoolLock8895.dlmmPosition8895(pool, position(100, listOf(1L), BigInteger.ONE, ofPair = other)))
        // an extended position: bins past the first 70 come from the appended entries
        val wide = PoolLock8895.dlmmPosition8895(pool, position(0, List(72) { if (it >= 70) 7L else 1L }, BigInteger.ONE))!!
        assertEquals(72, wide.shares.size)
        assertEquals(BigInteger.valueOf(7L), wide.shares[71])
        // a lock "forever" (u64 max) is held at Long.MAX_VALUE, never wrapped negative
        val forever = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        assertEquals(Long.MAX_VALUE, PoolLock8895.dlmmPosition8895(pool, position(5, listOf(1L), forever))!!.lockReleasePoint)
        assertEquals("slot clock: 24 h at 0.4 s", 1_000L + 216_000L, PoolLock8895.minReleasePoint8895(0, 1_000L, 9L))
        assertEquals("time clock: 24 h", 9L + 86_400L, PoolLock8895.minReleasePoint8895(1, 1_000L, 9L))
    }

    private fun report(vararg pools: LiquidityOwnership8522.Pool, unlocked: Boolean = false) = SafetyReport(
        checkedAt = now - 1_000L, rugcheckScore = 99, rugcheckStatus = "CONFIRMED", evaluatedForPaper8522 = false,
        lpOwnership8522 = LiquidityOwnership8522.Evidence(mint, pools.toList(), unlocked, true))

    @Test fun aChainLockOnlyEverTurnsAnUnreadLpProofIntoProven() {
        val unread = report(LiquidityOwnership8522.Pool(pool, "meteora_damm_v2", -1.0, 30_000.0))
        fun assess(r: SafetyReport, lock: PoolLock8895.Lock8895?) =
            LiquidityOwnership8522.assess(r, pool, false, nowMs = now, expectedMint = mint, chainLock8895 = lock)
        val lock80 = PoolLock8895.Lock8895(mint, pool, PoolLock8895.VENUE_DAMM_V2_8895, 80.0, now - 2_000L, "permanent/liquidity")
        assertEquals("as before without a read", LiquidityOwnership8522.Status.PENDING, assess(unread, null).status)
        val proven = assess(unread, lock80)
        assertEquals(LiquidityOwnership8522.Status.PROVEN, proven.status)
        assertEquals(PoolLock8895.PROVEN_REASON_8895, proven.reason)
        assertEquals("under 70 % is no proof: still waiting, never bought", LiquidityOwnership8522.Status.PENDING,
            assess(unread, lock80.copy(lockedPct = 60.0)).status)
        assertEquals("another pool's lock proves nothing", LiquidityOwnership8522.Status.PENDING, assess(unread, lock80.copy(pool = other)).status)
        assertEquals("another coin's read proves nothing", LiquidityOwnership8522.Status.PENDING, assess(unread, lock80.copy(mint = other)).status)
        assertEquals("a read older than 60 s proves nothing", LiquidityOwnership8522.Status.PENDING, assess(unread, lock80.copy(atMs = now - 61_000L)).status)
        assertEquals("a provider report of unlocked liquidity still refuses", LiquidityOwnership8522.Status.BLOCKED,
            assess(report(LiquidityOwnership8522.Pool(pool, "meteora_damm_v2", -1.0, 30_000.0), unlocked = true), lock80).status)
        assertEquals("a provider's under-70 % reading still refuses", LiquidityOwnership8522.Status.BLOCKED,
            assess(report(LiquidityOwnership8522.Pool(pool, "meteora_damm_v2", 40.0, 30_000.0)), lock80).status)
        PoolLock8895.putForTest8895(lock80)
        assertNotNull(PoolLock8895.proof8895(mint, pool, now))
        assertNull(PoolLock8895.proof8895(mint, pool, now + 70_000L))
        val o = src("engine/LiquidityOwnership8522.kt")
        assertTrue("every BLOCKED is decided before the chain read is consulted",
            o.indexOf("return Result(Status.BLOCKED, \"LP_UNLOCKED_CONFIRMED_8522\"") in 0 until o.indexOf("PoolLock8895.proves8895(chainLock8895,") &&
                o.indexOf("reportedLocked=\${report.lpLockPct}% required=\$MIN_LOCKED_PCT%") in 0 until o.indexOf("PoolLock8895.proves8895(chainLock8895,"))
        assertTrue(src("engine/PreTradeHardGate.kt").contains("chainLock8895 = try { com.lifecyclebot.engine.truth.PoolLock8895.proof8895(mint, selectedPool8522, now) } catch (_: Throwable) { null },"))
        assertTrue(src("engine/PreTradeHardGate.kt").contains("com.lifecyclebot.engine.truth.PoolLock8895.request8895(mint, selectedPool8522, now)"))
        // V5.0.8896 — a chain-proven lock is re-read while it holds, so the 60 s proof does not lapse between looks
        assertTrue(src("engine/PreTradeHardGate.kt").contains("ownership8522.reason == com.lifecyclebot.engine.truth.PoolLock8895.PROVEN_REASON_8895) && selectedPool8522.isNotBlank())"))
        assertTrue(src("engine/BuyRouteSafety8522.kt").contains("chainLock8895 = try { com.lifecyclebot.engine.truth.PoolLock8895.proof8895(mint, pool, nowMs) } catch (_: Throwable) { null })"))
        assertTrue("the unread-LP explore path stays off (owner, 8736 and 20 Nov)", src("engine/truth/ExploreLive8733.kt").contains("private const val ENABLED_8736 = false"))
        assertFalse("PoolLock8895 never blocks", src("engine/truth/PoolLock8895.kt").contains("Status.BLOCKED"))
    }

    @Test fun thePocketIsProvenOnCoinsEachAtItsWorstLaneLabel() {
        repeat(40) { i -> AgreementPocket8874.addCoinForTest8895("MOONSHOT", "C$i", if (i % 2 == 0) 80.0 else -10.0, 1_000L + i * 1_000_000L) }
        // the same coin read worse by another meme lane inside 15 minutes is held at that worse label
        AgreementPocket8874.addCoinForTest8895("EXPRESS", "C0", -20.0, 1_000L + 60_000L)
        // a non-meme lane files nothing
        AgreementPocket8874.addCoinForTest8895("CRYPTO_CORE", "X1", 500.0, 2_000L)
        val r = AgreementPocket8874.coinRecord8895()
        assertEquals(40.0, r[0], 0.0)
        assertEquals("19 x 80 + 20 x -10 + 1 x -20", 19 * 80.0 - 200.0 - 20.0, r[1], 1e-9)
        val e = AgreementPocket8874.coinEvidenceOf8895("SHITCOIN", null, r)
        assertNotNull("proven on coins, carried by a meme lane with no record of its own", e)
        assertEquals(AgreementPocket8874.KIND_8874, e!!.kind)
        assertEquals(AgreementPocket8874.COIN_ID_8895, e.id)
        assertTrue(com.lifecyclebot.engine.truth.CandidateAdmission8136.supported8136(e, 3.0))
        val losingLane = doubleArrayOf(40.0, -400.0, 40.0 * 100.0, 0.0)   // mean -10 %, no spread: proven losing
        assertTrue(AgreementPocket8874.losing8895(losingLane))
        assertNull("a lane whose own pocket record proves losing does not carry it", AgreementPocket8874.coinEvidenceOf8895("SHITCOIN", losingLane, r))
        assertNull("crypto lanes are not meme lanes", AgreementPocket8874.coinEvidenceOf8895("CRYPTO_CORE", null, r))
        assertNull("under 30 coins proves nothing", AgreementPocket8874.coinEvidenceOf8895("SHITCOIN", null, doubleArrayOf(20.0, 600.0, 36_000.0, 0.0)))
        assertTrue(AgreementPocket8874.statusLine8874().contains("coins8895{n40/"))
        val p = src("engine/truth/AgreementPocket8874.kt")
        assertTrue(p.contains("else (evidenceOf8874(key, recordOf(key)) ?: coinEvidence8895(raw.lane, recordOf(key)))"))
    }

    @Test fun theNoSetupCohortIsAnsweredForACarriedCoinOnly() {
        assertTrue(LaneAuthority8577.residualCohort8895("NO_TRIGGER"))
        assertTrue(LaneAuthority8577.residualCohort8895("OUTSIDE_ENVELOPE_8383"))
        assertFalse("a named losing setup still refuses", LaneAuthority8577.residualCohort8895("RS_LEADER"))
        assertFalse(LaneAuthority8577.residualCohort8895(null))
        val lane = src("engine/truth/LaneAuthority8577.kt")
        val fn = lane.substringAfter("fun memeEntryRefusal8626(").substringBefore("\n    }\n")
        assertTrue(fn.contains("if (why == LIVE_SETUP_LOSES_8714 && residualAnswered8895(ts, lane, nowMs, pocket8874)) return null"))
        val answered = lane.substringAfter("private fun residualAnswered8895(").substringBefore("\n    }\n")
        assertTrue("only a carried coin", answered.contains("if (!(pocket8874 || (try { PayingCell8723.pays8723(ts, lane, nowMs) } catch (_: Throwable) { false }))) return false"))
    }

    @Test fun theWatchlistLooksFirstWhereTheCohortPays() {
        val means = mapOf("A" to -14.1, "B" to -3.1, "C" to 31.7)
        assertEquals(1.0, MarketCohort8701.shareOf8895(means, "C")!!, 1e-9)
        assertEquals(0.0, MarketCohort8701.shareOf8895(means, "A")!!, 1e-9)
        assertEquals("ungraded sits in the middle", 0.5, MarketCohort8701.shareOf8895(means, "Z")!!, 1e-9)
        assertNull(MarketCohort8701.shareOf8895(mapOf("A" to 1.0), "A"))
        assertTrue(src("engine/BotService.kt").contains(
            "if (cohortShare8895 != null) priority += cohortShare8895 * com.lifecyclebot.engine.truth.TrajectoryVerdict8855.PRIORITY_SPAN_8855"))
    }

    @Test fun aDataDeferralOfAnEvidenceCoinNamesWhatItWaitedFor() {
        DecisionEpisode8535.noteAdmission8535("MOONSHOT", "M1", 1L, "DEFER_DATA", "DATA_INCOMPLETE_DEFER_8136", null,
            listOf("gross=+7.6 cost=+1.9 net=+5.7", "missing=price"), 1_000L, evidence8876 = "MARKET_CELL")
        DecisionEpisode8535.noteAdmission8535("MOONSHOT", "M1", 1L, "DEFER_DATA", "DATA_INCOMPLETE_DEFER_8136", null,
            listOf("missing=price"), 1_100L, evidence8876 = "MARKET_CELL")
        val line = DecisionEpisode8535.funnelLine8876()
        assertTrue(line, line.contains("MARKET_CELL{COINS=1 DEFER_DATA=1 why{DEFER_DATA:DATA_INCOMPLETE_DEFER_8136=1} wait{PRICE_STALE=1}}"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("com.lifecyclebot.engine.truth.PoolLock8895.statusLine8895()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 84. Selection, additive: the lock read on chain, the pocket on coins (8895)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 85. 8895's test compiles; a chain-proven lock stays fresh (8896)"))
    }
}
