package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7586MaintenanceCallbackControlTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `maintenance and callback rows stay explicit`() {
  assertTrue(src("perps/WatchlistEngine.kt").contains("fun setAlertCallback("))
  val w=src("engine/WhaleWalletTracker.kt"); assertTrue(w.contains("fun setOnMovementCallback(")); assertTrue(w.contains("fun unwatchWhale("))
  assertTrue(src("network/HeliusWebSocket.kt").contains("fun unwatchWallet("))
  val a=src("engine/AutoEndpointMigrator.kt"); assertTrue(a.contains("fun forceMigrate(")); assertTrue(a.contains("fun clearMigration("))
  assertTrue(src("engine/GeminiCopilot.kt").contains("fun clearCaches()"))
  assertTrue(src("engine/SmartChartScanner.kt").contains("fun clearAllCaches()"))
  assertTrue(src("perps/PriceAggregator.kt").contains("fun clearPrune("))
  assertTrue(src("perps/PerpsTradeVisualizer.kt").contains("fun clearAllHistory()"))
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("174 / 1,458")); assertTrue(a.contains("1,284 remain")) }
}