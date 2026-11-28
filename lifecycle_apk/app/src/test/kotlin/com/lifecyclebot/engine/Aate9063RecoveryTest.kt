package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.perps.crypto.CryptoExecFailureTracker
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate9063RecoveryTest {
    @Test fun journalIndexesOnceWithBoundedRowsAndKeepsUtf8OffsetsAcrossAppends() {
        val file=File.createTempFile("zone-index-9063",".jsonl")
        try {
            file.writeText("first\nSmåkager\nlast")
            val index=NativeZoneIndex9063<String>(capacity=2)
            assertFalse(index.advance(file,limit=1,decode={it}))
            assertEquals(1L,index.decoded)
            assertFalse(index.advance(file,decode={it})) // partial final row is not committed
            assertEquals(2L,index.decoded)
            file.appendText("\n")
            assertTrue(index.advance(file,decode={it}))
            assertEquals(listOf("Småkager","last"),index.entries().map{it.value})
            assertEquals(listOf("Småkager","last"),index.read(file,index.entries(),{it}))
            repeat(20){assertTrue(index.advance(file,decode={it}))}
            assertEquals("unchanged journal must not be reparsed",3L,index.decoded)
            file.writeText("new\n") // truncation resets offsets
            assertTrue(index.advance(file,decode={it}))
            assertEquals(listOf("new"),index.entries().map{it.value})
            file.writeText("replacement\n")
            index.reset() // owner must reset after an atomic compaction/replacement
            assertTrue(index.advance(file,decode={it}))
            assertEquals(listOf("replacement"),index.read(file,index.entries(),{it}))
        } finally {file.delete()}
    }

    @Test fun journalSkipsOversizeAndInvalidRowsWithoutHidingLaterEvidence() {
        val file=File.createTempFile("zone-limit-9063",".jsonl")
        try {
            file.writeText("x".repeat(10000)+"\nbad\ngood\n")
            val index=NativeZoneIndex9063<String>(capacity=2,maxLine=16)
            assertFalse(index.advance(file,limit=1,decode={it.takeUnless{it=="bad"}}))
            assertTrue(index.advance(file,decode={it.takeUnless{it=="bad"}}))
            assertEquals(listOf("good"),index.entries().map{it.value})
            assertEquals(2L,index.decoded)
        }finally{file.delete()}
    }

    @Test fun scopePagesCountExactlyAndEventuallyReachLatePopularSetups() {
        val keys=(0..2050).map{"scope%04d".format(it)}+List(350){"scope9999"}
        val pages=NativeZoneScopePage9063(2048)
        val first=pages.counts(keys.asReversed().asSequence())
        assertEquals(2048,first.size)
        assertEquals(1,first["scope0000"])
        assertFalse(first.containsKey("scope9999"))
        val second=pages.counts(keys.asSequence())
        assertEquals(350,second["scope9999"])
        assertEquals(4,second.size)
        assertEquals(first,pages.counts(keys.asSequence()))
    }

    @Test fun eachWitnessedVenueKeepsItsOwnContinuousWindow() {
        val t=100_000L;val mint="mint9063";val pool="2".repeat(32)
        fun mark(at:Long,venue:String,px:Double)=LabelMark8386(mint,venue,px,at,at,"CURVE_FRAME",decimals=6,independentBasis=true)
        fun native(m:LabelMark8386)=NativeZoneSequence9058.Mark(m.mint,m.venue,m.decimals,m.observedAtMs,m.readAtMs,m.usd,m.source)
        val tracker=NativeZoneSequence9058()
        val scope=NativeZoneSequence9058.Scope("MOONSHOT","BASE","SOLANA_POOL","TEST.LIVE","LONG")
        val d=NativeZoneSequence9058.Decision(scope,native(mark(t,pool,1.0)),t,1000,10.0,1.0,DoubleArray(12),true)
        assertTrue(tracker.reserve(mint,scope.lane,t));assertTrue(tracker.enroll(d))
        fun feed(at:Long,px:Double){
            val own=mark(at,pool,px);val newer=mark(at+1,"pump.curve",9.0)
            val accepted=NativePriceEvidence9062.accepted9063(mint,listOf(newer,own,own,
                own.copy(independentBasis=false),own.copy(mint="wrong")),at+1,6)
            assertEquals(listOf(own,newer),accepted)
            accepted.forEach{tracker.mark(native(it),at+1)}
        }
        feed(t+1000,1.0);feed(t+2000,1.25);feed(t+3000,.8)
        val row=tracker.drain().single()
        assertEquals(pool,row.entry!!.venue)
        assertEquals(1.0,row.entryUsd,0.0)
        assertEquals(1,row.labels[0]);assertEquals("STOP_MARK",row.reason)
        assertNull(NativePriceEvidence9062.select(mint,listOf(mark(t,pool,1.0)),t+5001,6))
    }

    @Test fun cryptoFailureCooldownIsExactMintAndCaseSensitive() {
        val mint="Ab"+"1".repeat(30);val other="ab"+"1".repeat(30)
        CryptoExecFailureTracker.recordMintSuccess9063(mint)
        CryptoExecFailureTracker.recordMintSuccess9063(other)
        try {
            repeat(3){CryptoExecFailureTracker.recordMintFailure9063(mint)}
            assertTrue(CryptoExecFailureTracker.mintCooledDown9063(mint))
            assertFalse(CryptoExecFailureTracker.mintCooledDown9063(other))
            CryptoExecFailureTracker.recordMintSuccess9063(other)
            assertTrue(CryptoExecFailureTracker.mintCooledDown9063(mint))
            CryptoExecFailureTracker.recordMintSuccess9063(mint)
            assertFalse(CryptoExecFailureTracker.mintCooledDown9063(mint))
        }finally{
            CryptoExecFailureTracker.recordMintSuccess9063(mint)
            CryptoExecFailureTracker.recordMintSuccess9063(other)
        }
    }

    @Test fun shadowByteAccountingRemainsExactAfterReplacementFlushAndMetadataEviction() {
        val q=ShadowWrites8386(capacity=2,byteCapacity=10000)
        assertTrue(q.stage("a",1,"xx"))
        assertEquals(70L+66L,q.bytes())
        assertTrue(q.stage("a",2,"z"));assertEquals(68L+66L,q.bytes())
        assertTrue(q.flush{true});assertEquals(66L,q.bytes())
        for(k in listOf("bb","ccc","dddd","eeeee")){
            assertTrue(q.stage(k,1,"x"));assertTrue(q.flush{true})
        }
        assertEquals("only the latest two metadata keys survive trim",72L+74L,q.bytes())
        assertFalse(q.stage("eeeee",0,"older"));assertEquals(146L,q.bytes())
    }
}
