package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * V5.0.8866 — the memory map reads a heap dump down to class names, field names and sizes: who holds the bytes, and not
 * one value out of any field, array or string.
 */
class Aate8866HeapMapTest {

    /** A small ART-style HPROF (1.0.3, 4-byte ids, Android heap records) with known holders. */
    private class Hprof {
        private val top = ByteArrayOutputStream()
        private val out = DataOutputStream(top)
        private val heap = ByteArrayOutputStream()
        private val h = DataOutputStream(heap)
        private var nextString = 1
        private val strings = HashMap<String, Int>()

        init {
            out.write("JAVA PROFILE 1.0.3".toByteArray()); out.writeByte(0)
            out.writeInt(4); out.writeLong(0L)
        }

        fun str(s: String): Int = strings.getOrPut(s) {
            val id = nextString++
            val b = s.toByteArray(Charsets.UTF_8)
            out.writeByte(0x01); out.writeInt(0); out.writeInt(4 + b.size); out.writeInt(id); out.write(b)
            id
        }

        fun loadClass(classId: Int, name: String) {
            val n = str(name)
            out.writeByte(0x02); out.writeInt(0); out.writeInt(16)
            out.writeInt(classId); out.writeInt(classId); out.writeInt(0); out.writeInt(n)
        }

        fun heapInfo(type: Int, name: String) { val n = str(name); h.writeByte(0xFE); h.writeInt(type); h.writeInt(n) }

        /** statics: (name, object id); fields: (name, type). */
        fun classDump(classId: Int, superId: Int, instSize: Int, statics: List<Pair<String, Int>>, fields: List<Pair<String, Int>>) {
            val staticIds = statics.map { str(it.first) to it.second }
            val fieldIds = fields.map { str(it.first) to it.second }
            h.writeByte(0x20); h.writeInt(classId); h.writeInt(0); h.writeInt(superId)
            repeat(5) { h.writeInt(0) }
            h.writeInt(instSize)
            h.writeShort(0)
            h.writeShort(staticIds.size)
            for ((n, v) in staticIds) { h.writeInt(n); h.writeByte(2); h.writeInt(v) }
            h.writeShort(fieldIds.size)
            for ((n, t) in fieldIds) { h.writeInt(n); h.writeByte(t) }
        }

        fun instance(id: Int, classId: Int, data: ByteArray) {
            h.writeByte(0x21); h.writeInt(id); h.writeInt(0); h.writeInt(classId); h.writeInt(data.size); h.write(data)
        }

        fun objectArray(id: Int, arrayClassId: Int, elements: IntArray) {
            h.writeByte(0x22); h.writeInt(id); h.writeInt(0); h.writeInt(elements.size); h.writeInt(arrayClassId)
            for (e in elements) h.writeInt(e)
        }

        fun byteArray(id: Int, data: ByteArray) {
            h.writeByte(0x23); h.writeInt(id); h.writeInt(0); h.writeInt(data.size); h.writeByte(8); h.write(data)
        }

        fun intArrayNoData(id: Int, n: Int) { h.writeByte(0xC3); h.writeInt(id); h.writeInt(0); h.writeInt(n); h.writeByte(10) }
        fun rootSticky(id: Int) { h.writeByte(0x05); h.writeInt(id) }
        fun rootJavaFrame(id: Int) { h.writeByte(0x03); h.writeInt(id); h.writeInt(1); h.writeInt(0) }
        fun rootVmInternal(id: Int) { h.writeByte(0x8D); h.writeInt(id) }
        fun rootJniMonitor(id: Int) { h.writeByte(0x8E); h.writeInt(id); h.writeInt(1); h.writeInt(0) }
        fun rootUnreachable(id: Int) { h.writeByte(0x90); h.writeInt(id) }
        fun rawHeapByte(b: Int) { h.writeByte(b) }

        fun bytes(): ByteArray {
            val body = heap.toByteArray()
            out.writeByte(0x1C); out.writeInt(0); out.writeInt(body.size); out.write(body)
            out.writeByte(0x2C); out.writeInt(0); out.writeInt(0)
            return top.toByteArray()
        }
    }

    private val secret = "TOPSECRETWALLETKEY"

    private fun int4(vararg v: Int): ByteArray {
        val b = ByteArrayOutputStream(); val d = DataOutputStream(b)
        for (x in v) d.writeInt(x)
        return b.toByteArray()
    }

