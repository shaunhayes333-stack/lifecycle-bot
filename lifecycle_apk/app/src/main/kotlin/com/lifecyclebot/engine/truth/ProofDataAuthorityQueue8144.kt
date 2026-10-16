package com.lifecyclebot.engine.truth

/**
 * V5.0.8144 — the authority-proof work queue as an explicit per-mint state machine.
 *
 * 8143 device: three live buys refused PRETRADE_HARD_BLOCK_FREEZE_AUTHORITY_UNVERIFIED_7238. The 8136 prefetch ran on a
 * ThreadPoolExecutor with a 64-slot queue and DiscardPolicy: on saturation execute() returned normally, the job was
 * dropped, `submitted` still counted it, and the retry ladder believed an ask was on the wire. The gate then asked
 * synchronously, cold, and refused. Nothing said the queue had rejected the proof.
 *
 *   UNREQUESTED      never asked.
 *   QUEUED           accepted into a lane (LIVE or BACKGROUND), waiting for a worker.
 *   RUNNING          a worker is asking the chain.
 *   PROVEN_CLEAN     the chain answered clean (terminal until the proof's own TTL makes it STALE).
 *   PROVEN_HOSTILE   the chain answered hostile (terminal until its TTL).
 *   UNANSWERED       every ask this window went unanswered: named terminal until the window reopens.
 *   QUEUE_REJECTED   the lane was full. Never counted as submitted; a bounded retry is due at [Slot8144.retryAtMs]
 *                    while rejections remain, otherwise a named terminal until the window reopens.
 *   RETRY_SCHEDULED  unanswered (or the gate asked and nobody answered) with attempts left: due at [Slot8144.retryAtMs].
 *
 * Two lanes: an already-qualified live candidate (gate / FDG callers) is always served before speculative background
 * enrichment, and a background request never takes live capacity. A queued background mint asked for by a live caller
 * is promoted. One mint is never queued or running twice (single flight). No state here is ever an approval: only
 * AuthorityProofState8136's fresh PROVEN_CLEAN record is executable, and this book never writes that record.
 *
 * Pure JVM, synchronized, bounded.
 */
