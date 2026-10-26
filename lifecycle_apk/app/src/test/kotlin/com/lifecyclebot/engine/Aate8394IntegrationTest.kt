package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.engine.cortex.Cortex7885
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate8394IntegrationTest {
    @Test fun supervisorBackoffDoesNotExpireReceiverBeforeItsFirstOpportunity() {
        val start = 1000000L
        val opened = HandoffReceiverTasks8153.open8153(null,"mint","MOONSHOT","QUALITY","fit",1,start+5000,start+5000)!!
        val held = HandoffReceiverTasks8153.suspendForScheduler8394(opened,start+90000,start+5000)
        assertTrue(HandoffReceiverTasks8153.expire8153(held,start+60000).pending)
        assertFalse(HandoffReceiverTasks8153.due8153(held,"MOONSHOT",start+60000,1))
        assertTrue(HandoffReceiverTasks8153.due8153(held,"MOONSHOT",start+90000,1))
        val scheduled=HandoffReceiverTasks8153.scheduled8153(held,"MOONSHOT",start+90000)
        assertEquals(1,scheduled.schedules)
    }
    @Test fun repeatedEvidenceCannotExtendSchedulingBeyondTheAbsoluteBudget() {
        val start=1000000L
        var t=HandoffReceiverTasks8153.open8153(null,"mint","MOONSHOT","QUALITY","fit",1,start,start)!!
        t=HandoffReceiverTasks8153.suspendForScheduler8394(t,start+90000,start)
        assertEquals(t,HandoffReceiverTasks8153.suspendForScheduler8394(t,start+90000,start+10000))
        t=HandoffReceiverTasks8153.suspendForScheduler8394(t,start+300000,start+100000)
        assertEquals(start+HandoffReceiverTasks8153.TASK_MAX_AGE_MS_8153,t.validUntilMs)
        val terminal=HandoffReceiverTasks8153.expire8153(t,t.validUntilMs+1)
        assertEquals(HandoffReceiverTasks8153.Outcome8153.DEFERRED_DATA,terminal.outcome)
        assertTrue(terminal.verdict.startsWith("SCHEDULING_REVALIDATION_REQUIRED_8394"))
    }
    @Test fun secondaryPoolDoesNotBecomeVerifiedBirthAcrossReaders() {
        val now=System.currentTimeMillis(); val mint="pool8394${System.nanoTime()}".padEnd(40,'A')
        val ts=TokenState(mint=mint,symbol="OLD"); ts.addedToWatchlistAt=now
        PoolCreationTime7385.record(mint,now-10000,"raydium")
        AdmissionBirth8144.notePairCreated8169(mint,"raydium",now-10000)
        assertNull(CanonicalTokenBirthTime7440.resolve(mint,now))
        assertNull(CanonicalTokenBirthTime7440.resolvedAgeMs(ts,now))
        assertEquals(AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN,AdmissionBirth8144.readFor8144(ts,now).kind)
        AdmissionBirth8144.noteOldestSignature8144(mint,now-86400000)
        assertEquals(86400000L,CanonicalTokenBirthTime7440.resolvedAgeMs(ts,now))
        assertEquals(86400000L,AdmissionBirth8144.readFor8144(ts,now).minAgeMs)
        AdmissionBirth8144.notePairCreated8169(mint,"raydium",now-5000)
        assertEquals(86400000L,CanonicalTokenBirthTime7440.resolvedAgeMs(ts,now))
    }
    @Test fun observedDiscoveryIsAMinimumAgeNotAVerifiedLaunch() {
        val now=System.currentTimeMillis();val ts=TokenState(mint="seen8394${System.nanoTime()}".padEnd(40,'B'),symbol="S")
        AdmissionBirth8144.noteObservation8394(ts.mint,now-3600000)
        assertNull(CanonicalTokenBirthTime7440.resolve(ts.mint,now))
        val r=AdmissionBirth8144.readFor8144(ts,now)
        assertEquals(AdmissionBirth8144.Kind8144.PROVEN_OLDER_THAN,r.kind)
        assertTrue(r.minAgeMs>=3600000)
        assertEquals("FIRST_SEEN",r.source)
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun actualPendingEncoderRoundTripsDetachedGradedArrays() {
        val cls=Cortex7885::class.java
        val field=cls.getDeclaredField("graded8006").apply { isAccessible=true }
        val graded=field.get(Cortex7885) as MutableMap<String,Any>
        val original=HashMap(graded)
        val decoder=cls.getDeclaredMethod("decodeGraded8372",JSONObject::class.java,List::class.java,java.lang.Long.TYPE).apply { isAccessible=true }
        val snapshot=cls.getDeclaredMethod("pendingSnapshot8076").apply { isAccessible=true }
        val restore=cls.getDeclaredMethod("restorePending",String::class.java,java.lang.Long.TYPE).apply { isAccessible=true }
        val now=System.currentTimeMillis()
        try {
            for(n in listOf(0,1,2,5)) {
                graded.clear()
                repeat(n) { i ->
                    val j=JSONObject().put("k","mint-$i|MOONSHOT").put("l","MOONSHOT").put("at",now-1000)
                        .put("i",JSONArray()).put("x",JSONArray()).put("pr",JSONArray()).put("pw",JSONArray())
                        .put("n",12.0+i).put("gr",15.0+i).put("su","BASE").put("s","HUNTER")
                    val row=decoder.invoke(Cortex7885,j,emptyList<String>(),now) as Pair<String,Any>
                    graded[row.first]=row.second
                }
                val encode=synchronized(Cortex7885) { snapshot.invoke(Cortex7885) as ()->String }
                graded.clear() // mutate AFTER capture, before serialization
                val raw=encode();val json=JSONObject(raw)
                assertEquals(n,json.getJSONArray("g8013").length())
                assertNotNull(json.getJSONArray("p"))
                synchronized(Cortex7885) { restore.invoke(Cortex7885,raw,now) }
                assertEquals(n,graded.size)
            }
        } finally { graded.clear();graded.putAll(original) }
    }
}