    /** 40 rows of 5,000 bytes under a static list, a 6,000-byte thread local, a 7,000-byte orphan, a zygote array. */
    private fun build(extra: (Hprof) -> Unit = {}): ByteArray {
        val f = Hprof()
        val objectClass = 0x1000; val holderClass = 0x1010; val rowClass = 0x1020; val objArrayClass = 0x1030
        f.loadClass(objectClass, "java.lang.Object")
        f.loadClass(holderClass, "t.Holder")
        f.loadClass(rowClass, "t.Row")
        f.loadClass(objArrayClass, "java.lang.Object[]")
        f.heapInfo(1, "app")
        val listId = 0x2000
        f.classDump(objectClass, 0, 8, emptyList(), emptyList())
        f.classDump(holderClass, objectClass, 8, listOf("rows" to listId), emptyList())
        // Row: own fields first (payload: object, n: int), then the superclass's (Object: none)
        f.classDump(rowClass, objectClass, 16, emptyList(), listOf("payload" to 2, "n" to 10))
        f.classDump(objArrayClass, objectClass, 0, emptyList(), emptyList())
        f.rootSticky(holderClass)
        val rows = IntArray(40) { 0x3000 + it * 16 }
        f.objectArray(listId, objArrayClass, rows)
        val payload = ByteArray(5_000) { i -> secret[i % secret.length].code.toByte() }
        for ((i, r) in rows.withIndex()) {
            val arr = 0x10000 + i * 0x2000
            f.instance(r, rowClass, int4(arr, 0x5EC2E7AA))
            f.byteArray(arr, payload)
        }
        val local = 0x90000
        f.byteArray(local, ByteArray(6_000) { 7 })
        f.rootJavaFrame(local)
        f.rootVmInternal(0x1000)
        f.rootJniMonitor(0x1000)
        f.byteArray(0xA0000, ByteArray(7_000) { 9 })
        f.rootUnreachable(0xA0000)
        f.intArrayNoData(0xB0000, 10)
        extra(f)
        f.heapInfo(2, "zygote")
        f.byteArray(0xC0000, ByteArray(50_000) { 1 })
        return f.bytes()
    }

    private fun analyse(bytes: ByteArray): HeapMapCore8866.Result {
        val file = File.createTempFile("aate8866", ".hprof")
        try {
            file.writeBytes(bytes)
            return HeapMapCore8866.analyse8866(file, "test header")
        } finally { file.delete() }
    }

    @Test fun theBytesAreTracedToTheStaticFieldThatHoldsThem() {
        val r = analyse(build())
        assertTrue(r.text, r.complete)
        // 40 payload arrays + 40 rows + the list + the local + the orphan + the int array; the zygote array is left out
        assertEquals(r.text, 84L, r.objects)
        assertTrue(r.text, r.text.startsWith("test header\n"))
        assertTrue(r.text, r.text.contains("status=COMPLETE"))
        assertTrue(r.text, r.text.contains("layoutMismatch=0"))
        assertTrue(r.text, r.text.contains("zygote="))
        assertTrue("the zygote heap's 50 KB is not counted", r.bytes < 300_000L)
        assertTrue(r.text, Regex("""\s40\s+t\.Row\n""").containsMatchIn(r.text))
        assertTrue(r.text, r.text.contains("static t.Holder.rows > java.lang.Object[]"))
        assertTrue(r.text, r.text.contains("byte[] < t.Row.payload < java.lang.Object[]"))
        assertTrue(r.text, r.text.contains("ROOT JAVA_FRAME"))
        // superseded by V5.0.8867 — an object nothing holds is garbage, named by the outermost object of what was dropped
        assertTrue(r.text, r.text.contains("GARBAGE built and dropped, outermost: byte[]"))
    }

    @Test fun noValueLeavesTheDump() {
        val text = analyse(build()).text
        assertFalse("array content", text.contains("TOPSECRET"))
        assertFalse("a field's value, decimal", text.contains(0x5EC2E7AA.toString()))
        assertFalse("a field's value, hex", text.lowercase().contains("5ec2e7aa"))
        // every line is a name, a count or a size: nothing longer than a chain of class names
        assertTrue(text.lines().none { it.contains(secret.take(6)) })
    }

    @Test fun aRecordItDoesNotKnowStopsItByNameAndKeepsWhatWasCounted() {
        val r = analyse(build { it.rawHeapByte(0x77) })
        assertFalse(r.complete)
        assertTrue(r.stopped, r.stopped.contains("0x77"))
        assertTrue(r.text, r.text.contains("status=STOPPED") && r.text.contains("stopped=unknown heap record 0x77"))
        val cut = build()
        val truncated = analyse(cut.copyOf(cut.size - 30_000))
        assertFalse(truncated.complete)
        assertTrue(truncated.stopped, truncated.stopped.contains("ended") || truncated.stopped.contains("past"))
        val notHprof = analyse("hello world, not a dump".toByteArray() + ByteArray(64))
        assertFalse(notHprof.complete)
    }

