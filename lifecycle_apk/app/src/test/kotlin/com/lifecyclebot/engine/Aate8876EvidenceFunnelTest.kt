package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8876 — plan W10: a coin that carried market-cell evidence (a paying cell, the agreement pocket, the LLM's proven band,
 * ...) is counted at every step it reaches, so the report names where those coins stop. Owner: "there is no guessing".
 */
class Aate8876EvidenceFunnelTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Before fun setUp() = DecisionEpisode8535.clearForTest8535()
    @After fun tearDown() = DecisionEpisode8535.clearForTest8535()

    @Test fun eachEvidenceKindIsCountedOnceAtEachStepItsCoinsReach() {
        val k = "AGREEMENT_POCKET"
        // coin 1: admitted, then the FDG blocks it (the admission is re-read once: still one coin)
        DecisionEpisode8535.noteAdmission8535("CORE", "M1", 1L, "ADMIT", "MARKET_CELL_8723", null, listOf("a"), 1_000L, evidence8876 = k)
        DecisionEpisode8535.noteAdmission8535("CORE", "M1", 1L, "ADMIT", "MARKET_CELL_8723", null, listOf("a"), 1_050L, evidence8876 = k)
        DecisionEpisode8535.noteFdg8535("CORE", "M1", 1L, false, "LP_LOCK_UNPROVEN", 1_100L)
        // coin 2: the admission refuses it
        DecisionEpisode8535.noteAdmission8535("MOONSHOT", "M2", 4L, "REFUSE", "SAFETY_REFUSED_8136", null, listOf("b"), 1_200L, evidence8876 = k)
        // coin 3: admitted, allowed, ticketed and executed
        DecisionEpisode8535.noteAdmission8535("EXPRESS", "M3", 2L, "ADMIT", "MARKET_CELL_8723", null, listOf("c"), 1_300L, evidence8876 = k)
        DecisionEpisode8535.noteFdg8535("EXPRESS", "M3", 2L, true, null, 1_350L)
        DecisionEpisode8535.noteStage8535("EXPRESS", "M3", 2L, "TICKET", 1_400L)
        DecisionEpisode8535.noteStage8535("EXPRESS", "M3", 2L, "EXEC", 1_500L)
        DecisionEpisode8535.noteStage8535("EXPRESS", "M3", 2L, "EXEC", 1_501L)
        // a coin that carried no evidence is not in the funnel
        DecisionEpisode8535.noteAdmission8535("CORE", "M4", 1L, "ADMIT", "SPECIFIC_EDGE_PROVEN_8136", null, listOf("d"), 1_600L)
        DecisionEpisode8535.noteStage8535("CORE", "M4", 1L, "TICKET", 1_700L)
        val line = DecisionEpisode8535.funnelLine8876()
        assertTrue(line, line.contains("AGREEMENT_POCKET{COINS=3 ADMIT=2 REFUSE=1 FDG_ALLOW=1 FDG_BLOCK=1 TICKET=1 EXEC=1 why{"))
        assertTrue(line, line.contains("REFUSE:SAFETY_REFUSED_8136=1") && line.contains("FDG_BLOCK:LP_LOCK_UNPROVEN=1"))
        assertFalse(line, line.contains("SPECIFIC_EDGE_PROVEN_8136"))
    }

    @Test fun theAdmissionNamesTheEvidenceAndTheBriefPrintsTheFunnel() {
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("evidence8876 = candidate.marketCell8723?.kind.orEmpty()) } catch (_: Throwable) {}"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("DecisionEpisode8535.funnelLine8876()"))
        assertTrue(DecisionEpisode8535.funnelLine8876().startsWith("evidenceFunnel8876[- rule="))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 65. Where the evidence coins go (8876)"))
    }
}
