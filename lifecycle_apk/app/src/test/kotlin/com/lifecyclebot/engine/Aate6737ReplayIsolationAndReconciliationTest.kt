package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ContaminationIsolationMigration6737
import com.lifecyclebot.engine.truth.ContaminationIsolationMigration6737.EventProbe
import com.lifecyclebot.engine.truth.ProvenanceAuthority6737
import com.lifecyclebot.engine.truth.ProvenanceAuthority6737.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.6737 — regression suite for three concurrent pillars:
 *   1. Shadow replay boundary (ProvenanceAuthority6737.guardMutation).
 *   2. Contamination isolation migration (idempotent classify).
 *   3. Reconciler exclusion by immutable event id.
 */
class Aate6737ReplayIsolationAndReconciliationTest {

    @Before
    fun reset() {
        ProvenanceAuthority6737.resetForTest6737()
    }

    // ─── Pillar 1: mutation-boundary shadow refusal ────────────────

    @Test
    fun `guardMutation refuses REPLAY_SHADOW on authoritative surfaces`() {
        val v = ProvenanceAuthority6737.guardMutation(Origin.REPLAY_SHADOW, "PaperAccountLedger6430.onBuy")
        assertFalse("REPLAY_SHADOW must be hard-refused at mutation boundary", v.allow)
        assertEquals("REPLAY_SHADOW_REFUSED", v.reason)
    }

    @Test
    fun `guardMutation allows GENUINE and REPLAY_RESTORE`() {
        assertTrue(ProvenanceAuthority6737.guardMutation(Origin.GENUINE_PAPER, "test").allow)
        assertTrue(ProvenanceAuthority6737.guardMutation(Origin.GENUINE_LIVE, "test").allow)
        // REPLAY_RESTORE is a real event replay to rebuild positions after
        // restart — same owner, same lane, same mode. It must NOT be
        // classified as synthetic just because the string contains "replay".
        assertTrue(
            "REPLAY_RESTORE must be preserved as a legitimate mutation",
            ProvenanceAuthority6737.guardMutation(Origin.REPLAY_RESTORE, "test").allow,
        )
    }

    @Test
    fun `guardMutation refuses QUARANTINE_AMBIGUOUS`() {
        val v = ProvenanceAuthority6737.guardMutation(Origin.QUARANTINE_AMBIGUOUS, "test")
        assertFalse(v.allow)
        assertEquals("QUARANTINE_AMBIGUOUS_REFUSED", v.reason)
    }

