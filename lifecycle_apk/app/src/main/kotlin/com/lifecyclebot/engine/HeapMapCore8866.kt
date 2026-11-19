package com.lifecyclebot.engine

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * V5.0.8866 §WHAT_HOLDS_THE_HEAP — a heap dump read down to names and sizes.
 *
 * 33 reports (5.0.8680 .. 5.0.8864): the lowest heap in the timeline rises with uptime, from ~120-230 MB in the first
 * quarter hour to ~360 MB after two hours, while MemoryCensus8144 stays at ~95-105 MB. By 5.0.8864's 3.6 h the heap sat
 * at 462 of 512 MB and the collector had run for 86 % of the uptime (blocking 74 %): avg cycle 5.8 s, a sell that took
 * six minutes, a label batch that took 33. What accumulates is outside every store the census can name, and a class
 * count alone does not say who holds a byte[].
 *
 * This reads an HPROF file (ART's 1.0.3 or the JVM's 1.0.2) in bounded memory and writes text:
 *   - the classes holding the most bytes (count and shallow bytes), per heap;
 *   - for a byte-weighted sample of objects, the chain of holders up to a static field or a GC root, found one hop per
 *     pass over the file, so the answer is "static X.y > X.rows holds 82 MB of byte[] < String.value < Row.json".
 *
 * Nothing but class names, field names, counts and sizes is ever read out of the file: no primitive value, no array
 * content and no string content is decoded (field data is only ever probed at reference offsets). The raw dump holds
 * the wallet's keys in plain bytes; it stays in app-private storage and is deleted by the caller (HeapMap8866).
 */
object HeapMapCore8866 {

    class Result(val complete: Boolean, val text: String, val objects: Long, val bytes: Long, val classes: Int, val samples: Int, val stopped: String)

    private const val TOP_CLASSES = 70
    private const val TOP_OWNERS = 45
    private const val CHAINS_PER_OWNER = 3
    private const val MAX_NODES = 600_000

    // ── HPROF constants ──
    private const val TAG_STRING = 0x01
    private const val TAG_LOAD_CLASS = 0x02
    private const val TAG_HEAP_DUMP = 0x0C
    private const val TAG_HEAP_DUMP_SEGMENT = 0x1C

    private const val SUB_ROOT_UNKNOWN = 0xFF
    private const val SUB_ROOT_JNI_GLOBAL = 0x01
    private const val SUB_ROOT_JNI_LOCAL = 0x02
    private const val SUB_ROOT_JAVA_FRAME = 0x03
    private const val SUB_ROOT_NATIVE_STACK = 0x04
    private const val SUB_ROOT_STICKY_CLASS = 0x05
    private const val SUB_ROOT_THREAD_BLOCK = 0x06
    private const val SUB_ROOT_MONITOR_USED = 0x07
    private const val SUB_ROOT_THREAD_OBJECT = 0x08
    private const val SUB_CLASS_DUMP = 0x20
    private const val SUB_INSTANCE_DUMP = 0x21
    private const val SUB_OBJECT_ARRAY_DUMP = 0x22
    private const val SUB_PRIMITIVE_ARRAY_DUMP = 0x23
    private const val SUB_HEAP_DUMP_INFO = 0xFE
    private const val SUB_ROOT_INTERNED_STRING = 0x89
    private const val SUB_ROOT_FINALIZING = 0x8A
    private const val SUB_ROOT_DEBUGGER = 0x8B
    private const val SUB_ROOT_REFERENCE_CLEANUP = 0x8C
    private const val SUB_ROOT_VM_INTERNAL = 0x8D
    private const val SUB_ROOT_JNI_MONITOR = 0x8E
    private const val SUB_ROOT_UNREACHABLE = 0x90
    private const val SUB_PRIMITIVE_ARRAY_NODATA = 0xC3

    private const val TYPE_OBJECT = 2
    private const val UNKNOWN_CLASS = Int.MIN_VALUE

    /** Pure: bytes of one value of an HPROF basic type ([idSize] for a reference), or -1 for a type the format does not define. */
    fun typeSize8866(type: Int, idSize: Int): Int = when (type) {
        1, 2 -> idSize
        4, 8 -> 1
        5, 9 -> 2
        6, 10 -> 4
        7, 11 -> 8
        else -> -1
    }

    /** Pure: the name of a primitive array of [type]. */
    fun primitiveArrayName8866(type: Int): String = when (type) {
        4 -> "boolean[]"; 5 -> "char[]"; 6 -> "float[]"; 7 -> "double[]"
        8 -> "byte[]"; 9 -> "short[]"; 10 -> "int[]"; 11 -> "long[]"
        else -> "primitive$type[]"
    }

