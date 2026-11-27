package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.RunnerTargetPath9049
import com.lifecyclebot.engine.truth.ResearchRows8877
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class Aate9049SharedRunnerEvidenceTest {
    @Test fun targetsAndStopRetainObservedOrderNotFinalPeakOrder() {
        val p = RunnerTargetPath9049.start(100000L,105000L,20.0,3.0)!!
        p.observe(106000L,34.0)
        p.observe(107000L,-21.0)
        p.observe(108000L,204.0)
        val j = JSONObject(p.json())
        val hits = j.getJSONArray("firstTargetMs")
        assertEquals(106000L,hits.getLong(0))
        assertEquals(108000L,hits.getLong(1))
        assertEquals(108000L,hits.getLong(3))
        assertTrue(hits.isNull(4))
        assertEquals(107000L,j.getLong("firstStopMs"))
        assertFalse(j.getBoolean("liveAuthority"))
        assertEquals(3,j.getInt("n"))
    }
    @Test fun sourceTimeAndRestartDoNotManufactureOutcome() {
        val p = RunnerTargetPath9049.start(100000L,105000L,15.0,3.0)!!
        p.observe(106000L,31.9) // below net +30
        p.observe(106000L,500.0) // duplicate source time, not another observation
        p.observe(104000L,500.0)
        p.observe(3700001L,500.0) // beyond the decision's hour
        p.observe(107000L,Double.NaN)
        val restored = RunnerTargetPath9049.unpack(p.pack(),100000L,105000L)!!
        assertEquals(p.json(),restored.json())
        assertTrue(JSONObject(restored.json()).getJSONArray("firstTargetMs").isNull(0))
        restored.observe(120000L,35.0)
        val j = JSONObject(restored.json())
        assertEquals(120000L,j.getJSONArray("firstTargetMs").getLong(0))
        assertEquals(14000L,j.getLong("maxGapMs"))
        assertEquals(3580000L,j.getLong("endGapMs"))
        assertNull(RunnerTargetPath9049.unpack(p.pack(),100001L,105000L))
        assertNull(RunnerTargetPath9049.unpack(p.pack(),100000L,105001L))
        assertNull(RunnerTargetPath9049.start(100000L,105000L,Double.NaN,3.0))
    }
    @Test fun precursorWithoutSnapshotCannotBecomeKnownFlowOrWrongAsset() {
        val f = "{\"asOfMs\":90000,\"buyers60\":0,\"buyTx15\":0}"
        val row = ResearchRows8877.precursor9049("asset", "CORE", 100000L, null,f)
        val j = JSONObject(row)
        assertFalse(j.getBoolean("rawPresent"))
        assertTrue(j.has("flow"))
        assertEquals(row,ResearchRows8877.validPrecursor9049(row,"asset","CORE",100000L))
        assertEquals("",ResearchRows8877.validPrecursor9049(row,"asset2","CORE",100000L))
        val future = JSONObject(ResearchRows8877.precursor9049("asset","CORE",100000L,null,"{\"asOfMs\":100001}"))
        assertFalse(future.has("flow"))
        val stale = JSONObject(ResearchRows8877.precursor9049("asset","CORE",100000L,null,"{\"asOfMs\":70000}"))
        assertFalse(stale.has("flow"))
    }
}
