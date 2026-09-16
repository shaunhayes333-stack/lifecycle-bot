package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6759 — regression fence for the ApiBackoff dual-schedule repair
 * (originally landed on `fix/exit-api-reliability-6758`).
 *
 * Locks the source-level invariants so a future refactor cannot collapse the
 * rate-limit and transient schedules back into a single table:
 *
 *   • 429 → `rateLimitSchedule` (2 min → 30 min). A paid-tier quota storm
 *     must NOT be re-hit every 30 seconds.
 *   • 401 / 403 → `authBackoffSchedule` (1 min → 10 min). Auth/forbidden
 *     is not a wire error and needs its own quiet window.
 *   • 5xx / 408 / 425 → `softBackoffSchedule` (2 s → 30 s). Transient wire
 *     errors recover fast so a single 503 does not silence a healthy provider.
 *   • `markFailure` is fail-open on every internal exception path.
 */
class Aate6759ApiBackoffRateVsTransientSchedulesTest {

    private val src by lazy {
        File("src/main/kotlin/com/lifecyclebot/engine/ApiBackoff.kt").readText()
    }

    @Test fun rate_limit_schedule_is_dedicated_and_long_lived() {
        assertTrue("rateLimitSchedule constant must exist", src.contains("rateLimitSchedule"))
        // The 429 → rateLimitSchedule mapping must be explicit and dispatched inside markFailure.
        assertTrue(
            "429 responses must dispatch to rateLimitSchedule",
            src.contains("429 -> rateLimitSchedule"),
        )
        // Long-lived floor and cap so a single 429 quota storm cannot loop.
        assertTrue("floor must be ≥ 2 minutes", src.contains("120_000L"))
        assertTrue("cap must be 30 minutes", src.contains("1_800_000L"))
    }

    @Test fun transient_schedule_is_separate_and_short_lived() {
        assertTrue("softBackoffSchedule constant must exist", src.contains("softBackoffSchedule"))
        // 5xx / non-429 4xx routes to the soft schedule so healthy providers
        // recover fast after a wobble.
        assertTrue(
            "non-rate-limit / non-auth codes must dispatch to softBackoffSchedule",
            src.contains("else -> softBackoffSchedule"),
        )
        assertTrue("short floor 2 s", src.contains("2_000L"))
        assertTrue("short cap 30 s", src.contains("30_000L"))
    }

    @Test fun auth_schedule_present_and_firm_but_not_permanent() {
        assertTrue("authBackoffSchedule constant must exist", src.contains("authBackoffSchedule"))
        assertTrue(
            "401 and 403 must dispatch to authBackoffSchedule",
            src.contains("401, 403 -> authBackoffSchedule"),
        )
    }

    @Test fun mark_failure_is_fail_open_on_internal_exceptions() {
        assertTrue(
            "markFailure must wrap its body in a fail-open try/catch",
            src.contains("catch (_: Throwable) { /* fail-open */ }"),
        )
    }
}
