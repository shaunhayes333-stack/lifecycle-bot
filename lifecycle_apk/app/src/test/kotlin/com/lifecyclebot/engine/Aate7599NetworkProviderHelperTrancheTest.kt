package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7599NetworkProviderHelperTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `classified rows remain present`() {
  run { val s=src("network/CoinGeckoTrending.kt"); assertTrue(s.contains("fun getTrending")); assertTrue(s.contains("fun getTrendingRank")) }
  run { val s=src("network/HeliusEnhancedWS.kt"); assertTrue(s.contains("fun updateWatchlist")) }
  run { val s=src("network/HostCircuitInterceptor.kt"); assertTrue(s.contains("fun totalNxBypassedRequests")); assertTrue(s.contains("fun totalServerBypassedRequests")) }
  run { val s=src("network/JupiterStrictTokenList.kt"); assertTrue(s.contains("fun getVerified")) }
  run { val s=src("network/KeylessLlmProviders6999.kt"); assertTrue(s.contains("fun chatWithModel")); assertTrue(s.contains("fun models")) }
  run { val s=src("network/KeylessPriceSources6996.kt"); assertTrue(s.contains("fun defiLlamaBatch")); assertTrue(s.contains("fun jupiterBatch")) }
  run { val s=src("network/SolanaWallet.kt"); assertTrue(s.contains("fun applyRoundRobin")); assertTrue(s.contains("fun awaitConfirmation")); assertTrue(s.contains("fun compactU16")); assertTrue(s.contains("fun finalized")); assertTrue(s.contains("fun getTokenAccountsChecked")); assertTrue(s.contains("fun getTokenAccountsWithDecimals")); assertTrue(s.contains("fun getTokenAccountsWithDecimalsStrict")); assertTrue(s.contains("fun markEndpointUnhealthy")); assertTrue(s.contains("fun mergeFrom")) }
  run { val s=src("network/SolscanDevTracker.kt"); assertTrue(s.contains("fun getRecentTransactions")) }
  run { val s=src("network/JupiterApi.kt"); assertTrue(s.contains("fun isTransient")) }
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("520 / 1,458")); assertTrue(a.contains("938 remain")) }
}