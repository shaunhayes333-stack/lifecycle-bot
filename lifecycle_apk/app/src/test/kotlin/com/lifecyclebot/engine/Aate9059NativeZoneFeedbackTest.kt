package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.NativeZoneFeedback9059
import com.lifecyclebot.engine.truth.NativeZoneNet9058
import com.lifecyclebot.engine.truth.NativeZoneSequence9058
import com.lifecyclebot.engine.truth.NativeZoneSignal9059
import com.lifecyclebot.engine.truth.RunnerBarrierLearning9051
import org.junit.Assert.*
import org.junit.Test

class Aate9059NativeZoneFeedbackTest {
    private val scope=NativeZoneSequence9058.Scope("CORE","QUIET_ACCUMULATION","SOLANA_CURVE","PUMP.MEASURED.LIVE","LONG")

    @Test fun multipleHeadsRetainOwnSetupAndExpiry(){
        val c=RunnerBarrierLearning9051
        val rows=(0 until 720).map { i ->
            val t=100000L+i*2*c.HOUR
            val a=if(i%2==0)-2.0 else 2.0;val b=if(i/2%2==0)-2.0 else 2.0;val y=if(a*b<0)1 else 0
            RunnerBarrierLearning9051.Sample("asset"+i,t,t+c.HOUR,DoubleArray(NativeZoneSequence9058.WIDTH){0.0}.also{it[0]=a;it[1]=b},
                IntArray(c.HEADS){y})
        }
        val fit=NativeZoneNet9058.fit(c.split(rows),0)!!
        val other=scope.copy(lane="CYCLIC")
        val book=NativeZoneFeedback9059(3,1000)
        val x=DoubleArray(NativeZoneSequence9058.WIDTH){0.0}.also{it[0]=-2.0;it[1]=2.0}
        assertTrue(book.publish(scope,0,10000L,fit));assertTrue(book.publish(scope,1,10001L,fit));assertTrue(book.publish(other,0,10002L,fit))
        assertEquals(listOf(0,1),book.predict(scope,x,10003L).map{it.head});assertEquals(listOf(0),book.predict(other,x,10003L).map{it.head})
        assertTrue(book.predict(scope.copy(setup="RETEST"),x,10003L).isEmpty())
        assertTrue(book.predict(scope.copy(source="PUMP.MEASURED.PAPER"),x,10003L).isEmpty())
        assertTrue(book.predict(scope,x,9999L).isEmpty())
        assertFalse(book.publish(scope,2,10000L,fit));assertTrue(book.publish(scope,1,10003L,fit));assertEquals(3,book.count())
        assertEquals(listOf(1),book.predict(scope,x,11001L).map{it.head});assertTrue(book.predict(scope,x,11004L).isEmpty())
    }

    @Test fun frozenProspectivePredictionsCannotMultiplySupport(){
        val p=NativeZoneSequence9058.Prediction(0,100L,.5,.9)
        val g=NativeZoneFeedback9059.Graded("asset",101,500,1,p)
        val h=NativeZoneFeedback9059.Graded("asset2",101,500,0,p)
        val score=NativeZoneFeedback9059.score(List(30){g}+h,0,501)!!
        assertEquals(2,score.assets);assertEquals(2,score.windows)
        assertEquals(.41,score.neuralBrier,1e-9);assertEquals(.25,score.linearBrier,1e-9)
        assertNull(NativeZoneFeedback9059.score(listOf(g.copy(label=-1),g.copy(decision=100),g.copy(available=999)),0,501))
        assertNull(NativeZoneFeedback9059.score(listOf(g,g.copy(label=0)),0,501))
    }

    @Test fun nativeSignalAndAllForecastHeadsSurviveOutcome(){
        val z=NativeZoneSequence9058()
        val d=100000L
        fun mark(t:Long,usd:Double)=NativeZoneSequence9058.Mark("asset","pump.curve",6,t,t,usd,"TEST")
        val p=NativeZoneSequence9058.Prediction(0,100L,.5,.9)
        assertTrue(z.reserve("asset","CORE",d))
        assertTrue(z.enrollAll9059(NativeZoneSequence9058.Decision(scope,mark(d,1.0),d,1000L,10.0,2.0,DoubleArray(12),false,signalAt9059=d-10)){
            _,_,_->listOf(p,p.copy(head=1),p)
        })
        z.mark(mark(d+1000,1.0),d+1000);z.mark(mark(d+2000,.8),d+2000)
        val out=z.drain().single()
        assertEquals(listOf(0,1),out.predictions9059.map{it.head});assertEquals(p,out.prediction);assertEquals(d-10,out.signalAt9059!!)
        val native=NativeZoneSignal9059("CORE","QUIET_ACCUMULATION",d,"LIVE")
        assertTrue(native.validFor("CORE",d+5000));assertFalse(native.validFor("CORE",d+5001))
        assertFalse(native.validFor("CYCLIC",d));assertFalse(native.validFor("CORE",d-1))
        assertEquals("QUIET_ACCUMULATION",native.setup());assertEquals("UNCLASSIFIED",native.copy(firedSetup=null).setup())
    }
}
