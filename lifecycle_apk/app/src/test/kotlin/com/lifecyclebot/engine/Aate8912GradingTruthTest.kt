package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LabelMark8386
import com.lifecyclebot.engine.truth.LaunchpadMigration8911
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8912 — grading truth: a pump.fun curve read never prices (or witnesses) a coin that is already past the curve's
 * completion price, and a chain read of the coin's own price corroborates its own step (no "unproven jump" refusal).
 */
class Aate8912GradingTruthTest {
    private fun m(venue: String, src: String) = LabelMark8386("Coin8912pump", venue, 1.0, 1_000L, 1_000L, src)

    @Test fun theCurveCompletesNearEightyFiveSol() {
        // at SOL $100 the curve completes at ~4.11e-7 SOL (~$4.1e-5) per token
        assertTrue("past the completion price: a pool's price", LaunchpadMigration8911.offCurve8912(4.93e-5, 100.0))
        assertFalse("inside the curve", LaunchpadMigration8911.offCurve8912(3.0e-5, 100.0))
        assertFalse("no SOL price: no verdict", LaunchpadMigration8911.offCurve8912(4.93e-5, 0.0))
        assertTrue(LaunchpadMigration8911.curveMark8912(m("pump.curve", "DEX_PAIR")))
        assertTrue(LaunchpadMigration8911.curveMark8912(m("", "CURVE_ACCOUNT")))
        assertTrue(LaunchpadMigration8911.curveMark8912(m("", "PUMP_PORTAL_TRADE_WS_7278")))
        assertFalse("a pool vault read is the pool, not the curve", LaunchpadMigration8911.curveMark8912(m("PoolAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "PUMPSWAP_VAULT_WS_8018")))
    }

    @Test fun aGraduatedCoinsCurveReadIsDead() {
        LaunchpadMigration8911.resetForTest8911()
        assertFalse(LaunchpadMigration8911.deadCurve8912("Coin8912pump", 1e-9, m("pump.curve", "CURVE_ACCOUNT")))
        LaunchpadMigration8911.markForTest8911("Coin8912pump")
        assertTrue(LaunchpadMigration8911.deadCurve8912("Coin8912pump", 1e-9, m("pump.curve", "CURVE_ACCOUNT")))
        assertFalse("a pool read of the same coin is its price", LaunchpadMigration8911.deadCurve8912("Coin8912pump", 1e-9, m("PoolAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "DEX_PAIR")))
        LaunchpadMigration8911.resetForTest8911()
    }

    @Test fun wired() {
        val l = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        val tick = l.substring(l.indexOf("fun tick(priceFor0:"))
        val dead = tick.indexOf("if (LaunchpadMigration8911.deadCurve8912(o.mint, o.entryPrice, mark8386)) {")
        assertTrue("before the curve can contradict or hold it", dead in 0 until tick.indexOf("if (curveContradicts8165(o.mint, px, nowMs, mark8386.observedAtMs)) {"))
        assertTrue(l.contains("if (LaunchpadMigration8911.offCurve8912(markUsd, solUsd)) return 0"))
        assertTrue(l.contains("val jumpHigh8912 = if (LabelEvidence8386.independent8418(mark8386)) Double.POSITIVE_INFINITY else o.entryPrice * (1.0 + o.peakPct / 100.0)"))
        assertTrue(l.contains("if (!PositionDisplayTruth8078.highStepOk8133(o.mint, o.entryPrice, jumpHigh8912, px, \"FORWARD_LABEL\")) {"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 100. Grading truth: the dead curve and the chain-read step (8912)"))
    }
}
