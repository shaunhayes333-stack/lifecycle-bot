package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7464SpecialistAffinityLineageTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun affinity_recovery_requires_exact_registry_lane_proof() {
        val s = src("engine/truth/MemeExecutionFunnelReceivers6625.kt")
        val fn = s.substringAfter("fun ensureAffinityLineage7464(").substringBefore("fun stamp6625(")
        assertTrue(fn.contains("GlobalTradeRegistry.getLaneAffinity(key.mint)"))
        assertTrue(fn.contains("if (laneKey !in affinity)"))
        assertTrue(fn.contains("SPECIALIST_AFFINITY_LINEAGE_NO_PROOF_7464_"))
    }

    @Test fun recovery_only_fills_discover_and_qualify() {
        val s = src("engine/truth/MemeExecutionFunnelReceivers6625.kt")
        val fn = s.substringAfter("fun ensureAffinityLineage7464(").substringBefore("fun stamp6625(")
        assertTrue(fn.contains("rec.stages[Stage.DISCOVER]"))
        assertTrue(fn.contains("rec.stages[Stage.QUALIFY]"))
        assertFalse(fn.contains("rec.stages[Stage.INTENT]"))
        assertFalse(fn.contains("rec.stages[Stage.FDG]"))
        assertFalse(fn.contains("rec.stages[Stage.MARK]"))
        assertFalse(fn.contains("rec.stages[Stage.SIZE]"))
    }

    @Test fun recovery_requires_owner_or_later_real_stage() {
        val s = src("engine/truth/MemeExecutionFunnelReceivers6625.kt")
        val fn = s.substringAfter("fun ensureAffinityLineage7464(").substringBefore("fun stamp6625(")
        assertTrue(fn.contains("downstreamStage.ordinal < Stage.OWNER.ordinal"))
    }

    @Test fun toolkit_recovers_lineage_on_the_same_resolved_key_before_stage_stamp() {
        val s = src("engine/ToolkitSignalSheet.kt")
        val call = s.indexOf(".ensureAffinityLineage7464(key, causalStage)")
        val stamp = s.indexOf("SpecialistCausalFunnel6625.stamp6625(key, causalStage, stage)", call)
        assertTrue(call >= 0)
        assertTrue(stamp > call)
    }

    @Test fun existing_intent_backfill_remains_same_record_executable_proof_only() {
        val s = src("engine/truth/MemeExecutionFunnelReceivers6625.kt")
        val fn = s.substringAfter("fun stamp6625(").substringBefore("fun stageCounts6625")
        assertTrue(fn.contains("Stage.DISCOVER in rec.stages"))
        assertTrue(fn.contains("hasFdgAllow7418 && hasMark7418 && hasSize7418"))
        assertTrue(fn.contains("INTENT_INFERRED_FROM_EXECUTABLE_LINEAGE_7418"))
    }
}
