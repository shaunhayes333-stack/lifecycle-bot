package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LanePocket8934
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Android/Gradle integration: exercise the actual lane miner and its causal publication adapter. */
class Aate9061PocketIntegrationTest {
    @Before fun before()=LanePocket8934.resetForTest8934()
    @After fun after()=LanePocket8934.resetForTest8934()

    @Test fun nativeTimingReadsTheLaneMinersOwnPublishedPopulationAndHorizon() {
        val before=System.currentTimeMillis()-1
        for(i in 0 until 40) {
            val row=JSONObject().put("t",before-100_000L+i*1000L).put("horizonMs",14_400_000L)
                .put("mint","asset$i").put("lane","QUALITY").put("stage","BASE_START").put("setup","BASE")
                .put("v3",30.0).put("pred",12.0).put("liq",25_000.0).put("mcap",200_000.0)
                .put("net",30.0).put("age",180.0)
            LanePocket8934.fileForTest8934(row)
        }
        LanePocket8934.mineForTest8934()
        val now=System.currentTimeMillis()
        fun read(lane:String,cap:Double,at:Long)=LanePocket8934.context9061("newasset",lane,"BASE","BASE_START",
            30.0,12.0,25_000.0,cap,180.0,Double.NaN,at)
        val s=read("QUALITY",200_000.0,now)
        assertNotNull(s)
        assertTrue(s!!.matched)
        assertEquals(14_400_000L,s.horizon)
        assertFalse(s.managed)
        assertEquals(40,s.n)
        assertNull("Publication cannot influence an earlier decision",read("QUALITY",200_000.0,before))
        assertNull("QUALITY proof cannot be borrowed by another lane",read("CORE",200_000.0,now))
        assertNull("Same lane outside its own cap band is not this population",read("QUALITY",2_000.0,now))
    }
    @Test fun sharedMatcherWithholdsAgeBlindProofAtBothNativeAndExtendedHorizons() {
        for (horizon in listOf(3_600_000L, 14_400_000L)) {
            LanePocket8934.resetForTest8934()
            val start=System.currentTimeMillis()-100_000L
            repeat(40) { i ->
                LanePocket8934.fileForTest8934(JSONObject().put("t",start+i*1000L)
                    .put("horizonMs",horizon).put("mint","ageProof$i").put("lane","QUALITY")
                    .put("stage","BASE_START").put("setup","BASE").put("v3",30.0)
                    .put("pred",12.0).put("liq",25_000.0).put("mcap",200_000.0)
                    .put("net",30.0).put("age",180.0))
            }
            LanePocket8934.mineForTest8934()
            val cells=if(horizon==3_600_000L) LanePocket8934.provenForTest8934("QUALITY")
                else LanePocket8934.provenExtForTest8991("QUALITY",horizon)
            val blindCell=cells.first { c -> c.conds.none { it.startsWith("age=") } }
            val publication=LanePocket8934::class.java.getDeclaredField("publication9061")
                .apply { isAccessible=true }.get(LanePocket8934)
            val matcher=LanePocket8934::class.java.declaredMethods.single { it.name=="match9061" }
                .apply { isAccessible=true }
            fun match(features:Set<String>, barred:Boolean)=matcher.invoke(LanePocket8934,"QUALITY",
                features,barred,System.currentTimeMillis(),publication) as? Pair<*,*>
            assertEquals(horizon,match(blindCell.conds.toSet(),false)?.second)
            assertNull("Losing age band cannot borrow blind proof at $horizon",match(blindCell.conds.toSet(),true))
            val namedCell=cells.first { c -> c.conds.any { it.startsWith("age=") } }
            assertEquals("Proof earned in the named age band remains available",horizon,
                match(namedCell.conds.toSet(),true)?.second)
        }
    }

}
