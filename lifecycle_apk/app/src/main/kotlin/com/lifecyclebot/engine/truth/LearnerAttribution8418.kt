package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 — LEARNING ATTRIBUTION BEYOND THE CANONICAL DNA PATH.
 *
 * The finalized bus (CanonicalFinalizedTradeBus6464) persists an ACK per REGISTERED consumer, so a registered learner
 * is credited once per tradeId across restarts. Everything else that ingests closes or labels was not:
 *
 *  - publish() calls several learners directly (CellAllocator7962.onClose7962, ExitProfile7955.onClose7955). Its
 *    duplicate check is the in-memory canonicalSeen map, which is empty after a restart, and
 *    FinalizedLearningReconciler7423 then republishes every CLOSED position with a durable terminal sell as
 *    BUS_PUBLISH_FAILED — so those learners were re-taught their whole history after every restart.
 *  - label learners (SpecialistMiner7972, LanePlaybook7907, ForwardOutcomeModel, CellAllocator7962) are reached once
 *    per staged label but a recovered observation (ForwardReturnLabeler7731 8397) can book the same horizon again.
 *  - TacticSwitcher keeps one row per (lane, band, tactic) for both books: a paper close moved the tactic live trades.
 *
 * One ledger per learner: [admit8418] refuses an id the learner already ingested in this process (dupRefused) or in a
 * previous process (replayRefused, from the persisted ledger), refuses a close whose book is not exactly PAPER or LIVE,
 * and counts what it admits per book. Labels are market observations, not trades: they are counted as label=.
 */
object LearnerAttribution8418 {
    enum class Verdict8418 { ADMIT, DUPLICATE, REPLAY, UNKNOWN_BOOK }

    /** Book of an ingest: a realised close is PAPER or LIVE; a forward label is LABEL. Anything else is unknown. */
    private fun bookOf8418(mode: String?): String? = when (mode?.trim()?.uppercase()) {
        "LIVE" -> "LIVE"
        "PAPER" -> "PAPER"
        "LABEL" -> "LABEL"
        else -> null
    }

    /** Pure ledger: ids ingested this process and ids a previous process persisted. Bounded per learner. */
    class Ledger8418(private val capacity: Int = 20_000) {
        private val current = LinkedHashSet<String>()
        private val prior = HashSet<String>()

        @Synchronized fun loadPrior(ids: Collection<String>) { for (id in ids) if (id !in current) prior.add(id) }

        @Synchronized fun admit(id: String): Verdict8418 {
            if (id in current) return Verdict8418.DUPLICATE
            if (id in prior) return Verdict8418.REPLAY
            current.add(id)
            if (current.size > capacity) { val it = current.iterator(); it.next(); it.remove() }
            return Verdict8418.ADMIT
        }

        @Synchronized fun size(): Int = current.size + prior.size
    }

    /** V5.0.8440 — MemoryGuard7977 census: ids held across every learner's ledger. */
    fun idCount8440(): Int = ledgers.values.sumOf { it.size() }

    private class Counts8418 {
        val paper = AtomicLong(0); val live = AtomicLong(0); val label = AtomicLong(0)
        val dup = AtomicLong(0); val replay = AtomicLong(0); val unknownBook = AtomicLong(0); val noId = AtomicLong(0)
    }

    private const val FILE_8418 = "learner_attribution_8418.txt"
    /** V5.0.8440 — newest persisted ids kept per learner (the replay window), and the slack before the file is compacted. */
    internal const val PRIOR_PER_LEARNER_8440 = 10_000
    private const val COMPACT_SLACK_8440 = 20_000

    private val ledgers = ConcurrentHashMap<String, Ledger8418>()
    private val counts = ConcurrentHashMap<String, Counts8418>()
    @Volatile private var loaded = false
    @Volatile internal var persistEnabled8418 = true

    private fun counts(learner: String) = counts.computeIfAbsent(learner) { Counts8418() }

    /**
     * May [learner] ingest the close / label [id] of book [mode] ("LIVE" / "PAPER" / "LABEL")? Once per (learner, id),
     * across restarts. A blank id cannot be deduplicated: it is admitted and counted as noId so the report shows it.
     */
    fun admit8418(learner: String, id: String, mode: String?): Boolean {
        val c = counts(learner)
        val book = bookOf8418(mode)
        if (book == null) { c.unknownBook.incrementAndGet(); return false }
        if (id.isBlank()) { c.noId.incrementAndGet(); bump(c, book); return true }
        ensureLoaded()
        val v = ledgers.computeIfAbsent(learner) { Ledger8418() }.admit(id)
        when (v) {
            Verdict8418.DUPLICATE -> { c.dup.incrementAndGet(); return false }
            Verdict8418.REPLAY -> { c.replay.incrementAndGet(); return false }
            else -> {}
        }
        bump(c, book)
        append(learner, id)
        return true
    }

    /**
     * A learner that already keeps its own durable once-only receipt (Cortex7885 close receipts): its verdict on a [mode]
     * close is counted here so the report shows it beside the others. Changes nothing it decides.
     */
    fun observe8418(learner: String, mode: String?, verdict: Verdict8418) {
        val c = counts(learner)
        when (verdict) {
            Verdict8418.DUPLICATE -> c.dup.incrementAndGet()
            Verdict8418.REPLAY -> c.replay.incrementAndGet()
            Verdict8418.UNKNOWN_BOOK -> c.unknownBook.incrementAndGet()
            Verdict8418.ADMIT -> bookOf8418(mode)?.let { bump(c, it) } ?: c.unknownBook.incrementAndGet()
        }
    }

