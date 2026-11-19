package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EntryStrategySnapshot6450
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * V5.0.8867 — from the owner's first phone memory map (5.0.8866, 18:10, 12 minutes up): a map's own view no longer hides
 * its owner, garbage is named by what was built, crypto runner-study coins are priced by their mint, a position with no
 * entry snapshot is not re-read from disk on every lookup, and a confirmed crypto floor is not re-confirmed per attempt.
 */
class Aate8867HeapOwnersTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    /** A minimal ART-style HPROF (1.0.3, 4-byte ids). */
    private class Hprof {
        private val top = ByteArrayOutputStream(); private val out = DataOutputStream(top)
        private val heap = ByteArrayOutputStream(); private val h = DataOutputStream(heap)
        private var next = 1; private val strings = HashMap<String, Int>()
        init { out.write("JAVA PROFILE 1.0.3".toByteArray()); out.writeByte(0); out.writeInt(4); out.writeLong(0L) }
        fun str(s: String): Int = strings.getOrPut(s) {
            val id = next++; val b = s.toByteArray()
            out.writeByte(0x01); out.writeInt(0); out.writeInt(4 + b.size); out.writeInt(id); out.write(b); id
        }
        fun loadClass(id: Int, name: String) { val n = str(name); out.writeByte(0x02); out.writeInt(0); out.writeInt(16); out.writeInt(id); out.writeInt(id); out.writeInt(0); out.writeInt(n) }
        fun classDump(id: Int, superId: Int, instSize: Int, statics: List<Pair<String, Int>>, fields: List<String>) {
            val st = statics.map { str(it.first) to it.second }; val fs = fields.map { str(it) }
            h.writeByte(0x20); h.writeInt(id); h.writeInt(0); h.writeInt(superId); repeat(5) { h.writeInt(0) }; h.writeInt(instSize)
            h.writeShort(0); h.writeShort(st.size); for ((n, v) in st) { h.writeInt(n); h.writeByte(2); h.writeInt(v) }
            h.writeShort(fs.size); for (n in fs) { h.writeInt(n); h.writeByte(2) }
        }
        fun instance(id: Int, cls: Int, vararg refs: Int) { h.writeByte(0x21); h.writeInt(id); h.writeInt(0); h.writeInt(cls); h.writeInt(refs.size * 4); for (r in refs) h.writeInt(r) }
        fun objectArray(id: Int, cls: Int, vararg el: Int) { h.writeByte(0x22); h.writeInt(id); h.writeInt(0); h.writeInt(el.size); h.writeInt(cls); for (e in el) h.writeInt(e) }
        fun bytes(id: Int, n: Int) { h.writeByte(0x23); h.writeInt(id); h.writeInt(0); h.writeInt(n); h.writeByte(8); h.write(ByteArray(n) { 7 }) }
        fun sticky(id: Int) { h.writeByte(0x05); h.writeInt(id) }
        fun done(): ByteArray { val b = heap.toByteArray(); out.writeByte(0x1C); out.writeInt(0); out.writeInt(b.size); out.write(b); return top.toByteArray() }
    }

    private fun analyse(bytes: ByteArray): HeapMapCore8866.Result {
        val f = File.createTempFile("aate8867", ".hprof")
        try { f.writeBytes(bytes); return HeapMapCore8866.analyse8866(f, "t") } finally { f.delete() }
    }

    @Test fun aMapsOwnViewDoesNotHideTheMapsOwner() {
        val hp = Hprof()
        val obj = 0x100; val map = 0x110; val view = 0x120; val box = 0x130; val owner = 0x140; val arr = 0x150; val churn = 0x160
        hp.loadClass(obj, "java.lang.Object"); hp.loadClass(map, "t.Map"); hp.loadClass(view, "t.Map\$EntryView")
        hp.loadClass(box, "t.Box"); hp.loadClass(owner, "t.Owner"); hp.loadClass(arr, "java.lang.Object[]"); hp.loadClass(churn, "t.Churn")
        val list = 0x1000; val theMap = 0x2000; val theView = 0x3000; val theBox = 0x4000
        hp.classDump(obj, 0, 8, emptyList(), emptyList())
        hp.classDump(map, obj, 16, emptyList(), listOf("table", "entrySet"))
        hp.classDump(view, obj, 12, emptyList(), listOf("map"))
        hp.classDump(box, obj, 12, emptyList(), listOf("m"))
        hp.classDump(owner, obj, 8, listOf("boxes" to list), emptyList())
        hp.classDump(arr, obj, 0, emptyList(), emptyList())
        hp.classDump(churn, obj, 12, emptyList(), listOf("json"))
        hp.sticky(owner)
        // the view is written before the box, so a first-come holder would be the view (the 8866 loop)
        val rows = IntArray(30) { 0x10000 + it * 0x2000 }
        hp.objectArray(0x5000, arr, *rows)
        hp.instance(theMap, map, 0x5000, theView)
        hp.instance(theView, view, theMap)
        hp.instance(theBox, box, theMap)
        hp.objectArray(list, arr, theBox)
        for (r in rows) hp.bytes(r, 5_000)
        // garbage: a dropped structure nothing holds
        for (i in 0 until 10) { hp.instance(0x90000 + i * 16, churn, 0xA0000 + i * 0x2000); hp.bytes(0xA0000 + i * 0x2000, 6_000) }
        val r = analyse(hp.done())
        assertTrue(r.text, r.complete)
        assertTrue(r.text, r.text.contains("static t.Owner.boxes > java.lang.Object[]"))
        assertTrue(r.text, r.text.contains("byte[] < java.lang.Object[] < t.Map.table < t.Box.m < java.lang.Object[]"))
        assertFalse(r.text, r.text.contains("t.Map\$EntryView.map"))
        assertFalse(r.text, r.text.contains("held only by what it holds"))
        assertTrue(r.text, r.text.contains("GARBAGE built and dropped, outermost: t.Churn"))
        assertTrue(r.text, r.text.contains("byte[] < t.Churn.json"))
        assertTrue(r.text, r.text.contains("GARBAGE rows ("))
    }

    @Test fun theNestingRuleIsExact() {
        assertEquals("java.util.concurrent.ConcurrentHashMap", HeapMapCore8866.outerOf8867("java.util.concurrent.ConcurrentHashMap\$EntrySetView"))
        assertEquals("a.B\$C", HeapMapCore8866.outerOf8867("a.B\$C\$D"))
        assertNull(HeapMapCore8866.outerOf8867("java.util.HashMap"))
        assertNull("an array is not nested", HeapMapCore8866.outerOf8867("java.util.HashMap\$Node[]"))
        val map = src("engine/HeapMap8866.kt")
        assertFalse("no collection before the dump: the garbage is the evidence", map.contains("rt.gc()"))
        assertTrue(map.contains("gcFirst=false"))
    }

    @Test fun aCryptoNominationIsPricedByItsMint() {
        val books = src("perps/CryptoStrategyCandidateBooks7803.kt")
        assertTrue(books.contains("RunnerStudy8852.nominate(DynamicAltTokenRegistry.solanaMintOf8866(ready.assetKey) ?: ready.assetKey,"))
        val m = "96WnzbBGRm3RadEMZcn6VfGQKTk7AaNYrUmTXNx9pump"
        assertEquals(m, com.lifecyclebot.perps.DynamicAltTokenRegistry.solanaMintOf8866("solana|$m"))
        assertNull("a non-Solana asset keeps its key", com.lifecyclebot.perps.DynamicAltTokenRegistry.solanaMintOf8866("base|0xc2daf5c7dd14ceb2203f09741aaaaaaaaaaaaaaa"))
    }

    @Test fun anAnsweredMissingEntryIsNotReadAgainUntilItCanHaveChanged() {
        assertTrue(EntryStrategySnapshot6450.missStands8867(1_000L, 1_000L))
        assertTrue(EntryStrategySnapshot6450.missStands8867(1_000L, 1_000L + 299_999L))
        assertFalse(EntryStrategySnapshot6450.missStands8867(1_000L, 1_000L + 300_000L))
        assertFalse(EntryStrategySnapshot6450.missStands8867(null, 1_000L))
        assertFalse("a clock that went back does not stand", EntryStrategySnapshot6450.missStands8867(5_000L, 1_000L))
        val s = src("engine/truth/EntryStrategySnapshot6450.kt")
        assertTrue("only an open store's answer is remembered",
            s.contains("if (LearningPersistence.ready() && !LearningPersistence.readFailed8086(persistenceKey6567(positionId))) {"))
        assertTrue("a new entry clears it", s.contains("missAt8867.remove(snap.positionId)   // V5.0.8867"))
        assertTrue(s.contains("missAt8867.remove(positionId)\n        snapshots[positionId] = next"))
        assertTrue(EntryStrategySnapshot6450.statusLine().contains("missServed8867="))
    }

    @Test fun aConfirmedFloorIsNotReconfirmedPerAttempt() {
        val t = src("perps/CryptoAltTrader.kt")
        assertTrue(t.contains("closePosition(id, \"TICK_HARD_FLOOR_\${tickPnl.toInt()}PCT\")\n                    if (!positions.containsKey(id)) tickFloorStrike7264.remove(id)"))
        assertTrue(t.contains("heldEval8866.remove(positionId)\n        tickFloorStrike7264.remove(positionId)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 58. What the phone's heap holds and what it throws away (8867)"))
    }
}
