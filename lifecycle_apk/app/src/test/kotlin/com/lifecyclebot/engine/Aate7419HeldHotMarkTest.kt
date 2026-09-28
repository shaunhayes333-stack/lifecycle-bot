package com.lifecyclebot.engine
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class Aate7419HeldHotMarkTest {
 @Test fun heldHotWorkerIsIndependentAndRiskClockOnlyStartsIt() {
  val h=File("src/main/kotlin/com/lifecyclebot/engine/truth/HeldHotMarkAuthority7419.kt").readText()
  val r=File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalRiskClock6454.kt").readText()
  assertTrue(r.contains("HeldHotMarkAuthority7419.start()"))
  assertTrue(r.contains("HeldHotMarkAuthority7419.stop()"))
  assertTrue(h.contains("HELD_HOT_MARK_REQUEST"))
  assertTrue(h.contains("HELD_HOT_MARK_ADVANCED"))
  assertTrue(h.contains("HELD_HOT_MARK_TIMEOUT"))
  assertFalse(h.contains("KeylessPriceSources"))
  assertFalse(h.contains("MainActivity"))
 }
}
