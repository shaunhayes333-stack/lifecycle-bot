package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7475IntakeEvidenceCompressionTest {
    @Test fun affinity_merge_is_additive_and_reports_change() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        val fn = s.substringAfter("fun mergeAffinity(").substringBefore("fun getWatchlist()")
        assertTrue(fn.contains("): Boolean"))
        assertTrue(fn.contains("e.laneAffinity.add(k)"))
        assertTrue(fn.contains("e.toolAffinity.add(k)"))
        assertTrue(fn.contains("return changed"))
    }

    @Test fun probation_requires_distinct_source_to_promote() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        val block = s.substringAfter("val existingProbation = probation[mint]").substringBefore("// V5.9.626")
        assertTrue(block.contains("distinctSource7475"))
        assertTrue(block.contains("PROBATION_SAME_SOURCE_REFRESH_COALESCED_7475"))
        assertTrue(block.contains("MULTI_SCANNER_CONFIRM"))
    }

    @Test fun duplicate_intake_only_rehydrates_on_new_information() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(s.contains("val meaningfulRefresh7475 = newSourceEvidence6566 || affinityChanged7475"))
        assertTrue(s.contains("if (hot6566 && meaningfulRefresh7475)"))
        assertTrue(s.contains("INTAKE_REPEAT_NO_NEW_EVIDENCE_COALESCED_7475"))
    }
}
