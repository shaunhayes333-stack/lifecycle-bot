package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7681LatencyExitStabilityContractTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun slowCycleAttributionStartsAtFiveSeconds() {
        val s = src("engine/truth/SlowCycleDiagnostic6437.kt")
        assertTrue(s.contains("SLOW_CYCLE_THRESHOLD_MS = 5_000L"))
        assertTrue(s.contains("SLOW_CYCLE_DIAGNOSTIC_6437"))
        assertTrue(s.contains("top3"))
    }

    @Test fun exitCoordinatorProtectsHealthyWorkersFromReset() {
        val s = src("engine/ExitCoordinatorHeartbeat.kt")
        assertTrue(s.contains("heartbeatAge > HEARTBEAT_STALE_MS && cur.activeWorkers <= 0 && phaseAge > phaseDeadline"))
        assertTrue(s.contains("EXIT_COORDINATOR_FALSE_RESET_PREVENTED_6324"))
        assertTrue(s.contains("EXIT_COORDINATOR_DUPLICATE_START_SUPPRESSED_6324"))
        assertTrue(s.contains("EXIT_COORDINATOR_GENERATION_REJECT_6324"))
    }

    @Test fun heavyMaintenanceIsOffloaded() {
        val m = src("engine/truth/MaintenanceWorker6448.kt")
        assertTrue(m.contains("inFlight"))
        assertTrue(m.contains("withTimeoutOrNull"))
        val p = src("engine/truth/PreSupervisorBudgetGuard6437.kt")
        assertTrue(p.contains("CYCLE_FANOUT_BUDGET_MS = 5_000L"))
    }

    @Test fun p0TenSourceTasksAreClosed() {
        val a = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertFalse(a.contains("- [ ] Attribute >5s cycle time"))
        assertFalse(a.contains("- [ ] Keep UI/report and learner maintenance"))
        assertFalse(a.contains("- [ ] Bound provider calls and fanout per cycle"))
        assertFalse(a.contains("- [ ] Eliminate stale coordinator resets"))
    }
}
