package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.*
import com.lifecyclebot.engine.truth.LabelMark8386
import org.junit.Assert.*
import org.junit.Test

class Aate8395AtomicOutcomeTest {
    private val id="mint|MOONSHOT|1000"
    private fun row()=CortexGradeRecord8144('D',id,"mint|MOONSHOT",1000,"MOONSHOT","BASE","TREND","EARLY","LOW","SMALL",2,30.0,true,true,false)
    private fun proof(net:Double,gross:Double,at:Long=301000,independent:Boolean=true)=CortexOutcomeProof8395(id,at-1000,at,net,gross,
        LabelMark8386("mint","curve",1.0,1000,1000,"CHAIN",decimals=6,independentBasis=independent),
        LabelMark8386("mint","curve",1+gross/100,at,at,"CHAIN",decimals=6,independentBasis=independent))
    private fun apply(b:CortexGradeBook8144,p:CortexOutcomeProof8395)=b.labelOutcome8395(p.decisionId,p.netPct,p.grossPct,p.netPct,true,p.horizonMs,p.observedAtMs,p.observedAtMs,p)
    @Test fun firstIndependentlyWitnessedExtremeIsAcceptedWithoutPreSettingAFlag() {
        val b=CortexGradeBook8144();val r=row();b.addDecision(r)
        assertFalse(r.witnessed8324)
        val p=proof(199995.0,200000.0)
        assertEquals(CortexOutcomeDisposition8395.ACCEPTED_FIRST,apply(b,p))
        assertEquals(199995.0,r.executablePct,0.0);assertTrue(r.witnessed8324)
        val restored=CortexGradeBook8144();restored.decode(b.encode())
        assertEquals(199995.0,restored.decision(id)!!.executablePct,0.0)
        assertEquals(r.firstOutcomeProof8395,restored.decision(id)!!.firstOutcomeProof8395)
    }
    @Test fun unwitnessedExtremeIsObservedOnlyAndCannotBecomeContributionReceipt() {
        val b=CortexGradeBook8144();val r=row();b.addDecision(r)
        assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY,apply(b,proof(199995.0,200000.0,independent=false)))
        assertTrue(r.executablePct.isNaN());assertEquals("",r.modelContribution8395)
        assertTrue(r.eligibleToGrade8395);assertEquals(199995.0,r.observedNetPct,0.0)
    }
    @Test fun priorWitnessCannotAuthorizeAnotherValueOrAnotherEpisode() {
        val b=CortexGradeBook8144();val r=row();b.addDecision(r);val p=proof(199995.0,200000.0)
        assertTrue(apply(b,p).accepted)
        assertEquals(CortexOutcomeDisposition8395.IDENTITY_MISMATCH,
            b.labelOutcome8395(id,3e10,3e10+5,3e10,true,600000,601000,601000,p))
        assertEquals(199995.0,r.executablePct,0.0);assertTrue(r.executableRevisedPct.isNaN())
        assertEquals(CortexOutcomeDisposition8395.MISSING,apply(b,p.copy(decisionId="mint|MOONSHOT|2000")))
    }
    @Test fun rejectedRevisionPreservesFirstValueProofAndAvailabilityThroughRestart() {
        val b=CortexGradeBook8144();val r=row();b.addDecision(r);apply(b,proof(199995.0,200000.0))
        val first=r.firstOutcomeProof8395
        assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY,apply(b,proof(3e10,3e10+5,601000,false)))
        assertEquals(first,r.firstOutcomeProof8395);assertEquals(301000L,r.firstAvailableAtMs8372)
        assertEquals(0,r.revisions8272);assertTrue(r.executableRevisedPct.isNaN())
        val restored=CortexGradeBook8144();restored.decode(b.encode())
        assertEquals(199995.0,restored.decision(id)!!.executablePct,0.0)
    }
    @Test fun acceptedRevisionHasItsOwnProofAndDoesNotStripEarlierRunnerWitness() {
        val b=CortexGradeBook8144();val r=row();b.addDecision(r);apply(b,proof(199995.0,200000.0))
        assertEquals(CortexOutcomeDisposition8395.ACCEPTED_REVISION,apply(b,proof(5.0,10.0,601000,false)))
        assertFalse(r.witnessed8324)
        assertEquals(CortexOutcomeDisposition8395.DUPLICATE_ACCEPTED,apply(b,proof(5.0,10.0,601000,false)))
        assertEquals(1,r.revisions8272)
        val restored=CortexGradeBook8144();restored.decode(b.encode())
        assertEquals(199995.0,restored.decision(id)!!.executablePct,0.0)
        assertEquals(5.0,restored.decision(id)!!.executableRevisedPct,0.0)
    }
    @Test fun eligibleLostPlaceholderIsNotProofOfCompletedLearning() {
        val b=CortexGradeBook8144();val r=row();b.addDecision(r);assertTrue(b.markLost(id))
        assertTrue(r.eligibleToGrade8395);assertEquals("",r.modelContribution8395)
        assertEquals(CortexOutcomeDisposition8395.ACCEPTED_FIRST,apply(b,proof(10.0,15.0)))
        assertFalse(r.lost);assertEquals(10.0,r.executablePct,0.0)
        assertEquals("",r.modelContribution8395) // only actual model fanout may set its receipt
    }
}
