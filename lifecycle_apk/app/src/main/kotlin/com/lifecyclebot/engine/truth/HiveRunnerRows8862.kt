package com.lifecyclebot.engine.truth

import com.lifecyclebot.AATEApp
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8862 §WHAT_THE_OTHER_PHONES_SAW — owner: "do all 5" (item 4: the hive, both ways).
 *
 * The hive (HiveEdge8000) already goes both ways for the books it was built with: forward-label cells, specialists,
 * playbook setups, tail ladders, Cortex seats, dev reputations and expert wallets are uploaded as deltas and read back as
 * "the rest of the network". 5.0.8856 read `sharedKeys=93916 mineUploaded=76876 specialistsFromHive=0 seatsInherited=0
 * runnerSignals[sent=0 pulled=0]`, and nothing on the phone could say whether ANY other install was writing to it: 82 %
 * of the keys were its own. Two things were missing.
 *
 * 1. THE CENSUS. Each install marks itself in [INSTALLS_TABLE_8862] on every sync; the count of other installs seen in the
 *    last seven days is on the report line. With none, the hive is this phone's own memory (it does outlive a learning
 *    reset) and there is nothing else to learn from yet; that is now a number, not a guess.
 * 2. THE RUNNER MODEL'S ROWS. The hive's books are sums, and a sum is the wrong way to pool a fact about one launch: two
 *    phones that both saw it would count it twice (which is why wallet and creator records are not pooled here). A row
 *    of RunnerModel8861 is keyed by the launch's mint, so the network's rows are a UNION: the first install to grade a
 *    launch writes its row ([ROWS_TABLE_8862], INSERT OR IGNORE), every other install takes the rows of launches its own
 *    tape did not hold. N installs fill the training set N times as fast, with nothing counted twice.
 *    A row from another install TEACHES the model; it never enters this install's forward record, so nothing another
 *    install writes can arm the model here — only what this phone's own tape graded can.
 */
object HiveRunnerRows8862 {
    /** The rows are RunnerModel8861.FEATURES_8861 in order; a different feature list takes a different table. */
    internal const val ROWS_TABLE_8862 = "hive_runner_rows_8861"
    internal const val INSTALLS_TABLE_8862 = "hive_installs_8862"
    private const val SEND_BATCH = 60
    private const val PULL_LIMIT = 2_000
    private const val ACTIVE_MS = 7L * 24L * 3_600_000L
    /** A launch's row is written four hours after its birth and the tape lasts 48: nothing older than this is a row any install could have just graded. */
    internal const val MAX_ROW_AGE_MS_8862 = 400L * 24L * 3_600_000L
    /** A position cannot lose more than itself: a return below -100 % less any cost a row could carry is not a graded trade. */
    internal const val NET_FLOOR_PCT_8862 = -200.0
    private const val FILE = "runner_hive_8862.txt"

    @Volatile private var tablesReady = false
    private var loaded = false
    private var sharedToBornMs = 0L
    private var pullMs = 0L
    @Volatile private var otherInstalls = -1
    private val sent = AtomicLong(); private val pulled = AtomicLong(); private val taken = AtomicLong(); private val refused = AtomicLong()
    private val syncs = AtomicLong(); private val failed = AtomicLong()

    /** Pure: a row's readings as text (a reading that could not be made stays "NaN"). */
    fun encodeX8862(x: DoubleArray): String = x.joinToString(",") { if (it.isFinite()) it.toString() else "NaN" }

    /** Pure: the inverse of [encodeX8862] for a row of [n] readings; null when it is not one. */
    fun decodeX8862(s: String?, n: Int): DoubleArray? {
        val parts = s?.split(',') ?: return null
        if (parts.size != n) return null
        val out = DoubleArray(n)
        for (i in 0 until n) out[i] = parts[i].trim().let { if (it == "NaN") Double.NaN else it.toDoubleOrNull() ?: return null }
        return out
    }

    /**
     * Pure: one pulled database row as a model row another install graded, or null when it is not a usable one (wrong
     * shape, no return, a birth that cannot be a launch some install just graded).
     */
    fun rowOf8862(row: Map<String, Any?>, nowMs: Long): RunnerModel8861.Row? {
        fun num(k: String): Double? = row[k]?.toString()?.toDoubleOrNull()
        val mint = row["mint"]?.toString().orEmpty()
        val born = num("born")?.toLong() ?: return null
        val net = num("net") ?: return null
        val x = decodeX8862(row["x"]?.toString(), RunnerModel8861.FEATURES_8861.size) ?: return null
        if (mint.length !in 32..44 || !net.isFinite() || net < NET_FLOOR_PCT_8862 || born <= 0L || born > nowMs || nowMs - born > MAX_ROW_AGE_MS_8862) return null
        return RunnerModel8861.Row(mint, born, x, (num("doubled") ?: 0.0) >= 0.5, net, (num("past") ?: 0.0) >= 0.5, mine = false)
    }

