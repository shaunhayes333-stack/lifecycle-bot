package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate9064NativeWarmupIntegrationTest {
    @Test fun productionTrainerAttemptsSupportedScopeBeforeEntireJournalIsIndexed() {
        val tracker=NativeZoneSequence9058();val t=100_000L
        fun mark(at:Long,px:Double)=NativeZoneSequence9058.Mark("same-asset","pump.curve",6,at,at,px,"CURVE_FRAME")
        val signal=NativeZoneContract9059.Signal("PB",t,"LAUNCH_CONTINUATION",emptyList(),"MATCHED",40.0,Double.NaN)
        val scope=NativeZoneSequence9058.Scope("EXPRESS",signal.primary(),"SOLANA_CURVE","PUMP.LIVE.PB.LANE","LONG")
        val market=NativeZoneMarket9061.Snapshot(20_000.0,5_000.0,2.0,2.0,2_000.0,1_000_000.0,0.0,60.0,1_000.0,"CURVE_LAUNCH")
        val d=NativeZoneSequence9058.Decision(scope,mark(t,1.0),t,1000,10.0,2.0,DoubleArray(12){Double.NaN},true,
            signalAt9059=t,signal=signal,objective=NativeZoneContract9059.objective(20.0,2.0,300_000L,"LANE"),market9061=market)
        assertTrue(tracker.reserve("same-asset","EXPRESS",t,signal.channel()));assertTrue(tracker.enroll(d))
        tracker.mark(mark(t+1000,1.0),t+1000);tracker.mark(mark(t+2000,.8),t+2000)
        val row=NativeZoneLab9058::class.java.getDeclaredMethod("row",NativeZoneSequence9058.Completed::class.java,java.lang.Long.TYPE)
            .apply{isAccessible=true}.invoke(NativeZoneLab9058,tracker.drain().single(),t+3000) as String
        val field=NativeZoneLab9058::class.java.getDeclaredField("index9063").apply{isAccessible=true}
        val index=field.get(NativeZoneLab9058) as NativeZoneIndex9063<*>
        val status=NativeZoneLab9058::class.java.getDeclaredField("status").apply{isAccessible=true}
        val f=File.createTempFile("native-batch9064",".jsonl")
        try {
            index.reset();f.bufferedWriter().use{w->repeat(1500){w.append(row).append('\n')}}
            assertTrue(NativeZoneLab9058.batchFile9064(f,t+10_000))
            assertTrue(index.size in 300..1024)
            // Repeated rows may qualify the cheap index count, but cannot
            // manufacture independent assets or pass the unchanged fitting bar.
            assertTrue(status.get(NativeZoneLab9058).toString(),status.get(NativeZoneLab9058).toString().startsWith("insufficient independent support"))
        } finally {index.reset();f.delete()}
    }
}
