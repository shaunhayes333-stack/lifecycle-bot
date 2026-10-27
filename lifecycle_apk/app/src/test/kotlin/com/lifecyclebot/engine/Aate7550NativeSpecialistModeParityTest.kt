package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7550NativeSpecialistModeParityTest {
    private fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/"+path).readText()

    @Test fun quality_has_one_age_rule_and_real_sizing_mode() {
        val s=src("v3/scoring/QualityTraderAI.kt")
        val eval=s.substringAfter("fun evaluate(").substringBefore("fun checkExit(")
        assertFalse(eval.contains("isPaperMode && learningProgress < 0.5"))
        assertTrue(eval.contains("MIN_AGE_MINUTES_BOOTSTRAP"))
        assertTrue(eval.contains("paperMode = isPaperMode"))
        assertFalse(eval.contains("paperMode = true,"))
    }

    @Test fun moonshot_native_admission_is_mode_neutral() {
        val s=src("v3/scoring/MoonshotTraderAI.kt")
        val score=s.substringAfter("fun scoreToken(").substringBefore("fun openPosition")
        // V5.0.8400 — the RC floor became a graded penalty on the fluid score (mode-neutral, no isPaperMode read);
        // only a confirmed rug (score 0) is refused. The 7550 invariant (one RC reading for both modes) is preserved.
        assertTrue(score.contains("if (rugcheckScore == 0) {"))
        assertTrue(score.contains("val rcPenalty8400 = rugcheckSoftPenalty8400(rugcheckScore)"))
        assertFalse(score.contains("val minRcScore = if (pendingRc) 1 else 15"))
        assertFalse(score.substringAfter("val pendingRc").substringBefore("SCORING").contains("isPaperMode"))
        // V5.0.7791 superseded the 7550 score-floor parity: paper MOONSHOT is an exploratory tail scanner
        // (Aate7791MoonshotAuthorityRestorationTest asserts the paper 20/28 floors); the RC floor stays mode-neutral.
    }

    @Test fun cashgen_uses_same_liquidity_pond() {
        val s=src("v3/scoring/CashGenerationAI.kt")
        val eval=s.substringAfter("fun evaluate(").substringBefore("fun addPosition")
        assertTrue(eval.contains("val learnedTreasuryMinLiq = 10_000.0"))
        assertFalse(eval.contains("if (isPaperMode) 5_000.0 else 10_000.0"))
    }

    @Test fun dip_reentry_and_recovery_shape_are_mode_neutral() {
        val s=src("v3/scoring/DipHunterAI.kt")
        assertTrue(s.contains("private const val RE_DIP_COOLDOWN_MS = 2L * 60L * 60_000L"))
        val eval=s.substringAfter("fun evaluate(").substringBefore("fun openDip(")
        assertTrue(eval.contains("val dailyLossRecoveryProbe = dailyPnl <= -DAILY_MAX_LOSS_SOL"))
        assertTrue(eval.contains("val redipCooldown = RE_DIP_COOLDOWN_MS"))
        assertFalse(eval.contains("PAPER_RE_DIP_COOLDOWN_MS"))
        assertFalse(eval.contains("LIVE_RE_DIP_COOLDOWN_MS"))
    }
}
