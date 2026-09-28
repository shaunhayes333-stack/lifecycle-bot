package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Accounting only. No execution authority. */
object SmartMoneyBridgeHealth7422 {
    enum class Disposition { CANDIDATE_CREATED, CONTRIBUTOR_ATTACHED, DUPLICATE, HELD_MINT, STALE, SAFETY_REJECT, INSUFFICIENT_EVIDENCE, ROUTE_UNAVAILABLE }
    private val detected = AtomicLong(0L)
    private val counts = ConcurrentHashMap<Disposition, AtomicLong>()
    fun detected() { detected.incrementAndGet() }
    fun disposition(d: Disposition) {
        counts.computeIfAbsent(d) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("SMART_MONEY_${d.name}") } catch (_: Throwable) {}
    }
    fun detectedCount(): Long = detected.get()
    fun count(d: Disposition): Long = counts[d]?.get() ?: 0L
    fun accounted(): Long = Disposition.values().sumOf { count(it) }
    fun unexplained(): Long = (detected.get() - accounted()).coerceAtLeast(0L)
    fun candidatesCreated(): Long = count(Disposition.CANDIDATE_CREATED)
    fun contributorsAttached(): Long = count(Disposition.CONTRIBUTOR_ATTACHED)
    fun rejected(): Long = accounted() - candidatesCreated() - contributorsAttached()
}
