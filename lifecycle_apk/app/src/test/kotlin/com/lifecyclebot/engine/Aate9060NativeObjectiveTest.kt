package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.NativeZoneContract9059
import com.lifecyclebot.engine.truth.NativeZoneSequence9058
import com.lifecyclebot.engine.truth.NativeZoneFeedback9059
import org.junit.Assert.*
import org.junit.Test

class Aate9060NativeObjectiveTest {
    private val c=NativeZoneContract9059
    private val t=100000L
    private val scope=NativeZoneSequence9058.Scope("CORE","QUIET_ACCUMULATION","SOLANA_CURVE","PUMP.MEASURED.LIVE.PB.LANE","LONG")
    @Test fun setupMembershipAndTiming() {
        val signal=c.signal("PB",t,"QUIET_ACCUMULATION",listOf("QUIET_ACCUMULATION","RANGE_SUPPORT"),"MATCHED",40.0,50.0,60.0,t)!!
        assertEquals(80.0,signal.scorePct,0.0001)
        assertEquals(listOf("QUIET_ACCUMULATION","RANGE_SUPPORT"),signal.names())
        assertEquals(listOf(scope,scope.copy(setup="RANGE_SUPPORT")),c.scopes(scope,signal))
        assertNull(c.signal("PB",t,"QUIET_ACCUMULATION",emptyList(),"MATCHED",40.0,50.0,60.0,t+5001))
        val no=c.signal("PB",t,null,emptyList(),"NO_MATCH",0.0,50.0,0.0,t)!!
        assertTrue(no.names().isEmpty())
        assertEquals("OBS_NO_MATCH",no.primary())
        val x=c.vector(DoubleArray(45){1.0},scope.copy(setup="RANGE_SUPPORT"),signal,null,t)
        assertEquals(52,x.size)
        assertTrue(x[40].isNaN()&&x[41].isNaN()&&x[50].isNaN())
        assertEquals(0.0,x[48],0.0)
        assertTrue(c.cohort("BASE_RETEST",t)!!.scorePct.isNaN())
    }
    @Test fun nativeTargetIsCostAwareAndCensored() {
        val o=c.objective(8.0,2.0,300000L,"RING")!!
        assertEquals(6.0,o.targetNetPct,0.0001)
        assertNull(c.objective(1.0,2.0,300000L,"RING"))
        assertEquals(1,c.outcome(o,t,t+1000,t+3000,t+2000,t+3000))
        assertEquals(0,c.outcome(o,t,t+1000,t+3000,0,t+3000))
        assertEquals(-1,c.outcome(o,t,t+1000,t+3000,0,0))
        assertNull(c.outcome(o,t,t+1000,t+3000,t+3000,t+2000))
        val long=c.grossLevels(100.0,110.0,95.0,"LONG")!!
        val short=c.grossLevels(100.0,90.0,105.0,"SHORT")!!
        assertEquals(long.first,short.first,0.0001)
        assertEquals(long.second,short.second,0.0001)
        assertNull(c.grossLevels(100.0,90.0,95.0,"LONG"))
    }
    @Test fun orderedNativeTargetAndMultipleForecasts() {
        val z=NativeZoneSequence9058()
        val signal=c.signal("PB",t,"QUIET_ACCUMULATION",listOf("RANGE_SUPPORT"),"MATCHED",40.0,50.0,60.0,t)!!
        val objective=c.objective(8.0,2.0,300000L,"LANE")!!
        fun mark(at:Long,px:Double)=NativeZoneSequence9058.Mark("asset","pump.curve",6,at,at,px,"TEST")
        assertTrue(z.reserve("asset","CORE",t,signal.channel()))
        val p=NativeZoneSequence9058.Prediction(-1,t-1,.7,.8)
        assertTrue(z.enrollAll9059(NativeZoneSequence9058.Decision(scope,mark(t,1.0),t,1000L,10.0,2.0,
            DoubleArray(12),false,signalAt9059=t,signal=signal,objective=objective)){_,_,_->listOf(p,p.copy(head=0),p.copy(head=1))})
        z.mark(mark(t+1000,1.0),t+1000)
        z.mark(mark(t+2000,1.09),t+2000)
        z.mark(mark(t+3000,.8),t+3000)
        val out=z.drain().single()
        assertEquals(1,out.nativeLabel)
        assertEquals(t+2000,out.nativeHit)
        assertEquals(listOf(-1,0,1),out.predictions9059.map{it.head})
        assertEquals(signal,out.signal)
        val grade=NativeZoneFeedback9059.score(listOf(NativeZoneFeedback9059.Graded("asset",t,t+3000,1,p)),-1,t+4000)!!
        assertEquals(1,grade.assets)
        assertEquals(.04,grade.neuralBrier,.0001)
    }
}
