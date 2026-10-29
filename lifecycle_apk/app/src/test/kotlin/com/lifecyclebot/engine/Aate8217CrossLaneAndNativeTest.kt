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
        assertTrue(a.contains("nativeQualified8213 = nativeOpinion8213 != null || nativeSetupBook8217 != null || runnerForming8260 != null,"))
        assertTrue(a.contains("SpecialistCandidateBooks7803.State.READY"))
        val f = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        // V5.0.8405 — DexScreener (sr=100%) now leads the off-watch batch over every chunk; the curve prices the pump mints it
        // missed; Jupiter (429-limited) is last. The 8217 invariant (a DexScreener read lands before marks are stored) holds.
        val body = f.substringAfter("private fun fetchOffWatchMarks7737(")
        // superseded by V5.0.8418 — on-curve mints are curve-read before DexScreener; the off-curve curve rung still follows it
        // (was: the first curve counter after the dex read).
        // superseded by V5.0.8418 — each chunk drops the mints the pair lane was just asked for (KeylessMarks8359.laneCovered8418),
        // so DexScreener is called on `ask8418`, not the raw chunk; the dex read still precedes the curve rung and the mark store.
        val dex = body.indexOf("DexscreenerApi().batchPriceFetch(ask8418)"); val curve = body.indexOf("offWatchCurvePriced7753.addAndGet(curve7753.size.toLong())")
        assertTrue(dex in 1 until curve)
        assertTrue("dex read lands before marks are stored", dex < body.indexOf("for ((m, px) in got) offWatchMarks7737[m] = px to at"))
    }
}
