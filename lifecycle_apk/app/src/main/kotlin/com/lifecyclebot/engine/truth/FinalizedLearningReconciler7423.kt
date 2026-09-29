package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/** ID-based CLOSED -> FINALIZED reconciliation.
 * V5.0.7459 also repairs the narrow case where exact durable finality/economics
 * exist but the 6464 fanout projection is missing. */
object FinalizedLearningReconciler7423 {
    enum class Reason {
        ECONOMICS_QUARANTINED,
        LEGACY_REPLAY,
        DUPLICATE,
        CORRUPT_ENTRY,
        /** Durable full terminal proof exists, but finalized bus publication is absent. */
        BUS_PUBLISH_FAILED,
        /** Historical CLOSED row with no durable full terminal proof to safely replay. */
        HISTORICAL_NO_DURABLE_FINALITY,
        UNKNOWN,
    }
    data class Missing(val positionId: String, val reason: Reason, val detail: String)
    data class Snapshot(val canonicalClosed: Int, val finalizedPublished: Int, val missing: List<Missing>) {
        val unexplained: Int get() = missing.count { it.reason == Reason.UNKNOWN }
        val explicitExcluded: Int get() = missing.count { it.reason in setOf(Reason.ECONOMICS_QUARANTINED, Reason.LEGACY_REPLAY, Reason.DUPLICATE, Reason.CORRUPT_ENTRY) }
    }

    private data class SnapshotCache7493(val key: String, val value: Snapshot)
    private val snapshotCache7493 =
        java.util.concurrent.atomic.AtomicReference<SnapshotCache7493?>(null)

    private fun revisionKey7493(): String =
        CanonicalPositionAuthority6441.mutationCount7387().toString() + "|" +
            EconomicEventSchema6464.version().toString() + "|" +
            CanonicalFinalizedTradeBus6464.canonicalRevision7493().toString()

    fun snapshot(): Snapshot {
        val key7493 = try { revisionKey7493() } catch (_: Throwable) { "" }
        snapshotCache7493.get()?.let { cached ->
            if (key7493.isNotBlank() && cached.key == key7493) {
                try { PipelineHealthCollector.labelInc("FINALIZED_RECONCILE_SNAPSHOT_REUSED_7493") } catch (_: Throwable) {}
                return cached.value
            }
        }
        val rebuilt7493 = try {
            val closed = CanonicalPositionAuthority6441.closedPositions()
            val publishedIds = CanonicalFinalizedTradeBus6464.canonicalPositionIds7018()
            val earliestBusAt7433 = CanonicalFinalizedTradeBus6464.earliestCanonicalAtMs7433()
            // V5.0.7432 — a historical CLOSED row is not automatically a
            // current bus-publication failure. Only call it BUS_PUBLISH_FAILED
            // when durable typed economics contains a full terminal SELL for
            // the same canonical positionId. Otherwise it is a historical
            // finality gap that cannot be safely replayed without inventing PnL.
            val fullTerminalByPosition7432 = try {
                EconomicEventSchema6464.snapshot().asSequence()
                    .filterIsInstance<EconomicEventSchema6464.Sell>()
                    .filter { !it.partial && it.positionId.isNotBlank() }
                    .map { it.positionId }
                    .toHashSet()
            } catch (_: Throwable) { emptySet<String>() }

            val missing = closed.asSequence()
                .filter { it.positionId !in publishedIds }
                .map { p ->
                    val src = p.entryPriceSource.uppercase()
                    val q = p.quarantineReason.uppercase()
                    val reason = when {
                        q.isNotBlank() || q.contains("ECONOMIC") || src.contains("QUARANTIN") -> Reason.ECONOMICS_QUARANTINED
                        src.contains("LEGACY_REPLAY") || src.contains("REPLAY_CARRY") -> Reason.LEGACY_REPLAY
                        src.contains("DUPLICATE") || q.contains("DUPLICATE") -> Reason.DUPLICATE
                        !p.entryCostSol.isFinite() || p.entryCostSol <= 0.0 || p.entryPriceUsd < 0.0 || src.contains("INVARIANT_BROKEN") -> Reason.CORRUPT_ENTRY
                        p.positionId in fullTerminalByPosition7432 -> Reason.BUS_PUBLISH_FAILED
                        p.positionId.isNotBlank() -> Reason.HISTORICAL_NO_DURABLE_FINALITY
                        else -> Reason.UNKNOWN
                    }
                    Missing(
                        p.positionId,
                        reason,
                        "mint=" + p.mint.take(12) + " lane=" + p.lane +
                            " src=" + p.entryPriceSource.take(48) +
                            " quarantine=" + p.quarantineReason.take(48) +
                            " durableFullSell=" + (p.positionId in fullTerminalByPosition7432),
                    )
                }.toList()
            Snapshot(closed.size, publishedIds.size, missing)
        } catch (t: Throwable) {
            Snapshot(0, 0, listOf(Missing("", Reason.UNKNOWN, "reconcile_throw=${t.javaClass.simpleName}")))
        }
        if (key7493.isNotBlank()) {
            val after7493 = try { revisionKey7493() } catch (_: Throwable) { "" }
            if (after7493 == key7493) snapshotCache7493.set(SnapshotCache7493(key7493, rebuilt7493))
        }
        return rebuilt7493
    }

