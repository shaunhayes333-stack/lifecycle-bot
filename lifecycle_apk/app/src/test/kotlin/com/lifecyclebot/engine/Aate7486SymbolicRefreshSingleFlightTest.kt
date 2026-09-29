package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7486SymbolicRefreshSingleFlightTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/SymbolicContext.kt").readText()

    @Test fun refresh_has_single_flight_owner_after_freshness_gate() {
        val s = src()
        val fn = s.substringAfter("fun refresh(symbol: String = \"\", mint: String = \"\")")
            .substringBefore("// ═══════════════════════════════════════════════════════════════════")
        val age = fn.indexOf("now - lastRefresh < 2_000L")
        val cas = fn.indexOf("refreshInFlight7486.compareAndSet(false, true)")
        val snapshot = fn.indexOf("SymbolicExitReasoner.getSignalSnapshot")
        assertTrue(age >= 0 && cas > age && snapshot > cas)
        assertTrue(fn.contains("SYMBOLIC_REFRESH_SINGLE_FLIGHT_REUSED_7486"))
    }

    @Test fun ownership_is_always_released() {
        val s = src()
        val fn = s.substringAfter("fun refresh(symbol: String = \"\", mint: String = \"\")")
            .substringBefore("// ═══════════════════════════════════════════════════════════════════")
        assertTrue(fn.contains("finally"))
        assertTrue(fn.contains("refreshInFlight7486.set(false)"))
    }

    @Test fun all_symbolic_computation_stays_present() {
        val s = src()
        assertTrue(s.contains("SymbolicExitReasoner.getSignalSnapshot(symbol, mint)"))
        assertTrue(s.contains("overallRisk ="))
        assertTrue(s.contains("overallConfidence ="))
        assertTrue(s.contains("edgeStrength ="))
        assertTrue(s.contains("save()"))
    }
}
