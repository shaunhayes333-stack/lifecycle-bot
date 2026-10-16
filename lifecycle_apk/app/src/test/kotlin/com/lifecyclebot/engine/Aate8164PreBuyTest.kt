package com.lifecyclebot.engine

import com.lifecyclebot.engine.learning.TacticSwitcher
import com.lifecyclebot.engine.market.LaunchTape7921
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.cortex.LearningEntryTruth8153
import com.lifecyclebot.engine.truth.LearningEpoch8164
import com.lifecyclebot.engine.truth.LiquidityTruth8037
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8164 — what happens before a buy: a learning reset stays reset, curve coins have a depth, and three closes do
 * not veto a lane's base.
 */
class Aate8164PreBuyTest {

    @After fun tearDown() { LearningEpoch8164.setForTest8164(0L) }

    private fun src(rel: String): String {
        val roots = listOf("src/main/kotlin/com/lifecyclebot", "app/src/main/kotlin/com/lifecyclebot", "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot")
        return roots.map { File(it, rel) }.first { it.exists() }.readText()
    }

    @Test fun closesSettledBeforeTheResetEpochAreHistoryNotEducation() {
        val epoch = 1_800_000_000_000L
        assertFalse(LearningEpoch8164.preEpoch8164(epoch - 1, 0L))          // never reset: nothing is excluded
        assertTrue(LearningEpoch8164.preEpoch8164(epoch - 1, epoch))        // settled before the reset
        assertFalse(LearningEpoch8164.preEpoch8164(epoch, epoch))           // at / after the reset: this run
        assertFalse(LearningEpoch8164.preEpoch8164(epoch + 60_000, epoch))
        assertFalse(LearningEpoch8164.preEpoch8164(0L, epoch))              // unknown settle time is not judged
    }

    @Test fun resetMarksTheEpochAndEveryLearningConsumerReadsIt() {
        val sweep = src("engine/truth/LearningResetSweep7781.kt")
        assertTrue(sweep.contains("LearningEpoch8164.mark8164(app)"))
        assertTrue(sweep.contains("LearningEpoch8164.load8164(app)"))
        val bridge = src("engine/truth/FinalizedBusConsumerBridge6465.kt")
        assertTrue(bridge.contains("consumer !in NON_LEARNING_CONSUMERS && LearningEpoch8164.preEpoch8164(env.atMs)"))
        // The epoch lives outside the sweep's own list, so the reset cannot erase its own start line.
        assertFalse(sweep.contains("\"aate_learning_epoch_8164\""))
    }

