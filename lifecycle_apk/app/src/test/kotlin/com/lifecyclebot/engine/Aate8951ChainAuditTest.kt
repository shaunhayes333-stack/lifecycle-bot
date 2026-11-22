package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CapTruth8951
import com.lifecyclebot.engine.truth.ChainConfirm8950
import com.lifecyclebot.engine.truth.ChainConfirm8950.Input8950
import com.lifecyclebot.engine.truth.TokenBirthHydrator7441
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8951 — the stack audit's chain reads: a coin's birth in one oldest-first read, an unread intake read from its curve,
 * lane input defers and the exec gate's unread pool asked of the chain, established assets left to their own lanes, and
 * the lanes' cap measured against the chain's.
 */
class Aate8951ChainAuditTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theBirthIsTheFirstRowOfAnOldestFirstRead() {
        val ok = JSONObject("""{"jsonrpc":"2.0","result":{"data":[{"signature":"5abc","slot":10,"blockTime":1795000000,"err":null}],"paginationToken":"10:0"}}""")
        assertEquals("5abc" to 1_795_000_000L, TokenBirthHydrator7441.firstRow8951(ok))
        assertNull(TokenBirthHydrator7441.firstRow8951(JSONObject("""{"result":{"data":[]}}""")))
        assertNull("a failed first row is not the creation", TokenBirthHydrator7441.firstRow8951(
            JSONObject("""{"result":{"data":[{"signature":"x","blockTime":1,"err":{"InstructionError":[0,"Custom"]}}]}}""")))
        assertNull(TokenBirthHydrator7441.firstRow8951(JSONObject("""{"error":{"code":-32601}}""")))
        val h = src("engine/truth/TokenBirthHydrator7441.kt")
        assertTrue(h.contains(".put(\"sortOrder\", \"asc\").put(\"limit\", 1)"))
        assertTrue(h.contains("put(\"filters\", JSONObject().put(\"status\", \"succeeded\"))"))
        assertTrue("proven as a walked one is", h.contains("proveAndPublish8418(helius8951, mint, state)"))
    }

    @Test fun theAuditsDeferralsAskTheChain() {
        assertEquals(setOf(Input8950.SUPPLY, Input8950.PRICE_DEPTH), ChainConfirm8950.inputsOf8950(listOf("SUPPLY")))
        assertEquals(listOf("PRICE", "SUPPLY", "HISTORY", "BP"), LaneInputTruth8236.missing8951(LaneInputTruth8236.Inputs8236(false, false, false, false, true)))
        assertTrue(LaneInputTruth8236.missing8951(LaneInputTruth8236.Inputs8236(true, true, true, true, false)).isEmpty())
        assertTrue(ChainConfirm8950.promotes8951(2.5)); assertFalse(ChainConfirm8950.promotes8951(0.0)); assertFalse(ChainConfirm8950.promotes8951(Double.NaN))
        assertTrue(src("engine/BotService.kt").contains("com.lifecyclebot.engine.truth.ChainConfirm8950.probation8951(mint)"))
        assertTrue(src("engine/SpecialistBrainBridge7542.kt").contains("ChainConfirm8950.confirm8950(ts, LaneInputTruth8236.missing8951(inputs8236), \"LANE\")"))
        assertTrue(src("engine/ExecutableOpenGate.kt").contains("ChainConfirm8950.confirmMint8950(mint, listOf(\"LIQUIDITY\", \"SUPPLY\"), \"EXEC\")") && src("engine/ExecutableOpenGate.kt").contains(".also { chainOnTokenMap8951(ts.mint, mode, it) }"))
    }

    @Test fun establishedAssetsKeepTheirOwnLanesAndTheCapIsMeasured() {
        val stage = src("engine/truth/StageRead8950.kt")
        assertTrue(stage.contains("if (!memeLifecycle) { established8951.incrementAndGet(); return }"))
        assertTrue(stage.contains("TokenMetricStageRouter.memeLifecycle8951(ts, ts.lastMcap, ts.lastLiquidityUsd, Double.NaN)"))
        assertEquals("agree<1.1x", CapTruth8951.bucket8951(10_000.0, 10_500.0))
        assertEquals("2-10x:readLower", CapTruth8951.bucket8951(1_987.0, 6_498.0))
        assertEquals("10x+:readHigher", CapTruth8951.bucket8951(500_000.0, 16_941.0))
        assertEquals("", CapTruth8951.bucket8951(0.0, 1.0))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 139. The stack audit: confirm it on chain (8951)"))
    }
}