    /**
     * V5.0.7459 — repair only provable 6464 publication gaps.
     *
     * Required agreement:
     *  - canonical position is CLOSED,
     *  - exact full durable SELL exists,
     *  - immutable entry snapshot exists,
     *  - row is not quarantine/replay/restored/corrupt,
     *  - 6464 has not already seen the position.
     *
     * Prefer the persisted rich 6450 event. When that row is absent but the
     * typed full SELL exists, reconstruct only fields directly supported by
     * the typed economics + immutable entry snapshot.
     */
    fun repairDurableBusPublishFailures7459(limit: Int = 8): Int {
        if (limit <= 0) return 0
        val published = CanonicalFinalizedTradeBus6464.canonicalPositionIds7018()
        val sells = try {
            EconomicEventSchema6464.snapshot().asSequence()
                .filterIsInstance<EconomicEventSchema6464.Sell>()
                .filter { !it.partial && it.positionId.isNotBlank() }
                .groupBy { it.positionId }
        } catch (_: Throwable) { emptyMap<String, List<EconomicEventSchema6464.Sell>>() }

        var repaired = 0
        for (p in CanonicalPositionAuthority6441.closedPositions()) {
            if (repaired >= limit || p.positionId in published) continue

            val src = p.entryPriceSource.uppercase()
            val q = p.quarantineReason.uppercase()
            if (q.isNotBlank() || src.contains("QUARANTIN") ||
                src.contains("LEGACY_REPLAY") || src.contains("REPLAY_CARRY") ||
                src.contains("DUPLICATE") || src.contains("INVARIANT_BROKEN") ||
                !p.entryCostSol.isFinite() || p.entryCostSol <= 0.0
            ) continue

            val sell = sells[p.positionId]?.maxByOrNull { it.atMs } ?: continue
            val entry = EntryStrategySnapshot6450.snapshot(p.positionId)
            if (entry == null) {
                try { PipelineHealthCollector.labelInc("FINALIZED_BUS_REPAIR_ENTRY_SNAPSHOT_MISSING_7459") } catch (_: Throwable) {}
                continue
            }
            val entryProv = entry.entrySource.uppercase()
            if (entryProv.contains("RESTOR") || entryProv.contains("REPLAY") ||
                entryProv.contains("ORPHAN") || entryProv.contains("CARRY")) continue
            if (sell.allocatedCostBasisSol <= 0.0 || !sell.allocatedCostBasisSol.isFinite()) continue

            val rich = CanonicalFinalityPersistence6486.durableEventForPosition7459(p.positionId)
            val netPnl = rich?.netRealizedPnlSol ?: (sell.realizedPnlSol - sell.exitFeesSol)
            val netPct = rich?.netReturnPct ?: (netPnl / sell.allocatedCostBasisSol * 100.0)
            if (!netPnl.isFinite() || !netPct.isFinite()) continue

            val eligibility = try {
                PaperLearningEligibility6519.decision(p.positionId, p.mint)
            } catch (_: Throwable) {
                PaperLearningEligibility6519.Decision(false, "ELIGIBILITY_LOOKUP_FAILED_7459")
            }
            val entryScore = entry.entryScore.coerceIn(0, 100)
            val lane = (rich?.entryLane ?: entry.entryLane).ifBlank { p.lane }
            if (lane.isBlank()) continue

            val env = CanonicalFinalizedTradeBus6464.Envelope(
                tradeId = p.positionId,
                atMs = rich?.settledAtMs ?: sell.atMs,
                realizedPnlSol = netPnl,
                realizedReturnPct = netPct,
                mint = p.mint,
                lane = lane,
                positionId = p.positionId,
                mode = rich?.mode ?: sell.mode,
                proofState = if (rich != null)
                    (rich.dataQuality + ":" + rich.priceIntegrity)
                else "DURABLE_TYPED_SELL_RECONSTRUCTED_7459",
                holdingTimeMs = rich?.holdingTimeMs
                    ?: (sell.atMs - entry.entryTimestampMs).coerceAtLeast(0L),
                entryScore = entryScore,
                entryTactic = rich?.entryTactic ?: entry.entryTactic,
                entryTradeType = entry.entryTradeType,
                entrySetup = entry.entrySetup,
                entryStyle = entry.entryStyle,
                entryEntryStyle = entry.entryEntryStyle,
                entryExitStyle = entry.entryExitStyle,
                entryStrategyVariantId = entry.entryStrategyVariantId,
                entrySource = entry.entrySource,
                marketRegime = entry.entryMarketRegime,
                scoreBand = com.lifecyclebot.engine.LosingPatternMemory.scoreBand(entryScore),
                terminal = true,
                learningEligible = eligibility.eligible,
                learningEligibilityReason = eligibility.reason,
                assetClassTag = (rich?.assetClassTag ?: entry.assetClassTag)
                    .ifBlank { AssetClass.fromLane(lane).tag },
                economicEventId = rich?.economicEventId?.ifBlank { sell.idempotencyKey }
                    ?: sell.idempotencyKey,
                exitReason = rich?.exitReason?.ifBlank { "DURABLE_BUS_REPAIR_7459" }
                    ?: "DURABLE_BUS_REPAIR_7459",
            )

            if (CanonicalFinalizedTradeBus6464.publish(env)) {
                CanonicalFinalizedTradeBus6464.deliverToConsumers(
                    env,
                    FinalizedBusConsumerBridge6465::deliver,
                )
                CanonicalFinalizedTradeBus6464.requestRetry6486()
                repaired++
                try {
                    PipelineHealthCollector.labelInc("FINALIZED_BUS_DURABLE_REPAIR_7459")
                    ForensicLogger.lifecycle(
                        "FINALIZED_BUS_DURABLE_REPAIR_7459",
                        "positionId=${p.positionId.take(24)} mint=${p.mint.take(12)} lane=$lane " +
                            "eventId=${env.economicEventId.take(36)} rich=${rich != null} netPct=$netPct",
                    )
                } catch (_: Throwable) {}
            }
        }
        return repaired
    }

    fun statusLine(): String {
        val s = snapshot()
        val by = s.missing.groupingBy { it.reason }.eachCount().entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}=${it.value}" }
        val ids = s.missing.take(5).joinToString("|") { "${it.positionId.take(36)}:${it.reason.name}" }
        return "closed=${s.canonicalClosed} published=${s.finalizedPublished} missing=${s.missing.size} explicit=${s.explicitExcluded} unknown=${s.unexplained} by=[$by] ids=[$ids]"
    }
}