    /** Pure: a class name as ART prints it ("java.lang.String[]") from either writer's spelling ("[Ljava/lang/String;"). */
    fun className8866(raw: String): String {
        var s = raw
        var dims = 0
        while (s.startsWith("[")) { dims++; s = s.substring(1) }
        if (dims > 0) {
            s = when {
                s.startsWith("L") && s.endsWith(";") -> s.substring(1, s.length - 1)
                s == "B" -> "byte"; s == "C" -> "char"; s == "I" -> "int"; s == "J" -> "long"
                s == "Z" -> "boolean"; s == "S" -> "short"; s == "F" -> "float"; s == "D" -> "double"
                else -> s
            }
        }
        s = s.replace('/', '.')
        return if (dims == 0) s else s + "[]".repeat(dims)
    }

    private fun align8(n: Long): Long = (n + 7L) and 7L.inv()

    /** Pure: the heap bytes of an array of [n] elements of [elemSize] bytes (12-byte header; 8-byte elements start at 16). */
    fun arrayBytes8866(n: Long, elemSize: Int): Long = align8((if (elemSize == 8) 16L else 12L) + n * elemSize)

    /** Pure: is an object of [size] bytes sampled at one sample per [every] bytes? ([hash] = a mixed object id.) Bigger always is. */
    fun sampled8866(hash: Long, size: Long, every: Long): Boolean = size >= every || ((hash ushr 1) % every) < size

    /** Pure: a 64-bit mix of an object id (object ids are aligned addresses; their low bits carry nothing). */
    fun mix8866(id: Long): Long {
        var z = id + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Pure: a holder class whose only job is to link elements (a hop through it says nothing about the owner). */
    fun linkage8866(className: String): Boolean =
        className.endsWith("\$Node") || className.endsWith("\$Entry") || className.endsWith("\$TreeNode") ||
            className.endsWith("\$HashEntry") || className.endsWith("\$LinkedNode") || className.endsWith("\$Segment")

    /** Pure: [hops] (leaf first) with a run of the same hop written once ("X.next x9"). */
    fun collapse8866(hops: List<String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < hops.size) {
            var j = i
            while (j + 1 < hops.size && hops[j + 1] == hops[i]) j++
            if (sb.isNotEmpty()) sb.append(" < ")
            sb.append(hops[i])
            if (j > i) sb.append(" x").append(j - i + 1)
            i = j + 1
        }
        return sb.toString()
    }

    // ── bounded reader ──
    private class Reader(file: File, bufSize: Int = 1 shl 20) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        val length: Long = raf.length()
        private val buf = ByteArray(bufSize)
        private var bufStart = 0L
        private var bufLen = 0
        private var p = 0
        var idSize = 4

        fun position(): Long = bufStart + p

        fun seek(pos: Long) {
            if (pos >= bufStart && pos <= bufStart + bufLen) { p = (pos - bufStart).toInt(); return }
            raf.seek(pos); bufStart = pos; bufLen = 0; p = 0
        }

        private fun fill() {
            bufStart += bufLen
            p = 0
            val n = raf.read(buf, 0, buf.size)
            bufLen = if (n < 0) 0 else n
            if (bufLen == 0) throw java.io.EOFException("end of file at $bufStart")
        }

