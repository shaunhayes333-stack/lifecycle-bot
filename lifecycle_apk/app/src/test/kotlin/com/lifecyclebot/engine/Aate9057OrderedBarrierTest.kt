package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.RunnerBarrierLearning9051
import com.lifecyclebot.engine.truth.RunnerTargetPath9049
import com.lifecyclebot.engine.market.BuyerFlow8666
import org.junit.Assert.*
import org.junit.Test

/** Pure production models; run in the lightweight harness and Android regression suite. */
class Aate9057OrderedBarrierTest {
    private val c=RunnerBarrierLearning9051
    private val d=100000L
    private val e=d+10000L
    private val hits=longArrayOf(e+1000,e+30000,e+30000,e+30000,0,0)

    @Test fun observedTargetSurvivesLaterGapAndStop() {
        val labels=c.prefixLabels9057(d,e,10000,e+30000,28000,3,e+2000,hits,e+2000,2)
        assertEquals(1,labels[0]);assertEquals(0,labels[1]);assertEquals(1,labels[6]);assertEquals(0,labels[17])
    }

    @Test fun unobservedOrderingIsNotNegativeEvidence() {
        val labels=c.prefixLabels9057(d,e,10000,e+30000,28000,3,0,hits,e+2000,2)
        assertEquals(1,labels[0]);assertEquals(-1,labels[1]);assertEquals(-1,labels[17])
        assertTrue(c.prefixLabels9057(d,e,10000,e+30000,30000,1,0,longArrayOf(e+30000,0,0,0,0,0),e,0).all{it==-1})
        assertTrue(c.prefixLabels9057(d,e,10000,e+30000,28000,3,0,hits,e+2000,3).all{it==-1})
    }

    @Test fun laterGapDoesNotEraseCompletedShortHorizon() {
        val end=d+300000L
        val labels=c.prefixLabels9057(d,e,10000,d+500000,200000,30,0,LongArray(6),end,29)
        assertTrue(labels.take(6).all{it==0});assertTrue(labels.drop(6).all{it==-1})
        val future=c.prefixLabels9057(d,e,10000,end+1000,10000,30,0,longArrayOf(end+1000,0,0,0,0,0),end+1000,30)
        assertEquals(0,future[0]);assertEquals(1,future[6])
    }

    @Test fun checkpointMigrationAndRestartNeverRepairGap() {
        val p=RunnerTargetPath9049.start(d,e,20.0,3.0)!!
        p.observe(e+1000,34.0);p.observe(e+2000,-21.0);p.observe(e+30000,203.0)
        val packed=p.pack()
        val parts=packed.split('|')
        assertEquals("2",parts[0]);assertEquals(e+2000,parts[15].toLong());assertEquals(2,parts[16].toInt())
        val restored=RunnerTargetPath9049.unpack(packed,d,e)!!
        assertEquals(packed,restored.pack())
        restored.observe(e+31000,500.0)
        assertEquals(e+2000,restored.pack().split('|')[15].toLong())
        val old=parts.take(15).toMutableList().also{it[0]="1"}.joinToString("|")
        assertEquals(e,RunnerTargetPath9049.unpack(old,d,e)!!.pack().split('|')[15].toLong())
        val forged=parts.toMutableList().also{it[15]=(e+30000).toString()}.joinToString("|")
        assertNull(RunnerTargetPath9049.unpack(forged,d,e))
    }

    @Test fun buyerEvidenceDependencyIsExercisedByStandaloneHarness() {
        val now=1800000L
        BuyerFlow8666.clearForTest()
        try {
            BuyerFlow8666.note("green9057","alice",2.0,true,now-20000,"a",now-19000)
            BuyerFlow8666.note("green9057","",4.0,true,now-2000,"b",now)
            val view=BuyerFlow8666.readForLearning9056("green9057",now)!!
            val proof=view.evidence9056!!
            assertEquals(0.5,proof.coverage60,0.0)
            assertFalse(proof.identities60);assertEquals(4.0,proof.net5,0.0)
            BuyerFlow8666.note("green9057","bob",4.0,true,now-2000,"b",now+1)
            assertNull(BuyerFlow8666.readForLearning9056("green9057",now))
            assertTrue(BuyerFlow8666.readForLearning9056("green9057",now+1)!!.evidence9056!!.identities60)
            assertFalse(proof.identities60)
        } finally { BuyerFlow8666.clearForTest() }
    }
}