    /** A learner rebuilt from the trade store on every read (LosingPatternMemory): its last rebuild's per-book totals. */
    fun rebuilt8418(learner: String, paper: Long, live: Long, dup: Long) {
        val c = counts(learner)
        c.paper.set(paper); c.live.set(live); c.dup.set(dup)
    }

    /** The book the runtime trades (PAPER / LIVE). */
    private fun runtimeBook8418(): String? = try {
        if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"
    } catch (_: Throwable) { null }

    /**
     * For a learner with ONE book that the runtime's decisions read (TacticSwitcher, ForwardOutcomeModel,
     * UnifiedExitPolicyHead): a close is admitted only from the runtime's own book, so a paper close never moves what
     * live trades on (and a live close never a paper rehearsal); then once per id like [admit8418].
     */
    fun admitRuntimeBook8418(learner: String, id: String, mode: String?): Boolean =
        admitRuntimeBookOf8418(learner, id, mode, runtimeBook8418())

    /** [admitRuntimeBook8418] with the runtime book [runtime] given (pure apart from the ledger). */
    fun admitRuntimeBookOf8418(learner: String, id: String, mode: String?, runtime: String?): Boolean {
        val book = bookOf8418(mode)
        if (book == null || book == "LABEL" || runtime == null || book != runtime) {
            counts(learner).unknownBook.incrementAndGet()
            return false
        }
        return admit8418(learner, id, mode)
    }

    private fun bump(c: Counts8418, book: String) {
        when (book) { "LIVE" -> c.live.incrementAndGet(); "PAPER" -> c.paper.incrementAndGet(); else -> c.label.incrementAndGet() }
    }

    private fun file(): java.io.File? = try {
        com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, FILE_8418) }
    } catch (_: Throwable) { null }

    @Synchronized private fun ensureLoaded() {
        if (loaded) return
        if (!persistEnabled8418) { loaded = true; return }
        val f = file() ?: return  // no context yet: stay unloaded, retry on the next admit
        loaded = true
        try {
            if (!f.exists()) return
            // V5.0.8440 — streamed, and at most [PRIOR_PER_LEARNER_8440] newest ids kept per learner. 8439 (3.2 h, heap 506/512 MB,
            // one OutOfMemoryError): the 6 MB file was read whole (readLines) and up to 200,000 ids went into unbounded
            // `prior` sets — ~40 MB held for the whole process, ~80 MB at load, none of it in the memory census. A replay
            // can only reach recent ids (the finalized-bus redelivery window), which the newest ids per learner cover.
            val byLearner = HashMap<String, ArrayDeque<String>>()
            var lines8440 = 0
            f.useLines { seq ->
                for (l in seq) {
                    lines8440++
                    val t = l.indexOf('\t')
                    if (t <= 0 || t >= l.length - 1) continue
                    val q = byLearner.getOrPut(l.substring(0, t)) { ArrayDeque() }
                    q.addLast(l.substring(t + 1))
                    if (q.size > PRIOR_PER_LEARNER_8440) q.removeFirst()
                }
            }
            for ((learner, ids) in byLearner) ledgers.computeIfAbsent(learner) { Ledger8418() }.loadPrior(ids)
            // Compact a file that outgrew what is kept (the newest ids per learner are the ones a replay can still reach).
            if (lines8440 > byLearner.values.sumOf { it.size } + COMPACT_SLACK_8440) {
                f.bufferedWriter().use { w -> for ((learner, ids) in byLearner) for (id in ids) { w.write(learner); w.write("\t"); w.write(id); w.write("\n") } }
            }
        } catch (_: Throwable) {}
    }

    @Synchronized private fun append(learner: String, id: String) {
        if (!persistEnabled8418) return
        try { file()?.appendText("$learner\t${id.replace('\n', ' ').replace('\t', ' ')}\n") } catch (_: Throwable) {}
    }

    /** learnerAttribution8418[learner: paper=,live=,dupRefused=,replayRefused=] (+label= / bookRefused= / noId= when non-zero). */
    fun statusLine8418(): String = "learnerAttribution8418[" + counts.entries.sortedBy { it.key }.joinToString(" · ") { (k, c) ->
        val extra = StringBuilder()
        if (c.label.get() > 0L) extra.append(",label=").append(c.label.get())
        if (c.unknownBook.get() > 0L) extra.append(",bookRefused=").append(c.unknownBook.get())
        if (c.noId.get() > 0L) extra.append(",noId=").append(c.noId.get())
        "$k: paper=${c.paper.get()},live=${c.live.get()},dupRefused=${c.dup.get()},replayRefused=${c.replay.get()}$extra"
    }.ifBlank { "-" } + "]"

    /** Test seam: [learner]'s ledger and counts forgotten (its own resetForTest clears its books). */
    internal fun forgetForTest8418(learner: String) { ledgers.remove(learner); counts.remove(learner) }

    /** Test seam: forget this process's ledgers and counts and simulate a restart that loads [prior] per learner. */
    internal fun resetForTest8418(prior: Map<String, Collection<String>> = emptyMap()) {
        ledgers.clear(); counts.clear(); persistEnabled8418 = false; loaded = true
        for ((learner, ids) in prior) ledgers.computeIfAbsent(learner) { Ledger8418() }.loadPrior(ids)
    }
}
