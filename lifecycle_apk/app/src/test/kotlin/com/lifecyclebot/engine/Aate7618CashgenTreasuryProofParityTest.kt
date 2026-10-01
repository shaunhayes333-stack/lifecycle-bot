package com.lifecyclebot.engine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class Aate7618CashgenTreasuryProofParityTest {
 @Test fun bothCompoundersUseCashflowProof() {
  val s=File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
  assertTrue(s.contains("if (l in setOf(\"TREASURY\", \"CASHGEN\") && !cashGenProofOk())"))
 }
}
