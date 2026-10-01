package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7655IntelligenceEstateCoverageTest {
    @Test fun provenanceRegistryNamesTheMajorMissingAndRepresentedFamilies() {
        val r = File("src/main/kotlin/com/lifecyclebot/engine/SuperEstateCoverageRegistry7655.kt").readText()
        listOf(
            "AI CrossTalk",
            "LLM provider council",
            "Scanner/arb ensemble",
            "LayerBrain learned heads",
            "MetaCognition/SuperBrain/BotBrain/Sentience aggregate",
            "SSI pilot council",
            "Collective/hive intelligence",
        ).forEach { assertTrue(r.contains(it)) }
        assertTrue(r.contains("REPRESENTED_UPSTREAM"))
        assertTrue(r.contains("DIRECT_CACHE"))
        assertTrue(r.contains("SAFETY_SOVEREIGN"))
        assertTrue(r.contains("LEGACY_NO_AUTOWIRE"))
    }

    @Test fun sourceCensusTracksAtLeastTheKnown150PlusEstate() {
        val root = File("src/main/kotlin")
        val rx = Regex("(AI|Brain|Scanner|Council|CrossTalk|Cognition|Sentience|Intelligence|Predictor|Oracle|Model|Learning|Policy|Agent|Expert|Reasoner|Planner)", RegexOption.IGNORE_CASE)
        val candidates = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && rx.containsMatchIn(it.name) }
            .count()
        assertTrue("expected 150+ intelligence candidates, found $candidates", candidates >= 150)
    }

    @Test fun estateTelemetryIncludesCoverageRegistry() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceEstate7654.kt").readText()
        assertTrue(e.contains("SuperEstateCoverageRegistry7655.coverage()"))
        assertTrue(e.contains("coverageFamilies"))
    }

    @Test fun censusScriptIsReportOnlyAndWritesAnAuditArtifact() {
        val s = File("../ci/super_intelligence_estate_audit_7655.py")
        assertTrue(s.exists())
        val t = s.readText()
        assertTrue(t.contains("super_intelligence_estate_census_7655.tsv"))
        assertTrue(t.contains("UNCLASSIFIED_INTELLIGENCE_REVIEW"))
        assertTrue(!t.contains("update_file"))
    }
}
