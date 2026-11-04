package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8527NativeEntryEvidenceParityTest {
    private fun src(name: String) = File("src/main/kotlin/com/lifecyclebot/" + name).readText()
    private fun read(momentum: Double?) = SpecialistMarketInputs8522.Read(
        NativeHistory8521.Read(listOf(100.0, 95.0), listOf(100.0, 95.0), 100.0, 1.0, emptyList()),
        NativeLaneAge8521.Evidence(2.0, 2.0), 95.0, 80.0, -5.0, momentum,
        ContinuationRead8348.Continuation8348(-5.0, ContinuationRead8348.Source8348.FROM_TAPE), 80, 70)

    @Test fun sameQualifiedReadKeepsNegativeMomentumAtActualDoor() {
        val qualified = read(-5.0)
        val native = SpecialistInputParity8527.shitcoinInputs8527(qualified, 0.0)
        val actual = SpecialistInputParity8527.shitcoinInputs8527(qualified, 0.0)
        assertEquals(native, actual)
        assertEquals(-5.0, actual.momentumPct, 0.0)
        assertEquals(0.0, SpecialistInputParity8527.shitcoinInputs8527(read(null), 0.0).momentumPct, 0.0)
        assertEquals(0.0, SpecialistInputParity8527.shitcoinInputs8527(read(Double.NaN), 0.0).momentumPct, 0.0)
    }

    @Test fun unknownOrZeroBundleCannotBecomeSyntheticTenFiftyOrEightyPercent() {
        for (unobserved in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 0.0)) {
            assertEquals(0.0, SpecialistInputParity8527.shitcoinInputs8527(read(-5.0), unobserved).bundlePct, 0.0)
        }
        // Actual measured high concentration is retained, never neutralised.
        assertEquals(80.0, SpecialistInputParity8527.shitcoinInputs8527(read(-5.0), 80.0).bundlePct, 0.0)
    }

    @Test fun unavailableVolumeCannotReceiveTheV3TotalsVolumeBonus() {
        val unknown = SpecialistInputParity8527.moonshotInputs8527(80.0, false, "PRE_IGNITION")
        assertEquals(0, unknown.volumeScore)
        assertEquals(0, SpecialistInputParity8527.moonshotInputs8527(Double.NaN, true, "IGNITION").volumeScore)
        assertEquals(0, SpecialistInputParity8527.moonshotInputs8527(0.0, true, "IGNITION").volumeScore)
        assertEquals(80, SpecialistInputParity8527.moonshotInputs8527(80.0, true, "IGNITION").volumeScore)
    }

    @Test fun canonicalLaunchAndFadePhaseReachBothNativeAndActualScore() {
        for (phase in listOf("PRE_IGNITION", "IGNITION", "EXPANDING", "POST_PUMP_FADE")) {
            val native = SpecialistInputParity8527.moonshotInputs8527(0.0, false, phase)
            val actual = SpecialistInputParity8527.moonshotInputs8527(0.0, false, phase)
            assertEquals(native, actual)
            assertEquals(phase, actual.phase)
        }
        assertEquals("MATURE_OR_UNKNOWN", SpecialistInputParity8527.moonshotInputs8527(0.0, false, null).phase)
        assertEquals("MATURE_OR_UNKNOWN", SpecialistInputParity8527.moonshotInputs8527(0.0, false, "").phase)
    }

    @Test fun highBundleCategoryDoesNotInventDeveloperSelling() {
        assertFalse(SpecialistInputParity8527.namedDevSelling8527("HIGH", "BUNDLE_HIGH", "HOLDER_CONCENTRATION"))
        assertFalse(SpecialistInputParity8527.namedDevSelling8527("", "", ""))
        assertTrue(SpecialistInputParity8527.namedDevSelling8527("DEV_SELLING", "", ""))
        assertTrue(SpecialistInputParity8527.namedDevSelling8527("", "dev sell observed", ""))
        assertTrue(SpecialistInputParity8527.namedDevSelling8527("", "", "DEV_SELL_RISK"))
    }

    @Test fun defaultSafetyZeroCannotHideMeasuredConcentrationOrMintQualityBonus() {
        val measured = SpecialistInputParity8527.topHolderPct8527(null, 42.0, 0.0, 0L, 1_000L)
        val unknown = SpecialistInputParity8527.topHolderPct8527(null, null, 0.0, 0L, 1_000L)
        assertEquals(42.0, measured, 0.0)
        assertEquals(20.0, unknown, 0.0)
        assertFalse(measured < 15.0)
        assertFalse(unknown < 15.0)
        assertTrue(src("v3/scoring/QualityTraderAI.kt").contains("if (topHolderPct < 15) qualityScore += 10"))
    }

    @Test fun realObservedZeroAndNamedSafetyEvidenceRemainValid() {
        assertEquals(0.0, SpecialistInputParity8527.topHolderPct8527(0.0, 42.0, 90.0, 900L, 1_000L), 0.0)
        assertEquals(0.0, SpecialistInputParity8527.topHolderPct8527(null, 0.0, 90.0, 900L, 1_000L), 0.0)
        assertEquals(0.0, SpecialistInputParity8527.topHolderPct8527(null, null, 0.0, 900L, 1_000L), 0.0)
        assertEquals(90.0, SpecialistInputParity8527.topHolderPct8527(null, null, 90.0, 900L, 1_000L), 0.0)
        assertEquals(20.0, SpecialistInputParity8527.topHolderPct8527(null, null, 90.0, 1_001L, 1_000L), 0.0)
        assertEquals(20.0, SpecialistInputParity8527.topHolderPct8527(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 900L, 1_000L), 0.0)
    }

    @Test fun shitcoinNativeEntryAndBootstrapAllUseSameSignedReadAndMeasuredBundle() {
        val bridge = src("engine/SpecialistBrainBridge7542.kt")
        val bot = src("engine/BotService.kt")
        assertTrue(bridge.contains("SpecialistInputParity8527.shitcoinInputs8527(marketInputs8522, ts.safety.firstBlockSupplyPct)"))
        assertTrue(bridge.contains("momentum=shitcoinInputs8527.momentumPct"))
        assertTrue(bridge.contains("bundlePct=shitcoinInputs8527.bundlePct"))
        assertTrue(bot.contains("val shitcoinMarketInputs8527 = SpecialistMarketInputs8522.forCycle8522(cycleSheet8522, ts)"))
        assertTrue(bot.contains("shitcoinMarketInputs8527, ts.safety.firstBlockSupplyPct)"))
        val actual = bot.substringAfter("var shitCoinSignal = laneDoor8105(").substringBefore("val forceBootstrapEntry")
        assertEquals(2, Regex("momentum = shitcoinInputs8527\\.momentumPct").findAll(actual).count())
        assertTrue(actual.contains("bundlePct = shitcoinInputs8527.bundlePct"))
        assertFalse(actual.contains("momentum = ts.momentum"))
        assertFalse(bot.contains("val bundlePct = if (ts.safety.bundleRisk"))
        // Entry attribution must retain the same measured bundle read; a removed local previously broke compilation.
        assertEquals(2, Regex("bundlePct = shitcoinInputs8527\\.bundlePct,").findAll(bot).count())
        val position = bot.substringAfter("var shitCoinSignal = laneDoor8105(")
            .substringAfter("com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ShitCoinPosition(")
            .substringBefore("entryGraduationProgress = graduationProgress")
        assertTrue(position.contains("bundlePct = shitcoinInputs8527.bundlePct,"))
        assertFalse(bot.contains("bundlePct = bundlePct,"))

    }

    @Test fun moonshotBothCallSitesUseObservedVolumeAndCanonicalPhaseAndCacheThem() {
        val bridge = src("engine/SpecialistBrainBridge7542.kt")
        val bot = src("engine/BotService.kt")
        val shared = src("engine/SpecialistInputParity8527.kt")
        assertTrue(bridge.contains("val moonshotInputs8527 = SpecialistInputParity8527.moonshotInputs8527(ts)"))
        assertTrue(bridge.contains("moonshotInputs8527.volumeScore,bp,rug,v3.toDouble(),v3c.toDouble(),moonshotInputs8527.phase"))
        val actual = bot.substringAfter("var moonshotScore = laneDoor8105(").substringBefore("// V5.9.618")
        assertTrue(actual.contains("volumeScore = moonshotInputs8527.volumeScore"))
        assertTrue(actual.contains("phase = moonshotInputs8527.phase"))
        assertFalse(actual.contains("phase = ts.phase"))
        assertFalse(bot.contains("else ts.lastV3Score ?: 20"))
        assertTrue(shared.contains("V3Adapter.dataKnowledge7327(ts).volume"))
        assertTrue(shared.contains("LaunchPhaseAuthority7401.snapshot(ts).phase.name"))
        assertTrue(bridge.substringAfter("private fun fp(").substringBefore("fun handoffScope8180").contains("SpecialistInputParity8527.moonshotInputs8527(ts)"))
    }

    @Test fun dipBothCallSitesUseNamedDeveloperEvidenceWithoutRemovingNativeDangerGate() {
        val bridge = src("engine/SpecialistBrainBridge7542.kt")
        val bot = src("engine/BotService.kt")
        assertTrue(bridge.contains("val devSelling = SpecialistInputParity8527.namedDevSelling8527(ts)"))
        val actual = bot.substringAfter("private fun operatorDipAssessment8522(").substringBefore("private fun operatorChoice8522(")
        assertTrue(actual.contains("isDevSelling = SpecialistInputParity8527.namedDevSelling8527(ts)"))
        assertFalse(actual.contains("bundleRisk"))
        val dip = src("v3/scoring/DipHunterAI.kt")
        assertTrue(dip.contains("if (isDevSelling)"))
        assertTrue(dip.contains("DEV_SELLING"))
    }

    @Test fun allFourHolderConsumersShareObservedPrecedenceFromNativeToEntry() {
        val bridge = src("engine/SpecialistBrainBridge7542.kt")
        val bot = src("engine/BotService.kt")
        assertTrue(bridge.contains("val top = SpecialistInputParity8527.topHolderPct8527(ts)"))
        val adapter = bot.substringAfter("private fun laneTopHolderPct7809(").substringBefore("private fun cyclicPolicyAllows7809(")
        assertTrue(adapter.contains("SpecialistInputParity8527.topHolderPct8527(ts)"))
        for (call in listOf("QualityTraderAI.evaluate(", "BlueChipTraderAI.evaluate(", "CashGenerationAI.evaluate(")) {
            assertTrue(call, bot.substringAfter(call).substringBefore("\n        )").contains("topHolderPct = laneTopHolderPct7809(ts)"))
        }
        val shit = bot.substringAfter("var shitCoinSignal = laneDoor8105(").substringBefore("// V5.9.618")
        assertTrue(shit.contains("topHolderPct = laneTopHolderPct7809(ts)"))
    }
}
