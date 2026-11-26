package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ConcentratedLock9032
import com.lifecyclebot.engine.truth.CurveReserves7951
import com.lifecyclebot.engine.truth.OnChainDepth8038
import com.lifecyclebot.engine.truth.PumpFunCoin9032
import io.github.novacrypto.base58.Base58
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.9032 — owner, 26 Nov 21:56: the LP rule is answered for a pump.fun coin that is not Mayhem, and the LP reader reads
 * the concentrated pools (Orca Whirlpool, Raydium CLMM).
 */
class Aate9032PumpLpAndPoolsTest {
    private val now = 30_000_000L
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val pumpProgram = com.lifecyclebot.network.SolanaSwapEvidence8506.PUMP

    private fun report(mint: String, vararg pools: LiquidityOwnership8522.Pool, unlocked: Boolean = false) = SafetyReport(
        checkedAt = now - 1_000L, rugcheckScore = 99, rugcheckStatus = "CONFIRMED", evaluatedForPaper8522 = false,
        lpOwnership8522 = LiquidityOwnership8522.Evidence(mint, pools.toList(), unlocked, true))

    @Test fun theSuffixAloneProvesNothingAndMayhemIsNotOpened() {
        assertTrue(PumpFunCoin9032.byConstruction9032(true, true, 0.0))             // supply not read yet: Mayhem has its own door
        assertTrue(PumpFunCoin9032.byConstruction9032(true, true, 1.0e9))
        assertFalse("Mayhem: two billion supply", PumpFunCoin9032.byConstruction9032(true, true, 2.0e9))
        assertFalse("ends in pump, never made by the program", PumpFunCoin9032.byConstruction9032(true, false, 1.0e9))
        assertFalse(PumpFunCoin9032.byConstruction9032(false, true, 1.0e9))
        assertTrue(PumpFunCoin9032.curveAccountIsPumps9032(true, pumpProgram))
        assertFalse(PumpFunCoin9032.curveAccountIsPumps9032(true, "11111111111111111111111111111111"))
        assertFalse(PumpFunCoin9032.curveAccountIsPumps9032(false, ""))
        PumpFunCoin9032.resetForTest9032()
        assertFalse(PumpFunCoin9032.programKnows9032("NeverSeen9032AAAAAAAAAAAAAAAAAAAAAAAAAAApump"))
        val seen = "SeenCurve9032AAAAAAAAAAAAAAAAAAAAAAAAAAApump"
        CurveReserves7951.noteFrame7951(seen, 34.0, 1_000_000.0, now - 9L * 60L * 60_000L)   // seen on the program's stream nine hours ago
        assertTrue(PumpFunCoin9032.programKnows9032(seen))
        PumpFunCoin9032.rememberForTest9032("NotAPumpMint9032AAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertFalse(PumpFunCoin9032.programKnows9032("NotAPumpMint9032AAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertTrue(PumpFunCoin9032.line9032().contains("the suffix alone proves nothing"))
        assertTrue(PumpFunCoin9032.ABSENT_RETRY_MS_9032 > PumpFunCoin9032.ASK_GAP_MS_9032 && PumpFunCoin9032.MAX_PROVEN_9032 >= 1_000)
    }

    @Test fun aPumpFunCoinsLpRuleIsProvenWhateverAPoolReports() {
        PumpFunCoin9032.resetForTest9032()
        val coin = "OpenedUp9032AAAAAAAAAAAAAAAAAAAAAAAAAAAApump"
        val unlocked = report(coin, LiquidityOwnership8522.Pool("main", "raydium", 0.0, 10_000.0), unlocked = true)
        val unread = report(coin, LiquidityOwnership8522.Pool("main", "pump_fun_amm", -1.0, 10_000.0))
        fun ask(r: SafetyReport) = LiquidityOwnership8522.assess(r, "main", false, nowMs = now, expectedMint = coin)
        // before the program is known to have made it: exactly as before
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, ask(unlocked).status)
        assertEquals(LiquidityOwnership8522.Status.PENDING, ask(unread).status)
        PumpFunCoin9032.rememberForTest9032(coin)
        assertEquals(LiquidityOwnership8522.Status.PROVEN, ask(unlocked).status)
        assertEquals(PumpFunCoin9032.REASON_9032, ask(unlocked).reason)
        assertEquals(LiquidityOwnership8522.Status.PROVEN, ask(unread).status)
        // a coin that is not a pump.fun coin keeps the 70 % rule
        val other = "OtherCoin9032AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        PumpFunCoin9032.rememberForTest9032(other)
        assertEquals(LiquidityOwnership8522.Status.BLOCKED, LiquidityOwnership8522.assess(
            report(other, LiquidityOwnership8522.Pool("main", "raydium", 69.9, 10_000.0)), "main", false, nowMs = now, expectedMint = other).status)
        PumpFunCoin9032.resetForTest9032()
        val own = src("engine/LiquidityOwnership8522.kt")
        assertTrue("an answer already proven keeps its own reason", own.contains("if (r.status == Status.PROVEN) return r"))
        val safety = src("engine/TokenSafetyChecker.kt")
        assertTrue(safety.contains("pumpLpAnswers9032(mint, \"LP_LOCK_BAND_NOT_APPLIED_PUMP_FUN_9032\") -> Unit"))
        assertTrue(safety.contains("if (lpUnlockedRisk && pumpLpAnswers9032(mint, \"LP_UNLOCKED_FLAG_ANSWERED_PUMP_FUN_9032\")) lpUnlockedRisk = false"))
        val coinSrc = src("engine/truth/PumpFunCoin9032.kt")
        assertTrue("a proof read resumes a waiting ticket", coinSrc.contains("TicketRetry8327.onProofLanded8330(m)") && coinSrc.contains("SafetyRefreshQueue.request(m)"))
    }

