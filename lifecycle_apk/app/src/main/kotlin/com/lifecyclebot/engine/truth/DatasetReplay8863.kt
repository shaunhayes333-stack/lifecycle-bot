package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.JobCost8813
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream

/**
 * V5.0.8863 §ONE_READ_SEEDS_THE_THREE_LEDGERS — owner, on the build that froze at launch: "f8x it bro."
 *
 * Owner's phone on 5.0.8856, 185 s after start (report of 19 Nov 05:24): PocketLedger8610, PayingCell8723 and
 * ManagedBands8719 each replayed the device's own labelled decisions (EdgeModel8537's dataset: 57,955 rows, 80 MB) on its
 * own thread at the same time — pocketLedgerSeed 6.8 s of CPU over 19.3 s, payingCellSeed 6.3 s over 18.0 s,
 * managedBandsSeed 6.7 s over 14.6 s — three readers decoding the same file and building the same JSONObject from the
 * same line, three times, to ask the same question of it (EdgeModel8537.replayAccept8642) and then take a handful of
 * numbers each. That is the first twenty seconds after start, when the dashboard is being built and the first cycle run.
 *
 * Now the file is read once. Each line that carries a horizon and is not refused by its own text
 * (EdgeModel8537.refusedUnparsed8863) is parsed once, asked once, and handed to the three ledgers in turn, in the order
 * the rows were written — the order each ledger replayed them in before. A ledger still finishes seeding before it files
 * a live label: its own worker waits here ([await8863]) and every label queued behind that wait is filed after it.
 *
 * Nothing about what is learned changes: same rows, same acceptance rule, same order, same arithmetic.
 */
object DatasetReplay8863 {

    /** Progress of one consistent generation; a failed seed retries from its last complete durable checkpoint. */
    class Counts8863 {
        @Volatile var lines = 0L
        @Volatile var refusedUnparsed = 0L
        @Volatile var parsed = 0L
        @Volatile var accepted = 0L
    }

    /**
     * Pure: one pass over [lines]. A line without a horizon is not a labelled decision; a line its own text refuses
     * ([refused]) is not parsed; each remaining line is parsed once and, when [accept] takes it, given to every sink in
     * [sinks] order. Legacy callers can skip an individual failing sink; production uses strict generation semantics.
     */
    fun pass8863(
        lines: Sequence<String>,
        accept: (JSONObject) -> Boolean,
        sinks: List<(JSONObject) -> Unit>,
        tally: Counts8863 = Counts8863(),
        refused: (String) -> Boolean = { EdgeModel8537.refusedUnparsed8863(it) },
        strictSinks9043: Boolean = false,
        consumed9043: (String) -> Unit = {},
    ): Counts8863 {
        for (line in lines) {
            JobCost8813.cooperate9043()
            try {
            tally.lines = tally.lines + 1L
            if (!line.contains("\"horizonMs\"")) continue
            if (refused(line)) { tally.refusedUnparsed = tally.refusedUnparsed + 1L; continue }
            val o = try { JSONObject(line) } catch (_: Throwable) { continue }
            tally.parsed = tally.parsed + 1L
            val taken = try { accept(o) } catch (_: Throwable) { false }
            if (!taken) continue
            tally.accepted = tally.accepted + 1L
            for (sink in sinks) {
                try { sink(o) } catch (e: Throwable) { if (strictSinks9043) throw e }
            }
            } finally { consumed9043(line) }
        }
        return tally
    }

    private val gate = Any()
    private var done = false                      // guarded by gate
    private val counts = Counts8863()             // written only by the thread holding gate
    private val attempts = AtomicLong()
    private val failures = AtomicLong()
    private val pendingLive9043 = AtomicLong()
    private var nextSeedAttemptNs9043 = 0L       // guarded by gate; retry pacing after an actual failed seed
    private var consecutiveFailures9043 = 0
    @Volatile internal var readinessForTest9043: (() -> Boolean)? = null
    @Volatile internal var retrySleepForTest9043: Long? = null
    @Volatile private var state = "not run"
    @Volatile private var wallMs = 0L
    @Volatile private var restoredLines9043 = 0L
    @Volatile private var tailLines9043 = 0L
    @Volatile private var checkpointState9043 = "not read"
    private var offset9043 = 0L

