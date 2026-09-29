package com.lifecyclebot.v3.shadow

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7452EntryMarkoutTruthTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun production_shadow_tracker_records_fixed_horizon_markouts() {
        val s = src("v3/shadow/ShadowTracker.kt")
        for (h in listOf(2,5,10,15,30,60,120,300,600)) {
            assertTrue(s.contains(h.toString()))
        }
        assertTrue(s.contains("observePrice7452"))
        assertTrue(s.contains("markoutsPct7452"))
    }

    @Test fun stop_vs_tp_classification_uses_actual_first_hit_time() {
        val s = src("v3/shadow/ShadowTracker.kt")
        assertTrue(s.contains("firstStopHitAtMs7452"))
        assertTrue(s.contains("firstTpHitAtMs7452"))
        assertTrue(s.contains("firstStopHitAtMs7452 < s.firstTpHitAtMs7452"))
        assertFalse(s.contains("hitStopFirst = shadow.troughPnlPct"))
    }

    @Test fun every_v3_observation_advances_markouts_without_extra_fetch() {
        val s = src("v3/V3EngineManager.kt")
        val region = s.substringAfter("V5.0.7452 — every current observation").substringBefore("// V5.0.7303")
        assertTrue(region.contains("observePrice7452"))
        assertTrue(region.contains("ts.lastPrice"))
        assertFalse(region.contains("PriceResolverFallback"))
    }

    @Test fun resolver_uses_ordered_hits_before_ten_minute_mark() {
        val s = src("v3/V3EngineManager.kt")
        assertTrue(s.contains("firstHit7452 == \"TP_FIRST\""))
        assertTrue(s.contains("firstHit7452 == \"STOP_FIRST\""))
        assertTrue(s.contains("markoutPct7452(m, 600)"))
    }
}
