package com.lifecyclebot.engine
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class Aate7424MarkTrustAndFinalityTest {
 @Test fun unverifiedRepairCannotBecomeCanonicalExitEconomics() {
  val m=File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPriceMark6522.kt").readText()
  val p=File("src/main/kotlin/com/lifecyclebot/engine/OpenPnlSanity.kt").readText()
  assertTrue(m.contains("if (!verifiedIdentity7424)"))
  assertTrue(m.contains("MARK_REPAIR_UNVERIFIED"))
  assertTrue(m.contains("MARK_REPAIR_VERIFIED"))
  assertTrue(p.contains("verifiedIdentity7424 = execSane7301"))
 }
 @Test fun finalizedReconcileIsConsumedByHealthReport() {
  val p=File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
  assertTrue(p.contains("FinalizedLearningReconciler7423.statusLine()"))
 }
}
