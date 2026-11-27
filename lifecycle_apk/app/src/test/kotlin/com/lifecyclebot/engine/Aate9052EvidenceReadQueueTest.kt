package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EvidenceReadQueue9052
import org.junit.Assert.*
import org.junit.Test

class Aate9052EvidenceReadQueueTest {
    @Test fun reservedCapacityAndPriorities() {
        val q=EvidenceReadQueue9052<String>(8,2,2){0L}
        for(i in 0..3)assertEquals(EvidenceReadQueue9052.Result.QUEUED,q.offer("d"+i,3,0,20000,"d"+i))
        assertEquals(EvidenceReadQueue9052.Result.FULL,q.offer("d4",3,0,20000,"d4"))
        assertNull(q.poll(true))
        for(i in 1..2)assertEquals(EvidenceReadQueue9052.Result.QUEUED,q.offer("e"+i,1,0,5000,"entry"))
        assertEquals(EvidenceReadQueue9052.Result.FULL,q.offer("e3",1,0,5000,"entry"))
        for(i in 1..2)assertEquals(EvidenceReadQueue9052.Result.QUEUED,q.offer("h"+i,0,0,10000,"hold"))
        assertEquals(EvidenceReadQueue9052.Result.FULL,q.offer("h3",0,0,10000,"hold"))
        assertEquals(EvidenceReadQueue9052.Result.PROMOTED,q.offer("d1",1,1000000,5000,"fresh"))
        val first=q.poll(true)!!
        assertEquals("h1",first.key)
        assertEquals(EvidenceReadQueue9052.Result.COALESCED,q.offer("h1",0,0,10000,"again"))
        q.complete(first.key)
        val second=q.poll(true)!!;assertEquals("h2",second.key);q.complete(second.key)
        val promoted=q.poll(true)!!;assertEquals("d1",promoted.key)
        assertEquals("fresh",promoted.value);assertEquals(0L,promoted.enqueuedNs)
        assertTrue(promoted.expired(5001000000L));assertFalse(promoted.expired(5000999999L))
        q.complete(promoted.key);assertEquals(5 to 0,q.counts())
    }
    @Test fun expiredDiscoveryCannotConsumeNewUrgentBudget9058() {
        var now=0L
        val q=EvidenceReadQueue9052<String>(8,2,2){now}
        repeat(4){assertEquals(EvidenceReadQueue9052.Result.QUEUED,q.offer("d"+it,3,now,1000,"old"))}
        now=2_000_000_000L
        assertEquals(EvidenceReadQueue9052.Result.QUEUED,q.offer("d0",1,now,5000,"new"))
        assertEquals(4L,q.expiredWaiting9058())
        val work=q.poll(true)!!
        assertEquals("new",work.value);assertEquals(now,work.enqueuedNs);q.complete(work.key)
        assertEquals(EvidenceReadQueue9052.Result.QUEUED,q.offer("p",3,now,30_000,"background"))
        now+=7_000_000_000L
        assertEquals(EvidenceReadQueue9052.Result.PROMOTED,q.offer("p",1,now,5_000,"urgent"))
        val promoted=q.poll(true)!!
        assertFalse(promoted.expired(now+4_999_999_999L));assertTrue(promoted.expired(now+5_000_000_000L))
        q.complete(promoted.key)
        q.offer("expired",0,now,1,"expired")
        now+=2_000_000L
        assertNull(q.poll(true));assertEquals(5L,q.expiredWaiting9058())
    }
    @Test fun urgentWorkerDoesNotConsumeDiscoverySlot() {
        val q=EvidenceReadQueue9052<Int>(8,2,2){0L}
        val read=java.util.concurrent.atomic.AtomicInteger()
        val t=Thread{val w=q.take(true);read.set(w.value);q.complete(w.key)}
        t.isDaemon=true;t.start()
        try {
            q.offer("low",3,0,1000,1);q.offer("hi",0,0,1000,2)
            t.join(1000)
            assertFalse(t.isAlive);assertEquals(2,read.get());assertEquals(1 to 0,q.counts())
        } finally {t.interrupt()}
    }
}
