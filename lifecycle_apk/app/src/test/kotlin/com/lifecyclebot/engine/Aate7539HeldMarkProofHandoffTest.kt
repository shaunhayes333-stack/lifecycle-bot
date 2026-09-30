package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7539HeldMarkProofHandoffTest {
    @Test fun corroboratedFanoutKeepsVerifiedProofButSingleSourceDoesNot() {
        val h = File("src/main/kotlin/com/lifecyclebot/engine/truth/HeldHotMarkAuthority7419.kt").readText()
        val verified = h.substringAfter("val verified7424 =").substringBefore("val publishOk")
        assertTrue(verified.contains("HELD_HOT_FANOUT_CORROBORATED_7419"))
        assertFalse(verified.contains("HELD_HOT_SINGLE_SOURCE_7419"))
        assertTrue(h.contains("HELD_HOT_CORROBORATED_FANOUT_PUBLISHED_7539"))
        assertTrue(h.contains("HELD_HOT_SINGLE_SOURCE_NOT_PROMOTED_7539"))
    }

    @Test fun fanoutStillRequiresAgreementForCorroboratedProof() {
        val f = File("src/main/kotlin/com/lifecyclebot/network/ParallelMarkFanout7088.kt").readText()
        assertTrue(f.contains("if (best.size >= 2)"))
        assertTrue(f.contains("MARK_SINGLE_SOURCE_UNCORROBORATED_7088"))
    }
}
