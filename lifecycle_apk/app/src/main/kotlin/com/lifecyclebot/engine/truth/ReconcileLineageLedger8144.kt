package com.lifecyclebot.engine.truth

import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8144 — DURABLE BIRTH RECORD OF EVERY LIVE CANONICAL LOT (lineage at the source).
 *
 * Root cause of the orphan-sell classes on 5.0.8142:
 *  - RESTART: CanonicalPositionAuthority6441 is memory-only and the boot rebuild replays PAPER
 *    events only. A LIVE position closed before a restart vanishes from closedPositions(), so its
 *    SELL row reads parentless whenever its BUY row is outside the bounded journal window.
 *  - ADOPTION / PROMOTION: LiveCanonicalRecovery6686 wallet adoption and the pending-entry promotion
 *    open a canonical lot without any durable economic Buy event (only the executor fill path writes
 *    one), so after a restart the lot's sells have no parent anywhere.
 *
 * Every LIVE lot that becomes OPEN (direct open or pending promotion) now writes one immutable birth
 * record here: positionId, mint, origin, original raw quantity, entry cost and basis source. First
 * write wins (the birth basis never changes). The file is append-only and is a protected evidence
 * store: a learning reset never touches it (ReconcileEvidenceStores8144).
 *
 * Persistence is injected ([install8144]) by ReconcileRunner8144 at runtime, so JVM tests and the
 * canonical authority's own tests run memory-only with no Android dependency.
 */
object ReconcileLineageLedger8144 {
    const val FILE_NAME_8144 = "reconcile_lineage_8144.ndjson"
    private const val MAX_RECORDS_8144 = 50_000
    private const val MAX_PENDING_LINES_8144 = 4_000

    enum class Origin8144 { EXECUTOR_FILL, PENDING_PROMOTION, WALLET_RECOVERY, OBSERVED_MARK_ADOPTION }

    data class Record8144(
        val positionId: String,
        val mint: String,
        val origin: String,
        val atMs: Long,
        val originalRaw: BigInteger,
        val entryCostSol: Double,
        val entrySource: String,
    )

