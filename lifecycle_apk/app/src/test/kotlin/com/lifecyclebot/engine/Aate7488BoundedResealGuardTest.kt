package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7488BoundedResealGuardTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()

    @Test fun reseal_guard_is_timestamped_bounded_and_long_horizon() {
        val s = src()
        assertTrue(s.contains("resealedTickets7488 = ConcurrentHashMap<String, Long>()"))
        assertTrue(s.contains("RESEAL_GUARD_TTL_MS_7488 = 20L * 60_000L"))
        assertTrue(s.contains("RESEAL_GUARD_SOFT_CAP_7488 = 12_000"))
        assertTrue(s.contains("RESEAL_GUARD_PRUNED_7488"))
    }

    @Test fun reseal_still_requires_all_existing_economic_checks() {
        val s = src()
        val fn = s.substringAfter("private fun revalidateAndResealExpired6613")
            .substringBefore("fun clearPendingForMint")
        assertTrue(fn.contains("claimResealAttempt7488(intent.attemptId)"))
        assertTrue(fn.contains("CausalFeedbackAuthority6715.isDecisionCurrent"))
        assertTrue(fn.contains("CanonicalPositionAuthority6441.openPositions()"))
        assertTrue(fn.contains("SealedOrderSizeAuthority6497.sealedSize"))
        assertTrue(fn.contains("resolveEntryMarkForMode7465"))
    }

    @Test fun test_reset_clears_bounded_guard() {
        assertTrue(src().contains("resealedTickets7488.clear()"))
    }
}