    private fun file(): File? = AATEApp.appContextOrNull()?.filesDir?.let { File(it, FILE) }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val p = file()?.takeIf { it.isFile }?.readText()?.trim()?.split('|') ?: return
            sharedToBornMs = p.getOrNull(0)?.toLongOrNull() ?: 0L; pullMs = p.getOrNull(1)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {}
    }

    private fun save() { try { file()?.writeText("$sharedToBornMs|$pullMs") } catch (_: Throwable) {} }

    private suspend fun ensureTables(client: com.lifecyclebot.collective.TursoClient) {
        if (tablesReady) return
        client.execute("CREATE TABLE IF NOT EXISTS $ROWS_TABLE_8862 (mint TEXT PRIMARY KEY, born INTEGER, x TEXT, doubled INTEGER, net REAL, past INTEGER, updated_ms INTEGER, instance TEXT, build TEXT)")
        client.execute("CREATE INDEX IF NOT EXISTS ${ROWS_TABLE_8862}_upd ON $ROWS_TABLE_8862(updated_ms)")
        client.execute("CREATE TABLE IF NOT EXISTS $INSTALLS_TABLE_8862 (instance TEXT PRIMARY KEY, build TEXT, updated_ms INTEGER)")
        tablesReady = true
    }

    @Synchronized
    private fun marks(): Pair<Long, Long> { load(); return sharedToBornMs to pullMs }

    /** HiveEdge8000.sync8000 (every ~10 minutes): mark this install, send its own new rows, take the rows of launches it did not grade. */
    suspend fun sync8862(client: com.lifecyclebot.collective.TursoClient, instanceId: String, nowMs: Long = System.currentTimeMillis()) {
        if (instanceId.isBlank()) return
        syncs.incrementAndGet()
        try {
            ensureTables(client)
            val build = try { com.lifecyclebot.BuildConfig.VERSION_NAME } catch (_: Throwable) { "?" }
            client.execute("INSERT INTO $INSTALLS_TABLE_8862 (instance, build, updated_ms) VALUES (?, ?, ?) " +
                "ON CONFLICT(instance) DO UPDATE SET build = excluded.build, updated_ms = excluded.updated_ms", listOf<Any?>(instanceId, build, nowMs))
            val census = client.query("SELECT COUNT(*) AS n FROM $INSTALLS_TABLE_8862 WHERE updated_ms > ? AND instance != ?", listOf<Any?>(nowMs - ACTIVE_MS, instanceId))
            if (census.success) otherInstalls = census.rows.firstOrNull()?.get("n")?.toString()?.toDoubleOrNull()?.toInt() ?: otherInstalls
            var (sharedTo, lastPull) = marks()
            // this install's own rows, oldest first; the first install to grade a launch keeps its row
            val own = RunnerModel8861.ownRowsAfter8862(sharedTo, SEND_BATCH * 5)
            for (chunk in own.chunked(SEND_BATCH)) {
                val stmts = chunk.map { r ->
                    "INSERT OR IGNORE INTO $ROWS_TABLE_8862 (mint, born, x, doubled, net, past, updated_ms, instance, build) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)" to
                        listOf<Any?>(r.mint, r.bornMs, encodeX8862(r.x), if (r.doubled) 1 else 0, r.net, if (r.pastHour) 1 else 0, nowMs, instanceId, build)
                }
                if (!(try { client.batch(stmts).all { it.success } } catch (_: Throwable) { false })) break
                sent.addAndGet(chunk.size.toLong()); sharedTo = chunk.last().bornMs
            }
            // the rows of launches another install graded
            val res = client.query("SELECT mint, born, x, doubled, net, past, updated_ms FROM $ROWS_TABLE_8862 WHERE updated_ms > ? AND instance != ? ORDER BY updated_ms LIMIT $PULL_LIMIT",
                listOf<Any?>(lastPull, instanceId))
            if (res.success) {
                val got = ArrayList<RunnerModel8861.Row>(res.rows.size)
                for (row in res.rows) {
                    lastPull = maxOf(lastPull, row["updated_ms"]?.toString()?.toDoubleOrNull()?.toLong() ?: 0L)
                    val r = rowOf8862(row, nowMs)
                    if (r == null) refused.incrementAndGet() else got.add(r)
                }
                pulled.addAndGet(res.rows.size.toLong())
                taken.addAndGet(RunnerModel8861.offerHive8862(got).toLong())
            }
            synchronized(this) { sharedToBornMs = sharedTo; pullMs = lastPull; save() }
        } catch (_: Throwable) { failed.incrementAndGet() }
    }

    fun statusLine8862(): String = "hiveRunnerRows8862[otherInstalls7d=${if (otherInstalls < 0) "unread" else otherInstalls.toString()} " +
        "rows{sent=${sent.get()} pulled=${pulled.get()} taken=${taken.get()} refused=${refused.get()}} syncs=${syncs.get()} failed=${failed.get()} " +
        "rule=a launch's row is written once by the first install to grade it; other installs' rows teach the model, only this install's own rows prove it]"
}
