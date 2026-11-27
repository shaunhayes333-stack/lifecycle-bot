package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.BuyerFlow8666
import com.lifecyclebot.engine.truth.NativeZoneSequence9058
import com.lifecyclebot.engine.truth.NativeZoneNet9058
import com.lifecyclebot.engine.truth.RunnerBarrierLearning9051
import org.junit.Assert.*
import org.junit.Test

class Aate9058NativeZoneTest {
    @Test fun decisionWindowsPreserveEarlierFormationAndOwnSetup() {
        val z=NativeZoneSequence9058(maxActive=3,perAsset=3,perLane=3,maxHistory=8,maxCompleted=8)
        val scope=NativeZoneSequence9058.Scope("CORE","UNCLASSIFIED","CURVE","PUMP","LONG")
        val later=scope.copy(setup="IGNITION")
        val d=100000L
        fun m(t:Long,p:Double=1.0,venue:String="pump.curve")=NativeZoneSequence9058.Mark("asset",venue,6,t,t,p,"TEST")
        fun obs(t:Long,s:NativeZoneSequence9058.Scope,p:Double=1.0)=NativeZoneSequence9058.Decision(s,m(t,p),t,1000L,10.0,2.0,DoubleArray(12){it+t/1000.0},false)
        assertTrue(z.reserve("asset","CORE",d));assertTrue(z.enroll(obs(d,scope)))
        assertTrue(z.hasActive9058("asset"));assertFalse(z.enroll(obs(d,scope)))
        assertFalse(z.reserve("asset","CORE",d+1000))
        z.mark(m(d+1000),d+1000);z.mark(m(d+2000,1.13),d+2000)
        assertTrue(z.reserve("asset","CORE",d+15000));assertTrue(z.enroll(obs(d+15000,later,1.2)))
        z.mark(m(d+16000,1.2),d+16000)
        z.mark(m(d+17000,0.9,"wrong"),d+17000);assertTrue(z.drain().isEmpty())
        z.mark(m(d+17000,1.0),d+17000)
        val out=z.drain().single()
        assertEquals(later,out.scope);assertTrue(out.labels.all{it==0})
        assertEquals(100.0,out.features[12],0.0);assertEquals(15.0,out.features[36],0.0);assertEquals(0.0,out.features[40],0.0)
        z.expire(d+40000);val first=z.drain().single()
        assertEquals(scope,first.scope);assertEquals(1,first.labels[0]);assertEquals(1,first.labels[1])
        assertEquals(1,first.labels[6]);assertEquals(-1,first.labels[17])
        assertEquals(d+1000,first.entryAt);assertEquals(d,first.decision.event)
        assertEquals(d+2000,first.firstTargets[0]);assertEquals(14000L,first.maxGap);assertEquals(3,first.observations)
        assertFalse(z.hasActive9058("asset"));assertEquals(scope,NativeZoneSequence9058.Scope.parse(scope.key()))
    }

    @Test fun nonlinearChallengerDoesNotFitTheHoldout() {
        val c=RunnerBarrierLearning9051
        val rows=(0 until 720).map{i->val t=100000L+i*2*c.HOUR
            val a=if(i%2==0)-2.0 else 2.0;val b=if(i/2%2==0)-2.0 else 2.0;val y=if(a*b<0)1 else 0
            RunnerBarrierLearning9051.Sample("asset"+i,t,t+c.HOUR,doubleArrayOf(a,b),IntArray(c.HEADS){y})}
        val split=c.split(rows);val fit=NativeZoneNet9058.fit(split,0)!!
        assertTrue(fit.metrics.averagePrecision>.9);assertTrue(fit.metrics.brier<fit.baseline.brier)
        val inverted=split.copy(test=split.test.map{it.copy(labels=IntArray(c.HEADS){h->1-it.labels[h]})})
        val other=NativeZoneNet9058.fit(inverted,0)!!
        assertEquals(fit.neural.predict(doubleArrayOf(-2.0,2.0)),other.neural.predict(doubleArrayOf(-2.0,2.0)),0.0)
        assertTrue(other.metrics.brier>fit.metrics.brier)
        val censored=split.train.first().copy(labels=IntArray(c.HEADS){-1})
        assertNull(c.fit(split.copy(test=split.test+censored),0))
        assertNull(NativeZoneNet9058.fit(split.copy(test=split.test+censored),0))
    }

    @Test fun delayedPublicationAndUntimedSwapsCannotFabricateDecisionEvidence() {
        val f=BuyerFlow8666;val t=1800000L
        f.clearForTest()
        try {
            f.note("late","alice",2.0,true,t-1000,"sig",t,availableAtMs9058=t+2000)
            assertNull(f.readForLearning9056("late",t))
            val timed=f.readForLearning9056("late",t+2000)!!
            assertEquals(t+2000,timed.evidence9056!!.receiptMs)
            assertTrue(timed.evidence9056!!.publicationClock9058)
            // No-op duplicate is not a new evidence publication.
            f.note("late","alice",2.0,true,t-1000,"sig",t+3000,availableAtMs9058=t+3000)
            assertNotNull(f.readForLearning9056("late",t+2000))
            f.note("late","bob",5.0,true,t+3000,"undated",t+3000,sourceTimeKnown9058=false)
            val mixed=f.readForLearning9056("late",t+3000)!!
            assertEquals(2.0,mixed.netSol60s,0.0)
            assertEquals(1,mixed.evidence9056!!.undated60);assertFalse(mixed.evidence9056!!.identities60)
            f.note("unknown","alice",3.0,true,t,"x",t,sourceTimeKnown9058=false)
            assertNotNull(f.read("unknown",t));assertNull(f.readForLearning9056("unknown",t))
        } finally { f.clearForTest() }
    }
}
