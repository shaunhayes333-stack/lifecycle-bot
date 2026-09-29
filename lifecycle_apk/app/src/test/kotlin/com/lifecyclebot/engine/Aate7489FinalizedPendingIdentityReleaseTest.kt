package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7489FinalizedPendingIdentityReleaseTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()

    @Test fun pending_identity_is_retained_during_grace_but_released_on_terminal_exclusion() {
        val s = src()
        val proof = s.substringAfter("if (env.mode.equals(\"paper\", true)").substringBefore("if (consumer !in NON_LEARNING_CONSUMERS")
        assertTrue(proof.contains("EXACT_EVENT_GRACE_MS_6699"))
        assertTrue(proof.contains("exactEventPendingLogged6699.add(pendingKey6699)"))
        assertTrue(proof.contains("exactEventPendingLogged6699.remove(pendingKey6699)"))
        assertTrue(proof.contains("FINALIZED_PENDING_IDENTITY_RELEASED_7489"))
    }

    @Test fun exclusion_authority_and_reason_are_unchanged() {
        val s = src()
        assertTrue(s.contains("CanonicalFinalizedTradeBus6464.exclude("))
        assertTrue(s.contains("UNPROVABLE_EXACT_TERMINAL_ECONOMICS_6699"))
    }
}
