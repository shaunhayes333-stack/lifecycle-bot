package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PreEntryDecisionRecord6345Test {
    @Before fun reset() { PreEntryDecisionRecord6345.resetForTest() }

    @Test
    fun pass_when_all_deterministic_facts_are_present() {
        val r = PreEntryDecisionRecord6345.emit(
            mint = "MintA", symbol = "SYM",
            laneRequested = "STANDARD", laneCanonical = "STANDARD",
            expectedRMultiple = 1.5, stopDistancePct = 8.0,
            executableSpreadBps = 25, liquidityUsd = 50_000.0,
            hydrationState = "LIVE_READY", sampleSizeCanonical = 40,
            governorState = "BASELINE",
        )
        assertEquals(PreEntryDecisionRecord6345.Verdict.PASS.name, r.foundationPolicyVerdict)
        assertTrue(r.foundationReasons.isEmpty())
    }

    @Test
    fun veto_when_multiple_deterministic_facts_missing() {
        val r = PreEntryDecisionRecord6345.emit(
            mint = "MintB", symbol = "BAD",
            laneRequested = "STANDARD", laneCanonical = "STANDARD",
            expectedRMultiple = 0.0, stopDistancePct = 0.0,
            executableSpreadBps = 0, liquidityUsd = 0.0,
            hydrationState = "", sampleSizeCanonical = 0,
            governorState = "BASELINE",
        )
        assertEquals(PreEntryDecisionRecord6345.Verdict.VETO.name, r.foundationPolicyVerdict)
        assertTrue(r.foundationReasons.contains("EXP_R_NON_POSITIVE"))
        assertTrue(r.foundationReasons.contains("STOP_DISTANCE_UNKNOWN"))
    }

    @Test
    fun warn_when_only_hydration_or_spread_is_missing() {
        val r = PreEntryDecisionRecord6345.emit(
            mint = "MintC", symbol = "SYM",
            laneRequested = "STANDARD", laneCanonical = "STANDARD",
            expectedRMultiple = 1.5, stopDistancePct = 8.0,
            executableSpreadBps = 0,       // only spread unknown
            liquidityUsd = 50_000.0,
            hydrationState = "LIVE_READY", sampleSizeCanonical = 40,
            governorState = "BASELINE",
        )
        assertEquals(PreEntryDecisionRecord6345.Verdict.WARN.name, r.foundationPolicyVerdict)
        assertEquals(listOf("SPREAD_UNKNOWN"), r.foundationReasons)
    }

    @Test
    fun compact_line_carries_every_deterministic_fact() {
        val r = PreEntryDecisionRecord6345.emit(
            mint = "MintD", symbol = "SD",
            laneRequested = "STANDARD", laneCanonical = "STANDARD",
            expectedRMultiple = 2.0, stopDistancePct = 6.0,
            executableSpreadBps = 30, liquidityUsd = 40_000.0,
            hydrationState = "LIVE_READY", sampleSizeCanonical = 10,
            governorState = "BASELINE",
        )
        val line = r.toCompactLine()
        assertTrue(line.contains("expR=2.00"))
        assertTrue(line.contains("stopPct=6.00"))
        assertTrue(line.contains("spreadBps=30"))
        assertTrue(line.contains("policy=PASS"))
    }
}
