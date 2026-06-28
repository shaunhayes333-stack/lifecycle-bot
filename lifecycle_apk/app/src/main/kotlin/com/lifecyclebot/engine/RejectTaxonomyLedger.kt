package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** V5.0.4425 — lightweight report-side ledger for normalized reject classes. */
object RejectTaxonomyLedger {
    private val counts = ConcurrentHashMap<RejectTaxonomy.Category, AtomicLong>()
    private val laneCounts = ConcurrentHashMap<String, AtomicLong>()
    private val hardSafetyCount = AtomicLong(0L)
    private val trainableCount = AtomicLong(0L)

    fun record(classification: RejectTaxonomy.Classification, lane: String, reason: String = "") {
        counts.computeIfAbsent(classification.category) { AtomicLong(0L) }.incrementAndGet()
        laneCounts.computeIfAbsent("${lane.uppercase()}:${classification.category.name}") { AtomicLong(0L) }.incrementAndGet()
        if (classification.hardSafety) hardSafetyCount.incrementAndGet()
        if (classification.trainable) trainableCount.incrementAndGet()
        if (classification.category == RejectTaxonomy.Category.UNKNOWN_REVIEW) {
            try { PipelineHealthCollector.labelInc("REJECT_TAXONOMY_UNKNOWN_REVIEW_4425") } catch (_: Throwable) {}
            try { ForensicLogger.lifecycle("REJECT_TAXONOMY_UNKNOWN_REVIEW_4425", "lane=$lane reason=${reason.take(96)}") } catch (_: Throwable) {}
        }
    }

    fun status(): String {
        val byCat = RejectTaxonomy.Category.values().joinToString(",") { cat ->
            "${cat.name}=${counts[cat]?.get() ?: 0L}"
        }
        val topLane = laneCounts.entries.sortedByDescending { it.value.get() }.take(8).joinToString(",") { "${it.key}=${it.value.get()}" }
        return "REJECT_TAXONOMY_LEDGER_4425 byCat=[$byCat] topLane=[$topLane] hardSafety=${hardSafetyCount.get()} trainable=${trainableCount.get()} report_only=true no_execution_authority=true"
    }

    fun resetForTests() {
        counts.clear()
        laneCounts.clear()
        hardSafetyCount.set(0L)
        trainableCount.set(0L)
    }
}
