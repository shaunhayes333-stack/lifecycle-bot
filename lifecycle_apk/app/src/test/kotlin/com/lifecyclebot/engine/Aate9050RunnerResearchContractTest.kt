package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate9050RunnerResearchContractTest {
    @Test fun sampledEndpointsNeverBecomeHeldPeaks() {
        val c = RunnerResearchContract9050
        for (prefix in listOf("JUP_8884:","DEX_9015:","CHAIN_8993:","NO_PRICE_JUP_8884:","LATE_READ_8884:","IMPLAUSIBLE_RATIO_8923:","LOSTMARK8983/")) {
            assertTrue(c.sampledEndpoint(prefix+"NATIVE_READY"))
            assertTrue(c.heldPeak(prefix+"NATIVE_READY",500.0).isNaN())
        }
        assertEquals(500.0,c.heldPeak("NATIVE_READY",500.0),0.0)
        assertTrue(c.heldPeak("NATIVE_READY",500.0,c.ENDPOINT_ONLY).isNaN())
        val row=JSONObject(ResearchRows8877.line8877(1000L,3600000L,"7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr","CORE",false,"BASE_START","NONE",10.0,50.0,50000.0,45000.0,3.0,-8.0,-5.0,100.0,100.0,-8.0,"JUP_8884:LOSTMARK8983/NATIVE_READY"))
        assertEquals(100.0,row.getDouble("peakPct"),0.0)
        assertTrue(row.isNull("heldPeakPct"))
        assertEquals(c.ENDPOINT_ONLY,row.getString("pathKind9050"))
        assertEquals(-8.0,ResearchRows8877.rowNet8983(row),0.0)
        assertNull(BasePocket8904.rowOf8904(row))
        LanePocket8934.resetForTest8934()
        try {
            LanePocket8934.fileForTest8934(row)
            assertTrue(LanePocket8934.statusLine8934().contains("endpointOnlyManaged9050=1"))
            assertTrue(LanePocket8934.statusLine8934().contains("exitsGraded8971=0"))
        } finally { LanePocket8934.resetForTest8934() }
    }
    @Test fun maturityAndModelPublicationAreCausal() {
        val c=RunnerResearchContract9050
        val h=14_400_000L
        assertFalse(c.maturedBefore(100L,h,100L+h))
        assertTrue(c.maturedBefore(100L,h,101L+h))
        assertFalse(c.maturedBefore(100L,h,99L))
        assertTrue(c.causalForward(h+200L,100L,h,h+300L))
        assertFalse(c.causalForward(h+400L,100L,h,h+300L))
        assertFalse(c.causalForward(h+200L,201L,h,h+300L))
        assertTrue(c.validCross(1000,2000,3000,1500,1e-7,.4,.2,10_000))
        assertFalse(c.validCross(1000,2000,3000,3500,1e-7,.4,.2,10_000))
        assertFalse(c.validCross(1000,2000,19000,1500,1e-7,.4,.2,20_000))
        assertFalse(c.validCross(1000,2000,14000,1500,1e-7,.4,.2,10_000))
        assertFalse(c.validCross(1000,2000,3000,1500,Double.NaN,.4,.2,10_000))
    }
    @Test fun longHorizonUsesOnlyFrozenMatchingPrecursor() {
        val row=JSONObject().put("mint","asset").put("lane","CORE").put("t",100000L)
            .put("precursor9049",JSONObject().put("v",1).put("assetId","asset").put("lane","CORE").put("atMs",100000L).put("age",3.0)
                .put("flow",JSONObject().put("asOfMs",99000L).put("buyers60",4)))
        assertEquals(3.0,LanePocket8934.rowExtras9002(row).first,0.0)
        assertEquals(4.0,LanePocket8934.rowExtras9002(row).second,0.0)
        row.getJSONObject("precursor9049").getJSONObject("flow").put("asOfMs",70000L)
        assertTrue(LanePocket8934.rowExtras9002(row).second.isNaN())
        row.getJSONObject("precursor9049").put("assetId","other")
        assertTrue(LanePocket8934.rowExtras9002(row).first.isNaN())
    }
}
