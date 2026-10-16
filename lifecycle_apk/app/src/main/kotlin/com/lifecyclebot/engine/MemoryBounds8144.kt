package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8144 — hard caps, ranked eviction and save coalescing for the resident learning stores.
 *
 * 5.0.8142 died at 512/512 MB (OutOfMemoryError on kotlinx DefaultExecutor) after 71 hard trims, 8 of which freed
 * nothing. The trims reached only a handful of caches; the stores that kept growing (Cortex assessments and pending
 * decisions, the hive's network book, the structure tapes, the unbound policy-head observations) had no cap that
 * pressure could tighten, and two FDG-path learners rewrote their whole SharedPreferences blob on every stamp.
 *
 * Everything here is pure (no Android): caps scale with [RetentionPolicy8136.Pressure]; eviction never picks a
 * protected entry (open position, pending transaction, confirmed fill, promoted strategy, sell obligation) and drops
 * the least valuable, least recently touched entries first.
 */
object MemoryBounds8144 {

    /** Pure: [base] scaled to heap pressure (NORMAL 100%, SOFT 75%, HARD 50%, CRITICAL 25%), never below [floor]. */
    fun capFor8144(base: Int, p: RetentionPolicy8136.Pressure, floor: Int = 0): Int {
        val b = base.coerceAtLeast(0)
        val scaled = when (p) {
            RetentionPolicy8136.Pressure.NORMAL -> b
            RetentionPolicy8136.Pressure.SOFT -> b / 4 * 3 + (b % 4) * 3 / 4
            RetentionPolicy8136.Pressure.HARD -> b / 2
            RetentionPolicy8136.Pressure.CRITICAL -> b / 4
        }
        return maxOf(scaled, floor.coerceAtLeast(0))
    }

    /** One resident entry as eviction sees it: higher [value] = more evidence; [isProtected] is never evicted. */
    class Candidate8144(val key: String, val value: Double, val touchedMs: Long, val isProtected: Boolean)

    /**
     * Pure: keys to evict so that at most [cap] entries remain. Protected entries are never chosen (they still count
     * toward the cap, so a store full of protected entries stays over it). The rest go lowest value first, then least
     * recently touched, then by key (deterministic).
     */
    fun victims8144(entries: List<Candidate8144>, cap: Int): List<String> {
        val excess = entries.size - cap.coerceAtLeast(0)
        if (excess <= 0) return emptyList()
        return entries.asSequence()
            .filter { !it.isProtected }
            .sortedWith(compareBy<Candidate8144>({ if (it.value.isNaN()) Double.NEGATIVE_INFINITY else it.value }, { it.touchedMs }, { it.key }))
            .take(excess)
            .map { it.key }
            .toList()
    }

    /**
     * Pure: the size an over-cap store is trimmed down to. Trimming to the cap itself made the next insert trigger a
     * second full sort (ChartReader / StructureTracker sorted every tape on every new mint once full).
     */
    fun trimTarget8144(cap: Int, slackPct: Int = 10): Int =
        (cap.coerceAtLeast(0).toLong() * (100 - slackPct.coerceIn(0, 50)) / 100).toInt()

    /**
     * Pure: keys of [stamps] (key -> last stamp ms) to drop so at most [cap] remain: oldest stamp first, only keys
     * older than [minAgeMs], never an [isProtected] key.
     */
    fun oldestBeyondCap8144(stamps: Map<String, Long>, cap: Int, minAgeMs: Long, nowMs: Long, isProtected: (String) -> Boolean = { false }): List<String> {
        val excess = stamps.size - cap.coerceAtLeast(0)
        if (excess <= 0) return emptyList()
        return stamps.entries.asSequence()
            .filter { nowMs - it.value >= minAgeMs && !(try { isProtected(it.key) } catch (_: Throwable) { true }) }
            .sortedWith(compareBy<Map.Entry<String, Long>>({ it.value }, { it.key }))
            .take(excess)
            .map { it.key }
            .toList()
    }

    /** Pure: minimum gap between whole-blob saves at this pressure (a big blob is re-serialised whole each time). */
    fun saveIntervalMs8144(baseMs: Long, p: RetentionPolicy8136.Pressure): Long {
        val b = baseMs.coerceAtLeast(0L)
        return when (p) {
            RetentionPolicy8136.Pressure.NORMAL -> b
            RetentionPolicy8136.Pressure.SOFT -> b * 2
            RetentionPolicy8136.Pressure.HARD -> b * 5
            RetentionPolicy8136.Pressure.CRITICAL -> b * 10
        }
    }
}

/**
 * V5.0.8144 — one whole-state save in flight, at most every [minIntervalMs]. UnifiedPolicyHead and
 * AutonomousMetaPolicy launched one full-state export + SharedPreferences rewrite per FDG stamp; under a busy
 * scanner that queued thousands of identical saves on the side-effect pool, each re-serialising the whole blob.
 *
 * Use: `if (c.claim8144()) launch { delay(c.delayMs8144(now)); c.begin8144(now); save() }` and, when the launch
 * fails or is cancelled before its body runs, [abort8144]. [begin8144] releases the claim BEFORE the save reads the
 * state, so a change made while the save runs schedules the next one: no change is ever left unsaved.
 */
class MemorySaveCoalescer8144(val minIntervalMs: Long) {
    private val scheduled = AtomicBoolean(false)
    @Volatile var lastRunMs: Long = 0L
        private set
    val requests = AtomicLong(0)
    val runs = AtomicLong(0)
    val coalesced = AtomicLong(0)

    /** True: the caller launches one save. False: a save already scheduled will carry this change. */
    fun claim8144(): Boolean {
        requests.incrementAndGet()
        if (scheduled.compareAndSet(false, true)) return true
        coalesced.incrementAndGet()
        return false
    }

    /** How long the claimed save waits so saves stay [minIntervalMs] apart. */
    fun delayMs8144(nowMs: Long): Long = (lastRunMs + minIntervalMs - nowMs).coerceIn(0L, minIntervalMs.coerceAtLeast(0L))

    /** The claimed save starts now: later requests claim a new one. */
    fun begin8144(nowMs: Long) {
        lastRunMs = nowMs
        runs.incrementAndGet()
        scheduled.set(false)
    }

    /** The claimed save never ran (launch refused / cancelled): release the claim. */
    fun abort8144() { scheduled.set(false) }

    fun isScheduled8144(): Boolean = scheduled.get()

    fun statusLine8144(): String = "saves=${runs.get()}/${requests.get()} coalesced=${coalesced.get()}"
}
