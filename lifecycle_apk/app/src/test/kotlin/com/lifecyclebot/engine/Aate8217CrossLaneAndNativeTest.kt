package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 8217 — one lane's chart read is never a token-wide judgement; native qualification survives a cache miss; lost labels get a DEX read. */
class Aate8217CrossLaneAndNativeTest {
    @Test fun chartPhaseIsNotTokenJudgementButTokenFactsStillAre() {
        assertFalse(SpecialistOwnership7951.refusalIsJudgement8136("SHAPE_DISTRIBUTION_PHASE_SHITCOIN", fdg = true))
        assertFalse(SpecialistOwnership7951.refusalIsJudgement8136("SETUP_DEVELOPING_WAIT", fdg = false))
        assertTrue(SpecialistOwnership7951.refusalIsJudgement8136("HOLDER_DISTRIBUTION top10=62%", fdg = true))
        assertTrue(SpecialistOwnership7951.refusalIsJudgement8136("DEV_SELL_FROM_TRADE_STREAM_7743", fdg = false))
        assertTrue(SpecialistOwnership7951.refusalIsJudgement8136("RUG_SIGNAL", fdg = false))
        assertTrue(SpecialistOwnership7951.refusalIsJudgement8136("LIQUIDITY_TOO_LOW", fdg = true))
    }

    @Test fun nativeBookAndDexLabelFallbackAreWired() {
        val a = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(a.contains("nativeQualified8213 = nativeOpinion8213 != null || nativeSetupBook8217 != null,"))
        assertTrue(a.contains("SpecialistCandidateBooks7803.State.READY"))
        val f = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        val curve = f.indexOf("offWatchCurvePriced7753.addAndGet"); val dex = f.indexOf("DexscreenerApi().batchPriceFetch(missed8217)")
        assertTrue(curve in 1 until dex)
        assertTrue("dex read lands before marks are stored", dex < f.indexOf("for ((m, mark) in got) offWatchMarks7737[m] = mark"))
    }
}
