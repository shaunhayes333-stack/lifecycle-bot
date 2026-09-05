package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6673 — Surgical triple-fix source acceptance locks.
 *
 *   Fire A — PAPER_QTY_HEAL_ON_SELL_WRITE_6673
 *            Retro-heal historical BUY row when a paper SELL closes with
 *            arithmetically consistent (price × qty ≈ cost) but the BUY
 *            row's qty disagrees by >10× (position opened before the
 *            V5.0.6671 decimal-integrity fix shipped).
 *
 *   Fire B — EXEC_INTENT_SYNTHESIZED_FROM_SEAL_6673
 *            When ExecutableOpenGate reaches its final-bind check and
 *            immutableTicket is null but ticketAuthority6564 /
 *            immutableAuthority6513 / sealedBuyIntent6608 carries a
 *            valid BUY seal, synthesize a minimal ExecutionIntent from
 *            the sealed authority instead of hard-blocking with
 *            EXEC_INTENT_MISSING_AT_FINAL_BIND_6519.
 *
 *   Fire C — SPECIALIST_CAUSAL_ID_CANONICAL_FORMAT
 *            The fallback `specialistCausalId6614` now emits the same
 *            7-part canonical execution key as the primary path so the
 *            ToolkitSignalSheet parser recovers the real mint and
 *            candidate version. Previously the 3-part "gen:ver:lane"
 *            fallback fed a bogus mint to SpecialistCausalFunnel6625
 *            and every meme role liveness row showed SIZING_CHOKED
 *            with sizedN=0 even while finalizedN was positive.
 */
class Aate6673SurgicalTripleFixTest {

    private val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
    private val openGate = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
    private val botService = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    // ── Fire A ─────────────────────────────────────────────────────────────

    @Test
    fun `Fire A REVERTED — paper qty heal code path is removed no writes on paper leg`() {
        // V5.0.6673b: Fire A was surgically wrong. `backfillLastBuyEntryQty6311`
        // finds the MOST RECENT BUY for a mint; when a stale/replayed SELL fires
        // against a mint that already has a fresh reopen, the heal rewrites the
        // fresh OPEN position's qty to the skewed sell qty, producing
        // "qty INVALID (invariant broken)" rows on the operator's dashboard
        // (USMS/STEP screenshot). The paper journal also uses distinct internal
        // decimal representations for price vs sol so the cost-consistency
        // check was structurally unreliable. Fully reverted; stale pre-6671
        // positions self-drain as they close. LIVE wallet-verified backfill
        // (V5.0.6337) is untouched.
        assertFalse(
            "PAPER_QTY_HEAL_ON_SELL_WRITE_6673 counter emit must be absent",
            executor.contains("PAPER_QTY_HEAL_ON_SELL_WRITE_6673"),
        )
        assertFalse(
            "sellCostConsistent6673 predicate must be removed",
            executor.contains("sellCostConsistent6673"),
        )
        assertTrue(
            "PAPER_QTY_HEAL_REVERTED marker must be present so future readers know why",
            executor.contains("PAPER_QTY_HEAL_REVERTED"),
        )
        assertTrue(
            "LIVE wallet-verified backfill (V5.0.6337) must remain untouched",
            executor.contains("BUY_QTY_BACKFILL_ON_SELL_WRITE_6337"),
        )
    }

    // ── Fire B ─────────────────────────────────────────────────────────────

    @Test
    fun `Fire B — final bind synthesizes intent from sealed authority when immutableTicket is null`() {
        assertTrue(
            "ExecutableOpenGate must expose EXEC_INTENT_SYNTHESIZED_FROM_SEAL_6673 counter",
            openGate.contains("EXEC_INTENT_SYNTHESIZED_FROM_SEAL_6673"),
        )
        assertTrue(
            "Synth path must consult ticketAuthority6564.fdgVerdict / hardNoReasons",
            openGate.contains("ticketAuthority6564?.fdgAllowed == true") &&
                openGate.contains("ticketAuthority6564.hardNoReasons.isEmpty()"),
        )
        assertTrue(
            "Synth path must fallback to immutableAuthority6513 when ticket auth absent",
            openGate.contains("immutableAuthority6513?.verdict?.uppercase() in setOf(\"BUY\", \"PROBE_ONLY\")"),
        )
        assertTrue(
            "Synth path must fallback to sealedBuyIntent6608 as final tier",
            openGate.contains("sealedBuyIntent6608 -> \"BUY\""),
        )
        assertTrue(
            "Fallback to the original EXEC_INTENT_MISSING block MUST still exist when no seal available",
            openGate.contains("EXEC_INTENT_MISSING_AT_FINAL_BIND_6519"),
        )
    }

    // ── Fire C ─────────────────────────────────────────────────────────────

    @Test
    fun `Fire C — specialistCausalId6614 fallback emits canonical 7-part execution key`() {
        assertTrue(
            "BotService fallback must call ExecutableOpenGate.canonicalExecutionKey",
            botService.contains("ExecutableOpenGate.canonicalExecutionKey(") &&
                botService.contains("SPECIALIST_CAUSAL_ID_CANONICAL_FORMAT"),
        )
        assertFalse(
            "Legacy 3-part fallback (gen:candidateVersion:lane) must be gone",
            botService.contains("\"\${BotRuntimeController.currentGeneration()}:\${LaneExecutionCoordinator.candidateVersionFor(identity.mint)}:\$cyclePrimaryLane\""),
        )
        assertTrue(
            "effectiveCandidateVersion6614 must be routed to downstream lookups",
            botService.contains("effectiveCandidateVersion6614") &&
                botService.contains("activeExecutionIntent6519(") &&
                botService.contains("if (cfg.paperMode) \"PAPER\" else \"LIVE\", identity.mint, effectiveCandidateVersion6614,"),
        )
    }
}
