package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiquidityTruth8037
import com.lifecyclebot.engine.truth.PoolCreationTime7385
import com.lifecyclebot.network.DexscreenerApi
import com.lifecyclebot.perps.DynamicAltTokenRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8460 — owner: "even at code source the strategy, token selection, pipeline chokes should be obvious". 8457 (9 min):
 *  - liq{dexBatch: a=10835 r=9855 rep=0}: the pair lane read each coin's chosen pool liquidity 9,855 times and wrote it
 *    nowhere, while SHITCOIN / EXPRESS deferred 1,061 / 1,053 evaluations LANE_LIQ_UNHYDRATED;
 *  - birth unknownMints JUPITER_STRICT/other=933, walk rpcFail=391, PROJECT_SNIPER BIRTH_METADATA_HYDRATING=1,107: the alt
 *    registry read Jupiter's verified list and dropped firstPool.createdAt, the floor that proves those coins old.
 */
class Aate8460SelectionChokesTest {
    @Test fun thePairLanesPoolLiquidityReachesTheTokenByTheIntakeRules() {
        assertTrue("a changed positive observation writes", LiquidityTruth8037.intakeWrites8418(0.0, 25_000.0, false, false))
        assertTrue("up or down", LiquidityTruth8037.intakeWrites8418(40_000.0, 25_000.0, false, false))
        assertFalse("never over a fresh chain / curve read", LiquidityTruth8037.intakeWrites8418(0.0, 25_000.0, false, true))
        assertFalse(LiquidityTruth8037.intakeWrites8418(25_000.0, 25_000.0, false, false))
        assertFalse(LiquidityTruth8037.intakeWrites8418(0.0, 0.0, false, false))
        assertTrue(DexscreenerApi.BatchPairs8285(emptySet(), emptyList(), DexscreenerApi.BatchCause8087.NO_DATA).liquidity8460.isEmpty())

        val api = File("src/main/kotlin/com/lifecyclebot/network/DexscreenerApi.kt").readText()
        val batch = api.substringAfter("fun batchPairs8285(").substringBefore("data class BatchPairs8285(")
        assertTrue("a pump.fun curve pair's depth is its own account", batch.contains("if (!pick.dexId.equals(\"pumpfun\", ignoreCase = true)) liquidity8460[mint] = pick.liquidityUsd"))
        assertTrue(batch.contains("BatchCause8087.PRICED, prices8287, liquidity8460)"))

        val lane = File("src/main/kotlin/com/lifecyclebot/engine/DexBatchLane8287.kt").readText()
        assertTrue(lane.substringAfter("private fun noteBatch8287(").substringBefore("private val PAIR_FEATURES_8287")
            .contains("applyLiquidity8460(r.liquidity8460)"))
        val apply = lane.substringAfter("private fun applyLiquidity8460(")
        assertTrue(apply.contains("if (!lt.intakeWrites8418(ts.lastLiquidityUsd, usd, false, lt.chainHeld8418(ts))) continue"))
        assertTrue("the first fill resumes deferred hand-offs", apply.contains("HydrationReeval8343.writeTick8345(ts, usd, \"DEX_BATCH_8460\")"))
        assertTrue(apply.contains("FeatureSourceCounters8285.note8285(\"liq\", \"dexBatch\", FeatureSourceCounters8285.REPAIRED)"))
    }

    @Test fun jupitersFirstPoolProvesAVerifiedCoinOld() {
        val mint = "Aate8460FirstPoo1Mint1111111111111111111111"
        val createdMs = java.time.Instant.parse("2024-03-01T00:00:00Z").toEpochMilli()
        DynamicAltTokenRegistry.notePoolFloor8460(mint, JSONObject().put("id", mint)
            .put("firstPool", JSONObject().put("id", "pool").put("createdAt", "2024-03-01T00:00:00Z")))
        assertEquals(createdMs, PoolCreationTime7385.createdAtMs(mint))
        val none = "Aate8460NoPoo1Mint11111111111111111111111111"
        DynamicAltTokenRegistry.notePoolFloor8460(none, JSONObject().put("id", none))
        DynamicAltTokenRegistry.notePoolFloor8460(none, JSONObject().put("firstPool", JSONObject().put("createdAt", "not-a-date")))
        assertEquals(null, PoolCreationTime7385.createdAtMs(none))
        val reg = File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val loader = reg.substringAfter("https://lite-api.jup.ag/tokens/v2/tag?query=verified")
        assertTrue("read for every listed coin, new or already held", loader.indexOf("notePoolFloor8460(mint, obj)") in 0 until loader.indexOf("if (registry.containsKey(canonicalKey6544))"))
    }
}
