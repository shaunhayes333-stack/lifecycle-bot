package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LivePipelineTrace6431
import com.lifecyclebot.engine.truth.LivePipelineTrace6431.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Aate8237PipelineCoverageTest {
    @Before fun reset() = LivePipelineTrace6431.resetForTest()
    @After fun clean() = LivePipelineTrace6431.resetForTest()

    @Test fun confirmedBuyPreventsUnwrittenIntermediateStagesFromClaimingAChoke() {
        repeat(12) { LivePipelineTrace6431.bumpStage(Stage.INTAKE) }
        LivePipelineTrace6431.bumpStage(Stage.FDG_ALLOW)
        LivePipelineTrace6431.bumpStage(Stage.EXEC_ATTEMPT)
        LivePipelineTrace6431.bumpStage(Stage.BUY_CONFIRMED)
        val report = LivePipelineTrace6431.render()
        assertTrue(report.contains("LIVE_PIPELINE_COVERAGE_GAP_8237: stage=LANE_SELECTED"))
        assertTrue(report.contains("BUY_CONFIRMED=1"))
        assertFalse(report.contains("LIVE_PIPELINE_CHOKE:"))
        assertFalse(report.contains("no writers"))
    }

    @Test fun noDownstreamEventsLeavesCauseUnresolvedAndRetainsRefusalEvidence() {
        LivePipelineTrace6431.bumpStage(Stage.INTAKE)
        LivePipelineTrace6431.bumpBlockReason("DATA_INCOMPLETE_DEFER_8136")
        val report = LivePipelineTrace6431.render()
        assertTrue(report.contains("LIVE_PIPELINE_GAP_UNRESOLVED_8237"))
        assertTrue(report.contains("DATA_INCOMPLETE_DEFER_8136=1"))
        assertFalse(report.contains("LIVE_PIPELINE_COVERAGE_GAP_8237"))
        assertFalse(report.contains("LIVE_PIPELINE_CHOKE:"))
    }

    @Test fun emptyTraceClaimsNeitherAChokeNorMissingSourceWriters() {
        val report = LivePipelineTrace6431.render()
        assertTrue(report.contains("NO DATA"))
        assertFalse(report.contains("LIVE_PIPELINE_CHOKE:"))
        assertFalse(report.contains("no writers"))
    }
}
