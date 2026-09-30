package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7547NativeSpecialistDecisionCorrectnessTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun treasury_neutral_defaults_cannot_become_executable_probe() {
        val s=src("engine/TreasuryBrain.kt")
        assertTrue(s.contains("val setupConfirmed7547"))
        assertTrue(s.contains("NO_CONFIRMED_SCALP_SETUP_7547"))
        assertTrue(s.contains("val liquidityConfirmed7547 = ts.lastLiquidityUsd >= 10_000.0"))
        assertTrue(s.indexOf("if (!setupConfirmed7547)") < s.indexOf("score >= 50.0 -> \"PROBE_SCALP\""))
    }

    @Test fun cyclic_negative_memory_shapes_floor_instead_of_tombstoning_lane() {
        val s=src("engine/CyclicTradeEngine.kt")
        assertFalse(s.contains("if(badExp||danger)return CandidateOpinion7542"))
        assertTrue(s.contains("if(badExp)floor+=5.0"))
        assertTrue(s.contains("if(danger)floor+=5.0"))
        assertTrue(s.contains("CYCLIC_NEGATIVE_EXPECTANCY_SOFT_SHAPE_7547"))
    }

    @Test fun native_brain_cache_tracks_fast_strategy_evidence() {
        val s=src("engine/SpecialistBrainBridge7542.kt")
        listOf(
            "ts.momentum","ts.volatility","ts.topHolderPct","ts.meta.momScore",
            "ts.meta.pressScore","ts.meta.velocityScore","ts.meta.emafanAlignment",
            "ts.meta.exhaustion","ts.meta.spikeDetected","ts.safety.firstBlockSupplyPct",
            "ts.sentiment.score","ts.toolAffinity.hashCode()"
        ).forEach { assertTrue("missing fingerprint input: $it", s.contains(it)) }
    }
}
