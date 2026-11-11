package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8000 — THE HIVE BRAIN: every installed instance's learnt edge, pooled.
 *
 * Owner: "share all learnt edge, specialist data, runner signals via the hive. if we have 100
 * installs ... the data shared and the edge would be unbeatable."
 *
 * One shared ledger in the collective Turso database (hive_edge_8000). Each instance uploads only
 * the CHANGE in its own evidence since its last upload (n, sum, sum of squares, wins, runners, best
 * per key) as an additive upsert, so the server row is the network total and nothing is counted
 * twice. Each instance pulls only rows changed since its last pull, and subtracts what it uploaded
 * itself: what remains is the rest of the network, merged into the local reads.
 *
 * Shared books (key prefix):
 *  - FRL|  forward-label cell / lane / stage tallies (5-minute label, progressively revised, 7997):
 *          watch-first, the runner grabber's proven cells, the edge gate's cohorts all read them.
 *  - SPEC| specialist fact combinations: a combination the network proves is promoted here too.
 *  - PB|   lane playbook setup books (proven / losing setups).
 *  - TAIL| tail-hunter ladder replays (cells, refusals, setups, sources, fact pairs).
 *
 * Runner signals (hive_runners_8000), checked every minute: a coin any instance sees run +100%
 * inside its first 30 minutes is broadcast; every other instance pulls it into intake at once, so
 * the whole network watches (and its gates judge) the runner while it is still running.
 *
 * A learning reset rebuilds local books from zero: a key whose local evidence fell below what was
 * uploaded is re-based, never uploaded as a negative.
 */
object HiveEdge8000 {

    private const val UPLOAD_MS = 10L * 60_000L
    // V5.0.8028 — A CLEAN HIVE in the same database. The 8000 tables summed every build's evidence with no
    // build tag, so labels from pre-8019 builds (floor coins filed under peak caps, unpriced cells, broken
    // bases) stayed in the network total and came straight back after every learning reset. 8028+ write and
    // read new tables only; the old ones are left untouched (nothing deleted, simply no longer read). On the
    // switch, everything this install already holds is counted as uploaded, so only what it learns from now
    // on enters the clean hive. Every row carries the build that last wrote it.
    // V5.0.8064 — a second clean hive: every network label before 8064 carried spike credit live never banks.
    const val EDGE_TABLE_8028 = "hive_edge_8076"
    const val RUNNER_TABLE_8028 = "hive_runners_8028"
    private const val FILE = "hive_uploaded_8076.txt"
    @Volatile private var baselineDue8028 = false
    private val baselined8028 = AtomicLong(0)
    private fun build8028(): String = try { com.lifecyclebot.BuildConfig.VERSION_NAME } catch (_: Throwable) { "?" }

    /** Pure: the switch baseline — every local key counts as already uploaded (its history stays out of the clean hive). */
    fun baseline8028(local: Map<String, DoubleArray>): Map<String, DoubleArray> = local.mapValues { it.value.copyOf() }
    private const val BATCH = 80
    private const val PULL_LIMIT = 5_000
    private const val RUNNER_PULL_WINDOW_MS = 15L * 60_000L

