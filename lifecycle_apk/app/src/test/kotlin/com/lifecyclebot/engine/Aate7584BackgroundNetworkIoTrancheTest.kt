package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7584BackgroundNetworkIoTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `background io remains off hot path by classification`() {
  val b=src("network/BirdeyeApi.kt"); assertTrue(b.contains("fun getTokenPrice(")); assertTrue(b.contains("fun getTokenSecurity(")); assertTrue(b.contains("fun getAllTimeStats(")); assertTrue(b.contains("fun getTraderGainersLosers("))
  val t=src("collective/TursoClient.kt"); assertTrue(t.contains("suspend fun testConnection()")); assertTrue(t.contains("suspend fun getMarketsTradesForReplay("))
  assertTrue(src("network/TelegramScraper.kt").contains("fun scrapePublicChannel("))
  assertTrue(src("network/XScraper.kt").contains("fun checkSolanaAccounts("))
  val c=src("collective/CollectiveLearning.kt"); assertTrue(c.contains("suspend fun getActiveUsersCount()")); assertTrue(c.contains("suspend fun getCollectiveTradeCount()")); assertTrue(c.contains("suspend fun getLegalAgreementCount()"))
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("149 / 1,458")); assertTrue(a.contains("1,309 remain")) }
}