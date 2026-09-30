package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7555MoonshotExpressFidelityTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun moonshot_local_projection_cannot_veto_native_scoring() {
        val m=src("v3/scoring/MoonshotTraderAI.kt")
        val score=m.substringAfter("fun scoreToken(").substringBefore("// 5. Safety check")
        assertTrue(score.contains("MOONSHOT_LOCAL_CAP_OBSERVED_7555"))
        assertTrue(score.contains("MOONSHOT_LOCAL_OCCUPANCY_OBSERVED_7555"))
        assertFalse(score.contains("return MoonshotScore(false, 0, 0.0, \"already_have_position\")"))
        assertFalse(score.contains("return MoonshotScore(false, 0, 0.0, \"max_"))
    }

    @Test fun moonshot_scores_canonical_launch_phase_vocabulary() {
        val m=src("v3/scoring/MoonshotTraderAI.kt")
        val block=m.substringAfter("val phaseScore = when").substringBefore("score += phaseScore")
        assertTrue(block.contains("\"PRE_IGNITION\" -> 15"))
        assertTrue(block.contains("\"IGNITION\" -> 12"))
        assertTrue(block.contains("\"EXPANDING\" -> 7"))
        assertTrue(block.contains("\"POST_PUMP_FADE\" -> -10"))
        assertTrue(block.contains("\"MATURE_OR_UNKNOWN\", \"METADATA_HYDRATING\", \"\" -> 0"))
        assertFalse(block.contains("contains(\"breakout\""))
    }

    @Test fun express_uses_distinct_hourly_and_five_minute_direction() {
        val b=src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(b.contains("val expressMomentum7555 = ts.lastPriceChange1h"))
        val line=b.lineSequence().first { it.contains("out[\"EXPRESS\"]=") }
        assertTrue(line.contains("expressMomentum7555"))
        assertTrue(line.contains("ts.lastPriceChange5m"))
        assertFalse(line.contains("lastLiquidityUsd,mom,bp,volVs,ts.lastPriceChange5m"))
    }

    @Test fun express_tool_identity_exposes_both_horizons() {
        val b=src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(b.contains("setOf(\"EXPRESS\",\"MOMENTUM_1H\",\"PRICE_5M\",\"VOLUME_ACCELERATION\")"))
    }
}
