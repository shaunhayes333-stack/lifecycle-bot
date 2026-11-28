package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercise the production journal codec, including censored windows and barrier reconstruction. */
class Aate9061NativeJournalIntegrationTest {
    @Test fun checkpointRowsRoundTripWithoutPromotingMissingPathsOrForgedLabels() {
        val tracker=NativeZoneSequence9058()
        val t=100_000L
        fun mark(at:Long,usd:Double)=NativeZoneSequence9058.Mark("asset","pump.curve",6,at,at,usd,"CURVE_FRAME")
        val signal=NativeZoneContract9059.Signal("PB",t,"LAUNCH_CONTINUATION",emptyList(),"MATCHED",40.0,Double.NaN)
        val scope=NativeZoneSequence9058.Scope("EXPRESS",signal.primary(),"SOLANA_CURVE","PUMP.LIVE.PB.LANE","LONG")
        val market=NativeZoneMarket9061.Snapshot(20_000.0,5_000.0,2.0,2.0,2_000.0,1_000_000.0,0.0,60.0,1_000.0,"CURVE_LAUNCH")
        val d=NativeZoneSequence9058.Decision(scope,mark(t,1.0),t,1000,10.0,2.0,DoubleArray(12){Double.NaN},true,
            signalAt9059=t,signal=signal,objective=NativeZoneContract9059.objective(20.0,2.0,300_000L,"LANE"),market9061=market)
        assertTrue(tracker.reserve("asset","EXPRESS",t,signal.channel()));assertTrue(tracker.enroll(d))
        tracker.mark(mark(t+1000,1.0),t+1000)
        tracker.mark(mark(t+2000,1.25),t+2000)
        tracker.mark(mark(t+3000,.8),t+3000)
        val winner=tracker.drain().single()
        assertEquals(1,winner.nativeLabel)
        val encode=NativeZoneLab9058::class.java.getDeclaredMethod("encode",NativeZoneSequence9058.Completed::class.java,Long::class.javaPrimitiveType).apply{isAccessible=true}
        val decode=NativeZoneLab9058::class.java.getDeclaredMethod("decode",String::class.java,Long::class.javaPrimitiveType).apply{isAccessible=true}
        fun row(r:NativeZoneSequence9058.Completed)=encode.invoke(NativeZoneLab9058,r,t+50_000L) as String
        fun read(s:String)=decode.invoke(NativeZoneLab9058,s,t+60_000L)
        val f=File.createTempFile("native-zone-codec",".jsonl")
        try {
            NativeZoneJournal9061.append(f,1){row(winner)}
            assertNotNull(read(f.readLines().single()))
            val forged=JSONObject(f.readLines().single()).put("nativeLabel",0)
            assertNull(read(forged.toString()))
            val alias = JSONObject(f.readLines().single())
            for (field in listOf("decision", "entry", "last"))
                alias.getJSONObject(field).put("venue", "MINT_ROUTE:" + "1".repeat(32))
            assertNull("a route alias is not a witnessed pool, including on journal reload", read(alias.toString()))
            assertTrue(tracker.reserve("asset","EXPRESS",t+20_000L,signal.channel()))
            val later=signal.copy(at=t+20_000L)
            assertTrue(tracker.enroll(d.copy(at=later.at,mark=mark(later.at,1.0),signalAt9059=later.at,signal=later)))
            tracker.expire(t+50_000L)
            val censored=tracker.drain().single()
            assertTrue(censored.labels.all{it==-1});assertEquals(-1,censored.nativeLabel)
            NativeZoneJournal9061.append(f,1){row(censored)}
            assertEquals(2,f.readLines().size);assertTrue(f.readLines().all{read(it)!=null})
            assertTrue(tracker.status().contains("ENTRY_TIMEOUT:1"))
        }finally{f.delete()}
    }
}