    private fun le(v: BigInteger, len: Int): ByteArray { val o = ByteArray(len); var x = v; for (i in 0 until len) { o[i] = x.and(BigInteger.valueOf(255L)).toInt().toByte(); x = x.shiftRight(8) }; return o }
    private fun i32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun pos(id: String, lo: Int, hi: Int, liq: Long) = ConcentratedLock9032.Position9032(id, lo, hi, BigInteger.valueOf(liq))

    @Test fun theLockedShareOfAConcentratedPoolIsItsSmallestLockedFraction() {
        assertEquals(100.0, ConcentratedLock9032.lockedPct9032(listOf(pos("full", -443_636, 443_636, 1_000L)), setOf("full"))!!, 1e-6)
        // an unlocked position inside the locked range: half of that piece can leave
        assertEquals(50.0, ConcentratedLock9032.lockedPct9032(listOf(pos("full", -1_000, 1_000, 100L), pos("mine", 0, 10, 100L)), setOf("full"))!!, 1e-6)
        // an unlocked position outside the locked range: a piece of the price axis with nothing locked
        assertEquals(0.0, ConcentratedLock9032.lockedPct9032(listOf(pos("low", -1_000, 0, 100L), pos("high", 0, 1_000, 100L)), setOf("low"))!!, 1e-6)
        assertEquals(0.0, ConcentratedLock9032.lockedPct9032(listOf(pos("a", -10, 10, 5L)), emptySet())!!, 1e-6)
        assertNull(ConcentratedLock9032.lockedPct9032(listOf(pos("empty", -10, 10, 0L)), setOf("empty")))
        assertNull(ConcentratedLock9032.lockedPct9032(emptyList(), emptySet()))
    }

