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
    private const val FILE = "hive_uploaded_8000.txt"
    private const val BATCH = 80
    private const val PULL_LIMIT = 5_000
    private const val RUNNER_PULL_WINDOW_MS = 15L * 60_000L

    // [n, sum, sumSq, wins, runners, best]
    private val uploaded = ConcurrentHashMap<String, DoubleArray>()
    private val aggregate = ConcurrentHashMap<String, DoubleArray>()
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
        val total = aggregate[key] ?: return null
        val o = othersOf8000(total, uploaded[key])
        return if (o[0] >= 1.0) o else null
    }

    /** Keys of the shared ledger with [prefix] that carry other instances' evidence. */
    fun netKeys8000(prefix: String): List<String> = aggregate.keys.filter { it.startsWith(prefix) && net8000(it) != null }

    // ── local snapshot of every shared book ──

    private fun snapshot(): Map<String, DoubleArray> {
        val out = HashMap<String, DoubleArray>(8_000)
        try { for ((k, v) in ForwardReturnLabeler7731.hiveSnapshot8000()) out["FRL|$k"] = v } catch (_: Throwable) {}
        try { for ((k, v) in SpecialistMiner7972.hiveSnapshot8000()) out["SPEC|$k"] = v } catch (_: Throwable) {}
        try { for ((k, v) in com.lifecyclebot.engine.cortex.LanePlaybook7907.hiveSnapshot8000()) out["PB|$k"] = v } catch (_: Throwable) {}
        try { for ((k, v) in TailHunter7996.hiveSnapshot8000()) out["TAIL|$k"] = v } catch (_: Throwable) {}
        // V5.0.8001 — the Cortex's voter seats, dev reputations and expert wallets.
        try { for ((k, v) in com.lifecyclebot.engine.cortex.Cortex7885.hiveSnapshot8001()) out["CX|$k"] = v } catch (_: Throwable) {}
        try { for ((k, v) in SpecialistMiner7972.hiveDevSnapshot8001()) out["DEV|$k"] = v } catch (_: Throwable) {}
        try { for ((k, v) in com.lifecyclebot.engine.ExpertWallets7962.hiveSnapshot8001()) out["EXP|$k"] = v } catch (_: Throwable) {}
        return out
    }

    // ── persistence of what this instance has uploaded (so a restart never re-uploads) ──

    private fun file(): File? = com.lifecyclebot.AATEApp.appContextOrNull()?.let { File(it.filesDir, FILE) }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            file()?.takeIf { it.exists() }?.forEachLine { line ->
                if (line.startsWith("#pull\t")) { lastPullMs = line.substringAfter('\t').toLongOrNull() ?: 0L; return@forEachLine }
                val k = line.substringBefore('\t'); val f = line.substringAfter('\t', "").split(',')
                if (k.isNotBlank() && f.size == 6) uploaded[k] = DoubleArray(6) { f[it].toDoubleOrNull() ?: 0.0 }
            }
        } catch (_: Throwable) {}
    }

    private fun save() {
        try {
            file()?.writeText(buildString {
                append("#pull\t").append(lastPullMs).append('\n')
                for ((k, v) in uploaded) append(k).append('\t').append(v.joinToString(",")).append('\n')
            })
        } catch (_: Throwable) {}
    }

    // ── sync (CollectiveLearning background loop) ──

    private suspend fun ensureTables(client: com.lifecyclebot.collective.TursoClient) {
        if (tableReady) return
        client.execute("CREATE TABLE IF NOT EXISTS hive_edge_8000 (k TEXT PRIMARY KEY, n REAL, s REAL, ss REAL, w REAL, r REAL, b REAL, updated_ms INTEGER)")
        client.execute("CREATE INDEX IF NOT EXISTS hive_edge_8000_upd ON hive_edge_8000(updated_ms)")
        client.execute("CREATE TABLE IF NOT EXISTS hive_runners_8000 (mint TEXT PRIMARY KEY, symbol TEXT, peak REAL, first_ms INTEGER, updated_ms INTEGER, instance TEXT)")
        client.execute("CREATE INDEX IF NOT EXISTS hive_runners_8000_upd ON hive_runners_8000(updated_ms)")
        tableReady = true
    }

    /** Every ~10 minutes: upload this instance's deltas, pull the network's changes, promote what the network proves. */
    suspend fun sync8000(client: com.lifecyclebot.collective.TursoClient, instanceId: String) {
        val now = System.currentTimeMillis()
        if (instanceId.isBlank() || now - lastUploadMs < UPLOAD_MS) return
        lastUploadMs = now
        ensureLoaded()
        ensureTables(client)
        // Upload deltas.
        val local = snapshot()
        val deltas = ArrayList<Pair<String, DoubleArray>>()
        for ((k, v) in local) {
            val prior = uploaded[k]
            if (prior != null && v[0] < prior[0]) { uploaded[k] = v.copyOf(); continue }   // local book was reset: re-base
            delta8000(v, prior)?.let { deltas += k to it }
        }
        for (chunk in deltas.chunked(BATCH)) {
            val stmts = chunk.map { (k, d) ->
                "INSERT INTO hive_edge_8000 (k, n, s, ss, w, r, b, updated_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(k) DO UPDATE SET n = n + excluded.n, s = s + excluded.s, ss = ss + excluded.ss, w = w + excluded.w, " +
                    "r = r + excluded.r, b = MAX(b, excluded.b), updated_ms = excluded.updated_ms" to
                    listOf<Any?>(k, d[0], d[1], d[2], d[3], d[4], d[5], now)
            }
            val ok = try { client.batch(stmts).all { it.success } } catch (_: Throwable) { false }
            if (!ok) break
            for ((k, _) in chunk) local[k]?.let { uploaded[k] = it.copyOf() }
            uploadedRows.addAndGet(chunk.size.toLong())
        }
        // Pull the network's changes since the last pull.
        val res = client.query(
            "SELECT k, n, s, ss, w, r, b, updated_ms FROM hive_edge_8000 WHERE updated_ms > ? ORDER BY updated_ms LIMIT $PULL_LIMIT",
            listOf<Any?>(lastPullMs),
        )
        if (res.success) {
            var maxTs = lastPullMs
            for (row in res.rows) {
                val k = row["k"]?.toString().orEmpty()
                if (k.isBlank()) continue
                aggregate[k] = DoubleArray(6) { i -> row[listOf("n", "s", "ss", "w", "r", "b")[i]]?.toString()?.toDoubleOrNull() ?: 0.0 }
                maxTs = maxOf(maxTs, row["updated_ms"]?.toString()?.toDoubleOrNull()?.toLong() ?: 0L)
            }
            pulledRows.addAndGet(res.rows.size.toLong())
            lastPullMs = maxTs
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

    /**
     * V5.0.8001 — BOOST (Collective Brain screen): a new or reset install inherits the whole network's
     * learnt edge now — every shared book paged in from the start, then specialists promoted, Cortex
     * seats and expert wallets inherited. Returns a one-paragraph summary for the boost dialog.
     */
    suspend fun boost8001(client: com.lifecyclebot.collective.TursoClient): String {
        ensureLoaded()
        ensureTables(client)
        var from = 0L
        var rows = 0
        var pages = 0
        while (pages < 40) {
            val res = client.query(
                "SELECT k, n, s, ss, w, r, b, updated_ms FROM hive_edge_8000 WHERE updated_ms > ? ORDER BY updated_ms LIMIT $PULL_LIMIT",
                listOf<Any?>(from),
            )
            if (!res.success || res.rows.isEmpty()) break
            var maxTs = from
            for (row in res.rows) {
                val k = row["k"]?.toString().orEmpty()
                if (k.isBlank()) continue
                aggregate[k] = DoubleArray(6) { i -> row[listOf("n", "s", "ss", "w", "r", "b")[i]]?.toString()?.toDoubleOrNull() ?: 0.0 }
                maxTs = maxOf(maxTs, row["updated_ms"]?.toString()?.toDoubleOrNull()?.toLong() ?: 0L)
            }
            rows += res.rows.size
            pages++
            if (maxTs <= from || res.rows.size < PULL_LIMIT) { from = maxTs; break }
            from = maxTs
        }
        lastPullMs = maxOf(lastPullMs, from)
        pulledRows.addAndGet(rows.toLong())
        save()
        val got = inherit()
        try { PipelineHealthCollector.labelInc("HIVE_BOOST_8001") } catch (_: Throwable) {}
        return "Hive brain: ${aggregate.size} shared keys ($rows rows read) — inherited $got"
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
        val now = System.currentTimeMillis()
        val send = pendingRunners.entries.toList()
        if (send.isNotEmpty()) {
            val stmts = send.map { (m, t) ->
                "INSERT INTO hive_runners_8000 (mint, symbol, peak, first_ms, updated_ms, instance) VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(mint) DO UPDATE SET peak = MAX(peak, excluded.peak), updated_ms = excluded.updated_ms" to
                    listOf<Any?>(m, t.first.take(24), t.second, t.third, now, instanceId)
            }
            if (try { client.batch(stmts).all { it.success } } catch (_: Throwable) { false }) {
                send.forEach { pendingRunners.remove(it.key, it.value) }
                runnersSent.addAndGet(send.size.toLong())
            }
        }
        val since = maxOf(lastRunnerPullMs, now - RUNNER_PULL_WINDOW_MS)
        val res = client.query(
            "SELECT mint, symbol, peak, updated_ms FROM hive_runners_8000 WHERE updated_ms > ? AND instance != ? ORDER BY updated_ms DESC LIMIT 50",
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
        "sharedKeys=${aggregate.size} mineUploaded=${uploaded.size} uploadedRows=${uploadedRows.get()} pulledRows=${pulledRows.get()} " +
            "specialistsFromHive=${promotedFromHive.get()} seatsInherited=${seatsInherited.get()} expertsInherited=${expertsInherited.get()} runnerSignals[sent=${runnersSent.get()} pulled=${runnersPulled.get()}] " +
            "books=FRL,SPEC,PB,TAIL,CX,DEV,EXP lastPull=${if (lastPullMs > 0L) "${(System.currentTimeMillis() - lastPullMs) / 1000}s" else "-"}"
}
