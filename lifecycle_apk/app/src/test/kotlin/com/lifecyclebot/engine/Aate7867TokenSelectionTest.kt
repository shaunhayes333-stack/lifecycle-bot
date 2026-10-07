package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.FreshLaunchSelector7737
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7867TokenSelectionTest {

    @Test fun creatorSeedBuyIsNotBuyerConcentration() {
        val w = File("src/main/kotlin/com/lifecyclebot/engine/WhaleDetector.kt").readText()
        assertTrue(w.contains("val crowdBuys7867 = buys.filter { it.wallet.isNotBlank() && it.wallet != dev }"))
        assertTrue(w.contains("distinctBuyers60s = crowdBuys7867.map { it.wallet }.toSet().size"))
        assertTrue(w.contains("OperatorRegistry.getDevWallet(mint)"))
        // With the creator excluded, a launch whose only buyer is the creator has no crowd: CONC_NA.
        assertEquals("CONC_NA", FreshLaunchSelector7737.concentrationBucket(0.0, 0))
        assertEquals("cells_7867", FreshLaunchSelector7737.CELLS_KEY_7867)
    }

    @Test fun lpLockIsJudgedOnTheDeepestMarket() {
        fun market(type: String, base: Double, quote: Double, locked: Double) = JSONObject()
            .put("marketType", type)
            .put("lp", JSONObject().put("baseUSD", base).put("quoteUSD", quote).put("lpLockedPct", locked))
        val arr = JSONArray()
            .put(market("side", 50.0, 50.0, 0.0))
            .put(market("pump_fun_amm", 40_000.0, 40_000.0, 100.0))
        assertEquals("pump_fun_amm", primaryMarket7867(arr)?.optString("marketType"))
        val noDepth = JSONArray().put(JSONObject().put("marketType", "first")).put(JSONObject().put("marketType", "second"))
        assertEquals("first", primaryMarket7867(noDepth)?.optString("marketType"))
        val src = File("src/main/kotlin/com/lifecyclebot/engine/TokenSafetyChecker.kt").readText()
        assertFalse(src.contains("val market = markets.optJSONObject(0)"))
    }
}
