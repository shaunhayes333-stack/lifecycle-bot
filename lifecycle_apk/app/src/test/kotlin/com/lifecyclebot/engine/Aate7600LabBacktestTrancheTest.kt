package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7600LabBacktestTrancheTest {
 private fun src(r:String)=File("src/main/kotlin/com/lifecyclebot/"+r).readText()
 @Test fun `classified rows remain present`() {
  run { val s=src("engine/AsyncStrategyLab.kt"); assertTrue(s.contains("fun requestBackgroundProviderHypothesis")) }
  run { val s=src("backtest/BacktestEngine.kt"); assertTrue(s.contains("fun assetClassBreakdown")); assertTrue(s.contains("fun compareStrategies")); assertTrue(s.contains("fun runAndLog")) }
  run { val s=src("engine/lab/LlmLabEngine.kt"); assertTrue(s.contains("fun requestTransferToMainPaper")) }
  run { val s=src("engine/lab/LlmLabStore.kt"); assertTrue(s.contains("fun adjustLiveBalance")); assertTrue(s.contains("fun getLiveBalance")) }
 }
 @Test fun `audit count advances`() { val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText(); assertTrue(a.contains("527 / 1,458")); assertTrue(a.contains("931 remain")) }
}