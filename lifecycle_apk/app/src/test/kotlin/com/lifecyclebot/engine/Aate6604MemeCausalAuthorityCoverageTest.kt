package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * V5.0.6604 — Meme causal authority restoration coverage, amended by
 * V5.0.6681 where source-level causal binding supersedes the global-fallback
 * terminal-veto experiment from 6604.
 */
class Aate6604MemeCausalAuthorityCoverageTest {

    @Test
    fun aate6681_meme_terminal_authority_is_lane_own_only() {
        val headSrc = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/UnifiedPolicyHead.kt"
        ).readText()
        assertTrue(
            "V5.0.6681: terminal learned veto authority must come from the lane's own causal sample history",
            headSrc.contains("V5.0.6681 §LANE_OWN_TERMINAL_AUTHORITY") &&
                headSrc.contains("return h.trained >= AUTHORITY_AUTHORITATIVE")
        )
        assertFalse(
            "V5.0.6681: do not re-introduce 6604 global MEME authority as lane-own terminal authority",
            headSrc.contains("isMemeLane && trained >= MEME_GLOBAL_AUTHORITY_TRAINED_6604")
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
            "V5.0.6604/6605: MEME specialist election keeps the lane-own pWin floor gate",
            botSrc.contains("lanePWinBelowGate6604") &&
                botSrc.contains("SPECIALIST_MIN_PWIN_6604") &&
                botSrc.contains("SPECIALIST_MIN_PWIN_6604 = 0.45") &&
                botSrc.contains("MEME_SPECIALIST_PWIN_GATE_6604") &&
                botSrc.contains("laneOwnHeadAuthority6605")
        )
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
            "6737 per-mint marked inventory must reject implausible values without erasing paid basis",
            capitalSrc.contains("value <= basis * 100.0") &&
                capitalSrc.contains("HERO_OPENMV_PER_POSITION_QUARANTINE_6604") &&
                capitalSrc.contains("unpricedBasis += basis")
        )
        assertTrue(
            "6737 sum must include unpriced funded basis, with a separately labelled validated subset",
            capitalSrc.contains("val openMv = markedValue + missingProjectedBasis") &&
                capitalSrc.contains("authoritativeOpenMarketValueSol = authoritativeMv") &&
                capitalSrc.contains("valuationComplete = complete")
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
