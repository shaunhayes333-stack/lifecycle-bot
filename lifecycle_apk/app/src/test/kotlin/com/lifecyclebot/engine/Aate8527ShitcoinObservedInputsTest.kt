package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.ShitCoinTraderAI
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8527ShitcoinObservedInputsTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/" + path).readText()
    private fun evidence(migrated: Boolean = false, website: String? = null, bp: Double? = 65.0,
                         volatility: Double? = 40.0) =
        SpecialistInputParity8527.shitcoinEvidence8527(bp, volatility, 30.0, website,
            "https://x.com/observed_project", "https://t.me/observed_project", "measuredCreator", migrated)

    @Test fun absentCurveProgressIsNeutralAndOnlyObservedCompletionIsKnown() {
        val unknown = evidence()
        assertFalse(unknown.graduationObserved)
        assertEquals(0.0, unknown.graduationProgress, 0.0)
        val complete = evidence(migrated = true)
        assertTrue(complete.graduationObserved)
        assertEquals(100.0, complete.graduationProgress, 0.0)
    }

    @Test fun nativeAndEntryCannotInferGraduationFromMarketCapOrMetaProxy() {
        val shared = src("engine/SpecialistInputParity8527.kt")
        val body = shared.substringAfter("fun shitcoinEvidence8527(ts:").substringBefore("data class MoonshotInputs")
        assertTrue(body.contains("ts.tokenMap.migratedOrGraduated"))
        assertFalse(body.contains("curveProgress"))
        assertFalse(body.contains("lastMcap"))
        val bot = src("engine/BotService.kt")
        val native = src("engine/SpecialistBrainBridge7542.kt")
        assertFalse(bot.contains("ts.lastMcap / 69_000.0"))
        assertTrue(bot.contains("val graduationProgress = shitcoinEvidence8527.graduationProgress"))
        assertTrue(bot.contains("entryGraduationProgress = graduationProgress"))
        assertTrue(native.contains("graduationProgress=shitcoinEvidence8527.graduationProgress"))
    }

    @Test fun pairUrlAndMentionsCannotInventProjectWebsiteOrSocialPresence() {
        assertFalse(evidence(website = null).hasWebsite)
        assertFalse(evidence(website = " ").hasWebsite)
        assertTrue(evidence(website = "https://observed-project.example").hasWebsite)
        val empty = SpecialistInputParity8527.shitcoinEvidence8527(null, null, null, null, null, null, null, false)
        assertFalse(empty.hasTwitter)
        assertFalse(empty.hasTelegram)
        assertEquals(0, empty.socialScore)
        val shared = src("engine/SpecialistInputParity8527.kt")
        val body = shared.substringAfter("fun shitcoinEvidence8527(ts:").substringBefore("data class MoonshotInputs")
        assertFalse(body.contains("pairUrl"))
        assertFalse(body.contains("xMentions"))
        assertFalse(body.contains("telegramMentions"))
        assertTrue(body.contains("BirdeyeMetaDataProvider.peekCached(ts.mint)"))
    }

    @Test fun bundleConcentrationCannotBecomeDeveloperHoldings() {
        val observed = evidence()
        assertEquals("measuredCreator", observed.devWallet)
        assertEquals(0.0, observed.devHoldPct, 0.0)
        val bot = src("engine/BotService.kt")
        assertFalse(bot.contains("val devHoldPct = ts.safety.firstBlockSupplyPct"))
        assertTrue(bot.contains("devHoldPct = shitcoinEvidence8527.devHoldPct"))
        assertTrue(src("engine/SpecialistBrainBridge7542.kt").contains("devHoldPct=shitcoinEvidence8527.devHoldPct"))
    }

    @Test fun datedOrderflowAndActualVolatilityAreNotReplacedByRawPressureOrAtrUnits() {
        assertEquals(65.0, evidence().buyPressurePct, 0.0)
        assertEquals(40.0, evidence().volatility, 0.0)
        val unknown = evidence(bp = Double.NaN, volatility = Double.NaN)
        assertTrue(unknown.buyPressurePct.isNaN())
        assertEquals(50.0, evidence(bp = 50.0).buyPressurePct, 0.0)
        assertEquals(0.0, evidence(bp = 0.0).buyPressurePct, 0.0)
        assertEquals(0.0, unknown.volatility, 0.0)
        val shared = src("engine/SpecialistInputParity8527.kt")
        val body = shared.substringAfter("fun shitcoinEvidence8527(ts:").substringBefore("data class MoonshotInputs")
        assertTrue(body.contains("read.buyPressurePct, ts.volatility"))
        assertFalse(body.contains("avgAtr"))
        assertFalse(body.contains("lastBuyPressurePct"))
    }

    @Test fun nativeAndActualCallsConsumeEverySharedEvidenceField() {
        val bridge = src("engine/SpecialistBrainBridge7542.kt")
        val bot = src("engine/BotService.kt")
        val call = bot.substringAfter("var shitCoinSignal = laneDoor8105(").substringBefore("// V5.9.618")
        for (field in listOf("buyPressurePct", "volatility", "devWallet", "devHoldPct", "socialScore",
            "hasWebsite", "hasTwitter", "hasTelegram")) {
            assertTrue(field, bridge.contains(field + "=shitcoinEvidence8527." + field))
            assertTrue(field, call.contains(field + " = shitcoinEvidence8527." + field))
        }
        assertFalse(bot.contains("calculateSocialScore("))
        val bootstrap = bot.substringAfter("val rawShitcoinScore = calculateBootstrapScore(").substringBefore("val forceBootstrapEntry")
        assertTrue(bootstrap.contains("buyPressurePct = shitcoinEvidence8527.buyPressurePct"))
        assertTrue(bootstrap.contains("volatility = shitcoinEvidence8527.volatility"))
    }

    @Test(timeout = 10_000L) fun genuineObservedEarlyLaunchCanQualifyWithoutInventedCurveProgress() {
        val oldPaper = ShitCoinTraderAI.isPaperMode
        try {
            ShitCoinTraderAI.setTradingMode(true)
            val i = evidence(website = "https://observed-project.example")
            fun evaluate() = ShitCoinTraderAI.evaluate(
                mint = "ParityEarly8527", symbol = "ParityEarly8527", currentPrice = 1.0,
                marketCapUsd = 80_000.0, liquidityUsd = 25_000.0, topHolderPct = 8.0,
                // superseded momentum = 20.0 (V5.0.8555 §LANE_SWEEP: a +20 % print is the breakout the curve no longer pays
                // for; an observed early launch qualifies on its base, not its spike)
                buyPressurePct = i.buyPressurePct, momentum = 4.0, volatility = i.volatility,
                tokenAgeMinutes = 2.0, launchPlatform = ShitCoinTraderAI.LaunchPlatform.PUMP_FUN,
                devWallet = i.devWallet, devHoldPct = i.devHoldPct, bundlePct = 0.0,
                socialScore = i.socialScore, hasWebsite = i.hasWebsite, hasTwitter = i.hasTwitter,
                hasTelegram = i.hasTelegram, hasGithub = false, isDexBoosted = true,
                dexTrendingRank = 0, isCopyCat = false, graduationProgress = i.graduationProgress,
                recordEducationScores = false)
            val native = evaluate()
            val actual = evaluate()
            assertTrue(native.reason, native.shouldEnter)
            assertTrue(actual.reason, actual.shouldEnter)
            assertEquals(native.entryScore, actual.entryScore)
            assertEquals(native.confidence, actual.confidence)
            assertFalse(native.graduationImminent)
            assertFalse(actual.graduationImminent)
        } finally {
            ShitCoinTraderAI.setTradingMode(oldPaper)
        }
    }
}
