package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Locks the runtime failures captured by smoke run 3098 and build 5.0.6660. */
class Aate6661ExecutionSpineRecoveryTest {

    @Test
    fun `money mode floor normalizes a smaller shaped upper cap`() {
        assertEquals(0.06, MoneyModeSizeBounds6661.clamp(0.0067, 0.06, 0.0067), 0.0000001)
        assertEquals(0.08, MoneyModeSizeBounds6661.clamp(0.08, 0.06, 0.12), 0.0000001)
    }

    @Test
    fun `immutable intent is validated before mint version claim`() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val sizeGate = source.indexOf("meetsMinimum6491")
        val intentGate = source.indexOf("val fdgIntent6519 = immutableTicket", sizeGate)
        val claim = source.indexOf("executableBuyClaim6487.putIfAbsent", sizeGate)
        assertTrue("intent must be validated before a mint-version side effect", intentGate > sizeGate && intentGate < claim)
    }

    @Test
    fun `paper attempt identity follows sealed intent lane before snapshot projection`() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            source.contains(
                "val preTicketLane6514 = activeIntentLane6658\n" +
                    "            ?: authority6513?.executionLane"
            )
        )
    }

    @Test
    fun `fanout guardian counts actual FDG gate outcomes first`() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt").readText()
        assertTrue(
            source.contains(
                "val fdgDecisions = gateVerdicts6640.takeIf { it > 0L }\n" +
                    "            ?: canonicalVerdicts6640.takeIf { it > 0L }"
            )
        )
    }
}
