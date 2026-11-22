package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PoolLock8895
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8938 — the buy door waives a provider's under-70 % figure on the same 10-minute chain proof the safety report carries;
 * between 60 s and 10 minutes it answers PENDING (which re-reads the pool), never BLOCKED; PROVEN still needs the fresh read.
 */
class Aate8938DoorProofWindowTest {
    private val mint = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"
    private val pool = "HLnpSz9h2S4hiLQ43rnSD9XkcUThA7B8hQMKmDaiTLcC"
    private val now = 1_800_000_000_000L

    @Before fun setUp() = PoolLock8895.resetForTest8895()
    @After fun tearDown() = PoolLock8895.resetForTest8895()

    private fun report() = SafetyReport(
        checkedAt = now - 1_000L, rugcheckScore = 99, rugcheckStatus = "CONFIRMED", evaluatedForPaper8522 = false, lpLockPct = 100.0,
        lpOwnership8522 = LiquidityOwnership8522.Evidence(mint, listOf(LiquidityOwnership8522.Pool(pool, "pump_fun_amm", 0.0, 40_000.0)), false, true))

    private fun assess(lock: PoolLock8895.Lock8895?) =
        LiquidityOwnership8522.assess(report(), pool, false, nowMs = now, expectedMint = mint, chainLock8895 = lock)

    @Test fun aProofTheReportCarriesIsNeverABlockAtTheDoor() {
        val held = PoolLock8895.Lock8895(mint, pool, PoolLock8895.VENUE_PUMPSWAP_8937, 100.0, now - 3L * 60_000L, "burned/lp_supply")
        PoolLock8895.putForTest8895(held)
        val r = assess(PoolLock8895.proof8895(mint, pool, now))
        assertEquals("3 minutes old: the provider's 0 % is waived, and the door waits on a fresh read", LiquidityOwnership8522.Status.PENDING, r.status)
        assertEquals("LP_OWNERSHIP_PROOF_PENDING_8522", r.reason)
        assertEquals("a fresh read proves it", LiquidityOwnership8522.Status.PROVEN, assess(held.copy(atMs = now - 2_000L)).status)
        PoolLock8895.putForTest8895(held.copy(atMs = now - 11L * 60_000L))
        assertEquals("past the report's window the provider's figure stands", LiquidityOwnership8522.Status.BLOCKED, assess(null).status)
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/LiquidityOwnership8522.kt").readText()
            .contains("PoolLock8895.reportProof8937(chainMint8937, p.address, nowMs) != null"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 126. The door waives on the report's proof (8938)"))
    }
}