    @Test
    fun `paper ledger provenanced overload refuses shadow at mutation boundary`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperAccountLedger6430.kt").readText()
        // Contract: the overload exists AND calls the guard BEFORE committing.
        assertTrue(src.contains("fun onBuyProvenanced6737"))
        assertTrue(src.contains("fun onSellProvenanced6737"))
        assertTrue(src.contains("ProvenanceAuthority6737.guardMutation(origin"))
        // No hidden default that lets a shadow-tagged path through the
        // legacy onBuy/onSell without going via guardMutation. The blank-
        // origin legacy path is intentionally GENUINE_PAPER by contract.
    }

    // ─── Pillar 2: idempotent classification / migration ───────────

    @Test
    fun `classifyOnce is idempotent by event id`() {
        val id = "evt-1"
        val first = ProvenanceAuthority6737.classifyOnce(id, Origin.GENUINE_PAPER)
        val second = ProvenanceAuthority6737.classifyOnce(id, Origin.REPLAY_SHADOW)
        assertEquals("First verdict is authoritative", Origin.GENUINE_PAPER, first)
        assertEquals("Second attempt must not overwrite", Origin.GENUINE_PAPER, second)
    }

    @Test
    fun `migration classifies genuine paper trade`() {
        val summary = ContaminationIsolationMigration6737.run(listOf(
            EventProbe(
                eventId = "g-1", sourceTag = "TokenizedStockTrader", lane = "STOCK_SPOT",
                mode = "PAPER", positionId = "p-1",
                hasVerifiedFill = true, hasEntryPriceUsd = true, hasQuantityRaw = true,
            ),
        ))
        assertEquals(1, summary.genuinePaper)
        assertEquals(Origin.GENUINE_PAPER, ProvenanceAuthority6737.originOf("g-1"))
    }

    @Test
    fun `migration classifies replay restore as legitimate NOT synthetic`() {
        val summary = ContaminationIsolationMigration6737.run(listOf(
            EventProbe(
                eventId = "r-1", sourceTag = "REPLAY_6486", lane = "MOONSHOT",
                mode = "PAPER", positionId = "p-2",
                hasVerifiedFill = true, hasEntryPriceUsd = true, hasQuantityRaw = true,
            ),
        ))
        assertEquals(
            "Genuine event replay to restore positions is not shadow.",
            1, summary.replayRestore,
        )
        assertEquals(Origin.REPLAY_RESTORE, ProvenanceAuthority6737.originOf("r-1"))
    }

    @Test
    fun `migration quarantines replay tag with missing restoration evidence`() {
        val summary = ContaminationIsolationMigration6737.run(listOf(
            EventProbe(
                eventId = "amb-1", sourceTag = "REPLAY_6486", lane = "",
                mode = "PAPER", positionId = "",
                hasVerifiedFill = false, hasEntryPriceUsd = false, hasQuantityRaw = false,
            ),
        ))
        assertEquals(1, summary.quarantined)
        assertEquals(Origin.QUARANTINE_AMBIGUOUS, ProvenanceAuthority6737.originOf("amb-1"))
        assertEquals("REPLAY_MISSING_RESTORATION_EVIDENCE_6737",
            ProvenanceAuthority6737.quarantineReasonOf("amb-1"))
    }

    @Test
    fun `migration flags explicit shadow tag as REPLAY_SHADOW`() {
        val summary = ContaminationIsolationMigration6737.run(listOf(
            EventProbe(
                eventId = "s-1", sourceTag = "SHADOW_WHATIF", lane = "SHADOW_MEME",
                mode = "PAPER", positionId = "s-p1",
                hasVerifiedFill = true, hasEntryPriceUsd = true, hasQuantityRaw = true,
            ),
        ))
        assertEquals(1, summary.replayShadow)
        assertEquals(Origin.REPLAY_SHADOW, ProvenanceAuthority6737.originOf("s-1"))
    }

    @Test
    fun `migration is idempotent across repeated runs`() {
        val probes = listOf(
            EventProbe("g-2", "TokenizedStockTrader", "STOCK_SPOT", "PAPER", "p-3",
                true, true, true),
            EventProbe("s-2", "SHADOW_SANDBOX", "SHADOW_TEST", "PAPER", "p-4",
                true, true, true),
            EventProbe("r-2", "REPLAY_6486", "MOONSHOT", "PAPER", "p-5",
                true, true, true),
        )
        val first = ContaminationIsolationMigration6737.run(probes)
        val second = ContaminationIsolationMigration6737.run(probes)
        assertEquals("Repeated migration must produce identical genuine-paper count",
            first.genuinePaper, second.genuinePaper)
        assertEquals("Repeated migration must produce identical shadow count",
            first.replayShadow, second.replayShadow)
        assertEquals("Repeated migration must produce identical replay-restore count",
            first.replayRestore, second.replayRestore)
        assertEquals(Origin.GENUINE_PAPER, ProvenanceAuthority6737.originOf("g-2"))
        assertEquals(Origin.REPLAY_SHADOW, ProvenanceAuthority6737.originOf("s-2"))
        assertEquals(Origin.REPLAY_RESTORE, ProvenanceAuthority6737.originOf("r-2"))
    }

    // ─── Pillar 3: reconciler exclusion by immutable ID ────────────

    @Test
    fun `reconciler excludes only shadow and quarantine ids not genuine or restore`() {
        ProvenanceAuthority6737.classifyOnce("gen-a", Origin.GENUINE_PAPER)
        ProvenanceAuthority6737.classifyOnce("rest-a", Origin.REPLAY_RESTORE)
        ProvenanceAuthority6737.classifyOnce("shdw-a", Origin.REPLAY_SHADOW)
        ProvenanceAuthority6737.classifyOnce("quar-a", Origin.QUARANTINE_AMBIGUOUS, "MISSING")

        assertFalse(ProvenanceAuthority6737.isExcludedFromParity("gen-a"))
        assertFalse("REPLAY_RESTORE is a real mutation and must count in parity",
            ProvenanceAuthority6737.isExcludedFromParity("rest-a"))
        assertTrue(ProvenanceAuthority6737.isExcludedFromParity("shdw-a"))
        assertTrue(ProvenanceAuthority6737.isExcludedFromParity("quar-a"))

        val excluded = ProvenanceAuthority6737.excludedEventIds(
            listOf("gen-a", "rest-a", "shdw-a", "quar-a", "unknown"),
        )
        assertEquals(setOf("shdw-a", "quar-a"), excluded)
    }

    @Test
    fun `paper replay source calls the isExcludedFromParity gate`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperReplay6464.kt").readText()
        assertTrue(
            "CanonicalPaperReplay6464.replay must consult the provenance gate before folding an event",
            src.contains("ProvenanceAuthority6737.isExcludedFromParity"),
        )
        assertTrue(src.contains("PAPER_REPLAY_EXCLUDED_QUARANTINE_6737"))
    }
}
