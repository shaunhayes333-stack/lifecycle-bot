package com.lifecyclebot.engine

import com.lifecyclebot.perps.crypto.brain.CryptoBehavior
import com.lifecyclebot.perps.crypto.brain.CryptoFluidLearning
import com.lifecyclebot.perps.crypto.brain.CryptoLosingPatternMemory
import com.lifecyclebot.perps.crypto.brain.CryptoTacticSwitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Simulated closes can train research, but cannot silently control live entry, sizing or exits. */
class Aate8832CryptoModeAuthorityTest {
    @Test fun paper_loser_bucket_never_makes_the_live_tier_shadow_only() {
        val tier = "AATE_8832_MODE_LOSS"
        repeat(25) { CryptoLosingPatternMemory.record(tier, 65, -20.0, isPaper = true) }
        assertTrue("paper may learn to avoid its own bucket",
            CryptoLosingPatternMemory.recommendedShadowOnly(tier, 65, isPaper = true))
        assertFalse("paper losses cannot veto live", CryptoLosingPatternMemory.recommendedShadowOnly(tier, 65))
        assertEquals(1.0, CryptoLosingPatternMemory.recommendedSizeMult(tier, 65), 0.0)
        assertTrue(CryptoLosingPatternMemory.recommendedSizeMult(tier, 65, isPaper = true) < 1.0)
    }

    @Test fun paper_tactic_rotation_cannot_change_the_live_entry_tactic() {
        val tier = "AATE_8832_MODE_TACTIC"
        repeat(6) { CryptoTacticSwitcher.onOutcome(tier, 65, false,
            CryptoTacticSwitcher.Tactic.MOMENTUM, isPaper = true) }
        assertEquals(CryptoTacticSwitcher.Tactic.PULLBACK,
            CryptoTacticSwitcher.activeTacticFor(tier, 65, isPaper = true))
        assertEquals(CryptoTacticSwitcher.Tactic.MOMENTUM,
            CryptoTacticSwitcher.activeTacticFor(tier, 65))
    }

    @Test fun paper_close_cannot_mature_live_entry_floors_or_alter_live_tilt() {
        val liveFloor = CryptoFluidLearning.getSpotScoreThreshold()
        val liveTp = CryptoFluidLearning.getSpotTpPct()
        val liveN = CryptoFluidLearning.liveTradeCount()
        val paperN = CryptoFluidLearning.paperTradeCount()
        val sentiment = CryptoBehavior.getScoreAdjustment()
        val liveCrossConfidence = CryptoFluidLearning.getCrossLearnedConfidence(50.0)
        val tilt = CryptoBehavior.isTiltProtectionActive()

        CryptoFluidLearning.recordPaperTrade(false, -20.0)
        CryptoBehavior.recordTrade(-90.0, "SIM_LOSS", "AATE8832", isPaper = true)
        assertEquals(liveN, CryptoFluidLearning.liveTradeCount())
        assertEquals(paperN + 1, CryptoFluidLearning.paperTradeCount())
        assertEquals(liveFloor, CryptoFluidLearning.getSpotScoreThreshold())
        assertEquals(liveTp, CryptoFluidLearning.getSpotTpPct(), 0.0)
        assertEquals(liveCrossConfidence, CryptoFluidLearning.getCrossLearnedConfidence(50.0), 0.0)
        assertEquals(sentiment, CryptoBehavior.getScoreAdjustment())
        assertEquals(tilt, CryptoBehavior.isTiltProtectionActive())
    }

    @Test fun mode_separation_is_wired_through_the_only_crypto_brain() {
        val brain = File("src/main/kotlin/com/lifecyclebot/perps/crypto/brain/CryptoBrain.kt").readText()
        val trader = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val exits = File("src/main/kotlin/com/lifecyclebot/perps/crypto/brain/CryptoLaneExitTuner.kt").readText()
        assertTrue(brain.contains("CryptoLosingPatternMemory.record(tier, score, pnlPct, isPaper)"))
        assertTrue(brain.contains("if (!isPaper) CryptoLaneExitTuner.recordClose"))
        assertTrue(brain.contains("tacticName8535.trim().uppercase() }, isPaper)"))
        assertTrue(trader.contains("CryptoBrain.activeTactic(tier, score, paper7803)"))
        assertTrue(trader.contains("CryptoBrain.sizingMultiplier(\n                cryptoTier7244, signal.score, paper7803"))
        assertTrue(trader.contains("CryptoBrain.getSpotScoreFloor(scanPaper7803)"))
        assertTrue(trader.contains("CryptoBrain.getTpPct(updated.isSpot, updated.isPaper)"))
        assertTrue(trader.contains("CryptoBrain.progressForMode8832(paperStatic8832)"))
        assertTrue(exits.contains("if (!state.getBoolean(\"let.live_only_8832\", false)) return"))
    }
}
