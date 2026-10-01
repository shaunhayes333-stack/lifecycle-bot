package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7585OperatorConfigControlTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `operator controls remain explicit controls`() {
  val c=src("engine/CloudLearningSync.kt"); assertTrue(c.contains("fun setOptIn(")); assertTrue(c.contains("fun setUseCommunityWeights("))
  val n=src("perps/PerpsNotificationManager.kt"); assertTrue(n.contains("fun setSoundEnabled(")); assertTrue(n.contains("fun setVibrationEnabled("))
  val t=src("engine/TimeModeScheduler.kt"); assertTrue(t.contains("fun setAutoSwitchEnabled(")); assertTrue(t.contains("fun setScheduleOverride(")); assertTrue(t.contains("fun clearSchedule()"))
  val v=src("engine/V3ConfidenceConfig.kt"); assertTrue(v.contains("fun setMode(")); assertTrue(v.contains("fun setCustomOverrides(")); assertTrue(v.contains("fun clearCustomOverrides()"))
  val f=src("engine/FreeRangeMode.kt"); assertTrue(f.contains("fun forceOff()")); assertTrue(f.contains("fun forceOn()")); assertTrue(f.contains("fun clearOverride()"))
  assertTrue(src("engine/LeveragePreference.kt").contains("fun setLeveragePreferred("))
 }
 @Test fun `corrected audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("150 / 1,458 classified; 1,308 remain")); assertTrue(a.contains("164 / 1,458")); assertTrue(a.contains("1,294 remain")) }
}