    private val sinks: List<(JSONObject) -> Unit> = listOf(
        { o -> PocketLedger8610.seedRow8863(o) },
        { o -> PayingCell8723.seedRow8863(o) },
        { o -> ManagedBands8719.seedRow8863(o) },
        { o -> AgreementPocket8874.seedRow8863(o) },   // V5.0.8874 — the agreement pocket's per-lane records
        { o -> LlmBands8875.seedRow8863(o) },   // V5.0.8875 — the analyst's answer bands (rows from 8875 on carry "llmP")
    )

    /**
     * Each ledger's worker: returns when the one replay has finished (true) or its read failed (false: the ledger's loader
     * asks again on its next call, rebuilding a failed generation from its durable checkpoint and replaying its tail).
     * The first worker to ask runs the pass on its own thread; the others wait for it. Live writes use afterSeed9043.
     */
    fun await8863(): Boolean = synchronized(gate) {
        if (done) true
        else if (nextSeedAttemptNs9043 != 0L && System.nanoTime() - nextSeedAttemptNs9043 < 0L) false
        else {
            attempts.incrementAndGet()
            val ok = try { replay(); true } catch (e: Throwable) {
                checkpointState9043 = "seed failed:${e.javaClass.simpleName}"
                false
            }
            if (ok) {
                done = true; consecutiveFailures9043 = 0; nextSeedAttemptNs9043 = 0L
            } else {
                failures.incrementAndGet(); state = "read failed"
                consecutiveFailures9043 = (consecutiveFailures9043 + 1).coerceAtMost(6)
                val retryMs = (1_000L shl (consecutiveFailures9043 - 1)).coerceAtMost(30_000L)
                nextSeedAttemptNs9043 = System.nanoTime() + retryMs * 1_000_000L
            }
            ok
        }
    }

