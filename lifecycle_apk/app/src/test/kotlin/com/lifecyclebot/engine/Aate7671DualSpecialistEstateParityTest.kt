package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7671DualSpecialistEstateParityTest {
    @Test fun memeNativeBridgeStillContainsAtLeastTwelveLaneOpinions() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        SpecialistEstateParity7671.memeNativeLanes.forEach { lane ->
            assertTrue("missing meme native lane $lane", s.contains("out[\"$lane\"]"))
        }
        assertTrue(SpecialistEstateParity7671.snapshot().memeMinimumMet)
    }

    @Test fun cryptoNativeBrainEstateStillContainsAtLeastTwelveModules() {
        val root = File("src/main/kotlin/com/lifecyclebot/perps/crypto/brain")
        val files = root.listFiles()?.filter { it.extension == "kt" }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()
        SpecialistEstateParity7671.cryptoNativeModules.forEach { module ->
            assertTrue("missing crypto native module $module", module in files)
        }
        assertTrue(SpecialistEstateParity7671.snapshot().cryptoMinimumMet)
    }

    @Test fun cryptoDeskIsTrackedSeparatelyFromTotalCryptoBrainEstate() {
        val s = File("src/main/kotlin/com/lifecyclebot/perps/CryptoLaneDesk7391.kt").readText()
        SpecialistEstateParity7671.cryptoDeskLanes.forEach { lane ->
            assertTrue("missing crypto desk lane $lane", s.contains("\"$lane\""))
        }
        assertTrue(SpecialistEstateParity7671.cryptoNativeModules.size >
            SpecialistEstateParity7671.cryptoDeskLanes.size)
    }

    @Test fun dormantSolanaArbIsNotMistakenForAHealthyCryptoSpecialist() {
        val arb = File("src/main/kotlin/com/lifecyclebot/v3/scoring/SolanaArbAI.kt").readText()
        assertTrue(arb.contains("report_only=true"))
    }
}
