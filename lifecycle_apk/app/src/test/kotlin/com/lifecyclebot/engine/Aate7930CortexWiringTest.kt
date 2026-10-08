package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7930 — Cortex wiring audit: priors yield to measured evidence; proven losers never do. */
class Aate7930CortexWiringTest {
    @Test fun onlyUnmeasuredPriorsYieldToEvidence() {
        assertTrue(LiveEdgeGate7877.priorOnly7930("PLAYBOOK_NO_TRIGGER_7907_SHITCOIN"))
        assertTrue(LiveEdgeGate7877.priorOnly7930("STAGE_LANE_MISFIT_7928_QUALITY_FRESH_LAUNCH"))
        assertFalse(LiveEdgeGate7877.priorOnly7930("PLAYBOOK_SETUP_PROVEN_LOSING_7907_SHITCOIN_FAST_CROWD"))
        assertFalse(LiveEdgeGate7877.priorOnly7930("STAGE_PROVEN_LOSING_7928_SHITCOIN_PEAK_EXHAUSTION"))
        assertFalse(LiveEdgeGate7877.priorOnly7930("STAGE_RUG_PRONE_7928_SHITCOIN"))
        assertFalse(LiveEdgeGate7877.priorOnly7930("PLAYBOOK_NO_TRIGGER_PROVEN_LOSING_7907_SHITCOIN"))
        assertFalse(LiveEdgeGate7877.priorOnly7930("STAGE_LANE_MISFIT_7928_SHITCOIN_PEAK_EXHAUSTION"))
        assertFalse(LiveEdgeGate7877.priorOnly7930("STAGE_LANE_MISFIT_7928_SHITCOIN_DUMPING"))
    }
}
