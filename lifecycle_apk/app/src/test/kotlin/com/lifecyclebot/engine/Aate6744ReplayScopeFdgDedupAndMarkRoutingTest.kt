package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6744 — Directive follow-up regression coverage for three
 * source-level defects the operator surfaced in the 6742 dump.
 *
 * §OPEN_COST_SAME_LOT_SET (Issue #3): the paper replay was
 * accumulating open-cost lines for mints closed via out-of-band
 * paths (recovery replay, terminal reducer, owner-lane restore),
 * producing openCostΔ = 14.68 SOL that the divergence guard read
 * as authoritative drift. Repair scopes the open-cost delta to the
 * canonical live open lot set.
 *
 * §FDG_PRE_DECISION_DEDUP (Issue #4): SPECIALIST_ARBITER dupLane=2834
 * proved every scanner-storm re-hydration burned a fresh V3/FDG
 * evaluation for the same (mint, candidateVersion, lane) triple.
 * Repair short-circuits inside a 750ms window BEFORE any FDG work.
 *
 * §OBSERVATION_FRESHNESS_ROUTING (Issue #5): 121-300s evidence used
 * to be rejected outright by the resolver even though the class
 * comment already documented the intent to route it to the
 * observation slot. Paper trades died at MARK_REJECT even when the
 * source was fine. Repair routes stale-but-in-band evidence to
 * OBSERVATION_SCORING with a widened 300s read window on that
 * purpose (executable slot stays strict at 120s).
 */
class Aate6744ReplayScopeFdgDedupAndMarkRoutingTest {

    // ─── §OPEN_COST_SAME_LOT_SET ─────────────────────────────────────

    @Test
    fun `paper replay scopes open-cost delta to the canonical live lot set`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperReplay6464.kt").readText()
        assertTrue(
            "replay MUST pull the canonical live-open lot set",
            src.contains("activeMintProjections6490(\"paper\")") &&
                src.contains("canonicalLiveMints6743"),
        )
        assertTrue(
            "replay MUST compute a scoped open-cost sum from replay perMintRemainingCostSol filtered to live mints",
            src.contains("replayOpenCostScoped6743") &&
                src.contains("snap.perMintRemainingCostSol.entries") &&
                src.contains("canonicalLiveMints6743.containsKey(it.key)"),
        )
        assertTrue(
            "replay MUST compute a scoped ledger open-cost sum",
            src.contains("ledgerOpenCostScoped6743") &&
                src.contains("canonicalLiveMints6743.values"),
        )
        assertTrue(
            "replay MUST only swap in the scoped delta when abs(scoped) < abs(raw), preserving detection of real ledger drift",
            src.contains("kotlin.math.abs(scopedOpenDelta6743) < kotlin.math.abs(openDelta)"),
        )
        assertTrue(
            "replay MUST emit a dedicated observability label when the swap engages",
            src.contains("PAPER_REPLAY_OPEN_COST_SCOPED_TO_LIVE_SET_6743"),
        )
    }

    // ─── §FDG_PRE_DECISION_DEDUP ─────────────────────────────────────

    @Test
    fun `executable open gate dedupes FDG BEFORE any FDG work`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "gate MUST declare a keyed dedup cache",
            src.contains("private data class FdgDedupKey6743") &&
                src.contains("fdgDedupLastMs6743"),
        )
        assertTrue(
            "dedup TTL must be tight (<= 1s) so genuine cycles still re-evaluate",
            src.contains("FDG_DEDUP_TTL_MS_6743") &&
                src.contains("private const val FDG_DEDUP_TTL_MS_6743 = 750L"),
        )
        val recordFdgIdx = src.indexOf("fun recordFdg(")
        assertTrue("recordFdg must exist", recordFdgIdx > 0)
        // The dedup call must appear inside the recordFdg body BEFORE
        // the shadow lane check (the first real FDG-related work).
        val recordFdgBody = src.substring(recordFdgIdx, kotlin.math.min(recordFdgIdx + 2500, src.length))
        val dedupIdx = recordFdgBody.indexOf("fdgDedupShouldSkip6743")
        val shadowIdx = recordFdgBody.indexOf("isShadowReadOnlyLane6487(lane)")
        assertTrue("dedup call must exist inside recordFdg", dedupIdx > 0)
        assertTrue("shadow-lane branch must exist inside recordFdg", shadowIdx > 0)
        assertTrue(
            "dedup MUST run BEFORE shadow-lane check (before any FDG-shaped work)",
            dedupIdx < shadowIdx,
        )
        assertTrue(
            "dedup MUST emit a dedicated observability label",
            src.contains("FDG_PRE_DECISION_DEDUP_SKIP_6743"),
        )
    }

    // ─── §OBSERVATION_FRESHNESS_ROUTING ──────────────────────────────

    @Test
    fun `mark resolver routes 121-300s evidence to observation not rejection`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPriceMark6522.kt").readText()
        assertTrue(
            "observation window constant must exist and be 300s",
            src.contains("OBSERVATION_FRESHNESS_WINDOW_MS_6743 = 300_000L"),
        )
        assertTrue(
            "executable-slot freshness MUST stay strict at 120s",
            src.contains("MARK_FRESHNESS_WINDOW_MS_6739 = 120_000L"),
        )
        assertTrue(
            "getFresh6734 must pick the 300s window ONLY for OBSERVATION_SCORING",
            src.contains("if (purpose == CanonicalMarkPurpose6570.OBSERVATION_SCORING)") &&
                src.contains("OBSERVATION_FRESHNESS_WINDOW_MS_6743 else MARK_FRESHNESS_WINDOW_MS_6739"),
        )
        val resolverIdx = src.indexOf("fun resolveBestSourceEvidence6734(")
        assertTrue("resolver must exist", resolverIdx > 0)
        val resolverBody = src.substring(resolverIdx, kotlin.math.min(resolverIdx + 2000, src.length))
        assertTrue(
            "resolver MUST route stale-but-in-band evidence to the observation-only path",
            resolverBody.contains("resolveObservationFromSourceEvidence6628"),
        )
        assertTrue(
            "resolver MUST emit a dedicated observability label when routing to observation",
            src.contains("CANONICAL_MARK_OBSERVATION_ROUTED_STALE_EVIDENCE_6743"),
        )
    }
}
