package com.lifecyclebot.engine
import com.lifecyclebot.engine.cortex.*
import org.junit.Assert.*
import org.junit.Test
class Aate8392CortexCoverageTest {
    private val strong=CortexScoreboard7885.Bucket.STRONG.ordinal
    private val refuse=CortexScoreboard7885.Bucket.REFUSE.ordinal
    private fun rows()=List(250) { i ->
        val score=((i*37)%100-50)/5.0;val at=1_000_000L+i*60_000L
        CortexAuthority8366.Row8366(at,score,score*4.0,
            CortexScoreboard7885.bucketOf(score,0.0,false).ordinal,at+300_000,at+300_000)
    }
    @Test fun missingStrongOutcomesCannotDisappearAndGrantAuthority() {
        val observed=rows();val now=observed.last().outcomeAvailableAtMs+1
        val missing=List(300) { i -> CortexAuthority8366.Row8366(1_000_001L+i,10.0,Double.NaN,strong,0L,1_300_001L+i) }
        assertTrue(CortexAuthority8366.test8372(observed,1.5,now).granted)
        val base=CortexAuthority8366.test8372(observed,1.5,now)
        val r=CortexAuthority8366.test8372(observed+missing,1.5,now)
        assertEquals(300,r.missing8392);assertEquals(550,r.matured8392)
        // V5.0.8399 — the missing outcomes stay in the denominator and SCALE the authority down, never vanish, never imputed.
        // superseded by V5.0.8418 — under the 70 % bar the scale is 0 (8399 kept coverage/0.7 down to 30 %): no grant.
        val minCov=minOf(r.strongCoverage8392,r.restCoverage8392)
        assertTrue(minCov<CortexWalkForward8144.MIN_RESOLVED_SHARE_8144)
        assertEquals(base.fraction*CortexAuthority8366.coverageScale8399(minCov),r.fraction,1e-12)
        assertTrue(r.fraction<base.fraction)
        // Under 30% known there is nothing to grant.
        val mostlyMissing=List(1_200) { i -> CortexAuthority8366.Row8366(1_000_001L+i,10.0,Double.NaN,strong,0L,1_300_001L+i) }
        val dark=CortexAuthority8366.test8372(observed+mostlyMissing,1.5,now)
        assertEquals(CortexAuthority8366.Verdict8366.INCOMPLETE_OUTCOMES,dark.verdict)
        assertFalse(dark.granted);assertEquals(0.0,dark.fraction,0.0)
    }
    @Test fun stillPendingIsNotAFabricatedLossAndDoesNotDiluteMatureCoverage() {
        val observed=rows();val now=observed.last().outcomeAvailableAtMs+1
        val pending=List(100){i->CortexAuthority8366.Row8366(now-i-1,10.0,Double.NaN,strong,0,now+300_000)}
        val base=CortexAuthority8366.test8372(observed,1.5,now)
        val r=CortexAuthority8366.test8372(observed+pending,1.5,now)
        assertEquals(base.fraction,r.fraction,0.0);assertEquals(base.oosN,r.oosN)
        assertEquals(0,r.missing8392);assertEquals(100,r.pending8392)
    }
    private fun record(id:String,version:Int,admit:Boolean,outcome:Double)=CortexGradeRecord8144(
        'D',id,id,1_000_000,"MOONSHOT","IGNITION","RANGE","NOW","C0","MC_LT10K",
        if(admit)strong else refuse,if(admit)10.0 else -10.0,admit,true,false).also {
            it.scoreVersion8372=version;it.executablePct=outcome;it.observedNetPct=outcome
            it.firstGross8324=outcome;it.firstAvailableAtMs8372=1_300_000;it.tailEv8324=if(admit)-100.0 else 100.0
        }
    @Test fun currentSelectionColumnsCannotMixHistoricalScorerLabels() {
        val b=CortexGradeBook8144()
        b.addDecision(record("oldYes",0,true,1000.0));b.addDecision(record("oldNo",0,false,-1000.0))
        b.addDecision(record("newYes",1,true,-34.0));b.addDecision(record("newNo",1,false,38.7))
        val selection=LearningEntryTruth8153.decisionRow8269(b,"MOONSHOT",1)
        val prediction=LearningEntryTruth8153.verdictRow8153(b,"MOONSHOT",1)
        assertEquals(1,selection.takeN);assertEquals(1,selection.refuseN)
        assertEquals(prediction.strongMean,selection.takeNetMean,0.0)
        assertEquals(prediction.refuseMean,selection.refuseNetMean,0.0)
        assertEquals(2,LearningEntryTruth8153.decisionRow8269(b,"MOONSHOT").takeN)
    }
    @Test fun currentRankUsesTheExecutedForecastNotTheUnusedTailOpinion() {
        val b=CortexGradeBook8144();b.addDecision(record("v0",0,true,1000.0));b.addDecision(record("v1",1,true,20.0))
        val current=b.rankRowsByLane8325(1)["MOONSHOT"]!!
        assertEquals(1,current.size);assertEquals(10.0,current.single().predicted,0.0)
        assertTrue(current.single().tailEv.isNaN())
        assertEquals(2,b.rankRowsByLane8325()["MOONSHOT"]!!.size)
    }
    @Test fun lostRecordSurvivesPersistenceAndReachesTheAuthorityDenominator() {
        val b=CortexGradeBook8144();val r=record("lost",1,true,Double.NaN);b.addDecision(r);b.markLost("lost")
        val restored=CortexGradeBook8144().apply { decode(b.encode()) }
        val rows=restored.scoredRows8366("MOONSHOT",true,1)
        assertEquals(1,rows.size);assertTrue(rows.single().netPct.isNaN());assertTrue(rows.single().dueAtMs8392>0)
    }
    @Test fun incompleteRefuseEvidenceCannotExerciseNegativeAuthorityEither() {
        val healthy=rows();val now=healthy.last().outcomeAvailableAtMs+1
        assertTrue(CortexAuthority8366.cohortCovered8392(healthy,refuse,now))
        val lost=List(300){i->CortexAuthority8366.Row8366(1_000_001L+i,-10.0,Double.NaN,refuse,0,1_300_001L+i)}
        assertFalse(CortexAuthority8366.cohortCovered8392(healthy+lost,refuse,now))
    }
    @Test fun laneWideProofIsReadFromWholeLaneRecordsNotNiches() {
        val lane="ZZ_LANE_8392"
        assertFalse(com.lifecyclebot.engine.truth.EvidenceHold8336.laneWideProven8392(lane, 1L))
        val at=com.lifecyclebot.engine.truth.EvidenceHold8336.startMs8336+1
        repeat(40){ com.lifecyclebot.engine.truth.EvidenceShadow8344.noteCloseForTests8345(lane, if(it%4==0) -8.0 else 15.0, at+it) }
        // V5.0.8420 — restored: a whole-lane shadow book (real exits and costs) proves the lane lane-wide again (lifts the niche
        // veto / timing wait only; no size input). The live-record path (laneWideLive8418) is kept beside it.
        assertTrue(com.lifecyclebot.engine.truth.EvidenceHold8336.laneWideProven8392(lane, at+100))
        assertTrue(com.lifecyclebot.engine.truth.EvidenceHold8336.laneWideLive8418(doubleArrayOf(30.0, 4.0)))
        assertFalse(com.lifecyclebot.engine.truth.EvidenceHold8336.laneWideLive8418(doubleArrayOf(29.0, 40.0)))
        assertFalse(com.lifecyclebot.engine.truth.EvidenceHold8336.laneWideLive8418(doubleArrayOf(80.0, 1.0)))
    }
}
