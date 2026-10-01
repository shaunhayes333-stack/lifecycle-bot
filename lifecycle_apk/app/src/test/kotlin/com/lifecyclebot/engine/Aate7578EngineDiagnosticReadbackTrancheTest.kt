package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7578EngineDiagnosticReadbackTrancheTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `engine diagnostic tranche consists of pure readbacks`() {
        assertTrue(src("engine/CatastrophicExitLatency.kt").contains("fun activeTraceCount(): Int = active.size"))
        assertTrue(src("engine/CatastrophicExitLatency.kt").contains("fun emittedTraceCount(): Long = emittedTraces.get()"))
        assertTrue(src("engine/ExitCoordinatorHeartbeat.kt").contains("fun falseResetsPreventedCount(): Long = falseResetsPrevented.get()"))
        assertTrue(src("engine/ExitCoordinatorHeartbeat.kt").contains("fun justifiedResetCount(): Long = justifiedResets.get()"))
        assertTrue(src("engine/ExitCoordinatorHeartbeat.kt").contains("fun staleResetCount(): Long = staleResets.get()"))
        assertTrue(src("engine/ForensicReconciler6377.kt").contains("fun lifetimeMismatchCount(): Long = mismatchCount.get()"))
        assertTrue(src("engine/ForensicReconciler6377.kt").contains("fun lifetimePassCount(): Long = passCount.get()"))
        assertTrue(src("engine/LoopCycleEmergencyEvict6352.kt").contains("fun totalShedCount(): Long = shedCount.get()"))
        assertTrue(src("engine/RejectionTelemetry.kt").contains("fun totalSessionCount(): Long = totalSession.get()"))
        assertTrue(src("engine/RuntimeRepairState.kt").contains("fun staleLocksClearedCount(): Long = staleLocksCleared.get()"))
        assertTrue(src("engine/ScannerFanoutDedupe6374.kt").contains("fun admitCount(): Long = admits.get()"))
        assertTrue(src("engine/ScannerFanoutDedupe6374.kt").contains("fun skipCount(): Long = skips.get()"))
    }

    @Test
    fun `audit updates wider remaining count`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7578 — F_DEAD engine diagnostic counter-readback tranche (12 rows)"))
        assertTrue(audit.contains("86 / 1,458"))
        assertTrue(audit.contains("1,372 remain"))
    }
}