        fun u1(): Int { if (p >= bufLen) fill(); return buf[p++].toInt() and 0xFF }
        fun u2(): Int = (u1() shl 8) or u1()
        fun u4(): Long {
            if (bufLen - p >= 4) {
                val v = ((buf[p].toLong() and 0xFF) shl 24) or ((buf[p + 1].toLong() and 0xFF) shl 16) or
                    ((buf[p + 2].toLong() and 0xFF) shl 8) or (buf[p + 3].toLong() and 0xFF)
                p += 4
                return v
            }
            return (u1().toLong() shl 24) or (u1().toLong() shl 16) or (u1().toLong() shl 8) or u1().toLong()
        }
        fun u8(): Long = (u4() shl 32) or u4()
        fun id(): Long = if (idSize == 4) u4() else u8()
        fun skip(n: Long) { if (n > 0) seek(position() + n) }
        fun read(dst: ByteArray, len: Int) {
            var off = 0
            while (off < len) {
                if (p >= bufLen) fill()
                val k = minOf(len - off, bufLen - p)
                System.arraycopy(buf, p, dst, off, k)
                p += k; off += k
            }
        }
        override fun close() { try { raf.close() } catch (_: Throwable) {} }
    }

    // ── open-addressing long -> int map (0 is never a key: the null reference) ──
    private class LongIntMap(expected: Int) {
        private var cap = Integer.highestOneBit(maxOf(16, expected * 2 - 1)) shl 1
        private var keys = LongArray(cap)
        private var vals = IntArray(cap)
        var size = 0
            private set

        fun get(key: Long): Int {
            if (key == 0L) return -1
            var i = (mix8866(key).toInt()) and (cap - 1)
            while (true) {
                val k = keys[i]
                if (k == key) return vals[i]
                if (k == 0L) return -1
                i = (i + 1) and (cap - 1)
            }
        }

        fun put(key: Long, value: Int) {
            if (key == 0L) return
            if ((size + 1) * 2 > cap) grow()
            var i = (mix8866(key).toInt()) and (cap - 1)
            while (true) {
                val k = keys[i]
                if (k == key) { vals[i] = value; return }
                if (k == 0L) { keys[i] = key; vals[i] = value; size++; return }
                i = (i + 1) and (cap - 1)
            }
        }

        private fun grow() {
            val ok = keys; val ov = vals
            cap = cap shl 1
            keys = LongArray(cap); vals = IntArray(cap); size = 0
            for (i in ok.indices) if (ok[i] != 0L) put(ok[i], ov[i])
        }

        fun clear() { java.util.Arrays.fill(keys, 0L); size = 0 }
    }

    private class ClassInfo(val id: Long, var nameId: Long) {
        var superId = 0L
        var instSize = 0L
        var fieldNameIds: LongArray = LongArray(0)
        var fieldTypes: ByteArray = ByteArray(0)
        var name: String = ""
        // flattened over the superclass chain, built once: byte offsets of reference fields and their names
        var refOffsets: IntArray? = null
        var refNameIdx: IntArray? = null
        var flatSize = -1
        var count = 0L
        var bytes = 0L
        var isLinkage = false
        /** V5.0.8867 — the class this one is nested in ("Map" for "Map$EntrySetView"), or -1. */
        var outer = -1
    }

    private class Stop(val why: String) : RuntimeException(why)

    /**
     * Reads [file] and returns the map. [header] is printed first (the caller's own facts: version, heap, timing).
     * Never throws: a file it cannot read to the end returns what was counted with [Result.stopped] naming the place.
     */
    fun analyse8866(file: File, header: String, sampleTargets: Int = 20_000, maxPasses: Int = 18, progress: (String) -> Unit = {}): Result {
        val t0 = System.currentTimeMillis()
        var stopped = ""
        val classes = ArrayList<ClassInfo>(1 shl 15)
        val classIndex = LongIntMap(1 shl 15)
        val names = HashMap<Long, String>(1 shl 15)
        val wantNames = HashSet<Long>(1 shl 15)
        val primCount = LongArray(16)
        val primBytes = LongArray(16)
        val heapNameIds = HashMap<Int, Long>()
        val heapBytes = HashMap<Int, LongArray>()   // heap type -> [objects, bytes]
        var idSize = 4
        var format = ""
        var objects = 0L
        var bytes = 0L
        var layoutMismatch = 0L
        var unknownClassInstances = 0L
        var passes = 0

        // nodes of the holder forest (samples first)
        var nodeCap = 1 shl 15
        var nodeId = LongArray(nodeCap)
        var nodeClass = IntArray(nodeCap)       // class index, or -(primitive type) for a primitive array
        var nodeParent = IntArray(nodeCap)      // node index, -1 none
        var nodeVia = IntArray(nodeCap)         // index into viaNames
        var nodeTerm = IntArray(nodeCap)        // index into terminals, 0 none
        var nodeLevel = IntArray(nodeCap)
        var nodeWeight = LongArray(nodeCap)     // samples only
        var nodes = 0
        var sampleCount = 0
        val nodeOf = LongIntMap(1 shl 15)
        val viaNames = ArrayList<String>().apply { add("[]") }
        val viaIndex = HashMap<Long, Int>()
        val terminals = ArrayList<String>().apply { add("") }
        val terminalIndex = HashMap<String, Int>()

        fun growNodes() {
            nodeCap = nodeCap shl 1
            nodeId = nodeId.copyOf(nodeCap); nodeClass = nodeClass.copyOf(nodeCap); nodeParent = nodeParent.copyOf(nodeCap)
            nodeVia = nodeVia.copyOf(nodeCap); nodeTerm = nodeTerm.copyOf(nodeCap); nodeLevel = nodeLevel.copyOf(nodeCap)
            nodeWeight = nodeWeight.copyOf(nodeCap)
        }
        fun addNode(id: Long, cls: Int, level: Int, weight: Long): Int {
            if (nodes == nodeCap) growNodes()
            val n = nodes++
            nodeId[n] = id; nodeClass[n] = cls; nodeParent[n] = -1; nodeVia[n] = 0; nodeTerm[n] = 0; nodeLevel[n] = level; nodeWeight[n] = weight
            nodeOf.put(id, n)
            return n
        }
        fun terminal(label: String): Int = terminalIndex.getOrPut(label) { terminals.add(label); terminals.size - 1 }
        fun via(nameId: Long): Int = viaIndex.getOrPut(nameId) { viaNames.add(names[nameId] ?: "?"); viaNames.size - 1 }

        fun classOf(id: Long): ClassInfo? { val i = classIndex.get(id); return if (i < 0) null else classes[i] }
        fun classSlot(id: Long): ClassInfo {
            val i = classIndex.get(id)
            if (i >= 0) return classes[i]
            val c = ClassInfo(id, 0L)
            classIndex.put(id, classes.size); classes.add(c)
            return c
        }

        /** The reference-field offsets of [c]'s instances (own fields first, then each superclass's), built once. */
        fun flatten(c: ClassInfo) {
            if (c.refOffsets != null) return
            val offs = ArrayList<Int>(); val nms = ArrayList<Int>()
            var off = 0
            var next: ClassInfo? = c
            var guard = 0
            while (guard++ < 64) {
                val cur = next ?: break
                for (k in cur.fieldTypes.indices) {
                    val t = cur.fieldTypes[k].toInt()
                    val sz = typeSize8866(t, idSize)
                    if (sz < 0) { c.refOffsets = IntArray(0); c.refNameIdx = IntArray(0); c.flatSize = -1; return }
                    if (t == TYPE_OBJECT || t == 1) { offs.add(off); nms.add(via(cur.fieldNameIds[k])) }
                    off += sz
                }
                next = if (cur.superId == 0L) null else classOf(cur.superId)
            }
            c.refOffsets = offs.toIntArray(); c.refNameIdx = nms.toIntArray(); c.flatSize = off
        }

        var scratch = ByteArray(1 shl 16)
        fun refAt(b: ByteArray, o: Int): Long =
            if (idSize == 4) ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
            else {
                var v = 0L
                for (k in 0 until 8) v = (v shl 8) or (b[o + k].toLong() and 0xFF)
                v
            }

        // ── candidates of the current pass (one per frontier node) ──
        var candRank = ByteArray(0)
        var candHolder = LongArray(0)
        var candClass = IntArray(0)
        var candVia = IntArray(0)
        var candTerm = IntArray(0)
        var frontierFrom = 0
        var frontierTo = 0
        var level = 0
        val frontier = LongIntMap(1 shl 12)

        // Preference among the holders of one object: a static field (0); an object or array not yet in the forest (1);
        // one already in it (2: the two chains join); a linking node not yet in it (3) or already in it (4: along a
        // doubly linked list the neighbour that is new leads outward, the one already seen leads back); a GC root (5:
        // a thread's local often points at something a static also holds, and the static is the owner).
        fun offer(target: Long, rank: Int, holderId: Long, holderClass: Int, viaIdx: Int, term: Int) {
            val n = frontier.get(target)
            if (n < 0) return
            var r = rank
            if (term == 0) {
                // a holder of the object's own class is a link in a chain (Node.next, Message.next), whatever it is called
                if (r == 1 && holderClass == nodeClass[n]) r = 3
                // V5.0.8867 — a view nested in the object's own class (Map$EntrySetView.map, HashMap$Values.this$0) points
                // back at the map it was made from: the map's owner holds both. Phone 18:10: 31.6 MB of a ConcurrentHashMap's
                // keys and values were filed under its own entry-set view, a loop with no owner.
                if (r == 1 && holderClass >= 0 && nodeClass[n] >= 0 && classes[holderClass].outer == nodeClass[n]) r = 3
                val existing = nodeOf.get(holderId)
                if (existing == n) return
                if (existing >= 0) r = if (r == 3) 4 else 2
            }
            val k = n - frontierFrom
            if (r < candRank[k].toInt()) { candRank[k] = r.toByte(); candHolder[k] = holderId; candClass[k] = holderClass; candVia[k] = viaIdx; candTerm[k] = term }
        }
        /** Does walking up from [from] reach [target]? (a link from target to from would then close a loop) */
        fun reaches(from: Int, target: Int): Boolean {
            var cur = from
            var guard = 0
            while (cur >= 0 && guard++ < 96) { if (cur == target) return true; cur = nodeParent[cur] }
            return false
        }

        /** mode 0: classes; 1: names; 2: histogram + samples; 3: holders of the frontier. */
        fun pass(mode: Int, sampleEvery: Long) {
            Reader(file).use { r ->
                // header: "JAVA PROFILE 1.0.x\0" u4 idSize u8 time
                val sb = StringBuilder()
                while (true) { val b = r.u1(); if (b == 0) break; if (sb.length < 64) sb.append(b.toChar()) }
                format = sb.toString()
                if (!format.startsWith("JAVA PROFILE")) throw Stop("not an HPROF file (header '${format.take(24)}')")
                idSize = r.u4().toInt()
                if (idSize != 4 && idSize != 8) throw Stop("identifier size $idSize")
                r.idSize = idSize
                r.u8()
                var heapType = 0
                var counted = true
                while (r.position() < r.length) {
                    val recordAt = r.position()
                    val tag = r.u1()
                    r.u4()
                    val len = r.u4()
                    val end = r.position() + len
                    if (end > r.length) throw Stop("the record at $recordAt runs ${end - r.length} bytes past the end of the file: the dump is cut short")
                    when {
                        tag == TAG_STRING && mode == 1 -> {
                            val sid = r.id()
                            if (sid in wantNames) {
                                val n = (len - idSize).toInt()
                                if (n in 0..4096) {
                                    if (scratch.size < n) scratch = ByteArray(n)
                                    r.read(scratch, n)
                                    names[sid] = String(scratch, 0, n, Charsets.UTF_8)
                                }
                            }
                        }
                        tag == TAG_LOAD_CLASS && mode == 0 -> {
                            r.u4()
                            val cid = r.id()
                            r.u4()
                            val nameId = r.id()
                            classSlot(cid).nameId = nameId
                            wantNames.add(nameId)
                        }
                        (tag == TAG_HEAP_DUMP || tag == TAG_HEAP_DUMP_SEGMENT) && mode != 1 -> {
                            while (r.position() < end) {
                                val at = r.position()
                                val sub = r.u1()
                                when (sub) {
                                    SUB_ROOT_UNKNOWN, SUB_ROOT_STICKY_CLASS, SUB_ROOT_MONITOR_USED, SUB_ROOT_INTERNED_STRING,
                                    SUB_ROOT_FINALIZING, SUB_ROOT_DEBUGGER, SUB_ROOT_REFERENCE_CLEANUP, SUB_ROOT_VM_INTERNAL,
                                    SUB_ROOT_UNREACHABLE -> {
                                        val id = r.id()
                                        if (mode == 3 && sub != SUB_ROOT_UNREACHABLE) offer(id, 5, 0L, 0, 0, terminal(rootName8866(sub)))
                                    }
                                    SUB_ROOT_JNI_GLOBAL -> { val id = r.id(); r.id(); if (mode == 3) offer(id, 5, 0L, 0, 0, terminal(rootName8866(sub))) }
                                    SUB_ROOT_JNI_LOCAL, SUB_ROOT_JAVA_FRAME, SUB_ROOT_THREAD_OBJECT, SUB_ROOT_JNI_MONITOR -> {
                                        val id = r.id(); r.skip(8)
                                        if (mode == 3) offer(id, 5, 0L, 0, 0, terminal(rootName8866(sub)))
                                    }
                                    SUB_ROOT_NATIVE_STACK, SUB_ROOT_THREAD_BLOCK -> {
                                        val id = r.id(); r.skip(4)
                                        if (mode == 3) offer(id, 5, 0L, 0, 0, terminal(rootName8866(sub)))
                                    }
                                    SUB_HEAP_DUMP_INFO -> {
                                        heapType = r.u4().toInt()
                                        val nameId = r.id()
                                        if (mode == 0) { heapNameIds[heapType] = nameId; wantNames.add(nameId) }
                                        val hn = names[heapNameIds[heapType] ?: -1L] ?: ""
                                        counted = hn != "zygote" && hn != "image"
                                    }
                                    SUB_CLASS_DUMP -> {
                                        val cid = r.id()
                                        r.u4()
                                        val superId = r.id()
                                        r.skip(5L * idSize)
                                        val instSize = r.u4()
                                        val cp = r.u2()
                                        for (k in 0 until cp) {
                                            r.u2()
                                            val t = r.u1()
                                            val sz = typeSize8866(t, idSize)
                                            if (sz < 0) throw Stop("constant pool type $t at $at")
                                            r.skip(sz.toLong())
                                        }
                                        val statics = r.u2()
                                        val c = if (mode == 0) classSlot(cid) else classOf(cid)
                                        for (k in 0 until statics) {
                                            val nameId = r.id()
                                            val t = r.u1()
                                            val sz = typeSize8866(t, idSize)
                                            if (sz < 0) throw Stop("static field type $t at $at")
                                            if (t == TYPE_OBJECT || t == 1) {
                                                val v = r.id()
                                                if (mode == 0) wantNames.add(nameId)
                                                else if (mode == 3 && v != 0L && frontier.get(v) >= 0 && c != null) {
                                                    offer(v, 0, 0L, 0, 0, terminal("static " + c.name + "." + (names[nameId] ?: "?")))
                                                }
                                            } else r.skip(sz.toLong())
                                        }
                                        val fields = r.u2()
                                        if (mode == 0 && c != null) {
                                            c.superId = superId; c.instSize = instSize
                                            val fn = LongArray(fields); val ft = ByteArray(fields)
                                            for (k in 0 until fields) {
                                                fn[k] = r.id(); ft[k] = r.u1().toByte()
                                                if (ft[k].toInt() == TYPE_OBJECT || ft[k].toInt() == 1) wantNames.add(fn[k])
                                            }
                                            c.fieldNameIds = fn; c.fieldTypes = ft
                                        } else r.skip(fields.toLong() * (idSize + 1))
                                    }
                                    SUB_INSTANCE_DUMP -> {
                                        val id = r.id()
                                        r.u4()
                                        val cid = r.id()
                                        val n = r.u4().toInt()
                                        if (mode == 0) { r.skip(n.toLong()) } else {
                                            val ci = classIndex.get(cid)
                                            val c = if (ci < 0) null else classes[ci]
                                            if (mode == 2) {
                                                val size = if (c != null && c.instSize > 0L) align8(c.instSize) else align8(8L + n)
                                                if (c == null) unknownClassInstances++
                                                val hb = heapBytes.getOrPut(heapType) { LongArray(2) }; hb[0]++; hb[1] += size
                                                if (counted) {
                                                    objects++; bytes += size
                                                    if (c != null) { c.count++; c.bytes += size }
                                                    if (ci >= 0 && nodes < MAX_NODES && sampled8866(mix8866(id), size, sampleEvery)) addNode(id, ci, 0, maxOf(size, sampleEvery))
                                                }
                                                r.skip(n.toLong())
                                            } else if (c == null || frontier.size == 0) {
                                                r.skip(n.toLong())
                                            } else {
                                                flatten(c)
                                                val offs = c.refOffsets!!
                                                if (c.flatSize != n) { layoutMismatch++; r.skip(n.toLong()) }
                                                else if (offs.isEmpty()) r.skip(n.toLong())
                                                else {
                                                    if (scratch.size < n) scratch = ByteArray(n)
                                                    r.read(scratch, n)
                                                    val nm = c.refNameIdx!!
                                                    for (k in offs.indices) {
                                                        val v = refAt(scratch, offs[k])
                                                        if (v != 0L && frontier.get(v) >= 0) offer(v, if (c.isLinkage) 3 else 1, id, ci, nm[k], 0)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    SUB_OBJECT_ARRAY_DUMP -> {
                                        val id = r.id()
                                        r.u4()
                                        val n = r.u4()
                                        val cid = r.id()
                                        if (mode == 2) {
                                            val ci = classIndex.get(cid)
                                            val size = if (idSize == 4) arrayBytes8866(n, 4) else align8(16L + n * 8L)
                                            val hb = heapBytes.getOrPut(heapType) { LongArray(2) }; hb[0]++; hb[1] += size
                                            if (counted) {
                                                objects++; bytes += size
                                                if (ci >= 0) { classes[ci].count++; classes[ci].bytes += size } else unknownClassInstances++
                                                if (ci >= 0 && nodes < MAX_NODES && sampled8866(mix8866(id), size, sampleEvery)) addNode(id, ci, 0, maxOf(size, sampleEvery))
                                            }
                                            r.skip(n * idSize)
                                        } else if (mode == 3 && frontier.size > 0) {
                                            val ci = classIndex.get(cid)
                                            val rank = if (ci >= 0 && classes[ci].isLinkage) 3 else 1
                                            var left = n
                                            while (left > 0) {
                                                val v = r.id()
                                                if (v != 0L && frontier.get(v) >= 0) offer(v, rank, id, if (ci < 0) UNKNOWN_CLASS else ci, 0, 0)
                                                left--
                                            }
                                        } else r.skip(n * idSize)
                                    }
                                    SUB_PRIMITIVE_ARRAY_DUMP, SUB_PRIMITIVE_ARRAY_NODATA -> {
                                        val id = r.id()
                                        r.u4()
                                        val n = r.u4()
                                        val t = r.u1()
                                        val sz = typeSize8866(t, idSize)
                                        if (sz < 0 || t == TYPE_OBJECT || t == 1) throw Stop("primitive array type $t at $at")
                                        if (mode == 2) {
                                            val size = arrayBytes8866(n, sz)
                                            val hb = heapBytes.getOrPut(heapType) { LongArray(2) }; hb[0]++; hb[1] += size
                                            if (counted) {
                                                objects++; bytes += size
                                                primCount[t]++; primBytes[t] += size
                                                if (nodes < MAX_NODES && sampled8866(mix8866(id), size, sampleEvery)) addNode(id, -t, 0, maxOf(size, sampleEvery))
                                            }
                                        }
                                        if (sub == SUB_PRIMITIVE_ARRAY_DUMP) r.skip(n * sz)
                                    }
                                    else -> throw Stop("unknown heap record 0x" + Integer.toHexString(sub) + " at offset $at (segment 0x" + Integer.toHexString(tag) + ")")
                                }
                            }
                            if (r.position() != end) throw Stop("heap segment at ${end - len} ran ${r.position() - end} bytes past its length")
                        }
                    }
                    r.seek(end)
                }
            }
        }

        try {
            progress("classes")
            pass(0, 0L); passes++
            progress("names")
            pass(1, 0L); passes++
            for (c in classes) {
                c.name = className8866(names[c.nameId] ?: "class@" + java.lang.Long.toHexString(c.id))
                c.isLinkage = linkage8866(c.name)
            }
            val byName = HashMap<String, Int>(classes.size * 2)
            for (i in classes.indices) byName.putIfAbsent(classes[i].name, i)
            for (c in classes) c.outer = outerOf8867(c.name)?.let { byName[it] } ?: -1
            val sampleEvery = maxOf(4096L, file.length() / maxOf(1, sampleTargets))
            progress("objects")
            pass(2, sampleEvery); passes++
            sampleCount = nodes
            frontierFrom = 0; frontierTo = nodes; level = 0
            while (frontierTo > frontierFrom && passes < maxPasses + 3) {
                val n = frontierTo - frontierFrom
                candRank = ByteArray(n) { 127 }; candHolder = LongArray(n); candClass = IntArray(n); candVia = IntArray(n); candTerm = IntArray(n)
                frontier.clear()
                for (i in frontierFrom until frontierTo) frontier.put(nodeId[i], i)
                level++
                progress("holders $level (${n} objects)")
                pass(3, sampleEvery); passes++
                val start = nodes
                for (k in 0 until n) {
                    val node = frontierFrom + k
                    if (candRank[k].toInt() == 127) { nodeTerm[node] = terminal(NO_HOLDER_8867); continue }
                    nodeVia[node] = candVia[k]
                    if (candTerm[k] != 0) { nodeTerm[node] = candTerm[k]; continue }
                    val existing = nodeOf.get(candHolder[k])
                    if (existing >= 0) {
                        if (reaches(existing, node)) nodeTerm[node] = terminal("(held only by what it holds: a loop with no outside holder found)")
                        else nodeParent[node] = existing
                        continue
                    }
                    if (nodes >= MAX_NODES) { nodeTerm[node] = terminal("(holder table full)"); continue }
                    nodeParent[node] = addNode(candHolder[k], candClass[k], level, 0L)
                }
                frontierFrom = start; frontierTo = nodes
            }
        } catch (s: Stop) {
            stopped = s.why
        } catch (e: java.io.EOFException) {
            stopped = "file ended inside a record (" + (e.message ?: "") + ")"
        } catch (e: OutOfMemoryError) {
            stopped = "out of memory while reading"
        } catch (t: Throwable) {
            stopped = t.javaClass.simpleName + ": " + (t.message ?: "").take(120)
        }

        // ── text ──
        val out = StringBuilder(1 shl 15)
        fun mb(b: Long): String = String.format(Locale.US, "%.1fMB", b / 1048576.0)
        fun pct(b: Long): String = if (bytes <= 0L) "-" else String.format(Locale.US, "%.1f%%", 100.0 * b / bytes)
        out.append(header.trimEnd()).append('\n')
        out.append("format=").append(format).append(" idSize=").append(idSize).append(" file=").append(mb(file.length()))
            .append(" status=").append(if (stopped.isEmpty()) "COMPLETE" else "STOPPED").append(" passes=").append(passes)
            .append(" readMs=").append(System.currentTimeMillis() - t0).append('\n')
        if (stopped.isNotEmpty()) out.append("stopped=").append(stopped).append('\n')
        out.append("objects=").append(objects).append(" bytes=").append(mb(bytes)).append(" classes=").append(classes.size)
            .append(" samples=").append(sampleCount).append(" layoutMismatch=").append(layoutMismatch)
            .append(" unknownClass=").append(unknownClassInstances).append('\n')
        if (heapBytes.isNotEmpty()) {
            out.append("heaps:")
            for ((t, v) in heapBytes.entries.sortedByDescending { it.value[1] }) {
                out.append(' ').append(names[heapNameIds[t] ?: -1L] ?: "default").append('=').append(mb(v[1])).append('/').append(v[0])
            }
            out.append("  (zygote and image heaps are the system's shared classes; the counts below leave them out)\n")
        }

        class Row(val name: String, val count: Long, val bytes: Long)
        val rows = ArrayList<Row>()
        for (c in classes) if (c.bytes > 0L) rows.add(Row(c.name, c.count, c.bytes))
        for (t in primCount.indices) if (primBytes[t] > 0L) rows.add(Row(primitiveArrayName8866(t), primCount[t], primBytes[t]))
        rows.sortByDescending { it.bytes }
        out.append("\n-- classes holding the most bytes (shallow: the object itself, not what it points to) --\n")
        for (rw in rows.take(TOP_CLASSES)) {
            out.append(String.format(Locale.US, "  %9s %6s %10d  %s\n", mb(rw.bytes), pct(rw.bytes), rw.count, rw.name))
        }

        if (sampleCount > 0) {
            fun hopName(node: Int): String {
                val c = nodeClass[node]
                return if (c == UNKNOWN_CLASS) "?[]" else if (c < 0) primitiveArrayName8866(-c) else classes[c].name
            }
            class Chain(var weight: Long, var n: Int)
            class Owner(var weight: Long, var n: Int) { val chains = HashMap<String, Chain>() }
            val owners = HashMap<String, Owner>()
            var resolvedBytes = 0L
            var garbageBytes = 0L
            var sampledBytes = 0L
            val hops = ArrayList<String>(24)
            for (s in 0 until sampleCount) {
                hops.clear()
                hops.add(hopName(s))
                var cur = s
                var term = ""
                var guard = 0
                var top = s
                while (true) {
                    val t = nodeTerm[cur]
                    val par = nodeParent[cur]
                    if (t != 0) { term = terminals[t]; break }
                    if (par < 0 || guard++ >= 4096) { term = "(not resolved in " + level + " passes)"; break }
                    // an array element is written as the array's class alone; a field as Class.field
                    hops.add(if (nodeVia[cur] == 0) hopName(par) else hopName(par) + "." + viaNames[nodeVia[cur]])
                    cur = par
                    top = par
                }
                val w = nodeWeight[s]
                sampledBytes += w
                // the owner is the terminal and the hop beneath it: "static X.INSTANCE > X.rows"
                val ownerLabel = when {
                    term.startsWith("static ") && hops.size > 1 -> term + " > " + hops[hops.size - 1]
                    term.startsWith("(not resolved") && hops.size > 1 -> term + " last holder seen: " + hops[hops.size - 1]
                    // V5.0.8867 — garbage is named by the outermost object of its dropped structure: what was built and thrown away
                    term == NO_HOLDER_8867 -> GARBAGE_8867 + hopName(top)
                    else -> term
                }
                if (term == NO_HOLDER_8867) garbageBytes += w
                if (term.startsWith("static ") || term.startsWith("ROOT ")) resolvedBytes += w
                val o = owners.getOrPut(ownerLabel) { Owner(0L, 0) }
                o.weight += w; o.n++
                val ch = o.chains.getOrPut(collapse8866(hops)) { Chain(0L, 0) }
                ch.weight += w; ch.n++
            }
            out.append("\n-- what holds the bytes (").append(sampleCount).append(" objects sampled by size; ")
                .append(mb(sampledBytes)).append(" represented, ").append(mb(resolvedBytes)).append(" traced to a static field or a root) --\n")
            out.append("   read a chain right to left: the owner is on the right, the bytes are on the left\n")
            out.append("   GARBAGE rows (").append(mb(garbageBytes))
                .append(" sampled) are objects no longer reachable and not yet collected: what the app builds and throws away, named by the outermost object of each dropped structure\n")
            val scale = if (sampledBytes > 0L) bytes.toDouble() / sampledBytes else 1.0
            for ((label, o) in owners.entries.sortedByDescending { it.value.weight }.take(TOP_OWNERS)) {
                val est = (o.weight * scale).toLong()
                out.append(String.format(Locale.US, "  %9s %6s n=%-6d %s\n", mb(est), pct(est), o.n, label))
                for ((chain, c) in o.chains.entries.sortedByDescending { it.value.weight }.take(CHAINS_PER_OWNER)) {
                    out.append(String.format(Locale.US, "      %9s n=%-5d %s\n", mb((c.weight * scale).toLong()), c.n, chain))
                }
            }
        }
        return Result(stopped.isEmpty(), out.toString(), objects, bytes, classes.size, sampleCount, stopped)
    }

    private const val NO_HOLDER_8867 = "(no holder found: unreachable, or held by the runtime)"
    private const val GARBAGE_8867 = "GARBAGE built and dropped, outermost: "

    /** Pure (V5.0.8867): the class a nested class is declared in ("a.B" for "a.B$C", "a.B$C" for "a.B$C$D"), or null. */
    fun outerOf8867(name: String): String? {
        if (name.endsWith("[]")) return null
        val i = name.lastIndexOf('$')
        return if (i > 0) name.substring(0, i) else null
    }

    /** Pure: the name of a GC root record. */
    fun rootName8866(sub: Int): String = "ROOT " + when (sub) {
        SUB_ROOT_JNI_GLOBAL -> "JNI_GLOBAL"
        SUB_ROOT_JNI_LOCAL -> "JNI_LOCAL"
        SUB_ROOT_JAVA_FRAME -> "JAVA_FRAME (a local variable of a running thread)"
        SUB_ROOT_NATIVE_STACK -> "NATIVE_STACK"
        SUB_ROOT_STICKY_CLASS -> "STICKY_CLASS"
        SUB_ROOT_THREAD_BLOCK -> "THREAD_BLOCK"
        SUB_ROOT_MONITOR_USED -> "MONITOR_USED"
        SUB_ROOT_THREAD_OBJECT -> "THREAD_OBJECT"
        SUB_ROOT_INTERNED_STRING -> "INTERNED_STRING"
        SUB_ROOT_FINALIZING -> "FINALIZING"
        SUB_ROOT_DEBUGGER -> "DEBUGGER"
        SUB_ROOT_REFERENCE_CLEANUP -> "REFERENCE_CLEANUP"
        SUB_ROOT_VM_INTERNAL -> "VM_INTERNAL"
        SUB_ROOT_JNI_MONITOR -> "JNI_MONITOR"
        else -> "UNKNOWN"
    }
}
