package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * V5.0.6604 — Meme causal authority restoration (troubleshoot_agent P0 fix).
 *
 * troubleshoot_agent diagnosed the <10% MemeTrader WR as three converging
 * "brains are telemetry-only" failures:
 *
 *   1. UnifiedPolicyHead.laneHasOwnAuthoritativeHead(lane) required the
 *      lane's OWN head to hit AUTHORITATIVE (n>=25) before the
 *      LEARNED_POLICY_VETO_6593 could fire. Cold MEME lanes rarely
 *      graduate their own head fast enough — the global head's learned
 *      -0.66 bias was never allowed to veto MEME candidates.
 *   2. TacticSwitcher rotated MOMENTUM→PULLBACK→REACCUMULATION→BREAKOUT
 *      on catastrophic buckets but BotService's weakWait branch promoted
 *      candidates to a DUST_PROBE regardless of the current tactic —
 *      rotations were cosmetic and the same weak signal fired the same
 *      probe.
 *   3. specialistEvaluationAllowed6600 (MEME desk owner election) checked
 *      designated-desk qualification but not the brains' proven-dead
 *      HARD_BLOCK or the AUTHORITATIVE lane pWin floor. Specialists were
 *      elected as primary/rescue even when BrainConsensusGate would
 *      independently HARD_BLOCK the same candidate downstream — capital
 *      was already committed before the veto could shape it out.
 *
 * The 6604 fix restores causal authority to each layer.
 */
class Aate6604MemeCausalAuthorityCoverageTest {

    @Test
    fun aate6604_meme_lane_global_authority_widens_veto() {
        val headSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/UnifiedPolicyHead.kt"
        ).readText()
        assertTrue(
            "V5.0.6604: laneHasOwnAuthoritativeHead must widen to MEME family when global head has hit MEME_GLOBAL_AUTHORITY_TRAINED_6604",
            headSrc.contains("MEME_GLOBAL_AUTHORITY_TRAINED_6604") &&
                headSrc.contains("MEME_GLOBAL_AUTHORITY_TRAINED_6604 = 50L") &&
                headSrc.contains("isMemeLane && trained >= MEME_GLOBAL_AUTHORITY_TRAINED_6604")
        )
        // Original own-head check must still be present (non-MEME lanes
        // still gated on own-head samples per the 6596 doctrine).
        assertTrue(
            "V5.0.6604: non-MEME own-head check must be preserved",
            headSrc.contains("h.trained >= AUTHORITY_AUTHORITATIVE")
        )
    }

    @Test
    fun aate6604_tactic_rotation_shapes_weakwait_branch_before_fdg() {
        val botSrc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6613: weakWait must consult TacticSwitcher and apply lane-local bounded shaping",
            botSrc.contains("tacticGateActive6604") &&
                botSrc.contains("com.lifecyclebot.engine.learning.TacticSwitcher.currentTactic(") &&
                botSrc.contains("TACTIC_ROTATED_WEAK_WAIT_SHAPED_6613") &&
                botSrc.contains("tacticWaitShape6613")
        )
        assertFalse(botSrc.contains("TACTIC_ROTATED_WEAK_WAIT_BLOCKED_6604"))
    }

    @Test
    fun aate6604_specialist_election_consults_consensus_and_pwin() {
        val botSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        assertTrue(
            "V5.0.6604: MEME specialist election must include proven-dead HARD_BLOCK equivalent gate",
            botSrc.contains("provenDeadHardBlock6604") &&
                botSrc.contains("MEME_SPECIALIST_CONSENSUS_HARD_BLOCK_6604") &&
                botSrc.contains("com.lifecyclebot.engine.LosingPatternMemory.stats(l, v3)")
        )
        assertTrue(
            "V5.0.6604: MEME specialist election must include AUTHORITATIVE lane pWin floor gate",
            botSrc.contains("lanePWinBelowGate6604") &&
                botSrc.contains("SPECIALIST_MIN_PWIN_6604") &&
                botSrc.contains("SPECIALIST_MIN_PWIN_6604 = 0.45") &&
                botSrc.contains("MEME_SPECIALIST_PWIN_GATE_6604")
        )
        // Post-6600 golden-tape contract: the standalone-mission-desk block
        // still `return specialistEvaluationAllowed6600` — the 6604 gates
        // are added BEFORE that return, so the final return remains intact.
        assertTrue(
            "V5.0.6604: standalone-mission-desk return contract from 6600 must be preserved",
            botSrc.contains("return specialistEvaluationAllowed6600")
        )
    }

    @Test
    fun aate6604_per_position_mark_quarantine_at_source() {
        val capitalSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalCapitalAuthority6450.kt"
        ).readText()
        assertTrue(
            "V5.0.6604: capital snapshot must quarantine per-position marks that exceed cost basis by SANITY_MULT_6604",
            capitalSrc.contains("perPositionInflated6604") &&
                capitalSrc.contains("HERO_OPENMV_PER_POSITION_QUARANTINE_6604") &&
                capitalSrc.contains("SANITY_MULT_6604 = 100.0") &&
                capitalSrc.contains("fresh > costBasis6604 * SANITY_MULT_6604")
        )
        // The pre-existing aggregate clamp (6602) is preserved as a
        // second-line safety net.
        assertTrue(
            "V5.0.6604: aggregate 6602 clamp must remain as the second-line safety net",
            capitalSrc.contains("HERO_OPENMV_SANITY_CLAMP_6602") &&
                capitalSrc.contains("SANITY_MULT_6602 = 100.0")
        )
    }

    @Test
    fun aate6604_paper_capital_facade_read_unification() {
        val facadeSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/PaperCapitalAuthority6577.kt"
        ).readText()
        assertTrue(
            "V5.0.6604: PaperCapitalAuthority6577 must expose the remaining read-only delegations",
            facadeSrc.contains("fun cashSol(): Double") &&
                facadeSrc.contains("fun openCostBasisSol(): Double") &&
                facadeSrc.contains("fun realizedPnlSol(): Double") &&
                facadeSrc.contains("fun feesSol(): Double") &&
                facadeSrc.contains("fun startingCashSol(): Double") &&
                facadeSrc.contains("fun isAuthorityInitialized6489(): Boolean")
        )
        // No direct PaperAccountLedger6430 read-only call site should
        // remain in main sources (writes are unaffected). We allow the
        // facade itself + the ledger source file itself to reference
        // these fields.
        val readMethods = listOf("cashSol", "openCostBasisSol", "realizedPnlSol", "feesSol", "startingCashSol")
        val mainRoot = java.io.File("src/main/kotlin")
        val offenders = mutableListOf<String>()
        mainRoot.walk().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            if (f.name == "PaperAccountLedger6430.kt" || f.name == "PaperCapitalAuthority6577.kt") return@forEach
            val txt = f.readText()
            for (m in readMethods) {
                if (txt.contains("PaperAccountLedger6430.$m")) offenders += "${f.name}:$m"
            }
        }
        assertTrue(
            "V5.0.6604: read-only ledger reads must be unified via PaperCapitalAuthority6577 facade, offenders=$offenders",
            offenders.isEmpty()
        )
    }
}
