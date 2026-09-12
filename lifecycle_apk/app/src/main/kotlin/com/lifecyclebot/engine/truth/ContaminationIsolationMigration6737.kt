package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.6737 — §CONTAMINATION_ISOLATION_MIGRATION.
 *
 * Pillar 2 — an idempotent migration that walks the historic event log,
 * classifies each event via `ProvenanceAuthority6737.classifyOnce`, and
 * quarantines ambiguous rows with explicit reasons. Design rules the
 * operator dictated:
 *
 *   • Preserve genuine positions and losses (never fabricated sells,
 *     refunds, invented prices, blanket deletion, or relabelling to hide
 *     contamination).
 *   • Anything containing "replay" is NOT automatically shadow — an
 *     event that restores a real position (owner-lane, mode, verified
 *     fill) is REPLAY_RESTORE.
 *   • Missing evidence → QUARANTINE_AMBIGUOUS with the specific reason
 *     stored on the authority.
 *
 * Classification decision (evaluated top-down, first match wins):
 *   REPLAY_SHADOW      — sourceTag or lane starts with "SHADOW_" OR the
 *                        payload was tagged as sandbox/what-if by the
 *                        upstream producer.
 *   REPLAY_RESTORE     — sourceTag contains "REPLAY_6486" or "REPLAY_"
 *                        AND positionId + mode + lane + fill amount are
 *                        all present and pass basic sanity.
 *   GENUINE_PAPER      — mode == PAPER and no replay tag.
 *   GENUINE_LIVE       — mode == LIVE  and no replay tag.
 *   QUARANTINE_AMBIGUOUS — anything else, with reason string.
 *
 * Idempotent: `run()` is safe to call repeatedly. The authority's
 * classifyOnce refuses to overwrite prior verdicts, so a second pass
 * produces the same tag set.
 */
object ContaminationIsolationMigration6737 {

    /** Payload the caller must supply for each event to be classified. */
    data class EventProbe(
        val eventId: String,
        val sourceTag: String,       // free-form provenance breadcrumb
        val lane: String,
        val mode: String,
        val positionId: String,
        val hasVerifiedFill: Boolean,
        val hasEntryPriceUsd: Boolean,
        val hasQuantityRaw: Boolean,
    )

    data class RunSummary(
        val examined: Int,
        val genuinePaper: Int,
        val genuineLive: Int,
        val replayRestore: Int,
        val replayShadow: Int,
        val quarantined: Int,
        val quarantineReasons: Map<String, Int>,
    )

    private val running = AtomicBoolean(false)

    /** Idempotent scan. Only the first classify wins for any given eventId. */
    fun run(probes: Iterable<EventProbe>): RunSummary {
        if (!running.compareAndSet(false, true)) {
            // Reentrancy is fine — the classifyOnce contract preserves idempotency,
            // but we still record the concurrent attempt so operators can see
            // migration frequency.
            try { PipelineHealthCollector.labelInc("CONTAMINATION_MIGRATION_CONCURRENT_ATTEMPT_6737") } catch (_: Throwable) {}
        }
        try {
            var examined = 0
            var gp = 0; var gl = 0; var rr = 0; var rs = 0; var q = 0
            val reasons = HashMap<String, Int>()
            for (p in probes) {
                examined += 1
                val (origin, reason) = classify(p)
                val actual = ProvenanceAuthority6737.classifyOnce(p.eventId, origin, reason)
                when (actual) {
                    ProvenanceAuthority6737.Origin.GENUINE_PAPER        -> gp += 1
                    ProvenanceAuthority6737.Origin.GENUINE_LIVE         -> gl += 1
                    ProvenanceAuthority6737.Origin.REPLAY_RESTORE       -> rr += 1
                    ProvenanceAuthority6737.Origin.REPLAY_SHADOW        -> rs += 1
                    ProvenanceAuthority6737.Origin.QUARANTINE_AMBIGUOUS -> {
                        q += 1
                        if (reason.isNotBlank()) reasons.merge(reason, 1, Int::plus)
                    }
                }
            }
            try {
                PipelineHealthCollector.labelInc("CONTAMINATION_MIGRATION_RAN_6737")
                ForensicLogger.lifecycle(
                    "CONTAMINATION_MIGRATION_RAN_6737",
                    "examined=$examined gp=$gp gl=$gl rr=$rr rs=$rs q=$q reasons=${reasons.entries.joinToString(",") { "${it.key}:${it.value}" }}",
                )
            } catch (_: Throwable) {}
            return RunSummary(examined, gp, gl, rr, rs, q, reasons)
        } finally {
            running.set(false)
        }
    }

    /** Classification decision. Returns (origin, quarantineReason). */
    fun classify(p: EventProbe): Pair<ProvenanceAuthority6737.Origin, String> {
        val src = p.sourceTag.uppercase()
        val lane = p.lane.uppercase()
        val mode = p.mode.uppercase()

        // Shadow first — any explicit shadow/sandbox breadcrumb wins immediately.
        if (src.contains("SHADOW_") || src.contains("SANDBOX_") ||
            lane.startsWith("SHADOW_") || src.contains("WHATIF_")) {
            return ProvenanceAuthority6737.Origin.REPLAY_SHADOW to ""
        }

        val isReplayTag = src.contains("REPLAY_6486") || src.startsWith("REPLAY_") ||
            src.contains("_REPLAY_") || src.endsWith("_REPLAY")

        if (isReplayTag) {
            // Replay of a real event — must have complete restoration evidence
            // to count as genuine restore. Missing evidence → quarantine.
            val restoreOk = p.positionId.isNotBlank() &&
                (mode == "PAPER" || mode == "LIVE") &&
                lane.isNotBlank() && p.hasVerifiedFill
            return if (restoreOk)
                ProvenanceAuthority6737.Origin.REPLAY_RESTORE to ""
            else
                ProvenanceAuthority6737.Origin.QUARANTINE_AMBIGUOUS to
                    "REPLAY_MISSING_RESTORATION_EVIDENCE_6737"
        }

        // Non-replay path — require mode. Missing mode = ambiguous.
        return when (mode) {
            "PAPER" -> ProvenanceAuthority6737.Origin.GENUINE_PAPER to ""
            "LIVE"  -> ProvenanceAuthority6737.Origin.GENUINE_LIVE  to ""
            else    -> ProvenanceAuthority6737.Origin.QUARANTINE_AMBIGUOUS to
                "MODE_MISSING_OR_UNKNOWN_6737"
        }
    }
}
