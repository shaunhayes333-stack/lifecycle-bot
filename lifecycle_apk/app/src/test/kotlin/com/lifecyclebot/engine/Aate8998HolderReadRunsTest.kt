package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.LpStateRecord8725
import com.lifecyclebot.engine.truth.PoolLock8895
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8998 — the holder read of an LP-blocked coin's pool runs on its own (8997 read 0 pools: it only rode a lock read that
 * had already happened), on the pool the LP rule blamed, and the coin is filed by the read's answer or UNREAD after a minute,
 * whether or not it ever comes back to intake.
 */
class Aate8998HolderReadRunsTest {
    private val mint = "So11111111111111111111111111111111111111112"
    private val deep = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
    private val shallow = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
    private val locked = "4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R"

    @Test fun theBlamedPoolIsTheDeepestUnlockedOneTheReportHolds() {
        val ts = TokenState(mint = mint, symbol = "T")
        ts.safety = SafetyReport(lpOwnership8522 = LiquidityOwnership8522.Evidence(mint, listOf(
            LiquidityOwnership8522.Pool(shallow, "meteora_dlmm", 0.0, 4_000.0),
            LiquidityOwnership8522.Pool(deep, "meteora_dlmm", 5.0, 40_000.0),
            LiquidityOwnership8522.Pool(locked, "raydium_cpmm", 100.0, 90_000.0),   // locked: not what the rule blamed
            LiquidityOwnership8522.Pool("Curve8998", "pump_fun", 0.0, 99_000.0))))   // a curve is not a pool to drain
        assertEquals(deep, LpStateRecord8725.blamedPool8998(ts))
        val bare = TokenState(mint = mint, symbol = "T", pairAddress = shallow)
        assertEquals("no report: the coin's own pair", shallow, LpStateRecord8725.blamedPool8998(bare))
    }

    @Test fun aReadThatCannotJudgeStillAnswersWithWhy() {
        val sp = PoolLock8895.Spread8997(mint, deep, 0, 0, Double.NaN, 1L, "NOT_DLMM")
        assertEquals("NOT_DLMM", sp.cls)
        assertEquals("SPREAD", PoolLock8895.Spread8997(mint, deep, 4, 6, 20.0, 1L).cls)
        assertEquals(64, PoolLock8895.SPREAD_MAX_POSITIONS_8998)
        assertEquals(60_000L, LpStateRecord8725.SPREAD_WAIT_MS_8997)
    }

    @Test fun wired() {
        fun src(f: String) = File("src/main/kotlin/com/lifecyclebot/engine/$f").readText()
        val lock = src("truth/PoolLock8895.kt")
        assertTrue(lock.contains("requestSpread8998(mint.trim(), p, nowMs)"))
        assertTrue(lock.contains("try { LpStateRecord8725.spreadReady8998(sp.mint, sp.atMs) } catch (_: Throwable) {}"))
        assertTrue(!lock.contains("spreadWanted8997"))
        val rec = src("truth/LpStateRecord8725.kt")
        assertTrue(rec.contains("if (pending8998.remove(mint, p)) file8998(p.ts, p.band, sp.cls)"))
        assertTrue(rec.contains("file8998(p.ts, p.band, \"UNREAD\")"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 183. The holder read runs on its own (8998)"))
    }
}