    @Test fun anActiveCurveHasADepthFromItsCap() {
        // $10,000 cap at $106 SOL = 94.3 SOL cap -> ~25.1 real SOL -> ~$2,661 of depth.
        val usd = LiquidityTruth8037.curveDepthUsd8164(10_000.0, 106.0)
        assertNotNull(usd)
        assertEquals(2_661.0, usd!!, 5.0)
        // Unknown price or cap: no depth is invented.
        assertNull(LiquidityTruth8037.curveDepthUsd8164(Double.NaN, 106.0))
        assertNull(LiquidityTruth8037.curveDepthUsd8164(10_000.0, 0.0))
        // Past what an active curve can carry (it completes near 85 real SOL): not a curve reading.
        assertNull(LiquidityTruth8037.curveDepthUsd8164(412.0 * 106.0, 106.0))
        assertNotNull(LiquidityTruth8037.curveDepthUsd8164(400.0 * 106.0, 106.0))
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("LiquidityTruth8037.hydrate8037(ts)"))
    }

    @Test fun threeLossesDoNotVetoALanesBase() {
        assertFalse(TacticSwitcher.tacticLosing8094(3, 0, -15.6))
        assertFalse(TacticSwitcher.tacticLosing8094(11, 1, -8.0))
        assertTrue(TacticSwitcher.tacticLosing8094(12, 1, -8.0))
    }

    // ── V5.0.8165: first buyers, before the pump ──

    private fun feat(ageMin: Double = 1.0, crowd: Int = 4, net: Double = 0.8, share: Double = 80.0, largest: Double = 30.0,
                     top3: Double = 60.0, dev: Boolean = false, fromFirst: Double = 20.0, fromPeak: Double = -5.0) =
        LaunchTape7921.Feat(ageMin, crowd, net, crowd / ageMin, share, largest, top3, dev, fromFirst, fromPeak, 1.0)

    @Test fun firstBuyersBeforeThePricePromotesInsideTheSniperWindow() {
        assertTrue(LaunchTape7921.earlyPass8165(feat()))
        // Inside the sniper's 180 s window only, and not before 20 s.
        assertFalse(LaunchTape7921.earlyPass8165(feat(ageMin = 0.2)))
        assertFalse(LaunchTape7921.earlyPass8165(feat(ageMin = 3.5)))
        // A crowd, not one wallet: >= 3 distinct buyers, none dominant, buying ahead of selling.
        assertFalse(LaunchTape7921.earlyPass8165(feat(crowd = 2)))
        assertFalse(LaunchTape7921.earlyPass8165(feat(largest = 55.0)))
        assertFalse(LaunchTape7921.earlyPass8165(feat(share = 55.0)))
        assertFalse(LaunchTape7921.earlyPass8165(feat(net = 0.1)))
        // Before the pump: already +60% over its first print is late; the dev selling is out.
        assertFalse(LaunchTape7921.earlyPass8165(feat(fromFirst = 75.0)))
        assertFalse(LaunchTape7921.earlyPass8165(feat(fromPeak = -30.0)))
        assertFalse(LaunchTape7921.earlyPass8165(feat(dev = true)))
        assertFalse(LaunchTape7921.earlyPass8165(feat(fromFirst = Double.NaN)))
        // The crowd-forming bar it complements is ignition: the same coin fails it this early.
        assertFalse(LaunchTape7921.priorPass(feat()))
    }

    @Test fun aPromotedLaunchIsAdmittedWithItsTapeCapAndCurveDepth() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("LaunchTape7921.lastPriceSol8165(mint)"))
        assertTrue(bot.contains("marketCapUsd = capUsd8165"))
        assertTrue(bot.contains("liquidityUsd = liqUsd8165"))
    }

    // ── V5.0.8165: the Cortex panel read "backwards" on one basis error ──

    @Test fun aMarkTheCoinsOwnCurveContradictsIsNotAMove() {
        val curve = 0.00001
        assertFalse(ForwardReturnLabeler7731.curveDisagrees8165(curve * 1.6, curve))   // a real move inside the read
        assertFalse(ForwardReturnLabeler7731.curveDisagrees8165(curve * 0.6, curve))
        assertTrue(ForwardReturnLabeler7731.curveDisagrees8165(curve * 106.0, curve))  // the SOL/USD factor
        assertTrue(ForwardReturnLabeler7731.curveDisagrees8165(curve * 23.4, curve))   // the 23x phantom
        assertTrue(ForwardReturnLabeler7731.curveDisagrees8165(curve / 3.0, curve))
        assertFalse(ForwardReturnLabeler7731.curveDisagrees8165(Double.NaN, curve))     // no witness, no verdict
        assertFalse(ForwardReturnLabeler7731.curveDisagrees8165(curve, 0.0))
        assertTrue(src("engine/BotService.kt").contains("PumpCurveKeys7269.markGraduated7392(mint)"))
    }

    @Test fun oneThousandXLabelDoesNotFlipTheTypicalCoin() {
        val refuse = List(140) { -20.0 } + listOf(180_000.0)
        val mean = refuse.sum() / refuse.size
        assertTrue(mean > 1_000.0)                                         // the panel's "backwards" number
        assertEquals(-20.0, LearningEntryTruth8153.median8165(refuse), 1e-9) // what refusing usually led to
        assertEquals(2.5, LearningEntryTruth8153.median8165(listOf(1.0, 4.0, Double.NaN, 2.0, 3.0)), 1e-9)
        assertTrue(LearningEntryTruth8153.median8165(emptyList()).isNaN())
    }
}
