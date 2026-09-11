package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6720 — §CAUSAL_RESERVATION_LIFECYCLE + §EXIT_COORDINATOR_LIFECYCLE.
 *
 * Operator diagnosis of 5.0.6719 dump:
 *   - UNRESOLVED_FEEDBACK_CAP_6715=1524 (91% of EXEC_GATE blocks) even after
 *     the ghost-position fix. Root cause: reservations LEAK when an attempt
 *     dies downstream of admit() without calling releaseAttempt (BELOW_MIN_
 *     NOTIONAL, EXPIRED_TICKET_ECONOMIC_REJECT_6614, SIZE_NOT_EXECUTABLE_6491,
 *     etc.) — 25 QUALITY sized/0 ticketed, 73 CORE sized/1 ticketed, 5
 *     MOONSHOT sized/0 ticketed. Every one holds a reservation forever.
 *
 *   - EXIT_COORDINATOR_NO_START_RELAUNCHED_6647 fired 117 times in 119 bot
 *     cycles. Root cause: deadline check compared `heartbeat >= requestedAt`.
 *     Every new sweep request bumps `requestedAt` to now(). During the
 *     coordinator's own delay() sleep between iterations, any fresh request
 *     instantly makes requestedAt > heartbeat, tripping the "no start" check,
 *     cancelling the live job, and relaunching it. The coordinator was
 *     being killed almost every tick despite processing correctly.
 *
 * Fixes:
 *   A. CausalFeedbackAuthority6715.admit() now runs sweepStaleReservations
 *      Locked at the top of every call. Any reservation older than 60s is
 *      auto-released. Emits CAUSAL_RESERVATION_TTL_SWEPT_6720. 60s is well
 *      past normal admit→open lifecycle (~5s) so live reservations are
 *      never yanked.
 *
 *   B. CausalFeedbackAuthority6715.admit() now supersedes any prior live
 *      reservation for the same (mode, mint, lane) triple when a fresher
 *      attempt is admitted. The old attempts lost owner election and MUST
 *      NOT keep consuming the cap. Emits CAUSAL_RESERVATION_SUPERSEDED_6720.
 *
 *   C. BotService.enforceExitStartDeadline6647 now decouples liveness from
 *      request timing. Coordinator is healthy if its Job.isActive AND its
 *      heartbeat updated within HEARTBEAT_STALENESS_MS (15s). Only a truly
 *      stuck/dead coordinator relaunches. Kills the 117× relaunch storm.
 */
class Aate6720CausalReservationAndExitCoordinatorTest {

    private val causal = File("src/main/kotlin/com/lifecyclebot/engine/truth/CausalFeedbackAuthority6715.kt").readText()
    private val botService = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test
    fun `Fix A — CausalFeedbackAuthority admit sweeps stale reservations before every check`() {
        assertTrue(
            "V5.0.6720 §CAUSAL_RESERVATION_LIFECYCLE marker must appear",
            causal.contains("V5.0.6720 §CAUSAL_RESERVATION_LIFECYCLE"),
        )
        assertTrue(
            "sweepStaleReservationsLocked must exist",
            causal.contains("private fun sweepStaleReservationsLocked(nowMs: Long)"),
        )
        assertTrue(
            "TTL sweep must run inside the same lock as the admit check",
            causal.contains("sweepStaleReservationsLocked(System.currentTimeMillis())") &&
                causal.contains("§CAUSAL_RESERVATION_LIFECYCLE"),
        )
        assertTrue(
            "Sweep must emit CAUSAL_RESERVATION_TTL_SWEPT_6720 telemetry",
            causal.contains("CAUSAL_RESERVATION_TTL_SWEPT_6720"),
        )
    }

    @Test
    fun `Fix B — admit supersedes older reservations for same mode-mint-lane triple`() {
        assertTrue(
            "Supersede branch must remove prior reservations for same (mode, mint, lane)",
            causal.contains("it.mode == nm && it.mint == mint && it.lane == nl && it.attemptId != attemptId"),
        )
        assertTrue(
            "Superseded events must emit CAUSAL_RESERVATION_SUPERSEDED_6720",
            causal.contains("CAUSAL_RESERVATION_SUPERSEDED_6720"),
        )
    }

    @Test
    fun `Fix C — exit coordinator deadline check decouples liveness from request timing`() {
        assertTrue(
            "V5.0.6720 §EXIT_COORDINATOR_LIFECYCLE marker must appear",
            botService.contains("V5.0.6720 §EXIT_COORDINATOR_LIFECYCLE"),
        )
        assertTrue(
            "Deadline check must consult Job.isActive AND heartbeat freshness",
            botService.contains("val jobAlive = exitSweepCoordinatorJob?.isActive == true") &&
                botService.contains("ExitCoordinatorHealth6737.healthy(jobAlive, exitCoordinatorStartHeartbeatMs6647.get(), now)") &&
                botService.contains("if (jobAlive && heartbeatFresh) return"),
        )
        assertFalse(
            "Legacy timing-only check (heartbeat >= requestedAt short-circuit at top) must be removed as the only guard",
            botService.contains("""if (exitCoordinatorStartHeartbeatMs6647.get() >= requestedAt) return
        synchronized(exitSweepCoordinatorLock) {
            if (exitCoordinatorStartHeartbeatMs6647.get() >= requestedAt) return
            exitSweepCoordinatorJob?.cancel()"""),
        )
    }
}
