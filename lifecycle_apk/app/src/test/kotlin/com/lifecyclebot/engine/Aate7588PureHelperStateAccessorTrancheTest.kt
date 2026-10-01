package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7588PureHelperStateAccessorTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `pure helper tranche remains deterministic or local state`() {
  val b=src("data/BotConfig.kt"); assertTrue(b.contains("fun validOrDefaultUrl(")); assertTrue(b.contains("fun validOrDefaultToken("))
  val o=src("perps/AlternativeOracles.kt"); assertTrue(o.split("fun isSupported(symbol: String): Boolean").size >= 3)
  val q=src("engine/quant/QuantMindV2.kt"); assertTrue(q.contains("fun getKellyFraction()")); assertTrue(q.contains("fun getOptimalLeverage()")); assertTrue(q.contains("fun getMomentumState()")); assertTrue(q.contains("fun getEdgeDecay()")); assertTrue(q.contains("fun getRecommendation()"))
  val c=src("engine/BirdeyeCreationInfoProvider.kt"); assertTrue(c.contains("fun isFreshDeploy(): Boolean")); assertTrue(c.contains("fun isYoungToken(): Boolean"))
  assertTrue(src("engine/BirdeyeWhaleFeeder.kt").contains("fun isKnownWhale("))
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("207 / 1,458")); assertTrue(a.contains("1,251 remain")) }
}