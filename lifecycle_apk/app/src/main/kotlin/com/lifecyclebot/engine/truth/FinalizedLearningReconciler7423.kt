package com.lifecyclebot.engine.truth

/** ID-based CLOSED -> FINALIZED reconciliation. Diagnostic/accounting authority only. */
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

    fun snapshot(): Snapshot {
        return try {
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
    }

    fun statusLine(): String {
        val s = snapshot()
        val by = s.missing.groupingBy { it.reason }.eachCount().entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}=${it.value}" }
        val ids = s.missing.take(5).joinToString("|") { "${it.positionId.take(36)}:${it.reason.name}" }
        return "closed=${s.canonicalClosed} published=${s.finalizedPublished} missing=${s.missing.size} explicit=${s.explicitExcluded} unknown=${s.unexplained} by=[$by] ids=[$ids]"
    }
}
