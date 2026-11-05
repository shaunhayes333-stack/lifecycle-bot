package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8535 — audit P0-05: the bridge's post-scoring correction skipped ELIGIBLE opinions scored on defaulted inputs. */
class Aate8535EligibleOnUnobservedTest {
    private val all = LaneInputTruth8236.Inputs8236(mcap = true, momentum = true, buyPressure = true, priceHistory = true, v3 = true)

    @Test fun every_positive_native_path_defers_when_its_bullish_witness_was_never_observed() {
        for (lane in listOf("QUALITY", "BLUECHIP", "MOONSHOT", "DIP_HUNTER", "CASHGEN", "MANIPULATED")) {
            assertNull("$lane with every input observed is left to its own verdict", LaneInputTruth8236.eligibleOnUnobserved8535(lane, all, true))
            val d = LaneInputTruth8236.eligibleOnUnobserved8535(lane, all.copy(buyPressure = false), true)!!
            assertTrue(lane, d.startsWith(LaneInputTruth8236.ELIGIBLE_UNOBSERVED_DEFER_8535) && d.endsWith("BUY_PRESSURE"))
        }
        assertEquals("${LaneInputTruth8236.ELIGIBLE_UNOBSERVED_DEFER_8535}:VOLUME", LaneInputTruth8236.eligibleOnUnobserved8535("DIP_HUNTER", all, false))
        assertNull("volume is a dip witness only", LaneInputTruth8236.eligibleOnUnobserved8535("QUALITY", all, false))
        // momentum 0.0 and the other defaults add no bullish conviction: an unknown optional feature contributes zero
        assertNull(LaneInputTruth8236.eligibleOnUnobserved8535("BLUECHIP", all.copy(momentum = false, mcap = false, v3 = false), true))
        // lanes that read their own feeds (TREASURY, CYCLIC, PROJECT_SNIPER, SHITCOIN, EXPRESS, CORE) are not mapped here
        for (lane in listOf("TREASURY", "CYCLIC", "PROJECT_SNIPER", "SHITCOIN", "EXPRESS", "CORE"))
            assertNull(lane, LaneInputTruth8236.eligibleOnUnobserved8535(lane, all.copy(buyPressure = false), false))
    }
}
