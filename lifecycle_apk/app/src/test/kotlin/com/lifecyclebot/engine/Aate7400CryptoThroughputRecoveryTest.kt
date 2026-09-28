package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7400CryptoThroughputRecoveryTest {
    private fun src(relative: String): String =
        File("src/main/kotlin/com/lifecyclebot/$relative").readText()

    @Test
    fun crypto_candidates_are_not_retired_for_missing_one_top25_window() {
        val s = src("perps/CryptoAltTrader.kt")
        val block = s.substringAfter("V5.0.7400 — ranked-out is NOT terminal")
            .substringBefore("for ((signalIndex6567")
        assertTrue(block.contains("markEvaluationProgress6570"))
        assertFalse(block.contains("markEvaluationDisposition6567"))
    }

    @Test
    fun learned_crypto_discipline_shapes_but_does_not_hard_veto_live() {
        val s = src("perps/CryptoAltTrader.kt")
        val block = s.substringAfter("if (learnedDisciplineVeto6644 && !isPaperMode.get())")
            .substringBefore("return candidate.canEnterFdg")
        assertTrue(block.contains("CRYPTO_DISCIPLINE_SOFT_SHAPED_7400"))
        assertFalse(block.contains("return false"))
    }

    @Test
    fun crypto_raw_size_does_not_die_before_canonical_sizer() {
        val s = src("perps/CryptoAltTrader.kt")
        assertTrue(s.contains("CRYPTO_PRECANONICAL_DUST_DEFERRED_TO_SIZER_7400"))
        val beforeSizer = s.substringBefore("CanonicalSizingBridge6532.resolve")
        assertFalse(beforeSizer.contains("PRE_SUBMIT_SIZE_BELOW_FLOOR"))
    }

    @Test
    fun portfolio_guards_read_the_sealed_canonical_size() {
        val s = src("perps/CryptoAltTrader.kt")
        val afterSizer = s.substringAfter("val finalSize = altSizingRes.finalSizeSol")
        assertTrue(afterSizer.contains("totalRisk7400 + finalSize"))
        assertTrue(afterSizer.contains("WalletPositionLock.canOpen(\"CryptoAlt\", finalSize"))
    }

    @Test
    fun meme_fdg_uses_fluid_floor_without_7399_cohort_hard_gate() {
        val s = src("engine/FinalDecisionGate.kt")
        assertFalse(s.contains("SNIPER_S0_10_NEG_EV_FLOOR_7399"))
        assertFalse(s.contains("sniperLowRescueAllowed7399"))
        assertTrue(s.contains("effectiveEntryScore7292 < canonicalFloor7266"))
    }
}
