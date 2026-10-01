package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7598PerpsMarketsAccessorTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `perps accessor rows remain named surfaces`() {
  run { val s=src("perps/CorrelationScanner.kt"); assertTrue(s.contains("fun getCorrelationStrength")); assertTrue(s.contains("fun hasEnoughData")) }
  run { val s=src("perps/CryptoAltScannerAI.kt"); assertTrue(s.contains("fun getAltBeta")); assertTrue(s.contains("fun getTopSectors")) }
  run { val s=src("perps/CryptoAltTrader.kt"); assertTrue(s.contains("fun getClosedPositions")); assertTrue(s.contains("fun hasPositionSymbol")); assertTrue(s.contains("fun hasTrustedMark")) }
  run { val s=src("perps/crypto/brain/CryptoBrain.kt"); assertTrue(s.contains("fun getLevConfFloor")); assertTrue(s.contains("fun getSlPct")); assertTrue(s.contains("fun shouldShadowOnly")) }
  run { val s=src("perps/crypto/CryptoWrappedAssetMapper.kt"); assertTrue(s.contains("fun isNativeOnly")) }
  run { val s=src("perps/DynamicAltTokenRegistry.kt"); assertTrue(s.contains("fun getNewTokens")); assertTrue(s.contains("fun getTokensBySector")) }
  run { val s=src("perps/JupiterPerps.kt"); assertTrue(s.contains("fun getActiveOrders")); assertTrue(s.contains("fun getFailedOrders")); assertTrue(s.contains("fun getSuccessfulOrders")); assertTrue(s.contains("fun getTotalOrders")) }
  run { val s=src("perps/MarketsLiveExecutor.kt"); assertTrue(s.contains("fun getSuccessRate")); assertTrue(s.contains("fun getTotalExecutions")); assertTrue(s.contains("fun getTotalFeesCollected")) }
  run { val s=src("perps/MarketsScanner.kt"); assertTrue(s.contains("fun getChinaStocks")); assertTrue(s.contains("fun getEuropeStocks")); assertTrue(s.contains("fun getGoldMiners")); assertTrue(s.contains("fun getJapanStocks")); assertTrue(s.contains("fun getSilverMiners")) }
  run { val s=src("perps/PerpsAdvancedAI.kt"); assertTrue(s.contains("fun getBestTradingHours")); assertTrue(s.contains("fun getCorrelatedPositions")); assertTrue(s.contains("fun getSectorRotation")); assertTrue(s.contains("fun isHighlyCorrelated")); assertTrue(s.contains("fun isInHotSector")) }
  run { val s=src("perps/PerpsAutoReplayLearner.kt"); assertTrue(s.contains("fun getLayerAdjustments")); assertTrue(s.contains("fun isLearning")) }
  run { val s=src("perps/PerpsCorrelationMatrix.kt"); assertTrue(s.contains("fun getCorrelationMatrix")); assertTrue(s.contains("fun getGroupMembers")); assertTrue(s.contains("fun getMarketGroup")) }
  run { val s=src("perps/PerpsModels.kt"); assertTrue(s.contains("fun isReadyForLive")) }
  run { val s=src("perps/PerpsLearningInsightsPanel.kt"); assertTrue(s.contains("fun getActionableInsights")); assertTrue(s.contains("fun getHighPriorityInsights")); assertTrue(s.contains("fun getInsightsByType")); assertTrue(s.contains("fun getMarketInsights")) }
  run { val s=src("perps/PerpsMarketDataFetcher.kt"); assertTrue(s.contains("fun getAllMarketsData")) }
  run { val s=src("perps/PerpsMarketScanners.kt"); assertTrue(s.contains("fun getLastScanTime")); assertTrue(s.contains("fun getTotalScans")) }
  run { val s=src("perps/PerpsTraderAI.kt"); assertTrue(s.contains("fun getCurrentStreak")) }
  run { val s=src("perps/PythOracle.kt"); assertTrue(s.contains("fun isTradable")) }
  run { val s=src("perps/TokenizedStockTrader.kt"); assertTrue(s.contains("fun isRegularTradingHours")) }
  run { val s=src("perps/WatchlistEngine.kt"); assertTrue(s.contains("fun isOnWatchlist")) }
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("499 / 1,458")); assertTrue(a.contains("959 remain")) }
}