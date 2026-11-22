package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ChainConfirm8950
import com.lifecyclebot.engine.truth.ChainConfirm8950.Input8950
import com.lifecyclebot.engine.truth.HeliusSwapCandles7819
import com.lifecyclebot.engine.truth.StageRead8950
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8950 — whatever a LIVE decision, hold or exit needs and lacks is read from the chain now; an UNKNOWN stage reads
 * the coin's own swap history at once (no free-source grace, one paced page) and its pipeline runs again when it lands.
 */
class Aate8950ChainConfirmTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val now = 1_800_000_000_000L
    private val mint = "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"

    @Before fun setUp() = StageRead8950.resetForTest8950()
    @After fun tearDown() { StageRead8950.resetForTest8950(); StageRead8950.direct8950 = null }

    @Test fun eachMissingInputMapsToItsChainRead() {
        assertEquals(setOf(Input8950.PRICE_DEPTH), ChainConfirm8950.inputsOf8950(listOf("price", "PRICE_STALE", "liquidity:UNKNOWN", "CRITICAL_LIQUIDITY")))
        assertEquals(setOf(Input8950.HISTORY), ChainConfirm8950.inputsOf8950(listOf("shape", "chg", "bp", "MOVE_HISTORY_SPARSE", "ENTRY_WAIT_FLOW_UNOBSERVED", "ENTRY_WAIT_STAGE_UNRESOLVED", "STAGE")))
        assertEquals(setOf(Input8950.PRICE_DEPTH, Input8950.HISTORY), ChainConfirm8950.inputsOf8950(listOf("MOVE_UNREAD")))
        assertEquals(setOf(Input8950.SAFETY, Input8950.AUTHORITY, Input8950.HOLDERS, Input8950.LP),
            ChainConfirm8950.inputsOf8950(listOf("BUY_DOOR_PROOF_LIVE_CRITICAL_PROOF_PENDING_8373")))
        assertTrue(ChainConfirm8950.inputsOf8950(listOf("FALLING_HOUR", "UNNAMED", "")).isEmpty())
        assertTrue(ChainConfirm8950.due8950(0L, now)); assertFalse(ChainConfirm8950.due8950(now, now + 19_999L))
    }

    @Test fun anUnknownStageIsReadOnChainWithoutGraceAndRunsAgainWhenItLands() {
        assertFalse(StageRead8950.pending8950(mint, now))
        StageRead8950.askForTest8950(mint, now)
        assertTrue("asked: the read skips the free grace and rides the near-decision tier", StageRead8950.pending8950(mint, now + 1_000L))
        assertEquals(HeliusSwapCandles7819.PRIORITY_NEAR_DECISION_8284, HeliusSwapCandles7819.priority8284(mint, now + 1_000L))
        assertFalse(StageRead8950.pending8950(mint, now + StageRead8950.WINDOW_MS_8950 + 1L))
        val ran = mutableListOf<String>()
        StageRead8950.direct8950 = { m, _ -> ran += m }
        StageRead8950.onMerged8950(mint, now + 2_000L)
        assertEquals(listOf(mint), ran)
        assertFalse("read landed: no longer pending", StageRead8950.pending8950(mint, now + 2_500L))
        assertTrue(StageRead8950.askDue8950(0L, now)); assertFalse(StageRead8950.askDue8950(now, now + 59_999L))
        val h = src("engine/truth/HeliusSwapCandles7819.kt")
        assertTrue(h.contains("val firstAsk = if (StageRead8950.pending8950(ts.mint, nowMs)) 0L else HeliusCandleGate8418.firstAsk8418(ts.mint, nowMs)"))
        assertTrue("one page on the paced share, never the lease", h.contains("else if (onePage8950) HeliusCreditEconomy7881.reserve8386(HeliusCreditEconomy7881.Consumer.SWAP_CANDLES, 100.0, false, nowMs)"))
        assertTrue(h.contains("200.0, priority >= PRIORITY_HELD_8284, nowMs)"))
    }

    @Test fun decisionsHoldsAndExitsAskTheChain() {
        assertTrue(src("engine/TokenMetricStageRouter.kt").contains("com.lifecyclebot.engine.truth.StageRead8950.onStage8950(ts, s.stage.name, now,"))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("if (!paper) try { ChainConfirm8950.confirm8950(ts, missing + CandidateAdmission8136.deferCauses8700(d.trace), \"DECISION\", nowMs) }"))
        assertTrue(src("engine/truth/EvidenceWait8948.kt").contains("ChainConfirm8950.confirm8950(ts, causes, \"EVIDENCE\", nowMs)"))
        assertTrue(src("engine/truth/MissingMarkExitVeto6835.kt").contains("ChainConfirm8950.confirmMint8950(mintKey, listOf(\"PRICE\"), \"EXIT\")"))
        assertTrue(src("engine/sell/SellReconciler.kt").contains("ChainConfirm8950.confirmMint8950(pos.mint, listOf(\"PRICE\"), \"HOLD\")"))
        assertTrue(src("engine/BotService.kt").contains("fastLaneEvaluate7277(mint, ConfigStore.load(applicationContext), \"STAGE_READ_8950\", onceKey = \"\$mint|8950|\$slot\")"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 138. What a decision needs is confirmed on chain (8950)"))
    }
}
