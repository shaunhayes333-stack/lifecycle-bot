package com.lifecyclebot.engine
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class Aate7422SmartMoneyAccountingTest {
 @Test fun accountingDoesNotAddExecutionRoute() {
  val c=File("src/main/kotlin/com/lifecyclebot/engine/CopyTradeEngine.kt").readText()
  val h=File("src/main/kotlin/com/lifecyclebot/engine/SmartMoneyBridgeHealth7422.kt").readText()
  assertTrue(c.contains("SmartMoneyBridgeHealth7422.detected()"))
  assertTrue(c.contains("Disposition.DUPLICATE"))
  assertTrue(c.contains("Disposition.INSUFFICIENT_EVIDENCE"))
  assertTrue(c.contains("Disposition.CANDIDATE_CREATED"))
  assertTrue(h.contains("Accounting only. No execution authority."))
  assertTrue(h.contains("fun unexplained()"))
 }
}
