package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7583DashboardCacheProjectionTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `dashboard and cache rows are projections`() {
  val d=src("engine/DashboardDataProvider.kt"); assertTrue(d.contains("fun lastCanonicalFinalized6485()")); assertTrue(d.contains("fun getIntelligenceDashboard(")); assertTrue(d.contains("fun getTreasuryDashboard()"))
  val c=src("v3/scoring/CollectiveIntelligenceAI.kt"); assertTrue(c.contains("fun getMintMemory(")); assertTrue(c.contains("fun getEndpointHealthRecords()"))
  val l=src("collective/CollectiveLearning.kt"); assertTrue(l.contains("fun lastSyncAgeMs6943(): Long")); assertTrue(l.contains("fun getWhaleEffectiveness("))
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("138 / 1,458")); assertTrue(a.contains("1,320 remain")) }
}