class ProofDataAuthorityQueue8144(
    private val liveCap: Int = LIVE_CAP_8144,
    private val backgroundCap: Int = BACKGROUND_CAP_8144,
    private val windowMs: Long = AuthorityProofState8136.RETRY_WINDOW_MS_8136,
    private val maxAttempts: Int = AuthorityProofState8136.MAX_ATTEMPTS_PER_WINDOW_8136,
    private val maxRejects: Int = MAX_REJECTS_PER_WINDOW_8144,
) {
    enum class Flight8144 { UNREQUESTED, QUEUED, RUNNING, PROVEN_CLEAN, PROVEN_HOSTILE, UNANSWERED, QUEUE_REJECTED, RETRY_SCHEDULED }

    enum class Priority8144 { LIVE, BACKGROUND }

    enum class Submit8144 { ACCEPTED, PROMOTED, ALREADY_QUEUED, ALREADY_RUNNING, BACKOFF, EXHAUSTED, QUEUE_REJECTED }

    enum class Answer8144 { CLEAN, HOSTILE, UNANSWERED }

    data class Slot8144(
        val state: Flight8144,
        val priority: Priority8144,
        /** Chain asks made this window (worker or gate), answered or not. */
        val attempts: Int,
        /** Times a lane was full for this mint this window. */
        val rejects: Int,
        val windowStartMs: Long,
        val lastAtMs: Long,
        /** RETRY_SCHEDULED / QUEUE_REJECTED: when the next bounded retry is due. */
        val retryAtMs: Long,
    )

    data class Taken8144(val mint: String, val priority: Priority8144)

    private val slots = HashMap<String, Slot8144>()
    private val live = ArrayDeque<String>()
    private val background = ArrayDeque<String>()

    /** Pure: wait before the next ask after [attempts] unanswered asks (same rungs as AuthorityProofState8136.retryDue8136). */
    fun retryDelayMs8144(attempts: Int): Long = RETRY_LADDER_MS_8144[attempts.coerceIn(0, RETRY_LADDER_MS_8144.lastIndex)]

    /** Pure: wait before re-offering a mint whose lane was full for the [rejects]th time. */
    fun rejectDelayMs8144(rejects: Int): Long = REJECT_LADDER_MS_8144[(rejects - 1).coerceIn(0, REJECT_LADDER_MS_8144.lastIndex)]

    private fun windowed(s: Slot8144, nowMs: Long): Slot8144 =
        if (nowMs - s.windowStartMs >= windowMs) s.copy(attempts = 0, rejects = 0, windowStartMs = nowMs) else s

    /**
     * Attempts allowed this window: a qualified live candidate gets the full ladder; speculative background enrichment
     * gets [BACKGROUND_MAX_ATTEMPTS_8144], so scans cannot multiply chain reads on providers the live gate needs.
     */
    private fun attemptCap(s: Slot8144): Int =
        if (s.priority == Priority8144.LIVE) maxAttempts else minOf(maxAttempts, BACKGROUND_MAX_ATTEMPTS_8144)

    /** Wait before the next ask; background never retries inside the proof's 20 s negative-cache window. */
    private fun retryWait(s: Slot8144): Long =
        if (s.priority == Priority8144.LIVE) retryDelayMs8144(s.attempts) else maxOf(retryDelayMs8144(s.attempts), BACKGROUND_MIN_RETRY_MS_8144)

    private fun exhausted(s: Slot8144): Boolean = s.attempts >= attemptCap(s) || s.rejects >= maxRejects

    private fun terminalFor(s: Slot8144): Flight8144 = if (s.rejects >= maxRejects) Flight8144.QUEUE_REJECTED else Flight8144.UNANSWERED

    private fun maxPriority(a: Priority8144, b: Priority8144): Priority8144 =
        if (a == Priority8144.LIVE || b == Priority8144.LIVE) Priority8144.LIVE else Priority8144.BACKGROUND

    /**
     * Ask for [mint]'s proof at [priority]. ACCEPTED is the only outcome that put new work in a lane; QUEUE_REJECTED means
     * the lane was full (a bounded retry is scheduled); EXHAUSTED means the window's attempts or rejections are spent.
     */
    @Synchronized
    fun request8144(mint: String, priority: Priority8144, nowMs: Long): Submit8144 {
        if (mint.isBlank()) return Submit8144.EXHAUSTED
        val s = slots[mint]?.let { windowed(it, nowMs) }
        if (s != null && s.state == Flight8144.QUEUED) {
            if (priority == Priority8144.LIVE && s.priority == Priority8144.BACKGROUND && live.size < liveCap) {
                background.remove(mint)
                live.addLast(mint)
                slots[mint] = s.copy(priority = Priority8144.LIVE)
                return Submit8144.PROMOTED
            }
            return Submit8144.ALREADY_QUEUED
        }
        if (s != null && s.state == Flight8144.RUNNING) return Submit8144.ALREADY_RUNNING
        val pending = s != null && (s.state == Flight8144.RETRY_SCHEDULED || s.state == Flight8144.QUEUE_REJECTED || s.state == Flight8144.UNANSWERED)
        if (s != null && pending) {
            if (exhausted(s)) {
                slots[mint] = s.copy(state = terminalFor(s), lastAtMs = nowMs, retryAtMs = s.windowStartMs + windowMs)
                return Submit8144.EXHAUSTED
            }
            if (nowMs < s.retryAtMs) return Submit8144.BACKOFF
        }
        val eff = if (s != null) maxPriority(s.priority, priority) else priority
        val accepted = when (eff) {
            Priority8144.LIVE -> live.size < liveCap
            Priority8144.BACKGROUND -> background.size < backgroundCap
        }
        val base = s ?: Slot8144(Flight8144.UNREQUESTED, eff, 0, 0, nowMs, nowMs, 0L)
        if (!accepted) {
            val rejects = base.rejects + 1
            slots[mint] = base.copy(state = Flight8144.QUEUE_REJECTED, priority = eff, rejects = rejects, lastAtMs = nowMs, retryAtMs = nowMs + rejectDelayMs8144(rejects))
            trimIfLarge(nowMs)
            return Submit8144.QUEUE_REJECTED
        }
        if (eff == Priority8144.LIVE) live.addLast(mint) else background.addLast(mint)
        slots[mint] = base.copy(state = Flight8144.QUEUED, priority = eff, lastAtMs = nowMs, retryAtMs = 0L)
        trimIfLarge(nowMs)
        return Submit8144.ACCEPTED
    }

    /** A worker takes the next mint: every live candidate before any background one. Marks it RUNNING and counts the ask. */
    @Synchronized
    fun take8144(nowMs: Long): Taken8144? {
        val (mint, lane) = live.removeFirstOrNull()?.let { it to Priority8144.LIVE }
            ?: background.removeFirstOrNull()?.let { it to Priority8144.BACKGROUND }
            ?: return null
        val s = slots[mint]?.let { windowed(it, nowMs) } ?: Slot8144(Flight8144.QUEUED, lane, 0, 0, nowMs, nowMs, 0L)
        slots[mint] = s.copy(state = Flight8144.RUNNING, attempts = s.attempts + 1, lastAtMs = nowMs)
        return Taken8144(mint, lane)
    }

    /** The worker's ask for [mint] ended. Returns the state it now holds (never an approval by itself). */
    @Synchronized
    fun complete8144(mint: String, answer: Answer8144, nowMs: Long): Flight8144 {
        val s = slots[mint] ?: Slot8144(Flight8144.RUNNING, Priority8144.BACKGROUND, 1, 0, nowMs, nowMs, 0L)
        val next = when (answer) {
            Answer8144.CLEAN -> Slot8144(Flight8144.PROVEN_CLEAN, s.priority, 0, 0, nowMs, nowMs, 0L)
            Answer8144.HOSTILE -> Slot8144(Flight8144.PROVEN_HOSTILE, s.priority, 0, 0, nowMs, nowMs, 0L)
            Answer8144.UNANSWERED ->
                if (s.attempts >= attemptCap(s)) s.copy(state = Flight8144.UNANSWERED, lastAtMs = nowMs, retryAtMs = s.windowStartMs + windowMs)
                else s.copy(state = Flight8144.RETRY_SCHEDULED, lastAtMs = nowMs, retryAtMs = nowMs + retryWait(s))
        }
        slots[mint] = next
        return next.state
    }

    /**
     * The live gate asked [mint] itself and nobody answered: one more attempt this window, and a LIVE retry on the ladder
     * (or the named UNANSWERED terminal when the window is spent). A queued or running ask is left alone.
     */
    fun noteGateUnanswered8144(mint: String, nowMs: Long): Flight8144 = noteUnanswered8144(mint, Priority8144.LIVE, nowMs)

    /**
     * Someone outside the queue asked [mint] and nobody answered (the live gate: LIVE; a scan-time read: BACKGROUND):
     * the ask counts as this window's attempt and a retry is scheduled on the ladder at the higher of the two priorities.
     */
    @Synchronized
    fun noteUnanswered8144(mint: String, priority: Priority8144, nowMs: Long): Flight8144 {
        if (mint.isBlank()) return Flight8144.UNREQUESTED
        val prev = slots[mint]?.let { windowed(it, nowMs) }
        if (prev != null && (prev.state == Flight8144.QUEUED || prev.state == Flight8144.RUNNING)) return prev.state
        val s = (prev ?: Slot8144(Flight8144.UNREQUESTED, priority, 0, 0, nowMs, nowMs, 0L))
            .let { it.copy(attempts = it.attempts + 1, priority = maxPriority(it.priority, priority), lastAtMs = nowMs) }
        val next = if (s.attempts >= attemptCap(s)) s.copy(state = Flight8144.UNANSWERED, retryAtMs = s.windowStartMs + windowMs)
            else s.copy(state = Flight8144.RETRY_SCHEDULED, retryAtMs = nowMs + retryWait(s))
        slots[mint] = next
        trimIfLarge(nowMs)
        return next.state
    }

    /** A fresh proof is already cached for [mint]: settle its slot (no-op for an unknown or in-flight mint). */
    @Synchronized
    fun settle8144(mint: String, proven: AuthorityProofState8136.State, nowMs: Long) {
        val s = slots[mint] ?: return
        if (s.state == Flight8144.QUEUED || s.state == Flight8144.RUNNING) return
        val f = when (proven) {
            AuthorityProofState8136.State.PROVEN_CLEAN -> Flight8144.PROVEN_CLEAN
            AuthorityProofState8136.State.PROVEN_HOSTILE -> Flight8144.PROVEN_HOSTILE
            else -> return
        }
        slots[mint] = Slot8144(f, s.priority, 0, 0, nowMs, nowMs, 0L)
    }

    /**
     * Mints whose bounded retry is due (RETRY_SCHEDULED, or QUEUE_REJECTED with rejections left), live first, at most
     * [max]. A slot whose window is spent turns into its named terminal here instead of being offered again.
     */
    @Synchronized
    fun due8144(nowMs: Long, max: Int): List<Taken8144> {
        val out = ArrayList<Pair<Slot8144, String>>()
        for ((m, raw) in slots.entries.toList()) {
            if (raw.state != Flight8144.RETRY_SCHEDULED && raw.state != Flight8144.QUEUE_REJECTED) continue
            val s = windowed(raw, nowMs)
            if (exhausted(s)) {
                slots[m] = s.copy(state = terminalFor(s), retryAtMs = s.windowStartMs + windowMs)
                continue
            }
            if (nowMs >= s.retryAtMs) out += s to m
        }
        return out.sortedWith(compareBy<Pair<Slot8144, String>>({ if (it.first.priority == Priority8144.LIVE) 0 else 1 }, { it.first.retryAtMs }))
            .take(max.coerceAtLeast(0))
            .map { Taken8144(it.second, it.first.priority) }
    }

    @Synchronized fun state8144(mint: String): Flight8144 = slots[mint]?.state ?: Flight8144.UNREQUESTED

    @Synchronized fun slot8144(mint: String): Slot8144? = slots[mint]

    @Synchronized fun liveDepth8144(): Int = live.size

    @Synchronized fun backgroundDepth8144(): Int = background.size

    /** Count of slots per state (diagnostics). */
    @Synchronized
    fun census8144(): Map<Flight8144, Int> = slots.values.groupingBy { it.state }.eachCount()

    private fun trimIfLarge(nowMs: Long) {
        if (slots.size <= SLOT_CAP_8144) return
        trim8144(SLOT_CAP_8144 / 2, nowMs)
    }

    /** Memory pressure: drop settled slots (proven / named terminal) oldest first; never a queued, running or due one. */
    @Synchronized
    fun trim8144(keep: Int, nowMs: Long): Int {
        val before = slots.size
        val settled = slots.entries.filter {
            val st = it.value.state
            val inFlight = st == Flight8144.QUEUED || st == Flight8144.RUNNING
            st == Flight8144.PROVEN_CLEAN || st == Flight8144.PROVEN_HOSTILE || st == Flight8144.UNANSWERED ||
                (st == Flight8144.QUEUE_REJECTED && it.value.rejects >= maxRejects) ||
                (!inFlight && nowMs - it.value.lastAtMs >= windowMs)
        }.sortedBy { it.value.lastAtMs }
        val excess = (slots.size - keep).coerceAtLeast(0)
        settled.take(excess).forEach { slots.remove(it.key) }
        return (before - slots.size).coerceAtLeast(0)
    }

    companion object {
        const val LIVE_CAP_8144 = 64
        const val BACKGROUND_CAP_8144 = 128
        const val MAX_REJECTS_PER_WINDOW_8144 = 8
        const val BACKGROUND_MAX_ATTEMPTS_8144 = 3
        const val BACKGROUND_MIN_RETRY_MS_8144 = 30_000L
        private const val SLOT_CAP_8144 = 6_000
        private val RETRY_LADDER_MS_8144 = longArrayOf(0L, 3_000L, 10_000L, 30_000L, 60_000L)
        private val REJECT_LADDER_MS_8144 = longArrayOf(2_000L, 5_000L, 15_000L, 30_000L)
    }
}
