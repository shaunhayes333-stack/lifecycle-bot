package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7602CollectiveRemainingTrancheTest { private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `surfaces remain source visible`() {
  run { val s=src("collective/CollectiveLearning.kt"); assertTrue(s.contains("fun downloadAll")); assertTrue(s.contains("fun uploadModePerformance")) }
  run { val s=src("collective/LegalAgreementManager.kt"); assertTrue(s.contains("fun getAcceptanceTimestamp")); assertTrue(s.contains("fun hasAcceptedAgreement")); assertTrue(s.contains("fun needsReacceptance")) }
  run { val s=src("collective/LocalOrphanStore.kt"); assertTrue(s.contains("fun reconcileAll")) }
  run { val s=src("collective/TursoClient.kt"); assertTrue(s.contains("fun nukeBadData")); assertTrue(s.contains("fun saveLeadLagPair")) }
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("547 / 1,458")); assertTrue(a.contains("911 remain")) }
}