    private val records = ConcurrentHashMap<String, Record8144>()
    private val pendingLines = ConcurrentLinkedQueue<String>()
    @Volatile private var file8144: java.io.File? = null
    @Volatile private var loaded8144 = false
    private val written = AtomicLong(0)
    private val loadedN = AtomicLong(0)
    private val ioExecutor8144 by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "reconcile-lineage-8144").apply { isDaemon = true } }
    }

    /** Pure: which path gave birth to the lot. */
    fun originOf8144(idempotencyKey: String, runId: String, entrySource: String, promoted: Boolean): Origin8144 = when {
        entrySource.contains("OBSERVED_MARK", true) -> Origin8144.OBSERVED_MARK_ADOPTION
        runId.contains("RECOVERY", true) || idempotencyKey.startsWith("LIVE_WALLET_CANONICAL_RECOVERY", true) -> Origin8144.WALLET_RECOVERY
        promoted -> Origin8144.PENDING_PROMOTION
        else -> Origin8144.EXECUTOR_FILL
    }

    /** Pure codec — '|' never appears in a mint, a positionId or a basis tag; it is replaced defensively. */
    fun encode8144(r: Record8144): String = listOf(
        r.positionId, r.mint, r.origin, r.atMs.toString(), r.originalRaw.toString(), r.entryCostSol.toString(), r.entrySource,
    ).joinToString("|") { it.replace("|", "/").replace("\n", " ") }

    fun decode8144(line: String): Record8144? = try {
        val f = line.split('|')
        if (f.size < 7 || f[0].isBlank() || f[1].isBlank()) null
        else Record8144(f[0], f[1], f[2], f[3].toLong(), BigInteger(f[4]), f[5].toDouble(), f[6])
    } catch (_: Throwable) { null }

    /**
     * CanonicalPositionAuthority6441: a LIVE lot just became OPEN. Cheap (map put + async append); safe under
     * the authority's lock. Returns true when this is the lot's first (birth) record.
     */
    fun note8144(
        positionId: String, mint: String, mode: String, originalRaw: BigInteger, entryCostSol: Double,
        entrySource: String, runId: String, idempotencyKey: String, promoted: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!mode.equals("live", true) || positionId.isBlank() || mint.isBlank() || originalRaw.signum() <= 0) return false
        if (records.size >= MAX_RECORDS_8144) return false
        val rec = Record8144(positionId, mint, originOf8144(idempotencyKey, runId, entrySource, promoted).name, nowMs,
            originalRaw, if (entryCostSol.isFinite()) entryCostSol else 0.0, entrySource.take(48))
        if (records.putIfAbsent(positionId, rec) != null) return false
        val line = encode8144(rec)
        val f = file8144
        if (f == null) {
            if (pendingLines.size < MAX_PENDING_LINES_8144) pendingLines.add(line)
        } else {
            ioExecutor8144.execute { append8144(f, listOf(line)) }
        }
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("RECONCILE_LINEAGE_BIRTH_8144_${rec.origin}") } catch (_: Throwable) {}
        return true
    }

    /** Runtime: bind the durable file, load earlier births, flush births noted before the bind. Call off the main thread. */
    @Synchronized
    fun install8144(f: java.io.File) {
        if (loaded8144) return
        try {
            if (f.exists()) f.readLines(Charsets.UTF_8).forEach { l ->
                decode8144(l)?.let { r -> if (records.putIfAbsent(r.positionId, r) == null) loadedN.incrementAndGet() }
            }
        } catch (_: Throwable) {}
        file8144 = f
        loaded8144 = true
        val drained = ArrayList<String>()
        var next = pendingLines.poll()
        while (next != null) { drained.add(next); next = pendingLines.poll() }
        if (drained.isNotEmpty()) append8144(f, drained)
    }

    private fun append8144(f: java.io.File, lines: List<String>) {
        try {
            java.io.FileOutputStream(f, true).use { out -> lines.forEach { out.write((it + "\n").toByteArray(Charsets.UTF_8)) } }
            written.addAndGet(lines.size.toLong())
        } catch (_: Throwable) {}
    }

    fun snapshot8144(): List<Record8144> = records.values.toList()
    fun mints8144(): Set<String> = records.values.mapTo(HashSet()) { it.mint }
    fun hasLineage8144(mint: String): Boolean = mint.isNotBlank() && records.values.any { it.mint == mint }
    fun recordFor8144(positionId: String): Record8144? = records[positionId]

    fun statusLine8144(): String {
        val byOrigin = records.values.groupingBy { it.origin }.eachCount().entries.joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "none" }
        return "lineage8144 records=${records.size} [$byOrigin] loaded=${loadedN.get()} written=${written.get()} durable=$loaded8144 pending=${pendingLines.size}"
    }

    internal fun resetForTest8144() { records.clear(); pendingLines.clear(); file8144 = null; loaded8144 = false }
}

/**
 * V5.0.8144 — stores that hold confirmed transaction evidence. A learning reset (LearningResetSweep7781)
 * never clears, deletes or rewrites any of them: the reset relearns edges, it does not forget fills.
 */
object ReconcileEvidenceStores8144 {
    /** SharedPreferences files holding fills, signatures, economic events or wallet lineage. */
    val PROTECTED_PREFS_8144: Set<String> = setOf(
        "fill_lot_ledger_6344",
        "canonical_economic_events_6486",
        "aate_signed_tx_8085",
    )
    /** Databases holding the trade journal. */
    val PROTECTED_DBS_8144: Set<String> = setOf("trade_history.db")
    /** Files holding economic events, lineage and the host wallet tracker. */
    val PROTECTED_FILES_8144: Set<String> = setOf(
        "economic_events_6486.ndjson",
        ReconcileLineageLedger8144.FILE_NAME_8144,
        "host_wallet_token_tracker.json",
    )

    /** Pure: the evidence stores a reset plan would touch (must always be empty). */
    fun violations8144(prefs: Collection<String>, dbs: Collection<String>, files: Collection<String>): List<String> =
        prefs.filter { it in PROTECTED_PREFS_8144 } + dbs.filter { it in PROTECTED_DBS_8144 } + files.filter { it in PROTECTED_FILES_8144 }

    /** Pure: may the reset clear this store? */
    fun resettable8144(name: String): Boolean =
        name !in PROTECTED_PREFS_8144 && name !in PROTECTED_DBS_8144 && name !in PROTECTED_FILES_8144
}
