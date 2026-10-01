package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7590CachedStateAccessorTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `accessor tranche stays lookup report or config read`() {
  assertTrue(src("engine/sell/BalanceProofWaitState.kt").contains("fun getWaiting("))
  assertTrue(src("engine/truth/CanonicalIdentityModel6464.kt").contains("fun getIdentity("))
  val i=src("v3/scoring/InsiderTrackerAI.kt"); assertTrue(i.contains("fun getAllWallets()")); assertTrue(i.contains("fun getWalletActivity("))
  assertTrue(src("engine/LeveragePreference.kt").contains("fun isLeveragePreferred("))
  val m=src("engine/ModeSpecificScanners.kt"); assertTrue(m.contains("fun getCached(")); assertTrue(m.contains("fun getMinBuyPressure()")); assertTrue(m.contains("fun getMinDipDepth()")); assertTrue(m.contains("fun getMinImpulse()"))
  val r=src("engine/RunTracker30D.kt"); assertTrue(r.contains("fun getTradeTrace(")); assertTrue(r.contains("fun isRunComplete()"))
  val s=src("v3/arb/SourceTimingRegistry.kt"); assertTrue(s.contains("fun getFirstSeen(")); assertTrue(s.contains("fun getLatestSeen(")); assertTrue(s.contains("fun getSourceCount(")); assertTrue(s.contains("fun getVenueLagMs("))
  val tm=src("engine/TimeModeScheduler.kt"); assertTrue(tm.contains("fun getSchedule()")); assertTrue(tm.contains("fun getBestHoursForMode(")); assertTrue(tm.contains("fun getWorstHoursForMode("))
  assertTrue(src("perps/TokenizedAssetRegistry.kt").contains("fun knownSymbols()"))
  val v=src("engine/V3ConfidenceConfig.kt"); assertTrue(v.contains("fun getAllModes()")); assertTrue(v.contains("fun getModeDescription()")); assertTrue(v.contains("fun hasCustomOverrides()"))
  val w=src("engine/WhaleWalletTracker.kt"); assertTrue(w.contains("fun getWatchedWhales()")); assertTrue(w.contains("fun getWatchedWhaleMovements()"))
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("253 / 1,458")); assertTrue(a.contains("1,205 remain")) }
}