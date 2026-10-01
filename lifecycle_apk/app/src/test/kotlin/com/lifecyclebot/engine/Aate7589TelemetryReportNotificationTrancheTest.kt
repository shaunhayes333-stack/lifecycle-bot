package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7589TelemetryReportNotificationTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `telemetry report notification rows stay non authoritative`() {
  assertTrue(src("engine/BundleDetector.kt").contains("fun getCacheStats(): String"))
  assertTrue(src("engine/truth/CanonicalEntryAuthority6540.kt").contains("fun assetClassStats6567()"))
  assertTrue(src("engine/DiscordNotifier.kt").contains("fun bigWinMsg("))
  assertTrue(src("engine/TradeJournal.kt").contains("fun getLiveStats("))
  assertTrue(src("engine/diagnostics/LockDiagnosticsTracker.kt").contains("fun inFlight("))
  val m=src("perps/MarketsScanner.kt"); assertTrue(m.contains("suspend fun getTopGainers(")); assertTrue(m.contains("suspend fun getTopLosers("))
  val n=src("perps/PerpsNotificationManager.kt"); assertTrue(n.contains("fun notifyLearningMilestone(")); assertTrue(n.contains("fun notifyLiquidationWarning(")); assertTrue(n.contains("fun notifyMTFAlignment("))
  val p=src("engine/quant/PortfolioAnalytics.kt"); assertTrue(p.contains("fun calculateCorrelations()")); assertTrue(p.contains("fun generateHeatMap()"))
  val r=src("engine/RejectionTelemetry.kt"); assertTrue(r.contains("fun topWindow(")); assertTrue(r.contains("fun topSession("))
  assertTrue(src("engine/ReportingHub.kt").contains("fun addBoundedSection("))
  val rc=src("engine/truth/RootCauseTelemetry6441.kt"); assertTrue(rc.contains("fun attribute(")); assertTrue(rc.contains("fun subsystemBreakdown()"))
  assertTrue(src("engine/RunTracker30D.kt").contains("fun getFilterStats(): String"))
  val s=src("engine/StrategyTelemetry.kt"); assertTrue(s.contains("fun computePaperTerminalLeaderboard(")); assertTrue(s.contains("fun bleeders(")); assertTrue(s.contains("fun getDisabled(): Set<String> = emptySet()"))
  assertTrue(src("engine/UniversalBridgeEngine.kt").contains("fun getBridgeStats()"))
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("229 / 1,458")); assertTrue(a.contains("1,229 remain")) }
}