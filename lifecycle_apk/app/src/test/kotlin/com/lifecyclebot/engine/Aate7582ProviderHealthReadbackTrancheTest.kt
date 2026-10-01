package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7582ProviderHealthReadbackTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `provider helpers stay queries not new authority`() {
  assertTrue(src("network/DexScreenerWebSocket.kt").contains("fun getSubscribedCount(): Int = subscribedPairs.size"))
  val q=src("engine/LiveProviderQuorum.kt"); assertTrue(q.contains("fun hostHealthy(")); assertTrue(q.contains("fun hostDegraded("))
  val p=src("engine/ProviderAuthority.kt"); assertTrue(p.contains("fun isDegraded(")); assertTrue(p.contains("fun allowedRoles(")); assertTrue(p.contains("fun canFulfill(")); assertTrue(p.contains("fun deviationPct("))
  val e=src("engine/sell/ExitProviderHealth.kt"); assertTrue(e.contains("fun jupiterProbeReady()")); assertTrue(e.contains("fun jupiterCooldownRemainingMs()")); assertTrue(e.contains("fun pumpRouteInvalidatedRecently("))
  assertTrue(src("engine/truth/ProviderCircuitBreaker6402.kt").contains("fun totalSkipEvents(): Long = skipEvents.get()"))
  assertTrue(src("perps/NetworkSignalAutoBuyer.kt").contains("fun getDailyRemaining(): Int ="))
  assertTrue(src("perps/PythOracle.kt").contains("fun isPriceFeedHealthy(symbol: String): Boolean"))
  val c=src("engine/CloudLearningSync.kt"); assertTrue(c.contains("fun isOptedIn(): Boolean = optedIn")); assertTrue(c.contains("fun isUsingCommunityWeights(): Boolean = usingCommunityWeights"))
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("131 / 1,458")); assertTrue(a.contains("1,327 remain")) }
}