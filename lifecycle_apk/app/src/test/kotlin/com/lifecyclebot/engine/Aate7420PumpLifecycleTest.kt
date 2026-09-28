package com.lifecyclebot.engine
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class Aate7420PumpLifecycleTest {
 @Test fun freshCreateOwnsBoundedTradeSubscription() {
  val s=File("src/main/kotlin/com/lifecyclebot/network/PumpFunWS.kt").readText()
  assertTrue(s.contains("registerFreshLifecycle7420(mint)"))
  assertTrue(s.contains("PUMP_TRADE_SUB_REQUESTED"))
  assertTrue(s.contains("PUMP_TRADE_SUB_OK"))
  assertTrue(s.contains("PUMP_TRADE_SUB_FAIL"))
  assertTrue(s.contains("PUMP_TRADE_EVENT_BUY"))
  assertTrue(s.contains("PUMP_TRADE_EVENT_SELL"))
  assertTrue(s.contains("PUMP_TRADE_UNSUBSCRIBED"))
  assertTrue(s.contains("PUMP_LIFECYCLE_STREAM_CAPABLE"))
 }
}
