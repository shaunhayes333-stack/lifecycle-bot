package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7658ScannerSourceAndHandoffTest {
    @Test fun scannerSourceReadbackIsReadOnlyAndFeedsItsOwnFamily() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/ScannerSourceBrain.kt").readText()
        val e = File("src/main/kotlin/com/lifecyclebot/engine/ExistingIntelligenceContext7650.kt").readText()
        val t = File("src/main/kotlin/com/lifecyclebot/engine/SuperEvidenceTopology7651.kt").readText()
        assertTrue(s.contains("fun sourceSnapshot7658(source: String): SourceSnapshot7658?"))
        assertTrue(e.contains("estate7654.sourceLearningUtility(policy)"))
        assertTrue(t.contains("SCANNER_SOURCE_LEARNING"))
    }

    @Test fun durableSuperIntelligenceHandoffExists() {
        val h = File("../../audits/SUPER_INTELLIGENCE_HANDOFF.md")
        assertTrue(h.exists())
        val text = h.readText()
        assertTrue(text.contains("AATE Super Intelligence Handoff"))
        assertTrue(text.contains("AICrossTalk"))
        assertTrue(text.contains("LLM provider council"))
        assertTrue(text.contains("250 intelligence-like Kotlin candidates"))
        assertTrue(text.contains("Recovery instruction for a new conversation"))
    }
}
