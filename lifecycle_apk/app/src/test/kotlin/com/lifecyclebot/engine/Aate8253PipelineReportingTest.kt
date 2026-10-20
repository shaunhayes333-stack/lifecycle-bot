package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LivePipelineTrace6431
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class Aate8253PipelineReportingTest {
    @After fun clear() { LivePipelineTrace6431.resetForTest() }

    @Test fun partialInstrumentationCannotClaimLaneSelectionChoke() {
        val trace = LivePipelineTrace6431
        trace.resetForTest()
        repeat(3) { trace.bumpStage(LivePipelineTrace6431.Stage.INTAKE) }
        trace.bumpStage(LivePipelineTrace6431.Stage.EXEC_ATTEMPT)
        trace.bumpStage(LivePipelineTrace6431.Stage.BUY_FAILED)
        trace.bumpBlockReason("FREEZE_AUTHORITY_UNVERIFIED")
        val report = trace.render()
        assertTrue(report.contains("UNINSTRUMENTED"))
        assertFalse(report.contains("LIVE_PIPELINE_CHOKE"))
        assertTrue(report.contains("FREEZE_AUTHORITY_UNVERIFIED=1"))
        assertTrue(trace.statusLine().contains("BUY_FAILED=1"))
        assertTrue(trace.statusLine().contains("LANE_SELECTED=UNINSTRUMENTED"))
    }
}
