package com.lifecyclebot.engine.truth

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 8219 — a contaminated pre-7032 partial is excluded terminally from learning consumers, not retried as a failed delivery. */
class Aate8219ContaminatedExcludeTest {
    @Test fun contaminatedIsTerminalExclusionBeforeDelivery() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        val ex = s.indexOf("CanonicalFinalizedTradeBus6464.exclude(consumer, env.tradeId, \"CONTAMINATED_PARTIAL_7032\")")
        assertTrue(ex > 0)
        assertTrue("before the generic quarantine and delivery", ex < s.indexOf("LearningQuarantineGate6470.shouldDropForLearning(positionId = env.positionId"))
        assertTrue(s.contains("ContaminatedPartialQuarantine7032.isContaminated(env.positionId)"))
    }
}
