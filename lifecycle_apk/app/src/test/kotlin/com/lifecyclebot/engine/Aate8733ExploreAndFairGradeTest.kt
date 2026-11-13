package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExploreLive8733
import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8733 — explore buys of unread-LP paying-cell coins, a fair 5-minute run grade, the 85M/month Helius plan. */
class Aate8733ExploreAndFairGradeTest {
    @Test fun anUnreadLpIsRelabelledOnlyOnceRugcheckIsAnswered() {
        val lp = "LP_OWNERSHIP_PROOF_PENDING_8522:selected pool has no complete ownership proof; pool=p"
        assertEquals(listOf("RUGCHECK_PENDING_OR_UNKNOWN:PENDING_REVIEW", lp),
            ExploreLive8733.relabel8733(listOf("RUGCHECK_PENDING_OR_UNKNOWN:PENDING_REVIEW", lp)))
        val out = ExploreLive8733.relabel8733(listOf("RUGCHECK_NO_FINDINGS_CHAIN_PROVEN_8471:PENDING_REVIEW", lp))
        assertTrue(out[1].startsWith("LP_UNREAD_EXPLORE_8733:"))
        assertTrue("the explore item is not a critical proof", PreTradeHardGate.criticalProofPending8373(out).isEmpty())
        assertEquals(listOf("MINT_AUTHORITY_UNKNOWN"), PreTradeHardGate.criticalProofPending8373(
            ExploreLive8733.relabel8733(listOf("MINT_AUTHORITY_UNKNOWN", lp))))
    }

    @Test fun theBudgetAndSizeAreBounded() {
        assertTrue(ExploreLive8733.withinBudget8733(0, 0))
        assertFalse(ExploreLive8733.withinBudget8733(ExploreLive8733.MAX_OPEN_8733, 0))
        assertFalse(ExploreLive8733.withinBudget8733(0, ExploreLive8733.MAX_PER_DAY_8733))
        assertEquals("EXPLORE_SIZE_ABOVE_CAP_8733", ExploreLive8733.spendRefusal8733(0.2, 0L))
        assertEquals("EXPLORE_SIZE_ABOVE_CAP_8733", ExploreLive8733.spendRefusal8733(Double.NaN, 0L))
    }

    @Test fun onlyAnUnreadLpExploresAndTheDoorStillBlocksUnlocked() {
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/PreTradeHardGate.kt").readText()
        assertTrue(gate.contains("val explore8733 = ownership8522.status == LiquidityOwnership8522.Status.PENDING &&"))
        assertTrue(gate.contains("LiquidityOwnership8522.Status.BLOCKED -> return block(ts, ownership8522.reason, ownership8522.detail)"))
        assertTrue(gate.indexOf("Status.BLOCKED -> return block") < gate.indexOf("val explore8733"))
        val lp = File("src/main/kotlin/com/lifecyclebot/engine/LiquidityOwnership8522.kt").readText()
        assertTrue(lp.contains("private const val MIN_LOCKED_PCT = 70.0") && lp.contains("LP_UNLOCKED_CONFIRMED_8522"))
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(ex.contains("ExploreLive8733.spendRefusal8733(sol, now8733)"))
        assertTrue(ex.indexOf("ExploreLive8733.spendRefusal8733") < ex.indexOf("runShadowPaperBuy(ts, sol, score, quality, \"live_prebroadcast_observation\")"))
    }

    @Test fun theFiveMinuteGradeCountsAQuietCoinAtItsLastPrint() {
        val t0 = 1_000_000L
        fun p(at: Long, px: Double) = TradeTape8100.Print8128(at, px, 0.1, true)
        assertEquals(2.0, RunTape8293.price5m8733(listOf(p(t0 + 60_000, 1.5), p(t0 + 300_000, 2.0)), t0)!!, 1e-9)
        assertEquals(0.3, RunTape8293.price5m8733(listOf(p(t0 + 30_000, 1.2), p(t0 + 90_000, 0.3)), t0)!!, 1e-9)
        assertNull(RunTape8293.price5m8733(listOf(p(t0 - 10_000, 1.0)), t0))
    }

    @Test fun theHeliusDayIsTheOwnersPlan() {
        assertEquals(2_833_333.0, HeliusCreditEconomy7881.DEFAULT_DAILY_BUDGET_CREDITS_8731, 0.0)
        val rep = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(rep.contains("Explore live buys (§8733): "))
    }
}
