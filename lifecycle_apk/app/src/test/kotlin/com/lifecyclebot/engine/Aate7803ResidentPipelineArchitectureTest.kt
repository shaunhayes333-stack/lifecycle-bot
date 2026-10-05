package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.SpecialistCandidateBooks7803
import com.lifecyclebot.perps.CryptoStrategyCandidateBooks7803
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Aate7803ResidentPipelineArchitectureTest {
    @Before fun reset() {
        SpecialistCandidateBooks7803.resetForTests()
        CryptoStrategyCandidateBooks7803.resetForTests()
    }

    @Test fun sameMintCanRemainResidentOnMultipleMemeSpecialists() {
        SpecialistCandidateBooks7803.publishHunt("MOONSHOT", "mint7803", "T")
        SpecialistCandidateBooks7803.publishHunt("DIP_HUNTER", "mint7803", "T")
        val lanes = SpecialistCandidateBooks7803.residentLanes("mint7803")
        assertTrue("MOONSHOT" in lanes)
        assertTrue("DIP_HUNTER" in lanes)
        assertEquals(2, lanes.size)
    }

    @Test fun memeArbitrationOnlySeesReadyProposals() {
        SpecialistCandidateBooks7803.markQualified("MOONSHOT","mintR","T",7803L,82.0,"q")
        SpecialistCandidateBooks7803.markQualified("QUALITY","mintR","T",7803L,79.0,"q")
        assertTrue(SpecialistCandidateBooks7803.readyFor("mintR",7803L).isEmpty())
        SpecialistCandidateBooks7803.markReady("QUALITY","mintR","T",7803L,83,81.0,"ready")
        val ready=SpecialistCandidateBooks7803.readyFor("mintR",7803L)
        assertEquals(setOf("QUALITY"), ready.keys)
    }

    @Test fun cryptoStrategiesRemainResidentUntilReadyReduction() {
        CryptoStrategyCandidateBooks7803.qualify("asset7803","AS","MOMENTUM",7803L,78,76,"q")
        CryptoStrategyCandidateBooks7803.qualify("asset7803","AS","PULLBACK",7803L,82,80,"q")
        assertNull(CryptoStrategyCandidateBooks7803.bestReady("asset7803",7803L))
        CryptoStrategyCandidateBooks7803.ready("asset7803","AS","PULLBACK",7803L,82,80,"ready")
        assertEquals("PULLBACK", CryptoStrategyCandidateBooks7803.bestReady("asset7803",7803L)?.strategy)
    }

    @Test fun sourcePinsResidentReadyMarkAndCryptoLifecycle() {
        fun src(path:String)=File("src/main/kotlin/com/lifecyclebot/$path").readText()
        val hunter=src("engine/market/LaneHunter7297.kt")
        val coordinator=src("engine/LaneExecutionCoordinator.kt")
        val auth=src("engine/TradeAuthorizer.kt")
        val gate=src("engine/ExecutableOpenGate.kt")
        val crypto=src("perps/CryptoAltTrader.kt")
        val cryptoDesk=src("perps/CryptoLaneDesk7391.kt")
        val helius=src("network/HeliusWebSocket.kt")
        val pnl=src("engine/OpenPnlSanity.kt")
        val toolkit=src("engine/ToolkitSignalSheet.kt")
        val cyclic=src("engine/CyclicTradeEngine.kt")
        val learning=src("perps/PerpsLearningBridge.kt")
        val voteStore=src("learning/LayerVoteStore.kt")
        val subscribers=src("engine/CanonicalSubscribers.kt")
        val specialistBridge=src("engine/SpecialistBrainBridge7542.kt")
        val tradeLessons=src("v4/meta/TradeLessonRecorder.kt")
        val adaptive=src("engine/AdaptiveLearningEngine.kt")
        val education=src("v3/scoring/EducationSubLayerAI.kt")
        val shitcoin=src("v3/scoring/ShitCoinTraderAI.kt")
        val moonshot=src("v3/scoring/MoonshotTraderAI.kt")
        val executor=src("engine/Executor.kt")

        assertTrue(hunter.contains("residentPicks7803"))
        assertTrue(hunter.contains("SpecialistCandidateBooks7803.publishHunt"))
        assertTrue(coordinator.contains("readyProposalScores7803"))
        assertTrue(coordinator.contains("priority(laneUpper) / 1000.0"))
        assertTrue(auth.contains("SpecialistCandidateBooks7803.markReady"))
        assertTrue(gate.contains("SPECIALIST_CANONICAL_MARK_READY_7803"))

        assertTrue(cryptoDesk.contains("fun qualifyAll7803"))
        assertTrue(crypto.contains("CryptoStrategyCandidateBooks7803.bestReady"))
        assertTrue(crypto.contains("\"strategy7803\" to signal.strategy7803"))
        assertTrue(crypto.contains("strategyFromReasons7803"))

        assertTrue(helius.contains("LinkedHashMap<String, Int?>"))
        assertTrue(helius.contains("sendUnsubscribe7803"))
        assertTrue(helius.contains("reconnectScheduler7803.schedule"))
        assertFalse(helius.substringAfter("private fun scheduleReconnect()").substringBeforeLast("}").contains("Thread.sleep"))

        assertTrue(pnl.contains("onPositionRebased7803(ts.mint, 0.0)"))

        // V5.0.7803 broader integrity audit.
        assertTrue(toolkit.contains("nativeReady7803"))
        assertTrue(toolkit.contains("NATIVE_SPECIALIST_REFUSED_7803"))
        assertFalse(cyclic.contains("LaneExecutionCoordinator.canRequestExecution(best.mint, \"CYCLIC\")"))
        assertTrue(crypto.contains("specialistLaneFromReasons7803"))
        assertTrue(crypto.contains("isPaper = paper"))
        assertTrue(learning.contains("ALT_PAPER_BRIDGE_SHADOW_ONLY_7803"))
        assertTrue(voteStore.contains("environment != com.lifecyclebot.engine.TradeEnvironment.LIVE"))
        assertTrue(subscribers.contains("environment = outcome.environment"))
        assertTrue(specialistBridge.contains("RuntimeModeAuthority.isPaper()"))
        assertTrue(tradeLessons.contains("PAPER::"))
        assertTrue(tradeLessons.contains("paperEvidence7803"))
        assertTrue(tradeLessons.contains("normalizeLessonMode7803"))
        assertTrue(tradeLessons.contains("val lesson = normalizeLessonMode7803(rawLesson)"))
        assertTrue(adaptive.contains("environment != TradeEnvironment.LIVE"))
        assertTrue(adaptive.contains("learnFromTrade(features, outcome.environment)"))
        assertTrue(education.contains("adaptiveEnvironment7803"))
        assertTrue(shitcoin.contains("executionRoute = if (pos.isPaper) \"PAPER\" else \"JUPITER_V6\""))
        assertTrue(moonshot.contains("executionRoute = if (pos.isPaperMode) \"PAPER\" else \"JUPITER_V6\""))
        assertTrue(executor.contains("environment = if (_fanoutIsPaper)"))
    }

    @Test fun sniperElectionAndNativeTraderSharePreIgnitionWindow() {
        val contract=File("src/main/kotlin/com/lifecyclebot/engine/LaneEntryContract6342.kt").readText()
        val sniper=File("src/main/kotlin/com/lifecyclebot/v3/scoring/ProjectSniperAI.kt").readText()
        assertTrue(contract.contains("SNIPER_LAUNCH_MAX_AGE_SECS_7393 = 180L"))
        assertTrue(contract.contains("Phase.PRE_IGNITION"))
        assertTrue(contract.contains("launch.distinctBuyers60s < 3"))
        assertTrue(sniper.contains("MAX_TOKEN_AGE_SECONDS = 180"))
        assertTrue(sniper.contains("Phase.PRE_IGNITION"))
    }

    @Test fun cryptoPendingRecoveryIsCausallyRepresented() {
        val authority=File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalAssetEntryContract6551.kt").readText()
        val recovery=File("src/main/kotlin/com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt").readText()
        val acceptance=File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSpineAcceptance6647.kt").readText()
        assertTrue(authority.contains("dispatchedPendingCount7803"))
        assertTrue(authority.contains("confirmRecoveredCryptoOpen7803"))
        assertTrue(recovery.contains("confirmRecoveredCryptoIntent7803"))
        assertTrue(acceptance.contains("CRYPTO_DISPATCH_WITHOUT_OPEN_OR_PENDING"))
        assertFalse(acceptance.contains("if (o.cryptoOpenConfirmed <= 0L) f += \"CRYPTO_OPEN_CONFIRMED_ZERO\""))
    }
}
