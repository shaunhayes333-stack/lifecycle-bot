package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7985EdgeVetoAuditTest {
    @Test fun edgeVetoAnswersToItsOwnRecord() {
        assertEquals("EDGE_VETO_ACTIVE", Cortex7885.vetoRuleOf("EDGE_VETO_ACTIVE"))
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("} else if (edgeVetoRetired7985()) {"))
        assertTrue(fdg.contains("Cortex7885.vetoRefusesWinners7953(\"EDGE_VETO_ACTIVE\")"))
    }
}
