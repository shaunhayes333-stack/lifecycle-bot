package com.lifecyclebot.engine.cortex

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Bounded, coalesced reads of lane-independent voters. The returned array is owned by that assessment. */
internal class CortexVoterMemo8522(
    private val laneFree: BooleanArray,
    private val capacity: Int = 128,
    private val ttlMs: Long = 3_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Memo(val input: Any, val priceAtMs: Long, val wallMs: Long, val readAtMs: Long, val values: DoubleArray)
    private val held = ConcurrentHashMap<String, Memo>()
    // Fixed stripes bound lock memory as well as memo memory. A same-token burst cannot read 288 shared voters in parallel.
    private val locks = Array(128) { Any() }
    val hits = AtomicLong(0L)
    val misses = AtomicLong(0L)
    val size: Int get() = held.size

    fun read(mint: String, input: Any, priceAtMs: Long, readAtMs: Long, core: BooleanArray?, order: IntArray,
             readOne: (Int) -> Double): DoubleArray = synchronized(locks[(mint.hashCode() and Int.MAX_VALUE) % locks.size]) {
        val wall = clock()
        val memo = held[mint]?.takeIf { reusable(it.input === input, it.priceAtMs, priceAtMs, it.wallMs, wall,
            it.readAtMs, readAtMs, ttlMs) }
        val out = DoubleArray(laneFree.size) { Double.NaN }
        for (i in order) {
            val cached = memo?.values?.getOrNull(i) ?: Double.NaN
            // An earlier lane may have budget-abstained on this voter. That NaN cannot suppress another lane's
            // seated voter, which must get its inline read just as it does on a memo miss.
            out[i] = if (memo != null && laneFree[i] && (cached.isFinite() || core?.getOrNull(i) != true)) cached else readOne(i)
        }
        if (memo == null) {
            misses.incrementAndGet()
            synchronized(held) {
                if (held.size >= capacity) trim(wall)
                if (held.size < capacity || held.containsKey(mint)) held[mint] = Memo(input, priceAtMs, wall, readAtMs, out.copyOf())
            }
        } else {
            hits.incrementAndGet()
            // A newly answered core voter may help the remaining lanes. Never mutate a preceding prediction's array
            // or advance the memo's clock: old votes cannot become fresh because a different voter just answered.
            val supplemented = memo.values.copyOf()
            var changed = false
            for (i in laneFree.indices) if (laneFree[i] && !supplemented[i].isFinite() && out[i].isFinite()) {
                supplemented[i] = out[i]; changed = true
            }
            if (changed) held.replace(mint, memo, memo.copy(values = supplemented))
        }
        out
    }

    fun trim(nowMs: Long): Int {
        val before = held.size
        held.entries.removeIf { nowMs - it.value.wallMs !in 0L..ttlMs }
        return (before - held.size).coerceAtLeast(0)
    }

    companion object {
        internal fun reusable(sameObject: Boolean, priceAtThen: Long, priceAtNow: Long, readWallMs: Long, wallNowMs: Long,
                              readNowMs: Long, nowMs: Long, maxAgeMs: Long = 3_000L): Boolean =
            sameObject && priceAtThen == priceAtNow && wallNowMs >= readWallMs && wallNowMs - readWallMs <= maxAgeMs &&
                kotlin.math.abs(nowMs - readNowMs) <= maxAgeMs
    }
}