    /**
     * Existing ledger workers retain a queued live update until a complete seed generation is ready. A failed
     * loader cannot let its next queued add race another ledger's clear/rebuild. The readiness check and mutation
     * share the seed owner lock, including when a caller queued its update before its loader was enqueued.
     * Only readiness is retried: a throwing mutation is never replayed after a possible partial application.
     */
    internal fun afterSeed9043(update: () -> Unit) {
        pendingLive9043.incrementAndGet()
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("live label seed wait cancelled")
                synchronized(gate) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("live label owner wait cancelled")
                    if (readinessForTest9043?.invoke() ?: await8863()) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("live label readiness cancelled")
                        update()
                        return
                    }
                }
                // Outside the shared gate, on the same existing background worker. No entry/exit caller waits here.
                Thread.sleep(retrySleepForTest9043 ?: 1_000L)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } finally { pendingLive9043.decrementAndGet() }
    }

    private fun replay() {
        val file = EdgeModel8537.datasetFile()
        if (file == null) { state = "no dataset"; return }   // nothing labelled yet: the ledgers start from live labels
        val t0 = System.nanoTime()
        JobCost8813.measureYielding8866("datasetSeed") {
            // Restore all five derived ledgers and their deduplication state as one generation. A failed attempt is
            // reconstructed from the last durable checkpoint, never resumed after a partly applied multi-sink row.
            clearSeed9043()
            val checkpoint = File(file.parentFile, "decision_seed_9043.bin")
            // Legacy rows have no decision-time stop. Their explicit fallback must not change invisibly merely
            // because a cached replay is available; invalidate that cache when the fallback policy changes.
            val legacyStops = EdgeModel8537.LANES.sorted().joinToString(",") { lane ->
                val stop = try { FieldManual7715.laneStopPct8392(lane) } catch (_: Throwable) { Double.NaN }
                "$lane:${FrozenEdgeTarget9043.effectiveStop9043(stop)}"
            }
            val policy = "edge=${EdgeModel8537.MODEL_EPOCH_8642};venue=${EdgeModel8537.VENUE_SCOPE_EPOCH_8660};epoch=${LearningEpoch8164.epochMs8164()};target=9043;legacyStops=$legacyStops"
            try {
                val restored = DatasetSeedCheckpoint9043.restore(checkpoint, file, policy) { input ->
                    PocketLedger8610.readSeed9043(input); PayingCell8723.readSeed9043(input)
                    ManagedBands8719.readSeed9043(input); AgreementPocket8874.readSeed9043(input); LlmBands8875.readSeed9043(input)
                }
                if (restored != null) {
                    offset9043 = restored.offset; counts.lines = restored.lines; counts.refusedUnparsed = restored.refused
                    counts.parsed = restored.parsed; counts.accepted = restored.accepted
                    restoredLines9043 = restored.lines; checkpointState9043 = "restored"
                } else checkpointState9043 = "cold"
            } catch (e: Exception) {
                clearSeed9043(); checkpointState9043 = "rebuild:${e.javaClass.simpleName}"
                if (e is InterruptedException) { Thread.currentThread().interrupt(); throw e }
            }
            val end = completePrefix9043(file)
            val before = counts.lines
            FileInputStream(file).use { raw ->
                raw.channel.position(offset9043)
                val bounded = object : FilterInputStream(raw) {
                    var remaining = end - offset9043
                    override fun read(): Int {
                        if (remaining <= 0L) return -1
                        return super.read().also { if (it >= 0) remaining-- }
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining <= 0L) return -1
                        return `in`.read(b, off, minOf(len.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
                    }
                }
                bounded.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    pass8863(seq, { o -> EdgeModel8537.replayAccept8642(o) }, sinks, counts,
                        strictSinks9043 = true,
                        consumed9043 = { line -> offset9043 += line.toByteArray(Charsets.UTF_8).size + 1L })
                }
            }
            require(offset9043 == end) { "dataset line boundary changed" }
            tailLines9043 = counts.lines - before
            ManagedBands8719.seedDone8863()
            try {
                DatasetSeedCheckpoint9043.save(checkpoint, file, policy,
                    DatasetSeedCheckpoint9043.Cursor(offset9043, counts.lines, counts.refusedUnparsed, counts.parsed, counts.accepted)) { out ->
                    PocketLedger8610.writeSeed9043(out); PayingCell8723.writeSeed9043(out)
                    ManagedBands8719.writeSeed9043(out); AgreementPocket8874.writeSeed9043(out); LlmBands8875.writeSeed9043(out)
                }
                checkpointState9043 += "/saved"
            } catch (e: Exception) {
                checkpointState9043 += "/saveFailed:${e.javaClass.simpleName}"
                if (e is InterruptedException) { Thread.currentThread().interrupt(); throw e }
            }
        }
        wallMs += (System.nanoTime() - t0) / 1_000_000L
        state = "done"
        try { PipelineHealthCollector.labelInc("POCKET_8610_SEEDED_FROM_DATASET") } catch (_: Throwable) {}
    }

    internal fun completePrefix9043(file: File): Long = DatasetSeedCheckpoint9043.completePrefix(file)

    private fun clearSeed9043() {
        PocketLedger8610.clearSeed9043(); PayingCell8723.clearSeed9043(); ManagedBands8719.clearSeed9043()
        AgreementPocket8874.clearSeed9043(); LlmBands8875.clearSeed9043()
        counts.lines = 0L; counts.refusedUnparsed = 0L; counts.parsed = 0L; counts.accepted = 0L
        offset9043 = 0L; restoredLines9043 = 0L; tailLines9043 = 0L
    }

    fun statusLine8863(): String =
        "datasetSeed8863[$state lines=${counts.lines} refusedUnparsed=${counts.refusedUnparsed} parsed=${counts.parsed} " +
            "accepted=${counts.accepted} ledgers=${sinks.size} reads=${attempts.get()} failed=${failures.get()} wallMs=$wallMs " +
            "checkpoint9043=$checkpointState9043 restoredLines=$restoredLines9043 tailLines=$tailLines9043 " +
            "pendingLive9043=${pendingLive9043.get()} " +
            "rule=one read of the labelled dataset seeds the pocket ledger, paying cells, managed bands, the agreement pocket (8874) and the analyst's answer bands (8875); a row its own text refuses is not parsed]"

    internal fun resetForTest8863() {
        synchronized(gate) {
            done = false; counts.lines = 0L; counts.refusedUnparsed = 0L; counts.parsed = 0L; counts.accepted = 0L
            nextSeedAttemptNs9043 = 0L; consecutiveFailures9043 = 0
            readinessForTest9043 = null; retrySleepForTest9043 = null
        }
        attempts.set(0L); failures.set(0L); state = "not run"; wallMs = 0L
    }
}
