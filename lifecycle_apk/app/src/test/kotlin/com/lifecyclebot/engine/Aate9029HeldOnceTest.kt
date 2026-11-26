package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.ForwardOutcomeOutbox8404
import com.lifecyclebot.engine.truth.IdPool9029
import com.lifecyclebot.engine.truth.PocketLedger8610
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9029 — what the heap holds, held once: a ledger bin is made by its first write, a durable outbox payload is read back
 * from its store, a seen observation is a number, a trade id is one String, and a long row is written a piece at a time.
 * Every change keeps the same answers; these tests pin the answers and the saving.
 */
class Aate9029HeldOnceTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    // ── ledger bins ──

    @Test fun aBinIsMadeByItsFirstWriteAndNotByARead() {
        val seat = CortexLedger7885.Seat(6)
        assertEquals(6, seat.binCount9029()); assertEquals(6, seat.bins.size)
        assertEquals(0, seat.binsMade9029())
        // reads: the empty book's numbers, and nothing is made
        for (i in 0 until 6) { assertEquals(0.0, seat.binRead9029(i).n, 0.0); assertEquals(0.0, seat.binRead9029(i).mean(), 0.0); assertEquals(0.5, seat.binRead9029(i).winRate(), 0.0) }
        assertNull(seat.binReadOrNull9029(-1)); assertNull(seat.binReadOrNull9029(6))
        assertEquals(0.0, seat.binReadOrNull9029(5)!!.n, 0.0)
        assertTrue(CortexLedger7885.discriminates8007(seat))
        assertEquals(0, seat.binsMade9029())
        // a write through the unchanged handle makes that bin only
        seat.bins[2].add(7.0, false)
        assertEquals(1, seat.binsMade9029())
        assertEquals(1.0, seat.binRead9029(2).n, 0.0); assertEquals(7.0, seat.binRead9029(2).sum, 0.0)
        assertSame(seat.bins[2], seat.binRead9029(2))
        // a revision of a bin that was never written changes nothing, in particular not the shared empty book
        seat.binRead9029(4).revise(1.0, 9.0, false, true)
        assertEquals(0.0, seat.binRead9029(4).n, 0.0); assertEquals(0.0, seat.binRead9029(4).sum, 0.0)
        assertEquals(0.0, CortexLedger7885.Seat(3).binRead9029(0).sum, 0.0)
    }

    @Test fun anEmptyBookIsStoredShortAndRestoredUnmade() {
        val seat = CortexLedger7885.Seat(4)
        seat.bins[1].add(5.0, true)
        val enc = seat.encode()
        // scored;sseModel;sseBase;above;below;bin0..bin3;4 brier fields — the three unwritten bins and both sides are empty fields
        val f = enc.split(';')
        assertEquals(5 + 4 + 4, f.size)
        assertEquals(listOf("", ""), f.subList(3, 5))
        assertEquals("", f[5]); assertTrue(f[6].startsWith("1.0,5.0,25.0,1.0,1.0,")); assertEquals("", f[7]); assertEquals("", f[8])
        val back = CortexLedger7885.Seat(4).also { it.decode(enc) }
        assertEquals(1, back.binsMade9029()); assertEquals(0, back.sidesMade8924())
        assertEquals(enc, back.encode())
        assertEquals(5.0, back.binRead9029(1).sum, 0.0); assertEquals(1.0, back.binRead9029(1).runners, 0.0)
        assertEquals(enc, seat.detached8386().encode())
        assertEquals(1, seat.detached8386().binsMade9029())
        // a bin that was made and holds nothing is stored like one that was never made
        val touched = CortexLedger7885.Seat(4).also { it.bins[0].scale(1.0); it.bins[3].scale(1.0); it.bins[1].add(5.0, true) }
        assertEquals(3, touched.binsMade9029())
        assertEquals(enc, touched.encode())
        assertTrue(CortexLedger7885.Stat().holdsNothing9029())
        assertFalse(CortexLedger7885.Stat().also { it.sum = 456.0 }.holdsNothing9029())
        // a book stored by an earlier build — every bin and side as the empty book's 54 characters — restores unmade too
        val zeros = CortexLedger7885.EMPTY_SIDE_ENC_8924
        val old = "0;0.0;0.0;$zeros;$zeros;$zeros;${seat.bins[1].encode()};$zeros;$zeros;0.0;0.0;0.0;0.0"
        val fromOld = CortexLedger7885.Seat(4).also { it.decode(old) }
        assertEquals(1, fromOld.binsMade9029()); assertEquals(0, fromOld.sidesMade8924())
        assertEquals(enc, fromOld.encode())
    }

    @Test fun theEmptyCheckDecidesWithoutSplittingABookThatHoldsSomething() {
        assertTrue(CortexLedger7885.emptyEnc9029(CortexLedger7885.EMPTY_ENC_9029))
        assertTrue(CortexLedger7885.emptyEnc9029(CortexLedger7885.EMPTY_SIDE_ENC_8924))
        assertTrue(CortexLedger7885.emptyEnc9029("0.0,0.0,0.0,0.0"))
        assertFalse(CortexLedger7885.emptyEnc9029("12.0,-5.0,25.0,0.0,0.0,0.0,0.0,INDEPENDENT_CURVE_8365,0.0"))
        assertFalse("a decayed count below 1 still holds something", CortexLedger7885.emptyEnc9029("0.75,-5.0,25.0,0.0,0.0"))
        assertFalse(CortexLedger7885.emptyEnc9029("0.0,456.0,0.0,0.0,0.0"))
        assertFalse(CortexLedger7885.emptyEnc9029("NaN,0.0,0.0,0.0"))
    }

    @Test fun aLedgerReadsTheSameWithUnmadeBinsAsWithMadeEmptyOnes() {
        val edges = doubleArrayOf(30.0, 45.0, 60.0, 75.0)
        fun build(touchAll: Boolean): CortexLedger7885 {
            val l = CortexLedger7885()
            repeat(40) { l.lane("CORE").add(2.0, false) }
            val s = l.seat("V", "CORE", edges.size + 1)
            if (touchAll) for (i in 0 until s.bins.size) s.bins[i].scale(1.0)
            repeat(30) { s.bins[3].add(9.0, false) }
            return l
        }
        val lazy = build(false); val eager = build(true)
        assertEquals(1, lazy.seats["V|CORE"]!!.binsMade9029()); assertEquals(5, eager.seats["V|CORE"]!!.binsMade9029())
        for (raw in listOf(10.0, 40.0, 50.0, 70.0, 90.0)) {
            val a = lazy.predict("V", "CORE", edges, raw); val b = eager.predict("V", "CORE", edges, raw)
            assertEquals(b.netPct, a.netPct, 1e-12); assertEquals(b.pWin, a.pWin, 1e-12)
        }
        assertEquals("a prediction makes no bin", 1, lazy.seats["V|CORE"]!!.binsMade9029())
        // the stored text is the same, and so is what it restores to
        assertEquals(eager.seats["V|CORE"]!!.encode(), lazy.seats["V|CORE"]!!.encode())
        val back = CortexLedger7885().also { it.decode(JSONObject(lazy.encode().toString())) }
        assertEquals(1, back.seats["V|CORE"]!!.binsMade9029())
        assertTrue(lazy.census8904(Any()).contains("binsMade9029=1/5"))
        assertTrue(eager.estBytes8309() > lazy.estBytes8309())
    }

    // ── a long row, one piece at a time ──

    @Test fun aLongRowIsCutIntoTheSamePiecesOneAtATime() {
        val big = "x".repeat(2_500) + "😀" + "y".repeat(700)
        val seen = ArrayList<String>(); val idx = ArrayList<Int>()
        val n = LearningPersistence.eachPiece9029(big, 1_001) { i, piece -> idx.add(i); seen.add(piece) }
        assertEquals(LearningPersistence.splitRow8086(big, 1_001), seen)
        assertEquals(seen.size, n); assertEquals((0 until n).toList(), idx)
        assertEquals(big, seen.joinToString(""))
        assertEquals(0, LearningPersistence.eachPiece9029("", 10) { _, _ -> error("no piece") })
        val p = src("engine/LearningPersistence.kt")
        assertTrue(p.contains("val parts = if (json.length <= ROW_CHARS_8086) 0 else eachPiece9029(json) { i, piece ->"))
        assertFalse("the pieces are no longer all cut first", p.contains("val pieces = if (json.length <= ROW_CHARS_8086) emptyList() else splitRow8086(json)"))
    }

    // ── the outbox ──

    @Test fun aDurablePayloadIsReadBackFromItsStore() {
        val disk = linkedMapOf<String, String>()
        var reads = 0
        val q = ForwardOutcomeOutbox8404(8) { id -> reads++; disk[id] }
        // staged: written, then only the id is held
        assertEquals("payload-A", q.stagePayload("ep1", { "payload-A" }) { k, v -> disk[k] = v; true })
        assertTrue(q.parkedLine9029(), q.parkedLine9029().contains("rows=1 holdingText=0 parkedEver=1 missingInStore=0 readBack=true"))
        assertEquals(0, reads)
        assertEquals("payload-A", q.payload("ep1")); assertEquals(1, reads)
        assertEquals("payload-A", q.stagePayload("ep1", { error("already held") }) { _, _ -> error("already durable") })
        // a write that fails keeps the text here
        assertFalse(q.stage("ep2", { "payload-B" }) { _, _ -> false })
        assertTrue(q.parkedLine9029().contains("rows=2 holdingText=1"))
        // a revision is found by its horizon and its payload read back
        assertTrue(q.stage("ep3|R|100|3600000", { "rev" }) { k, v -> disk[k] = v; true })
        assertEquals("ep3|R|100|3600000" to "rev", q.firstRevisionForHorizon8521("ep3", 3_600_000L))
        // delivery reads the payload back, and delivers exactly it
        val delivered = ArrayList<String>()
        q.drain(32, { k, v -> disk[k] = v; true }, { delivered.add(it); true }, { disk.remove(it); true })
        assertEquals(setOf("payload-A", "payload-B", "rev"), delivered.toSet())
        assertEquals(0, q.size()); assertTrue(disk.isEmpty())
    }

    @Test fun aRestoredRowIsParkedAndARowTheStoreNoLongerHoldsIsReleased() {
        val disk = linkedMapOf("a" to "one", "b" to "two", "c" to "three")
        var failing = false
        val q = ForwardOutcomeOutbox8404(8) { id -> if (failing) throw IllegalStateException("store closed") else disk[id] }
        q.restore(HashMap(disk))
        assertTrue(q.parkedLine9029().contains("rows=3 holdingText=0 parkedEver=3"))
        // a read that fails is not an answer: nothing is delivered and nothing is released
        failing = true
        var calls = 0
        q.drain(32, { _, _ -> error("already durable") }, { calls++; true }, { error("not acknowledged") })
        assertEquals(0, calls); assertEquals(3, q.size())
        assertNull(q.payload("a"))
        // the store answers again; the row it no longer holds is released, the others are delivered
        failing = false
        disk.remove("b")
        val delivered = ArrayList<String>()
        q.drain(32, { _, _ -> error("already durable") }, { delivered.add(it); true }, { disk.remove(it); true })
        assertEquals(listOf("one", "three"), delivered)
        assertEquals(0, q.size())
        assertTrue(q.parkedLine9029().contains("missingInStore=1"))
    }

    @Test fun withoutAStoreToReadFromTheOutboxHoldsItsTextAsBefore() {
        val disk = linkedMapOf<String, String>()
        val q = ForwardOutcomeOutbox8404(4)
        assertTrue(q.stage("ep", { "text" }) { k, v -> disk[k] = v; true })
        assertTrue(q.parkedLine9029().contains("rows=1 holdingText=1 parkedEver=0 missingInStore=0 readBack=false"))
        assertEquals("text", q.payload("ep"))
        assertEquals("", ForwardOutcomeOutbox8404.PARKED_9029)
        val l = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(l.contains("ForwardOutcomeOutbox8404(MAX_PENDING_7731 * 3) { id -> readRow9029(FORWARD_PREFIX_8404 + id) }"))
        assertTrue(l.contains("if (store == null && p == null) throw IllegalStateException(\"row store not open\")"))
    }

    // ── the seen set and the id pool ──

    @Test fun aSeenObservationIsANumberAndABitPerLane() {
        val k = PocketLedger8610.obsKey9029("MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump", 1_795_000_000_000L, 900_000L)
        assertEquals(k, PocketLedger8610.obsKey9029("MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump", 1_795_000_000_000L, 900_000L))
        val others = listOf(
            PocketLedger8610.obsKey9029("MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApumq", 1_795_000_000_000L, 900_000L),
            PocketLedger8610.obsKey9029("MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump", 1_795_000_000_001L, 900_000L),
            PocketLedger8610.obsKey9029("MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump", 1_795_000_000_000L, 3_600_000L),
            PocketLedger8610.obsKey9029("MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump", 900_000L, 1_795_000_000_000L),
        )
        assertEquals(4, (others + k).toSet().size - 1)
        // 20,000 observations of 200 coins: no two share a key
        val many = HashSet<Long>()
        for (m in 0 until 200) for (t in 0 until 100) many.add(PocketLedger8610.obsKey9029("Coin${m}xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxpump", 1_795_000_000_000L + t * 15_000L, 900_000L))
        assertEquals(20_000, many.size)
        // first lane to file an observation, a second lane on it, and a repeat
        val key = PocketLedger8610.obsKey9029("SeenTest9029xxxxxxxxxxxxxxxxxxxxxxxxxxxxpump", 42L, 900_000L)
        assertEquals(true, PocketLedger8610.noteSeen9029(key, "SHITCOIN_9029T"))
        assertEquals(false, PocketLedger8610.noteSeen9029(key, "MOONSHOT_9029T"))
        assertNull(PocketLedger8610.noteSeen9029(key, "SHITCOIN_9029T"))
        assertNull(PocketLedger8610.noteSeen9029(key, "MOONSHOT_9029T"))
        assertTrue(PocketLedger8610.seenLine9029().startsWith("pocketSeen9029[observations="))
        val p = src("engine/truth/PocketLedger8610.kt")
        assertTrue(p.contains("private val recent = LinkedHashMap<Long, Long>()"))
        assertTrue(p.contains("val first = noteSeen9029(obsKey9029(mint, at, horizon), lane) ?: return"))
    }

    @Test fun anIdIsOneStringForEverySetThatHoldsIt() {
        IdPool9029.resetForTest9029()
        val a = String(charArrayOf('t', 'r', 'a', 'd', 'e', '-', '1')); val b = String(charArrayOf('t', 'r', 'a', 'd', 'e', '-', '1'))
        assertNotSame(a, b)
        assertSame(a, IdPool9029.canon9029(a))
        assertSame(a, IdPool9029.canon9029(b))
        assertEquals("idPool9029[ids=1 shared=1 first=1]", IdPool9029.line9029())
        assertTrue(IdPool9029.MAX_9029 >= 20_000)
        val f = src("engine/truth/CanonicalFinalityPersistence6486.kt")
        assertTrue(f.contains("ex[IdPool9029.canon9029(k)] = x.optString(k).intern()"))
        assertTrue(f.contains(".put(IdPool9029.canon9029(tradeId), reason.intern())"))
        val la = src("engine/truth/LearnerAttribution8418.kt")
        assertTrue(la.contains("prior.add(IdPool9029.canon9029(id))")); assertTrue(la.contains("current.add(IdPool9029.canon9029(id))"))
        IdPool9029.resetForTest9029()
    }

    // ── what the next report shows ──

    @Test fun theMapPrintsItsTailAndTheBooksAreWrittenDirectly() {
        val h = src("engine/HeapMapCore8866.kt")
        assertTrue(h.contains("private const val TOP_OWNERS = 160"))
        assertTrue(h.contains("take(if (est >= bytes / 20) CHAINS_WIDE_9029 else CHAINS_PER_OWNER)"))
        val x = src("engine/cortex/CortexExit7897.kt")
        assertTrue(x.contains("CortexLedger7885.withLedger9028(\"ledger\", ledger, org.json.JSONObject().put(\"target8091\", TARGET_8091)"))
        assertFalse(x.contains(".put(\"ledger\", ledger.encode())"))
        val d = src("engine/DiagnosisBrief8438.kt")
        assertTrue(d.contains("IdPool9029.line9029()")); assertTrue(d.contains("PocketLedger8610.seenLine9029()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## §214 — V5.0.9029: what the heap holds is held once"))
    }
}
