package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.JobCost8813
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

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

    /** What the pass has done so far. Kept across a failed read so the retry resumes after the lines already offered. */
    class Counts8863 {
        @Volatile var lines = 0L
        @Volatile var refusedUnparsed = 0L
        @Volatile var parsed = 0L
        @Volatile var accepted = 0L
    }

    /**
     * Pure: one pass over [lines]. A line without a horizon is not a labelled decision; a line its own text refuses
     * ([refused]) is not parsed; each remaining line is parsed once and, when [accept] takes it, given to every sink in
     * [sinks] order. A sink that throws loses that row for itself only, as each ledger's own replay did.
     */
    fun pass8863(
        lines: Sequence<String>,
        accept: (JSONObject) -> Boolean,
        sinks: List<(JSONObject) -> Unit>,
        tally: Counts8863 = Counts8863(),
        refused: (String) -> Boolean = { EdgeModel8537.refusedUnparsed8863(it) },
    ): Counts8863 {
        for (line in lines) {
            tally.lines = tally.lines + 1L
            if (!line.contains("\"horizonMs\"")) continue
            if (refused(line)) { tally.refusedUnparsed = tally.refusedUnparsed + 1L; continue }
            val o = try { JSONObject(line) } catch (_: Throwable) { continue }
            tally.parsed = tally.parsed + 1L
            val taken = try { accept(o) } catch (_: Throwable) { false }
            if (!taken) continue
            tally.accepted = tally.accepted + 1L
            for (sink in sinks) {
                try { sink(o) } catch (_: Throwable) {}
            }
        }
        return tally
    }

    private val gate = Any()
    private var done = false                      // guarded by gate
    private val counts = Counts8863()             // written only by the thread holding gate
    private val attempts = AtomicLong()
    private val failures = AtomicLong()
    @Volatile private var state = "not run"
    @Volatile private var wallMs = 0L

    private val sinks: List<(JSONObject) -> Unit> = listOf(
        { o -> PocketLedger8610.seedRow8863(o) },
        { o -> PayingCell8723.seedRow8863(o) },
        { o -> ManagedBands8719.seedRow8863(o) },
    )

    /**
     * Each ledger's worker: returns when the one replay has finished (true) or its read failed (false: the ledger's loader
     * asks again on its next call, and the pass resumes after the lines it had already offered, so no row is filed twice).
     * The first worker to ask runs the pass on its own thread; the others wait for it.
     */
    fun await8863(): Boolean = synchronized(gate) {
        if (done) true
        else {
            attempts.incrementAndGet()
            val ok = try { replay(); true } catch (_: Throwable) { false }
            if (ok) done = true else { failures.incrementAndGet(); state = "read failed" }
            ok
        }
    }

    private fun replay() {
        val file = EdgeModel8537.datasetFile()
        if (file == null) { state = "no dataset"; return }   // nothing labelled yet: the ledgers start from live labels
        val t0 = System.currentTimeMillis()
        JobCost8813.measureBackground8817("datasetSeed") {
            val resumeAfter = counts.lines
            var index = 0L
            file.useLines { seq ->
                pass8863(seq.filter { index++ >= resumeAfter }, { o -> EdgeModel8537.replayAccept8642(o) }, sinks, counts)
            }
        }
        ManagedBands8719.seedDone8863()
        wallMs += System.currentTimeMillis() - t0
        state = "done"
        try { PipelineHealthCollector.labelInc("POCKET_8610_SEEDED_FROM_DATASET") } catch (_: Throwable) {}
    }

    fun statusLine8863(): String =
        "datasetSeed8863[$state lines=${counts.lines} refusedUnparsed=${counts.refusedUnparsed} parsed=${counts.parsed} " +
            "accepted=${counts.accepted} ledgers=${sinks.size} reads=${attempts.get()} failed=${failures.get()} wallMs=$wallMs " +
            "rule=one read of the labelled dataset seeds the pocket ledger, paying cells and managed bands; a row its own text refuses is not parsed]"

    internal fun resetForTest8863() {
        synchronized(gate) { done = false; counts.lines = 0L; counts.refusedUnparsed = 0L; counts.parsed = 0L; counts.accepted = 0L }
        attempts.set(0L); failures.set(0L); state = "not run"; wallMs = 0L
    }
}