    // [n, sum, sumSq, wins, runners, best]
    // V5.0.8681 — the network book and this install's upload ledger live on disk (HiveBook8681). The heap holds only
    // [netCache8681], a bounded read cache of computed network evidence (hits and misses).
    @Volatile private var rows8681: HiveRows8681? = null
    private val memRows8681 = HiveMemoryRows8681()
    private const val NET_CACHE_MAX_8681 = 4_096
    private val NONE_8681 = DoubleArray(0)
    private val netCache8681 = object : java.util.LinkedHashMap<String, DoubleArray>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DoubleArray>?): Boolean = size > netCacheCap8681
    }
    @Volatile private var netCacheCap8681 = NET_CACHE_MAX_8681
    @Volatile private var aggCount8681 = 0
    @Volatile private var upCount8681 = 0
    private val diskOpenFailed8681 = AtomicLong(0)
    private const val META_LAST_PULL_8681 = "last_pull_ms"
    private const val META_BASELINED_8681 = "baselined_8028"

    /** The book: the on-device SQLite store once an app context exists, else the bounded heap stand-in. */
    private fun rows(): HiveRows8681 {
        rows8681?.let { return it }
        val ctx = com.lifecyclebot.AATEApp.appContextOrNull() ?: return memRows8681
        synchronized(this) {
            rows8681?.let { return it }
            val opened: HiveRows8681 = try { HiveSqlRows8681.open(ctx) } catch (_: Throwable) {
                diskOpenFailed8681.incrementAndGet()
                try { PipelineHealthCollector.labelInc("HIVE_BOOK_DISK_OPEN_FAILED_8681") } catch (_: Throwable) {}
                memRows8681
            }
            rows8681 = opened
            refreshCounts8681(opened)
            return opened
        }
    }

    private fun refreshCounts8681(r: HiveRows8681 = rows()) {
        try { aggCount8681 = r.aggregateCount(); upCount8681 = r.uploadedCount() } catch (_: Throwable) {}
    }

    private val netGen8681 = AtomicLong(0)
    private fun invalidateNet8681() { synchronized(netCache8681) { netGen8681.incrementAndGet(); netCache8681.clear() } }

    /** Writes network rows to the book and drops cached reads they change. */
    private fun storeAggregate8681(batch: List<Pair<String, DoubleArray>>) {
        if (batch.isEmpty()) return
        val r = rows()
        r.putAggregate(batch)
        invalidateNet8681()
        refreshCounts8681(r)
    }

    private fun storeUploaded8681(batch: List<Pair<String, DoubleArray>>) {
        if (batch.isEmpty()) return
        val r = rows()
        r.putUploaded(batch)
        invalidateNet8681()
        refreshCounts8681(r)
    }

    /** Test hooks (V5.0.8681): network rows in the active book. */
    internal fun putAggregateForTest8681(key: String, v: DoubleArray) = storeAggregate8681(listOf(key to v))
    internal fun removeAggregateForTest8681(keys: Collection<String>) { rows().removeAggregate(keys); invalidateNet8681(); refreshCounts8681() }

    /** Pure: the decoded [n, s, ss, w, r, b] of one pulled hive row. */
    internal fun rowValues8681(row: Map<String, Any?>): DoubleArray =
        DoubleArray(6) { i -> row[ROW_COLUMNS_8681[i]]?.toString()?.toDoubleOrNull() ?: 0.0 }
    private val ROW_COLUMNS_8681 = arrayOf("n", "s", "ss", "w", "r", "b")

    @Volatile private var lastPullMs = 0L
    @Volatile private var lastUploadMs = 0L
    @Volatile private var lastRunnerPullMs = 0L
    @Volatile private var loaded = false
    @Volatile private var tableReady = false
    private val uploadedRows = AtomicLong(0)
    private val pulledRows = AtomicLong(0)
    private val runnersSent = AtomicLong(0)
    private val runnersPulled = AtomicLong(0)
    private val promotedFromHive = AtomicLong(0)
    private val pendingRunners = ConcurrentHashMap<String, Triple<String, Double, Long>>()
    private val runnerSeen = ConcurrentHashMap<String, Long>()
    private val sentPeak = ConcurrentHashMap<String, Double>()

    // ── pure ──

    /** Pure: the delta to upload for one key, or null when nothing changed / the local book was reset. */
    fun delta8000(local: DoubleArray, uploadedSoFar: DoubleArray?): DoubleArray? {
        val u = uploadedSoFar ?: DoubleArray(6)
        if (local[0] < u[0]) return null
        val d = DoubleArray(6) { i -> if (i == 5) local[5] else local[i] - u[i] }
        return if (d[0] > 0.0 || kotlin.math.abs(d[1]) >= 0.01) d else null
    }

    /** Pure: the rest of the network for a key — the server total minus this instance's own uploads. */
    fun othersOf8000(total: DoubleArray, mine: DoubleArray?): DoubleArray {
        val m = mine ?: DoubleArray(6)
        return DoubleArray(6) { i -> if (i == 5) total[5] else (total[i] - m[i]).let { if (i == 1) it else it.coerceAtLeast(0.0) } }
    }

    // ── reads ──

    /** The rest of the network's evidence for [key] ([n, sum, sumSq, wins, runners, best]), or null. */
    fun net8000(key: String): DoubleArray? {
        synchronized(netCache8681) { netCache8681[key] }?.let { return if (it.isEmpty()) null else it.copyOf() }
        val gen = netGen8681.get()
        val r = rows()
        val total = try { r.aggregate(key) } catch (_: Throwable) { null }
        val net = if (total == null) null else {
            val o = capWeight8014(othersOf8000(total, try { r.uploaded(key) } catch (_: Throwable) { null }), HIVE_WINDOW_8014)
            if (o[0] >= 1.0) o else null
        }
        // A read that raced a book write is answered but not cached (the write already cleared the cache).
        synchronized(netCache8681) { if (netGen8681.get() == gen) netCache8681[key] = net?.copyOf() ?: NONE_8681 }
        return net
    }

    /**
     * Pure. V5.0.8014 — the network's all-time evidence for a key counts as at most [cap] outcomes
     * (its mean kept), so an install's own fresh labels are never swamped by weeks-old network history.
     */
    fun capWeight8014(v: DoubleArray, cap: Double): DoubleArray {
        if (v.size < 5 || !(v[0] > cap)) return v
        val k = cap / v[0]
        return DoubleArray(v.size) { i -> if (i <= 4) v[i] * k else v[i] }
    }

    const val HIVE_WINDOW_8014 = 1_000.0

    // ── V5.0.8144 — the network book is capped in memory ──
    // V5.0.8681 — the book itself is on disk now (HiveBook8681); what is capped on the heap is the read cache of computed
    // network evidence. AGGREGATE_CAP_8144 stays the hard ceiling for that cache under any pressure scaling.
    const val AGGREGATE_CAP_8144 = 60_000
    const val AGGREGATE_KEY_BYTES_8144 = 170L
    private val aggregateEvicted8144 = AtomicLong(0)

    /** Cap the resident read cache at [cap] keys (least recently read go first). Returns keys evicted. */
    fun capAggregate8144(cap: Int): Int {
        val target = minOf(cap, NET_CACHE_MAX_8681).coerceAtLeast(0)
        netCacheCap8681 = target.coerceAtLeast(256)
        var n = 0
        synchronized(netCache8681) {
            val it = netCache8681.keys.iterator()
            while (netCache8681.size > target && it.hasNext()) { it.next(); it.remove(); n++ }
        }
        if (n > 0) aggregateEvicted8144.addAndGet(n.toLong())
        return n
    }

    private fun aggregateCapNow8144(): Int = com.lifecyclebot.engine.MemoryBounds8144.capFor8144(
        AGGREGATE_CAP_8144, try { com.lifecyclebot.engine.MemoryGuard7977.pressure8144() } catch (_: Throwable) { com.lifecyclebot.engine.RetentionPolicy8136.Pressure.NORMAL }, 10_000)

    /** MemoryGuard7977 (registry trim): the read cache at the pressure-scaled cap; stale runner dedupe rows go. */
    fun trim8144(p: com.lifecyclebot.engine.RetentionPolicy8136.Pressure) {
        capAggregate8144(com.lifecyclebot.engine.MemoryBounds8144.capFor8144(AGGREGATE_CAP_8144, p, 10_000) / 15)
        val now = System.currentTimeMillis()
        runnerSeen.entries.removeIf { now - it.value > 24L * 3_600_000L }
    }

    fun evicted8144(): Long = aggregateEvicted8144.get()
    /** V5.0.8681 — hive rows actually resident on the heap: the read cache plus any heap stand-in book. */
    fun residentCount8681(): Int = synchronized(netCache8681) { netCache8681.size } + (rows8681 ?: memRows8681).residentRows()

    /** Keys of the shared ledger with [prefix] that carry other instances' evidence. */
    fun netKeys8000(prefix: String): List<String> = try { rows().netKeys(prefix) } catch (_: Throwable) { emptyList() }

    // ── local snapshot of every shared book ──

    private fun snapshot(): Map<String, DoubleArray> {
        val out = HashMap<String, DoubleArray>(8_000)
        try { for ((k, v) in ForwardReturnLabeler7731.hiveSnapshot8000()) out["FRL8527|$k"] = v } catch (_: Throwable) {}
        try { for ((k, v) in SpecialistMiner7972.hiveSnapshot8000()) out["SPEC8663|$k"] = v } catch (_: Throwable) {}
        try { for ((k, v) in com.lifecyclebot.engine.cortex.LanePlaybook7907.hiveSnapshot8000()) out["PB|$k"] = v } catch (_: Throwable) {}
        try { for ((k, v) in TailHunter7996.hiveSnapshot8000()) out["TAIL|$k"] = v } catch (_: Throwable) {}
        // V5.0.8001 — the Cortex's voter seats, dev reputations and expert wallets.
        try { for ((k, v) in com.lifecyclebot.engine.cortex.Cortex7885.hiveSnapshot8001()) out["CX|$k"] = v } catch (_: Throwable) {}
        try { for ((k, v) in SpecialistMiner7972.hiveDevSnapshot8001()) out["DEV8663|$k"] = v } catch (_: Throwable) {}
        try { for ((k, v) in com.lifecyclebot.engine.ExpertWallets7962.hiveSnapshot8001()) out["EXP|$k"] = v } catch (_: Throwable) {}
        return out
    }

    // ── persistence of what this instance has uploaded (so a restart never re-uploads) ──

    private fun file(): File? = com.lifecyclebot.AATEApp.appContextOrNull()?.let { File(it.filesDir, FILE) }

    private fun ensureLoaded() {
        if (loaded) return
        if (com.lifecyclebot.AATEApp.appContextOrNull() == null) return   // V5.0.8086 — no store yet: latching now made the next save replace the file with empty state
        loaded = true
        try {
            val r = rows()
            // V5.0.8681 — the pre-8681 upload ledger (one text file rewritten whole every sync) is streamed into the book
            // once, in chunks, then archived beside itself (renamed, never removed).
            val legacy = file()
            if (legacy != null && legacy.exists()) migrateUploadedFile8681(r, legacy)
            lastPullMs = maxOf(lastPullMs, r.meta(META_LAST_PULL_8681)?.toLongOrNull() ?: 0L)
            if (r.uploadedCount() == 0 && r.meta(META_BASELINED_8681) == null) baselineDue8028 = true
            refreshCounts8681(r)
        } catch (_: Throwable) {}
    }

    private fun migrateUploadedFile8681(r: HiveRows8681, legacy: File) {
        val chunk = ArrayList<Pair<String, DoubleArray>>(500)
        var pull = 0L
        legacy.forEachLine { line ->
            if (line.startsWith("#pull\t")) { pull = line.substringAfter('\t').toLongOrNull() ?: 0L; return@forEachLine }
            val k = line.substringBefore('\t'); val f = line.substringAfter('\t', "").split(',')
            if (k.isNotBlank() && f.size == 6) chunk += k to DoubleArray(6) { f[it].toDoubleOrNull() ?: 0.0 }
            if (chunk.size >= 500) { check(r.putUploaded(chunk)) { "hive upload ledger migration failed" }; chunk.clear() }
        }
        check(r.putUploaded(chunk)) { "hive upload ledger migration failed" }
        r.putMeta(META_LAST_PULL_8681, pull.toString())
        r.putMeta(META_BASELINED_8681, "migrated8681")
        legacy.renameTo(File(legacy.path + ".migrated8681"))
        try { PipelineHealthCollector.labelInc("HIVE_UPLOAD_LEDGER_MIGRATED_8681") } catch (_: Throwable) {}
    }

    /** V5.0.8681 — the upload ledger is written row by row as it changes; only the pull cursor is saved here. */
    private fun save() {
        try { rows().putMeta(META_LAST_PULL_8681, lastPullMs.toString()) } catch (_: Throwable) {}
    }

    /** V5.0.8681 — where a boot page-in starts: the saved cursor when the book on disk holds the network, else the start. */
    private fun resumeFrom8681(): Long = if (lastPullMs > 0L && aggCount8681 > 0) lastPullMs else 0L
    @Volatile private var lastPageOk8681 = false

    // ── sync (CollectiveLearning background loop) ──

    private suspend fun ensureTables(client: com.lifecyclebot.collective.TursoClient) {
        if (tableReady) return
        client.execute("CREATE TABLE IF NOT EXISTS $EDGE_TABLE_8028 (k TEXT PRIMARY KEY, n REAL, s REAL, ss REAL, w REAL, r REAL, b REAL, updated_ms INTEGER, build TEXT)")
        client.execute("CREATE INDEX IF NOT EXISTS ${EDGE_TABLE_8028}_upd ON $EDGE_TABLE_8028(updated_ms)")
        client.execute("CREATE TABLE IF NOT EXISTS $RUNNER_TABLE_8028 (mint TEXT PRIMARY KEY, symbol TEXT, peak REAL, first_ms INTEGER, updated_ms INTEGER, instance TEXT, build TEXT)")
        client.execute("CREATE INDEX IF NOT EXISTS ${RUNNER_TABLE_8028}_upd ON $RUNNER_TABLE_8028(updated_ms)")
        tableReady = true
    }

    /** Every ~10 minutes: upload this instance's deltas, pull the network's changes, promote what the network proves. */
    suspend fun sync8000(client: com.lifecyclebot.collective.TursoClient, instanceId: String) {
        val now = System.currentTimeMillis()
        if (instanceId.isBlank()) return
        // V5.0.8096 — a boot page-in still owed is retried on every sync call, whatever the upload cadence.
        val uploadDue8096 = now - lastUploadMs >= UPLOAD_MS
        if (!uploadDue8096 && fullPulledThisProcess8094) return
        ensureLoaded()
        ensureTables(client)
        // V5.0.8094 — the first sync of every process pages the WHOLE network book in. The aggregate lives only in memory
        // while lastPullMs is restored from disk, so after any restart (crash, update) the incremental pull below fetched
        // only rows changed since the last pull: 8094 ran with sharedKeys=0 and every setup / cell record fell back to
        // local data (SHITCOIN LAUNCH_LADDER_PROVEN n208 +227% -> n127 +37%, SPECIALIST_EDGE n884 -> n51).
        // V5.0.8096 — BEFORE the upload: 8095 at 243 s was still uploading its restart backlog (uploadedRows=2720 of ~28k
        // keys) and had not paged anything in (sharedKeys=0), so every proven-setup gate read local-only data for minutes.
        if (!fullPulledThisProcess8094) bootPageIn8505(client)
        if (!uploadDue8096) return
        lastUploadMs = now
        // Upload deltas.
        val local = snapshot()
        val book = rows()
        if (baselineDue8028) {
            storeUploaded8681(baseline8028(local).toList())
            book.putMeta(META_BASELINED_8681, "1")
            baselined8028.set(local.size.toLong())
            baselineDue8028 = false
            save()
            try { PipelineHealthCollector.labelInc("HIVE_CLEAN_BASELINE_8028") } catch (_: Throwable) {}
        }
        val deltas = ArrayList<Pair<String, DoubleArray>>()
        val rebase8681 = ArrayList<Pair<String, DoubleArray>>()
        for ((k, v) in local) {
            val prior = try { book.uploaded(k) } catch (_: Throwable) { null }
            if (prior != null && v[0] < prior[0]) { rebase8681 += k to v.copyOf(); continue }   // local book was reset: re-base
            delta8000(v, prior)?.let { deltas += k to it }
        }
        storeUploaded8681(rebase8681)
        for (chunk in deltas.chunked(BATCH)) {
            val stmts = chunk.map { (k, d) ->
                "INSERT INTO $EDGE_TABLE_8028 (k, n, s, ss, w, r, b, updated_ms, build) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(k) DO UPDATE SET n = n + excluded.n, s = s + excluded.s, ss = ss + excluded.ss, w = w + excluded.w, " +
                    "r = r + excluded.r, b = MAX(b, excluded.b), updated_ms = excluded.updated_ms, build = excluded.build" to
                    listOf<Any?>(k, d[0], d[1], d[2], d[3], d[4], d[5], now, build8028())
            }
            val ok = try { client.batch(stmts).all { it.success } } catch (_: Throwable) { false }
            if (!ok) break
            storeUploaded8681(chunk.mapNotNull { (k, _) -> local[k]?.let { k to it.copyOf() } })
            uploadedRows.addAndGet(chunk.size.toLong())
        }
        // Pull the network's changes since the last pull.
        val res = client.query(
            "SELECT k, n, s, ss, w, r, b, updated_ms FROM $EDGE_TABLE_8028 WHERE updated_ms > ? ORDER BY updated_ms LIMIT $PULL_LIMIT",
            listOf<Any?>(lastPullMs),
        )
        if (res.success) {
            var maxTs = lastPullMs
            val pulled8681 = ArrayList<Pair<String, DoubleArray>>(res.rows.size)
            for (row in res.rows) {
                val k = row["k"]?.toString().orEmpty()
                if (k.isBlank()) continue
                pulled8681 += k to rowValues8681(row)
                maxTs = maxOf(maxTs, row["updated_ms"]?.toString()?.toDoubleOrNull()?.toLong() ?: 0L)
            }
            storeAggregate8681(pulled8681)
            pulledRows.addAndGet(res.rows.size.toLong())
            lastPullMs = maxTs
            capAggregate8144(aggregateCapNow8144())   // V5.0.8144
        }
        save()
        // What the network proves becomes local authority.
        inherit()
        try { PipelineHealthCollector.labelInc("HIVE_EDGE_SYNC_8000") } catch (_: Throwable) {}
    }

    private fun inherit(): String {
        val sp = try { SpecialistMiner7972.hivePromote8000() } catch (_: Throwable) { 0 }
        val cx = try { com.lifecyclebot.engine.cortex.Cortex7885.hiveInherit8001() } catch (_: Throwable) { 0 }
        val ex = try { com.lifecyclebot.engine.ExpertWallets7962.hiveInherit8001() } catch (_: Throwable) { 0 }
        promotedFromHive.addAndGet(sp.toLong()); seatsInherited.addAndGet(cx.toLong()); expertsInherited.addAndGet(ex.toLong())
        return "specialists=$sp cortexSeats=$cx expertWallets=$ex"
    }

    private val seatsInherited = AtomicLong(0)
    private val expertsInherited = AtomicLong(0)

    @Volatile private var fullPulledThisProcess8094 = false

    /** V5.0.8094 — page every network row updated after [start] into the aggregate: (rows read, last updated_ms). */
    // V5.0.8505 §HIVE_PAGE_IN_NOT_BEHIND_COLLECTIVE — 8502/8504 ran with sharedKeys=0 pulledRows=0 (8381-8499 paged 36-47k
    // keys in by ~240-340 s). The boot page-in only ran inside the 10-minute collective job, AFTER
    // uploadLocalPatternAggregates()+downloadAll() and inside their try, so a slow or failing collective step left every
    // forward-label cell, playbook and tail book reading local-only data (admitted n1300 -> n300). The page-in is now also
    // attempted from the 1-minute runner job until it lands, guarded so only one runs, and every attempt's outcome is counted.
    private val pageInFlight8505 = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pageInAttempts8505 = AtomicLong(0)
    private val pageInEmpty8505 = AtomicLong(0)
    @Volatile private var pageInError8505: String = "-"
    private suspend fun bootPageIn8505(client: com.lifecyclebot.collective.TursoClient) {
        if (fullPulledThisProcess8094 || !pageInFlight8505.compareAndSet(false, true)) return
        try {
            ensureLoaded()
            pageInAttempts8505.incrementAndGet()
            // V5.0.8681 — the book survives restarts on disk: a boot resumes from its saved cursor and pages in only what
            // the network changed since (the whole network only when the book is empty).
            val from8681 = resumeFrom8681()
            val (rows, to) = pageIn8094(client, from8681)
            // An unreachable hive (rows=0) retries the full page-in on the next sync; a resumed book that the hive
            // answered with nothing new is already current.
            val current8681 = rows > 0 || (from8681 > 0L && lastPageOk8681)
            if (current8681) fullPulledThisProcess8094 = true else pageInEmpty8505.incrementAndGet()
            lastPullMs = maxOf(lastPullMs, to)
            pulledRows.addAndGet(rows.toLong())
            try { PipelineHealthCollector.labelInc(if (rows > 0) "HIVE_FULL_PAGE_IN_AT_BOOT_8094" else "HIVE_PAGE_IN_EMPTY_8505") } catch (_: Throwable) {}
            if (from8681 > 0L) try { PipelineHealthCollector.labelInc("HIVE_BOOK_RESUMED_FROM_DISK_8681") } catch (_: Throwable) {}
            if (rows > 0) { save(); inherit() }
        } catch (t: Throwable) {
            pageInError8505 = (t.message ?: t.javaClass.simpleName).take(80)
            try { PipelineHealthCollector.labelInc("HIVE_PAGE_IN_EXCEPTION_8505") } catch (_: Throwable) {}
        } finally { pageInFlight8505.set(false) }
    }

    private suspend fun pageIn8094(client: com.lifecyclebot.collective.TursoClient, start: Long): Pair<Int, Long> {
        var from = start
        var rows = 0
        var pages = 0
        lastPageOk8681 = false
        while (pages < 40) {
            val res = client.query(
                "SELECT k, n, s, ss, w, r, b, updated_ms FROM $EDGE_TABLE_8028 WHERE updated_ms > ? ORDER BY updated_ms LIMIT $PULL_LIMIT",
                listOf<Any?>(from),
            )
            if (!res.success) pageInError8505 = (res.error ?: "query failed").take(80)   // V5.0.8505
            if (res.success) lastPageOk8681 = true
            if (!res.success || res.rows.isEmpty()) break
            var maxTs = from
            // V5.0.8681 — each page goes straight to the book on disk; nothing of it stays resident.
            val page8681 = ArrayList<Pair<String, DoubleArray>>(res.rows.size)
            for (row in res.rows) {
                val k = row["k"]?.toString().orEmpty()
                if (k.isBlank()) continue
                page8681 += k to rowValues8681(row)
                maxTs = maxOf(maxTs, row["updated_ms"]?.toString()?.toDoubleOrNull()?.toLong() ?: 0L)
            }
            storeAggregate8681(page8681)
            rows += res.rows.size
            pages++
            capAggregate8144(aggregateCapNow8144())   // V5.0.8144 — bounded while paging, not after

            if (maxTs <= from || res.rows.size < PULL_LIMIT) { from = maxTs; break }
            from = maxTs
        }
        return rows to from
    }

    /**
     * V5.0.8001 — BOOST (Collective Brain screen): a new or reset install inherits the whole network's
     * learnt edge now — every shared book paged in from the start, then specialists promoted, Cortex
     * seats and expert wallets inherited. Returns a one-paragraph summary for the boost dialog.
     */
    suspend fun boost8001(client: com.lifecyclebot.collective.TursoClient): String {
        ensureLoaded()
        ensureTables(client)
        val (rows, from) = pageIn8094(client, 0L)
        fullPulledThisProcess8094 = true
        lastPullMs = maxOf(lastPullMs, from)
        pulledRows.addAndGet(rows.toLong())
        save()
        val got = inherit()
        try { PipelineHealthCollector.labelInc("HIVE_BOOST_8001") } catch (_: Throwable) {}
        return "Hive brain: $aggCount8681 shared keys ($rows rows read) — inherited $got"
    }

    // ── runner signals ──

    /** ForwardReturnLabeler7731: a coin this instance saw run +100% within its first 30 minutes. */
    fun noteRunner8000(mint: String, symbol: String, grossPct: Double, firstSeenMs: Long) {
        if (mint.isBlank() || !(grossPct >= 100.0)) return
        val last = sentPeak[mint] ?: 0.0
        if (last > 0.0 && grossPct < last * 2.0) return            // re-broadcast only when it doubles again
        sentPeak[mint] = grossPct
        if (sentPeak.size > 5_000) sentPeak.clear()
        pendingRunners[mint] = Triple(symbol, grossPct, firstSeenMs)
        if (pendingRunners.size > 200) pendingRunners.keys.take(100).forEach { pendingRunners.remove(it) }
    }

    /** Every minute: broadcast this instance's runners, pull everyone else's into intake. */
    suspend fun fastSync8000(client: com.lifecyclebot.collective.TursoClient, instanceId: String) {
        if (instanceId.isBlank()) return
        ensureTables(client)
        if (!fullPulledThisProcess8094) bootPageIn8505(client)   // V5.0.8505
        val now = System.currentTimeMillis()
        val send = pendingRunners.entries.toList()
        if (send.isNotEmpty()) {
            val stmts = send.map { (m, t) ->
                "INSERT INTO $RUNNER_TABLE_8028 (mint, symbol, peak, first_ms, updated_ms, instance, build) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(mint) DO UPDATE SET peak = MAX(peak, excluded.peak), updated_ms = excluded.updated_ms, build = excluded.build" to
                    listOf<Any?>(m, t.first.take(24), t.second, t.third, now, instanceId, build8028())
            }
            if (try { client.batch(stmts).all { it.success } } catch (_: Throwable) { false }) {
                send.forEach { pendingRunners.remove(it.key, it.value) }
                runnersSent.addAndGet(send.size.toLong())
            }
        }
        val since = maxOf(lastRunnerPullMs, now - RUNNER_PULL_WINDOW_MS)
        val res = client.query(
            "SELECT mint, symbol, peak, updated_ms FROM $RUNNER_TABLE_8028 WHERE updated_ms > ? AND instance != ? ORDER BY updated_ms DESC LIMIT 50",
            listOf<Any?>(since, instanceId),
        )
        lastRunnerPullMs = now
        if (!res.success) return
        for (row in res.rows) {
            val mint = row["mint"]?.toString().orEmpty()
            if (mint.length < 30 || runnerSeen.putIfAbsent("PULL|$mint", now) != null) continue
            val sym = row["symbol"]?.toString().orEmpty().ifBlank { mint.take(6) }
            try {
                com.lifecyclebot.engine.TokenMergeQueue.enqueue(
                    mint = mint, symbol = sym, scanner = "HIVE_RUNNER_8000",
                    laneAffinity = setOf("SHITCOIN", "MOONSHOT", "EXPRESS"),
                )
                runnersPulled.incrementAndGet()
                PipelineHealthCollector.labelInc("HIVE_RUNNER_INTAKE_8000")
            } catch (_: Throwable) {}
        }
        if (runnerSeen.size > 10_000) runnerSeen.entries.removeIf { now - it.value > 24L * 3_600_000L }
    }

    fun statusLine8000(): String =
        "sharedKeys=$aggCount8681 evicted8144=${aggregateEvicted8144.get()} mineUploaded=$upCount8681 resident8681=${residentCount8681()} book8681=${if (rows8681 is HiveSqlRows8681) "DISK" else "HEAP"} diskOpenFailed8681=${diskOpenFailed8681.get()} uploadedRows=${uploadedRows.get()} pulledRows=${pulledRows.get()} " +
            "specialistsFromHive=${promotedFromHive.get()} seatsInherited=${seatsInherited.get()} expertsInherited=${expertsInherited.get()} runnerSignals[sent=${runnersSent.get()} pulled=${runnersPulled.get()}] " +
            "books=FRL,SPEC,PB,TAIL,CX,DEV,EXP hive=$EDGE_TABLE_8028 baselinedKeys8028=${baselined8028.get()} pageIn8505[attempts=${pageInAttempts8505.get()} empty=${pageInEmpty8505.get()} done=$fullPulledThisProcess8094 err=$pageInError8505] lastPull=${if (lastPullMs > 0L) "${(System.currentTimeMillis() - lastPullMs) / 1000}s" else "-"}"
}
