package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7502TokenBirthDormantStateTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/TokenBirthHydrator7441.kt").readText()

    @Test fun partial_progress_is_timestamped_and_bounded() {
        val s = src()
        assertTrue(s.contains("lastTouchedMs"))
        assertTrue(s.contains("DORMANT_PROGRESS_TTL_MS_7502 = 24L * 60L * 60_000L"))
        assertTrue(s.contains("PROGRESS_SOFT_CAP_7502 = 8_000"))
    }

    @Test fun pruning_never_touches_inflight_hydration() {
        val s = src()
        assertTrue(s.contains("it.key !in inFlight"))
        assertTrue(s.contains("TOKEN_BIRTH_DORMANT_STATE_PRUNED_7502"))
    }

    @Test fun successful_completion_still_clears_state() {
        val s = src()
        val fn = s.substringAfter("private fun publishIfComplete")
        assertTrue(fn.contains("progress.remove(mint)"))
        assertTrue(fn.contains("cooldownUntil.remove(mint)"))
    }
}