    @Test fun positionsAndPoolsAreReadAtTheProgramsOwnOffsets() {
        val liq = BigInteger("340282366920938463463374607431768211455")   // u128 max: never read signed
        val w = ConcentratedLock9032.whirlpoolPosition9032("W1", le(liq, 16) + i32(-443_636) + i32(443_636))!!
        assertEquals(liq, w.liquidity); assertEquals(-443_636, w.lower); assertEquals(443_636, w.upper)
        val c = ConcentratedLock9032.clmmPosition9032("C1", i32(-120) + i32(240) + le(BigInteger.valueOf(77L), 16))!!
        assertEquals(BigInteger.valueOf(77L), c.liquidity); assertEquals(-120, c.lower); assertEquals(240, c.upper)
        assertNull(ConcentratedLock9032.whirlpoolPosition9032("W1", ByteArray(23)))
        assertNull(ConcentratedLock9032.clmmPosition9032("", ByteArray(24)))
        val coin = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val coinKey = Base58.base58Decode(coin); val sol = Base58.base58Decode(OnChainDepth8038.WSOL)
        val whirl = ByteArray(ConcentratedLock9032.WHIRLPOOL_SIZE_9032)
        ConcentratedLock9032.WHIRLPOOL_DISC_9032.forEachIndexed { i, b -> whirl[i] = b.toByte() }
        coinKey.copyInto(whirl, 101); sol.copyInto(whirl, 181)
        assertTrue(ConcentratedLock9032.whirlpoolPool9032(coin, whirl))
        assertFalse("another coin's pool", ConcentratedLock9032.whirlpoolPool9032("Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", whirl))
        assertFalse(ConcentratedLock9032.whirlpoolPool9032(coin, whirl.copyOf(600)))
        val clmm = ByteArray(ConcentratedLock9032.CLMM_POOL_MIN_SIZE_9032)
        intArrayOf(247, 237, 227, 245, 215, 195, 222, 70).forEachIndexed { i, b -> clmm[i] = b.toByte() }
        sol.copyInto(clmm, 73); coinKey.copyInto(clmm, 105)
        assertTrue(ConcentratedLock9032.clmmPool9032(coin, clmm))
        assertFalse(ConcentratedLock9032.clmmPool9032(coin, whirl))
        assertEquals(216, ConcentratedLock9032.WHIRLPOOL_POSITION_SIZE_9032); assertEquals(241, ConcentratedLock9032.WHIRLPOOL_LOCK_SIZE_9032)
        assertEquals(281, ConcentratedLock9032.CLMM_POSITION_SIZE_9032)
        assertEquals(72, ConcentratedLock9032.WHIRLPOOL_POSITION_SLICE_AT_9032); assertEquals(73, ConcentratedLock9032.CLMM_POSITION_SLICE_AT_9032)
        assertEquals(24, ConcentratedLock9032.POSITION_SLICE_LEN_9032); assertTrue(ConcentratedLock9032.MAX_POSITIONS_9032 >= 100)
        val reader = src("engine/truth/PoolLock8895.kt")
        assertTrue(reader.contains("OnChainDepth8038.ORCA_WHIRLPOOL_8418 -> readConcentrated9032(mint, pool, ConcentratedLock9032.whirlpoolPool9032(mint, data), whirlpool = true)"))
        assertTrue(reader.contains("OnChainDepth8038.RAYDIUM_CLMM_8418 -> readConcentrated9032(mint, pool, ConcentratedLock9032.clmmPool9032(mint, data), whirlpool = false)"))
        // Orca's lock record names its pool at 72 and its position at 8; Raydium's lock program names them at 41 and 73
        assertTrue(reader.contains(".put(at(72, pool)), 8, 32))"))
        assertTrue(reader.contains("gpa(ConcentratedLock9032.CLMM_LOCK_PROGRAM_9032, cfg(JSONArray().put(at(41, pool)), 73, 32))"))
        assertEquals("ORCA_WHIRLPOOL", ConcentratedLock9032.VENUE_WHIRLPOOL_9032); assertEquals("RAYDIUM_CLMM", ConcentratedLock9032.VENUE_CLMM_9032)
    }

    @Test fun theBuildIsRecorded() {
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## §217 — V5.0.9032: a pump.fun coin's LP is not a question; the concentrated pools are read"))
        assertTrue(File("../../.github/workflows/build.yml").readText().contains("--tests '*Aate9032*'"))
    }
}
