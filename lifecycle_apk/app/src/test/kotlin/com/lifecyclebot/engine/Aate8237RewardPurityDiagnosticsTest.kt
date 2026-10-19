package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.RewardPurityGate6441
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate8237RewardPurityDiagnosticsTest {
    @Before fun reset() = RewardPurityGate6441.clearLifecycleDiagnosticsForTest8237()
    @After fun clean() = reset()

    @Test fun eightRetriesExposeOneExactUnresolvedEnvelopeWithoutLearningIt() {
        val gate = RewardPurityGate6441
        val pid = "missing-durable-position-8237"
        val eventId = "durable-terminal-event-8237"
        val before = gate.canonicalCounts()
        repeat(8) { assertFalse(gate.acceptFinalizedClose(pid, -0.002, eventId, envelopeMode = "live")) }
        assertNull(gate.outcomeOf(pid))
        assertEquals(before, gate.canonicalCounts())
        val line = gate.statusLine()
        assertTrue(line, line.contains("lifecyclePendingSamples8237=1"))
        assertTrue(line, line.contains("pid=$pid mode=live economicEventId=$eventId"))
        assertTrue(line, line.contains("expected=CLOSED actual=MISSING positionMode=MISSING remainingRaw=UNKNOWN attempts=8"))
    }

    @Test fun diagnosticsStayBoundedWhenDifferentDurableEnvelopesAreUnresolved() {
        repeat(20) { n ->
            assertFalse(RewardPurityGate6441.acceptFinalizedClose("missing-durable-position-8237-$n", -0.002,
                "durable-terminal-event-8237-$n", envelopeMode = "paper"))
        }
        val line = RewardPurityGate6441.statusLine()
        assertTrue(line, line.contains("lifecyclePendingSamples8237=8"))
        assertTrue(line, line.contains("pid=missing-durable-position-8237-19 mode=paper"))
    }
}