    @Test fun theSmallRulesAreExact() {
        assertEquals(4, HeapMapCore8866.typeSize8866(2, 4)); assertEquals(8, HeapMapCore8866.typeSize8866(2, 8))
        assertEquals(1, HeapMapCore8866.typeSize8866(4, 4)); assertEquals(1, HeapMapCore8866.typeSize8866(8, 4))
        assertEquals(2, HeapMapCore8866.typeSize8866(5, 4)); assertEquals(2, HeapMapCore8866.typeSize8866(9, 4))
        assertEquals(4, HeapMapCore8866.typeSize8866(6, 4)); assertEquals(4, HeapMapCore8866.typeSize8866(10, 4))
        assertEquals(8, HeapMapCore8866.typeSize8866(7, 4)); assertEquals(8, HeapMapCore8866.typeSize8866(11, 4))
        assertEquals(-1, HeapMapCore8866.typeSize8866(3, 4)); assertEquals(-1, HeapMapCore8866.typeSize8866(12, 4))
        assertEquals("java.lang.String[]", HeapMapCore8866.className8866("[Ljava/lang/String;"))
        assertEquals("byte[][]", HeapMapCore8866.className8866("[[B"))
        assertEquals("java.util.Map", HeapMapCore8866.className8866("java/util/Map"))
        assertEquals("java.lang.Object[]", HeapMapCore8866.className8866("java.lang.Object[]"))
        assertEquals(16L, HeapMapCore8866.arrayBytes8866(0, 1)); assertEquals(5_016L, HeapMapCore8866.arrayBytes8866(5_000, 1))
        assertEquals(96L, HeapMapCore8866.arrayBytes8866(10, 8))
        assertTrue("an object at least one sampling interval big is always taken", HeapMapCore8866.sampled8866(123L, 4096L, 4096L))
        assertFalse(HeapMapCore8866.sampled8866(2L * 4000L, 16L, 4096L))
        assertTrue(HeapMapCore8866.sampled8866(2L * 3L, 16L, 4096L))
        assertTrue(HeapMapCore8866.linkage8866("java.util.HashMap\$Node"))
        assertTrue(HeapMapCore8866.linkage8866("java.util.LinkedHashMap\$Entry"))
        assertFalse(HeapMapCore8866.linkage8866("java.util.ArrayList"))
        assertEquals("byte[] < A.next x3 < B.head", HeapMapCore8866.collapse8866(listOf("byte[]", "A.next", "A.next", "A.next", "B.head")))
        assertEquals("ROOT JNI_GLOBAL", HeapMapCore8866.rootName8866(0x01))
        assertEquals("byte[]", HeapMapCore8866.primitiveArrayName8866(8)); assertEquals("long[]", HeapMapCore8866.primitiveArrayName8866(11))
        assertEquals("char[]", HeapMapCore8866.primitiveArrayName8866(5)); assertEquals("int[]", HeapMapCore8866.primitiveArrayName8866(10))
        // object ids are 8-byte aligned addresses: neighbours must not land in neighbouring sample slots
        val mixed = (1L..512L).map { HeapMapCore8866.mix8866(it * 8L) }
        assertEquals(512, mixed.toSet().size)
        assertTrue("the low bits carry the id", mixed.map { (it ushr 1) % 4096L }.toSet().size > 400)
        assertEquals(HeapMapCore8866.mix8866(4096L), HeapMapCore8866.mix8866(4096L))
    }

    @Test fun theDumpIsASecretAndTheMapIsOptIn() {
        fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
        val map = src("engine/HeapMap8866.kt")
        assertTrue("written under the private cache", map.contains("File(app.cacheDir, DIR)"))
        assertTrue("deleted when read", map.contains("try { file.delete() } catch (_: Throwable) {}"))
        assertTrue("deleted whatever happened", map.contains("try { raw?.delete() } catch (_: Throwable) {}"))
        assertTrue("deleted at start", src("AATEApp.kt").contains("HeapMap8866.sweep8866(this)"))
        assertFalse("the core decodes no value", src("engine/HeapMapCore8866.kt").contains("readUTF"))
        val dialog = src("ui/ReportCopyDialog8198.kt")
        assertTrue(dialog.contains("offerHeapMap8866(activity)") && dialog.contains(".setNegativeButton(\"Not now\", null)"))
        val activity = src("ui/PipelineHealthActivity.kt")
        assertTrue("the test trigger is a debug build's only", activity.contains("aate_heap_map_8866") && activity.contains("FLAG_DEBUGGABLE"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("HeapMap8866.section8866()"))
        assertTrue(File("../../ci/runtime_device_checks_8418.sh").readText().contains("record heap_map"))
        assertTrue(File("../../.github/workflows/runtime-test.yml").readText().contains("report_pre restart heap_map"))
        assertEquals((512L shl 20) * 3 / 2 + (128L shl 20), HeapMap8866.diskNeeded8866(512L shl 20))
        val none = HeapMap8866.reportBlock8866(null, 0L, 1_000L, "")
        assertTrue(none, none.contains("none taken") && none.contains("Take memory map"))
        val shown = HeapMap8866.reportBlock8866((1..200).joinToString("\n") { "line $it" }, 0L, 600_000L, "", maxLines = 5)
        assertTrue(shown, shown.contains("taken 10 min before") && shown.contains("first 5 of 200 lines") && shown.contains("line 5") && !shown.contains("line 6\n"))
    }
}
