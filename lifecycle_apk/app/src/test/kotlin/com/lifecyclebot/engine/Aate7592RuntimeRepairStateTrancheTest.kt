package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7592RuntimeRepairStateTrancheTest {
 @Test fun `runtime repair rows remain bounded controls or readbacks`() {
  val s=File("src/main/kotlin/com/lifecyclebot/engine/RuntimeRepairState.kt").readText()
  assertTrue(s.contains("fun clearPaperModeRequest("))
  assertTrue(s.contains("fun enableLane("))
  assertTrue(s.contains("fun enableScannerSource("))
  assertTrue(s.contains("fun resumeTrading("))
  assertTrue(s.contains("fun scannerCap(): Int ="))
  assertTrue(s.contains("fun setScannerUserDisabled("))
  assertTrue(s.contains("fun shouldForcePaper(): Boolean = false"))
  assertTrue(s.contains("fun uiRebindGeneration(): Long ="))
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("277 / 1,458")); assertTrue(a.contains("1,181 remain")) }
}