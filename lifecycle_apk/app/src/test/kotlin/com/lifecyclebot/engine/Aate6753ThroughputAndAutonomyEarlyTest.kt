package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.BleederLaneProbation6747
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.6753 — Operator "44 trades an hour" diagnostic Feb 2026.
 *
 * Multiple defects the operator surfaced from V5.0.6750 telemetry:
 *
 *   §REGIME_FLOOR_RELAXED — my own V5.0.6747 CHOP/DUMP +10 floor was
 *     the top single blocker (1480 rejects). Halved to +5 so the
 *     effective admission floor is 20 (still tighter than 15) but no
 *     longer strangles approved entries.
 *   §DAMPER_LOOSENED — V5.0.6747 1-in-8 sampling was killing 2,215
 *     admissions. Now 1-in-3 CHOP/DUMP, 1-in-2 DEAD. Preserves storm
 *     suppression without starving the learner.
 *   §PROBATION_FROM_TRADE_5 — BleederLaneProbation6747 MIN_WINDOW cut
 *     from 15 to 5 so autonomous adjustment fires from trade 5, not
 *     after 50 losing trades.
 *   §BUY_ZERO_QTY_FORCE_CLOSE — applyBuyFill with actualQtyRaw <= 0
 *     now stamps Lifecycle.CLOSED at promote-time so the 8 phantom
 *     slots the operator diagnosed cannot form in the first place.
 *   §PER_LANE_STAGE_INSTRUMENTATION — new STAGE_FDG_INTENT_*_6753
 *     and STAGE_TICKET_REJECT_*_6753 labels expose which stage kills
 *     BLUECHIP/SHITCOIN/CYCLIC admissions.
 */
class Aate6753ThroughputAndAutonomyEarlyTest {

    @Before
    fun reset() { BleederLaneProbation6747.resetForTest() }

    @Test
    fun `regime score-floor delta halved to 5`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/RegimeDetector.kt").readText()
        // CHOP / DUMP floors relaxed from +10 to +5.
        assertTrue(
            "CHOP score-floor delta must be +5 (halved from +10)",
            src.contains("Regime.CHOP         -> +5"),
        )
        assertTrue(
            "DUMP score-floor delta must be +5 (halved from +10)",
            src.contains("Regime.DUMP         -> +5"),
        )
    }

    @Test
    fun `probe damper sample rate loosened`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val damperIdx = src.indexOf("fun probeShouldEmit6747(")
        assertTrue("probeShouldEmit6747 must exist", damperIdx > 0)
        val body = src.substring(damperIdx, kotlin.math.min(damperIdx + 1200, src.length))
        assertTrue("CHOP damper sample-N must be 3L (loosened from 8L)",
            body.contains("RegimeDetector.Regime.CHOP -> 3L"))
        assertTrue("DUMP damper sample-N must be 3L (loosened from 8L)",
            body.contains("RegimeDetector.Regime.DUMP -> 3L"))
        assertTrue("DEAD damper sample-N must be 2L (loosened from 4L)",
            body.contains("RegimeDetector.Regime.DEAD -> 2L"))
    }

    @Test
    fun `bleeder probation fires from trade 5 not 15`() {
        // Behaviour + source contract.
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/BleederLaneProbation6747.kt").readText()
        assertTrue("MIN_WINDOW must be 5 (autonomous adjustment early)",
            src.contains("MIN_WINDOW   = 5"))
        // 5 straight losses → probation.
        repeat(5) { BleederLaneProbation6747.onTradeClosed("EXPRESS", -5.0) }
        assertTrue("5 losses must trigger probation immediately",
            BleederLaneProbation6747.isOnProbation("EXPRESS"))
        // Regular-size admission refused.
        val refused = BleederLaneProbation6747.evaluate("EXPRESS", 0.05)
        assertNotNull("regular admission refused at trade 5+", refused)
    }

    @Test
    fun `buy fill with zero raw quantity stamps CLOSED at promote-time`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        // Lifecycle branch on actualQtyRaw sign.
        assertTrue(
            "promotion lifecycle MUST branch on actualQtyRaw sign",
            src.contains("lifecycle = if (actualQtyRaw.signum() > 0) Lifecycle.OPEN else Lifecycle.CLOSED"),
        )
        assertTrue(
            "zero-qty force-close MUST emit a dedicated diagnostic label",
            src.contains("CANONICAL_BUY_ZERO_QTY_FORCE_CLOSE_6753"),
        )
    }

    @Test
    fun `per-lane stage instrumentation emits at intent publish and ticket reject`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "publishFdgIntent must emit STAGE_FDG_INTENT_* per lane",
            src.contains("STAGE_FDG_INTENT_") && src.contains("PUBLISHED_SIZED"),
        )
        assertTrue(
            "ticket reject path must emit STAGE_TICKET_REJECT_* per lane",
            src.contains("STAGE_TICKET_REJECT_"),
        )
    }
}
