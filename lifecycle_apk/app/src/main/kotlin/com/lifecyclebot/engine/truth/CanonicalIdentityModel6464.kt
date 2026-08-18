package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6464 §P1 — CANONICAL IDENTITY MODEL (do not overload `lane`).
 *
 * OPERATOR MANDATE:
 *   "Do not overload lane. Persist canonicalOriginLane, strategyId,
 *    routeId, executionLane, exitPolicy. Normalize aliases exactly
 *    once at ingestion. Never mutate canonicalOriginLane after entry.
 *    Alias healing should approach zero for NEW positions."
 *
 * DESIGN
 * ──────
 * Per-positionId identity record with 5 canonical fields set at
 * ingestion and never rewritten:
 *
 *   canonicalOriginLane  — normalized origin (RESALE_SNIPE, BLUE_CHIP, ...)
 *   strategyId           — the strategy that generated the signal
 *   routeId              — the route used to enter (V3_LIQUIDITY_A, ...)
 *   executionLane        — where fills land (may differ from origin)
 *   exitPolicy           — the exit ruleset bound at entry (e.g. STANDARD_SL)
 *
 * `normalizeLane(raw)` maps aliases → canonical (RESALE_SNIPE/PRESALE_SNIPE
 * to RESALE_SNIPE; BLUECHIP → BLUE_CHIP; etc.).
 *
 * `record(positionId, ...)` refuses to overwrite an existing canonical
 * origin — subsequent calls hit `IDENTITY_REWRITE_REFUSED_6464`.
 */
object CanonicalIdentityModel6464 {

    data class Identity(
        val positionId: String,
        val canonicalOriginLane: String,
        val strategyId: String,
        val routeId: String,
        val executionLane: String,
        val exitPolicy: String,
        val ingestedAtMs: Long,
    )

    private val records = ConcurrentHashMap<String, Identity>()

    private val recorded = AtomicLong(0L)
    private val rewriteRefused = AtomicLong(0L)
    private val aliasNormalized = AtomicLong(0L)

    // Alias table — extend as ops observes.
    private val aliases: Map<String, String> = mapOf(
        "PRESALE_SNIPE" to "RESALE_SNIPE",
        "PRE_SALE_SNIPE" to "RESALE_SNIPE",
        "BLUECHIP" to "BLUE_CHIP",
        "BLUE_CHIPS" to "BLUE_CHIP",
        "MOMENTUMSWING" to "MOMENTUM_SWING",
        "WHALEFOLLOW" to "WHALE_FOLLOW",
        "COPY_TRADE" to "COPYTRADE",
        "CYCLIC_TREND" to "CYCLIC",
    )

    /**
     * Normalize a raw lane string to its canonical form. Emits
     * IDENTITY_ALIAS_NORMALIZED_6464 for observability. Unknown labels
     * pass through unchanged (upper-cased + trimmed).
     */
    fun normalizeLane(raw: String?): String {
        val clean = (raw ?: "").trim().uppercase().replace("-", "_")
        if (clean.isBlank()) return "UNKNOWN"
        val canonical = aliases[clean] ?: clean
        if (canonical != clean) {
            aliasNormalized.incrementAndGet()
            try { PipelineHealthCollector.labelInc("IDENTITY_ALIAS_NORMALIZED_6464") } catch (_: Throwable) {}
        }
        return canonical
    }

    /**
     * Record identity at ingestion. Refuses to overwrite an existing
     * canonicalOriginLane — that's the whole point.
     */
    fun record(
        positionId: String,
        canonicalOriginLaneRaw: String,
        strategyId: String,
        routeId: String,
        executionLaneRaw: String,
        exitPolicy: String,
    ) {
        if (positionId.isBlank()) return
        val existing = records[positionId]
        if (existing != null) {
            rewriteRefused.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "IDENTITY_REWRITE_REFUSED_6464",
                    "positionId=${positionId.take(16)} existingOrigin=${existing.canonicalOriginLane} " +
                        "attemptedOrigin=${normalizeLane(canonicalOriginLaneRaw)}",
                )
                PipelineHealthCollector.labelInc("IDENTITY_REWRITE_REFUSED_6464")
            } catch (_: Throwable) {}
            return
        }
        val identity = Identity(
            positionId = positionId,
            canonicalOriginLane = normalizeLane(canonicalOriginLaneRaw),
            strategyId = strategyId.ifBlank { "unknown_strategy" },
            routeId = routeId.ifBlank { "unknown_route" },
            executionLane = normalizeLane(executionLaneRaw),
            exitPolicy = exitPolicy.ifBlank { "STANDARD" },
            ingestedAtMs = System.currentTimeMillis(),
        )
        records[positionId] = identity
        recorded.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("IDENTITY_RECORDED_6464")
            PipelineHealthCollector.labelInc("IDENTITY_ORIGIN_${identity.canonicalOriginLane}_6464")
        } catch (_: Throwable) {}
    }

    fun getIdentity(positionId: String): Identity? = records[positionId]

    fun purge(positionId: String) { records.remove(positionId) }

    fun statusLine(): String =
        "records=${records.size} recorded=${recorded.get()} rewriteRefused=${rewriteRefused.get()} " +
            "aliasNormalized=${aliasNormalized.get()}"

    internal fun resetForTest() {
        records.clear()
        recorded.set(0L); rewriteRefused.set(0L); aliasNormalized.set(0L)
    }
}
